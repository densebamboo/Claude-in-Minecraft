package com.claudemod.entity.life;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * His mine as one blueprint: the plan and the record in the same object. A list of segments (stairs down, flat runs, width-wide
 * landings that turn, and side branches) from an entrance; from it every cell has a role: WALK (air inside the shape) or shell
 * (FLOOR, WALL, CEILING: solid). The user's rule, verbatim: "Follow the width of the path you started, if you're exposed wall
 * it off, if the ceiling is missing build it, if the floor is missing build it. If you can't continue the staircase down build
 * a straight tunnel until you can." Floor, ceiling and walls get patched; air within the shape doesn't; ore in the shell is mined
 * and refilled; the cave beside it gets a wall, never a seal. The face is the last row: nothing is traced from the world.
 */
public final class MineBlueprint {
    public enum Kind { STAIRS, FLAT, TURN, BRANCH, RUN } // RUN: a second tunnel at an ore's level off the first (tunnel-sized, its own pairs), off the main line like a branch: never the face
    public enum Role { WALK, FLOOR, WALL, CEILING }

    public static final class Segment {
        public Kind kind; public BlockPos start; public Direction dir; public int n; public Direction turnTo; // start: feet of the first row, column 0
        public Segment(Kind kind, BlockPos start, Direction dir, int n, Direction turnTo) { this.kind = kind; this.start = start.immutable(); this.dir = dir; this.n = n; this.turnTo = turnTo; }
    }

    /** One row of the mine: its feet cells (column 0 first), how tall it is, and which segment it belongs to. */
    public record Row(BlockPos[] feet, int height, int segment, int index, Direction dir) {
        public BlockPos first() { return feet[0]; }
    }

    public BlockPos entrance;
    public int width = 2, height = 3, torchEvery = 6, targetY = Integer.MIN_VALUE, branchEvery = 4, branchLength = 16;
    public String torchSide = "right";
    public final List<Segment> segments = new ArrayList<>();
    public final List<BlockPos> dangers = new ArrayList<>(); // where his mine stood open to a cave he died by, or a spawner near it (ClaudeLife.noteMineDeath): no new branch runs at one
    private List<Row> rows; private Map<BlockPos, Role> roles; private Map<BlockPos, Integer> rowOf; // derived from the segments

    public MineBlueprint() {}
    public MineBlueprint(BlockPos entrance, Direction dir, int width, int height) { this.entrance = entrance.immutable(); this.width = Math.max(1, Math.min(5, width)); this.height = Math.max(2, Math.min(4, height)); segments.add(new Segment(Kind.STAIRS, entrance, dir, 0, null)); }

    public boolean isEmpty() { return segments.isEmpty() || rows().isEmpty(); }
    public void invalidate() { rows = null; roles = null; rowOf = null; slotCells = null; slotWalls = null; }

    // ---- geometry -----------------------------------------------------------------------------------------------
    static Direction right(Direction dir) { return dir.getClockWise(); }

    /** The main line's last segment (branches are side segments: they never move the cursor). */
    public Segment lastMain() { for (int i = segments.size() - 1; i >= 0; i--) if (segments.get(i).kind != Kind.BRANCH && segments.get(i).kind != Kind.RUN) return segments.get(i); return null; }

    /** Where the main line ends: the feet cell (column 0) of its last row, and the direction it points; null for an empty blueprint. */
    public BlockPos exit() {
        Segment s = lastMain(); if (s == null) return null;
        return switch (s.kind) {
            case STAIRS -> s.n == 0 ? s.start.relative(s.dir, -1).above() : s.start.relative(s.dir, s.n - 1).below(s.n - 1); // (a fresh, undug stairs segment: the cursor is one step before its start)
            case FLAT -> s.n == 0 ? s.start.relative(s.dir, -1) : s.start.relative(s.dir, s.n - 1);
            case TURN -> s.turnTo == right(s.dir) ? s.start.relative(s.dir, width - 1).relative(right(s.dir), width - 1) : s.start.relative(s.dir, width - 1);
            default -> null;
        };
    }
    public Direction exitDir() { Segment s = lastMain(); if (s == null) return null; return s.kind == Kind.TURN ? s.turnTo : s.dir; }
    /** The feet cell the next row would start at (column 0) for a flat or a stairs continuation. */
    public BlockPos nextStart(boolean down) {
        Segment s = lastMain(); BlockPos e = exit(); Direction d = exitDir();
        if (s.kind == Kind.TURN) { // the landing's outer column, shifted one cell in the new direction, so the new rows are contiguous and never offset
            BlockPos st = s.turnTo == right(s.dir) ? s.start.relative(s.dir, width - 1).relative(right(s.dir), width) : s.start.relative(s.turnTo);
            return down ? st.below() : st;
        }
        return down ? e.relative(d).below() : e.relative(d);
    }
    public BlockPos face() { BlockPos e = exit(); return e == null ? null : e.immutable(); }

