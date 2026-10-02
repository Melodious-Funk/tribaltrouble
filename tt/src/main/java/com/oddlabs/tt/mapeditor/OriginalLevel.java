package com.oddlabs.tt.mapeditor;

import com.oddlabs.tt.model.RacesResources;
import com.oddlabs.tt.pathfinder.UnitGrid;
import com.oddlabs.tt.procedural.Landscape;
import com.oddlabs.tt.util.Utils;
import org.jspecify.annotations.NonNull;

import java.lang.ref.SoftReference;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.ResourceBundle;

/**
 * One island of the game's own campaigns, written out as a campaign editor level while its script is read: the island
 * as the game generates it, and what the script places and sets off as the editor's placements, areas and triggers.
 * {@link OriginalCampaign} has the scripts.
 *
 * <p>Grid cells are what the scripts use: meters halved. Things the scripts stack on one cell are spread to the
 * nearest free cells, as the game itself does when it lets units out, so the editor can show and pick each one.
 */
final class OriginalLevel {
    /** The generator inputs an island's script hands the game, and how many tribes take part. */
    record Island(int meters, Landscape.@NonNull TerrainType terrain, float hills, float vegetation, float supplies,
                  int seed, int tribes, int max_units) {
    }

    /** What an island generates to: kept for a while, as generating takes a second or two. */
    private record Geometry(float @NonNull [] @NonNull [] heights, MapFile.@NonNull Resources resources,
                            float @NonNull [] @NonNull [] starts) {
    }

    /** How far from where a script puts something to look for room for it, in cells, as the game looks. */
    private static final int SEARCH_RADIUS = 40;
    /** How far from its start the game scatters a computer tribe's first units, in meters (AI.getTarget). */
    private static final float SCATTER = 30f;
    /** How far towards the island's middle the game builds a computer tribe's first towers, in cells. */
    private static final int TOWER_DISTANCE = 10;
    /** How far behind its captor's base the game keeps prisoners, in cells (Island.placePrisoners). */
    private static final int PRISON_DISTANCE = 5;

    private static final Map<Island, SoftReference<Geometry>> geometries = new HashMap<>();

    private final @NonNull ResourceBundle bundle;
    private final int face_offset;
    private final @NonNull MapSettings settings;
    private final @NonNull Geometry geometry;
    private final @NonNull AccessMap access;
    private final int size;
    private final int @NonNull [] occupant;
    private final boolean @NonNull [] resource;
    final @NonNull Scenario scenario;
    private int tribes;

    /**
     * @param script the island scripts' class name without the number, whose bundles have the texts
     * @param number the island's number in its campaign, from 0
     * @param natives whether the campaign is the Natives', whose portraits it shows
     */
    OriginalLevel(@NonNull String script, int number, @NonNull Island island, boolean natives) {
        this.bundle = ResourceBundle.getBundle("com.oddlabs.tt.player.campaign." + script + number);
        this.face_offset = natives ? 1 + Param.FACES_PER_TRIBE : 1;
        this.settings = new MapSettings(sizeOf(island.meters()), island.terrain().ordinal(), slider(island.hills()),
                slider(island.vegetation()), slider(island.supplies()), island.seed());
        this.geometry = geometry(island);
        this.access = new AccessMap(geometry.heights(), settings);
        this.size = geometry.heights().length;
        this.occupant = new int[size * size];
        this.resource = new boolean[size * size];
        for (Resource kind : Resource.values()) {
            for (int[] position : geometry.resources().of(kind))
                resource[position[1] * size + position[0]] = true;
        }
        // The first island of a campaign is started straight away in the game, so it has no title of its own.
        this.scenario = Scenario.createDefault(bundle.containsKey("header") ? text("header")
                : CampaignEditor.i18n("original_island", number + 1));
        scenario.triggers.clear();
        scenario.briefing = bundle.containsKey("description") ? text("description") : "";
        scenario.objective = text(bundle.containsKey("objective") ? "objective" : "objective0");
        for (int i = 0; i < Scenario.NUM_PLAYERS; i++)
            scenario.players[i].enabled = false;
    }

