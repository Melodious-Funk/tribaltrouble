package com.oddlabs.tt.mapeditor;

import com.oddlabs.tt.global.Settings;
import com.oddlabs.tt.gui.CancelButton;
import com.oddlabs.tt.gui.CheckBox;
import com.oddlabs.tt.gui.FocusDirection;
import com.oddlabs.tt.gui.Form;
import com.oddlabs.tt.gui.GUIRoot;
import com.oddlabs.tt.gui.Group;
import com.oddlabs.tt.gui.HorizButton;
import com.oddlabs.tt.gui.Label;
import com.oddlabs.tt.gui.LabelBox;
import com.oddlabs.tt.gui.OKButton;
import com.oddlabs.tt.gui.PulldownButton;
import com.oddlabs.tt.gui.PulldownItem;
import com.oddlabs.tt.gui.PulldownMenu;
import com.oddlabs.tt.gui.Skin;
import com.oddlabs.tt.model.RacesResources;
import org.jspecify.annotations.NonNull;

import static com.oddlabs.tt.gui.Placement.BOTTOM_LEFT;
import static com.oddlabs.tt.gui.Placement.BOTTOM_RIGHT;
import static com.oddlabs.tt.gui.Placement.LEFT_MID;
import static com.oddlabs.tt.gui.Placement.RIGHT_MID;

/**
 * The tribes taking part in a level: for each, whether it plays, its tribe, its team and how the computer runs it.
 * The first is the one playing.
 */
final class PlayersForm extends Form {
    private static final int NAME_WIDTH = 110;
    private static final int PULLDOWN_WIDTH = 120;
    private static final int ROLE_WIDTH = 200;
    private static final int BUTTON_WIDTH = 100;

    private final @NonNull ScenarioLayer layer;
    private final @NonNull CheckBox @NonNull [] check_enabled = new CheckBox[Scenario.NUM_PLAYERS];
    private final @NonNull PulldownMenu<Void> @NonNull [] menu_race;
    private final @NonNull PulldownMenu<Void> @NonNull [] menu_team;
    private final @NonNull PulldownMenu<Scenario.Role> @NonNull [] menu_role;
    private final @NonNull HorizButton button_ok;
    // The players as the window opened, so only what was changed in it is changed, in a shared session too.
    private final Scenario.@NonNull PlayerSetup @NonNull [] opened = new Scenario.PlayerSetup[Scenario.NUM_PLAYERS];

