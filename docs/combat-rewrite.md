# Combat rewrite (D2D, from 2026-11-08)

The user's asks:
- "He should be elite at combat in any scenario."
- "More detailed logging to enemies as well to track his combat performance."
- "Everything he does should be weighted for easier tuning later."
- "Indirect reinforcement learning ... very obvious where time was wasted, and where something should have been done differently."

## Measuring it (built first)

**CombatLog.** Every fight is one episode. The enemy side is recorded too: its HP, the hits each way, the shots it fired at him, how close it got, and whether it was killed.

**Review per fight.** One `[Claude combat review]` line per fight compares the ideal time (sprint in, every hit charged) with the time it took. It then lists each waste as "lost: what; better: what". The wastes it catches:
- a charged hit with a foe in reach and no swing;
- half-charged swings;
- misses;
- few crits;
- shots taken with a shield in hand;
- blast damage;
- hits taken running;
- flip-flops between picks.

**Credit per decision.** Every Decide pick inside a fight carries two things:
- the HP lost and dealt until the next pick;
- the combat weights it read when it was priced.

The costliest pick is named with its weights.

**`tools/combat_report.py`.** This is the indirect reinforcement signal. It totals the waste across fights and charges the HP lost after each pick to the weights that priced that pick. The top of that list is where tuning starts.

**Benchmark (stages 1080-1095).** Sixteen fixed fights:
- arenas: open, cave, tunnel, dead end, ledge;
- foes: zombies, skeletons, creepers, spider, witch, husks, enderman;
- gear: none (stone sword), iron, diamond.

A lethal hit is scored as a death, and the run carries on to the next fight. Run the benchmark on main as the baseline, then again after each phase. A phase lands only if:
- no fight gets worse;
- the totals go down: deaths, HP lost, seconds over the ideal.

## The brain (the rewrite)

One `CombatBrain` runs per tick while anything is on him. It replaces the scattered doAttack, doFlee, digInFromArrows and cover branches with a single loop:

1. **Threat model.** Each foe is read for:
   - its damage per hit at his armor, and its reach;
   - its attack clock: zombie cooldown, skeleton draw, creeper fuse and swell, witch throw;
   - its speed, and whether it can keep pace with him;
   - its line of sight to him.

   The sums give two numbers: the HP per second he takes standing here, and the time to kill everything with charged hits.
2. **Options, all priced in seconds of his time (`Decide.choose`).** Each option costs its time plus the HP it costs (`Decide.hpS`):
   - engage melee;
   - engage with crits;
   - shield-walk in;
   - strafe a shooter;
   - fall back to a choke point (1-wide, so one foe at a time);
   - wall off with blocks;
   - pillar up (zombies can't reach him; shoot with a bow from the top);
   - dig in;
   - sprint away;
   - water-bucket a fall or a knockback ledge;
   - eat mid-fight behind cover.

   No locks. Every factor is a named `Weights.w`: `combat.*`, `fight.*`, `flee.*`, `cover.*`.
3. **Micro, every tick:**
   - Swing the tick the charge is full.
   - Jump-crit when the foe is in reach on the way down.
   - Open with a sprint hit for knockback, and W-tap to repeat it.
   - Raise the shield on a skeleton's draw or a creeper's swell, and lower it to swing.
   - Back-step out of a creeper's 3-block blast radius at its hiss.
   - Hit a spider as it lands.
   - Keep endermen at the water or under a 2-high ceiling.
   - Target: the lowest time-to-kill per HP per second first.
4. **Commit.** A pick holds until its inputs change past `combat.repick_margin`, so it never flips every tick.

Things to keep unchanged:
- the expert-player standard;
- the engagement rule (no fights picked without food);
- the cover-proximity rule;
- the gear tiers;
- prices, not locks.

Big Picture owns FortressFight and the blaze code; ping them before touching either. Keep their shootsHere blaze FOLLOW_RANGE behaviour.

## Phases

| Phase | What changes | Benchmark target |
|---|---|---|
| 1 | Micro: charge timing, crits, sprint knockback, spider timing, shield timing | weak swings and misses to near 0, crits ≥ half the hits, the time over the ideal halved |
| 2 | Threat model and one fight/run/cover choice that commits | no flip-flop deaths (1081, 1091), hits taken running cut |
| 3 | Terrain: choke points, walls, pillar, dig-in under fire | 1081 and 1091 survive ungeared |
| 4 | Ranged: shield-walk, strafing, the bow from a pillar | the skeleton fights cost under 3 HP in iron |
| 5 | Creeper and explosives: back-step, knock out of the fuse, never in the radius at the bang | 0 blast HP |
| 6 | Specials: enderman, witch, husk crowds, then the Nether with BP | — |
