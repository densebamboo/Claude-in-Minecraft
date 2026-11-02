# Nether route legs: climbs, failed spots, quieter logs

Branch `bp/route-climbs`. Day to Day's live reports.

- **A climb or drop of 2+ is its own leg** (`NetherRoute.done`): both its foot and its top are leg ends, so ActionPath plans the steps or the pillar. Live 15:04:48 a leg ended 3 above the one before; his body refused the jump, 6 s shuffling. It was 5+ before.
- **No re-plan onto a spot that just failed** (`NetherRoute.Reader.failedNear`): a node within 3 of a leg that failed in the last 2 min (Attempts WALK) costs 20 s more, so the new way goes round it. Live at -303,87,-63, a 40 s shuffle re-planned onto the same node. Day to Day's 03551ed loop escape stays as the safety net.
- **"his body refused the path's step" said once a walk** (`WorkGoal.walkTo`), not 4 times a second.

## Stages

535 (49 s), 536 (72 s), 537, 538 (152 s), 541, 545 green; 520-523, 529, 470-489 green (488 failed twice, then passed 3 of 3: its known flake, it passes with 1-2 health left).

## Restart

Needs one: new fields.
