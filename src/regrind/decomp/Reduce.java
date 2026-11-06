package regrind.decomp;

import regrind.decomp.Ast.*;
import regrind.decomp.Cfg.*;
import java.util.*;

/**
 * Graph rewrites that recover expression-level control flow: inlining of stack temporaries,
 * block merging, short-circuit conditions and ternaries.
 */
final class Reduce {
    final Cfg cfg;
    Map<Var, int[]> counts; // {defs, uses}

    Reduce(Cfg c) { cfg = c; }

    void run() {
        computePreds();
        for (Block b : cfg.blocks) b.regions.clear();
        for (Region r : cfg.regions) for (Block b : r.blocks) b.regions.add(r);
        boolean ch = true;
        while (ch) {
            ch = false;
            count();
            for (Block b : cfg.blocks) ch |= inline(b);
            ch |= forwardEmpty();
            ch |= mergeChains();
            ch |= shortCircuit();
            ch |= diamonds();
        }
    }

    void computePreds() {
        for (Block b : cfg.blocks) b.preds.clear();
        for (Block b : cfg.blocks) for (Block s : b.succ) if (!s.preds.contains(b)) s.preds.add(b);
    }

    private static boolean isHandler(Block b) { return b.catchType != null || b.finallyEntry; }

    private int[] c(Var v) { return counts.computeIfAbsent(v.find(), k -> new int[2]); }

    private void count() {
        counts = new HashMap<>();
        for (Block b : cfg.blocks) {
            for (Stmt s : b.stmts) {
                if (s instanceof ExprStmt es && es.e instanceof Assign a && a.lhs instanceof Local l) c(l.v)[0]++;
                s.exprs(e -> { countExpr(e); return e; });
            }
            if (b.cond != null) countExpr(b.cond);
            // a stack variable passed through to a successor with no code touching it is still a use
            if (b.entry != null) for (Expr e : b.entry) if (e instanceof Local l) c(l.v)[0] += 0;
        }
    }

    private void countExpr(Expr e) {
        e.walk(x -> {
            if (x instanceof Local l) c(l.v)[1]++;
            if (x instanceof Assign a && a.lhs instanceof Local l && !(a.lhs == null)) {
                // nested assignment: counts as a definition (the statement-level one is counted by count())
            }
        });
    }

    private boolean inline(Block b) {
        boolean ch = false;
        List<Stmt> st = b.stmts;
        for (int i = st.size() - 1; i >= 0; i--) {
            if (!(st.get(i) instanceof ExprStmt es && es.e instanceof Assign a && a.op == null && a.lhs instanceof Local l)) continue;
            Var t = l.v.find();
            if (!t.stack || t.noInline) continue;
            int[] cn = c(t);
            if (cn[0] != 1) continue;
            if (cn[1] == 2 && i + 1 < st.size() && st.get(i + 1) instanceof ExprStmt nx && nx.e instanceof Assign b2 && b2.op == null) {
                // T = E; lhs = T; ...T...  =>  ...(lhs = E)...
                if (b2.rhs instanceof Local r && r.v.find() == t && !b2.lhs.mentions(t)) {
                    a.rhs = new Assign(b2.lhs, a.rhs);
                    st.remove(i + 1);
                    cn[1] = 1;
                    ch = true;
                } else if (postInc(t, a, b2)) {
                    st.remove(i + 1);
                    cn[1] = 1;
                    ch = true;
                }
            }
            if (cn[1] != 1) continue;
            Expr val = a.rhs;
            boolean done = false;
            int j = i + 1;
            for (; j < st.size() && !done; j++) {
                Stmt s = st.get(j);
                boolean[] has = {false};
                s.exprs(e -> { if (e.mentions(t)) has[0] = true; return e; });
                if (has[0]) {
                    s.exprs(e -> subst(e, t, val));
                    done = true;
                } else if (!(s instanceof ExprStmt x && x.e instanceof Assign y && y.lhs instanceof Local q && q.v.find().stack)) break;
            }
            if (!done && j == st.size() && b.cond != null && b.cond.mentions(t)) {
                b.cond = subst(b.cond, t, val);
                done = true;
            }
            if (done) {
                st.remove(i);
                ch = true;
            }
        }
        return ch;
    }

    private static Expr subst(Expr e, Var t, Expr val) {
        return e.rewrite(x -> x instanceof Local l && l.v.find() == t ? val : x);
    }

    /** T = L; L = T + 1  =>  T = L++ */
    private boolean postInc(Var t, Assign a, Assign nx) {
        if (!(a.rhs instanceof FieldRef || a.rhs instanceof ArrayGet)) return false;
        if (!a.rhs.same(nx.lhs)) return false;
        Expr r = nx.rhs;
        if (r instanceof Cast c) r = c.e;
        if (r instanceof Binary b && (b.op.equals("+") || b.op.equals("-")) && b.l instanceof Local x && x.v.find() == t
                && b.r instanceof Const k && ((Number) k.v).doubleValue() == 1) {
            a.rhs = new IncDec(a.rhs, b.op.equals("+"), true);
            return true;
        }
        return false;
    }

