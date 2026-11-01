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
        final Map<String, int[]> ends = new HashMap<>();     // action key -> {no-progress ends, first tick}
        final Map<String, int[]> rung = new HashMap<>();     // action key -> {rung reached, tick}
        final Map<String, int[]> skills = new HashMap<>();   // failed skill line -> {times, first tick}
        final Map<String, Long> lastFeet = new HashMap<>();
        final Map<String, double[]> lastEnd = new HashMap<>(); // dig/place key -> {blocks broken, items, best distance, tick} at its last end
        final Map<String, String> tried = new HashMap<>();   // action key -> the ways tried so far, for the line to them
        final Map<String, String> what = new HashMap<>();    // action key -> what failed and how, from its trip
        final java.util.Set<String> told = new java.util.HashSet<>();
        Task started; int startTick, startBroken, startItems; double startDist; // the action now running, as it began
        int broken;                                          // blocks he's broken (ClaudeLife.noteBroken)
        String pending;                                      // a line to say on the next ClaudeLife tick (it has the player)
        final java.util.ArrayDeque<double[]> samples = new java.util.ArrayDeque<>(); // {tick, x, y, z, health, food, fire ticks}, one a second, the last 60
        double px = Double.NaN, py, pz; int pTick; Object pDim; int handOff = -100000; // (the last tick he was handing things over: give, a chest, a deposit)          // his position last tick (the silent-move check)
        java.util.UUID atkTarget; int atkSince; float atkHp; // the fight under way: whom, since when, its health then
        int lastFall = -100000, lastNoEffect = -100000, lastMove = -100000; // (each detector says so at most every 30 s)
        java.util.Map<String, Integer> gear;                 // his important gear by item, a second ago
        String quickLine; int quickTick, quickN;             // a skill line coming straight back
        String lastTold = ""; int lastTellTick = -1;                                // (stages read it)
        int trips;
    }
    private static final Map<UUID, St> ST = new HashMap<>();
    private static int now(ClaudeEntity g) { return g.getServer().getTickCount(); } // ponytail: his own tickCount restarts at 0 on every dimension change; this state outlives the entity
    private static St st(ClaudeEntity g) { return ST.computeIfAbsent(g.getUUID(), k -> new St()); }
    public static void reset(ClaudeEntity g) { ST.remove(g.getUUID()); }
    public static int incidents; public static String lastIncident = ""; // (stages read them)
    public static String lastTold(ClaudeEntity g) { St s = ST.get(g.getUUID()); return s == null ? "" : s.lastTold; }
    public static int trips(ClaudeEntity g) { St s = ST.get(g.getUUID()); return s == null ? 0 : s.trips; }
    /** That action written off by the watchdog (tripped, within its 2 minutes): its source should not queue it again. */
    public static boolean off(ClaudeEntity g, Task t) { String k = key(t); return k != null && rung(g, k) > 0; }
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
        String k = key(t); if (k == null) return true;
        int r = rung(g, k); if (r == 0) return true;
        Boolean got = achieved(g, t); if (Boolean.TRUE.equals(got) && !(t instanceof Task.GoTo)) return true; // (already done: the task ends at once by itself)
        if (t instanceof Task.GoTo && r < 2) return true; // a walk on rung 1 runs behind its MAY_DIG way (that's the way)
        if (r >= 3) { ClaudeMod.LOGGER.info("[Claude watchdog] {} picked again, written off and told: not run (Watchdog.starting)", k); return false; }
        ClaudeMod.LOGGER.info("[Claude watchdog] {} again after it tripped (rung {}): not run as it was, next way (Watchdog.starting)", k, r);
        climb(g, t, k, ClaudeChat_describe(g, t) + ": picked again after it kept failing");
        return false;
    }

    /** WorkGoal: the action ended (why: its exit line, or null). Counts it if it ended without its progress. */
    public static void ended(ClaudeEntity g, Task t, String why) {
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
                || t instanceof Task.GoTo && mine && ran > 3 && !meansFirst(g, t) && walkNoGain(s, k, g, t) // (nearer than his best on that walk over the window, or it's no progress: a one-block step cut and undone the next moment isn't; live 09:48 World 15, 21 "stepping off" in 80 s, net nothing) // (a leg that hands over to its own route's digs, blocks broken between legs and him nearer: progress; live 06:47 World 15 digging stairs home, 130 -> 113 off, tripped every few seconds)
                || (t instanceof Task.Break || t instanceof Task.Place) && mine && !madeSome && !meansFirst(g, t) && repeatNoGain(s, k, g, t); // (a dig or a placement refused and re-queued with nothing broken, nothing got and no nearer since its last end: live 03:18 World 15, 270 refused digs and the watchdog never saw them)
        if (!counted) { if (t instanceof Task.GoTo || t instanceof Task.Leap) ClaudeMod.LOGGER.info("[Claude watchdog] {} ended short, not counted ({}) (Watchdog.ended)", k, !mine || ran <= 3 ? "ended at once: the walker's own refusal" : madeSome ? "broke or got something on the way" : "got nearer"); return; } // (the rung stays for MEMORY: it came good, but a fresh trip on it climbs on)
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
            s.tried.put(k, tried + ", digging/bridging/pillaring to it");
            ClaudeMod.LOGGER.info("[Claude watchdog] {}: rung 1, his own way to it (dig, bridge, pillar, swim) in front (Watchdog.climb)", k);
            return;
        }
        if (r[0] <= 2) {
            r[0] = 2;
            int n = 0; for (var it = g.tasks.iterator(); it.hasNext(); ) if (k.equals(key(it.next()))) { it.remove(); n++; }
            mark(g, t);
            s.tried.put(k, tried + (tried.contains("digging") ? "" : ", digging/bridging (no way found)") + ", going round");
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
        int r = s.rung.containsKey("skill " + k) ? s.rung.get("skill " + k)[0] : 0;
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
                + (r > 0 ? ", got more " + gatherable(lacks) + " first and it still failed" : "") + "). doing something else for now, could you help with that?";
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
    private static java.util.Map<String, Integer> gearOf(ClaudeEntity g) {
        java.util.Map<String, Integer> m = new java.util.TreeMap<>();
        for (int i = 0; i < g.inventory.items.getContainerSize(); i++) { var st = g.inventory.items.getItem(i); if (important(st)) m.merge(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem()).getPath(), st.getCount(), Integer::sum); }
        for (var sl : net.minecraft.world.entity.EquipmentSlot.values()) { var st = g.getItemBySlot(sl); if (sl.isArmor() && important(st)) m.merge(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem()).getPath(), st.getCount(), Integer::sum); }
        return m;
    }

    /** Every tick, from the hazard layer: what no single task watches. */
    public static void tick(ClaudeEntity g) {
        if (!g.isAlive()) return;
        St s = st(g);
        // 1. a move no planned move made: more than 3 blocks in one tick (a knock, a fall through, an unlogged teleport)
        if (!Double.isNaN(s.px) && now(g) == s.pTick + 1 && g.level().dimension() == s.pDim) { // (a portal trip is a planned move)
            double d = Math.sqrt(Math.pow(g.getX() - s.px, 2) + Math.pow(g.getY() - s.py, 2) + Math.pow(g.getZ() - s.pz, 2));
            if (d > 3 && com.claudemod.entity.nav.Leaps.control(g) == null && now(g) - s.lastMove > 20 * 5) { s.lastMove = now(g); incident(g, "UNPLANNED MOVE", String.format("%.1f blocks in one tick, from %.1f %.1f %.1f to %.1f %.1f %.1f, doing %s", d, s.px, s.py, s.pz, g.getX(), g.getY(), g.getZ(), g.currentTask == null ? "-" : g.currentTask.getClass().getSimpleName())); }
        }
        s.px = g.getX(); s.py = g.getY(); s.pz = g.getZ(); s.pTick = now(g); s.pDim = g.level().dimension();
        if (g.currentTask instanceof Task.Toss || g.currentTask instanceof Task.Chest || g.currentTask instanceof Task.Deposit) s.handOff = now(g);
        if (now(g) % 20 != 0) return;
        s.samples.addLast(new double[] {now(g), g.getX(), g.getY(), g.getZ(), g.getHealth(), g.getFoodData().getFoodLevel(), g.getRemainingFireTicks()});
        while (s.samples.size() > 60) s.samples.pollFirst();
        // 2. health falling 5+ in 10 s with no answer to it (not running, not eating)
        double[] ten = null; for (double[] x : s.samples) if (now(g) - x[0] <= 20 * 10) { ten = x; break; }
        if (ten != null && ten[4] - g.getHealth() >= 5 && !(g.currentTask instanceof Task.Flee) && !(g.currentTask instanceof Task.Eat) && now(g) - s.lastFall > 20 * 30) { s.lastFall = now(g); incident(g, "HEALTH FALLING", String.format("%.0f -> %.0f in %d s, no flee or eat (doing %s, on fire %s, in lava %s)", ten[4], g.getHealth(), (now(g) - (int) ten[0]) / 20, g.currentTask == null ? "-" : g.currentTask.getClass().getSimpleName(), g.isOnFire(), g.isInLava())); }
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

    /**
     * One self-contained incident block (the user: "account for more scenarios so you can piece together more context"): where,
     * how he's been, what he was doing and why, what's about him, his recent log. To the log, one [Claude incident] line pointing
     * at it, and to saves/<world>/claude/incidents/<time>.txt.
     */
    public static void incident(ClaudeEntity g, String kind, String why) {
        StringBuilder b = new StringBuilder();
        var lv = g.level(); BlockPos at = g.blockPosition();
        b.append("INCIDENT ").append(kind).append(": ").append(why).append('\n');
        b.append("when: tick ").append(now(g)).append(", day time ").append(lv.getDayTime() % 24000).append('\n');
        b.append("where: ").append(lv.dimension().location()).append(' ').append(at.toShortString()).append(", under him ").append(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(lv.getBlockState(at.below()).getBlock()).getPath()).append(g.isInLava() ? ", IN LAVA" : "").append(g.isOnFire() ? ", on fire" : "").append('\n');
        St s = st(g); StringBuilder tr = new StringBuilder();
        for (double[] x : s.samples) if (now(g) - x[0] <= 20 * 30) tr.append(String.format(" %ds: hp %.0f food %.0f fire %.0f at %.0f %.0f %.0f;", (int) (x[0] - now(g)) / 20, x[4], x[5], x[6], x[1], x[2], x[3]));
        b.append("last 30 s:").append(tr.length() == 0 ? " -" : tr).append('\n');
        b.append("goal: ").append(g.goal == null ? "-" : g.goal).append("; their goal: ").append(g.playerGoal == null ? "-" : g.playerGoal).append("; on their job: ").append(g.commandActive).append(g.staying ? "; STAYING (their hold)" : "").append('\n');
        b.append("step: ").append(g.lastStep == null ? "-" : g.lastStep.skill() + " " + g.lastStep.target()).append("; last line: ").append(g.lastSkillLine == null ? "-" : g.lastSkillLine).append("; plan: ").append(g.skillQueue).append('\n');
        b.append("task: ").append(g.currentTask == null ? "-" : g.currentTask).append(" (age ").append(g.work == null ? "?" : g.work.taskAge()).append("t); queue ").append(g.tasks.size()).append(": ").append(g.tasks.stream().limit(6).toList()).append('\n');
        StringBuilder mobs = new StringBuilder();
        for (var m : Perception.known(g, net.minecraft.world.entity.Mob.class, g.getBoundingBox().inflate(24), x -> x.isAlive() && x instanceof net.minecraft.world.entity.monster.Enemy)) mobs.append(' ').append(m.getType().getDescription().getString()).append(String.format(" %.0f off%s;", m.distanceTo(g), g.hasLineOfSight(m) ? " in sight" : ""));
        b.append("hostiles known within 24:").append(mobs.length() == 0 ? " none" : mobs).append('\n');
        b.append("watchdog: ").append(s.trips).append(" trip(s); tripped: ").append(s.rung.keySet()).append("; told: ").append(s.lastTold.isEmpty() ? "-" : s.lastTold).append('\n');
        b.append(MindLog.recent());
        String text = b.toString();
        incidents++; lastIncident = kind + ": " + why;
        String file = "-";
        if (!com.claudemod.entity.ClaudeSpawner.testing && g.getServer() != null) {
            try {
                java.nio.file.Path dir = g.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("claude").resolve("incidents");
                java.nio.file.Files.createDirectories(dir);
                java.nio.file.Path f = dir.resolve(java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-" + kind.toLowerCase().replace(' ', '_') + ".txt");
                java.nio.file.Files.writeString(f, text); file = f.toString();
            } catch (Exception e) { file = "unwritten (" + e.getClass().getSimpleName() + ")"; }
        }
        ClaudeMod.LOGGER.info("[Claude incident] {}: {} (file {})\n{}", kind, why, file, text);
    }

    /** Self-check of the pure parts. */
    public static void main(String[] a) {
        if (!"cobblestone".equals(missing("failed: to craft furnace you are missing cobblestone"))) throw new AssertionError(missing("failed: to craft furnace you are missing cobblestone"));
        if (!"oak planks".equals(missing("failed: missing 3 oak planks (the table)"))) throw new AssertionError(missing("failed: missing 3 oak planks (the table)"));
        if (!"cobblestone".equals(gatherable("cobbled deepslate"))) throw new AssertionError("deepslate");
        System.out.println("Watchdog self-check passed");
    }
}
