package com.oddlabs.tt.mapeditor;

import com.oddlabs.net.NetworkSelector;
import com.oddlabs.tt.delegate.Menu;
import com.oddlabs.tt.font.Font;
import com.oddlabs.tt.form.MessageForm;
import com.oddlabs.tt.form.ProgressForm;
import com.oddlabs.tt.form.QuestionForm;
import com.oddlabs.tt.gui.CancelButton;
import com.oddlabs.tt.gui.ColumnInfo;
import com.oddlabs.tt.gui.EditBox;
import com.oddlabs.tt.gui.EditLine;
import com.oddlabs.tt.gui.FocusDirection;
import com.oddlabs.tt.gui.Form;
import com.oddlabs.tt.gui.GUI;
import com.oddlabs.tt.gui.GUIRoot;
import com.oddlabs.tt.gui.Group;
import com.oddlabs.tt.gui.HorizButton;
import com.oddlabs.tt.gui.Label;
import com.oddlabs.tt.gui.LabelBox;
import com.oddlabs.tt.gui.MultiColumnComboBox;
import com.oddlabs.tt.gui.Row;
import com.oddlabs.tt.gui.Skin;
import com.oddlabs.tt.gui.SortedLabel;
import com.oddlabs.tt.guievent.RowListener;
import com.oddlabs.tt.util.ServerMessageBundler;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static com.oddlabs.tt.gui.Placement.BOTTOM_LEFT;
import static com.oddlabs.tt.gui.Placement.BOTTOM_RIGHT;
import static com.oddlabs.tt.gui.Placement.LEFT_MID;
import static com.oddlabs.tt.gui.Placement.RIGHT_MID;
import static com.oddlabs.tt.gui.Placement.RIGHT_TOP;

/**
 * The campaign editor's start window: the campaign's name and description and its levels in the order they are
 * played, to add islands as levels, order them, open one in the editor, and to save or open campaigns.
 */
public final class CampaignEditorForm extends Form {
    private static final int CAPTION_WIDTH = 110;
    private static final int TEXT_WIDTH = 420;
    private static final int TITLE_WIDTH = 250;
    private static final int SIZE_WIDTH = 110;
    private static final int COUNT_WIDTH = 110;
    private static final int LIST_HEIGHT = 220;
    private static final int PREVIEW_SIZE = 200;
    private static final int BUTTON_WIDTH = 110;
    private static final int MAX_DESCRIPTION = 1000;

    private final @NonNull NetworkSelector network;
    private final @NonNull GUIRoot gui_root;
    private final @NonNull Menu main_menu;
    private final @NonNull CampaignSession session;
    private final @NonNull EditLine editline_name;
    private final @NonNull EditBox editbox_description;
    private final @NonNull MultiColumnComboBox<Integer> list;
    private final @NonNull MapPreviewView preview;
    private final @NonNull LabelBox label_level;

    public CampaignEditorForm(@NonNull NetworkSelector network, @NonNull GUIRoot gui_root, @NonNull Menu main_menu) {
        this(network, gui_root, main_menu, CampaignSession.create());
    }

