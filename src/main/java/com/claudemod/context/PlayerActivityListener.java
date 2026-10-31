package com.claudemod.context;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player.BedSleepingProblem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.biome.Biome;
import com.claudemod.entity.ClaudeEntity;
import com.claudemod.llm.ChatListener;

import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.CanPlayerSleepEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.ItemFishedEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerSetSpawnEvent;
import net.neoforged.neoforge.event.entity.player.TradeWithVillagerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

public final class PlayerActivityListener {
    private static final int CHAT_MAX_CHARS = 80;

    private PlayerActivityListener() {}

    public static void onBlockBreak(BlockEvent.BreakEvent e) {
        if (e.getPlayer() instanceof ServerPlayer p) {
            BlockPos pos = e.getPos();
            String blockId = BuiltInRegistries.BLOCK.getKey(e.getState().getBlock()).toString();
            log(p, new ActivityEvent.Mined(blockId, pos, System.currentTimeMillis()));
            try { PlayerTrace.block(p, "break", blockId, pos); } catch (NoClassDefFoundError ignored) {} // (the recorder class arrives with a relaunch; a hot-swapped listener must not blow up before it)
            if (!ClaudeEntity.isClaude(p) && p.level() instanceof ServerLevel slw) com.claudemod.entity.life.WatchBuild.userBroke(p, slw, pos, e.getState()); if (!ClaudeEntity.isClaude(p) && p.level() instanceof ServerLevel slc) com.claudemod.entity.life.CopyMode.userBroke(p, slc, pos, e.getState()); // (showing him: learned if he saw it) // (a build he's watching: learned if he saw it go)
            if (p.level() instanceof ServerLevel sl1) com.claudemod.entity.life.TempLedger.brokenBy(p, sl1, pos); // one of his temporary blocks back up (his after-record)
        }
    }

    public static void onBlockPlace(BlockEvent.EntityPlaceEvent e) {
        if (e.getEntity() instanceof ServerPlayer c && ClaudeEntity.isClaude(c)) { com.claudemod.context.PlayerProfile.notePlaced(c, e.getPos()); if (c instanceof ClaudeEntity g0 && c.level() instanceof ServerLevel sl0) com.claudemod.entity.life.TempLedger.placed(g0, sl0, e.getPos(), e.getPlacedBlock(), e.getBlockSnapshot().getState()); /* (every block he puts down for now, on one ledger) */ try { PlayerTrace.block(c, "place", BuiltInRegistries.BLOCK.getKey(e.getPlacedBlock().getBlock()).toString(), e.getPos()); } catch (NoClassDefFoundError ignored) {} return; } // (his places in the trace too: placed minus broken is his litter) // what he put down, on his own record: a rule that undoes his placements never touches anything else
        if (e.getEntity() instanceof ServerPlayer p && !ClaudeEntity.isClaude(p)) {
            BlockPos pos = e.getPos();
            String blockId = BuiltInRegistries.BLOCK.getKey(e.getPlacedBlock().getBlock()).toString();
            log(p, new ActivityEvent.Placed(blockId, pos, System.currentTimeMillis()));
            try { PlayerTrace.block(p, "place", blockId, pos); } catch (NoClassDefFoundError ignored) {}
            if (p.level() instanceof ServerLevel slw) com.claudemod.entity.life.WatchBuild.userPlaced(p, slw, e); if (p.level() instanceof ServerLevel slc) com.claudemod.entity.life.CopyMode.userPlaced(p, slc, e); // (showing him: learned if he saw it) // (a build he's watching: learned if he saw it go in)
            com.claudemod.context.PlayerProfile.notePlaced(p, pos);
        }
    }

