package com.oddlabs.tt.mapeditor;

import com.oddlabs.tt.font.Font;
import com.oddlabs.tt.gui.CancelButton;
import com.oddlabs.tt.gui.CheckBox;
import com.oddlabs.tt.gui.ColumnInfo;
import com.oddlabs.tt.gui.EditLine;
import com.oddlabs.tt.gui.FocusDirection;
import com.oddlabs.tt.gui.Form;
import com.oddlabs.tt.gui.GUIRoot;
import com.oddlabs.tt.gui.Group;
import com.oddlabs.tt.gui.HorizButton;
import com.oddlabs.tt.gui.Label;
import com.oddlabs.tt.gui.MultiColumnComboBox;
import com.oddlabs.tt.gui.OKButton;
import com.oddlabs.tt.gui.Row;
import com.oddlabs.tt.gui.Skin;
import com.oddlabs.tt.gui.SortedLabel;
import com.oddlabs.tt.guievent.RowListener;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static com.oddlabs.tt.gui.Placement.BOTTOM_LEFT;
import static com.oddlabs.tt.gui.Placement.BOTTOM_RIGHT;
import static com.oddlabs.tt.gui.Placement.LEFT_MID;
import static com.oddlabs.tt.gui.Placement.RIGHT_MID;

/**
 * Edits one trigger: its name, when it is part of the level, the condition it waits for and the actions it runs in
 * order.
 */
final class TriggerForm extends Form {
    private static final int CAPTION_WIDTH = 110;
    private static final int LIST_WIDTH = 520;
    private static final int LIST_HEIGHT = 170;
    private static final int BUTTON_WIDTH = 100;
    static final int MAX_NAME = 40;

    private final @NonNull GUIRoot gui_root;
    private final @NonNull CampaignTools tools;
    private final Scenario.@NonNull Trigger draft;
    private final @NonNull List<@NonNull Form> parents;
    private final @NonNull EditLine editline_name;
    private final @NonNull CheckBox check_active;
    private final @NonNull CheckBox check_repeat;
    private final @NonNull CheckBox check_easy;
    private final @NonNull CheckBox check_normal;
    private final @NonNull CheckBox check_hard;
    private final @NonNull Label label_condition;
    private final @NonNull MultiColumnComboBox<Integer> list_actions;

    /**
     * @param draft a copy of the trigger to edit
     * @param parents the windows under this one
     * @param done takes the edited trigger once accepted
     */
    TriggerForm(@NonNull GUIRoot gui_root, @NonNull CampaignTools tools, Scenario.@NonNull Trigger draft,
            @NonNull List<@NonNull Form> parents, @NonNull Consumer<Scenario.@NonNull Trigger> done) {
        super(CampaignEditor.i18n("trigger_caption"));
        this.gui_root = gui_root;
        this.tools = tools;
        this.draft = draft;
        this.parents = parents;

        editline_name = new EditLine(300, MAX_NAME);
        editline_name.set(draft.name);
        check_active = new CheckBox(draft.active, CampaignEditor.i18n("trigger_active"));
        check_repeat = new CheckBox(draft.repeat, CampaignEditor.i18n("trigger_repeat"));
        check_easy = new CheckBox((draft.difficulties & Scenario.Trigger.DIFFICULTY_EASY) != 0,
                CampaignEditor.i18n("difficulty_easy"));
        check_normal = new CheckBox((draft.difficulties & Scenario.Trigger.DIFFICULTY_NORMAL) != 0,
                CampaignEditor.i18n("difficulty_normal"));
        check_hard = new CheckBox((draft.difficulties & Scenario.Trigger.DIFFICULTY_HARD) != 0,
                CampaignEditor.i18n("difficulty_hard"));
        label_condition = new Label("", Skin.getSkin().getEditFont(), LIST_WIDTH - BUTTON_WIDTH
                - Skin.getSkin().getFormData().objectSpacing());
        HorizButton button_condition = new HorizButton(CampaignEditor.i18n("edit"), BUTTON_WIDTH);
        button_condition.addMouseClickListener((_, _, _, _) -> editCondition());

        Group rows = new FormRows(CAPTION_WIDTH)
                .add(CampaignEditor.i18n("trigger_name"), editline_name)
                .add(CampaignEditor.i18n("trigger_when"), check_active, check_repeat)
                .add(CampaignEditor.i18n("trigger_difficulties"), check_easy, check_normal, check_hard)
                .add(CampaignEditor.i18n("trigger_condition"), label_condition, button_condition)
                .build();

        list_actions = new MultiColumnComboBox<>(gui_root, new ColumnInfo[]{
                new ColumnInfo(CampaignEditor.i18n("trigger_actions"), LIST_WIDTH)}, LIST_HEIGHT);
        list_actions.addRowListener(new RowListener<>() {
            @Override
            public void rowDoubleClicked(@NonNull Integer index) {
                editAction(index);
            }
        });
        Group buttons = new Group();
        HorizButton button_add = button(buttons, null, "add", () -> addAction());
        HorizButton button_edit = button(buttons, button_add, "edit", () -> {
            Integer selected = list_actions.getSelected();
            if (selected != null)
                editAction(selected);
        });
        HorizButton button_copy = button(buttons, button_edit, "duplicate", () -> {
            Integer selected = list_actions.getSelected();
            if (selected != null) {
                read();
                draft.actions.add(selected + 1, draft.actions.get(selected).copy());
                refresh(selected + 1);
            }
        });
        HorizButton button_remove = button(buttons, button_copy, "remove", () -> {
            Integer selected = list_actions.getSelected();
            if (selected != null) {
                read();
                draft.actions.remove((int) selected);
                refresh(Math.min(selected, draft.actions.size() - 1));
            }
        });
        HorizButton button_up = button(buttons, button_remove, "move_up", () -> moveAction(-1));
        button(buttons, button_up, "move_down", () -> moveAction(1));
        buttons.compileCanvas();

        HorizButton button_ok = new OKButton(BUTTON_WIDTH);
        button_ok.addMouseClickListener((_, _, _, _) -> {
            String name = editline_name.getContents().trim();
            if (name.isEmpty()) {
                editline_name.triggerError();
                return;
            }
            read();
            remove();
            done.accept(draft);
        });
        HorizButton button_cancel = new CancelButton(BUTTON_WIDTH);
        button_cancel.addMouseClickListener((_, _, _, _) -> cancel());

        addChild(rows);
        addChild(list_actions);
        addChild(buttons);
        addChild(button_ok);
        addChild(button_cancel);
        rows.place();
        list_actions.place(rows, BOTTOM_LEFT);
        buttons.place(list_actions, BOTTOM_LEFT);
        button_cancel.place(buttons, BOTTOM_RIGHT, Skin.getSkin().getFormData().sectionSpacing());
        button_ok.place(button_cancel, LEFT_MID);
        compileCanvas();
        centerPos();
        refresh(0);
    }

