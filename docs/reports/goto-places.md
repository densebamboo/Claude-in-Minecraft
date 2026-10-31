# go_to a place in his own world when they're in another (Day to Day, 10:47 World 15)

Branch `bp/goto-places`, stage 507.

## Cause

- **Every go_to was refused across worlds.** `runSkill` refused every go_to when he and the user were in different worlds ("they're in another world"), whatever its target. "go_to fortress" in the Nether failed that way twice.
- **go_to didn't know the place names.** It resolved house, farm, chests and mine, but not the fortress or a portal.

## What changed

- **The cross-world refusal for go_to only applies when the target is them:** blank, you/them/the player, their name, or `where=near_them`.
- **go_to "fortress" in the Nether** heads to the fortress he found (`FortressScout.found`).
- **go_to "portal"** heads to a portal he knows in his own world (`PortalMap.resolve`).
- **The `[Claude where]` line** says "they're in another world" instead of "-1 blocks from them".

## Stage 507

Him in the Nether with a fortress found, them in the overworld.
- go_to fortress: "heading to the fortress I found at ... (31 blocks)", with a walk queued.
- go_to you: still "failed: they're in another world".

## Gate

All 20 groups are green, including 470-507.
- **0-86 failed once** on the golem stage, which is flaky on main too. It passed on the re-run.

## Restart

**Needs a game restart.**

## Not done

- **Exploring further along a fortress for its spawner** once the seen pieces are walked (the explore / POI feature).
