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
    static final int EVERY = 20 * 300, MIN_LINES = 30, KEEP = 1500, MAX_FINDINGS = 6, REMEMBER = 30, SAME = 3, MAX_CHARS = 60000;
    private static final String[] SKIP = {"[Claude pack]", "[Claude read]", "[Claude plan] thinking", "[Claude test]", "[Claude incident] REVIEW", "[Claude review]", "[Claude move] spin", "[Claude where]   task age"};
    /** Lines per window that only differ in their numbers: SAME kept, the rest counted (a loop shows as 3 lines and "x40"). */
    private static final java.util.Map<String, Integer> same = new java.util.LinkedHashMap<>();
    private static int added, whereN;
    /** Lines plain code writes when it flags something itself: a finding resting only on them is already on file. */
    private static final String[] PLAIN = {"[Claude incident]", "[Claude litter]", "[Claude conflict]", "[Claude move] spin"};
    private static final java.util.regex.Pattern SPOT = java.util.regex.Pattern.compile("(-?\\d+), ?(-?\\d+), ?(-?\\d+)");
    /** Spots findings named this hour: {times, first tick}. */
    private static final java.util.Map<String, int[]> seen = new java.util.HashMap<>();
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
        if (m.startsWith("[Claude leap]") && m.contains("take-off at")) return; // (the jump line before and the landing after say it)
        String line = m.length() > 260 ? m.substring(0, 260) : m;
        synchronized (buf) {
            added++;
            if (m.startsWith("[Claude where]") && whereN++ % 3 != 0) return; // (every 30 s is enough to follow him; every 10 s was a fifth of the bill)
            String key = line.replaceAll("-?\\d+", "#");
            if (!m.startsWith("[Claude where]") && same.merge(key, 1, Integer::sum) > SAME) return; // (his where lines are thinned above, never folded: they are how a reader follows him)
            buf.addLast(time + " " + line); while (buf.size() > KEEP) buf.removeFirst(); }
    }

    public static void tick(ClaudeEntity g) {
        for (JsonObject f; (f = found.poll()) != null; ) {
            String what = f.get("what").getAsString();
            StringBuilder ls = new StringBuilder(); boolean plain = true, any = false;
            for (var l : f.getAsJsonArray("lines")) { String l0 = l.getAsString(); ls.append("\n    | ").append(l0); any = true; boolean p0 = false; for (String k : PLAIN) if (l0.contains(k)) p0 = true; if (!p0) plain = false; }
            if (any && plain) { ClaudeMod.LOGGER.info("[Claude review] not filed, plain code flagged it already: {}", what); continue; } // (its incident is on file; the user 11-05: half the findings restated detector lines)
            java.util.List<String> spots = new java.util.ArrayList<>(); for (var m = SPOT.matcher(what); m.find(); ) spots.add(m.group(1) + "," + m.group(2) + "," + m.group(3));
            int times = 0; for (String sp : spots) { int[] v = seen.get(sp); if (v != null && g.tickCount - v[1] < 20 * 3600) times = Math.max(times, v[0]); }
            for (String sp : spots) { int[] v = seen.get(sp); if (v == null || g.tickCount - v[1] >= 20 * 3600) seen.put(sp, new int[] {1, g.tickCount}); else v[0]++; }
            if (times == 2) { Watchdog.incident(g, "REVIEW_STILL", what + " (the same spot in 3 reviews within the hour: still not fixed)" + ls); continue; } // one loud line for an issue that stays, not a quiet re-file every pass
            if (times > 0) { ClaudeMod.LOGGER.info("[Claude review] not filed again, the same spot as a finding {} time(s) this hour: {}", times, what); continue; }
            Watchdog.incident(g, "REVIEW", what + "\n  expert: " + f.get("expert").getAsString() + ls);
        }
        if (inFlight || g.pref("log_review", "on").equals("off") || (com.claudemod.entity.ClaudeSpawner.testing && call == null)) return;
        if (lastAt == Integer.MIN_VALUE || g.tickCount < lastAt) { lastAt = g.tickCount; return; } // (a new body: the clock starts here)
        if (g.tickCount - lastAt < EVERY) return;
        lastAt = g.tickCount;
        String window;
        synchronized (buf) {
            if (added < MIN_LINES) return;
            StringBuilder w = new StringBuilder(String.join("\n", buf));
            for (var e : same.entrySet()) if (e.getValue() > SAME) w.append("\n(").append(e.getValue() - SAME).append(" more lines like this, other numbers: ").append(e.getKey()).append(")");
            window = w.length() > MAX_CHARS ? "(older lines cut)\n" + w.substring(w.length() - MAX_CHARS) : w.toString();
            buf.clear(); same.clear(); added = 0;
        }
        String user;
        synchronized (reported) { user = (reported.isEmpty() ? "" : "Already reported:\n- " + String.join("\n- ", reported) + "\n\n") + "His log:\n" + window; }
        lastAsked = user; inFlight = true; long sent = System.currentTimeMillis(); int lines = window.split("\n").length;
        (call != null ? call : (java.util.function.Function<String, CompletableFuture<String>>) u -> com.claudemod.llm.ClaudeApi.review(prompt(), u)).apply(user).whenComplete((text, ex) -> {
            try {
                if (ex != null) { ClaudeMod.LOGGER.info("[Claude review] the call failed after {} s ({} lines, {} chars): {}", (System.currentTimeMillis() - sent) / 1000, lines, user.length(), ex.toString()); return; }
                var fs = JsonParser.parseString(text).getAsJsonObject().getAsJsonArray("findings"); int n = 0;
                for (var e : fs) { if (n++ >= MAX_FINDINGS) break; JsonObject f = e.getAsJsonObject(); found.add(f); synchronized (reported) { reported.addLast(f.get("what").getAsString()); while (reported.size() > REMEMBER) reported.removeFirst(); } }
                ClaudeMod.LOGGER.info("[Claude review] {} finding(s) in {} lines ({} chars, {} s)", Math.min(n, MAX_FINDINGS), lines, user.length(), (System.currentTimeMillis() - sent) / 1000);
            } catch (RuntimeException e) { ClaudeMod.LOGGER.info("[Claude review] unreadable answer: {}", e.toString()); }
            finally { inFlight = false; }
        });
    }

    /** Stages: due now again, the memory of what was reported kept. */
    public static void reset2(ClaudeEntity g) { lastAt = g.tickCount - EVERY; }
    /** Stages: the clock to "due now", the buffer and memory empty. */
    public static void reset(ClaudeEntity g) { lastAt = g.tickCount - EVERY; inFlight = false; found.clear(); seen.clear(); synchronized (buf) { buf.clear(); same.clear(); added = 0; whereN = 0; } synchronized (reported) { reported.clear(); } }

    private static String prompt;
    static String prompt() {
        if (prompt == null) try (var in = LogReviewer.class.getResourceAsStream("/claude/review_prompt.md")) { prompt = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8); } catch (Exception e) { throw new IllegalStateException("review_prompt.md: " + e); }
        return prompt;
    }
}
