package com.claudemod.entity.body;

import javax.annotation.Nullable;

import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/**
 * The server side of Claude's connection. Packets "to Claude" go nowhere (no client), which also skips
 * NeoForge's check that a client registered for modded packets (a fake client never registers any).
 */
public final class FakeGameListener extends ServerGamePacketListenerImpl {
    public FakeGameListener(MinecraftServer server, Connection connection, ServerPlayer player, CommonListenerCookie cookie) {
        super(server, connection, player, cookie);
    }

    @Override
    public void send(Packet<?> packet, @Nullable PacketSendListener listener) {}
}
