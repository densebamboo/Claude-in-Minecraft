package com.claudemod.entity.life;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import com.claudemod.ClaudeMod;
import com.claudemod.entity.ClaudeEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;

/**
 * His checklist of deaths, like a player's mental note "my stuff is at the fortress": every death with its dimension, spot,
 * what fell, and whether he got it back. Kept in the world folder so it outlives a restart. A pile is settled only by looking:
 * when its chunk is loaded and nothing is left there it's collected (he was there) or gone (it wasn't him); a pile that sat
 * loaded for its full 5 minutes is gone (despawned). Items in an unloaded chunk don't age, so a far pile stays pending.
 */
public final class DeathLog {
    private DeathLog() {}

    public static final class Death {
        public final String dim; public final BlockPos at; public final long time; public final String what;
        public String status; public int loadedTicks; public boolean wasNear; public int threat; // (the hostiles he knew of round it when he died, or saw there since: what the run back walks into)
        Death(String dim, BlockPos at, long time, String what, String status, int loadedTicks) { this.dim = dim; this.at = at; this.time = time; this.what = what; this.status = status; this.loadedTicks = loadedTicks; }
        public boolean pending() { return status.equals("pending"); }
        /** What fell worth a run of its own from where he stands (worthS against tripS); a pile of planks and a wooden pick is picked up only on his way. */
        public boolean worth(ClaudeEntity g) { return worthS(what) > tripS(g, dim, at); }
        String line() { return dim + "\t" + at.getX() + "\t" + at.getY() + "\t" + at.getZ() + "\t" + time + "\t" + status + "\t" + loadedTicks + "\t" + what + "\t" + threat; }
    }

    private static Map<UUID, List<Death>> all; // (static, lazy: fields added live start null)

    private static Path file(ClaudeEntity g) { return g.getServer().getWorldPath(LevelResource.ROOT).resolve("claude").resolve("deaths-" + g.getUUID() + ".tsv"); }

