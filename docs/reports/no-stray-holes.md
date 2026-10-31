# No cook-2-raws side trip, no fresh hole far from his mine (Day to Day, 11:50-11:52 World 15)

Branch `bp/no-stray-holes`, stage 511.

## Cause

- **A smelt for two raws.** The planner made "smelt cooked_salmon x2" a step while he was at food 20 with 5 cooked salmon on him.
- **That needed a furnace, so a fresh hole.** He needed cobble for one. collect's no-kit path for a few cobble dug "a few steps down right here", or "digging to the stone right here" (down through the dirt), 130 blocks from his mine. A stray hole and a crafting table were left behind.

## What changed

- **No cooking trip for a few raws.** A food smelt of fewer than 8 raws, while he's fed (food 18 or more), carrying cooked food, with no furnace within 16 and not on the user's order: `done: N raw food isn't worth a furnace out here ...: it cooks at my furnaces back home`.
- **No fresh hole away from his base.** More than 48 blocks from his mine and his house:
  - collect's cobble takes only rock showing within 4 blocks of him and at most 2 below;
  - otherwise it fails with the reason ("no fresh hole out here for N cobble, it waits till I'm back by my mine");
  - no staircase is cut from his feet, and no digging down through dirt to stone.
- **By his mine,** the old behaviour stands.

## Stage 511

- **Smelt 2 salmon, fed, no furnace:** done, they ride home. Without the change: "put down a crafting table to make a furnace".
- **Collect 8 cobble 130 from his mine, no stone showing:** refused with the reason, no dig queued.

## Gate

All 20 groups are green, including 470-511.

## Restart

**Needs a game restart.**

## Not done

- **Picking up bulk diorite, granite, sand and sandstone** on a trip. Most of these are already on the junk list for a full pack, but the pickup itself doesn't skip them.
- **The planner scheduling smelts with no inputs** (sent before).
