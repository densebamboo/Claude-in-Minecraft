# Nov 4: mining must finish before smelting

Live evidence: 00:18:42-00:18:49, `obtain iron_ingot 12` expands to mining then smelting. Each `strip_mine` returns "back up to my iron tunnel ... for a new pair of branches" with zero raw iron. One second later, smelting fails for missing input. The queue log explicitly shows `forCraft false` and smelting next.

The blueprint mining count/completion guard was enabled only when crafting was the next skill. Apply it to every counted ore request except an explicitly pointed-at vein. Travel and branch passes keep the mining request ahead of its dependent steps; collecting the requested total (inventory plus own storage) finishes it immediately. No counters, extra planner calls or hazard overrides.

Stage 569 reproduces the exact travel handoff and failed before the fix. It passes after the fix, checks that mining stays ahead of smelting with zero ore, and verifies immediate completion at the requested count both with smelting next and as a standalone request.

Validation: stage 569 passed. In the broad 0-86 run, 0-43 passed before stage 44 failed because its old bridge test assumes the now opt-in bridge is enabled. Unchanged main separately hit the known chicken-under-leaves flake. A separate 45-86 run completed; stages 63 (house) and 72 (shipwreck) emitted SOFT FAIL diagnostics. This is not a clean full gate. `git diff --check` passes.

Deployment: existing method bodies only; suitable for targeted hot reload after checking the live source matches this base. All compilation is in the test copy. Watch for `countedOre true` and "mining step stays on"; smelting should not run before raw iron arrives. Other failures (night holds, unreachable ore and the refused mine-wall log) remain under investigation.
