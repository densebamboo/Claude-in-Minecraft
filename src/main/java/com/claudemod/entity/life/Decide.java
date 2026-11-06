package com.claudemod.entity.life;

import com.claudemod.ClaudeMod;
import com.claudemod.entity.ClaudeEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * On a conflict he decides (the user 11-05: a conflict between his systems is resolved by his own decision-making, not just
 * detected). His options right then, each priced in seconds by the system that would carry it out; the cheapest feasible one is
 * taken and every price is said. An option he took for this goal stays open while it gains him ground, and is out once it didn't
 * (no counters: the measure is headway). When nothing of his own fits, the old skip stands (the planner gets a true conflict, the 3rd refusal, named).
 */
public final class Decide {
    private record Took(String option, double fromDist) {}
    private static final java.util.Map<java.util.UUID, java.util.Map<BlockPos, Took>> took = new java.util.HashMap<>();

    /** A walk to goal was refused by every way his walker has (why). True if he took one of his other ways (queued); false: the caller's skip stands. */
    public static boolean walkRefused(ClaudeEntity g, ServerLevel level, BlockPos goal, double within, String why) {
        var mine = took.computeIfAbsent(g.getUUID(), k -> new java.util.HashMap<>());
        if (mine.size() > 64) mine.clear();
        double d = Math.sqrt(g.blockPosition().distSqr(goal));
        Took before = mine.get(goal);
        boolean gained = before != null && d < before.fromDist() - 4; // (the last pick for this goal got him 4+ nearer: still a good pick)
        java.util.List<String> said = new java.util.ArrayList<>();
        String best = null; double bestS = Double.MAX_VALUE;
        boolean bores = FortressScout.bores(g, level);
        if (bores) {
            int n = Math.abs(goal.getX() - g.getBlockX()) + Math.abs(goal.getZ() - g.getBlockZ()) + Math.abs(goal.getY() - g.getBlockY());
            double s = FortressScout.BORE_S * n;
            boolean out = before != null && before.option().equals("bore") && !gained;
            said.add("bore straight there ~" + (int) s + " s (" + n + " blocks of line" + (out ? "; out: the last bore here got him no nearer" : "") + ")");
            if (!out && s < bestS) { best = "bore"; bestS = s; }
        } else said.add("bore: out (" + (level.dimension() == net.minecraft.world.level.Level.NETHER ? "no pick or under 16 blocks to floor with" : "only in the Nether") + ")");
        if (best == null) { best = "skip"; said.add("none of his own fits: the spot skipped as before"); } // (not priced against his own ways: a think still needs a way there after it)
        ClaudeMod.LOGGER.info("[Claude decide] the walk to {} ({} off) was refused ({}): {}: {} (Decide.walkRefused)", goal.toShortString(), (int) d, why, String.join("; ", said), best);
        mine.put(goal.immutable(), new Took(best, d));
        if (best.equals("bore")) {
            g.tasks.addFirst(new Task.GoTo(goal, within));
            g.tasks.addFirst(new Task.Bore(goal, goal.getY()));
            return true;
        }
        return false; // (none of his own: the skip stands; the planner gets it named on a conflict, the 3rd refusal: Watchdog.conflict)
    }
}
