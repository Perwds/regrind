package regrind.decomp;

import regrind.decomp.Ast.*;
import java.lang.classfile.*;
import java.lang.classfile.attribute.CodeAttribute;
import java.lang.classfile.instruction.*;
import java.lang.reflect.AccessFlag;
import java.util.*;

/**
 * Control-flow graph for one method: flat instructions, finally/synchronized recovery at the
 * instruction level, basic blocks, protected regions, and local-variable webs.
 */
final class Cfg {
    static final class Insn {
        Instruction i;
        int bci, line;
        boolean dead, endFinally;
    }
    record Exc(int start, int end, int handler, String type) {}
    record Lvt(int slot, String name, String desc, int start, int end) {}

    enum Term { GOTO, COND, SWITCH, RETURN, THROW, END_FINALLY, TRY }

    static final class Block {
        int id, start, end;
        List<Stmt> stmts = new ArrayList<>();
        Term term = Term.GOTO;
        Expr cond;                     // COND: branch to succ[0] when true; SWITCH selector; RETURN/THROW value
        List<Block> succ = new ArrayList<>();
        List<Block> preds = new ArrayList<>();
        Map<Block, List<Integer>> keys; // SWITCH
        Block dflt;
        List<Block> handlers = new ArrayList<>();
        List<Expr> entry;
        Region tryOf;                  // TRY: the region this virtual head opens
        List<Block> virtualSucc = new ArrayList<>();
        int rpo = -1;
        Block idom;
        int line;
        boolean finallyEntry;
        String catchType;
        Set<Region> regions = new HashSet<>();
        @Override public String toString() { return "B" + id; }
    }

    static final class Handler {
        int idx;
        Block block;
        List<String> types = new ArrayList<>();
        boolean fin;
    }

    static final class Region {
        BitSet cover = new BitSet();
        List<Handler> handlers = new ArrayList<>();
        Set<Block> blocks = new LinkedHashSet<>();
        Block head, entry;
    }

    final MethodModel method;
    final CodeModel code;
    final CodeAttribute ca;
    Insn[] ins;
    int[] bciToIdx;
    final List<Exc> excs = new ArrayList<>();
    final List<Lvt> lvt = new ArrayList<>();
    final Map<String, String> lvtGeneric = new HashMap<>();
    final Map<Integer, Boolean> finallyHandlers = new HashMap<>();
    final List<Region> regions = new ArrayList<>();
    final List<Block> blocks = new ArrayList<>();
    Block[] blockAt;
    Block entry;
    Var[] loadVar, storeVar;
    final List<Var> params = new ArrayList<>();
    Var thisVar;

    Cfg(MethodModel m, CodeModel code) {
        method = m;
        this.code = code;
        ca = (CodeAttribute) code;
    }

    int idx(java.lang.classfile.Label l) {
        int b = ca.labelToBci(l);
        if (b < 0 || b >= bciToIdx.length || bciToIdx[b] < 0) throw new IllegalStateException("bad label");
        return bciToIdx[b];
    }

    void flatten() {
        List<Insn> list = new ArrayList<>();
        bciToIdx = new int[ca.codeLength() + 1];
        Arrays.fill(bciToIdx, -1);
        int bci = 0, line = 0;
        List<LocalVariable> lv = new ArrayList<>();
        for (CodeElement e : code) {
            switch (e) {
                case Instruction i -> {
                    if (i instanceof DiscontinuedInstruction) throw new UnsupportedOperationException("jsr/ret subroutine");
                    Insn n = new Insn();
                    n.i = i;
                    n.bci = bci;
                    n.line = line;
                    bciToIdx[bci] = list.size();
                    list.add(n);
                    bci += i.sizeInBytes();
                }
                case LineNumber n -> line = n.line();
                case LocalVariable v -> lv.add(v);
                case LocalVariableType t -> lvtGeneric.put(t.slot() + ":" + t.name().stringValue() + ":" + ca.labelToBci(t.startScope()), t.signature().stringValue());
                default -> {}
            }
        }
        bciToIdx[bci] = list.size();
        ins = list.toArray(new Insn[0]);
        for (LocalVariable v : lv) {
            lvt.add(new Lvt(v.slot(), v.name().stringValue(), v.type().stringValue(), ca.labelToBci(v.startScope()), ca.labelToBci(v.endScope())));
        }
        for (ExceptionCatch x : code.exceptionHandlers()) {
            int s = idx(x.tryStart()), en = idx(x.tryEnd()), h = idx(x.handler());
            if (s < en) excs.add(new Exc(s, en, h, x.catchType().map(c -> c.asSymbol().descriptorString()).orElse(null)));
        }
    }

