package com.oddlabs.tt.mapeditor;

import com.oddlabs.tt.animation.TimerAnimation;
import com.oddlabs.tt.delegate.JumpDelegate;
import com.oddlabs.tt.form.InGameCampaignDialogForm;
import com.oddlabs.tt.gui.IconQuad;
import com.oddlabs.tt.gui.NativeCampaignIcons;
import com.oddlabs.tt.gui.Origin;
import com.oddlabs.tt.gui.VikingCampaignIcons;
import com.oddlabs.tt.landscape.LandscapeTarget;
import com.oddlabs.tt.landscape.TreeSupply;
import com.oddlabs.tt.model.Abilities;
import com.oddlabs.tt.model.Action;
import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.DeployType;
import com.oddlabs.tt.model.IronSupply;
import com.oddlabs.tt.model.Race;
import com.oddlabs.tt.model.RacesResources;
import com.oddlabs.tt.model.RockSupply;

import com.oddlabs.tt.model.RubberSupply;
import com.oddlabs.tt.model.SceneryModel;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.Ship;
import com.oddlabs.tt.model.Supply;
import com.oddlabs.tt.model.SupplyContainer;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.model.UnitTemplate;
import com.oddlabs.tt.model.behaviour.MagicController;
import com.oddlabs.tt.model.behaviour.NullController;
import com.oddlabs.tt.model.weapon.IronAxeWeapon;
import com.oddlabs.tt.model.weapon.RockAxeWeapon;
import com.oddlabs.tt.model.weapon.RubberAxeWeapon;
import com.oddlabs.tt.pathfinder.UnitGrid;
import com.oddlabs.tt.player.AI;
import com.oddlabs.tt.player.AdvancedAI;
import com.oddlabs.tt.player.PassiveAI;
import com.oddlabs.tt.player.Player;
import com.oddlabs.tt.player.campaign.CampaignState;
import com.oddlabs.tt.trigger.IntervalTrigger;
import com.oddlabs.tt.trigger.campaign.ReinforcementsTrigger;
import com.oddlabs.tt.util.Target;
import com.oddlabs.tt.util.Utils;
import com.oddlabs.tt.viewer.WorldViewer;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Random;
import java.util.ResourceBundle;

/**
 * Plays a campaign level's {@link Scenario} in a running game: puts the tribes' units and buildings on the island,
 * then watches each trigger's condition and runs its actions, as the game's own campaign islands do by hand.
 */
final class ScenarioRunner {
    /** Seconds between checks of a trigger's condition, as the game's campaign triggers use. */
    private static final float CHECK_INTERVAL = .5f;
    private static final float JUMP_METERS_PER_SECOND = 200f;
    private static final float JUMP_MAX_SECONDS = 3f;
    private static final int KILL_DAMAGE = 1_000_000;
    /** As the campaigns set their statues down. */
    private static final float STATUE_SHADOW = 2.6f;
    /** How far from where it was placed a ship is moved to find water it can lie in, in cells. */
    private static final int SHIP_SEARCH_RADIUS = 10;
    /** Tries at a free cell for each statue spawned in an area. */
    private static final int STATUE_TRIES = 30;

    /** What ends the level once a trigger decides it. */
    interface Outcome {
        void victory();

        void defeat(@NonNull String message);
    }

    private final @NonNull WorldViewer viewer;
    private final @NonNull Scenario scenario;
    private final int difficulty;
    private final @NonNull Outcome outcome;
    private final @Nullable Player @NonNull [] players = new Player[Scenario.NUM_PLAYERS];
    // The live unit or building behind each placed object id, following units that change owner.
    private final Map<Integer, Selectable<?>> objects = new HashMap<>();
    // The golden statues still standing, placed or spawned, and the placed ones by id.
    private final List<SceneryModel> statues = new ArrayList<>();
    private final Map<Integer, SceneryModel> placed_statues = new HashMap<>();
    private final Map<Integer, Watch> watches = new HashMap<>();
    private final Random random = new Random(42);
    private final @NonNull Spawns spawns;
    private @NonNull String objective;
    private boolean over;

    /**
     * @param difficulty the campaign difficulty, as {@link CampaignState} has it
     * @param spawns     where tribes' cameras start and their AIs call home, for those the level gives a spawn
     */
    ScenarioRunner(@NonNull WorldViewer viewer, @NonNull Scenario scenario, int difficulty, @NonNull Spawns spawns,
            @NonNull Outcome outcome) {
        this.viewer = viewer;
        this.scenario = scenario;
        this.spawns = spawns;
        this.difficulty = difficulty;
        this.outcome = outcome;
        this.objective = scenario.objective;
        // The game only has the players taking part, in slot order.
        Player[] world_players = viewer.getWorld().getPlayers();
        int next = 0;
        for (int i = 0; i < Scenario.NUM_PLAYERS; i++) {
            if (scenario.players[i].enabled && next < world_players.length)
                players[i] = world_players[next++];
        }
    }

    @NonNull String getObjective() {
        return objective;
    }

