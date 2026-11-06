package regrind.gui;

import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.*;
import javax.swing.undo.*;
import java.awt.*;
import java.awt.event.*;
import java.util.regex.*;

/** Syntax-highlighted code editor with a line gutter, undo and find. */
final class CodeView extends JPanel {
    static final Color BG = new Color(0x1E2127), GUTTER = new Color(0x282C34), TEXT = new Color(0xD7DAE0), DIM = new Color(0x636D83),
            KEYWORD = new Color(0xC678DD), STRING = new Color(0x98C379), NUMBER = new Color(0xD19A66), COMMENT = new Color(0x7F848E),
            TYPE = new Color(0xE5C07B), ANNOTATION = new Color(0x56B6C2), MEMBER = new Color(0x61AFEF), SELECT = new Color(0x3E4451);

    private static final Pattern JAVA = Pattern.compile(
            "(?<comment>//[^\\n]*|/\\*[\\s\\S]*?\\*/)"
            + "|(?<string>\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])+')"
            + "|(?<annotation>@[A-Za-z_][\\w.]*)"
            + "|(?<keyword>\\b(?:abstract|assert|boolean|break|byte|case|catch|char|class|continue|default|do|double|else|enum|extends|false|final|finally|float|for|if|implements|import|instanceof|int|interface|long|native|new|null|package|permits|private|protected|public|record|return|sealed|short|static|strictfp|super|switch|synchronized|this|throw|throws|transient|true|try|void|volatile|while|yield)\\b)"
            + "|(?<number>\\b(?:0[xX][0-9a-fA-F_]+[lL]?|\\d[\\d_]*(?:\\.\\d+)?(?:[eE][+-]?\\d+)?[lLfFdD]?)\\b)"
            + "|(?<type>\\b[A-Z][A-Za-z0-9_$]*\\b)"
            + "|(?<member>\\b[a-z_$][\\w$]*(?=\\())");
    private static final Pattern BYTECODE = Pattern.compile(
            "(?<comment>//[^\\n]*)"
            + "|(?<string>\"(?:\\\\.|[^\"\\\\\\n])*\")"
            + "|(?<annotation>^\\s*L\\d+:|\\bL\\d+\\b)"
            + "|(?<keyword>(?<=\\d  )[a-z_0-9]+)"
            + "|(?<number>\\b\\d+\\b)"
            + "|(?<type>[a-zA-Z_$][\\w$]*(?:/[\\w$]+)+)",
            Pattern.MULTILINE);

    final JTextPane text = new JTextPane() {
        @Override public boolean getScrollableTracksViewportWidth() {
            return getUI().getPreferredSize(this).width <= getParent().getSize().width;
        }
    };
    private final Pattern grammar;
    private final Timer rehighlight;
    private final UndoManager undo = new UndoManager();
    private final JPanel findBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
    private final JTextField findField = new JTextField(24);
    private final JLabel findInfo = new JLabel(" ");
    private final SimpleAttributeSet[] styles = new SimpleAttributeSet[8];
    private boolean loading;
    private boolean dirty;
    Runnable onDirty = () -> {};

