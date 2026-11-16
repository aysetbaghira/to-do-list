import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.awt.font.TextAttribute;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TodoApp {

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // fall back to the default look and feel
        }
        SwingUtilities.invokeLater(() -> new TodoFrame().setVisible(true));
    }

    // ------------------------------------------------------------------
    // MODEL
    // ------------------------------------------------------------------

    enum Priority {
        LOW("Low", new Color(0x2E7D32)),
        MEDIUM("Medium", new Color(0xEF6C00)),
        HIGH("High", new Color(0xC62828));

        final String label;
        final Color color;

        Priority(String label, Color color) {
            this.label = label;
            this.color = color;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    enum Filter {
        ALL, ACTIVE, COMPLETED;

        boolean matches(Task task) {
            switch (this) {
                case ACTIVE:    return !task.done;
                case COMPLETED: return task.done;
                default:        return true;
            }
        }
    }

    static class Task {
        String text;
        Priority priority;
        boolean done;
        String created;

        Task(String text, Priority priority) {
            this(text, priority, false,
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        }

        Task(String text, Priority priority, boolean done, String created) {
            this.text = text;
            this.priority = priority;
            this.done = done;
            this.created = created;
        }

        // ---- one line per task, tab separated ----
        String serialize() {
            return escape(text) + "\t" + priority.name() + "\t" + done + "\t" + created;
        }

        static Task deserialize(String line) {
            String[] parts = line.split("\t", -1);
            if (parts.length < 4) return null;
            try {
                return new Task(unescape(parts[0]), Priority.valueOf(parts[1]),
                        Boolean.parseBoolean(parts[2]), parts[3]);
            } catch (IllegalArgumentException ex) {
                return null; // corrupt line, skip it
            }
        }

        private static String escape(String s) {
            return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n");
        }

        private static String unescape(String s) {
            StringBuilder sb = new StringBuilder(s.length());
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '\\' && i + 1 < s.length()) {
                    char next = s.charAt(++i);
                    if (next == 't') sb.append('\t');
                    else if (next == 'n') sb.append('\n');
                    else sb.append(next);
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }
    }

    // ------------------------------------------------------------------
    // VIEW
    // ------------------------------------------------------------------

    /** A panel that always stretches to the width of its JScrollPane. */
    static class StretchPanel extends JPanel implements Scrollable {
        StretchPanel(LayoutManager layout) { super(layout); }

        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle r, int orientation, int direction) { return 16; }
        @Override public int getScrollableBlockIncrement(Rectangle r, int orientation, int direction) { return 64; }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

    static class TodoFrame extends JFrame {

        private static final Path DATA_FILE = Paths.get("tasks.txt");

        private final List<Task> tasks = new ArrayList<>();
        private final JPanel rows = new JPanel();
        private final JTextField input = new JTextField();
        private final JComboBox<Priority> priorityBox = new JComboBox<>(Priority.values());
        private final JLabel status = new JLabel();
        private final JSpinner fontSpinner = new JSpinner(new SpinnerNumberModel(15, 10, 30, 1));

        private JScrollPane scroll;
        private Filter filter = Filter.ALL;
        private int fontSize = 15;

        TodoFrame() {
            super("To-Do List");
            setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
            addWindowListener(new WindowAdapter() {
                @Override public void windowClosing(WindowEvent e) {
                    save();
                    System.exit(0);
                }
            });

            load();

            JPanel root = new JPanel(new BorderLayout(0, 10));
            root.setBorder(new EmptyBorder(12, 12, 12, 12));
            root.add(buildInputBar(), BorderLayout.NORTH);
            root.add(buildTaskList(), BorderLayout.CENTER);
            root.add(buildBottomBar(), BorderLayout.SOUTH);

            setContentPane(root);
            setSize(620, 640);
            setMinimumSize(new Dimension(460, 400));
            setLocationRelativeTo(null);
            refresh();
        }

        // -------------------- layout --------------------

        private JComponent buildInputBar() {
            JPanel bar = new JPanel(new BorderLayout(8, 0));

            input.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 15));
            input.setToolTipText("Type a task and press Enter");
            input.addActionListener(e -> addTask());

            JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
            right.setOpaque(false);

            priorityBox.setSelectedItem(Priority.MEDIUM);
            priorityBox.setToolTipText("Priority");
            priorityBox.setRenderer(new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(JList<?> list, Object value,
                                                              int index, boolean isSelected,
                                                              boolean cellHasFocus) {
                    JLabel label = (JLabel) super.getListCellRendererComponent(
                            list, value, index, isSelected, cellHasFocus);
                    Priority p = (Priority) value;
                    label.setText(p.label);
                    label.setForeground(isSelected ? list.getSelectionForeground() : p.color);
                    return label;
                }
            });
            right.add(priorityBox);

            JButton addButton = new JButton("Add task");
            addButton.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
            addButton.setFocusPainted(false);
            addButton.addActionListener(e -> addTask());
            right.add(addButton);

            bar.add(input, BorderLayout.CENTER);
            bar.add(right, BorderLayout.EAST);
            return bar;
        }

        private JComponent buildTaskList() {
            rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
            rows.setBackground(Color.WHITE);

            JPanel wrapper = new StretchPanel(new BorderLayout());
            wrapper.setBackground(Color.WHITE);
            wrapper.add(rows, BorderLayout.NORTH);

            scroll = new JScrollPane(wrapper);
            scroll.setBorder(BorderFactory.createLineBorder(new Color(0xE0E0E0)));
            scroll.getViewport().setBackground(Color.WHITE);
            scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            scroll.getVerticalScrollBar().setUnitIncrement(16);
            return scroll;
        }

        private JComponent buildBottomBar() {
            ButtonGroup group = new ButtonGroup();

            JPanel filters = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            filters.setOpaque(false);
            filters.add(filterButton("All", Filter.ALL, group));
            filters.add(filterButton("Active", Filter.ACTIVE, group));
            filters.add(filterButton("Completed", Filter.COMPLETED, group));

            JPanel tools = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
            tools.setOpaque(false);
            tools.add(new JLabel("Text size"));

            fontSpinner.setPreferredSize(new Dimension(56, fontSpinner.getPreferredSize().height));
            fontSpinner.addChangeListener(e -> {
                fontSize = (Integer) fontSpinner.getValue();
                refresh();
            });
            tools.add(fontSpinner);

            JButton clearButton = new JButton("Clear completed");
            clearButton.setFocusPainted(false);
            clearButton.addActionListener(e -> clearCompleted());
            tools.add(clearButton);

            JPanel controls = new JPanel(new BorderLayout());
            controls.setOpaque(false);
            controls.add(filters, BorderLayout.WEST);
            controls.add(tools, BorderLayout.EAST);

            status.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            status.setForeground(new Color(0x616161));
            status.setBorder(new EmptyBorder(4, 6, 0, 0));

            JPanel bottom = new JPanel(new BorderLayout());
            bottom.setOpaque(false);
            bottom.add(controls, BorderLayout.CENTER);
            bottom.add(status, BorderLayout.SOUTH);
            return bottom;
        }

        private JRadioButton filterButton(String text, Filter f, ButtonGroup group) {
            JRadioButton button = new JRadioButton(text, filter == f);
            button.setFocusPainted(false);
            button.setOpaque(false);
            button.addActionListener(e -> {
                filter = f;
                refresh();
            });
            group.add(button);
            return button;
        }

        // -------------------- one row per task --------------------

        private JComponent createRow(Task task, int index) {
            JPanel row = new JPanel(new BorderLayout(10, 0));
            row.setBackground(index % 2 == 0 ? Color.WHITE : new Color(0xFAFAFA));
            row.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 5, 0, 0, task.priority.color),
                    BorderFactory.createCompoundBorder(
                            BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0xE8E8E8)),
                            new EmptyBorder(8, 10, 8, 10))));

            JCheckBox check = new JCheckBox();
            check.setOpaque(false);
            check.setSelected(task.done);
            check.setToolTipText(task.done ? "Mark as not done" : "Mark as done");
            check.addActionListener(e -> {
                task.done = check.isSelected();
                refresh();
                save();
            });
            row.add(check, BorderLayout.WEST);

            JLabel title = new JLabel(task.text);
            title.setFont(titleFont(task.done));
            title.setForeground(task.done ? new Color(0x9E9E9E) : new Color(0x212121));

            JLabel meta = new JLabel(task.priority.label + " priority  ·  added " + task.created);
            meta.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, Math.max(10, fontSize - 3)));
            meta.setForeground(new Color(0x9E9E9E));

            JPanel center = new JPanel();
            center.setOpaque(false);
            center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
            title.setAlignmentX(Component.LEFT_ALIGNMENT);
            meta.setAlignmentX(Component.LEFT_ALIGNMENT);
            center.add(title);
            center.add(Box.createVerticalStrut(2));
            center.add(meta);
            row.add(center, BorderLayout.CENTER);

            JButton delete = new JButton("\u2715");
            delete.setToolTipText("Delete task");
            delete.setFocusPainted(false);
            delete.setContentAreaFilled(false);
            delete.setBorder(new EmptyBorder(2, 8, 2, 8));
            delete.setForeground(new Color(0xB71C1C));
            delete.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            delete.addActionListener(e -> {
                tasks.remove(task);
                refresh();
                save();
            });
            row.add(delete, BorderLayout.EAST);

            return row;
        }

        private Font titleFont(boolean done) {
            Font base = new Font(Font.SANS_SERIF, Font.PLAIN, fontSize);
            if (!done) return base;
            Map<TextAttribute, Object> attributes = new HashMap<>();
            attributes.put(TextAttribute.STRIKETHROUGH, TextAttribute.STRIKETHROUGH_ON);
            return base.deriveFont(attributes);
        }

        // -------------------- actions --------------------

        private void addTask() {
            String text = input.getText().trim();
            if (text.isEmpty()) {
                input.requestFocusInWindow();
                return;
            }
            tasks.add(new Task(text, (Priority) priorityBox.getSelectedItem()));
            input.setText("");
            input.requestFocusInWindow();
            refresh();
            save();

            SwingUtilities.invokeLater(() -> {
                JScrollBar bar = scroll.getVerticalScrollBar();
                bar.setValue(bar.getMaximum());
            });
        }

        private void clearCompleted() {
            int before = tasks.size();
            tasks.removeIf(t -> t.done);
            if (before == tasks.size()) {
                JOptionPane.showMessageDialog(this,
                        "There are no completed tasks to clear.",
                        "Nothing to clear", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            refresh();
            save();
        }

        // -------------------- refresh --------------------

        private void refresh() {
            int scrollPos = scroll == null ? 0 : scroll.getVerticalScrollBar().getValue();

            rows.removeAll();
            int visible = 0;
            for (Task task : tasks) {
                if (!filter.matches(task)) continue;
                rows.add(createRow(task, visible));
                visible++;
            }

            if (visible == 0) {
                JLabel empty = new JLabel(emptyMessage(), SwingConstants.CENTER);
                empty.setFont(new Font(Font.SANS_SERIF, Font.ITALIC, 14));
                empty.setForeground(new Color(0x9E9E9E));
                empty.setBorder(new EmptyBorder(40, 10, 40, 10));
                empty.setAlignmentX(Component.CENTER_ALIGNMENT);
                rows.add(empty);
            }

            rows.revalidate();
            rows.repaint();
            if (scroll != null) scroll.getVerticalScrollBar().setValue(scrollPos);
            updateStatus();
        }

        private String emptyMessage() {
            switch (filter) {
                case ACTIVE:    return "Nothing left to do. Nice!";
                case COMPLETED: return "No completed tasks yet.";
                default:        return "Your list is empty — add a task above.";
            }
        }

        private void updateStatus() {
            int done = 0;
            for (Task task : tasks) if (task.done) done++;
            if (tasks.isEmpty()) {
                status.setText("No tasks yet — add your first one above.");
            } else {
                int remaining = tasks.size() - done;
                status.setText(done + " of " + tasks.size() + " tasks completed"
                        + (remaining > 0 ? "  ·  " + remaining + " remaining" : "  ·  all done!"));
            }
        }

        // -------------------- persistence --------------------

        private void save() {
            List<String> lines = new ArrayList<>();
            for (Task task : tasks) lines.add(task.serialize());
            try {
                Files.write(DATA_FILE, lines, StandardCharsets.UTF_8);
            } catch (IOException e) {
                JOptionPane.showMessageDialog(this,
                        "Could not save tasks:\n" + e.getMessage(),
                        "Save error", JOptionPane.ERROR_MESSAGE);
            }
        }

        private void load() {
            tasks.clear();
            if (!Files.exists(DATA_FILE)) return;
            try {
                for (String line : Files.readAllLines(DATA_FILE, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) continue;
                    Task task = Task.deserialize(line);
                    if (task != null) tasks.add(task);
                }
            } catch (IOException e) {
                JOptionPane.showMessageDialog(this,
                        "Could not load tasks:\n" + e.getMessage(),
                        "Load error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }
}