package regrind.decomp;

import regrind.decomp.Ast.*;
import regrind.decomp.Cfg.*;
import java.lang.classfile.*;
import java.lang.classfile.constantpool.*;
import java.lang.classfile.instruction.*;
import java.lang.constant.*;
import java.util.*;
import java.util.function.UnaryOperator;

/** Abstract interpretation of each block's stack, producing statements and expression trees. */
final class Sim {
    /** Result of lcmp/fcmp/dcmp; normally consumed by the following conditional branch. */
    static final class Cmp extends Expr {
        Expr a, b;
        final char kind; // J, l, g
        final String ty;
        Cmp(Expr a, Expr b, char k, String ty) { this.a = a; this.b = b; kind = k; this.ty = ty; }
        @Override public String type() { return "I"; }
        @Override public void mapKids(UnaryOperator<Expr> f) { a = f.apply(a); b = f.apply(b); }
        @Override public void print(Out o) {
            o.add(switch (ty) { case "J" -> "Long"; case "F" -> "Float"; default -> "Double"; }).add(".compare(");
            a.print(o);
            o.add(", ");
            b.print(o);
            o.add(")");
        }
    }

    final Cfg cfg;
    final String thisClass;
    final ArrayList<Expr> stack = new ArrayList<>();
    Block cur;

    Sim(Cfg cfg, String thisClass) { this.cfg = cfg; this.thisClass = thisClass; }

    void run() {
        cfg.entry.entry = new ArrayList<>();
        Deque<Block> work = new ArrayDeque<>();
        Set<Block> seen = new HashSet<>();
        work.add(cfg.entry);
        seen.add(cfg.entry);
        for (Block b : cfg.blocks) {
            if (b.finallyEntry) b.entry = new ArrayList<>();
            else if (b.catchType != null) b.entry = new ArrayList<>(List.of(new Caught(b.catchType)));
        }
        while (!work.isEmpty()) {
            Block b = work.pollFirst();
            exec(b);
            for (Block s : b.succ) if (seen.add(s)) work.addFirst(s);
            for (Block h : b.handlers) if (seen.add(h)) work.addLast(h);
        }
        cfg.blocks.removeIf(b -> !seen.contains(b));
        for (Region r : cfg.regions) r.blocks.retainAll(seen);
    }

    private Expr pop() {
        if (stack.isEmpty()) throw new IllegalStateException("stack underflow");
        return stack.remove(stack.size() - 1);
    }
    private void push(Expr e) { stack.add(e); }
    private Expr peek() { return stack.get(stack.size() - 1); }

    private static boolean pure(Expr e) { return e instanceof Const || e instanceof Local || e instanceof Uninit || e instanceof Caught; }
    private static boolean trivial(Expr e) { return e instanceof Const || e instanceof Local || e instanceof Uninit; }

    private Var temp(Expr e, char kindHint) {
        Var v = new Var();
        v.stack = true;
        v.kind = kindHint;
        v.desc = Types.UNK;
        v.defs.add(e);
        return v;
    }

    private static char kind(Expr e) { return Cfg.kindOf(e.type()); }

    private void spill(int i) {
        Expr e = stack.get(i);
        Var v = temp(e, kind(e));
        cur.stmts.add(new ExprStmt(new Assign(new Local(v), e)));
        Local l = new Local(v);
        for (int k = i; k < stack.size(); k++) if (stack.get(k) == e) stack.set(k, l);
    }

    private void flush(int upTo) {
        for (int i = 0; i < upTo; i++) if (!pure(stack.get(i))) spill(i);
    }

    private void emit(Expr e) {
        flush(stack.size());
        cur.stmts.add(new ExprStmt(e));
    }

    /** Make a value safe to duplicate on the stack. */
    private void settle(int i) {
        if (trivial(stack.get(i))) return;
        flush(i);
        spill(i);
    }

    private boolean replaceIdentity(Expr old, Expr now) {
        boolean any = false;
        for (int i = 0; i < stack.size(); i++) if (stack.get(i) == old) { stack.set(i, now); any = true; }
        return any;
    }

