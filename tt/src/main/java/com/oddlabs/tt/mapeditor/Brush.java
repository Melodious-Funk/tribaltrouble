package com.oddlabs.tt.mapeditor;

import com.oddlabs.tt.procedural.Landscape;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** The brushes, in toolbar order: terrain shaping first, then resource painting. */
enum Brush {
    HEIGHT("brush_height", "hint_height", null),
    FLATTEN("brush_flatten", "hint_flatten", null),
    SMOOTH("brush_smooth", "hint_smooth", null),
    RANDOM("brush_random", "hint_random", null),
    RAMP("brush_ramp", "hint_ramp", null),
    TREES("brush_trees", "hint_trees", Resource.TREE),
    PALMS("brush_palms", "hint_trees", Resource.PALM),
    ROCK("brush_rock", "hint_rock", Resource.ROCK),
    IRON("brush_iron", "hint_iron", Resource.IRON),
    /** Clears every kind of resource under the brush. */
    ERASE("brush_erase", "hint_erase", null);

    private final @NonNull String name_key;
    private final @NonNull String hint_key;
    private final @Nullable Resource resource;

    Brush(@NonNull String name_key, @NonNull String hint_key, @Nullable Resource resource) {
        this.name_key = name_key;
        this.hint_key = hint_key;
        this.resource = resource;
    }

    /** The name, which for trees depends on the island's terrain: jungle and palm, or oak and pine. */
    @NonNull String getName(Landscape.@NonNull TerrainType terrain) {
        if (resource == Resource.TREE || resource == Resource.PALM)
            return MapEditor.i18n(name_key + "_" + terrain.name().toLowerCase(java.util.Locale.ROOT));
        return MapEditor.i18n(name_key);
    }

    @NonNull String getHint() {
        return MapEditor.i18n(hint_key);
    }

    /** Ramps are laid in one go when the drag ends; every other brush paints while the button is held. */
    boolean isDragShape() {
        return this == RAMP;
    }

    /** Whether this brush works on resources rather than on the terrain. */
    boolean isResourceBrush() {
        return resource != null || this == ERASE;
    }

    /** The resource this brush paints, or null for a terrain brush or the eraser. */
    @Nullable Resource getResource() {
        return resource;
    }
}
