package com.claudemod.entity.life;

import com.claudemod.ClaudeMod;
import com.claudemod.entity.ClaudeEntity;
import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The no-progress gate (the user 11-08: "why is he pacing and spinning", a universal fix, not pair by pair). Every job of his
 * goes through g.tasks; this measures each job with a target from its start to its end: his feet against the target, the
 * target block (state and contents), his pack. A job that ended with none of them changed is on record; the identical job
 * (same record, same target) is refused at the queue until something about it changes: the block, his pack, his spot by 2+,
 * or 2 minutes (mobs move, the world goes on). A job that puts itself back (a nudge, a walk in front of it) is its own
 * bounded retry and never recorded. The refusal names who asked (MindLog, the conflict line) and the skill that asked
 * hears it as a failure (ClaudeLife.runSkill), so the plan decides instead of asking again.
 */
public final class Gate {
    private Gate() {}

    /** The world as the job saw it: his feet, the target cell's block and contents, his pack. */
    record Snap(BlockPos feet, String block, int pack, double dist) {}
    /** A job that ended with nothing changed: the world then, when, why it ended, and who first queued it. */
    record Rec(Snap at, long tick, String why, String origin) {}

    private static final Map<UUID, Map<Task, Rec>> BOOK = new HashMap<>();
    private static final Map<UUID, Map<Task, Long>> SAID = new HashMap<>();
    private static final Map<UUID, Object[]> START = new HashMap<>(); // {task, snap}
    /** Refusals so far (ClaudeLife.runSkill reads the count around a skill; stages read it). */
    public static final Map<UUID, Integer> REFUSED = new HashMap<>();
    public static final Map<UUID, String> LAST_WHY = new HashMap<>();
    public static final long MEMORY = 20 * 120;
    /** A dig or a fill that changed its cell: what it was, what it left, who queued it. */
    record Change(Task task, String before, String after, String origin, long tick) {}
    private static final Map<UUID, Map<Long, Change[]>> HIST = new HashMap<>(); // cell -> {the one before, the last}
    /** The referee's losers: the job not to queue again while the cell stays as the winner left it. */
    record Lost(String keep, long tick, String why) {}
    private static final Map<UUID, Map<Task, Lost>> LOST = new HashMap<>();
    public static final Map<UUID, Integer> REFEREED = new HashMap<>();

    private static Map<Task, Rec> book(ClaudeEntity g) { return BOOK.computeIfAbsent(g.getUUID(), k -> new HashMap<>()); }
    private static long now(ClaudeEntity g) { return g.level().getGameTime(); }
    public static void reset(ClaudeEntity g) { BOOK.remove(g.getUUID()); SAID.remove(g.getUUID()); START.remove(g.getUUID()); HIST.remove(g.getUUID()); LOST.remove(g.getUUID()); }
    public static int refused(ClaudeEntity g) { return REFUSED.getOrDefault(g.getUUID(), 0); }

    /** The jobs it measures: those with a target cell, the fight and the flight left out (D2D's combat rewrite owns those). */
    private static BlockPos target(Task t) {
        if (t instanceof Task.Attack || t instanceof Task.Flee || t instanceof Task.Wait || t instanceof Task.Pillar || t instanceof Task.Dragon) return null;
        return Watchdog.target(t);
    }

    static Snap snap(ClaudeEntity g, BlockPos p) {
        var level = g.level(); String block = "";
        if (p != null && level.isLoaded(p)) {
            block = level.getBlockState(p).toString();
            var be = level.getBlockEntity(p);
            // (a container by its contents only: a lit furnace's clocks tick every tick, its output slot is what a take waits on)
            if (be instanceof net.minecraft.world.Container c) { int h = 1; for (int i = 0; i < c.getContainerSize(); i++) { var st = c.getItem(i); h = h * 31 + (st.isEmpty() ? 0 : st.getItem().hashCode() * 67 + st.getCount()); } block += "#" + h; }
            else if (be != null) { try { block += "#" + be.saveWithoutMetadata(level.registryAccess()).hashCode(); } catch (RuntimeException e) { block += "#?"; } }
        }
        int pack = 1;
        for (var list : java.util.List.of(g.getInventory().items, g.getInventory().armor, g.getInventory().offhand))
            for (var st : list) pack = pack * 31 + (st.isEmpty() ? 0 : st.getItem().hashCode() * 67 + st.getCount());
        BlockPos feet = BlockPos.containing(g.getX(), g.getY() + 0.2, g.getZ());
        return new Snap(feet, block, pack, p == null ? -1 : Math.sqrt(p.distToCenterSqr(g.position())));
    }

    /** WorkGoal: the job starts (after Watchdog.starting let it run). */
    public static void started(ClaudeEntity g, Task t, String whyBefore) {
        BlockPos p = target(t); if (p == null) { START.remove(g.getUUID()); return; }
        START.put(g.getUUID(), new Object[] {t, snap(g, p), whyBefore});
    }

