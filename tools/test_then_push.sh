#!/bin/bash
# Runs the game tests; pushes into the live game only if every stage passed.
cd "$(dirname "$0")/.."
out=$(./gradlew.bat runGameTestServer --no-configuration-cache --offline -q 2>&1 | grep -E "Claude test\] (FAIL|ALL)|error:")
echo "$out" | cut -c1-200
if echo "$out" | grep -q "ALL PASSED" && ! echo "$out" | grep -q "FAIL\|error:"; then python tools/hotswap.py; else echo "NOT PUSHED: tests failed"; exit 1; fi
