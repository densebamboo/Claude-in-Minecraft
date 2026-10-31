package com.claudemod.entity.body;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.phys.Vec3;

/**
 * Finding and walking routes, for a player body. Minecraft's pathfinder only works for mobs, so routes are
 * worked out by a stand-in (a zombie that is never added to the world, standing where he stands, about his
 * size); then he walks them himself with player inputs, node by node, jumping up steps.
 * Same calls as the old mob navigation, so the rest of the code doesn't care which body he has.
 */
public final class PlayerNav {
    private final Player p;
    private Zombie probe;
    private Path path;
    private double speed = 1.0;
    private boolean jump;
    // progress watchdog: no closer to the next node for a while = stuck, drop the path (callers re-path)
    private int nodeIndex = -1;
    private double bestToNode = Double.MAX_VALUE;
    private int noProgressTicks;
    private int lastDoorClick = -100; // the last tick he clicked a door open on his way

    public PlayerNav(Player p) { this.p = p; }

    private Zombie probe() {
        if (probe == null || probe.level() != p.level()) {
            probe = new Zombie(p.level());
            var range = probe.getAttribute(Attributes.FOLLOW_RANGE);
            if (range != null) range.setBaseValue(100.0); // routes up to ~100 blocks
            probe.setPathfindingMalus(PathType.UNPASSABLE_RAIL, 0.0f);
            probe.setPathfindingMalus(PathType.DAMAGE_FIRE, -1.0f); // magma, fire, lava edges: never a route, however short (a player walks round)
            probe.setPathfindingMalus(PathType.DAMAGE_OTHER, -1.0f); // cactus, berry bushes, wither roses
            probe.setPathfindingMalus(PathType.DANGER_FIRE, 4.0f); // beside fire: dear, not forbidden
            probe.setPathfindingMalus(PathType.POWDER_SNOW, -1.0f);

            probe.getNavigation().setCanFloat(true); // he swims across water like a player
            if (probe.getNavigation() instanceof net.minecraft.world.entity.ai.navigation.GroundPathNavigation gn) { gn.setCanOpenDoors(true); gn.setCanPassDoors(true); } // a player opens doors on his way (the zombie probe can't: closed doors read as walls)
            probe.setPathfindingMalus(PathType.DOOR_WOOD_CLOSED, 0.0f);
            probe.setPathfindingMalus(PathType.DOOR_OPEN, 0.0f);
        }
        probe.setPathfindingMalus(PathType.DAMAGE_CAUTIOUS, 8.0f); // pointed dripstone: dear, not forbidden (a stalactite hanging into the cave under his floor walled off his own branch mouth; a player walks past it) (set here, not only at the probe's birth: the live one is already made)
        probe.moveTo(p.getX(), p.getY(), p.getZ(), p.getYRot(), 0);
        probe.setOnGround(true); // the pathfinder only plans from solid footing
        return probe;
    }

    @Nullable public Path createPath(BlockPos pos, int accuracy) { return probe().getNavigation().createPath(pos, accuracy); }
    @Nullable public Path createPath(Entity e, int accuracy) { return probe().getNavigation().createPath(e, accuracy); }

    public boolean moveTo(double x, double y, double z, double speed) { return moveTo(createPath(BlockPos.containing(x, y, z), 1), speed); }
    public boolean moveTo(Entity e, double speed) { return moveTo(createPath(e, 1), speed); }

    public boolean moveTo(@Nullable Path path, double speed) {
        this.path = path;
        this.speed = speed;
        nodeIndex = -1;
        if (path != null) { // a fresh path starts at the cell he's leaving: nodes he's already level with or past go, never a turn back to touch them (the 300-degree spins on his stairs, the user)
            Vec3 pos = p.position();
            while (path.getNextNodeIndex() + 1 < path.getNodeCount()) {
                Node n = path.getNextNode(), after = path.getNode(path.getNextNodeIndex() + 1);
                double dx = n.x + 0.5 - pos.x, dz = n.z + 0.5 - pos.z;
                boolean behind = dx * dx + dz * dz < 1.0 && Math.abs(n.y - Math.floor(pos.y + 0.01)) <= 1 && (-dx) * (after.x - n.x) + (-dz) * (after.z - n.z) >= 0;
                if (!behind) break;
                path.advance();
            }
        }
        return path != null;
    }

