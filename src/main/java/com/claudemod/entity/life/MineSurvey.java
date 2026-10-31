package com.claudemod.entity.life;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Reading a mine like a player who walks into one: which way the main tunnel runs and where its open end is,
 * how wide and tall it is, how far apart the side branches are and how long, and where the torches go.
 * From that he can carry on digging in the same pattern, whoever started it.
 */
public final class MineSurvey {
    private MineSurvey() {}

    /**
     * What he learned. face: where to stand to dig the next slice (on the edge the width grows from);
     * dir: which way to dig; sinceBranch / sinceTorch: blocks dug since the last branch / torch at that end.
     */
    public record Result(BlockPos face, Direction dir, int width, int height, int branchEvery, int branchLength,
                         int torchEvery, String torchSide, int sinceBranch, int sinceTorch, int length) {}

    private static boolean cell(ServerLevel level, BlockPos p) {
        return level.getBlockState(p).getCollisionShape(level, p).isEmpty()
                && level.getBlockState(p.above()).getCollisionShape(level, p.above()).isEmpty()
                && level.getBlockState(p.below()).isFaceSturdy(level, p.below(), Direction.UP)
                && level.getFluidState(p).isEmpty();
    }

    /** Surveys the tunnel around `start`; `from` is where he came from (he continues at the end away from it). Null if it isn't a mine. */
    @Nullable
    public static Result scan(ServerLevel level, BlockPos start, BlockPos from) { return scan(level, start, from, false); }

