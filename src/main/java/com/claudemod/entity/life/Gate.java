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

    private static Map<Task, Rec> book(ClaudeEntity g) { return BOOK.computeIfAbsent(g.getUUID(), k -> new HashMap<>()); }
    private static long now(ClaudeEntity g) { return g.level().getGameTime(); }
    public static void reset(ClaudeEntity g) { BOOK.remove(g.getUUID()); SAID.remove(g.getUUID()); START.remove(g.getUUID()); }
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
        if (g.tasks.contains(t)) return; // (put itself back: a nudge, the block in front first, a walk to a stand spot; its own bounded retry)
        if (Boolean.TRUE.equals(Watchdog.achieved(g, t))) { book(g).remove(t); return; }
        Snap a = (Snap) s0[1], b = snap(g, p);
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
}
