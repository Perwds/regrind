package regrind.decomp;

import regrind.core.Workspace;
import regrind.decomp.Ast.*;
import java.lang.classfile.*;
import java.lang.classfile.attribute.*;
import java.lang.classfile.constantpool.*;
import java.lang.classfile.instruction.*;
import java.lang.reflect.AccessFlag;
import java.util.*;

/** Renders one class (and the classes nested in it) as Java source. */
final class ClassPrinter {
    final Workspace ws;
    final ClassModel cm;
    final String name;
    final Imports im;
    final ClassPrinter parent;
    final Stats stats;
    final Map<String, Expr> captured = new HashMap<>(); // val$x -> value in the enclosing scope
    final boolean isEnum, isRecord, isInterface, isAnnotation;
    Set<String> outerNames = Set.of();

    static final class Stats {
        int classes, methods, failed;
        final List<String> failures = new ArrayList<>();
        final Set<String> printed = new HashSet<>();
    }

    record Body(List<Stmt> stmts, Finish finish, List<Var> params, Var thisVar) {}
    record AnonInfo(Set<Integer> visible, Map<Integer, String> captures) {}

    ClassPrinter(Workspace ws, ClassModel cm, Imports im, ClassPrinter parent, Stats stats) {
        this.ws = ws;
        this.cm = cm;
        this.im = im;
        this.parent = parent;
        this.stats = stats;
        name = cm.thisClass().asInternalName();
        isEnum = cm.flags().has(AccessFlag.ENUM);
        isRecord = cm.findAttribute(Attributes.record()).isPresent();
        isInterface = cm.flags().has(AccessFlag.INTERFACE);
        isAnnotation = cm.flags().has(AccessFlag.ANNOTATION);
    }

    private Out newOut() {
        Out o = new Out(im, false);
        o.thisClass = name;
        o.anon = this::printAnon;
        return o;
    }

    // ---------------------------------------------------------------- inner class facts

    private static InnerClassInfo innerInfo(ClassModel c, String n) {
        var a = c.findAttribute(Attributes.innerClasses());
        if (a.isEmpty()) return null;
        for (InnerClassInfo i : a.get().classes()) if (i.innerClass().asInternalName().equals(n)) return i;
        return null;
    }

    private boolean isAnonymous(String n) {
        ClassModel m = ws.model(n);
        if (m == null) return false;
        InnerClassInfo i = innerInfo(m, n);
        return i != null && i.innerName().isEmpty();
    }

    /** Is this a non-static member class, whose constructors take the outer instance first? */
    private boolean isInnerMember(ClassModel m, String n) {
        InnerClassInfo i = innerInfo(m, n);
        return i != null && i.outerClass().isPresent() && i.innerName().isPresent() && !i.has(AccessFlag.STATIC) && !m.flags().has(AccessFlag.ENUM)
                && !m.flags().has(AccessFlag.INTERFACE) && m.findAttribute(Attributes.record()).isEmpty();
    }

    private static boolean isSwitchMapClass(ClassModel m) {
        if (m.fields().isEmpty() || !m.methods().stream().allMatch(x -> x.methodName().stringValue().equals("<clinit>"))) return false;
        return m.fields().stream().allMatch(f -> f.fieldName().stringValue().startsWith("$SwitchMap$"));
    }

    // ---------------------------------------------------------------- method pipeline

    private Set<String> staticFieldNames() {
        Set<String> s = new HashSet<>();
        for (FieldModel f : cm.fields()) if (f.flags().has(AccessFlag.STATIC)) s.add(f.fieldName().stringValue());
        return s;
    }

    Body decompile(MethodModel m, Set<String> reservedExtra) {
        CodeModel code = m.code().orElseThrow();
        Cfg c = new Cfg(m, code);
        c.flatten();
        c.recoverFinally();
        c.build();
        c.buildLocals();
        if (c.thisVar != null) c.thisVar.desc = Types.ofInternal(name);
        new Sim(c, name).run();
        new Reduce(c).run();
        List<Stmt> body = new Structurer(c).run();
        Simplify.run(body);
        Set<String> reserved = new HashSet<>(staticFieldNames());
        reserved.addAll(reservedExtra);
        String md = m.methodType().stringValue();
        String retG = m.findAttribute(Attributes.signature()).map(s -> s.signature().stringValue()).orElse(null);
        applyParamNames(m, c);
        if (retG != null) {
            List<String> raw = Sig.rawParams(retG);
            int skip = c.params.size() - raw.size();
            for (int i = 0; i < raw.size() && skip >= 0; i++) c.params.get(i + skip).generic = raw.get(i);
        }
        Finish f = new Finish(ws, Types.ret(md), retG, c.params, c.thisVar, reserved);
        f.types(body);
        lowerInnerAccess(body);
        f.finish(body);
        // captured values belong to the enclosing method, so they are substituted after this method's variables are settled
        Ast.rewriteAll(body, x -> x instanceof FieldRef r && r.target != null && r.name.startsWith("val$") && lookupCapture(r.name) != null ? lookupCapture(r.name) : x);
        return new Body(body, f, c.params, c.thisVar);
    }

    private static void applyParamNames(MethodModel m, Cfg c) {
        var a = m.findAttribute(Attributes.methodParameters());
        if (a.isEmpty()) return;
        var ps = a.get().parameters();
        if (ps.size() != c.params.size()) return;
        for (int i = 0; i < ps.size(); i++) {
            var n = ps.get(i).name();
            if (n.isPresent() && !c.params.get(i).fixedName) {
                c.params.get(i).name = n.get().stringValue();
                c.params.get(i).fixedName = true;
            }
        }
    }

