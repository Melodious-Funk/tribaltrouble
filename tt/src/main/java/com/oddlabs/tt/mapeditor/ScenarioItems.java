package com.oddlabs.tt.mapeditor;

import com.oddlabs.tt.model.RacesResources;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A level's {@link Scenario} as items for a shared session: the level's texts, each player, each object, area and
 * trigger, and the order of the triggers, each kept as the bytes it is written as under a key of its own. Two
 * scenarios are compared item by item, and an edit carries the items it changed as they are after it, so laying the
 * same edits in the same order gives every player the same scenario.
 *
 * <p>A key is the item's type in the high half and its id, or the player's number, in the low half.
 */
final class ScenarioItems {
    static final int HEADER = 0;
    static final int PLAYER = 1;
    static final int PLACEMENT = 2;
    static final int AREA = 3;
    static final int TRIGGER = 4;
    static final int ORDER = 5;

    static final long HEADER_KEY = key(HEADER, 0);
    static final long ORDER_KEY = key(ORDER, 0);

    /** What laying items does besides changing the scenario: the objects and tribes shown follow. */
    interface Shown {
        void placementRemoved(Scenario.@NonNull Placement placement);

        void placementAdded(Scenario.@NonNull Placement placement);

        /** A player's taking part or tribe changed. */
        void playerChanged(int player);
    }

    private ScenarioItems() {
    }

    static long key(int type, int id) {
        return (long) type << 32 | (id & 0xFFFF_FFFFL);
    }

    static int type(long key) {
        return (int) (key >>> 32);
    }

    static int id(long key) {
        return (int) key;
    }

    /** Every item of a scenario, by key. */
    static @NonNull Map<@NonNull Long, byte @NonNull []> of(@NonNull Scenario scenario) {
        Map<Long, byte[]> items = new HashMap<>();
        items.put(HEADER_KEY, write(out -> {
            out.writeUTF(scenario.title);
            out.writeUTF(scenario.briefing);
            out.writeUTF(scenario.objective);
        }));
        for (int i = 0; i < Scenario.NUM_PLAYERS; i++) {
            Scenario.PlayerSetup player = scenario.players[i];
            items.put(key(PLAYER, i), write(out -> {
                out.writeBoolean(player.enabled);
                out.writeByte(player.race);
                out.writeByte(player.team);
                out.writeByte(player.role.ordinal());
            }));
        }
        for (Scenario.Placement placement : scenario.placements) {
            items.put(key(PLACEMENT, placement.id()), write(out -> {
                out.writeByte(placement.player());
                out.writeByte(placement.kind().ordinal());
                out.writeShort(placement.x());
                out.writeShort(placement.y());
            }));
        }
        for (Scenario.Area area : scenario.areas) {
            items.put(key(AREA, area.id), write(out -> {
                out.writeUTF(area.name);
                out.writeFloat(area.x);
                out.writeFloat(area.y);
                out.writeFloat(area.radius);
            }));
        }
        for (Scenario.Trigger trigger : scenario.triggers) {
            items.put(key(TRIGGER, trigger.id), write(out -> {
                out.writeUTF(trigger.name);
                out.writeBoolean(trigger.active);
                out.writeBoolean(trigger.repeat);
                out.writeByte(trigger.difficulties);
                trigger.condition.write(out);
                out.writeInt(trigger.actions.size());
                for (Step action : trigger.actions)
                    action.write(out);
            }));
        }
        items.put(ORDER_KEY, write(out -> {
            out.writeInt(scenario.triggers.size());
            for (Scenario.Trigger trigger : scenario.triggers)
                out.writeInt(trigger.id);
        }));
        return items;
    }

