#!/usr/bin/env bash
# Replays incident files (stage 625) on the current code in the test copy, one test server at a time (test.lock).
# usage: tools/replay_incidents.sh <incident.txt>...      e.g. tools/replay_incidents.sh "$INST/saves/New World (16)/claude/incidents/"20261104-*death*.txt
# Each file is copied to a path with no spaces (JAVA_TOOL_OPTIONS splits on them), with its "death kit:" line from the game logs
# (matched by the death's minute and spot) added. One line per file: PASS ... SURVIVES / FAIL ... STILL DIES / FAIL ... NOT REPLAYABLE.
# Logs: $OUT/<name>.log. Env: INST (the instance), OUT (work dir, default /c/Users/lipid/claude-mod-copies/replay).
INST=${INST:-"/c/Users/lipid/curseforge/minecraft/Instances/Claude in Minecraft"}
OUT=${OUT:-/c/Users/lipid/claude-mod-copies/replay}
LOCK=/c/Users/lipid/claude-mod-copies/test.lock; LOG=/c/Users/lipid/claude-mod-copies/test/run/logs/latest.log
mkdir -p "$OUT"
( cat "$INST/logs/latest.log"; for z in "$INST"/logs/*.log.gz; do zcat "$z"; done ) 2>/dev/null | grep -a "\[Claude life\] death kit:" > "$OUT/kits.txt"
cd "$(dirname "$0")/.." || exit 1
until ( set -o noclobber; echo replay > "$LOCK" ) 2>/dev/null; do sleep 5; done
trap 'rm -f "$LOCK"' EXIT
for src in "$@"; do
  f=$(basename "$src"); cp "$src" "$OUT/$f"
  hm=$(echo "$f" | sed -E 's/^[0-9]{8}-([0-9]{2})([0-9]{2}).*/\1:\2/')
  pos=$(grep -m1 "^where:" "$OUT/$f" | sed -E 's/^where: \S+ (-?[0-9]+, -?[0-9]+, -?[0-9]+),.*/\1/')
  k=$(grep -a "$hm:[0-9][0-9]" "$OUT/kits.txt" | grep -a "at $pos, on" | tail -1)
  [ -n "$k" ] && echo "$k" | sed 's/.*\[Claude life\] //' >> "$OUT/$f"
  rm -f "$LOG"
  JAVA_TOOL_OPTIONS="-Dclaude.startStage=625 -Dclaude.endStage=625 -Dclaude.incident=$(cygpath -m "$OUT/$f" 2>/dev/null || echo "$OUT/$f")" timeout 600 bash tools/test_in_copy.sh | grep "error:" | head -3
  if [ -f "$LOG" ]; then r=$(grep -a "Claude test\] \(PASS\|FAIL\)" "$LOG" | head -1 | sed 's/.*\[Claude test\] //'); echo "$f: ${r:-no result line}"; cp "$LOG" "$OUT/${f%.txt}.log"; else echo "$f: build failed"; fi
done
