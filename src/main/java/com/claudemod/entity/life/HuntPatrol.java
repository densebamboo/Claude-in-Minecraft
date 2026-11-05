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
    /** Endermen seen a minute per 1000 square blocks of warped forest (shortcut: a rough figure, tune it from the pearls lines once hunts log sightings). */
    static final double RATE = 1.0, TYPICAL = 3000, SEARCH_S = 120;
    static final class Book { final Map<Long, Long> walked = new HashMap<>(); final Map<Long, double[]> hunt = new HashMap<>(); Double heading; } // hunt: {weight, server tick it was last added to}
    private static Map<UUID, Map<String, Book>> books; // (static: fields added live start null)
    static Book book(ClaudeEntity g, ServerLevel level) { if (books == null) books = new HashMap<>(); return books.computeIfAbsent(g.getUUID(), k -> new HashMap<>()).computeIfAbsent(PortalMap.dim(level), k -> new Book()); }
    public static void reset(ClaudeEntity g) { if (books != null) books.remove(g.getUUID()); if (picks != null) picks.remove(g.getUUID()); }

    /**
     * The endermen he's gone after and not killed (Day to Day, live 19:29-19:37: an angry one 4 down a pit, in sight; 15 s of swings at
     * air, the watchdog broke the fight off, and the hunt picked the same one again, 8 times in 7 minutes). Picked 3 times in 2 minutes
     * and still alive: let be for 2 minutes, as a player gives up on one he can't get at and looks for another.
     */
    private static Map<UUID, Map<UUID, java.util.ArrayDeque<Long>>> picks;
    private static java.util.ArrayDeque<Long> picksOf(ClaudeEntity g, UUID em) { if (picks == null) picks = new HashMap<>(); return picks.computeIfAbsent(g.getUUID(), k -> new HashMap<>()).computeIfAbsent(em, k -> new java.util.ArrayDeque<>()); }
    static final long PICK_WINDOW = 20L * 120, LET_BE = 20L * 120;
    /** He goes after this one now. */
    public static void picked(ClaudeEntity g, UUID em) { var q = picksOf(g, em); q.addLast(now(g)); while (q.size() > 8) q.pollFirst(); }
    /** Let be: picked 3+ times within 2 minutes, the last of them under 2 minutes ago. */
    public static boolean letBe(ClaudeEntity g, UUID em) {
        if (picks == null || !picks.containsKey(g.getUUID())) return false;
        var q = picks.get(g.getUUID()).get(em); if (q == null || q.size() < 3) return false;
        long now = now(g), last = q.peekLast(); int recent = 0; for (long t : q) if (last - t <= PICK_WINDOW) recent++;
        return recent >= 3 && now - last < LET_BE && now >= last;
    }
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

    /** Warped forest he has in view round him, in square blocks: standing ground on a 4-block grid within 40: seen, or within 16 (the ground round him he knows, stems or not). */
    static int forestArea(ClaudeEntity g, ServerLevel level) {
        int n = 0;
        for (int x = -40; x <= 40; x += 4) for (int z = -40; z <= 40; z += 4) {
            if (x * x + z * z > 1600) continue;
            BlockPos p = FortressScout.standAt(level, g.getBlockX() + x, g.getBlockZ() + z, g.getBlockY());
            if (p != null && level.getBiome(p).is(net.minecraft.world.level.biome.Biomes.WARPED_FOREST) && (x * x + z * z <= 256 || FortressScout.seen(g, level, p))) n++;
        }
        return n * 16;
    }
    /** The next patrol point and why, or null (no seen ground to stand on round him). */
    public static BlockPos next(ClaudeEntity g, ServerLevel level, String[] why) {
        walked(g, level);
        Book b = book(g, level); long now = now(g);
        BlockPos best = null; double bs = -1e9, bestA = 0; String bwhy = "";
        boolean inForest = false; // in a warped forest or by its edge (live 11:01:22: 2 out of it, his feet read the wastes and he left for a leg 26 off)
        if (level.dimension() == net.minecraft.world.level.Level.NETHER) for (int k = 0; k <= 16 && !inForest; k++) { BlockPos q = k == 0 ? g.blockPosition() : g.blockPosition().offset((int) Math.round(Math.cos(k * Math.PI / 4) * (k <= 8 ? 3 : 6)), 0, (int) Math.round(Math.sin(k * Math.PI / 4) * (k <= 8 ? 3 : 6))); inForest = level.getBiome(q).is(net.minecraft.world.level.biome.Biomes.WARPED_FOREST); }
        if (inForest && Atlas.quietAt(g, level, g.blockPosition())) inForest = false; // (found quiet: no more patrolling it)
        boolean anyHunt = false; for (double[] h : b.hunt.values()) if (weight(h, now) > 0.05) anyHunt = true;
        if (inForest && !anyHunt) { // the user 11-05: he knows the forest's size and its odds, and goes elsewhere only when that's the better bet
            int area = forestArea(g, level); double here = 60 * 1000 / (RATE * Math.max(16, area));
            BlockPos other = Atlas.findAway(g, level, "warped_forest", 64);
            double there = (other == null ? SEARCH_S : FortressScout.BORE_S * (Math.abs(other.getX() - g.getBlockX()) + Math.abs(other.getZ() - g.getBlockZ()))) + 60 * 1000 / (RATE * TYPICAL);
            String odds = String.format("this warped forest is about %d square blocks (seen), an enderman every ~%.0f s here; %s ~%.0f s", area, here, other == null ? "finding another and one there:" : "the one at " + other.toShortString() + " and one there:", there);
            if (here > there) { Atlas.quiet(g, level, g.blockPosition()); why[0] = odds + ": on to another"; ClaudeMod.LOGGER.info("[Claude skill] ender_pearls patrol: {} (HuntPatrol.next)", why[0]); return null; }
            if (g.tickCount % 200 < 40) ClaudeMod.LOGGER.info("[Claude skill] ender_pearls patrol: {}: staying (HuntPatrol.next)", odds);
        }
        int[] ds = inForest ? new int[] {12, 8, 16, 24} : new int[] {24, 16, 32, 48}; // (in a warped forest, where they spawn: short hops inside it; its stems hide all past 12, and the far legs it saw were the wastes beyond, live 08:14:52, review-111)
        for (int i = 0; i < 12; i++) {
            double a = i * Math.PI / 6;
            for (int d : ds) {
                BlockPos p = FortressScout.alongRay(g, level, a, d); if (p == null) continue;
                double score = 0; StringBuilder w = new StringBuilder();
                double hunt = 0; for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) { double[] h = b.hunt.get(cell(p.getX() + dx * CELL, p.getZ() + dz * CELL)); if (h != null) hunt += weight(h, now) * (dx == 0 && dz == 0 ? 1.0 : 0.4); }
                Long last = b.walked.get(cell(p.getX(), p.getZ())); boolean fresh = last != null && now - last < 20 * 120 && now >= last;
                if (hunt > 0.05 && !fresh) { score += 4 * hunt; w.append(String.format("endermen there %.1f; ", hunt)); } // ponytail: ground he walked in the last 2 min was seen empty, so old sightings there don't pull him back (the -44/-70 pacing)
                if (fresh) { score -= 4; w.append("walked ").append((now - last) / 20).append(" s ago; "); }
                int bio = biomeScore(level, p); score += bio; if (bio != 0) w.append(level.getBiome(p).unwrapKey().map(k -> k.location().getPath()).orElse("?")).append(' ').append(bio > 0 ? "+" : "").append(bio).append("; ");
                if (b.heading != null) { double turn = Math.cos(a - b.heading); score += 1.5 * turn; if (turn > 0.7) w.append("on his heading; "); }
                score -= Math.abs(d - ds[0]) * 0.02;
                if (score > bs) { bs = score; best = p; bestA = a; bwhy = w + String.format("score %.1f", score); }
                if (!fresh) break; // (the first standable spot along this way; ponytail: a just-walked one looks further out instead, live 20:5x a N-S corridor, both nearest ends walked, he flipped between them)
            }
        }
        if (inForest && !bwhy.contains("endermen there") && (best == null || !level.getBiome(best).is(net.minecraft.world.level.biome.Biomes.WARPED_FOREST))) { // all of the forest walked in the last 2 min: back through it, the oldest ground first; they keep spawning here, the wastes round it have none (live 09:12-09:18, out to the wastes and sent back, round and round)
            BlockPos f = null; double fs = -1e9, fa = 0;
            for (int i = 0; i < 12; i++) { double a = i * Math.PI / 6;
                for (int d : new int[] {8, 12, 16}) {
                    BlockPos p = FortressScout.standAt(level, g.getBlockX() + (int) Math.round(Math.cos(a) * d), g.getBlockZ() + (int) Math.round(Math.sin(a) * d), g.getBlockY());
                    if (p == null || !level.getBiome(p).is(net.minecraft.world.level.biome.Biomes.WARPED_FOREST) || !FortressScout.seen(g, level, p)) continue;
                    Long last = b.walked.get(cell(p.getX(), p.getZ())); double sc = (last == null ? 20 * 120 : Math.min(20 * 120, now - last)) / 20.0 + (b.heading == null ? 0 : 10 * Math.cos(a - b.heading));
                    if (sc > fs) { fs = sc; f = p; fa = a; }
                }
            }
            if (f != null) { best = f; bestA = fa; bwhy = "warped_forest, all of it walked in the last 2 min: back through it, its oldest ground first (the wastes round it have no endermen)"; }
        }
        if (best == null) { why[0] = "no ground in sight to walk to"; return null; }
        if (level.dimension() == net.minecraft.world.level.Level.NETHER && bs < 0 && !level.getBiome(best).is(net.minecraft.world.level.biome.Biomes.WARPED_FOREST)) { // (review-185/191: walked to wastes it scored -3.7 and -4.3, found nothing, turned back)
            why[0] = "nothing worth walking to round here (best " + best.toShortString() + ": " + bwhy + ")";
            ClaudeMod.LOGGER.info("[Claude skill] ender_pearls patrol: {} (HuntPatrol.next)", why[0]); return null;
        }
        b.heading = bestA;
        why[0] = "heading " + Perception.compassOf(new net.minecraft.world.phys.Vec3(Math.cos(bestA), 0, Math.sin(bestA))) + " to " + best.toShortString() + ": " + bwhy;
        ClaudeMod.LOGGER.info("[Claude skill] ender_pearls patrol: {} ({} cells walked, {} with endermen on record) (HuntPatrol.next)", why[0], b.walked.size(), b.hunt.size());
        return best;
    }
}
