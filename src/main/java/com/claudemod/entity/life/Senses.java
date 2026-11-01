package com.claudemod.entity.life;

import java.util.Map;
import java.util.TreeMap;

import com.claudemod.entity.ClaudeEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

/** What Claude would notice around him if he were a player: resources, stations, mobs, items. */
public final class Senses {
    private Senses() {}

    public static String describe(ClaudeEntity g) {
        ServerLevel level = (ServerLevel) g.level();
        BlockPos o = g.blockPosition();
        Map<String, Integer> seen = new TreeMap<>();
        int r = 20;
        for (BlockPos p : BlockPos.betweenClosed(o.offset(-r, -10, -r), o.offset(r, 10, r))) {
            BlockState s = level.getBlockState(p);
            if (s.isAir()) continue;
            String key = category(s);
            if (key == null) continue;
            if (key.endsWith("ore") || key.equals("stone")) {
                if (!exposed(level, p)) continue; // a player only sees what touches air
            }
            seen.merge(key, 1, Integer::sum);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("within 20 blocks you can see: ");
        if (seen.isEmpty()) sb.append("nothing useful");
        seen.forEach((k, v) -> sb.append(k).append(" x").append(v).append(", "));
        sb.append('\n');

        sb.append(Perception.describe(g)); // what he sees and hears, by direction and distance (never a count through rock)
        sb.append("you: health ").append((int) g.getHealth()).append("/").append((int) g.getMaxHealth())
          .append(", holding ").append(name(g.getItemBySlot(EquipmentSlot.MAINHAND).getItem()))
          .append(", armor ").append(g.getArmorValue())
          .append(level.getBrightness(LightLayer.SKY, o) == 0 ? ", underground" : ", on the surface")
          .append(", y=").append(o.getY()).append(", ").append(level.dimensionType().hasFixedTime() ? "no day or night here" : level.isNight() ? "night" : "day").append('\n');
        sb.append("inventory: ").append(g.inventory.summary()).append('\n');
        // his own stations (chests are extra inventory): where they are and what's in the chests
        StringBuilder st = new StringBuilder();
        for (net.minecraft.core.BlockPos p : g.myStations) {
            if (!level.isLoaded(p)) continue;
            var bs = level.getBlockState(p);
            String kind = bs.is(Blocks.CHEST) ? "chest" : bs.is(Blocks.FURNACE) ? "furnace" : bs.is(Blocks.CRAFTING_TABLE) ? "crafting_table" : null;
            if (kind == null) continue;
            st.append(g.storageChests.contains(p) ? "storage " : "").append(kind).append(" at ").append(p.toShortString()).append(" (").append((int) Math.sqrt(p.distSqr(o))).append(" away)");
            if (kind.equals("chest")) { var le = ChestLedger.entry(g, p); st.append(le == null ? " (not looked inside yet)" : " holding " + (le.items().isEmpty() ? "nothing" : ChestLedger.names(le.items(), 12))); } // as of his last look in it
            st.append("; ");
        }
        if (st.length() > 0) sb.append("your placed stations: ").append(st).append('\n');
        if (g.stairStart != null) sb.append("your mine: entrance ").append(g.stairStart.toShortString()).append(" (").append((int) Math.sqrt(g.stairStart.distSqr(o)))
          .append(" away), working face ").append(g.mineFace == null ? "?" : g.mineFace.toShortString()).append("; you are at ").append(o.toShortString()).append("\n");
        // his storage area: where it is and everything in it (all its chests together), as of his last look in each
        if (!g.storageChests.isEmpty()) {
            java.util.Map<net.minecraft.world.item.Item, Integer> all = new java.util.LinkedHashMap<>();
            int n = 0;
            for (net.minecraft.core.BlockPos p : g.storageChests) { var le = ChestLedger.entry(g, p); if (le == null) continue; n++; le.items().forEach((k, v) -> all.merge(k, v, Integer::sum)); }
            net.minecraft.core.BlockPos first = g.storageChests.get(0);
            sb.append("your storage area: ").append(g.storageChests.size()).append(" chests at ").append(first.toShortString()).append(" (").append((int) Math.sqrt(first.distSqr(o)))
              .append(" away), holding ").append(all.isEmpty() ? (n == 0 ? "nothing you've seen" : "nothing yet") : ChestLedger.names(all, 24)).append('\n');
        }
        { String led = ChestLedger.describe(g, level, 8); if (!led.isEmpty()) sb.append("your chests, as of your last look inside each (fetch from them for a craft; what anyone did since you didn't see): ").append(led).append('\n'); }
        return sb.toString();
    }

    private static String category(BlockState s) {
        if (s.is(BlockTags.LOGS)) return "trees (logs)";
        if (s.is(Blocks.CRAFTING_TABLE)) return "crafting_table";
        if (s.is(Blocks.FURNACE) || s.is(Blocks.BLAST_FURNACE) || s.is(Blocks.SMOKER)) return "furnace";
        if (s.is(Blocks.CHEST) || s.is(Blocks.BARREL)) return "chest";
        if (s.getBlock() instanceof CropBlock c) return c.isMaxAge(s) ? "ripe crops" : "growing crops";
        if (s.is(Blocks.FARMLAND)) return "farmland";
        if (s.is(Blocks.WATER)) return "water";
        if (s.is(BlockTags.BEDS)) return "bed";
        String id = BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
        if (id.endsWith("_ore")) return id.replace("deepslate_", "");
        if (s.is(BlockTags.BASE_STONE_OVERWORLD)) return "stone";
        if (s.is(Blocks.SAND)) return "sand";
        return null;
    }

    private static boolean exposed(ServerLevel level, BlockPos p) {
        for (Direction d : Direction.values()) if (level.getBlockState(p.relative(d)).isAir()) return true;
        return false;
    }

    private static String name(net.minecraft.world.item.Item item) {
        return item == net.minecraft.world.item.Items.AIR ? "nothing" : BuiltInRegistries.ITEM.getKey(item).getPath();
    }
}
