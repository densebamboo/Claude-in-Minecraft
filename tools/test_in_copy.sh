#!/bin/bash
# Runs the game tests in claude-mod-copies/test (a copy), so a compile never rewrites the live game's build/classes
# (the live client loads classes lazily from there: a test compile mid-run crashed it with NoClassDefFoundError).
SRC="$(cd "$(dirname "$0")/.." && pwd)"; DST="/c/Users/lipid/claude-mod-copies/test"
mkdir -p "$DST"
(cd "$SRC" && tar --exclude=./build --exclude=./run --exclude=./backups --exclude=./.gradle -cf - src build.gradle gradle.properties settings.gradle tools) | (cd "$DST" && tar -xf -)
mkdir -p "$DST/run" && cp -r "$SRC/run/gameteststructures" "$DST/run/" 2>/dev/null; cd "$DST" && ./gradlew.bat runGameTestServer --no-configuration-cache --offline -q "$@" 2>&1 | grep -E "Claude test\] (FAIL|ALL)|error:" | cut -c1-300
