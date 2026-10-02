package com.oddlabs.tt.mapeditor;

import com.oddlabs.tt.font.Font;
import com.oddlabs.tt.form.MessageForm;
import com.oddlabs.tt.form.QuestionForm;
import com.oddlabs.tt.gui.CancelButton;
import com.oddlabs.tt.gui.CheckBox;
import com.oddlabs.tt.gui.ColumnInfo;
import com.oddlabs.tt.gui.DateLabel;
import com.oddlabs.tt.gui.EditLine;
import com.oddlabs.tt.gui.FocusDirection;
import com.oddlabs.tt.gui.Form;
import com.oddlabs.tt.gui.GUIRoot;
import com.oddlabs.tt.gui.HorizButton;
import com.oddlabs.tt.gui.Label;
import com.oddlabs.tt.gui.MultiColumnComboBox;
import com.oddlabs.tt.gui.Row;
import com.oddlabs.tt.gui.Skin;
import com.oddlabs.tt.gui.TextBox;
import com.oddlabs.tt.guievent.RowListener;
import com.oddlabs.tt.input.GameAction;
import com.oddlabs.tt.input.InputEvent;
import com.oddlabs.tt.input.InputPhase;
import com.oddlabs.tt.input.Key;
import com.oddlabs.util.Color;
import org.joml.Vector4fc;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static com.oddlabs.tt.gui.Placement.BOTTOM_LEFT;
import static com.oddlabs.tt.gui.Placement.BOTTOM_RIGHT;
import static com.oddlabs.tt.gui.Placement.LEFT_MID;
import static com.oddlabs.tt.gui.Placement.RIGHT_MID;
import static com.oddlabs.tt.gui.Placement.RIGHT_TOP;

/**
 * Browses folders for saved files of one kind, maps or campaigns, showing a preview of the one selected, to open
 * one or delete one. {@link FileType} says what the files are and how to show them.
 *
 * <p>Folders are walked through the list, by mouse or keyboard: the folder above leads the list, then the folders
 * in this one, then its files, whichever column the list is sorted by. Buttons go back and forward through the
 * folders shown, up a folder, and to the kind's own folder, the home folder or the folder of the file last opened.
 * A folder, or a file to open, can also be typed into the box at the top.
 *
 * <p>Keys in the list: Enter opens, Backspace or Left goes up, Right opens a folder, and typing jumps to the
 * entry starting with what was typed. Anywhere in the dialog: Alt+Left and Alt+Right go back and forward, Alt+Up
 * goes up, and Ctrl+L moves to the box at the top.
 *
 * @param <E> a file as its kind lists it
 */
final class FileBrowserDialog<E> extends Form {
    /** A kind of file to browse, and how the dialog lists and shows it. */
    interface FileType<E> {
        /** The file name ending of the kind, as typed paths are told apart by. */
        @NonNull String extension();

        /** The files of the kind in a folder, leaving out those that cannot be read. */
        @NonNull List<E> list(@NonNull Path dir);

        @NonNull Path path(@NonNull E entry);

        @NonNull String name(@NonNull E entry);

        /** The columns after the name; the last one is the date, which folders show too. */
        @NonNull ColumnInfo @NonNull [] columns();

        /** An entry's cells in those columns. */
        @NonNull List<@NonNull Value> values(@NonNull E entry);

        /** @return the preview, or null when the file has none */
        @Nullable MapPreview preview(@NonNull E entry) throws IOException;

        /** The line under the preview. */
        @NonNull String info(@NonNull E entry);

        /** The text in the box under that, or null for none at all. */
        @Nullable String description(@NonNull E entry);

        /** The kind's own folder, the place button goes to, or null when the game has none. */
        @Nullable Path home();

        @NonNull String homeButton();

        @NonNull String deleteConfirm(@NonNull E entry);

        @NonNull String deleteFailed(@NonNull String reason);

        /** Told when a typed path is neither a folder nor a file of the kind. */
        @NonNull String notFound();

        /** Told when a typed file of the kind cannot be read. */
        @NonNull String unreadable();

        /** Where the dialog for this kind was last, while the game runs. */
        @NonNull Memory memory();
    }