    /** All rows in walking order (branches after the main row they leave from). */
    public List<Row> rows() {
        if (rows != null) return rows;
        List<Row> out = new ArrayList<>();
        for (int si = 0; si < segments.size(); si++) {
            Segment s = segments.get(si);
            int h = s.kind == Kind.BRANCH ? Math.min(height, 2) : s.kind == Kind.FLAT || s.kind == Kind.RUN ? Math.min(height, 3) : height, w = s.kind == Kind.BRANCH ? 1 : width; // the user: branches 2 high, the tunnel they come off 3; the stairs and the landing keep their height (headroom stepping down)
            Direction r = right(s.dir);
            if (s.kind == Kind.TURN) {
                for (int i = 0; i < width; i++) { BlockPos[] feet = new BlockPos[width]; for (int j = 0; j < width; j++) feet[j] = s.start.relative(s.dir, i).relative(r, j).immutable(); out.add(new Row(feet, h, si, i, s.dir)); }
                continue;
            }
            for (int k = 0; k < s.n; k++) {
                BlockPos f0 = s.kind == Kind.STAIRS ? s.start.relative(s.dir, k).below(k) : s.start.relative(s.dir, k);
                BlockPos[] feet = new BlockPos[w]; for (int j = 0; j < w; j++) feet[j] = f0.relative(r, j).immutable();
                out.add(new Row(feet, h, si, k, s.dir));
            }
        }
        rows = out; return out;
    }

