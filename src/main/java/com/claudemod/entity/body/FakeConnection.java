package com.claudemod.entity.body;

import java.lang.reflect.Field;

import javax.annotation.Nullable;

import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

/**
 * Claude's "network connection": there is no client on the other end, so everything the server sends him is
 * dropped. An embedded channel makes it look open (so the server treats him as connected) and lets a normal
 * disconnect close it, which runs the usual leave-the-game cleanup.
 */
public final class FakeConnection extends Connection {
    public FakeConnection() {
        super(PacketFlow.SERVERBOUND);
        try {
            Field channel = Connection.class.getDeclaredField("channel");
            channel.setAccessible(true);
            channel.set(this, new EmbeddedChannel());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Claude: couldn't set up his fake connection", e);
        }
    }

    @Override
    public void send(Packet<?> packet, @Nullable PacketSendListener listener, boolean flush) {}

    @Override
    public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> protocolInfo, T listener) {}

    @Override
    public void setupOutboundProtocol(ProtocolInfo<?> protocolInfo) {}

    @Override
    public void setReadOnly() {}
}
