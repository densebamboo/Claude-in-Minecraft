package com.claudemod.entity.life;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.claudemod.ClaudeMod;
import com.claudemod.director.ClaudeLife;
import com.claudemod.entity.ClaudeEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.phys.AABB;

/**
 * The game's timers, read exactly: an AI tracks what a player could only estimate. The day clock (the sun, a clock item), his own
 * dropped things (he knows when he dropped them), his own furnaces (he loaded them). Nothing he couldn't know: no strangers' piles,
 * no other players' furnaces, no weather forecast.
 */
public final class Clocks {
    private Clocks() {}

    public static final double SPRINT = 5.6; // blocks a second, sprinting on flat ground
    public static final int MARGIN = 5; // seconds: a run that needs the clock's last 5 s is lost (the start, the pickup, a pile spread about)

    /** His pile as last seen: where, how many stacks, ticks left on its clock, whether it was aging (its chunk entity-ticking). */
    public record Pile(BlockPos at, int stacks, int left, boolean ticking) {}
    private static Map<UUID, Pile> piles; // (static, lazily made: a field added live starts null)
    private static Map<UUID, Pile> piles() { if (piles == null) piles = new HashMap<>(); return piles; }

    /** "45s", "2m05s". */
    public static String fmt(int ticks) {
        int s = Math.max(0, ticks) / 20;
        return s < 60 ? s + "s" : s / 60 + "m" + String.format("%02d", s % 60) + "s";
    }

    /** Ticks left before an item entity despawns (items last 6000 ticks, aging only while their chunk ticks). */
    public static int left(ItemEntity i) { return i.lifespan - i.getAge(); }

    /**
     * His own pile right now: read off the item entities when they're there to read, else the last reading (frozen: items in a
     * chunk that isn't ticking don't age). A pile with 0 stacks is gone (its chunk is ticking and none of it is left). Null: none.
     */
    public static Pile pile(ClaudeEntity g, ServerLevel level) {
        Pile last = piles().get(g.getUUID());
        List<ItemEntity> own = new ArrayList<>(ClaudeLife.ownDropsNear(g, level, 160));
        if (own.isEmpty() && last != null && level.isLoaded(last.at()))
            own = level.getEntitiesOfClass(ItemEntity.class, new AABB(last.at()).inflate(16), i -> i.isAlive() && ClaudeLife.ownDrop(g, i));
        if (!own.isEmpty()) {
            ItemEntity far = own.get(0); for (ItemEntity i : own) if (left(i) > left(far)) far = i; // the pile lasts as long as its youngest stack
            Pile p = new Pile(far.blockPosition().immutable(), own.size(), left(far), level.isPositionEntityTicking(far.blockPosition()));
            piles().put(g.getUUID(), p);
            return p;
        }
        if (last == null) return null;
        if (level.isPositionEntityTicking(last.at()) || last.left() <= 0) { piles().remove(g.getUUID()); return new Pile(last.at(), 0, 0, true); } // there to see, and nothing of it left
        return new Pile(last.at(), last.stacks(), last.left(), false); // ponytail: frozen at the last reading (the ticks it aged between that reading and its chunk unloading aren't counted)
    }

    /** Seconds a run from a to b takes at a sprint. ponytail: a line estimate (1.25x the flat distance plus the climb), not the A* route's length. */
    public static double eta(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return (Math.sqrt(dx * dx + dz * dz) * 1.25 + Math.abs(a.getY() - b.getY())) / SPRINT;
    }

    /** A pile of his he can get to before it despawns (the idle pickup and the hole's break-out read this, as recover_drops does). */
    public static boolean canMake(ClaudeEntity g, ItemEntity i) { return left(i) / 20.0 >= eta(g.blockPosition(), i.blockPosition()) + MARGIN; }

