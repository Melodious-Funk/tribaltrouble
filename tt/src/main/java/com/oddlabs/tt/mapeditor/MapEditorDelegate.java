package com.oddlabs.tt.mapeditor;

import com.oddlabs.net.NetworkSelector;
import com.oddlabs.tt.animation.Animated;
import com.oddlabs.tt.animation.AnimationManager;
import com.oddlabs.tt.animation.TimerAnimation;
import com.oddlabs.tt.camera.Camera;
import com.oddlabs.tt.camera.CameraHost;
import com.oddlabs.tt.camera.CameraState;
import com.oddlabs.tt.camera.FirstPersonCamera;
import com.oddlabs.tt.camera.GameCamera;
import com.oddlabs.tt.camera.MapCamera;
import com.oddlabs.tt.camera.MapCameraOwner;
import com.oddlabs.tt.delegate.CameraDelegate;
import com.oddlabs.tt.event.LocalEventQueue;
import com.oddlabs.tt.form.MessageForm;
import com.oddlabs.tt.form.QuestionForm;
import com.oddlabs.tt.gui.CheckBox;
import com.oddlabs.tt.gui.CursorType;
import com.oddlabs.tt.gui.Form;
import com.oddlabs.tt.gui.GUIRoot;
import com.oddlabs.tt.gui.Group;
import com.oddlabs.tt.gui.HorizButton;
import com.oddlabs.tt.gui.Label;
import com.oddlabs.tt.gui.MouseButton;
import com.oddlabs.tt.gui.PulldownButton;
import com.oddlabs.tt.gui.PulldownItem;
import com.oddlabs.tt.gui.PulldownMenu;
import com.oddlabs.tt.gui.RadioButton;
import com.oddlabs.tt.gui.RadioButtonGroup;
import com.oddlabs.tt.gui.Skin;
import com.oddlabs.tt.input.GameAction;
import com.oddlabs.tt.input.InputEvent;
import com.oddlabs.tt.input.InputPhase;
import com.oddlabs.tt.input.Key;
import com.oddlabs.tt.landscape.HeightMap;
import com.oddlabs.tt.landscape.World;
import com.oddlabs.tt.player.campaign.CampaignState;
import com.oddlabs.tt.procedural.Landscape;
import com.oddlabs.tt.render.LandscapeLocation;
import com.oddlabs.tt.render.LandscapeRenderer;
import com.oddlabs.tt.render.MatrixStack;
import com.oddlabs.tt.render.Picker;
import com.oddlabs.tt.render.RenderQueues;
import com.oddlabs.tt.render.Renderer;
import com.oddlabs.tt.scenery.Water;
import com.oddlabs.tt.viewer.Cheat;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Random;

import static com.oddlabs.tt.gui.Placement.BOTTOM_LEFT;
import static com.oddlabs.tt.gui.Placement.RIGHT_MID;

/**
 * The editor screen: the island seen through the game camera, a toolbar along the top with a dropdown each for
 * terrain and resource brushes, and the mouse painting the terrain.
 *
 * <p>Left button paints with the positive side of a brush and right button with the negative side. Ctrl plus the
 * wheel sizes the brush, Shift plus the wheel sets its intensity, and the plain wheel zooms like in a game. The
 * middle button turns the view, as it does in a game.
 *
 * <p>The map mode key (Space by default) flies up to the game's island overview. Another press of it flies back, and
 * a left click flies down to the clicked spot. Nothing else works while there.
 */
