package com.oddlabs.tt.mapeditor;

import com.oddlabs.tt.global.Settings;
import com.oddlabs.tt.gui.Form;
import com.oddlabs.tt.gui.GUIRoot;
import com.oddlabs.tt.gui.HorizButton;
import com.oddlabs.tt.gui.PulldownButton;
import com.oddlabs.tt.gui.PulldownItem;
import com.oddlabs.tt.gui.PulldownMenu;
import com.oddlabs.tt.gui.RadioButton;
import com.oddlabs.tt.gui.RadioButtonGroup;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.function.IntConsumer;

import static com.oddlabs.tt.gui.Placement.RIGHT_MID;

/**
 * The campaign editor's part of the editor screen: a bar along the bottom to paint units, buildings and areas for a
 * player and to open the level's players, triggers and settings, and the mouse work on the island for them.
 *
 * <p>Areas are made, moved and sized on the island itself, and a trigger's areas and objects can be picked there
 * too: the trigger windows step aside until the pick is made.
 */
final class CampaignTools {
    /** Seconds between unit brush dabs while the button is held. */
    private static final float DAB_INTERVAL = .05f;
    private static final int PULLDOWN_WIDTH = 170;
    private static final int PLAYER_WIDTH = 120;
    private static final int BUTTON_WIDTH = 95;
    /** How far the mouse must move before a pressed area is dragged rather than clicked, in meters. */
    private static final float DRAG_DISTANCE = 1f;
    private static final float AREA_RESIZE_STEP = 1.15f;

    /** What the editor screen does for the campaign tools. */
    interface Host {
        @NonNull GUIRoot getGUIRoot();

        /** Keeps a change in the undo history. */
        void remember(@NonNull Object step);

        /** The campaign tool was chosen in the bar. */
        void campaignToolChosen();

        /** The hint line should be written again. */
        void refreshLabels();

        /** Opens the editor's Escape menu. */
        void openMenu();

        /** Gives the keyboard back to the editor, after a click on the bar. */
        void focusEditor();

        float getRadius();

        /** The brush intensity, 0 to 1. */
        float getDensity();
    }

    /** A choice in the tool dropdown: objects of a kind, the areas, or the eraser when both are left out. */
    private record Tool(@Nullable ObjectKind kind, boolean areas) {
    }

    private final @NonNull CampaignSession session;
    private final @NonNull ScenarioLayer layer;
    private @Nullable Host host;
    private @NonNull Tool tool = new Tool(ObjectKind.PEON, false);
    private int player;
    private @Nullable PulldownButton<Integer> pulldown_player;
    private Scenario.@Nullable Trigger highlight;

    // The stroke in progress.
    private ScenarioLayer.@Nullable Stroke stroke;
    private int stroke_sign;
    private float dab_timer;
    private Scenario.@Nullable Area dragged;
    private float drag_dx;
    private float drag_dy;
    private float press_x;
    private float press_y;
    private boolean drag_moved;

    // What the cursor points at.
    private Scenario.@Nullable Area hovered_area;
    private Scenario.@Nullable Placement hovered;

    // Picking for a trigger's setting, while its windows are put away.
    private @Nullable Param picking;
    private @Nullable List<@NonNull Form> pick_stack;
    private @Nullable IntConsumer pick_done;

    CampaignTools(@NonNull CampaignSession session, @NonNull ScenarioLayer layer) {
        this.session = session;
        this.layer = layer;
    }

    @NonNull CampaignSession getSession() {
        return session;
    }

    @NonNull ScenarioLayer getLayer() {
        return layer;
    }

    @NonNull Scenario getScenario() {
        return layer.getScenario();
    }

    private @NonNull Host host() {
        return Objects.requireNonNull(host, "host");
    }

    void markModified() {
        layer.markModified();
    }

    Scenario.@Nullable Trigger getHighlight() {
        return highlight;
    }

    /** Marks a trigger's areas and objects on the island. */
    void setHighlight(Scenario.@Nullable Trigger trigger) {
        highlight = trigger;
    }

    // ---- The bar ----

