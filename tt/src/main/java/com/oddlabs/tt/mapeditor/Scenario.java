package com.oddlabs.tt.mapeditor;

import com.oddlabs.matchmaking.MatchmakingServerInterface;
import com.oddlabs.tt.model.RacesResources;
import com.oddlabs.tt.player.campaign.CampaignState;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * What a campaign level adds to its island: the tribes taking part, the units and buildings they start with, named
 * areas, and the triggers that tell the story and decide the level.
 *
 * <p>Player 0 is the one playing; the others are computer players. Objects, areas and triggers carry ids that stay
 * the same while they are edited, so triggers can name them.
 */
final class Scenario {
    static final int NUM_PLAYERS = MatchmakingServerInterface.MAX_PLAYERS;

    // Version 2 gave trigger steps a building beside their object.
    private static final int VERSION = 2;
    private static final int MAX_ITEMS = 100_000;

    /** How a player is run. */
    enum Role {
        /** The one playing. */
        HUMAN("role_human"),
        /** A computer opponent building up and attacking, as good as the campaign's difficulty. */
        OPPONENT("role_opponent"),
        /** A computer player that roams but only attacks when a trigger sends it. */
        PASSIVE("role_passive"),
        /** A computer player that stays put, like guards or captives. */
        NEUTRAL("role_neutral");

        private final @NonNull String key;

        Role(@NonNull String key) {
            this.key = key;
        }

        @NonNull String getName() {
            return CampaignEditor.i18n(key);
        }
    }

    /** A tribe taking part. */
    static final class PlayerSetup {
        boolean enabled;
        /** {@link RacesResources#RACE_NATIVES} or {@link RacesResources#RACE_VIKINGS}. */
        int race;
        int team;
        @NonNull Role role;

        PlayerSetup(boolean enabled, int race, int team, @NonNull Role role) {
            this.enabled = enabled;
            this.race = race;
            this.team = team;
            this.role = role;
        }

        @NonNull PlayerSetup copy() {
            return new PlayerSetup(enabled, race, team, role);
        }
    }

    /** A unit or building a player starts with, at a grid cell. */
    record Placement(int id, int player, @NonNull ObjectKind kind, int x, int y) {
    }

    /** A named circle on the island, in meters. */
    static final class Area {
        final int id;
        @NonNull String name;
        float x;
        float y;
        float radius;

        Area(int id, @NonNull String name, float x, float y, float radius) {
            this.id = id;
            this.name = name;
            this.x = x;
            this.y = y;
            this.radius = radius;
        }

        boolean contains(float px, float py) {
            float dx = px - x;
            float dy = py - y;
            return dx * dx + dy * dy <= radius * radius;
        }
    }

    /** A condition to watch for and the actions to run once it holds. */
    static final class Trigger {
        static final int DIFFICULTY_EASY = 1;
        static final int DIFFICULTY_NORMAL = 2;
        static final int DIFFICULTY_HARD = 4;
        static final int ALL_DIFFICULTIES = DIFFICULTY_EASY | DIFFICULTY_NORMAL | DIFFICULTY_HARD;

        final int id;
        @NonNull String name;
        @NonNull Step condition;
        final @NonNull List<@NonNull Step> actions = new ArrayList<>();
        /** Whether it watches from the start, rather than once another trigger activates it. */
        boolean active = true;
        /** Whether it starts watching again after its actions have run. */
        boolean repeat;
        /** The campaign difficulties it is part of, as {@code DIFFICULTY_} bits. */
        int difficulties = ALL_DIFFICULTIES;

        Trigger(int id, @NonNull String name, @NonNull Step condition) {
            this.id = id;
            this.name = name;
            this.condition = condition;
        }

        /** A copy to edit, keeping the id. */
        @NonNull Trigger copy() {
            Trigger trigger = new Trigger(id, name, condition.copy());
            for (Step action : actions)
                trigger.actions.add(action.copy());
            trigger.active = active;
            trigger.repeat = repeat;
            trigger.difficulties = difficulties;
            return trigger;
        }