    CodeView(boolean bytecode, boolean editable) {
        super(new BorderLayout());
        grammar = bytecode ? BYTECODE : JAVA;
        Color[] cs = {TEXT, KEYWORD, STRING, NUMBER, COMMENT, TYPE, ANNOTATION, MEMBER};
        for (int i = 0; i < cs.length; i++) {
            styles[i] = new SimpleAttributeSet();
            StyleConstants.setForeground(styles[i], cs[i]);
        }
        text.setBackground(BG);
        text.setForeground(TEXT);
        text.setCaretColor(Color.WHITE);
        text.setSelectionColor(SELECT);
        text.setSelectedTextColor(TEXT);
        text.setFont(codeFont());
        text.setEditable(editable);
        text.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        ((DefaultCaret) text.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);

        JScrollPane sp = new JScrollPane(text);
        sp.setBorder(null);
        sp.getViewport().setBackground(BG);
        sp.setRowHeaderView(new Gutter());
        sp.getVerticalScrollBar().setUnitIncrement(18);
        add(sp, BorderLayout.CENTER);

        rehighlight = new Timer(350, e -> highlight());
        rehighlight.setRepeats(false);
        text.getDocument().addDocumentListener(new Changes());
        text.getDocument().addUndoableEditListener(e -> {
            if (!e.getEdit().getPresentationName().equals("style change") && !loading) undo.addEdit(e.getEdit());
        });

        buildFindBar();
        add(findBar, BorderLayout.SOUTH);
        findBar.setVisible(false);
        InputMap im = text.getInputMap(WHEN_FOCUSED);
        ActionMap am = text.getActionMap();
        int menu = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_F, menu), "find");
        am.put("find", action(this::showFind));
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, menu), "undo");
        am.put("undo", action(() -> { if (undo.canUndo()) undo.undo(); }));
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_Y, menu), "redo");
        am.put("redo", action(() -> { if (undo.canRedo()) undo.redo(); }));
    }

    static Font codeFont() {
        for (String n : new String[]{"JetBrains Mono", "Cascadia Mono", "Consolas"}) {
            Font f = new Font(n, Font.PLAIN, 14);
            if (f.getFamily().equals(n)) return f;
        }
        return new Font(Font.MONOSPACED, Font.PLAIN, 14);
    }

    static AbstractAction action(Runnable r) {
        return new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { r.run(); }
        };
    }

    private final class Changes implements DocumentListener {
        private void touched() {
            if (loading) return;
            if (!dirty) {
                dirty = true;
                onDirty.run();
            }
            rehighlight.restart();
        }
        @Override public void insertUpdate(DocumentEvent e) { touched(); }
        @Override public void removeUpdate(DocumentEvent e) { touched(); }
        @Override public void changedUpdate(DocumentEvent e) {}
    }

    boolean isDirty() { return dirty; }
    void clearDirty() { dirty = false; }

    void setCode(String code) {
        loading = true;
        try {
            int caret = Math.min(text.getCaretPosition(), code.length());
            StyledDocument d = text.getStyledDocument();
            d.remove(0, d.getLength());
            d.insertString(0, code, styles[0]);
            highlight();
            text.setCaretPosition(caret);
            undo.discardAllEdits();
            dirty = false;
        } catch (BadLocationException e) {
            throw new IllegalStateException(e);
        } finally {
            loading = false;
        }
    }

    String code() {
        try {
            return text.getDocument().getText(0, text.getDocument().getLength());
        } catch (BadLocationException e) {
            return "";
        }
    }

    private void highlight() {
        boolean was = loading;
        loading = true;
        StyledDocument d = text.getStyledDocument();
        String s = code();
        d.setCharacterAttributes(0, s.length(), styles[0], true);
        Matcher m = grammar.matcher(s);
        String[] groups = {"keyword", "string", "number", "comment", "type", "annotation"};
        int[] idx = {1, 2, 3, 4, 5, 6};
        while (m.find()) {
            for (int i = 0; i < groups.length; i++) {
                if (m.start(groups[i]) >= 0) d.setCharacterAttributes(m.start(), m.end() - m.start(), styles[idx[i]], true);
            }
            if (grammar == JAVA && m.start("member") >= 0) d.setCharacterAttributes(m.start(), m.end() - m.start(), styles[7], true);
        }
        loading = was;
    }

    void gotoLine(int line) {
        Element root = text.getDocument().getDefaultRootElement();
        int i = Math.max(0, Math.min(line - 1, root.getElementCount() - 1));
        text.setCaretPosition(root.getElement(i).getStartOffset());
        try {
            Rectangle r = text.modelToView2D(text.getCaretPosition()).getBounds();
            r.height = text.getVisibleRect().height / 2;
            text.scrollRectToVisible(r);
        } catch (BadLocationException ignored) {
        }
        text.requestFocusInWindow();
    }

    /** The identifier under the caret, with any dotted prefix. */
    String wordAtCaret() {
        String s = code();
        int p = text.getCaretPosition(), a = p, b = p;
        while (a > 0 && (Character.isJavaIdentifierPart(s.charAt(a - 1)) || s.charAt(a - 1) == '.')) a--;
        while (b < s.length() && Character.isJavaIdentifierPart(s.charAt(b))) b++;
        return s.substring(a, b);
    }

    // ---------------------------------------------------------------- find

    private void buildFindBar() {
        JLabel l = new JLabel("Find:");
        JButton next = new JButton("Next"), close = new JButton("Close");
        findBar.add(l);
        findBar.add(findField);
        findBar.add(next);
        findBar.add(close);
        findBar.add(findInfo);
        next.addActionListener(e -> findNext());
        findField.addActionListener(e -> findNext());
        close.addActionListener(e -> { findBar.setVisible(false); text.requestFocusInWindow(); });
        findField.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "hide");
        findField.getActionMap().put("hide", action(() -> { findBar.setVisible(false); text.requestFocusInWindow(); }));
    }

    void showFind() {
        findBar.setVisible(true);
        revalidate();
        String sel = text.getSelectedText();
        if (sel != null && !sel.isEmpty() && !sel.contains("\n")) findField.setText(sel);
        findField.selectAll();
        findField.requestFocusInWindow();
    }

    private void findNext() {
        String q = findField.getText();
        if (q.isEmpty()) return;
        String s = code().toLowerCase();
        q = q.toLowerCase();
        int i = s.indexOf(q, text.getSelectionEnd());
        if (i < 0) i = s.indexOf(q);
        if (i < 0) {
            findInfo.setText("No matches");
            return;
        }
        findInfo.setText(" ");
        text.requestFocusInWindow();
        text.select(i, i + q.length());
        try {
            text.scrollRectToVisible(text.modelToView2D(i).getBounds());
        } catch (BadLocationException ignored) {
        }
    }

    // ---------------------------------------------------------------- gutter

    private final class Gutter extends JComponent {
        Gutter() {
            setFont(text.getFont());
            text.getDocument().addDocumentListener(new DocumentListener() {
                private void again() { SwingUtilities.invokeLater(() -> { revalidate(); repaint(); }); }
                @Override public void insertUpdate(DocumentEvent e) { again(); }
                @Override public void removeUpdate(DocumentEvent e) { again(); }
                @Override public void changedUpdate(DocumentEvent e) {}
            });
        }
        @Override public Dimension getPreferredSize() {
            int lines = text.getDocument().getDefaultRootElement().getElementCount();
            int digits = Math.max(3, String.valueOf(lines).length());
            return new Dimension(getFontMetrics(getFont()).charWidth('0') * digits + 16, text.getPreferredSize().height);
        }
        @Override protected void paintComponent(Graphics g) {
            Rectangle clip = g.getClipBounds();
            g.setColor(GUTTER);
            g.fillRect(clip.x, clip.y, clip.width, clip.height);
            ((Graphics2D) g).setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(DIM);
            g.setFont(getFont());
            FontMetrics fm = g.getFontMetrics();
            Element root = text.getDocument().getDefaultRootElement();
            try {
                int first = root.getElementIndex(text.viewToModel2D(new Point(0, clip.y)));
                int last = root.getElementIndex(text.viewToModel2D(new Point(0, clip.y + clip.height)));
                for (int i = first; i <= last; i++) {
                    Rectangle r = text.modelToView2D(root.getElement(i).getStartOffset()).getBounds();
                    String n = String.valueOf(i + 1);
                    g.drawString(n, getWidth() - fm.stringWidth(n) - 8, r.y + fm.getAscent());
                }
            } catch (BadLocationException ignored) {
            }
        }
    }
}
