#!/bin/bash
# Restart gate: each group alone (stages leak between runs). 15 min cap per group.
cd /c/Users/lipid/Desktop/claude-mod
for g in "0 86" "99 111" "117 120" "122 125" "180 183" "199 199" "200 206" "255 258" "276 285" "295 296" "297 297" "298 299" "300 301" "350 359" "360 363" "368 371" "375 375" "420 420" "430 431"; do
  set -- $g
  out=$(timeout 900 env JAVA_TOOL_OPTIONS="-Dclaude.startStage=$1 -Dclaude.endStage=$2" bash tools/test_in_copy.sh 2>&1 | tail -3)
  echo "[$1-$2] ${out:-NO RESULT (timeout or crash)}"
done
