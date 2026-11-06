import java.util.*;

public class Tricky {
    static StringBuilder log = new StringBuilder();
    static int say(int x) { log.append(x).append(' '); return x; }
    static boolean yes(String s) { log.append(s); return true; }
    static boolean no(String s) { log.append(s); return false; }
    static int[] data = {1, 2, 3, 4, 5, 6};
    static int idx = 0;
    static int next() { return idx++; }
    long wide = 5; double dbl = 1.5; static long swide = 2;
    int[] arr = new int[3];
    Tricky self() { log.append("self "); return this; }

    static int f1(int n) {
        int r = 0;
        for (int i = 0; i < n; i++) {
            try {
                if (i == 1) continue;
                if (i == 3) return r + 1000;
                try { if (i == 2) throw new RuntimeException(); r += 1; }
                finally { r += 10; }
            } catch (RuntimeException e) { r += 100; continue; }
            finally { r += 1000; if (i == 9) break; }
            r += 5;
        }
        return r;
    }
    static int f2(int x) { try { return x / (x - 1); } catch (ArithmeticException e) { return -1; } finally { log.append("fin "); } }
    @SuppressWarnings("finally")
    static int f3() { try { throw new RuntimeException("a"); } finally { return 7; } }
    static int f4(int x) { int y = 0; try { y = 1; if (x > 0) throw new IllegalStateException(); y = 2; } catch (IllegalStateException e) { y += 10; } y += 100; return y; }
    static int f5(int[] a) { int s = 0; int i = 0; while (true) { if (i >= a.length) break; if (a[i] < 0) { i++; continue; } if (a[i] == 99) return -s; s += a[i++]; } return s; }
    static int f6(int n) { int c = 0; do { n--; if (n == 2) continue; if (n == 0) break; c += n; } while (n > 0); return c; }
    static int f7(int a, int b) {
        int r = 0;
        outer: while (a > 0) {
            a--;
            int j = b;
            while (j > 0) { j--; if (j == 1) continue outer; if (a == 1) break outer; r += 2; }
            r += 100;
        }
        return r;
    }
    static String sw1(int x) {
        StringBuilder s = new StringBuilder();
        switch (x) { case 1: s.append("a"); default: s.append("d"); case 2: s.append("b"); break; case 3: { s.append("c"); if (x > 0) break; s.append("no"); } case 4: s.append("e"); }
        return s.toString();
    }
    static String sw2(String k) {
        String r = "";
        switch (k) { case "x": r += "1"; case "y": r += "2"; break; case "z": r += "3"; }
        return r + "!";
    }
    static int sw3(int a, int b) { int r = 0; for (int i = 0; i < 3; i++) { switch (i) { case 0: if (a > 0) continue; r += 1; break; case 1: if (b > 0) break; r += 2; default: r += 4; } r += 10; } return r; }
    static int eval1() { idx = 0; int[] d = data; d[next()] += next() * 10; return d[0] + idx; }
    static int eval2() { int i = 1; i = i++ + ++i; int j = 2; j += j++ * 2; int k = 3; k = k-- - --k; return i * 100 + j * 10 + k; }
    static int eval3() { int a = 1; int b = (a = 5) + a; int c, d; c = d = b + 1; int[] m = new int[2]; int i = 0; m[i] = i = 1; return a + b * 10 + c * 100 + d + m[0] * 7 + m[1] * 3 + i; }
    static int eval4(Tricky t) { t.self().wide += 3; t.self().arr[1] += 4; int v = t.self().arr[1]++; long w = t.wide++; long x = ++t.wide; double y = t.dbl--; swide <<= 2; long z = swide++; return v + (int) w + (int) x + (int) y + (int) z + t.arr[1]; }
    static long eval5() { long[] a = {1, 2}; long b = a[0]++ + ++a[1]; long c = a[0] = b; double[] e = {1.5}; double f = e[0]++; return b + c + (long) f + (long) e[0]; }
    static int eval6() { int x = 0; x += say(1) + say(2) * say(3); return x + (say(4) > 3 ? say(5) : say(6)); }
    static String bools(int a, int b) {
        boolean p = a > 0, q = b > 0;
        boolean r = p ^ q, s = p & q, t = p | q, u = !p;
        p &= q; q |= r;
        boolean v = (a > 1 || b > 1) && !(a == b);
        boolean w = a > 0 ? b > 0 : b < 0;
        int n = (p ? 1 : 0) + (q ? 2 : 0);
        if (yes("A") && (no("B") || yes("C")) && !no("D")) n += 4;
        if (no("E") || no("F") && yes("G")) n += 8;
        return "" + p + q + r + s + t + u + v + w + n;
    }
    static boolean ret(int a) { return a > 0 && a < 10 || a == 42; }
    static int cond2(int a, int b, int c) { return a > b ? (b > c ? 1 : 2) : (a > c ? 3 : 4); }
    static String nullSafe(String s) { return s == null ? "null" : s.isEmpty() ? "empty" : s; }
    static char chars(char c, int n) { c += n; c++; char d = (char) (c + 1); int i = c - 'a'; return i > 2 ? d : c; }
    static byte bytes(byte b) { b <<= 1; b += 200; short s = (short) (b * 300); return (byte) (s >> 2); }
    static int shifts(int a, long b) { return (a >>> 3) + (int) (b << 40 >>> 38) + (-a >> 1) + (~a & 0xff); }
    static float fl(float a, float b) { float c = a % b; c = -c; return c > 0 ? c : a / b; }
    static boolean nan(double d, float f) { boolean a = d < 1, b = d > 1, c = d <= 1, e = d >= 1, g = !(f < 1), h = !(f >= 1); return a | b | c | e | !g | !h; }
    static int nested(int n) { int s = 0; for (int i = 0, j = n; i < j; i++, j--) { s += i * j; } for (;;) { if (s > 5) break; s += 3; } return s; }
    static int syncLoop(Object o, int n) { int c = 0; for (int i = 0; i < n; i++) { synchronized (o) { if (i == 1) continue; if (i == 3) break; c += i + 1; } c += 10; } return c; }
    static Object tern(boolean b) { Number n = b ? Integer.valueOf(1) : Double.valueOf(2); long l = b ? 1 : 2L; return "" + n + l; }
    static List<String> iter(Map<String, Integer> m) {
        List<String> out = new ArrayList<>();
        for (Iterator<Map.Entry<String, Integer>> it = m.entrySet().iterator(); it.hasNext();) { Map.Entry<String, Integer> e = it.next(); if (e.getValue() < 0) { it.remove(); continue; } out.add(e.getKey() + e.getValue()); }
        return out;
    }
    static String box() { Integer a = 5; int b = a + 1; Long c = 7L; long d = c * b; Character e = 'x'; char f = e; Boolean g = true; a++; return "" + a + b + c + d + e + f + (g ? "t" : "f"); }
    static int ctorArgs(boolean b) { return new int[]{b ? 1 : 2, b ? 3 : 4}[1] + new StringBuilder(b ? "ab" : "c").length() + Math.max(b ? 5 : 6, new Integer(b ? 7 : 8)); }
    static int multiDim() { int[][][] a = new int[2][3][]; a[1][2] = new int[]{9}; long[][] b = {{1}, {2, 3}}; return a[1][2][0] + (int) b[1][1] + a.length + a[0].length + b[1].length; }
    static int instOf(Object o) { if (o instanceof String && ((String) o).length() > 1) return 1; if (!(o instanceof Integer)) return 2; return (Integer) o + 1; }
    static class Res implements AutoCloseable { String n; Res(String n) { this.n = n; log.append("open" + n); } public void close() { log.append("close" + n); } }
    static int twr(boolean fail) { try (Res a = new Res("a"); Res b = new Res("b")) { if (fail) throw new RuntimeException("x"); return 1; } catch (RuntimeException e) { return 2; } finally { log.append("end "); } }
    static class P { int v = 1; P() { init(); } void init() { v = 2; } int get() { return v; } }
    static class Q extends P { int w = 3; Q() { super(); w += v; } @Override void init() { v = 5; w = 7; } @Override int get() { return super.get() * 10 + w; } }
    static int sinit; static { for (int i = 0; i < 4; i++) sinit += i; if (sinit > 3) sinit *= 2; }
    public static void main(String[] args) {
        System.out.println(f1(3) + " " + f1(5) + " " + f2(1) + " " + f2(3) + " " + f3() + " " + f4(0) + " " + f4(1));
        System.out.println(f5(new int[]{1, -2, 3}) + " " + f5(new int[]{1, 99, 3}) + " " + f6(5) + " " + f7(3, 3) + " " + f7(2, 4));
        for (int i = 0; i < 6; i++) System.out.print(sw1(i) + ",");
        System.out.println(sw2("x") + sw2("y") + sw2("z") + sw2("w") + " " + sw3(1, 1) + " " + sw3(0, 0) + " " + sw3(1, 0));
        System.out.println(eval1() + " " + eval2() + " " + eval3() + " " + eval4(new Tricky()) + " " + eval5() + " " + eval6());
        for (int a = -1; a < 3; a++) for (int b = -1; b < 3; b++) System.out.print(bools(a, b) + ";");
        System.out.println();
        System.out.println(ret(5) + " " + ret(42) + " " + ret(11) + " " + cond2(3, 2, 1) + cond2(3, 2, 5) + cond2(1, 2, 0) + cond2(1, 2, 5) + nullSafe(null) + nullSafe("") + nullSafe("s"));
        System.out.println((int) chars('a', 1) + " " + bytes((byte) 77) + " " + shifts(-77, 12345L) + " " + fl(7.5f, 2f) + " " + nan(Double.NaN, Float.NaN) + " " + nested(6));
        System.out.println(syncLoop(new Object(), 5) + " " + tern(true) + tern(false) + " " + iter(new TreeMap<>(Collections.singletonMap("k", 1))) + box() + ctorArgs(true) + ctorArgs(false) + multiDim());
        System.out.println(instOf("ab") + " " + instOf("a") + " " + instOf(4) + " " + twr(false) + twr(true) + " " + new Q().get() + " " + sinit);
        System.out.println(log);
    }
}
