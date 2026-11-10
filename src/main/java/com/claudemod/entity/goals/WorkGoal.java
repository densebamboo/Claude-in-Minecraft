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
    private BlockPos noWayFor; private String noWayWhy; // (the walk's planners all said no way to this spot: its give-up's cause is NO_PATH)

    private final ClaudeEntity g;
    private Task current;
    private int ticks;
    /** Lava over the cell or beside the space over it (up to 4): leaning out over it puts him where it falls (stage 1196). Lava under it is the bridge's own. */
    private static boolean lavaOverCell(ServerLevel level, BlockPos pos) { for (int k = 1; k <= 4; k++) { BlockPos q = pos.above(k); if (level.getFluidState(q).is(net.minecraft.tags.FluidTags.LAVA)) return true; for (Direction d : Direction.Plane.HORIZONTAL) if (level.getFluidState(q.relative(d)).is(net.minecraft.tags.FluidTags.LAVA)) return true; } return false; }
    private BlockPos leanFor; // the cell he leans out over the edge to place (sneaking): the sneak comes off once it's placed or he's on to something else
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

    private boolean roofDig(Task task) {
        return task instanceof Task.Break b && b.pos().getX() == g.getBlockX() && b.pos().getZ() == g.getBlockZ() && b.pos().getY() > g.getEyeY();
    }

    /** Nearest spot to breathe: straight up through the water first, else any open air pocket close by. */
    private boolean noOpenAir; // breathingSpot's last answer was its own roof dig: nothing open in reach
    private boolean footing() { BlockPos b = g.blockPosition().below(); return g.onGround() || !g.level().getBlockState(b).getCollisionShape(g.level(), b).isEmpty(); } // standing, or in a cell with rock under it
    private BlockPos breathingSpot() {
        var level = g.level(); noOpenAir = false;
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
            cands.add((level.getFluidState(under).isEmpty() ? q : under).immutable()); /* (dry: he stands in q; the floor block as the spot was a solid target no path reaches, so every dry pocket round a corner was turned down) */ dists.add(q.distSqr(eye) + (com.claudemod.director.ClaudeLife.isMineCell(g, under) || com.claudemod.director.ClaudeLife.isMineCell(g, q) ? 500 : 1000)); // (after the open columns; his own mine before any other pocket)
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
        { // the way he came: he swam in along it, so it's water he can swim back through (the user, 11-04: "he opens a water pocket and swims into it and doesnt swim back through it"; 00:50 he aimed at air 5 blocks back through a wall and drowned)
            java.util.List<BlockPos> back = com.claudemod.entity.nav.Route.wayBack(g, 20 * 60);
            // the shortest way through the cells he swam, to the nearest with air over it (by recency, a cell he'd poked into after coming down read as "on the way back": live 22:14 11-04 his aim flipped between the shaft and a dead end below as he bobbed a cell up and down, and he drowned 19 cells from air)
            java.util.Set<BlockPos> cells = new java.util.HashSet<>(); // his trail and any open cell beside it (a corner he cut leaves no trail cell between two of its own)
            for (BlockPos q : back) { cells.add(q); for (Direction d : Direction.values()) { BlockPos n = q.relative(d); if (level.getBlockState(n).getCollisionShape(level, n).isEmpty()) cells.add(n.immutable()); } }
            BlockPos start = cells.contains(g.blockPosition()) ? g.blockPosition() : null; double sd = Double.MAX_VALUE;
            if (start == null) for (BlockPos q : back) { double d0 = q.distToCenterSqr(g.position()); if (d0 < sd) { sd = d0; start = q; } }
            java.util.Map<BlockPos, BlockPos> came = new java.util.HashMap<>(); java.util.ArrayDeque<BlockPos> frontier = new java.util.ArrayDeque<>(); BlockPos goal = null;
            if (start != null) { came.put(start, start); frontier.add(start); }
            while (!frontier.isEmpty() && goal == null) {
                BlockPos q = frontier.poll(), qa = q.above();
                if (q != start && !level.getFluidState(q).isEmpty() && level.getFluidState(qa).isEmpty() && level.getBlockState(qa).getCollisionShape(level, qa).isEmpty()) { goal = q; break; }
                for (Direction d : Direction.values()) { BlockPos n = q.relative(d); if (cells.contains(n) && !came.containsKey(n) && (d != Direction.UP || level.getBlockState(n.above()).getCollisionShape(level, n.above()).isEmpty())) { came.put(n, q); frontier.add(n); } } // (up only with room over the cell for his head: live 00:56 11-05 the first step was up into his own head cell under rock, he jumped into it 10 s, health 20 -> 0.5) // (face to face: a diagonal past two rock corners is in sight but his body doesn't fit, 22:14 stage 650)
            }
            if (goal != null) {
                java.util.List<BlockPos> way = new java.util.ArrayList<>(); for (BlockPos q = goal; ; q = came.get(q)) { way.add(0, q); if (q.equals(start)) break; } // start .. goal
                BlockPos here0 = g.blockPosition();
                BlockPos aim = way.size() > 1 && way.get(0).equals(here0) ? way.get(1) : way.get(0); // the next cell along it, one face at a time (floated off it: back onto it first)
                if (g.tickCount % 20 == 0) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] no air in sight at {}: back the way he came, toward {} (air over {}, {} cells along his trail; body y {} pose {} sprint {} swimming {} hcoll {} vcoll {} motion {}; aim {}/{} head {})", g.blockPosition().toShortString(), aim.toShortString(), goal.toShortString(), way.size() - 1, String.format("%.2f", g.getY()), g.getPose(), g.isSprinting(), g.isSwimming(), g.horizontalCollision, g.verticalCollision, g.getDeltaMovement().toString(), level.getBlockState(aim).getBlock().getName().getString(), level.getFluidState(aim).isEmpty() ? "dry" : "wet", level.getBlockState(aim.above()).getBlock().getName().getString()); // (11-04 17:35 and 17:37: two drownings at 535,13,117 aimed 2 cells back and never moved; the log had no body state to say why)
                return aim;
            }
        }
        // nothing open within reach: a player digs the roof out over his head (he has a few seconds of air for it)
        if (!footing()) { // floating, no floor under him: a ledge under the roof close by first, to dig from standing (5x slow under water, 25x floating; with no floor near he sank away from the dig and drowned: stage 1259)
            BlockPos ledge = null; double ld = Double.MAX_VALUE;
            for (BlockPos q : BlockPos.betweenClosed(eye.offset(-6, -2, -6), eye.offset(6, 0, 6))) {
                if (!level.getFluidState(q).is(net.minecraft.tags.FluidTags.WATER) || level.getBlockState(q.below()).getCollisionShape(level, q.below()).isEmpty()) continue; // water to stand in, rock to stand on
                BlockPos r = q.above(), r2 = q.above(2); BlockState rq = level.getBlockState(r);
                if (rq.getCollisionShape(level, r).isEmpty() || rq.getDestroySpeed(level, r) < 0 || rq.hasBlockEntity() || !level.getFluidState(r2).isEmpty() || !level.getBlockState(r2).getCollisionShape(level, r2).isEmpty()) continue; // one block of roof with air over it
                double d0 = q.distToCenterSqr(g.getEyePosition()); if (d0 >= ld) continue;
                if (level.clip(new net.minecraft.world.level.ClipContext(g.getEyePosition(), Vec3.atCenterOf(q), net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, g)).getType() != net.minecraft.world.phys.HitResult.Type.MISS) continue; // a straight swim
                ledge = q.immutable(); ld = d0;
            }
            if (ledge != null) { if (g.tickCount % 20 == 0) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] no air in reach at {}, no floor under him: to the ledge at {} to dig up from standing (WorkGoal.breathingSpot)", g.blockPosition().toShortString(), ledge.toShortString()); return ledge; }
        }
        noOpenAir = true;
        BlockPos roof = eye.above();
        BlockState rs = level.getBlockState(roof);
        if (level.getFluidState(roof.above()).is(net.minecraft.tags.FluidTags.WATER) && !cands.isEmpty()) { int i = 0; for (int j = 1; j < cands.size(); j++) if (dists.get(j) < dists.get(i)) i = j; com.claudemod.ClaudeMod.LOGGER.info("[Claude life] water over the roof at {}: back toward the air at {} instead of digging up into it", g.blockPosition().toShortString(), cands.get(i).toShortString()); return cands.get(i); } // (digging up into more water drowned him at 50, 18, 82: the air was back along his tunnel)
        if (!rs.getCollisionShape(level, roof).isEmpty() && rs.getDestroySpeed(level, roof) >= 0 && !rs.hasBlockEntity() && !g.tasks.contains(new Task.Break(roof)) && !new Task.Break(roof.immutable()).equals(current)) {
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
            if (to.length() <= 3.0 && fb.getOwner() instanceof net.minecraft.world.entity.monster.Ghast gh && gh.isAlive() && g.hasLineOfSight(gh)) g.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, g.getEyePosition().add(gh.getBoundingBox().getCenter().subtract(fb.position()))); // (the swing sends it along his look from where the ball is, vanilla's aim deflect: so he looks the way from the ball to the ghast, never his eye's line (the ball is up to 3 off it, a miss on a 4-wide ghast) nor back along the ball's own line; live 20:22 11-08 the ghast had drifted, the ball flew past it 30 more blocks into a zombified piglin and the horde killed him)
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
                if (g.tickCount - fireSaid > 60) { fireSaid = g.tickCount; com.claudemod.ClaudeMod.LOGGER.info("[Claude life] in fire at {} ({}, {}, {}; fire at {}): off it to {} first (WorkGoal.tick)", ff.toShortString(), String.format("%.2f", g.getX()), String.format("%.2f", g.getY()), String.format("%.2f", g.getZ()), BlockPos.betweenClosedStream(g.getBoundingBox().deflate(0.001)).filter(p -> flv.getBlockState(p).is(net.minecraft.tags.BlockTags.FIRE)).map(BlockPos::toShortString).toList(), to.toShortString()); }
                return true;
            }
            if (g.tickCount - fireSaid > 60) { fireSaid = g.tickCount; com.claudemod.ClaudeMod.LOGGER.info("[Claude life] in fire at {}: no fire-free spot within 2 to step to, the job's own way out (WorkGoal.tick)", ff.toShortString()); }
        }
        // Air first, like a player watching his bubbles: underwater and getting low, everything waits while he comes up to
        // breathe (and he stays up till he's got his breath back).
        boolean underwater = g.isEyeInFluid(net.minecraft.tags.FluidTags.WATER);
        if (((underwater && g.getAirSupply() < 120) || (surfacing && g.getAirSupply() < 280 && g.isInWater())) && !com.claudemod.entity.life.Watchdog.airRoute(g)) { // (the watchdog's dug way to air, once the swim-up failed: its tasks run, not this)
            BlockPos spot = breathingSpot();
            boolean diggingRoof = roofDig(current) && noOpenAir; // Keep the same overhead dig when he sinks toward the floor. (only a dig for air: with air open in reach, a dig over his head is a step of his way on and waits; live 10:51 11-09 he mined one floating, sank out of reach and drowned with the air one cell over: stage 1258)
            if (!surfacing) {
                surfacing = true;
                if (current != null && !diggingRoof) { clearCrack(); Task air = noOpenAir && roofDig(g.tasks.peek()) ? g.tasks.poll() : null; g.tasks.addFirst(current); if (air != null) g.tasks.addFirst(air); current = null; g.currentTask = null; } // (breathingSpot's own dig for air stays first)
                com.claudemod.ClaudeMod.LOGGER.info("[Claude life] low on air ({}) at {}: coming up to breathe", g.getAirSupply(), g.blockPosition().toShortString());
            }
            if (diggingRoof || (roofDig(g.tasks.peek()) && current == null && noOpenAir)) { // the roof: dug through below, this tick (returning here every tick left the dig queued and him drowning under it)
                BlockPos roofOverEyes = ((Task.Break) (diggingRoof ? current : g.tasks.peek())).pos();
                g.getNavigation().stop(); // Stay grounded while mining: swimming adds the airborne mining penalty.
                if (!footing()) g.getJumpControl().jump(); // (no floor under him and no ledge near: held up at the roof, 25x slow but in reach; let go, he sank out of reach of it: stage 1260)
                if (g.tickCount % 20 == 0) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] digging the roof at {} for air (air {}, {})", roofOverEyes.toShortString(), g.getAirSupply(), diggingRoof ? "under way" : "starting");
            } else {
                var line = g.level().clip(new net.minecraft.world.level.ClipContext(g.position().add(0, 0.4, 0), Vec3.atCenterOf(spot), /* (body to the spot's own cell: its head cell can be rock under a low ceiling, and a line to rock sent him to the pathfinder, which can't swim) */ net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, g));
                if (line.getType() != net.minecraft.world.phys.HitResult.Type.MISS && g.tickCount % 10 == 0) g.getNavigation().moveTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, 1.0); // round a corner: the route, not a swim into the wall
                else if (line.getType() == net.minecraft.world.phys.HitResult.Type.MISS) { g.getNavigation().stop(); g.getMoveControl().setWantedPosition(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, 1.0); }
                if (spot.getY() > g.getBlockY() || (spot.getY() == g.getBlockY() && g.level().getBlockState(spot.above()).getCollisionShape(g.level(), spot.above()).isEmpty())) g.getJumpControl().jump(); // (no jump toward a cell with rock over it: the way on is under that rock, swum low) // swim up only when the air is up: air below means letting himself sink to it (holding jump kept him floating at the top with his mine's air under him)
                g.getLookControl().setLookAt(spot.getX() + 0.5, spot.getY() + 1.5, spot.getZ() + 0.5);
                return true;
            }
        }
        if (!(surfacing && ((current == null && roofDig(g.tasks.peek())) || roofDig(current)))) surfacing = false; // (still surfacing while the roof dig runs below: not a fresh start, no re-queue, every tick)
        // In lava: out first, everything else waits (he stood 3 s in a lava flow finishing a Place and died, 09:02)
        if (!g.isInLava() && g.onGround() && g.tickCount % 5 == 0 && g.level().getFluidState(g.blockPosition()).isEmpty()) { BlockPos f0 = g.blockPosition().immutable(); if (!f0.equals(dryCells.peekLast())) { dryCells.addLast(f0); if (dryCells.size() > 12) dryCells.pollFirst(); } } // (the cells he stood dry on lately: the way he came in, known good footing)
        if (g.isInLava()) {
            if (current != null) { clearCrack(); g.tasks.addFirst(current); current = null; g.currentTask = null; }
            var lv = g.level(); BlockPos me = g.blockPosition(), out = null; double bd = Double.MAX_VALUE;
            for (BlockPos q : BlockPos.betweenClosed(me.offset(-6, -1, -6), me.offset(6, 1, 6))) { // ponytail: 6 out, not 3 (live 19:53 World 15: fell into a Nether lava lake, no dry cell within 3, he only jumped till he died)
                if (!lv.getFluidState(q).isEmpty() || !lv.getFluidState(q.above()).isEmpty()) continue;
                if (!lv.getBlockState(q).getCollisionShape(lv, q).isEmpty() || !lv.getBlockState(q.above()).getCollisionShape(lv, q.above()).isEmpty()) continue;
                if (!lv.getBlockState(q.below()).isFaceSturdy(lv, q.below(), Direction.UP)) continue;
                if (lavaOutBad.containsKey(q) && g.tickCount - lavaOutBad.get(q) < 20 * 4) continue; // (a step he couldn't make just now: another way out, never the same failing step till he burns: live 06:53 World 15)
                double d = q.distSqr(me) + (dryCells.contains(q) ? -6 : 0) + (q.getY() < me.getY() ? 8 : 0) + (lv.clip(new net.minecraft.world.level.ClipContext(g.getEyePosition(), Vec3.atCenterOf(q), net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, g)).getType() == net.minecraft.world.phys.HitResult.Type.MISS ? 0 : 1000); // (a wall between: last resort)
                if (d >= 1000 && q.distSqr(me) > 2) continue; // (behind a wall and not beside him: no way out at all, the wall dig below is; 1.5 s lost walking at one in a sealed pocket)
                if (d < bd) { bd = d; out = q.immutable(); }
            }
            if (out != null && !out.equals(lavaOut)) { lavaOut = out; lavaOutSince = g.tickCount; }
            if (out != null && g.tickCount - lavaOutSince > 30 + 15 * (int) Math.sqrt(out.distSqr(me))) { lavaOutBad.put(out, g.tickCount); com.claudemod.ClaudeMod.LOGGER.info("[Claude life] in lava at {}: 1.5 s and not onto {}: that way's no good, another; {} (WorkGoal.reflexes)", me.toShortString(), out.toShortString(), lavaWhy(out)); lavaOut = null; }
            if (g.tickCount % 20 < 2) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] in lava at {}: getting out{}", me.toShortString(), out == null ? " (no dry cell near: jumping)" : " to " + out.toShortString() + (dryCells.contains(out) ? " (where he came from)" : "") + "; " + lavaWhy(out));
            g.getNavigation().stop();
            boolean wallUp = false; // (mining his way out: feet on the floor, no jump; off the ground every block takes 5x as long, vanilla, and the pocket burnt him to death, stage 530)
            if (out == null) { // no dry cell round him (a sealed pocket the lava came into, World 15 22:08: 17 s and dead): out through the wall, the side with no lava behind it, like a player
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
                    else { // vanilla time: both blocks' mining ticks pass before they go (they went at once: a cheat no player has)
                        float p1 = lv.getBlockState(dig).getDestroyProgress(g, lv, dig), p2 = lv.getBlockState(dig.above()).getCollisionShape(lv, dig.above()).isEmpty() ? 0f : lv.getBlockState(dig.above()).getDestroyProgress(g, lv, dig.above());
                        double need = (p1 > 0 && !lv.getBlockState(dig).getCollisionShape(lv, dig).isEmpty() ? Math.ceil(1 / p1) : 0) + (p2 > 0 ? Math.ceil(1 / p2) : 0);
                        String k = "lavaDig:" + dig.asLong(); Integer t0 = g.attempts.counters.get(k); if (t0 == null || g.tickCount - t0 > need + 20) { t0 = g.tickCount; g.attempts.counters.put(k, t0); }
                        g.swing(net.minecraft.world.InteractionHand.MAIN_HAND); lv.destroyBlockProgress(g.getId(), dig, (int) Math.min(9, (g.tickCount - t0) * 10 / Math.max(1, need)));
                        if (g.tickCount - t0 >= need) { for (BlockPos c0 : new BlockPos[] {dig, dig.above()}) if (!lv.getBlockState(c0).getCollisionShape(lv, c0).isEmpty()) sp0.gameMode.destroyBlock(c0); g.attempts.counters.remove(k); }
                    }
                    if (dig != null) { if (g.tickCount % 20 == 0) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] in lava at {}, no dry cell round him: through the wall at {} (no lava behind it) (WorkGoal.reflexes)", me.toShortString(), dig.toShortString()); out = dig; } // (every tick: on the 4 ticks of 5 it skipped he jumped, off the ground, mining at a fifth)
                    if (dig != null) wallUp = !lv.getBlockState(dig).getCollisionShape(lv, dig).isEmpty() || !lv.getBlockState(dig.above()).getCollisionShape(lv, dig.above()).isEmpty();
                }
            }
            if (wallUp) { g.getLookControl().faceNow(Vec3.atCenterOf(out)); g.getMoveControl().setWantedPosition(g.getX(), g.getY(), g.getZ(), 0); return true; }
            if (out != null) { g.getLookControl().faceNow(Vec3.atCenterOf(out)); /* (at once, then the walk: a turn over 4 ticks lost 4 of the ~22 a cell takes in lava drag) */ g.getMoveControl().setWantedPosition(out.getX() + 0.5, out.getY(), out.getZ() + 0.5, 1.3); g.getLookControl().setLookAt(out.getX() + 0.5, out.getY() + 1.0, out.getZ() + 0.5); g.setSprinting(true); }
            g.getJumpControl().jump();
            return true;
        }
        return false;
    }

    private int lavaDigTick = -100;
    private String lavaWhy(BlockPos out) { // what holds him in the lava, for the log: World 17 20:41 his own dry cell was the way out and he wasn't out of it in 1.5 s, unproven why (stage 746 gets out in 6 ticks)
        var lv = g.level(); var bb = g.getBoundingBox().deflate(0.001); StringBuilder touch = new StringBuilder();
        for (BlockPos q : BlockPos.betweenClosed(BlockPos.containing(bb.minX, bb.minY, bb.minZ), BlockPos.containing(bb.maxX, bb.maxY, bb.maxZ))) { var fs = lv.getFluidState(q);
            if (fs.is(net.minecraft.tags.FluidTags.LAVA) && q.getY() + fs.getHeight(lv, q) >= bb.minY) touch.append(q.toShortString()).append(fs.isSource() ? " source" : " flow " + fs.getAmount() + (fs.getValue(net.minecraft.world.level.material.FlowingFluid.FALLING) ? " falling" : "")).append("; "); }
        var dm = g.getDeltaMovement();
        return String.format("at %.2f, %.2f, %.2f moving %.3f, %.3f, %.3f, on ground %s; lava his body touches: %s; the way out's fluid %s; nav done %s, %.2f off the way out's centre; task %s, next %s, step %s",
            g.getX(), g.getY(), g.getZ(), dm.x, dm.y, dm.z, g.onGround(), touch.length() == 0 ? "none" : touch, out == null ? "-" : lv.getFluidState(out).isEmpty() ? "none" : lv.getFluidState(out).toString(), g.getNavigation().isDone(), out == null ? -1.0 : Math.hypot(g.getX() - out.getX() - 0.5, g.getZ() - out.getZ() - 0.5), current, g.tasks.peek(), g.lastStep == null ? "-" : g.lastStep.skill());
    }
    @Override
    public void tick() {
        if (g.tickCount % 5 == 0) g.attempts.sweep(); // (failures whose cause no longer holds leave the records the planners read)
        if (g.isUsingItem() && g.getUseItem().is(net.minecraft.world.item.Items.BOW) && g.tickCount - bowAimTick > 2) g.stopUsingItem(); /* the user: "walking super slow with his bow pulled back": the fight ended (target dead, out of sight, timed out, another job) with the draw still held, and a draw walks at 1/5 speed */
        if (reflexes()) return; // fire, air, lava first (WorkGoal.reflexes; also run while he's only hanging out with them)
        if (g.isSleeping()) return; // in bed: the job waits for morning (ticking on in bed timed the vein and its patches out)
        if (gravelOverHim()) return; // gravel coming down into a column his body is in: out from under it first (04:01 Nov 4, suffocated on a fresh step)
        if (g.tickCount % 40 == 7 && (digInAt == null || digInTasks == null || !inDigInHole())) adoptPitCover(); // his hole from before a restart: its cover state died with it (or an old cover's record, somewhere else)
        // On fire out of the lava with a water bucket on him: water at his feet puts it out, like a player (live 14:07: out of the lava at 17 health, he burned down to 9 with a water bucket in his pack); his fillBucket scoops it back up
        if (g.isOnFire() && !g.isInLava() && !g.isInWater() && g.tickCount % 10 == 3 && !g.level().dimensionType().ultraWarm()) {
            var lvF = g.level(); BlockPos feetF = g.blockPosition();
            if (lvF.getBlockState(feetF).canBeReplaced() && lvF.getFluidState(feetF).isEmpty() && lvF.getBlockState(feetF.below()).isFaceSturdy(lvF, feetF.below(), Direction.UP) && g.inventory.count(st -> st.is(net.minecraft.world.item.Items.WATER_BUCKET)) > 0 && g.hold(st -> st.is(net.minecraft.world.item.Items.WATER_BUCKET))) {
                g.getLookControl().faceNow(Vec3.atCenterOf(feetF.below()).add(0, 0.5, 0)); g.useHeld(); // (the bucket's own ray, at the floor under him: a block click never pours a bucket)
                com.claudemod.ClaudeMod.LOGGER.info("[Claude life] on fire at {} (health {}): water at his feet to put it out (WorkGoal)", feetF.toShortString(), (int) g.getHealth());
            }
        }
        // Burning out of the lava with lava within 2 of him or his work: work on (the fire burns either way; each touch more is 4 HP and the fire again, lava.contact_hp) or back along his own dry way out of its reach till the fire's out, priced (D2D review 626, live 03:58 11-07: 15 s of fire spent plugging and breaking beside the pool, at 12 HP the plug put him in it again, 20 -> 6 in 45 s; stage 941)
        if (current != null && !(current instanceof Task.Wait) && g.isOnFire() && !g.isInLava() && g.onGround() && g.tickCount % 10 == 4 && g.tickCount >= hotHeldTill && !g.hasEffect(net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE)) {
            ServerLevel lvH = (ServerLevel) g.level(); BlockPos meH = g.blockPosition(), atH = com.claudemod.entity.life.Watchdog.target(current);
            if (lavaWithin(lvH, meH, 2) || atH != null && lavaWithin(lvH, atH, 2)) {
                java.util.function.Predicate<BlockPos> standH = q -> lvH.getBlockState(q).getCollisionShape(lvH, q).isEmpty() && lvH.getBlockState(q.above()).getCollisionShape(lvH, q.above()).isEmpty() && lvH.getFluidState(q).isEmpty() && lvH.getBlockState(q.below()).isFaceSturdy(lvH, q.below(), Direction.UP);
                BlockPos back = null; for (var it0 = dryCells.descendingIterator(); it0.hasNext(); ) { BlockPos q = it0.next(); if (!q.equals(meH) && q.distSqr(meH) <= 8 * 8 && standH.test(q) && !lavaWithin(lvH, q, 3)) { back = q; break; } } // (his own way back first: ground he's walked; the list outlives a change of world, so near and still standing only)
                if (back == null) { double bd = Double.MAX_VALUE; for (BlockPos q : BlockPos.betweenClosed(meH.offset(-6, -1, -6), meH.offset(6, 1, 6))) { double dq = q.distSqr(meH); if (dq < bd && standH.test(q) && !lavaWithin(lvH, q, 3) && lvH.clip(new net.minecraft.world.level.ClipContext(g.getEyePosition(), Vec3.atCenterOf(q), net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, g)).getType() == net.minecraft.world.phys.HitResult.Type.MISS) { bd = dq; back = q.immutable(); } } }
                if (back != null) {
                    double fireS = g.getRemainingFireTicks() / 20.0, fireHp = fireS * w("lava.fire_hp_per_s", 1), cHp = w("lava.contact_hp", 12), touches = w("lava.work_near_s", 20) * w("lava.contact_per_s", 0.03);
                    double workS = com.claudemod.entity.life.Decide.hpS(g, g.getHealth(), fireHp, touches * cHp, cHp, 3);
                    double holdS = Math.sqrt(back.distSqr(meH)) / com.claudemod.entity.life.Decide.sprint() + fireS + com.claudemod.entity.life.Decide.hpS(g, g.getHealth(), fireHp, 0, cHp, 0);
                    var pick = com.claudemod.entity.life.Decide.choose(g, "lava.hold", "on fire " + String.format("%.0f", fireS) + " s more at health " + (int) g.getHealth() + ", lava within 2 of " + (lavaWithin(lvH, meH, 2) ? "him" : "his work at " + atH.toShortString()), true, java.util.List.of(
                        com.claudemod.entity.life.Decide.Option.of("work on", workS, "the fire's " + Math.round(fireHp) + " HP, and ~" + String.format("%.1f", touches) + " touch(es) more at " + Math.round(cHp) + " HP each beside it"),
                        com.claudemod.entity.life.Decide.Option.of("back off", holdS, "back to " + back.toShortString() + " out of its reach till the fire's out, then on")));
                    if (pick != null && pick.name().equals("back off")) {
                        if (current != null) { clearCrack(); g.tasks.addFirst(current); current = null; g.currentTask = null; }
                        g.tasks.addFirst(new Task.Wait(g.getRemainingFireTicks() + 10, false)); g.tasks.addFirst(new Task.GoTo(back, 0.6));
                        hotHeldTill = g.tickCount + g.getRemainingFireTicks() + 40;
                        com.claudemod.ClaudeMod.LOGGER.info("[Claude life] on fire at {} (health {}) with lava by his work: back to {} till the fire's out, then on (WorkGoal)", meH.toShortString(), (int) g.getHealth(), back.toShortString());
                        return;
                    }
                }
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
            if (g.onGround()) creepers.removeIf(c -> g.getY() - c.getY() >= w("creeper.fuse_blocks", 3)); // (it lights within 3 of him and can't climb: 3+ below his feet it never gets there. World 17 06:15, up his 5-high tower over three creepers, he ran off the top into them for 3+ min; stage 767)
            creepers.removeIf(c -> g.tasks.stream().anyMatch(t -> t instanceof Task.Attack a && a.target().equals(c.getUUID()))); // a fight with it already queued (clearing the block in the way first): leave that be, don't re-arm it every 5 ticks
            creepers.sort(java.util.Comparator.comparingDouble(c -> c.getSwellDir() > 0 ? -1 - c.getSwelling(1f) : c.distanceTo(g))); // the most dangerous first: a lit one (shortest fuse) before any other, then the nearest; only get(0) is judged below (the list's order was the world's: the one he fought came first, the lit one behind him was never looked at, 29.4 through iron, World 17 15:30; stage 1134)
            // armed, healthy and it isn't hissing yet: hit it (knocks it back) instead of being chased around by it
            boolean busyOther = !creepers.isEmpty() && current instanceof Task.Attack ca0 && !ca0.target().equals(creepers.get(0).getUUID())
                    && !(g.level() instanceof ServerLevel sl0 && (sl0.getEntity(ca0.target()) instanceof net.minecraft.world.entity.animal.Animal || sl0.getEntity(ca0.target()) instanceof net.minecraft.world.entity.animal.AbstractFish)); // in a fight with something else (a fish on the end of his sword is no fight: a creeper walked up while he hunted salmon and blew him up, 27 through full iron)
            if (!creepers.isEmpty() && (creepers.get(0).getSwellDir() <= 0 || hitsBeforeBang(creepers.get(0))) && !busyOther && !shouldFlee(creepers.get(0), true)) { // (hit it or not: fight.flee's price on its blast, not "armed and over 10 HP", audit) // a real sword only: five wooden-sword swings on a creeper is a funeral (death 18)
                g.urgentAttacker = creepers.get(0).getUUID();
                com.claudemod.director.ClaudeLife.nookSay(g, "creeperHit", "[Claude life] a creeper close and not hissing yet: hitting it back, armed and healthy (WorkGoal.tick)");
                creepers = java.util.List.of();
            } else if (busyOther) com.claudemod.director.ClaudeLife.nookSay(g, "creeperHit", "[Claude life] a creeper close while he fights something else: away from the creeper first, the fight waits (WorkGoal.tick)"); // (live 12:25 11-02: fighting a skeleton, the creeper was neither fought nor run from till it blew beside him in full iron)
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
        if (g.tickCount % 20 == 3 && !(current instanceof Task.Flee) && !(current instanceof Task.Attack) && g.getHealth() < g.getMaxHealth() && !diggingIn() && g.tickCount - desperateTick > 20 * 90
                && !g.isSleeping() && com.claudemod.director.ClaudeLife.foodToEat(g, true) == null && (!(g.getBlockY() >= g.level().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, g.getBlockX(), g.getBlockZ()) - 4 && !com.claudemod.director.ClaudeLife.dayHere(g.level()) ? enemiesNear(24) : hostilesNear(16)).isEmpty() || enemiesNear(16).size() >= 3)
                && coverPays("nothing to eat", enemiesNear(24).size())) { // (priced, cover.dig_in: was "health 4 or less")
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
                && !com.claudemod.entity.life.Perception.known(g, net.minecraft.world.entity.Mob.class, g.getBoundingBox().inflate(12), m -> m.isAlive() && m instanceof net.minecraft.world.entity.monster.Enemy && (m.getTarget() == g || m.distanceTo(g) < 6 || g.level().canSeeSky(g.blockPosition()))).isEmpty()
                && coverPays("weak kit", com.claudemod.entity.life.Perception.known(g, net.minecraft.world.entity.Mob.class, g.getBoundingBox().inflate(12), m -> m.isAlive() && m instanceof net.minecraft.world.entity.monster.Enemy).size())) { // (priced, cover.dig_in: was "night, or health under 10") // (underground: only one that's after him or close; anything in the cave within 12 kept him on a tower at 2 health for good)
            if (current != null) { clearCrack(); g.tasks.addFirst(current); }
            desperateNow = true;
            boolean dug = digInFromArrows();
            desperateNow = false;
            if (dug) { current = null; g.currentTask = null; com.claudemod.ClaudeMod.LOGGER.info("[Claude life] weak kit, hostiles within 12, cover priced over carrying on (cover.dig_in; health {}): under cover first", (int) g.getHealth()); } // (it said "hurt" by day at 20 HP: a label from the old night-or-under-10 rule, review 306)
            else if (current != null) { g.tasks.pollFirst(); }
        }
        // Phantoms out at night (someone's insomnia): working in the open just bleeds hearts; under cover till morning, like a player with no bed
        if (g.tickCount % 40 == 17 && !(current instanceof Task.Flee) && !(current instanceof Task.Attack) && !diggingIn()
                && !com.claudemod.director.ClaudeLife.dayHere(g.level()) && g.level().canSeeSky(g.blockPosition()) && !g.isSleeping()
                && !g.level().getEntitiesOfClass(net.minecraft.world.entity.monster.Phantom.class, g.getBoundingBox().inflate(32), m -> m.isAlive()).isEmpty()
                && coverPays("phantoms", g.level().getEntitiesOfClass(net.minecraft.world.entity.monster.Phantom.class, g.getBoundingBox().inflate(32), m -> m.isAlive()).size() * (weakKit() ? 2 : 1))) { // (priced, cover.dig_in: was "health under 16 or a weak kit"; a weak kit can't swat them, counted twice)
            if (current != null) { clearCrack(); g.tasks.addFirst(current); }
            desperateNow = true;
            boolean dug = digInFromArrows();
            desperateNow = false;
            if (dug) { current = null; g.currentTask = null; com.claudemod.ClaudeMod.LOGGER.info("[Claude life] phantoms out at night (health {}): under cover till morning", (int) g.getHealth()); }
            else if (current != null) { g.tasks.pollFirst(); }
        }
        if (g.urgentAttacker != null && com.claudemod.entity.life.DragonFight.dragonsHit(g, g.urgentAttacker)) g.urgentAttacker = null; // (the dragon's hits are the End fight's to answer: arrows in flight, the sword on the perch; "fighting back" chased it round the island)
        // Digging in already (in his hole, or at it): a hit doesn't stop him, like a player he keeps digging
        boolean byCover = digInAt != null && Math.abs(g.getBlockX() - digInAt.getX()) <= 3 && Math.abs(g.getBlockZ() - digInAt.getZ()) <= 3 && Math.abs(g.getBlockY() - digInAt.getY()) <= Math.max(4, digInRise + 2); // (an arrow's knockback puts him a block or two off his box: still making it)
        if (g.urgentAttacker != null && diggingIn() && byCover) { // shot up his own bare tower: a perch, not cover; the cover choice again (a lid and walls, or down to a hole), never holding there (live 18:13 11-04: 30 s of arrows on a roofless dirt tower, "digging on, not a fight", 20 -> 0)
            var shot0 = ((ServerLevel) g.level()).getEntity(g.urgentAttacker); BlockPos ft = g.blockPosition();
            if (shoots(shot0) && digInAt != null && ft.getY() >= digInAt.getY() + 2 && Math.abs(ft.getX() - digInAt.getX()) <= 1 && Math.abs(ft.getZ() - digInAt.getZ()) <= 1 && g.level().getBlockState(ft.above(2)).getCollisionShape(g.level(), ft.above(2)).isEmpty()) {
                if (digInTasks != null) g.tasks.removeAll(digInTasks);
                if (current != null) { clearCrack(); current = null; g.currentTask = null; }
                com.claudemod.ClaudeMod.LOGGER.info("[Claude life] shot on his bare tower at {} by a {} {} off: no holding up here, the cover choice again (WorkGoal.tick)", ft.toShortString(), shot0.getType().getDescription().getString(), (int) shot0.distanceTo(g));
                g.urgentAttacker = null;
                digInFromArrows();
            }
        }
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
            boolean hitterNearer = hitter instanceof net.minecraft.world.entity.LivingEntity hn && hn.isAlive() && aimed != null && hn.distanceTo(g) < aimed.distanceTo(g) - 1.0 && g.hasLineOfSight(hn); // (live 07:40 11-02: a skeleton 5.4 off shot him 12 times while he stayed on one 9-12 off, full iron 20 -> 0)
            boolean creeperFirst = aimed instanceof net.minecraft.world.entity.monster.Creeper ac && ac.isAlive() && !(hitter instanceof net.minecraft.world.entity.monster.Creeper) && ac.distanceTo(g) < w("fight.creeper_focus_r", 5); // (a creeper near enough to go off on him stays the one he deals with: bench 1115, a zombie's hit turned him off one 3 off, 27.4 from its blast)
            if (creeperFirst && (hitterOn || hitterNearer) && !aimedInReach) sdSay("creeperfirst", "[Claude life] hit by a {} while a creeper is {} off: the creeper first, the {} after (WorkGoal.tick)", hitter == null ? "mob" : hitter.getType().getDescription().getString(), String.format("%.1f", aimed.distanceTo(g)), hitter == null ? "hitter" : hitter.getType().getDescription().getString());
            if (!prey && !creeperFirst && (hitterOn || hitterNearer) && !aimedInReach) {
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
            if (!(foe instanceof net.minecraft.world.entity.LivingEntity l) || !l.isAlive() || foe.distanceTo(g) > 24 && !(foe instanceof net.minecraft.world.entity.Mob fm && huntsHim(fm)) || (shouldFlee(foe) && hostilesNear(14).isEmpty() && foe.distanceTo(g) > 8)) { // (a mob 2 blocks off that just hit him is on him, whatever its target says: "healing up" there is how a skeleton killed him) // (past 24 but within its follow range and after him, it's on him: World 17 04:52:31, a blaze 32 off "isn't on him", he walked on in its fire 17 -> 0, stage 763)
                if (foe != null) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] hit by a {} that isn't on him ({} blocks off, health {}): healing up, not running", foe.getType().getDescription().getString(), (int) foe.distanceTo(g), (int) g.getHealth());
                else com.claudemod.ClaudeMod.LOGGER.info("[Claude life] hit by a mob that's gone from the world now (health {}): nothing to fight, healing up (WorkGoal.tick)", (int) g.getHealth());
                g.urgentAttacker = null;
                healUp();
                return;
            }
            if (g.tickCount - wsDugTick < 100 && !(current instanceof Task.Attack) && wsDug != null && (current instanceof Task.Place || current instanceof Task.Break || current instanceof Task.GoTo go0 && go0.pos().equals(wsDug))) { // making cover (WorkGoal.tallCover): a hit doesn't turn him back to the open fight (stage 555: the re-arm cleared the build mid-way, the next hit knocked him off it)
                sdSay("coverbuild", "[Claude fight] hit by a {} while making cover (health {}): still at it, the fight from it after (WorkGoal.tick)", foe.getType().getDescription().getString(), (int) g.getHealth()); g.urgentAttacker = null; return;
            }
            if (current instanceof Task.Eat && g.tickCount - cfEatTick < 60) { // the fight chose this meal where he stands (the way to cover went nowhere): a hit doesn't take it off him, the fight comes after it (death-220: [Eat] pushed back behind each new fight; stage 1321)
                sdSay("fightmeal", "[Claude fight] hit by a {} mid-meal (health {}): eating on, the fight after it (WorkGoal.tick)", foe == null ? "?" : foe.getType().getDescription().getString(), (int) g.getHealth()); g.urgentAttacker = null; return;
            }
            if (foe instanceof net.minecraft.world.entity.monster.Blaze bh && !(current instanceof Task.Attack) && bh.getBoundingBox().minY > g.getY() + 3.2 && !(g.inventory.count(x -> x.is(net.minecraft.world.item.Items.BOW)) > 0 && g.inventory.count(x -> x.is(net.minecraft.world.item.Items.ARROW)) > 0)) { // a blaze up out of sword reach and no bow: a fight armed here is standing under it taking its volleys (live 23:18:40 11-09: 13 up and 25 off, 29 fireballs, no hit in, off at 8); the walk he's on goes on (up to its level, or away), standing still he gets out of its sight first (FortressFight's out-of-reach rule takes the job from there; stage 1325)
                boolean still = current == null || current instanceof Task.Wait;
                if (still) { current = new Task.Flee(60); g.currentTask = current; ticks = 0; stuck = 0; lastDist = Double.MAX_VALUE; }
                sdSay("blazehigh", "[Claude fight] hit by a Blaze {} up and {} off, out of sword reach and no bow (health {}): {} (WorkGoal.tick)", (int) (bh.getY() - g.getY()), (int) bh.distanceTo(g), (int) g.getHealth(), still ? "out of its sight, not a stand under it" : "on with the walk, no stand under it"); g.urgentAttacker = null; return;
            }
            if (foe instanceof net.minecraft.world.entity.monster.Blaze && g.tickCount - blazeRetreatTick < 200 && !(current instanceof Task.Attack)) { // walking out of their sight: a fireball doesn't turn him back to fight (each hit re-armed the fight, the fight re-queued the walk; he stood on a fence burning 13 -> 0, World 15 12:40)
                sdSay("blazewalk", "[Claude fight] hit by a Blaze while getting out of their sight (health {}): still going, the fight comes after (WorkGoal.tick)", (int) g.getHealth()); g.urgentAttacker = null; return;
            }
            if (g.tickCount - lastFightTick().getOrDefault(g.urgentAttacker, -100000) < 20 && g.tickCount >= lastFightTick().getOrDefault(g.urgentAttacker, -100000)) { sdSay("rearm", "[Claude life] hit by the {} again {} ticks after squaring up to it, doing {}: no new fight armed this tick (WorkGoal.tick)", foe.getType().getDescription().getString(), g.tickCount - lastFightTick().get(g.urgentAttacker), current == null ? "nothing" : current.getClass().getSimpleName()); g.urgentAttacker = null; return; } // (the same fight re-armed every 5 ticks: once a second is plenty) // ponytail: the map is static and his tickCount restarts on respawn: a stamp from his last body read "-51758 ticks ago", so no fight was ever armed (he died walking to a nook, enderman 20 -> 0, 17:18)
            lastFightTick().put(g.urgentAttacker, g.tickCount);
            boolean retreat = shouldFlee(foe) && !diggingIn() && backAmongS(hostilesNear(16).size()) > w("flee.job_drop_s", 60); // (priced: going back to the job among them, against dropping it; was "health under 10") // (never the escape itself: it threw away the tower he'd just queued and three husks killed him) // running at a few hearts: the job he was on is where the mobs are; it isn't resumed after the run (the queue walked him back to two zombies at 5 health)
            if (retreat && (current != null || !g.tasks.isEmpty())) { com.claudemod.ClaudeMod.LOGGER.info("[Claude life] retreating at health {}: the job there ({} tasks) is dropped, not walked back into (back among {} ~{} s against flee.job_drop_s {})", (int) g.getHealth(), g.tasks.size(), hostilesNear(16).size(), Math.round(backAmongS(hostilesNear(16).size())), Math.round(w("flee.job_drop_s", 60))); com.claudemod.director.ClaudeLife.clearTasks(g); }
            else if (current != null && g.tasks.peek() != current) { clearCrack(); g.tasks.addFirst(current); } // (once: not a copy of it every time this fires)
            com.claudemod.director.ClaudeLife.craftSwordIfCan(g, (ServerLevel) g.level()); // something's on him: a sword if he can make one
            boolean run = shouldFlee(foe, true);
            if (run) { var gf = gapFoe(); if (gf != null) { run = false; g.urgentAttacker = gf.getUUID(); } } // (a crowd with a wither skeleton in it: the fight from a gap it can't reach into, never a run past them)
            // (on the run back to his pile out of his armor: the pile rides on the fight, priced in fleePriced: recovery.fight_s)
            current = run ? new Task.Flee(600) : new Task.Attack(g.urgentAttacker, 300); if (run) fleeSeed = g.urgentAttacker; // (the one that set him running is what he runs from, seen or not) // (the run ends when he's clear of them, above; 30 s is only the backstop: 8 s cut it off 15 blocks from a witch)
            g.currentTask = current; // what he's doing right now (the chat and the logs read this)
            g.dropCurrent = false; // a drop meant for the job he was on (re-queued above) must not kill this fight the same tick (that re-armed the same fight every 5 ticks, 750 tasks deep)
            if (run) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] backing off (health {}, {} mobs close, armed {}; {})", (int) g.getHealth(), hostilesNear(10).size(), armed(), fightOdds);
            else com.claudemod.ClaudeMod.LOGGER.info("[Claude life] fighting back: {} (health {}, {} mobs close, armed {})", foe == null ? "?" : foe.getType().getDescription().getString(), (int) g.getHealth(), hostilesNear(10).size(), armed());
            g.urgentAttacker = null;
            ticks = 0; stuck = 0; lastDist = Double.MAX_VALUE;
        }
        if (!(current instanceof Task.Attack) && !(current instanceof Task.Flee)) shieldDown(); // the shield is for fights: down again for mining, walking, eating (a run keeps its own: a lit creeper on it, 06:31)
        if (g.dropCurrent) { // something was put in front of what he's doing (it was re-queued if it still matters)
            g.dropCurrent = false;
            clearCrack();
            current = null;
        }
        g.busy = current != null;
        if (current == null) {
            if (g.tasks.isEmpty() && g.skillQueue.isEmpty() && com.claudemod.entity.life.DragonFight.resume(g)) g.tasks.add(new Task.Dragon(20 * 60 * 30)); // (the End fight on and the dragon alive: a cleared queue never ends it)
            nearestFirst();
            current = g.tasks.poll();
            int dup = 0; while (current != null && !(current instanceof Task.Pillar) && current.equals(g.tasks.peek())) { Task nx = g.tasks.poll(); if (com.claudemod.entity.nav.Route.owns(g, nx) && !com.claudemod.entity.nav.Route.owns(g, current)) current = nx; dup++; } // (a route's own copy is the one kept: the other copy dropped took the route's end, and its old legs walked him back down, 12:31) // (two Pillar(-1)s are two towers to take down, not a copy: dropping one left a cobble block in his stair walkway) // the same task queued over and over (a route re-fired every few seconds): one copy is plenty
            if (dup > 0) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] {} duplicate copies of {} dropped from his queue", dup, current);
            while (current != null && (unseenOre(current) || !com.claudemod.entity.life.Watchdog.starting(g, current))) current = g.tasks.poll(); /* the no-progress watchdog: an action that kept failing goes up its ladder, not round again */
            g.busy = current != null;
            ticks = 0; stuck = 0; lastDist = Double.MAX_VALUE; breakProgress = 0; bridged = 0;
            if (leanFor != null && !(current instanceof Task.Place pl0 && pl0.pos().equals(leanFor))) { g.setShiftKeyDown(false); leanFor = null; } // (a lean left for another task: off the sneak)
            g.currentTask = current;
            if (current != null) com.claudemod.entity.life.Gate.started(g, current, exitWhy); // (the no-progress gate measures it from here)
            if (current == null) return;
        }
        com.claudemod.entity.nav.Route.step(g); // (his trail: the legs he walks through)
        ticks++;
        maybeStandUp();
        boolean done;
        if (!(current instanceof Task.Dragon) && !(current instanceof Task.Pillar) && !(current instanceof Task.Leap) && !(current instanceof Task.Break bk && bk.pos().getX() == g.getBlockX() && bk.pos().getZ() == g.getBlockZ() && bk.pos().getY() < g.getBlockY()) /* (the escape's own moves: its dig down was cut off every tick, he stood in the cloud, 20 -> 0 in 4 s, live 03:54) */ && com.claudemod.entity.life.DragonFight.breathOn(g)) { g.tasks.addFirst(current); com.claudemod.entity.life.DragonFight.again(g, true); com.claudemod.ClaudeMod.LOGGER.info("[Claude dragon] the dragon's breath on him during {}: out of it first, that after (WorkGoal)", current.getClass().getSimpleName()); current = null; return; } // (in the End: its breath outranks a pillar, a walk, a dig)
        try {
            done = switch (current) {
                case Task.GoTo t -> { if (ticks == 1) exitWhy = null; if (ticks == 1 && t.within() == 0.8 && !g.isInWater() && g.onGround() && t.pos().distSqr(g.blockPosition()) > 2 * 2 && (!g.level().getFluidState(t.pos()).isEmpty() || !g.level().getFluidState(t.pos().below()).isEmpty())) { com.claudemod.ClaudeMod.LOGGER.info("[Claude walk] out of the water at {}: the swim waypoint {} dropped (WorkGoal GoTo)", g.blockPosition().toShortString(), t.pos().toShortString()); yield true; } /* (a swim to the shore, queued while stuck swimming, outlived his getting out: live 01:16 11-05 he dug his ceiling toward a "shore" in the flooded pocket over his mine) */ if (g.staying && t.pos().distSqr(g.blockPosition()) > 2 * 2) { if (g.tickCount % 100 < 2 || ticks == 1) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] staying put where they said: the walk to {} ({}) waits (WorkGoal)", t.pos().toShortString(), com.claudemod.entity.ClaudeEntity.originOf(t)); yield true; } // (told to stay put, every chore of his own walks him off: a stash run, a torch run, his nook; one gate at the walk, their own go_to clears staying first, 15:03)
                    if ((g.onClimbable() || g.level().getBlockState(t.pos()).is(net.minecraft.tags.BlockTags.CLIMBABLE)) && t.pos().getX() == g.getBlockX() && t.pos().getZ() == g.getBlockZ() && t.pos().getY() > g.getBlockY()) { g.getNavigation().stop(); g.getJumpControl().jump(); if (ticks > 40) com.claudemod.ClaudeMod.LOGGER.info("[Claude walk] climb to {} gave up at y {} after 2 s (WorkGoal GoTo climb)", t.pos().toShortString(), g.getBlockY()); yield ticks > 40; } // ponytail: hold jump till he is in the cell (live 16:21 a one-tick jump per leg ended every leg at once, a vine-column loop); 2 s a cell cap // ponytail: live 14:50 -59,5x,-909: up a free-standing twisting vine a player holds jump; the walk never did, "couldn't get to" each cell of the planned climb
                    if (ticks == 1 && t.pos().distSqr(g.blockPosition()) > 4 * 4 && coverBeforeWalk(t)) yield true;
                    if (ticks == 1 && inReach(t.pos())) { // (only where he can dig it from: from 128 off it put a seen ore's Breaks ahead of the walk there, "no way to it" in a tick, dropped, D2D review 248/303/29; stage 1020) a walk into a cell still solid, its dig queued behind the walk: the dig first, then the walk (live 04:43 World 15: a GoTo into the rock under him, its Break next in the queue, 32 stalls in a minute)
                        BlockPos tp = t.pos(); var lv0 = g.level(); java.util.List<Task> first = new java.util.ArrayList<>();
                        for (Task q0 : g.tasks) {
                            if (q0 instanceof Task.Wait) break;
                            if (q0 instanceof Task.Break b0 && (b0.pos().equals(tp) || b0.pos().equals(tp.above())) && !lv0.getBlockState(b0.pos()).getCollisionShape(lv0, b0.pos()).isEmpty() && !first.contains(q0)) first.add(q0);
                        }
                        if (!first.isEmpty()) {
                            g.tasks.removeAll(first); g.tasks.addFirst(t); for (int i0 = first.size() - 1; i0 >= 0; i0--) g.tasks.addFirst(first.get(i0));
                            com.claudemod.ClaudeMod.LOGGER.info("[Claude walk] the walk to {} waits for its own cell's dig ({} block(s) still there, queued behind it): digging first (WorkGoal.GoTo)", tp.toShortString(), first.size());
                            yield true;
                        }
                    }
                    if (ticks == 1 && t.pos().distSqr(g.blockPosition()) > 4 * 4) { // the user's rule: every break and patch in reach is done in the moment (ore, then holes, then the path), then on
                        java.util.List<Task> here = new java.util.ArrayList<>(); var eye0 = g.getEyePosition();
                        if (g.tickCount - pulledAt > 20 * 60) { pulledAt = g.tickCount; pulled.clear(); } // (kept 60 s whatever the walk: keyed by target it cleared each time the walk flipped between two cells by his arch, and pulled the same 8 jobs forward again and again, 04:03) // each job pulled once per walk: one that can't be done goes back in the queue, never pulled again ahead of the walk (03:29: a patch that failed was pulled forward 10 times a second)
                        for (Task q0 : g.tasks) { if (q0 instanceof Task.Wait) break; BlockPos qp = q0 instanceof Task.Break b0 ? b0.pos() : q0 instanceof Task.Place p0 ? p0.pos() : null; if (qp != null && !pulled.contains(q0) && inReach(qp) && qp.distSqr(t.pos()) <= g.blockPosition().distSqr(t.pos()) /* (none farther from the walk's end than he is: each branch block dug brought the next one west in reach, and the walk north to unload became 8 blocks of tunnelling, live 13:59) */ && blockInTheWay((ServerLevel) g.level(), qp) == null && !(q0 instanceof Task.Place pw && pw.want() != null) && !com.claudemod.entity.life.WatchBuild.ownsCell(g, qp)) here.add(q0); } /* (not a build's blocks or its clearing: they go in its order, each from the spot planned for the build as it stands by then) */ /* (nothing between: a far row cell pulled ahead of the nearer ones hit "in the way" every tick, 04:29) */ // (the dig's and the place's own reach test: a looser eye distance pulled a patch he couldn't reach from there, and it was dropped unplaced, test stage 18)
                        pulled.addAll(here);
                        if (!here.isEmpty()) {
                            here.sort(java.util.Comparator.comparingInt(q0 -> q0 instanceof Task.Break b0 && com.claudemod.entity.life.MineBlueprint.ore(g.level().getBlockState(b0.pos())) && !com.claudemod.entity.life.Watchdog.hiddenOre(g.level(), b0.pos()) ? 0 : 1)); // ore first; the rest in queue order (a swap's Break and its Place stay a pair: sorting places ahead split them, 04:03)
                            g.tasks.removeAll(here); g.tasks.removeIf(q0 -> q0.equals(t)); g.tasks.addFirst(t); for (int i0 = here.size() - 1; i0 >= 0; i0--) g.tasks.addFirst(here.get(i0));
                            com.claudemod.ClaudeMod.LOGGER.info("[Claude dig] {} job(s) in reach at {} done first, then the walk to {}", here.size(), g.blockPosition().toShortString(), t.pos().toShortString());
                            exitWhy = "paused for " + here.size() + " job(s) in reach, the walk queued after them"; yield true; } }
                    yield (ticks == 1 && routeOut(t)) || walkTo(t.pos(), t.within()); }
                case Task.Break t -> { exitWhy = null; boolean fin = doBreak(t.pos()); if (fin) { var bs0 = g.level().getBlockState(t.pos()); if (!bs0.isAir() && bs0.getFluidState().isEmpty() && !bs0.canBeReplaced() && !(exitWhy != null && exitWhy.startsWith("in the way") && g.tasks.stream().anyMatch(q0 -> q0 instanceof Task.Break b0 && b0.pos().equals(t.pos())))) { boolean again = g.tasks.stream().anyMatch(q0 -> q0 instanceof Task.Break b0 && b0.pos().equals(t.pos())); /* (a block in the way taken first, the target back after it: progress, said by doBreak's "in the way" line, not a refusal: reviews 292/428/441 read each leaf and dirt as one loop) */ StackTraceElement[] st0 = Thread.currentThread().getStackTrace(); com.claudemod.ClaudeMod.LOGGER.info("[Claude dig] {} at {} left standing after {} ticks{} (he's at {})", net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(bs0.getBlock()).getPath(), t.pos().toShortString(), ticks, again ? ", back in the queue" : ", dropped", g.blockPosition().toShortString() + (exitWhy == null ? "" : "; why: " + exitWhy) + "; queued by " + com.claudemod.entity.ClaudeEntity.originOf(t)); com.claudemod.entity.life.MindLog.refused(g, t.pos(), "breaking " + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(bs0.getBlock()).getPath() + (exitWhy == null ? "" : " [" + exitWhy + "]") + " queued by " + com.claudemod.entity.ClaudeEntity.originOf(t)); if (!again) com.claudemod.entity.life.Parked.refused(g, t, exitWhy); } } yield fin; } // every dig that ends without the block broken says so (the user: path cells at -17, -17/-18, -150 skipped with no line in the log)
                case Task.Place t -> { exitWhy = null; placedAs = null; boolean fin = doPlace(t); if (fin && exitWhy != null && g.tasks.contains(t) && !exitWhy.startsWith("first:")) com.claudemod.ClaudeMod.LOGGER.info("[Claude dig] placing {} at {}: {} first, the placement after it (WorkGoal)", net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(t.item()).getPath(), t.pos().toShortString(), exitWhy); /* (a nudge or a walk to the spot that sees a face: the placement back on his queue, progress; live 11:30 11-08 his Nether bridge read as "gave up" and PLACE_REPEAT with each block down a second later, reviews 502/514/141/171/211) */ if (fin && exitWhy != null && !g.tasks.contains(t) /* (a step first, the placement queued after it: progress, said by that step's own line; review 216 read look-in, diamond, patch in 3 s as a PLACE_REPEAT) */ && !g.level().getBlockState(t.pos()).is(net.minecraft.world.level.block.Block.byItem(t.item())) && !(placedAs != null && g.level().getBlockState(t.pos()).is(net.minecraft.world.level.block.Block.byItem(placedAs))) /* (out of the planned block, a stand-in set down is done, not given up: review 492, cobblestone for cobbled deepslate logged "gave up" per block) */) { com.claudemod.ClaudeMod.LOGGER.info("[Claude dig] placing {} at {} gave up (he's at {}); why: {}", net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(t.item()).getPath(), t.pos().toShortString(), g.blockPosition().toShortString(), exitWhy + "; queued by " + com.claudemod.entity.ClaudeEntity.originOf(t)); com.claudemod.entity.life.MindLog.refused(g, t.pos(), "placing [" + exitWhy + "] queued by " + com.claudemod.entity.ClaudeEntity.originOf(t)); com.claudemod.entity.life.Watchdog.placeGaveUp(g, t.item(), t.pos(), exitWhy);  com.claudemod.entity.life.Parked.refused(g, t, exitWhy); } yield fin; }
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
                case Task.Bore t -> doBore(t);
                case Task.Replant t -> doReplant(t);
                case Task.MineVisibleOres t -> doMineVisibleOres(t);
                case Task.WallTorch t -> doWallTorch(t);
                case Task.CheckForCave t -> doCheckForCave();
                case Task.Fish t -> doFish(t);
                case Task.Leap t -> doLeap(t);
                case Task.Dragon t -> com.claudemod.entity.life.DragonFight.tick(g, this, ticks) || ticks >= t.maxTicks();
            };
        } catch (RuntimeException e) {
            done = true; // a step that throws (block changed under him, etc.) is just skipped
            com.claudemod.ClaudeMod.LOGGER.warn("[Claude life] {} threw: skipped (WorkGoal.tick)", current, e); g.attempts.bandaid(current + " threw " + e); // (it was silent: a bug in the step, never seen)
        }
        // Watchdog: no single action should take 45s (cooking and long fights have their own limits). Skip it, never hang.
        boolean longByNature = current instanceof Task.Smelt || current instanceof Task.FurnaceTake || current instanceof Task.Attack
                || current instanceof Task.Wait || current instanceof Task.Fish || current instanceof Task.Bore; // (the bore ends itself after 3 s without a step)
        if (!done && !longByNature && ticks > 20 * 45) {
            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] stuck on {} for 45s, skipping it", current);
            g.getNavigation().stop();
            BlockPos spot = switch (current) { case Task.Break b -> b.pos(); case Task.GoTo go -> go.pos(); case Task.Place pl -> pl.pos(); default -> null; }; // what he couldn't get at stays off the list a while (the ore-spotting would queue it straight back)
            if (spot != null) {
                var cause = com.claudemod.entity.life.Stall.diagnose(g, spot, current instanceof Task.GoTo go0 ? go0.within() : 1.5);
                g.attempts.fail(current instanceof Task.Break ? com.claudemod.entity.life.Attempts.Kind.BREAK : current instanceof Task.Place ? com.claudemod.entity.life.Attempts.Kind.PLACE : com.claudemod.entity.life.Attempts.Kind.WALK, spot, g.tickCount, g.blockPosition(), cause.reason(), cause.detail(), cause.mob());
                g.attempts.bandaid("the 45 s task clock fired on " + current + ": " + cause.say()); // (no task should need 45 s: the cause it found is the bug)
                final BlockPos sp = spot;
                if (current instanceof Task.Break) g.tasks.removeIf(t -> t instanceof Task.Break b && b.pos().distSqr(sp) <= 3 * 3); // the rest of that vein is just as out of reach: not 45s each
            }
            done = true;
        }
        if (done && current instanceof Task.GoTo gt0 && (Math.sqrt(g.blockPosition().distSqr(gt0.pos())) > gt0.within() + 4 || ticks <= 3 && Math.sqrt(g.blockPosition().distSqr(gt0.pos())) > gt0.within() + 0.5)) // (and any walk refused at once short of its spot: a 3-off refusal on the Nether ledge looped every 15 s with no word why, 08:16-08:32 11-05, REVIEW-130/134) // (any walk that ends 4+ off its spot, however long it ran: a silent end hid where 3 bed legs went, 12:53) // a walk that ended before it began, far from the spot: say so (go_to "49 64 164" ended in 2 ticks, silently, three plans running)
            com.claudemod.ClaudeMod.LOGGER.info("[Claude walk] GoTo {} ended {} from {} (walk-fail {}, queue now {} first {}, why {})", gt0.pos().toShortString(), ticks <= 3 ? "at once" : "after " + ticks + " ticks, " + (int) Math.sqrt(g.blockPosition().distSqr(gt0.pos())) + " off it,", g.blockPosition().toShortString(), g.attempts.of(com.claudemod.entity.life.Attempts.Kind.WALK).get(gt0.pos()), g.tasks.size(), g.tasks.peek(), exitWhy);
        if (done) {
            try { com.claudemod.entity.life.Gate.ended(g, current, exitWhy); } catch (RuntimeException e) { com.claudemod.ClaudeMod.LOGGER.warn("[Claude gate] ended() threw on {}", current, e); }
            try { com.claudemod.entity.life.Watchdog.ended(g, current, exitWhy); /* (a walk resets exitWhy at its first tick, so its own why: a split into route legs read "the walker's own refusal", Day to Day 18:25) */ } catch (RuntimeException e) { com.claudemod.ClaudeMod.LOGGER.warn("[Claude watchdog] ended() threw on {}", current, e); }
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
    /** The path really ends at {@code aim}: vanilla's ground pathing turns an air target into the ground under it and calls that reached (538 11-05: a route leg 13 up an open shaft, "reached" where he stood, 188 ticks still and the planner never asked, three times over). */
    private static boolean reaches(net.minecraft.world.level.pathfinder.Path p, BlockPos aim, double within) {
        if (p == null || !p.canReach()) return false; var end = p.getEndNode();
        return end == null || Math.abs(end.y - aim.getY()) <= Math.max(within, com.claudemod.entity.life.Weights.w("walk.reach_dy", 1));
    }

    /** The cell beside him (same level or one up) that is open and floored, to step out of the one he's in; null if there's none. */
    /** Lava behind a block he's to dig: a solid block bordering that lava (not the block itself) whose face toward it is in his reach: once the block's out he clicks it through the hole and puts a block into the lava itself (a player's dig-and-plug: the way stays open). Null: none, or nothing on him to put. (Sight is only known once the hole is open: plugThrough checks it.) */
    private BlockPos plugFace(ServerLevel level, BlockPos hole, BlockPos lava) {
        if (g.inventory.find(com.claudemod.entity.life.ClaudeInventory::isBuildingBlock).isEmpty()) return null;
        for (Direction d : Direction.values()) if (level.getFluidState(lava.relative(d)).is(net.minecraft.tags.FluidTags.LAVA)) return null; // (a pocket only: lava with more lava beside it is a body, a fall or a lake, and one block in it holds nothing back; stage 818 tunnelled into a fall cell by cell)
        for (Direction d : Direction.values()) { BlockPos n = lava.relative(d); if (n.equals(hole)) continue; var ns = level.getBlockState(n);
            if (ns.getCollisionShape(level, n).isEmpty() || !ns.getFluidState().isEmpty() || ns.canBeReplaced()) continue;
            if (Vec3.atCenterOf(n).add(d.getOpposite().getStepX() * 0.5, d.getOpposite().getStepY() * 0.5, d.getOpposite().getStepZ() * 0.5).distanceTo(g.getEyePosition()) <= 4.5) return n.immutable(); }
        return null;
    }
    /** The click that puts a block into that lava through the open hole: a face of a block bordering it, toward it, at a point he has clear sight of (fluids don't stop the look), in reach; null: none. {block, face, hit point}. */
    private Object[] plugThrough(ServerLevel level, BlockPos hole, BlockPos lava) {
        Vec3 eye = g.getEyePosition();
        for (Direction d : Direction.values()) { BlockPos n = lava.relative(d); if (n.equals(hole)) continue; var ns = level.getBlockState(n);
            if (ns.getCollisionShape(level, n).isEmpty() || !ns.getFluidState().isEmpty() || ns.canBeReplaced()) continue;
            Direction face = d.getOpposite(); Vec3 c0 = Vec3.atCenterOf(n).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
            for (double[] o : new double[][] {{0, 0}, {0.35, 0.35}, {-0.35, 0.35}, {0.35, -0.35}, {-0.35, -0.35}, {0, 0.4}, {0, -0.4}, {0.4, 0}, {-0.4, 0}}) {
                Vec3 hit = face.getAxis() == Direction.Axis.X ? c0.add(0, o[0], o[1]) : face.getAxis() == Direction.Axis.Y ? c0.add(o[0], 0, o[1]) : c0.add(o[0], o[1], 0);
                if (hit.distanceTo(eye) > 4.5) continue;
                var clip = level.clip(new net.minecraft.world.level.ClipContext(eye, hit.add(face.getStepX() * -0.05, face.getStepY() * -0.05, face.getStepZ() * -0.05), net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, g));
                if (clip.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK && clip.getBlockPos().equals(n) && (clip.getDirection() == face || faceSide(eye, n, face))) return new Object[] {n.immutable(), face, hit}; /* (the face itself, not the block from behind: stage 942) */ } }
        return null;
    }
    /** His way goes on past the cell he's to fill, to the side he's on: a walk queued to a cell beside him, further from it than he is (a cut he walked through, put back behind him; ActionPath.tasks). */
    private boolean goingOnPast(BlockPos fill) {
        BlockPos at = g.blockPosition();
        return !at.equals(fill) && g.tasks.stream().anyMatch(q -> q instanceof Task.GoTo go && go.pos().distManhattan(at) <= 2 && go.pos().distManhattan(fill) >= at.distManhattan(fill) && go.pos().distManhattan(at) < go.pos().distManhattan(fill)); // (the next leg on his side: nearer him than the cut, and no nearer the cut than he is (the way's last leg can be his own cell, stage 935); the put-back runs 2 past the cut, its next leg 1-2 on and a step down: a leg 1 off and 2 from the cut sent him back into his mine to patch it from inside, shut in, a new cut 2 s later, live 12:59-13:05 11-09, stage 1268)
    }
    private BlockPos stepOutSpot(BlockPos in) {
        ServerLevel level = (ServerLevel) g.level();
        BlockPos best = null; double bestD = Double.MAX_VALUE;
        { BlockPos at = g.blockPosition(); // already over the edge onto the cell his way goes on to (a cut he walked through, put back behind him): there, never back through the hole (stage 935: back onto the walkway, the wall shut him out and the walk cut it again, a loop)
          if (!at.equals(in) && at.distManhattan(in) == 1 && level.getBlockState(at).getCollisionShape(level, at).isEmpty() && level.getBlockState(at.above()).getCollisionShape(level, at.above()).isEmpty() && level.getBlockState(at.below()).isFaceSturdy(level, at.below(), Direction.UP)
              && goingOnPast(in)) return at.immutable(); }
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
                if (p.equals(pos) || !mayStandIn && p.above().equals(pos) || Vec3.atBottomCenterOf(p).add(0, 1.62, 0).distanceTo(Vec3.atCenterOf(pos)) > REACH - 0.3) continue; // (to fill it: never the cell under it, his head in the very cell; review 215 live 12:19, up a step his 2-high tunnel had no jump room for, 8 s and the patch skipped)
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
    private static java.util.Map<BlockPos, Integer> legDug; // a route leg's cell he broke a block out of, when (static: fields added live start null)
    private static java.util.Map<BlockPos, Integer> legDug() { if (legDug == null) legDug = new java.util.HashMap<>(); return legDug; }
    private static java.util.Map<BlockPos, Integer> forcedAt() { if (forcedAt == null) forcedAt = new java.util.HashMap<>(); return forcedAt; }

    /** In his mine or within walk.out_mine_near (6) of it: a row of its blueprint (branches too), a step of its stairs, a nook. A pocket off his own tunnel walks home through the mine, never out through some cave (D2D, his smelting pocket 2 up from a branch end). */
    private boolean nearHisMine(BlockPos p) {
        if (com.claudemod.director.ClaudeLife.inHisMine(g, p)) return true;
        double r2 = Math.pow(com.claudemod.entity.life.Weights.w("walk.out_mine_near", 6), 2);
        if (g.blueprint != null && !g.blueprint.isEmpty()) for (var row : g.blueprint.rows()) for (BlockPos f : row.feet()) if (f.distSqr(p) <= r2) return true;
        for (BlockPos sp : g.mineSpine) if (sp.distSqr(p) <= r2) return true;
        for (var nk : g.nooks) if (nk != null && nk.mouth().distSqr(p) <= r2) return true;
        return false;
    }

    /**
     * Underground, away from his mine, a far walk that gets no nearer: out to the surface first, the way a player does. Two ways,
     * priced in seconds: back out along his own trail (Route's cells he walked, the nearest one under open sky), or a staircase dug
     * up from here toward the walk (no trail, or a long one). The walk itself stays queued behind: overland from the top, in by his
     * mine's entrance. A way that failed lately is not offered again. False when neither is to be had.
     */
    private boolean wayOut(BlockPos pos, double within) {
        ServerLevel lv = (ServerLevel) g.level(); BlockPos here = g.blockPosition();
        int up = lv.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, here.getX(), here.getZ()) - here.getY();
        java.util.List<BlockPos> way = null;
        { java.util.List<BlockPos> back = com.claudemod.entity.nav.Route.wayBack(g, 20 * 60 * 20);
          java.util.function.Predicate<BlockPos> lava = q -> { for (BlockPos c0 : new BlockPos[] {q, q.above()}) { if (lv.getFluidState(c0).is(net.minecraft.tags.FluidTags.LAVA)) return true; for (Direction d : Direction.values()) if (lv.getFluidState(c0.relative(d)).is(net.minecraft.tags.FluidTags.LAVA)) return true; } return false; }; // (lava in or beside a cell, feet or head: it may have run there since he passed, D2D 12:17)
          java.util.Set<BlockPos> cells = new java.util.HashSet<>(); for (BlockPos q : back) if (!lava.test(q)) cells.add(q); // (his trail and the open cells beside it: a corner he cut leaves no trail cell between two of its own)
          for (BlockPos q : back) for (Direction d : Direction.values()) { BlockPos n = q.relative(d); if (lv.getBlockState(n).getCollisionShape(lv, n).isEmpty() && lv.getBlockState(n.above()).getCollisionShape(lv, n.above()).isEmpty() && !lava.test(n)) cells.add(n.immutable()); }
          BlockPos start = cells.contains(here) ? here : null; double sd = 9;
          if (start == null) for (BlockPos q : back) { double d0 = q.distSqr(here); if (d0 <= sd) { sd = d0; start = q; } }
          java.util.Map<BlockPos, BlockPos> came = new java.util.HashMap<>(); java.util.ArrayDeque<BlockPos> fr = new java.util.ArrayDeque<>(); BlockPos top = null;
          if (start != null) { came.put(start, start); fr.add(start); }
          final boolean toMine = com.claudemod.director.ClaudeLife.inHisMine(g, pos) || com.claudemod.director.ClaudeLife.touchesMine(g, pos); // (a walk into his mine: his trail back into it is the way, never up to the sky and round by his door; live 09:01 11-08, reviews 30/31: a staircase cut 120 up through deepslate, his way in 12 blocks back)
          while (!fr.isEmpty()) { BlockPos q = fr.poll(); if ((lv.canSeeSky(q.above()) || toMine && com.claudemod.director.ClaudeLife.inHisMine(g, q)) && !g.attempts.skipped(com.claudemod.entity.life.Attempts.Kind.WALK, q, g.tickCount, 20 * 60)) { top = q; break; }
              for (Direction d : Direction.values()) { BlockPos n = q.relative(d); if (cells.contains(n) && !came.containsKey(n)) { came.put(n, q); fr.add(n); } } }
          if (top != null) { way = new java.util.ArrayList<>(); for (BlockPos q = top; ; q = came.get(q)) { way.add(0, q); if (q.equals(start)) break; } } }
        double sprint = com.claudemod.entity.life.Decide.sprint(), digS = com.claudemod.entity.life.Weights.w("move.dig_down_s_per_block", 1.0);
        double hx = pos.getX() - here.getX(), hz = pos.getZ() - here.getZ(), hd = Math.max(1, Math.sqrt(hx * hx + hz * hz)), run = up + 30; // (aimed 30 past the staircase's own length: the step cutter only works past the planner's 28, and the planner runs out of time on a 20-up dig, so the cutter must carry him all the way out)
        int sx = (int) Math.floor(here.getX() + hx / hd * run), sz = (int) Math.floor(here.getZ() + hz / hd * run);
        BlockPos stairTop = new BlockPos(sx, lv.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, sx, sz), sz);
        boolean digOk = !g.attempts.skipped(com.claudemod.entity.life.Attempts.Kind.WALK, stairTop, g.tickCount, 20 * 60) && !g.inventory.find(st -> st.getItem() instanceof net.minecraft.world.item.PickaxeItem).isEmpty();
        java.util.List<com.claudemod.entity.life.Decide.Option> opts = new java.util.ArrayList<>();
        if (way != null) opts.add(com.claudemod.entity.life.Decide.Option.of("back out the way he came", way.size() / sprint + Math.sqrt(way.get(way.size() - 1).distSqr(pos)) / sprint, way.size() + " cells of his trail to " + (lv.canSeeSky(way.get(way.size() - 1).above()) ? "the open sky" : "his mine") + " at " + way.get(way.size() - 1).toShortString()));
        if (digOk) opts.add(com.claudemod.entity.life.Decide.Option.of("dig up from here", 2 * up * digS + run / sprint + Math.sqrt(stairTop.distSqr(pos)) / sprint, "a staircase " + up + " up toward it, to " + stairTop.toShortString()));
        if (opts.isEmpty()) { com.claudemod.ClaudeMod.LOGGER.info("[Claude life] {} under the surface, the walk to {} {} off: no trail out and no staircase to cut (no pick, or it failed lately)", up, pos.toShortString(), (int) hd); return false; }
        var pick = com.claudemod.entity.life.Decide.choose(g, "walk.out", (int) hd + " off, " + up + " under the surface, away from his mine", false, opts);
        if (pick == null) return false;
        g.tasks.addFirst(new Task.GoTo(pos, within));
        if (pick.name().startsWith("back")) { for (int k = way.size() - 1; k >= 1; k -= 5) g.tasks.addFirst(new Task.GoTo(way.get(k), 1.2)); } // (a leg every 5 cells back from the open sky, the nearest first)
        else g.tasks.addFirst(new Task.GoTo(stairTop, 2));
        hopsTried.put(pick.name().startsWith("back") ? way.get(way.size() - 1) : stairTop, g.tickCount); // (the way out's own walk is the walker's: the step cutter cuts it, never a way out of its own)
        com.claudemod.ClaudeMod.LOGGER.info("[Claude life] {} under the surface, the walk to {} {} off: {} first (WorkGoal.wayOut)", up, pos.toShortString(), (int) hd, pick.name());
        return true;
    }

    /** A walk farther than his walker plans, or between the surface and his mine: the whole way planned into legs (Route) and put in its place. */
    private boolean routeOut(Task.GoTo t) {
        if (hopsTried.containsKey(t.pos())) return false; // a hop or a way out of his own: the walker's (a route from underground to his staircase's top went back down it, stage 903)
        if (Math.sqrt(BlockPos.containing(g.getX(), g.getY() + 0.2, g.getZ()).distSqr(t.pos())) <= t.within()) return false; // there already: the walk ends, no route (live 21:19: "to -16, 68, -20 (0 blocks off): 3 legs", planned 12 times standing on it)
        var legs = com.claudemod.entity.nav.Route.expand(g, level(), t.pos(), t.within());
        if (legs == null || com.claudemod.entity.nav.Route.walkerTakes(legs)) return false;
        if (legs.isEmpty()) { g.getNavigation().stop(); exitWhy = "behind him on the route he's on: dropped (WorkGoal.routeOut)"; return true; } // (behind him on a route he's on: Route said so, dropped)
        for (int i = legs.size() - 1; i >= 0; i--) g.tasks.addFirst(legs.get(i));
        g.getNavigation().stop();
        exitWhy = "split into " + legs.size() + " legs of his route, walked next, queued by " + com.claudemod.entity.ClaudeEntity.originOf(t) + " (WorkGoal.routeOut)"; // (who sent him: a mine-door walk pushed in front of a salmon search at dusk came from nowhere in the log, live 09:37:40 11-08, review 295) (the walk line said "ended at once ... why null": the reviewer read every long walk as refused, 05:53-06:17 11-05, REVIEW-103/203)
        return true;
    }

    /** The search's budget for a walk: 3000, and 400 more a block of climb past 4 (a 23-up climb out of a lava-sea ledge ran out at 3000 on the cheaper ways round: "no way at all", World 15 22:38). */
    private static int climbBudget(BlockPos from, BlockPos to) { return 3000 + 400 * Math.max(0, to.getY() - from.getY() - 4); }

    /** A spot 5+ up with no way the search found (a fortress deck over the open, World 15 22:38): the player's way, a tower beside it from the ground under its edge, then onto it. */
    /** The blockage solver's Tier 0: no way at all with his rules kept, so once more with his own soft rules bent (ActionPath MAY_BEND). Each bent cell is logged with its rule (that rule blocked the walk: the bug) and his own build is patched back behind him. */
    private boolean bendWay(BlockPos pos, double within, BlockPos feet, ItemStack scaff, com.claudemod.entity.nav.ActionPath.Mode was) {
        var plan = com.claudemod.entity.nav.ActionPath.plan(g, (ServerLevel) g.level(), feet, pos, Math.max(within, 1.0), !scaff.isEmpty(), climbBudget(feet, pos), com.claudemod.entity.nav.ActionPath.Mode.MAY_BEND, g.attempts.of(com.claudemod.entity.life.Attempts.Kind.WALK), g.tickCount);
        if (plan == null) { com.claudemod.ClaudeMod.LOGGER.info("[Claude blockage] no way to {} from {} even with his own rules bent: the world or a hard rule blocks it (WorkGoal.bendWay)", pos.toShortString(), feet.toShortString()); return false; }
        var bent = com.claudemod.entity.nav.ActionPath.lastBent;
        java.util.List<BlockPos> back = new java.util.ArrayList<>();
        for (var e : bent.entrySet()) if (!e.getValue().startsWith("the tree rule")) back.add(e.getKey()); // (a log isn't his work to put back)
        var tasks = new java.util.ArrayList<>(com.claudemod.entity.nav.ActionPath.tasks(plan, feet, scaff.isEmpty() ? net.minecraft.world.item.Items.COBBLESTONE : scaff.getItem(), pos, within, (ServerLevel) g.level()));
        net.minecraft.world.item.Item patch = scaff.isEmpty() || scaff.is(net.minecraft.world.item.Items.DIRT) ? net.minecraft.world.item.Items.COBBLESTONE : scaff.getItem(); // shortcut: patched with his scaffold block, not the block's own kind; match it when a patch's look matters
        java.util.List<Task.Place> patches = new java.util.ArrayList<>();
        for (BlockPos b0 : back) { Task.Place pl = new Task.Place(b0.immutable(), patch); patches.add(pl); int at = -1; boolean cut = false; // patched right behind him, once he has stepped off the cut's column, as a player closes the hole he came through; after the whole walk a floor cut was out of his sight from outside and stayed open (stage 600)
            for (int i0 = 0; i0 < tasks.size() && at < 0; i0++) { Task t0 = tasks.get(i0); if (t0 instanceof Task.Break br && br.pos().equals(b0)) cut = true; else if (cut && t0 instanceof Task.GoTo go && (go.pos().getX() != b0.getX() || go.pos().getZ() != b0.getZ())) at = i0 + 1; }
            if (at < 0) tasks.add(pl); else tasks.add(at, pl); }
        queueWay(tasks, "WorkGoal.bendWay");
        for (Task.Place pl : patches) com.claudemod.entity.ClaudeEntity.ORIGIN.put(pl, "ClaudeLife.nookCutsBack"); // (a build of his: it stays, not a movement block taken back up)
        for (BlockPos b0 : back) if (g.tasks.stream().noneMatch(t0 -> t0 instanceof Task.Place p0 && p0.pos().equals(b0))) com.claudemod.ClaudeMod.LOGGER.info("[Claude blockage] the patch back at {} for the bent way to {} isn't in his queue (refused on add: parked?), so the guard will refuse the dig through it (WorkGoal.bendWay)", b0.toShortString(), pos.toShortString()); // (REVIEW-68 02:01 11-05: the shell dig of a bent way refused as "the shell stays", the way's legs ended at once 97 times; where its patch-back went is unproven)
        com.claudemod.ClaudeMod.LOGGER.info("[Claude blockage] no way to {} from {} with his rules kept ({}); bent: {}; {} digs, {} put down, {} patched back behind him. The rule that blocked it is the bug (WorkGoal.bendWay)", pos.toShortString(), feet.toShortString(), was, bent.isEmpty() ? "nothing (only digging his walk mode refused)" : bent.entrySet().stream().map(e -> e.getValue() + " at " + e.getKey().toShortString()).toList(), plan.digs(), plan.puts(), back.size());
        return true;
    }

    private boolean towerTo(BlockPos pos, BlockPos feet, ItemStack scaff) {
        ServerLevel level = (ServerLevel) g.level(); int up = pos.getY() - feet.getY();
        if (up < 5 || scaff.isEmpty() || scaff.getCount() < up + 2) return false;
        BlockPos[] sg = towerSpot(g, level, pos, feet); if (sg == null) return false; BlockPos bestS = sg[0], bestG = sg[1];
        g.tasks.addFirst(new Task.GoTo(pos, 0.9)); g.tasks.addFirst(new Task.Pillar(bestS.getY() - bestG.getY())); g.tasks.addFirst(new Task.GoTo(bestG, 0.7)); // (0.4 never settled there, a flaky stall)
        com.claudemod.ClaudeMod.LOGGER.info("[Claude life] no way to {} the search found ({} up): a tower beside it, from {} up {} to {}, then onto it (WorkGoal.towerTo)", pos.toShortString(), up, bestG.toShortString(), bestS.getY() - bestG.getY(), bestS.toShortString());
        return true;
    }
    /** A tower's spot for a ledge {@code pos} above him: {top cell beside the ledge, its foot on his level}, or null. */
    public static BlockPos[] towerSpot(com.claudemod.entity.ClaudeEntity g, ServerLevel level, BlockPos pos, BlockPos feet) {
        int up = pos.getY() - feet.getY();
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
        return bestS == null ? null : new BlockPos[] {bestS, bestG};
    }

    private boolean walkTo(BlockPos pos, double within) {
        { var np = g.getNavigation().getPath(); // never walk in under gravel still coming down (04:01 Nov 4: the walk took the fresh step under a falling column, he suffocated)
            if (np != null && !np.isDone()) { BlockPos nn = np.getNextNodePos(); BlockPos falling = nn.equals(g.blockPosition()) ? null : gravelComing((ServerLevel) g.level(), nn);
                if (falling != null) { g.getNavigation().stop(); if (g.tickCount % 20 == 0) com.claudemod.ClaudeMod.LOGGER.info("[Claude walk] waits before {}: gravel at {} still coming down into it (WorkGoal.walkTo)", nn.toShortString(), falling.toShortString()); return false; } } }
        var whyFailed = g.attempts.why(com.claudemod.entity.life.Attempts.Kind.WALK, pos);
        if (whyFailed != null) { // failed for a cause: skipped while that cause holds (the blocks round it, his kit, where he stands), tried again the moment it changes; no clock, no forced try
            if (g.attempts.skipped(com.claudemod.entity.life.Attempts.Kind.WALK, pos, g.tickCount, 0)) { if (skips().merge(pos.immutable(), 1, Integer::sum) % 20 == 1) com.claudemod.ClaudeMod.LOGGER.info("[Claude walk] the walk to {} skipped: it failed {} s ago for {}{}, nothing changed since (WorkGoal.walkTo)", pos.toShortString(), (g.tickCount - whyFailed.tick()) / 20, whyFailed.reason(), whyFailed.detail().isEmpty() ? "" : " (" + whyFailed.detail() + ")"); return exit("the walk there failed for " + whyFailed.reason() + ", nothing changed since"); }
            skips().remove(pos);
        }
        Integer failedAt = g.attempts.of(com.claudemod.entity.life.Attempts.Kind.WALK).get(pos);
        if (failedAt != null && whyFailed == null && g.tickCount - failedAt < 20 * 60) { // couldn't get there a moment ago (a failure with no cause on record: the old window, till its writer gives one)
            if (skips().merge(pos.immutable(), 1, Integer::sum) <= 3) { com.claudemod.ClaudeMod.LOGGER.info("[Claude walk] the walk to {} failed {} s ago: skipped this time ({} of 3) (WorkGoal.walkTo)", pos.toShortString(), (g.tickCount - failedAt) / 20, skips().get(pos)); return exit("the walk there failed " + (g.tickCount - failedAt) / 20 + " s ago: skipped"); }
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
        if (!g.level().getBlockState(pos).getCollisionShape(g.level(), pos).isEmpty() && inReach(pos) && g.tasks.peekFirst() instanceof Task.GoTo && g.level().getBlockState(pos).getDestroySpeed(g.level(), pos) >= 0
                && g.tickCount - legDug().getOrDefault(pos.immutable(), -100000) > 20 * 20 && !com.claudemod.entity.life.TempLedger.built(g, pos) && !com.claudemod.entity.life.TempLedger.has(g, pos) && !com.claudemod.entity.life.PortalMap.frameBlock(g, pos)) { /* (nor a block of his put down on a way: his nerd-pole broken under him block by block, live 04:13:47, stage 796) (never his own build: a block his highway or bore put down after the route was planned stays; the re-plan goes round, live 22:57 / 23:08:22, stage 790) */ // a leg of a route whose cell has a block in it: the player's way, the block and on (reviews 110/140: one basalt, one netherrack, a diamond pick on him, the leg dropped for a re-plan); once per cell, doBreak's guards decide
            legDug().put(pos.immutable(), g.tickCount); if (legDug().size() > 64) legDug().clear();
            g.tasks.addFirst(new Task.GoTo(pos, within)); g.tasks.addFirst(new Task.Break(pos.immutable()));
            com.claudemod.ClaudeMod.LOGGER.info("[Claude walk] the walk to {}: {} in the leg's cell, in reach: breaking it and walking on (WorkGoal.walkTo)", pos.toShortString(), g.level().getBlockState(pos).getBlock().getName().getString());
            return true;
        }
        if (!g.level().getBlockState(pos).getCollisionShape(g.level(), pos).isEmpty() && inReach(pos)) { if (g.tasks.peekFirst() instanceof Task.GoTo) com.claudemod.ClaudeMod.LOGGER.info("[Claude walk] the walk to {} ends at once: {} is there, in reach, with more walks queued (a route cell not dug?) (WorkGoal.walkTo)", pos.toShortString(), g.level().getBlockState(pos).getBlock().getName().getString()); return true; } // a walk to a block (a trunk, ore): in reach of it is there // ponytail: live 15:35 a route walk into a cell planned dug vanished silently, 30 s stuck: logged to prove it
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
        if (ticks <= 1) { walkSince.remove(pos); walkBest.remove(pos); } // ponytail: the task's (re)start restarts the 20 s clock; live 15:52 a GoTo resumed after 20 s in cover kept its pre-cover best distance and gave up at once 7 off (time on other tasks never counts)
        Double bestD = walkBest.get(pos);
        if (bestD == null || d < bestD - 0.5) { walkBest.put(pos.immutable(), d); walkSince.put(pos.immutable(), g.tickCount); } // getting closer: the clock starts over
        int since = walkSince.computeIfAbsent(pos.immutable(), k -> g.tickCount);
        if (d > within && d < 8 && g.tickCount - since > 20 * 20 && g.tickCount - since < 20 * 60) { // 20s on a spot a few blocks away: it's not happening
            walkSince.remove(pos); walkBest.remove(pos);
            var cause = com.claudemod.entity.life.Stall.diagnose(g, pos, within);
            g.attempts.fail(com.claudemod.entity.life.Attempts.Kind.WALK, pos, g.tickCount, g.blockPosition(), cause.reason(), cause.detail(), cause.mob());
            g.attempts.bandaid("the 20 s walk clock fired " + (int) d + " off " + pos.toShortString() + ": " + cause.say()); // (a generic clock ending a walk: the cause it found is the bug to fix)
            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] gave up walking to {} after 20s (he's at {}): {}", pos.toShortString(), g.blockPosition().toShortString(), cause.say());
            com.claudemod.entity.nav.Route.legFailed(g, pos);
            return true;
        }
        // stepping up onto a step he just cut: a second of just that (hop and walk onto it), no path pulling him elsewhere
        if (stepUpTo != null && g.tickCount - stepUpTick < 20 && gravelComing((ServerLevel) g.level(), BlockPos.containing(stepUpTo)) != null) { stepUpTo = null; pendingStepUp = null; } // gravel still coming down onto the step: no hop; the cutter waits it out (04:01 Nov 4, suffocated)
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
                routeReaches = reaches(p, aim, within);
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
        if (g.isInWater() && pos.getY() < g.getBlockY() && g.level().getFluidState(pos).is(net.minecraft.tags.FluidTags.WATER) && d > within) { // under the water (one below his floating feet counts: 10:36 he bobbed over -43,61,132 for minutes, "path none", then swam to shore and jumped back in): dive straight for it like a player (the body comes up for air by itself)
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
        boolean towardIt = !(g.tasks.peekFirst() instanceof Task.GoTo nx2) || Math.sqrt(feetCell.distSqr(nx2.pos())) <= Math.sqrt(pos.distSqr(nx2.pos())) + 1.0; // (run through a waypoint only where skipping it leaves the next leg no farther: a corner, not the step before it. On the Nether ledge the planner's first step -26,38,-48 was 2.0 off and "run through" on its first tick, his momentum toward it notwithstanding, the spot still 4 off and the same way planned again every 15 s, 08:16-08:45 11-05, stage 715)
        if ((d <= within || (chain && towardIt && d <= 2.0 && pos.getY() <= feetCell.getY())) && !onLeavesAbove) { // ponytail: live 19:40 -58,79,-901: a route's 1-up step 1.4 off counted as reached in a chain, so the next leg was 2 up and refused, a loop for minutes; a step up is only reached by stepping up
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
            routeReaches = reaches(path, aim, within);
            if (routeReaches && path != null && torchTooFar(path.getNodeCount() - path.getNextNodeIndex())) { g.getNavigation().stop(); return false; }
            if (routeReaches && d > within && path != null && path.getEndNode() != null && path.getEndNode().asBlockPos().equals(feetCell)) routeReaches = false; // a path that ends where he stands takes him nowhere: the goal past `within` isn't reached (D2D reviews 228/360, 10:08 and 10:26: a route leg in a pond 1 down, 2 off, aimed at the dry cell he stood on, "path 0/1" read as reached, 8 s still and the spot dropped; stage 901)
            if (routeReaches) for (int i = Math.max(1, path.getNextNodeIndex()); i < path.getNodeCount(); i++) { var b = path.getNode(i); double ay = i == path.getNextNodeIndex() ? g.getY() : path.getNode(i - 1).y; // (the next step against his feet, as Controls judges it: the start node can sit a block over them)
                if (b.y - ay > 1.25 && !g.level().getBlockState(new BlockPos(b.x, b.y, b.z)).is(net.minecraft.tags.BlockTags.CLIMBABLE) && !g.level().getBlockState(new BlockPos(b.x, b.y - 1, b.z)).is(net.minecraft.tags.BlockTags.CLIMBABLE)) { routeReaches = false; if (ticks % 200 == 1) com.claudemod.ClaudeMod.LOGGER.info("[Claude walk] the way to {} has a step {} up at {},{},{}: not his way, planning his own (WorkGoal.walkTo)", pos.toShortString(), String.format("%.1f", b.y - ay), b.x, b.y, b.z); break; } }
            if (current instanceof Task.GoTo && path != null && path.getNodeCount() > 0) { // a crowd of hostiles on the way: round them, or not this walk (he walked through the pack on his death pile, twice)
                java.util.List<net.minecraft.world.phys.Vec3> threats = new java.util.ArrayList<>(); int onRoute = 0; java.util.List<net.minecraft.world.entity.Mob> blockers = new java.util.ArrayList<>();
                if (ticks <= 2) { float yaw = (float) Math.toDegrees(Math.atan2(-(pos.getX() + 0.5 - g.getX()), pos.getZ() + 0.5 - g.getZ())); g.setYRot(yaw); g.setYHeadRot(yaw); g.yBodyRot = yaw; com.claudemod.entity.life.Perception.lookNow(g); } // a player looks the way he's going before he sets off: what's on the way is seen before the way is chosen
                for (var m : enemiesNear(24)) {
                    if (!(m.distanceTo(g) < 8 || g.hasLineOfSight(m))) continue; // (what he can see or hear close by, nothing through walls)
                    threats.add(m.position());
                    if (pest(m)) continue; // (two silverfish from the portal room's spawner kept him out of its doorway 3 times, stage 559)
                    if (m instanceof net.minecraft.world.entity.monster.Blaze || m instanceof net.minecraft.world.entity.FlyingMob) continue; // fliers hover off the floor: they shoot, they don't block a way (06:07 four blazes over his own rods 4 off kept every pick_up walk from starting, rods left to despawn)
                    for (int i = path.getNextNodeIndex(); i < path.getNodeCount(); i++) if (path.getNode(i).asBlockPos().distSqr(m.blockPosition()) < 9) { onRoute++; blockers.add(m); break; }
                }
                int crowd = !armed() || weakKit() ? 1 : 2;
                if (onRoute >= crowd && pos.distSqr(g.blockPosition()) > 4) {
                    g.getNavigation().stop();
                    if (g.tickCount - plannedAt().getOrDefault(pos.above(500).immutable(), -100000) > 60) {
                        plannedAt().put(pos.above(500).immutable(), g.tickCount); // (keyed apart from the ordinary plans for this spot)
                        var plan = com.claudemod.entity.nav.ActionPath.plan(g, (ServerLevel) g.level(), feetCell, pos, Math.max(within, 1.0), false, 3000, mayDig(pos) ? com.claudemod.entity.nav.ActionPath.Mode.MAY_DIG : com.claudemod.entity.nav.ActionPath.Mode.NO_DIG, g.attempts.of(com.claudemod.entity.life.Attempts.Kind.WALK), g.tickCount, threats); // (round them through the rock too, with a pick: from a hole the only way round is a tunnel, live 10:46 11-05 Nether, stage 672)
                        if (plan != null) {
                            var tasks = com.claudemod.entity.nav.ActionPath.tasks(plan, feetCell, net.minecraft.world.item.Items.COBBLESTONE, pos, within, (ServerLevel) g.level());
                            queueWay(tasks, "WorkGoal.walkTo");
                            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] {} hostile(s) on the way to {}: going round them ({} walks)", onRoute, pos.toShortString(), plan.walks());
                            return true;
                        }
                    }
                    if (!blockers.isEmpty() && armed()) { // no way round them: clearing them priced against waiting for them to move off (review 536, live 03:29: three magma cubes across his highway in his diamond kit, the walk written off and the route stalled; stage 940)
                        blockers.sort(java.util.Comparator.comparingDouble(m -> m.distanceTo(g)));
                        shouldFlee(blockers.get(0), false); double fightS = pricedFightS; // shortcut: prices the nearest blocker and the hostiles within 10 of him, not blockers further on the way; they come in as he closes
                        if (!Double.isNaN(fightS)) {
                            var pick = com.claudemod.entity.life.Decide.choose(g, "walk.blocked", onRoute + " hostile(s) on the way to " + pos.toShortString() + " and no way round them, health " + (int) g.getHealth() + ", armor " + g.getArmorValue(), true, java.util.List.of(
                                com.claudemod.entity.life.Decide.Option.of("clear them", fightS, "the fight as fight.flee prices it" + (fightOdds == null ? "" : " (" + fightOdds + ")") + ", then on"),
                                com.claudemod.entity.life.Decide.Option.of("wait", w("walk.blocked_wait_s", 90), "for them to move off his way; the walk written off till then")));
                            if (pick != null && pick.name().equals("clear them")) {
                                g.tasks.addFirst(new Task.GoTo(pos.immutable(), within));
                                for (int i = blockers.size() - 1; i >= 0; i--) g.tasks.addFirst(new Task.Attack(blockers.get(i).getUUID(), 20 * 30));
                                com.claudemod.ClaudeMod.LOGGER.info("[Claude life] {} hostile(s) between him and {}, no way round: clearing them, then on (WorkGoal.walkTo)", onRoute, pos.toShortString());
                                return true;
                            }
                        }
                    }
                    { var hm = nearestHostile(); g.attempts.fail(com.claudemod.entity.life.Attempts.Kind.WALK, pos, g.tickCount, g.blockPosition(), com.claudemod.entity.life.Attempts.Reason.BLOCKED_MOB, onRoute + " hostile(s) on the way, no way round them", hm == null ? null : hm.getUUID()); }
                    com.claudemod.ClaudeMod.LOGGER.info("[Claude life] {} hostile(s) between him and {} ({}): not walking through them", onRoute, pos.toShortString(), armed() ? "a crowd" : "unarmed");
                    { int held = 0; Vec3 me0 = g.position(); // the legs of his way queued straight after, past the same ones: they wait with this one, not a search round them each (BP on D2D review 536, live 03:29:42: three magma cubes across his highway, 9 legs, 9 searches of 500 ms, stage 797)
                      while (g.tasks.peekFirst() instanceof Task.GoTo nx) { Vec3 to = Vec3.atBottomCenterOf(nx.pos()).subtract(me0).multiply(1, 0, 1); double len = to.length(); boolean past = false;
                        if (len > 1e-3) for (var m : blockers) { Vec3 tm = m.position().subtract(me0).multiply(1, 0, 1); double along = tm.dot(to) / len; if (along > 0 && along < len && tm.subtract(to.scale(along / len)).length() < 3) { past = true; break; } }
                        if (!past) break;
                        g.tasks.pollFirst(); held++; g.attempts.fail(com.claudemod.entity.life.Attempts.Kind.WALK, nx.pos(), g.tickCount, g.blockPosition(), com.claudemod.entity.life.Attempts.Reason.BLOCKED_MOB, "behind the same " + onRoute + " hostile(s) as the leg to " + pos.toShortString(), blockers.isEmpty() ? null : blockers.get(0).getUUID()); }
                      if (held > 0) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] the next {} leg(s) of his way go past the same {} hostile(s): they wait with it (WorkGoal.walkTo)", held, onRoute); }
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
        // A cobweb in his cells or the next one on the way: cut with the sword like a player, never waded through (live 01:05, stuck in the stronghold's webs)
        if (inReach(pos) || d > 0.8) {
            BlockPos feet = BlockPos.containing(g.getX(), g.getY() + 0.2, g.getZ());
            Vec3 to = Vec3.atCenterOf(pos).subtract(g.position()); to = new Vec3(to.x, 0, to.z);
            BlockPos ahead = to.lengthSqr() < 0.01 ? feet : feet.relative(Direction.getNearest(to.x, 0, to.z));
            for (BlockPos w : new BlockPos[]{feet, feet.above(), ahead, ahead.above()})
                if (g.level().getBlockState(w).is(net.minecraft.world.level.block.Blocks.COBWEB)) {
                    com.claudemod.ClaudeMod.LOGGER.info("[Claude life] a cobweb at {} on his way to {}: cutting it (WorkGoal.walkTo)", w.toShortString(), pos.toShortString());
                    g.tasks.addFirst(new Task.GoTo(pos, within));
                    g.tasks.addFirst(new Task.Break(w.immutable()));
                    return true;
                }
        }
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
        if (com.claudemod.entity.body.Controls.refusedLately(g, 10) && routeReaches) { routeReaches = false; if (!pos.equals(refusedSaidFor)) { refusedSaidFor = pos.immutable(); com.claudemod.ClaudeMod.LOGGER.info("[Claude walk] his body refused the path's step on the walk to {} (2+ up, or lava): the path doesn't really reach, the walk's own plan takes it (WorkGoal.walkTo)", pos.toShortString()); com.claudemod.entity.body.Controls.traceMoves(g, 40); } } // (said once a walk: it was 4 a second, Day to Day) // (live 2026-11-01, 3 times: the probe's path 2 up by a fortress, he stood till the 20 s give-up; 03:53 a vanilla path over a lava drop ActionPath had turned down)
        boolean deepWater = g.isInWater() && !com.claudemod.entity.body.Controls.wading(g);
        if (ticks == 2 && !routeReaches && !g.onGround() && !g.isInWater() && !g.onClimbable()) airPlanFor = pos.immutable(); // (in the air on tick 2: the plan waits for the landing, stage 385)
        boolean resumePlan = pos.equals(planningFor) && g.tickCount - planningTick <= 20 && (g.onGround() || g.isInWater() || g.onClimbable()); // (his plan for this walk still being searched, a slice a tick: on with it)
        if (resumePlan || (((ticks == 2 || ticks <= 40 && pos.equals(airPlanFor)) && !routeReaches || stuck == 20 || stuck > 20 && stuck % 200 == 20) && d > Math.max(within, 1.0) && d <= 28 && !walkNoDig && !routeReaches && !deepWater && !(inHole() && g.isInWater()) // (a dry hole: planned like any walk, MAY_DIG; a flooded one stays the cutter's) (airborne on tick 2: planned once landed, up to tick 40; the partial path had walked him into a trench, stage 385)
                && g.tickCount - plannedAt().getOrDefault(pos.immutable(), -100000) > 60 // (3 s between plans for one spot: a loop guard, not a wait)
                && (g.onGround() || g.isInWater() || g.onClimbable() || airborneSaid(pos)))) { // (in the air: planned once he's landed; a plan from mid-air started its leap from a cell he wasn't standing on, live 10:36, 5 min of bobbing)
            plannedAt().put(pos.immutable(), g.tickCount); if (plannedAt().size() > 64) plannedAt().clear(); airPlanFor = null; planningFor = null;
            long t0 = System.nanoTime();
            var mode = mayDig(pos) ? com.claudemod.entity.nav.ActionPath.Mode.MAY_DIG : com.claudemod.entity.nav.ActionPath.Mode.NO_DIG;
            ItemStack scaff0 = scaffoldBlock(), scaff = solidScaffold(); // (the made way's puts: never a block that falls; doPlace won't set gravel over air, the way was dropped and made again the same second, ~2 min at 318,56,441, live 21:37 11-09, D2D 187/188/190, stage 1283; a tower takes gravel fine)
            boolean dirtOnHim = !scaff.isEmpty() && (scaff.is(net.minecraft.world.item.Items.DIRT) || scaff.is(net.minecraft.world.item.Items.GRASS_BLOCK) || scaff.is(net.minecraft.world.item.Items.COARSE_DIRT) || scaff.is(net.minecraft.world.item.Items.ROOTED_DIRT));
            boolean outside = g.level().canSeeSky(feetCell.above()) && !com.claudemod.director.ClaudeLife.inHisMine(g, feetCell);
            var plan = outside && !scaff.isEmpty() && !dirtOnHim ? com.claudemod.entity.nav.ActionPath.planSliced(g, (ServerLevel) g.level(), feetCell, pos, Math.max(within, 1.0), false, climbBudget(feetCell, pos), mode, g.attempts.of(com.claudemod.entity.life.Attempts.Kind.WALK), g.tickCount, java.util.List.of(), 1.0) : null; if (plan == com.claudemod.entity.nav.ActionPath.PENDING) { planningFor = pos.immutable(); planningTick = g.tickCount; g.getNavigation().stop(); return false; } // (still searching: stood still, the walk on, the search on next tick) // (the user: out in the world only dirt is put down and left; with none on him, a way without placing first)
            if (plan == null) plan = com.claudemod.entity.nav.ActionPath.planSliced(g, (ServerLevel) g.level(), feetCell, pos, Math.max(within, 1.0), !scaff.isEmpty(), climbBudget(feetCell, pos), mode, g.attempts.of(com.claudemod.entity.life.Attempts.Kind.WALK), g.tickCount, java.util.List.of(), 1.0); if (plan == com.claudemod.entity.nav.ActionPath.PENDING) { planningFor = pos.immutable(); planningTick = g.tickCount; g.getNavigation().stop(); return false; } // (still searching: stood still, the walk on, the search on next tick)
            if (plan == null) { // anywhere, not only the Nether (stage 982, review 57: 10 of deepslate to his stairs from a wide cave underground, the plain search spent 3000 nodes on the cave, 1.5 min of cutting steps instead). The Nether: a way that is mostly bridge or tunnel costs several times the heuristic a cell, and the plain search spent its budget on the ledge round him (stage 535: a 13-block bridge to the deck, "no way at all" with 54 blocks on him); once more, greedy (w 2.5), twice the budget
                double greedyW = com.claudemod.entity.nav.ActionPath.lineWeight((ServerLevel) g.level(), feetCell, pos); // (the straight line's own cost a block: a lava bridge or a tower is far more than 2.5)
                plan = com.claudemod.entity.nav.ActionPath.planSliced(g, (ServerLevel) g.level(), feetCell, pos, Math.max(within, 1.0), !scaff.isEmpty(), climbBudget(feetCell, pos) * 2, mode, g.attempts.of(com.claudemod.entity.life.Attempts.Kind.WALK), g.tickCount, java.util.List.of(), greedyW); if (plan == com.claudemod.entity.nav.ActionPath.PENDING) { planningFor = pos.immutable(); planningTick = g.tickCount; g.getNavigation().stop(); return false; } // (still searching: stood still, the walk on, the search on next tick)
                com.claudemod.ClaudeMod.LOGGER.info("[Claude route] {} -> {}: no way in the plain search; the greedy one (w {}): {} (WorkGoal.walkTo)", feetCell.toShortString(), pos.toShortString(), String.format("%.1f", greedyW), plan == null ? "none either" : plan.digs() + " digs, " + plan.puts() + " put down, " + plan.walks() + " walks");
            }
            java.util.List<BlockPos> nookCuts = new java.util.ArrayList<>();
            if (plan == null && mode == com.claudemod.entity.nav.ActionPath.Mode.MAY_DIG && !com.claudemod.director.ClaudeLife.allNooks(g).isEmpty() && com.claudemod.director.ClaudeLife.nookErrand(g, pos)) { // (only a walk into his nook: a walk to them or anywhere else with no way but through it stands by, said below) // no way at all with his nook's shell kept: once more, cutting it only where nothing else gets there
                var cutPlan = com.claudemod.entity.nav.ActionPath.plan(g, (ServerLevel) g.level(), feetCell, pos, Math.max(within, 1.0), !scaff.isEmpty(), 3000, com.claudemod.entity.nav.ActionPath.Mode.MAY_CUT_NOOK, g.attempts.of(com.claudemod.entity.life.Attempts.Kind.WALK), g.tickCount);
                if (cutPlan != null) for (var a : cutPlan.acts()) if (a instanceof com.claudemod.entity.nav.ActionPath.Act.Dig dg && com.claudemod.director.ClaudeLife.nookShell(g, dg.pos())) nookCuts.add(dg.pos().immutable());
                if (cutPlan != null && !nookCuts.isEmpty()) { plan = cutPlan; com.claudemod.ClaudeMod.LOGGER.info("[Claude route] no way to {} from {} with his nook's shell kept: cutting it at {}, each patched behind him once he's through (WorkGoal.walkTo)", pos.toShortString(), feetCell.toShortString(), nookCuts.stream().map(BlockPos::toShortString).toList()); }
                else if (cutPlan != null) plan = cutPlan; // (a way the first search missed, none of it his shell)
            }
            if (plan != null && torchTooFar(plan.walks() + 2 * plan.digs() + plan.puts())) return false;
            if (plan != null && (plan.digs() > 0 || plan.puts() > 0 || plan.acts().stream().anyMatch(a -> a instanceof com.claudemod.entity.nav.ActionPath.Act.Door || a instanceof com.claudemod.entity.nav.ActionPath.Act.Leap))) { // (a drop or a jump: its own task, Task.Leap)
                if (!nookCuts.isEmpty()) com.claudemod.director.ClaudeLife.nookCutsBack(g, nookCuts, scaff.isEmpty() || scaff.is(net.minecraft.world.item.Items.DIRT) ? net.minecraft.world.item.Items.COBBLESTONE : scaff.getItem()); // (queued first: the walk goes in front of them, so they run once he's through)
                var tasks = com.claudemod.entity.nav.ActionPath.tasks(plan, feetCell, scaff.isEmpty() ? net.minecraft.world.item.Items.COBBLESTONE : scaff.getItem(), pos, within, (ServerLevel) g.level());
                queueWay(tasks, "WorkGoal.walkTo");
                com.claudemod.ClaudeMod.LOGGER.info("[Claude route] the made way to {}: acts {} -> tasks {} (WorkGoal.walkTo)", pos.toShortString(), plan.acts(), tasks);
                com.claudemod.ClaudeMod.LOGGER.info("[Claude life] no route to {}: made one ({}: {} digs, {} blocks put down{}, {} walks; {} ms)", pos.toShortString(), mode, plan.digs(), plan.puts(), plan.puts() > 0 && outside && !dirtOnHim ? " of " + com.claudemod.entity.life.Crafting.name(scaff.getItem()) + ", to take back up" : "", plan.walks(), (System.nanoTime() - t0) / 1000000);
                return true;
            }
            if (plan != null) { // a way on foot the vanilla path missed (over a ledge, through an open door): its waypoints
                var tasks = com.claudemod.entity.nav.ActionPath.tasks(plan, feetCell, net.minecraft.world.item.Items.COBBLESTONE, pos, within);
                if (tasks.size() > 1) { queueWay(tasks, "WorkGoal.walkTo"); com.claudemod.ClaudeMod.LOGGER.info("[Claude life] route to {} found on foot ({} walks, {} waypoints): acts {} -> {} (WorkGoal.walkTo)", pos.toShortString(), plan.walks(), tasks.size() - 1, plan.acts(), tasks); return true; }
            } else if (towerTo(pos, feetCell, scaff0)) { return true;
            } else if (bendWay(pos, within, feetCell, scaff0, mode)) { return true;
            } else if (level().dimension() == net.minecraft.world.level.Level.NETHER && com.claudemod.entity.life.FortressScout.bores(g, level()) && Math.abs(pos.getX() - feetCell.getX()) + Math.abs(pos.getZ() - feetCell.getZ()) >= 3
                    && !boreLineStopped(feetCell, pos)) { // the user 11-05: "he should be able to bridge over lava"; no walk there: straight there, lava paved and rock dug (review-186/192, live 13:06 the walk home ended 27 off at a lava gap)
                com.claudemod.ClaudeMod.LOGGER.info("[Claude route] no walk to {} from {} ({}): straight there instead, the lava paved and the rock dug (WorkGoal.walkTo)", pos.toShortString(), feetCell.toShortString(), mode);
                g.tasks.addFirst(new Task.GoTo(pos, within)); g.tasks.addFirst(new Task.Bore(pos, pos.getY())); return true;
            } else {
                com.claudemod.ClaudeMod.LOGGER.info("[Claude life] no way to {} from {} ({}; blocks on him: {}): skipping it", pos.toShortString(), feetCell.toShortString(), mode, scaffoldBlock().getCount());
                noWayFor = pos.immutable(); noWayWhy = "no way with every planner (" + mode + ", " + scaffoldBlock().getCount() + " blocks on him)";
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
                failed(com.claudemod.entity.life.Attempts.Kind.WALK, pos, com.claudemod.entity.life.Attempts.Reason.GUARD, "his own station under it: reached by its stairs, never a hole cut over it");
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
                    failed(com.claudemod.entity.life.Attempts.Kind.WALK, pos, com.claudemod.entity.life.Attempts.Reason.BLOCKED_BLOCK, "solid rock, not a step of his stairs");
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
            if (g.mineShape != null && g.level().dimension() == net.minecraft.world.level.Level.OVERWORLD && !com.claudemod.director.ClaudeLife.inHisMine(g, g.blockPosition())
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
                if (g.tickCount % 100 < 2 || stuck > stepWait + 1) com.claudemod.ClaudeMod.LOGGER.info("[Claude walk] stuck {} ticks at {} on the walk to {} ({} off): a route part of the way, node {}/{} ending {}, walked first, no steps cut yet (WorkGoal.walkTo step cutter)", stuck, g.blockPosition().toShortString(), pos.toShortString(), (int) d, stepPath.getNextNodeIndex(), stepPath.getNodeCount(), stepPath.getEndNode() == null ? "-" : stepPath.getEndNode().asBlockPos().toShortString()); // (D2D review 49/51, live 22:28 11-07: 2 min at -225,-34,-18 with a short path 2/44 he spun on, the cutter never ran; unproven which branch held it, this says)
                stuck = 30;
                return false;
            }
            boolean plannerHadItsSay = d <= 28 && !(g.isInWater() && !com.claudemod.entity.body.Controls.wading(g)) && !(inHole() && g.isEyeInFluid(net.minecraft.tags.FluidTags.WATER)); // (shallow water in a hole: the planner pillars out of it now, Nov 4; the cutter racing it dithered stage 30) (the planner plans dug staircases itself within 28, out of a dry hole too (MAY_DIG there); deep water and a flooded hole are the cutter's)
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
                    g.getNavigation().getPath() == null ? "none" : g.getNavigation().getPath().getNextNodeIndex() + "/" + g.getNavigation().getPath().getNodeCount() + " ending " + (g.getNavigation().getPath().getEndNode() == null ? "-" : g.getNavigation().getPath().getEndNode().asBlockPos().toShortString()) + (g.getNavigation().getPath().canReach() ? " (reaches)" : " (short)") + ", plan reaches " + routeReaches,
                    lv.getBlockState(g.blockPosition().below()).getBlock().getName().getString());
        }
        // Too far for one path (it plans ~30 blocks): a hop of ~20 toward it over the ground first, then this walk again
        double hx = pos.getX() + 0.5 - g.getX(), hz = pos.getZ() + 0.5 - g.getZ(), hd = Math.sqrt(hx * hx + hz * hz);
        if (stuck == 40 && (g.pillarBlocks.contains(standingOn()) || spire(standingOn())) && pos.getY() < g.getBlockY() - 1 && descendStep(pos)) return true; // up on his own pillar: down it first, a hop from up here goes nowhere
        boolean roofed = g.level().dimensionType().hasCeiling(); // (the Nether: the heightmap is the bedrock roof, so "at the surface" never held and long walks never hopped)
        if ((stuck >= 40 && stuck % 40 == 0 || ticks == 2 && (g.level().canSeeSky(pos.above()) || com.claudemod.director.ClaudeLife.inHisMine(g, pos) || com.claudemod.director.ClaudeLife.touchesMine(g, pos))) && hd > 28 && !roofed && g.getBlockY() < g.level().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, g.getBlockX(), g.getBlockZ()) - 3 && !hopsTried.containsKey(pos) && !nearHisMine(g.blockPosition()) && wayOut(pos, within)) return true; // underground far from his mine, a far walk with no headway, or one to the surface or his mine from its start (caves toward it went deeper, live 12:15 "stuck in a cave outside his iron mine"): out to the surface first (the user 11-06: "backtrack to get out to the surface and then go back to his mine entrance"; reviews 226/259/260)
        if (stuck >= 40 && stuck % 40 == 0 && hd > 28 && (roofed || g.getBlockY() >= g.level().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, g.getBlockX(), g.getBlockZ()) - 3)) { // (at the surface, an overhang over him or not: "sky over his head" left him stalled under a ledge 305 blocks from his mine) // (every 2 s of no headway, not at 40 alone: a walk paused by its own step cuts comes back with its clock past 40, and the hop never came, D2D reviews 100/111, 4 min in his cobble pit, stage 902)
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
            if (mid != null) { // (none: every way round's failed lately, so fall through to the count below and the normal give-up; a return here held stuck at 40 forever, live 13:01 Nether)
            g.tasks.addFirst(new Task.GoTo(pos, within));
            g.tasks.addFirst(new Task.GoTo(mid, 4));
            hopsTried.put(mid.immutable(), g.tickCount); /* (its own record: in Attempts WALK the walker skipped the fresh hop as "failed 0 s ago", stage 371) */
            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] long walk to {} ({} blocks): hopping to {} first", pos.toShortString(), (int) hd, mid.toShortString());
            return true;
        } }
        { var np = g.getNavigation().getPath(); if (np != null && np == walkPath && np.getNextNodeIndex() > walkNode && !np.isDone() && !pos.equals(noWayFor)) { stuck = 0; lastDist = Math.min(lastDist, d); } walkPath = np; walkNode = np == null ? -1 : np.getNextNodeIndex(); } // on along his route counts as headway (round his house to its door the line to the bed gets no shorter for seconds)
        if (d < lastDist - 0.5 && !pos.equals(noWayFor)) { lastDist = d; stuck = 0; } else if (++stuck > STUCK_LIMIT) { // (a spot every planner has no way to: no headway on the plain path undoes the give-up, live 09:14 11-05 into Nether lava, stage 670)
            // Can't get to this spot: skip just the work tied to it (up to the next walk), keep the rest of the job,
            // and grab whatever already dropped around him first.
            // Later walks to the same spot (or right next to it) in this job would fail the same way: drop those too.
            { boolean noWay = pos.equals(noWayFor); var cause = noWay ? new com.claudemod.entity.life.Stall.Cause(com.claudemod.entity.life.Attempts.Reason.NO_PATH, noWayWhy, null) : com.claudemod.entity.life.Stall.diagnose(g, pos, within); noWayFor = null;
              g.attempts.fail(com.claudemod.entity.life.Attempts.Kind.WALK, pos, g.tickCount, g.blockPosition(), cause.reason(), cause.detail(), cause.mob());
              if (!noWay) g.attempts.bandaid("the 8 s walk clock fired on " + pos.toShortString() + " with a plan in hand: " + cause.say()); // (every planner had a way, the body didn't get closer: that's the bug)
              if (cause.reason() == com.claudemod.entity.life.Attempts.Reason.NO_TOOL) { String tool = com.claudemod.entity.life.Stall.toolFor(g, cause.detail()); if (tool != null && g.skillQueue.stream().noneMatch(x -> x.target().equals(tool))) { g.skillQueue.addFirst(new com.claudemod.entity.life.Skills.Step("obtain", tool, 1)); com.claudemod.ClaudeMod.LOGGER.info("[Claude walk] {} in the way to {} needs a {}: getting one (WorkGoal.walkTo)", cause.detail(), pos.toShortString(), tool); } }
              else if (cause.reason() == com.claudemod.entity.life.Attempts.Reason.BLOCKED_BLOCK && !noWay) forceDigAt = pos.immutable(); } // (a breakable block the walk stood at: the next go digs through)
            // A walk to a block he'll break (a trunk, ore): the Breaks get there their own way (tower, dig, bridge); keep them.
            boolean toABlock = !g.level().getBlockState(pos).getCollisionShape(g.level(), pos).isEmpty();
            boolean leg = com.claudemod.entity.nav.Route.isLeg(g, pos);
            if (leg) com.claudemod.entity.nav.Route.legFailed(g, pos);
            if (!toABlock) g.tasks.removeIf(x -> x instanceof Task.GoTo n ? n.pos().distSqr(pos) <= 2 // only the work tied to that spot: everything else (an ore vein, the next legs) stays
                    : x instanceof Task.Place pl ? pl.pos().distSqr(pos) <= 16 && g.pendingPatches.add(pl.pos().immutable()) /* (a patch there isn't forgotten: done when he's back by it) */ : x instanceof Task.WallTorch w ? w.pos().distSqr(pos) <= 16
                    : x instanceof Task.SealStep ss && ss.feet().distSqr(pos) <= 16);
            if (!leg && !toABlock && g.blueprint != null && !g.blueprint.isEmpty() && (pos.distSqr(g.blueprint.entrance) <= 9 || pos.distSqr(g.blueprint.outside()) <= 9) && !g.blueprint.touches(g.blockPosition())) { // the way into his mine is out of reach: the legs down it behind this walk go too (157 of them failed one by one, 7 s each)
                if (!g.attempts.within(com.claudemod.entity.life.Attempts.Kind.SENT_FROM, g.blueprint.entrance, g.tickCount, 20 * 120)) { failed(com.claudemod.entity.life.Attempts.Kind.SENT_FROM, g.blueprint.entrance, com.claudemod.entity.life.Attempts.Reason.NO_PATH, "his mine's way in out of reach from here"); g.attempts.of(com.claudemod.entity.life.Attempts.Kind.WALK).remove(pos); g.tasks.addFirst(new Task.GoTo(pos, within)); com.claudemod.ClaudeMod.LOGGER.info("[Claude life] his mine's way in at {} is a long walk: going again, by the surface", pos.toShortString()); return true; } // (the user: path to the entrance; dropping the legs left only a dig straight down)
                int before = g.tasks.size(); g.tasks.removeIf(x -> x instanceof Task.GoTo n && g.blueprint.touches(n.pos()));
                com.claudemod.ClaudeMod.LOGGER.info("[Claude life] can't get to his mine's way in at {}: the {} legs down it dropped", g.blueprint.entrance.toShortString(), before - g.tasks.size());
            }
            if (!leg && !toABlock) { var w0 = g.attempts.why(com.claudemod.entity.life.Attempts.Kind.WALK, pos); if (com.claudemod.entity.life.Decide.walkRefused(g, (ServerLevel) g.level(), pos, within, w0 == null ? "no cause" : w0.reason() + (w0.detail().isEmpty() ? "" : " " + w0.detail()))) return true; } // (a refused walk is a conflict he decides: his other ways priced, Decide)
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
        long tod = g.level().getDayTime() % 24000; double toDawn = (tod < 23460 ? 23460 - tod : 24000 - tod + 23460) / 20.0;
        return com.claudemod.director.ClaudeLife.nightOutsideNo(g, (ServerLevel) g.level(), digInAt.above(Math.max(0, digInRise) + 3), toDawn) != null; // (the hold is "in here or out till dawn": the whole night is the trip priced, never one job's 30 s) // (judged above the cover: at a tower's foot the tower itself shades the cell, it read as "underground" and the hold never started)
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

    private boolean coverBeforeWalk(Task.GoTo walk) {
        if (surfacing || com.claudemod.entity.life.Watchdog.airRoute(g)) return false;
        var cover = coverTasks();
        for (Task step : cover) if (step == walk) return false;
        java.util.List<Task> pending = new java.util.ArrayList<>();
        for (Task step : cover) {
            var it = g.tasks.iterator();
            while (it.hasNext()) if (it.next() == step) { it.remove(); pending.add(step); break; }
        }
        if (pending.isEmpty()) return false;
        g.tasks.addFirst(walk);
        for (int i = pending.size() - 1; i >= 0; i--) g.tasks.addFirst(pending.get(i));
        com.claudemod.ClaudeMod.LOGGER.info("[Claude life] finish {} cover steps before the walk to {}: build, safety wait, then teardown (WorkGoal.coverBeforeWalk)", pending.size(), walk.pos().toShortString());
        return true;
    }

    /** His step is shelter and it's dusk or night: the cover is for the night, not for a mob, so nothing about is no reason out (health 5 at dusk: in, "nothing about: out after 2 s", the plan said shelter again, in and out, review 119, stage 1228). */
    private boolean nightShelter() { long tod = g.level().getDayTime() % 24000; return g.lastStep != null && g.lastStep.skill().equals("shelter") && tod >= 11500 && tod < 23500; }

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
        if (lastCaveLeg != null && lastCaveLeg.distSqr(at) > 3 * 3) failed(com.claudemod.entity.life.Attempts.Kind.WALK, lastCaveLeg, com.claudemod.entity.life.Attempts.Reason.UNKNOWN, "the cave leg ran and he's back here, not up on it"); // back here and not up on the last leg: that leg didn't work
        BlockPos best = null; double bestScore = 0; java.util.List<Object[]> caveLegs = new java.util.ArrayList<>();
        for (BlockPos q0 : BlockPos.betweenClosed(at.offset(-14, 1, -14), at.offset(14, 14, 14))) {
            if (q0.getY() <= at.getY() + 1 || q0.equals(lastCaveLeg)) continue;
            { Integer f0 = g.attempts.any.get(q0.immutable()); if (f0 != null && g.tickCount - f0 < 20 * 120) continue; } // a leg he failed to walk to lately: not again (two legs took turns for two minutes, neither reached, while a tree on flat ground waited)
            if (!level.getBlockState(q0).getCollisionShape(level, q0).isEmpty() || !level.getBlockState(q0.above()).getCollisionShape(level, q0.above()).isEmpty()) continue;
            if (!level.getBlockState(q0.below()).isFaceSturdy(level, q0.below(), Direction.UP) || !level.getFluidState(q0).isEmpty()) continue;
            double score = (q0.getY() - at.getY()) * 4.0 - Math.sqrt(q0.distSqr(at)) * 0.5 - Math.sqrt(q0.distSqr(dest)) * 0.05; // up above all, then near, then toward it
            if (score <= bestScore) continue;
            caveLegs.add(new Object[] {score, q0.immutable()});
        }
        caveLegs.sort((x, y) -> Double.compare((double) y[0], (double) x[0])); // best first, a path tried for the top 8 only (every open ledge got a full pathfind: hundreds a call when none was reachable)
        for (int i = 0; i < caveLegs.size() && i < 8; i++) { var path = g.getNavigation().createPath((BlockPos) caveLegs.get(i)[1], 0); if (path != null && path.canReach()) { best = (BlockPos) caveLegs.get(i)[1]; bestScore = (double) caveLegs.get(i)[0]; break; } }
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
        { if (g.attempts.skipped(com.claudemod.entity.life.Attempts.Kind.STEP_UP, feet, g.tickCount, 20 * 120)) return null; } // steps up from here didn't take a moment ago: another way
        if (com.claudemod.director.ClaudeLife.bpMode(g) && g.blueprint != null && !g.blueprint.isEmpty() && dest.getY() > feet.getY() + 1) { // blueprint mode: up and out is along his mine's rows (the user: to reach the surface he goes up his staircase), never a second flight cut beside it
            var b = g.blueprint; int from = b.nearestRow(feet), to = b.nearestRow(dest);
            BlockPos fromCell = b.rowCell(from);
            Integer sentFromHere = g.attempts.last(com.claudemod.entity.life.Attempts.Kind.SENT_FROM, feet);
            if (fromCell != null && to >= 0 && from != to && fromCell.distSqr(feet) <= 8 * 8 && !g.attempts.skipped(com.claudemod.entity.life.Attempts.Kind.SENT_FROM, feet, g.tickCount, 20 * 90)) { // in or beside the mine (a bedroom pocket off the stairs counts), and not sent this way from this spot a minute ago
                failed(com.claudemod.entity.life.Attempts.Kind.SENT_FROM, feet, com.claudemod.entity.life.Attempts.Reason.GUARD, "sent to his stairs from here: back here means that way up doesn't work from here");
                g.tasks.removeIf(t -> t instanceof Task.GoTo gg && gg.pos().equals(dest));
                g.tasks.addFirst(new Task.GoTo(dest, within));
                java.util.List<BlockPos> cells = b.walkCells(from, to);
                if (cells.size() > 6 && feet.distSqr(dest) <= 16 * 16 && cells.size() > 2 + Math.sqrt(feet.distSqr(dest)) / 2) { /* (only a spot close by: the surface 66 blocks up from his y-16 tunnel IS a walk up his stairs; refused, every walk out ended at once and he sat in his mine) */ g.tasks.removeIf(t -> t instanceof Task.GoTo gg && gg.pos().equals(dest)); failed(com.claudemod.entity.life.Attempts.Kind.WALK, dest, com.claudemod.entity.life.Attempts.Reason.GUARD, "no flight cut beside his stairs: they are the way up"); com.claudemod.ClaudeMod.LOGGER.info("[Claude life] no way up from {} to {} ({} blocks off) but by {} hops round his mine: leaving it", feet.toShortString(), dest.toShortString(), (int) Math.sqrt(feet.distSqr(dest)), cells.size()); return true; } // a spot 2 blocks off is never worth a trip round the whole mine (rows 450 to 88 sent him from y16 down to y-58 and back for a plug above his branch)
                for (int k = cells.size() - 1; k >= 0; k--) g.tasks.addFirst(new Task.GoTo(cells.get(k), 0.8));
                com.claudemod.ClaudeMod.LOGGER.info("[Claude life] no way up from {}: along his mine's rows {} to {} ({} hops) and on to {}, not cutting new steps", feet.toShortString(), from, to, cells.size(), dest.toShortString());
                return true;
            }
            if (fromCell != null && fromCell.distSqr(feet) <= 8 * 8) { // beside his mine and the rows don't help (sent that way a minute ago): no one-wide flight cut up through the rock beside his stairs, ever (the user's zigzag)
                failed(com.claudemod.entity.life.Attempts.Kind.STEP_UP, feet, com.claudemod.entity.life.Attempts.Reason.GUARD, "beside his mine: no flight cut next to his stairs");
                com.claudemod.ClaudeMod.LOGGER.info("[Claude life] no way up from {} beside his mine: not cutting a flight next to his stairs; that way is off for now", feet.toShortString());
                return null;
            }
        }
        if (g.stairStart != null && g.mineSpine.size() >= 4 && dest.getY() > feet.getY() + 3 && dest.getY() >= g.stairStart.getY() - 6) { // the way up is his staircase (the user: "to reach the surface he has to go up his staircase"): over to it and up it, never a new flight cut through the rock
            BlockPos near = null; double nd = 24 * 24;
            for (BlockPos sp : g.mineSpine) { double d = sp.distSqr(feet); if (d < nd && level.getBlockState(sp).getCollisionShape(level, sp).isEmpty()) { nd = d; near = sp; } }
            Integer failed = g.attempts.of(com.claudemod.entity.life.Attempts.Kind.WALK).get(g.stairStart);
            Integer sentFromHere = g.attempts.last(com.claudemod.entity.life.Attempts.Kind.SENT_FROM, feet);
            if (g.attempts.skipped(com.claudemod.entity.life.Attempts.Kind.SENT_FROM, feet, g.tickCount, 20 * 90)) near = null; // sent to his stairs from this very spot a minute ago and back here: that way up doesn't work (a broken join in his stairs record), so not again (it looped every 12 s)
            if (near != null && nd > 2 * 2 && !g.attempts.skipped(com.claudemod.entity.life.Attempts.Kind.WALK, g.stairStart, g.tickCount, 20 * 120)) {
                failed(com.claudemod.entity.life.Attempts.Kind.SENT_FROM, feet, com.claudemod.entity.life.Attempts.Reason.GUARD, "sent to his stairs from here: back here means that way up doesn't work from here");
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
            if (com.claudemod.director.ClaudeLife.nookShell(g, b)) { com.claudemod.ClaudeMod.LOGGER.info("[Claude life] no step up from {} toward {}: {} is his nook's shell (its wall or roof): not cut for a step (WorkGoal.digStepUp)", feet.toShortString(), dest.toShortString(), b.toShortString()); failed(com.claudemod.entity.life.Attempts.Kind.STEP_UP, feet, com.claudemod.entity.life.Attempts.Reason.GUARD, "his nook's shell in the way of a step"); return null; }
            BlockState bs = level.getBlockState(b);
            if (!bs.getFluidState().isEmpty()) return null; // water or lava up there: not this way
            if (bs.getCollisionShape(level, b).isEmpty()) continue;
            if (bs.getDestroySpeed(level, b) < 0 || bs.hasBlockEntity()) return null;
            if (com.claudemod.entity.nav.ActionPath.structureBlock(level, b) && !com.claudemod.entity.nav.ActionPath.structureDigOk(level, b) && !com.claudemod.entity.life.TempLedger.built(g, b)) { // the intact rule refuses it in doBreak anyway (live 01:06, a step cut into the stronghold's wall 3x every 4 s)
                com.claudemod.ClaudeMod.LOGGER.info("[Claude life] no step up from {} toward {}: {} is a structure's own block, kept intact (WorkGoal.digStepUp)", feet.toShortString(), dest.toShortString(), b.toShortString());
                failed(com.claudemod.entity.life.Attempts.Kind.STEP_UP, feet, com.claudemod.entity.life.Attempts.Reason.GUARD, "a structure's own block in the way, kept intact"); return null; }
            if (bs.requiresCorrectToolForDrops() && g.inventory.find(st -> st.isCorrectToolForDrops(bs)).isEmpty()) return null; // no tool that takes it (stone with bare hands: 23 s a step, nothing dropping, up a cliff after a death): not this way
            dig.add(b);
        }
        BlockPos floor = step.below();
        boolean needFloor = !level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP);
        if (!dig.isEmpty() || needFloor) {
            // ponytail: keyed far above the world in placeRetries (no new fields live); a cut that never sticks (water refilling it) gives up after 3, not 20 tries in a minute
            BlockPos cutKey = feet.above(10000).immutable();
            boolean gravelRefill = !dig.isEmpty() && dig.stream().allMatch(b -> level.getBlockState(b).getBlock() instanceof net.minecraft.world.level.block.FallingBlock); // a gravel column coming down a block at a time is progress, not a cut that won't take
            if (!gravelRefill && placeRetries.merge(cutKey, 1, Integer::sum) > 3) {
                placeRetries.remove(cutKey);
                failed(com.claudemod.entity.life.Attempts.Kind.WALK, dest, com.claudemod.entity.life.Attempts.Reason.UNKNOWN, "the steps cut up toward it didn't take him up");
                failed(com.claudemod.entity.life.Attempts.Kind.STEP_UP, feet, com.claudemod.entity.life.Attempts.Reason.NO_PATH, "steps cut up from here didn't take him up"); // (and no step up from HERE for a while, whatever the destination: the next walk retried the same three cuts, 2 minutes in one spot)
                com.claudemod.ClaudeMod.LOGGER.info("[Claude life] cut a step up at {} three times and it didn't take: {} is off for now", feet.toShortString(), dest.toShortString());
                return null;
            }
            for (BlockPos b : dig) if (level.getBlockState(b).is(net.minecraft.tags.BlockTags.GUARDED_BY_PIGLINS) && !com.claudemod.entity.life.Perception.known(g, net.minecraft.world.entity.monster.piglin.Piglin.class, g.getBoundingBox().inflate(16)).isEmpty()) // (review-45: the planned routes price this; the step cut is the last way up, so it's said, not refused)
                com.claudemod.ClaudeMod.LOGGER.info("[Claude life] the step cut up at {} breaks {} at {} with piglins in sight: they'll turn on him (WorkGoal.digStepUp)", feet.toShortString(), name(level.getBlockState(b)), b.toShortString());
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
        // all cut, but gravel over the step still coming down: wait beside it, never step in under it (04:01 Nov 4: he stepped up under falling gravel and suffocated)
        BlockPos falling = gravelComing(level, step);
        if (falling != null) {
            if (g.tickCount % 20 == 0) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] step up at {} waits: gravel at {} still coming down into it (WorkGoal.digStepUp)", step.toShortString(), falling.toShortString());
            g.getNavigation().stop();
            return false;
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

    /** A player under falling gravel steps out from under it: any column his body overlaps (feet or one up, mid-jump) with gravel coming, to the nearest clear cell. */
    private boolean gravelOverHim() {
        ServerLevel level = (ServerLevel) g.level(); var box = g.getBoundingBox(); int fy = net.minecraft.util.Mth.floor(g.getY() + 0.01);
        BlockPos danger = null;
        for (double x : new double[] {box.minX, box.maxX}) for (double z : new double[] {box.minZ, box.maxZ}) {
            BlockPos col = BlockPos.containing(x, fy, z);
            BlockPos f = gravelComing(level, col.below()); if (f == null) f = gravelComing(level, col);
            if (f != null && f.getY() <= fy + 4) { danger = f; break; }
        }
        if (danger == null) return false;
        BlockPos feet = BlockPos.containing(g.getX(), fy, g.getZ()), out = null;
        for (BlockPos c : new BlockPos[] {feet, feet.north(), feet.south(), feet.east(), feet.west()})
            if (gravelComing(level, c.below()) == null && gravelComing(level, c) == null && level.getBlockState(c).getCollisionShape(level, c).isEmpty() && level.getBlockState(c.above()).getCollisionShape(level, c.above()).isEmpty() && !level.getBlockState(c.below()).getCollisionShape(level, c.below()).isEmpty()) { out = c; break; }
        g.getNavigation().stop();
        if (out != null) g.getMoveControl().setWantedPosition(out.getX() + 0.5, out.getY(), out.getZ() + 0.5, 1.0);
        if (g.tickCount % 20 == 0) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] gravel at {} coming down by him at {}: {} (WorkGoal.gravelOverHim)", danger.toShortString(), g.blockPosition().toShortString(), out == null ? "nowhere clear to step, holding still" : "stepping to " + out.toShortString());
        return true;
    }

    /** Gravel or sand over the step's head room that hasn't settled: a falling block in the column, or one resting on air. Null when it's safe to step in. */
    public static BlockPos gravelComing(ServerLevel level, BlockPos step) {
        var box = new net.minecraft.world.phys.AABB(step).expandTowards(0, 6, 0);
        for (var e : level.getEntitiesOfClass(net.minecraft.world.entity.item.FallingBlockEntity.class, box)) return e.blockPosition();
        for (int k = 1; k <= 6; k++) {
            BlockPos p = step.above(k);
            if (!(level.getBlockState(p).getBlock() instanceof net.minecraft.world.level.block.FallingBlock)) { if (!level.getBlockState(p).getCollisionShape(level, p).isEmpty()) return null; continue; }
            if (level.getBlockState(p.below()).getCollisionShape(level, p.below()).isEmpty() && level.getBlockTicks().hasScheduledTick(p, level.getBlockState(p).getBlock())) return p; // on air with its fall tick due: coming down. World-gen gravel over air has no tick and never falls (live 14:05 11-04: 2.5 min waiting under one)
        }
        return null;
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

    /** doBreak keeps it as his build (a station, his pit cap): off his tidy lists, so nothing queues it again (09:15 TempLedger.tidy, tidyLitter and PitGuard.cleanup re-queued his crafting table and the door-pit cap every 5 s, each refused). */
    private void keptAsBuild(BlockPos pos) {
        if (com.claudemod.entity.life.TempLedger.book(g).remove(pos) != null | g.pillarBlocks.remove(pos))
            com.claudemod.ClaudeMod.LOGGER.info("[Claude temp] {} kept as his build: off his tidy lists (WorkGoal.keptAsBuild)", pos.toShortString());
    }

    /** How far (eyes to a block's centre) he breaks from. Every picker of a block to break uses this, or doBreak refuses what it picked (the obsidian loop, 13:05). */
    public static final double BREAK_REACH = 4.25;

    /** A block between him and a station that he digs like a player: his pick (or the right tool) takes it, and it's nobody's build, shell, house or structure (live 06:29 11-08: stone over his furnace, 3 min nudging, review 130, stage 989). */
    private boolean digsInTheWay(ServerLevel level, BlockPos p, BlockState s) {
        if (s.requiresCorrectToolForDrops() && g.inventory.find(it -> it.isCorrectToolForDrops(s)).isEmpty()) return false;
        if (com.claudemod.entity.life.TempLedger.built(g, p) || com.claudemod.director.ClaudeLife.nookShell(g, p) || com.claudemod.entity.nav.ActionPath.structureBlock(level, p)) return false;
        if (com.claudemod.director.ClaudeLife.bpMode(g) && g.blueprint != null && g.blueprint.isShell(p) || g.house != null && g.house.isStructure(p)) return false;
        return true;
    }
    /** A cell beside him at his level to stand in and mine the block under his feet from: two open cells, a floor that isn't that block, no lava or fire there; null when there's none (stranded on it). */
    private BlockPos sideStand(ServerLevel level, BlockPos under) {
        BlockPos me = under.above(); BlockPos best = null;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) { if (dx == 0 && dz == 0) continue; BlockPos q = me.offset(dx, 0, dz);
            if (!com.claudemod.entity.life.FortressFight.stand(level, q) || level.getBlockState(q.below()).is(net.minecraft.world.level.block.Blocks.LAVA) || q.below().equals(under)) continue;
            if (dx != 0 && dz != 0 && !(level.getBlockState(me.offset(dx, 0, 0)).getCollisionShape(level, me.offset(dx, 0, 0)).isEmpty() || level.getBlockState(me.offset(0, 0, dz)).getCollisionShape(level, me.offset(0, 0, dz)).isEmpty())) continue; // (a diagonal he can't squeeze to)
            if (best == null || (dx == 0 || dz == 0)) { best = q.immutable(); if (dx == 0 || dz == 0) return best; } }
        return best;
    }
    public boolean inReachOf(BlockPos pos) { return inReach(pos); }
    /** doBreak's lava guard on a block: lava he knows of beside it (not under it) that no block of his plugs from this side. */
    public boolean lavaHolds(ServerLevel level, BlockPos pos) { for (Direction d : Direction.values()) { if (d == Direction.DOWN) continue; BlockPos a = pos.relative(d); if (level.getFluidState(a).is(net.minecraft.tags.FluidTags.LAVA) && com.claudemod.entity.life.Perception.knowsLavaBody(g, level, a) && plugFace(level, pos, a) == null) return true; } return false; }
    private boolean inReach(BlockPos pos) {
        Vec3 eye = g.getEyePosition(); // a player's reach: a face of it he sees, its point inside 4.5 (block_interaction_range), aimed at as blockInTheWay aims; 4.25 to the middle left a floating log 4.37 off, a face 3.8 off, "can't get at it" (reviews 199/200, stage 988)
        if (eye.distanceTo(Vec3.atCenterOf(pos)) > w("reach.block", 4.45) + 0.87) return false; // (no face of it within reach: no rays; a ray to a block far off stepped every cell and loaded every chunk on the way, the test server hung, stages 548, 781)
        for (Vec3 aim : aims(Vec3.atCenterOf(pos))) { var hit = g.level().clip(new net.minecraft.world.level.ClipContext(eye, aim, net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.NONE, g));
            if (hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK && hit.getBlockPos().equals(pos) && eye.distanceTo(hit.getLocation()) <= w("reach.block", 4.45)) return true; }
        return eye.distanceTo(Vec3.atCenterOf(pos)) <= BREAK_REACH; // 4.25 to the centre: every face he clicks stays inside the 4.75 his hands allow (4.0 left a mine-wall hole "out of reach" that a player reaches with a step)
    }

    private int approachTries;
    private BlockPos lastPillarFor;
    private static int pillarMissLog = -100; // (static: fields added live start null)
    private static java.util.Set<BlockPos> liftFor; // his own table reachFor sent a Break for: doBreak takes it up (static: fields added live start null)
    private static java.util.Set<BlockPos> liftFor() { if (liftFor == null) liftFor = new java.util.HashSet<>(); return liftFor; }

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
        boolean bodyRefused = com.claudemod.entity.body.Controls.refusedLately(g, 10); // his body refused the vanilla path's step (2+ up, lava): it doesn't really reach, the planned way takes it (09:16 in a 2-deep hole at -9,75,9 he stood 10 s per block under a step he can't jump)
        if (bodyRefused && !g.getNavigation().isDone()) g.getNavigation().stop();
        if (!g.getNavigation().isDone()) {
            lookWhereWalking(pos);
            return false;
        }
        if (ticks % 6 != 1) return false; // re-think a few times a second, not every tick (pathfinding is costly)
        if (++approachTries > 3) { { var c0 = com.claudemod.entity.life.Stall.diagnose(g, pos, 3.2); g.attempts.fail(com.claudemod.entity.life.Attempts.Kind.REACH, pos, g.tickCount, g.blockPosition(), c0.reason(), c0.detail(), c0.mob()); g.attempts.bandaid("the approach gave up after 3 re-plans at " + pos.toShortString() + ": " + c0.say()); } com.claudemod.ClaudeMod.LOGGER.info("[Claude life] gave up reaching {} (he's at {})", pos.toShortString(), g.blockPosition().toShortString()); return true; }
        BlockPos spot = standSpotBeside(pos); // never path "into" a solid block (vanilla would aim at the air above it)
        var path = g.getNavigation().createPath(spot, spot.equals(pos) ? (int) REACH - 1 : 1);
        if (path != null && path.canReach() && !bodyRefused) {
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
        BlockPos planKey = pos.below(500).immutable(), fromKey = pos.below(1000).immutable(); // (its own keys: a walk plan to the same cell, 3 s throttle, made this skip the plan and give up "no way to it" untried: stage 97, 1 run in 3)
        if (g.tickCount - plannedAt().getOrDefault(planKey, -100000) > 60 || !Integer.valueOf(feet.hashCode()).equals(plannedAt().get(fromKey))) { // (once per 3 s from one spot; from a new spot, at once)
            if (plannedAt().size() > 64) plannedAt().clear(); plannedAt().put(planKey, g.tickCount); plannedAt().put(fromKey, feet.hashCode());
            long t0 = System.nanoTime();
            ItemStack scaff = solidScaffold();
            var plan = com.claudemod.entity.nav.ActionPath.plan(g, level, feet, pos, 3.2, !scaff.isEmpty(), 3000, com.claudemod.entity.nav.ActionPath.Mode.MAY_DIG, g.attempts.of(com.claudemod.entity.life.Attempts.Kind.WALK), g.tickCount);
            if (plan != null) {
                var tasks = com.claudemod.entity.nav.ActionPath.tasks(plan, feet, scaff.isEmpty() ? net.minecraft.world.item.Items.COBBLESTONE : scaff.getItem(), pos, 3.2, level);
                if (plan.puts() > 0) { for (var a : plan.acts()) if (a instanceof com.claudemod.entity.nav.ActionPath.Act.Put pt) g.pillarBlocks.addFirst(pt.pos().immutable()); g.tasks.addFirst(new Task.Pillar(-1)); } // every block the way puts down is his to take back (a tower under a block overhead comes down once it's out; the Pillar(-1) is kept while a Break in reach is queued)
                g.tasks.addFirst(new Task.Break(pos));
                queueWay(tasks, "WorkGoal.approach");
                com.claudemod.ClaudeMod.LOGGER.info("[Claude life] no way to the {} at {} on foot: made one ({} digs, {} blocks put down, {} walks; {} ms)", name(level.getBlockState(pos)), pos.toShortString(), plan.digs(), plan.puts(), plan.walks(), (System.nanoTime() - t0) / 1000000);
                return true;
            }
        }
        failed(com.claudemod.entity.life.Attempts.Kind.REACH, pos, com.claudemod.entity.life.Attempts.Reason.NO_PATH, "no way to it, digging allowed");
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
        if (again && g.skillQueue.stream().noneMatch(s -> s.skill().equals("recover_left")) && (g.lastStep == null || !g.lastStep.skill().equals("recover_left"))) { // a drop despawns in 5 min: back for it now, the job's own tasks after (live 16:30:53 11-07: queued behind 257 strip-mine tasks, never ran, a diamond lost at -344,-55,-38)
            var held = new java.util.ArrayList<>(g.tasks); g.tasks.clear();
            String r = com.claudemod.entity.life.OwnEvents.recover(g, (ServerLevel) g.level(), new com.claudemod.entity.life.Skills.Step("recover_left", id, it.getItem().getCount()), id);
            g.tasks.addAll(held);
            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] {} at {}: it goes in 5 min, so back for it now ({}), the job's {} task(s) after (WorkGoal.leaveOwn)", it.getItem().getHoverName().getString(), it.blockPosition().toShortString(), r, held.size());
        }
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
            for (int k = 2; up > 0 && k <= up + 1; k++) { BlockPos hd = feet.above(k); if (!level.getBlockState(hd).getCollisionShape(level, hd).isEmpty() && !treeBlockInWay(hd)) { com.claudemod.ClaudeMod.LOGGER.info("[Claude life] in a hole under his tunnel floor at {}: no climb up {}, {} at {} over his head (not dug yet): the way on is the dig toward what he's after (WorkGoal.digThroughToward)", feet.toShortString(), up, name(level.getBlockState(hd)), hd.toShortString()); up = 0; } } // (live 20:29:56: the climb into an undug walk cell, doPillar "no head room", queued again every tick)
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
        if (!s.is(net.minecraft.world.item.Items.GRAVEL) && !s.is(net.minecraft.world.item.Items.SAND)) return s;
        // it falls where nothing holds it: the wood past what he keeps back, as ActionPath.moveBlocks counts it (gravel picked for the made way with 48 logs on him, doPlace refused it over air, the way made again every second, ~2 min, live 21:37 11-09, stage 1283)
        if (g.inventory.count(x -> x.is(net.minecraft.tags.ItemTags.PLANKS)) > com.claudemod.entity.life.Weights.w("scaffold.keep_planks", 8)) return g.inventory.find(x -> x.is(net.minecraft.tags.ItemTags.PLANKS));
        if (g.inventory.count(x -> x.is(net.minecraft.tags.ItemTags.LOGS)) > com.claudemod.entity.life.Weights.w("scaffold.keep_logs", 4)) return g.inventory.find(x -> x.is(net.minecraft.tags.ItemTags.LOGS));
        return ItemStack.EMPTY;
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
        s0 = biggestStack(s -> s.is(net.minecraft.world.item.Items.SOUL_SAND) || s.is(net.minecraft.world.item.Items.SOUL_SOIL) || s.is(net.minecraft.world.item.Items.BASALT) || s.is(net.minecraft.world.item.Items.SMOOTH_BASALT) || s.is(net.minecraft.world.item.Items.NETHER_WART_BLOCK) || s.is(net.minecraft.world.item.Items.WARPED_WART_BLOCK) || s.is(net.minecraft.world.item.Items.END_STONE)); // the Nether's other solid blocks: they hold where gravel falls (12 soul sand on him, gravel chosen, live 21:37 11-09, stage 1283)
        if (!s0.isEmpty()) return s0;
        // gravel or sand: junk, but it stands on a block under him (a player in a pit with nothing else towers up on it)
        s0 = g.inventory.find(s -> s.is(net.minecraft.world.item.Items.GRAVEL) || s.is(net.minecraft.world.item.Items.SAND));
        if (!s0.isEmpty()) return s0;
        // nothing else on him: planks will do to tower up (keeping 8 back for tools and a table)
        if (g.inventory.count(s -> s.is(net.minecraft.tags.ItemTags.PLANKS)) > com.claudemod.entity.life.Weights.w("scaffold.keep_planks", 8)) return g.inventory.find(s -> s.is(net.minecraft.tags.ItemTags.PLANKS));
        if (g.inventory.count(s -> s.is(net.minecraft.tags.ItemTags.LOGS)) > com.claudemod.entity.life.Weights.w("scaffold.keep_logs", 4)) return g.inventory.find(s -> s.is(net.minecraft.tags.ItemTags.LOGS)); // logs tower just as well (16 logs on him and "nothing to build with" sent him running to the player's mob camp)
        return ItemStack.EMPTY;
    }

    /**
     * Bridging like a player: one step toward target across a gap (ravine, water, a drop), placing a block
     * under the next step when there's nothing to stand on. Returns false when bridging can't help
     * (a wall in the way, too far up or down, no blocks, or he's bridged enough for this task).
     */
    private boolean bridgeToward(Vec3 target, double worth) { // worth: seconds of his time the thing over there is worth (a drop's value, a chase)
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
        if (overLava) for (var m : level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class, g.getBoundingBox().inflate(48), m -> m.isAlive() && (m instanceof net.minecraft.world.entity.monster.Blaze || m instanceof net.minecraft.world.entity.monster.Ghast || shoots(m)) && g.hasLineOfSight(m))) { shooter = m; break; }
        double risk = shooter == null ? 0 : 3.0 * Math.max(1, to.horizontalDistance()); // a knock off is the fall into the lava (World 15 13:41): its odds per block of bridge in a shooter's sight, priced (the user 11-05: a cost, not a ban)
        if (shooter != null && risk > worth) {
            g.setShiftKeyDown(false);
            if (g.tickCount - lavaBridgeSaid > 40) { lavaBridgeSaid = g.tickCount; com.claudemod.ClaudeMod.LOGGER.info("[Claude life] not bridging out over the lava toward {}: a {} {} off has him in sight; the knock's risk (~{} s) is more than it's worth (~{} s) (WorkGoal.bridgeToward)", BlockPos.containing(target).toShortString(), shooter.getType().getDescription().getString(), (int) shooter.distanceTo(g), (int) risk, (int) worth); }
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
        if (com.claudemod.director.ClaudeLife.bridgeBlocks().contains(pos) && g.getBoundingBox().move(0, -0.1, 0).intersects(new net.minecraft.world.phys.AABB(pos)) && level.getBlockState(pos.below()).getCollisionShape(level, pos.below()).isEmpty()) return exit("a block he put down to climb by, still under him (the walk off it ended at once): never cut from under his own (doBreak 1445)"); // a block he put down to climb by, still under him (the walk off it ended at once): never cut from under his own feet over a drop; it stays on the take-down list (a pillar's top with its next block under it is the take-down itself, one block down like a player: the guard refused it 3x a second at 4,86,2, live 23:32)
        if (com.claudemod.director.ClaudeLife.bridgeBlocks().contains(pos) && com.claudemod.entity.life.TempLedger.dirtHillStep(g, level, pos)) { com.claudemod.director.ClaudeLife.bridgeBlocks().remove(pos); com.claudemod.ClaudeMod.LOGGER.info("[Claude temp] not taking back {}: dirt, a step into the hill, may stay (his way planned another block for it) (WorkGoal.doBreak)", pos.toShortString()); return exit("a dirt step into the hill stays (doBreak)"); } // (the way booked the take-back by the block it planned, cobblestone under a roof; the step went in as dirt under the sky: up a second later, put in again each pass, review 454, stage 1286)
        if (ticks == 1 && com.claudemod.director.ClaudeLife.bridgeBlocks().contains(pos)) com.claudemod.ClaudeMod.LOGGER.info("[Claude temp] taking back {}: he is at {} {} {} onGround {} box {} (WorkGoal.doBreak)", pos.toShortString(), String.format("%.2f", g.getX()), String.format("%.2f", g.getY()), String.format("%.2f", g.getZ()), g.onGround(), g.getBoundingBox());
        if (com.claudemod.director.ClaudeLife.bridgeBlocks().contains(pos) && level.getBlockState(pos).isAir()) { com.claudemod.director.ClaudeLife.bridgeBlocks().remove(pos); return exit("doBreak line 1446"); }
        BlockState state = level.getBlockState(pos);
        if (!state.isAir() && com.claudemod.entity.nav.ActionPath.structureBlock(level, pos) && !com.claudemod.entity.nav.ActionPath.structureDigOk(level, pos) && !com.claudemod.entity.life.TempLedger.built(g, pos) && com.claudemod.entity.nav.ActionPath.keepStructure(g, level, pos, state, ticks == 1) && !(g.lastStep != null && g.lastStep.skill().equals("light_end_portal")) /* (the portal-room skill's own breaks: its one way in, a stuck door; the roof brick under him was refused 3x a second, live 00:57) */ && !com.claudemod.entity.life.Consent.theirWord(g, pos, "structure", "a structure's own block", null)) { // the user, 17:08: "generally you wanna keep the nether fortress intact" (16:42-16:45 the fortress lost 11 nether bricks to his walks and digs, none of them logged): only a planned way with no way round breaks one (and puts it back), or their own dig_at
            if (ticks == 1) { com.claudemod.ClaudeMod.LOGGER.info("[Claude dig] not breaking the {} at {}: a structure's own block, kept intact (only a planned way with no way round breaks one, and puts it back) (WorkGoal.doBreak, queued by {})", net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath(), pos.toShortString(), com.claudemod.entity.ClaudeEntity.originOf(current)); com.claudemod.entity.life.MindLog.refused(g, pos, "breaking a structure block [kept intact] queued by " + com.claudemod.entity.ClaudeEntity.originOf(current)); }
            return exit("a structure's own block, kept intact (doBreak)");
        }
        if (com.claudemod.entity.life.PitGuard.isCap(g, pos) && !state.isAir() && state.getFluidState().isEmpty() && !com.claudemod.entity.life.Consent.theirWord(g, pos, "pit cap", "the mouth of the hole by his door he closed", null)) { // the hole by his door he closed (and the fill under it): his build
            com.claudemod.director.ClaudeLife.bridgeBlocks().remove(pos);
            if (ticks == 1) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] not breaking {}: the mouth of the hole by his door he closed (or its fill), his build (WorkGoal.doBreak, queued by {})", pos.toShortString(), com.claudemod.entity.ClaudeEntity.originOf(current));
            keptAsBuild(pos);
            return exit("his cap stays (doBreak)");
        }
        { String kept0 = com.claudemod.entity.life.Consent.keptBlock(g, pos); if (kept0 != null && !state.isAir()) { if (ticks == 1) com.claudemod.ClaudeMod.LOGGER.info("[Claude consent] not breaking {} at {}: {} (WorkGoal.doBreak, queued by {})", name(state), pos.toShortString(), kept0, com.claudemod.entity.ClaudeEntity.originOf(current)); return exit("their block stays (Consent.keptBlock)"); } } // (what their word had him put there: no take-back, no tidy, no other job's dig)
        float hardness = state.getDestroySpeed(level, pos);
        if (ticks == 1 && state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_PICKAXE) && !inReach(pos) && com.claudemod.director.ClaudeLife.tableInReach(g, level) != null) { equipBestTool(state); equippedFor = pos.immutable(); } // at a table (he walked over for the tool): made here, before the walk back to the block
        if (TRACE && ticks == 1 && state.is(net.minecraft.tags.BlockTags.LOGS)) com.claudemod.ClaudeMod.LOGGER.info("[BTRACE] break {} from {} y={} reach={} next={}", pos.toShortString(), g.blockPosition().toShortString(), String.format("%.2f", g.getY()), inReach(pos), g.tasks.peek());
        if (state.isAir() && ticks < 40 && gravelComing(level, pos) != null) return false; // gravel coming down into the cell: it lands, then it's mined (ended on the empty tick, the cell filled behind him and the way ran on against gravel: live 22:18-22:38 11-09, stage 1284)
        if (state.isAir() || hardness < 0 || state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) return exit("nothing to mine there, " + (state.isAir() ? "air" : name(state)) + " where his block was (doBreak 1450)"); // water where his block was (a pillar in a pond): nothing to mine (45 s swinging at a water cell)
        boolean inWaterBeside = false; for (Direction d0 : Direction.Plane.HORIZONTAL) { var wf0 = level.getFluidState(pos.relative(d0)); if (wf0.is(net.minecraft.tags.FluidTags.WATER)) inWaterBeside = true; } if (inWaterBeside && pos.equals(g.blockPosition().below())) inWaterBeside = false; BlockPos liftedLava = null; // (the block under his feet: the water beside it doesn't make it safe, the hold asks Consent; live 15:59 "i told you not to mine the ones below you") (their word over the hold: Consent.theirWord below, not any order of theirs; the user 15:41: instructing him, he listens to them over his safety rules) // (any water source beside it at its level runs in, not only the one he stands in: 15:35 the pocket's obsidian held as "the face left standing") // (the user's pocket: his water runs into the hole and makes the lava behind it obsidian)
        if (!inWaterBeside && !com.claudemod.director.ClaudeLife.isCastBlock(pos) && !com.claudemod.director.ClaudeLife.obsOpening(g, pos)) for (Direction d : Direction.values()) if (level.getFluidState(pos.relative(d)).is(net.minecraft.tags.FluidTags.LAVA) && (d != Direction.DOWN || standsOn(pos)) && com.claudemod.entity.life.Perception.knowsLavaBody(g, level, pos.relative(d)) && plugFace(level, pos, pos.relative(d)) == null) { /* (lava under it never comes up into the cell: only the block under his own feet holds it back from him; live 04:12 11-09, review 198, stage 1232) */ if (com.claudemod.entity.life.Consent.theirWord(g, pos, com.claudemod.entity.life.Perception.knowsLava(g, pos.relative(d)) ? "lava behind" : "lava body", (d == Direction.UP ? "lava over it at " : d == Direction.DOWN ? "lava under it at " : "lava behind it at ") + pos.relative(d).toShortString(), pos.relative(d))) { liftedLava = pos.relative(d).immutable(); break; } if (ticks == 1 && !com.claudemod.entity.life.Perception.knowsLava(g, pos.relative(d))) com.claudemod.ClaudeMod.LOGGER.info("[Claude sense] {} behind {} is part of the pool he knows (never seen itself): holding (WorkGoal.doBreak)", pos.relative(d).toShortString(), pos.toShortString()); // it holds back lava he knows is there: never broken (lava he can't know of is met as it comes, below) (his mine's patch-and-dig broke the blocks in front of a lava flow twice and it ran onto him: two deaths at 220, -58, 64)
            failed(com.claudemod.entity.life.Attempts.Kind.BREAK, pos, com.claudemod.entity.life.Attempts.Reason.GUARD, "lava he knows of beside it");
            if (ticks == 1) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] not breaking {}: lava behind it at {}", pos.toShortString(), pos.relative(d).toShortString());
            if (ticks == 1) com.claudemod.director.ClaudeLife.lavaBehind(g, pos, pos.relative(d)); // (the goal wants lava: this face is where it is)
            return exit("doBreak line 1455");
        }
        if (liftedLava != null) { BlockPos stand0 = com.claudemod.entity.life.Consent.safeStand(g, level, pos, liftedLava); if (stand0 == com.claudemod.entity.life.Consent.REFUSE) { failed(com.claudemod.entity.life.Attempts.Kind.BREAK, pos, com.claudemod.entity.life.Attempts.Reason.GUARD, "the block under his feet by lava"); return exit("the block under his feet by lava: never dug from on top (Consent.safeStand)"); } if (stand0 != null) { g.tasks.addFirst(new Task.Break(pos.immutable())); g.tasks.addFirst(new Task.GoTo(stand0, 0.3)); return exit("their word over the lava hold: to the safe stand first (Consent.safeStand)"); } } // (the same step the safe way: beside or above, never under it)
        if (pos.equals(g.blockPosition().below()) && g.onGround() && !g.isInWater() && !com.claudemod.director.ClaudeLife.isCastBlock(pos) && !(digInTasks != null && digInTasks.stream().anyMatch(t -> t == current))) { // (his cast's pillar comes down under his feet by design) // the block under his own feet, with a drop beneath it (the dig-in hole is three down on purpose and knows the ground: exempt): not from up here (the last floating log of a tree, dug out from under him: a 5-block fall, every tree test since falls started to hurt)
            if (!com.claudemod.entity.life.TempLedger.built(g, pos) && !state.is(net.minecraft.tags.BlockTags.LOGS) && !state.is(net.minecraft.tags.BlockTags.LEAVES)) { BlockPos side = sideStand(level, pos); // the ground he stands on, natural: what's under it he hasn't seen (a cave, lava), so from a side cell like a player, not from on top (live 06:40 11-09, review 358: iron under his feet, 4 down into a cave, an 8-block shaft back up; stage 1301, lava 1302)
                if (side != null) { g.tasks.addFirst(new Task.Break(pos.immutable())); g.tasks.addFirst(new Task.GoTo(side, 0.3)); com.claudemod.ClaudeMod.LOGGER.info("[Claude dig] {} at {} is under his feet: from {} beside it, not from on top (what's under it unseen) (WorkGoal.doBreak)", state.getBlock().getName().getString(), pos.toShortString(), side.toShortString()); return exit("under his feet: from a side cell first"); } }
            int drop = 0; BlockPos q = pos.below();
            while (drop < 6 && level.getBlockState(q).getCollisionShape(level, q).isEmpty() && level.getFluidState(q).isEmpty()) { drop++; q = q.below(); }
            boolean takeFall = drop < 6 && !level.getFluidState(q).is(net.minecraft.tags.FluidTags.LAVA) && !level.getBlockState(q).is(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK) && g.getHealth() - (drop + 1 - 3) >= 14; // (stranded on one block over a short drop at near full health: a player just breaks it and takes the hit; refusing re-queued forever, 111 refusals in 15 s)
            if (drop >= 3 && !takeFall && !com.claudemod.director.ClaudeLife.safeDrop(pos) && com.claudemod.entity.life.Consent.theirWord(g, pos, "drop under his feet", "a " + drop + "-block drop under it", null)) { BlockPos stand1 = com.claudemod.entity.life.Consent.safeStand(g, level, pos, null); if (stand1 != null) { g.tasks.addFirst(new Task.Break(pos.immutable())); g.tasks.addFirst(new Task.GoTo(stand1, 0.3)); return exit("their word over the drop hold: off it to a side stand first (Consent.safeStand)"); } } // (their word: dug from beside it, not from on top)
            else if (drop >= 3 && !takeFall && !com.claudemod.director.ClaudeLife.safeDrop(pos)) { com.claudemod.ClaudeMod.LOGGER.info("[Claude life] not digging the block under his feet at {} out: a {}-block drop under it; leaving that one", pos.toShortString(), drop); failed(com.claudemod.entity.life.Attempts.Kind.BREAK, pos, com.claudemod.entity.life.Attempts.Reason.GUARD, "a " + drop + "-block drop under it"); return true; }
        }
        if (com.claudemod.director.ClaudeLife.bpMode(g) && g.blueprint != null && !g.blueprint.isEmpty() && g.blueprint.isShell(pos) && !pos.equals(g.blockPosition()) && !pos.equals(g.blockPosition().above()) /* (in his body: he's suffocating, it comes out; 09:04 gravel fell into his head at 169,-57,202) */ && !com.claudemod.entity.life.MineBlueprint.ore(state) && !g.landingCells.contains(pos) && !g.notches.contains(pos) && !(g.nookOld != null && (g.nookOld.touches(pos) || g.nookOld.oldBedCells().contains(pos))) && !g.tasks.stream().anyMatch(t -> t instanceof Task.Place pl && pl.pos().equals(pos)) && !com.claudemod.director.Primitives.putsBack(g, pos) && !com.claudemod.director.ClaudeLife.obsOwes(pos) && !com.claudemod.director.ClaudeLife.notchHeld(g, pos) && !com.claudemod.entity.life.Consent.theirWord(g, pos, "mine shell", "his mine's wall", null)) { /* (a plan's place_at for the cell is a swap too: dig_at the patch, the ore, place_at the patch) */ /* (a swap: out and straight back, the patch in front of a temp station he's taking up; refused, it re-queued Break+Place forever, 99 tasks on one cell) */ /* (a table/furnace notch is cut into the wall on purpose, then filled by the station) */ // his mine's shell (the arch's sides, every wall, floor and roof): never dug through by a walk, a step cut, a route or a pickup; only ore comes out of it (and is refilled)
            if (!com.claudemod.entity.life.MineBlueprint.ore(state) && com.claudemod.entity.life.TempLedger.built(g, pos)) { com.claudemod.ClaudeMod.LOGGER.info("[Claude ore] {} in his wall at {} is his own patch (put down by {}): the ore's out, that dig is done, his wall stays (WorkGoal.doBreak)", name(state), pos.toShortString(), com.claudemod.entity.life.TempLedger.builtCells(g).get(pos)); return true; } // (his plan queued the ore; the vein pass took it and patched the hole first: live 08:26 11-07, "claimed by two of his systems" x5; stage 778)
            if (com.claudemod.director.ClaudeLife.seenOre(g).remove(pos)) { com.claudemod.ClaudeMod.LOGGER.info("[Claude ore] the ore he saw at {} is already out (now {}): nothing to dig there, his wall stays (WorkGoal.doBreak)", pos.toShortString(), name(state)); return true; } // (queued when it was ore; the guard and the queue agree)
            if (ticks == 1) { shellRefusals++; lastShellRefusal = pos.immutable(); final BlockPos p1 = pos.immutable(); com.claudemod.ClaudeMod.LOGGER.info("[Claude life] not digging through his mine's wall at {} ({}): the shell stays (the dig queued by {}; Places queued within 2 of it: {})", p1.toShortString(), g.lastStep == null ? "-" : g.lastStep.skill(), com.claudemod.entity.ClaudeEntity.originOf(new Task.Break(p1)), g.tasks.stream().filter(t0 -> t0 instanceof Task.Place p0 && p0.pos().distManhattan(p1) <= 2).map(t0 -> ((Task.Place) t0).pos().toShortString() + " by " + com.claudemod.entity.ClaudeEntity.originOf(t0)).toList()); } // (REVIEW-68: a bent way's own shell dig refused here; the swap exemption needs its patch-back Place queued)
            failed(com.claudemod.entity.life.Attempts.Kind.BREAK, pos, com.claudemod.entity.life.Attempts.Reason.GUARD, "his mine's wall");
            return exit("doBreak line 1466");
        }
        if (com.claudemod.director.ClaudeLife.houseMode(g) && g.house != null && g.house.isStructure(pos) && g.house.rightBlock((ServerLevel) g.level(), pos, g.house.roleAt(pos)) && !(current instanceof Task.Break cb && g.tasks.stream().anyMatch(t -> t instanceof Task.Place pl && pl.pos().equals(pos))) && !com.claudemod.entity.life.Consent.theirWord(g, pos, "house", "his house's " + g.house.roleAt(pos), null)) { // his house's walls, posts, roof and foundation: never dug by a walk, a reflex or a pickup (a swap by the house pass comes as Break then Place)
            if (ticks == 1) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] not digging his house's {} at {} ({})", g.house.roleAt(pos), pos.toShortString(), g.lastStep == null ? "-" : g.lastStep.skill());
            failed(com.claudemod.entity.life.Attempts.Kind.BREAK, pos, com.claudemod.entity.life.Attempts.Reason.GUARD, "his house's " + g.house.roleAt(pos));
            return exit("doBreak line 1471");
        }
        if (state.is(net.minecraft.tags.BlockTags.BEDS) && !com.claudemod.director.ClaudeLife.bedPickup(pos) && !com.claudemod.entity.life.Consent.theirWord(g, pos, "bed", "his bed", null) && !(com.claudemod.director.ClaudeLife.isMineCell(g, pos) && !(g.getTarget() instanceof net.minecraft.server.level.ServerPlayer o0 && com.claudemod.context.PlayerProfile.placedBy(o0).contains(pos)))) { // (his own bed on his mine's path is his to take up and carry home: refused, it blocked the stairs and stalled him 20 minutes) // his bed: never dug out by a job (one vanished with nothing in the log to say why); the bed rules take one up themselves
            com.claudemod.ClaudeMod.LOGGER.warn("[Claude life] a job wanted the bed at {} broken (step {}): refused", pos.toShortString(), g.lastStep == null ? "-" : g.lastStep.skill() + " " + g.lastStep.target());
            return exit("doBreak line 1475");
        }
        if ((state.hasBlockEntity() || state.is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE)) && !state.is(net.minecraft.tags.BlockTags.BEDS) && !state.is(net.minecraft.world.level.block.Blocks.SPAWNER) // (a spawner is no station: stage 558, the portal room's silverfish spawner was kept "as storage")
                && !com.claudemod.director.ClaudeLife.isTempStation(g, pos) && !com.claudemod.director.ClaudeLife.stationPickup(pos) && !com.claudemod.entity.life.Consent.theirWord(g, pos, "station", "a station or storage", null)) {
            var bpS = com.claudemod.director.ClaudeLife.bpMode(g) && g.blueprint != null && !g.blueprint.isEmpty() ? g.blueprint : null;
            boolean his0 = g.myStations.contains(pos) || g.notches.contains(pos) || com.claudemod.entity.life.TempLedger.has(g, pos);
            boolean inPocket = com.claudemod.director.ClaudeLife.nookWalkCell(g, pos); // (a nook's walkway cell: the pocket, where no station belongs)
            boolean inHighway = his0 && com.claudemod.entity.life.Highway.owns(g, pos); // (his own table in his highway's cells: the highway digs it, so it's his to take up; review 215, stage 1208)
            if (his0 && ((bpS != null && (bpS.isWalk(pos) || bpS.isWayIn(pos)) && !com.claudemod.director.ClaudeLife.inHisNook(g, pos)) || inPocket || inHighway) && (state.is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE) || state.is(net.minecraft.world.level.block.Blocks.FURNACE))) { // his own station in his mine's walkway (outside his base): his to take up, the walkway dug (refused, the fix came back every pass)
                com.claudemod.director.ClaudeLife.takeUpStation(pos); g.myStations.remove(pos); g.notches.remove(pos); g.tempStations.remove(pos);
                com.claudemod.ClaudeMod.LOGGER.info("[Claude life] his own {} at {} stands in his {} (WorkGoal.doBreak): taking it up, back in his pack", name(state), pos.toShortString(), inPocket ? "base nook's pocket (its walkway)" : inHighway ? "highway" : "mine's walkway");
                g.tasks.addFirst(new Task.Pickup(40));
            } else if (his0 && state.is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE) && liftFor().contains(pos.immutable())) { // his own table over a station he's reaching (reachFor): taken up, back in his pack (live 00:00 11-08, stage 1023)
                liftFor().remove(pos.immutable()); com.claudemod.director.ClaudeLife.takeUpStation(pos); g.myStations.remove(pos); g.notches.remove(pos); g.tempStations.remove(pos);
                com.claudemod.ClaudeMod.LOGGER.info("[Claude life] his own {} at {} over the station he's reaching (WorkGoal.doBreak): taking it up, back in his pack", name(state), pos.toShortString());
                g.tasks.addFirst(new Task.Pickup(40));
            } else { if (ticks == 1) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] not breaking the {} at {} (WorkGoal.doBreak): stations and storage stay put (his own: {}, in his walkway: {}, in his nook: {})", name(state), pos.toShortString(), his0, bpS != null && bpS.isWalk(pos), com.claudemod.director.ClaudeLife.inHisNook(g, pos)); keptAsBuild(pos); return exit((his0 ? "his own " : "not his: ") + name(state) + ", kept: stations and storage stay put"); } // (whose it is in the refusal: the conflict the planner got named nobody, and it asked the user about "your crafting table", D2D 11-09, stage 1195)
        } // stations and storage stay put (except his own temporary ones, and his own taken up on purpose)
        if (g.getTarget() instanceof net.minecraft.server.level.ServerPlayer owner
                && com.claudemod.director.ClaudeLife.protectedBlock(g, owner, pos) && !com.claudemod.entity.life.Consent.theirWord(g, pos, "their build", "part of their build", null)) { if (ticks == 1) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] not breaking {}: it's part of their build", pos.toShortString()); return true; }
        // His pickaxe just broke (tools wear out) and this needs one: don't punch stone for nothing.
        // Drop the rest of this dig and redo the step, which makes or earns a new tool first.
        boolean madeTool = false;
        if (g.notches.contains(pos) && com.claudemod.director.ClaudeLife.tableComing(g) && g.inventory.find(s -> s.isCorrectToolForDrops(state)).isEmpty()) punchThrough().add(pos.immutable()); // (the notch for the table his new pick needs: dug by hand, never a re-tool that wipes it; review 58)
        if (state.requiresCorrectToolForDrops() && g.inventory.find(s -> s.isCorrectToolForDrops(state)).isEmpty()
                && ((madeTool = com.claudemod.director.ClaudeLife.craftToolNow(g, state)) || !punchThrough().contains(pos) || state.is(net.minecraft.tags.BlockTags.NEEDS_IRON_TOOL) || state.is(net.minecraft.tags.BlockTags.NEEDS_DIAMOND_TOOL))) { // (a step cut never punches obsidian)
            if (!madeTool && com.claudemod.director.ClaudeLife.tableComing(g)) return exit("a table for the new tool goes in first (doBreak)"); // (craftToolNow queued it: the re-tool below cleared it, live 19:41:25 11-08, review 58)
            if (!madeTool) { BlockPos tb = null; double tbd = 99; // a table a few steps off: walk to it, then the tool (the walk queued for it was wiped with the rest every time: 18 planks and 30 cobble on him, no pickaxe for minutes)
                for (BlockPos q : BlockPos.betweenClosed(g.blockPosition().offset(-6, -3, -6), g.blockPosition().offset(6, 3, 6))) if (level.getBlockState(q).is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE)) { double d = Math.sqrt(q.distSqr(g.blockPosition())); if (d < tbd) { tbd = d; tb = q.immutable(); } }
                if (tb != null && tbd > 3.5 && !g.attempts.within(com.claudemod.entity.life.Attempts.Kind.TOOL_WALK, tb, g.tickCount, 20 * 60)) { g.tasks.addFirst(new Task.Break(pos.immutable())); g.tasks.addFirst(new Task.GoTo(tb, 1.5)); if (g.tickCount - pulledAt > 20 * 60) { pulledAt = g.tickCount; pulled.clear(); } pulled.add(new Task.Break(pos.immutable())); failed(com.claudemod.entity.life.Attempts.Kind.TOOL_WALK, tb, com.claudemod.entity.life.Attempts.Reason.GUARD, "walked to this table for a tool already"); com.claudemod.ClaudeMod.LOGGER.info("[Claude life] no tool for {}: over to his table at {} to make one", name(state), tb.toShortString()); return true; } } // (a new pick on the spot even mid-climb: he punched one stone of his stairs up for 45 s with the materials on him)
            // like a player: craft a new one right here and keep going with the same job (the rest of the ore, the pickup, the patching)
            if (madeTool) return false;
            if (state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_PICKAXE) && (state.is(net.minecraft.tags.BlockTags.NEEDS_IRON_TOOL) || state.is(net.minecraft.tags.BlockTags.NEEDS_DIAMOND_TOOL))) { boolean hasPick = false; for (int i = 0; i < g.inventory.items.getContainerSize(); i++) { var it0 = g.inventory.items.getItem(i); if (it0.getItem() instanceof net.minecraft.world.item.PickaxeItem && !it0.is(net.minecraft.world.item.Items.WOODEN_PICKAXE) && !it0.is(net.minecraft.world.item.Items.GOLDEN_PICKAXE)) { hasPick = true; break; } } // (stone+ in hand and the block wants iron or diamond: a craft away isn't the answer; a broken stone pick still re-tools below)
                if (hasPick) { if (g.attempts.skipped(com.claudemod.entity.life.Attempts.Kind.BREAK, pos, g.tickCount, 20 * 300)) return true; failed(com.claudemod.entity.life.Attempts.Kind.BREAK, pos, com.claudemod.entity.life.Attempts.Reason.NO_TOOL, net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath() + " at " + pos.toShortString() + ": needs a better pickaxe than his"); com.claudemod.ClaudeMod.LOGGER.info("[Claude life] {} at {} needs a better pickaxe than his: left for later", name(state), pos.toShortString()); return true; } } // not broken, just the wrong tier (deepslate gold on a stone pick): the job goes on (it cleared his whole job 4 times in 2 minutes to "re-tool" a stone pick he had)
            if (g.tickCount - g.retoolTick < 20 * 30) return exit("just tried to re-tool: skip this block, don't loop (doBreak 1493)"); // just tried to re-tool: skip this block, don't loop
            g.retoolTick = g.tickCount;
            com.claudemod.director.ClaudeLife.clearTasks(g);
            if (g.lastStep != null && g.skillQueue.peek() != g.lastStep) g.skillQueue.addFirst(g.lastStep);
            String pick = g.inventory.count(s -> s.is(net.minecraft.tags.ItemTags.STONE_TOOL_MATERIALS)) >= 3 ? "stone_pickaxe" : "wooden_pickaxe"; // nothing to make one from here: the whole chain for one first (wood, planks, sticks, a table)
            if (g.skillQueue.stream().noneMatch(s -> s.skill().equals("obtain") && s.target().endsWith("pickaxe"))) g.skillQueue.addFirst(new com.claudemod.entity.life.Skills.Step("obtain", pick, 1));
            g.noteDoing("nothing on me that can mine " + name(state) + ", getting a " + pick + " before going on");
            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] no tool for {} anymore (broke?), re-tooling", name(state));
            return exit("doBreak line 1501");
        }
        if (!inReach(pos)) { Integer gaveUp = g.attempts.last(com.claudemod.entity.life.Attempts.Kind.REACH, pos); if (gaveUp != null && g.attempts.skipped(com.claudemod.entity.life.Attempts.Kind.REACH, pos, g.tickCount, 20 * 300)) { if (ticks == 1) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] not digging {} at {}: no way to it {}s ago", name(state), pos.toShortString(), (g.tickCount - gaveUp) / 20); return true; } return approach(pos); } // gave up reaching it a moment ago: skipped outright, not walked at again (a plan re-queued the same 150 out-of-reach cells every lap)

        // Standing in water (it flooded in): stop it where it comes in, like a player. Every spot where water flows into
        // his mine from outside (within 6) gets blocked for good; a source inside the mine gets blocked and mined straight
        // back out. The water left inside then drains. (Mining underwater is crawl-slow, so this comes first.)
        if (ticks == 1 && g.isInWater() && !com.claudemod.entity.body.Controls.wading(g) && !(surfacing && roofDig(new Task.Break(pos)))) { // (ankle deep he just mines: full speed there) (his breath dig over his head comes before any plug: 01:20 11-04 the plugs went first and he drowned "doing nothing" under it)
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
                boolean torchAim = w.getBlock() instanceof net.minecraft.world.level.block.BaseTorchBlock;
                if (torchAim && ticks < 40) { // a torch in his aim (his eyes in its little box at his cell's edge): his head moved, never the torch broken for the view (live 12:44 11-09, review 33, stage 1269)
                    BlockPos mid = g.blockPosition(); double mx = mid.getX() + 0.5 - g.getX(), mz = mid.getZ() + 0.5 - g.getZ();
                    if (mx * mx + mz * mz > 0.1 * 0.1) { g.getNavigation().stop(); g.getMoveControl().setWantedPosition(mid.getX() + 0.5, mid.getY(), mid.getZ() + 0.5, 0.5); sightCheckedFor = null; return false; }
                }
                boolean breakable = w.getDestroySpeed(level, inWay) >= 0 && !(g.getTarget() instanceof net.minecraft.server.level.ServerPlayer o
                        && com.claudemod.director.ClaudeLife.protectedBlock(g, o, inWay));
                boolean ownPatch = (w.is(net.minecraft.world.level.block.Blocks.COBBLESTONE) || w.is(net.minecraft.world.level.block.Blocks.COBBLED_DEEPSLATE)) && com.claudemod.director.ClaudeLife.seenOre(g).contains(pos) && com.claudemod.entity.life.MineBlueprint.ore(state); // his own patch over ore he saw: out, the ore, the patch back (the user)
                if (breakable && com.claudemod.entity.life.TempLedger.built(g, inWay) && !ownPatch) breakable = false; // (his own patch over ore he saw is the one build that comes out: the ownPatch way below puts it back; live 05:39-07:41 11-08 a patch-sealed iron ore was picked and dropped five times, stage 1073)
                if (breakable && com.claudemod.director.ClaudeLife.nookShell(g, inWay)) breakable = false; // (his nook's wall in the way of his aim: a look from elsewhere; stage 420 cut the shell beside his furnaces for the view of a dig on the way round) // (his own build in the way, his bedroom wall: a look from elsewhere; the nook pocket dig broke it for the view, his bedroom walled it again, round and round, 14:25)
                if (breakable) for (Direction ld : Direction.values()) if (level.getFluidState(inWay.relative(ld)).is(net.minecraft.tags.FluidTags.LAVA) && com.claudemod.entity.life.Perception.knowsLavaBody(g, level, inWay.relative(ld))) { breakable = false; break; } // (lava behind the block in the way: doBreak refuses it, so a look from elsewhere, not "break it first" twice a second; obsidian at -31,-56,-214 by his pool, live 13:11)
                if (breakable && w.requiresCorrectToolForDrops() && (w.is(net.minecraft.tags.BlockTags.NEEDS_IRON_TOOL) || w.is(net.minecraft.tags.BlockTags.NEEDS_DIAMOND_TOOL)) && g.inventory.find(st -> st.isCorrectToolForDrops(w)).isEmpty()) breakable = false; // no pick for it (obsidian of his frame in front of a mould block): round it to where he sees the block, not a 45 s stand-off
                if (breakable && com.claudemod.director.ClaudeLife.bpMode(g) && g.blueprint != null && !g.blueprint.isEmpty() && g.blueprint.isShell(inWay) && !com.claudemod.entity.life.MineBlueprint.ore(w) && !ownPatch && !com.claudemod.entity.life.MineBlueprint.ore(state)) breakable = false; // (ore behind his wall, seen through a vein hole: the wall block comes out and goes back below; 05:09 a redstone vein wrapped behind his tunnel wall, the hole held open and 30-block walks back and forth)
                if (breakable && torchAim) { BlockPos v = clearViewSpot(level, pos); if (v != null && !v.equals(g.blockPosition())) breakable = false; } // his torch in his line from further off: a step to where he sees it clear, like a player; the torch broken only with no such cell (live 12:54 11-09, review 84, stage 1274)
                boolean shellOverOre = breakable && com.claudemod.director.ClaudeLife.bpMode(g) && g.blueprint != null && !g.blueprint.isEmpty() && g.blueprint.isShell(inWay) && !com.claudemod.entity.life.MineBlueprint.ore(w) && !ownPatch;
                if (shellOverOre) { BlockPos view = clearViewSpot(level, pos); if (view != null && !view.equals(g.blockPosition())) { breakable = false; shellOverOre = false; com.claudemod.ClaudeMod.LOGGER.info("[Claude dig] his wall at {} in the way of {} at {} from {}: it shows from {}, a walk there, not a hole through his wall (WorkGoal.doBreak)", inWay.toShortString(), name(state), pos.toShortString(), g.blockPosition().toShortString(), view.toShortString()); } } // (live 05:12 11-05: 3 rows back from the ore hole he dug his wall along his eye line, 3 cells and their patches, then the patches were in the way; stage 712)
                if (breakable && ownPatch) { if (!(g.blueprint != null && !g.blueprint.isEmpty() && g.blueprint.isWalk(inWay))) g.tasks.addFirst(new Task.Place(inWay.immutable(), w.getBlock().asItem())); /* (a patch in his walkway stays out: the plan wants it open; live 16:57 11-08 put back over his tunnel, dug, put back, review 298, stage 1204) */ g.tasks.addFirst(new Task.Pickup(100)); g.tasks.addFirst(new Task.MineVisibleOres(3)); g.tasks.addFirst(new Task.Break(pos)); g.tasks.addFirst(new Task.Break(inWay)); /* the user: the whole vein before any patch: the vein behind it and its drops first, then the patch back (04:24 it went straight back after each ore and sealed the rest in) */ com.claudemod.ClaudeMod.LOGGER.info("[Claude ore] his patch at {} is over {} he saw at {}: out, the ore, the patch back", inWay.toShortString(), state.getBlock().getName().getString(), pos.toShortString()); return true; }
                boolean patchInFront = com.claudemod.director.ClaudeLife.isTempStation(g, pos) && (w.is(net.minecraft.world.level.block.Blocks.COBBLESTONE) || w.is(net.minecraft.world.level.block.Blocks.COBBLED_DEEPSLATE)) && !com.claudemod.entity.life.Gate.keptFromDig(g, inWay); // (a cell the referee keeps solid is his mine, no patch: queued, refused, 3 times in 1 s, review 273/330, stage 1219) his own patch in front of his own table/furnace: out, the station picked up, the patch back (three tables sat sealed in his stairs wall as litter, the user)
                if (patchInFront) breakable = true; // his mine's shell in the way of his aim (the floor he stands on, the arch's side): he steps round to see the block, never digs through
                boolean station = w.hasBlockEntity() || w.is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE);
                boolean cooking = level.getBlockEntity(inWay) instanceof net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity fz && !(fz.getItem(0).isEmpty() && fz.getItem(2).isEmpty()); // a batch in it: breaking it drops the batch (live 16:50:04, 6 porkchop out; audit 8, stage 1137)
                if (station && !cooking && (com.claudemod.director.ClaudeLife.isTempStation(g, inWay)
                        || g.myStations.contains(inWay) && com.claudemod.director.ClaudeLife.isMineCell(g, inWay))) breakable = true; // his own table out in the open, or in his walkway: pick it up (one set into the wall stays)
                else if (!breakable || station) {
                    // something he won't break (their chest, a table): step around it to where he can see the block
                    BlockPos clear = clearViewSpot(level, pos);
                    if (clear != null && !clear.equals(g.blockPosition()) && g.attempts.within(com.claudemod.entity.life.Attempts.Kind.WALK, clear, g.tickCount, 20 * 60)) { // a view cell he couldn't get to a moment ago: never sent there again (he looped on one six times in 15 s)
                        failed(com.claudemod.entity.life.Attempts.Kind.REACH, pos, com.claudemod.entity.life.Attempts.Reason.OUT_OF_REACH, "no cell to see it from that he can get to");
                        com.claudemod.ClaudeMod.LOGGER.info("[Claude life] can't get a clear look at {}: {} at {} in the way, and no way to {} where he'd see it; leaving it", pos.toShortString(), name(w), inWay.toShortString(), clear.toShortString());
                        return exit("doBreak line 1540");
                    }
                    if (clear != null && !clear.equals(g.blockPosition())) {
                        if (clear.distSqr(g.blockPosition()) <= 2 && clear.getY() == g.getBlockY()) { g.getNavigation().stop(); g.getMoveControl().setWantedPosition(clear.getX() + 0.5, clear.getY(), clear.getZ() + 0.5, 0.6); return false; } // the cell beside him: a nudge (a walk to it "arrives" at once and never gets there; 45 s stuck on a one-block walk)
                        g.tasks.addFirst(new Task.Break(pos));
                        g.tasks.addFirst(new Task.GoTo(clear, 0.5));
                        failed(com.claudemod.entity.life.Attempts.Kind.REACH, clear, com.claudemod.entity.life.Attempts.Reason.GUARD, "sent to this view cell once already"); // tried once: standing on it next time is fine, being sent to it again is not
                        return exit("doBreak line 1546: " + name(w) + " at " + inWay.toShortString() + " in the way, going round to " + clear.toShortString() + " to see it");
                    }
                    if (clear != null && clear.equals(g.blockPosition()) && (Math.abs(g.getX() - (clear.getX() + 0.5)) > 0.15 || Math.abs(g.getZ() - (clear.getZ() + 0.5)) > 0.15) && ticks < 40) { g.getNavigation().stop(); g.getMoveControl().setWantedPosition(clear.getX() + 0.5, clear.getY(), clear.getZ() + 0.5, 0.5); return false; } // (his own cell sees it from its middle; he stood off to the side: a step to the middle, never "refused" from the edge, 05:49 a diamond sealed in so)
                    failed(com.claudemod.entity.life.Attempts.Kind.BREAK, pos, com.claudemod.entity.life.Attempts.Reason.OUT_OF_REACH, "not in sight from any cell he can stand in"); // (on record: his mine check re-queued it every 3 s behind a redstone ore his stone pick can't take)
                    if (com.claudemod.director.ClaudeLife.bpMode(g) && g.blueprint != null && !g.blueprint.isEmpty() && g.blueprint.isShell(inWay)) { shellRefusals++; lastShellRefusal = inWay.immutable(); }
                    com.claudemod.ClaudeMod.LOGGER.info("[Claude life] can't break {}: {} at {} is in the way", pos.toShortString(), name(w), inWay.toShortString());
                    return exit("doBreak line 1550");
                }
                if (com.claudemod.entity.life.MineBlueprint.ore(w) && w.requiresCorrectToolForDrops() && g.inventory.find(s -> s.isCorrectToolForDrops(w)).isEmpty()) { // ore in front that needs a pick he hasn't got (stone in front without a pick still goes the re-tool way: a pick made on the spot): queuing it first only loops (live 03:18 World 15: copper in front of stone, wooden pick, 270 re-queues in 3 min)
                    failed(com.claudemod.entity.life.Attempts.Kind.BREAK, pos, com.claudemod.entity.life.Attempts.Reason.NO_TOOL, "ore in front of it needs a pick he hasn't got");
                    com.claudemod.ClaudeMod.LOGGER.info("[Claude life] can't break {}: {} at {} is in the way and needs a better pick than he has (WorkGoal.doBreak)", pos.toShortString(), name(w), inWay.toShortString());
                    return exit("in the way: " + name(w) + " needs a better pick");
                }
                if (com.claudemod.entity.nav.ActionPath.structureBlock(level, inWay) && !com.claudemod.entity.nav.ActionPath.structureDigOk(level, inWay) && !com.claudemod.entity.life.TempLedger.built(g, inWay) && !(g.lastStep != null && g.lastStep.skill().equals("light_end_portal"))) { // the intact rule refuses it anyway (live 01:09, a stronghold brick queued 3x a second as "in the way")
                    failed(com.claudemod.entity.life.Attempts.Kind.BREAK, pos, com.claudemod.entity.life.Attempts.Reason.GUARD, "a structure's block in the way, kept intact");
                    com.claudemod.ClaudeMod.LOGGER.info("[Claude life] can't break {}: {} at {} is in the way, a structure's own block kept intact (WorkGoal.doBreak)", pos.toShortString(), name(w), inWay.toShortString());
                    return exit("in the way: a structure's own block");
                }
                clearCrack();
                if (com.claudemod.director.ClaudeLife.isTempStation(g, pos) && (w.is(net.minecraft.world.level.block.Blocks.COBBLESTONE) || w.is(net.minecraft.world.level.block.Blocks.COBBLED_DEEPSLATE))) g.tasks.addFirst(new Task.Place(inWay.immutable(), w.getBlock().asItem())); // the patch goes back once the station's out
                if (shellOverOre) g.tasks.addFirst(new Task.Place(inWay.immutable(), inWay.getY() < 0 && g.inventory.count(s0 -> s0.is(net.minecraft.world.item.Items.COBBLED_DEEPSLATE)) > 0 ? net.minecraft.world.item.Items.COBBLED_DEEPSLATE : net.minecraft.world.item.Items.COBBLESTONE)); // his wall back once the ore's out (its own vein-behind check waits for the rest)
                g.tasks.addFirst(new Task.Break(pos));
                g.tasks.addFirst(new Task.Break(inWay));
                com.claudemod.ClaudeMod.LOGGER.info("[Claude dig] {} at {} is in the way of {} at {} (he's at {}; {}{}): that first, then it (WorkGoal.doBreak)", name(w), inWay.toShortString(), name(state), pos.toShortString(), g.blockPosition().toShortString(), g.blueprint != null && g.blueprint.isShell(inWay) ? "his mine's wall" : g.blueprint != null && g.blueprint.isWalk(inWay) ? "his walkway" : "not his mine", shellOverOre ? ", his wall put back after" : ""); // (silent before: 141 "back in the queue (doBreak line 1556)" 02:44-03:03 11-05, stone at -101, 12, -20 nine times from one spot, the block in the way never named)
                return exit("in the way: " + name(w) + " at " + inWay.toShortString() + " first (doBreak line 1556)");
            }
        }

        g.getNavigation().stop();
        g.getLookControl().setLookAt(Vec3.atCenterOf(pos));
        // the right tool in hand once he's actually at the block (and again now and then, in case something swapped it)
        if (!pos.equals(equippedFor) || ticks % 10 == 0) { int q0 = g.tasks.size(); equipBestTool(state); equippedFor = pos.immutable(); if (g.tasks.size() > q0 && g.tasks.peekFirst() instanceof Task.GoTo goT) { g.tasks.pollFirst(); g.tasks.addFirst(new Task.Break(pos.immutable())); g.tasks.addFirst(goT); if (g.tickCount - pulledAt > 20 * 60) { pulledAt = g.tickCount; pulled.clear(); } pulled.add(new Task.Break(pos.immutable())); /* (parked behind the walk on purpose: the in-reach rule leaves it there) */ return exit("the new tool needs his table (the nook's, 20 off): over to it first, this dig after (01:57: the walk was queue (doBreak 1563)"); } } // the new tool needs his table (the nook's, 20 off): over to it first, this dig after (01:57: the walk was queued and the dig went on with the iron pick, every 30 s, for 6 minutes)
        // Held left click through the game's own survival mining: real speed (tool, enchantments, on ground or
        // not, underwater), real drops and tool wear, the crack everyone else sees.
        // User Nov 4: reopen his plug from dry ground, sealing the newly exposed feeds with real right-clicks before water flows.
        boolean needsAir = surfacing || com.claudemod.entity.life.Watchdog.airRoute(g);
        boolean waterTunnel = !needsAir && level.dimension() == net.minecraft.world.level.Level.OVERWORLD && g.blueprint != null && g.blueprint.isWalk(pos) && !com.claudemod.director.ClaudeLife.isCastBlock(pos);
        if (waterTunnel) {
            var nextRow = g.blueprint.rows().get(g.blueprint.rowOf(pos)); java.util.Set<BlockPos> rowCells = new java.util.HashSet<>(), waterFeeds = new java.util.HashSet<>();
            for (BlockPos f : nextRow.feet()) for (int y = 0; y < nextRow.height(); y++) rowCells.add(f.above(y));
            for (BlockPos f : rowCells) for (Direction d : Direction.values()) { BlockPos q = f.relative(d); if (!rowCells.contains(q) && level.getFluidState(q).is(net.minecraft.tags.FluidTags.WATER)) waterFeeds.add(q.immutable()); }
            int feeds = waterFeeds.size();
            ItemStack fill = solidScaffold();
            if (feeds > 0 && (fill.isEmpty() || g.inventory.count(x -> x.is(fill.getItem())) < feeds)) { com.claudemod.ClaudeMod.LOGGER.info("[Claude mine] {} needs {} water seals before reopening, not enough solid blocks on him (WorkGoal.doBreak)", pos.toShortString(), feeds); return exit("water tunnel needs solid blocks before opening the plug"); }
        }
        if (!g.mineTick(pos)) return false;
        if (waterTunnel) {
            var row = g.blueprint.rows().get(g.blueprint.rowOf(pos));
            java.util.Set<BlockPos> interior = new java.util.HashSet<>(); for (BlockPos f : row.feet()) for (int y = 0; y < row.height(); y++) interior.add(f.above(y));
            java.util.Set<BlockPos> feeds = new java.util.LinkedHashSet<>();
            for (BlockPos f : row.feet()) for (int y = 0; y < row.height(); y++) for (Direction d : Direction.values()) { BlockPos q = f.above(y).relative(d); if (!interior.contains(q) && level.getFluidState(q).is(net.minecraft.tags.FluidTags.WATER)) feeds.add(q.immutable()); }
            BlockPos pocket = null; // a feed with no solid face anywhere round it: open water, no player places into that from dry ground
            for (BlockPos wet : feeds) {
                ItemStack fill = solidScaffold(); boolean sealed = false; StringBuilder tried = new StringBuilder();
                for (Direction against : Direction.values()) {
                    BlockPos support = wet.relative(against); if (level.getBlockState(support).canBeReplaced() || !level.getFluidState(support).isEmpty()) continue;
                    Vec3 hit = visibleFacePoint(level, g.getEyePosition(), support, against, 4.5); if (hit == null || fill.isEmpty()) { tried.append(against.getName()).append(hit == null ? " face hidden; " : " nothing to fill with; "); continue; }
                    g.hold(fill); g.useOnAt(support, against.getOpposite(), true, hit);
                    if (level.getFluidState(wet).isEmpty() && !level.getBlockState(wet).canBeReplaced()) { sealed = true; break; }
                    tried.append(against.getName()).append(" placed, still ").append(level.getBlockState(wet).getBlock().getName().getString()).append("; ");
                }
                com.claudemod.ClaudeMod.LOGGER.info("[Claude mine] water feed {} exposed by {}: {} (WorkGoal.doBreak)", wet.toShortString(), pos.toShortString(), sealed ? "sealed before flow" : "needs a visible supporting face (tried from " + String.format("%.2f %.2f %.2f", g.getX(), g.getEyeY(), g.getZ()) + ": " + (tried.length() == 0 ? "no solid face round it" : tried.toString()) + ")");
                // A forward face hidden by the other plug half becomes reachable when that half opens next; keep working from the dry row.
                if (!sealed && tried.length() == 0 && pocket == null) pocket = wet;
            }
            var seg0 = g.blueprint.segments.get(row.segment());
            if (pocket != null && seg0.kind == com.claudemod.entity.life.MineBlueprint.Kind.BRANCH) { // the branch ends here, like at lava: this cell plugged, nothing queued past it (live 01:54 11-05 World 17: the fills past it sent him swimming into the aquifer, his own blocks boxed him in)
                int oldN = seg0.n, cutAt = row.index(); seg0.n = cutAt; g.blueprint.invalidate(); int dropped = 0;
                for (java.util.Iterator<Task> it = g.tasks.iterator(); it.hasNext(); ) { Task q = it.next(); BlockPos tp = q instanceof Task.Place p0 ? p0.pos() : q instanceof Task.Break b0 ? b0.pos() : q instanceof Task.GoTo g0 ? g0.pos() : null; if (tp == null) continue;
                    int along = (tp.getX() - seg0.start.getX()) * seg0.dir.getStepX() + (tp.getZ() - seg0.start.getZ()) * seg0.dir.getStepZ(), side = Math.abs((tp.getX() - seg0.start.getX()) * seg0.dir.getStepZ() + (tp.getZ() - seg0.start.getZ()) * seg0.dir.getStepX());
                    if (along >= cutAt && along <= oldN + 1 && side <= 2) { it.remove(); dropped++; } }
                ItemStack plugFill = solidScaffold(); if (!plugFill.isEmpty()) g.tasks.addFirst(new Task.Place(pos.immutable(), plugFill.getItem()));
                com.claudemod.ClaudeMod.LOGGER.info("[Claude mine] branch at {} opened into open water at {} (no face to seal it against from dry ground): plugging {}, the branch ends at {} long (was {}), {} queued job(s) past it dropped (WorkGoal.doBreak)", seg0.start.toShortString(), pocket.toShortString(), pos.toShortString(), cutAt, oldN, dropped);
            }
        }
        { // what the break opened is in front of him now: the fluid beside it on record, and lava there means the block straight back in (lava runs into an opening a second and more later; a player puts it back)
            BlockPos lv = null;
            for (Direction d : Direction.values()) { BlockPos q = pos.relative(d); if (!level.getFluidState(q).isEmpty()) { com.claudemod.entity.life.Perception.sawCell(g, level, q); if (level.getFluidState(q).is(net.minecraft.tags.FluidTags.LAVA) && (d != Direction.DOWN || standsOn(pos))) lv = q.immutable(); } } // (lava under the hole stays down there: stage 1232)
            boolean waterIn = false; for (Direction d : Direction.Plane.HORIZONTAL) { var wf = level.getFluidState(pos.relative(d)); if (wf.is(net.minecraft.tags.FluidTags.WATER)) waterIn = true; } // (a water source beside the opening runs in and makes the lava behind it obsidian: the user's pocket technique, 15:33 the dirt put back undid it)
            if (lv != null && !waterIn && !com.claudemod.director.ClaudeLife.isCastBlock(pos) && !com.claudemod.director.ClaudeLife.obsOpening(g, pos)) { // the lava itself plugged through the hole, the same tick: a click on the block past it, the way kept open (stage 939, live 04:15 11-07 in basalt deltas: every face had lava behind it, the block went back each time and the bore went round and round)
                java.util.List<BlockPos> lavas = new java.util.ArrayList<>(); for (Direction d : Direction.values()) { BlockPos q = pos.relative(d); if (level.getFluidState(q).is(net.minecraft.tags.FluidTags.LAVA) && (d != Direction.DOWN || standsOn(pos))) lavas.add(q.immutable()); }
                ItemStack f0 = g.inventory.find(com.claudemod.entity.life.ClaudeInventory::isBuildingBlock);
                boolean all = !f0.isEmpty() && g.hold(st0 -> st0.is(f0.getItem())); String how = "";
                for (BlockPos q : lavas) { if (!all) break; Object[] pk = plugThrough(level, pos, q); if (pk == null) { all = false; how = "no face bordering " + q.toShortString() + " in sight and reach through the hole"; break; }
                    g.useOnAt((BlockPos) pk[0], (Direction) pk[1], true, (Vec3) pk[2]); if (level.getFluidState(q).is(net.minecraft.tags.FluidTags.LAVA)) { all = false; how = "the click at " + ((BlockPos) pk[0]).toShortString() + " didn't take"; break; } }
                if (all && !lavas.isEmpty()) { com.claudemod.ClaudeMod.LOGGER.info("[Claude life] lava behind {} as it came out (at {}): plugged through the hole with {}, the way kept open (WorkGoal.doBreak)", pos.toShortString(), lavas.stream().map(BlockPos::toShortString).toList(), f0.getItem()); lv = null; }
                else if (!lavas.isEmpty() && !how.isEmpty()) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] lava behind {} as it came out: not plugged through the hole ({}): the block back instead (WorkGoal.doBreak)", pos.toShortString(), how);
            }
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
        boolean ownObsidian = com.claudemod.entity.life.LessonWords.atOwnPocket(g); // (at his own obsidian pocket: the water's his on purpose, no plug, no "open water" refusal: stage 415's layer below was refused and dropped)
        if (!needsAir && !waterTunnel && !level.canSeeSky(pos) && !com.claudemod.director.ClaudeLife.isCastBlock(pos) && !ownObsidian) { // (the casting water running round his pillar's foot is not an aquifer: it "plugged" the pit and wrote the pillar's foot off as open water)
            ItemStack plug = solidScaffold(); // (never gravel or sand: a plug over his head would fall on him)
            if (plug.isEmpty() && pendingStepUp != null && pos.getY() > g.getBlockY()) { // cutting up into water with nothing to plug it: that way is off (he'd drown in his own cut)
                int sources = 0;
                for (BlockPos q : BlockPos.betweenClosed(pos.offset(-1, 0, -1), pos.offset(1, 2, 1))) if (level.getFluidState(q).isSource()) sources++;
                if (sources >= 2) {
                    failed(com.claudemod.entity.life.Attempts.Kind.BREAK, pos, com.claudemod.entity.life.Attempts.Reason.NO_BLOCKS, "cutting up into water (" + sources + " sources) with nothing to plug it");
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
            if (surfaceWater && n == 0) { failed(com.claudemod.entity.life.Attempts.Kind.BREAK, pos, com.claudemod.entity.life.Attempts.Reason.GUARD, "open water up top, not a flooded mine"); com.claudemod.ClaudeMod.LOGGER.info("[Claude life] water at {} is open water up top: leaving it, not that block", pos.toShortString()); return true; }
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
        if (level.getBlockState(pos.above()).getBlock() instanceof net.minecraft.world.level.block.FallingBlock && net.minecraft.world.level.block.FallingBlock.isFree(level.getBlockState(pos))) { // a gravel column over the cell: it comes down into it, dug again till the cell stays open (a player keeps digging; stage 1284)
            g.tasks.addFirst(new Task.Break(pos.immutable()));
            if (!pos.equals(columnSaid)) com.claudemod.ClaudeMod.LOGGER.info("[Claude dig] {} over {} comes down into it: dug again till it stays open (WorkGoal.doBreak)", name(level.getBlockState(pos.above())), pos.toShortString()); columnSaid = pos.immutable();
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
                || com.claudemod.entity.life.LessonWords.atOwnPocket(g) // (mining from his obsidian pocket: the drops wait for the sweep at the end; the walk to each one took him off the pocket, a trip back every block, stage 500)
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
            boolean overhang = feet.equals(t.from().above()) && com.claudemod.entity.life.PlaceAim.standable((ServerLevel) g.level(), t.from()); // on the edge of the block beside it, his column over the lip one down (08:42 at -10,82,8 over -10,81,8: the walk said "there", the leap gave up, 5 min of it)
            boolean beside = overhang || feet.getY() == t.from().getY() && Math.abs(feet.getX() - t.from().getX()) + Math.abs(feet.getZ() - t.from().getZ()) == 1 && com.claudemod.entity.life.PlaceAim.standable((ServerLevel) g.level(), t.from());
            if (beside) { g.getNavigation().stop(); g.getMoveControl().setWantedPosition(t.from().getX() + 0.5, t.from().getY(), t.from().getZ() + 0.5, 0.5); return false; } // the lip right beside him: a step onto it (the walk's own "there" is a cell off)
            if (!walkTo(t.from(), 0.5)) return false;
            feet = BlockPos.containing(g.getX(), g.getY() + 0.2, g.getZ());
            if (!feet.equals(t.from())) { com.claudemod.ClaudeMod.LOGGER.info("[Claude leap] the walk to the lip {} ended at {} (y {}; lip cell {}, under it {}): leaving the {} (WorkGoal.doLeap)", t.from().toShortString(), feet.toShortString(), String.format("%.2f", g.getY()), g.level().getBlockState(t.from()), g.level().getBlockState(t.from().below()), com.claudemod.entity.nav.Leaps.kindName(t.kind())); return exit("the walk to the lip ended off it"); }
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
            if (qp != null && qp.distSqr(g.blockPosition()) <= 6 * 6 && !(q instanceof Task.Break && com.claudemod.entity.life.Watchdog.hiddenOre(g.level(), qp))) { // (ore still walled in waits for the dig that opens it)
                g.tasks.remove(q);
                g.tasks.addFirst(q);
                return;
            }
        }
    }
    private boolean walkNoDig;
    private int hotHeldTill; // the lava.hold's back-off: not asked again till the fire it waited out is done
    private static boolean lavaWithin(ServerLevel level, BlockPos p, int r) { for (BlockPos q : BlockPos.betweenClosed(p.offset(-r, -1, -r), p.offset(r, 1, r))) if (level.getFluidState(q).is(net.minecraft.tags.FluidTags.LAVA)) return true; return false; }
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
          if (dest.getY() < f0.getY() - 2 && g.mineSpine.stream().anyMatch(sp -> sp.distSqr(f0) <= 12 * 12)) { failed(com.claudemod.entity.life.Attempts.Kind.WALK, dest, com.claudemod.entity.life.Attempts.Reason.GUARD, "by his mine: no zigzag cut down to it, his stairs are the way down"); return false; } }
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
            if (p.below().equals(target)) continue; // never stood on to be mined: broken from on top he drops into what's under it, unseen (live 10:39:54 11-09: a water pocket, 14 min in it, drowned; stage 1353)
            if (!level.getFluidState(p).isEmpty() || !level.getFluidState(p.above()).isEmpty()) continue; // (lava has no collision box: live 07:14 World 15 a furnace's view spot was a lava cell, a walk at it every minute for 47 min)
            if (g.attempts.within(com.claudemod.entity.life.Attempts.Kind.WALK, p, g.tickCount, 20 * 120) || !p.equals(g.blockPosition()) && g.attempts.within(com.claudemod.entity.life.Attempts.Kind.REACH, p, g.tickCount, 20 * 120)) continue; // (REACH: a view spot he was sent to and didn't stand on; a walk that ends at once is never a WALK failure, and -39,-14,113 was picked again and again, live 23:5x) // (a spot he couldn't get to lately: not picked again, the write-off holds)
            Vec3 eye = Vec3.atBottomCenterOf(p).add(0, 1.62, 0);
            if (eye.distanceTo(Vec3.atCenterOf(target)) > REACH - 0.3) continue;
            boolean seen = false; Vec3 c0 = Vec3.atCenterOf(target); // any face he'd see from there, the same test as the dig's own (centre only: the lapis over a head-height hole was "hidden" from right under it, and he stood 3 off for good, 05:02)
            for (Vec3 aim : aims(c0)) {
                var hit = level.clip(new net.minecraft.world.level.ClipContext(eye, aim, net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.NONE, g));
                if (hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK || hit.getBlockPos().equals(target)) { seen = true; break; } // (OUTLINE, as blockInTheWay: with COLLIDER a torch at head height let a cell "see" what the dig there found hidden behind it, his torch broken anyway, review 84, stage 1274)
            }
            if (!seen) continue;
            double dd = p.distSqr(g.blockPosition());
            if (dd < bestD) { bestD = dd; best = p; }
        }
        return best;
    }

    private BlockPos sightCheckedFor;

    /** The first other block between his eyes and the target, if any (what a player's crosshair would hit: vines and grass count). */
    /** Where a player can aim on a block: the middle, each face's middle, and each face's edges (his own wall torch hid every face middle of the furnace under it, 05:40 World 15: a player aims past the torch at the face's lower edge). */
    private static Vec3[] aims(Vec3 c) {
        java.util.List<Vec3> out = new java.util.ArrayList<>(); out.add(c);
        for (Direction d : Direction.values()) {
            Vec3 f = c.add(d.getStepX() * 0.45, d.getStepY() * 0.45, d.getStepZ() * 0.45); out.add(f);
            for (Direction e : Direction.values()) if (e.getAxis() != d.getAxis()) out.add(f.add(e.getStepX() * 0.35, e.getStepY() * 0.35, e.getStepZ() * 0.35));
        }
        return out.toArray(new Vec3[0]);
    }

    private BlockPos blockInTheWay(ServerLevel level, BlockPos target) {
        // a player can hit any face he can see: only "in the way" if every face (and the middle) is hidden
        BlockPos first = null;
        Vec3 c = Vec3.atCenterOf(target);
        for (Vec3 aim : aims(c)) {
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

    /** How far back he walks for a torch: a slot (or mouth) of his mine further than any other dark spot. */
    private double torchBack(Task.WallTorch t) { boolean mineTorch = com.claudemod.director.ClaudeLife.bpMode(g) && g.blueprint != null && !g.blueprint.isEmpty() && (g.blueprint.isTorchSlot(t.pos()) || g.blueprint.touches(t.pos())); /* (a branch mouth isn't a slot: queued within 16 all the same, dropped past 8, live 14:49 11-07, stage 1008) */ return mineTorch ? com.claudemod.entity.life.Weights.w("torch.slot_walk_back", 16) : com.claudemod.entity.life.Weights.w("torch.walk_back", 8); }
    /** Steps of the way to the torch he's on when that way is far past his walk back for it (doWallTorch drops it next tick), else 0. */
    private int torchFar;
    private boolean torchTooFar(int steps) { if (!(current instanceof Task.WallTorch wt) || steps <= torchBack(wt) * com.claudemod.entity.life.Weights.w("torch.way_over_back", 1.5)) return false; torchFar = steps; return true; }
    private boolean doWallTorch(Task.WallTorch t) {
        ServerLevel level = (ServerLevel) g.level();
        boolean slot = com.claudemod.director.ClaudeLife.bpMode(g) && g.blueprint != null && !g.blueprint.isEmpty() && g.blueprint.isTorchSlot(t.pos()); // a slot of his blueprint mine: exactly there. The eye-level guess read the world mid-dig: a step's bottom block still to dig lifted the torch a block, a floor mined out for ore dropped it one (world 14's stairs: 0, +1, -1 off the step)
        BlockPos pos = slot ? t.pos() : eyeLevel(level, t.pos(), t.wall());
        BlockPos wall = pos.relative(t.wall());
        if (ticks == 1) torchFar = 0;
        else if (torchFar > 0) { com.claudemod.ClaudeMod.LOGGER.info("[Claude torch] the torch at {} is {} off but {} steps by the way there, past {}: he's moved on from that dark spot (WorkGoal.doWallTorch)", pos.toShortString(), (int) Math.sqrt(pos.distSqr(g.blockPosition())), torchFar, (int) (torchBack(t) * com.claudemod.entity.life.Weights.w("torch.way_over_back", 1.5))); torchFar = 0; g.getNavigation().stop(); return true; } // (review 92: the next branch 12 off through the rock, 81 walks round by the main tunnel; the straight line passed it)
        { double back = torchBack(t); // a slot of his mine: as far as his upkeep queues one (8 here, 16 there: 7 slots re-queued every 3 s and each dropped at once, live 11:54 11-07, stage 1005)
          if (ticks == 1 && pos.distSqr(g.blockPosition()) > back * back) { com.claudemod.ClaudeMod.LOGGER.info("[Claude torch] the torch at {} is {} off, past {}: he's moved on from that dark spot (WorkGoal.doWallTorch)", pos.toShortString(), (int) Math.sqrt(pos.distSqr(g.blockPosition())), (int) back); return true; } } // he's moved on from that dark spot: not worth walking back for
        BlockState onto = level.getBlockState(wall);
        if (onto.hasBlockEntity() || onto.is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE)) { com.claudemod.ClaudeMod.LOGGER.info("[Claude torch] the torch at {} would go on the {} at {}: skipped; queued by {} (WorkGoal.doWallTorch)", pos.toShortString(), net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(onto.getBlock()).getPath(), wall.toShortString(), com.claudemod.entity.ClaudeEntity.originOf(t)); return true; } // never a torch stuck on a table or furnace
        if (ticks == 1 && slot && com.claudemod.entity.life.MineBlueprint.slotLit(level, pos)) return true; // (its slot has its torch already)
        if (ticks == 1 && !slot) for (BlockPos q : BlockPos.betweenClosed(pos.offset(-2, -2, -2), pos.offset(2, 2, 2))) { BlockState qs = level.getBlockState(q); if (!q.equals(pos) && (qs.is(net.minecraft.world.level.block.Blocks.WALL_TORCH) || qs.is(net.minecraft.world.level.block.Blocks.TORCH))) { com.claudemod.ClaudeMod.LOGGER.info("[Claude torch] the torch at {} on the {} wall: one already at {}, skipped; queued by {} (WorkGoal.doWallTorch)", pos.toShortString(), t.wall().getName(), q.toShortString(), com.claudemod.entity.ClaudeEntity.originOf(t)); return true; } } // a torch went up right by it since this one was planned (two queued in one pass went up stacked on one wall): a player looks before he puts one up
        if (!level.getBlockState(pos).canBeReplaced() || !level.getFluidState(pos).isEmpty() || !level.getBlockState(wall).isFaceSturdy(level, wall, t.wall().getOpposite())) { // (water there: a torch would just pop off
            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] torch at {} on the {} wall: no solid wall / spot taken, skipped", pos.toShortString(), t.wall().getName());
            return true;
        }
        ItemStack torch = g.inventory.find(s -> s.is(net.minecraft.world.item.Items.TORCH));
        if (torch.isEmpty() && com.claudemod.entity.life.Crafting.craft(level, g.inventory, net.minecraft.world.item.Items.TORCH, 1, false).made() > 0) { torch = g.inventory.find(s -> s.is(net.minecraft.world.item.Items.TORCH)); com.claudemod.ClaudeMod.LOGGER.info("[Claude torch] none on him for the torch at {}: made {} from his coal and sticks (WorkGoal.doWallTorch)", pos.toShortString(), g.inventory.count(s -> s.is(net.minecraft.world.item.Items.TORCH))); } // (a 2x2 craft, in his hands: mining in the dark with coal and wood on him, review 437; audit 5)
        if (torch.isEmpty()) { com.claudemod.ClaudeMod.LOGGER.info("[Claude torch] the torch at {}: none on him, skipped; queued by {} (WorkGoal.doWallTorch)", pos.toShortString(), com.claudemod.entity.ClaudeEntity.originOf(t)); return true; }
        BlockPos under = pos; for (int k = 0; k < 3 && !level.getBlockState(under.below()).isFaceSturdy(level, under.below(), Direction.UP); k++) under = under.below(); // (the floor under the torch's cell, not the cell: a torch up on a 3-high wall is mid-air, live 05:13-05:50 11-07 NO_PATH to it from the notch under its wall for 40 min)
        if (!inReach(pos)) {
            if (!g.blockPosition().equals(under) && ticks < 200) { if (under.distSqr(g.blockPosition()) <= 2) { g.getNavigation().stop(); g.getMoveControl().setWantedPosition(under.getX() + 0.5, under.getY(), under.getZ() + 0.5, 0.5); } else walkTo(under, 0.4); return false; } // onto it, not near it: the notch 1 off it is where the wall's face is out of sight and reach (a step: a nudge, a walk "arrives" a block short)
            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] torch at {} on the {} wall: out of reach from the floor under it, skipped", pos.toShortString(), t.wall().getName());
            return true;
        }
        Vec3 face = Vec3.atCenterOf(wall).subtract(t.wall().getStepX() * 0.5, 0, t.wall().getStepZ() * 0.5);
        if (!com.claudemod.entity.life.Perception.clear(level, g.getEyePosition(), face.add(t.wall().getStepX() * -0.05, 0, t.wall().getStepZ() * -0.05))) { // the wall's face out of his sight (round a corner, behind a block): no torch clicked through it; up to the torch's own cell for a look, else skipped (no sensing through walls: the one place path that skipped the check)
            if (!g.blockPosition().equals(under) && ticks < 200) { if (under.distSqr(g.blockPosition()) <= 2) { g.getNavigation().stop(); g.getMoveControl().setWantedPosition(under.getX() + 0.5, under.getY(), under.getZ() + 0.5, 0.5); } else walkTo(under, 0.4); return false; } // onto it, not near it: a notch 1 off it is where the face can't be seen
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
    public String exitWhyForTest() { return exitWhy; }
    /** Lava let spread from {@code from} (open cells sideways and down, 8 steps: the Nether's reach) gets to his feet or head. */
    /** A block can go in at p: something solid beside it, a footing beside it with something solid by that, or an open dry column under it. */
    private static boolean wallable(ServerLevel level, BlockPos p) {
        for (Direction d : Direction.values()) { BlockPos n = p.relative(d); if (!level.getBlockState(n).getCollisionShape(level, n).isEmpty()) return true;
            if (!level.getBlockState(n).canBeReplaced() || !level.getFluidState(n).isEmpty()) continue;
            for (Direction d2 : Direction.values()) { BlockPos s2 = n.relative(d2); if (!s2.equals(p) && !level.getBlockState(s2).getCollisionShape(level, s2).isEmpty()) return true; } }
        return false;
    }
    /** He stands on p (his feet on its top, his box over it). */
    private boolean standsOn(BlockPos p) { var bb = g.getBoundingBox(); return Math.abs(bb.minY - (p.getY() + 1)) < 0.25 && bb.maxX > p.getX() && bb.minX < p.getX() + 1 && bb.maxZ > p.getZ() && bb.minZ < p.getZ() + 1; }
    private boolean spreadReaches(ServerLevel level, BlockPos from) {
        BlockPos feet = g.blockPosition(), head = feet.above(); java.util.ArrayDeque<BlockPos> q = new java.util.ArrayDeque<>(); java.util.Map<BlockPos, Integer> seen = new java.util.HashMap<>(); q.add(from); seen.put(from, 0);
        while (!q.isEmpty()) { BlockPos c = q.poll(); int n = seen.get(c); if (c.equals(feet) || c.equals(head)) return true; if (n >= 8) continue;
            BlockPos dn = c.below(); boolean fallsDown = level.getBlockState(dn).canBeReplaced() && level.getFluidState(dn).isEmpty();
            for (BlockPos nb : fallsDown ? java.util.List.of(dn) : java.util.List.of(c.north(), c.south(), c.east(), c.west())) if (!seen.containsKey(nb) && level.getBlockState(nb).canBeReplaced() && !level.getFluidState(nb).is(net.minecraft.tags.FluidTags.LAVA)) { seen.put(nb, n + 1); q.add(nb); } }
        return false;
    }
    /** Lava beside a standing cell at feet or head, or over his head. */
    static boolean besideLava(ServerLevel level, BlockPos sp) { if (level.getFluidState(sp.above(2)).is(net.minecraft.tags.FluidTags.LAVA)) return true; for (Direction d : Direction.Plane.HORIZONTAL) for (int dy = 0; dy <= 1; dy++) if (level.getFluidState(sp.relative(d).above(dy)).is(net.minecraft.tags.FluidTags.LAVA)) return true; return false; }
    /** A dig of ore he can't have seen (every face closed; the dig that opens it dropped or not yet done): not started, his lesson or their step naming the cell aside. The plan and the vein scan queue it again once a face is open. */
    private boolean unseenOre(Task t) {
        if (!(t instanceof Task.Break b) || !com.claudemod.entity.life.Watchdog.hiddenOre(g.level(), b.pos())) return false;
        if (com.claudemod.entity.life.Consent.lesson(g) || com.claudemod.entity.life.Consent.theirStepNames(g, b.pos()) != null) return false;
        if (com.claudemod.director.ClaudeLife.seenOre(g).contains(b.pos())) { // he saw it, its faces closed since: still his to dig to (live 10:25:59 11-07: a diamond under the one he mined seen, 4 s later "he can't have seen it", then skipped every scan for 2 hours)
            StringBuilder shut = new StringBuilder(); for (Direction d : Direction.values()) { BlockPos n = b.pos().relative(d); shut.append(shut.length() == 0 ? "" : ", ").append(d.getName()).append(" ").append(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(g.level().getBlockState(n).getBlock()).getPath()).append(com.claudemod.entity.life.TempLedger.built(g, n) || com.claudemod.entity.life.TempLedger.has(g, n) ? " (his)" : ""); }
            com.claudemod.director.ClaudeLife.nookSay(g, "seenShut" + b.pos().asLong(), "[Claude dig] the " + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(g.level().getBlockState(b.pos()).getBlock()).getPath() + " at " + b.pos().toShortString() + ": seen, every face closed since (" + shut + "): digging to it (WorkGoal.unseenOre)");
            return false; }
        com.claudemod.ClaudeMod.LOGGER.info("[Claude dig] not digging the {} at {}: every face of it closed, he can't have seen it; dropped till a dig opens it (he's at {}; queued by {}) (WorkGoal.unseenOre)", net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(g.level().getBlockState(b.pos()).getBlock()).getPath(), b.pos().toShortString(), g.blockPosition().toShortString(), com.claudemod.entity.ClaudeEntity.originOf(t));
        return true;
    }
    private boolean exit(String why) { exitWhy = why; return true; }
    /** A failure of his with its cause, from where he stands (the user 11-04: fix causes, not counters; the skip holds while the cause does). */
    private void failed(com.claudemod.entity.life.Attempts.Kind k, BlockPos p, com.claudemod.entity.life.Attempts.Reason r, String detail) { g.attempts.fail(k, p, g.tickCount, g.blockPosition(), r, detail); }
    /** The nearest hostile within 16 of him, or null (a BLOCKED_MOB skip ends when it moves off). */
    private net.minecraft.world.entity.Mob nearestHostile() { return level().getEntitiesOfClass(net.minecraft.world.entity.Mob.class, g.getBoundingBox().inflate(16), m -> m.isAlive() && m instanceof net.minecraft.world.entity.monster.Enemy).stream().min(java.util.Comparator.comparingDouble(m -> m.distanceToSqr(g))).orElse(null); }
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
            if (g.attempts.within(com.claudemod.entity.life.Attempts.Kind.BREAK, p, g.tickCount, 20 * 300) || g.attempts.within(com.claudemod.entity.life.Attempts.Kind.REACH, p, g.tickCount, 20 * 300) || com.claudemod.entity.life.Watchdog.off(g, new Task.Break(p))) continue; /* (written off by the watchdog too: "not run" leaves no new refusal, the old one cleared as he stood somewhere new, and this re-queued it every 2 ticks, 45 s, live 13:59 11-07, stage 1007) */ // (no way to it: doBreak drops it for 300 s, so not queued either; 05:2x a redstone 15 blocks off re-queued every few s) // (refused a moment ago, lava behind it or out of reach: not asked for again every sweep, 05:31)
            ores.add(p.immutable());
            if (ores.size() >= 24) break;
        }
        // In his own mine: only the ore in its walls (not every ore showing in some cave nearby), and then only the
        // part of each vein close behind it, reachable through the opening. Out in a cave: whatever shows.
        // in his own mine: ore in its walls, plus ore he can plainly see off in a cave beside it that he can walk to (a player goes and
        // gets that, then comes back); never ore he'd have to tunnel after blind
        if (ownMine) ores.removeIf(o -> {
            for (Direction d : Direction.values()) { BlockPos nb = o.relative(d); if (com.claudemod.director.ClaudeLife.isMineCell(g, nb) || (g.blueprint != null && g.blueprint.isShell(nb) && level.getBlockState(nb).isAir())) return false; } // (showing through a hole in his own wall counts as his wall: the iron at 13, 18, -115 behind the hole at 13, 18, -114 was held for and never mined)
            if (!com.claudemod.director.ClaudeLife.canSee(g, level, o) && !com.claudemod.director.ClaudeLife.seenOre(g).contains(o)) { com.claudemod.director.ClaudeLife.nookSay(g, "oreSkip" + o.asLong(), "[Claude ore] skipping " + level.getBlockState(o).getBlock().getName().getString() + " at " + o.toShortString() + ": not in his mine's walls and he can't see it"); /* (once per ore, not every 5 ticks: 4,700 lines on 11-04) */ return true; }
            if (o.distSqr(at) <= 6 * 6) return false;
            if (com.claudemod.director.ClaudeLife.seenOre(g).contains(o) && o.distSqr(at) <= 12 * 12) return false; // (ore he saw, sealed since: dug to from his tunnel, no walk to an open cell needed)
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
                    if (!level.getBlockState(n).is(kind)) continue; if (com.claudemod.entity.life.Watchdog.skipBreak(g, n)) continue; // (the vein grown from a good ore pulled the refused one back in)
                    if (ownMine) { boolean shows = false; for (Direction dd : Direction.values()) { BlockState nn = level.getBlockState(n.relative(dd)); if (nn.isAir() || !nn.canOcclude()) shows = true; } if (!shows) continue; } // in his mine: only the vein he can see (showing to air), never tunnelling after hidden ore (03:05: chased a vein blind to y 5, ten under his floor)
                    if (all.add(n.immutable())) todo.add(n.immutable());
                }
            }
        }
        ores = new java.util.ArrayList<>(all);
        ores.sort(java.util.Comparator.<BlockPos>comparingInt(p -> level.getBlockState(p).is(net.minecraft.tags.BlockTags.DIAMOND_ORES) ? 0 : 1).thenComparingDouble(p -> p.distSqr(at)));
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
                && (com.claudemod.director.ClaudeLife.touchesMine(g, o) || ownMine && java.util.Arrays.stream(Direction.values()).anyMatch(d -> g.blueprint != null && airJoinedToMine(level, g.blueprint, o.relative(d))))) patch.add(o); // World 16: the rest of a small ore pocket is patched too, not only its mouth.
        patch.sort(java.util.Comparator.comparingDouble((BlockPos p) -> p.distSqr(at)).reversed()); // deepest holes before the opening that gives him access
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
        if (inOwnTunnel && filler != net.minecraft.world.item.Items.AIR) steps.add(new Task.MineVisibleOres(t.radius())); // another look before any patch: ore the digs just showed is the same vein, out first (the mine work order rule; D2D review 102732-253 11-07: the diamond at -318,-57,-32 patched 2 s after, a second one behind it, the patch dug out again)
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
    /** Ore showing into that pocket with line of sight, diamonds first, never his walkway's own. */
    public static java.util.List<BlockPos> veinBehind(com.claudemod.entity.ClaudeEntity g, ServerLevel level, BlockPos pos) {
        java.util.Set<BlockPos> pocket = pocketBehind(g, level, pos); java.util.LinkedHashSet<BlockPos> out = new java.util.LinkedHashSet<>();
        for (BlockPos p : pocket) for (Direction d : Direction.values()) { BlockPos q = p.relative(d); if (pocket.contains(q)) continue; BlockState s = level.getBlockState(q);
            if (!com.claudemod.entity.life.MineBlueprint.ore(s) || g.work != null && g.work.lavaHolds(level, q) /* (ore his lava guard keeps him from: the patch waited for it for good, both re-queued for minutes, live 22:43-22:46 11-09, review 36, stage 1290) */ || (g.blueprint != null && g.blueprint.isWalk(q)) || !com.claudemod.director.ClaudeLife.canSee(g, level, q) && !com.claudemod.director.ClaudeLife.seenOre(g).contains(q)) continue; // (in sight, or seen before: live 16:32:56 a diamond seen through the hole, out of sight 3 s later as he backed out, sealed in; stage 1013)
            out.add(q.immutable()); }
        java.util.List<BlockPos> l = new java.util.ArrayList<>(out); l.sort(java.util.Comparator.<BlockPos>comparingInt(q -> level.getBlockState(q).is(net.minecraft.tags.BlockTags.DIAMOND_ORES) ? 0 : 1).thenComparingDouble(q -> q.distSqr(pos))); return l;
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
        if (!g.inventory.hasRoomFor(i.getItem()) && (isOreDrop(i.getItem()) || i.getItem().is(net.minecraft.world.item.Items.ENDER_PEARL) || i.getItem().is(net.minecraft.world.item.Items.BLAZE_ROD) || i.getItem().is(net.minecraft.world.item.Items.ENDER_EYE) || i.getItem().is(net.minecraft.world.item.Items.OBSIDIAN)
                || com.claudemod.director.ClaudeLife.lootValue(i.getItem()) >= 12 && !(i.getItem().getItem() instanceof net.minecraft.world.item.TieredItem ti && (ti.getTier() == net.minecraft.world.item.Tiers.WOOD || ti.getTier() == net.minecraft.world.item.Tiers.STONE)) // gear worth more than a stack of stone (live 17:16 11-07: a diamond helmet left on his death pile for a pack of cobbled deepslate, despawned)
                || i.getItem().has(net.minecraft.core.component.DataComponents.FOOD) && !i.getItem().is(net.minecraft.world.item.Items.ROTTEN_FLESH) && !i.getItem().is(net.minecraft.world.item.Items.SPIDER_EYE) && com.claudemod.director.Kit.foodAsk(g) != null)) // (food while the kit's short: live 14:29 11-07 "not going for 3 Raw Porkchop: no room" on the hunt for it)
            com.claudemod.director.ClaudeLife.makeRoom(g, i.position(), i.getItem()); // ponytail: live 14:07, 3 pearls on his death pile skipped for a pack full of netherrack and spare kit; a player tosses junk for what he came for
        if (!g.inventory.hasRoomFor(i.getItem())) return "no room for it in his pack";
        BlockPos ip = i.blockPosition();
        if (g.level().getBlockState(ip).isCollisionShapeFullBlock(g.level(), ip)) return "inside a block";
        if (i.getY() - g.getY() > 1.3 && !isOreDrop(i.getItem()) && com.claudemod.director.ClaudeLife.lootValue(i.getItem()) < 12 && onFelledLeaves(g.level(), ip)) return "up on a felled tree's leaves: it comes down as they decay"; // (1 oak log 4.7 up: a 23-act way round a hill, 9 out and back, review 32, stage 1226)
        return null;
    }
    /** Resting on natural leaves with no log left within 4: a felled tree's, so they decay and drop it. */
    static boolean onFelledLeaves(net.minecraft.world.level.Level level, BlockPos ip) {
        var l = level.getBlockState(ip.below()); if (!(l.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock) || l.getValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT)) return false;
        for (BlockPos q : BlockPos.betweenClosed(ip.offset(-4, -4, -4), ip.offset(4, 3, 4))) if (level.getBlockState(q).is(net.minecraft.tags.BlockTags.LOGS)) return false;
        return true;
    }
    public static boolean valuableDrop(ItemStack s) { return isOreDrop(s); }
    private static java.util.Set<String> pickupSaid; private static int pickupEmptySaid = -1000; // (each drop's skip said once; an empty sweep at most every 5 s)
    static int buildingBlocks(ClaudeEntity g) { return g.inventory.count(st -> com.claudemod.director.ClaudeLife.junkItem(st) || st.is(net.minecraft.world.item.Items.COBBLESTONE) || st.is(net.minecraft.world.item.Items.COBBLED_DEEPSLATE)); }
    /** Junk his pickup leaves (a stack of building blocks on him already): no patch waits on it either (live 08:26-09:01 11-07: 113 give-ups on 2 gravel, stage 898). */
    static boolean junkSkipped(ClaudeEntity g, ItemStack s) { return com.claudemod.director.ClaudeLife.junkItem(s) && buildingBlocks(g) >= 64; }
    private static boolean pickupSkipped(net.minecraft.world.entity.item.ItemEntity i, String why) {
        if (why == null) return false;
        if (pickupSaid == null) pickupSaid = new java.util.HashSet<>(); if (pickupSaid.size() > 512) pickupSaid.clear(); // said once per kind of drop, cell and reason: three netherrack drops in one cell were three identical lines, read as a conflict (stage 923 flaky 1 in 6)
        if (pickupSaid.add(i.getItem().getItem() + "@" + i.blockPosition().asLong() + why)) com.claudemod.ClaudeMod.LOGGER.info("[Claude pickup] not going for {} {} at {}: {} (WorkGoal.doPickup)", i.getItem().getCount(), i.getItem().getHoverName().getString(), i.blockPosition().toShortString(), why);
        return true;
    }

    private int pickupBeat = -1000;
    /** How near a patch a drop counts as its own: the patch waits for it and its held pickup takes it, the one ball for both (live 00:44 11-05: 2.6 for the wait, 1.6 for the pickup, a drop between was waited on and never taken, 3 tries a second). */
    private static double holdR2() { return 2.6 * 2.6; }

    /** A way his planner made, queued as his walker's own: a Put or dig on a cell where another system's job (a mine patch) was refused and parked is the way's, never dropped by that park (live 01:36-01:38 11-05: the way's Put at -92, 15, -45 was taken for bpIntegrity's parked patch there, same block, same cell, and dropped; 2 min of "no path, nothing changed since"). */
    private BlockPos columnSaid; // (the gravel column last said: once per cell, not per block)
    private void queueWay(java.util.List<Task> tasks, String by) { // (by: the planner's method, as the queue's stack walk names it; ClaudeLife.walkPlanTask reads "WorkGoal.walkTo")
        int r0 = com.claudemod.entity.life.Gate.refused(g);
        for (int i = tasks.size() - 1; i >= 0; i--) { Task t = tasks.get(i); com.claudemod.entity.ClaudeEntity.ORIGIN.put(t, by); g.tasks.addFirst(t); }
        if (com.claudemod.entity.life.Gate.refused(g) == r0 || !(tasks.get(tasks.size() - 1) instanceof Task.GoTo end)) return;
        // a step of the way refused (the referee kept a cell): the rest goes too, else its walk stands behind a block that stays (live 15:07 11-08, 8 s of no headway); the walk on, planned again round it (stage 1049)
        java.util.Set<Task> way = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()); way.addAll(tasks.subList(0, tasks.size() - 1));
        g.tasks.removeIf(way::contains); plannedAt().remove(end.pos());
        com.claudemod.ClaudeMod.LOGGER.info("[Claude route] the made way to {} dropped: {}; the walk on, planned round it (WorkGoal.queueWay)", end.pos().toShortString(), com.claudemod.entity.life.Gate.LAST_WHY.get(g.getUUID()));
    }
    private boolean doPickup(Task.Pickup t) {
        if (ticks > t.maxTicks()) { boolean mineHold = t == holdPickup && holdAt != null && minePatch(holdAt); if (t == holdPickup) leaveHeld(); if (mineHold) return true; if (g.tasks.stream().noneMatch(x -> x instanceof Task.Pickup && x != t)) for (var i0 : g.level().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, g.getBoundingBox().inflate(8), i1 -> i1.isAlive() && !g.isGift(i1) && isOreDrop(i1.getItem()))) { com.claudemod.ClaudeMod.LOGGER.info("[Claude life] the pickup ran out of time with {} still at {}", i0.getItem().getHoverName().getString(), i0.blockPosition().toShortString()); com.claudemod.entity.life.OwnEvents.left(g, i0, "the pickup ran out of time"); leaveOwn(i0); } return true; } // (valuables still lying there go on his book: "you didn't grab them all" finds them; only when no later Pickup is queued: 07:08 a 40-tick per-ore pickup ran out mid-fall and booked a diamond LEFT that the vein's own pickup right after would have got)
        if (falling()) { fallNote("a pickup"); return false; } // dropping (down the hole he just dug): what's reachable is judged from where he lands
        { Vec3 e = com.claudemod.director.ClaudeLife.portalEdge(); if (e != null && Math.abs(g.getY() - e.y) <= 0.6) return true; } // up his casting pillar: nothing is chased from up here
        if (ticks == 1 && !(lastPickupTask == t)) { pickupEffort = 0; pickupTowers = 0; lastPickupTask = t; }
        ServerLevel level = (ServerLevel) g.level();
        var items = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, g.getBoundingBox().inflate(8, 10, 8), net.minecraft.world.entity.item.ItemEntity::isAlive); // tall: logs fall from treetops
        final int seen0 = items.size();
        items.removeIf(i -> pickupSkipped(i, pickupSkip(g, i))); // given up on, no room, inside a block: the same filter the waits ask (ClaudeLife.nookMove)
        items.removeIf(i -> (i.getItem().is(net.minecraft.tags.ItemTags.SAPLINGS) || i.getItem().is(net.minecraft.world.item.Items.STICK)) && i.getY() > g.getY() + 2 && pickupSkipped(i, "a sapling or stick up in the leaves: not worth a climb"));
        { int blocks = buildingBlocks(g);
          items.removeIf(i -> junkSkipped(g, i.getItem()) && pickupSkipped(i, "junk, with " + blocks + " building blocks on him already: not worth the walk")); } // (junk waits: live 03:43 11-07 a netherrack drop at his highway's open edge walked him off it into the basalt, 45 min stuck, starved)
        com.claudemod.entity.life.MineBlueprint bp = com.claudemod.director.ClaudeLife.bpMode(g) && g.blueprint != null && !g.blueprint.isEmpty() && (g.blueprint.touches(g.blockPosition()) || airJoinedToMine(level, g.blueprint, g.blockPosition())) ? g.blueprint : null; // in his mine (blueprint mode), an ore hole off it too (06:49 standing in one he took bp null: no wall pre-queue, the shell guard refused, a diamond left)
        if (t == holdPickup && holdAt != null) { BlockPos cell = holdAt; var pocket = minePatch(cell) ? pocketBehind(g, level, cell) : java.util.Set.<BlockPos>of(); items.removeIf(i -> !pocket.contains(i.blockPosition()) && i.position().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(cell)) > holdR2() && pickupSkipped(i, "outside the pocket this patch would close (WorkGoal.doPickup)")); } // World 16: a drop in the next cell belongs to the same hold, not only the 1.6-block ball.
        if (digInAt != null && digInTasks != null && digInTasks.stream().anyMatch(x -> x == t)) { BlockPos hole = digInAt; items.removeIf(i -> (Math.abs(i.getBlockX() - hole.getX()) > 1 || Math.abs(i.getBlockZ() - hole.getZ()) > 1) && pickupSkipped(i, "the cover's own pickup takes only what's in the hole")); } // the cover's own pickup (the dirt for the lid): only what's in the hole (it walked him out of his half-dug hole at night to drops lying about)
        // (a full pack: nothing to be had: pickupSkip above)
        if (bp != null) items.removeIf(i -> !bp.touches(i.blockPosition()) && !com.claudemod.director.ClaudeLife.inHisNook(g, i.blockPosition()) && !airJoinedToMine(level, bp, i.blockPosition()) && pickupSkipped(i, "in his mine only drops in it, in his nook or in open air joined to it count")); // only what lies in the mine or in open air joined to it (an ore hole dug off his walkway, the drop a cell deeper in: it was invisible here and the patch sealed it in): a drop that drifted up through the rock lured him into cutting his way up through his own ceiling
        if (items.isEmpty()) { if ((seen0 > 0 || g.tickCount - pickupEmptySaid > 100)) { pickupEmptySaid = g.tickCount; com.claudemod.ClaudeMod.LOGGER.info("[Claude pickup] nothing to pick up round {} ({} drop(s) in his sweep, all skipped as said) (WorkGoal.doPickup)", g.blockPosition().toShortString(), seen0); } return true; }
        var nearest = items.stream().min(java.util.Comparator.comparingDouble(g::distanceToSqr)).get();
        if (g.tickCount - pickupBeat > 100) { pickupBeat = g.tickCount; com.claudemod.ClaudeMod.LOGGER.info("[Claude pickup] going for {} {} at {} from {} (task tick {}/{}, effort {}, nav {}, {} drop(s) left) (WorkGoal.doPickup)", nearest.getItem().getCount(), nearest.getItem().getHoverName().getString(), nearest.blockPosition().toShortString(), g.blockPosition().toShortString(), ticks, t.maxTicks(), pickupEffort, g.getNavigation().isDone() ? "idle" : "moving", items.size()); } // (07:29 a 45 s pickup logged nothing: which drop, which branch)
        g.getLookControl().setLookAt(nearest);
        if (ticks % 8 != 1) return false;
        BlockPos ip = nearest.blockPosition();
        if (bp != null && isOreDrop(nearest.getItem()) && !g.getBoundingBox().inflate(1.0, 0.5, 1.0).intersects(nearest.getBoundingBox())) { // out of his reach in his mine, and his own next dig opens the way to it: that dig first (193: the drop in its ore hole beside the slot had him down the stairs and cutting the step's bottom ahead of its head cell)
            Task opens = null; int k0 = 0;
            for (Task q0 : g.tasks) { if (++k0 > 8) break; if (q0 instanceof Task.Break b0 && b0.pos().distSqr(ip) <= 2 && bp.isWalk(b0.pos()) && !level.getBlockState(b0.pos()).getCollisionShape(level, b0.pos()).isEmpty()) { opens = q0; break; } }
            if (opens != null) {
                Task later = new Task.Pickup(t.maxTicks()); if (t == holdPickup) holdPickup = (Task.Pickup) later; lastPickupTask = later;
                java.util.ArrayDeque<Task> before = new java.util.ArrayDeque<>(); while (!g.tasks.isEmpty() && g.tasks.peekFirst() != opens) before.addLast(g.tasks.pollFirst());
                g.tasks.pollFirst(); while (!before.isEmpty()) g.tasks.addFirst(before.pollLast()); g.tasks.addFirst(later); g.tasks.addFirst(opens); // (the dig now, the pickup straight after, then the rest in their order, each keeping who queued it: with the rest back in front, the patch waiting on this pickup came round first and waited again, 3 times a second, live 01:43:52 11-05, stage 710)
                com.claudemod.ClaudeMod.LOGGER.info("[Claude pickup] {} at {}: out of reach from {}, and his next dig at {} opens the way to it: the dig first, the pickup straight after (WorkGoal.doPickup)", nearest.getItem().getHoverName().getString(), ip.toShortString(), g.blockPosition().toShortString(), ((Task.Break) opens).pos().toShortString());
                return true;
            }
        }
        // 1. walk there if he can: onto it, or right next to it (in a wall pocket, players grab it from beside it)
        var path = g.getNavigation().createPath(ip, 0);
        if (path == null || !path.canReach()) path = g.getNavigation().createPath(ip, 1);
        if (path != null && path.canReach() && path.getEndNode() != null && path.getEndNode().asBlockPos().equals(g.blockPosition())) path = null; // (a path "to within 1" that ends where he stands: nothing to walk, nav idle, "going for" it 400 ticks; the step into the pocket below gets it, live 13:11 11-08, stage 1046)
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
        // 1b. up on leaves in his reach from where he stands (a log of his chop caught in the canopy): the leaf under it broken, it drops to him (live 07:32 11-09, reviews 27/189: a pillar, 6 digs and a bridge up for one log; stage 1300)
        if (nearest.getY() - g.getY() > 1.3 && !nearest.onGround() && nearest.getDeltaMovement().y < -0.01) { g.tasks.addFirst(again); g.tasks.addFirst(new Task.Wait(4, false)); return true; } // (falling to him, the leaf under it broken: waited for, not climbed to)
        if (nearest.getY() - g.getY() > 1.3) { BlockPos on = BlockPos.containing(nearest.getX(), nearest.getY() - 0.1, nearest.getZ());
            if (level.getBlockState(on).is(net.minecraft.tags.BlockTags.LEAVES) && g.getEyePosition().distanceTo(Vec3.atCenterOf(on)) <= g.blockInteractionRange() && com.claudemod.entity.life.Watchdog.seen(g, on) && !g.attempts.within(com.claudemod.entity.life.Attempts.Kind.BREAK, on, g.tickCount, 20 * 30)) {
                com.claudemod.ClaudeMod.LOGGER.info("[Claude pickup] {} at {} sits on leaves at {} in his reach: knocked down, not climbed to (WorkGoal.doPickup)", nearest.getItem().getHoverName().getString(), ip.toShortString(), on.toShortString());
                g.tasks.addFirst(again); g.tasks.addFirst(new Task.Break(on.immutable())); return true; } }
        // 2. a walk his walker plans: its ActionPath prices a dig, a step cut or a pillar against the way round (live 14:16 11-05: up out of a 3-deep pit, the sight-line dig and a 3-block "jump" had him "tried 16 ways"); the moves below only once that walk is on record failed, nothing changed since
        if (bp == null && nearest.getY() - g.getY() > 1.3 && !g.attempts.skipped(com.claudemod.entity.life.Attempts.Kind.WALK, ip, g.tickCount, 0)) { // (higher than a jump (1.25): no move below climbs it; down in a pocket or across a gap they do. shortcut: the walker can't yet plan into a 1-deep side pocket (522) or a lava gap's pickup (525), widen when it can)
            com.claudemod.ClaudeMod.LOGGER.info("[Claude pickup] {} at {}: no plain walk from {} ({} up, {} off): his walker plans the way (WorkGoal.doPickup)", nearest.getItem().getHoverName().getString(), ip.toShortString(), g.blockPosition().toShortString(), String.format("%.1f", nearest.getY() - g.getY()), String.format("%.1f", hz));
            g.tasks.addFirst(again); g.tasks.addFirst(new Task.GoTo(ip.immutable(), 1.0)); return true;
        }
        // 3. a block between him and it (item in a nook, behind a wall, buried in a hole): mine it
        BlockPos inWay = blockInTheWay(level, ip);
        if (inWay != null && (g.attempts.within(com.claudemod.entity.life.Attempts.Kind.REACH, inWay, g.tickCount, 20 * 60) || g.attempts.within(com.claudemod.entity.life.Attempts.Kind.BREAK, inWay, g.tickCount, 20 * 60))) { com.claudemod.ClaudeMod.LOGGER.info("[Claude pickup] the block in the way of {} at {} was refused a moment ago: not asking again", nearest.getItem().getHoverName().getString(), inWay.toShortString()); inWay = null; } // (04:21 the same refused dig asked for every second)
        if (inWay != null && level.getBlockState(inWay).getDestroySpeed(level, inWay) >= 0 && !level.getBlockState(inWay).hasBlockEntity() && !(com.claudemod.entity.nav.ActionPath.structureBlock(level, inWay) && !com.claudemod.entity.life.TempLedger.built(g, inWay)) && !(bp != null && bp.isShell(inWay)) && !(bp == null && !com.claudemod.director.ClaudeLife.isMineCell(g, inWay) && com.claudemod.director.ClaudeLife.touchesMine(g, inWay))) { // (never his mine's wall: the raw iron in a wall hole had him break the block over it, 12 fixes round that row; his staircase's walls too: the drop from a table notch had him cutting along his stair wall after it, the repairs he'd queued lost in the mess)
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
            boolean floorless = level.getBlockState(step.below()).getCollisionShape(level, step.below()).isEmpty(); // (the step's own cell is dug below; what he'd stand on is under it)
            if (floorless || g.attempts.skipped(com.claudemod.entity.life.Attempts.Kind.WALK, step, g.tickCount, 0)) { // (live 14:39 11-05: a step onto open air over the shaft, refused, queued again 43 times in 4 s)
                com.claudemod.ClaudeMod.LOGGER.info("[Claude pickup] {} at {}: no step down at {} ({}) (WorkGoal.doPickup)", nearest.getItem().getHoverName().getString(), ip.toShortString(), step.toShortString(), floorless ? "nothing under it: a drop, not a step" : "the walk there failed, nothing changed since");
            } else {
            g.tasks.addFirst(again);
            g.tasks.addFirst(new Task.GoTo(step, 0.6));
            for (BlockPos b : new BlockPos[] {step.above(2), step.above(), step}) if (!level.getBlockState(b).getCollisionShape(level, b).isEmpty()) g.tasks.addFirst(new Task.Break(b));
            return true;
            }
        }
        // 4. caught up in a tree's leaves: knock out the leaves it's lying on so it falls down to him
        BlockPos rest = level.getBlockState(ip).is(net.minecraft.tags.BlockTags.LEAVES) ? ip : ip.below();
        if (dy > 1.5 && level.getBlockState(rest).is(net.minecraft.tags.BlockTags.LEAVES) && treeBlockInWay(rest) && pickupEffort <= 8) {
            g.tasks.addFirst(again);
            g.tasks.addFirst(new Task.Break(rest));
            return true;
        }
        // 4b. in his mine, a valuable drop up in an ore pocket off the tunnel's wall, out of reach and too high to jump to: the wall beside him toward it opened, a step in, the grab, the wall put back, like a player (stage 263 1 in 4: from the floor 2 across and 2 below it, "tried 16 ways", then the patch sealed it in)
        if (bp != null && valuable && dy > 1.3 && dy < 3.4 && horiz < 3.2 && g.onGround() && pickupEffort <= 20) {
            Direction dir = Direction.getNearest(nearest.getX() - g.getX(), 0, nearest.getZ() - g.getZ());
            BlockPos s0 = g.blockPosition().relative(dir);
            double ry = nearest.getY() - s0.getY(), rx = Math.abs(nearest.getX() - (s0.getX() + 0.5)), rz = Math.abs(nearest.getZ() - (s0.getZ() + 0.5));
            java.util.List<BlockPos> dig = new java.util.ArrayList<>(); boolean ok = ry <= 2.2 && ry >= -0.4 && rx <= 1.2 && rz <= 1.2 && !level.getBlockState(s0.below()).getCollisionShape(level, s0.below()).isEmpty(); // (from that cell his reach takes it: the body's pickup box, 1 across and half a block over his head)
            for (BlockPos q : new BlockPos[] {s0.above(), s0}) { if (!ok) break; BlockState qs = level.getBlockState(q); if (qs.getCollisionShape(level, q).isEmpty()) continue; if (qs.getDestroySpeed(level, q) < 0 || qs.hasBlockEntity() || !level.getFluidState(q).isEmpty()) ok = false; else dig.add(q.immutable()); }
            if (ok && !dig.isEmpty()) {
                net.minecraft.world.item.Item fill = s0.getY() < 0 ? net.minecraft.world.item.Items.COBBLED_DEEPSLATE : net.minecraft.world.item.Items.COBBLESTONE;
                for (BlockPos q : dig) if (bp.isShell(q)) g.tasks.addFirst(new Task.Place(q, fill)); // his wall back after (queued first: his mine's shell guard lets a cell out only with its patch-back on the queue); a cell of his own planned tunnel stays open, his mine digs it anyway (live 03:10:06: "his mine wants it dug out, then a pickup wants it filled", D2D review 461)
                g.tasks.addFirst(again); g.tasks.addFirst(new Task.GoTo(s0.immutable(), 0.5));
                for (BlockPos q : dig) g.tasks.addFirst(new Task.Break(q));
                com.claudemod.ClaudeMod.LOGGER.info("[Claude pickup] {} at {}: {} up in the pocket, out of reach from {}: opening his wall at {} for the step in, the grab, then the wall back (WorkGoal.doPickup)", nearest.getItem().getHoverName().getString(), ip.toShortString(), String.format("%.1f", dy), g.blockPosition().toShortString(), dig);
                return true;
            }
        }
        // 5. a jump gets him there (a ledge up to ~3 blocks right beside it): jump for it, like a player
        BlockPos jx = g.blockPosition().relative(Direction.getNearest(nearest.getX() - g.getX(), 0, nearest.getZ() - g.getZ()));
        boolean jumpReach = Math.max(Math.abs(ip.getX() - g.getBlockX()), Math.abs(ip.getZ() - g.getBlockZ())) <= 1 || level.getBlockState(jx).getCollisionShape(level, jx).isEmpty() && level.getBlockState(jx.above()).getCollisionShape(level, jx.above()).isEmpty(); // (a jump reaches a drop on the ledge beside him, or he steps closer first; 2 cells off behind a wall it only pushes him into the wall: 100 ticks at a time, live 13:13 11-09, stage 1276)
        if (dy > 1.5 && dy < 3.4 && g.onGround() && jumpReach) {
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
        if (!junkDrop && bridgeToward(nearest.position(), isOreDrop(nearest.getItem()) ? 60 : 10)) return false; // (junk isn't worth a block put out over a drop: it waits)
        if (valuable && bp == null && digThroughToward(nearest.position())) { g.tasks.add(again); return true; } // ore: never left behind, dig straight to it (not through his mine's shell)
        if (valuable && bp != null && airJoinedToMine(level, bp, ip)) { // ore dropped in an ore hole off his tunnel (head-high, 2 deep: 03:32 two diamonds out of reach, then patched in): the player's way, break the wall block under the hole, step in, take it; the wall goes back on the patch list
            { Vec3 to0 = nearest.position().subtract(g.position()); BlockPos nx0 = g.blockPosition().relative(Direction.getNearest(to0.x, 0, to0.z)); // the wall cells he opens go back once he has it: queued now, so the shell guard lets the break through (03:41 it refused them 626 times: the Pickup asked again and again)
              for (BlockPos w0 : new BlockPos[] {nx0, nx0.above()}) if (bp.isShell(w0) && !level.getBlockState(w0).getCollisionShape(level, w0).isEmpty()) g.tasks.addFirst(new Task.Place(w0.immutable(), w0.getY() < 0 && g.inventory.count(x -> x.is(net.minecraft.world.item.Items.COBBLED_DEEPSLATE)) > 0 ? net.minecraft.world.item.Items.COBBLED_DEEPSLATE : net.minecraft.world.item.Items.COBBLESTONE)); }
            g.tasks.addFirst(again);
            if (digThroughToward(nearest.position())) { com.claudemod.ClaudeMod.LOGGER.info("[Claude life] {} at {} is down an ore hole off his tunnel: opening the wall under it to step in for it", nearest.getItem().getHoverName().getString(), ip.toShortString()); return true; }
            g.tasks.remove(again);
        }
        { StringBuilder round = new StringBuilder(); BlockPos me0 = g.blockPosition(); // (what the moves met: the cells between him and it, so a "no way" beside him can be read from the log; review 84 live 02:11 11-09 didn't repro)
          for (BlockPos q : new BlockPos[] {new BlockPos(ip.getX(), me0.getY(), me0.getZ()), new BlockPos(me0.getX(), me0.getY(), ip.getZ()), ip.above(), ip, ip.below()}) round.append(round.length() == 0 ? "" : ", ").append(q.toShortString()).append("=").append(level.getBlockState(q).getBlock().getName().getString());
          com.claudemod.ClaudeMod.LOGGER.info("[Claude life] left {} at {} behind: no way to it (he's at {}, {} up, {} off; effort {}, path {}; round it: {}; it: on ground {}, falling {}, age {}; him: on ground {})", nearest.getItem().getHoverName().getString(),
                ip.toShortString(), g.blockPosition().toShortString(), String.format("%.1f", dy), String.format("%.1f", horiz), pickupEffort, path == null ? "none" : path.getNodeCount() + " nodes, reaches " + path.canReach(), round, nearest.onGround(), String.format("%.2f", nearest.getDeltaMovement().y), nearest.getAge(), g.onGround()); } // (review 393 live 20:43 11-09: a Blaze Rod left mid-air at tick 2, never picked up; stage tries 1359 on the ground and a 10-deep pit both got it: whether it was still falling is what this shows)
        com.claudemod.entity.life.OwnEvents.left(g, nearest, "no way to it"); // (on his book: "you didn't grab all your diamonds" resolves to it)
        if (!leaveOwn(nearest)) g.markGift(nearest); // given up on: he doesn't keep going back for it (a valuable of his by his own mine: his still, recover_left)
        g.tasks.addFirst(again); return true; // nothing works: leave it
    }

    /** Patch his mine: any opening into a cave around this step (walls, floor, ceiling) gets filled, never his own mine's open space. */
    // ---- Nether travel: the bore ----
    private Direction boreDir; private Direction.Axis boreAxis0; private BlockPos boreCol; private int boreTries, boreYOff, boreLaneOff, boreDetour, boreStill, boreSteps, boreClean; private long boreStart; private BlockPos boreFrom, boreLast; private Task.Bore boreRun;
    private static final int[][] BORE_DETOURS = {{0, -2}, {1, 0}, {-1, 0}, {0, 2}, {2, 0}, {-2, 0}, {1, -2}, {-1, -2}, {0, -4}, {0, 4}, {4, 0}, {-4, 0}, {0, 6}, {6, 0}, {-6, 0}, {8, 0}, {-8, 0}, {0, 8}, {0, -6}}; // {lanes over, up}: lava overhead is gone round below or beside first
    /** One step of the bore: the next column along the L opened (head, then feet), its floor put in, rails beside it over lava, lava in view plugged; then a step into it. Done within 1 of the goal. */
    /** A block can go into p: something solid beside it to set it against. */
    private final java.util.LinkedHashMap<BlockPos, BlockState> boreBuilt = new java.util.LinkedHashMap<>(); // his own build's cells this bore dug through (a highway wall into it): put back once he's through
    private final java.util.ArrayDeque<BlockPos> boreOpened = new java.util.ArrayDeque<>(); // the last cells the bore dug open (lava that comes up beside them is plugged, behind him too)
    private BlockPos boreRoundAt; // (the column a way round lava was last planned from: once a column)
    private final java.util.Map<BlockPos, BlockPos> boreStopped = new java.util.HashMap<>(); // goal -> the cell a bore toward it was stopped at (what it met: the next straight line there is judged by it, no timer)
    /** The straight line from a to the goal runs within 2 of where a bore toward that goal was stopped. */
    private boolean boreLineStopped(BlockPos a, BlockPos goal) {
        BlockPos s = boreStopped.get(goal); if (s == null) return false;
        double dx = goal.getX() - a.getX(), dz = goal.getZ() - a.getZ(), len2 = dx * dx + dz * dz; if (len2 < 1) return false;
        double u = Math.max(0, Math.min(1, ((s.getX() - a.getX()) * dx + (s.getZ() - a.getZ()) * dz) / len2)), px = a.getX() + u * dx - s.getX(), pz = a.getZ() + u * dz - s.getZ();
        boolean hit = px * px + pz * pz <= 4; if (hit) com.claudemod.ClaudeMod.LOGGER.info("[Claude route] no straight line to {} from {}: it runs by {}, where the last bore there was stopped (WorkGoal.walkTo)", goal.toShortString(), a.toShortString(), s.toShortString());
        return hit;
    }
    private static boolean boreFace(ServerLevel level, BlockPos p) {
        for (Direction d : Direction.values()) { BlockState n = level.getBlockState(p.relative(d)); if (!n.isAir() && n.getFluidState().isEmpty() && !n.canBeReplaced()) return true; }
        return false;
    }
    /** Blocks for the bore's floor, mined first, as a player tops up before a crossing: a pocket into the nearest side wall (square to the bore, within 8, a block of it in sight), its 3x3 face a step, about 9 a step and no walking about; then back to the lip, the drops swept. What's behind the face he finds as he digs (doBreak keeps its own guards: lava he knows of, a structure's blocks). False when no wall is in sight. */
    private boolean boreStock(Task.Bore t, int want, String why) {
        ServerLevel level = level(); BlockPos me = g.blockPosition(); net.minecraft.world.phys.Vec3 eye = g.getEyePosition();
        Direction side = null; int at = 99;
        for (Direction sd : new Direction[] {boreDir.getClockWise(), boreDir.getCounterClockWise(), boreDir.getOpposite()}) for (int i = 1; i <= 8 && i < at; i++) {
            BlockPos q = me.relative(sd, i);
            if (boreRock(level, q) && com.claudemod.entity.life.Perception.clear(level, eye, net.minecraft.world.phys.Vec3.atCenterOf(q.relative(sd.getOpposite())))) { side = sd; at = i; break; }
            if (!level.getBlockState(q).isAir() || !level.getBlockState(q.above()).isAir()) break;
        }
        if (side == null) return false;
        Direction across = side.getClockWise(); int slices = Math.min(12, (want + 8) / 9 + 1);
        java.util.List<Task> out = new java.util.ArrayList<>();
        for (int i = 0; i < slices; i++) {
            BlockPos c = me.relative(side, at + i);
            for (int y : new int[] {0, 1, 2}) for (int a : new int[] {0, -1, 1}) out.add(new Task.Break(c.offset(across.getStepX() * a, y, across.getStepZ() * a).immutable()));
            out.add(new Task.GoTo(c.immutable(), 0.5));
        }
        out.add(new Task.GoTo(me.immutable(), 0.5)); out.add(new Task.Pickup(Math.min(400, 60 + 10 * slices)));
        com.claudemod.ClaudeMod.LOGGER.info("[Claude bore] blocks for the floor ({}): a pocket {} slices into the {} wall {} off at {} first, about 9 a slice (WorkGoal.boreStock)", why, slices, side.getName(), at, me.relative(side, at).toShortString());
        g.tasks.addFirst(t); for (int i = out.size() - 1; i >= 0; i--) g.tasks.addFirst(out.get(i));
        return true;
    }
    /** Rock the bore floors with (it drops itself), not a structure's, not his own build. */
    private boolean boreRock(ServerLevel level, BlockPos q) {
        var s = level.getBlockState(q); boolean self = false; for (var it : com.claudemod.entity.life.FortressScout.BORE_FILL) if (s.getBlock().asItem() == it) self = true;
        return self && !com.claudemod.entity.nav.ActionPath.structureBlock(level, q) && !com.claudemod.entity.life.TempLedger.built(g, q);
    }
    /** Why the bore leaves this cell standing (null: it digs it): unbreakable, a portal or its frame (his way back), or a block his picks can't take (the dig guard refuses it). */
    private String boreKeeps(ServerLevel level, BlockPos c) {
        var cs = level.getBlockState(c);
        if (cs.getDestroySpeed(level, c) < 0) return "unbreakable";
        if (cs.is(net.minecraft.world.level.block.Blocks.NETHER_PORTAL)) return "a portal";
        if (cs.is(net.minecraft.world.level.block.Blocks.OBSIDIAN) || cs.is(net.minecraft.world.level.block.Blocks.CRYING_OBSIDIAN)) for (BlockPos q : BlockPos.betweenClosed(c.offset(-1, -1, -1), c.offset(1, 1, 1))) if (level.getBlockState(q).is(net.minecraft.world.level.block.Blocks.NETHER_PORTAL)) return "a portal's frame";
        if (cs.requiresCorrectToolForDrops() && g.inventory.count(x -> x.isCorrectToolForDrops(cs)) == 0) return "past his picks"; // (any tool on him, not just the one in his hand)
        return null;
    }
    private boolean doBore(Task.Bore t) {
        ServerLevel level = level();
        if (!t.equals(boreRun)) { boreRun = t; boreBuilt.clear(); boreDir = null; boreAxis0 = null; boreCol = null; boreTries = 0; boreYOff = 0; boreLaneOff = 0; boreDetour = 0; boreClean = 0; boreStill = 0; boreSteps = 0; boreStart = g.tickCount; boreFrom = g.blockPosition().immutable();
            com.claudemod.ClaudeMod.LOGGER.info("[Claude bore] from {} to {} at y {}: straight through, rock dug and gaps floored (WorkGoal.doBore)", boreFrom.toShortString(), t.goal().toShortString(), t.y()); }
        BlockPos me = BlockPos.containing(g.getX(), g.getY() + 0.2, g.getZ()), goal = t.goal(); // (the cell his feet are in: on soul sand, 14/16 tall, blockPosition is the soul sand's own cell, and the bore dug the ground he walked on as his step, then had no face for a floor under it: 6 min refused, live 14:50 World 17)
        boolean boreMoved = boreLast != null && !me.equals(boreLast) && boreRun == t;
        if (boreMoved) { boreSteps++; if (boreSteps % 32 == 0) com.claudemod.ClaudeMod.LOGGER.info("[Claude bore] {} blocks, at {} ({} s) (WorkGoal.doBore)", boreSteps, me.toShortString(), (g.tickCount - boreStart) / 20); } boreLast = me.immutable();
        int dx = goal.getX() - me.getX(), dz = goal.getZ() - me.getZ();
        if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1) { double s0 = Math.max(1, g.tickCount - boreStart) / 20.0; com.claudemod.ClaudeMod.LOGGER.info("[Claude bore] there: {} -> {} ({} blocks in {} s, {} blocks/s) (WorkGoal.doBore)", boreFrom.toShortString(), me.toShortString(), boreSteps, String.format("%.0f", s0), String.format("%.1f", boreSteps / s0)); boreRun = null;
            if (!boreBuilt.isEmpty()) { int n0 = 0; for (var e : boreBuilt.entrySet()) { BlockPos c = e.getKey(); if (!level.getBlockState(c).isAir()) continue; com.claudemod.entity.life.TempLedger.planBuild(g, c, "Highway.skill"); g.tasks.addFirst(new Task.Place(c, e.getValue().getBlock().asItem(), null, e.getValue())); n0++; }
                com.claudemod.ClaudeMod.LOGGER.info("[Claude bore] through {} cell(s) of his own build on the way ({}): {} put back behind him (WorkGoal.doBore)", boreBuilt.size(), boreBuilt.keySet().stream().map(BlockPos::toShortString).toList(), n0); if (n0 > 0 && boreBuilt.keySet().stream().anyMatch(c -> c.equals(me) || c.equals(me.above()))) g.tasks.addFirst(new Task.GoTo(goal, 0.6)); boreBuilt.clear(); } // (stood in the opening: on to the goal first, then the wall back) // (a highway wall left open: live 04:14 11-09 review 195, stage 927)
            return true; }
        if (boreMoved && g.onGround() && !g.tasks.isEmpty() && !(g.tasks.peekFirst() instanceof Task.GoTo) && !(g.tasks.peekFirst() instanceof Task.Bore)) { /* (not the walk queued after it: that's where the bore leads; it took over after one step, planning through the unseen rock 7 at a time, stage 832) */ g.getNavigation().stop(); g.getMoveControl().setWantedPosition(g.getX(), g.getY(), g.getZ(), 0); Task nx = g.tasks.pollFirst(); g.tasks.addFirst(t); g.tasks.addFirst(nx); return true; } // a block on with something queued (a meal): it gets its turn, his step stopped first (stage 818: Eat never ran on the walk back; a yield on every block carried him into the next column unchecked, into lava)
        if (boreDir == null || (boreDir.getAxis() == Direction.Axis.X ? dx * boreDir.getStepX() <= 0 : dz * boreDir.getStepZ() <= 0)) // the L: the longer axis first, kept till it's walked
            boreDir = Math.abs(dx) >= Math.abs(dz) ? (dx > 0 ? Direction.EAST : Direction.WEST) : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
        if (boreAxis0 == null) boreAxis0 = boreDir.getAxis();
        if (!g.onGround() && !g.isInWater() && !g.isInLava()) return false; // (mid-step: the body lands first)
        int wantY = Math.max(Math.min(me.getY(), level.getMinBuildHeight() + 5), Math.min(120, t.y() + boreYOff)); // (the floor band: never down into it, never pulled up out of it either: 3 off the bottom it climbed him into the open under a husk; stage 635)
        int ny = me.getY() + Integer.signum(wantY - me.getY());
        BlockPos laneOf = boreDir.getAxis() == boreAxis0 ? boreFrom : goal; // (the L's lanes: the first leg on his start's line, the second on the goal's)
        int lane = (boreDir.getAxis() == Direction.Axis.X ? laneOf.getZ() : laneOf.getX()) + boreLaneOff;
        int off = boreDir.getAxis() == Direction.Axis.X ? me.getZ() - lane : me.getX() - lane;
        BlockPos f = off == 0 ? me.relative(boreDir).atY(ny) : (boreDir.getAxis() == Direction.Axis.X ? me.offset(0, 0, -Integer.signum(off)) : me.offset(-Integer.signum(off), 0, 0)).atY(me.getY()); // (drifted off it, into a cave: a step back into the lane first)
        if (off != 0) ny = me.getY();
        if (off == 0 && ny <= me.getY()) { // lava level with the step (beside it, or in it): the line goes up 2 over it, a bridge over the lava, never beside it (live test 816: a rail into a lava flow had nothing to go against)
            boolean side = false; for (Direction sd : new Direction[] {boreDir.getClockWise(), boreDir.getCounterClockWise()}) for (int yy = 0; yy <= 1; yy++) if (level.getFluidState(f.relative(sd).above(yy)).is(net.minecraft.tags.FluidTags.LAVA)) side = true;
            if ((side || level.getFluidState(f).is(net.minecraft.tags.FluidTags.LAVA)) && boreYOff < me.getY() + 2 - t.y()) { // a few lava cells by the step: plugged, the line kept level; up 2 over it only for a lake, priced (D2D urgent 06:44 11-07: up 2 for a cell of lava beside a 3-block bore, into a ceiling with lava falling through it, 20 -> 10; and its floor laid over the highway's, a 27-walk detour to reach the cell under it)
                net.minecraft.world.item.Item pl = null; for (var it : com.claudemod.entity.life.FortressScout.BORE_FILL) if (g.inventory.count(x -> x.is(it)) > 0) { pl = it; break; }
                java.util.List<BlockPos> cells = new java.util.ArrayList<>(); for (BlockPos q0 : new BlockPos[] {f, f.above(), f.relative(boreDir.getClockWise()), f.relative(boreDir.getClockWise()).above(), f.relative(boreDir.getCounterClockWise()), f.relative(boreDir.getCounterClockWise()).above()}) if (level.getFluidState(q0).is(net.minecraft.tags.FluidTags.LAVA)) cells.add(q0.immutable());
                boolean faces = !cells.isEmpty(); for (BlockPos q0 : cells) if (!boreFace(level, q0)) faces = false;
                if (pl != null && faces) { int ahead = 0; for (int k = 1; k <= 16; k++) for (int w0 = -1; w0 <= 1; w0++) for (int yy = 0; yy <= 1; yy++) if (level.getFluidState(me.relative(boreDir, k).relative(boreDir.getClockWise(), w0).above(yy)).is(net.minecraft.tags.FluidTags.LAVA)) ahead++;
                    var pick = com.claudemod.entity.life.Decide.choose(g, "bore.lava_level", ahead + " lava cell(s) at the line's level in the 16 ahead", true, java.util.List.of(
                        com.claudemod.entity.life.Decide.Option.of("plug it, stay level", ahead * com.claudemod.entity.life.Weights.w("bore.plug_s", 2), "a block in each, the line on at y " + me.getY()),
                        com.claudemod.entity.life.Decide.Option.of("the line up 2 over it", com.claudemod.entity.life.Weights.w("bore.raise_s", 12), "2 up, the line over it, 2 back down after")));
                    if (pick != null && pick.name().startsWith("plug")) { g.tasks.addFirst(t); for (BlockPos q0 : cells) g.tasks.addFirst(new Task.Place(q0, pl)); com.claudemod.ClaudeMod.LOGGER.info("[Claude bore] lava level with {}: {} cell(s) of it by the step plugged, the line kept at y {} (WorkGoal.doBore)", f.toShortString(), cells.size(), me.getY()); return true; } }
            }
            if (side || level.getFluidState(f).is(net.minecraft.tags.FluidTags.LAVA)) { int up = me.getY() + 2 - t.y(); if (boreYOff < up) { boreYOff = up; boreClean = 0; com.claudemod.ClaudeMod.LOGGER.info("[Claude bore] lava level with {}: the line goes up to y {} over it (WorkGoal.doBore)", f.toShortString(), t.y() + boreYOff); } ny = me.getY() + 1; f = me.relative(boreDir).atY(ny); }
        }
        if (off == 0 && ny < me.getY()) { BlockPos dfl = me.relative(boreDir).atY(ny).below(); // back down to the line only where there's ground under the step (live 14:47:10 11-05: back on the line out over the lava, the floor 2 down had nothing to go against, the same place asked every tick)
            if (level.getBlockState(dfl).getCollisionShape(level, dfl).isEmpty() && level.getBlockState(dfl.below()).getCollisionShape(level, dfl.below()).isEmpty()) {
                ny = me.getY(); f = me.relative(boreDir).atY(ny);
                if (!f.equals(boreCol)) com.claudemod.ClaudeMod.LOGGER.info("[Claude bore] the line is down at y {}, nothing under {} to step down onto: on level at y {} till there's ground (WorkGoal.doBore)", wantY, dfl.toShortString(), ny);
            }
        }
        if (!f.equals(boreCol)) { if (++boreClean >= 16 && (boreYOff != 0 || boreLaneOff != 0)) { com.claudemod.ClaudeMod.LOGGER.info("[Claude bore] 16 clean columns at {}: back on the line (y {}, lane {} -> y {}, lane 0) (WorkGoal.doBore)", f.toShortString(), t.y() + boreYOff, boreLaneOff, t.y()); boreYOff = 0; boreLaneOff = 0; boreDetour = 0; } boreCol = f.immutable(); boreTries = 0; boreStill = 0; } // (16 clean columns past a detour: back on the line)
        java.util.List<BlockPos> way = new java.util.ArrayList<>(java.util.List.of(f, f.above()));
        if (ny > me.getY()) way.add(me.above(2)); else if (ny < me.getY()) way.add(f.above(2));
        java.util.List<Task> todo = new java.util.ArrayList<>();
        net.minecraft.world.item.Item fill = null; for (var it : com.claudemod.entity.life.FortressScout.BORE_FILL) if (g.inventory.count(x -> x.is(it)) > 0) { fill = it; break; }
        { BlockPos u1 = me.below(), u2 = me.below(2), fl0 = f.below(); // on real ground at the lip of a gap or lava: enough on him for the whole stretch first, none or some (out on it there's nothing to mine; before the plugs, which floor lava with what he has)
          if (off == 0 && !level.getBlockState(fl0).isFaceSturdy(level, fl0, Direction.UP) && level.getBlockState(u1).isFaceSturdy(level, u1, Direction.UP) && level.getBlockState(u2).isFaceSturdy(level, u2, Direction.UP)) {
            int k = 0; boolean hot = false; for (int i = 0; i < 64; i++) { BlockPos q = f.relative(boreDir, i).below(); if (level.getBlockState(q).isFaceSturdy(level, q, Direction.UP)) break; k++; for (int d = 0; d <= 3; d++) if (level.getFluidState(q.below(d)).is(net.minecraft.tags.FluidTags.LAVA)) hot = true; }
            int need = k * (hot ? 3 : 1) + 2, on = 0; for (var it : com.claudemod.entity.life.FortressScout.BORE_FILL) on += g.inventory.count(x -> x.is(it));
            if (on < need && boreStock(t, need - on, k + " across ahead takes " + need + ", " + on + " on him")) return true; } }
        // lava in view by the opened way, or in it: a block in it first (he sees what the dig showed him, never into the rock)
        java.util.List<BlockPos> mine0 = new java.util.ArrayList<>(java.util.List.of(me, me.above())); if (level.getBlockState(me.above(2)).isAir() || level.getFluidState(me.above(2)).is(net.minecraft.tags.FluidTags.LAVA)) mine0.add(me.above(2)); // (the cell over his head when it's open: a step down leaves the one he left open, and lava runs down it onto him, stage 818)
        if (fill != null) for (BlockPos c : mine0) for (Direction d : Direction.values()) { BlockPos q = c.relative(d); // lava come up to where he stands (a flow down his own tunnel, stage 818's way back): plugged before anything ahead
            if (q.equals(me) || q.equals(me.above()) || !level.getFluidState(q).is(net.minecraft.tags.FluidTags.LAVA) || !boreFace(level, q)) continue;
            com.claudemod.ClaudeMod.LOGGER.info("[Claude bore] lava at {} beside him at {}: plugged first (WorkGoal.doBore)", q.toShortString(), me.toShortString());
            g.tasks.addFirst(t); g.tasks.addFirst(new Task.Place(q.immutable(), fill)); return true; }
        if (fill != null) for (BlockPos c : boreOpened) { if (!level.getBlockState(c).isAir()) continue; for (Direction d : Direction.values()) { BlockPos q = c.relative(d); // lava come up beside a cell he dug open (behind him, or in a column left for a way round): plugged, never left a channel down his tunnel (stage 818: the fall came down his own way through the cells a way round left open)
            if (!level.getFluidState(q).is(net.minecraft.tags.FluidTags.LAVA) || !boreFace(level, q) || q.distManhattan(me) > 5) continue;
            com.claudemod.ClaudeMod.LOGGER.info("[Claude bore] lava at {} by the cell {} he opened: plugged (WorkGoal.doBore)", q.toShortString(), c.toShortString());
            g.tasks.addFirst(t); g.tasks.addFirst(new Task.Place(q.immutable(), fill)); return true; } }
        BlockPos loose = null; // lava there's nothing to set a block against (a fall in the open): no plug, the line goes round it (stage 818: the plug's column went up beside the flow and it reached him)
        for (BlockPos c : way) {
            if (level.getFluidState(c).is(net.minecraft.tags.FluidTags.LAVA)) { if (!boreFace(level, c)) loose = c; else if (fill != null) todo.add(new Task.Place(c, fill)); continue; }
            if (!level.getBlockState(c).isAir()) continue;
            for (Direction d : Direction.values()) { BlockPos q = c.relative(d); if (way.contains(q) || q.equals(me) || q.equals(me.above())) continue;
                if (level.getFluidState(q).is(net.minecraft.tags.FluidTags.LAVA)) { if (!boreFace(level, q)) loose = q; else if (fill != null) todo.add(new Task.Place(q, fill)); } }
        }
        if (loose != null) boreTries = 99; // (straight to the next way round)
        for (BlockPos c : way) if (com.claudemod.entity.nav.ActionPath.structureBlock(level, c) && !com.claudemod.entity.life.TempLedger.built(g, c)) { // a structure's blocks: kept whole, and what he was looking for is likely right here
            com.claudemod.ClaudeMod.LOGGER.info("[Claude bore] {} at {} ahead is a structure's: the bore stops at {}, structures stay whole (WorkGoal.doBore)", name(level.getBlockState(c)), c.toShortString(), me.toShortString()); boreStopped.put(t.goal().immutable(), f.immutable()); boreRun = null; return true; }
        BlockPos kept = null;
        if (todo.isEmpty()) for (BlockPos c : way) { var cs = level.getBlockState(c); if (!cs.isAir() && cs.getFluidState().isEmpty()) {
            boolean behind = false; for (Direction d : Direction.values()) { BlockPos q = c.relative(d); if (!way.contains(q) && !q.equals(me) && !q.equals(me.above()) && level.getFluidState(q).is(net.minecraft.tags.FluidTags.LAVA) && plugFace(level, c, q) == null) behind = true; } // (lava he can plug through the hole once the block's out: dug, doBreak plugs it, stage 939)
            String keep = behind ? null : boreKeeps(level, c);
            if (keep != null) { if (kept == null) { kept = c; boreTries = 99; com.claudemod.ClaudeMod.LOGGER.info("[Claude bore] {} at {} on the line is {}: round it, never queued to dig (WorkGoal.doBore)", name(cs), c.toShortString(), keep); } } // (live 20:49 World 17: a bore out of his portal's side queued its frame's obsidian with a stone pick, refused 3 times, a TRIP)
            else if (behind && loose == null) { loose = c; boreTries = 99; } // lava behind the face (he saw it come up beside the way): doBreak won't open it, so round it now (stage 818: 12 minutes of refused digs at one face)
            else if (!behind) { todo.add(new Task.Break(c)); if (com.claudemod.entity.life.TempLedger.built(g, c)) boreBuilt.putIfAbsent(c.immutable(), cs); } } }
        BlockPos fl = f.below(); var fls = level.getBlockState(fl);
        boolean overLava = level.getFluidState(fl).is(net.minecraft.tags.FluidTags.LAVA) || level.getFluidState(fl.below()).is(net.minecraft.tags.FluidTags.LAVA); // (his own floor over it still counts: its rails and wall come after it; stage 820 got none)
        if (todo.isEmpty() && (!fls.isFaceSturdy(level, fl, Direction.UP) || fls.is(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK))) {
            if (fill == null && boreStock(t, 8, "no block on him to floor " + fl.toShortString() + " with: a few dug from the rock beside him first")) return true; // (netherrack is a tenth of a second with his pick: a player mines some, never ends the way; live 21:47 11-09, D2D 207, on a floor one block thick; stage 1323)
            if (fill == null) { com.claudemod.ClaudeMod.LOGGER.info("[Claude bore] no block on him to floor {} with, and none mined from where he stands: the bore ends at {} (WorkGoal.doBore)", fl.toShortString(), me.toShortString()); boreRun = null; return true; }
            if (fls.is(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK)) todo.add(new Task.Break(fl));
            todo.add(new Task.Place(fl, fill));
        }
        BlockPos fl2 = f.relative(boreDir).below(); var fl2s = level.getBlockState(fl2); // (bridging: the floor a block further too, so a sprint's look ahead meets ground, never the drop)
        if (todo.isEmpty() && off == 0 && ny == me.getY() && fill != null && fls.isFaceSturdy(level, fl, Direction.UP) && (fl2s.isAir() || fl2s.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) && level.getBlockState(fl2.above()).isAir() && level.getBlockState(fl2.below()).getCollisionShape(level, fl2.below()).isEmpty() && fl2.closerToCenterThan(g.getEyePosition(), 4.5)
                && !(wantY < me.getY() && !level.getBlockState(fl2.below(2)).getCollisionShape(level, fl2.below(2)).isEmpty())) // (never where the line steps down next: ground 2 under it takes the line into that cell, and the block he'd put is dug back out; review 78, stage 1170)
            todo.add(new Task.Place(fl2, fill));
        if (todo.isEmpty() && overLava && fill != null) { var sh = com.claudemod.entity.life.FortressScout.shooterInSight(g); // a shooter in sight over lava: a wall block at head height on its side (the knock's way off)
            if (sh != null) { Direction side = boreDir.getClockWise(); if ((sh.getX() - g.getX()) * side.getStepX() + (sh.getZ() - g.getZ()) * side.getStepZ() < 0) side = side.getOpposite(); BlockPos w = f.relative(side).above(); if (level.getBlockState(w).isAir() && boreFace(level, w)) todo.add(new Task.Place(w, fill)); } }
        if (todo.isEmpty() && overLava && fill != null) for (Direction side : new Direction[] {boreDir.getClockWise(), boreDir.getCounterClockWise()}) { BlockPos r = f.relative(side); var rs = level.getBlockState(r); if (rs.isAir() || rs.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) todo.add(new Task.Place(r, fill)); } // (rails: a knock-back over lava lands on a block)
        if (kept != null) todo.clear(); // (the column won't open while that cell stays: round it, nothing of it queued)
        if (!todo.isEmpty() || loose != null || kept != null) {
            if (todo.isEmpty()) todo.add(kept != null ? new Task.Break(kept) : new Task.Place(loose, net.minecraft.world.item.Items.NETHERRACK)); // (for the log line: the dig or plug it won't make)
            if (++boreTries > 12) { // this column won't open (lava behind a face he won't dig, a block he can't take): over it
                String nw; { Task t0 = todo.get(0); BlockPos p0 = t0 instanceof Task.Break b0 ? b0.pos() : t0 instanceof Task.Place q0 ? q0.pos() : f; // why it won't: the cell, its refusals on file, the last exit's words (Day to Day 19:04: 122 "won't open" lines in 8 s and none said why)
                    var w0 = g.attempts.why(t0 instanceof Task.Place ? com.claudemod.entity.life.Attempts.Kind.PLACE : com.claudemod.entity.life.Attempts.Kind.BREAK, p0); var w1 = g.attempts.why(com.claudemod.entity.life.Attempts.Kind.REACH, p0);
                    nw = name(level.getBlockState(p0)) + " at " + p0.toShortString() + (w0 == null ? "" : "; refused: " + w0.reason() + (w0.detail().isEmpty() ? "" : " " + w0.detail())) + (w1 == null ? "" : "; reach: " + w1.reason() + (w1.detail().isEmpty() ? "" : " " + w1.detail())) + (loose == null ? "" : "; lava at " + loose.toShortString() + " with no face to plug it from") + "; last exit: " + (exitWhy == null ? "none said" : exitWhy) + "; " + (kept != null ? "kept, never tried: " + boreKeeps(level, kept) : loose != null && boreTries >= 99 ? "lava with no face, never tried" : boreTries + " tries"); }
                boolean hotHere = loose != null; for (BlockPos q : BlockPos.betweenClosed(f.offset(-2, -2, -2), f.offset(2, 2, 2))) if (!hotHere && level.getFluidState(q).is(net.minecraft.tags.FluidTags.LAVA)) hotHere = true;
                if (hotHere && !f.equals(boreRoundAt)) { boreRoundAt = f.immutable(); // lava in the way: the way round planned as a walk (ActionPath keeps off lava, won't dig beside a body, bridges over it) to the line past it, before the fixed lanes (stage 818: every lane began beside the same lava and the bore ended 277 short)
                    BlockPos line = new BlockPos(me.getX(), wantY, me.getZ());
                    for (int ahead : new int[] {(int) com.claudemod.entity.life.Weights.w("bore.round_ahead", 10), (int) com.claudemod.entity.life.Weights.w("bore.round_ahead", 10) * 2}) {
                        BlockPos aim = line.relative(boreDir, ahead); long t0 = System.nanoTime();
                        var rp = com.claudemod.entity.nav.ActionPath.plan(g, level, me, aim, 2.0, true, (int) com.claudemod.entity.life.Weights.w("bore.round_budget", 6000));
                        if (rp == null || rp == com.claudemod.entity.nav.ActionPath.PENDING || rp.acts().isEmpty()) { com.claudemod.ClaudeMod.LOGGER.info("[Claude bore] lava at {}: no way round to {} ({} ahead) planned ({} ms) (WorkGoal.doBore)", loose == null ? f.toShortString() : loose.toShortString(), aim.toShortString(), ahead, (System.nanoTime() - t0) / 1_000_000); continue; }
                        var scaff = g.inventory.find(com.claudemod.entity.life.ClaudeInventory::isBuildingBlock);
                        var rt = com.claudemod.entity.nav.ActionPath.tasks(rp, me, scaff.isEmpty() ? net.minecraft.world.item.Items.NETHERRACK : scaff.getItem(), aim, 2.0, level);
                        boreTries = 0; boreCol = null; g.tasks.addFirst(t); for (int i = rt.size() - 1; i >= 0; i--) g.tasks.addFirst(rt.get(i));
                        com.claudemod.ClaudeMod.LOGGER.info("[Claude bore] lava at {}: round it as a walk to {} ({} ahead): {} digs, {} puts, {} steps ({} ms), then the bore on (WorkGoal.doBore)", loose == null ? f.toShortString() : loose.toShortString(), aim.toShortString(), ahead, rp.digs(), rp.puts(), rp.walks(), (System.nanoTime() - t0) / 1_000_000);
                        return true; } }
                if (boreDetour < BORE_DETOURS.length) { int[] dt = BORE_DETOURS[boreDetour++]; boreClean = 0; boreLaneOff = dt[0]; boreYOff = dt[1]; boreCol = null; com.claudemod.ClaudeMod.LOGGER.info("[Claude bore] {} won't open ({}: {}): round it, the line {} lane(s) over and {} up (WorkGoal.doBore; at {} facing {}, off {}, want y {}, way {} {}, loose {}, kept {})", f.toShortString(), todo.get(0), nw, boreLaneOff, boreYOff, me.toShortString(), boreDir, off, wantY, way.stream().map(BlockPos::toShortString).toList(), way.stream().map(q -> name(level.getBlockState(q))).toList(), loose == null ? "-" : loose.toShortString() + " " + boreFace(level, loose), kept == null ? "-" : kept.toShortString());
                    BlockPos back = me.relative(boreDir.getOpposite(), 5); // lava in the way: 5 back down his own way first, so the turn round it starts clear of the flow (stage 818: every way round began beside the same lava)
                    if (hotHere && level.getBlockState(back).isAir() && level.getBlockState(back.above()).isAir() && level.getFluidState(back).isEmpty() && level.getBlockState(back.below()).isFaceSturdy(level, back.below(), Direction.UP)) { boreTries = 0; g.tasks.addFirst(t); g.tasks.addFirst(new Task.GoTo(back, 0.6)); return true; } }
                else { com.claudemod.ClaudeMod.LOGGER.info("[Claude bore] {} won't open, nor any way round it ({}: {}): the bore ends at {} (WorkGoal.doBore)", f.toShortString(), todo.get(0), nw, me.toShortString()); boreStopped.put(t.goal().immutable(), f.immutable()); boreRun = null; return true; }
                return false;
            }
            g.tasks.addFirst(t); for (int i = todo.size() - 1; i >= 0; i--) g.tasks.addFirst(todo.get(i));
            for (Task td : todo) if (td instanceof Task.Break b) { boreOpened.addLast(b.pos().immutable()); while (boreOpened.size() > 12) boreOpened.pollFirst(); }
            return true;
        }
        // the way is open: the step
        if (++boreStill > 60) { Direction cw0 = boreDir.getClockWise(); com.claudemod.ClaudeMod.LOGGER.info("[Claude bore] 3 s at {} ({} {} {}, ground {}, box {}) with {} open and no step taken: the bore ends; beside it {} / {}, head-side {} / {}, floor {} (WorkGoal.doBore)", me.toShortString(), String.format("%.2f", g.getX()), String.format("%.2f", g.getY()), String.format("%.2f", g.getZ()), g.onGround(), g.getBoundingBox(), f.toShortString(), name(level.getBlockState(f.relative(cw0))), name(level.getBlockState(f.relative(cw0.getOpposite()))), name(level.getBlockState(f.above().relative(cw0))), name(level.getBlockState(f.above().relative(cw0.getOpposite()))), name(level.getBlockState(f.below()))); boreStopped.put(t.goal().immutable(), f.immutable()); boreRun = null; return true; }
        BlockPos past = f.relative(boreDir); g.getNavigation().stop(); g.setSprinting(off == 0 && !level.getBlockState(past.below()).getCollisionShape(level, past.below()).isEmpty()); // (a sprint only with ground past the step: its look ahead stops him short of a drop)
        g.getLookControl().setLookAt(f.getX() + 0.5, f.getY() + 1.5, f.getZ() + 0.5);
        g.getMoveControl().setWantedPosition(f.getX() + 0.5, f.getY(), f.getZ() + 0.5, 1.3);
        if (ny > me.getY()) g.getJumpControl().jump();
        return false;
    }

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
        if (surfacing || com.claudemod.entity.life.Watchdog.airRoute(g)) return false; // Plugging must not displace the dig that lets him breathe.
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
            for (Direction d : Direction.values()) { BlockPos w0 = q.relative(d); if (level.getFluidState(w0).is(net.minecraft.tags.FluidTags.WATER)) com.claudemod.entity.life.Perception.sawCell(g, level, w0); } // the water he plugs off is water he knows is there: a plug is never a way through for a walk of his (live 01:16 11-05 his walk bent his own plug open)
            int had = g.tasks.size();
            if (inMine) g.tasks.addFirst(new Task.Break(q));
            int withDig = g.tasks.size();
            g.tasks.addFirst(new Task.Place(q, plug.getItem()));
            if (g.tasks.size() == withDig) { while (g.tasks.size() > had) g.tasks.pollFirst(); skipped++; continue; } // (a parked plug: the queue refused it, so none is queued; counted as one, the dig went back in line to plug again every tick, -95, 15, -21 parked 01:09:53-01:11:55 11-05)
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
        logs.removeIf(chopSkip::contains); logs.removeIf(q -> g.pillarBlocks.contains(q) || com.claudemod.entity.life.TempLedger.has(g, q)); /* (his own tower of logs, up in the stump's cell once the bottom log is out, is no part of the tree: live 16:20 11-08 broken from under him, towered again; audit 7, stage 1203) */ if (g.house != null) logs.removeIf(q -> g.house.isStructure(q)); // his house's log posts touching the tree are no part of it (he went at them over and over, refused each time, and the house never got its wood)
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
            chopSkip.addAll(logs); chopWhy = "no headway on it for 40 s (" + logs.size() + " logs up, the lowest at " + logs.get(0).toShortString() + ")"; logs.clear(); { var c0 = com.claudemod.entity.life.Stall.diagnose(g, b, 4.5); g.attempts.fail(com.claudemod.entity.life.Attempts.Kind.BREAK, b, g.tickCount, g.blockPosition(), c0.reason(), c0.detail(), c0.mob()); g.attempts.bandaid("the 40 s chop clock fired on the tree at " + b.toShortString() + ": " + c0.say()); }
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
            if (scaffoldBlock().isEmpty() && g.inventory.count(x -> x.is(net.minecraft.tags.ItemTags.LOGS) || x.is(net.minecraft.tags.ItemTags.PLANKS)) == 0) { // nothing to tower with, and no logs to make planks of: its top is left now, not a tower of air re-queued each tick for the 40 s no-headway clock (live 10:12 11-09, review 48, stage 1257)
                com.claudemod.ClaudeMod.LOGGER.info("[Claude life] the tree at {}: its top is over his reach and he has nothing to tower with: leaving its top for now", b.toShortString());
                chopSkip.addAll(logs); chopWhy = "its top is over his reach, nothing to tower with"; chopDown = true; g.tasks.addFirst(t); return true;
            }
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
    /** A timed-out mine pickup leaves its hole open and pending; it never makes the drop a gift to permit sealing it in. */
    private void leaveHeld() {
        BlockPos at = holdAt; holdPickup = null; holdAt = null;
        if (at == null) return;
        if (minePatch(at)) { g.pendingPatches.add(at); g.tasks.removeIf(t -> t instanceof Task.Place p && p.pos().equals(at)); com.claudemod.ClaudeMod.LOGGER.info("[Claude patch] pickup could not clear the pocket at {}: left open with its drops, repair pending (WorkGoal.leaveHeld)", at.toShortString()); return; }
        for (var i : g.level().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(at).inflate(1.5), i -> i.isAlive() && !g.isGift(i))) {
            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] couldn't get the {} out of the hole at {} in 20 s: patching over it", i.getItem().getHoverName().getString(), at.toShortString());
            g.markGift(i);
        }
    }
    private boolean minePatch(BlockPos pos) {
        return g.level().dimension() == net.minecraft.world.level.Level.OVERWORLD && com.claudemod.director.ClaudeLife.bpMode(g) && g.blueprint != null && !g.blueprint.isEmpty() && !g.blueprint.isWalk(pos) && (g.blueprint.isShell(pos) || com.claudemod.director.ClaudeLife.inHisMine(g, g.blockPosition()));
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
    private java.util.Map<BlockPos, Integer> lookedIn; // a hole he walked up to for a look in before patching it -> when
    private java.util.Map<BlockPos, Integer> lookedIn() { if (lookedIn == null) lookedIn = new java.util.HashMap<>(); if (lookedIn.size() > 256) lookedIn.clear(); return lookedIn; }
    private java.util.Map<BlockPos, Integer> sentFor() { if (sentFor == null) sentFor = new java.util.HashMap<>(); if (sentFor.size() > 256) sentFor.clear(); return sentFor; }
    private java.util.Map<BlockPos, long[]> viewTry; // a placement -> {view cell, his feet} at its last step to a cell that sees a face
    private java.util.Map<BlockPos, long[]> viewTry() { if (viewTry == null) viewTry = new java.util.HashMap<>(); if (viewTry.size() > 256) viewTry.clear(); return viewTry; }
    private Task stepOutFor; private int stepOutAt; // the placement he's nudging into his walkway for, and from which of its ticks
    private boolean stepOutPatch; // the cell he's stepping out of is a wall patch (cobble): out onto his walkway first
    private java.util.Map<BlockPos, Integer> stepOutSince; // a cell he stands in that's to be filled -> when he first stepped out of it
    private java.util.Map<BlockPos, Integer> stepOutSince() { if (stepOutSince == null) stepOutSince = new java.util.HashMap<>(); if (stepOutSince.size() > 256) stepOutSince.clear(); return stepOutSince; }
    /** The walk he was sent on for this placement failed (a WALK failure at dest since he was sent; not sent yet: one a moment ago, which his walker skips anyway). */
    /** The air round a hole in his mine's rock, when it's a closed pocket (never reaching a walk cell of his mine or him) with a block of his own build in its seal: that block, else null. */
    private BlockPos sealedByHisBuild(ServerLevel level, BlockPos hole) {
        if (!com.claudemod.director.ClaudeLife.bpMode(g) || g.blueprint == null || g.blueprint.isEmpty()) return null;
        java.util.ArrayDeque<BlockPos> open = new java.util.ArrayDeque<>(); java.util.Set<BlockPos> seen = new java.util.HashSet<>(); open.add(hole.immutable()); seen.add(hole.immutable());
        BlockPos his = null, me = g.blockPosition(); int cap = (int) w("patch.pocket_cells", 48);
        while (!open.isEmpty()) {
            BlockPos q = open.poll();
            if (g.blueprint.isWalk(q) || q.equals(me) || q.equals(me.above()) || seen.size() > cap) return null; // open to his mine, or no small pocket
            for (Direction d : Direction.values()) { BlockPos n = q.relative(d); if (seen.contains(n)) continue;
                if (level.getBlockState(n).getCollisionShape(level, n).isEmpty() || !level.getFluidState(n).isEmpty()) { seen.add(n.immutable()); open.add(n.immutable()); }
                else if (his == null && com.claudemod.entity.life.TempLedger.built(g, n)) his = n.immutable(); }
        }
        return his;
    }

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
    /** His eye on the face's own side of its plane, or under 0.7 behind it (a lean to the edge shows it, as a player bridging leans out): a ray into the block from further behind is through it (stage 942). shortcut: the lean isn't made, the click is from where he stands; upgrade with an edge-step move. */
    private static boolean faceSide(Vec3 eye, BlockPos nb, Direction face) { Vec3 c = Vec3.atCenterOf(nb); return (eye.x - c.x) * face.getStepX() + (eye.y - c.y) * face.getStepY() + (eye.z - c.z) * face.getStepZ() - 0.5 > -0.7; }
    private Vec3 visibleFacePoint(ServerLevel level, Vec3 eye, BlockPos nb, Direction d, double reach) {
        Vec3 c = Vec3.atCenterOf(nb).add(d.getOpposite().getStepX() * 0.5, d.getOpposite().getStepY() * 0.5, d.getOpposite().getStepZ() * 0.5);
        Direction.Axis ax = d.getAxis();
        Vec3 u = ax == Direction.Axis.X ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0), v = ax == Direction.Axis.Z ? new Vec3(0, 1, 0) : new Vec3(0, 0, 1);
        double[][] offs = {{0, 0}, {0.4, 0}, {-0.4, 0}, {0, 0.4}, {0, -0.4}, {0.4, 0.4}, {0.4, -0.4}, {-0.4, 0.4}, {-0.4, -0.4}};
        for (double[] o : offs) {
            Vec3 pt = c.add(u.scale(o[0])).add(v.scale(o[1]));
            if (eye.distanceTo(pt) > reach) continue;
            var r = level.clip(new net.minecraft.world.level.ClipContext(eye, pt.add(d.getStepX() * 0.45, d.getStepY() * 0.45, d.getStepZ() * 0.45), net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, g)); // (into the block: a farmland's or slab's top sits below the cell's face)
            if (r.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK && r.getBlockPos().equals(nb) && (r.getDirection() == d.getOpposite() || faceSide(eye, nb, d.getOpposite()))) return pt; // (the face he clicks, not the block from its far side: a ray into the wall from behind it "saw" the face against the hole, and he put a block through a wall, stage 942)
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

    /** A cell of his mine's blueprint within r of p (his mine's ore-hole patches are for by his mine, not out in the world). */
    private boolean nearBlueprint(BlockPos p, int r) { BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos(); for (int dx = -r; dx <= r; dx++) for (int dy = -r; dy <= r; dy++) for (int dz = -r; dz <= r; dz++) if (g.blueprint.touches(q.setWithOffset(p, dx, dy, dz))) return true; return false; }
    private boolean doPlace(Task.Place t) {
        ServerLevel level = (ServerLevel) g.level();
        BlockPos pos = t.pos();
        if (ticks == 1 && level.getFluidState(pos.above()).is(net.minecraft.tags.FluidTags.LAVA) && level.getBlockState(pos).canBeReplaced() && t.item() instanceof BlockItem && g.inventory.count(x -> x.is(t.item())) >= 2) { // under falling lava: this block stops its fall and it spreads sideways from the cell over it; an open cell of that spread that runs on to him gets a block first (death 30, live 01:39 11-09: the spread came into his cell; stage 1196)
            for (Direction d : Direction.Plane.HORIZONTAL) { BlockPos ring = pos.above().relative(d);
                if (!level.getBlockState(ring).canBeReplaced() || !level.getFluidState(ring).isEmpty() || !spreadReaches(level, ring)) continue;
                if (ring.equals(g.blockPosition()) || ring.equals(g.blockPosition().above())) continue; // (his own cell: the view rule keeps him off cells beside lava; nothing to wall here)
                if (!wallable(level, ring)) { com.claudemod.ClaudeMod.LOGGER.info("[Claude life] {} stops the lava falling at {}: its spread runs through {}, but that cell has nothing to set a block against (no face, footing or column): the stopper goes in, the spread dealt with as it comes (WorkGoal.doPlace)", pos.toShortString(), pos.above().toShortString(), ring.toShortString()); continue; } // (live 04:10 11-09, review 185: the ring queued first, waiting on the stopper, 3 times a second; stage 1231)
                com.claudemod.ClaudeMod.LOGGER.info("[Claude life] {} stops the lava falling at {}: its spread runs through {} to him at {}: a block there first (WorkGoal.doPlace)", pos.toShortString(), pos.above().toShortString(), ring.toShortString(), g.blockPosition().toShortString());
                g.tasks.addFirst(t); g.tasks.addFirst(new Task.Place(ring.immutable(), t.item())); return exit("walling the lava's spread first"); }
        }
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
        { var vs = level.getBlockState(pos); if (vs.is(net.minecraft.world.level.block.Blocks.TWISTING_VINES) || vs.is(net.minecraft.world.level.block.Blocks.TWISTING_VINES_PLANT) || vs.is(net.minecraft.world.level.block.Blocks.WEEPING_VINES) || vs.is(net.minecraft.world.level.block.Blocks.WEEPING_VINES_PLANT) || vs.is(net.minecraft.world.level.block.Blocks.CAVE_VINES) || vs.is(net.minecraft.world.level.block.Blocks.CAVE_VINES_PLANT)) { // vines in the cell: not replaceable, but one swing takes them (a player breaks them and puts the block; stage 938, live 03:58 and 04:15 11-07: "filled" 3x a second, then written off)
            g.tasks.addFirst(t); g.tasks.addFirst(new Task.Break(pos.immutable()));
            if (ticks == 1) com.claudemod.ClaudeMod.LOGGER.info("[Claude dig] {} in the cell at {}: broken first, then the {} (WorkGoal.doPlace)", name(vs), pos.toShortString(), itemName(new ItemStack(t.item())));
            return exit("vines in the cell: broken first (doPlace)"); } }
        if (!level.getBlockState(pos).canBeReplaced() && !(t.want() != null && com.claudemod.entity.life.PlaceAim.growsInto(level.getBlockState(pos), t.want()))) { placeRetries.remove(pos.below(20000)); sentFor().remove(pos); if (t.facing() != null) sentFor().remove(pos.relative(t.facing())); viewTry().remove(pos); if (stepOutSince != null) { stepOutSince.remove(pos); stepOutSince.remove(pos.below(30000)); stepOutSince.remove(pos.below(40000)); } return exit("the cell's filled already, " + name(level.getBlockState(pos)) + " there (doPlace line 2561)"); }
        { BlockPos open0 = com.claudemod.director.ClaudeLife.nookFillOpenBefore(g, level, pos); if (open0 != null) { if (ticks == 1) com.claudemod.ClaudeMod.LOGGER.info("[Claude nook] not setting {} yet: the fill at {} before it is still open (its Place didn't go in), and this one could wall it off: the next round plans again (WorkGoal.doPlace)", pos.toShortString(), open0.toShortString()); return exit("doPlace: the nook fill's order"); } }
        if (!g.pillarBlocks.isEmpty() && g.pillarBlocks.contains(pos.below()) && t.item() instanceof net.minecraft.world.item.BlockItem sb && (sb.getBlock() instanceof net.minecraft.world.level.block.BedBlock || sb.getBlock() instanceof net.minecraft.world.level.block.ChestBlock
                || sb.getBlock() instanceof net.minecraft.world.level.block.CraftingTableBlock || sb.getBlock() instanceof net.minecraft.world.level.block.AbstractFurnaceBlock)) { // a bed, chest, table or furnace on a block of his scaffold: never (the scaffold comes down)
            if (ticks == 1) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] not setting {} on his scaffold at {}", net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(t.item()).getPath(), pos.toShortString());
            return exit("doPlace line 2565");
        }
        { Integer failedAt = g.attempts.of(com.claudemod.entity.life.Attempts.Kind.PLACE).get(pos); if (failedAt != null && g.attempts.skipped(com.claudemod.entity.life.Attempts.Kind.PLACE, pos, g.tickCount, 20 * 300)) { if (ticks == 1) { com.claudemod.ClaudeMod.LOGGER.info("[Claude life] not placing at {}: it failed there {}s ago", pos.toShortString(), (g.tickCount - failedAt) / 20); com.claudemod.entity.life.MindLog.refused(g, pos, "placing (failed there lately) queued by " + com.claudemod.entity.ClaudeEntity.originOf(t)); } return true; } } // (logged: a silent skip left an ore hole open in a test and nothing said why) // failed there a moment ago (nothing to place against, out of reach): not again yet
        if (g.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(pos)) && !(current instanceof Task.Pillar) && falling()) { // dropping through the cell (down his hole, under its lid): he's out of it in a moment, no stepping out onto the rim
            fallNote("placing at " + pos.toShortString());
            return false;
        }
        if (g.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(pos)) && !(current instanceof Task.Pillar) && coverCell(pos)) { if (nudgeIntoCover(t, "in his way")) return false; return deferCoverBlock(t); } // his body over the edge of a cell of his cover: into its middle, never the step-out onto the cell beside (that put him in the box's corner, out of it, and the arrows had him fighting back)
        if (g.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(pos)) && !(current instanceof Task.Pillar)) {
            stepOutPatch = t.item() == net.minecraft.world.item.Items.COBBLESTONE || t.item() == net.minecraft.world.item.Items.COBBLED_DEEPSLATE;
            BlockPos feetC = BlockPos.containing(g.getX(), g.getY() + 0.2, g.getZ()), from = pos.equals(feetC.above()) ? feetC : pos; // (his head cell to fill: the way out is from where he stands, its floor under his feet; looked for round the head cell it found none, live 01:03:44, stage 793)
            BlockPos out = stepOutSpot(from); // standing in the hole he's to fill (a floor cell he walked into): out of it, then the block from beside, like a player
            { BlockPos lk = pos.below(30000).immutable(), rk = pos.below(40000).immutable(); Integer last = stepOutSince().get(lk); // (his last step-out try for this cell, and the fresh windows given, kept far below the world in the same map: no new fields live)
              if (last != null && g.tickCount - last > 40 && stepOutSince().getOrDefault(rk, 0) < 2) { stepOutSince().remove(pos); stepOutSince().merge(rk, 1, Integer::sum); com.claudemod.ClaudeMod.LOGGER.info("[Claude life] stepping out of the hole at {} again: {} ticks of other work since his last try, a fresh 2 s (WorkGoal.doPlace)", pos.toShortString(), g.tickCount - last); } // other work came between (the vein behind it, a pickup): its time isn't the step-out's (live 15:11:51 11-08, review 341: the 2 s started 91 ticks before, gave up at once)
              stepOutSince().put(lk, g.tickCount); }
            int since = stepOutSince().computeIfAbsent(pos.immutable(), k -> g.tickCount);
            if (out != null && g.tickCount - since <= 40) { // while his box is still in the cell (the state, not a count of 2): out of it again, a nudge onto the cell beside or a walk to the one up; 2 s of that for a one-block step
                g.tasks.addFirst(t);
                if (out.getY() == from.getY() && out.getY() == feetC.getY() && out.distManhattan(from) == 1) { g.tasks.addFirst(new Task.Wait(8, false)); g.getNavigation().stop(); g.getMoveControl().setWantedPosition(out.getX() + 0.5, out.getY(), out.getZ() + 0.5, 0.6); } // (a walk to the cell beside ends at once: a nudge instead; level with his feet only: from the floor of a hole, patching his head cell, the spot beside it is a step up, and a flat nudge pushed him into the wall 3 times a second, live 00:49 11-05: that's a walk, which jumps)
                else g.tasks.addFirst(new Task.GoTo(out, 0.6));
                return exit("first: out of the cell it goes in, then the block from beside (doPlace 2580)");
            }
            stepOutSince().remove(pos); stepOutSince().remove(pos.below(30000)); stepOutSince().remove(pos.below(40000));
            if (out != null) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] standing in the hole at {}: 2 s of stepping out to {} didn't get him out, not placing it under himself (this round; the 2 s started {} ticks ago, this try at {}, his box {}) (WorkGoal.doPlace)", pos.toShortString(), out.toShortString(), g.tickCount - since, g.position(), g.getBoundingBox()); // (live 13:19 11-08 gave up half in the hole after the ore behind it and a pickup came between: was the window spent on other work? review 258)
            for (var it = g.tasks.iterator(); it.hasNext(); ) if (it.next() instanceof Task.Place p2 && p2.pos().equals(pos)) it.remove(); return exit("where he stands: never a block under himself, out of the hole first (doPlace 2584)"); } // where he's standing: never a block there (feet and head "footings" fed each other, 76 drops in 3 minutes)
        if (ticks == 1 && !(stepOutSince != null && stepOutSince.containsKey(pos)) && placeRetries.merge(pos.below(20000).immutable(), 1, Integer::sum) > 20) { // (not while he's stepping out of the cell: that has its own 2 s) // ponytail: tries kept far below the world in placeRetries (no new fields live); a placement that keeps coming back and never takes is dropped, not a 4-minute loop (his tower roof re-queued itself every tick)
            placeRetries.remove(pos.below(20000));
            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] placing at {} has failed 20 times: dropping it", pos.toShortString());
            failed(com.claudemod.entity.life.Attempts.Kind.PLACE, pos, com.claudemod.entity.life.Attempts.Reason.UNKNOWN, "placing failed 20 times");
            return exit("doPlace line 2589");
        }
        if (com.claudemod.director.ClaudeLife.bpMode(g) && g.blueprint != null && !g.blueprint.isEmpty() && g.blueprint.isShell(pos) && !g.blueprint.isWalk(g.blockPosition()) && !g.blueprint.isWalk(g.blockPosition().above())
                && pos.distSqr(g.blockPosition()) <= 6 * 6 && !goingOnPast(pos) && (t.item() == net.minecraft.world.item.Items.COBBLESTONE || t.item() == net.minecraft.world.item.Items.COBBLED_DEEPSLATE)) { // (a patch of his wall, never a station or the bed in his nook) his mine's wall patched from inside: out in an ore hole he steps back in first (he grabbed diamonds from behind the wall and the patch sealed him outside); while he's outside it, every time (a lifetime count of 2 sealed him out the second time)
            int ri = g.blueprint.nearestRow(pos);
            BlockPos in = null; for (BlockPos q : BlockPos.betweenClosed(pos.offset(-2, -2, -2), pos.offset(2, 1, 2))) if (g.blueprint.isWalk(q) && !g.blueprint.isWalk(q.below()) && (in == null || q.distSqr(g.blockPosition()) < in.distSqr(g.blockPosition()))) in = q.immutable(); // (the walkway cell right by the patch, a branch's too: nearestRow skips branch rows and sent him 44 to the trunk and back, live 08:43 11-08, review 99, stage 1036)
            if (in == null && ri >= 0) for (BlockPos f : g.blueprint.rows().get(ri).feet()) if (in == null || f.distSqr(g.blockPosition()) < in.distSqr(g.blockPosition())) in = f; // (else the walkway cell of that row nearest him)
            if (in != null && !walkFailedSince(in, pos)) {
                if (in.getY() == g.getBlockY() && Math.hypot(g.getX() - (in.getX() + 0.5), g.getZ() - (in.getZ() + 0.5)) <= 1.6) { // a cell off: a nudge in (a walk to the cell beside spins on the spot)
                    if (stepOutFor != t) { stepOutFor = t; stepOutAt = ticks; }
                    if (ticks - stepOutAt <= 60) { g.getNavigation().stop(); g.getMoveControl().setWantedPosition(in.getX() + 0.5, in.getY(), in.getZ() + 0.5, 0.6); return false; }
                } else if (!(sentFor().get(pos) instanceof Integer sent0 && g.tickCount - sent0 < 40)) { sentFor().put(pos.immutable(), g.tickCount); g.tasks.addFirst(t); g.tasks.addFirst(new Task.GoTo(in, 1.2)); return exit("first: to the cell beside it, the nudge above does the last step (doPlace 2599)"); } // ponytail: a walk back that came back short within 2 s isn't sent again (live 22:50: from the rock over his roof, 10 sends a second for 15 s) // (to the cell beside it: the nudge above does the last step)
            }
            if (in != null && ticks == 1) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] the walk back into his mine at {} failed: patching {} from where he is", in.toShortString(), pos.toShortString()); // (once a placement: every tick it was 20 lines in 2 s, read as a conflict, 01:58:41 11-05)
        }
        // ponytail: live 13:17, his overworld mine blueprint ran on Nether patches: ore "through the hole" queued, refused as their build, 3x/s loop
        if (ticks == 1 && level.dimension() == net.minecraft.world.level.Level.OVERWORLD && com.claudemod.director.ClaudeLife.bpMode(g) && g.blueprint != null && !g.blueprint.isEmpty() && g.blueprint.roleAt(pos) != com.claudemod.entity.life.MineBlueprint.Role.WALK && nearBlueprint(pos, (int) com.claudemod.entity.life.Weights.w("mine.patch_reach", 6))) { // (by his mine only: a crafting table in a notch 140 off ran it, live 21:10 World 17, a copper vein chased hole to hole and 12 cobble spent on patches out in the world, stage 686) // (any ore-hole patch, not only his shell: the end of his branch at 16,-57,-205 has no role, and 3 diamonds went in behind it, 04:10) // the user: mine all ore, then patch: ore now showing through this hole (the vein went on behind the block he took) comes out first
            { // a look into the hole before it's closed: from back down the tunnel its inside faces are out of his sight, and "can't see ore" isn't "no ore" (live 16:12 11-06: patched from 3 back, two diamond ore sealed in behind it; the user: the vein before the patch). Only where it's blind from here, once a minute a hole
                java.util.Set<BlockPos> pk = pocketBehind(g, level, pos); int blind = 0;
                for (BlockPos p : pk) for (Direction d : Direction.values()) { BlockPos q = p.relative(d); if (pk.contains(q) || level.getBlockState(q).getCollisionShape(level, q).isEmpty() || g.blueprint.isWalk(q) || com.claudemod.director.ClaudeLife.isMineCell(g, q)) continue; if (!com.claudemod.director.ClaudeLife.canSee(g, level, q)) blind++; }
                boolean restore = t.want() != null && t.want().getBlock().asItem() == t.item(); // a route putting back the block it dug on its way: he walked through it, nothing new behind it (the look sent him 53 back into the pocket, its route dug the wall again: 4 min, live 15:48 11-07, stage 1009)
                if (!restore && blind > 0 && pk.size() < 32 /* a closed pocket: his own ore hole; an open cave (the search cap) is crossed on its floor, never climbed into, stage 78 */ && !(lookedIn().get(pos) instanceof Integer l0 && g.tickCount - l0 < 20 * 60)) {
                    BlockPos edge = null; double bd = Double.MAX_VALUE;
                    for (Direction d : Direction.Plane.HORIZONTAL) { BlockPos n = pos.relative(d); if (pk.contains(n) || !level.getBlockState(n).getCollisionShape(level, n).isEmpty()) continue; BlockPos f = level.getBlockState(n.below()).getCollisionShape(level, n.below()).isEmpty() ? n.below() : n; if (f.equals(pos) || pk.contains(f) || !(g.blueprint.isWalk(f) || com.claudemod.director.ClaudeLife.isMineCell(g, f))) continue; // (the head cell beside a high hole: his feet a block down) (from his own walkway or tunnel: a cell of the cave under his flat run is no look-out, stage 77-78) (beside it, never in it: the cell above a floor patch put his feet in the hole, down into the cave under his flat run, stage 78)
                        if (!level.getBlockState(f).getCollisionShape(level, f).isEmpty() || !level.getBlockState(f.above()).getCollisionShape(level, f.above()).isEmpty() || level.getBlockState(f.below()).getCollisionShape(level, f.below()).isEmpty()) continue;
                        double dd = f.distSqr(g.blockPosition()); if (dd < bd) { bd = dd; edge = f.immutable(); } }
                    lookedIn().put(pos.immutable(), g.tickCount);
                    if (edge != null && !edge.equals(g.blockPosition())) {
                        com.claudemod.ClaudeMod.LOGGER.info("[Claude patch] the hole at {}: {} face(s) of it out of his sight from {}: a look in from {} before it's closed (WorkGoal.doPlace)", pos.toShortString(), blind, g.blockPosition().toShortString(), edge.toShortString());
                        g.tasks.addFirst(t); g.tasks.addFirst(new Task.GoTo(edge, 0.5));
                        return exit("first: a look into the hole (doPlace)");
                    }
                }
            }
            if (g.blueprint.roleAt(pos) != null) for (Task q0 : g.tasks) if (q0 instanceof Task.Place qp && qp.pos().distManhattan(pos) == 1 && g.blueprint.roleAt(qp.pos()) == null && Vec3.atCenterOf(qp.pos()).distanceToSqr(g.getEyePosition()) > Vec3.atCenterOf(pos).distanceToSqr(g.getEyePosition()) + 0.25 && level.getBlockState(qp.pos()).canBeReplaced() && !g.attempts.within(com.claudemod.entity.life.Attempts.Kind.PLACE, qp.pos(), g.tickCount, 20 * 60)) { // a pocket is filled from its deepest cell out: his mine's cell at its mouth patched first shut the ore cells behind it off, left open (stage 568); inside the pocket the vein's own order stands
                g.tasks.remove(q0); g.tasks.addFirst(t); g.tasks.addFirst(q0);
                com.claudemod.ClaudeMod.LOGGER.info("[Claude patch] {} behind the hole at {} filled first: the pocket from its deepest cell out (WorkGoal.doPlace)", qp.pos().toShortString(), pos.toShortString());
                return exit("first: the cell behind it");
            }
            int refusedBehind = 0; java.util.List<BlockPos> behind0 = veinBehind(g, level, pos); // (live 12:14: the vein went on past the hole's own faces, the patch sealed 3 diamond ore and a drop in)
            BlockPos wetAt = level.getFluidState(pos).is(net.minecraft.tags.FluidTags.WATER) ? pos : null; for (Direction d : Direction.values()) if (wetAt == null && level.getFluidState(pos.relative(d)).is(net.minecraft.tags.FluidTags.WATER)) wetAt = pos.relative(d);
            if (wetAt != null && !behind0.isEmpty()) { // water in or by the hole: the patch first, the vein behind it waits (live 00:55 11-05: "mined first, then the patch" had him after the coal into a flooded pocket, mining from its bottom, up for air, back down; the user: plug the water, never dive for it)
                com.claudemod.ClaudeMod.LOGGER.info("[Claude ore] water at {} by the hole at {}: patched first, the {} ore cell(s) behind it left for a dry way (WorkGoal.doPlace)", wetAt.toShortString(), pos.toShortString(), behind0.size());
                behind0 = java.util.List.of();
            }
            for (BlockPos q : behind0) { // faces into the hole and its pocket only: a block touching the hole by a corner shows no face into it (03:31 a corner iron "through the hole" had him breaking the torch's wall over and over to aim at it)
                BlockState qs = level.getBlockState(q);
                if ( !com.claudemod.entity.life.MineBlueprint.ore(qs) || g.blueprint.roleAt(q) == com.claudemod.entity.life.MineBlueprint.Role.WALK) continue; // (ore in his walls too: patched past, it was sealed in; only his walkway's own ore waits for the dig)
                if (qs.requiresCorrectToolForDrops() && g.inventory.find(st -> st.isCorrectToolForDrops(qs)).isEmpty()) { refusedBehind++; com.claudemod.ClaudeMod.LOGGER.info("[Claude ore] left exposed: {} at {}, seen from {}, because no correct pick is on him; the hole stays open (WorkGoal.doPlace)", name(qs), q.toShortString(), g.blockPosition().toShortString()); continue; }
                final BlockPos qq = q.immutable();
                if (com.claudemod.entity.life.Watchdog.off(g, new Task.Break(qq))) { refusedBehind++; com.claudemod.ClaudeMod.LOGGER.info("[Claude ore] left exposed: {} at {}, seen from {}, because its dig is written off; the hole stays open (WorkGoal.doPlace)", name(qs), qq.toShortString(), g.blockPosition().toShortString()); continue; }
                if (g.attempts.within(com.claudemod.entity.life.Attempts.Kind.BREAK, qq, g.tickCount, 20 * 60) || g.attempts.within(com.claudemod.entity.life.Attempts.Kind.REACH, qq, g.tickCount, 20 * 60)) { com.claudemod.ClaudeMod.LOGGER.info("[Claude ore] {} at {} behind the hole at {}: its dig was refused a moment ago (WorkGoal.doPlace)", qs.getBlock().getName().getString(), qq.toShortString(), pos.toShortString()); refusedBehind++; continue; } // (05:02 the patch waited on a lapis he couldn't reach from there, 3 times a second)
                g.tasks.removeIf(t0 -> t0 instanceof Task.Break b0 && b0.pos().equals(qq)); // (queued further back: it comes out now, before this patch seals it)
                g.tasks.addFirst(t); g.tasks.addFirst(new Task.Place(qq, qq.getY() < 0 ? net.minecraft.world.item.Items.COBBLED_DEEPSLATE : net.minecraft.world.item.Items.COBBLESTONE)); g.tasks.addFirst(new Task.Pickup(100)); g.tasks.addFirst(new Task.Break(qq)); // World 16: every mined cell comes back, deepest first, before the mouth closes.
                com.claudemod.ClaudeMod.LOGGER.info("[Claude ore] {} at {} shows through the hole at {}: mined first, then the patch", qs.getBlock().getName().getString(), qq.toShortString(), pos.toShortString());
                return exit("first: the ore behind it (doPlace line 2612)");
            }
            for (BlockPos q0 : BlockPos.betweenClosed(pos.offset(-1, -1, -1), pos.offset(1, 1, 1))) { // a seen ore at the hole's edge or corner whose aim runs through it: mined first, as the vein behind (the patch went in first, then "his patch is over coal ore he saw: out, the ore, the patch back", live 08:31 11-09, D2D review 65, stage 1254)
                if (q0.distManhattan(pos) <= 1) continue; BlockPos q = q0.immutable(); BlockState qs = level.getBlockState(q);
                if (!com.claudemod.director.ClaudeLife.seenOre(g).contains(q) || !com.claudemod.entity.life.MineBlueprint.ore(qs) || g.blueprint == null || g.blueprint.roleAt(q) == com.claudemod.entity.life.MineBlueprint.Role.WALK || behind0.contains(q)) continue;
                if (qs.requiresCorrectToolForDrops() && g.inventory.find(st -> st.isCorrectToolForDrops(qs)).isEmpty() || com.claudemod.entity.life.Watchdog.off(g, new Task.Break(q)) || g.attempts.within(com.claudemod.entity.life.Attempts.Kind.BREAK, q, g.tickCount, 20 * 60)) continue;
                if (new net.minecraft.world.phys.AABB(pos).clip(g.getEyePosition(), Vec3.atCenterOf(q)).isEmpty()) continue; // (its aim from here runs through the hole: patched, the hole is in its way and comes back out)
                g.tasks.removeIf(t0 -> t0 instanceof Task.Break b0 && b0.pos().equals(q));
                g.tasks.addFirst(t); g.tasks.addFirst(new Task.Place(q, q.getY() < 0 ? net.minecraft.world.item.Items.COBBLED_DEEPSLATE : net.minecraft.world.item.Items.COBBLESTONE)); g.tasks.addFirst(new Task.Pickup(100)); g.tasks.addFirst(new Task.Break(q));
                com.claudemod.ClaudeMod.LOGGER.info("[Claude ore] {} at {} by the hole at {}, its aim through the hole: mined first, then the patch (WorkGoal.doPlace)", qs.getBlock().getName().getString(), q.toShortString(), pos.toShortString());
                return exit("first: the ore by the hole");
            }
            if (refusedBehind > 0) { com.claudemod.ClaudeMod.LOGGER.info("[Claude ore] not patching {}: {} ore still behind it and his dig at it refused from here: the hole stays open till the vein's out (WorkGoal.doPlace)", pos.toShortString(), refusedBehind); return exit("ore behind the hole: left open, never sealed in"); } // (the user: the whole visible vein before any patch)
        }
        // something he dropped is still lying in there (raw iron in an ore pocket, a diamond a cell deeper in the vein): the patch waits while a drop his Pickup goes for lies there;
        // that Pickup's own end (picked up, or its time out with the drop still there: left, logged) ends the wait, never a count of tries; a drop his pickup left for recover_left is no wait (00:39 11-04: the patch waited on redstone the pickup had given up, 93 TRIPs that day)
        com.claudemod.entity.life.MineBlueprint bpIn = com.claudemod.director.ClaudeLife.bpMode(g) && g.blueprint != null && !g.blueprint.isEmpty() && g.blueprint.touches(g.blockPosition()) ? g.blueprint : null; // (as his Pickup sees it from where he stands)
        final java.util.Set<BlockPos> pocket0 = pocketBehind(g, level, pos); // (a drop a cell or two deeper in the pocket is sealed in all the same: 12:14 a diamond behind the patch)
        var coveredDrops = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(pos).inflate(5), i -> i.isAlive() && !g.isGift(i) && (pocket0.contains(i.blockPosition()) || i.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(pos))));
        var inside = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(pos).inflate(5),
                i -> i.isAlive() && (i.position().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos)) <= holdR2() || pocket0.contains(i.blockPosition())) && !g.isGift(i) && !com.claudemod.entity.life.OwnEvents.waiting(g, i) && (minePatch(pos) || g.inventory.hasRoomFor(i.getItem())) && (bpIn == null || bpIn.touches(i.blockPosition()) || airJoinedToMine(level, bpIn, i.blockPosition())));
        if (!inside.isEmpty() && wsDug != null && g.tickCount - wsDugTick < 100 && pos.closerThan(wsDug, 5)) { com.claudemod.ClaudeMod.LOGGER.info("[Claude life] {} by a block of the cover he's making at {}: the cover first, the drop after the fight (WorkGoal.doPlace)", inside.get(0).getItem().getHoverName().getString(), pos.toShortString()); inside = java.util.List.of(); } // ponytail: stage 555, a pearl in the post's cell held the build (a pickup, then "into the hole for it") with two endermen on him: 20 -> 5
        boolean wetBy = !level.getFluidState(pos).isEmpty(); for (Direction d : Direction.values()) if (!level.getFluidState(pos.relative(d)).isEmpty()) wetBy = true; // a plug against lava or water: it goes in first (the flow won't wait; the drop is after, or the lava's)
        if (minePatch(pos) && inside.stream().anyMatch(i -> !junkSkipped(g, i.getItem()) && (g.inventory.hasRoomFor(i.getItem()) || isOreDrop(i.getItem()) || com.claudemod.director.ClaudeLife.lootValue(i.getItem()) >= 12 || pickupSkip(g, i) == null)) && !wetBy && !coverCell(pos)) { // (a drop his pickup won't go for, no room for it, is no wait: a cobble in a full pack held the patch 3 times a second till the spot was written off, review 290, stage 1216; ore and valuables still hold it, never sealed in) // (junk his pickup leaves is no wait: 113 give-ups on 2 gravel, live 08:26-09:01 11-07, stage 898)
            holdPickup = new Task.Pickup(400); holdAt = pos.immutable(); g.tasks.addFirst(t); g.tasks.addFirst(holdPickup); viewTry().remove(pos);
            com.claudemod.ClaudeMod.LOGGER.info("[Claude patch] {} at {} waits for {} at {}: the whole pocket picked up before closing (WorkGoal.doPlace)", t.item(), pos.toShortString(), inside.get(0).getItem().getHoverName().getString(), inside.get(0).blockPosition().toShortString());
            return exit("mine drop still in the pocket: pickup first (WorkGoal.doPlace)");
        }
        if (!inside.isEmpty() && !wetBy && com.claudemod.director.ClaudeLife.dropsWorth(inside) < w("place.hold_worth", 15) && (level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty() && level.getFluidState(pos.above()).isEmpty() || com.claudemod.entity.life.Highway.owns(g, pos))) { // (or a cell of the highway underway: its wall swapped, the netherrack popped into the hole; no way in for it, held and re-queued, live 16:31; stage 921) // junk in a cell open above: the block pushes it up onto itself, where he walks (vanilla), nothing sealed in; no climb into the hole for it (stage 836: every highway floor swap held for its own netherrack)
            if (ticks == 1) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] the {} in {} pops up onto the block when it goes in (open above, junk): placing, no hold (WorkGoal.doPlace)", inside.get(0).getItem().getHoverName().getString(), pos.toShortString());
            inside = java.util.List.of(); }
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
            if (stoneWanted && g.blueprint != null && !g.blueprint.isEmpty() && g.blueprint.isArch(pos)) { failed(com.claudemod.entity.life.Attempts.Kind.WALK, pos, com.claudemod.entity.life.Attempts.Reason.NO_BLOCKS, "the arch takes stone only"); g.pendingPatches.add(pos.immutable()); if (ticks == 1) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] the arch at {} takes {} only: waiting till he has some", pos.toShortString(), itemName(new ItemStack(t.item()))); return true; } // (the user: the arch is all cobblestone, deepslate later; never andesite or dirt)
            stack = stoneWanted ? g.inventory.find(st -> st.is(net.minecraft.world.item.Items.COBBLESTONE) || st.is(net.minecraft.world.item.Items.COBBLED_DEEPSLATE)
                    || st.is(net.minecraft.world.item.Items.STONE) || st.is(net.minecraft.world.item.Items.ANDESITE) || st.is(net.minecraft.world.item.Items.DIORITE)
                    || st.is(net.minecraft.world.item.Items.GRANITE) || st.is(net.minecraft.world.item.Items.TUFF) || st.is(net.minecraft.world.item.Items.DEEPSLATE))
                    : scaffoldBlock();
            if (stack.isEmpty()) {
                failed(com.claudemod.entity.life.Attempts.Kind.WALK, pos, com.claudemod.entity.life.Attempts.Reason.NO_BLOCKS, "the patch takes stone, not dirt"); // (the upkeep waits a minute before queueing it again: dirt on him read as filler and the stone patches came back every 3 s)
                g.pendingPatches.add(pos.immutable()); // not forgotten: filled in once he has blocks again and is back here
                com.claudemod.ClaudeMod.LOGGER.info("[Claude life] couldn't place at {}: none of {} or any filler left (will come back to it)", pos.toShortString(), itemName(new ItemStack(t.item())));
                return exit("doPlace line 2644");
            }
        }
        if (stack.getItem() instanceof BlockItem fb && fb.getBlock() instanceof net.minecraft.world.level.block.FallingBlock && net.minecraft.world.level.block.FallingBlock.isFree(level.getBlockState(pos.below()))) { // sand or gravel with nothing under it falls at once (live 12:37 11-04: a sand lid over his cover hole came down on his head and suffocated him)
            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] not putting {} at {}: nothing under it, it would fall (WorkGoal.doPlace)", itemName(stack), pos.toShortString());
            failed(com.claudemod.entity.life.Attempts.Kind.WALK, pos, com.claudemod.entity.life.Attempts.Reason.NO_FACE, "nothing under it: it would fall");
            return exit("a falling block with nothing under it (doPlace fall)");
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
            failed(com.claudemod.entity.life.Attempts.Kind.PLACE, pos, com.claudemod.entity.life.Attempts.Reason.NO_FACE, "nothing to set it against yet (a build's cell)");
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
                    { var failed = g.attempts.of(com.claudemod.entity.life.Attempts.Kind.PLACE); boolean stale = false; // ponytail: live 14:28 -47,42,-885: the column's cells refused as "failed lately", the block put back in front, the column queued again: 4x a second in his fight
                      for (BlockPos q = b; q.getY() < pos.getY() && !stale; q = q.above()) { Integer at = failed.get(q); stale = at != null && g.tickCount - at < 20 * 300; }
                      if (stale) { com.claudemod.ClaudeMod.LOGGER.info("[Claude life] not placing {} at {}: the column up to it from {} failed lately (WorkGoal.doPlace)", itemName(new ItemStack(t.item())), pos.toShortString(), b.toShortString()); failed(com.claudemod.entity.life.Attempts.Kind.PLACE, pos, com.claudemod.entity.life.Attempts.Reason.NO_FACE, "the column up to it failed lately"); return exit("column below failed lately (doPlace)"); } }
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
                { BlockPos feet0 = BlockPos.containing(g.getX(), g.getY() + 0.2, g.getZ()); if (n.equals(feet0) || n.equals(feet0.above())) { com.claudemod.ClaudeMod.LOGGER.info("[Claude life] no footing at {} for {}: it's the cell he stands in (a block there is a jump onto it, and whatever made the way he stands on digs it out again) (WorkGoal.doPlace footing)", n.toShortString(), pos.toShortString()); continue; } } // (World 17 19:19: his bore's plug got a footing in his own feet, the bore's lane, and the bore dug it out, 17 times; at -146,42,-15 footings under his feet and head fed each other)
                boolean hasSupport = false;
                for (Direction d2 : Direction.values()) { BlockPos s2 = n.relative(d2); if (!level.getBlockState(s2).getCollisionShape(level, s2).isEmpty()) { hasSupport = true; break; } }
                if (!hasSupport || g.tickCount - footings().getOrDefault(pos.immutable(), -100000) < 20 * 60) continue; // (one footing a minute per patch, not one ever: a support mined out later got none)
                if (g.attempts.within(com.claudemod.entity.life.Attempts.Kind.PLACE, n, g.tickCount, 20 * 300)) { com.claudemod.ClaudeMod.LOGGER.info("[Claude life] no footing at {} for {}: placing there failed {} s ago (WorkGoal.doPlace footing)", n.toShortString(), pos.toShortString(), (g.tickCount - g.attempts.last(com.claudemod.entity.life.Attempts.Kind.PLACE, n)) / 20); continue; } // (live 15:20:34: a footing queued where placing had failed 10 s before, refused at once, 3 times in 11 s)
                footings().put(pos.immutable(), g.tickCount);
                g.tasks.addFirst(new Task.Place(pos, t.item()));
                g.tasks.addFirst(new Task.Place(n.immutable(), t.item()));
                com.claudemod.ClaudeMod.LOGGER.info("[Claude life] nothing to place {} against: a footing at {} first", pos.toShortString(), n.toShortString());
                return exit("doPlace line 2702");
            }
            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] couldn't place at {}: nothing to place it against", pos.toShortString());
            failed(com.claudemod.entity.life.Attempts.Kind.PLACE, pos, com.claudemod.entity.life.Attempts.Reason.NO_FACE, "nothing to place it against"); // (remembered: 56 tries at floating cells in a flooded cave in 3 minutes, 40 cobble gone)
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
            if (gaveUp != null && g.attempts.skipped(com.claudemod.entity.life.Attempts.Kind.PLACE, pos, g.tickCount, 20 * 300)) return exit("out of reach a moment ago: skipped outright (the same column of placements walked him up and down his stairs e (doPlace 2716)"); // out of reach a moment ago: skipped outright (the same column of placements walked him up and down his stairs every 20 s)
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
            if (done) { failed(com.claudemod.entity.life.Attempts.Kind.PLACE, pos, com.claudemod.entity.life.Attempts.Reason.OUT_OF_REACH, "couldn't get in reach to place it"); com.claudemod.ClaudeMod.LOGGER.info("[Claude life] couldn't get in reach to place at {} (he's at {})", pos.toShortString(), g.blockPosition().toShortString()); }
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
                    if (straight) { if (ticks > 200) { com.claudemod.ClaudeMod.LOGGER.info("[Claude life] couldn't step onto {} behind the bed spot at {} to set it (he's at {}, nothing between)", stand.toShortString(), pos.toShortString(), g.blockPosition().toShortString()); failed(com.claudemod.entity.life.Attempts.Kind.PLACE, pos, com.claudemod.entity.life.Attempts.Reason.OUT_OF_REACH, "couldn't step onto the stand spot behind the bed spot"); return true; } g.getNavigation().stop(); g.getMoveControl().setWantedPosition(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 0.5); return false; }
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
                if (t.facing() == null) { BlockPos out = standP.relative(d.getOpposite()); if (!level.getBlockState(out).getCollisionShape(level, out).isEmpty()) continue; } // (a planned front needs no room past the stand: a 1-wide tunnel's wall chest faces the tunnel, its far wall a step behind him; live 03:13 11-06, the junk pair came out west + north) // (no such front: the usual placement below) // the front opens onto the room: a cell with open space past it, never the next cell of the same notch (he stood in the second chest's slot and set the pair sideways)
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
                        if (ticks > 100) { failed(com.claudemod.entity.life.Attempts.Kind.PLACE, pos, com.claudemod.entity.life.Attempts.Reason.OUT_OF_REACH, "couldn't get onto the stand spot in front of it"); com.claudemod.ClaudeMod.LOGGER.info("[Claude life] couldn't get onto {} in front of the {} spot at {}: not setting it askew, next round", standP.toShortString(), itemName(stack), pos.toShortString()); return true; }
                        g.getNavigation().stop(); g.getMoveControl().setWantedPosition(standP.getX() + 0.5, standP.getY(), standP.getZ() + 0.5, 0.5); return false;
                    }
                    if (!walkFailedSince(standP, pos)) { sentFor().put(pos.immutable(), g.tickCount); g.tasks.addFirst(t); g.tasks.addFirst(new Task.GoTo(standP, 0.8)); return exit("the walk as its own task: the whole walker, not a path call every second; again while he isn't on the spot, un (doPlace 2797)"); } // the walk as its own task: the whole walker, not a path call every second; again while he isn't on the spot, unless that walk failed
                    failed(com.claudemod.entity.life.Attempts.Kind.PLACE, pos, com.claudemod.entity.life.Attempts.Reason.OUT_OF_REACH, "the walk to its stand spot failed");
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
            if (seenFace != null && leanFor != null) { if (leanFor.equals(pos)) g.setShiftKeyDown(false); leanFor = null; } // (the lean done: up off the sneak for the step onto it)
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
                    if (sp.equals(pos) || !level.getBlockState(sp).getCollisionShape(level, sp).isEmpty() || !level.getBlockState(sp.above()).getCollisionShape(level, sp.above()).isEmpty() || level.getBlockState(sp.below()).getCollisionShape(level, sp.below()).isEmpty() || level.getFluidState(sp).is(net.minecraft.tags.FluidTags.LAVA) || level.getFluidState(sp.above()).is(net.minecraft.tags.FluidTags.LAVA) || level.getBlockState(sp.below()).getCollisionShape(level, sp.below()).max(net.minecraft.core.Direction.Axis.Y) > 1.0) continue; // (never on top of a fence or a wall: no jump gets him up there, a pillar did, its dirt left outside his farm to plant a seed from a fence post, stage 93) (farmland, a slab: something to stand on, not only a full-faced block; never a lava cell: stage 816 was nudged into one)
                    double dd = sp.distSqr(g.blockPosition()); if (dd >= vd) continue;
                    if (besideLava(level, sp)) continue; // (lava beside an open cell that hasn't flowed in is falling: plug its fall and it spreads sideways into him; death 30, live 01:39 11-09, stage 1196)
                    Vec3 e2 = Vec3.atBottomCenterOf(sp).add(0, 1.62, 0);
                    for (Direction d : Direction.values()) {
                        BlockPos nb = pos.relative(d); BlockState ns = level.getBlockState(nb);
                        if (ns.isAir() || !ns.getFluidState().isEmpty() || ns.canBeReplaced()) continue;
                        if (visibleFacePoint(level, e2, nb, d, 4.25) != null) { view = sp; vd = dd; break; }
                    }
                }
                { BlockPos fl = BlockPos.containing(g.getX(), g.getY() + 0.2, g.getZ()); // one motion a block, like a player bridging: the cell that sees the face is the one he's on (or the next), at his level or one under; he sneaks to its edge by the cell (vanilla keeps a sneaking player on it) and the place goes in the tick the face comes into view; was a nudge, an exit, a 10-tick wait and the place again, two moves a block, ~1.2 blocks/s (Loops: stages 816/819)
                  if (view != null && ticks <= 30 && view.distSqr(fl) <= 2 && view.getY() <= fl.getY() && fl.getY() - view.getY() <= 1 && Math.abs(pos.getX() - view.getX()) + Math.abs(pos.getZ() - view.getZ()) == 1 && pos.getY() == view.getY() - 1 && g.onGround() && !lavaOverCell(level, pos)) { // (the floor under his next step only: a wall cell at his level is his body's way into what it holds back, lava in 1196)
                    double ex = view.getX() + 0.5 + (pos.getX() - view.getX()) * 0.72, ez = view.getZ() + 0.5 + (pos.getZ() - view.getZ()) * 0.72;
                    g.getNavigation().stop(); g.setShiftKeyDown(true); leanFor = pos.immutable(); g.getMoveControl().setWantedPosition(ex, view.getY(), ez, 0.6);
                    return false; } }
                BlockPos feetV = BlockPos.containing(g.getX(), g.getY() + 0.2, g.getZ());
                long[] lastV = viewTry().get(pos); boolean moved = lastV == null || view == null || lastV[0] != view.asLong() || lastV[1] != feetV.asLong(); // the state: since his last step for this face he's moved, or the cell that sees one is another
                if (view != null) { BlockPos seal = sealedByHisBuild(level, pos); if (seal != null) { com.claudemod.ClaudeMod.LOGGER.info("[Claude patch] the hole at {} is shut off from his mine by his own patch at {}: no opening left, nothing to fill (the view at {} is only reached through it) (WorkGoal.doPlace)", pos.toShortString(), seal.toShortString(), view.toShortString()); return true; } } // (live 09:03-09:16 11-07: sent into a sealed vein pocket, the route dug his patch to get in and put it back, 13 min; stage 785; before the nudge too: 12:45 11-07 a view 2 off in a column his patches had walled off, nudged at, NO_FACE, back on, again, stage 1006)
                if (view != null && moved && !walkFailedSince(view, pos)) {
                    viewTry().put(pos.immutable(), new long[] {view.asLong(), feetV.asLong()});
                    BlockPos feet0 = feetV;
                    if (view.equals(feet0)) { g.getNavigation().stop(); g.getMoveControl().setWantedPosition(view.getX() + 0.5, view.getY(), view.getZ() + 0.5, 0.4); g.tasks.addFirst(t); g.tasks.addFirst(new Task.Wait(6, false)); return exit("the right block, the wrong edge of it: into the middle, where the hole is in view (doPlace 2837)"); } // the right block, the wrong edge of it: into the middle, where the hole is in view
                    boolean hotLine = false; for (int k = 1; k <= 4; k++) { BlockPos q = BlockPos.containing(g.getX() + (view.getX() + 0.5 - g.getX()) * k / 4, view.getY(), g.getZ() + (view.getZ() + 0.5 - g.getZ()) * k / 4); for (int dy = 0; dy <= 2; dy++) if (level.getFluidState(q.above(dy)).is(net.minecraft.tags.FluidTags.LAVA)) hotLine = true; } // (the nudge is a straight move: lava at his feet, head or just over it on the line, falling or not, and the walk takes it instead, round or not at all; D2D urgent 06:44 11-07: a nudge for a bore block stepped him into lava falling through 131,62,511, 20 -> 10)
                    if (!hotLine && view.distSqr(feet0) <= 4 && view.getY() <= feet0.getY() && feet0.getY() - view.getY() <= 1) { g.getNavigation().stop(); g.getMoveControl().setWantedPosition(view.getX() + 0.5, view.getY(), view.getZ() + 0.5, 0.5); g.tasks.addFirst(t); g.tasks.addFirst(new Task.Wait(10, false)); return exit("a block or two off: a nudge to " + view.toShortString() + ", a walk there lands a block short (doPlace 2838)"); } // a block or two off: a nudge, a walk there lands a block short
                    if (view.distSqr(feet0) <= 4 && view.getY() > feet0.getY()) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] the spot that sees a face to set {} against is a step up at {} (he's at {}): the walker there, its jump; a nudge only slides along the ground (WorkGoal.doPlace)", pos.toShortString(), view.toShortString(), feet0.toShortString()); // (live 07:52 and 15:20 world 14: nudged at a spot one up, he never moved, and it gave up: doPlace 2838, then 2844)
                    sentFor().put(pos.immutable(), g.tickCount); g.tasks.addFirst(t); g.tasks.addFirst(new Task.GoTo(view, 0.5)); return exit("doPlace line 2839");
                }
                viewTry().remove(pos);
                com.claudemod.ClaudeMod.LOGGER.info("[Claude life] can't see a face to set {} against from anywhere he can stand: not placing it through the wall (he's at {} eye {}, spot {}, moved since the last step: {})", pos.toShortString(), g.blockPosition().toShortString(), g.getEyePosition(), view == null ? "none" : view.toShortString(), moved);
                { StringBuilder mp = new StringBuilder(); BlockPos me5 = g.blockPosition(); // (the 5x5 round the cell, layers y-1..y+1, x across, z down: P the cell, @ him, # solid, . open, ~ fluid; so the next one can be rebuilt as a stage, D2D review 74)
                  for (int dy = -1; dy <= 1; dy++) { mp.append(" y").append(pos.getY() + dy).append(":"); for (int dz = -2; dz <= 2; dz++) { for (int dx = -2; dx <= 2; dx++) { BlockPos q = pos.offset(dx, dy, dz); var st5 = g.level().getBlockState(q);
                      mp.append(q.equals(pos) ? 'P' : q.equals(me5) || q.equals(me5.above()) ? '@' : !g.level().getFluidState(q).isEmpty() ? '~' : st5.getCollisionShape(g.level(), q).isEmpty() ? '.' : '#'); } if (dz < 2) mp.append('|'); } }
                  com.claudemod.ClaudeMod.LOGGER.info("[Claude life] NO_FACE round {} (x {}..{}, z {}..{}):{} (WorkGoal.doPlace)", pos.toShortString(), pos.getX() - 2, pos.getX() + 2, pos.getZ() - 2, pos.getZ() + 2, mp); }
                failed(com.claudemod.entity.life.Attempts.Kind.PLACE, pos, com.claudemod.entity.life.Attempts.Reason.NO_FACE, "no face in sight from anywhere he can stand");
                return exit("doPlace line 2844");
            }
        }
        if (bedAim != null && against == Direction.DOWN) hit = bedAim; // (the bed from the corner: aimed at the foot's far half)
        placedAs = stack.getItem();
        g.useOnAt(pos.relative(against), against.getOpposite(), sneak, hit); // sneak only against a chest/table/door
        if (!level.getBlockState(pos).canBeReplaced() && !coveredDrops.isEmpty()) { java.util.Map<String, Integer> cov = new java.util.LinkedHashMap<>(); for (var drop : coveredDrops) cov.merge(drop.getItem().getHoverName().getString() + " at " + drop.blockPosition().toShortString(), drop.getItem().getCount(), Integer::sum); com.claudemod.ClaudeMod.LOGGER.info("[Claude patch] {} at {} covers drops of his: {} (WorkGoal.doPlace)", itemName(stack), pos.toShortString(), cov); } // (one line: three netherrack drops under one floor block read as a conflict, stage 934)
        if (!level.getBlockState(pos).canBeReplaced()) { placeRetries.remove(pos.below(20000)); /* (it took: the count starts over; 20 over a session locked his tower cell out) */ if (stepOutSince != null) { stepOutSince.remove(pos); stepOutSince.remove(pos.below(30000)); stepOutSince.remove(pos.below(40000)); } if (sentFor != null) sentFor.remove(pos); if (viewTry != null) viewTry.remove(pos); } // (placed: what was kept for getting it in is done with)
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

    private net.minecraft.world.item.Item placedAs; // the block doPlace set down this tick (a stand-in when out of the planned one)
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
        failed(com.claudemod.entity.life.Attempts.Kind.PLACE, pos, com.claudemod.entity.life.Attempts.Reason.OUT_OF_REACH, "no stand spot gives the facing it needs");
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
        { Boolean reach = reachFor(pos); if (reach != null) return reach; } // a chest he sees, as his furnaces (review 160, live 17:59:07 11-08: inReach's 4.25-to-the-centre fallback opened a chest through the rock)
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
            if (!tower && !walled && digInAt != null && g.getY() <= digInAt.getY() - 1.9 && g.getHealth() >= 14 && ticks % 40 == 20 && hostilesNear(4).isEmpty() && !holdingNight() && !com.claudemod.director.ClaudeLife.dayHere(g.level())) { // (the night's work: by day he's on his way out) // (not while holding for the night: it widened the hole till he was out of it)
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
            boolean phantomNight = !com.claudemod.director.ClaudeLife.dayHere(g.level()) && !g.level().getEntitiesOfClass(net.minecraft.world.entity.monster.Phantom.class, g.getBoundingBox().inflate(32), m -> m.isAlive()).isEmpty();
            // in the hole, hurt, with food on him: eat it there (health comes back under cover, not out in the open)
            if (g.getHealth() < 16 && !g.isUsingItem() && ticks % 20 == 7) { var f = com.claudemod.director.ClaudeLife.foodToEat(g, true); if (f != null) { ItemStack fs = g.inventory.find(st -> st.is(f)); if (!fs.isEmpty() && g.hold(fs)) g.useHeld(); } }
            boolean weakNight = !com.claudemod.director.ClaudeLife.dayHere(g.level()) && g.level().canSeeSky(g.blockPosition()) && ((!armed() || weakKit()) || g.getHealth() < 14); // (up top: the hole holds till morning; underground the clock means nothing, the wait ends when the crowd has gone: walled in at the face 8 minutes for "night") // nothing proper to fight with or in, or hurt, and it's dark: the hole is the place till morning, whether or not a mob happens to be in range right now (he came out at 6 health when they wandered off, and got hit to 2)
            java.util.List<net.minecraft.world.entity.Mob> lately = com.claudemod.entity.life.Perception.lately(g, 16); // (what he sensed lately counts as still about: under the lid the zombie on top is only heard now and then)
            if (ticks % 100 == 1 && !lately.isEmpty() && hostilesNear(16).isEmpty()) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] in his cover: {} sensed lately and not since, still about to him (doWait)", lately.get(0).getType().getDescription().getString());
            boolean hurtAndHunted = ((g.getHealth() < 8 && com.claudemod.director.ClaudeLife.foodToEat(g, true) == null && (!lately.isEmpty() || !(g.getBlockY() >= g.level().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, g.getBlockX(), g.getBlockZ()) - 4 && !com.claudemod.director.ClaudeLife.dayHere(g.level()) ? enemiesNear(24) : hostilesNear(16)).isEmpty() || enemiesNear(16).size() >= 3)) || phantomNight || weakNight) && (ticks < 20 * 600 || !com.claudemod.director.ClaudeLife.dayHere(g.level())); // the 10-minute cap is a daytime thing: at night the hole holds till morning, however long (the cap dumped him out at 6 health into the mobs) // (underground: only what's after him; a cave always has something within 24, and he sat at 2 health on his tower 4+ minutes, never healing, never going for food)
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
            if (g.getHealth() < 8 && !hostilesNear(12).isEmpty() && !(com.claudemod.director.ClaudeLife.dayHere(g.level()) && ticks > 20 * w("cover.leave_first_s", 5) && digInAt != null && !tunnelOut().contains(digInAt))) return false; // (by day, 45 s in and no tunnel out tried yet: the tunnel-out below gets its go; walled in a cave at 6 health he sat 5 minutes with zombies that never burn down there) // at a few hearts with mobs still right there: the cover holds, however long (coming down at 1 health into them is a death, not a plan)
            if (ticks % 100 == 51 && hostilesNear(8).isEmpty()) lately.stream().filter(m -> m instanceof net.minecraft.world.entity.monster.Creeper || m.distanceTo(g) <= 8).findFirst().ifPresent(m -> com.claudemod.ClaudeMod.LOGGER.info("[Claude life] staying in his cover: {} heard about {} off lately, out of sight (a creeper waits under you) (doWait)", m.getType().getDescription().getString(), (int) m.distanceTo(g))); if (ticks % 20 == 11) for (var m : lately) if (m instanceof net.minecraft.world.entity.monster.Creeper && !com.claudemod.entity.life.Perception.seesNow(g, m)) { Vec3 at = com.claudemod.entity.life.Perception.lastAt(g, m); if (at != null) { g.getLookControl().setLookAt(at.x, at.y + 0.9, at.z); break; } } // (a look at where it was: seen empty, it has gone; seen there, it stays)
            String shooter = shooterAbout(); // a skeleton he sees, hears or sensed lately: what a box is for; it stays up while that's about, whatever the wait's own clock says (live 04:43: down after 7 s with the skeleton 9-15 off)
            if (ticks > 40 && g.commandActive && g.getHealth() >= 14 && hostilesNear(6).isEmpty() && shooter == null && !hurtAndHunted && !holdingNight()) { com.claudemod.ClaudeMod.LOGGER.info("[Claude life] their order waiting, health {}, nothing within 6: out of his cover at {} for it (WorkGoal.doWait)", (int) g.getHealth(), digInAt == null ? "?" : digInAt.toShortString()); return true; } // (17:08 geared by them, ordered "go" twice, he held 3 min for a phantom)
            if (ticks > 40 && hostilesNear(8).isEmpty() && shooter == null && !hurtAndHunted && !holdingNight() && !nightShelter() && lately.stream().noneMatch(m -> !(m instanceof net.minecraft.world.entity.monster.Phantom && armed() && !weakKit()) && (m instanceof net.minecraft.world.entity.monster.Creeper || m.distanceTo(g) <= 8))) { /* (a phantom overhead doesn't pin an armed, armored player in a hole) */ com.claudemod.ClaudeMod.LOGGER.info("[Claude life] nothing about his cover at {} any more (none in sight, heard or sensed lately): out after {} s (WorkGoal.doWait)", digInAt == null ? "?" : digInAt.toShortString(), ticks / 20); return true; } // quiet out there: back to it (not at night with no gear: quiet now is a zombie in a minute; every rule that walked him out of his hole died to it)
            boolean caveCover = digInAt != null && underground((ServerLevel) g.level(), digInAt); // (down here the sun never clears them: day or night, the minute is the same)
            if ((com.claudemod.director.ClaudeLife.dayHere(g.level()) || caveCover) && g.getHealth() >= 14 && ticks > 20 * 60 && !phantomNight && !holdingNight()) { com.claudemod.ClaudeMod.LOGGER.info("[Claude life] {}, health {}, a minute in his cover with {} still about: out to deal with it (WorkGoal.doWait)", caveCover ? "in a cave" : "daylight", (int) g.getHealth(), shooter != null ? shooter : "something"); return true; } // (six minutes in a hole at 17 health in broad daylight is not a player)
            // hurt in his cover by day (or under rock, where the clock means nothing), something camped about: hold, or tunnel out under the
            // rock toward where he's going, priced: the hold by what it heals (nothing, with no food and food under 18: then it only waits
            // the camper out), each tunnel by its digs, the cells a mob there sees into, and the trip left after it (World 17 05:24: 5 min
            // at 2 HP in the Nether healing nothing, then 16 blocks "away" and up on an open ledge in the skeleton's view, dead; stage 769)
            if (com.claudemod.director.ClaudeLife.dayHere(g.level()) && g.getHealth() < 14 && ticks > 20 * w("cover.leave_first_s", 5) && ticks % 20 == 0 && digInAt != null && (walled || g.getY() <= digInAt.getY() - 1) && !enemiesNear(24).isEmpty() && !tunnelOut().contains(digInAt) && leaveCover(ticks)) return true;
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

    /** Out of his cover hurt with something camped about: hold vs a 16-block tunnel each way, priced (Decide "cover.leave"). True: tunnel queued. */
    private boolean leaveCover(int ticks) {
        ServerLevel lv = (ServerLevel) g.level(); BlockPos feet = g.blockPosition(); var near = enemiesNear(24);
        BlockPos dest = null; for (Task x : g.tasks) if (x instanceof Task.GoTo gt) dest = gt.pos(); // where he's on his way to
        double hp = g.getHealth(), want = Math.max(0, w("cover.leave_hp", 14) - hp), perHit = 0; for (var m : near) perHit = Math.max(perHit, mobHit(m));
        double heal = com.claudemod.entity.life.Food.regenS(g, want), wait = w("cover.camper_leaves_s", 300);
        java.util.List<com.claudemod.entity.life.Decide.Option> opts = new java.util.ArrayList<>();
        opts.add(com.claudemod.entity.life.Decide.Option.of("hold", Math.min(heal, wait) + (dest == null ? 0 : Math.sqrt(feet.distSqr(dest)) * w("cover.trip_block_s", 1.5)), heal < wait ? "heals to " + (int) (hp + want) + " in ~" + Math.round(heal) + " s" : "heals nothing (food " + g.getFoodData().getFoodLevel() + ", none on him): waits the camper out"));
        int len = (int) w("cover.tunnel_blocks", 16); java.util.Map<String, Integer> lens = new java.util.HashMap<>();
        for (int depth : new int[] {0, (int) w("cover.tunnel_down_blocks", 3)}) for (Direction d : Direction.Plane.HORIZONTAL) { // (level, or down under the floor first: under a cave's floor nothing out there sees in)
            String name = (depth > 0 ? "down " + depth + ", " : "") + "tunnel " + d.getName(); BlockPos base = feet.below(depth);
            double dig = 0; int seen = 0, n = 0; String no = null;
            for (int k = 1; k <= depth && no == null; k++) { BlockPos c = feet.below(k); BlockState st = lv.getBlockState(c); float pr = st.getDestroyProgress(g, lv, c); if (!lv.getFluidState(c).isEmpty() || pr <= 0 || st.hasBlockEntity()) no = "the way down " + k + " under him won't dig"; else dig += 1 / pr / 20.0 + w("cover.dig_step_s", 0.25); }
            boolean stop = false; int least = (int) w("cover.tunnel_min_blocks", 4);
            for (int i = 1; i <= len && no == null && !stop; i++) {
                if (dest != null && base.relative(d, i - 1).distSqr(dest) <= 4) break; // (there already)
                n = i; String block = null;
                for (BlockPos c : new BlockPos[] {base.relative(d, i), base.relative(d, i).above()}) {
                    BlockState st = lv.getBlockState(c);
                    if (!lv.getFluidState(c).isEmpty() || st.is(net.minecraft.world.level.block.Blocks.BEDROCK) || st.hasBlockEntity()) { block = "fluid or unbreakable " + i + " in"; break; }
                    float pr = st.getDestroyProgress(g, lv, c);
                    if (!st.getCollisionShape(lv, c).isEmpty()) { if (pr <= 0 || st.requiresCorrectToolForDrops() && g.inventory.find(x -> x.isCorrectToolForDrops(st)).isEmpty()) { block = st.getBlock().getName().getString() + " " + i + " in, past his tools"; break; } dig += 1 / pr / 20.0 + w("cover.dig_step_s", 0.25); }
                    if (opensToThem(lv, c, base, d, depth, feet, near)) seen++;
                }
                if (block == null && lv.getBlockState(base.relative(d, i).below()).getCollisionShape(lv, base.relative(d, i).below()).isEmpty()) { block = "no floor " + i + " in (" + base.relative(d, i).below().toShortString() + ")"; }
                if (block != null) { if (i - 1 >= least) { n = i - 1; stop = true; } else no = block; } // (short of what stops it: still out of their reach and sight, if it's long enough to be)
            }
            if (no != null || n == 0) { opts.add(com.claudemod.entity.life.Decide.Option.out(name, no == null ? "goes nowhere nearer" : no)); continue; }
            BlockPos end = base.relative(d, n); lens.put(name, n);
            double rest = dest == null ? 0 : Math.sqrt(end.distSqr(dest)) * w("cover.trip_block_s", 1.5);
            double hits = seen * w("cover.seen_cell_hits", 0.5);
            opts.add(com.claudemod.entity.life.Decide.Option.of(name, dig + n * w("cover.tunnel_walk_s", 0.3) + rest + (hits > 0 ? com.claudemod.entity.life.Decide.hpS(g, hp, 0, hits, perHit, Integer.MAX_VALUE) : 0), n + " blocks, ~" + Math.round(dig) + " s digging, " + seen + " cell(s) in their sight" + (dest == null ? "" : ", " + (int) Math.sqrt(end.distSqr(dest)) + " left to " + dest.toShortString())));
        }
        var pick = com.claudemod.entity.life.Decide.choose(g, "cover.leave", "health " + (int) hp + ", " + near.size() + " about" + (dest == null ? "" : ", on his way to " + dest.toShortString()), false, opts);
        if (pick != null && (ticks % 600 == 0 || !pick.name().equals(lastLeave))) com.claudemod.entity.life.Decide.choose(g, "cover.leave", "health " + (int) hp + ", " + near.size() + " about" + (dest == null ? "" : ", on his way to " + dest.toShortString()), true, opts);
        lastLeave = pick == null ? "hold" : pick.name();
        if (pick == null || "hold".equals(pick.name())) return false;
        int depth = pick.name().startsWith("down ") ? Integer.parseInt(pick.name().substring(5, pick.name().indexOf(','))) : 0; Direction d = Direction.byName(pick.name().substring(pick.name().lastIndexOf(' ') + 1)); int n = lens.get(pick.name());
        BlockPos end = feet.below(depth).relative(d, n);
        leaveBore = new Task.Bore(end.immutable(), end.getY());
        java.util.List<Task> dig = new java.util.ArrayList<>(); for (int k = 1; k <= depth; k++) dig.add(new Task.Break(feet.below(k).immutable())); dig.add(leaveBore); // (the bore's own steps: dug and walked as one, no walker to turn it back at the camper; no coming up at the end: the camper follows him along the top, what's next is the planner's)
        tunnelOut().add(digInAt.immutable()); g.tasks.removeAll(digInTasks);
        for (int i = dig.size() - 1; i >= 0; i--) g.tasks.addFirst(dig.get(i));
        com.claudemod.ClaudeMod.LOGGER.info("[Claude life] hurt in his cover with {} about: {}, {} blocks (WorkGoal.leaveCover)", near.size(), pick.name(), n);
        return true;
    }
    /** A tunnel cell that, dug, opens onto air one of them sees (from where it stands or a step or two round it: a camper moves): the open side of the tunnel is where it sees him and gets in. */
    private boolean opensToThem(ServerLevel lv, BlockPos c, BlockPos base, Direction d, int depth, BlockPos feet, java.util.List<net.minecraft.world.entity.Mob> near) {
        for (Direction dd : Direction.values()) {
            BlockPos n = c.relative(dd);
            if (dd == Direction.DOWN || n.equals(feet) || n.equals(feet.above()) || n.getX() == feet.getX() && n.getZ() == feet.getZ() && n.getY() < feet.getY() && n.getY() >= feet.getY() - depth) continue; // (his own cover and shaft)
            int along = d.getAxis() == Direction.Axis.X ? (n.getX() - base.getX()) * d.getStepX() : (n.getZ() - base.getZ()) * d.getStepZ();
            boolean onLine = (d.getAxis() == Direction.Axis.X ? n.getZ() == base.getZ() : n.getX() == base.getX()) && along >= 1 && (n.getY() == base.getY() || n.getY() == base.getY() + 1);
            if (onLine || !lv.getBlockState(n).getCollisionShape(lv, n).isEmpty()) continue; // (the tunnel itself, or rock: closed)
            Vec3 nc = Vec3.atCenterOf(n);
            for (var m : near) for (Vec3 off : new Vec3[] {Vec3.ZERO, new Vec3(2, 0, 0), new Vec3(-2, 0, 0), new Vec3(0, 0, 2), new Vec3(0, 0, -2)}) {
                Vec3 eye = m.getEyePosition().add(off); BlockPos eb = BlockPos.containing(eye); if (!lv.getBlockState(eb).getCollisionShape(lv, eb).isEmpty()) continue;
                if (eb.equals(n) || lv.clip(new net.minecraft.world.level.ClipContext(eye, nc, net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, m)).getType() == net.minecraft.world.phys.HitResult.Type.MISS) return true;
            }
        }
        return false;
    }
    private String lastLeave = ""; private Task leaveBore; // (the tunnel out of his cover: under rock, out of their sight, it is the cover while it runs)

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
        boolean junkBox = com.claudemod.director.ClaudeLife.isJunkChest(g, t.pos()); java.util.Map<net.minecraft.world.item.Item, Integer> kept = new java.util.HashMap<>(); final boolean deepslateWanted = com.claudemod.director.ClaudeLife.deepslateWanted(g);
        for (int i = 0; i < g.inventory.items.getContainerSize(); i++) {
            ItemStack s = g.inventory.items.getItem(i);
            if (s.isEmpty()) continue; if (s.is(net.minecraft.tags.ItemTags.LOGS) && !junkBox) { if (kept.merge(net.minecraft.world.item.Items.OAK_LOG, s.getCount(), Integer::sum) > 64 + s.getCount() - 1 && kept.get(net.minecraft.world.item.Items.OAK_LOG) - s.getCount() >= 64) slots.add(i); continue; } if (keeps(s)) continue; // (logs: a stack of 64 stays (the user), , whole stacks past that go in the chest; the user: no pack full of blocks)
            boolean cob = s.is(net.minecraft.world.item.Items.COBBLESTONE) || s.is(net.minecraft.world.item.Items.COBBLED_DEEPSLATE);
            if (cob && s.is(net.minecraft.world.item.Items.COBBLED_DEEPSLATE) && deepslateWanted) { kept.merge(net.minecraft.world.item.Items.COBBLESTONE, 1, Integer::sum); continue; } /* (and it counts as his kept stone, as spareStacks counts it: cobble past it is spare. D2D review 093255 11-07: 7 stacks of highway deepslate, 2 of cobble "kept", 0 in, the trip again every 10 s for a minute) */ // (a job's material on him: kept, never the junk chest's, live 16:33 11-06)
            if (junkBox) { if (cob ? kept.merge(net.minecraft.world.item.Items.COBBLESTONE, 1, Integer::sum) > 2 : com.claudemod.director.ClaudeLife.junkItem(s) && !(s.is(net.minecraft.world.item.Items.ROTTEN_FLESH) && com.claudemod.director.Kit.foodAsk(g) != null)) slots.add(i); continue; } // his junk chest: the junk and the stone past two stacks all told, as his toss keeps (kept for patching; 2 a kind left 2 stacks for the toss to throw on his floor, 05:17 11-05, stage 713)
            if (cob) continue; // for patching, and spare cobble never goes on the shelves: his junk chest takes what he can't carry (his chests filled up with it and "store" walked him 240 blocks to your chest)
            if (com.claudemod.director.ClaudeLife.junkItem(s)) continue; // (andesite and dirt on his loot shelves filled his stash)
            if (com.claudemod.director.ClaudeLife.neededByPlan(g, s.getItem())) continue; // (a queued step needs it: it stays on him, never stored and fetched back; review 73, stage 1213)
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
                if (com.claudemod.director.ClaudeLife.neededByPlan(g, s.getItem())) continue; // (kept for a queued step, as the store above keeps it: counted here it sent him chest to chest with 10 iron, 63 trips in 18 min, reviews 35/38, stage 1227)
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
                || s.is(net.minecraft.world.item.Items.TORCH) || s.is(net.minecraft.world.item.Items.CRAFTING_TABLE) || s.is(net.minecraft.world.item.Items.FURNACE) || s.is(net.minecraft.world.item.Items.CHEST)
                || s.is(net.minecraft.world.item.Items.COAL) || s.is(net.minecraft.world.item.Items.CHARCOAL) || s.is(net.minecraft.world.item.Items.STICK)
                || s.is(net.minecraft.tags.ItemTags.PLANKS) || s.is(net.minecraft.tags.ItemTags.LOGS)
                || s.is(net.minecraft.world.item.Items.ARROW) || s.is(net.minecraft.world.item.Items.ENDER_PEARL) || s.is(net.minecraft.world.item.Items.ENDER_EYE); // ammo and the dragon run's pearls/eyes ride with him like torches (live 01:16 World 15: took 32 arrows, the stash put all 32 back) (diamonds, iron and raw iron go IN the chest: the user's rule, valuables stay safe there and come back out for a craft) // ponytail: chests ride with him too: stashed, he had none left for a junk chest in the tunnel wall and threw 6 stacks of deepslate on his mine floor (live 22:27)
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
                if (!fuel.isEmpty()) { furnace.setItem(1, fuel.split(Math.min(fuelCount, fuel.getCount()))); fuelLine(t.pos(), furnace.getItem(1), n); }
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
            g.inventory.add(out); com.claudemod.entity.life.Watchdog.noteMade(g);
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
        if (coal == 0 && fuelHeld == 0 && g.inventory.count(s -> s.is(net.minecraft.tags.ItemTags.PLANKS)) * 3 / 2 < want) { // (logs cooking too: the spare ones, never whole logs in the fuel slot; the user 11-04 "turn them into planks first")
            // no coal: logs into planks first (a log burns 1.5 items, its 4 planks 6)
            ItemStack log = g.inventory.find(s -> s.is(net.minecraft.tags.ItemTags.LOGS));
            if (!log.isEmpty()) {
                String planks = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(log.getItem()).getPath().replace("_log", "_planks").replace("stripped_", "").replace("_wood", "_planks");
                var pi = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.withDefaultNamespace(planks));
                int planksFor = Math.max(1, (want * 2 + 2) / 3); if (inputWood) planksFor = Math.min(planksFor, 4 * (g.inventory.count(s -> s.is(net.minecraft.tags.ItemTags.LOGS)) - want)); // planks to make, not crafts: craft() counts items ("(want+5)/6" made 4 of the 6 needed and whole logs went in as fuel) ; the logs to cook stay logs
                if (pi != net.minecraft.world.item.Items.AIR && planksFor > 0) com.claudemod.entity.life.Crafting.craft(level, g.inventory, pi, planksFor, false);
            }
        }
        if (coal == 0 && fuelHeld == 0) {
            int wood = g.inventory.count(s -> s.is(net.minecraft.tags.ItemTags.LOGS) || s.is(net.minecraft.tags.ItemTags.PLANKS));
            int sticks = g.inventory.count(s -> s.is(net.minecraft.world.item.Items.STICK)); // half an item each: the smelt skill counts them as fuel, so must this (live 22:44 11-04: sticks only, smelt said "smelting", this said "not enough wood", round in circles)
            boolean inputIsWood = new ItemStack(t.input()).is(net.minecraft.tags.ItemTags.LOGS) || new ItemStack(t.input()).is(net.minecraft.tags.ItemTags.PLANKS);
            want = Math.min(want, inputIsWood ? (wood * 3 + sticks) / 5 : (wood * 3 + sticks) / 2);
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
            ItemStack planks = g.inventory.find(s -> s.is(net.minecraft.tags.ItemTags.PLANKS) && !s.is(net.minecraft.tags.ItemTags.NON_FLAMMABLE_WOOD));
            if (!planks.isEmpty() && planks.getCount() * 3 / 2 >= total) { fuel = planks; need = (total * 2 + 2) / 3; }
        }
        if (fuel.isEmpty()) { fuel = g.inventory.find(s -> s.is(net.minecraft.tags.ItemTags.PLANKS) && !s.is(net.minecraft.tags.ItemTags.NON_FLAMMABLE_WOOD)); need = (total * 2 + 2) / 3; for (int i = 0; i < g.inventory.items.getContainerSize(); i++) { ItemStack pk = g.inventory.items.getItem(i); if (pk.is(net.minecraft.tags.ItemTags.PLANKS) && !pk.is(net.minecraft.tags.ItemTags.NON_FLAMMABLE_WOOD) && pk.getCount() >= need - fuelSlot.getCount() && (fuel.getCount() < need - fuelSlot.getCount())) fuel = pk; } } // planks before whole logs; a stack that covers it (live 01:27 11-05: 3 oak planks found first, 8 birch ones just made, 5 whole logs went in)
        if (fuel.isEmpty() || (fuel.is(net.minecraft.tags.ItemTags.PLANKS) && fuel.getCount() < need - fuelSlot.getCount() && fuelSlot.isEmpty())) { // (3 planks for 7 iron burnt out halfway, the rest raw in there for good: logs when the planks won't cover it)
            ItemStack logs = g.inventory.find(s -> s.is(net.minecraft.tags.ItemTags.LOGS_THAT_BURN));
            if (!logs.isEmpty() || fuel.isEmpty()) { fuel = logs; need = (total * 2 + 2) / 3; }
        }
        if (fuel.isEmpty()) { fuel = g.inventory.find(s -> s.is(net.minecraft.world.item.Items.STICK)); need = total * 2; } // ponytail: the user 16:50, crimson/warped wood never burns but its sticks do (half an item each): the Nether's fuel
        if (!fuel.isEmpty() && (fuelSlot.isEmpty() || ItemStack.isSameItemSameComponents(fuelSlot, fuel))) {
            int add = Math.max(0, Math.min(need - fuelSlot.getCount(), fuel.getCount()));
            if (add > 0) {
                if (fuelSlot.isEmpty()) furnace.setItem(1, fuel.split(add)); else { fuelSlot.grow(add); fuel.shrink(add); }
                fuelLine(t.pos(), furnace.getItem(1).copyWithCount(add), total);
            }
        }
        furnace.setChanged();
        g.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    /** What went in as fuel (the bug log: whole logs burnt are a LOG_FUEL incident). */
    private void fuelLine(BlockPos at, ItemStack fuel, int items) {
        com.claudemod.ClaudeMod.LOGGER.info("[Claude smelt] fuel at {}: {} {} for {} items{}", at.toShortString(), fuel.getCount(), itemName(fuel), items, fuel.is(net.minecraft.tags.ItemTags.LOGS) ? " (whole logs)" : "");
    }

    /** Wait at a loaded furnace until it has cooked everything, then take the results. */
    /**
     * Into reach of a block he works with (a furnace, a chest, a door), seeing it: a walk to a spot beside it, then a nudge the
     * last block. null once he's there; false while on the way; true when he can't get there (said once).
     */
    /** Nothing solid on the straight line from his cell to {@code to} at his legs or head: a nudge gets there. */
    private boolean openStep(ServerLevel level, BlockPos from, BlockPos to) {
        for (double hy : new double[] {0.5, 1.5}) { Vec3 a = new Vec3(from.getX() + 0.5, from.getY() + hy, from.getZ() + 0.5), b = new Vec3(to.getX() + 0.5, to.getY() + hy, to.getZ() + 0.5);
            if (level.clip(new net.minecraft.world.level.ClipContext(a, b, net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, g)).getType() != net.minecraft.world.phys.HitResult.Type.MISS) return false; }
        return true;
    }
    private Boolean reachFor(BlockPos target) {
        ServerLevel level = (ServerLevel) g.level();
        if (inReach(target) && blockInTheWay(level, target) == null) return null;
        if (inReach(target)) { BlockPos soft = blockInTheWay(level, target); var ss = soft == null ? null : level.getBlockState(soft); // grass or a flower between him and it: one punch, like a player (live 14:08 11-07: tall grass in front of his furnace, 4 cooked porkchop left in it, off hunting hoglins, dead)
            if (ss != null && ss.getDestroySpeed(level, soft) == 0 && ss.getFluidState().isEmpty() && (ss.canBeReplaced() || ss.is(net.minecraft.tags.BlockTags.FLOWERS))) {
                g.getLookControl().setLookAt(soft.getX() + 0.5, soft.getY() + 0.5, soft.getZ() + 0.5); g.swing(InteractionHand.MAIN_HAND); g.gameMode.destroyBlock(soft);
                com.claudemod.ClaudeMod.LOGGER.info("[Claude life] {} at {} between him and the {} at {}: punched it out (WorkGoal.reachFor)", name(ss), soft.toShortString(), name(level.getBlockState(target)), target.toShortString());
                return false; }
            if (ss != null && g.currentTask != null && ss.getDestroySpeed(level, soft) >= 0 && (ss.getDestroySpeed(level, soft) <= com.claudemod.entity.life.Weights.w("reach.dig_in_way_hardness", 0.6) || digsInTheWay(level, soft, ss) || ss.is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE) && g.myStations.contains(soft)) /* (his own table by it: broken and picked up, live 00:00 11-08) */ && ss.getFluidState().isEmpty() && !ss.hasBlockEntity() && clearViewSpot(level, target) == null && !com.claudemod.entity.life.PortalMap.frameBlock(g, soft)) { // dirt or gravel over it and no spot that sees it: dug off, like a player (live 23:24 and 23:32 11-07: "in reach: true, in the way: dirt", nudged 10 s into his own cell twice, the charcoal left, the logs burnt out; D2D review 298/368, stage 1021)
                g.tasks.addFirst(g.currentTask); g.tasks.addFirst(new Task.Break(soft.immutable())); g.getNavigation().stop(); if (ss.is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE)) liftFor().add(soft.immutable());
                com.claudemod.ClaudeMod.LOGGER.info("[Claude life] {} at {} between him and the {} at {}, no spot that sees it: digging it off, then the job (WorkGoal.reachFor)", name(ss), soft.toShortString(), name(level.getBlockState(target)), target.toShortString());
                exitWhy = "the " + name(ss) + " over the " + name(level.getBlockState(target)) + " dug off first"; return true; } }
        BlockPos side = clearViewSpot(level, target); if (side == null) side = standSpotBeside(target);
        BlockPos feet = BlockPos.containing(g.getX(), g.getY() + 0.2, g.getZ());
        if (side.distSqr(feet) <= 4 && side.getY() == feet.getY() && openStep(level, feet, side)) { // (an open straight step only: a spot round the end of a wall 2 off was nudged at through the wall, 10 s, stage 1185) (level only: a nudge can't take a step up, 10 s stood still under his furnace one up the slope, live 11:31) // the spot beside him (or under him, off its centre): a nudge, a walk there "arrives" a block short
            if (ticks > 200) { BlockPos inWay0 = blockInTheWay(level, target); com.claudemod.ClaudeMod.LOGGER.info("[Claude life] couldn't get in reach of {} at {} (he's at {}; nudged 10 s toward {}, {} off it; in reach: {}, in the way: {})", name(level.getBlockState(target)), target.toShortString(), feet.toShortString(), side.toShortString(), String.format("%.1f", Math.sqrt(g.distanceToSqr(side.getX() + 0.5, side.getY(), side.getZ() + 0.5))), inReach(target), inWay0 == null ? "nothing" : name(level.getBlockState(inWay0)) + " at " + inWay0.toShortString()); failed(com.claudemod.entity.life.Attempts.Kind.REACH, target, com.claudemod.entity.life.Attempts.Reason.OUT_OF_REACH, "couldn't get in reach of " + name(level.getBlockState(target))); return true; }  // (live 02:03 11-07: his furnace 3 off, 11 cooked salmon in it, failed twice with no why, then fishing for food)
            g.getNavigation().stop(); g.getMoveControl().setWantedPosition(side.getX() + 0.5, side.getY(), side.getZ() + 0.5, 0.5); return false;
        }
        if (ticks == 1 && g.currentTask != null && !g.attempts.skipped(com.claudemod.entity.life.Attempts.Kind.REACH, target, g.tickCount, 0)) { // a far reach goes by his route like any walk (live 13:57-14:22 11-05: straight down through the lake to his furnace by his mine's rows, BLOCKED_FLUID for 25 min; only a GoTo was split into his route's legs)
            var legs = com.claudemod.entity.nav.Route.expand(g, level, side, side.equals(target) ? 2 : 0.6);
            if (legs != null && !legs.isEmpty() && !com.claudemod.entity.nav.Route.walkerTakes(legs)) {
                g.tasks.addFirst(g.currentTask); for (int i = legs.size() - 1; i >= 0; i--) g.tasks.addFirst(legs.get(i));
                g.getNavigation().stop(); exitWhy = "the way into reach of " + target.toShortString() + " split into " + legs.size() + " legs of his route, the job after (WorkGoal.reachFor)";
                com.claudemod.ClaudeMod.LOGGER.info("[Claude life] {} at {}: into reach by his route, {} legs, then the job (WorkGoal.reachFor)", name(level.getBlockState(target)), target.toShortString(), legs.size());
                return true;
            }
        }
        Task job = g.currentTask; int n0 = g.tasks.size(); if (job != null) g.tasks.addFirst(job); /* (the job behind whatever the walk queues in front: a made way returned "ended" 4 off and the load was dropped as out of reach, shuttling between two furnace spots over a minute, live 19:22 and 19:30 11-09, stage 1279) */
        boolean ended = walkTo(side, side.equals(target) ? 2 : 0.6);
        if (job != null && g.tasks.size() > n0 + 1 && g.tasks.contains(job)) { exitWhy = "the way into reach of " + target.toShortString() + " queued by the walk, the job after (WorkGoal.reachFor)"; com.claudemod.ClaudeMod.LOGGER.info("[Claude life] {} at {}: into reach by the way the walk made, then the job (WorkGoal.reachFor)", name(level.getBlockState(target)), target.toShortString()); return true; }
        if (job != null) g.tasks.removeFirstOccurrence(job);
        if (ended && g.attempts.skipped(com.claudemod.entity.life.Attempts.Kind.WALK, side, g.tickCount, 0)) { var why0 = g.attempts.why(com.claudemod.entity.life.Attempts.Kind.WALK, side); com.claudemod.ClaudeMod.LOGGER.info("[Claude life] couldn't get in reach of {} at {}: the walk to {} beside it is written off ({}), nothing changed since (WorkGoal.reachFor)", name(level.getBlockState(target)), target.toShortString(), side.toShortString(), why0 == null ? "-" : why0.reason() + (why0.detail().isEmpty() ? "" : ": " + why0.detail())); failed(com.claudemod.entity.life.Attempts.Kind.REACH, target, com.claudemod.entity.life.Attempts.Reason.NO_PATH, "the walk to the spot beside the " + name(level.getBlockState(target)) + " at " + side.toShortString() + " written off"); return true; } // (a skipped walk 1 off counted as there: he waited on it for good, 180 s and again after a restart, live 23:49-23:56 11-07, D2D review 368; stage 1022)
        if (ended && BlockPos.containing(g.getX(), g.getY() + 0.2, g.getZ()).distSqr(side) > 4) { com.claudemod.ClaudeMod.LOGGER.info("[Claude life] couldn't get in reach of {} at {} (he's at {})", name(level.getBlockState(target)), target.toShortString(), feet.toShortString()); failed(com.claudemod.entity.life.Attempts.Kind.REACH, target, com.claudemod.entity.life.Attempts.Reason.NO_PATH, "no way to a spot beside the " + name(level.getBlockState(target)) + " at " + side.toShortString()); return true; } // (on record: the furnace rule queued it again every 5 s, live 13:52)
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
        if (out.isEmpty()) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] furnace at {}: nothing done to take out (in: {} {}, fuel: {} {}, lit: {}, {} ticks on it) (WorkGoal.doFurnaceTake)", t.pos().toShortString(), furnace.getItem(0).getCount(), itemName(furnace.getItem(0)), furnace.getItem(1).getCount(), itemName(furnace.getItem(1)), level.getBlockState(t.pos()).getValue(net.minecraft.world.level.block.AbstractFurnaceBlock.LIT), ticks); // (live 11:42 11-08: "my cooked_salmon is done in my furnace 1 off: getting it" 14 times in 1.4 s, none taken, and the take said nothing)
        if (!out.isEmpty()) furnace.awardUsedRecipesAndPopExperience(g); // the XP a player gets for taking the output
        if (!out.isEmpty()) {
            g.noteDoing("smelted " + out.getCount() + " " + itemName(out));
            g.inventory.add(out); com.claudemod.entity.life.Watchdog.noteMade(g);
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
                m -> m.isAlive() && (m instanceof net.minecraft.world.entity.monster.Enemy || m instanceof net.minecraft.world.entity.NeutralMob nm0 && nm0.isAngryAt(g)) && (m.getTarget() == g || m.distanceTo(g) < 6)
                        && !(m instanceof net.minecraft.world.entity.NeutralMob nm && !nm.isAngryAt(g))); // (what he sees, hears or feels; a calm zombified piglin or enderman is no one on him: live 17:46 11-07 three made one wither skeleton "4 on him", he ran and died, stage 1057)
    }

    /** Hostile mobs about (any kind, after him or not). */
    private java.util.List<net.minecraft.world.entity.Mob> enemiesNear(double r) {
        return com.claudemod.entity.life.Perception.known(g, net.minecraft.world.entity.Mob.class, g.getBoundingBox().inflate(r), m -> m.isAlive() && m instanceof net.minecraft.world.entity.monster.Enemy
                && !(m instanceof net.minecraft.world.entity.NeutralMob nm && !nm.isAngryAt(g)) // (zombified piglins, endermen: no enemy till angry at him; 16:33 two calm ones were "a crowd" he wouldn't walk past, 20 s stuck)
                && !(m instanceof net.minecraft.world.entity.monster.piglin.Piglin pg && pg.getTarget() != g));
    }

    /** In reach with a charged swing: a hit lands right now (and knocks it back). */
    /** His swing lands on this creeper before it blows: in reach and in sight, armed, the sword back up (0.9) with creeper.hit_margin_ticks to spare of its fuse. */
    private boolean hitsBeforeBang(net.minecraft.world.entity.monster.Creeper c) {
        if (!armed() || c.getBoundingBox().distanceToSqr(g.getEyePosition()) > 9.0 || !g.hasLineOfSight(c)) return false;
        double ready = Math.max(0, 0.9 - g.getAttackStrengthScale(0f)) * g.getCurrentItemAttackStrengthDelay(), fuse = (1 - c.getSwelling(1f)) * 30;
        return c.getSwellDir() <= 0 || ready + w("creeper.hit_margin_ticks", 3) < fuse;
    }
    private boolean canHitNow(net.minecraft.world.entity.Entity e) {
        return armed() && g.getAttackStrengthScale(0.5f) >= 0.9f && (e.invulnerableTime <= w("combat.move.strike.iframes_max", 10) || !(e instanceof net.minecraft.world.entity.LivingEntity le && com.claudemod.entity.life.CombatLog.hitWithin(le, 10))) && e.getBoundingBox().distanceToSqr(g.getEyePosition()) <= 9.0 && g.hasLineOfSight(e); // (his own hit's hurt frames: a same-weapon swing into the first half takes nothing and spends his charge, bench 1108; frames from a weaker hurt (a burn tick) let a bigger hit through, so those are swung into)
    }

    private boolean armed() {
        return g.inventory.find(s -> s.getItem() instanceof net.minecraft.world.item.SwordItem || s.getItem() instanceof net.minecraft.world.item.AxeItem).isEmpty() == false;
    }

    /** Nothing much to fight with or in: a wooden weapon at best, or no armor on at all. Two mobs are a crowd for him then (a fresh respawn at night died twice "armed" with a wooden sword). */
    /** HP he can expect to lose out among n hostiles over cover.look_ahead_s (Decide.danger at his armor). */
    private double onHp(int n) { ServerLevel lv = (ServerLevel) g.level(); return com.claudemod.entity.life.Decide.danger(g, lv, lv.getDayTime() % 24000, w("cover.look_ahead_s", 10), Math.max(1, n)); }
    /** That HP as seconds of his time at his health and food (Decide.hpS: regen, the floor, the death odds). */
    private double backAmongS(int n) { return com.claudemod.entity.life.Decide.hpS(g, 0, onHp(n), com.claudemod.entity.life.Decide.hitHp() * com.claudemod.entity.life.Decide.armorF(g)); }
    /** Under cover now, or on with what he's doing among n hostiles (cover.dig_in): the HP he can expect to lose out here over cover.look_ahead_s (Decide.danger at his armor, priced by hpS: his health, food, the death odds) against building cover and waiting it out. True: cover. Logged when cover wins. */
    private boolean coverPays(String why, int n) {
        ServerLevel lv = (ServerLevel) g.level(); long tod = lv.getDayTime() % 24000;
        double perHit = com.claudemod.entity.life.Decide.hitHp() * com.claudemod.entity.life.Decide.armorF(g);
        double onHp = onHp(n);
        double build = w("cover.build_s", 2.5), buildHp = 0; // (the hits while he builds it: shooters with a line on him keep firing at a tower, melee within 4 swing; 596, two skeletons in the day: a tower under their fire)
        for (var m : com.claudemod.entity.life.Perception.known(g, net.minecraft.world.entity.Mob.class, g.getBoundingBox().inflate(24), x -> x.isAlive() && x instanceof net.minecraft.world.entity.monster.Enemy)) {
            if (shoots(m) && m.hasLineOfSight(g)) buildHp += build * w("flee.shots_per_s", 0.5) * w("flee.shot_hit_chance", 0.7) * mobHit(m);
            else if (m.distanceTo(g) < 4) buildHp += build * w("fight.mob_hits_per_s", 0.8) * mobHit(m); }
        double on = backAmongS(n), cover = build + com.claudemod.entity.life.Decide.hpS(g, 0, buildHp, perHit) + w("cover.wait_out_s", 60);
        var opts = new java.util.ArrayList<>(java.util.List.of(com.claudemod.entity.life.Decide.Option.of("cover", cover, "build " + build + " s (~" + String.format("%.1f", buildHp) + " HP while he does), wait it out ~" + Math.round(w("cover.wait_out_s", 60)) + " s"),
            com.claudemod.entity.life.Decide.Option.of("carry on", on, "~" + String.format("%.1f", onHp) + " HP to expect out here over " + Math.round(w("cover.look_ahead_s", 10)) + " s")));
        // the fight itself, as fight.flee prices it (a shield's share of the arrows, the swings, the HP): a lone skeleton with a shield on him is an 8 s fight, not a cover to wait out all night with it camped round it (D2D 1059, live 20:29-20:33 11-07: cover won "~77 s" against "carry on" alone, three covers under its arrows, dead)
        net.minecraft.world.entity.Mob foe = null; for (var m : com.claudemod.entity.life.Perception.known(g, net.minecraft.world.entity.Mob.class, g.getBoundingBox().inflate(16), x -> x.isAlive() && x instanceof net.minecraft.world.entity.monster.Enemy && !(x instanceof net.minecraft.world.entity.monster.Phantom))) if (foe == null || m.distanceTo(g) < foe.distanceTo(g)) foe = m;
        if (foe != null && armed()) { shouldFlee(foe, false); if (!Double.isNaN(pricedFightS)) opts.add(com.claudemod.entity.life.Decide.Option.of("fight", pricedFightS, fightOdds)); }
        String ctx = why + ", " + n + " hostile(s), health " + (int) g.getHealth() + ", armor " + g.getArmorValue() + (com.claudemod.director.ClaudeLife.dayHere(lv) ? "" : ", night");
        var pick = com.claudemod.entity.life.Decide.choose(g, "cover.dig_in", ctx, false, opts);
        if (pick != null && pick.name().equals("fight")) { com.claudemod.entity.life.Decide.choose(g, "cover.dig_in", ctx, true, opts); if (!(current instanceof Task.Attack)) g.tasks.addFirst(new Task.Attack(foe.getUUID(), 20 * 60)); return false; }
        if (pick == null || !pick.name().equals("cover")) return false;
        com.claudemod.entity.life.Decide.choose(g, "cover.dig_in", ctx, true, opts); return true;
    }
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
    private int cornerUntil, noWayOutTick = -100000; private double pricedFightS, pricedRunS; // (noWayOutTick: when pickEscape last found every way out goes past them)

    /** A crowd he'd run from with a wither skeleton in it, and a 2-high gap it can't reach into (FortressFight's wither cover): that wither skeleton, fought from the gap (the chokepoint: one at a time, the tall ones can't follow in). Null: run as before. Live 13:59 11-04: four escape runs, each blocked by a wither skeleton, 20 -> 0 in 10 s; the same kind of gap had held one off a minute before. */
    private net.minecraft.world.entity.LivingEntity gapFoe() {
        if (!(g.level() instanceof ServerLevel lv)) return null;
        net.minecraft.world.entity.monster.WitherSkeleton best = null;
        for (var w : com.claudemod.entity.life.Perception.known(g, net.minecraft.world.entity.monster.WitherSkeleton.class, g.getBoundingBox().inflate(16), net.minecraft.world.entity.Entity::isAlive)) if (best == null || w.distanceToSqr(g) < best.distanceToSqr(g)) best = w;
        if (best == null) return null;
        var cv = com.claudemod.entity.life.FortressFight.coverFor(g, lv, best);
        if (cv == null) return null;
        sdSay("gapfoe", "[Claude fight] a crowd with a wither skeleton in it ({} close): to the gap at {} it can't reach into, fighting it from there, not running past them (WorkGoal.gapFoe)", hostilesNear(10).size(), cv.cover().toShortString());
        return best;
    }

    /** Silverfish and endermites while he's above 8 health: 1 damage a bite, swatted, never a crowd to run from (stage 559: six from the portal room's spawner ran him off and he plugged himself out of the room). */
    private boolean pest(net.minecraft.world.entity.Entity m) { return g.getHealth() > 8 && (m instanceof net.minecraft.world.entity.monster.Silverfish || m instanceof net.minecraft.world.entity.monster.Endermite); }
    /** A mob that shoots him: skeletons, and anything holding a crossbow (pillagers, piglins: live 05:41 World 17, a crossbow piglin read as melee, "0 shooter(s)", a bare tower, shot off it). */
    public static boolean shoots(net.minecraft.world.entity.Entity m) { return m instanceof net.minecraft.world.entity.monster.AbstractSkeleton && !(m instanceof net.minecraft.world.entity.monster.WitherSkeleton) || m instanceof net.minecraft.world.entity.monster.CrossbowAttackMob && m instanceof net.minecraft.world.entity.LivingEntity l && l.isHolding(net.minecraft.world.item.Items.CROSSBOW) || m instanceof net.minecraft.world.entity.monster.Drowned d && d.isHolding(net.minecraft.world.item.Items.TRIDENT); } // (a trident drowned throws 8s from range: bench 1103, the run priced with no throws on it, 4 in his back)

    private boolean shouldFlee(net.minecraft.world.entity.Entity foe) { return shouldFlee(foe, false); }
    private boolean shouldFlee(net.minecraft.world.entity.Entity foe, boolean say) { pricedFightS = Double.NaN; pricedRunS = Double.NaN; // (each call prices afresh: the cornered choice read a blaze fight left over from the last zombie as "~0 s", 775)
        if (foe instanceof net.minecraft.world.entity.LivingEntity l0 && outOfItsReach(l0)) return false; // it can't get him up there: no reason to run (an iron golem under his 3-high tower read as a lost fight)
        if (com.claudemod.entity.life.FortressFight.coverMob(foe) && g.getHealth() > 6 && foe instanceof net.minecraft.world.entity.LivingEntity lf && com.claudemod.entity.life.FortressFight.coverFor(g, (ServerLevel) g.level(), lf) != null) { fightOdds = "from cover"; return false; } // a blaze or wither skeleton with cover at hand: the cover fight backs into it itself (running in the open from fireballs is how it goes wrong)
        if (foe instanceof net.minecraft.world.entity.monster.Blaze) { // blazes: fight or run, priced; the fight by Big Picture's FortressFight.fightS (kill time, the fireballs that land, a shield's share), the run by their fireballs on his back while they keep pace (they fly). Was "never flee: fought", audit WG shouldFlee
            int nb = Math.max(1, com.claudemod.entity.life.Perception.known(g, net.minecraft.world.entity.monster.Blaze.class, g.getBoundingBox().inflate(48), this::shootsHere).size());
            boolean shieldB = g.getItemBySlot(EquipmentSlot.OFFHAND).is(net.minecraft.world.item.Items.SHIELD) && !g.getCooldowns().isOnCooldown(net.minecraft.world.item.Items.SHIELD), fedB = com.claudemod.director.ClaudeLife.foodToEat(g, true) != null;
            double tough = g.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR_TOUGHNESS);
            double fightB = com.claudemod.entity.life.FortressFight.fightS(g, g.getHealth(), g.getArmorValue(), tough, shieldB, fedB, 0, nb);
            double fb = w("blaze.fireball_hp", 5), perB = fb * (1 - Math.min(20, Math.max(g.getArmorValue() / 5.0, g.getArmorValue() - fb / (2 + tough / 4))) / 25) + w("blaze.fire_hp", 3);
            var blz = com.claudemod.entity.life.Perception.known(g, net.minecraft.world.entity.monster.Blaze.class, g.getBoundingBox().inflate(48), this::shootsHere);
            BlockPos hideB = hideSpot((ServerLevel) g.level(), g.blockPosition(), blz); // (out of their sight on foot: they lose him there; none, and they fly after him and keep firing)
            double exposedB = hideB != null ? Math.sqrt(hideB.distSqr(g.blockPosition())) / com.claudemod.entity.life.Decide.sprint() : w("blaze.open_run_s", 15);
            double runHp = nb * exposedB * w("blaze.shots_per_s", 0.6) * w("blaze.shot_hit_chance", 0.5) * perB, runB = w("flee.run_s", 20) + com.claudemod.entity.life.Decide.hpS(g, 0, runHp, perB) + Math.min(com.claudemod.entity.life.FortressFight.fightS(g, g.getMaxHealth(), g.getArmorValue(), tough, shieldB, fedB, 0, nb), w("flee.avoid_s", 180));
            var pickB = com.claudemod.entity.life.Decide.choose(g, "fight.flee", nb + " blaze(s), health " + (int) g.getHealth() + ", armor " + g.getArmorValue() + (shieldB ? ", shield" : ""), say, java.util.List.of(
                com.claudemod.entity.life.Decide.Option.of("fight", fightB, "FortressFight.fightS, from cover where he has it"),
                com.claudemod.entity.life.Decide.Option.of("run", runB, "~" + Math.round(runHp) + " HP of fireballs getting " + (hideB != null ? "out of their sight at " + hideB.toShortString() : "away in the open") + ", then them again or kept away from")));
            fightOdds = nb + " blaze(s): fight ~" + Math.round(fightB) + " s, run ~" + Math.round(runB) + " s"; pricedFightS = fightB; pricedRunS = runB;
            return pickB != null && pickB.name().equals("run"); }
        // (wither skeletons: no rule any more, fleePriced prices them: their hits through armor, the Wither on each (fight.wither_hp_per_s for its fight.wither_s), and they walk at 0.25, short of his sprint. World 17 08:50: "can't outrun them" kept him fighting four on a bridge with no gap, 20 -> 0 in 2 s; stage 770)
        // (endermen: no rule any more, fleePriced prices them: a teleporter keeps pace with any run (flee.chase_hits), so the run is dear and the fight usually wins; low enough, the price says otherwise. Was a flat "never flee", audit WG shouldFlee; the 18:43 death it came from was a run priced as cheap)
        if (foe instanceof net.minecraft.world.entity.monster.Creeper c && c.distanceTo(g) < 5 && c.getSwellDir() > 0 && !hitsBeforeBang(c)) return true; // (it goes off before any swing of his lands: the run is all there is; unarmed or hurt goes to the price below, audit) // (hissing but his swing lands first: the hit knocks it out of its fuse; World 17 08:18, the tick said "hit it back" and this said "run" the same tick, he turned his back 1 off; stage 772) // hissing and he can't hit it right now (or can't fight it): run
        java.util.List<net.minecraft.world.entity.Mob> crowd = hostilesNear(10).stream().filter(m -> !pest(m) && !(m instanceof net.minecraft.world.entity.monster.Phantom) && !(m instanceof net.minecraft.world.entity.monster.Creeper && m.distanceTo(g) > 6)).collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new)); for (var m : com.claudemod.entity.life.Perception.known(g, net.minecraft.world.entity.Mob.class, g.getBoundingBox().inflate(24), m -> m.isAlive() && shoots(m) && (m.getTarget() == g || m.getTarget() == null) && g.hasLineOfSight(m))) if (!crowd.contains(m)) crowd.add(m); for (var m : com.claudemod.entity.life.Perception.known(g, net.minecraft.world.entity.monster.Creeper.class, g.getBoundingBox().inflate(16), m -> m.isAlive() && m.getTarget() == g)) if (!crowd.contains(m)) crowd.add(m); /* a creeper after him counts however far his run took him: dropped past 6, the run's end priced one of two as a lone fight and he walked back into both (02:06 11-09, 26 blasts since 11-05; stage 1147) */ int n = crowd.size(); // (and a shooter on him with a line on him, however far: its arrows land from 15 off; World 17 09:11, 09:41, stage 771) // the crowd that running gets him away from: no phantoms overhead (no outrunning them), no creeper 12-19 off (live 12:07 11-02: those made one spider '4 on him' and he ran instead of 2-3 axe hits)
        // three or more on him is a crowd whatever he's holding: get out of their reach (tower or dig in) and fight from there
        boolean underground = underground((ServerLevel) g.level(), g.blockPosition()); // (rock over him: a tree's leaves are the open)
        boolean armored = false; for (EquipmentSlot sl : EquipmentSlot.values()) if (sl.isArmor() && !g.getItemBySlot(sl).isEmpty()) armored = true;
        // (down a cave: no rule any more, the run there is priced with its dark hits, fight.flee: "running in the dark is how you die"; one zombie fought bare, two only in armor (two bare took 11 health in 3 s))
        // (a witch: no rule any more, fleePriced prices her: her potions (fight.witch_hit_hp, through armor) every fight.witch_throws_per_s, and she can't keep pace with a sprint (flee.witch_hits); stages 158 rush a hurt one, 159 run from a full one bare)
        return fleePriced(foe, crowd, underground, say);
    }

    /**
     * Fight or run, one priced choice (Decide.choose "fight.flee"), seconds of his time each:
     * the fight is its swings (fight.swing_s each, hitsToKill) and the hits he takes till they're down, each one on him swinging
     * (fight.mob_hits_per_s, its cooldown capping them: fight.mob_cooldown_s) till it's dead, a shooter's shots while he closes in;
     * the run is its time (flee.run_s), the hits he takes breaking off (flee.hits_breaking_off from each within 4) or all the way
     * from one that keeps pace (shooters, baby zombies, phantoms, anything once he can't sprint: flee.chase_hits), more in the
     * dark underground (flee.dark_hits), and then the same mobs met again: fought at full health, or kept away from
     * (flee.avoid_s; a mob burning in the sun only its health in seconds), whichever is cheaper. So at full health he fights
     * what he'd fight anyway, low health makes the fight now dear and the later one not, and a fight he'd lose now or later is
     * kept away from. On the run back to his pile out of his armor the pile rides on the fight (recovery.fight_s: the user
     * 11-05, "avoiding combat altogether until you get suited with your pickups"). Replaces the crowd, one-on-one, cave,
     * skeleton, HP 6 and recovery-run rules.
     */
    private static double w(String name, double dflt) { return com.claudemod.entity.life.Weights.w(name, dflt); }
    private final java.util.Map<java.util.UUID, int[]> reachMemo = new java.util.HashMap<>(); // mob -> {tick, 1 he can get to it / 0 not}
    /** He can get to it by his own moves (a walk, a block put down to step up, a dig): the zombie probe first, then his own planner, as he'd walk it (audit 11: the probe can't dig, place or leap; a skeleton on a 2-high ledge read "out of his reach" and cost 120 s in every fight price). */
    private boolean canGetTo(net.minecraft.world.entity.Entity m) {
        if (g.getNavigation().createPath(m, 1) instanceof net.minecraft.world.level.pathfinder.Path np && np.canReach()) return true;
        int[] k = reachMemo.get(m.getUUID()); if (k != null && g.tickCount - k[0] >= 0 && g.tickCount - k[0] < 40) return k[1] == 1; // (priced every tick in a fight: his planner once in 2 s per mob)
        if (reachMemo.size() > 64) reachMemo.clear();
        var plan = com.claudemod.entity.nav.ActionPath.plan(g, (ServerLevel) g.level(), g.blockPosition(), m.blockPosition(), w("fight.reach_blocks", 3.5) - 1, !scaffoldBlock().isEmpty(), 2000, com.claudemod.entity.nav.ActionPath.Mode.MAY_DIG, g.attempts.of(com.claudemod.entity.life.Attempts.Kind.WALK), g.tickCount);
        reachMemo.put(m.getUUID(), new int[] {g.tickCount, plan != null ? 1 : 0}); return plan != null;
    }
    private boolean fleePriced(net.minecraft.world.entity.Entity foe, java.util.List<net.minecraft.world.entity.Mob> crowd, boolean underground, boolean say) {
        java.util.List<net.minecraft.world.entity.LivingEntity> them = new java.util.ArrayList<>(crowd);
        if (foe instanceof net.minecraft.world.entity.LivingEntity lf && lf.isAlive() && !them.contains(lf)) them.add(lf);
        if (them.isEmpty()) { fightOdds = "nothing on him"; return false; }
        them.sort(java.util.Comparator.comparingDouble(m -> m.distanceTo(g)));
        double swing = w("fight.swing_s", 0.6), rate = w("fight.mob_hits_per_s", 0.8) * (armed() ? w("fight.knockback_hits_factor", 0.35) : 1), cool = w("fight.mob_cooldown_s", 1.0); // (a sword or axe knocks each one back: it walks in again before its next swing, about half the hits; HuntPatrol.fightS prices an enderman the same, 0.4. Stage 48: one zombie at 12 HP priced as 3 hits in 2.4 s, he ran) // (0.35, from the fight records 11-08: 38 won melee fights, ~0.21-0.3 hits per zombie-second armed; 0.5 priced 5 zombies in iron at 17 HP when they cost 7-11)
        String unreachable = null; double t = 0, hits = 0, hp = 0, runHits = 0, burnS = 0, witherF = 0, witherR = 0; int most = 0, swings = 0; boolean sprint = g.getFoodData().getFoodLevel() > 6, allBurn = true, anyPace = false;
        boolean shield = g.getItemBySlot(EquipmentSlot.OFFHAND).is(net.minecraft.world.item.Items.SHIELD) && !g.getCooldowns().isOnCooldown(net.minecraft.world.item.Items.SHIELD);
        // only so many melee can be on him at once (the open cells round him: a few out in the open, one or two in a tunnel): the next one starts hitting when one before it dies (5 zombies on him in iron read as ~17 HP when the real fights cost 2-9; bench 1117, he ran in circles)
        int atOnce = Math.max(1, Math.min((int) w("fight.melee_at_once_max", 3), openRound(them))); java.util.List<Double> meleeDeaths = new java.util.ArrayList<>();
        for (var m : them) {
            if (m instanceof net.minecraft.world.entity.monster.Creeper cr) { // (its blast, not a swing: the chance it goes off in the fight, armed he knocks it out of its fuse; on the run, near or lit it may still catch him. Was "armed and over 10 HP: hit it, else run", audit)
                int k = hitsToKill(m); swings += k; t += k * swing;
                double blast = w("creeper.blast_hp", 22) * (1 - Math.min(20, g.getArmorValue()) / 25.0);
                double pf = armed() && !weakFighter() ? w("creeper.blast_chance_armed", 0.45) : w("creeper.blast_chance_unarmed", 0.8), pr = cr.distanceTo(g) < 3 || cr.getSwellDir() > 0 ? w("creeper.blast_chance_close_run", 0.4) : w("creeper.blast_chance_run", 0.05); // (0.45: 52 of 111 armed creeper fights 11-05..11-09 ended in its blast; 0.15 priced ~3 HP and he fought it bare, 12:20 11-09 18 -> 5)
                hits += pf; hp += pf * blast; most += 1; runHits += pr * blast; allBurn = false; continue; }
            boolean witch = m instanceof net.minecraft.world.entity.monster.Witch; // (harming: 6 at Normal, armor no help)
            double dmg = witch ? w("fight.witch_hit_hp", 6) : mobHit(m); boolean ranged = shoots(m) || witch; // (a wither skeleton is a skeleton by class, but a sword, never a bow)
            boolean bowDuel = ranged && !witch && m instanceof net.minecraft.world.entity.monster.Enemy && m.distanceTo(g) >= 6 && m.distanceTo(g) <= 28 && canHit(m) && g.inventory.count(s0 -> s0.is(net.minecraft.world.item.Items.BOW)) > 0 && g.inventory.count(s0 -> s0.is(net.minecraft.world.item.Items.ARROW)) > 0; // (a shooter in his bow's range: doAttack shoots it from where he stands (shootAt), never the walk in under its arrows; stage 546 ran from one skeleton 9 off, bow and 16 arrows on him, priced as a sword fight)
            int k = bowDuel ? (int) Math.ceil(m.getHealth() / w("fight.bow_arrow_hp", 6)) : hitsToKill(m); swings += k; t += k * (bowDuel ? w("fight.bow_draw_s", 1.1) : swing);
            if (ranged && !bowDuel && m.distanceTo(g) > w("fight.reach_blocks", 3.5) && !canGetTo(m)) { t += w("fight.unreachable_s", 120); unreachable = m.getType().getDescription().getString(); } // a shooter he can't walk up to: no sword fight to be had, it shoots the while (live 11:19-11:21 11-07 death 109: a crossbow piglin 5 up on a ledge, "fight ~73 s (3 swings)" every hit, 20 -> 0; stage 1002)
            double start = !ranged && !witch && meleeDeaths.size() >= atOnce ? meleeDeaths.get(meleeDeaths.size() - atOnce) : 0; // (waits its turn for a cell on him)
            double h = bowDuel ? t * w("fight.shooter_shots_per_s", 0.5) * w("fight.shooter_hit_chance", 0.7) : (t - start) * (witch ? w("fight.witch_throws_per_s", 0.33) : rate) + (ranged ? m.distanceTo(g) / w("fight.close_blocks_per_shot", 6) : 0); // (Normal: a skeleton draws every 2 s)
            if (shoots(m) && shield && !bowDuel) h *= w("fight.shield_arrow_factor", 0.25); // (a shield up toward it while he closes in takes its arrows; stage 38 ran from one skeleton with a shield on him after 924759d) // (each hits him till it's down: the first for its own time, the next for both; a shooter ~a shot each 6 blocks he closes)
            if (!ranged && !witch) meleeDeaths.add(t);
            hits += h; hp += h * dmg; most += (int) Math.ceil(t / cool) + (ranged ? 2 : 0); if (m instanceof net.minecraft.world.entity.monster.WitherSkeleton) witherF += h;
            boolean pace = !sprint || m instanceof net.minecraft.world.entity.monster.EnderMan || m instanceof net.minecraft.world.entity.FlyingMob || m.isNoGravity() || (m instanceof net.minecraft.world.entity.monster.Zombie z && z.isBaby()); // (a flier keeps pace with any run: phantoms, ghasts, an evoker's vexes, which hit 9 through iron; bench 1110: 'running west from [Vex 1 east, Vex 1 east ...]', dead)
            anyPace |= pace || ranged && !witch; // (the pile: a shooter gets its shots either way, a run avoids no fight with it; review 1292)
            double rh = witch && sprint ? w("flee.witch_hits", 1) : pace ? w("flee.chase_hits", 3) : m.distanceTo(g) < 4 ? w("flee.hits_breaking_off", 0.7) : 0; runHits += rh * dmg; if (m instanceof net.minecraft.world.entity.monster.WitherSkeleton) witherR += rh;
            boolean burning = m.isOnFire() && !m.fireImmune() && g.level().isDay(); allBurn &= burning; burnS = Math.max(burnS, m.getHealth() / w("flee.burn_hp_per_s", 1.0));
        }
        double perHit = hits > 0 ? hp / hits : 3;
        boolean shooters = them.stream().anyMatch(m -> shoots(m)); double sightHp = shooters && sprint ? bestSightHp(crowd) : 0; runHits += sightHp; // (a shooter gets its shots while he is in its sight: on the heading that breaks it soonest, not three each whatever the ground)
        if (underground && !g.level().dimensionType().hasCeiling()) runHits += w("flee.dark_hits", 1.0) * perHit; // (the Nether is "underground" by its roof, but lit to the eye: no dark surcharge there)
        { double dot = w("fight.wither_hp_per_s", 0.5) * w("fight.wither_s", 10); hp += dot * (1 - Math.exp(-witherF)); runHits += dot * (1 - Math.exp(-witherR)); } // (the Wither rides on a hit: its 10 s only with the chance one of its hits lands, in the fight as on the run; was the fight's whole time + 10 s with no hit, and the run's scaled by its HP, not its hits: 18:39 11-08 both priced ~50% death) // (after perHit: the Wither is no hit. Each hit puts the Wither on him, armor no help: on all through the fight and its 10 s after; a run that takes a hit gets its 10 s too)
        boolean pile = g.getArmorValue() == 0 && (g.lastStep != null && g.lastStep.skill().equals("recover_drops") || g.skillQueue.stream().anyMatch(s0 -> s0.skill().equals("recover_drops")))
                && com.claudemod.entity.life.DeathLog.next(g, g.level().dimension().location().getPath()) != null && !anyPace; // (one that keeps pace with him gets its hits either way: running avoids no fight, review 1292)
        double fightNow = t + com.claudemod.entity.life.Decide.hpS(g, g.getHealth(), 0, hp, perHit, most), fightFull = t + com.claudemod.entity.life.Decide.hpS(g, g.getMaxHealth(), 0, hp, perHit, most);
        double later = Math.min(fightFull, allBurn ? burnS : w("flee.avoid_s", 180));
        double run = w("flee.run_s", 20) + com.claudemod.entity.life.Decide.hpS(g, 0, runHits, perHit) + later + (g.tickCount < cornerUntil ? w("flee.cornered_run_s", 30) : 0);
        // (cornered a moment ago, the run is dearer by the dead ends it already found: priced, not a 10 s lock that kept him fighting whatever came; audit WG shouldFlee)
        if (g.tickCount - noWayOutTick < 200) run = Double.MAX_VALUE / 4; // (a getaway he just saw isn't there: live 14:30 11-07 the run kept winning, he held 30 s and died; stage 1052)
        pricedFightS = fightNow + (pile ? w("recovery.fight_s", 600) : 0); pricedRunS = run; // (the cornered choice reads them)
        String what = them.size() == 1 ? them.get(0).getType().getDescription().getString() : them.size() + " on him";
        String unpriced = ""; if (say) for (var m : com.claudemod.entity.life.Perception.known(g, net.minecraft.world.entity.Mob.class, g.getBoundingBox().inflate(24), m -> m.isAlive() && shoots(m) && !them.contains(m) && g.hasLineOfSight(m))) unpriced += (unpriced.isEmpty() ? "" : ", ") + m.getType().getDescription().getString() + " " + (int) m.distanceTo(g) + " off (" + (m.getTarget() == g ? "on him" : m.getTarget() == null ? "no target" : "on " + m.getTarget().getType().getDescription().getString()) + ")"; // (a shooter with a line on him left out of the price: World 17 09:11, 09:41; the log first, the cause unproven)
        var pick = com.claudemod.entity.life.Decide.choose(g, "fight.flee", what + ", health " + (int) g.getHealth() + ", armor " + g.getArmorValue() + (armed() ? "" : ", unarmed") + (underground ? ", underground" : "") + (unpriced.isEmpty() ? "" : "; shooters with a line on him not priced: " + unpriced), say, java.util.List.of(
            com.claudemod.entity.life.Decide.Option.of("fight", fightNow + (pile ? w("recovery.fight_s", 600) : 0), swings + " swings, ~" + Math.round(hp) + " HP of hits" + (pile ? ", his pile on it out of his armor" : "")),
            g.tickCount - noWayOutTick < 200 ? com.claudemod.entity.life.Decide.Option.out("run", "every way out goes past them (seen " + (g.tickCount - noWayOutTick) / 20 + " s ago)") : com.claudemod.entity.life.Decide.Option.of("run", run, "~" + Math.round(runHits) + " HP getting away" + (shooters && sprint ? " (~" + Math.round(sightHp) + " of it arrows on the way out of their sight)" : "") + ", then " + (later == fightFull ? "the fight at full health" : allBurn ? "keeping away while it burns" : "keeping away from them"))));
        fightOdds = what + ": fight ~" + Math.round(fightNow) + " s (kill in " + swings + ", ~" + Math.round(hp) + " HP of hits; counted " + them.stream().map(m -> m.getType().getDescription().getString() + " " + (int) m.distanceTo(g)).collect(java.util.stream.Collectors.joining(", ")) + ")" + (pile ? " + his pile" : "") + (unreachable != null ? ", the " + unreachable + " out of his reach on foot" : "") + ", run ~" + Math.round(run) + " s";
        return pick != null && pick.name().equals("run");
    }

    private String fightOdds = ""; // the last sizing-up, for the log

    private java.util.UUID golemRetryFor; private int golemRetries; // re-towering beside a golem that strolled off
    private int reachWaitStart = -1, reachWaitTicks;
    private boolean lureShot; // a shot from his tower to draw it in // since when he's been up his tower waiting for it to come in reach (this attack)

    /** A ground mob that can't reach him where he stands: his feet over the top of it (its swing reaches out, never up), and it can't climb, shoot, fly or blow up. */
    private boolean outOfItsReach(net.minecraft.world.entity.LivingEntity m) {
        if (m instanceof net.minecraft.world.entity.monster.Spider || m instanceof net.minecraft.world.entity.monster.Creeper || shoots(m)
                || m instanceof net.minecraft.world.entity.FlyingMob || m.isNoGravity()) return false;
        if (!(m instanceof net.minecraft.world.entity.animal.IronGolem || m instanceof net.minecraft.world.entity.monster.Monster || m instanceof net.minecraft.world.entity.monster.hoglin.Hoglin)) return false; // (a hoglin is an Animal, not a Monster, in 1.21: it fights like one)
        double y = g.getY(); BlockPos f0 = g.blockPosition(); for (int d = 0; d <= 1; d++) { BlockPos q = f0.below(d); if (!g.level().getBlockState(q).getCollisionShape(g.level(), q).isEmpty()) { y = Math.max(y, q.getY() + 1); break; } } // (in the air settling onto his tower top counts: the fight starts as the last block goes in, him still rising; read by his height then, he "lost" the fight and jumped off, stage 826)
        if (y < m.getBoundingBox().maxY + 0.05) return false;
        // above its head is out of its reach only with no way up to him for it: on a slope or a rise it walks round (live 21:53 11-09: a zombie 27 off down a hill read as no threat, so "fight it", then the priced fight said run, review 233, stage 1285)
        if (m.getUUID().equals(reachMob) && g.tickCount - reachTick < 20) return reachOut;
        var way = m instanceof net.minecraft.world.entity.Mob mm ? mm.getNavigation().createPath(g, 1) : null;
        reachMob = m.getUUID(); reachTick = g.tickCount; reachOut = way == null || !way.canReach();
        return reachOut;
    }
    private java.util.UUID reachMob; private int reachTick; private boolean reachOut; // (outOfItsReach's last path, for a second: shouldFlee asks it every tick of a fight)

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
    /** Swings a second at full charge: 4 plus the item's attack speed modifier (sword 1.6, axe 0.8-1.0). */
    public static double weaponSpeed(ItemStack s) {
        double[] sp = {4.0};
        s.getAttributeModifiers().forEach(EquipmentSlot.MAINHAND, (attr, mod) -> {
            if (attr.is(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_SPEED) && mod.operation() == net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE) sp[0] += mod.amount();
        });
        return Math.max(0.1, sp[0]);
    }
    public static double weaponDamage(ItemStack s) {
        double[] dmg = {1.0};
        s.getAttributeModifiers().forEach(EquipmentSlot.MAINHAND, (attr, mod) -> {
            if (attr.is(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE) && mod.operation() == net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE) dmg[0] += mod.amount();
        });
        return dmg[0];
    }

    /** Hits it needs to land on him, with his armor counted. */
    private int hitsToDie(net.minecraft.world.entity.LivingEntity mob) { return (int) Math.ceil(g.getHealth() / mobHit(mob)); }

    /** What a hit of its does to him through his armor (vanilla armor: up to 80% off). */
    /** Holding a gap (escape.gap_hold): they come through one at a time, so each lands its hits only while it is the one he's killing (its swings, fight.swing_s each, hitsToKill), priced at his health by hpS; against running on: flee.run_s, the hits breaking off from those within 4, then the same mobs met again at full health or kept away from (flee.avoid_s). Null: hold; else why not, with both prices. */
    private String gapHoldNo(java.util.List<? extends net.minecraft.world.entity.Mob> mobs) {
        double swing = w("fight.swing_s", 0.6), rate = w("fight.mob_hits_per_s", 0.8) * (armed() ? w("fight.knockback_hits_factor", 0.35) : 1), cool = w("fight.mob_cooldown_s", 1.0);
        double t = 0, hits = 0, hp = 0, offHp = 0; int most = 0, n = 0;
        for (var m : mobs) { if (!m.isAlive() || m.getTarget() != g || m.distanceTo(g) > 24 || shoots(m)) continue; n++;
            int k = hitsToKill(m); double tm = k * swing, h = tm * rate, dmg = mobHit(m); t += tm; hits += h; hp += h * dmg; most += (int) Math.ceil(tm / cool);
            if (m.distanceTo(g) < 4) offHp += w("flee.hits_breaking_off", 0.7) * dmg; }
        if (n == 0) return null;
        double perHit = hits > 0 ? hp / hits : 3;
        double hold = t + com.claudemod.entity.life.Decide.hpS(g, g.getHealth(), 0, hp, perHit, most);
        double run = w("flee.run_s", 20) + com.claudemod.entity.life.Decide.hpS(g, 0, offHp, perHit) + Math.min(t + com.claudemod.entity.life.Decide.hpS(g, g.getMaxHealth(), 0, hp, perHit, most), w("flee.avoid_s", 180));
        if (hold <= run) return null;
        com.claudemod.entity.life.Decide.choose(g, "escape.gap_hold", n + " after him single file, health " + (int) g.getHealth() + ", armor " + g.getArmorValue(), true, java.util.List.of(
            com.claudemod.entity.life.Decide.Option.of("hold", hold, "one at a time, ~" + String.format("%.1f", hp) + " HP"), com.claudemod.entity.life.Decide.Option.of("run on", run, "~" + String.format("%.1f", offHp) + " HP breaking off")));
        return "holding ~" + Math.round(hold) + " s against running on ~" + Math.round(run) + " s";
    }
    /** Cells round him a melee mob can stand in to hit him (floor, two clear): how many can be on him at once. */
    private int openRound(java.util.List<? extends net.minecraft.world.entity.LivingEntity> them) {
        ServerLevel lv = (ServerLevel) g.level(); BlockPos f = BlockPos.containing(g.getX(), g.getY() + 0.2, g.getZ()); int n = 0;
        Vec3 to = Vec3.ZERO; for (var m : them) to = to.add(m.position().subtract(g.position()).multiply(1, 0, 1).normalize()); // (the side they come from: a tunnel's far end isn't a cell they reach him by)
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) { if (dx == 0 && dz == 0) continue; BlockPos c = f.offset(dx, 0, dz);
            if (to.lengthSqr() > 1e-4 && new Vec3(dx, 0, dz).dot(to) <= 0) continue;
            if (!solidAt(lv, c) && !solidAt(lv, c.above()) && solidAt(lv, c.below())) n++; }
        return n;
    }
    private double mobHit(net.minecraft.world.entity.LivingEntity mob) {
        boolean archer = shoots(mob); // (its arrows, not its melee: World 17 05:18, a skeleton priced at 2 a hit, arrows of 5 and 3 in his back; stage 766)
        double raw = archer ? (mob instanceof net.minecraft.world.entity.monster.Drowned ? w("fight.trident_hit_hp", 8) : w("fight.arrow_hit_hp", 4)) : mob.getAttributes().hasAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE)
                ? mob.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE) : 3.0;
        return Math.max(1.0, raw * (1.0 - Math.min(20, g.getArmorValue()) / 25.0));
    }

    private Vec3 fleeDir; // the way he's running: picked once, kept till it's blocked
    private final java.util.List<Vec3> fleeDead = new java.util.ArrayList<>(); private double fleeLastD = -1; private final java.util.Set<java.util.UUID> fleeFromIds = new java.util.HashSet<>(); private java.util.UUID fleeSeed; // headings that didn't gain him distance this run

    /** The way to run: away from them, with footing (no drop over 3), clear ahead, best where terrain hides him from them by 8 out. */
    /** HP the shooters' arrows cost him running 16 out on this heading: each step in a shooter's sight (its eye to his) at a sprint, flee.shots_per_s at flee.shot_hit_chance; a wall in the way leaves him standing there in view (flee.stuck_exposed_s). Behind rock it's none: the run that breaks their sight (World 17 09:40, 10:19; stage 773). */
    private double sightHp(Vec3 hd, java.util.List<? extends net.minecraft.world.entity.Entity> mobs) { return sightHp(hd, mobs, false); }
    /** runsOn: he keeps running (the run's price): no shots out of its weapon's range, and he outruns it at the pace he gains. Off for an escape that holds at its spot: there only breaking its sight helps, it walks back into range (stage 773). */
    private double sightHp(Vec3 hd, java.util.List<? extends net.minecraft.world.entity.Entity> mobs, boolean runsOn) {
        ServerLevel lv = (ServerLevel) g.level(); double hp = 0, step = 1 / com.claudemod.entity.life.Decide.sprint();
        for (var e : mobs) {
            if (!(e instanceof net.minecraft.world.entity.LivingEntity m) || !m.isAlive() || !shoots(m) || m.distanceTo(g) > 24) continue;
            double seenS = 0, range = runsOn ? shotRange(m) : Double.MAX_VALUE; java.util.List<Vec3> eyes = new java.util.ArrayList<>(); for (Vec3 off : new Vec3[] {Vec3.ZERO, new Vec3(3, 0, 0), new Vec3(-3, 0, 0), new Vec3(0, 0, 3), new Vec3(0, 0, -3)}) { Vec3 e0 = m.getEyePosition().add(off); BlockPos eb = BlockPos.containing(e0); if (off == Vec3.ZERO || lv.getBlockState(eb).getCollisionShape(lv, eb).isEmpty() && lv.getBlockState(eb.below()).getCollisionShape(lv, eb.below()).isEmpty()) eyes.add(e0); } // (where it stands and a few steps round: it moves; behind one pillar is no cover from a skeleton that steps off it)
            java.util.function.Predicate<Vec3> sees = pt -> eyes.stream().anyMatch(e0 -> com.claudemod.entity.life.Perception.clear(lv, e0, pt));
            for (int i = 1; i <= 16; i++) {
                Vec3 at = g.position().add(hd.scale(i)); BlockPos f = BlockPos.containing(at);
                if (!lv.getBlockState(f.above()).getCollisionShape(lv, f.above()).isEmpty() || !lv.getBlockState(f).getCollisionShape(lv, f).isEmpty() && !lv.getBlockState(f.above(2)).getCollisionShape(lv, f.above(2)).isEmpty()) { // (a wall: he stops at the last step in view)
                    Vec3 last = g.position().add(hd.scale(i - 1)); if (last.distanceTo(m.position()) <= range && sees.test(last.add(0, 1.6, 0))) seenS += w("flee.stuck_exposed_s", 4); break; } // (stopped by the wall out of its range: no shots there either)
                if (at.distanceTo(m.position()) > range) continue; // (out of its bow's range there: it walks after him, never shoots)
                if (sees.test(at.add(0, 1.6, 0))) { seenS += step; if (i == 16) seenS += runsOn ? Math.max(0, range - at.distanceTo(m.position())) / Math.max(0.5, com.claudemod.entity.life.Decide.sprint() - mobPace(m, 1.0)) : w("flee.open_exposed_s", 6); } // (still in its sight 16 out: open ground, it follows and shoots on; World 17 05:18, arrows of 5 and 3 in his back on the run, 16 -> 1)
            }
            hp += seenS * w("flee.shots_per_s", 0.5) * w("flee.shot_hit_chance", 0.7) * mobHit(m);
        }
        return hp;
    }
    /** The least arrow HP of the 12 headings round the way away from them (sightHp). */
    private double bestSightHp(java.util.List<? extends net.minecraft.world.entity.Mob> mobs) {
        Vec3 base = fleeHeading(mobs); double best = Double.MAX_VALUE;
        for (int r = 0; r < 360; r += 30) { double a = Math.toRadians(r); best = Math.min(best, sightHp(new Vec3(base.x * Math.cos(a) - base.z * Math.sin(a), 0, base.x * Math.sin(a) + base.z * Math.cos(a)), mobs, true)); }
        return best == Double.MAX_VALUE ? 0 : best;
    }
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

    // the escape he's committed to (lazy: fields added live start null/0)
    private BlockPos escapeAt, escapeGap; private String escapeKind; private int escapeSince = -100000, escapeBestTick; private double escapeBest; private boolean escapeHeld, escapePlugged; private int escapeSwings; private net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> escapeDim; // (the world it's in: a spot's coordinates mean nothing in another)
    public int escapePicks; // (test) escapes picked so far
    public String escapeKind() { return escapeKind; }
    private void clearEscape() { escapeAt = null; escapeGap = null; escapeKind = null; escapeHeld = false; escapePlugged = false; }
    /** A shooter out of his sword's reach (flee.shooter_melee_blocks): hidden from; one closer is in reach, hit like any other. */
    private boolean farShooter(net.minecraft.world.entity.Entity m) { return (shoots(m) || m instanceof net.minecraft.world.entity.monster.Blaze) && m.distanceTo(g) > w("flee.shooter_melee_blocks", 4); } // (a blaze's fireballs are hidden from the same way)
    /** The nearest cell on foot (flood out to flee.hide_blocks) that no shooter about sees, his head there out of each one's eye line and no melee mob within 3 of it. Null: none. */
    private BlockPos hideSpot(ServerLevel lv, BlockPos feet, java.util.List<? extends net.minecraft.world.entity.Mob> mobs) {
        java.util.List<net.minecraft.world.entity.Mob> sh = new java.util.ArrayList<>(); for (var m : mobs) if (m.isAlive() && farShooter(m) && m.distanceTo(g) < 32) sh.add(m);
        if (sh.isEmpty()) return null;
        int most = (int) w("flee.hide_blocks", 20); java.util.ArrayDeque<BlockPos> q = new java.util.ArrayDeque<>(); java.util.Map<BlockPos, Integer> dist = new java.util.HashMap<>(); q.add(feet.immutable()); dist.put(feet.immutable(), 0);
        while (!q.isEmpty() && dist.size() < 600) {
            BlockPos c = q.poll(); int d = dist.get(c);
            if (d > 0) { Vec3 head = Vec3.atBottomCenterOf(c).add(0, 1.6, 0); boolean seen = false; for (var m : sh) { Vec3 eye = m.getEyePosition(), in = head.subtract(eye).multiply(1, 0, 1); Vec3 nearer = in.length() > w("flee.hide_margin_blocks", 3) + 1 ? eye.add(in.normalize().scale(w("flee.hide_margin_blocks", 3))) : eye; if (com.claudemod.entity.life.Perception.clear(lv, eye, head) || lv.getBlockState(BlockPos.containing(nearer)).getCollisionShape(lv, BlockPos.containing(nearer)).isEmpty() && com.claudemod.entity.life.Perception.clear(lv, nearer, head)) { seen = true; break; } } // (and from a few steps nearer: it walks in after him; the first cell round the corner was seen again two steps on)
                boolean melee = false; for (var m : mobs) if (!farShooter(m) && m.position().distanceTo(Vec3.atBottomCenterOf(c)) < 3) melee = true;
                if (!seen && !melee) return c; }
            if (d >= most) continue;
            for (Direction dd : Direction.Plane.HORIZONTAL) for (int dy = -1; dy <= 1; dy++) { BlockPos n = c.relative(dd).above(dy); if (dist.containsKey(n) || !standable(lv, n)) continue; if (dy == 1 && !lv.getBlockState(c.above(2)).getCollisionShape(lv, c.above(2)).isEmpty()) continue; dist.put(n, d + 1); q.add(n); }
        }
        return null;
    }
    private boolean standable(ServerLevel lv, BlockPos c) {
        return lv.getBlockState(c).getCollisionShape(lv, c).isEmpty() && lv.getBlockState(c.above()).getCollisionShape(lv, c.above()).isEmpty() && lv.getFluidState(c).isEmpty() && lv.getFluidState(c.above()).isEmpty()
                && lv.getBlockState(c.below()).isFaceSturdy(lv, c.below(), Direction.UP) && !lv.getFluidState(c.below()).is(net.minecraft.tags.FluidTags.LAVA) && !lv.getBlockState(c.below()).is(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK);
    }
    private boolean solidAt(ServerLevel lv, BlockPos c) { return !lv.getBlockState(c).getCollisionShape(lv, c).isEmpty(); }
    /** Beside that standing cell, a drop of 5+ or lava within 5 under the edge: knockback there is death (16:58 wither skeletons knocked him off a fortress bridge into the lava sea). Null if none. */
    /** The drop off the worst open edge beside that standing cell, in blocks (up to 64), or -1 with lava (or no floor) down it. */
    static float edgeFall(ServerLevel lv, BlockPos c) {
        float worst = 0;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dz == 0) continue;
            BlockPos q = c.offset(dx, 0, dz);
            if (!lv.getBlockState(q).getCollisionShape(lv, q).isEmpty()) continue;
            int k = 0; BlockPos f = q.below();
            while (k < 64 && lv.getBlockState(f).getCollisionShape(lv, f).isEmpty() && lv.getFluidState(f).isEmpty()) { f = f.below(); k++; }
            if (k >= 64 || lv.getFluidState(f).is(net.minecraft.tags.FluidTags.LAVA)) return -1;
            if (!lv.getFluidState(f).isEmpty()) continue; // (water under it: no fall)
            worst = Math.max(worst, k + 1);
        }
        return worst;
    }
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
    public BlockPos pickEscapeNow(java.util.List<? extends net.minecraft.world.entity.Mob> mobs) { pickEscape(mobs); return escapeAt; } // (tests)
    /** The mob a walk takes him a block nearer than he is and within reach of (a creeper's blast 4, a hit 2.5), or null (stage 668). */
    private net.minecraft.world.entity.Mob walksPast(net.minecraft.world.level.pathfinder.Path path, java.util.List<? extends net.minecraft.world.entity.Mob> mobs) {
        for (var m : mobs) { double r = m instanceof net.minecraft.world.entity.monster.Creeper ? w("flee.creeper_clear", 5) : 2.5, now = m.position().distanceTo(g.position());
            for (int n = 1; n < path.getNodeCount(); n++) { double d = Vec3.atBottomCenterOf(path.getNode(n).asBlockPos()).distanceTo(m.position()); if (d < r && d < now - 1) return m; } } // (a block nearer at least: the first node beside him is a hair nearer on a run away too, stage 596)
        return null;
    }
    private void pickEscape(java.util.List<? extends net.minecraft.world.entity.Mob> mobs) {
        ServerLevel lv = level(); BlockPos feet = BlockPos.containing(g.getX(), g.getY() + 0.2, g.getZ()); Vec3 eye = g.getEyePosition(); int now = g.tickCount;
        String from = mobs.stream().map(m -> m.getType().getDescription().getString() + " " + (int) m.distanceTo(g) + " " + com.claudemod.entity.life.Perception.compassOf(m.position().subtract(g.position()))).toList().toString(); // (with the bearing: which run went through which mob)
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
        // 1b shooters on him: out of their sight, the nearest spot he walks to that none of them sees (round a corner, behind rock), held there; a run in the open or a hold in a straight passage only gives them more shots (World 17 09:40, 10:19; stage 773)
        if ((com.claudemod.entity.life.Food.regenS(g, 1) < w("flee.heals_within_s", 30) || g.tasks.stream().noneMatch(x -> x instanceof Task.GoTo)) && mobs.stream().anyMatch(m -> farShooter(m)) && mobs.stream().filter(m -> !farShooter(m) && m.distanceTo(g) < 16).count() <= w("flee.hide_melee_most", 1)) { BlockPos hide = hideSpot(lv, feet, mobs); // (held out of sight, what walks in is fought one at a time: two or more on foot after him and the held spot is a fight with them all; then the run on the heading out of their sight)
            if (hide != null) { var hp0 = g.getNavigation().createPath(hide, 0);
                if (hp0 != null && hp0.canReach() && walksPast(hp0, mobs) == null) { setEscape("hide", hide, null); com.claudemod.ClaudeMod.LOGGER.info("[Claude life] escape: out of the shooters' sight at {} ({} off on foot), held there, from {} (WorkGoal.pickEscape)", hide.toShortString(), hp0.getNodeCount(), from); return; } } }
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
            if (path == null || !path.canReach() || walksPast(path, mobs) != null) continue;
            setEscape("gap", gaps.get(i)[1], gaps.get(i)[0]); escapeSide = Direction.getNearest(gaps.get(i)[1].getX() - gaps.get(i)[0].getX(), 0, gaps.get(i)[1].getZ() - gaps.get(i)[0].getZ());
            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] escape: the 1-wide gap at {} ({} off, in sight, none of them nearer it), held from {} on its far side, from {} (WorkGoal.pickEscape)", gaps.get(i)[0].toShortString(), (int) Math.sqrt(gaps.get(i)[0].distSqr(feet)), gaps.get(i)[1].toShortString(), from);
            return;
        }
        // 3 a run: 16 out on the best heading he can walk (Day to Day, live 22:03 in a Nether basin: three runs in a row to ends 10-16 off up the basin's walls, "no closer in 2.5 s" each, 7 magma cubes round him, dead; his portal was 75 off). His known portal first when it isn't toward them, then the best heading turned 30 degrees at a time; each end must have a walk to it
        Vec3 base = fleeHeading(mobs), away = g.position().subtract(crowd).multiply(1, 0, 1); away = away.lengthSqr() < 1e-4 ? base : away.normalize();
        java.util.List<Vec3> heads = new java.util.ArrayList<>(); String portalNote = "";
        com.claudemod.entity.life.PortalMap.Portal known = null; double kd = 128;
        for (var p0 : com.claudemod.entity.life.PortalMap.net(g).portals) { if (!com.claudemod.entity.life.PortalMap.dim(lv).equals(p0.dim) || p0.at == null || "gone".equals(p0.state)) continue; double d0 = Math.sqrt(p0.at.distSqr(feet)); if (d0 < kd) { kd = d0; known = p0; } }
        BlockPos dest = null; for (Task x : g.tasks) if (x instanceof Task.GoTo gt) dest = gt.pos(); // where he's on his way to: the last walk queued behind the run (World 17 02:16, the village 63 west: four runs east toward his portal, dug in further off than he started)
        boolean nether = lv.dimension() == net.minecraft.world.level.Level.NETHER;
        if (known != null && dest != null && !nether) portalNote = ", his portal at " + known.at.toShortString() + " not first: he's on his way to " + dest.toShortString();
        else if (known != null) { Vec3 tp = Vec3.atCenterOf(known.at).subtract(g.position()).multiply(1, 0, 1); if (tp.lengthSqr() > 1e-4) { tp = tp.normalize(); if (tp.dot(away) > -0.2) { heads.add(tp); portalNote = ", toward his portal at " + known.at.toShortString() + " (" + (int) kd + " off)"; } else if (nether) { portalNote = ", his portal at " + known.at.toShortString() + " is through them: round them to it"; dest = known.at; } else portalNote = ", his portal at " + known.at.toShortString() + " is through them"; } } // (in the Nether his portal home is the way out: the headings below price the way round them to it. World 17 05:41:48, "through them", he ran north from it at 2 HP with nothing to eat, dead; stage 765)
        java.util.List<Vec3> turns = new java.util.ArrayList<>(); for (int r : new int[] {0, 30, -30, 60, -60, 90, -90, 120, -120, 150, -150, 180}) { double a = Math.toRadians(r); turns.add(new Vec3(base.x * Math.cos(a) - base.z * Math.sin(a), 0, base.x * Math.sin(a) + base.z * Math.cos(a))); }
        if (dest != null && dest.distSqr(feet) > 8 * 8) { // the headings that clear them, cheapest first: the walk the run costs him (blocks off his way, at a sprint) and how near it cuts by them
            Vec3 toDest = Vec3.atCenterOf(dest).subtract(g.position()).multiply(1, 0, 1).normalize(); final Vec3 aw = away;
            final boolean heals = com.claudemod.entity.life.Food.regenS(g, 1) < w("flee.heals_within_s", 30); // (no way to heal: out of their sight buys nothing, home is the way out; World 17 05:41, stage 765)
            double lostS = w("flee.dest_weight", 1.0) / com.claudemod.entity.life.Decide.sprint(), nearS = w("flee.toward_them_s", 8), keep = w("flee.keep_off_blocks", 6), awayS = w("flee.away_s", 1);
            final java.util.List<? extends net.minecraft.world.entity.Entity> ms = mobs;
            java.util.function.ToDoubleFunction<Vec3> price = hd -> { // (16 out: the blocks it takes off his way, or puts on it, at a sprint; how near the run takes him to each of them; a little for not turning his back on them)
                double s0 = -16 * hd.dot(toDest) * lostS + awayS * (1 - hd.dot(aw)) / 2;
                for (var m : ms) { Vec3 tm = m.position().subtract(g.position()).multiply(1, 0, 1); double along = Math.max(0, Math.min(16, tm.dot(hd))), near = tm.subtract(hd.scale(along)).length();
                    s0 += nearS * Math.max(0, (keep - near) / keep); }
                double arrows = heals ? sightHp(hd, ms) : 0; if (arrows > 0) s0 += com.claudemod.entity.life.Decide.hpS(g, 0, arrows, w("fight.arrow_hit_hp", 4)); // (the arrows on the way: a heading out of their sight is cheap) // (a side step keeps him as far off as now: no price; the closest pass, not the angle. World 17 02:16: one skeleton in front, every sideways heading priced as "toward them", he ran 31 further from the village)
                return s0; };
            turns.sort(java.util.Comparator.comparingDouble(price));
            if (!turns.isEmpty()) com.claudemod.ClaudeMod.LOGGER.info("[Claude decide] flee.heading (on his way to {}, {} off): {} heading(s) clear of them, best first: {} (WorkGoal.pickEscape)", dest.toShortString(), (int) Math.sqrt(dest.distSqr(feet)), turns.size(),
                turns.stream().limit(4).map(hd -> com.claudemod.entity.life.Perception.compassOf(hd) + " ~" + Math.round(price.applyAsDouble(hd)) + " s").toList());
        }
        else if (mobs.stream().anyMatch(m -> shoots(m))) { final java.util.List<? extends net.minecraft.world.entity.Entity> ms = mobs; java.util.Map<Vec3, Double> arrows = new java.util.HashMap<>(); for (Vec3 hd : turns) arrows.put(hd, sightHp(hd, ms)); // (no way he's going: still out of their sight first, the turn from straight away breaking ties)
            java.util.List<Vec3> order = new java.util.ArrayList<>(turns); turns.sort(java.util.Comparator.comparingDouble(hd -> arrows.get(hd) + order.indexOf(hd) * 0.01));
            com.claudemod.ClaudeMod.LOGGER.info("[Claude decide] flee.heading (shooters on him): {} heading(s), least arrows first: {} (WorkGoal.pickEscape)", turns.size(), turns.stream().limit(4).map(hd -> com.claudemod.entity.life.Perception.compassOf(hd) + " ~" + Math.round(arrows.get(hd)) + " HP").toList()); }
        heads.addAll(turns);
        BlockPos end = null; int tried = 0; java.util.List<String> nos = new java.util.ArrayList<>();
        int noStand = 0, recent = 0, thru = 0; boolean partial = false; BlockPos winding = null; Vec3 windDir = null; java.util.List<String> windy = new java.util.ArrayList<>();
        search:
        for (Vec3 hd : heads) for (int dist : new int[] {16, 10, 22}) {
            if (tried >= 12) break search;
            BlockPos e0 = BlockPos.containing(g.position().add(hd.scale(dist))); BlockPos e1 = null;
            for (int k = 0; k <= 12; k++) { if (standable(lv, e0.above(7 - k))) { e1 = e0.above(7 - k); break; } } // (7 up: a basin's or a valley's rim is an end too, if there's a walk up to it)
            if (e1 == null) { noStand++; continue; }
            if (g.attempts.within(com.claudemod.entity.life.Attempts.Kind.WALK, e1, now, 20 * 30)) { recent++; continue; }
            boolean through = false; for (var m : mobs) { Vec3 tm = m.position().subtract(g.position()).multiply(1, 0, 1); double along = tm.dot(hd); if (tm.length() < 3 && tm.normalize().dot(hd) > 0.8 || along > 0 && along < dist + w("flee.short_of_them_blocks", 8) && tm.subtract(hd.scale(along)).length() < (m instanceof net.minecraft.world.entity.monster.Creeper ? w("flee.creeper_clear", 5) : 2.5)) through = true; } // (and one just past the run's end: a run back toward them that stops short of them is no way out; stage 774, 12 back from a dead end into four withers) // (a creeper by the line: its blast, not its body, World 17 02:19 runs past three, one blew at 1, 20 -> 2) // (any mob ahead on the run's line, not only one within 3: live 13:59 11-04, two runs in a row into a wither skeleton 5 off on the line, 8 a hit, dead)
            if (through) { thru++; continue; }
            tried++; var path = g.getNavigation().createPath(e1, 1); double straight = Math.sqrt(e1.distSqr(feet)), walk = 0;
            if (path == null || path.getNodeCount() == 0) { if (nos.size() < 4) nos.add(e1.toShortString()); continue; }
            if (walksPast(path, mobs) != null) { thru++; continue; } // (the walk, not only the straight line: live 07:10 11-05, an end south-west whose walk went west first, past the creeper 6 off)
            for (int n = 1; n < path.getNodeCount(); n++) walk += Math.sqrt(path.getNode(n).asBlockPos().distSqr(path.getNode(n - 1).asBlockPos()));
            if (!path.canReach()) { // (a run needn't reach its end, only get him well along: stage 488's run off a fenced platform broke the skeleton's sight on the way. The live death was runs with no headway at all)
                double gain = straight - Math.sqrt(path.getEndNode().asBlockPos().distSqr(e1));
                if (gain < 6) { if (nos.size() < 4) nos.add(e1.toShortString() + " (" + (int) gain + " nearer at most)"); continue; }
                straight = gain; }
            if (walk > straight * 1.5 + 4) { if (winding == null) { winding = e1.immutable(); windDir = hd; } if (windy.size() < 4) windy.add(e1.toShortString() + " (" + (int) walk + " walked for " + (int) straight + ")"); continue; } // (a walk that winds away first reads as "no closer" to escapeBlocked's straight-line check, so it's only a fallback)
            end = e1.immutable(); fleeDir = hd; partial = !path.canReach(); break search;
        }
        if (end != null && partial) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] escape: no walk all the way to {}, but one that gets him well along it (WorkGoal.pickEscape)", end.toShortString());
        if (end == null && winding != null) { end = winding; fleeDir = windDir; }
        if (end == null) { fleeDir = base; end = BlockPos.containing(g.position().add(fleeDir.scale(16))); for (int k = 0; k <= 6; k++) { if (standable(lv, end.above(4 - k))) { end = end.above(4 - k); break; } }
            { var p0 = g.getNavigation().createPath(end, 1); var past = p0 == null ? null : walksPast(p0, mobs);
              if (past != null && !shoots(past)) { // every way out goes by them (a shooter's sight is no place to hold: stage 596's tower) (World 17 06:31: the walk out of his dip went up past the creeper, blown up; stage 768): no run, the reachable cell furthest from them instead
                BlockPos best = null; double bestD = mobs.stream().mapToDouble(m -> m.distanceTo(g)).min().orElse(0) + 1; int r = (int) w("flee.back_off_blocks", 8);
                for (BlockPos q : BlockPos.betweenClosed(feet.offset(-r, -2, -r), feet.offset(r, 2, r))) { if (!standable(lv, q)) continue;
                    double dq = mobs.stream().mapToDouble(m -> m.position().distanceTo(Vec3.atBottomCenterOf(q))).min().orElse(0); if (dq <= bestD) continue;
                    var pq = g.getNavigation().createPath(q, 0); if (pq == null || !pq.canReach() || walksPast(pq, mobs) != null) continue; best = q.immutable(); bestD = dq; }
                noWayOutTick = g.tickCount; com.claudemod.ClaudeMod.LOGGER.info("[Claude life] escape: every way out goes by the {} ({} through a mob): {} (WorkGoal.pickEscape)", past.getType().getDescription().getString(), thru, best == null ? "nowhere further from them to back off to, holding here" : "backing off to " + best.toShortString() + ", " + (int) bestD + " from them");
                end = best == null ? feet.immutable() : best; fleeDir = Vec3.atCenterOf(end).subtract(g.position()).multiply(1, 0, 1); fleeDir = fleeDir.lengthSqr() < 1e-4 ? base : fleeDir.normalize();
                setEscape("backoff", end, null); return; } }
            { BlockPos f0 = g.blockPosition(); boolean walled = false; // straight out runs into rock within 2: a dead end, no way out (stage 1072: a 1-wide tunnel's shut end, "straight out" into its wall, 17 -> 0 with no swing; live 04:09 and 06:15 11-08)
              for (int k = 1; k <= 2 && !walled; k++) { BlockPos q = BlockPos.containing(g.getX() + fleeDir.x * k, g.getY() + 0.2, g.getZ() + fleeDir.z * k); walled = solidAt(lv, q.above()) || solidAt(lv, q) && solidAt(lv, q.above(2)); }
              if (walled && portalNote.isEmpty()) { noWayOutTick = g.tickCount; com.claudemod.ClaudeMod.LOGGER.info("[Claude life] escape: no run end he can walk to ({} tried, no walk to {}; skipped {} with nowhere to stand, {} tried lately, {} through a mob) and straight out is rock within 2: no way out, holding here (WorkGoal.pickEscape)", tried, nos, noStand, recent, thru);
                  end = f0.immutable(); setEscape("backoff", end, null); return; } }
            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] escape: no run end he can walk to ({} tried, no walk to {}; skipped {} with nowhere to stand, {} tried lately, {} through a mob){}: straight out on the best heading (WorkGoal.pickEscape)", tried, nos, noStand, recent, thru, portalNote); }
        else if (!nos.isEmpty() || !windy.isEmpty() || !portalNote.isEmpty()) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] escape: run end {} has a walk to it{} ({} with none: {}; {} winding: {}) (WorkGoal.pickEscape)", end.toShortString(), portalNote, nos.size(), nos, windy.size(), windy);
        setEscape("run", end.immutable(), null);
        com.claudemod.ClaudeMod.LOGGER.info("[Claude life] escape: running {} to {} (no portal or 1-wide gap he gets to first; {} gap(s) looked at) from {} (WorkGoal.pickEscape)", com.claudemod.entity.life.Perception.compassOf(fleeDir), end.toShortString(), gaps.size(), from);
    }
    private Direction escapeSide; // the gap's far side, from the gap
    /** Through the gap and on: a run 16 out past it on its far side, the same escape's next leg (not a new pick). */
    private void runOnPast(String why) {
        ServerLevel lv = level(); Direction side = escapeSide == null ? Direction.getNearest(g.getX() - escapeAt.getX(), 0, g.getZ() - escapeAt.getZ()) : escapeSide;
        BlockPos end = escapeAt.relative(side, 16); for (int k = 0; k <= 8; k++) { if (standable(lv, end.above(4 - k))) { end = end.above(4 - k); break; } }
        com.claudemod.ClaudeMod.LOGGER.info("[Claude life] through the gap at {}: {}: on past it to {} (WorkGoal.atEscape)", escapeGap == null ? "?" : escapeGap.toShortString(), why, end.toShortString());
        escapeKind = "run"; escapeAt = end.immutable(); escapeGap = null; escapeHeld = false; escapeSince = g.tickCount; escapeDim = g.level().dimension(); escapeBest = g.position().distanceTo(Vec3.atBottomCenterOf(end)); escapeBestTick = g.tickCount;
        fleeDir = new Vec3(side.getStepX(), 0, side.getStepZ());
    }
    /** (tests) A run under way whose end is where he stands: the next flee tick is the run's end. */
    public void towerCoverForTest(BlockPos base, int high, Task.Wait wait) { digInAt = base.immutable(); digInTick = g.tickCount; digInRise = high; digInTasks = new java.util.ArrayList<>(java.util.List.of(new Task.Pillar(high), wait)); g.tasks.addFirst(wait); }
    public void runEndForTest() { setEscape("run", g.blockPosition().immutable(), null); }
    public String escapeHoldForTest(BlockPos at) { return escapeHoldForTest(at, "hide"); }
    public String escapeHoldForTest(BlockPos at, String kind) { if (at != null) { setEscape(kind, at, null); escapeHeld = true; } return escapeKind + "@" + (escapeAt == null ? "-" : escapeAt.toShortString()) + (escapeHeld ? " held" : ""); } // (tests: a hide he holds; what his escape is now)
    /** (tests) An escape through the gap held from at, its plug queued and still to go in. */
    public void plugPendingForTest(BlockPos gap, BlockPos at, net.minecraft.world.item.Item plug) { setEscape("gap", at.immutable(), gap.immutable()); escapeHeld = true; escapePlugged = true; g.tasks.addFirst(new Task.Place(gap.above().immutable(), plug)); g.tasks.addFirst(new Task.Place(gap.immutable(), plug)); }
    private void setEscape(String kind, BlockPos at, BlockPos gap) {
        escapeKind = kind; escapeAt = at; escapeGap = gap; escapeHeld = false; escapePlugged = false; escapeSince = g.tickCount; escapeDim = g.level().dimension(); escapeBest = g.position().distanceTo(Vec3.atBottomCenterOf(at)); escapeBestTick = g.tickCount; escapePicks++;
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
            String odds0 = fightOdds; double pf0 = pricedFightS, pr0 = pricedRunS; // (the re-ask leaves the run's own sizing-up as it was: the cornered choice and the log read it)
            net.minecraft.world.entity.Mob runFrom = null; for (var m : mobs) if (m.isAlive() && shouldFlee(m)) { runFrom = m; break; } // (at each run's end the fight is asked again: what broke it off (a creeper's hiss out of his reach) may be over, live 02:39 World 17: 35 s of runs from a creeper priced ~13 s to kill, caught in a cave, 19 -> 8, stage 794)
            if (runFrom == null && !mobs.isEmpty()) { var m0 = mobs.stream().min(java.util.Comparator.comparingDouble((net.minecraft.world.entity.Mob m) -> m instanceof net.minecraft.world.entity.monster.Creeper ? 0 : 1).thenComparingDouble(m -> m.distanceTo(g))).get(); /* a creeper after him first: a fight with anything else walks him back into its 7 and the creeper reflex breaks it off (live 08:44 11-07: run, fight the zombie, run, x3; stage 1000) */ com.claudemod.ClaudeMod.LOGGER.info("[Claude life] escape run's end at {} reached: the fight with the {} {} off is the better way again ({}): turning to it (WorkGoal.atEscape)", escapeAt.toShortString(), m0.getType().getDescription().getString(), (int) m0.distanceTo(g), fightOdds); clearEscape(); g.tasks.addFirst(new Task.Attack(m0.getUUID(), 20 * 30)); return 2; }
            fightOdds = odds0; pricedFightS = pf0; pricedRunS = pr0;
            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] escape run's end at {} reached, them still on him: on from here (WorkGoal.atEscape)", escapeAt.toShortString());
            clearEscape(); pickEscape(mobs); return 0;
        }
        if ("hide".equals(escapeKind)) { // out of the shooters' sight: held there; what walks in after him is hit as it comes in reach, one at a time; a shooter that sees him again: on to the next spot out of its sight
            if (d >= 1.5) return 0;
            if (!escapeHeld) { escapeHeld = true; g.setSprinting(false); g.getNavigation().stop(); com.claudemod.ClaudeMod.LOGGER.info("[Claude life] escape: out of their sight at {}: holding, hitting what comes in reach (WorkGoal.atEscape)", escapeAt.toShortString()); }
            for (var m : mobs) if (farShooter(m) && m.isAlive() && com.claudemod.entity.life.Perception.clear(lv, m.getEyePosition(), Vec3.atBottomCenterOf(escapeAt).add(0, 1.6, 0))) { com.claudemod.ClaudeMod.LOGGER.info("[Claude life] escape: the {} {} off sees him again at {}: on out of its sight (WorkGoal.atEscape)", m.getType().getDescription().getString(), (int) m.distanceTo(g), escapeAt.toShortString()); clearEscape(); pickEscape(mobs); return 0; }
            net.minecraft.world.entity.Mob close = null; for (var m : mobs) if (!farShooter(m) && m.isAlive() && (close == null || m.distanceTo(g) < close.distanceTo(g))) close = m;
            if (close != null && close.distanceTo(g) < 5) { equipWeapon(); g.getLookControl().setLookAt(close); if (canHitNow(close)) { g.attack(close); g.swing(InteractionHand.MAIN_HAND); } }
            return 1;
        }
        if ("backoff".equals(escapeKind)) { // as far from them as he gets without going by them: held there till the run's clock is out (a re-pick here was the same cell, 3 times a tick)
            if (d >= 1.5) return 0;
            if (!escapeHeld) { escapeHeld = true; g.setSprinting(false); g.getNavigation().stop(); com.claudemod.ClaudeMod.LOGGER.info("[Claude life] escape: backed off to {}, as far from them as he gets: holding (WorkGoal.atEscape)", escapeAt.toShortString()); }
            return 1;
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
            String holdNo = gapHoldNo(mobs); if (holdNo != null) { runOnPast(after + " after him, " + (int) g.getHealth() + " health, " + plug.getCount() + " blocks to plug it: " + holdNo); return 0; } // (priced, escape.gap_hold: was "more than 3 after him, a weak fighter, no armor or under 12 health") // (holding it against six cost him 20 health in 14 s and his life; through the gap they come single file and he outruns them)
            escapeSwings = 0;
            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] holding the gap at {} from {}: one at a time as they come through ({} blocks to plug it, health {}) (WorkGoal.atEscape)", escapeGap == null ? "?" : escapeGap.toShortString(), escapeAt.toShortString(), plug.getCount(), (int) g.getHealth());
        }
        // the plug he went for: still to go in (a hit set him running again before the blocks did), or in
        if (escapePlugged && escapeGap != null) {
            final BlockPos gp = escapeGap;
            boolean open = lv.getBlockState(gp).getCollisionShape(lv, gp).isEmpty() || lv.getBlockState(gp.above()).getCollisionShape(lv, gp.above()).isEmpty();
            boolean queued = g.tasks.stream().anyMatch(x -> x instanceof Task.Place pl && (pl.pos().equals(gp) || pl.pos().equals(gp.above())));
            boolean inIt = mobs.stream().anyMatch(m -> m.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(gp).minmax(new net.minecraft.world.phys.AABB(gp.above()))));
            if (open && queued && !inIt) { // the plug, priced against running on: it shuts them out only from the gap's far side (World 17 22:21:27-31, death 313: back on the creeper's side, "placing it before anything" ended each flee on its first tick, the creeper 5 -> 2)
                Vec3 gc = Vec3.atBottomCenterOf(gp), safe = Vec3.atBottomCenterOf(escapeAt).subtract(gc).multiply(1, 0, 1);
                java.util.function.ToDoubleFunction<Vec3> side = q -> q.subtract(gc).multiply(1, 0, 1).dot(safe);
                boolean heSafe = side.applyAsDouble(g.position()) > 0.1; var mobSafe = mobs.stream().filter(m -> side.applyAsDouble(m.position()) > 0.1 && m.distanceTo(g) < 8).findFirst().orElse(null);
                var plugIt = heSafe && mobSafe == null ? com.claudemod.entity.life.Decide.Option.of("plug it", com.claudemod.entity.life.Weights.w("escape.plug_s", 1), "two blocks across it")
                        : com.claudemod.entity.life.Decide.Option.out("plug it", heSafe ? "a " + mobSafe.getType().getDescription().getString() + " " + (int) mobSafe.distanceTo(g) + " off on his side of it" : "he's on their side of it: a plug shuts him in with them");
                var pick = com.claudemod.entity.life.Decide.choose(g, "escape.plug", "the gap at " + gp.toShortString() + ", health " + (int) g.getHealth(), plugIt.no() != null, java.util.List.of(plugIt, com.claudemod.entity.life.Decide.Option.of("run on", com.claudemod.entity.life.Weights.w("escape.run_on_s", 8), "a fresh way off")));
                if (pick == plugIt) { com.claudemod.ClaudeMod.LOGGER.info("[Claude life] the plug for the gap at {} still to go in: placing it before anything (WorkGoal.atEscape)", gp.toShortString()); return 2; }
                g.tasks.removeIf(x -> x instanceof Task.Place pl && (pl.pos().equals(gp) || pl.pos().equals(gp.above()))); clearEscape(); return 0;
            }
            if (!open && mobs.stream().noneMatch(m -> m.distanceTo(g) < 4)) { com.claudemod.ClaudeMod.LOGGER.info("[Claude life] the gap at {} is plugged, none of them this side: the run's over (WorkGoal.atEscape)", gp.toShortString()); return 2; }
        }
        // holding: on his spot, facing the gap, a swing at whatever is in reach once the swing is charged
        { String holdNo = gapHoldNo(mobs); if (holdNo != null) { runOnPast("health " + (int) g.getHealth() + " holding it: " + holdNo); return 0; } } // (priced: was "under 10 health")
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
        for (var m : ((ServerLevel) level()).getEntitiesOfClass(net.minecraft.world.entity.Mob.class, g.getBoundingBox().inflate(48), m -> m.isAlive() && fleeFromIds.contains(m.getUUID()) && (m.distanceTo(g) < 24 || huntsHim(m)))) if (!mobs.contains(m)) mobs.add(m); // (and past 24 while it still hunts him, up to its follow range: incident 256) // what he's running from stays what he's running from till he's clear of it (out of sight behind him is still on his heels: the run ended a second in and his walk took him back past the witch)
        // a lit creeper he can't outrun: the shield up, facing it, takes the blast (live 06:31 World 17: the run's way out went past it, 20 -> 0 with a shield in his off hand)
        if (g.getItemBySlot(EquipmentSlot.OFFHAND).is(net.minecraft.world.item.Items.SHIELD) && !g.getCooldowns().isOnCooldown(net.minecraft.world.item.Items.SHIELD)) {
            net.minecraft.world.entity.monster.Creeper lit = null;
            for (var m : mobs) if (m instanceof net.minecraft.world.entity.monster.Creeper cr && cr.getSwellDir() > 0 && (lit == null || cr.distanceTo(g) < lit.distanceTo(g))) lit = cr;
            if (lit != null) {
                net.minecraft.world.phys.Vec3 off = g.position().subtract(lit.position()); double away = off.horizontalDistanceSqr() < 1e-6 ? 0 : g.getDeltaMovement().x * off.x / off.horizontalDistance() + g.getDeltaMovement().z * off.z / off.horizontalDistance();
                double left = (1 - lit.getSwelling(1f)) * 28, out = lit.distanceTo(g) + left * Math.max(0, away); // its fuse left (ticks) and how far he'll be at the bang at the speed he's getting away now (a dead end, or a way out past it, is none: 06:31)
                if (out < w("creeper.blast_reach", 7) && hitsBeforeBang(lit)) { // his swing lands before the bang: the hit knocks it back out of its fuse, the shield only takes the blast (behind it a skeleton shot him 4 times while he faced one down for 10 s; stage 772)
                    g.getNavigation().stop(); g.setSprinting(false); if (g.isUsingItem() && g.getUsedItemHand() == InteractionHand.OFF_HAND) shieldDown(); equipWeapon(); g.getLookControl().setLookAt(lit);
                    if (canHitNow(lit)) { g.attack(lit); g.swing(InteractionHand.MAIN_HAND); com.claudemod.ClaudeMod.LOGGER.info("[Claude life] a lit creeper {} off, ~{} ticks to the bang: hit first, knocked back out of its fuse (WorkGoal.doFlee)", String.format("%.1f", lit.distanceTo(g)), (int) left); }
                    return false;
                }
                if (out < w("creeper.blast_reach", 7)) {
                    g.getNavigation().stop(); g.setSprinting(false); shieldUp(lit);
                    if (ticks % 10 == 0 || !g.isUsingItem()) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] a lit creeper {} off, ~{} ticks to the bang: {} blocks at best by then, inside its {}: shield up, facing it (WorkGoal.doFlee)", String.format("%.1f", lit.distanceTo(g)), (int) left, String.format("%.1f", out), (int) w("creeper.blast_reach", 7));
                    return false;
                }
            }
            if (g.isUsingItem() && g.getUsedItemHand() == InteractionHand.OFF_HAND && lit == null) { boolean other = false; for (var m : mobs) if (incoming(m)) other = true; if (!other) shieldDown(); } // the bang's over (or it went out): down, to run at full speed
        }
        if (CombatBrain.dodge(g, g.getItemBySlot(EquipmentSlot.OFFHAND).is(net.minecraft.world.item.Items.SHIELD) && !g.getCooldowns().isOnCooldown(net.minecraft.world.item.Items.SHIELD)) != null) return false; // (a fang line or a shot across his run: the step first)
        // hit and run, like a player: a charged swing at one in reach as he goes (its knockback buys the distance), the run goes on (combat review: 5 s charged and in reach, no swing, 7 hits taken running; bench 1081)
        if (g.getAttackStrengthScale(0.5f) >= w("flee.swing_charge", 0.95)) {
            net.minecraft.world.entity.Mob hitOne = null;
            for (var m : mobs) if (!(m instanceof net.minecraft.world.entity.monster.Creeper) && m.getTarget() == g && canHitNow(m) && (hitOne == null || m.distanceTo(g) < hitOne.distanceTo(g))) hitOne = m;
            if (hitOne != null) { equipWeapon(); if (g.isUsingItem() && g.getUsedItemHand() == InteractionHand.OFF_HAND) shieldDown(); g.getLookControl().faceNow(hitOne.getBoundingBox().getCenter()); g.attack(hitOne); g.swing(InteractionHand.MAIN_HAND); }
        }
        // cornered (a dungeon, a dead end): running isn't getting him anywhere, so like a player he turns and fights
        if (ticks == 1) fleeFrom = g.position();
        // (a back-off hold is a corner too: it never swings, and the run's clock never ends a hold; live 14:30 11-07 two hoglins, 30 s shield up, 20 -> 0, stage 1052)
        if (ticks >= 30 && ticks % 30 == 0 && (!escapeHeld || "backoff".equals(escapeKind)) && fleeFrom != null && g.position().distanceTo(fleeFrom) < 2.0 && !mobs.isEmpty() // every 1.5 s of the run, not only its first (live 12:07 11-02: escapes blocked x7 on one block, a spider 20 -> 0, never a swing back)
                && !mobs.stream().allMatch(m -> (com.claudemod.entity.goals.WorkGoal.shoots(m)) && m.distanceTo(g) > 3) // "cornered" by a crossbow 8 blocks off is not a fight to wade into (health 16 to 1 in 10 s)
                && !(mobs.stream().min(java.util.Comparator.comparingDouble(m -> m.distanceTo(g))).get() instanceof net.minecraft.world.entity.monster.Creeper)) { // and never a creeper: "fighting his way out" of one is the explosion
            var near = mobs.stream().min(java.util.Comparator.comparingDouble(m -> m.distanceTo(g))).get();
            shouldFlee(near); boolean unpriced = Double.isNaN(pricedFightS); double fightS = unpriced ? Double.MAX_VALUE / 4 : pricedFightS, runS = (Double.isNaN(pricedRunS) ? w("flee.run_s", 20) : pricedRunS) + w("flee.cornered_run_s", 30); // (fight.flee's own prices, refreshed; the run from a corner costs its dead ends again)
            double build = w("cover.build_s", 2.5), buildHp = 0; for (var m : mobs) if (m.distanceTo(g) < 4 && !shoots(m)) buildHp += w("fight.mob_hits_per_s", 0.8) * build * mobHit(m);
            double coverS = build + com.claudemod.entity.life.Decide.hpS(g, 0, buildHp, 3) + w("cover.wait_out_s", 60);
            double meleeOn = mobs.stream().filter(m -> !shoots(m)).mapToDouble(m -> m.distanceTo(g)).min().orElse(99);
            var corner = com.claudemod.entity.life.Decide.choose(g, "flee.cornered", (int) g.getHealth() + " HP, " + mobs.size() + " on him, " + near.getType().getDescription().getString() + " " + (int) near.distanceTo(g) + " off", true, java.util.List.of(
                unpriced ? com.claudemod.entity.life.Decide.Option.out("fight", "not priced: " + fightOdds) : com.claudemod.entity.life.Decide.Option.of("fight", weakFighter() ? Double.MAX_VALUE / 4 : fightS, weakFighter() ? "bare hands or wood" : "his way out through them"),
                meleeOn < 6 ? com.claudemod.entity.life.Decide.Option.out("cover", String.format("a melee mob %.1f off: a tower needs 6+, a hole 12+ (the cover proximity rule)", meleeOn)) : com.claudemod.entity.life.Decide.Option.of("cover", coverS, "dug in or boxed here, ~" + Math.round(buildHp) + " HP while it goes up"), // (priced as open while digInFromArrows would refuse it: "cover" won and he did the fight it had priced dearer, live 18:39 and 20:25 11-08)
                "backoff".equals(escapeKind) ? com.claudemod.entity.life.Decide.Option.out("run", "every way out goes past them (held where he backed off to)") : com.claudemod.entity.life.Decide.Option.of("run", runS, "on, another way"))); // (priced as a getaway that isn't there, run won every 1.5 s and he died holding: stage 1052)
            String pick = corner == null ? "fight" : corner.name();
            if (pick.equals("run")) { com.claudemod.ClaudeMod.LOGGER.info("[Claude life] cornered (health {}): on another way (WorkGoal.doFlee)", (int) g.getHealth()); clearEscape(); pickEscape(mobs); fleeFrom = g.position(); return false; }
            if (pick.equals("cover") && !weakFighter()) { desperateNow = true; boolean dug = digInFromArrows(); desperateNow = false; if (dug) { com.claudemod.ClaudeMod.LOGGER.info("[Claude life] cornered (health {}): digging in", (int) g.getHealth()); return true; } }
            if (weakFighter()) { // bare hands or a wooden sword: "fighting his way out" is 20 punches at 3 damage a bite (five deaths in two minutes at his bed); cover instead
                desperateNow = true;
                boolean dug = digInFromArrows();
                desperateNow = false;
                com.claudemod.ClaudeMod.LOGGER.info("[Claude life] cornered unarmed (health {}): {}", (int) g.getHealth(), dug ? "digging in" : "no cover to be had, running on");
                if (dug) return true;
                fleeFrom = g.position(); // (another 30 ticks of running before this is looked at again)
            } else {
            cornerUntil = g.tickCount + 200;
            g.setSprinting(false); clearEscape();
            g.tasks.addFirst(new Task.Attack(near.getUUID(), 200));
            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] cornered (health {}): fighting his way out", (int) g.getHealth());
            return true;
            }
        }
        if (ticks % 30 == 0) fleeFrom = g.position(); // (the next check: 1.5 s of running from here)
        if (mobs.isEmpty() || (ticks > t.maxTicks() && !escapeHeld)) { // (holding his gap: as long as they come)
            g.setSprinting(false);
            if (mobs.isEmpty()) clearEscape();
            if (g.urgentAttacker != null) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] the run's over ({}): the hit he took while running is left behind, no fight for it (WorkGoal.doFlee)", mobs.isEmpty() ? "nothing after him" : "out of time, " + ticks + " ticks");
            g.urgentAttacker = null; // whatever hit him while he ran is behind him now: no running again from a mob that's gone
            staleWalk();
            healUp();
            return true;
        }
        // One escape, kept till it's reached or proven blocked (live 16:25: a new heading every 8-20 ticks, round in circles in a horde he outruns two to one)
        if (escapeAt != null && g.tickCount - escapeSince > 20 * 60) { com.claudemod.ClaudeMod.LOGGER.info("[Claude life] escape {} at {} a minute old: picked fresh (WorkGoal.doFlee)", escapeKind, escapeAt.toShortString()); clearEscape(); }
        if (ticks == 1 && escapeAt != null && escapeHeld && "hide".equals(escapeKind)) { var onHim = mobs.stream().filter(m -> !shoots(m) && m.distanceTo(g) < w("flee.hide_melee_reach", 4)).findFirst().orElse(null); // a hide is out of the shooters' sight only: a mob on him there is no answer to it (live 07:20 11-07 death 213: the run kept the held hide, back into the zombified piglins, 14 -> 0 in 4 s; stage 757)
            if (onHim != null) { com.claudemod.ClaudeMod.LOGGER.info("[Claude life] a new run with the {} {} off on him: his hide at {} keeps only the shooters off, a fresh escape (WorkGoal.doFlee)", onHim.getType().getDescription().getString(), String.format("%.1f", onHim.distanceTo(g)), escapeAt.toShortString()); clearEscape(); } }
        if (escapeAt != null && escapeDim != g.level().dimension()) { com.claudemod.ClaudeMod.LOGGER.info("[Claude life] escape {} at {} was in {}: he's in {} now, picked fresh (WorkGoal.doFlee)", escapeKind, escapeAt.toShortString(), escapeDim == null ? "?" : escapeDim.location().getPath(), g.level().dimension().location().getPath()); clearEscape(); } // (live pair 1052 -> 765: a back-off held in the overworld, the next run in the Nether went for its coordinates)
        if (ticks == 1 && escapeAt != null && (!escapeHeld || "backoff".equals(escapeKind)) && g.tickCount - escapeSince > 100) clearEscape(); // (a back-off is from the last run's mobs, held only till that run's clock was out) // (a new run well after the last: from here, fresh; within 5 s it's the same run)
        if (escapeAt == null) pickEscape(mobs);
        else if (ticks % 10 == 1 && !escapeHeld) {
            String why = escapeBlocked(mobs);
            if (why != null) {
                { var hm = nearestHostile(); g.attempts.fail(com.claudemod.entity.life.Attempts.Kind.WALK, escapeAt, g.tickCount, g.blockPosition(), com.claudemod.entity.life.Attempts.Reason.BLOCKED_MOB, why, hm == null ? null : hm.getUUID()); }
                if ("run".equals(escapeKind) && fleeDir != null) fleeDead.add(fleeDir);
                com.claudemod.ClaudeMod.LOGGER.info("[Claude life] escape {} at {} blocked: {}; on his attempts, another (WorkGoal.escapeBlocked)", escapeKind, escapeAt.toShortString(), why);
                clearEscape(); pickEscape(mobs);
            }
        }
        if (escapeAt != null) { int at = atEscape(mobs); if (at == 1) return false;
            if (at == 2) { /* the run's end decided (the fight it turned to, the plug, or over): the hit he took on the way was deferred to it ("the run's end decides"), settled now, never re-judged next tick over that choice (live 21:50 11-08: the run's end turned him to the Creeper 6 off, the Skeleton's arrow from the run had him "fighting back: Skeleton" a tick later, the creeper blew 18 -> 8; stage 1143) */
                if (g.urgentAttacker != null) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] the run's end decided: the hit he took while running is settled by it, no fight of its own (WorkGoal.doFlee)");
                g.urgentAttacker = null; return true; } }
        if (escapeAt != null && !"run".equals(escapeKind) && g.position().distanceTo(Vec3.atBottomCenterOf(escapeAt)) < 2.0) { Vec3 dest = Vec3.atBottomCenterOf(escapeAt); g.getNavigation().stop(); g.getMoveControl().setWantedPosition(dest.x, dest.y, dest.z, 1.2); } // the last steps onto the spot itself
        else if (ticks % 8 == 1 && escapeAt != null) { // the way to it, re-made as he goes: the same place every time
            Vec3 dest = Vec3.atBottomCenterOf(escapeAt);
            g.setSprinting(g.getFoodData().getFoodLevel() > 6);
            if (!g.getNavigation().moveTo(dest.x, dest.y, dest.z, 1.4)) g.getMoveControl().setWantedPosition(dest.x, dest.y, dest.z, 1.4);
        }
        if (ticks > 20 && !escapeHeld && !(escapeAt != null && !"run".equals(escapeKind)) && mobs.stream().allMatch(m -> m.distanceTo(g) > 16 || (m.distanceTo(g) > 10 && !com.claudemod.entity.life.Perception.clear((ServerLevel) level(), m.getEyePosition(), g.getEyePosition())))) { // well clear, and out of their sight: the run worked
            if (!clearOfHunters(mobs)) { if (g.horizontalCollision && g.onGround()) g.getJumpControl().jump(); return false; }
            g.setSprinting(false); if (g.urgentAttacker != null) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] clear of them: the hit he took while running is left behind, no fight for it (WorkGoal.doFlee)"); g.urgentAttacker = null;
            clearEscape();
            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] got clear of them ({} ticks of running)", ticks);
            healUp();
            return true;
        }
        if (g.horizontalCollision && g.onGround()) g.getJumpControl().jump();
        return false;
    }

    /** Still after him: it has him as its target and he's inside its follow range (past it, the mob drops him). */
    private boolean huntsHim(net.minecraft.world.entity.Mob m) { return m.getTarget() == g && m.distanceTo(g) < m.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.FOLLOW_RANGE); }
    private int fleeHuntSaid = -1000;
    /**
     * Far enough off, but is anything still hunting him? (World 17 22:14, incident 256: "got clear" 16 off a zombie still after him,
     * his job walked him back into it, 20 -> 10.) One still on him: back to his job (it follows him there: flee.hunted_hits of its
     * hits) against running on till he's past its follow range (the gap at his pace less its chase pace), priced and said.
     */
    private boolean clearOfHunters(java.util.List<? extends net.minecraft.world.entity.Mob> mobs) {
        net.minecraft.world.entity.Mob h = null; for (var m : mobs) if (huntsHim(m) && (h == null || m.distanceTo(g) < h.distanceTo(g))) h = m;
        if (h == null) return true;
        double d = h.distanceTo(g), range = h.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.FOLLOW_RANGE);
        double pace = (g.getFoodData().getFoodLevel() > 6 ? com.claudemod.entity.life.Decide.sprint() : w("move.walk_blocks_per_s", 4.3)) - w("flee.chase_blocks_per_s", 2.5);
        var back = com.claudemod.entity.life.Decide.Option.of("back to his job", com.claudemod.entity.life.Decide.hpS(g, 0, w("flee.hunted_hits", 2) * mobHit(h), mobHit(h)), "it follows him there");
        var on = pace <= 0.1 ? com.claudemod.entity.life.Decide.Option.out("run on", "it keeps his pace")
                : com.claudemod.entity.life.Decide.Option.of("run on", (range - d) / pace, Math.round(range - d) + " blocks to its follow range " + Math.round(range));
        boolean say = g.tickCount - fleeHuntSaid >= 100; if (say) fleeHuntSaid = g.tickCount;
        var pick = com.claudemod.entity.life.Decide.choose(g, "flee.clear", h.getType().getDescription().getString() + " " + Math.round(d) + " off still after him, health " + (int) g.getHealth(), say, java.util.List.of(back, on));
        return pick != on;
    }

    /**
     * Clear of them and hurt: eat to heal (a full hunger bar heals fast), and badly hurt, hang back a while first (a player
     * doesn't walk back in at 3 hearts). One eat and one wait at most: never a pile of them from a run of hits.
     */
    /** The walk queued before the run still starts where the run began: only its end is kept, walked to from where he is now (live 09:41 World 17: the run over at health 9, the old path's nodes walked him 30 blocks back into the two skeletons he'd run from; shot dead). */
    private void staleWalk() {
        java.util.List<Task> l = new java.util.ArrayList<>(g.tasks);
        int i = 0; while (i < l.size() && !(l.get(i) instanceof Task.GoTo || l.get(i) instanceof Task.Leap)) i++;
        int j = i; while (j < l.size() && (l.get(j) instanceof Task.GoTo || l.get(j) instanceof Task.Leap)) j++;
        if (j - i < 2) return;
        BlockPos head = l.get(i) instanceof Task.GoTo go ? go.pos() : ((Task.Leap) l.get(i)).from();
        if (head.distSqr(g.blockPosition()) <= 4 * 4) return; // (still on it: nothing stale)
        Task last = l.get(j - 1); BlockPos end = last instanceof Task.GoTo go ? go.pos() : ((Task.Leap) last).land();
        l.subList(i, j).clear(); l.add(i, new Task.GoTo(end, last instanceof Task.GoTo go ? go.within() : 0.9));
        g.tasks.clear(); g.tasks.addAll(l);
        com.claudemod.ClaudeMod.LOGGER.info("[Claude life] the run's over at {}: the walk queued before it started back at {} ({} steps): dropped, on to its end {} from here (WorkGoal.staleWalk)", g.blockPosition().toShortString(), head.toShortString(), j - i, end.toShortString());
    }
    private void healUp() {
        // a skeleton still drawing on him: no standing there eating in its sights; cover first (the dig-in), the eating from in there
        if (!diggingIn() && !com.claudemod.entity.life.Perception.known(g, net.minecraft.world.entity.monster.Monster.class, g.getBoundingBox().inflate(20), m -> m.isAlive() && (com.claudemod.entity.goals.WorkGoal.shoots(m)) && (m.getTarget() == g || m.distanceTo(g) < 10)).isEmpty()) {
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
        for (var m : com.claudemod.entity.life.Perception.known(g, net.minecraft.world.entity.Mob.class, g.getBoundingBox().inflate(24), m -> m.isAlive() && (com.claudemod.entity.goals.WorkGoal.shoots(m))))
            return m.getType().getDescription().getString() + " " + (int) m.distanceTo(g) + " off (" + (com.claudemod.entity.life.Perception.seesNow(g, m) ? "in sight" : "heard or felt") + ")";
        for (var m : com.claudemod.entity.life.Perception.lately(g, 24)) if (com.claudemod.entity.goals.WorkGoal.shoots(m)) return m.getType().getDescription().getString() + " sensed lately";
        return null;
    }
    private java.util.Map<String, Integer> noCoverSaid; private int shooterHeld = -1, coverHitSaid = -1; // (lazy: fields added live start null)
    /** No cover here, and why: each reason said once a minute (the numbers in it aside), with the method's name. */
    public String lastNoCoverWhy; public int lastNoCoverTick = -1; // (the shelter skill reports this, not a guess: "nothing to tower with" with 287 blocks on him when a creeper was the reason, live 11:47)
    private boolean noCover(String why) {
        lastNoCoverWhy = why; lastNoCoverTick = g.tickCount;
        if (noCoverSaid == null || noCoverSaid.size() > 64) noCoverSaid = new java.util.HashMap<>();
        String k = why.replaceAll("[-0-9.]+", "#"); Integer at = noCoverSaid.get(k);
        if (at == null || g.tickCount - at >= 20 * 60) { noCoverSaid.put(k, g.tickCount); com.claudemod.ClaudeMod.LOGGER.info("[Claude life] no cover: {} (WorkGoal.digInFromArrows)", why); }
        return false;
    }

    private int cfEatTick = -100000; // (coverFight: when it chose a meal where he stands)
    private double cfBackBest = Double.MAX_VALUE; private int cfBackTick = -1; // (coverFight: the nearest he got to the cover on the way back, and when)
    private boolean coverGap; // the last digInFromArrows said no for now: a melee mob too close to finish the cover (the cover proximity rule), so he runs for the gap
    private boolean noCoverGap(String why) { coverGap = true; return noCover(why); }
    /** The last no-cover was the gap kind (a mob too close to finish it, or the edge): run for the gap, cover after. */
    public boolean coverGapNow() { return lastNoCoverTick == g.tickCount && coverGap; }
    private boolean digInFromArrows() {
        if (leaveBore != null && current == leaveBore) return noCover("tunnelling out of his cover under the rock: the tunnel is his cover"); // (World 17 05:24 fix: a skeleton heard above walled him in every block of it, 12 times; stage 769)
        coverGap = false;
        ServerLevel level = level();
        BlockPos feet = g.blockPosition();
        if (diggingIn()) {
            if (inDigInHole()) return true; // at it already (or down in it): carry on (one threat, one cover: never a second over it)
            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] knocked off his cover at {} (he's at {}): its steps dropped, cover again from here if anything still calls for it (WorkGoal.digInFromArrows)", digInAt.toShortString(), feet.toShortString());
            g.tasks.removeAll(digInTasks); // knocked off the spot: that hole's no use, start again here
            digInAt = null;
        }
        var shooters = new java.util.ArrayList<>(com.claudemod.entity.life.Perception.known(g, net.minecraft.world.entity.monster.Monster.class, g.getBoundingBox().inflate(24),
                m -> m.isAlive() && (com.claudemod.entity.goals.WorkGoal.shoots(m)) && (m.getTarget() == g || m.distanceTo(g) < 12 || m.hasLineOfSight(g)))); // (in sight within 24 counts: one 15 off shot him off a bare tower twice) skeletons and pillagers alike (a bare tower against a crossbow killed him)
        { var shot = g.getLastHurtByMob(); if ((com.claudemod.entity.goals.WorkGoal.shoots(shot)) && shot.isAlive() && g.tickCount - g.getLastHurtByMobTimestamp() < 200 && shot.distanceTo(g) < 32 && !shooters.contains(shot)) shooters.add((net.minecraft.world.entity.monster.Monster) shot); } // what shot him in the last 10 s is a shooter, seen or not, like a player knows (live 18:13 11-04: shot twice, then out of sight: "0 shooter(s)", a bare 3-high dirt tower, shot off it 20 -> 0)
        int crowd = (int) hostilesNear(14).stream().filter(m -> !pest(m)).count();
        boolean swarmed = ((!armed() || weakKit()) && crowd >= 2) || crowd >= (prepared() ? 4 : 3) // (the combat rule: a crowd is left alone unless he's prepared, iron and food: in iron with a diamond sword and six meals two skeletons and a zombie had him boxed in, live 04:42) a crowd (or two with nothing proper to fight them in): a covered hole or a tower stops them too
                || (!armed() && crowd >= 1 && !com.claudemod.director.ClaudeLife.dayHere(level)); // unarmed at night with anything on him: running from a zombie as fast as him is how a fresh respawn died twice; two down and a lid
        java.util.List<net.minecraft.world.entity.Mob> phantoms = com.claudemod.entity.life.Perception.known(g, net.minecraft.world.entity.Mob.class, g.getBoundingBox().inflate(32), m -> m.isAlive() && m instanceof net.minecraft.world.entity.monster.Phantom);
        boolean desperate = g.getHealth() <= 4 && com.claudemod.director.ClaudeLife.foodToEat(g, true) == null && !enemiesNear(24).isEmpty(); // no health to spare and no way to get it back: cover first
        if ((shooters.isEmpty() && !swarmed && phantoms.isEmpty() && !desperate && !desperateNow) || g.isInWater()) return noCover(g.isInWater() ? "in the water" : "nothing calls for it (no shooter on him, " + crowd + " hostile(s) within 14, no phantoms)");
        boolean diamondChest = g.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).is(net.minecraft.world.item.Items.DIAMOND_CHESTPLATE) || g.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).is(net.minecraft.world.item.Items.NETHERITE_CHESTPLATE); // (worn, not "diamond stage": that counts a deep mine too, and the dig-in stage died fleeing three husks)
        boolean shieldKit = prepared() && g.getItemBySlot(EquipmentSlot.OFFHAND).is(net.minecraft.world.item.Items.SHIELD); // (iron, a sword, food and a shield: a lone skeleton is blocked and killed, not hidden from; World 15 04:13, kitted, he dug in from one and sat out the night: the pearl hunt's window)
        if ((diamondChest || shieldKit) && armed() && g.getHealth() >= 10 && phantoms.isEmpty() && !swarmed && !desperate && !desperateNow) return noCover((diamondChest ? "a diamond chestplate" : "iron, a shield") + " and a sword: no hole, he fights or keeps on home"); // diamond gear: no hole for a skeleton or a crowd, he fights or keeps on home (the user: hiding is for the early game; with a diamond chestplate he runs to his bed or mine; he sat in a hole 2 minutes on the way home)
        if (armed() && !swarmed && !desperate && !desperateNow && phantoms.isEmpty() && shooters.size() == 1 && shooters.get(0).distanceTo(g) <= 4.5 && Math.abs(shooters.get(0).getY() - g.getY()) <= 2 && g.getHealth() >= 8) return noCover("a lone " + shooters.get(0).getType().getDescription().getString() + " " + (int) shooters.get(0).distanceTo(g) + " off: closer than any wall takes to build, he goes for it (self-defence, food or not)"); // (01:54 11-04: a skeleton 3 off shot him 20 -> 0 while he built a 13-block wall; an expert kills it in 2-3 swings)
        if (!level.getEntitiesOfClass(net.minecraft.world.entity.monster.Creeper.class, g.getBoundingBox().inflate(8), c -> c.isAlive()).isEmpty()) return noCover("a creeper within 8: distance is the only cover from one"); // a creeper that close just walks up to the hole and blows it in: distance is the only cover from one
        if (g.tasks.peek() instanceof Task.Pillar pu && pu.steps() > 0 || g.currentTask instanceof Task.Pillar pc && pc.steps() > 0) return noCover("his tower is going up: that's his cover, finished first (1 up, read as \"already up his tower\", it was stopped in a hoglin's reach, stage 826)");
        int openSides = 0; for (Direction d : Direction.Plane.HORIZONTAL) if (level.getBlockState(feet.below().relative(d)).getCollisionShape(level, feet.below().relative(d)).isEmpty()) openSides++; // (his own blocks under him at ground level are a hole filled back in, not a tower: live 19:05 11-04 he held on flat ground on that and a creeper walked up)
        if ((g.pillarBlocks.contains(feet.below()) || g.pillarBlocks.contains(feet.below(2))) && openSides >= 3) { // already up his own tower (its wait got dropped by a replan): stay up, no second tower on top of it
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
                    coversMade++; tunnelOut().remove(feet); digInAt = feet.immutable(); digInTick = g.tickCount; digInTasks = hut;
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
            coversMade++; tunnelOut().remove(feet); digInAt = feet.immutable(); digInTick = g.tickCount; digInTasks = stay;
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
            for (BlockPos w : BlockPos.betweenClosed(q.offset(-1, -1, -1), q.offset(1, 1, 1))) if (!level.getFluidState(w).isEmpty()) { holeOk = false; break; } // (water by the column, diagonals too, as doBreak's open-water guard reads it: it refuses those blocks, and at the shore (live 08:17 11-09, review 2) the hole went down refused cell by cell and got a lid over a pit; stage 1305) // (water beside the column floods the hole: refused further down with no tower tried, at a lake edge with 50 cobble on him)
        }
        if (level.getBlockState(feet.below(4)).getCollisionShape(level, feet.below(4)).isEmpty()) holeOk = false; // a cave under: not a hiding hole
        // Mobs right on him: digging in front of them only gets him hit. Two down is out of their reach, so he runs till
        // he's got the gap that two blocks take him to dig (or he can't shake them), then digs.
        double melee = hostilesNear(14).stream().filter(m -> !shoots(m)).mapToDouble(m -> m.distanceTo(g)).min().orElse(99); // (a wither skeleton is melee: left out with the bowmen it read "nearest melee 99" 13 off, live 23:13 11-07)
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
                double buildTicks = towerH * 10 + (enclosed ? 15 * 5 : 0); // a jump and a place a block up; ~5 ticks a hut block with its aim
                double arrowsBuilding = shooters.stream() /* (desperate too: at 2 health the first arrow is the end, live 05:41 World 17 shot off the tower going up) */.filter(m -> m.hasLineOfSight(g)).mapToDouble(m -> { double d = m.distanceTo(g); return d <= 8 ? 1.0 : d <= 12 ? 0.5 : 0.34; }).sum() * (buildTicks + 20) / 40.0; // (as the hole's: a shot every ~2 s, most miss at range)
                if (arrowsBuilding * 3 >= g.getHealth() - ((desperate || desperateNow) ? 0 : 4)) return noCover(String.format("%d shooter(s) with a line on him: ~%.0f arrows while a %d-high tower%s goes up, at health %d: out of their sights before any building", shooters.size(), arrowsBuilding, towerH, enclosed ? " and hut" : "", (int) g.getHealth())); // (live 04:55 11-02: building a tower and hut under 2-3 skeletons 3-8 off, 20 -> 0 in 13 s, no armor)
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
                else if (enclosed || (shooters.isEmpty() && phantoms.isEmpty())) { // (under a shooter a tower without its hut is a perch: 13-16 blocks passed the roofless check but not the hut's 14 + tower, live 18:13 11-04)
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
                coversMade++; tunnelOut().remove(feet); digInAt = feet.immutable(); digInTick = g.tickCount; digInTasks = up; digInRise = towerH;
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
            double boxNeed = 2.5 + (7 * 5 + 10) / 20.0 * 2.5; // the cover proximity rule down here too: ~7 blocks at ~5 ticks each, at a mob's ~2.5 blocks a second (live 23:13 11-07: boxed in with a wither skeleton 3 off, 13 -> 0 through the open sides)
            if (melee < boxNeed && !desperateNow) return noCoverGap(String.format("underground, a melee mob %.1f off: the box needs %.1f+ to go up (the cover proximity rule): no box", melee, boxNeed));
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
            coversMade++; tunnelOut().remove(feet); digInAt = feet.immutable(); digInTick = g.tickCount; digInTasks = box;
            g.getNavigation().stop(); g.getMoveControl().setWantedPosition(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, 0.6); // into the middle of the cell first, like a player squaring up in it: walking in, his body was over the corner cell and the step-out put him in it
            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] {} on him underground: walling himself in at {} ({} blocks, corners first; {}) nearest melee he knows {}, any melee mob {} off, desperate {}/{}, called from {}) (WorkGoal.digInFromArrows)", shooters.isEmpty() ? "a crowd" : "a skeleton", feet.toShortString(), n, shooters.isEmpty() ? crowd + " within 14" : shooters.get(0).getType().getDescription().getString() + " " + (int) shooters.get(0).distanceTo(g) + " off", melee >= 99 ? "none" : String.format("%.1f", melee), String.format("%.1f", level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class, g.getBoundingBox().inflate(14), m -> m.isAlive() && m instanceof net.minecraft.world.entity.monster.Enemy && !shoots(m)).stream().mapToDouble(m -> m.distanceTo(g)).min().orElse(99)), desperate, desperateNow, StackWalker.getInstance().walk(fs -> fs.skip(1).findFirst().map(f -> f.getMethodName() + ":" + f.getLineNumber()).orElse("?")));
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
        if (lidToo.getItem() instanceof net.minecraft.world.item.BlockItem lb && lb.getBlock() instanceof net.minecraft.world.level.block.FallingBlock) lidToo = ItemStack.EMPTY; // sand or gravel over an open hole falls in on him (live 12:37 11-04: suffocated under his own sand lid)
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
                ok &= !(level.getBlockState(roof).getBlock() instanceof net.minecraft.world.level.block.FallingBlock) && !(level.getBlockState(b).getBlock() instanceof net.minecraft.world.level.block.FallingBlock); // a sand roof comes down as the tuck is dug, and the sky with it (live 12:36 11-04: tucked under sand, shot 18 -> 2 from the rim)
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
        coversMade++; tunnelOut().remove(feet); digInAt = feet.immutable(); digInTick = g.tickCount; digInTasks = steps;
        g.pillarBlocks.addFirst(feet.below().immutable()); // the lid: on his list too
        com.claudemod.ClaudeMod.LOGGER.info("[Claude life] {} in the open: digging in at {} to wait it out (nearest melee {}, the hole needs {}+{})", shooters.isEmpty() ? "mobs on him" : "skeleton shooting at him", feet.toShortString(), melee >= 99 ? "none" : String.format("%.1f", melee), String.format("%.1f", holeNeed), melee < holeNeed ? "; boxed in after " + ticks / 20 + " s of running" : "");
        return true;
    }


    // ---- endermen from a booth (the player's trick): they're 3 tall, he's 2. A 2-high space they can't stand in, roofed, walled round his head but one side; the opening leaves them 2 off (out of their reach, in his) ----
    // ponytail: live 13:15, the old 2-deep 1x1 hole let them drop in (a 3-tall mob fits a 3-deep shaft) and he never landed a hit
    private BlockPos emHole; private int emSaid = -100;
    private boolean inEmHole() { // (no cell touching him, at his feet or head, with 3 free above it: nowhere an enderman can stand and reach him)
        ServerLevel level = (ServerLevel) g.level(); BlockPos f = g.blockPosition();
        if (!solidAt(level, f.above(2))) return false;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 1; dy++) { if (dx == 0 && dz == 0) continue; BlockPos q = f.offset(dx, dy, dz);
            if (solidAt(level, q.below()) && !solidAt(level, q) && !solidAt(level, q.above()) && !solidAt(level, q.above(2))) return false; }
        return true;
    }
    private boolean emHitSpot(ServerLevel level, BlockPos c) { // ponytail: live 16:44 -53,45,-893: a nook with no cell 2 off an enderman can stand in; it stood 4 off, out of reach, and he waited in it 30 s+
        for (int ex = -2; ex <= 2; ex++) for (int ez = -2; ez <= 2; ez++) { if (Math.max(Math.abs(ex), Math.abs(ez)) != 2) continue;
            BlockPos mid = c.offset(Integer.signum(ex), 1, Integer.signum(ez)); if (solidAt(level, mid)) continue; // (his swing goes out at head height)
            for (int ey = -1; ey <= 1; ey++) { BlockPos q = c.offset(ex, ey, ez); if (solidAt(level, q.below()) && !solidAt(level, q) && !solidAt(level, q.above()) && !solidAt(level, q.above(2))) return true; } }
        return false;
    }
    private Boolean endermanHole(Task.Attack t, net.minecraft.world.entity.monster.EnderMan em) {
        ServerLevel level = (ServerLevel) g.level(); BlockPos f = g.blockPosition();
        double d = em.distanceTo(g);
        if (inCappedHole()) return tallCover(t, em, true); // (his own capped hole: the fight from it)
        int angryAt = level.getEntitiesOfClass(net.minecraft.world.entity.monster.EnderMan.class, g.getBoundingBox().inflate(24), x -> x.isAlive() && x.getTarget() == g).size();
        if (inEmHole()) {
            emHole = f.immutable(); g.getNavigation().stop(); g.setSprinting(false); equipWeapon();
            g.getLookControl().setLookAt(em.getX(), em.getY() + 0.6, em.getZ()); // (its legs: what reaches down to him)
            if (em.getTarget() != g && d >= 8) { if (g.tickCount - emSaid > 100) { emSaid = g.tickCount; com.claudemod.ClaudeMod.LOGGER.info("[Claude fight] the enderman {} off isn't on him: out of the hole to bait it (WorkGoal.endermanHole)", (int) d); } return null; } // ponytail: live 14:46 -29,47,-882: waited in the nook for a calm one 15 off; cover only while one is engaged (the user 14:25), so out to bait it, back in when it comes
            if (em.getTarget() == g && !emHitSpot(level, f)) { if (g.tickCount - emSaid > 100) { emSaid = g.tickCount; com.claudemod.ClaudeMod.LOGGER.info("[Claude fight] my hole at {} has nowhere an enderman can stand in my reach: out to fight it (WorkGoal.endermanHole)", f.toShortString()); } return null; }
            if (em.getTarget() == g && ticks > 200 && em.getY() < g.getY() - 3 && g.hasLineOfSight(em)) { if (g.tickCount - emSaid > 100) { emSaid = g.tickCount; com.claudemod.ClaudeMod.LOGGER.info("[Claude fight] the enderman on me is {} below in sight for 10 s+ and can't climb to me: out of the hole to go down to it (WorkGoal.endermanHole)", (int) (g.getY() - em.getY())); } return null; } // ponytail: live 19:33 -32,62,-910, one 4 below in a pit, in sight, angry: the wait (and FIGHT NO EFFECT, re-picked) looped 5 min; the fight's drop-in takes it
            if (em.getTarget() == g && ticks > 200 && !g.hasLineOfSight(em)) { // (live 19:48 -53,45,-893: one 2 up, 6 off, out of sight 30 s, never came: any height, not only 3+)
            com.claudemod.ClaudeMod.LOGGER.info("[Claude fight] the enderman on me sits {} below/above out of sight at {} for 10 s+, no way to me: leaving it, on with the hunt (WorkGoal.endermanHole)", (int) (em.getY() - g.getY()), em.blockPosition().toShortString()); return true; } // ponytail: live 19:23 -29,47,-882: one 7 down in a cave under him, angry, 60 s+ of waiting; a player leaves it (it teleports to him if it can)
            if (d > 24) { if (g.tickCount - emSaid > 100) { emSaid = g.tickCount; com.claudemod.ClaudeMod.LOGGER.info("[Claude fight] the enderman went off {} away: out of the hole (WorkGoal.endermanHole)", (int) d); } return true; }
            if (g.getHealth() < (angryAt >= 2 ? 14 : 8)) { var food = com.claudemod.director.ClaudeLife.foodToEat(g, false); /* two on him: heal early, in cover (the user 20:13 "2 at once is okay if he can manage it, not if he keeps dying") */ if (food != null) { g.tasks.addFirst(new Task.Attack(t.target(), Math.max(200, t.maxTicks() - ticks))); g.tasks.addFirst(new Task.Eat(food)); return true; } }
            if (canHitNow(em)) { g.getLookControl().setLookAt(em.getX(), em.getY() + 0.6, em.getZ()); g.attack(em); g.swing(InteractionHand.MAIN_HAND); }
            else if (em.getTarget() != g && d < 16) g.getLookControl().setLookAt(em.getX(), em.getEyeY(), em.getZ()); // (not angry yet: a look at its eyes brings it over, the player's way)
            if (g.tickCount - emSaid > 200) { emSaid = g.tickCount; com.claudemod.ClaudeMod.LOGGER.info("[Claude fight] in my hole at {}: the enderman {} off (health {}) (WorkGoal.endermanHole)", f.toShortString(), (int) d, (int) g.getHealth()); }
            return false;
        }
        if (d < 4 || !g.onGround()) return null; // (too close to dig in: the ordinary fight)
        if (em.getTarget() != g) return null; // ponytail: the user 14:25 "only do the covering trick when he has an enderman engaged on him. bait them, then go to the cover" (the bait: doAttack's look and swing, or a hit in reach); live 13:12, dug in for a calm one 27 off that wandered away: close in first, dig in where a look at its eyes brings it
        if (emHole != null && g.tasks.stream().anyMatch(x -> x instanceof Task.Place pl && pl.pos().closerThan(emHole, 3) || x instanceof Task.Break br && br.pos().closerThan(emHole, 3))) return null; // ponytail: live 13:17, a fresh Attack mid-build started booth after booth (35 queued, hit to 8 hp): one booth at a time, fight it as it comes meanwhile
        for (int r = 1; r <= 8; r++) for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) { if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue; // ponytail: the user "use the trees rather than building": a 2-high nook under a fungus cap / overhang first, nearest ring out
            for (int dy = -2; dy <= 2; dy++) { BlockPos c = f.offset(dx, dy, dz);
                if (!solidAt(level, c.below()) || solidAt(level, c) || solidAt(level, c.above()) || !solidAt(level, c.above(2)) || !level.getFluidState(c).isEmpty()) continue;
                boolean open = false; for (int ex = -1; ex <= 1 && !open; ex++) for (int ez = -1; ez <= 1 && !open; ez++) for (int ey = -1; ey <= 1 && !open; ey++) { if (ex == 0 && ez == 0) continue; BlockPos q = c.offset(ex, ey, ez); if (solidAt(level, q.below()) && !solidAt(level, q) && !solidAt(level, q.above()) && !solidAt(level, q.above(2))) open = true; }
                if (open || !emHitSpot(level, c)) continue;
                { var ep = em.getNavigation().createPath(c, 2); if (ep == null || !ep.canReach()) continue; } // ponytail: the user 20:2x "only works if they can path up to you": its own 3-high walk to a cell 2 off the nook, else it never comes (it teleports about, or waits out of reach)
                emHole = c.immutable(); g.tasks.addFirst(new Task.Attack(t.target(), Math.max(400, t.maxTicks() - ticks))); g.tasks.addFirst(new Task.GoTo(c.immutable(), 0.4));
                com.claudemod.ClaudeMod.LOGGER.info("[Claude fight] an enderman {} off: a natural 2-high nook at {} ({} under it), into it instead of building (WorkGoal.endermanHole)", (int) d, c.toShortString(), name(level.getBlockState(c.above(2))));
                return true; } }
        { Boolean dug = tallCover(t, em, angryAt >= 2 || g.getHealth() <= 12); if (dug != null) return dug; } // ponytail: the user 20:13 "2 endermen at once is okay if he can manage it, but not if he keeps dying"; live 20:12 -60,38,-880, "no natural 2-high cover within 8" twice, 20 -> 10 in a second: a tunnel dug into the rock (no building), the capped hole (4 blocks) only with two on him or hurt
        if (g.tickCount - emSaid > 200) { emSaid = g.tickCount; com.claudemod.ClaudeMod.LOGGER.info("[Claude fight] an enderman {} off: no natural 2-high cover within 8 and none to dig, fighting it in the open (WorkGoal.endermanHole)", (int) d); }
        return null; // ponytail: the user 13:41 "natural cover ... if not, he keeps fighting": no booth building
    }
    private int unseenSince = -1;
    private final java.util.Map<java.util.UUID, Integer> behindWall = new java.util.HashMap<>(); // (mobs let be behind a wall, when)
    private boolean doAttack(Task.Attack t) {
        ServerLevel level = (ServerLevel) g.level();
        if (ticks == 1) unseenSince = -1; // (a fight left with the clock running ended the next one on its first tick)
        var e = level.getEntity(t.target());
        if (!(e instanceof net.minecraft.world.entity.LivingEntity mob) || !mob.isAlive()
                || ticks > t.maxTicks() || g.distanceTo(mob) > 64) {
            if (ticks <= 2) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] attack on {} over at once: {}", e == null ? "?" : e.getType().getDescription().getString(), e == null ? "gone" : !(e instanceof net.minecraft.world.entity.LivingEntity l2) ? "not a mob" : !l2.isAlive() ? "dead" : ticks > t.maxTicks() ? "ticks " + ticks + " > " + t.maxTicks() : "too far");
            g.setSprinting(false);
            // it died: grab what it dropped (beef, leather...) before moving on, like a player would
            boolean died = e == null || (e instanceof net.minecraft.world.entity.LivingEntity l && !l.isAlive());
            if (died && ticks > 1 && inEmHole()) { g.tasks.addFirst(new Task.Pickup(40)); com.claudemod.ClaudeMod.LOGGER.info("[Claude fight] the enderman's down: its pearl (the booth stays for the next) (WorkGoal.doAttack)"); }
            if (died && ticks > 1 && e != null) com.claudemod.ClaudeMod.LOGGER.info("[Claude fight] killed the {} at {} (WorkGoal.doAttack)", e.getType().getDescription().getString(), e.blockPosition().toShortString()); // (every kill on his log: his chat leads with it, MindLog.recent)
            if (died && ticks > 1 && e != null) { // what it left and where it falls: 0 rods off 5 blaze kills over a fortress, 05:59, the sweep saw no drops at all
                BlockPos fl = e.blockPosition(); int dn = 0; while (dn < 64 && level.getBlockState(fl).isAir()) { fl = fl.below(); dn++; }
                var near = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, e.getBoundingBox().inflate(8), x -> true);
                com.claudemod.ClaudeMod.LOGGER.info("[Claude fight] its drops: {} item(s) round it {}; under it {} down: {} (WorkGoal.doAttack)", near.size(), near.stream().map(x -> itemName(x.getItem()) + "@" + x.blockPosition().toShortString()).toList(), dn, name(level.getBlockState(fl)));
            }
            if (ticks > 1) { // his own arrows stuck round the fight: walked over and taken back, like a player does (01:47 26 shot, none picked up); the drops first
                var mine = level.getEntitiesOfClass(net.minecraft.world.entity.projectile.AbstractArrow.class, g.getBoundingBox().inflate(16, 6, 16), a -> a.isAlive() && a.getOwner() == g && a.pickup == net.minecraft.world.entity.projectile.AbstractArrow.Pickup.ALLOWED && a.getDeltaMovement().lengthSqr() < 1e-4 && level.getFluidState(a.blockPosition()).isEmpty());
                mine.sort(java.util.Comparator.comparingDouble(a -> -a.distanceToSqr(g))); // (addFirst: the nearest ends up first)
                for (var a : mine) g.tasks.addFirst(new Task.GoTo(a.blockPosition(), 1.0));
                if (!mine.isEmpty()) com.claudemod.ClaudeMod.LOGGER.info("[Claude fight] {} of his arrows stuck round the fight: picking them back up (WorkGoal.doAttack)", mine.size());
            }
            net.minecraft.world.entity.Mob still = died ? level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class, g.getBoundingBox().inflate(w("fight.drops_wait_blocks", 16)), // (16, not 8: the next wither 10 off walked in while he stepped out of his gap for bones, stage 774)
                m0 -> m0 != e && m0.isAlive() && m0.getTarget() == g && m0 instanceof net.minecraft.world.entity.monster.Enemy && !shoots(m0) && !(m0 instanceof net.minecraft.world.entity.monster.Blaze) && !(m0 instanceof net.minecraft.world.entity.monster.Ghast)).stream().findFirst().orElse(null) : null;
            if (still != null) { com.claudemod.ClaudeMod.LOGGER.info("[Claude fight] the drops wait: a {} {} off is still on him (WorkGoal.doAttack)", still.getType().getDescription().getString(), (int) still.distanceTo(g)); g.tasks.addFirst(new Task.Attack(still.getUUID(), Math.max(400, t.maxTicks()))); return true; } // ponytail: stage 553, the pickup after the first wither skeleton dug through his hole's wall for a drop and the second came in by it
            if (died && ticks > 1 && !(g.tasks.peek() instanceof Task.Pickup)) {
                // the next kill near: that first, the drops swept after the last (they last 5 minutes; the user 11-06 21:20 "he should kill multiple things at once in range and pick everything up after")
                double near = w("hunt.batch_blocks", 16);
                if (g.tasks.peek() instanceof Task.Attack na && level.getEntity(na.target()) instanceof net.minecraft.world.entity.LivingEntity nl && nl.isAlive() && nl.distanceTo(g) <= near) {
                    java.util.List<Task> all = new java.util.ArrayList<>(g.tasks); int end = 0;
                    while (end < all.size() && all.get(end) instanceof Task.Attack) end++;
                    if (end >= all.size() || !(all.get(end) instanceof Task.Pickup)) { all.add(end, new Task.Pickup(80 + 40 * end)); g.tasks.clear(); g.tasks.addAll(all); }
                    com.claudemod.ClaudeMod.LOGGER.info("[Claude fight] the {}'s drops wait: the next one ({}) is {} off, {} kill(s) queued, then one sweep (WorkGoal.doAttack)", e.getType().getDescription().getString(), nl.getType().getDescription().getString(), (int) nl.distanceTo(g), end);
                } else g.tasks.addFirst(new Task.Pickup(80));
            }
            return true;
        }
        { Integer bw = behindWall.get(mob.getUUID()); if (bw != null && g.tickCount - bw < 20 * 30 && !g.hasLineOfSight(mob)) return true; } // (one he let be behind a wall: whoever queues the fight again, it ends here while he still can't see it)
        if (ticks % 200 == 100) com.claudemod.ClaudeMod.LOGGER.info("[Claude fight] attack tick {}: the {} at {} ({} up, {} off), in sight {}, on him {}, he at {} on ground {} nav {}, can hit {}, walkable {}, refused lately {}, cover {}, footing {}, pitch {}, its way to him {}, it moving {} , charge {} its hurt frames {} reach {} crit wait {} (WorkGoal.doAttack)", ticks, mob.getType().getDescription().getString(), mob.blockPosition().toShortString(), (int) (mob.getY() - g.getY()), (int) g.distanceTo(mob), g.hasLineOfSight(mob), mob instanceof net.minecraft.world.entity.Mob mm && mm.getTarget() == g, g.blockPosition().toShortString(), g.onGround(), !g.getNavigation().isDone(), canHit(mob), g.getNavigation().createPath(mob, 1) instanceof net.minecraft.world.level.pathfinder.Path lp && lp.canReach(), com.claudemod.entity.body.Controls.refusedLately(g, 10), cfMode, footingSaid, (int) g.getXRot(), mob instanceof net.minecraft.world.entity.Mob m2 && m2.getNavigation().createPath(g, 1) instanceof net.minecraft.world.level.pathfinder.Path ip && ip.canReach(), mob instanceof net.minecraft.world.entity.Mob m3 && !m3.getNavigation().isDone(), String.format("%.2f", g.getAttackStrengthScale(0.5f)), mob.invulnerableTime, String.format("%.2f", Math.sqrt(mob.getBoundingBox().distanceToSqr(g.getEyePosition()))), g.tickCount - critJump().getOrDefault(g.getUUID(), -100)); // ponytail: a silent Attack stood 30 s at a time under a heard enderman (live 17:09): what it is doing
        if (g.hasLineOfSight(mob) || Math.abs(mob.getY() - g.getY()) <= 3 || mob instanceof net.minecraft.world.entity.Mob m0 && m0.getTarget() == g) unseenSince = -1; else if (unseenSince <= 0) unseenSince = g.tickCount; // ponytail: the fight skill's own rule (unseen and 3+ up/down: not a fight he can get at) held while it runs; live 17:09 stood 90 s under one 9 below, 30 s a pass
        if (unseenSince > 0 && g.tickCount - unseenSince > 60) { unseenSince = -1; com.claudemod.ClaudeMod.LOGGER.info("[Claude fight] the {} has been out of sight {} up/down for 3 s and isn't after him: leaving it (WorkGoal.doAttack)", mob.getType().getDescription().getString(), (int) (mob.getY() - g.getY())); return true; }
        if (ticks == 1) dropCloseSteps(); // (a closing-in step cut short by a new fight: stale, it would walk him to where the shooter was)
        if (ticks == 1 && mob instanceof net.minecraft.world.entity.animal.WaterAnimal && !level.getFluidState(mob.blockPosition().above(6)).isEmpty() && g.distanceTo(mob) > 3) { // (6 deep now, as the hunt picks them) // a fish deep down: no reaching it from the bank (100 s at half a heart swinging at cod he couldn't get to); the land animals come next
            failed(com.claudemod.entity.life.Attempts.Kind.WALK, mob.blockPosition(), com.claudemod.entity.life.Attempts.Reason.OUT_OF_REACH, "a fish 6+ deep");
            return true;
        }
        if (mob instanceof net.minecraft.world.entity.animal.IronGolem && !outOfItsReach(mob) && (g.pillarBlocks.contains(g.blockPosition().below()) || g.pillarBlocks.contains(g.blockPosition().below(2))) && g.inventory.count(com.claudemod.entity.life.ClaudeInventory::isBuildingBlock) > 0 && mob.getBoundingBox().maxY - g.getY() < com.claudemod.entity.life.Weights.w("golem.tower_top_up_max", 3)) { /* on his golem tower and still in its reach (it stood a block higher than his tower's foot): a block more before any swing */ com.claudemod.ClaudeMod.LOGGER.info("[Claude fight] on his tower at y {} with the golem's top at {}: still in its reach, a block more before the first swing (WorkGoal.doAttack)", String.format("%.2f", g.getY()), String.format("%.2f", mob.getBoundingBox().maxY)); com.claudemod.entity.life.Task.Pillar down = null; for (com.claudemod.entity.life.Task k : g.tasks) if (k instanceof com.claudemod.entity.life.Task.Pillar pd && pd.steps() < 0) { down = pd; break; } if (down != null) { java.util.List<com.claudemod.entity.life.Task> l0 = new java.util.ArrayList<>(g.tasks); l0.set(l0.indexOf(down), new com.claudemod.entity.life.Task.Pillar(down.steps() - 1)); g.tasks.clear(); g.tasks.addAll(l0); } g.tasks.addFirst(t); g.tasks.addFirst(new com.claudemod.entity.life.Task.Pillar(1)); return true; } // (live 21:55 World 17: feet 72.50 under its top 72.70, one hit 20 -> 3, then dead; stage 687)
        if (mob instanceof net.minecraft.world.entity.animal.IronGolem ig && ig.getTarget() != g && !outOfItsReach(mob) && t.maxTicks() >= 20 * 60) { // the golem job on the ground (the tower didn't go up: it walked into him): no fight down here with a golem that isn't after him, the tower again
            if (!mob.getUUID().equals(golemRetryFor)) { golemRetryFor = mob.getUUID(); golemRetries = 0; }
            if (golemRetries++ < 6) { g.skillQueue.addFirst(new com.claudemod.entity.life.Skills.Step("village_iron", "", 1)); com.claudemod.ClaudeMod.LOGGER.info("[Claude life] on the ground by the golem (the tower didn't go up): the tower again beside it"); return true; }
        }
        { Boolean cf = coverFight(t, mob); if (cf != null) { if (ticks % 100 == 1) com.claudemod.ClaudeMod.LOGGER.info("[Claude fight] doAttack held by coverFight ({}) on the {} {} off (WorkGoal.doAttack)", cf, mob.getType().getDescription().getString(), (int) g.distanceTo(mob)); return cf; } } /* ponytail: live 07:40 World 16 stood 75 s nav false on a reachable skeleton: which return holds him */
        if (g.level().dimension() == net.minecraft.world.level.Level.NETHER) { BlockPos un = BlockPos.containing(g.getX(), g.getY() - 0.2, g.getZ()); boolean hw = com.claudemod.entity.life.PortalMap.highwayBlock(g, com.claudemod.entity.life.PortalMap.NETHER, un, g.level().getBlockState(un)) || com.claudemod.entity.life.PortalMap.highwayBlock(g, com.claudemod.entity.life.PortalMap.NETHER, un.below(), g.level().getBlockState(un.below()));
            if (fightOnHw && !hw) com.claudemod.ClaudeMod.LOGGER.info("[Claude fight] off his highway mid-fight at {} ({} {} {}): motion {}, hit {} ticks ago, path to {}, sprinting {} (WorkGoal.doAttack)", g.blockPosition().toShortString(), String.format("%.2f", g.getX()), String.format("%.2f", g.getY()), String.format("%.2f", g.getZ()), g.getDeltaMovement(), g.hurtTime > 0 ? 10 - g.hurtTime : -1, g.getNavigation().getPath() == null || g.getNavigation().isDone() ? "-" : g.getNavigation().getPath().getTarget().toShortString(), g.isSprinting());
            fightOnHw = hw; } // (silent before: off his railless highway in a fight with no line of why, stage 932)
        { Boolean ft = fightFooting(mob); if (ft != null) { if (ticks % 100 == 1) com.claudemod.ClaudeMod.LOGGER.info("[Claude fight] doAttack held by fightFooting ({}) on the {} {} off (WorkGoal.doAttack)", ft, mob.getType().getDescription().getString(), (int) g.distanceTo(mob)); return ft; } } // (a lip behind him on the side a hit throws him: firm footing first; two Nether deaths in 4 min, World 15 13:48 and 13:51, knocked off mid-fight)
        if (mob instanceof net.minecraft.world.entity.monster.EnderMan em) { Boolean eh = endermanHole(t, em); if (eh != null) return eh; }
        if (mob instanceof net.minecraft.world.entity.monster.EnderMan em && em.getTarget() == g && mob.getY() - g.getY() > -4 && Math.hypot(mob.getX() - g.getX(), mob.getZ() - g.getZ()) < 16 && g.onGround() && (g.distanceTo(mob) > 4 || !canHit(mob)) && em.onGround() && !(em.getNavigation().createPath(g, 1) instanceof net.minecraft.world.level.pathfinder.Path ep && ep.canReach())) { // (on the ground: vanilla plans no path at all for a mob in the air, so one just spawned, teleported or falling read as "no way", and he backed off from one 8 off on flat grass for 70 s, 527) // ponytail: the ENDERMAN's way to him decides (live 20:42, 7 straight up: he could walk up, it can't drop 7, both stood) // an angry enderman up a ledge he can't walk to teleports to a target 16+ off that isn't staring (vanilla EndermanLookForPlayerGoal): back off and it comes (live 20:21, 6 up 6 off, he stood 3 fight clocks under it; 20:33, 1 up 13 off across a gap, the same)
            double hz = Math.hypot(mob.getX() - g.getX(), mob.getZ() - g.getZ()); Vec3 aw = g.position().subtract(mob.position()).multiply(1, 0, 1); if (aw.lengthSqr() < 1e-4) aw = new Vec3(1, 0, 0); aw = aw.normalize().scale(18 - hz);
            if (g.getNavigation().isDone()) { g.setSprinting(true); g.getNavigation().moveTo(g.getX() + aw.x, g.getY(), g.getZ() + aw.z, 1.3); com.claudemod.ClaudeMod.LOGGER.info("[Claude fight] the enderman is {} up, {} off, with no way on foot either way: backing off past 16 so it teleports to him (WorkGoal.doAttack)", (int) (mob.getY() - g.getY()), (int) hz); }
            return false;
        }
        if (mob instanceof net.minecraft.world.entity.monster.EnderMan && ((net.minecraft.world.entity.monster.EnderMan) mob).getTarget() == g && mob.getY() - g.getY() <= -4 && mob.getY() - g.getY() >= -6 && Math.hypot(mob.getX() - g.getX(), mob.getZ() - g.getZ()) < 4 && g.getHealth() > 10 && !g.level().getFluidState(mob.blockPosition()).is(net.minecraft.tags.FluidTags.LAVA)) { equipWeapon(); g.setSprinting(false); g.getMoveControl().setWantedPosition(mob.getX(), mob.getY(), mob.getZ(), 1.0); if (g.tickCount % 40 == 0) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] the Enderman is on me {} below in a pit it can't climb out of, {} across: dropping in to it ({} fall damage) (WorkGoal.doAttack)", (int) (g.getY() - mob.getY()), (int) Math.hypot(mob.getX() - g.getX(), mob.getZ() - g.getZ()), Math.max(0, (int) (g.getY() - mob.getY()) - 3)); return false; } // ponytail: live 19:29, staring brought an angry one nowhere (it can't climb 4) and he swung at air 15 s, over and over; a player drops in. Straight line, so only short (horiz<4); add a lava check on the way if it ever walks him over a lip into lava
        if (mob instanceof net.minecraft.world.entity.monster.EnderMan em && em.getTarget() != g && ticks % 200 < 100 && g.distanceTo(em) < 32 && g.hasLineOfSight(em)
                && !(em.getBoundingBox().distanceToSqr(g.getEyePosition()) <= 9.0)) { // ponytail: the user 14:20 "swing at them from distance to aggro": a calm one out of reach is called over, not chased (it teleports off a chase); 5 s of it, then 5 s closing in, by the task clock
            g.getNavigation().stop(); g.setSprinting(false); equipWeapon(); g.getLookControl().setLookAt(em.getX(), em.getEyeY(), em.getZ()); // (vanilla: the look at its eyes is what angers it; the swing is the player's habit)
            if (ticks % 10 == 0) g.swing(InteractionHand.MAIN_HAND);
            if (ticks % 100 == 0) { Vec3 vv = g.getViewVector(1.0F).normalize(), tt = new Vec3(em.getX() - g.getX(), em.getEyeY() - g.getEyeY(), em.getZ() - g.getZ()); double dd = tt.length(), dot = vv.dot(tt.normalize()); com.claudemod.ClaudeMod.LOGGER.info("[Claude fight] a calm enderman {} off: eyes on it and a swing to call it over; vanilla stare check: dot {} vs {} needed, his line of sight {}, yRot {} yHeadRot {} xRot {} (WorkGoal.doAttack)", (int) g.distanceTo(em), String.format("%.5f", dot), String.format("%.5f", 1.0 - 0.025 / dd), g.hasLineOfSight(em), (int) g.getYRot(), (int) g.getYHeadRot(), (int) g.getXRot()); } // (why a stare never angers it: 25 min on one calm enderman, World 17 19:26)
            return false;
        } // (the pearl stage: from a 2-deep hole it can't reach into) // a blaze or a wither skeleton: from cover, like an expert (FortressFight)
        // losing the fight: break off and get away, like a player would (fighting on at 3 hearts is how you die)
        if ((mob instanceof net.minecraft.world.entity.monster.Monster || mob instanceof net.minecraft.world.entity.animal.IronGolem || mob instanceof net.minecraft.world.entity.monster.hoglin.Hoglin) && shouldFlee(mob)) { // (the hoglin: never counted, so a losing fight with one at 4 HP went on to his death, 16:25 11-05)
            var gf = gapFoe(); if (gf != null && gf != mob) { g.tasks.addFirst(new Task.Attack(gf.getUUID(), 600)); return true; } // (a crowd with a wither skeleton in it: the fight from a gap it can't reach into, never a run past them)
            g.tasks.addFirst(new Task.Flee(160));
            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] breaking off the fight with the {}{} {} off (health {}, {} within 10; {}; he's at y {}, its top {}) (WorkGoal.doAttack)", mob instanceof net.minecraft.world.entity.AgeableMob am && am.isBaby() ? "baby " : "", mob.getType().getDescription().getString(), String.format("%.1f", mob.distanceTo(g)), (int) g.getHealth(), hostilesNear(10).size(), fightOdds, String.format("%.2f", g.getY()), String.format("%.2f", mob.getBoundingBox().maxY));
            return true;
        }
        if (shootAt(mob)) return false; // bow work: it stays out there and dies, or it closes and the sword takes over
        equipWeapon();
        if (mob instanceof net.minecraft.world.entity.monster.EnderMan em5 && em5.getTarget() == g && g.distanceTo(mob) > 3.5) g.getLookControl().setLookAt(mob.getX(), g.getEyeY(), mob.getZ()); // ponytail: eyes level, never on an angry one out of reach: a stared-at enderman stands still (vanilla EndermanFreezeWhenLookedAtGoal; live 20:42, 7 straight up, pitch -86, it never came)
        else g.getLookControl().setLookAt(mob);
        // A hissing creeper: back off until it calms down, then go back in.
        if (mob instanceof net.minecraft.world.entity.monster.Creeper creeper && creeper.getSwellDir() > 0 && canHitNow(creeper)) {
            g.getLookControl().faceNow(creeper.getBoundingBox().getCenter()); // hissing but in reach, swing ready: hit it (the knockback stops it), then back off
            com.claudemod.ClaudeMod.LOGGER.info("[Claude fight] a lit creeper {} off in reach: a hit then back off (swell {}, sprinting {}) (WorkGoal.doAttack)", String.format("%.1f", creeper.distanceTo(g)), String.format("%.2f", creeper.getSwelling(1.0F)), g.isSprinting());
            g.attack(creeper);
            g.swing(InteractionHand.MAIN_HAND);
            Vec3 back = g.position().subtract(creeper.position()).normalize().scale(4).add(g.position());
            g.getNavigation().moveTo(back.x, back.y, back.z, 1.3);
            return false;
        }
        if (mob instanceof net.minecraft.world.entity.monster.Creeper creeper && creeper.getSwellDir() > 0) {
            Vec3 away = g.position().subtract(creeper.position()).normalize().scale(6).add(g.position());
            boolean went = ticks % 5 != 0 || g.getNavigation().moveTo(away.x, away.y, away.z, 1.3);
            if (ticks % 5 == 0) com.claudemod.ClaudeMod.LOGGER.info("[Claude fight] a lit creeper {} off he can't hit now (swell {}, its feet {} to his {}): backing off toward {} ({}, nav done {}) (WorkGoal.doAttack)", String.format("%.1f", creeper.distanceTo(g)), String.format("%.2f", creeper.getSwelling(1.0F)), String.format("%.1f", creeper.getY()), String.format("%.1f", g.getY()), BlockPos.containing(away).toShortString(), went ? "a path" : "NO PATH", g.getNavigation().isDone()); // (death 7, 04:54 11-08: lit 3.5 off, blew 0.4 s later; nothing logged why he was still there)
            return false;
        }
        // dodge first: a fang line (no shield takes magic), or a shot his shield can't take (CombatBrain)
        if (CombatBrain.dodge(g, g.getItemBySlot(EquipmentSlot.OFFHAND).is(net.minecraft.world.item.Items.SHIELD) && !g.getCooldowns().isOnCooldown(net.minecraft.world.item.Items.SHIELD)) != null) return false;
        // Shield work, like a player with one in his off hand: up (facing it) when a shot or a hit is coming, down to swing
        // and to move at full speed. A raised shield walks at a fifth, so it's never just held up through a chase.
        if (incoming(mob)) shieldUp(mob); else shieldDown();
        // In reach like a player (3 blocks from his eyes to its hitbox, clear line): hit it, even up a ledge;
        // the knockback can knock it off the edge to him.
        boolean inReach = mob.getBoundingBox().distanceToSqr(g.getEyePosition()) <= 9.0 && canHit(mob);
        if (inReach) {
            chaseBest = Double.MAX_VALUE; chaseBestTick = ticks; // (in reach: the chase starts over once it runs off again)
            g.setSprinting(false);
            if (mob.getBoundingBox().distanceToSqr(g.getEyePosition()) < 2.0) g.getNavigation().stop();
            // Hit and step back, like a player: his reach (3) beats a zombie's (~2), so between swings he backs out of its bite, still facing it, and swings again as it walks back in
            boolean meleeMob = !(mob instanceof net.minecraft.world.entity.monster.Creeper) && !shoots(mob);
            { float ch = g.getAttackStrengthScale(0f); int cj0 = critJump().getOrDefault(g.getUUID(), -100); // the crit jump timed to the charge: up while it fills, falling as it's full (a jump at full charge waited ~7 ticks for its fall)
              if ((1 - ch) * g.getCurrentItemAttackStrengthDelay() <= w("fight.crit_jump_lead_ticks", 8) && ch < 0.95f && g.onGround() && !g.isInWater() && !g.isPassenger() && !(mob instanceof net.minecraft.world.entity.monster.Creeper) && !CombatBrain.sweepBeatsCrit(g, mob) && (mob instanceof net.minecraft.world.entity.monster.Monster || mob instanceof net.minecraft.world.entity.animal.IronGolem || mob instanceof net.minecraft.world.entity.monster.hoglin.Hoglin) && g.getHealth() > 8 && g.getFoodData().getFoodLevel() > 6 && g.tickCount - cj0 > 10) { critJump().put(g.getUUID(), g.tickCount); g.getJumpControl().jump(); } }
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
            if (g.getAttackStrengthScale(0.5f) >= 0.95f && (mob.invulnerableTime <= w("combat.move.strike.iframes_max", 10) || !com.claudemod.entity.life.CombatLog.hitWithin(mob, 10)) && mob.getBoundingBox().distanceToSqr(g.getEyePosition()) <= 9.0) { // (3 to the nearest point of it, like a player's reach: to its middle, a golem from his tower top read as out of reach)
                reachWaitStart = -1;
                int cj = critJump().getOrDefault(g.getUUID(), -100);
                if (g.onGround() && !g.isInWater() && !g.isPassenger() && !(mob instanceof net.minecraft.world.entity.monster.Creeper) && !CombatBrain.sweepBeatsCrit(g, mob) && (mob instanceof net.minecraft.world.entity.monster.Monster || mob instanceof net.minecraft.world.entity.animal.IronGolem || mob instanceof net.minecraft.world.entity.monster.hoglin.Hoglin) && g.getHealth() > 8 && g.getFoodData().getFoodLevel() > 6 && g.tickCount - cj > 20) { critJump().put(g.getUUID(), g.tickCount); g.getJumpControl().jump(); return false; } // the jump first: the swing on the way down is a critical hit (x1.5), the way a player fights
                if (!g.onGround() && (g.getDeltaMovement().y > 0.0 || g.fallDistance <= 0) && g.tickCount - cj <= 12) return false; // still going up, or at the top: vanilla crits only with a fall under him (fallDistance > 0)
                g.getLookControl().faceNow(mob.getBoundingBox().getCenter());
                shieldDown(); // a swing lowers the shield (it goes back up next tick if it's still needed)
                g.attack(mob);
                g.swing(InteractionHand.MAIN_HAND);
                if (!mob.isAlive()) g.noteDoing("killed a " + net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).getPath());
            }
            return false;
        }
        if (ticks % 8 != 1) return false; // re-think the chase a few times a second (it moves)
        if (mob instanceof net.minecraft.world.entity.animal.Animal || mob instanceof net.minecraft.world.entity.animal.AbstractFish) { // (a fish is no Animal: a salmon 5-7 off chased 15 s with no hit till the no-effect watchdog, live 11:18 11-09, D2D reviews 212/213) // a chase goes on while he's closing in: 5 s with no new closest approach (it keeps its distance up a slope, across water, behind a fence) and it's not to be had on foot as things stand; the next one (the hunt's choice skips it for a while)
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
        if (g.distanceTo(mob) < 5.5 && !canHit(mob)) { /* (can't hit it from here: leaves, glass or a fence between; seeing it is not reaching it) */
            BlockPos inWay = blockInTheWay((ServerLevel) g.level(), BlockPos.containing(mob.getBoundingBox().getCenter()));
            if (inWay != null) {
                BlockState w = g.level().getBlockState(inWay);
                boolean theirs = g.getTarget() instanceof net.minecraft.server.level.ServerPlayer o
                        && (com.claudemod.context.PlayerProfile.placedBy(o).contains(inWay) || com.claudemod.director.ClaudeLife.protectedBlock(g, o, inWay));
                if (!theirs && w.getDestroySpeed(g.level(), inWay) >= 0 && !w.hasBlockEntity() && !w.is(net.minecraft.tags.BlockTags.LOGS) && com.claudemod.director.Structures.intactKind(w) == null) { // (never a fortress's brick: round, or through its doorway) // (never a log: a cut through a trunk to hit a mob behind it left two birches with a log gone out of the middle; round the tree instead)
                    boolean soft = w.is(net.minecraft.tags.BlockTags.LEAVES) || w.getDestroySpeed(g.level(), inWay) <= 0.6f; // (leaves over a chicken, dirt or sand: what a player knocks away for a mob; never rock between him and it)
                    boolean shell = com.claudemod.director.ClaudeLife.bpMode(g) && g.blueprint != null && !g.blueprint.isEmpty() && g.blueprint.isShell(inWay);
                    { var ce = com.claudemod.entity.life.TempLedger.entry(g, inWay); if (ce != null && "cover".equals(ce.why) && g.level().getGameTime() - ce.at < 20 * 120 && !g.level().getEntitiesOfClass(net.minecraft.world.entity.monster.Blaze.class, g.getBoundingBox().inflate(24), net.minecraft.world.entity.Entity::isAlive).isEmpty()) { // his own cover from the blazes: never knocked out to hit one through it (live 17:37 11-07: blazeRetreat walled him in at 320,59,496, doAttack dug it out 11 s later, twice, 4 blazes on him in the open)
                        behindWall.put(mob.getUUID(), g.tickCount); com.claudemod.director.ClaudeLife.nookSay(g, "coverKept" + inWay.asLong(), "[Claude fight] the " + mob.getType().getDescription().getString() + " is behind his own cover at " + inWay.toShortString() + ": kept, the fight from it (WorkGoal.doAttack)"); return true; } }
                    if (!soft || shell || com.claudemod.entity.life.Watchdog.off(g, new Task.Break(inWay)) || g.attempts.within(com.claudemod.entity.life.Attempts.Kind.BREAK, inWay, g.tickCount, 20 * 60)) { // (World 15 16:44: digs through his own mine wall toward a mob behind it, each refused by the shell rule and asked for again every second, 235 lines)
                        g.attempts.fail(com.claudemod.entity.life.Attempts.Kind.REACH, mob.blockPosition(), g.tickCount, g.blockPosition(), com.claudemod.entity.life.Attempts.Reason.OUT_OF_REACH, "the " + mob.getType().getDescription().getString() + " behind a wall", mob.getUUID()); behindWall.put(mob.getUUID(), g.tickCount);
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
        if (com.claudemod.entity.body.Controls.refusedLately(g, 10) && g.onGround()) { // ponytail: live 14:42 -10,63,-930: the vanilla path's step 2 up refused by his body, re-taken every tick for 10 s+ under an enderman on a ledge 4 up
            g.getNavigation().stop(); g.setSprinting(false);
            if (mob instanceof net.minecraft.world.entity.monster.EnderMan em0 && em0.getTarget() != g) { equipWeapon(); g.getLookControl().setLookAt(mob.getX(), mob.getEyeY(), mob.getZ()); return false; } // (bait: the stare angers it and it teleports down to him)
            walkTo(mob.blockPosition(), 2.0); return false; } // (anything else, and an enderman already angry at him (the stare baits only a calm one; live 16:33 stared 15 s at one on him 3 up, fight_no_effect): the walk's own plan)
        var path = g.getNavigation().createPath(mob, 1);
        if (path != null && holdHighway(mob, path)) return false; // (a reaching path or one that only gets closer)
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
        boolean shoots = shoots(mob) || mob instanceof net.minecraft.world.entity.monster.Blaze || mob instanceof net.minecraft.world.entity.monster.Ghast; // (a blaze and a ghast shoot too, though not RangedAttackMobs in the game's code: a blaze was chased by a 1-wide bridge over the lava sea, World 15 13:41)
        if ((shoots && !(dy > 1.5 && horiz < 3.0)) || dy <= -4) {
            g.setSprinting(false);
            if (mob instanceof net.minecraft.world.entity.monster.EnderMan && g.hasLineOfSight(mob)) { g.getNavigation().stop(); equipWeapon(); g.getLookControl().setLookAt(mob.getX(), mob.getEyeY(), mob.getZ()); if (g.tickCount % 40 == 0) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] the Enderman is {} below with no way down on foot: staring it up to him", (int) -dy); return false; } // ponytail: live 18:40, he left an enderman 5 below and the pearl hunt picked it again each second; a player stares and it teleports to them (an angry one comes anyway); the fight's clock ends it
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
            if (mob instanceof net.minecraft.world.entity.monster.EnderMan) { // the dig down and the fight, priced against his health (World 17 20:41:00: dug down 17 to one at 11 HP with 300 fire ticks, no water, dead 16 s later)
                double fire = g.isOnFire() && !g.isInWater() && !g.hasEffect(net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE) ? g.getRemainingFireTicks() / 20.0 : 0; // (1 HP a second while it burns)
                double hits = com.claudemod.entity.life.Weights.w("enderman.hits_expected", 0.7) * com.claudemod.entity.life.Weights.w("enderman.hit_hp", 7) * com.claudemod.entity.life.Decide.armorF(g);
                // the way down as a player reads it: the line to it, the blocks on it to dig, off at bedrock or lava touching it (D2D review 243, World 17 08:41: 38 below priced 38 x 1 s with lava at the bottom; the walk ended at once, 30 short)
                if (g.attempts.skipped(com.claudemod.entity.life.Attempts.Kind.WALK, mob.blockPosition(), g.tickCount, 200)) { if (g.tickCount % 100 == 0) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] the Enderman is {} below at {}: no way down to it from here already ({}): leaving it", (int) -dy, mob.blockPosition().toShortString(), g.attempts.why(com.claudemod.entity.life.Attempts.Kind.WALK, mob.blockPosition())); return true; }
                int digs = 0; String shut = null; java.util.Set<BlockPos> seen = new java.util.HashSet<>(); Vec3 from = g.position(), to = mob.position();
                int steps = (int) Math.ceil(Math.max(Math.abs(to.y - from.y), Math.max(Math.abs(to.x - from.x), Math.abs(to.z - from.z))));
                for (int i = 1; i <= steps && shut == null; i++) { BlockPos c0 = BlockPos.containing(from.lerp(to, (double) i / steps));
                    for (BlockPos q : new BlockPos[] {c0, c0.above()}) { if (!seen.add(q)) continue; var bs = g.level().getBlockState(q);
                        if (bs.getDestroySpeed(g.level(), q) < 0) { shut = bs.getBlock().getName().getString() + " at " + q.toShortString(); break; }
                        for (Direction d : Direction.values()) if (g.level().getFluidState(q.relative(d)).is(net.minecraft.tags.FluidTags.LAVA)) { shut = "lava at " + q.relative(d).toShortString(); break; }
                        if (shut == null && g.level().getFluidState(q).is(net.minecraft.tags.FluidTags.LAVA)) shut = "lava at " + q.toShortString();
                        if (shut != null) break;
                        if (!bs.getCollisionShape(g.level(), q).isEmpty()) digs++; } }
                if (shut != null) { g.attempts.fail(com.claudemod.entity.life.Attempts.Kind.WALK, mob.blockPosition(), g.tickCount, g.blockPosition(), com.claudemod.entity.life.Attempts.Reason.NO_PATH, "the way down to an enderman " + (int) -dy + " below: " + shut, mob.getUUID()); com.claudemod.ClaudeMod.LOGGER.info("[Claude life] the Enderman is {} below, out of sight; the way down to it meets {}: leaving it", (int) -dy, shut); return true; }
                double digS = digs * com.claudemod.entity.life.Weights.w("move.dig_down_s_per_block", 1.0) + horiz / com.claudemod.entity.life.Weights.w("move.sprint_blocks_per_s", 5.6);
                var pick = com.claudemod.entity.life.Decide.choose(g, "enderman.dig_down", "it's " + (int) -dy + " below, out of sight; health " + (int) g.getHealth() + (fire > 0 ? ", " + (int) fire + " s of fire left" : "") + ", armor " + g.getArmorValue(), true, java.util.List.of(
                    com.claudemod.entity.life.Decide.Option.of("dig down to it", digS + com.claudemod.entity.life.Decide.hpS(g, fire, hits, com.claudemod.entity.life.Weights.w("enderman.hit_hp", 7) * com.claudemod.entity.life.Decide.armorF(g)), (int) digS + " s (" + digs + " blocks to dig), ~" + (int) hits + " HP of its hits" + (fire > 0 ? " on " + (int) fire + " of fire" : "")),
                    com.claudemod.entity.life.Decide.Option.of("leave it", com.claudemod.entity.life.Weights.w("enderman.next_one_s", 180) + com.claudemod.entity.life.Decide.hpS(g, fire, 0, 1) + com.claudemod.entity.life.Decide.hpS(g, g.getMaxHealth(), 0, hits, com.claudemod.entity.life.Weights.w("enderman.hit_hp", 7) * com.claudemod.entity.life.Decide.armorF(g), Integer.MAX_VALUE), "another one found, its fight at full health" + (fire > 0 ? ", the fire's " + (int) fire + " HP either way" : ""))));
                if (pick == null || !pick.name().equals("dig down to it")) return true;
                g.tasks.addFirst(new Task.Attack(t.target(), Math.max(200, t.maxTicks() - ticks))); g.tasks.addFirst(new Task.GoTo(mob.blockPosition(), 3)); com.claudemod.ClaudeMod.LOGGER.info("[Claude life] the Enderman is {} below, out of sight, no way down on foot: digging down to it at {}, then the fight again", (int) -dy, mob.blockPosition().toShortString()); return true; } // ponytail: live 21:26/21:27 he left two endermen 12 and 8 below (one had hit him) mid pearl hunt; a player stairs down to the pearl
            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] the {} is {} below with no way down on foot: leaving it", mob.getType().getDescription().getString(), (int) -dy);
            return true;
        }
        if (dy > 1.5 && dy <= 2.5 && horiz < 2.5 && g.onGround()) { // a jump puts it in reach: jump and swing
            g.getMoveControl().setWantedPosition(mob.getX(), g.getY(), mob.getZ(), 1.0);
            g.getJumpControl().jump();
            return false;
        }
        if (dy > 2.5 && horiz < 3.0 && mob instanceof net.minecraft.world.entity.monster.EnderMan) { g.getNavigation().stop(); if (((net.minecraft.world.entity.monster.EnderMan) mob).getTarget() == g) g.getLookControl().setLookAt(g.getX() + (g.getX() - mob.getX()) + 0.01, g.getEyeY(), g.getZ() + (g.getZ() - mob.getZ()) + 0.01); else g.getLookControl().setLookAt(mob); return false; } // ponytail: once it is angry at him he looks AWAY: vanilla EndermanFreezeWhenLookedAtGoal holds a stared-at enderman still (live 20:42, 7 straight up, he stared, it stood 3 fight clocks) // an enderman up a tree comes down to him once looked at (it teleports to its target); never pillar after it (live 20:09 -88,74,-890: 6 up a warped stem for one 8 up). The fight clock ends it if it never comes
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
        if (Math.abs(dy) > 3 && mob instanceof net.minecraft.world.entity.monster.EnderMan) { // (an enderman: its pearl is worth the climb; a zombie boxed 7 up waits on its out-of-sight clock, 640) more than a bridge's 3 up or down and no way on foot: his walker's way to it (dig, steps, a tower), then the fight; a way it has refused already ends the fight (World 17 19:26-19:51: a calm enderman 5 up, 28 off, "walkable false", 25 min standing still: bridgeToward gives up past 3 up, and nothing said so)
            BlockPos at = mob.blockPosition();
            if (g.attempts.skipped(com.claudemod.entity.life.Attempts.Kind.WALK, at, g.tickCount, 0)) { var w = g.attempts.why(com.claudemod.entity.life.Attempts.Kind.WALK, at); com.claudemod.ClaudeMod.LOGGER.info("[Claude fight] the {} at {} ({} up, {} off): no way on foot and his walker refused the way there ({}): leaving it (WorkGoal.doAttack)", mob.getType().getDescription().getString(), at.toShortString(), (int) dy, (int) horiz, w == null ? "no cause on file" : w.reason() + (w.detail().isEmpty() ? "" : " " + w.detail())); return true; }
            g.tasks.addFirst(new Task.Attack(t.target(), Math.max(200, t.maxTicks() - ticks))); g.tasks.addFirst(new Task.GoTo(at, 3));
            com.claudemod.ClaudeMod.LOGGER.info("[Claude fight] the {} at {} is {} up, {} off, no way on foot: his walker's way there (dig, steps or a tower), then the fight (WorkGoal.doAttack)", mob.getType().getDescription().getString(), at.toShortString(), (int) dy, (int) horiz);
            return true;
        }
        bridgeToward(mob.position(), 8); // (a chase over lava: a bow or the shore is the better trade under fire)
        return false;
    }

    // ---- fortress fights from cover (FortressFight; patch_blaze_loot.py) ----
    private int blazeRetreatTick = -1000; // ponytail: 10 s window from the retreat's start; fields added live start 0, which only matters in the first 10 s of a world
    private String cfMode = ""; private int cfPeekTick = -100, cfHopTick = -100, cfIdle; private boolean cfHealing; private java.util.UUID cfTarget;
    private int cfEmptyPeeks; // steps out to draw a lit blaze's shots that drew none
    /**
     * A wither skeleton with no 2-high gap about (World 15 19:00:01, "no 2-high gap within 10 ... shield and crits in the open"; two more
     * at 19:00:09, dead): he makes cover. A wither skeleton is 2.4 tall and its melee reaches only its attack box (its body widened
     * 0.83 sideways, at its own height). Where there's rock within 6: a 2-high tunnel 2 long into it at ground level, the gap the cover
     * fight wants (FortressFight.pickWither); it can't come in, from 2 in he's out of its box and it's in his reach (3). Else a 2-deep
     * hole at his feet with a cap two above the floor over it, set on a 3-block post at the rim: his head is under the floor, out of
     * its box; it can't drop in (the cap stops a 2.4-tall body in that column); it sees him through the open cells at floor level (an
     * unseen target is dropped after 3 s) and comes to the rim, where he hits its legs. In the hole he stands still: no crit hop (it
     * lifts his head into reach).
     * Tried first:
     * - 3 up a pillar: they stood 5 off, out of his reach, and a hit while he built it knocked him off.
     * - An open 1x1 hole 2 deep (stage 553): entities don't block each other, and one walked over the hole and dropped in on him.
     * - An L under the floor (a shaft and a side tunnel): from the surface it can't see in, so it forgot him and walked off.
     * Null: not for this fight.
     */
    private BlockPos wsDug; private int wsDugTick = -100000, wsDugSaid = -1000;
    private boolean diggable(ServerLevel level, BlockPos c) {
        var st = level.getBlockState(c);
        if (st.getCollisionShape(level, c).isEmpty() || st.getDestroySpeed(level, c) < 0 || st.getDestroySpeed(level, c) > 5 || st.getBlock() instanceof net.minecraft.world.level.block.FallingBlock || !level.getFluidState(c).isEmpty()) return false;
        for (var d : net.minecraft.core.Direction.values()) if (!level.getFluidState(c.relative(d)).isEmpty()) return false; // (no lava or water let in)
        return true;
    }
    /** In a capped hole: his head under the floor, rock round him at his feet and head, two cells over his head open, the cap over those. */
    private boolean inCappedHole() {
        if (!g.onGround()) return false;
        ServerLevel level = (ServerLevel) g.level(); BlockPos f = BlockPos.containing(g.getX(), g.getY() + 0.2, g.getZ());
        if (solidAt(level, f.above(2)) || solidAt(level, f.above(3)) || !solidAt(level, f.above(4))) return false;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) { if (dx == 0 && dz == 0) continue; if (!solidAt(level, f.offset(dx, 0, dz)) || !solidAt(level, f.offset(dx, 1, dz))) return false; }
        return true;
    }
    /** The mob can walk to {@code c} (its own pathfinder, the way it will come). */
    private boolean mobPaths(net.minecraft.world.entity.LivingEntity mob, BlockPos c) {
        if (!(mob instanceof net.minecraft.world.entity.Mob m)) return true;
        if (m.blockPosition().closerThan(c, 1.5)) return true;
        var path = m.getNavigation().createPath(c, 1); return path != null && path.canReach();
    }
    private boolean firmFloor(ServerLevel level, BlockPos c) { return solidAt(level, c) && !level.getBlockState(c).is(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK); }
    /** The 2-long tunnel off {@code f} (the cell it stands in) along {@code d}: both cells solid rock to dig, a solid roof and floor, rock beside them. */
    private boolean tunnelOk(ServerLevel level, BlockPos f, net.minecraft.core.Direction d) {
        for (int k = 1; k <= 2; k++) { BlockPos x = f.relative(d, k);
            if (!diggable(level, x) || !diggable(level, x.above()) || !solidAt(level, x.above(2)) || level.getBlockState(x.above(2)).getBlock() instanceof net.minecraft.world.level.block.FallingBlock || !firmFloor(level, x.below())) return false;
            for (var side : new net.minecraft.core.Direction[] {d.getClockWise(), d.getCounterClockWise()}) if (!solidAt(level, x.relative(side)) || !solidAt(level, x.above().relative(side))) return false; }
        return !solidAt(level, f.relative(d, 3)) ? false : solidAt(level, f.relative(d, 3).above()); // (shut at its end too)
    }
    private Boolean witherHole(Task.Attack t, net.minecraft.world.entity.LivingEntity mob) {
        if (!(mob instanceof net.minecraft.world.entity.monster.WitherSkeleton)) return null;
        return tallCover(t, mob, true);
    }
    private int wsLateSaid = -1000; // (the no-cover-in-time line: its own clock, the other tallCover lines' never hides it)
    /**
     * Cover from a mob too tall for a 2-high space (a wither skeleton 2.4, an enderman 2.9): in a capped hole, the fight from it; else,
     * with it within 12 and in sight, the cover made (a 2-high tunnel into rock within 6, or with {@code cap} the capped hole at his
     * feet, 4 blocks). Null: no cover to make here, the caller's fight goes on.
     */
    private Boolean tallCover(Task.Attack t, net.minecraft.world.entity.LivingEntity mob, boolean cap) {
        String who = mob.getType().getDescription().getString().toLowerCase(java.util.Locale.ROOT), aWho = ("aeiou".indexOf(who.charAt(0)) >= 0 ? "an " : "a ") + who;
        ServerLevel level = (ServerLevel) g.level();
        if (Boolean.getBoolean("claude.debugWither") && g.tickCount % 5 == 0) for (var w : level.getEntitiesOfClass(net.minecraft.world.entity.monster.WitherSkeleton.class, g.getBoundingBox().inflate(4))) com.claudemod.ClaudeMod.LOGGER.info("[Claude wither] me {} {} {} ground {} | ws {} {} {} ground {} reachBox {} (WorkGoal.witherHole)", String.format("%.2f", g.getX()), String.format("%.2f", g.getY()), String.format("%.2f", g.getZ()), g.onGround(), String.format("%.2f", w.getX()), String.format("%.2f", w.getY()), String.format("%.2f", w.getZ()), w.onGround(), w.isWithinMeleeAttackRange(g));
        if (inCappedHole()) {
            g.getNavigation().stop(); g.setSprinting(false); g.setJumping(false); g.xxa = 0; g.zza = 0; equipWeapon(); shieldDown();
            g.getLookControl().setLookAt(mob.getX(), mob.getY() + 0.3, mob.getZ()); // (its legs: what he reaches over the rim)
            if (canHitNow(mob)) { float hp0 = mob.getHealth(); g.getLookControl().faceNow(new Vec3(mob.getX(), mob.getY() + 0.3, mob.getZ())); g.attack(mob); g.swing(InteractionHand.MAIN_HAND);
                com.claudemod.ClaudeMod.LOGGER.info("[Claude fight] from my hole: hit the {} at the rim, hp {} -> {} (WorkGoal.tallCover)", who, hp0, mob.getHealth());
                if (!mob.isAlive()) g.noteDoing("killed a " + BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).getPath()); return false; }
            boolean withered = g.hasEffect(net.minecraft.world.effect.MobEffects.WITHER);
            if (withered && g.inventory.count(x -> x.is(net.minecraft.world.item.Items.MILK_BUCKET)) > 0 && g.getHealth() <= 12) { g.tasks.addFirst(new Task.Attack(t.target(), Math.max(200, t.maxTicks() - ticks))); g.tasks.addFirst(new Task.Eat(net.minecraft.world.item.Items.MILK_BUCKET)); cfSay("milk in the hole", "Wither on at health " + (int) g.getHealth()); return true; }
            net.minecraft.world.item.Item food = g.getFoodData().getFoodLevel() < 20 && g.getHealth() < 20 ? com.claudemod.director.ClaudeLife.foodToEat(g, g.getHealth() <= 6) : null;
            if (food != null) { g.tasks.addFirst(new Task.Attack(t.target(), Math.max(200, t.maxTicks() - ticks))); g.tasks.addFirst(new Task.Eat(food)); cfSay("eating in the hole", "health " + (int) g.getHealth()); return true; }
            if (ticks >= t.maxTicks() - 1 && mob.distanceTo(g) < 24) ticks = 2; // (the fight's clock waits while it comes to the rim)
            if (mob.distanceTo(g) > 24) return true; // (it went off: done here)
            if (++cfIdle > 20 * 30) { cfSay("it isn't coming", "30 s in the hole and the " + who + " stays " + (int) mob.distanceTo(g) + " off: done waiting"); return true; }
            cfSay("waiting in the hole", "the " + who + " " + (int) mob.distanceTo(g) + " off: it can't reach under the floor or drop in past the cap; it comes to the rim and gets hit there");
            return false;
        }
        if (mob.distanceTo(g) > 12 || !g.onGround() || !g.hasLineOfSight(mob)) return null; // (far off or unseen: the ordinary approach)
        if (g.tasks.stream().anyMatch(x -> x instanceof Task.Break)) return null; // (already digging it)
        BlockPos f = BlockPos.containing(g.getX(), g.getY() + 0.2, g.getZ());
        if (wsDug != null && wsDug.closerThan(f, 4) && g.tickCount - wsDugTick < 20 * 30) return null; // (dug one here just now: it's the cover fight's to use, no second)
        java.util.List<Task> dig = new java.util.ArrayList<>(); BlockPos in = null; String how = null;
        // a wall within 6: a tunnel 2 long from a cell it can stand in
        outer:
        for (int r = 0; r <= 6; r++) for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) { if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
            for (int dy = -1; dy <= 1; dy++) { BlockPos s0 = f.offset(dx, dy, dz);
                if (!com.claudemod.entity.life.FortressFight.stand(level, s0)) continue;
                if (!(level.getBlockState(s0.above(2)).getCollisionShape(level, s0.above(2)).isEmpty())) continue; // (it stands there too: 3 open)
                for (var d : net.minecraft.core.Direction.Plane.HORIZONTAL) { if (!tunnelOk(level, s0, d)) continue;
                    if (!mobPaths(mob, s0)) continue; // (the user 20:2x: cover "only works if ... they can path up to you": it walks to the mouth)
                    if (!s0.equals(f)) { var path = g.getNavigation().createPath(s0, 0); if (path == null || !path.canReach()) continue; dig.add(new Task.GoTo(s0.immutable(), 0.4)); }
                    BlockPos m = s0.relative(d), c = s0.relative(d, 2); dig.add(new Task.Break(m.above())); dig.add(new Task.Break(m)); dig.add(new Task.Break(c.above())); dig.add(new Task.Break(c.immutable()));
                    in = c.immutable(); how = "a 2-high tunnel into the rock at " + s0.toShortString() + " going " + d.getName(); break outer; } } }
        boolean capped = false;
        if (in == null && cap) { // the capped hole at his feet
            ItemStack blocks = scaffoldBlock();
            boolean ok = blocks.getCount() >= 4 && diggable(level, f.below()) && diggable(level, f.below(2)) && firmFloor(level, f.below(3)) && !solidAt(level, f.above()) && !solidAt(level, f.above(2)) && !solidAt(level, f.above(3));
            for (int dx = -1; dx <= 1 && ok; dx++) for (int dz = -1; dz <= 1 && ok; dz++) { if (dx == 0 && dz == 0) continue; if (!solidAt(level, f.offset(dx, -2, dz)) || !solidAt(level, f.offset(dx, -1, dz))) ok = false; } // (rock round both cells: a hole, not a ledge)
            if (ok) for (var d : net.minecraft.core.Direction.Plane.HORIZONTAL) { BlockPos post = f.relative(d);
                if (solidAt(level, post) || solidAt(level, post.above()) || solidAt(level, post.above(2)) || !solidAt(level, post.below())) continue; // (a rim cell to set the post on)
                if (level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, new net.minecraft.world.phys.AABB(post).expandTowards(0, 1, 0), e -> e != g).size() > 0) continue;
                boolean rim = false; for (var d2 : net.minecraft.core.Direction.Plane.HORIZONTAL) if (d2 != d && mobPaths(mob, f.relative(d2))) rim = true; if (!rim) continue; // (it walks to a rim cell, where he reaches its legs)
                net.minecraft.world.item.Item it = blocks.getItem();
                dig.add(new Task.Place(post.immutable(), it)); dig.add(new Task.Place(post.above().immutable(), it)); dig.add(new Task.Place(post.above(2).immutable(), it));
                dig.add(new Task.Place(f.above(2).immutable(), it)); // (the cap first, over his head while he still stands on the floor: from the hole the dug blocks' drops sat in its cell and the face was out of his place logic's view, stage 553)
                dig.add(new Task.Break(f.below().immutable())); dig.add(new Task.Break(f.below(2).immutable()));
                in = f.below(2).immutable(); capped = true; how = "a 2-deep hole at " + f.toShortString() + " capped two above the floor (a 3-block post on the " + d.getName() + " rim)"; break; }
        }
        if (in != null) { // the cover proximity rule, computed: the dig or build done before the first melee mob after him is in reach, or no cover (live 17:50:04 11-09, death-197: a tunnel begun with a wither skeleton 5 off, hit mid-dig 15 -> 10, then dead; stage 1312)
            double buildS = 0; BlockPos at = f;
            for (Task k : dig) {
                if (k instanceof Task.GoTo gt) { buildS += Math.sqrt(at.distSqr(gt.pos())) / 4.3; at = gt.pos(); }
                else if (k instanceof Task.Place) buildS += w("cover.place_s", 0.35);
                else if (k instanceof Task.Break br) { BlockState bs = level.getBlockState(br.pos()); float hard = bs.getDestroySpeed(level, br.pos()); if (hard <= 0) continue;
                    float speed = 1; boolean right = !bs.requiresCorrectToolForDrops(); for (int i = 0; i < g.inventory.items.getContainerSize(); i++) { ItemStack st = g.inventory.items.getItem(i); float sp = st.getDestroySpeed(bs); if (sp > speed) { speed = sp; right = right || st.isCorrectToolForDrops(bs); } }
                    buildS += hard * (right ? 30 : 100) / speed / 20.0 + w("cover.dig_aim_s", 0.2); }
            }
            net.minecraft.world.entity.Mob first = null; double arrive = Double.MAX_VALUE;
            for (var m : level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class, g.getBoundingBox().inflate(16), m -> m.isAlive() && m instanceof net.minecraft.world.entity.monster.Enemy && !shoots(m) && !(m instanceof net.minecraft.world.entity.monster.Creeper) && !(m instanceof net.minecraft.world.entity.monster.EnderMan) && (m.getTarget() == g || m == mob))) { // (an enderman teleports to him from any distance: its distance buys no time either way, stage 527 built its hole unhurt)
                double s0 = Math.max(0, m.distanceTo(g) - w("fight.mob_reach_blocks", 2.0)) / Math.max(0.5, mobPace(m, m instanceof net.minecraft.world.entity.monster.AbstractSkeleton ? 1.2 : 1.0)); if (s0 < arrive) { arrive = s0; first = m; } } // (its chase goal's speed: a skeleton's melee 1.2, the rest 1.0)
            if (first != null && arrive < buildS) {
                if (g.tickCount - wsLateSaid > 100 || g.tickCount < wsLateSaid) { wsLateSaid = g.tickCount; com.claudemod.ClaudeMod.LOGGER.info("[Claude fight] {} {} off and no 2-high gap: {} would take ~{} s, the {} {} off is on him in ~{} s: no cover to make in time, the fight where he stands (WorkGoal.tallCover)", aWho, (int) mob.distanceTo(g), how, String.format("%.1f", buildS), first.getType().getDescription().getString().toLowerCase(java.util.Locale.ROOT), (int) first.distanceTo(g), String.format("%.1f", arrive)); }
                return null; }
        }
        if (in == null) { if (g.tickCount - wsDugSaid > 100) { wsDugSaid = g.tickCount; com.claudemod.ClaudeMod.LOGGER.info("[Claude fight] {} {} off and no 2-high gap: no rock within 6 to tunnel into and no ground for a capped hole (fluid, bedrock, falling blocks, open round it, or under 3 blocks), the open fight" + (cap ? "" : " (no capped hole: one foe and health to spare)") + " (WorkGoal.tallCover)", aWho, (int) mob.distanceTo(g)); } return null; }
        g.getNavigation().stop(); g.xxa = 0; g.zza = 0; wsDug = in; wsDugTick = g.tickCount;
        g.tasks.addFirst(new Task.Attack(t.target(), Math.max(400, t.maxTicks() - ticks))); g.tasks.addFirst(new Task.GoTo(in, 0.3)); // (a hit mid-build knocks him off the spot: back to it, into the hole)
        for (int i = dig.size() - 1; i >= 0; i--) g.tasks.addFirst(dig.get(i));
        com.claudemod.ClaudeMod.LOGGER.info("[Claude fight] {} {} off and no 2-high gap: making cover, {}; he stands at {}, out of its reach, it in his (WorkGoal.tallCover)", aWho, (int) mob.distanceTo(g), how, in.toShortString());
        cfMode = "digging a gap"; return true;
    }
    private BlockPos footingSaid; // (the footing move said once a spot)
    /** A hit there throws him off: a drop of 4+ (or lava) within 2 blocks of {@code cell} the way {@code away} points, with no wall first. */
    private boolean lipBehind(ServerLevel level, BlockPos cell, Vec3 away) {
        for (int k = 1; k <= 2; k++) {
            BlockPos c = BlockPos.containing(cell.getX() + 0.5 + away.x * k, cell.getY(), cell.getZ() + 0.5 + away.z * k);
            if (c.equals(cell)) continue;
            if (!level.getBlockState(c).getCollisionShape(level, c).isEmpty() || !level.getBlockState(c.above()).getCollisionShape(level, c.above()).isEmpty()) return false; // (a wall behind him: the hit stops there)
            int depth = 0; BlockPos q = c.below();
            while (depth < 40 && level.getBlockState(q).getCollisionShape(level, q).isEmpty()) { if (level.getFluidState(q).is(net.minecraft.tags.FluidTags.LAVA)) return true; if (level.getFluidState(q).is(net.minecraft.tags.FluidTags.WATER)) break; depth++; q = q.below(); }
            if (level.getFluidState(c).is(net.minecraft.tags.FluidTags.LAVA) || depth >= 3) return true;
        }
        return false;
    }
    /**
     * Footing for a fight, as a player keeps it (Day to Day, World 15 13:48 and 13:51: fighting an enderman, then shooting a skeleton
     * from a lip while standing in fire, knocked off 17 and 22 down): a lip with a drop of 4+ or lava within 2 blocks behind him, the way
     * a hit from this foe throws him, means a step first to the nearest cell (4 at most) with firm ground or a wall that way, and never
     * fire under him; then the fight from there. Null: his footing's fine (or there's none better), the fight goes on.
     */
    /**
     * On his highway's floor with a melee mob already after him, and the chase's path off the floor: he holds the highway and
     * lets it come (it will), like a player on a bridge over a delta; the chase walked him off his railless highway into a
     * basalt delta after a magma cube a block below it, 1:45 lost getting back (live 23:59:58 11-06; stage 932). A mob not after
     * him is still chased.
     */
    private boolean holdHighway(net.minecraft.world.entity.LivingEntity mob, net.minecraft.world.level.pathfinder.Path path) {
        if (!(mob instanceof net.minecraft.world.entity.Mob m) || m.getTarget() != g && !(mob instanceof net.minecraft.world.entity.monster.Enemy && g.distanceTo(mob) <= com.claudemod.entity.life.Weights.w("fight.hold_range", 16)) || g.level().dimension() != net.minecraft.world.level.Level.NETHER || !g.onGround()) return false;
        String dim = com.claudemod.entity.life.PortalMap.NETHER;
        BlockPos under = g.blockPosition().below();
        if (!com.claudemod.entity.life.PortalMap.highwayBlock(g, dim, under, g.level().getBlockState(under))) return false;
        BlockPos off = null;
        for (int i = path.getNextNodeIndex(); i < path.getNodeCount() && off == null; i++) { BlockPos n = path.getNode(i).asBlockPos().below(); if (!com.claudemod.entity.life.PortalMap.highwayBlock(g, dim, n, g.level().getBlockState(n))) off = n; }
        if (off == null) return false;
        g.getNavigation().stop(); g.setSprinting(false); g.getLookControl().setLookAt(mob);
        if (!mob.getUUID().equals(holdSaid)) { holdSaid = mob.getUUID(); com.claudemod.ClaudeMod.LOGGER.info("[Claude fight] the {} {} off is after him (or a hostile in range of him: it comes) and the way to it leaves his highway at {}: holding the highway for it to come (WorkGoal.holdHighway)", mob.getType().getDescription().getString(), (int) g.distanceTo(mob), off.above().toShortString()); } // (once a mob: a hold is his choice, not a refusal)
        return true;
    }
    private java.util.UUID holdSaid; private boolean fightOnHw;
    private Boolean fightFooting(net.minecraft.world.entity.LivingEntity mob) {
        if (!g.onGround() || g.isInWater()) return null;
        ServerLevel level = (ServerLevel) g.level();
        BlockPos feet = BlockPos.containing(g.getX(), g.getY() + 0.2, g.getZ());
        Vec3 away = g.position().subtract(mob.position()).multiply(1, 0, 1); if (away.lengthSqr() < 1e-4) return null; away = away.normalize();
        boolean fire = level.getBlockState(feet).is(net.minecraft.tags.BlockTags.FIRE);
        boolean shooter = shoots(mob) || mob instanceof net.minecraft.world.entity.monster.Blaze || mob instanceof net.minecraft.world.entity.monster.Ghast; // (an arrow or fireball throws him from range: World 15 13:51, a skeleton's arrow off a lip, 22 down)
        boolean throwsHim = shooter ? mob.distanceTo(g) <= 24 && g.hasLineOfSight(mob) : mob.distanceTo(g) <= (footingSaid != null ? 6 : 4); // ponytail: 4 to start, 6 to finish the step (live 20:07 -119,76,-936: an enderman at the edge of 4 flipped it on and off, the approach stepped him back onto the lip 7 times in 2 s). (a melee mob only in reach: live 14:45 an enderman 15 off stepped him back and forth against the approach, 4 shuffles a second)
        if (!fire && (!throwsHim || !lipBehind(level, feet, away))) { footingSaid = null; return null; } // ponytail: live 14:45 -36,50,-898: a lip "behind" him from an enderman 15 off; the approach stepped him back onto it, 4 shuffles a second. Only a mob in reach throws him
        BlockPos best = null; double bd = Double.MAX_VALUE;
        String hd = com.claudemod.entity.life.PortalMap.NETHER; boolean onHw = level.dimension() == net.minecraft.world.level.Level.NETHER && com.claudemod.entity.life.PortalMap.highwayBlock(g, hd, feet.below(), level.getBlockState(feet.below()));
        for (BlockPos q0 : BlockPos.betweenClosed(feet.offset(-4, -1, -4), feet.offset(4, 1, 4))) { BlockPos q = q0.immutable();
            if (q.equals(feet) || !com.claudemod.entity.life.FortressFight.stand(level, q) || com.claudemod.entity.body.Controls.lavaCell(g, q)) continue;
            if (onHw && !com.claudemod.entity.life.PortalMap.highwayBlock(g, hd, q.below(), level.getBlockState(q.below()))) continue; // on his highway the footing stays on it: off its side is the delta it bridges, the mobs' ground (live 23:59:58 11-06: "firm footing" a block under his railless highway, 1:45 in the basalt; stage 932)
            Vec3 aw = Vec3.atBottomCenterOf(q).subtract(mob.position()).multiply(1, 0, 1); if (aw.lengthSqr() < 1e-4) continue; aw = aw.normalize();
            if (lipBehind(level, q, aw)) continue;
            double d = q.distSqr(feet) + (mob.distanceToSqr(Vec3.atBottomCenterOf(q)) < 2.5 * 2.5 ? 50 : 0); // (never into its arms for it)
            if (d < bd) { bd = d; best = q; } }
        if (best == null) { if (!feet.equals(footingSaid)) { footingSaid = feet; com.claudemod.ClaudeMod.LOGGER.info("[Claude fight] a lip behind him at {} the way the {} would throw him, and no firmer footing within 4: fighting on (WorkGoal.fightFooting)", feet.toShortString(), mob.getType().getDescription().getString()); } return null; }
        if (!feet.equals(footingSaid)) { footingSaid = feet; com.claudemod.ClaudeMod.LOGGER.info("[Claude fight] {} at {} the way the {} would throw him: to firm footing at {} first, then the fight (WorkGoal.fightFooting)", fire ? "fire under him" : "a lip behind him", feet.toShortString(), mob.getType().getDescription().getString(), best.toShortString()); }
        if (g.isUsingItem()) g.stopUsingItem();
        g.setSprinting(false); cfMove(best, 1.0);
        return false;
    }
    private final java.util.Map<java.util.UUID, BlockPos> mobWaited = new java.util.HashMap<>(); // a mob he waited out in a cell of his way: still there after the wait, waiting again is out
    /**
     * A mob standing where his walk's next step goes (World 17 01:29, the user: "he was trying to drop in a hole and there were 2 enderman
     * standing in it blocking him"; 00:53 an enderman and a baby piglin on his perch, "he should hit the enderman or push it off"): stalled or
     * shuffling there, one priced choice, walk.mob_in_cell. Hit it out (its kill, the hits it gives back while he does; an enderman on a
     * pearl hunt is the job itself; a piglin or a golem out: they all turn on him), wait for it to move (walk.mob_wait_s, once a mob and cell),
     * or another way (the walk written off with the mob as its cause: back on when it moves off, Attempts). True: dealt with.
     */
    public boolean mobInCell(Task.GoTo go, BlockPos node) {
        if (!(g.level() instanceof ServerLevel level)) return false;
        BlockPos feet = g.blockPosition(); int lo = Math.min(node.getY(), feet.getY()), hi = Math.max(node.getY(), feet.getY()) + 1;
        net.minecraft.world.phys.AABB way = new net.minecraft.world.phys.AABB(node.getX(), lo, node.getZ(), node.getX() + 1, hi + 1, node.getZ() + 1).deflate(0.05); // (the step's cell, its head room, and the column down to it on a drop)
        var in = level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, way, e -> e != g && e.isAlive() && !(e instanceof net.minecraft.world.entity.player.Player));
        if (in.isEmpty()) return false;
        var m = in.stream().min(java.util.Comparator.comparingDouble(e -> e.distanceToSqr(g))).get();
        String what = m.getType().getDescription().getString();
        boolean pearls = m instanceof net.minecraft.world.entity.monster.EnderMan && g.lastStep != null && g.lastStep.skill().equals("ender_pearls") || m instanceof net.minecraft.world.entity.monster.EnderMan && g.skillQueue.stream().anyMatch(s0 -> s0.skill().equals("ender_pearls"));
        com.claudemod.entity.life.Decide.Option hit;
        if (m instanceof net.minecraft.world.entity.monster.ZombifiedPiglin zp && !zp.isAngryAt(g)) { double[] f = com.claudemod.director.ClaudeLife.calmNeutralFightS(g, level, m); // (calm: the whole pack within its follow range joins at the first swing; one alone is a few seconds, six are thousands. Was out, review 108)
            hit = com.claudemod.entity.life.Decide.Option.of("hit it out", f[0], (int) f[3] + " calm one(s) join, ~" + Math.round(f[1]) + " s of swings, ~" + Math.round(f[2]) + " HP of hits"); }
        else if (m instanceof net.minecraft.world.entity.monster.piglin.AbstractPiglin || m instanceof net.minecraft.world.entity.animal.IronGolem || m instanceof net.minecraft.world.entity.npc.Villager)
            hit = com.claudemod.entity.life.Decide.Option.out("hit it out", "a " + what + ": hit one and the rest of them turn on him");
        else { int k = hitsToKill(m); double t = k * w("fight.swing_s", 0.6); boolean back = m instanceof net.minecraft.world.entity.monster.Enemy || m instanceof net.minecraft.world.entity.NeutralMob;
            double hp = back ? t * w("fight.mob_hits_per_s", 0.8) * mobHit(m) : 0;
            hit = com.claudemod.entity.life.Decide.Option.of("hit it out", t + com.claudemod.entity.life.Decide.hpS(g, 0, hp, mobHit(m)) - (pearls ? w("walk.mob_in_cell_pearl_s", 60) : 0), k + " swings" + (back ? ", ~" + Math.round(hp) + " HP back" : "") + (pearls ? ", its pearl the job" : "")); }
        var wait = node.equals(mobWaited.get(m.getUUID())) ? com.claudemod.entity.life.Decide.Option.out("wait for it to move", "waited once, it stayed")
                : com.claudemod.entity.life.Decide.Option.of("wait for it to move", w("walk.mob_wait_s", 15), "it wanders off");
        var round = com.claudemod.entity.life.Decide.Option.of("another way", w("walk.mob_round_s", 45), "the walk to " + go.pos().toShortString() + " off till it moves");
        var pick = com.claudemod.entity.life.Decide.choose(g, "walk.mob_in_cell", in.size() + " " + what + (in.size() > 1 ? "s" : "") + " in his next step " + node.toShortString() + ", he at " + feet.toShortString(), true, java.util.List.of(hit, wait, round));
        if (pick == null) return false;
        g.getNavigation().stop(); g.setSprinting(false);
        if (pick == hit) { g.tasks.addFirst(go); for (var e : in) if (!(e instanceof net.minecraft.world.entity.monster.piglin.AbstractPiglin)) g.tasks.addFirst(new Task.Attack(e.getUUID(), 20 * 30)); g.dropCurrent = true; return true; }
        if (pick == wait) { mobWaited.put(m.getUUID(), node.immutable()); g.tasks.addFirst(go); g.tasks.addFirst(new Task.Wait((int) (20 * w("walk.mob_wait_s", 15)), false)); g.dropCurrent = true; return true; }
        g.attempts.fail(com.claudemod.entity.life.Attempts.Kind.WALK, go.pos(), g.tickCount, feet, com.claudemod.entity.life.Attempts.Reason.BLOCKED_MOB, "a " + what + " in his step " + node.toShortString(), m.getUUID());
        g.dropCurrent = true; return true;
    }
    private int airborneTick = -100;
    private BlockPos planningFor; private int planningTick; // (the walk whose plan is being searched over ticks, since when)
    private BlockPos airPlanFor; // (the walk whose tick-2 plan was put off: he was in the air)
    private BlockPos refusedSaidFor; // (the walk the refused-step line was last said for)
    /** In the air with a walk to plan: said once a second, never planned from there (false: wait to land). */
    private boolean airborneSaid(BlockPos pos) { int nowT = g.getServer() == null ? g.tickCount : g.getServer().getTickCount(); if (nowT - airborneTick >= 20 || nowT < airborneTick) { airborneTick = nowT; com.claudemod.ClaudeMod.LOGGER.info("[Claude walk] in the air at {} with the walk to {} to plan: planned once he's landed, never from a cell he isn't standing on (WorkGoal.walkTo)", String.format("%.1f %.1f %.1f", g.getX(), g.getY(), g.getZ()), pos.toShortString()); } return false; }
    /**
     * Hurt in a blaze fight with no cover by him (World 15 10:09: "in the open: the ordinary fight" four times at 6 health, on fire, dead a minute on):
     * a blaze only shoots what it sees, so the nearest cell he can walk to (16 at most) (24 steps at most) that no blaze within 24 sees, then food, then the fight
     * again once he's healed. Already out of their sight: stood there healing. No such cell: running (Flee). Null: nothing to do here.
     */
    private Boolean blazePick; private int blazePickT = -100000; // the last blaze.retreat pick (true: out of their sight), so its line is written on a change
    /** Hurt in a blaze fight with no cover (blaze.retreat): fight on in the open (FortressFight.fightS for the blazes he knows of, at his health less the fire still on him), or out of their sight (hideSpot), the fireballs on the way, healed to full (Food.regenS) and the fight then at full health. True: retreat. Logged when it wins. */
    /** A blaze in range to shoot him: out to its own follow range (48), not 24 (live 00:46 11-08: one 29 off hit him 4 times while coverFight said "no blaze sees it", stage 983). */
    private boolean shootsHere(net.minecraft.world.entity.Entity b) { return b.isAlive() && b instanceof net.minecraft.world.entity.LivingEntity l && l.distanceTo(g) <= l.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.FOLLOW_RANGE); }
    private boolean blazeRetreatPays() {
        ServerLevel lv = (ServerLevel) g.level();
        var blz = com.claudemod.entity.life.Perception.known(g, net.minecraft.world.entity.monster.Blaze.class, g.getBoundingBox().inflate(48), this::shootsHere);
        int n = Math.max(1, blz.size());
        boolean shield = g.getItemBySlot(EquipmentSlot.OFFHAND).is(net.minecraft.world.item.Items.SHIELD) && !g.getCooldowns().isOnCooldown(net.minecraft.world.item.Items.SHIELD), fed = com.claudemod.director.ClaudeLife.foodToEat(g, true) != null;
        double armor = g.getArmorValue(), tough = g.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR_TOUGHNESS);
        double fire = g.isOnFire() ? g.getRemainingFireTicks() / 20.0 * w("blaze.burn_hp_per_s", 1) : 0; // (sure: the fire burns on either way)
        double on = com.claudemod.entity.life.FortressFight.fightS(g, Math.max(0.5, g.getHealth() - fire), armor, tough, shield, fed, 0, n);
        var all = lv.getEntitiesOfClass(net.minecraft.world.entity.monster.Blaze.class, g.getBoundingBox().inflate(48), this::shootsHere); // (the ones blazeRetreat goes out of the sight of)
        if (all.isEmpty()) return false; // (none within its 24: blazeRetreat has nothing to go out of the sight of; an empty list made every cell "hidden", 763's blaze 27 off)
        java.util.function.Predicate<BlockPos> hidden = c -> { for (var b : all) if (com.claudemod.entity.life.FortressFight.sees(lv, b, c)) return false; return true; };
        BlockPos feet = BlockPos.containing(g.getX(), g.getY() + 0.2, g.getZ()); java.util.Map<BlockPos, Integer> steps = new java.util.HashMap<>();
        BlockPos hide = hidden.test(feet) ? feet : blazeHideCell(lv, feet, hidden, steps);
        int boxN = 0; for (Direction dir : Direction.Plane.HORIZONTAL) for (int y = 0; y <= 1; y++) if (lv.getBlockState(feet.relative(dir).above(y)).canBeReplaced()) boxN++; if (lv.getBlockState(feet.above(2)).canBeReplaced()) boxN += 2; // (nowhere out of their sight: blazeRetreat walls him in where he stands, the roof against one more on a wall)
        boolean box = hide == null && boxN > 0 && scaffoldBlock().getCount() >= boxN;
        double walk = hide != null ? steps.getOrDefault(hide, 0) / com.claudemod.entity.life.Decide.sprint() : box ? boxN * w("cover.place_s", 0.35) / 2 : w("blaze.open_run_s", 15); // (a box: the build is the time in their sight, counted twice below as there-and-back)
        double fb = w("blaze.fireball_hp", 5), perB = fb * (1 - Math.min(20, Math.max(armor / 5.0, armor - fb / (2 + tough / 4))) / 25) + w("blaze.fire_hp", 3);
        double leaveHp = n * walk * w("blaze.shots_per_s", 0.6) * w("blaze.shot_hit_chance", 0.5) * perB * (shield ? w("blaze.shield_factor", 0.4) : 1);
        double back = 2 * walk + com.claudemod.entity.life.Decide.hpS(g, fire, leaveHp, perB) + com.claudemod.entity.life.Food.regenS(g, g.getMaxHealth() - Math.max(1, g.getHealth() - fire - leaveHp)) + com.claudemod.entity.life.FortressFight.fightS(g, g.getMaxHealth(), armor, tough, shield, fed, 0, n);
        boolean sameFight = g.tickCount - blazePickT < 20 * 30; blazePickT = g.tickCount; // (a pick 30 s after the last: a new fight, logged afresh)
        if (sameFight && blazePick != null && blazePick == (on > back)) return on > back; // logged when the pick changes (D2D 17:40 11-07: the retreat pick logged every tick, ~900 lines in 25 min)
        blazePick = on > back;
        com.claudemod.entity.life.Decide.choose(g, "blaze.retreat", n + " blaze(s) " + (feet.equals(hide) ? "known, none sees him where he stands" : "known, no cover") + ", health " + (int) g.getHealth() + (fire > 0 ? ", on fire" : "") + ", armor " + (int) armor + (shield ? ", a shield" : ""), true, java.util.List.of(
            com.claudemod.entity.life.Decide.Option.of("fight on", on, feet.equals(hide) ? "from where he stands" : "in the open"), com.claudemod.entity.life.Decide.Option.of("out of their sight", back, (hide != null ? "to " + hide.toShortString() : box ? "boxed in where he stands, " + boxN + " blocks" : "in the open") + ", ~" + Math.round(leaveHp) + " HP of fireballs, healed, then the fight at full health")));
        return on > back;
    }
    /** The nearest cell on foot no blaze sees (FortressFight.sees), 24 steps out, never through lava; steps in dist. Null: none. */
    private BlockPos blazeHideCell(ServerLevel level, BlockPos feet, java.util.function.Predicate<BlockPos> hidden, java.util.Map<BlockPos, Integer> dist) {
        java.util.ArrayDeque<BlockPos> q = new java.util.ArrayDeque<>(); dist.put(feet, 0); q.add(feet);
        while (!q.isEmpty() && dist.size() < 2000) {
            BlockPos f = q.poll(); int d0 = dist.get(f);
            if (d0 > 0 && hidden.test(f)) return f;
            if (d0 >= 24) continue; // (24 steps: about 5 s at a sprint, round a wall's end)
            for (Direction dir : Direction.Plane.HORIZONTAL) for (int dy = -1; dy <= 1; dy++) {
                BlockPos n = f.relative(dir).above(dy); if (dist.containsKey(n) || !com.claudemod.entity.life.FortressFight.stand(level, n)) continue;
                if (dy == 1 && !level.getBlockState(f.above(2)).getCollisionShape(level, f.above(2)).isEmpty()) continue;
                if (com.claudemod.entity.body.Controls.lavaCell(g, n)) continue;
                dist.put(n.immutable(), d0 + 1); q.add(n.immutable());
            }
        }
        return null;
    }
    private Boolean blazeRetreat(Task.Attack t, net.minecraft.world.entity.LivingEntity mob) {
        ServerLevel level = (ServerLevel) g.level();
        var blazes = level.getEntitiesOfClass(net.minecraft.world.entity.monster.Blaze.class, g.getBoundingBox().inflate(48), this::shootsHere);
        if (blazes.isEmpty()) return null;
        BlockPos feet = BlockPos.containing(g.getX(), g.getY() + 0.2, g.getZ());
        java.util.function.Predicate<BlockPos> hidden = c -> { for (var b : blazes) if (com.claudemod.entity.life.FortressFight.sees(level, b, c)) return false; return true; };
        if (hidden.test(feet)) {
            g.getNavigation().stop(); g.setSprinting(false); shieldDown(); g.getLookControl().setLookAt(mob);
            net.minecraft.world.item.Item food = g.getFoodData().getFoodLevel() < 20 ? com.claudemod.director.ClaudeLife.foodToEat(g, g.getHealth() <= 6) : null;
            if (food != null) { g.tasks.addFirst(new Task.Attack(t.target(), Math.max(200, t.maxTicks() - ticks))); g.tasks.addFirst(new Task.Eat(food)); cfSay("eating out of their sight", "health " + (int) g.getHealth() + ", hunger " + g.getFoodData().getFoodLevel() + ", no blaze sees " + feet.toShortString() + ": " + BuiltInRegistries.ITEM.getKey(food).getPath()); return true; }
            cfSay("healing out of their sight", "health " + (int) g.getHealth() + " at " + feet.toShortString() + ", no blaze sees it");
            if (ticks >= t.maxTicks() - 1) ticks = 2; // (the fight's clock waits while he heals)
            return false;
        }
        java.util.Map<BlockPos, Integer> dist = new java.util.HashMap<>(); BlockPos hide = blazeHideCell(level, feet, hidden, dist);
        g.tasks.addFirst(new Task.Attack(t.target(), Math.max(200, t.maxTicks() - ticks)));
        if (hide != null) {
            g.tasks.addFirst(new Task.GoTo(hide, 0.5));
            blazeRetreatTick = g.tickCount;
            com.claudemod.ClaudeMod.LOGGER.info("[Claude fight] health {}{}, no cover here and {} blaze(s) about: out of their sight to {} ({} steps), food, then the fight again (WorkGoal.blazeRetreat)", (int) g.getHealth(), g.isOnFire() ? ", burning" : "", blazes.size(), hide.toShortString(), dist.get(hide));
            cfMode = "out of their sight"; return true;
        }
        { // nowhere out of their sight: cover made where he stands, a block at feet and head on each side and one over him (a blaze only shoots what it sees; running in their sight is what killed him, World 15 12:06, six blazes, 20 -> 0)
            java.util.List<BlockPos> box = new java.util.ArrayList<>();
            for (Direction dir : Direction.Plane.HORIZONTAL) { box.add(feet.relative(dir)); box.add(feet.relative(dir).above()); }
            box.removeIf(c -> !level.getBlockState(c).canBeReplaced());
            BlockPos roof = feet.above(2);
            if (level.getBlockState(roof).canBeReplaced()) { // the roof has no face of its own to go against (the wall tops are diagonal to it): a block up on one wall first, then the roof against its side, as a player does
                boolean faced = false; for (Direction dir : Direction.Plane.HORIZONTAL) if (!level.getBlockState(roof.relative(dir)).canBeReplaced()) faced = true;
                if (!faced) { Direction toward = Direction.getNearest(blazes.get(0).getX() - g.getX(), 0, blazes.get(0).getZ() - g.getZ()); box.add(roof.relative(toward)); }
                box.add(roof);
            }
            ItemStack blocks = scaffoldBlock();
            if (!box.isEmpty() && blocks.getCount() >= box.size()) {
                net.minecraft.world.phys.AABB body = g.getBoundingBox().deflate(0.01);
                boolean inAWallCell = false; for (BlockPos c : box) if (body.intersects(new net.minecraft.world.phys.AABB(c))) inAWallCell = true;
                if (inAWallCell) { // his body (0.6 wide) over a wall cell's edge: the middle of his own block first, then the walls round him (World 15 12:22: walls queued in the cells he stood across, "where he's standing" every time, dead in place)
                    g.getNavigation().stop(); g.setSprinting(false); g.getMoveControl().setWantedPosition(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, 0.6);
                    cfSay("to the middle of his block", "walling in at " + feet.toShortString() + ": his body's over a wall cell's edge (" + String.format("%.2f %.2f", g.getX(), g.getZ()) + ")");
                    return false;
                }
                java.util.List<BlockPos> walls = new java.util.ArrayList<>(box.subList(0, box.size() - (box.contains(roof) ? (box.size() >= 2 && box.get(box.size() - 2).getY() == roof.getY() ? 2 : 1) : 0))); java.util.List<BlockPos> top = new java.util.ArrayList<>(box.subList(walls.size(), box.size()));
                walls.sort(java.util.Comparator.comparingDouble((BlockPos c) -> c.getY()).thenComparingDouble(c -> { double m = Double.MAX_VALUE; for (var b : blazes) m = Math.min(m, b.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(c))); return m; })); // (feet level first, each head block on the one under it; the side they're on first)
                box.clear(); box.addAll(walls); box.addAll(top); // (the roof's support, then the roof, last)
                { int out = 0; for (var it = g.tasks.iterator(); it.hasNext();) if (it.next() instanceof Task.GoTo gt && !gt.pos().equals(feet)) { it.remove(); out++; } if (out > 0) com.claudemod.ClaudeMod.LOGGER.info("[Claude fight] walling in at {}: {} walk(s) queued for the fight in the open dropped (the fight re-plans once he's healed) (WorkGoal.blazeRetreat)", feet.toShortString(), out); } // (live 07:19 11-07, review 207: the chase walk to 213,53,516 queued before the box ran once it was up and dug it out 3 s later)
                for (int i = box.size() - 1; i >= 0; i--) g.tasks.addFirst(new Task.Place(box.get(i).immutable(), blocks.getItem()));
                g.getNavigation().stop(); shieldDown();
                com.claudemod.ClaudeMod.LOGGER.info("[Claude fight] health {}{}, no cell out of {} blaze(s)' sight within 24 steps: walled in where he stands ({} {} at feet, head and over him), then food, then the fight (WorkGoal.blazeRetreat)", (int) g.getHealth(), g.isOnFire() ? ", burning" : "", blazes.size(), box.size(), com.claudemod.entity.life.Crafting.name(blocks.getItem()));
                cfMode = "walling in"; return true;
            }
        }
        g.tasks.addFirst(new Task.Flee(20 * 10));
        com.claudemod.ClaudeMod.LOGGER.info("[Claude fight] health {}{}, no cover, no cell out of {} blaze(s)' sight within 24 steps and too few blocks to wall in: running (WorkGoal.blazeRetreat)", (int) g.getHealth(), g.isOnFire() ? ", burning" : "", blazes.size());
        cfMode = "running"; return true;
    }
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
        if ((cv == null || blaze && !com.claudemod.entity.life.FortressFight.unbuilt(g, level, cv).isEmpty()) && blaze && g.getHealth() < g.getMaxHealth() && blazeRetreatPays()) { Boolean r = blazeRetreat(t, mob); if (r != null) return r; } // (priced, blaze.retreat: was "health 12 or less, 14 on fire") // (hurt, no cover: out of their sight first, never the open fight; World 15 10:09 burned to death "in the open" at 6) // (a cover still to build is no cover yet: hurt, the retreat's own choice first, the full box where nowhere's out of sight; stage 947)
        if (cv == null && !blaze) { Boolean wp = witherHole(t, mob); if (wp != null) return wp; } // (no 2-high gap for a wither skeleton: he digs a 2-high gap, never the open fight; World 15 19:00, two of them in the open, dead)
        if (cv == null) { if (ticks == 1) cfSay("in the open", "no cover for this " + mob.getType().getDescription().getString() + " here: the ordinary fight, shield and crits"); return null; }
        if (!(g.isUsingItem() && g.getUseItem().is(net.minecraft.world.item.Items.BOW))) equipWeapon(); // ponytail: live 18:56 "shooting it" x4 and arrows stayed 57: the sword swapped in every tick and cut each draw
        if (blaze) { var put = com.claudemod.entity.life.FortressFight.unbuilt(g, level, cv); var wallItem = com.claudemod.entity.life.FortressFight.wallItem(g); // cover with nothing to find (an open bridge): his blocks put up first, then the fight from behind them
            if (!put.isEmpty() && wallItem != null && g.inventory.count(x -> x.is(wallItem)) >= put.size() && g.tasks.stream().noneMatch(x -> x instanceof Task.Place)) {
                g.tasks.addFirst(new Task.Attack(t.target(), Math.max(200, t.maxTicks() - ticks)));
                for (int i = put.size() - 1; i >= 0; i--) g.tasks.addFirst(new Task.Place(put.get(i), wallItem));
                if (!com.claudemod.entity.life.FortressFight.at(g, cv.cover())) g.tasks.addFirst(new Task.GoTo(cv.cover(), 0.3));
                g.getNavigation().stop(); shieldDown();
                com.claudemod.ClaudeMod.LOGGER.info("[Claude fight] building cover from the blazes: {} block(s) at {} ({}) (WorkGoal.coverFight)", put.size(), put.stream().map(BlockPos::toShortString).toList(), cv.how());
                cfMode = "building cover"; return true; } }

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
        if (g.tasks.peek() instanceof Task.Eat eatNext && due == null && (inCover || !blaze && !meleeInByMeal()) && g.getFoodData().getFoodLevel() < 20) { // (eating drops the shield: out of cover with a sword in reach it waits — World 15 02:04, a meal at hunger 19 with skeletons on him, shield down, 17 -> 0) // a meal queued behind this fight: it goes first, the fight after it (World 15 01:29: "next: [Eat]" 400+ ticks behind an Attack that kept renewing past its clock, 13 HP down to 1)
            g.tasks.pollFirst(); g.tasks.addFirst(new Task.Attack(t.target(), Math.max(200, t.maxTicks() - ticks))); g.tasks.addFirst(eatNext);
            cfSay("eating first", "a meal was waiting behind the fight (hunger " + g.getFoodData().getFoodLevel() + ", health " + (int) hp + "), no volley due" + (inCover ? ", in cover" : ""));
            return true;
        }
        if (!blaze && !inCover && com.claudemod.entity.life.FortressFight.standAndFight(g, mob, cv.cover())) { cfHealing = false; cfSay("fighting it here", "the wither skeleton " + String.format("%.1f", mob.distanceTo(g)) + " off keeps pace, the gap " + Math.round(Math.sqrt(g.distanceToSqr(cv.cover().getX() + 0.5, cv.cover().getY(), cv.cover().getZ() + 0.5))) + " off: sword and shield where he stands, priced (wither.cover_walk)"); return null; } // (D2D death 14:28 11-08: sprinting 12 for the gap with it 1 off, 0 swings, 5 hits, dead)
        boolean backing = hp <= 12 || (withered && hp <= 14) || (g.isOnFire() && hp <= 14) || (cfHealing && (hp < 18 || withered)); // hurt: back to cover to heal before anything else, the blaze at arm's length too (live 12:22: swinging at one at 3 hearts with 6 about, dead)
        if (blaze && !backing) { // a blaze at arm's length (inside 2 it touches for 6 and sets him alight): hit it and keep it 2.4+ off; never his back to it, hurt or not
            net.minecraft.world.entity.monster.Blaze near = null; for (var b2 : level.getEntitiesOfClass(net.minecraft.world.entity.monster.Blaze.class, g.getBoundingBox().inflate(4), net.minecraft.world.entity.Entity::isAlive)) if (b2.distanceTo(g) < 3.0 && g.hasLineOfSight(b2) && (near == null || b2.distanceTo(g) < near.distanceTo(g))) near = b2;
            if (near != null && near.getBoundingBox().distanceToSqr(g.getEyePosition()) <= 9.0) { cfClose(level, near, due, shield); return false; }
        }
        if (backing && !inCover) { // the way back measured: a walk that isn't bringing him nearer is no way back (D2D death-220, live 21:52:43-21:53:18 11-09: "back into cover" 35 s while Controls refused the step to it, 10 -> 5 -> dead with 7 salmon on him and [Eat] queued behind the held attack; stage 1321)
            double dc = Math.sqrt(g.distanceToSqr(cv.cover().getX() + 0.5, cv.cover().getY(), cv.cover().getZ() + 0.5));
            if (cfBackTick < 0 || dc < cfBackBest - 0.5 || g.tickCount - cfBackTick > 20 * 30) { cfBackBest = dc; cfBackTick = g.tickCount; }
            if (g.tickCount - cfBackTick > com.claudemod.entity.life.Weights.w("cover.back_stuck_ticks", 40)) { // no nearer in 2 s: the fight where he stands
                net.minecraft.world.item.Item meal = g.getFoodData().getFoodLevel() < 20 ? com.claudemod.director.ClaudeLife.foodToEat(g, hp <= 6) : null;
                String why = "the way back to cover hasn't brought him nearer in " + (g.tickCount - cfBackTick) / 20 + " s (" + String.format("%.1f", dc) + " off)";
                if (due != null && shield) { g.getNavigation().stop(); g.setSprinting(false); shieldUp(due); cfSay("shield up where he stands", why + ": the volley on the shield here"); return false; }
                if (meal != null) { g.getNavigation().stop(); shieldDown(); cfBackTick = g.tickCount; cfEatTick = g.tickCount; g.tasks.removeIf(x -> x instanceof Task.Eat); g.tasks.addFirst(new Task.Attack(t.target(), Math.max(200, t.maxTicks() - ticks))); g.tasks.addFirst(new Task.Eat(meal)); cfSay("eating where he stands", why + ", health " + (int) hp + ", hunger " + g.getFoodData().getFoodLevel() + ", no volley due: " + BuiltInRegistries.ITEM.getKey(meal).getPath() + " now"); return true; }
                cfSay("fighting where he stands", why + ", nothing to eat: the fight here, not a walk that goes nowhere"); cfBackTick = g.tickCount; cfHealing = false; backing = false;
            }
        } else if (inCover) cfBackTick = -1;
        if (backing) { // (an expert backs off with health to spare and heals all the way: food's plenty)
            cfHealing = true;
            if (!inCover && due != null && shield) { shieldUp(due); cfMove(cv.cover(), 1.0); /* walking back behind the shield: stopping for every volley, 6 blazes always had one due and he never made the 3 steps (12:22) */ cfSay("shield up on the way back", "health " + (int) hp + ", a volley due: on the shield, not on his back"); return false; }
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
            if (mob.getBoundingBox().distanceToSqr(g.getEyePosition()) <= 9.0 && canHit(mob)) { cfIdle = 0; if (cfSwing(mob)) cfSay("hitting it from the gap", "it's " + String.format("%.1f", mob.distanceTo(g)) + " off and can't reach in; crits between swings"); return false; }
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
        if (mob.getBoundingBox().distanceToSqr(g.getEyePosition()) <= 9.0 && canHit(mob)) { cfClose(level, bz, due, shield); return false; }
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
            net.minecraft.world.entity.monster.Blaze watcher = null; for (var b2 : level.getEntitiesOfClass(net.minecraft.world.entity.monster.Blaze.class, g.getBoundingBox().inflate(24), net.minecraft.world.entity.Entity::isAlive)) if (b2 != mob && com.claudemod.entity.life.FortressFight.sees(level, b2, under)) watcher = b2;
            if (watcher != null) { // one at a time: another blaze sees where he'd stand to hit this one; lit or not, its volley comes once he's out there (live 05:44 11-09, death 115: "closing in: the blaze is quiet, 13 off" with 3 more about, between volleys; they lit up and hit him out there, 20 -> 8; stage 1238)
                if (!inCover) { g.setSprinting(true); cfMove(cv.cover(), 1.3); } else g.getNavigation().stop();
                if (shield && watcher.hasLineOfSight(g)) shieldUp(watcher);
                cfSay("one at a time", "another blaze " + (int) watcher.distanceTo(g) + " off sees " + under.toShortString() + ", where he'd hit this one: in cover till it doesn't");
                return false;
            }
            g.setSprinting(true); cfMove(under, 1.3);
            cfSay("closing in", "the blaze is " + (age < 0 ? "quiet" : "lit " + age + " ticks") + ", " + (int) mob.distanceTo(g) + " off" + (shield ? ", shield ready" : ""));
            return false;
        }
        // out of reach (floating high) or no time: a bow and arrows on him: shot from where he stands while it's quiet (vanilla's blaze hangs back from a player it sees: it never comes to a sword); drawing a bow and holding a shield up can't both be done, so the volley's blocked first (above) and the shot taken between
        if (!low || mob.distanceTo(g) > 14) { boolean drawingBow = g.isUsingItem() && g.getUseItem().is(net.minecraft.world.item.Items.BOW); if (canHit(mob) && (age < 0 || drawingBow)) { if (shootAt(mob)) { cfIdle = 0; cfSay("shooting it", "the blaze is " + (low ? "" : "up ") + "out of sword reach, " + (int) mob.distanceTo(g) + " off and quiet: arrows"); return false; } } }
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
        if (com.claudemod.director.Kit.food(g) == 0 && !(off <= 4 && Math.abs(below) <= 2 && mob instanceof net.minecraft.world.entity.Mob mm0 && mm0.getTarget() == g)) { closeSay("no food on me to heal on: not going in after the " + who + " (" + below + " below, " + off + " off), out of its sight instead"); return 0; } /* the user's engagement rule: no picking a fight without food for regen (stage 115: 8 down to a skeleton, a stone axe, no food) */
        ItemStack scaff = solidScaffold();
        java.util.List<Vec3> wide = new java.util.ArrayList<>();
        com.claudemod.entity.nav.ActionPath.Plan plan = null;
        java.util.Set<BlockPos> edges = new java.util.HashSet<>(); double edgeHp = 0;
        for (int k = 0; k < 3; k++) {
            plan = com.claudemod.entity.nav.ActionPath.plan(g, level, feet, to, 2.0, !scaff.isEmpty(), 3000, com.claudemod.entity.nav.ActionPath.Mode.MAY_DIG, g.attempts.of(com.claudemod.entity.life.Attempts.Kind.WALK), g.tickCount, wide);
            if (plan == null) break;
            BlockPos lava = null; edges.clear(); edgeHp = 0;
            for (var a : plan.acts()) {
                BlockPos p = a instanceof com.claudemod.entity.nav.ActionPath.Act.Walk w ? w.feet() : a instanceof com.claudemod.entity.nav.ActionPath.Act.Put pu ? pu.pos().above() : null;
                if (p != null && (lava = lavaBeside(level, p)) != null) break;
                if (p != null && edgeDanger(level, p) != null) {
                    float fall = edgeFall(level, p), fallHp = fall < 0 ? Float.MAX_VALUE : com.claudemod.entity.nav.Leaps.damage(g, level, fall, null);
                    if (fallHp >= g.getHealth() - w("fight.edge_margin_hp", 4)) { closeSay("the way in to the " + who + " runs along an edge at " + p.toShortString() + " (" + edgeDanger(level, p) + (fall < 0 ? "" : ", " + Math.round(fallHp) + " HP down it") + "): planned again wide of it"); lava = p.immutable(); break; } // (lava down it, or a fall he wouldn't live through: one knock is death)
                    if (edges.add(p.immutable())) edgeHp += w("fight.edge_knock_p", 0.1) * fallHp; // a fall he lives through is a price, never a ban: the chance a shot knocks him off there times what the fall costs (D2D review 81, live 17:37 11-07: a 6+ drop beside the only way up read as a ban, "no way in" 56 times, 2+ minutes in the open under the blazes; stage 1015)
                }
            }
            if (lava == null) break;
            if (level.getFluidState(lava).is(net.minecraft.tags.FluidTags.LAVA)) closeSay("the way in to the " + who + " runs by lava at " + lava.toShortString() + ": planned again wide of it");
            wide.add(Vec3.atBottomCenterOf(lava)); plan = null;
        }
        if (plan == null) { closeSay("no way in to the " + who + " (" + below + " below, " + off + " off) on foot, by digging or by blocks" + (wide.isEmpty() ? "" : " wide of the lava") + ": out of its sight instead"); return 0; }
        boolean shield = g.getItemBySlot(EquipmentSlot.OFFHAND).is(net.minecraft.world.item.Items.SHIELD);
        double secs = plan.walks() * 0.25 + plan.digs() * 0.6 + plan.puts() * 0.8;
        double take = Math.floor(secs / 2.0) * 4.0 * (1 - Math.min(20, g.getArmorValue()) / 25.0) * (shield ? 0.3 : 1.0) + edgeHp; // (a shot every ~2 s on the way in, ~4 a bolt or arrow less his armor; a shield takes most; and the falls its edges risk)
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
        if (mob instanceof net.minecraft.world.entity.monster.Creeper cr) return cr.getSwellDir() > 0 && cr.distanceTo(g) < w("creeper.blast_reach", 7); // a lit one in reach of its bang: the shield takes it (an unlit one is hit back or left, not shielded)
        if (mob instanceof net.minecraft.world.entity.monster.CrossbowAttackMob && net.minecraft.world.item.CrossbowItem.isCharged(mob.getMainHandItem()) && mob.distanceTo(g) < 24 && mob.hasLineOfSight(g)) return true; // a loaded crossbow on him (a piglin's, a pillager's): it looses any moment and the bolt is there in a tick or two (the arrow-in-flight check below is too late for it); down again while it reloads
        if (shoots(mob) && mob instanceof net.minecraft.world.entity.monster.AbstractSkeleton sk) { // (a wither skeleton is a skeleton by class but swings a sword: the melee branch below, live 18:38 11-08, 0 of its hits blocked)
            if (sk.isUsingItem() && sk.getTicksUsingItem() >= 10 && sk.distanceTo(g) < 24 && g.hasLineOfSight(sk)) return true; // it looses at ~20 ticks; the shield takes 5 to come up
        } else if (!(mob instanceof net.minecraft.world.entity.monster.Creeper) && mob.distanceTo(g) < 3.5) {
            return true;
        }
        for (var a : level().getEntitiesOfClass(net.minecraft.world.entity.projectile.AbstractArrow.class, g.getBoundingBox().inflate(12), a -> a.getOwner() != g && !a.onGround())) {
            if (a.getDeltaMovement().dot(g.getEyePosition().subtract(a.position())) > 0) return true; // coming his way
        }
        return false;
    }

    /** A melee mob on him that walks into reach before a meal (1.6 s) is down: vanilla's pace, ~44 x (speed x its chase modifier)^2 blocks a second (a zombie 2.3, a wither skeleton at its 1.2 about 4). Live 18:39 11-08: "eating first" at 2 HP, a wither skeleton 5 off was on him in 1.1 s. */
    private boolean meleeInByMeal() {
        return !level().getEntitiesOfClass(net.minecraft.world.entity.Mob.class, g.getBoundingBox().inflate(16), m -> m.isAlive() && m.getTarget() == g && !shoots(m) && !(m instanceof net.minecraft.world.entity.monster.Creeper)
            && m.distanceTo(g) - w("fight.reach_blocks", 3.5) < mobPace(m, 1.2) * w("food.eat_s", 1.6)).isEmpty();
    }
    /** Blocks a second a mob walks at its goal's speed modifier: vanilla moves it speed x speed (setSpeed sets its input too), a player at 0.98 x 0.1 for 4.317. */
    static double mobPace(net.minecraft.world.entity.LivingEntity m, double mod) { return 44.05 * Math.pow(m.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED) * mod, 2); }
    /** How far a shooter looses from: its weapon's own range (a bow 15, a crossbow 8), a trident drowned's goal 10. */
    static double shotRange(net.minecraft.world.entity.LivingEntity m) { return m.getMainHandItem().getItem() instanceof net.minecraft.world.item.ProjectileWeaponItem pw ? pw.getDefaultProjectileRange() : 10; }

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
        Vec3 top = Vec3.atCenterOf(t.pos()).add(0, 0.5, 0); // (the face he clicks: the top; inReach passes on any face, and the top was 4.8 from his eyes, the click refused, the till ended undone and the farm asked it again, stage 93 under the gate)
        if (!inReach(t.pos()) || g.getEyePosition().distanceTo(top) > w("reach.block", 4.45)) return walkTo(t.pos().above(), 1.5) && g.getEyePosition().distanceTo(top) > w("reach.block", 4.45);
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
            if (hookD < 1.0) { int blocked = g.attempts.counters.merge("castBlocked", 1, Integer::sum); if (blocked >= 3) { g.attempts.counters.put("castBlocked", 0); g.useHeld(); failed(com.claudemod.entity.life.Attempts.Kind.REACH, t.water(), com.claudemod.entity.life.Attempts.Reason.BLOCKED_BLOCK, "the cast lands out of the water: something in the line"); com.claudemod.ClaudeMod.LOGGER.info("[Claude life] the line stops at his feet every cast (something in the way): not fishing from here"); return true; } } else g.attempts.counters.put("castBlocked", 0); // (a block over or before him catches the line: aiming higher never helps, and he cast twenty times)
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
            if (pillarDone > 0 && t.steps() > 0 && digInAt != null && digInTasks != null && !digInTasks.isEmpty() && digInTasks.get(0) == t && (pillarFeet.getX() != digInAt.getX() || pillarFeet.getZ() != digInAt.getZ())) { // knocked off his cover's tower partway up: the next block would go where he landed, a pole beside it, and the walls and lid were planned round its top; the cover isn't there, so it's chosen again (D2D 1059, live 20:29-20:33 11-07: an arrow put him 2 off at 1 up, the hut's Places walked at its top from below, "clearing them" 179 times, shot dead on the stub)
                com.claudemod.ClaudeMod.LOGGER.info("[Claude life] knocked off his skytower at {} ({} of {} up), now at {}: the cover choice again (WorkGoal.doPillar)", digInAt.toShortString(), pillarDone, t.steps(), pillarFeet.toShortString());
                g.tasks.removeIf(x -> digInTasks.stream().anyMatch(y -> y == x)); digInTasks = null;
                digInFromArrows();
                return true;
            }
            if (!level.getBlockState(pillarFeet).getCollisionShape(level, pillarFeet).isEmpty() && g.getY() > pillarFeet.getY() + 0.01) pillarFeet = pillarFeet.above(); // (on top of it, not beside a thin one: an open trapdoor's wall in his cell, stage 995) standing IN a block lower than full (soul sand, a slab, a path): the tower goes on top of it, not "already there" (live 12:11, 14 min pinned on soul sand)
            { BlockState fs = level.getBlockState(pillarFeet); // a flower, a sapling, a mushroom in his own cell: no block ever goes in there; a player punches it out first (review 355, live 07:15 by the golem, the same jump over a daisy again and again; stage 993)
              if (!fs.isAir() && fs.getCollisionShape(level, pillarFeet).isEmpty() && !fs.canBeReplaced() && fs.getFluidState().isEmpty() && fs.getDestroySpeed(level, pillarFeet) == 0 && !fs.is(net.minecraft.tags.BlockTags.CROPS) && !(fs.getBlock() instanceof net.minecraft.world.level.block.BaseTorchBlock)) {
                com.claudemod.ClaudeMod.LOGGER.info("[Claude life] tower at {}: {} in his cell, a block can't go in over it: punched out first (WorkGoal.doPillar)", pillarFeet.toShortString(), fs.getBlock().getName().getString());
                g.tasks.addFirst(new Task.Pillar(t.steps() - pillarDone)); g.tasks.addFirst(new Task.Break(pillarFeet.immutable())); return true; }
              if (!fs.getCollisionShape(level, pillarFeet).isEmpty() && !fs.isFaceSturdy(level, pillarFeet, Direction.UP)) { // an open trapdoor, a fence gate, a low slab in the cell: no block goes in, and it's no floor a level up (review 305, live 07:14 by the golem: an open trapdoor's wall read as "there already, up a level" three times, he never left the ground; stage 995)
                if (digsInTheWay(level, pillarFeet, fs)) { com.claudemod.ClaudeMod.LOGGER.info("[Claude life] tower at {}: {} in the cell, no floor on it and no block goes in: dug out first (WorkGoal.doPillar)", pillarFeet.toShortString(), fs.getBlock().getName().getString());
                    g.tasks.addFirst(new Task.Pillar(t.steps() - pillarDone)); g.tasks.addFirst(new Task.Break(pillarFeet.immutable())); return true; }
                com.claudemod.ClaudeMod.LOGGER.info("[Claude life] tower at {}: {} in the cell, no floor on it, no block goes in, and not his to dig: no tower here ({} of {} up) (WorkGoal.doPillar)", pillarFeet.toShortString(), fs.getBlock().getName().getString(), pillarDone, t.steps()); return true; } }
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
        String inCell = ""; for (net.minecraft.world.entity.Entity e0 : level.getEntities(g, new net.minecraft.world.phys.AABB(pillarFeet).expandTowards(0, 2, 0))) inCell += " " + e0.getType().getDescription().getString() + String.format("@%.1f,%.1f,%.1f", e0.getX(), e0.getY(), e0.getZ()); // (the mobs in or over the cell: live 00:53 11-06, an enderman and a baby piglin on his perch, cause unproven)
        if (!high) { dbg("in the cell:" + (inCell.isEmpty() ? " none" : inCell) + "; jump too low y=" + g.getY() + " feet=" + pillarFeet.getY() + " jumping=" + g.jumpInput() + " delay=" + g.jumpDelay() + " ground=" + g.onGround() + " sneak=" + g.isShiftKeyDown() + " dy=" + g.getDeltaMovement().y + " ticks=" + pillarTicks + " holding=" + g.getMainHandItem() + " above: " + level.getBlockState(g.blockPosition().above()).getBlock() + "/" + level.getBlockState(g.blockPosition().above(2)).getBlock() + "/" + level.getBlockState(g.blockPosition().above(3)).getBlock()); return true; } // the jump didn't make it (low ceiling, slab...): give up towering
        // right click the top of the block he jumped off: the new block goes where his feet were
        boolean already = !level.getBlockState(pillarFeet).getCollisionShape(level, pillarFeet).isEmpty() && level.getBlockState(pillarFeet).isFaceSturdy(level, pillarFeet, Direction.UP); // landed on something there anyway (a floor on top: an open trapdoor's wall is none, review 305)
        if (already) { com.claudemod.ClaudeMod.LOGGER.info("[Claude life] tower at {}: {} there already, up a level without a block of his (y {}) (WorkGoal.doPillar)", pillarFeet.toShortString(), name(level.getBlockState(pillarFeet)), String.format("%.2f", g.getY())); pillarDone++; pillarPhase = 0; return pillarDone >= t.steps(); } // up a level either way (not his block to take down)
        if (!level.getFluidState(pillarFeet).isEmpty()) g.placeAt(pillarFeet, pillarItem); // in water: against the side walls (or whatever's under it)
        else g.useOn(pillarFeet.below(), Direction.UP);
        if (level.getBlockState(pillarFeet).getCollisionShape(level, pillarFeet).isEmpty()) { // the game didn't take it (yet): click again next tick, jump again if he lands
            if (g.tickCount - pillarMissLog > 20) { pillarMissLog = g.tickCount; com.claudemod.ClaudeMod.LOGGER.info("[Claude life] tower at {}: the block didn't go in at the top of the jump (y {}, held {}, in the cell:{}, the cell {}) (WorkGoal.doPillar)", pillarFeet.toShortString(), String.format("%.2f", g.getY()), g.getMainHandItem(), inCell.isEmpty() ? " none" : inCell, name(level.getBlockState(pillarFeet))); } // (D2D review 300, live 23:27:50 11-07: "jump too low" 4 times in 9 s by the golem, his apex 75.25 over feet 74, the place's failure never said)
            if (pillarTicks < 40) return false;
            dbg("in the cell:" + (inCell.isEmpty() ? " none" : inCell) + "; place failed at " + pillarFeet + " y=" + g.getY() + " held=" + g.getMainHandItem());
            return true;
        }
        // only a tower he'll come back down (to reach a tree, glowstone, ore up in a ceiling) goes on the list to take down;
        // one to get out of somewhere (a pit, water, a hole in his mine's floor) is his way up and stays, like a player's
        boolean comesDown = g.tasks.stream().anyMatch(q -> q instanceof Task.Pillar p && p.steps() < 0 || q instanceof Task.FinishTree || q instanceof Task.ChopTree);
        if (comesDown && !com.claudemod.entity.life.PitGuard.isCap(g, pillarFeet) && !(com.claudemod.director.ClaudeLife.isMineCell(g, pillarFeet.above()) && !com.claudemod.director.ClaudeLife.isMineCell(g, pillarFeet))) // (a tower up out of the hole his PitGuard is closing is the cap's fill, his build: on the list mid-chop, the next chop dug at the cap, review 372, stage 1218)
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
    /**
     * An arrow or a swing gets there: a collision ray from his eye to its body's middle or its eye (glass, leaves, fences and walls stop
     * it, as they stop a player's crosshair and his arrows). Seeing (hasLineOfSight, Perception.clear since 7345d37) looks past them;
     * hitting doesn't: he drew at a skeleton behind a fence and hit the fence.
     */
    private boolean canHit(net.minecraft.world.entity.LivingEntity mob) {
        Vec3 eye = g.getEyePosition();
        for (Vec3 to : new Vec3[] {mob.getBoundingBox().getCenter(), mob.getEyePosition(), mob.position().add(0, 0.4, 0)}) { // ponytail: a player hits any part of it; its legs too (live 20:12:06: an enderman hitting him under a wart block "behind rock, let be", 3 hits in 1 s)
            var hit = g.level().clip(new net.minecraft.world.level.ClipContext(eye, to, net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, g));
            if (hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS) return true;
        }
        return false;
    }
    /** A draw someone else aims (the End fight's shots): kept, not cut as a stray draw (line ~228). */
    public void bowAimed() { bowAimTick = g.tickCount; }
    private boolean shootAt(net.minecraft.world.entity.LivingEntity mob) {
        if (mob instanceof net.minecraft.world.entity.monster.EnderMan) { if (g.isUsingItem() && g.getUseItem().is(net.minecraft.world.item.Items.BOW)) g.stopUsingItem(); return false; } /* vanilla: an enderman teleports out of every arrow; live 01:42 twelve arrows at one, two after he said "switching to the diamond sword" */
        ItemStack bow = g.inventory.find(s -> s.is(net.minecraft.world.item.Items.BOW));
        // livestock is walked up to and hit, like a player does: arrows are kept for blazes, endermen and the dragon (01:47 thirteen arrows at a pig 26 off, at full health)
        if (!(mob instanceof net.minecraft.world.entity.monster.Enemy) && !lureShot) { if (g.isUsingItem() && g.getUseItem().is(net.minecraft.world.item.Items.BOW)) g.stopUsingItem(); return false; }
        if (bow.isEmpty() || g.inventory.count(s -> s.is(net.minecraft.world.item.Items.ARROW)) == 0) { if (g.isUsingItem() && g.getUseItem().is(net.minecraft.world.item.Items.BOW)) g.stopUsingItem(); return false; }
        double d = g.distanceTo(mob);
        double minRange = mob instanceof net.minecraft.world.entity.monster.Creeper ? 5.0 : lureShot ? 2.0 : 6.0; // (a shot to draw a golem to his tower: close is fine, it can't reach him)
        boolean drawing = g.isUsingItem() && g.getUseItem().is(net.minecraft.world.item.Items.BOW);
        if (d < minRange - (drawing ? 1.0 : 0) || d > 28 || !canHit(mob) || g.isInWater()) { if (drawing) g.stopUsingItem(); return false; }
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
            var hitB = g.level().clip(new net.minecraft.world.level.ClipContext(g.getEyePosition(), aim, net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, g)); Vec3 want = aim.subtract(g.getEyePosition());
            com.claudemod.ClaudeMod.LOGGER.info("[Claude life] shot at a {} {} blocks off (look {}/{} vs aim {}/{}, its v {}, {}) ", mob.getType().getDescription().getString(), (int) d, (int) g.getYRot(), (int) g.getXRot(), (int) Math.toDegrees(Math.atan2(-want.x, want.z)), (int) -Math.toDegrees(Math.atan2(want.y, want.horizontalDistance())), String.format("%.2f %.2f", vel.x, vel.z), hitB.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK ? "a block in the line at " + hitB.getBlockPos().toShortString() : "line clear"); // ponytail: live 15:46, ~21 arrows at a skeleton 10 off, none landed: log the aim to prove why
        }
        return true;
    }

    private void equipWeapon() {
        ItemStack best = ItemStack.EMPTY;
        double bestDmg = 0;
        for (int i = 0; i < g.inventory.items.getContainerSize(); i++) {
            ItemStack s = g.inventory.items.getItem(i);
            if (!(s.getItem() instanceof net.minecraft.world.item.SwordItem) && !(s.getItem() instanceof net.minecraft.world.item.AxeItem)) continue;
            double dmg = weaponDamage(s) * weaponSpeed(s); // damage a second: a wooden axe's 7 a hit at 0.8 swings a second loses to an iron sword's 6 at 1.6 (live 02:01 11-06, he died to an enderman swinging a wooden axe, the sword in his pack)
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
