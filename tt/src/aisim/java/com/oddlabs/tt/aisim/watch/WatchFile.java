package com.oddlabs.tt.aisim.watch;

import com.oddlabs.tt.aisim.play.Job;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * What {@code ./aisim.sh watch} hands the game: the harness game to watch and how. The parent JVM writes it (the
 * repository root is its working directory, so it resolves runs, snapshots and frozen AIs), and the game reads it
 * (whose working directory is {@code tt/}): every path in it is absolute. The job's own {@code game} and {@code logs}
 * paths are relative to the repository root and are not used by the game.
 *
 * <p>Written and read by the same snapshot, so it may change freely with the harness.
 *
 * @param job                 the game, exactly as the harness played it (run.json's job, or rebuilt from the game
 *                            file's header)
 * @param gameFile            the recorded game file (census checksums, events and the end), or null if there is none
 * @param endTick             the tick the recorded game ended at (its end line's t as a tick), or -1 if unknown
 * @param snap                the snapshot the game runs from (the latest build)
 * @param runSnap             the snapshot that played the recorded game
 * @param loaders             the AIs of the lineup that come from a jar instead of the running build: frozen
 *                            {@code @TAG} AIs, and with {@code --ai-from-run} the run snapshot's own AI packages
 * @param outDir              an absolute folder the session may write to (logs of the session, exports)
 * @param checkpointTicks     world ticks between rewind checkpoints at first (the store thins them out to stay
 *                            within {@code checkpointMegabytes})
 * @param checkpointMegabytes the memory the rewind checkpoints may take in all
 * @param presim              whether to simulate the whole game before showing it (every jump is then quick)
 * @param selftest            a scripted check to run instead of waiting for the user, then quit with its
 *                            result (null: none)
 * @param recordings          the folder of runs the view lists (G), or a single run's folder
 * @param filter              which games of a run the list shows at first (Recordings.Filter's argument)
 * @param browse              whether the list is open from the start (no game was named)
 * @param nextFile            where the game writes the arguments of the next start when a game of the list is
 *                            picked, one a line, for aisim.sh to start again with (a selftest's goes to its output
 *                            folder, and nothing starts again); null when the game was not started by aisim.sh
 * @param again               the options of this start that a next start repeats
 */
public record WatchFile(@NonNull Job job, @Nullable String gameFile, int endTick, @NonNull String snap,
                        @NonNull String runSnap, @NonNull List<Loader> loaders, @NonNull String outDir,
                        int checkpointTicks, int checkpointMegabytes, boolean presim, @Nullable String selftest,
                        @NonNull String recordings, @NonNull String filter, boolean browse,
                        @Nullable String nextFile, @NonNull List<String> again) {

    /**
     * An AI whose package comes from a jar: every seat whose spec names {@code name} (before any {@code :params}, such
     * as {@code @cur8} or {@code gauntlet}) is played by class {@code entry}, loaded child-first for
     * {@code packageName} from {@code jar} (build.Pool's loader), everything else from the running build.
     */
    public record Loader(@NonNull String name, @NonNull String entry, @NonNull String packageName,
                         @NonNull String jar) {
    }
}
