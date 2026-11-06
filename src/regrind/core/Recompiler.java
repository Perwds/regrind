package regrind.core;

import javax.tools.*;
import java.io.*;
import java.lang.classfile.ClassFile;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/** Compiles edited source against the workspace and swaps the resulting classes in. */
public final class Recompiler {
    private Recompiler() {}

    public record Problem(long line, long column, String message) {
        @Override public String toString() { return "line " + line + ": " + message; }
    }

    public record Outcome(boolean ok, List<String> replaced, List<Problem> problems, String log) {}

    private static final class MemSource extends SimpleJavaFileObject {
        final String code;
        MemSource(String name, String code) {
            super(URI.create("string:///" + name + ".java"), Kind.SOURCE);
            this.code = code;
        }
        @Override public CharSequence getCharContent(boolean ignore) { return code; }
    }

    private static final class MemClass extends SimpleJavaFileObject {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        MemClass(String name) { super(URI.create("mem:///" + name + ".class"), Kind.CLASS); }
        @Override public OutputStream openOutputStream() { return out; }
    }

    /**
     * @param topLevel internal name of the class the source declares
     * @param release  class file release to target, or 0 for the JDK default
     */
    public static Outcome compile(Workspace ws, String topLevel, String source, int release) throws IOException {
        JavaCompiler jc = ToolProvider.getSystemJavaCompiler();
        if (jc == null) return new Outcome(false, List.of(), List.of(new Problem(0, 0, "Running on a JRE: a JDK is needed to compile")), "");
        Path cp = Files.createTempDirectory("regrind-cp");
        try {
            for (var e : ws.classes.entrySet()) {
                Path p = cp.resolve(e.getKey() + ".class");
                Files.createDirectories(p.getParent());
                Files.write(p, e.getValue());
            }
            StringBuilder classpath = new StringBuilder(cp.toString());
            for (Path l : ws.libraries) classpath.append(File.pathSeparator).append(l);
            List<String> opts = new ArrayList<>(List.of("-nowarn", "-proc:none", "-g", "-classpath", classpath.toString()));
            if (release >= 8) opts.addAll(List.of("--release", String.valueOf(release)));

            DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
            Map<String, MemClass> output = new LinkedHashMap<>();
            StandardJavaFileManager std = jc.getStandardFileManager(diags, null, null);
            JavaFileManager fm = new ForwardingJavaFileManager<>(std) {
                @Override public JavaFileObject getJavaFileForOutput(Location l, String name, JavaFileObject.Kind k, FileObject sibling) {
                    MemClass m = new MemClass(name.replace('.', '/'));
                    output.put(name.replace('.', '/'), m);
                    return m;
                }
            };
            StringWriter log = new StringWriter();
            boolean ok = jc.getTask(log, fm, diags, opts, null, List.of(new MemSource(topLevel, source))).call();
            List<Problem> problems = new ArrayList<>();
            for (Diagnostic<? extends JavaFileObject> d : diags.getDiagnostics()) {
                if (d.getKind() == Diagnostic.Kind.ERROR) problems.add(new Problem(d.getLineNumber(), d.getColumnNumber(), d.getMessage(Locale.ROOT)));
            }
            if (!ok) return new Outcome(false, List.of(), problems, log.toString());
            List<String> replaced = new ArrayList<>();
            for (var e : output.entrySet()) {
                byte[] b = e.getValue().out.toByteArray();
                ClassFile.of().parse(b);
                ws.replaceClass(e.getKey(), b);
                replaced.add(e.getKey());
            }
            return new Outcome(true, replaced, problems, log.toString());
        } finally {
            try (Stream<Path> s = Files.walk(cp)) {
                s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }
}
