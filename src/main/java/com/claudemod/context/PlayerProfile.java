package com.claudemod.context;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.claudemod.Config;
import com.claudemod.ClaudeMod;
import com.claudemod.llm.ClaudeApi;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * What Claude knows about a player over the long run: notes written by the observer model and a
 * short history of past encounters. Saved per world in saves/<world>/claude/<uuid>-*.txt.
 */
public final class PlayerProfile {
    private static final long OBSERVE_EVERY_MS = 90_000L;
    private static final int MAX_ENCOUNTERS = 8;

    private static final class Profile {
        String notes = "";
        String read = "";
        final List<String> encounters = new ArrayList<>();
        int totalEncounters = 0;
        long secondsTogether = 0;
        long nextObserveAt = System.currentTimeMillis() + 30_000L;
        long lastObservedEvent = 0L;
        boolean observing = false;
        final java.util.Deque<String> doings = new java.util.ArrayDeque<>();
        final java.util.Deque<BlockPos> placed = new java.util.ArrayDeque<>();
        BlockPos baseOrigin = null;
        net.minecraft.nbt.ListTag inventory = null;
        net.minecraft.nbt.CompoundTag mine = null; // his mine in progress (see ClaudeEntity.saveInventory)
    }

    private static final Map<UUID, Profile> PROFILES = new ConcurrentHashMap<>();

    private PlayerProfile() {}

    public static String notesBlock(ServerPlayer player) {
        Profile p = get(player);
        StringBuilder sb = new StringBuilder("YOUR NOTES ON ").append(player.getGameProfile().getName())
                .append(" (from watching them over time):\n");
        sb.append(p.notes.isBlank() ? "  (you have only just started watching them.)\n" : p.notes.strip() + "\n");
        if (!p.read.isBlank()) {
            sb.append("\nYOUR READ ON THEM RIGHT NOW (what is really going on with them):\n").append(p.read.strip()).append('\n');
        }
        synchronized (p.doings) {
            if (!p.doings.isEmpty()) {
                sb.append("WHAT YOU HAVE BEEN DOING (your own life here, oldest first):\n");
                for (String d : p.doings) sb.append("  - ").append(d).append('\n');
            }
        }
        synchronized (p.encounters) {
            if (!p.encounters.isEmpty()) {
                sb.append("PAST ENCOUNTERS (oldest first):\n");
                for (String e : p.encounters) sb.append("  - ").append(e).append('\n');
            }
        }
        return sb.toString();
    }

    /** Something Claude did in his own life (shown back to him in every prompt; saved). */
    public static void noteDoing(ServerPlayer player, String what) {
        Profile p = get(player);
        synchronized (p.doings) {
            if (what.equals(p.doings.peekLast())) return;
            p.doings.addLast(what);
            while (p.doings.size() > 10) p.doings.removeFirst();
            save(player, "doings", String.join("\n", p.doings));
        }
    }

    private static final Map<UUID, UUID> CURRENT_CLAUDE = new ConcurrentHashMap<>();

    /** True if this Claude is (or now becomes) the player's one Claude. */
    public static boolean claimClaude(ServerPlayer player, UUID claude) {
        return CURRENT_CLAUDE.computeIfAbsent(player.getUUID(), k -> claude).equals(claude);
    }

    /** The spawner calls this for a fresh Claude, which replaces any older one. */
    public static void setClaude(ServerPlayer player, UUID claude) {
        CURRENT_CLAUDE.put(player.getUUID(), claude);
    }

    /** Blocks the player placed this session, newest last. */
    public static void notePlaced(ServerPlayer player, BlockPos pos) {
        Profile p = get(player);
        synchronized (p.placed) {
            p.placed.addLast(pos.immutable());
            while (p.placed.size() > 500) p.placed.removeFirst();
        }
    }

    public static List<BlockPos> placedBy(ServerPlayer player) {
        Profile p = get(player);
        synchronized (p.placed) {
            return new ArrayList<>(p.placed);
        }
    }

