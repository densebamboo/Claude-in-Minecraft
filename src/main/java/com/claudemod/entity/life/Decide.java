package com.claudemod.entity.life;

import com.claudemod.ClaudeMod;
import com.claudemod.entity.ClaudeEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * On a conflict he decides (the user 11-05: a conflict between his systems is resolved by his own decision-making, not just
 * detected). His options right then, each priced in seconds by the system that would carry it out; the cheapest feasible one is
 * taken and every price is said. An option he took for this goal stays open while it gains him ground, and is out once it didn't
 * (no counters: the measure is headway). When nothing of his own fits, the old skip stands (the planner gets a true conflict, the 3rd refusal, named).
 */
public final class Decide {
    private record Took(String option, double fromDist) {}
    private static final java.util.Map<java.util.UUID, java.util.Map<BlockPos, Took>> took = new java.util.HashMap<>();

    /**
     * Dusk out in the open (the user 11-05: "in most cases you can just flee to a safe area like his mine or a bed ... you don't need
     * to dig into a hole for 10 minutes just because the sun is going down"): where the night goes, priced in seconds. A bed or his
     * lit mine: the walk on his route, and the seconds he'd still be out once the game's first spawns can come (light 0 in the open:
     * tick 13188, 12969 in rain, now in thunder) weighted by what can hurt him (his armor, the hostiles he knows of, low HP). The hole:
     * its dig and the rest of the night lost in it. The cheapest is taken; every price is said. A free village bed at bed time is the
     * sleep rule's, before this. shortcut: walk time is the route's length at a sprint, not a mob-aware route; take Big Picture's
     * mob-aware ActionPath price once it lands.
     */
    /** The night in a hole dug here, in seconds of his time: its dig and the rest of the night lost in it. */
    private static double w(String name, double def) { return Weights.w(name, def); } // (every number in a weighing is tunable: config/claude/weights.json)
    // ---- the common piece: every weighed choice is options priced in seconds of his time, fed to choose() ----
    /** One option: its name, its price in seconds, null or why it can't be taken (out), and what the price is made of. */
    public record Option(String name, double s, String no, String detail) {
        public static Option of(String name, double s, String detail) { return new Option(name, s, null, detail == null ? "" : detail); }
        public static Option out(String name, String no) { return new Option(name, Double.MAX_VALUE, no, ""); }
    }
    /** The cheapest option he can take (null: none), and, when say, one [Claude decide] line with every price: "area (context): a ~N s (..); b out (..): a". */
    public static Option choose(ClaudeEntity g, String area, String context, boolean say, java.util.List<Option> options) {
        Option best = null; for (Option o : options) if (o.no() == null && (best == null || o.s() < best.s())) best = o;
        if (say) { java.util.List<String> said = new java.util.ArrayList<>(); for (Option o : options) said.add(o.no() != null ? o.name() + " out (" + o.no() + ")" : o.name() + " ~" + Math.round(o.s()) + " s" + (o.detail().isEmpty() ? "" : " (" + o.detail() + ")"));
            ClaudeMod.LOGGER.info("[Claude decide] {}{}: {}: {} (Decide.choose)", area, context == null || context.isEmpty() ? "" : " (" + context + ")", String.join("; ", said), best == null ? "none he can take" : best.name()); }
        CombatLog.decision(g, area, best == null ? "none" : best.name(), best == null ? 0 : best.s());
        return best;
    }
    /** The tick the game's first spawns can come in the open (light 0): 13188, 12969 in rain, now in thunder. */
    public static long darkAt(ServerLevel level, long tod) { return level.isThundering() ? tod : level.isRaining() ? 12969 : 13188; }
    /**
     * The HP he can expect to lose over the next s seconds out in the open from tick tod: each hostile he knows of ~a hit every
     * 1/danger.hits_per_s_per_hostile s while he passes; the night's spawns (from darkAt to dawn, 23460) at danger.hits_per_s_open_night;
     * the Nether's own at danger.hits_per_s_nether; his armor takes its share. The one danger estimate every price calls.
     * shortcut: a player's rough odds, not a mob-aware route; take a route's own exposure once ActionPath/NetherRoute price it.
     */
    public static double danger(ClaudeEntity g, ServerLevel level, long tod, double s, int hostiles) {
        double rate = w("danger.hits_per_s_per_hostile", 0.15) * hostiles * s;
        if (level.dimension() == net.minecraft.world.level.Level.NETHER) rate += w("danger.hits_per_s_nether", 0.02) * s;
        else if (!level.dimensionType().hasFixedTime()) { double toDark = Math.max(0, darkAt(level, tod) - tod) / 20.0, toDawn = tod > 23460 ? 0 : (23460 - tod) / 20.0; rate += Math.max(0, Math.min(s, toDawn) - toDark) * w("danger.hits_per_s_open_night", 0.05); }
        return hitHp() * armorF(g) * rate;
    }
    /**
     * HP lost, as seconds of his time. Sure damage (fire left, a fall) comes off his health first; the hits are a chance, each
     * perHit HP (through his armor), expected hits/perHit of them. Every HP costs what Food.sPerHp says it takes to win back
     * (vanilla's regen at his food and saturation, eating what he carries; food.no_food_hp_s an HP with nothing to eat); on top, the chance enough hits land to put him under
     * danger.min_hp_left costs danger.under_floor_s, and the chance they kill him danger.death_s. A steep price, never a refusal:
     * at 2 HP one zombie hit is death, so even a short walk in the dark prices high; at full health the same walk is cheap.
     */
    public static double hpS(ClaudeEntity g, double sure, double hits, double perHit) { return hpS(g, g.getHealth(), sure, hits, perHit, Integer.MAX_VALUE); }
    /** The same at the given health, with at most `most` hits able to land (a mob's swing has its cooldown: a 2 s fight with one zombie is 2-3 hits, never 6). */
    public static double hpS(ClaudeEntity g, double health, double sure, double hits, double perHit, int most) { var fd = g.getFoodData(); return hpS(g, health, sure, hits, perHit, most, fd.getFoodLevel(), fd.getSaturationLevel()); }
    /** The same at the given food and saturation (a what-if: "ready" after gear_up, fed). */
    public static double hpS(ClaudeEntity g, double health, double sure, double hits, double perHit, int most, double food, double sat) {
        double per = Food.sPerHp(g, Math.max(0, sure) + Math.max(0, hits), food, sat), h0 = health - Math.max(0, sure); // (vanilla regen at his food and saturation, eating what he carries: Big Picture's Food)
        double s = (Math.max(0, sure) + Math.max(0, hits)) * per;
        if (h0 <= 0) return s + w("danger.death_s", 1800);
        double floor = w("danger.min_hp_left", 4), ph = Math.max(0.5, perHit), lam = Math.max(0, hits) / ph;
        int kDie = (int) Math.ceil(h0 / ph), kFloor = (int) Math.ceil((h0 - floor) / ph);
        return s + (kDie > most ? 0 : atLeast(lam, kDie)) * w("danger.death_s", 1800) + (kFloor > most ? 0 : atLeast(lam, kFloor)) * w("danger.under_floor_s", 300);
    }
    /** Hits at hitHp() each (a zombie's, Normal) through his armor. */
    public static double hpS(ClaudeEntity g, double hits) { return hpS(g, 0, hits, hitHp() * armorF(g)); }
    /** The chance of k or more hits when lam are expected (Poisson). */
    static double atLeast(double lam, int k) { if (k <= 0) return 1; double term = Math.exp(-lam), cum = term; for (int i = 1; i < k; i++) { term *= lam / i; cum += term; } return Math.max(0, 1 - cum); }
    /** Seconds a block at a sprint, the walk prices' speed. */
    public static double sprint() { return w("move.sprint_blocks_per_s", 5.6); }
    public static double holeS(long tod) { return w("night.hole_dig_s", 10) + Math.max(0, 23460 - Math.max(tod, 12542)) / 20.0; }
    /**
     * A walk of walkS seconds through the open from now (tick tod), priced in seconds of his time: the walk plus the hits he can
     * expect on it (each known hostile ~0.15 a second while he passes; one every 20 s once the game's first spawns can come: light 0
     * in the open, tick 13188, 12969 in rain, now in thunder; his armor takes its share), 3 HP a hit (Normal), 10 s an HP to win back.
     * The hits are priced by hpS (their chance to put him under his floor or kill him): a price, not a refusal.
     * shortcut: rates are a player's rough odds, not a mob-aware route; take Big Picture's ActionPath/NetherRoute hit estimate once it lands.
     */
    public static double walkS(ClaudeEntity g, ServerLevel level, long tod, double walkS, int hostiles) {
        return walkS + hpS(g, danger(g, level, tod, walkS, hostiles)); // (the hits priced by their chances, hpS: a price, not a refusal) // (no walk that may take half his health or leave him under 2 hearts: a hard limit at 10 HP kept a 3-heart player from an 11 s walk with nothing about)
    }
    /** The share of a hit his armor lets through (armor points over danger.armor_full: vanilla's own curve, roughly). */
    public static double armorF(ClaudeEntity g) { return 1.0 - Math.min(20, g.getArmorValue()) / w("danger.armor_full", 25); }
    public static double hitHp() { return w("danger.hit_hp", 3); }
    /** Known hostiles that could meet him on the walk from a to b: within danger.walk_side of the line and danger.walk_dy of its height (live 06:17 World 17: 9 known, creepers in the caves under him, priced a 4 s walk to his lit mine at ~650 s; he dug a hole). */
    public static int hostilesAlong(ClaudeEntity g, BlockPos a, BlockPos b) {
        net.minecraft.world.phys.Vec3 A = net.minecraft.world.phys.Vec3.atBottomCenterOf(a), AB = net.minecraft.world.phys.Vec3.atBottomCenterOf(b).subtract(A);
        double l2 = Math.max(1e-6, AB.x * AB.x + AB.z * AB.z), side = w("danger.walk_side", 8), dy = w("danger.walk_dy", 4); int n = 0;
        for (var m : Perception.known(g, net.minecraft.world.entity.monster.Monster.class, g.getBoundingBox().inflate(32), x -> x.isAlive())) {
            double t = Math.max(0, Math.min(1, ((m.getX() - A.x) * AB.x + (m.getZ() - A.z) * AB.z) / l2));
            double ex = m.getX() - (A.x + AB.x * t), ez = m.getZ() - (A.z + AB.z * t);
            if (ex * ex + ez * ez <= side * side && Math.abs(m.getY() - (A.y + AB.y * t)) <= dy) n++;
        }
        return n;
    }
    public static int hostilesKnown(ClaudeEntity g) { int n = 0; for (var m : Perception.known(g, net.minecraft.world.entity.monster.Monster.class, g.getBoundingBox().inflate(32), x -> x.isAlive())) n++; return n; }