        /** Whether it is part of a campaign played at the given {@link CampaignState} difficulty. */
        boolean isFor(int campaign_difficulty) {
            int bit = switch (campaign_difficulty) {
                case CampaignState.DIFFICULTY_EASY -> DIFFICULTY_EASY;
                case CampaignState.DIFFICULTY_HARD -> DIFFICULTY_HARD;
                default -> DIFFICULTY_NORMAL;
            };
            return (difficulties & bit) != 0;
        }
    }

    @NonNull String title;
    /** Shown before the level starts. */
    @NonNull String briefing = "";
    /** The objective at the start, until a trigger changes it. */
    @NonNull String objective = "";
    final @NonNull PlayerSetup @NonNull [] players = new PlayerSetup[NUM_PLAYERS];
    final @NonNull List<@NonNull Placement> placements = new ArrayList<>();
    final @NonNull List<@NonNull Area> areas = new ArrayList<>();
    final @NonNull List<@NonNull Trigger> triggers = new ArrayList<>();
    private int next_id = 1;

    private Scenario(@NonNull String title) {
        this.title = title;
    }

    /** A new level: the player against one opponent, won by defeating every enemy. */
    static @NonNull Scenario createDefault(@NonNull String title) {
        Scenario scenario = new Scenario(title);
        scenario.players[0] = new PlayerSetup(true, RacesResources.RACE_VIKINGS, 0, Role.HUMAN);
        scenario.players[1] = new PlayerSetup(true, RacesResources.RACE_NATIVES, 1, Role.OPPONENT);
        for (int i = 2; i < NUM_PLAYERS; i++)
            scenario.players[i] = new PlayerSetup(false, RacesResources.RACE_NATIVES, 1, Role.OPPONENT);
        scenario.objective = CampaignEditor.i18n("default_objective");
        Trigger victory = new Trigger(scenario.newId(), CampaignEditor.i18n("default_victory_trigger"),
                Step.condition(ConditionKind.ENEMIES_DEFEATED));
        victory.actions.add(Step.action(ActionKind.VICTORY));
        scenario.triggers.add(victory);
        return scenario;
    }

    int newId() {
        return next_id++;
    }

    static @NonNull String playerName(int player) {
        return player == 0 ? CampaignEditor.i18n("player_you") : CampaignEditor.i18n("player_name", player + 1);
    }

    @Nullable Placement findPlacement(int id) {
        for (Placement placement : placements) {
            if (placement.id() == id)
                return placement;
        }
        return null;
    }

    @Nullable Area findArea(int id) {
        for (Area area : areas) {
            if (area.id == id)
                return area;
        }
        return null;
    }

    @Nullable Trigger findTrigger(int id) {
        for (Trigger trigger : triggers) {
            if (trigger.id == id)
                return trigger;
        }
        return null;
    }

    /** A new area's name: "Area" and the first free number. */
    @NonNull String newAreaName() {
        for (int n = 1; ; n++) {
            String name = CampaignEditor.i18n("area_name", n);
            boolean taken = false;
            for (Area area : areas)
                taken |= area.name.equals(name);
            if (!taken)
                return name;
        }
    }

    /**
     * What would spoil the level once played: the player with nothing to play with, tribes that count as beaten from
     * the start, or no way to win.
     */
    @NonNull List<@NonNull String> problems() {
        List<String> problems = new ArrayList<>();
        for (int i = 0; i < NUM_PLAYERS; i++) {
            if (!players[i].enabled)
                continue;
            boolean has_units = false;
            for (Placement placement : placements)
                has_units |= placement.player() == i && (placement.kind().isUnit()
                        || placement.kind().getGuardType() != -1);
            if (!has_units)
                problems.add(i == 0 ? CampaignEditor.i18n("problem_no_player_units")
                        : CampaignEditor.i18n("problem_no_units", playerName(i)));
        }
        boolean enemy = false;
        for (int i = 1; i < NUM_PLAYERS; i++)
            enemy |= players[i].enabled && players[i].team != players[0].team;
        if (!enemy)
            problems.add(CampaignEditor.i18n("problem_no_enemy"));
        boolean victory = false;
        for (Trigger trigger : triggers) {
            for (Step action : trigger.actions)
                victory |= ActionKind.of(action.kind) == ActionKind.VICTORY;
        }
        if (!victory)
            problems.add(CampaignEditor.i18n("problem_no_victory"));
        for (Trigger trigger : triggers) {
            ConditionKind condition = ConditionKind.of(trigger.condition.kind);
            checkStep(trigger, trigger.condition, true, condition.getName(), condition.getParams(),
                    isAreaOptional(true, trigger.condition), problems);
            for (Step action : trigger.actions) {
                ActionKind kind = ActionKind.of(action.kind);
                checkStep(trigger, action, false, kind.getName(), kind.getParams(),
                        isAreaOptional(false, action), problems);
                if (kind == ActionKind.DEPLOY_FROM)
                    checkDeploy(trigger, action, problems);
            }
        }
        return problems;
    }