    /** Makes the bar, its radio button joining the editor's tool buttons. */
    @NonNull Form createBar(@NonNull Host host, @NonNull RadioButtonGroup tools) {
        this.host = host;
        GUIRoot gui_root = host.getGUIRoot();
        Form bar = new Form() {
            @Override
            public void cancel() {
                // Escape on the bar opens the editor's menu, as on its toolbar.
                host.openMenu();
            }
        };
        RadioButton radio = new RadioButton(false, tools, CampaignEditor.i18n("tool_campaign"));
        PulldownMenu<Tool> menu_tool = new PulldownMenu<>();
        for (ObjectKind kind : ObjectKind.values())
            menu_tool.addItem(new PulldownItem<>(kind.getName(), new Tool(kind, false)));
        menu_tool.addItem(new PulldownItem<>(CampaignEditor.i18n("tool_areas"), new Tool(null, true)));
        menu_tool.addItem(new PulldownItem<>(CampaignEditor.i18n("tool_erase"), new Tool(null, false)));
        PulldownButton<Tool> pulldown_tool = new PulldownButton<>(gui_root, menu_tool, 0, PULLDOWN_WIDTH);
        radio.addMouseClickListener((_, _, _, _) -> {
            choose(menu_tool);
        });
        menu_tool.addItemChosenListener((menu, _) -> {
            tools.mark(radio);
            choose(menu);
        });

        PulldownMenu<Integer> menu_player = new PulldownMenu<>();
        for (int i = 0; i < Scenario.NUM_PLAYERS; i++)
            menu_player.addItem(new PulldownItem<>(Scenario.playerName(i), i));
        pulldown_player = new PulldownButton<>(gui_root, menu_player, 0, PLAYER_WIDTH);
        menu_player.addItemChosenListener((menu, index) -> {
            Integer chosen = menu.getItem(index).getAttachment();
            player = chosen != null ? chosen : 0;
            colourPlayer();
            host.refreshLabels();
            host.focusEditor();
        });
        colourPlayer();

        HorizButton button_players = new HorizButton(CampaignEditor.i18n("players"), BUTTON_WIDTH);
        button_players.addMouseClickListener((_, _, _, _) -> {
            cancelPick();
            gui_root.addModalForm(new PlayersForm(gui_root, layer, host::refreshLabels));
        });
        HorizButton button_triggers = new HorizButton(CampaignEditor.i18n("triggers"), BUTTON_WIDTH);
        button_triggers.addMouseClickListener((_, _, _, _) -> {
            cancelPick();
            gui_root.addModalForm(new TriggersForm(gui_root, this));
        });
        HorizButton button_level = new HorizButton(CampaignEditor.i18n("level"), BUTTON_WIDTH);
        button_level.addMouseClickListener((_, _, _, _) -> {
            cancelPick();
            gui_root.addModalForm(new LevelForm(layer, host::refreshLabels));
        });

        bar.addChild(radio);
        bar.addChild(pulldown_tool);
        bar.addChild(pulldown_player);
        bar.addChild(button_players);
        bar.addChild(button_triggers);
        bar.addChild(button_level);
        radio.place();
        pulldown_tool.place(radio, RIGHT_MID);
        pulldown_player.place(pulldown_tool, RIGHT_MID, 20);
        button_players.place(pulldown_player, RIGHT_MID, 20);
        button_triggers.place(button_players, RIGHT_MID);
        button_level.place(button_triggers, RIGHT_MID);
        bar.compileCanvas();
        return bar;
    }

    private void choose(@NonNull PulldownMenu<Tool> menu) {
        Tool chosen = menu.getItem(menu.getChosenItemIndex()).getAttachment();
        if (chosen != null)
            tool = chosen;
        host().campaignToolChosen();
    }

    private void colourPlayer() {
        if (pulldown_player != null)
            pulldown_player.setLabelColor(Settings.getSettings().team_colours[player]);
    }

    @NonNull String getHint() {
        if (picking == Param.AREA)
            return CampaignEditor.i18n("hint_pick_area");
        if (picking != null)
            return CampaignEditor.i18n("hint_pick_object");
        ObjectKind kind = tool.kind();
        if (tool.areas())
            return CampaignEditor.i18n("hint_areas");
        if (kind == null)
            return CampaignEditor.i18n("hint_erase_objects");
        if (kind == ObjectKind.CHIEFTAIN)
            return CampaignEditor.i18n("hint_chieftain", Scenario.playerName(player));
        if (kind.isBuilding())
            return CampaignEditor.i18n("hint_building", kind.getName(), Scenario.playerName(player));
        return CampaignEditor.i18n("hint_units", kind.getName(), Scenario.playerName(player));
    }