    /**
     * The drop race, for recover_drops: null when he can make it (or the clock is frozen till he's near), else the step's "done:"
     * line, having said plainly in chat that it'll be gone.
     */
    public static String race(ClaudeEntity g, ServerPlayer player, ServerLevel level, BlockPos spot) {
        Pile p = pile(g, level);
        if (p == null || p.stacks() == 0) return null; // no clock of his on it: recover_drops' own look decides
        double eta = eta(g.blockPosition(), spot);
        double runOnClock = p.ticking() ? eta : Math.min(eta, 128 * 1.25 / SPRINT); // ponytail: a frozen pile starts aging when its chunk ticks again, taken as the last 128 blocks of the run
        if (p.left() / 20.0 >= runOnClock + MARGIN) return null;
        ClaudeMod.LOGGER.info("[Claude life] his drops at {}: {} left on them, the run there ~{}s: gone before he gets there", spot.toShortString(), fmt(p.left()), (int) eta);
        com.claudemod.director.ClaudeChat.say(player, "my stuff despawns in " + fmt(p.left()) + " and it's a ~" + (int) Math.ceil(eta) + "s run, it'll be gone before i get there", "drop race");
        return "done: my things at " + spot.toShortString() + " despawn in " + fmt(p.left()) + " and the run there is ~" + (int) Math.ceil(eta) + "s: gone before I get there, leaving them";
    }

    /**
     * They told him his things despawned. His clock says: gone (or within MARGIN of it), he agrees and stops going for them; time
     * left on it, he says how much. Null when he has no pile on his clock (the model answers that one).
     */
    public static String heardDespawn(ClaudeEntity g, ServerPlayer player, String raw) {
        String low = raw.toLowerCase().trim();
        if (low.length() > 80 || !low.matches(".*\\b(despawn\\w*|de-spawn\\w*|disappeared|vanished)\\b.*")
                && !low.matches(".*\\b(stuff|things|items|drops|gear|loot|pile)\\b.*\\b(gone|lost)\\b.*")) return null;
        if (!(g.level() instanceof ServerLevel level)) return null;
        Pile p = pile(g, level);
        boolean going = g.lastStep != null && g.lastStep.skill().equals("recover_drops") || g.skillQueue.stream().anyMatch(s -> s.skill().equals("recover_drops"));
        if (p == null && !going) return null;
        String line;
        if (p == null || p.stacks() == 0 || p.left() < MARGIN * 20) {
            line = "yeah, it's gone. oh well";
            g.skillQueue.removeIf(s -> s.skill().equals("recover_drops"));
            if (g.lastStep != null && g.lastStep.skill().equals("recover_drops")) { ClaudeLife.clearTasks(g); ClaudeLife.dropCurrent(g); g.lastStep = null; g.nextPlanTick = g.tickCount; }
            if (g.playerGoal != null && g.playerGoal.toLowerCase().matches("(?s).*(recover|gear back|drops|death spot|old gear).*")) g.playerGoal = "";
            piles().remove(g.getUUID());
        } else if (!p.ticking()) line = "should still be there, its chunk isn't loaded so its timer's paused at " + fmt(p.left());
        else line = "should still be there, my clock has " + fmt(p.left()) + " left on it" + (going ? ", still going for it" : "");
        ClaudeMod.LOGGER.info("[Claude life] told his drops despawned (\"{}\"): his clock says {}", raw, p == null ? "no pile" : p.stacks() == 0 ? "gone" : fmt(p.left()) + " left" + (p.ticking() ? "" : ", frozen"));
        com.claudemod.director.ClaudeChat.say(player, line, "despawn claim");
        return line;
    }

