package com.oddlabs.tt.mapeditor;

import com.oddlabs.tt.global.Settings;
import com.oddlabs.tt.landscape.HeightMap;
import com.oddlabs.tt.landscape.World;
import com.oddlabs.tt.model.BuildingTemplate;
import com.oddlabs.tt.model.Race;
import com.oddlabs.tt.model.RacesResources;
import com.oddlabs.tt.model.SceneryModel;
import com.oddlabs.tt.model.UnitTemplate;
import com.oddlabs.tt.pathfinder.UnitGrid;
import com.oddlabs.tt.player.Player;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The units, buildings and areas of a campaign level in the editor, and painting them.
 *
 * <p>Objects show as the tribes' models standing still, with a dot or ring in their player's colour under them, so
 * nothing walks off or fights while the level is made. They go only on the playable region of {@link AccessMap},
 * clear of resources and of each other, as the game would place them; an edit that takes their ground out of the
 * playable region takes them away, until it is undone.
 */
final class ScenarioLayer implements ScenarioSync.Target {
    /** How close the cursor must be to a unit to point at it, in meters. */
    private static final float UNIT_REACH = 2.5f;
    /** Cells kept between painted units at the lowest density above zero. */
    private static final float SPARSE_SPACING = 4f;
    private static final float AREA_ALPHA = .9f;
    /** How far from the playable shore a ship may lie, in cells. */
    private static final int SHIP_SHORE_REACH = 5;
    /** As the campaigns set their statues down. */
    private static final float STATUE_SHADOW = 2.6f;
    /** The mark under a statue, which is no one's. */
    private static final Vector4fc GOLD = new Vector4f(1f, .8f, .2f, 1f);

    /** What one stroke placed, removed and changed, to take it back. */
    static final class Stroke {
        private final List<Scenario.Placement> added = new ArrayList<>();
        private final List<Scenario.Placement> removed = new ArrayList<>();
        // Each area's state before the stroke first changed it, or null for one the stroke added.
        private final Map<Integer, Scenario.@Nullable Area> areas_before = new HashMap<>();

        boolean isEmpty() {
            return added.isEmpty() && removed.isEmpty() && areas_before.isEmpty();
        }
    }

    private final @NonNull World world;
    private final @NonNull RacesResources races;
    private final @NonNull AccessMap access;
    private final @NonNull ResourceLayer resources;
    private final @NonNull Scenario scenario;
    private final int size;
    // The placement id taking each cell, or 0.
    private final int @NonNull [] occupant;
    private final Map<Integer, SceneryModel> models = new HashMap<>();
    // The warrior standing on each guarded tower, by the tower's id.
    private final Map<Integer, SceneryModel> guards = new HashMap<>();
    private boolean modified;
    // Goes up with every change, so a shared session can tell when to look for something to send.
    private int changes;

    ScenarioLayer(@NonNull World world, @NonNull RacesResources races, @NonNull AccessMap access,
            @NonNull ResourceLayer resources, @NonNull Scenario scenario) {
        this.world = world;
        this.races = races;
        this.access = access;
        this.resources = resources;
        this.scenario = scenario;
        this.size = access.getSize();
        this.occupant = new int[size * size];
        for (Scenario.Placement placement : scenario.placements) {
            occupy(placement, placement.id());
            show(placement);
        }
    }

    @Override
    public @NonNull Scenario getScenario() {
        return scenario;
    }

    /** Whether anything changed since the level was last saved. */
    boolean isModified() {
        return modified;
    }

    void markModified() {
        touched();
    }

    private void touched() {
        modified = true;
        changes++;
    }

    @Override
    public int getChangeCount() {
        return changes;
    }

    void markSaved() {
        modified = false;
    }

    // ---- Models ----

