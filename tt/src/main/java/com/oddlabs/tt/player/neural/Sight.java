package com.oddlabs.tt.player.neural;

import com.oddlabs.tt.landscape.TreeSupply;
import com.oddlabs.tt.model.IronSupply;
import com.oddlabs.tt.model.LandBuilding;
import com.oddlabs.tt.model.Race;
import com.oddlabs.tt.model.RockSupply;
import com.oddlabs.tt.model.RubberSupply;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.SupplyContainer;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.model.behaviour.Controller;
import com.oddlabs.tt.model.behaviour.GatherController;
import com.oddlabs.tt.model.behaviour.IdleController;
import com.oddlabs.tt.model.behaviour.PlaceBuildingController;
import com.oddlabs.tt.model.behaviour.RepairController;
import com.oddlabs.tt.model.weapon.IronAxeWeapon;
import com.oddlabs.tt.model.weapon.RockAxeWeapon;
import com.oddlabs.tt.model.weapon.RubberAxeWeapon;
import com.oddlabs.tt.pathfinder.UnitGrid;
import com.oddlabs.tt.player.Player;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The game as one decision round reads it: the player's own units and buildings sorted by what they are doing, and
 * the enemies' numbers. Read once per round; the skill layer and the observation both work from it. The game has no
 * fog of war, so all of it is what a player sees.
 */
final class Sight {
    /** The resources in gatherer and stock order: wood, rock, iron, rubber. */
    static final int RESOURCES = 4;
    /** Enemy units this close to home, in cells, threaten it. */
    static final int THREAT_CELLS = 36;
    private static final List<Class<?>> SUPPLIES = List.of(TreeSupply.class, RockSupply.class, IronSupply.class,
            RubberSupply.class);
    private static final List<Class<?>> WEAPONS = List.of(RockAxeWeapon.class, IronAxeWeapon.class,
            RubberAxeWeapon.class);

    final @NonNull List<Unit> idle_peons = new ArrayList<>();
    final @NonNull List<Unit> builders = new ArrayList<>();
    /** Peons outside that are neither idle, building nor gathering: walking somewhere. */
    int busy_peons;
    final int @NonNull [] gatherers = new int[RESOURCES];
    /** Warriors in the field, by Race.UNIT_WARRIOR_*. */
    final int @NonNull [] warrior_kinds = new int[3];
    /** Warriors in the field and the chieftain: everything the army orders move. */
    final @NonNull List<Unit> army = new ArrayList<>();
    final @NonNull List<Unit> idle_army = new ArrayList<>();
    @Nullable
    Unit chieftain;

    final @NonNull List<LandBuilding> quarters = new ArrayList<>();
    final @NonNull List<LandBuilding> armories = new ArrayList<>();
    final @NonNull List<LandBuilding> towers = new ArrayList<>();
    final @NonNull List<LandBuilding> sites = new ArrayList<>();
    /** Sites by Race.BUILDING_QUARTERS, _ARMORY and _TOWER. */
    final int @NonNull [] site_kinds = new int[3];
    int in_quarters;
    int in_armories;
    int garrison;
    /** The first armory's resources and weapons. */
    final int @NonNull [] stock = new int[RESOURCES];
    final int @NonNull [] weapons = new int[3];
    /** All units, inside buildings included. */
    int units;
    /** The centre of the base, in cells: the first quarters, else the first armory, else the start. */
    int home_x;
    int home_y;

    int enemy_units;
    int enemy_peons;
    final int @NonNull [] enemy_warrior_kinds = new int[3];
    int enemy_chieftains;
    int enemy_quarters;
    int enemy_armories;
    int enemy_towers;
    int enemy_sites;
    int enemy_kills;
    int enemy_lost;
    /** The enemies' start, in cells (the nearest one's). */
    int enemy_home_x;
    int enemy_home_y;
    /** Enemy warriors and chieftains within {@link #THREAT_CELLS} of home, and the nearest of them. */
    int threats;
    @Nullable
    Unit nearest_threat;
    /** The cell distance from home to the nearest enemy warrior or chieftain; the map's size without one. */
    int enemy_army_distance;
    /** Own army units within {@link #THREAT_CELLS} of the enemy's start. */
    int army_at_enemy;

