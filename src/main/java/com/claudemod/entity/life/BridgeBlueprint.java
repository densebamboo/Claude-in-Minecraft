package com.claudemod.entity.life;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A Nether bridge from a portal to a destination, as one plan, the way MineBlueprint is his mine (the user, 2026-10-31: "it's
 * similar to the mine. you pick a destination and bridge between that and the portal").
 *
 * Route: two straight legs. Leg 1 leaves the portal along the way it faces (the normal of its plane), to the destination's
 * coordinate on that axis; one 90 degree pivot there; leg 2 runs the other axis to the destination.
 * Cross-section (their demo): a floor as wide as the portal frame it leaves (its outer width: 4 for a default frame, 5 for
 * their 5x5 highway portals), a 1-block rail on each edge outside it at floor + 1, one material all through; the
 * walkway is 3 high. At the pivot the floor is the full corner square, the outer rail runs round it, the inner rails stop.
 * Height: level from the portal's floor, the destination's height made up in one staircase (a rise or a drop of 1 a row), in
 * the middle of the longer leg and never within {@link #MARGIN} rows of the portal, the pivot or the destination.
 * Rock: a row whose walkway or rails meet rock is a tunnel row: its rail columns become walls 3 high and a roof goes over,
 * the same material.
 * This class plans; it reads the level only to tell rock from air. Building it is a skill on top (not yet).
 */
public final class BridgeBlueprint {
    public enum Role { FLOOR, RAIL, WALK, WALL, ROOF }
    public static final int HEADROOM = 3, MARGIN = 8;
    public final int width; private final int lo, hi; // the floor's columns: lo..hi to the right of the centre line (4 wide: -2..1, 5 wide: -2..2)
    /** One row of the bridge: its centre cell (column 0, at floor height), the way it runs, its leg (1 or 2), its index along the leg and whether it's a stair row. */
    public record Row(BlockPos centre, Direction dir, int leg, int k, boolean stair, boolean tunnel) {}

    public final BlockPos portal, dest; public final Direction leg1, leg2; public final BlockPos pivot;
    public final List<Row> rows = new ArrayList<>();
    public final Map<BlockPos, Role> roles = new LinkedHashMap<>();
    public int stairFrom = -1, stairLeg = 0; public String why = "";
    /** The first pass (the user: "2 wide without rails first, then build the railing and widen when needed"): no rails on open rows; tunnel rows keep their walls and roof. */
    public boolean rails = true;

    private BridgeBlueprint(BlockPos portal, BlockPos dest, Direction leg1, Direction leg2, BlockPos pivot, int width) { this.portal = portal.immutable(); this.dest = dest.immutable(); this.leg1 = leg1; this.leg2 = leg2; this.pivot = pivot.immutable(); this.width = width; this.lo = -(width / 2); this.hi = width - 1 - width / 2; }

    /** The frame's outer width along its plane, read off the world: the portal cells' run plus the obsidian (or any frame block) on each end; 4 for a default frame. -1 if no portal there. */
    public static int frameWidth(ServerLevel level, BlockPos portalCell) {
        var s = level.getBlockState(portalCell); if (!s.is(Blocks.NETHER_PORTAL)) return -1;
        Direction along = s.getValue(NetherPortalBlock.AXIS) == Direction.Axis.X ? Direction.EAST : Direction.SOUTH;
        int n = 1; for (Direction d : new Direction[] {along, along.getOpposite()}) { BlockPos q = portalCell.relative(d); for (int k = 0; k < 23 && level.getBlockState(q).is(Blocks.NETHER_PORTAL); k++) { n++; q = q.relative(d); } }
        return n + 2;
    }

    /** The way a portal faces: the horizontal axis its plane doesn't span; null if no portal block there. */
    public static Direction.Axis facingAxis(ServerLevel level, BlockPos portalCell) {
        var s = level.getBlockState(portalCell);
        if (!s.is(Blocks.NETHER_PORTAL)) return null;
        return s.getValue(NetherPortalBlock.AXIS) == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X;
    }

    private int lateralMax(Direction rowDir, Direction along) { return rowDir.getClockWise().getAxis() == along.getAxis() ? (rowDir.getClockWise() == along ? hi : -lo) : 0; } // how far a row's columns (lo..hi to its right) reach along another direction
    private int lateralMin(Direction rowDir, Direction along) { return rowDir.getClockWise().getAxis() == along.getAxis() ? (rowDir.getClockWise() == along ? lo : -hi) : 0; }

    /**
     * The plan from the portal (its bottom portal cell, he stands there; the floor is the frame's bottom level, a block lower)
     * along the axis it faces, to the destination (a foot cell). Null if the destination is on the portal's own plane line.
     */
    private static int facingFirst(Direction.Axis facing, BlockPos p, BlockPos dest) { return Integer.signum(facing == Direction.Axis.X ? dest.getX() - p.getX() : dest.getZ() - p.getZ()); }
    public static BridgeBlueprint plan(ServerLevel level, BlockPos portalCell, Direction.Axis facing, BlockPos dest, int width) { return plan(level, portalCell, facing, dest, width, true); }
    public static BridgeBlueprint plan(ServerLevel level, BlockPos portalCell, Direction.Axis facing, BlockPos dest, int width, boolean rails) { return plan(level, portalCell, facing, dest, width, rails, null); }
    /**
     * As above; {@code pin} {leg, row, steps}: the staircase of the highway underway, kept as it was planned. The pricing reads the
     * world as it stands (rows laid, ground loaded), and that changes as he builds: re-priced on each resume, the staircase moved
     * from leg 2 row 122 to leg 1 row 254 and back to leg 2 row 140, and he laid a floor over 60 rows of his road a block up, then
     * dug it out again (live 02:58-03:34 11-07, the user: "he placed blocks over ALL the road and now he's breaking the middle").
     * A pin for another step count (the destination's height read again) is no pin.
     */
    public static BridgeBlueprint plan(ServerLevel level, BlockPos portalCell, Direction.Axis facing, BlockPos dest, int width, boolean rails, int[] pin) {
        Direction.Axis other = facing == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X;
        BlockPos portalSpot = portalCell;
        if (level != null && level.getBlockState(portalCell).is(Blocks.NETHER_PORTAL)) { // the floor lined up with the frame's outer edges: its columns span the frame, not the portal cell he named
            Direction along = Direction.fromAxisAndDirection(other, Direction.AxisDirection.POSITIVE);
            BlockPos a = portalCell, z = portalCell;
            for (int k = 0; k < 23 && level.getBlockState(a.relative(along.getOpposite())).is(Blocks.NETHER_PORTAL); k++) a = a.relative(along.getOpposite());
            for (int k = 0; k < 23 && level.getBlockState(z.relative(along)).is(Blocks.NETHER_PORTAL); k++) z = z.relative(along);
            int lo0 = -(width / 2), cells = (int) Math.round(Math.sqrt(a.distSqr(z))) + 1;
            int dirIn = facingFirst(facing, portalCell, dest); Direction l1 = Direction.fromAxisAndDirection(facing, dirIn > 0 ? Direction.AxisDirection.POSITIVE : Direction.AxisDirection.NEGATIVE);
            Direction right = l1.getClockWise();
            BlockPos cellLo = right == along ? a : z; // the portal cell on the side his columns start (lo)
            int off = width >= cells + 2 ? -1 : Math.max(0, (cells - width) / 2); // (as wide as the frame: lined up with its outer edges; narrower, a first pass: centred on the portal's opening)
            portalCell = cellLo.relative(right, off - lo0);
        }
        int d1 = facing == Direction.Axis.X ? dest.getX() - portalCell.getX() : dest.getZ() - portalCell.getZ();
        int d2 = other == Direction.Axis.X ? dest.getX() - portalCell.getX() : dest.getZ() - portalCell.getZ();
        if (d1 == 0) return null; // (straight out of the portal's side: no leg along its face)
        Direction l1 = Direction.fromAxisAndDirection(facing, d1 > 0 ? Direction.AxisDirection.POSITIVE : Direction.AxisDirection.NEGATIVE);
        Direction l2 = d2 == 0 ? null : Direction.fromAxisAndDirection(other, d2 > 0 ? Direction.AxisDirection.POSITIVE : Direction.AxisDirection.NEGATIVE);
        BlockPos floor0 = portalCell.below();
        BlockPos pivot = floor0.relative(l1, Math.abs(d1));
        BridgeBlueprint b = new BridgeBlueprint(portalSpot, dest, l1, l2, pivot, Math.max(1, width)); b.rails = rails;
        int n1 = Math.abs(d1) + (l2 == null ? 0 : b.lateralMax(l2, l1)); // (leg 1 runs on over the corner square)
        int k2from = l2 == null ? 0 : b.lateralMin(l1, l2), n2 = Math.abs(d2);
        // the staircase: in the middle of the longer leg, MARGIN rows clear of the portal, the pivot and the destination
        int dy = dest.getY() - portalCell.getY(), steps = Math.abs(dy);
        int free1 = Math.abs(d1) - 2 * MARGIN, free2 = l2 == null ? -1 : n2 - 2 * MARGIN;
        int leg = steps == 0 ? 0 : free2 >= free1 && free2 >= steps ? 2 : free1 >= steps ? 1 : free2 >= free1 ? 2 : 1;
        int legLen = leg == 1 ? Math.abs(d1) : n2;
        int s0 = leg == 0 ? -1 : Math.max(0, (legLen - steps) / 2);
        boolean pinned = false;
        if (pin != null && pin.length == 3 && pin[2] == steps) { int pl = pin[0], ps = pin[1];
            if (steps == 0 || pl == 0 || pl == 1 && ps >= 0 && ps + steps <= Math.abs(d1) || pl == 2 && l2 != null && ps >= k2from - 1 && ps + steps <= n2) { leg = steps == 0 ? 0 : pl; s0 = leg == 0 ? -1 : ps; legLen = leg == 1 ? Math.abs(d1) : n2; pinned = true; b.why += "staircase kept where it was planned (leg " + leg + " row " + s0 + "); "; } }
        if (leg != 0 && !pinned) { // where the staircase goes, priced: every row at the height it would have, lava to seal, rock to dig, air to bridge where the ground is loaded, the lava sea dear (live 15:48: the middle of leg 1 put 500 rows at the lava sea, the user: "going down to lava level is counterproductive"); the spec's margins as a price
            List<BlockPos> cs = new ArrayList<>(); List<Direction> ds = new ArrayList<>();
            for (int k = 1; k <= n1; k++) { cs.add(floor0.relative(l1, k)); ds.add(l1); }
            if (l2 != null) for (int k = k2from; k <= n2; k++) { BlockPos c = pivot.relative(l2, k); cs.add(new BlockPos(c.getX(), floor0.getY(), c.getZ())); ds.add(l2); }
            int n = cs.size(); double[] pa = new double[n + 1], pb = new double[n + 1];
            for (int i = 0; i < n; i++) { pa[i + 1] = pa[i] + rowCost(level, cs.get(i), ds.get(i), b.lo, b.hi); pb[i + 1] = pb[i] + rowCost(level, cs.get(i).above(dy), ds.get(i), b.lo, b.hi); }
            double near = Weights.w("highway.stair_near_end_s", 120), bestC = Double.MAX_VALUE, midC = Double.NaN; int bestLeg = leg, bestS = s0, bestBare = 0;
            double bareS = Weights.w("highway.stair_no_face_s", 10000); int sign0 = Integer.signum(dy);
            for (int lg = 1; lg <= (l2 == null ? 1 : 2); lg++) {
                int kFirst = lg == 1 ? 0 : k2from - 1, kLast = (lg == 1 ? Math.abs(d1) : n2) - steps; // s0 range: stair rows s0+1 .. s0+steps inside the leg
                for (int s = kFirst; s <= kLast; s++) {
                    int j = lg == 1 ? s : n1 + (s + 1 - k2from); if (j < 0 || j + steps > n) continue;
                    double cost = pa[j] + (pa[j + steps] - pa[j] + pb[j + steps] - pb[j]) / 2 + (pb[n] - pb[j + steps]);
                    int fromStart = lg == 1 ? s : s + 1 - k2from, toEnd = (lg == 1 ? Math.abs(d1) : n2) - (s + steps);
                    if (fromStart < MARGIN || toEnd < MARGIN) cost += near;
                    cost += Math.abs(s - (lg == 1 ? Math.abs(d1) : n2) / 2.0) * 1e-6; // (a tie: the leg's middle, as before)
                    int bare = 0; if (dy < 0) for (int i = j; i < j + steps; i++) if (!stepFace(level, cs.get(i).above(sign0 * (i - j + 1)), ds.get(i), b.lo, b.hi)) bare++;
                    cost += bare * bareS; // a step down over open air: nothing under it, beside it or ahead to set it against (the floor's underside can't be seen from above): it can't be laid (stage 922: 4 steps over the void, 4 holes)
                    if (lg == leg && s == s0) midC = cost;
                    if (cost < bestC) { bestC = cost; bestLeg = lg; bestS = s; bestBare = bare; }
                }
            }
            if (bestBare > 0) { b.why += "no stretch where a step down has something under or beside it to be set against (" + bestBare + " of " + steps + " steps over open air at best): level all the way, the " + steps + " down made up where there's ground; "; com.claudemod.ClaudeMod.LOGGER.info("[Claude highway] {} steps down to {}: none of the legs has rock or ground under {} steps in a row, so the bridge stays level; the height is made up at the end (BridgeBlueprint.plan)", steps, dest.toShortString(), steps); leg = 0; s0 = -1; bestC = Double.MAX_VALUE; }
            if (bestC < Double.MAX_VALUE) { if (bestLeg != leg || bestS != s0) b.why += "staircase priced to leg " + bestLeg + " row " + bestS + " (~" + Math.round(bestC) + " s of rows, the middle ~" + (Double.isNaN(midC) ? "-" : String.valueOf(Math.round(midC))) + " s); "; leg = bestLeg; s0 = bestS; legLen = leg == 1 ? Math.abs(d1) : n2; }
        }
        if (leg != 0 && (leg == 1 ? free1 : free2) < steps) b.why += "the staircase is closer than " + MARGIN + " rows to an end (leg " + leg + " is " + legLen + " long for " + steps + " steps); ";
        b.stairLeg = leg; b.stairFrom = s0;
        int sign = Integer.signum(dy);
        for (int k = 1; k <= n1; k++) { // (row 0 is the portal's own plane, its frame: the bridge starts in front of it)
            int rise = leg == 1 ? Math.max(0, Math.min(steps, k - s0)) : 0;
            boolean stair = leg == 1 && k > s0 && k <= s0 + steps;
            b.rows.add(new Row(floor0.relative(l1, k).above(sign * rise), l1, 1, k, stair, false));
        }
        int y1 = leg == 1 ? floor0.getY() + dy : floor0.getY();
        if (l2 != null) for (int k = k2from; k <= n2; k++) {
            int rise = leg == 2 ? Math.max(0, Math.min(steps, k - s0)) : 0;
            boolean stair = leg == 2 && k > s0 && k <= s0 + steps;
            BlockPos c = pivot.relative(l2, k); c = new BlockPos(c.getX(), y1 + sign * rise, c.getZ());
            b.rows.add(new Row(c, l2, 2, k, stair, false));
        }
        b.lay(level);
        b.why += width + " wide" + (rails ? "" : ", no rails yet") + ", leg 1 " + l1.getName() + " " + Math.abs(d1) + (l2 == null ? "" : ", pivot at " + pivot.toShortString() + ", leg 2 " + l2.getName() + " " + n2) + (leg == 0 ? ", level all the way" : ", " + steps + " step(s) " + (dy > 0 ? "up" : "down") + " in leg " + leg + " from row " + s0);
        return b;
    }

    /** A step's floor at {@code floor} can be laid: one of its cells has something solid under it, beside it outside the floor, or ahead or behind at its height (a face he can see from the row above); unloaded ground reads as yes (the builder reads it there). */
    static boolean stepFace(ServerLevel level, BlockPos floor, Direction dir, int lo, int hi) {
        if (level == null || !level.hasChunkAt(floor)) return true;
        Direction right = dir.getClockWise();
        for (int o = lo; o <= hi; o++) { BlockPos f = floor.relative(right, o);
            java.util.List<BlockPos> n = new ArrayList<>(List.of(f.below(), f.relative(dir), f.relative(dir.getOpposite())));
            if (o == lo) n.add(f.relative(right, -1)); if (o == hi) n.add(f.relative(right, 1));
            for (BlockPos q : n) if (!level.getBlockState(q).getCollisionShape(level, q).isEmpty()) return true; }
        return false;
    }

    /** Seconds a row costs at that floor: lava round it to seal, rock in its walkway to dig, air under its floor to bridge (loaded ground only), and the lava sea's own price (walking at lava level: ghasts, falls, plugs). */
    static double rowCost(ServerLevel level, BlockPos floor, Direction dir, int lo, int hi) {
        double s = 0;
        if (level != null && level.dimension() == net.minecraft.world.level.Level.NETHER && floor.getY() <= Weights.w("highway.lava_sea_floor_y", 34)) s += Weights.w("highway.lava_sea_row_s", 30);
        if (level == null || !level.hasChunkAt(floor)) return s;
        Direction right = dir.getClockWise(); double seal = Weights.w("highway.seal_s", 2), dig = Weights.w("highway.dig_s", 0.7), bridge = Weights.w("highway.bridge_s", 0.5);
        for (int o = lo - 1; o <= hi + 1; o++) for (int y = 0; y <= 4; y++) { BlockPos q = floor.relative(right, o).above(y);
            if (level.getFluidState(q).is(net.minecraft.tags.FluidTags.LAVA)) { s += seal; continue; }
            boolean in = o >= lo && o <= hi, solid = !level.getBlockState(q).getCollisionShape(level, q).isEmpty();
            if (in && y == 0 && !solid) s += bridge; else if (in && y >= 1 && y <= 3 && solid) s += dig; }
        return s;
    }

    /** Every cell's role, from the rows: floor, rails (outside the floor), walkway air; rows meeting rock get walls and a roof. */
    private void lay(ServerLevel level) {
        roles.clear();
        Set<BlockPos> floor = new HashSet<>();
        for (Row r : rows) for (int o = lo; o <= hi; o++) floor.add(r.centre().relative(r.dir().getClockWise(), o).immutable());
        List<Row> laid = new ArrayList<>();
        for (Row r : rows) {
            Direction right = r.dir().getClockWise();
            List<BlockPos> rails = new ArrayList<>();
            for (int o : new int[] {lo - 1, hi + 1}) { BlockPos q = r.centre().relative(right, o); if (!floor.contains(q) && !floor.contains(q.below()) && !floor.contains(q.above())) rails.add(q.above().immutable()); } // (a rail over another row's floor would block the turn, or a step)
            boolean rock = false;
            if (level != null && level.hasChunkAt(r.centre())) { // (a row out in unloaded ground isn't read: no chunk loads for a plan 700 rows long; the builder reads each row as it gets there)
                for (int o = lo; o <= hi && !rock; o++) for (int y = 1; y <= HEADROOM && !rock; y++) { BlockPos q = r.centre().relative(right, o).above(y); if (!level.getBlockState(q).getCollisionShape(level, q).isEmpty()) rock = true; }
                for (BlockPos q : rails) if (!rock && !level.getBlockState(q).getCollisionShape(level, q).isEmpty()) rock = true;
            }
            laid.add(new Row(r.centre(), r.dir(), r.leg(), r.k(), r.stair(), rock));
            for (int o = lo; o <= hi; o++) {
                BlockPos f = r.centre().relative(right, o).immutable();
                roles.put(f, Role.FLOOR);
                for (int y = 1; y <= HEADROOM; y++) roles.putIfAbsent(f.above(y), Role.WALK);
                if (rock) roles.put(f.above(HEADROOM + 1), Role.ROOF);
            }
            for (BlockPos q : rails) {
                if (roles.get(q) == Role.FLOOR || roles.get(q) == Role.WALK || !rock && !this.rails) continue;
                roles.put(q, rock ? Role.WALL : Role.RAIL);
                if (rock) { for (int y = 1; y < HEADROOM; y++) if (roles.get(q.above(y)) != Role.WALK) roles.put(q.above(y), Role.WALL); roles.put(q.above(HEADROOM), Role.ROOF); }
            }
        }
        // the corner post: where leg 1's outer rail ends and leg 2's begins, diagonal to both
        if (leg2 != null && this.rails) {
            Row last1 = null, first2 = null; for (Row r : rows) { if (r.leg() == 1) last1 = r; if (r.leg() == 2 && first2 == null) first2 = r; }
            if (last1 != null && first2 != null) {
                Direction r1 = leg1.getClockWise(); int out1 = r1 == leg2 ? lo - 1 : hi + 1; // (leg 1's rail on the side away from leg 2)
                BlockPos post = last1.centre().relative(r1, out1).relative(leg1, 1).above();
                if (!roles.containsKey(post)) roles.put(post.immutable(), Role.RAIL);
            }
        }
        rows.clear(); rows.addAll(laid);
    }

    /** The rail columns, to the right of a row's centre line (the tunnel's walls in a tunnel row). */
    public int[] railCols() { return new int[] {lo - 1, hi + 1}; }
    public int lo() { return lo; } public int hi() { return hi; }
    public int staircases() { int n = 0; boolean in = false; for (Row r : rows) { if (r.stair() && !in) n++; in = r.stair(); } return n; }
    public List<Row> leg(int l) { List<Row> out = new ArrayList<>(); for (Row r : rows) if (r.leg() == l) out.add(r); return out; }
    @Override public String toString() { return "bridge from " + portal.toShortString() + " to " + dest.toShortString() + ": " + why + " (" + rows.size() + " rows, " + roles.size() + " cells, " + rows.stream().filter(Row::tunnel).count() + " tunnel rows)"; }
}
