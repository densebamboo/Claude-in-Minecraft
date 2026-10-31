package com.claudemod.entity.life;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.BedItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;

import java.util.*;
import java.util.function.ToIntFunction;

/**
 * His house as one blueprint (2026-10-29): a plains-village starter house, 7x7 outside (5x5 inside), three high inside, a
 * cobblestone foundation, log posts, plank walls of ONE wood with window gaps, a wooden door with a step, a flat plank roof
 * with a one-block overhang (a stairs gable later), and inside: the bed in the back-left corner, a double chest and the
 * crafting table along the right wall, the furnace on the left, torches where it's dark, nothing in the middle.
 *
 * Coordinates: {@code origin} is the front-left corner cell of the outer ring at floor level (feet), seen from outside
 * looking at the door; {@code facing} is the way the door looks out. {@code across} runs left to right (0..6),
 * {@code inward} front to back (0..6); y is relative to the floor: -1 the foundation, 0..2 the three courses, 3 the roof.
 *
 * One {@link #reconcile} pass returns the tasks for what is wrong, in build order (foundation, posts, walls, door, roof,
 * the inside cleared, furnishing, lanterns), so a partial build reads as a house going up and a damaged one is repaired the
 * same way. Materials are named per role and never substituted: a Place is only emitted for a block he has; what he lacks
 * comes back in {@link #missing} so the skill gathers it. The user: "block choice is important".
 */
public class HouseBlueprint {
    public enum Role { FOUNDATION, FLOOR, POST, WALL, WINDOW, DOOR, STEP, ROOF, INTERIOR, BED, CHEST, TABLE, FURNACE }

    public BlockPos origin; public Direction facing;
    public String wood; // "oak", "spruce"...: fixed when the first post goes in; null until then
    public boolean stairsRoof; // the gable of stairs instead of the flat roof (later)
    public static final int W = 7, D = 7, H = 3; // outer footprint and the inside height

    private Map<BlockPos, Role> cells; // derived, cached
    private final List<BlockPos> order = new ArrayList<>(); // every structure cell in build order
    public final Map<Item, Integer> missing = new LinkedHashMap<>(); // after a reconcile: what he lacks for the cells it skipped

    public HouseBlueprint(BlockPos origin, Direction facing) { this.origin = origin.immutable(); this.facing = facing; }

    public Direction across() { return facing.getCounterClockWise(); } // the viewer's left to right, facing the door from outside
    public Direction inward() { return facing.getOpposite(); }
    public BlockPos cell(int a, int d, int y) { return origin.relative(across(), a).relative(inward(), d).above(y); }
    public BlockPos centre() { return cell(3, 3, 0); }
    public BlockPos doorCell() { return cell(3, 0, 0); }
    public BlockPos outside() { return cell(3, -2, 0); } // two out from the door: where "home" walks end
    public BlockPos inside() { return cell(3, 1, 0); }
    public BlockPos bedHead() { return cell(1, 5, 0); }
    public BlockPos bedFoot() { return cell(2, 5, 0); }
    public List<BlockPos> chestCells() { return List.of(cell(5, 1, 0), cell(5, 2, 0)); }
    public BlockPos tableCell() { return cell(5, 4, 0); }
    public BlockPos furnaceCell() { return cell(1, 3, 0); }
    public BlockPos furnace2Cell() { return cell(1, 2, 0); }
    /** The grove: sapling spots three out from the side and back walls, two apart, on dirt or grass (wood on tap by the house). */
    public List<BlockPos> grove() { return List.of(cell(-3, 1, 0), cell(-3, 5, 0), cell(W + 2, 1, 0), cell(W + 2, 5, 0), cell(1, D + 2, 0), cell(5, D + 2, 0)); }

