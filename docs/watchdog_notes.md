# No-progress watchdog: notes for the cloud session (Big Picture, 2026-10-31)

Status: design plus one **untested** draft, `specs/life/patch_watchdog.py`. It hasn't been compiled or run.

## What already exists on main
`ClaudeLife.noticeStuck` runs every 20 ticks. It has three tiers:
- **20 s:** `escapeStuck` (an ActionPath MAY_DIG plan to the task's target, put in front of the task).
- **45 s:** the task is dropped and marked in Attempts (BREAK). Three stucks on one step drop the plan.
- **90 s:** WALK/PLACE/BREAK are marked failed and everything is replanned.

It exempts Wait, Eat, Flee, Attack, Fish and FurnaceTake.

**The flaw:** progress means moving 3+ blocks from `progressPos`, gaining an item, or breaking a block. Any back-and-forth resets it:
- the 00:13 missed jump (5 times in 10 s);
- the 23:56 hop re-picking its own target;
- shuffles and spins.

So the root fix is the progress measure, not a new layer.

## Progress measure per action (the draft)
- **Target tasks:** GoTo(pos), Break(pos), Place(pos), Leap(land), UseDoor, Chest, Deposit, Till, WallTorch, Replant.
  - Progress only when he gets **1+ block closer than his best on that same task** (`progBest`, keyed by bot).
  - Also on a new task (by record equality), an item gained, or a block broken.
  - Moving without getting nearer is logged every 10 s ("moving but no nearer: best X now Y").
- **Other tasks** (Pillar, ChopTree, MineVisibleOres, SealStep, Pickup, Smelt, CheckForCave): the old 3-block move measure.
  - Smelt stays here on purpose: it can wait at the furnace.
- **Exempt** as now: Wait, Eat, Flee, Attack, Fish, FurnaceTake.
  - Better later: Attack progress = the target's health falling or the target dead; Flee progress = distance from the threats growing.
- **Break in reach:** a hard block (obsidian with a diamond pick is ~9.4 s) shows progress only at completion. The 20 s tier covers it. Better: read the crack stage (`clearCrack`/crack progress in WorkGoal) as progress.

## Telling them
- The second stuck on the same step (`failCount "stuck:<skill>"` == 2): say once, exactly, "stuck: <describe(task)> at x y z, no closer in N s; trying another way".
- The 90 s tier must never stop to wait for an answer. An earlier user call: he once sat 5 min on an arch block. Telling, never blocking.

## Ways to try, in order (the "next way" ladder)
1. Walk (NO_DIG ActionPath).
2. MAY_DIG: dig, bridge, pillar, swim. This is `escapeStuck`.
3. Round: mark the target cell in Attempts and let the planner pick another spot for the same goal (another tree, another ore face, another stand spot).
4. Say what failed, once, with the ways tried. Replan.

Use `Attempts` kinds (WALK, PLACE, BREAK, REACH) for every mark. `MindLog.refused` / `[Claude conflict]` lines are a second signal: three refusals of the same thing in 60 s count as no progress on it.

## Say-do link
The watchdog must know his real current action (`g.currentTask` + `ClaudeChat.describe`). The say-do pieces already sent (`patch_say_do.py`: acknowledgments, rule-ahead, hold notice, report truth) read the same state. A dropped order should also go through `holdNotice`-style "said once with the reason".

## Stage ideas
- **474 (in the draft):** a GoTo 12 off on open ground, teleported back to the start every 30 ticks (a missed jump falling back). Expect `stuckLevel >= 1` within 25 s. Main never trips: he keeps "moving".
- **Missed jump for real:** a 2-gap with a 1-up landing that's too far. The walker tries the leap and misses. Expect a stuck within 20 s and the next way taken (pillar, bridge or go round), never the same leap 3 times.
- **Hop loop:** a long walk whose hop resolves to its own target (the 23:56 case). Expect the watchdog to trip even if the WalkGoal fix regresses.
- **Spin on a slope:** the `[Claude move] spin` lines (280-300 degrees in 9-34 ticks). Expect no stuck on a slope that's walkable. The spin is a steering bug for its own stage.
- **Long legit work never trips:** strip-mining a 30-block tunnel, chopping a big oak, smelting 32 at one furnace, an obsidian break with a diamond pick.
- **Telling:** the second stuck on one step says one line naming the task, the spot and the seconds. It's never repeated, and it never waits for an answer.
- **Attack with no progress:** a target out of reach (a phantom circling) and nothing landing for 20 s. Expect a switch: the 17:09 self-defence case generalised.

## Free stage numbers
474 was free at 00:40. 470-473 and 480-489 are taken: portal memory, say-do, hazards.
