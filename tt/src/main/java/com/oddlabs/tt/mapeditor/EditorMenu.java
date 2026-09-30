package com.oddlabs.tt.mapeditor;

import com.oddlabs.tt.gui.FocusDirection;
import com.oddlabs.tt.gui.Form;
import com.oddlabs.tt.gui.HorizButton;
import org.jspecify.annotations.NonNull;

import static com.oddlabs.tt.gui.Placement.BOTTOM_MID;

/** The editor's Escape menu: back to editing, save, or leave. */
final class EditorMenu extends Form {
    private static final int BUTTON_WIDTH = 200;

    private final @NonNull HorizButton button_resume;

    EditorMenu(@NonNull Runnable save, @NonNull Runnable exit) {
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
        button_exit.place(button_save, BOTTOM_MID);
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