    // ---------------------------------------------------------------- successors

    int live(int i) {
        while (i < ins.length && ins[i].dead) i++;
        return i;
    }

    void succs(int i, List<Integer> out) {
        Instruction x = ins[i].i;
        switch (x) {
            case BranchInstruction b -> {
                out.add(live(idx(b.target())));
                if (b.opcode() != Opcode.GOTO && b.opcode() != Opcode.GOTO_W) out.add(live(i + 1));
            }
            case TableSwitchInstruction t -> {
                out.add(live(idx(t.defaultTarget())));
                for (SwitchCase c : t.cases()) out.add(live(idx(c.target())));
            }
            case LookupSwitchInstruction t -> {
                out.add(live(idx(t.defaultTarget())));
                for (SwitchCase c : t.cases()) out.add(live(idx(c.target())));
            }
            case ReturnInstruction r -> {}
            case ThrowInstruction t -> {}
            default -> out.add(live(i + 1));
        }
    }

    // ---------------------------------------------------------------- finally / synchronized

    private static boolean isLoad(Instruction i, int slot) {
        return i instanceof LoadInstruction l && l.slot() == slot && l.typeKind() == TypeKind.REFERENCE;
    }

    /** Remove inlined copies of finally blocks, turning the handler into a real finally. */
    void recoverFinally() {
        Map<Integer, BitSet> any = new TreeMap<>();
        for (Exc e : excs) {
            if (e.type != null) continue;
            BitSet c = any.computeIfAbsent(e.handler, k -> new BitSet());
            c.set(e.start, e.end);
        }
        List<Map.Entry<Integer, BitSet>> order = new ArrayList<>(any.entrySet());
        order.sort(Comparator.comparingInt(x -> x.getValue().cardinality()));
        for (var en : order) {
            try {
                tryFinally(en.getKey(), en.getValue());
            } catch (IllegalStateException ignored) {
                // leave this handler as a plain catch of Throwable
            }
        }
    }

    private void tryFinally(int h, BitSet coverRaw) {
        if (h >= ins.length || !(ins[h].i instanceof StoreInstruction st) || st.typeKind() != TypeKind.REFERENCE) return;
        int t = st.slot(), j = -1;
        for (int k = h + 1; k + 1 < ins.length; k++) {
            if (isLoad(ins[k].i, t) && ins[k + 1].i instanceof ThrowInstruction) { j = k; break; }
            if (ins[k].i instanceof ReturnInstruction) return;
        }
        if (j < 0) return;
        BitSet cover = (BitSet) coverRaw.clone();
        cover.clear(h, j + 2);
        for (int k = cover.nextSetBit(0); k >= 0; k = cover.nextSetBit(k + 1)) if (ins[k].dead) cover.clear(k);
        if (cover.isEmpty()) return;

        List<Integer> f = new ArrayList<>();
        for (int k = h + 1; k < j; k++) if (!ins[k].dead) f.add(k);
        if (f.isEmpty()) return;
        // the finally body must not jump anywhere except within itself
        List<Integer> tmp = new ArrayList<>();
        for (int k : f) {
            tmp.clear();
            succs(k, tmp);
            for (int s : tmp) if (s < h + 1 || s > j) return;
        }

        boolean sync = f.size() == 2 && ins[f.get(1)].i.opcode() == Opcode.MONITOREXIT;
        List<Integer> kill = new ArrayList<>();
        if (sync) {
            int lock = ins[f.get(0)].i instanceof LoadInstruction l ? l.slot() : -1;
            for (int k = cover.nextSetBit(0); k >= 0; k = cover.nextSetBit(k + 1)) {
                if (k + 1 < ins.length && isLoad(ins[k].i, lock) && ins[k + 1].i.opcode() == Opcode.MONITOREXIT) {
                    kill.add(k);
                    kill.add(k + 1);
                }
            }
        }
        for (int k : kill) { ins[k].dead = true; cover.clear(k); }

        Set<Integer> exits = new TreeSet<>();
        for (int k = cover.nextSetBit(0); k >= 0; k = cover.nextSetBit(k + 1)) {
            if (ins[k].i instanceof ReturnInstruction) {
                if (!sync) { undo(kill); return; }
                continue;
            }
            tmp.clear();
            succs(k, tmp);
            for (int s : tmp) if (!cover.get(s)) exits.add(s);
        }
        List<int[]> copies = new ArrayList<>();
        for (int y : exits) {
            if (y >= ins.length) continue;
            List<Integer> m = match(f, h, j, y);
            if (m == null) {
                if (sync) continue;
                undo(kill);
                return;
            }
            copies.add(m.stream().mapToInt(Integer::intValue).toArray());
        }
        for (int[] c : copies) for (int k : c) ins[k].dead = true;
        // bring a return that directly follows a stripped copy into the protected region
        for (int y : exits) {
            int z = live(y);
            if (z < ins.length && ins[z].i instanceof LoadInstruction && z + 1 < ins.length && ins[live(z + 1)].i instanceof ReturnInstruction) {
                cover.set(z);
                cover.set(live(z + 1));
            } else if (z < ins.length && ins[z].i instanceof ReturnInstruction) cover.set(z);
        }
        ins[h].dead = true;
        ins[j].dead = true;
        ins[j + 1].endFinally = true;
        finallyHandlers.put(h, true);
        coverRaw.clear();
        coverRaw.or(cover);
        finallyCover.put(h, cover);
        finallyStart.put(h, live(h));
    }

