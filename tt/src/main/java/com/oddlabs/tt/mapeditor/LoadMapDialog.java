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
import com.oddlabs.tt.gui.MultiColumnComboBox;
import com.oddlabs.tt.gui.Row;
import com.oddlabs.tt.gui.Skin;
import com.oddlabs.tt.guievent.RowListener;
import com.oddlabs.tt.util.ServerMessageBundler;
import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static com.oddlabs.tt.gui.Placement.BOTTOM_RIGHT;
import static com.oddlabs.tt.gui.Placement.LEFT_MID;

/** Lists the saved maps to open one, or delete one. */
final class LoadMapDialog extends Form {
    private static final int BUTTON_WIDTH = 100;
    private static final int NAME_WIDTH = 260;
    private static final int LIST_HEIGHT = 260;

    private final @NonNull GUIRoot gui_root;
    private final @NonNull Path dir;
    private final @NonNull Consumer<MapFile.@NonNull Entry> load;
    private final @NonNull MultiColumnComboBox<MapFile.Entry> list;

    LoadMapDialog(@NonNull GUIRoot gui_root, @NonNull Path dir, @NonNull Consumer<MapFile.@NonNull Entry> load) {
        super(MapEditor.i18n("load_caption"));
        this.gui_root = gui_root;
        this.dir = dir;
        this.load = load;

        ColumnInfo[] columns = new ColumnInfo[]{new ColumnInfo(MapEditor.i18n("column_name"), NAME_WIDTH),
                new ColumnInfo(MapEditor.i18n("column_size"), 120), new ColumnInfo(MapEditor.i18n(
                        "column_edited"), 80), new ColumnInfo(MapEditor.i18n("column_modified"), 170)};
        list = new MultiColumnComboBox<>(gui_root, columns, LIST_HEIGHT);
        list.addRowListener(new RowListener<>() {
            @Override
            public void rowDoubleClicked(MapFile.@NonNull Entry entry) {
                choose(entry);
            }
        });

        HorizButton button_load = new HorizButton(MapEditor.i18n("load_button"), BUTTON_WIDTH);
        button_load.addMouseClickListener((_, _, _, _) -> {
            MapFile.Entry selected = list.getSelected();
            if (selected != null)
                choose(selected);
        });
        HorizButton button_delete = new HorizButton(MapEditor.i18n("delete_button"), BUTTON_WIDTH);
        button_delete.addMouseClickListener((_, _, _, _) -> {
            MapFile.Entry selected = list.getSelected();
            if (selected != null)
                gui_root.addModalForm(new QuestionForm(MapEditor.i18n("delete_confirm", selected.name()),
                        (_, _, _, _) -> delete(selected)));
        });
        HorizButton button_cancel = new CancelButton(BUTTON_WIDTH);
        button_cancel.addMouseClickListener((_, _, _, _) -> cancel());

        addChild(list);
        addChild(button_load);
        addChild(button_delete);
        addChild(button_cancel);
        list.place();
        button_cancel.place(list, BOTTOM_RIGHT);
        button_load.place(button_cancel, LEFT_MID);
        button_delete.place(button_load, LEFT_MID);
        compileCanvas();
        centerPos();
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

    private void refresh() {
        list.clear();
        Font font = Skin.getSkin().getMultiColumnComboBoxData().font();
        List<MapFile.Entry> entries = MapFile.list(dir);
        for (MapFile.Entry entry : entries) {
            String edited = entry.edited() ? MapEditor.i18n("edited_yes") : "";
            list.addRow(new Row<>(List.of(
                    new Label(entry.name(), font, NAME_WIDTH),
                    new Label(ServerMessageBundler.getSizeString(entry.settings().size()), font),
                    new Label(edited, font),
                    new DateLabel(entry.modified().toMillis(), font)),
                    entry));
        }
        if (!entries.isEmpty())
            list.selectFirst();
    }

    private void choose(MapFile.@NonNull Entry entry) {
        remove();
        load.accept(entry);
    }

    private void delete(MapFile.@NonNull Entry entry) {
        try {
            Files.deleteIfExists(entry.path());
        } catch (IOException e) {
            gui_root.addModalForm(new MessageForm(MapEditor.i18n("delete_failed", e.getMessage())));
        }
        refresh();
    }
}