    /** Every cell's role; cells outside the house are absent. Order of insertion = build order. */
    public Map<BlockPos, Role> cells() {
        if (cells != null) return cells;
        Map<BlockPos, Role> m = new LinkedHashMap<>();
        for (int a = 0; a < W; a++) for (int d = 0; d < D; d++) m.put(cell(a, d, -1), Role.FOUNDATION);
        m.put(cell(3, -1, -1), Role.STEP);
        for (int y = 0; y < H; y++) for (int[] c : new int[][] {{0, 0}, {W - 1, 0}, {0, D - 1}, {W - 1, D - 1}}) m.put(cell(c[0], c[1], y), Role.POST);
        for (int y = 0; y < H; y++) for (int a = 0; a < W; a++) for (int d = 0; d < D; d++) {
            boolean ring = a == 0 || a == W - 1 || d == 0 || d == D - 1;
            if (!ring || m.containsKey(cell(a, d, y))) continue;
            boolean door = d == 0 && a == 3 && y < 2;
            boolean window = y == 1 && ((d == 3 && (a == 0 || a == W - 1)) || (a == 3 && d == D - 1));
            m.put(cell(a, d, y), door ? Role.DOOR : window ? Role.WINDOW : Role.WALL);
        }
        for (int a = -1; a <= W; a++) for (int d = -1; d <= D; d++) m.put(cell(a, d, H), Role.ROOF);
        for (int a = 1; a < W - 1; a++) for (int d = 1; d < D - 1; d++) {
            m.put(cell(a, d, 0), Role.INTERIOR); m.put(cell(a, d, 1), Role.INTERIOR); m.put(cell(a, d, 2), Role.INTERIOR);
        }
        m.put(bedHead(), Role.BED); m.put(bedFoot(), Role.BED);
        for (BlockPos c : chestCells()) m.put(c, Role.CHEST);
        m.put(tableCell(), Role.TABLE); m.put(furnaceCell(), Role.FURNACE); m.put(furnace2Cell(), Role.FURNACE);
        cells = m; return m;
    }
    public Role roleAt(BlockPos p) { return cells().get(p); }
    public boolean isStructure(BlockPos p) { Role r = roleAt(p); return r != null && r != Role.INTERIOR && r != Role.BED && r != Role.CHEST && r != Role.TABLE && r != Role.FURNACE; }
    public boolean touches(BlockPos p) { return roleAt(p) != null; }
    public boolean insideRoom(BlockPos p) { Role r = roleAt(p); return r == Role.INTERIOR || r == Role.BED || r == Role.CHEST || r == Role.TABLE || r == Role.FURNACE; }
    public void invalidate() { cells = null; }

    // ---- materials: named per role, one wood ---------------------------------------------------------------------------
    public Item planks() { return item(wood + "_planks", Items.OAK_PLANKS); }
    public Item log() { return item(wood + "_log", Items.OAK_LOG); }
    public Item door() { return item(wood + "_door", Items.OAK_DOOR); }
    public Item fence() { return item(wood + "_fence", Items.OAK_FENCE); }
    static Item item(String id, Item dflt) { var r = BuiltInRegistries.ITEM.getOptional(ResourceLocation.withDefaultNamespace(id)); return r.isPresent() ? r.get() : dflt; }
    /** The block a role takes, or null for roles that are air or furniture (handled by name). */
    public Item filler(Role r) {
        return switch (r) {
            case FOUNDATION, STEP -> Items.COBBLESTONE;
            case POST -> log();
            case WALL, ROOF -> planks();
            case DOOR -> door();
            case WINDOW -> fence();
            case BED -> null; case CHEST -> Items.CHEST; case TABLE -> Items.CRAFTING_TABLE; case FURNACE -> Items.FURNACE;
            default -> null;
        };
    }
    static boolean solid(ServerLevel level, BlockPos p) { return !level.getBlockState(p).getCollisionShape(level, p).isEmpty() && level.getFluidState(p).isEmpty(); }
    public boolean rightBlock(ServerLevel level, BlockPos p, Role r) {
        BlockState s = level.getBlockState(p);
        return switch (r) {
            case FOUNDATION -> solid(level, p) && !s.is(BlockTags.LOGS) && !s.is(BlockTags.LEAVES); // the ground itself counts (a villager's house sits on it); cobble fills holes
            case STEP -> true; // optional
            case POST -> s.is(BlockTags.LOGS);
            case WALL, ROOF -> s.is(BlockTags.PLANKS);
            case DOOR -> s.getBlock() instanceof DoorBlock;
            case WINDOW -> s.isAir() || s.is(BlockTags.FENCES) || s.getBlock() instanceof StainedGlassPaneBlock || s.is(Blocks.GLASS_PANE) || s.is(Blocks.GLASS);
            case INTERIOR -> s.getCollisionShape(level, p).isEmpty() || s.is(Blocks.WALL_TORCH) || s.is(Blocks.TORCH) || s.is(BlockTags.BEDS) || s.is(Blocks.CHEST) || s.is(Blocks.CRAFTING_TABLE) || s.is(Blocks.FURNACE);
            case BED -> s.is(BlockTags.BEDS);
            case CHEST -> s.is(Blocks.CHEST);
            case TABLE -> s.is(Blocks.CRAFTING_TABLE);
            case FURNACE -> s.is(Blocks.FURNACE) || s.is(Blocks.BLAST_FURNACE);
            case FLOOR -> true;
        };
    }