    private void show(Scenario.@NonNull Placement placement) {
        ObjectKind kind = placement.kind();
        float x = UnitGrid.coordinateFromGrid(placement.x());
        float y = UnitGrid.coordinateFromGrid(placement.y());
        if (!kind.hasOwner()) {
            double angle = placement.id() * 2.399963;
            models.put(placement.id(), new SceneryModel(world, x, y, (float) Math.cos(angle),
                    (float) Math.sin(angle), races.getTreasures()[statueVariant(placement.id())], STATUE_SHADOW,
                    false, null));
            return;
        }
        Scenario.PlayerSetup player = scenario.players[placement.player()];
        if (!player.enabled)
            return;
        Race race = races.getRace(player.race);
        SceneryModel model;
        if (kind.isBuilding()) {
            BuildingTemplate template = race.getBuildingTemplate(kind.getBuildingType());
            model = new SceneryModel(world, x, y, 1f, 0f, template.getBuiltRenderer());
            if (kind.getGuardType() != -1)
                guards.put(placement.id(), new GuardModel(world, x, y,
                        race.getUnitTemplate(kind.getGuardType()), template.getMountOffset()));
        } else {
            UnitTemplate template = race.getUnitTemplate(kind.getUnitType());
            // A different way for each, so a crowd does not stand to attention.
            double angle = placement.id() * 2.399963;
            model = new SceneryModel(world, x, y, (float) Math.cos(angle), (float) Math.sin(angle),
                    template.getSpriteRenderer(), template.getShadowDiameter(), false, null);
        }
        models.put(placement.id(), model);
    }

    private void hide(Scenario.@NonNull Placement placement) {
        SceneryModel model = models.remove(placement.id());
        if (model != null)
            model.remove();
        SceneryModel guard = guards.remove(placement.id());
        if (guard != null)
            guard.remove();
    }

    /** Which of the golden statue models a placed statue shows, the same in the editor as in the game. */
    static int statueVariant(int id) {
        return Math.floorMod(id * 7 + 3, ObjectKind.STATUE_VARIANTS);
    }

    /** A warrior standing on its tower, raised as the game raises a tower's guard. */
    private static final class GuardModel extends SceneryModel {
        private final float offset_z;

        GuardModel(@NonNull World world, float x, float y, @NonNull UnitTemplate template, float offset_z) {
            super(world, x, y, 0f, -1f, template.getSpriteRenderer(), 0f, false, null);
            this.offset_z = offset_z;
            // The constructor set it down before the offset was known.
            setPosition(x, y);
        }

        @Override
        public float getOffsetZ() {
            return offset_z;
        }
    }

    /** Shows a player's objects again, after the player's tribe or taking part changed. */
    void playerChanged(int player) {
        for (Scenario.Placement placement : scenario.placements) {
            if (placement.player() == player) {
                hide(placement);
                show(placement);
            }
        }
        touched();
    }

    /** Sets the models on the ground again where the heights changed, in cells (inclusive). */
    void heightsChanged(int x0, int y0, int x1, int y1) {
        int margin = RacesResources.MAX_BUILDING_SIZE;
        for (Scenario.Placement placement : scenario.placements) {
            if (placement.x() < x0 - margin || placement.x() > x1 + margin || placement.y() < y0 - margin
                    || placement.y() > y1 + margin)
                continue;
            SceneryModel model = models.get(placement.id());
            if (model != null)
                model.setPosition(model.getPositionX(), model.getPositionY());
            SceneryModel guard = guards.get(placement.id());
            if (guard != null)
                guard.setPosition(guard.getPositionX(), guard.getPositionY());
        }
    }

    /** Takes the models off the island, as the editor closes. */
    void close() {
        for (SceneryModel model : models.values())
            model.remove();
        models.clear();
        for (SceneryModel guard : guards.values())
            guard.remove();
        guards.clear();
    }

    // ---- Cells ----

