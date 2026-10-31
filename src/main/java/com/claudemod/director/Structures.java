package com.claudemod.director;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.claudemod.entity.ClaudeEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import com.claudemod.entity.life.Perception;

/**
 * What the world generated around him AS HE HAS SEEN IT (a piece counts once one of its own blocks was in his view: the server's
 * structure data only says which piece a seen block belongs to; F3 shows no structures), and what a seasoned player
 * knows about each: the good (beds, loot, trades, the end portal) and the bad (spawners, guardians, a warden, TNT under
 * the floor). The planner hears it in his situation; the mine keeps out of the dangerous ones.
 */
public final class Structures {
    private Structures() {}

    record Note(String what, boolean danger) {}

    /** The KNOWN key a structure id falls under ("village_plains" -> "village", "mineshaft_mesa" -> "mineshaft"), or null. */
    public static String key(String id) {
        for (String k : KNOWN.keySet()) if (id.startsWith(k) || id.contains(k)) return k;
        return null;
    }
    public static boolean danger(String id) { String k = key(id); return k != null && KNOWN.get(k).danger(); }
    static String what(String id) { String k = key(id); return k == null ? "a generated structure (" + id.replace('_', ' ') + ")" : KNOWN.get(k).what(); }

    /** One piece of a generated structure: the structure's id (the registry path) and the piece's box. Server data: what is THERE, not what he knows ({@link Perception#sightings}). */
    public record Piece(String id, BoundingBox box) {}
    private static List<Piece> testPieces; // gametest: the box has no generated structures, so a stage lays its own pieces (static: fields added live start null)
    public static List<Piece> testPieces() { if (testPieces == null) testPieces = new ArrayList<>(); return testPieces; }
    private static Map<String, List<Piece>> chunkPieces; // per level and chunk, read once: the structure data never changes, and a start's chunk can be a disk load away
    private static List<Piece> piecesIn(ServerLevel level, int cx, int cz) {
        if (chunkPieces == null) chunkPieces = new java.util.HashMap<>();
        String k = level.dimension().location() + " " + cx + " " + cz;
        List<Piece> got = chunkPieces.get(k);
        if (got != null) return got;
        if (chunkPieces.size() > 8192) chunkPieces.clear(); // ponytail: a cap, not an age-out
        var reg = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        BoundingBox column = new BoundingBox(cx << 4, level.getMinBuildHeight(), cz << 4, (cx << 4) + 15, level.getMaxBuildHeight(), (cz << 4) + 15);
        List<Piece> out = new ArrayList<>();
        for (StructureStart st : level.structureManager().startsForStructure(new ChunkPos(cx, cz), s -> true)) {
            var key = reg.getKey(st.getStructure());
            if (key == null) continue;
            for (StructurePiece p : st.getPieces()) { BoundingBox b = p.getBoundingBox(); if (b.intersects(column)) out.add(new Piece(key.getPath(), new BoundingBox(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()))); }
        }
        chunkPieces.put(k, out);
        return out;
    }
    /** The pieces (generated, and a test's own) with any part within r of at, each once. */
    public static List<Piece> piecesNear(ServerLevel level, BlockPos at, int r) {
        BoundingBox area = new BoundingBox(at.getX() - r, at.getY() - r, at.getZ() - r, at.getX() + r, at.getY() + r, at.getZ() + r);
        java.util.LinkedHashSet<Piece> out = new java.util.LinkedHashSet<>();
        for (Piece p : testPieces()) if (p.box().intersects(area)) out.add(p);
        for (int cx = (at.getX() - r) >> 4; cx <= (at.getX() + r) >> 4; cx++) for (int cz = (at.getZ() - r) >> 4; cz <= (at.getZ() + r) >> 4; cz++) {
            if (!level.hasChunk(cx, cz)) continue;
            for (Piece p : piecesIn(level, cx, cz)) if (p.box().intersects(area)) out.add(p);
        }
        return new ArrayList<>(out);
    }