    private void retarget(Block p, Block from, Block to) {
        p.succ.replaceAll(x -> x == from ? to : x);
        if (p.term == Term.SWITCH) {
            if (p.dflt == from) p.dflt = to;
            if (p.keys.containsKey(from)) {
                List<Integer> k = p.keys.remove(from);
                p.keys.computeIfAbsent(to, x -> new ArrayList<>()).addAll(k);
            }
        }
        // two equal successors collapse to a plain jump
        if (p.term == Term.COND && p.succ.get(0) == p.succ.get(1)) {
            Expr c = p.cond;
            if (c.any(x -> x instanceof Invoke || x instanceof Assign || x instanceof IncDec)) p.stmts.add(Ast.effect(c));
            p.term = Term.GOTO;
            p.cond = null;
            p.succ.remove(1);
        }
    }

    private void removeBlock(Block b) {
        cfg.blocks.remove(b);
        for (Region r : cfg.regions) r.blocks.remove(b);
    }

    private boolean forwardEmpty() {
        boolean ch = false;
        for (Block b : new ArrayList<>(cfg.blocks)) {
            if (b == cfg.entry || isHandler(b) || !b.stmts.isEmpty() || b.term != Term.GOTO || b.succ.size() != 1) continue;
            Block t = b.succ.get(0);
            if (t == b) continue;
            // an empty block heading a protected region or sitting in a different region still has no effect,
            // but keep any block a handler-range entry could depend on simple: only forward within regions
            if (!b.regions.equals(t.regions)) continue;
            for (Block p : new ArrayList<>(b.preds)) retarget(p, b, t);
            removeBlock(b);
            computePreds();
            ch = true;
        }
        return ch;
    }

    private boolean mergeChains() {
        boolean ch = false;
        for (Block b : new ArrayList<>(cfg.blocks)) {
            if (!cfg.blocks.contains(b)) continue;
            while (b.term == Term.GOTO && b.succ.size() == 1) {
                Block s = b.succ.get(0);
                if (s == b || s == cfg.entry || isHandler(s) || s.preds.size() != 1 || !b.regions.equals(s.regions)) break;
                b.stmts.addAll(s.stmts);
                b.term = s.term;
                b.cond = s.cond;
                b.keys = s.keys;
                b.dflt = s.dflt;
                b.succ = s.succ;
                removeBlock(s);
                computePreds();
                ch = true;
            }
        }
        return ch;
    }

    private boolean shortCircuit() {
        boolean ch = false;
        for (Block b : new ArrayList<>(cfg.blocks)) {
            if (!cfg.blocks.contains(b)) continue;
            boolean again = true;
            while (again && b.term == Term.COND) {
                again = false;
                Block t = b.succ.get(0), f = b.succ.get(1);
                for (int side = 0; side < 2 && !again; side++) {
                    Block s = side == 0 ? t : f, other = side == 0 ? f : t;
                    if (s == b || s.term != Term.COND || !s.stmts.isEmpty() || s.preds.size() != 1 || isHandler(s)
                            || !s.regions.equals(b.regions)) continue;
                    Block s0 = s.succ.get(0), s1 = s.succ.get(1);
                    if (s0 != other && s1 != other) continue;
                    Expr c1 = b.cond, c2 = s.cond;
                    Expr nc;
                    Block nt, nf;
                    if (side == 0) {
                        // b true -> s; b false -> other.  (c1 && c2') : next, other
                        if (s1 == other) { nc = new Binary("&&", c1, c2, "Z"); nt = s0; nf = other; }
                        else { nc = new Binary("&&", c1, Sim.negate(c2), "Z"); nt = s1; nf = other; }
                    } else {
                        // b true -> other; b false -> s.  (c1 || c2') : other, next
                        if (s0 == other) { nc = new Binary("||", c1, c2, "Z"); nt = other; nf = s1; }
                        else { nc = new Binary("||", c1, Sim.negate(c2), "Z"); nt = other; nf = s0; }
                    }
                    b.cond = nc;
                    b.succ = new ArrayList<>(List.of(nt, nf));
                    removeBlock(s);
                    computePreds();
                    ch = again = true;
                }
            }
        }
        return ch;
    }

    private boolean diamonds() {
        boolean ch = false;
        for (Block b : new ArrayList<>(cfg.blocks)) {
            if (!cfg.blocks.contains(b) || b.term != Term.COND) continue;
            Block t = b.succ.get(0), f = b.succ.get(1);
            if (!arm(t, b) || !arm(f, b) || t.succ.get(0) != f.succ.get(0)) continue;
            Assign x = (Assign) ((ExprStmt) t.stmts.get(0)).e, y = (Assign) ((ExprStmt) f.stmts.get(0)).e;
            Var a = ((Local) x.lhs).v.find(), c = ((Local) y.lhs).v.find();
            if (a != c || !a.stack) continue;
            Block j = t.succ.get(0);
            Ternary ter = new Ternary(b.cond, x.rhs, y.rhs);
            b.stmts.add(new ExprStmt(new Assign(new Local(a), ter)));
            a.defs.add(ter);
            b.term = Term.GOTO;
            b.cond = null;
            b.succ = new ArrayList<>(List.of(j));
            removeBlock(t);
            removeBlock(f);
            computePreds();
            ch = true;
        }
        return ch;
    }

    private static boolean arm(Block s, Block b) {
        return s != b && s.term == Term.GOTO && s.succ.size() == 1 && s.preds.size() == 1 && !isHandler(s) && s.regions.equals(b.regions)
                && s.stmts.size() == 1 && s.stmts.get(0) instanceof ExprStmt e && e.e instanceof Assign a && a.op == null && a.lhs instanceof Local;
    }
}