    private void assign(Expr lhs, Expr val) {
        Assign a = new Assign(lhs, val);
        if (replaceIdentity(val, a)) return;
        if (lhs instanceof Local l) {
            for (int i = 0; i < stack.size(); i++) if (stack.get(i).mentions(l.v) ) spill(i);
        }
        emit(a);
    }

    private void exec(Block b) {
        cur = b;
        stack.clear();
        if (b.entry != null) stack.addAll(b.entry);
        Cfg.Insn[] ins = cfg.ins;
        boolean ended = false;
        for (int i = b.start; i < b.end; i++) {
            if (ins[i].dead) continue;
            ended = step(i, ins[i].i) || ended;
        }
        if (!ended) finishGoto(b);
    }

    private void finishGoto(Block b) {
        b.term = Term.GOTO;
        exitStack(b);
    }

    /** Hand the operand stack to the successors through shared variables. */
    private void exitStack(Block b) {
        if (stack.isEmpty()) {
            for (Block s : b.succ) if (s.entry == null) s.entry = new ArrayList<>();
            return;
        }
        List<Expr> have = null;
        for (Block s : b.succ) if (s.entry != null) { have = s.entry; break; }
        List<Expr> out = new ArrayList<>();
        IdentityHashMap<Expr, Expr> done = new IdentityHashMap<>();
        for (int k = 0; k < stack.size(); k++) {
            Expr e = stack.get(k);
            Expr prior = done.get(e);
            if (e instanceof Uninit) { out.add(have != null && k < have.size() ? have.get(k) : e); continue; }
            Var v;
            if (have != null && k < have.size() && have.get(k) instanceof Local l) {
                v = l.v;
                if (e instanceof Local p && p.v.find().stack) { Var.union(v, p.v); out.add(new Local(v)); continue; }
            } else if (e instanceof Local p && p.v.find().stack) {
                out.add(e);
                continue;
            } else {
                v = new Var();
                v.stack = true;
                v.kind = kind(e);
            }
            if (prior != null) {
                b.stmts.add(new ExprStmt(new Assign(new Local(v), prior)));
                v.defs.add(prior);
            } else if (e instanceof Local l && l.v.find() == v.find()) {
                // already in place
            } else {
                b.stmts.add(new ExprStmt(new Assign(new Local(v), e)));
                v.defs.add(e);
                done.put(e, new Local(v));
            }
            out.add(new Local(v));
        }
        for (Block s : b.succ) {
            if (s.entry == null) s.entry = new ArrayList<>(out);
            else for (int k = 0; k < Math.min(out.size(), s.entry.size()); k++) {
                if (out.get(k) instanceof Local x && s.entry.get(k) instanceof Local y) {
                    Var.union(x.v, y.v);
                    y.v = y.v.find();
                }
            }
        }
    }

    private static Expr intCmp(Expr e, String op) {
        if (e instanceof Cmp c) {
            if (c.kind == 'J') return new Binary(op, c.a, c.b, "Z");
            // NaN must fall the same way as the bytecode: wrap the opposite test in a negation when needed
            boolean nanJumps = switch (op) {
                case "<", "<=" -> c.kind == 'l';
                case ">", ">=" -> c.kind == 'g';
                default -> op.equals("!=");
            };
            if (op.equals("==") || op.equals("!=")) return new Binary(op, c.a, c.b, "Z");
            if (!nanJumps) return new Binary(op, c.a, c.b, "Z");
            String inv = switch (op) { case "<" -> ">="; case "<=" -> ">"; case ">" -> "<="; default -> "<"; };
            return new Unary("!", new Binary(inv, c.a, c.b, "Z"));
        }
        return new Binary(op, e, Const.i(0), "Z");
    }

    static Expr negate(Expr e) {
        if (e instanceof Unary u && u.op.equals("!")) return u.e;
        if (e instanceof Binary b) {
            if (b.op.equals("&&")) return new Binary("||", negate(b.l), negate(b.r), "Z");
            if (b.op.equals("||")) return new Binary("&&", negate(b.l), negate(b.r), "Z");
            if (Ast.isCompare(b.op)) {
                String t = b.l.type();
                boolean eq = b.op.equals("==") || b.op.equals("!=");
                if (eq || !(t.equals("F") || t.equals("D"))) {
                    String inv = switch (b.op) {
                        case "==" -> "!="; case "!=" -> "=="; case "<" -> ">="; case "<=" -> ">"; case ">" -> "<="; default -> "<";
                    };
                    return new Binary(inv, b.l, b.r, "Z");
                }
            }
        }
        if (e instanceof Const c && c.desc.equals("Z")) return Const.bool(c.isInt(0));
        return new Unary("!", e);
    }

