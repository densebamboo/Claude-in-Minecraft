# Recovery checklist blocked by rejected junk

## Cause

On Nov 4 at 01:25:33, `recover_drops` chose the old bone death at 59,67,97 instead of the newer mine kit at 510,16,117. At 01:25:44 and 01:25:49 it rejected that bone pile as not worth the trip, but left the entry pending. `DeathLog.next` therefore selected it again. The watchdog recorded the same refusal twice. The earlier 01:20:43 mine death at 514,16,121 included ten raw iron.

A second error hid unloaded death locations: recovery substituted nearby loose items before checking whether the recorded death chunk was unloaded. Nearby junk could therefore make a distant, unseen kit appear worthless.

## Change

Reuse `DeathLog.abandon` when the selected, loaded death pile is explicitly rejected by the existing worth rule; queue recovery of the next pending pile. Check an unloaded recorded location before considering unrelated nearby drops. No new retry counters, valuation policy, sensing, movement, ownership, or hazard exceptions. Abandoning a checklist entry does not delete its items.

## Validation

- Stage 576 failed before: rejected junk remained pending and blocked the next pile.
- Stage 576 passes after: a loaded bone pile is retired, an unloaded raw-iron kit is selected within two decisions at tick 10, its entry remains pending, and the equipment gate still requires gearing up when the pack is empty.
- Existing stages 547 (unloaded Nether recovery equipment/portal gate) and 575 (rest before cleanup) are checked separately.

## Limits

This fixes pile selection, not drowning. The flooded drop-recovery deaths at 01:20 and 01:25 remain unresolved. Current piles have since been recorded as despawned; no items or world state were repaired. Full game-test gate has known baseline failures documented in the earlier session reports. Live reload must include the already deployed water-tunnel ClaudeLife changes; this topic branch deliberately excludes that separate fix.
