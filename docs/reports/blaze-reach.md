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

## Follow-up: hurt with no cover (World 15 10:09, a death)

He burned to death at the fortress at -97,75,61, 0/7 rods. At 6 health and on fire, `coverFight` found no cover and handed him to "the ordinary fight" four times. With no cover there was no retreat rule at all.

- **`WorkGoal.blazeRetreat`:** in a blaze fight with no cover, at 12 health or less (14 or less while burning):
  - he goes to the nearest cell he can walk to, 24 steps at most, that no blaze within 24 sees (`FortressFight.sees`, now public). A blaze only shoots what it sees;
  - there he eats, heals, and the fight resumes after;
  - with no such cell, he runs (`Flee`).
- **Stage 542:** a fortress deck with no cover within 10, a blaze hanging 9 up, and a wall 14 off. He starts at 6 health, burning. He's out of its sight in 5 s (21 steps round the wall's end), back to 20 health, alive at 30 s. 2 of 2. 539, 540, 533, 528 and 525 are green with it.
- The tp that put him among the blazes at 6 health was a live-ops step, not a bot decision. The fence-top nav stall before it (10:03-10:08) is still open in the backlog.

## Follow-up: nowhere out of their sight (World 15 12:06, a death)

Six blazes in the open; he ran in their sight and died 20 -> 0. Day to Day's b4f71ed sends every blaze fight to the cover fight, but `blazeRetreat`'s last resort was still that run (`Flee`).

- **Walled in where he stands.** With no cell out of every blaze's sight within 24 steps, he puts a block at feet and head on each open side, then one up on a wall and the roof against it: 10 at most. Then food, healing, and the fight. Running is left only for when he has too few blocks.
- **Stage 543:** 542's deck with no wall, at 6 health and burning. Walled in out of sight in 3 s, back to 20, alive at 30 s. 2 of 2. 542, 539, 540, 533, 528 and 470-489 are green with it.
