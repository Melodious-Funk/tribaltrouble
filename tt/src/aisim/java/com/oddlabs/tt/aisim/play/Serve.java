package com.oddlabs.tt.aisim.play;

import com.oddlabs.tt.aisim.Aisim;
import com.oddlabs.tt.aisim.UsageException;
import com.oddlabs.tt.aisim.analysis.End;
import com.oddlabs.tt.aisim.analysis.Runs;
import com.oddlabs.tt.aisim.analysis.Summary;
import com.oddlabs.tt.aisim.build.Snapshot;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * {@code serve}: plays batch requests from stdin on one pool of worker JVMs that outlives them, so a program that runs
 * many short batches one after another (a trainer's generations) pays a worker's start and JIT warm-up once, not once
 * per batch. A request is one line, a JSON object {@code {"id": ..., "args": [batch options]}}; its run is the run
 * {@code batch} with those options writes (run.json, results.jsonl, summary.txt), and once its last game is recorded
 * the answer is one {@value WorkerProcess#ROW_PREFIX} line on stdout: {@code {"id", "run", "games", "failed",
 * "status"}}, or {@code {"id", "error"}} for a request that could not start. Requests may overlap: their games share
 * one queue, in the order they came. Everything else goes to stderr. The pool is sized like a batch's
 * ({@code --workers}, {@code --cpus}, {@code --memory}, automatic by default) and plays the snapshot that was latest
 * when serve started. At the end of stdin it plays what is queued and exits.
 */
public final class Serve {
    /** One request's run in progress. */
    private static final class Request {
        final @NonNull String id;
        final @NonNull String name;
        final int expected;
        final boolean lost_only;
        final @NonNull Writer results;
        int done;
        int failed;

        Request(@NonNull String id, @NonNull String name, int expected, boolean lost_only, @NonNull Writer results) {
            this.id = id;
            this.name = name;
            this.expected = expected;
            this.lost_only = lost_only;
            this.results = results;
        }
    }

    /** A queued game and the request it belongs to. */
    private record Entry(@NonNull Request request, @NonNull Job job, @NonNull String heap) {
    }

    /** How long a driver waits for a game before it looks at its pace again. */
    private static final long POLL_MILLIS = 500;

    private final @NonNull PrintStream protocol;
    private final @NonNull String snap;
    private final @NonNull Path dir;
    private final @NonNull LinkedBlockingQueue<Entry> queue = new LinkedBlockingQueue<>();
    private final @NonNull List<WorkerProcess> live = new ArrayList<>();
    private final @NonNull Pace pace;
    private final Pace.@NonNull Limits limits;
    private volatile boolean input_done;
    private volatile boolean stopped;
    /** Whether a game failed, which keeps the worker logs. */
    private volatile boolean any_failed;

    private Serve(@NonNull PrintStream protocol, Pace.@NonNull Limits limits) throws IOException {
        this.protocol = protocol;
        this.limits = limits;
        this.snap = Snapshot.latest();
        this.dir = Aisim.ROOT.resolve("serve").resolve(String.valueOf(ProcessHandle.current().pid()));
        Files.createDirectories(dir);
        this.pace = new Pace(limits, "serve-" + ProcessHandle.current().pid(), WorkerProcess.footprint(
                WorkerProcess.heap(2, 0)), Pace.MAX_WORKERS, queue::size, this::liveWorkers);
    }

    /**
     * Serves requests until the end of stdin; {@code prepare} turns a request's batch options into its run, as batch
     * does, or throws a {@link UsageException}.
     */
    public static int run(Pace.@NonNull Limits limits,
            @NonNull Function<String[], Aisim.BatchRun> prepare) throws IOException {
        PrintStream protocol = System.out;
        System.setOut(System.err); // only answers go to stdout
        Serve serve = new Serve(protocol, limits);
        return serve.serve(prepare);
    }

