# Bug coverage

The user, 11-05: "I want everything that constitutes a bug logged properly." One row per kind of bug, with what catches it and where it lands.
A row whose detector is **GAP** isn't caught yet. It stays here until a detector and its stage ship.

In-game detectors file `Watchdog.incident` (a file in `<world>/claude/incidents/` with the flight recorder, plus a `[Claude incident] KIND` log line).
Log watchers (`tools/loopwatch.py`, `tools/pathwatch.py`, run by `tools/selfwatch.sh`) read the live log and print a flag line.

| Bug kind | Detector | Lands as | Stage |
|---|---|---|---|
| Death | Watchdog.death; loopwatch | incident DEATH; flag DEATH | |
| Stuck on an action (no progress) | Watchdog ladder | incident TRIP; flag STUCK | |
| A typed failure hit its counter (bandaid) | Attempts | incident BANDAID | |
| Same step result 3x inside a second | Watchdog.tick | incident INSTANT REPEAT | |
| Moved more than 3 blocks in a tick, unplanned | Watchdog.tick | incident UNPLANNED MOVE | |
| Health dropping (5+ in 10 s, not fleeing/eating) | Watchdog.tick | incident HEALTH FALLING | |
| Fighting 15 s with no effect | Watchdog.tick | incident FIGHT NO EFFECT | |
| Gear lost (not handed off) | Watchdog.tick | incident GEAR GONE | |
| Two rules fighting over one spot (3 refusals in 60 s) | MindLog.refused | log [Claude conflict]; flag CONFLICT | |
| Same line repeating (loop) | loopwatch | flag LOOP / REPEAT | |
| Standing still with a job | pathwatch | flag STILL | |
| Zigzag walking (far walked, little net) | pathwatch | flag ZIGZAG | |
| One goal more than 5 min | pathwatch | flag SLOWGOAL | |
| Unfinished tree / left blocks (litter he logs himself) | ClaudeLife; loopwatch | log [Claude litter]; flag LITTER | |
| Judgement calls an expert wouldn't make | LogReviewer (Haiku, every 5 min, ~$0.07/h, pref log_review=off) | incident REVIEW | 627 |
| Litter he logs, as an incident (each spot once per 10 min) | Watchdog line detector | incident LITTER | 644 |
| Temp block left past its use (he doesn't log it) | **GAP** | LITTER | |
| Conflict as an incident (night-hold status repeats excluded; one per wording per 10 min) | Watchdog line detector | incident CONFLICT | 644 |
| Spinning / shuffling in place | **GAP** | SPIN | |
| Any "-> failed" step line (a line and its watchdog echo are one; one per wording per 10 min) | Watchdog line detector | incident FAILED | 644 |
| Idle over 20 s with no task/step (not night hold, sleep, or planning under 45 s) | **GAP** | IDLE | |
| Furnace output left over 30 s in one of his furnaces (input empty) while not heading to it (charcoal sat there, World 17 23:35) | Watchdog.furnaceLeft | incident FURNACE_LEFT | 615 |
| Working bare-handed with the tool or its makings on him | **GAP** | BARE_HANDS | |
| Whole logs burnt as fuel | WorkGoal fuel line `[Claude smelt] fuel at X: N item for M items (whole logs)`; Watchdog line detector | incident LOG_FUEL | 615 |
| Placed and broken by him within 60 s, nothing done | **GAP** | PLACE_BREAK | |
| Walk of 16+ blocks with nothing done within 10 s | **GAP** | WASTED_TRIP | |
| Task over 2x a player-pace estimate | **GAP** | SLOW | |
| Chest/furnace use or ore targeted without line of sight | **GAP** | SIGHT | |
| Pushing into a block 1 s+ (dragging along leaves/walls) | Watchdog.dragAndWater: `[Claude move] dragging along <block>` | incident DRAG (once per block per 10 min) | 617 |
| Long spells in water (a river swum instead of bridged) | Watchdog.dragAndWater: `[Claude move] in water N s, M blocks` for every spell of 1 s+ | incident WATER (over 5 s on a walk) | 617 |

Volume (World 17 replay, 2.4 h): FAILED + CONFLICT + LITTER would have filed about 200 incidents, ~85 an hour, most of them conflicts.