    /** WorkGoal: the job ended (why: its exit line). Nothing changed and not put back by itself: on record. */
    public static void ended(ClaudeEntity g, Task t, String why) {
        Object[] s0 = START.remove(g.getUUID());
        BlockPos p = target(t); if (p == null || s0 == null || !t.equals(s0[0])) return;
        Snap a = (Snap) s0[1], b = snap(g, p);
        if (!a.block().equals(b.block()) && (t instanceof Task.Break || t instanceof Task.Place)) { // (the referee's history: who changed the cell to what)
            Map<Long, Change[]> h = HIST.computeIfAbsent(g.getUUID(), k -> new HashMap<>()); if (h.size() > 2000) h.clear();
            Change[] was = h.get(p.asLong());
            h.put(p.asLong(), new Change[] {was == null ? null : was[1], new Change(t, a.block(), b.block(), ClaudeEntity.originOf(t), now(g))});
        }
        if (g.tasks.contains(t)) return; // (put itself back: a nudge, the block in front first, a walk to a stand spot; its own bounded retry)
        if (Boolean.TRUE.equals(Watchdog.achieved(g, t))) { book(g).remove(t); return; }
        boolean nearer = a.dist() >= 0 && b.dist() <= a.dist() - 1.0;
        if (nearer || !a.block().equals(b.block()) || a.pack() != b.pack()) { book(g).remove(t); return; }
        if (why == s0[2]) why = null; // (the last job's exit line, left standing: not this one's)
        book(g).put(t, new Rec(b, now(g), why == null ? "no reason given" : why, ClaudeEntity.originOf(t)));
    }

    /**
     * The queue (ClaudeEntity.tasks): may this job go in? False, said with who asked, when the identical job ended last time with
     * nothing changed and nothing has changed since. The running job putting itself back is always let in.
     */
    private static final StackWalker WALK = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    /** Who is queuing it now: the first frame outside the queue and the gate (class.method:line). */
    private static String asker() {
        return WALK.walk(st -> st.filter(f -> !java.util.ArrayDeque.class.isAssignableFrom(f.getDeclaringClass()) && f.getDeclaringClass() != Gate.class && !f.getClassName().startsWith("java.")).findFirst()
                .map(f -> f.getDeclaringClass().getSimpleName() + "." + f.getMethodName() + ":" + f.getLineNumber()).orElse("?"));
    }

    public static boolean admits(ClaudeEntity g, Task t) {
        if ((t instanceof Task.Break || t instanceof Task.Place) && !referee(g, t)) return false;
        Map<Task, Rec> bk = BOOK.get(g.getUUID()); if (bk == null || bk.isEmpty()) return true;
        Rec r = bk.get(t); if (r == null) return true;
        if (t.equals(g.currentTask)) return true;
        BlockPos p = target(t); if (p == null) return true;
        Snap now0 = snap(g, p); String changed = null;
        if (now(g) - r.tick() > MEMORY) changed = "2 min passed";
        else if (!now0.block().equals(r.at().block())) changed = "the block there changed";
        else if (now0.pack() != r.at().pack()) changed = "his pack changed";
        else if (now0.feet().distSqr(r.at().feet()) >= 4) changed = "he moved " + (int) Math.sqrt(now0.feet().distSqr(r.at().feet())) + " off";
        if (changed != null) { bk.remove(t); return true; }
        String asker = asker();
        REFUSED.merge(g.getUUID(), 1, Integer::sum);
        String why = Watchdog.key(t) != null ? Watchdog.key(t) : t.getClass().getSimpleName() + " at " + p.toShortString();
        String line = why + " ended " + (now(g) - r.tick()) / 20 + " s ago with nothing changed (" + r.why() + "), and nothing has since";
        LAST_WHY.put(g.getUUID(), line);
        Map<Task, Long> said = SAID.computeIfAbsent(g.getUUID(), k -> new HashMap<>());
        Long last = said.get(t);
        if (last == null || now(g) - last > 20 * 20) {
            said.put(t, now(g)); if (said.size() > 256) said.clear();
            ClaudeMod.LOGGER.info("[Claude gate] not queueing {} again for {}: {} (first queued by {}) (Gate.admits)", t, asker, line, r.origin());
        }
        MindLog.refused(g, p, "gate: " + why + " asked again by " + asker);
        return false;
    }

    /** The referee kept this cell against a dig, and it still stands so: the route planner reads it, so the plan and the queue agree (a way through it would only be refused again, stage 1049). */
    public static boolean keptFromDig(ClaudeEntity g, BlockPos p) {
        Map<Task, Lost> lost = LOST.get(g.getUUID()); if (lost == null || lost.isEmpty()) return false;
        for (var e : lost.entrySet()) if (e.getKey() instanceof Task.Break b && b.pos().equals(p) && now(g) - e.getValue().tick() <= MEMORY && snap(g, p).block().equals(e.getValue().keep())) return true;
        return false;
    }

