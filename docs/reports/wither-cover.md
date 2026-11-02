# Wither skeletons: he makes his own cover

Branch `bp/wither-pillar`. This is part of the combat major: wither skeletons, blazes and endermen at an expert player's pace.

## The cause

In World 15 at 19:00 there was no 2-high gap within 10 blocks (`FortressFight.pickWither`), so he fought two wither skeletons in the open with shield and crits. He ran at 6 health, they kept pace, and he died. When no natural gap was found, nothing made one.

## What changed

- **`WorkGoal.witherHole`** (called from `coverFight` when `coverFor` finds no gap) makes cover:
  1. **Rock within 6:** a 2-high tunnel, 2 long, into it at ground level. This is the gap `pickWither` already uses. A 2.4-tall skeleton can't come in, and from 2 cells in he's out of its attack box (its body widened 0.83 sideways) but it's in his reach (3).
  2. **Otherwise, a capped hole at his feet:**
     - a 3-block post on one rim;
     - a cap two above the floor over his cell, set while he still stands on it;
     - then he digs 2 down.

     Why it works:
     - His head ends 0.2 below the floor, under its attack box (which stays at its own height).
     - The cap keeps a 2.4-tall body out of the column, so none can drop in.
     - It still sees him through the 2 open cells at floor level and comes to the rim, where he hits its legs.

     In the hole he stands still: no crit hop, which would lift his head into reach. He eats, uses milk if he's withered, and waits up to 30 s.
  3. Neither possible (fluid, bedrock, falling blocks, open ground round it, under 4 blocks): the open fight, as before, logged.
- **`WorkGoal.doAttack`:** after a kill, the drop pickup waits while another monster is on him, and the next fight starts at once. In the stage, the pickup dug through the hole's wall for a drop and the second skeleton came in by it.
- Kept from the earlier commit: no fleeing on foot from wither skeletons (`shouldFlee`), and no walking on while withered or hurt with one near (the `runSkill` gate).

## Tried and dropped (each proved from the logs)

- **3 up a pillar:** they stood 5 off, out of his reach, and a hit while he built it knocked him off. He died.
- **An open 1x1 hole, 2 deep:** entities don't block each other. One walked over the hole and dropped in on him (both at y 66, its reach box true).
- **An L under the floor (shaft and side tunnel):** from the surface it couldn't see in. Vanilla drops an unseen target after 3 s, so it walked off.

## Stage 553

Two live wither skeletons 6 off, on flat netherrack with nothing to hide behind. His kit: a diamond sword, an iron pickaxe, a shield, iron armour, a gold helmet, 32 cobble and 8 beef.

- **Passes** when both are dead and he's alive within 90 s.
- **Green 3 of 3:** both killed in 7-8 s, with no damage taken (low 20 HP).
- **Fails without the change:** the pillar version failed it twice (dead once, not both killed once). The open hole and the L failed it too. The plain open fight wasn't run in the stage. Its failure is the World 15 death.

## Restart

Needs a game restart: new methods and fields in WorkGoal.

## For Day to Day to watch live

- These lines show each stage of the fight:
  - `[Claude fight] a wither skeleton N off and no 2-high gap: making cover, ...`
  - `from my hole: hit the wither skeleton at the rim`
  - `waiting in the hole`
  - `the drops wait: a X N off is still on him`
- `-Dclaude.debugWither=true` logs his and each skeleton's position and its reach box every 5 ticks.
- **Not handled yet:**
  - Getting out of the hole after the fight is left to the walk planner (2 up under a cap; it digs or pillars).
  - On a fortress deck the hole digs into nether bricks, and it needs 3 solid blocks under him.
  - It's only proven against 2 skeletons. Watch a crowd of 3 or more.
