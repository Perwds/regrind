package regrind.decomp;

import regrind.core.Workspace;
import regrind.decomp.Ast.*;
import java.util.*;

/**
 * Final method-level passes: infer local types, insert the literals and casts the Java type system
 * needs, restore compound operators, declare variables, pick names and assign labels.
 */
final class Finish {
    final Workspace ws;
    final String retDesc;
    final String retGeneric;
    final List<Var> params;
    final Var thisVar;
    final Set<String> reserved;
    final Set<Var> allVars = new LinkedHashSet<>();
    final Set<Var> nonBool = new HashSet<>();
    final Map<Var, Integer> uses = new HashMap<>();
    final Map<Var, List<String>> hints = new HashMap<>();
    final Set<Var> resolving = new HashSet<>();

    Finish(Workspace ws, String retDesc, String retGeneric, List<Var> params, Var thisVar, Set<String> reserved) {
        this.ws = ws;
        this.retDesc = retDesc;
        this.retGeneric = retGeneric;
        this.params = params;
        this.thisVar = thisVar;
        this.reserved = reserved;
    }

    // ---------------------------------------------------------------- variables in use

    static void varsOf(Expr e, java.util.function.Consumer<Var> c) {
        e.walk(x -> {
            if (x instanceof Local l) c.accept(l.v.find());
            else if (x instanceof Assign a && a.lhs instanceof Local l) c.accept(l.v.find());
            else if (x instanceof IncDec n && n.e instanceof Local l) c.accept(l.v.find());
        });
    }

    private void scanVars(List<Stmt> body) {
        Ast.visit(body, s -> {
            s.exprs(e -> { varsOf(e, allVars::add); return e; });
            if (s instanceof Try t) for (CatchClause c : t.catches) allVars.add(c.v.find());
        });
    }

    // ---------------------------------------------------------------- type inference

    private void hint(Expr e, String desc) {
        if (e instanceof Local l) hints.computeIfAbsent(l.v.find(), k -> new ArrayList<>()).add(desc);
    }

    /** Walk uses of int-category locals to learn whether they behave as booleans. */
    private void scanUses(List<Stmt> body) {
        Set<Expr> neutral = Collections.newSetFromMap(new IdentityHashMap<>());
        Ast.visit(body, s -> {
            if (s instanceof Return r && r.e != null) markUse(r.e, retDesc, neutral);
            s.exprs(top -> {
                top.walk(x -> {
                    switch (x) {
                        case Invoke m -> {
                            List<String> ps = Types.params(m.desc);
                            for (int i = 0; i < m.args.length && i < ps.size(); i++) markUse(m.args[i], ps.get(i), neutral);
                            if (m.target != null) hint(m.target, Types.ofInternal(m.owner));
                        }
                        case NewObj n -> {
                            List<String> ps = Types.params(n.desc);
                            for (int i = 0; i < n.args.length && i < ps.size(); i++) markUse(n.args[i], ps.get(i), neutral);
                        }
                        case FieldRef f -> { if (f.target != null) hint(f.target, Types.ofInternal(f.owner)); }
                        case Assign a -> {
                            if (a.lhs instanceof Local) neutral.add(a.rhs);
                            else markUse(a.rhs, a.lhs.type(), neutral);
                        }
                        case Binary b when b.op.equals("==") || b.op.equals("!=") -> {
                            if (zeroOne(b.r)) neutral.add(b.l);
                            if (zeroOne(b.l)) neutral.add(b.r);
                            if (b.l instanceof Local && b.r.type().equals("Z")) neutral.add(b.l);
                            if (b.r instanceof Local && b.l.type().equals("Z")) neutral.add(b.r);
                        }
                        case Binary b when b.ty.equals("Z") || b.op.equals("&&") || b.op.equals("||") -> { neutral.add(b.l); neutral.add(b.r); }
                        case Unary u when u.op.equals("!") -> neutral.add(u.e);
                        case Cast c when c.desc.equals("Z") -> neutral.add(c.e);
                        case Ternary t -> {
                            if (neutral.contains(t)) { neutral.add(t.a); neutral.add(t.b); }
                            neutral.add(t.c);
                        }
                        default -> {}
                    }
                });
                top.walk(x -> {
                    if (x instanceof Local l) {
                        Var v = l.v.find();
                        uses.merge(v, 1, Integer::sum);
                        if (!neutral.contains(l) && (v.kind == 'I')) nonBool.add(v);
                    }
                });
                return top;
            });
        });
    }

