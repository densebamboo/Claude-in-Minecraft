package com.claudemod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.mojang.authlib.GameProfile;

import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;

/** Claude wears his own orange skin (the game would otherwise give a player with no skin server Steve or Alex). */
@Mixin(PlayerInfo.class)
public abstract class ClaudeSkinMixin {
    private static final PlayerSkin CLAUDE_SKIN = new PlayerSkin(
            ResourceLocation.fromNamespaceAndPath("claude", "textures/entity/claude.png"), null, null, null, PlayerSkin.Model.WIDE, false);

    @Shadow public abstract GameProfile getProfile();

    @Inject(method = "getSkin", at = @At("HEAD"), cancellable = true)
    private void claude$skin(CallbackInfoReturnable<PlayerSkin> cir) {
        if ("Claude".equals(getProfile().getName())) cir.setReturnValue(CLAUDE_SKIN);
    }
}
