package com.claudemod.entity.life;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.BedItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;
import java.util.function.ToIntFunction;

/**
 * His base at the foot of the stairs (the user, 2026-10-29): a nook off the tunnel with a 2-wide opening (the width of the
 * double chest), the chests set into its back wall, a bed in a niche in its side wall, a torch, and across the walkway a
 * furnace wall of six, two wide and three high, set into the rock. One plan that is also its record: {@link #reconcile}
 * says what is missing or wrong, as his ordinary tasks, so the same pass builds it and keeps it.
 *
 * Frame: {@code origin} is the wall cell beside the tunnel where the nook's mouth starts (floor level), {@code ahead} runs
 * along the tunnel (the mouth is 2 wide that way), {@code side} runs into the rock. cell(w, d, y): w 0..1 across the mouth,
 * d 1 at the mouth .. deeper into the rock, d <= 0 the tunnel; y up from the floor.
 */
public final class BaseNook {
    public enum Role { WALK, CHEST, BED, TABLE, FURNACE, TORCH }

    public static final int DEPTH = 3, HEIGHT = 3; // the pocket: 2 wide, 3 deep, 3 high

    public final BlockPos origin; public final Direction ahead, side;

    public BaseNook(BlockPos origin, Direction ahead, Direction side) { this.origin = origin.immutable(); this.ahead = ahead; this.side = side; }

    public BlockPos cell(int w, int d, int y) { return origin.relative(ahead, w).relative(side, d - 1).above(y); }

    /** The tunnel cell in front of the mouth's left column (where he stands to work on the nook). */
    public BlockPos mouth() { return cell(0, 0, 0); }
    public List<BlockPos> chestCells() { return List.of(cell(0, DEPTH + 1, 0), cell(1, DEPTH + 1, 0), cell(0, DEPTH + 1, 1), cell(1, DEPTH + 1, 1)); } // 2x2 in the back wall, the bottom pair first
    /** The third row, on top of his storage: his junk chest (the user: "a 3rd chest on top for blocks"). */
    public List<BlockPos> junkChestCells() { return List.of(cell(0, DEPTH + 1, 2), cell(1, DEPTH + 1, 2)); }
    public List<BlockPos> allChestCells() { List<BlockPos> a = new ArrayList<>(chestCells()); a.addAll(junkChestCells()); return a; }
    public BlockPos bedFoot() { return cell(2, DEPTH, 0); } // the user (world 14): in the right-wall niche beside the chests, running into the rock
    public BlockPos bedHead() { return cell(2, DEPTH + 1, 0); } // its head in the back-wall line, beside the chests
    public Direction bedFacing() { return side; }
    /** Where the bed used to go (the back-row niche along the tunnel, then the pocket's middle row): a bed found there is moved, its niche filled back in (cells still in the layout are skipped). */
    public List<BlockPos> oldBedCells() { return List.of(cell(2, DEPTH, 0), cell(3, DEPTH, 0), cell(2, DEPTH, 1), cell(3, DEPTH, 1), cell(1, 2, 0), cell(2, 2, 0), cell(2, 2, 1), cell(2, 2, 2)); }
    public BlockPos tableCell() { return cell(2, 1, 0); } // in the right wall by the mouth
    public BlockPos torchCell() { return cell(0, 2, 1); } // on the left wall, eye height
    public Direction torchWall() { return ahead.getOpposite(); }
    /** The furnace wall across the walkway: the rock beyond the tunnel's far column, 2 wide along the tunnel, 3 high. */
    public List<BlockPos> furnaceCells() {
        List<BlockPos> out = new ArrayList<>();
        for (int y = 0; y < 3; y++) for (int w = 0; w <= 1; w++) out.add(cell(w, -tunnelWidth(), y));
        return out;
    }
    private final java.util.Set<BlockPos> sitingBugs = new java.util.HashSet<>(); // (each said once)
    private int tunnelWidth = 2;
    public int tunnelWidth() { return tunnelWidth; }
    public BaseNook tunnelWidth(int w) { this.tunnelWidth = Math.max(1, w); return this; }

