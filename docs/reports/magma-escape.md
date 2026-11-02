# Escape runs: an end he can walk to

Branch `bp/magma-escape`. Day to Day, live 22:03, in a Nether basin: 7 magma cubes round him, and three escape runs in a row went to ends up the basin's walls. Each was "no closer to it in 2.5 s", and he died. His portal was 75 off.

## The cause

When `WorkGoal.pickEscape` had no portal or 1-wide gap, it ran 16 blocks out on the best heading (`fleeHeading`). Nothing checked that he could walk there. After a blocked run, the next pick took the same kind of end on a nearby heading.

## What changed (`WorkGoal.pickEscape`, step 3)

- **The headings, in order:**
  - his known portal in this dimension within 128, unless it's toward the crowd (not "gone");
  - then the best heading turned 0, ±30, ±60 ... 180 degrees.
- **The ends:** each heading is tried at 16, 10 and 22 out.
  - The end is the first cell he can stand in from 7 above to 5 below. Before, it was 4 above, so a basin's rim was never an end.
  - An end tried in the last 30 s is skipped, and so is a heading through a mob within 3.
- **The check:** the walk planner must reach the end (`createPath(...).canReach()`), with at most 12 tries.
  - A walk longer than 1.5 times the straight distance plus 4 is kept only as a fallback. `escapeBlocked` measures straight-line distance, so a walk that first winds away reads as "no closer". In stage 560 the south rim, 11 off, was a 26-block walk by the ramp, and he was called blocked on it.
  - A walk that doesn't reach its end still counts if it brings him at least 6 blocks closer. A run only has to get him well along: in stage 488 the end was off a fenced test platform, and the run broke the skeleton's sight on the way. The live death came from runs that made no headway at all.
  - With none of these, he runs straight out as before.
- **Logs:**
  - `escape: run end X has a walk to it (... with none: [...]; ... winding: [...])`;
  - `escape: no run end he can walk to (N tried, ...; skipped N with nowhere to stand, N tried lately, N through a mob)`.

## Stage 560

- **The setup:** an 11-wide Nether basin with walls 6 high, and the only way out a ramp up the east wall. Six angry zombified piglins stand 4 north. He's at 12 HP with an iron sword and chestplate.
- **Passes** when he's out over the rim within 30 s, alive, on his first escape pick.
- **Green 3 of 3:** his first pick is 21008,77,21014 by the ramp, and he's out in 3.5 s.
- **Fails without the change:** 2 picks. The first ran south into the wall ("no closer in 2.5 s"), and the second happened to reach the ramp.
  - The first version of the fix still failed it, for two reasons proven from the logs:
    - the 4-up stand search gave up after 1 try;
    - then a winding walk to the south rim was taken.
- **Stage 488 (a skeleton 6 off, break its sight):** the first full gate failed it. Only full walks counted then, so every end off the fenced platform was refused, and the run he took kept him in its sight (low 1). With partial walks allowed, it's green again.

## Restart

Needs a game restart: WorkGoal changed.

## Not done

- Magma cubes in the stage. Piglins stand in for the crowd: the bug is in where he runs, not in what's chasing him.
- `escapeBlocked` itself still judges a run in a straight line. A run that must wind is only a fallback now, but if it's taken, the check can still cut it short.
