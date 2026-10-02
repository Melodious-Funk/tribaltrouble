package com.oddlabs.tt.mapeditor;

import com.oddlabs.tt.model.DeployType;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * A setting of a trigger condition or action, and where in a {@link Step} it is kept. Several settings share a
 * field, since no condition or action uses two of them at once.
 */
enum Param {
    PLAYER("param_player"),
    TARGET_PLAYER("param_target_player"),
    NEW_OWNER("param_new_owner"),
    AREA("param_area"),
    OBJECT("param_object"),
    COUNT("param_count"),
    SECONDS("param_seconds"),
    RADIUS("param_radius"),
    UNIT_TYPE("param_unit_type"),
    DEPLOY_TYPE("param_deploy_type"),
    SUPPLY_TYPE("param_supply_type"),
    MAGIC("param_magic"),
    ROLE("param_role"),
    FACE("param_face"),
    TRIGGER("param_trigger"),
    HEADER("param_header"),
    TEXT("param_text"),
    /** A placed building, beside an {@link #OBJECT} when an action names both a unit and where it goes. */
    BUILDING("param_building"),
    TEAM("param_team"),
    /** Which kind of the player's units a step counts or orders, as a {@link UnitFilter} ordinal. */
    UNIT_FILTER("param_unit_filter");

    /** The kinds of unit that can be spawned, in {@link #UNIT_TYPE} order. */
    static final ObjectKind[] UNIT_TYPES = {ObjectKind.PEON, ObjectKind.ROCK_WARRIOR, ObjectKind.IRON_WARRIOR,
            ObjectKind.RUBBER_WARRIOR, ObjectKind.CHIEFTAIN};
    /** What an armory sends out, in {@link #DEPLOY_TYPE} order. */
    static final DeployType[] DEPLOY_TYPES = {DeployType.ROCK_WARRIOR, DeployType.IRON_WARRIOR,
            DeployType.RUBBER_WARRIOR, DeployType.PEON};
    /** The AI roles a player can be switched to, in {@link #ROLE} order. */
    static final Scenario.Role[] ROLES = {Scenario.Role.OPPONENT, Scenario.Role.PASSIVE, Scenario.Role.NEUTRAL,
            Scenario.Role.OPPONENT_EASY, Scenario.Role.OPPONENT_NORMAL, Scenario.Role.OPPONENT_HARD};
    /** Portraits per tribe; {@link #FACE} 0 is none, then the Vikings' and then the Natives'. */
    static final int FACES_PER_TRIBE = 9;

    private final @NonNull String key;

    Param(@NonNull String key) {
        this.key = key;
    }

    @NonNull String getCaption() {
        return CampaignEditor.i18n(key);
    }

    /** Whether the setting is a number typed in. */
    boolean isNumber() {
        return this == COUNT || this == SECONDS || this == RADIUS;
    }

    /** Whether the setting is text rather than a number or a choice. */
    boolean isText() {
        return this == HEADER || this == TEXT;
    }

    /** Whether the setting names a placed object, picked on the island. */
    boolean isObject() {
        return this == OBJECT || this == BUILDING;
    }

    int getDefault() {
        return switch (this) {
            case COUNT -> 5;
            case SECONDS -> 60;
            case RADIUS -> 12;
            case PLAYER -> 1;
            case AREA, OBJECT, BUILDING, TRIGGER -> -1;
            // Iron warriors, the campaigns' usual troops.
            case UNIT_TYPE -> 2;
            case DEPLOY_TYPE -> 1;
            default -> 0;
        };
    }

    int getMax() {
        return switch (this) {
            case SECONDS -> 60 * 60 * 3;
            case RADIUS -> 200;
            default -> 1000;
        };
    }

    /** The teams a tribe can be on, in {@link Scenario.PlayerSetup#team} order: the numbered ones, then neutral. */
    static @NonNull String @NonNull [] teamNames() {
        String[] names = new String[Scenario.NEUTRAL_TEAM + 1];
        for (int i = 0; i < Scenario.NUM_PLAYERS; i++)
            names[i] = CampaignEditor.i18n("team_name", i + 1);
        names[Scenario.NEUTRAL_TEAM] = CampaignEditor.i18n("team_neutral");
        return names;
    }

    /** The names to choose between, for a setting picked from a dropdown of fixed choices, or null. */
    @NonNull String @Nullable [] getChoices() {
        return switch (this) {
            case UNIT_TYPE -> {
                String[] names = new String[UNIT_TYPES.length];
                for (int i = 0; i < names.length; i++)
                    names[i] = UNIT_TYPES[i].getName();
                yield names;
            }
            case DEPLOY_TYPE -> new String[]{CampaignEditor.i18n("object_rock_warrior"),
                    CampaignEditor.i18n("object_iron_warrior"), CampaignEditor.i18n("object_rubber_warrior"),
                    CampaignEditor.i18n("object_peon")};
            case SUPPLY_TYPE -> new String[]{CampaignEditor.i18n("supply_tree"), CampaignEditor.i18n("supply_rock"),
                    CampaignEditor.i18n("supply_iron"), CampaignEditor.i18n("supply_rubber")};
            case MAGIC -> new String[]{CampaignEditor.i18n("magic_0"), CampaignEditor.i18n("magic_1")};
            case UNIT_FILTER -> {
                UnitFilter[] filters = UnitFilter.values();
                String[] names = new String[filters.length];
                for (int i = 0; i < names.length; i++)
                    names[i] = filters[i].getName();
                yield names;
            }
            case TEAM -> teamNames();
            case ROLE -> {
                String[] names = new String[ROLES.length];
                for (int i = 0; i < names.length; i++)
                    names[i] = ROLES[i].getName();
                yield names;
            }
            case FACE -> {
                String[] names = new String[1 + 2 * FACES_PER_TRIBE];
                names[0] = CampaignEditor.i18n("face_none");
                for (int i = 0; i < FACES_PER_TRIBE; i++) {
                    names[1 + i] = CampaignEditor.i18n("face_viking", i + 1);
                    names[1 + FACES_PER_TRIBE + i] = CampaignEditor.i18n("face_native", i + 1);
                }
                yield names;
            }
            default -> null;
        };
    }
}
