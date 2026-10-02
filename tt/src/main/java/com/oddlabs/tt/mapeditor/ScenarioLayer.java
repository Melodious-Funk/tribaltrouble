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
final class ScenarioLayer {
    /** How close the cursor must be to a unit to point at it, in meters. */
    private static final float UNIT_REACH = 2.5f;
    /** Cells kept between painted units at the lowest density above zero. */
    private static final float SPARSE_SPACING = 4f;
    private static final float AREA_ALPHA = .9f;

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
    private boolean modified;

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

    @NonNull Scenario getScenario() {
        return scenario;
    }

    /** Whether anything changed since the level was last saved. */
    boolean isModified() {
        return modified;
    }

    void markModified() {
        modified = true;
    }

    void markSaved() {
        modified = false;
    }

    // ---- Models ----

    private void show(Scenario.@NonNull Placement placement) {
        Scenario.PlayerSetup player = scenario.players[placement.player()];
        if (!player.enabled)
            return;
        Race race = races.getRace(player.race);
        ObjectKind kind = placement.kind();
        float x = UnitGrid.coordinateFromGrid(placement.x());
        float y = UnitGrid.coordinateFromGrid(placement.y());
        SceneryModel model;
        if (kind.isBuilding()) {
            BuildingTemplate template = race.getBuildingTemplate(kind.getBuildingType());
            model = new SceneryModel(world, x, y, 1f, 0f, template.getBuiltRenderer());
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
    }

    /** Shows a player's objects again, after the player's tribe or taking part changed. */
    void playerChanged(int player) {
        for (Scenario.Placement placement : scenario.placements) {
            if (placement.player() == player) {
                hide(placement);
                show(placement);
            }
        }
        modified = true;
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
        }
    }

    /** Takes the models off the island, as the editor closes. */
    void close() {
        for (SceneryModel model : models.values())
            model.remove();
        models.clear();
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

    /** Whether an object fits with its centre on a cell: its whole footprint on clear, playable ground. */
    boolean canPlace(@NonNull ObjectKind kind, int cx, int cy) {
        int r = kind.getFootprintRadius();
        if (cx - r < 0 || cy - r < 0 || cx + r >= size || cy + r >= size)
            return false;
        for (int y = cy - r; y <= cy + r; y++) {
            for (int x = cx - r; x <= cx + r; x++) {
                if (access.get(x, y) != AccessMap.Kind.REGION || resources.hasResource(x, y)
                        || occupant[y * size + x] != 0)
                    return false;
            }
        }
        return true;
    }

    private static int toGrid(float meters) {
        return UnitGrid.toGridCoordinate(meters);
    }

    private int countUnits(int player) {
        int count = 0;
        for (Scenario.Placement placement : scenario.placements) {
            if (placement.player() == player && !placement.kind().isBuilding()
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
        modified = true;
    }

    private void remove(Scenario.@NonNull Placement placement, @NonNull Stroke stroke) {
        scenario.placements.remove(placement);
        occupy(placement, 0);
        hide(placement);
        // Taking away what this stroke put down leaves nothing to restore.
        if (!stroke.added.remove(placement))
            stroke.removed.add(placement);
        modified = true;
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
        } else if (!kind.isBuilding() && countUnits(player) >= Player.DEFAULT_MAX_UNIT_COUNT) {
            return false;
        }
        if (!canPlace(kind, cx, cy))
            return false;
        add(new Scenario.Placement(scenario.newId(), player, kind, cx, cy), stroke);
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
            if ((kind != null && placement.kind() != kind) || (player != -1 && placement.player() != player))
                continue;
            float dx = UnitGrid.coordinateFromGrid(placement.x()) - cx;
            float dy = UnitGrid.coordinateFromGrid(placement.y()) - cy;
            float reach = radius + placement.kind().getFootprintRadius()
                    * HeightMap.METERS_PER_UNIT_GRID;
            if (dx * dx + dy * dy <= reach * reach)
                remove(placement, stroke);
        }
    }

    /** Takes away objects whose ground left the playable region; undoing the edit brings them back. */
    boolean prune(@NonNull Stroke stroke) {
        boolean changed = false;
        for (Scenario.Placement placement : new ArrayList<>(scenario.placements)) {
            int r = placement.kind().getFootprintRadius();
            boolean lost = false;
            for (int y = placement.y() - r; y <= placement.y() + r && !lost; y++) {
                for (int x = placement.x() - r; x <= placement.x() + r && !lost; x++)
                    lost = x < 0 || y < 0 || x >= size || y >= size || access.get(x, y) != AccessMap.Kind.REGION;
            }
            if (lost) {
                remove(placement, stroke);
                changed = true;
            }
        }
        return changed;
    }

    void undo(@NonNull Stroke stroke) {
        for (Scenario.Placement placement : stroke.added) {
            scenario.placements.remove(placement);
            occupy(placement, 0);
            hide(placement);
        }
        for (Scenario.Placement placement : stroke.removed) {
            scenario.placements.add(placement);
            occupy(placement, placement.id());
            show(placement);
        }
        for (Map.Entry<Integer, Scenario.@Nullable Area> entry : stroke.areas_before.entrySet()) {
            Scenario.Area current = scenario.findArea(entry.getKey());
            Scenario.Area before = entry.getValue();
            if (current != null)
                scenario.areas.remove(current);
            if (before != null)
                scenario.areas.add(before);
        }
        modified = true;
    }

    // ---- Areas ----

    /** Notes an area's state before a stroke changes it, once per stroke. */
    private void before(Scenario.@NonNull Area area, @NonNull Stroke stroke) {
        if (!stroke.areas_before.containsKey(area.id))
            stroke.areas_before.put(area.id, new Scenario.Area(area.id, area.name, area.x, area.y, area.radius));
        modified = true;
    }

    Scenario.@NonNull Area addArea(float x, float y, float radius, @NonNull Stroke stroke) {
        Scenario.Area area = new Scenario.Area(scenario.newId(), scenario.newAreaName(), x, y, radius);
        scenario.areas.add(area);
        stroke.areas_before.put(area.id, null);
        modified = true;
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
            if (!scenario.players[placement.player()].enabled)
                continue;
            Vector4fc c = colours[placement.player() % colours.length];
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
            if (param == Param.OBJECT)
                objects.add(step.get(param));
            else if (param == Param.AREA)
                areas.add(step.get(param));
        }
    }
}
