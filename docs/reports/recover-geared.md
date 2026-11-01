# Recovery: geared first when the pile waits

Branch `bp/recover-geared`. World 15 12:40: on recover_drops he went back into the fortress with 0 meals, no pick and 0 blocks, and was fireballed.

## Vanilla

A dropped item's 5-minute despawn timer only counts while its chunk is loaded. A pile in the other dimension, or far off, waits.

## What changed

`ClaudeLife` recover_drops, before the portal and the walk. When the next pending pile on his death list (`DeathLog.next`) is in an unloaded chunk, its clock is stopped, so he checks his kit first:
- a sword or axe (not wood) and a pickaxe;
- 4+ meals and 32+ blocks for a Nether pile, or 2+ meals for an overworld one.

Short of any of it: `gear_up`, then recover_drops, with the reason logged and said. One go at it every 10 min (counter "recoverGear", server ticks), so a kit he can't finish never holds the pile up for good. A pile whose chunk is loaded (its clock running) is gone for as before.

## Stages

- **547 (new):** a Nether pile on his list, its chunk unloaded. Unready, he says "my things ... keep while nobody's near them, so i'll gear up first (short of ...)" with gear_up queued first. Kitted, "through my portal for them" with enter_portal first.
- Green: 0-86 (stage 52, his drops at the end of his mine) and 160-162 (the despawn clocks).

## Restart

None needed beyond the jar: no new fields.
