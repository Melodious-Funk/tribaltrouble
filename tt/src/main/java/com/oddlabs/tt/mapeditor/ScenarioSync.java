package com.oddlabs.tt.mapeditor;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Keeps a campaign level's units, buildings, areas, triggers and texts in step with the others in a shared session,
 * as {@link SessionSync} keeps the island's heights and resources.
 *
 * <p>Besides the scenario as it is shown, this keeps the scenario as the session has it, as {@link ScenarioItems}:
 * every edit laid in the order the server put them, followed by this player's own edits that are on their way. An
 * item shown differently from that is one this player changed and has not sent yet, and {@link #take} turns those
 * into the next edit. Another player's edit goes into the session's scenario before this player's edits on their
 * way, and is shown wherever this player has nothing unsent.
 */
final class ScenarioSync {
    /** The scenario as the editor holds it. */
    interface Target {
        @NonNull
        Scenario getScenario();

        /** A count that goes up whenever anything in the scenario changes. */
        int getChangeCount();

        /**
         * Lays items another player's edit brought, as {@link ScenarioItems#apply} does, and shows them. Undo should
         * then leave them standing.
         */
        void applyShared(@NonNull Map<@NonNull Long, byte @Nullable []> items, int @Nullable [] order);
    }

    /** The items an edit changed, as they are after it; null for an item it took away. */
    record Op(@NonNull Map<@NonNull Long, byte @Nullable []> items) {
        // Version 2 carries triggers as Scenario version 3 keeps them.
        private static final int VERSION = 2;
        private static final int MAX_ITEMS = 1_000_000;
        // A trigger with many long dialogs is the largest item.
        private static final int MAX_ITEM_SIZE = 1 << 22;

        byte @NonNull [] encode() {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(new DeflaterOutputStream(bytes, new Deflater(Deflater.BEST_SPEED)))) {
                out.writeByte(VERSION);
                out.writeInt(items.size());
                for (Map.Entry<Long, byte[]> item : items.entrySet()) {
                    out.writeLong(item.getKey());
                    byte[] data = item.getValue();
                    out.writeInt(data != null ? data.length : -1);
                    if (data != null)
                        out.write(data);
                }
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            return bytes.toByteArray();
        }

        /** Reads an edit, checking every item in it. */
        static @NonNull Op decode(byte @NonNull [] data) throws IOException {
            try (var in = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(data)))) {
                if (in.readByte() != VERSION)
                    throw new IOException("Unknown scenario edit version");
                int count = in.readInt();
                if (count < 0 || count > MAX_ITEMS)
                    throw new IOException("Bad item count " + count);
                Map<Long, byte[]> items = new HashMap<>();
                for (int i = 0; i < count; i++) {
                    long key = in.readLong();
                    int length = in.readInt();
                    if (length < -1 || length > MAX_ITEM_SIZE)
                        throw new IOException("Bad item size " + length);
                    byte[] item = null;
                    if (length >= 0) {
                        item = new byte[length];
                        in.readFully(item);
                        ScenarioItems.check(key, item);
                    } else if (ScenarioItems.type(key) != ScenarioItems.PLACEMENT
                            && ScenarioItems.type(key) != ScenarioItems.AREA
                            && ScenarioItems.type(key) != ScenarioItems.TRIGGER) {
                                throw new IOException("Item " + Long.toHexString(key) + " cannot be taken away");
                            }
                    items.put(key, item);
                }
                return new Op(items);
            }
        }
    }

    private final @NonNull Target layer;
    // The scenario as the session has it, this player's own edits on their way included.
    private final Map<Long, byte[]> shared;
    // This player's edits sent and not yet acknowledged, oldest first.
    private final Deque<@NonNull Op> pending = new ArrayDeque<>();
    // The layer's change count when the last edit was taken.
    private int taken_at;

    /** Starts from the scenario as shown, which must be the scenario as the session has it. */
    ScenarioSync(@NonNull Target layer) {
        this.layer = layer;
        this.shared = ScenarioItems.of(layer.getScenario());
        this.taken_at = layer.getChangeCount();
    }

    /**
     * Takes what this player changed since the last edit as a new edit, to send. It is kept until acknowledged.
     *
     * @return the edit, or null when nothing changed
     */
    @Nullable
    Op take() {
        int changes = layer.getChangeCount();
        if (changes == taken_at)
            return null;
        taken_at = changes;
        Map<Long, byte[]> current = ScenarioItems.of(layer.getScenario());
        Map<Long, byte[]> items = new HashMap<>();
        for (Map.Entry<Long, byte[]> item : current.entrySet()) {
            if (!Arrays.equals(item.getValue(), shared.get(item.getKey())))
                items.put(item.getKey(), item.getValue());
        }
        for (Long key : shared.keySet()) {
            if (!current.containsKey(key))
                items.put(key, null);
        }
        if (items.isEmpty())
            return null;
        Op op = new Op(items);
        lay(op, null);
        pending.add(op);
        return op;
    }

    /** The server put this player's oldest edit on its way in the session's order. */
    void acknowledged() {
        pending.poll();
    }

    /** Lays another player's edit, which the server put before this player's edits still on their way. */
    void apply(@NonNull Op op) {
        Map<Long, byte[]> current = ScenarioItems.of(layer.getScenario());
        // Items this player changed and has not sent stay as they are: they go after the edit.
        Set<Long> unsent = new HashSet<>();
        for (Long key : op.items().keySet()) {
            if (!Arrays.equals(current.get(key), shared.get(key)))
                unsent.add(key);
        }
        lay(op, null);
        for (Op own : pending)
            lay(own, op.items().keySet());
        Map<Long, byte[]> shown = new HashMap<>();
        boolean triggers_changed = false;
        for (Long key : op.items().keySet()) {
            byte[] value = shared.get(key);
            if (unsent.contains(key) || Arrays.equals(current.get(key), value))
                continue;
            shown.put(key, value);
            triggers_changed |= ScenarioItems.type(key) == ScenarioItems.TRIGGER;
        }
        // The triggers follow the session's order, unless this player reordered them and has not sent it yet.
        int[] order = null;
        if ((shown.containsKey(ScenarioItems.ORDER_KEY) || triggers_changed)
                && !unsent.contains(ScenarioItems.ORDER_KEY)) {
            try {
                order = ScenarioItems.readOrder(shared.get(ScenarioItems.ORDER_KEY));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        shown.remove(ScenarioItems.ORDER_KEY);
        if (!shown.isEmpty() || order != null)
            layer.applyShared(shown, order);
    }

    /** Puts an edit's items into the session's scenario: all of them, or only those under the given keys. */
    private void lay(@NonNull Op op, @Nullable Set<Long> only) {
        for (Map.Entry<Long, byte[]> item : op.items().entrySet()) {
            if (only != null && !only.contains(item.getKey()))
                continue;
            if (item.getValue() != null)
                shared.put(item.getKey(), item.getValue());
            else
                shared.remove(item.getKey());
        }
    }
}
