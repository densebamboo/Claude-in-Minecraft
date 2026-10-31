#!/bin/bash
# Runs the game tests in claude-mod-copies/test (a copy), so a compile never rewrites the live game's build/classes
# (the live client loads classes lazily from there: a test compile mid-run crashed it with NoClassDefFoundError).
SRC="$(cd "$(dirname "$0")/.." && pwd)"; DST="${CLAUDE_TEST_COPY:-$( [ -d /c/Users/lipid ] && echo /c/Users/lipid/claude-mod-copies/test || echo "$HOME/claude-mod-copies/test" )}"
mkdir -p "$DST"
(cd "$SRC" && tar --exclude=./build --exclude=./run --exclude=./backups --exclude=./.gradle -cf - src build.gradle gradle.properties settings.gradle tools gradlew gradlew.bat gradle) | (cd "$DST" && tar -xf -)
mkdir -p "$DST/run" && cp -r "$SRC/run/gameteststructures" "$DST/run/" 2>/dev/null; GW=./gradlew; OFF=""; [ "$OS" = "Windows_NT" ] && GW=./gradlew.bat && OFF=--offline; cd "$DST" && chmod +x ./gradlew 2>/dev/null; $GW runGameTestServer --no-configuration-cache $OFF -q "$@" 2>&1 | grep -E "Claude test\] (FAIL|ALL)|error:" | cut -c1-300
