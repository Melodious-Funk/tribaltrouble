package com.oddlabs.tt.mapeditor;

import org.jspecify.annotations.NonNull;

import static com.oddlabs.tt.mapeditor.Param.AREA;
import static com.oddlabs.tt.mapeditor.Param.COUNT;
import static com.oddlabs.tt.mapeditor.Param.MAGIC;
import static com.oddlabs.tt.mapeditor.Param.OBJECT;
import static com.oddlabs.tt.mapeditor.Param.PLAYER;
import static com.oddlabs.tt.mapeditor.Param.RADIUS;
import static com.oddlabs.tt.mapeditor.Param.SECONDS;
import static com.oddlabs.tt.mapeditor.Param.SUPPLY_TYPE;

/**
 * What sets a campaign trigger off: one for each of the game's campaign triggers, with what the campaigns built from
 * them by hand made into settings. New kinds go last, as files keep the ordinal.
 */
enum ConditionKind {
    /** As soon as the level starts (GameStartedTrigger). */
    GAME_STARTED("condition_game_started"),
    /** Game time after the trigger became active (TimeTrigger). */
    TIME_ELAPSED("condition_time", SECONDS),
    /** A player has units in an area (NearPointTrigger). */
    UNITS_IN_AREA("condition_units_in_area", PLAYER, AREA, COUNT),
    /** A player's units come near a placed unit or building (NearArmyTrigger). */
    UNITS_NEAR_OBJECT("condition_units_near_object", PLAYER, OBJECT, RADIUS),
    /** A placed unit or building is destroyed (DeathTrigger). */
    OBJECT_DESTROYED("condition_object_destroyed", OBJECT),
    /** A player has no units left (PlayerEleminatedTrigger). */
    PLAYER_ELIMINATED("condition_player_eliminated", PLAYER),
    /** Every enemy of the player is gone (VictoryTrigger). */
    ENEMIES_DEFEATED("condition_enemies_defeated"),
    /** A player is down to fewer units than a count. */
    UNITS_BELOW("condition_units_below", PLAYER, COUNT),
    /** A player has stockpiled a supply (SupplyGatheredTrigger). */
    SUPPLIES_GATHERED("condition_supplies_gathered", PLAYER, SUPPLY_TYPE, COUNT),
    /** The player's chieftain casts a spell in an area (MagicUsedTrigger). */
    MAGIC_USED("condition_magic_used", AREA, MAGIC);

    private final @NonNull String key;
    private final @NonNull Param @NonNull [] params;

    ConditionKind(@NonNull String key, @NonNull Param @NonNull... params) {
        this.key = key;
        this.params = params;
    }

    @NonNull String getName() {
        return CampaignEditor.i18n(key);
    }

    @NonNull Param @NonNull [] getParams() {
        return params;
    }

    static @NonNull ConditionKind of(int ordinal) {
        ConditionKind[] kinds = values();
        return kinds[Math.clamp(ordinal, 0, kinds.length - 1)];
    }
}