    /** The island's size setting: the sizes run 256, 512, 1024 meters and on. */
    private static int sizeOf(int meters) {
        return Integer.numberOfTrailingZeros(meters / 256);
    }

    private static int slider(float amount) {
        return Math.round(amount * MapSettings.SLIDER_MAX);
    }

    /** Generates the island as the game does for the script, or takes it from the last time. */
    private static @NonNull Geometry geometry(@NonNull Island island) {
        synchronized (geometries) {
            SoftReference<Geometry> kept = geometries.get(island);
            Geometry geometry = kept != null ? kept.get() : null;
            if (geometry != null)
                return geometry;
        }
        Landscape landscape = Landscape.withoutTextures(island.tribes(), island.meters(), island.terrain(),
                island.hills(), island.vegetation(), island.supplies(), island.seed(), island.max_units());
        @SuppressWarnings("unchecked")
        List<int[]>[] positions = new List[Resource.values().length];
        positions[Resource.TREE.ordinal()] = landscape.getTrees();
        positions[Resource.PALM.ordinal()] = landscape.getPalmtrees();
        positions[Resource.ROCK.ordinal()] = landscape.getRock();
        positions[Resource.IRON.ordinal()] = landscape.getIron();
        Geometry geometry = new Geometry(landscape.getHeight(), new MapFile.Resources(positions),
                landscape.getStartingLocations());
        synchronized (geometries) {
            geometries.put(island, new SoftReference<>(geometry));
        }
        return geometry;
    }

    /** The level as the campaign editor keeps it. */
    CampaignFile.@NonNull Level toLevel() {
        return new CampaignFile.Level(settings, geometry.heights(), geometry.resources(),
                MapPreview.render(geometry.heights(), settings, geometry.resources()), scenario);
    }

    // ---- Texts ----

    /** A text of the island's script, in the game's language. */
    @NonNull String text(@NonNull String key, @NonNull Object @NonNull... args) {
        return Utils.getBundleString(bundle, key, args);
    }

    /** A portrait of the campaign's, by its place in the campaign's own list, as the editor numbers it. */
    int face(int index) {
        return face_offset + index;
    }

    // ---- Tribes ----

    /**
     * Adds a tribe, in the order the script's slots take part: the first is the one playing.
     *
     * @param team a team from 0, or {@link Scenario#NEUTRAL_TEAM}
     * @return the tribe's index
     */
    int tribe(int race, int team, Scenario.@NonNull Role role) {
        int index = tribes++;
        scenario.players[index] = new Scenario.PlayerSetup(true, race, team, index == 0 ? Scenario.Role.HUMAN : role);
        return index;
    }

    int vikings(int team, Scenario.@NonNull Role role) {
        return tribe(RacesResources.RACE_VIKINGS, team, role);
    }

    int natives(int team, Scenario.@NonNull Role role) {
        return tribe(RacesResources.RACE_NATIVES, team, role);
    }

    /** Where the game starts a tribe, as a cell. */
    int @NonNull [] start(int tribe) {
        float[] location = geometry.starts()[tribe];
        return new int[]{UnitGrid.toGridCoordinate(location[0]), UnitGrid.toGridCoordinate(location[1])};
    }

    // ---- Placements ----

    /** Whether an object fits with its centre on a cell, as the editor lets one be placed. */
    private boolean fits(@NonNull ObjectKind kind, int cx, int cy) {
        int r = kind.getFootprintRadius();
        if (cx - r < 0 || cy - r < 0 || cx + r >= size || cy + r >= size)
            return false;
        for (int y = cy - r; y <= cy + r; y++) {
            for (int x = cx - r; x <= cx + r; x++) {
                int cell = y * size + x;
                if (occupant[cell] != 0 || resource[cell] || access.get(x, y) != AccessMap.Kind.REGION)
                    return false;
            }
        }
        return true;
    }

