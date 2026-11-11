package com.oddlabs.tt.player.neural;

import com.oddlabs.tt.aikit.AiLog;
import com.oddlabs.tt.aikit.AiParams;
import com.oddlabs.tt.aikit.GameTime;
import com.oddlabs.tt.player.AI;
import com.oddlabs.tt.player.Player;
import com.oddlabs.tt.player.UnitInfo;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;

/**
 * A computer player whose plan comes from a trained network. Every {@code step} game seconds the policy ({@link Net})
 * reads an {@link Observation} and chooses one action per head ({@link Heads}); the skill layer ({@link Body}) carries
 * the choices out with plain rules. lab/neural holds the trainer and the notes.
 *
 * <p>Params: {@code net} the network file (without one the policy chooses at random), {@code traj} an existing folder
 * that gets this player's trajectory for the trainer, {@code greedy=1} to take the most likely action instead of
 * sampling (for measuring a net's strength), {@code seed} to vary the sampling between runs, and {@code step}. A
 * relative file name is from the repository root, also in the game client, which runs one folder below it.
 */
public final class NeuralAI extends AI {
    private final @NonNull AiLog log;
    private final GameTime.@NonNull Every every_second = new GameTime.Every(1000);
    private final @Nullable Net net;
    private final @NonNull Trajectory trajectory;
    private final @NonNull Body body;
    /** Null when the policy takes the most likely action. */
    private final @Nullable Random random;
    private final int step;
    private final int @NonNull [] action = new int[Heads.COUNT];

    public NeuralAI(@NonNull Player owner, @NonNull UnitInfo units, @NonNull String spec_params) {
        super(owner, units); // first: registers this AI and creates the starting units
        AiParams params = AiParams.parse(spec_params);
        String net_file = params.getString("net", "");
        String trajectory_folder = params.getString("traj", "");
        boolean greedy = params.getBoolean("greedy", false);
        int seed = params.getInt("seed", 0);
        step = Math.max(1, params.getInt("step", 5));
        log = AiLog.of(owner);
        log.log("PARAM", params.done());
        int slot = Arrays.asList(owner.getWorld().getPlayers()).indexOf(owner);
        // differs per game and per player without drawing from the world's random: the starts are the map's
        long mixed = seed * 1_000_003L + slot * 7919L + Float.floatToIntBits(
                owner.getStartX()) * 31L + Float.floatToIntBits(owner.getStartY());
        random = greedy ? null : new Random(mixed);
        net = Net.load(net_file.isEmpty() ? null : located(net_file), Observation.SIZE);
        trajectory = new Trajectory(trajectory_folder.isEmpty() ? null : located(trajectory_folder), log.game(),
                slot);
        body = new Body(owner, log);
        Arrays.fill(action, -1);
    }

    /**
     * The file or folder {@code name} of the parameters. The harness runs in the repository root, which a relative
     * name is from; the game client (a watched game, a play-test) runs in tt/, one folder below.
     */
    private static @NonNull Path located(@NonNull String name) {
        Path path = Path.of(name);
        if (!path.isAbsolute() && !Files.exists(path)) {
            Path from_client = Path.of("..").resolve(path);
            if (Files.exists(from_client)) {
                return from_client;
            }
        }
        return path;
    }

    @Override
    public void animate(float t) {
        if (!every_second.due(getOwner().getWorld())) {
            return;
        }
        try {
            think((int) (every_second.struck() / 1000));
        } catch (RuntimeException | AssertionError e) {
            // engine getters assert on units that just died: count it and let the game go on
            log.error("think", e);
        }
    }

    private void think(int second) {
        Player me = getOwner();
        Sight see = new Sight(me);
        if (second % step == 0) {
            decide(me, see, second);
        }
        body.act(see, second);
        if (second % 30 == 0 || (second < 240 && second % 10 == 0)) {
            log.log("STAT",
                    () -> "units " + see.units + " idle " + see.idle_peons.size() + " builders " + see.builders.size() + " busy " + see.busy_peons + " in quarters " + see.in_quarters + " in armory " + see.in_armories + " gatherers " + Arrays.toString(
                            see.gatherers) + " warriors " + see.warriors() + " sites " + see.sites.size() + " pending " + body.pendingBuild() + " plan " + Arrays.toString(
                                    action));
        }
    }

    private void decide(@NonNull Player me, @NonNull Sight see, int second) {
        float[] observation = Observation.of(me, see, action, body.hasPendingBuild(), me.getWorld().getMaxUnitCount(),
                me.getWorld().getMaxBuildingCount());
        boolean[] allowed = body.allowed(see);
        float[] logits = net == null ? new float[Heads.LOGITS] : net.forward(observation);
        float value = net == null ? 0f : net.value();
        float log_probability = Net.choose(logits, allowed, random, action);
        trajectory.record(second, observation, action, allowed, log_probability, value, Observation.outcome(me, see));
        body.decide(action, second);
        log.count("decisions");
    }
}
