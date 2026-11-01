# The pearl hunt's patrol; footing against shooters

Branch `bp/pearl-patrol`. Checkpoint 7, ender pearls.

## Patrol (the user: "explore it while patroling, then intelligently patrol the most optimal area for hunting")

Live 15:23 the hunt's re-decisions each queued a bare explore whose targets flipped (-121,76,-916, then -87,64,-919), about 20 s lost.

- **`HuntPatrol`:** his memory per dimension, on a 16-block grid: when he last walked each cell, and where endermen were seen or fought (a weight halving every 10 min). Endermen are recorded both by the hunt itself and by the "engage on the way" check.
- **The next point:** the best of the seen, standable ground 16-32 out in 12 directions, scored on:
  - endermen there or next to it (+4 a unit);
  - ground walked in the last 2 min (-4);
  - the user's biome rules: in the Nether a warped forest +3, elsewhere -5; in the overworld open biomes +2, forest/jungle/taiga/swamp -2;
  - his heading held (+1.5 times the cosine).

  The pick and its reasons are logged ("ender_pearls patrol: heading east to ...: plains +2; on his heading; score 3.5").
- The bare explore stays for when no ground is in sight.

## Footing against shooters

40687c0 limited fightFooting to a mob within 4 (an enderman 15 off had him shuffling). That undid the 13:51 case, a skeleton's arrow from range. Now a melee mob counts within 4, and a shooter (RangedAttackMob, blaze, ghast) in sight within 24.

## Stages

- **548 (new):** a flat stone plain. Three picks hold one heading (east, score 2.0 then 3.5 "on his heading"); endermen recorded behind him turn the next pick to them (west, score 12.5).
- 539-546 green (546 again, with the shooter rule).
- 526 and 527 fail on main itself (8ba9235 and faefbdd; in the backlog for Day to Day).

## Restart

Needs one: a new class.