    /**
     * Notes what a step names that is not set, gone, of the wrong kind, or a tribe not taking part.
     *
     * @param condition whether the step is the trigger's condition rather than one of its actions
     * @param area_optional whether leaving its area unset is fine, meaning anywhere
     */
    private void checkStep(@NonNull Trigger trigger, @NonNull Step step, boolean condition, @NonNull String step_name,
            @NonNull Param @NonNull [] params, boolean area_optional, @NonNull List<@NonNull String> problems) {
        for (Param param : params) {
            String what = param.getCaption().replace(":", "").toLowerCase(java.util.Locale.ROOT);
            switch (param) {
                case PLAYER, TARGET_PLAYER, NEW_OWNER -> {
                    int player = step.get(param);
                    if (!players[player].enabled)
                        problems.add(CampaignEditor.i18n("problem_absent_player", trigger.name, step_name,
                                playerName(player)));
                }
                case AREA, TRIGGER -> {
                    int id = step.get(param);
                    if (id == -1) {
                        if (!(param == Param.AREA && area_optional))
                            problems.add(CampaignEditor.i18n("problem_unset", trigger.name, step_name, what));
                    } else if (param == Param.AREA ? findArea(id) == null : findTrigger(id) == null) {
                        problems.add(CampaignEditor.i18n("problem_missing", trigger.name, step_name, what));
                    }
                }
                case OBJECT, BUILDING -> {
                    int id = step.get(param);
                    Placement placement = id != -1 ? findPlacement(id) : null;
                    if (id == -1)
                        problems.add(CampaignEditor.i18n("problem_unset", trigger.name, step_name, what));
                    else if (placement == null)
                        problems.add(CampaignEditor.i18n("problem_missing", trigger.name, step_name, what));
                    else if (!fits(condition, step, param, placement.kind()))
                        problems.add(CampaignEditor.i18n("problem_wrong_object", trigger.name, step_name,
                                describePlacement(placement), CampaignEditor.i18n(wanted(condition, step, param))));
                }
                default -> {
                }
            }
        }
    }

    /** Whether a step may leave its area unset, meaning the whole island. */
    static boolean isAreaOptional(boolean condition, @NonNull Step step) {
        return condition ? ConditionKind.of(step.kind) == ConditionKind.STATUES_LEFT
                : ActionKind.of(step.kind) == ActionKind.REMOVE_STATUES;
    }

    /** Whether a placed object is of a kind a step's setting can name. */
    static boolean fits(boolean condition, @NonNull Step step, @NonNull Param param, @NonNull ObjectKind kind) {
        boolean deploy = !condition && ActionKind.of(step.kind) == ActionKind.DEPLOY_FROM;
        if (param == Param.BUILDING)
            return deploy ? kind.isBuilding() : kind.holdsUnits();
        if (condition)
            return true;
        return switch (ActionKind.of(step.kind)) {
            case ENTER_BUILDING, CHANGE_OWNER_OBJECT -> kind.isUnit();
            default -> true;
        };
    }

    /** The key of what a step's setting wants, to explain a wrong pick. */
    static @NonNull String wanted(boolean condition, @NonNull Step step, @NonNull Param param) {
        if (param == Param.BUILDING)
            return !condition && ActionKind.of(step.kind) == ActionKind.DEPLOY_FROM ? "wants_building"
                    : "wants_holder";
        return "wants_unit";
    }

