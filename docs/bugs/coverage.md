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
| Temp block left past its use | TempLedger.tick logs `[Claude litter] left his X at P standing` once he's 48+ off; line detector | incident LITTER | 644 |
| Conflict as an incident (night-hold status repeats excluded; one per wording per 10 min) | Watchdog line detector | incident CONFLICT | 644 |
| Spinning / shuffling in place | ClaudeEntity `[Claude move] spin/shuffle` lines; line detector (one per wording per 10 min) | incident SPIN | 556 |
| Any "-> failed" step line (a line and its watchdog echo are one; one per wording per 10 min) | Watchdog line detector | incident FAILED | 644 |
| Idle 20 s+: no task, nothing queued, standing (not night hold, asleep, staying, or thinking under 45 s) | Watchdog.idle | incident IDLE | 556 |
| Furnace output left over 30 s in one of his furnaces (input empty) while not heading to it (charcoal sat there, World 17 23:35) | Watchdog.furnaceLeft | incident FURNACE_LEFT | 615 |
| Wrong tool (a 2x+ faster one in his bag), or 4+ axe/pick blocks by hand with the makings of a tool on him | Watchdog.brokeOwn (break event) | incident BARE_HANDS | 556 |
| Whole logs burnt as fuel | WorkGoal fuel line `[Claude smelt] fuel at X: N item for M items (whole logs)`; Watchdog line detector | incident LOG_FUEL | 615 |
| Placed and broken by him within 60 s, nothing broken or gained between | Watchdog.placedOwn / brokeOwn (place and break events) | incident PLACE_BREAK | 556 |
| Walk of 16+ blocks with nothing broken, gained or handed over within 10 s, no work at that spot (not explore/go_to/follow, their job, the night) | Watchdog.endChecks + wastedTrip | incident WASTED_TRIP | 800 |
| A walk or a dig over 1.5x a player's time and 10 s+ over (walk: distance / 5.6 b/s; dig: vanilla break time with his best tool, plus the walk into reach) | Watchdog.paceAndSight + endChecks | incident SLOW (one per kind of task per 10 min) | (long walks are split into legs, so the step check below is what catches a slow walk) |
| PACE: a plan step over 1.5x and 10 s+ over its player-pace budget (its walks and digs at their estimates, a placement 0.5 s, a chest or furnace 1 s, plus 1 s; night holds, sleep and stays don't count). The incident says where the time went: walking, digging, waiting, fighting, no task (idle or re-planning) | Watchdog.pace | incident SLOW (key SLOW\|step\|skill) | 800, 801 |
| Waiting at a furnace 20 s+ with nothing else done | Watchdog.furnaceWait | incident FURNACE_WAIT | 801 |
| A smelt load with 8+ more of the input on him or in his chests and no other load of it queued | Watchdog.endChecks | incident SMALL_BATCH | 801 |
| One of his furnaces cooking while 2+ of his within 6 stand empty and the input is still on him | Watchdog.idleFurnaces | incident IDLE_FURNACES | 801 |
| The same item out of the same chest twice within 5 min | Watchdog.endChecks | incident REPEAT_FETCH | 801 |
| Out and back: a walk ending within 3 of where an 8+ walk began, inside 60 s, nothing done | Watchdog.backtrack | incident WASTED_WALK | 801 |
| Off to a furnace/chest 10+ away with an empty furnace / a chest of his within 6 | Watchdog.paceAndSight | incident FAR_STATION | 801 |
| The same smelt/craft/collect/strip_mine/fetch job as a new step within 5 min of the last | Watchdog.pace | incident SPLIT_JOB | 801 |
| Walked over 1.5x (and 8+ more than) the first path planned for a walk | Watchdog.walkLedger + endChecks | incident ROUTE_DETOUR | (no stage yet) |
| Ore targeted with every face closed; a chest/furnace used (items changed) from 6 or less without line of sight | Watchdog.paceAndSight + endChecks | incident SIGHT | 556 (ore) |
| Pushing into a block 1 s+ (dragging along leaves/walls) | Watchdog.dragAndWater: `[Claude move] dragging along <block>` | incident DRAG (once per block per 10 min) | 617 |
| Long spells in water (a river swum instead of bridged) | Watchdog.dragAndWater: `[Claude move] in water N s, M blocks` for every spell of 1 s+ | incident WATER (over 5 s on a walk) | 617 |

Volume (World 17 replay, 2.4 h): FAILED + CONFLICT + LITTER would have filed about 200 incidents, ~85 an hour, most of them conflicts.