    /** A cell of an entry: its text, and the number it sorts by, then by the text. */
    record Value(@NonNull String text, long number) {
        static @NonNull Value of(@NonNull String text) {
            return new Value(text, 0);
        }

        static @NonNull Value date(long millis) {
            return new Value(new DateLabel(millis, Skin.getSkin().getMultiColumnComboBoxData().font()).getContents(),
                    millis);
        }
    }

    /** The folder last shown and the folder of the file last opened, kept for each kind while the game runs. */
    static final class Memory {
        private @Nullable Path last_dir;
        private @Nullable Path last_opened_dir;
    }

    private static final int BUTTON_WIDTH = 100;
    private static final int NAME_WIDTH = 230;
    // Tall enough for the preview, the line under it, and some of the description beside it.
    private static final int LIST_HEIGHT = 380;
    private static final int PREVIEW_SIZE = 256;
    private static final String PARENT = "..";
    // Letters typed within this long of each other make one name to jump to.
    private static final long TYPE_AHEAD_MILLIS = 1000;
    private static final Vector4fc ERROR_COLOR = Color.argb4v(0xFF_FF_70_60);
    // Room in the description box: a map's description, or a campaign's and its level titles.
    private static final int MAX_DESCRIPTION_CHARS = 8192;

    // Remembered while the game runs, for every kind: whether hidden folders show.
    private static boolean show_hidden;

    /** What a line of the list is; lines sort in this order, whichever column sorts them. */
    private enum Kind {
        PARENT,
        FOLDER,
        FILE
    }

    /**
     * A line in the list: the folder above, a folder, or a file.
     *
     * @param path the folder or file, or null for the folder above the top of the file system: the list of drives
     */
    private record Item<E>(@NonNull Kind kind, @Nullable Path path, @NonNull String name, @Nullable E entry) {
    }

    /** A cell sorting by a key of its own, after the kind of its line so folders stay above type. */
    private final class Cell extends Label {
        private final @NonNull Kind kind;
        private final @NonNull String text_key;
        private final long number_key;

        Cell(@NonNull CharSequence text, @NonNull Font font, int width, @NonNull Kind kind, @NonNull String text_key,
                long number_key) {
            super(text, font, width);
            this.kind = kind;
            this.text_key = text_key;
            this.number_key = number_key;
        }

        @Override
        public int compareTo(@NonNull Label o) {
            if (!(o instanceof FileBrowserDialog<?>.Cell other))
                return super.compareTo(o);
            int by_kind = kind.compareTo(other.kind);
            // The list shows its sorted order reversed when sorted the other way; the kinds must not be.
            if (by_kind != 0)
                return list.isSortedDescending() ? by_kind : -by_kind;
            int by_number = Long.compare(number_key, other.number_key);
            return by_number != 0 ? by_number : text_key.compareTo(other.text_key);
        }
    }

    private final @NonNull GUIRoot gui_root;
    private final @NonNull FileType<E> type;
    private final @NonNull Memory memory;
    private final @NonNull Consumer<@NonNull E> load;
    private final @NonNull EditLine editline_dir;
    private final @NonNull MultiColumnComboBox<Item<E>> list;
    private final @NonNull MapPreviewView preview;
    private final @NonNull Label label_info;
    private final @NonNull TextBox box_description;
    private final @NonNull Label label_status;
    private final @NonNull String hint;
    private final @NonNull HorizButton button_back;
    private final @NonNull HorizButton button_forward;
    private final @NonNull HorizButton button_up;
    private final @NonNull HorizButton button_last;
    private final @NonNull HorizButton button_load;
    private final @NonNull HorizButton button_delete;
    // Previews already read, by file; empty when the map has none.
    private final Map<Path, Optional<MapPreview>> previews = new HashMap<>();
    // Folders gone through, to go back and forward to; an empty one stands for the list of drives.
    private final Deque<Optional<Path>> back = new ArrayDeque<>();
    private final Deque<Optional<Path>> forward = new ArrayDeque<>();
    private final List<Row<Item<E>, Label>> rows = new ArrayList<>();
    // The folder shown, or null for the list of drives.
    private @Nullable Path dir;
    private @NonNull String typed = "";
    private long typed_at;

