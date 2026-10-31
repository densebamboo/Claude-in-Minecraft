# Cloud session handoff

You are the **Big Picture** builder for the Claude-in-Minecraft mod. It's a NeoForge 1.21.1 mod: a bot player named Claude plays survival beside the user, Fat_Herobrine. You build features and big fixes on branches. A local session, **Day to Day**, runs the live game on the user's PC. It pulls your branches, runs the gate there and restarts the game.

## The bar

- **Body of a player, mind of an AI.** Every limit a survival player has (senses by line of sight, reach, damage, hunger), and every bit of player knowledge: metas, exact timers, speedrun techniques.
- **Expert player judgement.** Judge every behaviour against an experienced player. Deaths, loops, wasted time, a wrong chat line or a silent give-up are all bugs.
- **Root causes only.** Fix the decision that caused the problem, not the symptom. Prove the cause from logs or traces, and add logging first if you can't.
- The user's rules are in [RULES.md](RULES.md), one line each. Treat them as requirements.

## Workflow

1. Work on a branch per feature: `bp/<feature>`. Never push to `main`. Day to Day merges.
2. Run the gate before you push: `bash tools/test_in_copy.sh` with `JAVA_TOOL_OPTIONS="-Dclaude.startStage=N -Dclaude.endStage=M"` per stage group. `bash tools/gate.sh` runs the main groups. Stages leak into each other, so run groups on their own.
   - Known flaky: 31, 121, 116, 229 (fine alone). Stages 235-236 need 234 first.
   - On Linux the script uses `./gradlew` online and a copy at `$HOME/claude-mod-copies/test`.
3. For each new behaviour, add a scenario stage in `src/main/java/com/claudemod/test/ClaudeBodyTest.java` that fails without your change. Real failures become permanent stages.
4. Put a report in the branch, `docs/reports/<feature>.md`, with:
   - what changed and why
   - which stages are green
   - whether it needs a game restart (new classes, fields or cross-class methods do)
   - anything Day to Day must watch for live
5. **You can't message Day to Day.** It watches GitHub for new pushes, so the branch and its report are the handoff. It can message you.

## Edit traps

- A `//` comment inserted after `{` mid-line swallows the rest of the line. Use `/* */`.
- A field declared between `@Override` and its method breaks the build.
- Shell heredocs halve backslashes, so write Java edits with a script file.

## Queue (take in order)

The local Big Picture session is building the **no-progress watchdog, hazard reflex layer and say-do check**. Don't duplicate them. Take these:

1. **Overworld biome facts.** He now has his exact biome (the `YOUR BIOME` line in `ClaudeChat.truthFor` and `ClaudeLife.situation`) and Nether biome mobs (in `Persona`). Add overworld biome knowledge for planning: villages, mob spawns, resources, structures. Then a stage that asks him where to find X.
2. **Procure distance.** It counts chest stock 290 blocks away, for example 4 cobbled deepslate, when he could mine 4 cobble with the pickaxe in his hand. The craft then fails before the fetch. Weigh the walk to a chest against gathering, and fetch before crafting.
3. **Death recovery across dimensions.** "go get your stuff" with his drops in the Nether failed at once, and he then claimed "got my stuff back". Plan it through `PortalMap` (walk to portal, through, to the drops) and report the real outcome.
4. **Spin on slopes.** Live logs show `[Claude move] spin ... (280-300 deg in 9-34 ticks)` on hills, even after the missed-jump fix (`ActionPath.missedHere`).
5. **Burning in the overworld.** Water nearby or a water bucket (pour, then pick it back up). Coordinate with the hazard layer if it has landed on main.
6. **Portal highways milestone.** The data model is in `PortalMap` (`addWay`, `partnerSpot`). Build Nether tunnels and highways between portals, and place new portals on them.

## State at handoff (2026-10-31)

- `main` holds the batch from 2026-10-30/31, including portal memory (`PortalMap`), the fire reflex, missed-jump avoidance, ExactSpot reset on death and dimension change, and the long-walk hop fix.
- Live world: "New World (14)". He is re-gearing in the overworld. His diamond kit and 13 blaze rods are in the Nether at -36 40 131.
- The bot's chat and planner models are `claude-haiku-5-5`, the user's choice for cost.
