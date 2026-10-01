package com.oddlabs.tt.mapeditor;

import com.oddlabs.tt.resource.WorldGenerator;
import org.jspecify.annotations.NonNull;

import java.nio.file.Path;

/** A saved map chosen to play, with what the skirmish menu needs to start a game on it. */
public final class CustomMap {
    private final @NonNull String name;
    private final @NonNull MapSettings settings;
    private final @NonNull Path path;

    CustomMap(@NonNull String name, @NonNull MapSettings settings, @NonNull Path path) {
        this.name = name;
        this.settings = settings;
        this.path = path;
    }

    public @NonNull String getName() {
        return name;
    }

    /** The island size, as an index into the skirmish menu's sizes. */
    public int getSizeIndex() {
        return settings.size();
    }

    public @NonNull String getMapcode() {
        return settings.toMapcode();
    }

    /** A generator building the island from the settings, with the map's saved heights and resources in place. */
    public @NonNull WorldGenerator createGenerator() {
        return settings.createGenerator(new SavedMapSource(path));
    }
}