    /** Every cell of the nook and what it is for. */
    public Map<BlockPos, Role> cells() {
        Map<BlockPos, Role> m = new LinkedHashMap<>();
        for (int d = 1; d <= DEPTH; d++) for (int w = 0; w <= 1; w++) for (int y = 0; y < HEIGHT; y++) m.put(cell(w, d, y), Role.WALK); // the pocket
        for (int w = 0; w <= 1; w++) m.put(cell(w, DEPTH + 1, 3), Role.WALK); // room over the top (junk) chests, or they won't open
        for (BlockPos c : chestCells()) m.put(c, Role.CHEST);
        for (BlockPos c : junkChestCells()) m.put(c, Role.CHEST);
        m.put(bedFoot(), Role.BED); m.put(bedHead(), Role.BED);
        for (int y = 1; y <= 2; y++) { m.put(bedFoot().above(y), Role.WALK); m.put(bedHead().above(y), Role.WALK); } // the user: 3 high over the bed, not 2
        m.put(tableCell(), Role.TABLE);
        m.put(torchCell(), Role.TORCH);
        for (BlockPos c : furnaceCells()) m.put(c, Role.FURNACE);
        return m;
    }
    public boolean touches(BlockPos p) { return cells().containsKey(p); }
    private java.util.Set<BlockPos> shellCache; private int shellFor = -1;
    /** The rock round it: every cell beside one of its cells that is none itself (walls, floor and roof round the pocket, the niches, the stations). The tunnel side is his mine's: ClaudeLife.nookShell leaves those out. */
    public java.util.Set<BlockPos> shell() {
        if (shellCache != null && shellFor == tunnelWidth) return shellCache;
        var cs = cells(); java.util.Set<BlockPos> s = new java.util.HashSet<>();
        for (BlockPos c : cs.keySet()) for (Direction d : Direction.values()) { BlockPos q = c.relative(d); if (!cs.containsKey(q)) s.add(q.immutable()); }
        shellCache = java.util.Collections.unmodifiableSet(s); shellFor = tunnelWidth; return shellCache;
    }
    public Role roleAt(BlockPos p) { return cells().get(p); }
    public boolean isBedCell(BlockPos p) { return p.equals(bedFoot()) || p.equals(bedHead()); }
    public Direction chestFacing() { return side.getOpposite(); } // their fronts toward the pocket
    public Direction furnaceFacing() { return side; } // toward the tunnel

    /** The floor cell he works on a station from: in front of a chest (the pocket), a furnace (the tunnel), the table (the pocket by the mouth). */
    public BlockPos standFor(BlockPos p) {
        if (chestCells().contains(p)) return cell(0, DEPTH, 0).relative(ahead, p.equals(chestCells().get(1)) || p.equals(chestCells().get(3)) ? 1 : 0);
        { int jw = junkChestCells().indexOf(p); if (jw >= 0) return cell(jw, DEPTH, 0); }
        if (p.equals(tableCell())) return cell(1, 1, 0);
        for (int i = 0; i < furnaceCells().size(); i++) if (furnaceCells().get(i).equals(p)) return cell(i % 2, -tunnelWidth() + 1, 0);
        // a cell to dig: straight on from the floor in front of it, never at an angle through the pocket's own wall (that cut a stray cell beside the bed niche)
        int w = (p.getX() - origin.getX()) * ahead.getStepX() + (p.getZ() - origin.getZ()) * ahead.getStepZ(), d = 1 + (p.getX() - origin.getX()) * side.getStepX() + (p.getZ() - origin.getZ()) * side.getStepZ();
        if (w >= 2 && d > DEPTH) return cell(2, DEPTH, 0); // the bed head's cell and over it: from the niche's front cell (dug first), straight on
        if (w >= 2) return cell(1, d, 0); // the niches in the right wall: from the pocket cell beside them
        if (d > DEPTH) return cell(w, DEPTH, 0); // over the chests: from the back row
        return cell(w, d - 1, 0); // the pocket: from the row before (the tunnel for the mouth)
    }

