package regrind.gui;

import regrind.core.*;
import regrind.decomp.*;
import javax.swing.*;
import javax.swing.table.*;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.dnd.*;
import java.awt.event.*;
import java.io.File;
import java.lang.classfile.ClassModel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.*;

/** The desktop workbench: browse a jar, read and edit decompiled source, search, export. */
public final class Workbench extends JFrame {
    /** Node payload in the class tree. */
    record Item(String label, String cls, String resource) {
        @Override public String toString() { return label; }
    }

    public Workspace ws;
    final DefaultMutableTreeNode root = new DefaultMutableTreeNode("Nothing open");
    final DefaultTreeModel treeModel = new DefaultTreeModel(root);
    final JTree tree = new JTree(treeModel);
    final JTextField filter = new JTextField();
    final JTabbedPane tabs = new JTabbedPane();
    final JTextArea console = new JTextArea();
    final JLabel status = new JLabel("Open a jar, class file or folder to begin.");
    final Map<String, ClassTab> open = new HashMap<>();
    /** Decompilation recurses deeply, so all of it runs on one big-stack thread. */
    final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(null, r, "regrind-worker", 1L << 29);
        t.setDaemon(true);
        return t;
    });
    public final DefaultTableModel results = new DefaultTableModel(new String[]{"Class", "Where", "Match"}, 0) {
        @Override public boolean isCellEditable(int r, int c) { return false; }
    };
    final JTable resultTable = new JTable(results);
    public final JTextField query = new JTextField();
    public final JComboBox<String> searchMode = new JComboBox<>(new String[]{"Strings and numbers", "References to", "Declarations", "Decompiled text"});
    final JCheckBox regex = new JCheckBox("Regex");
    public final JTabbedPane bottom = new JTabbedPane();
    private volatile boolean searchCancelled;

    public static void launch(Path p) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            theme();
            Workbench w = new Workbench();
            w.setVisible(true);
            if (p != null) w.openPath(p);
        });
    }

    public static void theme() {
        try {
            UIManager.put("control", new Color(0x2B2F36));
            UIManager.put("info", new Color(0x2B2F36));
            UIManager.put("nimbusBase", new Color(0x1E2127));
            UIManager.put("nimbusAlertYellow", new Color(0xE5C07B));
            UIManager.put("nimbusDisabledText", new Color(0x7F848E));
            UIManager.put("nimbusFocus", new Color(0x4B6EAF));
            UIManager.put("nimbusLightBackground", new Color(0x21252B));
            UIManager.put("nimbusSelectionBackground", new Color(0x3A5A8C));
            UIManager.put("nimbusSelectedText", Color.WHITE);
            UIManager.put("text", new Color(0xD7DAE0));
            for (UIManager.LookAndFeelInfo i : UIManager.getInstalledLookAndFeels()) {
                if (i.getName().equals("Nimbus")) UIManager.setLookAndFeel(i.getClassName());
            }
        } catch (ReflectiveOperationException | UnsupportedLookAndFeelException e) {
            // the default look and feel still works
        }
    }

    public Workbench() {
        super("Regrind");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1360, 860);
        setLocationByPlatform(true);
        setJMenuBar(menus());

        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setCellRenderer(new TreeLook());
        tree.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) openSelected();
            }
            @Override public void mousePressed(MouseEvent e) { popup(e); }
            @Override public void mouseReleased(MouseEvent e) { popup(e); }
        });
        tree.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "open");
        tree.getActionMap().put("open", CodeView.action(this::openSelected));

        filter.putClientProperty("JComponent.sizeVariant", "small");
        filter.setToolTipText("Filter classes by name");
        filter.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            private void go() { rebuildTree(); }
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { go(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { go(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) {}
        });
        JPanel left = new JPanel(new BorderLayout(0, 4));
        JPanel filterRow = new JPanel(new BorderLayout(6, 0));
        filterRow.add(new JLabel(" Filter"), BorderLayout.WEST);
        filterRow.add(filter, BorderLayout.CENTER);
        left.add(filterRow, BorderLayout.NORTH);
        left.add(new JScrollPane(tree), BorderLayout.CENTER);

        console.setEditable(false);
        console.setFont(CodeView.codeFont().deriveFont(13f));
        console.setBackground(CodeView.BG);
        console.setForeground(CodeView.TEXT);
        bottom.addTab("Console", new JScrollPane(console));
        bottom.addTab("Search", searchPanel());

        JSplitPane right = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tabs, bottom);
        right.setResizeWeight(0.78);
        right.setDividerSize(5);
        JSplitPane main = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        main.setDividerLocation(300);
        main.setDividerSize(5);
        add(main, BorderLayout.CENTER);
        status.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        add(status, BorderLayout.SOUTH);

        setDropTarget(new DropTarget() {
            @Override public synchronized void drop(DropTargetDropEvent e) {
                try {
                    e.acceptDrop(DnDConstants.ACTION_COPY);
                    @SuppressWarnings("unchecked")
                    List<File> fs = (List<File>) e.getTransferable().getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor);
                    if (!fs.isEmpty()) openPath(fs.get(0).toPath());
                } catch (Exception ex) {
                    log("Could not open dropped file: " + ex.getMessage());
                }
            }
        });
    }

    private JMenuBar menus() {
        JMenuBar b = new JMenuBar();
        int ctrl = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        JMenu file = new JMenu("File");
        file.add(item("Open...", KeyStroke.getKeyStroke(KeyEvent.VK_O, ctrl), this::chooseOpen));
        file.add(item("Add library jar...", null, this::chooseLibrary));
        file.addSeparator();
        file.add(item("Save edit to class", KeyStroke.getKeyStroke(KeyEvent.VK_S, ctrl), this::compileCurrent));
        file.add(item("Export jar...", KeyStroke.getKeyStroke(KeyEvent.VK_E, ctrl), this::chooseExport));
        file.add(item("Export all sources...", null, this::chooseExportSources));
        file.addSeparator();
        file.add(item("Quit", null, () -> System.exit(0)));
        JMenu nav = new JMenu("Navigate");
        nav.add(item("Search workspace", KeyStroke.getKeyStroke(KeyEvent.VK_F, ctrl | InputEvent.SHIFT_DOWN_MASK), this::focusSearch));
        nav.add(item("Filter classes", KeyStroke.getKeyStroke(KeyEvent.VK_P, ctrl), () -> filter.requestFocusInWindow()));
        nav.add(item("Go to class under caret", KeyStroke.getKeyStroke(KeyEvent.VK_F3, 0), this::gotoUnderCaret));
        nav.add(item("Close tab", KeyStroke.getKeyStroke(KeyEvent.VK_W, ctrl), this::closeCurrent));
        b.add(file);
        b.add(nav);
        return b;
    }

    private static JMenuItem item(String name, KeyStroke key, Runnable r) {
        JMenuItem i = new JMenuItem(name);
        if (key != null) i.setAccelerator(key);
        i.addActionListener(e -> r.run());
        return i;
    }

    void log(String s) {
        console.append(s + "\n");
        console.setCaretPosition(console.getDocument().getLength());
    }

    // ---------------------------------------------------------------- opening

    private void chooseOpen() {
        JFileChooser c = new JFileChooser();
        c.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        if (c.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) openPath(c.getSelectedFile().toPath());
    }

    private void chooseLibrary() {
        if (ws == null) return;
        JFileChooser c = new JFileChooser();
        if (c.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            ws.libraries.add(c.getSelectedFile().toPath());
            log("Added library " + c.getSelectedFile());
        }
    }

    public void openPath(Path p) {
        status.setText("Opening " + p + " ...");
        CompletableFuture.supplyAsync(() -> {
            try {
                return Workspace.open(p);
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, worker).whenComplete((w, err) -> SwingUtilities.invokeLater(() -> {
            if (err != null) {
                status.setText("Could not open " + p);
                log("Could not open " + p + ": " + err.getCause());
                JOptionPane.showMessageDialog(this, "Could not open that file:\n" + err.getCause().getMessage(), "Regrind", JOptionPane.ERROR_MESSAGE);
                return;
            }
            ws = w;
            setTitle("Regrind - " + p.getFileName());
            tabs.removeAll();
            open.clear();
            rebuildTree();
            for (String warn : w.warnings) log("warning: " + warn);
            status.setText(w.classes.size() + " classes, " + w.resources.size() + " resources");
        }));
    }

    void rebuildTree() {
        root.removeAllChildren();
        if (ws != null) {
            String f = filter.getText().strip().toLowerCase(Locale.ROOT).replace('.', '/');
            Map<String, DefaultMutableTreeNode> dirs = new HashMap<>();
            for (String n : ws.classes.keySet()) {
                if (n.indexOf('$') > 0 && !Decompiler.isTopLevel(ws, n)) continue;
                if (!f.isEmpty() && !n.toLowerCase(Locale.ROOT).contains(f)) continue;
                int s = n.lastIndexOf('/');
                DefaultMutableTreeNode parent = s < 0 ? root : dir(dirs, n.substring(0, s));
                parent.add(new DefaultMutableTreeNode(new Item(n.substring(s + 1), n, null)));
            }
            if (f.isEmpty() && !ws.resources.isEmpty()) {
                DefaultMutableTreeNode res = new DefaultMutableTreeNode("Resources");
                for (String r : ws.resources.keySet()) res.add(new DefaultMutableTreeNode(new Item(r, null, r)));
                root.add(res);
            }
        }
        sort(root);
        treeModel.reload();
        if (!filter.getText().isBlank()) for (int i = 0; i < tree.getRowCount() && i < 300; i++) tree.expandRow(i);
        else if (root.getChildCount() == 1) tree.expandRow(0);
    }

    private DefaultMutableTreeNode dir(Map<String, DefaultMutableTreeNode> dirs, String pkg) {
        DefaultMutableTreeNode d = dirs.get(pkg);
        if (d != null) return d;
        int s = pkg.lastIndexOf('/');
        DefaultMutableTreeNode parent = s < 0 ? root : dir(dirs, pkg.substring(0, s));
        d = new DefaultMutableTreeNode(pkg.substring(s + 1));
        parent.add(d);
        dirs.put(pkg, d);
        return d;
    }

    private static void sort(DefaultMutableTreeNode n) {
        List<DefaultMutableTreeNode> kids = new ArrayList<>();
        for (int i = 0; i < n.getChildCount(); i++) kids.add((DefaultMutableTreeNode) n.getChildAt(i));
        kids.sort(Comparator.<DefaultMutableTreeNode, Boolean>comparing(k -> k.getUserObject() instanceof Item).thenComparing(k -> k.toString().toLowerCase(Locale.ROOT)));
        n.removeAllChildren();
        for (DefaultMutableTreeNode k : kids) {
            n.add(k);
            sort(k);
        }
    }

    private final class TreeLook extends DefaultTreeCellRenderer {
        TreeLook() {
            setBackgroundNonSelectionColor(new Color(0x21252B));
            setTextNonSelectionColor(CodeView.TEXT);
            setBorderSelectionColor(null);
        }
        @Override public Component getTreeCellRendererComponent(JTree t, Object v, boolean sel, boolean exp, boolean leaf, int row, boolean focus) {
            super.getTreeCellRendererComponent(t, v, sel, exp, leaf, row, focus);
            setIcon(null);
            Object u = ((DefaultMutableTreeNode) v).getUserObject();
            if (u instanceof Item i && i.cls != null) {
                boolean edited = ws != null && ws.modified.contains(i.cls);
                setText(edited ? i.label + "  (edited)" : i.label);
                setForeground(edited ? CodeView.TYPE : sel ? Color.WHITE : CodeView.TEXT);
            } else if (u instanceof Item) setForeground(sel ? Color.WHITE : CodeView.COMMENT);
            else setForeground(sel ? Color.WHITE : CodeView.MEMBER);
            return this;
        }
    }

    private Item selected() {
        Object n = tree.getLastSelectedPathComponent();
        return n instanceof DefaultMutableTreeNode d && d.getUserObject() instanceof Item i ? i : null;
    }

    private void openSelected() {
        Item i = selected();
        if (i == null) return;
        if (i.cls != null) openClass(i.cls, 0);
        else openResource(i.resource);
    }

    private void popup(MouseEvent e) {
        if (!e.isPopupTrigger()) return;
        TreePath p = tree.getPathForLocation(e.getX(), e.getY());
        if (p == null) return;
        tree.setSelectionPath(p);
        Item i = selected();
        if (i == null || i.cls == null) return;
        JPopupMenu m = new JPopupMenu();
        m.add(item("Open", null, this::openSelected));
        m.add(item("Copy name", null, () -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(i.cls.replace('/', '.')), null)));
        m.add(item("Find references", null, () -> {
            query.setText(i.cls);
            searchMode.setSelectedIndex(1);
            focusSearch();
            runSearch();
        }));
        m.add(item("Rename class...", null, () -> renameClass(i.cls)));
        m.show(tree, e.getX(), e.getY());
    }

    private void renameClass(String cls) {
        String to = JOptionPane.showInputDialog(this, "New internal name for " + cls, cls);
        if (to == null || to.isBlank() || to.equals(cls)) return;
        String target = to.strip().replace('.', '/');
        try {
            int n = Renamer.renameClass(ws, cls, target);
            log("Renamed " + cls + " to " + target + " in " + n + " classes");
            tabs.removeAll();
            open.clear();
            rebuildTree();
            openClass(target, 0);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Rename failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void openResource(String r) {
        byte[] data = ws.resources.get(r);
        String key = "res:" + r;
        ClassTab have = open.get(key);
        if (have != null) {
            tabs.setSelectedComponent(have);
            return;
        }
        ClassTab t = new ClassTab(key, r.substring(r.lastIndexOf('/') + 1));
        boolean binary = false;
        for (int i = 0; i < Math.min(data.length, 4096); i++) if (data[i] == 0) binary = true;
        t.source.setCode(binary ? hex(data) : new String(data, StandardCharsets.UTF_8));
        t.views.remove(t.bytecode);
        t.views.remove(t.info);
        t.views.remove(t.hexView);
        t.views.setTitleAt(0, binary ? "Hex" : "Text");
        addTab(key, t.title, t);
    }

    private void addTab(String key, String title, ClassTab t) {
        open.put(key, t);
        tabs.addTab(title, t);
        int i = tabs.indexOfComponent(t);
        JPanel head = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        head.setOpaque(false);
        t.tabLabel = new JLabel(title);
        JLabel x = new JLabel("x");
        x.setForeground(CodeView.DIM);
        x.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { closeTab(t); }
        });
        head.add(t.tabLabel);
        head.add(x);
        tabs.setTabComponentAt(i, head);
        tabs.setSelectedComponent(t);
    }

    private void closeTab(ClassTab t) {
        if (t.source.isDirty()) {
            int a = JOptionPane.showConfirmDialog(this, "Discard unsaved edits to " + t.title + "?", "Regrind", JOptionPane.OK_CANCEL_OPTION);
            if (a != JOptionPane.OK_OPTION) return;
        }
        tabs.remove(t);
        open.remove(t.key);
    }

    private void closeCurrent() {
        if (tabs.getSelectedComponent() instanceof ClassTab t) closeTab(t);
    }

    // ---------------------------------------------------------------- class tabs

    public ClassTab openClass(String name, int line) {
        String top = Decompiler.topLevelOf(ws, name);
        ClassTab have = open.get(top);
        if (have != null) {
            tabs.setSelectedComponent(have);
            if (line > 0) have.source.gotoLine(line);
            return have;
        }
        ClassTab t = new ClassTab(top, top.substring(top.lastIndexOf('/') + 1));
        addTab(top, t.title, t);
        t.load(line);
        return t;
    }

    /** One opened class: decompiled source, bytecode, class info and hex. */
    public final class ClassTab extends JPanel {
        final String key, title;
        final JTabbedPane views = new JTabbedPane(JTabbedPane.BOTTOM);
        final CodeView source = new CodeView(false, true), bytecode = new CodeView(true, false), info = new CodeView(true, false), hexView = new CodeView(true, false);
        JLabel tabLabel;
        int lossy;

        ClassTab(String key, String title) {
            super(new BorderLayout());
            this.key = key;
            this.title = title;
            views.addTab("Decompiled", source);
            views.addTab("Bytecode", bytecode);
            views.addTab("Class info", info);
            views.addTab("Hex", hexView);
            add(views, BorderLayout.CENTER);
            source.onDirty = () -> { if (tabLabel != null) tabLabel.setText(title + " *"); };
        }

        void load(int line) {
            source.setCode("// decompiling " + key + " ...\n");
            status.setText("Decompiling " + key + " ...");
            long t0 = System.currentTimeMillis();
            CompletableFuture.supplyAsync(() -> {
                Decompiler.Result r = Decompiler.decompile(ws, key);
                ClassModel c = ws.model(key);
                StringBuilder bc = new StringBuilder(), in = new StringBuilder();
                for (String n : ws.classes.keySet()) {
                    if (n.equals(key) || Decompiler.topLevelOf(ws, n).equals(key)) {
                        bc.append(Disassembler.classListing(ws.model(n))).append('\n');
                        in.append(Disassembler.info(ws.model(n))).append("\n\n");
                    }
                }
                return new Object[]{r, bc.toString(), in.toString(), hex(ws.classes.get(key))};
            }, worker).whenComplete((o, err) -> SwingUtilities.invokeLater(() -> {
                if (err != null) {
                    source.setCode("// Regrind failed on this class:\n// " + err.getCause() + "\n");
                    status.setText("Failed on " + key);
                    log("Failed on " + key + ": " + err.getCause());
                    return;
                }
                Decompiler.Result r = (Decompiler.Result) o[0];
                lossy = r.failed();
                source.setCode(r.source());
                bytecode.setCode((String) o[1]);
                info.setCode((String) o[2]);
                hexView.setCode((String) o[3]);
                source.text.setCaretPosition(0);
                bytecode.text.setCaretPosition(0);
                info.text.setCaretPosition(0);
                hexView.text.setCaretPosition(0);
                if (line > 0) source.gotoLine(line);
                if (tabLabel != null) tabLabel.setText(title);
                for (String f : r.failures()) log("fallback: " + f);
                status.setText(String.format("%s: %d classes, %d methods, %d fell back, %d ms", key, r.classes(), r.methods(), r.failed(), System.currentTimeMillis() - t0));
                loaded.complete(null);
            }));
        }
        public final CompletableFuture<Void> loaded = new CompletableFuture<>();
    }

    static String hex(byte[] d) {
        StringBuilder b = new StringBuilder();
        int max = Math.min(d.length, 1 << 18);
        for (int i = 0; i < max; i += 16) {
            b.append(String.format("%08x  ", i));
            StringBuilder a = new StringBuilder();
            for (int k = 0; k < 16; k++) {
                if (i + k < max) {
                    int v = d[i + k] & 255;
                    b.append(String.format("%02x ", v));
                    a.append(v >= 32 && v < 127 ? (char) v : '.');
                } else b.append("   ");
                if (k == 7) b.append(' ');
            }
            b.append(' ').append(a).append('\n');
        }
        if (d.length > max) b.append("... ").append(d.length - max).append(" more bytes\n");
        return b.toString();
    }

    // ---------------------------------------------------------------- editing

    public void compileCurrent() {
        if (!(tabs.getSelectedComponent() instanceof ClassTab t) || t.key.startsWith("res:")) return;
        String code = t.source.code();
        if (t.lossy > 0 && JOptionPane.showConfirmDialog(this, t.lossy + " method(s) in this class could not be decompiled and are stubs that throw.\n"
                + "Compiling replaces their real bodies with those stubs. Continue?", "Regrind", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
        ClassModel c = ws.model(t.key);
        int release = Math.max(8, c.majorVersion() - 44);
        status.setText("Compiling " + t.key + " ...");
        CompletableFuture.supplyAsync(() -> {
            try {
                return Recompiler.compile(ws, t.key, code, release);
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, worker).whenComplete((r, err) -> SwingUtilities.invokeLater(() -> {
            bottom.setSelectedIndex(0);
            if (err != null) {
                log("Compile failed: " + err.getCause());
                status.setText("Compile failed");
                return;
            }
            if (!r.ok()) {
                log("Compile of " + t.key + " failed:");
                for (Recompiler.Problem p : r.problems()) log("  " + p);
                status.setText(r.problems().size() + " compile errors; class not changed");
                if (!r.problems().isEmpty()) t.source.gotoLine((int) r.problems().get(0).line());
                return;
            }
            log("Compiled " + t.key + ": replaced " + r.replaced().size() + " classes. Use File > Export jar to save.");
            t.source.clearDirty();
            t.tabLabel.setText(t.title);
            status.setText("Saved into workspace (not yet on disk)");
            lastCompile = r;
            for (var e = root.depthFirstEnumeration(); e.hasMoreElements();) {
                if (e.nextElement() instanceof DefaultMutableTreeNode n && n.getUserObject() instanceof Item i && i.cls != null && r.replaced().contains(i.cls)) treeModel.nodeChanged(n);
            }
        }));
    }
    public volatile Recompiler.Outcome lastCompile;

    private void chooseExport() {
        if (ws == null) return;
        JFileChooser c = new JFileChooser();
        c.setSelectedFile(new File("patched.jar"));
        if (c.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path out = c.getSelectedFile().toPath();
        if (Files.exists(out) && JOptionPane.showConfirmDialog(this, "Overwrite " + out.getFileName() + "?", "Regrind", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        try {
            ws.export(out);
            log("Exported " + ws.classes.size() + " classes to " + out);
            status.setText("Exported " + out.getFileName());
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, e.toString(), "Export failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void chooseExportSources() {
        if (ws == null) return;
        JFileChooser c = new JFileChooser();
        c.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (c.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path dir = c.getSelectedFile().toPath();
        status.setText("Exporting sources ...");
        CompletableFuture.runAsync(() -> {
            int n = 0, bad = 0;
            Set<String> taken = new HashSet<>();
            for (String name : new ArrayList<>(ws.classes.keySet())) {
                if (!Decompiler.isTopLevel(ws, name)) continue;
                try {
                    Path p = Workspace.sourcePath(dir, name, taken);
                    Files.createDirectories(p.getParent());
                    Files.writeString(p, Decompiler.decompile(ws, name).source());
                    n++;
                } catch (Exception e) {
                    bad++;
                }
            }
            String done = "Wrote " + n + " source files to " + dir + (bad > 0 ? ", " + bad + " failed" : "");
            SwingUtilities.invokeLater(() -> { log(done); status.setText(done); });
        }, worker);
    }

    private void gotoUnderCaret() {
        if (!(tabs.getSelectedComponent() instanceof ClassTab t) || ws == null) return;
        String w = t.source.wordAtCaret();
        if (w.isEmpty()) return;
        String tail = w.substring(w.lastIndexOf('.') + 1);
        String src = t.source.code();
        String pkg = t.key.contains("/") ? t.key.substring(0, t.key.lastIndexOf('/') + 1) : "";
        List<String> tries = new ArrayList<>();
        tries.add(w.replace('.', '/'));
        tries.add(pkg + tail);
        tries.add(t.key + "$" + tail);
        for (String line : src.split("\n")) {
            if (line.startsWith("import ") && line.endsWith("." + tail + ";")) tries.add(line.substring(7, line.length() - 1).replace('.', '/'));
        }
        for (String c : tries) {
            if (ws.classes.containsKey(c)) {
                openClass(c, 0);
                return;
            }
        }
        status.setText("No class named " + tail + " in this workspace");
    }

    // ---------------------------------------------------------------- search

    private JPanel searchPanel() {
        JPanel p = new JPanel(new BorderLayout(4, 4));
        JPanel row = new JPanel(new BorderLayout(6, 0));
        JPanel options = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JButton go = new JButton("Search");
        options.add(searchMode);
        options.add(regex);
        options.add(go);
        row.add(query, BorderLayout.CENTER);
        row.add(options, BorderLayout.EAST);
        p.add(row, BorderLayout.NORTH);
        resultTable.setFont(CodeView.codeFont().deriveFont(13f));
        resultTable.setRowHeight(20);
        resultTable.setAutoCreateRowSorter(true);
        resultTable.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() != 2) return;
                int r = resultTable.rowAtPoint(e.getPoint());
                if (r < 0) return;
                int m = resultTable.convertRowIndexToModel(r);
                String cls = (String) results.getValueAt(m, 0), where = (String) results.getValueAt(m, 1);
                openClass(cls, where.startsWith("line ") ? Integer.parseInt(where.substring(5)) : 0);
            }
        });
        p.add(new JScrollPane(resultTable), BorderLayout.CENTER);
        go.addActionListener(e -> runSearch());
        query.addActionListener(e -> runSearch());
        return p;
    }

    private void focusSearch() {
        bottom.setSelectedIndex(1);
        query.requestFocusInWindow();
        query.selectAll();
    }

    public CompletableFuture<Void> runSearch() {
        if (ws == null || query.getText().isEmpty()) return CompletableFuture.completedFuture(null);
        results.setRowCount(0);
        searchCancelled = false;
        String q = query.getText();
        boolean rx = regex.isSelected();
        Search.Mode mode = Search.Mode.values()[searchMode.getSelectedIndex()];
        status.setText("Searching ...");
        int limit = 5000;
        int[] count = {0};
        return CompletableFuture.runAsync(() -> {
            Search.run(ws, mode, q, rx, h -> {
                if (++count[0] > limit) {
                    searchCancelled = true;
                    return;
                }
                SwingUtilities.invokeLater(() -> results.addRow(new Object[]{h.cls(), h.where(), h.text()}));
            }, () -> searchCancelled);
            String msg = count[0] > limit ? "Stopped at " + limit + " results" : count[0] + " results";
            SwingUtilities.invokeLater(() -> status.setText(msg));
        }, worker).exceptionally(e -> {
            SwingUtilities.invokeLater(() -> status.setText("Bad search: " + e.getCause().getMessage()));
            return null;
        });
    }
}
