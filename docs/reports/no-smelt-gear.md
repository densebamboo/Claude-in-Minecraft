# His gear never goes in the furnace; standing on the fortress is being at it (Day to Day, 13:17 and 13:20 World 15)

Branch `bp/no-smelt-gear`, stage 518.

## 1. Iron swords smelted into nuggets (13:17)

- **What happened.** A kit plan wanted a shield, planned "smelt iron_nugget 9", and the smelt took his two iron swords as the input. Both `Procure` (any smelting recipe whose input is in his pack) and `smeltInput` accepted gear: iron and gold nuggets smelt from tools, weapons and armor.
- **What changed.**
  - `smeltInput` never picks an item with durability.
  - `Procure` skips smelting recipes whose every input is gear.
  - Nuggets now come from ingots, so the plan mines iron instead.

## 2. "Still 19 blocks short of the fortress" while on its bridge (13:20)

- **What happened.** go_to fortress measured distance to one remembered brick.
- **What changed.**
  - `FortressScout.onFortress(g)`: a fortress block (nether bricks, its fence, stairs or slab) under his feet, or the block below that.
  - go_to fortress is done there: "I'm on the fortress".
  - The chat facts and the planner's situation both say outright that he's standing on the fortress, so his replies can't deny it.

## Stage 518

- **Two iron swords, an iron pick, coal and a furnace:** smelt iron_nugget fails and both swords stay. The nugget plan is "strip_mine iron_ore 1, smelt iron_ingot 1, craft iron_nugget 9".
- **Nether bricks under him:** read as on the fortress.

## Gate

All 20 groups are green, including 470-518.
- **0-86 failed once** on the dig-in stage (flaky here). It passed on the re-run.

## Restart

**Needs a game restart.**
