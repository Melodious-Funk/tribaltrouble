package com.oddlabs.tt.mapeditor;

import com.oddlabs.tt.font.Font;
import com.oddlabs.tt.gui.ColumnInfo;
import com.oddlabs.tt.gui.FocusDirection;
import com.oddlabs.tt.gui.Form;
import com.oddlabs.tt.gui.GUIRoot;
import com.oddlabs.tt.gui.Group;
import com.oddlabs.tt.gui.HorizButton;
import com.oddlabs.tt.gui.Label;
import com.oddlabs.tt.gui.LabelBox;
import com.oddlabs.tt.gui.MultiColumnComboBox;
import com.oddlabs.tt.gui.OKButton;
import com.oddlabs.tt.gui.Row;
import com.oddlabs.tt.gui.Skin;
import com.oddlabs.tt.gui.SortedLabel;
import com.oddlabs.tt.guievent.RowListener;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;

import static com.oddlabs.tt.gui.Placement.BOTTOM_LEFT;
import static com.oddlabs.tt.gui.Placement.BOTTOM_RIGHT;
import static com.oddlabs.tt.gui.Placement.RIGHT_MID;

/**
 * The level's triggers in order: adding, editing, copying, removing and reordering them. The one selected is
 * marked on the island, with the areas and objects it names.
 */
final class TriggersForm extends Form {
    private static final int NAME_WIDTH = 170;
    private static final int CONDITION_WIDTH = 330;
    private static final int FLAGS_WIDTH = 150;
    private static final int LIST_HEIGHT = 300;
    private static final int BUTTON_WIDTH = 100;

    private final @NonNull GUIRoot gui_root;
    private final @NonNull CampaignTools tools;
    private final @NonNull MultiColumnComboBox<Integer> list;

