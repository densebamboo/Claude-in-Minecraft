package com.claudemod;

import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** A server tick that runs long: the server thread's stack logged while it is stuck, so a stall names its own cause (D2D 10:24-10:30 11-10: 32-49 s stalls with nothing in the log). */
public final class LagWatch {
    private static volatile long tickStart; private static volatile Thread server; private static volatile int tick; private static Thread watcher;
    private LagWatch() {}

    public static void onTickPre(ServerTickEvent.Pre e) {
        tickStart = System.nanoTime(); tick = e.getServer().getTickCount();
        server = Thread.currentThread(); if (watcher == null) start(); // (a world reopened runs a new server thread)
    }
    private static long lastSlowLog;
    public static void onTickPost(ServerTickEvent.Post e) {
        long t0 = tickStart; tickStart = 0; if (t0 == 0) return; long ms = (System.nanoTime() - t0) / 1_000_000;
        if (ms >= 1000 && System.currentTimeMillis() - lastSlowLog > 10_000) { lastSlowLog = System.currentTimeMillis(); ClaudeMod.LOGGER.warn("[Claude lag] tick {} took {} ms", tick, ms); } // (the stack comes from the watcher only past 3 s)
    }

    private static synchronized void start() {
        if (watcher != null) return;
        watcher = new Thread(() -> {
            int loggedTick = -1, samples = 0;
            while (true) {
                try { Thread.sleep(250); } catch (InterruptedException ie) { return; }
                long t0 = tickStart; Thread s = server; if (t0 == 0 || s == null) continue;
                long ms = (System.nanoTime() - t0) / 1_000_000; int tk = tick;
                if (tk != loggedTick) { loggedTick = tk; samples = 0; }
                if (ms < 1500 || samples >= 4 || ms < 1500L * (1L << samples)) continue; // a sample at 1.5, 3, 6 and 12 s into one tick (at 3 s the 2.2 s ticks went unsampled, live 14:15:24 11-10)
                samples++;
                StringBuilder sb = new StringBuilder(); int n = 0;
                for (StackTraceElement el : s.getStackTrace()) { if (n++ >= 40) break; sb.append("\n    at ").append(el); }
                ClaudeMod.LOGGER.warn("[Claude lag] tick {} has run {} ms on the server thread; it is at:{}", tk, ms, sb);
            }
        }, "claude-lagwatch");
        watcher.setDaemon(true); watcher.start();
    }
}