    // ---- Strokes ----

    /** Starts a stroke: left (sign 1) places, right (-1) takes away. */
    void begin(int sign, float x, float y) {
        stroke = new ScenarioLayer.Stroke();
        stroke_sign = sign;
        dab_timer = DAB_INTERVAL;
        dragged = null;
        ScenarioLayer.Stroke current = stroke;
        if (tool.areas()) {
            Scenario.Area area = layer.areaAt(x, y);
            if (sign < 0) {
                if (area != null) {
                    layer.removeArea(area, current);
                    host().getGUIRoot().getInfoPrinter().print(CampaignEditor.i18n("area_deleted", area.name));
                }
            } else if (area != null) {
                dragged = area;
                drag_dx = area.x - x;
                drag_dy = area.y - y;
                press_x = x;
                press_y = y;
                drag_moved = false;
            } else {
                layer.addArea(x, y, host().getRadius(), current);
            }
            return;
        }
        ObjectKind kind = tool.kind();
        if (sign > 0 && kind != null && (kind.isBuilding() || kind == ObjectKind.CHIEFTAIN)) {
            ensurePlaying();
            if (!layer.placeAt(kind, player, x, y, current))
                host().getGUIRoot().getInfoPrinter().print(CampaignEditor.i18n("cannot_place", kind.getName()));
        }
    }

    /** Paints while the button is held. */
    void paint(float t, float x, float y) {
        ScenarioLayer.Stroke current = stroke;
        if (current == null)
            return;
        Scenario.Area area = dragged;
        if (area != null) {
            float dx = x - press_x;
            float dy = y - press_y;
            if (drag_moved || dx * dx + dy * dy > DRAG_DISTANCE * DRAG_DISTANCE) {
                drag_moved = true;
                layer.moveArea(area, x + drag_dx, y + drag_dy, current);
            }
            return;
        }
        if (tool.areas())
            return;
        ObjectKind kind = tool.kind();
        boolean single = kind != null && (kind.isBuilding() || kind == ObjectKind.CHIEFTAIN);
        if (stroke_sign > 0 && single)
            return;
        dab_timer += t;
        if (dab_timer < DAB_INTERVAL)
            return;
        dab_timer = 0f;
        float radius = host().getRadius();
        if (kind == null) {
            layer.erase(null, -1, x, y, radius, current);
        } else if (stroke_sign < 0) {
            layer.erase(kind, player, x, y, radius, current);
        } else {
            ensurePlaying();
            layer.paintUnits(kind, player, x, y, radius, host().getDensity(), current);
        }
    }

    /**
     * Ends the stroke, keeping it to undo.
     *
     * @param open_clicked whether a click on an area without dragging it opens the area's window
     */
    void end(boolean open_clicked) {
        ScenarioLayer.Stroke current = stroke;
        stroke = null;
        Scenario.Area clicked = dragged != null && !drag_moved ? dragged : null;
        dragged = null;
        stroke_sign = 0;
        if (current != null && !current.isEmpty())
            host().remember(current);
        if (clicked != null && open_clicked)
            openArea(clicked);
    }

    boolean isStroking() {
        return stroke != null;
    }

    /** Painting for a player that does not take part brings it in, as the objects would otherwise not show. */
    private void ensurePlaying() {
        Scenario.PlayerSetup setup = getScenario().players[player];
        if (!setup.enabled) {
            setup.enabled = true;
            layer.playerChanged(player);
            host().getGUIRoot().getInfoPrinter().print(CampaignEditor.i18n("player_joined",
                    Scenario.playerName(player)));
        }
    }

    private void openArea(Scenario.@NonNull Area area) {
        GUIRoot gui_root = host().getGUIRoot();
        gui_root.addModalForm(new AreaForm(getScenario(), area, form -> {
            ScenarioLayer.Stroke edit = new ScenarioLayer.Stroke();
            if (!form.getName().equals(area.name))
                layer.renameArea(area, form.getName(), edit);
            if (form.getRadius() != Math.round(area.radius))
                layer.resizeArea(area, form.getRadius(), edit);
            if (!edit.isEmpty())
                host().remember(edit);
        }, () -> {
            ScenarioLayer.Stroke edit = new ScenarioLayer.Stroke();
            layer.removeArea(area, edit);
            host().remember(edit);
        }));
    }

