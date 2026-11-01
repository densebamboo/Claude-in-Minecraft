# Ore hole, the climb to his door, refusals back to their source, and quick fixes

Branch `bp/climb-hole`. Stages 522-524 are new. These are Day to Day's reports from 13:50 to 18:26 on World 15.

## Pathing

- **A drop in a 1-deep ore hole beside his tunnel** (the 15:17 incident file).
  - **Before:** "no way at all" to it, and the raw gold was left behind.
  - **Cause:** `digThroughToward` gave up when the drop was under 1 block away across, and a hole right beside his feet is exactly that.
  - **Now:** close by, the drop's cell decides the direction. He digs the wall cell over the hole, steps down in, and takes it. Stage 522 picks it up in about 3.6 s; it fails without the fix.
- **5 below his mine door** (16:09: "ended at once" 38 times).
  - **Cause:** `Route.expand` had no way over the land, and handed the climb to the walker, which turned it down at once.
  - **Now:** Route plans the climb itself with ActionPath (steps cut or blocks put down) and then goes in.
  - Stage 523 is a 5-up climb in a plain box. It passed before this change too, so the mine-door path itself is only checked by its log line live.

## Refusals back to their source (the four live loops)

The watchdog wrote these targets off, but the job that queued each one never asked, and queued it again every few seconds.
- **A fight through rock** (16:44, 235 lines):
  - he only clears soft blocks (leaves, dirt, sand, gravel) to reach a mob;
  - never rock, his mine's wall, or a block whose dig was just refused;
  - a mob let be behind a wall stays let be for 30 s while he can't see it, whoever queues the fight again.
- **A written-off temporary table** (13:50): neither the litter rounds (`TempLedger.takeable`) nor the "back in his pocket" round queue it again while it's written off.
- **Ore behind a hole** (16:34, 236 lines): once the ore's dig is written off, it's left in and the patch goes in, with a log line.
- **Water plugs** (18:21, 150+ repeats):
  - a plug that failed in the last 5 minutes, or that the watchdog wrote off, isn't queued again;
  - when all of them are like that, he walks out to dry ground within 5;
  - `Watchdog.off(g, task)` is the new check behind it.

Stage 524 covers the fight through rock (no dig, and the fight queued again ends) and the table (taken up normally; once written off, left for 20 s). It fails without the fix. The ore and water loops are not staged, because they need a mine blueprint; watch them live.

## Quick fixes

- **Kit:** the Nether kit's diamond pick is only for a portal's obsidian. With a lit overworld portal on record, it's not in the kit. Diamonds are still in the dragon kit.
- **Kit food:** with 6+ meals on him, a food shortfall never sends him out at night, nor up out of his own mine for mining work there.
- **enter_portal "fortress":**
  - lit portals come first;
  - with no fortress seen yet, the nearest lit portal (a way to the Nether), not a "no portal answers" failure.
- **Spare stone:** with 2+ spare stacks in his mine, it goes to the junk chest even with slots free. 331-404 cobbled deepslate sat on him with 5 slots free.

## Gate

Every group passes. 0-86 passed on its second run; the first failed "stairs turn", which is flaky on main.

## Restart

**Needs a game restart.**

## Not done here

- **The gold level as a flat landing** (14:46). That's a mine blueprint change, the next mine batch.
- **"Log what doBreak line 1550 refuses":** it already logs "can't break X: Y at Z is in the way".
