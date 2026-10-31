package com.claudemod.entity.life;

import java.util.Set;

import com.claudemod.entity.ClaudeEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Placing a block so it comes out the way a plan wants it (a stair's facing and half, a door's facing and hinge, a log's axis, a
 * slab's half): the game decides those from the click, his look and the face he clicks, exactly as for any player. So he works out,
 * before clicking, which face and which spot on it give that state (the game's own placement rule, run on the click he'd make),
 * and only clicks a face his eyes reach first; if no click from where he stands does it, the nearest spot he can stand that does.
 * Mind of an AI (the exact outcome of each click), body of a player (only faces he sees, in reach, his look the way he faces).
 */
public final class PlaceAim {
    private PlaceAim() {}

    /** A click: the block clicked, its face, the point on it. */
    public record Click(BlockPos clicked, Direction face, Vec3 hit) {}

    /** The properties a click decides: these must come out as planned. The rest follow the neighbours (a stair's shape, a fence's arms). */
    static final Set<Property<?>> CLICK = Set.of(BlockStateProperties.FACING, BlockStateProperties.HORIZONTAL_FACING, BlockStateProperties.FACING_HOPPER,
            BlockStateProperties.AXIS, BlockStateProperties.HORIZONTAL_AXIS, BlockStateProperties.HALF, BlockStateProperties.SLAB_TYPE,
            BlockStateProperties.DOOR_HINGE, BlockStateProperties.ATTACH_FACE, BlockStateProperties.ROTATION_16, BlockStateProperties.ORIENTATION,
            BlockStateProperties.DOUBLE_BLOCK_HALF, BlockStateProperties.BED_PART, BlockStateProperties.VERTICAL_DIRECTION);

    /** Anything about this block that the click decides. */
    public static boolean directional(BlockState want) {
        for (Property<?> p : want.getProperties()) if (CLICK.contains(p)) return true;
        return false;
    }

    /** The same block, with every click-decided property as planned. */
    public static boolean same(BlockState got, BlockState want) {
        if (got == null || want == null) return got == want;
        if (want.isAir()) return got.isAir() || got.canBeReplaced() && got.getFluidState().isEmpty() && !got.blocksMotion();
        if (got.getBlock() != want.getBlock()) return false;
        for (Property<?> p : want.getProperties()) if (CLICK.contains(p) && got.hasProperty(p) && !got.getValue(p).equals(want.getValue(p))) return false;
        return true;
    }

    /** A bottom slab where a double one is planned: a second click on it finishes it. */
    public static boolean growsInto(BlockState now, BlockState want) {
        return want.hasProperty(BlockStateProperties.SLAB_TYPE) && want.getValue(BlockStateProperties.SLAB_TYPE) == net.minecraft.world.level.block.state.properties.SlabType.DOUBLE
                && now.getBlock() == want.getBlock() && now.getValue(BlockStateProperties.SLAB_TYPE) != net.minecraft.world.level.block.state.properties.SlabType.DOUBLE;
    }

    static final double REACH = 4.4;
    static final double[] GRID = {0, -0.35, 0.35};

    /** A click from this eye that gives {@code want} at {@code pos}: a face he sees first, in reach, the game's rule giving that state. Null: none from here. */
    public static Click from(ClaudeEntity g, ServerLevel level, Vec3 eye, BlockPos pos, ItemStack stack, BlockState want) {
        if (!(stack.getItem() instanceof BlockItem)) return null;
        for (int k = -1; k < 6; k++) {
            Direction d = k < 0 ? null : Direction.values()[k];
            BlockPos clicked = d == null ? pos : pos.relative(d); // (the cell itself: a second slab on the first)
            BlockState cs = level.getBlockState(clicked);
            if (cs.isAir() || !cs.getFluidState().isEmpty()) continue;
            if (d == null && !growsInto(cs, want)) continue;
            if (d != null && cs.canBeReplaced()) continue; // (grass in the cell beside: no face to click)
            Direction[] faces = d == null ? Direction.values() : new Direction[] {d.getOpposite()};
            for (Direction face : faces) {
                Vec3 c = Vec3.atCenterOf(clicked).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
                Direction.Axis ax = face.getAxis();
                Vec3 u = ax == Direction.Axis.X ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0), v = ax == Direction.Axis.Z ? new Vec3(0, 1, 0) : new Vec3(0, 0, 1);
                for (double a : GRID) for (double b : GRID) {
                    Vec3 hit = c.add(u.scale(a)).add(v.scale(b));
                    if (eye.distanceTo(hit) > REACH) continue;
                    if (!firstSeen(g, level, eye, clicked, face, hit)) continue;
                    BlockState got = predict(g, level, eye, pos, stack, clicked, face, hit);
                    if (got != null && (same(got, want) || growsInto(got, want))) return new Click(clicked.immutable(), face, hit);
                }
            }
        }
        return null;
    }

    /** The nearest spot he can stand (feet cell) from where some click gives {@code want}; null: nowhere near. */
    public static BlockPos standFor(ClaudeEntity g, ServerLevel level, BlockPos pos, ItemStack stack, BlockState want) {
        BlockPos best = null; double bd = Double.MAX_VALUE; BlockPos me = g.blockPosition();
        boolean tall = want.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF);
        for (BlockPos q : BlockPos.betweenClosed(pos.offset(-4, -3, -4), pos.offset(4, 2, 4))) {
            BlockPos sp = q.immutable();
            if (!standable(level, sp)) continue;
            net.minecraft.world.phys.AABB body = new net.minecraft.world.phys.AABB(sp.getX() + 0.2, sp.getY(), sp.getZ() + 0.2, sp.getX() + 0.8, sp.getY() + 1.8, sp.getZ() + 0.8);
            if (body.intersects(new net.minecraft.world.phys.AABB(pos)) || tall && body.intersects(new net.minecraft.world.phys.AABB(pos.above()))) continue; // (never where the block goes)
            double dd = sp.distSqr(me); if (dd >= bd) continue;
            Vec3 eye = Vec3.atBottomCenterOf(sp).add(0, g.getEyeHeight(), 0);
            if (eye.distanceTo(Vec3.atCenterOf(pos)) > REACH + 0.9) continue;
            boolean all = true; // from anywhere in that cell (a step ends within a little of its middle): a click that gives it (a spot on the line where his facing flips is no spot)
            for (double[] off : new double[][] {{0, 0}, {0.2, 0.2}, {-0.2, 0.2}, {0.2, -0.2}, {-0.2, -0.2}}) if (from(g, level, eye.add(off[0], 0, off[1]), pos, stack, want) == null) { all = false; break; }
            if (all) { best = sp; bd = dd; }
        }
        return best;
    }

    public static boolean standable(ServerLevel level, BlockPos sp) {
        return level.getBlockState(sp).getCollisionShape(level, sp).isEmpty() && level.getBlockState(sp.above()).getCollisionShape(level, sp.above()).isEmpty()
                && !level.getBlockState(sp.below()).getCollisionShape(level, sp.below()).isEmpty() && level.getFluidState(sp).isEmpty();
    }

    /** The face is the first thing his eyes meet on the way to that point (what the crosshair would be on). */
    static boolean firstSeen(ClaudeEntity g, ServerLevel level, Vec3 eye, BlockPos clicked, Direction face, Vec3 hit) {
        Vec3 in = hit.subtract(face.getStepX() * 0.05, face.getStepY() * 0.05, face.getStepZ() * 0.05);
        BlockHitResult r = level.clip(new ClipContext(eye, in, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, g));
        return r.getType() == HitResult.Type.BLOCK && r.getBlockPos().equals(clicked) && r.getDirection() == face;
    }

    /** What the game would put at pos for this click, his look along eye->hit (the rule each block runs for any player); null: nothing there. */
    static BlockState predict(ClaudeEntity g, ServerLevel level, Vec3 eye, BlockPos pos, ItemStack stack, BlockPos clicked, Direction face, Vec3 hit) {
        double dx = hit.x - eye.x, dy = hit.y - eye.y, dz = hit.z - eye.z;
        float yaw = Mth.wrapDegrees((float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0f), pitch = Mth.clamp((float) -(Mth.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * Mth.RAD_TO_DEG), -90f, 90f); // (Controls.Look's own angles: the click faces exactly this way)
        float y0 = g.getYRot(), x0 = g.getXRot(), h0 = g.getYHeadRot(); boolean s0 = g.isShiftKeyDown();
        try {
            g.setYRot(yaw); g.setXRot(pitch); g.setYHeadRot(yaw);
            g.setShiftKeyDown(com.claudemod.entity.ClaudeEntity.usableBlock(level, clicked)); // (the real click sneaks against a door or chest)
            BlockPlaceContext ctx = new BlockPlaceContext(g, InteractionHand.MAIN_HAND, stack, new BlockHitResult(hit, face, clicked, false));
            BlockItem bi = (BlockItem) stack.getItem();
            ctx = bi.updatePlacementContext(ctx);
            if (ctx == null || !ctx.canPlace() || !ctx.getClickedPos().equals(pos)) return null;
            BlockState s = bi.getBlock().getStateForPlacement(ctx);
            return s == null || !s.canSurvive(level, pos) ? null : s;
        } catch (RuntimeException e) {
            return null;
        } finally { g.setYRot(y0); g.setXRot(x0); g.setYHeadRot(h0); g.setShiftKeyDown(s0); }
    }
}
