package regrind.decomp;

import regrind.core.Workspace;
import regrind.decomp.Ast.*;
import java.lang.classfile.*;
import java.lang.classfile.Attributes;
import java.lang.classfile.instruction.*;
import java.util.*;

/** Restores source constructs that javac compiles away. */
final class Sugar {
    private Sugar() {}

    // ---------------------------------------------------------------- before declarations

    static void preDeclare(List<Stmt> body, Workspace ws) {
        rewriteConcat(body);
        syncBlocks(body);
        mergeTries(body);
        Ast.visit(body, s -> s.lists(l -> { asserts(l); stringSwitch(l); enumSwitch(l, ws); }));
        asserts(body);
        stringSwitch(body);
        enumSwitch(body, ws);
    }

    /** try { try { A } catch (E e) { B } } finally { C }  =>  try { A } catch (E e) { B } finally { C } */
    private static void mergeTries(List<Stmt> l) {
        for (Stmt s : l) {
            s.lists(Sugar::mergeTries);
            if (s instanceof Try t && t.fin != null && t.catches.isEmpty() && t.body.size() == 1 && t.body.get(0) instanceof Try in && in.fin == null) {
                t.body = in.body;
                t.catches = in.catches;
            }
        }
    }

    /** new StringBuilder().append(a).append(b).toString()  =>  a + b */
    private static void rewriteConcat(List<Stmt> body) {
        Ast.rewriteAll(body, x -> {
            if (!(x instanceof Invoke m) || !m.is("java/lang/StringBuilder", "toString")) return x;
            List<Expr> parts = new ArrayList<>();
            Expr e = m.target;
            while (e instanceof Invoke a && a.is("java/lang/StringBuilder", "append") && a.args.length == 1) {
                String p = Types.params(a.desc).get(0);
                if (p.equals("[C") || p.equals("Ljava/lang/StringBuffer;")) return x;
                parts.add(0, p.equals("I") && a.args[0].type().equals("C") ? new Cast("I", a.args[0]) : a.args[0]);
                e = a.target;
            }
            if (!(e instanceof NewObj n) || !n.cls.equals("java/lang/StringBuilder") || parts.isEmpty()) return x;
            if (n.args.length == 1) {
                if (!n.desc.equals("(Ljava/lang/String;)V")) return x;
                parts.add(0, n.args[0]);
            } else if (n.args.length > 1) return x;
            return Sim.concatOf(parts);
        });
    }

    private static void syncBlocks(List<Stmt> l) {
        for (int i = 0; i + 1 < l.size(); i++) {
            if (!(l.get(i) instanceof ExprStmt es && es.e instanceof Monitor m && m.enter)) continue;
            // the try can sit inside labeled blocks opened for code after the synchronized statement
            List<Stmt> holder = l;
            int at = i + 1;
            while (holder.get(at) instanceof Labeled b && !b.body.isEmpty()) {
                holder = b.body;
                at = 0;
            }
            if (holder.get(at) instanceof Try t && t.catches.isEmpty() && t.fin != null
                    && t.fin.size() == 1 && t.fin.get(0) instanceof ExprStmt f && f.e instanceof Monitor x && !x.enter) {
                Expr lock = m.e instanceof Assign a ? a.rhs : m.e;
                holder.set(at, new Sync(lock, t.body));
                l.remove(i);
                i--;
            }
        }
        for (Stmt s : l) s.lists(Sugar::syncBlocks);
    }

    private static void asserts(List<Stmt> l) {
        for (int i = 0; i < l.size(); i++) {
            if (!(l.get(i) instanceof If f) || f.b != null || f.a.size() != 1 || !(f.a.get(0) instanceof Throw t)) continue;
            if (!(t.e instanceof NewObj n) || !n.cls.equals("java/lang/AssertionError") || n.args.length > 1) continue;
            Expr c = f.c, rest = null;
            if (c instanceof Binary b && b.op.equals("&&")) {
                // the flag is the leftmost operand of the && chain
                Expr first = b;
                Binary parent = null;
                while (first instanceof Binary q && q.op.equals("&&")) { parent = q; first = q.l; }
                if (!isDisabledFlag(first)) continue;
                rest = parent == b ? b.r : dropLeft(b);
            } else if (!isDisabledFlag(c)) continue;
            Expr cond = rest == null ? Const.bool(false) : Sim.negate(rest);
            l.set(i, new Assert(cond, n.args.length == 1 ? n.args[0] : null));
        }
    }

