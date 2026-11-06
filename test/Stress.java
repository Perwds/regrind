import regrind.core.Workspace;
import regrind.decomp.Decompiler;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;

/** Decompiles classes from the running JDK image and reports crash and fallback counts. */
public class Stress {
    public static void main(String[] a) throws Exception {
        String module = a[0], prefix = a.length > 1 ? a[1] : "";
        Path outDir = a.length > 2 ? Path.of(a[2]) : null;
        FileSystem fs = FileSystems.getFileSystem(URI.create("jrt:/"));
        Path root = fs.getPath("/modules", module);
        Path tmp = Files.createTempDirectory("stress");
        Path start = prefix.isEmpty() ? root : root.resolve(prefix);
        int n = 0;
        try (Stream<Path> s = Files.walk(start)) {
            for (Path p : (Iterable<Path>) s.filter(x -> x.toString().endsWith(".class"))::iterator) {
                Path dst = tmp.resolve(root.relativize(p).toString());
                Files.createDirectories(dst.getParent());
                Files.write(dst, Files.readAllBytes(p));
                n++;
            }
        }
        System.out.println("classes on disk: " + n);
        Workspace ws = Workspace.open(tmp);
        List<String> tops = new ArrayList<>();
        for (String c : ws.classes.keySet()) if (Decompiler.isTopLevel(ws, c)) tops.add(c);
        long t0 = System.currentTimeMillis();
        int classes = 0, methods = 0, failed = 0, crashed = 0, slow = 0;
        Map<String, Integer> reasons = new TreeMap<>();
        ExecutorService ex = Executors.newSingleThreadExecutor(r -> new Thread(null, r, "w", 1L << 29));
        for (String c : tops) {
            long t = System.currentTimeMillis();
            Future<Decompiler.Result> f = ex.submit(() -> Decompiler.decompile(ws, c));
            try {
                Decompiler.Result r = f.get(60, TimeUnit.SECONDS);
                classes += r.classes();
                methods += r.methods();
                failed += r.failed();
                for (String w : r.failures()) {
                    String why = w.substring(w.lastIndexOf(": ") + 2);
                    reasons.merge(why.length() > 70 ? why.substring(0, 70) : why, 1, Integer::sum);
                }
                if (outDir != null) {
                    Path p = outDir.resolve(c + ".java");
                    Files.createDirectories(p.getParent());
                    Files.writeString(p, r.source());
                }
            } catch (TimeoutException e) {
                System.out.println("TIMEOUT " + c);
                f.cancel(true);
                ex.shutdownNow();
                ex = Executors.newSingleThreadExecutor(r -> new Thread(null, r, "w", 1L << 29));
                crashed++;
            } catch (ExecutionException e) {
                crashed++;
                Throwable th = e.getCause();
                StackTraceElement at = th.getStackTrace().length > 0 ? th.getStackTrace()[0] : null;
                System.out.println("CRASH " + c + ": " + th + " at " + at);
            }
            if (System.currentTimeMillis() - t > 5000) slow++;
        }
        ex.shutdownNow();
        System.out.printf("top-level=%d classes=%d methods=%d fallback=%d (%.2f%%) crashed=%d slow=%d in %.1fs%n", tops.size(), classes, methods, failed,
                100.0 * failed / Math.max(1, methods), crashed, slow, (System.currentTimeMillis() - t0) / 1000.0);
        reasons.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(15).forEach(e -> System.out.println(e.getValue() + "  " + e.getKey()));
        try (Stream<Path> s = Files.walk(tmp)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }
}
