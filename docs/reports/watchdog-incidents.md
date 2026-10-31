# Watchdog batch 1: incident reports and every-tick detectors

Branch `bp/watchdog-incidents`. Stage 519 is new.

The user asked to "make the watchdog more robust and account for more scenarios so you can piece together more context". The first watchdog only watched one task's progress. This batch adds two things:
- detectors for what no single task watches;
- one self-contained incident block per event, so Day to Day can read what happened without stitching the log together.

## The incident block

`Watchdog.incident(g, kind, why)`:
- logs `[Claude incident] KIND: why (file ...)`, then the block;
- writes the block to `saves/<world>/claude/incidents/<yyyyMMdd-HHmmss>-<kind>.txt` (not in tests).

The block holds:
- **when:** the tick and the day time;
- **where:** his dimension, cell and the block under him, plus whether he's in lava or on fire;
- **last 30 s:** one sample a second, with health, food, fire and position;
- **goal:** his goal, the user's goal, and whether he's on their job or holding on their "stay";
- **step:** the plan step, its last result line and the plan queue;
- **task:** the current task with its age, and the first 6 queued;
- **hostiles known within 24:** with distance and line of sight;
- **watchdog state:** trips, what is tripped, the last line told;
- **his recent log** (`MindLog.recent()`).

Watchdog trips (`TRIP`) and deaths (`DEATH`, from `ClaudeEntity.die`) write one too.

## The detectors (`Watchdog.tick`, every tick from `WorkGoal.reflexes`)

| Kind | Fires when | Then |
|---|---|---|
| UNPLANNED MOVE | 3+ blocks in one tick, in the same world, not a leap and not his own catch-up teleport (`Watchdog.teleported`, called by `ClaudeLife.relocateNear`); at most every 5 s | logged |
| HEALTH FALLING | 5+ health lost in 10 s while not fleeing or eating; at most every 30 s | logged |
| FIGHT NO EFFECT | 15 s in one Attack with the target's health never going down; at most every 30 s | logged, and the fight is dropped (`dropCurrent`) |
| GEAR GONE | an iron-or-better tool, weapon or armor piece, a shield, a bow or crossbow, or flint and steel fewer than a second ago | logged with the task and step it happened under |
| INSTANT REPEAT | the same skill result line 3 times running, each within 1 s (`Watchdog.quickReturn`, from the skill runner); the live 12:30 "collect beef" ×9 in 2 s | logged |

What GEAR GONE leaves out:
- **Handing over:** a Toss (give), Chest or Deposit task in the last 2 s.
- **Wearing out:** a tool under 10% durability doesn't count as gear, so its breaking isn't a loss.
- **Valuables** (diamonds, rods, pearls, eyes, buckets): crafts and throws use them up, so a loss can't be told from a use.

Dropped during the work: an EMPTY FURNACE detector. `doFurnaceTake` already ends at once on an empty furnace in reach, so the detector could only fire mid-walk, as noise.

## Stage

| Stage | What | Without the change |
|---|---|---|
| 519 | An invulnerable zombie 2 off, attacked: FIGHT NO EFFECT once, after 15 s or more. His iron pick removed: GEAR GONE; a worn-out shovel removed: nothing. Health 20 → 13: HEALTH FALLING. A step line 3 times in a tick: INSTANT REPEAT, but not for two lines in turn. 8 blocks in one tick: UNPLANNED MOVE, but not for his own catch-up teleport | no incidents |

`scenarioReset` now also resets the watchdog, so a stage's own inventory clears and teleports aren't read as losses or moves.

## Gate (and a gate bug found on the way)

**The gate group "470-518" had been running only 470-489.** There is no stage 490. A ranged run that reached a missing number ended with "ALL PASSED", so 491 on never ran in the group. My earlier reports listed 491-518 as passing in the gate; those stages had only been proven one at a time. Now `skipGap` moves on to the next number until the end stage.

Running 491-519 in one go then showed real failures, fixed on this branch:
- **485, death in the water-bucket clutch:** his bucket upkeep scooped the clutch water back up mid-fall. Now there's no bucket fill in the air (`ClaudeLife.fillBucket`). The new incident block showed this straight away.
- **500, flaky even alone (3 of 4):** the water pushed him off his obsidian pocket, and the walk back into the water failed 3 of 3, which ended the order at 3-9 of 10. Now:
  - go_to exact steps straight into a cell touching his, level or one down (`ExactSpot.queueWalk`);
  - make_obsidian walks back to its dry spot first when he's pushed off.
  - 6 of 6 alone since.
- **501:** stage 500's lava on record held 501's dig. `scenarioReset` now clears the lava he's seen, his motion and his fall distance.

**Still order-dependent:** in one run of 470-519, stages 495 and 500 fail 1-2 times in 3. The causes are leftovers (he starts 495 on the far side of the stone, and 500 ends early after the stages before it). Both pass every time from a fresh server.

So the gate (`tools/gate_linux.sh`, now in the repo) runs 470-489 as one group and each stage from 491 on in its own server.

**Results on `bp/watchdog-incidents`:** every group passes.
- **0-86:** passed on the third run. The first run failed "dig-in", the second "stairs turn"; both are flaky on main too.
- **99-111 through 470-489:** all passed.
- **491 to 519:** each passed alone.

## Restart

**Needs a game restart.** There are new fields and methods in `Watchdog`, and new hooks in `WorkGoal`, `ClaudeEntity` and `ClaudeLife`.

## What Day to Day should watch for live

- **`[Claude incident]` lines and the `claude/incidents` folder in the world save.** Each file is the context for one event. Send the file rather than log excerpts.
- **UNPLANNED MOVE on a knockback.** A blaze fireball or a creeper blast moves him under 3 blocks a tick, so most knockbacks won't show. The fall log (`[Claude move] falling`) still covers those.
- **HEALTH FALLING in long fights.** It's skipped only while fleeing or eating, so a hard fight he's winning will log one every 30 s. That's by design (it's context, it changes nothing), but say if it's noise.
- **FIGHT NO EFFECT on mobs behind a block.** It drops the fight. If he keeps re-picking the same unreachable mob, the next batch should mark it in `Attempts`.

## Not done here

- **The order-dependence of 491-519 in one run:** the leftovers above.

## Next batches (from Day to Day's spec)

- order vs action mismatch;
- the dimension he's in vs the one the step needs;
- chat claims vs the world;
- spins (net-zero walking);
- plans missing inputs;
- standing by a hazard;
- the last 60 s at a death;
- the despawn countdown on dropped gear.