    /** Field and method access that only exists because of how inner classes are compiled. */
    private void lowerInnerAccess(List<Stmt> body) {
        if (!localClass()) {
            body.removeIf(s -> s instanceof ExprStmt e && e.e instanceof Assign a && a.lhs instanceof FieldRef f
                    && f.target instanceof Local l && l.v.find().isThis && (f.name.startsWith("this$") || f.name.startsWith("val$")));
        }
        Ast.rewriteAll(body, x -> switch (x) {
            case FieldRef f when f.target != null && f.name.startsWith("this$") && !localClass() -> new OuterThis(f.desc);
            case Invoke m when m.kind == Kind.STATIC && m.name.startsWith("access$") -> accessor(m);
            case NewObj n -> { fixNew(n); yield n; }
            default -> x;
        });
    }

    private boolean localClass() {
        InnerClassInfo i = innerInfo(cm, name);
        return i != null && i.outerClass().isEmpty() && i.innerName().isPresent();
    }

    private Expr lookupCapture(String field) {
        for (ClassPrinter p = this; p != null; p = p.parent) {
            Expr e = p.captured.get(field);
            if (e != null) return e;
        }
        return null;
    }

    /** Foo.this */
    static final class OuterThis extends Expr {
        final String desc;
        OuterThis(String d) { desc = d; }
        @Override public String type() { return desc; }
        @Override public void print(Out o) { o.add(o.cls(Types.internal(desc))).add(".this"); }
    }

    private Expr accessor(Invoke m) {
        ClassModel c = ws.model(m.owner);
        if (c == null) return m;
        for (MethodModel mm : c.methods()) {
            if (!mm.methodName().stringValue().equals(m.name) || !mm.methodType().stringValue().equals(m.desc) || mm.code().isEmpty()) continue;
            try {
                ClassPrinter p = new ClassPrinter(ws, c, im, null, new Stats());
                Body b = p.decompile(mm, Set.of());
                Expr e = null;
                if (b.stmts.size() == 1 && b.stmts.get(0) instanceof Return r) e = r.e;
                else if (b.stmts.size() >= 1 && b.stmts.get(0) instanceof ExprStmt s) e = s.e;
                if (e == null) return m;
                Map<Var, Expr> sub = new HashMap<>();
                for (int i = 0; i < b.params.size() && i < m.args.length; i++) sub.put(b.params.get(i).find(), m.args[i]);
                return e.rewrite(x -> x instanceof Local l && sub.containsKey(l.v.find()) ? sub.get(l.v.find()) : x);
            } catch (RuntimeException ex) {
                return m;
            }
        }
        return m;
    }

    private final Map<String, AnonInfo> anonCache = new HashMap<>();

    private AnonInfo anonInfo(String cls, String desc) {
        return anonCache.computeIfAbsent(cls + desc, k -> {
            ClassModel c = ws.model(cls);
            for (MethodModel m : c.methods()) {
                if (!m.methodName().stringValue().equals("<init>") || !m.methodType().stringValue().equals(desc)) continue;
                return scanCtor(c, m);
            }
            return null;
        });
    }

    /** Which constructor parameters of an anonymous class are real, and which are captures? */
    private AnonInfo scanCtor(ClassModel c, MethodModel m) {
        CodeModel code = m.code().orElseThrow();
        List<Instruction> list = new ArrayList<>();
        for (CodeElement e : code) if (e instanceof Instruction i) list.add(i);
        List<String> ps = Types.params(m.methodType().stringValue());
        int[] slotOf = new int[ps.size()];
        int s = 1;
        Map<Integer, Integer> paramAt = new HashMap<>();
        for (int i = 0; i < ps.size(); i++) {
            slotOf[i] = s;
            paramAt.put(s, i);
            s += Types.size(ps.get(i));
        }
        Map<Integer, String> caps = new HashMap<>();
        Set<Integer> visible = new TreeSet<>();
        for (int i = 0; i < list.size(); i++) {
            Instruction x = list.get(i);
            if (x instanceof LoadInstruction l && paramAt.containsKey(l.slot())) {
                Instruction nx = i + 1 < list.size() ? list.get(i + 1) : null;
                int p = paramAt.get(l.slot());
                boolean nullCheck = nx instanceof StackInstruction || nx instanceof InvokeInstruction iv && (iv.name().stringValue().equals("requireNonNull") || iv.name().stringValue().equals("getClass"));
                if (nx instanceof FieldInstruction f && f.opcode() == Opcode.PUTFIELD) caps.put(p, f.name().stringValue());
                else if (!nullCheck) visible.add(p);
            }
            if (x instanceof InvokeInstruction iv && iv.name().stringValue().equals("<init>")) break;
        }
        visible.removeAll(caps.keySet());
        return new AnonInfo(visible, caps);
    }

