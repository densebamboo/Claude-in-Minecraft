# Health 0 always ends in a death, the respawn's world, the deck edge, and a tower to a high spot

Branch `bp/eyes`. Stages 531-533 are new. These are Day to Day's reports from 22:38 to 22:50 on World 15.

## Health 0 and no death (22:48: the server hang)

- **What happened:** he sat at health 0 in lava with every hit "not taken" and no death event, and the server hung after a /tp.
- **The likely cause, unproven:** the death hook at the top of `die()` threw an `Error`, which its try didn't catch (only `RuntimeException`), so `die()` never finished. Now:
  - the hook catches everything;
  - in `ClaudeEntity.tick`, health 0 for 3 s with no respawn under way forces a real death, logged as a WARN.
- **What wasn't found:** the hang itself. The incident file and the log from the hang would show it; if it happens again, send the thread dump or the last 200 lines.

## The respawn's world (22:50, in the Nether at 0, 83, 0)

- His respawn point was set from the world passed in when he lay down, which could be the user's. It now takes his own world.
- At respawn: with no bed where his record says, he goes to the overworld's spawn, never the same coordinates in the Nether, and the log says why the bed was turned down.

## Off the deck's edge in a fight (22:48)

The lip guard (no step off a 4+ drop) skipped any tick with a jump pressed, and his fight jumps for crits. Now it runs on jumps too and cancels the jump. Leaps have their own control and never reach it. Stage 533: a blaze out past a 3-wide deck 10 over lava, 12 s of the fight, never off the deck.

## Up to a high spot (22:38: "no way at all" to the deck 23 up)

- **The search budget** grows with the climb: 3000, plus 400 a block past 4.
- **When the search finds nothing for a spot 5+ up:** a tower from the ground under the spot's edge, then onto it (`WorkGoal.towerTo`). Stage 532: a deck 19 up and 18 across from a wide ledge. It fails without the tower, even with the bigger budget. Now he's up in about 12 s.
- **go_to with a solid block's coordinates** (the deck's top brick) means standing on it: the first standing room above.

## Eyes of ender

Stage 531: `obtain ender_eye 3` with 3 pearls and 2 rods makes 3 eyes, through the game's own recipes. No code change was needed.

## Gate

All 63 groups passed on the first run.

## Restart

**Needs a game restart.**

## Not done here

- **Blaze cover:** fighting out of a blaze's line of sight. `FortressFight.coverFor` exists; check how it does live with the deck walk.
