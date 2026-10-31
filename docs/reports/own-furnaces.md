# His own furnaces, the whole lot, and raw ore rides home (Day to Day, 12:03-12:07 World 15)

Branch `bp/own-furnaces`, stage 512.

## Causes

- **The walk to his own furnace only happened "in his mine."** `goToStation` sent him to a furnace within 32 blocks only when `inHisMine` was true. At 12:03 he was in a branch cut off the staircase at y 21, which is likely off the blueprint (Day to Day's new spec says that branch is wrong anyway). So he put his own down "right here": a new furnace notched into his mine wall, in the ore hole the vein left, about 20 blocks from his nook's 6.
- **Only the craft's shortfall got smelted.** smelt cooked the step's count, 2 of 11 raw iron.
- **At 12:06, 165 blocks out,** "smelt these 9 raw iron" made him dig a hole for a furnace's cobble and loop on a table placement.

## What changed

- **`goToStation`:** before putting a furnace down, any furnace of his (`myStations`) within 40 blocks is walked to, in his mine or not. It's logged: `his own furnace at X, not a new one here`.
- **smelt of raw iron, gold or copper** smelts all of it on him, not just the step's count. It re-queues itself at the full count. This doesn't apply on the user's own order.
- **smelt of raw ore far from all his furnaces:** more than 40 blocks, no furnace within 16, none on him. The ore rides home: `done: the raw_iron rides home to my furnace at X (N off): no new furnace out here`.

## Stage 512

- 11 raw iron, `smelt iron_ingot 2`: "smelting all 11 raw_iron at once".
- His own furnace 12 off under the same roof: "over to my furnace at X", no new furnace by him. Without the change: "put down a crafting table to make a furnace".
- His furnaces 165 off: done, the raw iron rides home.

## Gate

All 20 groups are green, including 470-512.

## Restart

**Needs a game restart.**

## Not done (Day to Day's spec and bug lists)

- **Loading his nook furnaces with raw ore before he leaves the base.**
- **The kit's food line** naming cooked_salmon instead of "N meals of any cooked food".
- **The doPlace line 2612 refusal reason.**
- **The nook layout spec:** chests one deeper, furnaces on a nook side wall, branches only from flat levels, and migrating the World 15 nook.
- **The ore-hole cell as a station spot** in `placeNextTo`. It's no longer reached when his furnaces are near, but the notch rule itself is unchanged.
