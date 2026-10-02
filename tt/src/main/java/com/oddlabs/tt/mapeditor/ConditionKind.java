package com.oddlabs.tt.mapeditor;

import org.jspecify.annotations.NonNull;

import static com.oddlabs.tt.mapeditor.Param.AREA;
import static com.oddlabs.tt.mapeditor.Param.BUILDING;
import static com.oddlabs.tt.mapeditor.Param.COUNT;
import static com.oddlabs.tt.mapeditor.Param.MAGIC;
import static com.oddlabs.tt.mapeditor.Param.OBJECT;
import static com.oddlabs.tt.mapeditor.Param.PLAYER;
import static com.oddlabs.tt.mapeditor.Param.RADIUS;
import static com.oddlabs.tt.mapeditor.Param.SECONDS;
import static com.oddlabs.tt.mapeditor.Param.SUPPLY_TYPE;
import static com.oddlabs.tt.mapeditor.Param.UNIT_FILTER;

/**
 * What sets a campaign trigger off: one for each of the game's campaign triggers, with what the campaigns built from
 * them by hand made into settings. New kinds go last, as files keep the ordinal.
 */
enum ConditionKind {
    /** As soon as the level starts (GameStartedTrigger). */
    GAME_STARTED("condition_game_started"),
    /** Game time after the trigger became active (TimeTrigger). */
    TIME_ELAPSED("condition_time", SECONDS),
    /** A player has units of a kind in an area (NearPointTrigger). */
    UNITS_IN_AREA("condition_units_in_area", PLAYER, UNIT_FILTER, AREA, COUNT),
    /** A player's units of a kind come near a placed unit or building (NearArmyTrigger). */
    UNITS_NEAR_OBJECT("condition_units_near_object", PLAYER, UNIT_FILTER, OBJECT, RADIUS),
    /** A placed unit or building is destroyed (DeathTrigger). */
    OBJECT_DESTROYED("condition_object_destroyed", OBJECT),
    /** A player has no units left (PlayerEleminatedTrigger). */
    PLAYER_ELIMINATED("condition_player_eliminated", PLAYER),
    /** Every enemy of the player is gone (VictoryTrigger). */
    ENEMIES_DEFEATED("condition_enemies_defeated"),
    /** A player is down to fewer units of a kind than a count. */
    UNITS_BELOW("condition_units_below", PLAYER, UNIT_FILTER, COUNT),
    /** A player has stockpiled a supply (SupplyGatheredTrigger). */
    SUPPLIES_GATHERED("condition_supplies_gathered", PLAYER, SUPPLY_TYPE, COUNT),
    /** The player's chieftain casts a spell in an area (MagicUsedTrigger). */
    MAGIC_USED("condition_magic_used", AREA, MAGIC),
    /** Fewer golden statues than a count are left in an area, or anywhere when it names none. */
    STATUES_LEFT("condition_statues_left", AREA, COUNT),
    /** A tower or ship holds at least a count of units. */
    UNITS_INSIDE("condition_units_inside", BUILDING, COUNT),
    /** A placed unit, building or statue stands in an area. */
    OBJECT_IN_AREA("condition_object_in_area", OBJECT, AREA),
    /** A player has fewer units of a kind than a count in an area: none at all, for a count of 1. */
    UNITS_FEWER_IN_AREA("condition_units_fewer_in_area", PLAYER, UNIT_FILTER, AREA, COUNT);

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
