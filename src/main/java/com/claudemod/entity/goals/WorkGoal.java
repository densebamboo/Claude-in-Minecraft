package com.claudemod.entity.goals;

import java.util.EnumSet;
import java.util.List;

import com.claudemod.entity.ClaudeEntity;
import com.claudemod.entity.life.Task;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * WORK: Claude plays the game. Runs the entity's task queue one step at a time, with the same
 * reach, arm swings, crack animation and sounds a player would make.
 */
public class WorkGoal extends Goal {
    private static final java.util.Map<java.util.UUID, BlockPos> stayCell = new java.util.HashMap<>(); // the cell he was told to stay on

    private static final double REACH = 4.0; // (stand spots within 3.7 of the target; a wider search (4.2) left stair roofs unplaced in the stairs test, 4 of 5)
    private static final int STUCK_LIMIT = 160; // ~8s without getting closer: give up on this step

    private final ClaudeEntity g;
    private Task current;
    private int ticks;
    private static java.util.Map<BlockPos, Integer> spineWalkTick; // when the stairs-walk rule last fired for a spot (static: fields added live start null)
    private static java.util.Map<BlockPos, Integer> spineWalkTick() { if (spineWalkTick == null) spineWalkTick = new java.util.HashMap<>(); return spineWalkTick; }
    public int taskAge() { return ticks; }
    public int stuckCount() { return stuck; }
    private int stuck;
    private Task stepInFor; private int stepInAt; // the walk he's stepping into the cell beside for, and from which of its ticks
    private double lastDist = Double.MAX_VALUE;
    private float breakProgress;

    public WorkGoal(ClaudeEntity g) {
        this.g = g;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return g.currentRoll() == ClaudeEntity.Roll.WORK && g.isAlive();
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void stop() {
        clearCrack();
        current = null;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    private boolean surfacing;

    /** Nearest spot to breathe: straight up through the water first, else any open air pocket close by. */
    private BlockPos breathingSpot() {
        var level = g.level();
        BlockPos eye = BlockPos.containing(g.getEyePosition());
        for (int dy = 0; dy <= 16; dy++) {
            BlockPos q = eye.above(dy);
            if (!level.getFluidState(q).isEmpty()) continue;
            if (level.getBlockState(q).getCollisionShape(level, q).isEmpty()) return q.below(); // feet one below the air: head out of the water
            break; // a ceiling over the water: look around instead
        }
        // a roof over him (an overhang, a cave): the nearest column close by with open water all the way up to air, and he swims across under it
        // Every air spot close by, nearest first; the one he takes is one he can get to: a straight swim with nothing in the way, or a
        // route (an air pocket behind a wall had him swimming into the wall for 20 s and drowning beside it)
        java.util.List<BlockPos> cands = new java.util.ArrayList<>(); java.util.List<Double> dists = new java.util.ArrayList<>();
        for (int dx = -6; dx <= 6; dx++) for (int dz = -6; dz <= 6; dz++) { // a roof over him (an overhang, a cave): a column close by with open water all the way up to air
            BlockPos col = eye.offset(dx, 0, dz);
            if (!level.getFluidState(col).is(net.minecraft.tags.FluidTags.WATER)) continue;
            for (int dy = 1; dy <= 16; dy++) {
                BlockPos q = col.above(dy);
                if (!level.getFluidState(q).isEmpty()) continue;
                if (level.getBlockState(q).getCollisionShape(level, q).isEmpty()) { cands.add(q.below().immutable()); dists.add((double) (dx * dx + dz * dz)); }
                break;
            }
        }
        // a flooded tunnel: the air is back along it (his stairs up out of the water), not above him; an open cell with its head out of the water, within 8 (he dug up through the roof underwater, 5x slow, and drowned three blocks from air)
        for (BlockPos q : BlockPos.betweenClosed(eye.offset(-8, -6, -8), eye.offset(8, 8, 8))) { // (down too: his mine's dry walkway under the flooded spot is the way out; he floated at the top and drowned with it below him)
            if (!level.getFluidState(q).isEmpty() || !level.getBlockState(q).getCollisionShape(level, q).isEmpty()) continue;
            BlockPos under = q.below();
            if (level.getFluidState(under).isEmpty() && level.getBlockState(under).getCollisionShape(level, under).isEmpty()) continue; // midair: not somewhere to stand
            cands.add(under.immutable()); dists.add(q.distSqr(eye) + (com.claudemod.director.ClaudeLife.isMineCell(g, under) || com.claudemod.director.ClaudeLife.isMineCell(g, q) ? 500 : 1000)); // (after the open columns; his own mine before any other pocket)
        }
        boolean[] used = new boolean[cands.size()]; int routes = 0;
        for (int pass = 0; pass < cands.size() && pass < 40; pass++) { // nearest first (ponytail: a pick-the-min loop, no comparator: no new lambdas in a class the live game reloads)
            int i = -1; for (int j = 0; j < cands.size(); j++) if (!used[j] && (i < 0 || dists.get(j) < dists.get(i))) i = j;
            used[i] = true;
            BlockPos c = cands.get(i);
            var line = level.clip(new net.minecraft.world.level.ClipContext(g.getEyePosition(), Vec3.atCenterOf(c.above()), net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, g));
            if (line.getType() == net.minecraft.world.phys.HitResult.Type.MISS) return c; // a straight swim
            if (routes++ < 4) { var p = g.getNavigation().createPath(c, 0); if (p != null && p.canReach()) return c; } // round a corner (a few tries a tick at most)
        }
        // nothing open within reach: a player digs the roof out over his head (he has a few seconds of air for it)
        BlockPos roof = eye.above();
        BlockState rs = level.getBlockState(roof);
        if (level.getFluidState(roof.above()).is(net.minecraft.tags.FluidTags.WATER) && !cands.isEmpty()) { int i = 0; for (int j = 1; j < cands.size(); j++) if (dists.get(j) < dists.get(i)) i = j; com.claudemod.ClaudeMod.LOGGER.info("[Claude life] water over the roof at {}: back toward the air at {} instead of digging up into it", g.blockPosition().toShortString(), cands.get(i).toShortString()); return cands.get(i); } // (digging up into more water drowned him at 50, 18, 82: the air was back along his tunnel)
        if (!rs.getCollisionShape(level, roof).isEmpty() && rs.getDestroySpeed(level, roof) >= 0 && !rs.hasBlockEntity() && !g.tasks.contains(new Task.Break(roof))) {
            g.tasks.addFirst(new Task.Break(roof.immutable()));
            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] under a roof in the water at {}: digging up for air", g.blockPosition().toShortString());
        }
        return eye.above(2);
    }

    private int fireSaid = -1000;
    private final java.util.Map<BlockPos, Integer> hopsTried = new java.util.HashMap<>();
    private int bowAimTick; /* the last tick shootAt aimed: a draw nothing aims is let down */
    /** The hazard reflexes, first every tick in any roll: fire his box touches, air running low, lava. True: one acted this tick
     *  (everything else waits). WorkGoal.tick runs it first; ClaudeEntity.brainTick runs it before FollowGoal (stage 489). */
    public static int fireballsHit; // (stages read it)
    public boolean reflexes() {
        try { com.claudemod.entity.life.Atlas.tick(g); } catch (Exception e) { com.claudemod.ClaudeMod.LOGGER.warn("[Claude atlas] tick: {}", e.toString()); }
        try { com.claudemod.entity.life.Watchdog.tick(g); } catch (RuntimeException e) { com.claudemod.ClaudeMod.LOGGER.warn("[Claude watchdog] tick threw", e); } // (the every-tick detectors: any state)
        if (g.level() instanceof ServerLevel fbl) for (var fb : fbl.getEntitiesOfClass(net.minecraft.world.entity.projectile.LargeFireball.class, g.getBoundingBox().inflate(5.0))) { // a ghast's fireball coming at him: hit it back, the hazard layer's first move in any state (the user, 13:00 World 15: "he never hit fireballs back at the ghast"; the old reflex sat in ClaudeLife.tick behind its early returns)
            Vec3 to = g.getEyePosition().subtract(fb.position());
            if (fb.getDeltaMovement().dot(to) <= 0 || !g.hasLineOfSight(fb)) continue;
            g.getLookControl().setLookAt(fb); g.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, fb.position());
            if (to.length() <= 3.0) { g.attack(fb); g.swing(net.minecraft.world.InteractionHand.MAIN_HAND); fireballsHit++; com.claudemod.ClaudeMod.LOGGER.info("[Claude life] a ghast fireball at {}, {} off: hit it back (WorkGoal.reflexes)", fb.blockPosition().toShortString(), String.format("%.1f", to.length())); }
            break;
        }
        if (!g.isInLava() && g.level() instanceof ServerLevel flv && BlockPos.betweenClosedStream(g.getBoundingBox().deflate(0.001)).anyMatch(p -> flv.getBlockState(p).is(net.minecraft.tags.BlockTags.FIRE))) { /* fire first, like air: live 00:12 he stood in Nether fire 27 hits while a stalled walk kept him there, 20 -> dead; only the blaze fight stepped off fire */
            BlockPos ff = BlockPos.containing(g.getX(), g.getY() + 0.2, g.getZ()), to = null; double best = Double.MAX_VALUE;
            if (g.tickCount % 4 == 0) { BlockPos fb = BlockPos.betweenClosedStream(g.getBoundingBox().deflate(0.001)).filter(p -> flv.getBlockState(p).is(net.minecraft.tags.BlockTags.FIRE)).map(BlockPos::immutable).findFirst().orElse(null); /* the user: "he doesnt know how to put out fire": one punch puts a fire block out, as for any player (a click every 4 ticks) */
                if (fb != null) { g.getLookControl().setLookAt(fb.getX() + 0.5, fb.getY() + 0.1, fb.getZ() + 0.5); g.swing(net.minecraft.world.InteractionHand.MAIN_HAND); g.gameMode.destroyBlock(fb); if (g.tickCount - fireSaid > 60) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] in fire at {}: punched out the fire at {} (WorkGoal.tick)", ff.toShortString(), fb.toShortString()); } }
            for (int dy = 0; dy >= -1; dy--) for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) { BlockPos q = ff.offset(dx, dy, dz); double dq = q.distToCenterSqr(g.position()); if (dq < best && com.claudemod.entity.life.FortressFight.stand(flv, q)) { best = dq; to = q; } }
            if (to != null) {
                g.getNavigation().stop(); g.setSprinting(true); g.getMoveControl().setWantedPosition(to.getX() + 0.5, to.getY(), to.getZ() + 0.5, 1.3);
                if (g.tickCount - fireSaid > 60) { fireSaid = g.tickCount; com.claudemod.ClaudeMod.LOGGER.info("[Claude life] in fire at {}: off it to {} first (WorkGoal.tick)", ff.toShortString(), to.toShortString()); }
                return true;
            }
            if (g.tickCount - fireSaid > 60) { fireSaid = g.tickCount; com.claudemod.ClaudeMod.LOGGER.info("[Claude life] in fire at {}: no fire-free spot within 2 to step to, the job's own way out (WorkGoal.tick)", ff.toShortString()); }
        }
        // Air first, like a player watching his bubbles: underwater and getting low, everything waits while he comes up to
        // breathe (and he stays up till he's got his breath back).
        boolean underwater = g.isEyeInFluid(net.minecraft.tags.FluidTags.WATER);
        if ((underwater && g.getAirSupply() < 120) || (surfacing && g.getAirSupply() < 280 && g.isInWater())) {
            if (!surfacing) {
                surfacing = true;
                if (current != null) { clearCrack(); g.tasks.addFirst(current); current = null; g.currentTask = null; }
                com.claudemod.ClaudeMod.LOGGER.info("[Claude life] low on air ({}) at {}: coming up to breathe", g.getAirSupply(), g.blockPosition().toShortString());
            }
            BlockPos spot = breathingSpot();
            BlockPos roofOverEyes = BlockPos.containing(g.getEyePosition()).above();
            if (g.tasks.peek() instanceof Task.Break rb && rb.pos().equals(roofOverEyes) && current == null) { // the roof: dug through below, this tick (returning here every tick left the dig queued and him drowning under it)
                g.getNavigation().stop(); g.getJumpControl().jump();
            } else {
                var line = g.level().clip(new net.minecraft.world.level.ClipContext(g.getEyePosition(), Vec3.atCenterOf(spot.above()), net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, g));
                if (line.getType() != net.minecraft.world.phys.HitResult.Type.MISS && g.tickCount % 10 == 0) g.getNavigation().moveTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, 1.0); // round a corner: the route, not a swim into the wall
                else if (line.getType() == net.minecraft.world.phys.HitResult.Type.MISS) { g.getNavigation().stop(); g.getMoveControl().setWantedPosition(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, 1.0); }
                if (spot.getY() >= g.getBlockY()) g.getJumpControl().jump(); // swim up only when the air is up: air below means letting himself sink to it (holding jump kept him floating at the top with his mine's air under him)
                g.getLookControl().setLookAt(spot.getX() + 0.5, spot.getY() + 1.5, spot.getZ() + 0.5);
                return true;
            }
        }
        surfacing = false;
        // In lava: out first, everything else waits (he stood 3 s in a lava flow finishing a Place and died, 09:02)
        if (!g.isInLava() && g.onGround() && g.tickCount % 5 == 0 && g.level().getFluidState(g.blockPosition()).isEmpty()) { BlockPos f0 = g.blockPosition().immutable(); if (!f0.equals(dryCells.peekLast())) { dryCells.addLast(f0); if (dryCells.size() > 12) dryCells.pollFirst(); } } // (the cells he stood dry on lately: the way he came in, known good footing)
        if (g.isInLava()) {
            if (current != null) { clearCrack(); g.tasks.addFirst(current); current = null; g.currentTask = null; }
            var lv = g.level(); BlockPos me = g.blockPosition(), out = null; double bd = Double.MAX_VALUE;
            for (BlockPos q : BlockPos.betweenClosed(me.offset(-3, -1, -3), me.offset(3, 1, 3))) {
                if (!lv.getFluidState(q).isEmpty() || !lv.getFluidState(q.above()).isEmpty()) continue;
                if (!lv.getBlockState(q).getCollisionShape(lv, q).isEmpty() || !lv.getBlockState(q.above()).getCollisionShape(lv, q.above()).isEmpty()) continue;
                if (!lv.getBlockState(q.below()).isFaceSturdy(lv, q.below(), Direction.UP)) continue;
                if (lavaOutBad.containsKey(q) && g.tickCount - lavaOutBad.get(q) < 20 * 4) continue; // (a step he couldn't make just now: another way out, never the same failing step till he burns: live 06:53 World 15)
                double d = q.distSqr(me) + (dryCells.contains(q) ? -6 : 0) + (q.getY() < me.getY() ? 8 : 0) + (lv.clip(new net.minecraft.world.level.ClipContext(g.getEyePosition(), Vec3.atCenterOf(q), net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, g)).getType() == net.minecraft.world.phys.HitResult.Type.MISS ? 0 : 50); // (a wall between: last resort)
                if (d >= 50 && q.distSqr(me) > 2) continue; // (behind a wall and not beside him: no way out at all, the wall dig below is; 1.5 s lost walking at one in a sealed pocket)
                if (d < bd) { bd = d; out = q.immutable(); }
            }
            if (out != null && !out.equals(lavaOut)) { lavaOut = out; lavaOutSince = g.tickCount; }
            if (out != null && g.tickCount - lavaOutSince > 30) { lavaOutBad.put(out, g.tickCount); com.claudemod.ClaudeMod.LOGGER.info("[Claude life] in lava at {}: 1.5 s and not onto {}: that way's no good, another (WorkGoal.reflexes)", me.toShortString(), out.toShortString()); lavaOut = null; }
            if (g.tickCount % 20 < 2) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] in lava at {}: getting out{}", me.toShortString(), out == null ? " (no dry cell near: jumping)" : " to " + out.toShortString() + (dryCells.contains(out) ? " (where he came from)" : ""));
            g.getNavigation().stop();
            if (out == null && g.tickCount - lavaDigTick > 4) { // no dry cell round him (a sealed pocket the lava came into, World 15 22:08: 17 s and dead): out through the wall, the side with no lava behind it, like a player
                BlockPos dig = null; int best = Integer.MAX_VALUE;
                for (Direction d0 : Direction.Plane.HORIZONTAL) { BlockPos n = me.relative(d0);
                    var ns = lv.getBlockState(n); var nh = lv.getBlockState(n.above());
                    if (ns.getCollisionShape(lv, n).isEmpty() && nh.getCollisionShape(lv, n.above()).isEmpty()) continue; // (open: the walk above takes it)
                    if (ns.getDestroySpeed(lv, n) < 0 || ns.getDestroySpeed(lv, n) > 3 || nh.getDestroySpeed(lv, n.above()) < 0 || nh.getDestroySpeed(lv, n.above()) > 3 || ns.hasBlockEntity() || nh.hasBlockEntity()) continue;
                    if (!lv.getBlockState(n.below()).isFaceSturdy(lv, n.below(), Direction.UP)) continue;
                    int lava = 0; for (BlockPos c0 : new BlockPos[] {n, n.above()}) for (Direction d1 : Direction.values()) { BlockPos q1 = c0.relative(d1); if (!q1.equals(me) && !q1.equals(me.above()) && !q1.equals(n) && !q1.equals(n.above()) && lv.getFluidState(q1).is(net.minecraft.tags.FluidTags.LAVA)) lava++; }
                    if (lava < best) { best = lava; dig = n; } }
                if (dig != null && best == 0 && g instanceof net.minecraft.server.level.ServerPlayer sp0) {
                    lavaDigTick = g.tickCount; equipBestTool(lv.getBlockState(dig));
                    float prog = Math.min(lv.getBlockState(dig).getDestroyProgress(g, lv, dig), lv.getBlockState(dig.above()).isAir() ? 1f : lv.getBlockState(dig.above()).getDestroyProgress(g, lv, dig.above()));
                    if (prog < 0.1f) dig = null; // (more than 10 ticks a block with what he has: no quick way through here; a block a player can't cut in time is no way out)
                    else for (BlockPos c0 : new BlockPos[] {dig, dig.above()}) if (!lv.getBlockState(c0).getCollisionShape(lv, c0).isEmpty()) sp0.gameMode.destroyBlock(c0); // (his pick's few ticks a block, at once: the move control has him moving meanwhile)
                    if (dig != null) { com.claudemod.ClaudeMod.LOGGER.info("[Claude life] in lava at {}, no dry cell round him: through the wall at {} (no lava behind it) (WorkGoal.reflexes)", me.toShortString(), dig.toShortString()); out = dig; }
                }
            }
            if (out != null) { g.getLookControl().faceNow(Vec3.atCenterOf(out)); /* (at once, then the walk: a turn over 4 ticks lost 4 of the ~22 a cell takes in lava drag) */ g.getMoveControl().setWantedPosition(out.getX() + 0.5, out.getY(), out.getZ() + 0.5, 1.3); g.getLookControl().setLookAt(out.getX() + 0.5, out.getY() + 1.0, out.getZ() + 0.5); g.setSprinting(true); }
            g.getJumpControl().jump();
            return true;
        }
        return false;
    }

    private int lavaDigTick = -100;
    @Override
    public void tick() {
        if (g.isUsingItem() && g.getUseItem().is(net.minecraft.world.item.Items.BOW) && g.tickCount - bowAimTick > 2) g.stopUsingItem(); /* the user: "walking super slow with his bow pulled back": the fight ended (target dead, out of sight, timed out, another job) with the draw still held, and a draw walks at 1/5 speed */
        if (reflexes()) return; // fire, air, lava first (WorkGoal.reflexes; also run while he's only hanging out with them)
        if (g.isSleeping()) return; // in bed: the job waits for morning (ticking on in bed timed the vein and its patches out)
        if (g.tickCount % 40 == 7 && (digInAt == null || digInTasks == null || !inDigInHole())) adoptPitCover(); // his hole from before a restart: its cover state died with it (or an old cover's record, somewhere else)
        // On fire out of the lava with a water bucket on him: water at his feet puts it out, like a player (live 14:07: out of the lava at 17 health, he burned down to 9 with a water bucket in his pack); his fillBucket scoops it back up
        if (g.isOnFire() && !g.isInLava() && !g.isInWater() && g.tickCount % 10 == 3 && !g.level().dimensionType().ultraWarm()) {
            var lvF = g.level(); BlockPos feetF = g.blockPosition();
            if (lvF.getBlockState(feetF).canBeReplaced() && lvF.getFluidState(feetF).isEmpty() && lvF.getBlockState(feetF.below()).isFaceSturdy(lvF, feetF.below(), Direction.UP) && g.inventory.count(st -> st.is(net.minecraft.world.item.Items.WATER_BUCKET)) > 0 && g.hold(st -> st.is(net.minecraft.world.item.Items.WATER_BUCKET))) {
                g.getLookControl().faceNow(Vec3.atCenterOf(feetF.below()).add(0, 0.5, 0)); g.useHeld(); // (the bucket's own ray, at the floor under him: a block click never pours a bucket)
                com.claudemod.ClaudeMod.LOGGER.info("[Claude life] on fire at {} (health {}): water at his feet to put it out (WorkGoal)", feetF.toShortString(), (int) g.getHealth());
            }
        }
        // Told to stay put: he holds that cell like a player in a current, stepping back to its middle when the flow drifts him (the user: "you're letting the water carry you", 15:19)
        if (g.staying) { BlockPos feetH = BlockPos.containing(g.getX(), g.getY() + 0.2, g.getZ()); BlockPos h = stayCell.computeIfAbsent(g.getUUID(), k -> feetH.immutable());
            double dx = g.getX() - (h.getX() + 0.5), dz = g.getZ() - (h.getZ() + 0.5);
            if (current == null && Math.abs(feetH.getY() - h.getY()) <= 1 && dx * dx + dz * dz > 0.3 * 0.3 && dx * dx + dz * dz < 3 * 3) { g.getNavigation().stop(); g.getMoveControl().setWantedPosition(h.getX() + 0.5, h.getY(), h.getZ() + 0.5, 0.6); if (g.tickCount % 100 == 0) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] holding {}: drifted {} off, stepping back (WorkGoal)", h.toShortString(), String.format("%.2f", Math.sqrt(dx * dx + dz * dz))); }
        } else stayCell.remove(g.getUUID());
        // A creeper closing in (it never hits you, it just walks up and blows): get away before it does, like a player
        boolean underLid = g.pillarBlocks.contains(g.blockPosition().above(2)) || g.pillarBlocks.contains(g.blockPosition().above(1)) || g.pillarBlocks.contains(g.blockPosition().above(3)); // down his own hole with the lid on
        if (g.tickCount % 5 == 0 && !(current instanceof Task.Flee) && !(diggingIn() && inDigInHole()) && !underLid) {
            var creepers = g.level().getEntitiesOfClass(net.minecraft.world.entity.monster.Creeper.class, g.getBoundingBox().inflate(7),
                    c -> c.isAlive() && c.distanceTo(g) < 7 && g.hasLineOfSight(c)); // (any creeper that close, whoever it's after: one on the player's trail blew up next to him; one walled off behind a block is no threat, that kept him running back and forth in his stairs 22 times)
            creepers.removeIf(c -> g.tasks.stream().anyMatch(t -> t instanceof Task.Attack a && a.target().equals(c.getUUID()))); // a fight with it already queued (clearing the block in the way first): leave that be, don't re-arm it every 5 ticks
            // armed, healthy and it isn't hissing yet: hit it (knocks it back) instead of being chased around by it
            if (!creepers.isEmpty() && armed() && !weakFighter() && g.getHealth() > 10 && (creepers.get(0).getSwellDir() <= 0 || canHitNow(creepers.get(0)))) { // a real sword only: five wooden-sword swings on a creeper is a funeral (death 18)
                if (!(current instanceof Task.Attack ca0 && !(g.level() instanceof ServerLevel sl0 && (sl0.getEntity(ca0.target()) instanceof net.minecraft.world.entity.animal.Animal || sl0.getEntity(ca0.target()) instanceof net.minecraft.world.entity.animal.AbstractFish)))) g.urgentAttacker = creepers.get(0).getUUID(); // (a fish on the end of his sword is no fight: a creeper walked up while he hunted salmon and blew him up, 27 through full iron)
                creepers = java.util.List.of();
            }
            if (!creepers.isEmpty() && !g.level().canSeeSky(g.blockPosition()) && creepers.get(0).distanceTo(g) >= 4 && !scaffoldBlock().isEmpty()) { // down a tunnel: a block across it at feet and head and the creeper is shut out, like a player (running up and down his stairs from one went nowhere)
                Direction to = Direction.getNearest(creepers.get(0).getX() - g.getX(), 0, creepers.get(0).getZ() - g.getZ());
                BlockPos w1 = g.blockPosition().relative(to), w2 = w1.above();
                boolean passage = !g.level().getBlockState(w1.relative(to.getClockWise())).getCollisionShape(g.level(), w1.relative(to.getClockWise())).isEmpty() && !g.level().getBlockState(w1.relative(to.getCounterClockWise())).getCollisionShape(g.level(), w1.relative(to.getCounterClockWise())).isEmpty(); // a 1-wide tunnel only: in an open cave it just walks round the wall (it did, and blew him up mid-placing)
                if (passage && g.level().getBlockState(w1).canBeReplaced() && g.level().getBlockState(w2).canBeReplaced() && !creepers.get(0).getBoundingBox().intersects(new net.minecraft.world.phys.AABB(w1).minmax(new net.minecraft.world.phys.AABB(w2)))) {
                    if (current != null) { clearCrack(); g.tasks.addFirst(current); current = null; g.currentTask = null; }
                    g.tasks.addFirst(new Task.Place(w2.immutable(), scaffoldBlock().getItem()));
                    g.tasks.addFirst(new Task.Place(w1.immutable(), scaffoldBlock().getItem()));
                    com.claudemod.ClaudeMod.LOGGER.info("[Claude life] creeper {} blocks off down here: walling it off at {}", (int) creepers.get(0).distanceTo(g), w1.toShortString());
                    creepers = java.util.List.of();
                }
            }
            if (!creepers.isEmpty() && diggingIn() && inDigInHole()) { BlockPos lid0 = BlockPos.containing(g.getEyePosition()).above(); if (!g.level().getBlockState(lid0).getCollisionShape(g.level(), lid0).isEmpty()) { if (g.tickCount % 40 == 0) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] creeper {} blocks off: staying down in his covered hole (the rock takes the blast; out in the open it took him in one)", (int) creepers.get(0).distanceTo(g)); creepers = java.util.List.of(); } } // (he climbed out of a lidded hole at dawn to run from a creeper 6 off, and it blew him up, 20 damage, no armor)
            if (!creepers.isEmpty()) {
                if (current != null) { clearCrack(); var cr0 = creepers.get(0); boolean backToIt = current instanceof Task.GoTo cg && cr0.distanceToSqr(Vec3.atCenterOf(cg.pos())) < g.distanceToSqr(Vec3.atCenterOf(cg.pos())); if (!backToIt) g.tasks.addFirst(current); else g.tasks.removeIf(t -> t instanceof Task.GoTo gg && cr0.distanceToSqr(Vec3.atCenterOf(gg.pos())) < 8 * 8); } // a walk that leads back past the creeper is dropped, not resumed: he fled it and walked straight back to it for two minutes, sprinting his food away, till it blew
                current = new Task.Flee(160); // (8 s: 4 s had it back on him before he'd made any distance)
                g.currentTask = current;
                com.claudemod.ClaudeMod.LOGGER.info("[Claude life] creeper {} blocks off: getting away from it", (int) creepers.get(0).distanceTo(g));
                ticks = 0; stuck = 0; lastDist = Double.MAX_VALUE;
            }
        }
        if (g.tickCount % 20 == 3 && !(current instanceof Task.Flee) && !(current instanceof Task.Attack) && g.getHealth() <= 4 && !diggingIn() && g.tickCount - desperateTick > 20 * 90
                && !g.isSleeping() && com.claudemod.director.ClaudeLife.foodToEat(g, true) == null && (!(g.getBlockY() >= g.level().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, g.getBlockX(), g.getBlockZ()) - 4 && !g.level().isDay() ? enemiesNear(24) : hostilesNear(16)).isEmpty() || enemiesNear(16).size() >= 3)) {
            desperateTick = g.tickCount; // (once per 90s whether or not cover could be made: no second-by-second spam when it can't) // a heart or two, nothing to eat, something hunting him: under cover, now
            if (current != null) { clearCrack(); g.tasks.addFirst(current); }
            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] at health {} with nothing to eat and {} hostile(s) about: holing up", (int) g.getHealth(), enemiesNear(24).size());
            desperateNow = true;
            boolean dug = digInFromArrows(); // straight to cover (a tower, or two down and a lid): no running first, nothing is on him yet
            desperateNow = false;
            if (dug) { current = null; g.currentTask = null; }
            else if (coverGap) { current = new Task.Flee(60); g.currentTask = current; ticks = 0; stuck = 0; lastDist = Double.MAX_VALUE; desperateTick = -100000; com.claudemod.ClaudeMod.LOGGER.info("[Claude life] at health {} with a melee mob too close to finish cover: running for the gap first, cover once he has it (WorkGoal.tick)", (int) g.getHealth()); } // (the cover's there to be had, just not this close: the 90 s once-only guard is off so the next look makes it)
            else if (g.getTarget() instanceof net.minecraft.server.level.ServerPlayer p && p.isAlive() && !p.isSpectator() && com.claudemod.director.ClaudeLife.groundUnder(g, p) != null && com.claudemod.director.ClaudeLife.toThem(g, p) < 96 // (where he knows them to be)
                    && com.claudemod.entity.life.Perception.known(g, net.minecraft.world.entity.Mob.class, new net.minecraft.world.phys.AABB(com.claudemod.director.ClaudeLife.groundUnder(g, p)).inflate(12), m -> m.isAlive() && m instanceof net.minecraft.world.entity.monster.Enemy).isEmpty()) { // (not into the patrol camped on them)
                // no cover to be had (nothing to build with, rock underfoot): to them, and the job waits until he's eaten; the planner gets it next (food before anything)
                com.claudemod.director.ClaudeLife.clearTasks(g); g.skillQueue.clear(); clearCrack(); current = null; g.currentTask = null; g.nextPlanTick = g.tickCount;
                g.tasks.add(new Task.GoTo(com.claudemod.director.ClaudeLife.groundUnder(g, p), 3));
                g.lastSkillLine = "at " + (int) g.getHealth() + " health with nothing to eat and mobs about, no cover to be had: went to them; food before anything else";
                com.claudemod.ClaudeMod.LOGGER.info("[Claude life] at health {} with nothing to eat, mobs about and no cover to be had: to them, the job waits", (int) g.getHealth());
            }
            else { current = new Task.Flee(60); g.currentTask = current; ticks = 0; stuck = 0; lastDist = Double.MAX_VALUE; }
        }
        // Nothing to fight with or in, hostiles closing, dark or hurt: under cover now, before the first hit (a fresh respawn at night died idle, then "while wait" in a hole with no lid)
        if (g.tickCount % 20 == 11 && !(current instanceof Task.Flee) && !(current instanceof Task.Attack) && !diggingIn() && !g.isSleeping() && weakKit()
                && !((g.getHealth() >= 14 || g.inventory.count(x -> !x.isEmpty()) <= 4) && g.lastStep != null && g.lastStep.skill().equals("recover_drops")) // healthy and running for his things before they vanish (his whole iron kit): not pulled back into cover every 2 s
                && (!g.level().isDay() || g.getHealth() < 10) && !com.claudemod.entity.life.Perception.known(g, net.minecraft.world.entity.Mob.class, g.getBoundingBox().inflate(12), m -> m.isAlive() && m instanceof net.minecraft.world.entity.monster.Enemy && (m.getTarget() == g || m.distanceTo(g) < 6 || g.level().canSeeSky(g.blockPosition()))).isEmpty()) { // (underground: only one that's after him or close; anything in the cave within 12 kept him on a tower at 2 health for good)
            if (current != null) { clearCrack(); g.tasks.addFirst(current); }
            desperateNow = true;
            boolean dug = digInFromArrows();
            desperateNow = false;
            if (dug) { current = null; g.currentTask = null; com.claudemod.ClaudeMod.LOGGER.info("[Claude life] weak kit, hostiles within 12, {} (health {}): under cover first", g.level().isDay() ? "hurt" : "night", (int) g.getHealth()); }
            else if (current != null) { g.tasks.pollFirst(); }
        }
        // Phantoms out at night (someone's insomnia): working in the open just bleeds hearts; under cover till morning, like a player with no bed
        if (g.tickCount % 40 == 17 && !(current instanceof Task.Flee) && !(current instanceof Task.Attack) && !diggingIn() && (g.getHealth() < 16 || weakKit())
                && !g.level().isDay() && g.level().canSeeSky(g.blockPosition()) && !g.isSleeping()
                && !g.level().getEntitiesOfClass(net.minecraft.world.entity.monster.Phantom.class, g.getBoundingBox().inflate(32), m -> m.isAlive()).isEmpty()) {
            if (current != null) { clearCrack(); g.tasks.addFirst(current); }
            desperateNow = true;
            boolean dug = digInFromArrows();
            desperateNow = false;
            if (dug) { current = null; g.currentTask = null; com.claudemod.ClaudeMod.LOGGER.info("[Claude life] phantoms out at night (health {}): under cover till morning", (int) g.getHealth()); }
            else if (current != null) { g.tasks.pollFirst(); }
        }
        // Digging in already (in his hole, or at it): a hit doesn't stop him, like a player he keeps digging
        boolean byCover = digInAt != null && Math.abs(g.getBlockX() - digInAt.getX()) <= 3 && Math.abs(g.getBlockZ() - digInAt.getZ()) <= 3 && Math.abs(g.getBlockY() - digInAt.getY()) <= Math.max(4, digInRise + 2); // (an arrow's knockback puts him a block or two off his box: still making it)
        if (g.urgentAttacker != null && diggingIn() && (inDigInHole() || byCover)) { var inHole = ((ServerLevel) g.level()).getEntity(g.urgentAttacker); if (!(inHole instanceof net.minecraft.world.entity.LivingEntity) || inHole.distanceTo(g) > 2.5) { if (!inDigInHole() && coverHitSaid != digInTick) { coverHitSaid = digInTick; com.claudemod.ClaudeMod.LOGGER.info("[Claude life] hit off his cover's middle at {} by a {} {} off: back into it and on with it, not a fight (WorkGoal.tick)", digInAt.toShortString(), inHole == null ? "?" : inHole.getType().getDescription().getString(), inHole == null ? "?" : (int) inHole.distanceTo(g)); } else if (inDigInHole()) sdSay("cover", "[Claude life] hit while digging in at {} by a {} {} off (more than 2.5: not in the hole with him): digging on, not a fight (WorkGoal.tick)", digInAt == null ? "?" : digInAt.toShortString(), inHole == null ? "mob gone" : inHole.getType().getDescription().getString(), inHole == null ? "?" : (int) inHole.distanceTo(g)); g.urgentAttacker = null; } } // (knocked two off his box by the first arrow, he went to fight the skeleton at 9 and lost 18 health) // one hitting him from right beside him (a gap in the unfinished walls) gets fought: he stood in "wait" in full iron and took 20 zombie hits, 11 -> 0
        // Down his hole on the wait: a hit from something outside is no reason to climb out and "fight back" (that walk out of the lid is how a zombie took him 16 → 0); only something right in the hole with him gets fought
        if (g.urgentAttacker != null && current instanceof Task.Wait && digInTasks != null && digInTasks.stream().anyMatch(x -> x == current)) {
            var foe0 = ((ServerLevel) g.level()).getEntity(g.urgentAttacker);
            if (!(foe0 instanceof net.minecraft.world.entity.LivingEntity) || foe0.distanceTo(g) > 2.5) { sdSay("holewait", "[Claude life] hit in his hole by a {} {} blocks off: staying down, not climbing out for it (WorkGoal.tick)", foe0 == null ? "mob gone" : foe0.getType().getDescription().getString(), foe0 == null ? "?" : (int) foe0.distanceTo(g)); g.urgentAttacker = null; }
        }
        // Already in a fight and something else hits him: weighed like a player, never skipped silently. The one on him is fought first
        // when the one he's after isn't in his reach; the old fight waits in the queue (live 17:09: nine zombie hits in diamond armor
        // went unanswered while he chased a phantom circling 6 up, 19 -> 13, till the user asked if he'd let it beat him up)
        boolean turnOnHitter = false;
        if (g.urgentAttacker != null && current instanceof Task.Attack cf && !cf.target().equals(g.urgentAttacker)) {
            var hitter = ((ServerLevel) g.level()).getEntity(g.urgentAttacker); var aimed = ((ServerLevel) g.level()).getEntity(cf.target());
            boolean prey = aimed instanceof net.minecraft.world.entity.animal.Animal || aimed instanceof net.minecraft.world.entity.animal.AbstractFish; // (a hunt: the block below takes it as no fight)
            boolean hitterOn = hitter instanceof net.minecraft.world.entity.LivingEntity hl && hl.isAlive() && hl.distanceTo(g) <= 4.0;
            boolean aimedInReach = aimed instanceof net.minecraft.world.entity.LivingEntity al && al.isAlive() && al.getBoundingBox().distanceToSqr(g.getEyePosition()) <= 9.0 && g.hasLineOfSight(al);
            if (!prey && hitterOn && !aimedInReach) {
                turnOnHitter = true;
                com.claudemod.ClaudeMod.LOGGER.info("[Claude life] a {} hit him ({} off) while he went for a {} out of his reach ({} off): the {} first, the {} after (WorkGoal.tick)", hitter.getType().getDescription().getString(), String.format("%.1f", hitter.distanceTo(g)), aimed == null ? "mob gone" : aimed.getType().getDescription().getString(), aimed == null ? "?" : (int) aimed.distanceTo(g), hitter.getType().getDescription().getString(), aimed == null ? "fight" : aimed.getType().getDescription().getString());
            } else if (!prey) sdSay("busy", "[Claude life] hit by a {} ({} off) while fighting a {} {}: on with that one, the {} next (WorkGoal.tick)", hitter == null ? "mob gone" : hitter.getType().getDescription().getString(), hitter == null ? "?" : String.format("%.1f", hitter.distanceTo(g)), aimed == null ? "mob gone" : aimed.getType().getDescription().getString(), aimedInReach ? "in his reach" : "with the hitter not on him", hitter == null ? "hitter" : hitter.getType().getDescription().getString());
        }
        if (g.urgentAttacker != null && current instanceof Task.Flee) { var hf = ((ServerLevel) g.level()).getEntity(g.urgentAttacker); sdSay("flee", "[Claude life] hit by a {} ({} off) while running (health {}): still running, the run's end decides (WorkGoal.tick)", hf == null ? "mob gone" : hf.getType().getDescription().getString(), hf == null ? "?" : (int) hf.distanceTo(g), (int) g.getHealth()); }
        // Self-defense comes first: a mob that hit him gets fought, then he goes back to what he was doing.
        if (g.urgentAttacker != null && (turnOnHitter || !(current instanceof Task.Attack ca && !(((ServerLevel) g.level()).getEntity(ca.target()) instanceof net.minecraft.world.entity.animal.Animal || ((ServerLevel) g.level()).getEntity(ca.target()) instanceof net.minecraft.world.entity.animal.AbstractFish))) && !(current instanceof Task.Flee)) { // (swinging at a fish is no fight: a skeleton put 5 arrows in him while he hunted salmon)
            var foe = ((ServerLevel) g.level()).getEntity(g.urgentAttacker);
            if (!(foe instanceof net.minecraft.world.entity.LivingEntity l0) || !l0.isAlive()) { // that one's dead: whatever is on him now is the attacker (he killed the first zombie, sat down to "heal" with the second one hitting him, 20 -> 5)
                net.minecraft.world.entity.Mob next = null; for (var m : com.claudemod.entity.life.Perception.known(g, net.minecraft.world.entity.Mob.class, g.getBoundingBox().inflate(4), net.minecraft.world.entity.EntitySelector.ENTITY_STILL_ALIVE)) if (m instanceof net.minecraft.world.entity.monster.Enemy && m.getTarget() == g && (next == null || m.distanceTo(g) < next.distanceTo(g))) next = m; // (no lambda here: a class the live game reloads)
                if (next != null) { g.urgentAttacker = next.getUUID(); foe = next; }
            }
            // Gone, dead or far off, or hurt by a stray hit with nothing actually on him: nothing to fight or run from.
            // Running from a mob that isn't there is how he looped at 6 health; a player steps clear and eats.
            if (!(foe instanceof net.minecraft.world.entity.LivingEntity l) || !l.isAlive() || foe.distanceTo(g) > 24 || (shouldFlee(foe) && hostilesNear(14).isEmpty() && foe.distanceTo(g) > 8)) { // (a mob 2 blocks off that just hit him is on him, whatever its target says: "healing up" there is how a skeleton killed him)
                if (foe != null) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] hit by a {} that isn't on him ({} blocks off, health {}): healing up, not running", foe.getType().getDescription().getString(), (int) foe.distanceTo(g), (int) g.getHealth());
                else com.claudemod.ClaudeMod.LOGGER.info("[Claude life] hit by a mob that's gone from the world now (health {}): nothing to fight, healing up (WorkGoal.tick)", (int) g.getHealth());
                g.urgentAttacker = null;
                healUp();
                return;
            }
            if (g.tickCount - lastFightTick().getOrDefault(g.urgentAttacker, -100000) < 20) { sdSay("rearm", "[Claude life] hit by the {} again {} ticks after squaring up to it, doing {}: no new fight armed this tick (WorkGoal.tick)", foe.getType().getDescription().getString(), g.tickCount - lastFightTick().get(g.urgentAttacker), current == null ? "nothing" : current.getClass().getSimpleName()); g.urgentAttacker = null; return; } // (the same fight re-armed every 5 ticks: once a second is plenty)
            lastFightTick().put(g.urgentAttacker, g.tickCount);
            boolean retreat = shouldFlee(foe) && g.getHealth() < 10 && !diggingIn(); // (never the escape itself: it threw away the tower he'd just queued and three husks killed him) // running at a few hearts: the job he was on is where the mobs are; it isn't resumed after the run (the queue walked him back to two zombies at 5 health)
            if (retreat && (current != null || !g.tasks.isEmpty())) { com.claudemod.ClaudeMod.LOGGER.info("[Claude life] retreating at health {}: the job there ({} tasks) is dropped, not walked back into", (int) g.getHealth(), g.tasks.size()); com.claudemod.director.ClaudeLife.clearTasks(g); }
            else if (current != null && g.tasks.peek() != current) { clearCrack(); g.tasks.addFirst(current); } // (once: not a copy of it every time this fires)
            com.claudemod.director.ClaudeLife.craftSwordIfCan(g, (ServerLevel) g.level()); // something's on him: a sword if he can make one
            boolean run = shouldFlee(foe);
            current = run ? new Task.Flee(600) : new Task.Attack(g.urgentAttacker, 300); if (run) fleeSeed = g.urgentAttacker; // (the one that set him running is what he runs from, seen or not) // (the run ends when he's clear of them, above; 30 s is only the backstop: 8 s cut it off 15 blocks from a witch)
            g.currentTask = current; // what he's doing right now (the chat and the logs read this)
            g.dropCurrent = false; // a drop meant for the job he was on (re-queued above) must not kill this fight the same tick (that re-armed the same fight every 5 ticks, 750 tasks deep)
            if (run) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] backing off (health {}, {} mobs close, armed {}; {})", (int) g.getHealth(), hostilesNear(10).size(), armed(), fightOdds);
            else com.claudemod.ClaudeMod.LOGGER.info("[Claude life] fighting back: {} (health {}, {} mobs close, armed {})", foe == null ? "?" : foe.getType().getDescription().getString(), (int) g.getHealth(), hostilesNear(10).size(), armed());
            g.urgentAttacker = null;
            ticks = 0; stuck = 0; lastDist = Double.MAX_VALUE;
        }
        if (!(current instanceof Task.Attack)) shieldDown(); // the shield is for fights: down again for mining, walking, eating
        if (g.dropCurrent) { // something was put in front of what he's doing (it was re-queued if it still matters)
            g.dropCurrent = false;
            clearCrack();
            current = null;
        }
        g.busy = current != null;
        if (current == null) {
            nearestFirst();
            current = g.tasks.poll();
            int dup = 0; while (current != null && !(current instanceof Task.Pillar) && current.equals(g.tasks.peek())) { Task nx = g.tasks.poll(); if (com.claudemod.entity.nav.Route.owns(g, nx) && !com.claudemod.entity.nav.Route.owns(g, current)) current = nx; dup++; } // (a route's own copy is the one kept: the other copy dropped took the route's end, and its old legs walked him back down, 12:31) // (two Pillar(-1)s are two towers to take down, not a copy: dropping one left a cobble block in his stair walkway) // the same task queued over and over (a route re-fired every few seconds): one copy is plenty
            if (dup > 0) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] {} duplicate copies of {} dropped from his queue", dup, current);
            while (current != null && !com.claudemod.entity.life.Watchdog.starting(g, current)) current = g.tasks.poll(); /* the no-progress watchdog: an action that kept failing goes up its ladder, not round again */
            g.busy = current != null;
            ticks = 0; stuck = 0; lastDist = Double.MAX_VALUE; breakProgress = 0; bridged = 0;
            g.currentTask = current;
            if (current == null) return;
        }
        com.claudemod.entity.nav.Route.step(g); // (his trail: the legs he walks through)
        ticks++;
        maybeStandUp();
        boolean done;
        try {
            done = switch (current) {
                case Task.GoTo t -> { if (g.staying && t.pos().distSqr(g.blockPosition()) > 2 * 2) { if (g.tickCount % 100 < 2 || ticks == 1) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] staying put where they said: the walk to {} ({}) waits (WorkGoal)", t.pos().toShortString(), com.claudemod.entity.ClaudeEntity.originOf(t)); yield true; } // (told to stay put, every chore of his own walks him off: a stash run, a torch run, his nook; one gate at the walk, their own go_to clears staying first, 15:03)
                    if (ticks == 1) { // a walk into a cell still solid, its dig queued behind the walk: the dig first, then the walk (live 04:43 World 15: a GoTo into the rock under him, its Break next in the queue, 32 stalls in a minute)
                        BlockPos tp = t.pos(); var lv0 = g.level(); java.util.List<Task> first = new java.util.ArrayList<>();
                        for (Task q0 : g.tasks) if (q0 instanceof Task.Break b0 && (b0.pos().equals(tp) || b0.pos().equals(tp.above())) && !lv0.getBlockState(b0.pos()).getCollisionShape(lv0, b0.pos()).isEmpty() && !first.contains(q0)) first.add(q0);
                        if (!first.isEmpty()) {
                            g.tasks.removeAll(first); g.tasks.addFirst(t); for (int i0 = first.size() - 1; i0 >= 0; i0--) g.tasks.addFirst(first.get(i0));
                            com.claudemod.ClaudeMod.LOGGER.info("[Claude walk] the walk to {} waits for its own cell's dig ({} block(s) still there, queued behind it): digging first (WorkGoal.GoTo)", tp.toShortString(), first.size());
                            yield true;
                        }
                    }
                    if (ticks == 1 && t.pos().distSqr(g.blockPosition()) > 4 * 4) { // the user's rule: every break and patch in reach is done in the moment (ore, then holes, then the path), then on
                        java.util.List<Task> here = new java.util.ArrayList<>(); var eye0 = g.getEyePosition();
                        if (g.tickCount - pulledAt > 20 * 60) { pulledAt = g.tickCount; pulled.clear(); } // (kept 60 s whatever the walk: keyed by target it cleared each time the walk flipped between two cells by his arch, and pulled the same 8 jobs forward again and again, 04:03) // each job pulled once per walk: one that can't be done goes back in the queue, never pulled again ahead of the walk (03:29: a patch that failed was pulled forward 10 times a second)
                        for (Task q0 : g.tasks) { BlockPos qp = q0 instanceof Task.Break b0 ? b0.pos() : q0 instanceof Task.Place p0 ? p0.pos() : null; if (qp != null && !pulled.contains(q0) && inReach(qp) && qp.distSqr(t.pos()) <= g.blockPosition().distSqr(t.pos()) /* (none farther from the walk's end than he is: each branch block dug brought the next one west in reach, and the walk north to unload became 8 blocks of tunnelling, live 13:59) */ && blockInTheWay((ServerLevel) g.level(), qp) == null && !(q0 instanceof Task.Place pw && pw.want() != null) && !com.claudemod.entity.life.WatchBuild.ownsCell(g, qp)) here.add(q0); } /* (not a build's blocks or its clearing: they go in its order, each from the spot planned for the build as it stands by then) */ /* (nothing between: a far row cell pulled ahead of the nearer ones hit "in the way" every tick, 04:29) */ // (the dig's and the place's own reach test: a looser eye distance pulled a patch he couldn't reach from there, and it was dropped unplaced, test stage 18)
                        pulled.addAll(here);
                        if (!here.isEmpty()) {
                            here.sort(java.util.Comparator.comparingInt(q0 -> q0 instanceof Task.Break b0 && com.claudemod.entity.life.MineBlueprint.ore(g.level().getBlockState(b0.pos())) ? 0 : 1)); // ore first; the rest in queue order (a swap's Break and its Place stay a pair: sorting places ahead split them, 04:03)
                            g.tasks.removeAll(here); g.tasks.removeIf(q0 -> q0.equals(t)); g.tasks.addFirst(t); for (int i0 = here.size() - 1; i0 >= 0; i0--) g.tasks.addFirst(here.get(i0));
                            com.claudemod.ClaudeMod.LOGGER.info("[Claude dig] {} job(s) in reach at {} done first, then the walk to {}", here.size(), g.blockPosition().toShortString(), t.pos().toShortString());
                            yield true; } }
                    yield (ticks == 1 && routeOut(t)) || walkTo(t.pos(), t.within()); }
                case Task.Break t -> { exitWhy = null; boolean fin = doBreak(t.pos()); if (fin) { var bs0 = g.level().getBlockState(t.pos()); if (!bs0.isAir() && bs0.getFluidState().isEmpty() && !bs0.canBeReplaced()) { boolean again = g.tasks.stream().anyMatch(q0 -> q0 instanceof Task.Break b0 && b0.pos().equals(t.pos())); StackTraceElement[] st0 = Thread.currentThread().getStackTrace(); com.claudemod.ClaudeMod.LOGGER.info("[Claude dig] {} at {} left standing after {} ticks{} (he's at {})", net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(bs0.getBlock()).getPath(), t.pos().toShortString(), ticks, again ? ", back in the queue" : ", dropped", g.blockPosition().toShortString() + (exitWhy == null ? "" : "; why: " + exitWhy) + "; queued by " + com.claudemod.entity.ClaudeEntity.originOf(t)); com.claudemod.entity.life.MindLog.refused(g, t.pos(), "breaking " + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(bs0.getBlock()).getPath() + (exitWhy == null ? "" : " [" + exitWhy + "]") + " queued by " + com.claudemod.entity.ClaudeEntity.originOf(t)); } } yield fin; } // every dig that ends without the block broken says so (the user: path cells at -17, -17/-18, -150 skipped with no line in the log)
                case Task.Place t -> { exitWhy = null; boolean fin = doPlace(t); if (fin && exitWhy != null && !g.level().getBlockState(t.pos()).is(net.minecraft.world.level.block.Block.byItem(t.item()))) { com.claudemod.ClaudeMod.LOGGER.info("[Claude dig] placing {} at {} gave up (he's at {}); why: {}", net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(t.item()).getPath(), t.pos().toShortString(), g.blockPosition().toShortString(), exitWhy + "; queued by " + com.claudemod.entity.ClaudeEntity.originOf(t)); com.claudemod.entity.life.MindLog.refused(g, t.pos(), "placing [" + exitWhy + "] queued by " + com.claudemod.entity.ClaudeEntity.originOf(t)); } yield fin; }
                case Task.UseDoor t -> doDoor(t);
                case Task.Chest t -> doChest(t);
                case Task.Wait t -> doWait(t);
                case Task.Deposit t -> doDeposit(t);
                case Task.Smelt t -> doSmelt(t);
                case Task.FurnaceLoad t -> doFurnaceLoad(t);
                case Task.FurnaceTake t -> doFurnaceTake(t);
                case Task.Attack t -> doAttack(t);
                case Task.Flee t -> doFlee(t);
                case Task.Till t -> doTill(t);
                case Task.Toss t -> doToss(t);
                case Task.Eat t -> doEat(t);
                case Task.Pillar t -> doPillar(t);
                case Task.Pickup t -> doPickup(t);
                case Task.FinishTree t -> doFinishTree(t);
                case Task.ChopTree t -> doChopTree(t);
                case Task.SealStep t -> doSealStep(t);
                case Task.Replant t -> doReplant(t);
                case Task.MineVisibleOres t -> doMineVisibleOres(t);
                case Task.WallTorch t -> doWallTorch(t);
                case Task.CheckForCave t -> doCheckForCave();
                case Task.Fish t -> doFish(t);
                case Task.Leap t -> doLeap(t);
            };
        } catch (RuntimeException e) {
            done = true; // a step that throws (block changed under him, etc.) is just skipped
        }
        // Watchdog: no single action should take 45s (cooking and long fights have their own limits). Skip it, never hang.
        boolean longByNature = current instanceof Task.Smelt || current instanceof Task.FurnaceTake || current instanceof Task.Attack
                || current instanceof Task.Wait || current instanceof Task.Fish;
        if (!done && !longByNature && ticks > 20 * 45) {
            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] stuck on {} for 45s, skipping it", current);
            g.getNavigation().stop();
            BlockPos spot = switch (current) { case Task.Break b -> b.pos(); case Task.GoTo go -> go.pos(); case Task.Place pl -> pl.pos(); default -> null; }; // what he couldn't get at stays off the list a while (the ore-spotting would queue it straight back)
            if (spot != null) {
                g.attempts.any.put(spot.immutable(), g.tickCount);
                final BlockPos sp = spot;
                if (current instanceof Task.Break) g.tasks.removeIf(t -> t instanceof Task.Break b && b.pos().distSqr(sp) <= 3 * 3); // the rest of that vein is just as out of reach: not 45s each
            }
            done = true;
        }
        if (done && current instanceof Task.GoTo gt0 && Math.sqrt(g.blockPosition().distSqr(gt0.pos())) > gt0.within() + 4) // (any walk that ends 4+ off its spot, however long it ran: a silent end hid where 3 bed legs went, 12:53) // a walk that ended before it began, far from the spot: say so (go_to "49 64 164" ended in 2 ticks, silently, three plans running)
            com.claudemod.ClaudeMod.LOGGER.info("[Claude walk] GoTo {} ended {} from {} (walk-fail {}, queue now {} first {}, why {})", gt0.pos().toShortString(), ticks <= 3 ? "at once" : "after " + ticks + " ticks, " + (int) Math.sqrt(g.blockPosition().distSqr(gt0.pos())) + " off it,", g.blockPosition().toShortString(), g.attempts.of(com.claudemod.entity.life.Attempts.Kind.WALK).get(gt0.pos()), g.tasks.size(), g.tasks.peek(), exitWhy);
        if (done) {
            try { com.claudemod.entity.life.Watchdog.ended(g, current, current instanceof Task.GoTo ? null : exitWhy); /* (exitWhy is only reset by the dig, place and leap: a walk would carry the last one's) */ } catch (RuntimeException e) { com.claudemod.ClaudeMod.LOGGER.warn("[Claude watchdog] ended() threw on {}", current, e); }
            clearCrack();
            current = null;
            g.currentTask = null;
        }
    }

    /**
     * Where to stand for a target: the target itself if he can stand in it, otherwise the nearest open
     * spot next to it (or a level below/above, for a log on a slope), closest to him. Falls back to pos.
     */
    private BlockPos standSpotBeside(BlockPos pos) { return standSpotBeside(pos, true); }

    /** The cell beside him (same level or one up) that is open and floored, to step out of the one he's in; null if there's none. */
    private BlockPos stepOutSpot(BlockPos in) {
        ServerLevel level = (ServerLevel) g.level();
        BlockPos best = null; double bestD = Double.MAX_VALUE;
        for (Direction d : Direction.Plane.HORIZONTAL) for (int dy = -1; dy <= 1; dy++) { // (and one down: out of the cell under a hole in his wall the walkway is a step down; only 'up' sent him further into the hole, 263)
            BlockPos p = in.relative(d).above(dy);
            boolean open = level.getBlockState(p).getCollisionShape(level, p).isEmpty() && level.getBlockState(p.above()).getCollisionShape(level, p.above()).isEmpty() && level.getFluidState(p).isEmpty();
            if (!open || !level.getBlockState(p.below()).isFaceSturdy(level, p.below(), Direction.UP)) continue;
            double dd = Math.abs(dy) * 4 + p.distSqr(g.blockPosition());
            if (stepOutPatch && com.claudemod.director.ClaudeLife.bpMode(g) && g.blueprint != null && !g.blueprint.isEmpty() && g.blueprint.isWalk(p)) dd -= 50; // out of a hole in his mine's wall he's patching: back onto his walkway, never further out into the ore hole
            if (dd < bestD) { bestD = dd; best = p.immutable(); }
        }
        return best;
    }

    /** A spot to stand at to reach pos; mayStandIn: the cell itself when it's open (to dig there), never for a block that is to go in it. */
    private BlockPos standSpotBeside(BlockPos pos, boolean mayStandIn) {
        ServerLevel level = (ServerLevel) g.level();
        if (mayStandIn && level.getBlockState(pos).getCollisionShape(level, pos).isEmpty() && level.getFluidState(pos).isEmpty()) return pos;
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        // right beside it first; then a step further out or a bit higher (a trunk sunk into a slope or snow), anywhere in reach
        for (BlockPos p0 : BlockPos.betweenClosed(pos.offset(-2, -3, -2), pos.offset(2, 2, 2))) { // (three below too: a roof's overhang is placed from the ground under it)
            {
                BlockPos p = p0.immutable();
                if (p.equals(pos) || Vec3.atBottomCenterOf(p).add(0, 1.62, 0).distanceTo(Vec3.atCenterOf(pos)) > REACH - 0.3) continue;
                boolean open = level.getBlockState(p).getCollisionShape(level, p).isEmpty()
                        && level.getBlockState(p.above()).getCollisionShape(level, p.above()).isEmpty()
                        && level.getFluidState(p).isEmpty();
                boolean floor = level.getBlockState(p.below()).isFaceSturdy(level, p.below(), Direction.UP);
                if (!open || !floor) continue;
                double dd = p.distSqr(g.blockPosition()) + 4 * p.distManhattan(pos); // close to him, but beside it if possible
                if (dd < bestD) { bestD = dd; best = p; }
            }
        }
        return best != null ? best : pos;
    }

    private static java.util.Map<BlockPos, Integer> stepTries; // how often the same edge was picked to step off (static: fields added live start null)
    private static java.util.Map<BlockPos, Integer> stepTries() { if (stepTries == null) stepTries = new java.util.HashMap<>(); if (stepTries.size() > 50) stepTries.clear(); return stepTries; }

    private static java.util.Set<BlockPos> planned; // walks he's already planned a way for (static: fields added live start null)
    private static java.util.Set<BlockPos> planned() { if (planned == null) planned = new java.util.HashSet<>(); if (planned.size() > 64) planned.clear(); return planned; }

    private int mapTick = -100000;

    /** The ground around him as text, for the log: one row per layer (feet-1 up to feet+3), x across, z down. '#' solid, '.' open, '~' water, 'g' gravel/sand, '@' him, '*' the target if in view. */
    private String miniMap(BlockPos dest) {
        mapTick = g.tickCount;
        ServerLevel level = level();
        BlockPos at = g.blockPosition();
        StringBuilder sb = new StringBuilder(" heading ").append(Direction.getNearest(dest.getX() - at.getX(), 0, dest.getZ() - at.getZ()).getName()).append(", layers feet-1..feet+3 (x across, z down):");
        for (int dy = -1; dy <= 3; dy++) {
            sb.append("\n   y").append(at.getY() + dy).append(": ");
            for (int dz = -3; dz <= 3; dz++) {
                for (int dx = -3; dx <= 3; dx++) {
                    BlockPos q = at.offset(dx, dy, dz);
                    char c;
                    if (q.equals(dest)) c = '*';
                    else if (dx == 0 && dz == 0 && (dy == 0 || dy == 1)) c = '@';
                    else if (!level.getFluidState(q).isEmpty()) c = '~';
                    else if (level.getBlockState(q).getBlock() instanceof net.minecraft.world.level.block.FallingBlock) c = 'g';
                    else c = level.getBlockState(q).getCollisionShape(level, q).isEmpty() ? '.' : '#';
                    sb.append(c);
                }
                sb.append(dz < 3 ? "|" : "");
            }
        }
        return sb.toString();
    }

    private BlockPos forceDigAt; // a spot the plan keeps sending him to: walking failed, so he digs toward it
    private int detours; // which side he went round last (a field added live starts 0: fine)

    /** A spot 8 blocks off to the side of the straight line to pos, on the ground, alternating sides each call. Surface only. */
    private BlockPos sideWaypoint(BlockPos pos) {
        ServerLevel level = level();
        if (!level.canSeeSky(g.blockPosition().above())) return null;
        double dx = pos.getX() + 0.5 - g.getX(), dz = pos.getZ() + 0.5 - g.getZ(), len = Math.sqrt(dx * dx + dz * dz);
        if (len < 2) return null;
        double px = -dz / len, pz = dx / len; // perpendicular
        int sign = (detours++ % 2 == 0) ? 1 : -1;
        int x = (int) Math.floor(g.getX() + dx / len * 4 + px * 8 * sign), z = (int) Math.floor(g.getZ() + dz / len * 4 + pz * 8 * sign);
        BlockPos w = new BlockPos(x, level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
        if (!level.getFluidState(w).isEmpty() || !level.getFluidState(w.below()).isEmpty()) return null;
        return w;
    }
    private boolean routeReaches = true; // does the current route actually end at the destination?
    private java.util.Map<BlockPos, Integer> skips; // (lazy: fields added live start null)
    private BlockPos diveFor; // the chest he's diving to (fields added live start null: fine, null means none)
    private boolean diveBreath; // surfacing for air in the middle of a dive
    private java.util.Map<BlockPos, Integer> skips() { if (skips == null) skips = new java.util.HashMap<>(); return skips; }
    private static java.util.Map<java.util.UUID, Integer> critJump; // the tick of his last crit jump (static: fields added live start null)
    private static java.util.Map<java.util.UUID, Integer> critJump() { if (critJump == null) critJump = new java.util.HashMap<>(); return critJump; }

    /** Returns true when arrived, or when stuck (step skipped). */
    // when he first started walking to each spot (kept across re-queues, so a walk that keeps getting re-queued can't jiggle forever)
    private final java.util.Map<BlockPos, Integer> walkSince = new java.util.HashMap<>();
    private final java.util.Map<BlockPos, Double> walkBest = new java.util.HashMap<>();

    /** A closed wooden door or fence gate beside him, on the way to where he is going: opened, like a player walking through his own door. */
    private void openDoorAhead(BlockPos pos) {
        ServerLevel level = (ServerLevel) g.level();
        BlockPos feet = BlockPos.containing(g.getX(), g.getY() + 0.2, g.getZ());
        for (Direction d : Direction.Plane.HORIZONTAL) for (int y = 0; y <= 1; y++) {
            BlockPos n = feet.relative(d).above(y);
            BlockState s = level.getBlockState(n);
            boolean door = s.getBlock() instanceof DoorBlock && !s.is(net.minecraft.world.level.block.Blocks.IRON_DOOR), gate = s.getBlock() instanceof net.minecraft.world.level.block.FenceGateBlock;
            if (!door && !gate) continue;
            if (door ? s.getValue(DoorBlock.OPEN) : s.getValue(net.minecraft.world.level.block.FenceGateBlock.OPEN)) continue;
            double along = (n.getX() - feet.getX()) * (pos.getX() - feet.getX()) + (n.getZ() - feet.getZ()) * (pos.getZ() - feet.getZ());
            if (along <= 0) continue; // behind him: not on the way
            Vec3 hit = Vec3.atCenterOf(n);
            g.getLookControl().faceNow(hit);
            g.gameMode.useItemOn(g, level, ItemStack.EMPTY, InteractionHand.MAIN_HAND, new net.minecraft.world.phys.BlockHitResult(hit, d.getOpposite(), n, false));
            g.swing(InteractionHand.MAIN_HAND);
            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] opened the {} at {} on his way to {}", door ? "door" : "gate", n.toShortString(), pos.toShortString());
            return;
        }
    }

    private static java.util.Map<BlockPos, Integer> plannedAt; // when a way to a spot was last planned (static: fields added live start null)
    private static java.util.Map<BlockPos, Integer> plannedAt() { if (plannedAt == null) plannedAt = new java.util.HashMap<>(); return plannedAt; }

    /** Digging is a move only in or by his mine, underground (no sky over him, not in his house), or on a walk the plan keeps sending him. */
    private boolean mayDig(BlockPos pos) {
        if (pos.equals(forceDigAt)) return true;
        if (com.claudemod.director.ClaudeLife.inHisMine(g, pos) || com.claudemod.director.ClaudeLife.touchesMine(g, pos)) return true;
        BlockPos feet = BlockPos.containing(g.getX(), g.getY() + 0.2, g.getZ());
        boolean inHouse = com.claudemod.director.ClaudeLife.houseMode(g) && g.house != null && g.house.touches(feet);
        if (inHouse) return false;
        if (inHole()) return true; // in a hole (every side 2+ up): dug out of, like a player
        return !g.level().canSeeSky(feet.above());
    }

    /** Down a hole open to the sky: every column round his feet stands 2+ above them (a 1x1 shaft, a pit). (Underground every column is higher: that's a tunnel, not a hole.) */
    private boolean inHole() {
        BlockPos feet = BlockPos.containing(g.getX(), g.getY() + 0.2, g.getZ());
        if (!g.level().canSeeSky(feet.above())) return false;
        for (Direction d : Direction.Plane.HORIZONTAL) { BlockPos n = feet.relative(d); if (g.level().getBlockState(n).getCollisionShape(g.level(), n).isEmpty() || g.level().getBlockState(n.above()).getCollisionShape(g.level(), n.above()).isEmpty()) return false; } // (a side open at feet or head: a way out on foot)
        return true;
    }

    private static java.util.Map<BlockPos, Integer> forcedAt; // when a walk was last tried "for real" (dig allowed) after failing (static: fields added live start null)
    private static java.util.Map<BlockPos, Integer> forcedAt() { if (forcedAt == null) forcedAt = new java.util.HashMap<>(); return forcedAt; }

    /** A walk farther than his walker plans, or between the surface and his mine: the whole way planned into legs (Route) and put in its place. */
    private boolean routeOut(Task.GoTo t) {
        var legs = com.claudemod.entity.nav.Route.expand(g, level(), t.pos(), t.within());
        if (legs == null || com.claudemod.entity.nav.Route.walkerTakes(legs)) return false;
        if (legs.isEmpty()) { g.getNavigation().stop(); return true; } // (behind him on a route he's on: Route said so, dropped)
        for (int i = legs.size() - 1; i >= 0; i--) g.tasks.addFirst(legs.get(i));
        g.getNavigation().stop();
        return true;
    }

    /** The search's budget for a walk: 3000, and 400 more a block of climb past 4 (a 23-up climb out of a lava-sea ledge ran out at 3000 on the cheaper ways round: "no way at all", World 15 22:38). */
    private static int climbBudget(BlockPos from, BlockPos to) { return 3000 + 400 * Math.max(0, to.getY() - from.getY() - 4); }

    /** A spot 5+ up with no way the search found (a fortress deck over the open, World 15 22:38): the player's way, a tower beside it from the ground under its edge, then onto it. */
    private boolean towerTo(BlockPos pos, BlockPos feet, ItemStack scaff) {
        ServerLevel level = (ServerLevel) g.level(); int up = pos.getY() - feet.getY();
        if (up < 5 || scaff.isEmpty() || scaff.getCount() < up + 2) return false;
        BlockPos bestS = null, bestG = null; double bd = Double.MAX_VALUE;
        for (BlockPos s1 : BlockPos.betweenClosed(pos.offset(-4, 0, -4), pos.offset(4, 0, 4))) { BlockPos s0 = s1.immutable(); // (a cell off the edge of what he's climbing onto: open, with a standing cell of it beside)
            if (!level.getBlockState(s0).getCollisionShape(level, s0).isEmpty() || !level.getBlockState(s0.above()).getCollisionShape(level, s0.above()).isEmpty()) continue;
            if (!level.getBlockState(s0.below()).getCollisionShape(level, s0.below()).isEmpty()) continue; // (on the deck itself: no tower there)
            boolean edge = false; for (Direction d1 : Direction.Plane.HORIZONTAL) { BlockPos n1 = s0.relative(d1); if (level.getBlockState(n1).getCollisionShape(level, n1).isEmpty() && level.getBlockState(n1.above()).getCollisionShape(level, n1.above()).isEmpty() && level.getBlockState(n1.below()).isFaceSturdy(level, n1.below(), Direction.UP)) edge = true; }
            if (!edge) continue;
            BlockPos q = s0.below(); int k = 0; boolean ok = true;
            while (k < up + 6 && level.getBlockState(q).getCollisionShape(level, q).isEmpty()) { if (!level.getFluidState(q).isEmpty()) { ok = false; break; } q = q.below(); k++; }
            if (!ok || level.getBlockState(q).getCollisionShape(level, q).isEmpty() || !level.getFluidState(q.above()).isEmpty() || com.claudemod.entity.body.Controls.lavaCell(g, q.above())) continue;
            BlockPos gr = q.above(); if (Math.abs(gr.getY() - feet.getY()) > 2) continue; // (its foot on his own level: a walk there, then the tower)
            double dd = gr.distSqr(feet); if (dd < bd) { bd = dd; bestS = s0.immutable(); bestG = gr.immutable(); } }
        if (bestS == null) return false;
        g.tasks.addFirst(new Task.GoTo(pos, 0.9)); g.tasks.addFirst(new Task.Pillar(bestS.getY() - bestG.getY())); g.tasks.addFirst(new Task.GoTo(bestG, 0.7)); // (0.4 never settled there, a flaky stall)
        com.claudemod.ClaudeMod.LOGGER.info("[Claude life] no way to {} the search found ({} up): a tower beside it, from {} up {} to {}, then onto it (WorkGoal.towerTo)", pos.toShortString(), up, bestG.toShortString(), bestS.getY() - bestG.getY(), bestS.toShortString());
        return true;
    }

    private boolean walkTo(BlockPos pos, double within) {
        Integer failedAt = g.attempts.of(com.claudemod.entity.life.Attempts.Kind.WALK).get(pos);
        if (failedAt != null && g.tickCount - failedAt < 20 * 60) { // couldn't get there a moment ago: don't burn another 8s
            if (skips().merge(pos.immutable(), 1, Integer::sum) <= 3) { com.claudemod.ClaudeMod.LOGGER.info("[Claude walk] the walk to {} failed {} s ago: skipped this time ({} of 3) (WorkGoal.walkTo)", pos.toShortString(), (g.tickCount - failedAt) / 20, skips().get(pos)); return true; }
            int dugTo = Integer.MAX_VALUE; for (BlockPos sp : g.mineSpine) dugTo = Math.min(dugTo, sp.getY());
            boolean floating = g.level().getBlockState(pos).isAir() && g.level().getBlockState(pos.below()).getCollisionShape(g.level(), pos.below()).isEmpty(); // (air with nothing under it: an arch block, a branch cell; a surface spot with ground under it is somewhere to stand)
            if (Math.abs(pos.getY() - g.getBlockY()) >= 8 && floating && (!com.claudemod.director.ClaudeLife.isMineCell(g, pos) || pos.getY() < dugTo)) return true; // a spot in the air far above or below him, off his mine (a cave patch 25 down): no tunnelling a new spiral to it
            if (pos.getY() >= g.getBlockY() + 3 && floating) return true; // a spot in the air well above him (an arch block, a branch): no digging "through" to that, it stays skipped
            // ...unless the plan keeps sending him there (a route out that runs through it): try it for real, digging his way if he must
            if (g.tickCount - forcedAt().getOrDefault(pos.immutable(), -100000) < 20 * 60) return true; // the forced try failed already this minute: skipped like any failed walk (it re-fired every 3 s)
            forcedAt().put(pos.immutable(), g.tickCount); if (forcedAt().size() > 64) forcedAt().clear();
            skips().remove(pos); g.attempts.of(com.claudemod.entity.life.Attempts.Kind.WALK).remove(pos);
            forceDigAt = pos.immutable(); // this time he cuts his way through if walking fails again
            BlockPos side = null; /* (the planner has looked all round already: no side-waypoint detour; sideWaypoint(pos) towered him six up onto the wall it was meant to go round) */
            if (side != null) {
                g.tasks.addFirst(new Task.GoTo(pos, within));
                g.tasks.addFirst(new Task.GoTo(side, 1.5));
                com.claudemod.ClaudeMod.LOGGER.info("[Claude life] keeps being sent to {}: going round by {} this time", pos.toShortString(), side.toShortString());
                return true;
            }
            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] keeps being sent to {}: trying it for real again (digging through if he must)", pos.toShortString());
        }
        BlockPos feetCell = BlockPos.containing(g.getX(), g.getY() + 0.2, g.getZ()); // (on farmland or a slab his feet sit a little down into the block: the cell he is in, not the block under him)
        double d = Math.sqrt(feetCell.distSqr(pos));
        if (!g.level().getBlockState(pos).getCollisionShape(g.level(), pos).isEmpty() && inReach(pos)) return true; // a walk to a block (a trunk, ore): in reach of it is there
        if (walkSince.size() > 64) { walkSince.values().removeIf(t0 -> g.tickCount - t0 > 20 * 60); walkBest.keySet().retainAll(walkSince.keySet()); }
        if (walkTask != current) { walkTask = current; walkKept.clear(); walkFor = null; } // (each job's walks start their clocks afresh)
        if (!pos.equals(walkFor)) { // a walk to another spot inside the same job (the bed's step behind its foot, then the cell beside it): its own clocks, never the last spot's
            if (walkFor != null) walkKept.put(walkFor, new double[] {stuck, lastDist});
            double[] kept = walkKept.remove(pos);
            if (kept == null) { walkSince.remove(pos); walkBest.remove(pos); stuck = 0; lastDist = Double.MAX_VALUE; } // a new walk there: the clock starts now (a stale clock from a walk there 20-60 s ago gave up on the bed's spot the tick it began; the bed's first walk's 8 s of no-closer ran on into the second's)
            else { stuck = (int) kept[0]; lastDist = kept[1]; }
            walkFor = pos.immutable();
        }
        if (ticks <= 1 && pendingStepUp != null) { // the step he just cut: up onto it now, before any path can pull him back off the edge
            stepUpTo = Vec3.atBottomCenterOf(pendingStepUp); stepUpTick = g.tickCount; pendingStepUp = null;
        }
        Double bestD = walkBest.get(pos);
        if (bestD == null || d < bestD - 0.5) { walkBest.put(pos.immutable(), d); walkSince.put(pos.immutable(), g.tickCount); } // getting closer: the clock starts over
        int since = walkSince.computeIfAbsent(pos.immutable(), k -> g.tickCount);
        if (d > within && d < 8 && g.tickCount - since > 20 * 20 && g.tickCount - since < 20 * 60) { // 20s on a spot a few blocks away: it's not happening
            walkSince.remove(pos); walkBest.remove(pos);
            g.attempts.of(com.claudemod.entity.life.Attempts.Kind.WALK).put(pos.immutable(), g.tickCount);
            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] gave up walking to {} after 20s (he's at {})", pos.toShortString(), g.blockPosition().toShortString());
            com.claudemod.entity.nav.Route.legFailed(g, pos);
            return true;
        }
        // stepping up onto a step he just cut: a second of just that (hop and walk onto it), no path pulling him elsewhere
        if (stepUpTo != null && g.tickCount - stepUpTick < 20) {
            g.getNavigation().stop();
            g.getMoveControl().setWantedPosition(stepUpTo.x, stepUpTo.y, stepUpTo.z, 1.0);
            g.getLookControl().setLookAt(stepUpTo.x, stepUpTo.y + 1.5, stepUpTo.z);
            if (g.onGround() && g.getY() < stepUpTo.y - 0.5) g.getJumpControl().jump();
            if (g.onGround() && g.getY() >= stepUpTo.y - 0.01) { // up on it: a fresh look for a route from here, and if there's none, the next step right away
                stepUpTick = -100;
                BlockPos aim = standSpotBeside(pos);
                g.getNavigation().moveTo(aim.getX() + 0.5, aim.getY(), aim.getZ() + 0.5, 0.9);
                var p = g.getNavigation().getPath();
                routeReaches = p != null && p.canReach();
                lastDist = Math.sqrt(g.blockPosition().distSqr(pos)); stuck = 121; // (past every wait: the next step right away if there's still no route)
            }
            return false;
        }
        // stepping off an edge (into water / onto vines): just walk over it for a moment, no path in the way
        if (stepOffTo != null && g.tickCount - stepOffTick < 30) {
            g.getNavigation().stop();
            g.setShiftKeyDown(false);
            g.getMoveControl().setWantedPosition(stepOffTo.x, stepOffTo.y, stepOffTo.z, 0.6);
            if (!g.onGround() || g.isInWater()) stepOffTick = -100; // he's off: back to the walk
            return false;
        }
        // In water, headed somewhere not down under it: swim like a player. Space held to stay up, facing where he's going,
        // straight at the next point of his route (no spinning on the spot).
        if (g.isInWater() && !com.claudemod.entity.body.Controls.wading(g) && pos.getY() >= g.getBlockY() - 1 && d > within) {
            var path = g.getNavigation().getPath();
            Vec3 next = path != null && !path.isDone() ? Vec3.atBottomCenterOf(path.getNextNodePos()) : Vec3.atBottomCenterOf(pos);
            if (path == null || path.isDone()) g.getNavigation().moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 1.0);
            g.getJumpControl().jump(); // keep his head up
            g.getLookControl().setLookAt(next.x, g.getEyeY(), next.z, 30, 30);
            float yaw = (float) (Math.toDegrees(Math.atan2(next.z - g.getZ(), next.x - g.getX())) - 90);
            g.setYRot(yaw); g.yBodyRot = yaw; g.yHeadRot = yaw;
            g.getMoveControl().setWantedPosition(next.x, next.y, next.z, 1.0);
        }
        if (g.isInWater() && pos.getY() < g.getBlockY() - 1 && g.level().getFluidState(pos).is(net.minecraft.tags.FluidTags.WATER) && d > within) { // under the water: dive straight for it like a player (the body comes up for air by itself)
            Vec3 to = Vec3.atCenterOf(pos);
            g.getNavigation().stop();
            g.getLookControl().setLookAt(to.x, to.y, to.z, 30, 30);
            g.getMoveControl().setWantedPosition(to.x, to.y, to.z, 1.0);
            return false;
        }
        // standing on a canopy right above where he's headed (dark oak, jungle): that's not there yet, he's on the leaves
        boolean onLeavesAbove = pos.getY() <= g.getBlockY() - 3 && g.level().getBlockState(g.blockPosition().below()).is(net.minecraft.tags.BlockTags.LEAVES);
        boolean chain = g.tasks.peekFirst() instanceof Task.GoTo nx && Math.abs(nx.pos().getY() - pos.getY()) <= 1; // a waypoint with the next one queued on the same level: run through it, like a player (stopping dead at every one was a hitch every few blocks down his tunnel, the user)
        if (within < 1.0 && d <= 1.5 && pos.getY() == feetCell.getY() && !g.isInWater() && current instanceof Task.GoTo && com.claudemod.entity.life.PlaceAim.standable((ServerLevel) g.level(), pos)) { // a precise walk to the cell beside (or his own): straight into its middle (the path finder calls the cell beside "there": he stalled 10 ticks and stopped a block short, the build's stand for its next block a cell off)
            double hx = pos.getX() + 0.5 - g.getX(), hz = pos.getZ() + 0.5 - g.getZ();
            if (stepInFor != current) { stepInFor = current; stepInAt = ticks; } // (its own clock: the walk that brought him beside it doesn't count)
            if (hx * hx + hz * hz <= 0.09) { g.getNavigation().stop(); return true; }
            if (ticks - stepInAt > 30) { g.getNavigation().stop(); com.claudemod.ClaudeMod.LOGGER.info("[Claude walk] 1.5 s stepping into {} beside him and not in its middle (at {}, {}): that's there (WorkGoal.walkTo)", pos.toShortString(), String.format("%.2f", g.getX()), String.format("%.2f", g.getZ())); return true; }
            g.getNavigation().stop(); g.getMoveControl().setWantedPosition(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0.5);
            return false;
        }
        if ((d <= within || (chain && d <= 2.0)) && !onLeavesAbove) {
            if (!chain) g.getNavigation().stop();
            return true;
        }
        if (d <= 1.3 && within <= 1.0 && stuck > 10 && !onLeavesAbove) { g.getNavigation().stop(); return true; } // next to it and not getting any nearer (grass in the cell, a corner): that is there (a log in the grass a block off had him "couldn't get to" every 8 s)
        if (onLeavesAbove && ticks % 10 == 0) { // dig down through the canopy, then carry on
            g.tasks.addFirst(new Task.GoTo(pos, within));
            if (descendStep(pos)) return true;
            g.tasks.pollFirst();
        }
        // Gravel or sand fell into the spot (his tunnel, his stairs): dig it out rather than give up on it.
        var there = g.level().getBlockState(pos);
        if (there.getBlock() instanceof net.minecraft.world.level.block.FallingBlock && inReach(pos)) {
            g.tasks.addFirst(new Task.GoTo(pos, within));
            g.tasks.addFirst(new Task.Break(pos));
            return true;
        }
        boolean bridging = g.tickCount - bridgeCrouchTick < 20;
        if (!bridging && (ticks % 20 == 1 || g.getNavigation().isDone())) {
            // Vanilla pathing turns a solid target (a log, ore in a wall) into "the first air above it": the treetop.
            // Aim for an open spot beside it instead.
            BlockPos aim = standSpotBeside(pos);
            g.getNavigation().moveTo(aim.getX() + 0.5, aim.getY(), aim.getZ() + 0.5, 0.9);
            var path = g.getNavigation().getPath();
            routeReaches = path != null && path.canReach();
            if (routeReaches) for (int i = Math.max(1, path.getNextNodeIndex()); i < path.getNodeCount(); i++) { var b = path.getNode(i); double ay = i == path.getNextNodeIndex() ? g.getY() : path.getNode(i - 1).y; // (the next step against his feet, as Controls judges it: the start node can sit a block over them)
                if (b.y - ay > 1.25 && !g.level().getBlockState(new BlockPos(b.x, b.y, b.z)).is(net.minecraft.tags.BlockTags.CLIMBABLE) && !g.level().getBlockState(new BlockPos(b.x, b.y - 1, b.z)).is(net.minecraft.tags.BlockTags.CLIMBABLE)) { routeReaches = false; if (ticks % 200 == 1) com.claudemod.ClaudeMod.LOGGER.info("[Claude walk] the way to {} has a step {} up at {},{},{}: not his way, planning his own (WorkGoal.walkTo)", pos.toShortString(), String.format("%.1f", b.y - ay), b.x, b.y, b.z); break; } }
            if (current instanceof Task.GoTo && path != null && path.getNodeCount() > 0) { // a crowd of hostiles on the way: round them, or not this walk (he walked through the pack on his death pile, twice)
                java.util.List<net.minecraft.world.phys.Vec3> threats = new java.util.ArrayList<>(); int onRoute = 0;
                if (ticks <= 2) { float yaw = (float) Math.toDegrees(Math.atan2(-(pos.getX() + 0.5 - g.getX()), pos.getZ() + 0.5 - g.getZ())); g.setYRot(yaw); g.setYHeadRot(yaw); g.yBodyRot = yaw; com.claudemod.entity.life.Perception.lookNow(g); } // a player looks the way he's going before he sets off: what's on the way is seen before the way is chosen
                for (var m : enemiesNear(24)) {
                    if (!(m.distanceTo(g) < 8 || g.hasLineOfSight(m))) continue; // (what he can see or hear close by, nothing through walls)
                    threats.add(m.position());
                    for (int i = path.getNextNodeIndex(); i < path.getNodeCount(); i++) if (path.getNode(i).asBlockPos().distSqr(m.blockPosition()) < 9) { onRoute++; break; }
                }
                int crowd = !armed() || weakKit() ? 1 : 2;
                if (onRoute >= crowd && pos.distSqr(g.blockPosition()) > 4) {
                    g.getNavigation().stop();
                    if (g.tickCount - plannedAt().getOrDefault(pos.above(500).immutable(), -100000) > 60) {
                        plannedAt().put(pos.above(500).immutable(), g.tickCount); // (keyed apart from the ordinary plans for this spot)
                        var plan = com.claudemod.entity.nav.ActionPath.plan(g, (ServerLevel) g.level(), feetCell, pos, Math.max(within, 1.0), false, 3000, com.claudemod.entity.nav.ActionPath.Mode.NO_DIG, g.attempts.of(com.claudemod.entity.life.Attempts.Kind.WALK), g.tickCount, threats);
                        if (plan != null) {
                            var tasks = com.claudemod.entity.nav.ActionPath.tasks(plan, feetCell, net.minecraft.world.item.Items.COBBLESTONE, pos, within, (ServerLevel) g.level());
                            for (int i = tasks.size() - 1; i >= 0; i--) g.tasks.addFirst(tasks.get(i));
                            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] {} hostile(s) on the way to {}: going round them ({} walks)", onRoute, pos.toShortString(), plan.walks());
                            return true;
                        }
                    }
                    g.attempts.fail(com.claudemod.entity.life.Attempts.Kind.WALK, pos, g.tickCount);
                    com.claudemod.ClaudeMod.LOGGER.info("[Claude life] {} hostile(s) between him and {} ({}): not walking through them", onRoute, pos.toShortString(), armed() ? "a crowd" : "unarmed");
                    return true;
                }
            }
            // in his own mine, a route that doesn't arrive just wanders off (up the stairs and back): don't take it (ore in its wall counts: that walk took him up and out onto the grass)
            BlockPos routeEnd = path != null && path.getEndNode() != null ? path.getEndNode().asBlockPos() : null;
            boolean alongHisStairs = routeEnd != null && com.claudemod.director.ClaudeLife.inHisMine(g, routeEnd) && routeEnd.distSqr(pos) < (d - 3) * (d - 3); // a route that stays in his mine and ends 3+ blocks nearer: that's up his own stairs, worth walking (dropping it had him hop-cutting one step per 8 s up a perfectly good staircase)
            if (!routeReaches && d < 24 && !alongHisStairs && (com.claudemod.director.ClaudeLife.inHisMine(g, pos) || com.claudemod.director.ClaudeLife.touchesMine(g, pos)) && com.claudemod.director.ClaudeLife.inHisMine(g, g.blockPosition()))
                g.getNavigation().stop();
            // any route that ends farther from the spot than he is now is no route (the pathfinder's "closest it can get" can be the surface over a mine): stand, don't wander
            else if (!routeReaches && path != null && path.getEndNode() != null && path.getEndNode().asBlockPos().distSqr(pos) > (d + 2) * (d + 2))
                g.getNavigation().stop();
        }
        lookWhereWalking(pos);
        if (ticks % 10 == 3) openDoorAhead(pos);
        // stuck swimming in a flooded bit of his mine: block where the water comes in, then carry on
        if (g.isInWater() && !com.claudemod.entity.body.Controls.wading(g) && stuck == 30 && g.mineShape != null) {
            g.tasks.addFirst(new Task.GoTo(pos, within));
            if (blockWaterInflow()) return true;
            g.tasks.pollFirst();
        }
        // Stuck swimming (a flooded cave, a pool with a roof): no route plans through water and none of the digging rules below run in it, so he'd tread water for ever
        // (45 s stuck, then idle, "nah not in water"). A player towers out of the water (a block in under him) and digs up through whatever's over him; with nothing to
        // tower with, a notch in the nearest wall at the waterline to swim into. From there the rules for land take over (steps up, digging through).
        if (g.isInWater() && !com.claudemod.entity.body.Controls.wading(g) && stuck == 60 && !walkNoDig && pos.getY() >= g.getBlockY() - 1 && d > within) { // (once per stuck spell: every 3 s restarted the slow underwater dig and it never finished)
            ServerLevel lv = (ServerLevel) g.level();
            BlockPos feet = g.blockPosition(), head = feet.above(2);
            // first, the obvious: a shore. A flood-fill through the water (and the air over it) to the nearest cell with ground under it and room to stand, then waypoints there
            {
                java.util.Map<BlockPos, BlockPos> from = new java.util.HashMap<>();
                java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
                BlockPos start = feet.immutable(); from.put(start, start); queue.add(start);
                BlockPos shore = null;
                while (!queue.isEmpty() && from.size() < 2500 && shore == null) {
                    BlockPos c = queue.poll();
                    for (Direction dd : Direction.values()) {
                        BlockPos n = c.relative(dd);
                        if (from.containsKey(n) || n.distSqr(start) > 24 * 24) continue;
                        var ns = lv.getBlockState(n);
                        boolean passable = ns.getCollisionShape(lv, n).isEmpty() && lv.getBlockState(n.above()).getCollisionShape(lv, n.above()).isEmpty();
                        if (!passable) continue;
                        boolean water = !lv.getFluidState(n).isEmpty() || !lv.getFluidState(n.below()).isEmpty();
                        boolean ground = !lv.getBlockState(n.below()).getCollisionShape(lv, n.below()).isEmpty() && lv.getFluidState(n).isEmpty();
                        if (!water && !ground) continue; // (through the water, or onto ground right at its edge: not off across dry land)
                        from.put(n.immutable(), c);
                        if (ground) { shore = n.immutable(); break; }
                        queue.add(n.immutable());
                    }
                }
                if (shore != null && !shore.equals(start)) {
                    java.util.List<BlockPos> path = new java.util.ArrayList<>();
                    for (BlockPos c = shore; !c.equals(start); c = from.get(c)) path.add(0, c);
                    g.tasks.addFirst(new Task.GoTo(pos, within));
                    for (int i = path.size() - 1; i >= 0; i--) if (i == path.size() - 1 || i % 3 == 0) g.tasks.addFirst(new Task.GoTo(path.get(i), 0.8));
                    com.claudemod.ClaudeMod.LOGGER.info("[Claude life] stuck swimming at {}: a shore at {} ({} blocks through the water), swimming there", feet.toShortString(), shore.toShortString(), path.size());
                    return true;
                }
            }
            var hs = lv.getBlockState(head);
            boolean roof = !hs.getCollisionShape(lv, head).isEmpty();
            boolean roofDiggable = roof && hs.getDestroySpeed(lv, head) >= 0 && !hs.hasBlockEntity();
            g.tasks.addFirst(new Task.GoTo(pos, within));
            if (!scaffoldBlock().isEmpty() && (!roof || roofDiggable)) {
                g.tasks.addFirst(new Task.Pillar(1));
                if (roof) g.tasks.addFirst(new Task.Break(head.immutable()));
                com.claudemod.ClaudeMod.LOGGER.info("[Claude life] stuck swimming at {} ({}): a block in under him{}", feet.toShortString(), roof ? "roof overhead" : "open over him", roof ? " after digging the roof out" : "");
                return true;
            }
            for (Direction dd : Direction.Plane.HORIZONTAL) { // nothing to tower with: a notch in the wall beside him, two high, at his level
                BlockPos w = feet.relative(dd), w2 = w.above();
                var ws = lv.getBlockState(w); var ws2 = lv.getBlockState(w2);
                if (ws.getCollisionShape(lv, w).isEmpty() || ws.getDestroySpeed(lv, w) < 0 || ws.hasBlockEntity() || ws2.getDestroySpeed(lv, w2) < 0 || ws2.hasBlockEntity()) continue;
                g.tasks.addFirst(new Task.GoTo(w.immutable(), 0.6));
                if (!ws2.getCollisionShape(lv, w2).isEmpty()) { g.tasks.addFirst(new Task.Break(w2.immutable())); punchThrough().add(w2.immutable()); }
                g.tasks.addFirst(new Task.Break(w.immutable())); punchThrough().add(w.immutable()); // by hand if need be: getting out of the water is not a job for the right tool (the no-tool rule dumped his queue and sent him for logs, every 30 s, from the pool)
                com.claudemod.ClaudeMod.LOGGER.info("[Claude life] stuck swimming at {} with nothing to tower with: a notch in the wall {} of him to climb into", feet.toShortString(), dd.getName());
                return true;
            }
            g.tasks.pollFirst();
        }
        // In water with no route (the pathfinder won't plan underwater): swim straight at it; he dives if it's below.
        if (g.isInWater() && (g.getNavigation().isDone() || !routeReaches || pos.getY() < g.getBlockY() - 1)) {
            g.getNavigation().stop(); // (a route along the surface wouldn't take him down)
            g.getMoveControl().setWantedPosition(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 1.0);
        }
        // One planner (navigation-as-action-planning): the moment the vanilla path doesn't reach, or after a second without getting
        // nearer, the way is planned over moves (walk, jump, drop, swim, climb, a door, a block put down, and in MAY_DIG a block dug)
        // with the etiquette in the costs, once per spot per 3 s. No way at all: the walk ends now, one line, the spot off for a minute.
        boolean deepWater = g.isInWater() && !com.claudemod.entity.body.Controls.wading(g);
        if ((ticks == 2 && !routeReaches || stuck == 20 || stuck > 20 && stuck % 200 == 20) && d >= 2 && d <= 28 && !walkNoDig && !routeReaches && !deepWater && !(inHole() && g.isInWater()) // (a dry hole: planned like any walk, MAY_DIG; a flooded one stays the cutter's)
                && g.tickCount - plannedAt().getOrDefault(pos.immutable(), -100000) > 60) { // (3 s between plans for one spot: a loop guard, not a wait)
            plannedAt().put(pos.immutable(), g.tickCount); if (plannedAt().size() > 64) plannedAt().clear();
            long t0 = System.nanoTime();
            var mode = mayDig(pos) ? com.claudemod.entity.nav.ActionPath.Mode.MAY_DIG : com.claudemod.entity.nav.ActionPath.Mode.NO_DIG;
            ItemStack scaff = scaffoldBlock();
            boolean dirtOnHim = !scaff.isEmpty() && (scaff.is(net.minecraft.world.item.Items.DIRT) || scaff.is(net.minecraft.world.item.Items.GRASS_BLOCK) || scaff.is(net.minecraft.world.item.Items.COARSE_DIRT) || scaff.is(net.minecraft.world.item.Items.ROOTED_DIRT));
            boolean outside = g.level().canSeeSky(feetCell.above()) && !com.claudemod.director.ClaudeLife.inHisMine(g, feetCell);
            var plan = outside && !scaff.isEmpty() && !dirtOnHim ? com.claudemod.entity.nav.ActionPath.plan(g, (ServerLevel) g.level(), feetCell, pos, Math.max(within, 1.0), false, climbBudget(feetCell, pos), mode, g.attempts.of(com.claudemod.entity.life.Attempts.Kind.WALK), g.tickCount) : null; // (the user: out in the world only dirt is put down and left; with none on him, a way without placing first)
            if (plan == null) plan = com.claudemod.entity.nav.ActionPath.plan(g, (ServerLevel) g.level(), feetCell, pos, Math.max(within, 1.0), !scaff.isEmpty(), climbBudget(feetCell, pos), mode, g.attempts.of(com.claudemod.entity.life.Attempts.Kind.WALK), g.tickCount);
            if (plan == null && g.level().dimensionType().hasCeiling()) { // the Nether: a way that is mostly bridge or tunnel costs several times the heuristic a cell, and the plain search spent its budget on the ledge round him (stage 535: a 13-block bridge to the deck, "no way at all" with 54 blocks on him); once more, greedy (w 2.5), twice the budget
                plan = com.claudemod.entity.nav.ActionPath.plan(g, (ServerLevel) g.level(), feetCell, pos, Math.max(within, 1.0), !scaff.isEmpty(), climbBudget(feetCell, pos) * 2, mode, g.attempts.of(com.claudemod.entity.life.Attempts.Kind.WALK), g.tickCount, java.util.List.of(), 2.5);
                com.claudemod.ClaudeMod.LOGGER.info("[Claude route] {} -> {}: no way in the plain search; the greedy one (w 2.5): {} (WorkGoal.walkTo)", feetCell.toShortString(), pos.toShortString(), plan == null ? "none either" : plan.digs() + " digs, " + plan.puts() + " put down, " + plan.walks() + " walks");
            }
            java.util.List<BlockPos> nookCuts = new java.util.ArrayList<>();
            if (plan == null && mode == com.claudemod.entity.nav.ActionPath.Mode.MAY_DIG && !com.claudemod.director.ClaudeLife.allNooks(g).isEmpty() && com.claudemod.director.ClaudeLife.nookErrand(g, pos)) { // (only a walk into his nook: a walk to them or anywhere else with no way but through it stands by, said below) // no way at all with his nook's shell kept: once more, cutting it only where nothing else gets there
                var cutPlan = com.claudemod.entity.nav.ActionPath.plan(g, (ServerLevel) g.level(), feetCell, pos, Math.max(within, 1.0), !scaff.isEmpty(), 3000, com.claudemod.entity.nav.ActionPath.Mode.MAY_CUT_NOOK, g.attempts.of(com.claudemod.entity.life.Attempts.Kind.WALK), g.tickCount);
                if (cutPlan != null) for (var a : cutPlan.acts()) if (a instanceof com.claudemod.entity.nav.ActionPath.Act.Dig dg && com.claudemod.director.ClaudeLife.nookShell(g, dg.pos())) nookCuts.add(dg.pos().immutable());
                if (cutPlan != null && !nookCuts.isEmpty()) { plan = cutPlan; com.claudemod.ClaudeMod.LOGGER.info("[Claude route] no way to {} from {} with his nook's shell kept: cutting it at {}, each patched behind him once he's through (WorkGoal.walkTo)", pos.toShortString(), feetCell.toShortString(), nookCuts.stream().map(BlockPos::toShortString).toList()); }
                else if (cutPlan != null) plan = cutPlan; // (a way the first search missed, none of it his shell)
            }
            if (plan != null && (plan.digs() > 0 || plan.puts() > 0 || plan.acts().stream().anyMatch(a -> a instanceof com.claudemod.entity.nav.ActionPath.Act.Door || a instanceof com.claudemod.entity.nav.ActionPath.Act.Leap))) { // (a drop or a jump: its own task, Task.Leap)
                if (!nookCuts.isEmpty()) com.claudemod.director.ClaudeLife.nookCutsBack(g, nookCuts, scaff.isEmpty() || scaff.is(net.minecraft.world.item.Items.DIRT) ? net.minecraft.world.item.Items.COBBLESTONE : scaff.getItem()); // (queued first: the walk goes in front of them, so they run once he's through)
                var tasks = com.claudemod.entity.nav.ActionPath.tasks(plan, feetCell, scaff.isEmpty() ? net.minecraft.world.item.Items.COBBLESTONE : scaff.getItem(), pos, within, (ServerLevel) g.level());
                for (int i = tasks.size() - 1; i >= 0; i--) g.tasks.addFirst(tasks.get(i));
                com.claudemod.ClaudeMod.LOGGER.info("[Claude route] the made way to {}: acts {} -> tasks {} (WorkGoal.walkTo)", pos.toShortString(), plan.acts(), tasks);
                com.claudemod.ClaudeMod.LOGGER.info("[Claude life] no route to {}: made one ({}: {} digs, {} blocks put down{}, {} walks; {} ms)", pos.toShortString(), mode, plan.digs(), plan.puts(), plan.puts() > 0 && outside && !dirtOnHim ? " of " + com.claudemod.entity.life.Crafting.name(scaff.getItem()) + ", to take back up" : "", plan.walks(), (System.nanoTime() - t0) / 1000000);
                return true;
            }
            if (plan != null) { // a way on foot the vanilla path missed (over a ledge, through an open door): its waypoints
                var tasks = com.claudemod.entity.nav.ActionPath.tasks(plan, feetCell, net.minecraft.world.item.Items.COBBLESTONE, pos, within);
                if (tasks.size() > 1) { for (int i = tasks.size() - 1; i >= 0; i--) g.tasks.addFirst(tasks.get(i)); com.claudemod.ClaudeMod.LOGGER.info("[Claude life] route to {} found on foot ({} walks, {} waypoints)", pos.toShortString(), plan.walks(), tasks.size() - 1); return true; }
            } else if (towerTo(pos, feetCell, scaff)) { return true;
            } else {
                com.claudemod.ClaudeMod.LOGGER.info("[Claude life] no way to {} from {} ({}; blocks on him: {}): skipping it", pos.toShortString(), feetCell.toShortString(), mode, scaffoldBlock().getCount());
                stuck = STUCK_LIMIT; // (the give-up below runs now: the spot off for a minute, the work tied to it skipped, no 8 s wait)
            }
        }
        if (d < 3 && stuck > 30) stepTo(pos);
        if (stuck == 30) for (BlockPos q : BlockPos.betweenClosed(g.blockPosition().offset(-1, -1, -1), g.blockPosition().offset(1, 1, 1))) { // a rail in the way (mineshaft): pathing won't cross it, so it comes out
            if (!g.level().getBlockState(q).is(net.minecraft.tags.BlockTags.RAILS)) continue;
            g.tasks.addFirst(new Task.GoTo(pos, within));
            g.tasks.addFirst(new Task.Break(q.immutable()));
            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] a rail in his way at {}: breaking it", q.toShortString());
            return true;
        }
        /* (bridging on its own at stuck 10: gone; a bridge is a Put move of the planned way) */
        // Up a tree (on a trunk or the canopy) and no route anywhere: get down first, wherever he's headed. Even up high, players climb down to walk.
        if (stuck > 20 && (g.onGround() || g.onClimbable()) && ticks % 10 == 0 && treeUnder() >= 2) {
            g.tasks.addFirst(new Task.GoTo(pos, within));
            if (descendStep(pos)) return true;
            g.tasks.pollFirst();
        }
        // Way below with no walkable way down (up a tree, on a cliff): dig down under himself, or drop into water / climb down vines.
        boolean mineTripStart = ticks == 10 && !g.mineSpine.isEmpty() && !g.level().canSeeSky(pos) && !com.claudemod.director.ClaudeLife.inHisMine(g, g.blockPosition()) && g.level().canSeeSky(g.blockPosition()); // (a spot down his mine, and he's up top: his stairs from the start, not overland toward it first and the stairs only once stuck: he walked past his own entrance to stand over the chest)
        if (pos.getY() <= g.getBlockY() - 3 && (stuck > 20 || mineTripStart) && (g.onGround() || g.onClimbable()) && ticks % 10 == 0) {
            // one of his own stations (a chest, a furnace, a table) down there: it sits in a mine of his, reached by its stairs; never a hole cut in the grass over it (the dirt crosses)
            if (g.mineShape == null && !com.claudemod.entity.ClaudeSpawner.testing && g.myStations.stream().anyMatch(st -> st.distSqr(pos) <= 3 * 3 && (g.level().getBlockState(st).hasBlockEntity() || g.level().getBlockState(st).is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE))) && g.level().canSeeSky(g.blockPosition())) { // (no mine on file at all, a station still standing there; the test box keeps old stage tables about)
                g.attempts.of(com.claudemod.entity.life.Attempts.Kind.WALK).put(pos.immutable(), g.tickCount);
                com.claudemod.ClaudeMod.LOGGER.info("[Claude life] {} is one of his stations underground and he's up top with no stairs on file to it: not digging for it from here", pos.toShortString());
                return true;
            }
            g.tasks.addFirst(new Task.GoTo(pos, within)); // the walk again, after the step down
            // already on his stairs with the spot further down them: step by step along the spine to the one nearest it (never cutting "steps" through his own staircase)
            if (g.mineShape != null && !g.mineSpine.isEmpty() && com.claudemod.director.ClaudeLife.inHisMine(g, g.blockPosition())
                    && (com.claudemod.director.ClaudeLife.inHisMine(g, pos) || com.claudemod.director.ClaudeLife.touchesMine(g, pos))
                    && g.tickCount - spineWalkTick().getOrDefault(pos.immutable(), -100000) > 20 * 15) { // once per spot per 15 s: a step broken into a cave had this re-firing every few ticks
                spineWalkTick().put(pos.immutable(), g.tickCount);
                int here = 0, tgt = 0; double dh = Double.MAX_VALUE, dt = Double.MAX_VALUE;
                for (int i = 0; i < g.mineSpine.size(); i++) {
                    double a = g.mineSpine.get(i).distSqr(g.blockPosition()), b = g.mineSpine.get(i).distSqr(pos);
                    if (a < dh) { dh = a; here = i; }
                    if (b < dt) { dt = b; tgt = i; }
                }
                BlockPos tgtStep = g.mineSpine.get(tgt);
                if (g.level().getBlockState(tgtStep.below()).getCollisionShape(g.level(), tgtStep.below()).isEmpty()) { // that step has no floor (stairs broken into a cave): nothing to walk to; the give-up path handles it
                    com.claudemod.ClaudeMod.LOGGER.info("[Claude life] step {} at {} has no floor under it: not walking the stairs to {}", tgt, tgtStep.toShortString(), pos.toShortString());
                    // the end of his stairs hangs over a cavern: the mine's face comes back to the last step with ground under it and the stairs turn there (the planner sent him to "the end of my mine" over the void every 8 s, for ever)
                    int drop = 0; while (drop < 4 && g.level().getBlockState(tgtStep.below(1 + drop)).getCollisionShape(g.level(), tgtStep.below(1 + drop)).isEmpty()) drop++;
                    if (drop >= 3 && tgt > here && g.mineDir != null && !g.mineIsTunnel && !com.claudemod.entity.ClaudeSpawner.testing) { // (not in the test box: its stairs run out over the box's own air)
                        int m = tgt - 1;
                        while (m > here && g.level().getBlockState(g.mineSpine.get(m).below()).getCollisionShape(g.level(), g.mineSpine.get(m).below()).isEmpty()) m--;
                        BlockPos newFace = g.mineSpine.get(m);
                        while (g.mineSpine.size() > m + 1) g.mineSpine.remove(g.mineSpine.size() - 1); // the steps over the void come off the spine too (the route-to-mine code puts the face back at the spine's end otherwise)
                        g.mineFace = newFace.immutable();
                        g.badDigDirs.add(g.mineDir);
                        com.claudemod.ClaudeMod.LOGGER.info("[Claude life] his stairs end over a cavern ({}+ blocks of drop under step {}): the face is back at step {} ({}), turning there", drop, tgt, m, newFace.toShortString());
                        if (g.blockPosition().distSqr(newFace) > 2 * 2) { g.tasks.addFirst(new Task.GoTo(newFace, 1.6)); }
                        return true; // this walk (to the void) is over; the dig goes on from the face he's at
                    }
                } else {
                if (!g.level().getBlockState(pos).getCollisionShape(g.level(), pos).isEmpty() && !g.level().getBlockState(pos.above()).getCollisionShape(g.level(), pos.above()).isEmpty()) { // the "step" is solid rock: no stairs there (a phantom flight in his record had him up and down the real one every 15 s)
                    com.claudemod.ClaudeMod.LOGGER.info("[Claude life] {} is solid rock, not a step of his stairs: dropping that walk", pos.toShortString());
                    g.attempts.of(com.claudemod.entity.life.Attempts.Kind.WALK).put(pos.immutable(), g.tickCount);
                    return true;
                }
                g.tasks.pollFirst(); // (the walk itself goes back on after the steps)
                java.util.List<Task> legs = new java.util.ArrayList<>();
                int step = tgt >= here ? 4 : -4;
                for (int i = here + step; step > 0 ? i < tgt : i > tgt; i += step) { BlockPos sp = g.mineSpine.get(i); if (g.level().getBlockState(sp).getCollisionShape(g.level(), sp).isEmpty()) legs.add(new Task.GoTo(sp, 1.6)); } // (a step that's solid now, walled off or filled, is no waypoint: 20 s of walking into it each)
                // in a crater under his stairs the "nearest" step can be the one over his head: legs behind him (above when heading down, below when heading up) go, he's past them
                while (!legs.isEmpty() && legs.get(0) instanceof Task.GoTo lg && (step > 0 ? lg.pos().getY() >= g.getBlockY() + 2 : lg.pos().getY() <= g.getBlockY() - 2)) legs.remove(0);
                legs.add(new Task.GoTo(g.mineSpine.get(tgt), 1.6));
                legs.add(new Task.GoTo(pos, within));
                for (int i = legs.size() - 1; i >= 0; i--) g.tasks.addFirst(legs.get(i));
                com.claudemod.ClaudeMod.LOGGER.info("[Claude life] {} is further down his stairs: walking the steps to it ({} to {})", pos.toShortString(), here, tgt);
                return true;
                }
            }
            // a spot down in (or in the wall of) his own mine, and he's up top: the stairs are the way, never a fresh hole in the grass over it
            if (g.mineShape != null && !com.claudemod.director.ClaudeLife.inHisMine(g, g.blockPosition())
                    && (g.level().canSeeSky(g.blockPosition().above()) || g.mineSpine.stream().noneMatch(sp -> sp.distSqr(g.blockPosition()) <= 12 * 12)) // underground near his stairs (a crater under them, his bed pocket) he's not "up top": from there it's a walk, not a trip out and in by the entrance (40 blocks up and back, at night, empty-handed)
                    && (com.claudemod.director.ClaudeLife.inHisMine(g, pos) || com.claudemod.director.ClaudeLife.touchesMine(g, pos)
                        || (!g.level().canSeeSky(pos) && g.mineSpine.stream().anyMatch(sp -> sp.distSqr(pos) <= 10 * 10))) // (underground by his mine, on its record or not: his stairs are the way down; a chest off a branch the record had lost had him cutting a new stairway down from the grass)
                    && !g.mineSpine.isEmpty() && g.mineSpine.stream().anyMatch(sp -> sp.distSqr(pos) <= 48 * 48) // (any part of it: his mine's far end had moved 140 blocks past the chest he was walking to, and this rule switched off) // (a mine on file whose stairs don't go near the spot is no way to it: that looped every second)
                    && g.tickCount - spineWalkTick().getOrDefault(pos.immutable(), -100000) > 20 * 15) {
                spineWalkTick().put(pos.immutable(), g.tickCount);
                var rest = new java.util.ArrayList<>(g.tasks); g.tasks.clear(); // (a reorder, not a drop) the route goes FIRST (queued behind the failing walk it just re-fired every second, with him stood on the grass)
                if (!(com.claudemod.director.ClaudeLife.bpMode(g) && com.claudemod.director.ClaudeLife.bpWalkTo(g, pos))) { // (blueprint mine: in by the entrance and down its rows to the one by the spot; the route to the mine went to its far end first)
                com.claudemod.director.ClaudeLife.queueRouteToMine(g, (ServerLevel) g.level());
                int tgt = 0; double dt = Double.MAX_VALUE; // and the stairs down to the step nearest the spot, in the same go (the entrance alone, and the next walk went overland to the grass above the spot again)
                for (int i = 0; i < g.mineSpine.size(); i++) { double b = g.mineSpine.get(i).distSqr(pos); if (b < dt) { dt = b; tgt = i; } }
                for (int i = 4; i < tgt; i += 4) g.tasks.add(new Task.GoTo(g.mineSpine.get(i), 1.6));
                if (tgt > 0) g.tasks.add(new Task.GoTo(g.mineSpine.get(tgt), 1.6));
                }
                g.tasks.addAll(rest);
                stuck = 0;
                com.claudemod.ClaudeMod.LOGGER.info("[Claude life] {} is down in his mine: going in by the stairs, not digging for it from up here", pos.toShortString());
                return true;
            }
            if (descendStep(pos)) return true;
            g.tasks.pollFirst();
        }
        // Swimming in a pit with no way to climb out (the edge is too high from the water): tower up through it, like a player.
        // Deep water (no bottom to stand on), the way on is up and he can't climb out: a block in under him for footing, like a
        // player; standing, he gets on with it the normal way (a path out, or cutting steps), another block if he still floats.
        BlockPos feetB = BlockPos.containing(g.getX(), g.getY() + 0.1, g.getZ());
        boolean deep = !com.claudemod.entity.body.Controls.wading(g) && !g.level().getFluidState(feetB).isEmpty() && !g.level().getFluidState(feetB.below()).isEmpty();
        if (deep && stuck > 60 && pos.getY() - g.getBlockY() >= 2 && !walkNoDig && !scaffoldBlock().isEmpty()) {
            ServerLevel lv = (ServerLevel) g.level();
            // swimming, a jump never clears his own feet block, so no block goes under him; like a player he puts one in the water beside him,
            // at foot level, toward where he's going, and climbs onto it (a step), the roof over that cell opened first if it's low
            Direction dir = Direction.getNearest(pos.getX() + 0.5 - g.getX(), 0, pos.getZ() + 0.5 - g.getZ());
            BlockPos step = null;
            for (Direction sd : new Direction[] {dir, dir.getClockWise(), dir.getCounterClockWise(), dir.getOpposite()}) {
                BlockPos c = feetB.relative(sd);
                if (!lv.getFluidState(c).isEmpty() && lv.getBlockState(c).canBeReplaced() && !lv.getBlockState(c.below()).getCollisionShape(lv, c.below()).isEmpty()) { step = c.immutable(); break; }
            }
            if (step == null) return false; // nothing to set a step on beside him: the normal give-up
            BlockPos over = step.above(2);
            BlockState os = lv.getBlockState(over);
            boolean ceiling = !os.getCollisionShape(lv, over).isEmpty() && os.getDestroySpeed(lv, over) >= 0 && !os.hasBlockEntity() && lv.getFluidState(over.above()).isEmpty()
                    && !(g.getTarget() instanceof net.minecraft.server.level.ServerPlayer o && com.claudemod.director.ClaudeLife.protectedBlock(g, o, over));
            g.tasks.addFirst(new Task.GoTo(pos, within));
            g.tasks.addFirst(new Task.GoTo(step.above(), 0.9)); // up onto it
            g.tasks.addFirst(new Task.Place(step, scaffoldBlock().getItem()));
            if (ceiling) g.tasks.addFirst(new Task.Break(over.immutable()));
            g.pillarBlocks.addFirst(step); // (a step he put down: tidied up later like a tower)
            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] in deep water at {}, can't climb out: {}a stepping stone at {} to climb onto", g.blockPosition().toShortString(), ceiling ? "the roof over it out, then " : "", step.toShortString());
            return true;
        }
        // Way up there with no way to walk (underground, they're on the surface): dig a staircase up, like a player.
        var stepPath = g.getNavigation().getPath(); // (a one-point "route" straight up out of a hole is no route: no one jumps two blocks)
        int stepWait = com.claudemod.director.ClaudeLife.inHisMine(g, g.blockPosition()) ? 20 : 60; // in his own mine a gap in the stairs is a quick repair, not a 3s think
        if (pos.getY() - g.getBlockY() >= 2 && stuck > stepWait && (g.getNavigation().isDone() || !routeReaches || stepPath == null || stepPath.getNodeCount() <= 1) && g.onGround() && (!g.isInWater() || com.claudemod.entity.body.Controls.wading(g)) && !walkNoDig
                && !(Math.abs(pos.getY() - g.getBlockY()) > 6 && com.claudemod.director.ClaudeLife.inHisMine(g, g.blockPosition()) && stuck <= 120)) { // (in his mine: out by his stairs, not a new hole up; unless no route turns up in 6s, then he cuts his way like anyone would)
            if (stepPath != null && stepPath.getNodeCount() >= 4 && !stepPath.isDone() && pathGetsCloser(stepPath, Vec3.atCenterOf(pos))) { // a route part of the way: walk that first, cut steps only where it ends
                g.getNavigation().moveTo(stepPath, 0.9);
                stuck = 30;
                return false;
            }
            boolean plannerHadItsSay = d <= 28 && !(g.isInWater() && !com.claudemod.entity.body.Controls.wading(g)) && !(inHole() && g.isInWater()); // (the planner plans dug staircases itself within 28, out of a dry hole too (MAY_DIG there); deep water and a flooded hole are the cutter's)
            Boolean r = plannerHadItsSay || !mayDig(pos) ? null : digStepUp(pos, within); // the old cutter only where the planner can't look, and never where digging isn't allowed (it carved steps into a surface hill after a "no way")
            if (r != null) return r;
            boolean trappedBelow = !g.level().canSeeSky(g.blockPosition()) && g.inventory.find(s -> s.getItem() instanceof net.minecraft.world.item.PickaxeItem).isEmpty();
            if (trappedBelow && ticks % 40 == 1 && climbTheCave(pos, within)) return true; // underground with no pickaxe: the cave itself is the way up, bit by bit
        }
        /* (breaking through on its own at stuck 60: gone; a dig is a move of the planned way, in MAY_DIG only) */
        if (stuck == 40) { // a couple of seconds without getting closer: note it for the log
            ServerLevel lv = (ServerLevel) g.level();
            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] slow walk: at {} y={} -> {} (d {}), ground={} sneaking={} water={} path={} under him {}",
                    g.blockPosition().toShortString(), String.format("%.2f", g.getY()), pos.toShortString(), String.format("%.1f", d), g.onGround(), g.isShiftKeyDown(), g.isInWater(),
                    g.getNavigation().getPath() == null ? "none" : g.getNavigation().getPath().getNextNodeIndex() + "/" + g.getNavigation().getPath().getNodeCount(),
                    lv.getBlockState(g.blockPosition().below()).getBlock().getName().getString());
        }
        // Too far for one path (it plans ~30 blocks): a hop of ~20 toward it over the ground first, then this walk again
        double hx = pos.getX() + 0.5 - g.getX(), hz = pos.getZ() + 0.5 - g.getZ(), hd = Math.sqrt(hx * hx + hz * hz);
        if (stuck == 40 && (g.pillarBlocks.contains(standingOn()) || spire(standingOn())) && pos.getY() < g.getBlockY() - 1 && descendStep(pos)) return true; // up on his own pillar: down it first, a hop from up here goes nowhere
        boolean roofed = g.level().dimensionType().hasCeiling(); // (the Nether: the heightmap is the bedrock roof, so "at the surface" never held and long walks never hopped)
        if (stuck == 40 && hd > 28 && (roofed || g.getBlockY() >= g.level().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, g.getBlockX(), g.getBlockZ()) - 3)) { // (at the surface, an overhang over him or not: "sky over his head" left him stalled under a ledge 305 blocks from his mine)
            BlockPos mid = null;
            for (double ang : new double[] {0, 40, -40, 80, -80}) { // straight at it first; a hop that just failed: one off to the side instead
                double r = Math.toRadians(ang), dx = (hx * Math.cos(r) - hz * Math.sin(r)) / hd, dz = (hx * Math.sin(r) + hz * Math.cos(r)) / hd;
                int x = (int) Math.floor(g.getX() + dx * 20), z = (int) Math.floor(g.getZ() + dz * 20);
                BlockPos m = roofed ? com.claudemod.entity.life.FortressScout.alongRay(g, (ServerLevel) g.level(), Math.atan2(dz, dx), 20) // under a roof: ground at his height 20 out, never the roof, lava or fire
                        : new BlockPos(x, g.level().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
                if (m == null || m.distSqr(pos) <= 6 * 6) continue; /* (no ground that way, or the hop is the goal itself: live 23:56 "long walk to -450 49 57: hopping to -450 49 57 first" every 2 s) */
                Integer failed = null;
                for (var e : g.attempts.of(com.claudemod.entity.life.Attempts.Kind.WALK).entrySet()) if (e.getKey().distSqr(m) <= 9 && g.tickCount - e.getValue() < 20 * 60) { failed = e.getValue(); break; }
                if (failed == null) for (var e : hopsTried.entrySet()) if (e.getKey().distSqr(m) <= 9 && g.tickCount - e.getValue() < 20 * 60 && g.blockPosition().distSqr(e.getKey()) > 6 * 6) { failed = e.getValue(); break; } /* the same hop again within a minute, still far from it: it got him nowhere */
                if (failed == null) { mid = m; break; }
            }
            if (mid == null) return false; // every way round's failed lately: let this walk give up the normal way
            g.tasks.addFirst(new Task.GoTo(pos, within));
            g.tasks.addFirst(new Task.GoTo(mid, 4));
            hopsTried.put(mid.immutable(), g.tickCount); /* (its own record: in Attempts WALK the walker skipped the fresh hop as "failed 0 s ago", stage 371) */
            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] long walk to {} ({} blocks): hopping to {} first", pos.toShortString(), (int) hd, mid.toShortString());
            return true;
        }
        { var np = g.getNavigation().getPath(); if (np != null && np == walkPath && np.getNextNodeIndex() > walkNode && !np.isDone()) { stuck = 0; lastDist = Math.min(lastDist, d); } walkPath = np; walkNode = np == null ? -1 : np.getNextNodeIndex(); } // on along his route counts as headway (round his house to its door the line to the bed gets no shorter for seconds)
        if (d < lastDist - 0.5) { lastDist = d; stuck = 0; } else if (++stuck > STUCK_LIMIT) {
            // Can't get to this spot: skip just the work tied to it (up to the next walk), keep the rest of the job,
            // and grab whatever already dropped around him first.
            // Later walks to the same spot (or right next to it) in this job would fail the same way: drop those too.
            g.attempts.of(com.claudemod.entity.life.Attempts.Kind.WALK).put(pos.immutable(), g.tickCount);
            // A walk to a block he'll break (a trunk, ore): the Breaks get there their own way (tower, dig, bridge); keep them.
            boolean toABlock = !g.level().getBlockState(pos).getCollisionShape(g.level(), pos).isEmpty();
            boolean leg = com.claudemod.entity.nav.Route.isLeg(g, pos);
            if (leg) com.claudemod.entity.nav.Route.legFailed(g, pos);
            if (!toABlock) g.tasks.removeIf(x -> x instanceof Task.GoTo n ? n.pos().distSqr(pos) <= 2 // only the work tied to that spot: everything else (an ore vein, the next legs) stays
                    : x instanceof Task.Place pl ? pl.pos().distSqr(pos) <= 16 && g.pendingPatches.add(pl.pos().immutable()) // (a patch there isn't forgotten: done when he's back by it) : x instanceof Task.WallTorch w ? w.pos().distSqr(pos) <= 16
                    : x instanceof Task.SealStep ss && ss.feet().distSqr(pos) <= 16);
            if (!leg && !toABlock && g.blueprint != null && !g.blueprint.isEmpty() && (pos.distSqr(g.blueprint.entrance) <= 9 || pos.distSqr(g.blueprint.outside()) <= 9) && !g.blueprint.touches(g.blockPosition())) { // the way into his mine is out of reach: the legs down it behind this walk go too (157 of them failed one by one, 7 s each)
                if (!g.attempts.within(com.claudemod.entity.life.Attempts.Kind.SENT_FROM, g.blueprint.entrance, g.tickCount, 20 * 120)) { g.attempts.fail(com.claudemod.entity.life.Attempts.Kind.SENT_FROM, g.blueprint.entrance, g.tickCount); g.attempts.of(com.claudemod.entity.life.Attempts.Kind.WALK).remove(pos); g.tasks.addFirst(new Task.GoTo(pos, within)); com.claudemod.ClaudeMod.LOGGER.info("[Claude life] his mine's way in at {} is a long walk: going again, by the surface", pos.toShortString()); return true; } // (the user: path to the entrance; dropping the legs left only a dig straight down)
                int before = g.tasks.size(); g.tasks.removeIf(x -> x instanceof Task.GoTo n && g.blueprint.touches(n.pos()));
                com.claudemod.ClaudeMod.LOGGER.info("[Claude life] can't get to his mine's way in at {}: the {} legs down it dropped", g.blueprint.entrance.toShortString(), before - g.tasks.size());
            }
            g.tasks.addFirst(new Task.Pickup(60));
            ServerLevel lv = (ServerLevel) g.level();
            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] couldn't get to {} ({} on {}; he's at {}), skipping that spot",
                    pos.toShortString(), lv.getBlockState(pos).getBlock().getName().getString(),
                    lv.getBlockState(pos.below()).getBlock().getName().getString(), g.blockPosition().toShortString());
            return true;
        }
        return false;
    }

    /** Cover right now (a tower, or two down and a lid), whatever is or isn't on him: for the planner's "shelter". True if he could. */
    /**
     * Under cover for the night: in his hole (or walled tower, or box), the cover's wait queued or running, night out there and
     * him not geared for it. Nothing else runs till first light or a threat moves him.
     */
    /** His work in place inside the night's hold (the furnace in the hole wall, its cooking): the hold holds through it. */
    public final java.util.Set<Task> holdWork = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    /** Queued to run right after the night's wait in his cover (first light): the stations he set in its wall come back out. */
    public void afterHold(java.util.List<Task> ts) {
        if (digInTasks == null) return;
        java.util.List<Task> all = new java.util.ArrayList<>(g.tasks); int at = -1;
        for (int i = 0; i < all.size(); i++) { Task x = all.get(i); if (x instanceof Task.Wait && digInTasks.stream().anyMatch(y -> y == x)) { at = i; break; } }
        all.addAll(at + 1, ts); g.tasks.clear(); g.tasks.addAll(all);
    }
    /**
     * In his finished cover (the night's, or a box against a skeleton): its wait on (or a bite in the middle of it, or the work in
     * place inside the night's hold). One state: no other job runs, plans through it or takes its blocks back till the cover's own
     * steps take it down (live 04:42: a hunt's step and the box's walls booked in the same cells, the wait cut to 7 s).
     */
    public boolean holdingCover() {
        if (digInAt == null || digInTasks == null || !inDigInHole()) return false;
        Task next = current != null ? current : g.tasks.peek(); boolean inPlace = next != null && holdWork.contains(next);
        for (Task x : digInTasks) if (x instanceof Task.Wait && (x == current || current instanceof Task.Eat && g.tasks.contains(x) || current == null && g.tasks.peek() == x || inPlace && g.tasks.contains(x))) return true; // (the cover finished and its wait on, or a bite in the middle of it: while he's still digging or tucking in, the rest of him runs, the stuck handler too)
        return false;
    }
    public boolean holdingNight() {
        if (!holdingCover()) return false;
        return com.claudemod.director.ClaudeLife.nightOutsideNo(g, (ServerLevel) g.level(), digInAt.above(Math.max(0, digInRise) + 3)) != null; // (judged above the cover: at a tower's foot the tower itself shades the cell, it read as "underground" and the hold never started)
    }
    /** A cell of the cover he's making or in (its blocks, its digs): no other job's step, route or take-back touches it. */
    public boolean coverCell(BlockPos p) { return coverCellsNow().contains(p); }
    private java.util.Set<BlockPos> coverCells; private int coverCellsTick = -1; private Object coverCellsFor; // (once a tick per cover: the route search asks per cell; a cover queued mid-tick is its own)
    public java.util.Set<BlockPos> coverCellsNow() {
        if (coverCells != null && coverCellsTick == g.tickCount && coverCellsFor == digInTasks) return coverCells;
        coverCellsFor = digInTasks;
        java.util.Set<BlockPos> s = new java.util.HashSet<>();
        if (diggingIn()) for (Task x : digInTasks) { if (x instanceof Task.Place pl) s.add(pl.pos().immutable()); else if (x instanceof Task.Break b) s.add(b.pos().immutable()); }
        coverCells = s; coverCellsTick = g.tickCount;
        return s;
    }
    /** Covers he has made (box, tower, hole, staying up his tower): one threat, one cover; the stages count them. */
    public int coversMade;
    private int holdLogged = -1;

    /**
     * A covered 1x1 pit he's standing in, dug down from the open surface (rock round his feet, head and floor, a lid with open sky
     * over it, not his tower): his cover from before a restart (its state lives here and died with it; at dawn he couldn't path out
     * and dug about for minutes). Taken back as his cover, with its wait and its way out: the lid out, the climb up.
     */
    private void adoptPitCover() {
        if (current instanceof Task.Pillar || current instanceof Task.Break || g.tasks.peek() instanceof Task.Pillar) return;
        ServerLevel level = (ServerLevel) g.level();
        BlockPos f = g.blockPosition(), lid = f.above(2);
        java.util.function.Predicate<BlockPos> solid = q -> !level.getBlockState(q).getCollisionShape(level, q).isEmpty();
        if (solid.test(f) || solid.test(f.above()) || !solid.test(lid) || !solid.test(f.below()) || !level.getFluidState(f).isEmpty()) return;
        for (Direction d : Direction.Plane.HORIZONTAL) if (!solid.test(f.relative(d)) || !solid.test(f.above().relative(d)) || !solid.test(f.below().relative(d))) return; // (ground all round, under his feet too: a walled hut up his tower stands on a bare column)
        if (!level.canSeeSky(lid.above()) || g.pillarBlocks.contains(f.below())) return; // dug from the open surface: a box in a cave or his mine is no pit of his
        BlockPos top = lid.above();
        digInAt = top.immutable(); digInTick = g.tickCount; digInRise = 0;
        java.util.List<Task> steps = new java.util.ArrayList<>(java.util.List.of(new Task.Wait(400, false), new Task.Break(lid.immutable()), new Task.Pillar(top.getY() - f.getY())));
        digInTasks = steps;
        if (current != null) { clearCrack(); g.tasks.addFirst(current); current = null; g.currentTask = null; }
        for (int i = steps.size() - 1; i >= 0; i--) g.tasks.addFirst(steps.get(i));
        com.claudemod.ClaudeMod.LOGGER.info("[Claude life] in a covered pit at {} (his cover, its state lost to a restart): its wait and its way out again (the lid at {}, {} up)", f.toShortString(), lid.toShortString(), top.getY() - f.getY());
    }

    /** The steps of the cover he's in right now (its wait, the lid out, the climb up), or none: a new job never drops them. */
    public java.util.List<Task> coverTasks() { return digInAt != null && digInTasks != null && inDigInHole() ? digInTasks : java.util.List.of(); }

    public boolean shelter() {
        if (diggingIn()) return true;
        if (current != null) { clearCrack(); g.tasks.addFirst(current); current = null; g.currentTask = null; }
        desperateNow = true;
        boolean r = digInFromArrows();
        desperateNow = false;
        return r;
    }

    /** Blocks of a way out he's cutting: these get punched through by hand if he has no pickaxe (slow, no drops: a trapped player does it anyway). */
    private java.util.Set<BlockPos> punchThrough; // (lazy: fields added live start null)
    private java.util.Set<BlockPos> punchThrough() { if (punchThrough == null) punchThrough = new java.util.HashSet<>(); if (punchThrough.size() > 200) punchThrough.clear(); return punchThrough; }

    /**
     * Underground with no pickaxe and the way out far above: do what a player does, follow the cave upward. The highest spot
     * within reach that he can walk to (a ledge, a slope, a higher passage) becomes the next leg, then this walk again.
     */
    private BlockPos lastCaveLeg;
    private boolean climbTheCave(BlockPos dest, double within) {
        ServerLevel level = level();
        BlockPos at = g.blockPosition();
        if (lastCaveLeg != null && lastCaveLeg.distSqr(at) > 3 * 3) g.attempts.fail(com.claudemod.entity.life.Attempts.Kind.WALK, lastCaveLeg, g.tickCount); // back here and not up on the last leg: that leg didn't work
        BlockPos best = null; double bestScore = 0;
        for (BlockPos q0 : BlockPos.betweenClosed(at.offset(-14, 1, -14), at.offset(14, 14, 14))) {
            if (q0.getY() <= at.getY() + 1 || q0.equals(lastCaveLeg)) continue;
            { Integer f0 = g.attempts.any.get(q0.immutable()); if (f0 != null && g.tickCount - f0 < 20 * 120) continue; } // a leg he failed to walk to lately: not again (two legs took turns for two minutes, neither reached, while a tree on flat ground waited)
            if (!level.getBlockState(q0).getCollisionShape(level, q0).isEmpty() || !level.getBlockState(q0.above()).getCollisionShape(level, q0.above()).isEmpty()) continue;
            if (!level.getBlockState(q0.below()).isFaceSturdy(level, q0.below(), Direction.UP) || !level.getFluidState(q0).isEmpty()) continue;
            double score = (q0.getY() - at.getY()) * 4.0 - Math.sqrt(q0.distSqr(at)) * 0.5 - Math.sqrt(q0.distSqr(dest)) * 0.05; // up above all, then near, then toward it
            if (score <= bestScore) continue;
            var path = g.getNavigation().createPath(q0, 0);
            if (path == null || !path.canReach()) continue;
            best = q0.immutable(); bestScore = score;
        }
        if (best == null) return false;
        lastCaveLeg = best;
        g.tasks.removeIf(t -> t instanceof Task.GoTo gg && gg.pos().equals(dest)); // (not another copy of the walk every 2 s: that queue reached 166)
        g.tasks.addFirst(new Task.GoTo(dest, within));
        g.tasks.addFirst(new Task.GoTo(best, 0.8));
        com.claudemod.ClaudeMod.LOGGER.info("[Claude life] no pickaxe and no way up: following the cave up to {} first", best.toShortString());
        return true;
    }

    /**
     * One step of a staircase up toward dest: clear head room and the next step, put a block under it if
     * there's none, then step up. Returns true to end this walk (it's re-queued after the digging),
     * false/null to carry on walking; null when digging up can't help (lava, bedrock...).
     */
    private Direction stepUpDir; // the way his last step up went: the next one carries on straight, a real staircase
    private Vec3 stepUpTo; private int stepUpTick = -100;
    private BlockPos pendingStepUp; // a step being cut: he hops onto it as soon as the walk resumes

    private Boolean digStepUp(BlockPos dest, double within) {
        ServerLevel level = (ServerLevel) g.level();
        BlockPos feet = g.blockPosition();
        { Integer noStep = g.attempts.last(com.claudemod.entity.life.Attempts.Kind.STEP_UP, feet); if (noStep != null && g.tickCount - noStep < 20 * 120) return null; } // steps up from here didn't take a moment ago: another way
        if (com.claudemod.director.ClaudeLife.bpMode(g) && g.blueprint != null && !g.blueprint.isEmpty() && dest.getY() > feet.getY() + 1) { // blueprint mode: up and out is along his mine's rows (the user: to reach the surface he goes up his staircase), never a second flight cut beside it
            var b = g.blueprint; int from = b.nearestRow(feet), to = b.nearestRow(dest);
            BlockPos fromCell = b.rowCell(from);
            Integer sentFromHere = g.attempts.last(com.claudemod.entity.life.Attempts.Kind.SENT_FROM, feet);
            if (fromCell != null && to >= 0 && from != to && fromCell.distSqr(feet) <= 8 * 8 && (sentFromHere == null || g.tickCount - sentFromHere > 20 * 90)) { // in or beside the mine (a bedroom pocket off the stairs counts), and not sent this way from this spot a minute ago
                g.attempts.fail(com.claudemod.entity.life.Attempts.Kind.SENT_FROM, feet, g.tickCount);
                g.tasks.removeIf(t -> t instanceof Task.GoTo gg && gg.pos().equals(dest));
                g.tasks.addFirst(new Task.GoTo(dest, within));
                java.util.List<BlockPos> cells = b.walkCells(from, to);
                if (cells.size() > 6 && feet.distSqr(dest) <= 16 * 16 && cells.size() > 2 + Math.sqrt(feet.distSqr(dest)) / 2) { /* (only a spot close by: the surface 66 blocks up from his y-16 tunnel IS a walk up his stairs; refused, every walk out ended at once and he sat in his mine) */ g.tasks.removeIf(t -> t instanceof Task.GoTo gg && gg.pos().equals(dest)); g.attempts.fail(com.claudemod.entity.life.Attempts.Kind.WALK, dest, g.tickCount); com.claudemod.ClaudeMod.LOGGER.info("[Claude life] no way up from {} to {} ({} blocks off) but by {} hops round his mine: leaving it", feet.toShortString(), dest.toShortString(), (int) Math.sqrt(feet.distSqr(dest)), cells.size()); return true; } // a spot 2 blocks off is never worth a trip round the whole mine (rows 450 to 88 sent him from y16 down to y-58 and back for a plug above his branch)
                for (int k = cells.size() - 1; k >= 0; k--) g.tasks.addFirst(new Task.GoTo(cells.get(k), 0.8));
                com.claudemod.ClaudeMod.LOGGER.info("[Claude life] no way up from {}: along his mine's rows {} to {} ({} hops) and on to {}, not cutting new steps", feet.toShortString(), from, to, cells.size(), dest.toShortString());
                return true;
            }
            if (fromCell != null && fromCell.distSqr(feet) <= 8 * 8) { // beside his mine and the rows don't help (sent that way a minute ago): no one-wide flight cut up through the rock beside his stairs, ever (the user's zigzag)
                g.attempts.fail(com.claudemod.entity.life.Attempts.Kind.STEP_UP, feet, g.tickCount);
                com.claudemod.ClaudeMod.LOGGER.info("[Claude life] no way up from {} beside his mine: not cutting a flight next to his stairs; that way is off for now", feet.toShortString());
                return null;
            }
        }
        if (g.stairStart != null && g.mineSpine.size() >= 4 && dest.getY() > feet.getY() + 3 && dest.getY() >= g.stairStart.getY() - 6) { // the way up is his staircase (the user: "to reach the surface he has to go up his staircase"): over to it and up it, never a new flight cut through the rock
            BlockPos near = null; double nd = 24 * 24;
            for (BlockPos sp : g.mineSpine) { double d = sp.distSqr(feet); if (d < nd && level.getBlockState(sp).getCollisionShape(level, sp).isEmpty()) { nd = d; near = sp; } }
            Integer failed = g.attempts.of(com.claudemod.entity.life.Attempts.Kind.WALK).get(g.stairStart);
            Integer sentFromHere = g.attempts.last(com.claudemod.entity.life.Attempts.Kind.SENT_FROM, feet);
            if (sentFromHere != null && g.tickCount - sentFromHere < 20 * 90) near = null; // sent to his stairs from this very spot a minute ago and back here: that way up doesn't work (a broken join in his stairs record), so not again (it looped every 12 s)
            if (near != null && nd > 2 * 2 && (failed == null || g.tickCount - failed > 20 * 120)) {
                g.attempts.fail(com.claudemod.entity.life.Attempts.Kind.SENT_FROM, feet, g.tickCount);
                g.tasks.removeIf(t -> t instanceof Task.GoTo gg && gg.pos().equals(dest));
                g.tasks.addFirst(new Task.GoTo(dest, within));
                g.tasks.addFirst(new Task.GoTo(g.stairStart, 1.6));
                g.tasks.addFirst(new Task.GoTo(near, 1.2));
                com.claudemod.ClaudeMod.LOGGER.info("[Claude life] no way up from {}: over to his stairs at {} and up them, not cutting new steps", feet.toShortString(), near.toShortString());
                return true;
            }
        }
        // Far off: toward it. Mostly straight up: on the way the last step went (never back over the hole he came up),
        // else a side with rock to stand on (he may have no blocks to make a floor).
        double hx = dest.getX() + 0.5 - g.getX(), hz = dest.getZ() + 0.5 - g.getZ();
        java.util.List<Direction> ways = new java.util.ArrayList<>();
        if (Math.abs(hx) + Math.abs(hz) >= Math.max(1.5, dest.getY() - g.getY())) ways.add(Direction.getNearest(hx, 0, hz));
        if (stepUpDir != null) ways.add(stepUpDir);
        ways.add(g.getDirection());
        ways.addAll(Direction.Plane.HORIZONTAL.stream().toList());
        Direction dir = ways.get(0);
        for (Direction w : ways) {
            BlockPos fl = feet.relative(w), st = fl.above();
            boolean wet = !level.getFluidState(st).isEmpty() || !level.getFluidState(st.above()).isEmpty();
            if (!wet && level.getBlockState(fl).isFaceSturdy(level, fl, Direction.UP) && level.getBlockState(st).getDestroySpeed(level, st) >= 0) { dir = w; break; }
        }
        stepUpDir = dir;
        BlockPos step = feet.relative(dir).above();
        List<BlockPos> dig = new java.util.ArrayList<>();
        for (int k = 2; k <= 4; k++) if (level.getBlockState(step.above(k)).getBlock() instanceof net.minecraft.world.level.block.FallingBlock) { // gravel over the step's head room: it'll fall in as he cuts; it comes down first, from the bottom
            dig.add(step.above(k)); break;
        }
        for (BlockPos b : new BlockPos[] {feet.above(2), step, step.above()}) {
            if (com.claudemod.director.ClaudeLife.nookShell(g, b)) { com.claudemod.ClaudeMod.LOGGER.info("[Claude life] no step up from {} toward {}: {} is his nook's shell (its wall or roof): not cut for a step (WorkGoal.digStepUp)", feet.toShortString(), dest.toShortString(), b.toShortString()); g.attempts.fail(com.claudemod.entity.life.Attempts.Kind.STEP_UP, feet, g.tickCount); return null; }
            BlockState bs = level.getBlockState(b);
            if (!bs.getFluidState().isEmpty()) return null; // water or lava up there: not this way
            if (bs.getCollisionShape(level, b).isEmpty()) continue;
            if (bs.getDestroySpeed(level, b) < 0 || bs.hasBlockEntity()) return null;
            if (bs.requiresCorrectToolForDrops() && g.inventory.find(st -> st.isCorrectToolForDrops(bs)).isEmpty()) return null; // no tool that takes it (stone with bare hands: 23 s a step, nothing dropping, up a cliff after a death): not this way
            dig.add(b);
        }
        BlockPos floor = step.below();
        boolean needFloor = !level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP);
        if (!dig.isEmpty() || needFloor) {
            // ponytail: keyed far above the world in placeRetries (no new fields live); a cut that never sticks (water refilling it) gives up after 3, not 20 tries in a minute
            BlockPos cutKey = feet.above(10000).immutable();
            if (placeRetries.merge(cutKey, 1, Integer::sum) > 3) {
                placeRetries.remove(cutKey);
                g.attempts.of(com.claudemod.entity.life.Attempts.Kind.WALK).put(dest.immutable(), g.tickCount);
                g.attempts.fail(com.claudemod.entity.life.Attempts.Kind.STEP_UP, feet, g.tickCount); // (and no step up from HERE for a while, whatever the destination: the next walk retried the same three cuts, 2 minutes in one spot)
                com.claudemod.ClaudeMod.LOGGER.info("[Claude life] cut a step up at {} three times and it didn't take: {} is off for now", feet.toShortString(), dest.toShortString());
                return null;
            }
            g.tasks.addFirst(new Task.GoTo(dest, within)); // keep going after this step is cut
            if (needFloor) {
                ItemStack block = scaffoldBlock();
                if (block.isEmpty()) return null;
                boolean against = false; // a block can't go in midair: something has to be next to it
                for (Direction dd : Direction.values()) if (!level.getBlockState(floor.relative(dd)).getCollisionShape(level, floor.relative(dd)).isEmpty()) { against = true; break; }
                if (!against) return null;
                g.tasks.addFirst(new Task.Place(floor, block.getItem()));
            }
            for (int i = dig.size() - 1; i >= 0; i--) { g.tasks.addFirst(new Task.Break(dig.get(i))); punchThrough().add(dig.get(i).immutable()); }
            pendingStepUp = step.immutable();
            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] no way up: cutting a step up at {}{}", g.blockPosition().toShortString(), g.tickCount - mapTick > 20 * 30 ? miniMap(dest) : "");
            return true;
        }
        // all cut: step up onto it
        placeRetries.remove(feet.above(10000)); // (that cut took: its count starts over)
        g.getNavigation().stop();
        stepUpTo = Vec3.atBottomCenterOf(step); stepUpTick = g.tickCount;
        g.getMoveControl().setWantedPosition(step.getX() + 0.5, step.getY(), step.getZ() + 0.5, 1.0);
        g.getJumpControl().jump();
        stuck = 0;
        return false;
    }

    /** Players look where they are going: the next step of the path, or the destination. */
    private void lookWhereWalking(BlockPos dest) {
        var path = g.getNavigation().getPath();
        Vec3 at = null;
        if (path != null && !path.isDone()) // a point a couple of blocks up the path: the very next one is often under his feet, and looking at it spins him round
            for (int i = path.getNextNodeIndex(); i < path.getNodeCount() && at == null; i++) {
                Vec3 n = Vec3.atBottomCenterOf(path.getNodePos(i));
                if (Math.hypot(n.x - g.getX(), n.z - g.getZ()) >= 2.0) at = n.add(0, 1.4, 0);
            }
        if (at == null) {
            if (Math.hypot(dest.getX() + 0.5 - g.getX(), dest.getZ() + 0.5 - g.getZ()) < 1.5) return; // right there: no need to turn
            at = Vec3.atCenterOf(dest);
        }
        g.getLookControl().setLookAt(at.x, at.y, at.z, 20.0f, 20.0f);
    }

    /** Pathing won't finish a short step (stair steps, ledges): walk straight onto it, hopping up if it's higher. */
    private void stepTo(BlockPos pos) {
        ServerLevel level = (ServerLevel) g.level();
        if (!(level.getBlockState(pos).isAir() && level.getBlockState(pos.above()).isAir() && level.getBlockState(pos.below()).isSolid())) return;
        g.getNavigation().stop();
        g.getMoveControl().setWantedPosition(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 1.0);
        if (pos.getY() > g.getBlockY() && g.onGround()) g.getJumpControl().jump();
    }

    private boolean inReach(BlockPos pos) {
        return g.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) <= 4.25; // 4.25 to the centre: every face he clicks stays inside the 4.75 his hands allow (4.0 left a mine-wall hole "out of reach" that a player reaches with a step)
    }

    private int approachTries;
    private BlockPos lastPillarFor;

    /**
     * Out of reach, the way a player handles it: walk to a spot that can reach it; if walking can't get
     * there and it's overhead, tower up under it; otherwise skip it. Returns true to skip the block.
     */
    /**
     * A block to break that is out of reach: the vanilla path to a spot beside it first; leaves in the way punched; else one
     * planned way (digging allowed: a player tunnels to ore, towers under a block overhead, bridges a gap; his mine's shell, his
     * house and farm stay walls) to a cell within reach, then the block, then any tower back down. No way: one line, marked off.
     */
    private boolean approach(BlockPos pos) {
        if (ticks == 1) approachTries = 0;
        if (!g.getNavigation().isDone()) {
            lookWhereWalking(pos);
            return false;
        }
        if (ticks % 6 != 1) return false; // re-think a few times a second, not every tick (pathfinding is costly)
        if (++approachTries > 3) { g.attempts.of(com.claudemod.entity.life.Attempts.Kind.REACH).put(pos.immutable(), g.tickCount); com.claudemod.ClaudeMod.LOGGER.info("[Claude life] gave up reaching {} (he's at {})", pos.toShortString(), g.blockPosition().toShortString()); return true; }
        BlockPos spot = standSpotBeside(pos); // never path "into" a solid block (vanilla would aim at the air above it)
        var path = g.getNavigation().createPath(spot, spot.equals(pos) ? (int) REACH - 1 : 1);
        if (path != null && path.canReach()) {
            g.getNavigation().moveTo(path, 0.9);
            return false;
        }
        // Leaves in the way (a bushy tree, a low canopy): punch through them like a player, then try again.
        BlockPos leaf = leavesToward(pos);
        if (leaf != null) {
            approachTries--;
            g.tasks.addFirst(new Task.Break(pos));
            g.tasks.addFirst(new Task.Break(leaf));
            return true;
        }
        // The planned way, once per block per 3 s: a route to a cell within reach (digs, a tower, a bridge as moves), the block, the tower back down.
        ServerLevel level = (ServerLevel) g.level();
        BlockPos feet = BlockPos.containing(g.getX(), g.getY() + 0.2, g.getZ());
        if (pos.getY() < feet.getY() - 1 && g.pillarBlocks.contains(feet.below())) { // up his own tower, the block down below: climb down first, like a player (from a 1x1 top there's no way down on foot, and he gave up on his own put blocks)
            g.tasks.addFirst(new Task.Break(pos));
            g.tasks.addFirst(new Task.Pillar(-1));
            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] {} at {} is below him: down his tower first", name(level.getBlockState(pos)), pos.toShortString());
            return true;
        }
        if (g.tickCount - plannedAt().getOrDefault(pos.immutable(), -100000) > 60) {
            plannedAt().put(pos.immutable(), g.tickCount); if (plannedAt().size() > 64) plannedAt().clear();
            long t0 = System.nanoTime();
            ItemStack scaff = scaffoldBlock();
            var plan = com.claudemod.entity.nav.ActionPath.plan(g, level, feet, pos, 3.2, !scaff.isEmpty(), 3000, com.claudemod.entity.nav.ActionPath.Mode.MAY_DIG, g.attempts.of(com.claudemod.entity.life.Attempts.Kind.WALK), g.tickCount);
            if (plan != null) {
                var tasks = com.claudemod.entity.nav.ActionPath.tasks(plan, feet, scaff.isEmpty() ? net.minecraft.world.item.Items.COBBLESTONE : scaff.getItem(), pos, 3.2, level);
                if (plan.puts() > 0) { for (var a : plan.acts()) if (a instanceof com.claudemod.entity.nav.ActionPath.Act.Put pt) g.pillarBlocks.addFirst(pt.pos().immutable()); g.tasks.addFirst(new Task.Pillar(-1)); } // every block the way puts down is his to take back (a tower under a block overhead comes down once it's out; the Pillar(-1) is kept while a Break in reach is queued)
                g.tasks.addFirst(new Task.Break(pos));
                for (int i = tasks.size() - 1; i >= 0; i--) g.tasks.addFirst(tasks.get(i));
                com.claudemod.ClaudeMod.LOGGER.info("[Claude life] no way to the {} at {} on foot: made one ({} digs, {} blocks put down, {} walks; {} ms)", name(level.getBlockState(pos)), pos.toShortString(), plan.digs(), plan.puts(), plan.walks(), (System.nanoTime() - t0) / 1000000);
                return true;
            }
        }
        g.attempts.of(com.claudemod.entity.life.Attempts.Kind.REACH).put(pos.immutable(), g.tickCount);
        com.claudemod.ClaudeMod.LOGGER.info("[Claude life] can't get at {} from {} (no way to it, digging allowed): leaving it", pos.toShortString(), g.blockPosition().toShortString());
        return true;
    }

    /**
     * Something solid right in front of him, between him and where he's going (a fence he boxed himself in
     * with, a wall): queue breaking it. Never their builds, storage or workstations. True if he will dig.
     */
    /** What ore drops: diamonds, emeralds, raw metal, coal, redstone, lapis, quartz. */
    /**
     * A valuable drop by his own mine that his sweep can't get to (sealed behind his wall, up a hole): never a gift. It's on his book
     * as LEFT, the sweeps leave it, and recover_left goes back for it the player's way (through his patch, MAY_REOPEN, the patch
     * back after). True when that's what happened (live 12:15 a diamond behind his patch was marked a gift and left).
     */
    private boolean leaveOwn(net.minecraft.world.entity.item.ItemEntity it) {
        if (!isOreDrop(it.getItem()) || !com.claudemod.director.ClaudeLife.bpMode(g) || g.blueprint == null || g.blueprint.isEmpty()) return false;
        boolean byMine = false; for (BlockPos q : BlockPos.betweenClosed(it.blockPosition().offset(-2, -2, -2), it.blockPosition().offset(2, 2, 2))) if (g.blueprint.touches(q)) { byMine = true; break; }
        if (!byMine) return false;
        if (g.blueprint.isWalk(it.blockPosition())) { com.claudemod.ClaudeMod.LOGGER.info("[Claude life] {} at {} lies in his own walkway, not behind his wall: the sweeps (and a patch's hold) keep going for it, not recover_left (WorkGoal.leaveOwn)", it.getItem().getHoverName().getString(), it.blockPosition().toShortString()); return false; } // (stage 79: a raw iron on the step under him was left to recover_left, the patch's hold skipped it, and it stayed on his stairs)
        boolean again = com.claudemod.entity.life.OwnEvents.waitFor(g, it);
        String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(it.getItem().getItem()).getPath();
        if (again && g.skillQueue.stream().noneMatch(s -> s.skill().equals("recover_left"))) g.skillQueue.addFirst(new com.claudemod.entity.life.Skills.Step("recover_left", id, it.getItem().getCount()));
        com.claudemod.ClaudeMod.LOGGER.info("[Claude life] {} at {} by his own mine: his, not a gift; the sweeps leave it, {} (WorkGoal.leaveOwn)", it.getItem().getHoverName().getString(), it.blockPosition().toShortString(), again ? "recover_left goes back for it through his wall" : "three goes at it already: on his book, the hole over it held open");
        return true;
    }
    private static boolean isOreDrop(ItemStack s) {
        String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
        return id.equals("diamond") || id.equals("emerald") || id.startsWith("raw_") || id.equals("coal") || id.equals("redstone")
                || id.equals("lapis_lazuli") || id.equals("quartz") || id.endsWith("_ore") || id.equals("ancient_debris");
    }

    private boolean digThroughToward(Vec3 target) {
        ServerLevel level = (ServerLevel) g.level();
        Vec3 to = target.subtract(g.position());
        if (com.claudemod.director.ClaudeLife.bpMode(g) && g.blueprint != null && !g.blueprint.isEmpty()) { // down a hole under his own tunnel floor (an ore taken out of it): up out of it the player's way, a block under him per step, which puts the floor back too (he tried to break the floor beside him, the wall guard said no, round and round)
            BlockPos feet = g.blockPosition(); int up = 0;
            if (!g.blueprint.isWalk(feet)) for (int k = 1; k <= 3; k++) if (g.blueprint.isWalk(feet.above(k)) && g.blueprint.roleAt(feet.above(k - 1)) == com.claudemod.entity.life.MineBlueprint.Role.FLOOR) { up = k; break; }
            if (up > 0 && !(current instanceof Task.Pillar) && !(g.tasks.peek() instanceof Task.Pillar) && !scaffoldBlock().isEmpty()) { g.tasks.addFirst(new Task.Pillar(up)); com.claudemod.ClaudeMod.LOGGER.info("[Claude life] in a hole under his tunnel floor at {}: up {} the way he'd climb out, the floor back under him", feet.toShortString(), up); return true; }
        }
        Direction dir = Direction.getNearest(to.x, 0, to.z);
        if (to.horizontalDistance() < 1.0) { // (close by: which cell it's in decides, not the line; a drop in the hole beside his feet is under a block off, and that left raw gold behind, World 15 15:17)
            BlockPos ic = BlockPos.containing(target); int ddx = ic.getX() - g.getBlockX(), ddz = ic.getZ() - g.getBlockZ();
            if (Math.abs(ddx) + Math.abs(ddz) != 1) return false;
            dir = Direction.getNearest(ddx, 0, ddz);
        }
        BlockPos next = g.blockPosition().relative(dir);
        List<BlockPos> dig = new java.util.ArrayList<>();
        for (BlockPos b : new BlockPos[] {next.above(), next}) {
            BlockState bs = level.getBlockState(b);
            if (bs.getCollisionShape(level, b).isEmpty()) continue;
            boolean theirs = g.getTarget() instanceof net.minecraft.server.level.ServerPlayer o
                    && (com.claudemod.context.PlayerProfile.placedBy(o).contains(b) || com.claudemod.director.ClaudeLife.protectedBlock(g, o, b));
            if (bs.getDestroySpeed(level, b) < 0 || theirs || (bs.hasBlockEntity() && !com.claudemod.director.ClaudeLife.isTempStation(g, b))) return false;
            if (g.farm != null && g.farm.touches(b)) return false; // his farm's fence: the gate is the way out, never a hole in the ring
            if (com.claudemod.director.ClaudeLife.houseMode(g) && g.house != null && g.house.isStructure(b)) return false; // his house: the door
            for (Direction d : Direction.values()) if (level.getFluidState(b.relative(d)).isSource() && (!g.isInWater() || level.getFluidState(b.relative(d)).is(net.minecraft.tags.FluidTags.LAVA))) return false; // it's holding water/lava back: leave it (already swimming: water let out can't flood him more)
            dig.add(b.immutable());
        }
        if (dig.isEmpty()) return false;
        if (punchThrough().containsAll(dig)) { // tried these already and they're still there (14 goes at one block in 45 s): not the way out; the walk gets dropped and he thinks again
            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] boxed in, but {} at {} didn't break last time: not trying it again", level.getBlockState(dig.get(0)).getBlock().getName().getString(), dig.get(0).toShortString());
            return false;
        }
        for (int i = dig.size() - 1; i >= 0; i--) { g.tasks.addFirst(new Task.Break(dig.get(i))); punchThrough().add(dig.get(i).immutable()); } // boxed in with no pickaxe: punched through by hand (slow, drops nothing) rather than "re-tooling" for ever from inside the box
        for (BlockPos b : dig) { // his mine's wall broken to get through: on the list to fill back in once he's past and has cobble
            if (com.claudemod.director.ClaudeLife.isMineCell(g, b)) continue;
            for (Direction d : Direction.values()) if (com.claudemod.director.ClaudeLife.isMineCell(g, b.relative(d))) { g.pendingPatches.add(b); break; }
        }
        com.claudemod.ClaudeMod.LOGGER.info("[Claude life] boxed in: breaking through {}", dig);
        return true;
    }

    /** Leaves or a log he may break to get through (natural tree, not something of theirs). */
    private boolean treeBlockInWay(BlockPos b) {
        BlockState bs = g.level().getBlockState(b);
        if (!(bs.is(net.minecraft.tags.BlockTags.LEAVES) || bs.is(net.minecraft.tags.BlockTags.LOGS))) return false;
        return !(g.getTarget() instanceof net.minecraft.server.level.ServerPlayer o
                && (com.claudemod.context.PlayerProfile.placedBy(o).contains(b) || com.claudemod.director.ClaudeLife.protectedBlock(g, o, b)));
    }

    /** Leaves between him and the target that he can reach: in his line of sight, or right in front of him (feet/head). */
    private BlockPos leavesToward(BlockPos target) {
        ServerLevel level = (ServerLevel) g.level();
        BlockPos seen = blockInTheWay(level, target);
        if (seen != null && inReach(seen) && level.getBlockState(seen).is(net.minecraft.tags.BlockTags.LEAVES) && treeBlockInWay(seen)) return seen;
        Vec3 to = Vec3.atCenterOf(target).subtract(g.position());
        if (to.horizontalDistance() < 1.0) return null;
        BlockPos next = g.blockPosition().relative(Direction.getNearest(to.x, 0, to.z));
        for (BlockPos b : new BlockPos[] {next.above(), next}) {
            if (level.getBlockState(b).is(net.minecraft.tags.BlockTags.LEAVES) && treeBlockInWay(b)) return b.immutable();
        }
        return null;
    }

    private BlockPos lastCover;

    /** The nearest spot within 8 that {@code mob} can't see (feet nor head), he can stand on and walk to without a drop; null if none. */
    private BlockPos coverFrom(net.minecraft.world.entity.LivingEntity mob) {
        ServerLevel level = (ServerLevel) g.level();
        BlockPos feet = BlockPos.containing(g.getX(), g.getY() + 0.2, g.getZ());
        java.util.List<BlockPos> spots = new java.util.ArrayList<>();
        for (int dx = -8; dx <= 8; dx++) for (int dz = -8; dz <= 8; dz++) for (int dy = -1; dy <= 1; dy++) {
            BlockPos c = feet.offset(dx, dy, dz);
            if (dx * dx + dz * dz > 64 || !level.isLoaded(c)) continue;
            if (!level.getBlockState(c).getCollisionShape(level, c).isEmpty() || !level.getBlockState(c.above()).getCollisionShape(level, c.above()).isEmpty()
                    || !level.getFluidState(c).isEmpty() || !level.getBlockState(c.below()).isFaceSturdy(level, c.below(), Direction.UP)) continue;
            boolean seen = false;
            for (double h : new double[] {0.3, 1.6}) if (level.clip(new net.minecraft.world.level.ClipContext(mob.getEyePosition(), new Vec3(c.getX() + 0.5, c.getY() + h, c.getZ() + 0.5), net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, mob)).getType() == net.minecraft.world.phys.HitResult.Type.MISS) seen = true;
            if (!seen) spots.add(c.immutable());
        }
        spots.sort(java.util.Comparator.comparingDouble(c -> c.distSqr(feet)));
        for (int i = 0; i < Math.min(4, spots.size()); i++) { // ponytail: 4 tries at the vanilla path; the planner if open caves need more
            var path = g.getNavigation().createPath(spots.get(i), 0);
            if (path != null && path.canReach()) return spots.get(i);
        }
        return null;
    }

    /** A partial path that ends meaningfully closer to target than where he stands. */
    private boolean pathGetsCloser(net.minecraft.world.level.pathfinder.Path path, Vec3 target) {
        if (path == null || path.getNodeCount() < 2 || path.getEndNode() == null) return false;
        double end = Vec3.atBottomCenterOf(path.getEndNode().asBlockPos()).distanceTo(target);
        return end < g.position().distanceTo(target) - 1.5;
    }

    private int bridged; // blocks placed bridging during the current task

    /** A block that stays where it's put (for plugs and patches): the scaffold choice without gravel or sand. */
    private ItemStack solidScaffold() {
        ItemStack s = scaffoldBlock();
        return s.is(net.minecraft.world.item.Items.GRAVEL) || s.is(net.minecraft.world.item.Items.SAND) ? ItemStack.EMPTY : s;
    }

    /** The biggest stack on him that fits. */
    private ItemStack biggestStack(java.util.function.Predicate<ItemStack> fits) {
        ItemStack best = ItemStack.EMPTY;
        for (int i = 0; i < g.inventory.items.getContainerSize(); i++) { ItemStack s = g.inventory.items.getItem(i); if (!s.isEmpty() && fits.test(s) && s.getCount() > best.getCount()) best = s; }
        return best;
    }

    /** What he towers up and bridges with, like a player: dirt, cobblestone or cobbled deepslate. Never wood. */
    private ItemStack scaffoldBlock() {
        if (g.level().dimensionType().hasCeiling()) { ItemStack gp = com.claudemod.entity.life.FortressScout.ghastProof(g); if (!gp.isEmpty()) return gp; } // the Nether: bridges and walls of cobblestone or cobbled deepslate, never netherrack or dirt while he has those (the user 16:30: "its not ghast proof. cobble is better")
        if (g.level().canSeeSky(g.blockPosition())) for (int i = 0; i < g.inventory.items.getContainerSize(); i++) { ItemStack d0 = g.inventory.items.getItem(i); if (d0.is(net.minecraft.world.item.Items.DIRT) || d0.is(net.minecraft.world.item.Items.GRASS_BLOCK)) return d0; } // (out in the world; underground, stone: a floor patched in his mine is stone) // dirt first: a block he puts down out in the world reads as ground (the user: a bridge over a crater by his house went in as cobbled deepslate)
        ItemStack s0 = biggestStack(s -> s.is(net.minecraft.world.item.Items.DIRT) || s.is(net.minecraft.world.item.Items.COBBLESTONE)
                || s.is(net.minecraft.world.item.Items.COBBLED_DEEPSLATE)); // (the biggest: the first was cobblestone x1 beside 67 cobbled deepslate, "underground with 1 blocks", no tower from the horde, live 16:25)
        if (!s0.isEmpty()) return s0;
        // any plain stone he dug up does the job too (andesite, diorite, granite, tuff, deepslate, stone; netherrack and blackstone in the Nether)
        s0 = biggestStack(s -> s.is(net.minecraft.world.item.Items.ANDESITE) || s.is(net.minecraft.world.item.Items.DIORITE)
                || s.is(net.minecraft.world.item.Items.GRANITE) || s.is(net.minecraft.world.item.Items.TUFF) || s.is(net.minecraft.world.item.Items.DEEPSLATE)
                || s.is(net.minecraft.world.item.Items.STONE) || s.is(net.minecraft.world.item.Items.CALCITE) || s.is(net.minecraft.world.item.Items.NETHERRACK) || s.is(net.minecraft.world.item.Items.BLACKSTONE));
        if (!s0.isEmpty()) return s0;
        // gravel or sand: junk, but it stands on a block under him (a player in a pit with nothing else towers up on it)
        s0 = g.inventory.find(s -> s.is(net.minecraft.world.item.Items.GRAVEL) || s.is(net.minecraft.world.item.Items.SAND));
        if (!s0.isEmpty()) return s0;
        // nothing else on him: planks will do to tower up (keeping 8 back for tools and a table)
        if (g.inventory.count(s -> s.is(net.minecraft.tags.ItemTags.PLANKS)) > 8) return g.inventory.find(s -> s.is(net.minecraft.tags.ItemTags.PLANKS));
        if (g.inventory.count(s -> s.is(net.minecraft.tags.ItemTags.LOGS)) > 4) return g.inventory.find(s -> s.is(net.minecraft.tags.ItemTags.LOGS)); // logs tower just as well (16 logs on him and "nothing to build with" sent him running to the player's mob camp)
        return ItemStack.EMPTY;
    }

    /**
     * Bridging like a player: one step toward target across a gap (ravine, water, a drop), placing a block
     * under the next step when there's nothing to stand on. Returns false when bridging can't help
     * (a wall in the way, too far up or down, no blocks, or he's bridged enough for this task).
     */
    private boolean bridgeToward(Vec3 target) {
        ServerLevel level = (ServerLevel) g.level();
        Vec3 to = target.subtract(g.position());
        if (Math.abs(to.y) > 3 || to.horizontalDistance() < 1.5 || bridged >= 32) return false;
        if (!g.onGround() && !g.isInWater()) return true; // mid-step: let him land first
        Direction dir = Direction.getNearest(to.x, 0, to.z);
        BlockPos next = g.blockPosition().relative(dir);
        if (!level.getBlockState(next).getCollisionShape(level, next).isEmpty()
                || !level.getBlockState(next.above()).getCollisionShape(level, next.above()).isEmpty()) return false; // a wall, not a gap
        BlockPos gapFloor = next.below();
        if (level.getFluidState(gapFloor).is(net.minecraft.tags.FluidTags.WATER) && level.canSeeSky(gapFloor.above())) { g.setShiftKeyDown(false); return false; }
        boolean overLava = false; for (int k = 1; k <= 12 && !overLava; k++) { BlockPos q = next.below(k); if (level.getFluidState(q).is(net.minecraft.tags.FluidTags.LAVA)) overLava = true; else if (!level.getBlockState(q).getCollisionShape(level, q).isEmpty()) break; }
        net.minecraft.world.entity.Mob shooter = null;
        if (overLava) for (var m : level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class, g.getBoundingBox().inflate(48), m -> m.isAlive() && (m instanceof net.minecraft.world.entity.monster.Blaze || m instanceof net.minecraft.world.entity.monster.Ghast || m instanceof net.minecraft.world.entity.monster.RangedAttackMob) && g.hasLineOfSight(m))) { shooter = m; break; }
        if (shooter != null) { // the Nether bridge rule: never out over lava with a shooter on him (a fireball's knock is the fall: World 15 13:41)
            g.setShiftKeyDown(false);
            if (g.tickCount - lavaBridgeSaid > 40) { lavaBridgeSaid = g.tickCount; com.claudemod.ClaudeMod.LOGGER.info("[Claude life] not bridging out over the lava toward {}: a {} {} off has him in sight (WorkGoal.bridgeToward)", BlockPos.containing(target).toShortString(), shooter.getType().getDescription().getString(), (int) shooter.distanceTo(g)); }
            return false;
        } // open water: a player swims it (he laid 40-block dirt causeways across the sea)
        // Crouch-bridging like a player: sneak (the game won't let a sneaking player walk off an edge), place a
        // block against the side of the one he's standing on, shuffle onto it.
        g.getNavigation().stop();
        g.setShiftKeyDown(true);
        bridgeCrouchTick = g.tickCount;
        BlockPos floor = next.below();
        int drop = 0; // how far down the ground is past the edge
        while (drop < 5 && !level.getBlockState(floor.below(drop)).isFaceSturdy(level, floor.below(drop), Direction.UP)) drop++;
        if (drop >= 1 && (drop <= 1 || to.y < -0.5 && drop <= 3) && g.onGround() && level.getBlockState(g.blockPosition().below()).isFaceSturdy(level, g.blockPosition().below(), Direction.UP)
                && (level.getBlockState(g.blockPosition().relative(dir.getClockWise()).below()).isFaceSturdy(level, g.blockPosition().relative(dir.getClockWise()).below(), Direction.UP)
                    || level.getBlockState(g.blockPosition().relative(dir.getCounterClockWise()).below()).isFaceSturdy(level, g.blockPosition().relative(dir.getCounterClockWise()).below(), Direction.UP))) { // a step down off real ground (not off a 1x1 pillar or trunk top), not a gap: he walks off it
            g.setShiftKeyDown(false);
            g.getMoveControl().setWantedPosition(next.getX() + 0.5, next.getY(), next.getZ() + 0.5, 1.0);
            return true;
        }
        if (!level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP)) {
            if (!level.getBlockState(floor).canBeReplaced()) return false;
            ItemStack block = scaffoldBlock();
            if (block.isEmpty() || !g.hold(block)) return false;
            BlockPos under = g.blockPosition().below(); // what he stands on: click its side facing the gap
            if (!g.useOn(under, dir)) return false;
            if (level.canSeeSky(floor.above())) com.claudemod.director.ClaudeLife.bridgeBlocks().add(floor.immutable()); // out in the open: taken down after
            if (bridged == 0) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] bridging toward {} from {}{}", BlockPos.containing(target).toShortString(), g.blockPosition().toShortString(), overLava ? " (over lava: 2 wide)" : "");
            bridged++;
            if (overLava) { // over lava, 2 wide: a knock sideways lands on the bridge, not in the lava (the Nether bridge rule)
                for (Direction sd : new Direction[] {dir.getClockWise(), dir.getCounterClockWise()}) { BlockPos side = floor.relative(sd);
                    if (level.getBlockState(side).isFaceSturdy(level, side, Direction.UP)) break; // (a side already there)
                    if (level.getBlockState(side).canBeReplaced() && g.hold(block) && g.useOn(floor, sd)) { if (level.canSeeSky(side.above())) com.claudemod.director.ClaudeLife.bridgeBlocks().add(side.immutable()); break; } }
            }
        }
        g.getMoveControl().setWantedPosition(next.getX() + 0.5, next.getY(), next.getZ() + 0.5, 1.0); // sneaking makes it slow
        return true;
    }

    private int bridgeCrouchTick = -100, lavaBridgeSaid = -100;

    /** Stand back up once he's stopped bridging for a moment. */
    private void maybeStandUp() {
        if (g.isShiftKeyDown() && g.tickCount - bridgeCrouchTick > 15 && !com.claudemod.director.Primitives.crouching(g)) g.setShiftKeyDown(false); // (a crouch they asked for stays)
    }

    private BlockPos equippedFor;
    public static int shellRefusals; public static BlockPos lastShellRefusal; // (tests: digs of his mine's shell refused, at the wall guard and the in-the-way guard)
    private java.util.Map<BlockPos, Integer> plugLog; // water sources he plugged, and when (lazy: fields added live start null)
    private java.util.Map<BlockPos, Integer> plugLog() { if (plugLog == null) plugLog = new java.util.HashMap<>(); if (plugLog.size() > 200) plugLog.clear(); return plugLog; }

    private boolean doBreak(BlockPos pos) {
        ServerLevel level = (ServerLevel) g.level();
        if (com.claudemod.director.ClaudeLife.bridgeBlocks().contains(pos) && g.getBoundingBox().move(0, -0.1, 0).intersects(new net.minecraft.world.phys.AABB(pos))) return exit("a block he put down to climb by, still under him (the walk off it ended at once): never cut from under his own (doBreak 1445)"); // a block he put down to climb by, still under him (the walk off it ended at once): never cut from under his own feet; it stays on the take-down list
        if (ticks == 1 && com.claudemod.director.ClaudeLife.bridgeBlocks().contains(pos)) com.claudemod.ClaudeMod.LOGGER.info("[Claude temp] taking back {}: he is at {} {} {} onGround {} box {} (WorkGoal.doBreak)", pos.toShortString(), String.format("%.2f", g.getX()), String.format("%.2f", g.getY()), String.format("%.2f", g.getZ()), g.onGround(), g.getBoundingBox());
        if (com.claudemod.director.ClaudeLife.bridgeBlocks().contains(pos) && level.getBlockState(pos).isAir()) { com.claudemod.director.ClaudeLife.bridgeBlocks().remove(pos); return exit("doBreak line 1446"); }
        BlockState state = level.getBlockState(pos);
        if (!state.isAir() && com.claudemod.entity.nav.ActionPath.structureBlock(level, pos) && !com.claudemod.entity.nav.ActionPath.structureDigOk(level, pos) && !com.claudemod.entity.life.TempLedger.built(g, pos) && !com.claudemod.entity.life.Consent.theirWord(g, pos, "structure", "a structure's own block", null)) { // the user, 17:08: "generally you wanna keep the nether fortress intact" (16:42-16:45 the fortress lost 11 nether bricks to his walks and digs, none of them logged): only a planned way with no way round breaks one (and puts it back), or their own dig_at
            if (ticks == 1) { com.claudemod.ClaudeMod.LOGGER.info("[Claude dig] not breaking the {} at {}: a structure's own block, kept intact (only a planned way with no way round breaks one, and puts it back) (WorkGoal.doBreak, queued by {})", net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath(), pos.toShortString(), com.claudemod.entity.ClaudeEntity.originOf(current)); com.claudemod.entity.life.MindLog.refused(g, pos, "breaking a structure block [kept intact] queued by " + com.claudemod.entity.ClaudeEntity.originOf(current)); }
            return exit("a structure's own block, kept intact (doBreak)");
        }
        if (com.claudemod.entity.life.PitGuard.isCap(g, pos) && !state.isAir() && state.getFluidState().isEmpty() && !com.claudemod.entity.life.Consent.theirWord(g, pos, "pit cap", "the mouth of the hole by his door he closed", null)) { // the hole by his door he closed (and the fill under it): his build
            com.claudemod.director.ClaudeLife.bridgeBlocks().remove(pos);
            if (ticks == 1) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] not breaking {}: the mouth of the hole by his door he closed (or its fill), his build (WorkGoal.doBreak, queued by {})", pos.toShortString(), com.claudemod.entity.ClaudeEntity.originOf(current));
            return exit("his cap stays (doBreak)");
        }
        { String kept0 = com.claudemod.entity.life.Consent.keptBlock(g, pos); if (kept0 != null && !state.isAir()) { if (ticks == 1) com.claudemod.ClaudeMod.LOGGER.info("[Claude consent] not breaking {} at {}: {} (WorkGoal.doBreak, queued by {})", name(state), pos.toShortString(), kept0, com.claudemod.entity.ClaudeEntity.originOf(current)); return exit("their block stays (Consent.keptBlock)"); } } // (what their word had him put there: no take-back, no tidy, no other job's dig)
        float hardness = state.getDestroySpeed(level, pos);
        if (ticks == 1 && state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_PICKAXE) && !inReach(pos) && com.claudemod.director.ClaudeLife.tableInReach(g, level) != null) { equipBestTool(state); equippedFor = pos.immutable(); } // at a table (he walked over for the tool): made here, before the walk back to the block
        if (TRACE && ticks == 1 && state.is(net.minecraft.tags.BlockTags.LOGS)) com.claudemod.ClaudeMod.LOGGER.info("[BTRACE] break {} from {} y={} reach={} next={}", pos.toShortString(), g.blockPosition().toShortString(), String.format("%.2f", g.getY()), inReach(pos), g.tasks.peek());
        if (state.isAir() || hardness < 0 || state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) return exit("nothing to mine there, " + (state.isAir() ? "air" : name(state)) + " where his block was (doBreak 1450)"); // water where his block was (a pillar in a pond): nothing to mine (45 s swinging at a water cell)
        boolean inWaterBeside = false; for (Direction d0 : Direction.Plane.HORIZONTAL) { var wf0 = level.getFluidState(pos.relative(d0)); if (wf0.is(net.minecraft.tags.FluidTags.WATER)) inWaterBeside = true; } if (inWaterBeside && pos.equals(g.blockPosition().below())) inWaterBeside = false; BlockPos liftedLava = null; // (the block under his feet: the water beside it doesn't make it safe, the hold asks Consent; live 15:59 "i told you not to mine the ones below you") (their word over the hold: Consent.theirWord below, not any order of theirs; the user 15:41: instructing him, he listens to them over his safety rules) // (any water source beside it at its level runs in, not only the one he stands in: 15:35 the pocket's obsidian held as "the face left standing") // (the user's pocket: his water runs into the hole and makes the lava behind it obsidian)
        if (!inWaterBeside && !com.claudemod.director.ClaudeLife.isCastBlock(pos) && !com.claudemod.director.ClaudeLife.obsOpening(g, pos)) for (Direction d : Direction.values()) if (level.getFluidState(pos.relative(d)).is(net.minecraft.tags.FluidTags.LAVA) && com.claudemod.entity.life.Perception.knowsLavaBody(g, level, pos.relative(d))) { if (com.claudemod.entity.life.Consent.theirWord(g, pos, com.claudemod.entity.life.Perception.knowsLava(g, pos.relative(d)) ? "lava behind" : "lava body", (d == Direction.UP ? "lava over it at " : d == Direction.DOWN ? "lava under it at " : "lava behind it at ") + pos.relative(d).toShortString(), pos.relative(d))) { liftedLava = pos.relative(d).immutable(); break; } if (ticks == 1 && !com.claudemod.entity.life.Perception.knowsLava(g, pos.relative(d))) com.claudemod.ClaudeMod.LOGGER.info("[Claude sense] {} behind {} is part of the pool he knows (never seen itself): holding (WorkGoal.doBreak)", pos.relative(d).toShortString(), pos.toShortString()); // it holds back lava he knows is there: never broken (lava he can't know of is met as it comes, below) (his mine's patch-and-dig broke the blocks in front of a lava flow twice and it ran onto him: two deaths at 220, -58, 64)
            g.attempts.of(com.claudemod.entity.life.Attempts.Kind.BREAK).put(pos.immutable(), g.tickCount);
            if (ticks == 1) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] not breaking {}: lava behind it at {}", pos.toShortString(), pos.relative(d).toShortString());
            if (ticks == 1) com.claudemod.director.ClaudeLife.lavaBehind(g, pos, pos.relative(d)); // (the goal wants lava: this face is where it is)
            return exit("doBreak line 1455");
        }
        if (liftedLava != null) { BlockPos stand0 = com.claudemod.entity.life.Consent.safeStand(g, level, pos, liftedLava); if (stand0 == com.claudemod.entity.life.Consent.REFUSE) { g.attempts.of(com.claudemod.entity.life.Attempts.Kind.BREAK).put(pos.immutable(), g.tickCount); return exit("the block under his feet by lava: never dug from on top (Consent.safeStand)"); } if (stand0 != null) { g.tasks.addFirst(new Task.Break(pos.immutable())); g.tasks.addFirst(new Task.GoTo(stand0, 0.3)); return exit("their word over the lava hold: to the safe stand first (Consent.safeStand)"); } } // (the same step the safe way: beside or above, never under it)
        if (pos.equals(g.blockPosition().below()) && g.onGround() && !g.isInWater() && !com.claudemod.director.ClaudeLife.isCastBlock(pos) && !(digInTasks != null && digInTasks.stream().anyMatch(t -> t == current))) { // (his cast's pillar comes down under his feet by design) // the block under his own feet, with a drop beneath it (the dig-in hole is three down on purpose and knows the ground: exempt): not from up here (the last floating log of a tree, dug out from under him: a 5-block fall, every tree test since falls started to hurt)
            int drop = 0; BlockPos q = pos.below();
            while (drop < 6 && level.getBlockState(q).getCollisionShape(level, q).isEmpty() && level.getFluidState(q).isEmpty()) { drop++; q = q.below(); }
            boolean takeFall = drop < 6 && !level.getFluidState(q).is(net.minecraft.tags.FluidTags.LAVA) && !level.getBlockState(q).is(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK) && g.getHealth() - (drop + 1 - 3) >= 14; // (stranded on one block over a short drop at near full health: a player just breaks it and takes the hit; refusing re-queued forever, 111 refusals in 15 s)
            if (drop >= 3 && !takeFall && !com.claudemod.director.ClaudeLife.safeDrop(pos) && com.claudemod.entity.life.Consent.theirWord(g, pos, "drop under his feet", "a " + drop + "-block drop under it", null)) { BlockPos stand1 = com.claudemod.entity.life.Consent.safeStand(g, level, pos, null); if (stand1 != null) { g.tasks.addFirst(new Task.Break(pos.immutable())); g.tasks.addFirst(new Task.GoTo(stand1, 0.3)); return exit("their word over the drop hold: off it to a side stand first (Consent.safeStand)"); } } // (their word: dug from beside it, not from on top)
            else if (drop >= 3 && !takeFall && !com.claudemod.director.ClaudeLife.safeDrop(pos)) { com.claudemod.ClaudeMod.LOGGER.info("[Claude life] not digging the block under his feet at {} out: a {}-block drop under it; leaving that one", pos.toShortString(), drop); g.attempts.of(com.claudemod.entity.life.Attempts.Kind.BREAK).put(pos.immutable(), g.tickCount); return true; }
        }
        if (com.claudemod.director.ClaudeLife.bpMode(g) && g.blueprint != null && !g.blueprint.isEmpty() && g.blueprint.isShell(pos) && !com.claudemod.entity.life.MineBlueprint.ore(state) && !g.landingCells.contains(pos) && !g.notches.contains(pos) && !(g.nookOld != null && (g.nookOld.touches(pos) || g.nookOld.oldBedCells().contains(pos))) && !g.tasks.stream().anyMatch(t -> t instanceof Task.Place pl && pl.pos().equals(pos)) && !com.claudemod.director.Primitives.putsBack(g, pos) && !com.claudemod.director.ClaudeLife.obsOwes(pos) && !com.claudemod.entity.life.Consent.theirWord(g, pos, "mine shell", "his mine's wall", null)) { /* (a plan's place_at for the cell is a swap too: dig_at the patch, the ore, place_at the patch) */ /* (a swap: out and straight back, the patch in front of a temp station he's taking up; refused, it re-queued Break+Place forever, 99 tasks on one cell) */ /* (a table/furnace notch is cut into the wall on purpose, then filled by the station) */ // his mine's shell (the arch's sides, every wall, floor and roof): never dug through by a walk, a step cut, a route or a pickup; only ore comes out of it (and is refilled)
            if (com.claudemod.director.ClaudeLife.seenOre(g).remove(pos)) { com.claudemod.ClaudeMod.LOGGER.info("[Claude ore] the ore he saw at {} is already out (now {}): nothing to dig there, his wall stays (WorkGoal.doBreak)", pos.toShortString(), name(state)); return true; } // (queued when it was ore; the guard and the queue agree)
            if (ticks == 1) { shellRefusals++; lastShellRefusal = pos.immutable(); com.claudemod.ClaudeMod.LOGGER.info("[Claude life] not digging through his mine's wall at {} ({}): the shell stays", pos.toShortString(), g.lastStep == null ? "-" : g.lastStep.skill()); }
            g.attempts.of(com.claudemod.entity.life.Attempts.Kind.BREAK).put(pos.immutable(), g.tickCount);
            return exit("doBreak line 1466");
        }
        if (com.claudemod.director.ClaudeLife.houseMode(g) && g.house != null && g.house.isStructure(pos) && g.house.rightBlock((ServerLevel) g.level(), pos, g.house.roleAt(pos)) && !(current instanceof Task.Break cb && g.tasks.stream().anyMatch(t -> t instanceof Task.Place pl && pl.pos().equals(pos))) && !com.claudemod.entity.life.Consent.theirWord(g, pos, "house", "his house's " + g.house.roleAt(pos), null)) { // his house's walls, posts, roof and foundation: never dug by a walk, a reflex or a pickup (a swap by the house pass comes as Break then Place)
            if (ticks == 1) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] not digging his house's {} at {} ({})", g.house.roleAt(pos), pos.toShortString(), g.lastStep == null ? "-" : g.lastStep.skill());
            g.attempts.of(com.claudemod.entity.life.Attempts.Kind.BREAK).put(pos.immutable(), g.tickCount);
            return exit("doBreak line 1471");
        }
        if (state.is(net.minecraft.tags.BlockTags.BEDS) && !com.claudemod.director.ClaudeLife.bedPickup(pos) && !com.claudemod.entity.life.Consent.theirWord(g, pos, "bed", "his bed", null) && !(com.claudemod.director.ClaudeLife.isMineCell(g, pos) && !(g.getTarget() instanceof net.minecraft.server.level.ServerPlayer o0 && com.claudemod.context.PlayerProfile.placedBy(o0).contains(pos)))) { // (his own bed on his mine's path is his to take up and carry home: refused, it blocked the stairs and stalled him 20 minutes) // his bed: never dug out by a job (one vanished with nothing in the log to say why); the bed rules take one up themselves
            com.claudemod.ClaudeMod.LOGGER.warn("[Claude life] a job wanted the bed at {} broken (step {}): refused", pos.toShortString(), g.lastStep == null ? "-" : g.lastStep.skill() + " " + g.lastStep.target());
            return exit("doBreak line 1475");
        }
        if ((state.hasBlockEntity() || state.is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE)) && !state.is(net.minecraft.tags.BlockTags.BEDS)
                && !com.claudemod.director.ClaudeLife.isTempStation(g, pos) && !com.claudemod.director.ClaudeLife.stationPickup(pos) && !com.claudemod.entity.life.Consent.theirWord(g, pos, "station", "a station or storage", null)) {
            var bpS = com.claudemod.director.ClaudeLife.bpMode(g) && g.blueprint != null && !g.blueprint.isEmpty() ? g.blueprint : null;
            boolean his0 = g.myStations.contains(pos) || g.notches.contains(pos) || com.claudemod.entity.life.TempLedger.has(g, pos);
            boolean inPocket = com.claudemod.director.ClaudeLife.nookWalkCell(g, pos); // (a nook's walkway cell: the pocket, where no station belongs)
            if (his0 && ((bpS != null && (bpS.isWalk(pos) || bpS.isWayIn(pos)) && !com.claudemod.director.ClaudeLife.inHisNook(g, pos)) || inPocket) && (state.is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE) || state.is(net.minecraft.world.level.block.Blocks.FURNACE))) { // his own station in his mine's walkway (outside his base): his to take up, the walkway dug (refused, the fix came back every pass)
                com.claudemod.director.ClaudeLife.takeUpStation(pos); g.myStations.remove(pos); g.notches.remove(pos); g.tempStations.remove(pos);
                com.claudemod.ClaudeMod.LOGGER.info("[Claude life] his own {} at {} stands in his {} (WorkGoal.doBreak): taking it up, back in his pack", name(state), pos.toShortString(), inPocket ? "base nook's pocket (its walkway)" : "mine's walkway");
                g.tasks.addFirst(new Task.Pickup(40));
            } else { if (ticks == 1) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] not breaking the {} at {} (WorkGoal.doBreak): stations and storage stay put (his own: {}, in his walkway: {}, in his nook: {})", name(state), pos.toShortString(), his0, bpS != null && bpS.isWalk(pos), com.claudemod.director.ClaudeLife.inHisNook(g, pos)); return true; }
        } // stations and storage stay put (except his own temporary ones, and his own taken up on purpose)
        if (g.getTarget() instanceof net.minecraft.server.level.ServerPlayer owner
                && com.claudemod.director.ClaudeLife.protectedBlock(g, owner, pos) && !com.claudemod.entity.life.Consent.theirWord(g, pos, "their build", "part of their build", null)) { if (ticks == 1) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] not breaking {}: it's part of their build", pos.toShortString()); return true; }
        // His pickaxe just broke (tools wear out) and this needs one: don't punch stone for nothing.
        // Drop the rest of this dig and redo the step, which makes or earns a new tool first.
        boolean madeTool = false;
        if (state.requiresCorrectToolForDrops() && g.inventory.find(s -> s.isCorrectToolForDrops(state)).isEmpty()
                && ((madeTool = com.claudemod.director.ClaudeLife.craftToolNow(g, state)) || !punchThrough().contains(pos) || state.is(net.minecraft.tags.BlockTags.NEEDS_IRON_TOOL) || state.is(net.minecraft.tags.BlockTags.NEEDS_DIAMOND_TOOL))) { // (a step cut never punches obsidian)
            if (!madeTool) { BlockPos tb = null; double tbd = 99; // a table a few steps off: walk to it, then the tool (the walk queued for it was wiped with the rest every time: 18 planks and 30 cobble on him, no pickaxe for minutes)
                for (BlockPos q : BlockPos.betweenClosed(g.blockPosition().offset(-6, -3, -6), g.blockPosition().offset(6, 3, 6))) if (level.getBlockState(q).is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE)) { double d = Math.sqrt(q.distSqr(g.blockPosition())); if (d < tbd) { tbd = d; tb = q.immutable(); } }
                if (tb != null && tbd > 3.5 && !g.attempts.within(com.claudemod.entity.life.Attempts.Kind.TOOL_WALK, tb, g.tickCount, 20 * 60)) { g.tasks.addFirst(new Task.Break(pos.immutable())); g.tasks.addFirst(new Task.GoTo(tb, 1.5)); if (g.tickCount - pulledAt > 20 * 60) { pulledAt = g.tickCount; pulled.clear(); } pulled.add(new Task.Break(pos.immutable())); g.attempts.fail(com.claudemod.entity.life.Attempts.Kind.TOOL_WALK, tb, g.tickCount); com.claudemod.ClaudeMod.LOGGER.info("[Claude life] no tool for {}: over to his table at {} to make one", name(state), tb.toShortString()); return true; } } // (a new pick on the spot even mid-climb: he punched one stone of his stairs up for 45 s with the materials on him)
            // like a player: craft a new one right here and keep going with the same job (the rest of the ore, the pickup, the patching)
            if (madeTool) return false;
            if (state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_PICKAXE) && (state.is(net.minecraft.tags.BlockTags.NEEDS_IRON_TOOL) || state.is(net.minecraft.tags.BlockTags.NEEDS_DIAMOND_TOOL))) { boolean hasPick = false; for (int i = 0; i < g.inventory.items.getContainerSize(); i++) { var it0 = g.inventory.items.getItem(i); if (it0.getItem() instanceof net.minecraft.world.item.PickaxeItem && !it0.is(net.minecraft.world.item.Items.WOODEN_PICKAXE) && !it0.is(net.minecraft.world.item.Items.GOLDEN_PICKAXE)) { hasPick = true; break; } } // (stone+ in hand and the block wants iron or diamond: a craft away isn't the answer; a broken stone pick still re-tools below)
                if (hasPick) { Integer seen = g.attempts.of(com.claudemod.entity.life.Attempts.Kind.BREAK).get(pos); if (seen != null && g.tickCount - seen < 20 * 300) return true; g.attempts.of(com.claudemod.entity.life.Attempts.Kind.BREAK).put(pos.immutable(), g.tickCount); com.claudemod.ClaudeMod.LOGGER.info("[Claude life] {} at {} needs a better pickaxe than his: left for later", name(state), pos.toShortString()); return true; } } // not broken, just the wrong tier (deepslate gold on a stone pick): the job goes on (it cleared his whole job 4 times in 2 minutes to "re-tool" a stone pick he had)
            if (g.tickCount - g.retoolTick < 20 * 30) return exit("just tried to re-tool: skip this block, don't loop (doBreak 1493)"); // just tried to re-tool: skip this block, don't loop
            g.retoolTick = g.tickCount;
            com.claudemod.director.ClaudeLife.clearTasks(g);
            if (g.lastStep != null && g.skillQueue.peek() != g.lastStep) g.skillQueue.addFirst(g.lastStep);
            String pick = g.inventory.count(s -> s.is(net.minecraft.tags.ItemTags.STONE_TOOL_MATERIALS)) >= 3 ? "stone_pickaxe" : "wooden_pickaxe"; // nothing to make one from here: the whole chain for one first (wood, planks, sticks, a table)
            if (g.skillQueue.stream().noneMatch(s -> s.skill().equals("obtain") && s.target().endsWith("pickaxe"))) g.skillQueue.addFirst(new com.claudemod.entity.life.Skills.Step("obtain", pick, 1));
            g.noteDoing("my tool broke (nothing that can mine " + name(state) + "), making a new one before going on");
            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] no tool for {} anymore (broke?), re-tooling", name(state));
            return exit("doBreak line 1501");
        }
        if (!inReach(pos)) { Integer gaveUp = g.attempts.last(com.claudemod.entity.life.Attempts.Kind.REACH, pos); if (gaveUp != null && g.tickCount - gaveUp < 20 * 300) { if (ticks == 1) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] not digging {} at {}: no way to it {}s ago", name(state), pos.toShortString(), (g.tickCount - gaveUp) / 20); return true; } return approach(pos); } // gave up reaching it a moment ago: skipped outright, not walked at again (a plan re-queued the same 150 out-of-reach cells every lap)

        // Standing in water (it flooded in): stop it where it comes in, like a player. Every spot where water flows into
        // his mine from outside (within 6) gets blocked for good; a source inside the mine gets blocked and mined straight
        // back out. The water left inside then drains. (Mining underwater is crawl-slow, so this comes first.)
        if (ticks == 1 && g.isInWater() && !com.claudemod.entity.body.Controls.wading(g)) { // (ankle deep he just mines: full speed there)
            g.tasks.addFirst(new Task.Break(pos)); // this block again, once it's dry
            if (blockWaterInflow()) return exit("doBreak line 1510");
            g.tasks.pollFirst();
        }


        if (ticks == 200) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] still on the dig at {} after 10 s: he's at {} ({}), in reach {}, wet {} eyes under {} ground {}, in the way {}", pos.toShortString(), g.blockPosition().toShortString(), name(state), inReach(pos), g.isInWater(), g.isEyeInFluid(net.minecraft.tags.FluidTags.WATER), g.onGround(), blockInTheWay(level, pos) == null ? "nothing" : blockInTheWay(level, pos).toShortString()); // (a block under his own feet stalled a pillar take-down for minutes with no word why)
        // Players can't mine through walls (or vines, grass...): whatever the crosshair hits first gets dealt with first.
        // Checked once he's in reach and again every half second while he digs (he may have moved).
        if (!pos.equals(sightCheckedFor) || ticks % 10 == 0) {
            sightCheckedFor = pos.immutable();
            BlockPos inWay = blockInTheWay(level, pos);
            if (inWay != null) {
                BlockState w = level.getBlockState(inWay);
                boolean breakable = w.getDestroySpeed(level, inWay) >= 0 && !(g.getTarget() instanceof net.minecraft.server.level.ServerPlayer o
                        && com.claudemod.director.ClaudeLife.protectedBlock(g, o, inWay));
                if (breakable && com.claudemod.entity.life.TempLedger.built(g, inWay)) breakable = false;
                if (breakable && com.claudemod.director.ClaudeLife.nookShell(g, inWay)) breakable = false; // (his nook's wall in the way of his aim: a look from elsewhere; stage 420 cut the shell beside his furnaces for the view of a dig on the way round) // (his own build in the way, his bedroom wall: a look from elsewhere; the nook pocket dig broke it for the view, his bedroom walled it again, round and round, 14:25)
                if (breakable) for (Direction ld : Direction.values()) if (level.getFluidState(inWay.relative(ld)).is(net.minecraft.tags.FluidTags.LAVA) && com.claudemod.entity.life.Perception.knowsLavaBody(g, level, inWay.relative(ld))) { breakable = false; break; } // (lava behind the block in the way: doBreak refuses it, so a look from elsewhere, not "break it first" twice a second; obsidian at -31,-56,-214 by his pool, live 13:11)
                if (breakable && w.requiresCorrectToolForDrops() && (w.is(net.minecraft.tags.BlockTags.NEEDS_IRON_TOOL) || w.is(net.minecraft.tags.BlockTags.NEEDS_DIAMOND_TOOL)) && g.inventory.find(st -> st.isCorrectToolForDrops(w)).isEmpty()) breakable = false; // no pick for it (obsidian of his frame in front of a mould block): round it to where he sees the block, not a 45 s stand-off
                boolean ownPatch = (w.is(net.minecraft.world.level.block.Blocks.COBBLESTONE) || w.is(net.minecraft.world.level.block.Blocks.COBBLED_DEEPSLATE)) && com.claudemod.director.ClaudeLife.seenOre(g).contains(pos) && com.claudemod.entity.life.MineBlueprint.ore(state); // his own patch over ore he saw: out, the ore, the patch back (the user)
                if (breakable && com.claudemod.director.ClaudeLife.bpMode(g) && g.blueprint != null && !g.blueprint.isEmpty() && g.blueprint.isShell(inWay) && !com.claudemod.entity.life.MineBlueprint.ore(w) && !ownPatch) breakable = false;
                if (breakable && ownPatch) { g.tasks.addFirst(new Task.Place(inWay.immutable(), w.getBlock().asItem())); g.tasks.addFirst(new Task.Pickup(100)); g.tasks.addFirst(new Task.MineVisibleOres(3)); g.tasks.addFirst(new Task.Break(pos)); g.tasks.addFirst(new Task.Break(inWay)); /* the user: the whole vein before any patch: the vein behind it and its drops first, then the patch back (04:24 it went straight back after each ore and sealed the rest in) */ com.claudemod.ClaudeMod.LOGGER.info("[Claude ore] his patch at {} is over {} he saw at {}: out, the ore, the patch back", inWay.toShortString(), state.getBlock().getName().getString(), pos.toShortString()); return true; }
                boolean patchInFront = com.claudemod.director.ClaudeLife.isTempStation(g, pos) && (w.is(net.minecraft.world.level.block.Blocks.COBBLESTONE) || w.is(net.minecraft.world.level.block.Blocks.COBBLED_DEEPSLATE)); // his own patch in front of his own table/furnace: out, the station picked up, the patch back (three tables sat sealed in his stairs wall as litter, the user)
                if (patchInFront) breakable = true; // his mine's shell in the way of his aim (the floor he stands on, the arch's side): he steps round to see the block, never digs through
                boolean station = w.hasBlockEntity() || w.is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE);
                if (station && (com.claudemod.director.ClaudeLife.isTempStation(g, inWay)
                        || g.myStations.contains(inWay) && com.claudemod.director.ClaudeLife.isMineCell(g, inWay))) breakable = true; // his own table out in the open, or in his walkway: pick it up (one set into the wall stays)
                else if (!breakable || station) {
                    // something he won't break (their chest, a table): step around it to where he can see the block
                    BlockPos clear = clearViewSpot(level, pos);
                    if (clear != null && !clear.equals(g.blockPosition()) && g.attempts.within(com.claudemod.entity.life.Attempts.Kind.WALK, clear, g.tickCount, 20 * 60)) { // a view cell he couldn't get to a moment ago: never sent there again (he looped on one six times in 15 s)
                        g.attempts.fail(com.claudemod.entity.life.Attempts.Kind.REACH, pos, g.tickCount);
                        com.claudemod.ClaudeMod.LOGGER.info("[Claude life] can't get a clear look at {}: {} at {} in the way, and no way to {} where he'd see it; leaving it", pos.toShortString(), name(w), inWay.toShortString(), clear.toShortString());
                        return exit("doBreak line 1540");
                    }
                    if (clear != null && !clear.equals(g.blockPosition())) {
                        if (clear.distSqr(g.blockPosition()) <= 2 && clear.getY() == g.getBlockY()) { g.getNavigation().stop(); g.getMoveControl().setWantedPosition(clear.getX() + 0.5, clear.getY(), clear.getZ() + 0.5, 0.6); return false; } // the cell beside him: a nudge (a walk to it "arrives" at once and never gets there; 45 s stuck on a one-block walk)
                        g.tasks.addFirst(new Task.Break(pos));
                        g.tasks.addFirst(new Task.GoTo(clear, 0.5));
                        return exit("doBreak line 1546");
                    }
                    if (clear != null && clear.equals(g.blockPosition()) && (Math.abs(g.getX() - (clear.getX() + 0.5)) > 0.15 || Math.abs(g.getZ() - (clear.getZ() + 0.5)) > 0.15) && ticks < 40) { g.getNavigation().stop(); g.getMoveControl().setWantedPosition(clear.getX() + 0.5, clear.getY(), clear.getZ() + 0.5, 0.5); return false; } // (his own cell sees it from its middle; he stood off to the side: a step to the middle, never "refused" from the edge, 05:49 a diamond sealed in so)
                    g.attempts.fail(com.claudemod.entity.life.Attempts.Kind.BREAK, pos, g.tickCount); // (on record: his mine check re-queued it every 3 s behind a redstone ore his stone pick can't take)
                    if (com.claudemod.director.ClaudeLife.bpMode(g) && g.blueprint != null && !g.blueprint.isEmpty() && g.blueprint.isShell(inWay)) { shellRefusals++; lastShellRefusal = inWay.immutable(); }
                    com.claudemod.ClaudeMod.LOGGER.info("[Claude life] can't break {}: {} at {} is in the way", pos.toShortString(), name(w), inWay.toShortString());
                    return exit("doBreak line 1550");
                }
                if (com.claudemod.entity.life.MineBlueprint.ore(w) && w.requiresCorrectToolForDrops() && g.inventory.find(s -> s.isCorrectToolForDrops(w)).isEmpty()) { // ore in front that needs a pick he hasn't got (stone in front without a pick still goes the re-tool way: a pick made on the spot): queuing it first only loops (live 03:18 World 15: copper in front of stone, wooden pick, 270 re-queues in 3 min)
                    g.attempts.fail(com.claudemod.entity.life.Attempts.Kind.BREAK, pos, g.tickCount);
                    com.claudemod.ClaudeMod.LOGGER.info("[Claude life] can't break {}: {} at {} is in the way and needs a better pick than he has (WorkGoal.doBreak)", pos.toShortString(), name(w), inWay.toShortString());
                    return exit("in the way: " + name(w) + " needs a better pick");
                }
                clearCrack();
                if (com.claudemod.director.ClaudeLife.isTempStation(g, pos) && (w.is(net.minecraft.world.level.block.Blocks.COBBLESTONE) || w.is(net.minecraft.world.level.block.Blocks.COBBLED_DEEPSLATE))) g.tasks.addFirst(new Task.Place(inWay.immutable(), w.getBlock().asItem())); // the patch goes back once the station's out
                g.tasks.addFirst(new Task.Break(pos));
                g.tasks.addFirst(new Task.Break(inWay));
                return exit("doBreak line 1556");
            }
        }

        g.getNavigation().stop();
        g.getLookControl().setLookAt(Vec3.atCenterOf(pos));
        // the right tool in hand once he's actually at the block (and again now and then, in case something swapped it)
        if (!pos.equals(equippedFor) || ticks % 10 == 0) { int q0 = g.tasks.size(); equipBestTool(state); equippedFor = pos.immutable(); if (g.tasks.size() > q0 && g.tasks.peekFirst() instanceof Task.GoTo goT) { g.tasks.pollFirst(); g.tasks.addFirst(new Task.Break(pos.immutable())); g.tasks.addFirst(goT); if (g.tickCount - pulledAt > 20 * 60) { pulledAt = g.tickCount; pulled.clear(); } pulled.add(new Task.Break(pos.immutable())); /* (parked behind the walk on purpose: the in-reach rule leaves it there) */ return exit("the new tool needs his table (the nook's, 20 off): over to it first, this dig after (01:57: the walk was queue (doBreak 1563)"); } } // the new tool needs his table (the nook's, 20 off): over to it first, this dig after (01:57: the walk was queued and the dig went on with the iron pick, every 30 s, for 6 minutes)
        // Held left click through the game's own survival mining: real speed (tool, enchantments, on ground or
        // not, underwater), real drops and tool wear, the crack everyone else sees.
        if (!g.mineTick(pos)) return false;
        { // what the break opened is in front of him now: the fluid beside it on record, and lava there means the block straight back in (lava runs into an opening a second and more later; a player puts it back)
            BlockPos lv = null;
            for (Direction d : Direction.values()) { BlockPos q = pos.relative(d); if (!level.getFluidState(q).isEmpty()) { com.claudemod.entity.life.Perception.sawCell(g, level, q); if (level.getFluidState(q).is(net.minecraft.tags.FluidTags.LAVA)) lv = q.immutable(); } }
            boolean waterIn = false; for (Direction d : Direction.Plane.HORIZONTAL) { var wf = level.getFluidState(pos.relative(d)); if (wf.is(net.minecraft.tags.FluidTags.WATER)) waterIn = true; } // (a water source beside the opening runs in and makes the lava behind it obsidian: the user's pocket technique, 15:33 the dirt put back undid it)
            if (lv != null && !waterIn && !com.claudemod.director.ClaudeLife.isCastBlock(pos) && !com.claudemod.director.ClaudeLife.obsOpening(g, pos) && !com.claudemod.entity.life.Consent.theirWord(g, pos, "lava running in", "lava came in behind it at " + lv.toShortString(), lv)) {
                ItemStack fill = g.inventory.find(com.claudemod.entity.life.ClaudeInventory::isBuildingBlock);
                boolean plugged = false;
                if (!fill.isEmpty() && g.hold(st0 -> st0.is(fill.getItem()))) { // the block straight back, this tick, from where he stands: a player's right click, no walk to a stand spot (live 06:53 World 15: the queued placement walked him to a step that never took while the lava came on, and he burned with his whole diamond kit)
                    for (Direction d0 : Direction.values()) { BlockPos n0 = pos.relative(d0); if (n0.equals(lv) || lv.equals(pos)) continue; var ns0 = level.getBlockState(n0); if (ns0.getCollisionShape(level, n0).isEmpty() || !ns0.getFluidState().isEmpty()) continue;
                        if (Vec3.atCenterOf(n0).add(d0.getOpposite().getStepX() * 0.5, d0.getOpposite().getStepY() * 0.5, d0.getOpposite().getStepZ() * 0.5).distanceTo(g.getEyePosition()) > 4.5) continue;
                        if (g.useOn(n0, d0.getOpposite(), true) && !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) { plugged = true; break; } }
                }
                if (plugged) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] lava behind {} as it came out (at {}): plugged at once with {} (WorkGoal.doBreak)", pos.toShortString(), lv.toShortString(), fill.getItem());
                else if (!fill.isEmpty()) { g.tasks.addFirst(new Task.Place(pos.immutable(), fill.getItem())); net.minecraft.world.phys.Vec3 away0 = g.position().subtract(net.minecraft.world.phys.Vec3.atCenterOf(lv)).normalize(); BlockPos back = dryCells.isEmpty() ? null : dryCells.peekLast(); if (back != null && back.equals(g.blockPosition()) && dryCells.size() > 1) { var it0 = dryCells.descendingIterator(); it0.next(); back = it0.next(); } if (back != null && back.distSqr(lv) > g.blockPosition().distSqr(lv)) g.tasks.addFirst(new Task.GoTo(back, 0.5)); else g.tasks.addFirst(new Task.GoTo(BlockPos.containing(g.position().add(away0.scale(2))), 0.8)); } // (no clean click: back the way he came first, then the plug)
                if (!plugged) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] lava behind {} as it came out (at {}): {}", pos.toShortString(), lv.toShortString(), fill.isEmpty() ? "nothing to put back, stepping clear" : "back the way he came, then the block back");
                if (fill.isEmpty()) { net.minecraft.world.phys.Vec3 away = g.position().subtract(net.minecraft.world.phys.Vec3.atCenterOf(lv)).normalize().scale(3); g.tasks.addFirst(new Task.GoTo(BlockPos.containing(g.position().add(away)), 0.8)); }
                com.claudemod.director.ClaudeLife.lavaBehind(g, pos, lv);
                return exit("doBreak line 1576");
            }
            com.claudemod.entity.life.Consent.broke(g, pos, state, lv, waterIn); // (a go-ahead used up; their opening kept; lava by the hole: the bucket's water into it, the ward on)
        }
        com.claudemod.director.ClaudeLife.noteBroken(g); // (a block down counts as getting somewhere, even stone punched by hand)
        if (level.getBlockState(pos.above()).getBlock() instanceof net.minecraft.world.level.block.FallingBlock && !pos.above().equals(g.blockPosition()) && !pos.above().equals(g.blockPosition().above())) {
            g.tasks.addFirst(new Task.Break(pos)); // gravel (sand) above it drops into the hole: this cell again once it has, until the column is down
        }
        // Broke into water (an aquifer, a lake bed): plug it like a player, before it floods the tunnel.
        if (!level.canSeeSky(pos) && !com.claudemod.director.ClaudeLife.isCastBlock(pos)) { // (the casting water running round his pillar's foot is not an aquifer: it "plugged" the pit and wrote the pillar's foot off as open water)
            ItemStack plug = solidScaffold(); // (never gravel or sand: a plug over his head would fall on him)
            if (plug.isEmpty() && pendingStepUp != null && pos.getY() > g.getBlockY()) { // cutting up into water with nothing to plug it: that way is off (he'd drown in his own cut)
                int sources = 0;
                for (BlockPos q : BlockPos.betweenClosed(pos.offset(-1, 0, -1), pos.offset(1, 2, 1))) if (level.getFluidState(q).isSource()) sources++;
                if (sources >= 2) {
                    g.attempts.of(com.claudemod.entity.life.Attempts.Kind.BREAK).put(pos.immutable(), g.tickCount);
                    pendingStepUp = null;
                    g.tasks.removeIf(q -> q instanceof Task.Break b && b.pos().getY() >= pos.getY() && b.pos().distSqr(pos) <= 9);
                    com.claudemod.ClaudeMod.LOGGER.info("[Claude life] water above the step at {} and nothing to plug it with: not cutting up through that", pos.toShortString());
                    return exit("doBreak line 1594");
                }
            }
            // Every source within two blocks, all at once: a pool of two or more sources refills whatever single cell he plugs (infinite water),
            // so plugging one at a time and mining it back out loops forever. A plug in his walkway stays until the pool is dealt with.
            int n = 0;
            java.util.List<Task> plugs = new java.util.ArrayList<>(), reopen = new java.util.ArrayList<>();
            // nothing solid over it up to the sky (water, leaves, air don't count). canSeeSky failed under a pond: water dims the skylight, so a 2-deep pond read as a flooded mine and got "plugged" with all his cobble
            int skyTop = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, pos.getX(), pos.getZ());
            boolean openUp = skyTop - pos.getY() <= 32;
            boolean wetBy = false; for (BlockPos q : BlockPos.betweenClosed(pos.offset(-1, -1, -1), pos.offset(1, 1, 1))) if (level.getFluidState(q).is(net.minecraft.tags.FluidTags.WATER)) { wetBy = true; break; }
            openUp &= wetBy; // (no water by it at all, a log under leaves: nothing to judge; that read every canopy log as "open water" and skipped it)
            for (BlockPos q = pos.above(); openUp && q.getY() < skyTop; q = q.above()) { BlockState st = level.getBlockState(q); if (!st.getCollisionShape(level, q).isEmpty() && !st.is(net.minecraft.tags.BlockTags.LEAVES)) openUp = false; }
            boolean surfaceWater = openUp && !com.claudemod.director.ClaudeLife.inHisMine(g, pos); // a pond or lake up top is not a flooded mine: nobody fills a lake with cobble to till its bank (that "plugged" 12 sources by a crafting table)
            if (surfaceWater && n == 0) { g.attempts.of(com.claudemod.entity.life.Attempts.Kind.BREAK).put(pos.immutable(), g.tickCount); com.claudemod.ClaudeMod.LOGGER.info("[Claude life] water at {} is open water up top: leaving it, not that block", pos.toShortString()); return true; }
            if (!plug.isEmpty() && !surfaceWater) for (BlockPos q0 : BlockPos.betweenClosed(pos.offset(-2, -1, -2), pos.offset(2, 2, 2))) {
                BlockPos q = q0.immutable();
                if (n >= 12 || !level.getFluidState(q).is(net.minecraft.tags.FluidTags.WATER) || !level.getFluidState(q).isSource()) continue;
                if (g.blockPosition().equals(q) || g.blockPosition().above().equals(q)) continue; // where he stands
                { boolean feeds = q.distManhattan(pos) <= 1 || com.claudemod.director.ClaudeLife.isMineCell(g, q); for (Direction d : Direction.values()) if (!feeds && com.claudemod.director.ClaudeLife.isMineCell(g, q.relative(d))) feeds = true; if (!feeds) continue; } // only the water that flows into his tunnel: the cave beyond his plug is none of his business (he tried to fill sources two blocks past his branch end he couldn't even see, over and over)
                if (com.claudemod.director.ClaudeLife.isPortalPit(q)) continue; // his own water pit for a portal cast (it got "plugged" taking his pillar down beside it)
                if (g.tasks.contains(new Task.Place(q, plug.getItem()))) continue; // queued already
                Integer last = plugLog().get(q);
                if (last != null && g.tickCount - last < 20 * 60) continue; // plugged that one a moment ago: it's refilling, not new
                plugLog().put(q, g.tickCount);
                plugs.add(new Task.Place(q, plug.getItem()));
                if (com.claudemod.director.ClaudeLife.isMineCell(g, q)) reopen.add(new Task.Break(q)); // his walkway: opened again once every source is plugged (nothing left to refill it)
                n++;
            }
            plugs.addAll(reopen);
            for (int i = plugs.size() - 1; i >= 0; i--) g.tasks.addFirst(plugs.get(i));
            if (n > 0) g.attempts.counters.merge("aquiferPlugs", n, Integer::sum); // (aquifer count: read by ClaudeLife.aquiferCheck)
            if (n > 0) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] hit water at {}: plugging {} source block(s) around it{}", pos.toShortString(), n, reopen.isEmpty() ? "" : ", then clearing " + reopen.size() + " of his walkway again");
        }
        Task next = g.tasks.peek();
        // Keep digging only if the next step is right here (same tunnel, same trunk); he walks over those drops anyway.
        // Anything else (the next tree, a new job): collect what he just mined first.
        BlockPos nextAt = switch (next) {
            case Task.Break b -> b.pos();
            case Task.GoTo go -> go.pos();
            case Task.Place p -> p.pos();
            case Task.WallTorch w -> w.pos();
            case null -> null;
            default -> g.blockPosition();
        };
        boolean stillHere = next instanceof Task.Pickup || next instanceof Task.MineVisibleOres || next instanceof Task.Pillar
                || (next instanceof Task.ChopTree ct && Math.abs(ct.base().getX() - pos.getX()) <= 10 && Math.abs(ct.base().getZ() - pos.getZ()) <= 10) || (next instanceof Task.FinishTree ft && Math.abs(ft.base().getX() - pos.getX()) <= 10 && Math.abs(ft.base().getZ() - pos.getZ()) <= 10) // (a log of the tree he's chopping, by its trunk's column however high: the chop sweeps once it's down and his tower's back; a sweep mid-chop walked him off his tower after the drops below, bridged out from its top, and he towered again wherever he landed: half towers round the trunk, "wants more than 17 scaffold blocks", the top left; stage 195)
                || com.claudemod.director.ClaudeLife.isCastBlock(pos) // (a cast's mould block or the cell dug clear for the lava: its cobble lies in the mould, picked up once the frame stands; a chase took him off the pillar into the cell)
                || com.claudemod.director.ClaudeLife.bridgeBlocks().contains(pos) // (a planned way's own climbing block, taken back from the ledge: its drop lies at the foot he climbed from; the sweep walked him back down to it and the way began again, 40+ times live 04:42; the daylight chore drops its blocks off this list before it breaks them, so it still sweeps)
                || pendingStepUp != null // (a step he's cutting: the drops are at his feet anyway)
                || com.claudemod.director.ClaudeLife.obsOpening(g, pos) // (a cell opened onto lava, the water about to go in: the drop's picked up after; the walk to it went into the lava running in)
                || (nextAt != null && nextAt.distSqr(pos) <= 4 * 4 && !(next instanceof Task.Smelt));
        if (!stillHere) g.tasks.addFirst(new Task.Pickup(100));
        return exit("doBreak line 1645");
    }

    private ServerLevel level() { return (ServerLevel) g.level(); }

    /**
     * The next block job is a long way off (the top of his stairs, say) while others are right here: do the ones here
     * first, like a player would, instead of running up and down the stairs between them.
     */
    /** A drop or a jump the route planned: walked to the lip, then Leaps does it (lined up, the step or the run and take-off, steered in the air, the word on how it went). */
    private boolean doLeap(Task.Leap t) {
        BlockPos feet = BlockPos.containing(g.getX(), g.getY() + 0.2, g.getZ());
        if (!com.claudemod.entity.nav.Leaps.running(g, t) && !feet.equals(t.from())) {
            if (ticks > 20 * 15) { com.claudemod.ClaudeMod.LOGGER.info("[Claude leap] 15 s and not on the lip {} (he's at {}): leaving the {} (WorkGoal.doLeap)", t.from().toShortString(), feet.toShortString(), com.claudemod.entity.nav.Leaps.kindName(t.kind())); return exit("not on the lip in 15 s"); }
            boolean beside = feet.getY() == t.from().getY() && Math.abs(feet.getX() - t.from().getX()) + Math.abs(feet.getZ() - t.from().getZ()) == 1 && com.claudemod.entity.life.PlaceAim.standable((ServerLevel) g.level(), t.from());
            if (beside) { g.getNavigation().stop(); g.getMoveControl().setWantedPosition(t.from().getX() + 0.5, t.from().getY(), t.from().getZ() + 0.5, 0.5); return false; } // the lip right beside him: a step onto it (the walk's own "there" is a cell off)
            if (!walkTo(t.from(), 0.5)) return false;
            feet = BlockPos.containing(g.getX(), g.getY() + 0.2, g.getZ());
            if (!feet.equals(t.from())) { com.claudemod.ClaudeMod.LOGGER.info("[Claude leap] the walk to the lip {} ended at {}: leaving the {} (WorkGoal.doLeap)", t.from().toShortString(), feet.toShortString(), com.claudemod.entity.nav.Leaps.kindName(t.kind())); return exit("the walk to the lip ended off it"); }
        }
        g.getNavigation().stop();
        String r = com.claudemod.entity.nav.Leaps.tick(g, (ServerLevel) g.level(), t);
        if (r == null) return false;
        exitWhy = r.equals("landed") ? null : "leap: " + r; // (a landed leap leaves no "why" for the next walk's line)
        return true;
    }

    private void nearestFirst() {
        BlockPos headPos = switch (g.tasks.peek()) {
            case Task.Place p -> p.pos();
            case Task.Break b -> b.pos();
            case null, default -> null;
        };
        if (headPos == null || headPos.distSqr(g.blockPosition()) <= 12 * 12) return;
        int i = 0;
        for (Task q : g.tasks) {
            if (++i > 80) break;
            BlockPos qp = q instanceof Task.Place p ? p.pos() : q instanceof Task.Break b ? b.pos() : null;
            if (qp != null && qp.distSqr(g.blockPosition()) <= 6 * 6) {
                g.tasks.remove(q);
                g.tasks.addFirst(q);
                return;
            }
        }
    }
    private boolean walkNoDig;
    private final java.util.ArrayDeque<BlockPos> dryCells = new java.util.ArrayDeque<>(); private BlockPos lavaOut; private int lavaOutSince; private final java.util.Map<BlockPos, Integer> lavaOutBad = new java.util.HashMap<>(); // (the lava escape's memory)
    private final java.util.Map<BlockPos, Integer> placeRetries = new java.util.HashMap<>();
    private Vec3 stepOffTo;
    private BlockPos lastStepSide;
    private int lastStepDecided = -1000;
    private int stepOffTick = -100;

    /**
     * Nothing to tower with: make some, like a player. Logs -> planks (a 2x2 recipe, no table), else dig a few
     * dirt blocks nearby and tower after. Returns the blocks, null if he queued digging first, EMPTY if no way.
     */
    private ItemStack getScaffold(Task.Pillar t) {
        ServerLevel level = level();
        ItemStack log = g.inventory.find(s -> s.is(net.minecraft.tags.ItemTags.LOGS));
        if (!log.isEmpty()) {
            String planks = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(log.getItem()).getPath().replace("_log", "_planks").replace("stripped_", "");
            var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.withDefaultNamespace(planks));
            if (item != net.minecraft.world.item.Items.AIR) com.claudemod.entity.life.Crafting.craft(level, g.inventory, item, Math.max(1, t.steps() / 4 + 1), false);
            ItemStack s = g.inventory.find(st -> st.is(net.minecraft.tags.ItemTags.PLANKS));
            if (!s.isEmpty()) return s;
        }
        if (g.tickCount - g.scaffoldDigTick < 20 * 30) return ItemStack.EMPTY; // just tried
        g.scaffoldDigTick = g.tickCount;
        java.util.List<BlockPos> dirt = new java.util.ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(g.blockPosition().offset(-4, -2, -4), g.blockPosition().offset(4, 1, 4))) {
            BlockState bs = level.getBlockState(p);
            if (!bs.is(net.minecraft.tags.BlockTags.DIRT)) continue; // dirt, grass, podzol, mud, rooted dirt...
            if (!level.getBlockState(p.above()).canBeReplaced() || g.pillarBlocks.contains(p)) continue; // the top layer (ferns, grass on top are fine)
            if (g.getTarget() instanceof net.minecraft.server.level.ServerPlayer o && com.claudemod.director.ClaudeLife.protectedBlock(g, o, p)) continue;
            if (p.equals(g.blockPosition().below())) continue;
            dirt.add(p.immutable());
            if (dirt.size() >= t.steps() + 1) break;
        }
        if (dirt.isEmpty()) return ItemStack.EMPTY;
        g.tasks.addFirst(t); // the tower, once he has the dirt
        g.tasks.addFirst(new Task.Pickup(60));
        for (BlockPos p : dirt) g.tasks.addFirst(new Task.Break(p));
        com.claudemod.ClaudeMod.LOGGER.info("[Claude life] nothing to tower with: digging {} dirt first", dirt.size());
        return null;
    }

    /** How far he'd fall stepping into this air column, and what he'd land in: >=0 blocks to solid ground, or -1 if water breaks the fall. */
    private int dropAt(ServerLevel level, BlockPos top) {
        for (int k = 0; k < 40; k++) {
            BlockPos q = top.below(k);
            if (level.getFluidState(q).is(net.minecraft.tags.FluidTags.WATER)) return -1; // water: no fall damage
            if (level.getBlockState(q).is(net.minecraft.tags.BlockTags.CLIMBABLE)) return -1; // vines/ladder: climbs down them
            if (!level.getBlockState(q).getCollisionShape(level, q).isEmpty()) return k;
        }
        return 99;
    }

    /**
     * One step down from up high, like a player: mine the block under his feet (leaves, a log, his own dirt) when the
     * drop after it is small; otherwise step off an edge into water or onto vines (no fall damage). True if he did something.
     */
    /** How many tree blocks (logs, leaves) are stacked right under his feet: 2 or more means he's up a tree. */
    /** The block actually holding him up (at a trunk's edge his feet's block column is the air beside it), like a player's footing. */
    private BlockPos standingOn() {
        return g.onGround() ? g.getOnPos() : g.blockPosition().below();
    }

    private int treeUnder() {
        int n = 0;
        for (BlockPos q = standingOn(); n < 40; q = q.below(), n++) {
            var st = level().getBlockState(q);
            if (!st.is(net.minecraft.tags.BlockTags.LOGS) && !st.is(net.minecraft.tags.BlockTags.LEAVES)) break;
        }
        return n;
    }

    /** A 1x1 column of cobble/dirt-type blocks at least 3 high with air all round: a pillar someone built (his, after a phantom scare), not ground. */
    private boolean spire(BlockPos under) {
        ServerLevel level = level();
        for (int k = 0; k < 3; k++) {
            BlockPos q = under.below(k);
            BlockState qs = level.getBlockState(q);
            if (qs.getCollisionShape(level, q).isEmpty() || qs.hasBlockEntity() || !(qs.is(net.minecraft.world.level.block.Blocks.COBBLESTONE) || qs.is(net.minecraft.world.level.block.Blocks.COBBLED_DEEPSLATE) || qs.is(net.minecraft.world.level.block.Blocks.DIRT) || qs.is(net.minecraft.tags.BlockTags.PLANKS) || qs.is(net.minecraft.tags.BlockTags.LOGS))) return false;
            for (Direction d : Direction.Plane.HORIZONTAL) if (!level.getBlockState(q.relative(d)).getCollisionShape(level, q.relative(d)).isEmpty()) return false;
        }
        return true;
    }

    private boolean descendStep(BlockPos dest) {
        ServerLevel level = level();
        { BlockPos f0 = g.blockPosition(); // by his mine: no new zigzag cut down to a block he spotted below (the user: a one-wide zigzag next to his stairs); his stairs are the way down, or it's left
          if (dest.getY() < f0.getY() - 2 && g.mineSpine.stream().anyMatch(sp -> sp.distSqr(f0) <= 12 * 12)) { g.attempts.any.put(dest.immutable(), g.tickCount); return false; } }
        if (!com.claudemod.director.ClaudeLife.inHisMine(g, g.blockPosition()) && g.blueprint != null && !g.blueprint.isEmpty() && com.claudemod.director.ClaudeLife.downHisMine(g, dest)) return false; // a spot down his mine: the way there is his entrance and his stairs, never a new flight cut down from the surface (the user: "he should be pathing to the entrance"; he dug 10 steps into an aquifer toward lava 110 below)
        if (g.isInWater()) return false; // swimming: he just swims down (digging the bottom out would sink him to bedrock)
        BlockPos under = standingOn(), feet = under.above();
        BlockState us = level.getBlockState(under);
        boolean natural = us.is(net.minecraft.tags.BlockTags.LEAVES) || us.is(net.minecraft.tags.BlockTags.LOGS) || g.pillarBlocks.contains(under)
                || us.is(net.minecraft.world.level.block.Blocks.DIRT) || us.is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK) || us.is(net.minecraft.tags.BlockTags.BASE_STONE_OVERWORLD);
        boolean theirs = g.getTarget() instanceof net.minecraft.server.level.ServerPlayer o
                && (com.claudemod.context.PlayerProfile.placedBy(o).contains(under) || com.claudemod.director.ClaudeLife.protectedBlock(g, o, under));
        boolean tree = us.is(net.minecraft.tags.BlockTags.LEAVES) || us.is(net.minecraft.tags.BlockTags.LOGS) || g.pillarBlocks.contains(under) || spire(under); // (a 1x1 column of placed blocks under him is a pillar of his, on record or not)
        if (tree && !theirs && us.getDestroySpeed(level, under) >= 0 && !us.hasBlockEntity()) { // his own pillar, a tree: straight down it is how you get off one
            int drop = dropAt(level, under.below());
            if (drop == -1 || drop <= 2) { // a safe little drop (or water/vines below)
                g.pillarBlocks.remove(under);
                g.tasks.addFirst(new Task.Pickup(40));
                g.tasks.addFirst(new Task.Break(under));
                return true;
            }
        }
        // Ground under him and the way is down: never a hole straight down (no player does that: lava, a pit he can't climb out of).
        // A step forward and down toward it instead, cut like a staircase; the walk then carries on from the new step.
        if (natural && !tree && !theirs) {
            double hx = dest.getX() + 0.5 - g.getX(), hz = dest.getZ() + 0.5 - g.getZ();
            java.util.List<Direction> ways = new java.util.ArrayList<>();
            if (Math.abs(hx) + Math.abs(hz) >= 1.0) ways.add(Direction.getNearest(hx, 0, hz));
            ways.add(g.getDirection());
            for (Direction d : Direction.Plane.HORIZONTAL) if (!ways.contains(d)) ways.add(d);
            for (Direction d : ways) {
                BlockPos ahead = feet.relative(d), nf = ahead.below(); // the next step's feet: one on, one down
                BlockPos[] cut = {ahead.above(), ahead, nf};
                boolean ok = level.getFluidState(nf).isEmpty() && level.getFluidState(nf.below()).isEmpty() && dropAt(level, nf) <= 1
                        && level.getBlockState(nf.below()).isFaceSturdy(level, nf.below(), Direction.UP);
                for (BlockPos q : cut) {
                    BlockState qs = level.getBlockState(q);
                    if (!qs.getFluidState().isEmpty() || qs.getDestroySpeed(level, q) < 0 || qs.hasBlockEntity()) ok = false;
                    if (com.claudemod.director.ClaudeLife.isMineCell(g, q) || com.claudemod.director.ClaudeLife.touchesMine(g, q)) ok = false; // never a "step" cut through his own staircase
                    if (g.getTarget() instanceof net.minecraft.server.level.ServerPlayer o && com.claudemod.director.ClaudeLife.protectedBlock(g, o, q)) ok = false;
                }
                if (!ok) continue;
                java.util.List<Task> steps = new java.util.ArrayList<>();
                for (BlockPos q : cut) if (!level.getBlockState(q).getCollisionShape(level, q).isEmpty()) steps.add(new Task.Break(q.immutable()));
                if (steps.isEmpty()) continue; // open already: that is just walking, not for here
                steps.add(new Task.GoTo(nf.immutable(), 0.6));
                for (int i = steps.size() - 1; i >= 0; i--) g.tasks.addFirst(steps.get(i));
                com.claudemod.ClaudeMod.LOGGER.info("[Claude life] getting down: cutting a step down {} at {} (never straight down)", d.getName(), feet.toShortString());
                return true;
            }
        }
        // step off an edge where water or vines are below (any height), or onto a ledge at most 3 down
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos side = feet.relative(d);
            if (!level.getBlockState(side).getCollisionShape(level, side).isEmpty() || !level.getBlockState(side.above()).getCollisionShape(level, side.above()).isEmpty()) continue;
            int first = dropAt(level, side);
            int drop = first == -1 || first == 99 ? first : first - 1; // how far he'd actually fall: 0 is just level ground (another leaf)
            if (side.equals(lastStepSide) && g.tickCount - lastStepDecided < 60) continue; // tried this one a moment ago
            if (stepTries().getOrDefault(side, 0) >= 3) continue; // picked three times and never landed: that edge is no good
            { boolean lv = com.claudemod.entity.body.Controls.lavaCell(g, side); for (int k = 1; k <= Math.max(1, drop) + 1 && !lv; k++) lv = level.getFluidState(side.below(k)).is(net.minecraft.tags.FluidTags.LAVA); BlockPos land0 = side.below(Math.max(0, drop)); for (Direction d2 : Direction.Plane.HORIZONTAL) if (!lv && com.claudemod.entity.body.Controls.lavaCell(g, land0.relative(d2))) lv = true;
              if (lv) { if (g.tickCount % 40 == 0) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] getting down: not toward {}: lava in it, under it or beside where he'd land (WorkGoal)", side.toShortString()); continue; } } // (World 15 13:07 and 21:30: "stepping off toward 161, 50, -33 (1 block drop)" into lava, twice)
            if (drop == -1 || (drop >= 1 && drop <= 3)) {
                if (!side.equals(lastStepSide)) stepTries().clear();
                stepTries().merge(side.immutable(), 1, Integer::sum);
                lastStepSide = side.immutable();
                lastStepDecided = g.tickCount;
                g.getNavigation().stop();
                stepOffTo = Vec3.atBottomCenterOf(side).add(d.getStepX() * 0.4, 0, d.getStepZ() * 0.4); // past the edge
                stepOffTick = g.tickCount;
                g.tasks.addFirst(new Task.GoTo(side.below(Math.max(1, drop)), 0.9)); // the step itself, as a walk to where he lands
                com.claudemod.ClaudeMod.LOGGER.info("[Claude life] getting down: stepping off toward {} ({})", side.toShortString(), drop == -1 ? "water/vines below" : drop + " block drop");
                return true;
            }
        }
        // up on a canopy with no easy way off: break through the leaves under him if the fall is short (a heart at most)
        if (us.is(net.minecraft.tags.BlockTags.LEAVES) && !theirs) {
            int fall = dropAt(level, under.below());
            if (fall != 99 && (fall == -1 || fall <= 2) && g.getHealth() > 8) { // (measured from under the leaves: 2 there is a 3-block fall from his feet, the most that costs nothing; 4 was a 5-block fall, a heart, now that falls actually hurt him)
                g.tasks.addFirst(new Task.Break(under));
                com.claudemod.ClaudeMod.LOGGER.info("[Claude life] getting down: breaking through the leaves under him ({} block fall)", fall + 1);
                return true;
            }
        }
        return false;
    }

    /** Ground to stand on right under a block in the air (or beside its column), nearest to him; null if none. */
    private BlockPos groundUnder(ServerLevel level, BlockPos pos) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos col : new BlockPos[] {pos, pos.north(), pos.south(), pos.east(), pos.west()}) {
            BlockPos p = col.below();
            BlockState besideFloor = level.getBlockState(col.below());
            if (!col.equals(pos) && level.getBlockState(col).getCollisionShape(level, col).isEmpty() && besideFloor.isFaceSturdy(level, col.below(), Direction.UP) && !besideFloor.is(net.minecraft.tags.BlockTags.LEAVES) && !besideFloor.is(net.minecraft.tags.BlockTags.LOGS)) p = col; // a log low on the trunk (the far column of a 2x2 at the ground): the ground beside it at its own level (one level down was the grass itself: "nowhere to stand under the bough" for a trunk log, the walk in only ever came from a drop sweep mid-chop; stage 196)
            for (int k = 0; k < 24 && level.getBlockState(p.below()).getCollisionShape(level, p.below()).isEmpty(); k++) p = p.below();
            boolean open = level.getBlockState(p).getCollisionShape(level, p).isEmpty() && level.getBlockState(p.above()).getCollisionShape(level, p.above()).isEmpty()
                    && level.getBlockState(p.below()).isFaceSturdy(level, p.below(), Direction.UP) && level.getFluidState(p).isEmpty();
            if (!open || p.getY() < g.getBlockY() - 3) continue; // a pit or cave mouth under a branch: no climbing down into it to tower back up
            double dd = p.distSqr(g.blockPosition()) + (col.equals(pos) ? 0 : 2); // right under it is best
            if (dd < bestD) { bestD = dd; best = p.immutable(); }
        }
        return best;
    }

    /** A spot to stand that's in reach of the block with nothing in between, or null. */
    private BlockPos clearViewSpot(ServerLevel level, BlockPos target) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos p0 : BlockPos.betweenClosed(target.offset(-3, -2, -3), target.offset(3, 2, 3))) {
            BlockPos p = p0.immutable();
            if (!level.getBlockState(p).getCollisionShape(level, p).isEmpty() || !level.getBlockState(p.above()).getCollisionShape(level, p.above()).isEmpty()
                    || !level.getBlockState(p.below()).isFaceSturdy(level, p.below(), Direction.UP)) continue;
            if (!level.getFluidState(p).isEmpty() || !level.getFluidState(p.above()).isEmpty()) continue; // (lava has no collision box: live 07:14 World 15 a furnace's view spot was a lava cell, a walk at it every minute for 47 min)
            if (g.attempts.within(com.claudemod.entity.life.Attempts.Kind.WALK, p, g.tickCount, 20 * 120)) continue; // (a spot he couldn't get to lately: not picked again, the write-off holds)
            Vec3 eye = Vec3.atBottomCenterOf(p).add(0, 1.62, 0);
            if (eye.distanceTo(Vec3.atCenterOf(target)) > REACH - 0.3) continue;
            boolean seen = false; Vec3 c0 = Vec3.atCenterOf(target); // any face he'd see from there, the same test as the dig's own (centre only: the lapis over a head-height hole was "hidden" from right under it, and he stood 3 off for good, 05:02)
            for (Vec3 aim : new Vec3[] {c0, c0.add(0.45, 0, 0), c0.add(-0.45, 0, 0), c0.add(0, 0.45, 0), c0.add(0, -0.45, 0), c0.add(0, 0, 0.45), c0.add(0, 0, -0.45)}) {
                var hit = level.clip(new net.minecraft.world.level.ClipContext(eye, aim, net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, g));
                if (hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK || hit.getBlockPos().equals(target)) { seen = true; break; }
            }
            if (!seen) continue;
            double dd = p.distSqr(g.blockPosition());
            if (dd < bestD) { bestD = dd; best = p; }
        }
        return best;
    }

    private BlockPos sightCheckedFor;

    /** The first other block between his eyes and the target, if any (what a player's crosshair would hit: vines and grass count). */
    private BlockPos blockInTheWay(ServerLevel level, BlockPos target) {
        // a player can hit any face he can see: only "in the way" if every face (and the middle) is hidden
        BlockPos first = null;
        Vec3 c = Vec3.atCenterOf(target);
        Vec3[] aims = {c, c.add(0.45, 0, 0), c.add(-0.45, 0, 0), c.add(0, 0.45, 0), c.add(0, -0.45, 0), c.add(0, 0, 0.45), c.add(0, 0, -0.45)};
        for (Vec3 aim : aims) {
            var hit = level.clip(new net.minecraft.world.level.ClipContext(g.getEyePosition(), aim,
                    net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.NONE, g));
            if (hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) return null;
            BlockPos at = hit.getBlockPos();
            if (at.equals(target) || level.getBlockState(at).isAir()) return null; // this face is visible
            if (first == null) first = at.immutable();
            if ((com.claudemod.director.ClaudeLife.bpMode(g) && g.blueprint != null && g.blueprint.isShell(first)) && !(com.claudemod.director.ClaudeLife.bpMode(g) && g.blueprint != null && g.blueprint.isShell(at))) first = at.immutable(); // the block he can dig wins over his mine's wall another aim clipped (off-centre in a 1-wide branch, the aim grazed the wall's gravel and he gave up on the cell past the stone in front)
        }
        return first;
    }

    /** A lot of open dark air around the tunnel means he broke into a cave: explore it like a player would, then resume. */
    private boolean doCheckForCave() {
        ServerLevel level = (ServerLevel) g.level();
        if (com.claudemod.director.ClaudeLife.bpMode(g)) return true; // a cave beside the mine is a wall to build, not a reason to turn (the user's rule)
        BlockPos at = g.blockPosition();
        int air = 0;
        for (BlockPos p : BlockPos.betweenClosed(at.offset(-4, -2, -4), at.offset(4, 4, 4))) {
            if (level.getBlockState(p).isAir() && level.getBrightness(net.minecraft.world.level.LightLayer.SKY, p) == 0) air++;
        }
        if (air < 160) return true; // just his tunnel (a 2-wide, 4-tall one is a lot of air already)
        for (BlockPos done : g.exploredCaves) if (done.distSqr(at) < 28 * 28) return true; // a cave he already went through
        g.exploredCaves.add(at.immutable());
        if (g.exploredCaves.size() > 20) g.exploredCaves.remove(0);
        // Strip mining and the tunnel ran into a cave: this level's no good. Back to the bottom of his stairs and down
        // a stretch deeper, then the strip mine again from there (it keeps its pattern).
        if (g.lastStep != null && g.lastStep.skill().equals("strip_mine") && g.mineIsTunnel && g.mineSpine.size() >= 2) {
            int stairEnd = -1;
            for (int i = g.mineSpine.size() - 1; i >= 1 && stairEnd < 0; i--) if (g.mineSpine.get(i).getY() < g.mineSpine.get(i - 1).getY()) stairEnd = i;
            if (stairEnd > 0) {
                BlockPos end = g.mineSpine.get(stairEnd), prev = g.mineSpine.get(stairEnd - 1);
                Direction sd = Direction.getNearest(end.getX() - prev.getX(), 0, end.getZ() - prev.getZ());
                g.tasks.removeIf(q -> !(q instanceof Task.SealStep) && !(q instanceof Task.Place) && !(q instanceof Task.Pickup)); // the rest of the tunnel: not through a cave
                Integer bestY = com.claudemod.director.ClaudeLife.bestY(g.lastStep.target());
                if (bestY != null && at.getY() <= bestY + 2 && g.tunnelDir != null) { // already at the ore's level: turn the tunnel (like for lava), deeper is only the water table
                    g.tunnelDir = g.tunnelDir.getClockWise();
                    g.skillQueue.addFirst(g.lastStep);
                    com.claudemod.ClaudeMod.LOGGER.info("[Claude life] strip tunnel ran into a cave at {}: turning the tunnel {} from the face", at.toShortString(), g.tunnelDir);
                    g.noteDoing("hit a cave while strip mining, turned the tunnel " + g.tunnelDir + " to stay out of it");
                    return true;
                }
                g.tasks.add(new Task.GoTo(end, 1.6));
                g.mineFace = end; g.mineIsTunnel = false; g.tunnelDir = null;
                if (sd.getAxis().isHorizontal()) g.mineDir = sd;
                g.skillQueue.addFirst(g.lastStep); // the strip mine again, once he's deeper
                g.skillQueue.addFirst(new com.claudemod.entity.life.Skills.Step("dig_down", "", 12, "near_you"));
                com.claudemod.ClaudeMod.LOGGER.info("[Claude life] strip tunnel ran into a cave at {}: back to his stairs at {} and digging deeper instead", at.toShortString(), end.toShortString());
                g.noteDoing("hit a cave while strip mining, so went back to the stairs to dig deeper");
                return true;
            }
        }
        // Ask them, like a friend would: explore it, or keep strip mining? Meanwhile the tunnel keeps going through it.
        String ore = g.lastStep != null ? g.lastStep.target() : "";
        g.noteDoing("broke into a cave while strip mining, asked them whether to explore it; keeping the tunnel going meanwhile");
        if (g.getTarget() instanceof net.minecraft.server.level.ServerPlayer p) com.claudemod.director.ClaudeLife.askAboutCave(g, p, at, ore);
        return true;
    }

    /** Where a torch on this wall sits at eye level: one block above the floor under that spot (the head-height cell of someone standing there). */
    private static BlockPos eyeLevel(ServerLevel level, BlockPos pos, Direction wallDir) {
        java.util.function.Predicate<BlockPos> solid = q -> !level.getBlockState(q).getCollisionShape(level, q).isEmpty();
        BlockPos p = pos;
        for (int k = 0; k < 3 && !solid.test(p.below(2)) && !solid.test(p.below()); k++) p = p.below(); // too high: down to one above the floor
        for (int k = 0; k < 2 && solid.test(p.below()); k++) p = p.above(); // at the floor: up to head height
        BlockPos wall = p.relative(wallDir);
        return level.getBlockState(p).canBeReplaced() && level.getBlockState(wall).isFaceSturdy(level, wall, wallDir.getOpposite()) ? p : pos;
    }

    private boolean doWallTorch(Task.WallTorch t) {
        ServerLevel level = (ServerLevel) g.level();
        boolean slot = com.claudemod.director.ClaudeLife.bpMode(g) && g.blueprint != null && !g.blueprint.isEmpty() && g.blueprint.isTorchSlot(t.pos()); // a slot of his blueprint mine: exactly there. The eye-level guess read the world mid-dig: a step's bottom block still to dig lifted the torch a block, a floor mined out for ore dropped it one (world 14's stairs: 0, +1, -1 off the step)
        BlockPos pos = slot ? t.pos() : eyeLevel(level, t.pos(), t.wall());
        BlockPos wall = pos.relative(t.wall());
        if (ticks == 1 && pos.distSqr(g.blockPosition()) > 8 * 8) return true; // he's moved on from that dark spot: not worth walking back for
        BlockState onto = level.getBlockState(wall);
        if (onto.hasBlockEntity() || onto.is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE)) return true; // never a torch stuck on a table or furnace
        if (ticks == 1 && slot && com.claudemod.entity.life.MineBlueprint.slotLit(level, pos)) return true; // (its slot has its torch already)
        if (ticks == 1 && !slot) for (BlockPos q : BlockPos.betweenClosed(pos.offset(-2, -2, -2), pos.offset(2, 2, 2))) { BlockState qs = level.getBlockState(q); if (!q.equals(pos) && (qs.is(net.minecraft.world.level.block.Blocks.WALL_TORCH) || qs.is(net.minecraft.world.level.block.Blocks.TORCH))) return true; } // a torch went up right by it since this one was planned (two queued in one pass went up stacked on one wall): a player looks before he puts one up
        if (!level.getBlockState(pos).canBeReplaced() || !level.getFluidState(pos).isEmpty() || !level.getBlockState(wall).isFaceSturdy(level, wall, t.wall().getOpposite())) { // (water there: a torch would just pop off
            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] torch at {} on the {} wall: no solid wall / spot taken, skipped", pos.toShortString(), t.wall().getName());
            return true;
        }
        ItemStack torch = g.inventory.find(s -> s.is(net.minecraft.world.item.Items.TORCH));
        if (torch.isEmpty()) return true;
        if (!inReach(pos)) return walkTo(pos, REACH - 1) && !inReach(pos);
        Vec3 face = Vec3.atCenterOf(wall).subtract(t.wall().getStepX() * 0.5, 0, t.wall().getStepZ() * 0.5);
        if (!com.claudemod.entity.life.Perception.clear(level, g.getEyePosition(), face.add(t.wall().getStepX() * -0.05, 0, t.wall().getStepZ() * -0.05))) { // the wall's face out of his sight (round a corner, behind a block): no torch clicked through it; up to the torch's own cell for a look, else skipped (no sensing through walls: the one place path that skipped the check)
            if (Math.sqrt(pos.distSqr(g.blockPosition())) > 1.5) { walkTo(pos, 1.0); return false; }
            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] torch at {} on the {} wall: can't see that face from where he can stand, skipped", pos.toShortString(), t.wall().getName());
            return true;
        }
        g.getNavigation().stop();
        g.getLookControl().setLookAt(face.x, face.y, face.z);
        g.hold(torch);
        if (ticks < 5) return false;
        g.useOn(wall, t.wall().getOpposite()); // right click the wall's face: a torch on the wall
        com.claudemod.ClaudeMod.LOGGER.info("[Claude life] torch at {} on the {} wall: {}", pos.toShortString(), t.wall().getName(),
                level.getBlockState(pos).is(net.minecraft.world.level.block.Blocks.WALL_TORCH) ? "placed" : "didn't stick");
        return true;
    }

    /** A player glances at the walls and mines any ore showing (that their pickaxe can take). */
    private int pulledAt = -100000; private final java.util.Set<Task> pulled = new java.util.HashSet<>(); // (jobs pulled ahead of a walk, per walk target)
    String exitWhy = null; // why the last dig/place ended without its block changed (the user: every give-up says why)
    private boolean exit(String why) { exitWhy = why; return true; }
    private boolean doMineVisibleOres(Task.MineVisibleOres t) {
        ServerLevel level = (ServerLevel) g.level();
        BlockPos at = g.blockPosition();
        boolean ownMine = com.claudemod.director.ClaudeLife.inHisMine(g, at);
        int r = ownMine ? Math.max(t.radius(), 12) : t.radius(); // in his mine: ore showing down a cave it broke into counts too, if he can see it and walk to it
        java.util.List<BlockPos> ores = new java.util.ArrayList<>();
        java.util.Set<BlockPos> queued = new java.util.HashSet<>(); // ore already on the list (the scan runs every column: the same vein got queued three times over)
        for (Task q : g.tasks) if (q instanceof Task.Break b) queued.add(b.pos());
        for (BlockPos p : BlockPos.betweenClosed(at.offset(-r, -r, -r), at.offset(r, r + 1, r))) {
            BlockState st = level.getBlockState(p);
            if (!net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath().endsWith("_ore") || queued.contains(p)) continue;
            boolean exposed = false; // open to air, water, or anything see-through (torches, glass...)
            for (Direction d : Direction.values()) {
                BlockState n = level.getBlockState(p.relative(d));
                if (n.isAir() || !n.getFluidState().isEmpty() || !n.canOcclude()) { exposed = true; break; }
            }
            if (!exposed && !(ownMine && com.claudemod.director.ClaudeLife.seenOre(g).contains(p))) continue; // (ore he saw and then sealed behind his own patch: still his to fetch, the user)
            final BlockState ore = st;
            if (g.inventory.find(s -> s.isCorrectToolForDrops(ore)).isEmpty()) { if (p.distSqr(at) <= 36) com.claudemod.ClaudeMod.LOGGER.info("[Claude ore] skipping {} at {}: no pick for it yet", ore.getBlock().getName().getString(), p.toShortString()); continue; } // can't take it yet
            if (g.attempts.within(com.claudemod.entity.life.Attempts.Kind.BREAK, p, g.tickCount, 20 * 300)) continue; // (refused a moment ago, lava behind it or out of reach: not asked for again every sweep, 05:31)
            ores.add(p.immutable());
            if (ores.size() >= 24) break;
        }
        // In his own mine: only the ore in its walls (not every ore showing in some cave nearby), and then only the
        // part of each vein close behind it, reachable through the opening. Out in a cave: whatever shows.
        // in his own mine: ore in its walls, plus ore he can plainly see off in a cave beside it that he can walk to (a player goes and
        // gets that, then comes back); never ore he'd have to tunnel after blind
        if (ownMine) ores.removeIf(o -> {
            for (Direction d : Direction.values()) { BlockPos nb = o.relative(d); if (com.claudemod.director.ClaudeLife.isMineCell(g, nb) || (g.blueprint != null && g.blueprint.isShell(nb) && level.getBlockState(nb).isAir())) return false; } // (showing through a hole in his own wall counts as his wall: the iron at 13, 18, -115 behind the hole at 13, 18, -114 was held for and never mined)
            if (!com.claudemod.director.ClaudeLife.canSee(g, level, o) && !com.claudemod.director.ClaudeLife.seenOre(g).contains(o)) { com.claudemod.ClaudeMod.LOGGER.info("[Claude ore] skipping {} at {}: not in his mine's walls and he can't see it from {}", level.getBlockState(o).getBlock().getName().getString(), o.toShortString(), at.toShortString()); return true; }
            if (o.distSqr(at) <= 6 * 6) return false;
            for (Direction d : Direction.values()) { // a path to any open cell beside it (a path to the ore block itself aims at the air above it)
                BlockPos n = o.relative(d);
                if (!level.getBlockState(n).getCollisionShape(level, n).isEmpty()) continue;
                var path = g.getNavigation().createPath(n, 0);
                if (path != null && path.canReach()) return false;
            }
            com.claudemod.ClaudeMod.LOGGER.info("[Claude ore] skipping {} at {}: seen from {} but no walk to a cell beside it", level.getBlockState(o).getBlock().getName().getString(), o.toShortString(), at.toShortString());
            return true;
        });
        boolean sideTrip = ownMine && ores.stream().anyMatch(o -> !com.claudemod.director.ClaudeLife.touchesMine(g, o)); // off into the cave for it: back to his step after
        // each ore he sees, plus the rest of its vein (a player clears the whole patch)
        java.util.LinkedHashSet<BlockPos> all = new java.util.LinkedHashSet<>();
        for (BlockPos o : ores) {
            all.add(o);
            net.minecraft.world.level.block.Block kind = level.getBlockState(o).getBlock();
            java.util.ArrayDeque<BlockPos> todo = new java.util.ArrayDeque<>(java.util.List.of(o));
            while (!todo.isEmpty() && all.size() < 64) { // the whole vein, however big
                BlockPos c = todo.poll();
                for (BlockPos n : BlockPos.betweenClosed(c.offset(-1, -1, -1), c.offset(1, 1, 1))) { // edges and corners too: veins run diagonal
                    if (!level.getBlockState(n).is(kind)) continue;
                    if (ownMine) { boolean shows = false; for (Direction dd : Direction.values()) { BlockState nn = level.getBlockState(n.relative(dd)); if (nn.isAir() || !nn.canOcclude()) shows = true; } if (!shows) continue; } // in his mine: only the vein he can see (showing to air), never tunnelling after hidden ore (03:05: chased a vein blind to y 5, ten under his floor)
                    if (all.add(n.immutable())) todo.add(n.immutable());
                }
            }
        }
        ores = new java.util.ArrayList<>(all);
        ores.sort(java.util.Comparator.comparingDouble(p -> p.distSqr(at)));
        // Ore taken out of the floor leaves a hole in the walkway: patch it back like a player would.
        // stone to patch stone: cobble (cobbled deepslate down deep), dirt only if that's all he has
        net.minecraft.world.item.Item filler = g.inventory.find(st -> st.is(net.minecraft.world.item.Items.COBBLESTONE)).getItem();
        if (filler == net.minecraft.world.item.Items.AIR || at.getY() < 0 && g.inventory.count(st -> st.is(net.minecraft.world.item.Items.COBBLED_DEEPSLATE)) > 0)
            filler = g.inventory.find(st -> st.is(net.minecraft.world.item.Items.COBBLED_DEEPSLATE)).getItem();
        if (filler == net.minecraft.world.item.Items.AIR) filler = scaffoldBlock().getItem(); // dirt, any plain stone...
        java.util.List<Task> steps = new java.util.ArrayList<>();
        // the holes that open onto his own mine get patched; ones opening onto a real cave are left alone
        java.util.List<BlockPos> patch = new java.util.ArrayList<>();
        for (BlockPos o : ores) if (!com.claudemod.director.ClaudeLife.isMineCell(g, o) // ore in his path stays open: it's walkway
                && com.claudemod.director.ClaudeLife.touchesMine(g, o)) patch.add(o); // in his mine's walls, its corners and step edges included
        boolean inOwnTunnel = !patch.isEmpty();
        if (!ores.isEmpty()) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] {} ore in the walls at {}, {} of them in his mine's walls (will patch those)", ores.size(), at.toShortString(), patch.size());
        if (!ores.isEmpty() && patch.isEmpty()) com.claudemod.ClaudeMod.LOGGER.info("[Claude life]   (his mine as he knows it: shape {}, entrance {} heading {}, end {} heading {}{}, ownMine {})",
                g.mineShape, g.stairStart == null ? "-" : g.stairStart.toShortString(), g.stairDir, g.mineFace == null ? "-" : g.mineFace.toShortString(), g.mineDir,
                g.mineIsTunnel ? " (tunnel)" : " (stairs)", ownMine);
        // ore in a step's floor (the walkway right above it): put the floor straight back after it, never leave a hole to fall in
        net.minecraft.world.item.Item floorFill = filler;
        for (BlockPos o : ores) {
            steps.add(new Task.Break(o));
            boolean floor = !com.claudemod.director.ClaudeLife.isMineCell(g, o) && com.claudemod.director.ClaudeLife.isMineCell(g, o.above());
            if (floor && floorFill != net.minecraft.world.item.Items.AIR) {
                steps.add(new Task.Pickup(40));
                steps.add(new Task.Place(o, o.getY() < 0 && g.inventory.count(s2 -> s2.is(net.minecraft.world.item.Items.COBBLED_DEEPSLATE)) > 0 ? net.minecraft.world.item.Items.COBBLED_DEEPSLATE : floorFill));
                patch.remove(o);
            }
        }
        if (!ores.isEmpty()) steps.add(new Task.Pickup(Math.min(400, 60 + 20 * ores.size()))); // every drop (a big vein needs time), before moving on
        // back into his mine where he stood, so the patches go in from the mine side (never sealing himself into a pocket)
        if ((inOwnTunnel && filler != net.minecraft.world.item.Items.AIR) || sideTrip) steps.add(new Task.GoTo(at, 0.6));
        // In his own mine: then fill the holes back flush (walls, floor, ceiling), so the mine keeps its shape. Never a cave.
        if (inOwnTunnel && filler != net.minecraft.world.item.Items.AIR) for (BlockPos o : patch) steps.add(new Task.Place(o, o.getY() < 0
                && g.inventory.count(s -> s.is(net.minecraft.world.item.Items.COBBLED_DEEPSLATE)) > 0 ? net.minecraft.world.item.Items.COBBLED_DEEPSLATE : filler));
        for (int i = steps.size() - 1; i >= 0; i--) g.tasks.addFirst(steps.get(i));
        if (!ores.isEmpty()) g.noteDoing("found " + ores.size() + " ore in the walls");
        return true;
    }

    /** Walk over the items that just dropped, so they get picked up (players do this without thinking). */
    /** In the air and dropping (not swimming, not on a ladder): where he'll stand is where he lands. */
    private boolean falling() { return !g.onGround() && !g.isInWater() && !g.onClimbable() && g.getDeltaMovement().y < -0.08; }
    private int fallNoteTick; // the last tick a task waited on his fall (a fall logs once, when the wait starts)
    private void fallNote(String what) { if (g.tickCount - fallNoteTick > 1) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] {}: he's falling (y {}), waiting to land first (WorkGoal.falling)", what, String.format("%.1f", g.getY())); fallNoteTick = g.tickCount; }

    /** Why doPickup won't go for this drop wherever he stands (null: it will): given up on, no room in his pack, inside a block.
     *  The waits that queue a Pickup ask it first (ClaudeLife.nookMove), so they only wait for what the Pickup takes. */
    /** The air pocket behind a cell he'd patch: through faces, never his walkway or his mine's cells; 32 cells, 5 out at most. */
    public static java.util.Set<BlockPos> pocketBehind(com.claudemod.entity.ClaudeEntity g, ServerLevel level, BlockPos pos) {
        java.util.Set<BlockPos> seen = new java.util.LinkedHashSet<>(); java.util.ArrayDeque<BlockPos> todo = new java.util.ArrayDeque<>(); seen.add(pos.immutable()); todo.add(pos.immutable());
        while (!todo.isEmpty() && seen.size() < 32) { BlockPos p = todo.poll(); for (Direction d : Direction.values()) { BlockPos q = p.relative(d);
            if (seen.contains(q) || q.distManhattan(pos) > 5 || !level.getBlockState(q).getCollisionShape(level, q).isEmpty() || (g.blueprint != null && g.blueprint.isWalk(q)) || com.claudemod.director.ClaudeLife.isMineCell(g, q)) continue;
            seen.add(q.immutable()); todo.add(q.immutable()); } }
        return seen;
    }
    /** Ore he can take showing into that pocket (face to face), nearest the cell first, never his walkway's own. */
    public static java.util.List<BlockPos> veinBehind(com.claudemod.entity.ClaudeEntity g, ServerLevel level, BlockPos pos) {
        java.util.Set<BlockPos> pocket = pocketBehind(g, level, pos); java.util.LinkedHashSet<BlockPos> out = new java.util.LinkedHashSet<>();
        for (BlockPos p : pocket) for (Direction d : Direction.values()) { BlockPos q = p.relative(d); if (pocket.contains(q)) continue; BlockState s = level.getBlockState(q);
            if (!com.claudemod.entity.life.MineBlueprint.ore(s) || (g.blueprint != null && g.blueprint.isWalk(q)) || (s.requiresCorrectToolForDrops() && g.inventory.find(st -> st.isCorrectToolForDrops(s)).isEmpty())) continue;
            out.add(q.immutable()); }
        java.util.List<BlockPos> l = new java.util.ArrayList<>(out); l.sort(java.util.Comparator.comparingDouble(q -> q.distSqr(pos))); return l;
    }
    /** What a patch at pos would seal in: ore he can take showing into its pocket, or a valuable drop of his lying in it. Null: nothing. */
    public static String sealsIn(com.claudemod.entity.ClaudeEntity g, ServerLevel level, BlockPos pos) {
        java.util.List<BlockPos> ore = veinBehind(g, level, pos);
        if (!ore.isEmpty()) return ore.size() + " ore (" + level.getBlockState(ore.get(0)).getBlock().getName().getString() + " at " + ore.get(0).toShortString() + ")";
        java.util.Set<BlockPos> pocket = pocketBehind(g, level, pos);
        for (var i : level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(pos).inflate(5), i -> i.isAlive() && !g.isGift(i) && isOreDrop(i.getItem()) && pocket.contains(i.blockPosition())))
            return "his " + i.getItem().getHoverName().getString() + " lying at " + i.blockPosition().toShortString();
        return null;
    }
    public static String pickupSkip(com.claudemod.entity.ClaudeEntity g, net.minecraft.world.entity.item.ItemEntity i) {
        if (!i.isAlive()) return "gone";
        if (g.isGift(i)) return "given up on (no way to it), or theirs";
        if (com.claudemod.entity.life.OwnEvents.waiting(g, i)) return "his own, behind his wall: recover_left goes back for it";
        if (!g.inventory.hasRoomFor(i.getItem())) return "no room for it in his pack";
        BlockPos ip = i.blockPosition();
        if (g.level().getBlockState(ip).isCollisionShapeFullBlock(g.level(), ip)) return "inside a block";
        return null;
    }
    public static boolean valuableDrop(ItemStack s) { return isOreDrop(s); }
    private static java.util.Set<String> pickupSaid; private static int pickupEmptySaid = -1000; // (each drop's skip said once; an empty sweep at most every 5 s)
    private static boolean pickupSkipped(net.minecraft.world.entity.item.ItemEntity i, String why) {
        if (why == null) return false;
        if (pickupSaid == null) pickupSaid = new java.util.HashSet<>(); if (pickupSaid.size() > 512) pickupSaid.clear();
        if (pickupSaid.add(i.getUUID() + why)) com.claudemod.ClaudeMod.LOGGER.info("[Claude pickup] not going for {} {} at {}: {} (WorkGoal.doPickup)", i.getItem().getCount(), i.getItem().getHoverName().getString(), i.blockPosition().toShortString(), why);
        return true;
    }

    private boolean doPickup(Task.Pickup t) {
        if (ticks > t.maxTicks()) { if (t == holdPickup) leaveHeld(); for (var i0 : g.level().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, g.getBoundingBox().inflate(8), i1 -> i1.isAlive() && !g.isGift(i1) && isOreDrop(i1.getItem()))) { com.claudemod.ClaudeMod.LOGGER.info("[Claude life] the pickup ran out of time with {} still at {}", i0.getItem().getHoverName().getString(), i0.blockPosition().toShortString()); com.claudemod.entity.life.OwnEvents.left(g, i0, "the pickup ran out of time"); leaveOwn(i0); } return true; } // (valuables still lying there go on his book: "you didn't grab them all" finds them)
        if (falling()) { fallNote("a pickup"); return false; } // dropping (down the hole he just dug): what's reachable is judged from where he lands
        { Vec3 e = com.claudemod.director.ClaudeLife.portalEdge(); if (e != null && Math.abs(g.getY() - e.y) <= 0.6) return true; } // up his casting pillar: nothing is chased from up here
        if (ticks == 1 && !(lastPickupTask == t)) { pickupEffort = 0; pickupTowers = 0; lastPickupTask = t; }
        ServerLevel level = (ServerLevel) g.level();
        var items = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, g.getBoundingBox().inflate(8, 10, 8), net.minecraft.world.entity.item.ItemEntity::isAlive); // tall: logs fall from treetops
        final int seen0 = items.size();
        items.removeIf(i -> pickupSkipped(i, pickupSkip(g, i))); // given up on, no room, inside a block: the same filter the waits ask (ClaudeLife.nookMove)
        items.removeIf(i -> (i.getItem().is(net.minecraft.tags.ItemTags.SAPLINGS) || i.getItem().is(net.minecraft.world.item.Items.STICK)) && i.getY() > g.getY() + 2 && pickupSkipped(i, "a sapling or stick up in the leaves: not worth a climb"));
        com.claudemod.entity.life.MineBlueprint bp = com.claudemod.director.ClaudeLife.bpMode(g) && g.blueprint != null && !g.blueprint.isEmpty() && g.blueprint.touches(g.blockPosition()) ? g.blueprint : null; // in his mine (blueprint mode)
        if (t == holdPickup && holdAt != null) { BlockPos cell = holdAt; items.removeIf(i -> i.position().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(cell)) > 1.6 * 1.6 && pickupSkipped(i, "the hold is for the drop in the cell he's placing at")); } // (the placement's hold: what's in that cell, then the block; the nearest drop 6 off walked him away from the post and the walk back failed)
        if (digInAt != null && digInTasks != null && digInTasks.stream().anyMatch(x -> x == t)) { BlockPos hole = digInAt; items.removeIf(i -> (Math.abs(i.getBlockX() - hole.getX()) > 1 || Math.abs(i.getBlockZ() - hole.getZ()) > 1) && pickupSkipped(i, "the cover's own pickup takes only what's in the hole")); } // the cover's own pickup (the dirt for the lid): only what's in the hole (it walked him out of his half-dug hole at night to drops lying about)
        // (a full pack: nothing to be had: pickupSkip above)
        if (bp != null) items.removeIf(i -> !bp.touches(i.blockPosition()) && !com.claudemod.director.ClaudeLife.inHisNook(g, i.blockPosition()) && !airJoinedToMine(level, bp, i.blockPosition()) && pickupSkipped(i, "in his mine only drops in it, in his nook or in open air joined to it count")); // only what lies in the mine or in open air joined to it (an ore hole dug off his walkway, the drop a cell deeper in: it was invisible here and the patch sealed it in): a drop that drifted up through the rock lured him into cutting his way up through his own ceiling
        if (items.isEmpty()) { if ((seen0 > 0 || g.tickCount - pickupEmptySaid > 100)) { pickupEmptySaid = g.tickCount; com.claudemod.ClaudeMod.LOGGER.info("[Claude pickup] nothing to pick up round {} ({} drop(s) in his sweep, all skipped as said) (WorkGoal.doPickup)", g.blockPosition().toShortString(), seen0); } return true; }
        var nearest = items.stream().min(java.util.Comparator.comparingDouble(g::distanceToSqr)).get();
        g.getLookControl().setLookAt(nearest);
        if (ticks % 8 != 1) return false;
        BlockPos ip = nearest.blockPosition();
        if (bp != null && isOreDrop(nearest.getItem()) && !g.getBoundingBox().inflate(1.0, 0.5, 1.0).intersects(nearest.getBoundingBox())) { // out of his reach in his mine, and his own next dig opens the way to it: that dig first (193: the drop in its ore hole beside the slot had him down the stairs and cutting the step's bottom ahead of its head cell)
            Task opens = null; int k0 = 0;
            for (Task q0 : g.tasks) { if (++k0 > 8) break; if (q0 instanceof Task.Break b0 && b0.pos().distSqr(ip) <= 2 && bp.isWalk(b0.pos()) && !level.getBlockState(b0.pos()).getCollisionShape(level, b0.pos()).isEmpty()) { opens = q0; break; } }
            if (opens != null) {
                Task later = new Task.Pickup(t.maxTicks()); if (t == holdPickup) holdPickup = (Task.Pickup) later; lastPickupTask = later;
                java.util.ArrayDeque<Task> before = new java.util.ArrayDeque<>(); while (!g.tasks.isEmpty() && g.tasks.peekFirst() != opens) before.addLast(g.tasks.pollFirst());
                g.tasks.pollFirst(); g.tasks.addFirst(later); g.tasks.addFirst(opens); while (!before.isEmpty()) g.tasks.addFirst(before.pollLast()); // (the same tasks back in their order, the pickup straight after the dig: each keeps who queued it)
                com.claudemod.ClaudeMod.LOGGER.info("[Claude pickup] {} at {}: out of reach from {}, and his next dig at {} opens the way to it: the dig first, the pickup straight after (WorkGoal.doPickup)", nearest.getItem().getHoverName().getString(), ip.toShortString(), g.blockPosition().toShortString(), ((Task.Break) opens).pos().toShortString());
                return true;
            }
        }
        // 1. walk there if he can: onto it, or right next to it (in a wall pocket, players grab it from beside it)
        var path = g.getNavigation().createPath(ip, 0);
        if (path == null || !path.canReach()) path = g.getNavigation().createPath(ip, 1);
        if (path != null && path.canReach()) {
            var cur = g.getNavigation().getPath();
            if (!g.getNavigation().isDone() && cur != null && cur.getTarget().equals(path.getTarget())) return false; // already on his way to it: keep going (a fresh route every 8 ticks starts from the middle of his own block: back from the edge of the step, forward, back, his "shuffle" before stepping down to loot)
            g.getNavigation().moveTo(path, 1.0); return false;
        }
        // In a pocket with a block over it, or a block between him and it at his height: mine that block first
        double hz = Math.sqrt(Math.pow(nearest.getX() - g.getX(), 2) + Math.pow(nearest.getZ() - g.getZ(), 2));
        if (hz < 3 && pickupEffort < 10 && nearest.getY() < g.getY() - 0.5) { // down in a hole in the floor, a block over it
            for (BlockPos q : new BlockPos[] {ip.above()}) {
                if (q.equals(g.blockPosition()) || q.equals(g.blockPosition().above()) || q.equals(g.blockPosition().below())) continue;
                BlockState qs = level.getBlockState(q);
                if (qs.getCollisionShape(level, q).isEmpty() || qs.getDestroySpeed(level, q) < 0 || qs.hasBlockEntity() || qs.is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE)) continue;
                if (g.getTarget() instanceof net.minecraft.server.level.ServerPlayer o && com.claudemod.director.ClaudeLife.protectedBlock(g, o, q)) continue;
                if (bp != null && bp.isShell(q)) continue; // his mine's shell stays
                pickupEffort++;
                Task again2 = new Task.Pickup(t.maxTicks());
                lastPickupTask = again2;
                boolean mineWall = !com.claudemod.director.ClaudeLife.isMineCell(g, q);
                if (mineWall) { mineWall = false; for (Direction dd : Direction.values()) if (com.claudemod.director.ClaudeLife.isMineCell(g, q.relative(dd))) mineWall = true; }
                if (mineWall) g.tasks.addFirst(new Task.Place(q.immutable(), q.getY() < 0 ? net.minecraft.world.item.Items.COBBLED_DEEPSLATE : net.minecraft.world.item.Items.COBBLESTONE)); // his mine's wall: back in after
                g.tasks.addFirst(again2);
                g.tasks.addFirst(new Task.Break(q.immutable()));
                return true;
            }
        }
        // Right beside him (a wall pocket, a diagonal nook): just step into it, no path needed
        boolean wetDrop = !level.getFluidState(ip).isEmpty();
        int edgeDrop = 0; BlockPos underIt = new BlockPos(ip.getX(), g.getBlockY() - 1, ip.getZ());
        while (edgeDrop < 4 && level.getBlockState(underIt).getCollisionShape(level, underIt).isEmpty() && level.getFluidState(underIt).isEmpty()) { edgeDrop++; underIt = underIt.below(); } // (an item that popped off the trunk top he stood on: he hopped after it, off the edge, 6 blocks down)
        if (hz < 4.2 && nearest.getY() - g.getY() < 1.7 && nearest.getY() - g.getY() > (wetDrop ? -2.6 : -1.3) && pickupEffort < 6 && (edgeDrop < 3 || ip.equals(g.blockPosition()))) {
            pickupEffort++;
            g.getMoveControl().setWantedPosition(nearest.getX(), nearest.getY(), nearest.getZ(), 0.8);
            if (nearest.getY() - g.getY() > 0.6 && hz < 1.6 && g.onGround()) g.getJumpControl().jump(); // a step up: hop for it
            return false;
        }
        // Can't walk onto it. Work toward it like a player, a few moves at most, then come back to sweeping.
        boolean valuable = isOreDrop(nearest.getItem());
        { var it0 = nearest.getItem(); boolean junkStone = it0.is(net.minecraft.world.item.Items.COBBLESTONE) || it0.is(net.minecraft.world.item.Items.GRANITE) || it0.is(net.minecraft.world.item.Items.ANDESITE) || it0.is(net.minecraft.world.item.Items.DIORITE) || it0.is(net.minecraft.world.item.Items.TUFF) || it0.is(net.minecraft.world.item.Items.COBBLED_DEEPSLATE) || it0.is(net.minecraft.world.item.Items.DIRT) || it0.is(net.minecraft.world.item.Items.GRAVEL) || it0.is(net.minecraft.world.item.Items.NETHERRACK);
          if (junkStone && (g.inventory.count(x -> x.is(net.minecraft.world.item.Items.COBBLESTONE) || x.is(net.minecraft.world.item.Items.COBBLED_DEEPSLATE)) >= 32 || nearest.getY() - g.getY() < -1.5)) { // (or down a pit: no jump 4 down a trench for two cobble that fell in as his bridge came up) // a stray stone block he can't just walk to, with a pile of cobble on him: left, like a player (16 tries, step cuts and a 4-block fall for one granite)
            g.markGift(nearest); return true; } }
        if (++pickupEffort > (valuable ? 40 : 16)) { // ponytail: 16 moves per sweep (40 for ore), then give up on what's left
            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] left {} at {} behind: tried 16 ways to get it", nearest.getItem().getHoverName().getString(), ip.toShortString());
            com.claudemod.entity.life.OwnEvents.left(g, nearest, "tried 16 ways to get it"); // (on his book: "you didn't grab them all" resolves to it)
            if (!leaveOwn(nearest)) g.markGift(nearest); // given up on: he doesn't keep going back for it (a valuable of his by his own mine: his still, recover_left)
            return true;
        }
        Task again = new Task.Pickup(t.maxTicks());
        if (t == holdPickup) holdPickup = (Task.Pickup) again; // (the patch's Pickup, still)
        lastPickupTask = again; // keep the effort count across the re-queue
        // 2. a block between him and it (item in a nook, behind a wall, buried in a hole): mine it
        BlockPos inWay = blockInTheWay(level, ip);
        if (inWay != null && (g.attempts.within(com.claudemod.entity.life.Attempts.Kind.REACH, inWay, g.tickCount, 20 * 60) || g.attempts.within(com.claudemod.entity.life.Attempts.Kind.BREAK, inWay, g.tickCount, 20 * 60))) { com.claudemod.ClaudeMod.LOGGER.info("[Claude pickup] the block in the way of {} at {} was refused a moment ago: not asking again", nearest.getItem().getHoverName().getString(), inWay.toShortString()); inWay = null; } // (04:21 the same refused dig asked for every second)
        if (inWay != null && level.getBlockState(inWay).getDestroySpeed(level, inWay) >= 0 && !level.getBlockState(inWay).hasBlockEntity() && !(bp != null && bp.isShell(inWay)) && !(bp == null && !com.claudemod.director.ClaudeLife.isMineCell(g, inWay) && com.claudemod.director.ClaudeLife.touchesMine(g, inWay))) { // (never his mine's wall: the raw iron in a wall hole had him break the block over it, 12 fixes round that row; his staircase's walls too: the drop from a table notch had him cutting along his stair wall after it, the repairs he'd queued lost in the mess)
            final BlockState wayState = level.getBlockState(inWay);
            if (!valuable && wayState.requiresCorrectToolForDrops() && g.inventory.find(s -> s.isCorrectToolForDrops(wayState)).isEmpty()) { // stone with no pick: no way to it (stage 182: his own pod wall, then "no tool ... (broke?), re-tooling", a plan for a pick, gear_up and a walk off, all for a bed item 8 off)
                com.claudemod.ClaudeMod.LOGGER.info("[Claude pickup] {} at {} is behind {} at {} and nothing on him mines it: not digging through for it, left (WorkGoal.doPickup)", nearest.getItem().getHoverName().getString(), ip.toShortString(), name(wayState), inWay.toShortString());
                g.markGift(nearest); return true;
            }
            g.tasks.addFirst(again);
            g.tasks.addFirst(new Task.Break(inWay));
            com.claudemod.ClaudeMod.LOGGER.info("[Claude pickup] {} at {} is behind {} at {}: digging that out first (WorkGoal.doPickup)", nearest.getItem().getHoverName().getString(), ip.toShortString(), name(wayState), inWay.toShortString());
            return true;
        }
        double dy = nearest.getY() - g.getY();
        double horiz = Math.sqrt(Math.pow(nearest.getX() - g.getX(), 2) + Math.pow(nearest.getZ() - g.getZ(), 2));
        // 3. down in a pit: dig a step down toward it (no jumping into holes). Only for something worth it (ore) or a short
        //    step inside his own mine: a player doesn't dig a new hole into the ground after a dropped dirt block.
        if (dy < -1.5 && g.onGround() && bp == null && (valuable || dy > -2.6 && com.claudemod.director.ClaudeLife.inHisMine(g, g.blockPosition()))) { // (in his blueprint mine: no step cut for a drop, the stairs are the way down)
            Direction dir = horiz < 0.5 ? g.getDirection() : Direction.getNearest(nearest.getX() - g.getX(), 0, nearest.getZ() - g.getZ());
            BlockPos step = g.blockPosition().relative(dir).below();
            g.tasks.addFirst(again);
            g.tasks.addFirst(new Task.GoTo(step, 0.6));
            for (BlockPos b : new BlockPos[] {step.above(2), step.above(), step}) if (!level.getBlockState(b).getCollisionShape(level, b).isEmpty()) g.tasks.addFirst(new Task.Break(b));
            return true;
        }
        // 4. caught up in a tree's leaves: knock out the leaves it's lying on so it falls down to him
        BlockPos rest = level.getBlockState(ip).is(net.minecraft.tags.BlockTags.LEAVES) ? ip : ip.below();
        if (dy > 1.5 && level.getBlockState(rest).is(net.minecraft.tags.BlockTags.LEAVES) && treeBlockInWay(rest) && pickupEffort <= 8) {
            g.tasks.addFirst(again);
            g.tasks.addFirst(new Task.Break(rest));
            return true;
        }
        // 5. a jump gets him there (a ledge up to ~3 blocks right beside it): jump for it, like a player
        if (dy > 1.5 && dy < 3.4 && g.onGround()) {
            g.getMoveControl().setWantedPosition(nearest.getX(), g.getY(), nearest.getZ(), 1.0);
            if (horiz < 1.3) g.getJumpControl().jump();
            return false;
        }
        // 6. up on a ledge right above: tower up to it (twice a sweep at most), and back down after
        if (dy > 1.5 && dy <= 4.5 && horiz < 2.5 && bp == null && !scaffoldBlock().isEmpty() && pickupTowers++ < 2 && !g.pillarBlocks.contains(standingOn())) { // (never a tower inside his mine: "stopped towering: no head room" and a ceiling gone) // (a short tower once; never a second one from the top of his own pillar: that was a 21-block spire after a phantom)
            g.tasks.addFirst(new Task.Pillar(-1));
            g.tasks.addFirst(again);
            g.tasks.addFirst(new Task.Pillar((int) Math.max(1, Math.min(4, Math.round(dy)))));
            return true;
        }
        // in the water (a catch that sank, a drop off the bank): swim straight to it like a player, down and back up for air (the body surfaces by itself); "no way to it" left salmon behind
        if ((g.isInWater() || level.getFluidState(ip).is(net.minecraft.tags.FluidTags.WATER)) && level.getFluidState(ip).is(net.minecraft.tags.FluidTags.WATER) && pickupEffort <= 30) {
            Vec3 to = nearest.position();
            g.getNavigation().stop();
            g.setSprinting(false);
            g.getLookControl().setLookAt(to.x, to.y, to.z, 30, 30);
            g.getMoveControl().setWantedPosition(to.x, to.y, to.z, 1.0);
            return false;
        }
        // 5. across a gap: walk as close as he can, then bridge
        if (pathGetsCloser(path, nearest.position())) { g.getNavigation().moveTo(path, 1.0); return false; }
        boolean junkDrop = nearest.getItem().is(net.minecraft.world.item.Items.COBBLESTONE) || nearest.getItem().is(net.minecraft.world.item.Items.COBBLED_DEEPSLATE) || com.claudemod.director.ClaudeLife.junkItem(nearest.getItem());
        if (!junkDrop && bridgeToward(nearest.position())) return false; // (junk isn't worth a block put out over a drop: it waits)
        if (valuable && bp == null && digThroughToward(nearest.position())) { g.tasks.add(again); return true; } // ore: never left behind, dig straight to it (not through his mine's shell)
        if (valuable && bp != null && airJoinedToMine(level, bp, ip)) { // ore dropped in an ore hole off his tunnel (head-high, 2 deep: 03:32 two diamonds out of reach, then patched in): the player's way, break the wall block under the hole, step in, take it; the wall goes back on the patch list
            { Vec3 to0 = nearest.position().subtract(g.position()); BlockPos nx0 = g.blockPosition().relative(Direction.getNearest(to0.x, 0, to0.z)); // the wall cells he opens go back once he has it: queued now, so the shell guard lets the break through (03:41 it refused them 626 times: the Pickup asked again and again)
              for (BlockPos w0 : new BlockPos[] {nx0, nx0.above()}) if (bp.isShell(w0) && !level.getBlockState(w0).getCollisionShape(level, w0).isEmpty()) g.tasks.addFirst(new Task.Place(w0.immutable(), w0.getY() < 0 && g.inventory.count(x -> x.is(net.minecraft.world.item.Items.COBBLED_DEEPSLATE)) > 0 ? net.minecraft.world.item.Items.COBBLED_DEEPSLATE : net.minecraft.world.item.Items.COBBLESTONE)); }
            g.tasks.addFirst(again);
            if (digThroughToward(nearest.position())) { com.claudemod.ClaudeMod.LOGGER.info("[Claude life] {} at {} is down an ore hole off his tunnel: opening the wall under it to step in for it", nearest.getItem().getHoverName().getString(), ip.toShortString()); return true; }
            g.tasks.remove(again);
        }
        com.claudemod.ClaudeMod.LOGGER.info("[Claude life] left {} at {} behind: no way to it (he's at {}, {} up)", nearest.getItem().getHoverName().getString(),
                ip.toShortString(), g.blockPosition().toShortString(), String.format("%.1f", dy));
        com.claudemod.entity.life.OwnEvents.left(g, nearest, "no way to it"); // (on his book: "you didn't grab all your diamonds" resolves to it)
        if (!leaveOwn(nearest)) g.markGift(nearest); // given up on: he doesn't keep going back for it (a valuable of his by his own mine: his still, recover_left)
        g.tasks.addFirst(again); return true; // nothing works: leave it
    }

    /** Patch his mine: any opening into a cave around this step (walls, floor, ceiling) gets filled, never his own mine's open space. */
    private boolean doSealStep(Task.SealStep t) {
        ServerLevel level = level();
        if (com.claudemod.director.ClaudeLife.bpMode(g)) return true; // the blueprint's reconcile pass patches the shell
        Direction cw = t.dir().getClockWise();
        java.util.List<BlockPos> holes = new java.util.ArrayList<>();
        java.util.function.Consumer<BlockPos> check = q -> {
            if (TRACE) com.claudemod.ClaudeMod.LOGGER.info("[STRACE] {} mine={} empty={} sky={} repl={}", q.toShortString(), com.claudemod.director.ClaudeLife.isMineCell(g, q),
                    level.getBlockState(q).getCollisionShape(level, q).isEmpty(), level.canSeeSky(q), level.getBlockState(q).canBeReplaced());
            if (holes.size() >= 12 || com.claudemod.director.ClaudeLife.isMineCell(g, q)) return;
            boolean openSky = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, q.getX(), q.getZ()) <= q.getY(); // (not sky light: it lags; under a tree is still the open surface)
            if (!level.getBlockState(q).getCollisionShape(level, q).isEmpty() || openSky) return; // solid, or the open surface
            if (level.getFluidState(q).isSource() && !level.getFluidState(q).is(net.minecraft.tags.FluidTags.WATER)) return; // lava: leave to the lava rules
            var qs = level.getBlockState(q);
            boolean junk = qs.is(net.minecraft.tags.BlockTags.RAILS) || qs.is(net.minecraft.world.level.block.Blocks.COBWEB); // a mineshaft's rail or web in his wall: a hole all the same
            if (!qs.canBeReplaced() && !junk) return; // a torch, a ladder...: not a hole
            holes.add(q.immutable());
        };
        for (int w = 0; w < t.width(); w++) {
            BlockPos col = t.feet().relative(cw, w);
            check.accept(col.below());          // floor
            check.accept(col.above(t.tall()));  // ceiling
        }
        for (int h = 0; h < t.tall(); h++) {
            check.accept(t.feet().relative(cw.getOpposite()).above(h));     // left wall
            check.accept(t.feet().relative(cw, t.width()).above(h));        // right wall
        }
        // holes he had to leave earlier (no blocks on him then), close by: these too
        g.pendingPatches.removeIf(q -> !level.getBlockState(q).canBeReplaced() || !level.getBlockState(q).getCollisionShape(level, q).isEmpty());
        g.pendingPatches.stream().filter(q -> q.distSqr(t.feet()) <= 4 * 4 && !holes.contains(q) && !com.claudemod.director.ClaudeLife.isMineCell(g, q))
                .sorted(java.util.Comparator.comparingDouble(q -> q.distSqr(t.feet()))).limit(8).forEach(holes::add); // a few close ones at a time, in reach
        if (holes.isEmpty()) return true;
        // the mine's walls are stone: cobble (or cobbled deepslate down deep), never a patchwork of dirt
        net.minecraft.world.item.Item fill = g.inventory.find(st -> st.is(net.minecraft.world.item.Items.COBBLESTONE)).getItem();
        if (fill == net.minecraft.world.item.Items.AIR || t.feet().getY() < 0 && g.inventory.count(st -> st.is(net.minecraft.world.item.Items.COBBLED_DEEPSLATE)) > 0)
            fill = g.inventory.find(st -> st.is(net.minecraft.world.item.Items.COBBLED_DEEPSLATE)).getItem();
        if (fill == net.minecraft.world.item.Items.AIR) { g.pendingPatches.addAll(holes); return true; } // none yet: remembered, done once he has cobble
        holes.forEach(g.pendingPatches::remove);
        holes.sort(java.util.Comparator.comparingInt(BlockPos::getY)); // floor first: something to stand on, then walls
        for (int i = holes.size() - 1; i >= 0; i--) {
            g.tasks.addFirst(new Task.Place(holes.get(i), fill));
            if (!level.getBlockState(holes.get(i)).canBeReplaced()) g.tasks.addFirst(new Task.Break(holes.get(i))); // the rail comes out first
        }
        com.claudemod.ClaudeMod.LOGGER.info("[Claude life] mine broke into a cave at {}: sealing {} openings", t.feet().toShortString(), holes.size());
        // not a cave: someone's mine (torches, rails, placed blocks past the hole). Sealed, and this tunnel stops here.
        boolean otherMine = false;
        for (BlockPos h : holes) for (BlockPos q : BlockPos.betweenClosed(h.offset(-2, -2, -2), h.offset(2, 2, 2))) {
            if (otherMine || com.claudemod.director.ClaudeLife.isMineCell(g, q) || com.claudemod.director.ClaudeLife.touchesMine(g, q) || holes.contains(q)) continue;
            var qs = level.getBlockState(q);
            // (torches and cobblestone are what HE puts down: his own earlier work next to a new hole is not someone else's mine)
            if (qs.is(net.minecraft.tags.BlockTags.RAILS) || qs.is(net.minecraft.tags.BlockTags.PLANKS) || qs.is(net.minecraft.tags.BlockTags.FENCES)
                    || qs.is(net.minecraft.world.level.block.Blocks.LADDER) || qs.hasBlockEntity()) otherMine = true;
        }
        if (otherMine) {
            BlockPos f = t.feet(); Direction dir = t.dir();
            java.util.function.Predicate<BlockPos> ahead = q -> {
                int fwd = (q.getX() - f.getX()) * dir.getStepX() + (q.getZ() - f.getZ()) * dir.getStepZ();
                int side = Math.abs((q.getX() - f.getX()) * dir.getStepZ()) + Math.abs((q.getZ() - f.getZ()) * dir.getStepX());
                return fwd > 0 && fwd <= 32 && side <= t.width() + 1 && q.getY() >= f.getY() - 1 && q.getY() <= f.getY() + t.tall();
            };
            int before = g.tasks.size();
            g.tasks.removeIf(x -> (x instanceof Task.Break b && ahead.test(b.pos())) || (x instanceof Task.SealStep ss && ahead.test(ss.feet()))
                    || (x instanceof Task.WallTorch w && ahead.test(w.pos())) || (x instanceof Task.GoTo go && ahead.test(go.pos())));
            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] that's another mine at {}, not a cave: sealed it off and stopped this tunnel ({} steps dropped)", t.feet().toShortString(), before - g.tasks.size());
        }
        return true;
    }

    /**
     * In water that flooded his mine: stop it where it comes in, like a player. Every spot within 6 where water flows into
     * his mine from outside gets blocked for good; a source inside the mine gets blocked and mined straight back out, so
     * the walkway stays open. The water left inside then drains. True if he queued anything.
     */
    private int waterOutTick = -100000;
    private boolean blockWaterInflow() {
        ServerLevel level = level();
        ItemStack plug = solidScaffold();
        int n = 0, skipped = 0;
        if (!plug.isEmpty()) for (BlockPos q0 : BlockPos.betweenClosed(g.blockPosition().offset(-6, -3, -6), g.blockPosition().offset(6, 4, 6))) {
            if (n >= 12 || !level.getFluidState(q0).is(net.minecraft.tags.FluidTags.WATER)) continue;
            BlockPos q = q0.immutable();
            if (q.equals(g.blockPosition()) || q.equals(g.blockPosition().above())) continue; // not where he stands
            boolean inMine = com.claudemod.director.ClaudeLife.isMineCell(g, q);
            boolean entry = false; // outside his mine, right next to it: where it pours in
            if (!inMine) for (Direction d : Direction.values()) if (com.claudemod.director.ClaudeLife.isMineCell(g, q.relative(d))) { entry = true; break; }
            if (!entry && !(inMine && level.getFluidState(q).isSource() && q.distSqr(g.blockPosition()) <= 16)) continue;
            if (inMine && g.blueprint != null && !g.blueprint.isEmpty()) { int qr = g.blueprint.rowOf(q), mr = g.blueprint.rowOf(g.blockPosition()); if (mr < 0) mr = g.blueprint.rowOf(g.blockPosition().above()); if (qr >= 0 && mr >= 0 && qr < mr) continue; } // never his way back: a walkway cell toward his entrance stays open (he walled himself into a water cell at 50, 18, 82 and drowned; a player walls the water out from the dry side)
            if (g.attempts.within(com.claudemod.entity.life.Attempts.Kind.PLACE, q, g.tickCount, 20 * 300) || com.claudemod.entity.life.Watchdog.off(g, new Task.Place(q, plug.getItem()))) { skipped++; continue; } // (a plug that failed there, or one the watchdog wrote off: not queued again every tick, 150 times in 5 min live)
            if (inMine) g.tasks.addFirst(new Task.Break(q));
            g.tasks.addFirst(new Task.Place(q, plug.getItem()));
            n++;
        }
        if (n == 0 && skipped > 0 && g.isInWater() && g.tickCount - waterOutTick > 20 * 10) { // every plug failed already: out of the water to dry ground, not standing in it re-queueing them
            waterOutTick = g.tickCount; BlockPos dry = null; double bd = Double.MAX_VALUE;
            for (BlockPos q0 : BlockPos.betweenClosed(g.blockPosition().offset(-5, -2, -5), g.blockPosition().offset(5, 2, 5))) {
                if (!level.getFluidState(q0).isEmpty() || !level.getFluidState(q0.above()).isEmpty() || !level.getBlockState(q0).getCollisionShape(level, q0).isEmpty() || !level.getBlockState(q0.above()).getCollisionShape(level, q0.above()).isEmpty()) continue;
                if (!level.getBlockState(q0.below()).isFaceSturdy(level, q0.below(), Direction.UP) || com.claudemod.entity.body.Controls.lavaCell(g, q0)) continue;
                double dd = q0.distSqr(g.blockPosition()); if (dd < bd) { bd = dd; dry = q0.immutable(); }
            }
            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] in water at {}: its {} plug(s) all failed or written off already: {} (WorkGoal.blockWaterInflow)", g.blockPosition().toShortString(), skipped, dry == null ? "no dry ground within 5, waiting it out" : "out to dry ground at " + dry.toShortString());
            if (dry != null) { g.tasks.addFirst(new Task.GoTo(dry, 0.5)); return true; }
        }
        if (n > 0) g.attempts.counters.merge("aquiferPlugs", n, Integer::sum);
        if (n > 0) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] in water at {}: blocking {} spots where it comes in", g.blockPosition().toShortString(), n);
        return n > 0;
    }

    /** The whole tree, every time: whatever logs of it are still standing get chopped (twice more at most). */
    private boolean doReplant(Task.Replant t) {
        ServerLevel level = (ServerLevel) g.level();
        ItemStack sapling = g.inventory.find(s -> s.is(net.minecraft.tags.ItemTags.SAPLINGS));
        if (sapling.isEmpty()) { // none on him yet: the leaves are still coming down; a few seconds, a pickup, once more
            if (t.tries() >= 3) { com.claudemod.director.ClaudeLife.replantDue().add(t.pos().immutable()); com.claudemod.ClaudeMod.LOGGER.info("[Claude life] no sapling yet for the tree at {}: owed one, planted when he next has some", t.pos().toShortString()); return true; }
            g.tasks.addFirst(new Task.Replant(t.pos(), t.tries() + 1));
            g.tasks.addFirst(new Task.Pickup(100));
            g.tasks.addFirst(new Task.Wait(60, false));
            return true;
        }
        if (!level.getBlockState(t.pos()).canBeReplaced()) return true;
        g.tasks.addFirst(new Task.Place(t.pos(), sapling.getItem()));
        com.claudemod.ClaudeMod.LOGGER.info("[Claude life] a sapling back where the tree at {} stood", t.pos().toShortString());
        return true;
    }

    private boolean doFinishTree(Task.FinishTree t) {
        ServerLevel level = (ServerLevel) g.level();
        BlockPos b = t.base();
        lookRound(level, b); // a look up and round it before calling it done
        java.util.List<BlockPos> left = treeLogs(level, b);
        boolean owed = !left.isEmpty() && ((b.equals(chopLast) && !chopSkip.isEmpty()) || t.round() >= 6); // the chop had to leave some: not done, owed
        if (left.isEmpty() || owed) {
            // the tree's down (or the job's over for now): his towers around it come down too (top first), nothing left standing
            java.util.List<BlockPos> towers = new java.util.ArrayList<>();
            for (BlockPos p : g.pillarBlocks) if (p.distSqr(b) <= 10 * 10 && !level.getBlockState(p).isAir()) towers.add(p);
            if (!towers.isEmpty()) {
                towers.sort(java.util.Comparator.comparingInt(BlockPos::getY));
                g.pillarBlocks.removeAll(towers);
                g.tasks.addFirst(new Task.Pickup(80));
                for (BlockPos p : towers) g.tasks.addFirst(new Task.Break(p)); // lowest pushed first, so the top comes off first
                com.claudemod.ClaudeMod.LOGGER.info("[Claude life] tree at {}{}: taking down {} blocks of his towers", b.toShortString(), owed ? " left for now" : " done", towers.size());
            }
            if (owed) { com.claudemod.entity.life.TempLedger.treeOwed(g, level, b, left.size(), chopWhy.isEmpty() ? "the chop keeps ending with logs up" : chopWhy); return true; }
            treeMem().remove(b); com.claudemod.entity.life.TempLedger.treeDone(g, b);
            // and a sapling back where the trunk stood, so there's wood again later (the user's rule: trees round the base); the pickup first gets the saplings the leaves drop
            ItemStack sapling = g.inventory.find(s -> s.is(net.minecraft.tags.ItemTags.SAPLINGS));
            BlockPos ground = b.below();
            boolean stepOnStump = !level.getBlockState(b).canBeReplaced() && com.claudemod.entity.life.TempLedger.has(g, b); // a block of his put down for now on the stump: it comes up, then the sapling
            if (stepOnStump && (level.getBlockState(ground).is(net.minecraft.tags.BlockTags.DIRT) || level.getBlockState(ground).is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK))) { g.tasks.add(new Task.Break(b.immutable())); g.tasks.add(new Task.Pickup(40)); var te = com.claudemod.entity.life.TempLedger.entry(g, b); com.claudemod.ClaudeMod.LOGGER.info("[Claude temp] his {} ({}) stands on the stump at {}: back up first, then the sapling (WorkGoal.doFinishTree)", te.item, te.why, b.toShortString()); }
            if ((level.getBlockState(b).canBeReplaced() || stepOnStump) && (level.getBlockState(ground).is(net.minecraft.tags.BlockTags.DIRT) || level.getBlockState(ground).is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK))) {
                if (!sapling.isEmpty()) { g.tasks.add(new Task.Place(b.immutable(), sapling.getItem())); com.claudemod.ClaudeMod.LOGGER.info("[Claude life] tree at {} done: every log down, a sapling back in its place", b.toShortString()); }
                else { g.tasks.add(new Task.Pickup(60)); g.tasks.add(new Task.Replant(b.immutable())); com.claudemod.ClaudeMod.LOGGER.info("[Claude life] tree at {} done: every log down, a sapling once the leaves drop one", b.toShortString()); } // the leaves drop their saplings over the next moments: one gets planted once it's in his pocket
            } else com.claudemod.ClaudeMod.LOGGER.info("[Claude life] tree at {} done: every log down", b.toShortString());
            return true;
        }
        com.claudemod.ClaudeMod.LOGGER.info("[Claude life] tree at {} isn't finished: {} logs still standing, going back for them", b.toShortString(), left.size());
        g.tasks.addFirst(new Task.ChopTree(b, t.round() + 1)); // (it ends with the next FinishTree round)
        return true;
    }

    // ---- one tree, like a player (2026-10-29): the trunk from the bottom, a tower in its own column when the next log is out of
    // reach, every scaffold block back before moving on, a far bough from a tower of its own; never a bridge or a cut for a log ----
    private BlockPos chopBase, chopGoal, chopUnder; private int chopTop, chopIdleAt, chopLogsLast, chopScaffLast, chopStandTicks; private boolean chopDown;
    private final java.util.Set<BlockPos> chopSkip = new java.util.HashSet<>(), chopKnown = new java.util.HashSet<>(); private final java.util.Map<BlockPos, Integer> chopTries = new java.util.HashMap<>();

    private BlockPos chopLast; private String chopWhy = "";
    private java.util.Map<BlockPos, java.util.Set<BlockPos>> treeMem; // each tree's logs as he found them, by its base (lazy: fields added live start null)
    private java.util.Map<BlockPos, java.util.Set<BlockPos>> treeMem() { if (treeMem == null) treeMem = new java.util.HashMap<>(); if (treeMem.size() > 64) treeMem.clear(); return treeMem; }
    private static boolean isLog(ServerLevel level, BlockPos p) { return level.getBlockState(p).is(net.minecraft.tags.BlockTags.LOGS); }

    /**
     * One tree's logs still standing (never theirs, never another tree's), lowest first: its trunk from the base up (all four
     * columns of a 2x2), every log joined to it (diagonals too, stopping at the next tree's trunk), and every log of it he's
     * found before that still stands (a top left floating once the trunk under it was cut is still this tree's). No box: a
     * bough 3 or 4 out is the tree's, the next tree's trunk 2 off is not.
     */
    private java.util.List<BlockPos> treeLogs(ServerLevel level, BlockPos b) {
        java.util.Set<BlockPos> known = treeMem().computeIfAbsent(b.immutable(), k -> new java.util.HashSet<>());
        java.util.List<BlockPos> cols = new java.util.ArrayList<>(List.of(b));
        for (int sx : new int[] {-1, 1}) for (int sz : new int[] {-1, 1}) { // a 2x2 trunk: the square of logs at the base with b in a corner
            BlockPos a = b.offset(sx, 0, 0), c = b.offset(0, 0, sz), d = b.offset(sx, 0, sz);
            if (cols.size() == 1 && isLog(level, a) && isLog(level, c) && isLog(level, d)) { cols.add(a); cols.add(c); cols.add(d); }
        }
        java.util.List<BlockPos> seeds = new java.util.ArrayList<>();
        for (BlockPos c : cols) for (int y = 0; y < 40; y++) { BlockPos q = c.above(y); if (isLog(level, q)) seeds.add(q.immutable()); else if (y > 0) break; }
        for (BlockPos q : known) if (isLog(level, q) && !seeds.contains(q)) seeds.add(q);
        java.util.List<BlockPos> left = new java.util.ArrayList<>(seeds);
        if (!seeds.isEmpty()) left.addAll(com.claudemod.director.ClaudeLife.wholeTree(level, b, seeds));
        if (g.getTarget() instanceof net.minecraft.server.level.ServerPlayer o2) left.removeIf(p -> com.claudemod.context.PlayerProfile.placedBy(o2).contains(p) || com.claudemod.director.ClaudeLife.protectedBlock(g, o2, p));
        if (g.house != null) left.removeIf(p -> g.house.isStructure(p)); // his house's log posts beside the tree are no part of it
        known.addAll(left);
        left.sort(java.util.Comparator.comparingInt(BlockPos::getY));
        return left;
    }

    /** Before calling a tree done, a look up and round it: logs he can see near it that are cut free of any trunk (nothing under them down to the ground) are this tree's to finish. */
    private void lookRound(ServerLevel level, BlockPos b) {
        java.util.Set<BlockPos> known = treeMem().computeIfAbsent(b.immutable(), k -> new java.util.HashSet<>());
        Vec3 eye = g.getEyePosition();
        java.util.Set<BlockPos> looked = new java.util.HashSet<>();
        for (BlockPos p0 : BlockPos.betweenClosed(b.offset(-5, 0, -5), b.offset(5, 30, 5))) {
            if (!isLog(level, p0) || known.contains(p0) || looked.contains(p0)) continue;
            BlockPos p = p0.immutable();
            if (Vec3.atCenterOf(p).distanceTo(eye) > 24 || !seenThroughLeaves(level, eye, p)) continue;
            java.util.List<BlockPos> clump = new java.util.ArrayList<>(); java.util.ArrayDeque<BlockPos> todo = new java.util.ArrayDeque<>(List.of(p)); boolean grounded = false;
            java.util.Set<BlockPos> seen = new java.util.HashSet<>(List.of(p));
            while (!todo.isEmpty() && clump.size() < 64) {
                BlockPos c = todo.poll(); clump.add(c);
                BlockPos u = c.below(); if (!isLog(level, u) && !level.getBlockState(u).getCollisionShape(level, u).isEmpty() && !level.getBlockState(u).is(net.minecraft.tags.BlockTags.LEAVES)) grounded = true; // a trunk standing on the ground: a tree, not a leftover
                for (BlockPos n : BlockPos.betweenClosed(c.offset(-1, -1, -1), c.offset(1, 1, 1))) if (isLog(level, n) && seen.add(n.immutable())) todo.add(n.immutable());
            }
            looked.addAll(clump);
            if (grounded || (g.getTarget() instanceof net.minecraft.server.level.ServerPlayer o && clump.stream().anyMatch(q -> com.claudemod.context.PlayerProfile.placedBy(o).contains(q)))) continue;
            known.addAll(clump);
            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] tree at {}: {} log(s) left floating at {} (seen): his to finish", b.toShortString(), clump.size(), p.toShortString());
        }
    }

    /** His eyes reach the log: nothing solid in between but leaves (a player sees a log through the canopy's gaps). */
    private boolean seenThroughLeaves(ServerLevel level, Vec3 eye, BlockPos p) {
        Vec3 d = Vec3.atCenterOf(p).subtract(eye); double len = d.length(); if (len < 1e-3) return true; Vec3 step = d.scale(0.2 / len);
        Vec3 at = eye; BlockPos last = null;
        for (double s = 0; s < len + 0.2; s += 0.2, at = at.add(step)) {
            BlockPos q = BlockPos.containing(at);
            if (q.equals(last)) continue; last = q;
            if (q.equals(p)) return true;
            var st = level.getBlockState(q);
            if (st.isAir() || st.is(net.minecraft.tags.BlockTags.LEAVES) || !st.canOcclude()) continue;
            return false;
        }
        return true;
    }

    private boolean doChopTree(Task.ChopTree t) {
        ServerLevel level = (ServerLevel) g.level();
        BlockPos b = t.base();
        if (!b.equals(chopBase)) { chopBase = b.immutable(); chopLast = chopBase; chopWhy = ""; chopSkip.clear(); chopTries.clear(); chopDown = false; chopGoal = null; chopUnder = null; chopLogsLast = -1; chopScaffLast = -1; chopTop = Integer.MIN_VALUE; chopIdleAt = g.tickCount; chopStandTicks = 0;
            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] chopping the tree at {} (round {}): its drops wait for the sweep once it's down and his tower's back, no sweep off the tower mid-chop (WorkGoal.doChopTree)", b.toShortString(), t.round()); }
        java.util.List<BlockPos> logs = treeLogs(level, b);
        logs.removeIf(chopSkip::contains); if (g.house != null) logs.removeIf(q -> g.house.isStructure(q)); // his house's log posts touching the tree are no part of it (he went at them over and over, refused each time, and the house never got its wood)
        if (chopTop == Integer.MIN_VALUE) chopTop = logs.stream().mapToInt(BlockPos::getY).max().orElse(b.getY());
        int budget = chopTop - b.getY() + 3; // as many blocks as the tree is tall + 2 (a cap of 12 left the tops of tall trees standing)
        g.pillarBlocks.removeIf(p -> p.distSqr(b) <= 10 * 10 && level.getBlockState(p).getCollisionShape(level, p).isEmpty()); // (taken down, or never placed)
        java.util.List<BlockPos> scaffold = new java.util.ArrayList<>();
        for (BlockPos p : g.pillarBlocks) if (p.distSqr(b) <= 10 * 10 && !scaffold.contains(p)) scaffold.add(p);
        scaffold.sort((p1, p2) -> Integer.compare(p2.getY(), p1.getY())); // the top first
        if (chopGoal != null) { // back from a walk in under a bough
            if (chopUnder != null && chopUnder.distSqr(g.blockPosition()) > 2.5 && !inReach(chopGoal)) { chopSkip.add(chopGoal); logs.remove(chopGoal); chopWhy = "couldn't get in under the bough at " + chopGoal.toShortString(); com.claudemod.ClaudeMod.LOGGER.info("[Claude life] couldn't get in under the bough at {}: leaving it for now", chopGoal.toShortString()); }
            chopGoal = null; chopUnder = null;
        }
        if (logs.size() != chopLogsLast || scaffold.size() != chopScaffLast) { chopLogsLast = logs.size(); chopScaffLast = scaffold.size(); chopIdleAt = g.tickCount; }
        else if (g.tickCount - chopIdleAt > 20 * 40 && !logs.isEmpty()) {
            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] no headway on the tree at {} for 40 s ({} logs left): leaving the rest", b.toShortString(), logs.size());
            chopSkip.addAll(logs); chopWhy = "no headway on it for 40 s (" + logs.size() + " logs up, the lowest at " + logs.get(0).toShortString() + ")"; logs.clear(); g.attempts.any.put(b.immutable(), g.tickCount);
        }
        if (logs.isEmpty() || chopDown) {
            if (!scaffold.isEmpty()) { // his tower comes down from the top, under his feet, one block at a time (bottom-first left him on a floating top)
                g.tasks.addFirst(t); g.tasks.addFirst(new Task.Break(scaffold.get(0))); return true;
            }
            chopDown = false;
            if (logs.isEmpty()) { g.tasks.addFirst(new Task.FinishTree(b, t.round() + 1)); g.tasks.addFirst(new Task.Pickup(200)); chopBase = null; return true; } // the drops, a sapling where it stood (and anything missed)
        }
        for (BlockPos q : logs) { // the lowest log he can reach from where he stands
            if (!inReach(q)) continue;
            if (chopTries.merge(q, 1, Integer::sum) > 3) { chopSkip.add(q); chopWhy = "the log at " + q.toShortString() + " won't come down from where he can stand"; com.claudemod.ClaudeMod.LOGGER.info("[Claude life] the log at {} won't come down from here: leaving it for now", q.toShortString()); continue; } // (something unbreakable in the way: not a loop)
            g.tasks.addFirst(t); g.tasks.addFirst(new Task.Break(q)); return true;
        }
        BlockPos q = logs.get(0);
        double horiz = Math.hypot(g.getX() - (q.getX() + 0.5), g.getZ() - (q.getZ() + 0.5));
        if (q.getY() > g.getBlockY() && horiz < 2.5) { // over him: a block under his feet, in the trunk's own column once the bottom is cut
            if (scaffold.size() >= budget) { com.claudemod.ClaudeMod.LOGGER.info("[Claude life] the tree at {} wants more than {} scaffold blocks: leaving its top for now", b.toShortString(), budget); chopSkip.addAll(logs); chopWhy = "its top wants more than " + budget + " scaffold blocks"; chopDown = true; g.tasks.addFirst(t); return true; }
            BlockPos col = new BlockPos(q.getX(), g.getBlockY(), q.getZ());
            if (scaffold.isEmpty() && !g.blockPosition().equals(col) && level.getBlockState(col).getCollisionShape(level, col).isEmpty() && level.getBlockState(col.above()).getCollisionShape(level, col.above()).isEmpty()
                    && level.getBlockState(col.below()).isFaceSturdy(level, col.below(), Direction.UP) && chopStandTicks++ < 60) {
                g.getNavigation().stop(); g.getMoveControl().setWantedPosition(col.getX() + 0.5, col.getY(), col.getZ() + 0.5, 0.5); return false; // (a nudge: a walk to the cell beside him never lands on it)
            }
            chopStandTicks = 0;
            g.tasks.addFirst(t); g.tasks.addFirst(new Task.Pillar(1)); return true;
        }
        if (!scaffold.isEmpty()) { chopDown = true; g.tasks.addFirst(t); return true; } // a bough out of reach from up here: down first, then in under it
        BlockPos under = groundUnder(level, q);
        Integer uf = under == null ? null : g.attempts.any.get(under);
        if (under == null || uf != null && g.tickCount - uf < 20 * 60) { chopSkip.add(q); chopWhy = "nowhere to stand under the bough at " + q.toShortString(); com.claudemod.ClaudeMod.LOGGER.info("[Claude life] nowhere to stand under the bough at {}: leaving it for now", q.toShortString()); g.tasks.addFirst(t); return true; }
        chopGoal = q; chopUnder = under;
        g.tasks.addFirst(t); g.tasks.addFirst(new Task.GoTo(under, 0.5)); return true;
    }

    static boolean TRACE = false;
    private int pickupEffort, pickupTowers;
    private Task.Pickup holdPickup; private BlockPos holdAt; // the Pickup a patch waits on (a drop still in the hole), and the hole
    /** The patch's Pickup ran its time out and the drop is still in the hole: no way to it from here; it's left (logged), and the patch goes in. */
    private void leaveHeld() {
        BlockPos at = holdAt; holdPickup = null; holdAt = null;
        if (at == null) return;
        for (var i : g.level().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(at).inflate(1.5), i -> i.isAlive() && !g.isGift(i))) {
            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] couldn't get the {} out of the hole at {} in 20 s: patching over it", i.getItem().getHoverName().getString(), at.toShortString());
            g.markGift(i);
        }
    }
    /** Open dry air joined to his mine (6-neighbour, 8 cells at most, no cell beside lava) from this cell: an ore hole dug off his walkway; a cell in solid rock, by lava, or a pocket past 8 isn't. */
    static boolean airJoinedToMine(ServerLevel level, com.claudemod.entity.life.MineBlueprint bp, BlockPos from) {
        java.util.function.Predicate<BlockPos> open = q -> { // open dry air, nothing of lava beside it (lava has no collision box: the flood ran through the pool and his Pickup went after drops by the lava he was working)
            if (!level.getBlockState(q).getCollisionShape(level, q).isEmpty() || !level.getFluidState(q).isEmpty()) return false;
            for (Direction d : Direction.values()) if (level.getFluidState(q.relative(d)).is(net.minecraft.tags.FluidTags.LAVA)) return false;
            return true; };
        if (!open.test(from)) return false;
        java.util.ArrayDeque<BlockPos> todo = new java.util.ArrayDeque<>(); java.util.Set<BlockPos> seen = new java.util.HashSet<>();
        todo.add(from.immutable()); seen.add(from.immutable());
        while (!todo.isEmpty() && seen.size() <= 8) {
            BlockPos p = todo.poll();
            if (bp.touches(p)) return true;
            for (Direction d : Direction.values()) { BlockPos n = p.relative(d); if (!seen.contains(n) && open.test(n)) { seen.add(n.immutable()); todo.add(n.immutable()); } }
        }
        return false;
    }
    private java.util.Map<BlockPos, Integer> sentFor; // a placement -> the tick he was sent on the walk it needs (in front of a chest spot, back into his mine, to a view cell)
    private java.util.Map<BlockPos, Integer> sentFor() { if (sentFor == null) sentFor = new java.util.HashMap<>(); if (sentFor.size() > 256) sentFor.clear(); return sentFor; }
    private java.util.Map<BlockPos, long[]> viewTry; // a placement -> {view cell, his feet} at its last step to a cell that sees a face
    private java.util.Map<BlockPos, long[]> viewTry() { if (viewTry == null) viewTry = new java.util.HashMap<>(); if (viewTry.size() > 256) viewTry.clear(); return viewTry; }
    private Task stepOutFor; private int stepOutAt; // the placement he's nudging into his walkway for, and from which of its ticks
    private boolean stepOutPatch; // the cell he's stepping out of is a wall patch (cobble): out onto his walkway first
    private java.util.Map<BlockPos, Integer> stepOutSince; // a cell he stands in that's to be filled -> when he first stepped out of it
    private java.util.Map<BlockPos, Integer> stepOutSince() { if (stepOutSince == null) stepOutSince = new java.util.HashMap<>(); if (stepOutSince.size() > 256) stepOutSince.clear(); return stepOutSince; }
    /** The walk he was sent on for this placement failed (a WALK failure at dest since he was sent; not sent yet: one a moment ago, which his walker skips anyway). */
    private boolean walkFailedSince(BlockPos dest, BlockPos forPos) {
        Integer f = g.attempts.of(com.claudemod.entity.life.Attempts.Kind.WALK).get(dest);
        if (f == null) return false;
        Integer sent = sentFor().get(forPos);
        return sent != null ? f >= sent : g.tickCount - f < 20 * 60;
    }
    /** Room for a tower of `up` blocks right here: the cells over his head open, the blocks on him. */
    private boolean towerRoom(int up) {
        if (up <= 0) return true;
        if (scaffoldBlock().getCount() < up) return false;
        for (int k = 2; k <= up + 1; k++) { BlockPos q = g.blockPosition().above(k); if (!g.level().getBlockState(q).getCollisionShape(g.level(), q).isEmpty()) return false; }
        return true;
    }
    private Task lastPickupTask;

    private static java.util.Map<BlockPos, Integer> footings; // patches that already got a footing block queued (static: fields added live start null)
    private static java.util.Map<BlockPos, Integer> footings() { if (footings == null) footings = new java.util.HashMap<>(); return footings; }

    /** A spot on nb's face toward the cell (d points from the cell to nb) that his eyes reach first, within reach, or null: the middle, then toward the edges and corners, the way a player aims at whatever part of the face shows. */
    private Vec3 visibleFacePoint(ServerLevel level, Vec3 eye, BlockPos nb, Direction d, double reach) {
        Vec3 c = Vec3.atCenterOf(nb).add(d.getOpposite().getStepX() * 0.5, d.getOpposite().getStepY() * 0.5, d.getOpposite().getStepZ() * 0.5);
        Direction.Axis ax = d.getAxis();
        Vec3 u = ax == Direction.Axis.X ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0), v = ax == Direction.Axis.Z ? new Vec3(0, 1, 0) : new Vec3(0, 0, 1);
        double[][] offs = {{0, 0}, {0.4, 0}, {-0.4, 0}, {0, 0.4}, {0, -0.4}, {0.4, 0.4}, {0.4, -0.4}, {-0.4, 0.4}, {-0.4, -0.4}};
        for (double[] o : offs) {
            Vec3 pt = c.add(u.scale(o[0])).add(v.scale(o[1]));
            if (eye.distanceTo(pt) > reach) continue;
            var r = level.clip(new net.minecraft.world.level.ClipContext(eye, pt.add(d.getStepX() * 0.45, d.getStepY() * 0.45, d.getStepZ() * 0.45), net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, g)); // (into the block: a farmland's or slab's top sits below the cell's face)
            if (r.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK && r.getBlockPos().equals(nb)) return pt;
        }
        return null;
    }

    private BlockPos coverNudgeFor, coverNudgeDone; private int coverNudgeTick; private double coverNudgeBest; // the nudge into his cover's middle: for which block, since when it last gained, how near
    /**
     * A block of his cover in his way or out of his sight where he stands: a nudge into the middle of the cover's column, the Place
     * staying on meanwhile (never out of the cover, as the step-out and the view walk do). True while he's gaining on the middle;
     * false when he's there, or half a second went by without gaining (then the usual rules have it).
     */
    private java.util.Set<BlockPos> coverDeferred; // (lazy: fields added live start null)
    /**
     * His body still over a cell of his cover after the nudge: that block goes last, just before the cover's wait (he'll be off it
     * by then), once; still in his way then, it's left out. Never the step-out onto the cell beside: in a box that's out of it.
     */
    private boolean deferCoverBlock(Task.Place t) {
        if (coverDeferred == null || coverDeferred.size() > 64) coverDeferred = new java.util.HashSet<>();
        java.util.List<Task> all = new java.util.ArrayList<>(g.tasks); int at = -1;
        if (digInTasks != null) for (int i = 0; i < all.size() && at < 0; i++) for (Task y : digInTasks) if (y == all.get(i) && y instanceof Task.Wait) { at = i; break; }
        if (at > 0 && coverDeferred.add(t.pos().immutable())) {
            all.add(at, t); g.tasks.clear(); g.tasks.addAll(all); coverNudgeDone = null;
            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] his cover's block at {}: he's still over its cell ({}), it goes in last, before the wait (WorkGoal.deferCoverBlock)", t.pos().toShortString(), g.blockPosition().toShortString());
            return exit("his cover's block put last: he's over its cell (deferCoverBlock)");
        }
        g.tasks.remove(new Task.Break(t.pos().immutable())); g.pillarBlocks.remove(t.pos());
        com.claudemod.ClaudeMod.LOGGER.info("[Claude life] his cover's block at {}: he's still over its cell ({}) with nothing else of it left to place: left out, not stepping out of his cover for it (WorkGoal.deferCoverBlock)", t.pos().toShortString(), g.blockPosition().toShortString());
        return exit("his cover's block left out: he's over its cell (deferCoverBlock)");
    }
    private boolean nudgeIntoCover(Task.Place t, String why) {
        if (digInAt == null || t.pos().equals(coverNudgeDone)) return false;
        if (digInTasks != null && !digInTasks.isEmpty() && digInTasks.get(0) instanceof Task.Pillar && g.getBlockY() >= digInAt.getY() + 2 && (g.getBlockX() != digInAt.getX() || g.getBlockZ() != digInAt.getZ())) return false; // up a tower that isn't the cover's column: a nudge "into the middle" is a step off it
        double dx = g.getX() - (digInAt.getX() + 0.5), dz = g.getZ() - (digInAt.getZ() + 0.5), d = Math.sqrt(dx * dx + dz * dz);
        if (d < 0.15) { coverNudgeFor = null; return false; }
        if (!t.pos().equals(coverNudgeFor)) { coverNudgeFor = t.pos().immutable(); coverNudgeTick = g.tickCount; coverNudgeBest = d; com.claudemod.ClaudeMod.LOGGER.info("[Claude life] his cover's block at {} {}: a nudge into the middle of his cover first ({} off it) (WorkGoal.nudgeIntoCover)", t.pos().toShortString(), why, String.format("%.2f", d)); }
        else if (d < coverNudgeBest - 0.05 || !g.onGround()) { coverNudgeBest = Math.min(coverNudgeBest, d); coverNudgeTick = g.tickCount; } // (knocked up by a hit: no judging the nudge in the air)
        else if (g.tickCount - coverNudgeTick > 10) { coverNudgeDone = t.pos().immutable(); coverNudgeFor = null; com.claudemod.ClaudeMod.LOGGER.info("[Claude life] his cover's block at {} {}: not gaining on the middle ({} off, a hit or the wall in the way): placed from where he is (WorkGoal.nudgeIntoCover)", t.pos().toShortString(), why, String.format("%.2f", d)); return false; }
        g.getNavigation().stop(); g.getMoveControl().setWantedPosition(digInAt.getX() + 0.5, g.getY(), digInAt.getZ() + 0.5, 0.5);
        return true;
    }

    private Task walkTask; private BlockPos walkFor; private final java.util.Map<BlockPos, double[]> walkKept = new java.util.HashMap<>(); private net.minecraft.world.level.pathfinder.Path walkPath; private int walkNode = -1; // walkTo's clocks per spot within one job
    /** Nothing solid between him and the cell beside, at his feet or head, on the straight line there: a nudge gets him onto it. */
    private boolean openStep(ServerLevel level, BlockPos to) {
        Vec3 a = g.position(), b = Vec3.atBottomCenterOf(to);
        for (double s = 0; s <= 1.0001; s += 0.1) { Vec3 q = a.lerp(b, s); BlockPos c = BlockPos.containing(q.x, q.y + 0.2, q.z); if (!level.getBlockState(c).getCollisionShape(level, c).isEmpty() || !level.getBlockState(c.above()).getCollisionShape(level, c.above()).isEmpty()) return false; }
        return true;
    }

    private boolean doPlace(Task.Place t) {
        ServerLevel level = (ServerLevel) g.level();
        BlockPos pos = t.pos();
        if (upHisSkytower() && coverCell(pos) && !inReach(pos) && level.getBlockState(pos).canBeReplaced() && !enemiesNear(16).isEmpty()) { // up his tower with mobs about: never off it for a wall block (live 16:57: the walls' Places walked him off his tower into the wither skeletons, knocked into the lava)
            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] his cover's block at {} is out of reach from up his tower at {} with {} hostile(s) within 16: left out, he stays up (WorkGoal.doPlace)", pos.toShortString(), g.blockPosition().toShortString(), enemiesNear(16).size());
            return exit("a cover block out of reach from up his tower (doPlace)");
        }
        { String kept0 = com.claudemod.entity.life.Consent.keptOpen(g, pos); if (kept0 != null && level.getBlockState(pos).canBeReplaced()) { if (ticks == 1) com.claudemod.ClaudeMod.LOGGER.info("[Claude consent] not filling {} with {}: {} (WorkGoal.doPlace, queued by {})", pos.toShortString(), itemName(new ItemStack(t.item())), kept0, com.claudemod.entity.ClaudeEntity.originOf(t)); return exit("their opening stays (Consent.keptOpen)"); } } // (his upkeep's patch over what their word opened waits)
        { var te0 = com.claudemod.entity.life.TempLedger.entry(g, pos); BlockState here0 = level.getBlockState(pos);
          if (te0 != null && !here0.canBeReplaced() && t.item() instanceof BlockItem wb2 && !here0.is(wb2.getBlock()) && coverCell(pos)) { // a block of the cover he's making already fills this cell of it (the hole's lid is three Places, dirt, cobble, whatever he towers with: the first he has goes in, the rest find it done): it stays, the cover's own steps take it down
              if (ticks == 1) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] his cover's cell at {} has its block already ({} {}): that does it, no {} swapped in (WorkGoal.doPlace)", pos.toShortString(), te0.item, te0.why, itemName(new ItemStack(t.item())));
              return exit("doPlace: the cover's cell filled already"); }
          if (te0 != null && !here0.canBeReplaced() && t.item() instanceof BlockItem wb1 && !here0.is(wb1.getBlock()) && !g.attempts.within(com.claudemod.entity.life.Attempts.Kind.BREAK, pos, g.tickCount, 20 * 60)) { // a block of his put down for now stands in the cell this placement is for (a step on the stump a replant wants): back up first, then this
              g.tasks.addFirst(t); g.tasks.addFirst(new Task.Pickup(40)); g.tasks.addFirst(new Task.Break(pos.immutable()));
              com.claudemod.ClaudeMod.LOGGER.info("[Claude temp] his {} ({}) at {} stands where the {} goes: back up first, then the {} (WorkGoal.doPlace)", te0.item, te0.why, pos.toShortString(), itemName(new ItemStack(t.item())), itemName(new ItemStack(t.item())));
              return exit("his temporary block in the cell first (doPlace temp)"); } }
        if (level.getBlockState(pos).canBeReplaced() && coverCell(pos) && digInAt != null && digInTasks != null && !digInTasks.isEmpty() && digInTasks.get(0) instanceof Task.Place && (g.getBlockX() != digInAt.getX() || g.getBlockZ() != digInAt.getZ()) && nudgeIntoCover(t, "with him off its middle")) return false; // his box (or hut) goes up round him from its middle: knocked off it, back in first (built round an empty middle, the wait then read "out of his cover" and boxed him in again beside it)
        if (!level.getBlockState(pos).canBeReplaced() && !(t.want() != null && com.claudemod.entity.life.PlaceAim.growsInto(level.getBlockState(pos), t.want()))) { placeRetries.remove(pos.below(20000)); sentFor().remove(pos); if (t.facing() != null) sentFor().remove(pos.relative(t.facing())); viewTry().remove(pos); if (stepOutSince != null) stepOutSince.remove(pos); return exit("the cell's filled already, " + name(level.getBlockState(pos)) + " there (doPlace line 2561)"); }
        { BlockPos open0 = com.claudemod.director.ClaudeLife.nookFillOpenBefore(g, level, pos); if (open0 != null) { if (ticks == 1) com.claudemod.ClaudeMod.LOGGER.info("[Claude nook] not setting {} yet: the fill at {} before it is still open (its Place didn't go in), and this one could wall it off: the next round plans again (WorkGoal.doPlace)", pos.toShortString(), open0.toShortString()); return exit("doPlace: the nook fill's order"); } }
        if (!g.pillarBlocks.isEmpty() && g.pillarBlocks.contains(pos.below()) && t.item() instanceof net.minecraft.world.item.BlockItem sb && (sb.getBlock() instanceof net.minecraft.world.level.block.BedBlock || sb.getBlock() instanceof net.minecraft.world.level.block.ChestBlock
                || sb.getBlock() instanceof net.minecraft.world.level.block.CraftingTableBlock || sb.getBlock() instanceof net.minecraft.world.level.block.AbstractFurnaceBlock)) { // a bed, chest, table or furnace on a block of his scaffold: never (the scaffold comes down)
            if (ticks == 1) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] not setting {} on his scaffold at {}", net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(t.item()).getPath(), pos.toShortString());
            return exit("doPlace line 2565");
        }
        { Integer failedAt = g.attempts.of(com.claudemod.entity.life.Attempts.Kind.PLACE).get(pos); if (failedAt != null && g.tickCount - failedAt < 20 * 300) { if (ticks == 1) { com.claudemod.ClaudeMod.LOGGER.info("[Claude life] not placing at {}: it failed there {}s ago", pos.toShortString(), (g.tickCount - failedAt) / 20); com.claudemod.entity.life.MindLog.refused(g, pos, "placing (failed there lately) queued by " + com.claudemod.entity.ClaudeEntity.originOf(t)); } return true; } } // (logged: a silent skip left an ore hole open in a test and nothing said why) // failed there a moment ago (nothing to place against, out of reach): not again yet
        if (g.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(pos)) && !(current instanceof Task.Pillar) && falling()) { // dropping through the cell (down his hole, under its lid): he's out of it in a moment, no stepping out onto the rim
            fallNote("placing at " + pos.toShortString());
            return false;
        }
        if (g.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(pos)) && !(current instanceof Task.Pillar) && coverCell(pos)) { if (nudgeIntoCover(t, "in his way")) return false; return deferCoverBlock(t); } // his body over the edge of a cell of his cover: into its middle, never the step-out onto the cell beside (that put him in the box's corner, out of it, and the arrows had him fighting back)
        if (g.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(pos)) && !(current instanceof Task.Pillar)) {
            stepOutPatch = t.item() == net.minecraft.world.item.Items.COBBLESTONE || t.item() == net.minecraft.world.item.Items.COBBLED_DEEPSLATE;
            BlockPos out = stepOutSpot(pos); // standing in the hole he's to fill (a floor cell he walked into): out of it, then the block from beside, like a player
            int since = stepOutSince().computeIfAbsent(pos.immutable(), k -> g.tickCount);
            if (out != null && g.tickCount - since <= 40) { // while his box is still in the cell (the state, not a count of 2): out of it again, a nudge onto the cell beside or a walk to the one up; 2 s of that for a one-block step
                g.tasks.addFirst(t);
                if (out.getY() == pos.getY() && out.distManhattan(pos) == 1) { g.tasks.addFirst(new Task.Wait(8, false)); g.getNavigation().stop(); g.getMoveControl().setWantedPosition(out.getX() + 0.5, out.getY(), out.getZ() + 0.5, 0.6); } // (a walk to the cell beside ends at once: a nudge instead)
                else g.tasks.addFirst(new Task.GoTo(out, 0.6));
                return exit("doPlace line 2580");
            }
            stepOutSince().remove(pos);
            if (out != null) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] standing in the hole at {}: 2 s of stepping out to {} didn't get him out, not placing it under himself (this round)", pos.toShortString(), out.toShortString());
            for (var it = g.tasks.iterator(); it.hasNext(); ) if (it.next() instanceof Task.Place p2 && p2.pos().equals(pos)) it.remove(); return exit("where he's standing: never a block there (feet and head footings fed each other, 76 drops in 3 minutes) (doPlace 2584)"); } // where he's standing: never a block there (feet and head "footings" fed each other, 76 drops in 3 minutes)
        if (ticks == 1 && !(stepOutSince != null && stepOutSince.containsKey(pos)) && placeRetries.merge(pos.below(20000).immutable(), 1, Integer::sum) > 20) { // (not while he's stepping out of the cell: that has its own 2 s) // ponytail: tries kept far below the world in placeRetries (no new fields live); a placement that keeps coming back and never takes is dropped, not a 4-minute loop (his tower roof re-queued itself every tick)
            placeRetries.remove(pos.below(20000));
            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] placing at {} has failed 20 times: dropping it", pos.toShortString());
            g.attempts.fail(com.claudemod.entity.life.Attempts.Kind.PLACE, pos, g.tickCount, g.blockPosition());
            return exit("doPlace line 2589");
        }
        if (com.claudemod.director.ClaudeLife.bpMode(g) && g.blueprint != null && !g.blueprint.isEmpty() && g.blueprint.isShell(pos) && !g.blueprint.isWalk(g.blockPosition()) && !g.blueprint.isWalk(g.blockPosition().above())
                && pos.distSqr(g.blockPosition()) <= 6 * 6 && (t.item() == net.minecraft.world.item.Items.COBBLESTONE || t.item() == net.minecraft.world.item.Items.COBBLED_DEEPSLATE)) { // (a patch of his wall, never a station or the bed in his nook) his mine's wall patched from inside: out in an ore hole he steps back in first (he grabbed diamonds from behind the wall and the patch sealed him outside); while he's outside it, every time (a lifetime count of 2 sealed him out the second time)
            int ri = g.blueprint.nearestRow(pos);
            BlockPos in = null; if (ri >= 0) for (BlockPos f : g.blueprint.rows().get(ri).feet()) if (in == null || f.distSqr(g.blockPosition()) < in.distSqr(g.blockPosition())) in = f; // (the walkway cell of that row nearest him)
            if (in != null && !walkFailedSince(in, pos)) {
                if (in.getY() == g.getBlockY() && Math.hypot(g.getX() - (in.getX() + 0.5), g.getZ() - (in.getZ() + 0.5)) <= 1.6) { // a cell off: a nudge in (a walk to the cell beside spins on the spot)
                    if (stepOutFor != t) { stepOutFor = t; stepOutAt = ticks; }
                    if (ticks - stepOutAt <= 60) { g.getNavigation().stop(); g.getMoveControl().setWantedPosition(in.getX() + 0.5, in.getY(), in.getZ() + 0.5, 0.6); return false; }
                } else { sentFor().put(pos.immutable(), g.tickCount); g.tasks.addFirst(t); g.tasks.addFirst(new Task.GoTo(in, 1.2)); return exit("(to the cell beside it: the nudge above does the last step) (doPlace 2599)"); } // (to the cell beside it: the nudge above does the last step)
            }
            if (in != null) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] the walk back into his mine at {} failed: patching {} from where he is", in.toShortString(), pos.toShortString());
        }
        if (ticks == 1 && com.claudemod.director.ClaudeLife.bpMode(g) && g.blueprint != null && !g.blueprint.isEmpty() && g.blueprint.roleAt(pos) != com.claudemod.entity.life.MineBlueprint.Role.WALK) { // (any ore-hole patch, not only his shell: the end of his branch at 16,-57,-205 has no role, and 3 diamonds went in behind it, 04:10) // the user: mine all ore, then patch: ore now showing through this hole (the vein went on behind the block he took) comes out first
            int refusedBehind = 0; java.util.List<BlockPos> behind0 = veinBehind(g, level, pos); // (live 12:14: the vein went on past the hole's own faces, the patch sealed 3 diamond ore and a drop in)
            for (BlockPos q : behind0) { // faces into the hole and its pocket only: a block touching the hole by a corner shows no face into it (03:31 a corner iron "through the hole" had him breaking the torch's wall over and over to aim at it)
                BlockState qs = level.getBlockState(q);
                if ( !com.claudemod.entity.life.MineBlueprint.ore(qs) || g.blueprint.roleAt(q) == com.claudemod.entity.life.MineBlueprint.Role.WALK) continue; // (ore in his walls too: patched past, it was sealed in; only his walkway's own ore waits for the dig)
                if (qs.requiresCorrectToolForDrops() && g.inventory.find(st -> st.isCorrectToolForDrops(qs)).isEmpty()) continue; // (no pick for it: the patch goes in)
                final BlockPos qq = q.immutable();
                if (com.claudemod.entity.life.Watchdog.off(g, new Task.Break(qq))) { com.claudemod.ClaudeMod.LOGGER.info("[Claude ore] {} at {} behind the hole at {}: its dig is written off (no way to break it from any spot he can get to): left in, the patch goes in (WorkGoal.doPlace)", qs.getBlock().getName().getString(), qq.toShortString(), pos.toShortString()); continue; } // (World 15 16:34: the ore re-found every few seconds, the hole never shut, 236 lines)
                if (g.attempts.within(com.claudemod.entity.life.Attempts.Kind.BREAK, qq, g.tickCount, 20 * 60) || g.attempts.within(com.claudemod.entity.life.Attempts.Kind.REACH, qq, g.tickCount, 20 * 60)) { com.claudemod.ClaudeMod.LOGGER.info("[Claude ore] {} at {} behind the hole at {}: its dig was refused a moment ago (WorkGoal.doPlace)", qs.getBlock().getName().getString(), qq.toShortString(), pos.toShortString()); refusedBehind++; continue; } // (05:02 the patch waited on a lapis he couldn't reach from there, 3 times a second)
                g.tasks.removeIf(t0 -> t0 instanceof Task.Break b0 && b0.pos().equals(qq)); // (queued further back: it comes out now, before this patch seals it)
                g.tasks.addFirst(t); g.tasks.addFirst(new Task.Pickup(40)); g.tasks.addFirst(new Task.Break(qq));
                com.claudemod.ClaudeMod.LOGGER.info("[Claude ore] {} at {} shows through the hole at {}: mined first, then the patch", qs.getBlock().getName().getString(), qq.toShortString(), pos.toShortString());
                return exit("doPlace line 2612");
            }
            if (refusedBehind > 0) { com.claudemod.ClaudeMod.LOGGER.info("[Claude ore] not patching {}: {} ore still behind it and his dig at it refused from here: the hole stays open till the vein's out (WorkGoal.doPlace)", pos.toShortString(), refusedBehind); return exit("ore behind the hole: left open, never sealed in"); } // (the user: the whole visible vein before any patch)
        }
        // something he dropped is still lying in there (raw iron in an ore pocket, a diamond a cell deeper in the vein): the patch waits while a drop his Pickup goes for lies there;
        // that Pickup's own end (picked up, or its time out with the drop still there: left, logged) ends the wait, never a count of tries
        com.claudemod.entity.life.MineBlueprint bpIn = com.claudemod.director.ClaudeLife.bpMode(g) && g.blueprint != null && !g.blueprint.isEmpty() && g.blueprint.touches(g.blockPosition()) ? g.blueprint : null; // (as his Pickup sees it from where he stands)
        final java.util.Set<BlockPos> pocket0 = pocketBehind(g, level, pos); // (a drop a cell or two deeper in the pocket is sealed in all the same: 12:14 a diamond behind the patch)
        var inside = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(pos).inflate(5),
                i -> i.isAlive() && (i.position().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos)) <= 2.6 * 2.6 || pocket0.contains(i.blockPosition())) && !g.isGift(i) && g.inventory.hasRoomFor(i.getItem()) && (bpIn == null || bpIn.touches(i.blockPosition()) || airJoinedToMine(level, bpIn, i.blockPosition())));
        boolean wetBy = !level.getFluidState(pos).isEmpty(); for (Direction d : Direction.values()) if (!level.getFluidState(pos.relative(d)).isEmpty()) wetBy = true; // a plug against lava or water: it goes in first (the flow won't wait; the drop is after, or the lava's)
        { String hk = "hold:" + pos.asLong(); Integer held = g.attempts.counters.get(hk); // held for this drop a moment ago and it's still there: the pickup couldn't get it; it's left (logged) and the block goes in (04:33 a furnace held 6 times in 30 s for a drop the pickup never went for)
          if (!inside.isEmpty() && !wetBy && held != null && g.tickCount - held < 20 * 30 && com.claudemod.director.ClaudeLife.dropsWorth(inside) >= 15) { // a valuable pile is never sealed in (live 11:36: 3 diamonds in his branch's floor holes, the patch went over them after a 2.5 s hold): he steps down into the hole for it; can't: the hole stays open
            String wk = "walkin:" + pos.asLong(); Integer w = g.attempts.counters.get(wk);
            if (w == null || g.tickCount - w > 20 * 60) { g.attempts.counters.put(wk, g.tickCount); g.attempts.counters.remove(hk); g.tasks.addFirst(t); g.tasks.addFirst(new Task.Pickup(100)); g.tasks.addFirst(new Task.GoTo(pos.immutable(), 0.5)); com.claudemod.ClaudeMod.LOGGER.info("[Claude life] {} in the hole at {} and his pickup didn't get it: into the hole for it, then the patch (WorkGoal.doPlace)", inside.get(0).getItem().getHoverName().getString(), pos.toShortString()); return exit("valuable drop: stepping into the hole for it"); }
            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] {} in the hole at {}: couldn't get in for it a moment ago; the hole stays open, never patched over it (WorkGoal.doPlace)", inside.get(0).getItem().getHoverName().getString(), pos.toShortString());
            return exit("valuable drop in the hole: left open");
          }
          if (!inside.isEmpty() && !wetBy && held != null && g.tickCount - held < 20 * 30) { for (var i0 : inside) { com.claudemod.ClaudeMod.LOGGER.info("[Claude life] the {} in {} wasn't picked up when he held off for it: leaving it, placing", i0.getItem().getHoverName().getString(), pos.toShortString()); g.markGift(i0); } inside = java.util.List.of(); }
          else if (!inside.isEmpty() && !wetBy) g.attempts.counters.put(hk, g.tickCount); }
        if (!inside.isEmpty() && !wetBy && coverCell(pos)) { com.claudemod.ClaudeMod.LOGGER.info("[Claude life] {} by his cover's block at {}: the cover goes in first, the drop after it comes down (its own pickup) (WorkGoal.doPlace)", inside.get(0).getItem().getHoverName().getString(), pos.toShortString()); inside = java.util.List.of(); } // (a Pickup for it walked him out of his half-built box into the arrows)
        if (!inside.isEmpty() && !wetBy) {
            holdPickup = new Task.Pickup(400); holdAt = pos.immutable();
            g.tasks.addFirst(t);
            g.tasks.addFirst(holdPickup);
            viewTry().remove(pos); // (the hold moves him: where he looks from for this cell starts again after it)
            return exit("grab it, then the patch (doPlace 2625)"); // grab it, then the patch
        }
        ItemStack stack = g.inventory.find(s -> s.is(t.item()));
        if (stack.isEmpty() && t.want() != null) { // a build's planned block: never another block in its place
            if (ticks == 1) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] no {} on him for the build's cell at {}: not putting anything else there (WorkGoal.doPlace)", itemName(new ItemStack(t.item())), pos.toShortString());
            return exit("out of the planned block (doPlace want)");
        }
        if (stack.isEmpty() && t.item() instanceof BlockItem bi0 && (bi0.getBlock() instanceof net.minecraft.world.level.block.CropBlock || bi0.getBlock() instanceof net.minecraft.world.level.block.StemBlock)) { // a replant with no seeds on him: nothing else goes in a farm
            if (ticks == 1) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] no {} to replant at {}: leaving the farmland bare", itemName(new ItemStack(t.item())), pos.toShortString());
            return exit("doPlace line 2630");
        }
        if (stack.isEmpty() && t.item() instanceof BlockItem wb0 && !wb0.getBlock().defaultBlockState().isCollisionShapeFullBlock(level, pos)) { // a sapling, a torch, a bed: no filler goes in its place (05:26 a replant with no sapling on him put cobbled_deepslate in the stump's cell, and it was litter)
            boolean sap = t.item().builtInRegistryHolder().is(net.minecraft.tags.ItemTags.SAPLINGS);
            if (sap) com.claudemod.director.ClaudeLife.replantDue().add(pos.immutable());
            if (ticks == 1) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] no {} on him for {}: nothing else goes in its place{} (WorkGoal.doPlace)", itemName(new ItemStack(t.item())), pos.toShortString(), sap ? "; a sapling owed there, planted when he has one" : "");
            return exit("out of that block, and no filler stands in for it (doPlace no-filler)");
        }
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) {
            // out of that block: any filler he does have does the job; a stone patch (his mine) only ever takes stone, never dirt
            boolean stoneWanted = t.item() == net.minecraft.world.item.Items.COBBLESTONE || t.item() == net.minecraft.world.item.Items.COBBLED_DEEPSLATE;
            if (stoneWanted && g.blueprint != null && !g.blueprint.isEmpty() && g.blueprint.isArch(pos)) { g.attempts.any.put(pos.immutable(), g.tickCount); g.pendingPatches.add(pos.immutable()); if (ticks == 1) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] the arch at {} takes {} only: waiting till he has some", pos.toShortString(), itemName(new ItemStack(t.item()))); return true; } // (the user: the arch is all cobblestone, deepslate later; never andesite or dirt)
            stack = stoneWanted ? g.inventory.find(st -> st.is(net.minecraft.world.item.Items.COBBLESTONE) || st.is(net.minecraft.world.item.Items.COBBLED_DEEPSLATE)
                    || st.is(net.minecraft.world.item.Items.STONE) || st.is(net.minecraft.world.item.Items.ANDESITE) || st.is(net.minecraft.world.item.Items.DIORITE)
                    || st.is(net.minecraft.world.item.Items.GRANITE) || st.is(net.minecraft.world.item.Items.TUFF) || st.is(net.minecraft.world.item.Items.DEEPSLATE))
                    : scaffoldBlock();
            if (stack.isEmpty()) {
                g.attempts.any.put(pos.immutable(), g.tickCount); // (the upkeep waits a minute before queueing it again: dirt on him read as filler and the stone patches came back every 3 s)
                g.pendingPatches.add(pos.immutable()); // not forgotten: filled in once he has blocks again and is back here
                com.claudemod.ClaudeMod.LOGGER.info("[Claude life] couldn't place at {}: none of {} or any filler left (will come back to it)", pos.toShortString(), itemName(new ItemStack(t.item())));
                return exit("doPlace line 2644");
            }
        }
        // A player can only place against an existing block, never in midair.
        Direction against = null;
        // the face he can see through the gap: the block behind it, straight ahead of him (a player aims at the back wall, not the floor)
        Direction away = null; double bestOpen = Double.MAX_VALUE; // opposite the open side nearest him (the walkway he's in)
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos o = pos.relative(d);
            if (!level.getBlockState(o).getCollisionShape(level, o).isEmpty()) continue;
            double dd = g.distanceToSqr(Vec3.atCenterOf(o));
            if (dd < bestOpen) { bestOpen = dd; away = d.getOpposite(); }
        }
        if (away == null) away = Direction.getNearest(pos.getX() + 0.5 - g.getX(), 0, pos.getZ() + 0.5 - g.getZ());
        BlockState back = level.getBlockState(pos.relative(away));
        if (!back.isAir() && back.getFluidState().isEmpty() && !back.canBeReplaced() && !com.claudemod.entity.ClaudeEntity.usableBlock(level, pos.relative(away))) against = away;
        if (stack.is(net.minecraft.tags.ItemTags.LOGS) && !level.getBlockState(pos.below()).getCollisionShape(level, pos.below()).isEmpty()) against = Direction.DOWN; // a log goes on the block under it, clicked on top: it stands upright (the user: his house's oak posts came out on their side, clicked on a neighbour's side)
        for (int pass = 0; pass < 2 && against == null; pass++) for (Direction d : Direction.values()) { // a plain block first; one that opens (a chest) only if nothing else
            BlockState n = level.getBlockState(pos.relative(d));
            if (com.claudemod.entity.ClaudeEntity.usableBlock(level, pos.relative(d)) != (pass == 1)) continue;
            if (!n.isAir() && n.getFluidState().isEmpty()) { against = d; break; }
        }
        if (against == null && t.want() != null) { // a build's block with nothing to go against yet: no column or footing of its block outside the plan; the build's check comes back to it
            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] nothing to set {} against at {} yet (a build's cell): left for the build's check (WorkGoal.doPlace)", itemName(new ItemStack(t.item())), pos.toShortString());
            g.attempts.fail(com.claudemod.entity.life.Attempts.Kind.PLACE, pos, g.tickCount, g.blockPosition());
            return exit("nothing to go against yet (doPlace want)");
        }
        if (against == null && upHisSkytower() && !enemiesNear(16).isEmpty()) { // (a column up from the floor is a walk down off the tower)
            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] nothing to set {} against at {} from up his tower at {} with mobs about: left out, no column up from the floor (WorkGoal.doPlace)", itemName(new ItemStack(t.item())), pos.toShortString(), g.blockPosition().toShortString());
            return exit("nothing to go against from up his tower (doPlace)");
        }
        if (against == null) {
            for (BlockPos b = pos.below(), floor = pos.below(4); b.getY() > floor.getY(); b = b.below()) { // a hole over a drop: a column up from the floor's edge, like a player bridging
                if (!level.getBlockState(b).canBeReplaced() || !level.getBlockState(b).getFluidState().isEmpty()) break;
                boolean anchored = false;
                for (Direction d : Direction.values()) { BlockState n = level.getBlockState(b.relative(d)); if (!n.isAir() && n.getFluidState().isEmpty() && !n.canBeReplaced()) { anchored = true; break; } }
                if (anchored) {
                    g.tasks.addFirst(t);
                    for (BlockPos q = b; q.getY() < pos.getY(); q = q.above()) g.tasks.addFirst(new Task.Place(q.immutable(), t.item())); // (lowest first)
                    return exit("doPlace line 2674");
                }
            }
            // a hole in open air (a big cave): try again once the patches around it are in and give it something to sit against
            boolean anchorComing = false; // a placement queued beside it that has something to sit against now: once that's in, this one has its face (the state, not 2 tries)
            for (Task q : g.tasks) {
                if (!(q instanceof Task.Place p2) || p2.pos().distManhattan(pos) != 1 || !level.getBlockState(p2.pos()).canBeReplaced()) continue;
                for (Direction d2 : Direction.values()) { BlockPos s2 = p2.pos().relative(d2); BlockState n2 = level.getBlockState(s2); if (!s2.equals(pos) && !n2.isAir() && n2.getFluidState().isEmpty() && !n2.canBeReplaced()) { anchorComing = true; break; } }
                if (anchorComing) break;
            }
            if (anchorComing) {
                java.util.List<Task> rest = new java.util.ArrayList<>(g.tasks);
                int at = 0; // after the other placements queued right behind it
                while (at < rest.size() && rest.get(at) instanceof Task.Place) at++;
                rest.add(at, t);
                com.claudemod.director.ClaudeLife.clearTasks(g); g.tasks.addAll(rest);
                return exit("doPlace line 2690");
            }
            for (Direction d : new Direction[] {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP}) { // a footing first: a neighbour he CAN place, then this one against it (the user's repair: floor, then wall on it)
                BlockPos n = pos.relative(d);
                if (!level.getBlockState(n).canBeReplaced() || !level.getFluidState(n).isEmpty() || !level.getBlockState(n).getCollisionShape(level, n).isEmpty()) continue;
                boolean hasSupport = false;
                for (Direction d2 : Direction.values()) { BlockPos s2 = n.relative(d2); if (!level.getBlockState(s2).getCollisionShape(level, s2).isEmpty()) { hasSupport = true; break; } }
                if (!hasSupport || footings().getOrDefault(pos.immutable(), 0) >= 1) continue;
                if (g.attempts.within(com.claudemod.entity.life.Attempts.Kind.PLACE, n, g.tickCount, 20 * 300)) { com.claudemod.ClaudeMod.LOGGER.info("[Claude life] no footing at {} for {}: placing there failed {} s ago (WorkGoal.doPlace footing)", n.toShortString(), pos.toShortString(), (g.tickCount - g.attempts.last(com.claudemod.entity.life.Attempts.Kind.PLACE, n)) / 20); continue; } // (live 15:20:34: a footing queued where placing had failed 10 s before, refused at once, 3 times in 11 s)
                footings().merge(pos.immutable(), 1, Integer::sum);
                g.tasks.addFirst(new Task.Place(pos, t.item()));
                g.tasks.addFirst(new Task.Place(n.immutable(), t.item()));
                com.claudemod.ClaudeMod.LOGGER.info("[Claude life] nothing to place {} against: a footing at {} first", pos.toShortString(), n.toShortString());
                return exit("doPlace line 2702");
            }
            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] couldn't place at {}: nothing to place it against", pos.toShortString());
            g.attempts.fail(com.claudemod.entity.life.Attempts.Kind.PLACE, pos, g.tickCount, g.blockPosition()); // (remembered: 56 tries at floating cells in a flooded cave in 3 minutes, 40 cobble gone)
            return exit("doPlace line 2706");
        }
        if (!inReach(pos) && com.claudemod.director.ClaudeLife.isCastBlock(pos) && com.claudemod.director.ClaudeLife.portalEdge() != null) { // a mould block from his pillar: to its near edge, never a walk off the pillar (he walked off to "reach" a side wall and the pour came from the ground)
            Vec3 e = com.claudemod.director.ClaudeLife.portalEdge();
            if (Math.abs(g.getY() - e.y) > 0.6 || ticks > 60) { if (ticks == 1 || ticks > 60) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] mould block {} out of reach from the pillar (he's at {}): the cast re-places it", pos.toShortString(), g.blockPosition().toShortString()); return true; }
            g.getNavigation().stop(); g.getMoveControl().setWantedPosition(e.x, e.y, e.z, 0.4);
            return false;
        }
        if (!inReach(pos)) { // walk to where he can reach it (a hole in a wall is not somewhere to stand)
            Integer gaveUp = g.attempts.of(com.claudemod.entity.life.Attempts.Kind.PLACE).get(pos);
            if (gaveUp != null && g.tickCount - gaveUp < 20 * 300) return exit("out of reach a moment ago: skipped outright (the same column of placements walked him up and down his stairs e (doPlace 2716)"); // out of reach a moment ago: skipped outright (the same column of placements walked him up and down his stairs every 20 s)
            walkNoDig = true; // never dig through walls just to put a block somewhere
            boolean done = walkTo(standSpotBeside(pos, false), 0.8) && !inReach(pos) || ticks > 20 * 12 && g.getDeltaMovement().horizontalDistanceSqr() < 1.0E-4 && g.getNavigation().isDone(); // (stood still 12 s+ short of a patch he can't walk to: on to the next; a plain 12 s cap cut off stair roofs he was still walking to)
            walkNoDig = false;
            if (done && com.claudemod.director.ClaudeLife.bpMode(g) && g.blueprint != null && !g.blueprint.isEmpty() && g.blueprint.isShell(pos) && pos.getY() >= g.getBlockY() + 2 && pos.getY() - g.getBlockY() <= 6
                    && Math.abs(pos.getX() - g.getBlockX()) + Math.abs(pos.getZ() - g.getBlockZ()) <= 3 && !g.pillarBlocks.contains(standingOn()) && towerRoom(pos.getY() - g.getBlockY() - 2)) { // (not up his own tower already, and room for one: never once a session) // a hole high in his mine's wall with no spot to stand: a pillar under it, the block, the pillar down (a player patches a cave wall from a tower, not "couldn't get in reach")
                int up = pos.getY() - g.getBlockY() - 2;
                g.tasks.addFirst(new Task.Pillar(-1)); g.tasks.addFirst(new Task.Place(pos, t.item())); if (up > 0) g.tasks.addFirst(new Task.Pillar(up));
                com.claudemod.ClaudeMod.LOGGER.info("[Claude life] patch at {} is {} up with nowhere to stand: a {}-block tower under it", pos.toShortString(), pos.getY() - g.getBlockY(), Math.max(0, up));
                return exit("doPlace line 2725");
            }
            if (done) { g.attempts.fail(com.claudemod.entity.life.Attempts.Kind.PLACE, pos, g.tickCount, g.blockPosition()); com.claudemod.ClaudeMod.LOGGER.info("[Claude life] couldn't get in reach to place at {} (he's at {})", pos.toShortString(), g.blockPosition().toShortString()); }
            return done;
        }
        if (g.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(pos))) return exit("don't build himself in (doPlace 2730)"); // don't build himself in

        g.getNavigation().stop();
        // look at the face he is placing against, like aiming the crosshair
        Vec3 face = Vec3.atCenterOf(pos).add(against.getStepX() * 0.5, against.getStepY() * 0.5, against.getStepZ() * 0.5);
        g.getLookControl().setLookAt(face.x, face.y, face.z);
        g.hold(stack);
        if (ticks < 5) return false; // a player's right-click pace
        { Boolean aimed = placeAimed(t, stack, level); if (aimed != null) return aimed; } // a planned state (a stair's facing, a door's hinge): the click that gives it
        Vec3 bedAim = null;
        if (stack.getItem() instanceof net.minecraft.world.item.BlockItem bi && bi.getBlock() instanceof net.minecraft.world.level.block.BedBlock) { // a bed's head goes the way he faces when he clicks: stand behind the foot, click the floor in front, the head runs on along a free cell
            if (t.facing() != null && !level.getBlockState(pos.relative(t.facing())).canBeReplaced() && level.getBlockState(pos.relative(t.facing())).getDestroySpeed(level, pos.relative(t.facing())) >= 0 && !g.attempts.of(com.claudemod.entity.life.Attempts.Kind.BREAK).containsKey(pos.relative(t.facing())) && sentFor().putIfAbsent(pos.relative(t.facing()).immutable(), g.tickCount) == null) { // (while it's rock, no dig of it was refused, and none sent for it came back with it still there) the plan says where the head goes and that cell is still rock: dug first, never the bed turned some other way (it went into the nook the wrong way, the user)
                g.tasks.addFirst(t); g.tasks.addFirst(new Task.Break(pos.relative(t.facing()).immutable())); return exit("doPlace line 2741");
            }
            Direction headWay = t.facing() != null && level.getBlockState(pos.relative(t.facing())).canBeReplaced() ? t.facing() : null; // (the plan's way for the head: the nook's bed lies in its niche)
            if (headWay == null) for (Direction d : Direction.Plane.HORIZONTAL) { BlockPos h2 = pos.relative(d), stand = pos.relative(d.getOpposite()); if (level.getBlockState(h2).canBeReplaced() && !level.getBlockState(h2.below()).getCollisionShape(level, h2.below()).isEmpty() && level.getBlockState(stand).getCollisionShape(level, stand).isEmpty()) { headWay = d; break; } }
            if (headWay != null) {
                BlockPos stand = pos.relative(headWay.getOpposite());
                java.util.function.Predicate<BlockPos> standable = q -> level.getBlockState(q).getCollisionShape(level, q).isEmpty() && level.getBlockState(q.above()).getCollisionShape(level, q.above()).isEmpty() && !level.getBlockState(q.below()).getCollisionShape(level, q.below()).isEmpty();
                if (!standable.test(stand)) for (Direction p0 : new Direction[] {headWay.getClockWise(), headWay.getCounterClockWise()}) { // rock behind the foot: the corner behind it, the foot in view past the open cell beside it
                    BlockPos corner = stand.relative(p0), beside = pos.relative(p0);
                    if (standable.test(corner) && level.getBlockState(beside).getCollisionShape(level, beside).isEmpty()) { stand = corner; bedAim = Vec3.atCenterOf(pos.below()).add(headWay.getStepX() * 0.45, 0.5, headWay.getStepZ() * 0.45); break; } // (the far half of the floor: his facing rounds to the head's way, not the side)
                }
                if (!g.blockPosition().equals(stand)) {
                    boolean straight = stand.distSqr(g.blockPosition()) <= 4 && stand.getY() == g.getBlockY() && openStep(level, stand); // (the cell beside with nothing between: a nudge; through his house's back wall it was 10 s of walking into the planks, then "couldn't get behind the bed spot")
                    if (straight) { if (ticks > 200) { com.claudemod.ClaudeMod.LOGGER.info("[Claude life] couldn't step onto {} behind the bed spot at {} to set it (he's at {}, nothing between)", stand.toShortString(), pos.toShortString(), g.blockPosition().toShortString()); g.attempts.fail(com.claudemod.entity.life.Attempts.Kind.PLACE, pos, g.tickCount); return true; } g.getNavigation().stop(); g.getMoveControl().setWantedPosition(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 0.5); return false; }
                    if (walkTo(stand, 0.4) && !g.blockPosition().equals(stand) && !(stand.distSqr(g.blockPosition()) <= 4 && stand.getY() == g.getBlockY() && openStep(level, stand))) { com.claudemod.ClaudeMod.LOGGER.info("[Claude life] the walk to {} behind the bed spot at {} ended short (he's at {}): the next round tries again (the walker keeps its own memory of the failed walk) (WorkGoal.doPlace)", stand.toShortString(), pos.toShortString(), g.blockPosition().toShortString()); return true; } // the walker's way there (in at his door), not a line through the wall
                    return false; } // (the cell beside: a nudge, a walk there never lands on the cell)
                against = Direction.DOWN; // (the floor in front of him: his look lands straight ahead, so the game runs the bed that way)
            }
        }
        if (stack.getItem() instanceof net.minecraft.world.item.BlockItem fb && (fb.getBlock() instanceof net.minecraft.world.level.block.ChestBlock || fb.getBlock() instanceof net.minecraft.world.level.block.AbstractFurnaceBlock)) {
            // A chest or furnace: its front toward the room. A player stands straight in front of the spot and clicks the wall behind
            // it; placed from an angle the two halves of a double chest face different ways and never join (his house's chests
            // came out as two singles, facing the front wall, and his storage pass then took them up again).
            Direction front = null; double fd = Double.MAX_VALUE; BlockPos frontStand = null;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                if (t.facing() != null && d != t.facing().getOpposite()) continue; // the plan says which way its front looks (a corner chest has two walls to pick from; only the row it belongs to knows)
                BlockPos wall = pos.relative(d), standP = pos.relative(d.getOpposite());
                BlockState ws = level.getBlockState(wall);
                boolean backed = !ws.isAir() && ws.getFluidState().isEmpty() && !ws.canBeReplaced() && !com.claudemod.entity.ClaudeEntity.usableBlock(level, wall);
                java.util.function.Predicate<BlockPos> standable0 = sp -> level.getBlockState(sp).getCollisionShape(level, sp).isEmpty() && level.getBlockState(sp.above()).getCollisionShape(level, sp.above()).isEmpty() && !level.getBlockState(sp.below()).getCollisionShape(level, sp.below()).isEmpty();
                for (int down = 0; down < 2 && !standable0.test(standP); down++) standP = standP.below(); // a chest or furnace at head height (the top row of a 2x2, a furnace wall): placed from the floor in front of it
                boolean standable = standable0.test(standP);
                if (!backed || !standable) continue;
                double dd = g.distanceToSqr(Vec3.atCenterOf(standP));
                { BlockPos out = standP.relative(d.getOpposite()); if (!level.getBlockState(out).getCollisionShape(level, out).isEmpty()) continue; } // (no such front: the usual placement below) // the front opens onto the room: a cell with open space past it, never the next cell of the same notch (he stood in the second chest's slot and set the pair sideways)
                for (Direction s : new Direction[] {d.getClockWise(), d.getCounterClockWise()}) { // a single chest beside it: the new one faces the same way, so they join
                    BlockState bs = level.getBlockState(pos.relative(s));
                    if (bs.getBlock() instanceof net.minecraft.world.level.block.ChestBlock && bs.getValue(net.minecraft.world.level.block.ChestBlock.TYPE) == net.minecraft.world.level.block.state.properties.ChestType.SINGLE && bs.getValue(net.minecraft.world.level.block.ChestBlock.FACING) == d.getOpposite()) dd = -1;
                }
                if (dd < fd) { fd = dd; front = d; frontStand = standP; }
            }
            boolean floorPair = false;
            if (front == null && t.facing() == null && fb.getBlock() instanceof net.minecraft.world.level.block.ChestBlock) {
                for (Direction s2 : Direction.Plane.HORIZONTAL) {
                    BlockState bs = level.getBlockState(pos.relative(s2));
                    if (!(bs.getBlock() instanceof net.minecraft.world.level.block.ChestBlock) || bs.getValue(net.minecraft.world.level.block.ChestBlock.TYPE) != net.minecraft.world.level.block.state.properties.ChestType.SINGLE) continue;
                    Direction f = bs.getValue(net.minecraft.world.level.block.ChestBlock.FACING);
                    if (f.getAxis() == s2.getAxis()) continue; // (a chest in front of or behind it never joins it)
                    BlockPos sp = pos.relative(f);
                    if (!level.getBlockState(sp).getCollisionShape(level, sp).isEmpty() || !level.getBlockState(sp.above()).getCollisionShape(level, sp.above()).isEmpty() || level.getBlockState(sp.below()).getCollisionShape(level, sp.below()).isEmpty()) continue;
                    front = f.getOpposite(); frontStand = sp; floorPair = true; break;
                }
            }
            if (front != null) {
                BlockPos standP = frontStand;
                if (!g.blockPosition().equals(standP)) {
                    if (standP.distSqr(g.blockPosition()) <= 4 && standP.getY() == g.getBlockY()) { // the last block: a nudge onto it
                        if (ticks > 100) { g.attempts.fail(com.claudemod.entity.life.Attempts.Kind.PLACE, pos, g.tickCount, g.blockPosition()); com.claudemod.ClaudeMod.LOGGER.info("[Claude life] couldn't get onto {} in front of the {} spot at {}: not setting it askew, next round", standP.toShortString(), itemName(stack), pos.toShortString()); return true; }
                        g.getNavigation().stop(); g.getMoveControl().setWantedPosition(standP.getX() + 0.5, standP.getY(), standP.getZ() + 0.5, 0.5); return false;
                    }
                    if (!walkFailedSince(standP, pos)) { sentFor().put(pos.immutable(), g.tickCount); g.tasks.addFirst(t); g.tasks.addFirst(new Task.GoTo(standP, 0.8)); return exit("the walk as its own task: the whole walker, not a path call every second; again while he isn't on the spot, un (doPlace 2797)"); } // the walk as its own task: the whole walker, not a path call every second; again while he isn't on the spot, unless that walk failed
                    g.attempts.fail(com.claudemod.entity.life.Attempts.Kind.PLACE, pos, g.tickCount, g.blockPosition());
                    com.claudemod.ClaudeMod.LOGGER.info("[Claude life] couldn't get in front of the {} spot at {} (he's at {}): not setting it askew, next round", itemName(stack), pos.toShortString(), g.blockPosition().toShortString());
                    return exit("doPlace line 2800");
                }
                against = floorPair ? Direction.DOWN : front; // (open floor: the floor in front of him, looking the way the other chest faces)
            }
        }
        // right click the neighbour's face: the game places it (facing, logs' axis, rules) as for any player
        boolean sneak = com.claudemod.entity.ClaudeEntity.usableBlock(level, pos.relative(against));
        Vec3 hit = Vec3.atCenterOf(pos.relative(against)).add(against.getOpposite().getStepX() * 0.5, against.getOpposite().getStepY() * 0.5, against.getOpposite().getStepZ() * 0.5);
        if (against.getAxis().isVertical() && away != null) hit = hit.add(away.getStepX() * 0.4, 0, away.getStepZ() * 0.4); // aimed toward the back: he faces into the wall, like lining up a chest
        { // a player places where he can see (the user: "he's placing blocks through walls"): the face he clicks must be the first thing his eyes meet; another neighbour's face he can see will do; else a step to where he can see one; else it's left
            Direction seenFace = null; Vec3 seenAt = null; Vec3 eye0 = g.getEyePosition();
            for (int pass = 0; pass < 2 && seenFace == null; pass++) for (Direction d : Direction.values()) {
                if ((pass == 0) != (d == against)) continue; // the planned face first
                BlockPos nb = pos.relative(d); BlockState ns = level.getBlockState(nb);
                if (ns.isAir() || !ns.getFluidState().isEmpty() || ns.canBeReplaced()) continue;
                Vec3 fp = visibleFacePoint(level, eye0, nb, d, 4.5);
                if (fp != null) { seenFace = d; seenAt = fp; break; }
            }
            if (seenFace != null) { against = seenFace; sneak = com.claudemod.entity.ClaudeEntity.usableBlock(level, pos.relative(against)); hit = seenAt; } // (the spot on that face his eyes reach: the top of a wall's face over a block below, a floor block's side seen past the edge)
            if (seenFace == null && coverCell(pos)) { // his cover's own block with no face he can see from inside it: into the middle of the cover first, then left out; never a walk out of the cover for it (he walked out of his box for its corners, live 04:42, and the way back in was dug through its walls)
                if (nudgeIntoCover(t, "out of his sight")) return false; // (the Place stays on while he steps in)
                com.claudemod.ClaudeMod.LOGGER.info("[Claude life] his cover's block at {}: no face he can see from inside (he's at {}): left out, not walking out of his cover for it (WorkGoal.doPlace)", pos.toShortString(), g.blockPosition().toShortString());
                g.tasks.remove(new Task.Break(pos.immutable())); g.pillarBlocks.remove(pos);
                return exit("a cover block he can't see from inside (doPlace)");
            }
            if (seenFace == null) {
                BlockPos view = null; double vd = Double.MAX_VALUE; // the nearest spot he can stand that sees some face of a neighbour
                for (BlockPos p0 : BlockPos.betweenClosed(pos.offset(-3, -2, -3), pos.offset(3, 2, 3))) {
                    BlockPos sp = p0.immutable();
                    if (sp.equals(pos) || !level.getBlockState(sp).getCollisionShape(level, sp).isEmpty() || !level.getBlockState(sp.above()).getCollisionShape(level, sp.above()).isEmpty() || level.getBlockState(sp.below()).getCollisionShape(level, sp.below()).isEmpty()) continue; // (farmland, a slab: something to stand on, not only a full-faced block)
                    double dd = sp.distSqr(g.blockPosition()); if (dd >= vd) continue;
                    Vec3 e2 = Vec3.atBottomCenterOf(sp).add(0, 1.62, 0);
                    for (Direction d : Direction.values()) {
                        BlockPos nb = pos.relative(d); BlockState ns = level.getBlockState(nb);
                        if (ns.isAir() || !ns.getFluidState().isEmpty() || ns.canBeReplaced()) continue;
                        if (visibleFacePoint(level, e2, nb, d, 4.25) != null) { view = sp; vd = dd; break; }
                    }
                }
                BlockPos feetV = BlockPos.containing(g.getX(), g.getY() + 0.2, g.getZ());
                long[] lastV = viewTry().get(pos); boolean moved = lastV == null || view == null || lastV[0] != view.asLong() || lastV[1] != feetV.asLong(); // the state: since his last step for this face he's moved, or the cell that sees one is another
                if (view != null && moved && !walkFailedSince(view, pos)) {
                    viewTry().put(pos.immutable(), new long[] {view.asLong(), feetV.asLong()});
                    BlockPos feet0 = feetV;
                    if (view.equals(feet0)) { g.getNavigation().stop(); g.getMoveControl().setWantedPosition(view.getX() + 0.5, view.getY(), view.getZ() + 0.5, 0.4); g.tasks.addFirst(t); g.tasks.addFirst(new Task.Wait(6, false)); return exit("the right block, the wrong edge of it: into the middle, where the hole is in view (doPlace 2837)"); } // the right block, the wrong edge of it: into the middle, where the hole is in view
                    if (view.distSqr(feet0) <= 4 && view.getY() <= feet0.getY() && feet0.getY() - view.getY() <= 1) { g.getNavigation().stop(); g.getMoveControl().setWantedPosition(view.getX() + 0.5, view.getY(), view.getZ() + 0.5, 0.5); g.tasks.addFirst(t); g.tasks.addFirst(new Task.Wait(10, false)); return exit("a block or two off: a nudge, a walk there lands a block short (doPlace 2838)"); } // a block or two off: a nudge, a walk there lands a block short
                    if (view.distSqr(feet0) <= 4 && view.getY() > feet0.getY()) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] the spot that sees a face to set {} against is a step up at {} (he's at {}): the walker there, its jump; a nudge only slides along the ground (WorkGoal.doPlace)", pos.toShortString(), view.toShortString(), feet0.toShortString()); // (live 07:52 and 15:20 world 14: nudged at a spot one up, he never moved, and it gave up: doPlace 2838, then 2844)
                    sentFor().put(pos.immutable(), g.tickCount); g.tasks.addFirst(t); g.tasks.addFirst(new Task.GoTo(view, 0.5)); return exit("doPlace line 2839");
                }
                viewTry().remove(pos);
                com.claudemod.ClaudeMod.LOGGER.info("[Claude life] can't see a face to set {} against from anywhere he can stand: not placing it through the wall (he's at {} eye {}, spot {}, moved since the last step: {})", pos.toShortString(), g.blockPosition().toShortString(), g.getEyePosition(), view == null ? "none" : view.toShortString(), moved);
                g.attempts.fail(com.claudemod.entity.life.Attempts.Kind.PLACE, pos, g.tickCount, g.blockPosition());
                return exit("doPlace line 2844");
            }
        }
        if (bedAim != null && against == Direction.DOWN) hit = bedAim; // (the bed from the corner: aimed at the foot's far half)
        g.useOnAt(pos.relative(against), against.getOpposite(), sneak, hit); // sneak only against a chest/table/door
        if (!level.getBlockState(pos).canBeReplaced()) { if (stepOutSince != null) stepOutSince.remove(pos); if (sentFor != null) sentFor.remove(pos); if (viewTry != null) viewTry.remove(pos); } // (placed: what was kept for getting it in is done with)
        if (level.getBlockState(pos).canBeReplaced()) {
            var who = level.getEntities((net.minecraft.world.entity.Entity) null, new net.minecraft.world.phys.AABB(pos), e -> !e.isSpectator());
            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] placing {} at {} didn't take (against {}, in the way: {})", itemName(stack), pos.toShortString(), against,
                    who.isEmpty() ? "nothing" : who.get(0).getName().getString());
        }
        if (level.getBlockState(pos).getBlock() instanceof net.minecraft.world.level.block.ChestBlock)
            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] chest placed at {} against {} (sneak {}), he faces {} from {}: {}", pos.toShortString(), against, sneak,
                    g.getDirection(), g.blockPosition().toShortString(), level.getBlockState(pos));
        return exit("doPlace line 2858");
    }

    private java.util.Map<BlockPos, int[]> aimTry; // a planned-state placement -> {0, steps taken for it}
    /** A planned state: the click (face, spot, his look) the game turns into that state, from here or the nearest spot that has one; never a click that gives something else. Null: nothing about the click decides it (the usual placement). */
    private Boolean placeAimed(Task.Place t, ItemStack stack, ServerLevel level) {
        if (t.want() == null || !com.claudemod.entity.life.PlaceAim.directional(t.want())) return null;
        if (aimTry == null) aimTry = new java.util.HashMap<>();
        BlockPos pos = t.pos(); net.minecraft.world.level.block.state.BlockState want = t.want();
        var click = com.claudemod.entity.life.PlaceAim.from(g, level, g.getEyePosition(), pos, stack, want);
        if (click != null) {
            boolean sneak = com.claudemod.entity.ClaudeEntity.usableBlock(level, click.clicked());
            g.useOnAt(click.clicked(), click.face(), sneak, click.hit());
            var got = level.getBlockState(pos);
            aimTry.remove(pos);
            if (com.claudemod.entity.life.PlaceAim.growsInto(got, want)) { g.tasks.addFirst(t); return exit("half of the double slab in: the second on it (doPlace aimed)"); }
            if (com.claudemod.entity.life.PlaceAim.same(got, want)) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] set {} at {} as planned: clicked {} on its {} face from {} (WorkGoal.placeAimed)", got, pos.toShortString(), click.clicked().toShortString(), click.face(), g.blockPosition().toShortString());
            else com.claudemod.ClaudeMod.LOGGER.info("[Claude life] placing {} at {} for {} came out {} (clicked {} {} from {}): the build's check fixes it (WorkGoal.placeAimed)", itemName(stack), pos.toShortString(), want, got, click.clicked().toShortString(), click.face(), g.blockPosition().toShortString());
            return exit("placed as planned (doPlace aimed)");
        }
        BlockPos feet = BlockPos.containing(g.getX(), g.getY() + 0.2, g.getZ());
        BlockPos stand = com.claudemod.entity.life.PlaceAim.standFor(g, level, pos, stack, want);
        int[] tr = aimTry.get(pos); int tries = tr == null ? 0 : tr[1];
        if (stand != null && tries < 3) {
            aimTry.put(pos.immutable(), new int[] {0, tries + 1});
            if (stand.equals(feet) || stand.distSqr(feet) <= 2 && stand.getY() == feet.getY()) { // on it or a step off, his eyes off the spot that works: a nudge into its middle
                g.getNavigation().stop(); g.getMoveControl().setWantedPosition(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 0.4); g.tasks.addFirst(t); g.tasks.addFirst(new Task.Wait(8, false));
                com.claudemod.ClaudeMod.LOGGER.info("[Claude life] {} at {}: no click from where his eyes are gives it; a step into {} where one does (WorkGoal.placeAimed)", want, pos.toShortString(), stand.toShortString());
                return exit("a step into the spot whose click gives it (doPlace aimed)");
            }
            if (!walkFailedSince(stand, pos)) {
                sentFor().put(pos.immutable(), g.tickCount); g.tasks.addFirst(t); g.tasks.addFirst(new Task.GoTo(stand, 0.4));
                com.claudemod.ClaudeMod.LOGGER.info("[Claude life] {} at {}: no click from {} gives it; to {} first, where one does (WorkGoal.placeAimed)", want, pos.toShortString(), feet.toShortString(), stand.toShortString());
                return exit("to the spot whose click gives it (doPlace aimed)");
            }
        }
        aimTry.remove(pos);
        com.claudemod.ClaudeMod.LOGGER.info("[Claude life] no spot he can stand that gives {} at {} (he's at {}, spot {}, steps tried {}): not placing it wrong (WorkGoal.placeAimed)", want, pos.toShortString(), feet.toShortString(), stand == null ? "none" : stand.toShortString(), tries);
        g.attempts.fail(com.claudemod.entity.life.Attempts.Kind.PLACE, pos, g.tickCount, g.blockPosition());
        return exit("no click gives the planned state (doPlace aimed)");
    }

    private boolean doDoor(Task.UseDoor t) {
        ServerLevel level = (ServerLevel) g.level();
        BlockState state = level.getBlockState(t.pos());
        if (state.is(net.minecraft.tags.BlockTags.BEDS)) { // a bed: one right-click sets his spawn point there (by day too), the way it does for any player
            if (!inReach(t.pos())) return walkTo(t.pos(), 2) && !inReach(t.pos());
            g.getNavigation().stop();
            if (ticks < 6) { g.getLookControl().setLookAt(Vec3.atCenterOf(t.pos())); return false; }
            boolean ok = g.useOnAt(t.pos(), Direction.UP, false, Vec3.atCenterOf(t.pos()).add(0, 0.3, 0));
            if (g.isSleeping()) { String notNow = com.claudemod.director.ClaudeLife.sleepNotNow(g); if (notNow != null) { g.stopSleepInBed(true, true); com.claudemod.ClaudeMod.LOGGER.info("[Claude life] clicked his bed at {} at night: spawn {}, up again at once: {} (WorkGoal.doDoor)", t.pos().toShortString(), g.getRespawnPosition() != null ? "set at " + g.getRespawnPosition().toShortString() : "not set", notNow); } } // (vanilla sets the spawn on the click, before the night check: getting straight up keeps it, as for any player)
            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] clicked his bed at {}: spawn point {}", t.pos().toShortString(), ok && g.getRespawnPosition() != null ? "set" : "not set");
            return true;
        }
        if (!(state.getBlock() instanceof DoorBlock) && !(state.getBlock() instanceof net.minecraft.world.level.block.FenceGateBlock)) return true;
        { Boolean reach = reachFor(t.pos()); if (reach != null) return reach; } /* (the user: using a furnace through a wall is a bug; a player opens what he can see) */ // a free spot beside it, not the furnace block itself (set in his stair wall, "done, going to take it out" 13 times over 15 minutes and never in reach)
        g.getNavigation().stop();
        g.getLookControl().setLookAt(Vec3.atCenterOf(t.pos()));
        if (ticks < 6) return false;
        boolean openNow = state.getBlock() instanceof DoorBlock ? state.getValue(DoorBlock.OPEN) : state.getValue(net.minecraft.world.level.block.FenceGateBlock.OPEN);
        if (openNow != t.open()) {
            // right click the door (empty-handed use; useOn sneaks, which would skip doors, so click it directly)
            Vec3 hit = Vec3.atCenterOf(t.pos());
            g.getLookControl().faceNow(hit);
            g.gameMode.useItemOn(g, level, ItemStack.EMPTY, InteractionHand.MAIN_HAND,
                    new net.minecraft.world.phys.BlockHitResult(hit, Direction.getNearest(g.getX() - hit.x, 0, g.getZ() - hit.z), t.pos(), false));
            g.swing(InteractionHand.MAIN_HAND);
        }
        g.noteDoing((t.open() ? "opened" : "closed") + " a door at their place");
        return true;
    }

    private boolean doChest(Task.Chest t) {
        ServerLevel level = (ServerLevel) g.level();
        BlockPos pos = t.pos();
        if (!(level.getBlockEntity(pos) instanceof Container) && com.claudemod.entity.life.ChestLedger.knows(g, pos)) { if (!inReach(pos)) return walkTo(pos, 2) && !inReach(pos); com.claudemod.entity.life.ChestLedger.forget(g, pos, "is gone (he's at the spot)"); return true; } // one on his ledger: he finds out it's gone there, not from across the world
        if (!(level.getBlockEntity(pos) instanceof Container box) || box instanceof net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity) return true; // a furnace is no chest: its three slots are input, fuel and output (he had stored diamonds in one's input and raw copper in its output, which no player can do)
        if (ticks == 1 && unblockChest(pos, t)) return true;
        if (pos.equals(diveFor)) { diveFor = null; if (inReach(pos)) { com.claudemod.ClaudeMod.LOGGER.info("[Claude dive] at the chest {} after {} ticks, air {}, y {}: opening", pos.toShortString(), ticks, g.getAirSupply(), String.format("%.2f", g.getY())); g.tasks.addFirst(t); return true; } } // dived to it: the task starts over in reach (the open, the rummage, the take)
        if (!inReach(pos) && g.isInWater() && level.getFluidState(pos.above()).is(net.minecraft.tags.FluidTags.WATER)) { // a chest under water (a wreck): a dive, the way a player goes down for it
            if (ticks > 600) { com.claudemod.ClaudeMod.LOGGER.info("[Claude life] couldn't dive to the chest at {} in 30 s: leaving it", pos.toShortString()); return true; }
            if (g.getAirSupply() < 120) diveBreath = true;
            if (diveBreath && g.getAirSupply() >= 280) diveBreath = false;
            if (diveBreath) { g.getMoveControl().clear(); return false; } // up for a breath (the breath rule lifts him once nothing pulls him down); back down on a full lungful
            Vec3 over = Vec3.atCenterOf(pos.above());
            g.getNavigation().stop();
            g.getLookControl().setLookAt(Vec3.atCenterOf(pos));
            g.getMoveControl().setWantedPosition(over.x, over.y, over.z, 1.0); // the water over the chest: below his feet and wet, which is what the body dives for
            diveFor = pos.immutable();
            if (ticks % 20 == 1) com.claudemod.ClaudeMod.LOGGER.info("[Claude dive] tick {} to {}: y {}, air {}, dist {}, eyes under {}, dy {}", ticks, pos.toShortString(), String.format("%.2f", g.getY()), g.getAirSupply(), String.format("%.2f", g.getEyePosition().distanceTo(Vec3.atCenterOf(pos))), g.isEyeInFluid(net.minecraft.tags.FluidTags.WATER), String.format("%.3f", g.getDeltaMovement().y));
            return false;
        }
        if (g.isInWater() && level.getFluidState(pos.above()).is(net.minecraft.tags.FluidTags.WATER)) { Vec3 over = Vec3.atCenterOf(pos.above()); g.getMoveControl().setWantedPosition(over.x, over.y, over.z, 1.0); } // under water and in reach: the dive's goal kept alive every tick (it expires after 20), or the body floats him back up mid-rummage
        if (!inReach(pos)) return walkTo(pos, 2) && !inReach(pos);
        g.getNavigation().stop();
        g.getLookControl().setLookAt(Vec3.atCenterOf(pos));
        Block block = level.getBlockState(pos).getBlock();
        boolean chest = level.getBlockEntity(pos) instanceof ChestBlockEntity;
        if (ticks == 1) {
            if (g.isInWater()) com.claudemod.ClaudeMod.LOGGER.info("[Claude dive] opening {} under water with air {} at y {}", pos.toShortString(), g.getAirSupply(), String.format("%.2f", g.getY()));
            g.swing(InteractionHand.MAIN_HAND);
            if (chest) level.blockEvent(pos, block, 1, 1);
            level.playSound(null, pos, SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 0.5f, 0.9f);
            return false;
        }
        if (ticks < 10) return false; // a quick look: a speedrunner shift-clicks a chest empty in about half a second (2.5 s of "rummaging" read as standing about)
        if (t.take() && t.leave() == net.minecraft.world.item.Items.STRUCTURE_VOID) { // his own chest emptied into his pockets (a move): every stack he has room for, both halves of a double
            Container from = box; BlockState cs0 = level.getBlockState(pos);
            if (cs0.getBlock() instanceof net.minecraft.world.level.block.ChestBlock cb0) { Container both = net.minecraft.world.level.block.ChestBlock.getContainer(cb0, cs0, level, pos, true); if (both != null) from = both; }
            int took = 0; boolean full0 = false;
            for (int i = 0; i < from.getContainerSize(); i++) {
                ItemStack s = from.getItem(i); if (s.isEmpty()) continue;
                ItemStack taken = from.removeItem(i, s.getCount()); ItemStack left = g.inventory.add(taken);
                took += taken.getCount() - left.getCount();
                if (!left.isEmpty()) { from.setItem(i, left); full0 = true; }
            }
            from.setChanged(); com.claudemod.entity.life.ChestLedger.look(g, level, pos); // (what he saw in it: empty now, or what's left for the next load)
            if (chest) level.blockEvent(pos, block, 1, 0);
            level.playSound(null, pos, SoundEvents.CHEST_CLOSE, SoundSource.BLOCKS, 0.5f, 0.9f);
            g.noteDoing("took " + took + " item(s) out of my old chest to move them" + (full0 ? " (pockets full: another load)" : ""));
            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] the old chest at {}: took {} item(s) to move{}", pos.toShortString(), took, full0 ? ", pockets full" : "");
            return true;
        }
        if (t.take() && t.leave() == net.minecraft.world.item.Items.AIR) { // loot mode (batch 1): a structure's chest; his click rolls it, everything worth taking comes along, the valuable first if the pockets fill
            if (level.getBlockEntity(pos) instanceof net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity r) r.unpackLootTable(g);
            BlockState cs = level.getBlockState(pos);
            Container lootBox = box;
            if (cs.getBlock() instanceof net.minecraft.world.level.block.ChestBlock cb && cs.getValue(net.minecraft.world.level.block.ChestBlock.TYPE) != net.minecraft.world.level.block.state.properties.ChestType.SINGLE) { // a double chest: one click shows both halves
                if (level.getBlockEntity(pos.relative(net.minecraft.world.level.block.ChestBlock.getConnectedDirection(cs))) instanceof net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity r2) r2.unpackLootTable(g);
                Container both = net.minecraft.world.level.block.ChestBlock.getContainer(cb, cs, level, pos, true);
                if (both != null) lootBox = both;
            }
            final Container lb = lootBox;
            int took = 0; boolean full0 = false; java.util.List<String> names = new java.util.ArrayList<>();
            java.util.List<Integer> slots = new java.util.ArrayList<>();
            for (int i = 0; i < lb.getContainerSize(); i++) if (com.claudemod.director.ClaudeLife.worthTaking(g, lb.getItem(i))) slots.add(i);
            slots.sort((a, b2) -> Integer.compare(com.claudemod.director.ClaudeLife.lootValue(lb.getItem(b2)), com.claudemod.director.ClaudeLife.lootValue(lb.getItem(a))));
            for (int i : slots) {
                ItemStack s = lb.getItem(i);
                if (!g.inventory.hasRoomFor(s)) { com.claudemod.director.ClaudeLife.makeRoom(g); if (!g.inventory.hasRoomFor(s)) { full0 = true; continue; } }
                ItemStack taken = lb.removeItem(i, s.getCount());
                ItemStack left = g.inventory.add(taken);
                int got = taken.getCount() - left.getCount();
                took += got; if (got > 0) names.add(got + " " + itemName(taken));
                if (!left.isEmpty()) { lb.setItem(i, left); full0 = true; }
            }
            com.claudemod.director.ClaudeLife.lootedChests().add(pos.immutable());
            g.noteDoing(took == 0 ? "nothing worth taking in that chest" : "looted " + String.join(", ", names) + (full0 ? " (pockets full, the rest stays)" : ""));
            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] looted the chest at {}: {}", pos.toShortString(), took == 0 ? "nothing worth it" : String.join(", ", names));
        } else if (t.take() && t.leave() != null) { // his own chest: take all of that item
            int n = 0;
            for (int i = 0; i < box.getContainerSize(); i++) {
                if (!box.getItem(i).is(t.leave())) continue;
                if (n >= t.max()) break; // what he came for, no more (asked for a stack, he emptied the chest into his pockets)
                ItemStack taken = box.removeItem(i, Math.min(box.getItem(i).getCount(), t.max() - n));
                ItemStack left = g.inventory.add(taken);
                n += taken.getCount() - left.getCount();
                if (!left.isEmpty()) { ItemStack back = box.getItem(i).isEmpty() ? left : box.getItem(i).copyWithCount(box.getItem(i).getCount() + left.getCount()); box.setItem(i, back); break; } // no room for the rest: it stays in the chest
            }
            if (n == 0) com.claudemod.ClaudeMod.LOGGER.info("[Claude chest] took no {} from {}: {} (WorkGoal.doChest)", t.leave().getDescription().getString(), pos.toShortString(), g.inventory.hasRoomFor(new ItemStack(t.leave())) ? "none in it" : "pockets full");
            if (n > 0) g.noteDoing("took " + n + " " + t.leave().getDescription().getString() + " from my chest");
            if (n > 0) com.claudemod.entity.life.OwnEvents.took(g, t.leave(), n, pos);
        } else if (t.take()) {
            List<Integer> full = new java.util.ArrayList<>();
            for (int i = 0; i < box.getContainerSize(); i++) if (!box.getItem(i).isEmpty()) full.add(i);
            if (!full.isEmpty()) {
                int slot = full.get(g.getRandom().nextInt(full.size()));
                ItemStack s = box.getItem(slot);
                int n = Math.max(1, Math.min(s.getCount(), 1 + g.getRandom().nextInt(3)));
                ItemStack taken = box.removeItem(slot, n);
                g.noteDoing("took " + taken.getCount() + " " + itemName(taken) + " from their chest");
                g.inventory.add(taken);
            }
        } else if (t.leave() != null) {
            ItemStack give = g.inventory.find(s -> s.is(t.leave()));
            if (!give.isEmpty()) {
                ItemStack one = give.split(1);
                for (int i = 0; i < box.getContainerSize(); i++) {
                    if (box.getItem(i).isEmpty()) {
                        box.setItem(i, one);
                        g.noteDoing("left " + itemName(one) + " in their chest");
                        break;
                    }
                }
            }
        }
        box.setChanged();
        com.claudemod.entity.life.ChestLedger.opened(g, level, pos); // he had it open: his ledger is what's in it now (an emptied chest found empty, and the plan re-made)
        if (g.isInWater()) g.getMoveControl().clear(); // (a dive's goal: nothing keeps pulling him down once the chest is done)
        if (chest) level.blockEvent(pos, block, 1, 0);
        level.playSound(null, pos, SoundEvents.CHEST_CLOSE, SoundSource.BLOCKS, 0.5f, 0.9f);
        return true;
    }

    private boolean doWait(Task.Wait t) {
        g.getNavigation().stop();
        if (digInTasks != null && digInTasks.stream().anyMatch(x -> x == t)) { // hiding (up out of reach, or down a hole)
            // still in the cover at all? arrows knock a block a hit (five took him off a tower and he stood below it in "wait" to the end), a tower that never went up is just the ground, and a "hole" into a pond is a swim
            boolean tower = !digInTasks.isEmpty() && digInTasks.get(0) instanceof Task.Pillar;
            boolean walled = !digInTasks.isEmpty() && digInTasks.get(0) instanceof Task.Place; // boxed in where he stood (a cave, a tower top)
            boolean stayPut = digInTasks.size() == 1 && digInTasks.get(0) == t; // "already up his tower: staying put" is cover where he stands (read as a hole he wasn't down, it flapped out and back in several times a second at 2 health)
            boolean covered = digInAt != null && inDigInHole() && !g.isInWater() && (walled || stayPut || (tower ? g.getY() >= digInAt.getY() + 1.9 : g.getY() <= digInAt.getY() - 1.9));
            if (!covered && ticks > 5) {
                com.claudemod.ClaudeMod.LOGGER.info("[Claude life] out of his cover at {} ({}, he's at {}): that wait's over, cover again from here", digInAt == null ? "?" : digInAt.toShortString(), g.isInWater() ? "in water" : tower ? "off the tower" : "out of the hole", g.blockPosition().toShortString());
                g.tasks.removeAll(digInTasks); digInAt = null; digInTasks = null;
                if (com.claudemod.director.ClaudeLife.nightOutsideNo(g, (ServerLevel) g.level(), g.blockPosition()) != null) { desperateNow = true; digInFromArrows(); desperateNow = false; } // night and no gear: cover again right here, whether or not anything is on him now
                else digInFromArrows(); // fresh cover where he stands, if anything still calls for it; else back to the job
                return true;
            }
            for (var m : hostilesNear(4)) // up on his pillar: whatever's in reach below can't hit back, so he hits it; one in the gap of his walls hitting him: he hits back (he stood in his half-built hut and took seven zombie hits at full health, never swinging)
                if ((g.getY() - m.getY() >= 1.9 || m.distanceTo(g) < 2.5) && g.getAttackStrengthScale(0.5f) >= 0.9f && m.getBoundingBox().distanceToSqr(g.getEyePosition()) <= 9 && g.hasLineOfSight(m)) {
                    if (m.distanceTo(g) < 2.5) com.claudemod.director.ClaudeLife.craftSwordIfCan(g, (ServerLevel) g.level());
                    equipWeapon();
                    g.getLookControl().setLookAt(m); g.attack(m); g.swing(InteractionHand.MAIN_HAND); break;
                }
            // the night in a hole is not wasted: down in his covered hole and healthy, he mines the stone round his feet and head (each block only if everything else round it is solid and dry: the hole stays sealed), cobble for the morning's sword and pick
            if (!tower && !walled && digInAt != null && g.getY() <= digInAt.getY() - 1.9 && g.getHealth() >= 14 && ticks % 40 == 20 && hostilesNear(4).isEmpty() && !holdingNight() && !g.level().isDay()) { // (the night's work: by day he's on his way out) // (not while holding for the night: it widened the hole till he was out of it)
                boolean pick = false; for (int i = 0; i < g.inventory.items.getContainerSize(); i++) if (g.inventory.items.getItem(i).getItem() instanceof net.minecraft.world.item.PickaxeItem) { pick = true; break; }
                ServerLevel lv = (ServerLevel) g.level(); BlockPos ft = g.blockPosition();
                if (pick) for (Direction d : Direction.Plane.HORIZONTAL) {
                    BlockPos a = ft.relative(d), b = a.above(); boolean ok = true;
                    for (BlockPos c : new BlockPos[] {a, b}) {
                        BlockState cs = lv.getBlockState(c);
                        if (!cs.is(net.minecraft.tags.BlockTags.STONE_ORE_REPLACEABLES) && !cs.is(net.minecraft.tags.BlockTags.DEEPSLATE_ORE_REPLACEABLES)) { ok = false; break; }
                        for (Direction e : Direction.values()) { BlockPos n = c.relative(e); if (n.equals(ft) || n.equals(ft.above()) || n.equals(a) || n.equals(b)) continue; if (lv.getBlockState(n).getCollisionShape(lv, n).isEmpty() || !lv.getFluidState(n).isEmpty()) { ok = false; break; } }
                        if (!ok) break;
                    }
                    if (!ok) continue;
                    if (Math.abs(a.getX() - digInAt.getX()) > 1 || Math.abs(a.getZ() - digInAt.getZ()) > 1) continue; // (only beside the hole's column: his cover is the column)
                    g.tasks.addFirst(t); g.tasks.addFirst(new Task.GoTo(ft.immutable(), 0.3)); g.tasks.addFirst(new Task.Pickup(20)); g.tasks.addFirst(new Task.Break(a.immutable())); g.tasks.addFirst(new Task.Break(b.immutable())); // (and back under the lid after it: the way out starts there)
                    com.claudemod.ClaudeMod.LOGGER.info("[Claude life] in his hole for the night: mining the stone at {} meanwhile (sealed all round)", a.toShortString());
                    return true;
                }
            }
            boolean phantomNight = !g.level().isDay() && !g.level().getEntitiesOfClass(net.minecraft.world.entity.monster.Phantom.class, g.getBoundingBox().inflate(32), m -> m.isAlive()).isEmpty();
            // in the hole, hurt, with food on him: eat it there (health comes back under cover, not out in the open)
            if (g.getHealth() < 16 && !g.isUsingItem() && ticks % 20 == 7) { var f = com.claudemod.director.ClaudeLife.foodToEat(g, true); if (f != null) { ItemStack fs = g.inventory.find(st -> st.is(f)); if (!fs.isEmpty() && g.hold(fs)) g.useHeld(); } }
            boolean weakNight = !g.level().isDay() && g.level().canSeeSky(g.blockPosition()) && ((!armed() || weakKit()) || g.getHealth() < 14); // (up top: the hole holds till morning; underground the clock means nothing, the wait ends when the crowd has gone: walled in at the face 8 minutes for "night") // nothing proper to fight with or in, or hurt, and it's dark: the hole is the place till morning, whether or not a mob happens to be in range right now (he came out at 6 health when they wandered off, and got hit to 2)
            java.util.List<net.minecraft.world.entity.Mob> lately = com.claudemod.entity.life.Perception.lately(g, 16); // (what he sensed lately counts as still about: under the lid the zombie on top is only heard now and then)
            if (ticks % 100 == 1 && !lately.isEmpty() && hostilesNear(16).isEmpty()) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] in his cover: {} sensed lately and not since, still about to him (doWait)", lately.get(0).getType().getDescription().getString());
            boolean hurtAndHunted = ((g.getHealth() < 8 && com.claudemod.director.ClaudeLife.foodToEat(g, true) == null && (!lately.isEmpty() || !(g.getBlockY() >= g.level().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, g.getBlockX(), g.getBlockZ()) - 4 && !g.level().isDay() ? enemiesNear(24) : hostilesNear(16)).isEmpty() || enemiesNear(16).size() >= 3)) || phantomNight || weakNight) && (ticks < 20 * 600 || !g.level().isDay()); // the 10-minute cap is a daytime thing: at night the hole holds till morning, however long (the cap dumped him out at 6 health into the mobs) // (underground: only what's after him; a cave always has something within 24, and he sat at 2 health on his tower 4+ minutes, never healing, never going for food)
            // his own things lying out there have five minutes before they vanish: at 3 minutes, with health to run with, he goes for them whatever the night (31 stacks with iron in them vanished under a hole-wait once)
            if (g.getHealth() >= 10 && ticks % 40 == 3) {
                var mine = new java.util.ArrayList<>(com.claudemod.director.ClaudeLife.ownDropsNear(g, (ServerLevel) g.level(), 160));
                mine.removeIf(i -> !com.claudemod.entity.life.Clocks.canMake(g, i)); // the ones his clock says he can still get to // (160: he respawns a good way from where he fell; 64 never saw the 17 piles 150 blocks off, and they vanished under the wait)
                int worth = com.claudemod.director.ClaudeLife.dropsWorth(mine);
                int goAt = worth >= 80 ? 20 * 90 : worth >= 30 ? 20 * 150 : 20 * 200; // the more it's worth, the sooner he breaks cover for it (five minutes and it's gone)
                if (worth >= (weakNight ? 80 : 30) && !mine.isEmpty() && mine.stream().anyMatch(i -> i.getAge() > goAt) && (hostilesNear(6).isEmpty() || worth >= 80 && hostilesNear(4).isEmpty())) { // (a junk pile is not worth leaving cover for: iron or better only, the user's rule)
                    com.claudemod.ClaudeMod.LOGGER.info("[Claude life] his things at {} are {}s from vanishing: out of the hole for them now", mine.get(0).blockPosition().toShortString(), com.claudemod.entity.life.Clocks.left(mine.get(0)) / 20);
                    g.skillQueue.addFirst(new com.claudemod.entity.life.Skills.Step("recover_drops", "", 1));
                    return true;
                }
            }
            if (g.getHealth() < 8 && !hostilesNear(12).isEmpty() && !(g.level().isDay() && ticks > 20 * 45 && digInAt != null && !tunnelOut().contains(digInAt))) return false; // (by day, 45 s in and no tunnel out tried yet: the tunnel-out below gets its go; walled in a cave at 6 health he sat 5 minutes with zombies that never burn down there) // at a few hearts with mobs still right there: the cover holds, however long (coming down at 1 health into them is a death, not a plan)
            if (ticks % 100 == 51 && hostilesNear(8).isEmpty()) lately.stream().filter(m -> m instanceof net.minecraft.world.entity.monster.Creeper || m.distanceTo(g) <= 8).findFirst().ifPresent(m -> com.claudemod.ClaudeMod.LOGGER.info("[Claude life] staying in his cover: {} heard about {} off lately, out of sight (a creeper waits under you) (doWait)", m.getType().getDescription().getString(), (int) m.distanceTo(g)));
            String shooter = shooterAbout(); // a skeleton he sees, hears or sensed lately: what a box is for; it stays up while that's about, whatever the wait's own clock says (live 04:43: down after 7 s with the skeleton 9-15 off)
            if (ticks > 40 && g.commandActive && g.getHealth() >= 14 && hostilesNear(6).isEmpty() && shooter == null && !hurtAndHunted && !holdingNight()) { com.claudemod.ClaudeMod.LOGGER.info("[Claude life] their order waiting, health {}, nothing within 6: out of his cover at {} for it (WorkGoal.doWait)", (int) g.getHealth(), digInAt == null ? "?" : digInAt.toShortString()); return true; } // (17:08 geared by them, ordered "go" twice, he held 3 min for a phantom)
            if (ticks > 40 && hostilesNear(8).isEmpty() && shooter == null && !hurtAndHunted && !holdingNight() && lately.stream().noneMatch(m -> !(m instanceof net.minecraft.world.entity.monster.Phantom && armed() && !weakKit()) && (m instanceof net.minecraft.world.entity.monster.Creeper || m.distanceTo(g) <= 8))) { /* (a phantom overhead doesn't pin an armed, armored player in a hole) */ com.claudemod.ClaudeMod.LOGGER.info("[Claude life] nothing about his cover at {} any more (none in sight, heard or sensed lately): out after {} s (WorkGoal.doWait)", digInAt == null ? "?" : digInAt.toShortString(), ticks / 20); return true; } // quiet out there: back to it (not at night with no gear: quiet now is a zombie in a minute; every rule that walked him out of his hole died to it)
            boolean caveCover = digInAt != null && underground((ServerLevel) g.level(), digInAt); // (down here the sun never clears them: day or night, the minute is the same)
            if ((g.level().isDay() || caveCover) && g.getHealth() >= 14 && ticks > 20 * 60 && !phantomNight && !holdingNight()) { com.claudemod.ClaudeMod.LOGGER.info("[Claude life] {}, health {}, a minute in his cover with {} still about: out to deal with it (WorkGoal.doWait)", caveCover ? "in a cave" : "daylight", (int) g.getHealth(), shooter != null ? shooter : "something"); return true; } // (six minutes in a hole at 17 health in broad daylight is not a player)
            // hurt, in the hole by day, mobs still camped on top and no food to heal with: a player tunnels out sideways and comes up out of their sight, he doesn't sit till the cap dumps him into them
            if (g.level().isDay() && g.getHealth() < 14 && ticks > 20 * 45 && digInAt != null && (walled || g.getY() <= digInAt.getY() - 1) && tunnelOut().add(digInAt.immutable())) {
                var near = enemiesNear(24); // camped anywhere about, not just right on the lid
                if (!near.isEmpty()) {
                    near.sort(java.util.Comparator.comparingDouble(x -> x.distanceToSqr(g)));
                    var m = near.get(0);
                    Direction away = Direction.getNearest(g.getX() - m.getX(), 0, g.getZ() - m.getZ());
                    BlockPos feet = g.blockPosition();
                    java.util.List<Task> dig = new java.util.ArrayList<>();
                    for (int i = 1; i <= 16; i++) { BlockPos f = feet.relative(away, i); dig.add(new Task.Break(f.immutable())); dig.add(new Task.Break(f.above().immutable())); if (i % 4 == 0) dig.add(new Task.GoTo(f.immutable(), 0.9)); }
                    BlockPos end = feet.relative(away, 16);
                    dig.add(new Task.Break(end.above(2).immutable())); dig.add(new Task.Break(end.above(3).immutable())); // the way up at the far end
                    dig.add(new Task.Pickup(20)); dig.add(new Task.Pillar(2)); dig.add(new Task.Pickup(20));
                    g.tasks.removeAll(digInTasks);
                    for (int i = dig.size() - 1; i >= 0; i--) g.tasks.addFirst(dig.get(i));
                    com.claudemod.ClaudeMod.LOGGER.info("[Claude life] hurt in the hole by day with {} mob(s) camped on top: tunnelling out 16 blocks {} and coming up there", near.size(), away.getName());
                    return true;
                }
            }
            if (hurtAndHunted) return false;
            if (holdingNight()) { // the night in here, till first light: no time-out
                if (holdLogged != digInTick) { holdLogged = digInTick; com.claudemod.ClaudeMod.LOGGER.info("[Claude life] covered at {} for the night ({}): holding here till first light", digInAt.toShortString(), tower ? "up his tower" : walled ? "walled in" : "in his hole"); }
                return false;
            }
            if (shooter != null) { if (shooterHeld != digInTick) { shooterHeld = digInTick; com.claudemod.ClaudeMod.LOGGER.info("[Claude life] holding his cover at {} while a {} is about (seen, heard or sensed lately): past its wait's own clock, till it's gone (WorkGoal.doWait)", digInAt == null ? "?" : digInAt.toShortString(), shooter); } return false; }
            if (ticks >= t.ticks()) { com.claudemod.ClaudeMod.LOGGER.info("[Claude life] his cover's wait is up ({} s, {} hostile(s) within 8, no shooter about): out (WorkGoal.doWait)", ticks / 20, hostilesNear(8).size()); return true; }
        }
        if (t.atPlayer() && g.getTarget() instanceof net.minecraft.world.entity.player.Player tp && com.claudemod.entity.life.Whereabouts.believed(g, tp) != null) g.getLookControl().setLookAt(com.claudemod.entity.life.Whereabouts.believed(g, tp).add(0, 1.6, 0)); // (where he believes they are: not through a hill at them)
        return ticks >= t.ticks();
    }

    /** A chest with a solid block on it won't open, for him too: his own chest gets that block broken out (and the space kept clear). True if queued. */
    private boolean unblockChest(BlockPos pos, Task retry) {
        ServerLevel level = (ServerLevel) g.level();
        if (!(level.getBlockState(pos).getBlock() instanceof net.minecraft.world.level.block.ChestBlock) || !net.minecraft.world.level.block.ChestBlock.isChestBlockedAt(level, pos)) return false;
        if (!g.myStations.contains(pos)) return true; // someone else's, blocked: leave it
        g.landingCells.add(pos.above().immutable()); // part of his mine now: never patched shut
        g.tasks.addFirst(retry); // then open it
        g.tasks.addFirst(new Task.Break(pos.above().immutable()));
        com.claudemod.ClaudeMod.LOGGER.info("[Claude life] his chest at {} is blocked from above: clearing it", pos.toShortString());
        return true;
    }

    private boolean doDeposit(Task.Deposit t) {
        ServerLevel level = (ServerLevel) g.level();
        if (!(level.getBlockEntity(t.pos()) instanceof Container) && com.claudemod.entity.life.ChestLedger.knows(g, t.pos())) { if (!inReach(t.pos())) return walkTo(t.pos(), 2) && !inReach(t.pos()); com.claudemod.entity.life.ChestLedger.forget(g, t.pos(), "is gone (he's at the spot)"); return true; } // on his ledger: found gone there
        if (!(level.getBlockEntity(t.pos()) instanceof Container box) || box instanceof net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity) { com.claudemod.ClaudeMod.LOGGER.info("[Claude life] {} is no chest (a furnace or nothing): not putting things in it", t.pos().toShortString()); return true; } // (storage on file where a chest never got placed: a furnace stood there, and he filled its slots like a chest)
        if (ticks == 1 && unblockChest(t.pos(), t)) return true;
        { Boolean reach = reachFor(t.pos()); if (reach != null) return reach; } /* (the user: using a furnace through a wall is a bug; a player opens what he can see) */ // a free spot beside it, not the furnace block itself (set in his stair wall, "done, going to take it out" 13 times over 15 minutes and never in reach)
        g.getNavigation().stop();
        g.getLookControl().setLookAt(Vec3.atCenterOf(t.pos()));
        Block block = level.getBlockState(t.pos()).getBlock();
        boolean chest = level.getBlockEntity(t.pos()) instanceof ChestBlockEntity;
        if (ticks == 1) {
            if (chest) level.blockEvent(t.pos(), block, 1, 1);
            level.playSound(null, t.pos(), SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 0.5f, 1.0f);
            return false;
        }
        if (ticks < 8) return false; // (speedrunner pace: shift-click the lot in well under a second)
        java.util.List<Container> storeTargets = new java.util.ArrayList<>();
        BlockState chestState = level.getBlockState(t.pos());
        if (chestState.getBlock() instanceof net.minecraft.world.level.block.ChestBlock cb) { // the chest he has open, both halves of a double chest: what a player's chest screen shows, nothing across the room
            Container both = net.minecraft.world.level.block.ChestBlock.getContainer(cb, chestState, level, t.pos(), true);
            storeTargets.add(both != null ? both : box);
        } else storeTargets.add(box);
        int moved = 0, cobbleKept = 0;
        java.util.List<Integer> slots = new java.util.ArrayList<>();
        boolean junkBox = com.claudemod.director.ClaudeLife.isJunkChest(g, t.pos()); java.util.Map<net.minecraft.world.item.Item, Integer> kept = new java.util.HashMap<>();
        for (int i = 0; i < g.inventory.items.getContainerSize(); i++) {
            ItemStack s = g.inventory.items.getItem(i);
            if (s.isEmpty()) continue; if (s.is(net.minecraft.tags.ItemTags.LOGS) && !junkBox) { if (kept.merge(net.minecraft.world.item.Items.OAK_LOG, s.getCount(), Integer::sum) > 64 + s.getCount() - 1 && kept.get(net.minecraft.world.item.Items.OAK_LOG) - s.getCount() >= 64) slots.add(i); continue; } if (keeps(s)) continue; // (logs: a stack of 64 stays (the user), , whole stacks past that go in the chest; the user: no pack full of blocks)
            boolean cob = s.is(net.minecraft.world.item.Items.COBBLESTONE) || s.is(net.minecraft.world.item.Items.COBBLED_DEEPSLATE);
            if (junkBox) { if (cob ? kept.merge(s.getItem(), 1, Integer::sum) > 2 : com.claudemod.director.ClaudeLife.junkItem(s)) slots.add(i); continue; } // his junk chest: the junk and the cobble past two stacks a kind (kept for patching)
            if (cob) continue; // for patching, and spare cobble never goes on the shelves: his junk chest takes what he can't carry (his chests filled up with it and "store" walked him 240 blocks to your chest)
            if (com.claudemod.director.ClaudeLife.junkItem(s)) continue; // (andesite and dirt on his loot shelves filled his stash)
            slots.add(i);
        }
        if (junkBox) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] junk chest at {}: {} stacks in, cobble stacks kept {}", t.pos().toShortString(), slots.size(), kept);
        // organised like a player's chest: valuables first, then ores, then stone and the rest, same things together
        slots.sort(java.util.Comparator.comparingInt((Integer i) -> sortGroup(g.inventory.items.getItem(i)))
                .thenComparing(i -> net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(g.inventory.items.getItem(i).getItem()).toString()));
        for (int i : slots) {
            ItemStack s = g.inventory.items.getItem(i);
            ItemStack rest = s.copy();
            for (Container c : storeTargets) { rest = addTo(c, rest); if (rest.isEmpty()) break; }
            moved += s.getCount() - rest.getCount();
            g.inventory.items.setItem(i, rest);
        }
        for (Container c : storeTargets) c.setChanged();
        if (g.storageChests.contains(t.pos())) { // more than this chest took: on to the next of his chests with room, like a player with a row of them
            boolean left = false; int cobbleLeft = 0;
            for (int i = 0; i < g.inventory.items.getContainerSize() && !left; i++) {
                ItemStack s = g.inventory.items.getItem(i);
                if (s.isEmpty() || keeps(s)) continue;
                if (s.is(net.minecraft.world.item.Items.COBBLESTONE) || s.is(net.minecraft.world.item.Items.COBBLED_DEEPSLATE)) continue; /* (cobble is never for his other chests, only the junk chest: a third stack of it sent him chest to chest, open-close, at 01:58) */ if (s.is(net.minecraft.tags.ItemTags.LOGS) || com.claudemod.director.ClaudeLife.junkItem(s)) continue; /* (left over means what his other chests take: never cobble, logs or junk; each one sent him chest to chest) */
                left = true; com.claudemod.ClaudeMod.LOGGER.info("[Claude chest] {} x{} still on him after {}: on to his next chest for it", net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).getPath(), s.getCount(), t.pos().toShortString());
            }
            if (left) {
                BlockPos partner = chestState.getBlock() instanceof net.minecraft.world.level.block.ChestBlock && chestState.getValue(net.minecraft.world.level.block.ChestBlock.TYPE) != net.minecraft.world.level.block.state.properties.ChestType.SINGLE
                        ? t.pos().relative(net.minecraft.world.level.block.ChestBlock.getConnectedDirection(chestState)) : null;
                java.util.List<BlockPos> rows = new java.util.ArrayList<>(g.storageChests);
                { final BlockPos from0 = t.pos(); rows.sort(java.util.Comparator.comparingInt((BlockPos q) -> com.claudemod.director.ClaudeLife.routeDist(g, from0, q)).thenComparingInt(q -> q.getY()).thenComparingInt(q -> q.getZ()).thenComparingInt(q -> q.getX())); } // the next nearest by his tunnels (a nook per level: never the other level's first)
                for (BlockPos p : rows) {
                    if (p.equals(t.pos()) || p.equals(partner) || !(level.getBlockEntity(p) instanceof Container c) || c instanceof net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity) continue;
                    boolean room = false; for (int i = 0; i < c.getContainerSize() && !room; i++) room = c.getItem(i).isEmpty();
                    if (!room) continue;
                    g.tasks.addFirst(new Task.Deposit(p.immutable()));
                    g.tasks.addFirst(new Task.GoTo(p.immutable(), 2));
                    break;
                }
            }
        }
        com.claudemod.entity.life.ChestLedger.opened(g, level, t.pos()); // he had it open: his ledger is what's in it now
        if (chest) level.blockEvent(t.pos(), block, 1, 0);
        level.playSound(null, t.pos(), SoundEvents.CHEST_CLOSE, SoundSource.BLOCKS, 0.5f, 1.0f);
        { java.util.Map<String, Integer> kept0 = new java.util.TreeMap<>(); for (int i = 0; i < g.inventory.items.getContainerSize(); i++) { ItemStack k0 = g.inventory.items.getItem(i); if (!k0.isEmpty()) kept0.merge(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(k0.getItem()).getPath(), k0.getCount(), Integer::sum); } com.claudemod.ClaudeMod.LOGGER.info("[Claude chest] at {} ({}): stored {}, still on him {}", t.pos().toShortString(), junkBox ? "junk chest" : "chest", moved, kept0); } // every visit: what went in and what stayed
        if (moved > 0) g.noteDoing("stored " + moved + " items in your chest");
        return true;
    }

    /** Tools, weapons, armor, food, torches, fuel and what he crafts with stay on him (diamonds and iron too: the pick needs them). */
    public static boolean keeps(ItemStack s) {
        return s.isDamageableItem() || s.getItem() instanceof net.minecraft.world.item.BucketItem || s.get(net.minecraft.core.component.DataComponents.FOOD) != null
                || s.is(net.minecraft.world.item.Items.TORCH) || s.is(net.minecraft.world.item.Items.CRAFTING_TABLE) || s.is(net.minecraft.world.item.Items.FURNACE)
                || s.is(net.minecraft.world.item.Items.COAL) || s.is(net.minecraft.world.item.Items.CHARCOAL) || s.is(net.minecraft.world.item.Items.STICK)
                || s.is(net.minecraft.tags.ItemTags.PLANKS) || s.is(net.minecraft.tags.ItemTags.LOGS)
                || s.is(net.minecraft.world.item.Items.ARROW) || s.is(net.minecraft.world.item.Items.ENDER_PEARL) || s.is(net.minecraft.world.item.Items.ENDER_EYE); // ammo and the dragon run's pearls/eyes ride with him like torches (live 01:16 World 15: took 32 arrows, the stash put all 32 back) (diamonds, iron and raw iron go IN the chest: the user's rule, valuables stay safe there and come back out for a craft)
    }

    /** Chest order: 0 valuables, 1 ores and raw metal, 2 stone and dirt, 3 everything else. */
    private static int sortGroup(ItemStack s) {
        String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
        if (id.contains("diamond") || id.contains("emerald") || id.contains("gold") || id.contains("lapis") || id.contains("redstone")) return 0;
        if (id.startsWith("raw_") || id.endsWith("_ore") || id.endsWith("_ingot") || id.contains("coal") || id.contains("copper")) return 1;
        if (s.getItem() instanceof net.minecraft.world.item.BlockItem) return 2;
        return 3;
    }

    private static ItemStack addTo(Container box, ItemStack stack) {
        for (int pass = 0; pass < 2; pass++) // onto the same thing first, then a free slot
            for (int i = 0; i < box.getContainerSize() && !stack.isEmpty(); i++) {
                ItemStack in = box.getItem(i);
                if (pass == 1 && in.isEmpty()) { box.setItem(i, stack.copy()); stack.setCount(0); }
                else if (pass == 0 && !in.isEmpty() && ItemStack.isSameItemSameComponents(in, stack) && in.getCount() < in.getMaxStackSize()) {
                    int n = Math.min(stack.getCount(), in.getMaxStackSize() - in.getCount());
                    in.grow(n); stack.shrink(n);
                }
            }
        return stack;
    }

    private int smeltPhase;

    private boolean doSmelt(Task.Smelt t) {
        ServerLevel level = (ServerLevel) g.level();
        if (!(level.getBlockEntity(t.pos()) instanceof net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity furnace)) return true;
        if (ticks == 1) smeltPhase = 0;
        { Boolean reach = reachFor(t.pos()); if (reach != null) return reach; } /* (the user: using a furnace through a wall is a bug; a player opens what he can see) */ // a free spot beside it, not the furnace block itself (set in his stair wall, "done, going to take it out" 13 times over 15 minutes and never in reach)
        g.getNavigation().stop();
        g.getLookControl().setLookAt(Vec3.atCenterOf(t.pos()));
        if (smeltPhase == 0) {
            // load input (slot 0) and fuel (slot 1)
            int n = g.inventory.remove(s -> s.is(t.input()), t.count());
            if (n == 0) return true;
            ItemStack in = furnace.getItem(0);
            n = Math.min(n, in.isEmpty() ? 64 : 64 - in.getCount()); // a slot holds a stack
            if (n <= 0) return true;
            if (in.isEmpty()) furnace.setItem(0, new ItemStack(t.input(), n)); else if (in.is(t.input())) in.grow(n); else { g.inventory.add(new ItemStack(t.input(), n)); return true; }
            if (furnace.getItem(1).isEmpty()) {
                ItemStack fuel = g.inventory.find(s -> s.is(net.minecraft.world.item.Items.COAL) || s.is(net.minecraft.world.item.Items.CHARCOAL));
                int fuelCount = Math.max(1, (n + 7) / 8);
                if (fuel.isEmpty()) { fuel = g.inventory.find(s -> s.is(net.minecraft.tags.ItemTags.LOGS) || s.is(net.minecraft.tags.ItemTags.PLANKS)); fuelCount = Math.max(1, (n * 2 + 2) / 3); }
                if (!fuel.isEmpty()) furnace.setItem(1, fuel.split(Math.min(fuelCount, fuel.getCount())));
            }
            furnace.setChanged();
            g.swing(InteractionHand.MAIN_HAND);
            smeltPhase = 1;
            return false;
        }
        // wait for it to cook, then take everything out of the result slot
        boolean burntOut = ticks > 20 && furnace.getItem(1).isEmpty() && !level.getBlockState(t.pos()).getValue(net.minecraft.world.level.block.AbstractFurnaceBlock.LIT); // out of fuel with raw still in: no waiting on a cold furnace (45 s stood at one till the stuck timer)
        boolean doneCooking = furnace.getItem(0).isEmpty() || burntOut || ticks > 20 * 12 * t.count();
        if (!doneCooking && ticks % 40 != 0) return false;
        if (!doneCooking) return false;
        if (burntOut && !furnace.getItem(0).isEmpty()) { ItemStack raw = furnace.removeItem(0, 64); com.claudemod.ClaudeMod.LOGGER.info("[Claude life] furnace at {} went cold: took back {} {}", t.pos().toShortString(), raw.getCount(), itemName(raw)); g.inventory.add(raw); }
        ItemStack out = furnace.removeItem(2, 64);
        if (!out.isEmpty()) furnace.awardUsedRecipesAndPopExperience(g); // the XP a player gets for taking the output
        if (!out.isEmpty()) {
            g.noteDoing("smelted " + out.getCount() + " " + itemName(out));
            g.inventory.add(out);
        }
        g.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    private Task.FurnaceLoad loadTask; private int loadAt; // the furnace he's at and the tick he got there (the GUI time counts from there)
    /** Load input and enough fuel, then move on; the furnace cooks on its own. */
    private boolean doFurnaceLoad(Task.FurnaceLoad t) {
        ServerLevel level = (ServerLevel) g.level();
        if (!(level.getBlockEntity(t.pos()) instanceof net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity furnace)) return true;
        { Boolean reach = reachFor(t.pos()); if (reach != null) return reach; } /* (the user: using a furnace through a wall is a bug; a player opens what he can see) */ // a free spot beside it, not the furnace block itself (set in his stair wall, "done, going to take it out" 13 times over 15 minutes and never in reach)
        g.getNavigation().stop();
        g.getLookControl().setLookAt(Vec3.atCenterOf(t.pos()));
        if (loadTask != t) { loadTask = t; loadAt = ticks; g.swing(InteractionHand.MAIN_HAND); } // at the furnace: open it,
        if (ticks - loadAt < 5) return false; // shift-click the input and the fuel in, close it: five ticks, a speedrunner's pace (it was a second a furnace, counted from the walk's start) // open it, put the stuff and the fuel in, close it: about a second a furnace, like a player (seven furnaces were filled in one third of a second)
        ItemStack in = furnace.getItem(0);
        if (!in.isEmpty() && !in.is(t.input())) return true; // busy with something else
        // keep back enough fuel: with no coal, wood burns 1.5 items each, and if wood is also what's cooking (charcoal)
        // it has to be split between the input and the fuel slot, or nothing cooks
        int want = Math.min(t.count(), 64 - in.getCount());
        int coal = g.inventory.count(s -> s.is(net.minecraft.world.item.Items.COAL) || s.is(net.minecraft.world.item.Items.CHARCOAL));
        int fuelHeld = furnace.getItem(1).getCount();
        boolean inputWood = new ItemStack(t.input()).is(net.minecraft.tags.ItemTags.LOGS);
        if (coal == 0 && fuelHeld == 0 && !inputWood && g.inventory.count(s -> s.is(net.minecraft.tags.ItemTags.PLANKS)) * 3 / 2 < want) {
            // no coal: logs into planks first (a log burns 1.5 items, its 4 planks 6)
            ItemStack log = g.inventory.find(s -> s.is(net.minecraft.tags.ItemTags.LOGS));
            if (!log.isEmpty()) {
                String planks = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(log.getItem()).getPath().replace("_log", "_planks").replace("stripped_", "").replace("_wood", "_planks");
                var pi = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.withDefaultNamespace(planks));
                if (pi != net.minecraft.world.item.Items.AIR) com.claudemod.entity.life.Crafting.craft(level, g.inventory, pi, Math.max(1, (want + 5) / 6), false);
            }
        }
        if (coal == 0 && fuelHeld == 0) {
            int wood = g.inventory.count(s -> s.is(net.minecraft.tags.ItemTags.LOGS) || s.is(net.minecraft.tags.ItemTags.PLANKS));
            boolean inputIsWood = new ItemStack(t.input()).is(net.minecraft.tags.ItemTags.LOGS) || new ItemStack(t.input()).is(net.minecraft.tags.ItemTags.PLANKS);
            want = Math.min(want, inputIsWood ? (wood * 3) / 5 : (wood * 3) / 2);
            if (want <= 0) { com.claudemod.ClaudeMod.LOGGER.info("[Claude life] not enough wood to both cook and fuel {}: skipping", itemName(new ItemStack(t.input()))); return true; }
        }
        int n = g.inventory.remove(s -> s.is(t.input()), want);
        g.myStations.add(t.pos().immutable()); // a furnace he's using: he knows where it is, and when it'll be done
        g.furnaceReadyAt.put(t.pos().immutable(), g.tickCount + n * 200 + 40);
        if (n == 0) return true;
        n = Math.min(n, in.isEmpty() ? 64 : 64 - in.getCount()); // a slot holds a stack
        if (n <= 0) return true;
        if (in.isEmpty()) furnace.setItem(0, new ItemStack(t.input(), n)); else in.grow(n);
        // fuel for everything in it: coal does 8, a log/plank 1.5
        int total = furnace.getItem(0).getCount();
        ItemStack fuelSlot = furnace.getItem(1);
        if (fuelSlot.isEmpty() && total <= 1) { // (a wooden tool burns 10 s, one item: for 6 cod it took the fuel slot and the rest never cooked, live 07:37) // the user: a wooden tool he's outgrown (stone or better of the same kind on him) goes in as fuel, like a player ditching it, not carried around for ever
            for (int i = 0; i < g.inventory.items.getContainerSize() && fuelSlot.isEmpty(); i++) {
                ItemStack w = g.inventory.items.getItem(i);
                if (w.isEmpty() || !(w.is(net.minecraft.world.item.Items.WOODEN_PICKAXE) || w.is(net.minecraft.world.item.Items.WOODEN_SWORD) || w.is(net.minecraft.world.item.Items.WOODEN_AXE) || w.is(net.minecraft.world.item.Items.WOODEN_SHOVEL) || w.is(net.minecraft.world.item.Items.WOODEN_HOE))) continue;
                Class<?> kind = w.getItem().getClass(); boolean outgrown = false;
                for (int j = 0; j < g.inventory.items.getContainerSize(); j++) { ItemStack o = g.inventory.items.getItem(j); if (j != i && !o.isEmpty() && o.getItem().getClass() == kind && !o.getItem().getDescriptionId().contains("wooden") && !o.getItem().getDescriptionId().contains("golden")) { outgrown = true; break; } }
                if (!outgrown) continue;
                furnace.setItem(1, w.split(1));
                fuelSlot = furnace.getItem(1);
                com.claudemod.ClaudeMod.LOGGER.info("[Claude life] his {} is outgrown: burning it as fuel", itemName(fuelSlot));
            }
        }
        ItemStack fuel = g.inventory.find(s -> s.is(net.minecraft.world.item.Items.COAL) || s.is(net.minecraft.world.item.Items.CHARCOAL));
        int need = (total + 7) / 8;
        if (total <= 4 && fuelSlot.isEmpty()) { // a short batch (the load split over a row of furnaces): wood, a coal's eight would mostly burn away empty
            ItemStack planks = g.inventory.find(s -> s.is(net.minecraft.tags.ItemTags.PLANKS));
            if (!planks.isEmpty() && planks.getCount() * 3 / 2 >= total) { fuel = planks; need = (total * 2 + 2) / 3; }
        }
        if (fuel.isEmpty()) { fuel = g.inventory.find(s -> s.is(net.minecraft.tags.ItemTags.PLANKS)); need = (total * 2 + 2) / 3; } // planks before whole logs
        if (fuel.isEmpty() || (fuel.is(net.minecraft.tags.ItemTags.PLANKS) && fuel.getCount() < need - fuelSlot.getCount() && fuelSlot.isEmpty())) { // (3 planks for 7 iron burnt out halfway, the rest raw in there for good: logs when the planks won't cover it)
            ItemStack logs = g.inventory.find(s -> s.is(net.minecraft.tags.ItemTags.LOGS));
            if (!logs.isEmpty() || fuel.isEmpty()) { fuel = logs; need = (total * 2 + 2) / 3; }
        }
        if (!fuel.isEmpty() && (fuelSlot.isEmpty() || ItemStack.isSameItemSameComponents(fuelSlot, fuel))) {
            int add = Math.max(0, Math.min(need - fuelSlot.getCount(), fuel.getCount()));
            if (add > 0) {
                if (fuelSlot.isEmpty()) furnace.setItem(1, fuel.split(add)); else { fuelSlot.grow(add); fuel.shrink(add); }
            }
        }
        furnace.setChanged();
        g.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    /** Wait at a loaded furnace until it has cooked everything, then take the results. */
    /**
     * Into reach of a block he works with (a furnace, a chest, a door), seeing it: a walk to a spot beside it, then a nudge the
     * last block. null once he's there; false while on the way; true when he can't get there (said once).
     */
    private Boolean reachFor(BlockPos target) {
        ServerLevel level = (ServerLevel) g.level();
        if (inReach(target) && blockInTheWay(level, target) == null) return null;
        BlockPos side = clearViewSpot(level, target); if (side == null) side = standSpotBeside(target);
        BlockPos feet = BlockPos.containing(g.getX(), g.getY() + 0.2, g.getZ());
        if (side.distSqr(feet) <= 4 && side.getY() == feet.getY()) { // (level only: a nudge can't take a step up, 10 s stood still under his furnace one up the slope, live 11:31) // the spot beside him (or under him, off its centre): a nudge, a walk there "arrives" a block short
            if (ticks > 200) { com.claudemod.ClaudeMod.LOGGER.info("[Claude life] couldn't get in reach of {} at {} (he's at {})", name(level.getBlockState(target)), target.toShortString(), feet.toShortString()); g.attempts.fail(com.claudemod.entity.life.Attempts.Kind.REACH, target, g.tickCount); return true; }
            g.getNavigation().stop(); g.getMoveControl().setWantedPosition(side.getX() + 0.5, side.getY(), side.getZ() + 0.5, 0.5); return false;
        }
        boolean ended = walkTo(side, side.equals(target) ? 2 : 0.6);
        if (ended && BlockPos.containing(g.getX(), g.getY() + 0.2, g.getZ()).distSqr(side) > 4) { com.claudemod.ClaudeMod.LOGGER.info("[Claude life] couldn't get in reach of {} at {} (he's at {})", name(level.getBlockState(target)), target.toShortString(), feet.toShortString()); return true; }
        return false;
    }

    private boolean doFurnaceTake(Task.FurnaceTake t) {
        ServerLevel level = (ServerLevel) g.level();
        if (!(level.getBlockEntity(t.pos()) instanceof net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity furnace)) return true;
        { Boolean reach = reachFor(t.pos()); if (reach != null) return reach; } /* (the user: using a furnace through a wall is a bug; a player opens what he can see) */ // a free spot beside it, not the furnace block itself (set in his stair wall, "done, going to take it out" 13 times over 15 minutes and never in reach)
        g.getNavigation().stop();
        g.getLookControl().setLookAt(Vec3.atCenterOf(t.pos()));
        if (!furnace.getItem(0).isEmpty() && level.getRecipeManager().getRecipeFor(net.minecraft.world.item.crafting.RecipeType.SMELTING, new net.minecraft.world.item.crafting.SingleRecipeInput(furnace.getItem(0)), level).isEmpty()) { ItemStack odd = furnace.removeItem(0, 64); g.inventory.add(odd); com.claudemod.ClaudeMod.LOGGER.info("[Claude life] {} {} in the furnace at {} never smelts: took it back out", odd.getCount(), itemName(odd), t.pos().toShortString()); } // something that never cooks (diamonds stored in it like a chest): out it comes, no waiting on it (he'd wait out the 4-minute cap)
        if (!furnace.getItem(1).isEmpty() && !net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity.isFuel(furnace.getItem(1))) { ItemStack odd = furnace.removeItem(1, 64); g.inventory.add(odd); } // (nor what doesn't burn in the fuel slot)
        boolean cooking = !furnace.getItem(0).isEmpty() && ticks < 20 * 60 * 4; // ponytail: 4 min cap per furnace (a full stack takes ~10 min)
        if (cooking && furnace.getItem(1).isEmpty() && !level.getBlockState(t.pos()).getValue(net.minecraft.world.level.block.AbstractFurnaceBlock.LIT)) cooking = false; // out of fuel: take what's done
        if (cooking) return false; // the whole batch, then go
        ItemStack out = furnace.removeItem(2, 64);
        if (!out.isEmpty()) furnace.awardUsedRecipesAndPopExperience(g); // the XP a player gets for taking the output
        if (!out.isEmpty()) {
            g.noteDoing("smelted " + out.getCount() + " " + itemName(out));
            g.inventory.add(out);
        }
        if (!furnace.getItem(0).isEmpty() && furnace.getItem(1).isEmpty() && !level.getBlockState(t.pos()).getValue(net.minecraft.world.level.block.AbstractFurnaceBlock.LIT)) { // burnt out with raw left: that comes back out too, to be smelted again with fuel (left in, it was lost to him)
            ItemStack raw = furnace.removeItem(0, 64);
            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] furnace at {} ran out of fuel: took back {} {}", t.pos().toShortString(), raw.getCount(), itemName(raw));
            g.inventory.add(raw);
        }
        g.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    private java.util.List<net.minecraft.world.entity.Mob> hostilesNear(double r) {
        return com.claudemod.entity.life.Perception.known(g, net.minecraft.world.entity.Mob.class, g.getBoundingBox().inflate(r),
                m -> m.isAlive() && m instanceof net.minecraft.world.entity.monster.Enemy && (m.getTarget() == g || m.distanceTo(g) < 6)); // (what he sees, hears or feels)
    }

    /** Hostile mobs about (any kind, after him or not). */
    private java.util.List<net.minecraft.world.entity.Mob> enemiesNear(double r) {
        return com.claudemod.entity.life.Perception.known(g, net.minecraft.world.entity.Mob.class, g.getBoundingBox().inflate(r), m -> m.isAlive() && m instanceof net.minecraft.world.entity.monster.Enemy
                && !(m instanceof net.minecraft.world.entity.NeutralMob nm && !nm.isAngryAt(g)) // (zombified piglins, endermen: no enemy till angry at him; 16:33 two calm ones were "a crowd" he wouldn't walk past, 20 s stuck)
                && !(m instanceof net.minecraft.world.entity.monster.piglin.Piglin pg && pg.getTarget() != g));
    }

    /** In reach with a charged swing: a hit lands right now (and knocks it back). */
    private boolean canHitNow(net.minecraft.world.entity.Entity e) {
        return armed() && g.getAttackStrengthScale(0.5f) >= 0.9f && e.getBoundingBox().distanceToSqr(g.getEyePosition()) <= 9.0 && g.hasLineOfSight(e);
    }

    private boolean armed() {
        return g.inventory.find(s -> s.getItem() instanceof net.minecraft.world.item.SwordItem || s.getItem() instanceof net.minecraft.world.item.AxeItem).isEmpty() == false;
    }

    /** Nothing much to fight with or in: a wooden weapon at best, or no armor on at all. Two mobs are a crowd for him then (a fresh respawn at night died twice "armed" with a wooden sword). */
    private boolean weakKit() {
        boolean proper = !g.inventory.find(s -> (s.getItem() instanceof net.minecraft.world.item.SwordItem && !s.is(net.minecraft.world.item.Items.WOODEN_SWORD)) || (s.getItem() instanceof net.minecraft.world.item.AxeItem && !s.is(net.minecraft.world.item.Items.WOODEN_AXE))).isEmpty();
        boolean armor = false;
        for (EquipmentSlot sl : EquipmentSlot.values()) if (sl.isArmor() && !g.getItemBySlot(sl).isEmpty()) armor = true;
        return !proper || !armor;
    }

    /** Nothing proper to swing: a wooden weapon or none. (No armor alone doesn't make two zombies a reason to run: with an iron sword that run, in a cave, killed him.) */
    private boolean weakFighter() {
        return g.inventory.find(s -> (s.getItem() instanceof net.minecraft.world.item.SwordItem && !s.is(net.minecraft.world.item.Items.WOODEN_SWORD)) || (s.getItem() instanceof net.minecraft.world.item.AxeItem && !s.is(net.minecraft.world.item.Items.WOODEN_AXE))).isEmpty();
    }

    /** Fight or run: hurt, unarmed against a crowd, or a creeper about to blow means run. */
    private net.minecraft.world.phys.Vec3 fleeFrom;
    private int cornerUntil;

    private boolean shouldFlee(net.minecraft.world.entity.Entity foe) {
        if (foe instanceof net.minecraft.world.entity.LivingEntity l0 && outOfItsReach(l0)) return false; // it can't get him up there: no reason to run (an iron golem under his 3-high tower read as a lost fight)
        if (com.claudemod.entity.life.FortressFight.coverMob(foe) && g.getHealth() > 6 && foe instanceof net.minecraft.world.entity.LivingEntity lf && com.claudemod.entity.life.FortressFight.coverFor(g, (ServerLevel) g.level(), lf) != null) { fightOdds = "from cover"; return false; } // a blaze or wither skeleton with cover at hand: the cover fight backs into it itself (running in the open from fireballs is how it goes wrong)
        if (g.tickCount < cornerUntil && !(foe instanceof net.minecraft.world.entity.monster.Creeper)) return false; // cornered a moment ago: fight, no more running into the wall
        if (foe instanceof net.minecraft.world.entity.monster.Creeper c && c.distanceTo(g) < 5 && ((c.getSwellDir() > 0 && !canHitNow(c)) || !armed() || g.getHealth() <= 10)) return true; // hissing and he can't hit it right now (or can't fight it): run
        int n = hostilesNear(10).size();
        // three or more on him is a crowd whatever he's holding: get out of their reach (tower or dig in) and fight from there
        boolean underground = underground((ServerLevel) g.level(), g.blockPosition()); // (rock over him: a tree's leaves are the open)
        boolean armored = false; for (EquipmentSlot sl : EquipmentSlot.values()) if (sl.isArmor() && !g.getItemBySlot(sl).isEmpty()) armored = true;
        if (underground && !weakFighter() && g.getHealth() > 8 && n <= (armored ? 2 : 1) && !(foe instanceof net.minecraft.world.entity.monster.Creeper)) return false; // down a cave with a real sword: running in the dark is how you die; one zombie gets fought bare, two only in armor (two bare took 11 health in 3 s)
        if (foe instanceof net.minecraft.world.entity.monster.Witch w) { int toKill = hitsToKill(w); fightOdds = "kill it in " + toKill + ", its potions kill him in about " + (int) Math.ceil(g.getHealth() / 6.0); return !(toKill <= 3 || (armored && !weakFighter() && g.getHealth() > 12)); } // a witch: rushed and killed when that's quick (close in it throws slow and heals only between throws), else out of its sight; "no armor: run" ran from one a hit from dead, in its sight for 20 s // potions from range, over walls, by day too: no armor or no real sword, get out of its range (meleeing one with a wooden axe at 12 health killed him)
        if ((g.getHealth() <= 10 && n >= 2) || ((!armed() || weakFighter()) && n >= 2) || n >= (prepared() ? 4 : 3)) { fightOdds = n + " on him" + (prepared() ? " (prepared: a crowd is 4+)" : ""); return true; } // (the combat rule: a crowd is left alone unless he's prepared, iron and food: in iron with a diamond sword and six meals he ran from two skeletons and a zombie at 17 health, live 04:42)
        // One on one: size it up like a player. Winning the race: see it through, even at low health (running from a mob as fast as
        // you with it two hits from dead is how you die). Losing it: leave now, while there's health to run with, not at a heart and a half.
        if (foe instanceof net.minecraft.world.entity.LivingEntity l && !(foe instanceof net.minecraft.world.entity.monster.Creeper)) {
            int toKill = hitsToKill(l), toDie = hitsToDie(l);
            fightOdds = "kill it in " + toKill + ", dead in " + toDie;
            if (l instanceof net.minecraft.world.entity.monster.AbstractSkeleton) { int closing = (int) Math.ceil(l.distanceTo(g) / 6.0); return g.getHealth() <= 6 && toDie <= toKill + closing; } // a shooter: closing in is the plan; at low health only if the numbers say he'd lose (it shoots ~once per 6 blocks he sprints). "kill it in 3, dead in 6" in iron backed off, hid in four spots in its line of fire and died
            boolean losing = toDie * 1.6 < toKill; // ~1.6 swings of his per hit it lands (sword 0.6s, a zombie ~1s)
            if (!losing) return false;
            return g.getHealth() > 6 || toKill > 2; // losing: run while he can; at the end, only a mob two hits from dead is worth finishing
        }
        return g.getHealth() <= 6;
    }

    private String fightOdds = ""; // the last sizing-up, for the log

    private java.util.UUID golemRetryFor; private int golemRetries; // re-towering beside a golem that strolled off
    private int reachWaitStart = -1, reachWaitTicks;
    private boolean lureShot; // a shot from his tower to draw it in // since when he's been up his tower waiting for it to come in reach (this attack)

    /** A ground mob that can't reach him where he stands: his feet over the top of it (its swing reaches out, never up), and it can't climb, shoot, fly or blow up. */
    private boolean outOfItsReach(net.minecraft.world.entity.LivingEntity m) {
        if (m instanceof net.minecraft.world.entity.monster.Spider || m instanceof net.minecraft.world.entity.monster.Creeper || m instanceof net.minecraft.world.entity.monster.RangedAttackMob
                || m instanceof net.minecraft.world.entity.FlyingMob || m.isNoGravity()) return false;
        if (!(m instanceof net.minecraft.world.entity.animal.IronGolem || m instanceof net.minecraft.world.entity.monster.Monster)) return false;
        return g.getY() >= m.getBoundingBox().maxY + 0.05; // (in the air settling onto his tower top counts: the fight starts as the last block goes in)
    }

    /** Swings to kill it with the best weapon he carries (the one he'll be holding once the fight starts). */
    private int hitsToKill(net.minecraft.world.entity.LivingEntity mob) {
        double hit = 1.0; // bare hands
        for (int i = 0; i < g.inventory.items.getContainerSize(); i++) {
            ItemStack s = g.inventory.items.getItem(i);
            if (s.getItem() instanceof net.minecraft.world.item.SwordItem || s.getItem() instanceof net.minecraft.world.item.AxeItem)
                hit = Math.max(hit, weaponDamage(s));
        }
        return (int) Math.ceil(mob.getHealth() / hit);
    }

    /** What a swing with this does: the attack-damage modifiers only (compute() would lump attack speed in and call a stone sword a 2.6). */
    public static double weaponDamage(ItemStack s) {
        double[] dmg = {1.0};
        s.getAttributeModifiers().forEach(EquipmentSlot.MAINHAND, (attr, mod) -> {
            if (attr.is(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE) && mod.operation() == net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE) dmg[0] += mod.amount();
        });
        return dmg[0];
    }

    /** Hits it needs to land on him, with his armor counted. */
    private int hitsToDie(net.minecraft.world.entity.LivingEntity mob) {
        double raw = mob.getAttributes().hasAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE)
                ? mob.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE) : 3.0;
        double after = Math.max(1.0, raw * (1.0 - Math.min(20, g.getArmorValue()) / 25.0)); // (vanilla armor: up to 80% off)
        return (int) Math.ceil(g.getHealth() / after);
    }

    private Vec3 fleeDir; // the way he's running: picked once, kept till it's blocked
    private final java.util.List<Vec3> fleeDead = new java.util.ArrayList<>(); private double fleeLastD = -1; private final java.util.Set<java.util.UUID> fleeFromIds = new java.util.HashSet<>(); private java.util.UUID fleeSeed; // headings that didn't gain him distance this run

    /** The way to run: away from them, with footing (no drop over 3), clear ahead, best where terrain hides him from them by 8 out. */
    private Vec3 fleeHeading(java.util.List<? extends net.minecraft.world.entity.Mob> mobs) {
        Vec3 away = Vec3.ZERO; for (var m : mobs) away = away.add(g.position().subtract(m.position()).normalize());
        Vec3 them = g.getTarget() instanceof net.minecraft.world.entity.player.Player tp ? com.claudemod.entity.life.Whereabouts.believed(g, tp) : null; // (where he believes they are)
        if (them != null && !g.getTarget().isSpectator()) { Vec3 toPlayer = them.subtract(g.position()); if (toPlayer.length() > 3 && toPlayer.length() < 64 && toPlayer.normalize().dot(away.normalize()) > 0.3) away = away.add(toPlayer.normalize()); } // (the player only when they're that way anyway)
        away = new Vec3(away.x, 0, away.z); if (away.lengthSqr() < 1e-4) away = new Vec3(1, 0, 0); away = away.normalize();
        Vec3 best = away; double bestScore = -1e9; ServerLevel lv = (ServerLevel) level();
        for (int k = 0; k < 16; k++) {
            double ang = k * Math.PI / 8; Vec3 d = new Vec3(Math.cos(ang), 0, Math.sin(ang));
            double align = d.dot(away); if (align < -0.1) continue; // (never back toward them)
            boolean ok = true;
            for (int s0 = 2; s0 <= 6 && ok; s0 += 2) { BlockPos c = BlockPos.containing(g.position().add(d.scale(s0))); int drop = 0; while (drop <= 4 && lv.getBlockState(c.below(drop + 1)).getCollisionShape(lv, c.below(drop + 1)).isEmpty() && lv.getFluidState(c.below(drop + 1)).isEmpty()) drop++; if (drop > 3 || lv.getFluidState(c).is(net.minecraft.tags.FluidTags.LAVA)) ok = false; }
            if (!ok) continue;
            BlockPos h1 = BlockPos.containing(g.position().add(d.scale(1.5))).above(); boolean wall = !lv.getBlockState(h1).getCollisionShape(lv, h1).isEmpty() && !lv.getBlockState(h1.above()).getCollisionShape(lv, h1.above()).isEmpty();
            Vec3 there = g.getEyePosition().add(d.scale(8)); boolean hidden = !mobs.isEmpty() && mobs.stream().allMatch(m -> !com.claudemod.entity.life.Perception.clear(lv, m.getEyePosition(), there));
            final Vec3 dd = d; double score = align * 2 + (hidden ? 1.5 : 0) - (wall ? 2 : 0) - (fleeDead.stream().anyMatch(x -> x.dot(dd) > 0.85) ? 5 : 0); // (one that didn't gain him distance: not again this run)
            if (score > bestScore) { bestScore = score; best = d; }
        }
        return best;
    }
    /** The way he's running no longer takes him away: walled (bumping, not moving), a drop ahead, or one of them now ahead of him. */
    private boolean fleeBlocked(java.util.List<? extends net.minecraft.world.entity.Mob> mobs) {
        if (fleeDir == null) return true;
        if (g.horizontalCollision && g.getDeltaMovement().horizontalDistanceSqr() < 0.005) return true;
        BlockPos c = BlockPos.containing(g.position().add(fleeDir.scale(2))); int drop = 0; while (drop <= 4 && level().getBlockState(c.below(drop + 1)).getCollisionShape(level(), c.below(drop + 1)).isEmpty() && level().getFluidState(c.below(drop + 1)).isEmpty()) drop++;
        if (drop > 3) return true;
        return mobs.stream().anyMatch(m -> m.distanceTo(g) < 6 && m.position().subtract(g.position()).normalize().dot(fleeDir) > 0.5);
    }

    // the escape he's committed to (lazy: fields added live start null/0)
    private BlockPos escapeAt, escapeGap; private String escapeKind; private int escapeSince = -100000, escapeBestTick; private double escapeBest; private boolean escapeHeld, escapePlugged; private int escapeSwings;
    public int escapePicks; // (test) escapes picked so far
    public String escapeKind() { return escapeKind; }
    public boolean escapeHeldNow() { return escapeHeld; }
    private void clearEscape() { escapeAt = null; escapeGap = null; escapeKind = null; escapeHeld = false; escapePlugged = false; }
    private boolean standable(ServerLevel lv, BlockPos c) {
        return lv.getBlockState(c).getCollisionShape(lv, c).isEmpty() && lv.getBlockState(c.above()).getCollisionShape(lv, c.above()).isEmpty() && lv.getFluidState(c).isEmpty() && lv.getFluidState(c.above()).isEmpty()
                && lv.getBlockState(c.below()).isFaceSturdy(lv, c.below(), Direction.UP) && !lv.getFluidState(c.below()).is(net.minecraft.tags.FluidTags.LAVA) && !lv.getBlockState(c.below()).is(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK);
    }
    private boolean solidAt(ServerLevel lv, BlockPos c) { return !lv.getBlockState(c).getCollisionShape(lv, c).isEmpty(); }
    /** Beside that standing cell, a drop of 5+ or lava within 5 under the edge: knockback there is death (16:58 wither skeletons knocked him off a fortress bridge into the lava sea). Null if none. */
    static String edgeDanger(ServerLevel lv, BlockPos c) {
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dz == 0) continue;
            BlockPos q = c.offset(dx, 0, dz);
            if (!lv.getBlockState(q).getCollisionShape(lv, q).isEmpty()) continue; // a wall there: his back to it is safe
            int k = 0; BlockPos f = q.below();
            while (k < 6 && lv.getBlockState(f).getCollisionShape(lv, f).isEmpty() && !lv.getFluidState(f).is(net.minecraft.tags.FluidTags.LAVA)) { f = f.below(); k++; }
            if (lv.getFluidState(f).is(net.minecraft.tags.FluidTags.LAVA)) return "lava " + (k + 1) + " under the edge at " + q.toShortString();
            if (k >= 5) return "a drop of " + k + "+ at " + q.toShortString();
        }
        return null;
    }
    /** A 1-wide gap at c (feet and head solid both sides across it, open and floored front and back): the way through it, or null. */
    private Direction gapAxis(ServerLevel lv, BlockPos c) {
        if (!standable(lv, c)) return null;
        for (Direction d : new Direction[] {Direction.NORTH, Direction.EAST}) {
            Direction s = d.getClockWise();
            if (solidAt(lv, c.relative(s)) && solidAt(lv, c.relative(s).above()) && solidAt(lv, c.relative(s.getOpposite())) && solidAt(lv, c.relative(s.getOpposite()).above())
                    && standable(lv, c.relative(d)) && standable(lv, c.relative(d.getOpposite()))) return d;
        }
        return null;
    }
    /**
     * The way out, picked once: a portal in sight he reaches with its 4 s to spare; a 1-wide gap in sight he gets to first (through
     * it, plugged or held, one at a time); else a run 16 out on the best heading. Logged with what it saw.
     */
    private void pickEscape(java.util.List<? extends net.minecraft.world.entity.Mob> mobs) {
        ServerLevel lv = level(); BlockPos feet = BlockPos.containing(g.getX(), g.getY() + 0.2, g.getZ()); Vec3 eye = g.getEyePosition(); int now = g.tickCount;
        String from = mobs.stream().map(m -> m.getType().getDescription().getString() + " " + (int) m.distanceTo(g)).toList().toString();
        Vec3 crowd = Vec3.ZERO; for (var m : mobs) crowd = crowd.add(m.position()); crowd = mobs.isEmpty() ? g.position() : crowd.scale(1.0 / mobs.size());
        // 1 a portal he can see, with time for its 4 s in it (they come at ~2.3 a second, he sprints ~5)
        BlockPos portal = null; double pd = 1e9;
        for (BlockPos q : BlockPos.betweenClosed(feet.offset(-24, -6, -24), feet.offset(24, 6, 24))) {
            if (!lv.getBlockState(q).is(net.minecraft.world.level.block.Blocks.NETHER_PORTAL) || lv.getBlockState(q.below()).is(net.minecraft.world.level.block.Blocks.NETHER_PORTAL)) continue;
            Vec3 pc = Vec3.atBottomCenterOf(q); double dg = g.position().distanceTo(pc);
            if (dg >= pd || g.attempts.within(com.claudemod.entity.life.Attempts.Kind.WALK, q, now, 20 * 30) || !com.claudemod.entity.life.Perception.clear(lv, eye, pc.add(0, 1.0, 0))) continue;
            boolean first = true; for (var m : mobs) { double dm = m.position().distanceTo(pc); if (dm < dg || dm < dg / 2 + 10) { first = false; break; } }
            if (!first) continue;
            var path = g.getNavigation().createPath(q.immutable(), 0); if (path == null || !path.canReach()) continue;
            portal = q.immutable(); pd = dg;
        }
        if (portal != null && lv.dimension() == net.minecraft.world.level.Level.OVERWORLD && !com.claudemod.director.ClaudeLife.hasGoldPiece(g)) { com.claudemod.ClaudeMod.LOGGER.info("[Claude life] escape: not the portal at {}, no gold piece on him for the Nether side (WorkGoal.pickEscape)", portal.toShortString()); portal = null; } /* live 00:51: a creeper escape into the Nether with no gold, dead to piglins 5 min later */
        if (portal != null) { setEscape("portal", portal, null); com.claudemod.ClaudeMod.LOGGER.info("[Claude life] escape: the portal at {} ({} off, in sight, there with its 4 s to spare) from {} (WorkGoal.pickEscape)", portal.toShortString(), (int) pd, from); return; }
        // 2 a 1-wide gap he can see and gets to first: through it, then plugged or held
        java.util.List<BlockPos[]> gaps = new java.util.ArrayList<>();
        for (int dx = -18; dx <= 18; dx++) for (int dz = -18; dz <= 18; dz++) for (int dy = -2; dy <= 2; dy++) {
            if (dx * dx + dz * dz > 18 * 18) continue;
            BlockPos c = feet.offset(dx, dy, dz); if (!lv.isLoaded(c)) continue;
            Direction ax = gapAxis(lv, c); if (ax == null) continue;
            Vec3 cc = Vec3.atBottomCenterOf(c); double dg = g.position().distanceTo(cc);
            boolean first = true; for (var m : mobs) if (m.position().distanceTo(cc) < dg) { first = false; break; }
            if (!first) continue;
            Direction side = crowd.distanceTo(Vec3.atBottomCenterOf(c.relative(ax))) >= crowd.distanceTo(Vec3.atBottomCenterOf(c.relative(ax.getOpposite()))) ? ax : ax.getOpposite(); // the far side from them
            boolean seen = false; // the slot in the wall as he sees it, at an angle: any point of its near face, feet or head high
            Direction near = g.position().distanceTo(Vec3.atBottomCenterOf(c.relative(side))) < g.position().distanceTo(Vec3.atBottomCenterOf(c.relative(side.getOpposite()))) ? side : side.getOpposite();
            Direction across = near.getClockWise();
            for (double u : new double[] {-0.3, 0.0, 0.3}) for (double hh : new double[] {0.5, 1.5}) { if (seen) break; Vec3 pt = Vec3.atCenterOf(c).add(near.getStepX() * 0.45 + across.getStepX() * u, hh - 0.5, near.getStepZ() * 0.45 + across.getStepZ() * u); seen = com.claudemod.entity.life.Perception.clear(lv, eye, pt); }
            if (!seen) continue;
            BlockPos hold = c.relative(side);
            if (standable(lv, c.relative(side, 2))) { hold = c.relative(side, 2); if (standable(lv, c.relative(side, 3))) hold = c.relative(side, 3); } // 2-3 out: his reach (3) on the one in the gap, theirs (~1.4) short of him
            if (g.attempts.within(com.claudemod.entity.life.Attempts.Kind.WALK, hold, now, 20 * 30)) continue;
            if (edgeDanger(lv, hold) != null || edgeDanger(lv, c) != null) continue; // (no stand over a long drop or lava: one knock and he's gone)
            gaps.add(new BlockPos[] {c.immutable(), hold.immutable()});
        }
        gaps.sort(java.util.Comparator.comparingDouble(a -> a[0].distSqr(feet)));
        for (int i = 0; i < Math.min(4, gaps.size()); i++) {
            var path = g.getNavigation().createPath(gaps.get(i)[1], 0);
            if (path == null || !path.canReach()) continue;
            setEscape("gap", gaps.get(i)[1], gaps.get(i)[0]); escapeSide = Direction.getNearest(gaps.get(i)[1].getX() - gaps.get(i)[0].getX(), 0, gaps.get(i)[1].getZ() - gaps.get(i)[0].getZ());
            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] escape: the 1-wide gap at {} ({} off, in sight, none of them nearer it), held from {} on its far side, from {} (WorkGoal.pickEscape)", gaps.get(i)[0].toShortString(), (int) Math.sqrt(gaps.get(i)[0].distSqr(feet)), gaps.get(i)[1].toShortString(), from);
            return;
        }
        // 3 a run: 16 out on the best heading, kept
        fleeDir = fleeHeading(mobs);
        BlockPos end = BlockPos.containing(g.position().add(fleeDir.scale(16)));
        for (int k = 0; k <= 6; k++) { if (standable(lv, end.above(4 - k))) { end = end.above(4 - k); break; } }
        setEscape("run", end.immutable(), null);
        com.claudemod.ClaudeMod.LOGGER.info("[Claude life] escape: running {} to {} (no portal or 1-wide gap he gets to first; {} gap(s) looked at) from {} (WorkGoal.pickEscape)", com.claudemod.entity.life.Perception.compassOf(fleeDir), end.toShortString(), gaps.size(), from);
    }
    private Direction escapeSide; // the gap's far side, from the gap
    /** Through the gap and on: a run 16 out past it on its far side, the same escape's next leg (not a new pick). */
    private void runOnPast(String why) {
        ServerLevel lv = level(); Direction side = escapeSide == null ? Direction.getNearest(g.getX() - escapeAt.getX(), 0, g.getZ() - escapeAt.getZ()) : escapeSide;
        BlockPos end = escapeAt.relative(side, 16); for (int k = 0; k <= 8; k++) { if (standable(lv, end.above(4 - k))) { end = end.above(4 - k); break; } }
        com.claudemod.ClaudeMod.LOGGER.info("[Claude life] through the gap at {}: {}: on past it to {} (WorkGoal.atEscape)", escapeGap == null ? "?" : escapeGap.toShortString(), why, end.toShortString());
        escapeKind = "run"; escapeAt = end.immutable(); escapeGap = null; escapeHeld = false; escapeSince = g.tickCount; escapeBest = g.position().distanceTo(Vec3.atBottomCenterOf(end)); escapeBestTick = g.tickCount;
        fleeDir = new Vec3(side.getStepX(), 0, side.getStepZ());
    }
    private void setEscape(String kind, BlockPos at, BlockPos gap) {
        escapeKind = kind; escapeAt = at; escapeGap = gap; escapeHeld = false; escapePlugged = false; escapeSince = g.tickCount; escapeBest = g.position().distanceTo(Vec3.atBottomCenterOf(at)); escapeBestTick = g.tickCount; escapePicks++;
    }
    /** Why the escape he's on no longer works, or null: no closer for 2.5 s, the gap shut, the portal gone, one of them right in the way. */
    private String escapeBlocked(java.util.List<? extends net.minecraft.world.entity.Mob> mobs) {
        ServerLevel lv = level(); double d = g.position().distanceTo(Vec3.atBottomCenterOf(escapeAt));
        if ("portal".equals(escapeKind) && !lv.getBlockState(escapeAt).is(net.minecraft.world.level.block.Blocks.NETHER_PORTAL)) return "the portal's gone out";
        if ("gap".equals(escapeKind) && escapeGap != null && !escapePlugged && gapAxis(lv, escapeGap) == null) return "the gap at " + escapeGap.toShortString() + " isn't open any more";
        if (d < escapeBest - 0.8) { escapeBest = d; escapeBestTick = g.tickCount; }
        else if (g.tickCount - escapeBestTick > 50 && d > 1.5) return String.format("no closer to it in 2.5 s (%.1f off, %.1f at best)", d, escapeBest);
        Vec3 to = Vec3.atBottomCenterOf(escapeAt).subtract(g.position()).multiply(1, 0, 1);
        if (d > 3 && to.lengthSqr() > 1e-4) for (var m : mobs) { Vec3 tm = m.position().subtract(g.position()).multiply(1, 0, 1); if (tm.length() < 2.2 && tm.normalize().dot(to.normalize()) > 0.8) return "a " + m.getType().getDescription().getString() + " right in the way, " + String.format("%.1f", tm.length()) + " off"; }
        return null;
    }
    /** At the escape: 0 not there yet; 1 there and on it (in the portal, holding the gap); 2 done (the gap plugged: the run's over). */
    private int atEscape(java.util.List<? extends net.minecraft.world.entity.Mob> mobs) {
        ServerLevel lv = level(); double d = g.position().distanceTo(Vec3.atBottomCenterOf(escapeAt));
        if ("run".equals(escapeKind)) {
            if (d >= 2.0) return 0;
            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] escape run's end at {} reached, them still on him: on from here (WorkGoal.atEscape)", escapeAt.toShortString());
            clearEscape(); pickEscape(mobs); return 0;
        }
        if ("portal".equals(escapeKind)) {
            if (d >= 1.2) return 0;
            g.setSprinting(false); g.getNavigation().stop(); g.getMoveControl().setWantedPosition(escapeAt.getX() + 0.5, escapeAt.getY(), escapeAt.getZ() + 0.5, 0.6); // in it, still: the portal takes him in 4 s
            if (!escapeHeld) { escapeHeld = true; com.claudemod.ClaudeMod.LOGGER.info("[Claude life] in the portal at {}: standing in it till it takes him (WorkGoal.atEscape)", escapeAt.toShortString()); }
            return 1;
        }
        if (!escapeHeld && d >= 1.3) return 0; // (the route ends within a block of the spot: 0.7 left him standing 1.07 off it for good)
        if (!escapeHeld) {
            escapeHeld = true; g.setSprinting(false); g.getNavigation().stop();
            ItemStack plug = solidScaffold();
            boolean clearOfThem = escapeGap != null && mobs.stream().noneMatch(m -> m.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(escapeGap).minmax(new net.minecraft.world.phys.AABB(escapeGap.above()))));
            if (plug.getCount() >= 2 && clearOfThem && g.getEyePosition().distanceTo(Vec3.atCenterOf(escapeGap)) <= 4.5) { // two blocks across the gap: shut out, like a player
                escapePlugged = true;
                g.tasks.addFirst(new Task.Place(escapeGap.above().immutable(), plug.getItem())); g.tasks.addFirst(new Task.Place(escapeGap.immutable(), plug.getItem()));
                com.claudemod.director.ClaudeLife.bridgeBlocks().add(escapeGap.immutable()); com.claudemod.director.ClaudeLife.bridgeBlocks().add(escapeGap.above().immutable()); // (the daylight chore's, once all is quiet: on his tower list it came straight back out and the horde had him)
                com.claudemod.ClaudeMod.LOGGER.info("[Claude life] through the gap at {}: plugging it with two {} (WorkGoal.atEscape)", escapeGap.toShortString(), plug.getItem().getDescription().getString());
                return 2;
            }
            long after = mobs.stream().filter(m -> m.getTarget() == g).count();
            boolean armored = false; for (EquipmentSlot sl : EquipmentSlot.values()) if (sl.isArmor() && !g.getItemBySlot(sl).isEmpty()) armored = true;
            if (after > 3 || weakFighter() || !armored || g.getHealth() < 12) { runOnPast(after + " after him, " + (int) g.getHealth() + " health, " + plug.getCount() + " blocks to plug it: too many to trade hits with one at a time"); return 0; } // (holding it against six cost him 20 health in 14 s and his life; through the gap they come single file and he outruns them)
            escapeSwings = 0;
            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] holding the gap at {} from {}: one at a time as they come through ({} blocks to plug it, health {}) (WorkGoal.atEscape)", escapeGap == null ? "?" : escapeGap.toShortString(), escapeAt.toShortString(), plug.getCount(), (int) g.getHealth());
        }
        // the plug he went for: still to go in (a hit set him running again before the blocks did), or in
        if (escapePlugged && escapeGap != null) {
            final BlockPos gp = escapeGap;
            boolean open = lv.getBlockState(gp).getCollisionShape(lv, gp).isEmpty() || lv.getBlockState(gp.above()).getCollisionShape(lv, gp.above()).isEmpty();
            boolean queued = g.tasks.stream().anyMatch(x -> x instanceof Task.Place pl && (pl.pos().equals(gp) || pl.pos().equals(gp.above())));
            boolean inIt = mobs.stream().anyMatch(m -> m.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(gp).minmax(new net.minecraft.world.phys.AABB(gp.above()))));
            if (open && queued && !inIt) { com.claudemod.ClaudeMod.LOGGER.info("[Claude life] the plug for the gap at {} still to go in: placing it before anything (WorkGoal.atEscape)", gp.toShortString()); return 2; }
            if (!open && mobs.stream().noneMatch(m -> m.distanceTo(g) < 4)) { com.claudemod.ClaudeMod.LOGGER.info("[Claude life] the gap at {} is plugged, none of them this side: the run's over (WorkGoal.atEscape)", gp.toShortString()); return 2; }
        }
        // holding: on his spot, facing the gap, a swing at whatever is in reach once the swing is charged
        if (g.getHealth() < 10) { runOnPast("health " + (int) g.getHealth() + " holding it"); return 0; }
        if (mobs.stream().noneMatch(m -> m.getTarget() == g && m.distanceTo(g) < 24)) { com.claudemod.ClaudeMod.LOGGER.info("[Claude life] nothing after him any more at the gap {}: the hold's over (WorkGoal.atEscape)", escapeGap == null ? "?" : escapeGap.toShortString()); g.setSprinting(false); clearEscape(); return 2; }
        if (d > 0.6) g.getMoveControl().setWantedPosition(escapeAt.getX() + 0.5, escapeAt.getY(), escapeAt.getZ() + 0.5, 1.0);
        if (d > 3.5) { escapeHeld = false; escapeBest = d; escapeBestTick = g.tickCount; com.claudemod.ClaudeMod.LOGGER.info("[Claude life] knocked {} off his spot by the gap: back to it (WorkGoal.atEscape)", String.format("%.1f", d)); return 0; }
        net.minecraft.world.entity.Mob near = null; for (var m : mobs) if (near == null || m.distanceTo(g) < near.distanceTo(g)) near = m;
        if (near != null) { if (incoming(near)) shieldUp(near); else shieldDown(); }
        if (near != null && canHitNow(near)) {
            equipWeapon(); shieldDown(); g.getLookControl().faceNow(near.getBoundingBox().getCenter()); g.attack(near); g.swing(InteractionHand.MAIN_HAND); escapeSwings++;
        }
        if (g.tickCount % 100 == 0) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] holding the gap at {}: {} swings so far, health {}, {} after him, nearest {} (WorkGoal.atEscape)", escapeGap == null ? "?" : escapeGap.toShortString(), escapeSwings, (int) g.getHealth(), mobs.stream().filter(m -> m.getTarget() == g).count(), near == null ? "-" : String.format("%.1f", near.distanceTo(g))); else if (escapeGap != null) { equipWeapon(); g.getLookControl().setLookAt(Vec3.atCenterOf(escapeGap.above())); }
        return 1;
    }

    /** Back off from the mobs: run away from them (toward the player if that's away from them), then eat to heal once clear. */
    private boolean doFlee(Task.Flee t) {
        // A skeleton on him out in the open: running just keeps him in its sights. Dig in like a player instead.
        if ((ticks == 1 || ticks % 20 == 0) && digInFromArrows()) return true; // (and again every second: running from mobs as fast as him only bleeds health)
        var mobs = hostilesNear(14);
        mobs.addAll(level().getEntitiesOfClass(net.minecraft.world.entity.monster.Creeper.class, g.getBoundingBox().inflate(16), c -> c.isAlive() && !mobs.contains(c))); // (16, not 12: the run ended with it 12 off, the job turned him round into it, five times over, then it blew) // a creeper within 12 is still on him whatever its target says
        if (ticks == 1) { fleeDead.clear(); fleeLastD = -1; fleeFromIds.clear(); for (var m : mobs) fleeFromIds.add(m.getUUID()); if (fleeSeed != null) fleeFromIds.add(fleeSeed); fleeSeed = null; }
        for (var m : ((ServerLevel) level()).getEntitiesOfClass(net.minecraft.world.entity.Mob.class, g.getBoundingBox().inflate(24), m -> m.isAlive() && fleeFromIds.contains(m.getUUID()))) if (!mobs.contains(m)) mobs.add(m); // what he's running from stays what he's running from till he's clear of it (out of sight behind him is still on his heels: the run ended a second in and his walk took him back past the witch)
        // cornered (a dungeon, a dead end): running isn't getting him anywhere, so like a player he turns and fights
        if (ticks == 1) fleeFrom = g.position();
        if (ticks == 30 && !escapeHeld && fleeFrom != null && g.position().distanceTo(fleeFrom) < 2.0 && !mobs.isEmpty()
                && !mobs.stream().allMatch(m -> (m instanceof net.minecraft.world.entity.monster.AbstractSkeleton || m instanceof net.minecraft.world.entity.monster.Pillager) && m.distanceTo(g) > 3) // "cornered" by a crossbow 8 blocks off is not a fight to wade into (health 16 to 1 in 10 s)
                && !(mobs.stream().min(java.util.Comparator.comparingDouble(m -> m.distanceTo(g))).get() instanceof net.minecraft.world.entity.monster.Creeper)) { // and never a creeper: "fighting his way out" of one is the explosion
            var near = mobs.stream().min(java.util.Comparator.comparingDouble(m -> m.distanceTo(g))).get();
            if (weakFighter()) { // bare hands or a wooden sword: "fighting his way out" is 20 punches at 3 damage a bite (five deaths in two minutes at his bed); cover instead
                desperateNow = true;
                boolean dug = digInFromArrows();
                desperateNow = false;
                com.claudemod.ClaudeMod.LOGGER.info("[Claude life] cornered unarmed (health {}): {}", (int) g.getHealth(), dug ? "digging in" : "no cover to be had, running on");
                if (dug) return true;
                fleeFrom = g.position(); // (another 30 ticks of running before this is looked at again)
            } else {
            cornerUntil = g.tickCount + 200;
            g.setSprinting(false);
            g.tasks.addFirst(new Task.Attack(near.getUUID(), 200));
            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] cornered (health {}): fighting his way out", (int) g.getHealth());
            return true;
            }
        }
        if (mobs.isEmpty() || (ticks > t.maxTicks() && !escapeHeld)) { // (holding his gap: as long as they come)
            g.setSprinting(false);
            if (mobs.isEmpty()) clearEscape();
            if (g.urgentAttacker != null) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] the run's over ({}): the hit he took while running is left behind, no fight for it (WorkGoal.doFlee)", mobs.isEmpty() ? "nothing after him" : "out of time, " + ticks + " ticks");
            g.urgentAttacker = null; // whatever hit him while he ran is behind him now: no running again from a mob that's gone
            healUp();
            return true;
        }
        // One escape, kept till it's reached or proven blocked (live 16:25: a new heading every 8-20 ticks, round in circles in a horde he outruns two to one)
        if (escapeAt != null && g.tickCount - escapeSince > 20 * 60) { com.claudemod.ClaudeMod.LOGGER.info("[Claude life] escape {} at {} a minute old: picked fresh (WorkGoal.doFlee)", escapeKind, escapeAt.toShortString()); clearEscape(); }
        if (ticks == 1 && escapeAt != null && !escapeHeld && g.tickCount - escapeSince > 100) clearEscape(); // (a new run well after the last: from here, fresh; within 5 s it's the same run)
        if (escapeAt == null) pickEscape(mobs);
        else if (ticks % 10 == 1 && !escapeHeld) {
            String why = escapeBlocked(mobs);
            if (why != null) {
                g.attempts.fail(com.claudemod.entity.life.Attempts.Kind.WALK, escapeAt, g.tickCount, g.blockPosition());
                if ("run".equals(escapeKind) && fleeDir != null) fleeDead.add(fleeDir);
                com.claudemod.ClaudeMod.LOGGER.info("[Claude life] escape {} at {} blocked: {}; on his attempts, another (WorkGoal.escapeBlocked)", escapeKind, escapeAt.toShortString(), why);
                clearEscape(); pickEscape(mobs);
            }
        }
        if (escapeAt != null) { int at = atEscape(mobs); if (at == 1) return false; if (at == 2) return true; }
        if (escapeAt != null && !"run".equals(escapeKind) && g.position().distanceTo(Vec3.atBottomCenterOf(escapeAt)) < 2.0) { Vec3 dest = Vec3.atBottomCenterOf(escapeAt); g.getNavigation().stop(); g.getMoveControl().setWantedPosition(dest.x, dest.y, dest.z, 1.2); } // the last steps onto the spot itself
        else if (ticks % 8 == 1 && escapeAt != null) { // the way to it, re-made as he goes: the same place every time
            Vec3 dest = Vec3.atBottomCenterOf(escapeAt);
            g.setSprinting(g.getFoodData().getFoodLevel() > 6);
            if (!g.getNavigation().moveTo(dest.x, dest.y, dest.z, 1.4)) g.getMoveControl().setWantedPosition(dest.x, dest.y, dest.z, 1.4);
        }
        if (ticks > 20 && !escapeHeld && !(escapeAt != null && !"run".equals(escapeKind)) && mobs.stream().allMatch(m -> m.distanceTo(g) > 16 || (m.distanceTo(g) > 10 && !com.claudemod.entity.life.Perception.clear((ServerLevel) level(), m.getEyePosition(), g.getEyePosition())))) { // well clear, and out of their sight: the run worked
            g.setSprinting(false); if (g.urgentAttacker != null) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] clear of them: the hit he took while running is left behind, no fight for it (WorkGoal.doFlee)"); g.urgentAttacker = null;
            clearEscape();
            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] got clear of them ({} ticks of running)", ticks);
            healUp();
            return true;
        }
        if (g.horizontalCollision && g.onGround()) g.getJumpControl().jump();
        return false;
    }

    /**
     * Clear of them and hurt: eat to heal (a full hunger bar heals fast), and badly hurt, hang back a while first (a player
     * doesn't walk back in at 3 hearts). One eat and one wait at most: never a pile of them from a run of hits.
     */
    private void healUp() {
        // a skeleton still drawing on him: no standing there eating in its sights; cover first (the dig-in), the eating from in there
        if (!diggingIn() && !com.claudemod.entity.life.Perception.known(g, net.minecraft.world.entity.monster.Monster.class, g.getBoundingBox().inflate(20), m -> m.isAlive() && (m instanceof net.minecraft.world.entity.monster.AbstractSkeleton || m instanceof net.minecraft.world.entity.monster.Pillager) && (m.getTarget() == g || m.distanceTo(g) < 10)).isEmpty()) {
            if (digInFromArrows()) { com.claudemod.ClaudeMod.LOGGER.info("[Claude life] a skeleton still on him (health {}): under cover before any healing", (int) g.getHealth()); return; }
        }
        if (g.getHealth() >= g.getMaxHealth() - 4) return;
        if (current instanceof Task.Eat || current instanceof Task.Wait || g.tasks.peek() instanceof Task.Eat || g.tasks.peek() instanceof Task.Wait) return;
        net.minecraft.world.item.Item food = com.claudemod.director.ClaudeLife.foodToEat(g, g.getFoodData().getFoodLevel() <= 6); // (starving means can't sprint, not "under 18": 16:08 a spider eye at hunger 7 and full health poisoned him)
        if (g.getHealth() < 10 && !hostilesNear(16).isEmpty() && !diggingIn()) { desperateNow = true; boolean dug = digInFromArrows(); desperateNow = false; if (dug) { com.claudemod.ClaudeMod.LOGGER.info("[Claude life] clear of them at health {} with mobs still about: under cover to heal", (int) g.getHealth()); if (food != null && g.getFoodData().needsFood()) g.tasks.addFirst(new Task.Eat(food)); return; } } // (standing "a moment" in the open at 5 health, four zombies finished him in two seconds)
        if (g.getHealth() < 10 && !hostilesNear(16).isEmpty() && g.getFoodData().getFoodLevel() >= 18) { // hurt with mobs still about and a full enough stomach to heal: a few seconds back here; otherwise standing heals nothing, he eats or gets food and gets on with it
            g.tasks.addFirst(new Task.Wait(20 * 6, false));
            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] clear of them at health {}: a moment here before going back", (int) g.getHealth());
        }
        if (food != null && g.getFoodData().needsFood()) g.tasks.addFirst(new Task.Eat(food)); // eat first, then the wait
    }

    /**
     * Shot at in the open with no weapon worth closing in with: dig two down (dirt or stone he can take), cover the hole
     * over his head, wait it out, then climb back up. True if he started that.
     */
    private BlockPos digInAt;
    private boolean desperateNow; // at a heart with nothing to eat: cover now, no running for a gap first
    public int desperateTick; // when he last tried (a field added live starts 0: fine)
    private int digInTick = -100000;
    private int digInRise; // how high the tower under his cover is (0: a hole)
    private static java.util.Map<String, Integer> sdSaid; // when each self-defence skip/clear reason was last said (static: fields added live start null)
    /** A self-defence skip or clear, said with its reason; the same reason at most every 2 s (it can hold for many ticks). */
    private void sdSay(String key, String line, Object... args) {
        if (sdSaid == null) sdSaid = new java.util.HashMap<>();
        Integer last = sdSaid.get(g.getUUID() + key);
        if (last != null && last <= g.tickCount && g.tickCount - last < 40) return;
        sdSaid.put(g.getUUID() + key, g.tickCount);
        com.claudemod.ClaudeMod.LOGGER.info(line, args);
    }
    private static java.util.Map<java.util.UUID, Integer> lastFightTick; // when he last squared up to each mob (static: fields added live start null)
    private static java.util.Map<java.util.UUID, Integer> lastFightTick() { if (lastFightTick == null) lastFightTick = new java.util.HashMap<>(); return lastFightTick; }
    private static java.util.Set<BlockPos> tunnelOut; // holes he's already tunnelled out of (static: fields added live start null)
    private static java.util.Set<BlockPos> tunnelOut() { if (tunnelOut == null) tunnelOut = new java.util.HashSet<>(); return tunnelOut; }
    private java.util.List<Task> digInTasks = java.util.List.of();

    /** Standing in (or at the top of) the hole he's digging: same column, up to two below where he started it. */
    private boolean inDigInHole() {
        BlockPos f = g.blockPosition();
        return digInAt != null && Math.abs(f.getX() - digInAt.getX()) <= 1 && Math.abs(f.getZ() - digInAt.getZ()) <= 1
                && f.getY() <= digInAt.getY() + Math.max(4, digInRise + 1) && f.getY() >= digInAt.getY() - 3; // (+4: up his 3-high tower, in its hut; +2 read the tower top as "fell off" and stacked four towers)
    }

    /** In the middle of digging in (its steps still queued or running, not long ago). */
    private boolean diggingIn() {
        if (digInAt == null || digInTasks == null) return false;
        for (Task t : digInTasks) if (t == current || g.tasks.contains(t)) return true; // its wait still running counts, however long (the 28 s cap below had the cover rules stack a second tower on the first)
        return false;
    }

    /**
     * Rock over him, by the surface heightmap that leaves don't count in: two blocks of it over his head or more, or a ceiling
     * higher up. Under a tree in the open is the open (live 04:42: "underground" at y 69 under a birch, from !canSeeSky: a box
     * where the expert fights).
     */
    public static boolean underground(ServerLevel level, BlockPos feet) {
        return level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, feet.getX(), feet.getZ()) >= feet.getY() + 4;
    }
    /** Ready for a crowd (the combat rule): an iron or better chestplate on, an iron or better sword or axe, 4+ real food for regen. */
    private boolean prepared() {
        ItemStack chest = g.getItemBySlot(EquipmentSlot.CHEST);
        boolean iron = chest.is(net.minecraft.world.item.Items.IRON_CHESTPLATE) || chest.is(net.minecraft.world.item.Items.DIAMOND_CHESTPLATE) || chest.is(net.minecraft.world.item.Items.NETHERITE_CHESTPLATE);
        boolean blade = g.inventory.count(x -> x.is(net.minecraft.world.item.Items.IRON_SWORD) || x.is(net.minecraft.world.item.Items.DIAMOND_SWORD) || x.is(net.minecraft.world.item.Items.NETHERITE_SWORD) || x.is(net.minecraft.world.item.Items.IRON_AXE) || x.is(net.minecraft.world.item.Items.DIAMOND_AXE) || x.is(net.minecraft.world.item.Items.NETHERITE_AXE)) > 0;
        int food = g.inventory.count(x -> x.getItem().getFoodProperties(x, g) != null && !x.is(net.minecraft.world.item.Items.ROTTEN_FLESH) && !x.is(net.minecraft.world.item.Items.SPIDER_EYE));
        return iron && blade && food >= 4;
    }
    /** A skeleton (or pillager) he sees, hears or felt within 24, or sensed lately there: what keeps his cover up. Null: none. */
    private String shooterAbout() {
        for (var m : com.claudemod.entity.life.Perception.known(g, net.minecraft.world.entity.Mob.class, g.getBoundingBox().inflate(24), m -> m.isAlive() && (m instanceof net.minecraft.world.entity.monster.AbstractSkeleton || m instanceof net.minecraft.world.entity.monster.Pillager)))
            return m.getType().getDescription().getString() + " " + (int) m.distanceTo(g) + " off (" + (com.claudemod.entity.life.Perception.seesNow(g, m) ? "in sight" : "heard or felt") + ")";
        for (var m : com.claudemod.entity.life.Perception.lately(g, 24)) if (m instanceof net.minecraft.world.entity.monster.AbstractSkeleton || m instanceof net.minecraft.world.entity.monster.Pillager) return m.getType().getDescription().getString() + " sensed lately";
        return null;
    }
    private java.util.Map<String, Integer> noCoverSaid; private int shooterHeld = -1, coverHitSaid = -1; // (lazy: fields added live start null)
    /** No cover here, and why: each reason said once a minute (the numbers in it aside), with the method's name. */
    private boolean noCover(String why) {
        if (noCoverSaid == null || noCoverSaid.size() > 64) noCoverSaid = new java.util.HashMap<>();
        String k = why.replaceAll("[-0-9.]+", "#"); Integer at = noCoverSaid.get(k);
        if (at == null || g.tickCount - at >= 20 * 60) { noCoverSaid.put(k, g.tickCount); com.claudemod.ClaudeMod.LOGGER.info("[Claude life] no cover: {} (WorkGoal.digInFromArrows)", why); }
        return false;
    }

    private boolean coverGap; // the last digInFromArrows said no for now: a melee mob too close to finish the cover (the cover proximity rule), so he runs for the gap
    private boolean noCoverGap(String why) { coverGap = true; return noCover(why); }
    private boolean digInFromArrows() {
        coverGap = false;
        ServerLevel level = level();
        BlockPos feet = g.blockPosition();
        if (diggingIn()) {
            if (inDigInHole()) return true; // at it already (or down in it): carry on (one threat, one cover: never a second over it)
            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] knocked off his cover at {} (he's at {}): its steps dropped, cover again from here if anything still calls for it (WorkGoal.digInFromArrows)", digInAt.toShortString(), feet.toShortString());
            g.tasks.removeAll(digInTasks); // knocked off the spot: that hole's no use, start again here
            digInAt = null;
        }
        var shooters = com.claudemod.entity.life.Perception.known(g, net.minecraft.world.entity.monster.Monster.class, g.getBoundingBox().inflate(24),
                m -> m.isAlive() && (m instanceof net.minecraft.world.entity.monster.AbstractSkeleton || m instanceof net.minecraft.world.entity.monster.Pillager) && (m.getTarget() == g || m.distanceTo(g) < 12 || m.hasLineOfSight(g))); // (in sight within 24 counts: one 15 off shot him off a bare tower twice) skeletons and pillagers alike (a bare tower against a crossbow killed him)
        int crowd = hostilesNear(14).size();
        boolean swarmed = ((!armed() || weakKit()) && crowd >= 2) || crowd >= (prepared() ? 4 : 3) // (the combat rule: a crowd is left alone unless he's prepared, iron and food: in iron with a diamond sword and six meals two skeletons and a zombie had him boxed in, live 04:42) a crowd (or two with nothing proper to fight them in): a covered hole or a tower stops them too
                || (!armed() && crowd >= 1 && !level.isDay()); // unarmed at night with anything on him: running from a zombie as fast as him is how a fresh respawn died twice; two down and a lid
        java.util.List<net.minecraft.world.entity.Mob> phantoms = com.claudemod.entity.life.Perception.known(g, net.minecraft.world.entity.Mob.class, g.getBoundingBox().inflate(32), m -> m.isAlive() && m instanceof net.minecraft.world.entity.monster.Phantom);
        boolean desperate = g.getHealth() <= 4 && com.claudemod.director.ClaudeLife.foodToEat(g, true) == null && !enemiesNear(24).isEmpty(); // no health to spare and no way to get it back: cover first
        if ((shooters.isEmpty() && !swarmed && phantoms.isEmpty() && !desperate && !desperateNow) || g.isInWater()) return noCover(g.isInWater() ? "in the water" : "nothing calls for it (no shooter on him, " + crowd + " hostile(s) within 14, no phantoms)");
        boolean diamondChest = g.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).is(net.minecraft.world.item.Items.DIAMOND_CHESTPLATE) || g.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).is(net.minecraft.world.item.Items.NETHERITE_CHESTPLATE); // (worn, not "diamond stage": that counts a deep mine too, and the dig-in stage died fleeing three husks)
        boolean shieldKit = prepared() && g.getItemBySlot(EquipmentSlot.OFFHAND).is(net.minecraft.world.item.Items.SHIELD); // (iron, a sword, food and a shield: a lone skeleton is blocked and killed, not hidden from; World 15 04:13, kitted, he dug in from one and sat out the night: the pearl hunt's window)
        if ((diamondChest || shieldKit) && armed() && g.getHealth() >= 10 && phantoms.isEmpty() && !swarmed && !desperate && !desperateNow) return noCover((diamondChest ? "a diamond chestplate" : "iron, a shield") + " and a sword: no hole, he fights or keeps on home"); // diamond gear: no hole for a skeleton or a crowd, he fights or keeps on home (the user: hiding is for the early game; with a diamond chestplate he runs to his bed or mine; he sat in a hole 2 minutes on the way home)
        if (!level.getEntitiesOfClass(net.minecraft.world.entity.monster.Creeper.class, g.getBoundingBox().inflate(8), c -> c.isAlive()).isEmpty()) return noCover("a creeper within 8: distance is the only cover from one"); // a creeper that close just walks up to the hole and blows it in: distance is the only cover from one
        if (g.pillarBlocks.contains(feet.below()) || g.pillarBlocks.contains(feet.below(2))) { // already up his own tower (its wait got dropped by a replan): stay up, no second tower on top of it
            boolean roofed = !level.getBlockState(feet.above(2)).getCollisionShape(level, feet.above(2)).isEmpty();
            if ((!phantoms.isEmpty() || !shooters.isEmpty()) && !roofed) { // a bare tower under phantoms (or in a skeleton's sights) is a perch to be picked off (a phantom took him 20 → 0 up one): down it, and the hole with its lid instead
                ItemStack cap = solidScaffold(); // (hung off the tower: sand or gravel just falls)
                if (cap.getCount() >= 14) { // blocks on him: a lid and walls round him up here instead (coming down, "under cover first", the tower again: six rounds of that in a cave and he was shot dead)
                    java.util.List<Task> hut = new java.util.ArrayList<>();
                    java.util.List<BlockPos> cells = new java.util.ArrayList<>();
                    for (int h = -1; h <= 1; h++) for (Direction d : Direction.Plane.HORIZONTAL) cells.add(feet.above(h).relative(d).immutable()); // each ring on the one below, the first against the top tower block (beside his feet there's nothing to place against)
                    cells.add(feet.above(2).north().immutable()); cells.add(feet.above(2).immutable()); // (the lid hangs off a block on the north wall)
                    for (BlockPos q : cells) if (level.getBlockState(q).canBeReplaced() && level.getFluidState(q).isEmpty()) { hut.add(new Task.Place(q, cap.getItem())); g.pillarBlocks.addFirst(q); }
                    int n = hut.size();
                    hut.add(new Task.Wait(600, false));
                    for (int i = n - 1; i >= 0; i--) hut.add(new Task.Break(((Task.Place) hut.get(i)).pos()));
                    hut.add(new Task.Pillar(-1));
                    for (int i = hut.size() - 1; i >= 0; i--) g.tasks.addFirst(hut.get(i));
                    coversMade++; digInAt = feet.immutable(); digInTick = g.tickCount; digInTasks = hut;
                    com.claudemod.ClaudeMod.LOGGER.info("[Claude life] up a bare tower with {} about: walling himself in up here ({} blocks)", phantoms.isEmpty() ? "a shooter" : "phantoms", n);
                    return true;
                }
                g.tasks.addFirst(new Task.Pillar(-1));
                digInAt = null; digInTasks = null;
                com.claudemod.ClaudeMod.LOGGER.info("[Claude life] up a bare tower with {} about: coming down to dig in under a lid instead", phantoms.isEmpty() ? "a shooter" : "phantoms");
                return true;
            }
            java.util.List<Task> stay = java.util.List.of(new Task.Wait(600, false));
            g.tasks.addFirst(stay.get(0));
            coversMade++; digInAt = feet.immutable(); digInTick = g.tickCount; digInTasks = stay;
            var pin = enemiesNear(16); String who = ""; for (var m : pin) who += m.getType().getDescription().getString() + " " + (int) m.distanceTo(g) + (m.getTarget() == g ? "*" : "") + ", ";
            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] already up his tower at {}: staying put (about: {})", feet.toShortString(), who.isEmpty() ? "nothing" : who);
            return true;
        }
        boolean underground = underground(level, feet) || com.claudemod.director.ClaudeLife.isMineCell(g, feet); // (a tower works down here too, given head room; the dig-in below is for the surface only: never a hole down into who knows what, and never one in his own staircase: he dug one in the entrance, died in it, and it cut the stairs for the tracer)
        // Past the early game, in the open, a real sword and food for regen, a shooter alone (no crowd, no phantoms, not at his last
        // hearts with nothing to eat): no hole, box or tower, like a player in iron. He closes in on it (shouldFlee: closing in is
        // the plan), or he's running from it and gets out of its sight, then eats (the user: hiding is for the early game).
        if (!underground && !shooters.isEmpty() && !swarmed && phantoms.isEmpty() && !desperate && !desperateNow && com.claudemod.director.ClaudeLife.pastEarly(g) && !weakFighter() && com.claudemod.director.ClaudeLife.foodToEat(g, true) != null)
            return noCover("past the early game in the open (armor " + g.getArmorValue() + ", a real sword, food on him): no box or hole for " + shooters.size() + " shooter(s); he closes in on it or gets out of its sight");
        boolean holeOk = true; // the ground under him only matters for the hole: the tower stands on anything (130 cobble on him at a river bank and "no cover to be had")
        for (BlockPos q : new BlockPos[] {feet.below(), feet.below(2), feet.below(3)}) {
            BlockState st = level.getBlockState(q);
            boolean diggable = st.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_SHOVEL)
                    || (st.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_PICKAXE) && !g.inventory.find(i -> i.isCorrectToolForDrops(st)).isEmpty());
            if (!diggable || st.hasBlockEntity() || !level.getFluidState(q).isEmpty()) { holeOk = false; break; }
        }
        if (level.getBlockState(feet.below(4)).getCollisionShape(level, feet.below(4)).isEmpty()) holeOk = false; // a cave under: not a hiding hole
        // Mobs right on him: digging in front of them only gets him hit. Two down is out of their reach, so he runs till
        // he's got the gap that two blocks take him to dig (or he can't shake them), then digs.
        double melee = hostilesNear(14).stream().filter(m -> !(m instanceof net.minecraft.world.entity.monster.AbstractSkeleton)).mapToDouble(m -> m.distanceTo(g)).min().orElse(99);
        float digTicks = 10;
        for (BlockPos q : new BlockPos[] {feet.below(), feet.below(2)}) digTicks += 1f / Math.max(0.001f, level.getBlockState(q).getDestroyProgress(g, level, q)) + 5;
        final double holeNeed = Math.max(12.0, 2.5 + (digTicks + 25) / 20.0 * 2.5); // the cover proximity rule: a hole only with 12+ to the nearest melee mob (three down and the lid: a zombie covers that and more while he digs)
        if (!g.onGround() && !g.isInWater()) return noCover("in the air: decided on landing"); // in the air (a hit knocked him up): the tower can't start this tick, and that's no reason for a hole; decide on landing
        if (true) { // ponytail: always a skytower first (what they asked for); the dig-in below is only for when he has no blocks
            // no time to dig: two blocks up is just as far out of their reach and takes a second, and from there he can hit them
            ItemStack blocks = scaffoldBlock();
            boolean room = true;
            for (int up = 2; up <= 5; up++) room &= level.getBlockState(feet.above(up)).getCollisionShape(level, feet.above(up)).isEmpty();
            boolean roofless = (!phantoms.isEmpty() || !shooters.isEmpty()) && solidScaffold().getCount() < 13; // (the hut is walls hung off the tower: sand or gravel just falls, so only solid blocks count; a sand hut fell apart under a skeleton and he was shot off) // phantoms dive and skeletons shoot: a tower with no hut on it is just a perch to be shot off; the dig-in with its lid below is the cover then
            if (!blocks.isEmpty() && blocks.getCount() >= 2 && room && g.onGround() && !roofless) {
                if (underground && blocks.getCount() < 6) return noCover("underground with " + blocks.getCount() + " blocks: no tower worth it"); // (a tower with no blocks to spare to cap it: not worth it down here)
                if (melee < 6) { coverGap = true; return noCover(String.format("a melee mob %.1f off: a tower needs 6+ (the cover proximity rule), running for the gap first", melee)); } // (desperate too: at 2 hearts the 3-high tower went up with the zombie 2.7 off, it hit him on the second block and again on the way down, dead; "hole possible" made it the exception, then the hole needed 8.7 and the tower went up anyway) // (the user's cover rule: a tower needs 6+ from melee mobs; the old desperate exception stays only where a hole was the alternative) proximity: three blocks up takes ~2 s and a zombie covers 5 blocks in that; nearer than that he keeps running (sprinting outruns them) and towers once he has the gap
                boolean creeperBy = !level.getEntitiesOfClass(net.minecraft.world.entity.monster.Creeper.class, g.getBoundingBox().inflate(16)).isEmpty() && shooters.isEmpty() && phantoms.isEmpty(); // a creeper about: 4 up, out of its 3-block fuse range (at 3 it lit at the foot of his tower, blew it out from under him, and the zombies had him)
                int towerH = creeperBy && level.getBlockState(feet.above(5)).getCollisionShape(level, feet.above(5)).isEmpty() ? 4 : 3;
                int rise = 0; // on a slope "3 up" is measured from the higher ground beside him: a zombie a block up the hill hit him on top of his 3-high tower
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int k = 0; k <= 2; k++) { BlockPos q = feet.offset(dx, k, dz); if ((dx != 0 || dz != 0) && !level.getBlockState(q).getCollisionShape(level, q).isEmpty()) rise = Math.max(rise, k + 1); }
                if (rise > 0 && level.getBlockState(feet.above(towerH + rise + 1)).getCollisionShape(level, feet.above(towerH + rise + 1)).isEmpty()) towerH = Math.min(6, towerH + rise);
                ItemStack hutBlocks = solidScaffold();
                boolean enclosed = hutBlocks.getCount() >= 14 + (ItemStack.isSameItem(hutBlocks, blocks) ? towerH : 0); // walls at his feet and head and a lid up there, on top of what the tower takes
                Direction edge = null; boolean edgeLava = false; int edgeDrop = 0; // a cell beside him with no floor and a long drop or lava under it: a knockback off a bare tower there (or off the climb) is a fall into it
                for (Direction d : Direction.Plane.HORIZONTAL) {
                    BlockPos n = feet.relative(d);
                    if (!level.getBlockState(n.below()).getCollisionShape(level, n.below()).isEmpty()) continue;
                    int drop = 0; boolean lava = false;
                    for (BlockPos q = n.below(); drop < 40 && q.getY() > level.getMinBuildHeight(); q = q.below(), drop++) { if (level.getFluidState(q).is(net.minecraft.tags.FluidTags.LAVA)) { lava = true; break; } if (!level.getBlockState(q).getCollisionShape(level, q).isEmpty()) break; }
                    if (lava || drop >= 4) { edge = d; edgeLava = lava; edgeDrop = drop; break; }
                }
                boolean holeNow = holeOk && !underground && melee >= holeNeed; // (time to dig it before they're on him: nothing in melee within 12)
                if (!holeNow && edge != null && !enclosed) return noCoverGap(String.format("on the edge of %s to the %s: a bare tower there is a knockback into it; running for solid ground first (the fall-damage judgement)", edgeLava ? "lava " + edgeDrop + " down" : "a " + edgeDrop + "-block drop", edge.getName())); // (no walls to take the knockback)
                if (!holeNow && edge != null) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] tower on the edge of {} to the {}: walled in, the walls take the knockback (WorkGoal.digInFromArrows)", edgeLava ? "lava " + edgeDrop + " down" : "a " + edgeDrop + "-block drop", edge.getName());
                if (holeNow) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] cover{}: two down and a lid (nearest melee {}, the hole needs {}+), not a {} tower (WorkGoal.digInFromArrows)", shooters.isEmpty() ? "" : " from a skeleton", melee >= 99 ? "none" : String.format("%.1f", melee), String.format("%.1f", holeNeed), enclosed ? "walled" : "bare"); // (quicker and cheaper than a walled tower, and cover from arrows too; the tower is for when they're too close to dig)
                else {
                java.util.List<Task> up = new java.util.ArrayList<>(java.util.List.of(new Task.Pillar(towerH)));
                // arrows or phantoms about: a little hut on top (walls up from the tower top, a roof), like a player; all of it comes down after
                boolean ranged = !shooters.isEmpty() || !phantoms.isEmpty() || desperate; // (a roof too: phantoms dive, and at a heart nothing gets to reach him)
                java.util.List<BlockPos> hut = new java.util.ArrayList<>();
                if (enclosed) { // always, not just with a shooter in sight now (one came along later and knocked him off a bare one)
                    for (int h = towerH - 1; h <= towerH + 1; h++) for (Direction d : Direction.Plane.HORIZONTAL) hut.add(feet.above(h).relative(d).immutable()); // each ring sits on the one below (the first against the tower)
                    hut.add(feet.above(towerH + 2).north().immutable()); // a block on the north wall to hang the lid off (the lid over his head touches the walls only at the corners: nothing to place it against)
                    hut.add(feet.above(towerH + 2).immutable()); // the roof
                    hut.removeIf(q -> !level.getBlockState(q).canBeReplaced());
                    for (BlockPos q : hut) { up.add(new Task.Place(q, hutBlocks.getItem())); g.pillarBlocks.addFirst(q.immutable()); } // on his list: it comes down later whatever happens here
                }
                up.add(new Task.Wait(600, false));
                for (int i = hut.size() - 1; i >= 0; i--) up.add(new Task.Break(hut.get(i))); // roof first, then the walls top down
                for (int k = towerH - 1; k >= 0; k--) up.add(new Task.Break(feet.above(k).immutable())); // and the tower, top down, all of it (a 6-high one was left half standing)
                up.add(new Task.Pickup(60));
                for (int i = up.size() - 1; i >= 0; i--) g.tasks.addFirst(up.get(i));
                coversMade++; digInAt = feet.immutable(); digInTick = g.tickCount; digInTasks = up; digInRise = towerH;
                com.claudemod.ClaudeMod.LOGGER.info("[Claude life] mobs on him: a {}-high skytower at {} out of their reach{} (hole {}, nearest melee {}: the tower needs 6+, the hole {}+; {} shooter(s)) (WorkGoal.digInFromArrows)", towerH, feet.toShortString(), hut.isEmpty() ? " (bare: no blocks for walls)" : ", walled in on top", !holeOk ? "not here" : underground ? "not underground" : "too close to dig", String.format("%.1f", melee), holeOk && !underground ? String.format("%.1f", holeNeed) : "-", shooters.size());
                return true;
                }
            }
            if (!holeOk) return noCover("no tower to be had (" + blocks.getCount() + " blocks, head room " + room + ") and no hole here"); // no tower to be had, and no hole here either
            if (melee < holeNeed && (!(current instanceof Task.Flee) || ticks < 120 || melee < 3)) return noCoverGap(String.format("a melee mob %.1f off: the hole needs %.1f+, no tower to be had (the cover proximity rule): running for the gap first", melee, holeNeed)); // (never with one within 3, desperate or boxed in: three husks hit him to death in the hole he was digging) // proximity: the hole takes digTicks plus the lid; nearer than a zombie covers in that time he keeps running for the gap first; six seconds of running with no gap gained (boxed in) and the hole goes in anyway: two down blocks most of them
        }
        if (underground) { // no digging down from a cave floor: walls round him where he stands instead, the way a player boxes himself in when a skeleton has him in a cave (no cover rule at all down here is how he died at -4, -27, -28)
            ItemStack wall = scaffoldBlock();
            if (wall.getCount() < 6 || !g.onGround()) return noCover(wall.getCount() < 6 ? "underground with " + wall.getCount() + " blocks: not enough to wall himself in" : "underground, not on the ground: the box waits till he lands");
            // the order a player boxes himself in from inside: the corners first (against the floor, seen past the open sides), then the sides at his feet and head, then
            // the lid (against the roof); sides first, the corners are behind the walls out of his sight, and he walked out of the box for them (live 04:42)
            java.util.List<BlockPos> cells = new java.util.ArrayList<>();
            if (wall.getCount() >= 17) for (int h = 0; h <= 1; h++) for (Direction d : Direction.Plane.HORIZONTAL) cells.add(feet.above(h).relative(d).relative(d.getClockWise()).immutable()); // the corners too: a zombie's reach gets through the corner gap of a bare 1x1 (one hit him at 2 health five minutes into his box)
            for (int h = 0; h <= 1; h++) for (Direction d : Direction.Plane.HORIZONTAL) cells.add(feet.above(h).relative(d).immutable());
            if (!(wall.getItem() instanceof net.minecraft.world.item.BlockItem wb && wb.getBlock() instanceof net.minecraft.world.level.block.FallingBlock)) cells.add(feet.above(2).immutable()); // (sand or gravel for a lid just falls in on him)
            java.util.List<Task> box = new java.util.ArrayList<>();
            for (BlockPos q : cells) if (level.getBlockState(q).canBeReplaced() && level.getFluidState(q).isEmpty()) { box.add(new Task.Place(q, wall.getItem())); g.pillarBlocks.addFirst(q); }
            if (box.isEmpty()) return noCover("underground, every cell round him solid already: no box to build");
            int n = box.size();
            box.add(new Task.Wait(400, false));
            for (int i = n - 1; i >= 0; i--) box.add(new Task.Break(((Task.Place) box.get(i)).pos())); // down the other way round: the lid, the sides, the corners last (seen through the gaps the sides leave)
            box.add(new Task.Pickup(40));
            for (int i = box.size() - 1; i >= 0; i--) g.tasks.addFirst(box.get(i));
            coversMade++; digInAt = feet.immutable(); digInTick = g.tickCount; digInTasks = box;
            g.getNavigation().stop(); g.getMoveControl().setWantedPosition(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, 0.6); // into the middle of the cell first, like a player squaring up in it: walking in, his body was over the corner cell and the step-out put him in it
            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] {} on him underground: walling himself in at {} ({} blocks, corners first; {}) (WorkGoal.digInFromArrows)", shooters.isEmpty() ? "a crowd" : "a skeleton", feet.toShortString(), n, shooters.isEmpty() ? crowd + " within 14" : shooters.get(0).getType().getDescription().getString() + " " + (int) shooters.get(0).distanceTo(g) + " off");
            return true;
        }
        for (int i = 1; i <= 3; i++) { // water or a drop under him, or water beside the column (it floods the hole as he digs): no hole here (a pond at spawn; a lake edge where every break "hit water")
            BlockPos q = feet.below(i);
            if (!level.getFluidState(q).isEmpty() || level.getBlockState(q).getCollisionShape(level, q).isEmpty()) return noCover("water or a drop under him at " + q.toShortString() + ": no hole here");
            for (Direction dd : Direction.Plane.HORIZONTAL) if (!level.getFluidState(q.relative(dd)).isEmpty()) return noCover("water beside the hole's column at " + q.relative(dd).toShortString() + ": it'd flood");
        }
        // shooters: distance buys nothing, being in their sights does; the dig plus the lid takes arrows the whole time (one every ~2 s, ~3 each):
        // if that eats what health he has, he runs to break the line of sight first (a corner, a tree) and digs from there
        long inSights = shooters.stream().filter(m -> m.hasLineOfSight(g)).count();
        // a skeleton's bow fires on a fixed timer, but its arrows spread with distance and take time to arrive: at range most miss (full weight within 8, half to 12, a third beyond)
        double arrowsDuringDig = shooters.stream().filter(m -> m.hasLineOfSight(g)).mapToDouble(m -> { double d = m.distanceTo(g); return d <= 8 ? 1.0 : d <= 12 ? 0.5 : 0.34; }).sum() * (digTicks + 60) / 40.0;
        if (inSights > 0 && arrowsDuringDig * 3 >= g.getHealth() - 4 && !desperateNow) {
            return noCover(inSights + " shooter(s) with a line on him and only " + (int) g.getHealth() + " health: out of their sights before any digging");
        }
        // three down: the cover then goes in at ground level, where the ground around it gives it something to sit against
        ItemStack lidToo = scaffoldBlock(); // logs or planks on him: a lid as good as any
        java.util.List<Task> steps = new java.util.ArrayList<>(java.util.List.of(new Task.Break(feet.below()), new Task.Break(feet.below(2)), new Task.Break(feet.below(3)),
                new Task.Pickup(40), new Task.Place(feet.below(), net.minecraft.world.item.Items.DIRT), new Task.Place(feet.below(), net.minecraft.world.item.Items.COBBLESTONE))); // the lid from what the dig just gave him (dirt, or the cobble off the stone); 40 ticks for the drops to land (10 left the hole open: shot in it)
        if (!lidToo.isEmpty()) steps.add(new Task.Place(feet.below(), lidToo.getItem()));
        boolean noneOnHim = lidToo.isEmpty() && g.inventory.count(st -> st.is(net.minecraft.world.item.Items.DIRT) || st.is(net.minecraft.world.item.Items.COBBLESTONE)) == 0;
        String digLid = null; // what the dig itself gives the lid Places above (dirt by hand, cobble with a pick): a lid all the same (stage 120: the tuck under the overhang with a dirt lid made, and the climb out tried from the tuck)
        for (int k = 1; k <= 3 && digLid == null; k++) {
            final BlockPos q = feet.below(k); final BlockState qs = level.getBlockState(q);
            if (qs.is(net.minecraft.world.level.block.Blocks.DIRT) || qs.is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK) || qs.is(net.minecraft.world.level.block.Blocks.PODZOL) || qs.is(net.minecraft.world.level.block.Blocks.MYCELIUM) || qs.is(net.minecraft.world.level.block.Blocks.DIRT_PATH)) digLid = "dirt off " + q.toShortString();
            else if ((qs.is(net.minecraft.world.level.block.Blocks.STONE) || qs.is(net.minecraft.world.level.block.Blocks.COBBLESTONE)) && !g.inventory.find(i -> i.isCorrectToolForDrops(qs)).isEmpty()) digLid = "cobblestone off " + q.toShortString();
        }
        if (noneOnHim && digLid != null) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] nothing on him to cap the hole at {} with, but the dig gives {}: the lid from that, no tuck under the overhang (WorkGoal.digInFromArrows)", feet.toShortString(), digLid);
        boolean noLid = noneOnHim && digLid == null;
        BlockPos tuckedAt = null; // the cell beside the bottom he waits in (no lid at all): the way out starts back under the lid
        if (noLid) { // nothing to cap the hole with: an open pit is a shooting gallery (five arrows down it took him 20 → 6); a player steps sideways under the overhang instead
            Direction side = null;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos a = feet.below(3).relative(d), b = feet.below(2).relative(d), roof = feet.below(1).relative(d), floor = feet.below(4).relative(d);
                boolean ok = true;
                for (BlockPos q : new BlockPos[] {a, b}) { BlockState st = level.getBlockState(q); ok &= (st.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_SHOVEL) || (st.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_PICKAXE) && !g.inventory.find(i -> i.isCorrectToolForDrops(st)).isEmpty())) && !st.hasBlockEntity() && level.getFluidState(q).isEmpty(); }
                ok &= !level.getBlockState(roof).getCollisionShape(level, roof).isEmpty() && !level.getBlockState(floor).getCollisionShape(level, floor).isEmpty();
                if (ok) { side = d; break; }
            }
            if (side != null) {
                steps.add(new Task.Break(feet.below(3).relative(side))); steps.add(new Task.Break(feet.below(2).relative(side)));
                steps.add(new Task.GoTo(feet.below(3).relative(side), 0.5)); // tucked in under the rock, out of the line of fire from the rim
                tuckedAt = feet.below(3).relative(side).immutable();
            }
        }
        steps.add(new Task.Wait(400, false));
        if (tuckedAt != null) { steps.add(new Task.GoTo(feet.below(3).immutable(), 0.5)); com.claudemod.ClaudeMod.LOGGER.info("[Claude life] tucked in at {} beside the hole's bottom: its way out walks him back under the lid at {} first, then the lid out and the climb (WorkGoal.digInFromArrows)", tuckedAt.toShortString(), feet.below(3).toShortString()); } // (the climb from the tuck had the overhang over his head: "no head room", the exit dropped, three down at first light)
        steps.addAll(java.util.List.of(new Task.Break(feet.below()), new Task.Pillar(3)));
        for (int i = steps.size() - 1; i >= 0; i--) g.tasks.addFirst(steps.get(i));
        coversMade++; digInAt = feet.immutable(); digInTick = g.tickCount; digInTasks = steps;
        g.pillarBlocks.addFirst(feet.below().immutable()); // the lid: on his list too
        com.claudemod.ClaudeMod.LOGGER.info("[Claude life] {} in the open: digging in at {} to wait it out (nearest melee {}, the hole needs {}+{})", shooters.isEmpty() ? "mobs on him" : "skeleton shooting at him", feet.toShortString(), melee >= 99 ? "none" : String.format("%.1f", melee), String.format("%.1f", holeNeed), melee < holeNeed ? "; boxed in after " + ticks / 20 + " s of running" : "");
        return true;
    }


    // ---- endermen from a hole (the user's pearl stage): 2 down in a 1x1 hole, his head under the ground, an enderman (3 tall) can't reach in or follow; he hits its legs ----
    private BlockPos emHole; private int emSaid = -100;
    private boolean inEmHole() {
        ServerLevel level = (ServerLevel) g.level(); BlockPos f = g.blockPosition();
        if (!level.getBlockState(f.above(2)).getCollisionShape(level, f.above(2)).isEmpty()) return false;
        for (Direction d : Direction.Plane.HORIZONTAL) for (int y = 0; y <= 1; y++) { BlockPos q = f.above(y).relative(d); if (level.getBlockState(q).getCollisionShape(level, q).isEmpty()) return false; }
        return true;
    }
    private Boolean endermanHole(Task.Attack t, net.minecraft.world.entity.monster.EnderMan em) {
        ServerLevel level = (ServerLevel) g.level(); BlockPos f = g.blockPosition();
        double d = em.distanceTo(g);
        if (inEmHole()) {
            emHole = f.immutable(); g.getNavigation().stop(); g.setSprinting(false); equipWeapon();
            g.getLookControl().setLookAt(em.getX(), em.getY() + 0.6, em.getZ()); // (its legs: what reaches down to him)
            if (d > 24) { if (g.tickCount - emSaid > 100) { emSaid = g.tickCount; com.claudemod.ClaudeMod.LOGGER.info("[Claude fight] the enderman went off {} away: out of the hole (WorkGoal.endermanHole)", (int) d); } g.tasks.addFirst(new Task.Pillar(2)); return true; }
            if (g.getHealth() < 8) { var food = com.claudemod.director.ClaudeLife.foodToEat(g, false); if (food != null) { g.tasks.addFirst(new Task.Attack(t.target(), Math.max(200, t.maxTicks() - ticks))); g.tasks.addFirst(new Task.Eat(food)); return true; } }
            if (canHitNow(em)) { g.getLookControl().setLookAt(em.getX(), em.getY() + 0.6, em.getZ()); g.attack(em); g.swing(InteractionHand.MAIN_HAND); }
            else if (em.getTarget() != g && d < 16) g.getLookControl().setLookAt(em.getX(), em.getEyeY(), em.getZ()); // (not angry yet: a look at its eyes brings it over, the player's way)
            if (g.tickCount - emSaid > 200) { emSaid = g.tickCount; com.claudemod.ClaudeMod.LOGGER.info("[Claude fight] in my hole at {}: the enderman {} off (health {}) (WorkGoal.endermanHole)", f.toShortString(), (int) d, (int) g.getHealth()); }
            return false;
        }
        if (d < 4 || !g.onGround()) return null; // (too close to dig in: the ordinary fight)
        BlockPos b1 = f.below(), b2 = f.below(2);
        for (BlockPos q : new BlockPos[] {b1, b2}) { var s0 = level.getBlockState(q); if (s0.getCollisionShape(level, q).isEmpty() || s0.getDestroySpeed(level, q) < 0 || s0.getDestroySpeed(level, q) > 4 || s0.hasBlockEntity()) return null; for (Direction dd : Direction.values()) if (!level.getFluidState(q.relative(dd)).isEmpty()) return null; }
        if (level.getBlockState(f.below(3)).getCollisionShape(level, f.below(3)).isEmpty()) return null; // (a floor under the hole)
        for (Direction dd : Direction.Plane.HORIZONTAL) for (BlockPos q : new BlockPos[] {b1.relative(dd), b2.relative(dd)}) if (level.getBlockState(q).getCollisionShape(level, q).isEmpty()) return null; // (its walls whole)
        g.tasks.addFirst(new Task.Attack(t.target(), Math.max(200, t.maxTicks() - ticks)));
        g.tasks.addFirst(new Task.Break(b2)); g.tasks.addFirst(new Task.Break(b1));
        com.claudemod.ClaudeMod.LOGGER.info("[Claude fight] an enderman {} off: 2 down into a hole at {} first (it can't reach in), then its legs (WorkGoal.endermanHole)", (int) d, f.toShortString());
        return true;
    }
    private final java.util.Map<java.util.UUID, Integer> behindWall = new java.util.HashMap<>(); // (mobs let be behind a wall, when)
    private boolean doAttack(Task.Attack t) {
        ServerLevel level = (ServerLevel) g.level();
        var e = level.getEntity(t.target());
        if (!(e instanceof net.minecraft.world.entity.LivingEntity mob) || !mob.isAlive()
                || ticks > t.maxTicks() || g.distanceTo(mob) > 64) {
            if (ticks <= 2) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] attack on {} over at once: {}", e == null ? "?" : e.getType().getDescription().getString(), e == null ? "gone" : !(e instanceof net.minecraft.world.entity.LivingEntity l2) ? "not a mob" : !l2.isAlive() ? "dead" : ticks > t.maxTicks() ? "ticks " + ticks + " > " + t.maxTicks() : "too far");
            g.setSprinting(false);
            // it died: grab what it dropped (beef, leather...) before moving on, like a player would
            boolean died = e == null || (e instanceof net.minecraft.world.entity.LivingEntity l && !l.isAlive());
            if (died && ticks > 1 && e instanceof net.minecraft.world.entity.monster.EnderMan && inEmHole()) { g.tasks.addFirst(new Task.Pillar(2)); g.tasks.addFirst(new Task.Pickup(40)); com.claudemod.ClaudeMod.LOGGER.info("[Claude fight] the enderman's down: its pearl, then up out of the hole (WorkGoal.doAttack)"); }
            if (died && ticks > 1 && e != null) com.claudemod.ClaudeMod.LOGGER.info("[Claude fight] killed the {} at {} (WorkGoal.doAttack)", e.getType().getDescription().getString(), e.blockPosition().toShortString()); // (every kill on his log: his chat leads with it, MindLog.recent)
            if (ticks > 1) { // his own arrows stuck round the fight: walked over and taken back, like a player does (01:47 26 shot, none picked up); the drops first
                var mine = level.getEntitiesOfClass(net.minecraft.world.entity.projectile.AbstractArrow.class, g.getBoundingBox().inflate(16, 6, 16), a -> a.isAlive() && a.getOwner() == g && a.pickup == net.minecraft.world.entity.projectile.AbstractArrow.Pickup.ALLOWED && a.getDeltaMovement().lengthSqr() < 1e-4 && level.getFluidState(a.blockPosition()).isEmpty());
                mine.sort(java.util.Comparator.comparingDouble(a -> -a.distanceToSqr(g))); // (addFirst: the nearest ends up first)
                for (var a : mine) g.tasks.addFirst(new Task.GoTo(a.blockPosition(), 1.0));
                if (!mine.isEmpty()) com.claudemod.ClaudeMod.LOGGER.info("[Claude fight] {} of his arrows stuck round the fight: picking them back up (WorkGoal.doAttack)", mine.size());
            }
            if (died && ticks > 1 && !(g.tasks.peek() instanceof Task.Pickup)) g.tasks.addFirst(new Task.Pickup(80));
            return true;
        }
        { Integer bw = behindWall.get(mob.getUUID()); if (bw != null && g.tickCount - bw < 20 * 30 && !g.hasLineOfSight(mob)) return true; } // (one he let be behind a wall: whoever queues the fight again, it ends here while he still can't see it)
        if (ticks == 1) dropCloseSteps(); // (a closing-in step cut short by a new fight: stale, it would walk him to where the shooter was)
        if (ticks == 1 && mob instanceof net.minecraft.world.entity.animal.WaterAnimal && !level.getFluidState(mob.blockPosition().above(6)).isEmpty() && g.distanceTo(mob) > 3) { // (6 deep now, as the hunt picks them) // a fish deep down: no reaching it from the bank (100 s at half a heart swinging at cod he couldn't get to); the land animals come next
            g.attempts.any.put(mob.blockPosition().immutable(), g.tickCount);
            return true;
        }
        if (mob instanceof net.minecraft.world.entity.animal.IronGolem ig && ig.getTarget() != g && !outOfItsReach(mob) && t.maxTicks() >= 20 * 60) { // the golem job on the ground (the tower didn't go up: it walked into him): no fight down here with a golem that isn't after him, the tower again
            if (!mob.getUUID().equals(golemRetryFor)) { golemRetryFor = mob.getUUID(); golemRetries = 0; }
            if (golemRetries++ < 6) { g.skillQueue.addFirst(new com.claudemod.entity.life.Skills.Step("village_iron", "", 1)); com.claudemod.ClaudeMod.LOGGER.info("[Claude life] on the ground by the golem (the tower didn't go up): the tower again beside it"); return true; }
        }
        { Boolean cf = coverFight(t, mob); if (cf != null) return cf; }
        if (mob instanceof net.minecraft.world.entity.monster.EnderMan em) { Boolean eh = endermanHole(t, em); if (eh != null) return eh; } // (the pearl stage: from a 2-deep hole it can't reach into) // a blaze or a wither skeleton: from cover, like an expert (FortressFight)
        // losing the fight: break off and get away, like a player would (fighting on at 3 hearts is how you die)
        if ((mob instanceof net.minecraft.world.entity.monster.Monster || mob instanceof net.minecraft.world.entity.animal.IronGolem) && shouldFlee(mob)) {
            g.tasks.addFirst(new Task.Flee(160));
            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] breaking off the fight (health {}, {} within 10; {}) (WorkGoal.doAttack)", (int) g.getHealth(), hostilesNear(10).size(), fightOdds);
            return true;
        }
        if (shootAt(mob)) return false; // bow work: it stays out there and dies, or it closes and the sword takes over
        equipWeapon();
        g.getLookControl().setLookAt(mob);
        // A hissing creeper: back off until it calms down, then go back in.
        if (mob instanceof net.minecraft.world.entity.monster.Creeper creeper && creeper.getSwellDir() > 0 && canHitNow(creeper)) {
            g.getLookControl().faceNow(creeper.getBoundingBox().getCenter()); // hissing but in reach, swing ready: hit it (the knockback stops it), then back off
            g.attack(creeper);
            g.swing(InteractionHand.MAIN_HAND);
            Vec3 back = g.position().subtract(creeper.position()).normalize().scale(4).add(g.position());
            g.getNavigation().moveTo(back.x, back.y, back.z, 1.3);
            return false;
        }
        if (mob instanceof net.minecraft.world.entity.monster.Creeper creeper && creeper.getSwellDir() > 0) {
            Vec3 away = g.position().subtract(creeper.position()).normalize().scale(6).add(g.position());
            if (ticks % 5 == 0) g.getNavigation().moveTo(away.x, away.y, away.z, 1.3);
            return false;
        }
        // Shield work, like a player with one in his off hand: up (facing it) when a shot or a hit is coming, down to swing
        // and to move at full speed. A raised shield walks at a fifth, so it's never just held up through a chase.
        if (incoming(mob)) shieldUp(mob); else shieldDown();
        // In reach like a player (3 blocks from his eyes to its hitbox, clear line): hit it, even up a ledge;
        // the knockback can knock it off the edge to him.
        boolean inReach = mob.getBoundingBox().distanceToSqr(g.getEyePosition()) <= 9.0 && g.hasLineOfSight(mob);
        if (inReach) {
            chaseBest = Double.MAX_VALUE; chaseBestTick = ticks; // (in reach: the chase starts over once it runs off again)
            g.setSprinting(false);
            if (mob.getBoundingBox().distanceToSqr(g.getEyePosition()) < 2.0) g.getNavigation().stop();
            // Hit and step back, like a player: his reach (3) beats a zombie's (~2), so between swings he backs out of its bite, still facing it, and swings again as it walks back in
            boolean meleeMob = !(mob instanceof net.minecraft.world.entity.monster.Creeper) && !(mob instanceof net.minecraft.world.entity.monster.AbstractSkeleton) && !(mob instanceof net.minecraft.world.entity.monster.Pillager);
            if (meleeMob && g.getAttackStrengthScale(0.5f) < 0.95f && mob.distanceTo(g) < 2.6 && g.onGround()) {
                Vec3 back = g.position().subtract(mob.position()).multiply(1, 0, 1);
                if (back.length() > 0.01) {
                    back = back.normalize().scale(1.6).add(g.position());
                    BlockPos footing = BlockPos.containing(back).below();
                    if (!g.level().getBlockState(footing).getCollisionShape(g.level(), footing).isEmpty() && g.level().getFluidState(footing.above()).isEmpty()) { // (never backed off a ledge or into water)
                        g.getNavigation().stop();
                        g.getMoveControl().setWantedPosition(back.x, g.getY(), back.z, 1.15);
                        g.getLookControl().setLookAt(mob, 60f, 60f);
                        return false;
                    }
                }
            }
            // Swing once the attack is charged, like a player who doesn't spam-click (the game's own cooldown: ~0.6s sword, ~1s axe).
            if (g.getAttackStrengthScale(0.5f) >= 0.95f && mob.getBoundingBox().distanceToSqr(g.getEyePosition()) <= 9.0) { // (3 to the nearest point of it, like a player's reach: to its middle, a golem from his tower top read as out of reach)
                reachWaitStart = -1;
                int cj = critJump().getOrDefault(g.getUUID(), -100);
                if (g.onGround() && !g.isInWater() && !g.isPassenger() && (mob instanceof net.minecraft.world.entity.monster.Monster || mob instanceof net.minecraft.world.entity.animal.IronGolem) && g.getHealth() > 8 && g.getFoodData().getFoodLevel() > 6 && g.tickCount - cj > 20) { critJump().put(g.getUUID(), g.tickCount); g.getJumpControl().jump(); return false; } // the jump first: the swing on the way down is a critical hit (x1.5), the way a player fights
                if (!g.onGround() && g.getDeltaMovement().y > 0.0 && g.tickCount - cj <= 12) return false; // still going up: the crit wants the fall
                g.getLookControl().faceNow(mob.getBoundingBox().getCenter());
                shieldDown(); // a swing lowers the shield (it goes back up next tick if it's still needed)
                g.attack(mob);
                g.swing(InteractionHand.MAIN_HAND);
                if (!mob.isAlive()) g.noteDoing("killed a " + net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).getPath());
            }
            return false;
        }
        if (ticks % 8 != 1) return false; // re-think the chase a few times a second (it moves)
        if (mob instanceof net.minecraft.world.entity.animal.Animal) { // a chase goes on while he's closing in: 5 s with no new closest approach (it keeps its distance up a slope, across water, behind a fence) and it's not to be had on foot as things stand; the next one (the hunt's choice skips it for a while)
            double dNow = g.distanceTo(mob);
            if (ticks <= 1 || dNow <= 4) { chaseBest = Double.MAX_VALUE; chaseBestTick = ticks; } // (at arm's length it's no chase: the leaves or the ledge in the way are the code below's)
            else if (dNow < chaseBest - 0.5) { chaseBest = dNow; chaseBestTick = ticks; }
            else if (ticks - chaseBestTick > 100) {
                com.claudemod.director.ClaudeLife.preyOutNow(g, mob, chaseBest);
                com.claudemod.ClaudeMod.LOGGER.info("[Claude life] not closing on the {} ({} off, {} at best, 5 s): the next one", mob.getType().getDescription().getString(), (int) dNow, (int) chaseBest);
                g.setSprinting(false); g.getNavigation().stop();
                return true;
            }
        }
        if (mob.isInWater() && (g.isInWater() || mob.getY() < g.getY() - 0.5)) { // in the water (a fish, a drowned): swim straight at it like a player, down to it and up for air when it's needed; no path, and no digging the river bed for it
            Vec3 to = mob.getBoundingBox().getCenter();
            g.getNavigation().stop();
            g.setSprinting(false);
            g.getLookControl().setLookAt(to.x, to.y, to.z, 30, 30);
            g.getMoveControl().setWantedPosition(to.x, to.y, to.z, 1.0);
            return false;
        }
        // Close but something's between them (a chicken under a tree, behind a block): clear it, like a player
        // knocking the leaves away to get at it. Never their builds.
        if (g.distanceTo(mob) < 5.5 && !g.hasLineOfSight(mob)) {
            BlockPos inWay = blockInTheWay((ServerLevel) g.level(), BlockPos.containing(mob.getBoundingBox().getCenter()));
            if (inWay != null) {
                BlockState w = g.level().getBlockState(inWay);
                boolean theirs = g.getTarget() instanceof net.minecraft.server.level.ServerPlayer o
                        && (com.claudemod.context.PlayerProfile.placedBy(o).contains(inWay) || com.claudemod.director.ClaudeLife.protectedBlock(g, o, inWay));
                if (!theirs && w.getDestroySpeed(g.level(), inWay) >= 0 && !w.hasBlockEntity() && !w.is(net.minecraft.tags.BlockTags.LOGS) && com.claudemod.director.Structures.intactKind(w) == null) { // (never a fortress's brick: round, or through its doorway) // (never a log: a cut through a trunk to hit a mob behind it left two birches with a log gone out of the middle; round the tree instead)
                    boolean soft = w.is(net.minecraft.tags.BlockTags.LEAVES) || w.getDestroySpeed(g.level(), inWay) <= 0.6f; // (leaves over a chicken, dirt or sand: what a player knocks away for a mob; never rock between him and it)
                    boolean shell = com.claudemod.director.ClaudeLife.bpMode(g) && g.blueprint != null && !g.blueprint.isEmpty() && g.blueprint.isShell(inWay);
                    if (!soft || shell || com.claudemod.entity.life.Watchdog.off(g, new Task.Break(inWay)) || g.attempts.within(com.claudemod.entity.life.Attempts.Kind.BREAK, inWay, g.tickCount, 20 * 60)) { // (World 15 16:44: digs through his own mine wall toward a mob behind it, each refused by the shell rule and asked for again every second, 235 lines)
                        g.attempts.fail(com.claudemod.entity.life.Attempts.Kind.REACH, mob.blockPosition(), g.tickCount); behindWall.put(mob.getUUID(), g.tickCount);
                        com.claudemod.ClaudeMod.LOGGER.info("[Claude life] the {} is behind {} at {} ({}): no fight through it, let be (WorkGoal.doAttack)", mob.getType().getDescription().getString(), name(w), inWay.toShortString(), shell ? "his mine's wall" : !soft ? "rock" : "its dig refused a moment ago");
                        return true;
                    }
                    g.tasks.addFirst(new Task.Attack(t.target(), Math.max(100, t.maxTicks() - ticks)));
                    g.tasks.addFirst(new Task.Break(inWay));
                    return true;
                }
            }
        }
        BlockPos feet0 = g.blockPosition();
        if ((g.pillarBlocks.contains(feet0.below()) || g.pillarBlocks.contains(feet0.below(2))) && outOfItsReach(mob)) { // (one or two over his tower block: settling onto the top counts)
            g.getNavigation().stop(); g.getLookControl().setLookAt(mob);
            if (reachWaitStart < 0 || ticks < reachWaitTicks) { reachWaitStart = g.tickCount; }
            reachWaitTicks = ticks;
            if (mob instanceof net.minecraft.world.entity.animal.IronGolem && ((net.minecraft.world.entity.Mob) mob).getTarget() != g && g.tickCount - reachWaitStart > 60) { // 3 s up there and no swing at it, and it isn't after him (so it won't come over): down, and up again beside it
                if (!mob.getUUID().equals(golemRetryFor)) { golemRetryFor = mob.getUUID(); golemRetries = 0; }
                if (g.inventory.count(s0 -> s0.is(net.minecraft.world.item.Items.BOW)) > 0 && g.inventory.count(s0 -> s0.is(net.minecraft.world.item.Items.ARROW)) > 0 && mob.distanceTo(g) <= 20) { lureShot = true; boolean shot = shootAt(mob); lureShot = false; if (shot) return false; } // an arrow from up here: it comes for him and stands under the tower, in reach
                if (golemRetries++ < 6) { reachWaitStart = -1; g.skillQueue.addFirst(new com.claudemod.entity.life.Skills.Step("village_iron", "", 1)); com.claudemod.ClaudeMod.LOGGER.info("[Claude life] the golem is out of reach of his tower and not after him: down, and up again beside it"); return true; }
            }
            return false;
        } // up his own tower over something that can't get him: he waits for it to come back in reach (off the tower after it is into its reach)
        // Can he walk to it (up the hill, around)? Then sprint there.
        var path = g.getNavigation().createPath(mob, 1);
        if (path != null && path.canReach()) {
            g.setSprinting(true);
            g.getNavigation().moveTo(path, 1.3);
            return false;
        }
        // Can't walk there. Up on a ledge right above him: tower up to it (once per target).
        double dy = mob.getY() - g.getY();
        double horiz = Math.sqrt(Math.pow(mob.getX() - g.getX(), 2) + Math.pow(mob.getZ() - g.getZ(), 2));
        // No way to it on foot, and it shoots, or it's down a drop: never edge toward it (he chased a skeleton to the lip of a
        // mineshaft and went down it, 6 from the fall, then shot dead). Out of its sight, and it comes to him; a melee mob down
        // a drop can't get at him either
        boolean shoots = mob instanceof net.minecraft.world.entity.monster.RangedAttackMob || mob instanceof net.minecraft.world.entity.monster.Blaze || mob instanceof net.minecraft.world.entity.monster.Ghast; // (a blaze and a ghast shoot too, though not RangedAttackMobs in the game's code: a blaze was chased by a 1-wide bridge over the lava sea, World 15 13:41)
        if ((shoots && !(dy > 1.5 && horiz < 3.0)) || dy <= -4) {
            g.setSprinting(false);
            if (shoots) { int cl = closeOnShooter(mob, t); if (cl == 1) return false; if (cl == 2) return true; } // the expert closes in: a crossbow piglin holds 5-8 off and never comes round (live 16:23, 25 arrows while he "waited" for it)
            if (shoots && g.hasLineOfSight(mob)) {
                BlockPos cover = coverFrom(mob);
                if (cover != null) {
                    if (!cover.equals(lastCover)) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] can't get at the {} on foot ({} below, {} off): out of its sight at {}", mob.getType().getDescription().getString(), (int) -dy, (int) horiz, cover.toShortString());
                    lastCover = cover.immutable();
                    g.getNavigation().moveTo(cover.getX() + 0.5, cover.getY(), cover.getZ() + 0.5, 1.2);
                    return false;
                }
            }
            g.getNavigation().stop();
            if (shoots) return false; // out of its sight (or nowhere better): wait for it to come round, the fight's clock ends it
            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] the {} is {} below with no way down on foot: leaving it", mob.getType().getDescription().getString(), (int) -dy);
            return true;
        }
        if (dy > 1.5 && dy <= 2.5 && horiz < 2.5 && g.onGround()) { // a jump puts it in reach: jump and swing
            g.getMoveControl().setWantedPosition(mob.getX(), g.getY(), mob.getZ(), 1.0);
            g.getJumpControl().jump();
            return false;
        }
        if (dy > 1.5 && horiz < 3.0 && !mob.getUUID().equals(lastPillarMob) && g.onGround()) {
            lastPillarMob = mob.getUUID();
            g.setSprinting(false);
            g.tasks.addFirst(new Task.Attack(t.target(), Math.max(100, t.maxTicks() - ticks)));
            g.tasks.addFirst(new Task.Pillar((int) Math.max(1, Math.round(dy) - 1)));
            return true;
        }
        // Otherwise get as close as walking allows, then bridge the rest of the way.
        if (pathGetsCloser(path, mob.position())) { g.setSprinting(true); g.getNavigation().moveTo(path, 1.3); return false; }
        g.setSprinting(false);
        bridgeToward(mob.position());
        return false;
    }

    // ---- fortress fights from cover (FortressFight; patch_blaze_loot.py) ----
    private String cfMode = ""; private int cfPeekTick = -100, cfHopTick = -100, cfIdle; private boolean cfHealing; private java.util.UUID cfTarget;
    private int cfEmptyPeeks; // steps out to draw a lit blaze's shots that drew none
    private void cfSay(String mode, String why) { if (!mode.equals(cfMode)) { cfMode = mode; com.claudemod.ClaudeMod.LOGGER.info("[Claude fight] {}: {} (WorkGoal.coverFight)", mode, why); } }
    // cfMove: the last step goes straight to the centre: the path calls itself done ~0.5 short, over the cell edge (01:30 he stood at -72.0, cover -73, burning 13 -> 1)
    private void cfMove(BlockPos to, double speed) { if (Math.abs(g.getY() - to.getY()) < 0.6 && Math.hypot(g.getX() - to.getX() - 0.5, g.getZ() - to.getZ() - 0.5) < 1.6) { g.getNavigation().stop(); g.getMoveControl().setWantedPosition(to.getX() + 0.5, to.getY(), to.getZ() + 0.5, speed); return; } g.getNavigation().moveTo(to.getX() + 0.5, to.getY(), to.getZ() + 0.5, speed); } // (in his own cell the path is "done" at once and never centres him: at() wants 0.35, so "back into cover" held him off-centre in sight, burning 13 -> 1, the Eat never reached)
    /** The swing when charged, the hop first for the crit (a 2-high ceiling caps the hop; the fall still crits). True: swung or hopping. */
    private boolean cfSwing(net.minecraft.world.entity.LivingEntity mob) {
        if (g.getAttackStrengthScale(0.5f) < 0.95f) return false;
        if (g.onGround() && !g.isInWater() && g.tickCount - cfHopTick > 14 && g.getHealth() > 6) { cfHopTick = g.tickCount; g.getJumpControl().jump(); return true; }
        if (!g.onGround() && g.getDeltaMovement().y > 0 && g.tickCount - cfHopTick <= 8) return true; // still going up: the crit wants the fall
        g.getLookControl().faceNow(mob.getBoundingBox().getCenter());
        shieldDown(); float hp0 = mob.getHealth(); g.attack(mob); g.swing(InteractionHand.MAIN_HAND);
        com.claudemod.ClaudeMod.LOGGER.info("[Claude fight] swing at the {} {} off (eye {}): hp {} -> {}, invuln {}, his fall {} (WorkGoal.cfSwing)", BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).getPath(), String.format("%.2f", mob.distanceTo(g)), String.format("%.2f", g.getEyePosition().distanceTo(mob.getBoundingBox().getCenter())), hp0, mob.getHealth(), mob.invulnerableTime, String.format("%.2f", g.fallDistance));
        if (!mob.isAlive()) g.noteDoing("killed a " + BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).getPath());
        return true;
    }
    /** A blaze in his reach: kept 2.4+ off (its touch is 2), hit when the swing's charged, the shield up between swings when a volley is due. */
    private void cfClose(ServerLevel level, net.minecraft.world.entity.monster.Blaze bz, net.minecraft.world.entity.LivingEntity due, boolean shield) {
        g.setSprinting(false); g.getLookControl().setLookAt(bz);
        if (bz.distanceTo(g) < 2.4 && g.onGround()) { // back out of its touch, his reach (3) still on it
            Vec3 back = g.position().subtract(bz.position()).multiply(1, 0, 1); if (back.length() < 0.01) back = g.getLookAngle().scale(-1).multiply(1, 0, 1);
            back = back.normalize().scale(1.5).add(g.position()); BlockPos bf = BlockPos.containing(back.x, g.getY() + 0.2, back.z);
            if (com.claudemod.entity.life.FortressFight.stand((ServerLevel) g.level(), bf)) { g.getNavigation().stop(); g.getMoveControl().setWantedPosition(back.x, g.getY(), back.z, 1.2); }
            else g.getNavigation().stop();
        } else g.getNavigation().stop();
        if (g.getAttackStrengthScale(0.5f) >= 0.95f) { cfSwing(bz); cfSay("hitting the blaze", String.format("%.1f", bz.distanceTo(g)) + " off (its touch is 2, his reach 3), " + (bz.isOnFire() ? "lit " + com.claudemod.entity.life.FortressFight.chargeAge(g, bz) + " ticks" : "quiet")); return; }
        if (shield && due != null) shieldUp(due); // between swings: the volley on the shield
    }
    /**
     * Blazes and wither skeletons, fought like an expert: from cover that breaks a blaze's sight (its volley only runs while it
     * sees him) or a 2-high gap a wither skeleton can't reach into. Null: not that kind of mob, or no cover here (the ordinary fight
     * goes on, shield and crits).
     */
    private Boolean coverFight(Task.Attack t, net.minecraft.world.entity.LivingEntity mob) {
        if (!com.claudemod.entity.life.FortressFight.coverMob(mob)) return null;
        ServerLevel level = (ServerLevel) g.level();
        boolean blaze = mob instanceof net.minecraft.world.entity.monster.Blaze;
        if (ticks == 1 && !mob.getUUID().equals(cfTarget)) { cfTarget = mob.getUUID(); cfMode = ""; cfIdle = 0; } // (a fight carried on past its clock keeps its counters)
        if (ticks >= t.maxTicks() - 1 && cfIdle < 20 * 20 && mob.distanceTo(g) < 24) { ticks = 2; com.claudemod.ClaudeMod.LOGGER.info("[Claude fight] the {} is still on: the same fight goes on from cover past its {} s (WorkGoal.coverFight)", mob.getType().getDescription().getString(), t.maxTicks() / 20); } // (in place: a new task left a tick with no fight, and self-defence armed a second, plain one on the blaze) // (a fight from cover is slow by design: its clock running out left him idle in the gap and the follow rule walked him out)
        var cv = com.claudemod.entity.life.FortressFight.coverFor(g, level, mob);
        if (cv == null) { if (ticks == 1) cfSay("in the open", "no cover for this " + mob.getType().getDescription().getString() + " here: the ordinary fight, shield and crits"); return null; }
        equipWeapon();
        BlockPos feet = BlockPos.containing(g.getX(), g.getY() + 0.2, g.getZ());
        boolean inCover = com.claudemod.entity.life.FortressFight.at(g, cv.cover());
        boolean shield = g.getItemBySlot(EquipmentSlot.OFFHAND).is(net.minecraft.world.item.Items.SHIELD) && !g.getCooldowns().isOnCooldown(net.minecraft.world.item.Items.SHIELD);
        // fire under him: off it, now
        if (BlockPos.betweenClosedStream(g.getBoundingBox().deflate(0.001)).anyMatch(p -> level.getBlockState(p).is(net.minecraft.tags.BlockTags.FIRE))) { // any fire his box touches (the next cell's fire burns him too; it held him on his shield in it, 5 -> 1)
            BlockPos to = com.claudemod.entity.life.FortressFight.stand(level, feet) ? feet : com.claudemod.entity.life.FortressFight.stand(level, cv.cover()) && cv.cover().distSqr(feet) <= 9 ? cv.cover() : null; // (stand() is no fire; the centre of his own cell clears a neighbour's)
            if (to == null) for (int dx = -1; dx <= 1 && to == null; dx++) for (int dz = -1; dz <= 1 && to == null; dz++) { BlockPos q = feet.offset(dx, 0, dz); if (!q.equals(feet) && com.claudemod.entity.life.FortressFight.stand(level, q)) to = q; }
            if (to == null) to = cv.peek();
            shieldDown(); g.setSprinting(true); if (to.equals(feet)) { g.getNavigation().stop(); g.getMoveControl().setWantedPosition(to.getX() + 0.5, to.getY(), to.getZ() + 0.5, 1.3); } else cfMove(to, 1.3); cfSay("off the fire", "standing in fire at " + feet.toShortString() + ": to " + to.toShortString()); return false; }
        // hurt: into cover, Wither waited out (it outruns regen), milk if he has it, food
        boolean withered = g.hasEffect(net.minecraft.world.effect.MobEffects.WITHER);
        float hp = g.getHealth();
        var due = blaze ? com.claudemod.entity.life.FortressFight.volleyDue(g, level) : null; // a blaze lit 44+ in sight of him, or a fireball his way
        if (g.tasks.peek() instanceof Task.Eat eatNext && due == null && (inCover || (!blaze && level.getEntitiesOfClass(net.minecraft.world.entity.monster.WitherSkeleton.class, g.getBoundingBox().inflate(4), net.minecraft.world.entity.Entity::isAlive).isEmpty())) && g.getFoodData().getFoodLevel() < 20) { // (eating drops the shield: out of cover with a sword in reach it waits — World 15 02:04, a meal at hunger 19 with skeletons on him, shield down, 17 -> 0) // a meal queued behind this fight: it goes first, the fight after it (World 15 01:29: "next: [Eat]" 400+ ticks behind an Attack that kept renewing past its clock, 13 HP down to 1)
            g.tasks.pollFirst(); g.tasks.addFirst(new Task.Attack(t.target(), Math.max(200, t.maxTicks() - ticks))); g.tasks.addFirst(eatNext);
            cfSay("eating first", "a meal was waiting behind the fight (hunger " + g.getFoodData().getFoodLevel() + ", health " + (int) hp + "), no volley due" + (inCover ? ", in cover" : ""));
            return true;
        }
        if (blaze) { // a blaze at arm's length (inside 2 it touches for 6 and sets him alight): hit it and keep it 2.4+ off; never his back to it, hurt or not
            net.minecraft.world.entity.monster.Blaze near = null; for (var b2 : level.getEntitiesOfClass(net.minecraft.world.entity.monster.Blaze.class, g.getBoundingBox().inflate(4), net.minecraft.world.entity.Entity::isAlive)) if (b2.distanceTo(g) < 3.0 && g.hasLineOfSight(b2) && (near == null || b2.distanceTo(g) < near.distanceTo(g))) near = b2;
            if (near != null && near.getBoundingBox().distanceToSqr(g.getEyePosition()) <= 9.0) { cfClose(level, near, due, shield); return false; }
        }
        if (hp <= 12 || (withered && hp <= 14) || (g.isOnFire() && hp <= 14) || (cfHealing && (hp < 18 || withered))) { // (an expert backs off with health to spare and heals all the way: food's plenty)
            cfHealing = true;
            if (!inCover && due != null && shield) { g.getNavigation().stop(); shieldUp(due); cfSay("shield up on the way back", "health " + (int) hp + ", a volley due: on the shield, not on his back"); return false; }
            if (!inCover) { shieldDown(); g.setSprinting(true); cfMove(cv.cover(), 1.3); cfSay("back into cover", "at " + String.format("%.2f,%.2f", g.getX(), g.getZ()) + ", health " + (int) hp + (withered ? ", Wither on" : "") + (g.isOnFire() ? ", burning" : "") + ": to " + cv.cover().toShortString()); return false; }
            g.getNavigation().stop(); g.setSprinting(false); g.getLookControl().setLookAt(mob);
            if (withered && g.inventory.count(s -> s.is(net.minecraft.world.item.Items.MILK_BUCKET)) > 0) { g.tasks.addFirst(new Task.Attack(t.target(), Math.max(200, t.maxTicks() - ticks))); g.tasks.addFirst(new Task.Eat(net.minecraft.world.item.Items.MILK_BUCKET)); cfSay("milk", "Wither on at health " + (int) hp + ": the milk clears it"); return true; }
            net.minecraft.world.item.Item food = g.getFoodData().getFoodLevel() < 20 ? com.claudemod.director.ClaudeLife.foodToEat(g, hp <= 6) : null;
            if (food != null) { g.tasks.addFirst(new Task.Attack(t.target(), Math.max(200, t.maxTicks() - ticks))); g.tasks.addFirst(new Task.Eat(food)); cfSay("eating in cover", "health " + (int) hp + ", hunger " + g.getFoodData().getFoodLevel() + ": " + BuiltInRegistries.ITEM.getKey(food).getPath()); return true; }
            cfSay("healing in cover", "health " + (int) hp + (withered ? ", waiting the Wither out (it outruns regen)" : "") + " at " + cv.cover().toShortString());
            return false;
        }
        cfHealing = false;
        if (!blaze) { // a wither skeleton: into the gap, and it comes to its edge, in his reach and out of its own
            if (!inCover) { if (mob.distanceTo(g) < 3.2 && shield) shieldUp(mob); else shieldDown(); g.setSprinting(!g.isUsingItem()); cfMove(cv.cover(), g.isUsingItem() ? 1.0 : 1.3); cfSay("into the gap", cv.how()); return false; }
            g.getNavigation().stop(); g.setSprinting(false); shieldDown(); g.getLookControl().setLookAt(mob);
            if (mob.getBoundingBox().distanceToSqr(g.getEyePosition()) <= 9.0 && g.hasLineOfSight(mob)) { cfIdle = 0; if (cfSwing(mob)) cfSay("hitting it from the gap", "it's " + String.format("%.1f", mob.distanceTo(g)) + " off and can't reach in; crits between swings"); return false; }
            if (++cfIdle > 20 * 20) { cfSay("it isn't coming", "20 s in the gap and the wither skeleton stays " + (int) mob.distanceTo(g) + " off: done waiting"); return true; }
            cfSay("waiting in the gap", "it can't reach in; it comes to " + cv.peek().toShortString() + " and gets hit there");
            return false;
        }
        // a blaze: its glow is the clock (lit 60 ticks, then 3 shots 6 apart, only while it sees him; then 100 quiet)
        var bz = (net.minecraft.world.entity.monster.Blaze) mob;
        int pending = -1; for (var b2 : level.getEntitiesOfClass(net.minecraft.world.entity.monster.Blaze.class, g.getBoundingBox().inflate(32), net.minecraft.world.entity.Entity::isAlive)) pending = Math.max(pending, com.claudemod.entity.life.FortressFight.chargeAge(g, b2));
        if (due != null && shield) { g.getNavigation().stop(); g.setSprinting(false); shieldUp(due); cfSay("shield up", "a volley due from the blaze " + (int) due.distanceTo(g) + " off: on the shield, then in"); return false; }
        if (g.hurtTime > 0 || due != null) cfEmptyPeeks = 0; // (a shot came: the peeks draw them)
        if (cfEmptyPeeks >= 3 && g.tickCount - cfPeekTick > 20 * 10) cfEmptyPeeks = 0;
        if (!shield && pending >= 44 && cfEmptyPeeks >= 3) cfSay("no more peeks", "3 steps out drew no shot (a blaze lit with no sight of him stays lit): on with the fight, not out and back");
        if (!shield && pending >= 44 && cfEmptyPeeks < 3) { // no shield and a volley waiting: out of its sight, and its shots drawn one at a time from the cover's edge (World 15 01:34: 23 peeks a minute at a blaze lit 999 ticks, no shot drawn: after 3 empty ones, 10 s of the rest of the fight)
            if (!inCover && !(g.tickCount - cfPeekTick <= 3)) { g.setSprinting(true); cfMove(cv.cover(), 1.3); cfSay("ducking into cover", "a blaze lit " + pending + " ticks: its volley comes the moment it sees him"); return false; }
            if (pending >= 60 && g.tickCount - cfPeekTick > 16) { cfPeekTick = g.tickCount; cfEmptyPeeks++; cfMove(cv.peek(), 1.0); cfSay("drawing its shots", "a step out to " + cv.peek().toShortString() + " and back: each shot wants its sight of him"); return false; }
            if (g.tickCount - cfPeekTick > 3 && !inCover) cfMove(cv.cover(), 1.2);
            return false;
        }
        shieldDown();
        g.getLookControl().setLookAt(mob);
        if (mob.getBoundingBox().distanceToSqr(g.getEyePosition()) <= 9.0 && g.hasLineOfSight(mob)) { cfClose(level, bz, due, shield); return false; }
        int age = com.claudemod.entity.life.FortressFight.chargeAge(g, bz);
        boolean low = mob.getBoundingBox().minY <= g.getY() + 3.2; // a swing from the floor reaches it
        boolean time = shield || age < 0 || age < 24; // quiet, or lit just now: time to get there and back before the shots
        net.minecraft.world.entity.monster.Blaze other = null; for (var b2 : level.getEntitiesOfClass(net.minecraft.world.entity.monster.Blaze.class, g.getBoundingBox().inflate(24), net.minecraft.world.entity.Entity::isAlive)) if (b2 != mob && com.claudemod.entity.life.FortressFight.chargeAge(g, b2) >= 16 && b2.hasLineOfSight(g)) other = b2;
        if (other != null && low && time) { if (shield) { g.getNavigation().stop(); shieldUp(other); } cfSay("one at a time", "another blaze " + (int) other.distanceTo(g) + " off is lit and sees him: no walking out under its volley"); if (!shield && !inCover) cfMove(cv.cover(), 1.3); return false; }
        if (low && time && mob.distanceTo(g) <= 14) {
            Vec3 toMe = g.position().subtract(mob.position()).multiply(1, 0, 1); if (toMe.length() < 0.01) toMe = new Vec3(1, 0, 0);
            Vec3 spot = mob.position().add(toMe.normalize().scale(2.7)); // in his reach, out of its touch
            BlockPos under = BlockPos.containing(spot.x, mob.getY(), spot.z);
            for (int k = 0; k < 6 && !com.claudemod.entity.life.FortressFight.stand(level, under) && under.getY() > g.getBlockY() - 3; k++) under = under.below();
            if (!com.claudemod.entity.life.FortressFight.stand(level, under)) { under = BlockPos.containing(mob.getX(), mob.getY(), mob.getZ()); for (int k = 0; k < 6 && !com.claudemod.entity.life.FortressFight.stand(level, under) && under.getY() > g.getBlockY() - 3; k++) under = under.below(); }
            g.setSprinting(true); cfMove(under, 1.3);
            cfSay("closing in", "the blaze is " + (age < 0 ? "quiet" : "lit " + age + " ticks") + ", " + (int) mob.distanceTo(g) + " off" + (shield ? ", shield ready" : ""));
            return false;
        }
        // out of reach (floating high) or no time: a bow and arrows on him: shot from where he stands while it's quiet (vanilla's blaze hangs back from a player it sees: it never comes to a sword); drawing a bow and holding a shield up can't both be done, so the volley's blocked first (above) and the shot taken between
        if (!low || mob.distanceTo(g) > 14) { boolean drawingBow = g.isUsingItem() && g.getUseItem().is(net.minecraft.world.item.Items.BOW); if (g.hasLineOfSight(mob) && (age < 0 || drawingBow)) { if (shootAt(mob)) { cfIdle = 0; cfSay("shooting it", "the blaze is " + (low ? "" : "up ") + "out of sword reach, " + (int) mob.distanceTo(g) + " off and quiet: arrows"); return false; } } }
        BlockPos wait = shield ? cv.peek() : (age >= 0 ? cv.cover() : cv.peek());
        if (!com.claudemod.entity.life.FortressFight.at(g, wait)) cfMove(wait, 1.1); else g.getNavigation().stop();
        if (++cfIdle > 20 * 45) { cfSay("it stays out of reach", "45 s and the blaze never came in reach: done with this one"); return true; }
        cfSay("waiting at " + (wait.equals(cv.peek()) ? "the cover's edge" : "cover"), (low ? "" : "the blaze is up out of reach, ") + (age >= 0 ? "lit " + age : "quiet") + ", " + (int) mob.distanceTo(g) + " off");
        return false;
    }

    private java.util.List<Task> closeSteps; // the steps the last closing-in put in front of the fight (lazy: a field added live starts null)
    private java.util.Map<String, Integer> closeSaid;
    private void closeSay(String line) { // each kind of line once per 5 s, with the method's name
        if (closeSaid == null || closeSaid.size() > 64) closeSaid = new java.util.HashMap<>();
        String k = line.replaceAll("[-0-9.]+", "#"); Integer at = closeSaid.get(k);
        if (at == null || g.tickCount - at >= 100) { closeSaid.put(k, g.tickCount); com.claudemod.ClaudeMod.LOGGER.info("[Claude life] {} (WorkGoal.closeOnShooter)", line); }
    }
    private void dropCloseSteps() {
        if (closeSteps == null || closeSteps.isEmpty()) return;
        java.util.List<Task> old = closeSteps; closeSteps = null;
        int before = g.tasks.size(); g.tasks.removeIf(x -> { for (Task s : old) if (s == x) return true; return false; });
        if (g.tasks.size() < before) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] {} closing-in step(s) left from a fight cut short: dropped (WorkGoal.dropCloseSteps)", before - g.tasks.size());
    }
    /** Lava within a block of that cell, at it or up to two under (an arrow's knock off the edge): null if none. */
    private static BlockPos lavaBeside(ServerLevel level, BlockPos p) {
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int dy = -2; dy <= 1; dy++) { BlockPos q = p.offset(dx, dy, dz); if (level.getFluidState(q).is(net.minecraft.tags.FluidTags.LAVA)) return q.immutable(); }
        return null;
    }
    /**
     * A shooter the plain path can't reach (down a drop, over a ridge): the way in planned like a player's (walks, steps, drops
     * to 3, digs, blocks put down), never by lava, sized up against its fire. 0: no way worth it (out of its sight instead);
     * 1: walking the way in (the fight goes on); 2: its first steps put in front of the fight (the fight after them).
     */
    private int closeOnShooter(net.minecraft.world.entity.LivingEntity mob, Task.Attack t) {
        ServerLevel level = (ServerLevel) g.level();
        BlockPos feet = BlockPos.containing(g.getX(), g.getY() + 0.2, g.getZ()), to = mob.blockPosition();
        String who = mob.getType().getDescription().getString();
        int below = (int) Math.round(g.getY() - mob.getY()), off = (int) Math.sqrt(Math.pow(mob.getX() - g.getX(), 2) + Math.pow(mob.getZ() - g.getZ(), 2));
        if (com.claudemod.director.Kit.food(g) == 0) { closeSay("no food on me to heal on: not going in after the " + who + " (" + below + " below, " + off + " off), out of its sight instead"); return 0; } /* the user's engagement rule: no picking a fight without food for regen (stage 115: 8 down to a skeleton, a stone axe, no food) */
        ItemStack scaff = solidScaffold();
        java.util.List<Vec3> wide = new java.util.ArrayList<>();
        com.claudemod.entity.nav.ActionPath.Plan plan = null;
        for (int k = 0; k < 3; k++) {
            plan = com.claudemod.entity.nav.ActionPath.plan(g, level, feet, to, 2.0, !scaff.isEmpty(), 3000, com.claudemod.entity.nav.ActionPath.Mode.MAY_DIG, g.attempts.of(com.claudemod.entity.life.Attempts.Kind.WALK), g.tickCount, wide);
            if (plan == null) break;
            BlockPos lava = null;
            for (var a : plan.acts()) {
                BlockPos p = a instanceof com.claudemod.entity.nav.ActionPath.Act.Walk w ? w.feet() : a instanceof com.claudemod.entity.nav.ActionPath.Act.Put pu ? pu.pos().above() : null;
                if (p != null && (lava = lavaBeside(level, p)) != null) break;
                if (p != null && edgeDanger(level, p) != null) { closeSay("the way in to the " + who + " runs along an edge at " + p.toShortString() + " (" + edgeDanger(level, p) + "): planned again wide of it"); lava = p.immutable(); break; } // (a fall or the lava sea off a bridge is death by one arrow's knock)
            }
            if (lava == null) break;
            if (level.getFluidState(lava).is(net.minecraft.tags.FluidTags.LAVA)) closeSay("the way in to the " + who + " runs by lava at " + lava.toShortString() + ": planned again wide of it");
            wide.add(Vec3.atBottomCenterOf(lava)); plan = null;
        }
        if (plan == null) { closeSay("no way in to the " + who + " (" + below + " below, " + off + " off) on foot, by digging or by blocks" + (wide.isEmpty() ? "" : " wide of the lava") + ": out of its sight instead"); return 0; }
        boolean shield = g.getItemBySlot(EquipmentSlot.OFFHAND).is(net.minecraft.world.item.Items.SHIELD);
        double secs = plan.walks() * 0.25 + plan.digs() * 0.6 + plan.puts() * 0.8;
        double take = Math.floor(secs / 2.0) * 4.0 * (1 - Math.min(20, g.getArmorValue()) / 25.0) * (shield ? 0.3 : 1.0); // (a shot every ~2 s on the way in, ~4 a bolt or arrow less his armor; a shield takes most)
        if (g.getHealth() - take < 8) { closeSay(String.format("the way in to the %s (%d below, %d off) is ~%.1f s under its fire (~%.0f damage) at health %d: out of its sight instead", who, below, off, secs, take, (int) g.getHealth())); return 0; }
        var acts = plan.acts();
        if (acts.isEmpty()) return 0;
        String sized = String.format("%d walks %d digs %d blocks, ~%.1f s under its fire at health %d%s", plan.walks(), plan.digs(), plan.puts(), secs, (int) g.getHealth(), shield ? ", shield up while it's loaded" : "");
        if (acts.get(0) instanceof com.claudemod.entity.nav.ActionPath.Act.Walk) { // the walks first: sprinted inside the fight, the next re-think takes the rest
            BlockPos wp = null; int n = 0;
            for (var a : acts) { if (!(a instanceof com.claudemod.entity.nav.ActionPath.Act.Walk w) || n >= 8) break; wp = w.feet(); n++; }
            if (g.getNavigation().moveTo(wp.getX() + 0.5, wp.getY(), wp.getZ() + 0.5, 1.3)) {
                g.setSprinting(g.getFoodData().getFoodLevel() > 6);
                closeSay("closing on the " + who + " (" + below + " below, " + off + " off) by the planned way in (" + sized + "): to " + wp.toShortString());
                return 1;
            }
        }
        java.util.List<Task> steps = com.claudemod.entity.nav.ActionPath.tasks(plan, feet, scaff.isEmpty() ? net.minecraft.world.item.Items.COBBLESTONE : scaff.getItem(), to, 2.0, level);
        java.util.List<Task> first = new java.util.ArrayList<>();
        for (Task s : steps) { if (first.size() >= 4) break; first.add(s); if (s instanceof Task.GoTo && first.size() > 1) break; } // up to the walk after the first dig or block: the fight re-plans from there
        if (first.isEmpty()) return 0;
        dropCloseSteps();
        g.tasks.addFirst(new Task.Attack(t.target(), Math.max(100, t.maxTicks() - ticks)));
        for (int i = first.size() - 1; i >= 0; i--) g.tasks.addFirst(first.get(i));
        closeSteps = first;
        closeSay("closing on the " + who + " (" + below + " below, " + off + " off) by the planned way in (" + sized + "): first " + first + ", then the fight again");
        return 2;
    }

    private int lastHitTick = -100;
    private double chaseBest = Double.MAX_VALUE; private int chaseBestTick; // the chase's closest approach so far, and when
    private java.util.UUID lastPillarMob;

    /** Something a raised shield would stop: a skeleton about to loose (bow drawn ~half a second), an arrow flying his way, a mob in arm's reach. */
    private boolean incoming(net.minecraft.world.entity.LivingEntity mob) {
        if (!g.getItemBySlot(EquipmentSlot.OFFHAND).is(net.minecraft.world.item.Items.SHIELD)) return false;
        if (mob instanceof net.minecraft.world.entity.monster.CrossbowAttackMob && net.minecraft.world.item.CrossbowItem.isCharged(mob.getMainHandItem()) && mob.distanceTo(g) < 24 && mob.hasLineOfSight(g)) return true; // a loaded crossbow on him (a piglin's, a pillager's): it looses any moment and the bolt is there in a tick or two (the arrow-in-flight check below is too late for it); down again while it reloads
        if (mob instanceof net.minecraft.world.entity.monster.AbstractSkeleton sk) {
            if (sk.isUsingItem() && sk.getTicksUsingItem() >= 10 && sk.distanceTo(g) < 24 && g.hasLineOfSight(sk)) return true; // it looses at ~20 ticks; the shield takes 5 to come up
        } else if (!(mob instanceof net.minecraft.world.entity.monster.Creeper) && mob.distanceTo(g) < 3.5) {
            return true;
        }
        for (var a : level().getEntitiesOfClass(net.minecraft.world.entity.projectile.AbstractArrow.class, g.getBoundingBox().inflate(12), a -> a.getOwner() != g && !a.onGround())) {
            if (a.getDeltaMovement().dot(g.getEyePosition().subtract(a.position())) > 0) return true; // coming his way
        }
        return false;
    }

    /** Right click held on the shield in his off hand, facing the threat (a shield only stops what's in front of him). */
    private void shieldUp(net.minecraft.world.entity.LivingEntity threat) {
        if (!g.isUsingItem() && !g.getCooldowns().isOnCooldown(net.minecraft.world.item.Items.SHIELD)) g.startUsingItem(InteractionHand.OFF_HAND); // (an axe hit disables it for 5 s, for him as for anyone)
        g.getLookControl().setLookAt(threat);
    }

    private void shieldDown() {
        if (g.isUsingItem() && g.getUsedItemHand() == InteractionHand.OFF_HAND) g.stopUsingItem();
    }

    private boolean doTill(Task.Till t) {
        ServerLevel level = (ServerLevel) g.level();
        BlockState st = level.getBlockState(t.pos());
        boolean tillable = st.is(net.minecraft.world.level.block.Blocks.DIRT) || st.is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK);
        if (!tillable || !level.getBlockState(t.pos().above()).isAir()) return true;
        ItemStack hoe = g.inventory.find(s -> s.getItem() instanceof net.minecraft.world.item.HoeItem);
        if (hoe.isEmpty()) return true;
        if (!inReach(t.pos())) return walkTo(t.pos(), 3) && !inReach(t.pos());
        g.getNavigation().stop();
        g.getLookControl().setLookAt(Vec3.atCenterOf(t.pos()));
        g.hold(hoe);
        if (ticks < 6) return false;
        g.useOn(t.pos(), Direction.UP); // right click the top with the hoe
        return true;
    }

    private boolean doToss(Task.Toss t) {
        if (!(g.getTarget() instanceof net.minecraft.world.entity.player.Player p)) return true;
        // Walk right up to them (a couple of blocks, in sight), then lob it so it lands at their feet.
        if (g.distanceTo(p) > 2.5 || !g.hasLineOfSight(p)) {
            g.approaching = false;
            Vec3 at = com.claudemod.entity.life.Whereabouts.believed(g, p); // (where he believes they are: walking to their real spot unseen is knowing it through walls)
            if (at == null) return true; // he doesn't know where they are: keep it
            if (ticks % 10 == 1) g.getNavigation().moveTo(at.x, at.y, at.z, 1.0);
            return ticks > 300; // couldn't get to them: keep it
        }
        g.getNavigation().stop();
        // Look down at their feet and press Q, like a player handing something over: the game throws it along his look.
        Vec3 feet = p.position().add(0, 0.2, 0);
        g.getLookControl().setLookAt(feet);
        if (ticks < 10) return false;
        g.getLookControl().faceNow(feet);
        int n = 0, want = t.count();
        g.approaching = false;
        for (int i = 0; i < g.inventory.items.getContainerSize() && want > 0; i++) { // the real stacks, one Q press per stack: what's on them (enchantments, wear, names) goes with them
            ItemStack s = g.inventory.items.getItem(i);
            if (s.isEmpty() || !s.is(t.item())) continue;
            ItemStack out = s.split(Math.min(want, s.getCount()));
            if (s.isEmpty()) g.inventory.items.setItem(i, ItemStack.EMPTY);
            var drop = g.drop(out, false, true);
            if (drop != null) g.markGift(drop); // it's for them: he won't snatch it back up (anyone can pick it up, like any thrown item)
            n += out.getCount(); want -= out.getCount();
        }
        if (n == 0) return true;
        g.swing(InteractionHand.MAIN_HAND);
        g.lastGiven.put(t.item(), g.tickCount);
        com.claudemod.entity.life.OwnEvents.gave(g, t.item(), n, p.blockPosition());
        com.claudemod.director.Errand.tossed(g, t.item(), n);
        g.noteDoing("gave them " + n + " " + itemName(new ItemStack(t.item())));
        return true;
    }

    private int fishCaught, fishCastTick;
    private float fishAim; // pitch correction learned from where the casts land (past the water: lower; short of it: higher)
    private static java.lang.reflect.Field nibbleField; // FishingHook.nibble: >0 while a fish is on the line (the window a player reels in)

    private static int nibble(net.minecraft.world.entity.projectile.FishingHook hook) {
        try {
            if (nibbleField == null) { nibbleField = net.minecraft.world.entity.projectile.FishingHook.class.getDeclaredField("nibble"); nibbleField.setAccessible(true); }
            return nibbleField.getInt(hook);
        } catch (ReflectiveOperationException e) { return 0; }
    }

    /** Rod in hand, face the water, cast; wait for the bite (the bobber dips: the hook's nibble window), reel in; again until enough. */
    private boolean doFish(Task.Fish t) {
        if (ticks == 1) { fishCaught = 0; fishCastTick = -100; fishAim = 0; }
        var hook = g.fishing;
        boolean over = ticks > t.maxTicks() || fishCaught >= t.catches() || !hostilesNear(10).isEmpty(); // enough, out of time, or something coming: rod in, deal with it
        ItemStack rod = g.inventory.find(s -> s.is(net.minecraft.world.item.Items.FISHING_ROD));
        if (over || rod.isEmpty()) {
            if (hook != null && g.getMainHandItem().is(net.minecraft.world.item.Items.FISHING_ROD)) g.useHeld(); // reel in, whatever's on it
            if (fishCaught > 0) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] fishing done at {}: {} caught", t.water().toShortString(), fishCaught);
            return true;
        }
        if (!g.hold(rod)) return true;
        g.getNavigation().stop();
        Vec3 eye = g.getEyePosition(), to = Vec3.atCenterOf(t.water());
        double dx = to.x - eye.x, dz = to.z - eye.z, dy = to.y - eye.y;
        float yaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
        float pitch = (float) Math.max(-45, Math.min(70, -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))) - 10 + fishAim)); // a touch higher than the spot (the cast arcs down onto it), corrected by where the last ones fell
        g.setYRot(yaw); g.setYHeadRot(yaw); g.setXRot(pitch);
        if (hook == null) {
            if (ticks - fishCastTick < 15) return false; // the moment after a reel, like a player
            g.useHeld(); fishCastTick = ticks; // cast
            if (fishCaught == 0 && ticks < 40) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] casting at {} (hook out: {})", t.water().toShortString(), g.fishing != null);
            return false;
        }
        boolean afloat = g.level().getFluidState(hook.blockPosition()).is(net.minecraft.tags.FluidTags.WATER) || g.level().getFluidState(hook.blockPosition().below()).is(net.minecraft.tags.FluidTags.WATER); // (a bobber floats on the top: isInWater missed it)
        if (ticks - fishCastTick > 60 && !afloat) { // landed out of the water: reel in, correct the aim like a player, cast again
            double hookD = Math.hypot(hook.getX() - g.getX(), hook.getZ() - g.getZ()), wantD = Math.hypot(to.x - g.getX(), to.z - g.getZ());
            if (Math.abs(hookD - wantD) > 1.5) fishAim = Math.max(-40, Math.min(60, fishAim + (hookD > wantD ? 8 : -8))); // the right distance and still dry: off to the side, the aim's fine
            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] the cast landed out of the water ({} blocks out, the water's {}): aiming {} next", (int) hookD, (int) wantD, hookD > wantD ? "lower" : "higher");
            if (hookD < 1.0) { int blocked = g.attempts.counters.merge("castBlocked", 1, Integer::sum); if (blocked >= 3) { g.attempts.counters.put("castBlocked", 0); g.useHeld(); g.attempts.fail(com.claudemod.entity.life.Attempts.Kind.REACH, t.water(), g.tickCount); com.claudemod.ClaudeMod.LOGGER.info("[Claude life] the line stops at his feet every cast (something in the way): not fishing from here"); return true; } } else g.attempts.counters.put("castBlocked", 0); // (a block over or before him catches the line: aiming higher never helps, and he cast twenty times)
            g.useHeld(); fishCastTick = ticks; return false;
        }
        if (nibble(hook) > 0) { // a bite: reel in now (the catch flies to him)
            g.useHeld(); fishCaught++; fishCastTick = ticks; // (the catch flies to him and he picks it up where he stands)
            return false;
        }
        return false;
    }

    private boolean doEat(Task.Eat t) {
        // Hold right click with the food, like a player: the game runs the eating (only when he's hungry, unless
        // it's always-edible), fills his hunger, and his health comes back from that over time.
        if (g.isUsingItem()) { g.getNavigation().stop(); return false; }
        if (ticks > 1) return true; // finished (or the game wouldn't let him start)
        ItemStack food = g.inventory.find(s -> s.is(t.food()));
        if (food.isEmpty() || !g.hold(food)) return true;
        g.getNavigation().stop();
        return !g.useHeld();
    }

    private int pillarDone;

    /** Jump and place a block under himself, like a player towering up; or mine the pillar back down. */
    private int pillarPhase;
    private BlockPos pillarFeet;

    /** Towering up like a player: jump, and place a block under himself while in the air. Or mine the pillar back down. */
    private boolean doPillar(Task.Pillar t) {
        ServerLevel level = (ServerLevel) g.level();
        if (ticks == 1) { pillarDone = 0; pillarPhase = 0; }
        g.getNavigation().stop();
        if (t.steps() < 0) {
            // still up here for the next block (the rest of a branch)? keep the tower for now; it comes down after
            if (g.tasks.peek() instanceof Task.Break nb && inReach(nb.pos())) return true;
            // anything else queued he can reach from up here (the rest of a big tree's branches): do it all now, come down once
            java.util.List<Task> reach = new java.util.ArrayList<>();
            for (Task q : g.tasks) if (q instanceof Task.Break qb && inReach(qb.pos())) reach.add(q);
            if (!reach.isEmpty()) {
                g.tasks.removeAll(reach);
                g.tasks.addFirst(new Task.Pillar(-1));
                for (int i = reach.size() - 1; i >= 0; i--) g.tasks.addFirst(reach.get(i));
                return true;
            }
            // on his way up anyway (out of a hole): the tower under him is his way out, so it stays
            for (Task q : g.tasks) if (q instanceof Task.GoTo up && up.pos().getY() >= g.getBlockY() + 1) {
                g.pillarBlocks.removeIf(p -> Math.abs(p.getX() - g.getBlockX()) <= 1 && Math.abs(p.getZ() - g.getBlockZ()) <= 1);
                return true;
            }
            java.util.List<BlockPos> down = new java.util.ArrayList<>(); // the tower he's at, not every one on record (Fable: a Pillar(-1) for a mine patch walked him back to an old tree's tower 30 blocks off); the others are their own jobs' to take down
            for (var it = g.pillarBlocks.iterator(); it.hasNext(); ) { BlockPos p = it.next(); if (Math.abs(p.getX() - g.getBlockX()) <= 3 && Math.abs(p.getZ() - g.getBlockZ()) <= 3) { down.add(p); it.remove(); } }
            if (down.isEmpty()) for (BlockPos b = standingOn(); down.size() < 12; b = b.below()) { // none on record here (a tower built before the take-down was asked for): the free-standing column he's on is his tower, a player knows what he just built
                BlockState bs = level.getBlockState(b);
                if (bs.getCollisionShape(level, b).isEmpty() || bs.hasBlockEntity() || bs.getDestroySpeed(level, b) < 0) break;
                boolean free = true; for (Direction d : Direction.Plane.HORIZONTAL) if (!level.getBlockState(b.relative(d)).getCollisionShape(level, b.relative(d)).isEmpty()) free = false;
                if (!free) break; // the ground (or a wall beside it): not tower
                down.add(b.immutable());
            }
            if (down.isEmpty()) return true;
            down.sort(java.util.Comparator.comparingInt(BlockPos::getY)); // lowest first in the list...
            for (BlockPos p : down) g.tasks.addFirst(new Task.Break(p)); // ...so the HIGHEST ends up first in the queue: the tower comes down from the top under his feet, one block at a time (bottom-first left him on a floating top and a 6-block fall)
            g.tasks.add(new Task.Pickup(60)); // the dirt back
            return true;
        }
        if (pillarDone >= t.steps()) return true;
        // the tower block is chosen once per tower (planks made for it count, even if that's all the planks he has)
        if (ticks == 1) pillarItem = null;
        ItemStack block = pillarItem != null ? g.inventory.find(st -> st.is(pillarItem)) : scaffoldBlock();
        if (block.isEmpty() && pillarItem != null) block = scaffoldBlock(); // ran out of that block partway up: anything else he can tower with
        if (block.isEmpty()) block = getScaffold(t); // or make some (logs into planks), any time he runs out, not just at the start
        if (block == null) return true; // went to get some first (the tower comes after)
        if (!block.isEmpty()) pillarItem = block.getItem();
        if (block.isEmpty() || !g.hold(block)) { dbg("nothing to tower with (" + block + ")"); return true; }
        boolean swimming = g.isInWater(); // in water (deep, or a jump out of ankle-deep): swim up, a block in under him
        if (pillarPhase == 0) {
            if (!g.onGround() && !swimming) { if (ticks > 60) dbg("never landed"); return ticks > 60; } // never landed (a ladder...): give up towering
            pillarFeet = g.blockPosition();
            BlockPos head = pillarFeet.above(2);
            if (!level.getBlockState(head).getCollisionShape(level, head).isEmpty()) { // (water up there is fine: he swims through it)
                if (treeBlockInWay(head)) { // leaves (or the trunk) over his head: punch them out and keep towering
                    g.tasks.addFirst(new Task.Pillar(t.steps() - pillarDone));
                    g.tasks.addFirst(new Task.Break(head));
                    return true;
                }
                com.claudemod.ClaudeMod.LOGGER.info("[Claude life] no head room to tower at {}: {} at {} over his head, not a tree's; this climb ends here (WorkGoal.doPillar)", pillarFeet.toShortString(), name(level.getBlockState(head)), head.toShortString()); return true;
            }
            if (pillarDone == 0 && t.steps() > 0 && digInAt != null && digInTasks != null && !digInTasks.isEmpty() && digInTasks.get(0) == t && !pillarFeet.equals(digInAt)) reanchorSkytower(t, pillarFeet); // (decided on the run: the tower goes up where he stopped, its walls with it)
            // stand still over the spot first (no drifting off from the last walk), then jump
            Vec3 center = Vec3.atBottomCenterOf(pillarFeet);
            if ((new Vec3(g.getX() - center.x, 0, g.getZ() - center.z).lengthSqr() > 0.2 * 0.2 || g.getDeltaMovement().horizontalDistanceSqr() > 0.01)
                    && holdTicks++ < 30) { // ponytail: 30 ticks to settle, then jump anyway
                g.getMoveControl().setWantedPosition(center.x, center.y, center.z, 0.3);
                return false;
            }
            holdTicks = 0;
            g.getMoveControl().clear();
            g.getLookControl().faceNow(Vec3.atCenterOf(pillarFeet.below()).add(0, 0.5, 0)); // looking straight down, like a player towering
            g.getJumpControl().jump();
            pillarPhase = 1;
            pillarTicks = 0;
            return false;
        }
        g.getLookControl().setLookAt(Vec3.atCenterOf(pillarFeet.below()).add(0, 0.5, 0));
        g.getMoveControl().setWantedPosition(pillarFeet.getX() + 0.5, pillarFeet.getY(), pillarFeet.getZ() + 0.5, 0.3); // stay over the block
        pillarTicks++;
        // hold space like a player towering: the game only lets you jump again a moment after landing
        if ((g.onGround() || swimming) && g.getY() < pillarFeet.getY() + 1.1) g.getJumpControl().jump(); // (in water: keep swimming up)
        boolean high = g.getY() >= pillarFeet.getY() + 1.0;
        if (!high && (pillarTicks < 30 || !g.onGround()) && pillarTicks < (swimming ? 120 : 60)) return false; // still on the way up: let the jump (or the swim) finish
        if (!high) { dbg("jump too low y=" + g.getY() + " feet=" + pillarFeet.getY() + " jumping=" + g.jumpInput() + " delay=" + g.jumpDelay() + " ground=" + g.onGround() + " sneak=" + g.isShiftKeyDown() + " dy=" + g.getDeltaMovement().y + " ticks=" + pillarTicks + " holding=" + g.getMainHandItem() + " above: " + level.getBlockState(g.blockPosition().above()).getBlock() + "/" + level.getBlockState(g.blockPosition().above(2)).getBlock() + "/" + level.getBlockState(g.blockPosition().above(3)).getBlock()); return true; } // the jump didn't make it (low ceiling, slab...): give up towering
        // right click the top of the block he jumped off: the new block goes where his feet were
        boolean already = !level.getBlockState(pillarFeet).getCollisionShape(level, pillarFeet).isEmpty(); // landed on something there anyway
        if (already) { pillarDone++; pillarPhase = 0; return pillarDone >= t.steps(); } // up a level either way (not his block to take down)
        if (!level.getFluidState(pillarFeet).isEmpty()) g.placeAt(pillarFeet, pillarItem); // in water: against the side walls (or whatever's under it)
        else g.useOn(pillarFeet.below(), Direction.UP);
        if (level.getBlockState(pillarFeet).getCollisionShape(level, pillarFeet).isEmpty()) { // the game didn't take it (yet): click again next tick, jump again if he lands
            if (pillarTicks < 40) return false;
            dbg("place failed at " + pillarFeet + " y=" + g.getY() + " held=" + g.getMainHandItem());
            return true;
        }
        // only a tower he'll come back down (to reach a tree, glowstone, ore up in a ceiling) goes on the list to take down;
        // one to get out of somewhere (a pit, water, a hole in his mine's floor) is his way up and stays, like a player's
        boolean comesDown = g.tasks.stream().anyMatch(q -> q instanceof Task.Pillar p && p.steps() < 0 || q instanceof Task.FinishTree || q instanceof Task.ChopTree);
        if (comesDown && !(com.claudemod.director.ClaudeLife.isMineCell(g, pillarFeet.above()) && !com.claudemod.director.ClaudeLife.isMineCell(g, pillarFeet)))
            { g.pillarBlocks.remove(pillarFeet); g.pillarBlocks.addFirst(pillarFeet.immutable()); }
        pillarDone++;
        pillarPhase = 0;
        return pillarDone >= t.steps();
    }

    /**
     * The skytower goes up where he stands when the climb starts, not where the cover was decided: decided on the run he carries on a
     * block or three, and the hut's walls stayed round the decided column, out of reach from the real one; their Places walked him off
     * his finished tower into the mobs (live 16:57, a fortress bridge: knocked into the lava). The cover is planned again round the
     * column he's on: walls, lid, wait and take-down, and digInAt with them.
     */
    private void reanchorSkytower(Task.Pillar t, BlockPos at) {
        ServerLevel level = (ServerLevel) g.level();
        BlockPos was = digInAt; java.util.List<Task> old = digInTasks;
        net.minecraft.world.item.Item hutItem = null; java.util.List<BlockPos> oldHut = new java.util.ArrayList<>(); Task wait = null, pick = null;
        for (Task x : old) { if (x instanceof Task.Place pl) { hutItem = pl.item(); oldHut.add(pl.pos()); } else if (x instanceof Task.Wait) wait = x; else if (x instanceof Task.Pickup) pick = x; }
        int towerH = t.steps();
        java.util.List<BlockPos> hut = new java.util.ArrayList<>();
        if (hutItem != null) { // the same hut as digInFromArrows plans: each ring on the one below (the first against the tower top), a block on the north wall to hang the lid off, the lid
            for (int h = towerH - 1; h <= towerH + 1; h++) for (Direction d : Direction.Plane.HORIZONTAL) hut.add(at.above(h).relative(d).immutable());
            hut.add(at.above(towerH + 2).north().immutable());
            hut.add(at.above(towerH + 2).immutable());
            hut.removeIf(q -> !level.getBlockState(q).canBeReplaced());
        }
        g.pillarBlocks.removeAll(oldHut); // (none of them placed yet: the climb hasn't started)
        java.util.List<Task> up = new java.util.ArrayList<>(); up.add(t);
        for (BlockPos q : hut) { up.add(new Task.Place(q, hutItem)); g.pillarBlocks.addFirst(q); }
        up.add(wait != null ? wait : new Task.Wait(600, false));
        for (int i = hut.size() - 1; i >= 0; i--) up.add(new Task.Break(hut.get(i)));
        for (int k = towerH - 1; k >= 0; k--) { BlockPos q = at.above(k).immutable(); if (com.claudemod.director.ClaudeLife.hisBuildKeeps(g, level, q)) { com.claudemod.ClaudeMod.LOGGER.info("[Claude life] skytower take-down: {} is his build's own (his door's cap, his mine), it stays (WorkGoal.reanchorSkytower)", q.toShortString()); continue; } up.add(new Task.Break(q)); } // (live 03:52: the take-down queued his pit cap, refused 4 times)
        up.add(pick != null ? pick : new Task.Pickup(60));
        g.tasks.removeIf(x -> old.stream().anyMatch(y -> y == x));
        for (int i = up.size() - 1; i >= 1; i--) g.tasks.addFirst(up.get(i)); // (the Pillar itself is running)
        digInAt = at.immutable(); digInTasks = up; digInRise = towerH;
        com.claudemod.ClaudeMod.LOGGER.info("[Claude life] his skytower goes up at {}, not at {} where the cover was decided ({} off: he was still moving): its {} wall/lid blocks, wait and take-down planned again round this column (WorkGoal.reanchorSkytower)", at.toShortString(), was.toShortString(), String.format("%.1f", Math.sqrt(at.distSqr(was))), hut.size());
    }

    /** Up the skytower of his cover (on its column, 2+ above its foot): his cover as much as a lidded hole; nothing walks him off it while mobs are about. */
    private boolean upHisSkytower() {
        return digInAt != null && digInTasks != null && !digInTasks.isEmpty() && digInTasks.get(0) instanceof Task.Pillar p0 && p0.steps() > 0 && diggingIn()
                && g.getBlockX() == digInAt.getX() && g.getBlockZ() == digInAt.getZ() && g.getBlockY() >= digInAt.getY() + 2;
    }

    private int pillarTicks, holdTicks;
    private net.minecraft.world.item.Item pillarItem;

    private void dbg(String s) { com.claudemod.ClaudeMod.LOGGER.info("[Claude life] stopped towering: {}", s); }

    /** His best weapon in hand: a sword, else an axe (by the item's real attack damage). */
    /**
     * Like a player with a bow: a hostile 6+ blocks off (a creeper 5+) with a clear line gets shot, the bow drawn to full
     * (about a second) and loosed with a little lead on a moving target. True while he's doing that (no closing in).
     */
    private boolean shootAt(net.minecraft.world.entity.LivingEntity mob) {
        if (mob instanceof net.minecraft.world.entity.monster.EnderMan) { if (g.isUsingItem() && g.getUseItem().is(net.minecraft.world.item.Items.BOW)) g.stopUsingItem(); return false; } /* vanilla: an enderman teleports out of every arrow; live 01:42 twelve arrows at one, two after he said "switching to the diamond sword" */
        ItemStack bow = g.inventory.find(s -> s.is(net.minecraft.world.item.Items.BOW));
        // livestock is walked up to and hit, like a player does: arrows are kept for blazes, endermen and the dragon (01:47 thirteen arrows at a pig 26 off, at full health)
        if (!(mob instanceof net.minecraft.world.entity.monster.Enemy) && !lureShot) { if (g.isUsingItem() && g.getUseItem().is(net.minecraft.world.item.Items.BOW)) g.stopUsingItem(); return false; }
        if (bow.isEmpty() || g.inventory.count(s -> s.is(net.minecraft.world.item.Items.ARROW)) == 0) { if (g.isUsingItem() && g.getUseItem().is(net.minecraft.world.item.Items.BOW)) g.stopUsingItem(); return false; }
        double d = g.distanceTo(mob);
        double minRange = mob instanceof net.minecraft.world.entity.monster.Creeper ? 5.0 : lureShot ? 2.0 : 6.0; // (a shot to draw a golem to his tower: close is fine, it can't reach him)
        boolean drawing = g.isUsingItem() && g.getUseItem().is(net.minecraft.world.item.Items.BOW);
        if (d < minRange - (drawing ? 1.0 : 0) || d > 28 || !g.hasLineOfSight(mob) || g.isInWater()) { if (drawing) g.stopUsingItem(); return false; }
        g.getNavigation().stop(); // stood still to shoot, like a player
        g.setSprinting(false);
        bowAimTick = g.tickCount;
        if (!drawing) { g.hold(bow); g.startUsingItem(InteractionHand.MAIN_HAND); return true; }
        // aim: at its chest, led by where it'll be when the arrow lands (~1 block of flight per 2 ticks at full draw), a touch high for the drop
        Vec3 vel = mob.getDeltaMovement();
        double flight = d / 1.5;
        Vec3 aim = mob.getBoundingBox().getCenter().add(vel.x * flight, 0, vel.z * flight).add(0, d * 0.02, 0);
        g.getLookControl().setLookAt(aim.x, aim.y, aim.z, 90f, 90f);
        g.getLookControl().faceNow(aim);
        if (g.getTicksUsingItem() >= 20) { // full draw: loose it (the game fires along his look, takes the arrow and wears the bow, as for any player)
            g.releaseUsingItem();
            g.swing(InteractionHand.MAIN_HAND);
            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] shot at a {} {} blocks off", mob.getType().getDescription().getString(), (int) d);
        }
        return true;
    }

    private void equipWeapon() {
        ItemStack best = ItemStack.EMPTY;
        double bestDmg = 0;
        for (int i = 0; i < g.inventory.items.getContainerSize(); i++) {
            ItemStack s = g.inventory.items.getItem(i);
            if (!(s.getItem() instanceof net.minecraft.world.item.SwordItem) && !(s.getItem() instanceof net.minecraft.world.item.AxeItem)) continue;
            double dmg = weaponDamage(s);
            if (dmg > bestDmg) { bestDmg = dmg; best = s; }
        }
        if (!best.isEmpty()) g.hold(best);
    }

    /** The fastest tool he has for this block in hand (or an empty hand / harmless item when nothing helps). */
    private void equipBestTool(BlockState state) {
        // Pickaxe work: the cheapest pickaxe that still gets the drop (stone for stone, coal, iron; iron only for diamonds,
        // gold, redstone; diamond only for obsidian), so the good one isn't worn down on plain rock. Wooden only if it's all he has.
        if (state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_PICKAXE)) {
            ItemStack pick = ItemStack.EMPTY; float pickSpeed = Float.MAX_VALUE; boolean pickWooden = true;
            for (int i = 0; i < g.inventory.items.getContainerSize(); i++) {
                ItemStack s = g.inventory.items.getItem(i);
                if (!(s.getItem() instanceof net.minecraft.world.item.PickaxeItem) || !s.isCorrectToolForDrops(state)) continue;
                boolean wooden = s.is(net.minecraft.world.item.Items.WOODEN_PICKAXE);
                float sp = s.getDestroySpeed(state);
                if (pick.isEmpty() || (pickWooden && !wooden) || (wooden == pickWooden && sp < pickSpeed)) { pick = s; pickSpeed = sp; pickWooden = wooden; }
            }
            // only the good pick left for plain rock (the stone one broke): make a stone one rather than wear the iron down
            if (!pick.isEmpty() && !pick.is(net.minecraft.world.item.Items.STONE_PICKAXE) && !pickWooden && g.tickCount % 200 == 0) com.claudemod.ClaudeMod.LOGGER.info("[Claude tool] rock {} with {}: stone pick does it {}, since retool {}t, materials {}", state.getBlock().getName().getString(), net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(pick.getItem()).getPath(), new ItemStack(net.minecraft.world.item.Items.STONE_PICKAXE).isCorrectToolForDrops(state), g.tickCount - g.retoolTick, g.inventory.count(x -> x.is(net.minecraft.tags.ItemTags.STONE_TOOL_MATERIALS))); // (why the iron pick is on rock: the 01:57 stone pick broke and no new one came)
            if (!pick.isEmpty() && !pick.is(net.minecraft.world.item.Items.STONE_PICKAXE) && !pickWooden
                    && new ItemStack(net.minecraft.world.item.Items.STONE_PICKAXE).isCorrectToolForDrops(state)) { // no timer: each dig asks again, and the answer follows the state (a table in reach: craft; none: the walk to one goes first, see doBreak) // (a table in reach: craft now, whatever the timer: he walked to the nook table, stood 1 block from it inside the 30 s and walked back on the iron pick)
                g.retoolTick = g.tickCount;
                if (com.claudemod.director.ClaudeLife.craftToolNow(g, state, net.minecraft.world.item.Items.STONE_PICKAXE)) { equipBestTool(state); return; } // (a stone pick only: one waiting on a table is no reason for a diamond one)
            }
            if (!pick.isEmpty()) { g.hold(pick); return; }
        }
        ItemStack best = ItemStack.EMPTY;
        float bestSpeed = 1.0f;
        for (int i = 0; i < g.inventory.items.getContainerSize(); i++) {
            ItemStack s = g.inventory.items.getItem(i);
            if (s.getItem() instanceof net.minecraft.world.item.SwordItem && !state.is(net.minecraft.world.level.block.Blocks.COBWEB)) continue; // a sword is for fighting, not digging
            float sp = s.isEmpty() ? 1.0f : s.getDestroySpeed(state);
            if (sp > bestSpeed) { bestSpeed = sp; best = s; }
        }
        if (!best.isEmpty()) { g.hold(best); return; }
        // No right tool for it (dirt and no shovel, a log and no axe): make one on the spot if he can, now and then
        int sameKind = 0; // how much of this digging is ahead of him: a player punches a few dirt, he makes a shovel for a big dig (Fable: 6 scaffold dirt got a table and a wooden shovel)
        if (!state.requiresCorrectToolForDrops()) for (Task q : g.tasks) if (q instanceof Task.Break qb) { BlockState qs = g.level().getBlockState(qb.pos()); if (state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_SHOVEL) ? qs.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_SHOVEL) : qs.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_AXE)) sameKind++; }
        if ((state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_SHOVEL) || state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_AXE))
                && (state.requiresCorrectToolForDrops() || sameKind >= 16 || state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_AXE)) && g.tickCount - g.shovelTryTick > 20 * 60) { // (an axe still gets made for any chopping: wood comes by the stack)
            g.shovelTryTick = g.tickCount;
            if (com.claudemod.director.ClaudeLife.craftToolNow(g, state)) { equipBestTool(state); return; }
        }
        // otherwise his bare hands: never the wrong tool (a pickaxe on dirt, an axe on stone)
        // (a block or any plain item in hand is fine; never a tool or weapon, anything that wears down)
        if (g.getMainHandItem().isDamageableItem() && !g.hold(s -> s.isEmpty() || !s.isDamageableItem())) {
            for (int i = 0; i < 9; i++) if (g.getInventory().items.get(i).isEmpty()) { g.getInventory().selected = i; break; }
        }
    }

    private void clearCrack() {
        g.abortDig(); // let go of left click
    }

    private static String name(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath().replace('_', ' ');
    }

    private static String itemName(ItemStack s) {
        return BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().replace('_', ' ');
    }
}
