package com.oddlabs.tt.mapeditor;

import com.oddlabs.tt.model.Race;
import com.oddlabs.tt.model.RacesResources;
import org.jspecify.annotations.NonNull;

import java.util.List;

/**
 * The units, buildings and things a campaign level can start with. New kinds go last, as files keep the ordinal;
 * {@link #EDITOR_ORDER} is the order the editor lists them in.
 */
enum ObjectKind {
    PEON("object_peon", Race.UNIT_PEON, -1, -1),
    ROCK_WARRIOR("object_rock_warrior", Race.UNIT_WARRIOR_ROCK, -1, -1),
    IRON_WARRIOR("object_iron_warrior", Race.UNIT_WARRIOR_IRON, -1, -1),
    RUBBER_WARRIOR("object_rubber_warrior", Race.UNIT_WARRIOR_RUBBER, -1, -1),
    CHIEFTAIN("object_chieftain", Race.UNIT_CHIEFTAIN, -1, -1),
    QUARTERS("object_quarters", -1, Race.BUILDING_QUARTERS, -1),
    ARMORY("object_armory", -1, Race.BUILDING_ARMORY, -1),
    TOWER("object_tower", -1, Race.BUILDING_TOWER, -1),
    /** A tower with an iron warrior inside, as the campaigns guard their bases. */
    GUARDED_TOWER("object_guarded_tower", -1, Race.BUILDING_TOWER, Race.UNIT_WARRIOR_IRON),
    /** A ship on the sea by a shore, which units can board and leave. */
    SHIP("object_ship", -1, Race.BUILDING_SHIP, -1),
    /** A golden statue, as the campaigns' treasures: no one's, and only taken away by a trigger. */
    STATUE("object_statue", -1, -1, -1),
    ROCK_GUARDED_TOWER("object_rock_guarded_tower", -1, Race.BUILDING_TOWER, Race.UNIT_WARRIOR_ROCK),
    RUBBER_GUARDED_TOWER("object_rubber_guarded_tower", -1, Race.BUILDING_TOWER, Race.UNIT_WARRIOR_RUBBER);

    /** The kinds as the editor's dropdown lists them: units, buildings, then the rest. */
    static final List<ObjectKind> EDITOR_ORDER = List.of(PEON, ROCK_WARRIOR, IRON_WARRIOR, RUBBER_WARRIOR, CHIEFTAIN,
            QUARTERS, ARMORY, TOWER, ROCK_GUARDED_TOWER, GUARDED_TOWER, RUBBER_GUARDED_TOWER, SHIP, STATUE);
    /** Kinds of golden statue, as the game has models for. */
    static final int STATUE_VARIANTS = 6;

    private final @NonNull String name_key;
    private final int unit_type;
    private final int building_type;
    private final int guard_type;

    ObjectKind(@NonNull String name_key, int unit_type, int building_type, int guard_type) {
        this.name_key = name_key;
        this.unit_type = unit_type;
        this.building_type = building_type;
        this.guard_type = guard_type;
    }

    @NonNull String getName() {
        return CampaignEditor.i18n(name_key);
    }

    boolean isBuilding() {
        return building_type != -1;
    }

    /** Whether it is a unit of a tribe, a chieftain among them. */
    boolean isUnit() {
        return unit_type != -1;
    }

    /** Whether it is a warrior, which can man a tower. */
    boolean isWarrior() {
        return this == ROCK_WARRIOR || this == IRON_WARRIOR || this == RUBBER_WARRIOR;
    }

    boolean isShip() {
        return this == SHIP;
    }

    boolean isTower() {
        return building_type == Race.BUILDING_TOWER;
    }

    /** Whether units can go in and come out of it on a trigger's word: a tower or a ship. */
    boolean holdsUnits() {
        return isTower() || isShip();
    }

    /** Whether it belongs to a tribe; a statue belongs to no one. */
    boolean hasOwner() {
        return this != STATUE;
    }

    /** One placed and moved at a time rather than painted: buildings, chieftains and statues. */
    boolean isSingle() {
        return !isUnit() || this == CHIEFTAIN;
    }

    /** The race's unit template index, for units. */
    int getUnitType() {
        return unit_type;
    }

    /** The race's building template index, for buildings. */
    int getBuildingType() {
        return building_type;
    }

    /** The race's unit template index of the warrior manning a guarded tower, or -1. */
    int getGuardType() {
        return guard_type;
    }

    /**
     * How many cells the object takes out from its centre cell, as the game occupies them (a unit takes one). A ship
     * is longer than this, but keeping others this far off is enough in the editor.
     */
    int getFootprintRadius() {
        return switch (building_type) {
            case Race.BUILDING_QUARTERS -> RacesResources.QUARTERS_SIZE - 1;
            case Race.BUILDING_ARMORY -> RacesResources.ARMORY_SIZE - 1;
            case Race.BUILDING_TOWER -> RacesResources.TOWER_SIZE - 1;
            case Race.BUILDING_SHIP -> 2;
            default -> 0;
        };
    }

    static @NonNull ObjectKind of(int ordinal) {
        ObjectKind[] kinds = values();
        return kinds[Math.clamp(ordinal, 0, kinds.length - 1)];
    }
}
