import java.util.*;
import java.util.function.*;
import java.util.stream.*;

public class Modern {
    record Point(int x, int y) implements Comparable<Point> {
        static int made = 0;
        Point { if (x < 0) throw new IllegalArgumentException("neg"); made++; }
        int sum() { return x + y; }
        public int compareTo(Point o) { return Integer.compare(sum(), o.sum()); }
    }
    record Name(String first, List<String> rest) {}
    sealed interface Expr permits Num, Add {}
    record Num(int v) implements Expr {}
    record Add(Expr l, Expr r) implements Expr {}
    static int eval(Expr e) {
        if (e instanceof Num n) return n.v();
        if (e instanceof Add a) return eval(a.l()) + eval(a.r());
        throw new IllegalStateException();
    }
    enum Op implements IntBinaryOperator {
        PLUS { public int applyAsInt(int a, int b) { return a + b; } },
        TIMES { public int applyAsInt(int a, int b) { return a * b; } };
    }
    int base = 10;
    int addBase(int x) { IntUnaryOperator f = v -> v + base + x; return f.applyAsInt(1); }
    static List<String> streams(List<String> in) {
        return in.stream().filter(s -> s.length() > 1).map(String::toUpperCase).sorted(Comparator.comparing(String::length)).collect(Collectors.toList());
    }
    static Supplier<List<String>> sup() { return ArrayList::new; }
    static Function<Integer, int[]> mk() { return int[]::new; }
    static String lam(int k) {
        Runnable r = () -> { for (int i = 0; i < k; i++) { System.out.print(i); } System.out.println(); };
        r.run();
        BiFunction<Integer, Integer, Integer> add = (a, b) -> { int c = a + b; return c * k; };
        return String.valueOf(add.apply(1, 2));
    }
    static String swExpr(int d) {
        String s = switch (d) { case 1, 7 -> "weekend"; case 2 -> { String t = "mon"; yield t + "day"; } default -> "mid"; };
        return s;
    }
    static int swOld(String s) { return switch (s) { case "a" -> 1; case "b" -> 2; default -> s.length(); }; }
    static String text() { return """
        hello
          "world"
        """; }
    static String concatMix(int a, double b, Object o, String n) { return "a=" + a + ",b=" + b + ",o=" + o + "," + n + '!'; }
    static int nestedTry(int x) {
        int r = 0;
        try {
            try { if (x == 1) throw new RuntimeException("a"); r += 1; }
            catch (RuntimeException e) { r += 10; throw new IllegalStateException(e); }
            finally { r += 100; }
        } catch (IllegalStateException e) { r += 1000; }
        finally { r += 10000; }
        return r;
    }
    static int loopTry(int n) {
        int c = 0;
        for (int i = 0; i < n; i++) {
            try { if (i % 2 == 0) continue; if (i == 5) break; c += i; }
            finally { c += 100; }
        }
        return c;
    }
    static <K, V extends Comparable<V>> K best(Map<K, V> m) {
        K b = null; V v = null;
        for (Map.Entry<K, V> e : m.entrySet()) { if (v == null || e.getValue().compareTo(v) > 0) { v = e.getValue(); b = e.getKey(); } }
        return b;
    }
    class Counter { int c; class Deep { int get() { return c + base; } } }
    static int localCls(int a) {
        class Adder { int plus(int b) { return a + b; } }
        return new Adder().plus(2);
    }
    public static void main(String[] args) {
        Point p = new Point(1, 2);
        System.out.println(p + " " + p.sum() + " " + p.equals(new Point(1, 2)) + " " + Point.made + new Name("a", List.of("b")));
        System.out.println(eval(new Add(new Num(2), new Add(new Num(3), new Num(4)))));
        System.out.println(Op.PLUS.applyAsInt(2, 3) + " " + Op.TIMES.applyAsInt(2, 3) + " " + new Modern().addBase(5));
        System.out.println(streams(List.of("a", "bbb", "cc")) + " " + sup().get() + mk().apply(3).length + lam(3));
        System.out.println(swExpr(1) + swExpr(2) + swExpr(3) + swOld("a") + swOld("zzz") + text() + concatMix(1, 2.5, "o", null));
        System.out.println(nestedTry(1) + " " + nestedTry(2) + " " + loopTry(8) + " " + best(Map.of("x", 1)) + localCls(4));
        Modern m = new Modern(); Counter c = m.new Counter(); c.c = 5; Counter.Deep d = c.new Deep(); System.out.println(d.get());
    }
}