    /** Is the house finished (every structure cell right, the furniture in, the door hung)? */
    public boolean complete(ServerLevel level) {
        for (var e : cells().entrySet()) { if (e.getValue() == Role.FURNACE && e.getKey().equals(furnace2Cell())) continue; if (!rightBlock(level, e.getKey(), e.getValue())) return false; }
        return true;
    }
    public boolean shellStands(ServerLevel level) {
        for (var e : cells().entrySet()) { Role r = e.getValue(); if ((r == Role.POST || r == Role.WALL || r == Role.ROOF || r == Role.DOOR) && !rightBlock(level, e.getKey(), r)) return false; }
        return true;
    }

    /**
     * The tasks for what is wrong, in build order. {@code have} gives the count of an item he carries (chests included);
     * a Place goes out only when he has the block, the rest is counted into {@link #missing}. {@code protectedCell} says
     * which cells are theirs (never touched). At most {@code max} tasks.
     */
    public List<Task> reconcile(ServerLevel level, ToIntFunction<Item> have, java.util.function.Predicate<BlockPos> protectedCell, int max) {
        List<Task> out = new ArrayList<>(); missing.clear();
        Map<Item, Integer> budget = new HashMap<>();
        java.util.function.BiFunction<BlockPos, Item, Boolean> place = (p, it) -> {
            int left = budget.computeIfAbsent(it, have::applyAsInt);
            if (left <= 0) { missing.merge(it, 1, Integer::sum); return false; }
            budget.put(it, left - 1);
            if (solid(level, p) && !level.getBlockState(p).canBeReplaced()) out.add(new Task.Break(p)); // the wrong block out first (dirt in a wall, a stray cobble)
            out.add(new Task.Place(p, it));
            return true;
        };
        Role[] stages = {Role.FOUNDATION, Role.POST, Role.WALL, Role.DOOR, Role.STEP, Role.ROOF, Role.WINDOW, Role.INTERIOR, Role.BED, Role.CHEST, Role.TABLE, Role.FURNACE};
        for (Role stage : stages) {
            for (var e : cells().entrySet()) {
                if (out.size() >= max) return out;
                BlockPos p = e.getKey(); Role r = e.getValue();
                if (r != stage || protectedCell.test(p) || rightBlock(level, p, r)) continue;
                switch (r) {
                    case FOUNDATION -> { // a hole under the ring or the floor: cobble, stacked up from the first solid block (a slope), at most two deep
                        int depth = 0; while (depth < 2 && !solid(level, p.below(depth + 1))) depth++;
                        for (int k = depth; k >= 0 && out.size() < max; k--) if (!solid(level, p.below(k))) { if (!place.apply(p.below(k), Items.COBBLESTONE)) break; }
                    }
                    case STEP -> { if (!solid(level, p) && solid(level, p.below()) && level.getBlockState(p).canBeReplaced()) {} } // optional: only when the ground outside is a block lower (left out of v1)
                    case POST, WALL, ROOF -> place.apply(p, filler(r));
                    case DOOR -> { if (p.equals(cell(3, 0, 0))) { BlockState s = level.getBlockState(p); if (!s.isAir() && !(s.getBlock() instanceof DoorBlock)) out.add(new Task.Break(p)); BlockState s1 = level.getBlockState(p.above()); if (!s1.isAir() && !(s1.getBlock() instanceof DoorBlock)) out.add(new Task.Break(p.above())); place.apply(p, door()); } } // (the lower half: the game sets both)
                    case WINDOW -> { BlockState s = level.getBlockState(p); if (!s.isAir() && !rightBlock(level, p, r)) out.add(new Task.Break(p)); else if (s.isAir() && have.applyAsInt(fence()) > 0) place.apply(p, fence()); } // an open gap until he has fences or panes
                    case INTERIOR -> { if (!rightBlock(level, p, r)) out.add(new Task.Break(p)); } // nothing in the middle of the room
                    case BED -> { if (p.equals(bedFoot())) { Item bed = null; for (Item it : BuiltInRegistries.ITEM) if (it instanceof BedItem && have.applyAsInt(it) > 0) { bed = it; break; } if (bed == null) missing.merge(Items.WHITE_BED, 1, Integer::sum); else { if (solid(level, p) || solid(level, bedHead())) { out.add(new Task.Break(p)); out.add(new Task.Break(bedHead())); } if (place.apply(p, bed)) out.set(out.size() - 1, new Task.Place(p, bed, across().getOpposite())); } } /* the head the blueprint's way, into the corner (with no facing doPlace ran it to the first free cell: the wrong way in a house facing south, and the head's cell never read right) */
                        else if (p.equals(bedHead()) && level.getBlockState(bedFoot()).is(BlockTags.BEDS)) out.add(new Task.Break(bedFoot())); } // a bed on the foot's cell with its head the wrong way: up, and down again the right way
                    case CHEST, TABLE, FURNACE -> { if (r == Role.FURNACE && p.equals(furnace2Cell()) && budget.getOrDefault(Items.FURNACE, have.applyAsInt(Items.FURNACE)) < 1) continue; /* a second furnace only from a spare; never waited for */ if (solid(level, p) && !rightBlock(level, p, r)) out.add(new Task.Break(p)); if (place.apply(p, filler(r)) && r != Role.TABLE) out.set(out.size() - 1, new Task.Place(p, filler(r), r == Role.CHEST ? across().getOpposite() : across())); /* a chest's or furnace's front toward the room: the chests sit against the right wall, the furnace against the left */ }
                    default -> {}
                }
            }
            if (!out.isEmpty() && (stage == Role.FOUNDATION || stage == Role.POST || stage == Role.WALL || stage == Role.ROOF)) return out; // one stage at a time: a partial build reads as a house going up, and each course has something to stand on
        }
        // lanterns: inside on the back wall over the bed and beside the door, outside either side of the door, only where it's dark
        if (out.size() < max) for (BlockPos[] t : new BlockPos[][] {{cell(3, 5, 1), cell(3, 6, 1)}, {cell(2, 1, 1), cell(2, 0, 1)}, {cell(2, -1, 1), cell(2, 0, 1)}, {cell(4, -1, 1), cell(4, 0, 1)}}) {
            BlockPos at = t[0], wall = t[1];
            if (!level.getBlockState(at).isAir() || !solid(level, wall) || level.getBrightness(LightLayer.BLOCK, at) >= 8) continue;
            if (have.applyAsInt(Items.TORCH) <= 0) { missing.merge(Items.TORCH, 1, Integer::sum); break; }
            Direction toWall = Direction.getNearest(wall.getX() - at.getX(), 0, wall.getZ() - at.getZ());
            out.add(new Task.WallTorch(at, toWall));
        }
        return out;
    }

