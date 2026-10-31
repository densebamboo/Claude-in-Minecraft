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
- **Only real attempts are counted:** a leap off its landing, or a walk that ran over 3 ticks and ended no nearer than it began, with nothing broken or picked up.
- **Three no-progress ends of one action within 60 s trip it.** The target is marked in `Attempts` by kind (walk, reach, place, or walk at the landing).
- **The next way, rung by rung:**
  1. His own way to it: dig, bridge, pillar, swim (`ClaudeLife.escapeStuck`, now public).
  2. Go round: the target is written off and its queued copies are dropped. Planners read the Attempts mark and pick another spot.
  3. Tell them once, exactly: `stuck: <action and spot>: <N times in S s (why)>; tried <ways>. moving on ...`. Telling never blocks, and the plan carries on.
- **A tripped action is not run again as it was** for 2 minutes. Each re-pick of it climbs a rung (`Watchdog.starting`).
- **`[Claude conflict]` lines are logged against the running action** (`Watchdog.conflict`) but not acted on (see Gate).
- **Clock path:** an action the 45 s tier drops is written off on the same ladder (`Watchdog.writtenOff`), so picking it again tells them once. Its second stuck on one step also says one line, once (`Watchdog.tellOnce`).

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
| 474 | A walk 14 off, knocked back to the start every 1 s (a missed jump falling back): seen stuck within 27 s | never seen stuck (he "kept moving") |
| 475 | The same sprint-jump queued again each time it ends without landing (live 00:13): trips after 3, the landing is written off, never run a 4th time as it was, told at most once | runs forever |
| 476 | A walk that never gets nearer, picked again each time it's dropped (live 23:56 hop): escape plan at 20 s, dropped at 45 s, told once with the action, the spot, the seconds and the ways tried | the escape plan resets the clock every 20 s, forever, nothing said |
| 477 | "breaking the blocks now" with only a walk queued is rewritten; with a Break queued it's kept; a "later" claim is kept | said as is |
| 478 | "craft furnace -> failed: missing cobblestone" again and again: 2nd time cobblestone first, then skipped and told once; a success clears it | the loop the user saw |
| 479 | Long legit work never trips: 3 obsidian with a diamond pick (9.4 s each), then a walk round a wall | (guard) |

Stage 475 uses a leap that is turned down (a 4-gap sprint-jump to +1) rather than one that misses in the air. Both end the same way for the watchdog: the leap ends and he isn't on the landing.

Stage 478 calls `Watchdog.skillRan` directly with the live failure line. It doesn't run the full craft skill on the test platform, because there the collect would mine the platform floor.

## Gate (run in this cloud session, Linux, `tools/test_in_copy.sh` per group)

Every group passes on `bp/watchdog`.

| Group | Branch | Notes |
|---|---|---|
| 0-86 | ALL PASSED (2 of 4 runs) | Flaky on main here too: main passed 2 of 4 runs (it failed "dig in: died", "chicken under leaves" and "stairs turn"). The branch failed the same stages ("dig in: died", "stairs turn") plus "sunk tree" once. The dig-in death has no watchdog line in its log (a Flee from husks, which is exempt). |
| 99-111, 117-120, 122-125, 180-183, 199, 200-206, 255-258, 276-285, 295-296, 297, 298-299, 300-301, 350-359, 360-363, 368-371, 375, 420, 430-431 | ALL PASSED | same on main |
| 470-489 (incl. new 474-479) | ALL PASSED | 474-479 also pass on their own |

The first full run found three regressions, which are fixed on this branch:
- **Sunk tree (0-86):** a dig re-queued behind the block in front was counted as no progress.
- **Nook move (200-206):** a placement's "nudge to a stand spot" exit was counted, and the rung climbed with no cap.
- **Day / walked off (180-183):** a follow walk the walker turns down at once was counted, then the planner was woken.

Now:
- **Only leaps, and walks that ran and ended no nearer, are counted.** Instant walk refusals already have the walker's own "skipped this time (n of 3)" guard.
- **No planner wake. The rung is capped at 3.**
- **Conflicts are logged, not acted on.** A refusal is one of his own rules holding him, and digging or going round would walk him through it.

The first run also showed a loop on main itself. The 20 s escape plan's own walk steps reset the clock ("unstuck: moving again"), so he planned an escape every 20 s forever and never dropped the action or told them. The clock is now pinned to the goal the escape serves until he gets nearer it (stage 476).

## Restart

**Needs a game restart.** There is a new class (`Watchdog`), new static fields in `ClaudeLife`, and `escapeStuck` is now public.

## What Day to Day should watch for live

- **`[Claude watchdog] TRIP` lines.** Each one is a real loop caught. Check that the trip names the right action and that the next way (rung 1 dig/bridge, rung 2 go round) gets him out.
- **False trips on legit long work.** A walk that has to go well away from its target round a lake or a ravine for 20 s+ now counts as no progress, because the measure is straight-line distance and not path length. If that shows up live, the fix is to measure the walker's remaining route instead.
- **`ended short, not counted` lines.** Walks that end at once are logged one line per end. This could be noisy. If it is, the many walks ending at once are themselves worth a look (the gate's day stage has a follow walk turned down 3 times a second).
- **The "telling them" lines.** There should be at most one per action every 2 minutes, never repeated, and he should never wait for an answer.
- **Rewritten "breaking" claims:** `[Claude reply truth] move claims ... nothing queued breaks a block`. Check that it doesn't over-fire on true lines that use a dig skill name missing from `ClaudeChat.rtBreaks`.

## Notes for the gate on Linux

- **The network policy must allow** maven.neoforged.net, libraries.minecraft.net, piston-meta.mojang.com, piston-data.mojang.com, launchermeta.mojang.com and resources.download.minecraft.net. Without them the build fails, and `test_in_copy.sh` prints nothing and exits 0 because its grep hides the error. Treat "no ALL line" as a failure.
- **The cloud sandbox puts its proxy and truststore settings in `JAVA_TOOL_OPTIONS`.** Add the stage flags to the end of it (`JAVA_TOOL_OPTIONS="$JAVA_TOOL_OPTIONS -Dclaude.startStage=N ..."`) rather than replacing it.
- **This box is flaky on 0-86 for main too.** Two servers at once make it worse, so run the groups one at a time.
- **`tools/gate.sh` hard-codes the Windows path.** I ran its groups (plus 470-489) with the same loop from the checkout.