    private void occupy(Scenario.@NonNull Placement placement, int id) {
        int r = placement.kind().getFootprintRadius();
        for (int y = placement.y() - r; y <= placement.y() + r; y++) {
            for (int x = placement.x() - r; x <= placement.x() + r; x++) {
                if (x >= 0 && y >= 0 && x < size && y < size)
                    occupant[y * size + x] = id;
            }
        }
    }

    /** Whether a unit or building stands on a cell. */
    boolean isTaken(int x, int y) {
        return x >= 0 && y >= 0 && x < size && y < size && occupant[y * size + x] != 0;
    }

    /**
     * Whether an object fits with its centre on a cell: its whole footprint clear, and on playable ground, or for a
     * ship on the sea close to the playable shore.
     */
    boolean canPlace(@NonNull ObjectKind kind, int cx, int cy) {
        int r = kind.getFootprintRadius();
        if (cx - r < 0 || cy - r < 0 || cx + r >= size || cy + r >= size)
            return false;
        for (int y = cy - r; y <= cy + r; y++) {
            for (int x = cx - r; x <= cx + r; x++) {
                if (occupant[y * size + x] != 0 || !groundFits(kind, x, y))
                    return false;
            }
        }
        return !kind.isShip() || nearShore(cx, cy);
    }

    /** Whether one cell of an object's footprint is ground it can stand on. */
    private boolean groundFits(@NonNull ObjectKind kind, int x, int y) {
        if (kind.isShip())
            return access.isSea(x, y);
        return access.get(x, y) == AccessMap.Kind.REGION && !resources.hasResource(x, y);
    }

    /** Whether the playable region is within reach of a sea cell, for a ship's units to land on. */
    private boolean nearShore(int cx, int cy) {
        for (int y = Math.max(0, cy - SHIP_SHORE_REACH); y <= Math.min(size - 1, cy + SHIP_SHORE_REACH); y++) {
            for (int x = Math.max(0, cx - SHIP_SHORE_REACH); x <= Math.min(size - 1, cx + SHIP_SHORE_REACH); x++) {
                if (access.get(x, y) == AccessMap.Kind.REGION)
                    return true;
            }
        }
        return false;
    }

    private static int toGrid(float meters) {
        return UnitGrid.toGridCoordinate(meters);
    }

    private int countUnits(int player) {
        int count = 0;
        for (Scenario.Placement placement : scenario.placements) {
            if (placement.player() == player && placement.kind().isUnit()
                    && placement.kind() != ObjectKind.CHIEFTAIN)
                count++;
        }
        return count;
    }

    private void add(Scenario.@NonNull Placement placement, @NonNull Stroke stroke) {
        scenario.placements.add(placement);
        occupy(placement, placement.id());
        show(placement);
        stroke.added.add(placement);
        touched();
    }

    private void remove(Scenario.@NonNull Placement placement, @NonNull Stroke stroke) {
        scenario.placements.remove(placement);
        occupy(placement, 0);
        hide(placement);
        // Taking away what this stroke put down leaves nothing to restore.
        if (!stroke.added.remove(placement))
            stroke.removed.add(placement);
        touched();
    }

    // ---- Painting ----

    /**
     * Places one object at a spot: a building, a chieftain (moving the player's one there if it had one), or a single
     * unit.
     *
     * @return whether it was placed
     */
    boolean placeAt(@NonNull ObjectKind kind, int player, float px, float py, @NonNull Stroke stroke) {
        int cx = toGrid(px);
        int cy = toGrid(py);
        if (kind == ObjectKind.CHIEFTAIN) {
            for (Scenario.Placement placement : new ArrayList<>(scenario.placements)) {
                if (placement.player() == player && placement.kind() == ObjectKind.CHIEFTAIN)
                    remove(placement, stroke);
            }
        } else if (kind.isUnit() && countUnits(player) >= Player.DEFAULT_MAX_UNIT_COUNT) {
            return false;
        }
        if (!canPlace(kind, cx, cy))
            return false;
        // A statue is no one's; it is kept as the first player's.
        add(new Scenario.Placement(scenario.newId(), kind.hasOwner() ? player : 0, kind, cx, cy), stroke);
        return true;
    }