    /** What his mine's plan wants in a cell: true solid, false open, null no say. */
    private static Boolean planWants(ClaudeEntity g, BlockPos p) {
        if (g.blueprint == null) return null;
        MineBlueprint.Role r = g.blueprint.roleAt(p); if (r == null) return null;
        return r != MineBlueprint.Role.WALK;
    }

    /**
     * The block referee (the user 11-08: "[Claude owner] from a detector into the decision"). A dig or a fill that two of his
     * systems keep flipping: one did it, another undid it, and the first asks again. That third ask is a decision, priced by
     * what his mine's plan wants there (a cell with no plan: the cell stays as it stands, no work); the loser is told
     * and its job is refused while the cell stays as the winner left it (2 min at most).
     */
    private static boolean referee(ClaudeEntity g, Task t) {
        BlockPos p = Watchdog.target(t); if (p == null) return true;
        Map<Task, Lost> lost = LOST.get(g.getUUID());
        if (lost != null) { Lost l = lost.get(t);
            if (l != null) {
                if (now(g) - l.tick() <= MEMORY && snap(g, p).block().equals(l.keep())) { MindLog.refused(g, p, "referee: " + l.why()); REFUSED.merge(g.getUUID(), 1, Integer::sum); LAST_WHY.put(g.getUUID(), l.why()); return false; }
                lost.remove(t);
            } }
        Map<Long, Change[]> h = HIST.get(g.getUUID()); if (h == null) return true;
        Change[] c = h.get(p.asLong()); if (c == null || c[0] == null || c[1] == null) return true;
        Change mine = c[0], undo = c[1];
        if (!t.equals(mine.task()) || t.equals(undo.task()) || undo.tick() - mine.tick() > 20 * 60 || now(g) - undo.tick() > 20 * 60) return true;
        String here = snap(g, p).block();
        if (!here.equals(mine.before()) || !mine.before().equals(undo.after())) return true; // (not a flip back: something else since)
        if (t.equals(g.currentTask)) return true;
        String redoBy = ClaudeEntity.originOf(t), undoBy = undo.origin();
        boolean full = t instanceof Task.Place pl ? pl.item() instanceof net.minecraft.world.item.BlockItem bi && bi.getBlock().defaultBlockState().isCollisionShapeFullBlock(g.level(), p) : g.level().getBlockState(p).isCollisionShapeFullBlock(g.level(), p);
        Boolean wants = full ? planWants(g, p) : null; boolean redoSolid = t instanceof Task.Place; // (a torch in his walkway is no wall: the plan has no say on thin blocks)
        double work = Weights.w("referee.work_s", 2), against = Weights.w("referee.against_plan_s", 60);
        String what = t instanceof Task.Place ? "filled" : "dug out", other = t instanceof Task.Place ? "dug out" : "filled";
        var keep = Decide.Option.of("leave it " + other + " (" + undoBy + ")", wants == null ? 0 : wants == redoSolid ? against : 0, wants == null ? "no plan for the cell, it stands as it is" : "his mine's plan: " + (wants ? "solid" : "open"));
        var redo = Decide.Option.of(what + " again (" + redoBy + ")", work + (wants == null ? 0 : wants == redoSolid ? 0 : against), "");
        var pick = Decide.choose(g, "referee", p.toShortString() + " " + what + " by " + redoBy + ", then " + other + " by " + undoBy + ", now " + what + " again", true, java.util.List.of(keep, redo));
        REFEREED.merge(g.getUUID(), 1, Integer::sum);
        Map<Task, Lost> ls = LOST.computeIfAbsent(g.getUUID(), k -> new HashMap<>()); if (ls.size() > 256) ls.clear();
        if (pick == keep) {
            String why = p.toShortString() + " " + what + " again by " + redoBy + ": " + undoBy + " had it " + other + " " + (now(g) - undo.tick()) / 20 + " s ago, the referee kept that (" + keep.detail() + ")";
            ClaudeMod.LOGGER.info("[Claude referee] {} (Gate.referee)", why);
            ls.put(t, new Lost(here, now(g), why)); MindLog.refused(g, p, "referee: " + why); REFUSED.merge(g.getUUID(), 1, Integer::sum); LAST_WHY.put(g.getUUID(), why);
            return false;
        }
        String why = p.toShortString() + " " + other + " by " + undoBy + " undid " + redoBy + "; the referee gave it to " + redoBy + " (his mine's plan: " + (wants ? "solid" : "open") + ")";
        ClaudeMod.LOGGER.info("[Claude referee] {} (Gate.referee)", why);
        ls.put(undo.task(), new Lost(mine.after(), now(g), why)); // (the undoing job, told: not again while it stands as the plan wants)
        return true;
    }
}
