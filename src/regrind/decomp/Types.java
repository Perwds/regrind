package regrind.decomp;

import java.util.*;

/** Helpers over JVM descriptors; descriptors are the IR's type representation. "?" means unknown. */
public final class Types {
    private Types() {}
    public static final String OBJ = "Ljava/lang/Object;", STR = "Ljava/lang/String;", NULL = "null", UNK = "?";

    public static boolean isIntLike(String d) { return d.length() == 1 && "ZBCSI".contains(d); }
    public static boolean isRef(String d) { return d.startsWith("L") || d.startsWith("[") || d.equals(NULL); }
    public static boolean isPrimitive(String d) { return d.length() == 1 && "ZBCSIJFD".contains(d); }
    public static int size(String d) { return d.equals("J") || d.equals("D") ? 2 : 1; }
    public static String elem(String d) { return d.startsWith("[") ? d.substring(1) : UNK; }
    public static String internal(String d) {
        if (d.startsWith("L") && d.endsWith(";")) return d.substring(1, d.length() - 1);
        return d;
    }
    public static String ofInternal(String n) { return n.startsWith("[") ? n : "L" + n + ";"; }

    public static List<String> params(String md) {
        List<String> out = new ArrayList<>();
        int i = 1;
        while (md.charAt(i) != ')') {
            int s = i;
            while (md.charAt(i) == '[') i++;
            if (md.charAt(i) == 'L') i = md.indexOf(';', i);
            i++;
            out.add(md.substring(s, i));
        }
        return out;
    }
    public static String ret(String md) { return md.substring(md.indexOf(')') + 1); }

    /** Java source for a descriptor. */
    public static String src(String d, Imports im) {
        if (d == null) return "Object";
        int dims = 0;
        while (dims < d.length() && d.charAt(dims) == '[') dims++;
        String b = d.substring(dims), s;
        switch (b) {
            case "Z" -> s = "boolean";
            case "B" -> s = "byte";
            case "C" -> s = "char";
            case "S" -> s = "short";
            case "I" -> s = "int";
            case "J" -> s = "long";
            case "F" -> s = "float";
            case "D" -> s = "double";
            case "V" -> s = "void";
            case "null", "?" -> s = "Object";
            default -> s = im.name(internal(b));
        }
        return s + "[]".repeat(dims);
    }

    /** Is a value of type 'from' usable where 'to' is wanted without a cast (best effort)? */
    public static boolean assignable(String from, String to, regrind.core.Workspace w) {
        if (from.equals(to) || from.equals(NULL) || to.equals(OBJ) && isRef(from)) return true;
        if (from.equals(UNK) || to.equals(UNK)) return true;
        if (from.startsWith("[") || to.startsWith("[")) {
            if (from.startsWith("[") && to.startsWith("[")) {
                String a = elem(from), b = elem(to);
                return isRef(a) && isRef(b) && assignable(a, b, w);
            }
            return to.equals("Ljava/io/Serializable;") || to.equals("Ljava/lang/Cloneable;");
        }
        if (from.startsWith("L") && to.startsWith("L")) return w.isSubclass(internal(from), internal(to));
        return false;
    }
}