    /** preferNear: continue the end nearest `from` (they led him to the end they want worked) instead of the far one. */
    @Nullable
    public static Result scan(ServerLevel level, BlockPos start, BlockPos from, boolean preferNear) {
        // 1. a spot of tunnel floor near the start
        BlockPos seed = null;
        for (BlockPos p : BlockPos.withinManhattan(start, 3, 2, 3)) if (cell(level, p)) { seed = p.immutable(); break; }
        if (seed == null) return null;
        // 2. all the tunnel floor connected to it at that level (a strip mine is flat)
        Set<BlockPos> cells = new HashSet<>();
        ArrayDeque<BlockPos> todo = new ArrayDeque<>(List.of(seed));
        cells.add(seed);
        while (!todo.isEmpty() && cells.size() < 1500) {
            BlockPos c = todo.poll();
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos n = c.relative(d);
                if (n.distManhattan(seed) > 80 || cells.contains(n) || !cell(level, n)) continue;
                cells.add(n);
                todo.add(n);
            }
        }
        if (cells.size() < 10) return null;
        // 3. the main tunnel: the longest straight run along X and along Z; the one with side branches off it wins
        //    (early on a branch can be longer than the main tunnel, but branches don't have branches)
        Result pick = null;
        int pickBranches = -1, pickLen = -1;
        for (Direction.Axis axis : new Direction.Axis[] {Direction.Axis.X, Direction.Axis.Z}) {
            Run best = null;
            for (BlockPos c : cells) {
                if (cells.contains(c.relative(axis, -1))) continue; // only start counting at the beginning of a run
                int len = 0;
                while (cells.contains(c.relative(axis, len))) len++;
                if (best == null || len > best.len) best = new Run(axis, c, len);
            }
            if (best == null || best.len < 6) continue; // a room or a cave along this way, not a tunnel
            int[] branchCount = new int[1];
            Result r = analyze(level, cells, best, from, preferNear, branchCount);
            if (branchCount[0] > pickBranches || (branchCount[0] == pickBranches && best.len > pickLen)) {
                pick = r; pickBranches = branchCount[0]; pickLen = best.len;
            }
        }
        return pick;
    }

    private static Result analyze(ServerLevel level, Set<BlockPos> cells, Run best, BlockPos from, boolean preferNear, int[] branchCountOut) {
        Direction.Axis axis = best.axis;
        Direction.Axis cross = axis == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X;
        // 4. width: parallel rows beside it that run most of the same length
        int lo = 0, hi = 0;
        while (lo > -4 && rowCover(cells, best, cross, lo - 1) >= 0.6) lo--;
        while (hi < 4 && rowCover(cells, best, cross, hi + 1) >= 0.6) hi++;
        int width = hi - lo + 1;
        // 5. height, at the middle of the run
        BlockPos mid = best.start.relative(axis, best.len / 2);
        int height = 0;
        while (height < 5 && level.getBlockState(mid.above(height)).getCollisionShape(level, mid.above(height)).isEmpty()) height++;
        height = Math.max(2, Math.min(4, height));
        // 6. which end to keep digging: the one farther from where he came in
        BlockPos endA = best.start, endB = best.start.relative(axis, best.len - 1);
        boolean forwardIsB = preferNear ? endB.distSqr(from) <= endA.distSqr(from) : endB.distSqr(from) >= endA.distSqr(from);
        Direction dir = Direction.fromAxisAndDirection(axis, forwardIsB ? Direction.AxisDirection.POSITIVE : Direction.AxisDirection.NEGATIVE);
        BlockPos end = forwardIsB ? endB : endA;
        // stand on the edge row the width grows from (new slices are dug out toward dir's clockwise side)
        Direction grow = dir.getClockWise();
        int edgeOffset = grow.getAxisDirection() == Direction.AxisDirection.POSITIVE ? lo : hi;
        BlockPos face = end.relative(cross, edgeOffset);
        // 7. side branches: perpendicular runs off the main tunnel, at least 3 long
        List<Integer> branchAt = new ArrayList<>(), branchLen = new ArrayList<>();
        for (int i = 0; i < best.len; i++) {
            BlockPos on = best.start.relative(axis, i);
            int longest = 0;
            for (int side : new int[] {lo - 1, hi + 1}) {
                int step = side < 0 ? -1 : 1, n = 0;
                while (n < 40 && cells.contains(on.relative(cross, side + step * n))) n++;
                longest = Math.max(longest, n);
            }
            if (longest >= 3) { branchAt.add(i); branchLen.add(longest); }
        }
        // merge adjacent hits (a 2-wide branch) into one
        List<Integer> branches = new ArrayList<>();
        for (int i : branchAt) if (branches.isEmpty() || i - branches.get(branches.size() - 1) > 1) branches.add(i);
        branchCountOut[0] = branches.size();
        int branchEvery = branches.size() >= 2 ? median(diffs(branches)) : branches.isEmpty() ? 0 : 4; // 0: no branches
        int branchLength = branchLen.isEmpty() ? 16 : Math.max(3, median(branchLen));
        int endIndex = forwardIsB ? best.len - 1 : 0;
        int lastBranch = branches.isEmpty() ? -1 : forwardIsB ? branches.get(branches.size() - 1) : branches.get(0);
        int sinceBranch = lastBranch < 0 ? 0 : Math.abs(endIndex - lastBranch);
        // 8. torches along the main tunnel: spacing, and which wall (or the floor)
        List<Integer> torchAt = new ArrayList<>();
        int left = 0, right = 0, floor = 0;
        for (int i = 0; i < best.len; i++) {
            for (int off = lo - 1; off <= hi + 1; off++) {
                for (int dy = 0; dy < height; dy++) {
                    BlockPos p = best.start.relative(axis, i).relative(cross, off).above(dy);
                    BlockState s = level.getBlockState(p);
                    if (s.is(Blocks.WALL_TORCH)) {
                        Direction facing = s.getValue(WallTorchBlock.FACING); // points away from its wall
                        if (facing == dir.getClockWise()) left++; else if (facing == dir.getCounterClockWise()) right++;
                        torchAt.add(i);
                    } else if (s.is(Blocks.TORCH)) { floor++; torchAt.add(i); }
                }
            }
        }
        Collections.sort(torchAt);
        int torchEvery = torchAt.size() >= 2 ? Math.max(3, median(diffs(torchAt))) : 8;
        String torchSide = floor > left && floor > right ? "floor" : right > left ? "right" : "left";
        int lastTorch = torchAt.isEmpty() ? -1 : forwardIsB ? torchAt.get(torchAt.size() - 1) : torchAt.get(0);
        int sinceTorch = lastTorch < 0 ? torchEvery - 1 : Math.abs(endIndex - lastTorch);
        return new Result(face, dir, width, height, branchEvery, branchLength, torchEvery, torchSide, sinceBranch, sinceTorch, best.len);
    }

    private record Run(Direction.Axis axis, BlockPos start, int len) {}

    /** How much of the main run's length this parallel row also has open. */
    private static double rowCover(Set<BlockPos> cells, Run run, Direction.Axis cross, int offset) {
        int open = 0;
        for (int i = 0; i < run.len; i++) if (cells.contains(run.start.relative(run.axis, i).relative(cross, offset))) open++;
        return (double) open / run.len;
    }

    private static List<Integer> diffs(List<Integer> xs) {
        List<Integer> d = new ArrayList<>();
        for (int i = 1; i < xs.size(); i++) d.add(xs.get(i) - xs.get(i - 1));
        return d;
    }

    private static int median(List<Integer> xs) {
        List<Integer> s = new ArrayList<>(xs);
        Collections.sort(s);
        return s.get(s.size() / 2);
    }
}
