package com.claudemod.entity.life;

import com.claudemod.ClaudeMod;
import com.claudemod.entity.ClaudeEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The no-progress watchdog (queue item 1, 2026-10-31): one layer under every task and skill.
 *
 * Each action has its own progress measure: a walk, a dig, a placement or a leap gets nearer its target than his best on it
 * (by a block), or gets it done; anything gets an item, breaks a block or heals. ClaudeLife.noticeStuck reads that measure on a
 * clock. This class adds what the clock can't see: the same action (same kind, same target) ending again and again without
 * getting there. The live cases: a sprint-jump missed 5 times in 10 s, a long-walk hop re-picking its own target every 2 s, a
 * craft failing on the same missing item over and over. Each restart looked like a fresh task to the clock.
 *
 * The ladder, rung by rung, for one action that keeps failing:
 *  1. another way to it: digging, bridging, pillaring, swimming (ClaudeLife.escapeStuck, an ActionPath MAY_DIG plan);
 *  2. round it: the target written off in Attempts and its copies dropped, so the planner picks another spot for the job;
 *  3. tell them, once, exactly what failed and what he tried; never wait for an answer, the plan goes on without it.
 * Every trip is logged with "[Claude watchdog]".
 */
public final class Watchdog {
    /** No-progress ends of one action, inside this window, before it trips. */
    public static final int REPEATS = 3;
    public static final int WINDOW = 20 * 60;
    /** A tripped action stays tripped this long: started again inside it, it goes straight to the next rung. */
    public static final int MEMORY = 20 * 120;
    /** The same failed skill line this many times within SKILL_WINDOW: the skill-level trip. */
    public static final int SKILL_REPEATS = 2;
    public static final int SKILL_WINDOW = 20 * 300;

    static final class St {
        final java.util.Map<String, Integer> placeGaveUp = new java.util.HashMap<>(); // item|cell -> tick of its last give-up (PLACE_REPEAT)
        int dragTicks; String dragBlock = ""; BlockPos dragAt; // walking into a block (leaves, a wall) and sliding along it
        int wetSince = -1, wetWalkT; double wetX, wetY, wetZ;              // in water since, from where
        String wetDoing = "nothing"; boolean wetWalk;             // what he was at in the water (the walk ends as he lands)
        int idleSince = -1; double idleX, idleZ;                  // no task, no queue, standing, since (IDLE)
        long stillPos = Long.MIN_VALUE; BlockPos stillWant; int stillSince, stillBroken; // same block, the same walk queued, nothing dug, since (STUCK)
        int startEst; String startWhat = "";                      // the running action's player-pace estimate in ticks, and what it is (SLOW)
        int tripAt = -1, tripBroken, tripItems; BlockPos tripPos; String tripWhat = ""; double legRun; // legRun: the legs of one walk so far (a leg with the next one queued is no trip) // a 16+ block walk that got there: something done within 10 s? (WASTED_TRIP)
        int handRun;                                              // tool blocks broken by hand in a row (BARE_HANDS)
        final Map<UUID, Integer> tossed = new HashMap<>();        // his junk throws still on the ground: entity -> tick (THROW_LOOP)
        final Map<net.minecraft.world.item.Item, Integer> tossedItem = new HashMap<>(); // last throw tick per item (THROW_LOOP)
        final Map<BlockPos, int[]> placedAt = new HashMap<>();    // his placements: {tick, blocks broken then, items then} (PLACE_BREAK)
        Object stepRef; String stepName = ""; int stepStart, stepBudget, stepExcused, stepNoTask; final Map<String, int[]> stepUse = new HashMap<>(); // the plan step under way: its player-pace budget and where its time went {ticks, budget} (PACE)
        int waitSince = -1, waitBroken; BlockPos waitAt;          // at a furnace, waiting (FURNACE_WAIT)
        final java.util.ArrayDeque<int[]> walks = new java.util.ArrayDeque<>(); // the last walks: {end tick, from x, z, to x, z, broken, items} (WASTED_WALK)
        double walked, lastWX = Double.NaN, lastWZ, lastWX0, lastWZ0; double pathLen = -1; // the walk under way: distance walked, its first path's length (ROUTE_DETOUR)
        final Map<String, Integer> fetched = new HashMap<>();     // chest|item -> tick he took it out (REPEAT_FETCH)
        final Map<String, int[]> jobs = new HashMap<>();          // skill target -> {tick it began, step identity} (SPLIT_JOB)
        final Map<String, int[]> ends = new HashMap<>();     // action key -> {no-progress ends, first tick}
        final Map<String, int[]> rung = new HashMap<>();     // action key -> {rung reached, tick}
        final Map<String, int[]> skills = new HashMap<>();   // failed skill line -> {times, first tick}
        final Map<String, Long> lastFeet = new HashMap<>();
        final Map<String, double[]> lastEnd = new HashMap<>(); // dig/place key -> {blocks broken, items, best distance, tick} at its last end
        final Map<String, String> tried = new HashMap<>();   // action key -> the ways tried so far, for the line to them
        final Map<String, String> what = new HashMap<>();    // action key -> what failed and how, from its trip
        final java.util.Set<String> told = new java.util.HashSet<>();
        int airRouteTick = -100000;                          // when drowningOut queued a dug way to air (the swim-up reflex stands aside for 10 s)
        BlockPos airRouteTo; double airRouteD, airRouteBest; int airRouteBestTick; // its air cell, his eyes' distance to it then, the nearest since and when
        final Map<String, int[]> means = new HashMap<>();    // action key -> {ends excused as "something in front of it first", first tick}
        Task started; int startTick, startBroken, startItems; double startDist; // the action now running, as it began
        Map<String, Integer> bagAtStart;                     // (a chest action only: what he had going in)
        int broken;                                          // blocks he's broken (ClaudeLife.noteBroken)
        String pending;                                      // a line to say on the next ClaudeLife tick (it has the player)
        final java.util.ArrayDeque<double[]> samples = new java.util.ArrayDeque<>(); // {tick, x, y, z, health, food, fire ticks}, one a second, the last 60
        double px = Double.NaN, py, pz; int pTick; Object pDim; int handOff = -100000; // (the last tick he was handing things over: give, a chest, a deposit)          // his position last tick (the silent-move check)
        java.util.UUID atkTarget; int atkSince; float atkHp; // the fight under way: whom, since when, its health then
        float plannedHp; int plannedAt = -100000; // a planned drop's damage as Leaps planned it (HEALTH FALLING leaves it out)
        int lastFall = -100000, lastNoEffect = -100000, lastMove = -100000; // (each detector says so at most every 30 s)
        java.util.Map<String, Integer> gear;                 // his important gear by item, a second ago
        String quickLine; int quickTick, quickN;             // a skill line coming straight back
        String lastTold = ""; int lastTellTick = -1;                                // (stages read it)
        int trips;
        Task lastTargeted; int lastTargetedTick;             // the last task that had a cell to get to (a give-up often fires after its task was replaced)
        final java.util.ArrayDeque<Object[]> trace = new java.util.ArrayDeque<>(); // {first tick, last tick, line}: his body tick by tick and his tasks' starts and ends, the last 10 s (every incident prints it)
    }
    private static final Map<UUID, St> ST = new HashMap<>();
    private static int now(ClaudeEntity g) { return g.getServer().getTickCount(); } // ponytail: his own tickCount restarts at 0 on every dimension change; this state outlives the entity
    private static St st(ClaudeEntity g) { return ST.computeIfAbsent(g.getUUID(), k -> new St()); }
    public static void reset(ClaudeEntity g) { ST.remove(g.getUUID()); }
    /** Parked lifted this job (the block arrived, he moved off, time passed): its trip and write-off go with it, so it runs again. */
    public static void cleared(ClaudeEntity g, Task t) { St s = ST.get(g.getUUID()); String k = key(t); if (s == null || k == null) return; s.rung.remove(k); s.ends.remove(k); s.told.remove(k); }
    public static int incidents; public static String lastIncident = ""; // (stages read them)
    public static final java.util.ArrayDeque<String> recentKinds = new java.util.ArrayDeque<>(); // the last 50 incidents' kinds (stages)
    public static String lastTold(ClaudeEntity g) { St s = ST.get(g.getUUID()); return s == null ? "" : s.lastTold; }
    public static int trips(ClaudeEntity g) { St s = ST.get(g.getUUID()); return s == null ? 0 : s.trips; }
    /** That action written off by the watchdog (tripped, within its 2 minutes): its source should not queue it again. */
    public static boolean off(ClaudeEntity g, Task t) { String k = key(t); return k != null && rung(g, k) > 0; }
    /** A block to break that a picker should pass over: written off here, or its reach or break failed in the last 2 minutes. The obsidian picker chose a written-off block 3 times a second (13:05 and 13:09, 2026-11-02) until the whole skill tripped. */
    public static boolean skipBreak(ClaudeEntity g, BlockPos p) { return off(g, new Task.Break(p)) || g.attempts.within(Attempts.Kind.REACH, p, g.tickCount, MEMORY) || g.attempts.within(Attempts.Kind.BREAK, p, g.tickCount, MEMORY); }
    public static int rung(ClaudeEntity g, String key) { St s = ST.get(g.getUUID()); int[] r = s == null ? null : s.rung.get(key); return r == null || now(g) - r[1] > MEMORY ? 0 : r[0]; }

    // ---------------------------------------------------------------- the measures

    /** What the action is for, as a cell: a walk's spot, a dig's block, a placement's cell, a leap's landing; null: no target. */
    public static BlockPos target(Task t) {
        return t instanceof Task.GoTo x ? x.pos() : t instanceof Task.Break x ? x.pos() : t instanceof Task.Place x ? x.pos() : t instanceof Task.Leap x ? x.land()
                : t instanceof Task.UseDoor x ? x.pos() : t instanceof Task.Chest x ? x.pos() : t instanceof Task.Deposit x ? x.pos() : t instanceof Task.Till x ? x.pos()
                : t instanceof Task.WallTorch x ? x.pos() : t instanceof Task.Replant x ? x.pos() : t instanceof Task.FurnaceTake x ? x.pos() : t instanceof Task.FurnaceLoad x ? x.pos() : null;
    }
    /** How far his feet are from the action's target; -1: no target (the clock's move measure applies). */
    public static double targetDist(ClaudeEntity g, Task t) { BlockPos p = t == null ? null : target(t); return p == null ? -1 : Math.sqrt(p.distToCenterSqr(g.position())); }

    /** One action, whatever record carries it: the same leap re-planned or the same walk re-issued is the same action. */
    public static String key(Task t) {
        if (t instanceof Task.GoTo x) return "walk to " + x.pos().toShortString();
        if (t instanceof Task.Break x) return "break " + x.pos().toShortString();
        if (t instanceof Task.Place x) return "place at " + x.pos().toShortString();
        if (t instanceof Task.Leap x) return com.claudemod.entity.nav.Leaps.kindName(x.kind()) + " from " + x.from().toShortString() + " to " + x.land().toShortString();
        return null;
    }

    /** Did the ended action get what it was for? null: it can't be told from here (no verdict, nothing counted). */
    public static Boolean achieved(ClaudeEntity g, Task t) {
        var level = g.level();
        BlockPos feet = BlockPos.containing(g.getX(), g.getY() + 0.2, g.getZ());
        if (t instanceof Task.GoTo x) return Math.sqrt(x.pos().distToCenterSqr(g.position())) <= x.within() + 1.5;
        if (t instanceof Task.Break x) { var s = level.getBlockState(x.pos()); return s.isAir() || s.canBeReplaced(); }
        if (t instanceof Task.Place x) { var b = net.minecraft.world.level.block.Block.byItem(x.item()); return b == net.minecraft.world.level.block.Blocks.AIR ? null : level.getBlockState(x.pos()).is(b); }
        if (t instanceof Task.Leap x) return Math.abs(feet.getX() - x.land().getX()) + Math.abs(feet.getZ() - x.land().getZ()) <= 1 && Math.abs(feet.getY() - x.land().getY()) <= 1;
        return null;
    }

    // ---------------------------------------------------------------- the action layer (WorkGoal)