    CampaignEditorForm(@NonNull NetworkSelector network, @NonNull GUIRoot gui_root, @NonNull Menu main_menu,
            @NonNull CampaignSession session) {
        this.network = network;
        this.gui_root = gui_root;
        this.main_menu = main_menu;
        this.session = session;

        Label headline = new Label(CampaignEditor.i18n("campaign_editor"), Skin.getSkin().getHeadlineFont());
        editline_name = new EditLine(TEXT_WIDTH, MapFile.getMaxNameLength());
        editline_name.set(session.file.name);
        editbox_description = new EditBox(TEXT_WIDTH, 60, MAX_DESCRIPTION);
        editbox_description.setText(session.file.description);
        Group fields = new FormRows(CAPTION_WIDTH)
                .add(CampaignEditor.i18n("campaign_name"), editline_name)
                .add(CampaignEditor.i18n("campaign_description"), editbox_description)
                .build();

        list = new MultiColumnComboBox<>(gui_root, new ColumnInfo[]{
                new ColumnInfo(CampaignEditor.i18n("column_level"), TITLE_WIDTH),
                new ColumnInfo(MapEditor.i18n("column_size"), SIZE_WIDTH),
                new ColumnInfo(CampaignEditor.i18n("column_contents"), COUNT_WIDTH)}, LIST_HEIGHT);
        list.addRowListener(new RowListener<>() {
            @Override
            public void rowChosen(@NonNull Integer index) {
                showLevel(index);
            }

            @Override
            public void rowDoubleClicked(@NonNull Integer index) {
                edit(index);
            }
        });
        preview = new MapPreviewView(PREVIEW_SIZE);
        label_level = new LabelBox("", Skin.getSkin().getEditFont(), PREVIEW_SIZE);

        Group level_buttons = new Group();
        HorizButton button_add = button(level_buttons, null, "add_level", this::addLevel);
        HorizButton button_edit = button(level_buttons, button_add, "edit_level", () -> {
            Integer selected = list.getSelected();
            if (selected != null)
                edit(selected);
        });
        HorizButton button_up = button(level_buttons, button_edit, "move_up", () -> move(-1));
        HorizButton button_down = button(level_buttons, button_up, "move_down", () -> move(1));
        button(level_buttons, button_down, "remove_level", this::removeLevel);
        level_buttons.compileCanvas();

        Group file_buttons = new Group();
        HorizButton button_new = button(file_buttons, null, "new_campaign", () -> discardThen(
                () -> main_menu.setMenuCentered(new CampaignEditorForm(network, gui_root, main_menu))));
        HorizButton button_open = button(file_buttons, button_new, "open_campaign", this::open);
        HorizButton button_original = button(file_buttons, button_open, "open_original", this::openOriginal);
        HorizButton button_save = button(file_buttons, button_original, "save_campaign_button", this::save);
        HorizButton button_cancel = new CancelButton(BUTTON_WIDTH);
        button_cancel.addMouseClickListener((_, _, _, _) -> cancel());
        file_buttons.addChild(button_cancel);
        button_cancel.place(button_save, RIGHT_MID, 20);
        file_buttons.compileCanvas();

        addChild(headline);
        addChild(fields);
        addChild(list);
        addChild(preview);
        addChild(label_level);
        addChild(level_buttons);
        addChild(file_buttons);
        headline.place();
        fields.place(headline, BOTTOM_LEFT);
        list.place(fields, BOTTOM_LEFT);
        preview.place(list, RIGHT_TOP);
        label_level.place(preview, BOTTOM_LEFT);
        level_buttons.place(list, BOTTOM_LEFT);
        file_buttons.place(level_buttons, BOTTOM_LEFT, Skin.getSkin().getFormData().sectionSpacing());
        compileCanvas();
        refresh(session.file.levels.isEmpty() ? -1 : Math.min(session.level, session.file.levels.size() - 1));
    }

    private static @NonNull HorizButton button(@NonNull Group group, @Nullable HorizButton previous,
            @NonNull String key, @NonNull Runnable action) {
        HorizButton button = new HorizButton(CampaignEditor.i18n(key), BUTTON_WIDTH);
        button.addMouseClickListener((_, _, _, _) -> action.run());
        group.addChild(button);
        if (previous == null)
            button.place();
        else
            button.place(previous, RIGHT_MID);
        return button;
    }

    /** Opens a campaign's level in the editor. */
    static void editLevel(@NonNull NetworkSelector network, @NonNull GUI gui, @NonNull CampaignSession session,
            int level) {
        session.level = level;
        ProgressForm.setProgressForm(network, gui, new MapEditorLoader(network, session));
    }

    /** Takes the name and description typed in into the campaign. */
    private void read() {
        String name = editline_name.getContents().trim();
        String description = editbox_description.getContents();
        if (!name.equals(session.file.name) || !description.equals(session.file.description))
            session.markModified();
        session.file.name = name;
        session.file.description = description;
    }

    private void refresh(int select) {
        list.clear();
        Font font = Skin.getSkin().getMultiColumnComboBoxData().font();
        Row<Integer, Label> selected = null;
        List<CampaignFile.Level> levels = session.file.levels;
        for (int i = 0; i < levels.size(); i++) {
            CampaignFile.Level level = levels.get(i);
            Scenario scenario = level.scenario;
            Row<Integer, Label> row = new Row<>(List.of(
                    new SortedLabel(CampaignEditor.i18n("level_title", i + 1, scenario.title), i, font),
                    new Label(ServerMessageBundler.getSizeString(level.settings.size()), font),
                    new Label(CampaignEditor.i18n("level_contents", scenario.placements.size(),
                            scenario.triggers.size()), font)), i);
            list.addRow(row);
            if (i == select)
                selected = row;
        }
        if (selected != null)
            list.selectRow(selected);
        showLevel(select);
    }

