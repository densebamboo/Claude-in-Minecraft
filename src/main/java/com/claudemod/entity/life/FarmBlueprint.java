package com.claudemod.entity.life;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.*;
import java.util.function.ToIntFunction;

/**
 * His farm (the user, 2026-10-29): simple, fairly large, good to look at: plots of 9x9 farmland each with one water source
 * in the middle (water reaches four out each way, so every block is wet: the optimal layout), side by side with a one-wide
 * path between, a fence ring one block out with a gate on the side toward home, torches on the corner posts and every five
 * along each side. One plan that is also its record: {@link #reconcile} says what is missing, stage by stage.
 *
 * Frame: {@code origin} is the ground block (the dirt that becomes farmland) at plot 0's first corner; {@code along} runs
 * the row of plots; {@code across} = along turned clockwise. cell(a, d, y): a along, d across, y up from the ground block.
 * Inside: a 0..W-1 (W = plots*10-1), d 0..8; the ring at a -1 and W, d -1 and 9, one up (y 1); the gate in the middle of the
 * d = -1 side (that side faces home).
 */
public final class FarmBlueprint {
    public enum Role { FARMLAND, WATER, PATH, FENCE, GATE, TORCH, CROP }

    public final BlockPos origin; public final Direction along; public final int plots; public String wood = "oak";

    public FarmBlueprint(BlockPos origin, Direction along, int plots) { this.origin = origin.immutable(); this.along = along; this.plots = Math.max(1, Math.min(4, plots)); }

    public Direction across() { return along.getClockWise(); }
    public Direction home() { return across().getOpposite(); } // the gate's side
    public int width() { return plots * 10 - 1; }
    public BlockPos cell(int a, int d, int y) { return origin.relative(along, a).relative(across(), d).above(y); }
    public BlockPos centre() { return cell(width() / 2, 4, 1); }
    public BlockPos gate() { return cell(width() / 2, -1, 1); }
    public BlockPos outside() { return cell(width() / 2, -3, 1); } // in front of the gate
    public List<BlockPos> waterCells() { List<BlockPos> w = new ArrayList<>(); for (int p = 0; p < plots; p++) w.add(cell(p * 10 + 4, 4, 0)); return w; }

    public Map<BlockPos, Role> cells() {
        Map<BlockPos, Role> m = new LinkedHashMap<>();
        int W = width();
        for (int a = 0; a < W; a++) for (int d = 0; d <= 8; d++) m.put(cell(a, d, 0), a % 10 == 9 ? Role.PATH : Role.FARMLAND);
        for (BlockPos w : waterCells()) m.put(w, Role.WATER);
        for (int a = -1; a <= W; a++) { m.put(cell(a, -1, 1), Role.FENCE); m.put(cell(a, 9, 1), Role.FENCE); }
        for (int d = 0; d <= 8; d++) { m.put(cell(-1, d, 1), Role.FENCE); m.put(cell(W, d, 1), Role.FENCE); }
        m.put(gate(), Role.GATE);
        for (BlockPos t : torchCells()) m.put(t, Role.TORCH);
        return m;
    }
    /** The posts that carry a torch: the four corners and every fifth post along each side. */
    public List<BlockPos> torchCells() {
        List<BlockPos> out = new ArrayList<>(); int W = width();
        for (int a = -1; a <= W; a++) if (a == -1 || a == W || (a + 1) % 5 == 0) { if (!cell(a, -1, 1).equals(gate())) out.add(cell(a, -1, 2)); out.add(cell(a, 9, 2)); }
        for (int d = 4; d <= 4; d++) { out.add(cell(-1, d, 2)); out.add(cell(W, d, 2)); }
        return out;
    }
    public List<BlockPos> farmlandCells() { List<BlockPos> out = new ArrayList<>(); for (var e : cells().entrySet()) if (e.getValue() == Role.FARMLAND) out.add(e.getKey()); return out; }
    public boolean touches(BlockPos p) { return cells().containsKey(p) || (cells().containsKey(p.below()) && (cells().get(p.below()) == Role.FARMLAND || cells().get(p.below()) == Role.WATER || cells().get(p.below()) == Role.PATH)); }
    public Role roleAt(BlockPos p) { return cells().get(p); }

