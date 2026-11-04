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

## Perception parity (item 4)
- **Perception.clear:** two layers of leaves are seen through, and the third stops sight, as in FarSight.cast. Before, it saw through any depth. Stage 611.
- **Seen memory:** `FarSight.sawSurface`/`known`. Every far-sight ray's first hit goes on his record for this dimension; the record is cleared on a dimension change.
  - A cell is known if he saw it, saw the floor up to 3 under it, is within 6 of it, or walked it (Route's trail).
  - `FarSight.lookAllRound`: 48x24 rays all round, taken before a Nether route plan and before fortress ends are picked.
- **NetherRoute:**
  - An unknown cell reads as netherrack. The goal's cells within 8 count as known: he knows where he's headed.
  - A plan going into unseen ground is cut one leg past where it enters and marked part-way, so Route plans again after a look round.
  - A climb over 8 gets a leg end every 8 up. Stage 612: a hidden tunnel reads as rock, 26-28 digs; walked without the change, 6 digs.
- **FortressEnds:**
  - An end he has seen wins.
  - With none seen, he heads for where the bridge he saw runs out. A player beside a fortress follows its bridge; never through its side.
- **ActionPath:** unchanged. Its legs (about 12 blocks) are within what he sees.
- **Re-proven:** 535 (sealed in at the portal: tunnels out in legs, 85 s; was 50 s seeing through rock) and 538 (153-179 s; was 185 s). 545 passes (70 s, bricks intact). 536, 537, 539, 540, 542-547 and 549-556 are green.
- **Also:** tools/test_in_copy.sh now clears the copy's src first. Files from another branch used to compile in.

## Fortress fight: the gap, not the run (item 7)
- **Live 11-04 13:59 death:**
  - Deck scouting ("no blaze in view") walked him into a skeleton, 3 wither skeletons and 5 blazes. He had 2 blocks and a stone axe.
  - The crowd rule fled, and wither skeletons blocked all four escape runs. Health went 20 to 0 in 10 s.
  - The 2-high gap that won the fight at 13:58 was 15 off, outside pickWither's radius of 10.
- **Now:**
  - `WorkGoal.gapFoe`: with a wither skeleton in a crowd and a gap it can't reach into (`FortressFight.coverFor`), he goes to the gap and fights from there. Hooked where the attacker path and doAttack would flee.
  - `FortressFight.lastGap`: the last 2-high gap he fought from, used again within 24 if it is still safe and he can reach it.
- **Log:** `[Claude fight] a crowd with a wither skeleton in it (...): to the gap at P ... (WorkGoal.gapFoe)`.
- **Stage 613:** a nether-brick wall with a 2-high nook, 3 wither skeletons and a skeleton. With the change: in the gap in 40 ticks, health 18.9, never fled. Without it, he runs.

## Stronghold travel through the Nether (item 6, first part)
- **Before:** find_stronghold walked a 1,416-block estimate across ocean at about 3.4 blocks/s.
- **Now (`EyeHunt.viaNether` / `netherLeg`):**
  - With an estimate over 300 off, he prices the Nether way: his nearest lit Overworld portal, plus the Nether leg x1.6 (off-road), plus 100 for the frame and the crossings.
  - When that is less than walking: to the portal, enter_portal, then in the Nether to estimate/8 at his own height. There, build_portal (its carried-obsidian path, any flat spot), enter_portal, and the hunt goes on by the estimate.
  - Without 10 obsidian and a flint and steel, he gets them first (make_obsidian, obtain): once a hunt, priced at +300. If they don't come, he walks.
  - Taken once a hunt.
- **Stage 614:** an estimate 1,200 off, his portal 20 off, the kit on him: in by the portal. With 4 obsidian: make_obsidian 6 first. Without the change he walks the 1,200.
- **Stage 557:** moved to 27000. It shared 20000 with stage 548, whose terrain left a roof 160 over him, so he never threw. It passes there (3 throws, 130 s).

## Gate
- **Green:** 586-592, 594-595, 597, 600, 601, 94-96, 61.
- **Flaky on main, not from this branch (in the backlog):** 97, 593, and 596 in a range after 595.

## Restart
Hotswap should do: no new instance fields. It does add an enum constant (`ActionPath.Mode.MAY_BEND`) and new static members (`ActionPath.lastBent`, `bendWay`, `planFallback`). If the enhanced hotswap refuses the enum change, restart.