    /**
     * @param start_dir the folder to show when no other was browsed before
     * @param caption   the dialog's title
     * @param action    the label of the button that takes the selected file
     */
    FileBrowserDialog(@NonNull GUIRoot gui_root, @NonNull FileType<E> type, @NonNull Path start_dir,
            @NonNull String caption, @NonNull String action, @NonNull Consumer<@NonNull E> load) {
        super(caption);
        this.gui_root = gui_root;
        this.type = type;
        this.memory = type.memory();
        this.load = load;
        int spacing = Skin.getSkin().getFormData().objectSpacing();
        Font font = Skin.getSkin().getEditFont();

        Label label_dir = new Label(MapEditor.i18n("folder"), font);
        HorizButton button_go = new HorizButton(MapEditor.i18n("go_button"), BUTTON_WIDTH);
        button_go.addMouseClickListener((_, _, _, _) -> browseTyped());

        button_back = new HorizButton(MapEditor.i18n("browse_back"), 80);
        button_back.addMouseClickListener((_, _, _, _) -> goBack());
        button_forward = new HorizButton(MapEditor.i18n("browse_forward"), 90);
        button_forward.addMouseClickListener((_, _, _, _) -> goForward());
        button_up = new HorizButton(MapEditor.i18n("browse_up"), 60);
        button_up.addMouseClickListener((_, _, _, _) -> goUp());
        HorizButton button_place = new HorizButton(type.homeButton(), 100);
        button_place.addMouseClickListener((_, _, _, _) -> goToPlace(type.home()));
        button_place.setDisabled(type.home() == null);
        HorizButton button_home = new HorizButton(MapEditor.i18n("browse_home"), 80);
        button_home.addMouseClickListener((_, _, _, _) -> goToPlace(Path.of(System.getProperty("user.home"))));
        button_last = new HorizButton(MapEditor.i18n("browse_last"), 110);
        button_last.addMouseClickListener((_, _, _, _) -> goToPlace(memory.last_opened_dir));
        CheckBox check_hidden = new CheckBox(show_hidden, MapEditor.i18n("browse_hidden"));
        check_hidden.addCheckBoxListener(this::showHidden);

        ColumnInfo[] more = type.columns();
        ColumnInfo[] columns = new ColumnInfo[1 + more.length];
        columns[0] = new ColumnInfo(MapEditor.i18n("column_name"), NAME_WIDTH);
        System.arraycopy(more, 0, columns, 1, more.length);
        list = new MultiColumnComboBox<>(gui_root, columns, LIST_HEIGHT);
        list.addRowListener(new RowListener<>() {
            @Override
            public void rowChosen(@NonNull Item<E> item) {
                showSelected(item);
            }

            @Override
            public void rowDoubleClicked(@NonNull Item<E> item) {
                open(item);
            }
        });
        list.addInputListener(this::listKey);

        preview = new MapPreviewView(PREVIEW_SIZE);
        label_info = new Label("", font, PREVIEW_SIZE);
        box_description = new TextBox(PREVIEW_SIZE, LIST_HEIGHT - PREVIEW_SIZE - label_info.getHeight() - 2 * spacing,
                font, MAX_DESCRIPTION_CHARS);

        // The folder box spans the list and the preview.
        int dir_width = list.getWidth() + spacing + PREVIEW_SIZE - label_dir.getWidth() - button_go.getWidth() - 2 * spacing;
        editline_dir = new EditLine(dir_width, 1024);
        editline_dir.addEnterListener(_ -> browseTyped());

        button_load = new HorizButton(action, BUTTON_WIDTH);
        button_load.addMouseClickListener((_, _, _, _) -> {
            Item<E> selected = list.getSelected();
            if (selected != null)
                open(selected);
        });
        button_delete = new HorizButton(MapEditor.i18n("delete_button"), BUTTON_WIDTH);
        button_delete.addMouseClickListener((_, _, _, _) -> {
            Item<E> selected = list.getSelected();
            if (selected != null && selected.entry() != null) {
                E entry = selected.entry();
                gui_root.addModalForm(new QuestionForm(type.deleteConfirm(entry), (_, _, _, _) -> delete(entry)));
            }
        });
        HorizButton button_cancel = new CancelButton(BUTTON_WIDTH);
        button_cancel.addMouseClickListener((_, _, _, _) -> cancel());

        // The hint shares the bottom line with the buttons, under the list.
        int buttons_width = 3 * BUTTON_WIDTH + 2 * spacing;
        hint = MapEditor.i18n("browse_hint");
        label_status = new Label(hint, font, list.getWidth() + PREVIEW_SIZE - buttons_width - spacing);

        addChild(label_dir);
        addChild(editline_dir);
        addChild(button_go);
        addChild(button_back);
        addChild(button_forward);
        addChild(button_up);
        addChild(button_place);
        addChild(button_home);
        addChild(button_last);
        addChild(check_hidden);
        addChild(list);
        addChild(preview);
        addChild(label_info);
        addChild(box_description);
        addChild(label_status);
        addChild(button_load);
        addChild(button_delete);
        addChild(button_cancel);
        label_dir.place();
        editline_dir.place(label_dir, RIGHT_MID);
        button_go.place(editline_dir, RIGHT_MID);
        button_back.place(label_dir, BOTTOM_LEFT);
        button_forward.place(button_back, RIGHT_MID);
        button_up.place(button_forward, RIGHT_MID);
        button_place.place(button_up, RIGHT_MID, Skin.getSkin().getFormData().sectionSpacing());
        button_home.place(button_place, RIGHT_MID);
        button_last.place(button_home, RIGHT_MID);
        check_hidden.place(button_last, RIGHT_MID, Skin.getSkin().getFormData().sectionSpacing());
        list.place(button_back, BOTTOM_LEFT);
        preview.place(list, RIGHT_TOP);
        label_info.place(preview, BOTTOM_LEFT);
        box_description.place(label_info, BOTTOM_LEFT, spacing);
        button_cancel.place(preview, BOTTOM_RIGHT, list.getHeight() - PREVIEW_SIZE + spacing);
        button_load.place(button_cancel, LEFT_MID);
        button_delete.place(button_load, LEFT_MID);
        label_status.place(list, BOTTOM_LEFT, spacing + (button_cancel.getHeight() - label_status.getHeight()) / 2);
        compileCanvas();
        centerPos();

        Path start = memory.last_dir != null && Files.isDirectory(memory.last_dir) ? memory.last_dir : start_dir;
        if (!navigate(start, false, null))
            navigate(Path.of(System.getProperty("user.home")), false, null);
    }