    public static void dusk(ClaudeEntity g, ServerLevel level, long tod) {
        BlockPos me = g.blockPosition(); java.util.List<String> said = new java.util.ArrayList<>();
        Object[] pick = nightSpot(g, level, tod, said, false); String best = (String) pick[0]; BlockPos to = (BlockPos) pick[1];
        String key = best;
        if (to != null) { final BlockPos to0 = to; if (me.distSqr(to0) > 3 * 3 && g.tasks.stream().noneMatch(t -> t instanceof Task.GoTo go && go.pos().distSqr(to0) <= 4) && !(g.currentTask instanceof Task.GoTo cg && cg.pos().distSqr(to0) <= 4)) g.tasks.addFirst(new Task.GoTo(to0, 2.0)); else key = null; }
        else if (best.equals("hole") && g.skillQueue.stream().noneMatch(s0 -> s0.skill().equals("shelter"))) g.skillQueue.addFirst(new Skills.Step("shelter", "", 1)); else if (best.equals("hole")) key = null;
        if (key != null) ClaudeMod.LOGGER.info("[Claude decide] dusk (tick {}, the first spawns in {} s, {} hostile(s) known, armor {}): {}: {} (Decide.dusk)", tod, (int) Math.max(0, (level.isThundering() ? tod : level.isRaining() ? 12969 : 13188) - tod) / 20, hostilesKnown(g), g.getArmorValue(), String.join("; ", said), best);
    }

