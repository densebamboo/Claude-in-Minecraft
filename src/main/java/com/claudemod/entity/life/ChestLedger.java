package com.claudemod.entity.life;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import com.claudemod.ClaudeMod;
import com.claudemod.entity.ClaudeEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.Vec3;

/**
 * What's in each of his chests, as of his last look inside. The user: "he should know exactly what's in all his chests (or at
 * least exactly what was in there when he last looked, if someone put something in there or took something out he wouldnt know,
 * or like if it broke and he didnt see it etc)". He asked "does he know he have 7 diamonds": he didn't, the count only read chests
 * loaded and within 48, and he planned a diamond trip by his house with 7 in his nook's chest 190 off.
 *
 * An entry changes only on what he does or sees: he opens the chest (a deposit, a take: it becomes what's in it then, both halves
 * of a double), it's opened in front of him close enough to see in, or he sees the spot with the chest gone. Anything he didn't
 * witness stays as he last saw it. It's read from anywhere: his stored items, the plans that fetch from it, his answers.
 */
public final class ChestLedger {
    private ChestLedger() {}

    /** How far he'll walk to his own chest for something (his nook 190 off for 7 diamonds beats a strip mine). */
    public static final int REACH = 512;
    /** One chest half: its world, what was in it (item, count) and the game time he last looked. */
    public record Entry(String dim, Map<Item, Integer> items, long seen) {}
    /** A chest that holds what he wants: where, the item, how many, how far. */
    public record Spot(BlockPos pos, Item item, int count, int dist) {}

    private static final Map<UUID, Map<BlockPos, Entry>> books = new HashMap<>();
    private static final Map<UUID, Set<BlockPos>> openNow = new HashMap<>(), pending = new HashMap<>();

    public static Map<BlockPos, Entry> book(ClaudeEntity g) { return books.computeIfAbsent(g.getUUID(), k -> new LinkedHashMap<>()); }
    public static void reset(ClaudeEntity g) { books.remove(g.getUUID()); furnaces.remove(g.getUUID()); openNow.remove(g.getUUID()); pending.remove(g.getUUID()); }
    public static boolean knows(ClaudeEntity g, BlockPos p) { return book(g).containsKey(p); }
    public static Entry entry(ClaudeEntity g, BlockPos p) { return book(g).get(p); }
    static String dim(ServerLevel level) { return level.dimension().location().toString(); }

    /** One of his chests: his stations, his storage, his junk chests, his nook's, or one already on his ledger. */
    public static boolean his(ClaudeEntity g, BlockPos p) {
        return g.myStations.contains(p) || g.storageChests.contains(p) || com.claudemod.director.ClaudeLife.junkChests(g).contains(p)
                || com.claudemod.director.ClaudeLife.anyNookChest(g, p) || knows(g, p);
    }

    /** He has it open (or watched it open close by): the entry is what's in it now, both halves of a double chest. Gone: off the ledger. */
    public static void look(ClaudeEntity g, ServerLevel level, BlockPos p) {
        if (!(level.getBlockEntity(p) instanceof Container box) || box instanceof AbstractFurnaceBlockEntity) { forget(g, p, "is gone"); return; }
        put(g, level, p, box);
        BlockState s = level.getBlockState(p);
        if (s.getBlock() instanceof ChestBlock && s.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            BlockPos q = p.relative(ChestBlock.getConnectedDirection(s));
            if (level.getBlockEntity(q) instanceof Container c2) put(g, level, q, c2);
        }
    }

    /** He opened this chest himself: his own goes on the ledger as it is now; anyone else's is none of its business. */
    public static void opened(ClaudeEntity g, ServerLevel level, BlockPos p) { if (his(g, p)) look(g, level, p); }