    public static List<Death> of(ClaudeEntity g) {
        if (all == null) all = new HashMap<>();
        return all.computeIfAbsent(g.getUUID(), k -> {
            List<Death> l = new ArrayList<>();
            try { Path f = file(g); if (Files.exists(f)) for (String s : Files.readAllLines(f, StandardCharsets.UTF_8)) { String[] p = s.split("\t", 9); if (p.length >= 8) { Death d0 =
                new Death(p[0], new BlockPos(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3])), Long.parseLong(p[4]), p[7], p[5], Integer.parseInt(p[6])); if (p.length == 9) d0.threat = Integer.parseInt(p[8].trim()); l.add(d0); } } }
            catch (Exception e) { ClaudeMod.LOGGER.warn("[Claude deaths] couldn't read his death list: {}", e.toString()); }
            if (l.isEmpty()) for (String[] s : com.claudemod.director.ClaudeLife.deathKitsOf(g.getUUID())) { // (first run, live: the deaths he remembers already; newest first in, so oldest first out)
                var m = java.util.regex.Pattern.compile(" in (\\w+) at (-?\\d+), (-?\\d+), (-?\\d+)[^;]*; (.*)").matcher(s[1]);
                if (m.find()) l.add(0, new Death(m.group(1), new BlockPos(Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4))), Long.parseLong(s[0]), m.group(5), "pending", 0)); }
            if (!l.isEmpty()) ClaudeMod.LOGGER.info("[Claude deaths] his death checklist: {}", l.stream().map(d -> d.dim + " " + d.at.toShortString() + " " + d.status).toList());
            return l; });
    }

    private static void save(ClaudeEntity g) {
        try { Path f = file(g); Files.createDirectories(f.getParent()); List<String> out = new ArrayList<>(); for (Death d : of(g)) out.add(d.line()); Path tmp = f.resolveSibling(f.getFileName() + ".tmp"); Files.write(tmp, out, StandardCharsets.UTF_8); Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING); } // (in place, a crash mid-write lost his pending piles)
        catch (Exception e) { ClaudeMod.LOGGER.warn("[Claude deaths] couldn't save his death list: {}", e.toString()); }
    }

    /** He just died and these items fell: a new pending entry. */
    public static void died(ClaudeEntity g, ServerLevel level, BlockPos at, List<ItemEntity> fell) {
        if (fell.isEmpty()) return;
        Map<String, Integer> what = new TreeMap<>();
        for (ItemEntity i : fell) what.merge(BuiltInRegistries.ITEM.getKey(i.getItem().getItem()).getPath(), i.getItem().getCount(), Integer::sum);
        StringBuilder sb = new StringBuilder(); what.forEach((k, v) -> sb.append(sb.length() == 0 ? "" : ", ").append(k).append(v > 1 ? " " + v : ""));
        List<Death> l = of(g);
        boolean burnt = level.getFluidState(at).is(net.minecraft.tags.FluidTags.LAVA) || level.getFluidState(at.above()).is(net.minecraft.tags.FluidTags.LAVA); // items in lava burn at once (Day to Day 11-05: after his 13:28 death in a lava sea he planned a trip back for a pile that was ash)
        Death nd = new Death(level.dimension().location().getPath(), at.immutable(), level.getGameTime(), sb.toString(), burnt ? "gone (burnt: he died in lava)" : "pending", 0);
        nd.threat = threatAt(g, at); l.add(nd);
        while (l.size() > 20) l.remove(0); // ponytail: the last 20 deaths
        save(g);
        ClaudeMod.LOGGER.info("[Claude deaths] died in {} at {}: {} on his list as pending ({})", level.dimension().location().getPath(), at.toShortString(), fell.size() + " stack(s)", sb);
    }

    /** Every 5 s: settle what can be seen. Pending piles in this dimension whose chunk is loaded are checked for what's left. */
    public static void tick(ClaudeEntity g, ServerLevel level) {
        String dim = level.dimension().location().getPath(); boolean changed = false;
        for (Death d : of(g)) {
            if (!d.pending() || !d.dim.equals(dim) || !level.isPositionEntityTicking(d.at)) continue;
            d.loadedTicks += 100;
            boolean near = g.blockPosition().distSqr(d.at) <= 10 * 10;
            int left = level.getEntitiesOfClass(ItemEntity.class, new AABB(d.at).inflate(8), i -> i.isAlive() && !g.isGift(i)).size();
            if (left == 0) d.status = near || d.wasNear ? "collected" : d.loadedTicks >= 6000 ? "gone (despawned)" : "gone (nothing left there)";
            else if (d.loadedTicks >= 6000 + 200) d.status = "gone (despawned)";
            d.wasNear = near;
            if (g.blockPosition().distSqr(d.at) <= 24 * 24) { int th = threatAt(g, d.at); if (th != d.threat) { d.threat = th; changed = true; } } // (seen there now: what's there now, not what was)
            if (!d.pending()) { changed = true; ClaudeMod.LOGGER.info("[Claude deaths] his pile at {} in {}: {} ({} s loaded)", d.at.toShortString(), dim, d.status, d.loadedTicks / 20); }
        }
        if (changed) save(g);
    }

    /** The stages' reset. */
    public static void reset(ClaudeEntity g) { of(g).clear(); save(g); }

    /** He gives a pile up on purpose (they said so, or it isn't worth the risk). */
    public static void abandon(ClaudeEntity g, Death d, String why) { d.status = "abandoned: " + why; save(g); ClaudeMod.LOGGER.info("[Claude deaths] his pile at {} in {}: {}", d.at.toShortString(), d.dim, d.status); }

    /** The valuables he grabs first at his pile (Decide.grabAndRun): iron or better, or the gear the dragon path needs. The run itself is priced (worthS, tripS). */
    public static boolean worthTrip(String id) {
        return id.startsWith("iron_") || id.equals("raw_iron") || id.contains("diamond") || id.contains("netherite") || id.equals("emerald") || id.equals("shield") || id.equals("bow") || id.endsWith("_bed") || id.equals("ender_pearl") || id.equals("blaze_rod");
    }

    /** What fell ("id n, id n"), as the seconds to get it again: ClaudeLife.dropsWorth's points at recovery.trip_s_per_point. */
    public static double worthS(String what) {
        double pts = 0;
        for (String part : what.split(", ")) { String[] p = part.split(" "); if (p[0].isBlank()) continue;
            var item = BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.withDefaultNamespace(p[0]));
            int n = 1; try { if (p.length > 1) n = Integer.parseInt(p[1]); } catch (NumberFormatException e) { }
            pts += com.claudemod.director.ClaudeLife.worthEach(new net.minecraft.world.item.ItemStack(item)) * n; }
        return pts * Weights.w("recovery.trip_s_per_point", 3);
    }
    /** The run to a pile at `at` and back, priced (Decide.walkS: the walk and the hits on it); in another dimension a flat recovery.other_dim_trip_s. */
    /** The hostiles he knows of within 16 of a pile. */
    static int threatAt(ClaudeEntity g, BlockPos at) { return Perception.known(g, net.minecraft.world.entity.monster.Monster.class, new AABB(at).inflate(16), m -> m.isAlive()).size(); }
    /** The time on the pile picking it up with the hostiles he knows were round it (what killed him there, until he's seen it since), as his time: through his armor and health now, so better gear makes the run worth it again. */
    static double pileS(ClaudeEntity g, String dim, BlockPos at) {
        int th = 0; for (Death d : of(g)) if (d.at.equals(at) && d.dim.equals(dim)) th = d.threat; if (th == 0) return 0;
        ServerLevel level = (ServerLevel) g.level(); long tod = level.getDayTime() % 24000; double on = Weights.w("recovery.on_pile_s", 15);
        return Decide.hpS(g, Decide.danger(g, level, tod, on, th) - Decide.danger(g, level, tod, on, 0));
    }
    public static double tripS(ClaudeEntity g, String dim, BlockPos at) {
        if (!dim.equals(g.level().dimension().location().getPath())) return Weights.w("recovery.other_dim_trip_s", 180) + pileS(g, dim, at); // shortcut: a flat price for the portal and the far side's walk; take PortalMap's route price once it has one
        ServerLevel level = (ServerLevel) g.level();
        double s = 2 * Math.sqrt(g.blockPosition().distSqr(at)) / Decide.sprint(); // shortcut: straight there and back, not the walker's route
        long tod = level.getDayTime() % 24000; int n = Decide.hostilesAlong(g, g.blockPosition(), at); double pass = Math.min(s, Weights.w("recovery.hostile_pass_s", 10)); // (a mob he knows of is passed or shaken off, not at his heels the whole 300 blocks: one zombie by him priced a run for his iron at certain death)
        return s + Decide.hpS(g, Decide.danger(g, level, tod, s, 0) + Decide.danger(g, level, tod, pass, n) - Decide.danger(g, level, tod, pass, 0)) + pileS(g, dim, at);
    }

    /** The pending pile to go for: the one worth most over its run in this dimension, else the newest worth its run elsewhere; null when none. A pile with nothing worth it (worthTrip, by what fell) is never the run's target, here or elsewhere: it's picked up only if it lies on his way (Day to Day 11-05: a wooden pick and planks in the Nether, newest, chosen over the iron set). */
    public static Death next(ClaudeEntity g, String dim) {
        Death best = null; double most = 0;
        for (Death d : of(g)) if (d.pending() && d.dim.equals(dim)) { double net = worthS(d.what) - tripS(g, d.dim, d.at); if (net > most) { most = net; best = d; } }
        if (best != null) return best;
        for (Death d : of(g)) if (d.pending() && !d.dim.equals(dim) && d.worth(g)) best = d; // (newest: last on the list)
        return best;
    }

    /** The checklist for his prompts (planner and chat). */
    public static String describe(ClaudeEntity g, ServerLevel level) {
        List<Death> l = of(g); if (l.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("  YOUR DEATHS CHECKLIST (exact; pending = your things still lie there; items in an unloaded chunk don't age; answer where your stuff is from this list only):\n");
        int from = Math.max(0, l.size() - 6);
        for (int i = l.size() - 1; i >= from; i--) { Death d = l.get(i);
            sb.append("   - ").append(d.dim).append(" at ").append(d.at.toShortString()).append(d.time > 0 ? ", " + (level.getGameTime() - d.time) / 1200 + " min ago" : "")
              .append(": ").append(d.status.toUpperCase().startsWith("PENDING") ? (d.worth(g) ? "PENDING" : "PENDING, not worth a run from here (~" + (int) worthS(d.what) + " s to get again, the run ~" + (int) tripS(g, d.dim, d.at) + " s): picked up only on your way") : d.status).append(d.dim.equals(level.dimension().location().getPath()) ? "" : " (other dimension: through your portal)")
              .append("; fell: ").append(d.what.length() > 160 ? d.what.substring(0, 160) + "..." : d.what).append("\n"); }
        return sb.toString();
    }
}
