package com.claudemod.entity.nav;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;

import com.claudemod.entity.ClaudeEntity;
import com.claudemod.entity.life.MineBlueprint;
import com.claudemod.entity.life.Task;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * The whole way somewhere, planned before he sets off, the way a player knows his world: over the land (round cliffs, trees,
 * lava, across water) in legs his walker takes one at a time; into his mine by its way in and down its rows when the spot is
 * down his mine, and back out the same way. A GoTo farther than his walker plans, or one that goes between the surface and his
 * mine, becomes those legs. A leg he can't walk: the rest of that route dropped and the walk planned again round it (12 plans in a row at most).
 * Land not loaded yet: planned up to its edge, then planned again from there.
 */
public final class Route {
    private Route() {}

    /** Farther than this across (blocks) and the walk gets a route; nearer, his walker plans it itself. */
    public static final int FAR = 24;
    private static final int LEG = 10; // blocks a surface leg covers
    private static final int BUDGET = 60000; // columns looked at per surface plan

    /** A route he's queued: its end, its legs (cells), the GoTos it queued in order (by identity), its end's GoTo, how far along it he is. */
    private static final class Active {
        final BlockPos target; final double within; final Set<BlockPos> legs; final List<Task> order; final Task dest; final boolean destIsLeg; final int at; final Map<Task, Integer> idx = new java.util.IdentityHashMap<>();
        int progress = -1; // the last leg he's passed (on this route: only while he walks it)
        int onSince; boolean off; // when he (re)joined it: his trail counts from then; off: another walk is running in its place
        Active(BlockPos target, double within, Set<BlockPos> legs, List<Task> order, boolean destIsLeg, int at) { this.target = target; this.within = within; this.legs = legs; this.order = order; this.dest = order.get(order.size() - 1); this.destIsLeg = destIsLeg; this.at = at; this.onSince = at; for (int i = 0; i < order.size(); i++) idx.put(order.get(i), i); }
        int indexOf(Task t) { Integer i = idx.get(t); return i == null ? -1 : i; }
        int legIndex(BlockPos p) { for (int i = 0; i < order.size(); i++) if (order.get(i) instanceof Task.GoTo go && go.pos().equals(p)) return i; return -1; }
    }
    private static final Map<UUID, Active> active = new HashMap<>(); // the latest
    private static final Map<UUID, List<Active>> routes = new HashMap<>(); // every route whose end is still queued (or walked), oldest first
    private static final Map<UUID, java.util.LinkedHashMap<BlockPos, Integer>> trail = new HashMap<>(); // the cells he's walked through lately (the last 4000), when
    /** Every tick he's on a job: the cell he's in, on his trail (a leg of a route he walked through, whatever walk took him there, is passed). */
    public static void step(ClaudeEntity g) {
        var m = trail.computeIfAbsent(g.getUUID(), k -> new java.util.LinkedHashMap<>(512, 0.75f, true) { @Override protected boolean removeEldestEntry(Map.Entry<BlockPos, Integer> e) { return size() > 4000; } });
        m.put(g.blockPosition().immutable(), g.tickCount);
    }
    private static boolean live(ClaudeEntity g, Active r) { if (g.currentTask == r.dest) return true; for (Task q : g.tasks) if (q == r.dest) return true; return false; }
    /** expand's answer "his walker takes it as it is". */
    public static boolean walkerTakes(List<Task> legs) { return legs == MARK; }
    /** A walk one of his routes queued (its own copy). */
    public static boolean owns(ClaudeEntity g, Task t) { List<Active> rs = routes.get(g.getUUID()); if (rs == null || t == null) return false; for (Active r : rs) if (r.indexOf(t) >= 0) return true; return false; }
    private static final Map<UUID, BlockPos> planFor = new HashMap<>(); private static final Map<UUID, Integer> plans = new HashMap<>(); // plans made in a row for one spot (12 and his walker takes it)

    /** A leg of the route he's on (never expanded again). */
    public static boolean isLeg(ClaudeEntity g, BlockPos p) { List<Active> rs = routes.get(g.getUUID()); if (rs != null) for (Active r : rs) if (r.legs.contains(p) && live(g, r)) return true; return false; }

