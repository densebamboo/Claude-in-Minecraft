package com.claudemod.commands;

import com.claudemod.context.PlayerContext;
import com.claudemod.context.PlayerProfile;
import com.claudemod.director.ClaudeLife;
import com.claudemod.entity.ClaudeEntity;
import com.claudemod.entity.ClaudeSpawner;
import com.claudemod.llm.ChatListener;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

public final class ClaudeCommands {
    private ClaudeCommands() {}

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(
            LiteralArgumentBuilder.<CommandSourceStack>literal("claude")
                .then(Commands.literal("ask")
                    .then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            String message = StringArgumentType.getString(ctx, "message");
                            player.sendSystemMessage(Component.literal("<" + player.getGameProfile().getName() + "> " + message));
                            ChatListener.respond(player, message, true);
                            return 1;
                        })))
                .then(Commands.literal("summon")
                    .executes(ctx -> {
                        ServerPlayer player = ctx.getSource().getPlayerOrException();
                        var g = ClaudeEntity.activeFor(player);
                        if (g.isPresent()) ClaudeLife.relocateNear(g.get(), player, 3, 6);
                        else if (ClaudeEntity.any(player.getServer()).isEmpty()) ClaudeSpawner.join(player.getServer(), player, null);
                        return 1;
                    }))
                .then(Commands.literal("stop")
                    .executes(ctx -> {
                        ServerPlayer player = ctx.getSource().getPlayerOrException();
                        ClaudeEntity.activeFor(player).ifPresent(g -> {
                            g.tasks.clear();
                            g.skillQueue.clear();
                            g.commandActive = false;
                            g.playerGoal = "";
                            g.goal = "";
                            g.getNavigation().stop();
                        });
                        gray(player, "claude stopped what he was doing");
                        return 1;
                    }))
                .then(Commands.literal("where")
                    .executes(ctx -> {
                        ServerPlayer player = ctx.getSource().getPlayerOrException();
                        var found = ClaudeEntity.activeFor(player);
                        if (found.isEmpty()) {
                            gray(player, "claude is not in the world near you (try /claude summon)");
                            return 1;
                        }
                        ClaudeEntity g = found.get();
                        gray(player, "claude at " + g.blockPosition().toShortString() + " (" + (int) g.distanceTo(player) + " blocks), "
                                + g.skillQueue.size() + " plan steps, " + g.tasks.size() + " actions queued"
                                + (g.goal.isBlank() ? "" : ", goal: " + g.goal));
                        gray(player, "inventory: " + g.inventory.summary());
                        if (!g.prefs.isEmpty()) gray(player, "standing instructions: " + g.prefs);
                        var base = PlayerProfile.baseOrigin(player);
                        gray(player, "his place: " + (base == null ? "not started" : base.toShortString()));
                        return 1;
                    }))
                .then(Commands.literal("context")
                    .requires(src -> src.hasPermission(2))
                    .executes(ctx -> {
                        ServerPlayer player = ctx.getSource().getPlayerOrException();
                        for (String line : PlayerContext.buildPromptBlock(player).split("\n")) gray(player, line);
                        return 1;
                    }))
        );
    }

    private static void gray(ServerPlayer player, String text) {
        player.sendSystemMessage(Component.literal(text).withStyle(ChatFormatting.GRAY));
    }
}
