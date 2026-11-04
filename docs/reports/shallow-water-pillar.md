# Plan the shallow-water pillar the body already supports

## Cause

At Nov 4 03:13 the bot stayed at 550,9,104 in water over stone while trying to leave for food. Multiple mine-route legs had no path; the 90-second stuck handler dropped the queue. ActionPath rejected every straight-up pillar whenever the feet cell contained water, although WorkGoal.doPillar already supports swimming up and placing into that water cell.

Stage 584 isolated that missing move: solid bottom, one water cell at the feet, air at the head, a mineable roof, and a block to place. Original navigation found no route. With the move allowed, the unchanged body mined the roof, swim-jumped, and placed a real block underneath in 23 ticks.

## Change

Only allow that water pillar when its bottom is solid and the cell above the feet has no fluid. Existing digging, block reservation, ownership, structure, protected-shell, material-budget, and placement checks still run. Deep water was not enabled. No fake ground state, movement boost, direct block write, retry counter, or new action type. Only the compiled `ActionPath$Costs` inner class needs redefining.

## Validation

- 584 failed before: no shallow-water escape plan.
- 584 passes after: one roof dig, one placed block, actual task execution in 23 ticks, health retained; six-second player bound.
- The same scenario rejects no scaffold, head submerged in deeper water, and a protected mine ceiling. Bedrock sides exclude unrelated side-dig routes in these checks.
- Navigation 94–97 failed on ore approach (ceiling ore removed, rock ore remaining after 120 s); 500 failed at 8/10 portal obsidian after 150 s. Repeating both with the unchanged navigator produced the same failures. These baseline checks are not green and are not claimed fixed. All builds/tests use the isolated test copy, one server at a time.

## Limits

This adds a tested move; it does not prove the entire live mine exit is now reachable. Other protected geometry or failed landings may still block it. Deeper flooded recovery and missing-tool cases remain unresolved. Full gate was not rerun; live recurrence validation is pending.
