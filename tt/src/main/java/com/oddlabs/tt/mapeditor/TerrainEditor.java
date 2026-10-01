package com.oddlabs.tt.mapeditor;

import com.oddlabs.tt.landscape.HeightMap;
import com.oddlabs.tt.landscape.LandscapeLeaf;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.FloatBuffer;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Applies brushes to the island's height map and keeps the renderer in step.
 *
 * <p>The editor writes straight into the height array the {@link HeightMap} was built from, then uploads the
 * changed rectangle to the height texture in one go. Patch bounds, which culling and picking rely on, are grown
 * through {@link HeightMap#editHeight} but only for patches whose bounds the edit actually exceeded.
 *
 * <p>All coordinates here are grid units (one grid unit is {@link HeightMap#METERS_PER_UNIT_GRID} meters).
 */
final class TerrainEditor {
    static final float MIN_HEIGHT = 0f;
    static final float MAX_HEIGHT = 150f;

    /** Meters per second a full intensity height brush raises the ground at its center. */
    private static final float RAISE_RATE = 30f;
    /** How fast (1/seconds) smooth closes the gap to the local average at full intensity. */
    private static final float SETTLE_RATE = 8f;
    /** Largest fraction of the gap one frame of smooth may close, keeping sharpen stable. */
    private static final float MAX_SETTLE_STEP = 0.5f;
    /**
     * How fast (1/seconds) flatten closes the gap to its target at half intensity. The rate climbs steeply
     * towards full intensity, where flatten snaps to the target in one go.
     */
    private static final float FLATTEN_RATE = 15f;
    /** Share of a flatten brush's or a ramp's width that is fully flat, the rest blends into the ground around. */
    private static final float PLATEAU_CORE = 0.6f;
    static final int MAX_UNDO_STEPS = 32;

    private final @NonNull HeightMap height_map;
    private final float @NonNull [] @NonNull [] heights;
    private final int size;
    private final @NonNull FloatBuffer upload;
    private final float @NonNull [] @NonNull [] scratch;
    private final Deque<@NonNull UndoStep> undo_steps = new ArrayDeque<>();

    // Rectangle (inclusive) changed since the last flush.
    private int dirty_x0 = Integer.MAX_VALUE;
    private int dirty_y0 = Integer.MAX_VALUE;
    private int dirty_x1 = Integer.MIN_VALUE;
    private int dirty_y1 = Integer.MIN_VALUE;

    // Heights at the start of the current stroke, and the rectangle the stroke has touched.
    private float @Nullable [] @Nullable [] stroke_backup;
    private int stroke_x0;
    private int stroke_y0;
    private int stroke_x1;
    private int stroke_y1;

    private boolean modified;

    private record UndoStep(int x0, int y0, float @NonNull [] @NonNull [] heights) {
    }

    /** Told about each rectangle of grid cells (inclusive) whose heights reached the renderer. */
    @FunctionalInterface
    interface ChangeListener {
        void heightsChanged(int x0, int y0, int x1, int y1);
    }

    private final @NonNull ChangeListener listener;

    /**
     * @param heights the array the height map was built from; the height map keeps and reads the same array
     */
    TerrainEditor(@NonNull HeightMap height_map, float @NonNull [] @NonNull [] heights,
            @NonNull ChangeListener listener) {
        this.height_map = height_map;
        this.listener = listener;
        this.heights = heights;
        this.size = heights.length;
        this.upload = BufferUtils.createFloatBuffer(size * size);
        this.scratch = new float[size][size];
    }

    int getSize() {
        return size;
    }

    boolean isModified() {
        return modified;
    }

    void markSaved() {
        modified = false;
    }

    float @NonNull [] @NonNull [] copyHeights() {
        float[][] copy = new float[size][];
        for (int y = 0; y < size; y++)
            copy[y] = heights[y].clone();
        return copy;
    }

    /** Height at a grid position, bilinearly interpolated. */
    float getHeight(float gx, float gy) {
        gx = Math.clamp(gx, 0f, size - 1);
        gy = Math.clamp(gy, 0f, size - 1);
        int x0 = Math.min((int) gx, size - 2);
        int y0 = Math.min((int) gy, size - 2);
        float fx = gx - x0;
        float fy = gy - y0;
        float h0 = heights[y0][x0] * (1 - fx) + heights[y0][x0 + 1] * fx;
        float h1 = heights[y0 + 1][x0] * (1 - fx) + heights[y0 + 1][x0 + 1] * fx;
        return h0 * (1 - fy) + h1 * fy;
    }

    // ---- Strokes and undo ----

    void beginStroke() {
        if (stroke_backup != null)
            endStroke();
        stroke_backup = copyHeights();
        stroke_x0 = Integer.MAX_VALUE;
        stroke_y0 = Integer.MAX_VALUE;
        stroke_x1 = Integer.MIN_VALUE;
        stroke_y1 = Integer.MIN_VALUE;
    }

    /** @return whether the stroke changed anything, and so left an undo step */
    boolean endStroke() {
        float[][] backup = stroke_backup;
        stroke_backup = null;
        if (backup == null || stroke_x0 > stroke_x1)
            return false;
        // Only the touched rectangle of the backup is worth keeping.
        int w = stroke_x1 - stroke_x0 + 1;
        int h = stroke_y1 - stroke_y0 + 1;
        float[][] saved = new float[h][w];
        for (int y = 0; y < h; y++)
            System.arraycopy(backup[stroke_y0 + y], stroke_x0, saved[y], 0, w);
        undo_steps.push(new UndoStep(stroke_x0, stroke_y0, saved));
        while (undo_steps.size() > MAX_UNDO_STEPS)
            undo_steps.removeLast();
        return true;
    }

    /** Restores the heights from before the last stroke. */
    boolean undo() {
        endStroke();
        UndoStep step = undo_steps.poll();
        if (step == null)
            return false;
        for (int y = 0; y < step.heights().length; y++) {
            float[] row = step.heights()[y];
            System.arraycopy(row, 0, heights[step.y0() + y], step.x0(), row.length);
        }
        markDirty(step.x0(), step.y0(), step.x0() + step.heights()[0].length - 1,
                step.y0() + step.heights().length - 1);
        modified = true;
        flush();
        return true;
    }

    // ---- Brushes ----

    /** Raises (sign 1) or lowers (sign -1) the ground under the brush. */
    void applyHeight(float cx, float cy, float radius, float intensity, int sign, float dt) {
        float amount = sign * intensity * RAISE_RATE * dt;
        forEachCell(cx, cy, radius, (x, y, weight) -> heights[y][x] + amount * weight);
    }

    /**
     * Fills ground below the target height up to it (sign 1), or cuts ground above it down to it (sign -1). At full
     * intensity the middle of the brush lands on the target at once; at half it gets there in a fraction of a second.
     */
    void applyFlatten(float cx, float cy, float radius, float intensity, int sign, float dt, float target) {
        float blend = intensity >= 1f ? 1f
                : 1f - (float) Math.exp(-FLATTEN_RATE * intensity / (1f - intensity) * dt);
        forEachCell(cx, cy, radius, PLATEAU_CORE, (x, y, weight) -> {
            float h = heights[y][x];
            float gap = target - h;
            if (gap * sign <= 0f)
                return h;
            return h + gap * blend * weight;
        });
    }

    /** Smooths the ground toward its local average (sign 1), or sharpens it away from it (sign -1). */
    void applySmooth(float cx, float cy, float radius, float intensity, int sign, float dt) {
        int x0 = Math.max(0, (int) Math.floor(cx - radius) - 1);
        int y0 = Math.max(0, (int) Math.floor(cy - radius) - 1);
        int x1 = Math.min(size - 1, (int) Math.ceil(cx + radius) + 1);
        int y1 = Math.min(size - 1, (int) Math.ceil(cy + radius) + 1);
        // Average from a copy so the result does not depend on the order cells are visited in.
        for (int y = y0; y <= y1; y++)
            System.arraycopy(heights[y], x0, scratch[y], x0, x1 - x0 + 1);
        float rate = intensity * SETTLE_RATE * dt;
        forEachCell(cx, cy, radius, (x, y, weight) -> {
            float h = scratch[y][x];
            float average = localAverage(x, y);
            return h + sign * (average - h) * Math.min(rate * weight, MAX_SETTLE_STEP);
        });
    }

    /** Adds random bumps (sign 1) or dents (sign -1); the pattern is fixed for the length of a stroke. */
    void applyRandom(float cx, float cy, float radius, float intensity, int sign, float dt, int seed) {
        float amount = sign * intensity * RAISE_RATE * dt;
        // Features a few cells wide read as rough ground instead of single cell spikes.
        float feature = Math.max(2f, radius / 3f);
        forEachCell(cx, cy, radius, (x, y, weight) -> heights[y][x] + amount * weight * valueNoise(x / feature,
                y / feature, seed));
    }

    /**
     * Lays a straight ramp from (ax, ay) at height ha to (bx, by) at height hb, as wide as the brush. Building
     * (sign 1) only raises ground up to the ramp, carving (sign -1) only lowers it; intensity blends toward it.
     */
    void applyRamp(float ax, float ay, float ha, float bx, float by, float hb, float radius, float intensity,
            int sign) {
        float dx = bx - ax;
        float dy = by - ay;
        float length_sq = dx * dx + dy * dy;
        int x0 = Math.max(0, (int) Math.floor(Math.min(ax, bx) - radius));
        int y0 = Math.max(0, (int) Math.floor(Math.min(ay, by) - radius));
        int x1 = Math.min(size - 1, (int) Math.ceil(Math.max(ax, bx) + radius));
        int y1 = Math.min(size - 1, (int) Math.ceil(Math.max(ay, by) + radius));
        float core = radius * PLATEAU_CORE;
        boolean changed = false;
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                float t = length_sq > 0f ? Math.clamp(((x - ax) * dx + (y - ay) * dy) / length_sq, 0f, 1f) : 0f;
                float px = ax + dx * t - x;
                float py = ay + dy * t - y;
                float distance = (float) Math.sqrt(px * px + py * py);
                if (distance >= radius)
                    continue;
                float weight = plateau(distance, radius, core);
                float target = ha + (hb - ha) * t;
                float h = heights[y][x];
                float gap = target - h;
                if (gap * sign <= 0f)
                    continue;
                heights[y][x] = clampHeight(x, y, h + gap * weight * intensity);
                changed = true;
            }
        }
        if (changed)
            markDirty(x0, y0, x1, y1);
    }

    @FunctionalInterface
    private interface CellFunction {
        float apply(int x, int y, float weight);
    }

    private void forEachCell(float cx, float cy, float radius, @NonNull CellFunction function) {
        forEachCell(cx, cy, radius, 0f, function);
    }

    /** @param core share of the radius where the weight stays at 1 before falling off */
    private void forEachCell(float cx, float cy, float radius, float core, @NonNull CellFunction function) {
        int x0 = Math.max(0, (int) Math.floor(cx - radius));
        int y0 = Math.max(0, (int) Math.floor(cy - radius));
        int x1 = Math.min(size - 1, (int) Math.ceil(cx + radius));
        int y1 = Math.min(size - 1, (int) Math.ceil(cy + radius));
        if (x0 > x1 || y0 > y1)
            return;
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                float dx = x - cx;
                float dy = y - cy;
                float distance = (float) Math.sqrt(dx * dx + dy * dy);
                if (distance >= radius)
                    continue;
                heights[y][x] = clampHeight(x, y, function.apply(x, y, plateau(distance, radius, radius * core)));
            }
        }
        markDirty(x0, y0, x1, y1);
    }

    /** Smooth bell from 1 at the center to 0 at the edge. */
    private static float falloff(float distance, float radius) {
        return 0.5f * (1f + (float) Math.cos(Math.PI * distance / radius));
    }

    /** 1 out to the core distance, then a smooth bell down to 0 at the edge. */
    private static float plateau(float distance, float radius, float core) {
        return distance <= core ? 1f : falloff(distance - core, radius - core);
    }

    private float localAverage(int x, int y) {
        // 3x3 tent filter: 4 for the center, 2 for edges, 1 for corners.
        float sum = 0f;
        float total = 0f;
        for (int oy = -1; oy <= 1; oy++) {
            int sy = Math.clamp(y + oy, 0, size - 1);
            for (int ox = -1; ox <= 1; ox++) {
                int sx = Math.clamp(x + ox, 0, size - 1);
                float w = (2 - Math.abs(ox)) * (2 - Math.abs(oy));
                sum += scratch[sy][sx] * w;
                total += w;
            }
        }
        return sum / total;
    }

    /** Smoothly interpolated lattice noise in [0, 1]. */
    private static float valueNoise(float x, float y, int seed) {
        int xi = (int) Math.floor(x);
        int yi = (int) Math.floor(y);
        float fx = smooth(x - xi);
        float fy = smooth(y - yi);
        float a = lattice(xi, yi, seed) * (1 - fx) + lattice(xi + 1, yi, seed) * fx;
        float b = lattice(xi, yi + 1, seed) * (1 - fx) + lattice(xi + 1, yi + 1, seed) * fx;
        return a * (1 - fy) + b * fy;
    }

    private static float smooth(float t) {
        return t * t * (3 - 2 * t);
    }

    private static float lattice(int x, int y, int seed) {
        int h = x * 374761393 + y * 668265263 + seed * 144665;
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= h >>> 16;
        return (h & 0xFFFFFF) / (float) 0xFFFFFF;
    }

    /**
     * Keeps a cell's height in range, and the outermost cells on the sea floor. The generator keeps them there, and
     * the world wraps: the last row of the landscape takes its heights from the first, so a raised edge tears open
     * into a wall that shows on the far side of the map.
     */
    private float clampHeight(int x, int y, float h) {
        if (x == 0 || y == 0 || x == size - 1 || y == size - 1)
            return MIN_HEIGHT;
        return Math.clamp(h, MIN_HEIGHT, MAX_HEIGHT);
    }

    /** Puts the outermost cells on the sea floor, as the generator does, for heights saved before it was kept. */
    static void pinEdges(float @NonNull [] @NonNull [] heights) {
        int last = heights.length - 1;
        for (int i = 0; i <= last; i++) {
            heights[0][i] = MIN_HEIGHT;
            heights[last][i] = MIN_HEIGHT;
            heights[i][0] = MIN_HEIGHT;
            heights[i][last] = MIN_HEIGHT;
        }
    }

    // ---- Pushing changes to the renderer ----

    private void markDirty(int x0, int y0, int x1, int y1) {
        modified = true;
        dirty_x0 = Math.min(dirty_x0, x0);
        dirty_y0 = Math.min(dirty_y0, y0);
        dirty_x1 = Math.max(dirty_x1, x1);
        dirty_y1 = Math.max(dirty_y1, y1);
        if (stroke_backup != null) {
            stroke_x0 = Math.min(stroke_x0, x0);
            stroke_y0 = Math.min(stroke_y0, y0);
            stroke_x1 = Math.max(stroke_x1, x1);
            stroke_y1 = Math.max(stroke_y1, y1);
        }
    }

    /** Uploads everything changed since the last flush. Must run on the render thread. */
    void flush() {
        if (dirty_x0 > dirty_x1)
            return;
        int x0 = dirty_x0;
        int y0 = dirty_y0;
        int x1 = dirty_x1;
        int y1 = dirty_y1;
        dirty_x0 = Integer.MAX_VALUE;
        dirty_y0 = Integer.MAX_VALUE;
        dirty_x1 = Integer.MIN_VALUE;
        dirty_y1 = Integer.MIN_VALUE;

        // The render context caches texture bindings, so leave the binding as we found it.
        int previous_texture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            uploadRect(x0, y0, x1, y1);
            growPatchBounds(x0, y0, x1, y1);
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous_texture);
        }
        listener.heightsChanged(x0, y0, x1, y1);
    }

    private void uploadRect(int x0, int y0, int x1, int y1) {
        int w = x1 - x0 + 1;
        int h = y1 - y0 + 1;
        upload.clear();
        for (int y = y0; y <= y1; y++)
            upload.put(heights[y], x0, w);
        upload.flip();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, height_map.getHeightTexture().getHandle());
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, x0, y0, w, h, GL11.GL_RED, GL11.GL_FLOAT, upload);
    }

    private void growPatchBounds(int x0, int y0, int x1, int y1) {
        int cells = HeightMap.GRID_UNITS_PER_PATCH;
        int patches = size / cells;
        float meters_per_cell = HeightMap.METERS_PER_UNIT_GRID;
        // A cell on a patch edge is shared with the patch before it, so widen the range by one patch.
        int px0 = Math.max(0, (x0 - 1) / cells);
        int py0 = Math.max(0, (y0 - 1) / cells);
        int px1 = Math.min(patches - 1, x1 / cells);
        int py1 = Math.min(patches - 1, y1 / cells);
        for (int py = py0; py <= py1; py++) {
            for (int px = px0; px <= px1; px++) {
                LandscapeLeaf leaf = height_map.getLeafFromCoordinates((px + .5f) * cells * meters_per_cell,
                        (py + .5f) * cells * meters_per_cell);
                int min_x = -1, min_y = -1, max_x = -1, max_y = -1;
                float min = Float.POSITIVE_INFINITY;
                float max = Float.NEGATIVE_INFINITY;
                int cx1 = Math.min(size - 1, (px + 1) * cells);
                int cy1 = Math.min(size - 1, (py + 1) * cells);
                for (int y = py * cells; y <= cy1; y++) {
                    for (int x = px * cells; x <= cx1; x++) {
                        float h = heights[y][x];
                        if (h < min) {
                            min = h;
                            min_x = x;
                            min_y = y;
                        }
                        if (h > max) {
                            max = h;
                            max_x = x;
                            max_y = y;
                        }
                    }
                }
                // editHeight writes the value that is already there; we only want its bounds bookkeeping.
                if (min < leaf.bmin_z)
                    height_map.editHeight(min_x, min_y, min);
                if (max > leaf.bmax_z)
                    height_map.editHeight(max_x, max_y, max);
            }
        }
    }
}
