package com.oddlabs.tt.mapeditor;

import com.oddlabs.tt.font.Font;
import com.oddlabs.tt.gui.CancelButton;
import com.oddlabs.tt.gui.ColumnInfo;
import com.oddlabs.tt.gui.FocusDirection;
import com.oddlabs.tt.gui.Form;
import com.oddlabs.tt.gui.HorizButton;
import com.oddlabs.tt.gui.Label;
import com.oddlabs.tt.gui.MultiColumnComboBox;
import com.oddlabs.tt.gui.OKButton;
import com.oddlabs.tt.gui.Row;
import com.oddlabs.tt.gui.GUIRoot;
import com.oddlabs.tt.gui.Skin;
import com.oddlabs.tt.gui.SortedLabel;
import com.oddlabs.tt.gui.TextBox;
import com.oddlabs.tt.guievent.RowListener;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.function.Consumer;

import static com.oddlabs.tt.gui.Placement.BOTTOM_RIGHT;
import static com.oddlabs.tt.gui.Placement.LEFT_MID;
import static com.oddlabs.tt.gui.Placement.RIGHT_TOP;

/** Lists the {@link TriggerTemplate}s with what each does, to start a new trigger from one. */
final class TemplateForm extends Form {
    private static final int NAME_WIDTH = 300;
    private static final int HELP_WIDTH = 300;
    private static final int LIST_HEIGHT = 300;
    private static final int BUTTON_WIDTH = 100;

    private final @NonNull MultiColumnComboBox<TriggerTemplate> list;
    private final @NonNull TextBox box_help;

    /** @param chosen takes the template picked */
    TemplateForm(@NonNull GUIRoot gui_root, @NonNull Consumer<@NonNull TriggerTemplate> chosen) {
        super(CampaignEditor.i18n("templates_caption"));
        list = new MultiColumnComboBox<>(gui_root, new ColumnInfo[]{
                new ColumnInfo(CampaignEditor.i18n("column_template"), NAME_WIDTH)}, LIST_HEIGHT);
        Font font = Skin.getSkin().getEditFont();
        box_help = new TextBox(HELP_WIDTH, list.getHeight(), font, 1000);
        list.addRowListener(new RowListener<>() {
            @Override
            public void rowChosen(@NonNull TriggerTemplate template) {
                box_help.setText(template.getHelp());
                box_help.setOffsetY(0);
            }

            @Override
            public void rowDoubleClicked(@NonNull TriggerTemplate template) {
                remove();
                chosen.accept(template);
            }
        });
        Font row_font = Skin.getSkin().getMultiColumnComboBoxData().font();
        Row<TriggerTemplate, Label> first = null;
        for (TriggerTemplate template : TriggerTemplate.values()) {
            Row<TriggerTemplate, Label> row = new Row<>(List.of(new SortedLabel(template.getName(),
                    template.ordinal(), row_font)), template);
            list.addRow(row);
            if (first == null)
                first = row;
        }

        HorizButton button_ok = new OKButton(BUTTON_WIDTH);
        button_ok.addMouseClickListener((_, _, _, _) -> {
            TriggerTemplate template = list.getSelected();
            if (template != null) {
                remove();
                chosen.accept(template);
            }
        });
        HorizButton button_cancel = new CancelButton(BUTTON_WIDTH);
        button_cancel.addMouseClickListener((_, _, _, _) -> cancel());

        addChild(list);
        addChild(box_help);
        addChild(button_ok);
        addChild(button_cancel);
        list.place();
        box_help.place(list, RIGHT_TOP);
        button_cancel.place(box_help, BOTTOM_RIGHT);
        button_ok.place(button_cancel, LEFT_MID);
        compileCanvas();
        centerPos();
        if (first != null) {
            list.selectRow(first);
            box_help.setText(TriggerTemplate.values()[0].getHelp());
        }
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