    Sight(@NonNull Player me) {
        Race race = me.getRace();
        int start_x = UnitGrid.toGridCoordinate(me.getStartX());
        int start_y = UnitGrid.toGridCoordinate(me.getStartY());
        home_x = start_x;
        home_y = start_y;
        units = me.getUnitCountContainer().getNumSupplies();
        // copied: an order can change the engine's set at once
        for (Selectable<?> s : new ArrayList<>(me.getUnits().getSet())) {
            if (s.isDead()) {
                continue;
            }
            if (s instanceof LandBuilding building) {
                readBuilding(building);
            } else if (s instanceof Unit unit && !unit.isMounted()) {
                readUnit(race, unit);
            }
        }
        LandBuilding centre = !quarters.isEmpty() ? quarters.getFirst() : !armories.isEmpty() ? armories.getFirst() : null;
        if (centre != null) {
            home_x = centre.getGridX();
            home_y = centre.getGridY();
        }
        int map_cells = me.getWorld().getHeightMap().getGridUnitsPerWorld();
        enemy_army_distance = map_cells;
        long best_home = Long.MAX_VALUE;
        long best_threat = Long.MAX_VALUE;
        for (Player other : me.getWorld().getPlayers()) {
            if (!me.isEnemy(other)) {
                continue;
            }
            int ex = UnitGrid.toGridCoordinate(other.getStartX());
            int ey = UnitGrid.toGridCoordinate(other.getStartY());
            long home_distance = squared(ex - start_x, ey - start_y);
            if (home_distance < best_home) {
                best_home = home_distance;
                enemy_home_x = ex;
                enemy_home_y = ey;
            }
            enemy_units += other.getUnitCountContainer().getNumSupplies();
            enemy_kills += other.getUnitsKilled();
            enemy_lost += other.getUnitsLost();
            Race other_race = other.getRace();
            for (Selectable<?> s : other.getUnits().getSet()) {
                if (s.isDead()) {
                    continue;
                }
                if (s instanceof LandBuilding building) {
                    if (!building.isComplete()) {
                        enemy_sites++;
                    } else {
                        switch (building.getTemplate().getTemplateID()) {
                            case Race.BUILDING_QUARTERS -> enemy_quarters++;
                            case Race.BUILDING_ARMORY -> enemy_armories++;
                            case Race.BUILDING_TOWER -> enemy_towers++;
                            default -> {
                            }
                        }
                    }
                } else if (s instanceof Unit unit && !unit.isMounted()) {
                    int kind = kindOf(other_race, unit);
                    if (kind == Race.UNIT_PEON) {
                        enemy_peons++;
                        continue;
                    }
                    if (kind == Race.UNIT_CHIEFTAIN) {
                        enemy_chieftains++;
                    } else {
                        enemy_warrior_kinds[kind]++;
                    }
                    long distance = squared(unit.getGridX() - home_x, unit.getGridY() - home_y);
                    if (distance <= (long) THREAT_CELLS * THREAT_CELLS) {
                        threats++;
                    }
                    if (distance < best_threat) {
                        best_threat = distance;
                        nearest_threat = unit;
                    }
                }
            }
        }
        if (nearest_threat != null) {
            enemy_army_distance = (int) Math.min(map_cells, Math.round(Math.sqrt((double) best_threat)));
        }
        for (Unit unit : army) {
            if (squared(unit.getGridX() - enemy_home_x,
                    unit.getGridY() - enemy_home_y) <= (long) THREAT_CELLS * THREAT_CELLS) {
                army_at_enemy++;
            }
        }
    }

    private void readBuilding(@NonNull LandBuilding building) {
        int kind = building.getTemplate().getTemplateID();
        if (kind > Race.BUILDING_TOWER) {
            return;
        }
        if (!building.isComplete()) {
            sites.add(building);
            site_kinds[kind]++;
            return;
        }
        switch (kind) {
            case Race.BUILDING_QUARTERS -> {
                quarters.add(building);
                in_quarters += building.getUnitCount();
            }
            case Race.BUILDING_ARMORY -> {
                if (armories.isEmpty()) {
                    for (int i = 0; i < RESOURCES; i++) {
                        stock[i] = supplies(building, SUPPLIES.get(i));
                    }
                    for (int i = 0; i < weapons.length; i++) {
                        weapons[i] = supplies(building, WEAPONS.get(i));
                    }
                }
                armories.add(building);
                in_armories += building.getUnitCount();
            }
            default -> {
                towers.add(building);
                garrison += building.getUnitCount();
            }
        }
    }

    private void readUnit(@NonNull Race race, @NonNull Unit unit) {
        int kind = kindOf(race, unit);
        Controller doing = unit.getPrimaryController();
        if (kind == Race.UNIT_PEON) {
            if (doing instanceof IdleController) {
                idle_peons.add(unit);
            } else if (doing instanceof GatherController<?> gather) {
                int resource = SUPPLIES.indexOf(gather.getSupplyType());
                if (resource >= 0) {
                    gatherers[resource]++;
                }
            } else if (doing instanceof PlaceBuildingController || doing instanceof RepairController) {
                builders.add(unit);
            } else {
                busy_peons++;
            }
            return;
        }
        if (kind == Race.UNIT_CHIEFTAIN) {
            chieftain = unit;
        } else {
            warrior_kinds[kind]++;
        }
        army.add(unit);
        if (doing instanceof IdleController) {
            idle_army.add(unit);
        }
    }

    /** The unit's kind, Race.UNIT_*; a unit of no known template counts as a peon. */
    static int kindOf(@NonNull Race race, @NonNull Unit unit) {
        for (int kind = 0; kind < Race.NUM_UNITS; kind++) {
            if (unit.getTemplate() == race.getUnitTemplate(kind)) {
                return kind;
            }
        }
        return Race.UNIT_PEON;
    }

    private static int supplies(@NonNull LandBuilding building, @NonNull Class<?> type) {
        SupplyContainer container = building.getSupplyContainer(type);
        return container == null ? 0 : container.getNumSupplies();
    }

    static long squared(int dx, int dy) {
        return (long) dx * dx + (long) dy * dy;
    }

    int warriors() {
        return warrior_kinds[0] + warrior_kinds[1] + warrior_kinds[2];
    }

    int enemyWarriors() {
        return enemy_warrior_kinds[0] + enemy_warrior_kinds[1] + enemy_warrior_kinds[2];
    }

    int gathererCount() {
        return gatherers[0] + gatherers[1] + gatherers[2] + gatherers[3];
    }

    /** Finished buildings and sites of {@code kind} (Race.BUILDING_*). */
    int buildings(int kind) {
        int built = switch (kind) {
            case Race.BUILDING_QUARTERS -> quarters.size();
            case Race.BUILDING_ARMORY -> armories.size();
            default -> towers.size();
        };
        return built + site_kinds[kind];
    }

    /** The supply class of resource {@code index}, for recalling its gatherers. */
    @SuppressWarnings("unchecked")
    static @NonNull Class<? extends com.oddlabs.tt.model.Supply> supplyClass(int index) {
        return (Class<? extends com.oddlabs.tt.model.Supply>) SUPPLIES.get(index);
    }
}