    private static String desc(ClassEntry c) { return c.asSymbol().descriptorString(); }

    /** Executes one instruction; returns true if it ended the block. */
    private boolean step(int idx, Instruction ins) {
        switch (ins) {
            case ConstantInstruction c -> push(constant(c));
            case LoadInstruction l -> load(idx, l);
            case StoreInstruction s -> {
                Var v = cfg.storeVar[idx];
                Expr val = pop();
                v.defs.add(val);
                assign(new Local(v), val);
            }
            case IncrementInstruction n -> inc(idx, n);
            case OperatorInstruction o -> operator(o);
            case ConvertInstruction c -> push(new Cast(c.toType().upperBound().descriptorString(), pop()));
            case FieldInstruction f -> field(f);
            case InvokeInstruction m -> invoke(m);
            case InvokeDynamicInstruction d -> dynamic(d);
            case NewObjectInstruction n -> push(new Uninit(n.className().asInternalName()));
            case NewPrimitiveArrayInstruction n -> {
                Expr len = pop();
                push(new NewArray("[" + n.typeKind().upperBound().descriptorString(), new Expr[]{len}));
            }
            case NewReferenceArrayInstruction n -> {
                Expr len = pop();
                push(new NewArray("[" + desc(n.componentType()), new Expr[]{len}));
            }
            case NewMultiArrayInstruction n -> {
                Expr[] dims = new Expr[n.dimensions()];
                for (int i = dims.length - 1; i >= 0; i--) dims[i] = pop();
                push(new NewArray(desc(n.arrayType()), dims));
            }
            case ArrayLoadInstruction a -> {
                Expr i = pop(), arr = pop();
                ArrayGet g = new ArrayGet(arr, i);
                if (a.typeKind() != TypeKind.REFERENCE) g.hint = a.typeKind().upperBound().descriptorString();
                push(g);
            }
            case ArrayStoreInstruction a -> {
                Expr v = pop(), i = pop(), arr = pop();
                if (arr instanceof NewArray na && !stack.isEmpty() && peek() == arr && i instanceof Const c
                        && c.isInt(na.init == null ? 0 : na.init.size()) && na.dims.length == 1) {
                    if (na.init == null) na.init = new ArrayList<>();
                    na.init.add(v);
                } else assign(new ArrayGet(arr, i), v);
            }
            case StackInstruction s -> stackOp(s);
            case TypeCheckInstruction t -> {
                if (t.opcode() == Opcode.CHECKCAST) push(new Cast(desc(t.type()), pop()));
                else push(new InstanceOf(pop(), desc(t.type())));
            }
            case MonitorInstruction m -> emit(new Monitor(pop(), m.opcode() == Opcode.MONITORENTER));
            case NopInstruction n -> {}
            case BranchInstruction b -> { branch(idx, b); return true; }
            case TableSwitchInstruction t -> {
                switchOn(idx, t.defaultTarget(), t.cases());
                return true;
            }
            case LookupSwitchInstruction t -> {
                switchOn(idx, t.defaultTarget(), t.cases());
                return true;
            }
            case ReturnInstruction r -> {
                cur.term = Term.RETURN;
                cur.cond = r.typeKind() == TypeKind.VOID ? null : pop();
                flushAll();
                return true;
            }
            case ThrowInstruction t -> {
                if (cfg.ins[idx].endFinally) {
                    cur.term = Term.END_FINALLY;
                } else {
                    cur.term = Term.THROW;
                    cur.cond = pop();
                    flushAll();
                }
                return true;
            }
            default -> throw new UnsupportedOperationException("instruction " + ins.opcode());
        }
        return false;
    }

    private void flushAll() {
        for (Expr e : stack) {
            if (e instanceof Invoke || e instanceof Assign || e instanceof IncDec || e instanceof NewObj) cur.stmts.add(new ExprStmt(e));
        }
        stack.clear();
    }