    /** A leg he couldn't walk: the rest of the route dropped, and the walk to its end planned again round the failed spot (12 plans in a row at most). */
    public static void legFailed(ClaudeEntity g, BlockPos p) {
        Active a = active.get(g.getUUID()); if (a == null || !a.legs.contains(p)) return;
        boolean still = g.tasks.stream().anyMatch(t -> t instanceof Task.GoTo go && (a.legs.contains(go.pos()) || go.pos().equals(a.target)));
        if (!still && !p.equals(a.target)) { active.remove(g.getUUID()); com.claudemod.ClaudeMod.LOGGER.info("[Claude route] leg {} failed after the route to {} was dropped: not planned again", p.toShortString(), a.target.toShortString()); return; }
        int before = g.tasks.size();
        g.tasks.removeIf(t -> t instanceof Task.GoTo go && a.legs.contains(go.pos()));
        active.remove(g.getUUID());
        g.tasks.addFirst(new Task.GoTo(a.target, a.within));
        com.claudemod.ClaudeMod.LOGGER.info("[Claude route] leg {} failed: {} legs dropped, planning the way to {} again round it", p.toShortString(), before - g.tasks.size() + 1, a.target.toShortString());
    }

    /**
     * The legs for a walk to {@code to}, or null when his walker takes it as it is (near, or nothing a route adds: a cave off his
     * mine, a spot under the ground away from his mine).
     */
    public static List<Task> expand(ClaudeEntity g, ServerLevel level, BlockPos to, double within) {
        { List<Task> seen = alongRoutes(g, to); if (seen != null) return seen == MARK ? null : seen; } // (dropped: empty; his walker's: null; back on a route after other walks: the walk to its end, planned again from here) // (a leg behind him: dropped; a leg ahead of him: his walker's)
        BlockPos me = g.blockPosition();
        MineBlueprint b = g.blueprint; boolean mine = b != null && !b.isEmpty();
        int meNook = -1, toNook = -1; // a nook's pocket (his nooks, the one being moved) hangs off its mouth row: in his mine, never "on the land" (from the old pocket under the grass: out by his door and round, 12 legs for 8 blocks)
        if (mine) { meNook = com.claudemod.director.ClaudeLife.nookRowAt(g, b, me); if (meNook < 0) meNook = com.claudemod.director.ClaudeLife.nookRowAt(g, b, me.above()); toNook = com.claudemod.director.ClaudeLife.nookRowAt(g, b, to); if (toNook < 0) toNook = com.claudemod.director.ClaudeLife.nookRowAt(g, b, to.above()); }
        boolean meIn = mine && (b.isWalk(me) || b.isWalk(me.above()) || b.isWalk(me.below()) || meNook >= 0)
                && !(me.distSqr(b.outside()) <= 9 || me.distSqr(b.entrance) <= 9); // at his mine's door he's out already: "up the rows and out" from there replanned to the same door, forever (starving at -20, 66, 76)
        boolean toIn = mine && (b.isWalk(to) || b.isWalk(to.above()) || b.isWalk(to.below()) || toNook >= 0 || under(level, to) && near(b, to, 16));
        final int meRow = meNook >= 0 ? meNook : mine ? nearestRow(b, me) : -1, toRow = toNook >= 0 ? toNook : mine ? nearestRow(b, to) : -1; // (from or to a pocket: its mouth row)
        boolean meTop = !under(level, me);
        double across = Math.hypot(to.getX() - me.getX(), to.getZ() - me.getZ());
        List<BlockPos> legs = new ArrayList<>(); List<Double> slack = new ArrayList<>(); List<BlockPos> takeUp = new ArrayList<>();
        String why;
        if (toIn && !meIn) { // down his mine from outside it: over the land to its way in, in, down the rows
            if (!meTop) return null; // (in a cave off his mine: his walker)
            BlockPos out = b.outside();
            double off = Math.hypot(out.getX() - me.getX(), out.getZ() - me.getZ());
            if (off > 4 || Math.abs(out.getY() - me.getY()) > 1) { // (under his door, down the cave beside it, is not at it: 5 below it at -18, 62, -29 the walker stalled at the foot of the wall, 04:52)
                Surface s = surface(g, level, me, out);
                if (s == null && off > FAR) return null;
                if (s == null) com.claudemod.ClaudeMod.LOGGER.info("[Claude route] no way over the land from {} to his mine's door at {} ({} below it): the legs to the door stay, the climb his walker's (Route.expand)", me.toShortString(), out.toShortString(), out.getY() - me.getY());
                else {
                    for (BlockPos p : s.legs) { legs.add(p); slack.add(1.5); }
                    if (s.partial) return finish(g, to, within, legs, slack, true, "to his mine's way in (as far as the plan goes: planned again from there)");
                }
            }
            for (BlockPos q : b.wayInCells(false)) { // his own station standing in his way in (a temp furnace by the door): taken up on the way in, after the leg to the door
                BlockState qs = level.getBlockState(q);
                if (!(qs.is(Blocks.FURNACE) || qs.is(Blocks.CRAFTING_TABLE) || qs.is(Blocks.SMOKER) || qs.is(Blocks.BLAST_FURNACE))) continue;
                boolean his = g.myStations.contains(q) || g.tempStations.contains(q) || com.claudemod.entity.life.TempLedger.has(g, q);
                if (!his) { com.claudemod.ClaudeMod.LOGGER.info("[Claude route] a {} stands in his mine's way in at {}, not his: left, he walks round it (Route.expand)", qs.getBlock().getName().getString(), q.toShortString()); continue; }
                com.claudemod.director.ClaudeLife.takeUpStation(q); takeUp.add(q.immutable());
                com.claudemod.ClaudeMod.LOGGER.info("[Claude route] his own {} stands in his mine's way in at {}: taken up on the way in (Route.expand)", qs.getBlock().getName().getString(), q.toShortString());
            }
            legs.add(out); slack.add(1.5); legs.add(b.entrance); slack.add(0.8);
            for (BlockPos p : b.walkCells(0, toRow)) { legs.add(p); slack.add(0.8); }
            why = "over the land to his mine, in, down its rows";
        } else if (meIn && !toIn && across > FAR) { // out of his mine to somewhere off it: up the rows, out, then over the land (planned from the door)
            for (BlockPos p : b.walkCells(meRow, 0)) { legs.add(p); slack.add(0.8); }
            legs.add(b.entrance); slack.add(0.8); legs.add(b.outside()); slack.add(1.5);
            return finish(g, to, within, legs, slack, true, "up his mine's rows and out, then over the land");
        } else if (meIn && toIn && across > FAR) { // along his mine: its rows as they join
            for (BlockPos p : b.walkCells(meRow, toRow)) { legs.add(p); slack.add(0.8); }
            why = meNook >= 0 || toNook >= 0 ? "along his mine's rows (from or to his nook's mouth row)" : "along his mine's rows";
        } else if (!meIn && !toIn && meTop && across > FAR && !under(level, to)) { // over the land
            Surface s = surface(g, level, me, to);
            if (s == null) return null;
            for (BlockPos p : s.legs) { legs.add(p); slack.add(1.5); }
            if (s.partial) return finish(g, to, within, legs, slack, true, "over the land (as far as the plan goes: planned again from there)");
            why = "over the land";
        } else return null;
        List<Task> plan = finish(g, to, within, legs, slack, false, why);
        if (plan != null && !takeUp.isEmpty()) { // after the leg to his door: the station broken (in reach from there) and picked up, then in
            int i0 = 0; while (i0 < plan.size() && !(plan.get(i0) instanceof Task.GoTo go0 && go0.pos().equals(b.outside()))) i0++;
            List<Task> up = new ArrayList<>(); for (BlockPos q : takeUp) up.add(new Task.Break(q)); up.add(new Task.Pickup(40));
            plan.addAll(Math.min(i0 + 1, plan.size()), up);
        }
        return plan;
    }