    /**
     * Lays items into a scenario: each replaces what has its key, or takes it away when null. Areas and players are
     * changed in place, as a drag or a window may be holding them. Every item must have been {@link #check}ed.
     *
     * @param order the trigger ids in order, to put the triggers in, or null to leave their order
     */
    static void apply(@NonNull Scenario scenario, @NonNull Map<@NonNull Long, byte @Nullable []> items,
            int @Nullable [] order, @NonNull Shown shown) {
        for (Map.Entry<Long, byte[]> item : items.entrySet()) {
            try {
                applyItem(scenario, type(item.getKey()), id(item.getKey()), item.getValue(), shown);
            } catch (IOException e) {
                throw new IllegalStateException("An item was laid unchecked", e);
            }
        }
        if (order != null)
            orderTriggers(scenario, order);
    }

    private static void applyItem(@NonNull Scenario scenario, int type, int id, byte @Nullable [] data,
            @NonNull Shown shown) throws IOException {
        switch (type) {
            case HEADER -> {
                if (data == null)
                    return;
                String[] texts = readHeader(data);
                scenario.title = texts[0];
                scenario.briefing = texts[1];
                scenario.objective = texts[2];
            }
            case PLAYER -> {
                if (data == null)
                    return;
                Scenario.PlayerSetup setup = readPlayer(id, data);
                Scenario.PlayerSetup player = scenario.players[id];
                boolean shown_changed = player.enabled != setup.enabled || player.race != setup.race;
                player.enabled = setup.enabled;
                player.race = setup.race;
                player.team = setup.team;
                player.role = setup.role;
                if (shown_changed)
                    shown.playerChanged(id);
            }
            case PLACEMENT -> {
                Scenario.Placement old = scenario.findPlacement(id);
                if (old != null) {
                    scenario.placements.remove(old);
                    shown.placementRemoved(old);
                }
                if (data != null) {
                    Scenario.Placement placement = readPlacement(id, data);
                    scenario.placements.add(placement);
                    shown.placementAdded(placement);
                }
                scenario.sawId(id);
            }
            case AREA -> {
                Scenario.Area old = scenario.findArea(id);
                if (data == null) {
                    if (old != null)
                        scenario.areas.remove(old);
                } else {
                    Scenario.Area area = readArea(id, data);
                    if (old != null) {
                        old.name = area.name;
                        old.x = area.x;
                        old.y = area.y;
                        old.radius = area.radius;
                    } else {
                        scenario.areas.add(area);
                    }
                }
                scenario.sawId(id);
            }
            case TRIGGER -> {
                Scenario.Trigger old = scenario.findTrigger(id);
                if (data == null) {
                    if (old != null)
                        scenario.triggers.remove(old);
                } else {
                    Scenario.Trigger trigger = readTrigger(id, data);
                    if (old != null)
                        scenario.triggers.set(scenario.triggers.indexOf(old), trigger);
                    else
                        scenario.triggers.add(trigger);
                }
                scenario.sawId(id);
            }
            default -> {
            }
        }
    }

    /** Puts the triggers in the given order, and those it leaves out after them by id, as every player does alike. */
    private static void orderTriggers(@NonNull Scenario scenario, int @NonNull [] order) {
        List<Scenario.Trigger> ordered = new ArrayList<>(scenario.triggers.size());
        for (int id : order) {
            Scenario.Trigger trigger = scenario.findTrigger(id);
            if (trigger != null && !ordered.contains(trigger))
                ordered.add(trigger);
        }
        List<Scenario.Trigger> rest = new ArrayList<>(scenario.triggers);
        rest.removeAll(ordered);
        rest.sort((a, b) -> Integer.compare(a.id, b.id));
        ordered.addAll(rest);
        scenario.triggers.clear();
        scenario.triggers.addAll(ordered);
    }

