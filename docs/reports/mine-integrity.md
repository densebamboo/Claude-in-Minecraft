# Mine integrity: keep the owner of the vein, drops and patches

Branch: `codex/mine-integrity`. Base: `f5fa34e`.

## Proven causes

The logging-first regression reproduced the World 16 diamond failure. `ClaudeLife.sealHeld` logged exposed ore, `queued dig: false`, then removed the only patch task capable of discovering and mining that ore. The test ended with an empty queue, no ore collected and the hole open.

A separate negative control removed only the new mine-drop guard. With raw iron two cells behind a patch and a preceding unsuccessful pickup, `WorkGoal.doPlace` logged that the iron "wasn't picked up ... leaving it, placing". The patch closed before pickup and stage 566 failed. The patch checked a larger pocket than its pickup's 1.6-block filter, and its recent-hold shortcut treated failure as permission to abandon the drop.

## Changes

- Keep held patches queued. The existing `doPlace` sequence owns mining the visible vein, collecting drops and restoring the opening.
- Prefer exposed diamonds in both patch-driven mining and `MineVisibleOres`. Patch-driven discovery uses the existing face-visibility check rather than selecting hidden ore behind the pocket.
- Restore each cell mined through a patch, with deeper holes before the mouth. `MineVisibleOres` also restores the small connected ore pocket, not only cells touching the walkway. Walkway cells remain open.
- A mine patch's pickup covers its whole pocket. A previous unsuccessful pickup, missing inventory room or pickup timeout does not authorize burying its drops. A timeout leaves the hole open and records pending repair.
- A missing correct pick or written-off dig leaves ore exposed, with the reason logged. Neither means permission to seal it in.
- Add the requested `[Claude ore] left exposed:` and `[Claude patch] ... covers a drop of his ...` diagnostics, naming their methods.
- Include stages 565-568 in the Linux gate.

## Regression scenarios

- **565:** Patch-driven three-ore vein, diamond first, one floor drop, all drops collected before any patch, all seven opening/ore cells restored within 20 seconds.
- **566:** A drop two cells behind a patch after a failed pickup; collect it before closure within 15 seconds. The negative control fails.
- **567:** No correct pick; keep ore and its opening intact.
- **568:** The same whole-vein and restoration checks through `MineVisibleOres`, within 20 seconds.

## Validation

All runs used `JAVA_TOOL_OPTIONS="-Dclaude.startStage=N -Dclaude.endStage=M" bash tools/test_in_copy.sh`, one server at a time. Final source passed:

| Stages | Result |
| --- | --- |
| 0-86 | ALL PASSED, including ore/pickup/patch stage 18, staircase stage 31, blueprint stages 77-81 and tree stage 86 |
| 193-194 | ALL PASSED: real mine construction, ore-wall restoration and torch placement across a turn |
| 555 | ALL PASSED: both endermen killed in 10 seconds, lowest health 20; combat-cover pickup remains intact |
| 565 | ALL PASSED: diamond first, all drops before any patch, seven cells restored in 226 ticks (11.3 seconds) |
| 566 | ALL PASSED: deeper drop collected before closure in 42 ticks (2.1 seconds) |
| 567 | ALL PASSED: no correct pick, ore stays exposed |
| 568 | ALL PASSED: the `MineVisibleOres` entry point, seven cells restored in 204 ticks (10.2 seconds) |

The logging-first stage failed before the ore-owner fix. Stage 566 also failed with only the mine-drop guard disabled. `git diff --check` passes.

The other full-suite groups and a live-world restart/playtest were not run. This branch covers the recurring mine-order defects above, not the blockage solver or goal-based progress detection.

## Deployment and live checks

**Game restart required:** Java methods and behavior changed. No live installation or restart was performed. All builds ran through `tools/test_in_copy.sh` from the independent clone, in `C:/Users/lipid/claude-mod-copies/test`. `Desktop/claude-mod`, its build outputs and API-key configuration were untouched.

Watch a mining pass with exposed diamonds and a drop beyond the opening. It should finish the vein and pickup before restoring the pocket. An ordinary mine patch logging `covers a drop of his` is still a bug. Repeated `left exposed` lines with a refusal need the next backlog item, the blockage solver; this branch does not implement that solver or relax hazard rules.
