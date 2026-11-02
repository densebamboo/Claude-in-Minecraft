# The stronghold's small half: eyes of ender to its stone bricks

Branch `bp/stronghold-eyes`. The user, via Day to Day: craft eyes, throw them as a player does, triangulate, walk there, dig a staircase down to the stone bricks, stop and report. Finding the portal room is the user's half and isn't here.

## What changed

- **`entity/life/EyeHunt.java` (new), the skill `find_stronghold`:**
  - **Eyes:** with none on him, `obtain ender_eye` first. That already crafts from pearls and powder or rods (stage 531). With nothing to craft from, the skill fails and says why.
  - **A throw** is his own right click with an eye in hand (`hold` + `useHeld`, vanilla `EnderEyeItem`).
    - He watches it fly. 25 ticks on he notes the heading from where he threw it, and whether it rose (the stronghold is farther than 12) or went down (vanilla flies it onto the stronghold within 12).
    - A dropped eye (80% in vanilla) he can see is picked back up. One that went down into the ground is lost to him, as to a player, and marks the spot.
    - When vanilla makes no eye (no stronghold in the world, or the wrong dimension), he says so and the skill fails.
  - **Triangulation:**
    - The second throw is 200 blocks off the first heading at 45 degrees, so the two lines cross well.
    - He walks (in legs of 96) to where the last two rising throws' lines cross, in front of both, and throws there.
    - Lines within 5 degrees of each other give no estimate; he goes on along the last one and throws again.
    - An eye that rises again at the estimate drops that throw, so it counts as a fresh line from there.
    - Day to Day's spec said "follow the heading ~800 blocks, throw again". I went off at 45 degrees instead: two throws on the same line barely cross, so the estimate is poor.
  - **The dig:** at the spot, a staircase, never straight down.
    - Each step cuts 3 cells (head, body, the step down) and goes one block along.
    - It turns away from any fluid beside a cell, from bedrock, and from a step with no floor.
    - It stops when he *sees* stone bricks (mossy, cracked, chiselled, infested), iron bars or an end portal frame. "Sees" means the first block on a line from his eyes, never one behind the stone.
    - It gives up after 80 steps or near the world's bottom.
  - **Logs:** each throw logs where from, the heading (compass and degrees), rose or down, and the estimate.
- **Hooked up:** `ClaudeLife` runSkill `find_stronghold`, `Skills.player()`, the planner's skill list (`Persona`), and the dragon roadmap's stronghold row, which now names the skill and is a rule step.

## The test world has no stronghold

The GameTest server's overworld is superflat (`FlatLevelSource`). A probe asked its generator for the nearest stronghold, the same lookup the eye uses, and got none. So no real eye ever flies in a test. `EyeHunt.testStronghold` (null in the game) stands in for that one lookup: when his throw made no eye, it makes vanilla's own `EyeOfEnder` the way `EnderEyeItem` does and sends it to the stage's room. Everything else is real:
- the throw;
- watching the eye;
- the headings and the crossing;
- the walks;
- picking eyes back up;
- the staircase;
- seeing the bricks.

## Stage 557

- **The setup:** he starts on flat ground. 250 blocks off stands a stepped stone hill 12 high (the flat world's bedrock is 4 down), with a 15-wide stone-brick room whose ceiling is 7 under the hilltop. He has 4 eyes, an iron pickaxe and food.
- **Passes** when he reports the room's bricks, alive, with no teleport after the start, within 600 s.
- **Green 3 of 3:** 3 throws (east, north-east, then down at the crossing), 5 steps down, 129-135 s, 2-3 eyes left.
- **Caught on the way:**
  - The first version picked up the down-going eye from inside the hill, digging straight to it.
  - The first brick check counted a brick by the room's own air under his floor (sensing through stone). Both are fixed.

## Restart

Needs a game restart: a new class and a new skill.

## Not done

- **The portal room** (the user's half).
- **Live proof:** in World 15 the eye's flight against a real stronghold, and the staircase through real stone with caves and water.