    private void showLevel(int index) {
        if (index < 0 || index >= session.file.levels.size()) {
            preview.show(null, CampaignEditor.i18n("no_level"));
            label_level.setText(CampaignEditor.i18n("no_levels_help"));
            return;
        }
        CampaignFile.Level level = session.file.levels.get(index);
        preview.show(level.preview, MapEditor.i18n("no_preview"));
        label_level.setText(MapEditor.i18n("map_info", ServerMessageBundler.getSizeString(level.settings.size()),
                ServerMessageBundler.getTerrainTypeString(level.settings.terrain())));
    }

    private void addLevel() {
        read();
        gui_root.addModalForm(new MapEditorForm(network, gui_root, map -> {
            int number = session.file.levels.size() + 1;
            session.file.levels.add(CampaignFile.Level.of(map, CampaignEditor.i18n("level_default_title",
                    number)));
            session.markModified();
            refresh(session.file.levels.size() - 1);
        }));
    }

    private void edit(int index) {
        read();
        editLevel(network, gui_root.getGUI(), session, index);
    }

    private void move(int direction) {
        Integer selected = list.getSelected();
        if (selected == null)
            return;
        int target = selected + direction;
        List<CampaignFile.Level> levels = session.file.levels;
        if (target < 0 || target >= levels.size())
            return;
        read();
        levels.add(target, levels.remove((int) selected));
        session.markModified();
        refresh(target);
    }

    private void removeLevel() {
        Integer selected = list.getSelected();
        if (selected == null)
            return;
        int index = selected;
        String title = session.file.levels.get(index).scenario.title;
        gui_root.addModalForm(new QuestionForm(CampaignEditor.i18n("remove_level_confirm", title),
                (_, _, _, _) -> {
                    read();
                    session.file.levels.remove(index);
                    session.markModified();
                    refresh(Math.min(index, session.file.levels.size() - 1));
                }));
    }

    private @Nullable Path campaignsDir() {
        Path dir = CampaignEditor.getCampaignsDir();
        if (dir == null)
            gui_root.addModalForm(new MessageForm(MapEditor.i18n("no_maps_dir")));
        return dir;
    }

    private void save() {
        read();
        Path dir = campaignsDir();
        if (dir == null)
            return;
        String name = session.file.name;
        if (!MapFile.isValidName(name)) {
            editline_name.triggerError();
            gui_root.addModalForm(new MessageForm(CampaignEditor.i18n("invalid_campaign_name")));
            return;
        }
        if (session.wouldReplaceOther(dir, name)) {
            gui_root.addModalForm(new QuestionForm(CampaignEditor.i18n("overwrite_campaign", name),
                    (_, _, _, _) -> write(dir, name)));
        } else {
            write(dir, name);
        }
    }

    private void write(@NonNull Path dir, @NonNull String name) {
        try {
            session.save(dir, name);
            gui_root.getInfoPrinter().print(CampaignEditor.i18n("campaign_saved", name));
        } catch (IOException e) {
            gui_root.addModalForm(new MessageForm(CampaignEditor.i18n("campaign_save_failed",
                    String.valueOf(e.getMessage()))));
        }
    }

    private void open() {
        Path dir = campaignsDir();
        if (dir == null)
            return;
        discardThen(() -> gui_root.addModalForm(new LoadCampaignDialog(gui_root, dir, path -> {
            try {
                main_menu.setMenuCentered(new CampaignEditorForm(network, gui_root, main_menu,
                        CampaignSession.open(path)));
            } catch (IOException e) {
                gui_root.addModalForm(new MessageForm(CampaignEditor.i18n("campaign_load_failed",
                        path.getFileName().toString(), String.valueOf(e.getMessage()))));
            }
        })));
    }

    /** Opens a copy of one of the game's own campaigns, which saving makes a custom campaign. */
    private void openOriginal() {
        discardThen(() -> gui_root.addModalForm(new OriginalCampaignForm(gui_root, file -> main_menu.setMenuCentered(
                new CampaignEditorForm(network, gui_root, main_menu, CampaignSession.original(file))))));
    }

    /** Asks before letting go of unsaved changes, then goes on. */
    private void discardThen(@NonNull Runnable next) {
        read();
        if (session.isModified())
            gui_root.addModalForm(new QuestionForm(CampaignEditor.i18n("discard_confirm"), (_, _, _, _) -> next.run()));
        else
            next.run();
    }

    @Override
    public void cancel() {
        discardThen(super::cancel);
    }

    @Override
    public void setFocus(@NonNull FocusDirection direction) {
        if (direction == FocusDirection.BACKWARD) {
            super.setFocus(direction);
        } else {
            editline_name.setFocus(direction);
        }
    }
}
