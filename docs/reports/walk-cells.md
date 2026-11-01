# Refused steps and a planned walk's own cells

Branch `bp/walk-cells` (with `bp/refused-step`). Day to Day's live reports, 2026-11-01: 3 stalls by the fortress, one of 8 min at -29 70 14 under the 2-up guard; 03:53 a vanilla path over a lava drop that ActionPath had turned down.

## The cause

- The 2-up guard (and the lava guard) in `Controls.apply` stop him. `WorkGoal.walkTo` only plans with ActionPath when the vanilla path doesn't reach, and that path said it did, so nothing took over till the 20 s give-up.
- Once ActionPath did plan a plain walk, it handed over a waypoint every 6 cells. The vanilla path between them took the same 2-up node again.

## What changed

- **A step his body refuses** (2+ up, lava ahead or below) marks the path as not reaching (`Controls.refusedLately`): the walk's own plan takes it within a second. This sits alongside Day to Day's 0cda90e (a vanilla path with a 2-up node doesn't count as reaching) and their height fix (from his real y, 1.25).
- **A planned walk's steps up and down are its waypoints** (`ActionPath.tasks`): both cells of each step, so the vanilla path only ever fills in level ground between them.

## Stages and gate

- 538 (World 15's basalt pocket to the near fortress): 158-167 s, no damage, with 14-19 refused steps a run, each one planned round. It was 174 s before this branch.
- 535 and 541 green.
- The full Linux gate on 1dd2b3e: 70 of 71. The one red, 0-86 "stairs turn", is the known group flake (`docs/reports/highway-crossing.md`). Green on the merge with main up to 70e478e: 535, 538, 541.

## Restart

Needs one: a new field in Controls.

## For Day to Day to watch live

- `[Claude walk] his body refused the path's step on the walk to X ...: the walk's own plan takes it`
- No more "no jump makes that" loops at -29 70 14.
