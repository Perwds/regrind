package regrind.decomp;

import regrind.decomp.Ast.*;
import regrind.decomp.Cfg.*;
import java.util.*;
import java.util.function.Supplier;

/**
 * Turns a reducible CFG into nested statements. The core is the dominator-tree translation from
 * Ramsey's "Beyond Relooper": every merge node becomes the code following a labeled block.
 * Try regions get a virtual head node with virtual edges to their exits, which forces code outside
 * the protected range to be placed after the try statement.
 */
final class Structurer {
    final Cfg cfg;
    final List<Block> order = new ArrayList<>();
    final Map<Block, List<Block>> domKids = new HashMap<>();
    final Map<Block, List<Block>> allPreds = new HashMap<>();
    final Set<Block> headers = new HashSet<>();
    final Map<Block, Set<Block>> loopBody = new HashMap<>();
    final Map<Block, Labeled> labels = new HashMap<>();
    final Map<Block, Loop> loops = new HashMap<>();
    int nextId;

    Structurer(Cfg c) { cfg = c; }

    List<Stmt> run() {
        nextId = 1 + cfg.blocks.stream().mapToInt(b -> b.id).max().orElse(0);
        setupRegions();
        orderBlocks();
        dominators();
        findLoops();
        return tree(cfg.entry);
    }

    // ---------------------------------------------------------------- regions

    private void setupRegions() {
        // same protected blocks => same try statement with several catches
        Map<Set<Block>, Region> uniq = new LinkedHashMap<>();
        for (Region r : cfg.regions) {
            if (r.blocks.isEmpty()) continue;
            Region o = uniq.get(r.blocks);
            if (o == null) uniq.put(r.blocks, r);
            else o.handlers.addAll(r.handlers);
        }
        List<Region> rs = new ArrayList<>(uniq.values());
        rs.sort(Comparator.comparingInt(r -> r.blocks.size()));
        for (Region r : rs) open(r, rs);
    }

    private void open(Region r, List<Region> all) {
        computePreds();
        Set<Block> out = new LinkedHashSet<>();
        for (int guard = 0; ; guard++) {
            out.clear();
            for (Block b : r.blocks) {
                if (b == cfg.entry) out.add(b);
                for (Block p : b.preds) if (!r.blocks.contains(p)) out.add(b);
            }
            if (out.size() <= 1) break;
            if (guard > 64) throw new IllegalStateException("multi-entry try region");
            // widen across code that leaves the range and comes back (gaps in the exception table)
            Block first = out.stream().min(Comparator.comparingInt(b -> b.start)).get();
            boolean grew = false;
            for (Block b : out) {
                if (b == first) continue;
                for (Block p : b.preds) if (!r.blocks.contains(p) && r.blocks.add(p)) grew = true;
            }
            if (!grew) throw new IllegalStateException("multi-entry try region");
        }
        Block head = out.isEmpty() ? r.blocks.iterator().next() : out.iterator().next();
        Block v = new Block();
        v.id = nextId++;
        v.term = Term.TRY;
        v.tryOf = r;
        v.start = head.start;
        v.succ.add(head);
        for (Handler h : r.handlers) {
            if (h.block != null && !v.succ.contains(h.block)) v.succ.add(h.block);
        }
        for (Block p : new ArrayList<>(head.preds)) if (!r.blocks.contains(p)) retarget(p, head, v);
        if (cfg.entry == head) cfg.entry = v;
        r.head = head;
        r.entry = v;
        cfg.blocks.add(v);
        Set<Block> exits = new LinkedHashSet<>();
        for (Block b : r.blocks) {
            for (Block s : b.succ) if (!r.blocks.contains(s) && !v.succ.contains(s)) exits.add(s);
            for (Block s : b.virtualSucc) if (!r.blocks.contains(s) && !v.succ.contains(s)) exits.add(s);
        }
        v.virtualSucc.addAll(exits);
        for (Region o : all) if (o != r && o.blocks.containsAll(r.blocks)) o.blocks.add(v);
        for (Region o : all) if (o != r && o.blocks.contains(head)) o.blocks.add(v);
    }