    private Item byName(String id) { Item it = BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace(id)); return it == Items.AIR ? Items.OAK_FENCE : it; }
    public Item fence() { return byName(wood + "_fence"); }
    public Item gateItem() { return byName(wood + "_fence_gate"); }

    /** The plot cell he works a ring cell from: straight inside it (a corner: the plot's corner cell, diagonally). */
    public BlockPos standFor(BlockPos ring) {
        int a = (ring.getX() - origin.getX()) * along.getStepX() + (ring.getZ() - origin.getZ()) * along.getStepZ(), d = (ring.getX() - origin.getX()) * across().getStepX() + (ring.getZ() - origin.getZ()) * across().getStepZ();
        int a2 = Math.max(0, Math.min(width() - 1, a)), d2 = Math.max(0, Math.min(8, d));
        return cell(a2, d2, 1);
    }

    public static boolean solid(ServerLevel level, BlockPos p) { return !level.getBlockState(p).getCollisionShape(level, p).isEmpty(); }
    public boolean rightBlock(ServerLevel level, BlockPos p, Role r) {
        BlockState s = level.getBlockState(p);
        return switch (r) {
            case FARMLAND -> s.is(Blocks.FARMLAND);
            case WATER -> level.getFluidState(p).isSource() && level.getFluidState(p).is(net.minecraft.tags.FluidTags.WATER);
            case PATH -> s.is(BlockTags.DIRT) || s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.DIRT_PATH);
            case FENCE -> s.getBlock() instanceof FenceBlock;
            case GATE -> s.getBlock() instanceof FenceGateBlock;
            case TORCH -> s.is(Blocks.TORCH);
            case CROP -> s.getBlock() instanceof CropBlock;
        };
    }
    public boolean complete(ServerLevel level) { for (var e : cells().entrySet()) if (!rightBlock(level, e.getKey(), e.getValue())) return false; return true; }

    public final Map<Item, Integer> missing = new LinkedHashMap<>();
    /** Water cells that are dug but dry (the bucket's job, done by the life code). */
    public List<BlockPos> dryWater(ServerLevel level) { List<BlockPos> out = new ArrayList<>(); for (BlockPos w : waterCells()) if (!rightBlock(level, w, Role.WATER) && !solid(level, w)) out.add(w); return out; }

    /**
     * The tasks that take the farm one stage nearer the plan (clearing, the ground, the fence, the water cells dug, tilling,
     * torches, planting), from what he has ({@code have}); never more than {@code max}. Water is poured by the life code.
     */
    public List<Task> reconcile(ServerLevel level, ToIntFunction<Item> have, int max) {
        List<Task> out = new ArrayList<>(); missing.clear();
        Map<Item, Integer> budget = new HashMap<>();
        java.util.function.BiFunction<BlockPos, Item, Boolean> place = (p, it) -> {
            int left = budget.computeIfAbsent(it, have::applyAsInt);
            if (left <= 0) { missing.merge(it, 1, Integer::sum); return false; }
            budget.put(it, left - 1);
            if (solid(level, p) && !level.getBlockState(p).canBeReplaced()) out.add(new Task.Break(p));
            out.add(new Task.Place(p, it));
            return true;
        };
        var cells = cells(); int W = width();
        // 1. clearing: nothing standing inside the ring or on it that isn't the plan's (a tree, a rock, tall grass goes on its own)
        for (int a = -1; a <= W; a++) for (int d = -1; d <= 9; d++) for (int y = 1; y <= 2; y++) {
            BlockPos p = cell(a, d, y); Role r = cells.get(p);
            BlockState s = level.getBlockState(p);
            if (s.isAir() || s.canBeReplaced() || !solid(level, p)) continue;
            if (r != null && rightBlock(level, p, r)) continue;
            if (s.getBlock() instanceof CropBlock || s.hasBlockEntity()) continue;
            if (out.size() < max) out.add(new Task.Break(p));
        }
        if (!out.isEmpty()) return out;
        // 2. the ground: dirt under every inside cell and under the ring (farmland comes from dirt; a fence stands on a block)
        for (int a = -1; a <= W; a++) for (int d = -1; d <= 9; d++) {
            BlockPos p = cell(a, d, 0); Role r = cells.get(p); BlockState s = level.getBlockState(p);
            boolean ok = r == Role.WATER ? true : r == null ? solid(level, p) : s.is(BlockTags.DIRT) || s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.FARMLAND) || s.is(Blocks.DIRT_PATH);
            if (ok) continue;
            if (out.size() >= max) return out;
            if (r == null) { place.apply(p, Items.DIRT); continue; }
            if (solid(level, p)) out.add(new Task.Break(p));
            place.apply(p, Items.DIRT);
        }
        if (!out.isEmpty()) return out;
        // 3. the fence ring and the gate
        // each post from its inside neighbour (a fence placed from outside, across posts already standing, has no face he can see);
        // the corners first, from the plot's corner cell, before the posts beside them go in
        List<BlockPos> posts = new ArrayList<>();
        for (BlockPos p : List.of(cell(-1, -1, 1), cell(W, -1, 1), cell(-1, 9, 1), cell(W, 9, 1))) posts.add(p);
        if (!rightBlock(level, gate(), Role.GATE)) { out.add(new Task.GoTo(standFor(gate()), 0.5)); place.apply(gate(), gateItem()); } // the gate first: his way in and out while the ring closes
        for (var e : cells.entrySet()) if (e.getValue() == Role.FENCE && !posts.contains(e.getKey())) posts.add(e.getKey());
        for (BlockPos p : posts) { if (out.size() >= max) return out; if (!rightBlock(level, p, Role.FENCE)) { out.add(new Task.GoTo(standFor(p), 0.5)); place.apply(p, fence()); } }
        if (!out.isEmpty()) return out;
        // 4. the water cells dug (the pour is the life code's, with the bucket)
        for (BlockPos w : waterCells()) if (solid(level, w) && !rightBlock(level, w, Role.WATER) && out.size() < max) out.add(new Task.Break(w));
        if (!out.isEmpty()) return out;
        if (!dryWater(level).isEmpty()) return out; // (dry: the pour first, nothing else until the water is in)
        // 5. tilling, from the far side toward the gate
        for (int d = 8; d >= 0; d--) for (int a = 0; a < W; a++) { BlockPos p = cell(a, d, 0); if (cells.get(p) == Role.FARMLAND && !rightBlock(level, p, Role.FARMLAND) && (level.getBlockState(p).is(BlockTags.DIRT) || level.getBlockState(p).is(Blocks.GRASS_BLOCK))) { if (out.size() >= max) return out; out.add(new Task.Till(p)); } }
        if (!out.isEmpty()) return out;
        // 6. torches on the posts
        for (BlockPos t : torchCells()) { if (out.size() >= max) return out; if (!rightBlock(level, t, Role.TORCH) && rightBlock(level, t.below(), Role.FENCE)) { out.add(new Task.GoTo(standFor(t.below()), 0.5)); place.apply(t, Items.TORCH); } }
        if (!out.isEmpty()) return out;
        // 7. planting: whatever seeds he has, one kind per plot (wheat first)
        List<Item> seeds = new ArrayList<>();
        for (Item it : new Item[] {Items.WHEAT_SEEDS, Items.CARROT, Items.POTATO, Items.BEETROOT_SEEDS}) if (have.applyAsInt(it) > 0) seeds.add(it);
        if (seeds.isEmpty()) return out;
        for (int p0 = 0; p0 < plots; p0++) {
            Item seed = seeds.get(Math.min(p0, seeds.size() - 1));
            for (int a = p0 * 10; a < p0 * 10 + 9; a++) for (int d = 0; d <= 8; d++) {
                BlockPos ground = cell(a, d, 0), crop = ground.above();
                if (!rightBlock(level, ground, Role.FARMLAND) || !level.getBlockState(crop).isAir()) continue;
                if (out.size() >= max) return out;
                if (!place.apply(crop, seed)) break;
            }
        }
        return out;
    }

    public String stage(ServerLevel level) {
        var cells = cells(); int W = width();
        int clearing = 0, ground = 0, fence = 0, water = 0, till = 0, torch = 0, crops = 0;
        for (int a = -1; a <= W; a++) for (int d = -1; d <= 9; d++) for (int y = 1; y <= 2; y++) { BlockPos p = cell(a, d, y); Role r = cells.get(p); BlockState s = level.getBlockState(p); if (!s.isAir() && !s.canBeReplaced() && solid(level, p) && !(r != null && rightBlock(level, p, r)) && !(s.getBlock() instanceof CropBlock)) clearing++; }
        for (var e : cells.entrySet()) switch (e.getValue()) { case FENCE, GATE -> { if (!rightBlock(level, e.getKey(), e.getValue())) fence++; } case WATER -> { if (!rightBlock(level, e.getKey(), Role.WATER)) water++; } case FARMLAND -> { if (!rightBlock(level, e.getKey(), Role.FARMLAND)) till++; else if (level.getBlockState(e.getKey().above()).isAir()) crops++; } case TORCH -> { if (!rightBlock(level, e.getKey(), Role.TORCH)) torch++; } default -> {} }
        if (clearing > 0) return "clearing the ground (" + clearing + ")";
        if (fence > 0) return "the fence (" + fence + " to go)";
        if (water > 0) return "the water (" + water + ")";
        if (till > 0) return "tilling (" + till + " to go)";
        if (torch > 0) return "the torches (" + torch + ")";
        if (crops > 0) return "planting (" + crops + " to go)";
        return "done";
    }

    /** Flat ground of dirt or grass for the whole print, nothing wet in it, nothing of theirs: a place for the farm. */
    public static boolean fits(ServerLevel level, BlockPos origin, Direction along, int plots) {
        FarmBlueprint f = new FarmBlueprint(origin, along, plots); int y0 = origin.getY();
        for (int a = -2; a <= f.width() + 1; a++) for (int d = -2; d <= 10; d++) {
            BlockPos c = f.cell(a, d, 0);
            int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, c.getX(), c.getZ());
            if (top != y0 + 1) return false; // dead flat: farmland and a fence line want one level
            BlockState g = level.getBlockState(c);
            if (!(g.is(BlockTags.DIRT) || g.is(Blocks.GRASS_BLOCK) || g.is(Blocks.FARMLAND))) return false;
            for (int y = -1; y <= 2; y++) if (!level.getFluidState(c.above(y)).isEmpty() && !(y == 0 && f.waterCells().contains(c))) return false;
            if (level.getBlockState(c.above()).is(BlockTags.LOGS)) return false;
        }
        return true;
    }

    public CompoundTag toNbt() {
        CompoundTag t = new CompoundTag();
        t.putInt("x", origin.getX()); t.putInt("y", origin.getY()); t.putInt("z", origin.getZ()); t.putString("along", along.getName()); t.putInt("plots", plots); t.putString("wood", wood);
        return t;
    }
    public static FarmBlueprint fromNbt(CompoundTag t) {
        FarmBlueprint f = new FarmBlueprint(new BlockPos(t.getInt("x"), t.getInt("y"), t.getInt("z")), Direction.byName(t.getString("along")), t.getInt("plots"));
        if (t.contains("wood")) f.wood = t.getString("wood");
        return f;
    }
    @Override public String toString() { return "farm of " + plots + " plot(s) at " + origin.toShortString() + " along " + along.getName() + " (" + wood + " fence)"; }
}
