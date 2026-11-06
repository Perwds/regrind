package regrind.decomp;

import java.util.*;

/** Renders JVMS generic signatures (the Signature attribute) as Java source. */
public final class Sig {
    final String s;
    int i;
    final Imports im;

    public Sig(String sig, Imports im) { s = sig; this.im = im; }

    public static String field(String sig, Imports im) { return new Sig(sig, im).type(); }

    /** Formal type parameters as source, or "" when absent. */
    public String formals() {
        if (i >= s.length() || s.charAt(i) != '<') return "";
        i++;
        StringBuilder b = new StringBuilder("<");
        boolean first = true;
        while (s.charAt(i) != '>') {
            if (!first) b.append(", ");
            first = false;
            int c = s.indexOf(':', i);
            b.append(s, i, c);
            i = c;
            List<String> bounds = new ArrayList<>();
            while (s.charAt(i) == ':') {
                i++;
                if (s.charAt(i) == ':') continue;
                bounds.add(type());
            }
            bounds.removeIf("Object"::equals);
            if (!bounds.isEmpty()) b.append(" extends ").append(String.join(" & ", bounds));
        }
        i++;
        return b.append('>').toString();
    }

    /** Names of the formal type parameters of a class signature. */
    public static List<String> formalNames(String sig) {
        List<String> out = new ArrayList<>();
        if (sig.charAt(0) != '<') return out;
        int i = 1;
        while (sig.charAt(i) != '>') {
            int c = sig.indexOf(':', i);
            out.add(sig.substring(i, c));
            i = c;
            while (sig.charAt(i) == ':') {
                i++;
                if (sig.charAt(i) == ':') continue;
                int depth = 0;
                while (true) {
                    char ch = sig.charAt(i++);
                    if (ch == '<') depth++;
                    else if (ch == '>') depth--;
                    else if (ch == ';' && depth == 0) break;
                }
            }
        }
        return out;
    }

    /** The signature text of each parameter of a method signature. */
    public static List<String> rawParams(String sig) {
        int i = 0;
        if (sig.charAt(0) == '<') {
            int depth = 0;
            do {
                char c = sig.charAt(i++);
                if (c == '<') depth++;
                else if (c == '>') depth--;
            } while (depth > 0);
        }
        i++;
        List<String> out = new ArrayList<>();
        while (sig.charAt(i) != ')') {
            int st = i;
            while (sig.charAt(i) == '[') i++;
            if (sig.charAt(i) == 'L' || sig.charAt(i) == 'T') {
                int depth = 0;
                while (true) {
                    char c = sig.charAt(i++);
                    if (c == '<') depth++;
                    else if (c == '>') depth--;
                    else if (c == ';' && depth == 0) break;
                }
            } else i++;
            out.add(sig.substring(st, i));
        }
        return out;
    }

    public boolean more() { return i < s.length(); }

    public record Method(String formals, List<String> params, String ret, List<String> thrown) {}

    public static Method method(String sig, Imports im) {
        Sig p = new Sig(sig, im);
        String f = p.formals();
        p.i++; // (
        List<String> ps = new ArrayList<>();
        while (p.s.charAt(p.i) != ')') ps.add(p.type());
        p.i++;
        String r = p.type();
        List<String> th = new ArrayList<>();
        while (p.more() && p.s.charAt(p.i) == '^') {
            p.i++;
            th.add(p.type());
        }
        return new Method(f, ps, r, th);
    }

    public String type() {
        char c = s.charAt(i);
        switch (c) {
            case '[' -> { i++; return type() + "[]"; }
            case 'T' -> { int e = s.indexOf(';', i); String n = s.substring(i + 1, e); i = e + 1; return n; }
            case 'L' -> { return classType(); }
            default -> { i++; return Types.src(String.valueOf(c), im); }
        }
    }

    private String classType() {
        i++;
        StringBuilder raw = new StringBuilder();
        StringBuilder out = new StringBuilder();
        boolean started = false;
        while (true) {
            int st = i;
            while (";<.".indexOf(s.charAt(i)) < 0) i++;
            raw.append(s, st, i);
            String args = "";
            if (s.charAt(i) == '<') args = typeArgs();
            if (!started) { out.append(im.name(raw.toString())); started = true; }
            else out.append('.').append(raw.substring(raw.lastIndexOf("$") + 1));
            out.append(args);
            if (s.charAt(i) == ';') { i++; return out.toString(); }
            i++; // '.'
            raw.append('$');
        }
    }

    private String typeArgs() {
        i++;
        List<String> a = new ArrayList<>();
        while (s.charAt(i) != '>') {
            char c = s.charAt(i);
            if (c == '*') { i++; a.add("?"); }
            else if (c == '+') { i++; a.add("? extends " + type()); }
            else if (c == '-') { i++; a.add("? super " + type()); }
            else a.add(type());
        }
        i++;
        return "<" + String.join(", ", a) + ">";
    }
}
