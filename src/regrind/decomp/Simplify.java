package regrind.decomp;

import regrind.decomp.Ast.*;
import java.util.*;

/** Rewrites the labeled-block output of the structurer into ordinary if/while/do/switch shapes. */
final class Simplify {
    private boolean changed;

    static void run(List<Stmt> body) {
        Simplify s = new Simplify();
        int rounds = 0;
        do {
            s.changed = false;
            s.list(body);
        } while (s.changed && ++rounds < 500);
    }

    private void list(List<Stmt> l) {
        for (int i = 0; i < l.size(); i++) {
            Stmt s = l.get(i);
            s.lists(this::list);
            switch (s) {
                case Labeled b -> i = labeled(l, i, b);
                case Loop p -> i = loop(l, i, p);
                case If f -> i = ifs(l, i, f);
                case Try t -> {}
                default -> {}
            }
        }
        // nothing is reachable after an unconditional jump
        for (int i = 0; i + 1 < l.size(); i++) {
            if (l.get(i) instanceof Break || l.get(i) instanceof Continue || l.get(i) instanceof Return || l.get(i) instanceof Throw) {
                while (l.size() > i + 1) l.remove(l.size() - 1);
                changed = true;
            }
        }
    }

    // ---------------------------------------------------------------- jump analysis

    /** Removes jumps to target in tail position (where falling off the end means the same thing). */
    private boolean stripTail(List<Stmt> l, Stmt target) {
        if (l.isEmpty()) return false;
        Stmt last = l.get(l.size() - 1);
        boolean ch = false;
        switch (last) {
            case Break b when b.target == target -> { l.remove(l.size() - 1); ch = true; }
            case Continue c when c.target == target -> { l.remove(l.size() - 1); ch = true; }
            case If f -> {
                ch = stripTail(f.a, target);
                if (f.b != null) ch |= stripTail(f.b, target);
            }
            case Labeled m -> ch = stripTail(m.body, target);
            case Sync y -> ch = stripTail(y.body, target);
            case Try t -> {
                ch = stripTail(t.body, target);
                for (CatchClause c : t.catches) ch |= stripTail(c.body, target);
            }
            default -> {}
        }
        return ch;
    }

    private static int countJumps(List<Stmt> l, Stmt target) {
        int[] n = {0};
        Ast.visit(l, s -> {
            if (s instanceof Break b && b.target == target || s instanceof Continue c && c.target == target) n[0]++;
        });
        return n[0];
    }

    /** Does control never fall off the end of this list? */
    static boolean exits(List<Stmt> l) {
        if (l.isEmpty()) return false;
        Stmt s = l.get(l.size() - 1);
        return switch (s) {
            case Break b -> true;
            case Continue c -> true;
            case Return r -> true;
            case Throw t -> true;
            case If f -> f.b != null && exits(f.a) && exits(f.b);
            default -> false;
        };
    }

    private static boolean endsWith(List<Stmt> l, Stmt target) {
        if (l == null || l.isEmpty()) return false;
        Stmt s = l.get(l.size() - 1);
        return s instanceof Break b && b.target == target || s instanceof Continue c && c.target == target;
    }

    /** if (c) { A; jump T; } R  =>  if (c) { A } else { R }, in a list that is tail of T. */
    private boolean elseify(List<Stmt> l, Stmt target) {
        boolean ch = false;
        for (int i = 0; i < l.size(); i++) {
            if (!(l.get(i) instanceof If f) || i == l.size() - 1) continue;
            boolean aJumps = endsWith(f.a, target), bJumps = endsWith(f.b, target);
            if (aJumps && (f.b == null || f.b.isEmpty())) {
                f.a.remove(f.a.size() - 1);
                f.b = new ArrayList<>(l.subList(i + 1, l.size()));
                l.subList(i + 1, l.size()).clear();
                ch = true;
            } else if (bJumps && !exits(f.a)) {
                f.b.remove(f.b.size() - 1);
                f.a.addAll(l.subList(i + 1, l.size()));
                l.subList(i + 1, l.size()).clear();
                ch = true;
            }
        }
        if (!l.isEmpty() && l.get(l.size() - 1) instanceof If f) {
            ch |= elseify(f.a, target);
            if (f.b != null) ch |= elseify(f.b, target);
        }
        return ch;
    }

    private static void retarget(List<Stmt> l, Stmt from, Stmt to) {
        Ast.visit(l, s -> { if (s instanceof Break b && b.target == from) b.target = to; });
    }

    // ---------------------------------------------------------------- labeled blocks

    private int labeled(List<Stmt> l, int i, Labeled b) {
        boolean ch = stripTail(b.body, b);
        ch |= elseify(b.body, b);
        if (!b.body.isEmpty() && b.body.get(b.body.size() - 1) instanceof Switch sw && fallThrough(l, i, b, sw)) ch = true;
        if (!b.body.isEmpty() && (b.body.get(b.body.size() - 1) instanceof Loop || b.body.get(b.body.size() - 1) instanceof Switch)) {
            Stmt tail = b.body.get(b.body.size() - 1);
            if (countJumps(b.body.subList(0, b.body.size() - 1), b) == 0 && countJumps(List.of(tail), b) > 0) {
                retarget(b.body, b, tail);
                ch = true;
            }
        }
        if (countJumps(b.body, b) == 0) {
            l.remove(i);
            l.addAll(i, b.body);
            changed = true;
            return i - 1;
        }
        changed |= ch;
        return i;
    }

