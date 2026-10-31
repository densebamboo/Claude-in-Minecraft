# Gear tiers per dragon-path stage (the user's rule, 2026-10-31 ~12:20)

Branch `bp/kit-tiers`, stage 513.

## The rule

> "realistically, i don't think he needs full diamond gear for a nether trip. iron or better is fine and 1 gold piece is optional but not required and he could get that in the nether later. but he needs a diamond pick and for the obsidian obviously. for the dragon he should have full diamond tho"

## What changed (`Kit.missing`, the full kit)

- **`Kit.netherDone(g)`:** 6 blaze rods (or 12 powder), any eye of ender, or being in the End.
- **Before that, the Nether-trip kit:**
  - all four armor pieces iron or better;
  - an iron slot only goes diamond when his diamonds already cover it;
  - the diamond pickaxe;
  - a sword, a shield, a bucket, and food, light and fuel as before.
  - No diamond axe or shovel, and no full diamond armor.
- **After that, the dragon kit:** full diamond armor and the diamond axe and shovel, as before.
- **The gold piece** stays the existing wear-one-in-the-Nether rule. It's never named as a gap.
- **The planner's dragon roadmap line** names the kit for the stage he's at.

## Stage 513

- **Full iron armor, a diamond pick, iron sword, shield, bucket, food, torches, coal, flint and steel, a bed:** the kit asks for nothing.
- **With 6 blaze rods:** all 4 diamond pieces plus the axe and shovel.
- **Stage 2x (31 diamonds):** its expected kit is updated to the rule. The diamond pick still counts; the axe and shovel don't before the Nether.

## Test file

- `exactStages` and `body()` sit at the JVM's 64 KB method limit, so stages 494-513 now live one method each (`st494` ... `st513`), dispatched from `stages494`.

## Gate

All 20 groups are green, including 470-513.

## Restart

**Needs a game restart.**
