package com.oddlabs.tt.mapeditor;

import com.oddlabs.tt.form.QuestionForm;
import com.oddlabs.tt.gui.CancelButton;
import com.oddlabs.tt.gui.EditLine;
import com.oddlabs.tt.gui.FocusDirection;
import com.oddlabs.tt.gui.Form;
import com.oddlabs.tt.gui.GUIRoot;
import com.oddlabs.tt.gui.HorizButton;
import com.oddlabs.tt.gui.Label;
import com.oddlabs.tt.gui.OKButton;
import com.oddlabs.tt.gui.Skin;
import org.jspecify.annotations.NonNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.oddlabs.tt.gui.Placement.BOTTOM_RIGHT;
import static com.oddlabs.tt.gui.Placement.LEFT_MID;
import static com.oddlabs.tt.gui.Placement.RIGHT_MID;

/** Asks for the name to save a map under, and confirms before replacing another map. */
final class SaveMapDialog extends Form {
    private static final int BUTTON_WIDTH = 100;
    private static final int EDITLINE_WIDTH = 280;

    private final @NonNull GUIRoot gui_root;
    private final @NonNull Function<@NonNull String, @NonNull Path> path_for;
    private final @NonNull Function<@NonNull String, @NonNull String> overwrite_question;
    private final @NonNull Consumer<@NonNull String> save;
    private final @NonNull EditLine editline_name;

    SaveMapDialog(@NonNull GUIRoot gui_root, @NonNull Path dir, @NonNull String initial_name,
            @NonNull Consumer<@NonNull String> save) {
        this(gui_root, MapEditor.i18n("save_caption"), MapEditor.i18n("map_name"),
                name -> MapFile.pathFor(dir, name), name -> MapEditor.i18n("overwrite_confirm", name), initial_name,
                save);
    }

    /**
     * @param path_for where a name is saved, to ask before replacing what is there
     * @param overwrite_question the question asked before replacing it
     */
    SaveMapDialog(@NonNull GUIRoot gui_root, @NonNull String caption, @NonNull String name_caption,
            @NonNull Function<@NonNull String, @NonNull Path> path_for,
            @NonNull Function<@NonNull String, @NonNull String> overwrite_question, @NonNull String initial_name,
            @NonNull Consumer<@NonNull String> save) {
        super(caption);
        this.gui_root = gui_root;
        this.path_for = path_for;
        this.overwrite_question = overwrite_question;
        this.save = save;

        Label label_name = new Label(name_caption, Skin.getSkin().getEditFont());
        editline_name = new EditLine(EDITLINE_WIDTH, MapFile.getMaxNameLength());
        editline_name.append(initial_name);
        editline_name.addEnterListener(_ -> submit());

        HorizButton button_ok = new OKButton(BUTTON_WIDTH);
        button_ok.addMouseClickListener((_, _, _, _) -> submit());
        HorizButton button_cancel = new CancelButton(BUTTON_WIDTH);
        button_cancel.addMouseClickListener((_, _, _, _) -> cancel());

        addChild(label_name);
        addChild(editline_name);
        addChild(button_ok);
        addChild(button_cancel);
        label_name.place();
        editline_name.place(label_name, RIGHT_MID);
        button_cancel.place(editline_name, BOTTOM_RIGHT);
        button_ok.place(button_cancel, LEFT_MID);
        compileCanvas();
        centerPos();
    }

    @Override
    public void setFocus(@NonNull FocusDirection direction) {
        if (direction == FocusDirection.BACKWARD) {
            super.setFocus(direction);
        } else {
            editline_name.setFocus(direction);
        }
    }

    private void submit() {
        String name = editline_name.getContents().trim();
        if (!MapFile.isValidName(name)) {
            editline_name.triggerError();
            return;
        }
        remove();
        if (Files.exists(path_for.apply(name))) {
            gui_root.addModalForm(new QuestionForm(overwrite_question.apply(name),
                    (_, _, _, _) -> save.accept(name)));
        } else {
            save.accept(name);
        }
    }
}