    private static boolean zeroOne(Expr e) { return e instanceof Const c && (c.isInt(0) || c.isInt(1)); }

    private void markUse(Expr e, String want, Set<Expr> neutral) {
        hint(e, want);
        if (want.equals("Z")) neutral.add(e);
    }

    private void resolve(Var raw) {
        Var v = raw.find();
        if (!v.desc.equals(Types.UNK) || !resolving.add(v)) return;
        for (Expr d : v.defs) varsOf(d, this::resolve);
        v.desc = plainType(infer(v));
        resolving.remove(v);
        if (v.kind == 'A' && v.generic == null) {
            Lambda l = lambdaDef(v, 0);
            if (l != null) v.generic = lambdaSig(l);
        }
    }

    /** Anonymous classes cannot be named; declare their supertype instead. */
    private String plainType(String d) {
        if (!d.startsWith("L")) return d;
        String n = Types.internal(d);
        int i = n.lastIndexOf('$');
        if (i < 0 || i == n.length() - 1) return d;
        for (int k = i + 1; k < n.length(); k++) if (!Character.isDigit(n.charAt(k))) return d;
        Workspace.Info c = ws.info(n);
        if (c == null) return d;
        if (!c.interfaces().isEmpty()) return Types.ofInternal(c.interfaces().get(0));
        return Types.ofInternal(c.superName());
    }

    private static Lambda lambdaDef(Var v, int depth) {
        if (depth > 4 || v.defs.size() != 1) return null;
        Expr d = v.defs.get(0);
        if (d instanceof Lambda l) return l;
        return d instanceof Local x ? lambdaDef(x.v.find(), depth + 1) : null;
    }

    /** Parameterises a functional interface from the lambda's instantiated method type. */
    private String lambdaSig(Lambda l) {
        if (l.instDesc == null || l.sam == null || !l.ifaceDesc.startsWith("L")) return null;
        String iface = Types.internal(l.ifaceDesc);
        var cm = ws.model(iface);
        if (cm == null) return null;
        var cs = cm.findAttribute(java.lang.classfile.Attributes.signature());
        if (cs.isEmpty()) return null;
        List<String> formals = Sig.formalNames(cs.get().signature().stringValue());
        for (var m : cm.methods()) {
            if (!m.methodName().stringValue().equals(l.sam) || !m.flags().has(java.lang.reflect.AccessFlag.ABSTRACT)) continue;
            var ms = m.findAttribute(java.lang.classfile.Attributes.signature());
            if (ms.isEmpty()) return null;
            String sig = ms.get().signature().stringValue();
            List<String> raw = new ArrayList<>(Sig.rawParams(sig));
            List<String> inst = new ArrayList<>(Types.params(l.instDesc));
            raw.add(sig.substring(sig.indexOf(')') + 1));
            inst.add(Types.ret(l.instDesc));
            Map<String, String> map = new HashMap<>();
            for (int i = 0; i < raw.size() && i < inst.size(); i++) {
                String r = raw.get(i);
                if (r.startsWith("T") && r.endsWith(";") && Types.isRef(inst.get(i))) map.put(r.substring(1, r.length() - 1), inst.get(i));
            }
            StringBuilder b = new StringBuilder("L").append(iface).append("<");
            for (String f : formals) {
                String t = map.get(f);
                if (t == null) return null;
                b.append(t);
            }
            return b.append(">;").toString();
        }
        return null;
    }

    private static boolean boolish(Expr e) { return boolish(e, 0); }

