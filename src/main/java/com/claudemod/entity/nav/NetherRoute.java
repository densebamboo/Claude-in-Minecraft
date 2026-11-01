package com.claudemod.entity.nav;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

import com.claudemod.entity.ClaudeEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * The long way across the Nether (the current major, checkpoint 5: "reach a fortress from his portal"). The Nether has no sky,
 * so Route's land plan never ran there, and his walker plans only 28 out: from a ledge or a basalt pocket by his portal a deck
 * 100-300 off was "no way at all". This is the plan a player makes looking over the Nether: a coarse search over columns every
 * 4 blocks, any height, with every move he has costed in seconds and risk:
 * <ul>
 * <li>walk (sprint pace), up to 3 down or 1 up a column;</li>
 * <li>tunnel: each solid block on the way dug, at the pace of the best tool on him (netherrack is near instant with a pick);</li>
 * <li>climb: steps cut up or a pillar, about a second a block; drop: steps dug down or a fall within his health;</li>
 * <li>bridge: a block put down for every cell with nothing under it; over lava 2 wide and dear (the risk of a knock off it);</li>
 * <li>never through lava, bedrock or a block that holds lava back; a structure's blocks (the fortress) very dear, as ActionPath.</li>
 * </ul>
 * The answer is a string of leg ends about 12 apart, each a walk ActionPath plans in full (digs, puts, pillars, leaps). Only
 * loaded land is read: past its edge the plan stops at the nearest point and is planned again from there (Route.finish, again).
 */
public final class NetherRoute {
    private NetherRoute() {}

    static final int S = 4;            // the grid: one node every 4 blocks across
    static final int LEG_NODES = 3;    // a leg end every 3 nodes (about 12 blocks)
    static final int BUDGET = 12000;   // nodes expanded per plan
    static final double WALK = 1 / 5.6, CLIMB = 0.9, DESCEND = 0.35, PUT = 0.6, LAVA_RISK = 2.5, SHORE_RISK = 0.8;

    /** The plan: leg ends (feet cells), whether it stops short of the goal (the land past it not loaded), its cost in seconds, and what it does. */
    public record Plan(List<BlockPos> legs, boolean partial, double seconds, int digs, int puts, int climb, int nodes, long ms) {}

    private record Node(int x, int y, int z, double g, double f, Node from, int digs, int puts) {}

