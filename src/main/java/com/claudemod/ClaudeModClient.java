package com.claudemod;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/** Client side: just the config screen. Claude is a player, so the game draws him like one (skin: ClaudeSkinMixin). */
@Mod(value = ClaudeMod.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = ClaudeMod.MODID, value = Dist.CLIENT)
public class ClaudeModClient {
    public ClaudeModClient(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    private static boolean autoWorldDone;

    /** Dev run only (-Dclaude.autoWorld=<name>, build.gradle live): quick play can't make a world, so this does it once at the menu (Survival, Normal, cheats on) for unattended restarts. */
    @SubscribeEvent
    static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        String name = System.getProperty("claude.autoWorld");
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (name == null || autoWorldDone || mc.level != null || mc.getOverlay() != null || mc.screen == null) return;
        autoWorldDone = true;
        if (mc.getLevelSource().levelExists(name)) return; // quick play opens it
        ClaudeMod.LOGGER.info("[Claude] creating the world {} (claude.autoWorld)", name);
        var settings = new net.minecraft.world.level.LevelSettings(name, net.minecraft.world.level.GameType.SURVIVAL, false, net.minecraft.world.Difficulty.NORMAL, true,
                new net.minecraft.world.level.GameRules(), net.minecraft.world.level.WorldDataConfiguration.DEFAULT);
        mc.createWorldOpenFlows().createFreshLevel(name, settings, net.minecraft.world.level.levelgen.WorldOptions.defaultWithRandomSeed(),
                ra -> ra.registryOrThrow(net.minecraft.core.registries.Registries.WORLD_PRESET).getHolderOrThrow(net.minecraft.world.level.levelgen.presets.WorldPresets.NORMAL).value().createWorldDimensions(), mc.screen);
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        ClaudeMod.LOGGER.info("[Claude] client setup complete.");
    }
}