    /** Every cell's role; cells outside the shape and its shell are absent. */
    public Map<BlockPos, Role> roles() {
        if (roles != null) return roles;
        Map<BlockPos, Role> m = new HashMap<>(); Map<BlockPos, Integer> ro = new HashMap<>();
        List<Row> rs = rows();
        for (int i = 0; i < rs.size(); i++) for (BlockPos f : rs.get(i).feet()) for (int y = 0; y < rs.get(i).height(); y++) { m.put(f.above(y), Role.WALK); ro.putIfAbsent(f.above(y), i); }
        for (Row row : rs) for (BlockPos f : row.feet()) for (int y = 0; y < row.height(); y++) {
            BlockPos p = f.above(y);
            for (Direction d : Direction.values()) {
                BlockPos q = p.relative(d);
                if (m.get(q) == Role.WALK) continue;
                Role want = d == Direction.DOWN ? Role.FLOOR : d == Direction.UP ? Role.CEILING : Role.WALL;
                Role have = m.get(q);
                if (have == null || (want == Role.FLOOR) || (want == Role.CEILING && have == Role.WALL)) m.put(q, want); // floor beats ceiling beats wall (a riser is a floor first)
            }
        }
        if (!rs.isEmpty() && !segments.isEmpty()) { // the doorway: the cells just outside the top step are the way in, never shell (the user broke them out and he kept walling his own entrance shut)
            Row top = rs.get(0); Direction back = segments.get(0).dir.getOpposite();
            for (BlockPos f : top.feet()) for (int y = 0; y < top.height(); y++) m.remove(f.relative(back).above(y));
        }
        roles = m; rowOf = ro; return m;
    }
    public Role roleAt(BlockPos p) { return roles().get(p); }
    public boolean isWalk(BlockPos p) { return roleAt(p) == Role.WALK; }
    public boolean isShell(BlockPos p) { Role r = roleAt(p); return r != null && r != Role.WALK; }
    public boolean touches(BlockPos p) { return roleAt(p) != null; }
    /** The row a walk cell belongs to, or -1. */
    public int rowOf(BlockPos p) { roles(); Integer i = rowOf.get(p); return i == null ? -1 : i; }
    /** The row nearest to him (by his feet cell, else by distance), or -1 when the mine is empty. */
    static boolean torchNear(ServerLevel level, BlockPos cell) { for (BlockPos q : BlockPos.betweenClosed(cell.offset(-2, -1, -2), cell.offset(2, 1, 2))) { BlockState s = level.getBlockState(q); if (s.is(Blocks.WALL_TORCH) || s.is(Blocks.TORCH)) return true; } return false; }
    // ---- torches: the blueprint's own slots ---------------------------------------------------------------------------
    private Map<Integer, BlockPos> slotCells; private Map<BlockPos, Direction> slotWalls; private String slotKey; // derived from the segments (and the spacing and side), like the rows
    /**
     * The torch slots, row index -> the cell its torch goes in (the user, world 14: "his torch placement going down the stairs could be more uniform"). Every torchEvery rows of
     * the main line, counted along the whole line (stairs, landing and flat are one count, so the spacing carries on across a landing; the count per segment restarted at each one);
     * a branch or a second tunnel counts its own rows. One above the row's floor on the torch side (a branch: its right); a landing row whose torch side is the way on gets its end
     * wall. The tunnel his branches come off has none: its torches are the ones over the mouths. Fixed by the plan, never by the world: a slot's missing wall is patched, then its torch.
     */
    public Map<Integer, BlockPos> torchSlots() {
        String key = torchEvery + torchSide;
        if (slotCells != null && key.equals(slotKey)) return slotCells;
        Map<Integer, BlockPos> cells = new HashMap<>(); Map<BlockPos, Direction> walls = new HashMap<>();
        List<Row> rs = rows(); roles();
        Set<Integer> mouthSegs = new java.util.HashSet<>();
        for (Segment s2 : segments) if (s2.kind == Kind.BRANCH) { int ri = rowOf(s2.start.relative(s2.dir.getOpposite())); if (ri >= 0) mouthSegs.add(rs.get(ri).segment()); }
        int mainOrd = 0;
        for (int i = 0; i < rs.size(); i++) {
            Row row = rs.get(i); Segment seg = segments.get(row.segment());
            boolean own = seg.kind == Kind.BRANCH || seg.kind == Kind.RUN;
            int ord = own ? row.index() : mainOrd++;
            if (torchEvery <= 0 || row.height() < 2 || ord % torchEvery != torchEvery / 2) continue;
            if (seg.kind != Kind.BRANCH && mouthSegs.contains(row.segment())) continue; // (only the tunnel the branches come off lights by its mouths)
            boolean rightSide = !"left".equals(torchSide) || seg.kind == Kind.BRANCH;
            BlockPos cell = (rightSide ? row.feet()[row.feet().length - 1] : row.feet()[0]).above(1).immutable();
            Direction wallDir = rightSide ? right(row.dir()) : right(row.dir()).getOpposite();
            if (!isShell(cell.relative(wallDir))) wallDir = row.dir(); // (the landing row a turn to the torch side leaves from: its end wall)
            if (!isWalk(cell) || !isShell(cell.relative(wallDir))) continue;
            cells.put(i, cell); walls.put(cell, wallDir);
        }
        slotCells = cells; slotWalls = walls; slotKey = key; return cells;
    }
    public boolean isTorchSlot(BlockPos cell) { torchSlots(); return slotWalls.containsKey(cell); }
    /** The wall block a slot's torch hangs on. */
    public boolean isSlotWall(BlockPos p) { torchSlots(); for (Map.Entry<BlockPos, Direction> e : slotWalls.entrySet()) if (e.getKey().relative(e.getValue()).equals(p)) return true; return false; }
    /** A torch already in this slot: in its cell, or a block above or below it (where the old eye-level guess put them). Extra torches anywhere else are left as they are. */
    public static boolean slotLit(ServerLevel level, BlockPos cell) { for (int dy = -1; dy <= 1; dy++) { BlockState s = level.getBlockState(cell.above(dy)); if (s.is(Blocks.WALL_TORCH) || s.is(Blocks.TORCH)) return true; } return false; }
    /** The cell on the surface two out from the doorway: where "out of the mine" ends. */
    public BlockPos outside() { return entrance.relative(segments.get(0).dir, -2); }
    /** His mine's way in: the doorway and the cell before it (the outside), a column either side of the door too, from the floor to the
     *  roof's height. Kept clear: no station goes down there, and one of his own standing there is taken up when he goes in. */
    public boolean isWayIn(BlockPos p) {
        if (entrance == null || segments.isEmpty()) return false;
        Direction d = segments.get(0).dir, r = right(d); int ox = p.getX() - entrance.getX(), oz = p.getZ() - entrance.getZ(), dy = p.getY() - entrance.getY();
        int along = ox * d.getStepX() + oz * d.getStepZ(), side = ox * r.getStepX() + oz * r.getStepZ();
        return (along == -1 || along == -2) && side >= -1 && side <= width && dy >= -1 && dy < height && roleAt(p) == null;
    }
    /** The way in's cells (with its floor or without). */
    public List<BlockPos> wayInCells(boolean floor) {
        List<BlockPos> out = new ArrayList<>(); if (entrance == null || segments.isEmpty()) return out;
        Direction d = segments.get(0).dir, r = right(d);
        for (int a = 1; a <= 2; a++) for (int s = -1; s <= width; s++) for (int y = floor ? -1 : 0; y < height; y++) { BlockPos q = entrance.relative(d, -a).relative(r, s).above(y); if (roleAt(q) == null) out.add(q.immutable()); }
        return out;
    }
    public BlockPos rowCell(int i) { List<Row> rs = rows(); return i < 0 || i >= rs.size() ? null : rs.get(i).first(); }
    public int nearestRow(BlockPos feet) {
        int r = rowOf(feet); if (r >= 0) return r;
        List<Row> rs = rows(); if (rs.isEmpty()) return -1;
        double bd = Double.MAX_VALUE; int bi = -1;
        for (int i = 0; i < rs.size(); i++) { if (segments.get(rs.get(i).segment()).kind == Kind.BRANCH) continue; double d = rs.get(i).first().distSqr(feet); if (d < bd) { bd = d; bi = i; } }
        return bi;
    }

