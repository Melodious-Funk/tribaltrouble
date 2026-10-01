package com.oddlabs.tt.mapeditor;

import com.oddlabs.tt.font.Font;
import com.oddlabs.tt.form.MessageForm;
import com.oddlabs.tt.form.QuestionForm;
import com.oddlabs.tt.gui.CancelButton;
import com.oddlabs.tt.gui.ColumnInfo;
import com.oddlabs.tt.gui.DateLabel;
import com.oddlabs.tt.gui.FocusDirection;
import com.oddlabs.tt.gui.Form;
import com.oddlabs.tt.gui.GUIRoot;
import com.oddlabs.tt.gui.HorizButton;
import com.oddlabs.tt.gui.Label;
import com.oddlabs.tt.gui.LabelBox;
import com.oddlabs.tt.gui.MultiColumnComboBox;
import com.oddlabs.tt.gui.Row;
import com.oddlabs.tt.gui.Skin;
import com.oddlabs.tt.gui.SortedLabel;
import com.oddlabs.tt.guievent.RowListener;
import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static com.oddlabs.tt.gui.Placement.BOTTOM_LEFT;
import static com.oddlabs.tt.gui.Placement.BOTTOM_RIGHT;
import static com.oddlabs.tt.gui.Placement.LEFT_MID;

/** The saved campaigns, to open one in the editor or delete one. */
final class LoadCampaignDialog extends Form {
    private static final int NAME_WIDTH = 260;
    private static final int LEVELS_WIDTH = 80;
    private static final int DATE_WIDTH = 160;
    private static final int LIST_HEIGHT = 260;
    private static final int BUTTON_WIDTH = 100;

    private final @NonNull GUIRoot gui_root;
    private final @NonNull Path dir;
    private final @NonNull Consumer<@NonNull Path> open;
    private final @NonNull MultiColumnComboBox<CampaignFile.Entry> list;
    private final @NonNull LabelBox label_description;

    LoadCampaignDialog(@NonNull GUIRoot gui_root, @NonNull Path dir, @NonNull Consumer<@NonNull Path> open) {
        super(CampaignEditor.i18n("open_caption"));
        this.gui_root = gui_root;
        this.dir = dir;
        this.open = open;
        list = new MultiColumnComboBox<>(gui_root, new ColumnInfo[]{
                new ColumnInfo(MapEditor.i18n("column_name"), NAME_WIDTH),
                new ColumnInfo(CampaignEditor.i18n("column_levels"), LEVELS_WIDTH),
                new ColumnInfo(MapEditor.i18n("column_modified"), DATE_WIDTH)}, LIST_HEIGHT);
        list.addRowListener(new RowListener<>() {
            @Override
            public void rowChosen(CampaignFile.@NonNull Entry entry) {
                label_description.setText(entry.description());
            }

            @Override
            public void rowDoubleClicked(CampaignFile.@NonNull Entry entry) {
                choose(entry);
            }
        });
        label_description = new LabelBox("", Skin.getSkin().getEditFont(), list.getWidth());
        HorizButton button_open = new HorizButton(CampaignEditor.i18n("open_button"), BUTTON_WIDTH);
        button_open.addMouseClickListener((_, _, _, _) -> {
            CampaignFile.Entry selected = list.getSelected();
            if (selected != null)
                choose(selected);
        });
        HorizButton button_delete = new HorizButton(MapEditor.i18n("delete_button"), BUTTON_WIDTH);
        button_delete.addMouseClickListener((_, _, _, _) -> {
            CampaignFile.Entry selected = list.getSelected();
            if (selected != null)
                gui_root.addModalForm(new QuestionForm(CampaignEditor.i18n("delete_campaign_confirm",
                        selected.name()), (_, _, _, _) -> delete(selected)));
        });
        HorizButton button_cancel = new CancelButton(BUTTON_WIDTH);
        button_cancel.addMouseClickListener((_, _, _, _) -> cancel());
        addChild(list);
        addChild(label_description);
        addChild(button_open);
        addChild(button_delete);
        addChild(button_cancel);
        list.place();
        label_description.place(list, BOTTOM_LEFT);
        button_cancel.place(label_description, BOTTOM_RIGHT);
        button_delete.place(button_cancel, LEFT_MID);
        button_open.place(button_delete, LEFT_MID);
        compileCanvas();
        centerPos();
        refresh();
    }

    private void refresh() {
        list.clear();
        Font font = Skin.getSkin().getMultiColumnComboBoxData().font();
        int index = 0;
        for (CampaignFile.Entry entry : CampaignFile.list(dir)) {
            list.addRow(new Row<>(List.of(
                    new SortedLabel(entry.name(), index++, font),
                    new Label(Integer.toString(entry.level_titles().size()), font),
                    new DateLabel(entry.modified().toMillis(), font)), entry));
        }
        if (list.getSize() > 0) {
            list.selectFirst();
            CampaignFile.Entry first = list.getSelected();
            label_description.setText(first != null ? first.description() : "");
        } else {
            label_description.setText(CampaignEditor.i18n("no_campaigns"));
        }
    }

    private void choose(CampaignFile.@NonNull Entry entry) {
        remove();
        open.accept(entry.path());
    }

    private void delete(CampaignFile.@NonNull Entry entry) {
        try {
            Files.deleteIfExists(entry.path());
        } catch (IOException e) {
            gui_root.addModalForm(new MessageForm(CampaignEditor.i18n("delete_campaign_failed",
                    String.valueOf(e.getMessage()))));
        }
        refresh();
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