final class MapEditorDelegate extends CameraDelegate<Camera> implements CameraHost, MapCameraOwner,
        CampaignTools.Host {
    private static final float MIN_RADIUS = 4f;
    private static final float MAX_RADIUS = 96f;
    private static final float RADIUS_STEP = 1.15f;
    private static final int MIN_INTENSITY = 0;
    /** Seconds between resource brush dabs while the button is held. */
    private static final float RESOURCE_DAB_INTERVAL = .05f;
    private static final int MAX_INTENSITY = 100;
    private static final int INTENSITY_STEP = 5;
    private static final int LABEL_WIDTH = 150;
    private static final int PULLDOWN_WIDTH = 150;
    private static final int HINT_WIDTH = 760;
    /** Seconds from the main menu showing until a level test starts. */
    private static final float TEST_START_DELAY = 1f;

    private final @NonNull NetworkSelector network;
    private final @NonNull World world;
    private final @NonNull AnimationManager manager;
    private final @NonNull Picker picker;
    private static final float GROUND_UPDATE_INTERVAL = .1f;

    private final @NonNull TerrainEditor editor;
    private final @Nullable GroundTextures ground;
    private static final float ACCESS_UPDATE_INTERVAL = .25f;
    private final @NonNull AccessMap access_map;
    private final @NonNull PlantLayer plants;
    private final @NonNull Water water;
    private final @NonNull AccessOverlay access;
    private final @NonNull Label label_access_legend;
    // Seconds since the playable area last followed a stroke in progress.
    private float access_timer;
    // Seconds since the ground texture last followed a stroke in progress.
    private float ground_timer;
    // An edit finished and the ground texture should settle, possibly rebuilding the whole island.
    private boolean ground_settle;
    private final @NonNull MapSettings settings;
    private final @NonNull ResourceLayer layer;
    // Terrain steps and resource strokes, newest first, so undo takes them back in the order they were made.
    private final Deque<Object> history = new ArrayDeque<>();
    private ResourceLayer.@Nullable Stroke resource_stroke;
    // Resources the latest terrain edit left off the playable area; kept with it in the history until the next edit
    // or an undo, as the playable area catches up with an edit after the stroke ends.
    private ResourceLayer.@Nullable Stroke pruned;
    // Likewise the units and buildings it left off the playable area, when editing a campaign level.
    private ScenarioLayer.@Nullable Stroke pruned_objects;
    private float resource_timer;
    // Whether the resources differ from the generated ones, and whether they changed since the last save.
    private boolean resources_edited;
    private boolean resources_modified;
    private final @NonNull BrushRenderer brush_renderer = new BrushRenderer();
    private final @NonNull Animated ticker = this::tick;
    private final @NonNull LandscapeLocation location = new LandscapeLocation();
    private final @NonNull Random random = new Random(LocalEventQueue.getQueue().getHighPrecisionManager().getTick());

    private final @NonNull EditorCamera game_camera;
    private final @NonNull Toolbar toolbar;
    private final @NonNull Label label_radius;
    private final @NonNull Label label_intensity;
    private final @NonNull Label label_hint;

    // The campaign editor's tools and bar, when editing a campaign level, and whether its tool is the one in use.
    private final @Nullable CampaignTools campaign;
    private final @Nullable Form campaign_bar;
    private boolean campaign_active;
    // What the cursor points at, written on the hint line in place of the tool's hint.
    private @Nullable String hover_text;

    private @Nullable String map_name;
    // Whether the heights differ from what the settings generate, so saving must keep them.
    private boolean edited;

    private @NonNull Brush brush = Brush.HEIGHT;
    private float radius = 16f;
    private int intensity = 50;

    // The ground under the cursor, in meters.
    private boolean has_cursor;
    private float cursor_x;
    private float cursor_y;

    // The stroke in progress: 0 when none, else 1 for the left button and -1 for the right.
    private int stroke_sign;
    // Ground height where the stroke began: the flatten target, and the level the cursor is held to while painting.
    private float stroke_z;
    private int random_seed;
    private float ramp_x;
    private float ramp_y;

    private boolean map_mode;

    // The middle button view turn in progress. Drags and the release keep arriving here, like in a game.
    private @Nullable LookDelegate look;

    MapEditorDelegate(@NonNull NetworkSelector network, @NonNull GUIRoot gui_root, @NonNull World world,
            @NonNull AnimationManager manager, @NonNull Picker picker, @NonNull Cheat view,
            @NonNull CameraState camera_state, @NonNull TerrainEditor editor, @Nullable GroundTextures ground,
            @NonNull AccessMap access_map, @NonNull AccessOverlay access, @NonNull ResourceLayer layer,
            @NonNull PlantLayer plants, @NonNull Water water, @NonNull MapSettings settings,
            @Nullable String map_name, boolean edited, boolean resources_edited, @Nullable CampaignTools campaign) {
        super(gui_root, null);
        this.network = network;
        this.world = world;
        this.manager = manager;
        this.picker = picker;
        this.editor = editor;
        this.ground = ground;
        this.access_map = access_map;
        this.plants = plants;
        this.water = water;
        this.access = access;
        this.settings = settings;
        this.map_name = map_name;
        this.edited = edited;
        this.layer = layer;
        this.resources_edited = resources_edited;
        this.campaign = campaign;

        game_camera = new EditorCamera(this, camera_state);
        setCamera(game_camera);
        // Start south of the middle, looking north over the island.
        float center = world.getHeightMap().getMetersPerWorld() / 2f;
        game_camera.reset(center, center * .75f);

        toolbar = new Toolbar();
        // One dropdown of terrain brushes and one of resource brushes, each with a button beside it that picks
        // whichever brush its dropdown shows.
        Landscape.TerrainType terrain = Landscape.TerrainType.values()[settings.terrain()];
        PulldownMenu<Brush> menu_terrain = new PulldownMenu<>();
        PulldownMenu<Brush> menu_resource = new PulldownMenu<>();
        for (Brush b : Brush.values())
            (b.isResourceBrush() ? menu_resource : menu_terrain).addItem(new PulldownItem<>(b.getName(terrain), b));
        RadioButtonGroup tools = new RadioButtonGroup();
        RadioButton radio_terrain = new RadioButton(true, tools, MapEditor.i18n("tool_terrain"));
        RadioButton radio_resource = new RadioButton(false, tools, MapEditor.i18n("tool_resources"));
        PulldownButton<Brush> pulldown_terrain = new PulldownButton<>(gui_root, menu_terrain, 0, PULLDOWN_WIDTH);
        PulldownButton<Brush> pulldown_resource = new PulldownButton<>(gui_root, menu_resource, 0, PULLDOWN_WIDTH);
        radio_terrain.addMouseClickListener((_, _, _, _) -> {
            campaign_active = false;
            selectBrush(chosenBrush(menu_terrain));
            setFocus();
        });
        radio_resource.addMouseClickListener((_, _, _, _) -> {
            campaign_active = false;
            selectBrush(chosenBrush(menu_resource));
            setFocus();
        });
        // Choosing from a dropdown also switches to it.
        menu_terrain.addItemChosenListener((menu, _) -> {
            tools.mark(radio_terrain);
            campaign_active = false;
            selectBrush(chosenBrush(menu));
            setFocus();
        });
        menu_resource.addItemChosenListener((menu, _) -> {
            tools.mark(radio_resource);
            campaign_active = false;
            selectBrush(chosenBrush(menu));
            setFocus();
        });
        Group group_terrain = tool(radio_terrain, pulldown_terrain);
        Group group_resource = tool(radio_resource, pulldown_resource);
        toolbar.addChild(group_terrain);
        toolbar.addChild(group_resource);
        group_terrain.place();
        group_resource.place(group_terrain, RIGHT_MID, 20);
        label_radius = new Label("", Skin.getSkin().getEditFont(), LABEL_WIDTH);
        label_intensity = new Label("", Skin.getSkin().getEditFont(), LABEL_WIDTH);
        CheckBox check_trees = new CheckBox(view.draw_trees, MapEditor.i18n("show_trees"));
        check_trees.addCheckBoxListener(marked -> {
            view.draw_trees = marked;
            setFocus();
        });
        label_access_legend = new Label(MapEditor.i18n("access_legend"), Skin.getSkin().getEditFont());
        CheckBox check_access = new CheckBox(false, MapEditor.i18n("show_access"));
        check_access.addCheckBoxListener(marked -> {
            access.setVisible(marked);
            if (marked)
                addChild(label_access_legend);
            else
                label_access_legend.remove();
            placeAccessLegend();
            setFocus();
        });
        CheckBox check_wireframe = new CheckBox(view.line_mode, MapEditor.i18n("wireframe"));
        check_wireframe.addCheckBoxListener(marked -> {
            view.line_mode = marked;
            setFocus();
        });
        HorizButton button_undo = new HorizButton(MapEditor.i18n("undo"), 80);
        button_undo.addMouseClickListener((_, _, _, _) -> {
            undo();
            setFocus();
        });
        HorizButton button_menu = new HorizButton(MapEditor.i18n("menu"), 80);
        button_menu.addMouseClickListener((_, _, _, _) -> openMenu());
        label_hint = new Label("", Skin.getSkin().getEditFont(), HINT_WIDTH);
        Label label_controls = new Label(MapEditor.i18n("hint_controls"), Skin.getSkin().getEditFont(),
                HINT_WIDTH);
        toolbar.addChild(label_radius);
        toolbar.addChild(label_intensity);
        toolbar.addChild(check_trees);
        toolbar.addChild(check_wireframe);
        toolbar.addChild(check_access);
        toolbar.addChild(button_undo);
        toolbar.addChild(button_menu);
        toolbar.addChild(label_hint);
        toolbar.addChild(label_controls);
        label_radius.place(group_resource, RIGHT_MID, 20);
        label_intensity.place(label_radius, RIGHT_MID);
        check_trees.place(label_intensity, RIGHT_MID);
        check_wireframe.place(check_trees, RIGHT_MID);
        check_access.place(check_wireframe, RIGHT_MID);
        button_undo.place(check_access, RIGHT_MID, 20);
        button_menu.place(button_undo, RIGHT_MID);
        label_hint.place(group_terrain, BOTTOM_LEFT);
        label_controls.place(label_hint, BOTTOM_LEFT);
        toolbar.compileCanvas();
        addChild(toolbar);
        if (campaign != null) {
            campaign_bar = campaign.createBar(this, tools);
            addChild(campaign_bar);
        } else {
            campaign_bar = null;
        }

        refreshLabels();
    }

    /** A tool's button with its dropdown beside it, kept together so the row stays as tall as the dropdown. */
    private static @NonNull Group tool(@NonNull RadioButton radio, @NonNull PulldownButton<Brush> pulldown) {
        Group group = new Group();
        group.addChild(radio);
        group.addChild(pulldown);
        radio.place();
        pulldown.place(radio, RIGHT_MID);
        group.compileCanvas();
        return group;
    }

    /**
     * The game camera, able to zoom out twice as far as in a game (200 m instead of 100 m) so more of the island
     * fits in view while editing. The unlocked cinematic camera still goes higher.
     */
    private static final class EditorCamera extends GameCamera {
        private static final float EDITOR_MAX_Z = 2 * GameCamera.MAX_Z;

        EditorCamera(@NonNull CameraHost host, @NonNull CameraState state) {
            super(host, state);
        }

        @Override
        protected float getMaxZ() {
            return Math.max(EDITOR_MAX_Z, super.getMaxZ());
        }

        float maxZ() {
            return getMaxZ();
        }
    }

    /**
     * A terrain stroke in the undo history, with the resources it left off the playable area. The terrain editor
     * keeps what it changed in the heights.
     */
    private record TerrainStep(ResourceLayer.@NonNull Stroke pruned, ScenarioLayer.@Nullable Stroke pruned_objects) {
    }

    /** The toolbar looks like a window but Escape on it opens the editor menu instead of closing it. */
    private final class Toolbar extends Form {
        @Override
        public void cancel() {
            openMenu();
        }
    }

    // ---- CameraHost ----

    @Override
    public @NonNull World getWorld() {
        return world;
    }

    @Override
    public @NonNull Picker getPicker() {
        return picker;
    }

    /** The editor camera's ceiling, so turning the view or jumping from map mode keeps the zoom. */
    @Override
    public float getMaxCameraZ() {
        return game_camera.maxZ();
    }

    // ---- Lifecycle ----

    @Override
    protected void doAdd() {
        super.doAdd();
        LocalEventQueue.getQueue().getHighPrecisionManager().registerAnimation(ticker);
    }

    @Override
    protected void doRemove() {
        super.doRemove();
        LocalEventQueue.getQueue().getHighPrecisionManager().removeAnimation(ticker);
        cancelStroke();
    }

    @Override
    public void displayChangedNotify(int width, int height) {
        super.displayChangedNotify(width, height);
        toolbar.setPos((width - toolbar.getWidth()) / 2, height - toolbar.getHeight());
        if (campaign_bar != null)
            campaign_bar.setPos((width - campaign_bar.getWidth()) / 2, 0);
        placeAccessLegend();
    }

    /** Just below the toolbar, centred. */
    private void placeAccessLegend() {
        label_access_legend.setPos((getWidth() - label_access_legend.getWidth()) / 2,
                toolbar.getY() - label_access_legend.getHeight() - 4);
    }

    @Override
    public boolean canScroll() {
        // Keep edge scrolling in step with the cursor, as the game's camera delegates do.
        var input = Renderer.getLocalInput();
        float scale = getGUIRoot().getGlobalScale();
        game_camera.mouseMoved(Math.round(input.getMouseX() / scale), Math.round(input.getMouseY() / scale));
        return !map_mode && getGUIRoot().getModalDelegate() == null;
    }

    /** Runs every frame: the world's animations, painting while a button is held, and pushing edits to the GPU. */
    private void tick(float t) {
        world.tick(t);
        manager.runAnimations(t);
        has_cursor = !map_mode && pickCursor();
        if (stroke_sign != 0 && has_cursor && campaign_active && campaign != null)
            campaign.paint(t, cursor_x, cursor_y);
        else if (stroke_sign != 0 && has_cursor && !brush.isDragShape())
            paint(t);
        if (campaign != null) {
            String text = campaign.hover(has_cursor && getGUIRoot().getModalDelegate() == null, cursor_x,
                    cursor_y);
            if (!Objects.equals(text, hover_text)) {
                hover_text = text;
                refreshLabels();
            }
        }
        editor.flush();
        updateGround(t);
        updateAccess(t);
    }

    /**
     * Finds the ground under the mouse. While painting, the ground under the brush keeps moving, and picking it
     * would pull the brush along the view ray towards or away from the camera, which then changes the ground again.
     * So during a stroke the mouse ray is followed to the level the stroke began at instead, which only moves when
     * the mouse or the camera does.
     */
    private boolean pickCursor() {
        if (!picker.pickLocation(game_camera.getState(), location))
            return false;
        cursor_x = location.x;
        cursor_y = location.y;
        if (stroke_sign == 0 || brush.isDragShape() || campaign_active)
            return true;
        CameraState state = game_camera.getState();
        float eye_x = state.getCurrentX();
        float eye_y = state.getCurrentY();
        float eye_z = state.getCurrentZ();
        // The picked point is on the mouse ray, so the ray runs from the eye through it.
        float dz = editor.getHeight(toGrid(location.x), toGrid(location.y)) - eye_z;
        float t = (stroke_z - eye_z) / dz;
        if (dz < 0f && t > 0f) {
            cursor_x = eye_x + (location.x - eye_x) * t;
            cursor_y = eye_y + (location.y - eye_y) * t;
        }
        return true;
    }

    // ---- Brushes ----

    private static float toGrid(float meters) {
        return meters / HeightMap.METERS_PER_UNIT_GRID;
    }

    private static @NonNull Brush chosenBrush(@NonNull PulldownMenu<Brush> menu) {
        return Objects.requireNonNull(menu.getItem(menu.getChosenItemIndex()).getAttachment());
    }

    private void selectBrush(@NonNull Brush new_brush) {
        cancelStroke();
        brush = new_brush;
        refreshLabels();
    }

    // ---- Campaign tools ----

    @Override
    public void campaignToolChosen() {
        cancelStroke();
        campaign_active = true;
        refreshLabels();
        setFocus();
    }

    @Override
    public float getRadius() {
        return radius;
    }

    @Override
    public float getDensity() {
        return intensity / 100f;
    }

    @Override
    public void refreshLabels() {
        label_radius.clear();
        label_radius.append(MapEditor.i18n("radius", Math.round(radius)));
        label_intensity.clear();
        label_intensity.append(MapEditor.i18n("intensity", intensity));
        label_hint.clear();
        if (hover_text != null)
            label_hint.append(hover_text);
        else if (campaign != null && (campaign_active || campaign.isPicking()))
            label_hint.append(campaign.getHint());
        else
            label_hint.append(brush.getHint());
    }

    private void beginStroke(int sign) {
        if (stroke_sign != 0 || !has_cursor)
            return;
        stroke_sign = sign;
        if (campaign_active && campaign != null) {
            campaign.begin(sign, cursor_x, cursor_y);
            return;
        }
        if (brush.isDragShape()) {
            ramp_x = cursor_x;
            ramp_y = cursor_y;
            return;
        }
        stroke_z = editor.getHeight(toGrid(cursor_x), toGrid(cursor_y));
        if (brush.isResourceBrush()) {
            resource_stroke = new ResourceLayer.Stroke();
            // The first dab lands at once.
            resource_timer = RESOURCE_DAB_INTERVAL;
            return;
        }
        random_seed = random.nextInt();
        beginTerrainStroke();
    }

    @Override
    public void remember(@NonNull Object step) {
        history.push(step);
        while (history.size() > TerrainEditor.MAX_UNDO_STEPS)
            history.removeLast();
    }

    private void beginTerrainStroke() {
        pruned = new ResourceLayer.Stroke();
        pruned_objects = campaign != null ? new ScenarioLayer.Stroke() : null;
        editor.beginStroke();
    }

    /** Ends the terrain editor's stroke and, if it changed anything, records it to undo. */
    private void finishTerrainStroke() {
        ResourceLayer.Stroke stroke = pruned;
        if (editor.endStroke() && stroke != null) {
            remember(new TerrainStep(stroke, pruned_objects));
        } else {
            pruned = null;
            pruned_objects = null;
        }
    }

    private void finishResourceStroke() {
        ResourceLayer.Stroke stroke = resource_stroke;
        resource_stroke = null;
        if (stroke != null && !stroke.isEmpty()) {
            remember(stroke);
            resources_edited = true;
            resources_modified = true;
        }
    }

    /**
     * Brings the ground texture after the heights: every so often while painting, and in full once an edit is done.
     */
    private void updateGround(float t) {
        if (ground == null)
            return;
        ground_timer += t;
        if (stroke_sign == 0 && ground_settle) {
            ground_settle = false;
            ground.settle();
        } else if (ground.hasChanges() && ground_timer >= GROUND_UPDATE_INTERVAL) {
            ground_timer = 0f;
            ground.update();
        }
    }

    /**
     * Brings the playable area after the heights, now and then while painting and at once otherwise, takes away the
     * resources an edit left off it, and brings the overlay, the plants and the sea after it.
     */
    private void updateAccess(float t) {
        access_timer += t;
        if (access_map.isStale() && (stroke_sign == 0 || access_timer >= ACCESS_UPDATE_INTERVAL)) {
            access_timer = 0f;
            access_map.compute();
            access.mapChanged();
            // The ground under them changed, so plants and the sea's open water follow the sort too.
            plants.update();
            water.updateOceanPatches();
            // Units could no longer reach them. Undoing the edit brings them back.
            if (layer.prune(pruned != null ? pruned : new ResourceLayer.Stroke())) {
                resources_edited = true;
                resources_modified = true;
                ground_settle = true;
            }
            if (campaign != null)
                campaign.getLayer().prune(pruned_objects != null ? pruned_objects : new ScenarioLayer.Stroke());
        }
        access.update(Renderer.getRenderer().getRenderContext());
    }

    private void endStroke() {
        if (stroke_sign == 0)
            return;
        if (campaign_active && campaign != null) {
            campaign.end(true);
            stroke_sign = 0;
            return;
        }
        ground_settle = true;
        if (brush.isResourceBrush()) {
            finishResourceStroke();
        } else if (brush.isDragShape()) {
            if (has_cursor) {
                float ax = toGrid(ramp_x);
                float ay = toGrid(ramp_y);
                float bx = toGrid(cursor_x);
                float by = toGrid(cursor_y);
                beginTerrainStroke();
                editor.applyRamp(ax, ay, editor.getHeight(ax, ay), bx, by, editor.getHeight(bx, by), toGrid(radius),
                        intensity / 100f, stroke_sign);
                finishTerrainStroke();
            }
        } else {
            finishTerrainStroke();
        }
        stroke_sign = 0;
    }

    /** Drops a stroke without laying a pending ramp, keeping whatever was already painted as one undo step. */
    private void cancelStroke() {
        if (stroke_sign != 0 && campaign_active && campaign != null) {
            campaign.end(false);
        } else if (stroke_sign != 0 && brush.isResourceBrush()) {
            finishResourceStroke();
            ground_settle = true;
        } else if (stroke_sign != 0 && !brush.isDragShape()) {
            finishTerrainStroke();
            ground_settle = true;
        }
        stroke_sign = 0;
    }

    private void paint(float t) {
        if (brush.isResourceBrush()) {
            Resource resource = brush.getResource();
            // Dabs rather than every frame: each fills the brush to its density, so more would only cost time.
            resource_timer += t;
            if (resource_timer < RESOURCE_DAB_INTERVAL || resource_stroke == null)
                return;
            resource_timer = 0f;
            // Right click takes away what the brush paints; the eraser takes away everything with either button.
            if (resource != null && stroke_sign > 0)
                layer.paint(resource, cursor_x, cursor_y, radius, intensity / 100f, resource_stroke);
            else
                layer.erase(resource, cursor_x, cursor_y, radius, resource_stroke);
            return;
        }
        float gx = toGrid(cursor_x);
        float gy = toGrid(cursor_y);
        float r = toGrid(radius);
        float strength = intensity / 100f;
        switch (brush) {
            case HEIGHT -> editor.applyHeight(gx, gy, r, strength, stroke_sign, t);
            case FLATTEN -> editor.applyFlatten(gx, gy, r, strength, stroke_sign, t, stroke_z);
            case SMOOTH -> editor.applySmooth(gx, gy, r, strength, stroke_sign, t);
            case RANDOM -> editor.applyRandom(gx, gy, r, strength, stroke_sign, t, random_seed);
            default -> {
            }
        }
    }

    private void undo() {
        cancelStroke();
        ground_settle = true;
        Object step = history.poll();
        pruned = null;
        pruned_objects = null;
        if (step instanceof ResourceLayer.Stroke stroke) {
            layer.undo(stroke);
            resources_modified = true;
        } else if (step instanceof ScenarioLayer.Stroke objects && campaign != null) {
            campaign.getLayer().undo(objects);
        } else if (step instanceof TerrainStep terrain && editor.undo()) {
            // Their cells rejoin the playable area with the heights, so they are not pruned again.
            if (!terrain.pruned().isEmpty()) {
                layer.undo(terrain.pruned());
                resources_modified = true;
            }
            ScenarioLayer.Stroke objects = terrain.pruned_objects();
            if (objects != null && !objects.isEmpty() && campaign != null)
                campaign.getLayer().undo(objects);
        } else {
            getGUIRoot().getInfoPrinter().print(MapEditor.i18n("nothing_to_undo"));
        }
    }

    // ---- Mouse and keys ----

    private boolean isOverToolbar(int x, int y) {
        return isOver(toolbar, x, y) || (campaign_bar != null && isOver(campaign_bar, x, y));
    }

    private static boolean isOver(@NonNull Form form, int x, int y) {
        return x >= form.getX() && x < form.getX() + form.getWidth() && y >= form.getY()
                && y < form.getY() + form.getHeight();
    }

    @Override
    public void mousePressed(@NonNull MouseButton button, int x, int y) {
        if (map_mode || isOverToolbar(x, y))
            return;
        if (campaign != null && campaign.isPicking() && button != MouseButton.MIDDLE) {
            // Picking for a trigger: left takes what is under the cursor, right calls it off.
            if (button == MouseButton.RIGHT)
                campaign.cancelPick();
            else if (has_cursor)
                campaign.pickAt(cursor_x, cursor_y);
            return;
        }
        switch (button) {
            case LEFT -> beginStroke(1);
            case RIGHT -> beginStroke(-1);
            case MIDDLE -> {
                cancelStroke();
                look = new LookDelegate();
                getGUIRoot().pushDelegate(look);
            }
        }
    }

    @Override
    public void mouseReleased(@NonNull MouseButton button, int x, int y) {
        if (map_mode) {
            // Clicking the overview flies down to that spot, as in a game.
            if (button == MouseButton.LEFT && getCamera() instanceof MapCamera map_camera)
                picker.pickMapGoto(x, y, map_camera);
            return;
        }
        if (button == MouseButton.MIDDLE && look != null) {
            look.pop();
            look = null;
        }
        if ((button == MouseButton.LEFT && stroke_sign > 0) || (button == MouseButton.RIGHT && stroke_sign < 0))
            endStroke();
    }

    @Override
    public void mouseMoved(int x, int y) {
        game_camera.mouseMoved(x, y);
    }

    @Override
    public void mouseDragged(@NonNull MouseButton button, int x, int y, int relative_x, int relative_y,
            int absolute_x, int absolute_y) {
        if (button == MouseButton.MIDDLE && look != null) {
            look.getCamera().mouseMoved(x, y);
            return;
        }
        game_camera.mouseMoved(x, y);
    }

    @Override
    public void mouseScrolled(int amount) {
        if (map_mode)
            return;
        // The per key state, since on some platforms the modifier flags stay set after the key is let go.
        var input = Renderer.getLocalInput();
        int steps = Integer.signum(amount);
        if ((input.isKeyDown(Key.LCONTROL) || input.isKeyDown(Key.RCONTROL)) && campaign_active
                && campaign != null && campaign.resizeHovered(steps)) {
            refreshLabels();
        } else if (input.isKeyDown(Key.LCONTROL) || input.isKeyDown(Key.RCONTROL)) {
            radius = Math.clamp(steps > 0 ? radius * RADIUS_STEP : radius / RADIUS_STEP, MIN_RADIUS, MAX_RADIUS);
            refreshLabels();
        } else if (input.isKeyDown(Key.LSHIFT) || input.isKeyDown(Key.RSHIFT)) {
            intensity = Math.clamp(intensity + steps * INTENSITY_STEP, MIN_INTENSITY, MAX_INTENSITY);
            refreshLabels();
        } else {
            game_camera.mouseScrolled(amount);
        }
    }

    @Override
    public void handleInput(@NonNull InputEvent event) {
        // Space is also the activate key, which would otherwise click the editor like a left button.
        event.consumeAction(GameAction.UI_ACTIVATE);
        if (event.hasAction(GameAction.CAMERA_MAP_MODE)) {
            // Only a fresh press toggles map mode; holding the key must not flip it back and forth.
            if (event.getPhase() == InputPhase.PRESSED && !map_mode) {
                event.consumeAction(GameAction.CAMERA_MAP_MODE);
                enterMapMode();
                event.consume();
                return;
            }
            if (event.getPhase() != InputPhase.PRESSED) {
                event.consumeAction(GameAction.CAMERA_MAP_MODE);
                event.consume();
                return;
            }
            // A press while in map mode is the map camera's to handle: it flies back.
        }
        if (event.getPhase() == InputPhase.PRESSED && !map_mode) {
            if (event.isControlDown() && event.getKeyCode() == Key.Z) {
                undo();
                event.consume();
                return;
            }
            if (event.consumeAction(GameAction.GLOBAL_MENU) || event.consumeAction(GameAction.UI_CANCEL)) {
                if (campaign != null && campaign.isPicking())
                    campaign.cancelPick();
                else
                    openMenu();
                event.consume();
                return;
            }
        }
        super.handleInput(event);
    }

    // ---- Brush outline ----

    @Override
    public void render3D(@NonNull LandscapeRenderer renderer, @NonNull RenderQueues render_queues,
            @NonNull CameraState state, @NonNull MatrixStack model_view, @NonNull MatrixStack projection) {
        access.render(Renderer.getRenderer().getRenderContext(), renderer, state);
        // The campaign's markers stay while windows are open, so a trigger's areas show beside its window.
        boolean show_brush = has_cursor && !map_mode && getGUIRoot().getModalDelegate() == null
                && (campaign == null || !campaign.isPicking());
        if (!show_brush && campaign == null)
            return;
        // The water reflection is drawn from a camera mirrored below the sea; the brush has no place in it.
        if (state.getCurrentZ() < world.getHeightMap().getSeaLevelMeters())
            return;
        try (BrushRenderer.Batch batch = brush_renderer.begin(renderer, model_view, projection)) {
            if (campaign != null)
                campaign.render(batch);
            if (!show_brush)
                return;
            float r = stroke_sign < 0 ? 1f : .4f;
            float g = stroke_sign < 0 ? .4f : 1f;
            float b = stroke_sign == 0 ? 1f : .4f;
            batch.circle(cursor_x, cursor_y, radius, r, g, b, .9f);
            batch.dot(cursor_x, cursor_y, r, g, b, .9f);
            if (brush.isDragShape() && stroke_sign != 0) {
                batch.circle(ramp_x, ramp_y, radius, r, g, b, .9f);
                batch.line(ramp_x, ramp_y, cursor_x, cursor_y, r, g, b, .9f);
            }
        }
    }

    // ---- Menu, saving and leaving ----

    // ---- Map mode ----

    private void enterMapMode() {
        cancelStroke();
        map_mode = true;
        has_cursor = false;
        toolbar.remove();
        setFocus();
        game_camera.disable();
        setCamera(new MapCamera(this, game_camera));
        getCamera().enable();
    }

    @Override
    public void exitMapMode() {
        map_mode = false;
        getCamera().disable();
        // Land exactly where map mode started instead of easing in from the overview.
        game_camera.getState().snapToTarget();
        setCamera(game_camera);
        game_camera.enable();
        addChild(toolbar);
    }

    private void openMenu() {
        cancelStroke();
        // Keep the keyboard on the editor once the menu closes, not on a toolbar button.
        setFocus();
        if (getGUIRoot().getModalDelegate() != null)
            return;
        if (campaign == null) {
            getGUIRoot().addModalForm(new EditorMenu(this::save, this::exit));
            return;
        }
        getGUIRoot().addModalForm(new EditorMenu(CampaignEditor.i18n("editor_headline"), List.of(
                new EditorMenu.Entry(MapEditor.i18n("resume"), () -> {
                }),
                new EditorMenu.Entry(CampaignEditor.i18n("save_campaign"), () -> saveCampaign(null)),
                new EditorMenu.Entry(CampaignEditor.i18n("test_level"), this::chooseTestDifficulty),
                new EditorMenu.Entry(CampaignEditor.i18n("campaign_levels"), this::backToCampaign),
                new EditorMenu.Entry(MapEditor.i18n("exit_editor"), this::exit))));
    }

    /** The resources as they are now, by kind. */
    private MapFile.@NonNull Resources currentResources() {
        @SuppressWarnings("unchecked")
        List<int[]>[] positions = new List[Resource.values().length];
        for (Resource kind : Resource.values())
            positions[kind.ordinal()] = layer.positions(kind);
        return new MapFile.Resources(positions);
    }

    private void save() {
        Path dir = MapEditor.getMapsDir();
        if (dir == null) {
            getGUIRoot().addModalForm(new MessageForm(MapEditor.i18n("no_maps_dir")));
            return;
        }
        getGUIRoot().addModalForm(new SaveMapDialog(getGUIRoot(), dir, map_name != null ? map_name : "", name -> {
            boolean keep_heights = edited || editor.isModified();
            MapFile.Resources current = currentResources();
            float[][] heights = editor.copyHeights();
            // The preview shows the map as it is, even what is generated again from the settings.
            MapPreview preview = MapPreview.render(heights, settings, current);
            try {
                new MapFile(name, settings, keep_heights ? heights : null, resources_edited ? current : null,
                        preview).save(dir);
            } catch (IOException e) {
                getGUIRoot().addModalForm(new MessageForm(MapEditor.i18n("save_failed", e.getMessage())));
                return;
            }
            map_name = name;
            edited = keep_heights;
            editor.markSaved();
            resources_modified = false;
            getGUIRoot().getInfoPrinter().print(MapEditor.i18n("saved", name));
        }));
    }

    // ---- Campaign levels ----

    /** Puts the island as it is now into the campaign's level, in memory, so it is saved with the campaign. */
    private void storeLevel(@NonNull CampaignTools tools) {
        CampaignSession session = tools.getSession();
        CampaignFile.Level level = session.getLevel();
        boolean keep_heights = edited || editor.isModified();
        MapFile.Resources current = currentResources();
        float[][] heights = editor.copyHeights();
        level.heights = keep_heights ? heights : null;
        level.resources = resources_edited ? current : null;
        level.preview = MapPreview.render(heights, settings, current);
        if (editor.isModified() || resources_modified || tools.getLayer().isModified())
            session.markModified();
        edited = keep_heights;
        editor.markSaved();
        resources_modified = false;
        tools.getLayer().markSaved();
    }

    /**
     * Saves the campaign, asking for a name the first time.
     *
     * @param after run once it is saved, or null
     */
    private void saveCampaign(@Nullable Runnable after) {
        CampaignTools tools = Objects.requireNonNull(campaign);
        Path dir = CampaignEditor.getCampaignsDir();
        if (dir == null) {
            getGUIRoot().addModalForm(new MessageForm(MapEditor.i18n("no_maps_dir")));
            return;
        }
        storeLevel(tools);
        CampaignSession session = tools.getSession();
        String saved_name = session.getSavedName();
        if (saved_name != null) {
            writeCampaign(dir, saved_name, after);
            return;
        }
        getGUIRoot().addModalForm(new SaveMapDialog(getGUIRoot(), CampaignEditor.i18n("save_campaign_caption"),
                CampaignEditor.i18n("campaign_name"), name -> CampaignFile.pathFor(dir, name),
                name -> CampaignEditor.i18n("overwrite_campaign", name), session.file.name,
                name -> writeCampaign(dir, name, after)));
    }

    private void writeCampaign(@NonNull Path dir, @NonNull String name, @Nullable Runnable after) {
        CampaignSession session = Objects.requireNonNull(campaign).getSession();
        try {
            session.save(dir, name);
        } catch (IOException e) {
            getGUIRoot().addModalForm(new MessageForm(CampaignEditor.i18n("campaign_save_failed",
                    String.valueOf(e.getMessage()))));
            return;
        }
        getGUIRoot().getInfoPrinter().print(CampaignEditor.i18n("campaign_saved", name));
        if (after != null)
            after.run();
    }

    /** Points out what would spoil the level before testing it, and tests it if asked to anyway. */
    private void chooseTestDifficulty() {
        List<String> problems = Objects.requireNonNull(campaign).getScenario().problems();
        if (problems.isEmpty()) {
            showTestDifficulties();
            return;
        }
        getGUIRoot().addModalForm(new QuestionForm(CampaignEditor.i18n("problems_test", String.join("\n",
                problems)), (_, _, _, _) -> showTestDifficulties()));
    }

    private void showTestDifficulties() {
        getGUIRoot().addModalForm(new EditorMenu(CampaignEditor.i18n("test_caption"), List.of(
                new EditorMenu.Entry(CampaignEditor.i18n("difficulty_easy"),
                        () -> saveCampaign(() -> test(CampaignState.DIFFICULTY_EASY))),
                new EditorMenu.Entry(CampaignEditor.i18n("difficulty_normal"),
                        () -> saveCampaign(() -> test(CampaignState.DIFFICULTY_NORMAL))),
                new EditorMenu.Entry(CampaignEditor.i18n("difficulty_hard"),
                        () -> saveCampaign(() -> test(CampaignState.DIFFICULTY_HARD))),
                new EditorMenu.Entry(CampaignEditor.i18n("back"), () -> {
                }))));
    }

    /**
     * Plays the level as saved, from the main menu as a campaign would, and comes back to it in the editor once the
     * game is over.
     */
    private void test(int difficulty) {
        CampaignSession session = Objects.requireNonNull(campaign).getSession();
        Path dir = Objects.requireNonNull(CampaignEditor.getCampaignsDir());
        int level = session.level;
        Path path = CampaignFile.pathFor(dir, session.file.name);
        closeEditor();
        Renderer.startMenu(network, getGUIRoot().getGUI(), menu -> {
            // Started once the menu has faded in, so the game's loading does not overlap the menu's.
            TimerAnimation start = new TimerAnimation(LocalEventQueue.getQueue().getManager(), timer -> {
                timer.stop();
                CustomCampaign.test(network, menu.getGUIRoot(), path, session.file, level, difficulty,
                        (back_network, gui) -> CampaignEditorForm.editLevel(back_network, gui, session, level));
            }, TEST_START_DELAY);
            start.start();
        });
    }

    /** Back to the campaign's list of levels, keeping this level's changes in memory. */
    private void backToCampaign() {
        CampaignTools tools = Objects.requireNonNull(campaign);
        storeLevel(tools);
        CampaignSession session = tools.getSession();
        closeEditor();
        Renderer.startMenu(network, getGUIRoot().getGUI(), menu -> menu.setMenuCentered(new CampaignEditorForm(
                network, menu.getGUIRoot(), menu, session)));
    }

    private boolean isModified() {
        if (editor.isModified() || resources_modified)
            return true;
        return campaign != null && (campaign.getLayer().isModified() || campaign.getSession().isModified());
    }

    private void exit() {
        if (isModified()) {
            getGUIRoot().addModalForm(new QuestionForm(campaign != null ? CampaignEditor.i18n("exit_confirm")
                    : MapEditor.i18n("exit_confirm"), (_, _, _, _) -> leave()));
        } else {
            leave();
        }
    }

    private void leave() {
        closeEditor();
        Renderer.startMenu(network, getGUIRoot().getGUI());
    }

    /** Lets go of what the editor made for drawing, as the screen is left. */
    private void closeEditor() {
        if (ground != null)
            ground.close();
        access.close();
        if (campaign != null)
            campaign.getLayer().close();
    }

    /** Turns the view with the game's first person camera while the middle button is held. */
    private final class LookDelegate extends CameraDelegate<FirstPersonCamera> {
        LookDelegate() {
            super(MapEditorDelegate.this.getGUIRoot(), new FirstPersonCamera(MapEditorDelegate.this,
                    world.getHeightMap(), game_camera.getState()));
        }

        @Override
        protected @NonNull CursorType getCursorType() {
            return CursorType.NULL;
        }
    }
}
