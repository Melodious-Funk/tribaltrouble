package com.oddlabs.tt.mapeditor;

import com.oddlabs.tt.gui.FocusDirection;
import com.oddlabs.tt.gui.Form;
import com.oddlabs.tt.gui.HorizButton;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import static com.oddlabs.tt.gui.Placement.BOTTOM_MID;

/** The editor's Escape menu: back to editing, save, open the island to other players, or leave. */
final class EditorMenu extends Form {
    private static final int BUTTON_WIDTH = 200;

    private final @NonNull HorizButton button_resume;

    /** @param share opens a shared session on the island, or null when one cannot be opened */
    EditorMenu(@NonNull Runnable save, @NonNull Runnable exit, @Nullable Runnable share) {
        super(MapEditor.i18n("headline"));
        button_resume = new HorizButton(MapEditor.i18n("resume"), BUTTON_WIDTH);
        button_resume.addMouseClickListener((_, _, _, _) -> remove());
        HorizButton button_save = new HorizButton(MapEditor.i18n("save"), BUTTON_WIDTH);
        button_save.addMouseClickListener((_, _, _, _) -> {
            remove();
            save.run();
        });
        HorizButton button_exit = new HorizButton(MapEditor.i18n("exit_editor"), BUTTON_WIDTH);
        button_exit.addMouseClickListener((_, _, _, _) -> {
            remove();
            exit.run();
        });

        addChild(button_resume);
        addChild(button_save);
        addChild(button_exit);
        button_resume.place();
        button_save.place(button_resume, BOTTOM_MID);
        HorizButton above_exit = button_save;
        if (share != null) {
            HorizButton button_share = new HorizButton(MapEditor.i18n("session_share"), BUTTON_WIDTH);
            button_share.addMouseClickListener((_, _, _, _) -> {
                remove();
                share.run();
            });
            addChild(button_share);
            button_share.place(button_save, BOTTOM_MID);
            above_exit = button_share;
        }
        button_exit.place(above_exit, BOTTOM_MID);
        compileCanvas();
        centerPos();
    }

    @Override
    public void setFocus(@NonNull FocusDirection direction) {
        if (direction == FocusDirection.BACKWARD) {
            super.setFocus(direction);
        } else {
            button_resume.setFocus(direction);
        }
    }
}
