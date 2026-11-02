package com.claudemod.entity.life;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.claudemod.ClaudeMod;
import com.claudemod.entity.ClaudeEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.EyeOfEnder;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The stronghold's small half (the user, via Day to Day): eyes of ender thrown as a player throws them, the stronghold found from where
 * they fly, and a staircase dug down to its stone bricks.
 * <ul>
 * <li>A throw is his right click with an eye in hand (vanilla EnderEyeItem): he watches where it flies; 25 ticks on, the heading from
 * where he threw it to where it is, and whether it rose (the stronghold far off) or went down (within 12: vanilla's eye flies to it).
 * It drops back 80% of the time; he picks it up.</li>
 * <li>Two headings from throws ~200 blocks apart (the second off the first heading by 45 degrees, so the lines cross well) give the
 * spot where they cross (the speedrunner's triangulation). He walks there and throws again; when the eye goes down he's on it.</li>
 * <li>There, a staircase down (never straight down: one step down per block along, three cells cut each step, turned away from any
 * fluid) till he sees stone bricks, mossy, cracked or infested stone, or iron bars beside a cell he opened. Then he stops and says so.</li>
 * </ul>
 * Each throw is logged with where from, the heading and the estimate.
 */
public final class EyeHunt {
    private EyeHunt() {}

    /** A throw: where from (his eyes), the heading (radians, atan2 of z over x), where the eye was when read, and whether it went down. */
    public record Throw(Vec3 from, double angle, Vec3 at, boolean down) {}
    static final class Hunt {
        final List<Throw> throwsMade = new ArrayList<>();
        UUID eye; Vec3 eyeFrom; long thrownAt; Vec3 lastAt; long pickupBy;
        BlockPos second, digAt, found, spot; Direction dig; int steps;
    }
    private static Map<UUID, Hunt> hunts; // per bot (static: fields added live start null)
    static Hunt hunt(ClaudeEntity g) { if (hunts == null) hunts = new HashMap<>(); return hunts.computeIfAbsent(g.getUUID(), k -> new Hunt()); }
    public static void reset(ClaudeEntity g) { if (hunts != null) hunts.remove(g.getUUID()); }
    static long now(ClaudeEntity g) { return g.getServer() == null ? g.tickCount : g.getServer().getTickCount(); }

    /**
     * Test hook only: the test world is superflat and has no stronghold, so vanilla's eye never flies there. When set, a throw that
     * produced no eye gets one made the way vanilla makes it, sent to this spot. Null in the game.
     */
    public static java.util.function.Function<ClaudeEntity, BlockPos> testStronghold;

    public static Throw lastThrow(ClaudeEntity g) { var h = hunt(g); return h.throwsMade.isEmpty() ? null : h.throwsMade.get(h.throwsMade.size() - 1); }
    public static int throwCount(ClaudeEntity g) { return hunt(g).throwsMade.size(); }
    public static BlockPos found(ClaudeEntity g) { return hunt(g).found; }

    public static String skill(ClaudeEntity g, ServerPlayer player, ServerLevel level, Skills.Step st) {
        if (level.dimension() != Level.OVERWORLD) return "failed: strongholds are in the Overworld (I'm in the " + level.dimension().location().getPath() + ")";
        Hunt h = hunt(g);
        if (h.found != null) return "done: stone bricks at " + h.found.toShortString() + ", the stronghold (" + h.throwsMade.size() + " throws)";
        Skills.Step again = new Skills.Step("find_stronghold", st.target(), st.count(), st.where());
        // an eye in the air: read it
        if (h.eye != null) {
            long age = now(g) - h.thrownAt;
            var e = level.getEntity(h.eye);
            if (e instanceof EyeOfEnder eye && eye.isAlive() && age < 25) { g.getLookControl().setLookAt(eye); g.tasks.add(new Task.Wait((int) (25 - age), false)); g.skillQueue.addFirst(again); return "watching the eye fly"; }
            h.eye = null;
            if (e instanceof EyeOfEnder eye && eye.isAlive()) {
                Vec3 at = eye.position(); double dx = at.x - h.eyeFrom.x, dz = at.z - h.eyeFrom.z;
                boolean down = at.y < h.eyeFrom.y + 0.5 || Math.sqrt(dx * dx + dz * dz) < 1.5;
                Throw tw = new Throw(h.eyeFrom, Math.atan2(dz, dx), at, down); h.throwsMade.add(tw); h.lastAt = at; h.pickupBy = now(g) + 100;
                BlockPos est = estimate(h);
                ClaudeMod.LOGGER.info("[Claude eye] throw {} from {}: heading {} ({} deg), {} (the eye {} up, {} along); estimate {} (EyeHunt)", h.throwsMade.size(), BlockPos.containing(h.eyeFrom).toShortString(),
                        Perception.compassOf(new Vec3(Math.cos(tw.angle()), 0, Math.sin(tw.angle()))), (int) Math.toDegrees(tw.angle()), down ? "it went DOWN: the stronghold is right here" : "it rose: the stronghold is farther on",
                        String.format("%.1f", at.y - h.eyeFrom.y), String.format("%.1f", Math.sqrt(dx * dx + dz * dz)), est == null ? "none yet" : est.toShortString());
                g.tasks.add(new Task.Wait(60, false)); g.skillQueue.addFirst(again); // (it falls or breaks at 80 ticks)
                return "the eye flew " + Perception.compassOf(new Vec3(Math.cos(tw.angle()), 0, Math.sin(tw.angle()))) + (down ? " and went down: the stronghold is here" : "") + (est == null ? "" : "; my estimate " + est.toShortString());
            }
            ClaudeMod.LOGGER.info("[Claude eye] the eye was gone before I could read it (EyeHunt)");
        }
        // a dropped eye: back in the pack
        if (h.lastAt != null && now(g) < h.pickupBy + 200) {
            var drops = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new AABB(BlockPos.containing(h.lastAt)).inflate(24, 16, 24), i -> i.isAlive() && i.getItem().is(Items.ENDER_EYE));
            var drop = drops.stream().filter(i -> FortressFight.clear(level, g.getEyePosition(), i.position().add(0, 0.2, 0), g)).min(java.util.Comparator.comparingDouble(i -> i.distanceToSqr(g))).orElse(null); // (one he can see: an eye that went down into the ground is lost to him, as to a player)
            Throw lt0 = lastThrow(g);
            if (drop == null && !drops.isEmpty() && lt0 != null && lt0.down() && h.spot == null) { h.spot = BlockPos.containing(drops.get(0).getX(), g.getY(), drops.get(0).getZ()); h.lastAt = null; ClaudeMod.LOGGER.info("[Claude eye] the eye went down into the ground at {}: that's the spot (EyeHunt)", h.spot.toShortString()); }
            if (drop != null) { h.lastAt = null; Throw lt = lastThrow(g); if (lt != null && lt.down()) h.spot = drop.blockPosition().immutable(); /* (it went down: vanilla flew it onto the stronghold; where it drops is the spot) */ g.tasks.add(new Task.GoTo(drop.blockPosition(), 1.0)); g.tasks.add(new Task.Pickup(60)); g.skillQueue.addFirst(again);
                ClaudeMod.LOGGER.info("[Claude eye] the eye dropped at {}: picking it back up (EyeHunt)", drop.blockPosition().toShortString()); return "picking the eye back up at " + drop.blockPosition().toShortString(); }
            if (now(g) >= h.pickupBy) { h.lastAt = null; ClaudeMod.LOGGER.info("[Claude eye] the eye broke (no drop): {} eyes left (EyeHunt)", eyes(g)); }
            else { g.tasks.add(new Task.Wait(20, false)); g.skillQueue.addFirst(again); return "waiting for the eye to come down"; }
        }
        // digging down at the spot
        if (h.digAt != null) return dig(g, level, h, again);
        Throw last = lastThrow(g);
        if (last != null && last.down()) {
            BlockPos spot = h.spot != null ? h.spot : BlockPos.containing(last.at().x, g.getY(), last.at().z);
            if (horiz(g.position(), Vec3.atCenterOf(spot)) > 3) { g.tasks.add(new Task.GoTo(surface(level, spot), 1.0)); g.skillQueue.addFirst(again); return "to where the eye went down, " + spot.toShortString(); }
            h.digAt = g.blockPosition(); h.dig = g.getDirection();
            ClaudeMod.LOGGER.info("[Claude eye] at the spot the eye went down, {}: a staircase down to the stronghold, heading {} (EyeHunt)", h.digAt.toShortString(), h.dig.getName());
            return dig(g, level, h, again);
        }
        // eyes on hand
        if (eyes(g) == 0) {
            boolean canCraft = g.inventory.count(x -> x.is(Items.ENDER_PEARL)) > 0 && g.inventory.count(x -> x.is(Items.BLAZE_POWDER) || x.is(Items.BLAZE_ROD)) > 0;
            if (!canCraft) return "failed: no eyes of ender and nothing to make one (a pearl and blaze powder each): " + h.throwsMade.size() + " throws so far";
            g.skillQueue.addFirst(again); g.skillQueue.addFirst(new Skills.Step("obtain", "ender_eye", 1));
            return "out of eyes: making one (a pearl and blaze powder)";
        }
        // where to throw from
        Vec3 me = g.getEyePosition();
        BlockPos est = estimate(h);
        if (last == null) return throwEye(g, level, h, again, "the first throw");
        if (est == null) {
            if (h.second == null) { double a = last.angle() + Math.PI / 4, d = st.count() > 1 ? st.count() : 200; h.second = BlockPos.containing(last.from().x + Math.cos(a) * d, g.getY(), last.from().z + Math.sin(a) * d);
                if (h.throwsMade.size() >= 2) { a = last.angle(); h.second = BlockPos.containing(last.from().x + Math.cos(a) * d, g.getY(), last.from().z + Math.sin(a) * d); } // (two that don't cross: on along the last one, then again)
                ClaudeMod.LOGGER.info("[Claude eye] one heading so far: the next throw from {}, {} off it, so the two lines cross (EyeHunt)", h.second.toShortString(), (int) d); }
            if (horiz(g.position(), Vec3.atCenterOf(h.second)) > 6) return walkToward(g, level, h.second, again, "to throw again from " + h.second.toShortString());
            h.second = null; return throwEye(g, level, h, again, "the second throw, off the first line");
        }
        double toEst = horiz(g.position(), Vec3.atCenterOf(est));
        if (toEst > 24) return walkToward(g, level, est, again, "to the estimate " + est.toShortString() + " (" + (int) toEst + " off)");
        if (horiz(last.from(), me) < 16) { h.throwsMade.remove(h.throwsMade.size() - 1); } // (thrown here already and it rose: the estimate is off; that throw counts again as a fresh line from here)
        return throwEye(g, level, h, again, "at the estimate");
    }

    static int eyes(ClaudeEntity g) { return g.inventory.count(x -> x.is(Items.ENDER_EYE)); }
    static double horiz(Vec3 a, Vec3 b) { double dx = a.x - b.x, dz = a.z - b.z; return Math.sqrt(dx * dx + dz * dz); }
    static BlockPos surface(ServerLevel level, BlockPos p) { return level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p); }

    /** Where the last two rising throws' lines cross, ahead of both; null with fewer than two, or lines within 5 degrees of each other. */
    static BlockPos estimate(Hunt h) {
        List<Throw> up = new ArrayList<>(); for (Throw t : h.throwsMade) if (!t.down()) up.add(t);
        if (up.size() < 2) return null;
        Throw a = up.get(up.size() - 2), b = up.get(up.size() - 1);
        double ax = Math.cos(a.angle()), az = Math.sin(a.angle()), bx = Math.cos(b.angle()), bz = Math.sin(b.angle());
        double den = ax * bz - az * bx; if (Math.abs(den) < Math.sin(Math.toRadians(5))) return null;
        double wx = b.from().x - a.from().x, wz = b.from().z - a.from().z;
        double ta = (wx * bz - wz * bx) / den, tb = (wx * az - wz * ax) / den;
        if (ta < 0 || tb < 0) return null; // (they cross behind him: not where the eyes went)
        return BlockPos.containing(a.from().x + ax * ta, a.from().y, a.from().z + az * ta);
    }

    static String walkToward(ClaudeEntity g, ServerLevel level, BlockPos to, Skills.Step again, String why) {
        Vec3 d = Vec3.atCenterOf(to).subtract(g.position()); double len = Math.sqrt(d.x * d.x + d.z * d.z), leg = Math.min(len, 96);
        BlockPos step = surface(level, BlockPos.containing(g.getX() + d.x / len * leg, g.getY(), g.getZ() + d.z / len * leg));
        g.tasks.add(new Task.GoTo(step, 2.0)); g.skillQueue.addFirst(again);
        return "walking " + why + ", " + (int) len + " to go";
    }

    static String throwEye(ClaudeEntity g, ServerLevel level, Hunt h, Skills.Step again, String which) {
        if (!g.onGround()) { g.tasks.add(new Task.Wait(10, false)); g.skillQueue.addFirst(again); return "landing first, then the throw"; }
        g.getNavigation().stop();
        if (!g.hold(s -> s.is(Items.ENDER_EYE))) return "failed: couldn't get an eye in hand";
        g.setXRot(-20f);
        int before = eyes(g); Vec3 from = new Vec3(g.getX(), g.getY(0.5), g.getZ());
        g.useHeld(); // (vanilla EnderEyeItem: the eye flies to the nearest stronghold, or nothing happens with none in the world)
        EyeOfEnder eye = level.getEntitiesOfClass(EyeOfEnder.class, g.getBoundingBox().inflate(3), e -> e.isAlive() && e.tickCount < 3).stream().findFirst().orElse(null);
        if (eye == null && testStronghold != null) { BlockPos target = testStronghold.apply(g);
            if (target != null) { ItemStack st = g.getMainHandItem(); eye = new EyeOfEnder(level, g.getX(), g.getY(0.5), g.getZ()); eye.setItem(st); eye.signalTo(target); level.addFreshEntity(eye); st.shrink(1); } } // (the test world's stand-in for the world's own stronghold: vanilla's eye, made as vanilla makes it)
        if (eye == null) { ClaudeMod.LOGGER.info("[Claude eye] threw an eye at {} and it didn't fly: no stronghold in this world, or not this dimension (EyeHunt)", g.blockPosition().toShortString()); return "failed: the eye didn't fly: there's no stronghold it can find from here"; }
        h.eye = eye.getUUID(); h.eyeFrom = from; h.thrownAt = now(g);
        ClaudeMod.LOGGER.info("[Claude eye] {}: threw an eye at {} ({} eyes left) (EyeHunt)", which, g.blockPosition().toShortString(), eyes(g));
        g.tasks.add(new Task.Wait(25, false)); g.skillQueue.addFirst(again);
        return "threw an eye of ender (" + which + "), watching where it goes";
    }

    static boolean strongholdBlock(net.minecraft.world.level.block.state.BlockState s) {
        return s.is(Blocks.STONE_BRICKS) || s.is(Blocks.MOSSY_STONE_BRICKS) || s.is(Blocks.CRACKED_STONE_BRICKS) || s.is(Blocks.CHISELED_STONE_BRICKS) || s.is(Blocks.INFESTED_STONE_BRICKS) || s.is(Blocks.INFESTED_MOSSY_STONE_BRICKS) || s.is(Blocks.INFESTED_CRACKED_STONE_BRICKS) || s.is(Blocks.IRON_BARS) || s.is(Blocks.END_PORTAL_FRAME);
    }

    /** A step of the staircase, or done: stronghold blocks beside a cell he opened. */
    static String dig(ClaudeEntity g, ServerLevel level, Hunt h, Skills.Step again) {
        BlockPos f = BlockPos.containing(g.getX(), g.getY() + 0.2, g.getZ());
        for (BlockPos q : BlockPos.betweenClosed(f.offset(-2, -2, -2), f.offset(2, 3, 2))) {
            if (!strongholdBlock(level.getBlockState(q))) continue;
            var hit = level.clip(new net.minecraft.world.level.ClipContext(g.getEyePosition(), Vec3.atCenterOf(q), net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, g));
            if (hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK || !hit.getBlockPos().equals(q)) continue; // (seen: the first block on the line from his eyes; never one behind the stone, even with the room's own air on its far side)
            h.found = q.immutable();
            ClaudeMod.LOGGER.info("[Claude eye] stone bricks at {} ({}), {} steps down from {}: the stronghold. Stopping here (EyeHunt)", h.found.toShortString(), level.getBlockState(q).getBlock().getName().getString(), h.steps, h.digAt.toShortString());
            return "done: broke into the stronghold, " + level.getBlockState(q).getBlock().getName().getString().toLowerCase() + " at " + h.found.toShortString() + " (" + h.steps + " steps down)";
        }
        if (h.steps > 80 || f.getY() <= level.getMinBuildHeight() + 5) return "failed: " + h.steps + " steps down and no stone bricks (at " + f.toShortString() + ")";
        for (int turn = 0; turn < 4; turn++) {
            Direction d = h.dig; BlockPos next = f.relative(d).below();
            BlockPos[] cut = {f.relative(d).above(), f.relative(d), next};
            boolean ok = !level.getBlockState(next.below()).getCollisionShape(level, next.below()).isEmpty();
            for (BlockPos c : cut) { if (level.getBlockState(c).getDestroySpeed(level, c) < 0) ok = false; for (Direction n : Direction.values()) if (!level.getFluidState(c.relative(n)).isEmpty()) ok = false; }
            if (!ok) { h.dig = h.dig.getClockWise(); continue; }
            for (BlockPos c : cut) if (!level.getBlockState(c).isAir()) g.tasks.add(new Task.Break(c.immutable()));
            g.tasks.add(new Task.GoTo(next.immutable(), 0.3)); h.steps++; g.skillQueue.addFirst(again);
            if (h.steps % 5 == 0) for (Direction side : new Direction[]{d.getClockWise(), d.getCounterClockWise()}) { BlockPos w = f.relative(d).relative(side); if (level.getBlockState(w).isFaceSturdy(level, w, side.getOpposite())) { g.tasks.add(new Task.WallTorch(f.relative(d).immutable(), side)); break; } } // a torch every 5 steps on the side wall, like a player's stairs (the user: "torches for sure")
            if (h.steps % 8 == 1) ClaudeMod.LOGGER.info("[Claude eye] staircase step {} going {}, down to y {} (EyeHunt)", h.steps, d.getName(), next.getY());
            return "digging a staircase down to the stronghold (step " + h.steps + ", y " + next.getY() + ")";
        }
        return "failed: fluid or bedrock round the staircase at " + f.toShortString() + " every way";
    }
}
