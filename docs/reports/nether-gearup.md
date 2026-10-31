# gear_up in the Nether: the kit's behind him (Day to Day, 12:30 World 15)

Branch `bp/nether-gearup`, stage 514.

## Cause

- **gear_up checked the overworld kit in the Nether.** Its food line resolved "collect beef" to "heading up my stairs and out of the mine for cow", 9 times in 2 s.
- **Neither works there.** There are no cows in the Nether, and his mine is overworld-only.

## What changed

- **gear_up outside the overworld,** with 4 or more meals on him and not on the user's order: `done: through the portal now, the kit's behind me (N meals on me): on with the trip`. The kit is checked before the portal; once he's through, the trip's own needs take over.

## Stage 514

He's in the Nether with 12 meals and an iron sword. gear_up ends done, with nothing queued.

## Gate

All 20 groups are green, including 470-514.
- **Hazard 488** failed once in 470-514, then passed twice on re-runs.

## Restart

**Needs a game restart.**

## Not done (still open from today's later reports)

- **Pathing:**
  - the fortress approach 23 blocks up from a ledge over lava ("no way even digging");
  - route moves that build and dig, without the player-gated place primitive;
  - lava checks on digs;
  - route instructions ("up the hill") as waypoints;
  - logging the search frontier.
  The user's pathing standard covers all of this. It's a larger rework of ActionPath.
- **Blaze fire:** get out of their sight, and off a ledge over lava.
- **Creepers:** attack on sight at night in a crowd; recovering drops at night with nothing on him; the respawn that skipped his bed.
- **Fortress memory:** it doesn't persist across death and restarts.
- **blaze_rods:** gives up when the seen pieces are "walked" without being reached.