    private Expr constant(ConstantInstruction c) {
        if (c.opcode() == Opcode.ACONST_NULL) return Const.nul();
        Object v;
        try {
            v = c.constantValue();
        } catch (RuntimeException e) {
            return new Dynamic("$condy", "()Ljava/lang/Object;", new Expr[0]);
        }
        return switch (v) {
            case Integer i -> Const.i(i);
            case Long l -> new Const(l, "J");
            case Float f -> new Const(f, "F");
            case Double d -> new Const(d, "D");
            case String s -> new Const(s, Types.STR);
            case ClassDesc d -> Const.cls(d.descriptorString());
            case null -> Const.nul();
            default -> new Dynamic("$constant", "()Ljava/lang/Object;", new Expr[0]);
        };
    }

    private void load(int idx, LoadInstruction l) {
        Var v = cfg.loadVar[idx];
        // ++i used as a value: the increment is the previous statement on the same line
        List<Stmt> st = cur.stmts;
        if (l.typeKind() == TypeKind.INT && !st.isEmpty() && st.get(st.size() - 1) instanceof ExprStmt es
                && es.e instanceof IncDec n && n.e instanceof Local x && x.v.find() == v.find()
                && idx > 0 && cfg.ins[idx - 1].i instanceof IncrementInstruction
                && cfg.ins[idx].line == cfg.ins[idx - 1].line && cfg.ins[idx].line != 0
                && !(idx + 1 < cfg.ins.length && cfg.ins[idx + 1].i instanceof StoreInstruction)
                && inExpression(idx)) {
            st.remove(st.size() - 1);
            n.post = false;
            push(n);
            return;
        }
        push(new Local(v));
    }

    /** A freshly loaded value is part of an expression that spans the increment if the line continues past it. */
    private boolean inExpression(int idx) {
        if (!stack.isEmpty()) return true;
        // while (++i < n), foo(++i), x = ++i: the statement started before this load, at the iinc
        Cfg.Insn prevLine = idx >= 2 ? cfg.ins[idx - 2] : null;
        return prevLine == null || prevLine.line != cfg.ins[idx].line || cfg.blockAt[idx - 1] == cur && cur.start == idx - 1;
    }

    private void inc(int idx, IncrementInstruction n) {
        Var v = cfg.storeVar[idx] != null ? cfg.storeVar[idx] : cfg.loadVar[idx];
        Expr e = new Local(v);
        v.defs.add(Const.i(2));
        int k = n.constant();
        if (!stack.isEmpty() && peek() instanceof Local l && l.v.find() == v.find() && Math.abs(k) == 1) {
            stack.set(stack.size() - 1, new IncDec(e, k > 0, true));
            return;
        }
        for (int i = 0; i < stack.size(); i++) if (stack.get(i).mentions(v)) spill(i);
        if (Math.abs(k) == 1) emit(new IncDec(e, k > 0, true));
        else emit(new Assign(e, k > 0 ? "+" : "-", Const.i(Math.abs(k))));
    }

    private void operator(OperatorInstruction o) {
        String t = o.typeKind().upperBound().descriptorString();
        String op;
        switch (o.opcode()) {
            case ARRAYLENGTH -> { push(new ArrayLen(pop())); return; }
            case INEG, LNEG, FNEG, DNEG -> { push(new Unary("-", pop())); return; }
            case LCMP -> { Expr b = pop(), a = pop(); push(new Cmp(a, b, 'J', "J")); return; }
            case FCMPL, DCMPL -> { Expr b = pop(), a = pop(); push(new Cmp(a, b, 'l', a.type().equals("D") || o.opcode() == Opcode.DCMPL ? "D" : "F")); return; }
            case FCMPG, DCMPG -> { Expr b = pop(), a = pop(); push(new Cmp(a, b, 'g', a.type().equals("D") || o.opcode() == Opcode.DCMPG ? "D" : "F")); return; }
            case IADD, LADD, FADD, DADD -> op = "+";
            case ISUB, LSUB, FSUB, DSUB -> op = "-";
            case IMUL, LMUL, FMUL, DMUL -> op = "*";
            case IDIV, LDIV, FDIV, DDIV -> op = "/";
            case IREM, LREM, FREM, DREM -> op = "%";
            case ISHL, LSHL -> op = "<<";
            case ISHR, LSHR -> op = ">>";
            case IUSHR, LUSHR -> op = ">>>";
            case IAND, LAND -> op = "&";
            case IOR, LOR -> op = "|";
            case IXOR, LXOR -> op = "^";
            default -> throw new UnsupportedOperationException("operator " + o.opcode());
        }
        Expr b = pop(), a = pop();
        // ~x is compiled as x ^ -1
        if (op.equals("^") && b instanceof Const c && (c.isInt(-1) || Long.valueOf(-1).equals(c.v))) { push(new Unary("~", a)); return; }
        if ((op.equals("&") || op.equals("|") || op.equals("^")) && a.type().equals("Z") && b.type().equals("Z")) t = "Z";
        push(new Binary(op, a, b, t));
    }

