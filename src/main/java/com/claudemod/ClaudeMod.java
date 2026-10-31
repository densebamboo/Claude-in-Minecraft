package com.claudemod;

import org.slf4j.Logger;

import com.claudemod.commands.ClaudeCommands;
import com.claudemod.context.ContainerWatch;
import com.claudemod.context.PlayerActivityListener;
import com.claudemod.director.ClaudeChat;
import com.claudemod.entity.ClaudeSpawner;
import com.claudemod.llm.PersonaLoader;
import com.mojang.logging.LogUtils;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;

@Mod(ClaudeMod.MODID)
public class ClaudeMod {
    public static final String MODID = "claude";
    public static final Logger LOGGER = LogUtils.getLogger();

    public ClaudeMod(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::commonSetup);
        NeoForge.EVENT_BUS.register(this);
        NeoForge.EVENT_BUS.addListener(ClaudeCommands::register);
        NeoForge.EVENT_BUS.addListener(com.claudemod.entity.life.Perception::onSoundAt); // what he hears
        NeoForge.EVENT_BUS.addListener(com.claudemod.entity.life.Perception::onSoundFrom);
        NeoForge.EVENT_BUS.addListener(com.claudemod.entity.life.Whereabouts::onBreak); // a block they break in earshot (a level event, not a sound)
        NeoForge.EVENT_BUS.addListener(com.claudemod.entity.life.Whereabouts::onPlace);
        NeoForge.EVENT_BUS.addListener(com.claudemod.entity.life.OwnEvents::onBreak); // his own events: what he mined, picked up, put down
        NeoForge.EVENT_BUS.addListener(com.claudemod.entity.life.OwnEvents::onPickup);
        NeoForge.EVENT_BUS.addListener(com.claudemod.entity.life.OwnEvents::onPlace);
        NeoForge.EVENT_BUS.addListener(com.claudemod.context.PlayerTrace::onTick); // how the real player plays, recorded (to model him after them)
        NeoForge.EVENT_BUS.addListener(com.claudemod.context.PlayerTrace::onAttack);
        NeoForge.EVENT_BUS.addListener(com.claudemod.context.PlayerTrace::onRightClickBlock);
        NeoForge.EVENT_BUS.addListener(com.claudemod.context.PlayerTrace::onRightClickItem);
        NeoForge.EVENT_BUS.addListener(com.claudemod.context.PlayerTrace::onLeftClickBlock); // digs started, stopped, aborted
        NeoForge.EVENT_BUS.addListener(com.claudemod.context.PlayerTrace::onChat); // "mark ..." lines: the windows to cut
        NeoForge.EVENT_BUS.addListener(PlayerActivityListener::onBlockBreak);
        NeoForge.EVENT_BUS.addListener(PlayerActivityListener::onBlockPlace);
        NeoForge.EVENT_BUS.addListener(PlayerActivityListener::onCraft);
        NeoForge.EVENT_BUS.addListener(PlayerActivityListener::onSmelt);
        NeoForge.EVENT_BUS.addListener(PlayerActivityListener::onChat);
        NeoForge.EVENT_BUS.addListener(PlayerActivityListener::onAdvancement);
        NeoForge.EVENT_BUS.addListener(PlayerActivityListener::onDeath);
        NeoForge.EVENT_BUS.addListener(PlayerActivityListener::onDamage);
        NeoForge.EVENT_BUS.addListener(PlayerActivityListener::onEat);
        NeoForge.EVENT_BUS.addListener(PlayerActivityListener::onAttack);
        NeoForge.EVENT_BUS.addListener(PlayerActivityListener::onTrade);
        NeoForge.EVENT_BUS.addListener(PlayerActivityListener::onPickup);
        NeoForge.EVENT_BUS.addListener(PlayerActivityListener::onDrop);
        NeoForge.EVENT_BUS.addListener(PlayerActivityListener::onFish);
        NeoForge.EVENT_BUS.addListener(PlayerActivityListener::onSetSpawn);
        NeoForge.EVENT_BUS.addListener(PlayerActivityListener::onSleepAttempt);
        NeoForge.EVENT_BUS.addListener(ContainerWatch::onOpen);
        NeoForge.EVENT_BUS.addListener(ContainerWatch::onClose);
        NeoForge.EVENT_BUS.addListener(ClaudeChat::onServerTick);
        NeoForge.EVENT_BUS.addListener(ClaudeSpawner::onServerTick);
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        com.claudemod.entity.life.MindLog.install();
        LOGGER.info("[Claude] common setup. LLM enabled: {}, model: {}",
                Config.ENABLE_LLM.get(), Config.CHAT_MODEL.get() + " (chat) / " + Config.PLAN_MODEL.get() + " (planning)");
        if (Config.ENABLE_LLM.get() && Config.ANTHROPIC_API_KEY.get().isBlank()) {
            LOGGER.warn("[Claude] the AI is on but anthropicApiKey is empty. "
                    + "Set it in config/claude-common.toml.");
        }
        PersonaLoader.ensureExists();
        LOGGER.info("[Claude] persona file: {}", PersonaLoader.personaFile());
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("[Claude] server starting.");
        com.claudemod.context.PlayerProfile.clear();
        ClaudeSpawner.clear();
    }
}
