# Nether safety: shooters, lava bridges, eating at an edge, the fortress deck

Branch `bp/nether-safe`. Stage 525 is new.

## The cause (World 15, 13:41, knocked off his 1-wide bridge into the lava sea)

A read of the fight and bridging code found these gaps:
- **The fight code only saw a "shooter" in mobs that implement `RangedAttackMob`.** Blazes and ghasts don't. So a blaze across the lava was chased with `bridgeToward`, 1 wide, while it shot him.
- **`bridgeToward` never looked at what was under the bridge** or at who could see him.
- **The planner bridged over lava for 1 extra cost point,** so a lava-sea crossing beat a long way round.
- **The Nether hunger top-up queued a meal anywhere,** mid-bridge included.
- **The blaze search walked to the middle of a fortress piece's bounding box,** which can be in its wall or under its deck.

## What changed

- **Blazes and ghasts count as shooters** in `doAttack`. The careful close-in applies ("the way in ... wide of the lava", the time under fire, cover), not the chase and bridge.
- **`bridgeToward`:**
  - over lava (within 12 down), no bridging while a blaze, ghast or other shooter within 48 has him in sight;
  - over lava, every block is put down 2 wide.
- **ActionPath:** a block into lava costs 8 extra (was 1).
- **The Nether top-up meal** waits while he's at a lava edge (beside an open drop to lava), unless his food is 6 or less.
- **`FortressFight.blazeRods`** with no blaze in view walks to the brick cell he can stand on nearest the piece's middle (its floor or deck, never a corridor roof), not the box's middle.

## A bug of my own, found by the gate

My comment on the lava-put cost started with `//` mid-line, which swallowed the `Act.Put` after it. No route could put a block down at all, and stage 370's bridge over a drop failed. It's fixed (`/* */`), and the whole gate was run again.

## Stage

| Stage | What | Without the change |
|---|---|---|
| 525 | A blaze across a lava trench in sight: no block put out over the lava. A diamond across it with no shooter: fetched over a bridge 2 wide on every row. A lava edge read right, and a brick room's floor (not its roof) picked as the deck | fails: 4 blocks put out over the lava toward the blaze |

## Gate

Every group passes. 0-86 passed on its second run; the first failed "stairs turn", which is flaky on main.

## Restart

**Needs a game restart.**

## Not done here

These come from Day to Day's live reports of 21:27 and 21:30, against main 0fac459:
- **Moves that aren't steps of a route** still lack the lava check: the "getting down: stepping off toward" fallback, spins, and stall recovery. A spin carried him into lava at 115 48 -46, and "getting down" stepped him into lava at 161 50 -33. This is next.
- **Command teleports** (Day to Day's /tp) show up as UNPLANNED MOVE incidents. Tagging them is next too.
- **The fortress approach at deck height, and walking the fortress for its spawner,** are on `bp/explore`.
