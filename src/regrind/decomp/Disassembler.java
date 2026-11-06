package regrind.decomp;

import java.lang.classfile.*;
import java.lang.classfile.attribute.*;
import java.lang.classfile.constantpool.*;
import java.lang.classfile.instruction.*;
import java.util.*;

/** Textual bytecode listing and class-file summary. */
public final class Disassembler {
    private Disassembler() {}

    /** Operand text for one instruction, without labels; used by listings and to compare instructions. */
    public static String operands(Instruction i) {
        return switch (i) {
            case FieldInstruction f -> f.owner().asInternalName() + "." + f.name().stringValue() + " " + f.type().stringValue();
            case InvokeInstruction m -> m.owner().asInternalName() + "." + m.name().stringValue() + " " + m.type().stringValue();
            case InvokeDynamicInstruction d -> d.name().stringValue() + " " + d.type().stringValue() + " bootstrap=" + bootstrapName(d);
            case ConstantInstruction c -> constant(c);
            case LoadInstruction l -> String.valueOf(l.slot());
            case StoreInstruction s -> String.valueOf(s.slot());
            case IncrementInstruction n -> n.slot() + " " + n.constant();
            case NewObjectInstruction n -> n.className().asInternalName();
            case NewReferenceArrayInstruction n -> n.componentType().asInternalName();
            case NewPrimitiveArrayInstruction n -> n.typeKind().toString().toLowerCase(Locale.ROOT);
            case NewMultiArrayInstruction n -> n.arrayType().asInternalName() + " " + n.dimensions();
            case TypeCheckInstruction t -> t.type().asInternalName();
            default -> "";
        };
    }

    private static String bootstrapName(InvokeDynamicInstruction d) {
        try {
            return d.invokedynamic().bootstrap().bootstrapMethod().asSymbol().methodName();
        } catch (RuntimeException e) {
            return "?";
        }
    }

    private static String constant(ConstantInstruction c) {
        Object v;
        try {
            v = c.constantValue();
        } catch (RuntimeException e) {
            return "<dynamic>";
        }
        return v instanceof String s ? Ast.quote(s) : String.valueOf(v);
    }

    public static String method(MethodModel m) {
        StringBuilder b = new StringBuilder();
        var code = m.code().orElse(null);
        if (code == null) return "    // no code\n";
        CodeAttribute ca = (CodeAttribute) code;
        b.append("    // stack=").append(ca.maxStack()).append(" locals=").append(ca.maxLocals())
                .append(" bytes=").append(ca.codeLength()).append('\n');
        Map<Label, Integer> ids = new HashMap<>();
        java.util.function.Function<Label, String> lab = l -> "L" + ids.computeIfAbsent(l, x -> ids.size());
        int bci = 0;
        for (CodeElement e : code) {
            switch (e) {
                case LabelTarget t -> {
                    if (isTarget(code, t.label())) b.append("  ").append(lab.apply(t.label())).append(":\n");
                }
                case LineNumber n -> b.append("    // line ").append(n.line()).append('\n');
                case Instruction i -> {
                    b.append(String.format("    %5d  %-16s", bci, i.opcode().name().toLowerCase(Locale.ROOT)));
                    switch (i) {
                        case BranchInstruction br -> b.append(lab.apply(br.target()));
                        case TableSwitchInstruction t -> {
                            b.append("default: ").append(lab.apply(t.defaultTarget()));
                            for (SwitchCase c : t.cases()) b.append(", ").append(c.caseValue()).append(": ").append(lab.apply(c.target()));
                        }
                        case LookupSwitchInstruction t -> {
                            b.append("default: ").append(lab.apply(t.defaultTarget()));
                            for (SwitchCase c : t.cases()) b.append(", ").append(c.caseValue()).append(": ").append(lab.apply(c.target()));
                        }
                        default -> b.append(operands(i));
                    }
                    b.append('\n');
                    bci += i.sizeInBytes();
                }
                default -> {}
            }
        }
        for (ExceptionCatch x : code.exceptionHandlers()) {
            b.append("    // try ").append(ca.labelToBci(x.tryStart())).append("..").append(ca.labelToBci(x.tryEnd()))
                    .append(" -> ").append(ca.labelToBci(x.handler())).append(' ')
                    .append(x.catchType().map(ClassEntry::asInternalName).orElse("any")).append('\n');
        }
        return b.toString();
    }

