package com.claudemod.context;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.BlastFurnaceMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.DispenserMenu;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.inventory.HopperMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.SmokerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;

/**
 * Diffs container contents between open and close events so Claude can name exactly what
 * was deposited or withdrawn (and the approximate container location).
 *
 * Furnaces (and smokers / blast furnaces) get slot-aware labeling so changes to the input slot
 * become "put X to smelt", changes to the fuel slot become "added X as fuel", and the output slot
 * is ignored (PlayerEvent.ItemSmeltedEvent already covers extraction).
 */
public final class ContainerWatch {
    private sealed interface OpenState {}
    private record StorageState(Map<String, Integer> contents, BlockPos approxPos, String menuLabel) implements OpenState {}
    private record FurnaceState(Map<String, Integer> input, Map<String, Integer> fuel,
                                BlockPos approxPos, String menuLabel) implements OpenState {}

    private static final Map<UUID, OpenState> OPEN = new ConcurrentHashMap<>();

    private ContainerWatch() {}

    /**
     * Returns the label ("furnace", "smoker", "blast furnace") for the furnace menu the player
     * currently has open, or null if they're not in a furnace menu. Used by smelt-event logging
     * so we can pick the right verb (smelted/cooked/blasted).
     */
    public static String currentFurnaceLabelFor(java.util.UUID playerId) {
        OpenState state = OPEN.get(playerId);
        if (state instanceof FurnaceState fs) return fs.menuLabel;
        return null;
    }

    public static void onOpen(PlayerContainerEvent.Open e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        AbstractContainerMenu menu = e.getContainer();

        if (menu instanceof AbstractFurnaceMenu fm) {
            OPEN.put(p.getUUID(), new FurnaceState(
                    snapshotSingleSlot(fm, 0),
                    snapshotSingleSlot(fm, 1),
                    p.blockPosition(),
                    furnaceLabel(fm)));
            return;
        }

        if (isGenericStorageMenu(menu)) {
            OPEN.put(p.getUUID(), new StorageState(
                    snapshotContainerSlots(menu, p),
                    p.blockPosition(),
                    menuLabel(menu)));
        }
    }

    public static void onClose(PlayerContainerEvent.Close e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        OpenState state = OPEN.remove(p.getUUID());
        if (state == null) return;
        AbstractContainerMenu menu = e.getContainer();

        if (state instanceof FurnaceState fs && menu instanceof AbstractFurnaceMenu fm) {
            handleFurnaceClose(p, fs, fm);
        } else if (state instanceof StorageState ss && isGenericStorageMenu(menu)) {
            handleStorageClose(p, ss, menu);
        }
    }

    private static void handleStorageClose(ServerPlayer p, StorageState prev, AbstractContainerMenu menu) {
        Map<String, Integer> after = snapshotContainerSlots(menu, p);
        Map<String, Integer> deposits = new LinkedHashMap<>();
        Map<String, Integer> withdrawals = new LinkedHashMap<>();
        diff(prev.contents, after, deposits, withdrawals);

        if (deposits.isEmpty() && withdrawals.isEmpty()) {
            return; // Nothing changed -- not interesting enough to log
        }

        StringBuilder sb = new StringBuilder();
        if (!deposits.isEmpty()) sb.append("deposited ").append(formatItems(deposits));
        if (!withdrawals.isEmpty()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append("withdrew ").append(formatItems(withdrawals));
        }
        sb.append(" (").append(prev.menuLabel).append(" nearby)");
        log(p, new ActivityEvent.Container(sb.toString(), System.currentTimeMillis()), prev.approxPos);
    }

