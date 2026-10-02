package com.oddlabs.tt.mapeditor;

import com.oddlabs.tt.gui.FocusDirection;
import com.oddlabs.tt.gui.Form;
import com.oddlabs.tt.gui.HorizButton;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

import static com.oddlabs.tt.gui.Placement.BOTTOM_MID;

/** The editor's Escape menu: a column of buttons, each closing the menu and then doing its part. */
final class EditorMenu extends Form {
    private static final int BUTTON_WIDTH = 200;

    /** A button and what it does once the menu is closed. */
    record Entry(@NonNull String label, @NonNull Runnable action) {
    }

    private final @Nullable HorizButton button_first;

    /**
     * The map editor's menu: back to editing, save, open the island to other players, or leave.
     *
     * @param share opens a shared session on the island, or null when one cannot be opened
     */
    EditorMenu(@NonNull Runnable save, @NonNull Runnable exit, @Nullable Runnable share) {
        this(MapEditor.i18n("headline"), mapEntries(save, exit, share));
    }

    private static @NonNull List<@NonNull Entry> mapEntries(@NonNull Runnable save, @NonNull Runnable exit,
            @Nullable Runnable share) {
        List<Entry> entries = new ArrayList<>();
        entries.add(new Entry(MapEditor.i18n("resume"), () -> {
        }));
        entries.add(new Entry(MapEditor.i18n("save"), save));
        if (share != null)
            entries.add(new Entry(MapEditor.i18n("session_share"), share));
        entries.add(new Entry(MapEditor.i18n("exit_editor"), exit));
        return entries;
    }

    EditorMenu(@NonNull String caption, @NonNull List<@NonNull Entry> entries) {
        super(caption);
        HorizButton first = null;
        HorizButton previous = null;
        for (Entry entry : entries) {
            HorizButton button = new HorizButton(entry.label(), BUTTON_WIDTH);
            button.addMouseClickListener((_, _, _, _) -> {
                remove();
                entry.action().run();
            });
            addChild(button);
            if (previous == null) {
                button.place();
                first = button;
            } else
                button.place(previous, BOTTOM_MID);
            previous = button;
        }
        button_first = first;
        compileCanvas();
        centerPos();
    }

    @Override
    public void setFocus(@NonNull FocusDirection direction) {
        if (direction == FocusDirection.BACKWARD || button_first == null) {
            super.setFocus(direction);
        } else {
            button_first.setFocus(direction);
        }
    }
}
