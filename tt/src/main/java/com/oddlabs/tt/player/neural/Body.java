package com.oddlabs.tt.player.neural;

import com.oddlabs.tt.aikit.AiLog;
import com.oddlabs.tt.landscape.LandscapeTarget;
import com.oddlabs.tt.model.Action;
import com.oddlabs.tt.model.BuildingTemplate;
import com.oddlabs.tt.model.DeployType;
import com.oddlabs.tt.model.LandBuilding;
import com.oddlabs.tt.model.Race;
import com.oddlabs.tt.model.RacesResources;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.pathfinder.UnitGrid;
import com.oddlabs.tt.player.BuildingSiteScanFilter;
import com.oddlabs.tt.player.Player;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * The skill layer: it carries out, once a game second, what the policy last chose ({@link Heads}), with plain rules
 * for everything below that level. It gives orders only through the player's own order methods. The first quarters
 * and the first armory are built without asking the policy: a game without them is lost at once, and a policy that
 * must first learn that learns nothing else.
 */
final class Body {
    /** Builders a new building gets; fewer start it when no more are at hand. */
    private static final int BUILDERS = 10;
    /** A building that found no site or no builder for this long, in seconds, is given up. */
    private static final int BUILD_PATIENCE = 30;
    /** How far from the base's centre, in cells, a site may be. */
    private static final int SITE_RANGE = 40;
    /** How far from the centre towards the enemy, in cells, a tower is aimed at. */
    private static final int TOWER_REACH = 14;
    private static final int MAX_QUARTERS = 5;
    private static final int MAX_ARMORIES = 1;
    private static final int MAX_TOWERS = 6;
    /** Peons the armory keeps for making weapons whatever else is asked of it. */
    private static final int ARMORY_KEEP = 3;
    /** Peons a quarters keeps for breeding. */
    private static final int QUARTERS_KEEP = 2;
    /** A building needs this many peons at hand (idle outside or inside a building) to be asked for. */
    private static final int BUILD_MIN_PEONS = 6;
    /**
     * How often, in seconds, a new choice of weapon or about the chieftain takes effect: a quarters or an armory that
     * is told something else every few seconds starts over each time and never finishes anything.
     */
    private static final int WEAPON_SECONDS = 30;
    private static final int CHIEFTAIN_SECONDS = 60;
    /** The most peons moved or deployed by one rule in one second. */
    private static final int MOVE_AT_ONCE = 6;
    /** Idle warriors farther than this from home, in cells, walk back when the army defends or retreats. */
    private static final int HOME_CELLS = 18;
    /** Enemies within this many cells of the chieftain before he casts. */
    private static final int CAST_CELLS = 9;
    private static final int CAST_ENEMIES = 4;
    private static final DeployType[] HARVEST = {DeployType.PEON_HARVEST_TREE, DeployType.PEON_HARVEST_ROCK, DeployType.PEON_HARVEST_IRON, DeployType.PEON_HARVEST_RUBBER};
    private static final DeployType[] WARRIOR = {DeployType.ROCK_WARRIOR, DeployType.IRON_WARRIOR, DeployType.RUBBER_WARRIOR};

    private final @NonNull Player me;
    private final @NonNull AiLog log;
    private final @NonNull UnitGrid grid;
    /** The policy's current choices, one per head. */
    private final int @NonNull [] plan = new int[Heads.COUNT];
    /** The building waiting for builders or a site, Heads.BUILD_*; BUILD_NONE when none is. */
    private int pending_build = Heads.BUILD_NONE;
    private int pending_since;
    /** Whether the pending building's builders were already called out of a quarters. */
    private boolean builders_called;
    private int weapon_ordered = -1;
    private boolean chieftain_wanted;
    private int last_posture = -1;

    Body(@NonNull Player me, @NonNull AiLog log) {
        this.me = me;
        this.log = log;
        this.grid = me.getWorld().getUnitGrid();
        plan[Heads.GATHER_LEVEL] = 1;
        plan[Heads.ARMORY_SHARE] = 1;
    }

    boolean hasPendingBuild() {
        return pending_build != Heads.BUILD_NONE;
    }

    /** Which choices of each head can be carried out now; the policy chooses among these only. */
    boolean @NonNull [] allowed(@NonNull Sight see) {
        boolean[] ok = new boolean[Heads.LOGITS];
        java.util.Arrays.fill(ok, true);
        boolean can_build = pending_build == Heads.BUILD_NONE && see.sites.isEmpty() && !see.quarters.isEmpty()
                && see.idle_peons.size() + see.in_quarters + see.in_armories >= BUILD_MIN_PEONS
                && me.getBuildingCountContainer().getNumSupplies() < me.getWorld().getMaxBuildingCount();
        int build = Heads.offset(Heads.BUILD);
        ok[build + Heads.BUILD_QUARTERS] = can_build && see.buildings(Race.BUILDING_QUARTERS) < MAX_QUARTERS;
        ok[build + Heads.BUILD_ARMORY] = can_build && see.buildings(Race.BUILDING_ARMORY) < MAX_ARMORIES;
        ok[build + Heads.BUILD_TOWER] = can_build && !see.armories.isEmpty() && see.buildings(
                Race.BUILDING_TOWER) < MAX_TOWERS;
        ok[Heads.offset(Heads.CHIEF) + 1] = !see.quarters.isEmpty() || see.chieftain != null;
        return ok;
    }

