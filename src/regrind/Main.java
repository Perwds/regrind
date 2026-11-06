package regrind;

import regrind.core.Workspace;
import regrind.decomp.Decompiler;
import java.nio.file.*;
import java.util.*;

/** Command line entry point. With no arguments, or with --gui, opens the workbench. */
public final class Main {
    public static void main(String[] args) throws Exception {
        List<String> a = new ArrayList<>(List.of(args));
        if (a.contains("--help") || a.contains("-h")) {
            usage();
            return;
        }
        if (a.isEmpty() || a.remove("--gui")) {
            regrind.gui.Workbench.launch(a.isEmpty() ? null : Path.of(a.get(0)));
            return;
        }
        String out = null;
        boolean stats = false;
        String names = null;
        List<String> inputs = new ArrayList<>(), libs = new ArrayList<>();
        for (int i = 0; i < a.size(); i++) {
            String s = a.get(i);
            switch (s) {
                case "--outputdir", "-o" -> out = a.get(++i);
                case "--lib", "-cp" -> libs.add(a.get(++i));
                case "--stats" -> stats = true;
                case "--names" -> names = a.get(++i);
                default -> inputs.add(s);
            }
        }
        if (inputs.isEmpty()) {
            usage();
            System.exit(2);
        }
        int failed = 0, methods = 0, classes = 0, crashed = 0;
        Workspace ws = Workspace.open(Path.of(inputs.get(0)));
        for (int i = 1; i < inputs.size(); i++) ws.add(Path.of(inputs.get(i)));
        for (String l : libs) ws.libraries.add(Path.of(l));
        for (String w : ws.warnings) System.err.println("warning: " + w);
        if (names != null) System.err.println("renamed " + regrind.core.Renamer.applyNameFile(ws, Path.of(names)) + " classes from " + names);
        Set<String> taken = new HashSet<>(), printed = new HashSet<>();
        for (String n : new ArrayList<>(ws.classes.keySet())) {
            if (!Decompiler.isTopLevel(ws, n)) continue;
            try {
                Decompiler.Result r = Decompiler.decompileDeep(ws, n);
                classes += r.classes();
                methods += r.methods();
                failed += r.failed();
                printed.addAll(r.printed());
                for (String f : r.failures()) System.err.println("fallback: " + f);
                if (out == null) System.out.println(r.source());
                else {
                    Path p = Workspace.sourcePath(Path.of(out), n, taken);
                    Files.createDirectories(p.getParent());
                    Files.writeString(p, r.source());
                }
            } catch (RuntimeException | java.io.IOException e) {
                // obfuscated names may not be legal file names: lose that class, not the run
                crashed++;
                System.err.println("crash: " + n + ": " + e);
            }
        }
        Set<String> missing = new TreeSet<>(ws.classes.keySet());
        missing.removeAll(printed);
        for (String m : missing) System.err.println("not printed: " + m);
        if (stats || out != null) {
            System.err.printf("%d classes, %d methods, %d methods fell back to disassembly, %d classes crashed%n", classes, methods, failed, crashed);
        }
    }

    private static void usage() {
        System.out.println("""
                Regrind - Java decompiler and class workbench

                  java -jar regrind.jar                         open the workbench
                  java -jar regrind.jar --gui app.jar           open a jar in the workbench
                  java -jar regrind.jar app.jar -o src          decompile everything into a folder
                  java -jar regrind.jar Foo.class               decompile to the console

                Options:
                  -o, --outputdir DIR   write .java files to DIR
                  --lib JAR             extra jar for resolving types (repeatable)
                  --names FILE          rename classes first, from lines like pkg.Old=New
                  --stats               print totals when finished
                """);
    }
}
