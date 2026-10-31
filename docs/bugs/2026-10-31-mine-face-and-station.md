# Live bugs, 2026-10-31 01:46-02:05 (World 14, overworld)

From Day to Day (local, monitoring only). Log evidence is quoted from the live log. The causes below are what the log proves; anything else is marked unproven.

## 1. The mine-repair round fights his own crafting table (untested fix on this branch)

- 01:46:29 `[Claude life] a notch for his crafting_table at -16, 16, -82 (south of him at -16, 16, -84)`
- 01:46:30 `[Claude temp] crafting_table at -16, 16, -82 is in a cell of one of his builds (mine, ...)`
- From then on, every few seconds:
  - `[Claude dig] crafting_table at -16, 16, -82 left standing after 1 ticks, dropped (queued by ClaudeLife.bpIntegrity:10850)`
  - `[Claude life] not breaking the crafting table at -16, 16, -82 (WorkGoal.doBreak): stations and storage stay put (his own: true ...)`
- **Proven cause:** bpIntegrity queues a Break on a cell that holds his own station, and doBreak refuses it, every round.
- **Fix on this branch, compiled but NOT gated:** both repair rounds (mineIntegrity and bpIntegrity) drop Breaks on `g.myStations` and `g.storageChests`. The open question: should a notch for a station be cut into a blueprint cell at all, or should placeNextTo pick a spot outside the shell? Decide and gate.

## 2. "dig down / collect cobblestone" walks to the mine's face at its ENTRANCE

- He is deep in his own mine at -16, 16, -84 (overworld; the where lines carry no `the_nether` tag).
- Repeated `[Claude skill] collect cobblestone x8 -> getting the cobble by digging my mine further: down my mine to its face at -18, 68, -30`
- Then `dig_down cobble -> failed: going round in circles (down my mine to its face at -18, 68, -30)`. The watchdog trips it and tells the user, as designed.
- -18, 68, -30 is by the entrance (entrance -18, 67, -31). The real working face is far below.
- The face comes from `bpDigDown`: `b.trimToDug(level)`, then `BlockPos face = b.face()`, then a walk there if it's 3+ away.
- The last trim logged (01:42:54): `blueprint: trimmed to what's dug: mine 2x4 from -18, 67, -31 | stairs north | run 40 east | branch 32 south | ...`. That doesn't look like a trim to row 0.
- **Unproven:** why `face()` returns a cell by the entrance. Add logging first: the blueprint's rows, the face it picks and why. Then fix.
- **Related:** to get cobble, a player at the bottom of his mine just mines the rock in front of him. Collect shouldn't need a long walk to a "face" when stone is in reach (respecting the mine's shell).

## 3. Nether quartz ore logged in the overworld (unproven)

- 01:46:29 `[Claude ore] saw Nether Quartz Ore at -18, 11, -79 from -16, 16, -84: remembered`, in the overworld.
- That ore doesn't generate in the vanilla overworld. Possibly a mislabeled block or a stale ore memory from the Nether (seenOre has no dimension). Check whether `seenOre` mixes dimensions (it's keyed by position only), part of the dimension-aware memory item.

## Earlier tonight, already on main

The bow and enderman fixes, attack on sight, the gold gate, the lava guard, overworld-only mine skills (that fence was right for the 01:45 Nether strip_mine death, wrong as a diagnosis for #2), and the logging (pick_up misses, follow skips, fire burn clock).
