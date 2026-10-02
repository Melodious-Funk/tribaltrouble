package com.oddlabs.tt.mapeditor;

import com.oddlabs.tt.procedural.LandscapeOverride;
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
 * A custom campaign: a description and its levels in the order they are played, each an island as the map editor
 * saves it plus the level's {@link Scenario}.
 *
 * <p>The file is a gzipped stream of the magic, a version, the description, the level count, each level's title (so
 * listing campaigns reads little), and then per level the map as {@link MapFile#writeBody} writes it at
 * {@link #MAP_VERSION}, and the scenario. The campaign's name is the file name.
 */
final class CampaignFile {
    static final String EXTENSION = ".ttcampaign";

    private static final int MAGIC = 0x54_54_43_50; // "TTCP"
    private static final int VERSION = 1;
    private static final int MAX_LEVELS = 1000;
    // The map version the levels' islands are kept in. The file does not say, so it stays at what campaigns were
    // first saved with; a level has its briefing rather than a map's description.
    private static final int MAP_VERSION = 3;

    /** One level: an island and what happens on it. */
    static final class Level {
        @NonNull MapSettings settings;
        float @Nullable [] @Nullable [] heights;
        MapFile.@Nullable Resources resources;
        @Nullable MapPreview preview;
        @NonNull Scenario scenario;

        Level(@NonNull MapSettings settings, float @Nullable [] @Nullable [] heights,
                MapFile.@Nullable Resources resources, @Nullable MapPreview preview, @NonNull Scenario scenario) {
            this.settings = settings;
            this.heights = heights;
            this.resources = resources;
            this.preview = preview;
            this.scenario = scenario;
        }

        /** A level on a saved map, or on an island made from settings when the map is null. */
        static @NonNull Level of(@NonNull MapFile map, @NonNull String title) {
            return new Level(map.settings(), map.heights(), map.resources(), map.preview(),
                    Scenario.createDefault(title));
        }
    }

    /** A saved campaign as listed for choosing, without reading its levels. */
    record Entry(@NonNull String name, @NonNull Path path, @NonNull String description,
                 @NonNull List<@NonNull String> level_titles, @NonNull FileTime modified) {
    }

    @NonNull String name;
    @NonNull String description;
    final @NonNull List<@NonNull Level> levels;

    CampaignFile(@NonNull String name, @NonNull String description, @NonNull List<@NonNull Level> levels) {
        this.name = name;
        this.description = description;
        this.levels = levels;
    }

    static @NonNull Path pathFor(@NonNull Path dir, @NonNull String name) {
        return dir.resolve(name + EXTENSION);
    }

    void save(@NonNull Path dir) throws IOException {
        Files.createDirectories(dir);
        Path target = pathFor(dir, name);
        // Written next to the target and moved into place, so a failed save never leaves half a campaign behind.
        Path temp = Files.createTempFile(dir, name, ".tmp");
        try {
            try (var out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(
                    Files.newOutputStream(temp))))) {
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                out.writeUTF(description);
                out.writeInt(levels.size());
                for (Level level : levels)
                    out.writeUTF(level.scenario.title);
                for (Level level : levels) {
                    new MapFile("", level.settings, level.heights, level.resources, level.preview).writeBody(out, MAP_VERSION);
                    level.scenario.write(out);
                }
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    static @NonNull CampaignFile load(@NonNull Path path) throws IOException {
        try (var in = open(path)) {
            String description = in.readUTF();
            int count = readLevelCount(in);
            for (int i = 0; i < count; i++)
                in.readUTF();
            List<Level> levels = new ArrayList<>(count);
            for (int i = 0; i < count; i++)
                levels.add(readLevel(in));
            return new CampaignFile(nameOf(path), description, levels);
        }
    }

    /** Reads one level, skipping the ones before it. */
    static @NonNull Level loadLevel(@NonNull Path path, int index) throws IOException {
        try (var in = open(path)) {
            in.readUTF();
            int count = readLevelCount(in);
            if (index < 0 || index >= count)
                throw new IOException("The campaign has no level " + (index + 1));
            for (int i = 0; i < count; i++)
                in.readUTF();
            for (int i = 0; i < index; i++)
                readLevel(in);
            return readLevel(in);
        }
    }

    private static @NonNull Level readLevel(@NonNull DataInputStream in) throws IOException {
        MapFile map = MapFile.readBody(in, MAP_VERSION, "");
        Scenario scenario = Scenario.read(in);
        return new Level(map.settings(), map.heights(), map.resources(), map.preview(), scenario);
    }

    /** Lists the saved campaigns, newest first, skipping any file that cannot be read. */
    static @NonNull List<Entry> list(@NonNull Path dir) {
        List<Entry> entries = new ArrayList<>();
        if (!Files.isDirectory(dir))
            return entries;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path path : (Iterable<Path>) files::iterator) {
                if (!path.getFileName().toString().endsWith(EXTENSION) || !Files.isRegularFile(path))
                    continue;
                try (var in = open(path)) {
                    String description = in.readUTF();
                    int count = readLevelCount(in);
                    List<String> titles = new ArrayList<>(count);
                    for (int i = 0; i < count; i++)
                        titles.add(in.readUTF());
                    entries.add(new Entry(nameOf(path), path, description, titles,
                            Files.getLastModifiedTime(path)));
                } catch (IOException e) {
                    IO.println("Skipping unreadable campaign " + path + ": " + e);
                }
            }
        } catch (IOException e) {
            IO.println("Could not list campaigns in " + dir + ": " + e);
        }
        entries.sort((a, b) -> b.modified().compareTo(a.modified()));
        return entries;
    }

    private static int readLevelCount(@NonNull DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > MAX_LEVELS)
            throw new IOException("Bad level count " + count);
        return count;
    }

    private static @NonNull DataInputStream open(@NonNull Path path) throws IOException {
        var in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(path))));
        try {
            if (in.readInt() != MAGIC)
                throw new IOException("Not a campaign file");
            int version = in.readInt();
            if (version < 1 || version > VERSION)
                throw new IOException("Unsupported campaign version " + version);
            return in;
        } catch (IOException e) {
            in.close();
            throw e;
        }
    }

    private static @NonNull String nameOf(@NonNull Path path) {
        String file_name = path.getFileName().toString();
        return file_name.substring(0, file_name.length() - EXTENSION.length());
    }

    /** Builds a level's island from its heights and resources, read from the campaign file when the game loads. */
    record LevelSource(@NonNull String path, int index) implements LandscapeOverride.Source {
        @Override
        public @NonNull LandscapeOverride load() throws IOException {
            Level level = loadLevel(Path.of(path), index);
            MapFile.Resources resources = level.resources;
            return new LandscapeOverride(level.heights, resources == null ? null : new LandscapeOverride.Resources(
                    resources.of(Resource.TREE), resources.of(Resource.PALM), resources.of(Resource.ROCK),
                    resources.of(Resource.IRON)));
        }
    }
}
