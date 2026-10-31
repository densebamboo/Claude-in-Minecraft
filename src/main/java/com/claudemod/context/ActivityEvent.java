package com.claudemod.context;

import java.util.Set;

import net.minecraft.core.BlockPos;

/**
 * One thing the player did. Typed records allow consecutive same-type events to be merged
 * (e.g. 8 oak logs chopped becomes one line), and each event reports whether it's noteworthy
 * (intentional action) or trivial (terrain noise) so the persona can prioritize.
 */
public sealed interface ActivityEvent {
    long timestamp();
    String describe();

    /** Returns a key identifying mergeable events. null means this event never merges. */
    default String mergeKey() { return null; }

    /** Maximum time gap (ms) between events for merging to apply. */
    default long mergeWindowMs() { return 0L; }

    /** Combines this event with a newer event of the same mergeKey into one. */
    default ActivityEvent mergeWith(ActivityEvent newer) { return newer; }

    /**
     * True if this event represents an intentional action (crafts, smelts, chests, ores, logs,
     * advancements, damage, chat). False for terrain noise the player breaks incidentally
     * while traveling (dirt, snow, stone, grass, etc).
     */
    default boolean noteworthy() { return true; }

    /** Strips "minecraft:" namespace and turns underscores into spaces. "minecraft:acacia_log" -> "acacia log". */
    static String friendly(String id) {
        if (id == null) return "";
        int colon = id.indexOf(':');
        String name = colon >= 0 ? id.substring(colon + 1) : id;
        return name.replace('_', ' ');
    }

    static String atmosphericPlace(String biomeId) {
        String biome = friendly(biomeId);
        if (biome.contains("forest") || biome.contains("taiga") || biome.contains("jungle")
                || biome.contains("grove") || biome.contains("wood") || biome.contains("cherry")) {
            return "around trees";
        }
        if (biome.contains("beach") || biome.contains("river") || biome.contains("ocean")
                || biome.contains("swamp")) {
            return "near water";
        }
        if (biome.contains("peak") || biome.contains("slope") || biome.contains("mountain")
                || biome.contains("meadow")) {
            return "on high ground";
        }
        return "on the surface";
    }

    static public boolean isCropLike(String id) {
        return id != null && (
                id.contains("wheat")
                || id.contains("carrot")
                || id.contains("potato")
                || id.contains("beetroot")
                || id.contains("nether_wart")
                || id.contains("cocoa")
                || id.contains("melon")
                || id.contains("pumpkin")
        );
    }

    record Located(ActivityEvent event, String dimension, String biomeId, BlockPos pos) implements ActivityEvent {
        @Override public long timestamp() { return event.timestamp(); }
        @Override public String describe() {
            // No raw XYZ -- the 8b model parrots coordinates back verbatim.
            // Use meaningful location language. Raw surface biome labels make replies sound like a log.
            String loc = locationPhrase();
            return event.describe() + " [" + loc + "]";
        }

        private String locationPhrase() {
            String dim = friendly(dimension);
            if (dim.contains("nether")) return "in the nether";
            if (dim.contains("end")) return "in the end";
            if (pos.getY() < 45) return "deep underground, y=" + pos.getY();
            if (pos.getY() < 62) return "underground, y=" + pos.getY();
            return atmosphericPlace(biomeId);
        }
        @Override public String mergeKey() { return event.mergeKey(); }
        @Override public long mergeWindowMs() { return event.mergeWindowMs(); }
        @Override public ActivityEvent mergeWith(ActivityEvent newer) {
            ActivityEvent innerNewer = newer instanceof Located located ? located.event : newer;
            return new Located(event.mergeWith(innerNewer), dimension, biomeId, pos);
        }
        @Override public boolean noteworthy() { return event.noteworthy(); }
    }