    /** A block that tells a player what he's looking at, inside that structure's piece: a mineshaft's rails, cobwebs, fences and planks; a trial chamber's tuff bricks and copper; an ancient city's sculk. */
    public static boolean signOf(String id, BlockState s) {
        if (s.isAir() || s.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) return false;
        String k = key(id);
        if (k == null) return false;
        String b = BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
        return switch (k) {
            case "mineshaft" -> s.is(BlockTags.RAILS) || s.is(Blocks.COBWEB) || s.is(BlockTags.WOODEN_FENCES) || s.is(BlockTags.PLANKS) || s.is(Blocks.SPAWNER);
            case "stronghold" -> b.contains("stone_brick") || b.startsWith("infested") || s.is(Blocks.END_PORTAL_FRAME) || s.is(Blocks.IRON_BARS) || s.is(Blocks.IRON_DOOR) || s.is(Blocks.SPAWNER);
            case "trial_chambers" -> b.contains("tuff_brick") || b.contains("polished_tuff") || b.equals("chiseled_tuff") || b.contains("copper") || b.equals("trial_spawner") || b.equals("vault");
            case "ancient_city" -> b.contains("sculk") || b.equals("reinforced_deepslate") || b.contains("deepslate_brick") || b.contains("deepslate_tile") || b.equals("soul_lantern");
            case "fortress" -> b.startsWith("nether_brick") || s.is(Blocks.NETHER_WART) || s.is(Blocks.SPAWNER);
            case "bastion_remnant" -> b.contains("polished_blackstone") || b.contains("blackstone_brick") || b.equals("gilded_blackstone") || s.is(Blocks.GOLD_BLOCK);
            case "monument" -> b.contains("prismarine") || s.is(Blocks.SEA_LANTERN) || s.is(Blocks.WET_SPONGE);
            case "mansion" -> b.startsWith("dark_oak") || b.startsWith("birch_planks") || b.endsWith("_wool") || b.endsWith("_carpet");
            case "pillager_outpost" -> b.startsWith("dark_oak") || b.startsWith("birch") || s.is(Blocks.COBBLESTONE) || b.contains("banner");
            case "end_city" -> b.startsWith("purpur") || s.is(Blocks.END_STONE_BRICKS) || s.is(Blocks.END_ROD);
            case "desert_pyramid" -> b.contains("sandstone") || b.contains("terracotta");
            case "jungle_pyramid" -> b.contains("cobblestone") || s.is(Blocks.TRIPWIRE) || s.is(Blocks.DISPENSER) || s.is(Blocks.LEVER);
            case "swamp_hut" -> b.startsWith("spruce") || b.startsWith("oak_log") || s.is(Blocks.CAULDRON);
            case "ocean_ruin" -> b.contains("stone_brick") || b.contains("sandstone") || b.contains("prismarine");
            case "ruined_portal" -> b.contains("obsidian") || s.is(Blocks.NETHERRACK) || s.is(Blocks.MAGMA_BLOCK) || s.is(Blocks.GOLD_BLOCK);
            case "igloo" -> s.is(Blocks.SNOW_BLOCK) || b.endsWith("_carpet");
            case "shipwreck" -> b.endsWith("_planks") || b.endsWith("_log") || b.endsWith("_wood") || b.endsWith("_fence") || b.endsWith("_trapdoor") || b.endsWith("_stairs") || b.endsWith("_slab");
            case "nether_fossil" -> s.is(Blocks.BONE_BLOCK);
            case "trail_ruins" -> b.startsWith("suspicious") || b.contains("terracotta") || b.contains("mud_brick");
            case "buried_treasure" -> s.is(Blocks.CHEST);
            case "village" -> b.endsWith("_planks") || b.endsWith("_door") || b.endsWith("_bed") || b.endsWith("_stairs") || b.endsWith("_slab") || b.endsWith("_fence") || s.is(BlockTags.LOGS) || b.contains("glass_pane") || b.contains("terracotta") || b.contains("cobblestone") || b.contains("cut_sandstone") || b.contains("smooth_sandstone") || b.endsWith("_wool") || b.endsWith("_carpet") || b.endsWith("lantern") || s.is(BlockTags.CROPS)
                    || s.is(Blocks.BELL) || s.is(Blocks.HAY_BLOCK) || s.is(Blocks.DIRT_PATH) || s.is(Blocks.FARMLAND) || s.is(Blocks.COMPOSTER) || s.is(Blocks.LECTERN) || s.is(Blocks.SMOKER) || s.is(Blocks.BLAST_FURNACE) || s.is(Blocks.BARREL) || s.is(Blocks.CARTOGRAPHY_TABLE) || s.is(Blocks.FLETCHING_TABLE) || s.is(Blocks.GRINDSTONE) || s.is(Blocks.SMITHING_TABLE) || s.is(Blocks.STONECUTTER) || s.is(Blocks.LOOM) || s.is(Blocks.BREWING_STAND) || s.is(Blocks.CAULDRON) || s.is(Blocks.TORCH) || s.is(Blocks.WALL_TORCH);
            default -> false;
        };
    }

