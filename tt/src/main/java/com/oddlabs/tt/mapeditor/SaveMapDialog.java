package com.oddlabs.tt.mapeditor;

import com.oddlabs.tt.form.QuestionForm;
import com.oddlabs.tt.gui.CancelButton;
import com.oddlabs.tt.gui.EditBox;
import com.oddlabs.tt.gui.EditLine;
import com.oddlabs.tt.gui.FocusDirection;
import com.oddlabs.tt.gui.Form;
import com.oddlabs.tt.gui.GUIRoot;
import com.oddlabs.tt.gui.HorizButton;
import com.oddlabs.tt.gui.Label;
import com.oddlabs.tt.gui.OKButton;
import com.oddlabs.tt.gui.Skin;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.oddlabs.tt.gui.Placement.BOTTOM_LEFT;
import static com.oddlabs.tt.gui.Placement.BOTTOM_RIGHT;
import static com.oddlabs.tt.gui.Placement.LEFT_MID;
import static com.oddlabs.tt.gui.Placement.RIGHT_MID;

/**
 * Asks for the name to save a map under, and its description, and confirms before replacing another map. Saving a
 * campaign asks for the name alone.
 */
final class SaveMapDialog extends Form {
    private static final int BUTTON_WIDTH = 100;
    private static final int EDITLINE_WIDTH = 280;
    private static final int DESCRIPTION_HEIGHT = 100;

    private final @NonNull GUIRoot gui_root;
    private final @NonNull Function<@NonNull String, @NonNull Path> path_for;
    private final @NonNull Function<@NonNull String, @NonNull String> overwrite_question;
    private final @NonNull BiConsumer<@NonNull String, @NonNull String> save;
    private final @NonNull EditLine editline_name;
    // The description, when saving a map.
    private final @Nullable EditBox editbox_description;

    /** @param save takes the name and the description */
    SaveMapDialog(@NonNull GUIRoot gui_root, @NonNull Path dir, @NonNull String initial_name,
            @NonNull String initial_description, @NonNull BiConsumer<@NonNull String, @NonNull String> save) {
        this(gui_root, MapEditor.i18n("save_caption"), MapEditor.i18n("map_name"),
                name -> MapFile.pathFor(dir, name), name -> MapEditor.i18n("overwrite_confirm", name), initial_name,
                initial_description, save);
    }

    /**
     * @param path_for where a name is saved, to ask before replacing what is there
     * @param overwrite_question the question asked before replacing it
     */
    SaveMapDialog(@NonNull GUIRoot gui_root, @NonNull String caption, @NonNull String name_caption,
            @NonNull Function<@NonNull String, @NonNull Path> path_for,
            @NonNull Function<@NonNull String, @NonNull String> overwrite_question, @NonNull String initial_name,
            @NonNull Consumer<@NonNull String> save) {
        this(gui_root, caption, name_caption, path_for, overwrite_question, initial_name, null,
                (name, _) -> save.accept(name));
    }

    private SaveMapDialog(@NonNull GUIRoot gui_root, @NonNull String caption, @NonNull String name_caption,
            @NonNull Function<@NonNull String, @NonNull Path> path_for,
            @NonNull Function<@NonNull String, @NonNull String> overwrite_question, @NonNull String initial_name,
            @Nullable String initial_description, @NonNull BiConsumer<@NonNull String, @NonNull String> save) {
        super(caption);
        this.gui_root = gui_root;
        this.path_for = path_for;
        this.overwrite_question = overwrite_question;
        this.save = save;

        Label label_name = new Label(name_caption, Skin.getSkin().getEditFont());
        editline_name = new EditLine(EDITLINE_WIDTH, MapFile.getMaxNameLength());
        editline_name.set(initial_name);
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
        if (initial_description != null) {
            Label label_description = new Label(MapEditor.i18n("map_description"), Skin.getSkin().getEditFont());
            EditBox description = new EditBox(label_name.getWidth() + EDITLINE_WIDTH
                    + Skin.getSkin().getFormData().objectSpacing(), DESCRIPTION_HEIGHT, MapFile.MAX_DESCRIPTION_LENGTH);
            description.setText(initial_description);
            addChild(label_description);
            addChild(description);
            label_description.place(label_name, BOTTOM_LEFT, Skin.getSkin().getFormData().sectionSpacing());
            description.place(label_description, BOTTOM_LEFT);
            button_cancel.place(description, BOTTOM_RIGHT);
            editbox_description = description;
        } else {
            button_cancel.place(editline_name, BOTTOM_RIGHT);
            editbox_description = null;
        }
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
        String description = editbox_description != null ? editbox_description.getContents().strip() : "";
        remove();
        if (Files.exists(path_for.apply(name))) {
            gui_root.addModalForm(new QuestionForm(overwrite_question.apply(name),
                    (_, _, _, _) -> save.accept(name, description)));
        } else {
            save.accept(name, description);
        }
    }
}
