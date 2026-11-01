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
        public String status; public int loadedTicks; public boolean wasNear;
        Death(String dim, BlockPos at, long time, String what, String status, int loadedTicks) { this.dim = dim; this.at = at; this.time = time; this.what = what; this.status = status; this.loadedTicks = loadedTicks; }
        public boolean pending() { return status.equals("pending"); }
        String line() { return dim + "\t" + at.getX() + "\t" + at.getY() + "\t" + at.getZ() + "\t" + time + "\t" + status + "\t" + loadedTicks + "\t" + what; }
    }

    private static Map<UUID, List<Death>> all; // (static, lazy: fields added live start null)

    private static Path file(ClaudeEntity g) { return g.getServer().getWorldPath(LevelResource.ROOT).resolve("claude").resolve("deaths-" + g.getUUID() + ".tsv"); }

    public static List<Death> of(ClaudeEntity g) {
        if (all == null) all = new HashMap<>();
        return all.computeIfAbsent(g.getUUID(), k -> {
            List<Death> l = new ArrayList<>();
            try { Path f = file(g); if (Files.exists(f)) for (String s : Files.readAllLines(f, StandardCharsets.UTF_8)) { String[] p = s.split("\t", 8); if (p.length == 8)
                l.add(new Death(p[0], new BlockPos(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3])), Long.parseLong(p[4]), p[7], p[5], Integer.parseInt(p[6]))); } }
            catch (Exception e) { ClaudeMod.LOGGER.warn("[Claude deaths] couldn't read his death list: {}", e.toString()); }
            if (l.isEmpty()) for (String[] s : com.claudemod.director.ClaudeLife.deathKitsOf(g.getUUID())) { // (first run, live: the deaths he remembers already; newest first in, so oldest first out)
                var m = java.util.regex.Pattern.compile(" in (\\w+) at (-?\\d+), (-?\\d+), (-?\\d+)[^;]*; (.*)").matcher(s[1]);
                if (m.find()) l.add(0, new Death(m.group(1), new BlockPos(Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4))), Long.parseLong(s[0]), m.group(5), "pending", 0)); }
            if (!l.isEmpty()) ClaudeMod.LOGGER.info("[Claude deaths] his death checklist: {}", l.stream().map(d -> d.dim + " " + d.at.toShortString() + " " + d.status).toList());
            return l; });
    }

    private static void save(ClaudeEntity g) {
        try { Path f = file(g); Files.createDirectories(f.getParent()); List<String> out = new ArrayList<>(); for (Death d : of(g)) out.add(d.line()); Files.write(f, out, StandardCharsets.UTF_8); }
        catch (Exception e) { ClaudeMod.LOGGER.warn("[Claude deaths] couldn't save his death list: {}", e.toString()); }
    }

    /** He just died and these items fell: a new pending entry. */
    public static void died(ClaudeEntity g, ServerLevel level, BlockPos at, List<ItemEntity> fell) {
        if (fell.isEmpty()) return;
        Map<String, Integer> what = new TreeMap<>();
        for (ItemEntity i : fell) what.merge(BuiltInRegistries.ITEM.getKey(i.getItem().getItem()).getPath(), i.getItem().getCount(), Integer::sum);
        StringBuilder sb = new StringBuilder(); what.forEach((k, v) -> sb.append(sb.length() == 0 ? "" : ", ").append(k).append(v > 1 ? " " + v : ""));
        List<Death> l = of(g);
        l.add(new Death(level.dimension().location().getPath(), at.immutable(), level.getGameTime(), sb.toString(), "pending", 0));
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
            if (!d.pending()) { changed = true; ClaudeMod.LOGGER.info("[Claude deaths] his pile at {} in {}: {} ({} s loaded)", d.at.toShortString(), dim, d.status, d.loadedTicks / 20); }
        }
        if (changed) save(g);
    }

    /** The stages' reset. */
    public static void reset(ClaudeEntity g) { of(g).clear(); save(g); }

    /** He gives a pile up on purpose (they said so, or it isn't worth the risk). */
    public static void abandon(ClaudeEntity g, Death d, String why) { d.status = "abandoned: " + why; save(g); ClaudeMod.LOGGER.info("[Claude deaths] his pile at {} in {}: {}", d.at.toShortString(), d.dim, d.status); }

    /** The pending pile to go for: the nearest in this dimension, else the newest elsewhere; null when none. */
    public static Death next(ClaudeEntity g, String dim) {
        Death best = null;
        for (Death d : of(g)) if (d.pending() && d.dim.equals(dim) && (best == null || g.blockPosition().distSqr(d.at) < g.blockPosition().distSqr(best.at))) best = d;
        if (best != null) return best;
        for (Death d : of(g)) if (d.pending()) best = d; // (newest: last on the list)
        return best;
    }

    /** The checklist for his prompts (planner and chat). */
    public static String describe(ClaudeEntity g, ServerLevel level) {
        List<Death> l = of(g); if (l.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("  YOUR DEATHS CHECKLIST (exact; pending = your things still lie there; items in an unloaded chunk don't age; answer where your stuff is from this list only):\n");
        int from = Math.max(0, l.size() - 6);
        for (int i = l.size() - 1; i >= from; i--) { Death d = l.get(i);
            sb.append("   - ").append(d.dim).append(" at ").append(d.at.toShortString()).append(d.time > 0 ? ", " + (level.getGameTime() - d.time) / 1200 + " min ago" : "")
              .append(": ").append(d.status.toUpperCase().startsWith("PENDING") ? "PENDING" : d.status).append(d.dim.equals(level.dimension().location().getPath()) ? "" : " (other dimension: through your portal)")
              .append("; fell: ").append(d.what.length() > 160 ? d.what.substring(0, 160) + "..." : d.what).append("\n"); }
        return sb.toString();
    }
}
