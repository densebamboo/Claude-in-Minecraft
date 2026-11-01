# A proven loop is "stuck"; plans only once he's landed

Branch `bp/loop-stuck` (with `bp/plan-grounded`).

## What changed

- **`ClaudeLife.loopStuck`:** `ClaudeEntity.mtLoop` (Day to Day's 03551ed: the 2nd shuffle or spin on one block escapes at once) now goes through the watchdog's own 20 s step: stuck level 1, the goal pinned, the way out. Before, the escape alone made a new job each time, so the watchdog's clock started over and he was never "stuck". Stage 474 failed on main after 03551ed: "knocked back every 1 s for 27 s and never seen stuck".
- **The 2nd stuck line to the player** says what was tried: "... twice now; tried my own way out (dig, climb or round), trying another way". Stage 476 checks the line for it.
- **`WorkGoal.walkTo` plans only once he's on the ground**, in water or on a ladder. A plan from mid-air started its leap from a cell he wasn't standing on (Day to Day, live 10:36: 5 min bobbing by the water). It logs "in the air ... planned once he's landed" once a second.

## Gate

Full Linux gate: 71 of 72. The one red is 0-86 "dig in: died", the known group flake. 470-489 green 2 of 2 alone, before the gate.

## Restart

Needs one: new fields in WorkGoal.
