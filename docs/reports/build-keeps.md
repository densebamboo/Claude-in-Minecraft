# Planners never queue a dig his own build refuses (Day to Day items 38 and 42)

Branch `bp/build-keeps`, stage 496.

## Cause

- **doBreak refuses to dig his own builds:** his mine's shell other than ore, the cap over the hole by his door, and his house's right blocks.
- **Some planners queued those digs anyway,** so each one was refused and queued again:
  - collect's "stone near him" path picked his mine's wall stone (03:48, World 15: 0 cobble got, then the furnace craft looped);
  - the skytower take-down picked his pit cap (03:52).

## What changed

- **`ClaudeLife.hisBuildKeeps(g, level, pos)`** is true for the cells doBreak always refuses.
- **collect's candidate list** drops those cells and logs how many it dropped.
- **The skytower take-down** leaves them standing and logs it.
- **Stage 496:** he's in his mine, hurt with nothing to eat, so collect takes the "stone near him" path. No dig may be queued in his own wall. Without the change it queued 4.

## Gate

All 20 groups are green, including 470-496.

## Restart

**Needs a game restart.**

## Notes

- **Not every Break planner asks yet.** I added the check to the two planners in the reports, not to all of them. doBreak's guards have context-specific exceptions, so one shared "would doBreak refuse this?" check needs those guards moved into it first.
- **With every nearby stone excluded**, collect falls back to "digging down to find it". Getting it from the mine's face or from rock outside his builds is still to do. Item 39, where his cobble goes, is unlogged so far.