    /**
     * WorkGoal is about to start this action. A tripped one (still in memory) isn't run as it was: false means drop it, the
     * ladder has queued the next way (or written it off). True: run it.
     */
    public static void noteBroken(ClaudeEntity g) { st(g).broken++; }
    /** A walk ended again no nearer than his best on that walk within the window (a step cut and climbed back is no gain; blocks broken on the way don't count for a walk). Nearer by a block, and its count starts over. */
    private static boolean walkNoGain(St s, String k, ClaudeEntity g, Task t) {
        double d = targetDist(g, t); double[] last = s.lastEnd.get(k);
        boolean same = last != null && now(g) - last[3] <= WINDOW && d > last[2] - 1.0;
        s.lastEnd.put(k, new double[] {0, 0, same ? Math.min(d, last[2]) : Math.min(d, s.startDist), now(g)});
        if (!same && last != null) s.ends.remove(k);
        return true;
    }
    /** The task put itself back with something in front of it (a step out of the cell, a walk to a stand spot, a wait, the block in front): its own bounded retry, not the loop (the gate's nook move: a double chest's two cells and his feet, stepped round in 2 s). */
    private static boolean meansFirst(ClaudeEntity g, Task t) { Task head = g.tasks.peek(); return head != null && !head.equals(t) && g.tasks.contains(t); }
    /** meansFirst, excused 3 times per action in the window; past that, what it keeps putting in front isn't working (a swim to the shore in front of the same walk, 3 h at 541,12,126 on 2026-11-02, never counted). */
    private static boolean meansAgain(St s, String k, ClaudeEntity g, Task t) {
        if (!meansFirst(g, t)) return false;
        int[] m = s.means.get(k); if (m == null || now(g) - m[1] > WINDOW) { m = new int[] {0, now(g)}; s.means.put(k, m); }
        return ++m[0] <= 3;
    }
    /** The same dig or placement ended again with nothing gained since its last end: no block broken, no item got, not nearer its cell, and his feet in the same cell. Any of those, and its count starts over (the block in front cleared first, a nudge to a stand spot: progress). */
    private static boolean repeatNoGain(St s, String k, ClaudeEntity g, Task t) {
        double d = targetDist(g, t); int it = items(g); long feet = BlockPos.containing(g.getX(), g.getY() + 0.2, g.getZ()).asLong();
        double[] last = s.lastEnd.get(k);
        boolean same = last != null && last[0] == s.broken && last[1] == it && d > last[2] - 1.0 && now(g) - last[3] <= WINDOW && s.lastFeet.getOrDefault(k, Long.MIN_VALUE) == feet; // (standing where he stood: a step out of the cell first, a nudge to a stand spot, moves him; that's a means, not the loop)
        s.lastEnd.put(k, new double[] {s.broken, it, Math.min(d, last != null && same ? last[2] : d), now(g)}); s.lastFeet.put(k, feet); // (the cell as a long, apart: a double loses it at these coordinates)
        if (!same && last != null) s.ends.remove(k);
        return true;
    }
    private static int items(ClaudeEntity g) { int n = 0; for (int i = 0; i < g.inventory.items.getContainerSize(); i++) n += g.inventory.items.getItem(i).getCount(); return n; }
    public static boolean starting(ClaudeEntity g, Task t) {
        St s0 = st(g); s0.started = t; s0.startTick = now(g); s0.startBroken = s0.broken; s0.startItems = items(g); s0.startDist = targetDist(g, t);
        if (t instanceof Task.Chest) { s0.bagAtStart = new HashMap<>(); for (int i = 0; i < g.inventory.items.getContainerSize(); i++) { var it = g.inventory.items.getItem(i); if (!it.isEmpty()) s0.bagAtStart.merge(it.getHoverName().getString(), it.getCount(), Integer::sum); } } else s0.bagAtStart = null;
        try { paceAndSight(g, s0, t); } catch (RuntimeException e) { ClaudeMod.LOGGER.warn("[Claude watchdog] pace estimate threw", e); }
        if (target(t) != null) { s0.lastTargeted = t; s0.lastTargetedTick = now(g); }
        record(s0, now(g), "START " + t + (s0.startDist >= 0 ? String.format(" (%.1f off)", s0.startDist) : ""));
        String k = key(t); if (k == null) return true;
        int r = rung(g, k); if (r == 0) return true;
        Boolean got = achieved(g, t); if (Boolean.TRUE.equals(got) && !(t instanceof Task.GoTo)) return true; // (already done: the task ends at once by itself)
        if (t instanceof Task.GoTo && r < 2) return true; // a walk on rung 1 runs behind its MAY_DIG way (that's the way)
        if (r >= 3) { StringBuilder nx = new StringBuilder(); int i = 0; for (Task q : g.tasks) { if (i++ >= 4) break; nx.append(q).append(" by ").append(ClaudeEntity.originOf(q)).append("; "); }
            ClaudeMod.LOGGER.info("[Claude watchdog] {} picked again, written off and told: not run; queued by {}, current {}, next [{}] (Watchdog.starting)", k, ClaudeEntity.originOf(t), g.currentTask, nx); return false; } // (who keeps queuing it: 326 of these in 31 s at 07:21 11-05, REVIEW-145, its source never named)
        ClaudeMod.LOGGER.info("[Claude watchdog] {} again after it tripped (rung {}): not run as it was, next way (Watchdog.starting)", k, r);
        climb(g, t, k, ClaudeChat_describe(g, t) + ": picked again after it kept failing");
        return false;
    }

    /** WorkGoal: the action ended (why: its exit line, or null). Counts it if it ended without its progress. */
    public static void ended(ClaudeEntity g, Task t, String why) {
        { double d = targetDist(g, t); record(st(g), now(g), "END " + t + " after " + (g.work == null ? "?" : g.work.taskAge()) + "t" + (d >= 0 ? String.format(", %.1f off", d) : "") + (why == null ? "" : ": " + why)); }
        try { endChecks(g, st(g), t); } catch (RuntimeException e) { ClaudeMod.LOGGER.warn("[Claude watchdog] end checks threw", e); }
        String k = key(t); if (k == null) return;
        Boolean got = achieved(g, t); if (got == null) return;
        St s = st(g);
        if (got) { s.ends.remove(k); return; }
        // Counted: a leap that came down off its landing (or wasn't taken), and a walk that ran and ended no nearer, nothing
        // broken or picked up on the way. Not counted: digs and placements (their exits re-queue them by design: the block in
        // front first, a nudge to a stand spot; the clock covers them), and a walk that ended at once (the walker turned it down
        // itself: its own "skipped this time (n of 3)" and MindLog's conflict line cover those).
        boolean mine = t.equals(s.started); // (the same action, the record that started it: by value, as the keys are)
        int ran = mine ? now(g) - s.startTick : 0;
        boolean madeSome = mine && (s.broken != s.startBroken || items(g) != s.startItems);
        boolean counted = t instanceof Task.Leap
                || t instanceof Task.GoTo && mine && ran > 3 && !meansAgain(s, k, g, t) && walkNoGain(s, k, g, t) // (nearer than his best on that walk over the window, or it's no progress: a one-block step cut and undone the next moment isn't; live 09:48 World 15, 21 "stepping off" in 80 s, net nothing) // (a leg that hands over to its own route's digs, blocks broken between legs and him nearer: progress; live 06:47 World 15 digging stairs home, 130 -> 113 off, tripped every few seconds)
                || (t instanceof Task.Break || t instanceof Task.Place) && mine && !madeSome && !meansAgain(s, k, g, t) && repeatNoGain(s, k, g, t); // (a dig or a placement refused and re-queued with nothing broken, nothing got and no nearer since its last end: live 03:18 World 15, 270 refused digs and the watchdog never saw them)
        if (!counted) { if (t instanceof Task.GoTo || t instanceof Task.Leap) ClaudeMod.LOGGER.info("[Claude watchdog] {} ended short, not counted ({}) (Watchdog.ended)", k, !mine || ran <= 3 ? (why != null ? "ended at once: " + why : "ended at once: the walker's own refusal") : madeSome ? "broke or got something on the way" : meansFirst(g, t) ? "something queued in front of it first" : "got nearer"); return; } // (the rung stays for MEMORY: it came good, but a fresh trip on it climbs on)
        int[] e = s.ends.get(k);
        if (e == null || now(g) - e[1] > WINDOW) { e = new int[] {0, now(g)}; s.ends.put(k, e); }
        e[0]++;
        ClaudeMod.LOGGER.info("[Claude watchdog] {} ended without getting there ({}): {} in {} s (Watchdog.ended)", k, why == null ? "no reason given" : why, e[0], (now(g) - e[1]) / 20);
        if (e[0] >= REPEATS) { s.ends.remove(k); trip(g, t, k, e[0] + " times in " + Math.max(1, (now(g) - e[1]) / 20) + " s" + (why == null ? "" : " (" + why + ")")); }
    }

    /**
     * MindLog's [Claude conflict] (the same thing refused 3 times in 60 s). Logged against the running action only: a refusal is
     * one of his own rules holding him, and the next ways (digging to it, going round) would walk him through that rule (the gate:
     * a follow walk held in place, written off, sent him off; a dig re-queued behind the block in front is progress).
     */
    public static void conflict(ClaudeEntity g, BlockPos pos, String job) {
        Task t = g.currentTask; if (t == null || !pos.equals(target(t))) return;
        ClaudeMod.LOGGER.info("[Claude watchdog] conflict on the running action {}: {} (logged, not acted on) (Watchdog.conflict)", key(t), job);
    }

    /** The clock (ClaudeLife.noticeStuck, 45 s) dropped this action after his own way out didn't get him nearer: on the ladder at "gone round", so picking it again tells them. */
    public static void writtenOff(ClaudeEntity g, String k, String what) {
        if (k == null) return;
        St s = st(g); s.trips++;
        s.rung.put(k, new int[] {2, now(g)}); s.what.put(k, what);
        s.tried.put(k, "the straight way, his own way out (digging, bridging, pillaring), dropping it for another");
        ClaudeMod.LOGGER.info("[Claude watchdog] {}: written off by the clock ({}): picked again, they're told (Watchdog.writtenOff)", k, what);
    }

    private static void trip(ClaudeEntity g, Task t, String k, String how) {
        St s = st(g); s.trips++;
        String what = ClaudeChat_describe(g, t) + ": " + how;
        ClaudeMod.LOGGER.info("[Claude watchdog] TRIP {}: {}", k, what);
        incident(g, "TRIP", k + ": " + what);
        s.what.put(k, what);
        mark(g, t);
        climb(g, t, k, what);
    }

    /** The failed target in Attempts, by kind (the planners read these: another tree, another stand spot, the way round). */
    private static void mark(ClaudeEntity g, Task t) {
        BlockPos at = g.blockPosition();
        if (t instanceof Task.GoTo x) g.attempts.fail(Attempts.Kind.WALK, x.pos(), g.tickCount, at);
        else if (t instanceof Task.Break x) g.attempts.fail(Attempts.Kind.REACH, x.pos(), g.tickCount, at);
        else if (t instanceof Task.Place x) g.attempts.fail(Attempts.Kind.PLACE, x.pos(), g.tickCount, at);
        else if (t instanceof Task.Leap x) g.attempts.fail(Attempts.Kind.WALK, x.land(), g.tickCount, x.from());
    }

    /** One rung up the ladder for this action. */
    private static void climb(ClaudeEntity g, Task t, String k, String what) {
        St s = st(g);
        int[] r = s.rung.get(k); if (r == null || now(g) - r[1] > MEMORY) r = new int[] {0, 0};
        r[0]++; r[1] = now(g); s.rung.put(k, r);
        if (r[0] > 3) r[0] = 3;
        String tried = s.tried.getOrDefault(k, "the straight way");
        if (r[0] == 1 && !(t instanceof Task.Leap) && com.claudemod.director.ClaudeLife.escapeStuck(g, (ServerLevel) g.level(), t)) {
            s.tried.put(k, tried + ", a way of his own planned to it (digs, bridges or a pillar where it needed them)");
            ClaudeMod.LOGGER.info("[Claude watchdog] {}: rung 1, his own way to it (dig, bridge, pillar, swim) in front (Watchdog.climb)", k);
            return;
        }
        if (r[0] <= 2) {
            boolean noOwnWay = r[0] == 1 && !(t instanceof Task.Leap); // (escapeStuck just looked and found none; a second trip had its plan already, a leap never asks)
            r[0] = 2;
            int n = 0; for (var it = g.tasks.iterator(); it.hasNext(); ) if (k.equals(key(it.next()))) { it.remove(); n++; }
            mark(g, t);
            s.tried.put(k, tried + (noOwnWay ? ", no way to dig, bridge or pillar to it found" : "") + ", going round"); // (was "digging/bridging (no way found)" whatever had been tried: D2D reviews 16 and 84, a dig dropped after 1 tick and no bridge, said as tried)
            ClaudeMod.LOGGER.info("[Claude watchdog] {}: rung 2, written off ({} queued copies dropped): the way round, another spot for the job (Watchdog.climb)", k, n);
            return;
        }
        tell(g, k, "stuck: " + s.what.getOrDefault(k, what) + "; tried " + s.tried.getOrDefault(k, tried) + ". moving on to something else, a hand there would help");
        for (var it = g.tasks.iterator(); it.hasNext(); ) if (k.equals(key(it.next()))) it.remove();
    }

