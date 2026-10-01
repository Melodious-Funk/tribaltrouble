package com.oddlabs.tt.procedural;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.Serializable;
import java.util.List;

/**
 * Heights and resources an island is built with in place of the generated ones, as a map editor saves them. What
 * is left out is generated as usual.
 *
 * @param heights height map cells in meters, indexed [y][x], one per grid unit of the island
 * @param resources grid positions of every resource, placed instead of the generated ones
 */
public record LandscapeOverride(float @Nullable [] @NonNull [] heights, @Nullable Resources resources) {
    /** Grid positions ({x, y}) of each kind of resource. */
    public record Resources(@NonNull List<int @NonNull []> trees, @NonNull List<int @NonNull []> palm_trees,
                            @NonNull List<int @NonNull []> rocks, @NonNull List<int @NonNull []> iron) {
    }

    /**
     * Where a generator gets its override when it builds the world. It travels with the generator from the server
     * to the client, so it names the data rather than carrying it.
     */
    public interface Source extends Serializable {
        @NonNull LandscapeOverride load() throws IOException;
    }
}