    /** Places the objects, points the camera at the player and starts the triggers. */
    void start() {
        Unit[] chieftains = new Unit[Scenario.NUM_PLAYERS];
        for (Scenario.Placement placement : scenario.placements) {
            if (placement.kind() == ObjectKind.STATUE) {
                SceneryModel statue = newStatue(placement.x(), placement.y(),
                        ScenarioLayer.statueVariant(placement.id()), placement.id());
                if (statue != null)
                    placed_statues.put(placement.id(), statue);
                continue;
            }
            Player player = players[placement.player()];
            if (player == null)
                continue;
            Selectable<?> object = place(player, placement, chieftains);
            if (object != null)
                objects.put(placement.id(), object);
        }
        for (int i = 0; i < Scenario.NUM_PLAYERS; i++) {
            Player player = players[i];
            if (player != null)
                placeStart(player, i);
        }
        Player local = viewer.getLocalPlayer();
        viewer.getCamera().reset(local.getStartX(), local.getStartY());
        for (Scenario.Trigger trigger : scenario.triggers) {
            if (trigger.isFor(difficulty) && trigger.active)
                activate(trigger);
        }
        new DefeatWatch(local, local.getChieftain());
    }

    private @Nullable Selectable<?> place(@NonNull Player player, Scenario.@NonNull Placement placement,
            @Nullable Unit @NonNull [] chieftains) {
        ObjectKind kind = placement.kind();
        float x = UnitGrid.coordinateFromGrid(placement.x());
        float y = UnitGrid.coordinateFromGrid(placement.y());
        if (kind.isShip())
            return placeShip(player, placement.x(), placement.y());
        if (kind.isBuilding()) {
            Building building = player.buildBuilding(kind.getBuildingType(), placement.x(), placement.y());
            if (building != null && kind.getGuardType() != -1)
                man(building, kind.getGuardType());
            return building;
        }
        if (kind == ObjectKind.CHIEFTAIN) {
            // One chieftain a tribe; the first placed leads.
            if (chieftains[placement.player()] != null)
                return null;
            Unit chieftain = newChieftain(player, x, y);
            chieftains[placement.player()] = chieftain;
            return chieftain;
        }
        if (player.getUnitCountContainer().isSupplyFull())
            return null;
        return new Unit(player, x, y, null, player.getRace().getUnitTemplate(kind.getUnitType()));
    }

    /**
     * Puts a warrior straight into a tower. Walking in from the tower's own cell, as the game's campaigns have their
     * guards do, often never gets there, leaving the guard standing about and the tower empty.
     */
    private static void man(@NonNull Building tower, int warrior_type) {
        Player owner = tower.getOwner();
        if (owner.getUnitCountContainer().isSupplyFull() || tower.getUnitContainer() == null)
            return;
        Unit guard = new Unit(owner, tower.getPositionX(), tower.getPositionY(), null,
                owner.getRace().getUnitTemplate(warrior_type));
        if (tower.getUnitContainer().canEnter(guard))
            tower.getUnitContainer().enter(guard);
        else
            guard.setTarget(tower, Action.DEFAULT, false);
    }

