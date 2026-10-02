package com.oddlabs.tt.mapeditor;

import com.oddlabs.tt.gui.ColumnInfo;
import com.oddlabs.tt.gui.GUIRoot;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * Browses folders for saved campaigns, in a {@link FileBrowserDialog}, to open one or delete one. It starts in the
 * campaigns folder, but campaigns kept anywhere else can be found too.
 */
final class LoadCampaignDialog implements FileBrowserDialog.FileType<CampaignFile.Entry> {
    private static final FileBrowserDialog.Memory memory = new FileBrowserDialog.Memory();

    private LoadCampaignDialog() {
    }

    /** The dialog the campaign editor opens a campaign with. */
    static @NonNull FileBrowserDialog<CampaignFile.Entry> create(@NonNull GUIRoot gui_root, @NonNull Path start_dir,
            @NonNull Consumer<@NonNull Path> open) {
        return create(gui_root, start_dir, CampaignEditor.i18n("open_caption"), CampaignEditor.i18n("open_button"),
                open);
    }

    /**
     * @param start_dir the folder to show when no other was browsed before
     * @param caption   the dialog's title
     * @param action    the label of the button that takes the selected campaign
     */
    static @NonNull FileBrowserDialog<CampaignFile.Entry> create(@NonNull GUIRoot gui_root, @NonNull Path start_dir,
            @NonNull String caption, @NonNull String action, @NonNull Consumer<@NonNull Path> open) {
        return new FileBrowserDialog<>(gui_root, new LoadCampaignDialog(), start_dir, caption, action,
                entry -> open.accept(entry.path()));
    }

    @Override
    public @NonNull String extension() {
        return CampaignFile.EXTENSION;
    }

    @Override
    public @NonNull List<CampaignFile.Entry> list(@NonNull Path dir) {
        return CampaignFile.list(dir);
    }

    @Override
    public @NonNull Path path(CampaignFile.@NonNull Entry entry) {
        return entry.path();
    }

    @Override
    public @NonNull String name(CampaignFile.@NonNull Entry entry) {
        return entry.name();
    }

    @Override
    public @NonNull ColumnInfo @NonNull [] columns() {
        return new ColumnInfo[]{new ColumnInfo(CampaignEditor.i18n("column_levels"), 80),
                new ColumnInfo(MapEditor.i18n("column_modified"), 150)};
    }

    @Override
    public @NonNull List<FileBrowserDialog.@NonNull Value> values(CampaignFile.@NonNull Entry entry) {
        int levels = entry.level_titles().size();
        return List.of(new FileBrowserDialog.Value(Integer.toString(levels), levels),
                FileBrowserDialog.Value.date(entry.modified().toMillis()));
    }

    /** The island of the first level. */
    @Override
    public @Nullable MapPreview preview(CampaignFile.@NonNull Entry entry) throws IOException {
        if (entry.level_titles().isEmpty())
            return null;
        CampaignFile.Level first = CampaignFile.loadLevel(entry.path(), 0);
        if (first.preview != null)
            return first.preview;
        return first.heights != null ? MapPreview.render(first.heights, first.settings, first.resources) : null;
    }

    @Override
    public @NonNull String info(CampaignFile.@NonNull Entry entry) {
        return CampaignEditor.i18n("campaign_info", entry.level_titles().size());
    }

    /** The description, then the levels by title. */
    @Override
    public @Nullable String description(CampaignFile.@NonNull Entry entry) {
        StringBuilder text = new StringBuilder(entry.description().isEmpty() ? MapEditor.i18n("no_description")
                : entry.description());
        List<String> titles = entry.level_titles();
        if (titles.isEmpty())
            text.append("\n\n").append(CampaignEditor.i18n("no_levels"));
        else
            text.append('\n');
        for (int i = 0; i < titles.size(); i++)
            text.append('\n').append(CampaignEditor.i18n("level_title", i + 1, titles.get(i)));
        return text.toString();
    }

    @Override
    public @Nullable Path home() {
        return CampaignEditor.getCampaignsDir();
    }

    @Override
    public @NonNull String homeButton() {
        return CampaignEditor.i18n("browse_campaigns");
    }

    @Override
    public @NonNull String deleteConfirm(CampaignFile.@NonNull Entry entry) {
        return CampaignEditor.i18n("delete_campaign_confirm", entry.name());
    }

    @Override
    public @NonNull String deleteFailed(@NonNull String reason) {
        return CampaignEditor.i18n("delete_campaign_failed", reason);
    }

    @Override
    public @NonNull String notFound() {
        return CampaignEditor.i18n("browse_no_campaign");
    }

    @Override
    public @NonNull String unreadable() {
        return CampaignEditor.i18n("browse_unreadable_campaign");
    }

    @Override
    public FileBrowserDialog.@NonNull Memory memory() {
        return memory;
    }
}
