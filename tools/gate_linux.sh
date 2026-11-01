#!/bin/bash
# tools/gate.sh's groups, run one by one through tools/test_in_copy.sh from this checkout (Linux: its own copy dir).
# Stages 491 on run one per server: they were written and proven alone, and in one run they trip on each other's leftovers (docs/reports/watchdog-incidents.md).
ROOT="$1"; COPY="$2"; cd "$ROOT"
G=("0 86" "99 111" "117 120" "122 125" "180 183" "199 199" "200 206" "255 258" "276 285" "295 296" "297 297" "298 299" "300 301" "350 359" "360 363" "368 371" "375 375" "420 420" "430 431" "470 489")
for s in $(seq 491 538); do G+=("$s $s"); done
for g in "${G[@]}"; do
  set -- $g
  out=$(timeout 900 env CLAUDE_TEST_COPY=$COPY JAVA_TOOL_OPTIONS="$JAVA_TOOL_OPTIONS -Dclaude.startStage=$1 -Dclaude.endStage=$2" bash tools/test_in_copy.sh 2>&1 | tail -3)
  echo "[$1-$2] ${out:-NO RESULT (timeout, crash or build failure)}"
done