    /**
     * The night's spots priced against a hole here (dusk, and the shelter step all night): {name, where} of the best, where null
     * for the hole or carrying on. far: the shelter step's extra spots, free beds he has seen and a village he knows (a player
     * knows a village has beds); a spot picked in the last minute is out (the walk or the bed there didn't take: no picking it again in a loop).
     */
    public static Object[] nightSpot(ClaudeEntity g, ServerLevel level, long tod, java.util.List<String> said, boolean far) {
        BlockPos me = g.blockPosition();
        long dark = level.isThundering() ? tod : level.isRaining() ? 12969 : 13188;
        double toDark = Math.max(0, dark - tod) / 20.0;
        int hostiles = hostilesKnown(g);
        String best = "hole"; BlockPos to = null; double bestS = holeS(tod);
        if (com.claudemod.director.ClaudeLife.nightGearNo(g, level, me) == null) { best = "carry on out here"; bestS = 0; said.add("carry on out here ~0 s (the night's no danger to him as he is)"); }
        java.util.List<Object[]> spots = new java.util.ArrayList<>();
        for (BlockPos b : g.myStations) if (level.isLoaded(b) && level.getBlockState(b).is(net.minecraft.tags.BlockTags.BEDS)) spots.add(new Object[] {"his bed", b, 0.0});
        if (g.house != null && g.house.shellStands(level) && level.getBlockState(g.house.bedHead()).is(net.minecraft.tags.BlockTags.BEDS)) spots.add(new Object[] {"his house's bed", g.house.bedHead(), 0.0});
        if (g.blueprint != null && !g.blueprint.isEmpty()) spots.add(new Object[] {"his lit mine", g.blueprint.entrance, 0.0});
        for (BlockPos b : com.claudemod.director.ClaudeLife.bedsSeen(g)) if (level.isLoaded(b) && level.getBlockState(b).is(net.minecraft.tags.BlockTags.BEDS)) spots.add(new Object[] {"a free bed", b, 0.0}); // (at dusk too: live 20:14 11-07 the village bed 34 off was left out, he walked 154 for his mine, the night out ended in a death; stage 1060)
        if (far) {
            var vil = com.claudemod.director.ClaudeLife.knownVillage(g, (int) w("night.spot_max_route", 400), s0 -> true);
            if (vil != null) spots.add(new Object[] {"the village's beds", vil.nearestCell(g.position()), w("night.village_bed_find_s", 20)}); // (beds he hasn't seen yet: a look round for one)
        }
        boolean mineHere = g.blueprint != null && !g.blueprint.isEmpty() && (g.blueprint.touches(me) || com.claudemod.director.ClaudeLife.inHisMine(g, me));
        if (!mineHere && g.inventory.count(s0 -> s0.is(net.minecraft.tags.ItemTags.BEDS)) > 0) spots.add(new Object[] {"his bed down here", me, w("night.bed_here_s", 60)}); // (the bed he carries, down where he stands: his respawn point out in the open and the bed to take up again; a short walk to his own safety beats it, the user 11-06: "he could have easily gone to his mine or the village")
        for (Object[] sp : spots) {
            BlockPos at = (BlockPos) sp[1];
            String no = ((String) sp[0]).contains("bed") && !((String) sp[0]).contains("village") ? com.claudemod.director.ClaudeLife.bedNoGo(g, level, at) : null; // (the sleep skill's own judgement of the bed: one it won't sleep in is no night's end; World 17 21:12, incident 17: an obstructed bed picked every 20 s, four 87-block legs)
            if (no != null) { said.add(sp[0] + " at " + at.toShortString() + " out (" + no + ")"); continue; }
            Integer picked = g.attempts.counters.get("nightSpot:" + at.asLong());
            if (far && picked != null && g.tickCount - picked < 20 * 60) { said.add(sp[0] + " at " + at.toShortString() + " out (picked " + (g.tickCount - picked) / 20 + " s ago and it didn't give him the night)"); continue; }
            if (!mineHere && g.blueprint != null && !g.blueprint.isEmpty() && (g.blueprint.touches(at) || com.claudemod.director.ClaudeLife.inHisMine(g, at))) { sp[1] = at = g.blueprint.entrance; } // in his mine: safe at its mouth, his own lit tunnels from there (the user 11-06: distance to safety from the entrance, never to the bed at its far end)
            int dist = com.claudemod.director.ClaudeLife.routeDist(g, me, at);
            if (dist > w("night.spot_max_route", 400)) continue; // (out of any night's reach: not an option)
            double walk = dist / sprint(), out = Math.max(0, walk - toDark), s = walkS(g, level, tod, walk, hostilesAlong(g, me, at)) + (double) sp[2];
            said.add(sp[0] + " " + dist + " off " + (s == Double.MAX_VALUE ? "out (the hits on the way would take over half his health)" : "~" + (int) s + " s") + " (" + (int) walk + " s walk" + (out > 0 ? ", " + (int) out + " s of it after the spawns" : ", there before the spawns") + ((double) sp[2] > 0 ? ", " + (int) (double) sp[2] + " s to find one" : "") + ")");
            if (s < bestS) { bestS = s; best = (String) sp[0]; to = at; }
        }
        said.add("a hole here ~" + (int) holeS(tod) + " s (the night in it)");
        return new Object[] {best, to};
    }

