package regrind.decomp;

import regrind.core.Workspace;
import java.lang.classfile.*;
import java.lang.classfile.attribute.*;
import java.util.*;

/** Public entry point of the decompiler. */
public final class Decompiler {
    private Decompiler() {}

    public record Result(String source, int classes, int methods, int failed, List<String> failures, Set<String> printed) {}

    /** The outermost class that prints this one, staying inside the workspace. */
    public static String topLevelOf(Workspace ws, String name) {
        String cur = name;
        for (int guard = 0; guard < 32; guard++) {
            ClassModel c = ws.model(cur);
            if (c == null) return cur;
            String outer = null;
            var ic = c.findAttribute(Attributes.innerClasses());
            if (ic.isPresent()) {
                for (InnerClassInfo i : ic.get().classes()) {
                    if (i.innerClass().asInternalName().equals(cur) && i.outerClass().isPresent()) outer = i.outerClass().get().asInternalName();
                }
            }
            if (outer == null) {
                var em = c.findAttribute(Attributes.enclosingMethod());
                if (em.isPresent()) outer = em.get().enclosingClass().asInternalName();
            }
            if (outer == null || !ws.classes.containsKey(outer)) return cur;
            cur = outer;
        }
        return cur;
    }

    public static boolean isTopLevel(Workspace ws, String name) { return topLevelOf(ws, name).equals(name); }

    /** Decompile a class and everything nested in it. */
    public static Result decompile(Workspace ws, String name) {
        String top = topLevelOf(ws, name);
        ClassModel c = ws.model(top);
        if (c == null) throw new IllegalArgumentException("No such class: " + name);
        ClassPrinter.Stats st = new ClassPrinter.Stats();
        ClassPrinter p = new ClassPrinter(ws, c, new Imports(top, ws), null, st);
        String src = p.printTop();
        return new Result(src, st.classes, st.methods, st.failed, st.failures, st.printed);
    }

    /** Runs on a thread with a large stack: deep nesting in big methods recurses heavily. */
    public static Result decompileDeep(Workspace ws, String name) {
        Result[] out = new Result[1];
        Throwable[] err = new Throwable[1];
        Thread t = new Thread(null, () -> {
            try {
                out[0] = decompile(ws, name);
            } catch (Throwable e) {
                err[0] = e;
            }
        }, "decompile", 1L << 29);
        t.start();
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (err[0] != null) {
            if (err[0] instanceof RuntimeException r) throw r;
            throw new RuntimeException(err[0]);
        }
        return out[0];
    }
}
