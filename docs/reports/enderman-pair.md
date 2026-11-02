# Endermen: the pair fight, the gaze, and the one he can't reach

Branch `bp/enderman-pair` (with `bp/pearl-memory` merged in). This is part of the combat major. The user's bar, 20:13: "2 endermen at once is okay if he can manage it, but not if he keeps dying."

## The causes (live, World 15, warped forest around -60,38,-880)

- **No cover:** `endermanHole` only looked for natural 2-high nooks, and when it found none ("no natural 2-high cover within 8, fighting it in the open") he fought in the open. At 20:12:06 he took three hits in a second, 20 -> 10.
- **Late healing:** in cover he ate only below 8 HP, even with two angry.
- **A second one angered:** the gaze guard (`ClaudeEntity.tick`) let him look at the nearest calm enderman whenever none was targeting him *that tick*. One that had just lost him (after a teleport) opened that window while it was still in the fight. The guard also fixed only the first stared-at enderman each tick.
- **The same one, over and over:** the pearl hunt had no memory of endermen it gave up on. One angry and 4 down a pit, out of reach, was picked again after each break-off, 8 times in 7 minutes (Day to Day, live 19:29-19:37).

## What changed

- **Making cover (`WorkGoal.tallCover`, shared with the wither-skeleton fight):** when an enderman is on him and there's no natural nook:
  - a 2-high tunnel, 2 long, into rock within 6 (no blocks placed);
  - otherwise, only with two angry at him or at 12 HP or less, the capped hole: a 2-deep hole at his feet with a cap two above the floor on a 3-block post (4 blocks). The user's 13:41 rule was no booth building, so the hole is kept to the case where he'd otherwise die.

  Day to Day's 20:2x spec ("only works if you're on the ground and they can path up to you without hitting you and you can reach them") is checked when picking the spot: the mob's own pathfinder must reach the tunnel's mouth or a rim cell. From the capped hole he fights as he does with wither skeletons: hits their legs at the rim, eats, and doesn't hop.
- **The build goes through hits:** a hit while he's making cover no longer turns him back to the open fight (the self-defence re-arm cleared the build mid-way). A walk back into the hole is queued last, so a knockback doesn't strand him.
- **Drops wait during the build:** a drop in a cover cell no longer holds the build. In stage 555, a pearl left from an earlier run sat in the post's cell, and the "grab it first" rule cost him 20 -> 5.
- **Healing early with two:** in a nook with two angry at him, he eats below 14 HP instead of 8.
- **The gaze guard:**
  - An enderman that was angry at him in the last 10 s still counts as on him, so no calm one is ever "the nearest he may look at" then.
  - The guard now turns his eyes off every enderman in the cone each tick, not just the first.
- **Let be (`HuntPatrol.picked` / `letBe`, in `ender_pearls` and the on-the-way engage):** an enderman gone after 3 times in 2 minutes and still alive is let be for 2 minutes.

## Stages

- **555 (new):** in the Nether on flat netherrack in a bedrock box, nothing to hide behind.
  - Two live endermen on him, 8 off; a calm third one 20 off.
  - His kit: a diamond sword, an iron pickaxe, iron armour, 32 cobble, 8 beef.
  - It passes when both are dead, he's alive, and the third one was never angered by his eyes, all within 120 s.
  - Green 8 of 8 after the fixes: 10-11 s, no damage taken, the third one calm.
  - Before the build fixes: not both killed in 120 s, low 15 and low 5.
- **554 (new):** a NoAI enderman 8 down a bedrock pit, in sight; he has no blocks and no way down.
  - It passes when he goes after it 3 times at most in 180 s and lets it be.
  - Green. With the let-be rule switched off it fails: "gone after 7 times in 180 s".
- **553 (wither skeletons) is still green on this branch.**
- **527 (Day to Day's single enderman, from a hole) fails on plain main too (dd81175):** the enderman sits 2 below and 4 off, and he stands idle out of reach for 70 s. It's from the recent enderman-approach changes on main, not this branch. It's listed in the backlog.

## Restart

Needs a game restart: new fields and methods in WorkGoal and HuntPatrol.

## For Day to Day to watch live

- These lines show the new behaviour:
  - `making cover, a 2-deep hole ... capped two above the floor` / `a 2-high tunnel into the rock`
  - `hit by a X while making cover ... still at it`
  - `from my hole: hit the enderman at the rim`
  - `... by a block of the cover he's making: the cover first`
  - `ender_pearls: N enderman/endermen in sight, each gone after 3 times in 2 min ... let be`
- **Not proven yet:**
  - An enderman can teleport into the capped hole's column: it's 4 cells high, and endermen are 2.9 tall. This is rare (random teleports, a 1x1 target) and wasn't seen in 8 runs.
  - The tunnel variant is only reasoned out, not staged: stage 555 has no rock in reach, so it always builds the capped hole.
