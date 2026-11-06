package regrind.decomp;

import java.util.*;
import java.util.function.*;

/** Expression and statement trees the decompiler works on, with their source rendering. */
public final class Ast {
    private Ast() {}

    /** Text sink for rendering. In key mode, output is a canonical form used to compare expressions. */
    public static final class Out {
        public final StringBuilder sb = new StringBuilder();
        public int ind;
        public final Imports im;
        public final boolean key;
        public String thisClass;
        public BiConsumer<NewObj, Out> anon;
        public Out(Imports im, boolean key) { this.im = im; this.key = key; }
        public Out add(String s) { sb.append(s); return this; }
        public Out line() {
            sb.append('\n').append("    ".repeat(ind));
            return this;
        }
        public String cls(String internal) { return key ? internal : im.name(internal); }
        public String ty(String desc) { return key ? desc : Types.src(desc, im); }
    }

    private static int nextId;

    public static final class Var {
        public final int id = ++nextId;
        public String name, desc = Types.UNK, generic;
        public boolean stack, param, isThis, fixedName, noInline;
        public int slot = -1;
        public char kind = 'A'; // I, J, F, D, A
        public final List<Expr> defs = new ArrayList<>();
        public final List<String> useHints = new ArrayList<>();
        public boolean boolUseOnly = true;
        Var rep;
        public Var find() {
            Var v = this;
            while (v.rep != null) v = v.rep;
            return v;
        }
        public static void union(Var a, Var b) {
            a = a.find();
            b = b.find();
            if (a != b) {
                b.rep = a;
                a.defs.addAll(b.defs);
                a.useHints.addAll(b.useHints);
                a.boolUseOnly &= b.boolUseOnly;
            }
        }
    }

    // ---------------------------------------------------------------- expressions

    public abstract static class Expr {
        public abstract String type();
        public int prec() { return 15; }
        public abstract void print(Out o);
        /** Apply f to each direct child, storing the result back. */
        public void mapKids(UnaryOperator<Expr> f) {}
        public final Expr rewrite(UnaryOperator<Expr> f) {
            mapKids(k -> k.rewrite(f));
            return f.apply(this);
        }
        public final void walk(Consumer<Expr> c) {
            c.accept(this);
            mapKids(k -> { k.walk(c); return k; });
        }
        public final boolean mentions(Var v) {
            boolean[] r = {false};
            Var t = v.find();
            walk(e -> { if (e instanceof Local l && l.v.find() == t) r[0] = true; });
            return r[0];
        }
        public final boolean any(Predicate<Expr> p) {
            boolean[] r = {false};
            walk(e -> { if (p.test(e)) r[0] = true; });
            return r[0];
        }
        public final void printAt(Out o, int minPrec) {
            if (prec() < minPrec) {
                o.add("(");
                print(o);
                o.add(")");
            } else print(o);
        }
        public final String key() {
            Out o = new Out(null, true);
            print(o);
            return o.sb.toString();
        }
        public final boolean same(Expr b) { return b != null && key().equals(b.key()); }
    }

    public static final class Const extends Expr {
        public Object v;
        public String desc;
        public Const(Object v, String desc) { this.v = v; this.desc = desc; }
        public static Const nul() { return new Const(null, Types.NULL); }
        public static Const i(int x) { return new Const(x, "I"); }
        public static Const bool(boolean b) { return new Const(b ? 1 : 0, "Z"); }
        public static Const cls(String desc) { return new Const(desc, "Ljava/lang/Class;"); }
        @Override public String type() { return desc; }
        @Override public int prec() {
            if (v instanceof Number n && !desc.equals("Z") && n.doubleValue() < 0) return 13;
            return 15;
        }
        public boolean isInt(int x) { return v instanceof Integer i && i == x; }
        @Override public void print(Out o) {
            if (v == null) { o.add("null"); return; }
            if (desc.equals("Ljava/lang/Class;")) { o.add(o.ty((String) v)).add(".class"); return; }
            if (v instanceof String s) { o.add(quote(s)); return; }
            switch (desc) {
                case "Z" -> o.add(((Number) v).intValue() != 0 ? "true" : "false");
                case "C" -> o.add(charLit((char) ((Number) v).intValue()));
                case "J" -> o.add(v + "L");
                case "F" -> {
                    float f = (Float) v;
                    if (Float.isNaN(f)) o.add("Float.NaN");
                    else if (Float.isInfinite(f)) o.add(f > 0 ? "Float.POSITIVE_INFINITY" : "Float.NEGATIVE_INFINITY");
                    else o.add(f + "f");
                }
                case "D" -> {
                    double d = (Double) v;
                    if (Double.isNaN(d)) o.add("Double.NaN");
                    else if (Double.isInfinite(d)) o.add(d > 0 ? "Double.POSITIVE_INFINITY" : "Double.NEGATIVE_INFINITY");
                    else o.add(String.valueOf(d));
                }
                default -> o.add(String.valueOf(v));
            }
        }
    }

