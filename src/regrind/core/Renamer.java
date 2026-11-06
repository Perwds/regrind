package regrind.core;

import java.io.*;
import java.util.*;

/**
 * Renames a class across the workspace by rewriting constant pools directly, so nothing else in
 * the class file changes. String constants are deliberately left alone.
 */
public final class Renamer {
    private Renamer() {}

    private static final int UTF8 = 1, INT = 3, FLOAT = 4, LONG = 5, DOUBLE = 6, CLASS = 7, STRING = 8, FIELD = 9, METHOD = 10, IMETHOD = 11,
            NAME_TYPE = 12, HANDLE = 15, MTYPE = 16, DYNAMIC = 17, INDY = 18, MODULE = 19, PACKAGE = 20;

    /** Renames {@code from} and its nested classes to {@code to}. Returns the number of classes changed. */
    public static int renameClass(Workspace ws, String from, String to) throws IOException {
        if (ws.classes.containsKey(to)) throw new IllegalArgumentException(to + " already exists");
        Map<String, String> map = new HashMap<>();
        for (String n : ws.classes.keySet()) {
            if (n.equals(from)) map.put(n, to);
            else if (n.startsWith(from + "$")) map.put(n, to + n.substring(from.length()));
        }
        return renameAll(ws, map);
    }

    /** Applies many renames in one pass over the workspace. Returns the number of classes changed. */
    public static int renameAll(Workspace ws, Map<String, String> map) throws IOException {
        int changed = 0;
        for (String name : new ArrayList<>(ws.classes.keySet())) {
            byte[] old = ws.classes.get(name);
            byte[] now = rewrite(old, map);
            String newName = map.getOrDefault(name, name);
            if (now != old || !newName.equals(name)) {
                ws.classes.remove(name);
                ws.entryPaths.remove(name);
                ws.modified.remove(name);
                ws.replaceClass(newName, now);
                changed++;
            }
        }
        return changed;
    }

    /**
     * Reads "pkg.Old=New" or "pkg.Old=other.pkg.New" lines and applies those that name a class in the
     * workspace and whose new name is free. Returns how many were applied.
     */
    public static int applyNameFile(Workspace ws, java.nio.file.Path file) throws IOException {
        Map<String, String> map = new HashMap<>();
        Set<String> used = new HashSet<>(ws.classes.keySet());
        for (String line : java.nio.file.Files.readAllLines(file)) {
            int eq = line.indexOf('=');
            if (eq <= 0 || line.startsWith("#")) continue;
            String from = line.substring(0, eq).strip().replace('.', '/'), to = line.substring(eq + 1).strip().replace('.', '/');
            if (!ws.classes.containsKey(from) || to.isEmpty()) continue;
            if (to.indexOf('/') < 0 && from.indexOf('/') >= 0) to = from.substring(0, from.lastIndexOf('/') + 1) + to;
            if (used.add(to)) map.put(from, to);
        }
        renameAll(ws, map);
        return map.size();
    }

    static String mapName(String n, Map<String, String> map) {
        if (n.startsWith("[")) return mapDescriptor(n, map);
        return map.getOrDefault(n, n);
    }

    /** Rewrites L<name>; references inside descriptors and generic signatures. */
    static String mapDescriptor(String s, Map<String, String> map) {
        StringBuilder b = new StringBuilder();
        int i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            boolean start = c == 'L' && (i == 0 || "();[<>:*+-^".indexOf(s.charAt(i - 1)) >= 0 || Character.isUpperCase(s.charAt(i - 1)));
            if (start) {
                int e = i + 1;
                while (e < n && ";<.".indexOf(s.charAt(e)) < 0) e++;
                b.append('L').append(map.getOrDefault(s.substring(i + 1, e), s.substring(i + 1, e)));
                i = e;
            } else {
                b.append(c);
                i++;
            }
        }
        return b.toString();
    }

    private static byte[] rewrite(byte[] cls, Map<String, String> map) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(cls));
        int magic = in.readInt(), minor = in.readUnsignedShort(), major = in.readUnsignedShort(), count = in.readUnsignedShort();
        int[] tag = new int[count];
        Object[] val = new Object[count];
        int[][] refs = new int[count][];
        for (int i = 1; i < count; i++) {
            int t = in.readUnsignedByte();
            tag[i] = t;
            switch (t) {
                case UTF8 -> val[i] = in.readUTF();
                case INT, FLOAT -> val[i] = in.readInt();
                case LONG, DOUBLE -> { val[i] = in.readLong(); i++; }
                case CLASS, STRING, MTYPE, MODULE, PACKAGE -> refs[i] = new int[]{in.readUnsignedShort()};
                case FIELD, METHOD, IMETHOD, NAME_TYPE, DYNAMIC, INDY -> refs[i] = new int[]{in.readUnsignedShort(), in.readUnsignedShort()};
                case HANDLE -> refs[i] = new int[]{in.readUnsignedByte(), in.readUnsignedShort()};
                default -> throw new IOException("bad constant pool tag " + t);
            }
        }
        byte[] rest = in.readAllBytes();
        // classify every UTF8 by how it is used
        Map<Integer, String> edit = new HashMap<>();
        for (int i = 1; i < count; i++) {
            if (tag[i] == CLASS) {
                String s = (String) val[refs[i][0]];
                String m = mapName(s, map);
                if (!m.equals(s)) edit.put(refs[i][0], m);
            } else if (tag[i] == NAME_TYPE || tag[i] == MTYPE) {
                int d = refs[i][refs[i].length - 1];
                String s = (String) val[d];
                String m = mapDescriptor(s, map);
                if (!m.equals(s)) edit.put(d, m);
            }
        }
        // field and method descriptors and Signature attributes are plain UTF8s: any UTF8 that parses as a
        // descriptor and mentions the class, and is not a string constant's text, is such a reference
        Set<Integer> strings = new HashSet<>();
        for (int i = 1; i < count; i++) if (tag[i] == STRING) strings.add(refs[i][0]);
        for (int i = 1; i < count; i++) {
            if (tag[i] != UTF8 || strings.contains(i) || edit.containsKey(i)) continue;
            String s = (String) val[i];
            if (s.indexOf('L') >= 0 && (s.indexOf(';') >= 0 || s.indexOf('<') >= 0)) {
                String m = mapDescriptor(s, map);
                if (!m.equals(s)) edit.put(i, m);
            }
        }
        if (edit.isEmpty()) return cls;
        ByteArrayOutputStream bo = new ByteArrayOutputStream(cls.length + 64);
        DataOutputStream out = new DataOutputStream(bo);
        out.writeInt(magic);
        out.writeShort(minor);
        out.writeShort(major);
        out.writeShort(count);
        for (int i = 1; i < count; i++) {
            int t = tag[i];
            out.writeByte(t);
            switch (t) {
                case UTF8 -> out.writeUTF(edit.getOrDefault(i, (String) val[i]));
                case INT, FLOAT -> out.writeInt((Integer) val[i]);
                case LONG, DOUBLE -> { out.writeLong((Long) val[i]); i++; }
                case CLASS, STRING, MTYPE, MODULE, PACKAGE -> out.writeShort(refs[i][0]);
                case FIELD, METHOD, IMETHOD, NAME_TYPE, DYNAMIC, INDY -> { out.writeShort(refs[i][0]); out.writeShort(refs[i][1]); }
                case HANDLE -> { out.writeByte(refs[i][0]); out.writeShort(refs[i][1]); }
                default -> throw new IOException("bad tag");
            }
        }
        out.write(rest);
        return bo.toByteArray();
    }
}