    /** The free cell nearest to one where an object fits, looking ring by ring; the cell itself if none does. */
    private int @NonNull [] near(@NonNull ObjectKind kind, int x, int y) {
        for (int radius = 0; radius <= SEARCH_RADIUS; radius++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) == radius && fits(kind, x + dx, y + dy))
                        return new int[]{x + dx, y + dy};
                }
            }
        }
        return new int[]{x, y};
    }

    /**
     * Places an object of a tribe at the free cell nearest to a cell.
     *
     * @return the placement's id, for triggers to name
     */
    int place(int tribe, @NonNull ObjectKind kind, int x, int y) {
        int[] at = near(kind, x, y);
        int id = scenario.newId();
        scenario.placements.add(new Scenario.Placement(id, kind.hasOwner() ? tribe : 0, kind, at[0], at[1]));
        int r = kind.getFootprintRadius();
        for (int cy = at[1] - r; cy <= at[1] + r; cy++) {
            for (int cx = at[0] - r; cx <= at[0] + r; cx++) {
                if (cx >= 0 && cy >= 0 && cx < size && cy < size)
                    occupant[cy * size + cx] = id;
            }
        }
        return id;
    }

    /** Places so many units of a kind around a cell. */
    void units(int tribe, @NonNull ObjectKind kind, int count, int x, int y) {
        for (int i = 0; i < count; i++)
            place(tribe, kind, x, y);
    }

    /** Places a golden statue, which belongs to no one. */
    int statue(int x, int y) {
        return place(0, ObjectKind.STATUE, x, y);
    }

    /** A tower with a guard of a kind in it, as the scripts' insertGuardTower. */
    int guardTower(int tribe, @NonNull ObjectKind guard, int x, int y) {
        return place(tribe, towerFor(guard), x, y);
    }

    private static @NonNull ObjectKind towerFor(@NonNull ObjectKind guard) {
        return switch (guard) {
            case ROCK_WARRIOR -> ObjectKind.ROCK_GUARDED_TOWER;
            case RUBBER_WARRIOR -> ObjectKind.RUBBER_GUARDED_TOWER;
            default -> ObjectKind.GUARDED_TOWER;
        };
    }

    /** The units a tribe starts with, as the game's UnitInfo counts them. */
    record Army(boolean chieftain, int peons, int rock, int iron, int rubber) {
        static @NonNull Army of(int peons, int rock, int iron, int rubber) {
            return new Army(false, peons, rock, iron, rubber);
        }

        @NonNull Army withChieftain() {
            return new Army(true, peons, rock, iron, rubber);
        }
    }

    /**
     * The one playing's units, on the spots the game starts them on: peons, then warriors by weapon, then the
     * chieftain, as WorldViewer lines them up.
     */
    void playerStart(@NonNull Army army) {
        float[] spots = geometry.starts()[0];
        int[] spot = {0};
        placeOnSpots(spots, spot, ObjectKind.PEON, army.peons());
        placeOnSpots(spots, spot, ObjectKind.ROCK_WARRIOR, army.rock());
        placeOnSpots(spots, spot, ObjectKind.IRON_WARRIOR, army.iron());
        placeOnSpots(spots, spot, ObjectKind.RUBBER_WARRIOR, army.rubber());
        if (army.chieftain())
            placeOnSpots(spots, spot, ObjectKind.CHIEFTAIN, 1);
    }

    private void placeOnSpots(float @NonNull [] spots, int @NonNull [] next, @NonNull ObjectKind kind, int count) {
        for (int i = 0; i < count; i++) {
            int index = Math.min(next[0]++, spots.length / 2 - 1);
            place(0, kind, UnitGrid.toGridCoordinate(spots[2 * index]),
                    UnitGrid.toGridCoordinate(spots[2 * index + 1]));
        }
    }

    /**
     * A computer tribe's start as the game's AI builds it from UnitInfo: quarters and armory by its start, towers
     * towards the island's middle, and its units scattered around the start.
     *
     * @param guards how many of the towers a warrior of the army goes up into, as the scripts' manTowers
     */
    void base(int tribe, boolean quarters, boolean armory, int towers, int guards, @NonNull Army army) {
        int[] start = start(tribe);
        if (quarters)
            place(tribe, ObjectKind.QUARTERS, start[0], start[1]);
        if (armory)
            place(tribe, ObjectKind.ARMORY, start[0], start[1]);
        int rock = army.rock();
        int iron = army.iron();
        int rubber = army.rubber();
        int center = size / 2;
        float dx = center - start[0];
        float dy = center - start[1];
        float inv_dist = 1f / (float) Math.sqrt(dx * dx + dy * dy);
        for (int i = 0; i < towers; i++) {
            int tx = (int) (start[0] + TOWER_DISTANCE * dx * inv_dist);
            int ty = (int) (start[1] + TOWER_DISTANCE * dy * inv_dist);
            ObjectKind tower = ObjectKind.TOWER;
            if (i < guards) {
                // The first idle warrior goes up, of whatever weapon the tribe has.
                if (iron > 0) {
                    iron--;
                    tower = ObjectKind.GUARDED_TOWER;
                } else if (rock > 0) {
                    rock--;
                    tower = ObjectKind.ROCK_GUARDED_TOWER;
                } else if (rubber > 0) {
                    rubber--;
                    tower = ObjectKind.RUBBER_GUARDED_TOWER;
                }
            }
            place(tribe, tower, tx, ty);
        }
        Random random = new Random(42);
        if (army.chieftain())
            scatter(tribe, random, ObjectKind.CHIEFTAIN, 1);
        scatter(tribe, random, ObjectKind.PEON, army.peons());
        scatter(tribe, random, ObjectKind.ROCK_WARRIOR, rock);
        scatter(tribe, random, ObjectKind.IRON_WARRIOR, iron);
        scatter(tribe, random, ObjectKind.RUBBER_WARRIOR, rubber);
    }

    private void scatter(int tribe, @NonNull Random random, @NonNull ObjectKind kind, int count) {
        float[] location = geometry.starts()[tribe];
        for (int i = 0; i < count; i++) {
            float x = location[0] + (random.nextFloat() * 2 - 1) * SCATTER;
            float y = location[1] + (random.nextFloat() * 2 - 1) * SCATTER;
            place(tribe, kind, UnitGrid.toGridCoordinate(x), UnitGrid.toGridCoordinate(y));
        }
    }

    /** Where the game keeps a captor's prisoners: a little behind its start, away from the island's middle. */
    int @NonNull [] prison(int captor) {
        int[] start = start(captor);
        int center = size / 2;
        int dx = center - start[0];
        int dy = center - start[1];
        float inv_dist = 1f / (float) Math.sqrt(dx * dx + dy * dy);
        return new int[]{(int) (start[0] - PRISON_DISTANCE * dx * inv_dist),
                (int) (start[1] - PRISON_DISTANCE * dy * inv_dist)};
    }

    // ---- Areas ----

    /**
     * Adds an area around a cell.
     *
     * @param radius in meters
     * @param name_key the key of its name in the editor's texts
     * @return its id
     */
    int area(int x, int y, float radius, @NonNull String name_key, @NonNull Object @NonNull... args) {
        Scenario.Area area = new Scenario.Area(scenario.newId(), CampaignEditor.i18n(name_key, args),
                UnitGrid.coordinateFromGrid(x), UnitGrid.coordinateFromGrid(y), radius);
        scenario.areas.add(area);
        return area.id;
    }

    // ---- Triggers ----

    /** A trigger being written out, added to the level as it is made so others can name it. */
    final class Trigger {
        final Scenario.@NonNull Trigger trigger;

        private Trigger(Scenario.@NonNull Trigger trigger) {
            this.trigger = trigger;
        }

        int id() {
            return trigger.id;
        }

        /** Waits until another trigger activates it. */
        @NonNull Trigger inactive() {
            trigger.active = false;
            return this;
        }

        @NonNull Trigger repeat() {
            trigger.repeat = true;
            return this;
        }

        /** Takes part only at some campaign difficulties, as {@code Scenario.Trigger.DIFFICULTY_} bits. */
        @NonNull Trigger only(int difficulties) {
            trigger.difficulties = difficulties;
            return this;
        }

        @NonNull Trigger then(@NonNull Step action) {
            trigger.actions.add(action);
            return this;
        }

        /** A story dialog with one of the island's headers and texts and a portrait of the campaign's list. */
        @NonNull Trigger dialog(@NonNull String header_key, @NonNull String text_key, int face,
                @NonNull Object @NonNull... args) {
            Step step = Step.action(ActionKind.DIALOG);
            step.setText(Param.HEADER, text(header_key));
            step.setText(Param.TEXT, text(text_key, args));
            step.set(Param.FACE, face(face));
            return then(step);
        }

        @NonNull Trigger objective(@NonNull String key) {
            Step step = Step.action(ActionKind.OBJECTIVE);
            step.setText(Param.TEXT, text(key));
            return then(step);
        }

        @NonNull Trigger defeat(@NonNull String key) {
            Step step = Step.action(ActionKind.DEFEAT);
            step.setText(Param.TEXT, text(key));
            return then(step);
        }
    }

    /**
     * Adds a trigger, active from the start at every difficulty until told otherwise.
     *
     * @param name_key the key of its name in the editor's texts
     */
    @NonNull Trigger trigger(@NonNull String name_key, @NonNull Step condition, @NonNull Object @NonNull... args) {
        Scenario.Trigger trigger = new Scenario.Trigger(scenario.newId(), CampaignEditor.i18n(name_key, args),
                condition);
        scenario.triggers.add(trigger);
        return new Trigger(trigger);
    }

    // ---- Steps ----

    static @NonNull Step step(@NonNull ConditionKind kind) {
        return Step.condition(kind);
    }

    static @NonNull Step step(@NonNull ActionKind kind) {
        return Step.action(kind);
    }

    /** A step with its settings, given as pairs of a {@link Param} and its value. */
    static @NonNull Step with(@NonNull Step step, @NonNull Object @NonNull... settings) {
        for (int i = 0; i < settings.length; i += 2) {
            Param param = (Param) settings[i];
            Object value = settings[i + 1];
            int number = switch (value) {
                case Integer integer -> integer;
                case UnitFilter filter -> filter.ordinal();
                case ObjectKind kind -> unitType(kind);
                case Scenario.Role role -> roleIndex(role);
                default -> throw new IllegalArgumentException("Cannot set " + param + " to " + value);
            };
            step.set(param, number);
        }
        return step;
    }

    static @NonNull Step when(@NonNull ConditionKind kind, @NonNull Object @NonNull... settings) {
        return with(step(kind), settings);
    }

    static @NonNull Step act(@NonNull ActionKind kind, @NonNull Object @NonNull... settings) {
        return with(step(kind), settings);
    }

    private static int unitType(@NonNull ObjectKind kind) {
        for (int i = 0; i < Param.UNIT_TYPES.length; i++) {
            if (Param.UNIT_TYPES[i] == kind)
                return i;
        }
        throw new IllegalArgumentException(kind + " cannot be spawned");
    }

    private static int roleIndex(Scenario.@NonNull Role role) {
        for (int i = 0; i < Param.ROLES.length; i++) {
            if (Param.ROLES[i] == role)
                return i;
        }
        throw new IllegalArgumentException(role + " cannot be switched to");
    }

    /** The difficulties of {@code Scenario.Trigger}, by the campaign difficulty they stand for. */
    static final int EASY = Scenario.Trigger.DIFFICULTY_EASY;
    static final int NORMAL = Scenario.Trigger.DIFFICULTY_NORMAL;
    static final int HARD = Scenario.Trigger.DIFFICULTY_HARD;

    /** The editor's name for a difficulty bit, for trigger names. */
    static @NonNull String difficultyName(int difficulty) {
        return CampaignEditor.i18n(switch (difficulty) {
            case EASY -> "difficulty_easy";
            case HARD -> "difficulty_hard";
            default -> "difficulty_normal";
        });
    }

    /** Lists triggers made per difficulty for the same thing, easy first. */
    static final int @NonNull [] DIFFICULTIES = {EASY, NORMAL, HARD};
}