    final Map<Integer, BitSet> finallyCover = new HashMap<>();
    final Map<Integer, Integer> finallyStart = new HashMap<>();

    private void undo(List<Integer> kill) { for (int k : kill) ins[k].dead = false; }

    /** Match the finally body against the code at y; returns the matched live indices or null. */
    private List<Integer> match(List<Integer> f, int h, int j, int y) {
        Map<Integer, Integer> slotMap = new HashMap<>(), rev = new HashMap<>();
        List<Integer> got = new ArrayList<>();
        Map<Integer, Integer> pos = new HashMap<>(); // finally index -> position
        for (int a = 0; a < f.size(); a++) pos.put(f.get(a), a);
        int b = y;
        List<int[]> branches = new ArrayList<>();
        for (int a = 0; a < f.size(); a++) {
            b = live(b);
            if (b >= ins.length) return null;
            Instruction p = ins[f.get(a)].i, q = ins[b].i;
            if (p.opcode() != q.opcode() && !sameShape(p, q)) return null;
            int sp = slot(p), sq = slot(q);
            if (sp >= 0) {
                if (sq < 0) return null;
                Integer m = slotMap.putIfAbsent(sp, sq), r = rev.putIfAbsent(sq, sp);
                if (m != null && m != sq || r != null && r != sp) return null;
                if (p instanceof IncrementInstruction ip && ip.constant() != ((IncrementInstruction) q).constant()) return null;
            } else if (p instanceof BranchInstruction pb) {
                if (!(q instanceof BranchInstruction qb)) return null;
                branches.add(new int[]{idx(pb.target()), idx(qb.target())});
            } else if (p instanceof TableSwitchInstruction || p instanceof LookupSwitchInstruction) {
                return null;
            } else if (!Disassembler.operands(p).equals(Disassembler.operands(q))) return null;
            got.add(b);
            b++;
        }
        for (int[] br : branches) {
            Integer pp = pos.get(live(br[0]));
            if (pp == null) {
                // jump to the end of the finally body: must land right after the copy
                if (live(br[0]) != live(j) && live(br[0]) != j) return null;
                if (live(br[1]) != live(b)) return null;
            } else if (!got.get(pp).equals(live(br[1]))) return null;
        }
        return got;
    }

