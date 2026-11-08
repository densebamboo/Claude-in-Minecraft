package com.claudemod.entity.nav;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

/**
 * The open ends of a fortress's bridges and corridors (the user, 2026-11-01: "his tunnels to and from the nether fortress should
 * connect to the open ends of the fortress bridges"). A way into the fortress joins it where a bridge or corridor stops, along its
 * axis, never through its side; a way out leaves by the end nearest where he's going. Read from the blocks: the deck he can stand
 * on (FortressScout.stand on nether bricks), walked from a deck cell; an end is a deck cell whose next cell along some way is no
 * deck, with 6+ cells of deck straight behind it and 3 to 7 across it (a side edge has only the bridge's width behind it).
 */
public final class FortressEnds {
    private FortressEnds() {}

    /** An end: its last deck cell (feet), the way out of it, and the cell he stands in just outside it (the way in or out joins there). */
    public record End(BlockPos deck, Direction out, BlockPos outside) {}
    public static volatile End picked; public static volatile String pickedAmong = ""; // the last end Route chose and the ends it weighed (stage 538: never back to one he left while he knew the same ends)

    static boolean deck(ServerLevel level, BlockPos feet) {
        if (!level.isLoaded(feet)) return false;
        var under = level.getBlockState(feet.below());
        return com.claudemod.entity.life.FortressScout.stand(level, feet) && (under.is(net.minecraft.world.level.block.Blocks.NETHER_BRICKS) || under.is(net.minecraft.world.level.block.Blocks.NETHER_BRICK_SLAB) || under.is(net.minecraft.world.level.block.Blocks.NETHER_BRICK_STAIRS));
    }

    /** The deck cell nearest {@code p} (within 6 across, 8 up or down), or null. */
    public static BlockPos deckNear(ServerLevel level, BlockPos p) {
        BlockPos best = null; double bd = Double.MAX_VALUE;
        for (BlockPos q : BlockPos.betweenClosed(p.offset(-6, -8, -6), p.offset(6, 8, 6))) if (deck(level, q)) { double d = q.distSqr(p); if (d < bd) { bd = d; best = q.immutable(); } }
        return best;
    }

