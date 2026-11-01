# Lava pocket escape, the portal hold, and the fortress on his atlas

Branch `bp/climb2`. Stages 529-530 are new. These are Day to Day's reports from 21:00 to 22:08 on World 15.

## Lava in a sealed pocket (the 22:08 death)

- **What happened:** he stood in lava 17 s in a pocket and died.
- **Cause:** the lava escape (`WorkGoal.reflexes`) only looked for a dry, standable cell within 3. In a sealed pocket (walls all round with lava behind most of them) there was none, so it jumped in place to the end. It also counted cells behind a wall as ways out, with only a penalty.
- **Now:**
  - a cell behind a wall that isn't beside him is no way out;
  - with no dry cell, he cuts through the wall on the side with no lava behind it, if his tool takes a block in 10 ticks or less, and steps in.
  - Stage 530 is that pocket: out in 25 ticks with 16 health left, 3 of 3.

## The portal hold (22:02)

- **What happened:** enter_portal walked him to the frame's recorded cell (sometimes a corner) and then let his own movement take him back out before the 4 s it takes.
- **Now:**
  - he walks to a portal cell itself, in its bottom row, nearest him;
  - while stepping in, in the world he's leaving, he's held still: no walks, his slide damped, until the portal takes him.

## The fortress, remembered

- `find_fortress` notes a fortress it finds on his atlas, which is saved with him.
- With nothing in sight, `find_fortress` takes the one on his atlas, with no scouting.
- `go_to fortress` and `FortressScout.found` use the atlas spot when this run has none. They walk to its deck cell when it's loaded, not to the brick he saw from below.

## Climbs on netherrack (21:00)

Stage 529, a 15-up netherrack cliff 12 across with 64 cobblestone, passes. He pillars up, with ActionPath's block-put moves working again after nether-safe's comment fix. So the live "no way to 24, 84, 19 ... MAY_DIG" isn't reproduced here. It came from main 0fac459, before that fix, and may already be gone. If it shows up again, send the incident file and the route lines.

## Gate

Every group passes. Re-runs were needed for two stages that are flaky on main:
- **0-86 "dig in: died":** passed on re-run.
- **488 "out of its sight":** passed twice on re-run. It passes on main with 1-2 health left, so it sits on the edge.

## Restart

**Needs a game restart.**

## Not done here

- **Armor carried and not worn (22:08):** it's skipped when the user chose what he wears, or when what's on is as good. The incident file would tell which; it's on the user's machine.