    private void field(FieldInstruction f) {
        String owner = f.owner().asInternalName(), name = f.name().stringValue(), d = f.type().stringValue();
        switch (f.opcode()) {
            case GETSTATIC -> push(new FieldRef(null, owner, name, d));
            case GETFIELD -> push(new FieldRef(pop(), owner, name, d));
            case PUTSTATIC -> assign(new FieldRef(null, owner, name, d), pop());
            default -> {
                Expr v = pop(), t = pop();
                assign(new FieldRef(t, owner, name, d), v);
            }
        }
    }

    private void invoke(InvokeInstruction m) {
        String owner = m.owner().asSymbol().isArray() ? m.owner().asSymbol().descriptorString() : m.owner().asInternalName();
        String name = m.name().stringValue(), d = m.type().stringValue();
        int n = Types.params(d).size();
        Expr[] args = new Expr[n];
        for (int i = n - 1; i >= 0; i--) args[i] = pop();
        Expr target = m.opcode() == Opcode.INVOKESTATIC ? null : pop();
        if (name.equals("<init>")) {
            if (target instanceof Uninit u) {
                NewObj no = new NewObj(u.cls, d, args);
                if (!replaceIdentity(u, no)) emit(no);
            } else {
                Invoke c = new Invoke(Kind.SUPER, target, owner, name, d, args);
                emit(c);
            }
            return;
        }
        Kind k = switch (m.opcode()) {
            case INVOKESTATIC -> Kind.STATIC;
            case INVOKEINTERFACE -> Kind.INTERFACE;
            case INVOKESPECIAL -> owner.equals(thisClass) ? Kind.VIRTUAL : Kind.SUPER;
            default -> Kind.VIRTUAL;
        };
        Invoke c = new Invoke(k, target, owner, name, d, args);
        c.iface = m.isInterface();
        if (Types.ret(d).equals("V")) emit(c);
        else push(c);
    }

    private void dynamic(InvokeDynamicInstruction d) {
        String md = d.type().stringValue();
        int n = Types.params(md).size();
        Expr[] args = new Expr[n];
        for (int i = n - 1; i >= 0; i--) args[i] = pop();
        var bsm = d.invokedynamic().bootstrap();
        var handle = bsm.bootstrapMethod().asSymbol();
        String owner = handle.owner().descriptorString(), bname = handle.methodName();
        List<LoadableConstantEntry> bargs = bsm.arguments();
        if (owner.equals("Ljava/lang/invoke/LambdaMetafactory;") && bargs.size() >= 3 && bargs.get(1) instanceof MethodHandleEntry h) {
            MemberRefEntry r = h.asSymbol() instanceof DirectMethodHandleDesc ? h.reference() : null;
            Lambda l = new Lambda(Types.ret(md), h.kind(), r.owner().asInternalName(), r.name().stringValue(), r.type().stringValue(), args);
            l.ownerIface = r instanceof InterfaceMethodRefEntry;
            l.sam = d.name().stringValue();
            if (bargs.get(2) instanceof MethodTypeEntry mt) l.instDesc = mt.descriptor().stringValue();
            // receiver null checks generated in front of bound method references
            if (args.length > 0 && !cur.stmts.isEmpty() && cur.stmts.get(cur.stmts.size() - 1) instanceof ExprStmt es
                    && es.e instanceof Invoke c && (c.is("java/util/Objects", "requireNonNull") && c.args.length == 1 && c.args[0].same(args[0])
                    || c.name.equals("getClass") && c.target != null && c.target.same(args[0]))) {
                cur.stmts.remove(cur.stmts.size() - 1);
            }
            push(l);
            return;
        }
        if (owner.equals("Ljava/lang/invoke/StringConcatFactory;")) {
            List<String> ps = Types.params(md);
            for (int i = 0; i < args.length; i++) {
                // the call site records the static type; a char passed as int prints as a number
                if ("IZC".contains(ps.get(i)) && !(args[i] instanceof Const)) args[i] = new Cast(ps.get(i), args[i]);
                else if (args[i] instanceof Const c && c.v instanceof Integer && (ps.get(i).equals("C") || ps.get(i).equals("Z"))) args[i] = new Const(c.v, ps.get(i));
            }
            push(concat(bname, bargs, args));
            return;
        }
        Dynamic x = new Dynamic("/* invokedynamic " + bname + " */ $" + d.name().stringValue(), md, args);
        if (Types.ret(md).equals("V")) emit(x);
        else push(x);
    }