    private static Expr dropLeft(Binary b) {
        if (b.l instanceof Binary q && q.op.equals("&&")) {
            if (!(q.l instanceof Binary q2 && q2.op.equals("&&"))) return new Binary("&&", q.r, b.r, "Z");
            return new Binary("&&", dropLeft(q), b.r, "Z");
        }
        return b.r;
    }

    private static boolean isDisabledFlag(Expr e) {
        return e instanceof Unary u && u.op.equals("!") && u.e instanceof FieldRef f && f.name.equals("$assertionsDisabled");
    }

    /**
     * s$ = x; tmp = -1; switch (s$.hashCode()) { ... tmp = k ... } switch (tmp) { ... }
     *   =>  switch (x) { case "..": ... }
     */
    private static void stringSwitch(List<Stmt> l) {
        for (int i = 2; i + 1 < l.size(); i++) {
            if (!(l.get(i) instanceof Switch hs) || !(l.get(i + 1) instanceof Switch vs)) continue;
            if (!(hs.sel instanceof Invoke h) || !h.name.equals("hashCode") || !h.owner.equals("java/lang/String") || !(h.target instanceof Local sv)) continue;
            if (!(vs.sel instanceof Local tmp)) continue;
            if (!(l.get(i - 1) instanceof ExprStmt a && a.e instanceof Assign ta && ta.lhs instanceof Local tl && tl.v.find() == tmp.v.find()
                    && ta.rhs instanceof Const k && k.isInt(-1))) continue;
            if (!(l.get(i - 2) instanceof ExprStmt b && b.e instanceof Assign sa && sa.lhs instanceof Local sl && sl.v.find() == sv.v.find())) continue;
            Map<Integer, String> map = new HashMap<>();
            boolean ok = true;
            for (Case c : hs.cases) ok &= collect(c.body, sv.v.find(), tmp.v.find(), map);
            if (!ok) continue;
            for (Case c : vs.cases) {
                List<Expr> nk = new ArrayList<>();
                for (Expr e : c.keys) {
                    String lit = e instanceof Const q && q.v instanceof Integer n ? map.get(n) : null;
                    if (lit == null) { ok = false; break; }
                    nk.add(new Const(lit, Types.STR));
                }
                if (!ok) break;
                c.keys = nk;
            }
            if (!ok) continue;
            vs.sel = sa.rhs;
            l.remove(i);
            l.remove(i - 1);
            l.remove(i - 2);
            i -= 2;
        }
    }

    private static boolean collect(List<Stmt> body, Var s, Var tmp, Map<Integer, String> out) {
        for (Stmt st : body) {
            if (st instanceof Break) continue;
            if (!(st instanceof If f)) return false;
            if (!collectIf(f, s, tmp, out)) return false;
        }
        return true;
    }

    private static boolean collectIf(If f, Var s, Var tmp, Map<Integer, String> out) {
        if (!(f.c instanceof Invoke m) || !m.name.equals("equals") || !(m.target instanceof Local t) || t.v.find() != s
                || m.args.length != 1 || !(m.args[0] instanceof Const lit) || !(lit.v instanceof String str)) return false;
        for (Stmt x : f.a) {
            if (x instanceof Break) continue;
            if (x instanceof ExprStmt e && e.e instanceof Assign a && a.lhs instanceof Local l && l.v.find() == tmp && a.rhs instanceof Const k && k.v instanceof Integer n) out.put(n, str);
            else return false;
        }
        return f.b == null || collect(f.b, s, tmp, out);
    }