    /** What a building can send out: quarters only peons, a tower only warriors. */
    private void checkDeploy(@NonNull Trigger trigger, @NonNull Step step, @NonNull List<@NonNull String> problems) {
        Placement placement = findPlacement(step.get(Param.BUILDING));
        if (placement == null)
            return;
        boolean peons = Param.DEPLOY_TYPES[Math.clamp(step.get(Param.DEPLOY_TYPE), 0, Param.DEPLOY_TYPES.length - 1)]
                == com.oddlabs.tt.model.DeployType.PEON;
        String name = ActionKind.DEPLOY_FROM.getName();
        if (placement.kind() == ObjectKind.QUARTERS && !peons)
            problems.add(CampaignEditor.i18n("problem_quarters_peons", trigger.name, name));
        else if (placement.kind().isTower() && peons)
            problems.add(CampaignEditor.i18n("problem_tower_warriors", trigger.name, name));
    }

    @NonNull String describePlacement(@NonNull Placement placement) {
        if (!placement.kind().hasOwner())
            return CampaignEditor.i18n("object_label_unowned", placement.kind().getName(), placement.id());
        return CampaignEditor.i18n("object_label", placement.kind().getName(), playerName(placement.player()),
                placement.id());
    }

    /** The triggers whose condition or actions name an area, object or trigger id. */
    @NonNull List<@NonNull Trigger> usersOf(@NonNull Param param, int id) {
        List<Trigger> users = new ArrayList<>();
        for (Trigger trigger : triggers) {
            boolean uses = uses(trigger.condition, ConditionKind.of(trigger.condition.kind).getParams(), param, id);
            for (Step action : trigger.actions)
                uses |= uses(action, ActionKind.of(action.kind).getParams(), param, id);
            if (uses)
                users.add(trigger);
        }
        return users;
    }

    /** Whether a step names an id with a setting of the param's sort: an object or building counting as one. */
    private static boolean uses(@NonNull Step step, @NonNull Param @NonNull [] params, @NonNull Param param,
            int id) {
        for (Param p : params) {
            boolean same = p == param || (p.isObject() && param.isObject());
            if (same && step.get(p) == id)
                return true;
        }
        return false;
    }

    /** A setting's value as a person reads it. */
    @NonNull String describe(@NonNull Step step, @NonNull Param param) {
        return describe(step, param, false);
    }

    /** @param area_optional whether an unset area means the whole island */
    private @NonNull String describe(@NonNull Step step, @NonNull Param param, boolean area_optional) {
        if (param.isText()) {
            String text = step.getText(param).replace('\n', ' ');
            return text.length() > 24 ? "\"" + text.substring(0, 22) + "...\"" : "\"" + text + "\"";
        }
        int value = step.get(param);
        String[] choices = param.getChoices();
        if (choices != null)
            return choices[Math.clamp(value, 0, choices.length - 1)];
        if (value == -1 && param == Param.AREA && area_optional)
            return CampaignEditor.i18n("whole_island");
        if (value == -1 && (param == Param.AREA || param.isObject() || param == Param.TRIGGER))
            return CampaignEditor.i18n("none");
        return switch (param) {
            case PLAYER, TARGET_PLAYER, NEW_OWNER -> playerName(value);
            case AREA -> {
                Area area = findArea(value);
                yield area != null ? area.name : CampaignEditor.i18n("missing");
            }
            case OBJECT, BUILDING -> {
                Placement placement = findPlacement(value);
                yield placement != null ? describePlacement(placement) : CampaignEditor.i18n("missing");
            }
            case TRIGGER -> {
                Trigger trigger = findTrigger(value);
                yield trigger != null ? trigger.name : CampaignEditor.i18n("missing");
            }
            case SECONDS -> CampaignEditor.i18n("seconds", value);
            case RADIUS -> CampaignEditor.i18n("meters", value);
            default -> Integer.toString(value);
        };
    }

    @NonNull String describeCondition(@NonNull Step step) {
        ConditionKind kind = ConditionKind.of(step.kind);
        return describe(step, kind.getName(), kind.getParams(), isAreaOptional(true, step));
    }

    @NonNull String describeAction(@NonNull Step step) {
        ActionKind kind = ActionKind.of(step.kind);
        return describe(step, kind.getName(), kind.getParams(), isAreaOptional(false, step));
    }

