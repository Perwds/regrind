package regrind.core;

import java.io.*;
import java.lang.classfile.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.*;

/** An opened jar, directory or single class, plus any edits the user has made to it. */
public final class Workspace {
    public Path source;
    /** internal class name -> bytes */
    public final Map<String, byte[]> classes = new TreeMap<>();
    /** internal class name -> original entry path */
    public final Map<String, String> entryPaths = new HashMap<>();
    /** resource path -> bytes (includes class files that failed to parse) */
    public final Map<String, byte[]> resources = new TreeMap<>();
    public final Set<String> modified = new TreeSet<>();
    /** Extra jars on the recompile/type-resolution classpath. */
    public final List<Path> libraries = new ArrayList<>();
    public final List<String> warnings = new ArrayList<>();

    private final Map<String, Optional<ClassModel>> models = new HashMap<>();
    private final Map<String, Optional<Info>> infos = new HashMap<>();

    public record Info(String name, String superName, List<String> interfaces, boolean isInterface) {}

    public static Workspace open(Path p) throws IOException {
        Workspace w = new Workspace();
        w.add(p);
        w.source = p;
        return w;
    }

    public void add(Path p) throws IOException {
        if (Files.isDirectory(p)) {
            try (Stream<Path> s = Files.walk(p)) {
                for (Path f : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator) {
                    addEntry(p.relativize(f).toString().replace('\\', '/'), Files.readAllBytes(f));
                }
            }
        } else {
            String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
            if (n.endsWith(".class")) {
                addEntry(p.getFileName().toString(), Files.readAllBytes(p));
            } else {
                try (ZipFile z = new ZipFile(p.toFile())) {
                    for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements();) {
                        ZipEntry en = e.nextElement();
                        if (en.isDirectory()) continue;
                        try (InputStream in = z.getInputStream(en)) {
                            addEntry(en.getName(), in.readAllBytes());
                        } catch (IOException | RuntimeException ex) {
                            warnings.add("Could not read " + en.getName() + ": " + ex.getMessage());
                        }
                    }
                }
            }
        }
    }

    private void addEntry(String path, byte[] data) {
        if (path.endsWith(".class") && !path.startsWith("META-INF/versions/") && looksLikeClass(data)) {
            try {
                String name = ClassFile.of().parse(data).thisClass().asInternalName();
                if (classes.containsKey(name)) {
                    warnings.add("Duplicate class " + name + " in " + path);
                    resources.put(path, data);
                    return;
                }
                classes.put(name, data);
                entryPaths.put(name, path);
                return;
            } catch (RuntimeException ex) {
                warnings.add("Could not parse " + path + ": " + ex.getMessage());
            }
        }
        resources.put(path, data);
    }

    private static boolean looksLikeClass(byte[] d) {
        return d.length > 8 && (d[0] & 255) == 0xCA && (d[1] & 255) == 0xFE && (d[2] & 255) == 0xBA && (d[3] & 255) == 0xBE;
    }

    public void replaceClass(String name, byte[] bytes) {
        classes.put(name, bytes);
        entryPaths.putIfAbsent(name, name + ".class");
        modified.add(name);
        models.remove(name);
        infos.remove(name);
    }

    /** Writes every class and resource to a jar, via a temp file so a failed export leaves the target intact. */
    public void export(Path out) throws IOException {
        Path tmp = Files.createTempFile(out.toAbsolutePath().getParent(), "regrind", ".tmp");
        try (ZipOutputStream z = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
            String mf = "META-INF/MANIFEST.MF";
            if (resources.containsKey(mf)) put(z, mf, resources.get(mf));
            for (var e : resources.entrySet()) if (!e.getKey().equals(mf)) put(z, e.getKey(), e.getValue());
            for (var e : classes.entrySet()) put(z, entryPaths.getOrDefault(e.getKey(), e.getKey() + ".class"), e.getValue());
        }
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Where to write a class's source under dir. Windows treats A.java and a.java as one file, so a
     * name that differs only by case gets a suffix instead of silently replacing the other class.
     */
    public static Path sourcePath(Path dir, String cls, Set<String> taken) {
        String n = cls;
        for (int i = 2; !taken.add(n.toLowerCase(Locale.ROOT)); i++) n = cls + "_" + i;
        return dir.resolve(n + ".java");
    }

    private static void put(ZipOutputStream z, String name, byte[] data) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(data);
        z.closeEntry();
    }

    /** Class model from the workspace or the running JDK / libraries; null if it can't be found. */
    public synchronized ClassModel model(String name) {
        Optional<ClassModel> cached = models.get(name);
        if (cached == null) {
            byte[] b = classes.get(name);
            if (b == null) b = external(name);
            cached = Optional.empty();
            if (b != null) {
                try {
                    cached = Optional.of(ClassFile.of().parse(b));
                } catch (RuntimeException ignored) {
                }
            }
            models.put(name, cached);
        }
        return cached.orElse(null);
    }

    private byte[] external(String name) {
        for (Path lib : libraries) {
            try (ZipFile z = new ZipFile(lib.toFile())) {
                ZipEntry e = z.getEntry(name + ".class");
                if (e != null) try (InputStream in = z.getInputStream(e)) { return in.readAllBytes(); }
            } catch (IOException ignored) {
            }
        }
        try (InputStream in = ClassLoader.getSystemResourceAsStream(name + ".class")) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    public synchronized Info info(String name) {
        Optional<Info> cached = infos.get(name);
        if (cached == null) {
            ClassModel m = model(name);
            cached = m == null ? Optional.empty() : Optional.of(new Info(name,
                    m.superclass().map(c -> c.asInternalName()).orElse(null),
                    m.interfaces().stream().map(c -> c.asInternalName()).toList(),
                    m.flags().has(java.lang.reflect.AccessFlag.INTERFACE)));
            infos.put(name, cached);
        }
        return cached.orElse(null);
    }

    public boolean exists(String name) { return info(name) != null; }

    /** True if a is known to be assignable to b; unknown hierarchies answer false. */
    public boolean isSubclass(String a, String b) {
        if (a.equals(b) || b.equals("java/lang/Object")) return true;
        Set<String> seen = new HashSet<>();
        Deque<String> q = new ArrayDeque<>(List.of(a));
        while (!q.isEmpty()) {
            String c = q.poll();
            if (c.equals(b)) return true;
            if (!seen.add(c)) continue;
            Info i = info(c);
            if (i == null) continue;
            if (i.superName() != null) q.add(i.superName());
            q.addAll(i.interfaces());
        }
        return false;
    }

    /** Nearest common superclass, or a shared interface when the classes only meet at Object. */
    public String commonSuper(String a, String b) {
        if (isSubclass(a, b)) return b;
        if (isSubclass(b, a)) return a;
        List<String> chain = new ArrayList<>();
        for (String c = a; c != null;) {
            chain.add(c);
            Info i = info(c);
            c = i == null ? null : i.superName();
        }
        for (String c = b; c != null;) {
            if (chain.contains(c) && !c.equals("java/lang/Object")) return c;
            Info i = info(c);
            c = i == null ? null : i.superName();
        }
        Set<String> ia = allInterfaces(a);
        for (String x : allInterfaces(b)) if (ia.contains(x)) return x;
        return "java/lang/Object";
    }

    private Set<String> allInterfaces(String n) {
        Set<String> out = new LinkedHashSet<>();
        Deque<String> q = new ArrayDeque<>(List.of(n));
        Set<String> seen = new HashSet<>();
        while (!q.isEmpty()) {
            String c = q.poll();
            if (!seen.add(c)) continue;
            Info i = info(c);
            if (i == null) continue;
            if (i.isInterface()) out.add(c);
            if (i.superName() != null) q.add(i.superName());
            q.addAll(i.interfaces());
        }
        return out;
    }
}
