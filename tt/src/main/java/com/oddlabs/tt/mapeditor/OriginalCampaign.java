package com.oddlabs.tt.mapeditor;

import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.oddlabs.tt.mapeditor.OriginalLevel.DIFFICULTIES;
import static com.oddlabs.tt.mapeditor.OriginalLevel.act;
import static com.oddlabs.tt.mapeditor.OriginalLevel.difficultyName;
import static com.oddlabs.tt.mapeditor.OriginalLevel.when;

/**
 * The game's own Viking and Native campaigns as campaign editor campaigns, to look at and to copy from. The game
 * plays them from code ({@code player.campaign.VikingIsland0} and on), so each island is generated the way that code
 * generates it and its script is written out here as the editor's placements, areas and triggers, in the order the
 * campaign map leads through them. The texts come from the islands' own bundles, in the game's language.
 *
 * <p>Where the editor has no way to say what a script does, the level comes as close as it can:
 * <ul>
 * <li>The game carries the player's army from island to island; each level starts with the army the islands before
 * it leave, when every captive is freed.</li>
 * <li>Rubber weapons and spells come one island at a time in the game; a custom campaign has them all along.</li>
 * <li>The campaign map lets islands be skipped; the levels are played in order.</li>
 * <li>Scripts that keep the player from building or from making a chieftain, and the countdowns of the islands won
 * by holding out, have no trigger yet.</li>
 * <li>Captives tied up and the god Thor are props in the game; captives are a neutral tribe's peons here, and Thor
 * an area.</li>
 * <li>Difficulties that change a tribe's starting units spawn the difference when the level starts.</li>
 * </ul>
 */
final class OriginalCampaign {
    /** The campaigns there are. */
    enum Tribe {
        VIKINGS("original_vikings", OriginalVikingIslands::levels),
        NATIVES("original_natives", OriginalNativeIslands::levels);

        private final @NonNull String key;
        private final @NonNull Function<@NonNull Progress, @NonNull List<CampaignFile.@NonNull Level>> levels;

        Tribe(@NonNull String key,
                @NonNull Function<@NonNull Progress, @NonNull List<CampaignFile.@NonNull Level>> levels) {
            this.key = key;
            this.levels = levels;
        }

        @NonNull String getName() {
            return CampaignEditor.i18n(key);
        }
    }

    /** Told as each island is ready, as generating them takes a while. */
    interface Progress {
        void islandDone();
    }

    private OriginalCampaign() {
    }

    /**
     * Builds a campaign, generating every island.
     *
     * @return a campaign not saved anywhere, named as a copy of the original, so saving it makes a custom campaign
     */
    static @NonNull CampaignFile build(@NonNull Tribe tribe, @NonNull Progress progress) {
        return new CampaignFile(CampaignEditor.i18n("original_copy", tribe.getName()),
                CampaignEditor.i18n(tribe.key + "_description"),
                new ArrayList<>(tribe.levels.apply(progress)));
    }

    // ---- What many scripts do alike ----

    /** The story told as the level starts. */
    static OriginalLevel.@NonNull Trigger intro(@NonNull OriginalLevel level) {
        return level.trigger("original_intro", when(ConditionKind.GAME_STARTED));
    }

    /** Won once every enemy is gone (VictoryTrigger), with what is said then before the level ends. */
    static OriginalLevel.@NonNull Trigger victory(@NonNull OriginalLevel level) {
        return level.trigger("original_victory", when(ConditionKind.ENEMIES_DEFEATED));
    }

    /** Lost when a tribe the player must keep alive has no units left (PlayerEleminatedTrigger). */
    static void lostWith(@NonNull OriginalLevel level, int tribe) {
        level.trigger("original_lost", when(ConditionKind.PLAYER_ELIMINATED, Param.PLAYER, tribe),
                Scenario.playerName(tribe)).defeat("game_over");
    }

    /**
     * Something the script does when the level starts at one difficulty only.
     *
     * @param difficulty a {@code Scenario.Trigger.DIFFICULTY_} bit
     */
    static OriginalLevel.@NonNull Trigger atStart(@NonNull OriginalLevel level, int difficulty,
            @NonNull String name_key) {
        return level.trigger(name_key, when(ConditionKind.GAME_STARTED), difficultyName(difficulty)).only(difficulty);
    }

    /**
     * Gives a computer tribe more of its starting units at the harder difficulties, where the script starts it with
     * more: the level places what it has on easy.
     *
     * @param counts how many it starts with on easy, normal and hard
     */
    static void moreAtStart(@NonNull OriginalLevel level, int tribe, @NonNull ObjectKind kind, int area,
            int @NonNull [] counts) {
        for (int i = 1; i < DIFFICULTIES.length; i++) {
            int extra = counts[i] - counts[0];
            if (extra > 0) {
                atStart(level, DIFFICULTIES[i], "original_extra").then(act(ActionKind.SPAWN_UNITS, Param.PLAYER,
                        tribe, Param.UNIT_TYPE, kind, Param.COUNT, extra, Param.AREA, area));
            }
        }
    }

    /**
     * The attack the scripts send at the player's armory, or the chieftain when it has none, after which the
     * attacker's armory is filled up again and sends out the next wave to wait.
     *
     * @param minutes when it comes at each difficulty, easy first; 0 for not at that difficulty
     * @param attackers how many attack at each difficulty
     * @param next how many the armory sends out afterwards at each difficulty, 0 for none
     */
    static void wave(@NonNull OriginalLevel level, int number, int attacker, float @NonNull [] minutes,
            int @NonNull [] attackers, int @NonNull [] next) {
        for (int i = 0; i < DIFFICULTIES.length; i++) {
            if (minutes[i] <= 0)
                continue;
            OriginalLevel.Trigger trigger = level.trigger("original_wave", when(ConditionKind.TIME_ELAPSED,
                    Param.SECONDS, Math.round(minutes[i] * 60)), number, difficultyName(DIFFICULTIES[i]))
                    .only(DIFFICULTIES[i])
                    .then(act(ActionKind.ATTACK_PLAYER, Param.PLAYER, attacker, Param.TARGET_PLAYER, 0,
                            Param.COUNT, attackers[i]))
                    .then(act(ActionKind.REFILL_ARMORY, Param.PLAYER, attacker));
            if (next[i] > 0)
                trigger.then(act(ActionKind.DEPLOY, Param.PLAYER, attacker, Param.DEPLOY_TYPE, IRON_WARRIORS,
                        Param.COUNT, next[i]));
        }
    }

    /** {@link Param#DEPLOY_TYPES} index of iron warriors, the warriors the scripts deploy. */
    static final int IRON_WARRIORS = 1;
    /** {@link Param#MAGIC} of the first spell, and of the second. */
    static final int FIRST_SPELL = 0;
    static final int SECOND_SPELL = 1;

    /** The same number at every difficulty. */
    static int @NonNull [] all(int count) {
        return new int[]{count, count, count};
    }

    static float @NonNull [] all(float minutes) {
        return new float[]{minutes, minutes, minutes};
    }

    /** Builds the levels one by one, telling the progress. */
    static @NonNull List<CampaignFile.@NonNull Level> levels(@NonNull Progress progress,
            @NonNull List<@NonNull Supplier<@NonNull OriginalLevel>> islands) {
        List<CampaignFile.Level> levels = new ArrayList<>(islands.size());
        for (Supplier<OriginalLevel> island : islands) {
            levels.add(island.get().toLevel());
            progress.islandDone();
        }
        return levels;
    }
}
