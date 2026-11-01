package com.claudemod.entity.nav;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

import com.claudemod.entity.ClaudeEntity;
import com.claudemod.entity.life.Task;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The way a player gets somewhere: walking, jumping, dropping, swimming, climbing, a door opened, a block put down, and,
 * when the job allows it, a block dug, are all moves with a cost, and the cheapest string of them is the route (A* over
 * where his feet stand). Two modes: NO_DIG (every ordinary walk: nothing dug, blocks placed if he has them) and MAY_DIG
 * (the digging jobs, his own mine, a hole he is in, a walk the plan keeps sending him on). Walls in both: bedrock, their
 * blocks, stations, lava, blocks holding water back, gravel over nothing, his mine's shell and his house (etiquette:
 * never a hole through his own build; the door and the rows are the cheap way). Water is dear, his own tunnels cheap, a
 * cell where a move failed lately dearer. The result is his ordinary GoTo / Break / Place / Pillar / UseDoor tasks.
 */
public final class ActionPath {
    private ActionPath() {}

    public enum Mode { NO_DIG, MAY_DIG, MAY_REOPEN, MAY_CUT_SHELL, MAY_CUT_NOOK } // MAY_CUT_NOOK: as MAY_DIG, and his nook's shell may be cut, dearly (a walk with no way at all else); the caller patches each cut behind him (ClaudeLife.nookCutsBack) // MAY_CUT_SHELL: as MAY_REOPEN, and his mine's virgin shell may be cut, dearly (the goal needs what's behind it: the cast's lava); the caller owes each cut back // MAY_REOPEN: as MAY_DIG, and his own patches in his mine's shell may come out (a drop behind one: OwnEvents.reopenRoute puts them back); other digs dearer

