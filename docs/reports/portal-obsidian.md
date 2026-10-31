# The portal's obsidian the user's way; watchdog walk false positives and its chat rate (Day to Day items 52, 53)

Branch `bp/portal-obsidian`, stage 500.

## Item 52: the portal's obsidian was made the slow way

- **Cause.** `makeObsidian` only used the user's technique (`LessonWords.technique`: a water pocket at the lava's level, the top layer first) when `obsMaking && !portalMaking`.
  - build_portal's own obsidian step runs with `portalMaking` set. So it fell back to the old pour, scoop and wait-for-the-drain cycle: 16-20 s a block live, and "the water draining off before the next block" 42 times in 74 s.
  - The planner then called a working pool "stalling" and left it, a 148-block walk.
- **Fix.** The technique is used for the portal's obsidian too. It still falls back to the old way on its own if no pocket can be made.
- **Stage 500:** a 3x3 lava pool two deep, the portal's obsidian run (`make_obsidian` with the portal flag set, as build_portal runs it).
  - With the fix: 10 in 110 s.
  - Without it: 8 in 150 s, with drain waits.
- **The pace bound.** A diamond pick takes about 9.4 s a block on obsidian (no Efficiency), so "10 in 90 s" isn't possible. The stage asserts 10 in 150 s with the moves. Stage 415 already does 12 in 119 s.

## Item 53: walk false positives while digging stairs home

- **Fix.** A walk end only counts when nothing was gained since that walk's last end: no block broken, no item got, not nearer, feet in the same cell. A leg that put itself back behind something else isn't counted either. Digging a staircase home breaks blocks between legs, so it no longer trips.
- **His lines to the user.** At most one line every 2 minutes, on top of once per goal. Live, there were 12 "stuck" lines in 2 minutes.

## Gate

All 20 groups are green, including 470-500. 470-500 was re-run after the tell limit: all passed.

## Restart

**Needs a game restart.**