    private void fixNew(NewObj n) {
        ClassModel c = ws.model(n.cls);
        if (c == null) return;
        n.diamond = c.findAttribute(Attributes.signature()).map(s -> s.signature().stringValue().startsWith("<")).orElse(false);
        if (isAnonymous(n.cls) && n.anonBody == null) {
            AnonInfo ai = anonInfo(n.cls, n.desc);
            if (ai == null) return;
            List<Expr> vis = new ArrayList<>();
            Map<String, Expr> caps = new HashMap<>();
            for (int i = 0; i < n.args.length; i++) {
                if (ai.visible.contains(i)) vis.add(n.args[i]);
                else if (ai.captures.containsKey(i)) caps.put(ai.captures.get(i), n.args[i]);
            }
            boolean overInterface = !c.interfaces().isEmpty();
            n.args = overInterface ? new Expr[0] : vis.toArray(new Expr[0]);
            n.anonBody = new Object[]{c, caps};
        } else if (isInnerMember(c, n.cls) && n.args.length > 0 && n.outer == null) {
            Expr out = n.args[0];
            n.args = Arrays.copyOfRange(n.args, 1, n.args.length);
            boolean plain = out instanceof Local l && l.v.find().isThis || out instanceof OuterThis;
            if (plain) {
                if (out instanceof OuterThis) n.outer = null;
            } else n.outer = out;
            n.hiddenOuter = true;
        }
    }

    @SuppressWarnings("unchecked")
    private void printAnon(NewObj n, Out o) {
        Object[] data = (Object[]) n.anonBody;
        ClassModel c = (ClassModel) data[0];
        ClassPrinter p = new ClassPrinter(ws, c, im, this, stats);
        p.captured.putAll((Map<String, Expr>) data[1]);
        p.outerNames = enclosingNames;
        String base;
        var sig = c.findAttribute(Attributes.signature());
        if (sig.isPresent()) {
            Sig s = new Sig(sig.get().signature().stringValue(), im);
            s.formals();
            base = s.type();
            if (s.more()) base = s.type();
        } else if (!c.interfaces().isEmpty()) base = im.name(c.interfaces().get(0).asInternalName());
        else base = im.name(c.superclass().map(ClassEntry::asInternalName).orElse("java/lang/Object"));
        o.add("new ").add(base);
        Ast.printArgs(o, n.args);
        o.add(" {");
        o.ind++;
        p.members(o, true, null);
        o.ind--;
        o.line().add("}");
    }

    Set<String> enclosingNames = Set.of();

    // ---------------------------------------------------------------- lambdas

    private void resolveLambdas(List<Stmt> body, Set<String> names) {
        Ast.visit(body, s -> s.exprs(top -> {
            top.walk(x -> {
                if (x instanceof Lambda l && l.render == null) l.render = o -> { renderLambda(l, o, names); return null; };
            });
            return top;
        }));
    }

    private void renderLambda(Lambda l, Out o, Set<String> names) {
        MethodModel impl = null;
        if (l.owner.equals(name) && l.refKind != 8) {
            for (MethodModel m : cm.methods()) {
                if (m.methodName().stringValue().equals(l.name) && m.methodType().stringValue().equals(l.desc) && m.flags().has(AccessFlag.SYNTHETIC)) impl = m;
            }
        }
        if (impl == null) { methodRef(l, o); return; }
        Body b;
        try {
            b = decompile(impl, names);
        } catch (RuntimeException e) {
            o.add("/* lambda " + l.name + " could not be decompiled: " + sanitize(e) + " */ null");
            stats.failed++;
            stats.failures.add(name + "." + l.name + ": " + e);
            return;
        }
        boolean instance = b.thisVar != null;
        Expr[] cap = instance && l.captured.length > 0 ? Arrays.copyOfRange(l.captured, 1, l.captured.length) : l.captured;
        Map<Var, Expr> sub = new HashMap<>();
        for (int i = 0; i < cap.length && i < b.params.size(); i++) sub.put(b.params.get(i).find(), cap[i]);
        Ast.rewriteAll(b.stmts, x -> x instanceof Local v && sub.containsKey(v.v.find()) ? sub.get(v.v.find()) : x);
        Set<String> inner = new HashSet<>(names);
        inner.addAll(b.finish.usedNames());
        resolveLambdas(b.stmts, inner);
        List<Var> own = b.params.subList(Math.min(cap.length, b.params.size()), b.params.size());
        if (own.size() == 1) new Local(own.get(0)).print(o);
        else {
            o.add("(");
            for (int i = 0; i < own.size(); i++) {
                if (i > 0) o.add(", ");
                new Local(own.get(i)).print(o);
            }
            o.add(")");
        }
        o.add(" -> ");
        if (b.stmts.size() == 1 && b.stmts.get(0) instanceof Return r && r.e != null) r.e.printAt(o, 1);
        else if (b.stmts.size() == 1 && b.stmts.get(0) instanceof ExprStmt e && !(e.e instanceof Ast.Monitor)) e.e.printAt(o, 1);
        else {
            Ast.printBlock(o, stripTailReturn(b.stmts));
        }
    }

    private static List<Stmt> stripTailReturn(List<Stmt> l) {
        if (!l.isEmpty() && l.get(l.size() - 1) instanceof Return r && r.e == null) l.remove(l.size() - 1);
        return l;
    }

    private void methodRef(Lambda l, Out o) {
        String owner = l.owner.startsWith("[") ? o.ty(l.owner) : o.cls(l.owner);
        switch (l.refKind) {
            case 8 -> o.add(owner).add("::new");
            case 5, 7, 9 -> {
                if (l.captured.length >= 1) l.captured[0].printAt(o, 15);
                else o.add(owner);
                o.add("::").add(l.name);
            }
            default -> o.add(owner).add("::").add(l.name);
        }
    }

    // ---------------------------------------------------------------- whole class

