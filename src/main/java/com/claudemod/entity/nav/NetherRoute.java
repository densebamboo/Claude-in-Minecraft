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
    static final int BUDGET = 12000;   // nodes expanded per plan (nether.route_budget)
    public static int testBudget = 0;  // (a stage cuts it to make the budget run out on a small map)
    static final double WALK = 1 / 5.6, CLIMB = 0.9, DESCEND = 0.35, PUT = 0.6, LAVA_RISK = 2.5, SHORE_RISK = 0.8;

    /** The plan: leg ends (feet cells), whether it stops short of the goal (the land past it not loaded), its cost in seconds, and what it does. */
    public record Plan(List<BlockPos> legs, boolean partial, double seconds, int digs, int puts, int climb, int nodes, long ms) {}

    private record Node(int x, int y, int z, double g, double f, Node from, int digs, int puts) {}

    /** The way from {@code from} to within reach of {@code to}, or null when the loaded land holds none in the budget. */
    public static Plan plan(ClaudeEntity g, ServerLevel level, BlockPos from, BlockPos to) {
        // his own trail, when it runs from him to the goal: a way he's walked, priced against the search (live 22:05 11-06: 661 cells to the portal unused, the search ran out of nodes and dug at the rock nearest the goal; stage 924)
        com.claudemod.entity.life.FarSight.lookAllRound(g, level); com.claudemod.entity.life.PortalMap.seeRoads(g, level); // (a look round, and the roads in it on record: his own and anyone's; stage 928)
        Plan tp = road(g, level, from, to);
        double straight = h(from.getX(), from.getY(), from.getZ(), to);
        if (tp != null && tp.seconds() <= straight * com.claudemod.entity.life.Weights.w("nether.trail_ok_ratio", 1.5)) return tp;
        Plan sp = search(g, level, from, to, false);
        if (tp == null) return sp;
        double est = sp == null ? Double.MAX_VALUE : sp.partial() && !sp.legs().isEmpty() ? sp.seconds() + h(sp.legs().get(sp.legs().size() - 1).getX(), sp.legs().get(sp.legs().size() - 1).getY(), sp.legs().get(sp.legs().size() - 1).getZ(), to) * com.claudemod.entity.life.PortalMap.NETHER_OFF_ROAD : sp.seconds(); // (the rest past what it read, priced as the highway's way off is: ground with no way of his yet)
        if (tp.seconds() < est) { com.claudemod.ClaudeMod.LOGGER.info("[Claude route] Nether {} -> {}: his own way, ~{} s, over the search's ~{} s{} (NetherRoute.plan)", from.toShortString(), to.toShortString(), (int) tp.seconds(), sp == null ? "-" : String.valueOf((int) est), sp != null && sp.partial() ? " (part way, the rest at a sprint)" : ""); return tp; }
        return sp;
    }

    /** His own ways there: his trail, or his highways on record (onto one within reach), the cheaper; null when neither runs there. FortressScout.travel prices it with the walk, bridge and bore. */
    public static Plan road(ClaudeEntity g, ServerLevel level, BlockPos from, BlockPos to) {
        Plan tp = trail(g, from, to), hp = highway(g, from, to, true); if (hp == null) hp = ontoHighway(g, level, from, to); if (tp == null || hp != null && hp.seconds() < tp.seconds()) tp = hp; // (his highways on record outlive a restart; his trail doesn't: D2D 22:50, the user "he's not using the highway for pathing"; stage 925)
        return tp;
    }

    /** Along his trail, from the cell of it nearest him to the cell nearest the goal: leg ends every 8 cells and at each height change of 2; null when it doesn't run from within 6 of him to within 8 of the goal, or has a gap. */
    static Plan trail(ClaudeEntity g, BlockPos from, BlockPos to) {
        long t0 = System.nanoTime(); List<BlockPos> tr = Route.wayBack(g, Integer.MAX_VALUE); if (tr.size() < 2) return null;
        int ia = -1, ib = -1; double da = Double.MAX_VALUE, db = Double.MAX_VALUE;
        for (int i = 0; i < tr.size(); i++) { BlockPos q = tr.get(i); double a = q.distSqr(from), b = q.distSqr(to); if (a < da) { da = a; ia = i; } if (b < db) { db = b; ib = i; } }
        if (da > 36 || db > 64 || ia == ib) return null;
        int step = ia < ib ? 1 : -1, climb = 0, since = 0; double s = Math.sqrt(da) * WALK; List<BlockPos> legs = new ArrayList<>(); BlockPos prev = tr.get(ia), lastEnd = prev;
        for (int i = ia + step; i != ib + step; i += step) {
            BlockPos q = tr.get(i); double d = Math.sqrt(prev.distSqr(q)); if (d > 4) return null; // (a gap: the trail isn't one walk there)
            s += d * WALK + Math.max(0, q.getY() - prev.getY()) * 0.5; climb += Math.max(0, q.getY() - prev.getY());
            if (++since >= 8 || i == ib || Math.abs(q.getY() - lastEnd.getY()) >= 2) { legs.add(q); lastEnd = q; since = 0; }
            prev = q;
        }
        com.claudemod.ClaudeMod.LOGGER.info("[Claude route] Nether {} -> {}: along his trail, {} cells, {} legs, ~{} s (the straight sprint ~{} s) (NetherRoute.trail)", from.toShortString(), to.toShortString(), Math.abs(ib - ia), legs.size(), (int) s, (int) h(from.getX(), from.getY(), from.getZ(), to));
        return new Plan(legs, false, s, 0, 0, climb, 0, (System.nanoTime() - t0) / 1000000);
    }

    /** Along his highways on record (PortalMap segments, kept over restarts): onto the one he's by, along them and across their joins (the turn, a staircase), off by the goal; null when he or the goal isn't by one. */
    /** Off his highway: the search to the point of a segment where the way costs least (the climb or dig to it priced at a dig a block, then along it), then along it; null when none is within nether.highway_reach (48) or the search can't reach it whole. */
    static Plan ontoHighway(ClaudeEntity g, ServerLevel level, BlockPos from, BlockPos to) {
        BlockPos best = null; double bc = Double.MAX_VALUE; Plan bh = null; double reach = com.claudemod.entity.life.Weights.w("nether.highway_reach", 48);
        for (var sg : com.claudemod.entity.life.PortalMap.net(g).segments) { if (!com.claudemod.entity.life.PortalMap.NETHER.equals(sg.dim)) continue;
            BlockPos q = feet(sg, Math.max(sg.lo(), Math.min(sg.hi(), along(sg, from)))); double d = Math.sqrt(q.distSqr(from)); if (d > reach) continue;
            Plan hw = highway(g, q, to, false); if (hw == null) continue;
            double c = d * com.claudemod.entity.life.Weights.w("nether.onto_highway_s_per_block", 1.0) + hw.seconds(); if (c < bc) { bc = c; best = q; bh = hw; } }
        if (best == null) { com.claudemod.ClaudeMod.LOGGER.info("[Claude route] Nether {} -> {}: no highway of his on record within {} with a way along it to the goal (NetherRoute.ontoHighway)", from.toShortString(), to.toShortString(), (int) reach); return null; }
        Plan s = search(g, level, from, best, true); if (s == null || s.partial()) { com.claudemod.ClaudeMod.LOGGER.info("[Claude route] Nether {} -> {}: onto his highway at {}: the search {} (NetherRoute.ontoHighway)", from.toShortString(), to.toShortString(), best.toShortString(), s == null ? "found no way" : "got only part way, to " + (s.legs().isEmpty() ? "-" : s.legs().get(s.legs().size() - 1).toShortString())); return null; }
        List<BlockPos> legs = new ArrayList<>(s.legs()); legs.addAll(bh.legs());
        com.claudemod.ClaudeMod.LOGGER.info("[Claude route] Nether {} -> {}: onto his highway at {} (~{} s: {} dug, {} put, {} up), then along it ({} legs, ~{} s) (NetherRoute.ontoHighway)", from.toShortString(), to.toShortString(), best.toShortString(), (int) s.seconds(), s.digs(), s.puts(), s.climb(), bh.legs().size(), (int) bh.seconds());
        return new Plan(legs, bh.partial(), s.seconds() + bh.seconds(), s.digs(), s.puts(), s.climb() + bh.climb(), s.nodes(), s.ms() + bh.ms());
    }

    static Plan highway(ClaudeEntity g, BlockPos from, BlockPos to, boolean log) {
        long t0 = System.nanoTime(); List<com.claudemod.entity.life.PortalMap.Segment> segs = new ArrayList<>();
        for (var sg : com.claudemod.entity.life.PortalMap.net(g).segments) if (com.claudemod.entity.life.PortalMap.NETHER.equals(sg.dim)) segs.add(sg);
        if (segs.isEmpty()) return null;
        List<BlockPos> pt = new ArrayList<>(); List<Integer> on = new ArrayList<>(); // nodes: 0 him, 1 the goal, then points on segments (which segment)
        pt.add(from); on.add(-1); pt.add(to); on.add(-1);
        for (int i = 0; i < segs.size(); i++) { var sg = segs.get(i);
            pt.add(feet(sg, sg.lo())); on.add(i); pt.add(feet(sg, sg.hi())); on.add(i);
            for (BlockPos p : new BlockPos[] {from, to}) { BlockPos q = feet(sg, Math.max(sg.lo(), Math.min(sg.hi(), along(sg, p)))); if (p == to || Math.abs(across(sg, p) - sg.line()) <= sg.width + 1 && Math.abs(p.getY() - q.getY()) <= 3 && Math.sqrt(p.distSqr(q)) <= (p == from ? 4 : 8)) { pt.add(q); on.add(i); } } }
        for (int i = 0; i < segs.size(); i++) for (int e = 0; e < 2; e++) { var si = segs.get(i); BlockPos end = feet(si, e == 0 ? si.lo() : si.hi()); // a T: a road's end by another's side (a path joined to his highway part way along it)
            for (int j = 0; j < segs.size(); j++) { if (j == i) continue; var sj = segs.get(j); BlockPos q = feet(sj, Math.max(sj.lo(), Math.min(sj.hi(), along(sj, end)))); if (q.distSqr(end) <= 36) { pt.add(q); on.add(j); } } }
        int n = pt.size(); double[] dist = new double[n]; int[] prev = new int[n]; boolean[] done = new boolean[n]; java.util.Arrays.fill(dist, Double.MAX_VALUE); java.util.Arrays.fill(prev, -1); dist[0] = 0;
        for (int it = 0; it < n; it++) { int u = -1; for (int k = 0; k < n; k++) if (!done[k] && dist[k] < Double.MAX_VALUE && (u < 0 || dist[k] < dist[u])) u = k; if (u < 0 || u == 1) break; done[u] = true;
            for (int v = 0; v < n; v++) { if (v == u || done[v]) continue; BlockPos a = pt.get(u), b = pt.get(v); double d = Math.sqrt(a.distSqr(b)), c;
                if (on.get(u) >= 0 && on.get(u).equals(on.get(v))) c = d * WALK; // along one segment
                else if (u == 0 && on.get(v) >= 0 && d <= 4 || v == 1 && on.get(u) >= 0 && d <= 8) c = d * WALK + Math.max(0, b.getY() - a.getY()) * 0.5; // onto it by him, off it by the goal
                else if (v == 1 && on.get(u) >= 0) c = h(a.getX(), a.getY(), a.getZ(), b) * com.claudemod.entity.life.PortalMap.NETHER_OFF_ROAD; // off it anywhere: the rest priced as Nether ground with no way of his yet (the straight line alone is never dearer than the road: every plan left it at his feet), and planned from there (D2D 11-08: off only within 8 of the goal, a goal past the road's end got no highway at all; stage 1192)
                else if (on.get(u) >= 0 && on.get(v) >= 0 && d <= 6) c = d * WALK + Math.max(0, b.getY() - a.getY()) * 0.5; // a join: the turn, a staircase between two runs
                else c = -1;
                if (c >= 0 && dist[u] + c < dist[v]) { dist[v] = dist[u] + c; prev[v] = u; } } }
        if (prev[1] < 0) return null;
        List<Integer> path = new ArrayList<>(); for (int k = 1; k >= 0; k = prev[k]) path.add(0, k);
        List<BlockPos> legs = new ArrayList<>(); int climb = 0;
        for (int i = 1; i < path.size() - 1; i++) { BlockPos a = pt.get(path.get(i - 1)), b = pt.get(path.get(i)); climb += Math.max(0, b.getY() - a.getY());
            if (on.get(path.get(i - 1)) >= 0 && on.get(path.get(i - 1)).equals(on.get(path.get(i)))) { int len = (int) Math.round(Math.sqrt(a.distSqr(b))); for (int k = 8; k < len; k += 8) legs.add(new BlockPos(a.getX() + (b.getX() - a.getX()) * k / len, b.getY(), a.getZ() + (b.getZ() - a.getZ()) * k / len)); }
            if (legs.isEmpty() || !legs.get(legs.size() - 1).equals(b)) legs.add(b); }
        boolean offRoad = Math.sqrt(pt.get(path.get(path.size() - 2)).distSqr(to)) > 8; // (the road ends short of the goal: the plan stops at the exit, the rest planned from there)
        if (legs.isEmpty() || offRoad && legs.get(legs.size() - 1).distSqr(from) <= 8 * 8) return null; // (already at the exit: nothing to ride)
        if (log) com.claudemod.ClaudeMod.LOGGER.info("[Claude route] Nether {} -> {}: along his highway on record, {} legs, ~{} s (the straight sprint ~{} s) (NetherRoute.highway)", from.toShortString(), to.toShortString(), legs.size(), (int) dist[1], (int) h(from.getX(), from.getY(), from.getZ(), to));
        return new Plan(legs, offRoad, dist[1], 0, 0, climb, 0, (System.nanoTime() - t0) / 1000000);
    }
    static int along(com.claudemod.entity.life.PortalMap.Segment s, BlockPos p) { return s.axis == net.minecraft.core.Direction.Axis.X ? p.getX() : p.getZ(); }
    static int across(com.claudemod.entity.life.PortalMap.Segment s, BlockPos p) { return s.axis == net.minecraft.core.Direction.Axis.X ? p.getZ() : p.getX(); }
    /** The feet cell on a segment's line at that point along it. */
    static BlockPos feet(com.claudemod.entity.life.PortalMap.Segment s, int al) { return s.axis == net.minecraft.core.Direction.Axis.X ? new BlockPos(al, s.y() + 1, s.line()) : new BlockPos(s.line(), s.y() + 1, al); }

    private static Plan search(ClaudeEntity g, ServerLevel level, BlockPos from, BlockPos to, boolean entering) {
        long t0 = System.nanoTime();
        com.claudemod.entity.life.FarSight.lookAllRound(g, level); // (a look all round first: the way is planned over what he sees)
        Reader r = new Reader(g, level); r.to = to; r.entering = entering;
        PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
        Map<Long, Double> best = new HashMap<>();
        Node start = new Node(from.getX(), from.getY(), from.getZ(), 0, h(from.getX(), from.getY(), from.getZ(), to), null, 0, 0);
        open.add(start); best.put(key(start.x, start.y, start.z), 0.0);
        Node near = start; double nearH = start.f + Math.abs(start.y - to.getY()) * WALK; int expanded = 0; boolean edge = false;
        int budget = testBudget > 0 ? testBudget : (int) com.claudemod.entity.life.Weights.w("nether.route_budget", BUDGET);
        while (!open.isEmpty() && expanded++ < budget) {
            Node n = open.poll();
            Double was = best.get(key(n.x, n.y, n.z)); if (was != null && was < n.g - 1e-9) continue;
            double hz = Math.hypot(n.x - to.getX(), n.z - to.getZ());
            if (hz <= 8 && Math.abs(n.y - to.getY()) <= 6 && (r.stand(n.x, n.y, n.z) || r.standNear(n.x, n.y, n.z))) return done(r, n, false, expanded, t0, from, to);
            double hn = h(n.x, n.y, n.z, to) + Math.abs(n.y - to.getY()) * WALK; if (hn < nearH) { nearH = hn; near = n; } // (any node the moves reach: a tunnel's end is stood on once dug; counting only cells standable as they are, a way through rock never made headway and "no way in the loaded land" came back every few seconds, D2D live 14:00, stage 910) // ponytail: the part-way end counts height off the goal as a full walk each way (live 16:38: h's 0.15 a block down made the column 42 over the goal "nearest", 23 pillared up to then come 42 down)
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                int cx = n.x + dx * S, cz = n.z + dz * S;
                if (!r.loaded(cx, cz)) { edge = true; continue; }
                for (int ny : r.heights(cx, n.y, cz)) {
                    double[] c = r.move(n.x, n.y, n.z, cx, ny, cz);
                    if (c == null) continue;
                    if (n.puts + c[2] > r.blocks) continue; // (blocks he hasn't got: no bridge, no pillar)
                    double ng = n.g + c[0];
                    long k = key(cx, ny, cz); Double b0 = best.get(k);
                    if (b0 != null && b0 <= ng) continue;
                    best.put(k, ng);
                    open.add(new Node(cx, ny, cz, ng, ng + 1.3 * h(cx, ny, cz, to), n, n.digs + (int) c[1], n.puts + (int) c[2]));
                }
            }
        }
        if (near != start && (edge || open.isEmpty() || expanded >= budget) && Math.hypot(near.x - to.getX(), near.z - to.getZ()) < Math.hypot(from.getX() - to.getX(), from.getZ() - to.getZ()) - 12) {
            com.claudemod.ClaudeMod.LOGGER.info("[Claude route] Nether {} -> {}: {} (searched {} nodes): as far as {} for now, planned again from there (NetherRoute.plan)", from.toShortString(), to.toShortString(), edge ? "the land past it isn't loaded" : "no whole way in the budget", expanded, new BlockPos(near.x, near.y, near.z).toShortString());
            return done(r, near, true, expanded, t0, from, to);
        }
        com.claudemod.ClaudeMod.LOGGER.info("[Claude route] Nether {} -> {}: no way in the loaded land ({} nodes searched, {} ms){} (NetherRoute.plan)", from.toShortString(), to.toShortString(), expanded, (System.nanoTime() - t0) / 1000000, edge ? "; the land runs out short of it" : "");
        return null;
    }

    private static Plan done(Reader r, Node end, boolean partial, int nodes, long t0, BlockPos from, BlockPos to) {
        List<Node> path = new ArrayList<>(); for (Node k = end; k != null; k = k.from) path.add(k);
        java.util.Collections.reverse(path);
        for (int i = 1; i < path.size(); i++) if (!r.known(path.get(i).x, path.get(i).y, path.get(i).z)) { // into ground he hasn't seen: a leg into it, then a look and the plan again from there (what he sees there decides the rest)
            int keep = Math.min(path.size(), i + LEG_NODES);
            if (keep < path.size()) { Node at = path.get(keep - 1); com.claudemod.ClaudeMod.LOGGER.info("[Claude route] Nether {} -> {}: into ground he hasn't seen at {},{},{}: as far as {},{},{} for now, a look and the plan again from there (NetherRoute.done)", from.toShortString(), to.toShortString(), path.get(i).x, path.get(i).y, path.get(i).z, at.x, at.y, at.z); while (path.size() > keep) path.remove(path.size() - 1); end = at; partial = true; }
            break;
        }
        { StringBuilder nb = new StringBuilder(); for (int i = 0; i < path.size() && i < 40; i++) { Node b = path.get(i); nb.append(i == 0 ? "" : " ").append(b.x).append(',').append(b.y).append(',').append(b.z).append('(').append((int) b.g).append(')'); }
          com.claudemod.ClaudeMod.LOGGER.info("[Claude route] Nether {} -> {}: its nodes (x,y,z, (s so far)): {} (NetherRoute.plan)", from.toShortString(), to.toShortString(), nb); } // (Day to Day 14:48: a way over the warped canopy, 38 up; the nodes prove why next time)
        List<BlockPos> legs = new ArrayList<>(); int climb = 0;
        for (int i = 1; i < path.size(); i++) {
            Node a = path.get(i - 1), b = path.get(i); climb += Math.max(0, b.y - a.y);
            boolean last = i == path.size() - 1, big = Math.abs(b.y - a.y) >= 2 || b.digs - a.digs >= 3; // (a climb or drop of 2+ ends a leg there, its foot a leg end too: one walk plans its steps or pillar whole (Day to Day 15:04:48: a leg 3 above the one before, his body refused the jump, 6 s shuffling); so does a tunnel's every node: 12 across of tunnel, 24 cells dug, was past his walker's search and every leg "no way", stage 538)
            for (int up = a.y + 8; up < b.y; up += 8) legs.add(new BlockPos(b.x, up, b.z)); // a climb over 8: a leg end every 8 up its column (one walk plans a climb that size; 32 up in one leg was past the walker's reach, stage 535)
            if (last || big || i % LEG_NODES == 0 || i + 1 < path.size() && Math.abs(path.get(i + 1).y - b.y) >= 2) {
                BlockPos p = new BlockPos(b.x, b.y, b.z); if (legs.isEmpty() || !legs.get(legs.size() - 1).equals(p)) legs.add(p);
            }
        }
        long ms = (System.nanoTime() - t0) / 1000000;
        Plan p = new Plan(legs, partial, end.g, end.digs, end.puts, climb, nodes, ms);
        { int onTrail = 0, unseen = 0; for (int i = 1; i < path.size(); i++) { Node b = path.get(i); if (Route.walked(r.g, new BlockPos(b.x, b.y, b.z)) || Route.walked(r.g, new BlockPos(b.x, b.y - 1, b.z))) onTrail++; else if (!r.known(b.x, b.y - 1, b.z)) unseen++; }
          BlockPos trailNear = null; double td = Double.MAX_VALUE; for (BlockPos q : Route.wayBack(r.g, Integer.MAX_VALUE)) { double d = q.distSqr(to); if (d < td) { td = d; trailNear = q; } }
          com.claudemod.ClaudeMod.LOGGER.info("[Claude route] Nether {} -> {}: its {} nodes: {} on his trail, {} on ground he hasn't seen; his trail ({} cells) comes within {} of the goal{} (NetherRoute.done)", from.toShortString(), to.toShortString(), path.size() - 1, onTrail, unseen, Route.wayBack(r.g, Integer.MAX_VALUE).size(), trailNear == null ? "-" : (int) Math.sqrt(td), trailNear == null ? "" : " at " + trailNear.toShortString()); } // (D2D 01:50: "a rough time pathing back to his portal"; is his own way back on record, and does the plan use it? proven before a fix)
        com.claudemod.ClaudeMod.LOGGER.info("[Claude route] Nether {} -> {}: {} legs over {} nodes{}, about {} s: {} dug, {} put down, {} up ({} searched, {} ms; unseen cells read as netherrack) (NetherRoute.plan)",
                from.toShortString(), to.toShortString(), legs.size(), path.size(), partial ? " (part way)" : "", (int) end.g, end.digs, end.puts, climb, nodes, ms);
        return p;
    }

    static long key(int x, int y, int z) { return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | ((y + 2048) & 0xFFF); }
    /** At least the time a sprint straight there takes, the climb counted too. */
    static double h(int x, int y, int z, BlockPos to) { double dx = Math.abs(x - to.getX()), dz = Math.abs(z - to.getZ()); return (Math.max(dx, dz) + 0.414 * Math.min(dx, dz)) * WALK + Math.max(0, to.getY() - y) * 0.5 + Math.max(0, y - to.getY()) * 0.15; }

    /** The world as the plan reads it: loaded chunks only, each column's standing heights and each block's dig time kept for the plan. */
    static final class Reader {
        final ClaudeEntity g; final ServerLevel level; final int minY, maxY;
        final int blocks; // what he carries to put down: a way needing more isn't his
        final Map<Long, LevelChunk> chunks = new HashMap<>();
        final Map<Long, int[]> standCache = new HashMap<>();
        final Map<BlockState, Double> digCache = new HashMap<>();
        java.util.List<BlockPos> failedLegs; // (his walk failures of the last 2 min: Attempts WALK)
        boolean failedNear(int x, int y, int z) {
            if (failedLegs == null) { failedLegs = new java.util.ArrayList<>(); var m = g.attempts.of(com.claudemod.entity.life.Attempts.Kind.WALK); if (m != null) for (var e : m.entrySet()) if (g.tickCount - e.getValue() < 20 * 120 && g.tickCount >= e.getValue()) failedLegs.add(e.getKey()); }
            for (BlockPos f : failedLegs) if (Math.abs(f.getX() - x) <= 3 && Math.abs(f.getZ() - z) <= 3 && Math.abs(f.getY() - y) <= 3) return true;
            return false;
        }
        /** A walk that failed (last 2 min) within 8 across of this column, ending 4+ below where this move starts: the walker searched that drop and found no way down. */
        boolean failedDrop(int cx, int fromY, int cz) { failedNear(cx, fromY, cz); for (BlockPos f : failedLegs) if (Math.abs(f.getX() - cx) <= 8 && Math.abs(f.getZ() - cz) <= 8 && f.getY() <= fromY - 4) return true; return false; }
        Reader(ClaudeEntity g, ServerLevel level) { this.g = g; this.level = level; this.eye = g.getEyePosition(); this.sight = com.claudemod.entity.life.FarSight.reach(level); this.minY = level.getMinBuildHeight() + 1; this.blocks = com.claudemod.director.Kit.buildingBlocks(g); this.maxY = Math.min(118, Math.min(level.getMaxBuildHeight(), level.getMinBuildHeight() + level.getLogicalHeight()) - 3); } // (never up by the bedrock roof)
        LevelChunk chunk(int x, int z) { long k = ((long) (x >> 4) << 32) ^ ((z >> 4) & 0xffffffffL); return chunks.computeIfAbsent(k, kk -> level.getChunkSource().getChunkNow(x >> 4, z >> 4)); }
        boolean loaded(int x, int z) { return chunk(x, z) != null; }
        boolean entering; // the goal is in his highway: its shell by the goal is a way in (a block out, then put back), not a shortcut through it
        BlockPos to; // the goal: he knows where he's headed (a sighting, his atlas), so its cells within 8 are read as they are
        final Map<Long, Boolean> knownMemo = new HashMap<>();
        /** A cell he hasn't seen, walked or stood by reads as netherrack: rock at its dig price till he sees it (never a cave he never saw). */
        final net.minecraft.world.phys.Vec3 eye; final double sight; // where he looks from as he plans, and how far he sees (the Nether's fog)
        boolean known(int x, int y, int z) { if (to != null && Math.abs(x - to.getX()) <= 8 && Math.abs(y - to.getY()) <= 8 && Math.abs(z - to.getZ()) <= 8) return true;
            return knownMemo.computeIfAbsent(key(x, y, z), k -> com.claudemod.entity.life.FarSight.known(g, x, y, z) || com.claudemod.entity.life.PortalMap.nearHighway(g, com.claudemod.entity.life.PortalMap.NETHER, x, y, z, 32, 8, 4) // (his own highway: read as it is, never as unseen rock; the user 11-06 21:07 "he's not using the highway for pathing")
                || (x + 0.5 - eye.x) * (x + 0.5 - eye.x) + (y + 0.5 - eye.y) * (y + 0.5 - eye.y) + (z + 0.5 - eye.z) * (z + 0.5 - eye.z) <= sight * sight && clearPastHis(eye, new net.minecraft.world.phys.Vec3(x + 0.5, y + 0.5, z + 0.5)) && seen(x, y, z)); }
        /** Perception.clear, his own blocks seen past: the bridge or floor he put down over the void hid the cavern under it from the middle of it, so the cavern read as rock and legs were planned standing in mid-air (live 11-08: reviews 55, 127, 128, 165, 167; stage 1047). He put them there over open air; he knows what's behind them. */
        boolean clearPastHis(net.minecraft.world.phys.Vec3 from, net.minecraft.world.phys.Vec3 to) {
            BlockPos fromCell = BlockPos.containing(from), toCell = BlockPos.containing(to); int[] leaves = {0};
            Boolean ok = net.minecraft.world.level.BlockGetter.traverseBlocks(from, to, level, (lv, pos) -> {
                if (pos.equals(fromCell) || pos.equals(toCell)) return null;
                var st = lv.getBlockState(pos);
                if (lv.getFluidState(pos).is(FluidTags.LAVA)) return Boolean.FALSE;
                if (st.is(BlockTags.LEAVES) && ++leaves[0] >= 3) return Boolean.FALSE;
                if (!st.canOcclude() || com.claudemod.entity.life.TempLedger.built(g, pos) || com.claudemod.entity.life.TempLedger.has(g, pos)) return null;
                return st.getShape(lv, pos).clip(from, to, pos) != null ? Boolean.FALSE : null;
            }, lv -> Boolean.TRUE);
            return ok == null || ok;
        }
        private boolean seen(int x, int y, int z) { com.claudemod.entity.life.FarSight.sawCell(g, x, y, z); return true; } // (in plain view from where he stands: the void he looks across is air, not rock (Day to Day 15:17 11-05: the route tunnelled through the air over a lava sea); the ray fan alone leaves holes a block wide at 10 off; out to his sight, not 24: a void past a 24 cut-off read as floor again, stage 535)
        BlockState bs(int x, int y, int z) { LevelChunk c = chunk(x, z); return c == null || y < minY - 1 || y > maxY + 3 ? Blocks.BEDROCK.defaultBlockState() : !known(x, y, z) ? Blocks.NETHERRACK.defaultBlockState() : c.getBlockState(new BlockPos(x, y, z)); }
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
        /** A spot to stand on within 2 across and 1 up or down: the grid is 4 apart, a 3-wide deck between its columns is still arrived at (538: the fortress's corridors fell between them, "no way" in 12000 nodes; the walker plans that last bit in full). */
        boolean standNear(int x, int y, int z) { for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = -1; dy <= 1; dy++) if (stand(x + dx, y + dy, z + dz)) return true; return false; }
        /** The heights a move into column (x, z) from height y may end at: the standing heights within 32 up and down (the nearest three each way), and y itself (a tunnel or a bridge on at his level). */
        int[] heights(int x, int y, int z) {
            long k = key(x, y, z); int[] c = standCache.get(k); if (c != null) return c;
            List<Integer> up = new ArrayList<>(), down = new ArrayList<>();
            for (int d = 0; d <= 32 && (up.size() < 3 || down.size() < 3); d++) {
                if (up.size() < 3 && y + d <= maxY && stand(x, y + d, z)) up.add(y + d);
                if (d > 0 && down.size() < 3 && y - d >= minY && stand(x, y - d, z)) down.add(y - d);
            }
            List<Integer> all = new ArrayList<>(up); all.addAll(down);
            if (up.isEmpty() && y + 8 <= maxY) { boolean ok = true; for (int yy = y; yy <= y + 9 && ok; yy++) ok = bs(x, yy, z).isAir(); if (ok) all.add(y + 8); } // an open shaft he's seen, nothing to stand on within 32 up: 8 up it, a pillar (538 11-05: under a fortress deck 37 over him, no way up once the shaft read as the air it is). Never in rock: there every column offered it and searches ran 12000 nodes, 2+ s (535)
            if (!all.contains(y) && !lava(bs(x, y, z)) && !lava(bs(x, y + 1, z)) && bs(x, y, z).getDestroySpeed(level, BlockPos.ZERO) >= 0 && bs(x, y + 1, z).getDestroySpeed(level, BlockPos.ZERO) >= 0) all.add(y);
            if (to != null && to.getY() != y) { int ty = y + Integer.signum(to.getY() - y) * Math.min(4, Math.abs(to.getY() - y)); // steps cut through the rock toward the goal's height (only his own level or a cell to stand on was offered: 12 under his highway he went down to the void and round, never up through the rock, live 23:05 11-06 "digging up and around or down"; stage 926)
                if (!all.contains(ty) && ty >= minY && ty + 1 <= maxY && !lava(bs(x, ty, z)) && !lava(bs(x, ty + 1, z)) && bs(x, ty, z).getDestroySpeed(level, BlockPos.ZERO) >= 0 && bs(x, ty + 1, z).getDestroySpeed(level, BlockPos.ZERO) >= 0) all.add(ty); }
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
            if (com.claudemod.entity.life.PortalMap.highwayBlock(g, com.claudemod.entity.life.PortalMap.NETHER, p, s)) return c + (entering && Math.abs(x - to.getX()) <= 3 && Math.abs(z - to.getZ()) <= 3 && Math.abs(y - to.getY()) <= 3 ? com.claudemod.entity.life.Weights.w("netherroute.highway_entry_s", 3) : com.claudemod.entity.life.Weights.w("netherroute.highway_dig_s", 40)); /* (stepping into it by where it is joined: stage 926, the search found no way in through 40 s walls) */ // (his own highway: walked, never dug through for a shortcut; the user 11-06 21:07 "he's not using the highway for pathing")
            return c + (known(x, y, z) && ActionPath.structureBlock(level, p) && !com.claudemod.entity.life.TempLedger.built(g, p) ? 40 : 0); // (a fortress's own block: very dear, round it or its doorways)
        }
        /** {seconds, digs, puts} for the move from (x, y, z) to (cx, ny, cz), or null when it can't be made. */
        double[] move(int x, int y, int z, int cx, int ny, int cz) {
            double len = Math.hypot(cx - x, cz - z); int steps = (int) Math.ceil(len);
            double sec = len * WALK; int digs = 0, puts = 0;
            double PUT = com.claudemod.entity.life.Weights.w("netherroute.put_s", 2.2); // (a put out here is also one to go back for and take up: ~12 walk-blocks; was 0.6, 3 walk-blocks, and he bridged tree to tree, the user 11-06)
            int dy = ny - y;
            if (dy > 1) sec += (dy - 1) * CLIMB; // steps cut up or a pillar
            if (dy < -3) sec += (-dy - 3) * DESCEND; // steps dug down, or a drop he can take
            boolean flat = Math.abs(dy) <= 3;
            int prevPy = y;
            for (int i = 1; i <= steps; i++) {
                double f = (double) i / steps;
                int px = (int) Math.floor(x + 0.5 + (cx - x) * f), pz = (int) Math.floor(z + 0.5 + (cz - z) * f);
                int py = dy > 0 ? Math.min(ny, y + (int) Math.ceil(dy * Math.min(1.0, f * 1.5))) : dy < 0 ? (int) Math.round(y + dy * f) : y; // (going up: the climb early, at the wall; going down: an even slope)
                for (int yy = Math.min(prevPy, py); yy <= Math.max(prevPy, py) + 1; yy++) { // (every cell of height this step passes, feet to head: a steep step down sampled one cell a block and planned through bedrock under it, stage 545)
                    double d0 = dig(px, yy, pz);
                    if (d0 < 0) return null;
                    if (d0 > 0) { sec += d0 + com.claudemod.entity.life.Weights.w("netherroute.dig_extra_s", 1.0); digs++; } // (the swing, the pickup and the hole left: the user 11-06, "digging way too much unnecessarily")
                }
                prevPy = py;
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
            if (dy > 1 && digs == 0) puts += dy - 1; // (nothing cut for the climb: a pillar, one block a step up)
            if (bad(bs(cx, ny - 1, cz)) && !lava(bs(cx, ny - 1, cz))) return null; // (never onto magma or fire)
            if (dy < -1 && !solid(cx, ny - 1, cz)) return null; // (a way down lands on ground: no block is put under a spot 2+ below him, nothing to set it against on the way down; the plan stepped down the open cavern on blocks it would put in mid-air, stage 1047)
            if (failedNear(cx, ny, cz)) return null; // (the walker found no way there in the last 2 min: not that spot again, not even dearer; +20 s left the next plan a block over the same fall, live 14:04-14:06 11-08, reviews 127/128, stage 1047)
            if (dy < -3 && failedDrop(cx, y, cz)) return null; // (a way down near one the walker found none at, from about this height: the same drop a few blocks over) // (a leg that ended there failed in the last 2 min: the way round it, never the same spot again; live -303,87,-63, a 40 s shuffle re-planned onto the node that failed)
            if (dy < -3) { // a sheer fall into that column (nothing to cut steps in): its damage against what he keeps (Leaps.margin)
                boolean sheer = true; for (int yy = ny + 1; yy <= y; yy++) if (solid(cx, yy, cz)) { sheer = false; break; }
                if (sheer) { float hp = -dy - 3; if (Leaps.health(g) - hp < Leaps.margin(g)) return null; sec += 1 + hp * 1.5 * Leaps.hpPrice(g); }
            }
            return new double[] {sec, digs, puts};
        }
    }
}