    @Override
    public void setFocus(@NonNull FocusDirection direction) {
        if (direction == FocusDirection.BACKWARD) {
            super.setFocus(direction);
        } else {
            list.focusRows();
        }
    }

    /** Keys that move about the folders from anywhere in the dialog. */
    @Override
    protected void handleInput(@NonNull InputEvent event) {
        if (event.getPhase() == InputPhase.PRESSED || event.getPhase() == InputPhase.REPEAT) {
            Key key = event.getKeyCode();
            boolean done = true;
            if (event.isAltDown() && key == Key.LEFT)
                goBack();
            else if (event.isAltDown() && key == Key.RIGHT)
                goForward();
            else if (event.isAltDown() && key == Key.UP)
                goUp();
            else if (event.isControlDown() && key == Key.L)
                editline_dir.setFocus();
            else
                done = false;
            if (done) {
                event.consume();
                return;
            }
        }
        super.handleInput(event);
    }

    /** Keys for the list: up a folder, into one, or to the entry starting with what is typed. */
    private void listKey(@NonNull InputEvent event) {
        if (event.getPhase() != InputPhase.PRESSED && event.getPhase() != InputPhase.REPEAT)
            return;
        if (event.isAltDown() || event.isControlDown() || event.isMetaDown())
            return;
        if (event.consumeAction(GameAction.UI_BACKSPACE) || event.consumeAction(GameAction.UI_NAV_LEFT)) {
            goUp();
            event.consume();
        } else if (event.consumeAction(GameAction.UI_NAV_RIGHT)) {
            Item<E> selected = list.getSelected();
            if (selected != null && selected.kind() == Kind.FOLDER)
                open(selected);
            event.consume();
        } else {
            char c = event.getCharacter();
            if (c != 0 && c != ' ' && !Character.isISOControl(c)) {
                typeAhead(c);
                event.consume();
            }
        }
    }