    /**
     * Before a walk to {@code to}: where he is on every route still queued. On the route this walk belongs to, every leg before it
     * is behind him; on another, the farthest leg he's standing at and all before it (he walked through them). Those still queued
     * come off his queue. Back: an empty list when this walk is itself behind him on a route he's on (dropped), null-wrapped as
     * "his walker's" (a non-empty marker) when it's a leg ahead on one, or null when it's a walk of its own to plan.
     */
    private static List<Task> alongRoutes(ClaudeEntity g, BlockPos to) {
        List<Active> rs = routes.computeIfAbsent(g.getUUID(), k -> new ArrayList<>());
        Task cur = g.currentTask; BlockPos here = g.blockPosition(); int now = g.tickCount;
        var tr = trail.get(g.getUUID());
        for (var it = rs.iterator(); it.hasNext(); ) { Active r = it.next(); if (!live(g, r)) { it.remove(); if (r.legs.contains(to) && r.indexOf(cur) < 0) com.claudemod.ClaudeMod.LOGGER.info("[Claude route] {} was a leg of a route of his he's done with (to {}): a new walk, planned as one (Route.alongRoutes)", to.toShortString(), r.target.toShortString()); } }
        for (var it = rs.iterator(); it.hasNext(); ) { // a route whose end he's stood at since it was planned (whatever walk took him there): done
            Active r = it.next(); if (r.indexOf(cur) >= 0) continue;
            Integer w = tr == null ? null : tr.get(r.target);
            if (w == null || w < r.at) continue;
            final Active rr = r; int before = g.tasks.size(); g.tasks.removeIf(q -> rr.indexOf(q) >= 0); it.remove();
            com.claudemod.ClaudeMod.LOGGER.info("[Claude route] he's been at the end of his route to {} since it was planned ({} s ago, on another walk): its {} walk(s) left off his queue (Route.alongRoutes)", r.target.toShortString(), (now - w) / 20, before - g.tasks.size());
        }
        for (Active r : rs) {
            int ci = r.indexOf(cur), was = r.progress;
            boolean copy = ci < 0 && !owns(g, cur) && cur instanceof Task.GoTo cg0 && (r.legs.contains(cg0.pos()) || cg0.pos().equals(r.target)); // (a walker's copy of one of its legs: still on it; another route's own leg over the same cell is that route's, never this one's)
            if (ci < 0 && !copy) { if (cur instanceof Task.GoTo && !r.off) { r.off = true; com.claudemod.ClaudeMod.LOGGER.info("[Claude route] off his route to {} for a walk to {} (its leg {} of {} passed): nothing of it counts till he's back on it (Route.alongRoutes)", r.target.toShortString(), to.toShortString(), r.progress + 1, r.order.size()); } continue; }
            if (r.off) { // back on it after other walks: measured again from where he rejoins
                r.off = false; r.onSince = now;
                double off = Math.sqrt(to.distSqr(here));
                if (ci >= 0 && off > 4) {
                    final Active rr = r; int before = g.tasks.size(); g.tasks.removeIf(q -> q != cur && rr.indexOf(q) >= 0);
                    rs.remove(r); if (active.get(g.getUUID()) == r) active.remove(g.getUUID());
                    com.claudemod.ClaudeMod.LOGGER.info("[Claude route] back on his route to {} at {} after other walks, its next leg {} {} off him: its {} other walk(s) off his queue, the way to its end planned again from here (Route.alongRoutes)", r.target.toShortString(), here.toShortString(), to.toShortString(), (int) off, before - g.tasks.size());
                    return List.of(new Task.GoTo(r.target, r.within));
                }
            }
            if (ci >= 0) r.progress = Math.max(r.progress, ci - 1); // the route he's walking: what's before this leg is behind him
            int upTo = ci >= 0 ? ci - 1 : r.legIndex(cur instanceof Task.GoTo cg1 ? cg1.pos() : to) - 1;
            for (int i = upTo; i > r.progress; i--) if (r.order.get(i) instanceof Task.GoTo go) { Integer w = tr == null ? null : tr.get(go.pos()); if (w != null && w >= r.onSince) { r.progress = i; break; } } // a leg further on he walked through on it, since he (re)joined it
            if (r.progress == was) continue;
            final Active rr = r; int before = g.tasks.size();
            g.tasks.removeIf(q -> q != cur && rr.indexOf(q) >= 0 && rr.indexOf(q) <= rr.progress);
            if (g.tasks.size() < before) com.claudemod.ClaudeMod.LOGGER.info("[Claude route] {} leg(s) of his route to {} behind him at {} (its leg {} of {} passed, walking it): off his queue (Route.alongRoutes)", before - g.tasks.size(), r.target.toShortString(), here.toShortString(), r.progress + 1, r.order.size());
        }
        for (Active r : rs) {
            int ci = r.indexOf(cur);
            if (ci >= 0 && ci <= r.progress) { com.claudemod.ClaudeMod.LOGGER.info("[Claude route] the walk to {} is behind him on his route to {} (he's at {}): dropped, not walked back to (Route.alongRoutes)", to.toShortString(), r.target.toShortString(), here.toShortString()); return List.of(); }
            if (ci >= 0) return cur == r.dest && !r.destIsLeg ? null : MARK; // a leg of the route he's on, ahead of him: never expanded again (the end of a route that stopped short is planned again from there)
        }
        for (Active r : rs) { // a walk of some other job's to a cell of a route he's on
            if (!r.legs.contains(to) || r.off) continue; // (a route he's off: its cells are just cells)
            int li = r.legIndex(to);
            if (li >= 0 && li <= r.progress) { com.claudemod.ClaudeMod.LOGGER.info("[Claude route] the walk to {} is a leg behind him on his route to {} (he's at {}, walking it): dropped, not walked back to (Route.alongRoutes)", to.toShortString(), r.target.toShortString(), here.toShortString()); return List.of(); }
            return MARK; // ahead of him on it: his walker's, as the leg itself
        }
        return null;
    }
    private static final List<Task> MARK = List.of(new Task.Wait(0, false)); // (alongRoutes: "his walker takes it")

