package com.claudemod.entity.life;

import com.claudemod.entity.ClaudeEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Why a walk, a dig or a placement stopped getting anywhere (the user 11-04: "fix causes, not counters"). What's in his way right
 * now, read off the world: the step his body refused, the cell ahead (a block, unbreakable or not, the tool for it; water or lava;
 * a mob in it), no path at all, the spot out of reach. UNKNOWN when none of these: that stall is a bug to find, never a quiet skip.
 */
public final class Stall {
    private Stall() {}
    public record Cause(Attempts.Reason reason, String detail, java.util.UUID mob) {
        public String say() { return reason + (detail.isEmpty() ? "" : " (" + detail + ")"); }
    }

    /** The tool for a NO_TOOL block ("deepslate_iron_ore at x, y, z"): the pickaxe tier it needs, or an axe or shovel; null if none applies. */
    public static String toolFor(ClaudeEntity g, String detail) {
        if (detail == null || detail.isEmpty()) return null;
        var b = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.withDefaultNamespace(detail.split(" ")[0]));
        var bs = b.defaultBlockState();
        if (bs.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_PICKAXE)) return bs.is(net.minecraft.tags.BlockTags.NEEDS_DIAMOND_TOOL) ? "diamond_pickaxe" : bs.is(net.minecraft.tags.BlockTags.NEEDS_IRON_TOOL) ? "iron_pickaxe" : "stone_pickaxe";
        if (bs.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_AXE)) return "stone_axe";
        if (bs.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_SHOVEL)) return "stone_shovel";
        return null;
    }

    /** What's in the way of him getting to target (within: how near counts as there). */
    public static Cause diagnose(ClaudeEntity g, BlockPos target, double within) {
        if (!(g.level() instanceof ServerLevel lv)) return new Cause(Attempts.Reason.UNKNOWN, "no level", null);
        if (com.claudemod.entity.body.Controls.refusedLately(g, 20)) return new Cause(Attempts.Reason.BODY_REFUSED, "his body refused the path's step (2+ up, or lava)", null);
        BlockPos feet = g.blockPosition();
        var path = g.getNavigation().getPath();
        BlockPos next = path != null && !path.isDone() ? path.getNextNodePos() : null;
        if (next == null) { // no path: the cell one step toward the target
            double dx = target.getX() - feet.getX(), dz = target.getZ() - feet.getZ();
            next = Math.abs(dx) >= Math.abs(dz) ? feet.offset((int) Math.signum(dx), 0, 0) : feet.offset(0, 0, (int) Math.signum(dz));
            if (next.equals(feet)) next = feet.offset(0, (int) Math.signum(target.getY() - feet.getY()), 0);
        }
        for (BlockPos q : new BlockPos[] {next, next.above()}) {
            var mobs = lv.getEntitiesOfClass(net.minecraft.world.entity.Mob.class, new net.minecraft.world.phys.AABB(q).inflate(0.4), m -> m.isAlive());
            if (!mobs.isEmpty()) { var m = mobs.get(0); return new Cause(Attempts.Reason.BLOCKED_MOB, m.getType().getDescription().getString() + " at " + m.blockPosition().toShortString(), m.getUUID()); }
            var fs = lv.getFluidState(q);
            if (!fs.isEmpty()) return new Cause(Attempts.Reason.BLOCKED_FLUID, (fs.is(net.minecraft.tags.FluidTags.LAVA) ? "lava" : "water") + " at " + q.toShortString(), null);
            var bs = lv.getBlockState(q);
            if (bs.getCollisionShape(lv, q).isEmpty()) continue;
            String nm = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(bs.getBlock()).getPath() + " at " + q.toShortString();
            if (bs.getDestroySpeed(lv, q) < 0) return new Cause(Attempts.Reason.UNBREAKABLE, nm, null);
            if (bs.hasBlockEntity()) return new Cause(Attempts.Reason.CONTAINER, nm, null);
            if (bs.requiresCorrectToolForDrops() && !g.getInventory().items.stream().anyMatch(s -> s.isCorrectToolForDrops(bs))) return new Cause(Attempts.Reason.NO_TOOL, nm, null);
            return new Cause(Attempts.Reason.BLOCKED_BLOCK, nm, null);
        }
        if (path != null && path.getEndNode() != null && Math.sqrt(path.getEndNode().asBlockPos().distSqr(target)) > within + 1) { BlockPos end = path.getEndNode().asBlockPos(); int up = target.getY() - end.getY(); // (the walker's closest cell: live 21:14 its path ended under his feet, a log 5 up, and this read his own cell as "nothing in the way")
            return new Cause(Attempts.Reason.NO_PATH, "the path stops at " + end.toShortString() + ", " + (int) Math.sqrt(end.distSqr(target)) + " short" + (up > 1 ? " (" + up + " up)" : ""), null); }
        if (path == null || path.isDone() && Math.sqrt(feet.distSqr(target)) > within + 1) return new Cause(Attempts.Reason.NO_PATH, "no path from " + feet.toShortString(), null);
        if (!lv.getBlockState(target).getCollisionShape(lv, target).isEmpty() && g.getEyePosition().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(target)) > g.blockInteractionRange() + 0.5) return new Cause(Attempts.Reason.OUT_OF_REACH, String.format("%.1f off", g.getEyePosition().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(target))), null);
        return new Cause(Attempts.Reason.UNKNOWN, "path on, nothing in the way at " + next.toShortString() + ", no headway", null);
    }
}
