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

    private final EnumMap<Kind, Map<BlockPos, Integer>> at = new EnumMap<>(Kind.class);
    /** Named counts (aquifer plugs, the window they count over): not positions. */
    public final Map<String, Integer> counters = new HashMap<>();

    public Attempts() { for (Kind k : Kind.values()) at.put(k, new HashMap<>()); }

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
    public void fail(Kind k, BlockPos p, int tick) {
        if (from != null && from.get(k) != null) from.get(k).remove(p); // (this failure's standing unknown: not an older one's)
        Map<BlockPos, Integer> m = at.get(k);
        if (m.size() > 512) m.values().removeIf(t -> tick - t > 20 * 600); // (older than 10 minutes: nothing reads that far back)
        m.put(p.immutable(), tick);
    }
    public Integer last(Kind k, BlockPos p) { return at.get(k).get(p); }
    public boolean within(Kind k, BlockPos p, int now, int ticks) { Integer t = at.get(k).get(p); return t != null && now - t < ticks; }
    public void forget(Kind k, BlockPos p) { at.get(k).remove(p); if (from != null && from.get(k) != null) from.get(k).remove(p); }
    /** A fresh go at the cell, every kind (a new pass at a placement, the roof from on top). */
    public void forget(BlockPos p) { for (Map<BlockPos, Integer> m : at.values()) m.remove(p); }
    public void clear() { for (Map<BlockPos, Integer> m : at.values()) m.clear(); counters.clear(); from = null; }

    private static final Kind[] CELL_KINDS = {Kind.WALK, Kind.PLACE, Kind.BREAK, Kind.REACH};
    /** The latest failure of any kind at the cell (walk, place, break, reach), or null. */
    public Integer anyAt(BlockPos p) {
        Integer best = null;
        for (Kind k : CELL_KINDS) { Integer t = at.get(k).get(p); if (t != null && (best == null || t > best)) best = t; }
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
        t.counters.merge("aquiferPlugs", 3, Integer::sum); if (t.any.containsKey(new BlockPos(0, -30010, 0))) throw new AssertionError("a counter landed in a cell");
        System.out.println("Attempts self-check passed");
    }
}