    private void showHidden(boolean marked) {
        show_hidden = marked;
        Item<E> selected = list.getSelected();
        refresh(selected != null ? selected.path() : null);
    }

    /** Opens the folder or file typed into the box at the top, relative to the folder shown. */
    private void browseTyped() {
        String text = editline_dir.getContents().trim();
        Path typed_path;
        try {
            if (text.equals("~") || text.startsWith("~/"))
                text = System.getProperty("user.home") + text.substring(1);
            typed_path = Path.of(text);
            if (!typed_path.isAbsolute())
                typed_path = dir != null ? dir.resolve(typed_path) : typed_path.toAbsolutePath();
        } catch (InvalidPathException _) {
            refused(type.notFound());
            return;
        }
        if (text.isEmpty()) {
            refused(type.notFound());
        } else if (Files.isDirectory(typed_path)) {
            if (navigate(typed_path, true, null))
                list.focusRows();
            else
                editline_dir.triggerError();
        } else if (Files.isRegularFile(typed_path) && typed_path.getFileName().toString().endsWith(type.extension())) {
            openFile(typed_path.toAbsolutePath().normalize());
        } else {
            refused(type.notFound());
        }
    }

    private void refused(@NonNull String reason) {
        editline_dir.triggerError();
        setStatus(reason, true);
    }

    /** Opens a file typed in, as if it had been chosen in the list. */
    private void openFile(@NonNull Path file) {
        Path folder = file.getParent();
        for (E entry : type.list(folder)) {
            if (type.path(entry).toAbsolutePath().normalize().equals(file)) {
                memory.last_opened_dir = folder;
                remove();
                load.accept(entry);
                return;
            }
        }
        refused(type.unreadable());
    }

    private void goBack() {
        Optional<Path> target = back.pollFirst();
        if (target == null)
            return;
        Path from = dir;
        if (navigate(target.orElse(null), false, null))
            forward.addFirst(Optional.ofNullable(from));
        updateButtons();
        list.focusRows();
    }

    private void goForward() {
        Optional<Path> target = forward.pollFirst();
        if (target == null)
            return;
        Path from = dir;
        if (navigate(target.orElse(null), false, null))
            back.addFirst(Optional.ofNullable(from));
        updateButtons();
        list.focusRows();
    }

    /** Shows the folder above, with the one just left selected; above the top are the drives, if more than one. */
    private void goUp() {
        if (dir == null)
            return;
        Path parent = dir.getParent();
        if ((parent != null || hasDrives()) && navigate(parent, true, dir))
            list.focusRows();
    }

    private void goToPlace(@Nullable Path place) {
        if (place == null)
            return;
        try {
            Files.createDirectories(place);
        } catch (IOException _) {
            // Shown as a folder that cannot be opened.
        }
        if (navigate(place, true, null))
            list.focusRows();
    }

    /** Whether the file system has more than one top: the drives of Windows. */
    private static boolean hasDrives() {
        int count = 0;
        for (Path _ : FileSystems.getDefault().getRootDirectories())
            count++;
        return count > 1;
    }

    /**
     * Shows a folder.
     *
     * @param target   the folder, or null for the list of drives
     * @param remember whether going back returns to the folder shown before
     * @param select   the folder or file to select, or null for the first line
     * @return whether the folder could be shown
     */
    private boolean navigate(@Nullable Path target, boolean remember, @Nullable Path select) {
        Path folder = target != null ? target.toAbsolutePath().normalize() : null;
        if (folder != null) {
            if (!Files.isDirectory(folder)) {
                setStatus(type.notFound(), true);
                return false;
            }
            // Listing it is the only sure way to know it can be read.
            try (Stream<Path> _ = Files.list(folder)) {
                // Readable.
            } catch (IOException | SecurityException e) {
                setStatus(MapEditor.i18n("browse_unreadable", folder.toString()), true);
                return false;
            }
        }
        if (remember && !Objects.equals(folder, dir)) {
            back.addFirst(Optional.ofNullable(dir));
            forward.clear();
        }
        dir = folder;
        if (folder != null)
            memory.last_dir = folder;
        editline_dir.set(folder != null ? folder.toString() : "");
        editline_dir.setIndex(editline_dir.length());
        setStatus(hint, false);
        refresh(select);
        updateButtons();
        return true;
    }