    private static boolean boolish(Expr e, int depth) {
        if (depth > 8) return false;
        if (e instanceof Local l && l.v.find().stack && !l.v.find().defs.isEmpty()) {
            for (Expr d : l.v.find().defs) if (!boolish(d, depth + 1)) return false;
            return true;
        }
        if (e instanceof Const c) return c.isInt(0) || c.isInt(1) || c.desc.equals("Z");
        if (e instanceof Ternary t) return boolish(t.a, depth + 1) && boolish(t.b, depth + 1);
        return e.type().equals("Z");
    }

    private String infer(Var v) {
        if ("JFD".indexOf(v.kind) >= 0) return String.valueOf(v.kind);
        List<Expr> defs = new ArrayList<>();
        for (Expr d : v.defs) if (!(d instanceof Local l && l.v.find() == v)) defs.add(d);
        if (v.kind == 'I') {
            boolean allBool = !defs.isEmpty(), constOnly = true;
            String narrow = null;
            boolean mixed = false;
            for (Expr d : defs) {
                allBool &= boolish(d);
                if (d instanceof Const c && !c.desc.equals("Z") && !c.desc.equals("C")) continue;
                if (d instanceof Ternary t && t.a instanceof Const && t.b instanceof Const) continue;
                if (d instanceof Local l && l.v.find().stack && boolish(d)) continue;
                constOnly = false;
                String t = d.type();
                if (narrow == null) narrow = t;
                else if (!narrow.equals(t)) mixed = true;
            }
            if (allBool && !nonBool.contains(v) && (uses.getOrDefault(v, 0) > 0)) return "Z";
            if (allBool && !constOnly) return "Z";
            if (!mixed && !constOnly && narrow != null && Types.isIntLike(narrow) && !narrow.equals("Z")) return narrow;
            return "I";
        }
        String t = null;
        for (Expr d : defs) {
            String x = d.type();
            if (x.equals(Types.NULL) || x.equals(Types.UNK)) continue;
            t = t == null ? x : join(t, x);
        }
        if (t == null || t.equals(Types.OBJ)) {
            String h = null;
            for (String x : hints.getOrDefault(v, List.of())) {
                if (Types.isRef(x) && !x.equals(Types.OBJ)) h = h == null ? x : narrowest(h, x);
            }
            if (h != null && t == null) return h;
        }
        return t == null ? Types.OBJ : t;
    }

    private String narrowest(String a, String b) {
        if (Types.assignable(a, b, ws)) return a;
        return b;
    }

    private String join(String a, String b) {
        if (a.equals(b)) return a;
        if (a.startsWith("L") && b.startsWith("L")) return Types.ofInternal(ws.commonSuper(Types.internal(a), Types.internal(b)));
        return Types.OBJ;
    }

    // ---------------------------------------------------------------- coercion

    private boolean known(String d) {
        return !d.startsWith("L") || ws.exists(Types.internal(d));
    }

    Expr coerce(Expr e, String want) {
        String have = e.type();
        if (want.equals(Types.UNK) || want.equals("V")) return e;
        if (e instanceof Lambda && !want.equals(have) && !have.equals(Types.UNK)) return new Cast(have, e);
        if (e instanceof Ternary t && (Types.isPrimitive(want))) {
            t.a = coerce(t.a, want);
            t.b = coerce(t.b, want);
            if (want.equals("Z") && t.a instanceof Const x && t.b instanceof Const y) {
                if (x.isInt(1) && y.isInt(0)) return t.c;
                if (x.isInt(0) && y.isInt(1)) return Sim.negate(t.c);
            }
            return t;
        }
        if (e instanceof Const c && c.v instanceof Integer i && Types.isIntLike(want)) {
            if (want.equals("Z") && (i == 0 || i == 1)) return Const.bool(i == 1);
            if (want.equals("C") && i >= 0 && i <= 65535) return new Const(i, "C");
            if (want.equals("I")) return e;
            if (want.equals("B") || want.equals("S")) return new Const(i, want);
        }
        if (want.equals("Z") && Types.isIntLike(have) && !have.equals("Z")) return new Binary("!=", e, Const.i(0), "Z");
        if (have.equals("Z") && Types.isIntLike(want) && !want.equals("Z")) return new Ternary(e, Const.i(1), Const.i(0));
        if (want.length() == 1 && "BCS".contains(want) && Types.isIntLike(have) && !have.equals(want)) {
            boolean widening = want.equals("S") && have.equals("B");
            if (!widening) return new Cast(want, e);
        }
        if (Types.isRef(want) && Types.isRef(have) && !have.equals(Types.NULL) && !Types.assignable(have, want, ws)
                && known(have) && known(want)) return new Cast(want, e);
        return e;
    }

