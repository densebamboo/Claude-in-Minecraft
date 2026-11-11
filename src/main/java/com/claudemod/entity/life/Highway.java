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
 * "2 wide without rails first, then build the railing and widen when needed". Through rock (the user 11-07: "anytime he enters/
 * exits a rock he should place a shell frame. anywhere he needs to patch a tunnel he should place a shell, excluding lava plugs,
 * which he should plug with netherrack or the surrounding blocks. anything that doesnt need patching he can just do the floor";
 * "the shell frame should be visible on the outside of the rock and have corners"): the floor always; a full deepslate ring with
 * its corners at each tunnel mouth (the first and last rock row of a run); inside, the rock stays and only its holes get shell;
 * lava is plugged with netherrack (or a block like the rock round it), sealed from the safe side before the row is opened.
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
    // shortcut: one Claude per server, so his mine's stair height is kept here for the static row helpers; pass it in if a second bot ever builds
    static int stairH = 4;
    /** The headroom a row is cut to above its floor: a stair row his mine's stair height, a level row 3. */
    static int clear(BridgeBlueprint.Row r) { return r.stair() ? Math.max(3, stairH) : 3; }
    private static double w(String n, double d) { return Weights.w(n, d); }

    static final class State { BridgeBlueprint plan; int portalId = -1; BlockPos dest; BlockPos from; Direction.Axis fromAxis; /* (a branch: where it leaves his highway, and along what) */ boolean said; int built = -1, rows = -1, laidTo = -1, floorTo = -1; int[] stairs; } // laidTo: the furthest row he has seen laid; a row out in unloaded ground at or before it is his own work, laid (a player remembers how far his road goes) // stairs: {leg, row, steps} of the plan underway, pinned (BridgeBlueprint.plan)
    private static Map<UUID, State> states; // (static: fields added live start null)
    static State state(ClaudeEntity g) { if (states == null) states = new HashMap<>(); return states.computeIfAbsent(g.getUUID(), k -> new State()); }
    public static void reset(ClaudeEntity g) { if (states != null) states.remove(g.getUUID()); }
    /** The highway underway (portal, destination, rows laid), saved with his portal map: a restart lost it and he walked for the fortress with its deepslate as loose bridge blocks (D2D live 14:19, the user: "bug. he should be building the highway"; stage 913). */
    public static net.minecraft.nbt.CompoundTag save(ClaudeEntity g) { State s = state(g); if (s.dest == null || s.portalId < 0) return null; var t = new net.minecraft.nbt.CompoundTag(); t.putInt("portal", s.portalId); t.putLong("dest", s.dest.asLong()); t.putInt("built", s.built); t.putInt("rows", s.rows); t.putInt("laidTo", s.laidTo); t.putInt("floorTo", s.floorTo); if (s.stairs != null) t.putIntArray("stairs", s.stairs); if (s.from != null && s.fromAxis != null) { t.putLong("from", s.from.asLong()); t.putString("fromAxis", s.fromAxis.getName()); } return t; }
    public static void load(ClaudeEntity g, net.minecraft.nbt.CompoundTag t) { State s = state(g); s.plan = null; s.portalId = t.getInt("portal"); s.dest = BlockPos.of(t.getLong("dest")); s.built = t.getInt("built"); s.rows = t.getInt("rows"); s.laidTo = t.contains("laidTo") ? t.getInt("laidTo") : s.built - 1; /* (a save from before laidTo: built rows laid reach at least row built-1) */ s.stairs = t.contains("stairs") ? t.getIntArray("stairs") : null; s.floorTo = t.contains("floorTo") ? t.getInt("floorTo") : -1; s.from = t.contains("from") ? BlockPos.of(t.getLong("from")) : null; s.fromAxis = t.contains("fromAxis") ? Direction.Axis.byName(t.getString("fromAxis")) : null; ClaudeMod.LOGGER.info("[Claude highway] underway from his saved state: portal #{} to {}, {} of {} rows (Highway.load)", s.portalId, s.dest.toShortString(), s.built, s.rows); }
    /** The first trip to a scouted place in the Nether that no road of his reaches: the road is built on the way (the user 11-10: "scout out his pois, then establish a highway to them on the way to the pois from his portal"). What he says when it is; null when his road reaches it, it's near, or he has no lit portal to root it on. */
    public static String firstTrip(ClaudeEntity g, ServerLevel level, BlockPos poi) {
        if (level.dimension() != net.minecraft.world.level.Level.NETHER) return null;
        double far = Math.sqrt(g.blockPosition().distSqr(poi)); if (far < w("highway.trip_min_blocks", 64)) return null;
        boolean rooted = false;
        for (var p : PortalMap.net(g).portals) { if (!PortalMap.NETHER.equals(p.dim) || !"lit".equals(p.state)) continue; rooted = true;
            if (com.claudemod.entity.nav.NetherRoute.onTree(g, p.at, poi)) return null; } // (his road gets there: walked along it)
        if (!rooted) return null;
        State s = state(g); boolean on = s.dest != null && Math.abs(s.dest.getX() - poi.getX()) + Math.abs(s.dest.getZ() - poi.getZ()) <= 2;
        ClaudeMod.LOGGER.info("[Claude highway] to {} ({} off): no road of his reaches it{}: built on the way (Highway.firstTrip)", poi.toShortString(), (int) far, on ? ", the one underway goes there" : "");
        return on ? "the road to " + poi.toShortString() + " is underway: building it on the way there" : "no road of mine reaches " + poi.toShortString() + " yet: building it on the way there, off my highway";
    }
    /** A highway of his is underway (its block is his building stock, never junk). */
    public static boolean underway(ClaudeEntity g) { State s = state(g); return s.dest != null && s.portalId >= 0; }
    /** Rows of the highway underway whose floor is still to lay (rows short only of a rail don't count: rails wait for the way home); -1 when not known yet. */
    public static int floorLeft(ClaudeEntity g) { State s = state(g); return s.floorTo < 0 || s.rows < 0 ? -1 : Math.max(0, s.rows - 1 - s.floorTo); }
    /** For his planner: the highway he's building and that build_highway resumes it. */
    public static String describe(ClaudeEntity g) { State s = state(g); if (s.dest == null || s.portalId < 0) return "";
        return "\nHIGHWAY UNDERWAY (yours, in the Nether): from portal #" + s.portalId + " to " + s.dest.getX() + " " + s.dest.getY() + " " + s.dest.getZ() + (s.built >= 0 && s.rows > 0 ? ", " + s.built + " of " + s.rows + " rows laid" : "") + "; build_highway (no target) resumes it, and it is your way there: build it on rather than walking or bridging to that spot; it packs its own cobbled deepslate from your chests (no take_from_chest of your own for it).\n"; }
    public static BridgeBlueprint plan(ClaudeEntity g) { return state(g).plan; }
    public static void planForTest(ClaudeEntity g, BridgeBlueprint b) { state(g).plan = b; }
    /** The state whose plan this is (one Claude per server: the map holds one). */
    static State owner(BridgeBlueprint b) { if (states != null && b != null) for (State s : states.values()) if (s.plan == b) return s; return null; }
    static int laidTo(BridgeBlueprint b) { State o = owner(b); return o == null ? -1 : o.laidTo; }

    /** Where it goes: "x y z" in the words, else "fortress" (the one he scouted, on his atlas). */
    public static BlockPos destination(ClaudeEntity g, ServerLevel level, String words) {
        var m = java.util.regex.Pattern.compile("(-?\\d+)[ ,]+(-?\\d+)[ ,]+(-?\\d+)").matcher(words == null ? "" : words);
        if (m.find()) return new BlockPos(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
        BlockPos f = FortressScout.found(g); if (f == null) f = Atlas.find(g, level, "fortress");
        if (f != null && level.isLoaded(f)) { BlockPos dk = FortressScout.deck(level, f); if (dk != null) f = dk; } // (its deck, not the brick on record; stage 919)
        return f;
    }

    public static String skill(ClaudeEntity g, ServerPlayer player, ServerLevel level, com.claudemod.entity.life.Skills.Step st) {
        State s = state(g); stairH = com.claudemod.director.ClaudeLife.mineStairHeight(g);
        String words = st.target() == null ? "" : st.target().replaceFirst("^@", "");
        if (level.dimension() != net.minecraft.world.level.Level.NETHER) { // (his highways are in the Nether: through the portal first)
            var top = st.target().startsWith("@") ? null : topUpStep(g); // the one underway: his pack topped up from his chests on the way out, once (live 15:05: the planner's own 64 taken of 614, ~11 rows' worth; stage 916)
            g.skillQueue.addFirst(new Skills.Step("build_highway", "@" + words, st.count(), st.where())); g.skillQueue.addFirst(new Skills.Step("enter_portal", "", 1));
            var pack = st.target().startsWith("@") ? null : packStep(g); if (pack != null) g.skillQueue.addFirst(pack); // (the rest of the trip after his chests: mined)
            if (top != null) { g.skillQueue.addFirst(top); return "the highway's in the Nether: " + top.count() + " cobbled deepslate from my chests first (a full pack), then through the portal"; }
            if (pack != null) { int on = g.inventory.count(x -> x.is(BLOCK)); return "the highway's in the Nether: " + on + " cobbled deepslate on me, " + (pack.count() - on) + " more first (a full pack of " + pack.count() + "), then through the portal"; } // (the step's count is the trip's total, not what he has: "759 on me" with 654, live 19:53, D2D review 38)
            return "the highway's in the Nether: through the portal first";
        }
        if (s.dest != null && level.isLoaded(s.dest)) { BlockPos dk = FortressScout.deck(level, s.dest); // the fortress's deck once its ground is loaded: the height on record may be a brick seen from below (stage 919)
            if (dk != null && dk.getY() != s.dest.getY() && Math.abs(dk.getX() - s.dest.getX()) + Math.abs(dk.getZ() - s.dest.getZ()) <= 24) { ClaudeMod.LOGGER.info("[Claude highway] the destination {} is a fortress deck at {}: planned again to its height (Highway.skill)", s.dest.toShortString(), dk.toShortString()); s.dest = dk.immutable(); s.plan = null; words = ""; } }
        boolean spot = java.util.regex.Pattern.compile("-?\\d+[ ,]+-?\\d+[ ,]+-?\\d+").matcher(words).find(); // (their own coordinates: a highway there; a name like "fortress" reads the nearest record, which moves as he does)
        if (s.dest != null && !spot && !words.isBlank() && !st.target().startsWith("@")) ClaudeMod.LOGGER.info("[Claude highway] \"{}\" with a highway underway to {}: that one, not a new plan to the nearest {} on record (Highway.skill)", words, s.dest.toShortString(), words);
        if (s.dest != null && (!spot || st.target().startsWith("@"))) words = s.dest.getX() + " " + s.dest.getY() + " " + s.dest.getZ(); // (no target: the one underway, after a restart or not; the planner's plain build_highway planned a new one to the fortress on his map, 271,65,445 against the deck 310,33,402 underway: a fork at row 401, live 20:08 11-06, stage 877)
        if (!st.target().startsWith("@") || s.plan == null) { String r = makePlan(g, level, words, s); if (r != null) return r; }
        BridgeBlueprint b = s.plan; int lo = b.lo(), hi = b.hi();
        // where it stands: the first row not finished (read off the world; a row out in unloaded ground isn't, it's ahead)
        int next = next(level, b); boolean apron = false;
        if (next == 0 && b.rows.size() > 1 && standable(level, b.rows.get(0), lo, hi)) { // the apron, the row in front of the portal: he steps out onto it (no reach through a portal, vanilla) and swaps it last, from row 2
            int n1 = nextFrom(level, b, 1); if (n1 < b.rows.size()) next = n1; else apron = true; }
        { int front = next; while (front < b.rows.size() && (level.hasChunkAt(b.rows.get(front).centre()) ? floorDone(level, b.rows.get(front), lo, hi) : front <= laidTo(b))) front++; state(g).floorTo = front - 1; // frontier first: a row behind it short only of a rail waits for the way home (D2D 12:34 11-07: at the end by 223,33,402, then ~250 back for "rails on row 359 ... > 3 s for the rail", the walk not in the price)
          if (front > next && front < b.rows.size() && front - next > w("highway.rail_behind_rows", 8) && g.blockPosition().distSqr(b.rows.get(next).centre()) > 16 * 16) { ClaudeMod.LOGGER.info("[Claude highway] rails from row {} wait for the way home: the frontier is row {} ({} rows on), he is {} off the rail row (Highway.skill)", next + 1, front + 1, front - next, (int) Math.sqrt(g.blockPosition().distSqr(b.rows.get(next).centre()))); next = front; } }
        record(g, b, next);
        state(g).built = b.rows.size() - rowsLeft(level, b, 0); // (the rows laid, not the first gap: "31 of 710 rows laid" with the road laid past 529 told the planner it was stuck at the start, and sized the chest fetch from there; D2D 11-09, stage 1194)
        if (next >= b.rows.size()) return finish(g, player, b, "all " + b.rows.size() + " rows laid");
        var row0 = b.rows.get(next);
        if (fortress(level, row0, lo, hi)) { // it meets the fortress: one doorway through its wall, 3 tall across the walkway (the user: "3 tall"), and in (the user 12:37 11-07: "he hit the fortress ... all he has to do is break a wall to get in"; 07:17 it ended here with "the join is the next pass", a pass that never existed, and the way in went round the outside)
            Direction right = row0.dir().getClockWise(); List<BlockPos> wall = new ArrayList<>();
            for (int o = lo; o <= hi; o++) for (int y = 1; y <= 3; y++) { BlockPos q = row0.centre().relative(right, o).above(y); if (solid(level, q) && FortressScout.fortressBlock(level.getBlockState(q))) wall.add(q.immutable()); }
            BlockPos in = row0.centre().above().relative(row0.dir()); // (past the wall: the corridor behind it)
            if (wall.isEmpty()) { if (g.blockPosition().distSqr(in) > 1) g.tasks.add(new Task.GoTo(in, 0.8)); return finish(g, player, b, "it meets the fortress at row " + (next + 1) + " and goes into it through the doorway at " + in.toShortString()); }
            BlockPos from = next == 0 ? b.portal : b.rows.get(next - 1).centre().above();
            if (g.blockPosition().distSqr(from) > 2 * 2) g.tasks.add(new Task.GoTo(from, 0.8));
            for (BlockPos q : wall) { com.claudemod.entity.nav.ActionPath.allowStructureDig(level, q); g.tasks.add(new Task.Break(q)); }
            g.tasks.add(new Task.GoTo(in, 0.8)); g.skillQueue.addFirst(new Skills.Step("build_highway", "@" + words, st.count(), st.where()));
            String r = "the highway meets the fortress at row " + (next + 1) + ": a doorway through its wall (" + wall.size() + " brick" + (wall.size() == 1 ? "" : "s") + " at " + wall.get(0).toShortString() + "), then in";
            ClaudeMod.LOGGER.info("[Claude highway] {} (Highway.skill)", r); return r; }
        BlockPos standOn = apron ? b.rows.get(1).centre().above() : next == 0 ? b.portal : b.rows.get(next - 1).centre().above();
        if (g.blockPosition().distSqr(standOn) > 2 * 2) { // (back from a trip or a fight: along the highway to its end first)
            { int have0 = g.inventory.count(x -> x.is(BLOCK)); double far = Math.sqrt(g.blockPosition().distSqr(standOn)); // (blocks before the walk out: review 214 live 13:01 11-08, 3 on him, ~460 out to lay 2 and ~510 back)
              if (have0 < Math.min(w("highway.trip_min_blocks", 64), Math.ceil(rowsLeft(level, b, next) * w("highway.blocks_per_row", 2.6))) && far > w("highway.trip_far_blocks", 32)) { /* (enough for the rows left is enough: 47 on him for 12 rows sent him home for 64 more, ~13 min, live 09:42 11-09, stage 1278) */ ClaudeMod.LOGGER.info("[Claude highway] {} cobbled deepslate on him and the highway's end {} off at {}: blocks first, not a walk out for a row or two (Highway.skill)", have0, (int) far, standOn.toShortString()); return blocks(g, player, level, s, next, have0, words, st); } }
            if (g.attempts.skipped(Attempts.Kind.WALK, standOn, g.tickCount, 0)) { var why = g.attempts.why(Attempts.Kind.WALK, standOn); // (the walk there failed and its cause still holds: said, for the planner to decide on; live 23:10 11-06 magma cubes at the end, the same walk queued and ended at once 8 times in a second, stage 882)
                ClaudeMod.LOGGER.info("[Claude highway] the way to its end at {} failed ({}), nothing changed since: not queued again (Highway.skill)", standOn.toShortString(), why);
                return "failed: the way to the highway's end at " + standOn.toShortString() + " is blocked (" + why + "); deal with that first (row " + next + " of " + b.rows.size() + ")"; }
            g.tasks.add(new Task.GoTo(standOn, 0.8)); g.skillQueue.addFirst(new Skills.Step("build_highway", "@" + words, st.count(), st.where()));
            return "to the highway's end at " + standOn.toShortString() + " (row " + next + " of " + b.rows.size() + ")";
        }
        int have = g.inventory.count(x -> x.is(BLOCK)), batch = (int) Math.max(1, w("highway.batch_rows", 3)), used = 0, rows = 0; int[] cnt = new int[4];
        List<Task> out = new ArrayList<>();
        for (int i = next; i < (apron ? 1 : b.rows.size()) && rows < batch; i++) {
            var r = b.rows.get(i);
            if (!level.hasChunkAt(r.centre()) || fortress(level, r, lo, hi)) break;
            List<Task> one = new ArrayList<>(); int[] c1 = new int[4];
            boolean rk = rockRow(level, r, lo, hi), rkBack = i > 0 && rockRow(level, b.rows.get(i - 1), lo, hi);
            Shell sh = rk ? (i == 0 || !rkBack || i == b.rows.size() - 1 ? Shell.FRAME : Shell.PATCH) : shelled(level, b, i, lo, hi) ? Shell.PATCH : Shell.NONE; // (entering the rock: its first row framed)
            int need = 0;
            if (!rk && rkBack && !frameDone(level, b.rows.get(i - 1), lo, hi) && !shellDeep(level, b.rows.get(i - 1), lo, hi)) need += frameTasks(level, b.rows.get(i - 1), lo, hi, one, c1); // leaving it: the last rock row framed from it, before the step out
            need += rowTasks(level, r, lo, hi, one, c1, sh, plug(g));
            if (used + need > have) break;
            out.addAll(one); used += need; rows++; for (int k = 0; k < 4; k++) cnt[k] += c1[k];
        }
        if (rows == 0) return blocks(g, player, level, s, next, have, words, st);
        for (int i = next; i < next + rows; i++) { String why = null; for (int o : new int[] {lo - 1, hi + 1}) if (why == null && !solid(level, b.rows.get(i).centre().relative(b.rows.get(i).dir().getClockWise(), o).above())) why = railWhy(level, b.rows.get(i), o);
            if (why != null && !shelled(level, b, i, lo, hi)) { ClaudeMod.LOGGER.info("[Claude highway] rails on row {} at {}: {} (Highway.railWhy)", i + 1, b.rows.get(i).centre().toShortString(), why); break; } }
        for (Task k : out) if (k instanceof Task.Place pl) TempLedger.planBuild(g, pl.pos(), "Highway.skill"); // (the highway is his build: its blocks stay, never litter; live 15:25 LITTER on its floor, stage 917)
        g.tasks.addAll(out); g.skillQueue.addFirst(new Skills.Step("build_highway", "@" + words, st.count(), st.where()));
        String r = "highway rows " + (next + 1) + "-" + (next + rows) + " of " + b.rows.size() + ": " + cnt[0] + " lava sealed, " + cnt[1] + " dug, " + cnt[2] + " floor laid, " + cnt[3] + " shell, frame or rail blocks laid (" + used + " of " + have + " cobbled deepslate)";
        ClaudeMod.LOGGER.info("[Claude highway] {} (Highway.skill)", r);
        g.noteDoing("building the highway to " + b.dest.toShortString() + ", row " + (next + 1) + " of " + b.rows.size());
        return r;
    }

    /** The plan: the portal it leaves from (priced: the rows to build and his walk to it), 2 wide with no rails, to the destination. Null when made; else why not. */
    static String makePlan(ClaudeEntity g, ServerLevel level, String words, State s) {
        BlockPos dest = destination(g, level, words);
        if (dest == null) return "failed: no fortress on my map yet, and no x y z given: find_fortress first";
        if (s.from != null && s.fromAxis != null && s.dest != null && Math.abs(s.dest.getX() - dest.getX()) + Math.abs(s.dest.getZ() - dest.getZ()) <= 2) { // the branch underway goes on from where it leaves his highway
            var b = BridgeBlueprint.plan(level, s.from, s.fromAxis, dest, (int) Math.max(1, w("highway.first_width", 2)), false, s.stairs);
            if (b != null) { s.plan = b; s.dest = dest.immutable(); s.stairs = new int[] {b.stairLeg, b.stairFrom, stepsOf(b)}; ClaudeMod.LOGGER.info("[Claude highway] resuming the branch off his highway at {}: {} (Highway.makePlan)", s.from.toShortString(), b); return null; }
        }
        var going = s.from == null && s.portalId >= 0 && s.dest != null && Math.abs(s.dest.getX() - dest.getX()) + Math.abs(s.dest.getZ() - dest.getZ()) <= 2 ? PortalMap.byId(g, s.portalId) : null; // (by x z: its height may be put right at the deck)
        if (going != null && level.isLoaded(going.at)) going.state = PortalMap.stateAt(level, going.at);
        if (going != null && "lit".equals(going.state) && BridgeBlueprint.facingAxis(level, going.at) != null) { // the highway underway goes on from its own portal, not chosen again each call (live 15:05: re-chosen from #3 at row 104 to #4, a frame no longer there)
            var b = BridgeBlueprint.plan(level, going.at, BridgeBlueprint.facingAxis(level, going.at), dest, (int) Math.max(1, w("highway.first_width", 2)), false, s.stairs);
            if (b != null) { s.plan = b; s.dest = dest.immutable(); s.stairs = new int[] {b.stairLeg, b.stairFrom, stepsOf(b)}; ClaudeMod.LOGGER.info("[Claude highway] resuming from portal #{}: {} (Highway.makePlan)", going.id, b); return null; }
        }
        List<Decide.Option> opts = new ArrayList<>(); Map<String, PortalMap.Portal> byName = new HashMap<>();
        double rowS = w("highway.row_s", 6), sprint = Decide.sprint();
        for (var p : PortalMap.net(g).portals) {
            if (!PortalMap.NETHER.equals(p.dim)) continue;
            String n = "#" + p.id; byName.put(n, p);
            if (level.isLoaded(p.at) && p.at.distSqr(g.blockPosition()) <= 16 * 16) { String now = PortalMap.stateAt(level, p.at); if (!now.equals(p.state)) { ClaudeMod.LOGGER.info("[Claude portal] #{} is {} now (was {}) (Highway.makePlan)", p.id, now, p.state); p.state = now; } } // (the one he's by, as it is: a record 300 min old said unlit with him stood in it lit, D2D live 13:53; stage 909)
            if (!"lit".equals(p.state)) { opts.add(Decide.Option.out(n, p.state + ("unlit".equals(p.state) ? ": the plan reads its face off the lit portal, relight it first" : ""))); continue; } // shortcut: an unlit frame is out (live 15:05 picked #4 "to relight", its frame gone); price a relight step once enter/light runs before the plan
            double relight = 0;
            int rowsN = Math.abs(dest.getX() - p.at.getX()) + Math.abs(dest.getZ() - p.at.getZ()); double walk = PortalMap.NETHER.equals(PortalMap.dim((ServerLevel) g.level())) ? com.claudemod.entity.nav.NetherRoute.priceS(g, g.blockPosition(), p.at) : Math.sqrt(g.blockPosition().distSqr(p.at)) * com.claudemod.entity.nav.NetherRoute.offRoad(g) / sprint;
            opts.add(Decide.Option.of(n, rowsN * rowS + walk + relight, rowsN + " rows x " + rowS + " s + " + Math.round(walk) + " s to it" + (relight > 0 ? " + " + Math.round(relight) + " s to relight it" : "")));
        }
        Map<String, Object[]> branches = new HashMap<>(); boolean inNether = PortalMap.NETHER.equals(PortalMap.dim((ServerLevel) g.level()));
        for (var sg : PortalMap.net(g).segments) { // a branch off his highway where it comes nearest: one road tree from his portal, never a second line from it (the user 11-10, D2D's B)
            if (!PortalMap.NETHER.equals(sg.dim) || sg.width < 2) continue;
            Object[] bp = com.claudemod.entity.nav.NetherRoute.branchPoint(sg, dest); if (bp == null) continue; BlockPos q = (BlockPos) bp[0];
            PortalMap.Portal root = null; for (var p : PortalMap.net(g).portals) if (PortalMap.NETHER.equals(p.dim) && "lit".equals(p.state) && com.claudemod.entity.nav.NetherRoute.onTree(g, p.at, q)) { root = p; break; }
            if (root == null) continue; // (a piece of road not joined to a portal of his: no tree to branch off)
            int rowsN = Math.abs(dest.getX() - q.getX()) + Math.abs(dest.getZ() - q.getZ());
            double walk = inNether ? com.claudemod.entity.nav.NetherRoute.priceS(g, g.blockPosition(), q) : Math.sqrt(g.blockPosition().distSqr(root.at)) * com.claudemod.entity.nav.NetherRoute.offRoad(g) / sprint + Math.sqrt(root.at.distSqr(q)) / sprint; // (to the portal as the portal options price it, then along his highway at a sprint)
            String n = "branch at " + q.toShortString(); if (branches.containsKey(n)) continue; branches.put(n, new Object[] {q, bp[1], root});
            opts.add(Decide.Option.of(n, rowsN * rowS + walk, rowsN + " rows x " + rowS + " s off his highway (from portal #" + root.id + ") + " + Math.round(walk) + " s to it"));
        }
        var pick = Decide.choose(g, "highway.portal", "to " + dest.toShortString(), true, opts);
        if (pick != null && branches.containsKey(pick.name())) { Object[] br = branches.get(pick.name()); BlockPos q = (BlockPos) br[0]; Direction.Axis ax = (Direction.Axis) br[1]; var root = (PortalMap.Portal) br[2];
            boolean same = q.equals(s.from) && s.dest != null && Math.abs(s.dest.getX() - dest.getX()) + Math.abs(s.dest.getZ() - dest.getZ()) <= 2;
            var b = BridgeBlueprint.plan(level, q, ax, dest, (int) Math.max(1, w("highway.first_width", 2)), false, same ? s.stairs : null);
            if (b != null) { s.plan = b; s.portalId = root.id; s.dest = dest.immutable(); s.from = q.immutable(); s.fromAxis = ax; s.stairs = new int[] {b.stairLeg, b.stairFrom, stepsOf(b)}; if (!same) { s.laidTo = -1; s.floorTo = -1; }
                ClaudeMod.LOGGER.info("[Claude highway] planned as a branch off his highway at {} (portal #{}'s road), along {}: {} (Highway.makePlan)", q.toShortString(), root.id, ax.getName(), b); return null; }
            final String bad = pick.name(); opts.removeIf(o -> o.name().equals(bad)); }
        if (pick != null && branches.containsKey(pick.name())) pick = Decide.choose(g, "highway.portal", "to " + dest.toShortString(), true, opts);
        if (pick == null) return "failed: no lit Nether portal on my map to start a highway from (an unlit one is relit first)";
        var portal = byName.get(pick.name());
        var axis = BridgeBlueprint.facingAxis(level, portal.at);
        if (axis == null) return "failed: portal #" + portal.id + " at " + portal.where() + " isn't standing (no portal block there)";
        int width = (int) Math.max(1, w("highway.first_width", 2));
        var b = BridgeBlueprint.plan(level, portal.at, axis, dest, width, false, portal.id == s.portalId && s.dest != null && Math.abs(s.dest.getX() - dest.getX()) + Math.abs(s.dest.getZ() - dest.getZ()) <= 2 ? s.stairs : null);
        if (b == null) return "failed: " + dest.toShortString() + " is straight off the side of portal #" + portal.id + ": no leg out of its face";
        boolean same = portal.id == s.portalId && s.dest != null && Math.abs(s.dest.getX() - dest.getX()) + Math.abs(s.dest.getZ() - dest.getZ()) <= 2;
        s.plan = b; s.portalId = portal.id; s.dest = dest.immutable(); s.from = null; s.fromAxis = null; s.stairs = new int[] {b.stairLeg, b.stairFrom, stepsOf(b)}; if (!same) { s.laidTo = -1; s.floorTo = -1; } // (another highway: nothing of it laid yet; the same one planned again keeps how far it goes)
        ClaudeMod.LOGGER.info("[Claude highway] planned from portal #{}: {} (Highway.makePlan)", portal.id, b);
        return null;
    }

    /** The steps a plan's staircase makes: the destination's height against the portal's floor (what the pin is good for). */
    static int stepsOf(BridgeBlueprint b) { return Math.abs(b.dest.getY() - b.portal.getY()); }
    static boolean lava(ServerLevel level, BlockPos p) { return level.getFluidState(p).is(FluidTags.LAVA); }
    static boolean solid(ServerLevel level, BlockPos p) { var st = level.getBlockState(p); return !st.canBeReplaced() && !st.getCollisionShape(level, p).isEmpty(); }
    /** A cell of the highway underway (its cross-section: floor, walkway, rails or walls, roof) near p: the highway's to lay or dig, not another system's to fill (Loops 88: doBore filled -3,59,5 and -3,61,5, build_highway dug them out 11 s later). */
    public static boolean owns(ClaudeEntity g, BlockPos p) {
        BridgeBlueprint b = state(g).plan; if (b == null) return false;
        for (var r : b.rows) { BlockPos c = r.centre(); if (Math.abs(c.getX() - p.getX()) + Math.abs(c.getZ() - p.getZ()) > 8) continue;
            int y = p.getY() - c.getY(); if (y < 0 || y > clear(r) + 1) continue;
            Direction right = r.dir().getClockWise();
            for (int o = b.lo() - 1; o <= b.hi() + 1; o++) { BlockPos q = c.relative(right, o); if (q.getX() == p.getX() && q.getZ() == p.getZ()) return true; } }
        return false;
    }
    /** Rock, for the tunnel test: solid and not a tree's (stems, wart blocks, shroomlight, leaves). */
    static boolean rock(ServerLevel level, BlockPos p) { if (!solid(level, p)) return false; var st = level.getBlockState(p); return !(st.is(net.minecraft.tags.BlockTags.LOGS) || st.is(net.minecraft.tags.BlockTags.WART_BLOCKS) || st.is(net.minecraft.world.level.block.Blocks.SHROOMLIGHT) || st.is(net.minecraft.tags.BlockTags.LEAVES)); }
    /** A shell block left as it is: a portal frame, bedrock, anything with contents. */
    static boolean keep(ServerLevel level, BlockPos p) { var st = level.getBlockState(p); return st.is(net.minecraft.world.level.block.Blocks.OBSIDIAN) || st.is(net.minecraft.world.level.block.Blocks.CRYING_OBSIDIAN) || st.getDestroySpeed(level, p) < 0 || st.hasBlockEntity(); }
    static boolean fortress(ServerLevel level, BridgeBlueprint.Row r, int lo, int hi) {
        Direction right = r.dir().getClockWise();
        for (int o = lo; o <= hi; o++) for (int y = 0; y <= 3; y++) { BlockPos q = r.centre().relative(right, o).above(y); if (net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(q).getBlock()).getPath().contains("nether_brick")) return true; }
        return false;
    }
    /** The first row not finished (a row out in unloaded ground isn't: it's ahead); rows.size() when all are. */
    public static int next(ServerLevel level, BridgeBlueprint b) { return nextFrom(level, b, 0); }
    static int nextFrom(ServerLevel level, BridgeBlueprint b, int from) {
        // (rows go down in order: an unloaded row with a laid row past it is laid. Read as unfinished, at row 387 the unloaded row 14 sent him 370 back and there the unloaded far rows sent him out again, every minute, live 18:18-18:35 11-06, stage 872)
        int lastLaid = lastLaid(level, b, from);
        int n = from; for (; n < b.rows.size(); n++) if (open(level, b, n, lastLaid)) break; return n; }
    static int lastLaid(ServerLevel level, BridgeBlueprint b, int from) { int seen = -1; for (int i = b.rows.size() - 1; i >= from; i--) { var r = b.rows.get(i); if (level.hasChunkAt(r.centre()) && done(level, r, b.lo(), b.hi())) { seen = i; break; } }
        State o = owner(b); if (o == null) return seen; if (seen > o.laidTo) o.laidTo = seen; return o.laidTo >= from ? Math.max(seen, o.laidTo) : seen; } // (and the rows he laid that are out in unloaded ground now: live 17:14 11-09 back at his portal with 698 of 710 laid, the loaded rows ended at 415 and he went home to mine 383 deepslate for a road already built; stage 1356)
    /** Row i still to lay: not done where it's loaded; unloaded, only past the last laid row. */
    static boolean open(ServerLevel level, BridgeBlueprint b, int i, int lastLaid) { var r = b.rows.get(i); return level.hasChunkAt(r.centre()) ? !done(level, r, b.lo(), b.hi()) : i > lastLaid; }
    /** The rows still to lay from {@code from}: the gaps behind the frontier and the rows past it, not every row past the first gap (live 00:30 11-09: a gap at row 31, the road laid past 529, "680 rows left", a 576-block trip; stage 1194). */
    static int rowsLeft(ServerLevel level, BridgeBlueprint b, int from) { int last = lastLaid(level, b, from), n = 0; for (int i = from; i < b.rows.size(); i++) if (open(level, b, i, last)) n++; return n; }
    /** He can stand on the row as it is: its floor solid and its walkway open. */
    static boolean standable(ServerLevel level, BridgeBlueprint.Row r, int lo, int hi) {
        Direction right = r.dir().getClockWise();
        for (int o = lo; o <= hi; o++) { BlockPos f = r.centre().relative(right, o); if (!solid(level, f)) return false; for (int y = 1; y <= 2; y++) if (solid(level, f.above(y)) || lava(level, f.above(y))) return false; }
        return true;
    }
    /** Its floor all cobbled deepslate and its walkway open and dry (the rails aside). */
    static boolean floorDone(ServerLevel level, BridgeBlueprint.Row r, int lo, int hi) {
        Direction right = r.dir().getClockWise();
        for (int o = lo; o <= hi; o++) { BlockPos f = r.centre().relative(right, o);
            if (!level.getBlockState(f).is(net.minecraft.world.level.block.Blocks.COBBLED_DEEPSLATE)) return false;
            for (int y = 1; y <= clear(r); y++) if (solid(level, f.above(y)) || lava(level, f.above(y))) return false; }
        return true;
    }
    /** A row is finished: its floor all cobbled deepslate, its walkway open and dry, and the rails it wants up (railWhy). */
    static boolean done(ServerLevel level, BridgeBlueprint.Row r, int lo, int hi) {
        Direction right = r.dir().getClockWise();
        for (int o = lo; o <= hi; o++) { BlockPos f = r.centre().relative(right, o);
            if (!level.getBlockState(f).is(net.minecraft.world.level.block.Blocks.COBBLED_DEEPSLATE)) return false;
            for (int y = 1; y <= clear(r); y++) if (solid(level, f.above(y)) || lava(level, f.above(y))) return false; }
        for (int o : new int[] {lo - 1, hi + 1}) if (!solid(level, r.centre().relative(right, o).above()) && railWhy(level, r, o) != null) return false;
        return true;
    }

    /**
     * The rail on side o of row r (at floor + 1, the spec's), priced: what a fall off that side costs him (back up from the
     * bottom, highway.off_back_s plus highway.off_s_per_block for each block down, the deepest of the open columns out to 3;
     * lava under it, highway.off_lava_s) times the odds of going off it over the crossings he'll make (a mob biome's knockback
     * more often), against the block and the second to place it. The user: "2 wide without rails first, then build the railing
     * ... when needed"; live 23:59:58 11-06 off his railless highway into a basalt delta after a magma cube, 1:45 back up
     * (stage 933). Null: not worth it (the ground beside, or a short fall in quiet ground); else why.
     */
    public static String railWhy(ServerLevel level, BridgeBlueprint.Row r, int o) {
        Direction right = r.dir().getClockWise(); int out = Integer.signum(o); int y0 = r.centre().getY();
        int deep = 0; boolean lavaUnder = false;
        for (int k = 0; k < 3; k++) { BlockPos col = r.centre().relative(right, o + out * k);
            if (!level.hasChunkAt(col)) break;
            if (solid(level, col.above()) || solid(level, col)) break; // (ground or rock beside at his height or the floor's: no fall there, nor past it)
            int d = 0; for (; d < 48; d++) { BlockPos q = col.below(d); if (lava(level, q)) { lavaUnder = true; break; } if (solid(level, q)) break; }
            deep = Math.max(deep, d); }
        if (deep == 0 && !lavaUnder) return null;
        BlockPos mid = r.centre().relative(right, o);
        boolean mobs = level.getBiome(mid).is(net.minecraft.world.level.biome.Biomes.BASALT_DELTAS) || level.getBiome(mid).is(net.minecraft.world.level.biome.Biomes.SOUL_SAND_VALLEY);
        double off = lavaUnder ? w("highway.off_lava_s", 600) : w("highway.off_back_s", 20) + w("highway.off_s_per_block", 7) * deep;
        double odds = w("highway.knock_odds", 0.001) * (mobs ? w("highway.knock_mob_biome_x", 10) : 1) * w("highway.crossings", 30);
        double rail = w("highway.rail_s", 3);
        if (odds * off <= rail) return null;
        return (lavaUnder ? "lava under it" : deep + " down") + (mobs ? " in " + level.getBiome(mid).unwrapKey().map(k -> k.location().getPath().replace('_', ' ')).orElse("mob ground") : "") + ": a fall off it " + Math.round(off) + " s x " + String.format("%.2f", odds) + " odds > " + Math.round(rail) + " s for the rail";
    }

    /**
     * One row's tasks, in the order a player does it from the row behind: lava round it sealed first (each against rock or a seal
     * before it), the walkway dug (lava in it plugged then dug), the floor laid (rock in it dug out first), a tunnel row's holes
     * walled and roofed, then onto it. Returns the blocks it places; c: {sealed, dug, floor, holes}.
     */
    /** A row's shell work: none (an open row), its holes patched (inside a tunnel, or a short open gap between two), or the full ring with its corners (a tunnel mouth). */
    public enum Shell { NONE, PATCH, FRAME }
    /** A row read on its own (no neighbours): a rock row is its own mouth both ways, framed. */
    public static int rowTasks(ServerLevel level, BridgeBlueprint.Row r, int lo, int hi, List<Task> out, int[] c) { return rowTasks(level, r, lo, hi, out, c, rockRow(level, r, lo, hi) ? Shell.FRAME : Shell.NONE, Items.NETHERRACK); }
    /** The ring at a tunnel mouth: both walls 3 high, the roof, and the four corners (by the roof and by the floor), so it shows from the open side with no gaps. */
    static List<BlockPos> ring(BridgeBlueprint.Row r, int lo, int hi) {
        Direction right = r.dir().getClockWise(); List<BlockPos> ring = new ArrayList<>(shell(r, lo, hi));
        for (int o : new int[] {lo - 1, hi + 1}) { ring.add(r.centre().relative(right, o).above(clear(r) + 1).immutable()); ring.add(r.centre().relative(right, o).immutable()); }
        return ring;
    }
    /** What lava gets plugged with: netherrack, else a block of the rock round here he carries, else deepslate (the user: "plug with netherrack or the surrounding blocks"). */
    public static Item plug(ClaudeEntity g) {
        for (Item it : new Item[] {Items.NETHERRACK, Items.BLACKSTONE, Items.BASALT, Items.SOUL_SOIL, Items.COBBLESTONE}) if (g.inventory.count(x -> x.is(it)) > 0) return it;
        return BLOCK;
    }
    /** A row's shell cells: its walls 3 high, its roof over the walkway. */
    static List<BlockPos> shell(BridgeBlueprint.Row r, int lo, int hi) {
        Direction right = r.dir().getClockWise(); List<BlockPos> shell = new ArrayList<>();
        for (int y = 1; y <= clear(r); y++) for (int o : new int[] {lo - 1, hi + 1}) shell.add(r.centre().relative(right, o).above(y).immutable());
        for (int o = lo; o <= hi; o++) shell.add(r.centre().relative(right, o).above(clear(r) + 1).immutable());
        return shell;
    }
    /** A tunnel row: most of its shell rock, trees not counted (a stray block or two walled the open air by portal #3, the user's "arches", live 13:59; "nether trees ... can just be railing"; stage 914). A shell laid is rock too. */
    static boolean rockRow(ServerLevel level, BridgeBlueprint.Row r, int lo, int hi) {
        var shell = shell(r, lo, hi); int rockN = 0; for (BlockPos q : shell) if (rock(level, q)) rockN++;
        return rockN >= shell.size() * w("highway.tunnel_rock_share", 0.5);
    }
    /** Row i shelled: a tunnel row, or a short open stretch with tunnel rows close on both sides, the shell carried through it (the user 11-06: "it should be a full shell instead of like 2 wide gaps in the middle of 2 shells with rails"; stage 923). */
    static boolean shelled(ServerLevel level, BridgeBlueprint b, int i, int lo, int hi) {
        if (rockRow(level, b.rows.get(i), lo, hi)) return true;
        int gap = (int) w("highway.shell_gap_rows", 6); boolean back = false, ahead = false;
        for (int k = 1; k <= gap && !back; k++) back = i - k >= 0 && level.hasChunkAt(b.rows.get(i - k).centre()) && rockRow(level, b.rows.get(i - k), lo, hi);
        for (int k = 1; k <= gap && back && !ahead; k++) ahead = i + k < b.rows.size() && level.hasChunkAt(b.rows.get(i + k).centre()) && rockRow(level, b.rows.get(i + k), lo, hi);
        return back && ahead;
    }
    public static int rowTasks(ServerLevel level, BridgeBlueprint.Row r, int lo, int hi, List<Task> out, int[] c, boolean tunnel) { return rowTasks(level, r, lo, hi, out, c, tunnel ? Shell.PATCH : Shell.NONE, Items.NETHERRACK); }
    /** The ring for a mouth he's leaving (the row behind him, framed from it as he steps out of the rock). Returns the deepslate it places. */
    static int frameTasks(ServerLevel level, BridgeBlueprint.Row r, int lo, int hi, List<Task> out, int[] c) {
        int placed = 0; Set<BlockPos> own = new HashSet<>(); Direction right = r.dir().getClockWise();
        int hh = clear(r); for (int o = lo; o <= hi; o++) for (int y = 0; y <= hh; y++) own.add(r.centre().relative(right, o).above(y).immutable());
        // the corners sit diagonal to the walkway: dug out after the walls and roof beside them open (a face to reach them by), laid before them (live: walls first left both corners with no face, OUT_OF_REACH; stage 934)
        List<BlockPos> lay = new ArrayList<>(); for (int o : new int[] {lo - 1, hi + 1}) lay.add(r.centre().relative(right, o).immutable());
        for (int y = 1; y <= hh; y++) for (int o : new int[] {lo - 1, hi + 1}) lay.add(r.centre().relative(right, o).above(y).immutable());
        for (int o : new int[] {lo - 1, hi + 1}) lay.add(r.centre().relative(right, o).above(hh + 1).immutable());
        for (int o = lo; o <= hi; o++) lay.add(r.centre().relative(right, o).above(hh + 1).immutable());
        List<BlockPos> todo = new ArrayList<>(), dig = new ArrayList<>();
        for (BlockPos q : lay) {
            if (level.getBlockState(q).is(net.minecraft.world.level.block.Blocks.COBBLED_DEEPSLATE) || keep(level, q)) continue;
            if (solid(level, q)) { boolean lavaBehind = false; for (Direction d : Direction.values()) if (!own.contains(q.relative(d)) && !lay.contains(q.relative(d)) && lava(level, q.relative(d))) lavaBehind = true;
                if (lavaBehind) continue; // (it holds lava back: kept, not opened to swap)
                dig.add(q); }
            todo.add(q); }
        int wEnd = 2 + 2 * hh; // (lay: the 2 floor corners, the walls 2 x hh, the 2 roof corners, the roof)
        for (int k = 2; k < wEnd; k++) if (dig.contains(lay.get(k))) { out.add(new Task.Break(lay.get(k))); c[1]++; } // walls
        for (int k = wEnd + 2; k < lay.size(); k++) if (dig.contains(lay.get(k))) { out.add(new Task.Break(lay.get(k))); c[1]++; } // roof
        for (int k : new int[] {0, 1, wEnd, wEnd + 1}) if (dig.contains(lay.get(k))) { out.add(new Task.Break(lay.get(k))); c[1]++; } // corners
        for (BlockPos q : todo) { out.add(new Task.Place(q, BLOCK)); placed++; c[3]++; }
        return placed;
    }
    public static int rowTasks(ServerLevel level, BridgeBlueprint.Row r, int lo, int hi, List<Task> out, int[] c, Shell sh, Item plug) {
        Direction right = r.dir().getClockWise(); int placed = 0; boolean tunnel = sh != Shell.NONE;
        int[] cols = new int[hi - lo + 1]; { int k = 0; cols[k++] = 0; for (int o = 1; k < cols.length; o++) { if (o <= hi && k < cols.length) cols[k++] = o; if (-o >= lo && k < cols.length) cols[k++] = -o; } } // (from his line outward)
        Set<BlockPos> own = new HashSet<>(); List<BlockPos> floor = new ArrayList<>(), walk = new ArrayList<>();
        for (int o : cols) { BlockPos f = r.centre().relative(right, o).immutable(); floor.add(f); own.add(f); }
        for (int y = 1; y <= clear(r); y++) for (int o : cols) { BlockPos q = r.centre().relative(right, o).above(y).immutable(); walk.add(q); own.add(q); }
        Set<BlockPos> seal = new java.util.LinkedHashSet<>();
        for (BlockPos q : own) for (Direction d : Direction.values()) { BlockPos n = q.relative(d); if (!own.contains(n) && d != r.dir().getOpposite() && !(d == Direction.DOWN && floor.contains(q)) && lava(level, n)) seal.add(n.immutable()); } // (lava under the floor stays under it: no seal, live 04:11 11-09 129,58,513 had no face and held the row, review 198) // (not behind: that's the row he stands on, built)
        for (BlockPos q : seal) { out.add(new Task.Place(q, plug)); if (plug == BLOCK) placed++; c[0]++; } // (lava plugs: netherrack, never the highway's deepslate)
        for (BlockPos q : walk) { if (lava(level, q)) { out.add(new Task.Place(q, plug)); out.add(new Task.Break(q)); if (plug == BLOCK) placed++; c[1]++; } else if (solid(level, q)) { out.add(new Task.Break(q)); c[1]++; } }
        for (BlockPos f : floor) { var st = level.getBlockState(f); if (st.is(net.minecraft.world.level.block.Blocks.COBBLED_DEEPSLATE)) continue; if (solid(level, f)) { out.add(new Task.Break(f)); c[1]++; } out.add(new Task.Place(f, BLOCK)); placed++; c[2]++; }
        if (sh == Shell.FRAME) { List<Task> fr = new ArrayList<>(); placed += frameTasks(level, r, lo, hi, fr, c); for (Task k : fr) if (!(k instanceof Task.Place p0 && seal.contains(p0.pos()))) out.add(k); }
        else if (sh == Shell.PATCH) for (BlockPos q : shell(r, lo, hi)) { // inside the tunnel the rock stays: only a hole in it gets shell (the user 11-07: "anything that doesnt need patching he can just do the floor")
            if (seal.contains(q) || solid(level, q) || lava(level, q)) continue;
            out.add(new Task.Place(q, BLOCK)); placed++; c[3]++;
        }
        out.add(new Task.GoTo(r.centre().above(), 0.6));
        if (!tunnel) for (int o : new int[] {lo - 1, hi + 1}) { BlockPos q = r.centre().relative(right, o).above().immutable(); // (from the row: the last row's rail beside it is its anchor, the first gets a footing)
            if (!solid(level, q) && railWhy(level, r, o) != null) { out.add(new Task.Place(q, BLOCK)); placed++; c[3]++; } }
        return placed;
    }

    /** A row's walls and roof all deepslate: a shell he laid (a patched gap), no rock mouth to frame. */
    static boolean shellDeep(ServerLevel level, BridgeBlueprint.Row r, int lo, int hi) { for (BlockPos q : shell(r, lo, hi)) if (!level.getBlockState(q).is(net.minecraft.world.level.block.Blocks.COBBLED_DEEPSLATE)) return false; return true; }
    /** A mouth's ring all deepslate (or kept: a portal frame, bedrock). */
    static boolean frameDone(ServerLevel level, BridgeBlueprint.Row r, int lo, int hi) { for (BlockPos q : ring(r, lo, hi)) if (!level.getBlockState(q).is(net.minecraft.world.level.block.Blocks.COBBLED_DEEPSLATE) && !keep(level, q)) return false; return true; }

    /** Each leg's built stretch on his portal map, as a highway segment (extended as it grows). */
    public static void record(ClaudeEntity g, BridgeBlueprint b, int built) {
        { State s0 = state(g); s0.built = built; s0.rows = b.rows.size(); }
        // every flat run of rows, one leg and one height (the first run of each leg only, before: the rows past a staircase never on record, the route couldn't use them; stage 925)
        // shortcut: addSegment joins runs 1 apart in height on one line, so a 1-step staircase's two runs read as one at the first's height; split by height there if the route ever mis-walks one
        BridgeBlueprint.Row a = null, z = null;
        for (int i = 0; i <= Math.min(built, b.rows.size()); i++) { var r = i < Math.min(built, b.rows.size()) ? b.rows.get(i) : null;
            if (r != null && !r.stair() && a != null && r.leg() == a.leg() && r.centre().getY() == a.centre().getY()) { z = r; continue; }
            if (a != null && z != null && a != z) PortalMap.addSegment(g, PortalMap.NETHER, a.centre(), z.centre(), a.dir().getAxis(), b.width);
            a = r != null && !r.stair() ? r : null; z = a; }
        BridgeBlueprint.Row flat = null; boolean stair = false; // the staircases between runs: their joins on record too (only flat runs were: two runs at y 58 and y 32 read as no road through, the route home went round by the fortress deck and a branch was built over the road; death 24, live 17:32-18:08 11-10, stage 1470)
        for (int i = 0; i < Math.min(built, b.rows.size()); i++) { var r = b.rows.get(i);
            if (r.stair()) { if (flat != null) stair = true; continue; }
            if (stair && flat != null) PortalMap.addStair(g, flat.centre().above(), r.centre().above());
            stair = false; flat = r; }
    }

    static String finish(ClaudeEntity g, ServerPlayer player, BridgeBlueprint b, String how) {
        String r = "done: the highway from portal #" + state(g).portalId + " to " + b.dest.toShortString() + " is laid, " + b.width + " wide, cobbled deepslate: " + how;
        ClaudeMod.LOGGER.info("[Claude highway] {} (Highway.finish)", r); state(g).dest = null; // (done: no longer underway)
        if (player != null) com.claudemod.director.ClaudeChat.say(player, "the highway to " + b.dest.toShortString() + " is down (" + b.width + " wide, railed where a fall off it costs more than the rail): " + how, "build_highway: done");
        return r;
    }

    /** The next trip's lot, on top of what he carries: as many stacks as his pack holds, a slot or two kept for pickups (the user 11-06: "as many stacks as he can at a time, with maybe 1 or 2 empty inventory slots as a buffer"), no more than the rows left take. */
    static int trip(ClaudeEntity g, int left) {
        int room = 0, free = 0, have = 0; for (int k = 0; k < g.inventory.items.getContainerSize(); k++) { var it = g.inventory.items.getItem(k); if (it.isEmpty()) free++; else if (it.is(BLOCK)) { room += it.getMaxStackSize() - it.getCount(); have += it.getCount(); } }
        free = com.claudemod.director.ClaudeLife.freeSlotsAfterDump(g); // (take_from_chest puts away what he doesn't need first: D2D 5223cef)
        int fit = room + Math.max(0, free - (int) w("highway.trip_free_slots", 2)) * 64;
        return Math.max(0, Math.min((int) (left * w("highway.blocks_per_row", 2.6)) - have, fit));
    }
    /** The take_from_chest step for this trip's lot: a highway underway, him in the overworld, his chests holding a stack or more of what he's short; null otherwise. Also a side job while he waits on a furnace (D2D: "staring in the furnace again", live 15:3x). */
    public static Skills.Step topUpStep(ClaudeEntity g) {
        State s = state(g);
        if (s.dest == null || s.rows <= 0 || g.level().dimension() == net.minecraft.world.level.Level.NETHER) return null;
        int fetch = Math.min(chestCount(g), trip(g, s.rows - Math.max(0, s.built)));
        return fetch >= 64 ? new Skills.Step("take_from_chest", "cobbled_deepslate", fetch) : null;
    }
    /**
     * Before he goes back to the highway from the overworld: room in his pack for a stack or more of this trip's
     * deepslate (trip(): what fits with highway.trip_free_slots free) is filled first, a collect (his chests first, then
     * mining). Null when the pack is full enough, or when the collect just ran (what he got is what he takes).
     * Live 18:14 11-06: after a restart dropped the queued collect, he went back with 307 of 384; the user: "is he not
     * taking the minimum of stacks each time".
     */
    public static Skills.Step packStep(ClaudeEntity g) {
        State s = state(g);
        if (g.level().dimension() != net.minecraft.world.level.Level.OVERWORLD) return null;
        if (g.lastStep != null && g.lastStep.skill().equals("collect") && g.lastStep.target().equals("cobbled_deepslate")) return null;
        boolean planned = s.dest != null && s.portalId >= 0 && s.rows > 0; // (callers only ask with a build_highway on his queue)
        // a new highway is planned once he's through the portal: a full pack for it, the packing rule (D2D review 084849 11-07: the last one done at 07:17, the next queued at 08:42, "nothing underway" read as nothing to pack; out at row 404 with 1, 401 blocks back)
        // shortcut: a full pack, not the new plan's length; size it from the destination if over-packing a short one ever costs a trip
        int missing = trip(g, planned ? s.rows - Math.max(0, s.built) : (int) w("highway.unplanned_rows", 1000));
        int have0 = g.inventory.count(x -> x.is(BLOCK)); if (missing < w("highway.pack_min_short", 64) || have0 >= w("highway.fetch_min_stacks", 5) * 64) return null; // (5 stacks on him is a trip: back down his mine for the last 105 of 759, 654 on him, live 19:53 11-06; the user: "the minimum of stacks each time")
        int have = g.inventory.count(x -> x.is(BLOCK));
        ClaudeMod.LOGGER.info("[Claude highway] packing for the highway before the portal{}: {} on him, room for {} more of this trip (Highway.packStep)", planned ? "" : " (a new one, planned once he's through: a full pack)", have, missing);
        return new Skills.Step("collect", "cobbled_deepslate", have + missing);
    }
    static int chestCount(ClaudeEntity g) { int n = 0; for (var e : ChestLedger.book(g).values()) if (e.dim() != null && e.dim().endsWith("overworld")) n += e.items().getOrDefault(BLOCK, 0); return n; }

    /** Out of cobbled deepslate (or short of the next row): where the next lot comes from, priced; the trip queued, then back to building. */
    static String blocks(ClaudeEntity g, ServerPlayer player, ServerLevel level, State s, int next, int have, String words, Skills.Step st) {
        BridgeBlueprint b = s.plan; int left = rowsLeft(level, b, next);
        int trip = Math.max(64, trip(g, left)), inChests = chestCount(g);
        int minFetch = (int) (w("highway.fetch_min_stacks", 5) * 64); // (the user: "if he has less than 5 he can grab he should go mining")
        var portal = PortalMap.byId(g, s.portalId); double sprint = Decide.sprint();
        double toPortal = portal == null ? 0 : Math.sqrt(g.blockPosition().distSqr(portal.at)) / sprint, through = w("highway.portal_s", 8);
        double there = 2 * (toPortal + through), chestS = w("highway.chest_trip_s", 90), mineS = w("highway.mine_s_per_block", 1.2), mineWalk = w("highway.mine_trip_s", 120);
        List<Decide.Option> opts = new ArrayList<>();
        if (inChests < Math.min(trip, minFetch)) { opts.add(Decide.Option.out("his chests", inChests + " in them, under " + minFetch / 64 + " stacks: not worth the trip, mining")); }
        else if (inChests >= trip) opts.add(Decide.Option.of("his chests", there + chestS, inChests + " in his chests; " + Math.round(there) + " s there and back + " + Math.round(chestS) + " s at them"));
        else opts.add(Decide.Option.out("his chests", inChests + " in them, " + trip + " wanted"));
        if (inChests >= minFetch && inChests < trip) opts.add(Decide.Option.of("chests, then mine the rest", there + chestS + mineWalk + (trip - inChests) * mineS, inChests + " from chests, " + (trip - inChests) + " mined at " + mineS + " s each"));
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