    TriggersForm(@NonNull GUIRoot gui_root, @NonNull CampaignTools tools) {
        super(CampaignEditor.i18n("triggers_caption"));
        this.gui_root = gui_root;
        this.tools = tools;

        list = new MultiColumnComboBox<>(gui_root, new ColumnInfo[]{
                new ColumnInfo(CampaignEditor.i18n("column_trigger"), NAME_WIDTH),
                new ColumnInfo(CampaignEditor.i18n("column_condition"), CONDITION_WIDTH),
                new ColumnInfo(CampaignEditor.i18n("column_flags"), FLAGS_WIDTH)}, LIST_HEIGHT);
        list.addRowListener(new RowListener<>() {
            @Override
            public void rowChosen(@NonNull Integer id) {
                tools.setHighlight(tools.getScenario().findTrigger(id));
            }

            @Override
            public void rowDoubleClicked(@NonNull Integer id) {
                edit(id);
            }
        });
        LabelBox label_help = new LabelBox(CampaignEditor.i18n("triggers_help"), Skin.getSkin().getEditFont(),
                list.getWidth());

        Group buttons = new Group();
        HorizButton button_new = button(buttons, null, "new_trigger", this::add);
        HorizButton button_edit = button(buttons, button_new, "edit", () -> {
            Integer selected = list.getSelected();
            if (selected != null)
                edit(selected);
        });
        HorizButton button_copy = button(buttons, button_edit, "duplicate", this::duplicate);
        HorizButton button_delete = button(buttons, button_copy, "delete", this::delete);
        HorizButton button_up = button(buttons, button_delete, "move_up", () -> move(-1));
        button(buttons, button_up, "move_down", () -> move(1));
        buttons.compileCanvas();

        HorizButton button_close = new OKButton(BUTTON_WIDTH);
        button_close.addMouseClickListener((_, _, _, _) -> remove());

        addChild(list);
        addChild(label_help);
        addChild(buttons);
        addChild(button_close);
        list.place();
        label_help.place(list, BOTTOM_LEFT);
        buttons.place(label_help, BOTTOM_LEFT);
        button_close.place(buttons, BOTTOM_RIGHT, Skin.getSkin().getFormData().sectionSpacing());
        compileCanvas();
        centerPos();
        Scenario.Trigger highlight = tools.getHighlight();
        refresh(highlight != null ? highlight.id : -1);
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

    private void refresh(int select_id) {
        Scenario scenario = tools.getScenario();
        list.clear();
        Font font = Skin.getSkin().getMultiColumnComboBoxData().font();
        Row<Integer, Label> selected = null;
        for (int i = 0; i < scenario.triggers.size(); i++) {
            Scenario.Trigger trigger = scenario.triggers.get(i);
            Row<Integer, Label> row = new Row<>(List.of(
                    new SortedLabel(trigger.name, i, font),
                    new Label(scenario.describeCondition(trigger.condition), font),
                    new Label(flags(trigger), font)), trigger.id);
            list.addRow(row);
            if (trigger.id == select_id || selected == null)
                selected = row;
        }
        if (selected != null) {
            list.selectRow(selected);
            Integer id = list.getSelected();
            tools.setHighlight(id != null ? scenario.findTrigger(id) : null);
        } else {
            tools.setHighlight(null);
        }
    }

    /** A short note of how the trigger is set: actions, waiting, repeating and the difficulties left out. */
    private static @NonNull String flags(Scenario.@NonNull Trigger trigger) {
        StringBuilder text = new StringBuilder(CampaignEditor.i18n("actions_count", trigger.actions.size()));
        if (!trigger.active)
            text.append(", ").append(CampaignEditor.i18n("flag_off"));
        if (trigger.repeat)
            text.append(", ").append(CampaignEditor.i18n("flag_repeat"));
        if (trigger.difficulties != Scenario.Trigger.ALL_DIFFICULTIES) {
            text.append(", ");
            if ((trigger.difficulties & Scenario.Trigger.DIFFICULTY_EASY) != 0)
                text.append(CampaignEditor.i18n("flag_easy"));
            if ((trigger.difficulties & Scenario.Trigger.DIFFICULTY_NORMAL) != 0)
                text.append(CampaignEditor.i18n("flag_normal"));
            if ((trigger.difficulties & Scenario.Trigger.DIFFICULTY_HARD) != 0)
                text.append(CampaignEditor.i18n("flag_hard"));
        }
        return text.toString();
    }

    private void add() {
        Scenario scenario = tools.getScenario();
        Scenario.Trigger trigger = new Scenario.Trigger(scenario.newId(),
                CampaignEditor.i18n("trigger_default_name", scenario.triggers.size() + 1),
                Step.condition(ConditionKind.GAME_STARTED));
        trigger.actions.add(Step.action(ActionKind.DIALOG));
        gui_root.addModalForm(new TriggerForm(gui_root, tools, trigger, List.of(this), edited -> {
            scenario.triggers.add(edited);
            tools.markModified();
            refresh(edited.id);
        }));
    }

    private void edit(int id) {
        Scenario scenario = tools.getScenario();
        Scenario.Trigger trigger = scenario.findTrigger(id);
        if (trigger == null)
            return;
        gui_root.addModalForm(new TriggerForm(gui_root, tools, trigger.copy(), List.of(this), edited -> {
            int index = scenario.triggers.indexOf(scenario.findTrigger(id));
            if (index != -1)
                scenario.triggers.set(index, edited);
            tools.markModified();
            refresh(id);
        }));
    }

    private void duplicate() {
        Scenario scenario = tools.getScenario();
        Integer selected = list.getSelected();
        Scenario.Trigger trigger = selected != null ? scenario.findTrigger(selected) : null;
        if (trigger == null)
            return;
        Scenario.Trigger copy = new Scenario.Trigger(scenario.newId(),
                CampaignEditor.i18n("trigger_copy_name", trigger.name), trigger.condition.copy());
        for (Step action : trigger.actions)
            copy.actions.add(action.copy());
        copy.active = trigger.active;
        copy.repeat = trigger.repeat;
        copy.difficulties = trigger.difficulties;
        scenario.triggers.add(scenario.triggers.indexOf(trigger) + 1, copy);
        tools.markModified();
        refresh(copy.id);
    }

    private void delete() {
        Scenario scenario = tools.getScenario();
        Integer selected = list.getSelected();
        Scenario.Trigger trigger = selected != null ? scenario.findTrigger(selected) : null;
        if (trigger == null)
            return;
        int index = scenario.triggers.indexOf(trigger);
        scenario.triggers.remove(trigger);
        tools.markModified();
        int next = Math.min(index, scenario.triggers.size() - 1);
        refresh(next >= 0 ? scenario.triggers.get(next).id : -1);
    }

    private void move(int direction) {
        Scenario scenario = tools.getScenario();
        Integer selected = list.getSelected();
        Scenario.Trigger trigger = selected != null ? scenario.findTrigger(selected) : null;
        if (trigger == null)
            return;
        int index = scenario.triggers.indexOf(trigger);
        int target = index + direction;
        if (target < 0 || target >= scenario.triggers.size())
            return;
        scenario.triggers.remove(index);
        scenario.triggers.add(target, trigger);
        tools.markModified();
        refresh(trigger.id);
    }

    @Override
    public void setFocus(@NonNull FocusDirection direction) {
        if (direction == FocusDirection.BACKWARD) {
            super.setFocus(direction);
        } else {
            list.setFocus(direction);
        }
    }
}