    public static boolean solid(ServerLevel level, BlockPos p) { return !level.getBlockState(p).getCollisionShape(level, p).isEmpty(); }

    public boolean rightBlock(ServerLevel level, BlockPos p, Role r) {
        BlockState s = level.getBlockState(p);
        return switch (r) {
            case WALK -> !solid(level, p) && level.getFluidState(p).isEmpty();
            case CHEST -> s.is(Blocks.CHEST);
            case BED -> s.is(BlockTags.BEDS);
            case TABLE -> s.is(Blocks.CRAFTING_TABLE);
            case FURNACE -> s.is(Blocks.FURNACE);
            case TORCH -> s.is(Blocks.WALL_TORCH) || s.is(Blocks.TORCH) || level.getBrightness(LightLayer.BLOCK, p) >= 8;
        };
    }

    /** True when every cell is right (the bed, table and torch too), stations with no floor to stand on for them aside. */
    public boolean complete(ServerLevel level) {
        for (var e : cells().entrySet()) {
            if (rightBlock(level, e.getKey(), e.getValue())) continue;
            if (e.getValue() == Role.FURNACE || e.getValue() == Role.TABLE) { BlockPos st0 = standFor(e.getKey()); if (solid(level, st0) || solid(level, st0.above()) || !solid(level, st0.below())) continue; } // (left out: nowhere to stand for it)
            return false;
        }
        return true;
    }

    /** What is still missing after the last reconcile (item -> count): for his log line, never waited for. */
    public final Map<Item, Integer> missing = new LinkedHashMap<>();

