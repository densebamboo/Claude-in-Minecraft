# No levels off the stairs; a timed-out planner call retried

Branch `bp/mine-gold`.

## No levels off the stairs (Day to Day 14:46, World 15)

- **What happened:** his gold mining was a branch cut off the staircase mid-way, the layout the user rejected at y 21 that morning. Day to Day relayed the user's rule (their 12:10 correction): branches only off a flat ore level, never off the staircase.
- **Cause:** `bpAtLevel` had two such paths. With no room at a flat level, it used the old tunnels cut off the stairs at that height (`stairLevels`), then cut a new one there (`bpNewLevel`).
- **Now:** neither path runs.
  - The old mid-stair tunnels stay as they are; nothing more is dug from them.
  - With no flat level at the ore's height, `bpAtLevel` gives nothing, and the strip mine goes on to his next level down: the diamond level at y -58. Gold and iron both generate there.
  - A fresh mine's stairs still end in a flat level at the ore's height, as before.
- **Stage 146** used to check for exactly the new level off the stairs. It now checks that nothing is cut off the stairs.
- **Not done:** inserting a flat landing into stairs that already pass the ore's height. Day to Day's spec says existing layouts are left as they are.

## The planner call, once more on a timeout (22:02, 22:03)

`ClaudeApi` re-sends a call once on `HttpTimeoutException`, logged. Any other failure is handled as before.

## Gate

All 60 groups passed on the first run.

## Restart

**Needs a game restart.**