    @SuppressWarnings("unchecked")
    PlayersForm(@NonNull GUIRoot gui_root, @NonNull ScenarioLayer layer, @NonNull Runnable changed) {
        super(CampaignEditor.i18n("players_caption"));
        this.layer = layer;
        Scenario scenario = layer.getScenario();
        menu_race = new PulldownMenu[Scenario.NUM_PLAYERS];
        menu_team = new PulldownMenu[Scenario.NUM_PLAYERS];
        menu_role = new PulldownMenu[Scenario.NUM_PLAYERS];

        Group rows = new Group();
        Label previous = null;
        for (int i = 0; i < Scenario.NUM_PLAYERS; i++) {
            Scenario.PlayerSetup player = scenario.players[i];
            opened[i] = player.copy();
            Label name = new Label(Scenario.playerName(i), Skin.getSkin().getEditFont(), NAME_WIDTH);
            name.setColor(Settings.getSettings().team_colours[i]);
            check_enabled[i] = new CheckBox(player.enabled, CampaignEditor.i18n("takes_part"));
            if (i == 0)
                check_enabled[i].setDisabled(true);
            menu_race[i] = new PulldownMenu<>();
            // In the races' order, so the index is the race.
            menu_race[i].addItem(new PulldownItem<>(raceName(RacesResources.RACE_NATIVES)));
            menu_race[i].addItem(new PulldownItem<>(raceName(RacesResources.RACE_VIKINGS)));
            menu_team[i] = new PulldownMenu<>();
            for (String team_name : Param.teamNames())
                menu_team[i].addItem(new PulldownItem<>(team_name));
            menu_role[i] = new PulldownMenu<>();
            int role_index = 0;
            if (i == 0) {
                menu_role[i].addItem(new PulldownItem<>(Scenario.Role.HUMAN.getName(), Scenario.Role.HUMAN));
            } else {
                for (int r = 0; r < Param.ROLES.length; r++) {
                    menu_role[i].addItem(new PulldownItem<>(Param.ROLES[r].getName(), Param.ROLES[r]));
                    if (Param.ROLES[r] == player.role)
                        role_index = r;
                }
            }
            PulldownButton<Void> race = new PulldownButton<>(gui_root, menu_race[i], player.race, PULLDOWN_WIDTH);
            PulldownButton<Void> team = new PulldownButton<>(gui_root, menu_team[i], player.team, PULLDOWN_WIDTH);
            PulldownButton<Scenario.Role> role = new PulldownButton<>(gui_root, menu_role[i], role_index,
                    ROLE_WIDTH);
            rows.addChild(name);
            rows.addChild(check_enabled[i]);
            rows.addChild(race);
            rows.addChild(team);
            rows.addChild(role);
            if (previous == null)
                name.place();
            else
                name.place(previous, BOTTOM_LEFT, Skin.getSkin().getFormData().sectionSpacing());
            check_enabled[i].place(name, RIGHT_MID);
            race.place(check_enabled[i], RIGHT_MID);
            team.place(race, RIGHT_MID);
            role.place(team, RIGHT_MID);
            previous = name;
        }
        rows.compileCanvas();

        LabelBox label_help = new LabelBox(CampaignEditor.i18n("players_help"), Skin.getSkin().getEditFont(),
                rows.getWidth());
        button_ok = new OKButton(BUTTON_WIDTH);
        button_ok.addMouseClickListener((_, _, _, _) -> {
            apply();
            remove();
            changed.run();
        });
        HorizButton button_cancel = new CancelButton(BUTTON_WIDTH);
        button_cancel.addMouseClickListener((_, _, _, _) -> cancel());

        addChild(rows);
        addChild(label_help);
        addChild(button_ok);
        addChild(button_cancel);
        rows.place();
        label_help.place(rows, BOTTOM_LEFT);
        button_cancel.place(label_help, BOTTOM_RIGHT);
        button_ok.place(button_cancel, LEFT_MID);
        compileCanvas();
        centerPos();
    }

    static @NonNull String raceName(int race) {
        return CampaignEditor.i18n(race == RacesResources.RACE_NATIVES ? "race_natives" : "race_vikings");
    }

    private void apply() {
        Scenario scenario = layer.getScenario();
        for (int i = 0; i < Scenario.NUM_PLAYERS; i++) {
            Scenario.PlayerSetup player = scenario.players[i];
            Scenario.PlayerSetup before = opened[i];
            boolean enabled = i == 0 || check_enabled[i].isMarked();
            int race = menu_race[i].getChosenItemIndex();
            int team = menu_team[i].getChosenItemIndex();
            Scenario.Role role = menu_role[i].getItem(menu_role[i].getChosenItemIndex()).getAttachment();
            // What was left as it was keeps what another player in a shared session may have made of it since.
            if (enabled == before.enabled)
                enabled = player.enabled;
            if (race == before.race)
                race = player.race;
            if (team == before.team)
                team = player.team;
            if (role == null || role == before.role)
                role = player.role;
            boolean shown_changed = enabled != player.enabled || race != player.race;
            if (shown_changed || team != player.team || role != player.role)
                layer.markModified();
            player.enabled = enabled;
            player.race = race;
            player.team = team;
            player.role = role;
            if (shown_changed)
                layer.playerChanged(i);
        }
    }

    @Override
    public void setFocus(@NonNull FocusDirection direction) {
        if (direction == FocusDirection.BACKWARD) {
            super.setFocus(direction);
        } else {
            button_ok.setFocus(direction);
        }
    }
}
