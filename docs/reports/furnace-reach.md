# A furnace's stand spot was a lava cell (Day to Day, 07:14-08:02 World 15, 47 min lost)

Branch `bp/furnace-reach`, stage 502.

## Cause (proven, reproduced in stage 502)

- **The view check picked lava.** FurnaceTake and FurnaceLoad open the furnace only from where he can see it (`WorkGoal.reachFor`). With something in the way, they walk to `clearViewSpot`.
- **Lava has no collision box.** That check only asked for "no collision" at the spot and the cell above it, so a lava cell on stone passed: "couldn't get to 82, 56, 61 (Lava on Stone)".
- **So he walked at the lava every minute.** The walk to it failed, was re-sent, and its write-off reset each round ("trying it for real again"). The furnace never opened, so FurnaceLoad hung, then FurnaceTake.
- **The clock never saw it.** It counted every FurnaceTake as "waiting", even far from the furnace.

## What changed

- **`clearViewSpot`:**
  - never a cell holding a fluid, or with a fluid in the cell above;
  - never a spot whose walk failed in the last 2 minutes, so the write-off holds.
- **`standSpotBeside`:** "stand in the target cell itself" requires no fluid there.
- **The clock (`noticeStuck`):** a FurnaceTake counts as waiting only within 5 blocks of the furnace. On the way, it's measured like any walk. The watchdog measures FurnaceTake and FurnaceLoad by distance to the furnace.
- **Stage 502:** his furnace 3 blocks off behind a wall, with lava cells the nearest spots that see it.
  - With the change: round to a dry spot, the batch out in 2 s, never hurt.
  - Without it: FurnaceTake still hung after 30 s.

## Gate

All 20 groups are green, including 470-502.
- **122-125 failed once** ("iron level: no pair off a second tunnel"), the same flaky stage seen once before. It passed on both re-runs alone.

## Restart

**Needs a game restart.**

## Not proven

- **Who queued the 92 Pickups** with "0 drop(s) in his sweep". They came with the same walk loop and likely stop with it. I added no logging for their source.