    // ---- building ----------------------------------------------------------------------------------------------------
    /** A block he put there (a patch, a station), not rock the mine never cut. */
    static boolean placed(net.minecraft.world.level.block.state.BlockState s) {
        return s.is(net.minecraft.world.level.block.Blocks.COBBLESTONE) || s.is(net.minecraft.world.level.block.Blocks.COBBLED_DEEPSLATE) || s.is(net.minecraft.tags.BlockTags.PLANKS) || s.hasBlockEntity() || s.is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE) || s.getBlock() instanceof net.minecraft.world.level.block.FallingBlock; // (gravel or sand that fell into a dug row is not rock the mine never cut)
    }
    static boolean passable(ServerLevel level, BlockPos p) { return level.getBlockState(p).getCollisionShape(level, p).isEmpty() && level.getFluidState(p).isEmpty(); }
    public static boolean solid(ServerLevel level, BlockPos p) { return !level.getBlockState(p).getCollisionShape(level, p).isEmpty() && level.getFluidState(p).isEmpty(); }
    public static boolean ore(BlockState s) { return s.is(net.neoforged.neoforge.common.Tags.Blocks.ORES); }
    static Item filler(BlockPos p) { return p.getY() < 0 ? Items.COBBLED_DEEPSLATE : Items.COBBLESTONE; }
    /** The arch (his mine's shell above the ground at the entrance) is built of one block only (the user): cobblestone, then deepslate once the mine reaches the diamond level. */
    public boolean isArch(BlockPos q) { return entrance != null && q.getY() >= entrance.getY() && roleAt(q) != null && roleAt(q) != Role.WALK; }
    public Item archBlock() { for (Row r : rows()) if (r.first().getY() <= -50) return Items.COBBLED_DEEPSLATE; return Items.COBBLESTONE; }

    /** Can the stairs go on down from the cursor? The next step needs rock under it (a drop or a cave below means a flat run first). */
    public boolean canStepDown(ServerLevel level) {
        BlockPos s = nextStart(true);
        if (s == null) return false;
        if (solid(level, s.below()) && level.getBlockState(s.below()).isSolid()) return true;
        if (!level.getFluidState(s.below()).isEmpty() || !level.getFluidState(s.below(2)).isEmpty()) return false; // water or lava under the step: level across it
        return solid(level, s.below(2)); // one missing block under the step (a pocket, a loose block): down anyway, the floor patch fills it; a cave two or more deep is what a flat run is for (he went straight at four spots of his stairs to diamonds with rock a block further down)
    }

    /** One more row on the main line: a step down when the rock allows it and the level isn't reached, else a flat cell. Returns the new row's feet. */
    public BlockPos extend(ServerLevel level) {
        Segment s = lastMain();
        boolean atLevel = targetY != Integer.MIN_VALUE && exit() != null && exit().getY() <= targetY;
        boolean down = !atLevel && canStepDown(level);
        BlockPos st = nextStart(down);
        Kind want = down ? Kind.STAIRS : Kind.FLAT;
        if (s.kind == want && s.kind != Kind.TURN) s.n++; // the same kind goes on: one row longer
        else segments.add(new Segment(want, st, exitDir(), 1, null));
        invalidate();
        return st;
    }

    /** A turn: a width x width flat landing at the cursor, then the new direction. */
    public void turn(Direction turnTo) {
        Segment last = lastMain();
        if (last != null && last.kind == Kind.STAIRS && last.n >= 2) { // the landing starts on the last step, level with it (the user: his turn at z 65/64 was a block too far, it should have been 66/65): that step's row becomes the landing's first row
            int k = last.n - 1; BlockPos f0 = last.start.relative(last.dir, k).below(k);
            last.n = k;
            segments.add(new Segment(Kind.TURN, f0, last.dir, width, turnTo));
            invalidate();
            return;
        }
        BlockPos st = nextStart(false);
        segments.add(new Segment(Kind.TURN, st, exitDir(), width, turnTo));
        invalidate();
    }

    /** Side branches off the main row {@code rowIndex}: 1 wide, 2 high, {@code length} long, to each side. */
    public java.util.function.Predicate<BlockPos> keepOut; // cells no branch runs through or along (his base nook's: ClaudeLife.branchPairFor sets it); null: none
    public void branchPair(int rowIndex, int length) {
        Row row = rows().get(rowIndex); Direction d = row.dir(), r = right(d);
        java.util.Set<BlockPos> own = new java.util.HashSet<>(java.util.Arrays.asList(row.feet()));
        for (Direction side : new Direction[] {r, r.getOpposite()}) {
            BlockPos mouth = side == r ? row.feet()[row.feet().length - 1].relative(r) : row.feet()[0].relative(side);
            boolean corner = false; // the user: at a pivot the corner stays solid; no branch whose first cells run beside the turn or another stretch of the tunnel
            for (int k = 0; k < 3 && !corner; k++) { BlockPos q = mouth.relative(side, k);
                if (roleAt(q) == Role.WALK) { corner = true; break; }
                for (Direction h : new Direction[] {d, d.getOpposite()}) { BlockPos w = q.relative(h); if (roleAt(w) == Role.WALK && !own.contains(w) && segments.get(rows().get(Math.max(0, rowOf(w))).segment()).kind != Kind.BRANCH) corner = true; } }
            if (!corner && towardDanger(mouth, side, length)) { corner = true; com.claudemod.ClaudeMod.LOGGER.info("[Claude life] blueprint: no branch {} off row {}: it would run at the cave he died by", side.getName(), rowIndex); }
            if (!corner && keepOut != null) { // never through his base nook, nor along its wall (the user's nook: a branch off row 3 ran through its table and bed niche)
                search: for (int k = 0; k < Math.max(1, length); k++) for (int y = -1; y <= 2; y++) { BlockPos q = mouth.relative(side, k).above(y);
                    for (BlockPos w : new BlockPos[] {q, q.relative(d), q.relative(d.getOpposite())}) if (keepOut.test(w)) { corner = true; com.claudemod.ClaudeMod.LOGGER.info("[Claude life] blueprint: no branch {} off row {}: it would run through his base nook at {} (MineBlueprint.branchPair)", side.getName(), rowIndex, w.toShortString()); break search; } }
            }
            if (!corner) segments.add(new Segment(Kind.BRANCH, mouth, side, length, null));
        }
        invalidate();
    }

    /** A branch from mouth that way, length long, passes within 3 of a cell he died by (a cave opening, a spawner). */
    boolean towardDanger(BlockPos mouth, Direction side, int length) {
        if (dangers.isEmpty()) return false;
        for (int k = 0; k < Math.max(1, length); k++) { BlockPos q = mouth.relative(side, k); for (BlockPos d : dangers) if (d.distSqr(q) <= 3 * 3) return true; }
        return false;
    }

    /** How many branch pairs the main line has, and whether the main tunnel is a branching one (its own torches then sit only over the right-hand mouths). */
    public int branchPairs() { int n = 0; for (Segment s : segments) if (s.kind == Kind.BRANCH) n++; return n / 2; }

    /**
     * The one pass: for the rows in [from, to], the tasks that make the world match the blueprint, in a buildable order:
     * dig every WALK cell that isn't air (water or lava in one: a block into it first, then dug); mine ore in the shell;
     * then place what's missing: floor, walls, ceiling; then the torches on the torch side every {@code torchEvery} rows.
     */
    public BlockPos lavaHit;
    public boolean keepLava; // his goal wants lava (the portal's obsidian): a branch that hits it ends one short, the source left behind the plug (ClaudeLife.objectiveSync) // where a branch last ran into lava (the plug takes that cell; the pool behind it stays, and he knows it's there)
    public List<Task> reconcile(ServerLevel level, int from, int to) {
        boolean cut = false; // a branch that hits water or lava stops there: the source becomes its end wall (plugged by the shell patch below) and he moves on (the user: "plug the flow and move on"; he pushed a branch on into a flooded cave and drowned)
        for (Segment s : segments) {
            if (s.kind != Kind.BRANCH) continue;
            for (int k = 0; k < s.n; k++) { BlockPos f = s.start.relative(s.dir, k); if (level.getFluidState(f).isSource() || level.getFluidState(f.above()).isSource()) { com.claudemod.ClaudeMod.LOGGER.info("[Claude life] branch at {} hit {} at {}: plugging it, the branch ends there", s.start.toShortString(), level.getFluidState(f).isSource() ? level.getFluidState(f).getType() : level.getFluidState(f.above()).getType(), f.toShortString()); boolean lavaIt = level.getFluidState(f).is(net.minecraft.tags.FluidTags.LAVA) || level.getFluidState(f.above()).is(net.minecraft.tags.FluidTags.LAVA); boolean keep = keepLava && lavaIt && k > 0; s.n = keep ? k - 1 : k; cut = true; if (lavaIt) lavaHit = keep ? s.start.relative(s.dir, k - 1).immutable() : (level.getFluidState(f).is(net.minecraft.tags.FluidTags.LAVA) ? f : f.above()).immutable(); if (keep) com.claudemod.ClaudeMod.LOGGER.info("[Claude life] the goal wants lava: the branch ends one short at {}, the source left behind the plug", s.start.relative(s.dir, k - 1).toShortString()); break; } }
        }
        if (cut) invalidate();
        { // a branch of his dug off a tunnel row but missing from the plan (a lost record): taken back in as a branch, never walled (the user: he walled off his iron branches after the record lost them). Only his branch's shape counts: straight, 1 wide, 2+ high, rock walls and a ceiling, 3+ long; a cave mouth doesn't look like that and still gets walled
            List<Row> rs0 = rows(); roles(); boolean adopted = false;
            for (int i = Math.max(0, from); i <= Math.min(rs0.size() - 1, to); i++) {
                Row row = rs0.get(i); if (segments.get(row.segment()).kind != Kind.FLAT) continue;
                Direction r = right(row.dir());
                for (Direction side : new Direction[] {r, r.getOpposite()}) {
                    BlockPos c = (side == r ? row.feet()[row.feet().length - 1] : row.feet()[0]).relative(side);
                    if (roleAt(c) == Role.WALK) continue; // already a branch on the plan
                    int len = 0;
                    for (; len < 32; len++) {
                        BlockPos q = c.relative(side, len);
                        boolean open = (passable(level, q) || placed(level.getBlockState(q))) && (passable(level, q.above()) || placed(level.getBlockState(q.above()))) && solid(level, q.below()) && solid(level, q.above(2)) // (a patch he already put in its mouth counts as open: it comes back out)
                                && solid(level, q.relative(row.dir())) && solid(level, q.relative(row.dir().getOpposite()));
                        if (!open) break;
                    }
                    if (len >= 3) { segments.add(new Segment(Kind.BRANCH, c, side, len, null)); adopted = true; com.claudemod.ClaudeMod.LOGGER.info("[Claude life] blueprint: his branch at {} ({} long) back on the plan", c.toShortString(), len); }
                }
            }
            if (adopted) invalidate();
        }
        List<Row> rs = rows(); roles();
        List<Task> out = new ArrayList<>();
        Set<BlockPos> seen = new LinkedHashSet<>();
        for (int i = Math.max(0, from); i <= Math.min(rs.size() - 1, to); i++) { // row by row: its floor, its digs, then walls and ceiling, so no row is left open behind him and none is stepped onto before it has a floor
            Row row = rs.get(i);
            Segment seg = segments.get(row.segment());
            List<Task> dig = new ArrayList<>(), floor = new ArrayList<>(), wall = new ArrayList<>(), ceil = new ArrayList<>();
            for (BlockPos f : row.feet()) for (int yy = 0; yy < row.height(); yy++) {
                int y = seg.kind == Kind.STAIRS && row.height() > 1 ? (yy + 1) % row.height() : yy; // a step down is dug from his feet level ahead, then up, then the bottom cell last: the bottom first put the floor he stands on "in the way" of his aim and the body dug it out
                BlockPos p = f.above(y);
                if (!passable(level, p) && !com.claudemod.director.ClaudeLife.obsWorking(p)) {
                    if (!level.getFluidState(p).isEmpty()) dig.add(new Task.Place(p, filler(p))); // water or lava where the walk is: a block into it, then that block out
                    dig.add(new Task.Break(p));
                }
                for (Direction d : Direction.values()) {
                    BlockPos q = p.relative(d);
                    Role r = roleAt(q);
                    if (r == null || r == Role.WALK || !seen.add(q)) continue;
                    BlockState qs = level.getBlockState(q);
                    if (solid(level, q)) { if (ore(qs)) { dig.add(new Task.Break(q)); dig.add(new Task.MineVisibleOres(3)); dig.add(new Task.Pickup(100)); } /* (the ore path: the vein behind it and the drops before the patch; 05:01 a bare break, the rest of the lapis left in the wall and the drop with it) */ else if (isArch(q) && !qs.is(((net.minecraft.world.item.BlockItem) archBlock()).getBlock()) && (qs.is(net.minecraft.world.level.block.Blocks.COBBLESTONE) || qs.is(net.minecraft.world.level.block.Blocks.COBBLED_DEEPSLATE) || qs.is(net.minecraft.world.level.block.Blocks.ANDESITE) || qs.is(net.minecraft.world.level.block.Blocks.DIORITE) || qs.is(net.minecraft.world.level.block.Blocks.GRANITE) || qs.is(net.minecraft.world.level.block.Blocks.TUFF) || qs.is(net.minecraft.world.level.block.Blocks.DIRT))) { /* (his own fill only: the hill the arch runs into stays) */ dig.add(new Task.Break(q)); dig.add(new Task.Place(q, archBlock())); } /* one block at a time, out and straight back in, like a player: all the row's Breaks first left him on air outside his arch (04:03) */ continue; } // (an arch block of anything else, dirt or andesite or the old cobble once he is at the diamond level: swapped for the arch block) // ore in the shell: mined now, refilled on the next pass
                    if (com.claudemod.director.ClaudeLife.obsWorking(q)) continue; // (an obsidian run here: the cells he opens on purpose stay open till it's done)
                    Task place = new Task.Place(q, isArch(q) ? archBlock() : filler(q)); // the arch: the rows above ground get the same walls and roof as below (the user: "an extension of his mine on the surface"), floor first, walls bottom-up, the roof on the wall tops; the opening at the top is a walkway's face, never a shell cell, so it stays open
                    (r == Role.FLOOR ? floor : r == Role.CEILING ? ceil : wall).add(place);
                }
            }
            if (i == 0) for (BlockPos f : row.feet()) for (int yy = 0; yy < row.height(); yy++) { BlockPos q = f.relative(segments.get(0).dir.getOpposite()).above(yy); if (solid(level, q) && placed(level.getBlockState(q))) dig.add(new Task.Break(q)); } // the doorway stays open: a block he put there (an old patch walled it) comes out; the ground's own rock is left
            out.addAll(floor); out.addAll(dig); out.addAll(wall); out.addAll(ceil); // the floor first: a flat run across a cave gets its floor placed from the row before, never walked onto open air
            // torches: in the blueprint's slots, exactly there (torchSlots). On this same pass as the row's dig (the slot cell open, or dug above in this list), its wall patched first where it's missing (a cave beside it, ore mined out of it), never moved a row on; bright there or not (the user wants them even: the light check skipped slots by what lit them); a torch already in it, or a block off it, is its torch
            BlockPos slot = torchSlots().get(i);
            if (slot != null) {
                Direction wallDir = slotWalls.get(slot); BlockPos w = slot.relative(wallDir);
                boolean open = level.getBlockState(slot).isAir() || dig.contains(new Task.Break(slot));
                if (open && level.getFluidState(slot).isEmpty() && !slotLit(level, slot) && !com.claudemod.director.ClaudeLife.obsWorking(w)) {
                    if (!solid(level, w) || ore(level.getBlockState(w))) { Task pw = new Task.Place(w, isArch(w) ? archBlock() : filler(w)); if (!out.contains(pw)) out.add(pw); } // (ore: its Break is above in this list, the refill straight after it, then the torch)
                    out.add(new Task.WallTorch(slot, wallDir));
                }
            }
        }
        return out;
    }

    /** The torch over a right-hand branch mouth (every other pair): the main tunnel's cell above the opening, on the wall block over the branch. */
    public List<Task> branchMouthTorches(ServerLevel level) {
        List<Task> out = new ArrayList<>(); int pair = 0;
        for (Segment s : segments) {
            if (s.kind != Kind.BRANCH) continue;
            boolean rightHand = false; // the right-hand branch of its row: its dir is the row's right
            int ri = rowOf(s.start.relative(s.dir.getOpposite()));
            if (ri >= 0) rightHand = right(rows().get(ri).dir()) == s.dir;
            if (!rightHand) continue;
            if (pair++ % 2 != 0) continue;
            BlockPos cell = s.start.relative(s.dir.getOpposite()).above(2); // the main tunnel cell beside the opening, at head+1, on the wall block over the mouth (a 2-high mouth)
            if (level.getBlockState(cell).isAir() && solid(level, s.start.above(2))) { out.add(new Task.WallTorch(cell, s.dir)); continue; }
            Direction along = ri >= 0 ? rows().get(ri).dir() : null; // a 3-high mouth in a 3-high tunnel has no wall over it: the torch goes on the main wall just past the mouth, at head height
            if (along != null) { BlockPos beside = s.start.relative(s.dir.getOpposite()).relative(along).above(1); if (level.getBlockState(beside).isAir() && solid(level, s.start.relative(along).above(1))) out.add(new Task.WallTorch(beside, s.dir)); }
        }
        return out;
    }

    /** Rows actually dug: the leading MAIN rows whose WALK cells are all open (a torch is open). The record never runs ahead of the world. */
    public int dugMainRows(ServerLevel level) {
        int n = 0;
        for (Row r : rows()) {
            if (segments.get(r.segment()).kind == Kind.BRANCH || segments.get(r.segment()).kind == Kind.RUN) continue;
            boolean rock = true; // undug = natural rock at his feet across the row (a flight joined onto rock); a block he put in a dug row (a patch, a chest) or gravel that fell in leaves it his (one stray block in the stairs at 109, -22, 64 cut every row and branch past it off the record, and the branch mouths got walled)
            for (BlockPos f : r.feet()) if (passable(level, f) || !level.getFluidState(f).isEmpty() || placed(level.getBlockState(f))) rock = false;
            if (rock) return n;
            for (BlockPos f : r.feet()) for (BlockPos c : new BlockPos[] {f, f.above()}) { // lava in the row or against it: the mine ends before it (a flow into his tunnel kept the face out past it: walked to, dug beside, two deaths at 220, -58, 64; the "lava ahead" turn takes it on from the last clean row)
                if (level.getFluidState(c).is(net.minecraft.tags.FluidTags.LAVA)) return n;
                for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) if (level.getFluidState(c.relative(d)).is(net.minecraft.tags.FluidTags.LAVA)) return n;
            }
            n++;
        }
        return n;
    }

    /**
     * Drops every main row past the last dug one, and every branch cell past its last dug one: a face projected into rock, a flight
     * joined onto rock, a landing never cut, none of it is his mine. Run on load and before any use of the face. True if something went.
     */
    public boolean trimToDug(ServerLevel level) {
        boolean changed = false;
        // branches stay as planned: a branch partway dug is work in hand, finished by the upkeep when he's by it (trimming it to its dug length every new strip-mine round cut his diamond branch short and the patching walled its end); water or lava ends a branch in reconcile instead
        segments.removeIf(s -> (s.kind == Kind.BRANCH || s.kind == Kind.RUN) && s.n == 0);
        invalidate();
        int dug = dugMainRows(level);
        List<Row> rs = rows(); int mainCount = 0; Row first = null;
        for (Row r : rs) { if (segments.get(r.segment()).kind == Kind.BRANCH || segments.get(r.segment()).kind == Kind.RUN) continue; if (mainCount == dug) { first = r; break; } mainCount++; }
        if (first != null) { // the first undug main row: its segment is cut there and everything after it goes
            int si = first.segment(); Segment s = segments.get(si);
            // (branches recorded after the cut stay: they're dug and his; dropping them turned a dug branch into "air to patch" and he looped placing filler he didn't have)
            if (s.kind == Kind.TURN) { segments.subList(si, segments.size()).removeIf(x -> x.kind != Kind.BRANCH && x.kind != Kind.RUN); } // a half-cut landing is no landing
            else { s.n = first.index(); segments.subList(si + 1, segments.size()).removeIf(x -> x.kind != Kind.BRANCH && x.kind != Kind.RUN); if (s.n == 0 && si > 0) segments.remove(si); }
            changed = true;
            invalidate();
            // a kept branch must still hang off a kept main row: one off a cut row is an orphan out past the face (his face pointed at it, unreachable: a loop)
            java.util.Set<BlockPos> mainFeet = new java.util.HashSet<>();
            for (Row r : rows()) if (segments.get(r.segment()).kind != Kind.BRANCH) mainFeet.addAll(java.util.Arrays.asList(r.feet()));
            segments.removeIf(x -> x.kind == Kind.BRANCH && !mainFeet.contains(x.start.relative(x.dir.getOpposite())));
        }
        invalidate();
        return changed;
    }

    /** The feet cells to walk, from row a to row b inclusive (either way), every third row and the last: a walk to the next cell ends where he stands. */
    public List<BlockPos> walkCells(int a, int b) {
        List<Row> rs = rows(); List<BlockPos> out = new ArrayList<>();
        if (rs.isEmpty()) return out;
        a = Math.max(0, Math.min(rs.size() - 1, a)); b = Math.max(0, Math.min(rs.size() - 1, b));
        List<Integer> route = rowRoute(a, b);
        if (route.isEmpty()) { out.add(rs.get(b).first()); return out; } // (a record that doesn't join up: straight to it, the walker finds the way)
        for (int k = 0; k < route.size(); k++) { // a stop only where the way bends or steps (and every 12 rows on the flat): a leg end every 3 rows was a hitch at every branch mouth (the user)
            Row rk = rs.get(route.get(k)); Kind kk = segments.get(rk.segment()).kind;
            boolean change = k + 1 < route.size() && (rs.get(route.get(k + 1)).segment() != rk.segment() || rs.get(route.get(k + 1)).dir() != rk.dir() || rs.get(route.get(k + 1)).first().getY() != rk.first().getY());
            if (k == 0 || k == route.size() - 1 || change || (kk != Kind.FLAT && kk != Kind.RUN && kk != Kind.BRANCH && k % 3 == 0) || k % 12 == 0) out.add(rk.first());
        }
        return out;
    }

    /**
     * The rows from {@code a} to {@code b} along the mine as it joins in the rock: a row whose feet are beside another row's feet
     * (four ways, one up or down) is one step; breadth first, so the fewest rows. Row numbers say nothing about this: rows added
     * later (a new level's tunnel, a pushed branch) are numbered after everything else. Empty if they don't join.
     */
    public List<Integer> rowRoute(int a, int b) {
        List<Row> rs = rows(); List<Integer> out = new ArrayList<>();
        if (a < 0 || b < 0 || a >= rs.size() || b >= rs.size()) return out;
        Map<BlockPos, Integer> at = new HashMap<>();
        for (int i = 0; i < rs.size(); i++) for (BlockPos f : rs.get(i).feet()) at.putIfAbsent(f, i);
        int[] from = new int[rs.size()]; java.util.Arrays.fill(from, -2); from[a] = -1;
        java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>(); q.add(a);
        while (!q.isEmpty() && from[b] == -2) {
            int r = q.poll();
            for (BlockPos f : rs.get(r).feet()) for (Direction d : Direction.Plane.HORIZONTAL) for (int dy = -1; dy <= 1; dy++) {
                Integer n = at.get(f.relative(d).above(dy));
                if (n == null || n == r || from[n] != -2) continue;
                from[n] = r; q.add(n);
            }
        }
        if (from[b] == -2) return out;
        for (int r = b; r != -1; r = from[r]) out.add(0, r);
        return out;
    }

    // ---- persistence -------------------------------------------------------------------------------------------------
    public CompoundTag toNbt() {
        CompoundTag t = new CompoundTag();
        if (entrance != null) t.putLong("entrance", entrance.asLong());
        t.putInt("width", width); t.putInt("height", height); t.putInt("torchEvery", torchEvery); t.putString("torchSide", torchSide);
        t.putInt("targetY", targetY); t.putInt("branchEvery", branchEvery); t.putInt("branchLength", branchLength);
        ListTag list = new ListTag();
        for (Segment s : segments) {
            CompoundTag c = new CompoundTag();
            c.putString("kind", s.kind.name()); c.putLong("start", s.start.asLong()); c.putString("dir", s.dir.getName()); c.putInt("n", s.n);
            if (s.turnTo != null) c.putString("turnTo", s.turnTo.getName());
            list.add(c);
        }
        t.put("segments", list);
        t.putLongArray("dangers", dangers.stream().mapToLong(BlockPos::asLong).toArray());
        return t;
    }

    public static MineBlueprint fromNbt(CompoundTag t) {
        MineBlueprint b = new MineBlueprint();
        if (t.contains("entrance")) b.entrance = BlockPos.of(t.getLong("entrance"));
        b.width = t.getInt("width"); b.height = t.getInt("height"); b.torchEvery = t.getInt("torchEvery"); b.torchSide = t.getString("torchSide");
        b.targetY = t.getInt("targetY"); b.branchEvery = t.getInt("branchEvery"); b.branchLength = t.getInt("branchLength");
        for (Tag e : t.getList("segments", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) e;
            Direction turnTo = c.contains("turnTo") ? Direction.byName(c.getString("turnTo")) : null;
            b.segments.add(new Segment(Kind.valueOf(c.getString("kind")), BlockPos.of(c.getLong("start")), Direction.byName(c.getString("dir")), c.getInt("n"), turnTo));
        }
        for (long l : t.getLongArray("dangers")) b.dangers.add(BlockPos.of(l));
        return b;
    }

    @Override public String toString() {
        StringBuilder sb = new StringBuilder("mine ").append(width).append("x").append(height).append(" from ").append(entrance == null ? "?" : entrance.toShortString());
        for (Segment s : segments) sb.append(" | ").append(s.kind.name().toLowerCase()).append(' ').append(s.n > 0 ? s.n + " " : "").append(s.dir.getName()).append(s.turnTo != null ? " turn " + s.turnTo.getName() : "");
        return sb.toString();
    }
}
