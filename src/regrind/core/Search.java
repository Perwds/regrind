package regrind.core;

import regrind.decomp.Decompiler;
import java.lang.classfile.*;
import java.lang.classfile.constantpool.*;
import java.lang.classfile.instruction.*;
import java.util.*;
import java.util.function.*;
import java.util.regex.*;

/** Workspace-wide searches. Every mode reports hits to a callback so a UI can stream them. */
public final class Search {
    private Search() {}

    public enum Mode { STRINGS, REFERENCES, DECLARATIONS, SOURCE }

    public record Hit(String cls, String where, String text) {}

    public static void run(Workspace ws, Mode mode, String query, boolean regex, Consumer<Hit> out, BooleanSupplier cancelled) {
        Predicate<String> m = matcher(query, regex);
        for (String name : new ArrayList<>(ws.classes.keySet())) {
            if (cancelled.getAsBoolean()) return;
            ClassModel c = ws.model(name);
            if (c == null) continue;
            try {
                switch (mode) {
                    case STRINGS -> strings(c, name, m, out);
                    case REFERENCES -> references(c, name, m, out);
                    case DECLARATIONS -> declarations(c, name, m, out);
                    case SOURCE -> source(ws, name, m, out);
                }
            } catch (RuntimeException ignored) {
                // one unreadable class should not end a search
            }
        }
    }

    private static Predicate<String> matcher(String q, boolean regex) {
        if (regex) {
            Pattern p = Pattern.compile(q);
            return s -> p.matcher(s).find();
        }
        String low = q.toLowerCase(Locale.ROOT);
        return s -> s.toLowerCase(Locale.ROOT).contains(low);
    }

    private static String sig(MethodModel m) { return m.methodName().stringValue() + m.methodType().stringValue(); }

    private static void eachInstruction(ClassModel c, BiConsumer<MethodModel, Instruction> f) {
        for (MethodModel m : c.methods()) {
            if (m.code().isEmpty()) continue;
            for (CodeElement e : m.code().get()) if (e instanceof Instruction i) f.accept(m, i);
        }
    }

    private static void strings(ClassModel c, String name, Predicate<String> m, Consumer<Hit> out) {
        eachInstruction(c, (mm, i) -> {
            if (i instanceof ConstantInstruction k && k.constantValue() instanceof Object v
                    && (v instanceof String || v instanceof Number)) {
                String s = String.valueOf(v);
                if (m.test(s)) out.accept(new Hit(name, sig(mm), s));
            }
        });
        for (FieldModel f : c.fields()) {
            f.findAttribute(Attributes.constantValue()).ifPresent(a -> {
                String s = String.valueOf(a.constant().constantValue());
                if (m.test(s)) out.accept(new Hit(name, f.fieldName().stringValue(), s));
            });
        }
    }

    private static void references(ClassModel c, String name, Predicate<String> m, Consumer<Hit> out) {
        eachInstruction(c, (mm, i) -> {
            String t = switch (i) {
                case InvokeInstruction v -> v.owner().asInternalName() + "." + v.name().stringValue() + v.type().stringValue();
                case FieldInstruction f -> f.owner().asInternalName() + "." + f.name().stringValue() + " " + f.type().stringValue();
                case NewObjectInstruction n -> n.className().asInternalName();
                case TypeCheckInstruction n -> n.type().asInternalName();
                default -> null;
            };
            if (t != null && m.test(t)) out.accept(new Hit(name, sig(mm), t));
        });
    }

    private static void declarations(ClassModel c, String name, Predicate<String> m, Consumer<Hit> out) {
        if (m.test(name)) out.accept(new Hit(name, "", "class " + name));
        for (FieldModel f : c.fields()) {
            String s = f.fieldName().stringValue() + " " + f.fieldType().stringValue();
            if (m.test(s)) out.accept(new Hit(name, "field", s));
        }
        for (MethodModel f : c.methods()) if (m.test(sig(f))) out.accept(new Hit(name, "method", sig(f)));
    }

    private static void source(Workspace ws, String name, Predicate<String> m, Consumer<Hit> out) {
        if (!Decompiler.isTopLevel(ws, name)) return;
        String src = Decompiler.decompile(ws, name).source();
        int n = 0;
        for (String line : src.split("\n")) {
            n++;
            if (m.test(line)) out.accept(new Hit(name, "line " + n, line.strip()));
        }
    }
}