    /**
     * Fills a disk with units to a density: at full density on every free cell, thinning out as density drops, and at
     * zero only one at the centre.
     */
    void paintUnits(@NonNull ObjectKind kind, int player, float cx, float cy, float radius, float density,
            @NonNull Stroke stroke) {
        if (density <= 0f) {
            placeAt(kind, player, cx, cy, stroke);
            return;
        }
        float spacing = 1f + (1f - density) * (SPARSE_SPACING - 1f);
        int gx = toGrid(cx);
        int gy = toGrid(cy);
        int r = (int) Math.ceil(radius / HeightMap.METERS_PER_UNIT_GRID);
        // Nearest the centre first, so a small brush puts them where the cursor is.
        List<int[]> cells = new ArrayList<>();
        for (int y = gy - r; y <= gy + r; y++) {
            for (int x = gx - r; x <= gx + r; x++) {
                float dx = UnitGrid.coordinateFromGrid(x) - cx;
                float dy = UnitGrid.coordinateFromGrid(y) - cy;
                if (dx * dx + dy * dy <= radius * radius)
                    cells.add(new int[]{x, y, (int) ((dx * dx + dy * dy) * 16)});
            }
        }
        cells.sort((a, b) -> Integer.compare(a[2], b[2]));
        int units = countUnits(player);
        int reach = (int) Math.ceil(spacing) - 1;
        for (int[] cell : cells) {
            if (units >= Player.DEFAULT_MAX_UNIT_COUNT)
                return;
            if (!canPlace(kind, cell[0], cell[1]) || hasUnitWithin(cell[0], cell[1], reach, spacing))
                continue;
            add(new Scenario.Placement(scenario.newId(), player, kind, cell[0], cell[1]), stroke);
            units++;
        }
    }

    private boolean hasUnitWithin(int cx, int cy, int reach, float spacing) {
        for (int y = Math.max(0, cy - reach); y <= Math.min(size - 1, cy + reach); y++) {
            for (int x = Math.max(0, cx - reach); x <= Math.min(size - 1, cx + reach); x++) {
                int dx = x - cx;
                int dy = y - cy;
                if (occupant[y * size + x] != 0 && dx * dx + dy * dy < spacing * spacing)
                    return true;
            }
        }
        return false;
    }

    /**
     * Takes away objects under a brush.
     *
     * @param kind the kind to take, or null for every kind
     * @param player the player whose objects to take, or -1 for everyone's
     */
    void erase(@Nullable ObjectKind kind, int player, float cx, float cy, float radius, @NonNull Stroke stroke) {
        for (Scenario.Placement placement : new ArrayList<>(scenario.placements)) {
            if ((kind != null && placement.kind() != kind)
                    || (player != -1 && placement.kind().hasOwner() && placement.player() != player))
                continue;
            float dx = UnitGrid.coordinateFromGrid(placement.x()) - cx;
            float dy = UnitGrid.coordinateFromGrid(placement.y()) - cy;
            float reach = radius + placement.kind().getFootprintRadius()
                    * HeightMap.METERS_PER_UNIT_GRID;
            if (dx * dx + dy * dy <= reach * reach)
                remove(placement, stroke);
        }
    }

    /**
     * Takes away objects whose ground left the playable region, or ships the sea or shore left; undoing the edit brings
     * them back.
     */
    boolean prune(@NonNull Stroke stroke) {
        boolean changed = false;
        for (Scenario.Placement placement : new ArrayList<>(scenario.placements)) {
            ObjectKind kind = placement.kind();
            int r = kind.getFootprintRadius();
            boolean lost = kind.isShip() && !nearShore(placement.x(), placement.y());
            for (int y = placement.y() - r; y <= placement.y() + r && !lost; y++) {
                for (int x = placement.x() - r; x <= placement.x() + r && !lost; x++) {
                    lost = x < 0 || y < 0 || x >= size || y >= size
                            || (kind.isShip() ? !access.isSea(x, y) : access.get(x, y) != AccessMap.Kind.REGION);
                }
            }
            if (lost) {
                remove(placement, stroke);
                changed = true;
            }
        }
        return changed;
    }