    private int serve(@NonNull Function<String[], Aisim.BatchRun> prepare) throws IOException {
        Thread hook = new Thread(this::killWorkers, "aisim-serve-kill");
        Runtime.getRuntime().addShutdownHook(hook); // Ctrl+C or SIGTERM: no worker outlives the pool
        System.out.println("aisim serve: snapshot " + snap + ", worker logs in " + Aisim.slash(dir));
        pace.start();
        List<Thread> drivers = new ArrayList<>();
        for (int i = 0; i < pace.slots(); i++) {
            int index = i;
            Thread thread = new Thread(() -> drive(index), "aisim-serve-" + i);
            thread.start();
            drivers.add(thread);
        }
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            if (!line.isBlank()) {
                accept(line, prepare);
            }
        }
        input_done = true;
        for (Thread thread : drivers) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        pace.stop();
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException e) {
            // shutting down already
        }
        if (!any_failed) {
            deleteTree(dir); // the worker logs matter only when a game failed
        }
        return 0;
    }

    private static void deleteTree(@NonNull Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    /** Starts the run of one request line, or answers why it cannot. */
    private void accept(@NonNull String line, @NonNull Function<String[], Aisim.BatchRun> prepare) {
        String id = "?";
        try {
            Map<String, Object> request = Aisim.JSON.readValue(line, Aisim.JSON_OBJECT);
            id = String.valueOf(request.get("id"));
            if (!(request.get("args") instanceof List<?> list)) {
                throw new UsageException("a request is {\"id\": ..., \"args\": [batch options]}");
            }
            String[] args = list.stream().map(String::valueOf).toArray(String[]::new);
            Aisim.BatchRun run = prepare.apply(args);
            Path run_dir = Runs.RUNS.resolve(run.name());
            Files.createDirectories(run_dir.resolve("log"));
            Batch.writeRunJson(run_dir, run.name(), run.setup(), run.jobs(), pace.slots(), limits, false, snap);
            Writer results = Files.newBufferedWriter(run_dir.resolve("results.jsonl"), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            Request accepted = new Request(id, run.name(), run.jobs().size(),
                    Batch.LOGS_LOST.equals(run.setup().logs()), results);
            String heap = WorkerProcess.heap(run.jobs());
            Order.Ordered ordered = Order.longestFirst(run.jobs(), run.setup().lineup(), run.setup().config(),
                    run.name());
            System.out.println(
                    "aisim " + run.name() + ": " + run.setup().players() + " | " + run.setup().config() + " | " + run.jobs().size() + " games");
            for (Job job : ordered.jobs()) {
                queue.add(new Entry(accepted, job, heap));
            }
        } catch (IOException | RuntimeException e) {
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("id", id);
            answer.put("error", e instanceof UsageException ? e.getMessage() : e.toString());
            answer(answer);
        }
    }

    /** Plays queued games on worker slot {@code index} until the input ends and the queue is empty. */
    private void drive(int index) {
        WorkerProcess worker = null;
        String worker_heap = null;
        try {
            while (!stopped) {
                if (index >= pace.target()) {
                    if (worker != null) {
                        worker.close();
                        worker = null;
                    }
                    if (input_done && queue.isEmpty()) {
                        break;
                    }
                    Batch.sleep(POLL_MILLIS);
                    continue;
                }
                Entry entry = queue.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
                if (entry == null) {
                    if (input_done && queue.isEmpty()) {
                        break;
                    }
                    continue;
                }
                if (worker != null && !entry.heap().equals(worker_heap)) {
                    worker.close(); // a game of another size: a worker with that heap
                    worker = null;
                }
                if (worker == null) {
                    worker = start(index, entry.heap());
                    worker_heap = entry.heap();
                    if (worker == null) {
                        break;
                    }
                }
                Map<String, Object> row = worker.play(entry.job());
                if (stopped) {
                    break;
                }
                if (row == null) {
                    String problem = "worker exited with " + worker.exitCode() + " (see " + Aisim.slash(log(
                            index)) + ")";
                    row = Match.errorRow(entry.job(), snap, End.error, problem, 0, 0);
                }
                record(entry, row);
                worker.games++;
                if (!End.endedNormally(row) || worker.games >= Batch.GAMES_PER_WORKER) {
                    worker.close();
                    worker = null;
                }
            }
        } catch (IOException | RuntimeException e) {
            Logger.getLogger(Serve.class.getName()).log(Level.SEVERE, "serve worker thread " + index + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (worker != null) {
                worker.close();
            }
        }
    }

    /** Appends a game's row to its run's results.jsonl; after the run's last game, its summary and the answer. */
    private void record(@NonNull Entry entry, @NonNull Map<String, Object> row) throws IOException {
        Request request = entry.request();
        synchronized (request) {
            request.results.write(Aisim.JSON.writeValueAsString(row) + "\n");
            request.results.flush();
            if (request.lost_only && "win".equals(row.get("result"))) {
                Batch.deleteAiLogs(entry.job());
            }
            request.done++;
            if (row.get("result") == null) {
                request.failed++;
                any_failed = true;
            }
            if (request.done < request.expected) {
                return;
            }
            request.results.close();
            int status = Summary.run(request.name);
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("id", request.id);
            answer.put("run", request.name);
            answer.put("games", request.done);
            answer.put("failed", request.failed);
            answer.put("status", status);
            answer(answer);
        }
    }

    private void answer(@NonNull Map<String, Object> answer) {
        try {
            String text = Aisim.JSON.writeValueAsString(answer);
            synchronized (protocol) {
                protocol.println(WorkerProcess.ROW_PREFIX + text);
                protocol.flush();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Starts a worker JVM with {@code heap} for slot {@code index}; null once serve is stopping. */
    private @Nullable WorkerProcess start(int index, @NonNull String heap) throws IOException {
        synchronized (live) {
            if (stopped) {
                return null;
            }
            WorkerProcess worker = new WorkerProcess(log(index), dir.resolve("n").resolve("w" + index), snap, heap,
                    null);
            live.add(worker);
            return worker;
        }
    }

    private @NonNull Path log(int index) {
        return dir.resolve("w" + index + ".log");
    }

    private @NonNull List<ProcessHandle> liveWorkers() {
        synchronized (live) {
            live.removeIf(worker -> !worker.handle().isAlive());
            return live.stream().map(WorkerProcess::handle).toList();
        }
    }

    private void killWorkers() {
        stopped = true;
        synchronized (live) {
            for (WorkerProcess worker : live) {
                worker.kill();
            }
        }
    }
}
