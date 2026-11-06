package com.claudemod.entity.life;

import com.claudemod.ClaudeMod;
import com.claudemod.entity.ClaudeEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stair blocks for his mine's steps (the user 11-06: "replace the step blocks with stair blocks for faster access once the iron
 * and diamond levels are established"; "he is very slow going up and down his steps. if i can outrun him it's a bug"). Each step's
 * riser (the floor block a step up stands on) becomes a stair block, cobblestone above y 0, cobbled deepslate below, so the way up
 * walks without a jump. Priced on Weights "mine.stair*": the swap (craft, break, place per riser) against the climb time it saves
 * over the trips ahead; his planner hears it once his mine is down to diamond level and the swap pays.
 */
public final class MineStairs {
    private MineStairs() {}
    private static double w(String n, double d) { return Weights.w(n, d); }

    /** The block a riser wants: a stair facing the way up, the rock of that depth. */
    public static BlockState want(BlockPos p, Direction up) {
        return (p.getY() < 0 ? Blocks.COBBLED_DEEPSLATE_STAIRS : Blocks.COBBLESTONE_STAIRS).defaultBlockState().setValue(StairBlock.FACING, up);
    }

    /** His mine's risers not yet a stair block (loaded ground only), with the stair each wants, top first. */
    public static Map<BlockPos, BlockState> todo(ClaudeEntity g, ServerLevel level) {
        Map<BlockPos, BlockState> out = new LinkedHashMap<>();
        var b = g.blueprint;
        if (b == null || b.isEmpty() || level.dimension() != net.minecraft.world.level.Level.OVERWORLD) return out;
        for (var e : b.risers().entrySet()) {
            BlockPos p = e.getKey();
            if (!level.isLoaded(p) || level.getBlockState(p).getBlock() instanceof StairBlock) continue;
            if (level.getBlockState(p).getCollisionShape(level, p).isEmpty()) continue; // (no floor there yet: the mine's own patching lays it first)
            out.put(p, want(p, e.getValue()));
        }
        return out;
    }

    /** His mine reaches diamond level (and so iron's on the way down): the user's "once the iron and diamond levels are established". */
    public static boolean established(ClaudeEntity g) {
        var b = g.blueprint; if (b == null || b.isEmpty()) return false;
        int low = Integer.MAX_VALUE; for (var r : b.rows()) if (b.segments.get(r.segment()).kind != MineBlueprint.Kind.BRANCH) low = Math.min(low, r.first().getY());
        return low <= w("mine.stairs_from_y", -50);
    }

    /** The swap priced against keeping the block steps, for n risers. */
    public static Decide.Option choose(ClaudeEntity g, int n, boolean say) {
        double swap = n * (w("mine.stair_swap_s", 2.0) + w("mine.stair_craft_s", 0.5));
        double perTrip = (n / Math.max(1.0, (double) g.blueprint.width)) * (w("mine.step_up_s", 0.53) + w("mine.step_down_s", 0.29) - w("mine.stair_up_s", 0.25) - w("mine.stair_down_s", 0.18)); // (a trip is down and back up; a step is one riser per column)
        double trips = w("mine.trips_ahead", 20), keep = perTrip * trips;
        List<Decide.Option> opts = new ArrayList<>();
        opts.add(Decide.Option.of("stair blocks", swap, n + " risers at " + w("mine.stair_swap_s", 2.0) + " s each + crafting"));
        opts.add(Decide.Option.of("block steps", keep, Math.round(perTrip) + " s lost a trip x " + Math.round(trips) + " trips ahead"));
        return Decide.choose(g, "mine.stairs", n + " block steps in my mine", say, opts);
    }

    /** For his planner: a line once his mine is down to diamond level and the swap pays; "" otherwise. */
    public static String describe(ClaudeEntity g) {
        if (!(g.level() instanceof ServerLevel level) || !established(g)) return "";
        int n = todo(g, level).size(); if (n < 2) return "";
        var pick = choose(g, n, false);
        if (pick == null || !pick.name().equals("stair blocks")) return "";
        return "\nYOUR MINE'S STEPS: " + n + " of them are plain blocks; stair blocks walk up without a jump (the user wants them once the iron and diamond levels are in): stair_steps swaps them, cobblestone stairs above y 0, cobbled deepslate stairs below.\n";
    }

    /** stair_steps: craft what's short, then each riser from the step below: broken, the stair put in its place, top down, a batch at a time. */
    public static String skill(ClaudeEntity g, ServerPlayer player, ServerLevel level, Skills.Step st) {
        if (!com.claudemod.director.ClaudeLife.bpMode(g) || g.blueprint == null || g.blueprint.isEmpty()) return "failed: no mine on my blueprint to put stairs in";
        if (level.dimension() != net.minecraft.world.level.Level.OVERWORLD) return "failed: my mine's in the overworld";
        var todo = todo(g, level);
        if (todo.isEmpty()) return "done: my mine's steps are all stair blocks";
        if (!st.target().startsWith("@")) { var pick = choose(g, todo.size(), true); ClaudeMod.LOGGER.info("[Claude mine] stair blocks for {} risers: priced {} (MineStairs.skill)", todo.size(), pick == null ? "none" : pick.name()); } // (asked: he does it; the price is said, it's the planner's line that waits on it)
        int batch = (int) Math.max(2, w("mine.stairs_batch", 24));
        Map<Item, Integer> want = new LinkedHashMap<>(); int k = 0;
        for (var e : todo.entrySet()) { if (k++ >= batch) break; want.merge(e.getValue().getBlock().asItem(), 1, Integer::sum); }
        for (var e : want.entrySet()) { int have = g.inventory.count(x -> x.is(e.getKey()));
            if (have < e.getValue()) { // short: craft them first (6 blocks make 4), then back to this
                g.skillQueue.addFirst(new Skills.Step("stair_steps", "@", st.count(), st.where()));
                String name = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(e.getKey()).getPath();
                g.skillQueue.addFirst(new Skills.Step("craft", name, e.getValue() - have));
                return "crafting " + (e.getValue() - have) + " " + name + " for my mine's steps first";
            } }
        List<Task> out = new ArrayList<>(); k = 0;
        for (var e : todo.entrySet()) {
            if (k++ >= batch) break;
            BlockPos p = e.getKey(); BlockState s = e.getValue(); Direction up = s.getValue(StairBlock.FACING);
            BlockPos stand = p.relative(up.getOpposite()); // (the step below, its feet level with the riser: the riser is right in front of him)
            out.add(new Task.GoTo(stand, 0.6)); // (on that step, not the one above: from there no click gives the stair its facing)
            out.add(new Task.Break(p));
            out.add(new Task.Place(p, s.getBlock().asItem(), up, s));
            TempLedger.planBuild(g, p, "MineStairs.skill"); // (his mine's own block: it stays)
        }
        g.tasks.addAll(out);
        if (todo.size() > batch) g.skillQueue.addFirst(new Skills.Step("stair_steps", "@", st.count(), st.where()));
        String r = "stair blocks into my mine's steps: " + Math.min(batch, todo.size()) + " of " + todo.size() + " risers this go";
        ClaudeMod.LOGGER.info("[Claude mine] {} (MineStairs.skill)", r);
        g.noteDoing("putting stair blocks in my mine's steps");
        return r;
    }
}
