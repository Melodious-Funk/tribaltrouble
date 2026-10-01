package com.oddlabs.tt.mapeditor;

import com.oddlabs.tt.procedural.LandscapeOverride;
import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.io.Serial;
import java.nio.file.Path;

/**
 * Builds an island from a saved map's heights and resources. Only the file's path travels with the generator, so
 * this works where the client can read the file, as in a single player game on this computer.
 */
final class SavedMapSource implements LandscapeOverride.Source {
    @Serial
    private static final long serialVersionUID = 1;

    private final @NonNull String path;

    SavedMapSource(@NonNull Path path) {
        this.path = path.toAbsolutePath().toString();
    }

    @Override
    public @NonNull LandscapeOverride load() throws IOException {
        return override(MapFile.load(Path.of(path)));
    }

    /** The heights and resources a map builds its island with. */
    static @NonNull LandscapeOverride override(@NonNull MapFile map) {
        MapFile.Resources resources = map.resources();
        return new LandscapeOverride(map.heights(), resources == null ? null : new LandscapeOverride.Resources(
                resources.of(Resource.TREE), resources.of(Resource.PALM), resources.of(Resource.ROCK),
                resources.of(Resource.IRON)));
    }
}