    /** Takes the policy's choice as the plan from now on. */
    void decide(int @NonNull [] action, int second) {
        System.arraycopy(action, 0, plan, 0, plan.length);
        if (action[Heads.BUILD] != Heads.BUILD_NONE && pending_build == Heads.BUILD_NONE) {
            want(action[Heads.BUILD], second);
        }
    }

    private void want(int build, int second) {
        pending_build = build;
        pending_since = second;
        builders_called = false;
    }

    int pendingBuild() {
        return pending_build;
    }

    /** One game second of carrying out the plan. */
    void act(@NonNull Sight see, int second) {
        bootstrap(see, second);
        build(see, second);
        staff(see);
        gather(see);
        arm(see, second);
        chieftain(see, second);
        fight(see);
    }

    // ---------------------------------------------------------------- building

    private void bootstrap(@NonNull Sight see, int second) {
        if (pending_build != Heads.BUILD_NONE) {
            return;
        }
        if (see.buildings(Race.BUILDING_QUARTERS) == 0) {
            want(Heads.BUILD_QUARTERS, second);
        } else if (!see.quarters.isEmpty() && see.buildings(Race.BUILDING_ARMORY) == 0) {
            want(Heads.BUILD_ARMORY, second);
        }
    }

    private void build(@NonNull Sight see, int second) {
        if (!see.sites.isEmpty()) {
            // idle peons outside help the site along, up to a full crew; the rest go back inside (staff)
            int helpers = Math.min(see.idle_peons.size(), BUILDERS - see.builders.size());
            if (helpers > 0) {
                List<Unit> crew = see.idle_peons.subList(0, helpers);
                me.setTarget(crew.toArray(Selectable.newArray(0)), see.sites.getFirst(), Action.DEFAULT, false);
                crew.clear();
            }
            if (pending_build != Heads.BUILD_NONE && see.site_kinds[pending_build - 1] > 0) {
                pending_build = Heads.BUILD_NONE;
            }
            return;
        }
        if (pending_build == Heads.BUILD_NONE) {
            return;
        }
        if (second - pending_since > BUILD_PATIENCE) {
            log.count("build_given_up");
            pending_build = Heads.BUILD_NONE;
            return;
        }
        int have = see.idle_peons.size();
        LandBuilding source = fullest(see.quarters);
        int keep = QUARTERS_KEEP;
        if ((source == null || source.getUnitCount() <= keep) && !see.armories.isEmpty()) {
            source = see.armories.getFirst();
            keep = ARMORY_KEEP;
        }
        if (have < BUILDERS && !builders_called && source != null && source.getUnitCount() > keep) {
            // once: they leave one by one, and asking again each second would empty the building
            me.deployUnits(source, DeployType.PEON, Math.min(BUILDERS - have, source.getUnitCount() - keep));
            builders_called = true;
            pending_since = second;
            return;
        }
        boolean waiting = builders_called && have < BUILDERS && second - pending_since < BUILDERS;
        if (have == 0 || waiting) {
            return;
        }
        int kind = pending_build - 1; // Heads.BUILD_* are Race.BUILDING_* + 1
        int at_x = see.home_x;
        int at_y = see.home_y;
        if (kind == Race.BUILDING_TOWER) {
            double dx = see.enemy_home_x - see.home_x;
            double dy = see.enemy_home_y - see.home_y;
            double length = Math.max(1, Math.sqrt(dx * dx + dy * dy));
            at_x += (int) Math.round(dx / length * TOWER_REACH);
            at_y += (int) Math.round(dy / length * TOWER_REACH);
        }
        BuildingTemplate template = me.getRace().getBuildingTemplate(kind);
        BuildingSiteScanFilter sites = new BuildingSiteScanFilter(grid, template, SITE_RANGE, true);
        grid.scan(sites, at_x, at_y);
        if (sites.getResult().isEmpty()) {
            return; // tried again next second, until patience runs out
        }
        LandscapeTarget site = sites.getResult().getFirst();
        List<Unit> builders = see.idle_peons.subList(0, Math.min(have, BUILDERS));
        me.placeBuilding(builders.toArray(Selectable.newArray(0)), kind, site.getGridX(), site.getGridY());
        log.count("placed_" + kind);
        builders.clear();
        pending_build = Heads.BUILD_NONE;
    }

