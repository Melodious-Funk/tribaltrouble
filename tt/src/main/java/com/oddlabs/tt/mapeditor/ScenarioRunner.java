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
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.Supply;
import com.oddlabs.tt.model.SupplyContainer;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.model.UnitTemplate;
import com.oddlabs.tt.model.behaviour.MagicController;
import com.oddlabs.tt.model.behaviour.NullController;
import com.oddlabs.tt.model.weapon.IronAxeWeapon;
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
import java.util.List;
import java.util.Map;
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
    private final Map<Integer, Watch> watches = new HashMap<>();
    private final Random random = new Random(42);
    private @NonNull String objective;
    private boolean over;

    /**
     * @param difficulty the campaign difficulty, as {@link CampaignState} has it
     */
    ScenarioRunner(@NonNull WorldViewer viewer, @NonNull Scenario scenario, int difficulty,
            @NonNull Outcome outcome) {
        this.viewer = viewer;
        this.scenario = scenario;
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
        if (kind.isBuilding()) {
            Building building = player.buildBuilding(kind.getBuildingType(), placement.x(), placement.y());
            if (building != null && kind == ObjectKind.GUARDED_TOWER
                    && !player.getUnitCountContainer().isSupplyFull()) {
                Unit guard = new Unit(player, x, y, null, player.getRace().getUnitTemplate(Race.UNIT_WARRIOR_IRON));
                guard.setTarget(building, Action.DEFAULT, false);
            }
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
     * Where a player's camera starts and its AI sends units home to: its quarters, else its chieftain, else its
     * first unit or building.
     */
    private void placeStart(@NonNull Player player, int index) {
        Scenario.Placement first = null;
        Scenario.Placement best = null;
        for (Scenario.Placement placement : scenario.placements) {
            if (placement.player() != index)
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
                            && countUnits(player, area) >= Math.max(1, step.get(Param.COUNT));
                }
                case UNITS_NEAR_OBJECT -> {
                    Selectable<?> object = objects.get(step.get(Param.OBJECT));
                    Player player = players[step.get(Param.PLAYER)];
                    yield object != null && !object.isDead() && player != null
                            && hasUnitNear(player, object, step.get(Param.RADIUS));
                }
                case OBJECT_DESTROYED -> {
                    Selectable<?> object = objects.get(step.get(Param.OBJECT));
                    yield object == null || object.isDead();
                }
                case PLAYER_ELIMINATED -> {
                    Player player = players[step.get(Param.PLAYER)];
                    yield player == null || isEliminated(player);
                }
                case ENEMIES_DEFEATED -> enemiesDefeated();
                case UNITS_BELOW -> {
                    Player player = players[step.get(Param.PLAYER)];
                    yield player == null || player.getUnitCountContainer().getNumSupplies() < step.get(Param.COUNT);
                }
                case SUPPLIES_GATHERED -> {
                    Player player = players[step.get(Param.PLAYER)];
                    yield player != null && countSupplies(player, supplyType(step.get(Param.SUPPLY_TYPE)))
                            >= step.get(Param.COUNT);
                }
                case MAGIC_USED -> magicUsed(step);
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
                            UnitGrid.toGridCoordinate(area.y)), step.get(Param.COUNT));
            }
            case ATTACK_PLAYER -> {
                Player player = players[step.get(Param.PLAYER)];
                Player target_player = players[step.get(Param.TARGET_PLAYER)];
                Target target = target_player != null ? attackTarget(target_player) : null;
                if (player != null && target != null)
                    AI.attackLandscape(player, target, step.get(Param.COUNT));
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
                    for (Unit unit : unitsOf(from)) {
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
                if (object instanceof Unit unit && !unit.isDead())
                    unit.removeNow();
                else if (object instanceof Building building && !building.isDead())
                    building.hit(KILL_DAMAGE, 0f, 1f, building.getOwner());
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
        }
        return false;
    }

    /** Sets a computer player's AI, stopping the one it had. */
    private void setRole(@NonNull Player player, Scenario.@NonNull Role role) {
        AI old = player.getAI();
        if (old != null)
            old.stop();
        AI ai = switch (role) {
            case OPPONENT -> new AdvancedAI(player, null, aiDifficulty(difficulty));
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
        List<Unit> units = new ArrayList<>();
        for (Selectable<?> selectable : player.getUnits().getSet()) {
            if (selectable instanceof Unit unit && !unit.isDead())
                units.add(unit);
        }
        return units;
    }

    private static int countUnits(@NonNull Player player, Scenario.@NonNull Area area) {
        int count = 0;
        for (Unit unit : unitsOf(player)) {
            if (area.contains(unit.getPositionX(), unit.getPositionY()))
                count++;
        }
        return count;
    }

    private static boolean hasUnitNear(@NonNull Player player, @NonNull Selectable<?> object, float radius) {
        for (Unit unit : unitsOf(player)) {
            if (unit == object)
                continue;
            float dx = unit.getPositionX() - object.getPositionX();
            float dy = unit.getPositionY() - object.getPositionY();
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
