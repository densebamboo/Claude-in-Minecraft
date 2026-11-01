# Lava in the walk planners (the Nether shore deaths)

Branch `bp/lava-path`. Stages 520-521 are new.

## The causes

There were two lava deaths in the Nether on World 15:
- **13:41:** a blaze knocked him off a 1-wide bridge.
- **14:23:** he burned on a route along the lava-sea shore. A planned drop south onto the shore put him in lava twice.

A read of every planner and the per-tick guard found these gaps:
1. **ActionPath took lava as a free dig.** A lava cell has no collision, so `digSeconds` returned 0 s before any of its hazard checks ran. Lava in his head's cell, or in the cells he steps out over and drops through, was planned as `Dig` acts. Only the landing cell was checked.
2. **No drop check looked beside the landing.** A fall drifts sideways, and Leaps simulated it straight down.
3. **The corner cuts in the walker** (`PlayerNav.passed`) had no lava check, so he could cut across a lava cell.
4. **The per-tick lava guard:**
   - its probe was 0.45 and 0.9 ahead, on his centre line only;
   - it cut his input but not his slide;
   - the automatic sprint-jump (`openGround`) never looked for lava;
   - none of these guards run during a leap or drop.
5. **Walking right beside lava cost the vanilla walker only 4** (`DANGER_FIRE`), so a shore route beat a short way round.

## What changed

- **`ActionPath.digSeconds`:** lava is never a dig.
- **`Leaps.fall`:** a landing with lava beside it, at his feet's level or as the floor next to him, is turned down, with the reason logged.
- **`Controls`:**
  - The lava probe reaches 1.4 at a run and checks his shoulders.
  - Lava straight ahead stops him, and his slide too.
  - Lava only at a shoulder further on steers him off that side instead. The first version stopped him dead there, which stalled the bucket lesson (stage 285) beside its lava hole.
  - `openGround` refuses a sprint-jump with lava on its line or beside it, 4 blocks out.
- **`PlayerNav`:** corner cuts never cross lava, and cells beside lava cost 8 (was 4).

## Stages

| Stage | What | Without the change |
|---|---|---|
| 520 | A wall with one gap with lava at head height: no plan through it. A drop off a 4-high block: fine onto plain floor, turned down with lava beside the landing. A sprint-jump: refused with lava 3 out | fails: the plan walked under the lava |
| 521 | A walk along a lava river's shore, round a lava notch: there, never in it, never hurt | passes too (a guard) |

## Gate

Every group passes on `bp/lava-path`:
- 0-86 through 470-489 as groups;
- 491 to 521 one stage per server.

The first run failed 276-285 ("buckets": stalled beside the lava hole), caused by the shoulder stop. With the steer-off it passed twice, and the groups that ran before that fix were run again: all passed.

## Restart

**Needs a game restart.** These are code changes in `ActionPath`, `Leaps`, `Controls` and `PlayerNav`.

## What Day to Day should watch for live

- **`[Claude move] lava ahead at ...: not stepping`** while walking a shore: he should go round, not stall. A stall beside lava with that line repeating is the shoulder case; send the incident file.
- **Route lines with "lava beside where he'd land"** in drop refusals: a way round should be taken. On a Nether ledge with no way round, "no way at all" is now expected rather than a drop into the shore.
- **Not covered here:**
  - the fortress approach at deck height;
  - arriving at structures;
  - the bridge rules (2 wide or a rail, no bridging under a blaze's line of sight, never eating on the bridge).
  - The guards are still off during a running leap: a leap's landing is checked when it's planned, not while he's in the air.
