# His mine's record against the rock (Day to Day bugs 1, 2, 3, 8 of 2026-10-31)

Branch `bp/mine-record`. It is built on `live/station-in-mine-cell` and replaces that branch's untested station guard. Stages 490-493 are new.

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
- **A stray rock row doesn't end the mine.** `dugMainRows` treats rock rows followed within 2 rows by dug rows as a stray block (a fill, gravel, a block put back), not the end of the mine. It used to stop at the first rock row; a comment there already recorded one stray block cutting every row past it.
- **The broken record repairs itself.** `regrowMain`: when a run or branch hangs off no main row, the main line's last flight is taken on row by row while the rock shows it dug, until what hung off it joins again. Any trim calls it, so the record saved live gets fixed on his next dig_down or strip_mine. 51 stairs north from z -31 reach z -82 at y 16, where he is.
- **The doorway rule only hits the real doorway:** segment 0, row 0, of a stairs.
- **His ways are never sealed.** A new trail records the cells his feet and head pass through (every 5 ticks, 30 minutes, 4096 cells, per world). Both repair rounds drop any patch into an open cell on that trail (`[Claude life] the mine round: not patching N open cell(s) he walked through`).
- **The table guard is gone.** The branch's "repair rounds drop Breaks on his stations" guard is removed. It would also have stopped the existing, correct take-up of a station that really stands in his walkway (`WorkGoal.doBreak`). Stations still go only into blueprint WALL cells (the rule in `placeNextTo`), and the bad Break went away with its cause.
- **Logging.**
  - Every trim logs its reading: rows counted, stray rows kept, where it stopped and on which blocks, any regrow.
  - `bpDigDown` logs its rows (main and all), the face, which segment the face ends, his spot and the distance.

## Stages

| Stage | What | Without the change |
|---|---|---|
| 490 | One rock row at the top of an 8-row staircase: the record keeps 8 rows, the face at the bottom | cut to 0, face by the entrance |
| 491 | The record as saved live (stairs n = 0, the run kept): regrown to 8 from the rock, and reconcile plans no Place in his stairs or run | the stairs' foot to be filled |
| 492 | The Nether's blocks at his mine's x y z: no trim, no fixes, and quartz in sight there isn't remembered | fails ("fixes planned off the Nether's blocks"), checked by removing the guards in the test copy |
| 493 | He walked through an opening in his run's wall: the round leaves it open and patches a hole he never used | sealed |

## Gate

GATE_RESULTS

## Restart

**Needs a game restart.** There are new methods and fields in `MineBlueprint` and `ClaudeLife`.

## What Day to Day should watch for live

- **The first dig_down or strip_mine after the restart** should log `blueprint: ... his stairs north regrown 0 -> N rows from the rock (... joined again)`, then `dig down: ... face <deep cell>`. If it says "still does", the run's mouth isn't where the stairs end, so send the trim and dig-down lines.
- **`not patching ... he walked through` lines.** That's the guard working. If it ever keeps a real cave hole open that he wandered into, say so. That's the trade-off: he never seals his own way.
- **The andesite he already placed at -17 16/17 -83 is still there** unless the user broke it. The round won't re-place it once he's walked the cell, and the regrown stairs make it a walk cell, so the round digs it out.

## Not done here (batches 2 and 3, still open)

- "mine the stone in front of you" means the block his view points at.
- A Place blocked by the user standing in the cell: wait or say so once.
- Portal lit/unlit refreshes only with a clear line of sight.
- Eat with no food: go for food instead.
- The stale pillager cover hold against starving.