    /** Like coerce, but narrow literals must carry a cast because method arguments have no implicit narrowing. */
    private Expr arg(Expr e, String want) {
        Expr r = coerce(e, want);
        if (r instanceof Const c && c.v instanceof Integer && (want.equals("B") || want.equals("S"))) return new Cast(want, c);
        // javac emits nothing for char to int, but the overload chosen on recompile depends on it
        if (want.equals("I") && r.type().equals("C")) return new Cast("I", r);
        return r;
    }

    private void coerceAll(List<Stmt> body) {
        Ast.visit(body, s -> {
            s.exprs(e -> e.rewrite(this::coerceNode));
            if (s instanceof Return r && r.e != null) r.e = coerce(r.e, retDesc);
            if (s instanceof Return r && r.e != null && retGeneric != null && retGeneric.length() > 2) r.e = genericReturn(r.e);
            if (s instanceof Decl d && d.init != null) d.init = coerce(d.init, d.v.find().desc);
            if (s instanceof ExprStmt x && x.e instanceof Binary) s = s;
            if (s instanceof Switch w) {
                String t = w.sel.type();
                if (t.equals("C") || t.equals("B") || t.equals("S")) for (Case c : w.cases) c.keys.replaceAll(k -> coerce(k, t.equals("C") ? "C" : "I"));
            }
        });
    }

    /** Values assigned to a variable of type T need an unchecked cast once their static type is erased. */
    private Expr typeVar(Expr e, Var v) {
        if (v.generic == null || e instanceof Const) return e;
        String g = v.generic;
        int dims = 0;
        while (g.startsWith("[")) { g = g.substring(1); dims++; }
        if (!g.startsWith("T")) return e;
        if (e instanceof Local l && v.generic.equals(l.v.find().generic)) return e;
        Cast c = new Cast(v.desc, e instanceof Cast k ? k.e : e);
        c.generic = g.substring(1, g.length() - 1) + "[]".repeat(dims);
        return c;
    }

    private Expr genericReturn(Expr e) {
        int p = retGeneric.indexOf(')') + 1;
        int dims = 0;
        while (retGeneric.startsWith("[", p + dims)) dims++;
        if (e instanceof Const || !retGeneric.startsWith("T", p + dims)) return e;
        int end = retGeneric.indexOf(';', p);
        Cast c = new Cast(retDesc, e instanceof Cast k ? k.e : e);
        c.generic = retGeneric.substring(p + dims + 1, end) + "[]".repeat(dims);
        return c;
    }

    private Expr coerceNode(Expr x) {
        switch (x) {
            case Assign a -> {
                a.rhs = a.op != null ? a.rhs : coerce(a.rhs, a.lhs.type());
                if (a.op == null && a.lhs instanceof Local l) a.rhs = typeVar(a.rhs, l.v.find());
            }
            case Invoke m -> {
                List<String> ps = Types.params(m.desc);
                for (int i = 0; i < m.args.length && i < ps.size(); i++) m.args[i] = arg(m.args[i], ps.get(i));
                if (m.target != null && m.kind != Kind.SUPER) m.target = coerce(m.target, Types.ofInternal(m.owner));
            }
            case NewObj n -> {
                List<String> ps = Types.params(n.desc);
                for (int i = 0; i < n.args.length && i < ps.size(); i++) n.args[i] = arg(n.args[i], ps.get(i));
            }
            case NewArray n -> {
                if (n.init != null) n.init.replaceAll(v -> coerce(v, Types.elem(n.desc)));
            }
            case FieldRef f -> { if (f.target != null) f.target = coerce(f.target, Types.ofInternal(f.owner)); }
            case Ternary t -> {
                t.c = coerce(t.c, "Z");
                String ta = t.a.type(), tb = t.b.type();
                if (ta.equals("Z") && !tb.equals("Z")) t.b = coerce(t.b, "Z");
                if (tb.equals("Z") && !ta.equals("Z")) t.a = coerce(t.a, "Z");
                if (ta.equals("C") && t.b instanceof Const) t.b = coerce(t.b, "C");
                if (tb.equals("C") && t.a instanceof Const) t.a = coerce(t.a, "C");
            }
            case Unary u when u.op.equals("!") -> u.e = coerce(u.e, "Z");
            case Cast c when c.desc.equals("Z") -> { return coerce(c.e, "Z"); }
            case Cast c when Types.isPrimitive(c.desc) && c.e.type().equals(c.desc) -> { return c.e; }
            case Binary b -> { return binary(b); }
            default -> {}
        }
        return x;
    }