    /**
     * The tasks that take the nook one round nearer the plan: the digs first (mouth inward), then the stations from what
     * he has ({@code have}: how many of an item he can use, chests included). Never more than {@code max} tasks.
     */
    public List<Task> reconcile(ServerLevel level, ToIntFunction<Item> have, int max) {
        List<Task> out = new ArrayList<>(); missing.clear();
        Map<Item, Integer> budget = new HashMap<>();
        java.util.function.BiFunction<BlockPos, Item, Boolean> place = (p, it) -> {
            BlockPos st0 = standFor(p);
            if (solid(level, st0) || solid(level, st0.above()) || !solid(level, st0.below())) { if (sitingBugs.add(p.immutable())) com.claudemod.ClaudeMod.LOGGER.warn("[Claude life] NOOK SITING BUG: no floor to stand on for the {} at {} (stand {}): {} is sited where its layout doesn't fit", it, p.toShortString(), st0.toShortString(), this); return false; } // (a siting bug, not a smaller design: never planned, or he loops on it)
            int left = budget.computeIfAbsent(it, have::applyAsInt);
            if (left <= 0) { missing.merge(it, 1, Integer::sum); return false; }
            budget.put(it, left - 1);
            out.add(new Task.GoTo(standFor(p), 0.5)); // from the floor cell in front of it, like a player (never dug at from an angle through the wall)
            if (solid(level, p)) out.add(new Task.Break(p)); // the rock (or a wrong block) out first
            return true;
        };
        var cells = cells();
        BlockPos lastStand = null;
        for (var e : cells.entrySet()) { // digs: the pocket and the niches, so the stations have their spots (stations and their cells are dug when placed)
            if (out.size() >= max) return out;
            BlockPos p = e.getKey(); Role r = e.getValue();
            if ((r != Role.WALK && r != Role.BED) || rightBlock(level, p, r) || (r == Role.BED && !solid(level, p))) continue; // (the bed's cells too: its foot is in the pocket's middle now, and rock left there put him on top of it to set the top chests, which came out single)
            BlockState s = level.getBlockState(p);
            if (s.hasBlockEntity() || s.is(BlockTags.BEDS)) continue; // (never a station or bed dug out as "rock")
            if (p.getY() == origin.getY() + 3 && junkChestCells().contains(p.below()) && solid(level, p.below()) && !level.getBlockState(p.below()).is(Blocks.CHEST)) continue; // (the room over the junk row: cut once its chest cell is, from below; before that no face of it is in view)
            BlockPos stand = standFor(p);
            if (!stand.equals(lastStand)) { out.add(new Task.GoTo(stand, 0.5)); lastStand = stand; } // each cell straight on from the floor in front of it
            out.add(new Task.Break(p));
        }
        if (!out.isEmpty()) return out; // the digging round, then the stations next round (placed against dug walls, from dug floor)
        for (BlockPos c : allChestCells()) if (solid(level, c) && !rightBlock(level, c, Role.CHEST)) { if (out.size() >= max) return out; if (out.isEmpty() || !(out.get(0) instanceof Task.GoTo)) out.add(0, new Task.GoTo(standFor(c), 0.5)); out.add(new Task.Break(c)); } // the whole 2x2 cut out first (rock over a bottom chest hides its back wall and keeps the top one from opening)
        if (!out.isEmpty()) return out;
        for (BlockPos c : allChestCells()) { if (out.size() >= max) return out; if (!rightBlock(level, c, Role.CHEST) && place.apply(c, Items.CHEST)) out.add(new Task.Place(c, Items.CHEST, chestFacing())); }
        for (int i = out.size() - 1; i >= 0; i--) if (out.get(i) instanceof Task.Break b && !cells.containsKey(b.pos())) out.remove(i);
        if (out.size() < max && !level.getBlockState(bedFoot()).is(BlockTags.BEDS)) for (BlockPos c : oldBedCells()) { // the bed where it used to go: picked up (it goes in the new spot), its niche filled back in
            if (cells.containsKey(c)) continue;
            if (level.getBlockState(c).is(BlockTags.BEDS)) { com.claudemod.director.ClaudeLife.takeUpBed(level, c); out.add(new Task.GoTo(standFor(c), 0.5)); out.add(new Task.Break(c)); out.add(new Task.Pickup(40)); }
            else if (!solid(level, c) && level.getFluidState(c).isEmpty()) out.add(new Task.Place(c, c.getY() < 0 ? Items.COBBLED_DEEPSLATE : Items.COBBLESTONE));
        }
        if (!out.isEmpty()) return out;
        if (out.size() < max && level.getBlockState(bedFoot()).is(BlockTags.BEDS) ^ level.getBlockState(bedHead()).is(BlockTags.BEDS)) { // a bed lying the wrong way across his spot: picked up, set again the right way
            BlockPos wrong = level.getBlockState(bedFoot()).is(BlockTags.BEDS) ? bedFoot() : bedHead();
            com.claudemod.director.ClaudeLife.takeUpBed(level, wrong); out.add(new Task.GoTo(standFor(wrong), 0.5)); out.add(new Task.Break(wrong)); out.add(new Task.Pickup(40));
            return out;
        }
        if (!rightBlock(level, bedFoot(), Role.BED) || !rightBlock(level, bedHead(), Role.BED)) {
            Item bed = null; for (Item it : net.minecraft.core.registries.BuiltInRegistries.ITEM) if (it instanceof BedItem && have.applyAsInt(it) > 0) { bed = it; break; }
            if (bed == null) missing.merge(Items.WHITE_BED, 1, Integer::sum);
            else if (out.size() < max) {
                for (BlockPos c : new BlockPos[] {bedFoot(), bedHead()}) if (solid(level, c) && !level.getBlockState(c).is(BlockTags.BEDS)) out.add(new Task.Break(c));
                if (!level.getBlockState(bedFoot()).is(BlockTags.BEDS) && !level.getBlockState(bedHead()).is(BlockTags.BEDS)) out.add(new Task.Place(bedFoot(), bed, bedFacing())); // the head runs into the rock, beside the chests
            }
        }
        if (out.size() < max && !rightBlock(level, tableCell(), Role.TABLE) && place.apply(tableCell(), Items.CRAFTING_TABLE)) out.add(new Task.Place(tableCell(), Items.CRAFTING_TABLE));
        for (BlockPos f : furnaceCells()) { if (out.size() >= max) return out; if (!rightBlock(level, f, Role.FURNACE) && place.apply(f, Items.FURNACE)) out.add(new Task.Place(f, Items.FURNACE, furnaceFacing())); }
        if (out.size() < max && !rightBlock(level, torchCell(), Role.TORCH) && have.applyAsInt(Items.TORCH) > 0 && !solid(level, torchCell())) out.add(new Task.WallTorch(torchCell(), torchWall()));
        return out;
    }

