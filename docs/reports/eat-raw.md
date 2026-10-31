# Safe raw food eaten when hungry (Day to Day, 11:20 World 15)

Branch `bp/eat-raw`, stage 509.

## Cause

- **The eat skill refused safe raw food below full.** With nothing to cook in, it ate safe raw food (fish, beef, pork, mutton) only at food 14 or lower, or 17 or lower when hurt. From 15 to 19 it returned "failed: can't cook the salmon yet ... eat it raw only if starving". That wording also taught the planner a wrong rule.
- **The user's rule is "eat below full".** A player eats safe raw food when hungry.
- **Not reproduced:** the live report says food 3. At food 3 the code already ate the raw salmon when nothing could cook it. I can't see the log line that would explain food 3; the stage reproduces the exact live failure line at food 16.

## What changed

- **Food 6 or less** (he can't sprint): safe raw food is eaten now, even with a furnace by him. Cooking can wait.
- **Below full with nothing to cook in:** safe raw food is eaten.
- **Raw chicken still waits** for a furnace, unless he's at 4 health or less. The failure line says why ("raw chicken can poison me"). The "eat it raw only if starving" wording is gone.

## Stage 509

- **Food 16, salmon, logs, no furnace:** eaten raw. Without the change: the live failure line, word for word.
- **Food 3, a furnace by him:** eaten now.
- **Raw chicken at food 10, health 20:** not eaten raw.

## Gate

All 20 groups are green, including 470-509.
- **Hazard 485** (the water-bucket fall) failed once in 470-509, then passed twice on re-runs.

## Restart

**Needs a game restart.**

## Not done

- **"No furnace nearby" doesn't count his furnaces in his mine nook,** per the storage-is-inventory rule, before falling back to 8 cobblestone.
- **The planner schedules crafts and smelts whose inputs he lacks** (Day to Day's later note). The watchdog's `obtain` next way from `bp/craft-obtain` already turns a repeated missing-ingredient craft into obtain. Checking needs before scheduling is still open.
