# A full pack: surplus stone first, not the sugar (Day to Day, 10:18 World 15)

Branch `bp/pack-stone`, stage 505.

## Cause

- **Too much stone kept.** `dumpJunk` kept 2 stacks of stone per type: 2 of cobblestone and 2 of cobbled deepslate, 238 stone in 4 slots.
- **So the "least use" rule dropped something tiny.** Nothing else was on the junk list, so the rule dropped the 2 sugar. That freed one slot for a moment.
- **And he picked it straight back up.** He was standing on it, and every player picks up what they touch (2 s toss delay, vanilla). So the drop repeated every 7 s.

## What changed

- **Stone kept is 2 stacks in total,** cobblestone and cobbled deepslate together. The surplus goes first, so the pack has real room.
- **Spider eyes are junk now.** Bones stay: bonemeal has uses.
- **No pickup refusal.** I didn't stop him picking up what he walks over: that would break the vanilla-rules rule. With real room made, the cycle doesn't start.

## Stage 505

A full pack: 2 stacks of cobble, 2 of cobbled deepslate, 2 sugar, the rest fish.
- **With the change:** 128 stone kept, the sugar stays, 3 slots free.
- **Without it:** all 238 stone kept.

## Gate

All 20 groups are green, including 470-505.

## Restart

**Needs a game restart.**