    /** The shelter step's night walk: his mine, a bed or a village when one beats the cover here (null: the cover here). */
    public static String nightWalk(ClaudeEntity g, ServerLevel level, Skills.Step st) {
        if (level.dimension() != net.minecraft.world.level.Level.OVERWORLD || com.claudemod.director.ClaudeLife.dayHere(level) && !level.isThundering()) return null;
        long tod = level.getDayTime() % 24000; java.util.List<String> said = new java.util.ArrayList<>();
        Object[] pick = nightSpot(g, level, tod, said, true); String best = (String) pick[0]; BlockPos to = (BlockPos) pick[1];
        ClaudeMod.LOGGER.info("[Claude decide] night cover (tick {}, {} hostile(s) known, armor {}, health {}): {}: {} (Decide.nightWalk)", tod, hostilesKnown(g), g.getArmorValue(), (int) g.getHealth(), String.join("; ", said), best);
        if (to == null) return null;
        g.attempts.counters.put("nightSpot:" + to.asLong(), g.tickCount);
        int off = (int) Math.sqrt(g.blockPosition().distSqr(to));
        if (best.equals("his lit mine")) { g.tasks.add(new Task.GoTo(to, 2.0)); g.skillQueue.addFirst(st); return "night: to my mine for cover, " + off + " blocks off (better than a hole out here)"; } // (at its mouth the shelter step takes him down its rows)
        if (best.equals("the village's beds")) { g.tasks.add(new Task.GoTo(to, 4.0)); g.skillQueue.addFirst(new Skills.Step("sleep", "", 1)); return "night: to the village, " + off + " blocks off, to sleep in one of its beds"; }
        g.skillQueue.addFirst(new Skills.Step("sleep", "", 1)); return "night: " + best + " at " + to.toShortString() + ", " + off + " blocks off: going to sleep in it";
    }

