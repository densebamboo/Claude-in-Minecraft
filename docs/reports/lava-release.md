# Lava released by his own dig: plugged the same tick; the lava escape learns its way out (Day to Day batch 27)

Branch `bp/lava-release`, stage 501.

## The death (06:53 World 15: the whole diamond kit burned)

1. His dig at the obsidian pool opened onto lava. doBreak saw it ("lava behind ... the block straight back") but only **queued** the block as a Place task. That task first walked him to a stand spot: a step one down and diagonal that never took ("next node none").
2. The lava flowed in meanwhile and reached him.
3. The lava escape picked that same one-down step again and again, until he slid into the deep pool.

## What changed

- **(a) The plug, at once.** The moment a dig opens onto lava, he holds a building block and right-clicks it into the opened cell against a solid neighbour, from where he stands, the same tick. It's logged: `plugged at once with ...`.
  - **If no clean click is possible:** he steps back the way he came (his last dry cell, if that's further from the lava) and then the Place.
- **(b) The lava escape.**
  - He remembers his last 12 dry standing cells and prefers them: the way he came in.
  - A cell below him costs more, since lava drag makes a step down hard.
  - A target he hasn't reached in 1.5 s is dropped for 4 s, so he tries another way out instead of the same failing step.
- **Stage 501:** stone with a lava pocket right behind it, a diamond pick and cobble on him.
  - With the change: the stone is out and his cobble is in its place the same tick, and he's never hurt.
  - Without it: the hole stayed open 29 ticks, about the time lava takes to flow a block.

## Gate

All 20 groups are green, including 470-501.
- **0-86 failed once** on "dig in: died", the husk stage that's flaky on main here too. It passed on the re-run.

## Restart

**Needs a game restart.**

## Not done

- **(b), climbing out of a deep pool** by placing blocks under him. The escape still jumps when no dry cell is within 3 blocks.
- **(c)** make_obsidian standing only where a released flow can't reach him without a plug ready.
- **(d)** the one-down step with no path itself (the walker).
- No stage reproduces the live escape loop; (b) is covered only by the full gate.