    public void stop() { path = null; }
    public boolean isDone() { return path == null || path.isDone(); }
    @Nullable public Path getPath() { return path; }
    public void setCanFloat(boolean canFloat) {}

    double speed() { return speed; }
    boolean wantsJump() { return jump; }
    /** Height of the node he's walking toward, or null when not on a route. */
    @Nullable Double targetY() { return path == null || path.isDone() ? null : (double) path.getNextNode().y; }

    /** This tick: the horizontal direction to walk along the route (null when not walking), and whether to jump. */
    @Nullable Vec3 steer() {
        jump = false;
        if (isDone()) { path = null; return null; }
        Vec3 pos = p.position();
        // reached the next node? (close enough sideways, about the same height) then on to the one after
        while (!path.isDone()) {
            Node n = path.getNextNode();
            double dx = n.x + 0.5 - pos.x, dz = n.z + 0.5 - pos.z;
            double r0 = p.isSprinting() ? 0.5 : 0.35; // (at a sprint he covers 0.28 a tick) // (at a sprint he covers 0.28 a tick: a 0.35 window was overshot and he turned back for it)
            boolean there = dx * dx + dz * dz < r0 * r0 && Math.abs(n.y - pos.y) < 1.2;
            // or already past it toward the one after (cutting a corner like a person)
            boolean passed = false;
            if (!there && path.getNextNodeIndex() + 1 < path.getNodeCount()) {
                Node after = path.getNode(path.getNextNodeIndex() + 1);
                passed = Math.abs(after.y - n.y) <= 1 && Math.abs(n.y - Math.floor(pos.y + 0.01)) <= 1 // (a hillside: the node one up or down from the next; flat-only made him turn back to touch each one, the 280-degree spins)
                        && pos.distanceToSqr(after.x + 0.5, pos.y, after.z + 0.5) < pos.distanceToSqr(n.x + 0.5, pos.y, n.z + 0.5)
                        && dx * dx + dz * dz < 1.0
                        && !dropOnLine(pos, after); // (never across the corner of a pit's mouth: the node is walked to first)
            }
            if (there || passed) path.advance(); else break;
        }
        if (path.isDone()) { path = null; return null; }

        Node n = path.getNextNode();
        // Walk like a person, not along the grid: head for the farthest node ahead he can walk to in a straight
        // line (flat, clear, solid ground the whole way). Nodes he cuts past count as reached.
        int far = path.getNextNodeIndex();
        for (int i = path.getNextNodeIndex() + 1; i < Math.min(path.getNodeCount(), path.getNextNodeIndex() + 10); i++) {
            Node m = path.getNode(i);
            if (m.y != n.y || n.y != Math.floor(pos.y + 0.01) || !straightWalk(pos, m)) break;
            far = i;
        }
        if (far > path.getNextNodeIndex()) {
            Node m = path.getNode(far);
            Vec3 toFar = new Vec3(m.x + 0.5 - pos.x, 0, m.z + 0.5 - pos.z);
            if (toFar.lengthSqr() < 0.6 * 0.6) path.setNextNodeIndex(far); // already there: skip the corners
            n = m;
        }
        double ax = 0.5, az = 0.5; // where in the node's cell he heads for: its middle, or the middle of what a door's panel leaves open
        { BlockPos nc = new BlockPos(n.x, n.y, n.z); var ns = p.level().getBlockState(nc);
          if (ns.getBlock() instanceof net.minecraft.world.level.block.DoorBlock || ns.getBlock() instanceof net.minecraft.world.level.block.FenceGateBlock) {
              var sh = ns.getCollisionShape(p.level(), nc);
              if (!sh.isEmpty()) { var b = sh.bounds(); // (an open door's panel down one side: the gap is 13/16 wide and he's 0.6, so the cell's middle left him 1/80 of a block; a hair off it he walked into the panel's end and hopped on the step for 8 s, the house's bed given up, stages 84/85)
                  if (b.maxX - b.minX < 0.5) ax = b.minX < 0.01 ? (b.maxX + 1) / 2 : b.minX / 2;
                  if (b.maxZ - b.minZ < 0.5) az = b.minZ < 0.01 ? (b.maxZ + 1) / 2 : b.minZ / 2; } } }
        double dx = n.x + ax - pos.x, dz = n.z + az - pos.z;
        double d = Math.sqrt(dx * dx + dz * dz);
        // stuck check
        if (path.getNextNodeIndex() != nodeIndex) { nodeIndex = path.getNextNodeIndex(); bestToNode = d; noProgressTicks = 0; }
        else if (d < bestToNode - 0.05) { bestToNode = d; noProgressTicks = 0; }
        else if (++noProgressTicks > 60) { path = null; return null; }
        // a closed wooden door or fence gate in his way: right-click it open, like a player, and walk on
        if (p instanceof net.minecraft.server.level.ServerPlayer sp && p.tickCount - lastDoorClick > 10) {
            BlockPos nb = new BlockPos(n.x, n.y, n.z), aheadB = BlockPos.containing(pos.x + dx / Math.max(d, 1e-3) * 0.8, pos.y, pos.z + dz / Math.max(d, 1e-3) * 0.8);
            for (BlockPos q : new BlockPos[] {aheadB, aheadB.above(), nb, nb.above()}) {
                var st = p.level().getBlockState(q);
                boolean closedDoor = st.getBlock() instanceof net.minecraft.world.level.block.DoorBlock && !st.getValue(net.minecraft.world.level.block.DoorBlock.OPEN) && !st.is(net.minecraft.world.level.block.Blocks.IRON_DOOR);
                boolean closedGate = st.getBlock() instanceof net.minecraft.world.level.block.FenceGateBlock && !st.getValue(net.minecraft.world.level.block.FenceGateBlock.OPEN);
                if (!(closedDoor || closedGate) || q.distToCenterSqr(p.getEyePosition()) > 4.5 * 4.5) continue;
                lastDoorClick = p.tickCount;
                Vec3 hit = Vec3.atCenterOf(q);
                sp.gameMode.useItemOn(sp, sp.serverLevel(), net.minecraft.world.item.ItemStack.EMPTY, net.minecraft.world.InteractionHand.MAIN_HAND,
                        new net.minecraft.world.phys.BlockHitResult(hit, net.minecraft.core.Direction.getNearest(pos.x - hit.x, 0, pos.z - hit.z), q, false));
                sp.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                break;
            }
        }
        // a step up ahead (or walking into a wall on the way to it): jump, the way a player taps space
        if (p.onGround() && (n.y > pos.y + 0.5 || (p.horizontalCollision && n.y >= Math.floor(pos.y)))) jump = true;
        if (d < 1e-3) return Vec3.ZERO;
        return new Vec3(dx / d, 0, dz / d);
    }

