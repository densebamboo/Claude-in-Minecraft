#!/bin/bash
# Runs the game tests in claude-mod-copies/test (a copy), so a compile never rewrites the live game's build/classes
# (the live client loads classes lazily from there: a test compile mid-run crashed it with NoClassDefFoundError).
SRC="$(cd "$(dirname "$0")/.." && pwd)"; DST="${CLAUDE_TEST_COPY:-$( [ -d /c/Users/lipid ] && echo /c/Users/lipid/claude-mod-copies/test || echo "$HOME/claude-mod-copies/test" )}"
mkdir -p "$DST" && rm -rf "$DST/src" # (a fresh src each run: files another branch left behind compiled in, a stale Parked.java broke a main build)
(cd "$SRC" && tar --exclude=./build --exclude=./run --exclude=./backups --exclude=./.gradle -cf - src build.gradle gradle.properties settings.gradle tools gradlew gradlew.bat gradle) | (cd "$DST" && tar -xf -)
rm -rf "$DST/run/world" # a fresh world each launch: the old one kept every run's persistent mobs in the arenas and his saved state since 11-05, so a stage passed or failed by what ran before it
[ -f "$DST/run/server.properties" ] && sed -i 's/^level-seed=.*/level-seed=claude-tests/' "$DST/run/server.properties" # one seed every launch: a fresh world with a random seed gave the stages over natural ground (816, 819) different land each run
mkdir -p "$DST/run" && cp -r "$SRC/run/gameteststructures" "$DST/run/" 2>/dev/null; GW=./gradlew; OFF=""; [ "$OS" = "Windows_NT" ] && GW=./gradlew.bat && OFF=--offline; cd "$DST" && chmod +x ./gradlew 2>/dev/null; $GW runGameTestServer --no-configuration-cache $OFF -q "$@" 2>&1 | grep -E "Claude test\] (FAIL|ALL)|error:" | cut -c1-300
