package com.claudemod.entity.life;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import com.claudemod.ClaudeMod;
import com.claudemod.entity.ClaudeEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * His far sight, by line of sight only (the user, 2026-10-30 16:58: "he should have a line of sight at a 20 chunk render distance,
 * quake pro fov"). Live 16:55 in the Nether: "do you see any fortresses?" got "just dark and netherrack", and "im looking at one"
 * got "where? i'm not seeing it from here": a fortress was in view, and his block senses stopped at 20.
 *
 * The view is Perception's own (FOV_V 110, 16:9, RENDER 320: one set of numbers for mobs and blocks). Minecraft's FOV option is the
 * VERTICAL angle (GameRenderer's projection takes it as fovy), so Quake Pro is 110 up and down and about 137 across at 16:9.
 * Reach: 320 in the Overworld and the End (the terrain fog ends at the render distance); in the Nether vanilla's fog ends at
 * min(render distance, 192) / 2 = 96 blocks (FogRenderer: Nether effects are foggy), so that is all a player sees there at 20 chunks.
 * Only loaded chunks can be cast into (he is a player in the player list, so what's loaded round him is what a client is sent); a ray
 * that runs into an unloaded chunk stops there and is counted, never guessed past.
 *
 * What stops a ray: its first solid hit (a full block, or a part block whose shape the ray crosses). Air, water (see-through, as in
 * Perception.clear), glass, fences and grass go on; two layers of leaves are seen through, the third stops it (a forest is a wall);
 * lava stops it (an opaque surface: nothing under a lava lake is seen). Never a block behind another block.
 *
 * A scan, every 3 s: a coarse fan (24 x 14, angle-even over the frustum, shifted each scan along a Halton sequence so four scans
 * fill a grid four times finer), then a 5 x 5 fan round each landmark hit (up to 4) for its extent. A careful look (asked "do you
 * see any?", or pointed to) is one dense fan (64 x 36). Each hit of a structure's own block goes on his sightings (Perception.Sighting:
 * the cells he saw, never the server's structure data).
 */
public final class FarSight {
    private FarSight() {}

    public static final int SCAN_EVERY = 60; // 3 s
    static final int FAN_W = 24, FAN_H = 14, DENSE_W = 64, DENSE_H = 36, REFINE = 5, REFINE_HITS = 4;
    static final double HALF_YAW = Math.atan(Perception.TAN_H), HALF_PITCH = Math.atan(Perception.TAN_V);
    static final int MERGE = 24; // a cell of the same kind within this of a sighting is more of it
    public static final long BUDGET_NS = 2_000_000; // a scan over 2 ms is logged

    /** How far he sees here: 320 (20 chunks), or the Nether's fog at 96. */
    public static double reach(ServerLevel level) { return level.dimension() == Level.NETHER ? Math.min(Perception.RENDER, 192) * 0.5 : Perception.RENDER; }

    // ---- the ray ----
    public static final int HIT = 0, OPEN = 1, UNLOADED = 2, LAVA = 3;
    /** A ray's end: the cell and block it stopped on (HIT, LAVA), or where it ran out (OPEN: past its reach or out of the world; UNLOADED: an unloaded chunk). */
    public record Ray(BlockPos cell, BlockState state, double dist, int end) {}

    static double next(int c, int s, double o, double d) { return s > 0 ? (c + 1 - o) / d : s < 0 ? (c - o) / d : Double.MAX_VALUE; }
    /**
     * The first thing he sees along dir (unit) from `from`, out to max: a cell-by-cell walk (Amanatides-Woo) on the loaded chunks'
     * sections, an all-air 16-cube crossed in one jump (the sky, the open Nether: a ray costs its sections there, not its cells).
     */
    public static Ray cast(ServerLevel level, Vec3 from, Vec3 dir, double max) { return cast(level, from, dir, max, null); }
    /** The same, with a scan's own chunk cache (its rays cross the same chunks). */
    static Ray cast(ServerLevel level, Vec3 from, Vec3 dir, double max, Map<Long, LevelChunk> cache) {
        double dx = dir.x, dy = dir.y, dz = dir.z;
        int x = Mth.floor(from.x), y = Mth.floor(from.y), z = Mth.floor(from.z);
        int sx = dx > 0 ? 1 : dx < 0 ? -1 : 0, sy = dy > 0 ? 1 : dy < 0 ? -1 : 0, sz = dz > 0 ? 1 : dz < 0 ? -1 : 0;
        double tdx = sx == 0 ? Double.MAX_VALUE : Math.abs(1 / dx), tdy = sy == 0 ? Double.MAX_VALUE : Math.abs(1 / dy), tdz = sz == 0 ? Double.MAX_VALUE : Math.abs(1 / dz);
        double tmx = next(x, sx, from.x, dx), tmy = next(y, sy, from.y, dy), tmz = next(z, sz, from.z, dz);
        int minY = level.getMinBuildHeight(), maxY = level.getMaxBuildHeight();
        int ccx = Integer.MIN_VALUE, ccz = Integer.MIN_VALUE; LevelChunk ch = null; LevelChunkSection[] secs = null;
        Vec3 end = from.add(dir.scale(max)); int leaves = 0; double t = 0; boolean step = true; // (the eye's own cell: stepped out of first)
        for (int guard = (int) (max * 3) + 8; guard > 0; guard--) {
            if (step) { if (tmx < tmy && tmx < tmz) { x += sx; t = tmx; tmx += tdx; } else if (tmy < tmz) { y += sy; t = tmy; tmy += tdy; } else { z += sz; t = tmz; tmz += tdz; } }
            step = true;
            if (t > max) break;
            if (y < minY || y >= maxY) { if (y < minY && sy <= 0 || y >= maxY && sy >= 0) break; continue; } // (out of the world, heading further out)
            int cx = x >> 4, cz = z >> 4;
            if (cx != ccx || cz != ccz) { ccx = cx; ccz = cz; ch = cache == null ? level.getChunkSource().getChunkNow(cx, cz) : cache.computeIfAbsent(net.minecraft.world.level.ChunkPos.asLong(cx, cz), k -> level.getChunkSource().getChunkNow(net.minecraft.world.level.ChunkPos.getX(k), net.minecraft.world.level.ChunkPos.getZ(k))); if (ch == null) return new Ray(new BlockPos(x, y, z), null, t, UNLOADED); secs = ch.getSections(); }
            int si = ch.getSectionIndex(y); if (si < 0 || si >= secs.length) continue;
            LevelChunkSection sec = secs[si];
            if (sec.hasOnlyAir()) { // out of this 16-cube in one go: the cell just past where the ray leaves it
                int bx = x & ~15, by = y & ~15, bz = z & ~15;
                double ex = sx > 0 ? (bx + 16 - from.x) / dx : sx < 0 ? (bx - from.x) / dx : Double.MAX_VALUE;
                double ey = sy > 0 ? (by + 16 - from.y) / dy : sy < 0 ? (by - from.y) / dy : Double.MAX_VALUE;
                double ez = sz > 0 ? (bz + 16 - from.z) / dz : sz < 0 ? (bz - from.z) / dz : Double.MAX_VALUE;
                double te = Math.min(ex, Math.min(ey, ez)); if (te > max) break;
                Vec3 q = from.add(dir.scale(te));
                x = te == ex ? (sx > 0 ? bx + 16 : bx - 1) : Mth.clamp(Mth.floor(q.x), bx, bx + 15);
                y = te == ey && te != ex ? (sy > 0 ? by + 16 : by - 1) : Mth.clamp(Mth.floor(q.y), by, by + 15);
                z = te == ez && te != ex && te != ey ? (sz > 0 ? bz + 16 : bz - 1) : Mth.clamp(Mth.floor(q.z), bz, bz + 15);
                t = te; tmx = next(x, sx, from.x, dx); tmy = next(y, sy, from.y, dy); tmz = next(z, sz, from.z, dz); step = false;
                continue;
            }
            BlockState s = sec.getBlockState(x & 15, y & 15, z & 15);
            if (s.isAir()) continue;
            if (s.getFluidState().is(FluidTags.LAVA)) return new Ray(new BlockPos(x, y, z), s, t, LAVA);
            if (s.getBlock() instanceof LiquidBlock) continue; // (water: see-through)
            boolean mark = isSign(s);
            if (!mark && !s.canOcclude()) { if (s.is(BlockTags.LEAVES) && ++leaves >= 3) return new Ray(new BlockPos(x, y, z), s, t, HIT); continue; } // (glass, fences, grass: seen past)
            BlockPos p = new BlockPos(x, y, z);
            VoxelShape sh = s.getShape(level, p);
            if (sh.isEmpty()) continue;
            if (sh != Shapes.block() && sh.clip(from, end, p) == null) continue; // (a part block the ray goes by)
            return new Ray(p, s, t, HIT);
        }
        return new Ray(null, null, max, OPEN);
    }

    /** His own view of that cell now: a ray from his eye (any way he could turn his head) whose first hit is that cell, to its middle or an open face; null when something stands between, or it's past his reach. */
    public static Ray seeCell(ClaudeEntity g, ServerLevel level, BlockPos cell) {
        Vec3 eye = g.getEyePosition(), c = Vec3.atCenterOf(cell); double reach = reach(level);
        List<Vec3> pts = new ArrayList<>(); pts.add(c);
        for (Direction d : Direction.values()) { BlockPos n = cell.relative(d); if (!level.getBlockState(n).canOcclude()) pts.add(c.add(d.getStepX() * 0.45, d.getStepY() * 0.45, d.getStepZ() * 0.45)); }
        for (Vec3 pt : pts) {
            Vec3 v = pt.subtract(eye); double len = v.length(); if (len > reach || len < 1e-3) continue;
            Ray r = cast(level, eye, v.scale(1 / len), Math.min(reach, len + 1.0));
            if ((r.end() == HIT || r.end() == LAVA) && cell.equals(r.cell())) return r;
        }
        return null;
    }

    // ---- what a block tells a player ----
    /** A block that says what it's part of: the structure key it's a sign of (strong: it starts a sighting; sure: no doubt what it is), and the kinds a weak one only adds to. */
    record Sign(String id, String label, boolean strong, boolean sure, String[] extend, boolean house, int ctx) {}
    private static final Sign NONE = new Sign(null, null, false, false, new String[0], false, 0);
    private static final Map<Block, Sign> SIGNS = new IdentityHashMap<>();
    static Sign strong(String id, String label, boolean sure) { return new Sign(id, label, true, sure, new String[0], false, 0); }
    static Sign weak(String label, boolean house, String... extend) { return new Sign(null, label, false, false, extend, house, 0); }
    static Sign base(BlockState s) {
        Block bl = s.getBlock(); Sign got = SIGNS.get(bl);
        if (got == null) { got = classify(bl); SIGNS.put(bl, got); }
        return got;
    }
    static boolean isSign(BlockState s) { return base(s) != NONE; }
    static Sign classify(Block bl) {
        String b = BuiltInRegistries.BLOCK.getKey(bl).getPath(); BlockState s = bl.defaultBlockState(); String nice = b.replace('_', ' ');
        if (b.startsWith("nether_brick") || b.equals("cracked_nether_bricks") || b.equals("chiseled_nether_bricks")) return strong("fortress", "nether brick", true);
        if (bl == Blocks.NETHER_WART || bl == Blocks.SOUL_SAND) return weak(nice, false, "fortress");
        if (b.contains("polished_blackstone") || b.contains("blackstone_brick") || b.equals("gilded_blackstone") || b.startsWith("blackstone_")) return strong("bastion_remnant", nice, true);
        if (bl == Blocks.BLACKSTONE || bl == Blocks.GOLD_BLOCK) return weak(nice, false, "bastion_remnant", "ruined_portal");
        if (s.is(BlockTags.RAILS)) return strong("mineshaft", "rails", true);
        if (bl == Blocks.COBWEB) return strong("mineshaft", "cobwebs", false);
        if (b.contains("tuff_brick") || b.startsWith("polished_tuff") || b.equals("chiseled_tuff") || bl == Blocks.TRIAL_SPAWNER || bl == Blocks.VAULT
                || b.contains("copper") && !b.contains("ore") && !b.startsWith("raw") && !b.contains("lightning")) return strong("trial_chambers", nice, true);
        if (bl == Blocks.REINFORCED_DEEPSLATE || b.startsWith("deepslate_brick") || b.startsWith("deepslate_tile") || b.startsWith("cracked_deepslate") || b.equals("chiseled_deepslate")) return strong("ancient_city", nice, true);
        if (bl == Blocks.SOUL_LANTERN) return new Sign("ancient_city", nice, true, false, new String[0], false, 3);
        if (b.contains("prismarine") || bl == Blocks.SEA_LANTERN || bl == Blocks.WET_SPONGE) return strong("monument", nice, true);
        if (b.startsWith("purpur") || b.startsWith("end_stone_brick") || bl == Blocks.END_ROD) return strong("end_city", nice, true);
        if (bl == Blocks.CHISELED_SANDSTONE || bl == Blocks.BLUE_TERRACOTTA) return strong("desert_pyramid", nice, false);
        if (b.startsWith("mossy_cobblestone")) return strong("jungle_pyramid", "mossy cobblestone", false);
        if (bl == Blocks.CRYING_OBSIDIAN) return strong("ruined_portal", "crying obsidian", true);
        if (bl == Blocks.OBSIDIAN) return strong("obsidian", "obsidian", false);
        if (bl == Blocks.NETHER_PORTAL) return strong("nether_portal", "a lit nether portal", true);
        if (bl == Blocks.BONE_BLOCK) return new Sign("fossil", "bone blocks", true, true, new String[0], false, 2);
        if (bl == Blocks.END_PORTAL_FRAME || b.startsWith("infested")) return strong("stronghold", nice, true);
        if (b.contains("stone_brick") && !b.contains("deepslate") && !b.contains("end_stone") && !b.contains("mud")) return new Sign("stronghold", nice, false, false, new String[] {"stronghold", "ocean_ruin", "trail_ruins"}, false, 1);
        if (bl == Blocks.SUSPICIOUS_GRAVEL) return strong("trail_ruins", "suspicious gravel", false);
        if (bl == Blocks.BELL) return strong("village", "a bell", true);
        if (bl == Blocks.HAY_BLOCK || bl == Blocks.DIRT_PATH || bl == Blocks.COMPOSTER || bl == Blocks.LECTERN || bl == Blocks.SMOKER || bl == Blocks.BLAST_FURNACE || bl == Blocks.BARREL
                || bl == Blocks.CARTOGRAPHY_TABLE || bl == Blocks.FLETCHING_TABLE || bl == Blocks.GRINDSTONE || bl == Blocks.SMITHING_TABLE || bl == Blocks.STONECUTTER || bl == Blocks.LOOM) return strong("village", nice, false);
        if (s.is(BlockTags.PLANKS)) return weak(nice, true, "village", "mineshaft");
        if (s.is(BlockTags.WOODEN_DOORS) || s.is(BlockTags.BEDS) || s.is(BlockTags.WOODEN_STAIRS) || s.is(BlockTags.WOODEN_SLABS) || bl == Blocks.GLASS_PANE || s.is(BlockTags.WOOL) || s.is(BlockTags.WOOL_CARPETS) || bl == Blocks.LANTERN) return weak(nice, true, "village");
        if (s.is(BlockTags.WOODEN_FENCES)) return weak(nice, false, "mineshaft", "village");
        if (s.is(BlockTags.CROPS) || bl == Blocks.FARMLAND || bl == Blocks.TORCH || bl == Blocks.WALL_TORCH || s.is(BlockTags.TERRACOTTA) || bl == Blocks.CUT_SANDSTONE || bl == Blocks.SMOOTH_SANDSTONE) return weak(nice, false, "village", "desert_pyramid");
        if (bl == Blocks.COBBLESTONE) return weak(nice, false, "village", "jungle_pyramid");
        if (bl == Blocks.TRIPWIRE || bl == Blocks.LEVER || bl == Blocks.DISPENSER) return weak(nice, false, "jungle_pyramid");
        if (bl == Blocks.SPAWNER) return weak("a spawner", false, "fortress", "mineshaft", "stronghold");
        if (bl == Blocks.IRON_BARS) return weak(nice, false, "stronghold");
        return NONE;
    }
    /** The sign at that cell, with what the place says (stone bricks deep down: a stronghold; bone blocks: the Nether's fossils or the Overworld's). */
    static Sign sign(ServerLevel level, BlockPos p, BlockState st) {
        Sign s = base(st); if (s == NONE) return null;
        boolean nether = level.dimension() == Level.NETHER, over = level.dimension() == Level.OVERWORLD;
        if (s.ctx == 1) return over && p.getY() < 30 ? strong("stronghold", s.label, false) : weak(s.label, false, s.extend);
        if (s.ctx == 2) return strong(nether ? "nether_fossil" : "fossil", s.label, true);
        if (s.ctx == 3) return nether ? null : strong("ancient_city", s.label, false);
        return s;
    }
    /** What he'd call it: "a fortress". */
    public static String kindName(String id) {
        if (id == null) return "something";
        return switch (id) {
            case "fortress" -> "a fortress"; case "bastion_remnant" -> "a bastion"; case "mineshaft" -> "a mineshaft"; case "trial_chambers" -> "a trial chamber";
            case "ancient_city" -> "an ancient city"; case "monument" -> "an ocean monument"; case "end_city" -> "an end city"; case "desert_pyramid" -> "a desert temple";
            case "jungle_pyramid" -> "a jungle temple"; case "ruined_portal" -> "a ruined portal"; case "obsidian" -> "a portal frame, or lava that met water";
            case "nether_portal" -> "a nether portal"; case "nether_fossil" -> "a nether fossil"; case "fossil" -> "a fossil"; case "stronghold" -> "a stronghold";
            case "trail_ruins" -> "trail ruins"; case "village" -> "a village"; default -> id.replace('_', ' ');
        };
    }
    /** The kind a word names ("fortress", "bastions", "the temple"), "*" for the one they pointed at ("landmark", "structure"), or null. */
    public static String kindWord(String text) {
        if (text == null) return null; String t = text.toLowerCase(Locale.ROOT);
        if (t.matches(".*\\bfortress(es)?\\b.*") || t.matches(".*\\bnether bricks?\\b.*")) return "fortress";
        if (t.matches(".*\\bbastions?\\b.*")) return "bastion_remnant";
        if (t.matches(".*\\bvillages?\\b.*")) return "village";
        if (t.matches(".*\\btemples?\\b.*") || t.matches(".*\\bpyramids?\\b.*")) return "temple";
        if (t.matches(".*\\bmonuments?\\b.*")) return "monument";
        if (t.matches(".*\\bmineshafts?\\b.*")) return "mineshaft";
        if (t.matches(".*\\bstrongholds?\\b.*")) return "stronghold";
        if (t.matches(".*\\btrial chambers?\\b.*")) return "trial_chambers";
        if (t.matches(".*\\bancient city\\b.*")) return "ancient_city";
        if (t.matches(".*\\bend city\\b.*")) return "end_city";
        if (t.matches(".*\\bportals?\\b.*")) return "portal";
        if (t.matches(".*\\b(landmark|structure|the one (they|you) pointed( at)?|where they (were )?look(ed|ing))\\b.*")) return "*";
        return null;
    }
    /** A sighting's id answers to that kind word ("temple": either temple; "portal": a lit portal, a ruined one, obsidian; "*": any). */
    public static boolean kindMatches(String kind, String id) {
        if (kind == null || kind.equals("*")) return true;
        return switch (kind) {
            case "temple" -> id.equals("desert_pyramid") || id.equals("jungle_pyramid");
            case "portal" -> id.equals("nether_portal") || id.equals("ruined_portal") || id.equals("obsidian");
            default -> id.equals(kind) || id.startsWith(kind);
        };
    }

    // ---- what he has seen, as he saw it ----
    private static Map<UUID, java.util.Set<Long>> passed; // weak signs that joined nothing, and his own builds: not looked at again till the next minute (static: fields added live start null)
    private static Map<UUID, ArrayList<long[]>> houseCells; // building blocks seen out in the world (planks, doors, beds, panes), not yet a building: cell, tick
    private static Map<UUID, Integer> made; // (tests) new sightings logged
    /** Structure blocks close by he looked for and couldn't see from this spot and facing: not again till he turns, moves or 5 s pass (the 64 checks a second go on to the next ones). */
    static final class Misses { final java.util.Set<Long> cells = new java.util.HashSet<>(); Vec3 eye = Vec3.ZERO, look = Vec3.ZERO; int tick; }
    private static Map<UUID, Misses> misses;
    static java.util.Set<Long> misses(ClaudeEntity g) {
        if (misses == null) misses = new HashMap<>();
        Misses m = misses.computeIfAbsent(g.getUUID(), k -> new Misses()); Vec3 eye = g.getEyePosition(), look = g.getViewVector(1f);
        if (m.eye.distanceToSqr(eye) > 1 || m.look.dot(look) < 0.94 || g.tickCount - m.tick > 100 || m.tick > g.tickCount || m.cells.size() > 8192) { m.cells.clear(); m.eye = eye; m.look = look; m.tick = g.tickCount; } // (moved a block, turned 20 degrees, 5 s on: looked at afresh)
        return m.cells;
    }
    static java.util.Set<Long> passed(ClaudeEntity g) { if (passed == null) passed = new HashMap<>(); return passed.computeIfAbsent(g.getUUID(), k -> new java.util.HashSet<>()); }
    public static int newSightings(ClaudeEntity g) { return made == null ? 0 : made.getOrDefault(g.getUUID(), 0); }
    /** His own builds: his house, farm, mine, stations and chests are his, not a village's. */
    static boolean own(ClaudeEntity g, BlockPos p) {
        return g.house != null && g.house.touches(p) || g.farm != null && g.farm.touches(p) || g.myStations.contains(p) || g.storageChests.contains(p) || com.claudemod.director.ClaudeLife.ownMine(g, p);
    }
    static boolean known(ClaudeEntity g, BlockPos p) {
        if (passed(g).contains(p.asLong())) return true;
        for (Perception.Sighting s : Perception.sightList(g)) if (s.cells.contains(p)) return true;
        return false;
    }
    /**
     * A block of a structure he saw at p: onto the sighting it belongs to (the same kind within 24), else a new sighting if it's a
     * strong sign, logged once. A weak one only adds to a sighting of a kind it's part of; building blocks out in the world
     * (planks, doors, beds, panes) make a building once 3 are seen within 8 of each other. Returns the sighting, or null.
     */
    static Perception.Sighting addSeen(ClaudeEntity g, ServerLevel level, BlockPos p, BlockState st, double dist, String how) {
        Sign sg = sign(level, p, st); if (sg == null) return null;
        p = p.immutable();
        if (own(g, p)) { passed(g).add(p.asLong()); return null; }
        List<Perception.Sighting> list = Perception.sightList(g); var dim = level.dimension(); int now = g.tickCount;
        Perception.Sighting best = null; double bd = Double.MAX_VALUE;
        for (Perception.Sighting s : list) {
            if (s.dim() != dim) continue;
            boolean fits = sg.strong ? s.id().equals(sg.id) : java.util.Arrays.asList(sg.extend).contains(s.id());
            if (!fits) continue;
            double d = com.claudemod.director.Structures.distSqrTo(s.seen(), p);
            if (d <= MERGE * MERGE && d < bd) { bd = d; best = s; }
        }
        if (best != null) { best.add(p, now); if (sg.strong && sg.sure && !best.sure) { best.sure = true; best.block = sg.label; } return best; }
        String id = sg.id, label = sg.label; boolean sure = sg.sure; List<BlockPos> with = new ArrayList<>();
        if (!sg.strong) {
            if (!sg.house) { passed(g).add(p.asLong()); return null; }
            if (houseCells == null) houseCells = new HashMap<>();
            var hc = houseCells.computeIfAbsent(g.getUUID(), k -> new ArrayList<>());
            hc.removeIf(e -> now - e[1] > 20 * 60 || e[1] > now);
            boolean had = false; for (long[] e : hc) if (e[0] == p.asLong()) had = true;
            if (!had) hc.add(new long[] {p.asLong(), now});
            for (long[] e : hc) { BlockPos q = BlockPos.of(e[0]); if (Math.max(Math.abs(q.getX() - p.getX()), Math.max(Math.abs(q.getY() - p.getY()), Math.abs(q.getZ() - p.getZ()))) <= 8) with.add(q); }
            if (with.size() < 3) return null;
            for (BlockPos q : with) hc.removeIf(e -> e[0] == q.asLong());
            id = "village"; label = sg.label + " (a building)"; sure = false;
        }
        Perception.Sighting s = new Perception.Sighting(id, dim, p, now, label, sure);
        for (BlockPos q : with) s.add(q, now);
        list.add(s); if (list.size() > 512) list.remove(0);
        if (made == null) made = new HashMap<>(); made.merge(g.getUUID(), 1, Integer::sum);
        ClaudeMod.LOGGER.info("[Claude sense] saw {} at {} from {}, {} blocks off ({}): {} on record, as much of it as he saw (FarSight.addSeen)", label, p.toShortString(), g.blockPosition().toShortString(), (int) dist, how, kindName(id));
        return s;
    }

    // ---- the scans ----
    /** His last look: when, how many rays, what the rays met (for "what do you see"), its cost. */
    static final class View { int scans, tick = -100000, rays; long lastNs, maxNs, denseNs; String facing = "", summary = "nothing yet"; double reach; int unloaded; double unloadedAt; }
    private static Map<UUID, View> views;
    static View view(ClaudeEntity g) { if (views == null) views = new HashMap<>(); return views.computeIfAbsent(g.getUUID(), k -> new View()); }
    public static long lastScanNs(ClaudeEntity g) { return view(g).lastNs; }
    public static long maxScanNs(ClaudeEntity g) { return view(g).maxNs; }
    public static int lastRays(ClaudeEntity g) { return view(g).rays; }
    public static void reset(ClaudeEntity g) { UUID id = g.getUUID(); if (views != null) views.remove(id); if (passed != null) passed.remove(id); if (houseCells != null) houseCells.remove(id); if (made != null) made.remove(id); if (looks != null) looks.remove(id); if (asked != null) asked.remove(id); if (misses != null) misses.remove(id); }

    static double halton(int i, int base) { double f = 1, r = 0; i += 1; while (i > 0) { f /= base; r += f * (i % base); i /= base; } return r; }
    static Vec3 dirOf(Vec3 f, Vec3 r, Vec3 u, double a, double b) { return f.scale(Math.cos(a)).add(r.scale(Math.sin(a))).scale(Math.cos(b)).add(u.scale(Math.sin(b))).normalize(); }

    /** A look over his screen: the coarse fan (each 3 s) or the dense one (a careful look). Returns the rays cast. */
    public static int scan(ClaudeEntity g, ServerLevel level, boolean dense) {
        long t0 = System.nanoTime();
        View v = view(g);
        if (passed != null && g.tickCount % 1200 < SCAN_EVERY) passed(g).clear(); // (a minute on: the weak ones looked at again)
        Vec3 eye = g.getEyePosition(), f = g.getViewVector(1f);
        Vec3 r = f.cross(new Vec3(0, 1, 0)); r = r.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : r.normalize(); Vec3 u = r.cross(f).normalize();
        double reach = reach(level);
        int w = dense ? DENSE_W : FAN_W, hh = dense ? DENSE_H : FAN_H;
        double ox = dense ? 0.5 : halton(v.scans, 2), oy = dense ? 0.5 : halton(v.scans, 3); v.scans++;
        double sa = 2 * HALF_YAW / w, sb = 2 * HALF_PITCH / hh;
        Map<String, Integer> tally = new HashMap<>(); int rays = 0, unloaded = 0; double unloadedAt = Double.MAX_VALUE;
        List<double[]> marks = new ArrayList<>(); List<BlockPos> markCells = new ArrayList<>(); Map<Long, LevelChunk> cache = new HashMap<>();
        for (int i = 0; i < w; i++) for (int j = 0; j < hh; j++) {
            double a = -HALF_YAW + (i + ox) * sa, b = -HALF_PITCH + (j + oy) * sb;
            Vec3 d = dirOf(f, r, u, a, b);
            if (!Perception.inView(eye, f, r, u, eye.add(d))) continue; // (the corners of the angle grid fall off the screen)
            rays++;
            Ray ry = cast(level, eye, d, reach, cache);
            String what = ry.end() == HIT ? BuiltInRegistries.BLOCK.getKey(ry.state().getBlock()).getPath().replace('_', ' ') : ry.end() == LAVA ? "lava" : ry.end() == UNLOADED ? "unloaded" : "open";
            tally.merge(what, 1, Integer::sum);
            if (ry.end() == UNLOADED) { unloaded++; unloadedAt = Math.min(unloadedAt, ry.dist()); }
            if (ry.end() == HIT && isSign(ry.state())) {
                addSeen(g, level, ry.cell(), ry.state(), ry.dist(), dense ? "a careful look" : "far sight");
                boolean apart = true; for (BlockPos q : markCells) if (q.distSqr(ry.cell()) < 64) apart = false;
                if (!dense && apart && marks.size() < REFINE_HITS) { marks.add(new double[] {a, b}); markCells.add(ry.cell()); }
            }
        }
        for (double[] mk : marks) for (int p = 0; p < REFINE; p++) for (int q = 0; q < REFINE; q++) { // round each hit, a fan as fine as the dense one: the rest of it
            double a = mk[0] + ((double) p / (REFINE - 1) - 0.5) * sa, b = mk[1] + ((double) q / (REFINE - 1) - 0.5) * sb;
            Ray ry = cast(level, eye, dirOf(f, r, u, a, b), reach, cache); rays++;
            if (ry.end() == HIT && isSign(ry.state())) addSeen(g, level, ry.cell(), ry.state(), ry.dist(), "a closer look");
        }
        long ns = System.nanoTime() - t0;
        v.tick = g.tickCount; v.rays = rays; v.reach = reach; v.unloaded = unloaded; v.unloadedAt = unloadedAt; v.facing = words(new Vec3(f.x, 0, f.z));
        if (dense) v.denseNs = ns; else { v.lastNs = ns; v.maxNs = Math.max(v.maxNs, ns); }
        v.summary = summary(tally, rays, reach, level.dimension() == Level.NETHER, unloaded, unloadedAt);
        if (ns > BUDGET_NS) ClaudeMod.LOGGER.info("[Claude sense] far sight: {} {} rays took {} ms, over the 2 ms budget (FarSight.scan)", dense ? "a careful look," : "a scan,", rays, String.format(Locale.ROOT, "%.2f", ns / 1e6));
        return rays;
    }
    static String summary(Map<String, Integer> tally, int rays, double reach, boolean nether, int unloaded, double unloadedAt) {
        if (rays == 0) return "nothing";
        List<Map.Entry<String, Integer>> es = new ArrayList<>(tally.entrySet()); es.sort((x, y) -> y.getValue() - x.getValue());
        List<String> out = new ArrayList<>();
        for (var e : es) {
            if (out.size() >= 4) break;
            int pc = Math.round(100f * e.getValue() / rays); if (pc < 5) continue;
            String k = e.getKey();
            String nm = k.equals("open") ? (nether ? "fog (nothing within " + (int) reach + ")" : "open sky or nothing within " + (int) reach) : k.equals("unloaded") ? "the edge of the loaded world" : k;
            out.add(nm + " " + pc + "%");
        }
        return String.join(", ", out) + (unloaded > 0 ? "; the world isn't loaded past about " + (int) unloadedAt + " blocks somewhere in view (you can't see past that)" : "");
    }

    /** Each second: the structure blocks within 16 (8 up and down) in his view, cell by cell (sections with none skipped). */
    public static void nearScan(ClaudeEntity g, ServerLevel level) {
        BlockPos c = g.blockPosition(); int checks = 0; Vec3 eye = g.getEyePosition();
        java.util.Set<Long> miss = misses(g);
        int x0 = c.getX() - 16, x1 = c.getX() + 16, z0 = c.getZ() - 16, z1 = c.getZ() + 16;
        int y0 = Math.max(level.getMinBuildHeight(), c.getY() - 8), y1 = Math.min(level.getMaxBuildHeight() - 1, c.getY() + 8);
        BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
        for (int cx = x0 >> 4; cx <= x1 >> 4; cx++) for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) {
            LevelChunk ch = level.getChunkSource().getChunkNow(cx, cz); if (ch == null) continue;
            LevelChunkSection[] secs = ch.getSections();
            for (int sy = y0 >> 4; sy <= y1 >> 4; sy++) {
                int si = ch.getSectionIndexFromSectionY(sy); if (si < 0 || si >= secs.length) continue;
                LevelChunkSection sec = secs[si];
                if (sec.hasOnlyAir() || !sec.maybeHas(FarSight::isSign)) continue;
                for (int x = Math.max(x0, cx << 4); x <= Math.min(x1, (cx << 4) + 15); x++) for (int y = Math.max(y0, sy << 4); y <= Math.min(y1, (sy << 4) + 15); y++) for (int z = Math.max(z0, cz << 4); z <= Math.min(z1, (cz << 4) + 15); z++) {
                    BlockState st = sec.getBlockState(x & 15, y & 15, z & 15);
                    if (!isSign(st)) continue;
                    q.set(x, y, z); if (known(g, q) || miss.contains(q.asLong())) continue;
                    if (++checks > 64) return;
                    if (!Perception.seesBlock(g, level, q)) { miss.add(q.asLong()); continue; }
                    addSeen(g, level, q.immutable(), st, eye.distanceTo(Vec3.atCenterOf(q)), "close by");
                }
            }
        }
    }

    // ---- what he says of it ----
    static String words(Vec3 d) {
        String[] n = {"south", "south-west", "west", "north-west", "north", "north-east", "east", "south-east"};
        return n[(int) Math.floorMod(Math.round(Math.toDegrees(Math.atan2(-d.x, d.z)) / 45.0), 8)];
    }
    static String vert(double dy) { return dy > 12 ? ", up high" : dy > 4 ? ", a bit up" : dy < -12 ? ", well below" : dy < -4 ? ", a bit below" : ""; }
    static String about(double d) { return d < 20 ? String.valueOf((int) Math.round(d)) : String.valueOf(Math.round(d / 5) * 5); }
    /** "nether brick about 70 blocks east, a bit up" from his eye. */
    static String where(ClaudeEntity g, BlockPos cell) {
        Vec3 eye = g.getEyePosition(), c = Vec3.atCenterOf(cell); Vec3 v = c.subtract(eye);
        return "about " + about(v.length()) + " blocks " + words(new Vec3(v.x, 0, v.z)) + vert(v.y);
    }
    /** For truthFor: the structures and landmarks he has seen in this dimension, nearest first. */
    public static String seenLine(ClaudeEntity g) {
        List<Perception.Sighting> l = new ArrayList<>(Perception.landmarks(g)); if (l.isEmpty()) return "none yet";
        Vec3 eye = g.getEyePosition();
        l.sort(java.util.Comparator.comparingDouble(s -> Vec3.atCenterOf(s.nearestCell(eye)).distanceTo(eye)));
        List<String> out = new ArrayList<>();
        for (Perception.Sighting s : l) {
            if (out.size() >= 6) break;
            BlockPos n = s.nearestCell(eye); int ago = Math.max(0, (g.tickCount - s.tick()) / 20);
            out.add(s.block() + " (" + (s.sure() ? "" : "maybe ") + kindName(s.id()) + ") " + where(g, n) + " (seen " + (ago < 120 ? ago + " s" : ago / 60 + " min") + " ago; nearest block of it you saw " + n.getX() + " " + n.getY() + " " + n.getZ() + ")");
        }
        return String.join("; ", out) + ". To go to one: go_to with its kind (\"fortress\") or those coordinates";
    }
    /** For truthFor: what his eyes met on his last look. */
    public static String viewLine(ClaudeEntity g) {
        View v = view(g); if (v.tick < -1000 || v.tick > g.tickCount) return "not looked yet";
        return "facing " + v.facing + ", " + Math.max(0, (g.tickCount - v.tick) / 20) + " s ago: " + v.summary;
    }

    // ---- "im looking at one" ----
    /** What their look came to: when, his line, the cell their look met, whether he saw it himself, its name (only when he saw it), what's in his way. */
    public record Look(int tick, String line, BlockPos cell, boolean seen, String label, String kind, String theirDir, String blocker) {}
    private static Map<UUID, Look> looks; private static Map<UUID, Integer> asked;
    public static String lastLine = "";
    public static Look look(ClaudeEntity g) { return looks == null ? null : looks.get(g.getUUID()); }
    private static final Pattern POINT = Pattern.compile("\\b(i'?m|im|i am) (looking|lookin|staring|pointing) (at|right at|toward|towards|over at)\\b|\\b(where|what) (i'?m|im|i am) (looking|lookin|pointing)( at)?\\b|\\blook (where|at what) (i'?m|im|i am)\\b");
    private static final Pattern THAT = Pattern.compile("\\b(that one|over there|right there|see (that|it) over)\\b");
    private static final Pattern ASK = Pattern.compile("\\b(see|seeing|spot|notice|any)\\b.*\\b(fortress(es)?|bastions?|villages?|temples?|portals?|monuments?|structures?|strongholds?|end city|anything)\\b|\\b(do|can) you see (it|one|any|anything)\\b|\\bsee (one|it|any) (now|yet)\\b");
    private static final Pattern AGAIN = Pattern.compile("\\b(now|how about now|what about now|and now|anything now|still nothing)\\b");
    private static final Pattern SKIP = Pattern.compile("\\blava\\b|your mine|this one is yours|use this mine|the one i'?m (standing|at)");

    /** Their line about what's far off: asked what he sees (a careful look first; the chat answers), or pointing ("im looking at one"): his own sight answers. True: answered here. */
    public static boolean heard(ClaudeEntity g, ServerPlayer p, String raw) {
        if (!(g.level() instanceof ServerLevel level) || p.level() != g.level()) return false;
        String low = raw.toLowerCase(Locale.ROOT).replace('’', '\'');
        if (SKIP.matcher(low).find()) return false;
        if (asked == null) asked = new HashMap<>();
        Integer at = asked.get(g.getUUID()); boolean recent = at != null && g.tickCount - at <= 20 * 120 && at <= g.tickCount;
        boolean point = POINT.matcher(low).find() || THAT.matcher(low).find() && (recent || kindWord(low) != null || low.matches(".*\\b(see|look)\\b.*"));
        if (!point && (ASK.matcher(low).find() || recent && AGAIN.matcher(low).find())) {
            asked.put(g.getUUID(), g.tickCount);
            int n = scan(g, level, true);
            if (!Perception.precise(g)) Perception.lookRound(g, null); // (and a turn all the way round, as a player looks about when asked: the horizon in his far view for the next ask)
            ClaudeMod.LOGGER.info("[Claude sense] asked what he sees far off (\"{}\"): a careful look, {} rays in {} ms: {} (FarSight.heard)", raw, n, String.format(Locale.ROOT, "%.2f", view(g).denseNs / 1e6), view(g).summary);
            return false;
        }
        if (!point) return false;
        asked.put(g.getUUID(), g.tickCount);
        Look l = resolve(g, p, level);
        if (looks == null) looks = new HashMap<>(); looks.put(g.getUUID(), l);
        ClaudeMod.LOGGER.info("[Claude sense] they pointed (\"{}\"): their look met {}; he {} (FarSight.heard)", raw, l.cell() == null ? "nothing in reach" : l.cell().toShortString(), l.seen() ? "sees it: " + l.label() : "can't see it" + (l.blocker() == null ? "" : ", " + l.blocker() + " in his way"));
        if (com.claudemod.llm.ChatListener.looksLikeOrder(raw)) return false; // ("go to the one im looking at": the chat plans it, THEY'RE LOOKING AT in its facts)
        lastLine = l.line();
        com.claudemod.director.ClaudeChat.say(p, l.line(), "farsight: they pointed");
        return true;
    }

    /** His head on a spot: the whole of him turns, and holds it a moment (Perception's turn). */
    static void face(ClaudeEntity g, Vec3 at) {
        Vec3 e = g.getEyePosition(); double dx = at.x - e.x, dy = at.y - e.y, dz = at.z - e.z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz)), pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        g.setYRot(yaw); g.yBodyRot = yaw; g.setYHeadRot(yaw); g.setXRot(pitch);
        Perception.Mind m = Perception.mind(g); m.turnTo = at; m.turnTick = g.tickCount;
    }

    /** Their look, checked with his own eyes. Never names a block he can't see. */
    static Look resolve(ClaudeEntity g, ServerPlayer p, ServerLevel level) {
        int now = g.tickCount;
        if (!Whereabouts.seesNow(g, p)) { // a turn to where he knows them to be first, the way a player turns to his friend
            Vec3 at = Whereabouts.believed(g, p);
            if (at != null && at.distanceTo(g.position()) <= 64) { face(g, at.add(0, 1.4, 0)); Perception.lookNow(g); }
            if (!Whereabouts.seesNow(g, p)) return new Look(now, "can't see you from here, so i can't tell where you're looking. which way is it from you?", null, false, null, null, null, null);
        }
        Vec3 te = p.getEyePosition(), td = p.getViewVector(1f); String theirDir = words(new Vec3(td.x, 0, td.z));
        Ray their = cast(level, te, td, reach(level));
        BlockPos cell = their.end() == HIT || their.end() == LAVA ? their.cell() : null;
        if (cell != null && their.end() == HIT && isSign(their.state())) { // a structure's block: does he see it himself?
            Ray mine = seeCell(g, level, cell);
            if (mine != null) return named(g, level, cell, mine.state(), theirDir, "oh yeah, i see it: ");
        }
        // not from here as it is: he looks the way they're looking, carefully
        float yaw = p.getYHeadRot(), pitch = p.getXRot(); g.setYRot(yaw); g.yBodyRot = yaw; g.setYHeadRot(yaw); g.setXRot(pitch);
        Perception.Mind m = Perception.mind(g); m.turnTo = g.getEyePosition().add(td.scale(20)); m.turnTick = now; Perception.lookNow(g);
        scan(g, level, true);
        Perception.Sighting best = null; BlockPos bestCell = null; double bestAlong = Double.MAX_VALUE;
        for (Perception.Sighting s : Perception.landmarks(g)) for (BlockPos q : s.cells()) { // one he has seen, near their line of sight
            Vec3 v = Vec3.atCenterOf(q).subtract(te); double along = v.dot(td); if (along <= 2) continue;
            double perp = v.subtract(td.scale(along)).length();
            if (perp <= Math.max(6, along * 0.08) && along < bestAlong) { bestAlong = along; best = s; bestCell = q; }
        }
        if (best != null) return named(g, level, bestCell, level.getBlockState(bestCell), theirDir, "that one? i see ");
        if (cell != null) { // their look meets a plain block: he says it only if he sees it too
            Ray mine = seeCell(g, level, cell);
            if (mine != null) { face(g, Vec3.atCenterOf(cell)); String nm = name(mine.state()); return new Look(now, "where you're looking i just see " + nm + " " + where(g, cell) + ", nothing like a structure there from here", cell, true, nm, null, theirDir, null); }
        }
        Vec3 aim = cell != null ? Vec3.atCenterOf(cell) : te.add(td.scale(reach(level)));
        Vec3 eye = g.getEyePosition(), v = aim.subtract(eye); double len = v.length();
        Ray b = len < 1e-3 ? null : cast(level, eye, v.scale(1 / len), Math.min(len, reach(level)));
        String blocker = b != null && (b.end() == HIT || b.end() == LAVA) && (cell == null || !b.cell().equals(cell)) ? name(b.state()) + " " + where(g, b.cell()) : null;
        String line = blocker != null ? "can't see it from here, there's " + blocker + " of me in the way. is it past that, off to the " + theirDir + " of you?"
                : cell == null ? "i'm looking " + theirDir + " too and there's nothing in sight that way" + (level.dimension() == Level.NETHER ? " (just fog past " + (int) reach(level) + ")" : "") + ". how far is it?"
                : "can't make it out from here. is it off to the " + theirDir + " of you?";
        return new Look(now, line, cell, false, null, null, theirDir, blocker);
    }
    static String name(BlockState s) { Sign sg = base(s); return sg != NONE && sg.label != null && sg.strong ? sg.label : BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath().replace('_', ' '); }
    /** A block he sees where they're looking: on record, his head on it, said with where it is from him and what it's part of. */
    static Look named(ClaudeEntity g, ServerLevel level, BlockPos cell, BlockState st, String theirDir, String lead) {
        face(g, Vec3.atCenterOf(cell));
        Perception.Sighting s = addSeen(g, level, cell, st, g.getEyePosition().distanceTo(Vec3.atCenterOf(cell)), "where they pointed");
        Vec3 eye = g.getEyePosition(), f = Vec3.atCenterOf(cell).subtract(eye).normalize();
        Vec3 r = f.cross(new Vec3(0, 1, 0)); r = r.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : r.normalize(); Vec3 u = r.cross(f).normalize();
        for (int a = -3; a <= 3; a++) for (int b = -3; b <= 3; b++) { Ray ry = cast(level, eye, dirOf(f, r, u, a * 0.01, b * 0.01), reach(level)); if (ry.end() == HIT && isSign(ry.state())) addSeen(g, level, ry.cell(), ry.state(), ry.dist(), "where they pointed"); } // (round it: how much of it he can see)
        String nm = name(st); double d = eye.distanceTo(Vec3.atCenterOf(cell));
        String kind = s != null ? (s.sure() ? "" : "maybe ") + kindName(s.id()) : null;
        String line = lead + nm + " " + where(g, cell) + " (where you're looking)" + (kind != null ? ", " + kind + " by the look of it" : "") + (d > 16 ? ". want me to head over?" : "");
        return new Look(g.tickCount, line, cell.immutable(), true, nm, s == null ? null : s.id(), theirDir, null);
    }
    /** For truthFor: what they're looking at, as far as his own eyes can tell. */
    public static String lookLine(ClaudeEntity g, ServerPlayer p) {
        if (g == null || !(g.level() instanceof ServerLevel level) || p.level() != g.level()) return "you can't tell (not in your world)";
        Look l = look(g); String last = l != null && g.tickCount - l.tick() <= 20 * 120 && l.tick() <= g.tickCount ? " When they pointed " + (g.tickCount - l.tick()) / 20 + " s ago: " + (l.seen() ? l.label() + (l.kind() != null ? " (" + kindName(l.kind()) + ")" : "") + " at " + l.cell().getX() + " " + l.cell().getY() + " " + l.cell().getZ() + ", seen yourself (go_to \"landmark\" goes there)" : "you couldn't see it" + (l.blocker() == null ? "" : " (" + l.blocker() + " in your way)")) + "." : "";
        if (!Whereabouts.seesNow(g, p)) return "you can't see them, so you can't tell." + last;
        Vec3 te = p.getEyePosition(), td = p.getViewVector(1f); String dir = words(new Vec3(td.x, 0, td.z));
        Ray their = cast(level, te, td, reach(level));
        if (their.end() == OPEN || their.end() == UNLOADED) return "they're facing " + dir + ", at nothing within your sight (open air" + (level.dimension() == Level.NETHER ? " or fog" : "") + ")." + last;
        Ray mine = seeCell(g, level, their.cell());
        if (mine == null) return "they're facing " + dir + ", at something about " + about(te.distanceTo(Vec3.atCenterOf(their.cell()))) + " blocks from them that you can't see from where you are (never name it)." + last;
        Sign sg = sign(level, their.cell(), mine.state());
        return name(mine.state()) + (sg != null && sg.strong ? " (" + (sg.sure ? "" : "maybe ") + kindName(sg.id) + ")" : "") + " " + where(g, their.cell()) + " of you, at " + their.cell().getX() + " " + their.cell().getY() + " " + their.cell().getZ() + "; you see it too." + last;
    }

    /** go_to with a kind ("fortress", "village", "landmark"): the nearest cell he saw of the nearest one, or null (none seen; or not a kind word). */
    public static BlockPos goTarget(ClaudeEntity g, String target) {
        String kind = kindWord(target); if (kind == null || target.matches(".*-?\\d+[ ,_]+-?\\d+[ ,_]+-?\\d+.*")) return null;
        Vec3 at = g.position();
        if (kind.equals("*")) { Look l = look(g); if (l != null && l.seen() && l.cell() != null && g.tickCount - l.tick() <= 20 * 600) { ClaudeMod.LOGGER.info("[Claude walk] go_to \"{}\": the {} they pointed to at {} (FarSight.goTarget)", target, l.label(), l.cell().toShortString()); return l.cell(); } }
        Perception.Sighting s = Perception.nearestLandmark(g, kind);
        if (s == null) return null;
        BlockPos c = s.nearestCell(at);
        ClaudeMod.LOGGER.info("[Claude walk] go_to \"{}\": {} he saw ({}), its nearest seen block {} ({} blocks) (FarSight.goTarget)", target, kindName(s.id()), s.block(), c.toShortString(), (int) Math.sqrt(c.distToCenterSqr(at)));
        return c;
    }
}
