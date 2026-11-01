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
- The planner turned "enderman here" into change=replace and dropped the dragon goal (Day to Day, 2026-11-01). A side request like that is a side step that resumes the goal after.
- Chat accuracy: `ClaudeChat.moveTruth` judges a reply right after a new order emptied the queue for a replan (Day to Day, 01:18:25: "still on it. pearls first, then eyes. i'm walking to you now." went out as "nothing queued right now; not heading anywhere yet" to "beat the ender dragon"). Either rewrite only the false clause (the walk claim) while a replan is pending, or check after the new plan lands.
- The walker refused GoTo -2,16,78 from -5,16,78 "at once" with no reason logged (Day to Day, 01:18; the bed walk, fixed around in 6231be5). Log the reason.
- With a full pack he walks 230 blocks home to store (Day to Day, dc02094). Consider a chest or another in-field option.
- Silent path (01:36): take_from_chest blaze_rod queued GoTo -1,17,76 and a Task.Chest take. Route.alongRoutes then dropped 51 walks and the full-pack stash ran at -1,16,76. The take never logged a result and the rod wasn't taken: the stash or the re-plan dropped the Chest task silently.
- Chat accuracy (01:36:14, 01:36:22): the planner's reason said "the blaze rod is on me now (I just took it from the chest)" when take_from_chest never moved it. Plan text should come from his real inventory, not an assumed outcome.
- Pearl hunt (user feature): prefer a Nether warped forest when the Nether is reachable by his portal network (the densest endermen); otherwise open overworld biomes at night: plains, desert, savanna, snowy plains, where they're seen from far off. Never wander forests or jungles (trees block the view; hunting by sound is a fallback). The explore leg of ender_pearls heads for known open biomes (biome awareness). The skill picks the spot, not the LLM planner: at 01:55, after "no enderman within 24", the planner chose "explore dark_forest" (by day a dark forest is defensible, since endermen spawn under the canopy, but that's the skill's call).
- Skill waits ("it's night", "it's day ...") are matched on their text by runStep's nightRefusal and Watchdog.skillRan (Day to Day's live fix after 02:01, when the watchdog asked the user for help over a day wait). Give waits their own result prefix ("waiting:") instead.
- The planner's view of counts (Day to Day, after b13afaf): the pack log line "cooked_porkchop from the animals about" has no count, so the planner writes its own `obtain cooked_porkchop x3`, which is "done" at once (obtain counts the total held). Make totals vs deltas explicit in what the planner reads and writes.
- Route spin (Day to Day, after 9fd8b9c): a route to -5,82,6 was planned 12 times in about 1 s before Route.finish's cap handed it to the walker. The cap counts plans, not time: a re-plan that fails at once should back off (and log why each leg was dropped). Check whether it was a Nether plan (NetherRoute partial "again" ending where it started).
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
