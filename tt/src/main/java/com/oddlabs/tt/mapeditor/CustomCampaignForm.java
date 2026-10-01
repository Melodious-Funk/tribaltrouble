package com.oddlabs.tt.mapeditor;

import com.oddlabs.net.NetworkSelector;
import com.oddlabs.tt.font.Font;
import com.oddlabs.tt.gui.CancelButton;
import com.oddlabs.tt.gui.ColumnInfo;
import com.oddlabs.tt.gui.FocusDirection;
import com.oddlabs.tt.gui.Form;
import com.oddlabs.tt.gui.GUIRoot;
import com.oddlabs.tt.gui.HorizButton;
import com.oddlabs.tt.gui.Label;
import com.oddlabs.tt.gui.LabelBox;
import com.oddlabs.tt.gui.MultiColumnComboBox;
import com.oddlabs.tt.gui.Row;
import com.oddlabs.tt.gui.Skin;
import com.oddlabs.tt.gui.SortedLabel;
import com.oddlabs.tt.guievent.RowListener;
import com.oddlabs.tt.player.campaign.CampaignState;
import org.jspecify.annotations.NonNull;

import java.util.List;

import static com.oddlabs.tt.gui.Placement.BOTTOM_LEFT;
import static com.oddlabs.tt.gui.Placement.BOTTOM_RIGHT;
import static com.oddlabs.tt.gui.Placement.LEFT_MID;
import static com.oddlabs.tt.gui.Placement.RIGHT_TOP;

/** A custom campaign's levels, in order, to choose the next one to play. */
final class CustomCampaignForm extends Form {
    private static final int BUTTON_WIDTH = 120;
    private static final int TITLE_WIDTH = 280;
    private static final int STATUS_WIDTH = 120;
    private static final int LIST_HEIGHT = 260;
    private static final int PREVIEW_SIZE = 200;

    private final @NonNull NetworkSelector network;
    private final @NonNull GUIRoot gui_root;
    private final @NonNull CustomCampaign campaign;
    private final @NonNull MultiColumnComboBox<Integer> list;
    private final @NonNull MapPreviewView preview;
    private final @NonNull LabelBox label_level;
    private final @NonNull HorizButton button_play;

    CustomCampaignForm(@NonNull NetworkSelector network, @NonNull GUIRoot gui_root,
            @NonNull CustomCampaign campaign) {
        this.network = network;
        this.gui_root = gui_root;
        this.campaign = campaign;
        CampaignState state = campaign.getState();

        Label headline = new Label(campaign.getName(), Skin.getSkin().getHeadlineFont());
        String difficulty = switch (state.getDifficulty()) {
            case CampaignState.DIFFICULTY_EASY -> CampaignEditor.i18n("difficulty_easy");
            case CampaignState.DIFFICULTY_HARD -> CampaignEditor.i18n("difficulty_hard");
            default -> CampaignEditor.i18n("difficulty_normal");
        };
        int width = TITLE_WIDTH + STATUS_WIDTH + PREVIEW_SIZE + Skin.getSkin().getFormData().objectSpacing();
        LabelBox label_description = new LabelBox(campaign.getDescription() + "\n"
                + CampaignEditor.i18n("playing_at", state.getName(), difficulty)
                + (isComplete() ? "\n" + CampaignEditor.i18n("campaign_complete") : ""),
                Skin.getSkin().getEditFont(), width);

        ColumnInfo[] columns = {new ColumnInfo(CampaignEditor.i18n("column_level"), TITLE_WIDTH),
                new ColumnInfo(CampaignEditor.i18n("column_status"), STATUS_WIDTH)};
        list = new MultiColumnComboBox<>(gui_root, columns, LIST_HEIGHT);
        list.addRowListener(new RowListener<>() {
            @Override
            public void rowChosen(@NonNull Integer index) {
                select(index);
            }

            @Override
            public void rowDoubleClicked(@NonNull Integer index) {
                play(index);
            }
        });
        preview = new MapPreviewView(PREVIEW_SIZE);
        label_level = new LabelBox("", Skin.getSkin().getEditFont(), PREVIEW_SIZE);

        button_play = new HorizButton(CampaignEditor.i18n("play_level"), BUTTON_WIDTH);
        button_play.addMouseClickListener((_, _, _, _) -> {
            Integer selected = list.getSelected();
            if (selected != null)
                play(selected);
        });
        HorizButton button_back = new CancelButton(BUTTON_WIDTH);
        button_back.addMouseClickListener((_, _, _, _) -> cancel());

        addChild(headline);
        addChild(label_description);
        addChild(list);
        addChild(preview);
        addChild(label_level);
        addChild(button_play);
        addChild(button_back);
        headline.place();
        label_description.place(headline, BOTTOM_LEFT);
        list.place(label_description, BOTTOM_LEFT);
        preview.place(list, RIGHT_TOP);
        label_level.place(preview, BOTTOM_LEFT);
        button_back.place(list, BOTTOM_RIGHT);
        button_play.place(button_back, LEFT_MID);
        compileCanvas();

        fill();
    }

    private boolean isComplete() {
        CampaignState state = campaign.getState();
        for (int i = 0; i < state.getNumIslands(); i++) {
            if (state.getIslandState(i) != CampaignState.ISLAND_COMPLETED)
                return false;
        }
        return true;
    }

    private void fill() {
        Font font = Skin.getSkin().getMultiColumnComboBoxData().font();
        CampaignState state = campaign.getState();
        // Start on the level to play next: the first one open and not yet won.
        Row<Integer, Label> next = null;
        for (int i = 0; i < campaign.getNumLevels(); i++) {
            int island = state.getIslandState(i);
            String status = switch (island) {
                case CampaignState.ISLAND_COMPLETED -> CampaignEditor.i18n("status_won");
                case CampaignState.ISLAND_AVAILABLE, CampaignState.ISLAND_SEMI_AVAILABLE ->
                        CampaignEditor.i18n("status_open");
                default -> CampaignEditor.i18n("status_locked");
            };
            Row<Integer, Label> row = new Row<>(List.of(
                    new SortedLabel(CampaignEditor.i18n("level_title", i + 1, campaign.getLevel(i).scenario.title),
                            i, font),
                    new Label(status, font)), i);
            list.addRow(row);
            if (next == null && island != CampaignState.ISLAND_COMPLETED && isPlayable(i))
                next = row;
        }
        if (next != null)
            list.selectRow(next);
        else
            list.selectFirst();
        Integer selected = list.getSelected();
        select(selected != null ? selected : 0);
    }

    private boolean isPlayable(int index) {
        int island = campaign.getState().getIslandState(index);
        return island == CampaignState.ISLAND_AVAILABLE || island == CampaignState.ISLAND_SEMI_AVAILABLE
                || island == CampaignState.ISLAND_COMPLETED;
    }

    private void select(int index) {
        if (index < 0 || index >= campaign.getNumLevels())
            return;
        CampaignFile.Level level = campaign.getLevel(index);
        boolean playable = isPlayable(index);
        preview.show(playable ? level.preview : null, playable ? MapEditor.i18n("no_preview")
                : CampaignEditor.i18n("status_locked"));
        label_level.setText(playable ? level.scenario.objective : CampaignEditor.i18n("locked_hint"));
        button_play.setDisabled(!playable);
    }

    private void play(int index) {
        if (!isPlayable(index))
            return;
        campaign.islandChosen(network, gui_root, index);
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