    /**
     * Ctrl and the wheel over an area with the area tool sizes the area rather than the brush.
     *
     * @return whether the wheel was taken
     */
    boolean resizeHovered(int steps) {
        Scenario.Area area = hovered_area;
        if (!tool.areas() || area == null || stroke != null)
            return false;
        float radius = Math.clamp(steps > 0 ? area.radius * AREA_RESIZE_STEP : area.radius / AREA_RESIZE_STEP,
                AreaForm.MIN_RADIUS, AreaForm.MAX_RADIUS);
        ScenarioLayer.Stroke edit = new ScenarioLayer.Stroke();
        layer.resizeArea(area, radius, edit);
        host().remember(edit);
        return true;
    }

    // ---- Picking for triggers ----

    boolean isPicking() {
        return picking != null;
    }

    /** Whether an area is being picked, where a click on open ground adds one the size of the brush. */
    boolean isPickingArea() {
        return picking == Param.AREA;
    }

    /**
     * Puts the trigger windows away for the island to pick an area or object on, then brings them back.
     *
     * @param stack the windows, bottom one first; all but the top one come back
     * @param done takes the id picked, or -1 when the pick was called off
     */
    void pick(@NonNull Param param, @NonNull List<@NonNull Form> stack, @NonNull IntConsumer done) {
        for (int i = stack.size() - 1; i >= 0; i--)
            stack.get(i).remove();
        picking = param;
        pick_stack = stack;
        pick_done = done;
        host().refreshLabels();
    }

    /** Picks what the cursor points at; open ground makes a new area when an area is wanted. */
    void pickAt(float x, float y) {
        if (picking == Param.AREA) {
            Scenario.Area area = layer.areaAt(x, y);
            if (area == null) {
                ScenarioLayer.Stroke added = new ScenarioLayer.Stroke();
                area = layer.addArea(x, y, host().getRadius(), added);
                host().remember(added);
            }
            finishPick(area.id);
        } else {
            Scenario.Placement placement = layer.placementAt(x, y);
            if (placement == null) {
                host().getGUIRoot().getInfoPrinter().print(CampaignEditor.i18n("nothing_to_pick"));
                return;
            }
            finishPick(placement.id());
        }
    }

    void cancelPick() {
        if (picking != null)
            finishPick(-1);
    }

    private void finishPick(int id) {
        List<Form> stack = Objects.requireNonNull(pick_stack);
        IntConsumer done = Objects.requireNonNull(pick_done);
        picking = null;
        pick_stack = null;
        pick_done = null;
        host().refreshLabels();
        GUIRoot gui_root = host().getGUIRoot();
        for (int i = 0; i < stack.size() - 1; i++)
            gui_root.addModalForm(stack.get(i));
        done.accept(id);
    }

    // ---- The cursor ----

    /**
     * Notes what the cursor points at.
     *
     * @return a line about it, or null when it points at nothing of the campaign's
     */
    @Nullable String hover(boolean has_cursor, float x, float y) {
        if (!has_cursor) {
            hovered_area = null;
            hovered = null;
            return null;
        }
        Scenario scenario = getScenario();
        boolean wants_areas = picking == Param.AREA || (tool.areas() && picking == null);
        hovered = wants_areas ? null : layer.placementAt(x, y);
        hovered_area = hovered == null ? layer.areaAt(x, y) : null;
        if (hovered != null)
            return CampaignEditor.i18n("hover_object", scenario.describePlacement(hovered),
                    users(scenario.usersOf(Param.OBJECT, hovered.id())));
        if (hovered_area != null)
            return CampaignEditor.i18n("hover_area", hovered_area.name, Math.round(hovered_area.radius),
                    users(scenario.usersOf(Param.AREA, hovered_area.id)));
        return null;
    }

    private static @NonNull String users(@NonNull List<Scenario.@NonNull Trigger> triggers) {
        if (triggers.isEmpty())
            return CampaignEditor.i18n("no_triggers");
        StringBuilder names = new StringBuilder();
        for (Scenario.Trigger trigger : triggers)
            names.append(names.isEmpty() ? "" : ", ").append(trigger.name);
        return CampaignEditor.i18n("used_by", names);
    }

    void render(BrushRenderer.@NonNull Batch batch) {
        layer.render(batch, highlight, hovered_area, hovered);
    }
}
