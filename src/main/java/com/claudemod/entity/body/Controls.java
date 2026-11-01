package com.claudemod.entity.body;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * What a player's hands do on a keyboard and mouse: where they look, which way they walk, when they jump.
 * The brain asks for these each tick (same calls the old mob body had); {@link #apply} turns them into
 * the player's rotation and movement inputs before the real player physics run.
 */
public final class Controls {
    private static final java.util.Map<java.util.UUID, net.minecraft.core.BlockPos> FALLING = new java.util.HashMap<>(); // (an unplanned fall under way: logged once)
    private Controls() {}

    /** Mouse: turn toward a point, a little each tick like a person (not a snap). */
    public static final class Look {
        private final Player p;
        private Vec3 want;
        private int setTick = -10;
        private boolean soft; // a look-where-you-are-going target (not a mob, not them): walking never goes backwards to keep facing it

        public Look(Player p) { this.p = p; }

        public void setLookAt(Entity e) { setLookAt(e.getX(), e.getEyeY(), e.getZ()); soft = false; }
        public void setLookAt(Entity e, float yawSpeed, float pitchSpeed) { setLookAt(e); }
        public void setLookAt(Vec3 v) { setLookAt(v.x, v.y, v.z); }
        public void setLookAt(double x, double y, double z, float yawSpeed, float pitchSpeed) { setLookAt(x, y, z); }
        public void setLookAt(double x, double y, double z) { want = new Vec3(x, y, z); setTick = p.tickCount; soft = true; }

        /** Snap straight to it (aiming a click, where a person would have lined it up already). */
        public void faceNow(Vec3 v) {
            setLookAt(v);
            float[] yp = anglesTo(v);
            p.setYRot(yp[0]); p.setXRot(yp[1]); p.setYHeadRot(yp[0]); p.yBodyRot = yp[0];
        }

        boolean active() { return want != null && p.tickCount - setTick <= 2; }

        /** Turn toward the look target, or (walking with nothing to look at) the way he's going. */
        void apply(Vec3 walking) {
            boolean moving = walking != null && walking.lengthSqr() > 1e-4;
            Vec3 at = active() ? want : moving ? p.getEyePosition().add(walking.x * 4, 0, walking.z * 4) : null;
            if (active() && soft && moving) { // looking at a waypoint behind him: a player turns and walks forward, no moonwalking
                Vec3 toLook = new Vec3(want.x - p.getX(), 0, want.z - p.getZ());
                if (toLook.lengthSqr() > 1e-4 && toLook.normalize().dot(walking.normalize()) < -0.2) at = p.getEyePosition().add(walking.x * 4, 0, walking.z * 4);
                else if (toLook.lengthSqr() > 1e-4 && toLook.length() < 4) { Vec3 eye = p.getEyePosition(); Vec3 far = toLook.normalize().scale(4); at = new Vec3(eye.x + far.x, want.y, eye.z + far.z); } // a waypoint close by: the same height, 4 out (a step a block below and a block ahead tipped his head straight down at every step and back up: the "head bob" on stairs)
            }
            if (at == null) return;
            float[] yp = anglesTo(at);
            float yaw = Mth.approachDegrees(p.getYRot(), yp[0], 40.0f);
            float pitch = Mth.approachDegrees(p.getXRot(), yp[1], 30.0f);
            p.setYRot(yaw); p.setXRot(pitch); p.setYHeadRot(yaw);
            p.yBodyRot = Mth.approachDegrees(p.yBodyRot, yaw, 30.0f);
        }

        private float[] anglesTo(Vec3 v) {
            Vec3 eye = p.getEyePosition();
            double dx = v.x - eye.x, dy = v.y - eye.y, dz = v.z - eye.z;
            float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0f;
            float pitch = (float) -(Mth.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * Mth.RAD_TO_DEG);
            return new float[] {Mth.wrapDegrees(yaw), Mth.clamp(pitch, -90.0f, 90.0f)};
        }
    }

    /** Keys: walk straight at a point (a ledge step, a bridge block), when there's no path to follow. */
    public static final class Move {
        private final Player p;
        private Vec3 wanted;
        private double speed;
        private int setTick = -100;

        public Move(Player p) { this.p = p; }

        public void setWantedPosition(double x, double y, double z, double speed) {
            wanted = new Vec3(x, y, z);
            this.speed = speed;
            setTick = p.tickCount;
        }

        public void clear() { wanted = null; }

        /** Horizontal direction to walk this tick, or null when there's nowhere to go. */
        Vec3 steer() {
            if (wanted == null || p.tickCount - setTick > 20) { wanted = null; return null; } // a step, not a journey
            Vec3 d = new Vec3(wanted.x - p.getX(), 0, wanted.z - p.getZ());
            if (d.lengthSqr() < 0.15 * 0.15) return null; // over it already (keep it: swimming straight down still needs the target)
            return d.normalize();
        }

        double speed() { return speed; }
        double wantedY() { return wanted == null ? Double.NaN : wanted.y; }
        double wantedDist() { return wanted == null ? 0 : Math.sqrt(Math.pow(wanted.x - p.getX(), 2) + Math.pow(wanted.z - p.getZ(), 2)); }
    }

    /** Open ground for a sprint-jump: headroom (not a 2-high branch), no wall and no 2+ drop in the next three blocks (a one-block step is what the jump is for). */
    /** Lava at his feet level in that cell, or as its floor (lava under an open cell). */
    public static boolean lavaCell(Player p, net.minecraft.core.BlockPos c) {
        var l = p.level();
        return l.getFluidState(c).is(net.minecraft.tags.FluidTags.LAVA) || l.getFluidState(c.below()).is(net.minecraft.tags.FluidTags.LAVA) && l.getBlockState(c.below()).getCollisionShape(l, c.below()).isEmpty();
    }

    public static boolean openGround(Player p, Vec3 dir) {
        var lvl = p.level();
        for (int i = 0; i <= 4; i++) { // (4: a sprint-jump lands 3-4 out)
            var c = net.minecraft.core.BlockPos.containing(p.getX() + dir.x * i, p.getY(), p.getZ() + dir.z * i);
            if (lavaCell(p, c) || lavaCell(p, c.relative(net.minecraft.core.Direction.getNearest(-dir.z, 0, dir.x))) || lavaCell(p, c.relative(net.minecraft.core.Direction.getNearest(dir.z, 0, -dir.x)))) return false; // lava on the line or beside it: walked, never jumped (the jump's landing and its slide are past the lava guard)
            if (lvl.getBlockState(c.below()).is(net.minecraft.world.level.block.Blocks.FARMLAND) || lvl.getBlockState(c).getBlock() instanceof net.minecraft.world.level.block.CropBlock) return false; // a farm is walked, never jumped across (a landing tramples farmland)
            if (!lvl.getBlockState(c.above(2)).getCollisionShape(lvl, c.above(2)).isEmpty()) return false;
            if (i > 0 && !lvl.getBlockState(c.above()).getCollisionShape(lvl, c.above()).isEmpty()) return false;
            int drop = 0; var q = c.below();
            while (drop < 2 && lvl.getBlockState(q).getCollisionShape(lvl, q).isEmpty() && lvl.getFluidState(q).isEmpty()) { drop++; q = q.below(); }
            if (drop >= 2) return false;
        }
        return true;
    }

    /** Space bar. */
    public static final class Jump {
        private final Player p;
        private int jumpTick = -10;

        public Jump(Player p) { this.p = p; }

        public void jump() { jumpTick = p.tickCount; }

        boolean pressed() { return p.tickCount - jumpTick <= 1; }
    }

    /**
     * Turns this tick's intent into a player's inputs: look first (so "forward" is up to date), then the
     * walking direction as forward/strafe relative to where he's facing, so he can look at one thing and
     * walk toward another like a real player. Sneaking walks at sneak speed; holding space swims up.
     */
    public static void apply(Player p, Look look, Move move, Jump jump, PlayerNav nav) {
        if (p instanceof com.claudemod.entity.ClaudeEntity ce0) { // a fall is never silent (live 10:53 World 15: 4.5 s with no line from a Nether ledge into a lava lake 18 down)
            if (p.onGround() || p.isInWater() || p.isInLava()) { if (FALLING.remove(p.getUUID()) != null && p.isInLava()) com.claudemod.ClaudeMod.LOGGER.info("[Claude move] the fall ended in lava at {} (Controls.apply)", p.blockPosition().toShortString()); }
            else if (p.fallDistance > 3 && !FALLING.containsKey(p.getUUID()) && com.claudemod.entity.nav.Leaps.control(p) == null) { FALLING.put(p.getUUID(), p.blockPosition().immutable()); int depth = 0; net.minecraft.core.BlockPos q = p.blockPosition(); while (depth < 64 && p.level().getBlockState(q).getCollisionShape(p.level(), q).isEmpty() && p.level().getFluidState(q).isEmpty()) { depth++; q = q.below(); }
                com.claudemod.ClaudeMod.LOGGER.info("[Claude move] falling at {} ({} so far, {} more to {} at {}), doing {} (Controls.apply)", p.blockPosition().toShortString(), String.format("%.1f", p.fallDistance), depth, net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(p.level().getBlockState(q).getBlock()).getPath(), q.toShortString(), ce0.currentTask == null ? "-" : ce0.currentTask.getClass().getSimpleName()); }
        }
        p.xxa = 0; p.zza = 0;
        { var lc = com.claudemod.entity.nav.Leaps.control(p); // a drop or a jump running (Leaps.tick): its hands this tick, exactly, no lip guard, no auto sprint-jump, no path
          if (lc != null) {
              if (lc.walk() != null && lc.walk().lengthSqr() > 1e-6) { double yaw = p.getYRot() * Mth.DEG_TO_RAD; double fx = -Math.sin(yaw), fz = Math.cos(yaw), rx = Math.cos(yaw), rz = Math.sin(yaw); float sc = (float) Mth.clamp(lc.scale(), 0.0, 1.0);
                  p.zza = (float) (lc.walk().x * fx + lc.walk().z * fz) * sc; p.xxa = (float) (lc.walk().x * rx + lc.walk().z * rz) * sc; }
              if (lc.sprint() != p.isSprinting()) p.setSprinting(lc.sprint());
              p.setJumping(lc.jump());
              return;
          } }
        boolean jumpNow = jump.pressed(); Vec3 backOff = null;
        Vec3 walk = nav.steer();
        double speed = nav.speed();
        if (nav.wantsJump()) jumpNow = true;
        if (walk == null) {
            walk = move.steer();
            speed = move.speed();
        }
        look.apply(walk);
        net.minecraft.world.level.pathfinder.Path planned = nav.getPath(); net.minecraft.world.level.pathfinder.Node nextNode = planned != null && !planned.isDone() ? planned.getNextNode() : null;
        if (walk != null && walk.lengthSqr() > 1e-4 && !p.isInLava()) { /* lava ahead is never a step: live 01:05 and 01:22 a spin by the Nether portal walked him into the same lava pocket at -10 70 -5 twice (the lip guard below only knew drops) */
            Vec3 ld = walk.normalize();
            double hv = Math.sqrt(p.getDeltaMovement().x * p.getDeltaMovement().x + p.getDeltaMovement().z * p.getDeltaMovement().z);
            double[] reaches = p.isSprinting() || hv > 0.2 ? new double[] {0.45, 0.9, 1.4} : new double[] {0.45, 0.9}; // (at a sprint he slides a block on: 0.9 was seen too late)
            Vec3 sd = new Vec3(-ld.z, 0, ld.x).scale(0.3); // his shoulders, either side
            outer:
            for (double reach : reaches) for (int k = -1; k <= 1; k++) {
                net.minecraft.core.BlockPos ah = net.minecraft.core.BlockPos.containing(p.getX() + ld.x * reach + sd.x * k, p.getY() + 0.1, p.getZ() + ld.z * reach + sd.z * k);
                if (ah.equals(p.blockPosition())) continue;
                boolean lavaAt = lavaCell(p, ah);
                if (lavaAt && k != 0 && reach > 0.5) { walk = ld.subtract(sd.scale(k * 2.0)).normalize(); continue outer; } // (only his shoulder would touch it, further on: steered off that side, not stopped; walking a lava hole's edge stalled him dead)
                if (lavaAt) {
                    p.setSprinting(false); walk = null;
                    Vec3 v = p.getDeltaMovement(); if (reach < 1.0 && v.x * ld.x + v.z * ld.z > 0) backOff = ld.scale(-1); // (and his slide checked the player's way: the keys the other way, never a hand on his speed, 1:1)
                    if (p instanceof com.claudemod.entity.ClaudeEntity ce && ce.tickCount % 40 == 0) com.claudemod.ClaudeMod.LOGGER.info("[Claude move] lava ahead at {}: not stepping (Controls.apply)", ah.toShortString());
                    break outer;
                }
            }
        }
        if (walk != null && walk.lengthSqr() > 1e-4 && p.onGround() && !p.isInWater() && !p.isInLava()) { // lava anywhere under the open cell ahead: never a step, whatever the path says (live 10:53 World 15: a path's next node 2 down off a Nether ledge, "safe" to the path, the lake 18 under it)
            Vec3 ld0 = walk.normalize();
            for (double reach : p.isSprinting() ? new double[] {0.7, 1.4} : new double[] {0.7}) {
                net.minecraft.core.BlockPos ah0 = net.minecraft.core.BlockPos.containing(p.getX() + ld0.x * reach, p.getY(), p.getZ() + ld0.z * reach);
                if (ah0.equals(p.blockPosition()) || !p.level().getBlockState(ah0).getCollisionShape(p.level(), ah0).isEmpty()) continue;
                int dn = 0; net.minecraft.core.BlockPos q0 = ah0.below(); boolean lv0 = false;
                while (dn < 48 && p.level().getBlockState(q0).getCollisionShape(p.level(), q0).isEmpty()) { if (p.level().getFluidState(q0).is(net.minecraft.tags.FluidTags.LAVA)) { lv0 = true; break; } if (!p.level().getFluidState(q0).isEmpty()) break; dn++; q0 = q0.below(); }
                if (lv0) { p.setSprinting(false); walk = null; if (p instanceof com.claudemod.entity.ClaudeEntity ce && ce.tickCount % 40 == 0) com.claudemod.ClaudeMod.LOGGER.info("[Claude move] lava {} below the open cell at {}: not stepping off (Controls.apply)", dn + 1, ah0.toShortString()); break; }
            }
        }
        { Vec3 v = p.getDeltaMovement(); double hv = Math.sqrt(v.x * v.x + v.z * v.z); // his own slide, whatever the walk says: a spin or a stall's recovery step carried him into lava past the guard on the walk's line (World 15 21:27, 115 48 -46)
          if (hv > 0.04 && !p.isInLava() && !p.isInWater()) { double vx = v.x / hv, vz = v.z / hv; boolean lv = false;
              for (double r : new double[] {0.35, 0.7, Math.min(2.0, 0.35 + hv * 5)}) { net.minecraft.core.BlockPos c0 = net.minecraft.core.BlockPos.containing(p.getX() + vx * r, p.getY() + 0.1, p.getZ() + vz * r); if (!c0.equals(p.blockPosition()) && lavaCell(p, c0)) { lv = true; break; } }
              if (lv) { backOff = new Vec3(-vx, 0, -vz); p.setSprinting(false); p.setJumping(false); jumpNow = false; if (walk != null && walk.x * vx + walk.z * vz > 0) walk = null;
                  if (p instanceof com.claudemod.entity.ClaudeEntity ce && ce.tickCount % 40 == 0) com.claudemod.ClaudeMod.LOGGER.info("[Claude move] sliding toward lava at {}: stopped (Controls.apply)", p.blockPosition().toShortString()); } } }
        if (backOff != null && walk == null) { walk = backOff; speed = 1.0; } // (back-pedalling off the lava's lip: what a player's keys do)
        boolean safeStep = nextNode != null && nextNode.y >= p.getBlockY() - 3 && Math.abs(nextNode.x + 0.5 - p.getX()) <= 1.6 && Math.abs(nextNode.z + 0.5 - p.getZ()) <= 1.6; // his path's next step is right there and at most 3 down: the pathfinder already judged that fall; the lip check below cut across a corner of a hillside and had him shuffling at the edge (the user)
        if (!safeStep && walk != null && walk.lengthSqr() > 1e-4 && p.onGround() && !p.isInWater()) { // (a jump too: a crit's hop at a fortress deck's edge carried him off it into the lava 31 down, World 15 22:48; leaps run on their own, above) // the lip of a drop that would hurt (4+ blocks, no water below): no walking off it; the step-down rules cut a way instead (falls hurt him now, like anyone)
            Vec3 dir = walk.normalize();
            for (double reach : p.isSprinting() ? new double[] {0.7, 1.4} : new double[] {0.7}) { // at a sprint he covers the last block in three ticks: the lip has to be seen a block sooner, and the sprint dropped so he stops in time
                net.minecraft.core.BlockPos ahead = net.minecraft.core.BlockPos.containing(p.getX() + dir.x * reach, p.getY(), p.getZ() + dir.z * reach);
                if (ahead.equals(p.blockPosition()) || !p.level().getBlockState(ahead).getCollisionShape(p.level(), ahead).isEmpty()) continue;
                int drop = 0; boolean water = false, lava = false; net.minecraft.core.BlockPos q = ahead.below();
                while (drop < 40 && p.level().getBlockState(q).getCollisionShape(p.level(), q).isEmpty()) { var fq = p.level().getFluidState(q); if (!fq.isEmpty()) { if (fq.is(net.minecraft.tags.FluidTags.LAVA)) lava = true; else water = true; break; } drop++; q = q.below(); } // (only water is a landing: live 10:53 World 15 the lava lake 18 under a Nether ledge passed as "water below", he walked off it with his whole kit)
                if (lava) { p.setSprinting(false); walk = null; if (p instanceof com.claudemod.entity.ClaudeEntity ce && ce.tickCount % 40 == 0) com.claudemod.ClaudeMod.LOGGER.info("[Claude move] lava {} below the lip at {}: not stepping off (Controls.apply)", drop + 1, ahead.toShortString()); break; } // (the whole column: water at the bottom of a deep drop is a fine landing, like any player jumping into a pool)
                if (drop >= 4 && !water) { jumpNow = false; p.setJumping(false); if (reach > 1) { p.setSprinting(false); walk = walk.scale(0.3); } else walk = null; break; } // a block out: ease off; at the lip: stop
            }
        }
        if (walk != null && walk.lengthSqr() > 1e-4) {
            double yaw = p.getYRot() * Mth.DEG_TO_RAD;
            double fx = -Math.sin(yaw), fz = Math.cos(yaw); // where he's facing
            double rx = Math.cos(yaw), rz = Math.sin(yaw);  // his left
            float scale = (float) Mth.clamp(speed, 0.2, 1.0);
            if (p.isShiftKeyDown() || p.isVisuallyCrawling()) scale *= 0.3f;        // sneaking (and crawling under a low ceiling) is slow, the same as the vanilla client
            if (p.isUsingItem()) scale *= 0.2f;           // using something (a shield up, eating) is slower still, the same as the vanilla client
            p.zza = (float) (walk.x * fx + walk.z * fz) * scale;
            p.xxa = (float) (walk.x * rx + walk.z * rz) * scale;
            // a player sprints wherever he's going (the user: sprinting and sprint-jumping are the default), walks only for the last couple of blocks, sneaking, eating, a shield up, or an empty stomach
            var path = nav.getPath();
            double left = path != null && !path.isDone() ? Math.sqrt(p.blockPosition().distSqr(path.getTarget())) : move.wantedDist();
            boolean sprint = (speed > 1.1 || left > 2.5) && p.zza > 0.5f && !p.isShiftKeyDown() && p.getFoodData().getFoodLevel() > 6 && !p.isUsingItem();
            if (sprint != p.isSprinting()) p.setSprinting(sprint);
            if (sprint && left > 4 && p.onGround() && !p.isInWater() && !jumpNow && p.getFoodData().getFoodLevel() >= 10 && !com.claudemod.entity.ClaudeSpawner.testing && openGround(p, walk.normalize())) jumpNow = true; // (off in the test box: its stages are a few blocks each, and a hop at a stage's edge reads as the wrong move) // sprint-jumping on open ground: faster, at a hunger price he can afford
            if (jumpNow) p.setJumping(true);
        } else if (p.isSprinting()) {
            p.setSprinting(false);
        }
        // In water, like a player: swim toward where he's going. Going up (or nowhere): hold space and stay afloat.
        // Going down (a flooded staircase, a lake bed): dive. Running out of air: up for a breath no matter what.
        if (p.isInWater() && p.getFluidHeight(net.minecraft.tags.FluidTags.WATER) > p.getFluidJumpThreshold()) {
            var path = nav.getPath();
            net.minecraft.core.BlockPos goal = path != null && !path.isDone() ? path.getNextNodePos()
                    : Double.isNaN(move.wantedY()) ? null : net.minecraft.core.BlockPos.containing(move.wanted);
            // only dive for somewhere underwater; a lower spot on dry land (past a river bank) means swim across and climb out
            boolean diving = goal != null && goal.getY() < p.getBlockY() && p.getAirSupply() > 60 // a block below his feet block, not half a block below his y: afloat at y+0.5 in a 2-deep pool every goal on his own level read as a dive and pinned him to the bottom
                    && p.level().getFluidState(goal).is(net.minecraft.tags.FluidTags.WATER);
            if (diving) p.setDeltaMovement(p.getDeltaMovement().add(0, -0.04, 0)); // the same push down a sneaking swimmer gets
            else if (!wading(p) || (p.horizontalCollision && goal != null && !(p instanceof com.claudemod.entity.ClaudeEntity ce0 && (ce0.staying || ce0.currentTask instanceof com.claudemod.entity.life.Task.Break || ce0.currentTask instanceof com.claudemod.entity.life.Task.Place)))) jumpNow = true; // (against a wall only when he means to walk on: in the user's 1x1 water pocket he touched the walls all the time and hopped, mining at a fifth of the speed, 15:15) // (shallow water: he stands in it, space only to step up onto a ledge)
        }
        p.setJumping(jumpNow);
    }

    /** In shallow water, head out and the bottom right under him: a player just stands and walks there (full mining speed). */
    public static boolean wading(net.minecraft.world.entity.Entity p) {
        if (!p.isInWater() || p.isEyeInFluid(net.minecraft.tags.FluidTags.WATER)) return false;
        net.minecraft.core.BlockPos under = net.minecraft.core.BlockPos.containing(p.getX(), p.getY() - 0.6, p.getZ());
        return !p.level().getBlockState(under).getCollisionShape(p.level(), under).isEmpty();
    }
}