    /** A generated structure's own block, kept intact (the user, 17:08: "generally you wanna keep the nether fortress intact"): which one, or null. Never dug for a way or for blocks: its doorways and corridors are the way. */
    public static String intactKind(BlockState s) {
        if (s.is(Blocks.NETHER_BRICKS) || s.is(Blocks.CRACKED_NETHER_BRICKS) || s.is(Blocks.CHISELED_NETHER_BRICKS) || s.is(Blocks.NETHER_BRICK_FENCE) || s.is(Blocks.NETHER_BRICK_STAIRS) || s.is(Blocks.NETHER_BRICK_SLAB) || s.is(Blocks.NETHER_BRICK_WALL)) return "a nether fortress";
        if (s.is(Blocks.POLISHED_BLACKSTONE_BRICKS) || s.is(Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS) || s.is(Blocks.GILDED_BLACKSTONE) || s.is(Blocks.CHISELED_POLISHED_BLACKSTONE) || s.is(Blocks.POLISHED_BLACKSTONE_BRICK_STAIRS) || s.is(Blocks.POLISHED_BLACKSTONE_BRICK_SLAB) || s.is(Blocks.POLISHED_BLACKSTONE_BRICK_WALL)) return "a bastion";
        return null;
    }

    /** Squared distance from p to the nearest cell of the box (0 inside). */
    public static double distSqrTo(BoundingBox b, BlockPos p) { return distSqr(b, p); }
    static double distSqr(BoundingBox b, BlockPos p) {
        int dx = Math.max(0, Math.max(b.minX() - p.getX(), p.getX() - b.maxX())), dy = Math.max(0, Math.max(b.minY() - p.getY(), p.getY() - b.maxY())), dz = Math.max(0, Math.max(b.minZ() - p.getZ(), p.getZ() - b.maxZ()));
        return (double) dx * dx + (double) dy * dy + (double) dz * dz;
    }

    /** The player's-eye summary by structure id (the registry path, "village_plains" matched by prefix). */
    static final Map<String, Note> KNOWN = Map.ofEntries(
            Map.entry("village", new Note("a village: hay and crops (replanted), the smith's chest, a bed to sleep in, villagers to trade with; take what's useful, leave the village standing", false)),
            Map.entry("pillager_outpost", new Note("a pillager outpost: pillagers with crossbows, a captain (killing it gives Bad Omen, which starts a raid at a village); steer clear", true)),
            Map.entry("mineshaft", new Note("a mineshaft: minecart chests with loot, rails, cobwebs and cave spider spawners in the cobweb corridors; stay out of the cobwebs", true)),
            Map.entry("stronghold", new Note("a stronghold: the end portal room is in there (12 eyes of ender light it), silverfish in the stone; don't dig it up, remember where it is", true)),
            Map.entry("trial_chambers", new Note("a trial chamber: trial spawners pour mobs out at anyone close; never dig into it, keep the mine away", true)),
            Map.entry("ancient_city", new Note("an ancient city: the deep dark, sculk, a warden that kills in two hits; leave at once and never come back", true)),
            Map.entry("monument", new Note("an ocean monument: guardians (mining fatigue, lasers) and elder guardians; not without full gear and potions", true)),
            Map.entry("mansion", new Note("a woodland mansion: vindicators and evokers inside; not without full gear", true)),
            Map.entry("desert_pyramid", new Note("a desert temple: loot chests in the basement with TNT under the pressure plate; never step on the blue terracotta", true)),
            Map.entry("jungle_pyramid", new Note("a jungle temple: arrow traps on tripwires and a lever puzzle, a chest", true)),
            Map.entry("swamp_hut", new Note("a witch hut: a witch, a cauldron, nothing worth it", true)),
            Map.entry("igloo", new Note("an igloo: a bed and a chest; sometimes a basement with a zombie villager to cure", false)),
            Map.entry("shipwreck", new Note("a shipwreck: chests with food, iron and a treasure map", false)),
            Map.entry("buried_treasure", new Note("buried treasure: a chest with diamonds and a heart of the sea", false)),
            Map.entry("ruined_portal", new Note("a ruined portal: obsidian and a chest with gold; a start on a nether portal", false)),
            Map.entry("ocean_ruin", new Note("ocean ruins: drowned (tridents) around them, small loot", true)),
            Map.entry("nether_fossil", new Note("a nether fossil: bone blocks", false)),
            Map.entry("fortress", new Note("a nether fortress: blazes (rods for the End) and wither skeletons; the way to blaze rods", true)),
            Map.entry("bastion_remnant", new Note("a bastion: piglin brutes; gold armor keeps the ordinary piglins calm", true)),
            Map.entry("end_city", new Note("an end city: shulkers, elytra on the ship", true)),
            Map.entry("trail_ruins", new Note("trail ruins: buried, brush the suspicious gravel for pottery and loot", false)),
            Map.entry("nether_portal", new Note("a lit nether portal: a way between the Overworld and the Nether", false)),
            Map.entry("obsidian", new Note("obsidian: maybe a portal frame, maybe where lava met water; obsidian for a portal either way", false)),
            Map.entry("fossil", new Note("a fossil: bone blocks, sometimes coal or diamond ore in it", false)));

