# Portal entry reports arrival, not the step in; no Nether steps in the overworld; a spectator is no protection (Day to Day, 10:27-10:29 World 15)

Branch `bp/portal-entry`, stage 506.

## 1. enter_portal said done on stepping in

- **What happened.** `PortalMap.enterStep` queued a walk into the portal and a 5 s wait, and returned "stepping into portal #4". If the walk ended short, the wait ran out and the plan went on: find_fortress at 10:27:34, then blaze_rods, both in the overworld.
- **What changed.** It remembers the world he stepped in from and re-runs itself once that walk and wait are done:
  - the world changed: `done: through portal #N, in the nether now`;
  - he's standing in the portal (feet or head): wait 1 s more, up to 15 s (a player stands still about 4 s);
  - otherwise: `failed: didn't get into portal #N (I'm at X, still in the overworld)`.
  - A failed step clears the plan, so no Nether steps follow. An entry over a minute old is dropped as stale.
- **Not proven:** why the walk into the portal cell "ended at once" at 10:27:47. Nothing in the walker names portal blocks. The existing `[Claude walk] GoTo ... ended at once (... why ...)` line will show it next time.

## 2. Nether-only skills in the overworld

- **`blaze_rods` fails at once outside the Nether** ("blazes are only in the Nether: enter_portal first"), as find_fortress already did.

## 3. A spectator counted as protection

- **The planner's situation now says it outright** when you're spectating: you can't fight, block, light or protect him, mobs ignore you, so he never plans to stay near you for safety. At night his own cover is his safety.
- **A walk "near them" was already refused** with you spectating ("done: they're spectating"). The bad part was the plan's reasoning.

## Stage 506

A lit portal beside him; the order is `enter_portal`, then `blaze_rods`.
- He goes through; the step reports "done: through portal #1, in the nether now" only on arrival.
- blaze_rods never runs while he's in the overworld.
- Without the change there's no "through" report at all.

## Gate

All 20 groups are green, including 470-506.

## Restart

**Needs a game restart.**
