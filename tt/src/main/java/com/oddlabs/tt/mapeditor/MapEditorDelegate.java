package com.oddlabs.tt.mapeditor;

import com.oddlabs.net.NetworkSelector;
import com.oddlabs.tt.animation.Animated;
import com.oddlabs.tt.animation.AnimationManager;
import com.oddlabs.tt.camera.CameraHost;
import com.oddlabs.tt.camera.CameraState;
import com.oddlabs.tt.camera.FirstPersonCamera;
import com.oddlabs.tt.camera.GameCamera;
import com.oddlabs.tt.delegate.CameraDelegate;
import com.oddlabs.tt.event.LocalEventQueue;
import com.oddlabs.tt.form.MessageForm;
import com.oddlabs.tt.form.QuestionForm;
import com.oddlabs.tt.gui.CheckBox;
import com.oddlabs.tt.gui.CursorType;
import com.oddlabs.tt.gui.Form;
import com.oddlabs.tt.gui.GUIRoot;
import com.oddlabs.tt.gui.HorizButton;
import com.oddlabs.tt.gui.Label;
import com.oddlabs.tt.gui.MouseButton;
import com.oddlabs.tt.gui.RadioButton;
import com.oddlabs.tt.gui.RadioButtonGroup;
import com.oddlabs.tt.gui.Skin;
import com.oddlabs.tt.input.GameAction;
import com.oddlabs.tt.input.InputEvent;
import com.oddlabs.tt.input.InputPhase;
import com.oddlabs.tt.input.Key;
import com.oddlabs.tt.landscape.HeightMap;
import com.oddlabs.tt.landscape.World;
import com.oddlabs.tt.render.LandscapeLocation;
import com.oddlabs.tt.render.LandscapeRenderer;
import com.oddlabs.tt.render.MatrixStack;
import com.oddlabs.tt.render.Picker;
import com.oddlabs.tt.render.RenderQueues;
import com.oddlabs.tt.render.Renderer;
import com.oddlabs.tt.viewer.Cheat;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Random;

import static com.oddlabs.tt.gui.Placement.BOTTOM_LEFT;
import static com.oddlabs.tt.gui.Placement.RIGHT_MID;

/**
 * The editor screen: the island seen through the game camera, a brush toolbar along the top, and the mouse
 * painting the terrain.
 *
 * <p>Left button paints with the positive side of a brush and right button with the negative side. Ctrl plus the
 * wheel sizes the brush, Shift plus the wheel sets its intensity, and the plain wheel zooms like in a game. The
 * middle button turns the view, as it does in a game.
 */
final class MapEditorDelegate extends CameraDelegate<GameCamera> implements CameraHost {
    private static final float MIN_RADIUS = 4f;
    private static final float MAX_RADIUS = 96f;
    private static final float RADIUS_STEP = 1.15f;
    private static final int MIN_INTENSITY = 5;
    private static final int MAX_INTENSITY = 100;
    private static final int INTENSITY_STEP = 5;
    private static final int LABEL_WIDTH = 150;
    private static final int HINT_WIDTH = 760;

    private final @NonNull NetworkSelector network;
    private final @NonNull World world;
    private final @NonNull AnimationManager manager;
    private final @NonNull Picker picker;
    private final @NonNull TerrainEditor editor;
    private final @NonNull MapSettings settings;
    private final @NonNull BrushRenderer brush_renderer = new BrushRenderer();
    private final @NonNull Animated ticker = this::tick;
    private final @NonNull LandscapeLocation location = new LandscapeLocation();
    private final @NonNull Random random = new Random(LocalEventQueue.getQueue().getHighPrecisionManager().getTick());

    private final @NonNull Toolbar toolbar;
    private final @NonNull Label label_radius;
    private final @NonNull Label label_intensity;
    private final @NonNull Label label_hint;

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

    // The middle button view turn in progress. Drags and the release keep arriving here, like in a game.
    private @Nullable LookDelegate look;