    private Expr binary(Binary b) {
        if (b.op.equals("&&") || b.op.equals("||")) {
            b.l = coerce(b.l, "Z");
            b.r = coerce(b.r, "Z");
            return b;
        }
        String tl = b.l.type(), tr = b.r.type();
        if (b.op.equals("==") || b.op.equals("!=")) {
            boolean eq = b.op.equals("==");
            if (b.l instanceof Ternary t && t.a instanceof Const x && t.b instanceof Const y && b.r instanceof Const k && k.v instanceof Integer
                    && (x.isInt(1) && y.isInt(0) || x.isInt(0) && y.isInt(1))) {
                boolean straight = (k.isInt(0) ? !eq : eq) == x.isInt(1);
                return straight ? t.c : Sim.negate(t.c);
            }
            if (tl.equals("Z") && b.r instanceof Const k && k.v instanceof Integer) return (k.isInt(0)) == eq ? Sim.negate(b.l) : b.l;
            if (tr.equals("Z") && b.l instanceof Const k && k.v instanceof Integer) return (k.isInt(0)) == eq ? Sim.negate(b.r) : b.r;
            if (tl.equals("Z") && !tr.equals("Z")) b.r = coerce(b.r, "Z");
            if (tr.equals("Z") && !tl.equals("Z")) b.l = coerce(b.l, "Z");
        }
        if (Ast.isCompare(b.op)) {
            if (tl.equals("C") && b.r instanceof Const) b.r = coerce(b.r, "C");
            if (tr.equals("C") && b.l instanceof Const) b.l = coerce(b.l, "C");
        }
        if (b.ty.equals("Z") && (b.op.equals("&") || b.op.equals("|") || b.op.equals("^"))) {
            b.l = coerce(b.l, "Z");
            b.r = coerce(b.r, "Z");
        } else if ((b.op.equals("&") || b.op.equals("|") || b.op.equals("^")) && tl.equals("Z") && tr.equals("Z")) b.ty = "Z";
        else if ((b.op.equals("&") || b.op.equals("|") || b.op.equals("^")) && (tl.equals("Z") || tr.equals("Z"))) {
            b.l = coerce(b.l, "Z");
            b.r = coerce(b.r, "Z");
            b.ty = "Z";
        }
        // ternaries and comparisons of narrow values with mismatched references need help from javac's rules only
        if ((b.op.equals("==") || b.op.equals("!=")) && Types.isRef(tl) && Types.isRef(tr)) {
            if (!tl.equals(Types.NULL) && !tr.equals(Types.NULL) && !Types.assignable(tl, tr, ws) && !Types.assignable(tr, tl, ws)
                    && known(tl) && known(tr)) b.l = new Cast(Types.OBJ, b.l);
        }
        return b;
    }

    // ---------------------------------------------------------------- compound operators

    private Expr compound(Expr x, boolean statement) {
        if (!(x instanceof Assign a) || a.op != null) return x;
        Expr r = a.rhs;
        boolean cast = false;
        if (r instanceof Cast c && c.desc.equals(a.lhs.type()) && Types.isPrimitive(c.desc) && c.e instanceof Binary) { r = c.e; cast = true; }
        if (!(r instanceof Binary b) || !b.l.same(a.lhs)) return x;
        if (Ast.isCompare(b.op) || b.op.equals("&&") || b.op.equals("||")) return x;
        if (a.lhs.any(e -> e instanceof Invoke || e instanceof Assign || e instanceof IncDec)) return x;
        if (b.r instanceof Const k && k.v instanceof Number n && n.doubleValue() == 1 && (b.op.equals("+") || b.op.equals("-")) && Types.isPrimitive(a.lhs.type())) {
            return new IncDec(a.lhs, b.op.equals("+"), statement);
        }
        return new Assign(a.lhs, b.op, b.r);
    }

