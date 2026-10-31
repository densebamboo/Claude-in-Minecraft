# He logs back in where he logged off (Day to Day, World 15 restarts)

Branch `bp/rejoin-spot`, stage 510.

## What's known

- **Every restart put him beside the user** (08:17, 10:56, 11:37), once straight out of his mine. In `ClaudeSpawner.join` that only happens when no player data of his is found to log in at, or its world doesn't match.
- **His profile UUID is stable** (an offline UUID from his name), and he's in the server's player list, so vanilla's save-all at shutdown should write his data.
- **Not proven:** why the data wasn't used on those joins. The join now logs it, so the next restart will show it.

## What changed

- **His own position record.** He records where he stands (cell and world) with his mine every 10 s, while alive and on the ground.
- **Join without usable player data:** he goes back to that record if the spot is still a safe stand (feet and head open, no fluid, a floor), in its own world, the Nether included. Only otherwise does he go near the user, as before.
- **Join logging:** player data found or not (position, world), and the spot chosen with why: `[Claude] join: ...`.
- **Respawns after a death are unchanged:** bed, else the world spawn.

## Stage 510

- His 10 s record gives back his spot and world.
- Once that cell is filled with stone, it gives nothing (so he'd go near the user instead).
- A server restart can't run inside a game test, so `ClaudeSpawner.lastSpot` is the piece tested. The join wiring around it is exercised only live.

## Gate

All 20 groups are green, including 470-510.

## Restart

**Needs a game restart.** The record starts after the first 10 s of play on this build, so the first restart after loading it may still put him by the user.
