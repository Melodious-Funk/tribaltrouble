package com.oddlabs.tt.mapeditor;

import com.oddlabs.tt.gui.GUIObject;
import com.oddlabs.tt.gui.Group;
import com.oddlabs.tt.gui.Label;
import com.oddlabs.tt.gui.Skin;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import static com.oddlabs.tt.gui.Placement.BOTTOM_LEFT;
import static com.oddlabs.tt.gui.Placement.RIGHT_MID;
import static com.oddlabs.tt.gui.Placement.RIGHT_TOP;

/** Lays out captioned rows, one under another, with the captions in a column of their own. */
final class FormRows {
    private final @NonNull Group group = new Group();
    private final int caption_width;
    private @Nullable GUIObject last_caption;

    FormRows(int caption_width) {
        this.caption_width = caption_width;
    }

    /** Adds a row of a caption and controls side by side. */
    @NonNull FormRows add(@NonNull String caption, @NonNull GUIObject @NonNull... controls) {
        Label label = new Label(caption, Skin.getSkin().getEditFont(), caption_width);
        group.addChild(label);
        if (last_caption == null)
            label.place();
        else
            label.place(last_caption, BOTTOM_LEFT);
        // A tall control would overlap the next caption, so the next row starts below the tallest one.
        GUIObject lowest = label;
        GUIObject previous = label;
        for (GUIObject control : controls) {
            group.addChild(control);
            control.place(previous, previous == label && control.getHeight() > label.getHeight() ? RIGHT_TOP
                    : RIGHT_MID);
            if (control.getY() < lowest.getY())
                lowest = control;
            previous = control;
        }
        if (lowest != label) {
            // An invisible spacer under the caption, as low as the tallest control.
            Label spacer = new Label("", Skin.getSkin().getEditFont(), caption_width);
            group.addChild(spacer);
            spacer.place(label, BOTTOM_LEFT, label.getY() - spacer.getHeight() - lowest.getY());
            last_caption = spacer;
        } else {
            last_caption = label;
        }
        return this;
    }

    @NonNull Group build() {
        group.compileCanvas();
        return group;
    }
}