    private void compoundAll(List<Stmt> body) {
        Ast.visit(body, s -> {
            s.exprs(e -> e.rewrite(x -> compound(x, false)));
            if (s instanceof ExprStmt es) {
                if (es.e instanceof IncDec n) n.post = true;
            }
            if (s instanceof Loop l) l.update.replaceAll(u -> { if (u instanceof IncDec n) n.post = true; return u; });
        });
    }

    // ---------------------------------------------------------------- declarations

    private final Map<Stmt, Set<Var>> deep = new IdentityHashMap<>();

    private Set<Var> touched(Stmt s) {
        Set<Var> got = deep.get(s);
        if (got != null) return got;
        Set<Var> r = new HashSet<>();
        s.exprs(e -> { varsOf(e, r::add); return e; });
        if (s instanceof Try t) for (CatchClause c : t.catches) r.remove(c.v.find());
        s.lists(l -> { for (Stmt k : l) r.addAll(touched(k)); });
        deep.put(s, r);
        return r;
    }

    private Set<Var> own(Stmt s) {
        Set<Var> r = new HashSet<>();
        s.exprs(e -> { varsOf(e, r::add); return e; });
        return r;
    }

    private static boolean plainDef(Stmt s, Var v) {
        return s instanceof ExprStmt es && es.e instanceof Assign a && a.op == null && a.lhs instanceof Local l && l.v.find() == v
                && !a.rhs.mentions(v);
    }

    private void declare(List<Stmt> l, Set<Var> vars) {
        Map<Stmt, Map<List<Stmt>, Set<Var>>> down = new IdentityHashMap<>();
        for (Var v : vars) {
            int first = -1, count = 0;
            for (int i = 0; i < l.size(); i++) {
                if (touched(l.get(i)).contains(v)) {
                    if (first < 0) first = i;
                    count++;
                }
            }
            if (first < 0) continue;
            Stmt s = l.get(first);
            List<Stmt> into = count == 1 ? deeper(s, v) : null;
            if (into != null) {
                down.computeIfAbsent(s, k -> new IdentityHashMap<>()).computeIfAbsent(into, k -> new LinkedHashSet<>()).add(v);
                continue;
            }
            if (plainDef(s, v)) {
                Assign a = (Assign) ((ExprStmt) s).e;
                Decl d = new Decl(v, a.rhs);
                l.set(first, d);
                deep.remove(s);
            } else {
                l.add(first, new Decl(v, null));
            }
        }
        for (var e : down.entrySet()) for (var f : e.getValue().entrySet()) declare(f.getKey(), f.getValue());
    }

    /** The single nested list v can be declared in, if any. */
    private List<Stmt> deeper(Stmt s, Var v) {
        if (own(s).contains(v)) return null;
        List<List<Stmt>> hit = new ArrayList<>();
        s.lists(c -> {
            for (Stmt k : c) if (touched(k).contains(v)) { hit.add(c); break; }
        });
        if (hit.size() != 1) return null;
        List<Stmt> t = hit.get(0);
        if (s instanceof Loop) {
            for (Stmt k : t) {
                if (touched(k).contains(v)) {
                    if (!plainDef(k, v)) return null;
                    break;
                }
            }
        }
        return t;
    }

    // ---------------------------------------------------------------- names

    private static final Set<String> KEYWORDS = Set.of("abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
            "const", "continue", "default", "do", "double", "else", "enum", "extends", "false", "final", "finally", "float", "for", "goto", "if",
            "implements", "import", "instanceof", "int", "interface", "long", "native", "new", "null", "package", "private", "protected", "public",
            "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this", "throw", "throws", "transient", "true", "try", "void",
            "volatile", "while", "_");

