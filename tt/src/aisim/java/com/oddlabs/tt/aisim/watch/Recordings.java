package com.oddlabs.tt.aisim.watch;

import com.oddlabs.tt.aisim.analysis.Runs;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The recordings {@code watch} can show: the runs of a folder of runs (as {@code aisim/runs} is one), newest first,
 * and a run's games, from its result rows. Both sides of {@code watch} read them: the parent JVM to find the game to
 * start with when none is named, and the game for its list of games (G, {@link GamesPanel}).
 */
final class Recordings {
    private static final String RESULTS = "results.jsonl";
    private static final String TIMEOUT_END = "\"end\":\"timeout\"";
    private static final Pattern KEY = Pattern.compile("s(\\d+)-(\\d+)");

    private Recordings() {
    }

    /**
     * A run folder: its name, when its results were written last (epoch milliseconds), how many games it has and how
     * many of them ended at the time limit.
     */
    record Run(@NonNull Path dir, @NonNull String name, long modified, int games, int timeouts) {
    }

    /**
     * A game of a run, from its result row: its lineup, team A's result ({@code win}, {@code loss}, {@code draw};
     * {@code failed} for a game that did not end), how it ended ({@code timeout}, {@code elim}, ...), its length in
     * game seconds, each team's units and kills at the end (team A's first), and whether its game file is there
     * (without it the game plays from the run's job alone, unverified).
     */
    record Game(@NonNull String key, @NonNull String players, @NonNull String result, @NonNull String end,
                double seconds, @NonNull String units, @NonNull String kills, boolean file) {
        boolean timeout() {
            return end.equals("timeout");
        }
    }

    /** Which games a list shows. */
    enum Filter {
        ALL("all games"),
        TIMEOUT("games that ended at the time limit"),
        DECIDED("games won or lost");

        private final @NonNull String label;

        Filter(@NonNull String label) {
            this.label = label;
        }

        @NonNull
        String label() {
            return label;
        }

        /** The filter as {@code --filter} names it. */
        @NonNull
        String argument() {
            return name().toLowerCase(Locale.ROOT);
        }

        @NonNull
        Filter next() {
            return values()[(ordinal() + 1) % values().length];
        }

        boolean shows(@NonNull Game game) {
            return switch (this) {
                case ALL -> true;
                case TIMEOUT -> game.timeout();
                case DECIDED -> game.result().equals("win") || game.result().equals("loss");
            };
        }

        /** The filter {@code --filter} names, or null for a name that is none. */
        static @Nullable Filter of(@NonNull String argument) {
            for (Filter filter : values()) {
                if (filter.argument().equals(argument)) {
                    return filter;
                }
            }
            return null;
        }
    }

    /**
     * The runs of {@code folder}, newest first: its folders that have result rows, or {@code folder} itself when it is
     * a run. A folder that is not there has none.
     */
    static @NonNull List<Run> runs(@NonNull Path folder) throws IOException {
        List<Run> runs = new ArrayList<>();
        if (Files.isRegularFile(folder.resolve(RESULTS))) {
            runs.add(run(folder));
        } else if (Files.isDirectory(folder)) {
            try (var dirs = Files.list(folder)) {
                for (Path dir : dirs.filter(d -> Files.isRegularFile(d.resolve(RESULTS))).toList()) {
                    runs.add(run(dir));
                }
            }
        }
        runs.sort(Comparator.comparingLong(Run::modified).reversed().thenComparing(Run::name));
        return runs;
    }

    private static @NonNull Run run(@NonNull Path dir) throws IOException {
        Path results = dir.resolve(RESULTS);
        // counted in the text: a folder of many runs holds megabytes of rows, which only an opened run has parsed
        String rows = Files.readString(results, StandardCharsets.UTF_8);
        int games = (int) rows.lines().filter(line -> !line.isBlank()).count();
        Path absolute = dir.toAbsolutePath().normalize();
        return new Run(absolute, String.valueOf(absolute.getFileName()), Files.getLastModifiedTime(
                results).toMillis(), games, count(rows, TIMEOUT_END));
    }

    private static int count(@NonNull String text, @NonNull String part) {
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) {
            count++;
        }
        return count;
    }

    /** The games of the run in {@code run_dir}, by seed and side. */
    static @NonNull List<Game> games(@NonNull Path run_dir) throws IOException {
        List<Game> games = new ArrayList<>();
        for (Map<String, Object> row : Runs.readJsonl(run_dir.resolve(RESULTS))) {
            if (!(row.get("key") instanceof String key)) {
                continue;
            }
            String players = row.get("players") instanceof String text ? text : "";
            String result = row.get("result") instanceof String text ? text : "failed";
            String end = row.get("end") instanceof String text ? text : "";
            double seconds = row.get("t") instanceof Number number ? number.doubleValue() : 0;
            games.add(new Game(key, players, result, end, seconds, perTeam(row, "units"), perTeam(row, "kills"),
                    Files.isRegularFile(Runs.gameFile(run_dir, key))));
        }
        games.sort(Comparator.comparingLong((Game game) -> order(game.key())).thenComparing(Game::key));
        return games;
    }

    /** Number {@code field} of each team of {@code row}, as "250 - 187"; empty for a row without teams. */
    private static @NonNull String perTeam(@NonNull Map<String, Object> row, @NonNull String field) {
        if (!(row.get("teams") instanceof List<?> teams)) {
            return "";
        }
        List<String> numbers = new ArrayList<>();
        for (Object team : teams) {
            if (team instanceof Map<?, ?> map && map.get(field) instanceof Number number) {
                numbers.add(String.valueOf(number.longValue()));
            }
        }
        return String.join(" - ", numbers);
    }

    /** Sorts keys by seed, then side; a key of another form last. */
    private static long order(@NonNull String key) {
        Matcher matcher = KEY.matcher(key);
        if (!matcher.matches()) {
            return Long.MAX_VALUE;
        }
        try {
            return Long.parseLong(matcher.group(1)) * 1000 + Long.parseLong(matcher.group(2));
        } catch (NumberFormatException e) {
            return Long.MAX_VALUE;
        }
    }

    /** A game to start with. */
    record Pick(@NonNull Path run_dir, @NonNull String key) {
    }

    /**
     * The first game of {@code run_dir} that {@code filter} shows, one with its game file before one without; null if
     * it shows none.
     */
    static @Nullable Pick first(@NonNull Path run_dir, @NonNull Filter filter) throws IOException {
        Game without_file = null;
        for (Game game : games(run_dir)) {
            if (!filter.shows(game)) {
                continue;
            }
            if (game.file()) {
                return new Pick(run_dir, game.key());
            }
            if (without_file == null) {
                without_file = game;
            }
        }
        return without_file == null ? null : new Pick(run_dir, without_file.key());
    }

    /** The first game {@code filter} shows of the newest run of {@code folder} that has one; null if none has. */
    static @Nullable Pick newest(@NonNull Path folder, @NonNull Filter filter) throws IOException {
        for (Run run : runs(folder)) {
            Pick pick = first(run.dir(), filter);
            if (pick != null) {
                return pick;
            }
        }
        return null;
    }
}