    /** What a run back to a pile in an unloaded chunk needs first (empty: go now), and the reasons, priced. */
    public record RunKit(java.util.List<Skills.Step> first, String why) {}
    /**
     * The run back to his pile, its needs from the run itself (the user 11-05: "all you need to run to get your stuff back is a pick
     * and some blocks, and maybe some food, sometimes not even any of those. It all depends on the context"; "most times I'll run
     * in there without gearing up again"). A pick, any pick, only if the way digs (the Nether, or a pile under the ground): the rock
     * gives the blocks for a bridge or cover. Food only when he can't sprint (food 6 or under) with nothing on him to eat. No sword,
     * armor or full kit: what fell is what he's going for (a pile with his iron kit in it is the kit). Was: a sword, a non-wooden
     * pick, 4 meals and 32 blocks in the Nether, else gear_up's full iron kit first (World 17 20:41: all his iron was in the pile).
     */
    public static RunKit runKit(ClaudeEntity g, ServerLevel level, DeathLog.Death dn) {
        boolean nether = dn.dim.contains("nether"), digs = nether || dn.at.getY() < w("recovery.digs_below_y", 50);
        boolean pick = g.inventory.count(x -> x.getItem() instanceof net.minecraft.world.item.PickaxeItem) > 0;
        int meals = com.claudemod.director.Kit.food(g), food = g.getFoodData().getFoodLevel(), cobble = g.inventory.count(x -> x.is(net.minecraft.world.item.Items.COBBLESTONE) || x.is(net.minecraft.world.item.Items.COBBLED_DEEPSLATE) || x.is(net.minecraft.world.item.Items.BLACKSTONE));
        BlockPos from = g.blockPosition(); String fromName = "here";
        if (!dn.dim.equals(level.dimension().location().getPath())) { double bd = Double.MAX_VALUE; for (var p : PortalMap.net(g).portals) if (dn.dim.equals(p.dim) && "lit".equals(p.state) && p.at.distSqr(dn.at) < bd) { bd = p.at.distSqr(dn.at); from = p.at; fromName = "his portal at " + p.where(); } }
        int runS = fromName.equals("here") && !dn.dim.equals(level.dimension().location().getPath()) ? -1 : (int) (Math.sqrt(from.distSqr(dn.at)) / sprint());
        java.util.List<Skills.Step> first = new java.util.ArrayList<>(); java.util.List<String> said = new java.util.ArrayList<>();
        if (digs && !pick) { first.add(new Skills.Step("craft", cobble >= 3 ? "stone_pickaxe" : "wooden_pickaxe", 1)); said.add("a pick first (the way digs: " + (nether ? "the Nether" : "under the ground") + "; the rock gives the blocks)"); }
        else said.add(digs ? "his pick does (the rock gives the blocks)" : "no pick needed (an open way)");
        if (food <= 6 && meals == 0) { first.add(new Skills.Step("gear_up", "basic", 1)); said.add("food first (food " + food + ": no sprinting, nothing to eat)"); }
        else said.add("food " + food + (meals > 0 ? ", " + meals + " meal(s)" : "") + " does");
        if (nether) { // piglins: a gold piece or the hits (the gold-piece rule: going without is a cost priced by the job; World 17 20:51, no gold, no armor, dead to piglins 20 -> 4 in 2 s on the run back)
            boolean gold = false; for (var sl : new net.minecraft.world.entity.EquipmentSlot[] {net.minecraft.world.entity.EquipmentSlot.HEAD, net.minecraft.world.entity.EquipmentSlot.CHEST, net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.FEET}) if (g.getItemBySlot(sl).getItem() instanceof net.minecraft.world.item.ArmorItem a && a.getMaterial().equals(net.minecraft.world.item.ArmorMaterials.GOLD)) gold = true;
            if (!gold && g.inventory.count(x -> x.getItem() instanceof net.minecraft.world.item.ArmorItem a && a.getMaterial().equals(net.minecraft.world.item.ArmorMaterials.GOLD)) > 0) gold = true; // (on him: his wear puts it on)
            if (!gold) {
                double there = (runS < 0 ? w("recovery.nether_run_s_unknown", 60) : runS) * 2, armorF = armorF(g), dmg = there * w("recovery.piglin_hits_per_s_no_gold", 0.05) * w("recovery.piglin_hit_hp", 7) * armorF; // (~a piglin hit every 20 s in the open Nether without gold, 7 HP: a player's rough odds; shortcut: the route's own biomes and the piglins Perception knows of, once travel() prices them)
                int goldHere = com.claudemod.director.ClaudeLife.haveFor(g, level, net.minecraft.world.item.Items.GOLD_INGOT); // (his pack and his chests)
                var gp = goldHere < 4 ? null : choose(g, "recovery.gold_piece", "", false, java.util.List.of(Option.of("golden boots first", w("recovery.gold_boots_craft_s", 15), ""), Option.of("straight there", hpS(g, 0, dmg, w("recovery.piglin_hit_hp", 7) * armorF), ""))); // (the boots' craft against the piglin hits priced as his time, hpS)
                if (gp != null && gp.name().equals("golden boots first")) { first.add(new Skills.Step("craft", "golden_boots", 1)); said.add("golden boots first (" + goldHere + " gold to hand: a craft): no gold piece, ~" + (int) dmg + " HP of piglin hits on the ~" + (int) there + " s there and back, ~" + (int) hpS(g, 0, dmg, w("recovery.piglin_hit_hp", 7) * armorF) + " s of his time at " + (int) g.getHealth() + " health (armor " + g.getArmorValue() + "), against the boots' ~" + (int) w("recovery.gold_boots_craft_s", 15) + " s"); }
                else said.add("no gold piece" + (goldHere >= 4 ? "" : " and none to hand") + ": ~" + (int) dmg + " HP of piglin hits on the ~" + (int) there + " s if he fights; straight there, no fights till he's in what fell (the user 11-05: \"avoiding combat altogether until you get suited with your pickups\")");
            } else said.add("a gold piece on him: the piglins let him be");
        }
        if (nether && level.getServer() != null) { // the fortress that killed him: its blazes and wither skeletons round the pile are priced too, never the piglins alone (live 00:39 11-08: in gold boots and a wooden sword, fireballed dead 85 short of it)
            ServerLevel nl = level.getServer().getLevel(net.minecraft.world.level.Level.NETHER);
            BlockPos fort = nl == null ? null : Atlas.find(g, nl, "fortress"); // shortcut: the fortress nearest him, fine with one or two on record; a per-pile lookup if he knows many
            double fr = w("recovery.fortress_r", 192); // (a fortress runs ~200 across and his atlas holds one sighting of it: both live piles were ~120 off it, on its own bridges)
            if (fort != null && fort.distSqr(dn.at) <= fr * fr) {
                boolean sword = g.inventory.count(x -> x.getItem() instanceof net.minecraft.world.item.SwordItem sw && sw.getTier() != net.minecraft.world.item.Tiers.WOOD && sw.getTier() != net.minecraft.world.item.Tiers.GOLD) > 0;
                double armorF = armorF(g), hits = w("recovery.fortress_hits", 4) * (sword ? 1 : w("recovery.fortress_no_sword_x", 1.5)), perHit = w("recovery.fortress_hit_hp", 6) * armorF; // (fireballs and wither swords round a fortress pile: a few hits there and back, more with nothing to kill them fast)
                double expect = hits * perHit, keep = w("recovery.fortress_hp_keep", 6); // (what he'd take against what he has, keeping a margin to get out: 36 HP at 20 health read "he can take it" priced in seconds)
                if (expect > g.getHealth() - keep) { first.add(new Skills.Step("gear_up", "", 1)); said.add("gear up first: the pile is by the fortress at " + fort.toShortString() + " (blazes and wither skeletons): ~" + (int) (hits * perHit) + " HP of their hits in armor " + g.getArmorValue() + (sword ? "" : " with no real sword") + ", " + (int) g.getHealth() + " health"); }
                else said.add("the fortress at " + fort.toShortString() + " by the pile: ~" + (int) (hits * perHit) + " HP of its mobs' hits in armor " + g.getArmorValue() + ", he can take it");
            }
        }
        said.add("no sword, armor or kit: they're in what fell (" + (dn.what.length() > 80 ? dn.what.substring(0, 80) + "..." : dn.what) + ")");
        return new RunKit(first, "the run to his pile in " + dn.dim + " at " + dn.at.toShortString() + (runS >= 0 ? " (~" + runS + " s straight from " + fromName + "; the way itself is priced there)" : "") + ": " + String.join("; ", said));
    }