    private static void retarget(Block p, Block from, Block to) {
        p.succ.replaceAll(x -> x == from ? to : x);
        p.virtualSucc.replaceAll(x -> x == from ? to : x);
        if (p.term == Term.SWITCH) {
            if (p.dflt == from) p.dflt = to;
            if (p.keys.containsKey(from)) p.keys.put(to, p.keys.remove(from));
        }
    }

    private void computePreds() {
        for (Block b : cfg.blocks) b.preds.clear();
        for (Block b : cfg.blocks) for (Block s : b.succ) if (!s.preds.contains(b)) s.preds.add(b);
    }

    // ---------------------------------------------------------------- order and dominators

    private List<Block> edges(Block b) {
        List<Block> e = new ArrayList<>(b.succ);
        e.addAll(b.virtualSucc);
        return e;
    }

    private void orderBlocks() {
        // iterative DFS; reversed postorder, with earlier successors first
        Set<Block> seen = new HashSet<>();
        Deque<Object[]> st = new ArrayDeque<>();
        List<Block> post = new ArrayList<>();
        seen.add(cfg.entry);
        st.push(new Object[]{cfg.entry, 0});
        while (!st.isEmpty()) {
            Object[] top = st.peek();
            Block b = (Block) top[0];
            List<Block> es = edges(b);
            int i = (Integer) top[1];
            // visit last edges first so that the first successor ends up earliest in reverse postorder
            if (i < es.size()) {
                top[1] = i + 1;
                Block s = es.get(es.size() - 1 - i);
                if (seen.add(s)) st.push(new Object[]{s, 0});
            } else {
                post.add(b);
                st.pop();
            }
        }
        for (int i = post.size() - 1; i >= 0; i--) {
            Block b = post.get(i);
            b.rpo = order.size();
            order.add(b);
        }
        for (Block b : order) allPreds.put(b, new ArrayList<>());
        for (Block b : order) for (Block s : edges(b)) if (!allPreds.get(s).contains(b)) allPreds.get(s).add(b);
    }

    private void dominators() {
        Block e = cfg.entry;
        e.idom = e;
        boolean ch = true;
        while (ch) {
            ch = false;
            for (Block b : order) {
                if (b == e) continue;
                Block nw = null;
                for (Block p : allPreds.get(b)) {
                    if (p.idom == null) continue;
                    nw = nw == null ? p : meet(p, nw);
                }
                if (nw != null && b.idom != nw) { b.idom = nw; ch = true; }
            }
        }
        for (Block b : order) domKids.put(b, new ArrayList<>());
        for (Block b : order) if (b != e && b.idom != null) domKids.get(b.idom).add(b);
    }

    private static Block meet(Block a, Block b) {
        while (a != b) {
            while (a.rpo > b.rpo) a = a.idom;
            while (b.rpo > a.rpo) b = b.idom;
        }
        return a;
    }

    private boolean dominates(Block a, Block b) {
        while (true) {
            if (a == b) return true;
            if (b.idom == b || b.idom == null) return false;
            b = b.idom;
        }
    }

    private void findLoops() {
        for (Block u : order) {
            for (Block v : edges(u)) {
                if (v.rpo > u.rpo) continue;
                if (!dominates(v, u)) throw new IllegalStateException("irreducible control flow");
                headers.add(v);
                Set<Block> body = loopBody.computeIfAbsent(v, k -> new HashSet<>(List.of(k)));
                Deque<Block> q = new ArrayDeque<>();
                if (body.add(u)) q.add(u);
                while (!q.isEmpty()) {
                    Block x = q.poll();
                    for (Block p : allPreds.get(x)) if (body.add(p)) q.add(p);
                }
            }
        }
    }

    private boolean isMerge(Block b) {
        int n = 0;
        for (Block p : allPreds.get(b)) if (p.rpo < b.rpo) n++;
        return n >= 2;
    }

    // ---------------------------------------------------------------- translation