    private static boolean sameShape(Instruction p, Instruction q) {
        // aload_1 vs aload 5 have different opcodes but identical meaning
        if (p instanceof LoadInstruction a && q instanceof LoadInstruction b) return a.typeKind() == b.typeKind();
        if (p instanceof StoreInstruction a && q instanceof StoreInstruction b) return a.typeKind() == b.typeKind();
        if (p instanceof IncrementInstruction && q instanceof IncrementInstruction) return true;
        if (p instanceof ConstantInstruction a && q instanceof ConstantInstruction b) return a.typeKind() == b.typeKind();
        if (p instanceof BranchInstruction a && q instanceof BranchInstruction b) return a.opcode() == b.opcode();
        return false;
    }

    private static int slot(Instruction i) {
        return switch (i) {
            case LoadInstruction l -> l.slot();
            case StoreInstruction s -> s.slot();
            case IncrementInstruction n -> n.slot();
            default -> -1;
        };
    }

    // ---------------------------------------------------------------- blocks

    void build() {
        int n = ins.length;
        BitSet leader = new BitSet(n + 1);
        leader.set(live(0));
        List<Integer> tmp = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (ins[i].dead) continue;
            Instruction x = ins[i].i;
            boolean endsBlock = x instanceof BranchInstruction || x instanceof TableSwitchInstruction
                    || x instanceof LookupSwitchInstruction || x instanceof ReturnInstruction || x instanceof ThrowInstruction;
            if (endsBlock) {
                leader.set(live(i + 1));
                tmp.clear();
                succs(i, tmp);
                for (int s : tmp) leader.set(s);
            }
        }
        Map<Integer, Region> byHandler = new LinkedHashMap<>();
        for (Exc e : excs) {
            int h = live(e.handler);
            leader.set(h);
            leader.set(live(e.start));
            leader.set(live(Math.min(e.end, n)));
            Region r = byHandler.computeIfAbsent(e.handler, k -> {
                Region x = new Region();
                Handler hd = new Handler();
                hd.idx = h;
                hd.fin = finallyHandlers.containsKey(e.handler);
                x.handlers.add(hd);
                return x;
            });
            Handler hd = r.handlers.get(0);
            if (hd.fin) {
                BitSet c = finallyCover.get(e.handler);
                r.cover = c;
            } else {
                r.cover.set(e.start, e.end);
                hd.types.add(e.type);
            }
        }
        // protected ranges extended by finally recovery must also start blocks
        for (BitSet c : finallyCover.values()) {
            for (int k = c.nextSetBit(0); k >= 0; k = c.nextSetBit(k + 1)) {
                if (k == 0 || !c.get(k - 1)) leader.set(live(k));
                if (!c.get(k + 1)) leader.set(live(k + 1));
            }
        }
        blockAt = new Block[n + 1];
        Block cur = null;
        for (int i = 0; i < n; i++) {
            if (ins[i].dead) continue;
            if (cur == null || leader.get(i)) {
                if (cur != null) cur.end = i;
                cur = new Block();
                cur.id = blocks.size();
                cur.start = i;
                cur.line = ins[i].line;
                blocks.add(cur);
            }
            blockAt[i] = cur;
        }
        if (cur == null) throw new IllegalStateException("empty code");
        cur.end = n;
        entry = blocks.get(0);

