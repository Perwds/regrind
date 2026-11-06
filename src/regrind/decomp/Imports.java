package regrind.decomp;

import regrind.core.Workspace;
import java.util.*;

/** Maps internal class names to source names and records the imports needed. */
public final class Imports {
    final String pkg, topLevel;
    final Workspace ws;
    final Map<String, String> simpleToFq = new HashMap<>();
    public final TreeSet<String> imports = new TreeSet<>();

    public Imports(String thisClass, Workspace ws) {
        int i = thisClass.lastIndexOf('/');
        pkg = i < 0 ? "" : thisClass.substring(0, i);
        int d = thisClass.indexOf('$', i + 2);
        topLevel = d > 0 ? thisClass.substring(0, d) : thisClass;
        this.ws = ws;
        simpleToFq.put(topLevel.substring(i + 1), topLevel);
    }

    public String name(String internal) {
        if (internal.startsWith("[")) return Types.src(internal, this);
        int slash = internal.lastIndexOf('/');
        String p = slash < 0 ? "" : internal.substring(0, slash);
        String tail = internal.substring(slash + 1);
        String outerTail = tail, rest = "";
        int d = tail.indexOf('$', 1);
        if (d > 0 && d < tail.length() - 1) {
            outerTail = tail.substring(0, d);
            rest = nested(tail.substring(d + 1));
        }
        String top = (p.isEmpty() ? "" : p + "/") + outerTail;
        if (top.equals(topLevel) && !rest.isEmpty()) return rest;
        String resolved = resolve(p, outerTail, top);
        return rest.isEmpty() ? resolved : resolved + "." + rest;
    }

    private static String nested(String s) {
        // Foo$1Bar -> Bar (local class); Foo$Bar$Baz -> Bar.Baz; digit-only names stay as written.
        StringBuilder b = new StringBuilder();
        for (String part : s.split("\\$")) {
            int i = 0;
            while (i < part.length() && Character.isDigit(part.charAt(i))) i++;
            String n = i < part.length() ? part.substring(i) : "$" + part;
            if (b.length() > 0) b.append('.');
            b.append(n);
        }
        return b.toString();
    }

    private String resolve(String p, String simple, String fq) {
        String have = simpleToFq.get(simple);
        if (have != null) return have.equals(fq) ? simple : fq.replace('/', '.');
        if (p.equals(pkg)) {
            simpleToFq.put(simple, fq);
            return simple;
        }
        if (ws != null && ws.exists(pkg.isEmpty() ? simple : pkg + "/" + simple)) return fq.replace('/', '.');
        simpleToFq.put(simple, fq);
        if (!p.equals("java/lang")) imports.add(fq.replace('/', '.'));
        return simple;
    }
}