    /** The legs as GoTos, the walk's own end last (a leg too, or, when the plan stops short, left to be planned again from there). */
    private static List<Task> finish(ClaudeEntity g, BlockPos to, double within, List<BlockPos> legs, List<Double> slack, boolean again, String why) {
        List<Task> out = new ArrayList<>(); Set<BlockPos> mark = new HashSet<>();
        BlockPos last = null, me = g.blockPosition(); boolean started = false;
        for (int i = 0; i < legs.size(); i++) {
            BlockPos p = legs.get(i); if (p.equals(last)) continue;
            if (!started && p.equals(me)) continue; // (where he stands: his start is no leg; it stayed queued behind him, 12:30)
            if (i == legs.size() - 1 && p.equals(to)) continue; // (the end's own walk covers it: two equal walks side by side, and the duplicate drop took the end, 12:31)
            started = true; out.add(new Task.GoTo(p, slack.get(i))); mark.add(p.immutable()); last = p;
        }
        out.add(new Task.GoTo(to, within)); if (!again) mark.add(to.immutable());
        int n = to.equals(planFor.get(g.getUUID())) ? plans.getOrDefault(g.getUUID(), 0) + 1 : 1;
        planFor.put(g.getUUID(), to.immutable()); plans.put(g.getUUID(), n);
        if (n > 12) { com.claudemod.ClaudeMod.LOGGER.info("[Claude route] to {}: planned 12 times without getting there, his walker takes it as it is", to.toShortString()); return null; }
        Active a = new Active(to.immutable(), within, mark, out, !again, g.tickCount);
        active.put(g.getUUID(), a); List<Active> rs = routes.computeIfAbsent(g.getUUID(), k -> new ArrayList<>()); rs.add(a); while (rs.size() > 8) rs.remove(0);
        com.claudemod.ClaudeMod.LOGGER.info("[Claude route] to {} ({} blocks off): {} legs, {}", to.toShortString(), (int) Math.sqrt(g.blockPosition().distSqr(to)), out.size(), why);
        return out;
    }

