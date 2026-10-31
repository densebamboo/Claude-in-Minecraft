# Gold is optional for the Nether: never turned back at the portal (Day to Day, 12:53 World 15)

Branch `bp/gold-optional`, stage 516.

## Cause

- **The old gold gate.** `ClaudeLife.onTravel` cancelled his own walk into the Nether when he had no gold piece: "not into the Nether with no gold piece: out of the portal, golden boots first". That queued an obtain of golden boots, which became a raw-gold fetch from a chest and 157 tasks.
- **The new rule.** The user (12:20): "1 gold piece is optional but not required and he could get that in the nether later". `bp/kit-tiers` already took gold out of gear_up, but the portal still blocked.

## What changed

- **onTravel never cancels.** With no gold piece it logs once a minute that he's going without it, with piglins kept clear of. He wears gold when he has it (equipArmor, unchanged).

## Stage 516

His own plan, `enter_portal` with no gold piece and not on the user's order.
- With the change: into the Nether.
- Without it: turned back, `obtain golden_boots` queued in front of the portal step.

## Gate

All 20 groups are green, including 470-516.
- **180-183 failed once** ("pod: asleep in the dark"). It passed on both re-runs alone.

## Restart

**Needs a game restart.**