    // ---------------------------------------------------------------- peons inside

    /** Idle peons go inside, and the peons inside are split between breeding and the armory as the plan says. */
    private void staff(@NonNull Sight see) {
        LandBuilding armory = see.armories.isEmpty() ? null : see.armories.getFirst();
        LandBuilding emptiest = emptiest(see.quarters);
        int inside = see.in_quarters + see.in_armories;
        int armory_goal = armory == null ? 0 : Math.round(Heads.armoryShare(plan[Heads.ARMORY_SHARE]) * inside);
        // the idle wait outside for a pending building: they are its builders
        if (!see.idle_peons.isEmpty() && pending_build == Heads.BUILD_NONE) {
            LandBuilding into = armory != null && (see.in_armories < armory_goal
                    || emptiest == null) ? armory : emptiest;
            if (into != null && !into.isDead()) {
                // MOVE walks in; DEFAULT would repair a building that is not at full strength instead
                me.setTarget(see.idle_peons.toArray(Selectable.newArray(0)), into, Action.MOVE, false);
                see.idle_peons.clear();
            }
        }
        if (armory == null) {
            return;
        }
        LandBuilding fullest = fullest(see.quarters);
        if (see.in_armories < armory_goal - 1 && fullest != null && fullest.getUnitCount() > QUARTERS_KEEP) {
            int move = Math.min(MOVE_AT_ONCE, Math.min(armory_goal - see.in_armories,
                    fullest.getUnitCount() - QUARTERS_KEEP));
            me.setRallyPoint(fullest, armory);
            me.deployUnits(fullest, DeployType.PEON, move);
        } else if (see.in_armories > Math.max(armory_goal, ARMORY_KEEP) + 2 && emptiest != null) {
            // out of the armory as plain peons: idle outside next second, they walk into a quarters
            me.deployUnits(armory, DeployType.PEON, Math.min(MOVE_AT_ONCE, see.in_armories - Math.max(armory_goal,
                    ARMORY_KEEP)));
        }
    }

    private void gather(@NonNull Sight see) {
        if (see.armories.isEmpty()) {
            return;
        }
        LandBuilding armory = see.armories.getFirst();
        int spare = armory.getUnitCount() - ARMORY_KEEP;
        int peons = see.units - see.warriors() - see.garrison - (see.chieftain != null ? 1 : 0);
        for (int resource = 0; resource < Sight.RESOURCES; resource++) {
            int goal = Heads.gatherers(plan[Heads.GATHER_MIX], plan[Heads.GATHER_LEVEL], resource, peons);
            int have = see.gatherers[resource];
            if (have < goal && spare > 0) {
                int send = Math.min(MOVE_AT_ONCE, Math.min(goal - have, spare));
                me.deployUnits(armory, HARVEST[resource], send);
                spare -= send;
            } else if (have > goal + 2) {
                me.recallGatherers(armory, Sight.supplyClass(resource), Math.min(MOVE_AT_ONCE, have - goal));
            }
        }
    }

    // ---------------------------------------------------------------- weapons and warriors

    private void arm(@NonNull Sight see, int second) {
        if (see.armories.isEmpty()) {
            weapon_ordered = -1;
            return;
        }
        LandBuilding armory = see.armories.getFirst();
        int weapon = plan[Heads.WEAPON];
        if (weapon != weapon_ordered && (weapon_ordered < 0 || second % WEAPON_SECONDS == 0)) {
            me.buildRockWeapons(armory, 0, false);
            me.buildIronWeapons(armory, 0, false);
            me.buildRubberWeapons(armory, 0, false);
            switch (weapon) {
                case Race.UNIT_WARRIOR_ROCK -> me.buildRockWeapons(armory, 1, true);
                case Race.UNIT_WARRIOR_IRON -> me.buildIronWeapons(armory, 1, true);
                default -> me.buildRubberWeapons(armory, 1, true);
            }
            weapon_ordered = weapon;
        }
        int goal = Math.round(Heads.armyShare(plan[Heads.ARMY]) * see.units);
        int want = goal - see.warriors() - see.garrison;
        int staff = armory.getUnitCount() - ARMORY_KEEP;
        // the strongest weapon in stock first
        for (int kind = Race.UNIT_WARRIOR_RUBBER; kind >= Race.UNIT_WARRIOR_ROCK && want > 0 && staff > 0; kind--) {
            int deploy = Math.min(Math.min(want, staff), Math.min(see.weapons[kind], MOVE_AT_ONCE));
            if (deploy > 0) {
                me.deployUnits(armory, WARRIOR[kind], deploy);
                want -= deploy;
                staff -= deploy;
            }
        }
    }