    private void updateButtons() {
        button_back.setDisabled(back.isEmpty());
        button_forward.setDisabled(forward.isEmpty());
        button_up.setDisabled(dir == null || (dir.getParent() == null && !hasDrives()));
        button_last.setDisabled(memory.last_opened_dir == null || !Files.isDirectory(memory.last_opened_dir));
    }

    private void setStatus(@NonNull String text, boolean error) {
        label_status.set(text);
        label_status.setColor(error ? ERROR_COLOR : Label.DISABLED_COLOR);
    }

    /** Lists the folder shown again, selecting a folder or file in it, or the first line. */
    private void refresh(@Nullable Path select) {
        list.clear();
        rows.clear();
        list.setOffsetY(0);
        Font font = Skin.getSkin().getMultiColumnComboBoxData().font();
        if (dir == null) {
            for (Path drive : FileSystems.getDefault().getRootDirectories())
                addFolder(new Item<>(Kind.FOLDER, drive, drive.toString(), null), font);
        } else {
            Path parent = dir.getParent();
            if (parent != null || hasDrives())
                addFolder(new Item<>(Kind.PARENT, parent, PARENT, null), font);
            for (Path sub : folders(dir))
                addFolder(new Item<>(Kind.FOLDER, sub, sub.getFileName().toString(), null), font);
            for (E entry : type.list(dir)) {
                String name = type.name(entry);
                List<Label> cells = new ArrayList<>();
                cells.add(cell(name, font, NAME_WIDTH, Kind.FILE, name, 0));
                for (Value value : type.values(entry))
                    cells.add(cell(value.text(), font, 0, Kind.FILE, value.text(), value.number()));
                addRow(new Row<>(cells, new Item<>(Kind.FILE, type.path(entry), name, entry)));
            }
        }
        Row<Item<E>, Label> chosen = null;
        for (Row<Item<E>, Label> row : rows) {
            Item<E> item = row.getContentObject();
            if (select != null && item != null && select.equals(item.path()) && item.kind() != Kind.PARENT)
                chosen = row;
        }
        if (chosen != null) {
            list.selectRow(chosen);
            list.showRow(chosen);
            list.clickedRow();
        } else if (list.getSize() > 0) {
            list.selectFirst();
        } else {
            showSelected(null);
        }
    }

    private void addFolder(@NonNull Item<E> item, @NonNull Font font) {
        long modified = 0;
        if (item.path() != null && item.kind() != Kind.PARENT) {
            try {
                modified = Files.getLastModifiedTime(item.path()).toMillis();
            } catch (IOException _) {
                // Shown without a date.
            }
        }
        String name = item.kind() == Kind.PARENT ? PARENT : item.name() + (item.name().endsWith("/")
                || item.name().endsWith("\\") ? "" : "/");
        List<Label> cells = new ArrayList<>();
        cells.add(cell(name, font, NAME_WIDTH, item.kind(), item.name(), 0));
        int blanks = type.columns().length - 1;
        for (int i = 0; i < blanks; i++)
            cells.add(cell("", font, 0, item.kind(), item.name(), 0));
        cells.add(item.kind() == Kind.PARENT ? cell("", font, 0, item.kind(), "", 0) : dateCell(modified, font,
                item.kind()));
        addRow(new Row<>(cells, item));
    }

    private void addRow(@NonNull Row<Item<E>, Label> row) {
        rows.add(row);
        list.addRow(row);
    }

    private @NonNull Label cell(@NonNull String text, @NonNull Font font, int width, @NonNull Kind kind,
            @NonNull String key, long number) {
        return new Cell(text, font, width > 0 ? width : font.getWidth(text), kind, key.toLowerCase(Locale.ROOT),
                number);
    }

    private @NonNull Label dateCell(long millis, @NonNull Font font, @NonNull Kind kind) {
        String text = new DateLabel(millis, font).getContents();
        return new Cell(text, font, font.getWidth(text), kind, "", millis);
    }

