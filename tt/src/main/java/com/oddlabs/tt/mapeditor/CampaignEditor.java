package com.oddlabs.tt.mapeditor;

import com.oddlabs.tt.form.MessageForm;
import com.oddlabs.tt.form.QuestionForm;
import com.oddlabs.tt.gui.GUIRoot;
import com.oddlabs.tt.render.Renderer;
import com.oddlabs.tt.util.Utils;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.ResourceBundle;
import java.util.function.Consumer;

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

    /**
     * Lets the player find a campaign to play in any folder. One from outside the campaigns folder is copied into
     * it, since a campaign is played, and its progress kept, by its name there.
     *
     * @param chosen told the name of the campaign to play, once it is in the campaigns folder
     */
    public static void choosePlayable(@NonNull GUIRoot gui_root, @NonNull Consumer<@NonNull String> chosen) {
        Path dir = getCampaignsDir();
        if (dir == null) {
            gui_root.addModalForm(new MessageForm(MapEditor.i18n("no_maps_dir")));
            return;
        }
        try {
            Files.createDirectories(dir);
        } catch (IOException _) {
            // The browser says so if the folder cannot be shown.
        }
        gui_root.addModalForm(LoadCampaignDialog.create(gui_root, dir, i18n("play_caption"), i18n("play_button"),
                path -> importPlayable(gui_root, dir, path, chosen)));
    }

    private static void importPlayable(@NonNull GUIRoot gui_root, @NonNull Path dir, @NonNull Path source,
            @NonNull Consumer<@NonNull String> chosen) {
        CampaignFile file;
        try {
            file = CampaignFile.load(source);
            if (file.levels.isEmpty())
                throw new IOException(i18n("no_levels"));
        } catch (IOException e) {
            gui_root.addModalForm(new MessageForm(i18n("campaign_load_failed", source.getFileName().toString(),
                    String.valueOf(e.getMessage()))));
            return;
        }
        Path target = CampaignFile.pathFor(dir, file.name);
        try {
            if (Files.exists(target) && Files.isSameFile(source, target)) {
                chosen.accept(file.name);
            } else if (Files.exists(target) && !Arrays.equals(Files.readAllBytes(source), Files.readAllBytes(
                    target))) {
                gui_root.addModalForm(new QuestionForm(i18n("import_replace_confirm", file.name),
                        (_, _, _, _) -> copyPlayable(gui_root, source, target, file.name, chosen)));
            } else {
                copyPlayable(gui_root, source, target, file.name, chosen);
            }
        } catch (IOException e) {
            gui_root.addModalForm(new MessageForm(i18n("import_failed", String.valueOf(e.getMessage()))));
        }
    }

    private static void copyPlayable(@NonNull GUIRoot gui_root, @NonNull Path source, @NonNull Path target,
            @NonNull String name, @NonNull Consumer<@NonNull String> chosen) {
        try {
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        } catch (IOException e) {
            gui_root.addModalForm(new MessageForm(i18n("import_failed", String.valueOf(e.getMessage()))));
            return;
        }
        chosen.accept(name);
    }
}
