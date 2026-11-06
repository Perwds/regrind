import regrind.core.*;
import regrind.gui.Workbench;
import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.concurrent.*;

/** Drives the real workbench window off-screen: open, decompile, edit, compile, search, export, rerun. */
public class GuiCheck {
    static void edt(ThrowingRunnable r) throws Exception { SwingUtilities.invokeAndWait(() -> { try { r.run(); } catch (Exception e) { throw new RuntimeException(e); } }); }
    interface ThrowingRunnable { void run() throws Exception; }

    public static void main(String[] a) throws Exception {
        Path jar = Path.of(a[0]), outDir = Path.of(a[1]);
        Files.createDirectories(outDir);
        Workbench[] w = new Workbench[1];
        edt(() -> {
            Workbench.theme();
            w[0] = new Workbench();
            w[0].addNotify();
            w[0].setSize(1360, 860);
            w[0].validate();
            w[0].openPath(jar);
        });
        Thread.sleep(1500);
        Workbench.ClassTab[] t = new Workbench.ClassTab[1];
        edt(() -> t[0] = w[0].openClass(a[2], 0));
        t[0].loaded.get(20, TimeUnit.SECONDS);
        Thread.sleep(300);
        shot(w[0], outDir.resolve("source.png"));
        Field: {
            edt(() -> {
                var src = sourceOf(t[0]);
                String code = src.getText();
                int i = code.indexOf(a[3]);
                if (i < 0) throw new IllegalStateException("edit target not found");
                src.setText(code.replace(a[3], a[4]));
                w[0].compileCurrent();
            });
        }
        Thread.sleep(6000);
        System.out.println("compile: " + (w[0].lastCompile == null ? "NO RESULT" : w[0].lastCompile.ok() + " " + w[0].lastCompile.replaced()));
        edt(() -> { w[0].query.setText("println"); w[0].searchMode.setSelectedIndex(1); w[0].bottom.setSelectedIndex(1); });
        w[0].runSearch().get(20, TimeUnit.SECONDS);
        Thread.sleep(500);
        System.out.println("search rows: " + w[0].results.getRowCount());
        shot(w[0], outDir.resolve("search.png"));
        w[0].ws.export(outDir.resolve("patched.jar"));
        System.out.println("exported; modified=" + w[0].ws.modified);
        System.exit(0);
    }

    static javax.swing.text.JTextComponent sourceOf(Component c) {
        if (c instanceof JTextPane p && p.isEditable()) return p;
        if (c instanceof Container k) for (Component x : k.getComponents()) {
            var r = sourceOf(x);
            if (r != null) return r;
        }
        return null;
    }

    static void shot(JFrame f, Path p) throws Exception {
        BufferedImage[] img = new BufferedImage[1];
        edt(() -> {
            f.validate();
            img[0] = new BufferedImage(f.getWidth(), f.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img[0].createGraphics();
            f.getContentPane().getParent().paint(g);
            g.dispose();
        });
        ImageIO.write(img[0], "png", p.toFile());
    }
}
