# Seeing isn't hitting

Branch `bp/can-hit`. After 7345d37 his hasLineOfSight is Perception.clear, eye to eye, through glass, leaves and fences. That's right for sensing, but his arrows and swings were going by it, so he drew at a skeleton behind glass and hit the glass. He also "swung through" leaves: the server doesn't check blocks for a hit.

## What changed

- **`WorkGoal.canHit(mob)`:** a COLLIDER ray from his eye to its body's middle or its eye, as a player's crosshair stops at the first block. It's used for:
  - the melee reach (doAttack's inReach, the cover fight's swings, cfClose);
  - the bow (shootAt; the cover fight's shot at an out-of-reach blaze);
  - the "something between us" clearing in doAttack (within 5.5: leaves, glass or a fence between, the block cleared). That's what keeps stage 12 (a chicken boxed in by leaves) a fight he wins by clearing the leaves, not by swinging through them.
- Sensing (hasLineOfSight) is unchanged everywhere else.

## Stages

- **549 (new):** a skeleton behind a glass wall, a bow and 16 arrows. Seen through the glass, never shot: 0 arrows in 5 s, 2 of 2. On main's code: 4 arrows loosed at the glass.
- Green: 12 alone (2 of 2), 470-489, 539-546.
- 0-86 in the group: flaky; main's code with this test file fails it too (2 of 3). In the backlog as test debt.

## Restart

None beyond the jar.