    // ---------------------------------------------------------------- the skill layer (ClaudeLife's plan steps)

    /** A plan step's result: the same failure again inside SKILL_WINDOW, nothing it lacked come in, is no progress on that step. */
    public static void skillRan(ClaudeEntity g, Skills.Step step, String result) {
        if (result == null) return;
        String sk = step.skill() + (step.target() == null || step.target().isBlank() ? "" : " " + step.target());
        St s = st(g);
        if (!result.startsWith("failed")) { if (result.startsWith("done") || result.startsWith("crafted") || result.startsWith("smelted")) { s.skills.keySet().removeIf(x -> x.startsWith(sk + "|")); s.rung.keySet().removeIf(x -> x.startsWith("skill " + sk + "|")); } return; }
        if (result.contains("it's night") || result.contains("it's day,")) return; // (a wait, not a failure)
        String k = sk + "|" + result.replaceAll("\\d+", "#"); // (counts in the line aren't a new failure)
        int[] e = s.skills.get(k);
        if (e == null || now(g) - e[1] > SKILL_WINDOW) { e = new int[] {0, now(g)}; s.skills.put(k, e); }
        e[0]++;
        ClaudeMod.LOGGER.info("[Claude watchdog] {} -> {}: {} time(s) in {} s (Watchdog.skillRan)", sk, result, e[0], (now(g) - e[1]) / 20);
        if (e[0] < SKILL_REPEATS) return;
        s.trips++;
        String fk = sk; String lacks = missing(result);
        int r = s.rung.containsKey("skill " + k) && now(g) - s.rung.get("skill " + k)[1] <= SKILL_WINDOW ? s.rung.get("skill " + k)[0] : 0; // (a trip hours old starts over at rung 0, its recoveries included, as the action layer forgets after MEMORY)
        s.rung.put("skill " + k, new int[] {r + 1, now(g)});
        if (r == 0 && "craft".equals(step.skill()) && lacks != null && gatherable(lacks) == null) { // the next way for a craft short of what it's made of: obtain it, the whole chain worked out (mine, smelt, then craft), not the bare craft again (live World 15: "craft iron_pickaxe -> missing iron_ingot" with raw iron and coal on him; "craft diamond_leggings -> missing diamond", 5 of 7)
            g.skillQueue.addFirst(new Skills.Step("obtain", step.target(), Math.max(1, step.count())));
            g.nextPlanTick = Math.min(g.nextPlanTick, g.tickCount);
            ClaudeMod.LOGGER.info("[Claude watchdog] TRIP {}: \"{}\" {} times: obtain {} instead (its {} smelted, mined or made first) (Watchdog.skillRan)", sk, result, e[0], step.target(), lacks);
            return;
        }
        if (r == 0 && lacks == null && (result.contains("no building blocks") || result.matches("(?s).*collect (logs|stone|cobblestone|cobble)( or (logs|stone|cobblestone))? first.*"))) { // ("build_here -> failed: no building blocks (collect logs or stone first)": the line says what to get)
            g.skillQueue.addFirst(step); g.skillQueue.addFirst(new Skills.Step("collect", "cobblestone", 16));
            g.nextPlanTick = Math.min(g.nextPlanTick, g.tickCount);
            ClaudeMod.LOGGER.info("[Claude watchdog] TRIP {}: \"{}\" {} times: cobblestone first, then the step again (Watchdog.skillRan)", sk, result, e[0]);
            return;
        }
        if (r == 0 && lacks != null && gatherable(lacks) != null) { // the next way: get what it lacks first, then the step again
            String gat = gatherable(lacks);
            g.skillQueue.addFirst(step);
            g.skillQueue.addFirst(new Skills.Step("collect", gat, gat.equals("logs") ? 6 : 8));
            g.nextPlanTick = Math.min(g.nextPlanTick, g.tickCount);
            ClaudeMod.LOGGER.info("[Claude watchdog] TRIP {}: \"{}\" {} times: the {} it lacks first (collect {}), then the step again (Watchdog.skillRan)", sk, result, e[0], lacks, gat);
            return;
        }
        g.failCount.put(fk, 3); // (the plan runner skips a step at 3: the planner picks another way)
        String line = sk.replace('_', ' ') + " keeps failing: " + result.substring(result.indexOf(':') + 1).trim() + " (" + e[0] + " times in " + Math.max(1, (now(g) - e[1]) / 20) + " s"
                + (r > 0 ? ", " + triedFirst(step.skill(), lacks) + " and it still failed" : "") + "). doing something else for now, could you help with that?";
        ClaudeMod.LOGGER.info("[Claude watchdog] TRIP {}: \"{}\" {} times: skipped, the planner told, them told (Watchdog.skillRan)", sk, result, e[0]);
        tell(g, "skill " + sk, line);
        s.skills.remove(k);
    }

    /** "missing cobblestone" / "you are missing 3 oak planks" -> the thing it lacks; null if the line names none. */
    public static String missing(String result) {
        var m = java.util.regex.Pattern.compile("missing (?:\\d+ |#\\s?)?([a-z_ ]+?)(?:\\)|,|;|$| for | to | \\()").matcher(result.toLowerCase());
        return m.find() ? m.group(1).trim() : null;
    }
    /** What a collect step gets for it (the same words the craft skill's own gather uses), or null. */
    /** What rung 0 did before this trip, said as it was (live 03:41: "got more null first" when it had gone to obtain the iron). */
    static String triedFirst(String skill, String lacks) {
        return gatherable(lacks) != null ? "got more " + gatherable(lacks) + " first" : "craft".equals(skill) && lacks != null ? "went for the " + lacks.replace('_', ' ') + " first" : "tried another way first";
    }
    static String gatherable(String lacks) {
        if (lacks == null) return null;
        return lacks.contains("cobble") || lacks.equals("stone") ? "cobblestone" : lacks.contains("plank") || lacks.contains("log") || lacks.contains("stick") ? "logs" : null;
    }

    // ---------------------------------------------------------------- telling them

    /** Once per action, a line to them (said on the next ClaudeLife tick, which has the player). Never waits for an answer. */
    public static void tellOnce(ClaudeEntity g, String k, String line) { tell(g, k, line); }
    private static void tell(ClaudeEntity g, String k, String line) {
        St s = st(g);
        if (s.told.contains(k)) { ClaudeMod.LOGGER.info("[Claude watchdog] {}: already told them once, not again (Watchdog.tell)", k); return; }
        if (now(g) - s.lastTellTick < 20 * 120 && s.lastTellTick > 0) { ClaudeMod.LOGGER.info("[Claude watchdog] {}: told them something {} s ago, not another line yet (at most one every 2 min): \"{}\" (Watchdog.tell)", k, (now(g) - s.lastTellTick) / 20, line); return; } // (live 06:47: 12 "stuck" lines in 2 minutes)
        s.told.add(k); s.lastTellTick = now(g);
        s.pending = line; s.lastTold = line;
        com.claudemod.director.ClaudeLife.lesson(g, line);
        ClaudeMod.LOGGER.info("[Claude watchdog] telling them: \"{}\" (Watchdog.tell)", line);
    }
    /** ClaudeLife.noticeStuck: the pending line said. */
    public static void flush(ClaudeEntity g, ServerPlayer player) {
        St s = ST.get(g.getUUID()); if (s == null || s.pending == null || player == null) return;
        com.claudemod.director.ClaudeChat.say(player, s.pending, "watchdog: no progress");
        s.pending = null;
    }

    /** For the chat's truth: what he's on and how long it's gone nowhere; null if it's moving. */
    public static String stuckLine(ClaudeEntity g) {
        if (g.currentTask == null || g.progressTick <= 0) return null;
        int secs = (g.tickCount - g.progressTick) / 20;
        return secs < 10 ? null : ClaudeChat_describe(g, g.currentTask) + ", no nearer in " + secs + " s";
    }

    private static String ClaudeChat_describe(ClaudeEntity g, Task t) { try { return com.claudemod.director.ClaudeChat.describe(g, t); } catch (RuntimeException e) { return t.getClass().getSimpleName(); } }

    // ---------------------------------------------------------------- the every-tick detectors (WorkGoal.reflexes: any state)

