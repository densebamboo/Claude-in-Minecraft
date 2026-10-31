package com.claudemod.entity.life;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.claudemod.ClaudeMod;
import com.claudemod.entity.ClaudeEntity;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Copy mode (the user, 2026-10-30, teaching him obsidian live): "I'll show you", "watch me", "do what I do", "copy me" start it.
 * He stops his own work and watches them, as WatchBuild does a build. Each step they do in his sight is learned as primitive moves
 * relative to where the demo started (the anchor, their feet cell then): the exact cell they stood on and their facing (go_to
 * x y z facing yaw pitch, where "exact"), then the act (place_at x y z block / dig_at x y z). A step they name ("stand right here")
 * is learned the same way. A block they put down and picked straight back up is a step SHOWN: the place is what to copy.
 * "now you": he does the steps since the last cue himself, through his ordinary body (ExactSpot's walk: their cell, never shared,
 * no pushing; Primitives' place_at / dig_at: reach, sight, the shell guard, lava guards, the litter rule), then watches again.
 * "ok" / "got it" / "your turn": the demo is saved per bot in LearnedStore (kind SKILL, phase 2 of the primitives plan) and he does
 * whatever of it he hasn't done yet. A sequence from anywhere else (a recorded player trace) is the same JSON: load() runs it.
 * Every decision logs "[Claude copy] ... (CopyMode.method)".
 */
public final class CopyMode {
    private CopyMode() {}

    static final int HOLD = 20 * 60 * 30;
    public static final String SOURCE_WATCHED = "watched", SOURCE_TRACE = "trace";

    /** One primitive move, relative to the anchor: go_to (the exact cell, with facing), place_at (the cell, the block), dig_at (the cell). */
    public record Move(String skill, BlockPos at, String block, Float yaw, Float pitch) {
        public String describe() { return skill + " " + at.getX() + " " + at.getY() + " " + at.getZ() + (block == null ? "" : " " + block) + (yaw == null ? "" : " facing " + Math.round(yaw) + " " + Math.round(pitch == null ? 0 : pitch)); }
    }
    static final int WATCH = 0, REPLAY = 1, FINAL = 2;
    static final class Session {
        final UUID user; final String userName; final BlockPos anchor; final int started; String name, goal = "";
        final List<Move> moves = new ArrayList<>(); final List<Boolean> shown = new ArrayList<>();
        int repeated, phase = WATCH, replayFrom, replayTo, missed; Task.Wait hold; BlockPos lastStand;
        BlockPos shift = BlockPos.ZERO; final CopyBuckets.Seen buckets = new CopyBuckets.Seen(); // (his go moved onto another lava; their bucket as he's seen it)
        Session(UUID user, String userName, BlockPos anchor, int started, String name) { this.user = user; this.userName = userName; this.anchor = anchor; this.started = started; this.name = name; }
    }
    private static Map<UUID, Session> sessions; // (static, made on first use: fields added live start null)
    static Map<UUID, Session> sessions() { if (sessions == null) sessions = new HashMap<>(); return sessions; }
    public static String lastLine = "";
    public static boolean active(ClaudeEntity g) { return sessions().containsKey(g.getUUID()); }
    public static boolean watching(ClaudeEntity g) { Session s = sessions().get(g.getUUID()); return s != null && s.phase == WATCH; }
    public static BlockPos anchorNow(ClaudeEntity g) { Session s = sessions().get(g.getUUID()); return s == null ? null : s.anchor; } // (tests)
    public static List<Move> movesNow(ClaudeEntity g) { Session s = sessions().get(g.getUUID()); return s == null ? List.of() : List.copyOf(s.moves); }
    public static String lastSaved = "";
    private static Map<UUID, java.util.Set<BlockPos>> known; // the cells he watched them work at, or a learned sequence names (a spot he knows for dig_at / place_at)
    static java.util.Set<BlockPos> known(ClaudeEntity g) { if (known == null) known = new HashMap<>(); return known.computeIfAbsent(g.getUUID(), k -> new java.util.LinkedHashSet<>()); }
    /** He watched them work this cell, or the sequence he's running names it: he knows the spot (Primitives.knownHow). */
    public static boolean knows(ClaudeEntity g, BlockPos p) { return known != null && known(g).contains(p); }
    static void note(ClaudeEntity g, BlockPos p) { var k = known(g); k.add(p.immutable()); if (k.size() > 256) k.remove(k.iterator().next()); }
    static String bot(ClaudeEntity g) { return g.getGameProfile().getName(); }
    static String xyz(BlockPos p) { return p.getX() + " " + p.getY() + " " + p.getZ(); }
    /** A test's clean slate: no session, his saved sequences gone. */
    public static void reset(ClaudeEntity g) { sessions().remove(g.getUUID()); if (known != null) known.remove(g.getUUID()); lastLine = ""; lastSaved = ""; for (String n : LearnedStore.list(bot(g), LearnedStore.Kind.SKILL)) LearnedStore.delete(bot(g), LearnedStore.Kind.SKILL, n); }

    // ================================================================ their words

    static final Pattern START = Pattern.compile("\\b(i'?ll show you|ill show you|i will show you|let me show you|lemme show you|watch me|watch what i do|watch how i do|see what i do|do what i do|do as i do|do it like me|do like me|copy me|copy what i do|follow my lead)\\b(\\s+how to\\s+(?<name>[a-z0-9' -]{1,40}))?");
    static final Pattern BUILDING = Pattern.compile("\\b(build|building|make a|make an|making a|building a)\\b"); // (watching a build is WatchBuild's)
    static final Pattern CUE = Pattern.compile("(ok(ay)?|alright|now|right|so)?[ ,]*(now you|you now|you try|now you try|you do it|now do (it|that|the same)|do (it|that) now|do the same|your go|go ahead)\\b[ ,.!a-z]*");
    static final Pattern END = Pattern.compile("(ok(ay)?|alright|right|so|now)?[ ,]*(ok(ay)?|got it|gotcha|your turn|now your turn|that'?s it,? your turn|you got it|ok your turn|all yours)([ ,]+(claude|now|your turn|got it))?[ .!]*");

    static String norm(String raw) { return raw.toLowerCase(Locale.ROOT).replace('’', '\'').trim(); }

    /** Their words about showing him: handled here (true) or left to the chat (false). */
    public static boolean heard(ClaudeEntity g, ServerPlayer p, String raw) {
        String low = norm(raw);
        Session s = sessions().get(g.getUUID());
        if (s != null && s.user.equals(p.getUUID())) {
            if (END.matcher(low).matches()) { finish(g, p, s, raw); return true; }
            if (CUE.matcher(low).matches()) { cue(g, p, s, raw); return true; }
            return false;
        }
        Matcher m = START.matcher(low);
        if (!m.find() || low.endsWith("?") || BUILDING.matcher(low).find()) return false;
        String nm = m.group("name") == null ? "" : m.group("name").replaceAll("\\b(here|now|real quick|quick|ok|okay|please|claude)\\b.*$", "").replaceAll("[^a-z0-9' -]", "").trim();
        ClaudeMod.LOGGER.info("[Claude copy] \"{}\": they'll show him{}: copy mode (CopyMode.heard)", raw, nm.isEmpty() ? "" : " how to " + nm);
        var steps = new JsonArray(); var o = new JsonObject();
        o.addProperty("skill", "copy_me"); o.addProperty("target", nm); o.addProperty("count", 1); o.addProperty("where", "near_them"); steps.add(o);
        com.claudemod.director.ClaudeLife.command(g, p, steps, "watch them and copy what they do" + (nm.isEmpty() ? "" : ": " + nm) + " (their words: \"" + raw + "\")", "replace");
        lastLine = "ok, watching. say \"now you\" for my go, \"your turn\" when you're done showing me"; com.claudemod.director.ClaudeChat.say(p, lastLine, "copy: start");
        return true;
    }

    /** copy_me [name]: stand by facing them, learning each step he sees, till "your turn". */
    public static String skillCopy(ClaudeEntity g, ServerPlayer p, Skills.Step st) {
        Session s = sessions().get(g.getUUID());
        if (s != null && !s.user.equals(p.getUUID())) { sessions().remove(g.getUUID()); s = null; }
        if (s == null) {
            BlockPos at = Whereabouts.lookFor(g, p); if (at == null) at = g.blockPosition();
            String nm = st.target() == null ? "" : st.target().replace('_', ' ').trim();
            s = new Session(p.getUUID(), p.getGameProfile().getName(), at.immutable(), g.tickCount, nm.isEmpty() ? freeName(g, p.getGameProfile().getName() + "'s demo") : nm);
            sessions().put(g.getUUID(), s);
            ClaudeMod.LOGGER.info("[Claude copy] watching {} to copy \"{}\": the anchor is their feet cell {} (CopyMode.skillCopy)", s.userName, s.name, xyz(at));
        }
        s.goal = g.playerGoal;
        hold(g, s);
        return "watching " + s.userName + " to copy what they do: learning each step i see, my go on \"now you\"";
    }
    static String freeName(ClaudeEntity g, String base) {
        if (!LearnedStore.exists(bot(g), LearnedStore.Kind.SKILL, base)) return base;
        for (int i = 2; ; i++) if (!LearnedStore.exists(bot(g), LearnedStore.Kind.SKILL, base + " " + i)) return base + " " + i;
    }
    static void hold(ClaudeEntity g, Session s) {
        final Task.Wait old = s.hold; if (old != null) g.tasks.removeIf(x -> x == old);
        g.tasks.add(s.hold = new Task.Wait(HOLD, false)); s.phase = WATCH;
    }

    // ================================================================ what he sees them do

    /** "stand right here" while showing him (ExactSpot.heard): their cell and facing, a step of the demo. True: taken as one. */
    public static boolean recordStand(ClaudeEntity g, ServerPlayer p, ExactSpot.Ask a) {
        Session s = sessions().get(g.getUUID());
        if (s == null || !s.user.equals(p.getUUID()) || s.phase != WATCH) return false;
        add(g, s, new Move("go_to", a.cell().subtract(s.anchor), null, p.getYRot(), p.getXRot()), "they named it (\"" + a.words() + "\")");
        s.lastStand = a.cell();
        lastLine = "ok, that block (" + xyz(a.cell()) + "): i'll stand there on my go"; com.claudemod.director.ClaudeChat.say(p, lastLine, "copy: stand step");
        return true;
    }
    static void add(ClaudeEntity g, Session s, Move m, String why) {
        s.moves.add(m); s.shown.add(false);
        ClaudeMod.LOGGER.info("[Claude copy] step {} learned: {} ({}) (CopyMode.add)", s.moves.size(), m.describe(), why);
    }

    static List<ClaudeEntity> watchersOf(ServerLevel level, ServerPlayer p) {
        List<ClaudeEntity> out = new ArrayList<>();
        if (sessions == null || sessions.isEmpty()) return out;
        for (ServerPlayer sp : level.getServer().getPlayerList().getPlayers()) if (sp instanceof ClaudeEntity g && g.level() == level) { Session s = sessions.get(g.getUUID()); if (s != null && s.phase == WATCH && s.user.equals(p.getUUID())) out.add(g); }
        return out;
    }
    /** They put a block down: learned (where they stood, their facing, the block) if he saw it go in. */
    public static void userPlaced(ServerPlayer p, ServerLevel level, net.neoforged.neoforge.event.level.BlockEvent.EntityPlaceEvent e) {
        for (ClaudeEntity g : watchersOf(level, p)) event(g, sessions.get(g.getUUID()), p, level, e.getPos().immutable(), e.getPlacedBlock(), true);
    }
    /** They broke a block: learned if he saw it go (one they'd just put down and took back up: that place was a step shown). */
    public static void userBroke(ServerPlayer p, ServerLevel level, BlockPos pos, BlockState was) {
        for (ClaudeEntity g : watchersOf(level, p)) event(g, sessions.get(g.getUUID()), p, level, pos.immutable(), was, false);
    }
    static void event(ClaudeEntity g, Session s, ServerPlayer p, ServerLevel level, BlockPos pos, BlockState state, boolean place) {
        if (state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) return; // (a bucket's fluid: CopyBuckets learns it by their bucket)
        String blk = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        if (!Perception.seesBlock(g, level, pos)) {
            s.missed++;
            ClaudeMod.LOGGER.info("[Claude copy] they {} {} at {} out of his sight (he's at {}): not learned (CopyMode.event)", place ? "put in" : "broke", blk, xyz(pos), xyz(g.blockPosition()));
            return;
        }
        if (!Whereabouts.seesNow(g, p)) Perception.lookNow(g); // (watching them: his eyes on them as it happens)
        BlockPos stand = Whereabouts.seesNow(g, p) ? BlockPos.containing(p.getX(), p.getY() + 0.2, p.getZ()) : null; // where they stood, as he saw it
        float yaw = p.getYRot(), pitch = p.getXRot(); // (their head: where they faced, seen)
        if (!place) {
            for (int i = s.moves.size() - 1; i >= s.repeated; i--) {
                Move m = s.moves.get(i);
                if (m.skill().equals("place_at") && m.at().equals(pos.subtract(s.anchor)) && !s.shown.get(i)) {
                    s.shown.set(i, true);
                    ClaudeMod.LOGGER.info("[Claude copy] they took the {} at {} straight back up: shown, the place is the step to copy (CopyMode.event)", blk, xyz(pos));
                    return;
                }
            }
        }
        if (stand != null && !stand.equals(s.lastStand)) { add(g, s, new Move("go_to", stand.subtract(s.anchor), null, yaw, pitch), "where they stood for it, seen"); s.lastStand = stand; }
        else if (stand == null) ClaudeMod.LOGGER.info("[Claude copy] couldn't see where they stood for the {} at {}: the act alone (CopyMode.event)", blk, xyz(pos));
        note(g, pos);
        if (place) {
            Item it = state.getBlock().asItem();
            String id = it instanceof BlockItem ? BuiltInRegistries.ITEM.getKey(it).getPath() : blk;
            add(g, s, new Move("place_at", pos.subtract(s.anchor), id, yaw, pitch), "seen put in");
        } else add(g, s, new Move("dig_at", pos.subtract(s.anchor), null, yaw, pitch), "seen broken (" + blk + ")");
    }

    // ================================================================ his go

    /** The moves as plan steps at an anchor: go_to x y z facing yaw pitch (exact), place_at x y z block, dig_at x y z. */
    public static JsonArray steps(List<Move> moves, BlockPos anchor, ClaudeEntity g) {
        JsonArray a = new JsonArray(); java.util.Set<String> fetch = new java.util.LinkedHashSet<>();
        for (Move m : moves) if (m.skill().equals("place_at") && m.block() != null && !CopyBuckets.fluid(m.block())) { Item it = BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.withDefaultNamespace(m.block())); if (g != null && g.inventory.count(x -> x.is(it)) == 0) fetch.add(m.block()); }
        for (String b : fetch) a.add(step("obtain", b, (int) moves.stream().filter(m -> b.equals(m.block())).count(), "near_you")); // (the block he's to put down and hasn't got: got first, the ordinary way)
        for (Move m : moves) {
            BlockPos p = anchor.offset(m.at());
            switch (m.skill()) {
                case "go_to" -> a.add(step("go_to", xyz(p) + (m.yaw() == null ? "" : " facing " + m.yaw() + " " + (m.pitch() == null ? 0f : m.pitch())), 1, "exact"));
                case "place_at" -> a.add(step("place_at", xyz(p) + " " + m.block(), 1, "near_you"));
                case "dig_at" -> a.add(step("dig_at", xyz(p), 1, "near_you"));
                case "scoop_at" -> a.add(step("scoop_at", xyz(p), 1, "near_you"));
                default -> { }
            }
        }
        return a;
    }
    static JsonObject step(String skill, String target, int count, String where) { JsonObject o = new JsonObject(); o.addProperty("skill", skill); o.addProperty("target", target); o.addProperty("count", count); o.addProperty("where", where); return o; }

    static void cue(ClaudeEntity g, ServerPlayer p, Session s, String raw) {
        if (s.phase != WATCH) { lastLine = "still on my go, one sec"; com.claudemod.director.ClaudeChat.say(p, lastLine, "copy: busy"); return; }
        if (s.moves.size() <= s.repeated) {
            lastLine = s.missed > 0 ? "i couldn't see what you did from here: show me again where i can see?" : "i haven't seen you do anything new to copy yet";
            ClaudeMod.LOGGER.info("[Claude copy] \"{}\": nothing new learned since the last go ({} missed out of sight) (CopyMode.cue)", raw, s.missed);
            com.claudemod.director.ClaudeChat.say(p, lastLine, "copy: nothing to copy"); return;
        }
        run(g, p, s, REPLAY, raw);
        lastLine = "ok, my go"; com.claudemod.director.ClaudeChat.say(p, lastLine, "copy: my go");
    }
    static void run(ClaudeEntity g, ServerPlayer p, Session s, int phase, String raw) {
        List<Move> todo = s.moves.subList(s.repeated, s.moves.size());
        s.replayFrom = s.repeated; s.replayTo = s.moves.size();
        s.shift = CopyBuckets.shift(g, s, todo); // (the lava they worked is obsidian now: the same on the next lava he sees)
        JsonArray st = steps(todo, s.anchor.offset(s.shift), g);
        ClaudeMod.LOGGER.info("[Claude copy] \"{}\": {} step(s) {}: {} (CopyMode.run)", raw, todo.size(), phase == FINAL ? "left to do himself" : "since the last go", st);
        s.phase = phase;
        final Task.Wait h = s.hold; if (h != null) { g.tasks.removeIf(x -> x == h); if (g.currentTask == h) g.dropCurrent = true; } s.hold = null;
        String goal = (phase == FINAL ? "do it myself: " : "copy them: ") + s.name + " (their words: \"" + raw + "\")";
        com.claudemod.director.ClaudeLife.command(g, p, st, goal, "replace");
        s.goal = g.playerGoal;
    }

    static void finish(ClaudeEntity g, ServerPlayer p, Session s, String raw) {
        if (s.moves.isEmpty()) {
            sessions().remove(g.getUUID()); dropHold(g, s);
            lastLine = s.missed > 0 ? "i couldn't see what you did from here, so nothing to copy. show me again where i can see?" : "i didn't see you do anything to copy";
            ClaudeMod.LOGGER.info("[Claude copy] \"{}\": the demo's over with nothing learned ({} missed) (CopyMode.finish)", raw, s.missed);
            com.claudemod.director.ClaudeChat.say(p, lastLine, "copy: nothing"); g.commandActive = false; g.playerGoal = ""; return;
        }
        boolean saved = LearnedStore.save(bot(g), LearnedStore.Kind.SKILL, s.name, sequence(s.name, s.userName, SOURCE_WATCHED, s.anchor, g.level().dimension().location().toString(), s.moves));
        lastSaved = saved ? s.name : "";
        ClaudeMod.LOGGER.info("[Claude copy] \"{}\": the demo's over, {} step(s) {} as \"{}\" ({} done already) (CopyMode.finish)", raw, s.moves.size(), saved ? "saved" : "NOT saved", s.name, s.repeated);
        if (s.repeated >= s.moves.size()) {
            sessions().remove(g.getUUID()); dropHold(g, s);
            lastLine = "got it, " + s.moves.size() + " steps" + (saved ? ", remembered as \"" + s.name + "\"" : "") + ". done them all already";
            com.claudemod.director.ClaudeChat.say(p, lastLine, "copy: saved");
            g.commandActive = false; g.commandResults.clear(); g.playerGoal = "done (learned " + s.name + " from them): stay with them and wait for their next ask";
            return;
        }
        lastLine = "got it" + (saved ? ", remembered as \"" + s.name + "\"" : "") + ". my turn";
        com.claudemod.director.ClaudeChat.say(p, lastLine, "copy: my turn");
        run(g, p, s, FINAL, raw);
    }
    static void dropHold(ClaudeEntity g, Session s) { final Task.Wait h = s.hold; if (h != null) { g.tasks.removeIf(x -> x == h); if (g.currentTask == h) g.dropCurrent = true; } s.hold = null; }

    /** Each tick: watching their hands while he stands by; a new job from anyone ends the demo. */
    public static void tick(ClaudeEntity g) {
        if (g.level().isClientSide || sessions == null || sessions.isEmpty()) return;
        Session s = sessions.get(g.getUUID()); if (s == null) return;
        ServerPlayer user = g.getServer().getPlayerList().getPlayer(s.user);
        if (user == null || user.level() != g.level()) { sessions.remove(g.getUUID()); dropHold(g, s); ClaudeMod.LOGGER.info("[Claude copy] {} isn't in his world any more: copy mode over (CopyMode.tick)", s.userName); return; }
        if (!g.playerGoal.equals(s.goal) && !g.skillQueue.stream().anyMatch(x -> x.skill().equals("copy_me"))) { sessions.remove(g.getUUID()); dropHold(g, s); ClaudeMod.LOGGER.info("[Claude copy] a new job (\"{}\"): copy mode over, {} step(s) learned, not kept (CopyMode.tick)", g.playerGoal, s.moves.size()); return; }
        if (s.phase != WATCH) return;
        g.nextPlanTick = Math.max(g.nextPlanTick, g.tickCount + 100); // (his own planning waits while he watches)
        CopyBuckets.watch(g, s, user, (ServerLevel) g.level()); // their bucket: a pour or a scoop seen, obsidian forming
        if (s.hold != null && g.currentTask == s.hold) g.getLookControl().setLookAt(Whereabouts.seesNow(g, user) ? WatchBuild.aimOf(user) : net.minecraft.world.phys.Vec3.atCenterOf(s.anchor)); // watching their hands, as a player watches a friend show him
    }

    /**
     * Between steps (ClaudeLife.runWork, before the job's report): his go done (the world checked: each block he was to put in or
     * take out is so), back to watching; the last go done, the demo's over. Watching with no hold: the hold again. True: handled.
     */
    public static boolean between(ClaudeEntity g, ServerPlayer p) {
        if (sessions == null) return false;
        Session s = sessions.get(g.getUUID()); if (s == null || !g.skillQueue.isEmpty()) return false;
        if (s.phase == WATCH) { if (s.hold == null || g.currentTask != s.hold && !g.tasks.contains(s.hold)) { hold(g, s); ClaudeMod.LOGGER.info("[Claude copy] back to standing by, watching them (CopyMode.between)"); } return true; }
        ServerLevel level = (ServerLevel) g.level(); List<String> off = new ArrayList<>();
        for (int i = s.replayFrom; i < s.replayTo && i < s.moves.size(); i++) {
            Move m = s.moves.get(i); BlockPos at = s.anchor.offset(s.shift).offset(m.at()); BlockState now = level.getBlockState(at);
            { String fb = CopyBuckets.check(s, i, level, s.anchor.offset(s.shift)); if (fb != null) off.add(fb); }
            if (m.skill().equals("place_at") && !CopyBuckets.fluid(m.block()) && !BuiltInRegistries.BLOCK.getKey(now.getBlock()).getPath().equals(m.block()) && !BuiltInRegistries.ITEM.getKey(now.getBlock().asItem()).getPath().equals(m.block())) off.add(m.block() + " not at " + xyz(at) + " (" + BuiltInRegistries.BLOCK.getKey(now.getBlock()).getPath() + ")");
            if (m.skill().equals("dig_at") && !(now.isAir() || now.canBeReplaced())) off.add(xyz(at) + " still " + BuiltInRegistries.BLOCK.getKey(now.getBlock()).getPath());
        }
        String failed = g.commandResults.stream().filter(r -> r.contains("-> failed") || r.startsWith("failed")).findFirst().orElse(null);
        boolean ok = off.isEmpty() && failed == null;
        String how = ok ? "done" : failed != null ? failed : String.join("; ", off);
        ClaudeMod.LOGGER.info("[Claude copy] his go ({} step(s)) {}: {} (CopyMode.between)", s.replayTo - s.replayFrom, ok ? "done, checked in the world" : "didn't come off", how);
        if (ok) s.repeated = s.replayTo;
        g.commandResults.clear();
        if (s.phase == REPLAY) {
            lastLine = ok ? "done, like that?" : "couldn't do that one: " + shortWhy(how);
            com.claudemod.director.ClaudeChat.say(p, lastLine, "copy: my go done");
            s.goal = g.playerGoal; hold(g, s); g.commandActive = true;
            return true;
        }
        sessions.remove(g.getUUID());
        lastLine = ok ? "done, all " + s.moves.size() + " steps" : "couldn't finish it: " + shortWhy(how);
        com.claudemod.director.ClaudeChat.say(p, lastLine, "copy: my turn done");
        g.commandActive = false; g.playerGoal = "done (" + s.name + ": " + (ok ? "did it myself" : how) + "): stay with them and wait for their next ask";
        return true;
    }
    static String shortWhy(String how) { String w = how.contains("failed: ") ? how.substring(how.lastIndexOf("failed: ") + 8) : how; return w.length() > 120 ? w.substring(0, 120) : w; }

    // ================================================================ the saved form (watched, or from a recorded trace)

    /** The JSON a learned sequence is kept as: anchor (absolute, where it was shown), its world, the teacher, where it came from, the moves relative to the anchor. */
    public static JsonObject sequence(String name, String teacher, String source, BlockPos anchor, String dim, List<Move> moves) {
        JsonObject o = new JsonObject();
        o.addProperty("format", "primitive_sequence"); o.addProperty("teacher", teacher); o.addProperty("source", source); o.addProperty("dim", dim);
        JsonArray an = new JsonArray(); an.add(anchor.getX()); an.add(anchor.getY()); an.add(anchor.getZ()); o.add("anchor", an);
        JsonArray st = new JsonArray();
        for (Move m : moves) {
            JsonObject e = new JsonObject(); e.addProperty("skill", m.skill());
            JsonArray at = new JsonArray(); at.add(m.at().getX()); at.add(m.at().getY()); at.add(m.at().getZ()); e.add("at", at);
            if (m.block() != null) e.addProperty("block", m.block());
            if (m.yaw() != null) e.addProperty("yaw", m.yaw());
            if (m.pitch() != null) e.addProperty("pitch", m.pitch());
            CopyBuckets.save(m, e); // (what he saw come of it: obsidian where the water met the lava)
            st.add(e);
        }
        o.add("steps", st);
        return o;
    }
    /** The moves of a saved (or trace-made) sequence; null when it isn't one. */
    public static List<Move> moves(JsonObject o) {
        if (o == null || !o.has("steps")) return null;
        List<Move> out = new ArrayList<>();
        try {
            for (var el : o.getAsJsonArray("steps")) {
                JsonObject e = el.getAsJsonObject(); JsonArray at = e.getAsJsonArray("at");
                out.add(CopyBuckets.loaded(e, new Move(e.get("skill").getAsString(), new BlockPos(at.get(0).getAsInt(), at.get(1).getAsInt(), at.get(2).getAsInt()), e.has("block") ? e.get("block").getAsString() : null, e.has("yaw") ? e.get("yaw").getAsFloat() : null, e.has("pitch") ? e.get("pitch").getAsFloat() : null)));
            }
        } catch (RuntimeException ex) { ClaudeMod.LOGGER.warn("[Claude copy] not a primitive sequence: {} (CopyMode.moves)", ex.toString()); return null; }
        return out;
    }
    public static BlockPos anchor(JsonObject o) { JsonArray a = o.getAsJsonArray("anchor"); return new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()); }
    /** Runs a learned sequence (his saved one by name, or one made from a trace) at an anchor (null: where it was shown), as their order. */
    public static String load(ClaudeEntity g, ServerPlayer p, JsonObject seq, BlockPos at) {
        List<Move> ms = moves(seq);
        if (ms == null || ms.isEmpty()) return "failed: no steps in that sequence";
        BlockPos an = at != null ? at : anchor(seq);
        for (Move m : ms) if (!m.skill().equals("go_to")) note(g, an.offset(m.at())); // (the sequence names these cells: his to work, as taught)
        String nm = seq.has("name") ? seq.get("name").getAsString() : "a learned sequence";
        ClaudeMod.LOGGER.info("[Claude copy] running \"{}\" ({} from {}, {} step(s)) at {} (CopyMode.load)", nm, seq.has("source") ? seq.get("source").getAsString() : "?", seq.has("teacher") ? seq.get("teacher").getAsString() : "?", ms.size(), xyz(an));
        com.claudemod.director.ClaudeLife.command(g, p, steps(ms, an, g), "do " + nm + " (learned)", "replace");
        return "doing " + nm + ": " + ms.size() + " steps";
    }
}
