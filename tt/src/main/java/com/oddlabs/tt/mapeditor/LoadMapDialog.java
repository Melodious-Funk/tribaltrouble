package com.oddlabs.tt.mapeditor;

import com.oddlabs.tt.font.Font;
import com.oddlabs.tt.form.MessageForm;
import com.oddlabs.tt.form.QuestionForm;
import com.oddlabs.tt.gui.CancelButton;
import com.oddlabs.tt.gui.ColumnInfo;
import com.oddlabs.tt.gui.DateLabel;
import com.oddlabs.tt.gui.EditLine;
import com.oddlabs.tt.gui.FocusDirection;
import com.oddlabs.tt.gui.Form;
import com.oddlabs.tt.gui.GUIRoot;
import com.oddlabs.tt.gui.HorizButton;
import com.oddlabs.tt.gui.Label;
import com.oddlabs.tt.gui.MultiColumnComboBox;
import com.oddlabs.tt.gui.Row;
import com.oddlabs.tt.gui.Skin;
import com.oddlabs.tt.gui.SortedLabel;
import com.oddlabs.tt.guievent.RowListener;
import com.oddlabs.tt.util.ServerMessageBundler;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static com.oddlabs.tt.gui.Placement.BOTTOM_LEFT;
import static com.oddlabs.tt.gui.Placement.BOTTOM_RIGHT;
import static com.oddlabs.tt.gui.Placement.LEFT_MID;
import static com.oddlabs.tt.gui.Placement.RIGHT_MID;
import static com.oddlabs.tt.gui.Placement.RIGHT_TOP;

/**
 * Browses folders for saved maps, showing a preview of the one selected, to open one or delete one.
 *
 * <p>The folder can be typed into the box at the top, or walked through the list: folders are listed first, with
 * the one above at the very top, and opening one shows what is in it.
 */
final class LoadMapDialog extends Form {
    private static final int BUTTON_WIDTH = 100;
    private static final int NAME_WIDTH = 230;
    private static final int LIST_HEIGHT = 300;
    private static final int PREVIEW_SIZE = 256;
    private static final String PARENT = "..";

    // The folder last browsed, to come back to the next time a map is chosen.
    private static @Nullable Path last_dir;

    /** A line in the list: a folder to open, or a map. */
    private record Item(@NonNull Path path, MapFile.@Nullable Entry entry) {
    }

    private final @NonNull GUIRoot gui_root;
    private final @NonNull Consumer<MapFile.@NonNull Entry> load;
    private final @NonNull EditLine editline_dir;
    private final @NonNull MultiColumnComboBox<Item> list;
    private final @NonNull MapPreviewView preview;
    private final @NonNull Label label_info;
    // Previews already read, by file; empty when the map has none.
    private final Map<Path, Optional<MapPreview>> previews = new HashMap<>();
    private @NonNull Path dir;

    /**
     * @param start_dir the folder to show when no other was browsed before
     * @param caption the dialog's title
     * @param action the label of the button that takes the selected map
     */
    LoadMapDialog(@NonNull GUIRoot gui_root, @NonNull Path start_dir, @NonNull String caption,
            @NonNull String action, @NonNull Consumer<MapFile.@NonNull Entry> load) {
        super(caption);
        this.gui_root = gui_root;
        this.load = load;
        this.dir = last_dir != null && Files.isDirectory(last_dir) ? last_dir : start_dir;

        Label label_dir = new Label(MapEditor.i18n("folder"), Skin.getSkin().getEditFont());
        HorizButton button_go = new HorizButton(MapEditor.i18n("go_button"), BUTTON_WIDTH);
        button_go.addMouseClickListener((_, _, _, _) -> browseTyped());

        ColumnInfo[] columns = new ColumnInfo[]{new ColumnInfo(MapEditor.i18n("column_name"), NAME_WIDTH),
                new ColumnInfo(MapEditor.i18n("column_size"), 110), new ColumnInfo(MapEditor.i18n(
                        "column_edited"), 70), new ColumnInfo(MapEditor.i18n("column_modified"), 150)};
        list = new MultiColumnComboBox<>(gui_root, columns, LIST_HEIGHT);
        list.addRowListener(new RowListener<>() {
            @Override
            public void rowChosen(@NonNull Item item) {
                showPreview(item);
            }

            @Override
            public void rowDoubleClicked(@NonNull Item item) {
                open(item);
            }
        });

        preview = new MapPreviewView(PREVIEW_SIZE);
        label_info = new Label("", Skin.getSkin().getEditFont(), PREVIEW_SIZE);

        // The folder box spans the list and the preview.
        int dir_width = list.getWidth() + Skin.getSkin().getFormData().objectSpacing() + PREVIEW_SIZE
                - label_dir.getWidth() - button_go.getWidth() - 2 * Skin.getSkin().getFormData().objectSpacing();
        editline_dir = new EditLine(dir_width, 1024);
        editline_dir.addEnterListener(_ -> browseTyped());

        HorizButton button_load = new HorizButton(action, BUTTON_WIDTH);
        button_load.addMouseClickListener((_, _, _, _) -> {
            Item selected = list.getSelected();
            if (selected != null)
                open(selected);
        });
        HorizButton button_delete = new HorizButton(MapEditor.i18n("delete_button"), BUTTON_WIDTH);
        button_delete.addMouseClickListener((_, _, _, _) -> {
            Item selected = list.getSelected();
            if (selected != null && selected.entry() != null) {
                MapFile.Entry entry = selected.entry();
                gui_root.addModalForm(new QuestionForm(MapEditor.i18n("delete_confirm", entry.name()),
                        (_, _, _, _) -> delete(entry)));
            }
        });
        HorizButton button_cancel = new CancelButton(BUTTON_WIDTH);
        button_cancel.addMouseClickListener((_, _, _, _) -> cancel());

        addChild(label_dir);
        addChild(editline_dir);
        addChild(button_go);
        addChild(list);
        addChild(preview);
        addChild(label_info);
        addChild(button_load);
        addChild(button_delete);
        addChild(button_cancel);
        label_dir.place();
        editline_dir.place(label_dir, RIGHT_MID);
        button_go.place(editline_dir, RIGHT_MID);
        list.place(label_dir, BOTTOM_LEFT);
        preview.place(list, RIGHT_TOP);
        label_info.place(preview, BOTTOM_LEFT);
        button_cancel.place(preview, BOTTOM_RIGHT, list.getHeight() - PREVIEW_SIZE
                + Skin.getSkin().getFormData().objectSpacing());
        button_load.place(button_cancel, LEFT_MID);
        button_delete.place(button_load, LEFT_MID);
        compileCanvas();
        centerPos();
        browse(dir);
    }

