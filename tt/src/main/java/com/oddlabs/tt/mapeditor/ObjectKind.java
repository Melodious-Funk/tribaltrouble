package com.oddlabs.tt.mapeditor;

import com.oddlabs.tt.model.Race;
import com.oddlabs.tt.model.RacesResources;
import org.jspecify.annotations.NonNull;

/** The units and buildings a campaign level can start with, in the editor's dropdown order. */
enum ObjectKind {
    PEON("object_peon", Race.UNIT_PEON, -1),
    ROCK_WARRIOR("object_rock_warrior", Race.UNIT_WARRIOR_ROCK, -1),
    IRON_WARRIOR("object_iron_warrior", Race.UNIT_WARRIOR_IRON, -1),
    RUBBER_WARRIOR("object_rubber_warrior", Race.UNIT_WARRIOR_RUBBER, -1),
    CHIEFTAIN("object_chieftain", Race.UNIT_CHIEFTAIN, -1),
    QUARTERS("object_quarters", -1, Race.BUILDING_QUARTERS),
    ARMORY("object_armory", -1, Race.BUILDING_ARMORY),
    TOWER("object_tower", -1, Race.BUILDING_TOWER),
    /** A tower with an iron warrior inside, as the campaigns guard their bases. */
    GUARDED_TOWER("object_guarded_tower", -1, Race.BUILDING_TOWER);

    private final @NonNull String name_key;
    private final int unit_type;
    private final int building_type;

    ObjectKind(@NonNull String name_key, int unit_type, int building_type) {
        this.name_key = name_key;
        this.unit_type = unit_type;
        this.building_type = building_type;
    }

    @NonNull String getName() {
        return CampaignEditor.i18n(name_key);
    }

    boolean isBuilding() {
        return building_type != -1;
    }

    /** The race's unit template index, for units. */
    int getUnitType() {
        return unit_type;
    }

    /** The race's building template index, for buildings. */
    int getBuildingType() {
        return building_type;
    }

    /** How many cells the object takes out from its centre cell, as the game occupies them (a unit takes one). */
    int getFootprintRadius() {
        return switch (building_type) {
            case Race.BUILDING_QUARTERS -> RacesResources.QUARTERS_SIZE - 1;
            case Race.BUILDING_ARMORY -> RacesResources.ARMORY_SIZE - 1;
            case Race.BUILDING_TOWER -> RacesResources.TOWER_SIZE - 1;
            default -> 0;
        };
    }

    static @NonNull ObjectKind of(int ordinal) {
        ObjectKind[] kinds = values();
        return kinds[Math.clamp(ordinal, 0, kinds.length - 1)];
    }
}