    private static Expr concat(String bname, List<LoadableConstantEntry> bargs, Expr[] args) {
        List<Expr> parts = new ArrayList<>();
        if (bname.equals("makeConcatWithConstants") && !bargs.isEmpty() && bargs.get(0) instanceof StringEntry se) {
            String recipe = se.stringValue();
            StringBuilder lit = new StringBuilder();
            int a = 0, c = 1;
            for (int i = 0; i < recipe.length(); i++) {
                char ch = recipe.charAt(i);
                if (ch == 1 || ch == 2) {
                    if (lit.length() > 0) { parts.add(new Const(lit.toString(), Types.STR)); lit.setLength(0); }
                    if (ch == 1) parts.add(args[a++]);
                    else {
                        LoadableConstantEntry k = bargs.get(c++);
                        parts.add(k instanceof StringEntry s ? new Const(s.stringValue(), Types.STR) : new Dynamic("$const", "()Ljava/lang/Object;", new Expr[0]));
                    }
                } else lit.append(ch);
            }
            if (lit.length() > 0) parts.add(new Const(lit.toString(), Types.STR));
        } else parts.addAll(List.of(args));
        return concatOf(parts);
    }

    static Expr concatOf(List<Expr> parts) {
        boolean strFirst = !parts.isEmpty() && (parts.get(0).type().equals(Types.STR) || parts.size() > 1 && parts.get(1).type().equals(Types.STR));
        Expr e = strFirst ? null : new Const("", Types.STR);
        for (Expr p : parts) e = e == null ? p : new Binary("+", e, p, Types.STR);
        return e;
    }

    private void stackOp(StackInstruction s) {
        int n = stack.size();
        switch (s.opcode()) {
            case POP -> popValue();
            case POP2 -> {
                boolean wide = Types.size(peek().type()) == 2;
                popValue();
                if (!wide) popValue();
            }
            case DUP -> {
                Expr top = peek();
                if (top instanceof NewArray) { push(top); return; }
                settle(n - 1);
                push(peek());
            }
            case DUP_X1 -> {
                settle(n - 1);
                settleBelow(n - 2);
                stack.add(n - 2, peek());
            }
            case DUP_X2 -> {
                settle(n - 1);
                if (Types.size(stack.get(n - 2).type()) == 2) { settleBelow(n - 2); stack.add(n - 2, peek()); }
                else { settleBelow(n - 3); settleBelow(n - 2); stack.add(n - 3, peek()); }
            }
            case DUP2 -> {
                if (Types.size(peek().type()) == 2) {
                    settle(n - 1);
                    push(peek());
                } else {
                    settle(n - 2);
                    settle(n - 1);
                    push(stack.get(n - 2));
                    push(stack.get(n - 1));
                }
            }
            case DUP2_X1 -> {
                if (Types.size(peek().type()) == 2) {
                    settle(n - 1);
                    settleBelow(n - 2);
                    stack.add(n - 2, peek());
                } else {
                    settle(n - 2);
                    settle(n - 1);
                    settleBelow(n - 3);
                    Expr a = stack.get(n - 2), b = stack.get(n - 1);
                    stack.add(n - 3, b);
                    stack.add(n - 3, a);
                }
            }
            case DUP2_X2 -> {
                if (Types.size(peek().type()) == 2) {
                    settle(n - 1);
                    if (Types.size(stack.get(n - 2).type()) == 2) {
                        settleBelow(n - 2);
                        stack.add(n - 2, peek());
                    } else {
                        settleBelow(n - 3);
                        settleBelow(n - 2);
                        stack.add(n - 3, peek());
                    }
                } else {
                    settle(n - 2);
                    settle(n - 1);
                    settleBelow(n - 3);
                    settleBelow(n - 4);
                    Expr a = stack.get(n - 2), b = stack.get(n - 1);
                    stack.add(n - 4, b);
                    stack.add(n - 4, a);
                }
            }
            case SWAP -> {
                flush(n);
                for (int i : new int[]{n - 2, n - 1}) {
                    if (stack.get(i) instanceof Const) continue;
                    spill(i);
                    if (stack.get(i) instanceof Local l) l.v.noInline = true;
                }
                Collections.swap(stack, n - 2, n - 1);
            }
            default -> throw new UnsupportedOperationException("stack op " + s.opcode());
        }
    }

