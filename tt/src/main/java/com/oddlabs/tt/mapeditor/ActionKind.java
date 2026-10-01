package com.oddlabs.tt.mapeditor;

import org.jspecify.annotations.NonNull;

import static com.oddlabs.tt.mapeditor.Param.AREA;
import static com.oddlabs.tt.mapeditor.Param.COUNT;
import static com.oddlabs.tt.mapeditor.Param.DEPLOY_TYPE;
import static com.oddlabs.tt.mapeditor.Param.FACE;
import static com.oddlabs.tt.mapeditor.Param.HEADER;
import static com.oddlabs.tt.mapeditor.Param.NEW_OWNER;
import static com.oddlabs.tt.mapeditor.Param.OBJECT;
import static com.oddlabs.tt.mapeditor.Param.PLAYER;
import static com.oddlabs.tt.mapeditor.Param.ROLE;
import static com.oddlabs.tt.mapeditor.Param.SECONDS;
import static com.oddlabs.tt.mapeditor.Param.TARGET_PLAYER;
import static com.oddlabs.tt.mapeditor.Param.TEXT;
import static com.oddlabs.tt.mapeditor.Param.TRIGGER;
import static com.oddlabs.tt.mapeditor.Param.UNIT_TYPE;

/**
 * What a campaign trigger does once set off, run in order: what the game's campaigns do from their triggers. New
 * kinds go last, as files keep the ordinal.
 */
enum ActionKind {
    /** Shows a story dialog; the actions after it wait until it is closed. */
    DIALOG("action_dialog", HEADER, TEXT, FACE),
    /** Changes the objective shown in the game menu. */
    OBJECTIVE("action_objective", TEXT),
    /** Prints a line of text, like a game notice. */
    MESSAGE("action_message", TEXT),
    /** Holds the actions after it back for a while. */
    WAIT("action_wait", SECONDS),
    /** Flies the camera to an area. */
    CAMERA_JUMP("action_camera", AREA),
    /** Puts new units in an area. */
    SPAWN_UNITS("action_spawn", PLAYER, UNIT_TYPE, COUNT, AREA),
    /** Sends a player's warriors to attack an area. */
    ATTACK_AREA("action_attack_area", PLAYER, COUNT, AREA),
    /** Sends a player's warriors at another player's armory, chieftain or other units. */
    ATTACK_PLAYER("action_attack_player", PLAYER, TARGET_PLAYER, COUNT),
    /** Sends units out of a player's armory. */
    DEPLOY("action_deploy", PLAYER, DEPLOY_TYPE, COUNT),
    /** Fills a player's armory up with units and iron weapons. */
    REFILL_ARMORY("action_refill_armory", PLAYER),
    /** From now on, a player's armory replaces the units the player loses. */
    REINFORCEMENTS("action_reinforcements", PLAYER, DEPLOY_TYPE),
    /** Hands a player's units in an area to another player, as freed captives join the player. */
    CHANGE_OWNER_AREA("action_change_owner_area", PLAYER, AREA, NEW_OWNER),
    /** Hands a placed unit to another player. */
    CHANGE_OWNER_OBJECT("action_change_owner_object", OBJECT, NEW_OWNER),
    /** Takes a placed unit or building away. */
    REMOVE_OBJECT("action_remove_object", OBJECT),
    /** Changes how a computer player behaves. */
    SET_AI("action_set_ai", PLAYER, ROLE),
    /** Starts another trigger watching for its condition. */
    ACTIVATE_TRIGGER("action_activate", TRIGGER),
    /** Stops another trigger. */
    DEACTIVATE_TRIGGER("action_deactivate", TRIGGER),
    /** Wins the level and opens the next one. */
    VICTORY("action_victory"),
    /** Loses the level, with a message. */
    DEFEAT("action_defeat", TEXT);

    private final @NonNull String key;
    private final @NonNull Param @NonNull [] params;

    ActionKind(@NonNull String key, @NonNull Param @NonNull... params) {
        this.key = key;
        this.params = params;
    }

    @NonNull String getName() {
        return CampaignEditor.i18n(key);
    }

    @NonNull Param @NonNull [] getParams() {
        return params;
    }

    static @NonNull ActionKind of(int ordinal) {
        ActionKind[] kinds = values();
        return kinds[Math.clamp(ordinal, 0, kinds.length - 1)];
    }
}
