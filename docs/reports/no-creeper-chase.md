# Attack on sight: never a creeper, never into a crowd (Day to Day, death 12:44:40 World 15)

Branch `bp/no-creeper-chase`, stage 515.

## Cause

- **The attack-on-sight rule picked the nearest hostile in sight.** `ClaudeLife.clearOnSight` went for it, creepers included, with no thought for other mobs about.
- **Live:** "a Creeper 15 blocks off in sight: after it", at night, with skeletons shooting him. 26.8 damage from 18 health, and he lost his full iron kit.

## What changed

- **Creepers are never gone after on sight.** One that closes in is the hazard layer's (the creeper reflex, stage 487).
- **Two or more hostiles in sight: none are gone after on sight.** That's the user's rule that crowds are left alone. It's logged with the count. Self-defence, when one hits him, is unchanged.

## Stage 515

Geared, by day:
- a creeper in sight: let be;
- a creeper and a zombie: let be;
- a lone zombie: taken on.

## Gate

All 20 groups are green, including 470-515.

## Restart

**Needs a game restart.**

## Not done (the same death chain and the user's new rule)

- **Death recovery under the user's 12:49 rule.** Important gear must come back inside the 5-minute despawn window, planned safely and never by running from the pile. Today it waits for first light and loses it. This needs its own branch: the drops' worth-versus-risk choice becomes a plan with the despawn clock in it.
- **The respawn at the world spawn** with his bed standing.
- **The raw internal fail string in chat.**
- **The blaze ledge death and the pathing standard.**
