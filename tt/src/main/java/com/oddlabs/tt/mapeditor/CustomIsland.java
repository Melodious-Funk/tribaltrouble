package com.oddlabs.tt.mapeditor;

import com.oddlabs.matchmaking.Game;
import com.oddlabs.net.NetworkSelector;
import com.oddlabs.tt.global.Globals;
import com.oddlabs.tt.gui.GUIRoot;
import com.oddlabs.tt.landscape.WorldParameters;
import com.oddlabs.tt.net.GameNetwork;
import com.oddlabs.tt.net.PlayerSlot;
import com.oddlabs.tt.player.Player;
import com.oddlabs.tt.player.UnitInfo;
import com.oddlabs.tt.player.campaign.CampaignState;
import com.oddlabs.tt.player.campaign.Island;
import com.oddlabs.tt.viewer.WorldViewer;
import org.jspecify.annotations.NonNull;

/** A level of a custom campaign, played on its saved island with its scenario. */
final class CustomIsland extends Island {
    private final @NonNull CustomCampaign campaign;
    private final int index;

    CustomIsland(@NonNull CustomCampaign campaign, int index) {
        super(campaign);
        this.campaign = campaign;
        this.index = index;
    }

    private @NonNull Scenario getScenario() {
        return campaign.getLevel(index).scenario;
    }

    @Override
    protected void init(@NonNull NetworkSelector network, @NonNull GUIRoot gui_root) {
        CampaignFile.Level level = campaign.getLevel(index);
        MapSettings settings = level.settings;
        // A level that starts with ships plays with them, on any island.
        boolean ships = settings.isArchipelago();
        for (Scenario.Placement placement : level.scenario.placements)
            ships |= placement.kind().isShip();
        // spotless:off
        WorldParameters world_params = WorldParameters.builder()
                .initialGameSpeed(Game.GAMESPEED_NORMAL)
                .mapcode(settings.toMapcode())
                .initialUnitCount(1)
                .maxUnitCount(Player.DEFAULT_MAX_UNIT_COUNT)
                .mapSize(settings.size())
                .ships(Globals.SHIPS_ENABLED && ships)
                .build();
        // spotless:on
        String[] ai_names = new String[Scenario.NUM_PLAYERS];
        for (int i = 0; i < ai_names.length; i++)
            ai_names[i] = CampaignEditor.i18n("tribe_name", i + 1);
        GameNetwork game_network = startNewGame(network, gui_root,
                settings.createGenerator(new CampaignFile.LevelSource(campaign.getPath().toString(), index)),
                world_params, ai_names);
        int difficulty = campaign.getState().getDifficulty();
        Scenario scenario = level.scenario;
        for (int i = 0; i < Scenario.NUM_PLAYERS; i++) {
            Scenario.PlayerSetup player = scenario.players[i];
            if (!player.enabled)
                continue;
            boolean human = i == 0;
            game_network.getClient().getServerInterface().setPlayerSlot(i, human ? PlayerSlot.HUMAN : PlayerSlot.AI,
                    player.race, Scenario.gameTeam(player.team), true,
                    human ? PlayerSlot.AI_NONE : aiSlot(player.role, difficulty));
            // The scenario places every unit and building itself.
            game_network.getClient().setUnitInfo(i, new UnitInfo());
        }
        game_network.getClient().getServerInterface().startServer();
    }

    /** The AI a computer player gets; opponents play as well as the campaign's difficulty. */
    private static int aiSlot(Scenario.@NonNull Role role, int difficulty) {
        return switch (role) {
            case OPPONENT -> switch (difficulty) {
                case CampaignState.DIFFICULTY_EASY -> PlayerSlot.AI_EASY;
                case CampaignState.DIFFICULTY_HARD -> PlayerSlot.AI_HARD;
                default -> PlayerSlot.AI_NORMAL;
            };
            case OPPONENT_EASY -> PlayerSlot.AI_EASY;
            case OPPONENT_NORMAL -> PlayerSlot.AI_NORMAL;
            case OPPONENT_HARD -> PlayerSlot.AI_HARD;
            case PASSIVE -> PlayerSlot.AI_PASSIVE_CAMPAIGN;
            case NEUTRAL, HUMAN -> PlayerSlot.AI_NEUTRAL_CAMPAIGN;
        };
    }

    @Override
    protected void start() {
        WorldViewer viewer = getViewer();
        ScenarioRunner runner = new ScenarioRunner(viewer, getScenario(), campaign.getState().getDifficulty(),
                new ScenarioRunner.Outcome() {
                    @Override
                    public void victory() {
                        campaign.levelWon(viewer, index);
                    }

                    @Override
                    public void defeat(@NonNull String message) {
                        campaign.defeated(viewer, message);
                    }
                });
        campaign.setRunner(runner);
        runner.start();
    }

    @Override
    protected boolean hasDefaultDefeat() {
        // The scenario watches for defeat itself, as the player may start without a chieftain.
        return false;
    }

    @Override
    protected @NonNull CharSequence getHeader() {
        return getScenario().title;
    }

    @Override
    protected @NonNull CharSequence getDescription() {
        return getScenario().briefing;
    }

    @Override
    protected @NonNull CharSequence getCurrentObjective() {
        return campaign.getCurrentObjective();
    }
}