    private void settleBelow(int i) {
        // values sliding under a duplicate must already be evaluated
        if (!pure(stack.get(i))) {
            flush(i);
            spill(i);
        }
    }

    private void popValue() {
        Expr e = pop();
        if (e instanceof Local || e instanceof Const || e instanceof Caught || e instanceof Uninit) return;
        if (e instanceof Cast c && pure(c.e)) return;
        if (e instanceof NewArray na && replaceIdentity(na, na)) return;
        emit(e);
    }

    private void branch(int idx, BranchInstruction b) {
        Opcode op = b.opcode();
        if (op == Opcode.GOTO || op == Opcode.GOTO_W) {
            cur.term = Term.GOTO;
            exitStack(cur);
            return;
        }
        Expr c = switch (op) {
            case IFEQ -> intCmp(pop(), "==");
            case IFNE -> intCmp(pop(), "!=");
            case IFLT -> intCmp(pop(), "<");
            case IFGE -> intCmp(pop(), ">=");
            case IFGT -> intCmp(pop(), ">");
            case IFLE -> intCmp(pop(), "<=");
            case IFNULL -> new Binary("==", pop(), Const.nul(), "Z");
            case IFNONNULL -> new Binary("!=", pop(), Const.nul(), "Z");
            default -> {
                Expr y = pop(), x = pop();
                yield new Binary(switch (op) {
                    case IF_ICMPEQ, IF_ACMPEQ -> "==";
                    case IF_ICMPNE, IF_ACMPNE -> "!=";
                    case IF_ICMPLT -> "<";
                    case IF_ICMPGE -> ">=";
                    case IF_ICMPGT -> ">";
                    default -> "<=";
                }, x, y, "Z");
            }
        };
        Block target = cfg.blockAt[cfg.live(cfg.idx(b.target()))];
        Block fall = cfg.blockAt[cfg.live(idx + 1)];
        if (target == fall) {
            if (c.any(x -> x instanceof Invoke || x instanceof Assign || x instanceof IncDec)) cur.stmts.add(Ast.effect(c));
            cur.term = Term.GOTO;
            cur.succ.clear();
            cur.succ.add(target);
            exitStack(cur);
            return;
        }
        // keep the fall-through as the "then" side: that is how source ifs compile
        cur.term = Term.COND;
        cur.cond = negate(c);
        cur.succ.clear();
        cur.succ.add(fall);
        cur.succ.add(target);
        exitStack(cur);
    }

    private void switchOn(int idx, java.lang.classfile.Label dflt, List<SwitchCase> cases) {
        Expr sel = pop();
        cur.term = Term.SWITCH;
        cur.cond = sel;
        cur.keys = new LinkedHashMap<>();
        cur.dflt = cfg.blockAt[cfg.live(cfg.idx(dflt))];
        for (SwitchCase c : cases) {
            Block t = cfg.blockAt[cfg.live(cfg.idx(c.target()))];
            cur.keys.computeIfAbsent(t, k -> new ArrayList<>()).add(c.caseValue());
        }
        exitStack(cur);
    }
}
