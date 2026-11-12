package com.oddlabs.tt.player.neural;

import com.oddlabs.tt.aikit.AiLog;
import com.oddlabs.tt.aikit.AiParams;
import com.oddlabs.tt.landscape.LandscapeTarget;
import com.oddlabs.tt.model.Action;
import com.oddlabs.tt.model.BuildingTemplate;
import com.oddlabs.tt.model.DeployType;
import com.oddlabs.tt.model.LandBuilding;
import com.oddlabs.tt.model.Race;
import com.oddlabs.tt.model.RacesResources;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.model.behaviour.IdleController;
import com.oddlabs.tt.pathfinder.UnitGrid;
import com.oddlabs.tt.player.BuildingSiteScanFilter;
import com.oddlabs.tt.player.Player;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The skill layer: it carries out, once a game second, what the policy last chose ({@link Heads}), with plain rules
 * for everything below that level. It gives orders only through the player's own order methods. The first quarters
 * and the first armory are built without asking the policy: a game without them is lost at once, and a policy that
 * must first learn that learns nothing else.
 *
 * <p>Where the peons are is the policy's: every quarters keeps as many inside as {@link Heads#QUARTERS_KEEP} says and
 * sends the rest to the armory; the armory sends out as many gatherers of each resource as its target says (the
 * gather heads move the targets up and down), keeps as many at work on weapons as {@link Heads#ARMORY_KEEP} says,
 * and arms the rest as fast as it has weapons.
 */
final class Body {
    /** A building that found no site or no builder for this long, in seconds, is given up. */
    private static final int BUILD_PATIENCE = 30;
    /** How long, in seconds, builders that were called out of buildings are waited for. */
    private static final int CALL_SECONDS = 6;
    /** How far from the base's centre, in cells, a site may be. */
    private static final int SITE_RANGE = 40;
    /** How far from the centre towards the enemy, in cells, a tower is aimed at. */
    private static final int TOWER_REACH = 14;
    static final int MAX_QUARTERS = 8;
    static final int MAX_ARMORIES = 1;
    static final int MAX_TOWERS = 8;
    /** Peons the armory keeps whatever else is asked of it: gatherers leave it down to this. */
    private static final int ARMORY_FLOOR = 1;
    /** Iron weapons' worth of wood and iron in stock from which the forge crew is kept before gatherers. */
    private static final int FORGE_STOCK = 2;
    /** Peons a building keeps when builders are called out of it. */
    private static final int BUILDERS_LEAVE = 1;
    /** A building needs this many peons at hand (idle outside or inside a building) to be asked for. */
    private static final int BUILD_MIN_PEONS = 6;
    /** The most peons moved or deployed by one rule in one second. */
    private static final int MOVE_AT_ONCE = 6;
    /**
     * Builders whose building is finished stay outside this long, in seconds, for the next building to be asked for.
     */
    private static final int HOLD_SECONDS = 6;
    /** Idle warriors farther than this from home, in cells, walk back when the army defends or retreats. */
    private static final int HOME_CELLS = 18;
    /** Attackers go for enemy warriors within this many cells before they go on to the buildings. */
    private static final int ENGAGE_CELLS = 10;
    /** Enemies within this many cells of the chieftain before he casts. */
    private static final int CAST_CELLS = 9;
    private static final int CAST_ENEMIES = 4;
    private static final DeployType[] HARVEST = {DeployType.PEON_HARVEST_TREE, DeployType.PEON_HARVEST_ROCK, DeployType.PEON_HARVEST_IRON, DeployType.PEON_HARVEST_RUBBER};
    private static final DeployType[] WARRIOR = {DeployType.ROCK_WARRIOR, DeployType.IRON_WARRIOR, DeployType.RUBBER_WARRIOR};

    private final @NonNull Player me;
    private final @NonNull AiLog log;
    private final @NonNull UnitGrid grid;
    /** Builders a new building gets; fewer start it when no more are at hand. */
    private final int crew;
    /** The builders of each building until the first armory stands. */
    private final int first_crew;
    /** Buildings under construction at a time. */
    private final int max_sites;
    /** Warriors that join an attack under way leave home in groups of this many: one by one they die one by one. */
    private final int reinforcements;
    /** The policy's current choices, one per head. */
    private final int @NonNull [] plan = new int[Heads.COUNT];
    /** The game second from which each head may choose anew. */
    private final int @NonNull [] free_from = new int[Heads.COUNT];
    /** The gatherers wanted on wood, rock, iron and rubber. */
    private final int @NonNull [] gather_targets = new int[Sight.RESOURCES];
    /** The building waiting for builders or a site, Heads.BUILD_*; BUILD_NONE when none is. */
    private int pending_build = Heads.BUILD_NONE;
    private int pending_since;
    /** Whether the pending building's builders were already called out of the buildings. */
    private boolean builders_called;
    private int sites_seen;
    /** Until this game second idle peons stay outside. */
    private int hold_until;
    private int weapon_ordered = -1;
    private int last_posture = -1;
    private boolean had_threat;

    Body(@NonNull Player me, @NonNull AiLog log, @NonNull AiParams params) {
        this.me = me;
        this.log = log;
        crew = params.getInt("b_crew", 10);
        first_crew = params.getInt("b_first", 20);
        max_sites = params.getInt("b_sites", 2);
        reinforcements = params.getInt("b_reinforce", 12);
        this.grid = me.getWorld().getUnitGrid();
        plan[Heads.QUARTERS_KEEP] = 2;
        plan[Heads.ARMORY_KEEP] = 1;
        for (int resource = 0; resource < Sight.RESOURCES; resource++) {
            plan[Heads.gatherHead(resource)] = Heads.GATHER_SAME;
            gather_targets[resource] = Heads.firstGatherers(resource);
        }
        plan[Heads.WEAPON] = Heads.WEAPONS_IRON_RUBBER;
    }

    boolean hasPendingBuild() {
        return pending_build != Heads.BUILD_NONE;
    }

    /** The policy's current choices: what each head chose last. */
    int @NonNull [] plan() {
        return plan;
    }

    /** Which choices of each head can be made now; the policy chooses among these only. */
    boolean @NonNull [] allowed(@NonNull Sight see, int second) {
        boolean[] ok = new boolean[Heads.LOGITS];
        Arrays.fill(ok, true);
        for (int head = 0; head < Heads.COUNT; head++) {
            if (second < free_from[head]) {
                // between two of its decisions a head keeps its choice
                int offset = Heads.offset(head);
                Arrays.fill(ok, offset, offset + Heads.size(head), false);
                ok[offset + plan[head]] = true;
            }
        }
        boolean can_build = pending_build == Heads.BUILD_NONE && see.siteCount() < max_sites
                && see.idle_peons.size() + see.in_quarters + see.in_armories >= BUILD_MIN_PEONS
                && me.getBuildingCountContainer().getNumSupplies() < me.getWorld().getMaxBuildingCount();
        int build = Heads.offset(Heads.BUILD);
        ok[build + Heads.BUILD_NONE] = true;
        ok[build + Heads.BUILD_QUARTERS] = can_build && see.buildings(Race.BUILDING_QUARTERS) < MAX_QUARTERS;
        ok[build + Heads.BUILD_ARMORY] = can_build && see.buildings(Race.BUILDING_ARMORY) < MAX_ARMORIES;
        ok[build + Heads.BUILD_TOWER] = can_build && !see.armories.isEmpty() && see.buildings(
                Race.BUILDING_TOWER) < MAX_TOWERS;
        if (second >= free_from[Heads.CHIEF]) {
            ok[Heads.offset(Heads.CHIEF) + 1] = !see.quarters.isEmpty() || see.chieftain != null;
        }
        return ok;
    }

    /** Takes the policy's choice, made among {@link #allowed} at the same second, as the plan from now on. */
    void decide(int @NonNull [] action, int second) {
        for (int head = 0; head < Heads.COUNT; head++) {
            if (second >= free_from[head]) {
                free_from[head] = second + Heads.period(head);
                if (head >= Heads.GATHER_WOOD && head <= Heads.GATHER_RUBBER) {
                    // a step, taken once per choice: in between the head only repeats it
                    int resource = head - Heads.GATHER_WOOD;
                    gather_targets[resource] = Math.clamp(gather_targets[resource] + Heads.gatherStep(action[head]),
                            0, Heads.MAX_GATHERERS);
                }
            }
        }
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

    /** The gatherers wanted on wood, rock, iron and rubber. */
    int @NonNull [] gatherTargets() {
        return gather_targets;
    }

    /** One game second of carrying out the plan. */
    void act(@NonNull Sight see, int second) {
        bootstrap(see, second);
        build(see, second);
        staff(see, second);
        gather(see);
        arm(see);
        chieftain(see);
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
        if (see.siteCount() < sites_seen) {
            hold_until = second + HOLD_SECONDS; // a building is finished: its builders may be the next one's
        }
        sites_seen = see.siteCount();
        // the opening: until the armory stands there is nothing else for a peon to do
        int wanted = see.armories.isEmpty() ? first_crew : crew;
        if (pending_build == Heads.BUILD_NONE) {
            // idle peons outside help the sites along, up to a full crew each (a builder is idle for a moment
            // whenever it has used up its wood); the rest go inside (staff)
            for (LandBuilding site : see.sites) {
                int helpers = Math.min(see.idle_peons.size(), wanted - see.buildersOf(site));
                if (helpers > 0) {
                    List<Unit> more = see.idle_peons.subList(0, helpers);
                    me.setTarget(more.toArray(Selectable.newArray(0)), site, Action.DEFAULT, false);
                    more.clear();
                }
            }
            return;
        }
        if (second - pending_since > BUILD_PATIENCE) {
            log.count("build_given_up");
            pending_build = Heads.BUILD_NONE;
            return;
        }
        int have = see.idle_peons.size();
        if (have < wanted && !builders_called) {
            // once: they leave one by one, and asking again each second would empty the buildings
            builders_called = true;
            if (callBuilders(see, wanted - have) > 0) {
                pending_since = second;
                return;
            }
        }
        boolean waiting = builders_called && have < wanted && second - pending_since < CALL_SECONDS;
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
        List<Unit> builders = see.idle_peons.subList(0, Math.min(have, wanted));
        me.placeBuilding(builders.toArray(Selectable.newArray(0)), kind, site.getGridX(), site.getGridY());
        log.count("placed_" + kind);
        builders.clear();
        pending_build = Heads.BUILD_NONE;
        sites_seen++; // the site is carried from now on
    }

    /** Calls up to {@code need} peons out of the quarters and the armory to stand idle outside; returns how many. */
    private int callBuilders(@NonNull Sight see, int need) {
        int called = 0;
        for (LandBuilding quarters : see.quarters) {
            int take = Math.min(need - called, quarters.getUnitCount() - BUILDERS_LEAVE);
            if (take > 0) {
                // without a rally point of its own a quarters sends its peons into the armory
                me.setRallyPoint(quarters, quarters.getGridX(), quarters.getGridY());
                me.deployUnits(quarters, DeployType.PEON, take);
                called += take;
            }
        }
        if (called < need && !see.armories.isEmpty()) {
            LandBuilding armory = see.armories.getFirst();
            int take = Math.min(need - called, armory.getUnitCount() - BUILDERS_LEAVE);
            if (take > 0) {
                me.deployUnits(armory, DeployType.PEON, take);
                called += take;
            }
        }
        return called;
    }

    // ---------------------------------------------------------------- peons inside

    /** Idle peons go inside, and every quarters sends the peons above its keep to the armory. */
    private void staff(@NonNull Sight see, int second) {
        if (pending_build != Heads.BUILD_NONE) {
            return; // the idle are its builders, and those called out of the quarters must not walk into the armory
        }
        LandBuilding armory = see.armories.isEmpty() ? null : see.armories.getFirst();
        int keep = Heads.quartersKeep(plan[Heads.QUARTERS_KEEP]);
        if (!see.idle_peons.isEmpty() && second >= hold_until) {
            LandBuilding emptiest = emptiest(see.quarters);
            LandBuilding into = emptiest != null && (armory == null
                    || emptiest.getUnitCount() < keep) ? emptiest : armory;
            if (into != null && !into.isDead()) {
                // MOVE walks in; DEFAULT would repair a building that is not at full strength instead
                me.setTarget(see.idle_peons.toArray(Selectable.newArray(0)), into, Action.MOVE, false);
                see.idle_peons.clear();
            }
        }
        if (armory == null) {
            return;
        }
        for (LandBuilding quarters : see.quarters) {
            int leave = Math.min(MOVE_AT_ONCE, quarters.getUnitCount() - keep);
            if (leave > 0) {
                me.setRallyPoint(quarters, armory);
                me.deployUnits(quarters, DeployType.PEON, leave);
            }
        }
    }

    private void gather(@NonNull Sight see) {
        if (see.armories.isEmpty()) {
            return;
        }
        LandBuilding armory = see.armories.getFirst();
        // the forge crew stays first once there is something to forge: gatherers who leave it idle pile up stock
        // nobody turns into weapons
        boolean forging = Math.min(see.stock[0] / 2, see.stock[2]) >= FORGE_STOCK;
        int spare = armory.getUnitCount() - (forging ? Math.max(ARMORY_FLOOR, Heads.armoryKeep(
                plan[Heads.ARMORY_KEEP])) : ARMORY_FLOOR);
        for (int resource = 0; resource < Sight.RESOURCES; resource++) {
            int goal = gather_targets[resource];
            // those told to leave count: they go one by one
            int have = see.gatherers[resource] + armory.getDeployContainer(HARVEST[resource]).getNumSupplies();
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

    private void arm(@NonNull Sight see) {
        if (see.armories.isEmpty()) {
            weapon_ordered = -1;
            return;
        }
        LandBuilding armory = see.armories.getFirst();
        int weapon = plan[Heads.WEAPON];
        if (weapon != weapon_ordered) {
            boolean iron = weapon != Heads.WEAPONS_RUBBER;
            boolean rubber = weapon != Heads.WEAPONS_IRON;
            boolean rock = weapon == Heads.WEAPONS_ALL;
            me.buildIronWeapons(armory, iron ? 1 : 0, iron);
            me.buildRubberWeapons(armory, rubber ? 1 : 0, rubber);
            me.buildRockWeapons(armory, rock ? 1 : 0, rock);
            weapon_ordered = weapon;
        }
        int spare = armory.getUnitCount() - Heads.armoryKeep(plan[Heads.ARMORY_KEEP]);
        // the strongest weapon in stock first
        for (int kind = Race.UNIT_WARRIOR_RUBBER; kind >= Race.UNIT_WARRIOR_ROCK && spare > 0; kind--) {
            int deploy = Math.min(spare, Math.min(see.weapons[kind], MOVE_AT_ONCE));
            if (deploy > 0) {
                me.deployUnits(armory, WARRIOR[kind], deploy);
                spare -= deploy;
            }
        }
    }

    private void chieftain(@NonNull Sight see) {
        boolean wanted = plan[Heads.CHIEF] == 1;
        if (see.chieftain == null && !see.quarters.isEmpty() && wanted != me.isTrainingChieftain()) {
            me.trainChieftain(see.quarters.getFirst(), wanted);
        }
        Unit chieftain = see.chieftain;
        if (chieftain == null || see.enemy_fighters.isEmpty()) {
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
        boolean changed = posture != last_posture;
        last_posture = posture;
        garrisonTowers(see);
        switch (posture) {
            case Heads.POSTURE_ATTACK -> attack(see, changed);
            case Heads.POSTURE_DEFEND -> defend(see, changed);
            default -> walkHome(see, changed ? see.army : see.idle_army, false);
        }
    }

    /**
     * The army goes for the enemy: his warriors where they stand in its way, else his nearest building, else whatever
     * he has left. A new attack orders everyone at once; after that the idle are sent on, those at home in groups.
     */
    private void attack(@NonNull Sight see, boolean changed) {
        Unit threat = see.threats > 0 ? see.nearest_threat : null;
        if (changed) {
            log.count("attacks");
            had_threat = false;
            if (threat == null) {
                send(see, see.army);
                return;
            }
        }
        List<Unit> at_home = new ArrayList<>();
        List<Unit> in_field = new ArrayList<>();
        // a threat at home: everyone there meets it, once all of them and then the idle, while the field fights on
        boolean alarm = threat != null && !threat.isDead() && !had_threat;
        had_threat = threat != null && !threat.isDead();
        for (Unit unit : alarm || changed ? see.army : see.idle_army) {
            if (atHome(see, unit)) {
                at_home.add(unit);
            } else if (unit.getPrimaryController() instanceof IdleController || changed) {
                in_field.add(unit);
            }
        }
        send(see, in_field);
        if (had_threat) {
            if (!at_home.isEmpty()) {
                me.setLandscapeTarget(at_home.toArray(Selectable.newArray(0)), threat.getGridX(), threat.getGridY(),
                        Action.ATTACK, true);
            }
        } else if (at_home.size() >= reinforcements || at_home.size() == see.army.size()) {
            send(see, at_home);
        }
    }

    /**
     * Orders {@code units} to walk at the enemy fighting, by what is near their first. They walk to a place, never
     * at a building: a unit ordered at a building razes it while warriors cut it down, and one that walks fighting
     * turns on the warriors it meets, then the peons, then the towers and buildings.
     */
    private void send(@NonNull Sight see, @NonNull List<Unit> units) {
        if (units.isEmpty()) {
            return;
        }
        Unit lead = units.getFirst();
        Selectable<?> target = nearestFighter(see, lead, ENGAGE_CELLS);
        if (target == null) {
            target = me.findNearestEnemyBuilding(lead.getGridX(), lead.getGridY());
        }
        if (target == null) {
            target = me.findNearestEnemy(lead.getGridX(), lead.getGridY());
        }
        if (target != null && !target.isDead()) {
            me.setLandscapeTarget(units.toArray(Selectable.newArray(0)), target.getGridX(), target.getGridY(),
                    Action.ATTACK, true);
        }
    }

    /** The enemy warrior or chieftain nearest {@code unit} within {@code cells}; null without one. */
    private static @Nullable Unit nearestFighter(@NonNull Sight see, @NonNull Unit unit, int cells) {
        Unit nearest = null;
        long best = (long) cells * cells;
        for (Unit fighter : see.enemy_fighters) {
            long distance = Sight.squared(fighter.getGridX() - unit.getGridX(), fighter.getGridY() - unit.getGridY());
            if (distance <= best && !fighter.isDead()) {
                best = distance;
                nearest = fighter;
            }
        }
        return nearest;
    }

    /** The army meets what threatens the base, everyone when a threat appears, and stays at home otherwise. */
    private void defend(@NonNull Sight see, boolean changed) {
        Unit threat = see.threats > 0 ? see.nearest_threat : null;
        if (threat != null && !threat.isDead()) {
            List<Unit> units = changed || !had_threat ? see.army : see.idle_army;
            had_threat = true;
            if (!units.isEmpty()) {
                me.setLandscapeTarget(units.toArray(Selectable.newArray(0)), threat.getGridX(), threat.getGridY(),
                        Action.ATTACK, true);
            }
        } else {
            had_threat = false;
            walkHome(see, changed ? see.army : see.idle_army, true);
        }
    }

    private static boolean atHome(@NonNull Sight see, @NonNull Unit unit) {
        return Sight.squared(unit.getGridX() - see.home_x, unit.getGridY() - see.home_y) <= HOME_CELLS * HOME_CELLS;
    }

    /** Sends the units of {@code units} that strayed from home back to it. */
    private void walkHome(@NonNull Sight see, @NonNull List<Unit> units, boolean aggressive) {
        List<Unit> far = units.stream().filter(unit -> !atHome(see, unit)).toList();
        if (!far.isEmpty()) {
            me.setLandscapeTarget(far.toArray(Selectable.newArray(0)), see.home_x, see.home_y, Action.MOVE,
                    aggressive);
        }
    }

    /** An idle warrior at home walks into each empty tower. */
    private void garrisonTowers(@NonNull Sight see) {
        for (LandBuilding tower : see.towers) {
            if (tower.getUnitCount() > 0) {
                continue;
            }
            Unit warrior = null;
            for (Unit unit : see.idle_army) {
                if (unit != see.chieftain && atHome(see, unit)) {
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
