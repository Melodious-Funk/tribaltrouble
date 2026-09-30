package com.oddlabs.tt.mapeditor;

import com.oddlabs.tt.form.MapcodeForm;
import com.oddlabs.tt.form.TerrainMenu;
import com.oddlabs.tt.render.Renderer;
import com.oddlabs.tt.util.Utils;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.ResourceBundle;

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

    /** Where saved maps live, or null when the game has no writable directory. */
    static @Nullable Path getMapsDir() {
        Path game_dir = Renderer.getLocalInput().getGameDir();
        return game_dir == null ? null : game_dir.resolve(MAPS_DIR);
    }
}
