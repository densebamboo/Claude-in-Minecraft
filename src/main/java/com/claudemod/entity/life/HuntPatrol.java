package com.claudemod.entity.life;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.claudemod.ClaudeMod;
import com.claudemod.entity.ClaudeEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * The pearl hunt's patrol (the user, 2026-11-01: "explore it while patroling, then intelligently patrol the most optimal area for
 * hunting"). Live 15:23 each re-decision queued a bare explore, its targets flipping (-121,76,-916 then -87,64,-919). His memory, per
 * dimension, on a 16-block grid: when he last walked each cell, and where endermen were seen or fought (a weight that halves every 10
 * minutes). The next patrol point is the best of the seen ground he can stand on 16-32 out in 12 directions, scored by: endermen
 * there or near (the pull), ground walked in the last 2 minutes (the push), the user's biome rules (in the Nether a warped forest; in
 * the overworld open ground over forest and jungle, where the trees block the view), and his heading held (no flip-flopping).
 */
public final class HuntPatrol {
    private HuntPatrol() {}

    static final int CELL = 16;
    static final class Book { final Map<Long, Long> walked = new HashMap<>(); final Map<Long, double[]> hunt = new HashMap<>(); Double heading; } // hunt: {weight, server tick it was last added to}
    private static Map<UUID, Map<String, Book>> books; // (static: fields added live start null)
    static Book book(ClaudeEntity g, ServerLevel level) { if (books == null) books = new HashMap<>(); return books.computeIfAbsent(g.getUUID(), k -> new HashMap<>()).computeIfAbsent(PortalMap.dim(level), k -> new Book()); }
    public static void reset(ClaudeEntity g) { if (books != null) books.remove(g.getUUID()); }
    static long cell(int x, int z) { return ((long) Math.floorDiv(x, CELL) << 32) ^ (Math.floorDiv(z, CELL) & 0xffffffffL); }
    static long now(ClaudeEntity g) { return g.getServer() == null ? g.tickCount : g.getServer().getTickCount(); } // (server ticks: his own restart after a portal)

    /** An enderman seen or fought at {@code p}: that ground scores for the hunt. */
    public static void saw(ClaudeEntity g, ServerLevel level, BlockPos p, double w) {
        Book b = book(g, level); long k = cell(p.getX(), p.getZ()); double[] h = b.hunt.computeIfAbsent(k, kk -> new double[] {0, now(g)});
        h[0] = weight(h, now(g)) + w; h[1] = now(g);
    }
    static double weight(double[] h, long now) { double age = Math.max(0, now - h[1]) / (20.0 * 60 * 10); return h[0] * Math.pow(0.5, age); }
    /** Each call: the ground he's on is walked now. */
    public static void walked(ClaudeEntity g, ServerLevel level) { Book b = book(g, level); long t = now(g); b.walked.put(cell(g.getBlockX(), g.getBlockZ()), t);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) { double[] h = b.hunt.get(cell(g.getBlockX() + dx * CELL, g.getBlockZ() + dz * CELL)); if (h != null) { h[0] = weight(h, t) * 0.5; h[1] = t; } } } // ponytail: called only with no enderman in sight: ground seen empty pulls less (the user 20:2x "pacing back and forth in the same area": the kills at -49..-75,-880 kept pulling him back, east-west 8 times in 30 s)

    static int biomeScore(ServerLevel level, BlockPos p) {
        String b = level.getBiome(p).unwrapKey().map(k -> k.location().getPath()).orElse("");
        if (level.dimension() == net.minecraft.world.level.Level.NETHER) return b.equals("warped_forest") ? 3 : -5;
        if (b.contains("plains") || b.contains("desert") || b.contains("savanna") || b.contains("badlands") || b.contains("meadow") || b.contains("snowy_slopes")) return 2;
        if (b.contains("forest") || b.contains("jungle") || b.contains("taiga") || b.contains("swamp")) return -2;
        return 0;
    }

    /** The next patrol point and why, or null (no seen ground to stand on round him). */
    public static BlockPos next(ClaudeEntity g, ServerLevel level, String[] why) {
        walked(g, level);
        Book b = book(g, level); long now = now(g);
        BlockPos best = null; double bs = -1e9, bestA = 0; String bwhy = "";
        for (int i = 0; i < 12; i++) {
            double a = i * Math.PI / 6;
            for (int d : new int[] {24, 16, 32}) {
                BlockPos p = FortressScout.alongRay(g, level, a, d); if (p == null) continue;
                double score = 0; StringBuilder w = new StringBuilder();
                double hunt = 0; for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) { double[] h = b.hunt.get(cell(p.getX() + dx * CELL, p.getZ() + dz * CELL)); if (h != null) hunt += weight(h, now) * (dx == 0 && dz == 0 ? 1.0 : 0.4); }
                Long last = b.walked.get(cell(p.getX(), p.getZ())); boolean fresh = last != null && now - last < 20 * 120 && now >= last;
                if (hunt > 0.05 && !fresh) { score += 4 * hunt; w.append(String.format("endermen there %.1f; ", hunt)); } // ponytail: ground he walked in the last 2 min was seen empty, so old sightings there don't pull him back (the -44/-70 pacing)
                if (fresh) { score -= 4; w.append("walked ").append((now - last) / 20).append(" s ago; "); }
                int bio = biomeScore(level, p); score += bio; if (bio != 0) w.append(level.getBiome(p).unwrapKey().map(k -> k.location().getPath()).orElse("?")).append(' ').append(bio > 0 ? "+" : "").append(bio).append("; ");
                if (b.heading != null) { double turn = Math.cos(a - b.heading); score += 1.5 * turn; if (turn > 0.7) w.append("on his heading; "); }
                score -= Math.abs(d - 24) * 0.02;
                if (score > bs) { bs = score; best = p; bestA = a; bwhy = w + String.format("score %.1f", score); }
                break; // (the first standable spot along this way)
            }
        }
        if (best == null) { why[0] = "no ground in sight to walk to"; return null; }
        b.heading = bestA;
        why[0] = "heading " + Perception.compassOf(new net.minecraft.world.phys.Vec3(Math.cos(bestA), 0, Math.sin(bestA))) + " to " + best.toShortString() + ": " + bwhy;
        ClaudeMod.LOGGER.info("[Claude skill] ender_pearls patrol: {} ({} cells walked, {} with endermen on record) (HuntPatrol.next)", why[0], b.walked.size(), b.hunt.size());
        return best;
    }
}