    public static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) esc(b, s.charAt(i), '"');
        return b.append('"').toString();
    }
    public static String charLit(char c) {
        StringBuilder b = new StringBuilder("'");
        esc(b, c, '\'');
        return b.append('\'').toString();
    }
    private static void esc(StringBuilder b, char c, char q) {
        switch (c) {
            case '\n' -> b.append("\\n");
            case '\r' -> b.append("\\r");
            case '\t' -> b.append("\\t");
            case '\b' -> b.append("\\b");
            case '\f' -> b.append("\\f");
            case '\\' -> b.append("\\\\");
            default -> {
                if (c == q) b.append('\\').append(c);
                else if (c < 32 || c >= 127 && c < 161 || Character.isSurrogate(c) || c == 0xFFFE || c == 0xFFFF)
                    b.append(String.format("\\u%04x", (int) c));
                else b.append(c);
            }
        }
    }

    public static final class Local extends Expr {
        public Var v;
        public Local(Var v) { this.v = v; }
        @Override public String type() {
            Var r = v.find();
            if (r.desc.equals(Types.UNK) && r.kind == 'I') return "I";
            return r.desc;
        }
        @Override public void print(Out o) {
            Var r = v.find();
            if (o.key) o.add("#" + r.id);
            else o.add(r.isThis ? "this" : r.name == null ? "v" + r.id : r.name);
        }
    }

    /** Literal source text with a known type (enum case labels and the like). */
    public static final class Raw extends Expr {
        public final String text, desc;
        public Raw(String t, String d) { text = t; desc = d; }
        @Override public String type() { return desc; }
        @Override public void print(Out o) { o.add(text); }
    }

    /** Placeholder for the exception object at a handler's start. */
    public static final class Caught extends Expr {
        public String desc;
        public Caught(String d) { desc = d; }
        @Override public String type() { return desc; }
        @Override public void print(Out o) { o.add("$caught"); }
    }

    /** {@code new} before its constructor has been called. Lives only on the simulated stack. */
    public static final class Uninit extends Expr {
        public final String cls;
        public Uninit(String c) { cls = c; }
        @Override public String type() { return Types.ofInternal(cls); }
        @Override public void print(Out o) { o.add("new " + cls); }
    }

    public static final class FieldRef extends Expr {
        public Expr target;
        public final String owner, name, desc;
        public FieldRef(Expr t, String owner, String name, String desc) { target = t; this.owner = owner; this.name = name; this.desc = desc; }
        @Override public String type() { return desc; }
        @Override public void mapKids(UnaryOperator<Expr> f) { if (target != null) target = f.apply(target); }
        @Override public void print(Out o) {
            if (target != null) {
                target.printAt(o, 15);
                o.add(".").add(name);
            } else if (!o.key && owner.equals(o.thisClass)) o.add(name);
            else o.add(o.cls(owner)).add(".").add(name);
        }
    }

    public static final class ArrayGet extends Expr {
        public Expr arr, idx;
        public String hint = Types.UNK; // element type from the instruction, for arrays of unresolved type
        public ArrayGet(Expr a, Expr i) { arr = a; idx = i; }
        @Override public String type() {
            String t = Types.elem(arr.type());
            return t.equals(Types.UNK) ? hint : t;
        }
        @Override public void mapKids(UnaryOperator<Expr> f) { arr = f.apply(arr); idx = f.apply(idx); }
        @Override public void print(Out o) {
            arr.printAt(o, 15);
            o.add("[");
            idx.print(o);
            o.add("]");
        }
    }

    public static final class ArrayLen extends Expr {
        public Expr arr;
        public ArrayLen(Expr a) { arr = a; }
        @Override public String type() { return "I"; }
        @Override public void mapKids(UnaryOperator<Expr> f) { arr = f.apply(arr); }
        @Override public void print(Out o) {
            arr.printAt(o, 15);
            o.add(".length");
        }
    }

    public static final class Binary extends Expr {
        public String op;
        public Expr l, r;
        public String ty;
        public Binary(String op, Expr l, Expr r, String ty) { this.op = op; this.l = l; this.r = r; this.ty = ty; }
        @Override public String type() { return ty; }
        @Override public int prec() { return precOf(op); }
        @Override public void mapKids(UnaryOperator<Expr> f) { l = f.apply(l); r = f.apply(r); }
        @Override public void print(Out o) {
            int p = prec();
            l.printAt(o, p);
            o.add(" " + op + " ");
            // string concat and && || are associative; everything else needs the right side bracketed
            boolean assoc = op.equals("&&") || op.equals("||");
            r.printAt(o, assoc ? p : p + 1);
        }
    }

    static int precOf(String op) {
        return switch (op) {
            case "||" -> 3;
            case "&&" -> 4;
            case "|" -> 5;
            case "^" -> 6;
            case "&" -> 7;
            case "==", "!=" -> 8;
            case "<", "<=", ">", ">=" -> 9;
            case "<<", ">>", ">>>" -> 10;
            case "+", "-" -> 11;
            default -> 12;
        };
    }
    public static boolean isCompare(String op) {
        return switch (op) { case "==", "!=", "<", "<=", ">", ">=" -> true; default -> false; };
    }

    public static final class Unary extends Expr {
        public String op;
        public Expr e;
        public Unary(String op, Expr e) { this.op = op; this.e = e; }
        @Override public String type() { return op.equals("!") ? "Z" : e.type(); }
        @Override public int prec() { return 13; }
        @Override public void mapKids(UnaryOperator<Expr> f) { e = f.apply(e); }
        @Override public void print(Out o) {
            o.add(op);
            // avoid "--x" for a negated negative
            if (op.equals("-") && (e instanceof Unary u && u.op.equals("-") || e instanceof Const c && c.prec() == 13)) {
                o.add("(");
                e.print(o);
                o.add(")");
            } else e.printAt(o, 13);
        }
    }

    public static final class Cast extends Expr {
        public String desc;
        public Expr e;
        public String generic;
        public Cast(String d, Expr e) { desc = d; this.e = e; }
        @Override public String type() { return desc; }
        @Override public int prec() { return 13; }
        @Override public void mapKids(UnaryOperator<Expr> f) { e = f.apply(e); }
        @Override public void print(Out o) {
            o.add("(").add(generic != null ? generic : o.ty(desc)).add(")");
            e.printAt(o, 13);
        }
    }

    public static final class InstanceOf extends Expr {
        public Expr e;
        public String desc;
        public InstanceOf(Expr e, String d) { this.e = e; desc = d; }
        @Override public String type() { return "Z"; }
        @Override public int prec() { return 9; }
        @Override public void mapKids(UnaryOperator<Expr> f) { e = f.apply(e); }
        @Override public void print(Out o) {
            e.printAt(o, 9);
            o.add(" instanceof ").add(o.ty(desc));
        }
    }

    public static final class Ternary extends Expr {
        public Expr c, a, b;
        public Ternary(Expr c, Expr a, Expr b) { this.c = c; this.a = a; this.b = b; }
        @Override public String type() {
            String x = a.type(), y = b.type();
            if (x.equals(Types.NULL)) return y;
            if (y.equals(Types.NULL)) return x;
            if (Types.isIntLike(x) && Types.isIntLike(y)) {
                if (x.equals(y)) return x;
                if (a instanceof Const) return y;
                if (b instanceof Const) return x;
                return "I";
            }
            return x;
        }
        @Override public int prec() { return 2; }
        @Override public void mapKids(UnaryOperator<Expr> f) { c = f.apply(c); a = f.apply(a); b = f.apply(b); }
        @Override public void print(Out o) {
            c.printAt(o, 3);
            o.add(" ? ");
            a.printAt(o, 2);
            o.add(" : ");
            b.printAt(o, 2);
        }
    }

    public enum Kind { STATIC, VIRTUAL, SPECIAL, INTERFACE, SUPER }

    public static final class Invoke extends Expr {
        public Kind kind;
        public Expr target;
        public String owner, name, desc;
        public Expr[] args;
        public Invoke(Kind k, Expr t, String owner, String name, String desc, Expr[] args) {
            kind = k; target = t; this.owner = owner; this.name = name; this.desc = desc; this.args = args;
        }
        @Override public String type() { return Types.ret(desc); }
        @Override public void mapKids(UnaryOperator<Expr> f) {
            if (target != null) target = f.apply(target);
            for (int i = 0; i < args.length; i++) args[i] = f.apply(args[i]);
        }
        public boolean is(String own, String nm) { return name.equals(nm) && owner.equals(own); }
        @Override public void print(Out o) {
            if (kind == Kind.SUPER) {
                if (name.equals("<init>")) o.add(target instanceof Local l && l.v.isThis && o.thisClass != null && owner.equals(o.thisClass) ? "this" : "super");
                else if (o.key) o.add("super." + owner + "." + name);
                else o.add(superPrefix(o)).add(".").add(name);
            } else if (target != null) {
                target.printAt(o, 15);
                o.add(".").add(name);
            } else if (!o.key && owner.equals(o.thisClass)) o.add(name);
            else o.add(o.cls(owner)).add(".").add(name);
            printArgs(o, args);
        }
        private String superPrefix(Out o) {
            return iface ? o.cls(owner) + ".super" : "super";
        }
        public boolean iface;
    }

    static void printArgs(Out o, Expr[] args) {
        o.add("(");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) o.add(", ");
            args[i].printAt(o, 1);
        }
        o.add(")");
    }

    public static final class NewObj extends Expr {
        public final String cls, desc;
        public Expr[] args;
        public Expr outer; // qualifying instance for outer.new Inner(), when shown
        public Object anonBody; // set when this instantiates an inlined anonymous class
        public boolean hiddenOuter, diamond;
        public NewObj(String c, String d, Expr[] a) { cls = c; desc = d; args = a; }
        @Override public String type() { return Types.ofInternal(cls); }
        @Override public void mapKids(UnaryOperator<Expr> f) {
            if (outer != null) outer = f.apply(outer);
            for (int i = 0; i < args.length; i++) args[i] = f.apply(args[i]);
        }
        @Override public void print(Out o) {
            if (anonBody != null && !o.key) { o.anon.accept(this, o); return; }
            if (outer != null) { outer.printAt(o, 15); o.add("."); }
            String n = o.cls(cls);
            if (outer != null && n.contains(".")) n = n.substring(n.lastIndexOf('.') + 1);
            o.add("new ").add(n);
            if (diamond) o.add("<>");
            printArgs(o, args);
        }
    }

    public static final class NewArray extends Expr {
        public final String desc; // full array type
        public Expr[] dims;
        public List<Expr> init; // non-null for array initialisers
        public NewArray(String desc, Expr[] dims) { this.desc = desc; this.dims = dims; }
        @Override public String type() { return desc; }
        @Override public void mapKids(UnaryOperator<Expr> f) {
            for (int i = 0; i < dims.length; i++) dims[i] = f.apply(dims[i]);
            if (init != null) init.replaceAll(f);
        }
        @Override public void print(Out o) {
            if (init != null) {
                o.add("new ").add(o.ty(desc)).add("{");
                for (int i = 0; i < init.size(); i++) {
                    if (i > 0) o.add(", ");
                    init.get(i).printAt(o, 2);
                }
                o.add("}");
                return;
            }
            int total = 0;
            while (desc.charAt(total) == '[') total++;
            o.add("new ").add(o.ty(desc.substring(total)));
            for (Expr d : dims) { o.add("["); d.print(o); o.add("]"); }
            o.add("[]".repeat(total - dims.length));
        }
    }

    /** lhs = rhs, or lhs op= rhs when op is set. */
    public static final class Assign extends Expr {
        public Expr lhs, rhs;
        public String op;
        public Assign(Expr l, Expr r) { lhs = l; rhs = r; }
        public Assign(Expr l, String op, Expr r) { lhs = l; this.op = op; rhs = r; }
        @Override public String type() { return lhs.type(); }
        @Override public int prec() { return 1; }
        @Override public void mapKids(UnaryOperator<Expr> f) {
            if (!(lhs instanceof Local)) lhs = f.apply(lhs);
            rhs = f.apply(rhs);
        }
        @Override public void print(Out o) {
            lhs.printAt(o, 15);
            o.add(op == null ? " = " : " " + op + "= ");
            rhs.printAt(o, 1);
        }
    }

    public static final class IncDec extends Expr {
        public Expr e;
        public boolean inc, post;
        public IncDec(Expr e, boolean inc, boolean post) { this.e = e; this.inc = inc; this.post = post; }
        @Override public String type() { return e.type(); }
        @Override public int prec() { return post ? 14 : 13; }
        @Override public void mapKids(UnaryOperator<Expr> f) { if (!(e instanceof Local)) e = f.apply(e); }
        @Override public void print(Out o) {
            String s = inc ? "++" : "--";
            if (!post) o.add(s);
            e.printAt(o, 14);
            if (post) o.add(s);
        }
    }

    public static final class Monitor extends Expr {
        public Expr e;
        public final boolean enter;
        public Monitor(Expr e, boolean enter) { this.e = e; this.enter = enter; }
        @Override public String type() { return "V"; }
        @Override public void mapKids(UnaryOperator<Expr> f) { e = f.apply(e); }
        @Override public void print(Out o) {
            o.add(enter ? "/* monitorenter */ " : "/* monitorexit */ ");
            e.printAt(o, 13);
        }
    }

    /** A dynamic call site with no source form. */
    public static final class Dynamic extends Expr {
        public String label, desc;
        public Expr[] args;
        public Dynamic(String l, String d, Expr[] a) { label = l; desc = d; args = a; }
        @Override public String type() { return Types.ret(desc); }
        @Override public void mapKids(UnaryOperator<Expr> f) { for (int i = 0; i < args.length; i++) args[i] = f.apply(args[i]); }
        @Override public void print(Out o) {
            o.add(label);
            printArgs(o, args);
        }
    }

    /** A lambda or method reference, resolved late so that the enclosing names are final. */
    public static final class Lambda extends Expr {
        public String ifaceDesc;       // the functional interface type
        public int refKind;
        public String owner, name, desc;
        public boolean ownerIface;
        public Expr[] captured;
        public String sam, instDesc;    // interface method name, instantiated method type
        public Lambda(String t, int k, String o, String n, String d, Expr[] c) { ifaceDesc = t; refKind = k; owner = o; name = n; desc = d; captured = c; }
        @Override public String type() { return ifaceDesc; }
        @Override public int prec() { return 1; }
        @Override public void mapKids(UnaryOperator<Expr> f) { for (int i = 0; i < captured.length; i++) captured[i] = f.apply(captured[i]); }
        public Function<Out, Void> render;
        @Override public void print(Out o) {
            if (o.key) {
                o.add("lambda:" + owner + "." + name + desc);
                printArgs(o, captured);
            } else if (render != null) render.apply(o);
            else o.add("/* lambda " + owner + "." + name + " */ null");
        }
    }

    // ---------------------------------------------------------------- statements

    public abstract static class Stmt {
        public abstract void print(Out o);
        public void exprs(UnaryOperator<Expr> f) {}
        public void lists(Consumer<List<Stmt>> c) {}
    }

    public static void printBlock(Out o, List<Stmt> b) {
        o.add("{");
        o.ind++;
        for (Stmt s : b) {
            o.line();
            s.print(o);
        }
        o.ind--;
        o.line().add("}");
    }

    public static final class ExprStmt extends Stmt {
        public Expr e;
        public ExprStmt(Expr e) { this.e = e; }
        @Override public void print(Out o) {
            if (statementExpr(e) || o.key) {
                e.print(o);
                o.add(";");
            } else {
                // compilers other than javac can discard values Java has no statement form for
                o.add("{ Object unused = ");
                e.printAt(o, 1);
                o.add("; }");
            }
        }
        @Override public void exprs(UnaryOperator<Expr> f) { e = f.apply(e); }
    }

    public static final class Decl extends Stmt {
        public Var v;
        public Expr init;
        public Decl(Var v, Expr init) { this.v = v; this.init = init; }
        public static String typeText(Var r, Out o) {
            return r.generic != null ? Sig.field(r.generic, o.im) : o.ty(r.desc);
        }
        void printNoSemi(Out o) {
            Var r = v.find();
            o.add(typeText(r, o)).add(" ");
            new Local(r).print(o);
            if (init != null) {
                o.add(" = ");
                init.printAt(o, 1);
            }
        }
        @Override public void print(Out o) { printNoSemi(o); o.add(";"); }
        @Override public void exprs(UnaryOperator<Expr> f) { if (init != null) init = f.apply(init); }
    }

    public static final class Return extends Stmt {
        public Expr e;
        public Return(Expr e) { this.e = e; }
        @Override public void print(Out o) {
            if (e == null) o.add("return;");
            else { o.add("return "); e.print(o); o.add(";"); }
        }
        @Override public void exprs(UnaryOperator<Expr> f) { if (e != null) e = f.apply(e); }
    }

    public static final class Throw extends Stmt {
        public Expr e;
        public Throw(Expr e) { this.e = e; }
        @Override public void print(Out o) { o.add("throw "); e.print(o); o.add(";"); }
        @Override public void exprs(UnaryOperator<Expr> f) { e = f.apply(e); }
    }

    public static final class If extends Stmt {
        public Expr c;
        public List<Stmt> a, b;
        public If(Expr c, List<Stmt> a, List<Stmt> b) { this.c = c; this.a = a; this.b = b; }
        @Override public void print(Out o) {
            o.add("if (");
            c.print(o);
            o.add(") ");
            printBlock(o, a);
            if (b != null && !b.isEmpty()) {
                o.add(" else ");
                if (b.size() == 1 && b.get(0) instanceof If i) i.print(o);
                else printBlock(o, b);
            }
        }
        @Override public void exprs(UnaryOperator<Expr> f) { c = f.apply(c); }
        @Override public void lists(Consumer<List<Stmt>> c) { c.accept(a); if (b != null) c.accept(b); }
    }

    public static final class Labeled extends Stmt {
        public String label;
        public List<Stmt> body;
        public Labeled(List<Stmt> b) { body = b; }
        @Override public void print(Out o) { o.add(label + ": "); printBlock(o, body); }
        @Override public void lists(Consumer<List<Stmt>> c) { c.accept(body); }
    }

    public enum LoopKind { WHILE, DO, FOR, FOREACH }

    public static final class Loop extends Stmt {
        public LoopKind kind = LoopKind.WHILE;
        public Expr cond; // null = true
        public List<Stmt> body;
        public Stmt init;
        public List<Expr> update = new ArrayList<>();
        public Var each;
        public Expr iter;
        public String label;
        public Loop(List<Stmt> b) { body = b; }
        @Override public void print(Out o) {
            if (label != null) o.add(label + ": ");
            switch (kind) {
                case WHILE -> {
                    o.add("while (");
                    if (cond == null) o.add("true"); else cond.print(o);
                    o.add(") ");
                    printBlock(o, body);
                }
                case DO -> {
                    o.add("do ");
                    printBlock(o, body);
                    o.add(" while (");
                    cond.print(o);
                    o.add(");");
                }
                case FOR -> {
                    o.add("for (");
                    if (init instanceof Decl d) d.printNoSemi(o);
                    else if (init instanceof ExprStmt e) e.e.print(o);
                    o.add("; ");
                    if (cond != null) cond.print(o);
                    o.add(";");
                    for (int i = 0; i < update.size(); i++) {
                        o.add(i == 0 ? " " : ", ");
                        update.get(i).print(o);
                    }
                    o.add(") ");
                    printBlock(o, body);
                }
                case FOREACH -> {
                    o.add("for (").add(Decl.typeText(each.find(), o)).add(" ");
                    new Local(each).print(o);
                    o.add(" : ");
                    iter.print(o);
                    o.add(") ");
                    printBlock(o, body);
                }
            }
        }
        @Override public void exprs(UnaryOperator<Expr> f) {
            if (cond != null) cond = f.apply(cond);
            if (init != null) init.exprs(f);
            update.replaceAll(f);
            if (iter != null) iter = f.apply(iter);
        }
        @Override public void lists(Consumer<List<Stmt>> c) { c.accept(body); }
    }

    public static final class Break extends Stmt {
        public Stmt target;
        public String label;
        public Break(Stmt t) { target = t; }
        @Override public void print(Out o) { o.add(label == null ? "break;" : "break " + label + ";"); }
    }

    public static final class Continue extends Stmt {
        public Loop target;
        public String label;
        public Continue(Loop t) { target = t; }
        @Override public void print(Out o) { o.add(label == null ? "continue;" : "continue " + label + ";"); }
    }

    public static final class Case {
        public List<Expr> keys = new ArrayList<>();
        public boolean isDefault;
        public List<Stmt> body = new ArrayList<>();
    }

    public static final class Switch extends Stmt {
        public Expr sel;
        public List<Case> cases = new ArrayList<>();
        public String label;
        public Switch(Expr s) { sel = s; }
        @Override public void print(Out o) {
            if (label != null) o.add(label + ": ");
            o.add("switch (");
            sel.print(o);
            o.add(") {");
            for (Case c : cases) {
                o.ind++;
                for (Expr k : c.keys) {
                    o.line().add("case ");
                    k.print(o);
                    o.add(":");
                }
                if (c.isDefault) o.line().add("default:");
                o.ind++;
                for (Stmt s : c.body) {
                    o.line();
                    s.print(o);
                }
                o.ind -= 2;
            }
            o.line().add("}");
        }
        @Override public void exprs(UnaryOperator<Expr> f) {
            sel = f.apply(sel);
            for (Case c : cases) c.keys.replaceAll(f);
        }
        @Override public void lists(Consumer<List<Stmt>> c) { for (Case k : cases) c.accept(k.body); }
    }

    public static final class CatchClause {
        public List<String> types = new ArrayList<>();
        public Var v;
        public List<Stmt> body;
    }

    public static final class Try extends Stmt {
        public List<Stmt> body;
        public List<CatchClause> catches = new ArrayList<>();
        public List<Stmt> fin;
        public Try(List<Stmt> b) { body = b; }
        @Override public void print(Out o) {
            o.add("try ");
            printBlock(o, body);
            for (CatchClause c : catches) {
                o.add(" catch (");
                for (int i = 0; i < c.types.size(); i++) {
                    if (i > 0) o.add(" | ");
                    o.add(o.ty(c.types.get(i)));
                }
                o.add(" ");
                new Local(c.v).print(o);
                o.add(") ");
                printBlock(o, c.body);
            }
            if (fin != null) {
                o.add(" finally ");
                printBlock(o, fin);
            }
        }
        @Override public void lists(Consumer<List<Stmt>> c) {
            c.accept(body);
            for (CatchClause k : catches) c.accept(k.body);
            if (fin != null) c.accept(fin);
        }
    }

    public static final class Sync extends Stmt {
        public Expr lock;
        public List<Stmt> body;
        public Sync(Expr l, List<Stmt> b) { lock = l; body = b; }
        @Override public void print(Out o) {
            o.add("synchronized (");
            lock.print(o);
            o.add(") ");
            printBlock(o, body);
        }
        @Override public void exprs(UnaryOperator<Expr> f) { lock = f.apply(lock); }
        @Override public void lists(Consumer<List<Stmt>> c) { c.accept(body); }
    }

    public static final class Assert extends Stmt {
        public Expr cond, msg;
        public Assert(Expr c, Expr m) { cond = c; msg = m; }
        @Override public void print(Out o) {
            o.add("assert ");
            cond.print(o);
            if (msg != null) { o.add(" : "); msg.print(o); }
            o.add(";");
        }
        @Override public void exprs(UnaryOperator<Expr> f) { cond = f.apply(cond); if (msg != null) msg = f.apply(msg); }
    }

    public static final class Comment extends Stmt {
        public String text;
        public Comment(String t) { text = t; }
        @Override public void print(Out o) { o.add("// " + text); }
    }

    static boolean statementExpr(Expr e) {
        return e instanceof Invoke || e instanceof Assign || e instanceof IncDec || e instanceof NewObj || e instanceof Dynamic || e instanceof Monitor;
    }

    /** The statement that evaluates a condition only for its side effects. */
    public static Stmt effect(Expr c) {
        return statementExpr(c) ? new ExprStmt(c) : new If(c, new ArrayList<>(), null);
    }

    /** Visit every statement, parents first. */
    public static void visit(List<Stmt> list, Consumer<Stmt> c) {
        for (Stmt s : new ArrayList<>(list)) {
            c.accept(s);
            s.lists(l -> visit(l, c));
        }
    }

    /** Rewrite every expression in a tree. */
    public static void rewriteAll(List<Stmt> list, UnaryOperator<Expr> f) {
        visit(list, s -> s.exprs(e -> e.rewrite(f)));
    }
}