    /** Below the ground's top there by more than 3 (a cave, his mine), as far as the loaded land says; unloaded: not. */
    static boolean under(ServerLevel level, BlockPos p) {
        LevelChunk ch = level.getChunkSource().getChunkNow(p.getX() >> 4, p.getZ() >> 4);
        if (ch == null) return false;
        return p.getY() < ch.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX() & 15, p.getZ() & 15) - 3;
    }
    private static boolean near(MineBlueprint b, BlockPos p, int r) { for (var row : b.rows()) for (BlockPos f : row.feet()) if (f.distSqr(p) <= r * r) return true; return false; }
    /** The row nearest p, branches and runs too (a spot at a branch's end is reached by that branch). */
    static int nearestRow(MineBlueprint b, BlockPos p) {
        var rs = b.rows(); int bi = 0; double bd = Double.MAX_VALUE;
        for (int i = 0; i < rs.size(); i++) for (BlockPos f : rs.get(i).feet()) { double d = f.distSqr(p); if (d < bd) { bd = d; bi = i; } }
        return bi;
    }

    // ---- over the land: A* over the ground's columns ----

    private record Surface(List<BlockPos> legs, boolean partial, double cost) {}
    private static final int BAD = Integer.MIN_VALUE, UNKNOWN = Integer.MIN_VALUE + 1;
    private record Col(int feet, boolean water, double extra) {}

    /**
     * Where he'd stand in column (x, z) coming from feet height `ref`: the same height, a step up, or a drop of up to 4, whichever
     * is open (feet and head clear, no lava, ground under, or the water's top to swim at). The ground's top alone was wrong under
     * an overhang (the roof read as the ground: a 3-high wall round where he stood).
     */
    private static Col col(ServerLevel level, Map<Long, Col> cache, int x, int z, int ref) {
        long k = key(x, z) * 31 + ref; Col c = cache.get(k); if (c != null) return c;
        LevelChunk ch = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
        if (ch == null) c = new Col(UNKNOWN, false, 0);
        else {
            c = new Col(BAD, false, 0);
            for (int y : new int[] {ref, ref + 1, ref - 1, ref - 2, ref - 3, ref - 4, ref + 2}) { // (a 2-high step last: only when nothing lower stands)
                BlockPos feet = new BlockPos(x, y, z); BlockState fs = ch.getBlockState(feet), hs = ch.getBlockState(feet.above()), gs = ch.getBlockState(feet.below());
                if (!open(level, feet, fs) || !open(level, feet.above(), hs)) continue;
                if (fs.getFluidState().is(FluidTags.LAVA) || hs.getFluidState().is(FluidTags.LAVA) || gs.getFluidState().is(FluidTags.LAVA)) { c = new Col(BAD, false, 0); break; }
                boolean water = fs.getFluidState().is(FluidTags.WATER) && hs.getFluidState().isEmpty(); // at the water's top: swimming
                boolean ground = !gs.getCollisionShape(level, feet.below()).isEmpty() || gs.getFluidState().is(FluidTags.WATER);
                if (!ground && !water) continue; // (open air under: falls on to a lower candidate)
                if (gs.is(BlockTags.LEAVES)) continue; // (a tree's canopy is no footing: legs up on it had him pillar 5 high to reach them)
                if (gs.is(Blocks.MAGMA_BLOCK) || gs.is(Blocks.CACTUS) || gs.is(Blocks.CAMPFIRE) || gs.is(Blocks.SOUL_CAMPFIRE) || fs.is(Blocks.FIRE) || fs.is(Blocks.SWEET_BERRY_BUSH) || fs.is(Blocks.POWDER_SNOW) || fs.is(Blocks.CACTUS)) { c = new Col(BAD, false, 0); break; }
                if (fs.getFluidState().is(FluidTags.WATER) && !water) continue; // under the water's top: not a walk
                c = new Col(y, water, water ? 2.0 : 0); // (swimming is slower than walking)
                break;
            }
        }
        cache.put(k, c);
        return c;
    }
    /** He can be in it: nothing to bump into (air, grass, flowers, water). */
    private static boolean open(ServerLevel level, BlockPos p, BlockState s) { return s.getCollisionShape(level, p).isEmpty() && !s.is(Blocks.POWDER_SNOW); }
    private static long key(int x, int z) { return ((long) x << 32) | (z & 0xffffffffL); }

    private record N(int x, int z, int feet, double g, double f) {}

    /** The way over the land from `from` to `to`'s column, as leg ends every LEG blocks (and at the water's edges); null if there's none in the loaded land. */
    static Surface surface(ClaudeEntity g, ServerLevel level, BlockPos from, BlockPos to) { return surface(g, level, from, to, BUDGET, Double.MAX_VALUE, -1); }

    /**
     * The real walk over the land from {@code from} to beside {@code to} (within {@code dy} of its height), in blocks of route, or -1
     * when there's none as short as {@code maxLen} (an animal up a cliff, across a ravine: no plan walks there, or only a long way round).
     */
    public static double walkLength(ClaudeEntity g, ServerLevel level, BlockPos from, BlockPos to, double maxLen, int dy) {
        Surface s = surface(g, level, from, to, 20000, maxLen, dy);
        return s == null || s.partial ? -1 : s.cost;
    }

    static Surface surface(ClaudeEntity g, ServerLevel level, BlockPos from, BlockPos to, int budget, double maxCost, int atDy) {
        long t0 = System.nanoTime(); double goalG = 0;
        Map<Long, Col> cache = new HashMap<>();
        // (nodes keyed by column AND height: the cave floor under the ground and the ground over it are two places; one height a
        // column made the overhang's top unreachable once the floor under it was found first)
        Map<Long, Double> best = new HashMap<>(); Map<Long, Long> parent = new HashMap<>(); Map<Long, int[]> at = new HashMap<>();
        Set<Long> unknown = new HashSet<>(); // (columns)
        var failed = g.attempts.of(com.claudemod.entity.life.Attempts.Kind.WALK); int now = g.tickCount;
        List<BlockPos> recentFails = new ArrayList<>(); for (var e : failed.entrySet()) if (now - e.getValue() < 20 * 120) recentFails.add(e.getKey());
        PriorityQueue<N> open = new PriorityQueue<>((a, b2) -> Double.compare(a.f, b2.f));
        int tx = to.getX(), tz = to.getZ(), ty = to.getY();
        // the spot's own height: where its columns stand within 2 of it, the plan ends there at that height, never in the pit beside it
        // (to his door at -18, 67, -29 the plan ended 6 under it at -17, 61, -29: "a column within 1" took any height, and he spun at
        // the foot of the wall, 04:03 and 04:52)
        // (and where nothing stands at its height in its 3x3 (a lava pool, a pond), the nearest ring that has somewhere, never "any height":
        // to surface lava at 51, 71, -57 the plan took the ravine floor 56 under it, 13:33)
        int ring = atDy >= 0 ? 1 : standRing(level, cache, tx, tz, ty), gr = ring > 0 ? ring : 1, tol = atDy >= 0 ? atDy : ring > 0 ? 2 : 6;
        boolean wantH = atDy < 0;
        if (wantH && (ring != 1)) com.claudemod.ClaudeMod.LOGGER.info("[Claude route] land plan to {}: nowhere to stand at its height right by it (lava, water, a drop): {} (Route.surface)", to.toShortString(), ring > 0 ? "the plan ends " + ring + " out from it at its height" : "nowhere within 4 either: the plan ends by it within 6 of its height");
        int deep = Math.min(from.getY(), ty) - 4; // (more than 4 under both ends: a ravine, a cave; dear)
        long sk = node(from.getX(), from.getZ(), from.getY());
        open.add(new N(from.getX(), from.getZ(), from.getY(), 0, h(from.getX(), from.getZ(), tx, tz))); best.put(sk, 0.0); at.put(sk, new int[] {from.getX(), from.getZ(), from.getY()});
        long goal = Long.MIN_VALUE, closest = sk; double closestH = h(from.getX(), from.getZ(), tx, tz), closest3 = closestH + (wantH ? 3 * Math.abs(from.getY() - ty) : 0);
        double underG = -1; int underFeet = 0; BlockPos under = null; // the first way to its columns at the wrong height
        int expanded = 0;
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        while (!open.isEmpty() && expanded++ < budget) {
            N n = open.poll(); long nk = node(n.x, n.z, n.feet);
            if (n.g > best.getOrDefault(nk, Double.MAX_VALUE) + 1e-9) continue;
            if (n.f > maxCost) break; // (nothing left that could be that short)
            if (underG >= 0 && n.f > underG * 1.6 + 24) break; // (only the pit's floor under it so far, and no way round to its height a player would walk: as near as it gets)
            double hn = h(n.x, n.z, tx, tz), hn3 = hn + (wantH ? 3 * Math.abs(n.feet - ty) : 0); // (as near as it gets: the height off counts 3 times over)
            if (hn3 < closest3) { closest3 = hn3; closestH = hn; closest = nk; }
            boolean atCols = Math.abs(n.x - tx) <= gr && Math.abs(n.z - tz) <= gr;
            if (atCols && Math.abs(n.feet - ty) <= tol) { goal = nk; goalG = n.g; break; }
            if (atCols && wantH && underG < 0) { underG = n.g; underFeet = n.feet; under = new BlockPos(n.x, n.feet, n.z); } // under it (or over it), not at it: a way to its height is looked for a while longer
            if (unknown.contains(key(n.x, n.z))) continue; // (the plan stops at the loaded land's edge)
            for (int[] d : dirs) {
                int x = n.x + d[0], z = n.z + d[1];
                Col c = col(level, cache, x, z, n.feet);
                int feet = c.feet;
                boolean unk = feet == UNKNOWN;
                if (feet == BAD) continue;
                if (unk) feet = n.feet;
                int dy = feet - n.feet;
                Col here = col(level, cache, n.x, n.z, n.feet);
                boolean swim = c.water || here.water;
                if (dy >= 3) continue; // a cliff: round it (a 2-high step is cut into, below)
                if (dy <= -4 && !c.water) continue; // a drop that hurts: round it, unless into water
                double cost = (d[0] != 0 && d[1] != 0 ? 1.414 : 1.0) * (swim ? 2.5 : 1.0) + (dy == 1 ? 0.4 : 0) + (dy == 2 ? 16.0 : 0) + (dy <= -2 ? 1.0 : 0) + c.extra + (unk ? 0.5 : 0)
                        + (atDy < 0 && feet < deep ? 0.5 * (deep - feet) : 0); // (under both ends by more than 4: dear, a ravine's floor only when the spot's down there or nothing else gets there)
                if (d[0] != 0 && d[1] != 0) { // a diagonal: both sides open at about this height (no corner cutting through a trunk)
                    Col a = col(level, cache, n.x + d[0], n.z, n.feet), b2 = col(level, cache, n.x, n.z + d[1], n.feet);
                    if (a.feet == BAD || b2.feet == BAD || a.feet != UNKNOWN && Math.abs(a.feet - n.feet) > 1 || b2.feet != UNKNOWN && Math.abs(b2.feet - n.feet) > 1) continue;
                }
                for (BlockPos f : recentFails) if (Math.abs(f.getX() - x) <= 2 && Math.abs(f.getZ() - z) <= 2) { cost += 40; break; } // where a walk failed lately: round it
                long k = node(x, z, feet); double ng = n.g + cost;
                if (ng >= best.getOrDefault(k, Double.MAX_VALUE)) continue;
                best.put(k, ng); parent.put(k, nk); at.put(k, new int[] {x, z, feet}); if (unk) unknown.add(key(x, z));
                open.add(new N(x, z, feet, ng, ng + h(x, z, tx, tz)));
            }
        }
        boolean partial = goal == Long.MIN_VALUE;
        if (atDy >= 0) return partial ? null : new Surface(List.of(), false, goalG); // (a length asked for, not legs)
        long end = partial ? closest : goal;
        boolean edge = false; for (Long k = end; k != null && !edge; k = parent.get(k)) { int[] q = at.get(k); edge = unknown.contains(key(q[0], q[1])); }
        com.claudemod.ClaudeMod.LOGGER.info("[Claude route] land plan {} -> {}: {} columns looked at, {} ms{}", from.toShortString(), to.toShortString(), expanded, (System.nanoTime() - t0) / 1_000_000, !partial ? "" : edge ? " (to the loaded land's edge)" : " (no way through found: as near as it gets)");
        if (under != null && !partial) com.claudemod.ClaudeMod.LOGGER.info("[Claude route] land plan to {}: the near way reaches it {} below it at {} (a pit or a drop beside it): round by its height instead, {} blocks of route against {} (Route.surface)", to.toShortString(), ty - underFeet, under.toShortString(), (int) goalG, (int) underG);
        else if (under != null) { int[] q = at.get(end); com.claudemod.ClaudeMod.LOGGER.info("[Claude route] land plan to {}: its columns are reached only {} below it at {} (a pit beside it) and no way round to its height within {} blocks: as near as it gets, {}, the climb his walker's (Route.surface)", to.toShortString(), ty - underFeet, under.toShortString(), (int) (underG * 1.6 + 24), q[0] + ", " + q[2] + ", " + q[1]); }
        if (partial && closestH > h(from.getX(), from.getZ(), tx, tz) - 12) return null; // nowhere much nearer: no plan
        List<long[]> path = new ArrayList<>(); // x, z, feet
        for (Long k = end; k != null; k = parent.get(k)) { int[] q = at.get(k); path.add(new long[] {q[0], q[1], q[2]}); }
        java.util.Collections.reverse(path);
        // the plan ends where the loaded land does: the rest is planned again from there
        int cut = path.size();
        for (int i = 0; i < path.size(); i++) if (unknown.contains(key((int) path.get(i)[0], (int) path.get(i)[1]))) { cut = i; partial = true; break; }
        List<BlockPos> legs = new ArrayList<>();
        int sinceLeg = 0; boolean wasWater = col(level, cache, from.getX(), from.getZ(), from.getY()).water;
        for (int i = 1; i < cut; i++) {
            long[] p = path.get(i); boolean water = col(level, cache, (int) p[0], (int) p[1], (int) p[2]).water;
            if (p[2] - path.get(i - 1)[2] >= 2) { // a step to cut: a leg ends at its foot and on its top, the climb a short move of its own
                long[] q = path.get(i - 1); BlockPos foot = new BlockPos((int) q[0], (int) q[2], (int) q[1]);
                if (i > 1 && (legs.isEmpty() || !legs.get(legs.size() - 1).equals(foot))) legs.add(foot);
                legs.add(new BlockPos((int) p[0], (int) p[2], (int) p[1])); sinceLeg = 0; wasWater = water; continue;
            }
            sinceLeg++;
            if (sinceLeg >= LEG || water != wasWater || i == cut - 1) { legs.add(new BlockPos((int) p[0], (int) p[2], (int) p[1])); sinceLeg = 0; }
            wasWater = water;
        }
        if (legs.isEmpty()) return null;
        com.claudemod.entity.life.PitGuard.planned(g, level, path.subList(0, cut)); // (a hole by his door this way skirts: noted, the second time closed)
        return new Surface(legs, partial, goalG);
    }
    /** The nearest ring round the spot (1 = its 3x3, up to 4) with a column he can stand in within 2 of its height; -1: none within 4. */
    private static int standRing(ServerLevel level, Map<Long, Col> cache, int tx, int tz, int ty) {
        for (int r = 1; r <= 4; r++) for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            if (Math.max(Math.abs(dx), Math.abs(dz)) != r && r > 1) continue;
            Col c = col(level, cache, tx + dx, tz + dz, ty); if (c.feet != BAD && c.feet != UNKNOWN && Math.abs(c.feet - ty) <= 2) return r;
        }
        return -1;
    }
    /** One of the spot's 3x3 columns stands within 2 of its height (somewhere to stand at it): then the plan ends at that height. */
    private static boolean standsNear(ServerLevel level, Map<Long, Col> cache, int tx, int tz, int ty) {
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) { Col c = col(level, cache, tx + dx, tz + dz, ty); if (c.feet != BAD && c.feet != UNKNOWN && Math.abs(c.feet - ty) <= 2) return true; }
        return false;
    }
    private static long node(int x, int z, int feet) { return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | ((feet + 2048) & 0xFFF); }
    private static double h(int x, int z, int tx, int tz) { double dx = Math.abs(x - tx), dz = Math.abs(z - tz); return Math.max(dx, dz) + 0.414 * Math.min(dx, dz); }
}
