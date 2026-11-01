package com.claudemod.entity.life;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.claudemod.ClaudeMod;
import com.claudemod.entity.ClaudeEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;

/**
 * His atlas: the biomes he has seen and where, and the ground he has covered, per dimension. A player remembers "the warped
 * forest was past the lava lake, north of the portal" and doesn't walk the same ground twice looking for something; the
 * explore skill reads both. What counts as seen is what's in view round him (within 48, at about his height), never the
 * game's map of biomes beyond it.
 */
public final class Atlas {
    private Atlas() {}

    public static final int CELL = 32;   // the coverage grid
    static final int SEEN = 48;          // how far round him a biome counts as seen
    static final int PER_BIOME = 8;      // spots kept per biome (48+ apart)

    static final class Book {
        final Map<String, Map<String, List<BlockPos>>> places = new HashMap<>(); // dim -> biome -> spots
        final Map<String, Set<Long>> covered = new HashMap<>();                  // dim -> grid cells walked
    }
    private static Map<UUID, Book> books;
    static Book book(ClaudeEntity g) { if (books == null) books = new HashMap<>(); return books.computeIfAbsent(g.getUUID(), k -> new Book()); }
    public static void reset(ClaudeEntity g) { if (books != null) books.remove(g.getUUID()); }

    static String dim(ServerLevel l) { return l.dimension().location().getPath(); }
    static long cell(int x, int z) { return ((long) Math.floorDiv(x, CELL) << 32) | (Math.floorDiv(z, CELL) & 0xffffffffL); }

    /** "warped forest", "minecraft:warped_forest", "the warped forest" -> "warped_forest". */
    public static String biomeName(String words) {
        if (words == null) return "";
        String w = words.toLowerCase(java.util.Locale.ROOT).trim().replaceFirst("^(the|a|an)\\s+", "").replace("minecraft:", "").replaceAll("[\\s-]+", "_");
        return w.replaceAll("s$", "").equals("warped_forest") ? "warped_forest" : w;
    }

    /** Every 2 s from the hazard layer: his cell is covered; the biomes in view are noted. */
    public static void tick(ClaudeEntity g) {
        if (g.tickCount % 40 != 7 || !(g.level() instanceof ServerLevel l)) return;
        FortressScout.noteDeck(g);
        Book b = book(g); String d = dim(l);
        Set<Long> cov = b.covered.computeIfAbsent(d, k -> new HashSet<>());
        if (cov.size() < 20000) cov.add(cell(g.getBlockX(), g.getBlockZ()));
        BlockPos me = g.blockPosition();
        for (int r : new int[] {0, 8, 16, 32, SEEN}) for (int k = 0; k < (r == 0 ? 1 : 8); k++) {
            double a = k * Math.PI / 4;
            BlockPos p = me.offset((int) Math.round(Math.cos(a) * r), 0, (int) Math.round(Math.sin(a) * r));
            if (!l.isLoaded(p)) continue;
            if (r > 0 && !inView(g, l, p)) continue;
            note(g, l, l.getBiome(p).unwrapKey().map(x -> x.location().getPath()).orElse(""), p);
        }
    }

    /** Open air on the line from his eyes to that spot (a wall or the rock between: not seen). */
    static boolean inView(ClaudeEntity g, ServerLevel l, BlockPos p) {
        var eye = g.getEyePosition(); var to = net.minecraft.world.phys.Vec3.atCenterOf(p.above());
        var hit = l.clip(new net.minecraft.world.level.ClipContext(eye, to, net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, g));
        return hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS || hit.getLocation().distanceTo(to) < 6;
    }

    public static void note(ClaudeEntity g, ServerLevel l, String biome, BlockPos p) {
        if (biome.isEmpty()) return;
        List<BlockPos> spots = book(g).places.computeIfAbsent(dim(l), k -> new HashMap<>()).computeIfAbsent(biome, k -> new ArrayList<>());
        for (BlockPos q : spots) if (q.distSqr(p) < SEEN * SEEN) return;
        if (spots.size() >= PER_BIOME) return;
        spots.add(p.immutable());
        ClaudeMod.LOGGER.info("[Claude atlas] {} seen at {} in {} ({} spot(s) of it on record) (Atlas.note)", biome, p.toShortString(), dim(l), spots.size());
    }

    /** The nearest spot of that biome he has seen in this world, or null. */
    public static BlockPos find(ClaudeEntity g, ServerLevel l, String biome) {
        var m = book(g).places.get(dim(l)); if (m == null) return null;
        List<BlockPos> spots = m.get(biome); if (spots == null) return null;
        BlockPos best = null; double bd = Double.MAX_VALUE;
        for (BlockPos q : spots) { double dd = q.distSqr(g.blockPosition()); if (dd < bd) { bd = dd; best = q; } }
        return best;
    }

    public static int covered(ClaudeEntity g, ServerLevel l) { Set<Long> c = book(g).covered.get(dim(l)); return c == null ? 0 : c.size(); }

    /** The heading with the least ground he's covered along it (48-160 out), sixteen ways round; ties: the one nearest {@code now}. */
    public static double freshHeading(ClaudeEntity g, ServerLevel l, double now) {
        Set<Long> cov = book(g).covered.getOrDefault(dim(l), Set.of());
        double best = now; int bc = Integer.MAX_VALUE; double bTurn = Double.MAX_VALUE;
        for (int k = 0; k < 16; k++) {
            double a = k * Math.PI / 8; int n = 0;
            for (int r = 48; r <= 160; r += 16) if (cov.contains(cell(g.getBlockX() + (int) (Math.cos(a) * r), g.getBlockZ() + (int) (Math.sin(a) * r)))) n++;
            double turn = Math.abs(Math.atan2(Math.sin(a - now), Math.cos(a - now)));
            if (n < bc || n == bc && turn < bTurn) { bc = n; best = a; bTurn = turn; }
        }
        return best;
    }

    public static CompoundTag save(ClaudeEntity g) {
        Book b = book(g); CompoundTag t = new CompoundTag(); ListTag ps = new ListTag();
        b.places.forEach((d, m) -> m.forEach((bi, spots) -> { CompoundTag c = new CompoundTag(); c.putString("dim", d); c.putString("biome", bi); c.putLongArray("at", spots.stream().mapToLong(BlockPos::asLong).toArray()); ps.add(c); }));
        t.put("places", ps);
        CompoundTag cv = new CompoundTag(); b.covered.forEach((d, s) -> cv.put(d, new LongArrayTag(s.stream().mapToLong(Long::longValue).toArray()))); t.put("covered", cv);
        return t;
    }
    public static void load(ClaudeEntity g, CompoundTag t) {
        Book b = book(g); b.places.clear(); b.covered.clear(); int n = 0;
        for (Tag x : t.getList("places", Tag.TAG_COMPOUND)) { CompoundTag c = (CompoundTag) x; List<BlockPos> spots = new ArrayList<>(); for (long l0 : c.getLongArray("at")) spots.add(BlockPos.of(l0)); b.places.computeIfAbsent(c.getString("dim"), k -> new HashMap<>()).put(c.getString("biome"), spots); n++; }
        CompoundTag cv = t.getCompound("covered"); for (String d : cv.getAllKeys()) { Set<Long> s = new HashSet<>(); for (long l0 : cv.getLongArray(d)) s.add(l0); b.covered.put(d, s); }
        ClaudeMod.LOGGER.info("[Claude atlas] {} biome record(s) from his saved state (Atlas.load)", n);
    }
}