    public static void onCraft(PlayerEvent.ItemCraftedEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) {
            ItemStack crafted = e.getCrafting();
            if (crafted.isEmpty()) return;
            String id = BuiltInRegistries.ITEM.getKey(crafted.getItem()).toString();
            log(p, new ActivityEvent.Crafted(id, crafted.getCount(), System.currentTimeMillis()));
        }
    }

    public static void onSmelt(PlayerEvent.ItemSmeltedEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) {
            ItemStack smelted = e.getSmelting();
            if (smelted.isEmpty()) return;
            String id = BuiltInRegistries.ITEM.getKey(smelted.getItem()).toString();
            String device = ContainerWatch.currentFurnaceLabelFor(p.getUUID());
            if (device == null) device = "furnace";
            log(p, new ActivityEvent.Smelted(id, smelted.getCount(), device, System.currentTimeMillis()));
        }
    }

    public static void onChat(ServerChatEvent e) {
        ServerPlayer p = e.getPlayer();
        if (p == null) return;
        String raw = e.getRawText();
        if (raw == null || raw.isBlank()) return;
        String trimmed = raw.length() > CHAT_MAX_CHARS
                ? raw.substring(0, CHAT_MAX_CHARS) + "..."
                : raw;
        log(p, new ActivityEvent.Chat(trimmed.replace("\"", "'"), System.currentTimeMillis()));
        com.claudemod.llm.ClaudeApi.remember(p, p.getGameProfile().getName() + " (in chat)", raw);
        ChatListener.tryRespond(p, raw); // (their whole message: the 80-char cap is the activity log's; 15:22 "place a blo..." lost half a lesson step)
    }

    public static void onAdvancement(AdvancementEvent.AdvancementEarnEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) {
            String advId = e.getAdvancement().id().toString();
            log(p, new ActivityEvent.Advancement(advId, System.currentTimeMillis()));
        }
    }

    public static void onDeath(LivingDeathEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) {
            String cause = e.getSource().getMsgId();
            log(p, new ActivityEvent.Died(cause, System.currentTimeMillis()));
            return;
        }
        // Don't log when the player kills Claude -- he's not something that can be killed.
        if (ClaudeEntity.isClaude(e.getEntity())) return;
        if (e.getSource().getEntity() instanceof ServerPlayer p) {
            String entityId = entityId(e.getEntity());
            String source = e.getSource().getMsgId();
            log(p, new ActivityEvent.Killed(entityId, source, System.currentTimeMillis()));
        }
    }

    public static void onEat(LivingEntityUseItemEvent.Finish e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        ItemStack stack = e.getItem();
        if (stack.isEmpty()) return;
        if (stack.getItem().getFoodProperties(stack, p) == null) return;
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        log(p, new ActivityEvent.Ate(id, 1, System.currentTimeMillis()));
    }

    public static void onDamage(LivingDamageEvent.Post e) {
        if (e.getEntity() instanceof ServerPlayer p) {
            float dmg = e.getNewDamage();
            if (dmg >= 4.0f) {
                // Don't log "you took damage from Claude" -- the LLM would say "you took damage from me"
                Entity attacker = e.getSource().getEntity();
                if (ClaudeEntity.isClaude(attacker)) return;
                String src = damageSource(e.getSource().getMsgId(), attacker);
                log(p, new ActivityEvent.Damage(Math.round(dmg), src, System.currentTimeMillis()));
            }
        }
    }

    public static void onAttack(AttackEntityEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        Entity target = e.getTarget();
        if (!(target instanceof LivingEntity)) return;
        // Don't log attacks on Claude -- the LLM would confusingly narrate "you attacked me"
        if (ClaudeEntity.isClaude(target)) return;
        String weapon = p.getMainHandItem().isEmpty() ? "bare hands" : itemId(p.getMainHandItem());
        log(p, new ActivityEvent.Attacked(entityId(target), weapon, 1, System.currentTimeMillis()));
    }

    public static void onTrade(TradeWithVillagerEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        MerchantOffer offer = e.getMerchantOffer();
        String cost = formatStack(offer.getCostA());
        if (!offer.getCostB().isEmpty()) {
            cost += " and " + formatStack(offer.getCostB());
        }
        String result = formatStack(offer.getResult());
        log(p, new ActivityEvent.Traded(entityId(e.getAbstractVillager()), cost, result, System.currentTimeMillis()));
    }

    public static void onPickup(ItemEntityPickupEvent.Post e) {
        if (!(e.getPlayer() instanceof ServerPlayer p)) return;
        ItemStack original = e.getOriginalStack();
        ItemStack remaining = e.getCurrentStack();
        int pickedUp = original.getCount() - (remaining.isEmpty() ? 0 : remaining.getCount());
        if (pickedUp <= 0) return;
        log(p, new ActivityEvent.PickedUp(itemId(original), pickedUp, System.currentTimeMillis()));
    }

    public static void onDrop(ItemTossEvent e) {
        if (!(e.getPlayer() instanceof ServerPlayer p)) return;
        ItemEntity itemEntity = e.getEntity();
        ItemStack stack = itemEntity.getItem();
        if (stack.isEmpty()) return;
        log(p, new ActivityEvent.Dropped(itemId(stack), stack.getCount(), System.currentTimeMillis()));
    }

    public static void onFish(ItemFishedEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        if (e.getDrops().isEmpty()) return;
        String items = e.getDrops().stream()
                .filter(stack -> !stack.isEmpty())
                .map(PlayerActivityListener::formatStack)
                .reduce((a, b) -> a + ", " + b)
                .orElse("nothing");
        log(p, new ActivityEvent.Fished(items, System.currentTimeMillis()));
    }

    public static void onSetSpawn(PlayerSetSpawnEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        BlockPos spawn = e.getNewSpawn();
        if (spawn == null) return;
        String level = e.getSpawnLevel().location().toString();
        log(p, new ActivityEvent.SetSpawn(spawn, level, e.isForced(), System.currentTimeMillis()));
    }

    public static void onSleepAttempt(CanPlayerSleepEvent e) {
        ServerPlayer p = e.getEntity();
        BedSleepingProblem problem = e.getProblem();
        String result = problem == null ? "allowed" : problem.name().toLowerCase();
        log(p, new ActivityEvent.SleepAttempt(e.getPos(), result, System.currentTimeMillis()));
    }

    private static void log(ServerPlayer player, ActivityEvent event) {
        if (ClaudeEntity.isClaude(player)) return; // his own doings are tracked by his brain, not as "what the player did"
        ServerLevel level = player.serverLevel();
        BlockPos pos = player.blockPosition();
        Holder<Biome> biomeHolder = level.getBiome(pos);
        String biome = biomeHolder.unwrapKey()
                .map(key -> key.location().toString())
                .orElse("unknown");
        String dim = level.dimension().location().toString();
        ActivityEvent seen = com.claudemod.entity.life.Whereabouts.asPerceived(player, new ActivityEvent.Located(event, dim, biome, pos)); // (only what he saw them do, or what's in everyone's chat)
        if (seen != null) PlayerActivityTracker.log(player.getUUID(), seen);
    }

    private static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static String formatStack(ItemStack stack) {
        if (stack.isEmpty()) return "nothing";
        return stack.getCount() + " " + ActivityEvent.friendly(itemId(stack));
    }

    private static String entityId(Entity entity) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
    }

    private static String damageSource(String msgId, Entity attacker) {
        if (attacker == null) return msgId;
        return msgId + " by " + entityId(attacker);
    }
}
