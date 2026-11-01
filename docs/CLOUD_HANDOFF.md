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
   - **Linux full gate:** `bash tools/gate_linux.sh <repo> $HOME/claude-mod-copies/test`, about 30 min. It runs 0-86 through 470-489 as groups, and every stage from 491 on in its own server: they were written alone and trip on each other's leftovers in one run. Bump the `seq` end when you add a stage.
   - **The proxy:** the cloud sandbox's proxy and truststore are in `JAVA_TOOL_OPTIONS`, so append the stage flags rather than replacing it. The network policy must allow maven.neoforged.net, libraries.minecraft.net and the piston-*/launchermeta/resources mojang hosts. "No ALL line" means the build failed.
   - **A gap in stage numbers** used to end a run with ALL PASSED (that hid 491+ until 2026-11-01). `skipGap` now moves on to the next number.
   - **Flaky on main here too:** 0-86 "stairs turn", "dig in: died", "dig-in: not under cover" and "chicken under leaves" (re-run the group); 488 (passes with 1-2 health left); 245-247 fail on main (not gated).
   - **Only one test server at a time.** Two at once makes the box flaky, and the copy dir is shared. To write code while a gate runs, use a `git worktree` (the gate copies from the main checkout).
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

## The current major (the user, 00:25 2026-11-01: overrides any queue)

"Make sure he's capable of completing every major checkpoint in the game, and then once he can do that move on to the minors." Each checkpoint is done only when he completes it end to end, with no /tp or /give, proven by a long stage that starts from the checkpoint before. Minors wait in [bugs/backlog.md](bugs/backlog.md).

The checkpoints, in order:
1. wood and tools
2. iron gear
3. diamonds
4. obsidian and a portal
5. **REACH A FORTRESS from his portal (current)**
6. blaze rods
7. ender pearls
8. eyes of ender
9. the stronghold (eye throws, triangulation)
10. the End portal
11. the dragon

1-4 work live. 6 worked live once he was teleported onto a fortress: 14 rods on 2026-11-01 00:07.

**Reach a fortress: the gap is the route planner itself.**
- **What fails:** from a Nether ledge or a basalt pocket by his portal, `ActionPath` reports "no way at all" and lists only drops.
- **Why:** `WorkGoal.walkTo` plans only within 28 blocks (budget `climbBudget`), and Route's land plan is overworld-minded.
- **What's needed:** a long-range Nether planner that plans mixed routes over 100-300 blocks:
  - walk, dig, steps cut up, pillar (`towerTo` exists), bridge (2 wide over lava, railed; `bridgeToward` does 2 wide, `BridgeBlueprint` is the full spec and is used only in tests), tunnel;
  - each move costed in seconds and risk;
  - toward the nearest known fortress cell (`Atlas.find(g, level, "fortress")`, `FortressFight.deckCell`, `FortressScout.found`).
  - A coarse search over columns at about 4-block resolution, for example, then legs run by ActionPath.
- **Stages from World 15's real terrain** (rebuild it in the test box, bigger than 16x16 is fine):
  - the ledge 164 44 -7 to the deck at about 185 64 -14. The user named 3 routes: the left hill, the right hill, a staircase.
  - the basalt pocket 3 44 73 by portal #5 (7 53 2) to the near fortress at -65 68 135 (deck about -81 85 136). Its spawner is at -98 76 58.
  - the bridge drop at 188 68 -53.
- **Done means:** he reaches a deck from his portal in each case, unaided, without dying.

**Related, already on main:**
- the fortress deck walk (`FortressScout.deckStep`);
- spawner patience;
- blazes as shooters;
- no bridging under a shooter's eye;
- 2 wide over lava;
- the lip guard on jumps;
- the lava momentum guard;
- the lava-pocket escape;
- the tower to a high spot.

**For blaze rods, after reaching a fortress** (00:15 death at the spawner):
- leave or wall off the camp at low health and food;
- stop at the rod target;
- blaze contact damage (6) in the threat model.

## Branches not on main

- **`bp/parity`** (pushed, NOT gated): the first vanilla-parity fixes, from the user's 00:15 rule "every tool, behavior, function of his body ... 1 to 1 with vanilla".
  - In `ClaudeEntity.hurt`, a hit that sends no motion (shield-blocked) leaves his velocity as it was. His physics run server-side; a real player's client never gets that push.
  - No `setDeltaMovement` brakes by lava or in a portal: back-pedal or no keys instead.
  - The shield goes through `gameMode.useItem` with a shield check.
  - The lava-wall escape mines with `mineTick` at his pick's pace.
  - Run 480-489, 517, 520-521, 530 and the full gate; add a shield-vs-fireball stage (his fight code lowers the shield outside an Attack).
  - The rest of the parity audit:
    - respawn is a hand-made rejoin, not `PlayerList.respawn`;
    - `useOnAt` has no line-of-sight check;
    - doors are clicked with an empty hand;
    - `hold()` sets the selected slot directly;
    - diving needs no sneak;
    - sneak speed is hard-coded;
    - containers and crafting run without menus;
    - the bow is used without ammo or event checks.
  - Each needs a stage comparing him to a real ServerPlayer.
- **`bp/branch-writeoff`:** old, no stage.

## State at handoff (2026-11-01 about 00:30)

- `main` is 33caa5d. Reports for each batch are in `docs/reports/`.
- **Live world: "New World (15)".** His portal #4 is at 70 68 65 in the overworld, linked to Nether #5 at 7 53 2. His bed is at -2/-1 16 78; he still respawns "no bed: world spawn" (overworld now, correct) and why is unproven. The fix logs the reason.
- The bot's chat and planner models are `claude-haiku-5-5`.
- **The combat standard, queued after the majors** (the user, 00:18): one combat controller limited to a player's inputs and reach, but with TAS precision:
  - full-charge hits, crits, sprint knockback;
  - the shield timed to each hit;
  - fireball deflects;
  - bow lead;
  - several enemies handled by positioning and target choice;
  - each mob's trick.
- **The Nether highway builder and travel calculator** (the user, 23:55) come after the majors too. See `bugs/backlog.md`.
