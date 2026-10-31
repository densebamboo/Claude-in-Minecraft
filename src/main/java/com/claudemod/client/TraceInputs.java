package com.claudemod.client;

import java.util.Locale;

/**
 * The real player's keys, read from the client (integrated server only: the recorder runs on the server thread of the same
 * game). Only ever loaded on the client dist (PlayerTrace checks FMLEnvironment first), so a dedicated server never sees it.
 */
public final class TraceInputs {
    private TraceInputs() {}

    /** The local player's keys as JSON fields, or null when the traced player isn't the one at this keyboard. */
    public static String read(String name) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        var lp = mc.player; if (lp == null || !lp.getGameProfile().getName().equals(name)) return null;
        var in = lp.input; var o = mc.options;
        if (in == null || o == null) return null; // (the first ticks after joining: the client's input isn't set up yet; reading it crashed the server at 01:16)
        return String.format(Locale.ROOT, "\"fwd\":%.2f,\"strafe\":%.2f,\"jump\":%b,\"sneak\":%b,\"sprint\":%b,\"attack\":%b,\"use\":%b",
                in.forwardImpulse, in.leftImpulse, in.jumping, in.shiftKeyDown, o.keySprint.isDown() || lp.isSprinting(), o.keyAttack.isDown(), o.keyUse.isDown());
    }
}
