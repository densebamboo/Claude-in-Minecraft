# No-progress watchdog (queue item 1)

Branch `bp/watchdog`. Stages 474-479 are new.

## What changed and why

The old stuck check (`ClaudeLife.noticeStuck`) counted any move of 3+ blocks as progress. So a missed jump and the fall back, a shuffle, or a hop that re-picked its own target reset it forever. It also saw a re-issued task as a fresh one. That is the root cause behind all four live cases. The fix is in the progress measure and in counting repeats. A second layer on top would not fix it.

**1. Every action's own progress measure** (`ClaudeLife.noticeStuck`, `Watchdog.targetDist` and `Watchdog.key`)

- **Tasks with a target** (GoTo, Break, Place, Leap, UseDoor, Chest, Deposit, Till, WallTorch, Replant) make progress only when he gets 1+ block nearer the target than his best on that same action.
- **Same action** is decided by key (kind plus target cell), so a re-planned copy of the action counts as the same one.
- **Progress also counts** for an item gained, a block broken, or a new action.
- **Tasks with no target** keep the old move measure.
- **Exempt as before:** Wait, Eat, Flee, Attack, Fish, FurnaceTake.
- **Logging:** moving without getting nearer is logged every 10 s (`[Claude watchdog] moving but no nearer`).
- **The 20 s / 45 s / 90 s tiers are unchanged.** They now fire on the real measure.

**2. The same action repeated with no progress** (new class `entity/life/Watchdog.java`, hooked into `WorkGoal` where a task starts and where it ends)

- **Each end is checked by the action's own test:** walk arrived, block gone, block placed, leap landed.
- **Three no-progress ends of one action within 60 s trip it.** The target is marked in `Attempts` by kind (walk, reach, place, or walk at the landing).
- **The next way, rung by rung:**
  1. His own way to it: dig, bridge, pillar, swim (`ClaudeLife.escapeStuck`, now public).
  2. Go round: the target is written off, its queued copies are dropped, and the planner is woken to pick another spot.
  3. Tell them once, exactly: `stuck: <action and spot>: <N times in S s (why)>; tried <ways>. moving on ...`. Telling never blocks, and the plan carries on.
- **A tripped action is not run again as it was** for 2 minutes. Each re-pick of it climbs a rung (`Watchdog.starting`).
- **`[Claude conflict]` counts as a second signal.** Three refusals of the current action's target count as no progress on it (`MindLog.refused` calls `Watchdog.conflict`).
- **Clock path:** its second stuck on one step now says one line, once (`Watchdog.tellOnce`).

**3. The same plan step failing the same way** (`Watchdog.skillRan`, called after every skill result in `ClaudeLife`)

- **The repeat is caught even when `failCount` gets cleared.** The live furnace loop slipped past `failCount` because every successful collect cleared it.
- **2nd identical failure:** if the line names what it lacks and a collect step can get that (cobblestone or logs), he collects it first, then runs the step again.
- **The next identical failure:** the step is skipped (`failCount` set to 3), the planner gets a lesson line, and they're told once, e.g. `craft furnace keeps failing: ... missing cobblestone (3 times in N s, got more cobblestone first and it still failed) ...`.
- **A success** (`done`, `crafted` or `smelted`) clears the count.

**4. Say-do: "breaking the blocks now"** (`ClaudeChat.rtMoves`)

- **The claim is checked:** "breaking / digging / mining / chopping / clearing the ..." said as happening now is only kept when something breaking is queued or running (Break, ChopTree, MineVisibleOres, SealStep, a pillar coming down) or a dig skill is queued.
- **Otherwise the line is rewritten** to what he's really doing, plus the watchdog's line ("<action>, no nearer in N s") when he's stuck.
- **Claims about later** ("I'll be breaking ... after") are left alone.

## Stages

| Stage | Case | Without the change |
|---|---|---|
| 474 | A walk 12 off, knocked back to the start every 1.5 s (a missed jump falling back): seen stuck within 25 s | never seen stuck (he "kept moving") |
| 475 | The same sprint-jump queued again each time it ends without landing (live 00:13): trips after 3, the landing is written off, never run a 4th time as it was, told at most once | runs forever |
| 476 | The same walk to a sealed spot picked again each time it ends (live 23:56 hop): full ladder, then told once with the action, the spot and the ways tried | runs forever, nothing said |
| 477 | "breaking the blocks now" with only a walk queued is rewritten; with a Break queued it's kept; a "later" claim is kept | said as is |
| 478 | "craft furnace -> failed: missing cobblestone" again and again: 2nd time cobblestone first, then skipped and told once; a success clears it | the loop the user saw |
| 479 | Long legit work never trips: 3 obsidian with a diamond pick (9.4 s each), then a walk round a wall | (guard) |

Stage 475 uses a leap that is turned down (a 4-gap sprint-jump to +1) rather than one that misses in the air. Both end the same way for the watchdog: the leap ends and he isn't on the landing.

Stage 478 calls `Watchdog.skillRan` directly with the live failure line. It doesn't run the full craft skill on the test platform, because there the collect would mine the platform floor.

## Gate (run in this cloud session, Linux, `tools/test_in_copy.sh` per group)

GATE_RESULTS

## Restart

**Needs a game restart.** There is a new class (`Watchdog`), new static fields in `ClaudeLife`, and `escapeStuck` is now public.

## What Day to Day should watch for live

- **`[Claude watchdog] TRIP` lines.** Each one is a real loop caught. Check that the trip names the right action and that the next way (rung 1 dig/bridge, rung 2 go round) gets him out.
- **False trips on legit long work.** A walk that has to go well away from its target round a lake or a ravine for 20 s+ now counts as no progress, because the measure is straight-line distance and not path length. If that shows up live, the fix is to measure the walker's remaining route instead.
- **`ended without getting there` lines.** Walks that end at once are now logged one line per end. This could be noisy. If it is, the many walks ending at once are themselves worth a look.
- **The "telling them" lines.** There should be at most one per action every 2 minutes, never repeated, and he should never wait for an answer.
- **Rewritten "breaking" claims:** `[Claude reply truth] move claims ... nothing queued breaks a block`. Check that it doesn't over-fire on true lines that use a dig skill name missing from `ClaudeChat.rtBreaks`.

## Notes for the gate on Linux

- **The network policy must allow** maven.neoforged.net, libraries.minecraft.net, piston-meta.mojang.com, piston-data.mojang.com, launchermeta.mojang.com and resources.download.minecraft.net. Without them the build fails, and `test_in_copy.sh` prints nothing and exits 0 because its grep hides the error. Treat "no ALL line" as a failure.
- **The cloud sandbox puts its proxy and truststore settings in `JAVA_TOOL_OPTIONS`.** Add the stage flags to the end of it (`JAVA_TOOL_OPTIONS="$JAVA_TOOL_OPTIONS -Dclaude.startStage=N ..."`) rather than replacing it.
- **`tools/gate.sh` hard-codes the Windows path.** I ran its groups (plus 470-489) with the same loop from the checkout.
