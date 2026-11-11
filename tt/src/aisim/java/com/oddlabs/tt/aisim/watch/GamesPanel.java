package com.oddlabs.tt.aisim.watch;

import com.oddlabs.tt.font.Font;
import com.oddlabs.tt.font.TextLineRenderer;
import com.oddlabs.tt.gui.MouseButton;
import com.oddlabs.tt.gui.Skin;
import com.oddlabs.tt.render.GUIRenderer;
import com.oddlabs.util.Color;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The list of recorded games (G): the runs of the folder of recordings, newest first, and, of the run you click,
 * its games, which F filters (all, those that ended at the time limit, those won or lost). A click on a game watches
 * it: the program starts again on it ({@link WatchGame#open}), since one start of the client plays one game. The wheel
 * scrolls; Backspace goes back to the runs.
 */
final class GamesPanel extends HudPanel {
    private static final int PADDING = 12;
    private static final int COLUMN_GAP = 18;
    /** A row is this much higher than a line of text. */
    private static final int ROW_GAP = 2;
    /** The room between the title, the rows and the last line. */
    private static final int PART_GAP = 6;
    private static final int WIDTH = 980;
    private static final int MAX_ROWS = 24;
    private static final int MIN_ROWS = 4;
    /** The room the list leaves at the window's edges at least. */
    private static final int MARGIN = 8;
    private static final int WHEEL_ROWS = 3;
    private static final Vector4fc BACKGROUND = new Vector4f(.03f, .03f, .05f, .98f);
    private static final Vector4fc HOVER_BAND = new Vector4f(1f, 1f, 1f, .16f);
    private static final Vector4fc WATCHED_BAND = new Vector4f(.3f, .6f, 1f, .22f);
    private static final String BACK = "< all runs";
    private static final String CLOSE = "G or Esc closes";
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);
    /** The columns of the runs and of a run's games; the first takes the room the others leave. */
    private static final String[] RUN_COLUMNS = {"run", "last game", "games", "at the time limit"};
    private static final String[] RUN_SAMPLES = {"", "0000-00-00 00:00", "00000", "00000"};
    private static final String[] GAME_COLUMNS = {"game", "team A", "ended", "length", "units", "kills", ""};
    private static final String[] GAME_SAMPLES = {"s000000-00", "failed", "at the time limit", "0:00:00", "000 - 000 - 000", "000 - 000 - 000", "no game file"};

    private final @NonNull WatchGame game;
    private final @NonNull Font font;
    private final @NonNull Path folder;
    /** The run and game on the screen: lit in the lists. */
    private final @Nullable Path watched_run;
    private final @NonNull String watched_key;
    private @NonNull List<Recordings.Run> runs = List.of();
    /** The run whose games are listed, or null while the runs are. */
    private Recordings.@Nullable Run open;
    private @NonNull List<Recordings.Game> all_games = List.of();
    /** The open run's games the filter shows. */
    private @NonNull List<Recordings.Game> games = List.of();
    private Recordings.@NonNull Filter filter;
    /** The first row shown. */
    private int scroll;
    /** The row under the mouse, of those shown; -1 for none. */
    private int hovered = -1;
    private int rows = MAX_ROWS;
    private boolean loaded;
    private @Nullable String problem;

    GamesPanel(@NonNull WatchGame game) {
        this.game = game;
        this.font = Skin.getSkin().getEditFont();
        WatchFile file = game.file();
        this.folder = Path.of(file.recordings());
        Path game_file = file.gameFile() == null ? null : Path.of(file.gameFile()).getParent();
        // a run's game file is in its g/ folder
        this.watched_run = game_file == null ? null : game_file.getParent();
        this.watched_key = game.job().key();
        Recordings.Filter named = Recordings.Filter.of(file.filter());
        this.filter = named == null ? Recordings.Filter.ALL : named;
        setDim(WIDTH, height(rows));
    }

    private int line() {
        return font.getHeight() + ROW_GAP;
    }

    /** The panel's height with {@code count} rows: the title, the column names, the rows and the two last lines. */
    private int height(int count) {
        return 2 * PADDING + 2 * PART_GAP + (count + 4) * line();
    }

    /** Shown: reads the folder again (runs may have come since), and lists the watched game's run the first time. */
    @Override
    protected void doAdd() {
        super.doAdd();
        hovered = -1;
        try {
            problem = null;
            runs = Recordings.runs(folder);
            Recordings.Run reopen = open;
            if (!loaded) {
                loaded = true;
                for (Recordings.Run run : runs) {
                    if (watchedRun(run)) {
                        reopen = run;
                    }
                }
            }
            if (reopen != null) {
                open(reopen);
            }
        } catch (IOException e) {
            problem = "cannot read " + folder + ": " + e.getMessage();
        }
    }

    /** Lists {@code run}'s games, the watched one in view. */
    private void open(Recordings.@NonNull Run run) throws IOException {
        all_games = Recordings.games(run.dir());
        open = run;
        applyFilter();
        for (int i = 0; i < games.size(); i++) {
            if (watched(run, games.get(i))) {
                scroll = Math.max(0, i + 1 - rows / 2);
            }
        }
        clampScroll();
    }

    /** Whether {@code run} is the run of the game on the screen: by its folder, or by name without a game file. */
    private boolean watchedRun(Recordings.@NonNull Run run) {
        return watched_run != null ? run.dir().equals(watched_run) : run.name().equals(game.job().run());
    }

    private boolean watched(Recordings.@NonNull Run run, Recordings.@NonNull Game listed) {
        return watchedRun(run) && listed.key().equals(watched_key);
    }

    private void applyFilter() {
        List<Recordings.Game> shown = new ArrayList<>();
        for (Recordings.Game listed : all_games) {
            if (filter.shows(listed)) {
                shown.add(listed);
            }
        }
        games = shown;
        scroll = 0;
    }

    /** F: the next filter of a run's games. */
    void cycleFilter() {
        filter = filter.next();
        applyFilter();
    }

    /** Backspace: from a run's games back to the runs. */
    void back() {
        open = null;
        all_games = List.of();
        games = List.of();
        scroll = 0;
        hovered = -1;
    }

    /** The rows of the list now: the runs, or the way back and the open run's games. */
    private int count() {
        return open == null ? runs.size() : games.size() + 1;
    }

    private void clampScroll() {
        scroll = Math.clamp(scroll, 0, Math.max(0, count() - rows));
    }

    /**
     * Sizes and places the list for a window of {@code width} x {@code height} (the HUD's layout, every frame): in the
     * middle, with as many rows as fit under {@code top} (the jump box, which is drawn over the HUD).
     */
    void place(int width, int height, int top) {
        int fits_width = Math.min(WIDTH, width - 2 * MARGIN);
        int fits = Math.clamp((top - MARGIN - height(0)) / line(), MIN_ROWS, MAX_ROWS);
        if (fits_width != getWidth() || fits != rows) {
            rows = fits;
            setDim(fits_width, height(rows));
            clampScroll();
        }
        setPos((width - getWidth()) / 2, Math.max(MARGIN, Math.min((height + getHeight()) / 2, top) - getHeight()));
    }

    /** The bottom of shown row {@code row}, in the panel's own coordinates (y grows upwards). */
    private int rowY(int row) {
        return getHeight() - PADDING - PART_GAP - (row + 3) * line();
    }

    /** The shown row at panel-local {@code y}, or -1 above and below the rows. */
    private int rowAt(int y) {
        int top = rowY(0) + line();
        if (y >= top || y < rowY(rows - 1)) {
            return -1;
        }
        int row = (top - 1 - y) / line();
        return scroll + row < count() ? row : -1;
    }

    /** The middle of shown row {@code row} in the panel's own coordinates, for scripted clicks. */
    int rowMiddleY(int row) {
        return rowY(row) + line() / 2;
    }

    /** Where each column starts: the first at the left, the others as wide as their widest text, at the right. */
    private int @NonNull [] columns(@NonNull String @NonNull [] names, @NonNull String @NonNull [] samples) {
        int[] x = new int[names.length];
        int right = getWidth() - PADDING;
        for (int column = names.length - 1; column > 0; column--) {
            right -= Math.max(font.getWidth(names[column]), font.getWidth(samples[column]));
            x[column] = right;
            right -= COLUMN_GAP;
        }
        x[0] = PADDING;
        return x;
    }

    @Override
    protected void renderGeometry(@NonNull GUIRenderer renderer) {
        renderer.drawColoredQuad(0, 0, getWidth(), getHeight(), BACKGROUND);
        int right = getWidth() - PADDING;
        int title_y = getHeight() - PADDING - font.getHeight();
        Recordings.Run run = open;
        String title = run == null ? "Recorded games in " + folder : "Run " + run.name() + lineup();
        int close_x = right - font.getWidth(CLOSE);
        TextLineRenderer.render(renderer, font, title, PADDING, title_y, 0, close_x - COLUMN_GAP, Color.WHITE);
        TextLineRenderer.render(renderer, font, CLOSE, close_x, title_y, 0, right, TextPanel.DIM);
        String[] names = run == null ? RUN_COLUMNS : GAME_COLUMNS;
        int[] x = columns(names, run == null ? RUN_SAMPLES : GAME_SAMPLES);
        int names_y = getHeight() - PADDING - PART_GAP - 2 * line();
        renderer.drawColoredQuad(PADDING / 2f, names_y - ROW_GAP / 2f, getWidth() - PADDING, line(),
                TextPanel.HEADING_BAND);
        for (int column = 0; column < names.length; column++) {
            TextLineRenderer.render(renderer, font, names[column], x[column], names_y, 0, right, TextPanel.LABEL);
        }
        for (int row = 0; row < rows && scroll + row < count(); row++) {
            int index = scroll + row;
            int y = rowY(row);
            boolean lit = run == null ? watchedRun(runs.get(index)) : index > 0 && watched(run, games.get(
                    index - 1));
            if (lit || row == hovered) {
                renderer.drawColoredQuad(PADDING / 2f, y - ROW_GAP / 2f, getWidth() - PADDING, line(),
                        row == hovered ? HOVER_BAND : WATCHED_BAND);
            }
            if (run == null) {
                renderRun(renderer, runs.get(index), x, y);
            } else if (index == 0) {
                TextLineRenderer.render(renderer, font, BACK, PADDING, y, 0, right, TextPanel.WARN);
            } else {
                renderGame(renderer, games.get(index - 1), x, y, lit);
            }
        }
        int count = count();
        String shown = count == 0 ? "no runs with results in this folder" : "rows " + (scroll + 1) + " to " + Math.min(
                count, scroll + rows) + " of " + count + " (the wheel scrolls)";
        String keys = problem != null ? problem : run == null ? "click a run for its games" : "F: " + filter.label() + " (" + games.size() + " of " + all_games.size() + ")    Backspace: all runs";
        TextLineRenderer.render(renderer, font, keys, PADDING, PADDING + line(), 0, right,
                problem != null ? TextPanel.BAD : TextPanel.WARN);
        String click = run == null ? "" : "    click a game to watch it: the viewer starts again on it";
        TextLineRenderer.render(renderer, font, shown + click, PADDING, PADDING, 0, right, TextPanel.DIM);
    }

    /** The open run's lineup, after its name: every game of a run has the same, by rotation. */
    private @NonNull String lineup() {
        return all_games.isEmpty() ? "" : ": " + all_games.get(0).players();
    }

    private void renderRun(@NonNull GUIRenderer renderer, Recordings.@NonNull Run run, int @NonNull [] x, int y) {
        int right = getWidth() - PADDING;
        String when = WHEN.format(Instant.ofEpochMilli(run.modified()).atZone(ZoneId.systemDefault()));
        TextLineRenderer.render(renderer, font, run.name(), x[0], y, 0, x[1] - COLUMN_GAP, Color.WHITE);
        TextLineRenderer.render(renderer, font, when, x[1], y, 0, right, TextPanel.DIM);
        TextLineRenderer.render(renderer, font, String.valueOf(run.games()), x[2], y, 0, right, Color.WHITE);
        TextLineRenderer.render(renderer, font, String.valueOf(run.timeouts()), x[3], y, 0, right,
                run.timeouts() > 0 ? TextPanel.WARN : TextPanel.DIM);
    }

    private void renderGame(@NonNull GUIRenderer renderer, Recordings.@NonNull Game listed, int @NonNull [] x,
            int y, boolean lit) {
        int right = getWidth() - PADDING;
        String key = lit ? listed.key() + "   (on the screen)" : listed.key();
        TextLineRenderer.render(renderer, font, key, x[0], y, 0, x[1] - COLUMN_GAP, Color.WHITE);
        Vector4fc result_color = switch (listed.result()) {
            case "win" -> TextPanel.GOOD;
            case "draw" -> TextPanel.WARN;
            default -> TextPanel.BAD;
        };
        TextLineRenderer.render(renderer, font, listed.result(), x[1], y, 0, right, result_color);
        String end = switch (listed.end()) {
            case "timeout" -> "at the time limit";
            case "elim" -> "eliminated";
            default -> listed.end();
        };
        TextLineRenderer.render(renderer, font, end, x[2], y, 0, x[3] - COLUMN_GAP, Color.WHITE);
        long seconds = Math.round(listed.seconds());
        String length = seconds >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600,
                seconds / 60 % 60, seconds % 60) : String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
        TextLineRenderer.render(renderer, font, length, x[3], y, 0, right, Color.WHITE);
        TextLineRenderer.render(renderer, font, listed.units(), x[4], y, 0, x[5] - COLUMN_GAP, Color.WHITE);
        TextLineRenderer.render(renderer, font, listed.kills(), x[5], y, 0, x[6] - COLUMN_GAP, Color.WHITE);
        if (!listed.file()) {
            TextLineRenderer.render(renderer, font, "no game file", x[6], y, 0, right, TextPanel.DIM);
        }
    }

    /** A click on a run lists its games; on the first row of a run's games goes back; on a game watches it. */
    @Override
    protected void mouseClicked(@NonNull MouseButton button, int x, int y, int clicks) {
        int row = rowAt(y);
        if (button != MouseButton.LEFT || row < 0) {
            return;
        }
        int index = scroll + row;
        Recordings.Run run = open;
        try {
            if (run == null) {
                open(runs.get(index));
            } else if (index == 0) {
                back();
            } else {
                game.open(run.dir(), games.get(index - 1).key(), filter);
            }
        } catch (IOException e) {
            problem = "cannot read that run: " + e.getMessage();
        }
        hovered = -1;
    }

    @Override
    protected void mouseMoved(int x, int y) {
        hovered = rowAt(localY(y));
    }

    @Override
    protected void mouseExited() {
        super.mouseExited();
        hovered = -1;
    }

    /** The wheel: up towards the first row, down towards the last. */
    @Override
    protected void mouseScrolled(int amount) {
        scroll -= amount * WHEEL_ROWS;
        clampScroll();
        hovered = -1;
    }
}
