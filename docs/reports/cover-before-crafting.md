# Keep cover construction, safety wait, and teardown in order

## Proven cause

Nov 4 01:53:51: a skeleton caused a 13-block underground cover at 531,12,109. At 01:53:56 a walk to the crafting table at 522,16,110 resumed and WorkGoal pulled twelve nearby jobs ahead of that walk. Those jobs crossed the cover's safety wait: its lid was removed at 01:53:58, zero seconds after placement; sides followed while arrows reduced health 12 to 8, then 5 and 2. He died at 01:54:08. TempLedger already excludes active cover from its optional cleanup; this was the body's queued teardown, reordered by the walk optimizers.

Both optimizers searched the entire remaining queue: one pulled digs needed in the walk's destination cell, and the other pulled nearby digs/places before a longer walk. Neither respected a queued Wait. A crafting walk inserted during self-defence could also run before the unfinished active cover sequence.

## Change

Both searches stop at the first Wait. An unrelated long walk from active cover first restores the remaining cover steps in their original order: build, threat-aware wait, teardown, pickup, then the walk. Task identity preserves distinct jobs at the same coordinates. The cover's own moves and all ordinary work before the wait retain their existing rules. No new retry counters, combat thresholds, sensing, reach, ownership, structure, or hazard exceptions.

## Validation

- Stage 579 failed before, including a rerun with the final naturally supported lid: cover was torn down before its safety wait. Afterward all 17 blocks remain while a sensed skeleton is present; removing that threat permits teardown and pickup in 268 ticks (13.4 s), then the deferred walk resumes. Build/hold is checked by 9.5 s; cleanup bound is 20 s, allowing seventeen real cobble breaks with a stone pick and pickup.
- Stages 580 and 581 both failed before because a queued dig crossed its Wait. Both pass after, exercising each optimizer independently and checking the deferred dig resumes within twelve seconds.
- Existing stages 18 (nearby work/reach), 575 (rest before cleanup), and 577 (air escape) pass separately.
- All builds/tests use the isolated test copy, one server at a time. Test setup uses a NoAI skeleton to hold a real perception threat; this is an ordering and physical-cover regression, not an end-to-end arrow-combat test.

## Limits

An exploratory high-ceiling fixture exposed a separate unsupported-lid placement failure: the builder tries to reach a support from outside the box. The final fixture supplies natural ceiling support, matching the live mine. That general cover-construction issue remains unresolved. Live combat recurrence and the separate flooded-navigation loop still need validation; this fix does not claim every skeleton death or flooded recovery is resolved.
