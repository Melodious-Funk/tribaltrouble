package com.oddlabs.tt.mapeditor;

import com.oddlabs.tt.gui.CancelButton;
import com.oddlabs.tt.gui.EditBox;
import com.oddlabs.tt.gui.EditLine;
import com.oddlabs.tt.gui.FocusDirection;
import com.oddlabs.tt.gui.Form;
import com.oddlabs.tt.gui.GUIObject;
import com.oddlabs.tt.gui.GUIRoot;
import com.oddlabs.tt.gui.Group;
import com.oddlabs.tt.gui.HorizButton;
import com.oddlabs.tt.gui.Label;
import com.oddlabs.tt.gui.LabelBox;
import com.oddlabs.tt.gui.NumberEditLine;
import com.oddlabs.tt.gui.OKButton;
import com.oddlabs.tt.gui.PulldownButton;
import com.oddlabs.tt.gui.PulldownItem;
import com.oddlabs.tt.gui.PulldownMenu;
import com.oddlabs.tt.gui.ScrollablePulldownMenu;
import com.oddlabs.tt.gui.Skin;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static com.oddlabs.tt.gui.Placement.BOTTOM_LEFT;
import static com.oddlabs.tt.gui.Placement.BOTTOM_RIGHT;
import static com.oddlabs.tt.gui.Placement.LEFT_MID;

/**
 * Edits a trigger's condition or one of its actions: its kind, and a control for each setting the kind has. Areas
 * and objects can be picked by clicking them on the island, which puts the windows away until the pick is made.
 */
final class StepForm extends Form {
    private static final int CAPTION_WIDTH = 130;
    private static final int CONTROL_WIDTH = 300;
    private static final int BUTTON_WIDTH = 100;
    private static final int PICK_WIDTH = 110;
    /** Dropdowns longer than this scroll, so they stay on the screen. */
    private static final int MAX_SHOWN_ITEMS = 12;

    private final @NonNull GUIRoot gui_root;
    private final @NonNull CampaignTools tools;
    private final boolean condition;
    private final @NonNull Step draft;
    private final @NonNull List<@NonNull Form> parents;
    private final @NonNull Consumer<@NonNull Step> done;
    private final @NonNull PulldownMenu<Integer> menu_kind;
    private final @NonNull PulldownButton<Integer> pulldown_kind;
    private final Map<Param, GUIObject> controls = new EnumMap<>(Param.class);

    /**
     * @param condition whether the step is a condition rather than an action
     * @param draft a copy of the step to edit
     * @param parents the windows under this one, which picking on the island puts away too
     * @param done takes the edited step once accepted
     */
    StepForm(@NonNull GUIRoot gui_root, @NonNull CampaignTools tools, boolean condition, @NonNull Step draft,
            @NonNull List<@NonNull Form> parents, @NonNull Consumer<@NonNull Step> done) {
        super(CampaignEditor.i18n(condition ? "condition_caption" : "action_caption"));
        this.gui_root = gui_root;
        this.tools = tools;
        this.condition = condition;
        this.draft = draft;
        this.parents = parents;
        this.done = done;
        Scenario scenario = tools.getScenario();

        menu_kind = newMenu(condition ? ConditionKind.values().length : ActionKind.values().length);
        String help_key;
        Param[] params;
        if (condition) {
            for (ConditionKind kind : ConditionKind.values())
                menu_kind.addItem(new PulldownItem<>(kind.getName(), kind.ordinal()));
            ConditionKind kind = ConditionKind.of(draft.kind);
            params = kind.getParams();
            help_key = kind.name().toLowerCase(java.util.Locale.ROOT);
        } else {
            for (ActionKind kind : ActionKind.values())
                menu_kind.addItem(new PulldownItem<>(kind.getName(), kind.ordinal()));
            ActionKind kind = ActionKind.of(draft.kind);
            params = kind.getParams();
            help_key = kind.name().toLowerCase(java.util.Locale.ROOT);
        }
        pulldown_kind = new PulldownButton<>(gui_root, menu_kind, draft.kind, CONTROL_WIDTH);
        menu_kind.addItemChosenListener((menu, index) -> changeKind(index));

        FormRows rows = new FormRows(CAPTION_WIDTH);
        rows.add(CampaignEditor.i18n(condition ? "condition_kind" : "action_kind"), pulldown_kind);
        for (Param param : params)
            addControl(rows, scenario, param);
        Group group = rows.build();
        LabelBox label_help = new LabelBox(CampaignEditor.i18n("help_" + help_key), Skin.getSkin().getEditFont(),
                group.getWidth());

        HorizButton button_ok = new OKButton(BUTTON_WIDTH);
        button_ok.addMouseClickListener((_, _, _, _) -> {
            read();
            remove();
            done.accept(draft);
        });
        HorizButton button_cancel = new CancelButton(BUTTON_WIDTH);
        button_cancel.addMouseClickListener((_, _, _, _) -> cancel());
        addChild(group);
        addChild(label_help);
        addChild(button_ok);
        addChild(button_cancel);
        group.place();
        label_help.place(group, BOTTOM_LEFT);
        button_cancel.place(label_help, BOTTOM_RIGHT);
        button_ok.place(button_cancel, LEFT_MID);
        compileCanvas();
        centerPos();
    }

