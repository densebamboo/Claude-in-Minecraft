package com.claudemod.entity;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.claudemod.ClaudeMod;
import com.claudemod.Config;
import com.claudemod.context.PlayerProfile;
import com.claudemod.director.ClaudeLife;
import com.claudemod.entity.body.FakeConnection;
import com.claudemod.entity.body.FakeGameListener;
import com.mojang.authlib.GameProfile;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Claude joins your world a few seconds after you do, as a real player: join message, tab list, his own save
 * (inventory, where he was, health, hunger). When he dies he respawns near you a moment later; when nobody
 * real is online he leaves. On shutdown the game saves and removes him like any player.
 */
public final class ClaudeSpawner {
    private static final int CHECK_EVERY_TICKS = 100;
    private static final long JOIN_DELAY_MS = 8_000L;
    private static final int RESPAWN_DELAY_TICKS = 20;
    private static final Map<UUID, Long> FIRST_SEEN = new ConcurrentHashMap<>();
    private static ClaudeEntity dead;
    /** Set by the automated in-game tests: don't auto-join or log him off for having nobody to play with. */
    public static boolean testing = false;
    private static int respawnAt;
    private static int nextTick = 0;

    private ClaudeSpawner() {}

    /** The same profile every time, so his player data (inventory, position) carries over between sessions. */
    public static GameProfile profile() {
        return new GameProfile(UUIDUtil.createOfflinePlayerUUID(ClaudeEntity.NAME), ClaudeEntity.NAME);
    }

    private static int frozenSaid = -100000;
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (dead != null && server.getTickCount() >= respawnAt) respawn(server);
        if (server.getTickCount() % 10 == 5) for (ServerPlayer p0 : server.getPlayerList().getPlayers()) { // his chunk tickets move in his own tick (ClaudeEntity.tick): teleported past them (a /tp, a test's drop), his chunk isn't entity-ticking, he never ticks again and they never move; from here they follow him (stage 537 World 15: frozen 240 s at 188, 68, -53 after the drop, 157 ticks)
            if (!(p0 instanceof ClaudeEntity c0) || !c0.isAlive() || c0.serverLevel().isPositionEntityTicking(c0.blockPosition())) continue;
            c0.serverLevel().getChunkSource().move(c0);
            if (server.getTickCount() - frozenSaid > 200) { frozenSaid = server.getTickCount(); ClaudeMod.LOGGER.info("[Claude] his chunk at {} in {} isn't ticking (moved there past his chunk tickets): the tickets moved to him (ClaudeSpawner.onServerTick)", c0.blockPosition().toShortString(), c0.level().dimension().location().getPath()); }
        }
        if (testing || server.getTickCount() < nextTick) return;
        nextTick = server.getTickCount() + CHECK_EVERY_TICKS;