    /** A cell of his mine's shell holding his own patch block (cobblestone / cobbled deepslate: what he patches with; the virgin shell is stone or deepslate). */
    public static boolean ownPatch(ClaudeEntity g, ServerLevel level, BlockPos p) {
        if (!com.claudemod.director.ClaudeLife.bpMode(g) || g.blueprint == null || g.blueprint.isEmpty() || !g.blueprint.isShell(p)) return false;
        BlockState s = level.getBlockState(p);
        return s.is(net.minecraft.world.level.block.Blocks.COBBLESTONE) || s.is(net.minecraft.world.level.block.Blocks.COBBLED_DEEPSLATE);
    }
    /** For MAY_CUT_SHELL: a cell of his mine's shell that may be cut and put back: solid, never ore, a station, his walkway, a landing or a notch. */
    public static boolean cuttableShell(ClaudeEntity g, ServerLevel level, BlockPos p) {
        if (!com.claudemod.director.ClaudeLife.bpMode(g) || g.blueprint == null || g.blueprint.isEmpty() || !g.blueprint.isShell(p) || g.blueprint.isWalk(p) || g.landingCells.contains(p) || g.notches.contains(p)) return false;
        BlockState s = level.getBlockState(p);
        return !s.getCollisionShape(level, p).isEmpty() && level.getFluidState(p).isEmpty() && !com.claudemod.entity.life.MineBlueprint.ore(s) && !s.hasBlockEntity() && !s.is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE);
    }
    /** For MAY_REOPEN: 1 his own patch, 2 the shell cell right under or over one (the way into the hole behind a head-high patch), 0 neither (never ore, his walkway or a station). */
    public static int reopenable(ClaudeEntity g, ServerLevel level, BlockPos p) {
        if (ownPatch(g, level, p)) return 1;
        if (!com.claudemod.director.ClaudeLife.bpMode(g) || g.blueprint == null || g.blueprint.isEmpty() || !g.blueprint.isShell(p) || g.blueprint.isWalk(p)) return 0;
        BlockState s = level.getBlockState(p);
        if (com.claudemod.entity.life.MineBlueprint.ore(s) || s.hasBlockEntity() || s.is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE) || s.getCollisionShape(level, p).isEmpty()) return 0;
        return ownPatch(g, level, p.above()) || ownPatch(g, level, p.below()) || openShell(g, level, p.above()) || openShell(g, level, p.below()) ? 2 : 0;
    }
    /** A hole in his mine's shell: a shell cell with nothing in it (an ore hole left open while the vein or a drop is still behind it). */
    static boolean openShell(ClaudeEntity g, ServerLevel level, BlockPos q) {
        return g.blueprint != null && g.blueprint.isShell(q) && level.getBlockState(q).getCollisionShape(level, q).isEmpty() && level.getFluidState(q).isEmpty();
    }

    public sealed interface Act {
        record Walk(BlockPos feet) implements Act {}
        record Dig(BlockPos pos) implements Act {}
        record Put(BlockPos pos) implements Act {}
        record Door(BlockPos pos) implements Act {}
        record Leap(BlockPos from, BlockPos land, int kind, int down, float hp, double cost) implements Act {} // a drop (4+ down) or a jump over a gap (Leaps works it out, Task.Leap does it)
    }

    private record Node(BlockPos feet, double g, double f, Node from, List<Act> acts, int puts, float hurt) {} // hurt: the HP the drops on the way here took

    public record Plan(List<Act> acts, int digs, int puts, int walks) {}

    private static java.util.Set<BlockPos> CLOSED = java.util.Set.of(); // (planClosed: cells taken as solid rock for one plan)
    /** MAY_DIG with {@code closed} taken as solid rock, never dug (is there a way round a gap with the gap shut). */
    public static Plan planClosed(ClaudeEntity g, ServerLevel level, BlockPos from, BlockPos to, double within, int budget, java.util.Set<BlockPos> closed) {
        CLOSED = closed; try { return plan(g, level, from, to, within, true, budget, Mode.MAY_DIG, null, 0); } finally { CLOSED = java.util.Set.of(); }
    }
    /** The old entry: digging allowed, no memory of failed cells. */
    public static Plan plan(ClaudeEntity g, ServerLevel level, BlockPos from, BlockPos to, double within, boolean canPlace, int budget) {
        return plan(g, level, from, to, within, canPlace, budget, Mode.MAY_DIG, null, 0);
    }

    /** The cheapest route from his feet to within {@code within} of {@code to}, or null if none within the search budget. */
    public static Plan plan(ClaudeEntity g, ServerLevel level, BlockPos from, BlockPos to, double within, boolean canPlace, int budget, Mode mode, Map<BlockPos, Integer> failedAt, int now) {
        return plan(g, level, from, to, within, canPlace, budget, mode, failedAt, now, List.of());
    }

    /** As above, keeping clear of {@code avoid} (hostile mobs): no move ends within 3 of one, and the cells within 7 cost more the closer they are. */
    public static Plan plan(ClaudeEntity g, ServerLevel level, BlockPos from, BlockPos to, double within, boolean canPlace, int budget, Mode mode, Map<BlockPos, Integer> failedAt, int now, List<net.minecraft.world.phys.Vec3> avoid) {
        return plan(g, level, from, to, within, canPlace, budget, mode, failedAt, now, avoid, 1.0);
    }

    /** As above, the heuristic weighted by w (> 1: a greedy A* for ways that are mostly digging, where a dug block costs several times the walk-based heuristic; the way found is near the cheapest, in far fewer nodes). */
    public static Plan plan(ClaudeEntity g, ServerLevel level, BlockPos from, BlockPos to, double within, boolean canPlace, int budget, Mode mode, Map<BlockPos, Integer> failedAt, int now, List<net.minecraft.world.phys.Vec3> avoid, double w) {
        Costs c = new Costs(g, level, canPlace, mode, failedAt, now); c.avoid = avoid;
        PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
        Map<BlockPos, Double> best = new HashMap<>();
        open.add(new Node(from.immutable(), 0, w * h(from, to), null, List.of(), 0, 0f));
        best.put(from.immutable(), 0.0);
        int expanded = 0;
        while (!open.isEmpty() && expanded++ < budget) {
            Node n = open.poll();
            double dd = Math.sqrt(n.feet.distSqr(to));
            if (dd <= within) { coverNote(c, from, to); leapNote(c, from, to, n); return unwind(n); }
            c.hurtNow = n.hurt;
            for (Move m : c.moves(n.feet)) {
                int np = n.puts; float nh = n.hurt; for (Act a : m.acts) { if (a instanceof Act.Put) np++; else if (a instanceof Act.Leap lp) nh += lp.hp(); }
                if (np > c.maxPuts) continue; // (no more blocks put down than he has: the third put of a bridge with two dirt left him at the lip of a mineshaft)
                double ng = n.g + m.cost;
                Double was = best.get(m.feet);
                if (was != null && was <= ng) continue;
                best.put(m.feet, ng);
                open.add(new Node(m.feet, ng, ng + w * h(m.feet, to), n, m.acts, np, nh));
            }
        }
        coverNote(c, from, to); leapNote(c, from, to, null);
        return null; // (a partial route to the closest spot is not what a player does: he'd rather pick another way)
    }

    private static void coverNote(Costs c, BlockPos from, BlockPos to) {
        if (c.coverLeft > 0) com.claudemod.ClaudeMod.LOGGER.info("[Claude route] {} -> {}: his cover's cells left alone ({} looks at them refused): never dug through (ActionPath.plan)", from.toShortString(), to.toShortString(), c.coverLeft);
    }

    private static final Map<java.util.UUID, Integer> refusedSaid = new HashMap<>();
    /** Each drop and jump the way takes, with its cost; the structure blocks it digs; and the drops and jumps turned down, with why (no way at all: always; else once per 10 s). Never a silent path. */
    private static void leapNote(Costs c, BlockPos from, BlockPos to, Node n) {
        int sd = 0; StringBuilder sdAt = new StringBuilder();
        for (Node k = n; k != null; k = k.from) for (Act a : k.acts) {
            if (a instanceof Act.Leap l) com.claudemod.ClaudeMod.LOGGER.info("[Claude route] {} -> {}: {} off {} onto {} ({}{} HP; he's at {}, keeps {}), cost {} (ActionPath.plan)", from.toShortString(), to.toShortString(), Leaps.kindName(l.kind()), l.from().toShortString(), l.land().toShortString(), l.kind() == Leaps.DROP ? l.down() + " down, " : "", Leaps.fmt(l.hp()), Leaps.fmt(c.hp), (int) c.margin, String.format("%.1f", l.cost()));
            else if (a instanceof Act.Dig dg && c.structure(dg.pos())) { sd++; sdAt.append(' ').append(c.level.getBlockState(dg.pos()).getBlock().getName().getString()).append(" at ").append(dg.pos().toShortString()); }
        }
        if (sd > 0) com.claudemod.ClaudeMod.LOGGER.info("[Claude route] {} -> {}: through {} structure block(s) ({}): no way round in the search; each goes back once he's past (ActionPath.plan)", from.toShortString(), to.toShortString(), sd, sdAt.toString().trim());
        if (c.refused.isEmpty()) return;
        Integer last = refusedSaid.get(c.g.getUUID());
        if (n != null && last != null && c.g.tickCount - last < 200 && last <= c.g.tickCount) return;
        refusedSaid.put(c.g.getUUID(), c.g.tickCount);
        StringBuilder sb = new StringBuilder(); int i = 0; for (var e : c.refused.entrySet()) { if (i++ >= 4) break; sb.append(i > 1 ? "; " : "").append(e.getKey()).append(": ").append(e.getValue()); }
        com.claudemod.ClaudeMod.LOGGER.info("[Claude route] {} -> {}: {} drop(s)/jump(s) turned down{}: {} (ActionPath.plan)", from.toShortString(), to.toShortString(), c.refused.size(), n == null ? ", and no way at all" : ", a way without them taken", sb);
        if (n == null) { var e = c.refused.entrySet().iterator().next(); Leaps.refusedNote(c.g, e.getKey() + ": " + e.getValue()); }
    }

    /** A structure's own block, kept intact (the user, 17:08: "generally you wanna keep the nether fortress intact"): nether bricks and their fence / stairs / slabs / walls, polished blackstone and its bricks, gilded blackstone, anywhere; inside a fortress, bastion, village, temple, outpost, igloo, mansion or ruin piece on record, that structure's own blocks (Structures.signOf; a bastion's plain blackstone too). */
    public static boolean structureBlock(ServerLevel level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        if (s.isAir() || !s.getFluidState().isEmpty() && s.getCollisionShape(level, p).isEmpty()) return false;
        String b = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
        if (b.contains("nether_brick") || b.contains("polished_blackstone") || b.equals("gilded_blackstone")) return true;
        for (var pc : com.claudemod.director.Structures.piecesNear(level, p, 0)) {
            if (!pc.box().isInside(p)) continue;
            String k = com.claudemod.director.Structures.key(pc.id());
            if (k == null || !STRUCT_KEYS.contains(k)) continue;
            if (com.claudemod.director.Structures.signOf(pc.id(), s) || k.equals("bastion_remnant") && s.is(net.minecraft.world.level.block.Blocks.BLACKSTONE)) return true;
        }
        return false;
    }
    static final java.util.Set<String> STRUCT_KEYS = java.util.Set.of("fortress", "bastion_remnant", "village", "desert_pyramid", "jungle_pyramid", "pillager_outpost", "igloo", "mansion", "ocean_ruin", "trail_ruins", "end_city", "stronghold");
    /** Structure cells a planned way digs (no way round): the dig guard lets these through for 5 minutes; tasks() puts each back. */
    private static final Map<BlockPos, Long> STRUCT_OK = new HashMap<>();
    public static boolean structureDigOk(ServerLevel level, BlockPos p) { Long t = STRUCT_OK.get(p); return t != null && level.getGameTime() - t < 20 * 300; }

    private static double h(BlockPos a, BlockPos b) {
        double dx = Math.abs(a.getX() - b.getX()), dy = Math.abs(a.getY() - b.getY()), dz = Math.abs(a.getZ() - b.getZ());
        return Math.max(dx, dz) + Math.min(dx, dz) * 0.2 + dy * 1.2;
    }

    private static Plan unwind(Node n) {
        List<Act> acts = new ArrayList<>();
        for (Node k = n; k != null; k = k.from) { List<Act> rev = new ArrayList<>(k.acts); java.util.Collections.reverse(rev); acts.addAll(rev); }
        java.util.Collections.reverse(acts);
        int d = 0, p = 0, w = 0;
        for (Act a : acts) { if (a instanceof Act.Dig) d++; else if (a instanceof Act.Put) p++; else if (a instanceof Act.Walk) w++; }
        return new Plan(acts, d, p, w);
    }

    /** The plan as his tasks: walks run together into a GoTo every six cells and up to each dig, placement or door (the vanilla path handles open ground, a waypoint keeps it on the planned way). */
    public static List<Task> tasks(Plan plan, BlockPos from, Item scaffold, BlockPos dest, double within) { return tasks(plan, from, scaffold, dest, within, null); }

    /**
     * With {@code level}: the user's rule for blocks put down out in the world (sky over the cell, not his mine): dirt or grass
     * may stay (a step up a steep hill), anything else goes on the take-down list so it is broken back up once he is past.
     */
    public static List<Task> tasks(Plan plan, BlockPos from, Item scaffold, BlockPos dest, double within, ServerLevel level) {
        boolean dirt = scaffold == net.minecraft.world.item.Items.DIRT || scaffold == net.minecraft.world.item.Items.GRASS_BLOCK || scaffold == net.minecraft.world.item.Items.COARSE_DIRT || scaffold == net.minecraft.world.item.Items.ROOTED_DIRT;
        List<Task> out = new ArrayList<>(); List<BlockPos> takeBack = new ArrayList<>(); boolean tookBack = false; List<Object[]> restore = new ArrayList<>(); // restore: structure blocks dug (pos, state), put back once he's past
        BlockPos lastWalk = null, cur = from; int run = 0;
        for (Act a : plan.acts()) {
            if (a instanceof Act.Walk w) {
                lastWalk = w.feet(); cur = w.feet();
                if (!restore.isEmpty()) { final BlockPos at0 = cur; if (restore.stream().allMatch(o -> { BlockPos q = (BlockPos) o[0]; return !q.equals(at0) && !q.equals(at0.above()) && Math.abs(q.getX() - at0.getX()) + Math.abs(q.getZ() - at0.getZ()) >= 2; })) { // past the structure blocks he had to dig: each goes back as it was
                    out.add(new Task.GoTo(lastWalk, 0.9)); lastWalk = null; run = 0;
                    for (Object[] o : restore) { BlockState st0 = (BlockState) o[1]; out.add(new Task.Place((BlockPos) o[0], st0.getBlock().asItem(), null, st0)); }
                    com.claudemod.ClaudeMod.LOGGER.info("[Claude route] {} structure block(s) dug for the way ({}): put back from {} once past (ActionPath.tasks)", restore.size(), restore.stream().map(o -> ((BlockPos) o[0]).toShortString()).toList(), at0.toShortString());
                    restore.clear(); continue; } }
                final BlockPos at = cur; List<BlockPos> off = new ArrayList<>();
                if (!takeBack.isEmpty() && takeBack.stream().noneMatch(q -> at.equals(q) || at.equals(q.above())) && takeBack.stream().anyMatch(q -> at.distSqr(q) <= 3 * 3)) { net.minecraft.world.phys.Vec3 eye = net.minecraft.world.phys.Vec3.atBottomCenterOf(at).add(0, 1.62, 0); for (BlockPos q : takeBack) if (eye.distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(q)) <= 4.0) off.add(q); } // (in reach from the ledge only: the rest of a tall tower stays the take-down chore's; going back down for it undid the climb)
                if (!off.isEmpty()) { // stepped off the tower that got him up, still right by it (on the ledge): back out now, top first, while it's in view below him
                    out.add(new Task.GoTo(lastWalk, 0.9)); lastWalk = null; run = 0; // (if that ends with him still on the tower's edge, the Break refuses to cut it from under him: it stays on the take-down list)
                    off.sort((q1, q2) -> q2.getY() - q1.getY()); // top first, so the rest stay in reach
                    for (BlockPos q : off) out.add(new Task.Break(q)); // (left on the take-down list till it's really gone: a Break refused under his feet stays the chore's)
                    takeBack.removeAll(off); tookBack = true;
                    continue;
                }
                if (++run >= 6) { out.add(new Task.GoTo(lastWalk, 0.9)); lastWalk = null; run = 0; }
                continue;
            }
            if (lastWalk != null) { out.add(new Task.GoTo(lastWalk, 0.9)); lastWalk = null; run = 0; }
            if (a instanceof Act.Leap l) { out.add(new Task.Leap(l.from(), l.land(), l.kind(), l.down(), l.hp())); cur = l.land(); continue; }
            if (a instanceof Act.Dig d) { out.add(new Task.Break(d.pos())); if (level != null && structureBlock(level, d.pos())) { restore.add(new Object[] {d.pos().immutable(), level.getBlockState(d.pos())}); STRUCT_OK.put(d.pos().immutable(), level.getGameTime()); } }
            else if (a instanceof Act.Door d) out.add(new Task.UseDoor(d.pos(), true));
            else if (a instanceof Act.Put p) {
                boolean outside = level == null || level.canSeeSky(p.pos().above());
                if (p.pos().equals(cur)) out.add(new Task.Pillar(1)); // the block goes under his own feet: that's a tower step (jump and place)
                else out.add(new Task.Place(p.pos(), scaffold));
                if (outside && !dirt) { com.claudemod.director.ClaudeLife.bridgeBlocks().add(p.pos().immutable()); if (p.pos().equals(cur) || p.pos().getY() >= cur.getY()) takeBack.add(p.pos().immutable()); } // (a tower step to climb by comes back the moment he's off it; a bridge waits till he's across: the daylight chore's) // out in the world only dirt may stay (a step up a hill): anything else is broken back up once he is past; in his mine a put is a patch
            }
        }
        if (lastWalk != null) { out.add(new Task.GoTo(lastWalk, 0.9)); lastWalk = null; }
        if (!restore.isEmpty()) { for (Object[] o : restore) { BlockState st0 = (BlockState) o[1]; out.add(new Task.Place((BlockPos) o[0], st0.getBlock().asItem(), null, st0)); } com.claudemod.ClaudeMod.LOGGER.info("[Claude route] {} structure block(s) dug for the way ({}): put back at the way's end (ActionPath.tasks)", restore.size(), restore.stream().map(o -> ((BlockPos) o[0]).toShortString()).toList()); }
        // (no pickup after: the block drops back down where he climbed from, and going back down for one cobble undid the climb, a loop; what's left on the list, a block still under him at the end, is the daylight chore's, as before)
        out.add(new Task.GoTo(dest, within));
        return out;
    }

    private record Move(BlockPos feet, double cost, List<Act> acts) {}

    /** What each move costs here, with what he has and what he is allowed. */
    private static final class Costs {
        final int maxPuts; // the blocks he has to put down
        final ClaudeEntity g; final ServerLevel level; final boolean canPlace; final Mode mode; final Map<BlockPos, Integer> failedAt; final int now;
        final net.minecraft.server.level.ServerPlayer owner;
        final boolean bp, house;
        final java.util.Set<BlockPos> reserved = new java.util.HashSet<>(); // cells another job of his fills (a replant's stump, a patch): never a block of the walk's put down in one (05:26 a step went on the stump the sapling was for)
        List<net.minecraft.world.phys.Vec3> avoid = List.of();
        float hurtNow; // the HP the drops on the way to the node being expanded took (no regen counted)
        final float hp, margin; // his health now; the HP he keeps after any drop (Leaps.margin)
        final java.util.Map<String, String> refused = new java.util.LinkedHashMap<>(); // drops / jumps turned down, and why
        void refuse(String what, String why) { if (refused.size() < 40) refused.putIfAbsent(what, why); }
        final java.util.Map<BlockPos, Leaps.Fall> falls = new java.util.HashMap<>();
        Leaps.Fall fallAt(BlockPos col) { return falls.computeIfAbsent(col.immutable(), k -> Leaps.fall(g, level, k, k.getY(), 0)); }
        final java.util.Map<BlockPos, Boolean> structMemo = new java.util.HashMap<>();
        boolean structure(BlockPos p) { return structMemo.computeIfAbsent(p.immutable(), k -> structureBlock(level, k) && !com.claudemod.entity.life.TempLedger.built(g, k)); } // (bricks he put down himself aside)
        final java.util.Map<Long, Boolean> sight = new java.util.HashMap<>();
        /** From the lip, looking over it: the landing in plain view (Perception.clear). */
        boolean seesLanding(BlockPos lip, Direction d, BlockPos land) {
            long k = lip.asLong() * 31 + land.asLong() * 7 + d.ordinal();
            return sight.computeIfAbsent(k, kk -> Leaps.seesOver(level, lip, lip.relative(d), land));
        }
        /** What losing hp costs as a move: 3 a heart-half, and steeply past 4 (a player keeps his health for what comes next). */
        static double hpCost(float hp) { return hp <= 0 ? 0 : 1 + hp * 3 + (hp > 4 ? (hp - 4) * (hp - 4) * 1.5 : 0); }
        /** -1: too close to a threat to stand there; else what being near them adds. */
        double avoidCost(BlockPos t) {
            double add = 0; net.minecraft.world.phys.Vec3 c = net.minecraft.world.phys.Vec3.atBottomCenterOf(t);
            for (var a : avoid) { double d = Math.sqrt(Math.pow(c.x - a.x, 2) + Math.pow(c.z - a.z, 2)) + Math.abs(c.y - a.y) * 0.5; if (d < 3) return -1; if (d < 7) add += (7 - d) * 2.5; }
            return add;
        }
        Costs(ClaudeEntity g, ServerLevel level, boolean canPlace, Mode mode, Map<BlockPos, Integer> failedAt, int now) {
            this.g = g; this.level = level; this.canPlace = canPlace; this.mode = mode; this.failedAt = failedAt; this.now = now;
            this.hp = Leaps.health(g); this.margin = Leaps.margin(g);
            this.maxPuts = !canPlace ? 0 : g.inventory.count(s -> s.is(net.minecraft.world.item.Items.DIRT) || s.is(net.minecraft.world.item.Items.GRASS_BLOCK) || s.is(net.minecraft.world.item.Items.COBBLESTONE)
                    || s.is(net.minecraft.world.item.Items.COBBLED_DEEPSLATE) || s.is(net.minecraft.world.item.Items.ANDESITE) || s.is(net.minecraft.world.item.Items.DIORITE) || s.is(net.minecraft.world.item.Items.GRANITE)
                    || s.is(net.minecraft.world.item.Items.TUFF) || s.is(net.minecraft.world.item.Items.DEEPSLATE) || s.is(net.minecraft.world.item.Items.STONE) || s.is(net.minecraft.world.item.Items.CALCITE)
                    || s.is(net.minecraft.world.item.Items.NETHERRACK) || s.is(net.minecraft.world.item.Items.BLACKSTONE)); // (what scaffoldBlock picks from: netherrack and blackstone too, or a Nether way with only those on him planned no block put down)
            this.owner = g.getTarget() instanceof net.minecraft.server.level.ServerPlayer p ? p : null;
            this.bp = com.claudemod.director.ClaudeLife.bpMode(g) && g.blueprint != null && !g.blueprint.isEmpty();
            this.house = com.claudemod.director.ClaudeLife.houseMode(g) && g.house != null;
            for (Task t0 : g.tasks) { if (t0 instanceof Task.Place pl && pl.item().builtInRegistryHolder().is(net.minecraft.tags.ItemTags.SAPLINGS)) reserved.add(pl.pos()); else if (t0 instanceof Task.Replant rp) reserved.add(rp.pos()); } // (a replant's cell; a tree's base while it's being chopped is the chop's own, and a queued block's cell may be the very block the walk puts down: the chop's towers went short when those were refused)
            reserved.addAll(com.claudemod.director.ClaudeLife.replantDue());
        }

        boolean door(BlockPos p) { BlockState s = level.getBlockState(p); return (s.getBlock() instanceof DoorBlock && !s.is(net.minecraft.world.level.block.Blocks.IRON_DOOR)) || s.getBlock() instanceof FenceGateBlock; }
        boolean doorOpen(BlockPos p) { BlockState s = level.getBlockState(p); return s.getBlock() instanceof DoorBlock ? s.getValue(DoorBlock.OPEN) : s.getBlock() instanceof FenceGateBlock && s.getValue(FenceGateBlock.OPEN); }
        boolean climbable(BlockPos p) { return level.getBlockState(p).is(BlockTags.CLIMBABLE); }
        final java.util.Set<BlockPos> closed = CLOSED;
        boolean passable(BlockPos p) { // he can be in it: air, water, anything with no collision (grass, torches), a ladder or vine, an open door
            if (closed.contains(p)) return false;
            BlockState s = level.getBlockState(p);
            if (level.getFluidState(p).is(net.minecraft.tags.FluidTags.LAVA)) return false;
            return s.getCollisionShape(level, p).isEmpty() || climbable(p) || (door(p) && doorOpen(p));
        }
        boolean water(BlockPos p) { return level.getFluidState(p).is(net.minecraft.tags.FluidTags.WATER); }
        boolean solid(BlockPos p) { return closed.contains(p) || !level.getBlockState(p).getCollisionShape(level, p).isEmpty(); }
        final java.util.Map<BlockPos, Boolean> lavaBodyMemo = new java.util.HashMap<>(); // (a pool's cells, once per plan)
        boolean lavaBy(BlockPos p) {
            for (Direction d : Direction.values()) { BlockPos n0 = p.relative(d); if (level.getFluidState(n0).is(net.minecraft.tags.FluidTags.LAVA) && lavaBodyMemo.computeIfAbsent(n0.immutable(), k -> com.claudemod.entity.life.Perception.knowsLavaBody(g, level, k))) return true; } // (lava he knows of, the pool he saw included, as doBreak asks) // (lava he knows of: what's in the rock is met as it comes)
            return false;
        }
        int coverLeft; // cells of his cover the search wouldn't dig
        /** His own build: never dug through (the rows of his mine and his house's door are the way). */
        final java.util.Set<BlockPos> straySaid = new java.util.HashSet<>(); // (each said once a plan)
        final java.util.Set<BlockPos> intactSaid = new java.util.HashSet<>(); // (a structure's blocks kept: said once a plan)
        /** A plain block of clutter in a cell his nook keeps open (ClaudeLife.nookStray): digging it is the nook's own plan, so a way may go through it. */
        boolean stray(BlockPos p) {
            if (!com.claudemod.director.ClaudeLife.nookStray(g, level, p)) return false;
            if (straySaid.add(p.immutable())) com.claudemod.ClaudeMod.LOGGER.info("[Claude route] {} at {} sits in a cell his nook keeps open (its pocket): clutter, not his build: a way through it, dug (ActionPath.hisBuild)", level.getBlockState(p).getBlock().getName().getString(), p.toShortString());
            return true;
        }
        boolean hisBuild(BlockPos p) {
            if (com.claudemod.director.ClaudeLife.nookShell(g, p)) return mode != Mode.MAY_CUT_NOOK; // his nook's walls and roof: never a way through (12:21 a walk to his junk chest cut its roof from the cave above, five digs to save the walk round); only with no way at all, dearly, patched behind him
            if (stray(p)) return false; // (before the nook's and the bed's walls below: those cells refused his own pillar in the pocket, and from the ledge every way into it was "no way", live 14:53-15:01)
            if (bp && g.blueprint.isShell(p) && !g.landingCells.contains(p) && !g.notches.contains(p) && !((mode == Mode.MAY_REOPEN || mode == Mode.MAY_CUT_SHELL) && reopenable(g, level, p) > 0) && !(mode == Mode.MAY_CUT_SHELL && cuttableShell(g, level, p))) return true; // (his own patch, or the cell under/over it, reopening: out and back) // (the cut out of his tunnel over to a pool is the obsidian work's: through his wall there, as the user did)
            if (house && g.house.isStructure(p)) return true;
            if (com.claudemod.entity.life.TempLedger.built(g, p) && !(bp && g.blueprint.isShell(p))) return true; // a block a build system of his put there (his bedroom's wall: 05:30 the walk to his bed dug the room he had just walled); the shell keeps its own rule, its reopen included
            if (com.claudemod.director.ClaudeLife.inHisNook(g, p)) return true;
            for (var nk : com.claudemod.director.ClaudeLife.allNooks(g)) { BlockPos bh = nk.bedHead(), bf = nk.bedFoot(); if (bh != null && Math.max(Math.abs(p.getX() - bh.getX()), Math.max(Math.abs(p.getY() - bh.getY()), Math.abs(p.getZ() - bh.getZ()))) <= 3 || bf != null && Math.max(Math.abs(p.getX() - bf.getX()), Math.max(Math.abs(p.getY() - bf.getY()), Math.abs(p.getZ() - bf.getZ()))) <= 3) return true; } // (his bedroom's walls, 3 round his nook bed: -25,-57,-228 is past the nook's own cells) // his base nook (bedroom, chests, furnaces): the walk to the lava west of it dug through his bedroom wall, his bedroom walled it again, twice in a minute (live 13:09)
            if (com.claudemod.entity.life.PitGuard.isCap(g, p)) return true; // (the mouth of the hole by his door he closed, and the fill under it: a walk dug through it, 2026-10-30)
            if (g.farm != null && g.farm.touches(p) && !level.getBlockState(p).is(BlockTags.DIRT) && !level.getBlockState(p).is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK)) return true;
            return level.getBlockState(p).is(BlockTags.BEDS);
        }
        boolean besideHisBuild(BlockPos p) {
            for (Direction d : Direction.values()) { BlockPos q = p.relative(d); if ((bp && g.blueprint.isShell(q)) || (house && g.house.isStructure(q))) return true; }
            return false;
        }
        /** Seconds to dig it, or -1 when it stays (not allowed in this mode, bedrock, their block, a station, his build, a hazard's wall, a block holding water back). */
        double digSeconds(BlockPos p) {
            if (closed.contains(p)) return -1;
            BlockState s = level.getBlockState(p);
            if (level.getFluidState(p).is(net.minecraft.tags.FluidTags.LAVA)) return -1; // lava has no collision: it read as a free 0 s "dig" for the cell he steps into, his head's cell and the cells he drops through (the Nether shore deaths)
            if (s.getCollisionShape(level, p).isEmpty()) return 0;
            if (g.coverCell(p)) { coverLeft++; return -1; } // the cover he's making or in: never a way through it (live 04:42 a route back into his box dug out its west wall)
            if (mode == Mode.NO_DIG) return -1;
            if (s.getDestroySpeed(level, p) < 0 || s.hasBlockEntity()) return -1;
            if (s.is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE) && !com.claudemod.director.ClaudeLife.isTempStation(g, p) && !com.claudemod.director.ClaudeLife.stationPickup(p)) return -1; // a table is a station with no block entity: the dig guard refuses it, so a route never plans one (the planner dug it, the guard said no, the route came back)
            if (s.is(net.minecraft.tags.BlockTags.LOGS) && !(p.getX() == g.getBlockX() && p.getZ() == g.getBlockZ() && p.getY() < g.getBlockY() && level.getBlockState(g.blockPosition().below()).is(net.minecraft.tags.BlockTags.LOGS))) return -1; // a tree is felled whole or left standing: never a log cut out of it for a way through (the trunk he's standing on is the exception: digging down it is taking that tree down, from the top)
            if (owner != null && com.claudemod.director.ClaudeLife.protectedBlock(g, owner, p)) return -1;
            if (hisBuild(p)) return -1;
            if (com.claudemod.director.ClaudeLife.hazard(g, level, p, 1) != null || lavaBy(p)) return -1;
            { String ik = com.claudemod.director.Structures.intactKind(s); if (ik != null && !com.claudemod.entity.life.TempLedger.built(g, p)) { if (intactSaid.isEmpty()) com.claudemod.ClaudeMod.LOGGER.info("[Claude route] {} at {} is {}'s: kept intact, the way goes round or through its doorways (ActionPath.digSeconds)", s.getBlock().getName().getString(), p.toShortString(), ik); intactSaid.add(p.immutable()); } } // (the structure-intact rule: his own placed bricks aside; a structure block is very dear below, never a wall: shut in with no way round, one is dug and put back, ActionPath.tasks)
            if (!g.isInWater()) for (Direction d : Direction.values()) if (level.getFluidState(p.relative(d)).isSource() && (com.claudemod.entity.life.Perception.knowsWater(g, p.relative(d)) || com.claudemod.entity.life.Perception.knowsLava(g, p.relative(d)))) return -1; // (holding water back)
            if (s.getBlock() instanceof net.minecraft.world.level.block.FallingBlock && !solid(p.below())) return -1; // gravel over nothing: it comes down on him
            boolean right = !s.requiresCorrectToolForDrops();
            float speed = 1f; // bare hands
            for (int i = 0; i < g.inventory.items.getContainerSize(); i++) { var it = g.inventory.items.getItem(i); if (it.isEmpty()) continue; speed = Math.max(speed, it.getDestroySpeed(s)); if (!right && it.isCorrectToolForDrops(s)) right = true; }
            if (!right) return -1; // the user: stone with bare hands is no way (7.5 s a block, nothing drops): round it, dirt, or a block put down
            float hard = s.getDestroySpeed(level, p);
            float prog = hard <= 0 ? 1f : speed / hard / 30f; // (the best tool on him: the dig equips it; what's in his hand at planning time was the old price)
            if (prog <= 0) return -1;
            double sec = Math.min(10, 1.0 / (prog * 20)) + (structure(p) ? 40 : 0); // a structure's own block: very dear (round it, its doorway, a bridge); dug only with no way round, and put back (tasks)
            if (mode == Mode.MAY_REOPEN || mode == Mode.MAY_CUT_SHELL) { int r = reopenable(g, level, p); if (r == 1) return sec + 1.5; if (r == 2) return sec + 2.5; if (mode == Mode.MAY_CUT_SHELL && bp && g.blueprint.isShell(p)) return sec + 9; sec += 3; } // (a cut through his virgin shell: the dearest dig, only where nothing cheaper gets there) // reopening: his own patch at a modest price, the shell cell under/over it a bit more, virgin rock always dearer (04:25 a 30-block cut round a patch)
            if (besideHisBuild(p)) sec += 4; // a one-wide cut along his mine or house: dear, not forbidden
            if (mode == Mode.MAY_CUT_NOOK && com.claudemod.director.ClaudeLife.nookShell(g, p)) sec += 9; // (his nook's shell: the dearest dig, only where nothing else gets there)
            return sec;
        }
        boolean mine(BlockPos p) { return com.claudemod.director.ClaudeLife.isMineCell(g, p); }
        boolean missedHere(BlockPos land) { if (failedAt == null) return false; Integer t = failedAt.get(land); return t != null && now - t < 20 * 120; }
        double failPenalty(BlockPos p) { if (failedAt == null) return 0; Integer t = failedAt.get(p); return t != null && now - t < 20 * 60 ? 6 : 0; }
        /** Standing in or on it hurts: not a cell he moves to. */
        boolean hurts(BlockPos feet) { return hurts(feet, false); }
        /** blockGoesUnder: a block is put into the cell under (lava there is replaced by it: no hurt from it). */
        boolean hurts(BlockPos feet, boolean blockGoesUnder) {
            BlockState in = level.getBlockState(feet), on = level.getBlockState(feet.below());
            return in.is(net.minecraft.world.level.block.Blocks.CACTUS) || in.is(net.minecraft.world.level.block.Blocks.SWEET_BERRY_BUSH) || in.is(BlockTags.FIRE)
                    || in.is(net.minecraft.world.level.block.Blocks.WITHER_ROSE) || in.is(net.minecraft.world.level.block.Blocks.POWDER_SNOW) || in.is(net.minecraft.world.level.block.Blocks.POINTED_DRIPSTONE)
                    || on.is(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK) || on.is(BlockTags.CAMPFIRES) || on.is(net.minecraft.world.level.block.Blocks.CACTUS)
                    || level.getFluidState(feet).is(net.minecraft.tags.FluidTags.LAVA) || !blockGoesUnder && level.getFluidState(feet.below()).is(net.minecraft.tags.FluidTags.LAVA);
        }

        /** Every move from here: 4 ways flat / one up / down to 3, a door opened on the way, up or down a ladder, straight up a block (placed under him), each with the digs and placements it takes. */
        List<Move> moves(BlockPos feet) {
            List<Move> out = new ArrayList<>();
            boolean swimming = water(feet);
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos ahead = feet.relative(d);
                // flat, or one up, or down 1..3: the cell he ends up standing in
                for (int dy = 1; dy >= -3; dy--) {
                    BlockPos t = ahead.above(dy);
                    List<Act> acts = new ArrayList<>();
                    double cost = dy == 0 ? 1.0 : dy == 1 ? 1.6 : 1.0 + (-dy) * 0.8;
                    boolean ok = true;
                    // the cells he passes through: ahead at his level and head, plus (going up) the head room above him; (going down) the column he drops through
                    List<BlockPos> through = new ArrayList<>();
                    if (dy == 1) { through.add(feet.above(2)); through.add(ahead.above()); through.add(ahead.above(2)); }
                    else if (dy == 0) { through.add(ahead); through.add(ahead.above()); }
                    else { through.add(ahead); through.add(ahead.above()); for (int k = -1; k >= dy; k--) through.add(ahead.above(k)); }
                    for (BlockPos q : through) {
                        if (swimming && dy == 0 && q.equals(ahead.above()) && water(ahead) && !passable(q)) { cost += 2.0; continue; } // a flooded 1-high gap: swum through flat, like a player
                        if (door(q) && !doorOpen(q) && dy == 0) { cost += 1.5; acts.add(new Act.Door(q.immutable())); continue; } // a closed door or gate: opened on the way
                        if (passable(q)) { if (water(q)) cost += 1.5; continue; }
                        double sec = digSeconds(q);
                        if (sec < 0) { ok = false; break; }
                        cost += 1.5 + sec * 2; acts.add(new Act.Dig(q.immutable()));
                    }
                    boolean lavaFloor = dy == 0 && canPlace && level.getFluidState(t.below()).is(net.minecraft.tags.FluidTags.LAVA) && !solid(t.below()) && !reserved.contains(t.below()); // a gap over lava: a block put into the lava is the way across (a player bridging a lava lake), never a jump
                    if (!ok || hurts(t, lavaFloor)) continue; // (magma underfoot, a cactus, fire: a player goes round)
                    // something under his feet there: a solid block, water to swim in, a ladder; else a block put down (a bridge, a step), if he can
                    BlockPos under = t.below();
                    if (!solid(under) && !water(t) && !climbable(t)) {
                        if (dy != 0 || !canPlace) continue; // (a block put down is a flat bridge or a tower step, never a step down into a drop: the body can't set one from up there)
                        if (!lavaFloor && (solid(under.below()) || water(under.below()))) continue; // the user: a block put down for movement is for real terrain (a gap, a drop), never a raised line over flat ground or a causeway over water he can swim
                        boolean against = dy == 0 || solid(under.relative(d.getOpposite())) || solid(under.below()); // flat: against the block he stands on (his own last bridge block counts, the plan knows it is there); a drop: something in the world
                        if (!against) continue;
                        if (reserved.contains(under)) continue; // (another job's cell)
                        cost += 3 + (lavaFloor ? 8 : 0); /* (a block into the lava: dear, a way over land wins; 1 extra made a lava-sea bridge the route of choice) */ acts.add(new Act.Put(under.immutable()));
                    }
                    if (dy < 0 && water(t)) cost += 1; // a drop into water: fine, a bit slower
                    if (mine(t)) cost *= 0.7; // his own tunnels: the way he knows
                    if (stray(feet.below())) cost += 3; // (stepping off his own pillar in his nook's pocket: dearer than digging it down under him, so the way in takes the whole tower down, never leaves its foot in front of his chest)
                    cost += failPenalty(t);
                    { double av = avoidCost(t); if (av < 0) continue; cost += av; } // (a crowd of hostiles: round them, like a player)
                    acts.add(new Act.Walk(t.immutable()));
                    out.add(new Move(t.immutable(), cost, acts));
                }
            }
            // off an edge, 4 or more down (the user, 16:42: "he should be able to calculate fall damage and assess risk"): vanilla's fall worked out tick by tick (Leaps.fall:
            // water, a cobweb, a ladder or vine take all of it, hay and a bed some), its HP the move's price, taken only while what's left keeps his margin; never onto lava,
            // fire or magma, never a landing he can't see from the lip (16:31 he stood on his bridge 15 over them: "no way", 40 walks ended at once)
            if (!swimming && solid(feet.below())) for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos ahead = feet.relative(d);
                if (!passable(ahead) || !passable(ahead.above()) || water(ahead) || solid(ahead.below()) || water(ahead.below()) || climbable(ahead) || climbable(ahead.below())) continue;
                Leaps.Fall f = fallAt(ahead);
                if (f.down() <= 3 && f.ok()) continue; // (up to three down: the ordinary step down above)
                String what = "drop off " + feet.toShortString() + " " + d.getName();
                String why = f.why();
                if (why == null && level.getBlockState(f.land()).is(net.minecraft.world.level.block.Blocks.POWDER_SNOW)) why = "powder snow where he'd land (no damage, but it freezes him and holds him)";
                if (why == null && !water(f.land()) && hurts(f.land())) why = "the landing hurts (" + level.getBlockState(f.land()).getBlock().getName().getString() + " on " + level.getBlockState(f.land().below()).getBlock().getName().getString() + ")";
                if (why == null && !seesLanding(feet, d, f.land())) why = "he can't see the landing from the lip";
                float left = hp - hurtNow - f.hp();
                if (why == null && left < margin) why = (hurtNow > 0 ? Leaps.fmt(hp - hurtNow) + " HP by then" : "he's at " + Leaps.fmt(hp)) + ": " + (int) Math.max(0, left) + " after, under the " + (int) margin + " he keeps";
                if (why != null) { refuse(what, Leaps.cost(f) + ": " + why); continue; }
                double cost = 1.2 + f.down() * 0.05 + hpCost(f.hp()) + f.extraSec() * 2 + (water(f.land()) ? 1 : 0) + failPenalty(f.land());
                double av = avoidCost(f.land()); if (av < 0) continue; cost += av;
                List<Act> acts = new ArrayList<>(); acts.add(new Act.Leap(feet.immutable(), f.land().immutable(), Leaps.DROP, f.down(), f.hp(), cost));
                out.add(new Move(f.land().immutable(), cost, acts));
            }
            // a jump over a gap (the user, 16:48: "he should get better and jumping across platforms and parkour. and use it in his pathing"): vanilla's motion (Leaps.reach, jumpKind):
            // a walk-jump over 1, a sprint-jump over 2 (up one), 3 and 4 (down); priced by the chance of a miss times what's under the gap; a miss onto lava, no bottom or
            // past his margin: no jump, a bridge (or the way round) instead
            if (!swimming && solid(feet.below()) && !climbable(feet) && passable(feet.above(2))) {
                boolean canSprint = g.getFoodData().getFoodLevel() > 6;
                for (Direction d : Direction.Plane.HORIZONTAL) {
                    float missHp = 0; int missDepth = 0; String missWhy = null;
                    for (int i = 1; i <= 4; i++) {
                        BlockPos gc = feet.relative(d, i);
                        if (!passable(gc) || !passable(gc.above()) || !passable(gc.above(2)) || water(gc) || climbable(gc)) break;
                        if (solid(gc.below())) break; // ground right there: a walk, not a gap (water under it is a gap a jump saves swimming)
                        Leaps.Fall mf = fallAt(gc); // a miss: down this cell
                        if (!mf.ok()) missWhy = mf.why();
                        else if (level.getBlockState(mf.land()).is(net.minecraft.world.level.block.Blocks.POWDER_SNOW)) missWhy = "powder snow";
                        else { missHp = Math.max(missHp, mf.hp()); missDepth = Math.max(missDepth, mf.down()); }
                        BlockPos col = feet.relative(d, i + 1);
                        for (int dy = 1; dy >= -3; dy--) {
                            int kind = Leaps.jumpKind(i, dy, canSprint);
                            if (kind < 0) continue;
                            BlockPos land = col.above(dy);
                            if (!passable(land) || !passable(land.above()) || water(land.above())) continue;
                            if (dy == 1 && !passable(land.above(2))) continue; // the arc's top over a landing one up
                            boolean clear = true; for (int k = dy + 1; k <= 1; k++) if (!passable(col.above(k))) { clear = false; break; } // the cells he comes down through at the landing
                            if (!clear) continue;
                            if (!(solid(land.below()) || water(land) || climbable(land))) continue;
                            if (!water(land) && hurts(land)) continue;
                            String what = Leaps.kindName(kind) + " off " + feet.toShortString() + " " + d.getName() + " over " + i + (dy > 0 ? " up 1" : dy < 0 ? " down " + -dy : "");
                            if (missWhy != null) { refuse(what, "a miss falls onto " + missWhy + ": a bridge instead"); continue; }
                            if (hp - hurtNow - missHp < margin) { refuse(what, "a miss is " + missDepth + " down, " + Leaps.fmt(missHp) + " HP (he's at " + Leaps.fmt(hp - hurtNow) + ", keeps " + (int) margin + "): a bridge instead"); continue; }
                            if (!seesLanding(feet, d, land)) { refuse(what, "he can't see the landing"); continue; }
                            if (missedHere(land)) { refuse(what, "he missed that landing in the last 2 min: the way round"); continue; } /* live 00:13: the same sprint-jump over 2 up 1 missed 5 times in 10 s; a +6 penalty was still cheaper than the hill */
                            float jhp = water(land) || climbable(land) ? 0 : Leaps.damage(g, level, 1.2522f - dy, level.getBlockState(land.below())); // (the arc's top counts: coming down 2 is 1 HP, 3 is 2)
                            if (hp - hurtNow - jhp < margin) { refuse(what, Leaps.fmt(jhp) + " HP coming down, under the " + (int) margin + " he keeps"); continue; }
                            double cost = (i + 1) * 0.8 + 0.6 + (kind == Leaps.SPRINT_JUMP ? 0.4 : 0) + hpCost(jhp) + Leaps.missRisk(i, dy) * (hpCost(missHp) * 4 + missDepth * 2 + 4) + failPenalty(land);
                            double av = avoidCost(land); if (av < 0) continue; cost += av;
                            List<Act> acts = new ArrayList<>(); acts.add(new Act.Leap(feet.immutable(), land.immutable(), kind, Math.max(0, -dy), jhp, cost));
                            out.add(new Move(land.immutable(), cost, acts));
                        }
                    }
                }
            }
            // straight down through his own block under him (his pillar left in his nook's pocket): dug from the top, onto what's under it (up to 2 down), as a player takes his tower down to get back in
            if (!swimming && stray(feet.below())) {
                BlockPos below = feet.below(); double sec = digSeconds(below);
                if (sec >= 0) {
                    BlockPos land = below; int k = 0;
                    while (k < 2 && passable(land.below()) && !water(land.below())) { land = land.below(); k++; }
                    if ((solid(land.below()) || water(land)) && !hurts(land)) { List<Act> acts = new ArrayList<>(); acts.add(new Act.Dig(below.immutable())); acts.add(new Act.Walk(land.immutable())); out.add(new Move(land.immutable(), 2.5 + sec * 2 + k * 0.8 + failPenalty(land), acts)); }
                }
            }
            // a ladder or vine: straight up or down it
            if (climbable(feet)) {
                for (int dy : new int[] {1, -1}) {
                    BlockPos t = feet.above(dy);
                    if (!passable(t) || (dy == 1 && !passable(t.above())) || hurts(t)) continue;
                    if (!climbable(t) && dy == 1 && !solid(t.below())) continue;
                    List<Act> acts = new ArrayList<>(); acts.add(new Act.Walk(t.immutable()));
                    out.add(new Move(t.immutable(), 1.5 + failPenalty(t), acts));
                }
            }
            // straight up: a block under him (towering), with head room dug if need be; never on water (a jump there doesn't clear his feet)
            if (canPlace && !swimming) {
                BlockPos up = feet.above();
                List<Act> acts = new ArrayList<>();
                double cost = 4;
                BlockPos head = feet.above(2);
                boolean ok = true;
                if (!passable(head)) { double sec = digSeconds(head); if (sec < 0) ok = false; else { cost += 1.5 + sec * 2; acts.add(new Act.Dig(head.immutable())); } }
                if (ok && !passable(up)) ok = false;
                if (reserved.contains(feet)) ok = false; // (another job's cell: the stump a sapling is for)
                if (ok) { acts.add(new Act.Put(feet.immutable())); acts.add(new Act.Walk(up.immutable())); out.add(new Move(up.immutable(), cost + failPenalty(up), acts)); }
            }
            return out;
        }
    }
}
