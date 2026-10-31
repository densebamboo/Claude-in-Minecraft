# At his furnace, his own queued work first (Day to Day item 51)

Branch `bp/side-job-order`, stage 499.

## Cause

- **`rest` was put back, but behind the wrong things.** The furnace side job in `ClaudeLife.sideJobWhileCooking` did restore his queued work: after the new side job and the furnace take.
- **So his own unfinished work kept slipping.** A felled tree's pickups and its replant went behind a new tree, and the next round did the same again. The drops were left to despawn, and the sapling never went back. That's litter, and it breaks the tree etiquette rule.

## What changed

- **His own work goes first.** If he still has his own work queued (pickups, a replant, finishing a tree, digs, placements, torches), he does that while the batch cooks, then the furnace take. No new side job is taken, and it's logged.
- **Stage 499:** at his furnace with a pickup and a replant queued, and a tree near enough to tempt a side job.
  - With the change, the sapling goes back in 33 ticks and no new tree is taken on.
  - Without it, he went off to the new tree.

## Gate

All 20 groups are green, including 470-499.
- **122-125 failed once** ("iron level: no pair off a second tunnel"). It passed on both re-runs alone, and the new rule never fired in that group.

## Restart

**Needs a game restart.**
