# Nov 4: clear a stale missing-tool cleanup hold

At 00:19 the live litter log still reports "no tool that drops it" on his cobblestone, while the inventory trace lists a stone pick. `TempLedger.takeable` checks the current tool successfully, then refuses on the old stored reason. That reason only cleared after leaving and returning.

Clear exactly the missing-tool reason once the correct tool is present. Preserve reach failures, active cover, hazards, ownership and watchdog holds. Log the state change by method name.

Stage 570 failed before the fix with the stale missing-tool reason, and passes after it. It checks immediate cleanup eligibility after obtaining a pick, and that an unrelated reach refusal is retained. No world repairs or live-checkout builds. Existing method body only, suitable for targeted hot reload.