    private static void handleFurnaceClose(ServerPlayer p, FurnaceState prev, AbstractFurnaceMenu fm) {
        Map<String, Integer> nowInput = snapshotSingleSlot(fm, 0);
        Map<String, Integer> nowFuel = snapshotSingleSlot(fm, 1);

        Map<String, Integer> inputAdded = new LinkedHashMap<>();
        Map<String, Integer> inputRemoved = new LinkedHashMap<>();
        diff(prev.input, nowInput, inputAdded, inputRemoved);

        Map<String, Integer> fuelAdded = new LinkedHashMap<>();
        Map<String, Integer> fuelRemoved = new LinkedHashMap<>();
        diff(prev.fuel, nowFuel, fuelAdded, fuelRemoved);

        boolean anyChange = !inputAdded.isEmpty() || !inputRemoved.isEmpty()
                         || !fuelAdded.isEmpty() || !fuelRemoved.isEmpty();
        if (!anyChange) return;

        StringBuilder sb = new StringBuilder();
        if (!inputAdded.isEmpty()) sb.append("put ").append(formatItems(inputAdded)).append(" in to smelt");
        if (!inputRemoved.isEmpty()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append("removed ").append(formatItems(inputRemoved)).append(" from the smelt slot");
        }
        if (!fuelAdded.isEmpty()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append("added ").append(formatItems(fuelAdded)).append(" as fuel");
        }
        if (!fuelRemoved.isEmpty()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append("removed ").append(formatItems(fuelRemoved)).append(" from the fuel slot");
        }
        sb.append(" (").append(prev.menuLabel).append(" nearby)");
        log(p, new ActivityEvent.Container(sb.toString(), System.currentTimeMillis()), prev.approxPos);
    }

    private static boolean isGenericStorageMenu(AbstractContainerMenu menu) {
        return menu instanceof ChestMenu
            || menu instanceof ShulkerBoxMenu
            || menu instanceof HopperMenu
            || menu instanceof DispenserMenu;
    }

    private static String menuLabel(AbstractContainerMenu menu) {
        if (menu instanceof ChestMenu) return "chest";
        if (menu instanceof ShulkerBoxMenu) return "shulker box";
        if (menu instanceof HopperMenu) return "hopper";
        if (menu instanceof DispenserMenu) return "dispenser/dropper";
        return "container";
    }

    private static String furnaceLabel(AbstractFurnaceMenu menu) {
        if (menu instanceof BlastFurnaceMenu) return "blast furnace";
        if (menu instanceof SmokerMenu) return "smoker";
        if (menu instanceof FurnaceMenu) return "furnace";
        return "furnace";
    }

    private static Map<String, Integer> snapshotContainerSlots(AbstractContainerMenu menu, Player player) {
        Map<String, Integer> counts = new HashMap<>();
        Inventory playerInv = player.getInventory();
        for (Slot slot : menu.slots) {
            if (slot.container == playerInv) continue;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            counts.merge(id, stack.getCount(), Integer::sum);
        }
        return counts;
    }

    private static Map<String, Integer> snapshotSingleSlot(AbstractContainerMenu menu, int slotIndex) {
        Map<String, Integer> counts = new HashMap<>();
        if (slotIndex >= menu.slots.size()) return counts;
        ItemStack stack = menu.slots.get(slotIndex).getItem();
        if (stack.isEmpty()) return counts;
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        counts.put(id, stack.getCount());
        return counts;
    }

    private static void diff(Map<String, Integer> before, Map<String, Integer> after,
                              Map<String, Integer> added, Map<String, Integer> removed) {
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(before.keySet());
        keys.addAll(after.keySet());
        for (String key : keys) {
            int b = before.getOrDefault(key, 0);
            int a = after.getOrDefault(key, 0);
            int delta = a - b;
            if (delta > 0) added.put(key, delta);
            else if (delta < 0) removed.put(key, -delta);
        }
    }

    private static String formatItems(Map<String, Integer> items) {
        return items.entrySet().stream()
                .map(en -> en.getValue() + " " + ActivityEvent.friendly(en.getKey()))
                .collect(Collectors.joining(", "));
    }

    private static void log(ServerPlayer player, ActivityEvent event, BlockPos pos) {
        ServerLevel level = player.serverLevel();
        Holder<Biome> biomeHolder = level.getBiome(pos);
        String biome = biomeHolder.unwrapKey()
                .map(key -> key.location().toString())
                .orElse("unknown");
        String dim = level.dimension().location().toString();
        ActivityEvent seen = com.claudemod.entity.life.Whereabouts.asPerceived(player, new ActivityEvent.Located(event, dim, biome, pos)); // (only what he saw them do, or what's in everyone's chat)
        if (seen != null) PlayerActivityTracker.log(player.getUUID(), seen);
    }
}
