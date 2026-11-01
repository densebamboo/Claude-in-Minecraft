# Reach a fortress on World 15's own Nether

Branch `bp/w15-terrain`, on top of main 94c12b9 (the Nether planner, `docs/reports/nether-route.md`).

## What changed

- **World 15's Nether as stages 536-538.**
  - `tools/mca_extract.py` reads the region files on `live/world15-nether` (452f4fc) and cuts two boxes into `src/main/resources/claude_testterrain/`: `w15_ledge.gz` (x 140..215, y 28..127, z -75..15) and `w15_pocket.gz` (x -112..24, y 28..127, z -4..146).
  - The stages rebuild a box at its real coordinates in the test Nether, a slice a tick, walled and roofed with bedrock. Chunks the save never generated read as bedrock.
  - He gets the kit he had live: a diamond pick and sword, 128 cobble and cobbled deepslate, 16 beef, golden boots and an iron chestplate. He's dropped at the spot and walks to the deck, with no /tp or /give after the drop. Natural mobs in the box are cleared, because the route is what's under test.
  - A stage passes when he stands on fortress bricks within 30 of the deck, alive.
- **His chunk tickets follow him** (`ClaudeSpawner.onServerTick`, every 10 ticks). They used to move only inside his own tick. Moved past them (a /tp; these stages' drop), his chunk wasn't entity-ticking, he never ticked again, and they never moved. Stage 537 froze for 240 s at 188 68 -53 after 157 ticks. "tp him to unstick" could have done the same live. The new line is `[Claude] his chunk at X in D isn't ticking ...: the tickets moved to him`.
- **Over lava a planned bridge is 2 wide** (`ActionPath.tasks`). Each block put down with lava in it or within 6 under it gets a second one beside it, as `bridgeToward` does. This settles the 1-wide vs 2-wide mismatch from the Nether planner.

## Stages (each run alone, twice)

| stage | from | to | result |
|---|---|---|---|
| 536 | the ledge 164 44 -7 | the far deck 185 65 -14 | 18 s both runs, no damage. A 21-block bridge across (the greedy leg), not the left hill, the right hill or the staircase |
| 537 | the bridge drop 188 68 -53 | the far fortress | 2 s and 8 s: a 3-leg plan onto its bricks |
| 538 | the basalt pocket 3 44 73 | the near fortress (deck -81 85 136) | 319 s, then 194 s, no damage. 112 off, 4 legs failed and re-planned round each |
| 535, 525 | | | green with the 2-wide change |

## Not done / to watch

- **538 is slow:** 194-319 s for 112 blocks, where a player takes about 90 s. His 10 s trail shows a leg up to y 103 that failed ("no way at all", greedy too) and a wander near the deck. That's the next efficiency cut.
- **536 bridges straight across** where the user named three routes (the left hill, the right hill, a staircase). The bridge is the cheapest by the planner's seconds; whether it's what an expert does under ghast fire is the user's call.
- Basalt he dug fell where he couldn't reach and was left ("left Basalt at -45, 81, 84 behind: no way to it"). That's litter by the rule.

## Gate

(below once the run finishes)

## Restart

Needs a game restart: a new field and loop in ClaudeSpawner, and new resources.
