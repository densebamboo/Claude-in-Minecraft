# Air escape before water plugging

## Proven causes

The Nov 4 01:25:12 live trace queued a stone roof dig at 510,17,117 for air, immediately followed by `blocking 10 spots where it comes in` and `doBreak line 1510`. The plug routine displaced the survival dig. At 01:25:25 the watchdog found no path to air; the bot drowned at 01:25:31.

Stage 577 reproduced that exact sequence and failed with health 17.5, no running task, and zero air. Suppressing plugs alone still failed: the movement controller automatically swam upward while mining. The vanilla progress was 0.0035555554/tick off the ground, versus 0.017777778 grounded with the same stone pick. The air reflex also explicitly jumped during roof mining.

The deeper stage 578 exposed another interruption: once he sank a block toward the floor, the reflex no longer recognized the same overhead target, because it compared against the cell immediately above his current eyes. It resumed swimming instead of finishing the dig.

## Small shared changes

- Plugging yields while the existing surfacing state or watchdog air route is active.
- Roof mining stops navigation and keeps the same overhead target as his height changes, without pressing jump.
- Automatic swimming yields to an active mining task; ordinary surfacing still presses jump through the existing air reflex.

No new retry/give-up counters, world edits, synthetic ground state, mining-speed changes, reach exceptions, or ownership/structure/shell/lava exceptions. The player's existing survival mining handles tool speed and wear. Callers of `blockWaterInflow` are the walk and break paths; the shared movement controller applies once per ClaudeEntity tick.

## Tests

- Stage 577: failing before; roof removed and breathing with health 20 after the changes. The initial full-refill version passed in 118 ticks.
- Stage 578: three-deep pool, floating under planned headroom with a stone pick and four cobble; failing before the target-continuity change, then reaches air with a 100+ reserve and health 20 in 124 ticks.
- Both scenarios bound escape plus the safe air reserve to ten seconds, matching the reserve checked by existing surfacing stage 483. A stricter exploratory full-refill check did not pass: air was 279 at fifteen seconds after ordinary tasks resumed. Complete refill time is not asserted or claimed by these escape scenarios.
- Existing stages 27 and 483 (ordinary surfacing) passed separately; repeat against the combined live version before deployment.

## Limits

The no-pick-under-cobblestone case is not solved by faster grounded mining. An accessible floor, a usable tool, a dig permitted by the existing protections, and a reachable air opening still matter. This is not proof that the whole flooded mine or drop recovery is fixed. The separately deployed water-tunnel preflight must also be tested with stage 578 before a live reload; do not replace its WorkGoal with this branch's version and remove its other changes.