    private static @NonNull HorizButton button(@NonNull Group group, @Nullable HorizButton previous,
            @NonNull String key, @NonNull Runnable action) {
        HorizButton button = new HorizButton(CampaignEditor.i18n(key), BUTTON_WIDTH);
        button.addMouseClickListener((_, _, _, _) -> action.run());
        group.addChild(button);
        if (previous == null)
            button.place();
        else
            button.place(previous, RIGHT_MID);
        return button;
    }

    /** Takes what the controls show into the draft. */
    private void read() {
        String name = editline_name.getContents().trim();
        if (!name.isEmpty())
            draft.name = name;
        draft.active = check_active.isMarked();
        draft.repeat = check_repeat.isMarked();
        int difficulties = 0;
        if (check_easy.isMarked())
            difficulties |= Scenario.Trigger.DIFFICULTY_EASY;
        if (check_normal.isMarked())
            difficulties |= Scenario.Trigger.DIFFICULTY_NORMAL;
        if (check_hard.isMarked())
            difficulties |= Scenario.Trigger.DIFFICULTY_HARD;
        draft.difficulties = difficulties;
    }

    private void refresh(int select) {
        Scenario scenario = tools.getScenario();
        label_condition.set(scenario.describeCondition(draft.condition));
        list_actions.clear();
        Font font = Skin.getSkin().getMultiColumnComboBoxData().font();
        Row<Integer, Label> selected = null;
        for (int i = 0; i < draft.actions.size(); i++) {
            Row<Integer, Label> row = new Row<>(List.of(new SortedLabel(CampaignEditor.i18n("numbered", i + 1,
                    scenario.describeAction(draft.actions.get(i))), i, font)), i);
            list_actions.addRow(row);
            if (i == select)
                selected = row;
        }
        if (selected != null)
            list_actions.selectRow(selected);
    }

    private @NonNull List<@NonNull Form> stack() {
        List<Form> stack = new ArrayList<>(parents);
        stack.add(this);
        return stack;
    }

    private void editCondition() {
        read();
        gui_root.addModalForm(new StepForm(gui_root, tools, true, draft.condition.copy(), stack(), step -> {
            draft.condition = step;
            refresh(0);
        }));
    }

    private void addAction() {
        read();
        gui_root.addModalForm(new StepForm(gui_root, tools, false, Step.action(ActionKind.DIALOG), stack(),
                step -> {
                    draft.actions.add(step);
                    refresh(draft.actions.size() - 1);
                }));
    }

    private void editAction(int index) {
        read();
        gui_root.addModalForm(new StepForm(gui_root, tools, false, draft.actions.get(index).copy(), stack(),
                step -> {
                    draft.actions.set(index, step);
                    refresh(index);
                }));
    }

    private void moveAction(int direction) {
        Integer selected = list_actions.getSelected();
        if (selected == null)
            return;
        int target = selected + direction;
        if (target < 0 || target >= draft.actions.size())
            return;
        read();
        Step step = draft.actions.remove((int) selected);
        draft.actions.add(target, step);
        refresh(target);
    }

    @Override
    public void setFocus(@NonNull FocusDirection direction) {
        if (direction == FocusDirection.BACKWARD) {
            super.setFocus(direction);
        } else {
            editline_name.setFocus(direction);
        }
    }
}
