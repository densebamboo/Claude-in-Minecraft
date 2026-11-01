# Reach a fortress: the long-range Nether planner

Branch `claude/fervent-mayer-rq8xd0`. This is checkpoint 5 of the current major: reach a fortress from his portal.

## The cause

In the Nether he got "no way at all" from a ledge or a pocket by his portal. Two things caused it:

- `Route.expand` only plans long walks over land open to the sky: its "over the land" branch needs `!under(level, me)`. The Nether has a ceiling, so no long walk there was ever planned.
- `WorkGoal.walkTo` only calls `ActionPath` for spots 28 blocks off or less. A deck 100 blocks off got the vanilla pathfinder and nothing else.

## What changed

- **`entity/nav/NetherRoute.java` (new).** A coarse A* over columns every 4 blocks, at any height. Each move is costed in seconds plus risk:
  - walk at sprint pace;
  - tunnel at the dig pace of the best tool on him;
  - climb (steps cut or a pillar) at about 0.9 s a block;
  - steps down, or a sheer fall only within his health margin (`Leaps.margin`);
  - bridge: a block put down per cell with no floor; over lava 2 blocks plus risk, over a 6+ void a careful bridge;
  - lava beside the way adds risk;
  - never through lava, bedrock, a block holding lava back, onto magma or fire, or up by the bedrock roof;
  - a fortress's own blocks very dear (`ActionPath.structureBlock`).

  It reads only loaded chunks. When the land runs out it plans to the nearest point and is planned again from there (`Route.finish`, again). Leg ends come about every 12 blocks, and at every climb or drop of 5 or more, so `ActionPath` plans each one in full.
- **`Route.expand`:** in a dimension with a ceiling, any walk farther than 24 across goes to `NetherRoute`. It runs before the mine branches, because his overworld mine's coordinates mean nothing in the Nether.
- **`WorkGoal.walkTo`:** in the Nether, when the plain `ActionPath` search finds no way, it tries once more greedy (w 2.5) with twice the budget. A leg that is mostly bridge costs about 4 a cell against a heuristic of 1, so the plain search spent its budget on the ground round him. In stage 535 a 13-block bridge to the deck was "no way at all" with 54 blocks on him.
- **`ActionPath` block count:** `maxPuts` now counts netherrack and blackstone, the same blocks `scaffoldBlock` picks from. With only those on him, a Nether way planned no blocks put down.

## Stages

- **535 (new):** in the real Nether at 4000, 4000, walled in with bedrock so the seed's own terrain plays no part.
  - The course: a sealed basalt pocket at his portal's level, a lava sea with a netherrack shore round it, a 24-up cliff, a 20-block lava gap, and a nether brick deck 8 higher, 94 off.
  - It passes when he stands on the bricks, unaided and alive, within 300 s.
  - Green: 49 s, no damage, 80 of 128 cobble left. He tunnelled out, took the shore, climbed the cliff, then pillared and bridged to the deck.
  - It fails without the change: the first runs ended with "no way at all" on the last bridge.
- The full Linux gate results are below.

## Gate

(filled in below once the run finishes)

## Restart

Needs a game restart: a new class, and a new branch in Route and WorkGoal.

## For Day to Day to watch live

- These lines show each plan:
  - `[Claude route] Nether A -> B: N legs over M nodes, about S s: D dug, P put down, U up`;
  - "as far as X for now" when the land isn't loaded;
  - `no way in the plain search; the greedy one (w 2.5)` for the bridge retries.
- The bridge `ActionPath` builds is 1 wide; the 2-wide lava rule is in `bridgeToward` only. The coarse plan prices lava bridges as 2 wide, but the leg builds 1 wide. Watch for knocks off it near shooters.
- It is not yet run on World 15's own terrain: the ledge at 164 44 -7, the basalt pocket at 3 44 73 to the fortress at -65 68 135, and the bridge drop at 188 68 -53. Those are the live proof.
