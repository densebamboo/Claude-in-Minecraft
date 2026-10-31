package com.claudemod.entity.life;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.claudemod.ClaudeMod;
import com.claudemod.entity.ClaudeEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Their exact block (the user, live 2026-10-30 14:16: "stand right where im standing" ... "no stand literally on the block im
 * standing on"; he stood a block off and said "ok im standing where you were"). "right here", "where I'm standing", "on my block",
 * "this block", "exactly here", "literally where I am" with a standing/going word mean THEIR FEET CELL at the moment of the message,
 * as he knows it: seen (a fresh look), or told (coords). Not near_them (within a few blocks): a go_to where "exact_them", onto the
 * middle of that one cell (within 0.3), whatever they do after.
 *
 * Vanilla: two players can't share a cell. While they're still on it he stands beside it, says so once ("you're still on it:
 * stepping onto your block when you move"), and steps on the moment it's free; a walk onto it stops short the tick they're in the
 * way (no pushing). Arrived, he looks round again before he says anything: on the cell from the message, and if they've moved, he
 * says where they are now. Nothing claims arrival from a walk ending: every "I'm on your block" / "where you were" line comes from
 * his feet cell against the cell (ClaudeChat.say drops a claim that isn't so for the true comparison, with the clarifying question).
 * A go_to near_them also leaves the real comparison in the job's results, so the chat model's report can't claim more.
 * go_to where "exact" is the same walk onto the cell its target names ("x y z [facing yaw pitch]"): what copy mode replays.
 * Every decision logs "[Claude exact] ... (ExactSpot.method)".
 */
public final class ExactSpot {
    private ExactSpot() {}

    static final double ON = 0.3; // on the cell: within this of its middle
    static final int ASK_FOR = 20 * 60 * 5; // their "stand right here" stays what exact_them means for five minutes
    static final int WAIT_MAX = 20 * 120; // two minutes beside it with them on it: he says so and stops waiting

    /** What they asked: their feet cell at the message, where exactly they stood, when, how he knew, their words. */
    public record Ask(UUID who, BlockPos cell, Vec3 at, long time, String how, String words) {}
    /** A walk onto an exact cell: theirs (from the message) or a named one (copy mode); near: a go_to near_them, checked on arrival. */
    static final class Job {
        boolean approach; /* walking over to where they last were: their cell once he sees them */ final UUID who; BlockPos cell; final boolean theirs, near; final Ask ask; final int started; final Float yaw, pitch;
        Task walk, beside; int tries, waitStart = -1; boolean waitSaid;
        Job(UUID who, BlockPos cell, boolean theirs, boolean near, Ask ask, int started, Float yaw, Float pitch) { this.who = who; this.cell = cell; this.theirs = theirs; this.near = near; this.ask = ask; this.started = started; this.yaw = yaw; this.pitch = pitch; }
    }
    record Done(BlockPos cell, int tick) {}
    /** A cell he saw them on, from when to when (his ticks): their trail as he saw it, the last minute. */
    public record Stood(BlockPos cell, int from, int to) {}
    private static Map<UUID, java.util.ArrayDeque<Stood>> trail; // (static, made on first use)
    static java.util.ArrayDeque<Stood> trail(ClaudeEntity g) { if (trail == null) trail = new HashMap<>(); return trail.computeIfAbsent(g.getUUID(), k -> new java.util.ArrayDeque<>()); }
    /** Each 5 ticks while he sees them: their feet cell onto the trail (a minute of it). */
    static void watchTrail(ClaudeEntity g) {
        if (g.tickCount % 5 != 0 || !(g.getTarget() instanceof ServerPlayer p) || p instanceof ClaudeEntity || !Whereabouts.seesNow(g, p)) return;
        BlockPos c = BlockPos.containing(p.getX(), p.getY() + 0.2, p.getZ());
        var tr = trail(g); Stood last = tr.peekLast();
        if (last != null && last.cell().equals(c) && g.tickCount - last.to() <= 40) { tr.pollLast(); tr.addLast(new Stood(last.cell(), last.from(), g.tickCount)); }
        else tr.addLast(new Stood(c.immutable(), g.tickCount, g.tickCount));
        while (!tr.isEmpty() && (g.tickCount - tr.peekFirst().to() > 20 * 60 || tr.peekFirst().to() > g.tickCount || tr.size() > 64)) tr.pollFirst();
    }
    /** The newest cell on their trail they stood still on (1 s+) that isn't the one they're on now; null: none. */
    static Stood pastStood(ClaudeEntity g, ServerPlayer p, BlockPos now) {
        var it = trail(g).descendingIterator();
        while (it.hasNext()) { Stood s = it.next(); if (now != null && s.cell().equals(now)) continue; if (s.to() - s.from() >= 20) return s; }
        return null;
    }
    private static Map<UUID, Ask> asks; private static Map<UUID, Job> jobs; private static Map<UUID, Done> done; // (static, made on first use: fields added live start null)
    static Map<UUID, Ask> asks() { if (asks == null) asks = new HashMap<>(); return asks; }
    static Map<UUID, Job> jobs() { if (jobs == null) jobs = new HashMap<>(); return jobs; }
    static Map<UUID, Done> dones() { if (done == null) done = new HashMap<>(); return done; }
    /** What he last said about it (tests). */
    public static String lastLine = "";

    public static void reset(ClaudeEntity g) { if (trail != null) trail.remove(g.getUUID()); asks().remove(g.getUUID()); jobs().remove(g.getUUID()); dones().remove(g.getUUID()); lastLine = ""; }
    public static Ask ask(ClaudeEntity g) { return asks().get(g.getUUID()); }
    /** The exact cell he's walking onto (or waiting by), or null. */
    public static BlockPos target(ClaudeEntity g) { Job j = jobs().get(g.getUUID()); return j == null || j.near || j.approach ? null : j.cell; }
    /** Walking over to them first: he can't see them yet (tests). */
    public static boolean approaching(ClaudeEntity g) { Job j = jobs().get(g.getUUID()); return j != null && j.approach; }
    public static boolean waiting(ClaudeEntity g) { Job j = jobs().get(g.getUUID()); return j != null && j.waitSaid && j.waitStart >= 0; }

    static String xyz(BlockPos p) { return p.getX() + " " + p.getY() + " " + p.getZ(); }
    /** Which way 'to' is from 'from', in blocks: "2 east, 1 north", or "the same block". */
    public static String rel(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX(), dy = to.getY() - from.getY(), dz = to.getZ() - from.getZ();
        StringBuilder sb = new StringBuilder();
        if (dx != 0) sb.append(Math.abs(dx)).append(dx > 0 ? " east" : " west");
        if (dz != 0) sb.append(sb.length() > 0 ? ", " : "").append(Math.abs(dz)).append(dz > 0 ? " south" : " north");
        if (dy != 0) sb.append(sb.length() > 0 ? ", " : "").append(Math.abs(dy)).append(dy > 0 ? " up" : " down");
        return sb.length() == 0 ? "the same block" : sb.toString();
    }
    static double offMiddle(ClaudeEntity g, BlockPos c) { double dx = g.getX() - (c.getX() + 0.5), dz = g.getZ() - (c.getZ() + 0.5); return Math.sqrt(dx * dx + dz * dz); }
    /** His feet cell (on farmland or a slab his feet sit a little down into it: the cell he is in). */
    public static BlockPos feet(ClaudeEntity g) { return BlockPos.containing(g.getX(), g.getY() + 0.2, g.getZ()); }
    /** On it: his feet in that cell, within 0.3 of its middle. */
    public static boolean onCell(ClaudeEntity g, BlockPos c) { return feet(g).equals(c) && offMiddle(g, c) <= ON + 0.02; }

    // ================================================================ their words

    static final Pattern EXACT = Pattern.compile("\\b(right here|exactly here|literally here|right where i('?m| am)|right where im|exactly where i('?m| am)|exactly where im|literally where i('?m| am)|literally where im|where i'?m standing|where im standing|where i am standing|where i'?m at|where im at|where i stand|on my block|this spot|on my spot|my exact spot|(the|this) block i'?m (standing )?on|(the|this) block im (standing )?on|the block under me|this block|my block)\\b");
    /** Past tense: where they stood a moment ago ("go where i was just standing", "the coords i was just at", "my last spot"). */
    static final Pattern PAST = Pattern.compile("\\b(where i was( just)?+( at| standing| stood)?+(?!\\s+\\w+ing\\b)|where i just was|where i'?d (just )?been( standing)?|where i stood|my last spot|the (coords|coordinates|spot|block|place) i was( just)? (at|on|standing on|standing at|in))\\b");
    static final Pattern POS_VERB = Pattern.compile("\\b(stand|standing|step|go|get|come|move|walk|hop|be|stay|wait|sit)\\b");
    static final Pattern OTHER_JOB = Pattern.compile("\\b(place|put|pour|break|mine|dig|craft|build|make|bring|give|kill|fight|grab|take(?! it from the top| it easy| it slow| your time)|pick|light|eat|use|smelt|cook|chop|fill|throw|drop|scoop|empty)\\b");
    static final Pattern XYZ = Pattern.compile("(-?\\d+)[ ,_]+(-?\\d+)[ ,_]+(-?\\d+)");
    static final Pattern FACING = Pattern.compile("facing[ _]+(-?[0-9.]+)[ _]+(-?[0-9.]+)");
    /** A line of his claiming to be on their block / where they stood (ClaudeChat.say checks it). */
    public static final Pattern CLAIM = Pattern.compile("\\b(i'?m|im|i am)\\b[^.!?]{0,25}?\\b(on (your|ur|the same) (block|spot|cell)|on the block (you|ur)|where (you|u) (were|are|'re|r)\\b|standing where (you|u)|on top of your block)");

    static String norm(String raw) { return raw.toLowerCase(Locale.ROOT).replace('’', '\'').trim(); }
    /** Their words name their exact cell to stand on ("stand right where im standing", "get on my block"). */
    public static boolean exactWords(String raw) {
        String low = norm(raw);
        if (!EXACT.matcher(low).find() && !PAST.matcher(low).find() || low.matches("(?s).*\\bwhere (are|r) (you|u)\\b.*")) return false;
        return POS_VERB.matcher(PAST.matcher(EXACT.matcher(low).replaceAll(" ")).replaceAll(" ")).find();
    }

    /**
     * Their cell, as he knows it right now: told in these very words (coords with I'm/me/here), else a fresh look round and where his
     * eyes put them (seen this moment, or in the last 2 s). Null: he can't say which block (heard only, out of sight, never seen).
     */
    static Ask resolve(ClaudeEntity g, ServerPlayer p, String raw) {
        if (PAST.matcher(norm(raw)).find() && !EXACT.matcher(norm(raw)).find()) { // "where i was just standing": the last cell he saw them stand still on (1 s+) before where they are now
            Whereabouts.lookFor(g, p);
            BlockPos now = Whereabouts.seesNow(g, p) ? BlockPos.containing(p.getX(), p.getY() + 0.2, p.getZ()) : null;
            Stood st = pastStood(g, p, now);
            if (st == null) { ClaudeMod.LOGGER.info("[Claude exact] \"{}\": no cell he saw them stand still on in the last minute{} (ExactSpot.resolve)", raw, now == null ? "" : " but the one they're on, " + xyz(now)); return null; }
            ClaudeMod.LOGGER.info("[Claude exact] \"{}\": where they last stood still, {} ({} s there, left {} s ago; they're {} now) (ExactSpot.resolve)", raw, xyz(st.cell()), (st.to() - st.from()) / 20, (g.tickCount - st.to()) / 20, now == null ? "out of sight" : "at " + xyz(now));
            return new Ask(p.getUUID(), st.cell(), Vec3.atBottomCenterOf(st.cell()), g.level().getGameTime() - (g.tickCount - st.to()), "where they last stood", raw);
        }
        Whereabouts.Spot s0 = Whereabouts.of(g, p);
        Matcher m = XYZ.matcher(norm(raw));
        if (m.find() && s0 != null && s0.how == Whereabouts.How.TOLD && g.level().getGameTime() - s0.time <= 2) {
            BlockPos c = BlockPos.containing(s0.at);
            ClaudeMod.LOGGER.info("[Claude exact] \"{}\": they told him their spot, {}: that cell (ExactSpot.resolve)", raw, xyz(c));
            return new Ask(p.getUUID(), c, s0.at, s0.time, "told", raw);
        }
        Whereabouts.lookFor(g, p); // (a fresh look: where his eyes put them a moment ago may not be where they are now)
        Whereabouts.Spot s = Whereabouts.of(g, p);
        long age = s == null ? Long.MAX_VALUE : g.level().getGameTime() - s.time;
        if (s == null || s.spent() || s.dim != g.level().dimension() || s.how != Whereabouts.How.SEEN || age > 40) {
            ClaudeMod.LOGGER.info("[Claude exact] \"{}\": he can't say which block they're on ({}) (ExactSpot.resolve)", raw, s == null ? "never seen or told" : s.spent() ? "lost track of them" : s.how != Whereabouts.How.SEEN ? "only " + s.how.name().toLowerCase() + " them, not seen" : "last seen " + age / 20 + " s ago");
            return null;
        }
        BlockPos c = BlockPos.containing(s.at.x, s.at.y + 0.2, s.at.z); // (their feet cell: on a slab or farmland their feet sit a little into it)
        ServerLevel lv = (ServerLevel) g.level();
        if (lv.getBlockState(c.below()).getCollisionShape(lv, c.below()).isEmpty() && !lv.getBlockState(c.below(2)).getCollisionShape(lv, c.below(2)).isEmpty()) c = c.below(); // (mid-jump: the cell they jumped from)
        ClaudeMod.LOGGER.info("[Claude exact] \"{}\": their feet cell {} (seen {} s ago at {}, {}, {}) (ExactSpot.resolve)", raw, xyz(c), age / 20, String.format("%.2f", s.at.x), String.format("%.2f", s.at.y), String.format("%.2f", s.at.z));
        return new Ask(p.getUUID(), c.immutable(), s.at, s.time, "seen", raw);
    }

    /**
     * Their chat line naming their exact cell: the cell resolved and kept for exact_them; a plain stand-here order walks there now
     * (true: handled); with more in it ("stand right here and place your water bucket") the rest goes to the chat model (false),
     * whose go_to exact_them takes this cell. Not knowing which block: he asks which (true).
     */
    public static boolean heard(ClaudeEntity g, ServerPlayer p, String raw) {
        { Boolean lw = LessonWords.heardLine(g, p, raw); if (lw != null) return lw; } // the lesson's count, its last instruction, the place the line names (the pocket, the water, beside them): never their feet for those
        if (!exactWords(raw)) return false;
        String rest = PAST.matcher(EXACT.matcher(norm(raw)).replaceAll(" ")).replaceAll(" ");
        Ask a = resolve(g, p, raw);
        if (a == null && Whereabouts.spot(g, p) != null) { // out of sight, but he knows where they were: over to them first, their block once he sees them (14:52 "stand right where im standing rn" 94 off got "which block?")
            asks().remove(g.getUUID()); // (an older ask's cell isn't where they are now)
            ClaudeMod.LOGGER.info("[Claude exact] \"{}\": can't see them from here, last known about {}: over to them first, their block once he sees them (ExactSpot.heard)", raw, xyz(Whereabouts.spot(g, p)));
            if (OTHER_JOB.matcher(rest).find()) return false; // (the chat plans the rest; its go_to exact_them comes over first the same way)
            var steps0 = new com.google.gson.JsonArray(); var o0 = new com.google.gson.JsonObject();
            o0.addProperty("skill", "go_to"); o0.addProperty("target", ""); o0.addProperty("count", 1); o0.addProperty("where", "exact_them"); steps0.add(o0);
            com.claudemod.director.ClaudeLife.command(g, p, steps0, "stand on their exact block, coming over first (their words: \"" + raw + "\")", "replace");
            return true;
        }
        if (a == null) {
            Whereabouts.Spot s = Whereabouts.of(g, p);
            lastLine = s != null && s.how == Whereabouts.How.HEARD ? "i can hear you but can't see you: which block? send me its coords" : "can't see you from here: which block? send me its coords";
            com.claudemod.director.ClaudeChat.say(p, lastLine, "exact: which block");
            ClaudeMod.LOGGER.info("[Claude exact] \"{}\": no sight of them: asked which block (ExactSpot.heard)", raw);
            return true;
        }
        asks().put(g.getUUID(), a);
        if (CopyMode.recordStand(g, p, a)) return true; // (showing him: a step of the demo, done on "now you")
        if (OTHER_JOB.matcher(rest).find()) {
            ClaudeMod.LOGGER.info("[Claude exact] \"{}\": their block is {}; more asked in it, so the chat plans the rest (its go_to exact_them takes this cell) (ExactSpot.heard)", raw, xyz(a.cell));
            return false;
        }
        var steps = new com.google.gson.JsonArray(); var o = new com.google.gson.JsonObject();
        o.addProperty("skill", "go_to"); o.addProperty("target", ""); o.addProperty("count", 1); o.addProperty("where", "exact_them"); steps.add(o);
        com.claudemod.director.ClaudeLife.command(g, p, steps, "stand on their exact block " + xyz(a.cell) + " (their words: \"" + raw + "\")", "replace");
        return true;
    }

    // ================================================================ the walk

    /** go_to where exact_them (their cell from the message) or exact (the target's "x y z [facing yaw pitch]"): onto its middle. */
    public static String start(ClaudeEntity g, ServerPlayer p, Skills.Step st) {
        boolean theirs = "exact_them".equals(st.where());
        String tl = st.target() == null ? "" : st.target().toLowerCase(Locale.ROOT);
        Matcher m = XYZ.matcher(tl); BlockPos cell = m.find() ? new BlockPos(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))) : null;
        Matcher f = FACING.matcher(tl); Float yaw = null, pitch = null;
        if (f.find()) { try { yaw = Float.parseFloat(f.group(1)); pitch = Float.parseFloat(f.group(2)); } catch (NumberFormatException e) { } }
        Ask a = null;
        if (theirs && cell == null) {
            a = asks().get(g.getUUID());
            if (a != null && (!a.who.equals(p.getUUID()) || g.level().getGameTime() - a.time > ASK_FOR || a.time > g.level().getGameTime())) a = null;
            if (a == null) a = resolve(g, p, "(go_to exact_them, no fresh ask)");
            BlockPos last = a == null ? Whereabouts.spot(g, p) : null;
            if (a == null && last != null) { // out of sight: over to where they last were; their cell the moment he sees them (ExactSpot.tick / settle)
                Job j0 = new Job(p.getUUID(), last.immutable(), true, false, null, g.tickCount, null, null); j0.approach = true;
                jobs().put(g.getUUID(), j0);
                g.tasks.add(j0.walk = new Task.GoTo(last.immutable(), 3));
                lastLine = "coming over: can't see you from here"; com.claudemod.director.ClaudeChat.say(p, lastLine, "exact: coming over");
                ClaudeMod.LOGGER.info("[Claude exact] go_to exact_them: can't see them, last known about {} ({} blocks off): over there first, their block once he sees them (ExactSpot.start)", xyz(last), (int) Math.sqrt(last.distSqr(g.blockPosition())));
                return "walking over to them first (last known about " + xyz(last) + "): their exact block once i see them";
            }
            if (a == null) {
                lastLine = "can't see you from here: which block? send me its coords";
                com.claudemod.director.ClaudeChat.say(p, lastLine, "exact: which block");
                return "done: I can't see which block they're on; asked them which";
            }
            cell = a.cell;
        }
        if (cell == null) return "failed: go_to exact needs the block's x y z";
        Job j = new Job(p.getUUID(), cell.immutable(), theirs, false, a, g.tickCount, yaw, pitch);
        jobs().put(g.getUUID(), j);
        ClaudeMod.LOGGER.info("[Claude exact] go_to {}: onto the middle of {} (he's at {}, {}){} (ExactSpot.start)", theirs ? "exact_them" : "exact", xyz(cell), xyz(feet(g)), rel(feet(g), cell), a == null ? "" : ", where they stood when they said \"" + a.words + "\"");
        boolean on = occupied(g, p, cell) != null;
        if (theirs) { lastLine = on ? "ok, you're on it: stepping onto your block (" + xyz(cell) + ") when you move" : "ok, coming onto your block (" + xyz(cell) + ")"; com.claudemod.director.ClaudeChat.say(p, lastLine, "exact: start"); if (on) j.waitSaid = true; }
        queueWalk(g, p, j);
        return "walking onto " + (theirs ? "their exact block " : "the exact block ") + xyz(cell) + (a != null ? " (where they stood when they said it)" : "") + (on ? "; they're on it, so beside it till they move" : "");
    }

    /** Any other go_to (near_them, coordinates): its arrival gets the real comparison in the job's results (never "I'm there" from the walk ending). */
    public static void near(ClaudeEntity g, ServerPlayer p, BlockPos dest) {
        jobs().put(g.getUUID(), new Job(p.getUUID(), dest.immutable(), true, true, null, g.tickCount, null, null));
    }

    /** Who's in the way of him standing in the cell's middle (their body over it, as he sees it), or null. */
    static LivingEntity occupied(ClaudeEntity g, ServerPlayer p, BlockPos c) {
        AABB box = new AABB(c.getX() + 0.2, c.getY(), c.getZ() + 0.2, c.getX() + 0.8, c.getY() + 1.8, c.getZ() + 0.8); // (his own body standing in its middle)
        for (LivingEntity e : g.level().getEntitiesOfClass(LivingEntity.class, box)) {
            if (e == g || !e.isAlive() || e.isSpectator()) continue;
            if (e == p ? Whereabouts.seesNow(g, p) || e.distanceTo(g) <= 3 : g.hasLineOfSight(e) || e.distanceTo(g) <= 3) return e;
        }
        return null;
    }

    static BlockPos besideCell(ClaudeEntity g, BlockPos c) {
        ServerLevel lv = (ServerLevel) g.level(); BlockPos best = null;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos q = c.relative(d);
            if (!PlaceAim.standable(lv, q) || !lv.getFluidState(q).isEmpty()) continue;
            if (best == null || q.distSqr(g.blockPosition()) < best.distSqr(g.blockPosition())) best = q;
        }
        return best;
    }

    static void queueWalk(ClaudeEntity g, ServerPlayer p, Job j) {
        LivingEntity in = occupied(g, p, j.cell);
        if (in != null) {
            if (j.waitStart < 0) j.waitStart = g.tickCount;
            BlockPos side = besideCell(g, j.cell);
            if (side != null && !onCell(g, side) && Vec3.atBottomCenterOf(j.cell).distanceTo(g.position()) > 1.6) { g.tasks.add(j.beside = new Task.GoTo(side, 0.3)); ClaudeMod.LOGGER.info("[Claude exact] {} is on {}: to {} beside it, to wait (ExactSpot.queueWalk)", in.getName().getString(), xyz(j.cell), xyz(side)); }
            else g.tasks.add(new Task.Wait(10, true));
            return;
        }
        j.waitStart = -1;
        g.tasks.add(j.walk = new Task.GoTo(j.cell, ON));
    }

    /** Each tick: a walk onto the cell stops short the moment they're in the way (no pushing); a walk to beside it ends once it's free. */
    public static void tick(ClaudeEntity g) {
        if (g.level().isClientSide) return;
        watchTrail(g); // (their feet cells as he sees them: "where i was just standing")
        LessonWords.tickHold(g); // (held to the pocket: a step back against the water's push)
        if (jobs == null || jobs.isEmpty()) return;
        Job j = jobs.get(g.getUUID()); if (j == null || j.near) return;
        ServerPlayer p = g.getServer().getPlayerList().getPlayer(j.who);
        if (j.approach) { if (g.tickCount % 5 == 0 && p != null && Whereabouts.seesNow(g, p)) sighted(g, p, j); return; } // (on his way over: their cell the moment they're in sight)
        if (g.currentTask == null) return;
        if (g.currentTask == j.walk && Vec3.atBottomCenterOf(j.cell).distanceTo(g.position()) < 2.0) {
            LivingEntity in = occupied(g, p, j.cell);
            if (in != null) { g.dropCurrent = true; g.getNavigation().stop(); j.walk = null; ClaudeMod.LOGGER.info("[Claude exact] {} stepped onto {} as he came on: stopped short, no pushing (ExactSpot.tick)", in.getName().getString(), xyz(j.cell)); }
        } else if (g.currentTask == j.beside && g.tickCount % 5 == 0 && occupied(g, p, j.cell) == null) {
            g.dropCurrent = true; j.beside = null; ClaudeMod.LOGGER.info("[Claude exact] {} is free before he got beside it: straight onto it (ExactSpot.tick)", xyz(j.cell));
        }
    }

    /**
     * Between tasks (ClaudeLife.runWork, before the next step): on the cell? Checked against his feet, and they looked for again
     * before anything is said. Still theirs: beside it, waiting. Free and not on it: onto it again (4 tries), then the truth and
     * which block. True: hold the plan (still at it, or the job's ended here).
     */
    public static boolean settle(ClaudeEntity g, ServerPlayer p) {
        if (jobs == null) return false;
        Job j = jobs.get(g.getUUID()); if (j == null) return false;
        BlockPos at = feet(g);
        if (j.near) { // (a go_to near_them: the real comparison into the results)
            jobs.remove(g.getUUID());
            BlockPos them = Whereabouts.lookFor(g, p);
            String line = "go_to arrival: I'm at " + xyz(at) + (at.equals(j.cell) ? " (the spot it went to)" : "; the spot it went to, " + xyz(j.cell) + ", is " + rel(at, j.cell) + " of me") + (them == null ? "; where they are I can't see" : them.equals(at) ? "; on their block " + xyz(them) : "; their block " + xyz(them) + " is " + rel(at, them) + " of me (not on their block)");
            if (g.commandActive) g.commandResults.add(line);
            ClaudeMod.LOGGER.info("[Claude exact] {} (ExactSpot.settle)", line);
            return false;
        }
        if (j.approach) { // the walk over ended: a look round; in sight, their cell; else nobody there, and only now "which block?"
            Whereabouts.lookFor(g, p);
            if (Whereabouts.seesNow(g, p)) sighted(g, p, j);
            if (!j.approach) { queueWalk(g, p, j); return true; }
            jobs.remove(g.getUUID());
            lastLine = "i'm where i last knew you were (" + xyz(at) + ") and can't see you: which block? send me its coords";
            com.claudemod.director.ClaudeChat.say(p, lastLine, "exact: not found");
            ClaudeMod.LOGGER.info("[Claude exact] came over to their last known spot {}: not in sight there, asked which block (ExactSpot.settle)", xyz(j.cell));
            endJob(g, "done: came over to their last known spot " + xyz(j.cell) + ", they're not in sight; asked which block");
            return true;
        }
        if (onCell(g, j.cell)) { arrive(g, p, j); return j.theirs && g.skillQueue.isEmpty(); }
        LivingEntity in = occupied(g, p, j.cell);
        if (in != null) {
            if (j.waitStart < 0) j.waitStart = g.tickCount;
            if (!j.waitSaid && j.theirs) { j.waitSaid = true; lastLine = "you're still on it: stepping onto your block when you move"; com.claudemod.director.ClaudeChat.say(p, lastLine, "exact: waiting"); }
            if (g.tickCount - j.waitStart > WAIT_MAX) {
                jobs.remove(g.getUUID());
                String line = "done: they stayed on " + xyz(j.cell) + " two minutes; I'm beside it at " + xyz(at);
                ClaudeMod.LOGGER.info("[Claude exact] {} (ExactSpot.settle)", line);
                if (j.theirs) { lastLine = "you're still on your block (" + xyz(j.cell) + "), i'm right by it at " + xyz(at) + ". tell me when you've moved"; com.claudemod.director.ClaudeChat.say(p, lastLine, "exact: gave up waiting"); endJob(g, line); return true; }
                if (g.commandActive) g.commandResults.add(line);
                return false;
            }
            if (g.tickCount % 40 == 0 || j.waitStart == g.tickCount) ClaudeMod.LOGGER.info("[Claude exact] {} is on {} ({} s): he waits beside it at {}, no pushing (ExactSpot.settle)", in.getName().getString(), xyz(j.cell), (g.tickCount - j.waitStart) / 20, xyz(at));
            queueWalk(g, p, j);
            return true;
        }
        if (j.waitStart >= 0) { ClaudeMod.LOGGER.info("[Claude exact] {} is free now: stepping on (ExactSpot.settle)", xyz(j.cell)); j.waitStart = -1; }
        if (++j.tries <= 4) {
            if (j.tries > 1) ClaudeMod.LOGGER.info("[Claude exact] not on {} yet (he's at {}, {} off its middle, {}): onto it again, try {} (ExactSpot.settle)", xyz(j.cell), xyz(at), String.format("%.2f", offMiddle(g, j.cell)), rel(at, j.cell), j.tries);
            queueWalk(g, p, j);
            return true;
        }
        jobs.remove(g.getUUID());
        String line = "failed: couldn't get onto " + xyz(j.cell) + ": I'm at " + xyz(at) + ", it's " + rel(at, j.cell) + " of me";
        ClaudeMod.LOGGER.info("[Claude exact] {} (ExactSpot.settle)", line);
        if (j.theirs) { lastLine = "can't get onto it: i'm at " + xyz(at) + ", your block " + xyz(j.cell) + " is " + rel(at, j.cell) + " of me. which block, the one under you?"; com.claudemod.director.ClaudeChat.say(p, lastLine, "exact: couldn't"); endJob(g, line); return true; }
        if (g.commandActive) g.commandResults.add(line);
        g.lastSkillLine = "go_to " + xyz(j.cell) + " -> " + line;
        g.skillQueue.clear(); // (the rest of a sequence stood on that cell)
        return false;
    }

    /** On it: facing as asked, a fresh look for them, and only then the line (true by construction). */
    static void arrive(ClaudeEntity g, ServerPlayer p, Job j) {
        jobs.remove(g.getUUID());
        if (j.yaw != null) { g.setYRot(j.yaw); g.setYHeadRot(j.yaw); g.yBodyRot = j.yaw; if (j.pitch != null) g.setXRot(j.pitch); Perception.lookNow(g); }
        dones().put(g.getUUID(), new Done(j.cell, g.tickCount));
        String mid = String.format("%.2f", offMiddle(g, j.cell));
        if (!j.theirs) {
            String line = "on " + xyz(j.cell) + " (my feet cell, " + mid + " from its middle)" + (j.yaw == null ? "" : ", facing " + Math.round(j.yaw) + " / " + Math.round(j.pitch == null ? 0 : j.pitch));
            if (g.commandActive) g.commandResults.add("go_to arrival: " + line);
            ClaudeMod.LOGGER.info("[Claude exact] {} (ExactSpot.arrive)", line);
            return;
        }
        BlockPos now = Whereabouts.lookFor(g, p); // (looked for again before saying anything: they may have moved)
        boolean seen = Whereabouts.seesNow(g, p);
        String moved = now == null || !seen && now.equals(j.cell) ? "can't see where you went" : now.equals(j.cell) ? null : "you've moved: you're at " + xyz(now) + " now"; // (their last spot his own cell, unseen: they've gone, he doesn't know where)
        lastLine = moved == null ? "ok, on your block now (" + xyz(j.cell) + ")" : "ok, i'm on the block you were on (" + xyz(j.cell) + "). " + moved;
        String result = "go_to arrival: on their block " + xyz(j.cell) + " (my feet cell, " + mid + " from its middle; where they stood when they said it)" + (moved == null ? "" : now == null ? "; I can't see them now" : "; they've moved: at " + xyz(now) + " now (" + (seen ? "seen" : "last known") + ")");
        ClaudeMod.LOGGER.info("[Claude exact] {} (ExactSpot.arrive)", result);
        com.claudemod.director.ClaudeChat.say(p, lastLine, "exact: arrived");
        if (g.skillQueue.isEmpty()) endJob(g, result);
        else if (g.commandActive) g.commandResults.add(result);
    }

    /** The order was only this: done, said, and he stays put on the cell (they put him there for a reason) till their next ask. */
    static void endJob(ClaudeEntity g, String result) {
        g.lastSkillLine = "go_to exact_them -> " + result;
        g.commandResults.clear(); g.commandActive = false;
        g.playerGoal = "done (" + result + "): stay put and wait for their next ask";
        g.staying = true;
        ClaudeMod.LOGGER.info("[Claude exact] their order is done: staying put at {} for their next ask (ExactSpot.endJob)", xyz(feet(g)));
    }

    // ================================================================ what he may say

    /** A claim to be on their block / where they stood holds: his feet on the cell they asked him onto (lately), or on their seen cell. */
    public static boolean claimTrue(ClaudeEntity g, ServerPlayer p) {
        BlockPos at = feet(g);
        Job j = jobs == null ? null : jobs.get(g.getUUID());
        if (j != null && !j.near) return onCell(g, j.cell);
        Done d = dones().get(g.getUUID());
        if (d != null && g.tickCount - d.tick() < 20 * 60 * 5 && d.tick() <= g.tickCount) return at.equals(d.cell());
        BlockPos them = Whereabouts.seesNow(g, p) ? Whereabouts.spot(g, p) : null;
        return them != null && them.equals(at);
    }
    /** The true line in place of a claim that isn't so: where he is, where their block is, which way; and which block they mean. */
    public static String truthLine(ClaudeEntity g, ServerPlayer p) {
        BlockPos at = feet(g), cell = target(g);
        Done d = dones().get(g.getUUID());
        if (cell == null && d != null && g.tickCount - d.tick() < 20 * 60 * 5) cell = d.cell();
        if (cell == null) cell = Whereabouts.spot(g, p);
        if (cell == null) return "not on your block: i'm at " + xyz(at) + " and can't see you. which block, the one under you?";
        return "not on your block yet: i'm at " + xyz(at) + ", your block " + xyz(cell) + " is " + rel(at, cell) + " of me. which block, the one under you?";
    }
    /** For the chat model (ClaudeChat.truthFor): his feet against their block, exactly. */
    public static String feetTalk(ClaudeEntity g, ServerPlayer p) {
        BlockPos at = feet(g);
        StringBuilder sb = new StringBuilder("your feet are in the block at ").append(xyz(at));
        Job j = jobs == null ? null : jobs.get(g.getUUID());
        if (j != null && j.approach) sb.append("; you're on your way over to them (you can't see them yet: their exact block once you do), you're NOT on their block");
        else if (j != null && !j.near) sb.append(j.theirs ? "; you're getting onto the exact block they asked you onto, " : "; you're getting onto the exact block ").append(xyz(j.cell)).append(j.theirs ? " (where they stood when they said it)" : "").append(": ").append(onCell(g, j.cell) ? "you're on it" : "you're NOT on it, it's " + rel(at, j.cell) + " of you").append(j.waitStart >= 0 ? ", they're on it, you wait beside it" : "");
        else { Done d = dones().get(g.getUUID()); if (d != null && g.tickCount - d.tick() < 20 * 60 * 5 && d.tick() <= g.tickCount) sb.append("; the block they asked you onto is ").append(xyz(d.cell())).append(": ").append(at.equals(d.cell()) ? "you're on it" : "you're NOT on it, it's " + rel(at, d.cell()) + " of you"); }
        BlockPos them = Whereabouts.spot(g, p);
        sb.append(them == null ? "; their feet block you don't know right now" : "; their feet block as you know it: " + xyz(them) + (them.equals(at) ? "" : ", " + rel(at, them) + " of you"));
        { String lw = LessonWords.talk(g, p); if (!lw.isEmpty()) sb.append(". ").append(lw); } // (the lesson's places, the job's pin, their count, the resumed instruction)
        return sb.append(". Say you're on their block or where they stood ONLY when this says you're on it; otherwise say where you are and which way it is, and ask which block they mean if it's unclear").toString();
    }

    /** On his way over, they're in sight: their feet cell now, and onto it (beside it first while they're on it). */
    static void sighted(ClaudeEntity g, ServerPlayer p, Job j) {
        Ask a = resolve(g, p, "(came over: in sight now)");
        if (a == null) return;
        BlockPos was = j.cell; j.cell = a.cell(); j.approach = false; j.tries = 0; asks().put(g.getUUID(), a);
        final Task w = j.walk; if (w != null) { g.tasks.removeIf(x -> x == w); if (g.currentTask == w) { g.dropCurrent = true; g.getNavigation().stop(); } } j.walk = null;
        ClaudeMod.LOGGER.info("[Claude exact] in sight now ({} blocks off): their feet cell {} (last known was {}): onto it (ExactSpot.sighted)", Math.round(Math.sqrt(a.cell().distSqr(g.blockPosition()))), xyz(a.cell()), xyz(was));
    }

    // ================================================================ their lesson: he stays with them

    static final Pattern LESSON = Pattern.compile("\\b(learn|learning|lesson|teach|teaching|tutorial|show(ing)? (me|you|him)|walk(ing)? (me|you|him) through|watch(ing)? (me|them|how)|copy(ing)? (me|them))\\b");
    private static Map<UUID, Task> lessonTask; private static Map<UUID, Integer> lessonLogged; // (static, made on first use)
    /** Their order is a lesson (learn X from them, they're showing him), not done, them in his world: he stays with them. */
    public static boolean lesson(ClaudeEntity g, ServerPlayer p) {
        if (p == null || p.level() != g.level() || p.isSpectator()) return false;
        if (g.lessonOpen != null && !g.lessonOpen.isBlank()) return true;
        return lessonGoal(g.playerGoal);
    }
    /** A goal that names a lesson still open (the lesson words, not done). */
    public static boolean lessonGoal(String goal0) {
        String goal = goal0 == null ? "" : goal0.toLowerCase(Locale.ROOT);
        return !goal.isBlank() && !goal.startsWith("done") && !goal.startsWith("watch them build") && LESSON.matcher(goal).find() && !LESSON_END.matcher(goal).find()
                && !goal.matches("(?s).*\\b(teach|show|learn)\\w*\\b[^.!?]{0,60}\\b(later|sometime|some time|next time|tomorrow|another time|soon)\\b.*"); // (a lesson promised for later is no lesson now: 16:33 "I'll teach you a nether tunneling skill later" held him in one for 15 min) // ("lesson over: wait for their next ask" kept the lesson open, 15:59)
    }
    static final Pattern LESSON_END = Pattern.compile("\\b(lesson('?s| is)? (over|done|finished)|end (the|this|of the) lesson|class('?s)? (dismissed|over)|that'?s (all|it) for (the|this|today'?s) lesson|you can go back to (work|mining|what you were doing|your stuff)|go back to (work|mining|your (own )?(work|stuff|mine))|we'?re done with the lesson)\\b");
    /** A goal of theirs: a lesson opens it, "lesson over" closes it; any other goal leaves an open lesson alone. */
    public static void noteGoal(ClaudeEntity g, String goal) {
        if (goal == null || goal.isBlank()) return;
        String low = goal.toLowerCase(Locale.ROOT);
        if (LESSON_END.matcher(low).find()) { closeLesson(g, "their goal: " + goal); return; }
        if (lessonGoal(goal) && !goal.equals(g.lessonOpen)) { boolean was = g.lessonOpen != null && !g.lessonOpen.isBlank(); g.lessonOpen = goal; ClaudeMod.LOGGER.info("[Claude lesson] {} \"{}\": open till they end it (ExactSpot.noteGoal)", was ? "a new lesson" : "lesson opened", goal); }
        else if (g.lessonOpen != null && !g.lessonOpen.isBlank() && !goal.equals(g.lessonOpen)) ClaudeMod.LOGGER.info("[Claude lesson] goal now \"{}\": a step in the lesson \"{}\", which stays open (ExactSpot.noteGoal)", goal, g.lessonOpen);
    }
    /** Their chat line: "lesson over" and the like end the lesson. */
    public static void heardEnd(ClaudeEntity g, String words) {
        if (g.lessonOpen == null || g.lessonOpen.isBlank() || words == null) return;
        if (LESSON_END.matcher(words.toLowerCase(Locale.ROOT)).find()) closeLesson(g, "they said \"" + words + "\"");
    }
    static void closeLesson(ClaudeEntity g, String why) {
        if (g.lessonOpen == null || g.lessonOpen.isBlank()) return;
        ClaudeMod.LOGGER.info("[Claude lesson] the lesson \"{}\" is over: {} (ExactSpot.closeLesson)", g.lessonOpen, why);
        g.lessonOpen = "";
        LessonWords.lessonClosed(g); // (its count, its pin, its places and its last instruction end with it)
        if (lessonGoal(g.playerGoal)) g.playerGoal = "done (" + g.playerGoal + "): the lesson's over";
    }
    static final java.util.Set<String> WITH_THEM = java.util.Set.of("go_to", "follow", "wait", "copy_me", "watch_build", "eat", "fight", "shelter", "sleep");
    /** His own work, set aside in a lesson: digs, patches, his own walks and stations (never a fight, a flight, eating, cover, a walk to his bed or onto their block). */
    static boolean ownWork(ClaudeEntity g, Task t) {
        if (t instanceof Task.GoTo go) {
            Job j = jobs == null ? null : jobs.get(g.getUUID());
            if (j != null && (t == j.walk || t == j.beside)) return false;
            return !g.level().getBlockState(go.pos()).is(net.minecraft.tags.BlockTags.BEDS) && !g.level().getBlockState(go.pos().below()).is(net.minecraft.tags.BlockTags.BEDS);
        }
        return t instanceof Task.Break || t instanceof Task.Place || t instanceof Task.WallTorch || t instanceof Task.MineVisibleOres || t instanceof Task.ChopTree || t instanceof Task.FinishTree
                || t instanceof Task.Replant || t instanceof Task.Till || t instanceof Task.CheckForCave || t instanceof Task.SealStep || t instanceof Task.Deposit || t instanceof Task.Smelt
                || t instanceof Task.FurnaceLoad || t instanceof Task.FurnaceTake || t instanceof Task.Chest || t instanceof Task.Fish;
    }
    /**
     * Each tick (top of ClaudeLife.runWork): their lesson on and no order of theirs running: his own plan's steps and his own work in
     * hand go; he goes over to them (4+ off) or stands by watching them. True: that's what he's doing (runWork stops there).
     */
    public static boolean lessonHold(ClaudeEntity g, ServerPlayer p) {
        if (g.commandActive || g.staying || !lesson(g, p)) return false;
        if (lessonTask == null) lessonTask = new HashMap<>();
        if (lessonLogged == null) lessonLogged = new HashMap<>();
        g.nextPlanTick = Math.max(g.nextPlanTick, g.tickCount + 100); // (his own planning waits: the lesson is the job)
        { Task m0 = lessonTask.get(g.getUUID()); if (m0 != null && (g.currentTask == m0 || g.tasks.contains(m0)) && g.skillQueue.isEmpty()) { // (his walk to them or his watch under way: its route legs are his, nothing to set aside)
            BlockPos seen = Whereabouts.seesNow(g, p) ? Whereabouts.spot(g, p) : null;
            if (!(m0 instanceof Task.GoTo go0) || seen == null || seen.distSqr(go0.pos()) <= 16 || go0.pos().equals(LessonWords.holdCell(g, p))) return true;
            g.tasks.removeIf(x -> x == m0); if (g.currentTask == m0) { g.dropCurrent = true; g.getNavigation().stop(); } lessonTask.remove(g.getUUID());
            ClaudeMod.LOGGER.info("[Claude lesson] he sees them at {}, not where he was walking ({}): over to them there (ExactSpot.lessonHold)", xyz(seen), xyz(go0.pos()));
        } }
        java.util.List<String> dropped = new java.util.ArrayList<>();
        for (var it = g.skillQueue.iterator(); it.hasNext(); ) { var s = it.next(); if (!WITH_THEM.contains(s.skill())) { dropped.add(s.skill() + (s.target().isBlank() ? "" : " " + s.target())); it.remove(); } }
        Task mine = lessonTask.get(g.getUUID()), cur = g.currentTask;
        if (cur != null && cur != mine && ownWork(g, cur)) { g.dropCurrent = true; g.getNavigation().stop(); dropped.add(cur.getClass().getSimpleName() + " (in hand)"); }
        int n0 = g.tasks.size(); g.tasks.removeIf(x -> x != mine && ownWork(g, x)); if (g.tasks.size() < n0) dropped.add((n0 - g.tasks.size()) + " queued task(s)");
        if (!dropped.isEmpty()) ClaudeMod.LOGGER.info("[Claude lesson] their lesson (\"{}\") is the job: his own {} set aside (ExactSpot.lessonHold)", g.playerGoal, dropped);
        if (!g.skillQueue.isEmpty()) return false; // (a walk to them, a wait: as planned)
        if (mine != null && (g.currentTask == mine || g.tasks.contains(mine))) return true;
        if (cur != null && cur != mine && !ownWork(g, cur)) return true; // (a reflex first: a fight, eating, cover)
        if (!g.tasks.isEmpty()) return true;
        { BlockPos hc = LessonWords.holdCell(g, p); // the place they put him / held the job to / the lesson's water he's in: held till they move him
          if (hc != null && (!feet(g).equals(hc) || offMiddle(g, hc) > 0.45)) { Task back = new Task.GoTo(hc, ON); lessonTask.put(g.getUUID(), back); g.tasks.add(back); ClaudeMod.LOGGER.info("[Claude lesson] off the place they put him ({}; he's at {}, {} off its middle): back onto it, not over to them (ExactSpot.lessonHold)", xyz(hc), xyz(feet(g)), String.format("%.2f", offMiddle(g, hc))); return true; }
          if (hc != null) { Task w = new Task.Wait(40, true); lessonTask.put(g.getUUID(), w); g.tasks.add(w); Integer l0 = lessonLogged.get(g.getUUID()); if (l0 == null || g.tickCount - l0 > 20 * 60) { lessonLogged.put(g.getUUID(), g.tickCount); ClaudeMod.LOGGER.info("[Claude lesson] holding {} where they put him, watching them, no walk over to them (ExactSpot.lessonHold)", xyz(hc)); } return true; } }
        BlockPos them = Whereabouts.spot(g, p);
        if (them == null) { Whereabouts.askWhere(g, p); Task w = new Task.Wait(100, true); lessonTask.put(g.getUUID(), w); g.tasks.add(w); return true; }
        double d = Math.sqrt(them.distSqr(g.blockPosition()));
        boolean placed = g.staying || g.lastStep != null && g.lastStep.skill().equals("go_to") && d <= 16; // (a spot they sent him to, or told to stay: he holds it, not walks to them; 15:51 he left the pocket's water 50 ms after arriving)
        Task t = d > 4 && !placed ? new Task.GoTo(them, 3) : new Task.Wait(40, true);
        lessonTask.put(g.getUUID(), t); g.tasks.add(t);
        Integer last = lessonLogged.get(g.getUUID());
        if (d > 4 || last == null || g.tickCount - last > 20 * 60) { lessonLogged.put(g.getUUID(), g.tickCount); ClaudeMod.LOGGER.info("[Claude lesson] their lesson (\"{}\") is on, no order running: {} (ExactSpot.lessonHold)", g.playerGoal, d > 4 ? "over to them at " + xyz(them) + " (" + Math.round(d) + " blocks), not his own work" : "standing by them, watching"); }
        return true;
    }
}
