package com.oddlabs.tt.mapeditor;

import com.oddlabs.tt.render.Renderer;
import com.oddlabs.tt.util.Utils;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;

/**
 * Shared strings and locations for the campaign editor and for playing the campaigns it makes.
 */
public final class CampaignEditor {
    private static final ResourceBundle bundle = ResourceBundle.getBundle(CampaignEditor.class.getName());

    private static final String CAMPAIGNS_DIR = "campaigns";

    private CampaignEditor() {
    }

    public static @NonNull String i18n(@NonNull String key, @NonNull Object @NonNull... args) {
        return Utils.getBundleString(bundle, key, args);
    }

    /** Where saved campaigns live, or null when the game has no writable directory. */
    static @Nullable Path getCampaignsDir() {
        Path game_dir = Renderer.getLocalInput().getGameDir();
        return game_dir == null ? null : game_dir.resolve(CAMPAIGNS_DIR);
    }

    /** The names of the saved campaigns that have levels to play, newest first. */
    public static @NonNull List<@NonNull String> playableCampaigns() {
        List<String> names = new ArrayList<>();
        Path dir = getCampaignsDir();
        if (dir == null)
            return names;
        for (CampaignFile.Entry entry : CampaignFile.list(dir)) {
            if (!entry.level_titles().isEmpty())
                names.add(entry.name());
        }
        return names;
    }
}