    @Override
    public void setFocus(@NonNull FocusDirection direction) {
        if (direction == FocusDirection.BACKWARD) {
            super.setFocus(direction);
        } else {
            list.setFocus(direction);
        }
    }

    /** Opens the folder typed into the box, or points out that it is not one. */
    private void browseTyped() {
        Path typed;
        try {
            typed = Path.of(editline_dir.getContents().trim());
        } catch (InvalidPathException _) {
            editline_dir.triggerError();
            return;
        }
        if (!browse(typed))
            editline_dir.triggerError();
        else
            list.setFocus(FocusDirection.FORWARD);
    }

    /** @return whether the path is a folder, now shown */
    private boolean browse(@NonNull Path path) {
        Path target = path.toAbsolutePath().normalize();
        if (!Files.isDirectory(target))
            return false;
        dir = target;
        last_dir = target;
        editline_dir.set(target.toString());
        refresh();
        return true;
    }

    private void refresh() {
        list.clear();
        Font font = Skin.getSkin().getMultiColumnComboBoxData().font();
        // Folders first, the one above leading, then the maps newest first, as they are listed.
        int index = 0;
        Path parent = dir.getParent();
        if (parent != null)
            list.addRow(folderRow(PARENT, parent, index++, font));
        for (Path sub : folders(dir))
            list.addRow(folderRow(sub.getFileName().toString(), sub, index++, font));
        for (MapFile.Entry entry : MapFile.list(dir)) {
            String edited = entry.edited() ? MapEditor.i18n("edited_yes") : "";
            list.addRow(new Row<>(List.of(
                    new SortedLabel(entry.name(), index++, font),
                    new Label(ServerMessageBundler.getSizeString(entry.settings().size()), font),
                    new Label(edited, font),
                    new DateLabel(entry.modified().toMillis(), font)),
                    new Item(entry.path(), entry)));
        }
        if (list.getSize() > 0)
            list.selectFirst();
        else
            showPreview(null);
    }

    private static @NonNull Row<Item, Label> folderRow(@NonNull String name, @NonNull Path path, int index,
            @NonNull Font font) {
        long modified;
        try {
            modified = Files.getLastModifiedTime(path).toMillis();
        } catch (IOException _) {
            modified = 0;
        }
        return new Row<>(List.of(
                new SortedLabel(name + "/", index, font),
                new Label("", font),
                new Label("", font),
                new DateLabel(modified, font)),
                new Item(path, null));
    }

    /** The folders inside one, by name, leaving out hidden ones. */
    private static @NonNull List<Path> folders(@NonNull Path dir) {
        List<Path> folders = new ArrayList<>();
        try (Stream<Path> paths = Files.list(dir)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                String name = path.getFileName().toString();
                if (!name.startsWith(".") && Files.isDirectory(path) && Files.isReadable(path))
                    folders.add(path);
            }
        } catch (IOException e) {
            IO.println("Could not list folders in " + dir + ": " + e);
        }
        folders.sort((a, b) -> a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString()));
        return folders;
    }

    private void showPreview(@Nullable Item item) {
        MapFile.Entry entry = item != null ? item.entry() : null;
        if (entry == null) {
            preview.show(null, item != null ? MapEditor.i18n("folder_preview") : "");
            label_info.set("");
            return;
        }
        Optional<MapPreview> found = previews.computeIfAbsent(entry.path(), path -> {
            try {
                return Optional.ofNullable(MapFile.loadPreview(path));
            } catch (IOException e) {
                IO.println("Could not read the preview of " + path + ": " + e);
                return Optional.empty();
            }
        });
        preview.show(found.orElse(null), MapEditor.i18n("no_preview"));
        label_info.set(MapEditor.i18n("map_info", ServerMessageBundler.getSizeString(entry.settings().size()),
                ServerMessageBundler.getTerrainTypeString(entry.settings().terrain())));
    }

    /** Opens a folder, or takes a map. */
    private void open(@NonNull Item item) {
        MapFile.Entry entry = item.entry();
        if (entry == null) {
            browse(item.path());
        } else {
            remove();
            load.accept(entry);
        }
    }

    private void delete(MapFile.@NonNull Entry entry) {
        try {
            Files.deleteIfExists(entry.path());
        } catch (IOException e) {
            gui_root.addModalForm(new MessageForm(MapEditor.i18n("delete_failed", e.getMessage())));
        }
        previews.remove(entry.path());
        refresh();
    }
}
