package com.claudemod.entity.life;

import net.minecraft.core.BlockPos;

import java.util.AbstractMap;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * What he tried lately and where it failed, by kind (2026-10-29, robustness plan item 5). One map shared by walks, placements
 * and digs let a failure of one kind silence another at the same spot: a walk that failed near an ore later skipped the dig of
 * that ore without a word; a placement that failed at a cell ended the next walk to that cell before it began. Fake positions
 * (a cell 20000 up for "no step up from here", (0, -30010, 0) for an aquifer count) carried other kinds and counters in the same
 * map. Here each kind has its own record, counters have names, and {@link #any} answers "did anything fail there lately" for the
 * planning code that only needs that.
 */
public final class Attempts {
    public enum Kind {
        WALK,      // a walk to the cell ended without getting there
        PLACE,     // a block couldn't be set there (no stand spot, no face, out of reach)
        BREAK,     // a block there was left (a guard refused it, no tool, open water above)
        REACH,     // no way to get within reach of the block (the approach gave up)
        STEP_UP,   // keyed by his feet: steps up from here didn't take
        SENT_FROM, // keyed by his feet: a walk from here was sent the long way round
        TOOL_WALK, // a table he walked to for a tool
        STATION    // a spot he tried to set a station in
    }

    /** Why it failed (the user 11-04: "fix causes, not counters"): a skip holds while that cause holds, and ends when it changes. */
    public enum Reason {
        NO_PATH,       // no way there with every planner he has (walk, dig, bridge, tower, his rules bent)
        BLOCKED_BLOCK, // a block in the way he could break
        UNBREAKABLE,   // a block in the way nobody breaks (bedrock, a portal frame, a barrier)
        BLOCKED_MOB,   // a mob in the cell or on the way
        BLOCKED_FLUID, // water or lava in the cell or on the way
        OUT_OF_REACH,  // no stand spot within reach of it
        NO_FACE,       // nothing to place against
        NO_TOOL,       // the right tool not on him
        NO_BLOCKS,     // nothing to put down
        CONTAINER,     // a chest or furnace of his (or theirs) there
        GUARD,         // one of his own rules refused it (a hazard hold, etiquette, their blocks)
        BODY_REFUSED,  // the plan had a step his body would not take (2+ up, lava): plan and body disagree, a bug
        UNKNOWN        // nobody said why: a bug (files an incident)
    }
    /** One failure with its cause and what it looked like then: the blocks round the cell, his tools and blocks, where he stood, the mob in the way. */
    public record Why(Reason reason, String detail, java.util.List<BlockPos> stood, int tick, long world, long kit, java.util.UUID mob) {}
    /** The entity these are his (the change checks read his kit, his feet and his level); null in the self-check. */
    public final com.claudemod.entity.ClaudeEntity g;
    private final EnumMap<Kind, Map<BlockPos, Why>> why = new EnumMap<>(Kind.class);

    private final EnumMap<Kind, Map<BlockPos, Integer>> at = new EnumMap<>(Kind.class);
    /** Named counts (aquifer plugs, the window they count over): not positions. */
    public final Map<String, Integer> counters = new HashMap<>();

    public Attempts() { this(null); }
    public Attempts(com.claudemod.entity.ClaudeEntity owner) { g = owner; for (Kind k : Kind.values()) { at.put(k, new HashMap<>()); why.put(k, new HashMap<>()); } }

    /** The cause it failed for (null: written without one). */
    public Why why(Kind k, BlockPos p) { return why.get(k).get(p); }

    /**
     * A failure with its cause. The skip holds while nothing that cause depends on has changed: the blocks within 2 of the cell, his
     * tools and blocks, where he stands (8 standings remembered: a go from somewhere new is a new go, a go from an old standing is not),
     * and for a mob, that mob still within 4. No clock ends it.
     */
    public void fail(Kind k, BlockPos p, int tick, BlockPos stood, Reason r, String detail) { fail(k, p, tick, stood, r, detail, null); }
    public void fail(Kind k, BlockPos p, int tick, BlockPos stood, Reason r, String detail, java.util.UUID mob) {
        BlockPos c = p.immutable(); Why old = why.get(k).get(c);
        java.util.List<BlockPos> st = new java.util.ArrayList<>(old != null ? old.stood() : java.util.List.of());
        if (stood != null) { st.add(stood.immutable()); if (st.size() > 8) st.remove(0); }
        failAt(k, c, tick);
        if (stood != null) { if (from == null) from = new EnumMap<>(Kind.class); from.computeIfAbsent(k, x -> new HashMap<>()).put(c, stood.immutable()); }
        Map<BlockPos, Why> w = why.get(k); if (w.size() > 512) w.keySet().retainAll(at.get(k).keySet());
        w.put(c, new Why(r, detail == null ? "" : detail, st, tick, worldSig(c), kitSig(), mob));
        com.claudemod.ClaudeMod.LOGGER.info("[Claude attempt] {} at {} failed: {}{} (from {}; by {}) (Attempts.fail)", k, c.toShortString(), r, detail == null || detail.isEmpty() ? "" : " " + detail, stood == null ? "?" : stood.toShortString(), caller());
        if (r == Reason.UNKNOWN || r == Reason.BODY_REFUSED) bandaid("a " + k + " failure at " + c.toShortString() + " with " + (r == Reason.UNKNOWN ? "no cause given" : "a step his body refused: plan and body disagree") + " (" + caller() + ")");
    }

    /** What changed since the failure (the skip is over: the record goes), or null: the cause still holds. Records with no cause: null. */
    public String changed(Kind k, BlockPos p) {
        Why w = why.get(k).get(p);
        if (w == null || g == null || !(g.level() instanceof net.minecraft.server.level.ServerLevel lv)) return null;
        String ch = null;
        if (worldSig(p) != w.world()) ch = "the blocks round it changed";
        else if (kitSig() != w.kit()) ch = "his tools or blocks changed";
        else if (!w.stood().isEmpty() && w.stood().stream().noneMatch(s0 -> s0.closerThan(g.blockPosition(), far(w.reason()) ? 8 : 2.5))) ch = "he stands somewhere new (" + g.blockPosition().toShortString() + ")";
        else if (w.mob() != null) { var m = lv.getEntity(w.mob()); if (m == null || !m.isAlive() || !m.blockPosition().closerThan(p, 4)) ch = "the mob in the way moved off"; }
        if (ch != null) { forget(k, p); com.claudemod.ClaudeMod.LOGGER.info("[Claude attempt] {} at {} back on: {} (was {}{}) (Attempts.changed)", k, p.toShortString(), ch, w.reason(), w.detail().isEmpty() ? "" : " " + w.detail()); }
        return ch;
    }

    /** Skipped: a failure on record whose cause still holds. A record with no cause keeps the old window (ticks), as before. */
    public boolean skipped(Kind k, BlockPos p, int now, int ticks) {
        Integer t = at.get(k).get(p); if (t == null) return false;
        if (why.get(k).get(p) == null) return now - t < ticks;
        return changed(k, p) == null;
    }

    /** A cause his planners searched the whole area for (no path, a block nobody breaks, a fluid): only standing in another area (8+ off) is a new go; reach-type causes change a step away. */
    private static boolean far(Reason r) { return r == Reason.NO_PATH || r == Reason.UNBREAKABLE || r == Reason.BLOCKED_FLUID; }

    private int sweepAt; // (round the records a few a tick: the planners read the maps directly, so changed ones must leave them)
    /** A few records a call: those whose cause no longer holds leave (called each tick from his work loop). */
    public void sweep() {
        if (g == null) return;
        java.util.List<java.util.Map.Entry<Kind, BlockPos>> all = new java.util.ArrayList<>();
        for (Kind k : CELL_KINDS) for (BlockPos p : why.get(k).keySet()) all.add(java.util.Map.entry(k, p));
        if (all.isEmpty()) return;
        for (int i = 0; i < Math.min(8, all.size()); i++) { var e = all.get((sweepAt++ & 0x7fffffff) % all.size()); if (at.get(e.getKey()).containsKey(e.getValue())) changed(e.getKey(), e.getValue()); else why.get(e.getKey()).remove(e.getValue()); }
    }

    private long worldSig(BlockPos p) {
        if (g == null) return 0; var lv = g.level(); long h = 17;
        for (BlockPos q : BlockPos.betweenClosed(p.offset(-2, -2, -2), p.offset(2, 2, 2))) h = h * 31 + net.minecraft.world.level.block.Block.getId(lv.getBlockState(q));
        return h;
    }
    private long kitSig() {
        if (g == null) return 0; long h = 7; int blocks = 0;
        for (int i = 0; i < g.getInventory().getContainerSize(); i++) { var s = g.getInventory().getItem(i); if (s.isEmpty()) continue;
            if (s.getItem() instanceof net.minecraft.world.item.TieredItem || s.is(net.minecraft.world.item.Items.BUCKET) || s.is(net.minecraft.world.item.Items.WATER_BUCKET) || s.is(net.minecraft.world.item.Items.FLINT_AND_STEEL) || s.is(net.minecraft.world.item.Items.SHEARS)) h = h * 31 + net.minecraft.core.registries.BuiltInRegistries.ITEM.getId(s.getItem());
            else if (s.getItem() instanceof net.minecraft.world.item.BlockItem) blocks += s.getCount(); }
        return h * 31 + (blocks == 0 ? 0 : blocks < 16 ? 1 : blocks < 64 ? 2 : 3); // (blocks by the handful: one more cobble is not a new situation, the first stack is)
    }
    private static String caller() { return StackWalker.getInstance().walk(f -> f.filter(x -> !x.getClassName().endsWith("Attempts") && !x.getClassName().contains("Attempts$")).findFirst().map(x -> x.getClassName().substring(x.getClassName().lastIndexOf('.') + 1) + "." + x.getMethodName() + ":" + x.getLineNumber()).orElse("?")); }
    /** A give-up with no cause, or a generic timer firing: an incident (the bug list), once per site per 10 minutes. */
    public void bandaid(String what) {
        if (g == null) return; String key = "bandaid:" + caller(); Integer t0 = counters.get(key);
        if (t0 != null && g.tickCount - t0 < 20 * 600 && g.tickCount >= t0) return;
        counters.put(key, g.tickCount);
        try { Watchdog.incident(g, "BANDAID", what); } catch (RuntimeException e) { com.claudemod.ClaudeMod.LOGGER.warn("[Claude attempt] incident for {} threw", what, e); }
    }

    /** The record of one kind, cell to the tick it last failed (the walk planner reads WALK as its "failed lately" penalty). */
    public Map<BlockPos, Integer> of(Kind k) { return at.get(k); }

    private EnumMap<Kind, Map<BlockPos, BlockPos>> from; // where his feet were when it failed there (lazy: fields added live start null)
    /** A failure and where he stood for it: another go from the same standing, nothing changed, is the same failure (PitGuard.resume). */
    public void fail(Kind k, BlockPos p, int tick, BlockPos stood) {
        fail(k, p, tick);
        if (stood == null) return;
        if (from == null) from = new EnumMap<>(Kind.class);
        Map<BlockPos, BlockPos> m = from.computeIfAbsent(k, x -> new HashMap<>());
        if (m.size() > 512) m.keySet().retainAll(at.get(k).keySet());
        m.put(p.immutable(), stood.immutable());
    }
    /** Where his feet were when it last failed there; null: not recorded with it (or no failure on record). */
    public BlockPos from(Kind k, BlockPos p) { if (from == null || !at.get(k).containsKey(p)) return null; Map<BlockPos, BlockPos> m = from.get(k); return m == null ? null : m.get(p); }
    public void fail(Kind k, BlockPos p, int tick) { if (from != null && from.get(k) != null) from.get(k).remove(p); failAt(k, p, tick); why.get(k).remove(p); } // (no cause given: the old window applies; the sites move to fail(..., Reason, detail) one by one)
    private void failAt(Kind k, BlockPos p, int tick) {
        Map<BlockPos, Integer> m = at.get(k);
        if (m.size() > 512) m.values().removeIf(t -> tick - t > 20 * 600); // (older than 10 minutes: nothing reads that far back)
        m.put(p.immutable(), tick);
        if (k == Kind.WALK) com.claudemod.ClaudeMod.LOGGER.info("[Claude walk] {} written off as a failed walk by {} (Attempts.fail)", p.toShortString(), StackWalker.getInstance().walk(f -> f.skip(1).filter(x -> !x.getClassName().endsWith("Attempts")).findFirst().map(x -> x.getClassName().substring(x.getClassName().lastIndexOf('.') + 1) + "." + x.getMethodName() + ":" + x.getLineNumber()).orElse("?"))); // ponytail: live 15:38, a walk to his portal 96 off was skipped as "failed 0 s ago" with no line saying who wrote it off
    }
    public Integer last(Kind k, BlockPos p) { return at.get(k).get(p); }
    public boolean within(Kind k, BlockPos p, int now, int ticks) { return skipped(k, p, now, ticks); } // (a failure with its cause holds till the cause changes; one without keeps the window)
    public void forget(Kind k, BlockPos p) { at.get(k).remove(p); why.get(k).remove(p); if (from != null && from.get(k) != null) from.get(k).remove(p); }
    /** A fresh go at the cell, every kind (a new pass at a placement, the roof from on top). */
    public void forget(BlockPos p) { for (Map<BlockPos, Integer> m : at.values()) m.remove(p); for (Map<BlockPos, Why> m : why.values()) m.remove(p); }
    public void clear() { for (Map<BlockPos, Integer> m : at.values()) m.clear(); for (Map<BlockPos, Why> m : why.values()) m.clear(); counters.clear(); from = null; }

    private static final Kind[] CELL_KINDS = {Kind.WALK, Kind.PLACE, Kind.BREAK, Kind.REACH};
    /** The latest failure of any kind at the cell (walk, place, break, reach), or null; one whose cause still holds is "now". */
    public Integer anyAt(BlockPos p) {
        Integer best = null;
        for (Kind k : CELL_KINDS) { Integer t = at.get(k).get(p); if (t != null && why.get(k).get(p) != null) t = changed(k, p) != null ? null : g == null ? t : Integer.valueOf(g.tickCount); if (t != null && (best == null || t > best)) best = t; } // (a cause that still holds reads as failing now: the readers' own windows don't end it, a change does)
        return best;
    }

    /**
     * "Did anything fail at this cell lately", as a map, for the planning code that only asks that (pick another tree, another
     * table, another hole). Reads see every kind; a put records a WALK failure; a remove gives the cell a fresh go of every kind.
     */
    public final Map<BlockPos, Integer> any = new AbstractMap<>() {
        @Override public Integer get(Object k) { return k instanceof BlockPos p ? anyAt(p) : null; }
        @Override public boolean containsKey(Object k) { return get(k) != null; }
        @Override public Integer put(BlockPos k, Integer v) { Integer was = anyAt(k); at.get(Kind.WALK).put(k.immutable(), v); return was; }
        @Override public Integer remove(Object k) { if (!(k instanceof BlockPos p)) return null; Integer was = anyAt(p); forget(p); return was; }
        @Override public void clear() { for (Kind k : CELL_KINDS) at.get(k).clear(); }
        @Override public Set<Entry<BlockPos, Integer>> entrySet() { // (a snapshot: the readers only look)
            Map<BlockPos, Integer> merged = new HashMap<>();
            for (Kind k : CELL_KINDS) for (var e : at.get(k).entrySet()) merged.merge(e.getKey(), e.getValue(), Math::max);
            return new HashSet<>(merged.entrySet());
        }
    };

    /** Self-check: kinds don't silence each other; the any view sees them all; counters are names, not cells. */
    public static void main(String[] a) {
        Attempts t = new Attempts(); BlockPos ore = new BlockPos(1, 2, 3), cell = new BlockPos(4, 5, 6);
        t.fail(Kind.WALK, ore, 100);
        if (t.within(Kind.REACH, ore, 120, 6000) || t.within(Kind.BREAK, ore, 120, 6000)) throw new AssertionError("a walk failure silenced the dig");
        if (!t.within(Kind.WALK, ore, 120, 6000) || t.any.get(ore) != 100) throw new AssertionError("the walk failure lost");
        t.fail(Kind.PLACE, cell, 200);
        if (t.within(Kind.WALK, cell, 210, 6000)) throw new AssertionError("a place failure ended the walk");
        if (!t.any.containsKey(cell)) throw new AssertionError("any view missed the place failure");
        t.any.put(cell, 300); if (t.last(Kind.WALK, cell) != 300) throw new AssertionError("any.put isn't a walk failure");
        t.any.remove(cell); if (t.anyAt(cell) != null) throw new AssertionError("any.remove left a kind behind");
        t.fail(Kind.PLACE, cell, 400, ore); if (!ore.equals(t.from(Kind.PLACE, cell))) throw new AssertionError("where he stood lost");
        t.fail(Kind.PLACE, cell, 500); if (t.from(Kind.PLACE, cell) != null) throw new AssertionError("an older failure's standing kept");
        t.fail(Kind.WALK, ore, 600, cell, Reason.NO_PATH, "test"); if (t.why(Kind.WALK, ore).reason() != Reason.NO_PATH || !t.skipped(Kind.WALK, ore, 99999, 1)) throw new AssertionError("a caused failure lost its cause or ran out on a clock");
        t.fail(Kind.WALK, ore, 700); if (t.why(Kind.WALK, ore) != null || t.skipped(Kind.WALK, ore, 99999, 1)) throw new AssertionError("a causeless failure kept a cause or skipped past its window");
        t.counters.merge("aquiferPlugs", 3, Integer::sum); if (t.any.containsKey(new BlockPos(0, -30010, 0))) throw new AssertionError("a counter landed in a cell");
        System.out.println("Attempts self-check passed");
    }
}
