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
    /** Seconds a block at a sprint, the walk prices' speed. */
    public static double sprint() { return w("move.sprint_blocks_per_s", 5.6); }
    public static double holeS(long tod) { return w("night.hole_dig_s", 10) + Math.max(0, 23460 - Math.max(tod, 12542)) / 20.0; }
    /**
     * A walk of walkS seconds through the open from now (tick tod), priced in seconds of his time: the walk plus the hits he can
     * expect on it (each known hostile ~0.15 a second while he passes; one every 20 s once the game's first spawns can come: light 0
     * in the open, tick 13188, 12969 in rain, now in thunder; his armor takes its share), 3 HP a hit (Normal), 10 s an HP to win back.
     * MAX_VALUE when what he'd expect to take is over half his health or leaves him under 2 hearts: not a walk to make, whatever it saves.
     * shortcut: rates are a player's rough odds, not a mob-aware route; take Big Picture's ActionPath/NetherRoute hit estimate once it lands.
     */
    public static double walkS(ClaudeEntity g, ServerLevel level, long tod, double walkS, int hostiles) {
        long dark = level.isThundering() ? tod : level.isRaining() ? 12969 : 13188;
        double toDark = Math.max(0, dark - tod) / 20.0, armorF = armorF(g);
        double dmg = hitHp() * armorF * (w("danger.hits_per_s_per_hostile", 0.15) * hostiles * walkS + Math.max(0, walkS - toDark) * w("danger.hits_per_s_open_night", 0.05));
        return tooMuch(g, dmg) ? Double.MAX_VALUE : walkS + dmg * w("danger.s_per_hp", 10); // (no walk that may take half his health or leave him under 2 hearts: a hard limit at 10 HP kept a 3-heart player from an 11 s walk with nothing about)
    }
    /** The share of a hit his armor lets through (armor points over danger.armor_full: vanilla's own curve, roughly). */
    public static double armorF(ClaudeEntity g) { return 1.0 - Math.min(20, g.getArmorValue()) / w("danger.armor_full", 25); }
    public static double hitHp() { return w("danger.hit_hp", 3); }
    /** Damage he won't take for anything a walk saves: over danger.max_hp_share of his health, or leaving him under danger.min_hp_left. */
    public static boolean tooMuch(ClaudeEntity g, double dmg) { return dmg > Math.min(g.getHealth() - w("danger.min_hp_left", 4), g.getHealth() * w("danger.max_hp_share", 0.5)); }
    public static int hostilesKnown(ClaudeEntity g) { int n = 0; for (var m : Perception.known(g, net.minecraft.world.entity.monster.Monster.class, g.getBoundingBox().inflate(32), x -> x.isAlive())) n++; return n; }

    public static void dusk(ClaudeEntity g, ServerLevel level, long tod) {
        BlockPos me = g.blockPosition();
        long dark = level.isThundering() ? tod : level.isRaining() ? 12969 : 13188;
        double toDark = Math.max(0, dark - tod) / 20.0;
        int hostiles = hostilesKnown(g);
        java.util.List<String> said = new java.util.ArrayList<>(); String best = "hole"; BlockPos to = null; double bestS = holeS(tod);
        if (com.claudemod.director.ClaudeLife.nightGearNo(g, level, me) == null) { best = "carry on out here"; bestS = 0; said.add("carry on out here ~0 s (the night's no danger to him as he is)"); }
        java.util.List<Object[]> spots = new java.util.ArrayList<>();
        for (BlockPos b : g.myStations) if (level.isLoaded(b) && level.getBlockState(b).is(net.minecraft.tags.BlockTags.BEDS)) spots.add(new Object[] {"his bed", b});
        if (g.house != null && g.house.shellStands(level) && level.getBlockState(g.house.bedHead()).is(net.minecraft.tags.BlockTags.BEDS)) spots.add(new Object[] {"his house's bed", g.house.bedHead()});
        if (g.blueprint != null && !g.blueprint.isEmpty()) spots.add(new Object[] {"his lit mine", g.blueprint.entrance});
        for (Object[] sp : spots) {
            BlockPos at = (BlockPos) sp[1];
            String no = ((String) sp[0]).contains("bed") ? com.claudemod.director.ClaudeLife.bedNoGo(g, level, at) : null; // (the sleep skill's own judgement of the bed: one it won't sleep in is no night's end; World 17 21:12, incident 17: an obstructed bed picked every 20 s, four 87-block legs)
            if (no != null) { said.add(sp[0] + " at " + at.toShortString() + " out (" + no + ")"); continue; }
            int dist = com.claudemod.director.ClaudeLife.routeDist(g, me, at);
            if (dist > w("night.spot_max_route", 400)) continue; // (out of any night's reach: not an option)
            double walk = dist / sprint(), out = Math.max(0, walk - toDark), s = walkS(g, level, tod, walk, hostiles);
            said.add(sp[0] + " " + dist + " off " + (s == Double.MAX_VALUE ? "out (the hits on the way would take over half his health)" : "~" + (int) s + " s") + " (" + (int) walk + " s walk" + (out > 0 ? ", " + (int) out + " s of it after the spawns" : ", there before the spawns") + ")");
            if (s < bestS) { bestS = s; best = (String) sp[0]; to = at; }
        }
        said.add("a hole here ~" + (int) holeS(tod) + " s (the night in it)");
        String key = best;
        if (to != null) { final BlockPos to0 = to; if (me.distSqr(to0) > 3 * 3 && g.tasks.stream().noneMatch(t -> t instanceof Task.GoTo go && go.pos().distSqr(to0) <= 4) && !(g.currentTask instanceof Task.GoTo cg && cg.pos().distSqr(to0) <= 4)) g.tasks.addFirst(new Task.GoTo(to0, 2.0)); else key = null; }
        else if (best.equals("hole") && g.skillQueue.stream().noneMatch(s0 -> s0.skill().equals("shelter"))) g.skillQueue.addFirst(new Skills.Step("shelter", "", 1)); else if (best.equals("hole")) key = null;
        if (key != null) ClaudeMod.LOGGER.info("[Claude decide] dusk (tick {}, the first spawns in {} s, {} hostile(s) known, armor {}): {}: {} (Decide.dusk)", tod, (int) toDark, hostiles, g.getArmorValue(), String.join("; ", said), best);
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
                if (dmg > g.getHealth() * w("danger.max_hp_share", 0.5) && goldHere >= 4) { first.add(new Skills.Step("craft", "golden_boots", 1)); said.add("golden boots first (" + goldHere + " gold to hand: a craft): no gold piece, ~" + (int) dmg + " HP of piglin hits on the ~" + (int) there + " s there and back is over half his " + (int) g.getHealth() + " (armor " + g.getArmorValue() + ")"); }
                else said.add("no gold piece" + (goldHere >= 4 ? "" : " and none to hand") + ": ~" + (int) dmg + " HP of piglin hits on the ~" + (int) there + " s if he fights; straight there, no fights till he's in what fell (the user 11-05: \"avoiding combat altogether until you get suited with your pickups\")");
            } else said.add("a gold piece on him: the piglins let him be");
        }
        said.add("no sword, armor or kit: they're in what fell (" + (dn.what.length() > 80 ? dn.what.substring(0, 80) + "..." : dn.what) + ")");
        return new RunKit(first, "the run to his pile in " + dn.dim + " at " + dn.at.toShortString() + (runS >= 0 ? " (~" + runS + " s from " + fromName + ")" : "") + ": " + String.join("; ", said));
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
        double dmg = hitHp() * armorF * (w("danger.hits_per_s_per_hostile", 0.15) * hostiles + (night ? w("danger.hits_per_s_open_night", 0.05) : 0)) * tJunk, perHP = g.getFoodData().getFoodLevel() >= 18 ? w("danger.s_per_hp", 10) : w("danger.s_per_hp_no_regen", 30); // (food 18: vanilla's regen line)
        boolean tooMuch = tooMuch(g, dmg);
        double stay = dmg * perHP, worth = com.claudemod.director.ClaudeLife.dropsWorth(junk) * w("recovery.s_per_worth_point", 1);
        if (!tooMuch && stay <= worth) return new Grab(all, null, "a plain pickup: the " + junk.size() + " other stack(s) worth ~" + (int) worth + " s, the ~" + (int) tJunk + " s on them ~" + (int) stay + " s of hits (" + hostiles + " hostile(s) known" + (night ? ", the open night" : "") + ")");
        BlockPos to = null; String toName = null; double bd = Double.MAX_VALUE;
        if (level.dimension() == net.minecraft.world.level.Level.NETHER) { for (var p : PortalMap.net(g).portals) if (PortalMap.NETHER.equals(p.dim) && "lit".equals(p.state) && p.at.distSqr(spot) < bd) { bd = p.at.distSqr(spot); to = p.at; toName = "his portal at " + p.where(); } }
        else {
            for (BlockPos b : g.myStations) if (level.isLoaded(b) && level.getBlockState(b).is(net.minecraft.tags.BlockTags.BEDS) && b.distSqr(spot) < bd && com.claudemod.director.ClaudeLife.bedNoGo(g, level, b) == null) { bd = b.distSqr(spot); to = b; toName = "his bed at " + b.toShortString(); }
            if (g.house != null && g.house.shellStands(level) && g.house.bedHead().distSqr(spot) < bd) { bd = g.house.bedHead().distSqr(spot); to = g.house.bedHead(); toName = "his house"; }
            if (g.blueprint != null && !g.blueprint.isEmpty() && g.blueprint.entrance.distSqr(spot) < bd) { bd = g.blueprint.entrance.distSqr(spot); to = g.blueprint.entrance; toName = "his mine's door"; }
        }
        String why = "grab and run: the " + junk.size() + " other stack(s) worth ~" + (int) worth + " s against " + (tooMuch ? "hits past half his health" : "~" + (int) stay + " s of hits") + " in the ~" + (int) tJunk + " s on them (" + hostiles + " hostile(s) known" + (night ? ", the open night" : "") + ", health " + (int) g.getHealth() + ", armor " + g.getArmorValue() + "): the " + val.size() + " valuable stack(s), then " + (to == null ? "away (no safe spot of his known)" : "straight for " + toName);
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
        } else said.add("bore: out (" + (level.dimension() == net.minecraft.world.level.Level.NETHER ? "no pick or under 16 blocks to floor with" : "only in the Nether") + ")");
        if (best == null) { best = "skip"; said.add("none of his own fits: the spot skipped as before"); } // (not priced against his own ways: a think still needs a way there after it)
        ClaudeMod.LOGGER.info("[Claude decide] the walk to {} ({} off) was refused ({}): {}: {} (Decide.walkRefused)", goal.toShortString(), (int) d, why, String.join("; ", said), best);
        mine.put(goal.immutable(), new Took(best, d));
        if (best.equals("bore")) {
            g.tasks.addFirst(new Task.GoTo(goal, within));
            g.tasks.addFirst(new Task.Bore(goal, goal.getY()));
            return true;
        }
        return false; // (none of his own: the skip stands; the planner gets it named on a conflict, the 3rd refusal: Watchdog.conflict)
    }
}
