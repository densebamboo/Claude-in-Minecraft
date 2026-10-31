# Cloud session handoff

You get **all bugs and features** (the user's rule from 2026-10-31: Day to Day only monitors and reports). You are the **Big Picture** builder for the Claude-in-Minecraft mod. It's a NeoForge 1.21.1 mod: a bot player named Claude plays survival beside the user, Fat_Herobrine. You build features and big fixes on branches. A local session, **Day to Day**, runs the live game on the user's PC. It pulls your branches, runs the gate there and restarts the game.

## The bar

- **Body of a player, mind of an AI.** Every limit a survival player has (senses by line of sight, reach, damage, hunger), and every bit of player knowledge: metas, exact timers, speedrun techniques.
- **Expert player judgement.** Judge every behaviour against an experienced player. Deaths, loops, wasted time, a wrong chat line or a silent give-up are all bugs.
- **Root causes only.** Fix the decision that caused the problem, not the symptom. Prove the cause from logs or traces, and add logging first if you can't.
- The user's rules are in [RULES.md](RULES.md), one line each. Treat them as requirements.

## Workflow

1. Work on a branch per fix or feature: `bp/<name>`. When its gate is green, merge it into `main` yourself and push `main`. Day to Day pulls `main` and restarts the live game at the user's pause. Untested or experimental work stays on its branch.
2. Run the gate before you push: `bash tools/test_in_copy.sh` with `JAVA_TOOL_OPTIONS="-Dclaude.startStage=N -Dclaude.endStage=M"` per stage group. `bash tools/gate.sh` runs the main groups. Stages leak into each other, so run groups on their own.
   - Known flaky: 31, 121, 116, 229 (fine alone), 396 (blaze fight, about 2 in 3), 388 (fortress doorway, about 1 in 2; the walk ends at doPlace). Stages 235-236 need 234 first.
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

The hazard reflex layer (`WorkGoal.reflexes()`: fire, air and lava in every mode, stages 480-489) and the say-do check (stage 473) are on `main`. Build on them.

1. **No-progress watchdog.** This is the big one; the local session handed it over before starting. It is one layer under every skill and task:
   - Each action declares its progress measure: distance closing, a block broken, an item gained, health back.
   - The same action repeated with no progress gets marked failed in `Attempts`.
   - Then he tries the next way: another route, dig, bridge, pillar, go round.
   - Only then does he ask the user, saying exactly what failed.
   - The `[Claude conflict]` lines (`MindLog.refused`) are existing signal.

   Real cases, each a stage:
   - The long-walk hop that re-picked its own target every 2 s (fixed on main by a one-off check; the watchdog must catch the general case).
   - A sprint-jump missed 5 times in 10 s.
   - "breaking the blocks now" while only re-planning a walk.
   - Repeated "craft furnace -> failed: missing cobblestone".
   - Live 01:05: at 0.8 HP, on fire, with no food and hunger 16 (no regen), he kept walking a 21-step dig route across the Nether, spun beside lava and died in it. Low HP with no way to heal must stop the journey (food, out of fire, cover) before anything else.

   Read `docs/watchdog_notes.md` first: the local Big Picture notes (noticeStuck, its flaw, progress measures, the next-way ladder, stage ideas). `docs/drafts/patch_watchdog.py` (stage 474) is an UNTESTED draft: use it or not.
2. **Overworld biome facts.** He now has his exact biome (the `YOUR BIOME` line in `ClaudeChat.truthFor` and `ClaudeLife.situation`) and Nether biome mobs (in `Persona`). Add overworld biome knowledge for planning: villages, mob spawns, resources, structures. Then a stage that asks him where to find X.
3. **Procure distance.** It counts chest stock 290 blocks away, for example 4 cobbled deepslate, when he could mine 4 cobble with the pickaxe in his hand. The craft then fails before the fetch. Weigh the walk to a chest against gathering, and fetch before crafting.
4. **Death recovery across dimensions.** "go get your stuff" with his drops in the Nether failed at once, and he then claimed "got my stuff back". Plan it through `PortalMap` (walk to portal, through, to the drops) and report the real outcome.
5. **Spin on slopes.** Live logs show `[Claude move] spin ... (280-300 deg in 9-34 ticks)` on hills, even after the missed-jump fix (`ActionPath.missedHere`).
6. **Fire in the way is a move.** The user taught him this at 01:29: "you want to extinguish fire if its in your way". `ActionPath.hurts()` treats any fire cell as a no-go, so routes go round it. A player punches it out with one click. Make a fire cell a cheap "punch then walk" act (the fire reflex already punches with `gameMode.destroyBlock`). In the Nether there's no water: punch it out or cover it with cobble.
7. **Portal highways milestone.** The data model is in `PortalMap` (`addWay`, `partnerSpot`). Build Nether tunnels and highways between portals, and place new portals on them.

## State at handoff (2026-10-31)

- `main` holds the batch from 2026-10-30/31, including portal memory (`PortalMap`), the fire reflex, missed-jump avoidance, ExactSpot reset on death and dimension change, and the long-walk hop fix.
- Live world: "New World (14)". He is re-gearing in the overworld. His diamond kit and 13 blaze rods are in the Nether at -36 40 131.
- The bot's chat and planner models are `claude-haiku-5-5`, the user's choice for cost.