        for (Region r : byHandler.values()) {
            Handler hd = r.handlers.get(0);
            r.cover.clear(hd.idx);
            hd.block = blockAt[hd.idx];
            if (hd.block == null) continue;
            if (hd.fin) hd.block.finallyEntry = true;
            else hd.block.catchType = hd.types.size() == 1 && hd.types.get(0) != null ? hd.types.get(0) : "Ljava/lang/Throwable;";
            for (int k = r.cover.nextSetBit(0); k >= 0; k = r.cover.nextSetBit(k + 1)) {
                if (k < n && !ins[k].dead && blockAt[k] != null) r.blocks.add(blockAt[k]);
            }
            r.blocks.remove(hd.block);
            if (!r.blocks.isEmpty()) regions.add(r);
        }
        for (Region r : regions) for (Block b : r.blocks) b.handlers.add(r.handlers.get(0).block);
    }

    // ---------------------------------------------------------------- local variable webs

    private record Def(int id, int slot, int insn) {}

    /** Splits each slot into independent variables: every load joins the stores that can reach it. */
    void buildLocals() {
        int n = ins.length;
        loadVar = new Var[n];
        storeVar = new Var[n];
        List<Def> defs = new ArrayList<>();
        Map<Integer, BitSet> slotDefs = new HashMap<>();
        Map<Integer, Integer> paramDef = new HashMap<>();
        String md = method.methodType().stringValue();
        boolean isStatic = method.flags().has(AccessFlag.STATIC);
        int slot = 0;
        List<Var> paramVars = new ArrayList<>();
        if (!isStatic) {
            thisVar = new Var();
            thisVar.isThis = true;
            thisVar.param = true;
            thisVar.slot = 0;
            paramVars.add(thisVar);
            slot = 1;
        }
        for (String p : Types.params(md)) {
            Var v = new Var();
            v.desc = p;
            v.param = true;
            v.slot = slot;
            v.kind = kindOf(p);
            paramVars.add(v);
            params.add(v);
            slot += Types.size(p);
        }
        List<Var> defVars = new ArrayList<>();
        for (Var v : paramVars) {
            Def d = new Def(defs.size(), v.slot, -1);
            defs.add(d);
            defVars.add(v);
            slotDefs.computeIfAbsent(v.slot, k -> new BitSet()).set(d.id);
            paramDef.put(v.slot, d.id);
        }
        for (int i = 0; i < n; i++) {
            if (ins[i].dead) continue;
            int s = -1;
            char k = 'I';
            if (ins[i].i instanceof StoreInstruction st) { s = st.slot(); k = kindOf(st.typeKind()); }
            else if (ins[i].i instanceof IncrementInstruction in) s = in.slot();
            if (s >= 0) {
                Def d = new Def(defs.size(), s, i);
                defs.add(d);
                Var v = new Var();
                v.slot = s;
                v.kind = k;
                if ("JFD".indexOf(k) >= 0) v.desc = String.valueOf(k);
                defVars.add(v);
                slotDefs.computeIfAbsent(s, x -> new BitSet()).set(d.id);
            }
        }
        // reaching definitions over blocks
        Map<Block, BitSet> in = new HashMap<>(), out = new HashMap<>(), allDefs = new HashMap<>();
        for (Block b : blocks) {
            in.put(b, new BitSet());
            out.put(b, null);
        }
        BitSet start = new BitSet();
        for (int id : paramDef.values()) start.set(id);
        in.get(entry).or(start);
        Map<Integer, Integer> defAt = new HashMap<>();
        for (Def d : defs) if (d.insn >= 0) defAt.put(d.insn, d.id);
        for (Block b : blocks) {
            BitSet all = new BitSet();
            for (int i = b.start; i < b.end; i++) { Integer d = defAt.get(i); if (d != null) all.set(d); }
            allDefs.put(b, all);
        }
        linkSuccessors();
        Deque<Block> work = new ArrayDeque<>(blocks);
        Set<Block> queued = new HashSet<>(blocks);
        while (!work.isEmpty()) {
            Block b = work.poll();
            queued.remove(b);
            BitSet cur = (BitSet) in.get(b).clone();
            BitSet ex = (BitSet) cur.clone();
            ex.or(allDefs.get(b));
            for (int i = b.start; i < b.end; i++) {
                Integer d = defAt.get(i);
                if (d != null) {
                    cur.andNot(slotDefs.get(defs.get(d).slot));
                    cur.set(d);
                }
            }
            for (Block h : b.handlers) if (merge(in.get(h), ex) && queued.add(h)) work.add(h);
            for (Block s : b.succ) if (merge(in.get(s), cur) && queued.add(s)) work.add(s);
        }
        int[] uf = new int[defs.size()];
        for (int i = 0; i < uf.length; i++) uf[i] = i;
        List<int[]> uses = new ArrayList<>();
        for (Block b : blocks) {
            BitSet cur = (BitSet) in.get(b).clone();
            for (int i = b.start; i < b.end; i++) {
                if (ins[i].dead) continue;
                int s = -1;
                if (ins[i].i instanceof LoadInstruction l) s = l.slot();
                else if (ins[i].i instanceof IncrementInstruction c) s = c.slot();
                int first = -1;
                if (s >= 0) {
                    BitSet r = (BitSet) cur.clone();
                    BitSet m = slotDefs.get(s);
                    if (m != null) r.and(m); else r.clear();
                    for (int d = r.nextSetBit(0); d >= 0; d = r.nextSetBit(d + 1)) {
                        if (first < 0) first = d; else union(uf, first, d);
                    }
                    uses.add(new int[]{i, first, s});
                }
                Integer d = defAt.get(i);
                if (d != null) {
                    if (ins[i].i instanceof IncrementInstruction && first >= 0) union(uf, first, d);
                    cur.andNot(slotDefs.get(defs.get(d).slot));
                    cur.set(d);
                }
            }
        }
        // merge webs that the debug info says are one variable
        Map<String, Integer> byLvt = new HashMap<>();
        for (Def d : defs) {
            int b = d.insn < 0 ? 0 : ins[d.insn].bci + ins[d.insn].i.sizeInBytes();
            if (d.insn >= 0 && ins[d.insn].i instanceof IncrementInstruction) b = ins[d.insn].bci;
            Lvt l = findLvt(d.slot, b);
            if (l == null) continue;
            Integer o = byLvt.putIfAbsent(l.slot + ":" + l.name + ":" + l.start, d.id);
            if (o != null) union(uf, o, d.id);
        }
        // the representative of a web is its parameter when it has one
        Var[] rep = new Var[defs.size()];
        for (Def d : defs) {
            int r = find(uf, d.id);
            Var v = defVars.get(d.id);
            if (rep[r] == null) rep[r] = v;
            else if (v.param) rep[r] = v;
        }
        for (Def d : defs) {
            Var v = rep[find(uf, d.id)];
            if (d.insn >= 0) storeVar[d.insn] = v;
            int b = d.insn < 0 ? 0 : ins[d.insn].bci + ins[d.insn].i.sizeInBytes();
            if (d.insn >= 0 && ins[d.insn].i instanceof IncrementInstruction) b = ins[d.insn].bci;
            Lvt l = findLvt(d.slot, b);
            if (l != null && !v.isThis) applyLvt(v, l);
        }
        for (int[] u : uses) {
            Var v;
            if (u[1] >= 0) v = rep[find(uf, u[1])];
            else {
                v = new Var();
                v.slot = u[2];
                if (ins[u[0]].i instanceof LoadInstruction l) v.kind = kindOf(l.typeKind());
            }
            loadVar[u[0]] = v;
            Lvt l = findLvt(u[2], ins[u[0]].bci);
            if (l != null && !v.isThis) applyLvt(v, l);
        }
    }

    private void applyLvt(Var v, Lvt l) {
        if (v.fixedName) return;
        v.fixedName = true;
        v.name = l.name;
        v.desc = l.desc;
        v.generic = lvtGeneric.get(l.slot + ":" + l.name + ":" + l.start);
    }

    private Lvt findLvt(int slot, int bci) {
        for (Lvt l : lvt) if (l.slot == slot && bci >= l.start && bci < l.end) return l;
        return null;
    }

    private static boolean merge(BitSet into, BitSet from) {
        int c = into.cardinality();
        into.or(from);
        return into.cardinality() != c;
    }

    private static int find(int[] uf, int x) {
        while (uf[x] != x) x = uf[x] = uf[uf[x]];
        return x;
    }
    private static void union(int[] uf, int a, int b) { uf[find(uf, b)] = find(uf, a); }

    static char kindOf(String d) {
        return switch (d) { case "J" -> 'J'; case "F" -> 'F'; case "D" -> 'D'; case "Z", "B", "C", "S", "I" -> 'I'; default -> 'A'; };
    }
    static char kindOf(TypeKind k) {
        return switch (k) { case LONG -> 'J'; case FLOAT -> 'F'; case DOUBLE -> 'D'; case REFERENCE -> 'A'; default -> 'I'; };
    }

    /** Fill succ from the last instruction of each block. */
    private void linkSuccessors() {
        List<Integer> tmp = new ArrayList<>();
        for (Block b : blocks) {
            int last = b.end - 1;
            while (last >= b.start && ins[last].dead) last--;
            if (last < b.start) throw new IllegalStateException("empty block");
            tmp.clear();
            succs(last, tmp);
            b.succ.clear();
            for (int s : tmp) {
                Block t = s < ins.length ? blockAt[s] : null;
                if (t == null) throw new IllegalStateException("falls off code");
                if (!b.succ.contains(t)) b.succ.add(t);
            }
        }
    }
}
