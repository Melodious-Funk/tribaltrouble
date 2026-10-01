package com.oddlabs.tt.mapeditor;

import com.oddlabs.tt.landscape.HeightMap;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * A saved editor map: the generator settings plus, once edited, the whole height map and the resources.
 *
 * <p>What was never edited is generated again from the settings when the map is loaded. The file is a gzipped
 * stream of the magic, a version, the settings, whether heights, resources and a preview follow, an optional
 * preview picture, an optional square grid of heights in meters, and optionally the grid positions of each kind
 * of resource. The preview comes before the heights so browsing maps reads little of each file. Version 1 files
 * have no resources, and versions before 3 no preview, nor the flags for them.
 */
record MapFile(@NonNull String name, @NonNull MapSettings settings, float @Nullable [] @Nullable [] heights,
               @Nullable Resources resources, @Nullable MapPreview preview) {
    static final String EXTENSION = ".ttmap";
    /** The newest map version, which {@link #writeBody} writes. */
    static final int BODY_VERSION = 3;

    private static final int MAGIC = 0x54_54_4D_50; // "TTMP"
    private static final int VERSION = BODY_VERSION;

    /** Grid positions of every resource, one list per kind in {@link Resource} order. */
    record Resources(@NonNull List<int @NonNull []> @NonNull [] positions) {
        @NonNull List<int @NonNull []> of(@NonNull Resource kind) {
            return positions[kind.ordinal()];
        }
    }
    private static final int MAX_NAME_LENGTH = 48;

    /** A saved map as listed in the load dialog, without reading its heights. */
    record Entry(@NonNull String name, @NonNull Path path, @NonNull MapSettings settings, boolean edited,
                 @NonNull FileTime modified) {
    }

    static int getMaxNameLength() {
        return MAX_NAME_LENGTH;
    }

    /** Whether a name can be used as a file name on every platform the game ships on. */
    static boolean isValidName(@NonNull String name) {
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH || name.startsWith(".") || name.endsWith(".")
                || name.endsWith(" "))
            return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < ' ' || "/\\:*?\"<>|".indexOf(c) != -1)
                return false;
        }
        return true;
    }

    /** Height map cells along one side of the island the settings generate. */
    static int gridSize(@NonNull MapSettings settings) {
        return settings.getMetersPerWorld() / HeightMap.METERS_PER_UNIT_GRID;
    }

    static @NonNull Path pathFor(@NonNull Path dir, @NonNull String name) {
        return dir.resolve(name + EXTENSION);
    }

    void save(@NonNull Path dir) throws IOException {
        Files.createDirectories(dir);
        Path target = pathFor(dir, name);
        // Write next to the target and move it into place, so a failed save never leaves half a map behind.
        Path temp = Files.createTempFile(dir, name, ".tmp");
        try {
            try (var out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(
                    Files.newOutputStream(temp))))) {
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                writeBody(out);
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** Writes everything after the magic and version, as a campaign file also keeps its levels. */
    void writeBody(@NonNull DataOutputStream out) throws IOException {
        writeSettings(out, settings);
        out.writeBoolean(heights != null);
        out.writeBoolean(resources != null);
        out.writeBoolean(preview != null);
        if (preview != null) {
            out.writeShort(preview.size());
            out.write(preview.rgb());
        }
        if (heights != null) {
            out.writeInt(heights.length);
            for (float[] row : heights) {
                for (float height : row) {
                    out.writeFloat(height);
                }
            }
        }
        if (resources != null) {
            for (Resource kind : Resource.values()) {
                List<int[]> positions = resources.of(kind);
                out.writeInt(positions.size());
                for (int[] position : positions) {
                    out.writeShort(position[0]);
                    out.writeShort(position[1]);
                }
            }
        }
    }

    static @NonNull MapFile load(@NonNull Path path) throws IOException {
        try (var in = open(path)) {
            return readBody(in, readVersion(in), nameOf(path));
        }
    }

    /** Reads what {@link #writeBody} wrote, for a file of the given map version. */
    static @NonNull MapFile readBody(@NonNull DataInputStream in, int version, @NonNull String name)
            throws IOException {
        MapSettings settings = readSettings(in);
        boolean has_heights = in.readBoolean();
        boolean has_resources = version >= 2 && in.readBoolean();
        boolean has_preview = version >= 3 && in.readBoolean();
        MapPreview preview = has_preview ? readPreview(in) : null;
        float[][] heights = null;
        if (has_heights) {
            int grid_size = in.readInt();
            if (grid_size != gridSize(settings))
                throw new IOException("Height map does not fit the island size");
            heights = new float[grid_size][grid_size];
            for (float[] row : heights) {
                for (int x = 0; x < row.length; x++) {
                    row[x] = in.readFloat();
                }
            }
        }
        Resources resources = null;
        if (has_resources) {
            int grid_size = gridSize(settings);
            @SuppressWarnings("unchecked")
            List<int[]>[] positions = new List[Resource.values().length];
            for (int k = 0; k < positions.length; k++) {
                int count = in.readInt();
                if (count < 0 || count > grid_size * grid_size)
                    throw new IOException("Bad resource count " + count);
                positions[k] = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    int x = in.readShort();
                    int y = in.readShort();
                    if (x < 0 || y < 0 || x >= grid_size || y >= grid_size)
                        throw new IOException("Resource outside the island");
                    positions[k].add(new int[]{x, y});
                }
            }
            resources = new Resources(positions);
        }
        if (preview == null && heights != null)
            preview = MapPreview.render(heights, settings, resources);
        return new MapFile(name, settings, heights, resources, preview);
    }

    /**
     * The map's preview: the saved one, else one drawn from its saved heights.
     *
     * @return the preview, or null when the map keeps neither a preview nor heights
     */
    static @Nullable MapPreview loadPreview(@NonNull Path path) throws IOException {
        boolean has_heights;
        try (var in = open(path)) {
            int version = readVersion(in);
            readSettings(in);
            has_heights = in.readBoolean();
            if (version >= 2)
                in.readBoolean(); // Whether resources follow; they come after the preview.
            boolean has_preview = version >= 3 && in.readBoolean();
            if (has_preview)
                return readPreview(in);
        }
        // Older maps have no preview of their own; drawing one needs the heights.
        return has_heights ? load(path).preview() : null;
    }

    private static @NonNull MapPreview readPreview(@NonNull DataInputStream in) throws IOException {
        int size = in.readShort();
        if (size <= 0 || size > MapPreview.MAX_SIZE)
            throw new IOException("Bad preview size " + size);
        byte[] rgb = new byte[size * size * 3];
        in.readFully(rgb);
        return new MapPreview(size, rgb);
    }

    /** Lists the saved maps, skipping any file that cannot be read. */
    static @NonNull List<Entry> list(@NonNull Path dir) {
        List<Entry> entries = new ArrayList<>();
        if (!Files.isDirectory(dir))
            return entries;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path path : (Iterable<Path>) files::iterator) {
                if (!path.getFileName().toString().endsWith(EXTENSION) || !Files.isRegularFile(path))
                    continue;
                try (var in = open(path)) {
                    int version = readVersion(in);
                    MapSettings settings = readSettings(in);
                    boolean edited = in.readBoolean();
                    if (version >= 2 && in.readBoolean())
                        edited = true;
                    entries.add(new Entry(nameOf(path), path, settings, edited, Files.getLastModifiedTime(path)));
                } catch (IOException e) {
                    IO.println("Skipping unreadable map " + path + ": " + e);
                }
            }
        } catch (IOException e) {
            IO.println("Could not list maps in " + dir + ": " + e);
        }
        entries.sort((a, b) -> b.modified().compareTo(a.modified()));
        return entries;
    }

    /** The version after the magic, if this build can read it. */
    private static int readVersion(@NonNull DataInputStream in) throws IOException {
        int version = in.readInt();
        if (version < 1 || version > VERSION)
            throw new IOException("Unsupported map version " + version);
        return version;
    }

    private static @NonNull DataInputStream open(@NonNull Path path) throws IOException {
        var in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(path))));
        try {
            if (in.readInt() != MAGIC)
                throw new IOException("Not a map file");
            return in;
        } catch (IOException e) {
            in.close();
            throw e;
        }
    }

    private static void writeSettings(@NonNull DataOutputStream out, @NonNull MapSettings settings)
            throws IOException {
        out.writeInt(settings.size());
        out.writeInt(settings.terrain());
        out.writeInt(settings.hills());
        out.writeInt(settings.trees());
        out.writeInt(settings.supplies());
        out.writeInt(settings.seed());
    }

    private static @NonNull MapSettings readSettings(@NonNull DataInputStream in) throws IOException {
        return new MapSettings(in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt());
    }

    private static @NonNull String nameOf(@NonNull Path path) {
        String file_name = path.getFileName().toString();
        return file_name.substring(0, file_name.length() - EXTENSION.length());
    }
}