    /** Important gear: iron or better tools, weapons and armor, a shield, a bow, flint and steel (the user's important-gear rule). One about to wear out isn't counted: its breaking is no loss to report. */
    static boolean important(net.minecraft.world.item.ItemStack st) {
        if (st.isEmpty()) return false;
        String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem()).getPath();
        if (st.isDamageableItem() && st.getMaxDamage() - st.getDamageValue() < st.getMaxDamage() / 10) return false;
        if (st.isDamageableItem()) return id.startsWith("iron_") || id.startsWith("diamond_") || id.startsWith("netherite_") || id.equals("shield") || id.equals("bow") || id.equals("crossbow") || id.equals("flint_and_steel");
        return false; // (diamonds, rods, pearls, eyes and buckets get used up in crafts and throws: no telling a loss from a use)
    }
    /** His important gear: pack, armor and off hand (a shield he put up there is still his: D2D bench 1103, stage 994). */
    private static java.util.Map<String, Integer> gearOf(ClaudeEntity g) {
        java.util.Map<String, Integer> m = new java.util.TreeMap<>();
        for (int i = 0; i < g.inventory.items.getContainerSize(); i++) { var st = g.inventory.items.getItem(i); if (important(st)) m.merge(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem()).getPath(), st.getCount(), Integer::sum); }
        for (var sl : net.minecraft.world.entity.EquipmentSlot.values()) { var st = g.getItemBySlot(sl); if ((sl.isArmor() || sl == net.minecraft.world.entity.EquipmentSlot.OFFHAND) && important(st)) m.merge(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem()).getPath(), st.getCount(), Integer::sum); }
        return m;
    }

    /** Every tick, from the hazard layer: what no single task watches. */
    // ---- log-line detectors (the user 11-05: "everything that constitutes a bug logged properly"; docs/bugs/coverage.md): a failed step, a conflict, litter he logged, each an incident once per kind and spot in 10 min
    private static final java.util.concurrent.ConcurrentLinkedQueue<String> LOGGED = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static final java.util.Map<String, Integer> filed = new java.util.HashMap<>();
    public static boolean linesInTests = false; // (off in the gate: every stage's failed lines would file one; the line stage turns it on)
    /** One [Claude ...] line (from MindLog's appender, any thread). */
    public static void logged(String m) { if (!m.startsWith("[Claude incident]") && lineKind(m) != null) LOGGED.add(m); }
    static String lineKind(String m) {
        if (m.startsWith("[Claude litter]")) return "LITTER";
        if (m.startsWith("[Claude move] spin") || m.startsWith("[Claude move] shuffle")) return "SPIN";
        if (m.startsWith("[Claude smelt] fuel")) return m.contains("(whole logs)") ? "LOG_FUEL" : null; // (the user 11-04: "turn them into planks first": a log is 1.5 items of fuel, its 4 planks 6)
        if (m.startsWith("[Claude conflict]")) return m.contains("in his cover") ? null : "CONFLICT"; // (the night hold's status line repeats by design: not a fight)
        return m.contains("-> failed") ? "FAILED" : null;
    }
    /** The rate-limit key: the kind, the spot for litter, and its words without numbers or the [Claude x] tag (a skill line and the watchdog's echo of it are one). */
    static String lineKey(String kind, String m) {
        var spot = java.util.regex.Pattern.compile("-?\\d+, -?\\d+, -?\\d+").matcher(m);
        String words = m.replaceFirst("^\\[Claude [a-z]+\\] ", "").replaceAll("\\([^()]*\\)", "").replaceAll("\\d+", "#").replaceAll("[^A-Za-z#]+", " ").trim(); // (punctuation differs between a line and its echo)
        return kind + "|" + (kind.equals("LITTER") && spot.find() ? spot.group() : "") + "|" + words.substring(0, Math.min(50, words.length())); // (litter: each spot is its own mess; a conflict or failure is one rule's bug wherever it hits: by spot it was 90 an hour, World 17)
    }
    private static void lineIncidents(ClaudeEntity g) {
        for (String m; (m = LOGGED.poll()) != null; ) {
            if (com.claudemod.entity.ClaudeSpawner.testing && !linesInTests) continue;
            String kind = lineKind(m);
            fileOnce(g, kind, lineKey(kind, m), m.length() > 400 ? m.substring(0, 400) : m);
        }
    }
    /** An incident once per key in 10 min. */
    private static void fileOnce(ClaudeEntity g, String kind, String k, String why) {
        Integer t0 = filed.get(k);
        if (t0 != null && now(g) - t0 < 20 * 600 && now(g) >= t0) return;
        if (filed.size() > 500) filed.clear();
        filed.put(k, now(g));
        incident(g, kind, why);
    }
    /**
     * Movement a player wouldn't make, logged (World 17: dragging along leaves, a slow river crossing, neither in the log):
     * pushing forward into a block for 1 s+ ([Claude move] dragging, incident DRAG), and every spell in water with its length and
     * time ([Claude move] in water, incident WATER over 5 s while on a walk).
     */
    private static void dragAndWater(ClaudeEntity g, St s) {
        if (g.horizontalCollision && Math.abs(g.zza) > 0.1f && !g.isInWater()) {
            if (s.dragTicks++ == 0) {
                net.minecraft.world.phys.Vec3 f = net.minecraft.world.phys.Vec3.directionFromRotation(0, g.getYRot());
                BlockPos ahead = BlockPos.containing(g.getX() + f.x * 0.6, g.getY() + 0.5, g.getZ() + f.z * 0.6);
                if (g.level().getBlockState(ahead).isAir()) ahead = ahead.above();
                s.dragAt = ahead.immutable(); s.dragBlock = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(g.level().getBlockState(ahead).getBlock()).getPath();
            }
            if (s.dragTicks == 20) { // (at 1 s, not at the end: pushing into a wall can go on for ever)
                String line = "dragging along " + s.dragBlock + " at " + s.dragAt.toShortString() + " for 1 s, now at " + g.blockPosition().toShortString() + ", doing " + (g.currentTask == null ? "nothing" : g.currentTask);
                ClaudeMod.LOGGER.info("[Claude move] {}", line);
                if (!com.claudemod.entity.ClaudeSpawner.testing || linesInTests) fileOnce(g, "DRAG", "DRAG|" + s.dragAt.asLong(), line);
            }
        } else s.dragTicks = 0;
        boolean wet = g.isInWater(); // (WATER files on the time walking in it: 50 s fishing in the shallows and a step out read as "52 s in water for 2 blocks", review 339; stage 996)
        if (wet && s.wetSince < 0) { s.wetSince = now(g); s.wetX = g.getX(); s.wetY = g.getY(); s.wetZ = g.getZ(); s.wetWalk = false; s.wetWalkT = 0; s.wetDoing = "nothing"; }
        if (wet && g.currentTask != null) { s.wetDoing = g.currentTask.toString(); if (g.currentTask instanceof Task.GoTo) { s.wetWalk = true; s.wetWalkT++; } }
        else if (!wet && g.onGround() && s.wetSince >= 0) { // (landed: swimming bobs him out of the water every stroke)
            int ticks = now(g) - s.wetSince; s.wetSince = -1;
            if (ticks >= 20) {
                double d = Math.sqrt(Math.pow(g.getX() - s.wetX, 2) + Math.pow(g.getZ() - s.wetZ, 2));
                String line = String.format("in water %.1f s, %.0f blocks from %.0f %.0f %.0f to %.0f %.0f %.0f, doing %s; walking %.1f s of it", ticks / 20.0, d, s.wetX, s.wetY, s.wetZ, g.getX(), g.getY(), g.getZ(), s.wetDoing, s.wetWalkT / 20.0);
                ClaudeMod.LOGGER.info("[Claude move] {}", line);
                if (s.wetWalkT > 20 * 5 && s.wetWalk && (!com.claudemod.entity.ClaudeSpawner.testing || linesInTests)) fileOnce(g, "WATER", "WATER|" + BlockPos.containing(s.wetX, s.wetY, s.wetZ).asLong(), line);
            }
        }
    }

    private static boolean on() { return !com.claudemod.entity.ClaudeSpawner.testing || linesInTests; }
    private static String nm(net.minecraft.world.level.block.state.BlockState st) { return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath(); }
    /** Any face of the block in his line of sight (a player sees a chest by its side, not only its middle). */
    public static boolean seen(ClaudeEntity g, BlockPos p) { // any of a face's centre and four corners in view: a player sees a face side-on too (live 07:59, a furnace beside his corridor)
        for (var d : net.minecraft.core.Direction.values()) for (int k = 0; k < 5; k++) {
            double u = k == 0 ? 0 : (k & 1) == 1 ? 0.4 : -0.4, v = k == 0 ? 0 : k < 3 ? 0.4 : -0.4;
            var at = net.minecraft.world.phys.Vec3.atCenterOf(p).add(d.getStepX() * 0.45 + (d.getAxis() == net.minecraft.core.Direction.Axis.X ? 0 : u), d.getStepY() * 0.45 + (d.getAxis() == net.minecraft.core.Direction.Axis.Y ? 0 : d.getAxis() == net.minecraft.core.Direction.Axis.X ? u : v), d.getStepZ() * 0.45 + (d.getAxis() == net.minecraft.core.Direction.Axis.Z ? 0 : v));
            if (g.level().clip(new net.minecraft.world.level.ClipContext(g.getEyePosition(), at, net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, g)).getBlockPos().equals(p)) return true;
        }
        return false;
    }
    /** A player's ticks to break this block with the best tool he carries (no enchantments: the estimate errs long). */
    static int breakTicks(ClaudeEntity g, BlockPos p, net.minecraft.world.level.block.state.BlockState st) {
        float h = st.getDestroySpeed(g.level(), p); if (h < 0) return -1; if (h == 0) return 1;
        float best = 1f; boolean harvest = !st.requiresCorrectToolForDrops();
        for (int i = 0; i < g.inventory.items.getContainerSize(); i++) { var it = g.inventory.items.getItem(i); best = Math.max(best, it.getDestroySpeed(st)); if (it.isCorrectToolForDrops(st)) harvest = true; }
        return (int) Math.ceil(h * (harvest ? 30 : 100) / best);
    }
    private static final double PACE = Clocks.SPRINT / 20; // blocks a tick, a player sprinting
    /** Ore with every face against an opaque block: nothing a player could have seen it by. */
    public static boolean hiddenOre(net.minecraft.world.level.Level l, BlockPos p) {
        if (!l.getBlockState(p).is(net.neoforged.neoforge.common.Tags.Blocks.ORES)) return false;
        for (var d : net.minecraft.core.Direction.values()) { var n = l.getBlockState(p.relative(d)); if (!n.canOcclude() || n.isAir()) return false; }
        return true;
    }
    /** A placement given up (WorkGoal's "placing X at P gave up"): the same item at the same cell again within 10 s is PLACE_REPEAT (Day to Day review 98/15/29, 11-05: tables and saplings into a filled cell or his own). */
    /** The one of this tool or station he carries already, in good shape (worn under watch.dup_worn of its life), said for the line; null: none, or it's not a tool or station. */
    public static String dupHeld(ClaudeInventory inv, net.minecraft.world.item.Item target) {
        boolean kind = target instanceof net.minecraft.world.item.DiggerItem || target instanceof net.minecraft.world.item.SwordItem || target instanceof net.minecraft.world.item.ShieldItem
                || target == net.minecraft.world.item.Items.CRAFTING_TABLE || target == net.minecraft.world.item.Items.FURNACE || target == net.minecraft.world.item.Items.SMOKER || target == net.minecraft.world.item.Items.BLAST_FURNACE;
        if (!kind) return null;
        for (int i = 0; i < inv.items.getContainerSize(); i++) { var st = inv.items.getItem(i); if (!st.is(target)) continue;
            if (!st.isDamageableItem() || st.getDamageValue() < st.getMaxDamage() * Weights.w("watch.dup_worn", 0.75)) return st.isDamageableItem() ? (st.getMaxDamage() - st.getDamageValue()) + " of " + st.getMaxDamage() + " uses left" : st.getCount() + " packed"; }
        return null;
    }
    /** STEP_DONE_IGNORED (Day to Day, review 117: "strip_mine iron_ore x9 -> no iron yet: pushing my branch" with 14-20 raw iron on him): a step goes on with "no X yet" while his pack already holds its count of X (tools and armor not counted). */
    public static String stepDone(ClaudeEntity g, Skills.Step st, String result) {
        if (st == null || result == null || st.count() <= 0) return null;
        var m = java.util.regex.Pattern.compile("\\bno ([a-z_]+(?: [a-z_]+)?) yet").matcher(result.toLowerCase()); if (!m.find()) return null;
        String what = m.group(1).trim().replace(' ', '_'); int have = 0; java.util.Set<String> kinds = new java.util.TreeSet<>();
        for (int i = 0; i < g.inventory.items.getContainerSize(); i++) { var it = g.inventory.items.getItem(i); if (it.isEmpty() || it.isDamageableItem()) continue;
            String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(it.getItem()).getPath(); if (!id.contains(what)) continue; have += it.getCount(); kinds.add(id); }
        if (have < st.count()) return null;
        String d = String.format("step %s %s x%d said \"%s\" with %d %s on him already, at %s", st.skill(), st.target(), st.count(), result.length() > 120 ? result.substring(0, 120) : result, have, String.join("/", kinds), g.blockPosition().toShortString());
        if (on()) fileOnce(g, "STEP_DONE_IGNORED", "STEP_DONE_IGNORED|" + st.skill() + "|" + st.target() + "|" + now(g) / 1200, d);
        return d;
    }
    /** DUP_CRAFT (Day to Day, review 111/126): a tool or station made with one already on him in good shape. */
    public static void dupCraft(ClaudeEntity g, net.minecraft.world.item.Item target, String had) {
        if (!on()) return; String name = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(target).getPath();
        fileOnce(g, "DUP_CRAFT", "DUP_CRAFT|" + name + "|" + now(g) / 1200, String.format("made a %s with one on him already (%s); step %s, at %s", name, had, g.lastStep == null ? "-" : g.lastStep.skill() + " " + g.lastStep.target(), g.blockPosition().toShortString()));
    }
    public static void placeGaveUp(ClaudeEntity g, net.minecraft.world.item.Item item, BlockPos p, String why) {
        if (!on()) return; St s = st(g);
        String name = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).getPath(), k = name + "|" + p.asLong();
        if (s.placeGaveUp.size() > 128) s.placeGaveUp.clear();
        Integer t0 = s.placeGaveUp.put(k, now(g));
        if (t0 != null && now(g) - t0 <= 200) fileOnce(g, "PLACE_REPEAT", "PLACE_REPEAT|" + k, String.format("placing %s at %s gave up again %d s after the last time (%s); he's at %s", name, p.toShortString(), (now(g) - t0) / 20, why, g.blockPosition().toShortString()));
    }
    /** At an action's start: its player-pace estimate (SLOW), and ore targeted with no face open to see it by (SIGHT). */
    private static void paceAndSight(ClaudeEntity g, St s, Task t) {
        s.startEst = -1; s.startWhat = "";
        if (t instanceof Task.GoTo) { s.walked = 0; s.pathLen = -1; s.lastWX = g.getX(); s.lastWZ = g.getZ(); s.lastWX0 = g.getX(); s.lastWZ0 = g.getZ(); }
        if (on() && t instanceof Task.GoTo go && s.startDist >= 20 && g.lastStep != null && java.util.Set.of("craft", "obtain", "gear_up").contains(g.lastStep.skill())) { // a walk to an old crafting table with a new one on him (4 planks) (FAR_STATION; Day to Day review 260/27, 11-05: 24 and 51 blocks)
            int planks = g.inventory.count(x -> x.is(net.minecraft.tags.ItemTags.PLANKS)), logs = g.inventory.count(x -> x.is(net.minecraft.tags.ItemTags.LOGS));
            if (planks >= 4 || logs >= 1) for (BlockPos q : BlockPos.betweenClosed(go.pos().offset(-2, -1, -2), go.pos().offset(2, 1, 2))) if (g.level().isLoaded(q) && g.level().getBlockState(q).is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE)) {
                fileOnce(g, "FAR_STATION", "FAR_STATION|" + q.asLong(), String.format("off to the crafting table at %s, %.0f away, for step %s, with %d planks and %d logs on him (a table is 4 planks)", q.toShortString(), s.startDist, g.lastStep.skill(), planks, logs)); break; }
        }
        BlockPos st0 = t instanceof Task.FurnaceLoad x ? x.pos() : t instanceof Task.Smelt x ? x.pos() : t instanceof Task.Deposit x ? x.pos() : null;
        if (on() && st0 != null && s.startDist > 10) { // a furnace (an empty one) or a chest of his within 6 would do (FAR_STATION)
            boolean furnace = !(t instanceof Task.Deposit);
            for (BlockPos o : furnace ? g.myStations : g.storageChests) {
                if (o.equals(st0) || o.distToCenterSqr(g.position()) > 36 || !g.level().isLoaded(o)) continue;
                var be = g.level().getBlockEntity(o);
                boolean does = furnace ? be instanceof net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity f && f.getItem(0).isEmpty() && f.getItem(2).isEmpty() : be instanceof net.minecraft.world.level.block.entity.ChestBlockEntity;
                if (does) { fileOnce(g, "FAR_STATION", "FAR_STATION|" + st0.asLong(), String.format("off to the %s at %s, %.0f away, with his %s at %s %.0f off doing the same (%s)", furnace ? "furnace" : "chest", st0.toShortString(), s.startDist, furnace ? "empty furnace" : "chest", o.toShortString(), Math.sqrt(o.distToCenterSqr(g.position())), t)); break; }
            }
        }
        if (t instanceof Task.GoTo && s.startDist >= 8) { s.startEst = (int) (s.startDist / PACE); s.startWhat = "walk"; }
        if (t instanceof Task.Break b) {
            var st = g.level().getBlockState(b.pos()); int bt = breakTicks(g, b.pos(), st);
            if (bt > 0) { s.startEst = bt + (int) (Math.max(0, s.startDist - 4.5) / PACE); s.startWhat = "breaking " + nm(st); }
            if (on() && hiddenOre(g.level(), b.pos())) {
                fileOnce(g, "SIGHT", "SIGHT|" + b.pos().asLong(), "targeting " + nm(st) + " at " + b.pos().toShortString() + " with every face closed (he can't have seen it); he's at " + g.blockPosition().toShortString() + ", step " + (g.lastStep == null ? "-" : g.lastStep.skill() + " " + g.lastStep.target()));
            }
        }
    }
    /** At an action's end: slower than twice a player's pace (SLOW); a 16+ block walk arrived (armed for WASTED_TRIP); a chest/furnace used unseen (SIGHT). */
    private static void endChecks(ClaudeEntity g, St s, Task t) {
        if (!on() || !t.equals(s.started)) return;
        int ran = now(g) - s.startTick;
        boolean got = t instanceof Task.GoTo ? Boolean.TRUE.equals(achieved(g, t)) : t instanceof Task.Break && s.broken != s.startBroken;
        { int[] u = s.stepUse.computeIfAbsent(kindOf(t), k -> new int[2]); u[1] += budget(g, s, t, ran); } // (the step's budget: this action at a player's pace)
        if (got && s.startEst > 0 && ran > 1.5 * s.startEst && ran - s.startEst >= 200)
            fileOnce(g, "SLOW", "SLOW|" + s.startWhat, String.format("%s took %.1f s, a player %.1f s (%s, from %.1f off)", s.startWhat, ran / 20.0, s.startEst / 20.0, t, s.startDist));
        String step = g.lastStep == null ? "" : g.lastStep.skill();
        boolean leg = t instanceof Task.GoTo && got && (g.tasks.peekFirst() instanceof Task.GoTo || g.tasks.peekFirst() instanceof Task.Leap); // (one walk's legs, the next under way: judged at the walk's end; live 16:39, a flight a leg out of his mine, WASTED_TRIP at each; stage 920) (a jump next is his walk going on too: review 225, stage 992)
        double walked = s.startDist + (t instanceof Task.GoTo && got ? s.legRun : 0); s.legRun = leg ? walked : 0;
        if (t instanceof Task.GoTo && got && !leg && walked >= 16 && !g.commandActive && !g.holdingNight() && !java.util.Set.of("explore", "go_to", "follow", "come", "scout").contains(step)) {
            s.tripAt = now(g); s.tripBroken = s.broken; s.tripItems = items(g); s.tripPos = g.blockPosition().immutable(); s.tripWhat = String.format("%.0f blocks to %s (step %s)", walked, ((Task.GoTo) t).pos().toShortString(), step.isEmpty() ? "-" : step + " " + g.lastStep.target());
        }
        BlockPos c = t instanceof Task.Chest x ? x.pos() : t instanceof Task.Deposit x ? x.pos() : t instanceof Task.Smelt x ? x.pos() : t instanceof Task.FurnaceLoad x ? x.pos() : t instanceof Task.FurnaceTake x ? x.pos() : null;
        net.minecraft.world.item.Item in = t instanceof Task.FurnaceLoad x ? x.input() : t instanceof Task.Smelt x ? x.input() : null;
        if (in != null && items(g) != s.startItems) { // a load went in: more of it on him or in his chests, and no other load of it coming (SMALL_BATCH)
            int bag = g.inventory.count(x -> x.is(in)), chests = g.level() instanceof ServerLevel sl ? ChestLedger.count(g, sl, in) : 0;
            boolean more = g.tasks.stream().anyMatch(x -> x instanceof Task.FurnaceLoad f && f.input() == in || x instanceof Task.Smelt f2 && f2.input() == in);
            if (bag + chests >= 8 && !more) fileOnce(g, "SMALL_BATCH", "SMALL_BATCH|" + c.asLong(), "loaded " + Math.abs(s.startItems - items(g)) + " " + in + " into the furnace at " + c.toShortString() + " with " + bag + " more on him and " + chests + " in his chests, no other load of it queued (" + t + ")");
        }
        if (t instanceof Task.Chest ch && ch.take() && items(g) > s.startItems) { // the same chest, the same item, again within 5 min (REPEAT_FETCH)
            for (var e : gained(s, g).entrySet()) {
                String k = ch.pos().asLong() + "|" + e.getKey(); Integer t0 = s.fetched.put(k, now(g));
                if (t0 != null && now(g) - t0 <= 20 * 300) fileOnce(g, "REPEAT_FETCH", "REPEAT_FETCH|" + k, "took " + e.getKey() + " out of the chest at " + ch.pos().toShortString() + " again, " + (now(g) - t0) / 20 + " s after the last time (one trip for the lot would do)");
            }
            if (s.fetched.size() > 128) s.fetched.clear();
        }
        if (t instanceof Task.GoTo && got && s.pathLen > 0 && s.walked > 1.5 * s.pathLen && s.walked - s.pathLen > 8) // walked half as far again as the path he planned (ROUTE_DETOUR)
            fileOnce(g, "ROUTE_DETOUR", "ROUTE_DETOUR|" + ((Task.GoTo) t).pos().asLong(), String.format("walked %.0f blocks on a %.0f-block path to %s (%.0f straight)", s.walked, s.pathLen, ((Task.GoTo) t).pos().toShortString(), s.startDist));
        if (t instanceof Task.GoTo) { s.walks.addLast(new int[] {now(g), (int) s.lastWX0, (int) s.lastWZ0, g.getBlockX(), g.getBlockZ(), s.broken, items(g)}); while (s.walks.size() > 8) s.walks.removeFirst(); backtrack(g, s); }
        if (c != null && items(g) != s.startItems && Math.sqrt(c.distToCenterSqr(g.getEyePosition())) <= 6 && !seen(g, c))
            fileOnce(g, "SIGHT", "SIGHT|" + c.asLong(), "used the " + nm(g.level().getBlockState(c)) + " at " + c.toShortString() + " without seeing it (" + t + ", from " + g.blockPosition().toShortString() + ")");
    }
    public static int stuckTicks = 20 * 180; // (stage 811 shortens it)
    /** The same block 3 min while a walk waits in his queue, whatever task holds him (live 09:52-10:31: 40 min of planned cover Waits, no incident). Not holding the night, asleep, staying where told, or in their lesson. */
    private static void stuck(ClaudeEntity g, St s) {
        BlockPos want = g.currentTask instanceof Task.GoTo c ? c.pos() : null;
        if (want == null) for (Task t : g.tasks) if (t instanceof Task.GoTo q) { want = q.pos(); break; }
        long pos = g.blockPosition().asLong();
        boolean excused = g.holdingNight() || g.isSleeping() || g.staying || Consent.lesson(g) || !on();
        if (excused || want == null || pos != s.stillPos || !want.equals(s.stillWant) || s.broken != s.stillBroken) { s.stillPos = pos; s.stillWant = want; s.stillSince = now(g); s.stillBroken = s.broken; return; } // (digging from one spot is work: an obsidian pocket's 10 blocks)
        if (now(g) - s.stillSince >= stuckTicks)
            fileOnce(g, "STUCK", "STUCK|" + pos + "|" + want.asLong(), String.format("at %s for %d s with the walk to %s queued all that time, doing %s (queue %d, step %s)", g.blockPosition().toShortString(), (now(g) - s.stillSince) / 20, want.toShortString(), g.currentTask, g.tasks.size(), g.lastStep == null ? "-" : g.lastStep.skill() + " " + g.lastStep.target()));
    }
    /** No task, nothing queued, standing still 20 s+ (not holding the night, asleep, held by them, or thinking under 45 s). */
    private static void idle(ClaudeEntity g, St s) {
        boolean still = s.idleSince >= 0 && Math.abs(g.getX() - s.idleX) + Math.abs(g.getZ() - s.idleZ) < 2;
        boolean excused = g.holdingNight() || g.isSleeping() || g.staying || g.planning && g.tickCount - g.planStartTick < 20 * 45;
        if (g.currentTask != null || !g.tasks.isEmpty() || excused || !on()) { s.idleSince = -1; return; }
        if (!still) { s.idleSince = now(g); s.idleX = g.getX(); s.idleZ = g.getZ(); return; }
        if (now(g) - s.idleSince >= 20 * 20)
            fileOnce(g, "IDLE", "IDLE|" + g.blockPosition().getX() / 8 + "," + g.blockPosition().getZ() / 8, String.format("no task for %d s at %s%s; plan %s; last line %s", (now(g) - s.idleSince) / 20, g.blockPosition().toShortString(), g.planning ? ", thinking" : "", g.skillQueue, g.lastSkillLine == null ? "-" : g.lastSkillLine));
    }
    /** 10 s after a 16+ block walk got there: nothing broken, nothing gained, nothing handed over, and no work on that spot: a wasted trip. */
    private static void wastedTrip(ClaudeEntity g, St s) {
        if (s.tripAt < 0 || now(g) - s.tripAt < 200) return;
        int at = s.tripAt; s.tripAt = -1;
        if (!on() || s.broken != s.tripBroken || items(g) != s.tripItems || s.handOff >= at || g.isSleeping()) return;
        BlockPos w = g.currentTask == null || g.currentTask instanceof Task.GoTo ? null : target(g.currentTask);
        if (w != null && w.distSqr(s.tripPos) <= 36 || g.currentTask instanceof Task.Attack || g.currentTask instanceof Task.Fish || g.currentTask instanceof Task.Smelt) return; // (at work there)
        fileOnce(g, "WASTED_TRIP", "WASTED_TRIP|" + s.tripPos.asLong(), "walked " + s.tripWhat + " and did nothing there in 10 s; now " + (g.currentTask == null ? "no task" : g.currentTask) + " at " + g.blockPosition().toShortString());
    }
    /** He threw something out as junk (ClaudeLife's toss): the same one-of item thrown again within 2 min means he took it back (THROW_LOOP); armor, iron+ tools and ingots/gems thrown at all is THREW_VALUABLE (Day to Day 11-05, live 05:16-05:17: iron boots, golden boots, an iron helmet thrown three times). */
    public static void thrown(ClaudeEntity g, net.minecraft.world.entity.item.ItemEntity e) {
        if (e == null || !on()) return;
        St s = st(g); var it = e.getItem(); String n = it.getHoverName().getString();
        if (s.tossed.size() > 256) s.tossed.clear();
        s.tossed.put(e.getUUID(), now(g));
        Integer last = s.tossedItem.put(it.getItem(), now(g));
        if (it.getMaxStackSize() == 1 && last != null && now(g) - last <= 2400) fileOnce(g, "THROW_LOOP", "THROW_LOOP|" + n, "threw out " + n + " again " + (now(g) - last) / 20 + " s after the last time: he took it back in between");
        if (valuable(it)) fileOnce(g, "THREW_VALUABLE", "THREW_VALUABLE|" + n, "threw out " + it.getCount() + " " + n + " as junk: a player stashes or keeps armor, iron+ tools and ingots");
    }
    /** He picked an item up: one he threw out within 2 min is a THROW_LOOP. */
    public static void pickedUp(ClaudeEntity g, UUID entity, net.minecraft.world.item.ItemStack what) {
        if (!on()) return;
        Integer t = st(g).tossed.remove(entity);
        if (t != null && now(g) - t <= 2400) fileOnce(g, "THROW_LOOP", "THROW_LOOP|" + what.getHoverName().getString(), "picked back up the " + what.getHoverName().getString() + " he threw out " + (now(g) - t) / 20 + " s ago");
    }
    public static boolean valuable(net.minecraft.world.item.ItemStack it) {
        if (it.getItem() instanceof net.minecraft.world.item.ArmorItem || it.is(net.neoforged.neoforge.common.Tags.Items.INGOTS) || it.is(net.neoforged.neoforge.common.Tags.Items.GEMS)) return true;
        return it.getItem() instanceof net.minecraft.world.item.TieredItem t && t.getTier() != net.minecraft.world.item.Tiers.WOOD && t.getTier() != net.minecraft.world.item.Tiers.STONE; // (spent wood/stone tools are a player's junk too)
    }
    /** Leaps: a planned drop landed for the damage it planned (no more): that much falling health is his plan, not a threat. */
    public static void plannedHurt(ClaudeEntity g, float hp) { St s = st(g); s.plannedHp = hp; s.plannedAt = now(g); }
    /** He put a block down (the place event). */
    public static void placedOwn(ClaudeEntity g, BlockPos p) { St s = st(g); if (s.placedAt.size() > 256) s.placedAt.clear(); s.placedAt.put(p.immutable(), new int[] {now(g), s.broken, items(g)}); }
    /** He broke a block (the break event, before the drop): his own placement undone with nothing done (PLACE_BREAK); a tool block by hand or with the wrong tool (BARE_HANDS). */
    public static void brokeOwn(ClaudeEntity g, BlockPos p, net.minecraft.world.level.block.state.BlockState st) {
        if (!on()) return;
        St s = st(g);
        int[] pl = s.placedAt.remove(p);
        if (pl != null && now(g) - pl[0] <= 20 * 60 && s.broken == pl[1] && items(g) <= pl[2])
            fileOnce(g, "PLACE_BREAK", "PLACE_BREAK|" + p.asLong(), "put " + nm(st) + " down at " + p.toShortString() + " and broke it " + (now(g) - pl[0]) / 20 + " s later with nothing broken or gained between; doing " + (g.currentTask == null ? "-" : g.currentTask) + ", step " + (g.lastStep == null ? "-" : g.lastStep.skill() + " " + g.lastStep.target()));
        boolean axe = st.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_AXE), pick = st.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_PICKAXE);
        if (!axe && !pick) return;
        var held = g.getMainHandItem(); float hs = held.getDestroySpeed(st), best = hs; String bestName = "";
        for (int i = 0; i < g.inventory.items.getContainerSize(); i++) { var it = g.inventory.items.getItem(i); float v = it.getDestroySpeed(st); if (v > best) { best = v; bestName = it.getHoverName().getString(); } }
        boolean wrongTool = hs <= 1 || st.requiresCorrectToolForDrops() && !held.isCorrectToolForDrops(st); // (a tool for it that drops it is right: stone picks for all a stone pick mines, the user's rule; a stone pick on his furnace with a diamond one on him filed it, review-58 07:06)
        if (wrongTool && best >= 2 * hs && best > 1) { fileOnce(g, "BARE_HANDS", "BARE_HANDS|wrong|" + nm(st), "broke " + nm(st) + " at " + p.toShortString() + " with " + (held.isEmpty() ? "his hand" : held.getHoverName().getString()) + " while carrying " + bestName); s.handRun = 0; return; }
        if (hs > 1) { s.handRun = 0; return; }
        int planks = 0, logs = 0;
        for (int i = 0; i < g.inventory.items.getContainerSize(); i++) { var it = g.inventory.items.getItem(i); if (it.is(net.minecraft.tags.ItemTags.PLANKS)) planks += it.getCount(); if (it.is(net.minecraft.tags.ItemTags.LOGS)) logs += it.getCount(); }
        if (++s.handRun >= 4 && planks + 4 * logs >= 9) // (the makings of a wooden tool and a table: 3 head, 2 sticks, 4 table)
            fileOnce(g, "BARE_HANDS", "BARE_HANDS|hand|" + (axe ? "axe" : "pick"), s.handRun + " " + (axe ? "axe" : "pickaxe") + " blocks by hand in a row (now " + nm(st) + " at " + p.toShortString() + ") with the makings of a tool on him (logs " + logs + ", planks " + planks + ")");
    }

    private static String kindOf(Task t) { return t instanceof Task.GoTo ? "walking" : t instanceof Task.Break || t instanceof Task.ChopTree || t instanceof Task.FinishTree ? "digging" : t instanceof Task.Place ? "placing" : t instanceof Task.FurnaceTake || t instanceof Task.Smelt || t instanceof Task.Wait ? "waiting" : t instanceof Task.Attack || t instanceof Task.Flee ? "fighting" : t instanceof Task.Chest || t instanceof Task.Deposit || t instanceof Task.FurnaceLoad ? "chests and furnaces" : "other"; }
    /** An action's ticks at a player's pace: walks and digs by their estimate (and only when they got done), a placement or a chest 0.5-1 s plus the walk into reach; the rest (waits, fights, chores) as they ran: other detectors judge those. */
    private static int budget(ClaudeEntity g, St s, Task t, int ran) {
        int walk = (int) (Math.max(0, s.startDist - 4.5) / PACE);
        if (t instanceof Task.GoTo) return s.startEst > 0 ? Math.min(ran, s.startEst) : Math.min(ran, (int) (Math.max(0, s.startDist) / PACE) + 10);
        if (t instanceof Task.Break) return s.startEst > 0 ? Math.min(ran, s.startEst) : Math.min(ran, 10);
        if (t instanceof Task.Place) return Math.min(ran, 10 + walk);
        if (t instanceof Task.Chest || t instanceof Task.Deposit || t instanceof Task.FurnaceLoad) return Math.min(ran, 20 + walk);
        return ran;
    }
    private static Map<String, Integer> gained(St s, ClaudeEntity g) { // what came into his bag since the action began: the bag now against the start snapshot is rough, by item name (start count kept only as a total)
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i < g.inventory.items.getContainerSize(); i++) { var it = g.inventory.items.getItem(i); if (!it.isEmpty()) m.merge(it.getHoverName().getString(), it.getCount(), Integer::sum); }
        if (s.bagAtStart != null) for (var e : s.bagAtStart.entrySet()) m.merge(e.getKey(), -e.getValue(), Integer::sum);
        m.values().removeIf(v -> v <= 0); return m;
    }
    /**
     * PACE (the user 11-05: "everything in the same vein constitutes as slow"): each plan step's time against a player's: the sum of
     * its actions at a player's pace plus a second. Night holds, sleep and their stays don't count. Over 1.5x and 10 s+ over: SLOW, with
     * where the time went (walking, digging, waiting, no task: idle or re-planning).
     */
    private static void pace(ClaudeEntity g, St s) {
        Object ref = g.lastStep;
        if (ref != s.stepRef) {
            if (s.stepRef != null && on()) {
                int took = now(g) - s.stepStart - s.stepExcused, budget = 20 + s.stepUse.values().stream().mapToInt(u -> u[1]).sum();
                if (took > 1.5 * budget && took - budget >= 200) {
                    StringBuilder b = new StringBuilder();
                    for (var e : s.stepUse.entrySet()) b.append(String.format("%s %.1f s (a player %.1f s), ", e.getKey(), e.getValue()[0] / 20.0, e.getValue()[1] / 20.0));
                    fileOnce(g, "SLOW", "SLOW|step|" + s.stepName.split(" ")[0], String.format("the step %s took %.1f s, a player %.1f s: %sno task %.1f s (idle or re-planning)", s.stepName, took / 20.0, budget / 20.0, b, s.stepNoTask / 20.0));
                }
            }
            s.stepRef = ref; s.stepStart = now(g); s.stepExcused = 0; s.stepNoTask = 0; s.stepUse.clear();
            s.stepName = g.lastStep == null ? "" : g.lastStep.skill() + " " + g.lastStep.target() + " x" + g.lastStep.count();
            if (g.lastStep != null && on()) { // the same job again within 5 min, a new step for it (SPLIT_JOB): one run of the total would do
                String k = g.lastStep.skill() + " " + g.lastStep.target(); int[] j = s.jobs.get(k);
                if (j != null && now(g) - j[0] <= 20 * 300 && j[1] != System.identityHashCode(ref) && java.util.Set.of("smelt", "craft", "collect", "strip_mine", "fetch", "obtain").contains(g.lastStep.skill()))
                    fileOnce(g, "SPLIT_JOB", "SPLIT_JOB|" + k, "a new " + k + " x" + g.lastStep.count() + " step " + (now(g) - j[0]) / 20 + " s after the last one (one run of the total would do); plan " + g.skillQueue);
                s.jobs.put(k, new int[] {now(g), System.identityHashCode(ref)}); if (s.jobs.size() > 64) s.jobs.clear();
            }
        }
        if (s.stepRef == null) return;
        if (g.holdingNight() || g.isSleeping() || g.staying) { s.stepExcused++; return; }
        if (g.currentTask == null) s.stepNoTask++;
        else s.stepUse.computeIfAbsent(kindOf(g.currentTask), k -> new int[2])[0]++;
    }
    /** The walk under way: distance walked, and the length of the first path planned for it. */
    private static void walkLedger(ClaudeEntity g, St s) {
        if (!(g.currentTask instanceof Task.GoTo)) return;
        if (!Double.isNaN(s.lastWX)) s.walked += Math.sqrt(Math.pow(g.getX() - s.lastWX, 2) + Math.pow(g.getZ() - s.lastWZ, 2));
        s.lastWX = g.getX(); s.lastWZ = g.getZ();
        var p = g.getNavigation().getPath();
        if (s.pathLen < 0 && p != null && p.getNodeCount() > 1) { double l = 0; for (int i = 1; i < p.getNodeCount(); i++) l += Math.sqrt(p.getNodePos(i).distSqr(p.getNodePos(i - 1))); s.pathLen = l; }
    }
    /** Out and back: a walk ending within 3 of where a walk 8+ away began, inside 60 s, nothing done between (WASTED_WALK). */
    private static void backtrack(ClaudeEntity g, St s) {
        if (!on() || s.walks.size() < 2) return;
        int[] last = s.walks.peekLast(); java.util.List<int[]> ws = new java.util.ArrayList<>(s.walks);
        for (int i = 0; i < ws.size() - 1; i++) {
            int[] w = ws.get(i); if (last[0] - w[0] > 20 * 60) continue;
            double out = 0; for (int j = i; j < ws.size(); j++) out = Math.max(out, Math.hypot(ws.get(j)[3] - w[1], ws.get(j)[4] - w[2])); // (the furthest he got: a long walk comes in legs)
            double back = Math.hypot(last[3] - w[1], last[4] - w[2]);
            if (out >= 8 && back <= 3 && last[5] == w[5] && last[6] == w[6]) { fileOnce(g, "WASTED_WALK", "WASTED_WALK|" + w[1] + "," + w[2], String.format("walked %.0f out from %d, %d and back again within %d s with nothing done (%s)", out, w[1], w[2], (last[0] - w[0]) / 20, g.lastStep == null ? "no step" : g.lastStep.skill() + " " + g.lastStep.target())); return; }
        }
    }
    /** At a furnace 20 s+ waiting with nothing else done (FURNACE_WAIT): a player loads it and goes on working. */
    private static void furnaceWait(ClaudeEntity g, St s) {
        BlockPos f = g.currentTask instanceof Task.FurnaceTake x ? x.pos() : g.currentTask instanceof Task.Smelt x ? x.pos() : null;
        if (f == null || !on()) { s.waitSince = -1; return; }
        if (s.waitSince < 0 || !f.equals(s.waitAt) || s.broken != s.waitBroken) { s.waitSince = now(g); s.waitAt = f.immutable(); s.waitBroken = s.broken; return; }
        if (now(g) - s.waitSince >= 20 * 20) fileOnce(g, "FURNACE_WAIT", "FURNACE_WAIT|" + f.asLong(), "waiting at the furnace at " + f.toShortString() + " " + (now(g) - s.waitSince) / 20 + " s with nothing else done (" + g.currentTask + ", step " + (g.lastStep == null ? "-" : g.lastStep.skill() + " " + g.lastStep.target()) + ")");
    }
    /** One of his furnaces cooking while 2+ of his others within 6 stand empty and the input it's cooking is still on him (IDLE_FURNACES). */
    private static void idleFurnaces(ClaudeEntity g) {
        if (!on()) return;
        for (BlockPos f : g.myStations) {
            if (!g.level().isLoaded(f) || !(g.level().getBlockEntity(f) instanceof net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity fe) || fe.getItem(0).isEmpty()) continue;
            var in = fe.getItem(0).getItem(); int left = g.inventory.count(x -> x.is(in)); if (left == 0) continue;
            int empty = 0; for (BlockPos o : g.myStations) if (!o.equals(f) && o.distSqr(f) <= 36 && g.level().isLoaded(o) && g.level().getBlockEntity(o) instanceof net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity oe && oe.getItem(0).isEmpty() && oe.getItem(2).isEmpty()) empty++;
            if (empty >= 2) fileOnce(g, "IDLE_FURNACES", "IDLE_FURNACES|" + f.asLong(), "the furnace at " + f.toShortString() + " cooks " + fe.getItem(0).getCount() + " " + in + " while " + empty + " of his furnaces beside it stand empty and " + left + " more is on him");
        }
    }

    // furnace output done and sitting (input empty) over 30 s in one of his furnaces while he isn't on his way to it (World 17 23:35: charcoal left in it)
    private static final java.util.Map<BlockPos, Integer> doneSince = new java.util.HashMap<>();
    private static void furnaceLeft(ClaudeEntity g) {
        if (com.claudemod.entity.ClaudeSpawner.testing && !linesInTests) return;
        Task cur = g.currentTask;
        for (BlockPos f : g.myStations) {
            if (!g.level().isLoaded(f) || !(g.level().getBlockEntity(f) instanceof net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity fe) || fe.getItem(2).isEmpty() || !fe.getItem(0).isEmpty()) { doneSince.remove(f); continue; }
            int since = doneSince.computeIfAbsent(f.immutable(), k -> now(g));
            boolean heading = cur != null && f.equals(target(cur)) || g.tasks.stream().anyMatch(t -> t instanceof Task.FurnaceTake ft && ft.pos().equals(f) && cur instanceof Task.GoTo);
            if (heading) { doneSince.put(f.immutable(), now(g)); continue; }
            if (now(g) - since > 20 * 30) fileOnce(g, "FURNACE_LEFT", "FURNACE_LEFT|" + f.asLong(), fe.getItem(2).getCount() + " " + fe.getItem(2).getHoverName().getString() + " done in his furnace at " + f.toShortString() + " for " + (now(g) - since) / 20 + " s; he's " + Math.round(Math.sqrt(f.distToCenterSqr(g.position()))) + " off, doing " + (cur == null ? "nothing" : cur));
        }
    }

    public static void tick(ClaudeEntity g) {
        if (!g.isAlive()) return;
        LogReviewer.tick(g);
        lineIncidents(g);
        if (now(g) % 20 == 0) { furnaceLeft(g); idle(g, st(g)); stuck(g, st(g)); wastedTrip(g, st(g)); furnaceWait(g, st(g)); idleFurnaces(g); }
        pace(g, st(g)); walkLedger(g, st(g));
        dragAndWater(g, st(g));
        St s = st(g);
        // 1. a move no planned move made: more than 3 blocks in one tick (a knock, a fall through, an unlogged teleport)
        if (!Double.isNaN(s.px) && now(g) == s.pTick + 1 && g.level().dimension() == s.pDim) { // (a portal trip is a planned move)
            double d = Math.sqrt(Math.pow(g.getX() - s.px, 2) + Math.pow(g.getY() - s.py, 2) + Math.pow(g.getZ() - s.pz, 2));
            if (d > 3 && com.claudemod.entity.nav.Leaps.control(g) == null && now(g) - s.lastMove > 20 * 5) { s.lastMove = now(g); incident(g, "UNPLANNED MOVE", String.format("%.1f blocks in one tick, from %.1f %.1f %.1f to %.1f %.1f %.1f, doing %s", d, s.px, s.py, s.pz, g.getX(), g.getY(), g.getZ(), g.currentTask == null ? "-" : g.currentTask.getClass().getSimpleName())); }
        }
        s.px = g.getX(); s.py = g.getY(); s.pz = g.getZ(); s.pTick = now(g); s.pDim = g.level().dimension();
        record(s, now(g), bodyLine(g));
        if (g.currentTask instanceof Task.Toss || g.currentTask instanceof Task.Chest || g.currentTask instanceof Task.Deposit) s.handOff = now(g);
        if (now(g) % 20 != 0) return;
        s.samples.addLast(new double[] {now(g), g.getX(), g.getY(), g.getZ(), g.getHealth(), g.getFoodData().getFoodLevel(), g.getRemainingFireTicks(), g.getAirSupply(), g.isEyeInFluid(net.minecraft.tags.FluidTags.WATER) ? 1 : 0, g.level().getBlockState(net.minecraft.core.BlockPos.containing(g.getEyePosition()).above()).blocksMotion() ? 1 : 0}); // + air, eyes under water, a solid block over his head (two drownings 17:09/18:11 had none of it)
        while (s.samples.size() > 60) s.samples.pollFirst();
        // 2. health falling 5+ in 10 s with no answer to it (not running, not eating)
        double[] ten = null; for (double[] x : s.samples) if (now(g) - x[0] <= 20 * 10) { ten = x; break; }
        double planned = now(g) - s.plannedAt <= 20 * 10 ? s.plannedHp : 0; // (a drop he planned and took as planned: 08:10:22 a 9-block leap for its 6 HP read as HEALTH FALLING)
        if (ten != null && ten[4] - g.getHealth() - planned >= 5 && !(g.currentTask instanceof Task.Flee) && !(g.currentTask instanceof Task.Eat) && now(g) - s.lastFall > 20 * 30) { s.lastFall = now(g); incident(g, "HEALTH FALLING", String.format("%.0f -> %.0f in %d s, no flee or eat (doing %s, on fire %s, in lava %s)%s", ten[4], g.getHealth(), (now(g) - (int) ten[0]) / 20, g.currentTask == null ? "-" : g.currentTask.getClass().getSimpleName(), g.isOnFire(), g.isInLava(), drowningOut(g))); }
        // 3. a fight with no effect: 15 s in an attack, its target no worse off
        if (g.currentTask instanceof Task.Attack a && g.level() instanceof ServerLevel sl && sl.getEntity(a.target()) instanceof net.minecraft.world.entity.LivingEntity foe) {
            if (!a.target().equals(s.atkTarget)) { s.atkTarget = a.target(); s.atkSince = now(g); s.atkHp = foe.getHealth(); }
            else if (foe.getHealth() < s.atkHp) { s.atkHp = foe.getHealth(); s.atkSince = now(g); }
            else if (now(g) - s.atkSince > 20 * 15 && now(g) - s.lastNoEffect > 20 * 30) { s.lastNoEffect = now(g); incident(g, "FIGHT NO EFFECT", "15 s attacking a " + foe.getType().getDescription().getString() + " " + String.format("%.1f", foe.distanceTo(g)) + " off, not a hit landed (it's at " + (int) foe.getHealth() + "): breaking it off"); g.dropCurrent = true; s.atkTarget = null; }
        } else s.atkTarget = null;
        // 4. important gear gone with no death (smelted, dropped, broken)
        var now = gearOf(g);
        if (s.gear != null && now(g) - s.handOff > 40) { java.util.List<String> lost = new java.util.ArrayList<>(); for (var e : s.gear.entrySet()) { int left = now.getOrDefault(e.getKey(), 0); if (left < e.getValue()) lost.add((e.getValue() - left) + " " + e.getKey()); } if (!lost.isEmpty()) incident(g, "GEAR GONE", String.join(", ", lost) + " (doing " + (g.currentTask == null ? "-" : g.currentTask.getClass().getSimpleName()) + ", step " + (g.lastStep == null ? "-" : g.lastStep.skill() + " " + g.lastStep.target()) + ")"); }
        s.gear = now;
    }

    /**
     * Drowning with his health already falling: the swim-up reflex (WorkGoal, air under 120) has failed, so the way to air is
     * planned like a stuck escape, digging allowed (two drownings in his own mine, 17:09 and 18:11 on 2026-11-02, with the reflex
     * there both times). Returns what it did, for the incident line.
     */
    public static boolean airRoute(ClaudeEntity g) {
        St s = ST.get(g.getUUID()); if (s == null || now(g) - s.airRouteTick >= 20 * 10) return false;
        if (s.airRouteTo == null) return true;
        double d = g.getEyePosition().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(s.airRouteTo));
        if (d < s.airRouteBest - 0.3) { s.airRouteBest = d; s.airRouteBestTick = now(g); }
        boolean further = d > s.airRouteD + 1.5, stalled = now(g) - s.airRouteBestTick > Weights.w("drown.air_route_stall_ticks", 30);
        if (!further && !stalled) return true;
        s.airRouteTick = -100000;
        ClaudeMod.LOGGER.info("[Claude watchdog] the dug way to air at {} {}: the swim-up reflex takes back over (Watchdog.airRoute)", s.airRouteTo.toShortString(), further ? "has him further from it than it started" : "got him no nearer in " + (now(g) - s.airRouteBestTick) + " ticks");
        return false; // (only while it gets him nearer: live 17:04:19 11-07 its dig went round to a view cell, he stood 3 s, a 31-block shore swim took over, 14 -> 0, the air 3 cells off; stage 1011)
    }
    public static String drowningOutForTest(ClaudeEntity g) { return drowningOut(g); } // (tests: the HEALTH FALLING drowning branch at the moment it fires)
    static String drowningOut(ClaudeEntity g) {
        if (!g.isEyeInFluid(net.minecraft.tags.FluidTags.WATER) || g.getAirSupply() > 0 || !(g.level() instanceof ServerLevel lv)) return "";
        BlockPos me = BlockPos.containing(g.getEyePosition()), best = null;
        for (BlockPos q : BlockPos.betweenClosed(me.offset(-6, -3, -6), me.offset(6, 6, 6))) // the nearest cell he can breathe in: air, with air over it
            if (lv.getBlockState(q).isAir() && lv.getBlockState(q.above()).isAir() && (best == null || q.distSqr(me) < best.distSqr(me))) best = q.immutable();
        if (best == null) return "; drowning, no air within 6";
        boolean planned = com.claudemod.director.ClaudeLife.escapeStuck(g, lv, new Task.GoTo(best, 0.5));
        if (planned) { St s0 = st(g); s0.airRouteTick = now(g); s0.airRouteTo = best; s0.airRouteD = s0.airRouteBest = g.getEyePosition().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(best)); s0.airRouteBestTick = now(g); }
        ClaudeMod.LOGGER.info("[Claude watchdog] drowning at {} with the swim-up failing: {} air at {} (Watchdog.drowningOut)", me.toShortString(), planned ? "a dug way to the" : "no way found to the", best.toShortString());
        return planned ? "; drowning: a dug way to air at " + best.toShortString() + " queued" : "; drowning: no way to air at " + best.toShortString();
    }

    /** A skill line coming straight back (under 1 s, 3 times running): it returns at once and is queued again (live 12:30 World 15 "collect beef" 9 times in 2 s). */
    public static void quickReturn(ClaudeEntity g, String line) {
        St s = st(g);
        if (line.equals(s.quickLine) && now(g) - s.quickTick < 20) s.quickN++; else s.quickN = 1;
        s.quickLine = line; s.quickTick = now(g);
        if (s.quickN == 3) incident(g, "INSTANT REPEAT", "the same step result 3 times inside a second each: " + line);
    }

    /** A move his own code made (the catch-up teleport): not an unplanned one. */
    public static void teleported(ClaudeEntity g) { st(g).px = Double.NaN; }

    /** A death: the incident with its last minute. */
    public static void death(ClaudeEntity g, String how) { incident(g, "DEATH", how); }

    /** Layers feet-1..feet+3, 7x7 (x across, z down): @ him, ~ fluid, g gravel/sand, # solid, . open. */
    static String blockMap(net.minecraft.world.level.Level lv, BlockPos at) {
        StringBuilder sb = new StringBuilder(" (x across, z down):");
        for (int dy = -1; dy <= 3; dy++) {
            sb.append("\n  y").append(at.getY() + dy).append(": ");
            for (int dz = -3; dz <= 3; dz++) {
                for (int dx = -3; dx <= 3; dx++) {
                    BlockPos q = at.offset(dx, dy, dz); var s = lv.getBlockState(q);
                    sb.append(dx == 0 && dz == 0 && (dy == 0 || dy == 1) ? '@' : !lv.getFluidState(q).isEmpty() ? '~' : s.getBlock() instanceof net.minecraft.world.level.block.FallingBlock ? 'g' : s.getCollisionShape(lv, q).isEmpty() ? '.' : '#');
                }
                if (dz < 3) sb.append('|');
            }
        }
        return sb.toString();
    }

    /**
     * One self-contained incident block (the user: "account for more scenarios so you can piece together more context"): where,
     * how he's been, what he was doing and why, what's about him, his recent log. To the log, one [Claude incident] line pointing
     * at it, and to saves/<world>/claude/incidents/<time>.txt.
     */
    /** A trace entry: the same line as the last one only stretches its tick span; entries older than 10 s go. */
    static void record(St s, int tick, String line) {
        Object[] last = s.trace.peekLast();
        if (last != null && last[2].equals(line)) last[1] = tick; else s.trace.addLast(new Object[] {tick, tick, line});
        while (s.trace.size() > 400 || !s.trace.isEmpty() && tick - (int) s.trace.peekFirst()[1] > 200) s.trace.pollFirst();
    }
    /** His body this tick: where, how he's moving, what his legs and hands were told (the walk's target, the path's next node, a jump), and what task. */
    static String bodyLine(ClaudeEntity g) {
        var v = g.getDeltaMovement(); var nav = g.getNavigation(); var path = nav.getPath();
        String next = path == null ? "no path" : path.isDone() ? "path done" : "next " + path.getNextNodePos().toShortString() + " (" + path.getNextNodeIndex() + "/" + path.getNodeCount() + ")";
        return String.format("at %.2f %.2f %.2f v %.2f %.2f %.2f%s%s%s%s pose %s look %.0f/%.0f in %.2f/%.2f%s | %s | want %s%s | %s age %s",
                g.getX(), g.getY(), g.getZ(), v.x, v.y, v.z, g.onGround() ? " ground" : " air", g.horizontalCollision ? " hcoll" : "", g.isInWater() ? (g.isEyeInFluid(net.minecraft.tags.FluidTags.WATER) ? " under water" : " in water") + flow(g) : "", g.isInLava() ? " LAVA" : "",
                g.getPose(), g.getYRot(), g.getXRot(), g.zza, g.xxa, g.isJumpingNow() ? " jumping" : "", next, g.getMoveControl().debug(), g.getJumpControl().pressed() ? " JUMP" : "", g.currentTask == null ? "no task" : g.currentTask, g.work == null ? "?" : g.work.taskAge());
    }
    /** The current at his feet (a 1-deep stream ran him at 0.35 blocks/s, live 04:08 11-08: the push that would prove it was never logged). */
    static String flow(ClaudeEntity g) { var q = g.blockPosition(); var f = g.level().getFluidState(q).getFlow(g.level(), q); return f.lengthSqr() < 1e-4 ? "" : String.format(" flow %.2f/%.2f", f.x, f.z); }
    /** The context sections every incident carries: the target's own surroundings, his path, and the last 10 s tick by tick. */
    public static String context(ClaudeEntity g) { St s = st(g);
        StringBuilder b = new StringBuilder(); var lv = g.level();
        Task cur = g.currentTask != null ? g.currentTask : s.started;
        BlockPos t = cur == null ? null : target(cur);
        if (t == null && s.lastTargeted != null) { b.append("(now ").append(cur == null ? "no task" : cur).append("; the last task with a target began ").append((now(g) - s.lastTargetedTick) / 20).append(" s ago)\n"); cur = s.lastTargeted; t = target(cur); }
        if (t != null && !(t.distSqr(g.blockPosition()) <= Weights.w("watchdog.read_range", 48) * Weights.w("watchdog.read_range", 48) && lv.hasChunkAt(t))) b.append("target: ").append(cur).append(" -> ").append(t.toShortString()).append(String.format(", %.1f off, past watchdog.read_range or unloaded: not read (a block read or a sight line out there loads chunks on the server thread: the test server hung twice, 06 Nov 04:10 and 04:39, with both ends loaded)", Math.sqrt(t.distToCenterSqr(g.position())))).append('\n');
        else if (t != null) {
            b.append("target: ").append(cur).append(" -> ").append(t.toShortString()).append(" is ").append(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(lv.getBlockState(t).getBlock()).getPath())
             .append(String.format(", %.1f off (%d across, %d up)", Math.sqrt(t.distToCenterSqr(g.position())), (int) Math.round(Math.sqrt(Math.pow(t.getX() - g.getBlockX(), 2) + Math.pow(t.getZ() - g.getBlockZ(), 2))), t.getY() - g.getBlockY()))
             .append(", in sight ").append(lv.clip(new net.minecraft.world.level.ClipContext(g.getEyePosition(), net.minecraft.world.phys.Vec3.atCenterOf(t), net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, g)).getBlockPos().equals(t)).append('\n');
            b.append("blocks round the target").append(blockMap(lv, t).replace("(x across, z down)", "(x across, z down; @ the target's column)")).append('\n');
        } else b.append("target: none (").append(cur == null ? "no task" : cur).append(")\n");
        var path = g.getNavigation().getPath();
        if (path != null) {
            StringBuilder ps = new StringBuilder(); for (int i = path.getNextNodeIndex(); i < path.getNodeCount() && i < path.getNextNodeIndex() + 16; i++) ps.append(' ').append(path.getNodePos(i).toShortString()).append(';');
            b.append("his path: to ").append(path.getTarget().toShortString()).append(", reaches it ").append(path.canReach()).append(", node ").append(path.getNextNodeIndex()).append('/').append(path.getNodeCount()).append(", next:").append(ps).append('\n');
        } else b.append("his path: none\n");
        b.append("the last 10 s, tick by tick (ticks ago; a span = the same for those ticks):\n");
        int nw = now(g); for (Object[] e : s.trace) { int a = nw - (int) e[0], z = nw - (int) e[1]; b.append(a == z ? String.format("  -%d: ", a) : String.format("  -%d..-%d: ", a, z)).append(e[2]).append('\n'); }
        return b.toString();
    }
    public static void incident(ClaudeEntity g, String kind, String why) {
        StringBuilder b = new StringBuilder();
        var lv = g.level(); BlockPos at = g.blockPosition();
        b.append("INCIDENT ").append(kind).append(": ").append(why).append('\n');
        b.append("when: tick ").append(now(g)).append(", day time ").append(lv.getDayTime() % 24000).append('\n');
        b.append("where: ").append(lv.dimension().location()).append(' ').append(at.toShortString()).append(", under him ").append(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(lv.getBlockState(at.below()).getBlock()).getPath()).append(g.isInLava() ? ", IN LAVA" : "").append(g.isOnFire() ? ", on fire" : "").append('\n');
        St s = st(g); StringBuilder tr = new StringBuilder();
        for (double[] x : s.samples) if (now(g) - x[0] <= 20 * 30) tr.append(String.format(" %ds: hp %.0f food %.0f fire %.0f at %.0f %.0f %.0f%s;", (int) (x[0] - now(g)) / 20, x[4], x[5], x[6], x[1], x[2], x[3], x.length > 9 && (x[7] < 300 || x[8] > 0) ? String.format(" air %.0f%s%s", x[7], x[8] > 0 ? " eyes under water" : "", x[9] > 0 ? " roof over head" : "") : ""));
        b.append("blocks round him").append(blockMap(lv, at)).append('\n'); // (the 3 h water loop of Nov 3 couldn't be proven: no picture of the pocket)
        b.append("last 30 s:").append(tr.length() == 0 ? " -" : tr).append('\n');
        b.append("goal: ").append(g.goal == null ? "-" : g.goal).append("; their goal: ").append(g.playerGoal == null ? "-" : g.playerGoal).append("; on their job: ").append(g.commandActive).append(g.staying ? "; STAYING (their hold)" : "").append('\n');
        b.append("step: ").append(g.lastStep == null ? "-" : g.lastStep.skill() + " " + g.lastStep.target()).append("; last line: ").append(g.lastSkillLine == null ? "-" : g.lastSkillLine).append("; plan: ").append(g.skillQueue).append('\n');
        b.append("task: ").append(g.currentTask == null ? "-" : g.currentTask).append(" (age ").append(g.work == null ? "?" : g.work.taskAge()).append("t); queue ").append(g.tasks.size()).append(": ").append(g.tasks.stream().limit(6).toList()).append('\n');
        StringBuilder mobs = new StringBuilder();
        for (var m : Perception.known(g, net.minecraft.world.entity.Mob.class, g.getBoundingBox().inflate(24), x -> x.isAlive() && x instanceof net.minecraft.world.entity.monster.Enemy)) mobs.append(' ').append(m.getType().getDescription().getString()).append(String.format(" %.0f off%s;", m.distanceTo(g), g.hasLineOfSight(m) ? " in sight" : ""));
        b.append("hostiles known within 24:").append(mobs.length() == 0 ? " none" : mobs).append('\n');
        b.append("watchdog: ").append(s.trips).append(" trip(s); tripped lately: ").append(s.rung.entrySet().stream().filter(x -> now(g) - x.getValue()[1] <= MEMORY).map(java.util.Map.Entry::getKey).toList()).append("; told: ").append(s.lastTold.isEmpty() ? "-" : s.lastTold).append('\n');
        try { b.append(context(g)); } catch (RuntimeException e) { b.append("context: threw ").append(e).append('\n'); }
        b.append(MindLog.recent());
        String text = b.toString();
        incidents++; lastIncident = kind + ": " + why; recentKinds.addLast(kind); while (recentKinds.size() > 50) recentKinds.removeFirst();
        String file = "-";
        if (!com.claudemod.entity.ClaudeSpawner.testing && g.getServer() != null) {
            try {
                java.nio.file.Path dir = g.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("claude").resolve("incidents");
                java.nio.file.Files.createDirectories(dir);
                java.nio.file.Path f = dir.resolve(java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-" + kind.toLowerCase().replace(' ', '_') + "-" + incidents + ".txt"); // (two in one second overwrote each other)
                java.nio.file.Files.writeString(f, text); file = f.toString();
            } catch (Exception e) { file = "unwritten (" + e.getClass().getSimpleName() + ")"; }
        }
        if (file.startsWith("unwritten") || "-".equals(file)) ClaudeMod.LOGGER.info("[Claude incident] {}: {} (file {})\n{}", kind, why, file, text);
        else ClaudeMod.LOGGER.info("[Claude incident] {}: {} (file {})", kind, why, file); // (the whole context is in the file: the log keeps the line)
    }

    /** Self-check of the pure parts. */
    public static void main(String[] a) {
        if (!"cobblestone".equals(missing("failed: to craft furnace you are missing cobblestone"))) throw new AssertionError(missing("failed: to craft furnace you are missing cobblestone"));
        if (!"oak planks".equals(missing("failed: missing 3 oak planks (the table)"))) throw new AssertionError(missing("failed: missing 3 oak planks (the table)"));
        if (!"cobblestone".equals(gatherable("cobbled deepslate"))) throw new AssertionError("deepslate");
        if (!"LOG_FUEL".equals(lineKind("[Claude smelt] fuel at 1, 2, 3: 2 oak log for 3 items (whole logs)")) || lineKind("[Claude smelt] fuel at 1, 2, 3: 2 oak planks for 3 items") != null) throw new AssertionError("log fuel");
        if (!"SPIN".equals(lineKind("[Claude move] spin at -89, 22, -22 doing GoTo to -91, 23, -24 next node -90,22,-22 (294 deg in 19 ticks)"))) throw new AssertionError("spin");
        if (lineKind("[Claude move] in water 8.8 s, 30 blocks") != null || lineKind("[Claude move] dragging along oak_leaves") != null) throw new AssertionError("own move lines refiled");
        if (lineKind("[Claude conflict] 3 times in 59 s: [Claude life] holding in his cover at 0, 66, 0 for the night") != null) throw new AssertionError("night hold is a conflict");
        if (!lineKey("FAILED", "[Claude skill] collect logs x2 -> failed: it's night (no iron armor)").equals(lineKey("FAILED", "[Claude watchdog] collect logs x5 -> failed: it's night: 1 time(s) in 0 s"))) throw new AssertionError("skill line and its echo differ: " + lineKey("FAILED", "[Claude skill] collect logs x2 -> failed: it's night (no iron armor)"));
        if (lineKey("LITTER", "[Claude litter] the tree at -7, 79, 5 isn't finished").equals(lineKey("LITTER", "[Claude litter] the tree at -11, 78, 13 isn't finished"))) throw new AssertionError("two trees, one key");
        if (triedFirst("craft", "iron_ingot").contains("null") || !triedFirst("craft", "iron_ingot").contains("iron ingot")) throw new AssertionError(triedFirst("craft", "iron_ingot"));
        if (!triedFirst("build_here", null).equals("tried another way first")) throw new AssertionError(triedFirst("build_here", null));
        System.out.println("Watchdog self-check passed");
    }
}