    /** Blocks the player breaks incidentally while traveling -- not the point of what they're doing. */
    Set<String> FILLER_BLOCKS = Set.of(
        // Dirt family
        "minecraft:dirt", "minecraft:coarse_dirt", "minecraft:rooted_dirt", "minecraft:mud",
        "minecraft:grass_block", "minecraft:podzol", "minecraft:mycelium",
        // Grass / ferns / flowers
        "minecraft:short_grass", "minecraft:tall_grass", "minecraft:fern", "minecraft:large_fern",
        "minecraft:dead_bush", "minecraft:dandelion", "minecraft:poppy", "minecraft:blue_orchid",
        "minecraft:allium", "minecraft:azure_bluet", "minecraft:red_tulip", "minecraft:orange_tulip",
        "minecraft:white_tulip", "minecraft:pink_tulip", "minecraft:oxeye_daisy", "minecraft:cornflower",
        "minecraft:lily_of_the_valley", "minecraft:torchflower", "minecraft:pink_petals",
        "minecraft:sunflower", "minecraft:lilac", "minecraft:rose_bush", "minecraft:peony",
        // Snow / ice
        "minecraft:snow", "minecraft:snow_block", "minecraft:powder_snow",
        // Sand / gravel
        "minecraft:sand", "minecraft:red_sand", "minecraft:gravel",
        // Common stone
        "minecraft:stone", "minecraft:cobblestone", "minecraft:deepslate", "minecraft:cobbled_deepslate",
        "minecraft:tuff", "minecraft:granite", "minecraft:diorite", "minecraft:andesite",
        "minecraft:netherrack", "minecraft:end_stone",
        // Leaves
        "minecraft:oak_leaves", "minecraft:birch_leaves", "minecraft:spruce_leaves",
        "minecraft:dark_oak_leaves", "minecraft:jungle_leaves", "minecraft:acacia_leaves",
        "minecraft:cherry_leaves", "minecraft:mangrove_leaves",
        "minecraft:azalea_leaves", "minecraft:flowering_azalea_leaves",
        // Misc plants / undergrowth
        "minecraft:vine", "minecraft:glow_lichen", "minecraft:hanging_roots",
        "minecraft:moss_block", "minecraft:moss_carpet",
        "minecraft:seagrass", "minecraft:tall_seagrass", "minecraft:kelp", "minecraft:kelp_plant",
        "minecraft:lily_pad", "minecraft:big_dripleaf", "minecraft:small_dripleaf",
        "minecraft:azalea", "minecraft:flowering_azalea", "minecraft:bamboo", "minecraft:cactus",
        "minecraft:sugar_cane"
    );

    record Mined(String blockId, BlockPos firstPos, int count, long timestamp) implements ActivityEvent {
        public Mined(String blockId, BlockPos pos, long timestamp) {
            this(blockId, pos, 1, timestamp);
        }
        @Override public String mergeKey() { return "mined:" + blockId; }
        @Override public long mergeWindowMs() { return 30_000L; }
        @Override public ActivityEvent mergeWith(ActivityEvent newer) {
            if (!(newer instanceof Mined m)) return newer;
            return new Mined(blockId, firstPos, count + m.count, newer.timestamp());
        }
        @Override public String describe() {
            if (isCropLike(blockId)) {
                if (count == 1) return "harvested " + friendly(blockId);
                return "harvested " + count + " " + friendly(blockId);
            }
            if (count == 1) return "mined " + friendly(blockId);
            return "mined " + count + " " + friendly(blockId);
        }
        @Override public boolean noteworthy() {
            return !FILLER_BLOCKS.contains(blockId);
        }
    }

    record Placed(String blockId, BlockPos firstPos, int count, long timestamp) implements ActivityEvent {
        public Placed(String blockId, BlockPos pos, long timestamp) {
            this(blockId, pos, 1, timestamp);
        }
        @Override public String mergeKey() { return "placed:" + blockId; }
        @Override public long mergeWindowMs() { return 30_000L; }
        @Override public ActivityEvent mergeWith(ActivityEvent newer) {
            if (!(newer instanceof Placed p)) return newer;
            return new Placed(blockId, firstPos, count + p.count, newer.timestamp());
        }
        @Override public String describe() {
            if (isCropLike(blockId)) {
                if (count == 1) return "planted " + friendly(blockId);
                return "planted " + count + " " + friendly(blockId);
            }
            if (count == 1) return "placed " + friendly(blockId);
            return "placed " + count + " " + friendly(blockId);
        }
    }

    record Crafted(String itemId, int count, long timestamp) implements ActivityEvent {
        @Override public String mergeKey() { return "crafted:" + itemId; }
        @Override public long mergeWindowMs() { return 60_000L; }
        @Override public ActivityEvent mergeWith(ActivityEvent newer) {
            if (!(newer instanceof Crafted c)) return newer;
            return new Crafted(itemId, count + c.count, newer.timestamp());
        }
        @Override public String describe() { return "crafted " + count + " " + friendly(itemId); }
    }

    record Smelted(String itemId, int count, String device, long timestamp) implements ActivityEvent {
        @Override public String mergeKey() { return "smelted:" + itemId + ":" + device; }
        @Override public long mergeWindowMs() { return 120_000L; }
        @Override public ActivityEvent mergeWith(ActivityEvent newer) {
            if (!(newer instanceof Smelted s)) return newer;
            return new Smelted(itemId, count + s.count, device, newer.timestamp());
        }
        @Override public String describe() {
            String verb = switch (device) {
                case "smoker" -> "cooked";
                case "blast furnace" -> "blasted";
                default -> "smelted";
            };
            return verb + " " + count + " " + friendly(itemId) + " (in a " + device + ")";
        }
    }

