# His mine's record against the rock (Day to Day bugs 1, 2, 3, 8 of 2026-10-31)

Branch `bp/mine-record`. It is built on `live/station-in-mine-cell` and replaces that branch's untested station guard. Stages 491-492 are new.

## The causes, proven

One chain explains the face by the entrance, the table "in a cell of his build", the repair round sealing his own tunnel, and the walks back refused 3 of 3.

1. **The record was trimmed against the Nether.** At 01:42 he ran strip_mine in the Nether: the 01:45 death that main has since fenced with "mine skills are overworld-only". `bpStripMine` trims the blueprint to what's dug, using his level, which was the Nether. His overworld stairs were read against the netherrack at the same x y z.
   - Row 0 read as rock, so the stairs were cut to `n = 0`. That's the logged `stairs north` with no count.
   - The run 40 east and the branches were kept, and the record was saved.
2. **With no stairs, the face became the entrance.** `exit()` of a stairs with n = 0 is one step before the entrance and one up: (-18, 67, -31) → (-18, 68, -30). That's exactly the face dig_down and collect walked to (bugs 2 and 3 of batch 1).
3. **The stairs' foot became shell.** The run now hung off nothing, so the cells where the stairs come into it read as holes to patch. That's the andesite at -17 16/17 -83, put back after the user broke it. It shut him in at hunger 6 and refused his walks back.
4. **The doorway rule hit his table.** `reconcile()`'s rule "the doorway stays open" applies to row 0, which was now the run's first row at y 16. So it broke "a block he put" one cell behind that row. A crafting table counts as such a block, and that's where his table was notched in. doBreak refused it every round.
5. **The quartz is a separate bug of the same kind.** `ClaudeLife.tick` passed the user's world (`player.serverLevel()`) to all of his senses and upkeep jobs. At 01:46 the user was in the Nether and he was in the overworld. So `noteSeenOre` read the Nether at his spot: "saw Nether Quartz Ore", remembered by x y z alone.

## What changed

- **His world, always.** In `ClaudeLife`, 81 `player.serverLevel()` uses now read his own level, `g.level()`: tick, the mine rounds, ore, lava, beds, furnaces, temp blocks, house and farm upkeep. This matches what `runSkill` already did ("his world, never theirs"). Two that are about the user's spot are kept: `groundUnder(player)` and `relocateNear`. When they share a world, nothing changes.
- **The record refuses another world.** `MineBlueprint.trimToDug` and `reconcile` do nothing outside the overworld, and say so in the log. `noteSeenOre` refuses a level that isn't his, or isn't the overworld.
- **The broken record repairs itself.** `regrowMain`: when a run or branch hangs off no main row, the main line's last flight is taken on row by row while the rock shows it dug, until what hung off it joins again. Any trim calls it, so the record saved live gets fixed on his next dig_down or strip_mine. 51 stairs north from z -31 reach z -82 at y 16, where he is.
- **The doorway rule only hits the real doorway:** segment 0, row 0, of a stairs.
- **The table guard is gone.** The branch's "repair rounds drop Breaks on his stations" guard is removed. It would also have stopped the existing, correct take-up of a station that really stands in his walkway (`WorkGoal.doBreak`). Stations still go only into blueprint WALL cells (the rule in `placeNextTo`), and the bad Break went away with its cause.
- **Logging.**
  - Every trim logs its reading: rows counted, where it stopped and on which blocks, any regrow.
  - `bpDigDown` logs its rows (main and all), the face, which segment the face ends, his spot and the distance.

## Stages

| Stage | What | Without the change |
|---|---|---|
| 491 | The record as saved live (stairs n = 0, the run kept): regrown to 8 from the rock, and reconcile plans no Place in his stairs or run | the stairs' foot to be filled |
| 492 | The Nether's blocks at his mine's x y z: no trim, no fixes, and quartz in sight there isn't remembered | fails ("fixes planned off the Nether's blocks"), checked by removing the guards in the test copy |

## Gate

Every group passes on `bp/mine-record`: 0-86, 99-111, 117-120, 122-125, 180-183, 199, 200-206, 255-258, 276-285, 295-296, 297, 298-299, 300-301, 350-359, 360-363, 368-371, 375, 420, 430-431 and 470-492.

- **0-86 is flaky on main too.** It failed once on "stairs turn", a stage that also fails on main in this sandbox, then passed alone.
- **Two of my own tries were dropped**, because the existing stages showed they were wrong:
  - **A "stray rock row" rule.** One rock row followed by dug rows was to count as a stray block, not the mine's end. Stage 81 says the opposite on purpose ("a flight joined onto rock: the record must stop there"), and the proven cause was the Nether trim anyway.
  - **A "never seal a cell he walked or dug through" guard** for the repair rounds, which Day to Day asked for. It broke legit repairs. Stage 77's blueprint pass refills cells he dug in the wrong place, and stage 78's cave crossing walls off cave air he walked across. Nothing he walks or digs reliably marks "his way". The real fix is the record being right: it can't be read against another world, and a broken one regrows from the rock.

## Restart

**Needs a game restart.** There are new methods and fields in `MineBlueprint` and `ClaudeLife`.

## What Day to Day should watch for live

- **The first dig_down or strip_mine after the restart** should log `blueprint: ... his stairs north regrown 0 -> N rows from the rock (... joined again)`, then `dig down: ... face <deep cell>`. If it says "still does", the run's mouth isn't where the stairs end, so send the trim and dig-down lines.
- **The andesite he already placed at -17 16/17 -83 is still there** unless the user broke it. Once the stairs are regrown, it's in a walk cell, so the round digs it out.
- **Batch 4 item 10 ("digging my mine: 4 more rows from -18, 64, -34")** has the same cause. bpStripMine trims, and so regrows, before it picks where to dig.

## Not done here (batches 2-6, still open)

- "mine the stone in front of you" means the block his view points at.
- A Place blocked by the user standing in the cell: wait or say so once.
- Portal lit/unlit refreshes only with a clear line of sight.
- Eat with no food: go for food instead.
- The stale pillager cover hold against starving.
- Lessons that never end; "keep digging" said while idle; false "deepslate on that spot now".
- Spatial words resolved in code: "touching the portal", "here" (`where=looked_at`), "the one missing on this side". Plus a frame record and FarSight tying seen obsidian to known frames.
- Copy mode ended by the user's own "are you watching?".
