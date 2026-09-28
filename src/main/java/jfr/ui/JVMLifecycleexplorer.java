package jfr.ui;

import jfr.content.*;
import jfr.engine.*;
import jfr.model.*;

import javax.swing.*;
import javax.swing.border.*;
import javax.swing.event.*;
import javax.swing.table.*;
import javax.swing.text.*;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import java.util.stream.*;

public class JVMLifecycleexplorer extends JFrame {

    private static final int MAX_EVENTS = 60_000;

    private final JTextArea codeEditor = new JTextArea();
    private final JTextArea console = new JTextArea();
    private final EventTableModel model = new EventTableModel();
    private JTable table;
    private TableRowSorter<EventTableModel> sorter;
    private final JEditorPane explainPane = new JEditorPane();
    private final StackPanel stackPanel = new StackPanel();
    private final JTextArea rawArea = new JTextArea();
    private final TimelinePanel timeline = new TimelinePanel();
    private final HeapPanel heapPanel = new HeapPanel();
    private final JEditorPane summaryPane = new JEditorPane();
    private final DefaultTableModel hotModel = ro("Method (your code)", "Samples", "\u2248 Time", "Share");
    private final DefaultTableModel allocModel = ro("Class allocated", "Samples", "\u2248 Bytes");
    private final DefaultTableModel jitModel = ro("Method", "Compiler tier", "At");
    private final DefaultTreeModel treeModel = new DefaultTreeModel(new DefaultMutableTreeNode("Run a program to see the class loaders"));
    private final JTree loaderTree = new JTree(treeModel);
    private final JComboBox<String> classCombo = new JComboBox<>();
    private final JCheckBox verboseBox = new JCheckBox("Verbose (constant pool, flags)");
    private final JLabel classInfoLabel = new JLabel(" ");
    private final JTextArea bytecodeArea = new JTextArea();
    private final JTabbedPane tabs = new JTabbedPane();
    private JButton runButton, stopButton;
    private JComboBox<String> exampleBox;
    private JSpinner periodSpinner;
    private JCheckBox gcBox, jdkBox, followBox;
    private boolean beginnerMode = true;
    private JButton viewButton;
    private JLabel viewBadge, periodLabel;
    private JComponent basicsTab, eventLogTab, timelineTab, heapTab, insightsTab, loaderTab, bytecodeTab, guideTab;
    private JSplitPane rightSplit;
    private final JPanel rightHolder = new JPanel(new BorderLayout());
    private final Map<Category, JCheckBox> catBoxes = new EnumMap<>(Category.class);
    private JTextField searchField;
    private JProgressBar progress;
    private final JLabel statusLabel = new JLabel("Ready. Pick an example and press Compile & Run. The Basics tab will guide you.");
    private final Set<Category> visibleCats = EnumSet.allOf(Category.class);
    private final List<Object> highlights = new ArrayList<>();

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {

        Thread t = new Thread(r, "runner-worker");
        t.setDaemon(true);
        return t;
    });
    private final ConcurrentLinkedQueue<Ev> pending = new ConcurrentLinkedQueue<>();
    private final List<long[]> heapSamples = new ArrayList<>();
    private volatile Instant runStart = Instant.now();
    private volatile String userLoader = "";
    private volatile List<Fr> liveFrames = List.of();
    private volatile String liveThread = "";
    private volatile boolean liveDirty;
    private ProgramRunner runner;
    private boolean running, adjustingCombo;
    private Path compiledDir;
    private int tick, dropped;
    private javax.swing.Timer flushTimer;

    static DefaultTableModel ro(String... cols) {

        return new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
    }

    public JVMLifecycleexplorer() {

        super("JVM Lifecycle Explorer  -  powered by Java Flight Recorder");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1500, 920);
        setLocationRelativeTo(null);
        initUI();
        addWindowListener(new WindowAdapter() {

            @Override public void windowClosing(WindowEvent e) {

                if (runner != null) runner.stop();
                executor.shutdownNow();
                deleteDir(compiledDir);
            }
        });
    }

    private void initUI() {

        setLayout(new BorderLayout());
        flushTimer = new javax.swing.Timer(200, e -> flushPending());

        add(buildToolbar(), BorderLayout.NORTH);
        JSplitPane main = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildLeft(), buildRight());
        main.setDividerLocation(470);
        main.setResizeWeight(0.3);
        add(main, BorderLayout.CENTER);

        JPanel status = new JPanel(new BorderLayout(8, 0));
        status.setBorder(new EmptyBorder(3, 8, 3, 8));
        progress = new JProgressBar();
        progress.setIndeterminate(true);
        progress.setPreferredSize(new Dimension(120, 12));
        progress.setVisible(false);
        status.add(statusLabel, BorderLayout.CENTER);
        status.add(progress, BorderLayout.EAST);
        add(status, BorderLayout.SOUTH);

        getRootPane().registerKeyboardAction(e -> startRun(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        loadExample();
        setViewMode(true);
    }

    private JComponent buildToolbar() {

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        exampleBox = new JComboBox<>(Examples.ALL.keySet().toArray(new String[0]));
        exampleBox.addActionListener(e -> { if (!running) loadExample(); });
        runButton = new JButton("Compile & Run");
        runButton.setToolTipText("Shortcut: Ctrl+Enter (Cmd+Enter on macOS)");
        runButton.setFont(runButton.getFont().deriveFont(Font.BOLD));
        runButton.addActionListener(e -> startRun());
        stopButton = new JButton("Stop");
        stopButton.setEnabled(false);
        stopButton.addActionListener(e -> { if (runner != null) runner.stop(); });
        periodLabel = new JLabel("  Sample (ms):");
        periodSpinner = new JSpinner(new SpinnerNumberModel(10, 1, 100, 1));
        periodSpinner.setToolTipText("How often JFR samples the running code (smaller = more detail, more overhead)");
        gcBox = new JCheckBox("Force GC at end", true);
        gcBox.setToolTipText("After your program finishes, ask for a garbage collection so you can see its classes being unloaded (step 5)");
        jdkBox = new JCheckBox("JDK internals", false);
        jdkBox.setToolTipText("Also show events caused by JDK code rather than yours (noisy)");
        jdkBox.addActionListener(e -> { applyFilter(); refreshInsights(true); });

        left.add(new JLabel("Example:"));
        left.add(exampleBox);
        left.add(runButton);
        left.add(stopButton);
        left.add(gcBox);
        left.add(periodLabel);
        left.add(periodSpinner);
        left.add(jdkBox);

        viewBadge = new JLabel();
        viewBadge.setFont(viewBadge.getFont().deriveFont(Font.BOLD, 12f));
        viewButton = new JButton();
        viewButton.setFont(viewButton.getFont().deriveFont(Font.BOLD, 13f));
        viewButton.setMargin(new Insets(6, 16, 6, 16));
        viewButton.setOpaque(true);
        viewButton.setFocusPainted(false);
        viewButton.addActionListener(e -> setViewMode(!beginnerMode));
        JButton storyButton = new JButton("JVM Story Time");
        storyButton.setToolTipText("A deep dive comedy documentary inside HotSpot (opens in a new tab)");
        storyButton.setFocusPainted(false);
        storyButton.addActionListener(e -> openStoryTab());
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 6));
        right.add(storyButton);
        right.add(viewBadge);
        right.add(viewButton);

        JPanel p = new JPanel(new BorderLayout());
        p.add(left, BorderLayout.WEST);
        p.add(right, BorderLayout.EAST);
        return p;
    }

    private JComponent buildLeft() {

        codeEditor.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        codeEditor.setTabSize(4);
        JScrollPane es = new JScrollPane(codeEditor);
        es.setRowHeaderView(new LineNumbers(codeEditor));
        es.setBorder(new TitledBorder("1. Source code (edit me!)"));

        console.setEditable(false);
        console.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        console.setBackground(new Color(0x1E1E1E));
        console.setForeground(new Color(0xD4D4D4));
        console.setCaretColor(Color.WHITE);
        JScrollPane cs = new JScrollPane(console);
        cs.setBorder(new TitledBorder("2. Console - your program's output & compiler messages"));

        JSplitPane vs = new JSplitPane(JSplitPane.VERTICAL_SPLIT, es, cs);
        vs.setResizeWeight(0.7);
        vs.setDividerLocation(560);
        return vs;
    }

    private JComponent buildRight() {

        basicsTab = buildBasicsTab();
        eventLogTab = buildEventLog();
        timelineTab = buildTimelineTab();
        heapTab = buildHeapTab();
        insightsTab = buildInsightsTab();
        loaderTab = buildLoaderTab();
        bytecodeTab = buildBytecodeTab();
        guideTab = buildGuideTab();

        explainPane.setContentType("text/html");
        explainPane.setEditable(false);
        explainPane.setText("<html><body style='font-family:sans-serif;font-size:12px;margin:8px'>"
                + "<b>Click any event</b> (in the Event Log or on the Timeline) and this panel explains it in plain English.</body></html>");
        rawArea.setEditable(false);
        rawArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        JPanel stackTab = new JPanel(new BorderLayout());
        JCheckBox showJdkFrames = new JCheckBox("Show JDK frames", true);
        showJdkFrames.addActionListener(e -> stackPanel.setShowJdk(showJdkFrames.isSelected()));
        stackTab.add(showJdkFrames, BorderLayout.NORTH);
        stackTab.add(new JScrollPane(stackPanel), BorderLayout.CENTER);

        JTabbedPane inspector = new JTabbedPane();
        inspector.addTab("What is this?", new JScrollPane(explainPane));
        inspector.addTab("Call Stack", stackTab);
        inspector.addTab("Raw JFR data", new JScrollPane(rawArea));
        inspector.setBorder(new TitledBorder("Inspector"));

        rightSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tabs, inspector);
        rightSplit.setResizeWeight(0.62);
        rightHolder.add(tabs, BorderLayout.CENTER);
        return rightHolder;
    }

    private JComponent buildEventLog() {

        table = new JTable(model) {
            @Override public String getToolTipText(MouseEvent me) {

                int r = rowAtPoint(me.getPoint());
                if (r < 0) return null;
                Ev ev = model.get(convertRowIndexToModel(r));
                return "<html><body style='width:420px'><b>" + UiUtils.esc(ev.type) + "</b> - " + UiUtils.esc(ev.target) + "<br>" + UiUtils.esc(ev.details) + "</body></html>";
            }
        };
        sorter = new TableRowSorter<>(model);
        table.setRowSorter(sorter);
        table.setRowHeight(22);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setFillsViewportHeight(true);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        EvRenderer renderer = new EvRenderer();
        int[] widths = {45, 105, 85, 125, 150, 165, 230, 420};
        for (int i = 0; i < widths.length; i++) {

            table.getColumnModel().getColumn(i).setCellRenderer(renderer);
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        table.getSelectionModel().addListSelectionListener(e -> {

            if (!e.getValueIsAdjusting()) onRowSelected();
        });

        JPanel cats = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        for (Category c : Category.values()) {

            JCheckBox cb = new JCheckBox(c.shortLabel, true);
            cb.setForeground(c.color.darker());
            cb.setToolTipText(c.label);
            cb.addActionListener(e -> {
                if (cb.isSelected()) visibleCats.add(c); else visibleCats.remove(c);
                applyFilter();
            });
            cats.add(cb);
            catBoxes.put(c, cb);
        }
        searchField = new JTextField(16);
        searchField.setToolTipText("Filter rows by text (thread, event, target, details)");
        searchField.getDocument().addDocumentListener(new DocumentListener() {

            public void insertUpdate(DocumentEvent e) { applyFilter(); }
            public void removeUpdate(DocumentEvent e) { applyFilter(); }
            public void changedUpdate(DocumentEvent e) { applyFilter(); }
        });
        followBox = new JCheckBox("Follow live", true);
        JPanel search = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        search.add(new JLabel("Search:"));
        search.add(searchField);
        search.add(followBox);
        JButton showAll = new JButton("Show all categories");
        showAll.addActionListener(e -> {

            visibleCats.addAll(EnumSet.allOf(Category.class));
            for (JCheckBox b : catBoxes.values()) b.setSelected(true);
            applyFilter();
        });
        search.add(showAll);

        JPanel filters = new JPanel(new BorderLayout());
        filters.add(cats, BorderLayout.NORTH);
        filters.add(search, BorderLayout.CENTER);
        filters.add(note("Bold, tinted rows = exact lifecycle steps driven by this tool. Other rows come from JFR. Click a column header to sort."), BorderLayout.SOUTH);

        JPanel p = new JPanel(new BorderLayout());
        p.add(filters, BorderLayout.NORTH);
        p.add(new JScrollPane(table), BorderLayout.CENTER);
        return p;
    }

    private final class EvRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int row, int col) {
            super.getTableCellRendererComponent(t, v, sel, foc, row, col);
            Ev ev = model.get(t.convertRowIndexToModel(row));
            String text;
            int align = SwingConstants.LEFT;
            switch (col) {

                case 0 -> { text = String.valueOf(ev.seq); align = SwingConstants.RIGHT; }
                case 1 -> { text = String.format("+%.3f ms", model.relMs(ev)); align = SwingConstants.RIGHT; }
                case 2 -> { text = UiUtils.fmtDur(ev.durNs); align = SwingConstants.RIGHT; }
                case 3 -> text = ev.thread;
                case 4 -> text = ev.cat.label;
                case 5 -> text = ev.type;
                case 6 -> text = ev.target;
                default -> text = ev.details;
            }
            setText(text);
            setHorizontalAlignment(align);
            setFont(ev.milestone ? t.getFont().deriveFont(Font.BOLD) : t.getFont());
            if (!sel) {

                setBackground(ev.milestone ? UiUtils.tint(ev.cat.color) : ev.jdk ? new Color(0xF5F5F5) : Color.WHITE);
                setForeground(col == 4 ? ev.cat.color.darker() : ev.jdk ? Color.GRAY : Color.BLACK);
            }
            return this;
        }
    }

    private void applyFilter() {
        boolean showJdk = jdkBox.isSelected();
        String q = searchField.getText().trim().toLowerCase();
        sorter.setRowFilter(new RowFilter<EventTableModel, Integer>() {

            @Override public boolean include(Entry<? extends EventTableModel, ? extends Integer> en) {

                Ev ev = en.getModel().get(en.getIdentifier());
                if (!visibleCats.contains(ev.cat)) return false;
                if (ev.jdk && !showJdk) return false;
                if (q.isEmpty()) return true;
                return (ev.type + " " + ev.target + " " + ev.details + " " + ev.thread).toLowerCase().contains(q);
            }
        });
    }

    private void onRowSelected() {

        int r = table.getSelectedRow();
        if (r < 0) return;
        showEvent(model.get(table.convertRowIndexToModel(r)));
    }

    private void showEvent(Ev ev) {

        StringBuilder sb = new StringBuilder("<html><body style='font-family:sans-serif;font-size:12px;margin:8px'>");
        sb.append("<h3 style='margin:0'><font color='").append(UiUtils.hex(ev.cat.color.darker())).append("'>").append(UiUtils.esc(ev.type)).append("</font></h3>");
        sb.append("<p style='margin:4px 0'><b>Target:</b> <code>").append(UiUtils.esc(ev.target)).append("</code></p>");
        sb.append("<p style='margin:4px 0'><b>Phase:</b> ").append(UiUtils.esc(ev.cat.label))
          .append(" &nbsp; <b>Thread:</b> ").append(UiUtils.esc(ev.thread))
          .append(" &nbsp; <b>At:</b> +").append(String.format("%.1f ms", model.relMs(ev)));
        if (ev.durNs > 0) sb.append(" &nbsp; <b>Duration:</b> ").append(UiUtils.fmtDur(ev.durNs));
        if (ev.count > 1) sb.append(" &nbsp; <b>Merged samples:</b> ").append(ev.count);
        sb.append("</p><p style='margin:4px 0'><b>Details:</b> ").append(UiUtils.esc(ev.details)).append("</p><hr>");
        sb.append(Explanations.explain(ev)).append("</body></html>");
        explainPane.setText(sb.toString());
        explainPane.setCaretPosition(0);

        stackPanel.setFrames(ev.stack.isEmpty() ? "This event carries no stack trace."
                : "Call stack at the moment of this event (" + ev.stack.size() + " frames)", ev.stack);
        rawArea.setText(ev.jfrEvent.isEmpty()
                ? "(This row is a lifecycle step generated by the tool itself, not a JFR event.)"
                : "JFR event type: " + ev.jfrEvent + "\n\n" + ev.raw);
        rawArea.setCaretPosition(0);

        clearHighlights();
        if (ev.userLine > 0) highlightLine(ev.userLine, new Color(0xFFF59D), true);
    }

    private void selectEvent(Ev ev) {

        int mi = model.all().indexOf(ev);
        int vi = mi < 0 ? -1 : table.convertRowIndexToView(mi);
        if (vi >= 0) {

            table.getSelectionModel().setSelectionInterval(vi, vi);
            table.scrollRectToVisible(table.getCellRect(vi, 0, true));
        } else {
            showEvent(ev);
        }
    }

    private static JComponent note(String text) {

        JTextArea t = new JTextArea(text);
        t.setEditable(false);
        t.setLineWrap(true);
        t.setWrapStyleWord(true);
        t.setOpaque(false);
        t.setFont(t.getFont().deriveFont(Font.ITALIC));
        t.setBorder(new EmptyBorder(4, 8, 4, 8));
        return t;
    }

    private JComponent buildTimelineTab() {

        JSlider zoom = new JSlider(1, 40, 1);
        zoom.addChangeListener(e -> timeline.setZoom(zoom.getValue()));
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        top.add(new JLabel("Zoom:"));
        top.add(zoom);
        JScrollPane sp = new JScrollPane(timeline);
        sp.getViewport().setScrollMode(JViewport.SIMPLE_SCROLL_MODE);
        sp.getViewport().addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) { timeline.revalidate(); }
        });
        timeline.setOnSelect(this::selectEvent);
        JPanel north = new JPanel(new BorderLayout());
        north.add(top, BorderLayout.NORTH);
        north.add(note("One lane per phase. Diamonds are the exact STEP markers driven by this tool; bars are JFR events (bar length = duration). "
                + "Hover for details, click an event to inspect it below."), BorderLayout.SOUTH);
        JPanel p = new JPanel(new BorderLayout());
        p.add(north, BorderLayout.NORTH);
        p.add(sp, BorderLayout.CENTER);
        return p;
    }

    private JComponent buildHeapTab() {

        JPanel p = new JPanel(new BorderLayout());
        p.add(note("Objects live on the heap. The blue line rises as your program allocates and drops when the garbage collector frees unreachable objects "
                + "(red lines). Dashed lines mark lifecycle steps. Numbers are JVM-wide, so they include this tool's own window."), BorderLayout.NORTH);
        p.add(heapPanel, BorderLayout.CENTER);
        return p;
    }

    private JComponent buildInsightsTab() {

        summaryPane.setContentType("text/html");
        summaryPane.setEditable(false);
        JScrollPane ss = new JScrollPane(summaryPane);
        ss.setPreferredSize(new Dimension(400, 190));
        ss.setBorder(new TitledBorder("Run summary"));

        JPanel grid = new JPanel(new GridLayout(1, 3, 6, 0));
        grid.add(titled(new JTable(hotModel), "Hot methods (where time was spent)"));
        grid.add(titled(new JTable(allocModel), "Top allocations (who fills the heap)"));
        grid.add(titled(new JTable(jitModel), "JIT-compiled methods (yours)"));
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.add(ss, BorderLayout.NORTH);
        p.add(grid, BorderLayout.CENTER);
        return p;
    }

    private static JComponent titled(JTable t, String title) {

        t.setAutoCreateRowSorter(true);
        t.setFillsViewportHeight(true);
        JScrollPane sp = new JScrollPane(t);
        sp.setBorder(new TitledBorder(title));
        return sp;
    }

    private JComponent buildLoaderTab() {

        JPanel p = new JPanel(new BorderLayout());
        p.add(note("Class loaders form a hierarchy and delegate to their PARENT FIRST. Your classes are defined by an isolated loader whose parent is the "
                + "platform loader. Classes listed under bootstrap/platform are JDK classes your code needed for the first time in this run."), BorderLayout.NORTH);
        loaderTree.setRootVisible(true);
        p.add(new JScrollPane(loaderTree), BorderLayout.CENTER);
        return p;
    }

    private JComponent buildBytecodeTab() {

        bytecodeArea.setEditable(false);
        bytecodeArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        classCombo.addActionListener(e -> { if (!adjustingCombo) disassemble(); });
        verboseBox.addActionListener(e -> disassemble());
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        top.add(new JLabel("Class file:"));
        top.add(classCombo);
        top.add(verboseBox);
        JPanel north = new JPanel(new BorderLayout());
        north.add(top, BorderLayout.NORTH);
        JPanel info = new JPanel(new BorderLayout());
        classInfoLabel.setBorder(new EmptyBorder(0, 10, 0, 4));
        info.add(classInfoLabel, BorderLayout.NORTH);
        info.add(note("This is what the JVM really executes (javap -c): a stack machine. Look for getstatic, invokevirtual, new, ldc and the static initializer <clinit>. "
                + "Tick 'Verbose' to see the constant pool that the Linking step resolves."), BorderLayout.SOUTH);
        north.add(info, BorderLayout.SOUTH);
        JPanel p = new JPanel(new BorderLayout());
        p.add(north, BorderLayout.NORTH);
        p.add(new JScrollPane(bytecodeArea), BorderLayout.CENTER);
        return p;
    }

    private JComponent buildGuideTab() {

        JEditorPane g = new JEditorPane("text/html", Explanations.guideHtml());
        g.setEditable(false);
        g.setCaretPosition(0);
        return new JScrollPane(g);
    }

    private final ProgramRunner.Sink sink = new ProgramRunner.Sink() {

        @Override public void started(Instant t, String loader) {

            runStart = t;
            userLoader = loader;
            SwingUtilities.invokeLater(() -> model.origin = t);
        }
        @Override public void event(Ev ev) { pending.add(ev); }
        @Override public void console(String s) { SwingUtilities.invokeLater(() -> appendConsole(s)); }
        @Override public void heap(Instant t, long used, long committed) {

            long ms = Duration.between(runStart, t).toMillis();
            synchronized (heapSamples) {
                if (heapSamples.size() < 30_000) heapSamples.add(new long[]{ms, used, committed});
            }
        }
        @Override public void compiled(Path dir, String main, List<String> classes) {

            SwingUtilities.invokeLater(() -> onCompiled(dir, classes));
        }
        @Override public void compileError(int line, String msg) {

            SwingUtilities.invokeLater(() -> highlightLine(line, new Color(0xFFCDD2), true));
        }
        @Override public void liveStack(String th, List<Fr> fr) {

            liveThread = th;
            liveFrames = fr;
            liveDirty = true;
        }
        @Override public void status(String s) { SwingUtilities.invokeLater(() -> statusLabel.setText(s)); }
    };

    void startRun() {

        if (running) return;
        running = true;
        runButton.setEnabled(false);
        stopButton.setEnabled(true);
        exampleBox.setEnabled(false);
        progress.setVisible(true);
        clearHighlights();
        story = null;
        lastCode = codeEditor.getText();
        showBasicsRunning();
        model.clear();
        pending.clear();
        synchronized (heapSamples) { heapSamples.clear(); }
        dropped = 0;
        tick = 0;
        liveDirty = false;
        console.setText("");
        rawArea.setText("");
        explainPane.setText("<html><body style='font-family:sans-serif;font-size:12px;margin:8px'>Running... click an event when rows appear.</body></html>");
        stackPanel.setFrames("Waiting for a live sample...", List.of());
        timeline.setData(List.of(), Instant.now());
        heapPanel.setData(List.of(), List.of());
        hotModel.setRowCount(0);
        allocModel.setRowCount(0);
        jitModel.setRowCount(0);
        summaryPane.setText("");
        treeModel.setRoot(new DefaultMutableTreeNode("Running..."));
        bytecodeArea.setText("");
        classInfoLabel.setText(" ");
        deleteDir(compiledDir);
        compiledDir = null;

        ProgramRunner.Config cfg = new ProgramRunner.Config();
        cfg.code = codeEditor.getText();
        cfg.periodMs = (Integer) periodSpinner.getValue();
        cfg.forceGc = gcBox.isSelected();
        ProgramRunner r = new ProgramRunner(sink);
        runner = r;
        flushTimer.start();
        executor.submit(() -> {
            try {

                r.run(cfg);
            } catch (Throwable t) {

                StringWriter sw = new StringWriter();
                t.printStackTrace(new PrintWriter(sw));
                SwingUtilities.invokeLater(() -> appendConsole("[tool error] " + sw + "\n"));
            } finally {

                SwingUtilities.invokeLater(this::finishRun);
            }
        });
    }

    boolean isRunning() { return running; }

    private void flushPending() {

        List<Ev> batch = new ArrayList<>();
        Ev ev;
        while ((ev = pending.poll()) != null) {

            if (model.getRowCount() + batch.size() >= MAX_EVENTS) { dropped++; continue; }
            ev.seq = model.getRowCount() + batch.size() + 1;
            batch.add(ev);
        }
        if (!batch.isEmpty()) {

            model.append(batch);
            if (followBox.isSelected() && table.getSelectedRow() < 0) {

                int last = table.getRowCount() - 1;
                if (last >= 0) table.scrollRectToVisible(table.getCellRect(last, 0, true));
            }
        }
        if (liveDirty && table.getSelectedRow() < 0) {

            liveDirty = false;
            stackPanel.setFrames("LIVE sample from thread " + liveThread + " (select a row to freeze a different stack)", liveFrames);
        }
        if (++tick % 3 == 0) refreshInsights(false);
    }

    private void finishRun() {

        flushTimer.stop();
        flushPending();
        trimAfterRun();
        model.sortByTime();
        refreshInsights(true);
        running = false;
        runButton.setEnabled(true);
        stopButton.setEnabled(false);
        exampleBox.setEnabled(true);
        progress.setVisible(false);
        int hidden = 0;
        for (Ev e : model.all()) if (e.jdk) hidden++;
        statusLabel.setText("Finished: " + model.getRowCount() + " events (" + hidden + " JDK-internal, hidden unless enabled)"
                + (dropped > 0 ? " - " + dropped + " dropped (limit " + MAX_EVENTS + ")" : "") + ". Click a row to learn what it means.");
        applyFilter();
        if (table.getRowCount() > 0) table.scrollRectToVisible(table.getCellRect(0, 0, true));

        explainPane.setText("<html><body style='font-family:sans-serif;font-size:12px;margin:8px'>"
                + "<b>Click any event</b> (in the Event Log or on the Timeline) and this panel explains it in plain English.</body></html>");
        story = buildStory();
        if (story.ok) {

            showStep(0);
            if (beginnerMode) {

                tabs.setSelectedComponent(basicsTab);
                statusLabel.setText("Finished. Read the story in the Basics tab: press 'Next step >' to walk through it.");
            }
        } else
            showBasicsFailed();
    }

    private void trimAfterRun() {

        Instant last = null;
        for (Ev e : model.all()) if (e.milestone && (last == null || e.start.isAfter(last))) last = e.start;
        if (last == null) return;
        Instant cut = last.plusMillis(400);
        model.all().removeIf(e -> e.start.isAfter(cut));
        long cutMs = Duration.between(runStart, cut).toMillis();
        synchronized (heapSamples) { heapSamples.removeIf(s -> s[0] > cutMs); }
    }

    private void appendConsole(String s) {

        console.append(s);
        if (console.getDocument().getLength() > 300_000) console.replaceRange("", 0, 100_000);
        console.setCaretPosition(console.getDocument().getLength());
    }

    private void onCompiled(Path dir, List<String> classes) {

        compiledDir = dir;
        adjustingCombo = true;
        classCombo.removeAllItems();
        for (String c : classes) classCombo.addItem(c);
        adjustingCombo = false;
        if (!classes.isEmpty()) {

            classCombo.setSelectedIndex(0);
            disassemble();
        }
    }

    private static final class LinePainter implements Highlighter.HighlightPainter {

        private final Color c;
        LinePainter(Color c) { this.c = c; }
        @Override public void paint(Graphics g, int p0, int p1, Shape bounds, JTextComponent comp) {
            try {
                Rectangle r = comp.modelToView(p0);
                if (r != null) {
                    g.setColor(c);
                    g.fillRect(0, r.y, comp.getWidth(), r.height);
                }
            } catch (BadLocationException ignored) { }
        }
    }

    private void clearHighlights() {

        for (Object h : highlights) codeEditor.getHighlighter().removeHighlight(h);
        highlights.clear();
    }

    private void highlightLine(int line, Color c, boolean scroll) {

        try {
            int idx = line - 1;
            if (idx < 0 || idx >= codeEditor.getLineCount()) return;
            int s = codeEditor.getLineStartOffset(idx), e = codeEditor.getLineEndOffset(idx);
            highlights.add(codeEditor.getHighlighter().addHighlight(s, Math.max(e, s + 1), new LinePainter(c)));
            if (scroll) {
                Rectangle r = codeEditor.modelToView(s);
                if (r != null) codeEditor.scrollRectToVisible(r);
            }
        } catch (BadLocationException ignored) { }
    }

    private void loadExample() {

        String code = Examples.ALL.get((String) exampleBox.getSelectedItem());
        if (code == null) return;
        clearHighlights();
        codeEditor.setText(code);
        codeEditor.setCaretPosition(0);
    }

    private static void deleteDir(Path dir) {

        if (dir == null) return;
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) { }
    }

    private void disassemble() {

        if (compiledDir == null || classCombo.getSelectedItem() == null) return;
        Path dir = compiledDir;
        String cls = (String) classCombo.getSelectedItem();
        boolean verbose = verboseBox.isSelected();
        bytecodeArea.setText("Running javap ...");
        new SwingWorker<String[], Void>() {

            @Override protected String[] doInBackground() {
                return new String[]{classInfo(dir, cls), javap(dir, cls, verbose)};
            }
            @Override protected void done() {
                try {

                    String[] r = get();
                    classInfoLabel.setText(r[0]);
                    bytecodeArea.setText(r[1]);
                } catch (Exception ex) {

                    bytecodeArea.setText(String.valueOf(ex));
                }
                bytecodeArea.setCaretPosition(0);
            }
        }.execute();
    }

    private static String javap(Path dir, String cls, boolean verbose) {

        Optional<java.util.spi.ToolProvider> tool = java.util.spi.ToolProvider.findFirst("javap");
        if (tool.isEmpty()) return "javap is not available in this Java runtime.";
        StringWriter out = new StringWriter();
        PrintWriter pw = new PrintWriter(out);
        tool.get().run(pw, pw, verbose ? "-v" : "-c", "-p", "-classpath", dir.toString(), cls);
        pw.flush();
        return out.toString();
    }

    private static String classInfo(Path dir, String cls) {

        try {

            byte[] b = Files.readAllBytes(dir.resolve(cls + ".class"));
            boolean magic = (b[0] & 0xFF) == 0xCA && (b[1] & 0xFF) == 0xFE && (b[2] & 0xFF) == 0xBA && (b[3] & 0xFF) == 0xBE;
            int minor = ((b[4] & 0xFF) << 8) | (b[5] & 0xFF);
            int major = ((b[6] & 0xFF) << 8) | (b[7] & 0xFF);
            int cp = ((b[8] & 0xFF) << 8) | (b[9] & 0xFF);
            return cls + ".class  \u00b7  " + b.length + " bytes  \u00b7  magic " + (magic ? "0xCAFEBABE" : "?") + "  \u00b7  format version " + major + "." + minor
                    + " (Java " + (major - 44) + ")  \u00b7  " + (cp - 1) + " constant-pool entries";
        } catch (Exception e) {

            return cls;
        }
    }

    private static final Category[] STEP_CATS = {
        Category.LOADING, Category.LINKING, Category.INITIALIZATION, Category.EXECUTION, Category.UNLOADING};

    private final JToggleButton[] stepButtons = new JToggleButton[5];
    private final ButtonGroup stepGroup = new ButtonGroup();
    private final JEditorPane storyPane = new JEditorPane();
    private final BasicsPicture picture = new BasicsPicture();
    private JButton backBtn, nextBtn, rawBtn;
    private int basicsStep = -1;
    private Story story;
    private String lastCode = "";

    private JComponent buildBasicsTab() {

        String[] labels = {"1   Load", "2   Link", "3   Initialize", "4   Use", "5   Unload"};
        JPanel steps = new JPanel(new GridLayout(1, 5, 6, 0));
        steps.setBorder(new EmptyBorder(8, 8, 4, 8));
        for (int i = 0; i < 5; i++) {

            final int idx = i;
            JToggleButton b = new JToggleButton(labels[i]);
            b.setFont(b.getFont().deriveFont(Font.BOLD, 13f));
            b.setForeground(STEP_CATS[i].color.darker());
            b.setFocusPainted(false);
            b.setEnabled(false);
            b.addActionListener(e -> showStep(idx));
            stepGroup.add(b);
            steps.add(b);
            stepButtons[i] = b;
        }

        storyPane.setContentType("text/html");
        storyPane.setEditable(false);
        JScrollPane storyScroll = new JScrollPane(storyPane);
        storyScroll.setBorder(new TitledBorder("The story of your program"));
        JPanel pictureWrap = new JPanel(new BorderLayout());
        pictureWrap.setBorder(new TitledBorder("Where things live in memory"));
        pictureWrap.add(picture, BorderLayout.CENTER);
        JPanel center = new JPanel(new GridLayout(1, 2, 8, 0));
        center.setBorder(new EmptyBorder(0, 8, 0, 8));
        center.add(storyScroll);
        center.add(pictureWrap);

        backBtn = new JButton("< Back");
        backBtn.addActionListener(e -> showStep(basicsStep - 1));
        nextBtn = new JButton("Next step >");
        nextBtn.setFont(nextBtn.getFont().deriveFont(Font.BOLD, 14f));
        nextBtn.setMargin(new Insets(6, 18, 6, 18));
        nextBtn.addActionListener(e -> showStep(basicsStep + 1));
        rawBtn = new JButton("Show me the raw events for this step");
        rawBtn.addActionListener(e -> showRawEvents(basicsStep));
        JButton advanced = new JButton("Show advanced view (all tabs) >>");
        advanced.setFont(advanced.getFont().deriveFont(Font.BOLD));
        advanced.addActionListener(e -> setViewMode(false));

        JPanel navLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        navLeft.add(backBtn);
        navLeft.add(nextBtn);
        navLeft.add(rawBtn);
        JPanel navRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 6));
        navRight.add(new JLabel("Want the full data?"));
        navRight.add(advanced);
        JPanel nav = new JPanel(new BorderLayout());
        nav.add(navLeft, BorderLayout.WEST);
        nav.add(navRight, BorderLayout.EAST);

        JPanel p = new JPanel(new BorderLayout(0, 4));
        p.add(steps, BorderLayout.NORTH);
        p.add(center, BorderLayout.CENTER);
        p.add(nav, BorderLayout.SOUTH);
        showBasicsWelcome();
        return p;
    }

    private static String page(String body) {

        return "<html><body style='font-family:sans-serif;font-size:13px;margin:10px'>" + body + "</body></html>";
    }

    private void showBasicsMessage(String html, Story.Pic pic) {

        stepGroup.clearSelection();
        for (JToggleButton b : stepButtons) b.setEnabled(false);
        storyPane.setText(page(html));
        storyPane.setCaretPosition(0);
        picture.setPic(pic);
        backBtn.setEnabled(false);
        nextBtn.setEnabled(false);
        rawBtn.setEnabled(false);
        basicsStep = -1;
    }

    private void showBasicsWelcome() {

        Story.Pic p = new Story.Pic();
        p.metaHint = "Metaspace holds the blueprint (metadata) of every class the JVM has loaded.";
        p.stackHint = "The stack has one box (frame) for each method call that is running right now.";
        p.heapHint = "The heap holds every object your code creates with new.";
        p.caption = "Three places to remember. Run a program and watch them fill up.";
        showBasicsMessage("<h2>Welcome!</h2>"
                + "<p>This tab tells the <b>story of your program</b> in five short steps, using what <i>really</i> happened when it ran on the JVM:</p>"
                + "<ol><li><b>Load</b>: the JVM finds your class</li>"
                + "<li><b>Link</b>: it checks the class is safe</li>"
                + "<li><b>Initialize</b>: static fields and static blocks run</li>"
                + "<li><b>Use</b>: <code>main()</code> runs, objects are created</li>"
                + "<li><b>Unload</b>: the JVM cleans up</li></ol>"
                + "<p><b>To begin:</b> choose an example (top left) and press <b>Compile &amp; Run</b>.</p>"
                + "<p>Prefer the big picture first? Click <b>JVM Story Time</b> (top right) for a deep dive comedy documentary.</p>"
                + "<p><i>When you want the raw data (event log, timeline, bytecode...), press the "
                + "<b>Show advanced view &gt;&gt;</b> button at the top right.</i></p>", p);
    }

    private void showBasicsRunning() {

        Story.Pic p = new Story.Pic();
        p.caption = "Running... the story appears here as soon as the program finishes.";
        showBasicsMessage("<h2>Running your program...</h2><p>The JVM is loading, linking, initializing and running it while "
                + "Java Flight Recorder takes notes. This only takes a moment.</p>", p);
    }

    private void showBasicsFailed() {
        
        Story.Pic p = new Story.Pic();
        p.caption = "Nothing was loaded because the program did not compile.";
        showBasicsMessage("<h2>Your program did not run</h2>"
                + "<p>Look at the <b>Console</b> (bottom left) for the error message. The line with the problem is "
                + "<b>highlighted in red</b> in the editor.</p>"
                + "<p>Fix it and press <b>Compile &amp; Run</b> again.</p>", p);
    }

    private Story buildStory() {

        Story s = new Story();
        Set<String> jdkSeen = new HashSet<>();
        Set<String> stackKeys = Set.of("run", "init", "clinit", "sleep", "alloc", "exception", "monitor-enter");
        int bestUser = 0;
        for (Ev e : model.all()) {

            double ms = model.relMs(e);
            switch (e.key) {
                case "step-load" -> { s.ok = true; s.main = e.target; s.loadNs = e.durNs; }
                case "step-link" -> s.linkNs = e.durNs;
                case "step-init" -> { s.initNs = e.durNs; s.initEndMs = ms + e.durNs / 1e6; }
                case "step-run" -> s.ranMain = true;
                case "step-end" -> { s.mainNs = e.durNs; s.failed = e.type.contains("FAILED"); }
                case "step-gc" -> s.forcedGc = true;
                case "load", "define" -> { if (e.jdk) jdkSeen.add(e.target); else s.classes.putIfAbsent(e.target, ms); }
                case "unload" -> { if (!s.unloaded.contains(e.target)) s.unloaded.add(e.target); }
                case "sleep" -> { if (!e.jdk) { s.sleepNs += e.durNs; s.sleepCount++; } }
                case "alloc" -> {
                    long[] a = s.allocs.computeIfAbsent(e.target, k -> new long[2]);
                    a[0] += e.count;
                    a[1] += e.bytes;
                    s.allocBytes += e.bytes;
                }
                case "jit" -> { if (!e.jdk) s.jit++; }
                case "deopt" -> { if (!e.jdk) s.deopts++; }
                case "exception" -> { if (!e.milestone && !e.jdk) s.exceptions++; }
                case "thread-start" -> { if (!e.target.startsWith("main (")) s.threads++; }
                case "gc" -> { s.gcs++; s.gcPauseNs += e.bytes; }
                default -> { }
            }
            if (!e.jdk && !e.stack.isEmpty() && stackKeys.contains(e.key)) {
                int u = 0;
                for (Fr f : e.stack) if (f.user()) u++;
                if (u > bestUser) { bestUser = u; s.stack = e.stack; }
            }
        }
        s.jdkClasses = jdkSeen.size();
        return s;
    }

    private static String prettyClass(String n) {

        if (n == null) return "?";
        String base = n;
        int dims = 0;
        while (base.startsWith("[")) { dims++; base = base.substring(1); }
        if (dims > 0) {

            String el = switch (base) {
                case "B" -> "byte"; case "I" -> "int"; case "J" -> "long"; case "C" -> "char";
                case "D" -> "double"; case "Z" -> "boolean"; case "S" -> "short"; case "F" -> "float";
                default -> base.startsWith("L") ? UiUtils.simpleName(base.substring(1).replace(";", "")) : base;
            };
            return el + "[]".repeat(dims);
        }
        return UiUtils.simpleName(n);
    }

    private void showStep(int i) {

        if (story == null || !story.ok) return;
        basicsStep = Math.max(0, Math.min(4, i));
        for (JToggleButton b : stepButtons) b.setEnabled(true);
        stepButtons[basicsStep].setSelected(true);
        storyPane.setText(chapterHtml(basicsStep));
        storyPane.setCaretPosition(0);
        picture.setPic(picFor(basicsStep));
        backBtn.setEnabled(basicsStep > 0);
        nextBtn.setEnabled(basicsStep < 4);
        rawBtn.setEnabled(true);
        clearHighlights();
        int line = lineFor(basicsStep);
        if (line > 0) highlightLine(line, new Color(0xFFF59D), true);
    }

    private static String plural(int n, String one, String many) { return n + " " + (n == 1 ? one : many); }

    private static String codeTag(String s) { return "<code>" + UiUtils.esc(s) + "</code>"; }

    private String chapterHtml(int step) {

        Story s = story;
        String main = codeTag(s.main);
        List<String> lazy = new ArrayList<>(), early = new ArrayList<>();
        for (Map.Entry<String, Double> en : s.classes.entrySet()) {
            
            if (en.getKey().equals(s.main)) continue;
            String item = codeTag(en.getKey()) + " (at +" + String.format("%.1f ms", en.getValue()) + ")";
            if (en.getValue() > s.initEndMs + 0.5) lazy.add(item); else early.add(codeTag(en.getKey()));
        }
        List<String> run = new ArrayList<>();
        String tagline, what, analogy, why, tryIt, extra = "";

        switch (step) {
            
            case 0 -> {
                tagline = "The JVM finds your class and brings it into memory.";
                what = "Your compiled program lives in a <code>.class</code> file (bytecode). A <b>class loader</b> found " + main
                        + ", read it, and built a <code>Class</code> object describing it in <b>Metaspace</b>, the memory area for class blueprints.";
                analogy = "Taking a recipe off the shelf and laying it on the counter. Nothing is cooked yet.";
                why = "Java loads classes <b>lazily</b>: only when your code first needs them. That keeps start-up fast, and a class you never use is never loaded.";
                run.add(main + " was loaded in <b>" + UiUtils.fmtDur(s.loadNs) + "</b>.");
                if (!lazy.isEmpty()) run.add("Later, <b>" + plural(lazy.size(), "more class", "more classes") + "</b> of yours " + (lazy.size() == 1 ? "was" : "were") + " loaded the first time <code>main</code> needed " + (lazy.size() == 1 ? "it" : "them") + ": "
                        + String.join(", ", lazy) + ".");
                else if (early.isEmpty()) run.add("This program only uses one class of its own, so there was nothing else to load.");
                if (!early.isEmpty()) run.add("Loaded early, because the JVM needed them to check or run the main class: " + String.join(", ", early) + ".");
                if (s.jdkClasses > 0) run.add(plural(s.jdkClasses, "extra JDK class", "extra JDK classes") + " had to be loaded for the first time because your code used " + (s.jdkClasses == 1 ? "it" : "them") + " (basics like <code>String</code> and <code>System</code> are already loaded at start-up).");
                tryIt = "Add a second class at the bottom of the editor and call it from the <i>middle</i> of <code>main</code>. Run again: is it loaded at the start, or only when <code>main</code> reaches it?";
            }
            case 1 -> {
                tagline = "The JVM checks that your class is safe, and gets it ready.";
                what = "Linking has three sub-phases: <b>1. Verify</b> (is bytecode safe and valid?), <b>2. Prepare</b> (allocates memory for <code>static</code> fields with zero/null default values), and <b>3. Resolve</b> (replaces symbolic constant pool entries with direct memory pointers).";
                analogy = "Checking the recipe for safety and setting up clean empty bowls on the counter for shared ingredients (defaults: 0, false, null).";
                why = "Protects against corrupted bytecode before execution. Your static fields exist in memory now, but they still hold 0/null until Step 3!";
                run.add(main + " was linked in <b>" + UiUtils.fmtDur(s.linkNs) + "</b>.");
                run.add("<b>Linking mechanism:</b> HotSpot links upon method inspection (<code>getDeclaredMethods()</code>), measuring bytecode verification and preparation time.");
                tryIt = "Notice that static fields start with default 0 / null in Step 2, before your initializers run in Step 3.";
            }
            case 2 -> {
                tagline = "Static fields get their real values and static blocks run, once.";
                what = "The JVM executed the class's hidden <code>&lt;clinit&gt;</code> method: every <code>static</code> field initializer and <code>static { }</code> block, top to bottom. This happens <b>exactly once</b> per class, and always <b>before</b> the class is first used.";
                analogy = "Preheating the oven and measuring out the initial ingredients so the kitchen is ready before cooking begins.";
                why = "Explains why static block output prints in the console <b>before</b> <code>main()</code> runs, and why static blocks never re-run when creating subsequent objects.";
                run.add("Initializing " + main + " took <b>" + UiUtils.fmtDur(s.initNs) + "</b>.");
                run.add("Check the Console: anything a static block printed appeared <i>before</i> <code>main()</code> started.");
                if (!lazy.isEmpty()) run.add("Your other classes are initialized later on their first active use, not now.");
                tryIt = "Add <code>static { System.out.println(\"hello from static\"); }</code> to your class and run. Where does it appear compared with the first line of <code>main</code>?";
            }
            case 3 -> {
                tagline = "Your <code>main</code> method runs: method calls, objects, threads.";
                what = "The JVM pushed a <b>frame</b> for <code>main()</code> onto the thread's <b>stack</b> and started running your code. Every method call adds a frame and every return removes one. Objects made with <code>new</code> live on the <b>heap</b>; the stack only holds references to them.";
                analogy = "Now you are cooking. The stack is your \"where am I in the recipe\" notes; the heap is the pantry where new ingredients (objects) are created.";
                why = "Stack: fast, automatic, one per thread. Heap: shared, cleaned up by the garbage collector. Knowing which is which explains errors like <code>StackOverflowError</code> (stack full) versus <code>OutOfMemoryError</code> (heap full).";
                if (!s.ranMain) run.add("The JVM could not find <code>public static void main(String[] args)</code> in " + main + ".");
                else if (s.failed) run.add("<code>main()</code> ended with an <b>exception</b> after " + UiUtils.fmtDur(s.mainNs) + " (see the Console).");
                else run.add("<code>main()</code> ran for <b>" + UiUtils.fmtDur(s.mainNs) + "</b>.");
                if (s.sleepCount > 0) run.add("Your code slept " + s.sleepCount + " time(s) for " + UiUtils.fmtDur(s.sleepNs) + " in total. A sleeping thread uses no CPU.");
                if (s.allocBytes > 0) run.add("JFR sampled about <b>" + UiUtils.fmtBytes(s.allocBytes) + "</b> of objects being created on the heap.");
                if (s.jit > 0) run.add(s.jit + " of your methods ran so often that the JIT compiler turned them from bytecode into fast machine code.");
                if (s.threads > 0) run.add("Your code started " + s.threads + " extra thread(s). Each has its own stack; all share the heap.");
                if (s.exceptions > 0) run.add(s.exceptions + " exception(s) were thrown.");
                if (run.size() == 1) run.add("This run was short. Try example <b>2 (garbage collection)</b> or <b>3 (JIT)</b> to see the heap and the compiler get busy.");
                tryIt = "Change a number in the code (a loop count or an array size) and run again: what changes in the Heap box? Then try the <b>5 - Exceptions &amp; the call stack</b> example and watch the Stack box grow.";
            }
            default -> {
                tagline = "When nobody needs the class any more, the JVM cleans up.";
                what = "After <code>main()</code> finished, the tool let go of your program's class loader and asked for a garbage collection. Once a class loader can no longer be reached, the collector can free it together with every class it loaded.";
                analogy = "Clean-up time: the recipe goes back on the shelf and the counter is wiped.";
                why = "In normal programs classes almost never unload, because the built-in loaders live as long as the JVM. Unloading matters in servers that reload code (plugins, hot deploy): a leaked class loader there is a classic <b>Metaspace memory leak</b>.";
                if (!s.unloaded.isEmpty()) {
                    List<String> names = new ArrayList<>();
                    for (String n : s.unloaded) names.add(codeTag(n));
                    run.add("The garbage collector unloaded <b>" + s.unloaded.size() + "</b> of your classes: " + String.join(", ", names) + ".");
                } else if (!s.forcedGc) {
                    run.add("<b>Force GC at end</b> (top toolbar) is switched off, so no clean-up was requested.");
                } else {
                    run.add("A garbage collection was requested, but the JVM chose not to unload the classes this time. That is normal: when to unload is the JVM's decision.");
                }
                if (s.gcs > 0) run.add(plural(s.gcs, "garbage collection was", "garbage collections were") + " recorded in total, with " + UiUtils.fmtDur(s.gcPauseNs) + " of stop-the-world pauses (JVM-wide, so this includes the tool's own window).");
                tryIt = "Untick <b>Force GC at end</b> in the toolbar and run again. There is no clean-up step, and the classes stay loaded.";
                extra = "<p><b>You have seen the whole lifecycle.</b> Load another example from the drop-down, or press "
                        + "<b>Show advanced view &gt;&gt;</b> (top right) to explore the raw events, timeline and bytecode.</p>";
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<h2><font color='").append(UiUtils.hex(STEP_CATS[step].color.darker())).append("'>Step ").append(step + 1)
          .append(" of 5: ").append(new String[]{"LOADING", "LINKING", "INITIALIZATION", "USING YOUR CODE", "UNLOADING"}[step]).append("</font></h2>");
        sb.append("<p><i>").append(tagline).append("</i></p>");
        sb.append("<p><b>What happened</b><br>").append(what).append("</p>");
        sb.append("<p><b>Think of it like this</b><br>").append(analogy).append("</p>");
        sb.append("<p><b>Why it matters</b><br>").append(why).append("</p>");
        sb.append("<p><b>In YOUR run</b></p><ul>");
        for (String r : run) sb.append("<li>").append(r).append("</li>");
        sb.append("</ul>");
        sb.append("<table width='100%' bgcolor='#FFF8E1' cellpadding='6'><tr><td><b>Try this:</b> ").append(tryIt).append("</td></tr></table>");
        sb.append(extra);
        return page(sb.toString());
    }

    private Story.Pic picFor(int step) {

        Story s = story;
        Story.Pic p = new Story.Pic();
        String main = s.main;
        switch (step) {
            case 0 -> {
                p.meta.add(new Story.Chip(main, Category.LOADING.color, "loaded"));
                p.stackHint = "Nothing is running yet. Loading only builds the blueprint.";
                p.heapHint = "No objects yet.";
                p.glow = 0;
                p.caption = "A blueprint of " + main + " now exists in Metaspace.";
            }
            case 1 -> {
                p.meta.add(new Story.Chip(main, Category.LINKING.color, "verified"));
                p.stackHint = "Still nothing running.";
                p.heapHint = "Still no objects.";
                p.glow = 0;
                p.caption = "Checked and prepared. Its static fields exist but still hold default values (0, false, null).";
            }
            case 2 -> {
                p.meta.add(new Story.Chip(main, Category.INITIALIZATION.color, "initialized"));
                p.stackHint = "main() has not started yet.";
                p.heapHint = "Objects created by static initializers (if any) would appear here.";
                p.glow = 0;
                p.caption = "Static fields now hold their real values. main() has not started yet.";
            }
            case 3 -> {
                for (Map.Entry<String, Double> en : s.classes.entrySet()) {
                    boolean isMain = en.getKey().equals(main);
                    boolean lazy = en.getValue() > s.initEndMs + 0.5;
                    p.meta.add(new Story.Chip(en.getKey(), Category.EXECUTION.color, isMain ? "in use" : lazy ? "loaded on first use" : "loaded early"));
                }
                if (p.meta.isEmpty()) p.meta.add(new Story.Chip(main, Category.EXECUTION.color, "in use"));
                List<Fr> user = new ArrayList<>();
                for (Fr f : s.stack) if (f.user()) user.add(f);
                if (user.isEmpty()) {
                    p.stack.add(new Story.Chip(UiUtils.simpleName(main) + ".main()", Category.EXECUTION.color, "running"));
                } else {
                    for (int i = user.size() - 1; i >= 0; i--) {
                        Fr f = user.get(i);
                        p.stack.add(new Story.Chip(f.simple() + "." + f.method() + "()", Category.EXECUTION.color, f.line() > 0 ? "line " + f.line() : ""));
                    }
                    Fr top = s.stack.get(0);
                    if (!top.user()) p.stack.add(new Story.Chip(top.simple() + "." + top.method() + "()", Category.SYSTEM.color, "JDK"));
                }
                List<Map.Entry<String, long[]>> al = new ArrayList<>(s.allocs.entrySet());
                al.sort((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1]));
                int shown = Math.min(5, al.size());
                for (int i = 0; i < shown; i++) {
                    Map.Entry<String, long[]> en = al.get(i);
                    p.heap.add(new Story.Chip(prettyClass(en.getKey()), Category.MEMORY.color, "~" + UiUtils.fmtBytes(en.getValue()[1])));
                }
                if (al.size() > shown) p.heap.add(new Story.Chip("+ " + (al.size() - shown) + " more kinds of objects", Category.MEMORY.color, "", true, false));
                p.heapHint = "This run created few or no sampled objects. Try example 2 to see the heap fill up.";
                p.glow = 1;
                p.caption = "The stack shows the deepest chain of calls JFR saw in your code (newest call on top). "
                        + "Object sizes are estimates from sampling.";
            }
            default -> {
                boolean gone = !s.unloaded.isEmpty();
                for (String name : s.classes.keySet()) {
                    boolean un = s.unloaded.contains(name);
                    p.meta.add(new Story.Chip(name, Category.UNLOADING.color, un ? "unloaded" : "still loaded", true, un));
                }
                if (p.meta.isEmpty()) p.meta.add(new Story.Chip(main, Category.UNLOADING.color, "still loaded", true, false));
                p.stackHint = "The program finished, so its thread and its stack are gone.";
                List<Map.Entry<String, long[]>> al = new ArrayList<>(s.allocs.entrySet());
                al.sort((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1]));
                for (int i = 0; i < Math.min(4, al.size()); i++) {
                    p.heap.add(new Story.Chip(prettyClass(al.get(i).getKey()), Category.MEMORY.color, "garbage", true, false));
                }
                p.heapHint = "Nothing references the objects any more: they are garbage, ready to be collected.";
                p.glow = 0;
                p.caption = gone ? "The classes were removed from Metaspace together with their class loader."
                        : "The classes are still there: the JVM did not unload them this time (or the clean-up was switched off).";
            }
        }
        return p;
    }

    private static int findLine(String[] lines, String regex) {

        Pattern p = Pattern.compile(regex);
        for (int i = 0; i < lines.length; i++) if (p.matcher(lines[i]).find()) return i + 1;
        return -1;
    }

    private int lineFor(int step) {

        String[] lines = lastCode.split("\n", -1);
        int classLine = findLine(lines, "\\b(class|interface|enum|record)\\s+" + Pattern.quote(story.main) + "\\b");
        return switch (step) {
            case 0, 1 -> classLine;
            case 2 -> {
                int l = findLine(lines, "^\\s*static\\s*\\{");
                if (l < 0) l = findLine(lines, "^\\s*(public\\s+|private\\s+|protected\\s+)?static\\s+(final\\s+)?[\\w<>\\[\\],.?\\s]+\\s+\\w+\\s*=[^=]");
                yield l > 0 ? l : classLine;
            }
            case 3 -> findLine(lines, "\\bstatic\\s+void\\s+main\\s*\\(");
            default -> -1;
        };
    }

    private void showRawEvents(int step) {

        Set<Category> cats = switch (step) {
            case 0 -> EnumSet.of(Category.LOADING);
            case 1 -> EnumSet.of(Category.LINKING);
            case 2 -> EnumSet.of(Category.INITIALIZATION);
            case 3 -> EnumSet.of(Category.EXECUTION, Category.MEMORY, Category.THREADS, Category.JIT, Category.EXCEPTIONS);
            default -> EnumSet.of(Category.UNLOADING, Category.GC);
        };
        if (beginnerMode) setViewMode(false);
        visibleCats.clear();
        visibleCats.addAll(cats);
        for (Map.Entry<Category, JCheckBox> en : catBoxes.entrySet()) en.getValue().setSelected(cats.contains(en.getKey()));
        searchField.setText("");
        applyFilter();
        tabs.setSelectedComponent(eventLogTab);
        statusLabel.setText("Showing the raw events for step " + (step + 1) + ". Press 'Show all categories' to see everything, "
                + "or '<< Back to beginner view' (top right) to return to the story.");
    }

    private void setViewMode(boolean beginner) {

        beginnerMode = beginner;
        tabs.removeAll();
        tabs.addTab("Basics", basicsTab);
        if (!beginner) {

            tabs.addTab("Event Log", eventLogTab);
            tabs.addTab("Timeline", timelineTab);
            tabs.addTab("Heap & GC", heapTab);
            tabs.addTab("Insights", insightsTab);
            tabs.addTab("Class Loaders", loaderTab);
            tabs.addTab("Bytecode", bytecodeTab);
        }
        tabs.addTab("Guide", guideTab);
        if (storyOpen) addStoryTab();

        rightHolder.removeAll();
        if (beginner) {
            
            rightHolder.add(tabs, BorderLayout.CENTER);
        } else {

            rightSplit.setTopComponent(tabs);
            rightHolder.add(rightSplit, BorderLayout.CENTER);
            SwingUtilities.invokeLater(() -> rightSplit.setDividerLocation(0.62));
        }
        periodLabel.setVisible(!beginner);
        periodSpinner.setVisible(!beginner);
        jdkBox.setVisible(!beginner);

        viewBadge.setText(beginner ? "View: BEGINNER" : "View: ADVANCED");
        viewBadge.setForeground(beginner ? new Color(0x2E7D32) : new Color(0x1565C0));
        viewButton.setText(beginner ? "Show advanced view  >>" : "<<  Back to beginner view");
        viewButton.setBackground(beginner ? new Color(0xFFE082) : new Color(0xBBDEFB));
        viewButton.setToolTipText(beginner
                ? "Reveals the Event Log, Timeline, Heap & GC, Insights, Class Loaders and Bytecode tabs, plus the Inspector"
                : "Hides the advanced tabs and returns to the simple step-by-step story");
        tabs.setSelectedIndex(beginner ? 0 : Math.min(1, tabs.getTabCount() - 1));
        rightHolder.revalidate();
        rightHolder.repaint();
    }

    private JComponent storyTab;
    private boolean storyOpen;

    private JComponent buildStoryTab() {
        
        JEditorPane pane = new JEditorPane("text/html", StoryContent.storyHtml());
        pane.setEditable(false);
        pane.addHyperlinkListener(e -> {
            if (e.getEventType() != HyperlinkEvent.EventType.ACTIVATED) return;
            String d = e.getDescription();
            if (d == null) return;
            if (d.startsWith("#")) {
                
                pane.scrollToReference(d.substring(1));
            } else if (d.startsWith("example:")) {
                
                int n;
                try { n = Integer.parseInt(d.substring(8).trim()); } catch (NumberFormatException ex) { return; }
                if (running) {
                    statusLabel.setText("A program is running. Wait for it to finish, then click the link again.");
                } else if (n >= 1 && n <= exampleBox.getItemCount()) {
                    exampleBox.setSelectedIndex(n - 1);
                    statusLabel.setText("Loaded example " + n + " into the editor. Press Compile & Run to watch the JVM do it for real.");
                }
            }
        });
        SwingUtilities.invokeLater(() -> pane.setCaretPosition(0));
        return new JScrollPane(pane);
    }

    private void addStoryTab() {
        
        tabs.addTab("JVM Story", storyTab);
        int idx = tabs.indexOfComponent(storyTab);
        JPanel head = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        head.setOpaque(false);
        JButton close = new JButton("\u00d7");
        close.setToolTipText("Close this tab");
        close.setBorder(new EmptyBorder(0, 4, 0, 4));
        close.setContentAreaFilled(false);
        close.setFocusPainted(false);
        close.addActionListener(e -> closeStoryTab());
        head.add(new JLabel("JVM Story"));
        head.add(close);
        tabs.setTabComponentAt(idx, head);
    }

    private void openStoryTab() {
        
        if (storyTab == null) storyTab = buildStoryTab();
        if (!storyOpen) {
            storyOpen = true;
            addStoryTab();
        }
        tabs.setSelectedComponent(storyTab);
    }

    private void closeStoryTab() {
        
        storyOpen = false;
        tabs.remove(storyTab);
        tabs.setSelectedIndex(0);
    }

    private static String stepLabel(String key) {
        
        return switch (key) {
            case "step-load" -> "load";
            case "step-link" -> "link";
            case "step-init" -> "init";
            case "step-run" -> "main()";
            case "step-end" -> "end";
            case "step-gc" -> "gc()";
            default -> "";
        };
    }

    private void refreshInsights(boolean full) {
        
        List<Ev> evs = model.all();
        boolean showJdk = jdkBox.isSelected();
        int period = (Integer) periodSpinner.getValue();

        List<Ev> tl = new ArrayList<>();
        for (Ev e : evs) if (!e.jdk || showJdk) tl.add(e);
        timeline.setData(tl, model.origin);

        List<long[]> hs;
        synchronized (heapSamples) { hs = new ArrayList<>(heapSamples); }
        long peak = 0;
        for (long[] s : hs) peak = Math.max(peak, s[1]);

        List<Object[]> marks = new ArrayList<>();
        Map<String, long[]> hot = new HashMap<>();
        Map<String, long[]> alloc = new HashMap<>();
        Set<String> userClasses = new LinkedHashSet<>(), jdkClasses = new LinkedHashSet<>();
        List<Ev> jits = new ArrayList<>();
        Map<String, Long> step = new HashMap<>();
        long hotTotal = 0, allocBytes = 0, gcPause = 0;
        int gcs = 0, deopts = 0, threads = 0, exceptions = 0;
        for (Ev e : evs) {
            
            double ms = model.relMs(e);
            if (e.milestone && !stepLabel(e.key).isEmpty()) marks.add(new Object[]{ms, stepLabel(e.key), e.cat.color});
            switch (e.key) {
                
                case "run", "init", "clinit" -> {
                    hot.computeIfAbsent(e.target, k -> new long[1])[0] += e.count;
                    hotTotal += e.count;
                }
                case "alloc" -> {
                    long[] a = alloc.computeIfAbsent(e.target, k -> new long[2]);
                    a[0] += e.count;
                    a[1] += e.bytes;
                    allocBytes += e.bytes;
                }
                case "load", "define" -> (e.jdk ? jdkClasses : userClasses).add(e.target);
                case "gc" -> { gcs++; gcPause += e.bytes; marks.add(new Object[]{ms, "GC", new Color(0xE53935)}); }
                case "jit" -> { if (!e.jdk) jits.add(e); }
                case "deopt" -> { if (!e.jdk) deopts++; }
                case "thread-start" -> threads++;
                case "exception" -> { if (!e.milestone) exceptions++; }
                case "step-load", "step-link", "step-init", "step-end" -> step.put(e.key, e.durNs);
                default -> { }
            }
        }
        heapPanel.setData(hs, marks);

        hotModel.setRowCount(0);
        final long ht = Math.max(1, hotTotal);
        hot.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0])).limit(40).forEach(en ->
                hotModel.addRow(new Object[]{en.getKey(), en.getValue()[0], UiUtils.fmtDur(en.getValue()[0] * period * 1_000_000L),
                        String.format("%.0f%%", 100.0 * en.getValue()[0] / ht)}));
        allocModel.setRowCount(0);
        alloc.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1])).limit(40).forEach(en ->
                allocModel.addRow(new Object[]{en.getKey(), en.getValue()[0], UiUtils.fmtBytes(en.getValue()[1])}));
        jitModel.setRowCount(0);
        for (Ev e : jits) jitModel.addRow(new Object[]{e.target, e.extra, "+" + String.format("%.1f ms", model.relMs(e))});

        StringBuilder sb = new StringBuilder("<html><body style='font-family:sans-serif;font-size:12px;margin:4px'>");
        sb.append("<table cellpadding='2'>");
        sb.append("<tr><td><b>Lifecycle of the main class</b></td><td>")
          .append("Load <b>").append(UiUtils.fmtDur(step.getOrDefault("step-load", 0L))).append("</b> &rarr; ")
          .append("Link <b>").append(UiUtils.fmtDur(step.getOrDefault("step-link", 0L))).append("</b> &rarr; ")
          .append("Initialize <b>").append(UiUtils.fmtDur(step.getOrDefault("step-init", 0L))).append("</b> &rarr; ")
          .append("main() ran for <b>").append(UiUtils.fmtDur(step.getOrDefault("step-end", 0L))).append("</b></td></tr>");
        sb.append("<tr><td><b>Classes</b></td><td>").append(userClasses.size()).append(" of yours loaded; ")
          .append(jdkClasses.size()).append(" extra JDK classes loaded for the first time because of your code</td></tr>");
        sb.append("<tr><td><b>Heap</b></td><td>peak in use ").append(UiUtils.fmtBytes(peak)).append(" (JVM-wide); sampled allocations \u2248 ")
          .append(UiUtils.fmtBytes(allocBytes)).append("</td></tr>");
        sb.append("<tr><td><b>Garbage collection</b></td><td>").append(gcs).append(" cycle(s), total pause ").append(UiUtils.fmtDur(gcPause)).append("</td></tr>");
        sb.append("<tr><td><b>JIT</b></td><td>").append(jits.size()).append(" of your methods compiled to machine code, ")
          .append(deopts).append(" deoptimization(s)</td></tr>");
        sb.append("<tr><td><b>Threads / exceptions</b></td><td>").append(threads).append(" thread(s) started by your code; ")
          .append(exceptions).append(" exception(s)/error(s) thrown</td></tr>");
        sb.append("</table></body></html>");
        summaryPane.setText(sb.toString());
        summaryPane.setCaretPosition(0);

        if (full) buildLoaderTree(evs);
    }

    private void buildLoaderTree(List<Ev> evs) {
        
        DefaultMutableTreeNode boot = new DefaultMutableTreeNode();
        DefaultMutableTreeNode plat = new DefaultMutableTreeNode();
        DefaultMutableTreeNode app = new DefaultMutableTreeNode();
        DefaultMutableTreeNode user = new DefaultMutableTreeNode();
        boot.add(plat);
        plat.add(app);
        plat.add(user);
        Map<String, DefaultMutableTreeNode> byLoader = new HashMap<>();
        byLoader.put("bootstrap", boot);
        byLoader.put("platform", plat);
        byLoader.put("app", app);
        byLoader.put(userLoader, user);
        Set<String> unloaded = new HashSet<>();
        for (Ev e : evs) if (e.key.equals("unload")) unloaded.add(e.target);
        Map<DefaultMutableTreeNode, Integer> counts = new HashMap<>();
        Set<String> seen = new HashSet<>();
        for (Ev e : evs) {
            
            if (!e.key.equals("load") && !e.key.equals("define")) continue;
            if (!seen.add(e.extra + "|" + e.target)) continue;
            DefaultMutableTreeNode parent = byLoader.getOrDefault(e.extra, app);
            int n = counts.merge(parent, 1, Integer::sum);
            if (n <= 300) {
                
                parent.add(new DefaultMutableTreeNode(e.target + "     +" + String.format("%.1f ms", model.relMs(e))
                        + (unloaded.contains(e.target) ? "     [unloaded by GC]" : "")));
            }
        }
        boot.setUserObject("Bootstrap loader - native, loads java.base   (" + counts.getOrDefault(boot, 0) + " classes first loaded in this run)");
        plat.setUserObject("Platform loader ('platform') - other JDK modules   (" + counts.getOrDefault(plat, 0) + ")");
        app.setUserObject("Application loader ('app') - runs this tool, NOT your program   (" + counts.getOrDefault(app, 0) + ")");
        user.setUserObject("Isolated user loader ('" + userLoader + "') - created just for your program; parent = platform loader   ("
                + counts.getOrDefault(user, 0) + " classes)");
        treeModel.setRoot(boot);
        treeModel.reload();
    }

    public static void main(String[] args) {
        try {
            
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) { }
        SwingUtilities.invokeLater(() -> new JVMLifecycleexplorer().setVisible(true));
    }
}
