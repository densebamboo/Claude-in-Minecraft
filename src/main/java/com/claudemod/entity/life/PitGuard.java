package com.claudemod.entity.life;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.claudemod.ClaudeMod;
import com.claudemod.director.ClaudeLife;
import com.claudemod.entity.ClaudeEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A hole by his door made safe the player's way (2026-10-30, live: a cave pit opened in front of his mine's door at -18, 67, -29,
 * its floor 6 down; every trip in he dropped into it and spun at the foot of the wall, 25 s at 04:03 and again at 04:52). A player
 * who falls into a hole by his own front door, or keeps walking round it, fills its mouth.
 *
 * The rule: a fall of 3+ that lands him in a hole within 8 of his mine's door, or a second route that skirts the same hole there,
 * and he closes the hole at its mouth: cobble in every open cell of the mouth at his door's ground level (the cells with a drop of
 * 2+ under them), from his door outwards, his own build (it stays: never on the temporary ledger). A hole is a drop he can't walk
 * back up out of to his door in a short walk (low ground he can walk up from is a slope, left as it is). A big mouth (more than
 * 64 cells) gets the apron within 3 of his door only; with less cobble on him than the mouth needs, the cells nearest his door.
 * Never his mine's cells (the blueprint patches those, the arch in its own block), his house, farm or nook, nor a hole the user
 * built (their blocks in its walls). Ore he sees in the hole's walls comes out first (his mine-integrity habit). Down in the hole
 * when it's queued, he walks up to his door first (the planner's way out, or his walker's climb) and caps it from there.
 */
public final class PitGuard {
    static final class ClaudeSpawner0 { static boolean testing() { return com.claudemod.entity.ClaudeSpawner.testing; } }
    private PitGuard() {}

    public static final int NEAR = 8; // a fall or a skirt this near his door (blocks across)
    static final int SPAN = 12; // the mouth looked at this far round his door
    static final int MAX_CAP = 64; // a bigger mouth: the apron within 3 of his door only
    static final int HOLE_MAX = 6; // a hole worth a cap: a mouth this small at most (more is the lie of the land)
    static boolean down0(ClaudeEntity g, BlockPos seed) { return g.getBlockY() <= seed.getY() - 2 && across(g.blockPosition(), seed) <= 2; } // (down in it now: out of it is the job, the cap his way up)