    /** Reads an item to check it, throwing if it is not one this game could have written. */
    static void check(long key, byte @NonNull [] data) throws IOException {
        int id = id(key);
        switch (type(key)) {
            case HEADER -> {
                if (id != 0)
                    throw new IOException("Bad level text item");
                readHeader(data);
            }
            case PLAYER -> {
                if (id < 0 || id >= Scenario.NUM_PLAYERS)
                    throw new IOException("Bad player " + id);
                readPlayer(id, data);
            }
            case PLACEMENT -> readPlacement(id, data);
            case AREA -> readArea(id, data);
            case TRIGGER -> readTrigger(id, data);
            case ORDER -> {
                if (id != 0)
                    throw new IOException("Bad trigger order item");
                readOrder(data);
            }
            default -> throw new IOException("Unknown item type " + type(key));
        }
    }

    /** The title, briefing and objective. */
    static @NonNull String @NonNull [] readHeader(byte @NonNull [] data) throws IOException {
        DataInputStream in = in(data);
        String title = in.readUTF();
        String briefing = in.readUTF();
        String objective = in.readUTF();
        return new String[]{title, briefing, objective};
    }

    static Scenario.@NonNull PlayerSetup readPlayer(int player, byte @NonNull [] data) throws IOException {
        DataInputStream in = in(data);
        boolean enabled = in.readBoolean();
        int race = in.readByte();
        int team = in.readByte();
        Scenario.Role[] roles = Scenario.Role.values();
        Scenario.Role role = roles[Math.clamp(in.readByte(), 0, roles.length - 1)];
        if (!RacesResources.isValidRace(race))
            race = RacesResources.RACE_NATIVES;
        // As when read from a file: the first player is always the one playing, and only that one.
        if (player == 0) {
            enabled = true;
            role = Scenario.Role.HUMAN;
        } else if (role == Scenario.Role.HUMAN) {
            role = Scenario.Role.OPPONENT;
        }
        return new Scenario.PlayerSetup(enabled, race, Math.clamp(team, 0, Scenario.NEUTRAL_TEAM), role);
    }

    static Scenario.@NonNull Placement readPlacement(int id, byte @NonNull [] data) throws IOException {
        DataInputStream in = in(data);
        int player = Math.clamp(in.readByte(), 0, Scenario.NUM_PLAYERS - 1);
        ObjectKind kind = ObjectKind.of(in.readByte());
        return new Scenario.Placement(id, player, kind, in.readShort(), in.readShort());
    }

    static Scenario.@NonNull Area readArea(int id, byte @NonNull [] data) throws IOException {
        DataInputStream in = in(data);
        return new Scenario.Area(id, in.readUTF(), in.readFloat(), in.readFloat(), in.readFloat());
    }

    static Scenario.@NonNull Trigger readTrigger(int id, byte @NonNull [] data) throws IOException {
        DataInputStream in = in(data);
        String name = in.readUTF();
        boolean active = in.readBoolean();
        boolean repeat = in.readBoolean();
        int difficulties = in.readByte();
        Scenario.Trigger trigger = new Scenario.Trigger(id, name, Step.read(in, Scenario.VERSION));
        trigger.active = active;
        trigger.repeat = repeat;
        trigger.difficulties = difficulties & Scenario.Trigger.ALL_DIFFICULTIES;
        int actions = in.readInt();
        if (actions < 0 || actions > data.length)
            throw new IOException("Bad action count " + actions);
        for (int i = 0; i < actions; i++)
            trigger.actions.add(Step.read(in, Scenario.VERSION));
        return trigger;
    }

    /** The trigger ids in order. */
    static int @NonNull [] readOrder(byte @NonNull [] data) throws IOException {
        DataInputStream in = in(data);
        int count = in.readInt();
        if (count < 0 || count > data.length / 4)
            throw new IOException("Bad trigger count " + count);
        int[] ids = new int[count];
        for (int i = 0; i < count; i++)
            ids[i] = in.readInt();
        return ids;
    }

    private interface Writer {
        void write(@NonNull DataOutputStream out) throws IOException;
    }

    private static byte @NonNull [] write(@NonNull Writer writer) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            writer.write(out);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return bytes.toByteArray();
    }

    private static @NonNull DataInputStream in(byte @NonNull [] data) {
        return new DataInputStream(new ByteArrayInputStream(data));
    }
}
