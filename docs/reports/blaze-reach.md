# Blaze rods: blazes he can't reach

Branch `bp/blaze-lure`. Checkpoint 6. Live, World 15 01:27-01:36: four blazes hovered 7-14 off a fortress hall for 5+ minutes, with 0/7 rods and not one swing.

## The cause, from vanilla (1.21.1 `Blaze.BlazeAttackGoal`)

- A blaze that sees its target within 48 hangs back, charges (60 ticks) and fires 3 shots. It never closes in.
- A blaze that loses sight of you drifts toward you for 5 ticks only (`lastSeen < 5`), then stops. It also rises whenever your eyes are above its own plus a random 0.5-6.9.

So a sword gets a blaze only by chance, or at its spawner, where new ones appear within 4 of the cage. The cover fight (`WorkGoal.coverFight`) runs before the bow code, so even with a bow and 28 arrows on him he never shot one.

## What changed

- **Bow and arrows:** a blaze out of sword reach (up over 3.2, or 14+ off) that he can see and that's quiet gets shot (`shootAt`). A draw already under way is finished. The shield stays the answer to a volley that's due, which is handled above it.
- **No bow, blazes out of reach 15 s** (`FortressFight.blazeRods`):
  - to their spawner, beside it (a known one within 128);
  - else along the fortress deck for its spawner room (`deckStep`);
  - else `failed:` with the reason ("a bow, or their spawner, is what this needs"), so the planner hears the missing tool.
- **No more empty peeks:** after 3 steps out that drew no shot (a blaze lit with no sight of him stays lit: `chargeAge` 999, 23 peeks a minute live), 10 s of the rest of the fight before peeking again.
- **A meal waits for no fight:** an Eat queued behind a cover fight goes first while no volley is due, and the fight resumes after it (01:29: "next: [Eat]" 400+ ticks).

## Stages

- **539:** a Nether hall, a blaze hanging 7 over the floor, a seen spawner 28 off, no bow. He's at the spawner in 20 s.
- **540:** the same with a bow and 32 arrows. The blaze is shot within a second.
- Green with the merge of main up to 66799bc: 470-489, 517, 525, 527, 528, 533, 539, 540, and 0-86 on the re-run ("stairs turn", known flaky).

## Restart

Needs one: new fields in WorkGoal and FortressFight, and a new method on Perception.

## For Day to Day to watch live

- `[Claude fight] shooting it: the blaze is up out of sword reach ...`
- `... out of reach 15 s ... to the blaze spawner at X`
- `no more peeks`
- `eating first`
- He has 2 arrows now (the livestock volleys before f789f29). Restocking for the trip is in the backlog: 10 flint and 8 feathers are in his chest at -1,16,76.
