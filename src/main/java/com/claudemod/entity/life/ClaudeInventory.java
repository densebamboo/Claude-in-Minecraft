package com.claudemod.entity.life;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Claude's inventory: his real player inventory (the 36 main slots; armor and offhand are worn, not counted),
 * with the small helpers the planner uses: count, find, take, add, and turning logs into planks.
 */
public final class ClaudeInventory {
    private final Inventory inv;
    /** The 36 main slots as a container (hotbar first, then the backpack). */
    public final Container items;

    public ClaudeInventory(Inventory inv) {
        this.inv = inv;
        this.items = new MainSlots(inv);
    }

    /** Adds as much as fits (where a player's pickup would put it); returns what did not fit. */
    public ItemStack add(ItemStack stack) {
        ItemStack rest = stack.copy();
        inv.add(rest);
        return rest;
    }

    /** Room for this in his pack: an empty slot, or a stack of it with space left. */
    public boolean hasRoomFor(ItemStack stack) { return inv.getFreeSlot() >= 0 || inv.getSlotWithRemainingSpace(stack) >= 0; }

    public int count(Predicate<ItemStack> filter) {
        int n = 0;
        for (ItemStack s : inv.items) if (!s.isEmpty() && filter.test(s)) n += s.getCount();
        return n;
    }

    /** Removes up to n matching items; returns how many were removed. */
    public int remove(Predicate<ItemStack> filter, int n) {
        int left = n;
        for (int i = 0; i < inv.items.size() && left > 0; i++) {
            ItemStack s = inv.items.get(i);
            if (s.isEmpty() || !filter.test(s)) continue;
            int take = Math.min(left, s.getCount());
            s.shrink(take);
            left -= take;
        }
        inv.setChanged();
        return n - left;
    }

    /** First stack matching the filter, or empty. Not a copy. */
    public ItemStack find(Predicate<ItemStack> filter) {
        for (ItemStack s : inv.items) if (!s.isEmpty() && filter.test(s)) return s;
        return ItemStack.EMPTY;
    }

    /** A plain full block he can build with (no doors, beds, or other multi-block things). */
    public static boolean isBuildingBlock(ItemStack s) {
        if (!(s.getItem() instanceof BlockItem bi)) return false;
        Block b = bi.getBlock();
        if (b instanceof DoorBlock || b instanceof BedBlock) return false;
        BlockState st = b.defaultBlockState();
        return st.isSolid() && !st.hasBlockEntity();
    }

    /** Turns logs into planks (4 each) until he has at least {@code want} planks or runs out of logs. */
    public void craftPlanks(int want) {
        while (count(s -> s.is(ItemTags.PLANKS)) < want) {
            ItemStack log = find(s -> s.is(ItemTags.LOGS));
            if (log.isEmpty()) return;
            Item planks = planksFor(log.getItem());
            log.shrink(1);
            ItemStack rest = add(new ItemStack(planks, 4));
            if (!rest.isEmpty()) inv.player.drop(rest, false); // full: it falls at his feet, like a player's
        }
    }

    private static Item planksFor(Item log) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(log);
        String path = id.getPath().replace("stripped_", "").replace("_log", "_planks").replace("_wood", "_planks")
                .replace("_stem", "_planks").replace("_hyphae", "_planks");
        Item planks = BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath(id.getNamespace(), path));
        return planks == Items.AIR ? Items.OAK_PLANKS : planks;
    }

    /** Short summary for prompts, e.g. "12 oak planks, 3 cobblestone, 1 torch". */
    public String summary() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ItemStack s : inv.items) {
            if (s.isEmpty()) continue;
            counts.merge(BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().replace('_', ' '), s.getCount(), Integer::sum);
        }
        StringBuilder sb = new StringBuilder();
        counts.forEach((k, v) -> sb.append(sb.length() == 0 ? "" : ", ").append(v).append(' ').append(k));
        // what he's wearing and holding in his off hand: his gear too (left out, the planner thought he had no armor in full iron and planned a chestplate)
        StringBuilder worn = new StringBuilder();
        for (int i = inv.armor.size() - 1; i >= 0; i--) { ItemStack a = inv.armor.get(i); if (!a.isEmpty()) worn.append(worn.length() == 0 ? "" : ", ").append(BuiltInRegistries.ITEM.getKey(a.getItem()).getPath().replace('_', ' ')); }
        for (ItemStack o : inv.offhand) if (!o.isEmpty()) worn.append(worn.length() == 0 ? "" : ", ").append(BuiltInRegistries.ITEM.getKey(o.getItem()).getPath().replace('_', ' ')).append(" (off hand)");
        if (sb.length() == 0 && worn.length() == 0) return "nothing";
        return (sb.length() == 0 ? "nothing in the pack" : sb.toString()) + "; wearing " + (worn.length() == 0 ? "no armor" : worn);
    }

    /** The main 36 slots of a player inventory, as a container. */
    private record MainSlots(Inventory inv) implements Container {
        @Override public int getContainerSize() { return inv.items.size(); }
        @Override public boolean isEmpty() { return inv.items.stream().allMatch(ItemStack::isEmpty); }
        @Override public ItemStack getItem(int slot) { return inv.items.get(slot); }
        @Override public ItemStack removeItem(int slot, int amount) { return inv.removeItem(slot, amount); }
        @Override public ItemStack removeItemNoUpdate(int slot) { return inv.removeItemNoUpdate(slot); }
        @Override public void setItem(int slot, ItemStack stack) { inv.items.set(slot, stack); inv.setChanged(); }
        @Override public void setChanged() { inv.setChanged(); }
        @Override public boolean stillValid(Player player) { return true; }
        @Override public void clearContent() { inv.items.clear(); }
    }
}
