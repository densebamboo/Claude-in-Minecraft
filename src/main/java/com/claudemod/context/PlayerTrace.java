package com.claudemod.context;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.claudemod.ClaudeMod;
import com.claudemod.entity.ClaudeEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * Exactly how a player plays, the real one and him on the same schema, so a run of each can be laid side by side (the user,
 * 2026-10-29: "rather than just having you guys guess without actually seeing it"). One JSON object per line, one file per player
 * per day, under the instance folder (claude-traces/<day>-<name>.jsonl). Nothing here changes the game; it only watches.
 *   s       every tick he moves, turns or changes state: pos (3 dp), velocity, yaw/pitch (1 dp), flags, slot, held, hp, food, sat, air
 *   in      the real player's keys (client side, integrated server only): forward/strafe/jump/sneak/sprint/attack/use; his own: his
 *           move impulses and jump
 *   aim     the crosshair, when it changes: block (pos, face, id) or entity (type, id, dist)
 *   mine    a dig started / stopped / aborted, with pos and face (the time between is the mining time)
 *   break / place / use_block / use_item / attack, as before, use_item with the crosshair target (fluid-aware for buckets)
 *   pour    after a bucket use: where the fluid actually landed (the cells that changed), next tick
 *   inv     what came in or went out of his pack this tick (pickups, crafts, drops, uses)
 *   ctx     each second: lava and water within 6 (count, nearest), mobs within 16 (type, pos), light, time of day, weather
 *   task / skill   his own decisions (the bot only): the task he's on, his skill line, when they change
 *   mark    a chat line starting "mark" (the cut tool's window: "mark start obsidian" ... "mark stop")
 */
public final class PlayerTrace {
    private static final Map<UUID, java.io.BufferedWriter> outs = new HashMap<>();
    private static final Map<UUID, String> days = new HashMap<>();
    private static final Map<UUID, String> lastS = new HashMap<>(), lastAim = new HashMap<>(), lastIn = new HashMap<>(), lastTask = new HashMap<>(), lastSkill = new HashMap<>();
    private static final Map<UUID, Map<Item, Integer>> lastInv = new HashMap<>();
    private static final Map<UUID, Object[]> pendingPour = new HashMap<>(); // [tick, center, snapshot]
    private static Path dir;

    private PlayerTrace() {}

    public static void onTick(PlayerTickEvent.Post e) { try { onTick0(e); } catch (Throwable ex) { if (traceErrors++ < 5) ClaudeMod.LOGGER.warn("[Claude trace] skipped a tick: {}", ex.toString()); } } // the recorder only watches: it must never take the server down (01:16 crash)
    private static int traceErrors = 0;
    private static void onTick0(PlayerTickEvent.Post e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || p.level().isClientSide) return;
        UUID id = p.getUUID(); boolean bot = ClaudeEntity.isClaude(p);
        // s: the body
        Vec3 v = p.getDeltaMovement(); ItemStack held = p.getMainHandItem();
        int flags = (p.onGround() ? 1 : 0) | (p.isSwimming() ? 2 : 0) | (p.isInWater() ? 4 : 0) | (p.isSprinting() ? 8 : 0) | (p.isShiftKeyDown() ? 16 : 0) | (p.isInLava() ? 32 : 0) | (p.onClimbable() ? 64 : 0) | (p.isFallFlying() ? 128 : 0);
        String s = String.format(Locale.ROOT, "\"x\":%.3f,\"y\":%.3f,\"z\":%.3f,\"vx\":%.3f,\"vy\":%.3f,\"vz\":%.3f,\"yaw\":%.1f,\"pitch\":%.1f,\"f\":%d,\"slot\":%d,\"held\":\"%s\",\"hp\":%.1f,\"food\":%d,\"sat\":%.1f,\"air\":%d",
                p.getX(), p.getY(), p.getZ(), v.x, v.y, v.z, p.getYRot(), p.getXRot(), flags, p.getInventory().selected, id(held), p.getHealth(), p.getFoodData().getFoodLevel(), p.getFoodData().getSaturationLevel(), p.getAirSupply());
        if (!s.equals(lastS.get(id))) { lastS.put(id, s); log(p, "s", s); }
        // in: the keys
        String in = bot ? String.format(Locale.ROOT, "\"fwd\":%.2f,\"strafe\":%.2f,\"jump\":%b,\"sneak\":%b,\"sprint\":%b", p.zza, p.xxa, ((ClaudeEntity) p).isJumpingNow(), p.isShiftKeyDown(), p.isSprinting())
                : net.neoforged.fml.loading.FMLEnvironment.dist.isClient() ? com.claudemod.client.TraceInputs.read(p.getGameProfile().getName()) : null;
        if (in != null && !in.equals(lastIn.get(id))) { lastIn.put(id, in); log(p, "in", in); }
        // aim: the crosshair
        String aim = aim(p, ClipContext.Fluid.NONE);
        if (!aim.equals(lastAim.get(id))) { lastAim.put(id, aim); log(p, "aim", aim); }
        // pour: where the bucket's fluid went
        Object[] pp = pendingPour.get(id);
        if (pp != null && p.tickCount > (int) pp[0]) {
            pendingPour.remove(id); BlockPos c = (BlockPos) pp[1]; @SuppressWarnings("unchecked") Map<BlockPos, String> was = (Map<BlockPos, String>) pp[2];
            StringBuilder sb = new StringBuilder();
            for (BlockPos q : BlockPos.betweenClosed(c.offset(-2, -2, -2), c.offset(2, 2, 2))) { String now = fluid((ServerLevel) p.level(), q); if (!now.equals(was.get(q.immutable()))) sb.append(sb.length() > 0 ? "," : "").append(String.format(Locale.ROOT, "{\"at\":[%d,%d,%d],\"from\":\"%s\",\"to\":\"%s\"}", q.getX(), q.getY(), q.getZ(), was.get(q.immutable()), now)); }
            log(p, "pour", "\"changed\":[" + sb + "]");
        }
        // inv: what came and went
        Map<Item, Integer> inv = new HashMap<>(); for (ItemStack it : p.getInventory().items) if (!it.isEmpty()) inv.merge(it.getItem(), it.getCount(), Integer::sum);
        for (ItemStack it : p.getInventory().armor) if (!it.isEmpty()) inv.merge(it.getItem(), it.getCount(), Integer::sum);
        for (ItemStack it : p.getInventory().offhand) if (!it.isEmpty()) inv.merge(it.getItem(), it.getCount(), Integer::sum);
        Map<Item, Integer> was = lastInv.put(id, inv);
        if (was != null && !was.equals(inv)) {
            StringBuilder sb = new StringBuilder(); java.util.Set<Item> all = new java.util.HashSet<>(was.keySet()); all.addAll(inv.keySet());
            for (Item it : all) { int d = inv.getOrDefault(it, 0) - was.getOrDefault(it, 0); if (d != 0) sb.append(sb.length() > 0 ? "," : "").append("\"").append(BuiltInRegistries.ITEM.getKey(it).getPath()).append("\":").append(d); }
            if (sb.length() > 0) log(p, "inv", "\"d\":{" + sb + "}");
        }
        // ctx: what's about, each second
        if (p.tickCount % 20 == 0) log(p, "ctx", ctx(p));
        // his decisions
        if (bot) {
            ClaudeEntity g = (ClaudeEntity) p;
            String task = g.currentTask == null ? "" : g.currentTask.toString(); if (task.length() > 160) task = task.substring(0, 160);
            if (!task.equals(lastTask.get(id))) { lastTask.put(id, task); log(p, "task", "\"now\":\"" + esc(task) + "\",\"queued\":" + g.tasks.size()); }
            String skill = g.lastSkillLine == null ? "" : g.lastSkillLine; if (skill.length() > 200) skill = skill.substring(0, 200);
            if (!skill.equals(lastSkill.get(id))) { lastSkill.put(id, skill); log(p, "skill", "\"line\":\"" + esc(skill) + "\""); }
        }
    }

    /** The crosshair: the block or entity a player would see under it, out to his reach. */
    static String aim(ServerPlayer p, ClipContext.Fluid fluid) {
        double reach = p.blockInteractionRange(); Vec3 eye = p.getEyePosition(), look = p.getViewVector(1f), end = eye.add(look.scale(reach));
        BlockHitResult bh = p.level().clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, fluid, p));
        double ereach = p.entityInteractionRange(); Vec3 eend = eye.add(look.scale(ereach));
        EntityHitResult eh = net.minecraft.world.entity.projectile.ProjectileUtil.getEntityHitResult(p, eye, eend, p.getBoundingBox().expandTowards(look.scale(ereach)).inflate(1), en -> !en.isSpectator() && en.isPickable(), ereach * ereach);
        if (eh != null && (bh.getType() == HitResult.Type.MISS || eh.getLocation().distanceTo(eye) < bh.getLocation().distanceTo(eye))) {
            Entity en = eh.getEntity(); return String.format(Locale.ROOT, "\"entity\":\"%s\",\"id\":%d,\"dist\":%.2f", BuiltInRegistries.ENTITY_TYPE.getKey(en.getType()).getPath(), en.getId(), eh.getLocation().distanceTo(eye));
        }
        if (bh.getType() == HitResult.Type.MISS) return "\"none\":true";
        BlockPos q = bh.getBlockPos();
        return String.format(Locale.ROOT, "\"block\":\"%s\",\"at\":[%d,%d,%d],\"face\":\"%s\",\"hit\":[%.2f,%.2f,%.2f]", fluid == ClipContext.Fluid.NONE ? id(p.level().getBlockState(q).getBlock().asItem()) : fluid((ServerLevel) p.level(), q) + "|" + id(p.level().getBlockState(q).getBlock().asItem()),
                q.getX(), q.getY(), q.getZ(), bh.getDirection().getName(), bh.getLocation().x, bh.getLocation().y, bh.getLocation().z);
    }

    static String ctx(ServerPlayer p) {
        ServerLevel lv = (ServerLevel) p.level(); BlockPos f = p.blockPosition();
        int lava = 0, water = 0; double nl = 99, nw = 99; BlockPos nlp = null, nwp = null;
        for (BlockPos q : BlockPos.betweenClosed(f.offset(-6, -4, -6), f.offset(6, 4, 6))) {
            var fs = lv.getFluidState(q); if (fs.isEmpty()) continue;
            double d = Math.sqrt(q.distSqr(f));
            if (fs.is(FluidTags.LAVA)) { lava++; if (d < nl) { nl = d; nlp = q.immutable(); } } else if (fs.is(FluidTags.WATER)) { water++; if (d < nw) { nw = d; nwp = q.immutable(); } }
        }
        StringBuilder mobs = new StringBuilder();
        for (var m : lv.getEntitiesOfClass(net.minecraft.world.entity.Mob.class, p.getBoundingBox().inflate(16), Entity::isAlive)) {
            if (mobs.length() > 600) break;
            mobs.append(mobs.length() > 0 ? "," : "").append(String.format(Locale.ROOT, "[\"%s\",%.1f,%.1f,%.1f]", BuiltInRegistries.ENTITY_TYPE.getKey(m.getType()).getPath(), m.getX(), m.getY(), m.getZ()));
        }
        return String.format(Locale.ROOT, "\"lava\":%d,\"lavaAt\":%s,\"water\":%d,\"waterAt\":%s,\"mobs\":[%s],\"light\":[%d,%d],\"time\":%d,\"rain\":%b,\"thunder\":%b,\"dim\":\"%s\"",
                lava, at(nlp), water, at(nwp), mobs, lv.getBrightness(LightLayer.BLOCK, f), lv.getBrightness(LightLayer.SKY, f), lv.getDayTime() % 24000, lv.isRaining(), lv.isThundering(), lv.dimension().location().getPath());
    }
    static String at(BlockPos q) { return q == null ? "null" : "[" + q.getX() + "," + q.getY() + "," + q.getZ() + "]"; }
    static String fluid(ServerLevel lv, BlockPos q) { var fs = lv.getFluidState(q); return fs.isEmpty() ? "none" : (fs.is(FluidTags.LAVA) ? "lava" : "water") + (fs.isSource() ? "" : fs.getAmount()); }
    static String id(ItemStack s) { return s.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(s.getItem()).getPath(); }
    static String id(Item i) { return BuiltInRegistries.ITEM.getKey(i).getPath(); }
    static String esc(String s) { return s.replace("\\", "\\\\").replace("\"", "'").replace("\n", " "); }

    public static void onAttack(AttackEntityEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || p.level().isClientSide) return;
        var t = e.getTarget();
        log(p, "attack", String.format(Locale.ROOT, "\"target\":\"%s\",\"id\":%d,\"dist\":%.2f,\"held\":\"%s\",\"charge\":%.2f",
                BuiltInRegistries.ENTITY_TYPE.getKey(t.getType()).getPath(), t.getId(), p.distanceTo(t), id(p.getMainHandItem()), p.getAttackStrengthScale(0.5f)));
    }

    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || p.level().isClientSide) return;
        BlockPos q = e.getPos();
        log(p, "mine", String.format(Locale.ROOT, "\"action\":\"%s\",\"block\":\"%s\",\"at\":[%d,%d,%d],\"face\":\"%s\",\"held\":\"%s\"",
                e.getAction().name().toLowerCase(Locale.ROOT), id(p.level().getBlockState(q).getBlock().asItem()), q.getX(), q.getY(), q.getZ(), e.getFace() == null ? "" : e.getFace().getName(), id(p.getMainHandItem())));
    }

    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || p.level().isClientSide) return;
        BlockPos q = e.getPos(); var hit = e.getHitVec();
        log(p, "use_block", String.format(Locale.ROOT, "\"block\":\"%s\",\"at\":[%d,%d,%d],\"face\":\"%s\",\"hit\":[%.2f,%.2f,%.2f],\"held\":\"%s\"",
                id(p.level().getBlockState(q).getBlock().asItem()), q.getX(), q.getY(), q.getZ(), hit.getDirection().getName(), hit.getLocation().x, hit.getLocation().y, hit.getLocation().z, id(e.getItemStack())));
    }

    public static void onRightClickItem(PlayerInteractEvent.RightClickItem e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || p.level().isClientSide) return;
        boolean bucket = e.getItemStack().getItem() instanceof net.minecraft.world.item.BucketItem;
        String aim = aim(p, bucket ? ClipContext.Fluid.SOURCE_ONLY : ClipContext.Fluid.NONE);
        log(p, "use_item", "\"held\":\"" + id(e.getItemStack()) + "\"," + aim);
        if (bucket) { // what the cells round the target were, to see where the fluid lands next tick
            BlockHitResult bh = p.level().clip(new ClipContext(p.getEyePosition(), p.getEyePosition().add(p.getViewVector(1f).scale(p.blockInteractionRange())), ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, p));
            BlockPos c = bh.getType() == HitResult.Type.MISS ? p.blockPosition() : bh.getBlockPos();
            Map<BlockPos, String> snap = new HashMap<>(); for (BlockPos q : BlockPos.betweenClosed(c.offset(-2, -2, -2), c.offset(2, 2, 2))) snap.put(q.immutable(), fluid((ServerLevel) p.level(), q));
            pendingPour.put(p.getUUID(), new Object[] {p.tickCount, c.immutable(), snap});
        }
    }

    public static void onChat(ServerChatEvent e) {
        String t = e.getRawText().trim();
        if (t.toLowerCase(Locale.ROOT).startsWith("mark")) log(e.getPlayer(), "mark", "\"text\":\"" + esc(t) + "\"");
    }

    /** Block broken or placed (called from the activity listener that already sees those; his own too). */
    public static void block(ServerPlayer p, String what, String blockId, BlockPos q) {
        log(p, what, String.format(Locale.ROOT, "\"block\":\"%s\",\"at\":[%d,%d,%d],\"held\":\"%s\"", blockId.replace("minecraft:", ""), q.getX(), q.getY(), q.getZ(), id(p.getMainHandItem())));
    }

    /** His temporary-block ledger's lines (TempLedger): "temp" put down, "temp_up" back up, "temp_left" left standing, "tree_left". */
    public static void temp(ServerPlayer p, String kind, String fields) { log(p, kind, fields); }

    private static synchronized void log(ServerPlayer p, String kind, String fields) {
        if (com.claudemod.entity.ClaudeSpawner.testing) return; // (the gametest server: nobody to model)
        UUID id = p.getUUID();
        try {
            String today = LocalDate.now().toString(); java.io.BufferedWriter out = outs.get(id);
            if (out == null || !today.equals(days.get(id))) {
                if (out != null) out.close();
                if (dir == null) dir = p.server.getServerDirectory().resolve("claude-traces");
                Files.createDirectories(dir);
                days.put(id, today);
                Path file = dir.resolve(today + "-" + p.getGameProfile().getName() + ".jsonl");
                out = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND); outs.put(id, out);
                ClaudeMod.LOGGER.info("[Claude trace] recording {} to {}", p.getGameProfile().getName(), file);
            }
            out.write("{\"t\":" + System.currentTimeMillis() + ",\"tick\":" + p.server.getTickCount() + ",\"who\":\"" + p.getGameProfile().getName() + "\",\"k\":\"" + kind + "\"," + fields + "}\n");
            if (p.server.getTickCount() % 40 == 0 || kind.equals("mark")) out.flush();
        } catch (IOException ex) {
            ClaudeMod.LOGGER.warn("[Claude trace] couldn't write the trace: {}", ex.getMessage());
        }
    }
}
