package com.oddlabs.tt.mapeditor;

import com.oddlabs.tt.gui.CancelButton;
import com.oddlabs.tt.gui.EditLine;
import com.oddlabs.tt.gui.FocusDirection;
import com.oddlabs.tt.gui.Form;
import com.oddlabs.tt.gui.Group;
import com.oddlabs.tt.gui.HorizButton;
import com.oddlabs.tt.gui.LabelBox;
import com.oddlabs.tt.gui.NumberEditLine;
import com.oddlabs.tt.gui.OKButton;
import com.oddlabs.tt.gui.Skin;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.function.Consumer;

import static com.oddlabs.tt.gui.Placement.BOTTOM_LEFT;
import static com.oddlabs.tt.gui.Placement.BOTTOM_RIGHT;
import static com.oddlabs.tt.gui.Placement.LEFT_MID;

/** An area's name and size, which triggers use it, and deleting it. */
final class AreaForm extends Form {
    private static final int CAPTION_WIDTH = 90;
    private static final int BUTTON_WIDTH = 100;
    static final int MAX_NAME = 32;
    static final int MIN_RADIUS = 2;
    static final int MAX_RADIUS = 200;

    private final @NonNull EditLine editline_name;
    // What was accepted, for the edit to apply.
    private @NonNull String name = "";
    private int radius;

    /**
     * @param edit applies the new name and radius, as one undo step
     * @param delete takes the area away
     */
    AreaForm(@NonNull Scenario scenario, Scenario.@NonNull Area area, @NonNull Consumer<AreaForm> edit,
            @NonNull Runnable delete) {
        super(CampaignEditor.i18n("area_caption"));
        editline_name = new EditLine(220, MAX_NAME);
        editline_name.set(area.name);
        NumberEditLine editline_radius = new NumberEditLine(80, 3, MIN_RADIUS, MAX_RADIUS,
                Math.round(area.radius));
        Group rows = new FormRows(CAPTION_WIDTH)
                .add(CampaignEditor.i18n("area_name_caption"), editline_name)
                .add(CampaignEditor.i18n("area_radius_caption"), editline_radius)
                .build();
        List<Scenario.Trigger> users = scenario.usersOf(Param.AREA, area.id);
        StringBuilder used = new StringBuilder();
        for (Scenario.Trigger trigger : users)
            used.append(used.isEmpty() ? "" : ", ").append(trigger.name);
        LabelBox label_used = new LabelBox(users.isEmpty() ? CampaignEditor.i18n("area_unused")
                : CampaignEditor.i18n("used_by", used), Skin.getSkin().getEditFont(), rows.getWidth());

        HorizButton button_ok = new OKButton(BUTTON_WIDTH);
        button_ok.addMouseClickListener((_, _, _, _) -> {
            String name = editline_name.getContents().trim();
            if (name.isEmpty()) {
                editline_name.triggerError();
                return;
            }
            this.name = name;
            this.radius = StepForm.parse(editline_radius, MIN_RADIUS, MAX_RADIUS, Math.round(area.radius));
            remove();
            edit.accept(this);
        });
        HorizButton button_delete = new HorizButton(CampaignEditor.i18n("delete"), BUTTON_WIDTH);
        button_delete.addMouseClickListener((_, _, _, _) -> {
            remove();
            delete.run();
        });
        HorizButton button_cancel = new CancelButton(BUTTON_WIDTH);
        button_cancel.addMouseClickListener((_, _, _, _) -> cancel());
        addChild(rows);
        addChild(label_used);
        addChild(button_ok);
        addChild(button_delete);
        addChild(button_cancel);
        rows.place();
        label_used.place(rows, BOTTOM_LEFT);
        button_cancel.place(label_used, BOTTOM_RIGHT);
        button_delete.place(button_cancel, LEFT_MID);
        button_ok.place(button_delete, LEFT_MID);
        compileCanvas();
        centerPos();
    }

    @NonNull String getName() {
        return name;
    }

    int getRadius() {
        return radius;
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