    private static void put(ClaudeEntity g, ServerLevel level, BlockPos p, Container box) {
        Map<Item, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < box.getContainerSize(); i++) { ItemStack s = box.getItem(i); if (!s.isEmpty()) m.merge(s.getItem(), s.getCount(), Integer::sum); }
        Entry before = book(g).put(p.immutable(), new Entry(dim(level), m, level.getGameTime()));
        if (before == null || !before.items().equals(m)) ClaudeMod.LOGGER.info("[Claude ledger] his chest at {}: {}", p.toShortString(), m.isEmpty() ? "empty" : names(m, 99));
    }

    public static void forget(ClaudeEntity g, BlockPos p, String why) {
        if (book(g).remove(p) != null) ClaudeMod.LOGGER.info("[Claude ledger] his chest at {} {}: off his ledger", p.toShortString(), why);
    }

    // ---------------------------------------------------------------- reading it

    private static boolean here(ClaudeEntity g, ServerLevel level, BlockPos p, Entry e, int reach) {
        return e.dim().equals(dim(level)) && p.distSqr(g.blockPosition()) <= (double) reach * reach;
    }

    /** Everything in his chests by his last look in each, in this world within reach. */
    public static Map<Item, Integer> totals(ClaudeEntity g, ServerLevel level) { return totals(g, level, REACH); }
    public static Map<Item, Integer> totals(ClaudeEntity g, ServerLevel level, int reach) {
        Map<Item, Integer> m = new HashMap<>();
        for (var e : book(g).entrySet()) if (here(g, level, e.getKey(), e.getValue(), reach)) e.getValue().items().forEach((k, v) -> m.merge(k, v, Integer::sum));
        return m;
    }
    public static int count(ClaudeEntity g, ServerLevel level, Item item) { return totals(g, level).getOrDefault(item, 0); }

    /** The chests that hold what he wants, nearest first (one spot per chest and item). */
    public static List<Spot> where(ClaudeEntity g, ServerLevel level, Predicate<ItemStack> want) {
        List<Spot> out = new ArrayList<>();
        for (var e : book(g).entrySet()) {
            if (!here(g, level, e.getKey(), e.getValue(), REACH)) continue;
            int d = com.claudemod.director.ClaudeLife.routeDist(g, g.blockPosition(), e.getKey()); // by his tunnels: a nook through the rock is not near
            for (var it : e.getValue().items().entrySet()) if (it.getValue() > 0 && want.test(new ItemStack(it.getKey()))) out.add(new Spot(e.getKey(), it.getKey(), it.getValue(), d));
        }
        out.sort(java.util.Comparator.comparingInt(Spot::dist));
        return out;
    }

    // ---- his furnaces: what each holds done (output, and what its input cooks into), as he last saw it, from anywhere (live 03:27 11-08: food 6, meals 0, 4 porkchop done 2899 s in one 317 off, 5 steak in another 598 off, and he fished: an unloaded furnace was no food at all)
    private static final Map<UUID, Map<BlockPos, Entry>> furnaces = new HashMap<>();
    public static Map<BlockPos, Entry> furnaceBook(ClaudeEntity g) { return furnaces.computeIfAbsent(g.getUUID(), k -> new LinkedHashMap<>()); }
    /** A furnace of his, loaded: its output, and its input as what it cooks into (it finishes once he's near enough for its chunk to run), on the book; nothing in it, off. */
    public static void furnaceSaw(ClaudeEntity g, ServerLevel level, BlockPos p) {
        if (!(level.getBlockEntity(p) instanceof AbstractFurnaceBlockEntity fe)) { furnaceBook(g).remove(p); return; }
        Map<Item, Integer> m = new LinkedHashMap<>(); ItemStack out = fe.getItem(2), in = fe.getItem(0);
        if (!out.isEmpty()) m.merge(out.getItem(), out.getCount(), Integer::sum);
        if (!in.isEmpty()) { var rec = level.getRecipeManager().getRecipeFor(net.minecraft.world.item.crafting.RecipeType.SMELTING, new net.minecraft.world.item.crafting.SingleRecipeInput(in), level); if (rec.isPresent()) m.merge(rec.get().value().getResultItem(level.registryAccess()).getItem(), in.getCount(), Integer::sum); }
        if (m.isEmpty()) furnaceBook(g).remove(p); else furnaceBook(g).put(p.immutable(), new Entry(level.dimension().location().toString(), m, level.getGameTime()));
    }
    /** His furnaces in this dimension holding what he wants, nearest first: loaded ones read now, the rest as he last saw them. */
    public static List<Spot> furnacesWith(ClaudeEntity g, ServerLevel level, Predicate<ItemStack> want) {
        List<Spot> out = new ArrayList<>(); String d = level.dimension().location().toString();
        for (BlockPos p : g.myStations) if (level.isLoaded(p) && level.getBlockEntity(p) instanceof AbstractFurnaceBlockEntity) furnaceSaw(g, level, p);
        for (var it = furnaceBook(g).entrySet().iterator(); it.hasNext(); ) { var e = it.next(); if (!g.myStations.contains(e.getKey())) { it.remove(); continue; } if (!e.getValue().dim().equals(d)) continue;
            int dist = (int) Math.sqrt(e.getKey().distSqr(g.blockPosition()));
            for (var i : e.getValue().items().entrySet()) if (i.getValue() > 0 && want.test(new ItemStack(i.getKey()))) out.add(new Spot(e.getKey(), i.getKey(), i.getValue(), dist)); }
        out.sort(java.util.Comparator.comparingInt(Spot::dist));
        return out;
    }

    /** "x, y, z (190 blocks from you, looked inside 4m ago): 7 diamond, 12 iron_ingot; ..." nearest first, empty ones left out. */
    public static String describe(ClaudeEntity g, ServerLevel level, int max) {
        List<Map.Entry<BlockPos, Entry>> es = new ArrayList<>();
        for (var e : book(g).entrySet()) if (e.getValue().dim().equals(dim(level)) && !e.getValue().items().isEmpty()) es.add(e);
        es.sort(java.util.Comparator.comparingDouble(e -> e.getKey().distSqr(g.blockPosition())));
        StringBuilder sb = new StringBuilder(); int n = 0;
        for (var e : es) {
            if (n++ >= max) { sb.append("; and ").append(es.size() - max).append(" more"); break; }
            sb.append(sb.length() == 0 ? "" : "; ").append(e.getKey().toShortString()).append(" (").append((int) Math.sqrt(e.getKey().distSqr(g.blockPosition()))).append(" blocks from you, looked inside ")
              .append(ago(level.getGameTime() - e.getValue().seen())).append("): ").append(names(e.getValue().items(), 8));
        }
        return sb.toString();
    }

    /** What a plan takes from his chests, said with where and how far: " (from my chests: 3 diamond at x, y, z, 160 blocks off)". */
    public static String fetchNote(ClaudeEntity g, ServerLevel level, Map<Item, Integer> draw) {
        if (draw == null || draw.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (var d : draw.entrySet()) {
            int left = d.getValue();
            for (Spot sp : where(g, level, s -> s.is(d.getKey()))) {
                if (left <= 0) break;
                int take = Math.min(left, sp.count()); left -= take;
                sb.append(sb.length() == 0 ? "" : ", ").append(take).append(' ').append(name(d.getKey())).append(" at ").append(sp.pos().toShortString()).append(", ").append(sp.dist()).append(" blocks off");
            }
        }
        return sb.length() == 0 ? "" : " (fetching from my chests: " + sb + ")";
    }

    static String name(Item i) { return BuiltInRegistries.ITEM.getKey(i).getPath(); }
    static String names(Map<Item, Integer> m, int max) {
        StringBuilder sb = new StringBuilder(); int k = 0;
        for (var e : m.entrySet()) { if (k++ >= max) { sb.append(", ..."); break; } sb.append(k > 1 ? ", " : "").append(e.getValue()).append(' ').append(name(e.getKey())); }
        return sb.toString();
    }
    static String ago(long ticks) {
        long s = Math.max(0, ticks) / 20;
        return s < 60 ? "just now" : s < 3600 ? (s / 60) + "m ago" : s < 86400 ? (s / 3600) + "h ago" : (s / 86400) + "d ago";
    }

    // ---------------------------------------------------------------- what he sees

    /**
     * Every half second: his chests in sight. The spot in view with no chest: gone, off the ledger. One opened in front of him (by
     * them) close enough to see in: read while it's open, and once more as it shuts. Out of sight, nothing changes.
     */
    public static void tick(ClaudeEntity g) {
        if (g.tickCount % 10 != 7 || !(g.level() instanceof ServerLevel level)) return;
        Set<BlockPos> pend = pending.get(g.getUUID());
        if (pend != null && level.dimension() == net.minecraft.world.level.Level.OVERWORLD) for (Iterator<BlockPos> it = pend.iterator(); it.hasNext();) { // a save from before the ledger: what he'd put in them
            BlockPos p = it.next();
            if (!level.isLoaded(p)) continue;
            if (!knows(g, p) && level.getBlockEntity(p) instanceof ChestBlockEntity) look(g, level, p);
            it.remove();
        }
        Map<BlockPos, Entry> b = book(g); BlockPos me = g.blockPosition(); String d = dim(level);
        Set<BlockPos> cand = new HashSet<>();
        for (var e : b.entrySet()) if (e.getValue().dim().equals(d) && e.getKey().distSqr(me) <= 24 * 24) cand.add(e.getKey());
        for (BlockPos p : g.myStations) if (p.distSqr(me) <= 24 * 24) cand.add(p);
        for (BlockPos p : g.storageChests) if (p.distSqr(me) <= 24 * 24) cand.add(p);
        Set<BlockPos> open = openNow.computeIfAbsent(g.getUUID(), k -> new HashSet<>());
        for (BlockPos p : cand) {
            if (!level.isLoaded(p)) continue;
            boolean chest = level.getBlockEntity(p) instanceof Container c0 && !(c0 instanceof AbstractFurnaceBlockEntity);
            boolean known = b.containsKey(p);
            if (!chest && level.getBlockEntity(p) instanceof AbstractFurnaceBlockEntity && g.myStations.contains(p)) { furnaceSaw(g, level, p); continue; } // a furnace of his: what's done in it, on his furnace book
            if (!chest && !known) continue; // a table of his
            if (!Perception.seesBlock(g, level, p)) continue; // out of sight: as he last saw it
            if (!chest) { forget(g, p, "is gone (he sees the spot)"); open.remove(p); continue; }
            boolean openHere = level.getBlockEntity(p) instanceof ChestBlockEntity && ChestBlockEntity.getOpenCount(level, p) > 0 && Vec3.atCenterOf(p).distanceTo(g.getEyePosition()) <= 8;
            if (openHere) { look(g, level, p); open.add(p.immutable()); }
            else if (open.remove(p)) look(g, level, p);
        }
    }

    // ---------------------------------------------------------------- his save

    public static CompoundTag save(ClaudeEntity g) {
        CompoundTag t = new CompoundTag(); ListTag l = new ListTag();
        for (var e : book(g).entrySet()) {
            CompoundTag c = new CompoundTag();
            c.putLong("p", e.getKey().asLong());
            if (!"minecraft:overworld".equals(e.getValue().dim())) c.putString("d", e.getValue().dim());
            c.putLong("t", e.getValue().seen());
            CompoundTag it = new CompoundTag();
            e.getValue().items().forEach((k, v) -> it.putInt(BuiltInRegistries.ITEM.getKey(k).toString(), v));
            c.put("i", it);
            l.add(c);
        }
        t.put("chests", l);
        ListTag fl = new ListTag(); // his furnaces as he last saw them
        for (var e : furnaceBook(g).entrySet()) { CompoundTag c = new CompoundTag(); c.putLong("p", e.getKey().asLong()); c.putString("d", e.getValue().dim()); c.putLong("t", e.getValue().seen()); CompoundTag it = new CompoundTag(); e.getValue().items().forEach((k, v) -> it.putInt(BuiltInRegistries.ITEM.getKey(k).toString(), v)); c.put("i", it); fl.add(c); }
        t.put("furnaces", fl);
        Set<BlockPos> pend = pending.get(g.getUUID());
        if (pend != null && !pend.isEmpty()) t.putLongArray("pending", pend.stream().mapToLong(BlockPos::asLong).toArray());
        return t;
    }

    public static void load(ClaudeEntity g, CompoundTag t) {
        Map<BlockPos, Entry> b = book(g); b.clear();
        for (Tag x : t.getList("chests", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) x; CompoundTag it = c.getCompound("i");
            Map<Item, Integer> m = new LinkedHashMap<>();
            for (String k : it.getAllKeys()) { ResourceLocation id = ResourceLocation.tryParse(k); Item item = id == null ? Items.AIR : BuiltInRegistries.ITEM.get(id); if (item != Items.AIR) m.put(item, it.getInt(k)); }
            b.put(BlockPos.of(c.getLong("p")), new Entry(c.contains("d") ? c.getString("d") : "minecraft:overworld", m, c.getLong("t")));
        }
        Map<BlockPos, Entry> fb = furnaceBook(g); fb.clear();
        for (Tag x : t.getList("furnaces", Tag.TAG_COMPOUND)) { CompoundTag c = (CompoundTag) x; CompoundTag it = c.getCompound("i"); Map<Item, Integer> m = new LinkedHashMap<>();
            for (String k : it.getAllKeys()) { ResourceLocation id = ResourceLocation.tryParse(k); Item item = id == null ? Items.AIR : BuiltInRegistries.ITEM.get(id); if (item != Items.AIR) m.put(item, it.getInt(k)); }
            fb.put(BlockPos.of(c.getLong("p")), new Entry(c.getString("d"), m, c.getLong("t"))); }
        if (t.contains("pending")) { Set<BlockPos> pend = new HashSet<>(); for (long p : t.getLongArray("pending")) pend.add(BlockPos.of(p)); pending.put(g.getUUID(), pend); }
    }

    /** A save from before the ledger: the chests on his record are read once, the first time each loads (what he put in them, he knew). */
    public static void seedFrom(ClaudeEntity g, Collection<BlockPos> chests) {
        if (chests.isEmpty()) return;
        pending.computeIfAbsent(g.getUUID(), k -> new HashSet<>()).addAll(chests);
    }
}
