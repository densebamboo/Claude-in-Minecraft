# Backlog: the minors (parked until the major checkpoints pass; the user, 2026-11-01 00:25)

Each line is a live report from Day to Day (World 15, 2026-10-31/11-01) not on the current major.

## Features the user asked for (after the majors)
- **Nether highway builder** on `BridgeBlueprint`:
  - row by row from a portal: floor, rails, tunnel shell, mid-leg staircase;
  - resumable, with repair rounds that never seal it;
  - a highway to every fortress or warped forest on his atlas;
  - go_to prefers the highway;
  - the network as nodes and edges in PortalMap and the atlas.
- **Nether travel calculator:**
  - overworld/Nether coordinate math (x/8, z/8);
  - predicting where a new portal links (the 128 search radius);
  - planning long overworld trips through the Nether;
  - answering in chat.
- **A portal inside his mine,** linked to a Nether hub portal.
- **The combat controller** (see CLOUD_HANDOFF.md).
- **The vanilla-parity audit, the rest of it** (see CLOUD_HANDOFF.md, `bp/parity`).

## Bugs
- The respawn says "no bed" with his bed at -2/-1 16 78 (logged now; find why).
- After a death he planned to hand his 14 rods to the user, a spectator who can't pick up:
  - never act on his own offer before a yes;
  - his goal items (rods, pearls, eyes, the kit) aren't offered away;
  - nothing given or tossed to a spectator;
  - "wait at the edge for you?" said to a spectator.
- Blaze attacks "over at once: gone": log why each attack ends (out of reach or sight, despawned).
- The long-walk hop chooser alternates between two targets (23:55, a pair that never makes net progress).
- "bridge across" or "bridging now" should make a bridge task, not more walking.
- A water clutch onto leaves waterlogs the leaf (Day to Day, World 15 00:38:18: "falling (4 blocks so far): water bucket under his feet on -14, 75, -10", a leaves block). `ClaudeLife.mlg` takes the first block with a hitbox as the ground; a bucket clicked on a waterloggable block fills that block, so no water lands and the fall isn't broken. He was in a fight, so `fillBucket` skipped, and the leaf stayed waterlogged (litter) with his bucket empty till 00:50.
  - mlg: never aim at a waterloggable block (leaves, slabs, stairs): a full plain top face, or no clutch;
  - a clutch's water is a pending pickup that outlives a fight (taken back right after, like temp blocks).
- Stage 532 (deck climb by towerTo) is flaky: 6 of 19 runs failed on one build, 0 of 8 on another. A bisect showed main's code fails with nothing but extra stages in the test file, so it is timing-sensitive, not caused by a route change. The failure: he stalls 2 blocks from the tower's foot (GoTo within 0.7) for 80 s with nothing logged. A silent stall is a bug: its failure line now dumps his position, motion and path; find why the walk never settles.
- Pack: make room before a pickup goal (stash superseded gear). Part done: superseded gear goes when the pack is full.
- Chat inventory: "pack's full" and "11 rods" from stale lines (part done: a live free-slot line). The `[Claude pack]` log should come from the same read.
- The gold level as a flat landing in stairs that already pass it (new mines are fine; old mid-stair runs are no longer used).
- Armor carried and not worn (22:08). It's skipped when the user chose what he wears, or when what's on is as good; check which.
- Watchdog batches still to do:
  - order vs action mismatch;
  - the dimension he's in vs the one the step needs;
  - chat claims vs the world, including logged sightings he then denies;
  - net-zero spins;
  - plans missing inputs;
  - standing beside a hazard;
  - the last 60 s at a death;
  - the despawn countdown on dropped gear.
- The PitGuard pit stages 245-247 fail on main ("the mouth isn't shut"), not gated.
- Older backlog:
  - "stay" holds and how they lapse;
  - re-checking a stale plan;
  - the kit gate in the Nether;
  - pack pressure;
  - death recovery under the important-gear rule;
  - lavaTheyMean misfiring;
  - the nook layout spec.
