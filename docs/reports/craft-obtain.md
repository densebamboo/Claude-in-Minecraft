# A craft short of what it's made of goes to obtain (Day to Day items 37, 43, 47 logging, 48)

Branch `bp/craft-obtain`, stage 498.

## What changed

- **The watchdog's next way for a failed craft.** When the same craft fails twice on a missing ingredient other than cobble or logs, `Watchdog.skillRan` now queues `obtain <the craft's target>`. Procure then works out the chain: smelt the raw iron, mine the diamonds, then craft.
  - Before, its only next way was collecting cobble or logs. "missing iron_ingot" (raw iron and coal on him) and "missing diamond" were skipped and re-picked.
- **Item 37.** "no building blocks (collect logs or stone first)" now gets cobblestone first, then the step again.
- **Item 47, logging.** doPlace's instant exit "line 2561" means the cell already holds a block, so the place is moot. The exit now names that block: `the cell's filled already, <block> there`.

## Stage 498

- Two "missing iron_ingot" failures of `craft iron_pickaxe` give `obtain iron_pickaxe`.
- Two "no building blocks" failures give `collect cobblestone`, then the build again.
- Stage 478 (cobblestone collected first for a furnace) still passes.

## Gate

All 20 groups are green, including 470-498.

## Restart

**Needs a game restart.**

## Not done (Day to Day's follow-ups)

- **Item 48 as the user's armor rule.** obtain diamond_leggings will go and mine diamonds. The armor-piecemeal rule wants the best piece he can afford now (helmet or boots with 5 diamonds) and to never pick the unaffordable piece. That's a change to gear_up's piece choice, not done here.
- **Item 47: counting failures per source,** so a whole failing patch round trips rather than each cell once.
- **Item 49:** strip_mine and finishing a branch must read the watchdog's write-off (Attempts) and end or reroute the branch.
- **Item 50:** why the deepslate at 25 -57 184 won't break. Not proven.