    String printTop() {
        Out o = newOut();
        print(o, null);
        StringBuilder b = new StringBuilder();
        int slash = name.lastIndexOf('/');
        if (slash > 0) b.append("package ").append(name.substring(0, slash).replace('/', '.')).append(";\n\n");
        for (String i : im.imports) b.append("import ").append(i).append(";\n");
        if (!im.imports.isEmpty()) b.append('\n');
        b.append(o.sb).append('\n');
        return b.toString();
    }

    private String simpleName() {
        InnerClassInfo i = innerInfo(cm, name);
        if (i != null && i.innerName().isPresent()) return i.innerName().get().stringValue();
        String n = name.substring(name.lastIndexOf('/') + 1);
        int d = n.lastIndexOf('$');
        return d > 0 && d < n.length() - 1 ? n.substring(d + 1) : n;
    }

    /** Unlike AccessFlags.flags(), tolerates bits that are invalid for the location. */
    private static Set<AccessFlag> safeFlags(int mask, AccessFlag.Location loc) {
        Set<AccessFlag> out = EnumSet.noneOf(AccessFlag.class);
        for (AccessFlag f : AccessFlag.values()) if (f.locations().contains(loc) && (mask & f.mask()) != 0) out.add(f);
        return out;
    }

    private String modifiers(Set<AccessFlag> f, boolean cls) {
        StringBuilder b = new StringBuilder();
        if (f.contains(AccessFlag.PUBLIC)) b.append("public ");
        if (f.contains(AccessFlag.PROTECTED)) b.append("protected ");
        if (f.contains(AccessFlag.PRIVATE)) b.append("private ");
        if (f.contains(AccessFlag.ABSTRACT)) b.append("abstract ");
        if (f.contains(AccessFlag.STATIC)) b.append("static ");
        if (f.contains(AccessFlag.FINAL)) b.append("final ");
        return b.toString();
    }

    void print(Out o, Set<AccessFlag> forced) {
        stats.classes++;
        InnerClassInfo ii = innerInfo(cm, name);
        Set<AccessFlag> flags = EnumSet.noneOf(AccessFlag.class);
        flags.addAll(forced != null ? forced : ii != null ? safeFlags(ii.flagsMask(), AccessFlag.Location.INNER_CLASS) : safeFlags(cm.flags().flagsMask(), AccessFlag.Location.CLASS));
        boolean sealed = !isEnum && cm.findAttribute(Attributes.permittedSubclasses()).isPresent();
        if (isInterface || isEnum || isRecord) { flags.remove(AccessFlag.ABSTRACT); flags.remove(AccessFlag.FINAL); }
        if (isInterface || isEnum || isRecord) flags.remove(AccessFlag.STATIC);
        annotations(cm, o, true);
        o.add(modifiers(flags, true));
        if (sealed) o.add("sealed ");
        o.add(isAnnotation ? "@interface " : isInterface ? "interface " : isEnum ? "enum " : isRecord ? "record " : "class ").add(simpleName());

        var sig = cm.findAttribute(Attributes.signature());
        String superText = null;
        List<String> ifaces = new ArrayList<>();
        if (sig.isPresent()) {
            Sig s = new Sig(sig.get().signature().stringValue(), im);
            o.add(s.formals());
            superText = s.type();
            while (s.more()) ifaces.add(s.type());
        } else {
            String sc = cm.superclass().map(ClassEntry::asInternalName).orElse(null);
            if (sc != null) superText = im.name(sc);
            for (ClassEntry e : cm.interfaces()) ifaces.add(im.name(e.asInternalName()));
        }
        if (isRecord) printRecordHeader(o);
        boolean showSuper = !isEnum && !isRecord && !isInterface && superText != null && !superText.equals("Object");
        if (showSuper) o.add(" extends ").add(superText);
        if (isAnnotation) ifaces.clear();
        if (!ifaces.isEmpty()) o.add(isInterface ? " extends " : " implements ").add(String.join(", ", ifaces));
        if (sealed) {
            List<String> ps = new ArrayList<>();
            for (ClassEntry e : cm.findAttribute(Attributes.permittedSubclasses()).get().permittedSubclasses()) ps.add(im.name(e.asInternalName()));
            o.add(" permits ").add(String.join(", ", ps));
        }
        o.add(" {");
        o.ind++;
        members(o, false, flags);
        o.ind--;
        o.line().add("}");
    }

    private void printRecordHeader(Out o) {
        List<String> cs = new ArrayList<>();
        for (RecordComponentInfo r : cm.findAttribute(Attributes.record()).get().components()) {
            String t = r.findAttribute(Attributes.signature()).map(s -> Sig.field(s.signature().stringValue(), im))
                    .orElse(Types.src(r.descriptor().stringValue(), im));
            cs.add(t + " " + r.name().stringValue());
        }
        o.add("(").add(String.join(", ", cs)).add(")");
    }

    private static boolean hasMethod(ClassModel c, String n) {
        return c.methods().stream().anyMatch(m -> m.methodName().stringValue().equals(n));
    }

