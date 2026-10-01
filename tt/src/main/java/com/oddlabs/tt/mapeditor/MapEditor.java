package com.oddlabs.tt.mapeditor;

import com.oddlabs.tt.form.MapcodeForm;
import com.oddlabs.tt.form.MessageForm;
import com.oddlabs.tt.form.TerrainMenu;
import com.oddlabs.tt.gui.GUIRoot;
import com.oddlabs.tt.render.Renderer;
import com.oddlabs.tt.util.Utils;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ResourceBundle;
import java.util.function.Consumer;

/**
 * Shared strings and locations for the map editor.
 */
public final class MapEditor {
    private static final ResourceBundle bundle = ResourceBundle.getBundle(MapEditor.class.getName());
    // The terrain options share their captions with the skirmish menu so they stay translated alike.
    private static final ResourceBundle terrain_bundle = ResourceBundle.getBundle(TerrainMenu.class.getName());
    private static final ResourceBundle mapcode_bundle = ResourceBundle.getBundle(MapcodeForm.class.getName());

    private static final String MAPS_DIR = "maps";

    private MapEditor() {
    }

    public static @NonNull String i18n(@NonNull String key, @NonNull Object @NonNull... args) {
        return Utils.getBundleString(bundle, key, args);
    }

    static @NonNull String terrainI18n(@NonNull String key, @NonNull Object @NonNull... args) {
        return Utils.getBundleString(terrain_bundle, key, args);
    }

    static @NonNull String mapcodeI18n(@NonNull String key) {
        return Utils.getBundleString(mapcode_bundle, key);
    }

    /**
     * Opens the map browser to choose a saved map to play, and hands it over once chosen.
     */
    public static void chooseMapToPlay(@NonNull GUIRoot gui_root, @NonNull Consumer<@NonNull CustomMap> play) {
        Path dir = getMapsDir();
        if (dir != null) {
            try {
                Files.createDirectories(dir);
            } catch (IOException _) {
                dir = null;
            }
        }
        Path start = dir != null ? dir : Path.of(System.getProperty("user.home"));
        gui_root.addModalForm(new LoadMapDialog(gui_root, start, i18n("play_caption"), i18n("play_button"),
                entry -> {
                    MapFile map;
                    try {
                        // Read it all now, so a broken file is reported here rather than while the game loads.
                        map = MapFile.load(entry.path());
                    } catch (IOException e) {
                        gui_root.addModalForm(new MessageForm(i18n("load_failed", e.getMessage())));
                        return;
                    }
                    play.accept(new CustomMap(map.name(), map.settings(), entry.path()));
                }));
    }

    /** Where saved maps live, or null when the game has no writable directory. */
    static @Nullable Path getMapsDir() {
        Path game_dir = Renderer.getLocalInput().getGameDir();
        return game_dir == null ? null : game_dir.resolve(MAPS_DIR);
    }
}