    /** The folders inside one, by name, leaving out hidden ones unless they are asked for. */
    private static @NonNull List<Path> folders(@NonNull Path dir) {
        List<Path> folders = new ArrayList<>();
        try (Stream<Path> paths = Files.list(dir)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                String name = path.getFileName().toString();
                boolean hidden = name.startsWith(".") || isHidden(path);
                if ((show_hidden || !hidden) && Files.isDirectory(path))
                    folders.add(path);
            }
        } catch (IOException | SecurityException e) {
            IO.println("Could not list folders in " + dir + ": " + e);
        }
        folders.sort((a, b) -> a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString()));
        return folders;
    }

    private static boolean isHidden(@NonNull Path path) {
        try {
            return Files.isHidden(path);
        } catch (IOException _) {
            return false;
        }
    }

    /** Jumps to the next line whose name starts with the letters typed lately. */
    private void typeAhead(char c) {
        long now = System.currentTimeMillis();
        String next = now - typed_at <= TYPE_AHEAD_MILLIS ? typed + c : String.valueOf(c);
        typed_at = now;
        // Typing one letter again goes on to the next line starting with it.
        boolean repeat = next.length() > 1 && next.chars().allMatch(ch -> ch == next.charAt(0));
        typed = repeat ? String.valueOf(c) : next;
        String prefix = typed.toLowerCase(Locale.ROOT);

        List<Row<Item<E>, Label>> shown = new ArrayList<>(rows);
        shown.sort(null);
        if (!list.isSortedDescending())
            shown = shown.reversed();
        Item<E> selected = list.getSelected();
        int start = 0;
        for (int i = 0; i < shown.size(); i++) {
            if (Objects.equals(shown.get(i).getContentObject(), selected))
                start = typed.length() == 1 ? i + 1 : i;
        }
        for (int n = 0; n < shown.size(); n++) {
            Row<Item<E>, Label> row = shown.get((start + n) % shown.size());
            Item<E> item = row.getContentObject();
            if (item != null && item.kind() != Kind.PARENT && item.name().toLowerCase(Locale.ROOT).startsWith(
                    prefix)) {
                list.selectRow(row);
                list.showRow(row);
                list.clickedRow();
                return;
            }
        }
    }

    private void showSelected(@Nullable Item<E> item) {
        E entry = item != null ? item.entry() : null;
        button_delete.setDisabled(entry == null);
        if (entry == null) {
            preview.show(null, item != null ? MapEditor.i18n("folder_preview") : "");
            label_info.set("");
            box_description.setText("");
            box_description.setOffsetY(0);
            return;
        }
        Optional<MapPreview> found = previews.computeIfAbsent(type.path(entry), path -> {
            try {
                return Optional.ofNullable(type.preview(entry));
            } catch (IOException e) {
                IO.println("Could not read the preview of " + path + ": " + e);
                return Optional.empty();
            }
        });
        preview.show(found.orElse(null), MapEditor.i18n("no_preview"));
        label_info.set(type.info(entry));
        String description = type.description(entry);
        box_description.setText(description != null ? description : "");
        box_description.setOffsetY(0);
    }

    /** Opens a folder, or takes a file. */
    private void open(@NonNull Item<E> item) {
        switch (item.kind()) {
            case PARENT -> goUp();
            case FOLDER -> {
                if (navigate(item.path(), true, null))
                    list.focusRows();
            }
            case FILE -> {
                E entry = item.entry();
                if (entry == null)
                    return;
                memory.last_opened_dir = dir;
                remove();
                load.accept(entry);
            }
        }
    }

    private void delete(@NonNull E entry) {
        try {
            Files.deleteIfExists(type.path(entry));
        } catch (IOException e) {
            gui_root.addModalForm(new MessageForm(type.deleteFailed(String.valueOf(e.getMessage()))));
        }
        previews.remove(type.path(entry));
        // Keep the place in the list: select the line that took the deleted one's place.
        Path next = null;
        boolean found = false;
        List<Row<Item<E>, Label>> shown = new ArrayList<>(rows);
        shown.sort(null);
        if (!list.isSortedDescending())
            shown = shown.reversed();
        for (Row<Item<E>, Label> row : shown) {
            Item<E> item = row.getContentObject();
            if (item == null)
                continue;
            if (found) {
                next = item.path();
                break;
            }
            found = entry.equals(item.entry());
        }
        refresh(next);
    }
}