    record Chat(String message, long timestamp) implements ActivityEvent {
        @Override public String describe() { return "said in chat: \"" + message + "\""; }
    }

    record Container(String detail, long timestamp) implements ActivityEvent {
        @Override public String describe() { return detail; }
    }

    record Advancement(String advId, long timestamp) implements ActivityEvent {
        @Override public String describe() { return "earned advancement: " + friendly(advId); }
    }

    record Damage(int amount, String source, long timestamp) implements ActivityEvent {
        @Override public String describe() { return "took " + amount + " damage from " + friendly(source); }
    }

    record Died(String cause, long timestamp) implements ActivityEvent {
        @Override public String describe() { return "died (cause: " + friendly(cause) + ")"; }
    }

    record Ate(String itemId, int count, long timestamp) implements ActivityEvent {
        @Override public String mergeKey() { return "ate:" + itemId; }
        @Override public long mergeWindowMs() { return 30_000L; }
        @Override public ActivityEvent mergeWith(ActivityEvent newer) {
            if (!(newer instanceof Ate a)) return newer;
            return new Ate(itemId, count + a.count, newer.timestamp());
        }
        @Override public String describe() { return "ate " + count + " " + friendly(itemId); }
    }

    record Fished(String items, long timestamp) implements ActivityEvent {
        @Override public String describe() { return "caught while fishing: " + items; }
    }

    record SetSpawn(BlockPos pos, String spawnLevel, boolean forced, long timestamp) implements ActivityEvent {
        @Override public String describe() {
            String how = forced ? "set forced spawn" : "set spawn";
            return how + " in " + friendly(spawnLevel);
        }
    }

    record SleepAttempt(BlockPos pos, String result, long timestamp) implements ActivityEvent {
        @Override public String mergeKey() { return "sleep:" + result; }
        @Override public long mergeWindowMs() { return 60_000L; }
        @Override public ActivityEvent mergeWith(ActivityEvent newer) { return newer; }
        @Override public String describe() {
            return "tried to sleep (result: " + result + ")";
        }
    }

    record Attacked(String entityId, String weaponId, int count, long timestamp) implements ActivityEvent {
        @Override public String mergeKey() { return "attacked:" + entityId + ":" + weaponId; }
        @Override public long mergeWindowMs() { return 20_000L; }
        @Override public ActivityEvent mergeWith(ActivityEvent newer) {
            if (!(newer instanceof Attacked a)) return newer;
            return new Attacked(entityId, weaponId, count + a.count, newer.timestamp());
        }
        @Override public String describe() {
            String weapon = weaponId == null || weaponId.isBlank() ? "bare hands" : friendly(weaponId);
            if (count == 1) return "attacked " + friendly(entityId) + " with " + weapon;
            return "attacked " + friendly(entityId) + " " + count + " times with " + weapon;
        }
    }

    record Killed(String entityId, String source, long timestamp) implements ActivityEvent {
        @Override public String describe() { return "killed " + friendly(entityId) + " (source: " + friendly(source) + ")"; }
    }

    record Traded(String villagerId, String cost, String result, long timestamp) implements ActivityEvent {
        @Override public String describe() {
            return "traded with " + friendly(villagerId) + ": gave " + cost + " for " + result;
        }
    }

    record PickedUp(String itemId, int count, long timestamp) implements ActivityEvent {
        @Override public String mergeKey() { return "picked_up:" + itemId; }
        @Override public long mergeWindowMs() { return 30_000L; }
        @Override public ActivityEvent mergeWith(ActivityEvent newer) {
            if (!(newer instanceof PickedUp p)) return newer;
            return new PickedUp(itemId, count + p.count, newer.timestamp());
        }
        @Override public String describe() { return "picked up " + count + " " + friendly(itemId); }
        @Override public boolean noteworthy() { return !FILLER_BLOCKS.contains(itemId); }
    }

    record Dropped(String itemId, int count, long timestamp) implements ActivityEvent {
        @Override public String mergeKey() { return "dropped:" + itemId; }
        @Override public long mergeWindowMs() { return 20_000L; }
        @Override public ActivityEvent mergeWith(ActivityEvent newer) {
            if (!(newer instanceof Dropped d)) return newer;
            return new Dropped(itemId, count + d.count, newer.timestamp());
        }
        @Override public String describe() { return "dropped " + count + " " + friendly(itemId); }
    }
}