    /** His drops to fetch and, when he grabs and runs, where to run to (null: a plain pickup, then he carries on). */
    public record Grab(java.util.List<net.minecraft.world.entity.item.ItemEntity> fetch, BlockPos runTo, String why) {}
    /**
     * At his pile (the user 11-05: "if it's safe he can just pick it up, if he senses danger or his health is low he should grab
     * and run ... It should be weighed"): the valuables first always (DeathLog.worthTrip); the rest priced. The seconds the junk
     * would keep him there (~1.5 a stack) cost the hits he'd expect in them (the hostiles he knows of, the open night, his armor,
     * as walkS) at 10 s an HP he can win back by eating, 30 s one he can't; against the junk's worth (dropsWorth, ~1 s a point).
     * Staying dearer, or past half his health: grab the valuables and run for the nearest safety (his portal in the Nether; his
     * bed, his house's bed or his lit mine at home). The pickup itself resets their clock; a death on the way out leaves a fresh
     * pile nearer safety, so the run is worth taking more often than the fight.
     */
    public static Grab grabAndRun(ClaudeEntity g, ServerLevel level, BlockPos spot, java.util.List<net.minecraft.world.entity.item.ItemEntity> drops) {
        java.util.List<net.minecraft.world.entity.item.ItemEntity> val = new java.util.ArrayList<>(), junk = new java.util.ArrayList<>();
        for (var d : drops) (DeathLog.worthTrip(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(d.getItem().getItem()).getPath()) ? val : junk).add(d);
        java.util.List<net.minecraft.world.entity.item.ItemEntity> all = new java.util.ArrayList<>(val); all.addAll(junk);
        if (val.isEmpty() || junk.isEmpty()) return new Grab(all, null, "");
        int hostiles = 0; for (var m : Perception.known(g, net.minecraft.world.entity.monster.Monster.class, g.getBoundingBox().inflate(32).minmax(new net.minecraft.world.phys.AABB(spot).inflate(16)), x -> x.isAlive())) hostiles++;
        long tod = level.getDayTime() % 24000; boolean night = !level.dimensionType().hasFixedTime() && (level.isThundering() || tod >= (level.isRaining() ? 12969 : 13188) && tod <= 23460) && level.canSeeSky(spot.above());
        double armorF = armorF(g), tJunk = w("recovery.s_per_junk_stack", 1.5) * junk.size();
        double dmg = hitHp() * armorF * (w("danger.hits_per_s_per_hostile", 0.15) * hostiles + (night ? w("danger.hits_per_s_open_night", 0.05) : 0)) * tJunk;
        double stay = hpS(g, dmg), worth = com.claudemod.director.ClaudeLife.dropsWorth(junk) * w("recovery.s_per_worth_point", 1);
        if (stay <= worth) return new Grab(all, null, "a plain pickup: the " + junk.size() + " other stack(s) worth ~" + (int) worth + " s, the ~" + (int) tJunk + " s on them ~" + (int) stay + " s of hits (" + hostiles + " hostile(s) known" + (night ? ", the open night" : "") + ")");
        BlockPos to = null; String toName = null; double bd = Double.MAX_VALUE;
        if (level.dimension() == net.minecraft.world.level.Level.NETHER) { for (var p : PortalMap.net(g).portals) if (PortalMap.NETHER.equals(p.dim) && "lit".equals(p.state) && p.at.distSqr(spot) < bd) { bd = p.at.distSqr(spot); to = p.at; toName = "his portal at " + p.where(); } }
        else {
            for (BlockPos b : g.myStations) if (level.isLoaded(b) && level.getBlockState(b).is(net.minecraft.tags.BlockTags.BEDS) && b.distSqr(spot) < bd && com.claudemod.director.ClaudeLife.bedNoGo(g, level, b) == null) { bd = b.distSqr(spot); to = b; toName = "his bed at " + b.toShortString(); }
            if (g.house != null && g.house.shellStands(level) && g.house.bedHead().distSqr(spot) < bd) { bd = g.house.bedHead().distSqr(spot); to = g.house.bedHead(); toName = "his house"; }
            if (g.blueprint != null && !g.blueprint.isEmpty() && g.blueprint.entrance.distSqr(spot) < bd) { bd = g.blueprint.entrance.distSqr(spot); to = g.blueprint.entrance; toName = "his mine's door"; }
        }
        String why = "grab and run: the " + junk.size() + " other stack(s) worth ~" + (int) worth + " s against ~" + (int) stay + " s of hits in the ~" + (int) tJunk + " s on them (" + hostiles + " hostile(s) known" + (night ? ", the open night" : "") + ", health " + (int) g.getHealth() + ", armor " + g.getArmorValue() + "): the " + val.size() + " valuable stack(s), then " + (to == null ? "away (no safe spot of his known)" : "straight for " + toName);
        return new Grab(val, to, why);
    }

