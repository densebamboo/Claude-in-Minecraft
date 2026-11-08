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

## Move library (the user 11-08: "a lot of combat skills he can act on like dodging, blocking, falling back, rounding up for groups ... tactics for every enemy type and mixed groups")

Every move is a function the brain can pick each tick, priced in seconds of his time plus the HP it is expected to cost
(`Decide.hpS`), every factor a `Weights.w` under `combat.move.<name>.*`. A move carries its own review rule, so the fight
review says when a better move was there and not taken.

| Move | What he does | Pick it when |
|---|---|---|
| strike | the charged hit, facing the target | in reach, charge full |
| crit | jump timed to the charge, hit on the fall (1.5x) | on firm ground, one foe in reach, no creeper near |
| sweep | a charged sword hit on the ground, not sprinting: everything within 1 of the target takes it | 2+ melee bunched in front of him |
| sprint_knock | sprint into the first hit (extra knockback), W-tap to re-arm it | opening on a melee foe, pushing one off a ledge or out of a fuse |
| back_step | step out of its reach while the charge fills, back in to hit | melee with shorter reach than his 3 (zombies, husks, spiders) |
| block | shield up toward the threat, down for the swing | an arrow drawn at him, a creeper's hiss in blast range, a melee wind-up while he is charging |
| dodge | strafe across the line of fire / sidestep / jump | an arrow, fireball, trident, evoker fangs, a ravager's charge, a phantom's swoop with no shield |
| fall_back | retreat to a chosen spot: a 1-wide choke, a doorway, behind a block, up a step, toward light | 2+ melee in the open, a crowd he can't sweep, low HP with a choke near |
| round_up | kite in an arc so the group bunches in a line behind him, then turn and sweep | 3+ melee, room to circle, a sword |
| wall_off | place a block to cut a shooter's sight or split a group | a skeleton/pillager with a block between in reach; a second melee coming round the first |
| pillar | 2-3 blocks up, hit or shoot down | zombies/husks/drowned only (spiders climb), with blocks and time to build |
| dig_in | the hole/box (the cover-proximity rule: distance enough to finish) | can't win and can't outrun, no choke |
| bow | draw and loose with lead on a moving target | shooters out of reach, blazes, ghasts, endermen never, phantoms, from a pillar |
| bucket | water under a fall/knockback ledge, water on fire, water to slow a rush | knockback toward a drop, on fire, a crowd on open ground |
| eat | eat behind cover or with them knocked back | hunger under his regen line and a gap of 1.6 s |
| disengage | sprint away (food > 6) | the fight is dearer than leaving and it can't keep pace |
| target | focus order | every tick: lowest time-to-kill per HP/s it deals; a creeper in blast range first |

## Tactics per enemy (each a weight set + a move preference)

| Enemy | Tactic |
|---|---|
| zombie, husk, drowned on land | back_step + strike/crit; sweep a bunch; pillar beats them; drowned with a trident: dodge the throw, close in |
| baby zombie | it keeps pace and is small: fight, never run; crit timing on its jumps |
| zombie villager | as zombie (cure is a separate skill) |
| skeleton, stray, bogged | block or dodge the draw, close in zig-zag, wall_off to cut sight; from cover: bow back; stray slowness / bogged poison raise the cost of being hit |
| spider, cave spider | hit as it lands from its leap (time the leap), back_step, never pillar (it climbs); cave spider: poison, fits 1-high gaps: kill it fast with crits |
| creeper | sprint_knock it away at once, back off at the hiss (3+ blocks before the bang), block if he can't, never fight it in a crowd beside him; bow if he has one |
| enderman | never look at its face unless fighting; fight under a 2-high ceiling or in water's edge, crits; never bow |
| witch | rush: close fast (strafe its throws), hit; it heals: no pauses; drink milk/regen after |
| slime, magma cube | big first, small ones last; back_step from the bounces; magma: no fire under him |
| silverfish | don't break infested stone; out of the tunnel, block the hole |
| phantom | sleep prevents it; fight: hit on the swoop's low point, block/dodge the dive, bow on the circle |
| pillager | close in behind cover, block the bolt; bow back |
| vindicator | its hit is 13: block, back_step, crits; fight from a choke |
| evoker | dodge the fang lines (sideways), kill the vexes with sweeps, rush the evoker |
| vex | sweeps, they pass walls: back to open ground |
| ravager | block the roar/charge, dodge sideways, hit its side; pillar 3 beats it |
| guardian | out of its beam's sight, close in under it; elder: avoid without water breathing |
| breeze | its wind charge knocks up: no edges, bow from cover, close in on its landing |
| blaze, ghast, piglin, hoglin, wither skeleton | Big Picture's FortressFight/Nether code owns these; the brain calls into it |
| warden | never fight: sneak, leave |

## Mixed groups

- Creeper first while it's within its blast reach of him or of the group he'll stand in; otherwise last (its blast hits them too).
- Shooters behind melee: keep a melee foe between him and the shooter (it takes the arrows), or wall_off the line.
- Two melee kinds: fall_back to a choke so they come in one at a time; sweep when they bunch.
- Witch with others: the witch first if he can reach it in 2 s (its potions slow and poison him for the rest), else keep a melee foe in its line.
- Phantoms over a ground fight: get under a roof first (they can't follow).
- A spider in a zombie group: the spider first (it out-paces the run and climbs his pillar).
- Every pick is the group's sum: HP/s on him, time-to-kill, the order that brings the HP/s down fastest.
