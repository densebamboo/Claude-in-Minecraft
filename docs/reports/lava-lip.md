# Never a step off a ledge with lava under it; falls are never silent (Day to Day, death 10:53:52 World 15)

Branch `bp/lava-lip`, stage 508.

## Cause (found in the code, reproduced in stage 508)

- **His move controls (`Controls.apply`) had two lava checks, and both had a gap.**
  1. **The "lava ahead" check** looked only at the cell ahead and the one under it.
  2. **The lip check** (no walking off a drop of 4 or more) scanned the whole column, but counted **any** fluid at the bottom as a safe landing ("water below"), lava included. It was also skipped whenever the path's next node was 3 or fewer down.
- **Live:** an 18-block drop from a Nether ledge into a lava lake passed both checks. He fell for 4.5 s with no log line and burned with his whole kit.

## What changed

- **Lava anywhere under the open cell ahead stops him at the lip,** at any depth, whatever the path says. It's logged: `[Claude move] lava N below the open cell at X: not stepping off`.
- **The lip check** treats only water as a landing.
- **Unplanned falls are logged** once they pass 3 blocks: where, how far still to go, onto what, and the task he was on. So does a fall that ends in lava.
- **doBreak's "nothing to mine" exit** names what's really in the cell, air or the block. It used to say "water where his block was" for any air or liquid, which is wrong in the Nether.

## Stage 508

A ledge 10 above a lava lake, walled in on the other three sides, him steered straight at a spot across the lake with no path (the walker's fallback).
- **With the change:** 20 s on the ledge, stopped at the lip ("lava 11 below"), never hurt.
- **Without it:** he walked off.

## Gate

All 20 groups are green, including 470-508.
- **0-86 failed twice** on "stairs turn", a stage that also fails on main here. It passed on the third run.

## Restart

**Needs a game restart.**

## Not done

- **The 7-minute shuffle on one long-walk hop target.** The watchdog's window measure from `bp/stale-stash-cols` should now catch a walk that never beats its best distance. The hop's re-picked targets have different keys, so that isn't proven for this case.
- **The earlier lava touch at 10:36:19:** lava damage in cave_air after a refused drop. Not looked at.
