# Explore with an atlas, the fortress deck walk, the pearl stage, and lava from momentum

Branch `bp/explore`. Stages 526-528 are new. This is the user's goal of 19:35-19:40 (blaze rods, then a warped forest and pearls), plus Day to Day's live reports from 21:26-21:30.

## Atlas (`entity/life/Atlas.java`)

- **What he records:** every 2 s, the biomes in view round him (0, 8, 16, 32 and 48 out, 8 ways, at his height, only with a clear line from his eyes) and a spot he can stand on in each. That's up to 8 spots per biome, 48+ apart.
- **Coverage:** the ground he has walked, as a 32-block grid per dimension.
- **Saved with him:** the `atlas` entry in his mine tag.
- It never reads the game's biome map beyond what's in view.

## Explore

- **`explore <biome>`** (for example `explore warped_forest`; a target that's no biome is the plain look round):
  - already in it: done;
  - seen before: he heads for that spot;
  - otherwise: off over fresh ground, on the heading with the least ground covered.
- **Plain explore** turns toward fresh ground instead of a random heading.
- **On a fortress** in the Nether, explore and the blaze search follow its deck. That means the brick floor he can walk from where he stands (stairs, slabs and soul sand count), to the furthest part not walked yet. A blaze spawner in sight ends it. This is the minimal version Day to Day asked for; the full piece graph is not done.

## Pearl stage

- **`ender_pearls [count]`:**
  - the count defaults to 14 (12 eyes for an empty frame plus 2 spares), less pearls and eyes he already has;
  - it needs the existing `endermanTime` gear check;
  - an enderman in sight: after it;
  - in the Nether outside a warped forest: explore for one;
  - otherwise: walk on, looking.
- **The fight:**
  - against an enderman 4+ off, he digs 2 down where he stands (sound walls, a floor, no fluid) and fights from the hole. It can't reach in; he hits its legs.
  - At under 8 health he eats in the hole.
  - If it teleports more than 24 away, he climbs out.
  - After the kill: the pearl, then up out of the hole.
- **Not done:** reading eyes already in an End portal frame; crafting the eyes.

## Lava from moves that aren't route steps (21:27 and 21:30)

- **The momentum guard:** whatever the walker wants, if his own slide would carry him into lava within the next few ticks, it's stopped, and any jump with it. This covers spins and stall recovery.
- **"Getting down":** a side with lava at foot level, in the drop, or beside the landing is never picked.

## Also

- **`/` command lines from the chat bridge** (Day to Day's /tp) aren't counted as UNPLANNED MOVE incidents.

## Stages

| Stage | What |
|---|---|
| 526 | A warped-forest patch (set with `fillbiome`) 8 off: on his atlas, walked to, "done: in the warped forest". 5 of 5 |
| 527 | An enderman at him: 2 down into a hole, killed from it with no damage taken, pearl picked up, out after. 4 of 4 |
| 528 | An L of nether-brick deck in grass: the deck walk picks its far end, never a cell off the bricks, and he walks there |

## Gate

Every group passes. 0-86 passed on its third run; two runs failed "stairs turn", which is flaky on main.

## Restart

**Needs a game restart.** There's a new class (`Atlas`) and new saved data.

## Next

- **Climbs on netherrack** (21:00: "no way to 24, 84, 19 ... blocks on him: 64"): stage 529 is being written.
- **The fortress on his atlas** across restarts, and go_to fortress onto its deck: on `bp/climb2`.
- **The portal hold** (22:02): enter_portal walks him back out of the portal before it takes him.
- **The gold level as a flat landing:** a mine blueprint change.