    private static final class State {
        BlockPos lastGround; float fall; boolean leapt; BlockPos door; int noStoneSaid = -100000; BlockPos resumedFrom; int resumedLeft = -1;
        final Set<BlockPos> caps = new LinkedHashSet<>(); // his build: the cells he set (or will set) in a hole's mouth
        final Set<BlockPos> hole = new HashSet<>(); // the hole's open cells under it: what he puts in there (his climb out) is the fill, his build too
        final List<long[]> skirts = new ArrayList<>(); // drop cells his routes went round: {pos, tick}
        final Set<BlockPos> lights = new HashSet<>(); // his torches in a hollow by his door: his build
        final LinkedHashSet<Long> routeCols = new LinkedHashSet<>(); // the columns his recent routes near his door ran over
        int lastClose = -100000, mobSaid = -100000, lightLeft = -1; BlockPos lightFrom;
        BlockPos laterSeed; String laterWhy; boolean laterSaid; int notNowSaid = -100000; String notNowLast, sameSaid; // a hole put off (their order, the night): closed later; the lines said
        Map<BlockPos, Integer> capIn; Map<BlockPos, Integer> capIn() { if (capIn == null) capIn = new HashMap<>(); return capIn; } // a cap cell -> when it was seen in (lazy: fields added live start null)
    }
    private static final Map<UUID, State> states = new HashMap<>();
    private static State st(ClaudeEntity g) { return states.computeIfAbsent(g.getUUID(), k -> new State()); }
    public static void reset(ClaudeEntity g) { states.remove(g.getUUID()); }
    /** A cell of a hole's mouth he closed (or is closing), or of the hole under it: his build (never temporary, never dug through). */
    public static boolean isCap(ClaudeEntity g, BlockPos p) { State s = states.get(g.getUUID()); return s != null && (s.caps.contains(p) || s.hole.contains(p) || s.lights.contains(p)); }
    public static Set<BlockPos> lights(ClaudeEntity g) { return st(g).lights; }
    static long col(int x, int z) { return ((long) x << 32) | (z & 0xffffffffL); }
    /** Within a block of a column one of his recent routes by his door ran over. */
    static boolean byRoute(State s, BlockPos c) { for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) if (s.routeCols.contains(col(c.getX() + dx, c.getZ() + dz))) return true; return false; }
    public static Set<BlockPos> caps(ClaudeEntity g) { return st(g).caps; }

    /** His door: the cell just outside his mine's way in. */
    public static BlockPos door(ClaudeEntity g) { MineBlueprint b = g.blueprint; return b == null || b.isEmpty() ? null : b.outside(); }

    /**
     * Not now, and why: their order runs (15:23 world 14: a fall by his door queued the cap job mid "come back to the spot"), or it's
     * night and sleepIfNight holds him to his bed (by it, walking back to it, asleep) more than 16 off this spot (15:19 world 14: 48
     * cap Places 35 off his field bed, and the bed hold and the cap walks took turns every few seconds; a player doesn't walk 35
     * blocks in the dark to plug a hole). Null: now is fine. Every PitGuard job asks this one: close (whatever set it off), resume,
     * his blocks already queued for it, and the hole put off (closed once this says null and he's by his door).
     */
    static String notNow(ClaudeEntity g, ServerLevel level, BlockPos at) {
        if (g.commandActive) return "their order first, it waits till that's done";
        if (Consent.lesson(g)) return "their lesson's open: it waits till they end it"; // (instruction first: what they have him do isn't undone round them)
        BlockPos bed = ClaudeLife.nightBedHold(g, level);
        if (bed != null && bed.distSqr(at) > 16 * 16) return "night: held by his bed at " + bed.toShortString() + " (ClaudeLife.sleepIfNight), " + (int) Math.sqrt(bed.distSqr(at)) + " blocks off it: it waits for the morning, no walk out in the dark to plug it";
        return null;
    }

    static boolean open(ServerLevel level, BlockPos p) { return level.getBlockState(p).getCollisionShape(level, p).isEmpty() && level.getFluidState(p).isEmpty(); }
    static boolean solid(ServerLevel level, BlockPos p) { return !level.getBlockState(p).getCollisionShape(level, p).isEmpty(); }
    static int across(BlockPos a, BlockPos b) { return Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getZ() - b.getZ())); }

    /** One of his builds owns it (his mine's blueprint and cells, his house, farm, nook): no hole of his to fill. */
    static boolean hisBuildCell(ClaudeEntity g, BlockPos p) {
        if (g.blueprint != null && !g.blueprint.isEmpty() && g.blueprint.touches(p)) return true;
        if (g.house != null && g.house.touches(p)) return true;
        if (g.farm != null && g.farm.touches(p)) return true;
        return ClaudeLife.inHisNook(g, p) || ClaudeLife.isMineCell(g, p);
    }

    /** A cell of a hole's mouth: open, a drop of 2+ under it (open under it too), none of his builds' cells. */
    static boolean mouthCell(ClaudeEntity g, ServerLevel level, BlockPos c) {
        return level.isLoaded(c) && open(level, c) && open(level, c.below()) && !hisBuildCell(g, c) && !hisBuildCell(g, c.below());
    }

    /** The mouth's cells joined to the start at its level, within SPAN of his door. */
    static Set<BlockPos> mouth(ClaudeEntity g, ServerLevel level, BlockPos start, BlockPos door, int max) {
        Set<BlockPos> out = new LinkedHashSet<>();
        if (!mouthCell(g, level, start)) return out;
        ArrayDeque<BlockPos> q = new ArrayDeque<>(); q.add(start.immutable()); out.add(start.immutable());
        while (!q.isEmpty() && out.size() < max) {
            BlockPos c = q.poll();
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos n = c.relative(d);
                if (out.contains(n) || across(n, door) > SPAN || !mouthCell(g, level, n)) continue;
                out.add(n.immutable()); q.add(n.immutable());
            }
        }
        return out;
    }

    /** Every tick: a fall noticed when he lands; a closing left half done picked up again. */
    public static void tick(ClaudeEntity g, ServerLevel level) {
        State s = st(g);
        if (g.tickCount % 200 == 37 && !ClaudeSpawner0.testing()) cleanup(g, level);
        if (g.isInWater()) { s.fall = 0; s.leapt = false; return; } // (water breaks a fall)
        if (!g.onGround()) { s.fall = Math.max(s.fall, g.fallDistance); if (g.currentTask instanceof Task.Leap) s.leapt = true; return; }
        float fell = s.fall; s.fall = 0; boolean leapt = s.leapt; s.leapt = false;
        BlockPos from = s.lastGround; s.lastGround = g.blockPosition().immutable();
        if (fell >= 2.5f && leapt) ClaudeMod.LOGGER.info("[Claude life] dropped {} to {} by his own planned leap: low ground he chose, not a hole he fell in (PitGuard.tick)", (int) fell, g.blockPosition().toShortString()); // (08:50 a planned 6-down leap into the dip by his door capped 74 cells of natural ground)
        else if (fell >= 2.5f) fell(g, level, from, g.blockPosition().immutable(), fell);
        if (g.tickCount % 20 == 7) hostileIn(g, level, s);
        if (g.tickCount % 10 == 3 && !s.caps.isEmpty()) for (BlockPos p : s.caps) if (!s.capIn().containsKey(p) && level.isLoaded(p) && !level.getBlockState(p).canBeReplaced()) s.capIn().put(p.immutable(), g.tickCount); // (when each went in: a cell that failed before one beside it went in has a face now)
        if (g.tickCount % 20 == 11 && !s.caps.isEmpty()) notNowQueued(g, level, s);
        if (g.tickCount % 40 == 37 && s.laterSeed != null) later(g, level, s);
        if (g.tickCount % 40 == 17 && !s.caps.isEmpty()) resume(g, level, s);
    }

    /** Not now (their order, the night at his bed) with his blocks for the hole by his door still queued: off his queue (resume queues what's still open once it's fine and he's by his door). */
    static void notNowQueued(ClaudeEntity g, ServerLevel level, State s) {
        BlockPos at = s.door != null ? s.door : door(g);
        if (at == null) return;
        String no = notNow(g, level, at);
        if (no == null) return;
        int n = 0;
        for (var it = g.tasks.iterator(); it.hasNext(); ) { Task t = it.next(); if (t instanceof Task.Place p && (s.caps.contains(p.pos()) || s.lights.contains(p.pos()))) { it.remove(); n++; } }
        if (g.currentTask instanceof Task.Place p0 && (s.caps.contains(p0.pos()) || s.lights.contains(p0.pos()))) { ClaudeLife.dropCurrent(g); n++; }
        if (n > 0) ClaudeMod.LOGGER.info("[Claude life] the hole by his door at {}: {} of his queued blocks for it off his queue ({}) (PitGuard.notNowQueued)", at.toShortString(), n, no);
    }

    /** A hole put off (their order, the night): closed once it's fine, when he's by his door again (on his way past, not a detour). */
    static void later(ClaudeEntity g, ServerLevel level, State s) {
        BlockPos door = door(g);
        if (door == null) { ClaudeMod.LOGGER.info("[Claude life] the hole at {} put off ({}): no mine door now, nothing to close by it (PitGuard.later)", s.laterSeed.toShortString(), s.laterWhy); s.laterSeed = null; return; }
        if (notNow(g, level, s.laterSeed) != null) return; // (still not now: close said why when it put it off)
        if (across(g.blockPosition(), door) > SPAN + 4) { if (!s.laterSaid) { s.laterSaid = true; ClaudeMod.LOGGER.info("[Claude life] the hole at {} put off ({}): closed when he's next by his door ({} off it now) (PitGuard.later)", s.laterSeed.toShortString(), s.laterWhy, across(g.blockPosition(), door)); } return; }
        BlockPos seed = s.laterSeed; String why = s.laterWhy; s.laterSeed = null; s.laterWhy = null; s.laterSaid = false;
        ClaudeMod.LOGGER.info("[Claude life] by his door now, nothing in the way: the hole at {} put off ({}): closing it (PitGuard.later)", seed.toShortString(), why);
        close(g, level, seed, why + ", put off till now");
    }

    /** A hostile he knows of (seen or heard) down in a hollow within 12 of his door: it gets made safe (once a minute at most, never mid-closing). */
    static void hostileIn(ClaudeEntity g, ServerLevel level, State s) {
        BlockPos door = door(g);
        if (door == null || g.tickCount - s.lastClose < 20 * 60 || !pending(g, level, s).isEmpty()) return;
        int floorY = door.getY() - 1;
        for (var m : Perception.known(g, net.minecraft.world.entity.monster.Monster.class, new net.minecraft.world.phys.AABB(door).inflate(12), x -> x.isAlive())) {
            BlockPos mp = m.blockPosition();
            if (across(mp, door) > 12 || mp.getY() > floorY - 1) continue;
            BlockPos seed = null;
            for (int dx = -1; dx <= 1 && seed == null; dx++) for (int dz = -1; dz <= 1 && seed == null; dz++) { BlockPos c = new BlockPos(mp.getX() + dx, floorY, mp.getZ() + dz); if (mouthCell(g, level, c)) seed = c; }
            if (seed == null) continue; // (under the ground, not down a hollow open by his door)
            close(g, level, seed, "a " + m.getType().getDescription().getString() + " down in it at " + mp.toShortString());
            return;
        }
    }

    /** He landed after a fall: in a hole by his door, it gets closed. */
    static void fell(ClaudeEntity g, ServerLevel level, BlockPos from, BlockPos landed, float fall) {
        BlockPos door = door(g);
        if (door == null) return; // (no mine, no door: a fall out in the world is just a fall)
        if (across(landed, door) > 24) return; // (a drop off a ledge out in the world: nothing to do with his door)
        if (across(landed, door) > NEAR || Math.abs(landed.getY() - door.getY()) > 24) { ClaudeMod.LOGGER.info("[Claude life] fell {} to {}: {} off his door at {}, not a hole by it (PitGuard.fell)", (int) fall, landed.toShortString(), across(landed, door), door.toShortString()); return; }
        if (hisBuildCell(g, landed) || hisBuildCell(g, landed.below())) { ClaudeMod.LOGGER.info("[Claude life] fell {} to {}: in his own build (his mine), the blueprint's to patch (PitGuard.fell)", (int) fall, landed.toShortString()); return; }
        if (landed.getY() > door.getY() - 2) { ClaudeMod.LOGGER.info("[Claude life] fell {} to {}: at his door's level (y {}) or above it, no hole by it (PitGuard.fell)", (int) fall, landed.toShortString(), door.getY()); return; }
        int floorY = door.getY() - 1;
        if (from != null && across(from, door) <= NEAR + 2 && Math.abs(from.getY() - 1 - floorY) <= 2) floorY = from.getY() - 1; // (the ground he walked off, about his door's level: the mouth is there)
        BlockPos seed = null;
        for (int r = 0; r <= 1 && seed == null; r++) for (int dx = -r; dx <= r && seed == null; dx++) for (int dz = -r; dz <= r && seed == null; dz++) { BlockPos c = new BlockPos(landed.getX() + dx, floorY, landed.getZ() + dz); if (mouthCell(g, level, c)) seed = c; }
        if (seed == null) { ClaudeMod.LOGGER.info("[Claude life] fell {} to {} by his door: no open mouth over him at y {} (a cave under the ground, not a hole in it) (PitGuard.fell)", (int) fall, landed.toShortString(), floorY); return; }
        close(g, level, seed, "he fell " + (int) fall + " into it, from " + (from == null ? "?" : from.toShortString()) + " to " + landed.toShortString());
    }

    /** A route he's about to walk: a drop beside it near his door is a hole it skirts (the second plan that skirts the same one, he closes it). */
    public static void planned(ClaudeEntity g, ServerLevel level, List<long[]> path) {
        BlockPos door = door(g);
        if (door == null || path.isEmpty()) return;
        State s0 = st(g);
        for (long[] q : path) if (Math.max(Math.abs((int) q[0] - door.getX()), Math.abs((int) q[1] - door.getZ())) <= SPAN) { s0.routeCols.remove(col((int) q[0], (int) q[1])); s0.routeCols.add(col((int) q[0], (int) q[1])); }
        while (s0.routeCols.size() > 400) { var it0 = s0.routeCols.iterator(); it0.next(); it0.remove(); }
        for (long[] q : path) {
            int x = (int) q[0], z = (int) q[1], feet = (int) q[2];
            if (Math.max(Math.abs(x - door.getX()), Math.abs(z - door.getZ())) > NEAR || Math.abs(feet - door.getY()) > 2) continue;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos c = new BlockPos(x + d.getStepX(), feet - 1, z + d.getStepZ());
                if (!mouthCell(g, level, c)) continue;
                skirted(g, level, c);
                return; // (one hole a plan)
            }
        }
    }

    static void skirted(ClaudeEntity g, ServerLevel level, BlockPos c) {
        State s = st(g); int now = g.tickCount; BlockPos door = door(g);
        s.skirts.removeIf(e -> now - (int) e[1] > 20 * 60 * 20 || now < e[1]); // (20 minutes: an old skirt is another trip's)
        Set<BlockPos> m = mouth(g, level, c, door, 400);
        for (long[] e : s.skirts) {
            if (!m.contains(BlockPos.of(e[0]))) continue;
            if (now - (int) e[1] < 100) return; // (the same walk planned again a moment later: one skirt)
            ClaudeMod.LOGGER.info("[Claude route] his way by his door skirts the hole at {} again ({} s after the first): closing it (PitGuard.skirted)", c.toShortString(), (now - (int) e[1]) / 20);
            s.skirts.clear();
            close(g, level, c, "his routes keep skirting it");
            return;
        }
        s.skirts.add(new long[] {c.asLong(), now});
        ClaudeMod.LOGGER.info("[Claude route] his way by his door skirts a drop at {} (a mouth of {} open cells): noted; skirted again and he closes it (PitGuard.skirted)", c.toShortString(), m.size());
    }

    /** His old caps that were no hole's (a cluster of more than HOLE_MAX, or under 4 deep): taken back, a few at a time when he's idle (the user, World 15: "why is there so much cobble and deepslate placed there"). */
    private static int cleanTick = -100000;
    public static void cleanup(ClaudeEntity g, ServerLevel level) {
        if (g.tickCount - cleanTick < 200 || g.currentTask != null || !g.tasks.isEmpty() || !g.skillQueue.isEmpty() || g.planning || !level.isDay()) return;
        cleanTick = g.tickCount;
        var bc = TempLedger.builtCells(g); List<BlockPos> mine = new ArrayList<>();
        for (var e : bc.entrySet()) if (e.getValue() != null && e.getValue().startsWith("PitGuard") && e.getKey().distSqr(g.blockPosition()) <= 32 * 32 && level.isLoaded(e.getKey())) { var st0 = level.getBlockState(e.getKey()); if (st0.is(net.minecraft.world.level.block.Blocks.COBBLESTONE) || st0.is(net.minecraft.world.level.block.Blocks.COBBLED_DEEPSLATE)) mine.add(e.getKey()); }
        if (mine.isEmpty()) return;
        Set<BlockPos> left = new HashSet<>(mine); List<BlockPos> take = new ArrayList<>();
        while (!left.isEmpty() && take.size() < 16) {
            BlockPos s0 = left.iterator().next(); List<BlockPos> cl = new ArrayList<>(); ArrayDeque<BlockPos> q = new ArrayDeque<>(); q.add(s0); left.remove(s0);
            while (!q.isEmpty()) { BlockPos c = q.poll(); cl.add(c); for (Direction d : Direction.Plane.HORIZONTAL) for (int dy = -1; dy <= 1; dy++) { BlockPos n = c.relative(d).above(dy); if (left.remove(n)) q.add(n); } }
            int deep = 0; for (BlockPos c : cl) { int k = 0; BlockPos q1 = c.below(); while (k < 8 && open(level, q1)) { k++; q1 = q1.below(); } deep = Math.max(deep, k); }
            if (cl.size() <= HOLE_MAX && deep >= 4) continue; // (a true hole's cap: it stays)
            cl.sort(Comparator.comparingDouble(c -> c.distSqr(g.blockPosition())));
            for (BlockPos c : cl) { if (take.size() >= 16) break; take.add(c); }
        }
        if (take.isEmpty()) return;
        for (BlockPos c : take) bc.remove(c); // (no build of his now: his to take back)
        take.sort(Comparator.comparingInt((BlockPos c) -> -c.getY()));
        g.tasks.add(new Task.Pickup(60)); for (int i = take.size() - 1; i >= 0; i--) g.tasks.addFirst(new Task.Break(take.get(i)));
        ClaudeMod.LOGGER.info("[Claude life] {} of his old cap blocks by his door were no hole's (natural ground): taking them back, {} this round (PitGuard.cleanup)", mine.size(), take.size());
    }

    /** The cells of the caps still open (not set yet). */
    static List<BlockPos> pending(ClaudeEntity g, ServerLevel level, State s) {
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos p : s.caps) if (level.isLoaded(p) && level.getBlockState(p).canBeReplaced() && level.getFluidState(p).isEmpty()) out.add(p);
        return out;
    }

    static int stone(ClaudeEntity g) { return g.inventory.count(x -> x.is(Items.COBBLESTONE) || x.is(Items.COBBLED_DEEPSLATE)); }

    /** Close the hole whose mouth holds this cell. */
    static void close(ClaudeEntity g, ServerLevel level, BlockPos seed, String why) {
        BlockPos door = door(g); State s = st(g);
        if (door == null) return;
        { String no = notNow(g, level, seed); // (after main's order line: 15:19 a 48-cell close at night, 35 off his bed)
          if (no != null) { boolean again = seed.equals(s.laterSeed); s.laterSeed = seed.immutable(); s.laterWhy = why; s.laterSaid = false;
            ClaudeMod.LOGGER.info("[Claude life] the hole at {} ({}): {}{} (PitGuard.close)", seed.toShortString(), why, no, again ? " (put off already)" : "");
            return; } }
        int pend = pending(g, level, s).size();
        if (pend > 0) { ClaudeMod.LOGGER.info("[Claude life] the hole at {} ({}): already closing one by his door, {} cell(s) to go (PitGuard.close)", seed.toShortString(), why, pend); return; }
        Set<BlockPos> mouth = mouth(g, level, seed, door, 400);
        if (mouth.isEmpty()) { ClaudeMod.LOGGER.info("[Claude life] the hole at {} ({}): no open mouth there now (PitGuard.close)", seed.toShortString(), why); return; }
        // a hole, or low ground? from its bottom back up to his door on foot
        BlockPos bottom = seed; while (bottom.getY() > level.getMinBuildHeight() && open(level, bottom.below())) bottom = bottom.below();
        double straight = Math.hypot(bottom.getX() - door.getX(), bottom.getZ() - door.getZ());
        double back = com.claudemod.entity.nav.Route.walkLength(g, level, bottom, door, 3 * straight + 12, 1);
        boolean low = back >= 0; // (a walk back up from its bottom: low ground, not a hole; but his routes step into it, he fell in, or a hostile's in it: still made safe, kept small)
        // the user's own hole (their blocks in its walls or floor): theirs
        if (g.getTarget() instanceof ServerPlayer owner) {
            Set<BlockPos> theirs = new HashSet<>(com.claudemod.context.PlayerProfile.placedBy(owner));
            if (!theirs.isEmpty()) for (BlockPos m : mouth) for (int dy = 0; dy <= 8; dy++) for (Direction d : Direction.values()) {
                BlockPos n = m.below(dy).relative(d);
                if (theirs.contains(n)) { ClaudeMod.LOGGER.info("[Claude life] the hole at {} ({}): their block at {} in its walls, their build: left open (PitGuard.close)", seed.toShortString(), why, n.toShortString()); return; }
            }
        }
        int depth = 0; { BlockPos q0 = seed; while (depth < 8 && open(level, q0)) { depth++; q0 = q0.below(); } }
        if (!down0(g, seed) && (mouth.size() > HOLE_MAX || depth < 4 || low)) { // (natural ground: a slope, a dip or a drop he can step down: walked round, never capped; World 15: a 400-cell "mouth" by his door took 389 blocks of cobble, a grey blob the user took for a base)
            ClaudeMod.LOGGER.info("[Claude life] the drop at {} ({}): a mouth of {} cells, {} deep{}: natural ground, walked round, not capped (PitGuard.close)", seed.toShortString(), why, mouth.size(), depth, low ? ", a walk back up out of it" : ""); return; }
        List<BlockPos> cells = new ArrayList<>(mouth); String part = "its whole mouth (" + mouth.size() + " cells, " + depth + " deep)";
        if (low || cells.size() > MAX_CAP) { cells.removeIf(c -> across(c, door) > 3 && !byRoute(s, c)); part = "the apron within 3 of his door and the strip his routes cross (" + cells.size() + " of a mouth of " + mouth.size() + " cells" + (low ? "; low ground, " + (int) back + " blocks' walk back up from its bottom at " + bottom.toShortString() + ", but a hazard by his door" : "") + ")"; }
        int have = stone(g);
        if (!cells.isEmpty() && have == 0) { ClaudeMod.LOGGER.info("[Claude life] the hole at {} ({}): no cobble on him for its {} cells, left for when he has some; the light goes in (PitGuard.close)", seed.toShortString(), why, cells.size()); cells.clear(); }
        cells.sort(Comparator.comparingDouble(c -> c.distSqr(door)));
        if (cells.size() > have) { part += ", the " + have + " nearest his door (no more cobble on him)"; cells = new ArrayList<>(cells.subList(0, have)); }
        // down in it: a tower up the open column he fell through (the seed's), its top the first block of the cap, and the cap out from
        // there round him, standing on it as it grows (a player who drops into a hole by his door: up and fill it from where he is)
        boolean down = !low && g.getBlockY() <= seed.getY() - 1 && across(g.blockPosition(), seed) <= SPAN && cells.contains(seed);
        List<BlockPos> order = down ? fromCell(level, cells, seed, door) : rimFirst(level, cells, door);
        List<BlockPos> ores = oreInWalls(g, level, mouth, door, s.hole);
        // what stays open is lit: a hostile spawns only at block light 0
        List<BlockPos> torches = cells.size() < mouth.size() ? lightSpots(g, level, s, door, new HashSet<>(cells)) : List.of();
        int tor = g.inventory.count(x -> x.is(Items.TORCH));
        if (torches.size() > tor) { ClaudeMod.LOGGER.info("[Claude life] the hollow at {} wants {} torch(es) and he has {}: the nearest his door (PitGuard.close)", seed.toShortString(), torches.size(), tor); torches = new ArrayList<>(torches.subList(0, tor)); }
        s.lastClose = g.tickCount;
        if (order.isEmpty() && torches.isEmpty()) { ClaudeMod.LOGGER.info("[Claude life] the hole at {} ({}): nothing to set by his door ({}: none by his door or his routes; its floor there lit already): left as it is (PitGuard.close)", seed.toShortString(), why, part); return; }
        s.caps.addAll(order); s.lights.addAll(torches); s.door = door; s.resumedFrom = null;
        for (int i = order.size() - 1; i >= 0; i--) g.tasks.addFirst(new Task.Place(order.get(i), Items.COBBLESTONE));
        if (down) { g.tasks.addFirst(new Task.Pillar(seed.getY() - bottom.getY() + 1)); g.tasks.addFirst(new Task.GoTo(bottom, 0.5)); }
        for (int i = torches.size() - 1; i >= 0; i--) g.tasks.addFirst(new Task.Place(torches.get(i), Items.TORCH)); // (lit first, while its floor is still open to walk)
        if (!ores.isEmpty()) { g.tasks.addFirst(new Task.Pickup(60)); for (int i = ores.size() - 1; i >= 0; i--) g.tasks.addFirst(new Task.Break(ores.get(i))); }
        ClaudeMod.LOGGER.info("[Claude life] the hole at {} by his mine's door ({}): closing {} with cobble at y {}, from his door out, his build (it stays) (PitGuard.close); {}{}{}", seed.toShortString(), why, part, seed.getY(),
                ores.isEmpty() ? "no ore in its walls in sight" : "the ore in its walls first: " + ores.stream().map(BlockPos::toShortString).toList(), down ? "; down in it: a " + (seed.getY() - bottom.getY() + 1) + "-block tower up from " + bottom.toShortString() + " first, its top the cap's first block" : "",
                torches.isEmpty() ? "" : "; " + torches.size() + " torch(es) on its floor first: " + torches.stream().map(BlockPos::toShortString).toList());
    }

    /** Torch spots on the hollow's floor by his door (within 12): every floor cell there lit (block light) or within 10 of one; 6 at most. */
    static List<BlockPos> lightSpots(ClaudeEntity g, ServerLevel level, State s, BlockPos door, Set<BlockPos> capped) {
        List<BlockPos> floor = new ArrayList<>();
        Set<Long> cappedCols = new HashSet<>(); for (BlockPos q : capped) cappedCols.add(col(q.getX(), q.getZ()));
        for (BlockPos c : s.hole) if (across(c, door) <= SPAN && open(level, c) && open(level, c.above()) && solid(level, c.below()) && !cappedCols.contains(col(c.getX(), c.getZ())) // (not under the cap: his tower out goes up a capped column)
                && level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, c) == 0) floor.add(c);
        floor.sort(Comparator.comparingDouble(c -> c.distSqr(door)));
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos c : floor) {
            boolean lit = false; for (BlockPos t : out) if (t.distManhattan(c) <= 10) { lit = true; break; }
            if (lit) continue;
            if (!level.getBlockState(c.below()).isFaceSturdy(level, c.below(), Direction.UP)) continue; // (a torch stands on a full top face)
            out.add(c.immutable());
            if (out.size() >= 6) break;
        }
        return out;
    }

    /** The order to set them: from the cells against the rim (by his door first) inwards, each against one already set. */
    static List<BlockPos> rimFirst(ServerLevel level, List<BlockPos> cells, BlockPos door) {
        Set<BlockPos> set = new HashSet<>(cells), seen = new HashSet<>();
        List<BlockPos> src = new ArrayList<>();
        for (BlockPos c : cells) for (Direction d : Direction.values()) { BlockPos n = c.relative(d); if (!set.contains(n) && solid(level, n)) { src.add(c); break; } }
        src.sort(Comparator.comparingDouble(c -> c.distSqr(door)));
        List<BlockPos> out = new ArrayList<>(); ArrayDeque<BlockPos> q = new ArrayDeque<>();
        for (BlockPos c : src) if (seen.add(c)) { q.add(c); }
        while (!q.isEmpty()) {
            BlockPos c = q.poll(); out.add(c);
            for (Direction d : Direction.Plane.HORIZONTAL) { BlockPos n = c.relative(d); if (set.contains(n) && seen.add(n)) q.add(n); }
        }
        for (BlockPos c : cells) if (!seen.contains(c)) out.add(c); // (nothing to set it against yet: last, against what's set by then)
        return out;
    }

    /** The order out from one cell (the top of his tower): each cell beside one set already; any not joined to it, from the rim. */
    static List<BlockPos> fromCell(ServerLevel level, List<BlockPos> cells, BlockPos start, BlockPos door) {
        Set<BlockPos> set = new HashSet<>(cells), seen = new HashSet<>(); List<BlockPos> out = new ArrayList<>();
        ArrayDeque<BlockPos> q = new ArrayDeque<>(); q.add(start); seen.add(start);
        while (!q.isEmpty()) { BlockPos c = q.poll(); out.add(c); for (Direction d : Direction.Plane.HORIZONTAL) { BlockPos n = c.relative(d); if (set.contains(n) && seen.add(n)) q.add(n); } }
        List<BlockPos> rest = new ArrayList<>(); for (BlockPos c : cells) if (!seen.contains(c)) rest.add(c);
        out.addAll(rimFirst(level, rest, door));
        return out;
    }

    /** Ore in the hole's walls he can see (or saw), and has the pick for: mined before the mouth goes shut. */
    static List<BlockPos> oreInWalls(ClaudeEntity g, ServerLevel level, Set<BlockPos> mouth, BlockPos door, Set<BlockPos> hole) {
        List<BlockPos> ores = new ArrayList<>(); Set<BlockPos> seen = new HashSet<>(), done = new HashSet<>();
        ArrayDeque<BlockPos> q = new ArrayDeque<>();
        int top = mouth.iterator().next().getY();
        for (BlockPos m : mouth) { q.add(m); seen.add(m); }
        while (!q.isEmpty() && seen.size() < 2000 && ores.size() < 8) {
            BlockPos c = q.poll();
            for (Direction d : Direction.values()) {
                BlockPos n = c.relative(d);
                if (n.getY() > top || n.getY() < top - 24 || across(n, door) > SPAN + 4 || seen.contains(n)) continue;
                if (open(level, n)) { seen.add(n); q.add(n.immutable()); if (n.getY() < top) hole.add(n.immutable()); continue; }
                BlockState st = level.getBlockState(n);
                if (!MineBlueprint.ore(st) || !done.add(n.immutable())) continue;
                if (!ClaudeLife.canSee(g, level, n) && !ClaudeLife.seenOre(g).contains(n)) continue; // (only what he can see: nothing through the rock)
                if (st.requiresCorrectToolForDrops() && g.inventory.find(x -> x.isCorrectToolForDrops(st)).isEmpty()) { ClaudeMod.LOGGER.info("[Claude ore] {} at {} in the hole's wall: no pick for it, it stays behind the cap (PitGuard.oreInWalls)", st.getBlock().getName().getString(), n.toShortString()); continue; }
                ores.add(n.immutable());
            }
        }
        return ores;
    }

    /** A closing half done (his queue cleared by a new step, a fight): the rest of the mouth queued again when he's by his door. */
    static void resume(ClaudeEntity g, ServerLevel level, State s) {
        if (s.door == null || across(g.blockPosition(), s.door) > 16) return;
        { String no = notNow(g, level, s.door); if (no != null) { if (!no.equals(s.notNowLast) || g.tickCount - s.notNowSaid > 20 * 60 * 5) { s.notNowSaid = g.tickCount; s.notNowLast = no; ClaudeMod.LOGGER.info("[Claude life] the hole by his door at {}: nothing queued for it now ({}) (PitGuard.resume)", s.door.toShortString(), no); } return; } }
        if (g.currentTask == null && g.tasks.isEmpty()) { // his torches in the hollow not in yet (a step that failed, his queue cleared): queued again
            List<BlockPos> dark = new ArrayList<>(); for (BlockPos p : s.lights) if (level.isLoaded(p) && level.getBlockState(p).isAir() && solid(level, p.below())) dark.add(p);
            boolean same = s.lightFrom != null && s.lightLeft == dark.size() && s.lightFrom.closerThan(g.blockPosition(), 3); // (queued again from here already and none went in since: not from this spot again)
            if (!dark.isEmpty() && !same && g.inventory.count(x -> x.is(Items.TORCH)) > 0) { s.lightFrom = g.blockPosition().immutable(); s.lightLeft = dark.size(); for (BlockPos p : dark) g.attempts.forget(Attempts.Kind.PLACE, p); for (int i = dark.size() - 1; i >= 0; i--) g.tasks.addFirst(new Task.Place(dark.get(i), Items.TORCH)); ClaudeMod.LOGGER.info("[Claude life] the hollow by his door: {} of his torches not in yet ({}): queued again from {} (PitGuard.resume)", dark.size(), dark.stream().map(BlockPos::toShortString).toList(), g.blockPosition().toShortString()); return; }
        }
        List<BlockPos> left = pending(g, level, s);
        if (left.isEmpty()) return;
        if (g.currentTask instanceof Task.Place p0 && s.caps.contains(p0.pos())) return;
        for (Task t : g.tasks) if (t instanceof Task.Place p1 && s.caps.contains(p1.pos())) return; // (still queued)
        if (!(g.currentTask == null && g.tasks.isEmpty()) && !(g.currentTask instanceof Task.GoTo)) return; // (busy with something else: when it's done, or on a walk by his door)
        boolean up = g.onGround() && Math.abs(g.getBlockY() - s.door.getY()) <= 1; // (up by his door now: a cell that failed from down in the hole gets its go from here)
        if (s.resumedFrom != null && s.resumedLeft == left.size() && s.resumedFrom.closerThan(g.blockPosition(), 3)) return; // (queued again from here already and none went in since: not from this spot again)
        if (!up) left.removeIf(p -> g.attempts.within(Attempts.Kind.PLACE, p, g.tickCount, 20 * 300)); // (one that failed lately: doPlace said why; another go from up by his door)
        else { // up by his door: a cell that failed lately gets another go only when it failed from down in the hole, or a cap beside it went in since (a face to set it against now); failed from up here with nothing changed, it's the same failure again (live 07:52-07:54 world 14: the same cells queued again 6 times, each "can't see a face", doPlace 2844)
            List<BlockPos> same = new ArrayList<>(); List<String> seen = new ArrayList<>();
            for (BlockPos p : left) {
                Integer f = g.attempts.last(Attempts.Kind.PLACE, p);
                if (f == null || g.tickCount - f >= 20 * 300) continue;
                BlockPos stood = g.attempts.from(Attempts.Kind.PLACE, p);
                if (stood != null && stood.getY() < s.door.getY() - 1) continue; // (failed from down in the hole: from up here it's another view)
                boolean beside = false; for (Direction d : Direction.values()) { Integer in = s.capIn().get(p.relative(d)); if (in != null && in >= f) { beside = true; break; } }
                if (beside) continue;
                same.add(p); seen.add(p.toShortString() + " (" + (g.tickCount - f) / 20 + " s ago from " + (stood == null ? "?" : stood.toShortString()) + ")");
            }
            if (!same.isEmpty()) { left.removeAll(same); String k = same.toString(); if (!k.equals(s.sameSaid)) { s.sameSaid = k; ClaudeMod.LOGGER.info("[Claude life] the hole by his door: {} cell(s) of its mouth failed lately from up here and nothing beside them went in since: {}; not queued again from the same view (doPlace said why) (PitGuard.resume)", same.size(), seen); } }
        }
        if (left.isEmpty()) return;
        for (BlockPos p : left) g.attempts.forget(Attempts.Kind.PLACE, p);
        s.resumedFrom = g.blockPosition().immutable(); s.resumedLeft = left.size();
        if (stone(g) == 0) { if (g.tickCount - s.noStoneSaid > 20 * 60) { s.noStoneSaid = g.tickCount; ClaudeMod.LOGGER.info("[Claude life] the hole by his door still has {} open cell(s) in its mouth and no cobble on him: left for when he has some (PitGuard.resume)", left.size()); } return; }
        List<BlockPos> order = rimFirst(level, left, s.door);
        for (int i = order.size() - 1; i >= 0; i--) g.tasks.addFirst(new Task.Place(order.get(i), Items.COBBLESTONE));
        ClaudeMod.LOGGER.info("[Claude life] the hole by his door: {} cell(s) of its mouth still open, his queue lost them: queued again (PitGuard.resume)", order.size());
    }
}
