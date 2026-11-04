# Nov 4: continue through aquifers; consider the other direction

User's updated requirement supersedes "plug water and end the branch": if water blocks forward travel and pivots, advance the existing blueprint while sealing the exposed water. The user also identified the dry -Z extension as another option.

## Changes

- Water no longer truncates a planned branch in `MineBlueprint.reconcile`; lava still does. Existing branch-completion/growth checks permit reopening his water plug. The body, not a blind pivot, handles water at the next cell.
- For a blueprint walking cell, `WorkGoal.doBreak` requires enough lining blocks for the whole row boundary before opening it. It opens the plug halves from the previous row and uses actual player right-clicks on visible, reachable supporting faces to seal exposed feeds before they flow. No `setBlock`, teleport or drowning waiver is used in production code. The old reactive source-plug/reopen loop does not run over this sequence.
- A known nearby aquifer requires 32 lining blocks even underground. Supply collection requests a total of 32, preventing a delta/total loop.
- `bpOpenRun` considers the opposite side even if another unfinished run exists. A blocked +Z run cannot hide a dry -Z extension. New corridors enter the blueprint before their digs, so the opening belongs to the planned walkway rather than an unrelated hole in a protected mine wall.

## Validation

- Stage 572 failed before the change: an already plugged face with water ahead and on both sides never progressed within 45 seconds. It now traverses the aquifer with full health and no submerged eyes, within the 45-second limit (317 ticks in the initial passing run).
- Stage 573 passes: an existing blocked +Z run, all existing branch slots refused, and a dry -Z option. The planner adds a real northbound run and preserves the station blocking the old run.
- Stage 574 passes: known aquifer work underground requests 32 lining blocks, and the supply request ends at 32 held.
- Stages 569-571 pass separately, as does stage 501's immediate lava-plug regression. Existing blueprint stages 77-81 pass as a group.
- The broad gate is not wholly green: current stage 44 assumes the now opt-in development bridge; unchanged main also reproduced the known chicken-under-leaves failure. House 63 and shipwreck 72 emitted soft failures in the 45-86 run. Those are not claimed fixed here.

## Live handoff

Built only in the designated test copy. For hot reload, load `TempLedger`, then `WorkGoal`, then `MineBlueprint`, then `ClaudeLife`, stopping on any redefine failure. This puts the sealing body in place before planners can schedule water crossings. No live checkout compile, daemon stop, game restart or direct world repair.

Watch `[Claude mine] water feed ... sealed before flow`, the lining-block supply request, and a real northbound run when that direction is dry. Any repeated invisible support-face refusal or stopped progress remains a bug to trace. This does not implement the general Haiku blockage solver, fix all night/sleep behavior, or clear every historical watchdog failure. Hot reload affects the current JVM only; the commits must be integrated before a restart to retain the changes.

Hot reload completed at approximately Nov 4 00:56:41, all four redefinitions accepted without debugger errors. Each live source file matched `origin/main` before deployment. The earlier counted-mining fix was observed in live log lines at 00:28:49-00:28:50.

**Live validation remains incomplete.** At 00:56:34, before this reload completed, he was already underwater at 534,15,120 on `recover_drops`, holding only a stone sword. At 00:56:36, air was 119; `breathingSpot` tried to break the cobblestone roof at 534,16,120, but the missing-tool path refused it and attempted re-tooling. Drowning damage began 00:56:46 and he died at 00:56:54 at 534,15,123. This is a separate flooded-room recovery/air-route failure, not a passing live aquifer-crossing result. It needs a regression beginning with no pick and a submerged recovery route beneath a solid roof. Do not infer that the full iron-mine/recovery incident is resolved from stages 572-574.
