package com.oddlabs.tt.mapeditor;

import com.oddlabs.net.NetworkSelector;
import com.oddlabs.tt.delegate.Menu;
import com.oddlabs.tt.form.CampaignDialogForm;
import com.oddlabs.tt.form.MessageForm;
import com.oddlabs.tt.gui.CampaignIcons;
import com.oddlabs.tt.gui.GUI;
import com.oddlabs.tt.gui.GUIRoot;
import com.oddlabs.tt.gui.Origin;
import com.oddlabs.tt.gui.VikingCampaignIcons;
import com.oddlabs.tt.model.RacesResources;
import com.oddlabs.tt.player.campaign.Campaign;
import com.oddlabs.tt.player.campaign.CampaignState;
import com.oddlabs.tt.render.Renderer;
import com.oddlabs.tt.steam.SteamManager;
import com.oddlabs.tt.viewer.WorldViewer;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;

/**
 * A campaign made in the campaign editor, played from the campaign menu: its levels are played in order, each
 * opening once the one before is won, at the difficulty the campaign was started with.
 */
public final class CustomCampaign extends Campaign {
    /** Where a test run started from the editor goes back to. */
    interface TestReturn {
        void back(@NonNull NetworkSelector network, @NonNull GUI gui);
    }

    private final @NonNull CampaignFile file;
    private final @NonNull Path path;
    private final @Nullable TestReturn test;
    private @Nullable ScenarioRunner runner;

    private CustomCampaign(@NonNull CampaignState state, @NonNull CampaignFile file, @NonNull Path path,
            @Nullable TestReturn test) {
        super(state);
        this.file = file;
        this.path = path;
        this.test = test;
        state.setNumIslands(file.levels.size());
        // Custom levels are made with every weapon and spell in mind.
        state.setHasRubberWeapons(true);
        state.setHasMagic0(true);
        state.setHasMagic1(true);
    }

    /** A new campaign state for playing a saved custom campaign from its first level. */
    public static @NonNull CampaignState newState(@NonNull String campaign_name) {
        CampaignState state = new CampaignState(new int[]{CampaignState.ISLAND_AVAILABLE});
        state.setRace(CampaignState.RACE_CUSTOM);
        state.setCustomCampaign(campaign_name);
        return state;
    }

    /**
     * Opens the custom campaign a campaign state plays.
     *
     * @return the campaign, or null after telling the player it could not be read
     */
    public static @Nullable CustomCampaign open(@NonNull GUIRoot gui_root, @NonNull CampaignState state) {
        Path dir = CampaignEditor.getCampaignsDir();
        String name = state.getCustomCampaign();
        if (dir == null || name == null) {
            gui_root.addModalForm(new MessageForm(CampaignEditor.i18n("campaign_missing", String.valueOf(name))));
            return null;
        }
        Path path = CampaignFile.pathFor(dir, name);
        try {
            CampaignFile file = CampaignFile.load(path);
            if (file.levels.isEmpty())
                throw new IOException(CampaignEditor.i18n("no_levels"));
            return new CustomCampaign(state, file, path, null);
        } catch (IOException e) {
            gui_root.addModalForm(new MessageForm(CampaignEditor.i18n("campaign_load_failed", name,
                    String.valueOf(e.getMessage()))));
            return null;
        }
    }

    /** Plays one level of a saved campaign as a test, keeping no progress and going back once the game is over. */
    static void test(@NonNull NetworkSelector network, @NonNull GUIRoot gui_root, @NonNull Path path,
            @NonNull CampaignFile file, int level, int difficulty, @NonNull TestReturn back) {
        CampaignState state = newState(file.name);
        state.setDifficulty(difficulty);
        CustomCampaign campaign = new CustomCampaign(state, file, path, back);
        campaign.startIsland(network, gui_root, level);
    }

    @NonNull String getName() {
        return file.name;
    }

    @NonNull String getDescription() {
        return file.description;
    }

    int getNumLevels() {
        return file.levels.size();
    }

    CampaignFile.@NonNull Level getLevel(int index) {
        return file.levels.get(index);
    }

    @NonNull Path getPath() {
        return path;
    }

    void setRunner(@Nullable ScenarioRunner runner) {
        this.runner = runner;
    }

    /** Opens the level list in the main menu. */
    public void showLevels(@NonNull NetworkSelector network, @NonNull Menu main_menu) {
        main_menu.setMenuCentered(new CustomCampaignForm(network, main_menu.getGUIRoot(), this));
    }

    @Override
    public @NonNull CampaignIcons getIcons() {
        return VikingCampaignIcons.getIcons();
    }

    /** Shows the level's briefing, and starts it once the player goes on. */
    @Override
    public void islandChosen(@NonNull NetworkSelector network, @NonNull GUIRoot gui_root, int number) {
        Scenario scenario = getLevel(number).scenario;
        String briefing = scenario.briefing.isBlank() ? scenario.objective : scenario.briefing;
        gui_root.addModalForm(new CampaignDialogForm(scenario.title, briefing, null, Origin.AT_START,
                () -> startIsland(network, gui_root, number), true));
    }

    @Override
    public @NonNull CharSequence getCurrentObjective() {
        ScenarioRunner current = runner;
        if (current != null)
            return current.getObjective();
        int island = getState().getCurrentIsland();
        return island >= 0 && island < getNumLevels() ? getLevel(island).scenario.objective : "";
    }

    @Override
    public void startIsland(@NonNull NetworkSelector network, @NonNull GUIRoot gui_root, int number) {
        runner = null;
        getState().setCurrentIsland(number);
        // Steam knows the two tribes' campaigns; a custom one shows as the tribe played.
        boolean natives = getLevel(number).scenario.players[0].race == RacesResources.RACE_NATIVES;
        SteamManager.setCampaignRichPresence(natives ? "Natives" : "Vikings");
        SteamManager.setInActiveWorld(true);
        new CustomIsland(this, number).chosen(network, gui_root);
    }

    /** Marks a level won, opens the next, and ends the game as won. */
    void levelWon(@NonNull WorldViewer viewer, int index) {
        getState().setIslandState(index, CampaignState.ISLAND_COMPLETED);
        if (index + 1 < getNumLevels())
            getState().setIslandState(index + 1, CampaignState.ISLAND_AVAILABLE);
        victory(viewer);
    }

    @Override
    protected boolean savesProgress() {
        return test == null;
    }

    @Override
    public void gameClosed(@NonNull NetworkSelector network, @NonNull GUI gui) {
        runner = null;
        if (test != null) {
            test.back(network, gui);
            return;
        }
        Renderer.startMenu(network, gui, menu -> showLevels(network, menu));
    }
}