    /** A walk to goal was refused by every way his walker has (why). True if he took one of his other ways (queued); false: the caller's skip stands. */
    public static boolean walkRefused(ClaudeEntity g, ServerLevel level, BlockPos goal, double within, String why) {
        var mine = took.computeIfAbsent(g.getUUID(), k -> new java.util.HashMap<>());
        if (mine.size() > 64) mine.clear();
        double d = Math.sqrt(g.blockPosition().distSqr(goal));
        Took before = mine.get(goal);
        boolean gained = before != null && d < before.fromDist() - w("decide.headway_blocks", 4); // (the last pick for this goal got him 4+ nearer: still a good pick)
        java.util.List<String> said = new java.util.ArrayList<>();
        String best = null; double bestS = Double.MAX_VALUE;
        boolean bores = FortressScout.bores(g, level);
        if (bores) {
            int n = Math.abs(goal.getX() - g.getBlockX()) + Math.abs(goal.getZ() - g.getBlockZ()) + Math.abs(goal.getY() - g.getBlockY());
            double s = FortressScout.BORE_S * n;
            boolean out = before != null && before.option().equals("bore") && !gained;
            said.add("bore straight there ~" + (int) s + " s (" + n + " blocks of line" + (out ? "; out: the last bore here got him no nearer" : "") + ")");
            if (!out && s < bestS) { best = "bore"; bestS = s; }
        } else { boolean pick = g.inventory.count(x -> x.getItem() instanceof net.minecraft.world.item.PickaxeItem) > 0; // the reason that holds, not both (D2D review 111514-93: "no pick or under 16" with a diamond pick on him)
            int fl = FortressScout.boreBlocks(g);
            said.add("bore: out (" + (level.dimension() != net.minecraft.world.level.Level.NETHER ? "only in the Nether" : !pick ? "no pick" : fl + " blocks to floor with, under 16") + ")"); }
        int up = goal.getY() - g.getBlockY(), mb = com.claudemod.entity.nav.ActionPath.moveBlocks(g); BlockPos[] towerAt = null; // a ledge above him: a tower beside it, as a player does (review 439, 16:02 11-08: 45 logs on him, a spot 6 up skipped "0 blocks to floor with", the tower never priced)
        if (up >= 2) { BlockPos[] sg = up + 2 <= mb ? com.claudemod.entity.goals.WorkGoal.towerSpot(g, level, goal, g.blockPosition()) : null;
            if (sg == null) said.add("tower: out (" + (up + 2 > mb ? mb + " blocks to put down, " + (up + 2) + " wanted" : "no open cell beside it to tower in") + ")");
            else { int hgt = sg[0].getY() - sg[1].getY(); double s = Math.sqrt(sg[1].distSqr(g.blockPosition())) / sprint() + hgt * w("move.tower_s", 0.6) + 1; boolean out = before != null && before.option().equals("tower") && !gained;
                said.add("tower " + hgt + " up beside it from " + sg[1].toShortString() + " ~" + (int) Math.ceil(s) + " s (" + mb + " blocks to put down" + (out ? "; out: the last tower here got him no nearer" : "") + ")");
                if (!out && s < bestS) { best = "tower"; bestS = s; towerAt = sg; } } }
        if (best == null) { best = "skip"; said.add("none of his own fits: the spot skipped as before"); } // (not priced against his own ways: a think still needs a way there after it)
        ClaudeMod.LOGGER.info("[Claude decide] the walk to {} ({} off) was refused ({}): {}: {} (Decide.walkRefused)", goal.toShortString(), (int) d, why, String.join("; ", said), best);
        mine.put(goal.immutable(), new Took(best, d));
        if (best.equals("tower")) {
            g.tasks.addFirst(new Task.GoTo(goal, within));
            g.tasks.addFirst(new Task.Pillar(towerAt[0].getY() - towerAt[1].getY()));
            g.tasks.addFirst(new Task.GoTo(towerAt[1], 0.7));
            return true;
        }
        if (best.equals("bore")) {
            g.tasks.addFirst(new Task.GoTo(goal, within));
            g.tasks.addFirst(new Task.Bore(goal, goal.getY()));
            return true;
        }
        return false; // (none of his own: the skip stands; the planner gets it named on a conflict, the 3rd refusal: Watchdog.conflict)
    }
}