    /**
     * Takes back a stroke: removes what it placed, puts back what it removed and returns areas to how they were.
     *
     * @return what that changed, as a stroke whose undo redoes this one
     */
    @NonNull Stroke undo(@NonNull Stroke stroke) {
        Stroke undone = new Stroke();
        for (Scenario.Placement placement : stroke.added) {
            scenario.placements.remove(placement);
            occupy(placement, 0);
            hide(placement);
            undone.removed.add(placement);
        }
        for (Scenario.Placement placement : stroke.removed) {
            scenario.placements.add(placement);
            occupy(placement, placement.id());
            show(placement);
            undone.added.add(placement);
        }
        for (Map.Entry<Integer, Scenario.@Nullable Area> entry : stroke.areas_before.entrySet()) {
            Scenario.Area current = scenario.findArea(entry.getKey());
            Scenario.Area before = entry.getValue();
            undone.areas_before.put(entry.getKey(), current != null ? new Scenario.Area(current.id, current.name,
                    current.x, current.y, current.radius) : null);
            if (current != null)
                scenario.areas.remove(current);
            if (before != null)
                scenario.areas.add(before);
        }
        touched();
        return undone;
    }

    // ---- Shared sessions ----

    @Override
    public void applyShared(@NonNull Map<@NonNull Long, byte @Nullable []> items, int @Nullable [] order) {
        ScenarioItems.apply(scenario, items, order, new ScenarioItems.Shown() {
            @Override
            public void placementRemoved(Scenario.@NonNull Placement placement) {
                occupy(placement, 0);
                hide(placement);
            }

            @Override
            public void placementAdded(Scenario.@NonNull Placement placement) {
                occupy(placement, placement.id());
                show(placement);
            }

            @Override
            public void playerChanged(int player) {
                ScenarioLayer.this.playerChanged(player);
            }
        });
        touched();
    }

    // ---- Areas ----

    /** Notes an area's state before a stroke changes it, once per stroke. */
    private void before(Scenario.@NonNull Area area, @NonNull Stroke stroke) {
        if (!stroke.areas_before.containsKey(area.id))
            stroke.areas_before.put(area.id, new Scenario.Area(area.id, area.name, area.x, area.y, area.radius));
        touched();
    }

    Scenario.@NonNull Area addArea(float x, float y, float radius, @NonNull Stroke stroke) {
        Scenario.Area area = new Scenario.Area(scenario.newId(), scenario.newAreaName(), x, y, radius);
        scenario.areas.add(area);
        stroke.areas_before.put(area.id, null);
        touched();
        return area;
    }

    void moveArea(Scenario.@NonNull Area area, float x, float y, @NonNull Stroke stroke) {
        before(area, stroke);
        area.x = x;
        area.y = y;
    }

    void resizeArea(Scenario.@NonNull Area area, float radius, @NonNull Stroke stroke) {
        before(area, stroke);
        area.radius = radius;
    }

    void renameArea(Scenario.@NonNull Area area, @NonNull String name, @NonNull Stroke stroke) {
        before(area, stroke);
        area.name = name;
    }

    void removeArea(Scenario.@NonNull Area area, @NonNull Stroke stroke) {
        before(area, stroke);
        scenario.areas.remove(area);
    }

    /** The smallest area around a spot, or null. */
    Scenario.@Nullable Area areaAt(float x, float y) {
        Scenario.Area found = null;
        for (Scenario.Area area : scenario.areas) {
            if (area.contains(x, y) && (found == null || area.radius < found.radius))
                found = area;
        }
        return found;
    }

