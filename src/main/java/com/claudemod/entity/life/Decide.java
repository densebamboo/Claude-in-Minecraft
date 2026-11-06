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
    public static double holeS(long tod) { return 10 + Math.max(0, 23460 - Math.max(tod, 12542)) / 20.0; }
    /**
     * A walk of walkS seconds through the open from now (tick tod), priced in seconds of his time: the walk plus the hits he can
     * expect on it (each known hostile ~0.15 a second while he passes; one every 20 s once the game's first spawns can come: light 0
     * in the open, tick 13188, 12969 in rain, now in thunder; his armor takes its share), 3 HP a hit (Normal), 10 s an HP to win back.
     * MAX_VALUE when what he'd expect to take is over half his health or leaves him under 2 hearts: not a walk to make, whatever it saves.
     * shortcut: rates are a player's rough odds, not a mob-aware route; take Big Picture's ActionPath/NetherRoute hit estimate once it lands.
     */
    public static double walkS(ClaudeEntity g, ServerLevel level, long tod, double walkS, int hostiles) {
        long dark = level.isThundering() ? tod : level.isRaining() ? 12969 : 13188;
        double toDark = Math.max(0, dark - tod) / 20.0, armorF = 1.0 - Math.min(20, g.getArmorValue()) / 25.0;
        double dmg = 3 * armorF * (0.15 * hostiles * walkS + Math.max(0, walkS - toDark) / 20.0);
        return dmg > Math.min(g.getHealth() - 4, g.getHealth() / 2) ? Double.MAX_VALUE : walkS + dmg * 10; // (no walk that may take half his health or leave him under 2 hearts: a hard limit at 10 HP kept a 3-heart player from an 11 s walk with nothing about)
    }
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
            BlockPos at = (BlockPos) sp[1]; int dist = com.claudemod.director.ClaudeLife.routeDist(g, me, at);
            if (dist > 400) continue; // (out of any night's reach: not an option)
            double walk = dist / 5.6, out = Math.max(0, walk - toDark), s = walkS(g, level, tod, walk, hostiles);
            said.add(sp[0] + " " + dist + " off " + (s == Double.MAX_VALUE ? "out (the hits on the way would take over half his health)" : "~" + (int) s + " s") + " (" + (int) walk + " s walk" + (out > 0 ? ", " + (int) out + " s of it after the spawns" : ", there before the spawns") + ")");
            if (s < bestS) { bestS = s; best = (String) sp[0]; to = at; }
        }
        said.add("a hole here ~" + (int) holeS(tod) + " s (the night in it)");
        String key = best;
        if (to != null) { final BlockPos to0 = to; if (me.distSqr(to0) > 3 * 3 && g.tasks.stream().noneMatch(t -> t instanceof Task.GoTo go && go.pos().distSqr(to0) <= 4) && !(g.currentTask instanceof Task.GoTo cg && cg.pos().distSqr(to0) <= 4)) g.tasks.addFirst(new Task.GoTo(to0, 2.0)); else key = null; }
        else if (best.equals("hole") && g.skillQueue.stream().noneMatch(s0 -> s0.skill().equals("shelter"))) g.skillQueue.addFirst(new Skills.Step("shelter", "", 1)); else if (best.equals("hole")) key = null;
        if (key != null) ClaudeMod.LOGGER.info("[Claude decide] dusk (tick {}, the first spawns in {} s, {} hostile(s) known, armor {}): {}: {} (Decide.dusk)", tod, (int) toDark, hostiles, g.getArmorValue(), String.join("; ", said), best);
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
        double armorF = 1.0 - Math.min(20, g.getArmorValue()) / 25.0, tJunk = 1.5 * junk.size();
        double dmg = 3 * armorF * (0.15 * hostiles + (night ? 1 / 20.0 : 0)) * tJunk, perHP = g.getFoodData().getFoodLevel() >= 18 ? 10 : 30;
        boolean tooMuch = dmg > Math.min(g.getHealth() - 4, g.getHealth() / 2);
        double stay = dmg * perHP, worth = com.claudemod.director.ClaudeLife.dropsWorth(junk);
        if (!tooMuch && stay <= worth) return new Grab(all, null, "a plain pickup: the " + junk.size() + " other stack(s) worth ~" + (int) worth + " s, the ~" + (int) tJunk + " s on them ~" + (int) stay + " s of hits (" + hostiles + " hostile(s) known" + (night ? ", the open night" : "") + ")");
        BlockPos to = null; String toName = null; double bd = Double.MAX_VALUE;
        if (level.dimension() == net.minecraft.world.level.Level.NETHER) { for (var p : PortalMap.net(g).portals) if (PortalMap.NETHER.equals(p.dim) && "lit".equals(p.state) && p.at.distSqr(spot) < bd) { bd = p.at.distSqr(spot); to = p.at; toName = "his portal at " + p.where(); } }
        else {
            for (BlockPos b : g.myStations) if (level.isLoaded(b) && level.getBlockState(b).is(net.minecraft.tags.BlockTags.BEDS) && b.distSqr(spot) < bd) { bd = b.distSqr(spot); to = b; toName = "his bed at " + b.toShortString(); }
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
        boolean gained = before != null && d < before.fromDist() - 4; // (the last pick for this goal got him 4+ nearer: still a good pick)
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
