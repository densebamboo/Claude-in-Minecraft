# Lava crossings as pieces of highway; two walking fixes

Branch `bp/highway-crossing`.

## The user's rule (via Day to Day)

"They should be the same blueprint as the highway imo ... That way he can connect them cleanly later."

## What changed

- **A crossing is a piece of highway** (`ActionPath.crossings`, `crossingTasks`). A walk's plan with 3+ flat blocks put down over lava in a straight line becomes highway rows:
  - each row's floor as wide as his nearest portal's frame (`BridgeBlueprint.frameWidth`: 4 by default, 5 for a 5x5), laid from his line outward;
  - a rail on each side at floor + 1. The first row's rails get a block beside the floor under them, since there's nothing else to place against; each rail after goes against the one before;
  - then a walk onto the row.
- **Kept as his build:** `TempLedger.planBuild` books the cells before the Places run, so none are taken back up.
- **On record:** `PortalMap.Segment` (dim, end rows' centre floor cells, axis, width) is saved with the portal network. `addSegment` extends a segment on the same axis, line and floor instead of adding a parallel one. `segmentFor` lines a new crossing up with one within its width and 8 of its ends.
- **Short of blocks** for rows × (width + 2) + 2: 2 wide as before, with a log line saying so.
- **The Nether plan prices it:** a lava cell is 6 blocks put down (`NetherRoute`), so land wins where there is any.
- **No jump at a path step 2+ up** (`Controls.apply`). Stage 538's 15 HP fall proved this from the new "last tick on the ground" line: standing on a 1-block bridge 18 up, the path's next node was 2 up, diagonally, and the hop carried him off. He now stands, and the walk's planner cuts steps or towers. Ladders and vines are excluded.
- **A diagonal past lava's corner goes round the other corner** (`Controls.apply`). With the fall gone, 538 stood still for 2 min at -49 102 89: the path's diagonal clipped a lava pool's corner and the lava guard stopped him every tick.
- **Every fall logs the last tick on the ground:** position, motion, the walk vector, the path's next node, sprint, jump, safeStep and the move goal.

## Stages

- **541 (new):** a 12-wide lava channel in the Nether. Across in 25 s: 12/12 rows 4 wide, all railed, still standing 15 s on, 1 segment on record "4 wide along x". It used 74 blocks.
- **538:** 174 s twice, no damage. It was 194-319 s with a 15 HP fall.
- Green alone: 520, 521, 525, 532, 533, 535-537, 539, 540.
- The full gate is below.

## Not done

- No stage yet for a second crossing snapping onto a first. `segmentFor` and `addSegment` are in, and the 541 segment is checked.
- The planner prompt doesn't yet know crossings are highway pieces.

## Restart

Needs one: new fields (`PortalMap.Net.segments`, `Controls.LAST_GROUND`, `TempLedger.plannedBuild`) and new classes.

## Gate

Full Linux gate: 69 of 71 groups green on the first run.

- **500** (portal obsidian, 9 of 10 in 150 s): green 2 of 2 on re-runs. A one-off.
- **0-86:** failed with "chicken under leaves", "dig in: died" and "stairs turn", all on the known-flaky list. It passed 1 of 4 on this branch against 3 of 3 on main, so I bisected:
  - this branch with main's `Controls.java` still failed 2 of 3;
  - **main's code with only this branch's test file failed 1 of 3.**

  The group is timing-sensitive to the test file itself (as 532 was), not to this code. Stage 12 alone passes 4 of 4. That's worth a look on its own: the 0-86 stages leak into each other more as the file grows.
