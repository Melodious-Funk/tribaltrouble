package com.oddlabs.tt.mapeditor;

import com.oddlabs.tt.gui.CancelButton;
import com.oddlabs.tt.gui.EditBox;
import com.oddlabs.tt.gui.EditLine;
import com.oddlabs.tt.gui.FocusDirection;
import com.oddlabs.tt.gui.Form;
import com.oddlabs.tt.gui.Group;
import com.oddlabs.tt.gui.HorizButton;
import com.oddlabs.tt.gui.OKButton;
import org.jspecify.annotations.NonNull;

import static com.oddlabs.tt.gui.Placement.BOTTOM_RIGHT;
import static com.oddlabs.tt.gui.Placement.LEFT_MID;

/** A level's title, the briefing shown before it starts, and the objective it starts with. */
final class LevelForm extends Form {
    private static final int CAPTION_WIDTH = 110;
    private static final int TEXT_WIDTH = 420;
    private static final int BUTTON_WIDTH = 100;
    static final int MAX_TITLE = 60;
    static final int MAX_TEXT = 2000;

    private final @NonNull EditLine editline_title;

    LevelForm(@NonNull ScenarioLayer layer, @NonNull Runnable changed) {
        super(CampaignEditor.i18n("level_caption"));
        Scenario scenario = layer.getScenario();
        String opened_title = scenario.title;
        String opened_briefing = scenario.briefing;
        String opened_objective = scenario.objective;
        editline_title = new EditLine(TEXT_WIDTH, MAX_TITLE);
        editline_title.set(scenario.title);
        EditBox editbox_briefing = new EditBox(TEXT_WIDTH, 120, MAX_TEXT);
        editbox_briefing.setText(scenario.briefing);
        EditBox editbox_objective = new EditBox(TEXT_WIDTH, 60, MAX_TEXT);
        editbox_objective.setText(scenario.objective);
        Group rows = new FormRows(CAPTION_WIDTH)
                .add(CampaignEditor.i18n("level_title_caption"), editline_title)
                .add(CampaignEditor.i18n("level_briefing_caption"), editbox_briefing)
                .add(CampaignEditor.i18n("level_objective_caption"), editbox_objective)
                .build();

        HorizButton button_ok = new OKButton(BUTTON_WIDTH);
        button_ok.addMouseClickListener((_, _, _, _) -> {
            String title = editline_title.getContents().trim();
            if (title.isEmpty()) {
                editline_title.triggerError();
                return;
            }
            // Only what was changed here, so what another player in a shared session changed meanwhile stays.
            if (!title.equals(opened_title))
                scenario.title = title;
            if (!editbox_briefing.getContents().equals(opened_briefing))
                scenario.briefing = editbox_briefing.getContents();
            if (!editbox_objective.getContents().equals(opened_objective))
                scenario.objective = editbox_objective.getContents();
            layer.markModified();
            remove();
            changed.run();
        });
        HorizButton button_cancel = new CancelButton(BUTTON_WIDTH);
        button_cancel.addMouseClickListener((_, _, _, _) -> cancel());
        addChild(rows);
        addChild(button_ok);
        addChild(button_cancel);
        rows.place();
        button_cancel.place(rows, BOTTOM_RIGHT);
        button_ok.place(button_cancel, LEFT_MID);
        compileCanvas();
        centerPos();
    }

    @Override
    public void setFocus(@NonNull FocusDirection direction) {
        if (direction == FocusDirection.BACKWARD) {
            super.setFocus(direction);
        } else {
            editline_title.setFocus(direction);
        }
    }
}
