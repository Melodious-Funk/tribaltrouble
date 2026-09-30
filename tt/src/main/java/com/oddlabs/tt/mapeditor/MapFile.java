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
 * A saved editor map: the generator settings plus, once the terrain has been edited, the whole height map.
 *
 * <p>Maps without heights are regenerated from their settings when loaded. The file is a gzipped stream of the
 * magic, a version, the settings and an optional square grid of heights in meters.
 */
record MapFile(@NonNull String name, @NonNull MapSettings settings, float @Nullable [] @Nullable [] heights) {
    static final String EXTENSION = ".ttmap";

    private static final int MAGIC = 0x54_54_4D_50; // "TTMP"
    private static final int VERSION = 1;
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
                writeSettings(out, settings);
                out.writeBoolean(heights != null);
                if (heights != null) {
                    out.writeInt(heights.length);
                    for (float[] row : heights) {
                        for (float height : row) {
                            out.writeFloat(height);
                        }
                    }
                }
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    static @NonNull MapFile load(@NonNull Path path) throws IOException {
        try (var in = open(path)) {
            MapSettings settings = readSettings(in);
            float[][] heights = null;
            if (in.readBoolean()) {
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
            return new MapFile(nameOf(path), settings, heights);
        }
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
                    MapSettings settings = readSettings(in);
                    boolean edited = in.readBoolean();
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

    private static @NonNull DataInputStream open(@NonNull Path path) throws IOException {
        var in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(path))));
        try {
            if (in.readInt() != MAGIC)
                throw new IOException("Not a map file");
            int version = in.readInt();
            if (version != VERSION)
                throw new IOException("Unsupported map version " + version);
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
