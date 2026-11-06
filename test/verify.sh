#!/bin/bash
# Round trip: compile corpus -> decompile -> recompile the decompiled source -> run both -> compare output.
root=$(cd "$(dirname "$0")/.." && pwd); w=$root/build/verify; rm -rf "$w"; pass=0; fail=0
check() { # label release file (-g adds debug info when $4 is set)
  label=$1; rel=$2; src=$3; dbg=$4; main=$(basename "$src" .java); d=$w/$label; mkdir -p "$d/a" "$d/src" "$d/b"
  javac --release $rel $dbg -nowarn -Xlint:none -d "$d/a" "$src" 2>/dev/null || { echo "$label: corpus failed to compile"; return; }
  java -cp "$root/out" regrind.Main "$d/a" -o "$d/src" 2>"$d/decomp.log"; tail -1 "$d/decomp.log"
  grep -E "fallback|crash" "$d/decomp.log"
  if javac --release $rel -nowarn -Xlint:none -d "$d/b" $(find "$d/src" -name '*.java') 2>"$d/recompile.log"; then
    java -ea -cp "$d/a" $main >"$d/a.out" 2>&1; java -ea -cp "$d/b" $main >"$d/b.out" 2>&1
    if cmp -s "$d/a.out" "$d/b.out"; then echo "PASS $label"; pass=$((pass+1)); else echo "FAIL $label: output differs"; diff "$d/a.out" "$d/b.out" | head -20; fail=$((fail+1)); fi
  else echo "FAIL $label: recompile"; grep -E "error:" "$d/recompile.log" | sed "s/.*[/\]//" | head -${MAXERR:-25}; fail=$((fail+1)); fi
}
for f in "$root"/test/corpus/t8/*.java; do n=$(basename "$f" .java)
  check "$n-8" 8 "$f" ""; check "$n-25" 25 "$f" ""; check "$n-25g" 25 "$f" "-g"; done
for f in "$root"/test/corpus/t25/*.java; do n=$(basename "$f" .java)
  check "$n-25" 25 "$f" ""; check "$n-25g" 25 "$f" "-g"; done
echo "passed=$pass failed=$fail"
