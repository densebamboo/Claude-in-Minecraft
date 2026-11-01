package com.claudemod.entity.life;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.claudemod.entity.ClaudeEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * What he has seen of the world, coarse (the user, 2026-11-01, via Day to Day: routes see through walls, "he tunnels toward caves and
 * around hidden lava he has never seen"; the no-sensing-through-walls rule). A 4x4x4 cube is known once one of his far-sight rays
 * passed through it or stopped in it (FarSight.cast while he scans), or he's been within 5 of it. One long per 16-cube section, a bit
 * per cube: a whole Nether trip is a few hundred entries. The route planners read a cell he doesn't know as what he'd assume it to
 * be, rock (NetherRoute), and find caves and lava as he digs, the way a player does.
 */
public final class Known {
    private Known() {}

    private static Map<UUID, Map<String, Map<Long, Long>>> books; // bot -> dimension -> section -> its 64 cubes (static: fields added live start null)
    private static Map<Long, Long> book(ClaudeEntity g, ServerLevel level) {
        if (books == null) books = new HashMap<>();
        return books.computeIfAbsent(g.getUUID(), k -> new HashMap<>()).computeIfAbsent(PortalMap.dim(level), k -> new HashMap<>());
    }
    public static void reset(ClaudeEntity g) { if (books != null) books.remove(g.getUUID()); if (lookedAt != null) lookedAt.remove(g.getUUID()); }

    static long section(int x, int y, int z) { return BlockPos.asLong(x >> 4, y >> 4, z >> 4); }
    static int bit(int x, int y, int z) { return ((x >> 2) & 3) | (((y >> 2) & 3) << 2) | (((z >> 2) & 3) << 4); }

    /** The cube of (x, y, z) is known. */
    public static boolean knows(ClaudeEntity g, ServerLevel level, int x, int y, int z) {
        Long m = book(g, level).get(section(x, y, z)); return m != null && (m >>> bit(x, y, z) & 1L) != 0;
    }
    public static boolean knows(ClaudeEntity g, ServerLevel level, BlockPos p) { return knows(g, level, p.getX(), p.getY(), p.getZ()); }

    /** A marker for one scan of his: each cell a ray steps through or stops in, and each all-air section it crosses whole. */
    public static final class Marker {
        final Map<Long, Long> b;
        Marker(Map<Long, Long> b) { this.b = b; }
        public void cell(int x, int y, int z) { b.merge(section(x, y, z), 1L << bit(x, y, z), (a, c) -> a | c); }
        public void wholeSection(int sx, int sy, int sz) { b.put(BlockPos.asLong(sx >> 4, sy >> 4, sz >> 4), -1L); } // (all air: every cube of it seen through)
    }
    public static Marker marker(ClaudeEntity g, ServerLevel level) { return new Marker(book(g, level)); }

    /** Each second: the cubes within 5 of him (what's at arm's length and underfoot he knows, seen or felt). */
    public static void near(ClaudeEntity g, ServerLevel level) {
        Marker m = marker(g, level); BlockPos c = g.blockPosition();
        for (int dx = -5; dx <= 5; dx += 2) for (int dy = -3; dy <= 4; dy += 2) for (int dz = -5; dz <= 5; dz += 2) m.cell(c.getX() + dx, c.getY() + dy, c.getZ() + dz);
    }

    /** How many 4-cubes he knows in this dimension (the log, the tests). */
    public static int count(ClaudeEntity g, ServerLevel level) { int n = 0; for (long v : book(g, level).values()) n += Long.bitCount(v); return n; }

    private static Map<UUID, Integer> lookedAt; // when he last looked all the way round for a route (server ticks)
    /** He looked all round within the last {@code ticks} (server ticks: his own restart after a portal). */
    public static boolean lookedLately(ClaudeEntity g, int ticks) { Integer t = lookedAt == null ? null : lookedAt.get(g.getUUID()); int now = g.getServer() == null ? g.tickCount : g.getServer().getTickCount(); return t != null && now - t <= ticks && now >= t; }
    public static void looked(ClaudeEntity g) { if (lookedAt == null) lookedAt = new HashMap<>(); lookedAt.put(g.getUUID(), g.getServer() == null ? g.tickCount : g.getServer().getTickCount()); }
}