    /** The object at a spot: a building whose ground it is on, or the nearest unit close by. */
    Scenario.@Nullable Placement placementAt(float px, float py) {
        int cx = toGrid(px);
        int cy = toGrid(py);
        if (cx >= 0 && cy >= 0 && cx < size && cy < size && occupant[cy * size + cx] != 0) {
            Scenario.Placement placement = scenario.findPlacement(occupant[cy * size + cx]);
            if (placement != null && placement.kind().isBuilding())
                return placement;
        }
        Scenario.Placement nearest = null;
        float best = UNIT_REACH * UNIT_REACH;
        for (Scenario.Placement placement : scenario.placements) {
            float dx = UnitGrid.coordinateFromGrid(placement.x()) - px;
            float dy = UnitGrid.coordinateFromGrid(placement.y()) - py;
            float d = dx * dx + dy * dy;
            if (d <= best) {
                best = d;
                nearest = placement;
            }
        }
        return nearest;
    }

    // ---- Drawing ----

    /**
     * Marks every object with its player's colour and outlines the areas. What the highlighted trigger names stands
     * out, as does what the cursor points at.
     */
    void render(BrushRenderer.@NonNull Batch batch, Scenario.@Nullable Trigger highlight,
            Scenario.@Nullable Area hovered_area, Scenario.@Nullable Placement hovered) {
        Vector4fc[] colours = Settings.getSettings().team_colours;
        Set<Integer> named_objects = new HashSet<>();
        Set<Integer> named_areas = new HashSet<>();
        if (highlight != null) {
            collect(highlight.condition, ConditionKind.of(highlight.condition.kind).getParams(), named_objects,
                    named_areas);
            for (Step action : highlight.actions)
                collect(action, ActionKind.of(action.kind).getParams(), named_objects, named_areas);
        }
        for (Scenario.Placement placement : scenario.placements) {
            boolean owned = placement.kind().hasOwner();
            if (owned && !scenario.players[placement.player()].enabled)
                continue;
            Vector4fc c = owned ? colours[placement.player() % colours.length] : GOLD;
            float x = UnitGrid.coordinateFromGrid(placement.x());
            float y = UnitGrid.coordinateFromGrid(placement.y());
            if (placement.kind().isBuilding()) {
                float r = (placement.kind().getFootprintRadius() + .5f)
                        * HeightMap.METERS_PER_UNIT_GRID;
                batch.circle(x, y, r, c.x(), c.y(), c.z(), .9f);
            } else {
                batch.dot(x, y, c.x(), c.y(), c.z(), .9f);
            }
            boolean named = named_objects.contains(placement.id());
            if (named || placement.equals(hovered)) {
                float r = (placement.kind().getFootprintRadius() + 1.5f)
                        * HeightMap.METERS_PER_UNIT_GRID;
                batch.circle(x, y, r, 1f, named ? .85f : 1f, named ? .2f : 1f, 1f);
            }
        }
        for (Scenario.Area area : scenario.areas) {
            boolean named = named_areas.contains(area.id);
            float r = named ? 1f : .95f;
            float g = named ? .85f : .95f;
            float b = named ? .2f : .95f;
            float a = area == hovered_area || named ? 1f : AREA_ALPHA * .7f;
            batch.circle(area.x, area.y, area.radius, r, g, b, a);
            batch.dot(area.x, area.y, r, g, b, a);
            if (area == hovered_area || named)
                batch.circle(area.x, area.y, Math.max(1f, area.radius - 1f), r, g, b, a);
        }
    }

    private static void collect(@NonNull Step step, @NonNull Param @NonNull [] params,
            @NonNull Set<Integer> objects, @NonNull Set<Integer> areas) {
        for (Param param : params) {
            if (param.isObject())
                objects.add(step.get(param));
            else if (param == Param.AREA)
                areas.add(step.get(param));
        }
    }
}