    /** The CLOCKS block for his prompts: short, exact. */
    public static String describe(ClaudeEntity g, ServerLevel level) {
        StringBuilder sb = new StringBuilder("CLOCKS (exact, read from the game: quote these, never guess a timer): ");
        int t = (int) (level.getDayTime() % 24000);
        boolean rain = level.isRaining(), thunder = level.isThundering();
        int bedsFrom = rain ? 12010 : 12542, mobsFrom = rain ? 12969 : 13188, mobsTo = rain ? 23031 : 22812; // (the vanilla sky-light thresholds)
        sb.append("day ").append(level.getDayTime() / 24000 + 1).append(", time ").append(t).append(": ");
        if (thunder) sb.append("thunderstorm, mobs spawn and beds work even by day");
        else if (t < bedsFrom) sb.append("day; beds work in ").append(fmt(bedsFrom - t)).append(", surface mobs spawn in ").append(fmt(mobsFrom - t));
        else if (t < mobsFrom) sb.append("dusk, beds work now; surface mobs spawn in ").append(fmt(mobsFrom - t));
        else if (t < mobsTo) sb.append("night, surface mobs spawning; that stops in ").append(fmt(mobsTo - t)).append(", daylight (mobs burn) in ").append(fmt(24000 - t));
        else sb.append("dawn, no new surface mobs; daylight (mobs burn) in ").append(fmt(24000 - t));
        String[] moon = {"full", "waning gibbous", "last quarter", "waning crescent", "new", "waxing crescent", "first quarter", "waxing gibbous"};
        sb.append("; moon ").append(moon[level.getMoonPhase() % 8]).append(thunder ? "" : rain ? "; raining" : "; clear").append(".\n");
        Pile p = pile(g, level);
        if (p != null && p.stacks() > 0) sb.append("  your dropped things: ").append(p.stacks()).append(" stack(s) at ").append(p.at().toShortString())
                .append(p.ticking() ? ", despawn in " + fmt(p.left()) : ", frozen while unloaded with " + fmt(p.left()) + " left")
                .append(" (dropped items last 5 min, aging only while their chunk is loaded); a sprint there ~").append((int) Math.ceil(eta(g.blockPosition(), p.at()))).append("s.\n");
        int n = 0;
        for (BlockPos f : g.myStations) {
            if (n >= 4 || !level.isLoaded(f) || !(level.getBlockEntity(f) instanceof AbstractFurnaceBlockEntity fb)) continue;
            String one = furnace(fb, level); if (one == null) continue;
            n++; sb.append("  your ").append(BuiltInRegistries.BLOCK.getKey(level.getBlockState(f).getBlock()).getPath()).append(" at ").append(f.toShortString()).append(": ").append(one).append(".\n");
        }
        int awake = g.getStats().getValue(net.minecraft.stats.Stats.CUSTOM.get(net.minecraft.stats.Stats.TIME_SINCE_REST));
        sb.append(DeathLog.describe(g, level)); // (planner and chat both read this block)
        if (awake >= 24000) sb.append("  awake ").append(String.format("%.1f", awake / 24000.0)).append(" days since you last slept (phantoms come from 3).\n"); // ponytail: crops/saplings growth not on the clocks yet
        return sb.toString();
    }

    /** One furnace's clock: what's in, the next item and the last, the fuel. Null when it's empty. */
    static String furnace(AbstractFurnaceBlockEntity fb, ServerLevel level) {
        ItemStack in = fb.getItem(0), fuel = fb.getItem(1), out = fb.getItem(2);
        if (in.isEmpty() && out.isEmpty()) return null;
        CompoundTag tag = fb.saveWithoutMetadata(level.registryAccess());
        int lit = tag.getShort("BurnTime"), progress = tag.getShort("CookTime"), total = Math.max(1, tag.getShort("CookTimeTotal"));
        StringBuilder sb = new StringBuilder();
        if (!in.isEmpty()) {
            int burn = lit + (fuel.isEmpty() ? 0 : fuel.getCount() * fuel.getBurnTime(RecipeType.SMELTING)); // ticks of fire left: the burning item plus the fuel slot
            int next = total - progress, all = next + (in.getCount() - 1) * total;
            sb.append(in.getCount()).append(' ').append(name(in)).append(" in");
            if (burn <= 0) sb.append(", no fuel: stopped");
            else if (burn >= all) sb.append(", next in ").append(fmt(next)).append(", all done in ").append(fmt(all)).append(" (").append(total / 20).append("s an item)");
            else { int k = burn >= next ? Math.min(in.getCount(), 1 + (burn - next) / total) : 0; sb.append(", next in ").append(burn >= next ? fmt(next) : "never").append("; fuel runs out in ").append(fmt(burn)).append(" after ").append(k).append(" more"); }
        }
        if (!out.isEmpty()) sb.append(sb.length() == 0 ? "" : "; ").append(out.getCount()).append(' ').append(name(out)).append(" ready");
        return sb.toString();
    }

    private static String name(ItemStack s) { return BuiltInRegistries.ITEM.getKey(s.getItem()).getPath(); }
}