    /** Recover "case 1: A; case 2: B;" from { switch ... } B. */
    private boolean fallThrough(List<Stmt> l, int i, Labeled b, Switch sw) {
        if (i == l.size() - 1) return false;
        int k = -1;
        for (int c = 0; c < sw.cases.size(); c++) {
            List<Stmt> body = sw.cases.get(c).body;
            if (body.size() == 1 && body.get(0) instanceof Break x && x.target == b) k = c;
        }
        if (k <= 0) return false;
        Case ck = sw.cases.get(k);
        if (ck.isDefault && k == sw.cases.size() - 1) return false;
        for (int c = 0; c < sw.cases.size(); c++) {
            if (c == k) continue;
            List<Stmt> body = sw.cases.get(c).body;
            int n = countJumps(body, b);
            if (n == 0) continue;
            if (c != k - 1 || n != 1 || !endsWith(body, b)) return false;
        }
        List<Stmt> prev = sw.cases.get(k - 1).body;
        if (endsWith(prev, b)) prev.remove(prev.size() - 1);
        List<Stmt> rest = new ArrayList<>(l.subList(i + 1, l.size()));
        l.subList(i + 1, l.size()).clear();
        ck.body = rest;
        if (!exits(rest) && k < sw.cases.size() - 1) rest.add(new Break(sw));
        return true;
    }

    // ---------------------------------------------------------------- ifs

    private int ifs(List<Stmt> l, int i, If f) {
        if (f.b != null && f.b.isEmpty()) f.b = null;
        if (f.a.isEmpty() && f.b != null) {
            f.c = Sim.negate(f.c);
            f.a = f.b;
            f.b = null;
            changed = true;
        }
        if (f.a.isEmpty() && f.b == null) {
            boolean effects = f.c.any(x -> x instanceof Invoke || x instanceof Assign || x instanceof IncDec || x instanceof NewObj);
            if (effects && !Ast.statementExpr(f.c)) return i; // stays as an empty if
            if (effects) l.set(i, new ExprStmt(f.c));
            else l.remove(i--);
            changed = true;
            return i;
        }
        if (f.b != null && exits(f.a)) {
            l.addAll(i + 1, f.b);
            f.b = null;
            changed = true;
        }
        return i;
    }

    // ---------------------------------------------------------------- loops

    private int loop(List<Stmt> l, int i, Loop p) {
        if (p.kind != LoopKind.WHILE || p.cond != null) return i;
        List<Stmt> b = p.body;
        boolean ch = stripTail(b, p);
        ch |= elseify(b, p);
        ch |= stripContinueInElse(b, p);
        changed |= ch;
        if (b.isEmpty()) return i;

        // while (true) { if (c) { X; exit } Y }  =>  while (!c) { Y } X
        if (b.get(0) instanceof If f && f.b == null && exits(f.a) && noContinue(f.a, p)) {
            List<Stmt> y = b.subList(1, b.size());
            if (canHoist(f.a, y, p)) {
                hoist(l, i, p, Sim.negate(f.c), f.a, new ArrayList<>(y));
                return i;
            }
        }
        if (b.size() == 1 && b.get(0) instanceof If f && f.b != null) {
            if (exits(f.b) && noContinue(f.b, p) && canHoist(f.b, f.a, p)) {
                hoist(l, i, p, f.c, f.b, new ArrayList<>(f.a));
                return i;
            }
            if (exits(f.a) && noContinue(f.a, p) && canHoist(f.a, f.b, p)) {
                hoist(l, i, p, Sim.negate(f.c), f.a, new ArrayList<>(f.b));
                return i;
            }
        }
        // while (true) { B; if (c) break; }  =>  do { B } while (!c);
        Stmt last = b.get(b.size() - 1);
        if (last instanceof If f && f.b == null && exits(f.a) && noContinue(b.subList(0, b.size() - 1), p) && noContinue(f.a, p)
                && canHoist(f.a, b.subList(0, b.size() - 1), p)) {
            List<Stmt> x = f.a;
            p.kind = LoopKind.DO;
            p.cond = Sim.negate(f.c);
            b.remove(b.size() - 1);
            if (endsWith(x, p)) x.remove(x.size() - 1);
            l.addAll(i + 1, x);
            changed = true;
        }
        return i;
    }

    /** continue in the else arm of a final if falls through to the loop end. */
    private boolean stripContinueInElse(List<Stmt> b, Loop p) {
        return stripTail(b, p);
    }

    private static boolean noContinue(List<Stmt> l, Loop p) {
        boolean[] none = {true};
        Ast.visit(l, s -> { if (s instanceof Continue c && c.target == p) none[0] = false; });
        return none[0];
    }

    /** X can follow the loop if the loop's only way out is through it, or if it is a bare break. */
    private static boolean canHoist(List<Stmt> x, List<Stmt> rest, Loop p) {
        if (x.size() == 1 && x.get(0) instanceof Break b && b.target == p) return true;
        boolean[] none = {true};
        Ast.visit(rest, s -> { if (s instanceof Break k && k.target == p) none[0] = false; });
        Ast.visit(x.subList(0, x.size() - 1), s -> { if (s instanceof Break k && k.target == p) none[0] = false; });
        return none[0];
    }

    private void hoist(List<Stmt> l, int i, Loop p, Expr cond, List<Stmt> x, List<Stmt> body) {
        p.cond = cond;
        p.body = body;
        List<Stmt> after = new ArrayList<>(x);
        if (endsWith(after, p)) after.remove(after.size() - 1);
        l.addAll(i + 1, after);
        changed = true;
    }
}