    private static boolean isTarget(CodeModel code, Label l) {
        for (CodeElement e : code) {
            switch (e) {
                case BranchInstruction b -> { if (b.target() == l) return true; }
                case TableSwitchInstruction t -> {
                    if (t.defaultTarget() == l) return true;
                    for (SwitchCase c : t.cases()) if (c.target() == l) return true;
                }
                case LookupSwitchInstruction t -> {
                    if (t.defaultTarget() == l) return true;
                    for (SwitchCase c : t.cases()) if (c.target() == l) return true;
                }
                default -> {}
            }
        }
        for (ExceptionCatch x : code.exceptionHandlers()) if (x.handler() == l) return true;
        return false;
    }

    /** Whole-class bytecode listing. */
    public static String classListing(ClassModel c) {
        StringBuilder b = new StringBuilder();
        b.append("// class ").append(c.thisClass().asInternalName()).append(" (version ")
                .append(c.majorVersion()).append('.').append(c.minorVersion()).append(")\n");
        c.superclass().ifPresent(s -> b.append("// extends ").append(s.asInternalName()).append('\n'));
        for (ClassEntry i : c.interfaces()) b.append("// implements ").append(i.asInternalName()).append('\n');
        b.append('\n');
        for (FieldModel f : c.fields()) {
            b.append("0x").append(Integer.toHexString(f.flags().flagsMask())).append(' ').append(f.fieldName().stringValue()).append(' ')
                    .append(f.fieldType().stringValue()).append('\n');
        }
        if (!c.fields().isEmpty()) b.append('\n');
        for (MethodModel m : c.methods()) {
            b.append("0x").append(Integer.toHexString(m.flags().flagsMask())).append(' ').append(m.methodName().stringValue())
                    .append(m.methodType().stringValue()).append('\n');
            try {
                b.append(method(m));
            } catch (RuntimeException e) {
                b.append("    // unreadable code: ").append(e).append('\n');
            }
            b.append('\n');
        }
        return b.toString();
    }

    /** Class metadata and constant pool. */
    public static String info(ClassModel c) {
        StringBuilder b = new StringBuilder();
        b.append("Class:      ").append(c.thisClass().asInternalName()).append('\n');
        b.append("Super:      ").append(c.superclass().map(ClassEntry::asInternalName).orElse("-")).append('\n');
        b.append("Interfaces: ");
        for (ClassEntry i : c.interfaces()) b.append(i.asInternalName()).append(' ');
        b.append("\nVersion:    ").append(c.majorVersion()).append('.').append(c.minorVersion())
                .append(" (Java ").append(Math.max(1, c.majorVersion() - 44)).append(")\n");
        b.append("Flags:      0x").append(Integer.toHexString(c.flags().flagsMask())).append('\n');
        b.append("Attributes: ");
        for (Attribute<?> a : c.attributes()) b.append(a.attributeName().stringValue()).append(' ');
        b.append("\nFields:     ").append(c.fields().size()).append("\nMethods:    ").append(c.methods().size());
        b.append("\n\nConstant pool (").append(c.constantPool().size()).append(" slots)\n");
        for (PoolEntry e : c.constantPool()) {
            b.append(String.format("%6d  %-20s %s%n", e.index(), e.getClass().getSimpleName().replace("Impl", ""), describe(e)));
        }
        return b.toString();
    }

    private static String describe(PoolEntry e) {
        try {
            return switch (e) {
                case Utf8Entry u -> Ast.quote(u.stringValue());
                case ClassEntry x -> x.asInternalName();
                case StringEntry s -> Ast.quote(s.stringValue());
                case IntegerEntry i -> String.valueOf(i.intValue());
                case LongEntry l -> String.valueOf(l.longValue());
                case FloatEntry f -> String.valueOf(f.floatValue());
                case DoubleEntry d -> String.valueOf(d.doubleValue());
                case NameAndTypeEntry n -> n.name().stringValue() + " " + n.type().stringValue();
                case MemberRefEntry m -> m.owner().asInternalName() + "." + m.name().stringValue() + " " + m.type().stringValue();
                default -> e.toString();
            };
        } catch (RuntimeException ex) {
            return "<malformed>";
        }
    }
}