    MapEditorDelegate(@NonNull NetworkSelector network, @NonNull GUIRoot gui_root, @NonNull World world,
            @NonNull AnimationManager manager, @NonNull Picker picker, @NonNull Cheat view,
            @NonNull CameraState camera_state, @NonNull TerrainEditor editor, @NonNull MapSettings settings,
            @Nullable String map_name, boolean edited) {
        super(gui_root, null);
        this.network = network;
        this.world = world;
        this.manager = manager;
        this.picker = picker;
        this.editor = editor;
        this.settings = settings;
        this.map_name = map_name;
        this.edited = edited;

        GameCamera camera = new GameCamera(this, camera_state);
        setCamera(camera);
        // Start south of the middle, looking north over the island.
        float center = world.getHeightMap().getMetersPerWorld() / 2f;
        camera.reset(center, center * .75f);

        toolbar = new Toolbar();
        RadioButtonGroup brushes = new RadioButtonGroup();
        RadioButton first = null;
        RadioButton previous = null;
        for (Brush b : Brush.values()) {
            RadioButton button = new RadioButton(b == brush, brushes, b.getName());
            button.addMouseClickListener((_, _, _, _) -> selectBrush(b));
            toolbar.addChild(button);
            if (previous == null) {
                button.place();
                first = button;
            } else {
                button.place(previous, RIGHT_MID);
            }
            previous = button;
        }
        label_radius = new Label("", Skin.getSkin().getEditFont(), LABEL_WIDTH);
        label_intensity = new Label("", Skin.getSkin().getEditFont(), LABEL_WIDTH);
        CheckBox check_trees = new CheckBox(view.draw_trees, MapEditor.i18n("show_trees"));
        check_trees.addCheckBoxListener(marked -> view.draw_trees = marked);
        CheckBox check_wireframe = new CheckBox(view.line_mode, MapEditor.i18n("wireframe"));
        check_wireframe.addCheckBoxListener(marked -> view.line_mode = marked);
        HorizButton button_undo = new HorizButton(MapEditor.i18n("undo"), 80);
        button_undo.addMouseClickListener((_, _, _, _) -> undo());
        HorizButton button_menu = new HorizButton(MapEditor.i18n("menu"), 80);
        button_menu.addMouseClickListener((_, _, _, _) -> openMenu());
        label_hint = new Label("", Skin.getSkin().getEditFont(), HINT_WIDTH);
        Label label_controls = new Label(MapEditor.i18n("hint_controls"), Skin.getSkin().getEditFont(),
                HINT_WIDTH);
        toolbar.addChild(label_radius);
        toolbar.addChild(label_intensity);
        toolbar.addChild(check_trees);
        toolbar.addChild(check_wireframe);
        toolbar.addChild(button_undo);
        toolbar.addChild(button_menu);
        toolbar.addChild(label_hint);
        toolbar.addChild(label_controls);
        label_radius.place(previous, RIGHT_MID, 20);
        label_intensity.place(label_radius, RIGHT_MID);
        check_trees.place(label_intensity, RIGHT_MID);
        check_wireframe.place(check_trees, RIGHT_MID);
        button_undo.place(check_wireframe, RIGHT_MID, 20);
        button_menu.place(button_undo, RIGHT_MID);
        label_hint.place(first, BOTTOM_LEFT);
        label_controls.place(label_hint, BOTTOM_LEFT);
        toolbar.compileCanvas();
        addChild(toolbar);

        refreshLabels();
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
    }

    @Override
    public boolean canScroll() {
        // Keep edge scrolling in step with the cursor, as the game's camera delegates do.
        var input = Renderer.getLocalInput();
        float scale = getGUIRoot().getGlobalScale();
        getCamera().mouseMoved(Math.round(input.getMouseX() / scale), Math.round(input.getMouseY() / scale));
        return getGUIRoot().getModalDelegate() == null;
    }

