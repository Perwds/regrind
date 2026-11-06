import java.util.*;
import java.io.*;

public class Basics {
    static int counter = 3;
    int field = 7;
    long[] arr = new long[4];
    static final String NAME = "basics";
    enum Color { RED, GREEN("g", 2), BLUE { @Override String label() { return "bloo"; } };
        final String code; final int n;
        Color() { this("x", 0); }
        Color(String c, int n) { code = c; this.n = n; }
        String label() { return name().toLowerCase() + code + n; }
    }
    interface Shape { double area(); default String describe() { return "area=" + area(); } }
    static class Sq implements Shape { double s; Sq(double s) { this.s = s; } public double area() { return s * s; } }
    class Inner { int v = field + 1; int outerField() { return field * v; } }
    static abstract class Base<T> { abstract T make(); T twice() { make(); return make(); } }
    static int fib(int n) { return n < 2 ? n : fib(n - 1) + fib(n - 2); }
    static boolean isEven(int x) { return x % 2 == 0; }
    static String loops(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) { if (i == 2) continue; if (i == 5) break; sb.append(i).append(','); }
        int j = 0;
        while (j < 3) { sb.append('w'); j++; }
        do { sb.append('d'); j--; } while (j > 0);
        outer:
        for (int a = 0; a < 3; a++) {
            for (int b = 0; b < 3; b++) {
                if (b == 2) continue outer;
                if (a == 2) break outer;
                sb.append(a * b);
            }
        }
        return sb.toString();
    }
    static int sw(int x) {
        int r = 0;
        switch (x) { case 1: r = 10; break; case 2: r = 20; case 3: r += 30; break; case 100: return -1; default: r = 99; }
        return r;
    }
    static String strSw(String s) {
        switch (s) { case "alpha": return "A"; case "beta": case "gamma": return "BG"; case "Aa": return "c1"; case "BB": return "c2"; default: return "?"; }
    }
    static String enumSw(Color c) {
        switch (c) { case RED: return "r"; case GREEN: return "g"; default: return c.label(); }
    }
    static int tryIt(String s) {
        int v = -1;
        try { v = Integer.parseInt(s); } catch (NumberFormatException e) { v = -2; } finally { counter++; }
        return v;
    }
    static int tryRet(int x) {
        try { if (x > 0) return x * 2; return x - 1; } finally { counter += 2; }
    }
    static String multi(int x) {
        try {
            if (x == 0) throw new IOException("io");
            if (x == 1) throw new IllegalStateException("ise");
            return "ok";
        } catch (IOException | IllegalStateException e) { return e.getMessage(); }
        catch (RuntimeException e) { return "rt"; }
    }
    static String res(String text) throws IOException {
        try (StringReader r = new StringReader(text); BufferedReader b = new BufferedReader(r)) { return b.readLine(); }
    }
    final Object lock = new Object();
    int sync(int a) { synchronized (lock) { field += a; if (a > 5) return 1; } return field; }
    static int ops(int a, int b, long c) {
        int x = a + b * 3 - (a ^ b) | (a & 7);
        x <<= 2; x >>= 1; x >>>= 1;
        long y = c * a + (c >> 3);
        byte bb = (byte) x; short ss = (short) y; char ch = (char) (65 + a);
        bb += 3; ch++;
        double d = a / 2.0; float f = (float) d + 1f;
        boolean t = a > b && b > 0 || c == 5;
        boolean u = !t;
        int ter = t ? 1 : u ? 2 : 3;
        return x + (int) y + bb + ss + ch + (int) d + (int) f + ter + (t ? 1 : 0);
    }
    static int incs(int[] a) {
        int i = 0; int r = a[i++]; r += a[++i]; a[i]++; a[0] += 5; r += a[i--];
        int k = i++ + ++i;
        return r + k + i;
    }
    int fieldOps() { field++; int a = field++; int b = ++field; this.field += 2; arr[1]++; counter++; return a + b + counter++; }
    static double floats(double a, float b) {
        double r = 0;
        if (a < 1.5) r += 1; if (a >= 2) r += 2; if (b > 3f) r += 4; if (b <= 4f) r += 8; if (!(a > 0)) r += 16; if (a != a) r += 32;
        return r;
    }
    static int[] arrays() { int[] a = {1, 2, 3}; int[][] m = new int[2][3]; m[1][2] = a[1]; String[] s = new String[]{"a", null}; return new int[]{m[1][2], s.length, a.length}; }
    static String concat(int a, String b, char c, long d) { String s = "v=" + a + b + c + d; s += "!"; return 1 + 2 + s + 1 + 2; }
    static long sum(List<Integer> l) { long t = 0; for (int x : l) t += x; for (Integer y : l) { if (y == null) continue; t += y; } return t; }
    static int arrSum(int[] a) { int t = 0; for (int x : a) t += x; return t; }
    static <T extends Comparable<T>> T max(List<T> l) { T b = null; for (T x : l) if (b == null || x.compareTo(b) > 0) b = x; return b; }
    static int varargs(String f, int... xs) { return f.length() + xs.length; }
    static boolean cond(String s, int n) { if (s != null && s.length() > n || n == 0) return true; return false; }
    static String nested(int a, int b) { if (a > 0) { if (b > 0) return "pp"; else return "pn"; } else if (b > 0) { return "np"; } return "nn"; }
    static int whileRead(String t) throws IOException { BufferedReader r = new BufferedReader(new StringReader(t)); String l; int c = 0; while ((l = r.readLine()) != null) c += l.length(); return c; }
    static int assertIt(int x) { assert x > 0 : "must be positive"; return x; }
    static int anon(final int k) {
        final int[] box = {0};
        Runnable r = new Runnable() { int inner = 2; public void run() { box[0] = k * inner; } };
        r.run();
        return box[0];
    }
    int localClass(final int a) {
        class Adder { int extra = 1; int plus(int b) { return a + b + extra + field; } }
        return new Adder().plus(2);
    }
    static int localStatic(final int a) {
        class Doubler { int twice() { return a * 2; } }
        return new Doubler().twice();
    }
    static Object chainTernary(int x) { return x > 0 ? "pos" : x < 0 ? Integer.valueOf(x) : null; }
    static char cc(boolean b, char c) { return b ? c : 'z'; }
    public static void main(String[] args) throws Exception {
        System.out.println(fib(10) + " " + isEven(3) + " " + loops(8));
        for (int i : new int[]{1, 2, 3, 100, 7}) System.out.print(sw(i) + " ");
        System.out.println();
        for (String s : new String[]{"alpha", "beta", "gamma", "Aa", "BB", "zz"}) System.out.print(strSw(s));
        System.out.println();
        for (Color c : Color.values()) System.out.print(enumSw(c) + c.label());
        System.out.println();
        System.out.println(tryIt("5") + " " + tryIt("x") + " " + tryRet(3) + " " + tryRet(-3) + " " + counter);
        System.out.println(multi(0) + multi(1) + multi(2) + res("hello\nw"));
        Basics b = new Basics();
        System.out.println(b.sync(2) + " " + b.sync(9) + " " + b.fieldOps() + " " + b.field);
        System.out.println(ops(3, 4, 5L) + " " + incs(new int[]{1, 2, 3, 4}));
        System.out.println(floats(1.0, 3.5f) + " " + floats(Double.NaN, Float.NaN) + " " + Arrays.toString(arrays()));
        System.out.println(concat(1, "b", 'c', 4L) + sum(Arrays.asList(1, 2, 3)) + arrSum(new int[]{4, 5}));
        System.out.println(max(Arrays.asList("a", "c", "b")) + varargs("xy", 1, 2) + cond("abc", 1) + cond(null, 1) + nested(1, -1) + nested(-1, 1));
        System.out.println(whileRead("ab\ncde") + " " + assertIt(4) + " " + anon(21) + " " + chainTernary(0) + chainTernary(-1) + cc(true, 'q'));
        System.out.println(b.localClass(4) + " " + localStatic(4));
        System.out.println(new Sq(3).describe() + " " + b.new Inner().outerField() + " " + NAME.length());
        System.out.println(new Base<String>() { int n; String make() { return "m" + (n++); } }.twice());
    }
}
