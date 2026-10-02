package com.oddlabs.tt.mapeditor;

import com.oddlabs.tt.model.Abilities;
import com.oddlabs.tt.model.Race;
import com.oddlabs.tt.model.Unit;
import org.jspecify.annotations.NonNull;

/**
 * Which of a tribe's units a trigger condition counts or an action orders, as {@link Param#UNIT_FILTER} picks. New
 * kinds go last, as files keep the ordinal.
 */
enum UnitFilter {
    ANY("unit_filter_any"),
    PEONS("object_peon"),
    /** Every warrior, whatever its weapon. */
    WARRIORS("unit_filter_warriors"),
    ROCK_WARRIORS("object_rock_warrior"),
    IRON_WARRIORS("object_iron_warrior"),
    RUBBER_WARRIORS("object_rubber_warrior"),
    CHIEFTAIN("object_chieftain");

    private final @NonNull String key;

    UnitFilter(@NonNull String key) {
        this.key = key;
    }

    @NonNull String getName() {
        return CampaignEditor.i18n(key);
    }

    /** Whether a unit is of the kind, telling the kinds apart by their template in the owner's race. */
    boolean matches(@NonNull Unit unit) {
        if (this == ANY)
            return true;
        if (unit.getAbilities().hasAbilities(Abilities.MAGIC))
            return this == CHIEFTAIN;
        Race race = unit.getOwner().getRace();
        return switch (this) {
            case PEONS -> unit.getTemplate() == race.getUnitTemplate(Race.UNIT_PEON);
            case WARRIORS -> unit.getTemplate() == race.getUnitTemplate(Race.UNIT_WARRIOR_ROCK)
                    || unit.getTemplate() == race.getUnitTemplate(Race.UNIT_WARRIOR_IRON)
                    || unit.getTemplate() == race.getUnitTemplate(Race.UNIT_WARRIOR_RUBBER);
            case ROCK_WARRIORS -> unit.getTemplate() == race.getUnitTemplate(Race.UNIT_WARRIOR_ROCK);
            case IRON_WARRIORS -> unit.getTemplate() == race.getUnitTemplate(Race.UNIT_WARRIOR_IRON);
            case RUBBER_WARRIORS -> unit.getTemplate() == race.getUnitTemplate(Race.UNIT_WARRIOR_RUBBER);
            default -> false;
        };
    }

    static @NonNull UnitFilter of(int ordinal) {
        UnitFilter[] filters = values();
        return filters[Math.clamp(ordinal, 0, filters.length - 1)];
    }
}