    /** Writes everything between the braces of a class. */
    void members(Out o, boolean anon, Set<AccessFlag> flags) {
        stats.printed.add(name);
        Map<String, Expr> staticInit = new LinkedHashMap<>();
        MethodModel clinit = null;
        for (MethodModel m : cm.methods()) if (m.methodName().stringValue().equals("<clinit>")) clinit = m;
        Body clinitBody = null;
        String clinitError = null;
        if (clinit != null) {
            try {
                clinitBody = decompile(clinit, Set.of());
                resolveLambdas(clinitBody.stmts, clinitBody.finish.usedNames());
            } catch (RuntimeException | StackOverflowError e) {
                if (System.getProperty("regrind.debug") != null) e.printStackTrace();
                clinitError = sanitize(e);
            }
        }
        if (isEnum) enumConstants(o, clinitBody);
        if (clinitBody != null) hoistStatics(clinitBody.stmts, staticInit);

        for (FieldModel f : cm.fields()) printField(o, f, staticInit);
        if (clinit != null) {
            stats.methods++;
            if (clinitBody != null) {
                clinitBody.stmts.removeIf(ClassPrinter::isAssertInit);
                stripTailReturn(clinitBody.stmts);
                if (!clinitBody.stmts.isEmpty()) {
                    o.line().line().add("static ");
                    Ast.printBlock(o, clinitBody.stmts);
                }
            } else failed(o, clinit, clinitError);
        }
        for (MethodModel m : cm.methods()) {
            String n = m.methodName().stringValue();
            if (n.equals("<clinit>") || m.flags().has(AccessFlag.SYNTHETIC) || m.flags().has(AccessFlag.BRIDGE)) continue;
            if (isEnum && skipEnumMethod(m)) continue;
            if (isRecord && skipRecordMethod(m)) continue;
            stats.methods++;
            printMethod(o, m, anon);
        }
        nested(o);
    }

    private static boolean isAssertInit(Stmt s) {
        return s instanceof ExprStmt e && e.e instanceof Assign a && a.lhs instanceof FieldRef f && f.name.equals("$assertionsDisabled");
    }

    private void nested(Out o) {
        var a = cm.findAttribute(Attributes.innerClasses());
        if (a.isEmpty()) return;
        for (InnerClassInfo i : a.get().classes()) {
            String n = i.innerClass().asInternalName();
            if (n.equals(name) || !ws.classes.containsKey(n)) continue;
            ClassModel c = ws.model(n);
            if (c == null) continue;
            boolean member = i.outerClass().isPresent() && i.outerClass().get().asInternalName().equals(name);
            boolean local = i.outerClass().isEmpty() && i.innerName().isPresent()
                    && c.findAttribute(Attributes.enclosingMethod()).map(e -> e.enclosingClass().asInternalName().equals(name)).orElse(false);
            if (!member && !local || isSwitchMapClass(c)) continue;
            ClassPrinter p = new ClassPrinter(ws, c, im, this, stats);
            o.line().line();
            Set<AccessFlag> forced = null;
            if (local) {
                // captured values stay visible as constructor arguments, so it can live as a plain nested class
                forced = EnumSet.noneOf(AccessFlag.class);
                forced.addAll(safeFlags(i.flagsMask(), AccessFlag.Location.INNER_CLASS));
                if (enclosedInStatic(c)) forced.add(AccessFlag.STATIC);
            }
            p.print(p.reuse(o), forced);
        }
    }