    static boolean validName(String s) {
        if (s == null || s.isEmpty() || KEYWORDS.contains(s) || !Character.isJavaIdentifierStart(s.charAt(0))) return false;
        for (int i = 1; i < s.length(); i++) if (!Character.isJavaIdentifierPart(s.charAt(i))) return false;
        return true;
    }

    private String base(Var v) {
        String d = v.desc;
        int dims = 0;
        while (d.startsWith("[")) { d = d.substring(1); dims++; }
        String b;
        switch (d) {
            case "Z" -> b = "flag";
            case "B" -> b = "b";
            case "C" -> b = "c";
            case "S" -> b = "sh";
            case "I" -> b = dims > 0 ? "n" : "i";
            case "J" -> b = "l";
            case "F" -> b = "f";
            case "D" -> b = "d";
            default -> {
                String n = Types.internal(d);
                n = n.substring(Math.max(n.lastIndexOf('/'), n.lastIndexOf('$')) + 1).replaceAll("^[0-9]+", "");
                if (n.equals("Object")) b = "obj";
                else if (n.equals("String")) b = "str";
                else if (n.equals("Class")) b = "clazz";
                else if (n.endsWith("Exception") || n.endsWith("Error") || n.equals("Throwable")) b = "ex";
                else if (n.isEmpty()) b = "obj";
                else b = Character.toLowerCase(n.charAt(0)) + n.substring(1);
            }
        }
        if (dims > 0) b += "s";
        return validName(b) ? b : b + "_";
    }

    private final Set<String> used = new HashSet<>();

    private String fresh(String b) {
        if (!reserved.contains(b) && used.add(b)) return b;
        if (b.length() == 1 && Character.isLowerCase(b.charAt(0)) && "ijk".contains(b)) {
            for (char c = (char) (b.charAt(0) + 1); c <= 'n'; c++) {
                String x = String.valueOf(c);
                if (!reserved.contains(x) && used.add(x)) return x;
            }
        }
        for (int i = 2; ; i++) {
            String x = b + i;
            if (!reserved.contains(x) && used.add(x)) return x;
        }
    }

    private void name(List<Var> order) {
        for (Var v : order) {
            if (v.isThis) continue;
            if (v.fixedName && validName(v.name) && !reserved.contains(v.name)) used.add(v.name);
            else if (v.fixedName) v.fixedName = false;
        }
        for (Var v : order) {
            if (v.isThis || v.fixedName) continue;
            v.name = fresh(base(v));
        }
    }

    /** Debug-info names can repeat in sibling scopes but may collide once declarations move; rename those. */
    private void scopes(List<Stmt> l, Set<String> visible) {
        Set<String> here = new HashSet<>(visible);
        for (Stmt s : l) {
            if (s instanceof Decl d) {
                Var v = d.v.find();
                if (!here.add(v.name)) {
                    v.name = fresh(v.name);
                    here.add(v.name);
                }
            }
            if (s instanceof Loop p && p.kind == LoopKind.FOREACH) {
                Var v = p.each.find();
                if (here.contains(v.name)) v.name = fresh(v.name);
                Set<String> in = new HashSet<>(here);
                in.add(v.name);
                scopes(p.body, in);
                continue;
            }
            if (s instanceof Loop p && p.init instanceof Decl d) {
                Var v = d.v.find();
                if (here.contains(v.name)) v.name = fresh(v.name);
                Set<String> in = new HashSet<>(here);
                in.add(v.name);
                scopes(p.body, in);
                continue;
            }
            if (s instanceof Try t) {
                scopes(t.body, here);
                for (CatchClause c : t.catches) {
                    Var v = c.v.find();
                    if (here.contains(v.name)) v.name = fresh(v.name);
                    Set<String> in = new HashSet<>(here);
                    in.add(v.name);
                    scopes(c.body, in);
                }
                if (t.fin != null) scopes(t.fin, here);
                continue;
            }
            if (s instanceof Switch w) {
                // all case bodies share one scope
                List<Stmt> all = new ArrayList<>();
                for (Case c : w.cases) all.addAll(c.body);
                scopes(all, here);
                continue;
            }
            s.lists(x -> scopes(x, here));
        }
    }