        Optional<ClaudeEntity> claude = ClaudeEntity.any(server);
        List<ServerPlayer> real = server.getPlayerList().getPlayers().stream()
                .filter(p -> !(p instanceof ClaudeEntity) && p.isAlive()).toList(); // a spectator counts: the user watching him play is still someone to play with (he used to log off the moment they switched to spectator)
        if (Boolean.getBoolean("claude.hostSpectator")) for (ServerPlayer p : real) if (p.addTag("claude_spectator_set")) { // once per player (the tag sticks with them) // dev run only (build.gradle live): the user watches, cheats on
            server.getPlayerList().op(p.getGameProfile());
            p.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
            ClaudeMod.LOGGER.info("[Claude] {} made op and set to spectator (claude.hostSpectator)", p.getGameProfile().getName());
        }
        if (real.isEmpty()) {
            // nobody to play with: he logs off too (saved like any player) and comes back with them
            claude.ifPresent(c -> c.connection.disconnect(Component.literal("nobody to play with")));
            return;
        }
        if (claude.isPresent() || dead != null || !Config.ENABLE_SPAWN.get()) return;
        long now = System.currentTimeMillis();
        ServerPlayer first = real.get(0);
        if (now - FIRST_SEEN.computeIfAbsent(first.getUUID(), k -> now) < JOIN_DELAY_MS) return;
        join(server, first, null);
    }

    /** He died: respawn in a moment (his things stay where he fell, like any player's). */
    public static boolean pendingRespawn(ClaudeEntity c) { return dead == c; }
    static void respawnSoon(ClaudeEntity c) {
        dead = c;
        respawnAt = c.getServer() == null ? 0 : c.getServer().getTickCount() + RESPAWN_DELAY_TICKS;
    }

    private static void respawn(MinecraftServer server) {
        ClaudeEntity old = dead;
        dead = null;
        ServerPlayer owner = old.getTarget() instanceof ServerPlayer sp && !sp.isRemoved() ? sp
                : server.getPlayerList().getPlayers().stream().filter(p -> !(p instanceof ClaudeEntity)).findFirst().orElse(null);
        server.getPlayerList().remove(old); // saves (empty-handed, dead) and takes the body out; no "left the game"
        if (owner != null) join(server, owner, old);
    }

    /** Brings Claude into the game near them (or back where he logged off, if that's near them). */
    public static ClaudeEntity join(MinecraftServer server, ServerPlayer owner, ClaudeEntity respawnOf) {
        ServerLevel level = owner.serverLevel();
        GameProfile profile = profile();
        ClaudeEntity c = new ClaudeEntity(server, level, profile);
        FakeConnection connection = new FakeConnection();
        PlayerList list = server.getPlayerList();

        Optional<CompoundTag> saved = respawnOf == null ? list.load(c) : Optional.empty(); // his own player data
        ServerLevel savedIn = saved.map(t -> savedLevel(server, t)).orElse(null);
        if (savedIn != null && savedIn != level) { c.setServerLevel(savedIn); level = savedIn; ClaudeMod.LOGGER.info("[Claude] his save puts him in {}: back there", savedIn.dimension().location()); } // (he logged off in the Nether: he comes back in the Nether, like any player)
        c.loadGameTypes(saved.orElse(null));
        new FakeGameListener(server, connection, c, CommonListenerCookie.createInitial(profile, false)); // sets c.connection

        if (respawnOf != null && !testing) { // a respawn is in his bed's world, else the overworld, like any player: never the world they're in (16:59 he died in the Nether and came back at y 0 in Nether bedrock, then fell out of the world)
            ServerLevel home = respawnOf.getRespawnPosition() != null ? server.getLevel(respawnOf.getRespawnDimension()) : null;
            if (home == null) home = server.overworld();
            if (home != level) { c.setServerLevel(home); level = home; ClaudeMod.LOGGER.info("[Claude] respawning in {}, not where they are", home.dimension().location()); }
        }
        boolean nearThem = saved.isPresent() && c.level() == level; // his save says where he is: he logs in there, like any player (the summon command exists for the rest)
        ClaudeMod.LOGGER.info("[Claude] join: his player data {}{}, {} (ClaudeSpawner.join)", saved.isPresent() ? "found, at " + c.blockPosition().toShortString() : "not found", savedIn != null ? " in " + savedIn.dimension().location() : "", respawnOf != null ? "a respawn" : nearThem ? "logging in there" : "placing him");
        if (!nearThem && respawnOf == null && !testing) { // no player data to log in at: where he last stood, from his own record (every 10 s), if that spot's still a safe stand (live World 15: three restarts put him beside them, once out of his mine at y 20)
            var last = lastSpot(server, owner);
            if (last != null) { ServerLevel ll = (ServerLevel) last[0]; BlockPos lp = (BlockPos) last[1]; if (ll != level) { c.setServerLevel(ll); level = ll; } c.moveTo(lp.getX() + 0.5, lp.getY(), lp.getZ() + 0.5, owner.getYRot(), 0); nearThem = true; ClaudeMod.LOGGER.info("[Claude] join: back where he last stood, {} in {} (his own record) (ClaudeSpawner.join)", lp.toShortString(), ll.dimension().location()); }
        }
        if (!nearThem) {
            BlockPos spot = null;
            BlockPos bed = respawnOf != null ? respawnOf.getRespawnPosition() : null; // slept in a bed: he comes back there, like any player (not into the patrol camped on an AFK player at spawn: six deaths that way)
            if (bed != null && respawnOf.getRespawnDimension() == level.dimension() && level.getBlockState(bed).is(net.minecraft.tags.BlockTags.BEDS)) {
                spot = bed.above();
                for (var d : net.minecraft.core.Direction.Plane.HORIZONTAL) { BlockPos q = bed.relative(d); if (level.getBlockState(q).getCollisionShape(level, q).isEmpty() && level.getBlockState(q.above()).getCollisionShape(level, q.above()).isEmpty() && !level.getBlockState(q.below()).getCollisionShape(level, q.below()).isEmpty()) { spot = q; break; } }
                com.claudemod.ClaudeMod.LOGGER.info("[Claude] respawning at his bed at {}", bed.toShortString());
            } else if (respawnOf != null && !testing) { // no bed set: the world spawn, like any player (the user's rule; "near them" put him in their mine's pool 90 blocks from spawn with nothing on him)
                if (bed != null) ClaudeMod.LOGGER.info("[Claude] his respawn point {} in {} has no bed there now ({}): the overworld's spawn (ClaudeSpawner.join)", bed.toShortString(), respawnOf.getRespawnDimension().location(), level.isLoaded(bed) ? level.getBlockState(bed).getBlock().getName().getString() : "not loaded");
                if (level != server.overworld()) { level = server.overworld(); c.setServerLevel(level); } // (the world spawn is the overworld's, always: World 15 22:50 he came back in the Nether at 0, 83, 0)
                BlockPos ws = level.getSharedSpawnPos();
                spot = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, ws);
                com.claudemod.ClaudeMod.LOGGER.info("[Claude] no bed: respawning at the world spawn {}", spot.toShortString());
            } else if (respawnOf != null) { // (the test box: back beside the mock player, or every stage after a death runs with him at the world spawn)
                spot = ClaudeLife.spotNear(level, owner, 4, 9);
            }
            if (spot == null) spot = ClaudeLife.spotNear(level, owner, 4, 9);
            if (spot == null) spot = owner.blockPosition();
            c.moveTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, owner.getYRot() + 180, 0);
        }
        if (respawnOf != null) {
            c.copyBrainFrom(respawnOf);
            c.justRespawned = true;
            c.setHealth(c.getMaxHealth());
            c.deathTime = 0;
            c.getFoodData().setFoodLevel(20);
            c.getFoodData().setSaturation(5.0f);
        } else if (saved.isEmpty()) {
            migrateOldInventory(c, owner);
        }
        if (c.isDeadOrDying()) c.setHealth(c.getMaxHealth()); // logged off dead: back on his feet

        addToPlayerList(list, c);
        list.broadcastAll(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(c)));
        level.addNewPlayer(c);
        if (respawnOf == null) {
            list.broadcastSystemMessage(Component.translatable("multiplayer.player.joined", c.getDisplayName()).withStyle(ChatFormatting.YELLOW), false);
        }
        c.setTarget(owner);
        if (respawnOf == null) c.greetAt = 60; // a few seconds after joining: hi, what do you want to do?
        PlayerProfile.setClaude(owner, c.getUUID());
        ClaudeMod.LOGGER.info("[Claude] {} near {} at {}", respawnOf == null ? "joined" : "respawned",
                owner.getGameProfile().getName(), c.blockPosition().toShortString());
        return c;
    }

    /** Where he last stood (his own record, saved every 10 s with his mine): {level, cell}, or null if there's none or it isn't a safe stand now (open for feet and head, no fluid, a floor). */
    public static Object[] lastSpot(MinecraftServer server, ServerPlayer owner) {
        var mine = com.claudemod.context.PlayerProfile.mine(owner);
        if (mine == null || !mine.contains("lastPos") || !mine.contains("lastDim")) return null;
        BlockPos lp = BlockPos.of(mine.getLong("lastPos"));
        ServerLevel ll = server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, net.minecraft.resources.ResourceLocation.parse(mine.getString("lastDim"))));
        if (ll == null) return null;
        boolean safe = ll.getBlockState(lp).getCollisionShape(ll, lp).isEmpty() && ll.getBlockState(lp.above()).getCollisionShape(ll, lp.above()).isEmpty() && ll.getFluidState(lp).isEmpty() && ll.getFluidState(lp.above()).isEmpty() && ll.getBlockState(lp.below()).isFaceSturdy(ll, lp.below(), net.minecraft.core.Direction.UP);
        if (!safe) { ClaudeMod.LOGGER.info("[Claude] join: where he last stood, {} in {}, isn't a safe stand now (ClaudeSpawner.lastSpot)", lp.toShortString(), ll.dimension().location()); return null; }
        return new Object[] {ll, lp};
    }

    /** The world a saved player tag puts him in (its Dimension, as the game reads it for any player), or null. */
    public static ServerLevel savedLevel(MinecraftServer server, CompoundTag tag) {
        if (tag == null || !tag.contains("Dimension")) return null;
        return net.minecraft.world.level.dimension.DimensionType.parseLegacy(new com.mojang.serialization.Dynamic<>(net.minecraft.nbt.NbtOps.INSTANCE, tag.get("Dimension"))).result().map(server::getLevel).orElse(null);
    }

    /** For the automated tests: Claude in the game at a spot, with nobody to play with (he just runs the tasks he's given). */
    public static ClaudeEntity joinAt(MinecraftServer server, ServerLevel level, BlockPos at) {
        GameProfile profile = profile();
        ClaudeEntity c = new ClaudeEntity(server, level, profile);
        c.loadGameTypes(null);
        new FakeGameListener(server, new FakeConnection(), c, CommonListenerCookie.createInitial(profile, false));
        c.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
        c.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        addToPlayerList(server.getPlayerList(), c);
        server.getPlayerList().broadcastAll(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(c)));
        level.addNewPlayer(c);
        return c;
    }

    /** The player list keeps its players private (they normally only arrive over the network). */
    @SuppressWarnings("unchecked")
    private static void addToPlayerList(PlayerList list, ServerPlayer c) {
        try {
            Field players = PlayerList.class.getDeclaredField("players");
            players.setAccessible(true);
            ((List<ServerPlayer>) players.get(list)).add(c);
            Field byUuid = PlayerList.class.getDeclaredField("playersByUUID");
            byUuid.setAccessible(true);
            ((Map<UUID, ServerPlayer>) byUuid.get(list)).put(c.getUUID(), c);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Claude: couldn't add him to the player list", e);
        }
    }

    /** What the old (mob) Claude was carrying moves into his player inventory, once. */
    private static void migrateOldInventory(ClaudeEntity c, ServerPlayer owner) {
        var old = PlayerProfile.inventory(owner);
        if (old == null || old.isEmpty()) return;
        SimpleContainer box = new SimpleContainer(36);
        box.fromTag(old, c.registryAccess());
        int moved = 0;
        for (int i = 0; i < box.getContainerSize(); i++) {
            ItemStack s = box.getItem(i);
            if (s.isEmpty()) continue;
            moved += s.getCount();
            ItemStack rest = c.inventory.add(s);
            if (!rest.isEmpty()) c.drop(rest, false);
        }
        PlayerProfile.setInventory(owner, null);
        ClaudeMod.LOGGER.info("[Claude] moved {} items from his old inventory into his player inventory", moved);
    }

    /** New session: he joins again. */
    public static void clear() {
        FIRST_SEEN.clear();
        dead = null;
        nextTick = 0; // a reopened world's tick count starts over: don't wait out the last session's countdown
        respawnAt = 0;
    }
}