    private List<Stmt> tree(Block x) {
        List<Block> ys = new ArrayList<>();
        for (Block k : domKids.get(x)) if (isMerge(k)) ys.add(k);
        ys.sort(Comparator.comparingInt(b -> b.rpo));
        if (!headers.contains(x)) return nest(ys, ys.size() - 1, () -> node(x));
        Set<Block> body = loopBody.get(x);
        List<Block> inner = new ArrayList<>(), outer = new ArrayList<>();
        for (Block y : ys) (body.contains(y) ? inner : outer).add(y);
        return nest(outer, outer.size() - 1, () -> {
            Loop l = new Loop(null);
            loops.put(x, l);
            l.body = nest(inner, inner.size() - 1, () -> node(x));
            loops.remove(x);
            return new ArrayList<>(List.of(l));
        });
    }

    private List<Stmt> nest(List<Block> ys, int i, Supplier<List<Stmt>> core) {
        if (i < 0) return core.get();
        Block y = ys.get(i);
        Labeled l = new Labeled(null);
        labels.put(y, l);
        l.body = nest(ys, i - 1, core);
        labels.remove(y);
        List<Stmt> out = new ArrayList<>();
        out.add(l);
        out.addAll(tree(y));
        return out;
    }

    private List<Stmt> branch(Block from, Block to) {
        if (to.rpo <= from.rpo) {
            Loop l = loops.get(to);
            if (l == null) throw new IllegalStateException("back edge out of scope");
            return new ArrayList<>(List.of(new Continue(l)));
        }
        if (isMerge(to)) {
            Labeled l = labels.get(to);
            if (l == null) throw new IllegalStateException("jump out of scope");
            return new ArrayList<>(List.of(new Break(l)));
        }
        return tree(to);
    }

    private List<Stmt> node(Block x) {
        List<Stmt> out = new ArrayList<>(x.stmts);
        switch (x.term) {
            case GOTO -> out.addAll(branch(x, x.succ.get(0)));
            case COND -> out.add(new If(x.cond, branch(x, x.succ.get(0)), branch(x, x.succ.get(1))));
            case RETURN -> out.add(new Return(x.cond));
            case THROW -> out.add(new Throw(x.cond));
            case END_FINALLY -> {}
            case SWITCH -> {
                Switch sw = new Switch(x.cond);
                List<Block> ts = new ArrayList<>(x.keys.keySet());
                if (!ts.contains(x.dflt)) ts.add(x.dflt);
                ts.sort(Comparator.comparingInt(b -> b.start));
                for (Block t : ts) {
                    Case c = new Case();
                    List<Integer> ks = x.keys.get(t);
                    if (ks != null) {
                        Collections.sort(ks);
                        for (int k : ks) c.keys.add(Const.i(k));
                    }
                    c.isDefault = t == x.dflt;
                    c.body = branch(x, t);
                    sw.cases.add(c);
                }
                out.add(sw);
            }
            case TRY -> {
                Region r = x.tryOf;
                Try t = new Try(branch(x, r.head));
                for (Handler h : r.handlers) {
                    if (h.block == null) continue;
                    if (h.fin) {
                        t.fin = branch(x, h.block);
                        continue;
                    }
                    CatchClause c = new CatchClause();
                    for (String ty : h.types) {
                        String tt = ty == null ? "Ljava/lang/Throwable;" : ty;
                        if (!c.types.contains(tt)) c.types.add(tt);
                    }
                    if (c.types.contains("Ljava/lang/Throwable;")) c.types = new ArrayList<>(List.of("Ljava/lang/Throwable;"));
                    c.v = catchVar(h.block, c);
                    c.body = branch(x, h.block);
                    t.catches.add(c);
                }
                out.add(t);
            }
        }
        return out;
    }

    private static Var catchVar(Block h, CatchClause c) {
        Var v = new Var();
        v.desc = c.types.size() == 1 ? c.types.get(0) : "Ljava/lang/Throwable;";
        java.util.function.UnaryOperator<Expr> sub = e -> e.rewrite(x -> x instanceof Caught ? new Local(v) : x);
        if (!h.stmts.isEmpty() && h.stmts.get(0) instanceof ExprStmt es && es.e instanceof Assign a && a.lhs instanceof Local l
                && a.rhs instanceof Caught) {
            h.stmts.remove(0);
            Var u = l.v.find();
            if (!u.fixedName || u.desc.equals(Types.UNK)) u.desc = v.desc;
            return u;
        }
        for (Stmt s : h.stmts) s.exprs(sub);
        if (h.cond != null) h.cond = sub.apply(h.cond);
        return v;
    }
}