    /** The structures he has SEEN with a piece within 48 of him, nearest first, as (id, note, distance, "danger" or ""). Never what's in the rock unseen. */
    public static List<String[]> around(ClaudeEntity g, ServerLevel level) {
        BlockPos at = g.blockPosition();
        java.util.Map<String, Integer> near = new java.util.LinkedHashMap<>();
        for (var s : Perception.sightings(g)) {
            if (s.dim() != level.dimension()) continue;
            int d = (int) Math.sqrt(distSqr(s.box(), at));
            if (d <= 48) near.merge(s.id(), d, Math::min);
        }
        List<String[]> out = new ArrayList<>();
        near.forEach((id, d) -> out.add(new String[] {id, what(id), String.valueOf(d), danger(id) ? "danger" : ""}));
        out.sort(java.util.Comparator.comparingInt(a -> Integer.parseInt(a[2])));
        return out;
    }

    public static BlockPos testPiece; // gametest: the box has no generated structures; the fixture says where "the piece" is

    /** The centre of the nearest piece he has SEEN of a structure whose id contains {@code idPart} (null: any of the ones with loot) within r, or null. He walks to what he has looked at; the chests inside are still found by eye. */
    public static BlockPos nearestPiece(ClaudeEntity g, ServerLevel level, BlockPos at, String idPart, int r) {
        if (testPiece != null) return testPiece;
        BlockPos best = null; double bd = Double.MAX_VALUE;
        for (var s : Perception.sightings(g)) {
            if (s.dim() != level.dimension()) continue;
            String id = s.id();
            if (idPart != null && !id.contains(idPart)) continue;
            if (idPart == null && !(id.contains("ruined_portal") || id.contains("shipwreck") || id.contains("desert_pyramid") || id.contains("igloo") || id.contains("mineshaft") || id.contains("village"))) continue;
            BlockPos c = s.nearestCell(net.minecraft.world.phys.Vec3.atCenterOf(at)); // (a cell he saw, not a box's middle in the rock)
            double d = c.distSqr(at);
            if (d <= (double) r * r && d < bd) { bd = d; best = c.immutable(); }
        }
        return best;
    }

    /** A dangerous structure he has SEEN with a piece here (its box, a block of slack), and not his own mine: a player knows the places he has looked at, never what's inside the rock. Null otherwise. */
    public static Perception.Sighting seenDangerAt(ClaudeEntity g, BlockPos p) {
        if (ClaudeLife.ownMine(g, p)) return null; // his own stairs and rows are his, whatever a structure's box overlaps (world 14: two blocks from them in his strip mine at y -58, "not geared up for a mineshaft")
        for (var s : Perception.sightings(g)) if (s.dim() == g.level().dimension() && danger(s.id()) && s.near(p, Perception.Sighting.INFER + 1)) return s; // (by the cells he saw: a box round a whole mineshaft took the rock between its corridors)
        return null;
    }

    /** One line for his situation: what's around and what it means, or "" when nothing is. */
    public static String describe(ClaudeEntity g, ServerLevel level) {
        var list = around(g, level);
        if (list.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("structures you've seen near you: ");
        for (int i = 0; i < Math.min(3, list.size()); i++) {
            var s = list.get(i);
            sb.append(i == 0 ? "" : "; ").append(s[1]).append(" (").append(s[2]).append(" blocks)");
        }
        return sb.append("\n").toString();
    }
}
