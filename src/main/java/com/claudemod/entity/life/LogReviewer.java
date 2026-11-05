package com.claudemod.entity.life;

import com.claudemod.ClaudeMod;
import com.claudemod.entity.ClaudeEntity;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.concurrent.CompletableFuture;

/**
 * Every 5 minutes of play the cheap model reads his log since the last read and answers "what would an expert player not have done,
 * or done faster?"; each finding is a REVIEW incident (with the flight recorder). The user 11-05. About $0.07 an hour on Haiku
 * (replay of World 17, 23:00-00:05: 57 findings, $0.078 for 1.08 h). Off with the pref log_review=off.
 */
public final class LogReviewer {
    static final int EVERY = 20 * 300, MIN_LINES = 30, KEEP = 1500, MAX_FINDINGS = 6, REMEMBER = 30;
    private static final String[] SKIP = {"[Claude pack]", "[Claude read]", "[Claude plan] thinking", "[Claude test]", "[Claude incident] REVIEW", "[Claude review]"};
    private static final java.util.ArrayDeque<String> buf = new java.util.ArrayDeque<>();
    private static final java.util.ArrayDeque<String> reported = new java.util.ArrayDeque<>();
    private static final java.util.concurrent.ConcurrentLinkedQueue<JsonObject> found = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static volatile boolean inFlight;
    private static int lastAt = Integer.MIN_VALUE;
    /** The model call (stages swap in a fake; null = the real one, never made while testing). */
    public static java.util.function.Function<String, CompletableFuture<String>> call = null;
    public static String lastAsked = "";

    /** One [Claude ...] log line (from MindLog's appender). */
    public static void add(String time, String m) {
        for (String s : SKIP) if (m.startsWith(s)) return;
        synchronized (buf) { buf.addLast(time + " " + (m.length() > 260 ? m.substring(0, 260) : m)); while (buf.size() > KEEP) buf.removeFirst(); }
    }

    public static void tick(ClaudeEntity g) {
        for (JsonObject f; (f = found.poll()) != null; ) {
            StringBuilder ls = new StringBuilder(); for (var l : f.getAsJsonArray("lines")) ls.append("\n    | ").append(l.getAsString());
            Watchdog.incident(g, "REVIEW", f.get("what").getAsString() + "\n  expert: " + f.get("expert").getAsString() + ls);
        }
        if (inFlight || g.pref("log_review", "on").equals("off") || (com.claudemod.entity.ClaudeSpawner.testing && call == null)) return;
        if (lastAt == Integer.MIN_VALUE || g.tickCount < lastAt) { lastAt = g.tickCount; return; } // (a new body: the clock starts here)
        if (g.tickCount - lastAt < EVERY) return;
        lastAt = g.tickCount;
        String window;
        synchronized (buf) { if (buf.size() < MIN_LINES) return; window = String.join("\n", buf); buf.clear(); }
        String user;
        synchronized (reported) { user = (reported.isEmpty() ? "" : "Already reported:\n- " + String.join("\n- ", reported) + "\n\n") + "His log:\n" + window; }
        lastAsked = user; inFlight = true;
        (call != null ? call : (java.util.function.Function<String, CompletableFuture<String>>) u -> com.claudemod.llm.ClaudeApi.review(prompt(), u)).apply(user).whenComplete((text, ex) -> {
            try {
                if (ex != null) { ClaudeMod.LOGGER.info("[Claude review] the call failed: {}", ex.toString()); return; }
                var fs = JsonParser.parseString(text).getAsJsonObject().getAsJsonArray("findings"); int n = 0;
                for (var e : fs) { if (n++ >= MAX_FINDINGS) break; JsonObject f = e.getAsJsonObject(); found.add(f); synchronized (reported) { reported.addLast(f.get("what").getAsString()); while (reported.size() > REMEMBER) reported.removeFirst(); } }
                ClaudeMod.LOGGER.info("[Claude review] {} finding(s) in {} lines", Math.min(n, MAX_FINDINGS), window.split("\n").length);
            } catch (RuntimeException e) { ClaudeMod.LOGGER.info("[Claude review] unreadable answer: {}", e.toString()); }
            finally { inFlight = false; }
        });
    }

    /** Stages: due now again, the memory of what was reported kept. */
    public static void reset2(ClaudeEntity g) { lastAt = g.tickCount - EVERY; }
    /** Stages: the clock to "due now", the buffer and memory empty. */
    public static void reset(ClaudeEntity g) { lastAt = g.tickCount - EVERY; inFlight = false; found.clear(); synchronized (buf) { buf.clear(); } synchronized (reported) { reported.clear(); } }

    private static String prompt;
    static String prompt() {
        if (prompt == null) try (var in = LogReviewer.class.getResourceAsStream("/claude/review_prompt.md")) { prompt = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8); } catch (Exception e) { throw new IllegalStateException("review_prompt.md: " + e); }
        return prompt;
    }
}
