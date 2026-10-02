package com.oddlabs.tt.mapeditor;

import com.oddlabs.tt.gui.ColumnInfo;
import com.oddlabs.tt.gui.GUIRoot;
import com.oddlabs.tt.gui.TextBox;
import com.oddlabs.tt.util.ServerMessageBundler;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/** Browses folders for saved maps, in a {@link FileBrowserDialog}, to open one or delete one. */
final class LoadMapDialog implements FileBrowserDialog.FileType<MapFile.Entry> {
    private static final FileBrowserDialog.Memory memory = new FileBrowserDialog.Memory();

    private LoadMapDialog() {
    }

    /**
     * @param start_dir the folder to show when no other was browsed before
     * @param caption   the dialog's title
     * @param action    the label of the button that takes the selected map
     */
    static @NonNull FileBrowserDialog<MapFile.Entry> create(@NonNull GUIRoot gui_root, @NonNull Path start_dir,
            @NonNull String caption, @NonNull String action, @NonNull Consumer<MapFile.@NonNull Entry> load) {
        return new FileBrowserDialog<>(gui_root, new LoadMapDialog(), start_dir, caption, action, load);
    }

    /** Shows a map's description in a box, scrolled to its start, or nothing when no map is shown. */
    static void showDescription(@NonNull TextBox box, @Nullable String description) {
        box.setText(description == null ? "" : description.isEmpty() ? MapEditor.i18n("no_description")
                : description);
        box.setOffsetY(0);
    }

    @Override
    public @NonNull String extension() {
        return MapFile.EXTENSION;
    }

    @Override
    public @NonNull List<MapFile.Entry> list(@NonNull Path dir) {
        return MapFile.list(dir);
    }

    @Override
    public @NonNull Path path(MapFile.@NonNull Entry entry) {
        return entry.path();
    }

    @Override
    public @NonNull String name(MapFile.@NonNull Entry entry) {
        return entry.name();
    }

    @Override
    public @NonNull ColumnInfo @NonNull [] columns() {
        return new ColumnInfo[]{new ColumnInfo(MapEditor.i18n("column_size"), 110),
                new ColumnInfo(MapEditor.i18n("column_edited"), 70),
                new ColumnInfo(MapEditor.i18n("column_modified"), 150)};
    }

    @Override
    public @NonNull List<FileBrowserDialog.@NonNull Value> values(MapFile.@NonNull Entry entry) {
        return List.of(new FileBrowserDialog.Value(ServerMessageBundler.getSizeString(entry.settings().size()),
                        entry.settings().size()),
                FileBrowserDialog.Value.of(entry.edited() ? MapEditor.i18n("edited_yes") : ""),
                FileBrowserDialog.Value.date(entry.modified().toMillis()));
    }

    @Override
    public @Nullable MapPreview preview(MapFile.@NonNull Entry entry) throws IOException {
        return MapFile.loadPreview(entry.path());
    }

    @Override
    public @NonNull String info(MapFile.@NonNull Entry entry) {
        return MapEditor.i18n("map_info", ServerMessageBundler.getSizeString(entry.settings().size()),
                ServerMessageBundler.getTerrainTypeString(entry.settings().terrain()));
    }

    @Override
    public @Nullable String description(MapFile.@NonNull Entry entry) {
        return entry.description().isEmpty() ? MapEditor.i18n("no_description") : entry.description();
    }

    @Override
    public @Nullable Path home() {
        return MapEditor.getMapsDir();
    }

    @Override
    public @NonNull String homeButton() {
        return MapEditor.i18n("browse_maps");
    }

    @Override
    public @NonNull String deleteConfirm(MapFile.@NonNull Entry entry) {
        return MapEditor.i18n("delete_confirm", entry.name());
    }

    @Override
    public @NonNull String deleteFailed(@NonNull String reason) {
        return MapEditor.i18n("delete_failed", reason);
    }

    @Override
    public @NonNull String notFound() {
        return MapEditor.i18n("browse_not_found");
    }

    @Override
    public @NonNull String unreadable() {
        return MapEditor.i18n("browse_unreadable_map");
    }

    @Override
    public FileBrowserDialog.@NonNull Memory memory() {
        return memory;
    }
}
