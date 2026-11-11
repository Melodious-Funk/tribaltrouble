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
    /** A row's own result, team A's: its teams have none of their own. */
    private static final String WIN = "\"result\":\"win\"";
    private static final String LOSS = "\"result\":\"loss\"";
    private static final String DRAW = "\"result\":\"draw\"";
    /** A row's lineup: its first "players", before those of its teams. */
    private static final Pattern PLAYERS = Pattern.compile("\"players\":\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern KEY = Pattern.compile("s(\\d+)-(\\d+)");

    private Recordings() {
    }

    /**
     * A run folder: its name, when its results were written last (epoch milliseconds), its lineup (its first game's:
     * the games of a run differ in the seats, not the players), how many games it has, how many of them team A won,
     * lost and drew, and how many ended at the time limit.
     */
    record Run(@NonNull Path dir, @NonNull String name, long modified, @NonNull String players, int games, int wins,
               int losses, int draws, int timeouts) {
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
        Matcher players = PLAYERS.matcher(rows);
        return new Run(absolute, String.valueOf(absolute.getFileName()), Files.getLastModifiedTime(
                results).toMillis(), players.find() ? players.group(1) : "", games, count(rows, WIN), count(rows,
                        LOSS), count(rows, DRAW), count(rows, TIMEOUT_END));
    }

    /**
     * A lineup in short, for a column or a line of the HUD: each player's name, and of its parameters only the file
     * it names first, without folders and ending ({@code neural:net=a/b/g000020.bin,seed=3 vs easy} is
     * {@code neural g000020 vs easy}); parameters that name no file stay as they are.
     */
    static @NonNull String shortLineup(@NonNull String players) {
        List<String> words = new ArrayList<>();
        for (String word : players.trim().split("\\s+")) {
            int colon = word.indexOf(':');
            if (colon < 0) {
                words.add(word);
                continue;
            }
            String shown = word;
            for (String parameter : word.substring(colon + 1).split(",")) {
                String value = parameter.substring(parameter.indexOf('=') + 1);
                int slash = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
                if (slash >= 0) {
                    String file = value.substring(slash + 1);
                    int dot = file.lastIndexOf('.');
                    shown = word.substring(0, colon) + " " + (dot > 0 ? file.substring(0, dot) : file);
                    break;
                }
            }
            words.add(shown);
        }
        return String.join(" ", words);
    }

    /**
     * The folder of the run the game of {@code file} is of: where its game file is (in the run's g/ or replay
     * folder), else the run of its name in the folder of recordings, or that folder itself when it is the run.
     */
    static @NonNull Path runDir(@NonNull WatchFile file) {
        if (file.gameFile() != null) {
            Path run = Path.of(file.gameFile()).getParent().getParent();
            if (run != null) {
                return run;
            }
        }
        Path folder = Path.of(file.recordings());
        return Files.isRegularFile(folder.resolve(RESULTS)) ? folder : folder.resolve(file.job().run());
    }

    /** Game {@code key} of the run in {@code run_dir}, from its result row; null without one (or without the run). */
    static @Nullable Game game(@NonNull Path run_dir, @NonNull String key) {
        try {
            for (Game game : games(run_dir)) {
                if (game.key().equals(key)) {
                    return game;
                }
            }
        } catch (IOException e) {
            // no rows to read: as a game without one
        }
        return null;
    }

    /** How a game ended, in words: {@code at the time limit}, {@code eliminated}, else as its row says. */
    static @NonNull String endText(@NonNull Game game) {
        return switch (game.end()) {
            case "timeout" -> "at the time limit";
            case "elim" -> "eliminated";
            default -> game.end();
        };
    }

    /** A game's length as a clock time of its own game time: {@code 30:00}, {@code 1:02:03}. */
    static @NonNull String lengthText(@NonNull Game game) {
        long seconds = Math.round(game.seconds());
        return seconds >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60,
                seconds % 60) : String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
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
