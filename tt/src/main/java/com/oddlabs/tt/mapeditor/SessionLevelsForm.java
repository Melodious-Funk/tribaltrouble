package com.oddlabs.tt.mapeditor;

import com.oddlabs.tt.font.Font;
import com.oddlabs.tt.gui.ColumnInfo;
import com.oddlabs.tt.gui.FocusDirection;
import com.oddlabs.tt.gui.Form;
import com.oddlabs.tt.gui.GUIRoot;
import com.oddlabs.tt.gui.Group;
import com.oddlabs.tt.gui.HorizButton;
import com.oddlabs.tt.gui.Label;
import com.oddlabs.tt.gui.LabelBox;
import com.oddlabs.tt.gui.MultiColumnComboBox;
import com.oddlabs.tt.gui.OKButton;
import com.oddlabs.tt.gui.Row;
import com.oddlabs.tt.gui.Skin;
import com.oddlabs.tt.gui.SortedLabel;
import com.oddlabs.tt.guievent.RowListener;
import com.oddlabs.tt.util.ServerMessageBundler;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.function.IntConsumer;

import static com.oddlabs.tt.gui.Placement.BOTTOM_LEFT;
import static com.oddlabs.tt.gui.Placement.BOTTOM_RIGHT;
import static com.oddlabs.tt.gui.Placement.RIGHT_MID;

/**
 * The levels of a campaign shared in a session, from the editor: going to another level, or adding one, takes
 * everyone in the session there together.
 */
final class SessionLevelsForm extends Form {
    private static final int TITLE_WIDTH = 300;
    private static final int SIZE_WIDTH = 110;
    private static final int LIST_HEIGHT = 220;
    private static final int BUTTON_WIDTH = 140;

    private final @NonNull MultiColumnComboBox<Integer> list;
    private final @NonNull HorizButton button_go;

    /**
     * @param go  takes everyone to the level of that number
     * @param add picks an island to add as a level, and takes everyone to it
     */
    SessionLevelsForm(@NonNull GUIRoot gui_root, @NonNull CampaignSession campaign, @NonNull IntConsumer go,
            @NonNull Runnable add) {
        super(CampaignEditor.i18n("session_levels_caption"));
        list = new MultiColumnComboBox<>(gui_root, new ColumnInfo[]{new ColumnInfo(CampaignEditor.i18n("column_level"),
                TITLE_WIDTH), new ColumnInfo(MapEditor.i18n("column_size"), SIZE_WIDTH)}, LIST_HEIGHT);
        button_go = new HorizButton(CampaignEditor.i18n("session_go_level"), BUTTON_WIDTH);
        list.addRowListener(new RowListener<>() {
            @Override
            public void rowChosen(@NonNull Integer index) {
                button_go.setDisabled(index == campaign.level);
            }

            @Override
            public void rowDoubleClicked(@NonNull Integer index) {
                if (index != campaign.level) {
                    remove();
                    go.accept(index);
                }
            }
        });
        Font font = Skin.getSkin().getMultiColumnComboBoxData().font();
        List<CampaignFile.Level> levels = campaign.file.levels;
        Row<Integer, Label> current = null;
        for (int i = 0; i < levels.size(); i++) {
            CampaignFile.Level level = levels.get(i);
            String title = CampaignEditor.i18n("level_title", i + 1, level.scenario.title);
            Row<Integer, Label> row = new Row<>(List.of(
                    new SortedLabel(i == campaign.level ? CampaignEditor.i18n("session_level_current", title) : title,
                            i, font),
                    new Label(ServerMessageBundler.getSizeString(level.settings.size()), font)), i);
            list.addRow(row);
            if (i == campaign.level)
                current = row;
        }
        LabelBox label_help = new LabelBox(CampaignEditor.i18n("session_levels_help"), Skin.getSkin().getEditFont(),
                list.getWidth());

        Group buttons = new Group();
        button_go.addMouseClickListener((_, _, _, _) -> {
            Integer selected = list.getSelected();
            if (selected != null && selected != campaign.level) {
                remove();
                go.accept(selected);
            }
        });
        HorizButton button_add = new HorizButton(CampaignEditor.i18n("add_level"), BUTTON_WIDTH);
        button_add.addMouseClickListener((_, _, _, _) -> {
            remove();
            add.run();
        });
        buttons.addChild(button_go);
        buttons.addChild(button_add);
        button_go.place();
        button_add.place(button_go, RIGHT_MID);
        buttons.compileCanvas();
        HorizButton button_close = new OKButton(BUTTON_WIDTH);
        button_close.addMouseClickListener((_, _, _, _) -> remove());

        addChild(list);
        addChild(label_help);
        addChild(buttons);
        addChild(button_close);
        list.place();
        label_help.place(list, BOTTOM_LEFT);
        buttons.place(label_help, BOTTOM_LEFT);
        button_close.place(buttons, BOTTOM_RIGHT, Skin.getSkin().getFormData().sectionSpacing());
        compileCanvas();
        centerPos();
        if (current != null)
            list.selectRow(current);
        button_go.setDisabled(true);
    }

    @Override
    public void setFocus(@NonNull FocusDirection direction) {
        if (direction == FocusDirection.BACKWARD) {
            super.setFocus(direction);
        } else {
            list.setFocus(direction);
        }
    }
}