    /** switch ($SwitchMap$pkg$Color[e.ordinal()])  =>  switch (e) */
    private static void enumSwitch(List<Stmt> l, Workspace ws) {
        for (Stmt st : l) {
            if (st instanceof Switch z && z.sel instanceof Invoke o0 && o0.name.equals("ordinal") && o0.target != null && o0.args.length == 0) ordinalSwitch(z, o0, ws);
            if (!(st instanceof Switch w) || !(w.sel instanceof ArrayGet g) || !(g.arr instanceof FieldRef f) || !f.name.startsWith("$SwitchMap$")) continue;
            if (!(g.idx instanceof Invoke o) || !o.name.equals("ordinal") || o.target == null) continue;
            Map<Integer, String> names = switchMap(f, ws);
            if (names == null) continue;
            boolean ok = true;
            for (Case c : w.cases) for (Expr k : c.keys) ok &= k instanceof Const q && q.v instanceof Integer n && names.containsKey(n);
            if (!ok) continue;
            for (Case c : w.cases) c.keys.replaceAll(k -> new Raw(names.get((Integer) ((Const) k).v), Types.ofInternal(o.owner)));
            w.sel = o.target;
        }
    }

    /** Enums compiled together are switched on the ordinal directly. */
    private static void ordinalSwitch(Switch w, Invoke o, Workspace ws) {
        ClassModel m = ws.model(o.owner);
        if (m == null || !m.flags().has(java.lang.reflect.AccessFlag.ENUM)) return;
        List<String> names = new ArrayList<>();
        for (FieldModel f : m.fields()) if (f.flags().has(java.lang.reflect.AccessFlag.ENUM)) names.add(f.fieldName().stringValue());
        for (Case c : w.cases) for (Expr k : c.keys) if (!(k instanceof Const q && q.v instanceof Integer n && n >= 0 && n < names.size())) return;
        for (Case c : w.cases) c.keys.replaceAll(k -> new Raw(names.get((Integer) ((Const) k).v), Types.ofInternal(o.owner)));
        w.sel = o.target;
    }

    private static Map<Integer, String> switchMap(FieldRef f, Workspace ws) {
        ClassModel m = ws.model(f.owner);
        if (m == null) return null;
        for (MethodModel mm : m.methods()) {
            if (!mm.methodName().stringValue().equals("<clinit>") || mm.code().isEmpty()) continue;
            Map<Integer, String> out = new HashMap<>();
            String field = null, constant = null;
            boolean armed = false;
            for (CodeElement e : mm.code().get()) {
                if (e instanceof FieldInstruction fi && fi.opcode() == Opcode.GETSTATIC) {
                    if (fi.name().stringValue().startsWith("$SwitchMap$")) field = fi.name().stringValue();
                    else constant = fi.name().stringValue();
                } else if (e instanceof InvokeInstruction ii && ii.name().stringValue().equals("ordinal")) {
                    armed = true;
                } else if (armed && e instanceof ConstantInstruction c && c.constantValue() instanceof Integer n) {
                    if (f.name.equals(field)) out.put(n, constant);
                    armed = false;
                }
            }
            return out;
        }
        return null;
    }

    // ---------------------------------------------------------------- after declarations

    static void postDeclare(List<Stmt> body, Workspace ws) {
        loops(body, ws);
        returnTemps(body);
    }

    /** int tmp = E; return tmp;  =>  return E; */
    private static void returnTemps(List<Stmt> l) {
        for (Stmt s : l) s.lists(Sugar::returnTemps);
        for (int i = 0; i + 1 < l.size(); i++) {
            if (l.get(i) instanceof Decl d && d.init != null && !d.v.find().fixedName && l.get(i + 1) instanceof Return r
                    && r.e instanceof Local x && x.v.find() == d.v.find()) {
                r.e = d.init;
                l.remove(i);
            }
        }
    }

    private static boolean uses(List<Stmt> l, Var v) {
        boolean[] r = {false};
        Ast.visit(l, s -> s.exprs(e -> { Finish.varsOf(e, x -> { if (x == v) r[0] = true; }); return e; }));
        return r[0];
    }

