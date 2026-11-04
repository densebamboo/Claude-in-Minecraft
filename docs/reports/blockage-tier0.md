# Blockage solver Tier 0, and the plan-timeout fallback

Branch `bp/blockage-tier0`. Day to Day's queue, items 1 and 2.

## Tier 0 (backlog TOP FEATURES item 1)
- **When:** his walk finds no way at all (vanilla path, ActionPath, the nook cut, a tower), just before "no way to X ... skipping it".
- **What:** one more ActionPath search in the new mode `MAY_BEND`: as MAY_DIG, plus his own soft rules bend at +9 s a dig (the plan bending the fewest wins):
  - his own builds: mine shell, nook shell, house, any block his build systems put down (TempLedger);
  - the tree rule (a log cut out of a standing tree);
  - his walk's NO_DIG.
- **Hard rules never bend:** lava, water held back, their blocks (protectedBlock), structures, a mob after him, his cover, block entities, bedrock, gravel over nothing, the right tool.
- **After:** each bent cell of his build is patched back behind him (`nookCutsBack`, his scaffold block; logs aren't put back).
- **Log:** `[Claude blockage] no way to X from Y with his rules kept (MODE); bent: [rule at P, ...]`. That rule is the bug to fix. With no way even bent: `... even with his own rules bent: the world or a hard rule blocks it`.
- **Stage 600:** sealed in a 3x3 room of his own build blocks (floor and roof too), sent 7 blocks out. Passes: out through one bent wall in ~87 ticks, patched back. Without the change: he skips the walk.

## Plan fallback (item 2)
- **Live 08:31-09:03:** 25 min idle, 14 "never came back" lines. The cause was a MindLog parse error in the prompt (fixed in c9e9cd0), but the gap had no filler either way.
- **Now:** on the 45 s give-up, or when the prompt throws, an idle him takes `ruleStep`'s step (as with no planner), logged `[Claude plan] ...: the rules' step meanwhile: X (ClaudeLife.planFallback)`.
- **Stage 601:** passes in 2 ticks; without the change he stands idle.

## One owner for unreachable patches (item 3)
- **Live 11-04:** 840 bpReconcile, 338 bpIntegrity, 98 TempLedger.tidy and 85 Consent.keptOpen conflict lines. Each system re-queued the same refused patch every few seconds.
- **Now:** `entity/life/Parked`, one check in his task queue (`ClaudeEntity.tasks` addFirst/addLast).
  - A dig or fill from an upkeep system (ClaudeLife.bp*, mineIntegrity, TempLedger.*, Consent.*, the build systems) refused 3 times in 60 s is parked with its reason.
  - Those systems can't queue it again until he has more of the block, is 8+ off, or 2 min pass.
  - On release it also clears the other owners of "not again yet": the watchdog's write-off (`Watchdog.cleared`), the PLACE/REACH/BREAK attempt skips and the conflict count.
- **Logs:** `[Claude parked] fill at P (origin) refused: why ...` and `... back on (he has more of the block; ...)`.
- **Stage 610:** a build system re-queues a patch he has no block for, every second. Parked at the 3rd refusal; put down once he has the block. Without the change it never goes in: the watchdog's write-off and doPlace's 5-minute guard keep refusing it.

## Gate
- **Green:** 586-592, 594-595, 597, 600, 601, 94-96, 61.
- **Flaky on main, not from this branch (in the backlog):** 97, 593, and 596 in a range after 595.

## Restart
Hotswap should do: no new instance fields. It does add an enum constant (`ActionPath.Mode.MAY_BEND`) and new static members (`ActionPath.lastBent`, `bendWay`, `planFallback`). If the enhanced hotswap refuses the enum change, restart.
