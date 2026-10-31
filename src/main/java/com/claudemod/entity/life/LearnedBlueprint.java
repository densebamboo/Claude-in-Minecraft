package com.claudemod.entity.life;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A build he learned by watching someone make it (WatchBuild): each block they placed (and each they broke, as "air here") relative
 * to its own corner, with its full state (a stair's facing and half, a door's hinge, a log's axis), in their own placement order, and
 * how he learned it (seen go in, or found on the look round after). It keeps the way it faced (its front: a door's outside, else the
 * side he watched from) and can be turned (Rotation, the states with it). Saved through LearnedStore, so it outlives the world.
 */
public final class LearnedBlueprint {
    /** One cell: where (from the corner), what (air: broken there), the order they did it in, how he knows it. */
    public record Cell(BlockPos at, BlockState state, int order, String how) {}

    public final String name, by;
    public final Direction front;
    public final List<Cell> cells; // in their order

    public LearnedBlueprint(String name, String by, Direction front, List<Cell> cells) {
        this.name = name; this.by = by; this.front = front;
        List<Cell> c = new ArrayList<>(cells); c.sort(java.util.Comparator.comparingInt(Cell::order)); this.cells = List.copyOf(c);
    }

    /** Blocks to put down (not the "air here" cells). */
    public int blocks() { int n = 0; for (Cell c : cells) if (!c.state().isAir()) n++; return n; }
    /** Its size: x, y, z. */
    public int[] size() {
        int mx = 0, my = 0, mz = 0;
        for (Cell c : cells) { mx = Math.max(mx, c.at().getX()); my = Math.max(my, c.at().getY()); mz = Math.max(mz, c.at().getZ()); }
        return new int[] {mx + 1, my + 1, mz + 1};
    }
    /** "24 blocks, 3 wide 4 deep 3 tall, its front to the south". */
    public String summary() {
        int[] s = size(); int air = cells.size() - blocks();
        return blocks() + " blocks" + (air > 0 ? " and " + air + " cleared" : "") + ", " + s[0] + " wide " + s[2] + " deep " + s[1] + " tall, its front to the " + front.getName();
    }

    /** The quarter turns that bring its front round to {@code to}. */
    public Rotation turnTo(Direction to) {
        if (to == null || !to.getAxis().isHorizontal()) return Rotation.NONE;
        Rotation[] r = {Rotation.NONE, Rotation.CLOCKWISE_90, Rotation.CLOCKWISE_180, Rotation.COUNTERCLOCKWISE_90};
        Direction d = front; for (int i = 0; i < 4; i++) { if (d == to) return r[i]; d = d.getClockWise(); }
        return Rotation.NONE;
    }

    /** Turned: the cells moved round its corner (and back to start at 0,0,0), every state turned with them. */
    public List<Cell> turned(Rotation rot) {
        List<Cell> out = new ArrayList<>(); int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        for (Cell c : cells) { BlockPos p = c.at().rotate(rot); minX = Math.min(minX, p.getX()); minZ = Math.min(minZ, p.getZ()); }
        for (Cell c : cells) out.add(new Cell(c.at().rotate(rot).offset(-minX, 0, -minZ), c.state().rotate(rot), c.order(), c.how()));
        return out;
    }

    // ---- JSON (LearnedStore's body)

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("by", by); o.addProperty("front", front.getName());
        JsonArray sz = new JsonArray(); for (int v : size()) sz.add(v); o.add("size", sz);
        o.addProperty("blocks", blocks());
        JsonArray arr = new JsonArray();
        for (Cell c : cells) {
            JsonObject e = new JsonObject();
            JsonArray at = new JsonArray(); at.add(c.at().getX()); at.add(c.at().getY()); at.add(c.at().getZ()); e.add("at", at);
            e.addProperty("state", BlockStateParser.serialize(c.state())); e.addProperty("order", c.order()); e.addProperty("how", c.how());
            arr.add(e);
        }
        o.add("cells", arr);
        return o;
    }

    /** Back from the saved JSON; null if it doesn't read (logged). */
    public static LearnedBlueprint fromJson(JsonObject o) {
        try {
            String name = o.get("name").getAsString(), by = o.has("by") ? o.get("by").getAsString() : "?";
            Direction front = Direction.byName(o.has("front") ? o.get("front").getAsString() : "south"); if (front == null) front = Direction.SOUTH;
            List<Cell> cells = new ArrayList<>();
            for (var el : o.getAsJsonArray("cells")) {
                JsonObject e = el.getAsJsonObject(); JsonArray at = e.getAsJsonArray("at");
                BlockState st;
                try { st = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(), e.get("state").getAsString(), false).blockState(); }
                catch (Exception bad) { com.claudemod.ClaudeMod.LOGGER.info("[Claude learned] blueprint \"{}\": a cell's state \"{}\" doesn't read ({}): left out (LearnedBlueprint.fromJson)", name, e.get("state").getAsString(), bad.getMessage()); continue; }
                cells.add(new Cell(new BlockPos(at.get(0).getAsInt(), at.get(1).getAsInt(), at.get(2).getAsInt()), st, e.get("order").getAsInt(), e.has("how") ? e.get("how").getAsString() : "seen"));
            }
            return new LearnedBlueprint(name, by, front, cells);
        } catch (RuntimeException ex) {
            com.claudemod.ClaudeMod.LOGGER.warn("[Claude learned] a blueprint file doesn't read: {} (LearnedBlueprint.fromJson)", ex.toString());
            return null;
        }
    }

    public static BlockState air() { return Blocks.AIR.defaultBlockState(); }
}
