package com.claudemod.entity.life;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.claudemod.ClaudeMod;
import com.claudemod.entity.ClaudeEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * The highway skill (build_highway): a Nether highway from one of his portals to a place he has scouted, to the user's spec
 * (nether-bridge-spec: one L from the portal, BridgeBlueprint), cobbled deepslate only. First pass, as the user builds them:
 * "2 wide without rails first, then build the railing and widen when needed". A tunnel row is dug 2 wide and 3 high with its
 * holes (air, lava) walled and roofed; lava beside or across a row is sealed from the safe side before the row is opened ("path
 * around lava falls, blocking them off if it's in the way of the highway").
 *
 * Every choice is priced (Decide.choose, numbers on Weights "highway.*"): which portal it leaves from, and where the blocks come
 * from once he runs out (his chests, a strip mine at deepslate level, both). Each call builds a few rows, then queues itself
 * again; where it stands is read off the world (the first row not finished), so a death, a restart or a trip for blocks picks up
 * where the highway ends. Each leg goes on his portal map as a highway segment as it grows. The fortress join (one opening in its
 * railing) and the rail / widening passes come later; it stops at the first fortress block.
 */
public final class Highway {
    private Highway() {}
    static final Item BLOCK = Items.COBBLED_DEEPSLATE;
    private static double w(String n, double d) { return Weights.w(n, d); }

    static final class State { BridgeBlueprint plan; int portalId = -1; BlockPos dest; boolean said; }
    private static Map<UUID, State> states; // (static: fields added live start null)
    static State state(ClaudeEntity g) { if (states == null) states = new HashMap<>(); return states.computeIfAbsent(g.getUUID(), k -> new State()); }
    public static void reset(ClaudeEntity g) { if (states != null) states.remove(g.getUUID()); }
    public static BridgeBlueprint plan(ClaudeEntity g) { return state(g).plan; }

    /** Where it goes: "x y z" in the words, else "fortress" (the one he scouted, on his atlas). */
    static BlockPos destination(ClaudeEntity g, ServerLevel level, String words) {
        var m = java.util.regex.Pattern.compile("(-?\\d+)[ ,]+(-?\\d+)[ ,]+(-?\\d+)").matcher(words == null ? "" : words);
        if (m.find()) return new BlockPos(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
        BlockPos f = FortressScout.found(g); return f != null ? f : Atlas.find(g, level, "fortress");
    }

    public static String skill(ClaudeEntity g, ServerPlayer player, ServerLevel level, com.claudemod.entity.life.Skills.Step st) {
        State s = state(g);
        String words = st.target() == null ? "" : st.target().replaceFirst("^@", "");
        if (level.dimension() != net.minecraft.world.level.Level.NETHER) { // (his highways are in the Nether: through the portal first)
            g.skillQueue.addFirst(new Skills.Step("build_highway", words, st.count(), st.where())); g.skillQueue.addFirst(new Skills.Step("enter_portal", "", 1));
            return "the highway's in the Nether: through the portal first";
        }
        if (!st.target().startsWith("@") || s.plan == null) { String r = makePlan(g, level, words, s); if (r != null) return r; }
        BridgeBlueprint b = s.plan; int lo = b.lo(), hi = b.hi();
        // where it stands: the first row not finished (read off the world; a row out in unloaded ground isn't, it's ahead)
        int next = next(level, b); boolean apron = false;
        if (next == 0 && b.rows.size() > 1 && standable(level, b.rows.get(0), lo, hi)) { // the apron, the row in front of the portal: he steps out onto it (no reach through a portal, vanilla) and swaps it last, from row 2
            int n1 = nextFrom(level, b, 1); if (n1 < b.rows.size()) next = n1; else apron = true; }
        record(g, b, next);
        if (next >= b.rows.size()) return finish(g, player, b, "all " + b.rows.size() + " rows laid");
        var row0 = b.rows.get(next);
        if (fortress(level, row0, lo, hi)) return finish(g, player, b, "it meets the fortress at row " + (next + 1) + " (" + row0.centre().toShortString() + "); the join through its railing is the next pass");
        BlockPos standOn = apron ? b.rows.get(1).centre().above() : next == 0 ? b.portal : b.rows.get(next - 1).centre().above();
        if (g.blockPosition().distSqr(standOn) > 2 * 2) { // (back from a trip or a fight: along the highway to its end first)
            g.tasks.add(new Task.GoTo(standOn, 0.8)); g.skillQueue.addFirst(new Skills.Step("build_highway", "@" + words, st.count(), st.where()));
            return "to the highway's end at " + standOn.toShortString() + " (row " + next + " of " + b.rows.size() + ")";
        }
        int have = g.inventory.count(x -> x.is(BLOCK)), batch = (int) Math.max(1, w("highway.batch_rows", 3)), used = 0, rows = 0; int[] cnt = new int[4];
        List<Task> out = new ArrayList<>();
        for (int i = next; i < (apron ? 1 : b.rows.size()) && rows < batch; i++) {
            var r = b.rows.get(i);
            if (!level.hasChunkAt(r.centre()) || fortress(level, r, lo, hi)) break;
            List<Task> one = new ArrayList<>(); int[] c1 = new int[4]; int need = rowTasks(level, r, lo, hi, one, c1);
            if (used + need > have) break;
            out.addAll(one); used += need; rows++; for (int k = 0; k < 4; k++) cnt[k] += c1[k];
        }
        if (rows == 0) return blocks(g, player, level, s, next, have, words, st);
        g.tasks.addAll(out); g.skillQueue.addFirst(new Skills.Step("build_highway", "@" + words, st.count(), st.where()));
        String r = "highway rows " + (next + 1) + "-" + (next + rows) + " of " + b.rows.size() + ": " + cnt[0] + " lava sealed, " + cnt[1] + " dug, " + cnt[2] + " floor laid, " + cnt[3] + " tunnel holes walled (" + used + " of " + have + " cobbled deepslate)";
        ClaudeMod.LOGGER.info("[Claude highway] {} (Highway.skill)", r);
        g.noteDoing("building the highway to " + b.dest.toShortString() + ", row " + (next + 1) + " of " + b.rows.size());
        return r;
    }

    /** The plan: the portal it leaves from (priced: the rows to build and his walk to it), 2 wide with no rails, to the destination. Null when made; else why not. */
    static String makePlan(ClaudeEntity g, ServerLevel level, String words, State s) {
        BlockPos dest = destination(g, level, words);
        if (dest == null) return "failed: no fortress on my map yet, and no x y z given: find_fortress first";
        List<Decide.Option> opts = new ArrayList<>(); Map<String, PortalMap.Portal> byName = new HashMap<>();
        double rowS = w("highway.row_s", 6), sprint = Decide.sprint();
        for (var p : PortalMap.net(g).portals) {
            if (!PortalMap.NETHER.equals(p.dim)) continue;
            String n = "#" + p.id; byName.put(n, p);
            if (!"lit".equals(p.state)) { opts.add(Decide.Option.out(n, p.state)); continue; }
            int rowsN = Math.abs(dest.getX() - p.at.getX()) + Math.abs(dest.getZ() - p.at.getZ()); double walk = Math.sqrt(g.blockPosition().distSqr(p.at)) * PortalMap.NETHER_OFF_ROAD / sprint;
            opts.add(Decide.Option.of(n, rowsN * rowS + walk, rowsN + " rows x " + rowS + " s + " + Math.round(walk) + " s to it"));
        }
        var pick = Decide.choose(g, "highway.portal", "to " + dest.toShortString(), true, opts);
        if (pick == null) return "failed: no lit Nether portal of mine to start a highway from";
        var portal = byName.get(pick.name());
        var axis = BridgeBlueprint.facingAxis(level, portal.at);
        if (axis == null) return "failed: portal #" + portal.id + " at " + portal.where() + " isn't standing (no portal block there)";
        int width = (int) Math.max(1, w("highway.first_width", 2));
        var b = BridgeBlueprint.plan(level, portal.at, axis, dest, width, false);
        if (b == null) return "failed: " + dest.toShortString() + " is straight off the side of portal #" + portal.id + ": no leg out of its face";
        s.plan = b; s.portalId = portal.id; s.dest = dest.immutable();
        ClaudeMod.LOGGER.info("[Claude highway] planned from portal #{}: {} (Highway.makePlan)", portal.id, b);
        return null;
    }

    static boolean lava(ServerLevel level, BlockPos p) { return level.getFluidState(p).is(FluidTags.LAVA); }
    static boolean solid(ServerLevel level, BlockPos p) { var st = level.getBlockState(p); return !st.canBeReplaced() && !st.getCollisionShape(level, p).isEmpty(); }
    static boolean fortress(ServerLevel level, BridgeBlueprint.Row r, int lo, int hi) {
        Direction right = r.dir().getClockWise();
        for (int o = lo; o <= hi; o++) for (int y = 0; y <= 3; y++) { BlockPos q = r.centre().relative(right, o).above(y); if (net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(q).getBlock()).getPath().contains("nether_brick")) return true; }
        return false;
    }
    /** The first row not finished (a row out in unloaded ground isn't: it's ahead); rows.size() when all are. */
    public static int next(ServerLevel level, BridgeBlueprint b) { return nextFrom(level, b, 0); }
    static int nextFrom(ServerLevel level, BridgeBlueprint b, int from) { int n = from; for (; n < b.rows.size(); n++) { var r = b.rows.get(n); if (!level.hasChunkAt(r.centre()) || !done(level, r, b.lo(), b.hi())) break; } return n; }
    /** He can stand on the row as it is: its floor solid and its walkway open. */
    static boolean standable(ServerLevel level, BridgeBlueprint.Row r, int lo, int hi) {
        Direction right = r.dir().getClockWise();
        for (int o = lo; o <= hi; o++) { BlockPos f = r.centre().relative(right, o); if (!solid(level, f)) return false; for (int y = 1; y <= 2; y++) if (solid(level, f.above(y)) || lava(level, f.above(y))) return false; }
        return true;
    }
    /** A row is finished: its floor all cobbled deepslate, its walkway open and dry. */
    static boolean done(ServerLevel level, BridgeBlueprint.Row r, int lo, int hi) {
        Direction right = r.dir().getClockWise();
        for (int o = lo; o <= hi; o++) { BlockPos f = r.centre().relative(right, o);
            if (!level.getBlockState(f).is(net.minecraft.world.level.block.Blocks.COBBLED_DEEPSLATE)) return false;
            for (int y = 1; y <= 3; y++) if (solid(level, f.above(y)) || lava(level, f.above(y))) return false; }
        return true;
    }

    /**
     * One row's tasks, in the order a player does it from the row behind: lava round it sealed first (each against rock or a seal
     * before it), the walkway dug (lava in it plugged then dug), the floor laid (rock in it dug out first), a tunnel row's holes
     * walled and roofed, then onto it. Returns the blocks it places; c: {sealed, dug, floor, holes}.
     */
    static int rowTasks(ServerLevel level, BridgeBlueprint.Row r, int lo, int hi, List<Task> out, int[] c) {
        Direction right = r.dir().getClockWise(); int placed = 0;
        int[] cols = new int[hi - lo + 1]; { int k = 0; cols[k++] = 0; for (int o = 1; k < cols.length; o++) { if (o <= hi && k < cols.length) cols[k++] = o; if (-o >= lo && k < cols.length) cols[k++] = -o; } } // (from his line outward)
        Set<BlockPos> own = new HashSet<>(); List<BlockPos> floor = new ArrayList<>(), walk = new ArrayList<>();
        for (int o : cols) { BlockPos f = r.centre().relative(right, o).immutable(); floor.add(f); own.add(f); }
        for (int y = 1; y <= 3; y++) for (int o : cols) { BlockPos q = r.centre().relative(right, o).above(y).immutable(); walk.add(q); own.add(q); }
        Set<BlockPos> seal = new java.util.LinkedHashSet<>();
        for (BlockPos q : own) for (Direction d : Direction.values()) { BlockPos n = q.relative(d); if (!own.contains(n) && d != r.dir().getOpposite() && lava(level, n)) seal.add(n.immutable()); } // (not behind: that's the row he stands on, built)
        for (BlockPos q : seal) { out.add(new Task.Place(q, BLOCK)); placed++; c[0]++; }
        for (BlockPos q : walk) { if (lava(level, q)) { out.add(new Task.Place(q, BLOCK)); out.add(new Task.Break(q)); placed++; c[1]++; } else if (solid(level, q)) { out.add(new Task.Break(q)); c[1]++; } }
        for (BlockPos f : floor) { var st = level.getBlockState(f); if (st.is(net.minecraft.world.level.block.Blocks.COBBLED_DEEPSLATE)) continue; if (solid(level, f)) { out.add(new Task.Break(f)); c[1]++; } out.add(new Task.Place(f, BLOCK)); placed++; c[2]++; }
        boolean tunnel = false; // (rock beside or over the walkway: a tunnel row, its shell closed where the rock has holes)
        List<BlockPos> shell = new ArrayList<>();
        for (int y = 1; y <= 3; y++) for (int o : new int[] {lo - 1, hi + 1}) shell.add(r.centre().relative(right, o).above(y).immutable());
        for (int o : cols) shell.add(r.centre().relative(right, o).above(4).immutable());
        for (BlockPos q : walk) if (solid(level, q)) tunnel = true;
        for (BlockPos q : shell) if (solid(level, q)) tunnel = true;
        if (tunnel) for (BlockPos q : shell) if (!solid(level, q) && !seal.contains(q)) { out.add(new Task.Place(q, BLOCK)); placed++; c[3]++; }
        out.add(new Task.GoTo(r.centre().above(), 0.6));
        return placed;
    }

    /** Each leg's built stretch on his portal map, as a highway segment (extended as it grows). */
    static void record(ClaudeEntity g, BridgeBlueprint b, int built) {
        for (int leg = 1; leg <= 2; leg++) { BridgeBlueprint.Row a = null, z = null; for (int i = 0; i < built; i++) { var r = b.rows.get(i); if (r.leg() != leg || r.stair()) { if (a != null && z != null && r.leg() == leg) break; continue; } if (a == null) a = r; z = r; }
            if (a != null && z != null && a != z && a.centre().getY() == z.centre().getY()) PortalMap.addSegment(g, PortalMap.NETHER, a.centre(), z.centre(), a.dir().getAxis(), b.width); }
    }

    static String finish(ClaudeEntity g, ServerPlayer player, BridgeBlueprint b, String how) {
        String r = "done: the highway from portal #" + state(g).portalId + " to " + b.dest.toShortString() + " is laid, " + b.width + " wide, cobbled deepslate: " + how;
        ClaudeMod.LOGGER.info("[Claude highway] {} (Highway.finish)", r);
        if (player != null) com.claudemod.director.ClaudeChat.say(player, "the highway to " + b.dest.toShortString() + " is down (" + b.width + " wide, no rails yet): " + how, "build_highway: done");
        return r;
    }

    /** Out of cobbled deepslate (or short of the next row): where the next lot comes from, priced; the trip queued, then back to building. */
    static String blocks(ClaudeEntity g, ServerPlayer player, ServerLevel level, State s, int next, int have, String words, Skills.Step st) {
        BridgeBlueprint b = s.plan; int left = b.rows.size() - next;
        int trip = (int) Math.max(64, Math.min(left * w("highway.blocks_per_row", 2.6), w("highway.trip_blocks", 384)));
        int inChests = 0; for (var e : ChestLedger.book(g).values()) if (e.dim() != null && e.dim().endsWith("overworld")) inChests += e.items().getOrDefault(BLOCK, 0);
        var portal = PortalMap.byId(g, s.portalId); double sprint = Decide.sprint();
        double toPortal = portal == null ? 0 : Math.sqrt(g.blockPosition().distSqr(portal.at)) / sprint, through = w("highway.portal_s", 8);
        double there = 2 * (toPortal + through), chestS = w("highway.chest_trip_s", 90), mineS = w("highway.mine_s_per_block", 1.2), mineWalk = w("highway.mine_trip_s", 120);
        List<Decide.Option> opts = new ArrayList<>();
        if (inChests >= trip) opts.add(Decide.Option.of("his chests", there + chestS, inChests + " in his chests; " + Math.round(there) + " s there and back + " + Math.round(chestS) + " s at them"));
        else opts.add(Decide.Option.out("his chests", inChests + " in them, " + trip + " wanted"));
        if (inChests > 0 && inChests < trip) opts.add(Decide.Option.of("chests, then mine the rest", there + chestS + mineWalk + (trip - inChests) * mineS, inChests + " from chests, " + (trip - inChests) + " mined at " + mineS + " s each"));
        opts.add(Decide.Option.of("strip mine it", there + mineWalk + trip * mineS, trip + " mined at deepslate level at " + mineS + " s each (ore seen on the way gets mined: diamonds for gear)"));
        opts.add(Decide.Option.out("netherrack", "the user: highways are always cobbled deepslate"));
        var pick = Decide.choose(g, "highway.blocks", have + " on him, " + left + " rows left, " + trip + " for this trip", true, opts);
        g.skillQueue.addFirst(new Skills.Step("build_highway", "@" + words, st.count(), st.where()));
        g.skillQueue.addFirst(new Skills.Step("enter_portal", "", 1));
        int fromChests = pick.name().startsWith("strip") ? 0 : Math.min(inChests, trip);
        if (trip - fromChests > 0) g.skillQueue.addFirst(new Skills.Step("collect", "cobbled_deepslate", trip - fromChests + have));
        if (fromChests > 0) g.skillQueue.addFirst(new Skills.Step("take_from_chest", "cobbled_deepslate", fromChests));
        g.skillQueue.addFirst(new Skills.Step("enter_portal", "", 1));
        String r = "out of cobbled deepslate for the highway (" + have + " on him, row " + (next + 1) + " of " + b.rows.size() + " next): " + pick.name() + " for " + trip + ", then back to it";
        ClaudeMod.LOGGER.info("[Claude highway] {} (Highway.blocks)", r);
        if (player != null) com.claudemod.director.ClaudeChat.say(player, "out of cobbled deepslate for the highway: getting " + trip + " more (" + pick.name() + "), then back to row " + (next + 1), "build_highway: blocks");
        return r;
    }
}