    /** The deck he can walk from {@code start} (3000 cells at most) and its open ends. */
    public static List<End> ends(ServerLevel level, BlockPos start) {
        List<End> out = new ArrayList<>(); if (start == null || !deck(level, start)) return out;
        Map<BlockPos, Integer> seen = new HashMap<>(); ArrayDeque<BlockPos> q = new ArrayDeque<>(); seen.put(start, 0); q.add(start);
        while (!q.isEmpty() && seen.size() < 3000) {
            BlockPos f = q.poll();
            for (Direction d : Direction.Plane.HORIZONTAL) for (int dy = -1; dy <= 1; dy++) {
                BlockPos n = f.relative(d).above(dy); if (seen.containsKey(n) || !deck(level, n)) continue;
                seen.put(n.immutable(), 0); q.add(n.immutable());
            }
        }
        for (BlockPos f : seen.keySet()) for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos n = f.relative(d); if (seen.containsKey(n) || seen.containsKey(n.above()) || seen.containsKey(n.below())) continue; // (the deck goes on that way)
            if (ActionPath.structureBlock(level, n) || ActionPath.structureBlock(level, n.above())) continue; // (its own wall there: a shut end, not an open one; natural rock or air beyond is open, a tunnel joins it)
            int behind = 0; for (int k = 1; k <= 8 && seen.containsKey(f.relative(d.getOpposite(), k)); k++) behind++;
            if (behind < 6) continue;
            Direction across = d.getClockWise(); int w = 1;
            for (int k = 1; k <= 4 && seen.containsKey(f.relative(across, k)); k++) w++;
            for (int k = 1; k <= 4 && seen.containsKey(f.relative(across.getOpposite(), k)); k++) w++;
            if (w < 3 || w > 7) continue;
            // one end per stretch: its middle cell across
            int lo = 0, hi = 0; while (lo > -4 && seen.containsKey(f.relative(across, lo - 1))) lo--; while (hi < 4 && seen.containsKey(f.relative(across, hi + 1))) hi++;
            if ((lo + hi) / 2 != 0) continue;
            out.add(new End(f.immutable(), d, n.immutable()));
        }
        return out;
    }

    /** The ways into the fortress whose deck holds {@code to}: its seen ends (all, when none is seen yet), nearest first by the walk there and along the deck; the caller prices each by its planned route (538, 11-05: the nearest by a straight line from where he stood flipped north to east and back for 2 min, each end's way round passing the other). */
    /** His highway's end at a fortress's wall: the way in (end), the bricks still in its doorway (3 tall across the walkway, the user: "3 tall"), the segment. */
    public record Door(End end, List<BlockPos> bricks) {}
    private static final java.util.Set<BlockPos> DOOR_BRICKS = java.util.concurrent.ConcurrentHashMap.newKeySet(); // shortcut: one Claude per server; key by bot if a second ever builds
    public static boolean doorBrick(BlockPos p) { return DOOR_BRICKS.contains(p); }
    /**
     * The end of a highway of his that meets a fortress's wall, its doorway's inside the nearest to {@code near} (within 96), or null.
     * The user 20:10 11-07: "that would ideally be how he paths to the fortress": portal, his highway, the doorway, in.
     */
    public static Door highwayDoor(com.claudemod.entity.ClaudeEntity g, ServerLevel level, BlockPos near) {
        Door best = null; double bd = Double.MAX_VALUE;
        for (var sg : com.claudemod.entity.life.PortalMap.net(g).segments) { if (!com.claudemod.entity.life.PortalMap.NETHER.equals(sg.dim) || sg.a.equals(sg.b)) continue;
            for (int s = 0; s < 2; s++) { BlockPos e = s == 0 ? sg.a : sg.b, o = s == 0 ? sg.b : sg.a;
                if (!level.isLoaded(e) || e.distSqr(near) > 96 * 96) continue;
                int sign = sg.axis == Direction.Axis.X ? Integer.signum(e.getX() - o.getX()) : Integer.signum(e.getZ() - o.getZ()); if (sign == 0) continue;
                Direction out = Direction.fromAxisAndDirection(sg.axis, sign > 0 ? Direction.AxisDirection.POSITIVE : Direction.AxisDirection.NEGATIVE), right = out.getClockWise();
                int lo = -(sg.width / 2), hi = sg.width - 1 - sg.width / 2;
                for (int k = 1; k <= 4; k++) { BlockPos slab = e.relative(out, k); boolean wall = false;
                    for (int c = lo - 1; c <= hi + 1 && !wall; c++) for (int y = 1; y <= 3; y++) if (com.claudemod.entity.life.FortressScout.fortressBlock(level.getBlockState(slab.relative(right, c).above(y)))) { wall = true; break; }
                    if (!wall) continue;
                    List<BlockPos> bricks = new ArrayList<>();
                    for (int c = lo; c <= hi; c++) for (int y = 1; y <= 3; y++) { BlockPos q = slab.relative(right, c).above(y); var st = level.getBlockState(q); if (!st.getCollisionShape(level, q).isEmpty() && com.claudemod.entity.life.FortressScout.fortressBlock(st)) bricks.add(q.immutable()); }
                    BlockPos in = null; for (int j = 1; j <= 3 && in == null; j++) for (int dy = 0; dy <= 2 && in == null; dy++) { BlockPos f = slab.relative(out, j).above(dy); if (deck(level, f)) in = f.immutable(); }
                    if (in != null && in.distSqr(near) < bd) { bd = in.distSqr(near); best = new Door(new End(in, out.getOpposite(), slab.relative(out.getOpposite()).above().immutable()), bricks); }
                    break; } } }
        if (best != null) DOOR_BRICKS.addAll(best.bricks());
        return best;
    }

    public static List<End> entries(com.claudemod.entity.ClaudeEntity g, ServerLevel level, BlockPos from, BlockPos to) {
        BlockPos d0 = deckNear(level, to); if (d0 == null) return List.of();
        List<End> all = ends(level, d0); boolean any = false; for (End e : all) if (seen(g, e)) any = true;
        List<End> out = new java.util.ArrayList<>(); for (End e : all) if (!any || seen(g, e)) out.add(e);
        out.sort(java.util.Comparator.comparingDouble(e -> Math.sqrt(e.outside().distSqr(from)) + Math.sqrt(e.deck().distSqr(d0))));
        if (!out.isEmpty() && !any) com.claudemod.director.ClaudeLife.nookSay(g, "fortressEnd", "[Claude route] no end of the fortress at " + d0.toShortString() + " seen yet: heading for where the bridge he saw runs out, at " + out.get(0).deck().toShortString() + " (FortressEnds.entries)");
        return out.subList(0, Math.min(out.size(), (int) com.claudemod.entity.life.Weights.w("fortress.ends_priced", 3)));
    }

    /** The way out of the fortress he stands on toward {@code to}: the end nearest it, or null. */
    /** An end he's seen (its deck, or the way out of it) wins over one he hasn't; with none seen, the end of the bridge he saw (a player beside a fortress follows its bridge, never through its side). */
    static boolean seen(com.claudemod.entity.ClaudeEntity g, End e) { return com.claudemod.entity.life.FarSight.known(g, e.deck().getX(), e.deck().getY(), e.deck().getZ()) || com.claudemod.entity.life.FarSight.known(g, e.outside().getX(), e.outside().getY(), e.outside().getZ()); }

    public static End exit(com.claudemod.entity.ClaudeEntity g, ServerLevel level, BlockPos me, BlockPos to) {
        BlockPos d0 = deckNear(level, me); if (d0 == null) return null;
        End best = null; double bd = Double.MAX_VALUE;
        List<End> all = ends(level, d0); boolean any = false; for (End e : all) if (seen(g, e)) any = true;
        for (End e : all) { if (any && !seen(g, e)) continue; double d = e.outside().distSqr(to); if (d < bd) { bd = d; best = e; } }
        if (best != null && !any) com.claudemod.director.ClaudeLife.nookSay(g, "fortressEnd", "[Claude route] no end of the fortress at " + d0.toShortString() + " seen yet: heading for where the bridge he saw runs out, at " + best.deck().toShortString() + " (FortressEnds.exit)");
        return best;
    }
}