    /** The way from {@code from} to within reach of {@code to}, or null when the loaded land holds none in the budget. */
    public static Plan plan(ClaudeEntity g, ServerLevel level, BlockPos from, BlockPos to) {
        long t0 = System.nanoTime();
        Reader r = new Reader(g, level);
        PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
        Map<Long, Double> best = new HashMap<>();
        Node start = new Node(from.getX(), from.getY(), from.getZ(), 0, h(from.getX(), from.getY(), from.getZ(), to), null, 0, 0);
        open.add(start); best.put(key(start.x, start.y, start.z), 0.0);
        Node near = start; double nearH = start.f; int expanded = 0; boolean edge = false;
        while (!open.isEmpty() && expanded++ < BUDGET) {
            Node n = open.poll();
            Double was = best.get(key(n.x, n.y, n.z)); if (was != null && was < n.g - 1e-9) continue;
            double hz = Math.hypot(n.x - to.getX(), n.z - to.getZ());
            if (hz <= 8 && Math.abs(n.y - to.getY()) <= 6 && r.stand(n.x, n.y, n.z)) return done(n, false, expanded, t0, from, to);
            double hn = h(n.x, n.y, n.z, to); if (r.stand(n.x, n.y, n.z) && hn < nearH) { nearH = hn; near = n; }
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                int cx = n.x + dx * S, cz = n.z + dz * S;
                if (!r.loaded(cx, cz)) { edge = true; continue; }
                for (int ny : r.heights(cx, n.y, cz)) {
                    double[] c = r.move(n.x, n.y, n.z, cx, ny, cz);
                    if (c == null) continue;
                    double ng = n.g + c[0];
                    long k = key(cx, ny, cz); Double b0 = best.get(k);
                    if (b0 != null && b0 <= ng) continue;
                    best.put(k, ng);
                    open.add(new Node(cx, ny, cz, ng, ng + 1.3 * h(cx, ny, cz, to), n, n.digs + (int) c[1], n.puts + (int) c[2]));
                }
            }
        }
        if (near != start && (edge || open.isEmpty() || expanded >= BUDGET) && Math.hypot(near.x - to.getX(), near.z - to.getZ()) < Math.hypot(from.getX() - to.getX(), from.getZ() - to.getZ()) - 12) {
            com.claudemod.ClaudeMod.LOGGER.info("[Claude route] Nether {} -> {}: {} (searched {} nodes): as far as {} for now, planned again from there (NetherRoute.plan)", from.toShortString(), to.toShortString(), edge ? "the land past it isn't loaded" : "no whole way in the budget", expanded, new BlockPos(near.x, near.y, near.z).toShortString());
            return done(near, true, expanded, t0, from, to);
        }
        com.claudemod.ClaudeMod.LOGGER.info("[Claude route] Nether {} -> {}: no way in the loaded land ({} nodes searched, {} ms){} (NetherRoute.plan)", from.toShortString(), to.toShortString(), expanded, (System.nanoTime() - t0) / 1000000, edge ? "; the land runs out short of it" : "");
        return null;
    }

    private static Plan done(Node end, boolean partial, int nodes, long t0, BlockPos from, BlockPos to) {
        List<Node> path = new ArrayList<>(); for (Node k = end; k != null; k = k.from) path.add(k);
        java.util.Collections.reverse(path);
        List<BlockPos> legs = new ArrayList<>(); int climb = 0;
        for (int i = 1; i < path.size(); i++) {
            Node a = path.get(i - 1), b = path.get(i); climb += Math.max(0, b.y - a.y);
            boolean last = i == path.size() - 1, big = Math.abs(b.y - a.y) >= 5; // (a big climb or drop ends a leg there: one walk plans it whole)
            if (last || big || i % LEG_NODES == 0 || i + 1 < path.size() && Math.abs(path.get(i + 1).y - b.y) >= 5) {
                BlockPos p = new BlockPos(b.x, b.y, b.z); if (legs.isEmpty() || !legs.get(legs.size() - 1).equals(p)) legs.add(p);
            }
        }
        long ms = (System.nanoTime() - t0) / 1000000;
        Plan p = new Plan(legs, partial, end.g, end.digs, end.puts, climb, nodes, ms);
        com.claudemod.ClaudeMod.LOGGER.info("[Claude route] Nether {} -> {}: {} legs over {} nodes{}, about {} s: {} dug, {} put down, {} up ({} searched, {} ms) (NetherRoute.plan)",
                from.toShortString(), to.toShortString(), legs.size(), path.size(), partial ? " (part way)" : "", (int) end.g, end.digs, end.puts, climb, nodes, ms);
        return p;
    }

    static long key(int x, int y, int z) { return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | ((y + 2048) & 0xFFF); }
    /** At least the time a sprint straight there takes, the climb counted too. */
    static double h(int x, int y, int z, BlockPos to) { double dx = Math.abs(x - to.getX()), dz = Math.abs(z - to.getZ()); return (Math.max(dx, dz) + 0.414 * Math.min(dx, dz)) * WALK + Math.max(0, to.getY() - y) * 0.5 + Math.max(0, y - to.getY()) * 0.15; }

    /** The world as the plan reads it: loaded chunks only, each column's standing heights and each block's dig time kept for the plan. */
    static final class Reader {
        final ClaudeEntity g; final ServerLevel level; final int minY, maxY;
        final Map<Long, LevelChunk> chunks = new HashMap<>();
        final Map<Long, int[]> standCache = new HashMap<>();
        final Map<BlockState, Double> digCache = new HashMap<>();
        Reader(ClaudeEntity g, ServerLevel level) { this.g = g; this.level = level; this.minY = level.getMinBuildHeight() + 1; this.maxY = Math.min(118, Math.min(level.getMaxBuildHeight(), level.getMinBuildHeight() + level.getLogicalHeight()) - 3); } // (never up by the bedrock roof)
        LevelChunk chunk(int x, int z) { long k = ((long) (x >> 4) << 32) ^ ((z >> 4) & 0xffffffffL); return chunks.computeIfAbsent(k, kk -> level.getChunkSource().getChunkNow(x >> 4, z >> 4)); }
        boolean loaded(int x, int z) { return chunk(x, z) != null; }
        BlockState bs(int x, int y, int z) { LevelChunk c = chunk(x, z); return c == null || y < minY - 1 || y > maxY + 3 ? Blocks.BEDROCK.defaultBlockState() : c.getBlockState(new BlockPos(x, y, z)); }
        boolean lava(BlockState s) { return s.getFluidState().is(FluidTags.LAVA); }
        boolean solid(int x, int y, int z) { BlockState s = bs(x, y, z); return !s.getCollisionShape(level, new BlockPos(x, y, z)).isEmpty(); }
        boolean bad(BlockState s) { return lava(s) || s.is(BlockTags.FIRE) || s.is(Blocks.MAGMA_BLOCK) || s.is(Blocks.BEDROCK); }
        /** He can stand here as it is: feet and head open and dry, a sturdy floor that doesn't burn. */
        boolean stand(int x, int y, int z) {
            BlockState f = bs(x, y, z), hd = bs(x, y + 1, z), u = bs(x, y - 1, z); BlockPos up = new BlockPos(x, y - 1, z);
            if (!f.getCollisionShape(level, new BlockPos(x, y, z)).isEmpty() || !hd.getCollisionShape(level, new BlockPos(x, y + 1, z)).isEmpty()) return false;
            if (!f.getFluidState().isEmpty() || !hd.getFluidState().isEmpty() || f.is(BlockTags.FIRE)) return false;
            return u.getFluidState().isEmpty() && !u.is(Blocks.MAGMA_BLOCK) && u.isFaceSturdy(level, up, net.minecraft.core.Direction.UP);
        }
        /** The heights a move into column (x, z) from height y may end at: the standing heights within 32 up and down (the nearest three each way), and y itself (a tunnel or a bridge on at his level). */
        int[] heights(int x, int y, int z) {
            long k = key(x, y, z); int[] c = standCache.get(k); if (c != null) return c;
            List<Integer> up = new ArrayList<>(), down = new ArrayList<>();
            for (int d = 0; d <= 32 && (up.size() < 3 || down.size() < 3); d++) {
                if (up.size() < 3 && y + d <= maxY && stand(x, y + d, z)) up.add(y + d);
                if (d > 0 && down.size() < 3 && y - d >= minY && stand(x, y - d, z)) down.add(y - d);
            }
            List<Integer> all = new ArrayList<>(up); all.addAll(down);
            if (!all.contains(y) && !lava(bs(x, y, z)) && !lava(bs(x, y + 1, z)) && bs(x, y, z).getDestroySpeed(level, BlockPos.ZERO) >= 0 && bs(x, y + 1, z).getDestroySpeed(level, BlockPos.ZERO) >= 0) all.add(y);
            c = all.stream().mapToInt(Integer::intValue).toArray(); standCache.put(k, c); return c;
        }
        /** Seconds to dig it with the best tool on him, -1 never (bedrock, lava, a block entity, nothing that takes it). */
        double dig(int x, int y, int z) {
            BlockState s = bs(x, y, z); BlockPos p = new BlockPos(x, y, z);
            if (lava(s)) return -1;
            if (s.getCollisionShape(level, p).isEmpty()) return s.getFluidState().isEmpty() ? 0 : -1;
            if (s.hasBlockEntity()) return -1;
            for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) if (lava(bs(x + d.getStepX(), y + d.getStepY(), z + d.getStepZ())) && d != net.minecraft.core.Direction.DOWN) return -1; // (a block holding lava back: dug, the lava comes in on him)
            Double c = digCache.get(s);
            if (c == null) {
                float hard = s.getDestroySpeed(level, p);
                if (hard < 0) c = -1.0;
                else {
                    boolean right = !s.requiresCorrectToolForDrops(); float speed = 1f;
                    for (int i = 0; i < g.inventory.items.getContainerSize(); i++) { ItemStack it = g.inventory.items.getItem(i); if (it.isEmpty()) continue; speed = Math.max(speed, it.getDestroySpeed(s)); if (!right && it.isCorrectToolForDrops(s)) right = true; }
                    if (!right) c = -1.0;
                    else { float prog = hard <= 0 ? 1f : speed / hard / 30f; c = Math.min(10, 1.0 / (prog * 20)) + 0.25; } // (+ aiming at it and picking it up)
                }
                digCache.put(s, c);
            }
            if (c < 0) return -1;
            return c + (ActionPath.structureBlock(level, p) && !com.claudemod.entity.life.TempLedger.built(g, p) ? 40 : 0); // (a fortress's own block: very dear, round it or its doorways)
        }
        /** {seconds, digs, puts} for the move from (x, y, z) to (cx, ny, cz), or null when it can't be made. */
        double[] move(int x, int y, int z, int cx, int ny, int cz) {
            double len = Math.hypot(cx - x, cz - z); int steps = (int) Math.ceil(len);
            double sec = len * WALK; int digs = 0, puts = 0;
            int dy = ny - y;
            if (dy > 1) sec += (dy - 1) * CLIMB; // steps cut up or a pillar
            if (dy < -3) sec += (-dy - 3) * DESCEND; // steps dug down, or a drop he can take
            boolean flat = Math.abs(dy) <= 3;
            for (int i = 1; i <= steps; i++) {
                double f = (double) i / steps;
                int px = (int) Math.floor(x + 0.5 + (cx - x) * f), pz = (int) Math.floor(z + 0.5 + (cz - z) * f);
                int py = dy > 0 ? Math.min(ny, y + (int) Math.ceil(dy * Math.min(1.0, f * 1.5))) : dy < 0 ? (int) Math.round(y + dy * f) : y; // (going up: the climb early, at the wall; going down: an even slope)
                for (int hgt = 0; hgt <= 1; hgt++) {
                    double d0 = dig(px, py + hgt, pz);
                    if (d0 < 0) return null;
                    if (d0 > 0) { sec += d0; digs++; }
                }
                if (dy > 0 && i == 1) { double d2 = dig(x, y + 2, z); if (d2 < 0 && solid(x, y + 2, z)) return null; } // (head room over him to step or pillar up)
                if (flat || i == steps) { // what's under him on the way
                    if (!solid(px, py - 1, pz)) {
                        int k = 1; boolean lavaUnder = false;
                        while (k <= 6 && !solid(px, py - k, pz)) { if (lava(bs(px, py - k, pz))) { lavaUnder = true; break; } k++; }
                        if (lavaUnder) { sec += 6 * PUT + LAVA_RISK; puts += 6; } // (over lava: a piece of highway, 4 wide and its 2 rails (ActionPath.crossingTasks), and a knock off it is death)
                        else if (k > 6) { sec += PUT + 1.0; puts++; } // (nothing under it for 6+: in the Nether a fall that far is lava or death, a careful bridge)
                        else if (k > 3 || i == steps) { sec += PUT; puts++; } // (a drop of 3 or less is walked down)
                    }
                    for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) if (lava(bs(px + d.getStepX(), py, pz + d.getStepZ())) || lava(bs(px + d.getStepX(), py - 1, pz + d.getStepZ()))) { sec += SHORE_RISK; break; } // (lava beside the way: slower, careful steps)
                }
            }
            if (bad(bs(cx, ny - 1, cz)) && !lava(bs(cx, ny - 1, cz))) return null; // (never onto magma or fire)
            if (dy < -3) { // a sheer fall into that column (nothing to cut steps in): its damage against what he keeps (Leaps.margin)
                boolean sheer = true; for (int yy = ny + 1; yy <= y; yy++) if (solid(cx, yy, cz)) { sheer = false; break; }
                if (sheer) { float hp = -dy - 3; if (Leaps.health(g) - hp < Leaps.margin(g)) return null; sec += 1 + hp * 1.5; }
            }
            return new double[] {sec, digs, puts};
        }
    }
}
