package com.claudemod.entity.life;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

/** His own debug log, the [Claude ...] lines, kept as his short-term memory: the planner and chat see what he did, what failed and why (03:32 he left two diamonds "no way to it" and then told them he'd seen none). */
public final class MindLog {
    private static final int KEEP = 60;
    private static final java.util.ArrayDeque<String> lines = new java.util.ArrayDeque<>();
    // his inventory dump and position ping are given to him separately; his senses spam every second
    private static final String[] SKIP = {"[Claude pack]", "[Claude where]", "[Claude read]", "[Claude plan] thinking", "[Claude test]"};

    public static void install() {
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        AbstractAppender app = new AbstractAppender("ClaudeMind", null, null, true, new Property[0]) {
            @Override public void append(LogEvent e) {
                if (!e.getLoggerName().startsWith("com.claudemod")) return;
                String m = e.getMessage().getFormattedMessage();
                if (!m.startsWith("[Claude ") && !m.startsWith("[Claude]")) return;
                for (String s : SKIP) if (m.startsWith(s)) return;
                String t = java.time.LocalTime.ofInstant(java.time.Instant.ofEpochMilli(e.getTimeMillis()), java.time.ZoneId.systemDefault()).withNano(0).toString();
                String line = t + " " + (m.length() > 220 ? m.substring(0, 220) + "..." : m);
                synchronized (lines) { lines.addLast(line); while (lines.size() > KEEP) lines.removeFirst(); }
                if (!m.startsWith("[Claude conflict]") && !m.startsWith("[Claude ore] skipping") && !m.startsWith("[Claude ore] saw") && !m.contains("spotted ") && !m.startsWith("[Claude plan]") && !m.contains("nothing to pick up round") && !m.contains("its next round when he passes") && !m.startsWith("[Claude life]   (his mine as he knows it")) watch(m, e.getTimeMillis()); // (routine re-scans and plans repeat by design: not a fight)
            }
        };
        app.start();
        ctx.getConfiguration().addAppender(app);
        ctx.getConfiguration().getRootLogger().addAppender(app, null, null);
        ctx.updateLoggers();
    }

    /** The newest line naming this spot that isn't a dig/skip echo: the rule that refused the job (for a conflict line). */
    public static String lastAbout(String spot) {
        synchronized (lines) {
            var it = lines.descendingIterator();
            while (it.hasNext()) { String l = it.next(); if (l.contains(spot) && !l.contains("[Claude dig]") && !l.contains("[Claude conflict]") && !l.contains("it failed there")) return l.substring(Math.min(l.length(), 9)); }
            return "(no line names it)";
        }
    }

    /** Two of his rules fighting over one spot: one keeps queuing the job, another keeps refusing it. On the 3rd refusal in 60 s it says so once, in one line (the user: "whenever he's having a conflict, have the bot himself tell you somehow, in the cheapest way possible"). */
    public static void refused(com.claudemod.entity.ClaudeEntity g, net.minecraft.core.BlockPos pos, String job) {
        if (job.contains("doBreak line 1556")) return; // (the block in front broken first, then this: progress, not a fight)
        String k = "conflict:" + pos.asLong(), kt = "conflictT:" + pos.asLong();
        Integer t0 = g.attempts.counters.get(kt);
        if (t0 == null || g.tickCount - t0 > 20 * 60) { g.attempts.counters.put(kt, g.tickCount); g.attempts.counters.put(k, 0); }
        int n = g.attempts.counters.merge(k, 1, Integer::sum);
        if (n == 3) Watchdog.conflict(g, pos, job); // (the watchdog's second signal: three refusals of the same thing)
        if (n == 3) com.claudemod.ClaudeMod.LOGGER.info("[Claude conflict] {} at {}: queued again and refused 3 times in {} s; step: {} ({}); refused by: {}",
                job, pos.toShortString(), (g.tickCount - g.attempts.counters.get(kt)) / 20, g.lastStep == null ? "-" : g.lastStep.skill() + " " + g.lastStep.target(), g.tasks.size() + " queued", lastAbout(pos.toShortString()));
    }

    // any conflict: the same thing (same spot, same words) 3 times in 60 s is two of his rules undoing each other, or one retrying what can't work
    private static final java.util.Map<String, java.util.ArrayDeque<Long>> seen = new java.util.HashMap<>();
    private static final java.util.List<String> pending = new java.util.ArrayList<>();
    private static String prev = "", prevKey = "";
    private static void watch(String m, long now) {
        String key = m.replaceAll("\\([^()]*\\)", "").replaceAll("\\d+ ?s ago|after \\d+ ticks|\\d+ ticks|\\d+ s\\b|\\d+s\\b", "#").trim(); // (the timings and the "(he's at ...)" asides change; the spot and the words don't)
        if (!key.matches(".*-?\\d+, -?\\d+, -?\\d+.*")) return; // a conflict is about a spot: a line naming none (a fall waited out, "made a sword") is just the same news again
        synchronized (seen) {
            if (seen.size() > 2000) seen.clear();
            var q = seen.computeIfAbsent(key, k -> new java.util.ArrayDeque<>());
            while (!q.isEmpty() && now - q.peekFirst() > 60_000) q.removeFirst();
            q.addLast(now);
            if (q.size() == 3) pending.add("[Claude conflict] 3 times in " + (now - q.peekFirst()) / 1000 + " s: " + (m.length() > 200 ? m.substring(0, 200) : m) + " | just before: " + (prev.length() > 200 ? prev.substring(0, 200) : prev));
            if (!key.equals(prevKey)) { prev = m; prevKey = key; }
        }
    }
    /** Said from the server tick (never from inside the logger). */
    public static void flush() {
        java.util.List<String> out;
        synchronized (seen) { if (pending.isEmpty()) return; out = new java.util.ArrayList<>(pending); pending.clear(); }
        for (String l : out) com.claudemod.ClaudeMod.LOGGER.info(l);
    }

    /** The last lines of his log, oldest first, for a prompt. */
    public static String recent() {
        synchronized (lines) {
            if (lines.isEmpty()) return "";
            // the user 2026-11-01: he killed the enderman they'd pointed at, then asked "where's it at?": the kill sat mid-log and lost to the open order. Fights he just finished go first.
            java.time.LocalTime cut = java.time.LocalTime.now().minusMinutes(3); StringBuilder done = new StringBuilder();
            for (String l : lines) if (l.contains("[Claude fight]") && (l.contains("'s down") || l.contains("killed") || l.contains(" dead")) && l.length() > 8 && !java.time.LocalTime.parse(l.substring(0, 8)).isBefore(cut)) done.append(l).append("\n");
            String head = done.length() == 0 ? "" : "JUST DONE (fights you finished in the last 3 minutes: that mob is DEAD, never ask where it is; tell them you got it):\n" + done;
            return head + "YOUR RECENT LOG (your own debug log, what you just did, what failed and why; trust it over guesses, e.g. a drop you \"left behind\" is still lying there):\n" + String.join("\n", lines) + "\n";
        }
    }
}
