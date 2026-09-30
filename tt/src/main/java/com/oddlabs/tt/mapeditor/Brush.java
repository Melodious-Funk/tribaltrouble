package com.oddlabs.tt.mapeditor;

import org.jspecify.annotations.NonNull;

/** The terrain brushes, in toolbar order. */
enum Brush {
    HEIGHT("brush_height", "hint_height"),
    FLATTEN("brush_flatten", "hint_flatten"),
    SMOOTH("brush_smooth", "hint_smooth"),
    RANDOM("brush_random", "hint_random"),
    RAMP("brush_ramp", "hint_ramp");

    private final @NonNull String name_key;
    private final @NonNull String hint_key;

    Brush(@NonNull String name_key, @NonNull String hint_key) {
        this.name_key = name_key;
        this.hint_key = hint_key;
    }

    @NonNull String getName() {
        return MapEditor.i18n(name_key);
    }

    @NonNull String getHint() {
        return MapEditor.i18n(hint_key);
    }

    /** Ramps are laid in one go when the drag ends; every other brush paints while the button is held. */
    boolean isDragShape() {
        return this == RAMP;
    }
}