    private @NonNull String describe(@NonNull Step step, @NonNull String name, @NonNull Param @NonNull [] params,
            boolean area_optional) {
        if (params.length == 0)
            return name;
        StringBuilder text = new StringBuilder(name).append(": ");
        for (int i = 0; i < params.length; i++) {
            if (i > 0)
                text.append(", ");
            text.append(describe(step, params[i], area_optional));
        }
        return text.toString();
    }

    void write(@NonNull DataOutputStream out) throws IOException {
        out.writeInt(VERSION);
        out.writeUTF(title);
        out.writeUTF(briefing);
        out.writeUTF(objective);
        out.writeInt(next_id);
        for (PlayerSetup player : players) {
            out.writeBoolean(player.enabled);
            out.writeByte(player.race);
            out.writeByte(player.team);
            out.writeByte(player.role.ordinal());
        }
        out.writeInt(placements.size());
        for (Placement placement : placements) {
            out.writeInt(placement.id());
            out.writeByte(placement.player());
            out.writeByte(placement.kind().ordinal());
            out.writeShort(placement.x());
            out.writeShort(placement.y());
        }
        out.writeInt(areas.size());
        for (Area area : areas) {
            out.writeInt(area.id);
            out.writeUTF(area.name);
            out.writeFloat(area.x);
            out.writeFloat(area.y);
            out.writeFloat(area.radius);
        }
        out.writeInt(triggers.size());
        for (Trigger trigger : triggers) {
            out.writeInt(trigger.id);
            out.writeUTF(trigger.name);
            out.writeBoolean(trigger.active);
            out.writeBoolean(trigger.repeat);
            out.writeByte(trigger.difficulties);
            trigger.condition.write(out);
            out.writeInt(trigger.actions.size());
            for (Step action : trigger.actions)
                action.write(out);
        }
    }

    static @NonNull Scenario read(@NonNull DataInputStream in) throws IOException {
        int version = in.readInt();
        if (version < 1 || version > VERSION)
            throw new IOException("Unsupported level version " + version);
        Scenario scenario = new Scenario(in.readUTF());
        scenario.briefing = in.readUTF();
        scenario.objective = in.readUTF();
        scenario.next_id = in.readInt();
        Role[] roles = Role.values();
        for (int i = 0; i < NUM_PLAYERS; i++) {
            boolean enabled = in.readBoolean();
            int race = in.readByte();
            int team = in.readByte();
            Role role = roles[Math.clamp(in.readByte(), 0, roles.length - 1)];
            if (!RacesResources.isValidRace(race))
                race = RacesResources.RACE_NATIVES;
            // The first player is always the one playing, and only that one.
            if (i == 0) {
                enabled = true;
                role = Role.HUMAN;
            } else if (role == Role.HUMAN) {
                role = Role.OPPONENT;
            }
            scenario.players[i] = new PlayerSetup(enabled, race, Math.clamp(team, 0, NUM_PLAYERS - 1), role);
        }
        int count = readCount(in);
        for (int i = 0; i < count; i++) {
            int id = in.readInt();
            int player = Math.clamp(in.readByte(), 0, NUM_PLAYERS - 1);
            ObjectKind kind = ObjectKind.of(in.readByte());
            scenario.placements.add(new Placement(id, player, kind, in.readShort(), in.readShort()));
        }
        count = readCount(in);
        for (int i = 0; i < count; i++)
            scenario.areas.add(new Area(in.readInt(), in.readUTF(), in.readFloat(), in.readFloat(), in.readFloat()));
        count = readCount(in);
        for (int i = 0; i < count; i++) {
            int id = in.readInt();
            String name = in.readUTF();
            boolean active = in.readBoolean();
            boolean repeat = in.readBoolean();
            int difficulties = in.readByte();
            Trigger trigger = new Trigger(id, name, Step.read(in, version));
            trigger.active = active;
            trigger.repeat = repeat;
            trigger.difficulties = difficulties & Trigger.ALL_DIFFICULTIES;
            int actions = readCount(in);
            for (int a = 0; a < actions; a++)
                trigger.actions.add(Step.read(in, version));
            scenario.triggers.add(trigger);
        }
        return scenario;
    }

    private static int readCount(@NonNull DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > MAX_ITEMS)
            throw new IOException("Bad count " + count);
        return count;
    }
}