    /** The stage the house is at, for the log and the planner: foundation, frame, walls, door, roof, inside, done. */
    public String stage(ServerLevel level) {
        int holes = 0; for (var e : cells().entrySet()) if (e.getValue() == Role.FOUNDATION && !rightBlock(level, e.getKey(), Role.FOUNDATION)) holes++;
        if (holes > 0) return "foundation (" + holes + " to fill)";
        for (Role r : new Role[] {Role.POST, Role.WALL, Role.DOOR, Role.ROOF}) { int n = 0; for (var e : cells().entrySet()) if (e.getValue() == r && !rightBlock(level, e.getKey(), r)) n++; if (n > 0) return r.name().toLowerCase() + "s (" + n + " to go)"; }
        for (Role r : new Role[] {Role.BED, Role.CHEST, Role.TABLE, Role.FURNACE}) for (var e : cells().entrySet()) if (e.getValue() == r && !e.getKey().equals(furnace2Cell()) && !rightBlock(level, e.getKey(), r)) return "furnishing (" + r.name().toLowerCase() + ")";
        return "done";
    }

    // ---- the site ------------------------------------------------------------------------------------------------------
    /** Does this origin and facing fit: level ground under the 9x9 roof print (within -2..0 of the floor), no water or lava near, no trunk in the walls' way. */
    public static boolean fits(ServerLevel level, BlockPos origin, Direction facing) {
        HouseBlueprint h = new HouseBlueprint(origin, facing);
        int y0 = origin.getY();
        for (int a = -1; a <= W; a++) for (int d = -1; d <= D; d++) {
            BlockPos c = h.cell(a, d, 0);
            int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, c.getX(), c.getZ()); // the first air above the ground
            if (top > y0 || top < y0 - 2) return false; // a hill inside the print, or a drop deeper than a two-block foundation
            for (int y = -3; y <= H + 1; y++) if (!level.getFluidState(c.above(y)).isEmpty()) return false;
            for (int y = 0; y <= H + 1; y++) { BlockState s = level.getBlockState(c.above(y)); if (s.is(BlockTags.LOGS) && a >= 0 && a < W && d >= 0 && d < D) return false; }
        }
        for (int dx = -5; dx <= 5; dx++) for (int dz = -5; dz <= 5; dz++) { BlockPos c = h.centre().offset(dx, 0, dz); for (int y = -2; y <= 1; y++) if (!level.getFluidState(c.above(y)).isEmpty()) return false; }
        return true;
    }

    // ---- NBT -----------------------------------------------------------------------------------------------------------
    public CompoundTag toNbt() {
        CompoundTag t = new CompoundTag();
        t.putInt("x", origin.getX()); t.putInt("y", origin.getY()); t.putInt("z", origin.getZ()); t.putString("facing", facing.getName());
        if (wood != null) t.putString("wood", wood); t.putBoolean("stairsRoof", stairsRoof);
        return t;
    }
    public static HouseBlueprint fromNbt(CompoundTag t) {
        Direction f = Direction.byName(t.getString("facing")); if (f == null || f.getAxis().isVertical()) f = Direction.NORTH;
        HouseBlueprint h = new HouseBlueprint(new BlockPos(t.getInt("x"), t.getInt("y"), t.getInt("z")), f);
        if (t.contains("wood")) h.wood = t.getString("wood"); h.stairsRoof = t.getBoolean("stairsRoof");
        return h;
    }
    @Override public String toString() { return "house at " + origin.toShortString() + " facing " + facing.getName() + (wood == null ? "" : ", " + wood); }
}