    private static void loops(List<Stmt> l, Workspace ws) {
        for (Stmt s : l) s.lists(x -> loops(x, ws));
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i) instanceof Loop p && p.kind == LoopKind.WHILE) {
                if (iterLoop(l, i, p, ws)) { i--; continue; }
                forLoop(l, i, p);
                i = l.indexOf(p);
            }
            if (l.get(i) instanceof Loop p && p.kind == LoopKind.FOR) arrayLoop(l, i, p);
        }
    }

    private static boolean isUpdate(Stmt s, Var v) {
        if (!(s instanceof ExprStmt e)) return false;
        if (e.e instanceof IncDec n) return n.e instanceof Local l && l.v.find() == v;
        return e.e instanceof Assign a && a.lhs instanceof Local l && l.v.find() == v;
    }

    private static void forLoop(List<Stmt> l, int i, Loop p) {
        if (p.cond == null || p.body.isEmpty()) return;
        Stmt last = p.body.get(p.body.size() - 1);
        for (Var v : condVars(p.cond)) {
            if (!isUpdate(last, v)) continue;
            Labeled guard = p.body.size() == 2 && p.body.get(0) instanceof Labeled g ? g : null;
            boolean hasInit = i > 0 && l.get(i - 1) instanceof Decl d && d.v.find() == v && !uses(l.subList(i + 1, l.size()), v);
            if (!hasInit && guard == null) continue;
            p.kind = LoopKind.FOR;
            p.update.add(((ExprStmt) last).e);
            p.body.remove(p.body.size() - 1);
            if (guard != null) {
                Ast.visit(guard.body, s -> { });
                replaceBreaks(guard.body, guard, p);
                p.body.remove(0);
                p.body.addAll(0, guard.body);
            }
            if (hasInit) {
                p.init = l.remove(i - 1);
            }
            return;
        }
    }

    private static void replaceBreaks(List<Stmt> l, Labeled from, Loop to) {
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i) instanceof Break b && b.target == from) l.set(i, new Continue(to));
            l.get(i).lists(x -> replaceBreaks(x, from, to));
        }
    }

    private static List<Var> condVars(Expr c) {
        List<Var> out = new ArrayList<>();
        Finish.varsOf(c, v -> { if (!out.contains(v)) out.add(v); });
        return out;
    }

    /** arr$ = X; len$ = arr$.length; for (i$ = 0; i$ < len$; i$++) { T v = arr$[i$]; ... } */
    private static void arrayLoop(List<Stmt> l, int i, Loop p) {
        if (i < 2 || !(p.init instanceof Decl di) || !(di.init instanceof Const z) || !z.isInt(0)) return;
        if (!(l.get(i - 1) instanceof Decl dl) || !(dl.init instanceof ArrayLen al) || !(al.arr instanceof Local a1)) return;
        if (!(l.get(i - 2) instanceof Decl da) || da.v.find() != a1.v.find() || da.init == null) return;
        Var iv = di.v.find(), lv = dl.v.find(), av = da.v.find();
        if (!(p.cond instanceof Binary c) || !c.op.equals("<") || !(c.l instanceof Local x) || x.v.find() != iv || !(c.r instanceof Local y) || y.v.find() != lv) return;
        if (p.update.size() != 1 || !(p.update.get(0) instanceof IncDec u) || !u.inc || !(u.e instanceof Local ul) || ul.v.find() != iv) return;
        if (p.body.isEmpty() || !(p.body.get(0) instanceof Decl dv) || !(dv.init instanceof ArrayGet g)) return;
        if (!(g.arr instanceof Local g1) || g1.v.find() != av || !(g.idx instanceof Local g2) || g2.v.find() != iv) return;
        List<Stmt> rest = p.body.subList(1, p.body.size());
        List<Stmt> after = l.subList(i + 1, l.size());
        if (uses(rest, iv) || uses(rest, av) || uses(rest, lv) || uses(after, iv) || uses(after, av) || uses(after, lv)) return;
        Loop e = new Loop(new ArrayList<>(rest));
        e.kind = LoopKind.FOREACH;
        e.each = dv.v;
        e.iter = da.init;
        e.label = p.label;
        relink(e.body, p, e);
        l.set(i, e);
        l.remove(i - 1);
        l.remove(i - 2);
    }

    private static void relink(List<Stmt> l, Loop from, Loop to) {
        Ast.visit(l, s -> {
            if (s instanceof Break b && b.target == from) b.target = to;
            if (s instanceof Continue c && c.target == from) c.target = to;
        });
    }

    /** Iterator it = X.iterator(); while (it.hasNext()) { T v = (T) it.next(); ... } */
    private static boolean genericRoot(Expr e, Workspace ws) {
        return switch (e) {
            case Local v -> v.v.find().generic != null && v.v.find().generic.contains("<");
            case FieldRef f -> hasSignature(f.owner, f.name, f.desc, ws);
            case Invoke m -> hasSignature(m.owner, m.name, m.desc, ws) && (m.target == null || genericRoot(m.target, ws));
            case Cast c -> false;
            default -> false;
        };
    }

    private static boolean hasSignature(String owner, String name, String desc, Workspace ws) {
        Deque<String> q = new ArrayDeque<>(List.of(owner));
        Set<String> seen = new HashSet<>();
        while (!q.isEmpty()) {
            String c = q.poll();
            if (!seen.add(c)) continue;
            ClassModel m = ws.model(c);
            if (m == null) continue;
            for (FieldModel f : m.fields()) {
                if (f.fieldName().stringValue().equals(name) && f.fieldType().stringValue().equals(desc)) return f.findAttribute(Attributes.signature()).isPresent();
            }
            for (MethodModel f : m.methods()) {
                if (f.methodName().stringValue().equals(name) && f.methodType().stringValue().equals(desc)) return f.findAttribute(Attributes.signature()).isPresent();
            }
            m.superclass().ifPresent(s -> q.add(s.asInternalName()));
            for (var i : m.interfaces()) q.add(i.asInternalName());
        }
        return false;
    }

    private static boolean iterLoop(List<Stmt> l, int i, Loop p, Workspace ws) {
        if (i < 1 || !(l.get(i - 1) instanceof Decl di) || !(di.init instanceof Invoke m) || !m.name.equals("iterator") || m.target == null || m.args.length != 0) return false;
        if (!m.desc.equals("()Ljava/util/Iterator;")) return false;
        if (!(p.cond instanceof Invoke h) || !h.name.equals("hasNext") || !(h.target instanceof Local t) || t.v.find() != di.v.find()) return false;
        if (p.body.isEmpty() || !(p.body.get(0) instanceof Decl dv) || dv.init == null) return false;
        Expr n = dv.init instanceof Cast c ? c.e : dv.init;
        Cast unboxed = null;
        if (n instanceof Invoke u && u.name.endsWith("Value") && u.args.length == 0 && u.target instanceof Cast bc) {
            unboxed = bc;
            n = bc.e;
        }
        if (!(n instanceof Invoke nx) || !nx.name.equals("next") || !(nx.target instanceof Local t2) || t2.v.find() != di.v.find()) return false;
        Var it = di.v.find();
        List<Stmt> rest = p.body.subList(1, p.body.size());
        if (uses(rest, it) || uses(l.subList(i + 1, l.size()), it)) return false;
        Loop e = new Loop(new ArrayList<>(rest));
        e.kind = LoopKind.FOREACH;
        e.each = dv.v;
        Var ev = dv.v.find();
        boolean typedIterable = genericRoot(m.target, ws);
        Cast elemCast = unboxed != null ? unboxed : dv.init instanceof Cast c ? c : null;
        if (elemCast != null && !typedIterable && ev.generic == null) {
            // the element type of the iterable is unknown here; an unchecked cast keeps the loop compilable
            e.iter = new IterCast(m.target, elemCast.desc);
        } else e.iter = m.target;
        e.label = p.label;
        relink(e.body, p, e);
        l.set(i, e);
        l.remove(i - 1);
        return true;
    }

    /** (Iterable<T>) x, needed when x has no generic type information. */
    static final class IterCast extends Expr {
        Expr e;
        final String elem;
        IterCast(Expr e, String elem) { this.e = e; this.elem = elem; }
        @Override public String type() { return "Ljava/lang/Iterable;"; }
        @Override public int prec() { return 13; }
        @Override public void mapKids(java.util.function.UnaryOperator<Expr> f) { e = f.apply(e); }
        @Override public void print(Out o) {
            o.add("(Iterable<").add(o.ty(elem)).add(">)");
            e.printAt(o, 13);
        }
    }
}
