package com.oddlabs.tt.player.neural;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * What the policy saw and chose, step by step, for the trainer (lab/neural): one file per player and game, named
 * after the harness's game key. Writing changes no decision. A file that exists is never touched, so replaying a
 * game leaves the trainer's data alone.
 *
 * <p>The file, big-endian: the magic and version, the observation size, the heads and their sizes, the number of
 * outcome numbers, the slot; then a record per decision: the game second, the observation, the action of each head
 * (a byte), which logits were allowed (a byte each), the log probability, the value, and the outcome numbers
 * ({@link Observation#outcome}). The game's result is not in it: the AI is not told when the game ends, so the
 * trainer takes it from the run's results.jsonl.
 */
final class Trajectory {
    private static final int MAGIC = 0x5454544A; // "TTTJ"
    private static final int VERSION = 1;

    private final @Nullable Path file;
    /** Set when writing failed: the game goes on without the file. */
    private boolean failed;
    private int records;

    /**
     * A trajectory under {@code folder} for the game the harness labelled {@code game} ("key=s3-1 snap=..."); it
     * writes nothing without a folder, without a key, or when the file is already there.
     */
    Trajectory(@Nullable String folder, @NonNull String game, int slot) {
        String key = null;
        for (String word : game.split(" ")) {
            if (word.startsWith("key=")) {
                key = word.substring("key=".length());
            }
        }
        Path path = folder == null || key == null ? null : Path.of(folder, key + "-s" + slot + ".traj");
        if (path != null) {
            try {
                Files.createDirectories(path.getParent());
                Files.write(path, header(slot), StandardOpenOption.CREATE_NEW);
            } catch (IOException e) {
                path = null; // most likely it exists: a replay
            }
        }
        file = path;
    }

    boolean on() {
        return file != null && !failed;
    }

    int records() {
        return records;
    }

    private static byte @NonNull [] header(int slot) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(MAGIC);
        out.writeInt(VERSION);
        out.writeInt(Observation.SIZE);
        out.writeInt(Heads.COUNT);
        for (int head = 0; head < Heads.COUNT; head++) {
            out.writeInt(Heads.size(head));
        }
        out.writeInt(Observation.OUTCOME_SIZE);
        out.writeInt(slot);
        return bytes.toByteArray();
    }

    /**
     * Appends one decision. The file is opened and closed each time: nothing tells the AI that the game is over, so
     * a file kept open would never be closed, and a worker plays thousands of games.
     */
    void record(float seconds, float @NonNull [] observation, int @NonNull [] action, boolean @NonNull [] allowed,
            float log_probability, float value, float @NonNull [] outcome) {
        if (file == null || failed) {
            return;
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeFloat(seconds);
            for (float v : observation) {
                out.writeFloat(v);
            }
            for (int a : action) {
                out.writeByte(a);
            }
            for (boolean ok : allowed) {
                out.writeByte(ok ? 1 : 0);
            }
            out.writeFloat(log_probability);
            out.writeFloat(value);
            for (float v : outcome) {
                out.writeFloat(v);
            }
            Files.write(file, bytes.toByteArray(), StandardOpenOption.APPEND);
            records++;
        } catch (IOException e) {
            failed = true;
        }
    }
}
