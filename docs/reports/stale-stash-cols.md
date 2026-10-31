# Base stations off chests in solid rock; the watchdog fooled by a step cut and undone (Day to Day, 09:48 World 15)

Branch `bp/stale-stash-cols`, stages 503 and 504.

## 1. Base stations in uncarved rock

- **What happened.** Dumping loot into his stash chest calls `baseStations` (table and furnace wall) off `storageCols()`. That's a static list of two chest columns, remembered from whichever chest setup ran last. The columns' x/z were combined with the **current** chest's y.
  - Live, the cells came out in solid rock 37 blocks under him: "placing crafting_table at 31, 16, 228 ... the cell's filled already, stone there".
  - Then came a walk down to them from the surface.
- **What changed.**
  - stashLoot lays stations only when that chest is one of the recorded columns (same x/z, within 2 in y), and logs when it isn't.
  - `baseStations` itself refuses unless the tunnel cell in front of the chests is carved (open, no fluid) and he's within 8 blocks. It logs why.
  - These hold whatever produced the bad cells. Which chest at y 16 he was dumping into isn't proven (one of his own, or a structure chest he had on record).
- **Stage 504:** chests in solid rock under him: nothing queued. Without the guard: a Break, a walk, a place.

## 2. The watchdog fooled by a step cut and undone

- **What happened.** A walk end counted only if it ended no nearer than its own start. A one-block step down, climbed back the next moment, passed that every time: "ended short, not counted (got nearer)". Cutting that step also broke a block, which counted as gaining something. That's 21 "stepping off" lines in 80 s, net nothing.
- **What changed.**
  - A walk end is measured against his **best** distance on that walk within the 60 s window: nearer by a block and its count starts over, otherwise it counts. Blocks broken on the way don't count as gain for a walk.
  - The started action is matched by value, not by object identity, as the watchdog's keys already were.
- **Stage 503:** a far walk, one step down and back up, 4 times.
  - With the change: it trips.
  - Without it: never.

## Gate

All 20 groups are green, including 470-504.

## Restart

**Needs a game restart.**

## Not done

- **Item 2:** "getting down" to a target far below by his mine's stairs, or a planned staircase via ActionPath, instead of cutting one step at a time. The watchdog now catches the loop, and the stations no longer send him there, but the walker itself is unchanged.