    // ---------------------------------------------------------------- labels

    private int nextLabel = 1;

    private void labels(List<Stmt> l, Deque<Stmt> breakables) {
        for (Stmt s : l) {
            switch (s) {
                case Break b -> {
                    if (breakables.peek() != b.target) b.label = label(b.target);
                    else b.label = null;
                }
                case Continue c -> {
                    Stmt inner = null;
                    for (Stmt x : breakables) if (x instanceof Loop) { inner = x; break; }
                    if (inner != c.target) c.label = label(c.target);
                    else c.label = null;
                }
                case Labeled b -> {
                    Deque<Stmt> saved = breakables;
                    labelsIn(b.body, saved);
                }
                case Loop p -> {
                    breakables.push(p);
                    labels(p.body, breakables);
                    breakables.pop();
                }
                case Switch w -> {
                    breakables.push(w);
                    for (Case c : w.cases) labels(c.body, breakables);
                    breakables.pop();
                }
                default -> s.lists(x -> labels(x, breakables));
            }
        }
    }

    private void labelsIn(List<Stmt> body, Deque<Stmt> b) { labels(body, b); }

    private String label(Stmt t) {
        switch (t) {
            case Labeled b -> { if (b.label == null) b.label = "block" + nextLabel++; return b.label; }
            case Loop p -> { if (p.label == null) p.label = "loop" + nextLabel++; return p.label; }
            case Switch w -> { if (w.label == null) w.label = "sw" + nextLabel++; return w.label; }
            default -> throw new IllegalStateException();
        }
    }

    // ---------------------------------------------------------------- driver

    void types(List<Stmt> body) {
        scanVars(body);
        for (Var p : params) allVars.add(p.find());
        scanUses(body);
        for (Var v : allVars) resolve(v);
        mergeSlots(body);
        coerceAll(body);
    }

    /** x2 = f(x) on the same slot and type is the same source variable being updated. */
    private void mergeSlots(List<Stmt> body) {
        Ast.visit(body, s -> {
            if (!(s instanceof ExprStmt es && es.e instanceof Assign a && a.lhs instanceof Local l)) return;
            Var to = l.v.find();
            if (to.stack || to.slot < 0 || to.isThis) return;
            a.rhs.walk(x -> {
                if (x instanceof Local r) {
                    Var from = r.v.find();
                    Var dst = l.v.find();
                    if (from != dst && from.slot == dst.slot && !from.stack && !from.isThis && from.desc.equals(dst.desc)
                            && (!from.fixedName || !dst.fixedName || from.name.equals(dst.name))) {
                        if (dst.param) Var.union(dst, from); else Var.union(from, dst);
                    }
                }
            });
        });
    }

    void finish(List<Stmt> body) {
        compoundAll(body);
        Sugar.preDeclare(body, ws);
        Simplify.run(body);
        allVars.clear();
        scanVars(body);
        for (Var v : allVars) resolve(v);
        Set<Var> locals = new LinkedHashSet<>(allVars);
        for (Var p : params) locals.remove(p.find());
        if (thisVar != null) locals.remove(thisVar.find());
        Ast.visit(body, s -> { if (s instanceof Try t) for (CatchClause c : t.catches) locals.remove(c.v.find()); });
        declare(body, locals);
        Sugar.postDeclare(body, ws);
        // assignments as statements that lost their target's declaration get cleaned up by the passes above
        List<Var> names = new ArrayList<>();
        for (Var p : params) names.add(p.find());
        names.addAll(locals);
        Ast.visit(body, s -> { if (s instanceof Try t) for (CatchClause c : t.catches) names.add(c.v.find()); });
        Ast.visit(body, s -> {
            if (s instanceof Loop p && p.each != null) names.add(p.each.find());
        });
        name(names);
        Set<String> top = new HashSet<>();
        for (Var p : params) top.add(p.find().name);
        scopes(body, top);
        labels(body, new ArrayDeque<>());
    }

    Set<String> usedNames() { return used; }
}