    /** Where the work stands, for his line: "the pocket (4 to dig)", "the furnaces (3 to set)", "done". */
    public String stage(ServerLevel level) {
        int dig = 0, chests = 0, furn = 0;
        for (var e : cells().entrySet()) { if (rightBlock(level, e.getKey(), e.getValue())) continue; switch (e.getValue()) { case WALK -> dig++; case CHEST -> chests++; case FURNACE -> furn++; default -> {} } }
        if (dig > 0) return "the pocket (" + dig + " to dig)";
        if (chests > 0) return "the chests (" + chests + " to set)";
        if (!rightBlock(level, bedFoot(), Role.BED)) return "the bed";
        if (furn > 0) return "the furnaces (" + furn + " to set)";
        if (!rightBlock(level, torchCell(), Role.TORCH)) return "the torch";
        return "done";
    }

    public CompoundTag toNbt() {
        CompoundTag t = new CompoundTag();
        t.putInt("x", origin.getX()); t.putInt("y", origin.getY()); t.putInt("z", origin.getZ());
        t.putString("ahead", ahead.getName()); t.putString("side", side.getName()); t.putInt("tunnel", tunnelWidth);
        return t;
    }
    public static BaseNook fromNbt(CompoundTag t) {
        return new BaseNook(new BlockPos(t.getInt("x"), t.getInt("y"), t.getInt("z")), Direction.byName(t.getString("ahead")), Direction.byName(t.getString("side"))).tunnelWidth(t.contains("tunnel") ? t.getInt("tunnel") : 2);
    }
    @Override public boolean equals(Object o) { return o instanceof BaseNook n && n.origin.equals(origin) && n.ahead == ahead && n.side == side; }
    @Override public int hashCode() { return java.util.Objects.hash(origin, ahead, side); }
    /** His nooks into his save: the list under "nooks" (one per branch level), the one he's using under "nook" as well (a build from before the list reads that). */
    public static void saveAll(CompoundTag mine, List<BaseNook> all, BaseNook current) {
        List<BaseNook> out = new ArrayList<>(all == null ? List.of() : all); if (current != null && !out.contains(current)) out.add(current);
        net.minecraft.nbt.ListTag l = new net.minecraft.nbt.ListTag(); for (BaseNook n : out) l.add(n.toNbt());
        if (!l.isEmpty()) mine.put("nooks", l);
        if (current != null) mine.put("nook", current.toNbt());
    }
    /** His nooks from his save: the "nooks" list, and an old single "nook" folded in (a save from before the list). One that can't be read is said and left out. */
    public static List<BaseNook> readAll(CompoundTag mine) {
        List<BaseNook> out = new ArrayList<>(); if (mine == null) return out;
        for (net.minecraft.nbt.Tag x : mine.getList("nooks", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            try { BaseNook n = fromNbt((CompoundTag) x); if (!out.contains(n)) out.add(n); } catch (Exception e) { com.claudemod.ClaudeMod.LOGGER.warn("[Claude life] a base nook on file couldn't be read: {} (BaseNook.readAll)", e.toString()); }
        }
        if (mine.contains("nook")) {
            try { BaseNook n = fromNbt(mine.getCompound("nook")); if (!out.contains(n)) { out.add(n); if (mine.contains("nooks")) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] the base nook on file under the old key, {}, wasn't on the list: added (BaseNook.readAll)", n); } } catch (Exception e) { com.claudemod.ClaudeMod.LOGGER.warn("[Claude life] the base nook on file couldn't be read: {} (BaseNook.readAll)", e.toString()); }
        }
        return out;
    }
    @Override public String toString() { return "nook at " + origin.toShortString() + " along " + ahead.getName() + ", into the " + side.getName() + " wall"; }
}
