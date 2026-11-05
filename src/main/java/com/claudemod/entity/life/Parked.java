package com.claudemod.entity.life;

import com.claudemod.entity.ClaudeEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One owner for "that patch can't be done yet". A dig or a fill his upkeep systems queued (bpReconcile, bpIntegrity,
 * TempLedger, Consent, the build systems) refused 3 times in 60 s (MindLog's conflict) is parked with its reason, and his queue won't take it
 * from them again until something changed: he has more of the block, he's 8+ off where it failed, or 2 min passed.
 * (Live 11-04: 840 + 338 + 98 + 85 conflict lines, each system re-queuing the same refused patch every few seconds.)
 */
public final class Parked {
    private Parked() {}
    private record Park(String why, int at, BlockPos from, Item item, int had) {}
    private static Map<UUID, Map<String, Park>> books; // (static: fields added live start null)
    private static final java.util.regex.Pattern UPKEEP = java.util.regex.Pattern.compile("^(ClaudeLife\\.bp\\w+|ClaudeLife\\.mineIntegrity|TempLedger\\.\\w+|Consent\\.\\w+)$");

    private static Map<String, Park> book(ClaudeEntity g) { if (books == null) books = new HashMap<>(); return books.computeIfAbsent(g.getUUID(), k -> new HashMap<>()); }
    private static String key(Task t) { return t instanceof Task.Break b ? "dig " + b.pos().asLong() : t instanceof Task.Place p ? "fill " + p.pos().asLong() : null; }
    private static BlockPos pos(Task t) { return t instanceof Task.Break b ? b.pos() : ((Task.Place) t).pos(); }
    /** Queued by one of his upkeep systems (the origin's class.method, its line number stripped). */
    static boolean upkeep(Task t) { String o = ClaudeEntity.originOf(t).replaceAll(":\\d+$", ""); return UPKEEP.matcher(o).matches() || TempLedger.BUILD_SYSTEMS.contains(o); }

    /** A dig or fill gave up: an upkeep system's goes on the book, with why. */
    public static void refused(ClaudeEntity g, Task t, String why) {
        String k = key(t); if (k == null || !upkeep(t)) return;
        if (g.attempts.counters.getOrDefault("conflict:" + pos(t).asLong(), 0) < 3) return; // (a one-off miss is retried as before: the 3rd refusal in 60 s, MindLog's conflict, is the loop)
        Item it = t instanceof Task.Place p ? p.item() : null;
        book(g).put(k, new Park(why == null ? "no reason given" : why, g.tickCount, g.blockPosition().immutable(), it, it == null ? 0 : g.inventory.count(s -> s.is(it))));
        com.claudemod.ClaudeMod.LOGGER.info("[Claude parked] {} at {} ({}) refused: {}: not queued again till he has more of the block, is 8+ off, or 2 min pass (Parked.refused)", k.split(" ")[0], pos(t).toShortString(), ClaudeEntity.originOf(t), why);
    }

    /** The queue's one check: true while that upkeep job is parked and nothing has changed since. */
    public static boolean blocks(ClaudeEntity g, Task t) {
        if (books == null) return false;
        String k = key(t); if (k == null) return false;
        Map<String, Park> b = books.get(g.getUUID()); Park p = b == null ? null : b.get(k);
        if (p == null || !upkeep(t)) return false;
        String back = g.tickCount - p.at() > 20 * 120 ? "2 min passed" : p.from().distSqr(g.blockPosition()) > 8 * 8 ? "he's 8+ off where it failed" : p.item() != null && p.had() == 0 && g.inventory.count(s -> s.is(p.item())) > 0 ? "he has the block now" : null; // (only a park made with none of the block on him is about the block: 40+ cobble on him, one more picked up let a park about where he stood back in, refused again, 02:44:21 11-05, stage 711)
        if (back == null) return true;
        b.remove(k); Watchdog.cleared(g, t); g.attempts.of(Attempts.Kind.PLACE).remove(pos(t)); g.attempts.of(Attempts.Kind.REACH).remove(pos(t)); g.attempts.of(Attempts.Kind.BREAK).remove(pos(t)); g.attempts.counters.remove("conflict:" + pos(t).asLong()); // (the other owners of "not again yet": the watchdog's write-off, doPlace's 5 min guard, the reach skip)
        com.claudemod.ClaudeMod.LOGGER.info("[Claude parked] {} at {} back on ({}; it was: {}) (Parked.blocks)", k.split(" ")[0], pos(t).toShortString(), back, p.why());
        return false;
    }
}