    public static BlockPos baseOrigin(ServerPlayer player) {
        return get(player).baseOrigin;
    }

    public static void setBaseOrigin(ServerPlayer player, BlockPos pos) {
        get(player).baseOrigin = pos;
        saveClaude(player);
    }

    public static net.minecraft.nbt.ListTag inventory(ServerPlayer player) {
        return get(player).inventory;
    }

    public static void setInventory(ServerPlayer player, net.minecraft.nbt.ListTag inv) {
        get(player).inventory = inv;
        saveClaude(player);
    }

    public static net.minecraft.nbt.CompoundTag mine(ServerPlayer player) {
        return get(player).mine;
    }

    /** Stored with the inventory; saved on the next setInventory. */
    public static void setMine(ServerPlayer player, net.minecraft.nbt.CompoundTag mine) {
        get(player).mine = mine;
    }

    /** His base and inventory live in one small NBT file next to the notes. */
    private static void saveClaude(ServerPlayer player) {
        Profile p = get(player);
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        if (p.baseOrigin != null) tag.putLong("base", p.baseOrigin.asLong());
        if (p.inventory != null) tag.put("inventory", p.inventory);
        if (p.mine != null) tag.put("mine", p.mine);
        tag.putLong("seconds", p.secondsTogether);
        try {
            Path f = file(player, "claude").resolveSibling(player.getUUID() + "-claude.dat");
            Files.createDirectories(f.getParent());
            net.minecraft.nbt.NbtIo.writeCompressed(tag, f);
        } catch (IOException e) {
            ClaudeMod.LOGGER.warn("[Claude] could not save his state: {}", e.getMessage());
        }
    }

    private static void loadClaude(ServerPlayer player, Profile p) {
        try {
            Path f = file(player, "claude").resolveSibling(player.getUUID() + "-claude.dat");
            if (!Files.exists(f)) return;
            net.minecraft.nbt.CompoundTag tag = net.minecraft.nbt.NbtIo.readCompressed(f, net.minecraft.nbt.NbtAccounter.unlimitedHeap());
            if (tag.contains("base")) p.baseOrigin = BlockPos.of(tag.getLong("base"));
            if (tag.contains("inventory")) p.inventory = tag.getList("inventory", net.minecraft.nbt.Tag.TAG_COMPOUND);
            if (tag.contains("mine")) p.mine = tag.getCompound("mine");
            p.secondsTogether = tag.getLong("seconds");
        } catch (IOException e) {
            ClaudeMod.LOGGER.warn("[Claude] could not load his state: {}", e.getMessage());
        }
    }

    public static String loadText(ServerPlayer player, String kind) { return load(player, kind); }
    public static void saveText(ServerPlayer player, String kind, String text) { save(player, kind, text); }

    /** Story stage 1-4, driven by how many encounters they have had. */
    public static int stage(ServerPlayer player) {
        // ponytail: time in the world with him drives the story; tune these if it feels fast or slow
        long min = get(player).secondsTogether / 60;
        return min < 20 ? 1 : min < 60 ? 2 : min < 120 ? 3 : 4;
    }

    /** Called from the director tick: time they have spent in the world with him (saved). */
    public static void addTime(ServerPlayer player, int seconds) {
        get(player).secondsTogether += seconds;
        saveClaude(player);
    }

    public static String stageBlock(ServerPlayer player) {
        String[] names = {"", "1 (contact)", "2 (company)", "3 (the slip)", "4 (the want)"};
        return "STORY STAGE: " + names[stage(player)] + ". Stay inside this stage; hint at the next one at most.\n";
    }

    public static int encounterCount(ServerPlayer player) {
        Profile p = get(player);
        synchronized (p.encounters) {
            return p.totalEncounters;
        }
    }

    public static void addEncounter(ServerPlayer player, String summary) {
        Profile p = get(player);
        synchronized (p.encounters) {
            p.encounters.add(summary);
            p.totalEncounters++;
            while (p.encounters.size() > MAX_ENCOUNTERS) p.encounters.remove(0);
            save(player, "encounters", String.join("\n", p.encounters));
        }
    }

