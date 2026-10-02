package com.oddlabs.tt.mapeditor;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

/**
 * A campaign open in the campaign editor: kept in memory while its levels are edited one at a time, and written out
 * when saved.
 */
final class CampaignSession {
    final @NonNull CampaignFile file;
    /** The level open in the editor, or the one last edited. */
    int level;
    // The name the campaign was last saved or opened under, or null for one never saved.
    private @Nullable String saved_name;
    private boolean modified;

    private CampaignSession(@NonNull CampaignFile file, @Nullable String saved_name) {
        this.file = file;
        this.saved_name = saved_name;
    }

    static @NonNull CampaignSession create() {
        return new CampaignSession(new CampaignFile("", "", new ArrayList<>()), null);
    }

    static @NonNull CampaignSession open(@NonNull Path path) throws IOException {
        CampaignFile file = CampaignFile.load(path);
        return new CampaignSession(file, file.name);
    }

    /** A new campaign whose first level is an island, opened on that level. */
    static @NonNull CampaignSession startingWith(@NonNull MapFile island) {
        CampaignSession session = create();
        session.file.levels.add(CampaignFile.Level.of(island, CampaignEditor.i18n("level_default_title", 1)));
        return session;
    }

    /** One of the game's own campaigns, never saved: saving it makes a custom campaign of a copy of it. */
    static @NonNull CampaignSession original(@NonNull CampaignFile file) {
        return new CampaignSession(file, null);
    }

    /** A campaign handed over in a shared session, not saved here yet, opened on the level being edited. */
    static @NonNull CampaignSession shared(CampaignFile.@NonNull Shared shared) {
        CampaignSession session = new CampaignSession(shared.file(), null);
        session.level = shared.level();
        return session;
    }

    CampaignFile.@NonNull Level getLevel() {
        return file.levels.get(level);
    }

    @Nullable String getSavedName() {
        return saved_name;
    }

    boolean isModified() {
        return modified;
    }

    void markModified() {
        modified = true;
    }

    /** Whether saving under a name would replace a different campaign than this one. */
    boolean wouldReplaceOther(@NonNull Path dir, @NonNull String name) {
        return !name.equals(saved_name) && Files.exists(CampaignFile.pathFor(dir, name));
    }

    void save(@NonNull Path dir, @NonNull String name) throws IOException {
        file.name = name;
        file.save(dir);
        saved_name = name;
        modified = false;
    }
}