    /** Runs every frame: the world's animations, painting while a button is held, and pushing edits to the GPU. */
    private void tick(float t) {
        world.tick(t);
        manager.runAnimations(t);
        has_cursor = pickCursor();
        if (stroke_sign != 0 && has_cursor && !brush.isDragShape())
            paint(t);
        editor.flush();
    }

    /**
     * Finds the ground under the mouse. While painting, the ground under the brush keeps moving, and picking it
     * would pull the brush along the view ray towards or away from the camera, which then changes the ground again.
     * So during a stroke the mouse ray is followed to the level the stroke began at instead, which only moves when
     * the mouse or the camera does.
     */
    private boolean pickCursor() {
        if (!picker.pickLocation(getCamera().getState(), location))
            return false;
        cursor_x = location.x;
        cursor_y = location.y;
        if (stroke_sign == 0 || brush.isDragShape())
            return true;
        CameraState state = getCamera().getState();
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

    private void selectBrush(@NonNull Brush new_brush) {
        cancelStroke();
        brush = new_brush;
        refreshLabels();
    }

    private void refreshLabels() {
        label_radius.clear();
        label_radius.append(MapEditor.i18n("radius", Math.round(radius)));
        label_intensity.clear();
        label_intensity.append(MapEditor.i18n("intensity", intensity));
        label_hint.clear();
        label_hint.append(brush.getHint());
    }

    private void beginStroke(int sign) {
        if (stroke_sign != 0 || !has_cursor)
            return;
        stroke_sign = sign;
        if (brush.isDragShape()) {
            ramp_x = cursor_x;
            ramp_y = cursor_y;
            return;
        }
        stroke_z = editor.getHeight(toGrid(cursor_x), toGrid(cursor_y));
        random_seed = random.nextInt();
        editor.beginStroke();
    }

    private void endStroke() {
        if (stroke_sign == 0)
            return;
        if (brush.isDragShape()) {
            if (has_cursor) {
                float ax = toGrid(ramp_x);
                float ay = toGrid(ramp_y);
                float bx = toGrid(cursor_x);
                float by = toGrid(cursor_y);
                editor.beginStroke();
                editor.applyRamp(ax, ay, editor.getHeight(ax, ay), bx, by, editor.getHeight(bx, by), toGrid(radius),
                        intensity / 100f, stroke_sign);
                editor.endStroke();
            }
        } else {
            editor.endStroke();
        }
        stroke_sign = 0;
    }

    /** Drops a stroke without laying a pending ramp, keeping whatever was already painted as one undo step. */
    private void cancelStroke() {
        if (stroke_sign != 0 && !brush.isDragShape())
            editor.endStroke();
        stroke_sign = 0;
    }

    private void paint(float t) {
        float gx = toGrid(cursor_x);
        float gy = toGrid(cursor_y);
        float r = toGrid(radius);
        float strength = intensity / 100f;
        switch (brush) {
            case HEIGHT -> editor.applyHeight(gx, gy, r, strength, stroke_sign, t);
            case FLATTEN -> editor.applyFlatten(gx, gy, r, strength, stroke_sign, t, stroke_z);
            case SMOOTH -> editor.applySmooth(gx, gy, r, strength, stroke_sign, t);
            case RANDOM -> editor.applyRandom(gx, gy, r, strength, stroke_sign, t, random_seed);
            case RAMP -> {
            }
        }
    }

    private void undo() {
        cancelStroke();
        if (!editor.undo())
            getGUIRoot().getInfoPrinter().print(MapEditor.i18n("nothing_to_undo"));
    }

    // ---- Mouse and keys ----

    private boolean isOverToolbar(int x, int y) {
        return x >= toolbar.getX() && x < toolbar.getX() + toolbar.getWidth() && y >= toolbar.getY()
                && y < toolbar.getY() + toolbar.getHeight();
    }

    @Override
    public void mousePressed(@NonNull MouseButton button, int x, int y) {
        if (isOverToolbar(x, y))
            return;
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
        if (button == MouseButton.MIDDLE && look != null) {
            look.pop();
            look = null;
        }
        if ((button == MouseButton.LEFT && stroke_sign > 0) || (button == MouseButton.RIGHT && stroke_sign < 0))
            endStroke();
    }

    @Override
    public void mouseMoved(int x, int y) {
        getCamera().mouseMoved(x, y);
    }

    @Override
    public void mouseDragged(@NonNull MouseButton button, int x, int y, int relative_x, int relative_y,
            int absolute_x, int absolute_y) {
        if (button == MouseButton.MIDDLE && look != null) {
            look.getCamera().mouseMoved(x, y);
            return;
        }
        getCamera().mouseMoved(x, y);
    }

    @Override
    public void mouseScrolled(int amount) {
        // The per key state, since on some platforms the modifier flags stay set after the key is let go.
        var input = Renderer.getLocalInput();
        int steps = Integer.signum(amount);
        if (input.isKeyDown(Key.LCONTROL) || input.isKeyDown(Key.RCONTROL)) {
            radius = Math.clamp(steps > 0 ? radius * RADIUS_STEP : radius / RADIUS_STEP, MIN_RADIUS, MAX_RADIUS);
            refreshLabels();
        } else if (input.isKeyDown(Key.LSHIFT) || input.isKeyDown(Key.RSHIFT)) {
            intensity = Math.clamp(intensity + steps * INTENSITY_STEP, MIN_INTENSITY, MAX_INTENSITY);
            refreshLabels();
        } else {
            getCamera().mouseScrolled(amount);
        }
    }

    @Override
    public void handleInput(@NonNull InputEvent event) {
        if (event.getPhase() == InputPhase.PRESSED) {
            if (event.isControlDown() && event.getKeyCode() == Key.Z) {
                undo();
                event.consume();
                return;
            }
            if (event.consumeAction(GameAction.GLOBAL_MENU) || event.consumeAction(GameAction.UI_CANCEL)) {
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
        if (!has_cursor || getGUIRoot().getModalDelegate() != null)
            return;
        try (BrushRenderer.Batch batch = brush_renderer.begin(renderer, model_view, projection)) {
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

    private void openMenu() {
        cancelStroke();
        if (getGUIRoot().getModalDelegate() == null)
            getGUIRoot().addModalForm(new EditorMenu(this::save, this::exit));
    }

    private void save() {
        Path dir = MapEditor.getMapsDir();
        if (dir == null) {
            getGUIRoot().addModalForm(new MessageForm(MapEditor.i18n("no_maps_dir")));
            return;
        }
        getGUIRoot().addModalForm(new SaveMapDialog(getGUIRoot(), dir, map_name != null ? map_name : "", name -> {
            boolean keep_heights = edited || editor.isModified();
            try {
                new MapFile(name, settings, keep_heights ? editor.copyHeights() : null).save(dir);
            } catch (IOException e) {
                getGUIRoot().addModalForm(new MessageForm(MapEditor.i18n("save_failed", e.getMessage())));
                return;
            }
            map_name = name;
            edited = keep_heights;
            editor.markSaved();
            getGUIRoot().getInfoPrinter().print(MapEditor.i18n("saved", name));
        }));
    }

    private void exit() {
        if (editor.isModified()) {
            getGUIRoot().addModalForm(new QuestionForm(MapEditor.i18n("exit_confirm"),
                    (_, _, _, _) -> Renderer.startMenu(network, getGUIRoot().getGUI())));
        } else {
            Renderer.startMenu(network, getGUIRoot().getGUI());
        }
    }

    /** Turns the view with the game's first person camera while the middle button is held. */
    private final class LookDelegate extends CameraDelegate<FirstPersonCamera> {
        LookDelegate() {
            super(MapEditorDelegate.this.getGUIRoot(), new FirstPersonCamera(MapEditorDelegate.this,
                    world.getHeightMap(), MapEditorDelegate.this.getCamera().getState()));
        }

        @Override
        protected @NonNull CursorType getCursorType() {
            return CursorType.NULL;
        }
    }
}