    private void addControl(@NonNull FormRows rows, @NonNull Scenario scenario, @NonNull Param param) {
        String caption = param.getCaption();
        String[] choices = param.getChoices();
        if (choices != null) {
            PulldownMenu<Integer> menu = newMenu(choices.length);
            for (int i = 0; i < choices.length; i++)
                menu.addItem(new PulldownItem<>(choices[i], i));
            rows.add(caption, pulldown(menu, draft.get(param)));
            controls.put(param, menu);
            return;
        }
        switch (param) {
            case PLAYER, TARGET_PLAYER, NEW_OWNER -> {
                PulldownMenu<Integer> menu = new PulldownMenu<>();
                for (int i = 0; i < Scenario.NUM_PLAYERS; i++) {
                    String name = Scenario.playerName(i);
                    if (!scenario.players[i].enabled)
                        name = CampaignEditor.i18n("player_not_playing", name);
                    menu.addItem(new PulldownItem<>(name, i));
                }
                rows.add(caption, pulldown(menu, draft.get(param)));
                controls.put(param, menu);
            }
            case AREA -> {
                PulldownMenu<Integer> menu = newMenu(scenario.areas.size() + 1);
                menu.addItem(new PulldownItem<>(CampaignEditor.i18n(Scenario.isAreaOptional(condition, draft)
                        ? "whole_island" : "none"), -1));
                int chosen = 0;
                for (Scenario.Area area : scenario.areas) {
                    if (area.id == draft.get(param))
                        chosen = menu.getSize();
                    menu.addItem(new PulldownItem<>(area.name, area.id));
                }
                rows.add(caption, pulldownAt(menu, chosen, CONTROL_WIDTH - PICK_WIDTH
                        - Skin.getSkin().getFormData().objectSpacing()), pickButton(param));
                controls.put(param, menu);
            }
            case OBJECT, BUILDING -> {
                Label label = new Label(scenario.describe(draft, param), Skin.getSkin().getEditFont(),
                        CONTROL_WIDTH - PICK_WIDTH - Skin.getSkin().getFormData().objectSpacing());
                rows.add(caption, label, pickButton(param));
            }
            case TRIGGER -> {
                PulldownMenu<Integer> menu = newMenu(scenario.triggers.size() + 1);
                menu.addItem(new PulldownItem<>(CampaignEditor.i18n("none"), -1));
                int chosen = 0;
                for (Scenario.Trigger trigger : scenario.triggers) {
                    if (trigger.id == draft.get(param))
                        chosen = menu.getSize();
                    menu.addItem(new PulldownItem<>(trigger.name, trigger.id));
                }
                rows.add(caption, pulldownAt(menu, chosen, CONTROL_WIDTH));
                controls.put(param, menu);
            }
            case HEADER -> {
                EditLine line = new EditLine(CONTROL_WIDTH, LevelForm.MAX_TITLE);
                line.set(draft.getText(param));
                rows.add(caption, line);
                controls.put(param, line);
            }
            case TEXT -> {
                EditBox box = new EditBox(CONTROL_WIDTH, 110, LevelForm.MAX_TEXT);
                box.setText(draft.getText(param));
                rows.add(caption, box);
                controls.put(param, box);
            }
            default -> {
                NumberEditLine line = new NumberEditLine(100, 6, 0, param.getMax(), draft.get(param));
                rows.add(caption, line);
                controls.put(param, line);
            }
        }
    }