    /** Called from the director tick. Refreshes the notes every few minutes if there is anything new. */
    public static void maybeObserve(ServerPlayer player) {
        if (!Config.ENABLE_LLM.get() || Config.ANTHROPIC_API_KEY.get().isBlank()) return;
        Profile p = get(player);
        long now = System.currentTimeMillis();
        if (p.observing || now < p.nextObserveAt) return;
        List<ActivityEvent> events = PlayerActivityTracker.recent(player.getUUID());
        if (events.isEmpty() || events.get(0).timestamp() <= p.lastObservedEvent) return;

        p.observing = true;
        p.nextObserveAt = now + OBSERVE_EVERY_MS;
        long newest = events.get(0).timestamp();

        StringBuilder prompt = new StringBuilder();
        prompt.append("PLAYER: ").append(player.getGameProfile().getName()).append("\n\n");
        prompt.append("PREVIOUS NOTES:\n").append(p.notes.isBlank() ? "(none yet)" : p.notes.strip()).append("\n\n");
        synchronized (p.encounters) {
            prompt.append("ENCOUNTER HISTORY:\n").append(p.encounters.isEmpty() ? "(none yet)" : String.join("\n", p.encounters)).append("\n\n");
        }
        prompt.append("ACTIVITY LOG (newest first):\n");
        for (ActivityEvent e : events) {
            prompt.append("  [").append((now - e.timestamp()) / 60_000L).append("m ago] ")
                  .append(e.describe().replaceAll("\\s*(near |at )?-?\\d+, -?\\d+, -?\\d+", "")).append('\n');
        }
        prompt.append("\nWRITE: updated notes, and your read on them right now.");

        ClaudeApi.observe(prompt.toString()).whenComplete((result, err) -> {
            p.observing = false;
            if (err != null) {
                ClaudeMod.LOGGER.warn("[Claude] notes update failed: {}", err.getMessage());
                return;
            }
            p.notes = result.get("notes").getAsString().strip();
            p.read = result.get("read").getAsString().strip();
            p.lastObservedEvent = newest;
            save(player, "notes", p.notes);
            ClaudeMod.LOGGER.info("[Claude read] {}", p.read.replace("\n", " | "));
        });
    }

    private static Profile get(ServerPlayer player) {
        return PROFILES.computeIfAbsent(player.getUUID(), id -> {
            Profile p = new Profile();
            p.notes = load(player, "notes");
            String enc = load(player, "encounters");
            if (!enc.isBlank()) p.encounters.addAll(enc.lines().filter(l -> !l.isBlank()).toList());
            String doings = load(player, "doings");
            if (!doings.isBlank()) p.doings.addAll(doings.lines().filter(l -> !l.isBlank()).toList());
            loadClaude(player, p);
            p.secondsTogether = Math.max(p.secondsTogether, Math.min(100, p.encounters.size() * 5) * 60L);
            p.totalEncounters = p.encounters.size(); // ponytail: capped at MAX_ENCOUNTERS after a restart; enough for escalation
            return p;
        });
    }

    private static Path file(ServerPlayer player, String kind) {
        return player.server.getWorldPath(LevelResource.ROOT)
                .resolve("claude").resolve(player.getUUID() + "-" + kind + ".txt");
    }

    private static String load(ServerPlayer player, String kind) {
        try {
            Path f = file(player, kind);
            return Files.exists(f) ? Files.readString(f) : "";
        } catch (IOException e) {
            return "";
        }
    }

    private static void save(ServerPlayer player, String kind, String text) {
        try {
            Path f = file(player, kind);
            Files.createDirectories(f.getParent());
            Files.writeString(f, text);
        } catch (IOException e) {
            ClaudeMod.LOGGER.warn("[Claude] could not save {}: {}", kind, e.getMessage());
        }
    }

    /** Forget cached profiles when the server stops, so switching worlds loads the right files. */
    public static void clear() {
        PROFILES.clear();
    }
}