    /** Puts a ship on the water where it was placed, or the nearest spot a ship can lie in, ready to sail. */
    private @Nullable Ship placeShip(@NonNull Player player, int grid_x, int grid_y) {
        if (player.getBuildingCountContainer().isSupplyFull())
            return null;
        var template = player.getRace().getBuildingTemplate(Race.BUILDING_SHIP);
        UnitGrid grid = viewer.getWorld().getUnitGrid();
        for (int radius = 0; radius <= SHIP_SEARCH_RADIUS; radius++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != radius)
                        continue;
                    int x = grid_x + dx;
                    int y = grid_y + dy;
                    if (x < 0 || y < 0 || x >= grid.getGridSize() || y >= grid.getGridSize()
                            || !Ship.isPlacingLegal(grid, template, x, y))
                        continue;
                    Ship ship = new Ship(player, template, x, y);
                    ship.instantBuild();
                    return ship;
                }
            }
        }
        return null;
    }

    /** A golden statue on a cell, if the cell is free land; it stands in the way like the campaigns' statues. */
    private @Nullable SceneryModel newStatue(int grid_x, int grid_y, int variant, int id) {
        UnitGrid grid = viewer.getWorld().getUnitGrid();
        if (grid_x < 0 || grid_y < 0 || grid_x >= grid.getGridSize() || grid_y >= grid.getGridSize()
                || grid.isGridOccupied(grid_x, grid_y, UnitGrid.LAND) || grid.isWater(grid_x, grid_y)
                || grid.getRegion(grid_x, grid_y, UnitGrid.LAND) == null)
            return null;
        double angle = id * 2.399963;
        SceneryModel statue = new SceneryModel(viewer.getWorld(), UnitGrid.coordinateFromGrid(grid_x),
                UnitGrid.coordinateFromGrid(grid_y), (float) Math.cos(angle), (float) Math.sin(angle),
                viewer.getWorld().getRacesResources().getTreasures()[variant], STATUE_SHADOW, true,
                CampaignEditor.i18n("object_statue_name"));
        statues.add(statue);
        return statue;
    }

    private void removeStatue(@NonNull SceneryModel statue) {
        if (statues.remove(statue))
            statue.remove();
    }

    private static @NonNull Unit newChieftain(@NonNull Player player, float x, float y) {
        ResourceBundle bundle = ResourceBundle.getBundle(Player.class.getName());
        String name = Utils.getBundleString(bundle, player.getPlayerInfo().getRace() == RacesResources.RACE_NATIVES
                ? "native_chieftain_name" : "chieftain_name");
        Unit chieftain = new Unit(player, x, y, null, player.getRace().getUnitTemplate(Race.UNIT_CHIEFTAIN), name,
                false);
        chieftain.increaseMagicEnergy(0, 1000);
        chieftain.increaseMagicEnergy(1, 1000);
        player.setActiveChieftain(chieftain);
        return chieftain;
    }

    /**
     * Where a player's camera starts and its AI sends units home to: its spawn, else its quarters, else its chieftain,
     * else its first unit or building.
     */
    private void placeStart(@NonNull Player player, int index) {
        int[] spawn = spawns.get(index);
        if (spawn != null) {
            player.setStartX(UnitGrid.coordinateFromGrid(spawn[0]));
            player.setStartY(UnitGrid.coordinateFromGrid(spawn[1]));
            return;
        }
        Scenario.Placement first = null;
        Scenario.Placement best = null;
        for (Scenario.Placement placement : scenario.placements) {
            if (placement.player() != index || !placement.kind().hasOwner())
                continue;
            if (first == null)
                first = placement;
            if (placement.kind() == ObjectKind.QUARTERS) {
                best = placement;
                break;
            }
            if (placement.kind() == ObjectKind.CHIEFTAIN && best == null)
                best = placement;
        }
        Scenario.Placement start = best != null ? best : first;
        if (start != null) {
            player.setStartX(UnitGrid.coordinateFromGrid(start.x()));
            player.setStartY(UnitGrid.coordinateFromGrid(start.y()));
        }
    }

    // ---- Triggers ----

    private void activate(Scenario.@NonNull Trigger trigger) {
        if (over || watches.containsKey(trigger.id) || !trigger.isFor(difficulty))
            return;
        watches.put(trigger.id, new Watch(trigger));
    }

    private void deactivate(int trigger_id) {
        Watch watch = watches.remove(trigger_id);
        if (watch != null)
            watch.cancel();
    }

    /** Stops every trigger, once the level is decided. */
    private void end() {
        over = true;
        for (Watch watch : new ArrayList<>(watches.values()))
            watch.cancel();
        watches.clear();
    }

    /** Checks a trigger's condition until it holds, then runs the actions. */
    private final class Watch extends IntervalTrigger {
        private final Scenario.@NonNull Trigger trigger;
        private final @NonNull ConditionKind kind;
        // For magic: whether the spell has begun inside the area.
        private boolean casting;
        private boolean cancelled;

        Watch(Scenario.@NonNull Trigger trigger) {
            // A time condition is one check after the time, in game time so pausing and game speed count.
            super(ConditionKind.of(trigger.condition.kind) == ConditionKind.TIME_ELAPSED
                            ? Math.max(.1f, trigger.condition.get(Param.SECONDS))
                            : CHECK_INTERVAL, 0f,
                    ConditionKind.of(trigger.condition.kind) == ConditionKind.TIME_ELAPSED
                            ? viewer.getWorld().getAnimationManagerGameTime()
                            : viewer.getWorld().getAnimationManagerRealTime());
            this.trigger = trigger;
            this.kind = ConditionKind.of(trigger.condition.kind);
        }

        void cancel() {
            if (!cancelled) {
                cancelled = true;
                abort();
            }
        }

        @Override
        protected void check() {
            if (!cancelled && holds())
                triggered();
        }

        @Override
        protected void done() {
            if (cancelled || over)
                return;
            cancelled = true;
            watches.remove(trigger.id);
            new Run(trigger).next();
        }

        private boolean holds() {
            Step step = trigger.condition;
            return switch (kind) {
                case GAME_STARTED, TIME_ELAPSED -> true;
                case UNITS_IN_AREA -> {
                    Scenario.Area area = scenario.findArea(step.get(Param.AREA));
                    Player player = players[step.get(Param.PLAYER)];
                    yield area != null && player != null
                            && countUnits(player, area, filter(step)) >= Math.max(1, step.get(Param.COUNT));
                }
                case UNITS_NEAR_OBJECT -> {
                    float[] at = whereIs(step.get(Param.OBJECT));
                    Player player = players[step.get(Param.PLAYER)];
                    yield at != null && player != null && hasUnitNear(player, filter(step),
                            objects.get(step.get(Param.OBJECT)), at, step.get(Param.RADIUS));
                }
                case OBJECT_DESTROYED -> whereIs(step.get(Param.OBJECT)) == null;
                case PLAYER_ELIMINATED -> {
                    Player player = players[step.get(Param.PLAYER)];
                    yield player == null || isEliminated(player);
                }
                case ENEMIES_DEFEATED -> enemiesDefeated();
                case UNITS_BELOW -> {
                    Player player = players[step.get(Param.PLAYER)];
                    yield player == null || countUnits(player, filter(step)) < step.get(Param.COUNT);
                }
                case SUPPLIES_GATHERED -> {
                    Player player = players[step.get(Param.PLAYER)];
                    yield player != null && countSupplies(player, supplyType(step.get(Param.SUPPLY_TYPE)))
                            >= step.get(Param.COUNT);
                }
                case MAGIC_USED -> magicUsed(step);
                case STATUES_LEFT -> statuesIn(scenario.findArea(step.get(Param.AREA))).size()
                        < step.get(Param.COUNT);
                case UNITS_INSIDE -> {
                    Selectable<?> object = objects.get(step.get(Param.BUILDING));
                    yield object instanceof Building building && !building.isDead()
                            && building.getUnitContainer() != null
                            && building.getUnitContainer().getNumSupplies() >= Math.max(1, step.get(Param.COUNT));
                }
                case OBJECT_IN_AREA -> {
                    float[] at = whereIs(step.get(Param.OBJECT));
                    Scenario.Area area = scenario.findArea(step.get(Param.AREA));
                    yield at != null && area != null && area.contains(at[0], at[1]);
                }
                case UNITS_FEWER_IN_AREA -> {
                    Scenario.Area area = scenario.findArea(step.get(Param.AREA));
                    Player player = players[step.get(Param.PLAYER)];
                    yield area != null && (player == null
                            || countUnits(player, area, filter(step)) < Math.max(1, step.get(Param.COUNT)));
                }
            };
        }

        /** The player's chieftain has cast the spell inside the area and finished it, as MagicUsedTrigger. */
        private boolean magicUsed(@NonNull Step step) {
            Unit chieftain = viewer.getLocalPlayer().getChieftain();
            Scenario.Area area = scenario.findArea(step.get(Param.AREA));
            if (chieftain == null || chieftain.isDead() || area == null
                    || !area.contains(chieftain.getPositionX(), chieftain.getPositionY()))
                return false;
            boolean magic = chieftain.getPrimaryController() instanceof MagicController;
            if (!casting && magic && chieftain.getLastMagicIndex() == step.get(Param.MAGIC))
                casting = true;
            return casting && !magic;
        }
    }

    /** Runs a trigger's actions one after another; some wait before the next. */
    private final class Run {
        private final Scenario.@NonNull Trigger trigger;
        private int index;

        Run(Scenario.@NonNull Trigger trigger) {
            this.trigger = trigger;
        }

        void next() {
            while (!over && index < trigger.actions.size()) {
                Step step = trigger.actions.get(index++);
                if (perform(step, this::next))
                    return;
            }
            if (!over && trigger.repeat)
                activate(trigger);
        }
    }

    /**
     * Does one action.
     *
     * @param resume carries on with the actions after this one
     * @return whether resume will be called later, once the action is done
     */
    private boolean perform(@NonNull Step step, @NonNull Runnable resume) {
        ActionKind kind = ActionKind.of(step.kind);
        switch (kind) {
            case DIALOG -> {
                viewer.getGUIRoot().addModalForm(new InGameCampaignDialogForm(viewer, step.getText(Param.HEADER),
                        step.getText(Param.TEXT), face(step.get(Param.FACE)), step.get(Param.FACE) == 0
                        || step.get(Param.FACE) > Param.FACES_PER_TRIBE ? Origin.AT_END : Origin.AT_START,
                        resume));
                return true;
            }
            case OBJECTIVE -> {
                objective = step.getText(Param.TEXT);
                viewer.getGUIRoot().getInfoPrinter().print(CampaignEditor.i18n("new_objective", objective));
            }
            case MESSAGE -> viewer.getGUIRoot().getInfoPrinter().print(step.getText(Param.TEXT));
            case WAIT -> {
                TimerAnimation timer = new TimerAnimation(viewer.getWorld().getAnimationManagerGameTime(), t -> {
                    t.stop();
                    resume.run();
                }, Math.max(.1f, step.get(Param.SECONDS)));
                timer.start();
                return true;
            }
            case CAMERA_JUMP -> {
                Scenario.Area area = scenario.findArea(step.get(Param.AREA));
                if (area != null) {
                    viewer.getGUIRoot().pushDelegate(new JumpDelegate(viewer, viewer.getCamera(), area.x, area.y,
                            JUMP_METERS_PER_SECOND, JUMP_MAX_SECONDS, resume));
                    return true;
                }
            }
            case SPAWN_UNITS -> {
                Scenario.Area area = scenario.findArea(step.get(Param.AREA));
                Player player = players[step.get(Param.PLAYER)];
                if (area != null && player != null)
                    spawn(player, Param.UNIT_TYPES[Math.clamp(step.get(Param.UNIT_TYPE), 0,
                            Param.UNIT_TYPES.length - 1)], step.get(Param.COUNT), area);
            }
            case ATTACK_AREA -> {
                Scenario.Area area = scenario.findArea(step.get(Param.AREA));
                Player player = players[step.get(Param.PLAYER)];
                if (area != null && player != null)
                    AI.attackLandscape(player, new LandscapeTarget(UnitGrid.toGridCoordinate(area.x),
                            UnitGrid.toGridCoordinate(area.y)), step.get(Param.COUNT), filter(step)::matches);
            }
            case ATTACK_PLAYER -> {
                Player player = players[step.get(Param.PLAYER)];
                Player target_player = players[step.get(Param.TARGET_PLAYER)];
                Target target = target_player != null ? attackTarget(target_player) : null;
                if (player != null && target != null)
                    AI.attackLandscape(player, target, step.get(Param.COUNT), filter(step)::matches);
            }
            case DEPLOY -> {
                Player player = players[step.get(Param.PLAYER)];
                if (player != null && player.getArmory() != null && !player.getArmory().isDead())
                    player.deployUnits(player.getArmory(), deployType(step.get(Param.DEPLOY_TYPE)),
                            step.get(Param.COUNT));
            }
            case REFILL_ARMORY -> {
                Player player = players[step.get(Param.PLAYER)];
                if (player != null)
                    refillArmory(player);
            }
            case REINFORCEMENTS -> {
                Player player = players[step.get(Param.PLAYER)];
                if (player != null)
                    new ReinforcementsTrigger(player, deployType(step.get(Param.DEPLOY_TYPE)));
            }
            case CHANGE_OWNER_AREA -> {
                Scenario.Area area = scenario.findArea(step.get(Param.AREA));
                Player from = players[step.get(Param.PLAYER)];
                Player to = players[step.get(Param.NEW_OWNER)];
                if (area != null && from != null && to != null && from != to) {
                    for (Unit unit : unitsOf(from, filter(step))) {
                        if (area.contains(unit.getPositionX(), unit.getPositionY()) && !isChieftain(unit))
                            changeOwner(unit, to);
                    }
                }
            }
            case CHANGE_OWNER_OBJECT -> {
                Selectable<?> object = objects.get(step.get(Param.OBJECT));
                Player to = players[step.get(Param.NEW_OWNER)];
                if (object instanceof Unit unit && !unit.isDead() && to != null && unit.getOwner() != to
                        && !isChieftain(unit))
                    changeOwner(unit, to);
            }
            case REMOVE_OBJECT -> {
                Selectable<?> object = objects.get(step.get(Param.OBJECT));
                SceneryModel statue = placed_statues.get(step.get(Param.OBJECT));
                if (object instanceof Unit unit && !unit.isDead())
                    unit.removeNow();
                else if (object instanceof Building building && !building.isDead())
                    building.hit(KILL_DAMAGE, 0f, 1f, building.getOwner());
                else if (statue != null)
                    removeStatue(statue);
            }
            case SET_AI -> {
                Player player = players[step.get(Param.PLAYER)];
                if (player != null && player != viewer.getLocalPlayer())
                    setRole(player, Param.ROLES[Math.clamp(step.get(Param.ROLE), 0, Param.ROLES.length - 1)]);
            }
            case ACTIVATE_TRIGGER -> {
                Scenario.Trigger other = scenario.findTrigger(step.get(Param.TRIGGER));
                if (other != null)
                    activate(other);
            }
            case DEACTIVATE_TRIGGER -> deactivate(step.get(Param.TRIGGER));
            case VICTORY -> {
                end();
                outcome.victory();
            }
            case DEFEAT -> {
                end();
                String message = step.getText(Param.TEXT);
                outcome.defeat(message.isBlank() ? CampaignEditor.i18n("default_defeat") : message);
            }
            case SET_TEAM -> {
                Player player = players[step.get(Param.PLAYER)];
                if (player != null)
                    player.setTeam(Scenario.gameTeam(Math.clamp(step.get(Param.TEAM), 0, Scenario.NEUTRAL_TEAM)));
            }
            case ENTER_BUILDING -> {
                Building building = liveBuilding(step.get(Param.BUILDING));
                if (objects.get(step.get(Param.OBJECT)) instanceof Unit unit && building != null)
                    sendInto(unit, building);
            }
            case BOARD_FROM_AREA -> {
                Scenario.Area area = scenario.findArea(step.get(Param.AREA));
                Player player = players[step.get(Param.PLAYER)];
                Building building = liveBuilding(step.get(Param.BUILDING));
                if (area != null && player != null && building != null) {
                    int left = Math.max(1, step.get(Param.COUNT));
                    for (Unit unit : unitsOf(player, filter(step))) {
                        if (left > 0 && area.contains(unit.getPositionX(), unit.getPositionY())
                                && sendInto(unit, building))
                            left--;
                    }
                }
            }
            case LEAVE_BUILDING -> {
                Building building = liveBuilding(step.get(Param.BUILDING));
                if (building instanceof Ship ship)
                    unload(ship, Integer.MAX_VALUE, null);
                else if (building != null && building.getUnitContainer() != null)
                    emptyTower(building);
            }
            case DEPLOY_FROM -> {
                Building building = liveBuilding(step.get(Param.BUILDING));
                if (building != null)
                    deployFrom(building, deployType(step.get(Param.DEPLOY_TYPE)), Math.max(1, step.get(Param.COUNT)));
            }
            case SPAWN_STATUES -> {
                Scenario.Area area = scenario.findArea(step.get(Param.AREA));
                if (area != null)
                    spawnStatues(area, Math.max(1, step.get(Param.COUNT)));
            }
            case REMOVE_STATUES -> {
                for (SceneryModel statue : statuesIn(scenario.findArea(step.get(Param.AREA))))
                    removeStatue(statue);
            }
            case REMOVE_UNITS, KILL_UNITS -> {
                Player player = players[step.get(Param.PLAYER)];
                Scenario.Area area = scenario.findArea(step.get(Param.AREA));
                if (player != null) {
                    for (Unit unit : unitsOf(player, filter(step))) {
                        // Those in towers and ships stay, as taking them out from under their building breaks it.
                        if (unit.isMounted() || (area != null
                                && !area.contains(unit.getPositionX(), unit.getPositionY())))
                            continue;
                        if (kind == ActionKind.KILL_UNITS)
                            unit.hit(KILL_DAMAGE, 0f, 1f, player);
                        else
                            unit.removeNow();
                    }
                }
            }
        }
        return false;
    }

    private @Nullable Building liveBuilding(int id) {
        return objects.get(id) instanceof Building building && !building.isDead() ? building : null;
    }

    /**
     * Sends a unit to walk into a tower or aboard a ship of its own tribe, as the player would order it.
     *
     * @return whether it was sent: it lives, is free to go, and fits
     */
    private static boolean sendInto(@NonNull Unit unit, @NonNull Building building) {
        if (unit.isDead() || unit.isMounted() || unit.getOwner() != building.getOwner()
                || building.getUnitContainer() == null || !building.getUnitContainer().canEnter(unit))
            return false;
        unit.setTarget(building, Action.DEFAULT, false);
        return true;
    }

    /** Brings a tower's guard down, wherever the tower has room by its door. */
    private static void emptyTower(@NonNull Building tower) {
        if (tower.getUnitContainer().getNumSupplies() > 0)
            tower.getUnitContainer().exit();
    }

    /**
     * Sets units on a ship ashore, if it lies by land; at sea there is nowhere for them to go.
     *
     * @param count how many at most
     * @param type the kind to land, or null for any kind
     */
    private static void unload(@NonNull Ship ship, int count, @Nullable DeployType type) {
        if (ship.getEntrance() == ship || ship.getShipHR() == null)
            return;
        Race race = ship.getOwner().getRace();
        int[] kinds = type != null ? new int[]{unitType(type)} : new int[]{Race.UNIT_WARRIOR_RUBBER,
                Race.UNIT_WARRIOR_IRON, Race.UNIT_WARRIOR_ROCK, Race.UNIT_PEON, Race.UNIT_CHIEFTAIN};
        int landed = 0;
        for (int kind : kinds) {
            while (landed < count && ship.getShipHR().exitUnit(race.getUnitTemplate(kind)) != null)
                landed++;
        }
    }

    /**
     * Sends units of a kind out of a building, making those it lacks as far as the tribe has room: quarters send
     * peons, an armory or a ship the kind asked for, and a tower its guard.
     */
    private void deployFrom(@NonNull Building building, @NonNull DeployType type, int count) {
        Player owner = building.getOwner();
        if (building instanceof Ship ship) {
            if (ship.getShipHR() == null)
                return;
            Race race = owner.getRace();
            int have = ship.getShipHR().countUnitsOfType(type == DeployType.PEON ? Unit.class : weaponOf(type));
            for (int i = have; i < count && !owner.getUnitCountContainer().isSupplyFull(); i++) {
                Unit unit = new Unit(owner, ship.getPositionX(), ship.getPositionY(), null,
                        race.getUnitTemplate(unitType(type)));
                if (ship.getUnitContainer().canEnter(unit)) {
                    ship.getUnitContainer().enter(unit);
                } else {
                    unit.removeNow();
                    break;
                }
            }
            unload(ship, count, type);
            return;
        }
        if (building.getUnitContainer() == null)
            return;
        if (building.getUnitContainer() instanceof com.oddlabs.tt.model.MountUnitContainer) {
            emptyTower(building);
            return;
        }
        // Quarters only bring forth peons.
        DeployType sent = building.getAbilities().hasAbilities(Abilities.BUILD_ARMIES) ? type : DeployType.PEON;
        if (building.getDeployContainer(sent) == null)
            return;
        int inside = building.getUnitContainer().getNumSupplies();
        int room = owner.getWorld().getMaxUnitCount() - owner.getUnitCountContainer().getNumSupplies();
        int missing = Math.clamp(count - inside, 0, Math.max(0, room));
        if (missing > 0)
            building.getUnitContainer().increaseSupply(missing);
        if (sent != DeployType.PEON)
            building.fillSupplies(weaponOf(sent), count);
        owner.deployUnits(building, sent, count);
    }

    private static int unitType(@NonNull DeployType type) {
        return switch (type) {
            case ROCK_WARRIOR -> Race.UNIT_WARRIOR_ROCK;
            case IRON_WARRIOR -> Race.UNIT_WARRIOR_IRON;
            case RUBBER_WARRIOR -> Race.UNIT_WARRIOR_RUBBER;
            default -> Race.UNIT_PEON;
        };
    }

    private static @NonNull Class<?> weaponOf(@NonNull DeployType type) {
        return switch (type) {
            case ROCK_WARRIOR -> RockAxeWeapon.class;
            case RUBBER_WARRIOR -> RubberAxeWeapon.class;
            default -> IronAxeWeapon.class;
        };
    }

    /** Sets statues down on free land at random spots in an area. */
    private void spawnStatues(Scenario.@NonNull Area area, int count) {
        for (int i = 0; i < count; i++) {
            for (int attempt = 0; attempt < STATUE_TRIES; attempt++) {
                double angle = random.nextDouble() * 2 * Math.PI;
                float distance = (float) Math.sqrt(random.nextFloat()) * area.radius;
                int x = UnitGrid.toGridCoordinate(area.x + distance * (float) Math.cos(angle));
                int y = UnitGrid.toGridCoordinate(area.y + distance * (float) Math.sin(angle));
                if (newStatue(x, y, random.nextInt(ObjectKind.STATUE_VARIANTS), random.nextInt()) != null)
                    break;
            }
        }
    }

    /** Sets a computer player's AI, stopping the one it had. */
    private void setRole(@NonNull Player player, Scenario.@NonNull Role role) {
        AI old = player.getAI();
        if (old != null)
            old.stop();
        AI ai = switch (role) {
            case OPPONENT -> new AdvancedAI(player, null, aiDifficulty(difficulty));
            case OPPONENT_EASY -> new AdvancedAI(player, null, AdvancedAI.DIFFICULTY_EASY);
            case OPPONENT_NORMAL -> new AdvancedAI(player, null, AdvancedAI.DIFFICULTY_NORMAL);
            case OPPONENT_HARD -> new AdvancedAI(player, null, AdvancedAI.DIFFICULTY_HARD);
            case PASSIVE -> new PassiveAI(player, null, true);
            case NEUTRAL, HUMAN -> new PassiveAI(player, null, false);
        };
        player.setAI(ai);
    }

    /** The opponents' AI level for a campaign difficulty. */
    static int aiDifficulty(int campaign_difficulty) {
        return switch (campaign_difficulty) {
            case CampaignState.DIFFICULTY_EASY -> AdvancedAI.DIFFICULTY_EASY;
            case CampaignState.DIFFICULTY_HARD -> AdvancedAI.DIFFICULTY_HARD;
            default -> AdvancedAI.DIFFICULTY_NORMAL;
        };
    }

    private static @Nullable IconQuad face(int face) {
        if (face <= 0)
            return null;
        if (face <= Param.FACES_PER_TRIBE)
            return VikingCampaignIcons.getIcons().getFaces()[face - 1];
        int index = face - 1 - Param.FACES_PER_TRIBE;
        return index < Param.FACES_PER_TRIBE ? NativeCampaignIcons.getIcons().getFaces()[index] : null;
    }

    private void spawn(@NonNull Player player, @NonNull ObjectKind kind, int count, Scenario.@NonNull Area area) {
        for (int i = 0; i < count; i++) {
            double angle = random.nextDouble() * 2 * Math.PI;
            float distance = (float) Math.sqrt(random.nextFloat()) * area.radius;
            float x = area.x + distance * (float) Math.cos(angle);
            float y = area.y + distance * (float) Math.sin(angle);
            if (kind == ObjectKind.CHIEFTAIN) {
                if (!player.hasActiveChieftain())
                    newChieftain(player, x, y);
                return;
            }
            if (player.getUnitCountContainer().isSupplyFull())
                return;
            Unit unit = new Unit(player, x, y, null, player.getRace().getUnitTemplate(kind.getUnitType()));
            viewer.getPicker().getRespondManager().addResponder(unit);
        }
    }

    /** Hands a unit to another tribe, as the game's campaigns free captives: the same kind of unit, new owner. */
    private void changeOwner(@NonNull Unit unit, @NonNull Player owner) {
        float x = unit.getPositionX();
        float y = unit.getPositionY();
        UnitTemplate template = unit.getTemplate();
        int template_index = -1;
        for (int i = Race.UNIT_WARRIOR_ROCK; i <= Race.UNIT_PEON; i++) {
            if (unit.getOwner().getRace().getUnitTemplate(i) == template)
                template_index = i;
        }
        unit.removeNow();
        if (owner.getUnitCountContainer().isSupplyFull())
            return;
        // The new owner's own kind of the unit, as its race may differ.
        UnitTemplate new_template = template_index != -1 ? owner.getRace().getUnitTemplate(template_index)
                : template;
        Unit changed = new Unit(owner, x, y, null, new_template);
        viewer.getPicker().getRespondManager().addResponder(changed);
        objects.replaceAll((_, object) -> object == unit ? changed : object);
    }

    private static void refillArmory(@NonNull Player player) {
        Building quarters = player.getQuarters();
        Building armory = player.getArmory();
        if (quarters == null || armory == null)
            return;
        quarters.removeSupplies(Unit.class);
        armory.fillSupplies(Unit.class,
                player.getWorld().getMaxUnitCount() - player.getUnitCountContainer().getNumSupplies());
        armory.fillSupplies(IronAxeWeapon.class, Integer.MAX_VALUE);
    }

    /** What to send an attack at: the armory, else the quarters, else the chieftain, else any unit. */
    private static @Nullable Target attackTarget(@NonNull Player player) {
        Building armory = player.getArmory();
        if (armory != null && !armory.isDead())
            return armory;
        Building quarters = player.getQuarters();
        if (quarters != null && !quarters.isDead())
            return quarters;
        Unit chieftain = player.getChieftain();
        if (chieftain != null && !chieftain.isDead())
            return chieftain;
        for (Selectable<?> selectable : player.getUnits().getSet()) {
            if (!selectable.isDead())
                return selectable;
        }
        return null;
    }

    private static @NonNull DeployType deployType(int index) {
        return Param.DEPLOY_TYPES[Math.clamp(index, 0, Param.DEPLOY_TYPES.length - 1)];
    }

    private static @NonNull Class<? extends Supply> supplyType(int index) {
        return switch (index) {
            case 0 -> TreeSupply.class;
            case 1 -> RockSupply.class;
            case 2 -> IronSupply.class;
            default -> RubberSupply.class;
        };
    }

    private static boolean isChieftain(@NonNull Unit unit) {
        return unit.getAbilities().hasAbilities(Abilities.MAGIC);
    }

    private static @NonNull List<@NonNull Unit> unitsOf(@NonNull Player player) {
        return unitsOf(player, UnitFilter.ANY);
    }

    /** The player's live units of a kind. */
    private static @NonNull List<@NonNull Unit> unitsOf(@NonNull Player player, @NonNull UnitFilter filter) {
        List<Unit> units = new ArrayList<>();
        for (Selectable<?> selectable : player.getUnits().getSet()) {
            if (selectable instanceof Unit unit && !unit.isDead() && filter.matches(unit))
                units.add(unit);
        }
        return units;
    }

    /** The units of a kind a step's {@link Param#UNIT_FILTER} picks. */
    private static @NonNull UnitFilter filter(@NonNull Step step) {
        return UnitFilter.of(step.get(Param.UNIT_FILTER));
    }

    /**
     * How many units of a kind the player has: of any kind, as the game counts them, with those in its buildings;
     * else those out on the island.
     */
    private static int countUnits(@NonNull Player player, @NonNull UnitFilter filter) {
        return filter == UnitFilter.ANY ? player.getUnitCountContainer().getNumSupplies()
                : unitsOf(player, filter).size();
    }

    private static int countUnits(@NonNull Player player, Scenario.@NonNull Area area, @NonNull UnitFilter filter) {
        int count = 0;
        for (Unit unit : unitsOf(player, filter)) {
            if (area.contains(unit.getPositionX(), unit.getPositionY()))
                count++;
        }
        return count;
    }

    /**
     * Where a placed object is while it stands: a live unit or building, or a statue not yet taken away.
     *
     * @return x and y in meters, or null when it is gone
     */
    private float @Nullable [] whereIs(int id) {
        Selectable<?> object = objects.get(id);
        if (object != null)
            return object.isDead() ? null : new float[]{object.getPositionX(), object.getPositionY()};
        SceneryModel statue = placed_statues.get(id);
        return statue != null && statues.contains(statue)
                ? new float[]{statue.getPositionX(), statue.getPositionY()} : null;
    }

    /** The statues standing in an area, or anywhere when there is no area. */
    private @NonNull List<@NonNull SceneryModel> statuesIn(Scenario.@Nullable Area area) {
        List<SceneryModel> found = new ArrayList<>();
        for (SceneryModel statue : statues) {
            if (area == null || area.contains(statue.getPositionX(), statue.getPositionY()))
                found.add(statue);
        }
        return found;
    }

    /**
     * Whether a unit of the player, of the kind, is within a radius of a spot.
     *
     * @param self the object at the spot, which does not count itself, or null
     */
    private static boolean hasUnitNear(@NonNull Player player, @NonNull UnitFilter filter,
            @Nullable Selectable<?> self, float @NonNull [] at, float radius) {
        for (Unit unit : unitsOf(player, filter)) {
            if (unit == self)
                continue;
            float dx = unit.getPositionX() - at[0];
            float dy = unit.getPositionY() - at[1];
            if (dx * dx + dy * dy <= radius * radius)
                return true;
        }
        return false;
    }

    private static boolean isEliminated(@NonNull Player player) {
        return player.getUnitCountContainer().getNumSupplies() == 0 && !player.hasActiveChieftain();
    }

    /** Every enemy of the player is out of units, as VictoryTrigger decides. */
    private boolean enemiesDefeated() {
        Player local = viewer.getLocalPlayer();
        for (Player player : viewer.getWorld().getPlayers()) {
            if (local.isEnemy(player) && !isEliminated(player))
                return false;
        }
        return true;
    }

    /** Supplies of a kind stocked in the player's buildings, as SupplyGatheredTrigger counts them. */
    private static int countSupplies(@NonNull Player player, @NonNull Class<? extends Supply> type) {
        int count = 0;
        for (Selectable<?> selectable : player.getUnits().getSet()) {
            if (selectable instanceof Building building && !building.isDead()
                    && building.getPrimaryController() instanceof NullController
                    && building.getAbilities().hasAbilities(Abilities.BUILD_ARMIES)) {
                SupplyContainer container = building.getSupplyContainer(type);
                if (container != null)
                    count += container.getNumSupplies();
            }
        }
        return count;
    }

    /** Loses the level when the chieftain the player started with dies, or the player has nothing left. */
    private final class DefeatWatch extends IntervalTrigger {
        private final @NonNull Player player;
        private final @Nullable Unit chieftain;
        private @NonNull String message = "";

        DefeatWatch(@NonNull Player player, @Nullable Unit chieftain) {
            super(viewer.getWorld(), CHECK_INTERVAL, 0f);
            this.player = player;
            this.chieftain = chieftain;
        }

        @Override
        protected void check() {
            if (over) {
                abort();
                return;
            }
            if (chieftain != null && chieftain.isDead()) {
                message = CampaignEditor.i18n("defeat_chieftain");
                triggered();
            } else if (isEliminated(player)) {
                message = CampaignEditor.i18n("default_defeat");
                triggered();
            }
        }

        @Override
        protected void done() {
            if (over)
                return;
            end();
            outcome.defeat(message);
        }
    }
}
