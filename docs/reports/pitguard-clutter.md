# PitGuard: only true holes capped; the grey blob taken back

Branch `bp/pitguard`. Stage 534 is new; stage 299 is superseded.

## The cause (the user, World 15: "why is there so much cobble and deepslate placed there if that's not his base")

Day to Day counted 389 blocks put down by `PitGuard.close` within about 25 of his door. The guard took any open cells below his door's level, with a 2+ drop under them, as a "hole's mouth". A mouth of 400 cells (natural ground, a dip) still got its "apron" and the strip his routes cross capped.

## What changed

- **`PitGuard.close` caps a true hole only:**
  - a mouth of 6 cells or fewer;
  - 4+ deep (past a safe drop);
  - no walk back up out of it.
  - Anything else is walked round, not capped, and logged with the mouth's size and depth.
  - Down in it already (he fell in), he still caps it, his way up.
- **The take-back round (`PitGuard.cleanup`):** his old caps are in his saved built-cells record, origin `PitGuard.close`. In daylight, when idle, every 10 s, he takes back up to 16 a round (top down, the nearest first, within 32 of him) of every cluster that was no hole's: more than 6 cells, or under 4 deep beneath. A true hole's cap stays.
- **Stage 299** expected its 189-cell hollow capped, the behavior that made the blob. It now passes on "natural ground, walked round, not capped". Its later phases (cap retries) are for true holes and no longer run there.
- **`towerTo`** settles on its foot within 0.7 (0.4 stalled now and then; stage 532 passes 4 of 4).

## Stage

| Stage | What |
|---|---|
| 534 | 10 old caps on flat ground: all 10 queued to take back; 2 over a 5-deep hole: kept |

## Gate

Every group passes. Re-runs were needed for flaky stages:
- **0-86:** "chicken under leaves" on the first run, which is flaky on main.
- **122-125:** passed on re-run.
- **532:** fixed as above.

The pit stages 245-247 aren't in the gate and fail on main too ("the mouth isn't shut"), unchanged.

## Restart

**Needs a game restart.** Then watch for `[Claude life] N of his old cap blocks by his door were no hole's (natural ground): taking them back` in daylight by his door.
