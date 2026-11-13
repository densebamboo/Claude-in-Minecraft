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
        public String status; public int loadedTicks; public boolean wasNear; public int threat, creepers; // (the hostiles he knew of round it when he died, or saw there since: what the run back walks into; the creepers among them)
        Death(String dim, BlockPos at, long time, String what, String status, int loadedTicks) { this.dim = dim; this.at = at; this.time = time; this.what = what; this.status = status; this.loadedTicks = loadedTicks; }
        public boolean pending() { return status.equals("pending"); }
        /** What fell worth a run of its own from where he stands (worthS against tripS); a pile of planks and a wooden pick is picked up only on his way. */
        public boolean worth(ClaudeEntity g) { return worthS(what) > tripS(g, dim, at); }
        String line() { return dim + "\t" + at.getX() + "\t" + at.getY() + "\t" + at.getZ() + "\t" + time + "\t" + status + "\t" + loadedTicks + "\t" + what + "\t" + threat + "\t" + creepers; }
    }

    private static Map<UUID, List<Death>> all; // (static, lazy: fields added live start null)

    private static Path file(ClaudeEntity g) { return g.getServer().getWorldPath(LevelResource.ROOT).resolve("claude").resolve("deaths-" + g.getUUID() + ".tsv"); }

    public static List<Death> of(ClaudeEntity g) {
        if (all == null) all = new HashMap<>();
        return all.computeIfAbsent(g.getUUID(), k -> {
            List<Death> l = new ArrayList<>();
            try { Path f = file(g); if (Files.exists(f)) for (String s : Files.readAllLines(f, StandardCharsets.UTF_8)) { String[] p = s.split("\t", 10); if (p.length >= 8) { Death d0 =
                new Death(p[0], new BlockPos(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3])), Long.parseLong(p[4]), p[7], p[5], Integer.parseInt(p[6])); if (p.length >= 9) d0.threat = Integer.parseInt(p[8].trim()); if (p.length >= 10) d0.creepers = Integer.parseInt(p[9].trim()); l.add(d0); } } }
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
        int[] th = threatsAt(g, at, true); nd.threat = th[0]; nd.creepers = th[1]; l.add(nd);
        while (l.size() > 20) l.remove(0); // ponytail: the last 20 deaths
        save(g);
        ClaudeMod.LOGGER.info("[Claude deaths] died in {} at {}: {} on his list as pending ({})", level.dimension().location().getPath(), at.toShortString(), fell.size() + " stack(s)", sb);
    }

    /** Every 5 s: settle what can be seen. Pending piles in this dimension whose chunk is loaded are checked for what's left. */
    private static int backForTick = -100000;
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
            if (d.pending() && left > 0 && Math.sqrt(g.blockPosition().distSqr(d.at)) / 4.0 < (6000 - d.loadedTicks) / 20.0 && g.tickCount - backForTick > 20 * 30 && !g.skillQueue.stream().anyMatch(s -> s.skill().equals("recover_drops")) && !(g.lastStep != null && g.lastStep.skill().equals("recover_drops")) && next(g, dim) == d && !(g.currentTask instanceof Task.Attack || g.currentTask instanceof Task.Flee || g.currentTask instanceof Task.Eat || g.tasks.stream().anyMatch(t -> t instanceof Task.Flee) || g.holdingCover() || g.buildingCover())) { // (deaths 25 and 26, live 21:05 and 22:05 11-10: an iron kit despawned 9 and 40 off him while he strip-mined round it; only the idle pickup read the clock)
                backForTick = g.tickCount; g.tasks.clear(); com.claudemod.director.ClaudeLife.dropCurrent(g); g.skillQueue.addFirst(new Skills.Step("recover_drops", "", 1)); // (the work round it waits: a strip-mine's 168 queued tasks ran past the clock live)
                ClaudeMod.LOGGER.info("[Claude deaths] his pile at {} is loaded, its clock running ({} s counted of 300), he's {} off: back for it now, before {} (DeathLog.tick)", d.at.toShortString(), d.loadedTicks / 20, (int) Math.sqrt(g.blockPosition().distSqr(d.at)), g.lastStep == null ? "anything else" : g.lastStep.skill()); }
            if (g.blockPosition().distSqr(d.at) <= 24 * 24) { int[] th = threatsAt(g, d.at, false); if (th[0] != d.threat || th[1] != d.creepers) { d.threat = th[0]; d.creepers = th[1]; changed = true; } } // (seen there now: what's there now, not what was)
            if (!d.pending()) { changed = true; ClaudeMod.LOGGER.info("[Claude deaths] his pile at {} in {}: {} ({} s loaded)", d.at.toShortString(), dim, d.status, d.loadedTicks / 20); }
        }
        if (changed) save(g);
    }

    /** Logging only (11-10: his 7 rods' pile was "gone (nothing left there)" at 290 s counted, cause unproven): why an item off a pending pile left the world. */
    public static void onLeave(net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent e) {
        if (all == null || !(e.getEntity() instanceof ItemEntity it) || !(e.getLevel() instanceof ServerLevel level)) return;
        var r = it.getRemovalReason();
        if (r == null || r == net.minecraft.world.entity.Entity.RemovalReason.UNLOADED_TO_CHUNK || r == net.minecraft.world.entity.Entity.RemovalReason.UNLOADED_WITH_PLAYER || r == net.minecraft.world.entity.Entity.RemovalReason.CHANGED_DIMENSION) return;
        String dim = level.dimension().location().getPath(); BlockPos p = it.blockPosition();
        for (var en : all.entrySet()) for (Death d : en.getValue()) if (d.pending() && d.dim.equals(dim) && d.at.distSqr(p) <= 8 * 8) {
            String why = it.getItem().isEmpty() ? "picked up (or merged)" : r == net.minecraft.world.entity.Entity.RemovalReason.KILLED ? "destroyed (fire, lava, cactus, explosion)" : it.getAge() >= it.lifespan ? "despawned" : "discarded (" + r + ")"; /* (lifespan: NeoForge's, 6000 unless a mod set it) */
            var pl = level.getNearestPlayer(it, -1);
            ClaudeMod.LOGGER.info("[Claude deaths] an item off his pile at {} left the world: {} ({}), age {} ticks ({} s counted on the pile), at {} in {} on {}, nearest player {}", d.at.toShortString(), BuiltInRegistries.ITEM.getKey(it.getItem().getItem()).getPath(), why, it.getAge(), d.loadedTicks / 20, p.toShortString(), level.getBlockState(p).getBlock().getName().getString() + (level.getFluidState(p).isEmpty() ? "" : " / " + level.getFluidState(p).getType()), level.getBlockState(p.below()).getBlock().getName().getString(), pl == null ? "none" : pl.getName().getString() + " " + (int) pl.distanceTo(it) + " off");
            boolean lava = level.getFluidState(p).is(net.minecraft.tags.FluidTags.LAVA), fire = level.getBlockState(p).is(net.minecraft.tags.BlockTags.FIRE);
            if (!it.getItem().isEmpty() && !why.startsWith("picked")) { // a job item lost off his pile: an incident of its own (D2D pass 143457, live 14:21:24 11-12: 2 blaze rods despawned 11 off him on the rods job, logged here only, no review named them; stage 1673)
                String id = BuiltInRegistries.ITEM.getKey(it.getItem().getItem()).getPath();
                for (ServerLevel lv : level.getServer().getAllLevels()) if (lv.getEntity(en.getKey()) instanceof ClaudeEntity g && jobItem(g, id)) {
                    Watchdog.incident(g, "JOB_ITEM_LOST", it.getItem().getCount() + " " + id + " off his pile at " + d.at.toShortString() + " " + why + " (" + d.loadedTicks / 20 + " s counted on the pile), him " + (g.level() == level ? (int) Math.sqrt(g.blockPosition().distSqr(p)) + " off" : "in " + g.level().dimension().location().getPath()) + ", step " + (g.lastStep == null ? "-" : g.lastStep.skill() + " " + g.lastStep.target()));
                    break; }
            }
            if (!it.getItem().isEmpty() && (lava || fire || r == net.minecraft.world.entity.Entity.RemovalReason.KILLED)) { // burned: once nothing worth a trip is left of the pile, it's gone, not waiting (live 01:57:13 11-12, review 015814-24: his diamond tools and iron set burned in the lava by his death spot, recover_drops still listed them "picked up if I pass them"; stage 1590)
                boolean left = false; for (ItemEntity o : level.getEntitiesOfClass(ItemEntity.class, new AABB(d.at).inflate(8), x -> x != it && x.isAlive())) if (worthTrip(BuiltInRegistries.ITEM.getKey(o.getItem().getItem()).getPath())) left = true;
                if (!left) { d.status = "gone: burned in the " + (lava ? "lava" : "fire") + " at " + p.toShortString(); ClaudeMod.LOGGER.info("[Claude deaths] his pile at {} in {}: {}", d.at.toShortString(), d.dim, d.status);
                    for (ServerLevel lv : level.getServer().getAllLevels()) if (lv.getEntity(en.getKey()) instanceof ClaudeEntity g) save(g); }
            }
            return;
        }
    }

    /** The stages' reset. */
    public static void reset(ClaudeEntity g) { of(g).clear(); save(g); }

    /** He gives a pile up on purpose (they said so, or it isn't worth the risk). */
    /** An item his job needs: a dragon-path item (rods, powder, pearls, eyes), or what his step now or next is after. */
    static boolean jobItem(ClaudeEntity g, String id) {
        if (id.equals("blaze_rod") || id.equals("blaze_powder") || id.equals("ender_pearl") || id.equals("ender_eye")) return true;
        java.util.List<Skills.Step> steps = new java.util.ArrayList<>(); if (g.lastStep != null) steps.add(g.lastStep); var q = g.skillQueue.peekFirst(); if (q != null) steps.add(q);
        for (Skills.Step st : steps) { String tg = st.target() == null ? "" : st.target().toLowerCase(); int at = tg.indexOf('@'); if (at >= 0) tg = tg.substring(0, at); if (!tg.isEmpty() && (tg.equals(id) || tg.replace(' ', '_').equals(id))) return true; }
        return false;
    }
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
    static int threatAt(ClaudeEntity g, BlockPos at) { return threatsAt(g, at, false)[0]; }
    /** {hostiles, creepers among them} he knows of within 16 of a pile; at his death the one that killed him too, seen or not (live 04:12 11-10: shot dead in a cave by a skeleton he never saw, the pile priced as if nothing was there, and he ran straight back into it unarmored). */
    static int[] threatsAt(ClaudeEntity g, BlockPos at, boolean dying) {
        var ms = new java.util.ArrayList<>(Perception.known(g, net.minecraft.world.entity.monster.Monster.class, new AABB(at).inflate(16), m -> m.isAlive()));
        if (dying && g.getLastHurtByMob() instanceof net.minecraft.world.entity.monster.Monster k && k.isAlive() && k.blockPosition().distSqr(at) <= 24 * 24 && !ms.contains(k)) ms.add(k);
        int c = 0; for (var m : ms) if (m instanceof net.minecraft.world.entity.monster.Creeper) c++;
        return new int[] {ms.size(), c};
    }
    /** What he knows waits at the pile, said for the trip's price line. */
    public static String threatNote(ClaudeEntity g, String dim, BlockPos at) {
        for (Death d : of(g)) if (d.at.equals(at) && d.dim.equals(dim)) return d.threat == 0 ? "nothing known there" : d.threat + " hostile(s) known there" + (d.creepers > 0 ? ", " + d.creepers + " creeper(s)" : "");
        return "no death of his there";
    }
    /** The time on the pile picking it up with the hostiles he knows were round it (what killed him there, until he's seen it since), as his time: through his armor and health now, so better gear makes the run worth it again. */
    static double pileS(ClaudeEntity g, String dim, BlockPos at) {
        int th = 0, cr = 0; for (Death d : of(g)) if (d.at.equals(at) && d.dim.equals(dim)) { th = d.threat; cr = Math.min(d.creepers, d.threat); } if (th == 0) return 0;
        ServerLevel level = (ServerLevel) g.level(); long tod = runTod(level, at); double on = Weights.w("recovery.on_pile_s", 15);
        // a creeper is its blast, not a 3 HP hit: the chance one gets to him in his time on the pile (D2D 04:12 11-10: 3-4 creepers at the pile priced as hitters, armor 0, a run back into them)
        double lamC = cr * (1 - Math.exp(-Weights.w("recovery.creeper_blast_per_s", 0.05) * on));
        return Decide.hpS(g, Decide.danger(g, level, tod, on, th - cr) - Decide.danger(g, level, tod, on, 0)) + Decide.blastS(g, lamC);
    }
    /** The time of day his run to a pile is priced at: now, while its chunk is loaded (its 5-min clock runs); by day when it's frozen unloaded in the overworld, since waiting for morning costs nothing (death 25, live 20:39 11-10: a pile with no threat priced ~2299 s at dusk against ~264 s of iron, ~139 s by day: never the run's target, then lost; stage 1398). */
    static long runTod(ServerLevel level, BlockPos at) {
        long tod = level.getDayTime() % 24000;
        return level.dimension() == net.minecraft.world.level.Level.OVERWORLD && !level.isPositionEntityTicking(at) && (tod < 1000 || tod > 12000) ? 6000 : tod;
    }
    public static double tripS(ClaudeEntity g, String dim, BlockPos at) {
        if (!dim.equals(g.level().dimension().location().getPath())) { // through a portal of his: the walk to it, the crossing, the far side's way as it's priced there (Nether: his highways, the rest as Nether ground), and back the same (Loops' sink 3, D2D 11-09: the same price on both sides of the portal; stage 1315)
            double best = Double.MAX_VALUE, cross = Weights.w("recovery.portal_cross_s", 4);
            for (var p : PortalMap.net(g).portals) { if (!p.dim.equals(g.level().dimension().location().getPath()) || !"lit".equals(p.state)) continue; var q = PortalMap.leadsTo(g, p); if (q == null || !q.dim.equals(dim)) continue;
                double here = Math.sqrt(g.blockPosition().distSqr(p.at)) / Decide.sprint(), far = PortalMap.NETHER.equals(dim) ? com.claudemod.entity.nav.NetherRoute.priceS(g, q.at, at) + Math.sqrt(q.at.distSqr(at)) / Decide.sprint() : 2 * Math.sqrt(q.at.distSqr(at)) / Decide.sprint();
                if (PortalMap.NETHER.equals(p.dim)) { here = com.claudemod.entity.nav.NetherRoute.priceS(g, g.blockPosition(), p.at) + Math.sqrt(g.blockPosition().distSqr(p.at)) / Decide.sprint(); far = 2 * Math.sqrt(q.at.distSqr(at)) / Decide.sprint(); } else here *= 2;
                best = Math.min(best, here + 2 * cross + far); }
            return (best == Double.MAX_VALUE ? Weights.w("recovery.other_dim_trip_s", 180) : best) + pileS(g, dim, at); } // (no portal of his that leads there: a flat price)
        ServerLevel level = (ServerLevel) g.level();
        double s = 2 * Math.sqrt(g.blockPosition().distSqr(at)) / Decide.sprint(); // shortcut: straight there and back, not the walker's route
        long tod = runTod(level, at); int n = Decide.hostilesAlong(g, g.blockPosition(), at); double pass = Math.min(s, Weights.w("recovery.hostile_pass_s", 10)); // (a mob he knows of is passed or shaken off, not at his heels the whole 300 blocks: one zombie by him priced a run for his iron at certain death)
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