    /** javac stores captures before super(); only Java 25 source can say that, so put the call first. */
    private static void superFirst(List<Stmt> l) {
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i) instanceof ExprStmt e && e.e instanceof Invoke m && m.name.equals("<init>")) {
                if (i > 0) l.add(0, l.remove(i));
                return;
            }
            if (!(l.get(i) instanceof ExprStmt e && e.e instanceof Assign a && a.lhs instanceof FieldRef f && f.name.contains("$"))) return;
        }
    }

    private boolean enclosedInStatic(ClassModel c) {
        var em = c.findAttribute(Attributes.enclosingMethod());
        if (em.isEmpty() || em.get().enclosingMethodName().isEmpty()) return true;
        String n = em.get().enclosingMethodName().get().stringValue();
        for (MethodModel m : cm.methods()) if (m.methodName().stringValue().equals(n) && m.flags().has(AccessFlag.STATIC)) return true;
        return false;
    }

    private Out reuse(Out o) {
        o.thisClass = name;
        o.anon = this::printAnon;
        return o;
    }

    private void hoistStatics(List<Stmt> stmts, Map<String, Expr> out) {
        List<String> order = new ArrayList<>();
        for (FieldModel f : cm.fields()) order.add(f.fieldName().stringValue());
        int last = -1;
        Iterator<Stmt> it = stmts.iterator();
        while (it.hasNext()) {
            Stmt s = it.next();
            if (isAssertInit(s)) {
                it.remove();
                continue;
            }
            if (!(s instanceof ExprStmt e && e.e instanceof Assign a && a.op == null && a.lhs instanceof FieldRef f
                    && f.target == null && f.owner.equals(name))) break;
            int pos = order.indexOf(f.name);
            if (pos <= last) break;
            last = pos;
            out.put(f.name, a.rhs);
            it.remove();
        }
    }

    private void printField(Out o, FieldModel f, Map<String, Expr> staticInit) {
        String n = f.fieldName().stringValue();
        if (f.flags().has(AccessFlag.ENUM)) return;
        boolean localCls = localClass();
        if (f.flags().has(AccessFlag.SYNTHETIC) && !(localCls && (n.startsWith("val$") || n.startsWith("this$")))) return;
        if (isRecord && !f.flags().has(AccessFlag.STATIC)) return;
        o.line();
        annotations(f, o, false);
        Set<AccessFlag> fl = EnumSet.noneOf(AccessFlag.class);
        fl.addAll(safeFlags(f.flags().flagsMask(), AccessFlag.Location.FIELD));
        if (isInterface) { fl.remove(AccessFlag.PUBLIC); fl.remove(AccessFlag.STATIC); fl.remove(AccessFlag.FINAL); }
        o.add(modifiers(fl, false));
        if (fl.contains(AccessFlag.VOLATILE)) o.add("volatile ");
        if (fl.contains(AccessFlag.TRANSIENT)) o.add("transient ");
        String d = f.fieldType().stringValue();
        String t = f.findAttribute(Attributes.signature()).map(s -> Sig.field(s.signature().stringValue(), im)).orElse(Types.src(d, im));
        o.add(t).add(" ").add(n);
        var cv = f.findAttribute(Attributes.constantValue());
        Expr init = staticInit.get(n);
        if (cv.isPresent()) init = constantValue(cv.get().constant().constantValue(), d);
        if (init != null) {
            o.add(" = ");
            init.printAt(o, 1);
        }
        o.add(";");
    }

    private static Expr constantValue(Object v, String d) {
        return switch (v) {
            case Integer i -> switch (d) {
                case "Z" -> Const.bool(i != 0);
                case "C" -> new Const(i, "C");
                default -> new Const(i, d);
            };
            case Long l -> new Const(l, "J");
            case Float f -> new Const(f, "F");
            case Double x -> new Const(x, "D");
            case String s -> new Const(s, Types.STR);
            default -> Const.nul();
        };
    }

    private void enumConstants(Out o, Body clinit) {
        if (clinit == null) return;
        List<Stmt> l = clinit.stmts;
        List<Runnable> items = new ArrayList<>();
        Iterator<Stmt> it = l.iterator();
        while (it.hasNext()) {
            Stmt s = it.next();
            if (s instanceof ExprStmt e && e.e instanceof Assign a && a.lhs instanceof FieldRef f && f.owner.equals(name)) {
                boolean constant = cm.fields().stream().anyMatch(x -> x.fieldName().stringValue().equals(f.name) && x.flags().has(AccessFlag.ENUM));
                if (constant && a.rhs instanceof NewObj n) {
                    final String cname = f.name;
                    items.add(() -> {
                        o.add(cname);
                        Expr[] rest = n.args.length >= 2 ? Arrays.copyOfRange(n.args, 2, n.args.length) : new Expr[0];
                        if (rest.length > 0) Ast.printArgs(o, rest);
                        if (!n.cls.equals(name) && ws.model(n.cls) != null) {
                            ClassPrinter p = new ClassPrinter(ws, ws.model(n.cls), im, this, stats);
                            o.add(" {");
                            o.ind++;
                            p.members(o, true, null);
                            o.ind--;
                            o.line().add("}");
                        }
                    });
                    it.remove();
                    continue;
                }
                if (f.name.equals("$VALUES")) {
                    it.remove();
                    continue;
                }
            }
            break;
        }
        for (int i = 0; i < items.size(); i++) {
            o.line();
            items.get(i).run();
            o.add(i + 1 < items.size() ? "," : ";");
        }
        if (items.isEmpty()) o.line().add(";");
    }

    private boolean skipEnumMethod(MethodModel m) {
        String n = m.methodName().stringValue(), d = m.methodType().stringValue();
        return n.equals("values") && d.startsWith("()") || n.equals("valueOf") && d.startsWith("(Ljava/lang/String;)");
    }

    private boolean skipRecordMethod(MethodModel m) {
        String n = m.methodName().stringValue(), d = m.methodType().stringValue();
        var comps = cm.findAttribute(Attributes.record()).get().components();
        if (m.code().isPresent() && (n.equals("toString") || n.equals("hashCode") || n.equals("equals"))) {
            for (CodeElement e : m.code().get()) {
                if (e instanceof InvokeDynamicInstruction i && i.bootstrapMethod().owner().descriptorString().equals("Ljava/lang/runtime/ObjectMethods;")) return true;
            }
        }
        if (d.startsWith("()")) {
            for (RecordComponentInfo c : comps) {
                if (c.name().stringValue().equals(n) && m.code().isPresent()) {
                    int count = 0;
                    boolean get = false;
                    for (CodeElement e : m.code().get()) {
                        if (e instanceof Instruction i) {
                            count++;
                            if (i instanceof FieldInstruction f && f.name().stringValue().equals(n)) get = true;
                        }
                    }
                    return get && count == 3;
                }
            }
        }
        if (n.equals("<init>")) {
            StringBuilder want = new StringBuilder("(");
            for (RecordComponentInfo c : comps) want.append(c.descriptor().stringValue());
            want.append(")V");
            if (d.contentEquals(want)) {
                int put = 0, other = 0;
                for (CodeElement e : m.code().get()) {
                    if (e instanceof FieldInstruction f && f.opcode() == Opcode.PUTFIELD) put++;
                    else if (e instanceof InvokeInstruction) other++;
                    else if (e instanceof BranchInstruction) other += 2;
                }
                return put == comps.size() && other == 1;
            }
        }
        return false;
    }

    private static String sanitize(Throwable e) {
        String s = String.valueOf(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        return s.replace("*/", "* /").replace('\n', ' ');
    }

    private void failed(Out o, MethodModel m, String why) {
        stats.failed++;
        stats.failures.add(name + "." + m.methodName().stringValue() + m.methodType().stringValue() + ": " + why);
        o.line().line().add("// Regrind could not decompile " + m.methodName().stringValue() + m.methodType().stringValue() + ": " + why);
        o.line().add("/*");
        String listing;
        try {
            listing = Disassembler.method(m).replace("*/", "* /");
        } catch (RuntimeException e) {
            listing = "unreadable";
        }
        for (String line : listing.split("\n")) o.line().add(line);
        o.line().add("*/");
    }

    private void printMethod(Out o, MethodModel m, boolean anon) {
        String n = m.methodName().stringValue(), d = m.methodType().stringValue();
        boolean ctor = n.equals("<init>");
        boolean hasCode = m.code().isPresent();
        Body body = null;
        String error = null;
        if (hasCode) {
            try {
                body = decompile(m, Set.of());
                resolveLambdas(body.stmts, body.finish.usedNames());
            } catch (RuntimeException | StackOverflowError e) {
                if (System.getProperty("regrind.debug") != null) e.printStackTrace();
                error = sanitize(e);
            }
        }
        List<String> pdesc = Types.params(d);
        int hide = 0;
        if (ctor && isEnum) hide = 2;
        else if (ctor && isInnerMember(cm, name)) hide = 1;

        if (ctor && body != null) {
            int total = pdesc.size();
            hiddenParamUses(m, body, anon);
            cleanCtor(body, anon);
            if (anon) {
                // whatever remains is an instance initialiser
                stripTailReturn(body.stmts);
                if (!body.stmts.isEmpty()) {
                    o.line().line();
                    Ast.printBlock(o, body.stmts);
                }
                return;
            }
            stripTailReturn(body.stmts);
            boolean onlyCtor = cm.methods().stream().filter(x -> x.methodName().stringValue().equals("<init>")).count() == 1;
            if (body.stmts.isEmpty() && total - hide == 0 && onlyCtor && !m.flags().has(AccessFlag.PRIVATE)
                    && m.flags().has(AccessFlag.PUBLIC) == cm.flags().has(AccessFlag.PUBLIC)
                    && m.findAttribute(Attributes.runtimeVisibleAnnotations()).isEmpty()) return;
            if (body.stmts.isEmpty() && total - hide == 0 && onlyCtor && isEnum) return;
        }
        if (ctor && anon) return;
        if (error != null) failed(o, m, error);

        o.line().line();
        annotations(m, o, false);
        Set<AccessFlag> fl = EnumSet.noneOf(AccessFlag.class);
        fl.addAll(safeFlags(m.flags().flagsMask(), AccessFlag.Location.METHOD));
        fl.remove(AccessFlag.VARARGS);
        fl.remove(AccessFlag.TRANSIENT);
        fl.remove(AccessFlag.VOLATILE);
        boolean isDefault = isInterface && hasCode && !fl.contains(AccessFlag.STATIC) && !fl.contains(AccessFlag.PRIVATE);
        if (isInterface) { fl.remove(AccessFlag.PUBLIC); fl.remove(AccessFlag.ABSTRACT); }
        if (ctor && isEnum) fl.remove(AccessFlag.PRIVATE);
        StringBuilder mods = new StringBuilder(modifiers(fl, false));
        if (isDefault) mods.append("default ");
        if (fl.contains(AccessFlag.SYNCHRONIZED)) mods.append("synchronized ");
        if (fl.contains(AccessFlag.NATIVE)) mods.append("native ");
        if (fl.contains(AccessFlag.STRICT)) mods.append("strictfp ");
        o.add(mods.toString());

        Sig.Method sm = null;
        var sig = m.findAttribute(Attributes.signature());
        if (sig.isPresent()) sm = Sig.method(sig.get().signature().stringValue(), im);
        if (sm != null && !sm.formals().isEmpty()) o.add(sm.formals()).add(" ");
        if (ctor) o.add(simpleName());
        else o.add(sm != null ? sm.ret() : Types.src(Types.ret(d), im)).add(" ").add(n);

        boolean varargs = m.flags().has(AccessFlag.VARARGS);
        o.add("(");
        List<String> types = new ArrayList<>();
        for (int i = hide; i < pdesc.size(); i++) {
            String ty = Types.src(pdesc.get(i), im);
            if (sm != null) {
                int fromEnd = pdesc.size() - i;
                if (fromEnd <= sm.params().size()) ty = sm.params().get(sm.params().size() - fromEnd);
            }
            types.add(ty);
        }
        for (int i = 0; i < types.size(); i++) {
            if (i > 0) o.add(", ");
            String ty = types.get(i);
            if (varargs && i == types.size() - 1 && ty.endsWith("[]")) ty = ty.substring(0, ty.length() - 2) + "...";
            o.add(ty).add(" ");
            if (body != null) new Local(body.params.get(i + hide)).print(o);
            else o.add("arg" + i);
        }
        o.add(")");
        var defVal = m.findAttribute(Attributes.annotationDefault());
        if (defVal.isPresent()) o.add(" default ").add(annotationValue(defVal.get().defaultValue()));
        List<String> thrown = new ArrayList<>();
        if (sm != null && !sm.thrown().isEmpty()) thrown.addAll(sm.thrown());
        else m.findAttribute(Attributes.exceptions()).ifPresent(e -> { for (ClassEntry c : e.exceptions()) thrown.add(im.name(c.asInternalName())); });
        if (!thrown.isEmpty()) o.add(" throws ").add(String.join(", ", thrown));
        if (!hasCode) { o.add(";"); return; }
        o.add(" ");
        if (body == null) {
            // keeps the class compilable, and makes a lost body loud rather than silent
            o.add("{");
            o.ind++;
            o.line().add("throw new UnsupportedOperationException(\"Regrind could not decompile this method\");");
            o.ind--;
            o.line().add("}");
            return;
        }
        Ast.printBlock(o, stripTailReturn(body.stmts));
    }

    /** Constructor code may read its hidden parameters directly rather than through the synthetic fields. */
    private void hiddenParamUses(MethodModel m, Body b, boolean anon) {
        Map<Var, Expr> sub = new HashMap<>();
        if (anon) {
            AnonInfo ai = scanCtor(cm, m);
            for (var e : ai.captures.entrySet()) {
                Var p = b.params.get(e.getKey()).find();
                if (e.getValue().startsWith("this$")) sub.put(p, new OuterThis(p.desc));
                else if (lookupCapture(e.getValue()) != null) sub.put(p, lookupCapture(e.getValue()));
            }
        } else if (isInnerMember(cm, name) && !b.params.isEmpty()) {
            sub.put(b.params.get(0).find(), new OuterThis(b.params.get(0).find().desc));
        }
        if (sub.isEmpty()) return;
        // the hidden-parameter assignments themselves are gone already, so only reads remain
        for (Stmt s : b.stmts) {
            if (s instanceof ExprStmt e && e.e instanceof Invoke i && i.name.equals("<init>")) {
                // an explicit this(...) or super(...) keeps its raw arguments: cleanCtor trims them
                continue;
            }
            Ast.visit(List.of(s), x -> x.exprs(top -> top.rewrite(q -> q instanceof Local v && sub.containsKey(v.v.find()) ? sub.get(v.v.find()) : q)));
        }
    }

    private void cleanCtor(Body b, boolean anon) {
        if (localClass()) superFirst(b.stmts);
        // the implicit super() and enum super(name, ordinal)
        Iterator<Stmt> it = b.stmts.iterator();
        while (it.hasNext()) {
            Stmt s = it.next();
            if (s instanceof ExprStmt e) {
                if (e.e instanceof Assign a && a.lhs instanceof FieldRef f && !localClass() && (f.name.startsWith("this$") || f.name.startsWith("val$"))) {
                    it.remove();
                    continue;
                }
                if (e.e instanceof Invoke c && !b.params.isEmpty() && (c.is("java/util/Objects", "requireNonNull") && c.args.length == 1 && c.args[0] instanceof Local q && q.v.find() == b.params.get(0).find()
                        || c.name.equals("getClass") && c.target instanceof Local q2 && q2.v.find() == b.params.get(0).find())) {
                    it.remove();
                    continue;
                }
                if (e.e instanceof Invoke m && m.name.equals("<init>") && m.kind == Kind.SUPER) {
                    if (m.owner.equals(name)) {
                        if (isEnum) m.args = Arrays.copyOfRange(m.args, Math.min(2, m.args.length), m.args.length);
                        else if (isInnerMember(cm, name) && m.args.length > 0) m.args = Arrays.copyOfRange(m.args, 1, m.args.length);
                    } else if (anon || isEnum || m.args.length == 0 || m.owner.equals("java/lang/Record")) it.remove();
                    return;
                }
            }
            if (!(s instanceof ExprStmt)) return;
        }
    }

    // ---------------------------------------------------------------- annotations

    private void annotations(AttributedElement e, Out o, boolean own) {
        List<java.lang.classfile.Annotation> all = new ArrayList<>();
        e.findAttribute(Attributes.runtimeVisibleAnnotations()).ifPresent(a -> all.addAll(a.annotations()));
        e.findAttribute(Attributes.runtimeInvisibleAnnotations()).ifPresent(a -> all.addAll(a.annotations()));
        for (var a : all) {
            o.add(annotation(a));
            o.line();
        }
    }

    private String annotation(java.lang.classfile.Annotation a) {
        StringBuilder b = new StringBuilder("@").append(Types.src(a.className().stringValue(), im));
        if (!a.elements().isEmpty()) {
            b.append("(");
            List<String> parts = new ArrayList<>();
            for (AnnotationElement x : a.elements()) {
                String n = x.name().stringValue();
                String v = annotationValue(x.value());
                parts.add(a.elements().size() == 1 && n.equals("value") ? v : n + " = " + v);
            }
            b.append(String.join(", ", parts)).append(")");
        }
        return b.toString();
    }

    private String annotationValue(AnnotationValue v) {
        return switch (v) {
            case AnnotationValue.OfString s -> Ast.quote(s.stringValue());
            case AnnotationValue.OfBoolean x -> String.valueOf(x.booleanValue());
            case AnnotationValue.OfChar x -> Ast.charLit(x.charValue());
            case AnnotationValue.OfLong x -> x.longValue() + "L";
            case AnnotationValue.OfFloat x -> x.floatValue() + "f";
            case AnnotationValue.OfConstant c -> String.valueOf(c.resolvedValue());
            case AnnotationValue.OfClass c -> Types.src(c.className().stringValue(), im) + ".class";
            case AnnotationValue.OfEnum e -> Types.src(e.className().stringValue(), im) + "." + e.constantName().stringValue();
            case AnnotationValue.OfAnnotation a -> annotation(a.annotation());
            case AnnotationValue.OfArray a -> {
                List<String> p = new ArrayList<>();
                for (AnnotationValue x : a.values()) p.add(annotationValue(x));
                yield "{" + String.join(", ", p) + "}";
            }
        };
    }
}