    private void chieftain(@NonNull Sight see, int second) {
        if (second % CHIEFTAIN_SECONDS == 0) {
            chieftain_wanted = plan[Heads.CHIEF] == 1;
        }
        if (see.chieftain == null && !see.quarters.isEmpty() && chieftain_wanted != me.isTrainingChieftain()) {
            me.trainChieftain(see.quarters.getFirst(), chieftain_wanted);
        }
        Unit chieftain = see.chieftain;
        if (chieftain == null || see.nearest_threat == null) {
            return;
        }
        int near = 0;
        for (Player other : me.getWorld().getPlayers()) {
            if (!me.isEnemy(other)) {
                continue;
            }
            for (Selectable<?> s : other.getUnits().getSet()) {
                if (s instanceof Unit && !s.isDead() && Sight.squared(s.getGridX() - chieftain.getGridX(),
                        s.getGridY() - chieftain.getGridY()) <= CAST_CELLS * CAST_CELLS) {
                    near++;
                }
            }
        }
        if (near < CAST_ENEMIES) {
            return;
        }
        // both races: magic 0 holds or poisons a crowd, magic 1 strikes it
        if (chieftain.canDoMagic(RacesResources.INDEX_MAGIC_STUN)) {
            me.doMagic(chieftain, RacesResources.INDEX_MAGIC_STUN);
            log.count("cast");
        } else if (chieftain.canDoMagic(RacesResources.INDEX_MAGIC_BLAST)) {
            me.doMagic(chieftain, RacesResources.INDEX_MAGIC_BLAST);
            log.count("cast");
        }
    }

    // ---------------------------------------------------------------- the army

    private void fight(@NonNull Sight see) {
        int posture = plan[Heads.POSTURE];
        // a new posture orders everyone; after that only the idle are sent again
        List<Unit> units = posture != last_posture ? see.army : see.idle_army;
        last_posture = posture;
        if (posture != Heads.POSTURE_ATTACK) {
            garrisonTowers(see);
        }
        if (units.isEmpty()) {
            return;
        }
        Selectable<?>[] selection = units.toArray(Selectable.newArray(0));
        switch (posture) {
            case Heads.POSTURE_ATTACK -> {
                int from_x = units.getFirst().getGridX();
                int from_y = units.getFirst().getGridY();
                Selectable<?> target = me.findNearestEnemyBuilding(from_x, from_y);
                if (target == null) {
                    target = me.findNearestEnemy(from_x, from_y);
                }
                if (target != null && !target.isDead()) {
                    me.setTarget(selection, target, Action.ATTACK, true);
                }
            }
            case Heads.POSTURE_DEFEND -> {
                Unit threat = see.threats > 0 ? see.nearest_threat : null;
                if (threat != null && !threat.isDead()) {
                    me.setTarget(selection, threat, Action.ATTACK, true);
                } else {
                    walkHome(see, units, true);
                }
            }
            default -> walkHome(see, units, false);
        }
    }

    /** Sends the units of {@code units} that strayed from home back to it. */
    private void walkHome(@NonNull Sight see, @NonNull List<Unit> units, boolean aggressive) {
        List<Unit> far = units.stream().filter(unit -> Sight.squared(unit.getGridX() - see.home_x,
                unit.getGridY() - see.home_y) > HOME_CELLS * HOME_CELLS).toList();
        if (!far.isEmpty()) {
            me.setLandscapeTarget(far.toArray(Selectable.newArray(0)), see.home_x, see.home_y, Action.MOVE,
                    aggressive);
        }
    }

    /** An idle warrior walks into each empty tower. */
    private void garrisonTowers(@NonNull Sight see) {
        for (LandBuilding tower : see.towers) {
            if (tower.getUnitCount() > 0 || see.idle_army.isEmpty()) {
                continue;
            }
            Unit warrior = null;
            for (Unit unit : see.idle_army) {
                if (unit != see.chieftain) {
                    warrior = unit;
                    break;
                }
            }
            if (warrior == null) {
                return;
            }
            me.setTarget(Selectable.newArray(warrior), tower, Action.DEFAULT, false);
            see.idle_army.remove(warrior);
            see.army.remove(warrior);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static @Nullable LandBuilding fullest(@NonNull List<LandBuilding> buildings) {
        LandBuilding best = null;
        for (LandBuilding building : buildings) {
            if (best == null || building.getUnitCount() > best.getUnitCount()) {
                best = building;
            }
        }
        return best;
    }

    private static @Nullable LandBuilding emptiest(@NonNull List<LandBuilding> buildings) {
        LandBuilding best = null;
        for (LandBuilding building : buildings) {
            if (best == null || building.getUnitCount() < best.getUnitCount()) {
                best = building;
            }
        }
        return best;
    }
}
