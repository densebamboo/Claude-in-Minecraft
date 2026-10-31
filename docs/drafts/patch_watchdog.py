# No-progress watchdog: progress measured per action, not by "moved 3 blocks" (2026-10-31; the user: "every scenario needs to be
# accounted for and have robust infrastructure"). Live 00:13: the same sprint-jump over 2, up 1, missed 5 times in 10 s; 23:56 a
# long-walk hop re-picked its own target every 2 s. noticeStuck (20 s escape plan, 45 s drop + Attempts, 90 s replan) counted
# any 3-block move as progress, so a missed jump and the fall back, or a shuffle, reset it forever.
# Fix: a task with a target (GoTo, Break, Place, Leap, UseDoor, Chest, Deposit, Till, WallTorch, Replant) makes progress only by
# getting 1+ block closer to its target than his best on that same task (or by a new task, an item gained, a block broken); other
# tasks keep the old move measure; waiting / eating / fighting / fleeing stay exempt. The second time one step gets stuck he says
# so once, exactly ("stuck: <doing> at x y z, no closer in N s; trying another way"). Every reset and trip is logged.
# Stage 474: a walk 12 off on open ground, knocked back to the start every 1.5 s (a missed jump falling back): stuck noticed
# within 25 s (main: never). Usage: python patch_watchdog.py [mod dir]
import io, os, sys
ROOT = sys.argv[1] if len(sys.argv) > 1 else r"C:\Users\lipid\Desktop\claude-mod"
J = os.path.join(ROOT, "src", "main", "java", "com", "claudemod")
LIFE = os.path.join(J, "director", "ClaudeLife.java"); T = os.path.join(J, "test", "ClaudeBodyTest.java")
def rd(p): return io.open(p, encoding="utf-8").read()
def wr(p, s): io.open(p, "w", encoding="utf-8", newline="").write(s)
def patch(path, old, new):
    s = rd(path); c = s.count(old); assert c == 1, (os.path.basename(path), old[:90], c); wr(path, s.replace(old, new))

patch(LIFE, "    private static void noticeStuck(ClaudeEntity g, ServerPlayer player) {\n",
'''    private static java.util.Map<java.util.UUID, Task> progTask; private static java.util.Map<java.util.UUID, Double> progBest; // the task he's measured on, and his best distance to its target (static: fields added live start null)
    /** The task's own progress measure: how far his feet are from its target; -1: no target (the move measure applies). */
    static double targetDist(ClaudeEntity g, Task t) {
        BlockPos p = t instanceof Task.GoTo x ? x.pos() : t instanceof Task.Break x ? x.pos() : t instanceof Task.Place x ? x.pos() : t instanceof Task.Leap x ? x.land()
                : t instanceof Task.UseDoor x ? x.pos() : t instanceof Task.Chest x ? x.pos() : t instanceof Task.Deposit x ? x.pos() : t instanceof Task.Till x ? x.pos()
                : t instanceof Task.WallTorch x ? x.pos() : t instanceof Task.Replant x ? x.pos() : null;
        return p == null ? -1 : Math.sqrt(p.distToCenterSqr(g.position()));
    }
    private static void noticeStuck(ClaudeEntity g, ServerPlayer player) {
        if (progTask == null) progTask = new java.util.HashMap<>(); if (progBest == null) progBest = new java.util.HashMap<>();
''')
patch(LIFE, "        if (!busy || g.progressPos == null || at.distSqr(g.progressPos) > 3 * 3 || items != g.progressItems || brk != progressBroken.getOrDefault(g.getUUID(), -1)) {\n",
'''        double tgt = now == null ? -1 : targetDist(g, now);
        boolean sameTask = now != null && now.equals(progTask.get(g.getUUID()));
        boolean closer = tgt >= 0 && sameTask && tgt < progBest.getOrDefault(g.getUUID(), Double.MAX_VALUE) - 1.0; // a block nearer than his best on it
        boolean moved = (tgt < 0 || !sameTask) && g.progressPos != null && at.distSqr(g.progressPos) > 3 * 3; // no target: the move measure; a new task: a fresh start
        if (busy && tgt >= 0 && sameTask && !closer && g.progressPos != null && at.distSqr(g.progressPos) > 3 * 3 && g.tickCount % 200 == 17) ClaudeMod.LOGGER.info("[Claude life] moving but no nearer: {} best {} now {} (ClaudeLife.noticeStuck)", ClaudeChat.describe(g, now), String.format("%.1f", progBest.getOrDefault(g.getUUID(), -1.0)), String.format("%.1f", tgt));
        if (!busy || g.progressPos == null || !sameTask || closer || moved || items != g.progressItems || brk != progressBroken.getOrDefault(g.getUUID(), -1)) {
            progTask.put(g.getUUID(), now); if (tgt >= 0) progBest.put(g.getUUID(), Math.min(tgt, sameTask ? progBest.getOrDefault(g.getUUID(), tgt) : tgt)); else progBest.remove(g.getUUID());
''')
patch(LIFE, "            int strikes = g.failCount.merge(stuckKey, 1, Integer::sum);\n",
'''            int strikes = g.failCount.merge(stuckKey, 1, Integer::sum);
            if (strikes == 2 && player != null) ClaudeChat.say(player, "stuck: " + doing + " at " + at.toShortString() + ", no closer in " + secs + " s; trying another way", "stuck twice: " + stuckKey); // (the user: say exactly what failed)
''')
s = rd(T); i = s.index("    private static boolean exactStages("); d = "                    default -> { return false; }"; j = s.index(d, i)
assert "case 474 ->" not in s
s = s[:j] + '''                    case 474 -> { // a walk 12 off, knocked back to the start every 1.5 s (a missed jump falling back): the watchdog sees no progress though he keeps moving
                        BlockPos start = h.absolutePos(new BlockPos(2, 2, 7)), goal = h.absolutePos(new BlockPos(14, 2, 7));
                        if (t[0] == 1) { scenarioReset(c); c.stuckLevel = 0; for (int x = 1; x <= 15; x++) for (int z = 4; z <= 10; z++) { level.setBlock(h.absolutePos(new BlockPos(x, 1, z)), Blocks.STONE.defaultBlockState(), 2); for (int y = 2; y <= 4; y++) level.setBlock(h.absolutePos(new BlockPos(x, y, z)), Blocks.AIR.defaultBlockState(), 2); } }
                        if (t[0] == 10) { c.teleportTo(start.getX() + 0.5, start.getY(), start.getZ() + 0.5); c.playerGoal = "walk over there"; c.tasks.add(new com.claudemod.entity.life.Task.GoTo(goal, 0.5)); }
                        if (t[0] > 10 && t[0] % 30 == 0 && c.stuckLevel == 0) { c.teleportTo(start.getX() + 0.5, start.getY(), start.getZ() + 0.5); if (c.tasks.isEmpty() && c.currentTask == null) c.tasks.add(new com.claudemod.entity.life.Task.GoTo(goal, 0.5)); }
                        if (c.stuckLevel >= 1) { pass(stage, t, "no progress seen in " + (t[0] - 10) / 20 + " s though he kept moving"); c.tasks.clear(); c.stuckLevel = 0; c.playerGoal = ""; }
                        else if (t[0] == 520) check(false, "watchdog: knocked back every 1.5 s for 25 s and never seen stuck (task " + c.currentTask + ")");
                    }
''' + s[j:]
wr(T, s)
print("patch_watchdog: applied to", ROOT)