    /** A dropdown for so many items, scrolling when it would be too tall for the screen. */
    private static @NonNull PulldownMenu<Integer> newMenu(int items) {
        return items > MAX_SHOWN_ITEMS ? new ScrollablePulldownMenu<>(MAX_SHOWN_ITEMS) : new PulldownMenu<>();
    }

    private @NonNull PulldownButton<Integer> pulldown(@NonNull PulldownMenu<Integer> menu, int value) {
        int chosen = 0;
        for (int i = 0; i < menu.getSize(); i++) {
            Integer attachment = menu.getItem(i).getAttachment();
            if (attachment != null && attachment == value)
                chosen = i;
        }
        return pulldownAt(menu, chosen, CONTROL_WIDTH);
    }

    private @NonNull PulldownButton<Integer> pulldownAt(@NonNull PulldownMenu<Integer> menu, int index, int width) {
        return new PulldownButton<>(gui_root, menu, index, width);
    }

    private @NonNull HorizButton pickButton(@NonNull Param param) {
        HorizButton button = new HorizButton(CampaignEditor.i18n("pick"), PICK_WIDTH);
        button.addMouseClickListener((_, _, _, _) -> pick(param));
        return button;
    }

    /** Takes what the controls show into the draft. */
    private void read() {
        for (Map.Entry<Param, GUIObject> entry : controls.entrySet()) {
            Param param = entry.getKey();
            switch (entry.getValue()) {
                case PulldownMenu<?> menu -> {
                    Object attachment = menu.getItem(menu.getChosenItemIndex()).getAttachment();
                    if (attachment instanceof Integer value)
                        draft.set(param, value);
                }
                case NumberEditLine line -> draft.set(param, parse(line, 0, param.getMax(), draft.get(param)));
                case EditLine line -> draft.setText(param, line.getContents());
                case EditBox box -> draft.setText(param, box.getContents());
                default -> {
                }
            }
        }
    }

    /** The number typed in, held to its range, or a fallback when it is not a number. */
    static int parse(@NonNull EditLine line, int min, int max, int fallback) {
        try {
            return Math.clamp(Long.parseLong(line.getContents().trim()), min, max);
        } catch (NumberFormatException _) {
            return fallback;
        }
    }

    private void changeKind(int index) {
        Integer kind = menu_kind.getItem(index).getAttachment();
        if (kind == null || kind == draft.kind)
            return;
        read();
        if (condition)
            draft.setCondition(ConditionKind.of(kind));
        else
            draft.setAction(ActionKind.of(kind));
        remove();
        reopen();
    }

    /** Opens the window again for the draft, centred where this one was, as its size may change. */
    private void reopen() {
        int centre_x = getX() + getWidth() / 2;
        int centre_y = getY() + getHeight() / 2;
        StepForm form = new StepForm(gui_root, tools, condition, draft, parents, done);
        form.setPos(Math.clamp(centre_x - form.getWidth() / 2, 0, Math.max(0, gui_root.getWidth() - form.getWidth())),
                Math.clamp(centre_y - form.getHeight() / 2, 0, Math.max(0,
                        gui_root.getHeight() - form.getHeight())));
        gui_root.addModalForm(form);
    }

    private void pick(@NonNull Param param) {
        read();
        List<Form> stack = new ArrayList<>(parents);
        stack.add(this);
        tools.pick(param, stack, id -> {
            Scenario.Placement placement = param.isObject() && id != -1 ? tools.getScenario().findPlacement(id)
                    : null;
            if (placement != null && !Scenario.fits(condition, draft, param, placement.kind())) {
                // Said at once, rather than only when the level is tested.
                gui_root.getInfoPrinter().print(CampaignEditor.i18n("pick_refused",
                        tools.getScenario().describePlacement(placement),
                        CampaignEditor.i18n(Scenario.wanted(condition, draft, param))));
            } else if (id != -1) {
                draft.set(param, id);
            }
            reopen();
        });
    }

    @Override
    public void setFocus(@NonNull FocusDirection direction) {
        if (direction == FocusDirection.BACKWARD) {
            super.setFocus(direction);
        } else {
            pulldown_kind.setFocus(direction);
        }
    }
}
