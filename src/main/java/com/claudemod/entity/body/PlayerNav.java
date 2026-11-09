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
            probe.setPathfindingMalus(PathType.DANGER_FIRE, 8.0f); // beside fire or lava: dear, not forbidden (8, was 4: a route along a lava-sea shore beat a short way round, and a knock or a slip on it burned him)
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

    /** Why this tick's steer went where it did, and the path's nodes round the next one (the loop line reads them: a corner shuffle in his mine, live 02:40 11-08, would not repro, stage 987). */
    public String steerWhy = "";
    public String around() {
        if (path == null) return "no path";
        StringBuilder sb = new StringBuilder(); int i0 = path.getNextNodeIndex();
        for (int i = Math.max(0, i0 - 3); i < Math.min(path.getNodeCount(), i0 + 4); i++) { Node m = path.getNode(i); sb.append(i == i0 ? "[" : "").append(m.x).append(",").append(m.y).append(",").append(m.z).append(i == i0 ? "]" : "").append(" "); }
        return sb.append("(").append(i0).append(" of ").append(path.getNodeCount()).append(")").toString();
    }
    /** This tick: the horizontal direction to walk along the route (null when not walking), and whether to jump. */
    /** The node before the next one, a flat step from his cell with a clear line to it: made the next again. */
    private boolean backToSkipped(Vec3 pos, BlockPos here) {
        Node pn = path.getNode(path.getNextNodeIndex() - 1);
        if (pn.y != here.getY() || pn.x == here.getX() && pn.z == here.getZ() || Math.abs(pn.x - here.getX()) + Math.abs(pn.z - here.getZ()) > 1) return false;
        double fx = pn.x + 0.5 - pos.x, fz = pn.z + 0.5 - pos.z, fl = Math.sqrt(fx * fx + fz * fz); if (fl < 1e-3) return false;
        for (double s0 = 0.2; s0 < fl + 0.1; s0 += 0.2) if (!p.level().noCollision(p, p.getBoundingBox().move(fx / fl * Math.min(s0, fl), 0.01, fz / fl * Math.min(s0, fl)))) return false;
        path.setNextNodeIndex(path.getNextNodeIndex() - 1); return true;
    }
    @Nullable Vec3 steer() {
        jump = false; steerWhy = "";
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
        { // a flat step whose straight line catches a block's corner (his box off the cell's middle, over the next row): back to the middle of his cell first, like a player stepping round (16:20 11-07: pressed into a birch trunk's corner 65 s, stage 1010)
            double ex = n.x + ax - pos.x, ez = n.z + az - pos.z, el = Math.sqrt(ex * ex + ez * ez);
            BlockPos here = BlockPos.containing(pos.x, pos.y + 0.01, pos.z); double cx = here.getX() + 0.5 - pos.x, cz = here.getZ() + 0.5 - pos.z;
            if (el > 0.5 && n.y == here.getY() && !p.level().noCollision(p, p.getBoundingBox().move(ex / el * 0.4, 0.01, ez / el * 0.4))) {
                Node n0 = path.getNextNode(); double fx = n0.x + 0.5 - pos.x, fz = n0.z + 0.5 - pos.z, fl = Math.sqrt(fx * fx + fz * fz);
                boolean clear0 = n0 != n && n0.y == here.getY() && fl > 1e-3; for (double s = 0.2; clear0 && s < fl + 0.1; s += 0.2) clear0 = p.level().noCollision(p, p.getBoundingBox().move(fx / fl * Math.min(s, fl), 0.01, fz / fl * Math.min(s, fl))); // (the whole line to it: a corner past the first step is the one stage 1010 pressed into)
                if (clear0) { n = n0; ax = 0.5; az = 0.5; } // the far node's line clips a block (a leaf at head height over the diagonal): the next node on the grid first, never back to the cell's middle and out at the diagonal again, a 50 s spin (D2D review 87, live 20:08 11-07; stage 1016)
                else if (path.getNextNodeIndex() > 0 && backToSkipped(pos, here)) { n = path.getNextNode(); ax = 0.5; az = 0.5; steerWhy = "back to the node he'd passed, " + n.x + "," + n.y + "," + n.z + ": the line past it clips a block"; } // (marked passed while he was knocked about, then pushed back to his cell's far corner: the bend's own node first; live 06:14 11-09, review 110, 18 s at a tunnel bend; stage 1240)
                else if (cx * cx + cz * cz > 0.1 * 0.1 && p.level().noCollision(p, p.getBoundingBox().move(cx, 0.01, cz))) { steerWhy = "back to his cell's middle: the line to " + n.x + "," + n.y + "," + n.z + " clips a block"; return new Vec3(cx, 0, cz).normalize(); }
                else steerWhy = "on at " + n.x + "," + n.y + "," + n.z + " though its line clips a block (at his cell's middle, the next node not clear either)";
            }
        }
        { int fy = (int) Math.floor(pos.y + 0.01); // a step up ahead and no room to jump where he stands (a block 2 over his floor): to a cell beside the step with room over it, and up from there, as a player does (live 12:23 11-09: 18 s of hops to +0.2 under one, stage 1264)
          if (p.onGround() && n.y == fy + 1 && !p.level().noCollision(p, p.getBoundingBox().move(0, 1.02, 0))) {
              BlockPos off = null; double od = Double.MAX_VALUE;
              for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                  BlockPos c = new BlockPos(n.x, fy, n.z).relative(dir);
                  if (p.level().getBlockState(c.below()).getCollisionShape(p.level(), c.below()).isEmpty()) continue; // ground to take off from
                  var box = p.getBoundingBox().move(c.getX() + 0.5 - pos.x, c.getY() - pos.y, c.getZ() + 0.5 - pos.z);
                  if (!p.level().noCollision(p, box) || !p.level().noCollision(p, box.move(0, 1.02, 0))) continue; // room to stand and to jump
                  double cd = c.distToCenterSqr(pos.x, c.getY() + 0.5, pos.z); if (cd < od) { od = cd; off = c; }
              }
              if (off != null && od <= 2.5 * 2.5) { double tx = off.getX() + 0.5 - pos.x, tz = off.getZ() + 0.5 - pos.z, tl = Math.sqrt(tx * tx + tz * tz);
                  steerWhy = "to " + off.getX() + "," + off.getY() + "," + off.getZ() + " to take off: no room to jump up to " + n.x + "," + n.y + "," + n.z + " where he stands";
                  return tl < 1e-3 ? Vec3.ZERO : new Vec3(tx / tl, 0, tz / tl); }
          } }
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
        if (!steerWhy.startsWith("on at")) steerWhy = "to " + n.x + "," + n.y + "," + n.z + (far > path.getNextNodeIndex() ? " (cut ahead from the next node)" : "");
        return new Vec3(dx / d, 0, dz / d);
    }

    /** A drop of 2+ under the straight line from here to that node (the corner of a hole's mouth). */
    private boolean dropOnLine(Vec3 from, Node to) {
        var level = p.level(); int fy = (int) Math.floor(from.y + 0.01);
        double dx = to.x + 0.5 - from.x, dz = to.z + 0.5 - from.z, len = Math.sqrt(dx * dx + dz * dz);
        for (double s = 0.25; s < len; s += 0.25) {
            for (int k = -1; k <= 1; k++) { // lava on the line at his feet or under them, his shoulders too: the corner cut goes through it, not round (a cut across a shore cell)
                double sx = -dz / len * 0.3 * k, sz = dx / len * 0.3 * k;
                BlockPos f = BlockPos.containing(from.x + dx * s / len + sx, fy, from.z + dz * s / len + sz);
                if (level.getFluidState(f).is(net.minecraft.tags.FluidTags.LAVA) || level.getFluidState(f.below()).is(net.minecraft.tags.FluidTags.LAVA) && level.getBlockState(f.below()).getCollisionShape(level, f.below()).isEmpty()) return true;
            }
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