    /** A drop of 2+ under the straight line from here to that node (the corner of a hole's mouth). */
    private boolean dropOnLine(Vec3 from, Node to) {
        var level = p.level(); int fy = (int) Math.floor(from.y + 0.01);
        double dx = to.x + 0.5 - from.x, dz = to.z + 0.5 - from.z, len = Math.sqrt(dx * dx + dz * dz);
        for (double s = 0.25; s < len; s += 0.25) {
            BlockPos q = BlockPos.containing(from.x + dx * s / len, fy - 1, from.z + dz * s / len);
            if (level.getBlockState(q).getCollisionShape(level, q).isEmpty() && level.getBlockState(q.below()).getCollisionShape(level, q.below()).isEmpty() && level.getFluidState(q).isEmpty()) return true;
        }
        return false;
    }
    /**
     * Can he walk straight from here to that node's center? Checks along the line (and his shoulders either
     * side): room for his body at the same level, and solid ground under every step (no hidden drops).
     */
    private boolean straightWalk(Vec3 from, Node to) {
        var level = p.level();
        Vec3 target = new Vec3(to.x + 0.5, to.y, to.z + 0.5);
        Vec3 line = target.subtract(from.x, target.y, from.z).multiply(1, 0, 1);
        double len = line.length();
        if (len < 1e-3) return true;
        Vec3 dir = line.scale(1 / len);
        Vec3 side = new Vec3(-dir.z, 0, dir.x).scale(0.3); // half his width
        for (double s = 0.5; s <= len; s += 0.5) {
            for (int k = -1; k <= 1; k++) {
                double x = from.x + dir.x * s + side.x * k, z = from.z + dir.z * s + side.z * k;
                BlockPos feet = BlockPos.containing(x, to.y, z);
                if (!level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()) return false;
                if (!level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()) return false;
                BlockPos ground = feet.below();
                if (!level.getBlockState(ground).isFaceSturdy(level, ground, net.minecraft.core.Direction.UP)) return false;
                if (!level.getFluidState(feet).isEmpty()) return false; // water/lava: follow the route instead
            }
        }
        return true;
    }
}
