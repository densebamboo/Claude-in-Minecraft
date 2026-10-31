# Ghast fireballs hit back in every state (the user, 13:00 World 15: "he never hit fireballs back at the ghast")

Branch `bp/fireball-back`, stage 517.

## Cause

- **The deflect reflex sat in the wrong place.** It was in `ClaudeLife.tick`, after its early returns (holds, cover, plan states, no owner), and looked only 3 blocks out.
- **Stage 486 never showed a deflect.** The ghast fireball hazard stage passed with no "hit it back" line: he dodged, he never deflected.
- **Live:** no swing at all during the 13:00 death.

## What changed

- **The reflex moved to `WorkGoal.reflexes()`**, the hazard layer that runs every tick in any state. It finds a fireball within 5 blocks coming at him, in his sight, and keeps looking at it. Within 3 blocks it swings: a hit sends a large fireball back the way it came, vanilla.
- **The old reflex in `ClaudeLife.tick` is removed** (one copy).

## Stage 517

A ghast 13 off and 6 up fires at him. The fireball is hit back ("2.7 off: hit it back (WorkGoal.reflexes)") and he's never hurt.

## Gate

All 20 groups are green, including 470-517.
- **0-86 failed once** on "stairs turn" (flaky on main here). It passed on the re-run.

## Restart

**Needs a game restart.**

## Not done

- **Breaking line of sight from ghasts and blazes, and keeping off open lava ledges in their sight.** That's part of the combat layer and the pathing standard.
