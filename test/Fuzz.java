import regrind.core.Workspace;
import regrind.decomp.Decompiler;
import java.lang.classfile.*;
import java.lang.classfile.attribute.StackMapTableAttribute;
import java.lang.constant.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;
import static java.lang.constant.ConstantDescs.*;

/**
 * Hostile input: corrupted class files must never hang or kill the process, and a method with
 * irreducible control flow must produce a stub, not an exception.
 */
public class Fuzz {
    public static void main(String[] a) throws Exception {
        irreducible();
        FileSystem fs = FileSystems.getFileSystem(URI.create("jrt:/"));
        Path root = fs.getPath("/modules/java.base/java/util");
        List<Path> files;
        try (Stream<Path> s = Files.walk(root)) {
            files = new ArrayList<>(s.filter(x -> x.toString().endsWith(".class")).toList());
        }
        Random r = new Random(42);
        Collections.shuffle(files, r);
        int ok = 0, rejected = 0, threw = 0, hung = 0, rounds = Integer.parseInt(a[0]);
        Map<String, Integer> kinds = new TreeMap<>();
        ExecutorService ex = pool();
        for (int i = 0; i < rounds; i++) {
            byte[] b = Files.readAllBytes(files.get(i % files.size())).clone();
            int flips = 1 + r.nextInt(12);
            for (int k = 0; k < flips; k++) b[8 + r.nextInt(b.length - 8)] = (byte) r.nextInt(256);
            Path tmp = Files.createTempFile("fuzz", ".class");
            Files.write(tmp, b);
            Workspace ws;
            try {
                ws = Workspace.open(tmp);
            } catch (Exception e) {
                rejected++;
                continue;
            } finally {
                Files.delete(tmp);
            }
            if (ws.classes.isEmpty()) { rejected++; continue; }
            String n = ws.classes.keySet().iterator().next();
            Future<?> f = ex.submit(() -> Decompiler.decompile(ws, n));
            try {
                f.get(10, TimeUnit.SECONDS);
                ok++;
            } catch (TimeoutException e) {
                hung++;
                System.out.println("HUNG on round " + i);
                f.cancel(true);
                ex.shutdownNow();
                ex = pool();
            } catch (ExecutionException e) {
                threw++;
                kinds.merge(e.getCause().getClass().getSimpleName(), 1, Integer::sum);
            }
        }
        ex.shutdownNow();
        System.out.printf("rounds=%d decompiled=%d rejected-at-load=%d class-level-exception=%d hung=%d %s%n", rounds, ok, rejected, threw, hung, kinds);
    }

    static ExecutorService pool() { return Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(null, r, "w", 1L << 29); t.setDaemon(true); return t; }); }

    /** Two entries into a loop: cannot be written with Java control flow. */
    static void irreducible() throws Exception {
        ClassDesc me = ClassDesc.of("Irr");
        byte[] bytes = ClassFile.of(ClassFile.StackMapsOption.DROP_STACK_MAPS).build(me, c -> c.withMethodBody("f", MethodTypeDesc.of(CD_int, CD_int), ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, b -> {
            Label x = b.newLabel(), y = b.newLabel();
            b.iload(0).ifeq(y);
            b.labelBinding(x);
            b.iinc(0, -1);
            b.labelBinding(y);
            b.iload(0).ifne(x);
            b.iconst_1().ireturn();
        }));
        Path dir = Files.createTempDirectory("irr");
        Files.write(dir.resolve("Irr.class"), bytes);
        Decompiler.Result r = Decompiler.decompile(Workspace.open(dir), "Irr");
        System.out.println("irreducible: failed=" + r.failed() + " " + r.failures());
        System.out.println(r.source());
    }
}
