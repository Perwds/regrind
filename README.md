# Regrind

> **Disclaimer:** Regrind was written entirely by AI. I made it to test something out.

A Java decompiler with a desktop workbench, in the spirit of CFR and Recaf. Plain Java with no
dependencies: it needs JDK 25 or newer, and a JDK (not a JRE) for the edit-and-recompile feature.

## Build and run

Download `regrind.jar` from the [latest release](https://github.com/Perwds/regrind/releases/latest) and run it, or build it yourself below:

```bash
java -jar regrind.jar
```

To rebuild it on Windows:

```bash
powershell -ExecutionPolicy Bypass -File build.ps1
```

On Linux or macOS:

```bash
sh build.sh
```

```bash
java -jar regrind.jar --gui app.jar       # open a jar straight away
java -jar regrind.jar app.jar -o src      # decompile a jar into a folder
java -jar regrind.jar Foo.class           # print to the console
java -jar regrind.jar app.jar -o src --lib dep.jar --stats
```

## The workbench

- **Class tree** with a filter box (Ctrl+P). Double-click to open. Drag a jar onto the window to open it.
- **Four views per class:** Decompiled, Bytecode, Class info (with the constant pool) and Hex.
- **Edit and recompile:** change the decompiled source and press Ctrl+S. It compiles against the jar
  and swaps the classes in memory. Errors appear in the console and the caret jumps to the first one.
- **Export jar** (Ctrl+E) writes the patched jar. Nothing touches disk until you do this.
- **Export all sources** writes every class as a `.java` file.
- **Search** (Ctrl+Shift+F): strings and numbers, references to a class or member, declarations,
  or the decompiled text, with optional regex.
- **Rename class** from the tree's right-click menu, across the whole workspace.
- **Go to class** under the caret with F3. Ctrl+F finds in the current view.

## What it decompiles

If/else, all loop forms, labeled break and continue, switch with fall-through, string and enum
switches, try/catch/finally, synchronized, ternaries, short-circuit conditions, lambdas, method
references, anonymous and inner classes, enums, records, sealed types, generics, annotations,
assertions, and both string concatenation styles (StringBuilder and invokedynamic).

## How it works

1. `Cfg` flattens the method, strips inlined `finally` copies and builds basic blocks and variable webs.
2. `Sim` runs the operand stack abstractly to turn instructions into expression trees.
3. `Reduce` folds short-circuit conditions and ternaries in the graph.
4. `Structurer` turns the graph into nested statements using dominators (Ramsey's "Beyond Relooper").
5. `Simplify` rewrites the labeled blocks into normal `if`, `while`, `do` and `switch`.
6. `Finish` infers types, adds the casts and literals Java needs, declares and names variables.
7. `Sugar` restores foreach, string and enum switches, `synchronized`, `assert` and so on.
8. `ClassPrinter` renders the class.

If a method can't be structured, its bytecode is printed in a comment and its body becomes a stub
that throws `UnsupportedOperationException`. The workbench warns you before compiling over a stub.

## Known limits

- Irreducible control flow (usually obfuscation) and `jsr`/`ret` from very old compilers fall back to that stub.
- Try-with-resources shows as its expanded try/catch form. It behaves the same but reads longer.
- Pattern-matching `switch` over types shows a placeholder for its `invokedynamic` call and won't recompile.
- Instance field initialisers stay in the constructor rather than moving to the field.
- Local classes print as nested classes, so a local class in a generic method can fail to recompile.
- Decompiled source doesn't always recompile, especially generic-heavy code. See the numbers below.
- With `-o` or Export all sources, two classes differing only by case get a `_2` suffix on Windows, and that file then won't compile as a public class.
- Rename covers classes only, not fields or methods, and leaves string constants (including reflection names) alone.

## Measured results

Run against JDK 25's own classes:

| Check | Result |
| --- | --- |
| `java.base`, `java.desktop`, `java.xml`, `jdk.compiler` (140,597 methods) | 0 crashes, 3 methods fell back to stubs |
| Round-trip corpus (8 builds): recompile, run, compare output | 8 of 8 identical |
| All of `java.util`, including `concurrent` and `stream`: source files that recompile with no errors | 263 of 361 (73%) |
| 1,500 randomly corrupted class files | 0 hangs; 378 rejected with a class-level error, 845 rejected at load |

The 73% is a deliberately hard target, since that code is dense with generics, and a file with one
error counts as failed. The round-trip corpus is hand-written, so it covers known tricky cases rather
than proving behaviour matches on arbitrary code.

## Tests

```bash
bash test/verify.sh
```

Compiles `test/corpus`, decompiles it, recompiles the result, runs both versions and compares their
output, across Java 8 and 25 targets, with and without debug info. It recompiles at the same release
as the original, which is what the workbench does when you save.

The other tools in `test/` are `Stress` (decompile a whole JDK module), `Fuzz` (corrupted class files),
`GuiCheck` (drive the real window off-screen) and `RenameCheck`. Compile them first:

```bash
javac -cp out -d build/stress-classes test/*.java
```

Then run one. The classpath separator is `;` on Windows and `:` elsewhere:

```bash
java -cp "out;build/stress-classes" Stress java.base java/util
```
