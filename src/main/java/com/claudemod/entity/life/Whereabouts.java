package com.claudemod.entity.life;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.claudemod.context.ActivityEvent;
import com.claudemod.entity.ClaudeEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Where the people he plays with are, as far as he knows it (the user, 2026-10-30: "he shouldnt know what your coords are
 * relative to him unless he can see or hear you"). The server knows every player's position; he knows only:
 *  - what he SEES (Perception's sight: in his view, nothing opaque between): their exact spot, live while in view;
 *  - what he HEARS (their footsteps, the blocks they break or place within earshot, their swings: Perception's hearing places the
 *    sound, roughly, the error growing with distance);
 *  - what they TELL him (chat with coords and "I'm/me/here" in it: "i'm at 120 64 -30"; "come here" alone tells nothing);
 *  - right on him (touch).
 * Each sighting replaces the last; what's left is a last-known spot with a time and how he knows it. A spot he went to and found
 * empty (looked round, nobody) is spent: he doesn't know where they are any more, until he sees, hears or is told again. Not
 * knowing, he asks once ("where are you?") and gets on with his own work; he never waits on the answer.
 * What a player sees of another: where they are, what they hold, their armor, whether they're on fire or sneaking. Never their
 * health, hunger, inventory or what their crosshair is on (that only roughly, from close by, watching their head).
 */
public final class Whereabouts {
    private Whereabouts() {}

    public enum How { SEEN, HEARD, TOLD }

    /** One thing he knows of where someone is: the spot, the world it's in, when (game time), how, and the sound if heard. */
    public static final class Spot {
        public final Vec3 at; public final ResourceKey<Level> dim; public final long time; public final How how; public final String sound;
        boolean spent, asked; // spent: he went there and they'd gone; asked: "where are you?" said since
        Spot(Vec3 at, ResourceKey<Level> dim, long time, How how, String sound) { this.at = at; this.dim = dim; this.time = time; this.how = how; this.sound = sound; }
        public boolean spent() { return spent; }
        public boolean asked() { return asked; }
    }

    private static final Map<UUID, Map<UUID, Spot>> BOOK = new HashMap<>();
    private static final Map<UUID, Set<UUID>> ASKED_BLANK = new HashMap<>(); // asked with no spot at all
    static final double LOOKED_THERE = 6.0; // stood this close to the spot, looked round: they'd be in sight

    /** His book of where people were, kept across a relaunch (the user: he should remember my last seen location; a restart wiped it and he asked "send coords?"). */
    public static net.minecraft.nbt.CompoundTag save(ClaudeEntity g) {
        net.minecraft.nbt.CompoundTag t = new net.minecraft.nbt.CompoundTag();
        for (var e : book(g).entrySet()) { Spot s = e.getValue(); if (s.spent) continue; net.minecraft.nbt.CompoundTag c = new net.minecraft.nbt.CompoundTag();
            c.putDouble("x", s.at.x); c.putDouble("y", s.at.y); c.putDouble("z", s.at.z); c.putString("dim", s.dim.location().toString()); c.putLong("time", s.time); c.putString("how", s.how.name()); t.put(e.getKey().toString(), c); }
        return t;
    }
    public static void load(ClaudeEntity g, net.minecraft.nbt.CompoundTag t) {
        for (String k : t.getAllKeys()) { try { net.minecraft.nbt.CompoundTag c = t.getCompound(k);
            ResourceKey<Level> dim = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, net.minecraft.resources.ResourceLocation.parse(c.getString("dim")));
            book(g).put(UUID.fromString(k), new Spot(new Vec3(c.getDouble("x"), c.getDouble("y"), c.getDouble("z")), dim, c.getLong("time"), How.valueOf(c.getString("how")), null)); } catch (Exception ignored) {} }
    }
    static Map<UUID, Spot> book(ClaudeEntity g) { return BOOK.computeIfAbsent(g.getUUID(), k -> new HashMap<>()); }
    static long now(ClaudeEntity g) { return g.level().getGameTime(); }

    /** In his view right now (or right on him): not a spectator (they're invisible to a survival player). */
    public static boolean seesNow(ClaudeEntity g, Player p) {
        return p != null && p.level() == g.level() && !p.isRemoved() && !p.isSpectator() && (Perception.seesNow(g, p) || p.distanceTo(g) <= Perception.TOUCH);
    }
    /** He knows where they are this moment: seen in the last 10 s, heard in the last 5, or on him (Perception's own memory). */
    public static boolean aware(ClaudeEntity g, Player p) {
        return p != null && p.level() == g.level() && ((!p.isSpectator() && Perception.aware(g, p)) || Perception.taggedWithin(g, p, Perception.SEEN_MEMORY) != null); // (their nametag, a spectator's too)
    }

    /** He has a live fix on them: in sight or earshot of late, or their own word on where they are (not yet found wrong). */
    public static boolean current(ClaudeEntity g, Player p) {
        if (aware(g, p)) return true;
        Spot s = of(g, p);
        return s != null && !s.spent && s.dim == g.level().dimension(); // any last known spot (seen, heard, told) until he's been there and found them gone: a player heads to where his friend was
    }

    /** What he knows of where they are, brought up to date from his senses now. Null: never seen, heard or told. */
    public static Spot of(ClaudeEntity g, Player p) {
        if (p == null) return null;
        Map<UUID, Spot> book = book(g); UUID id = p.getUUID(); Spot s = book.get(id);
        if (p.level() == g.level() && !p.isRemoved()) {
            long now = now(g);
            if (!p.isSpectator() && p.distanceTo(g) <= Perception.TOUCH) { s = new Spot(p.position(), g.level().dimension(), now, How.SEEN, null); book.put(id, s); } // right on him
            else { // (where his eyes last put them, at his last look: never the server's live position, even a tick after they left his view)
                Perception.Mind m = Perception.mind(g); Perception.Contact best = null;
                for (Perception.Contact c : p.isSpectator() ? new Perception.Contact[] {m.tagged.get(id)} : new Perception.Contact[] {m.seen.get(id), m.heard.get(id), m.felt.get(id), m.tagged.get(id)}) if (c != null && c.tick() <= g.tickCount && (best == null || c.tick() > best.tick())) best = c; // (a contact from before he respawned: not one of this life's)
                if (best != null) {
                    long t = now - (g.tickCount - best.tick());
                    if (s == null || t > s.time || t == s.time && s.how == How.TOLD && best.how() != Perception.How.HEARD || t == s.time && s.how == How.SEEN && best.how() == Perception.How.SEEN && !best.at().equals(s.at)) { /* (his look later in the same tick wins: moved that tick, the spot stayed where they'd been; stage 212) */ s = new Spot(best.at(), g.level().dimension(), t, best.how() == Perception.How.HEARD ? How.HEARD : How.SEEN, best.sound()); Spot was = book.put(id, s); if (was == null || was.spent || t - was.time > 600) com.claudemod.ClaudeMod.LOGGER.info("[Claude whereabouts] regained them by {} ({}) at {}, {} blocks off really, contact {} ticks old (before: {}) (Whereabouts.of)", s.how, best.sound() == null ? "-" : best.sound(), BlockPos.containing(s.at).toShortString(), (int) p.distanceTo(g), g.tickCount - best.tick(), was == null ? "none" : (was.spent ? "spent " : "") + was.how + " " + (t - was.time) / 20 + " s older"); } // (his own eyes over their word, the same moment) // (regaining them logged: 01:10 he "last saw" them 180 blocks off, source unproven)
                }
            }
        }
        if (s != null && !s.spent) { Set<UUID> a = ASKED_BLANK.get(g.getUUID()); if (a != null) a.remove(id); }
        return s;
    }

    /** The spot he believes they're at (exact while seen), or null: he doesn't know (never, spent, or not in his world). */
    public static BlockPos spot(ClaudeEntity g, Player p) {
        Spot s = of(g, p);
        return s == null || s.spent || s.dim != g.level().dimension() ? null : BlockPos.containing(s.at);
    }
    public static Vec3 believed(ClaudeEntity g, Player p) {
        Spot s = of(g, p);
        if (s == null || s.spent || s.dim != g.level().dimension()) return null;
        return s.at;
    }

    /**
     * A job needs where they are ("come here", "give me", "follow"): a look round first, the way a
     * player turns about for his friend; then the spot as he knows it (null: he doesn't).
     */
    public static BlockPos lookFor(ClaudeEntity g, Player p) {
        if (p != null && p.level() == g.level()) Perception.glance(g); // (a fresh look: where his eyes put them a moment ago may not be where they are now)
        return spot(g, p);
    }

    /** They told him where they are (chat coords). */
    public static void told(ClaudeEntity g, Player p, BlockPos at) {
        toldQuiet(g, p, Vec3.atBottomCenterOf(at));
        com.claudemod.ClaudeMod.LOGGER.info("[Claude whereabouts] they told him they're at {}", at.toShortString());
    }
    /** The same, unlogged (the test harness's old stages: their mock calls out its spot every tick, as those stages were written to). */
    public static void toldQuiet(ClaudeEntity g, Player p, Vec3 at) {
        book(g).put(p.getUUID(), new Spot(at, g.level().dimension(), now(g), How.TOLD, null));
        Set<UUID> a = ASKED_BLANK.get(g.getUUID()); if (a != null) a.remove(p.getUUID());
    }

    private static final Pattern XYZ = Pattern.compile("(-?\\d+)[ ,]+(-?\\d+)[ ,]+(-?\\d+)");
    private static final Pattern SELF = Pattern.compile("\\b(i'?m|i am|im|me|my|we'?re|we are|here|i'?ll be|meet)\\b");
    /** Their chat line: coords with "I'm / me / here" in it are where they are ("i'm at 120 64 -30", "come to me at 5 70 9"). */
    public static void heardChat(ServerPlayer p, String raw) {
        if (raw == null || p instanceof ClaudeEntity) return;
        { ClaudeEntity g0 = claudeOf(p); if (g0 != null) heardPointing(g0, p, raw); } // "there's some lava over here": the lava they mean
        String low = raw.toLowerCase();
        Matcher m = XYZ.matcher(low);
        if (!m.find() || !SELF.matcher(low).find()) return;
        ClaudeEntity g = claudeOf(p); if (g == null) return;
        try { told(g, p, new BlockPos(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)))); } catch (NumberFormatException e) { }
    }

    /**
     * Each few ticks: his senses into the book; and standing where he last knew them, a look round: nobody, and the spot is spent
     * (a player at his friend's last spot sees they've gone).
     */
    public static void tick(ClaudeEntity g, Player p) {
        if (p == null || g.tickCount % 4 != 2) return;
        Spot s = of(g, p);
        if (s == null || s.spent || s.dim != g.level().dimension() || seesNow(g, p) || g.level().getGameTime() - s.time < 40) return; // (seen or heard a moment ago: they're about)
        double dx = s.at.x - g.getX(), dz = s.at.z - g.getZ();
        if (dx * dx + dz * dz > LOOKED_THERE * LOOKED_THERE || Math.abs(s.at.y - g.getY()) > 4) return;
        Perception.glance(g);
        if (of(g, p) != s) return; // seen after all
        s.spent = true;
        com.claudemod.ClaudeMod.LOGGER.info("[Claude whereabouts] (debug: they are really {} from him, removed={}, line of sight eye-to-eye clear={}, his yaw {})", String.format("%.2f", p.distanceTo(g)), p.isRemoved(), Perception.clear((net.minecraft.server.level.ServerLevel) g.level(), g.getEyePosition(), p.getEyePosition()), (int) g.getYRot()); // 03:36:40 "nobody there" with them 2 blocks off: no line said why
        com.claudemod.ClaudeMod.LOGGER.info("[Claude whereabouts] at the spot he last {} them ({}, {} s ago): nobody there", s.how == How.SEEN ? "saw" : s.how == How.HEARD ? "heard" : "was told", BlockPos.containing(s.at).toShortString(), (now(g) - s.time) / 20);
    }

    /**
     * He needs to know where they are and doesn't (never knew, the spot spent, or only an old spot: not seen or heard of late):
     * "where are you?" once (a new sighting, sound or answer resets it). True when he said it. He never waits on the answer.
     */
    public static boolean askWhere(ClaudeEntity g, Player p) {
        if (!(p instanceof ServerPlayer sp)) return false;
        Spot s = of(g, p);
        if (current(g, p)) return false; // he knows where they are this moment
        if (!Perception.lookedRound(g, 100)) { Perception.lookRound(g, p.getUUID()); com.claudemod.ClaudeMod.LOGGER.info("[Claude whereabouts] lost track of them: looking all the way round first (Whereabouts.askWhere)"); return false; } // a player turns about for his friend's nametag before asking
        if (s != null) { if (s.asked) return false; s.asked = true; }
        else if (!ASKED_BLANK.computeIfAbsent(g.getUUID(), k -> new HashSet<>()).add(p.getUUID())) return false;
        com.claudemod.ClaudeMod.LOGGER.info("[Claude whereabouts] doesn't know where they are: asking, and on with his own work");
        com.claudemod.director.ClaudeChat.say(sp, s != null && s.spent ? "where'd you go? not where i last saw you. send coords?" : s != null ? "where'd you get to? lost track of you, send coords?" : "where are you? can't see you from here, send coords?", "where are you");
        return true;
    }

    /** For his prompts: where they are as he knows it, and how he knows. */
    public static String describe(ClaudeEntity g, Player p) {
        if (p == null) return "nobody's playing with you right now";
        Spot s = of(g, p);
        if (seesNow(g, p)) {
            double d = g.distanceTo(p);
            return "you can see them, " + Math.round(d) + " blocks " + Perception.compass(g.position(), p.position()) + " of you (at " + p.blockPosition().toShortString() + ")" + (d < 8 ? ", right by you" : "");
        }
        if (s == null) return "you don't know where they are: you haven't seen or heard them (they can tell you their coords; ask if you need them)";
        String at = BlockPos.containing(s.at).toShortString(), age = ago(now(g) - s.time);
        String rel = s.dim == g.level().dimension() ? Math.round(s.at.distanceTo(g.position())) + " blocks " + Perception.compass(g.position(), s.at) + " of you" : "in " + s.dim.location().getPath();
        if (s.spent) return "you don't know where they are now: you went to where you last " + (s.how == How.SEEN ? "saw" : s.how == How.HEARD ? "heard" : "were told") + " them (" + at + ", " + age + ") and they'd gone";
        String assume = "; assume they're still there unless they said they moved: go there, don't ask (ask only if you get there and they've gone)"; // (the user: he should remember their last-seen spot and assume they're there from context, 04:43)
        return switch (s.how) {
            case SEEN -> "you last saw them at " + at + " (" + rel + "), " + age + "; you can't see them now" + assume;
            case HEARD -> "you heard them (" + heardAs(s.sound) + ") about " + rel + " (around " + at + "), " + age + "; you can't see them" + assume;
            case TOLD -> "they told you they're at " + at + " (" + rel + "), " + age + assume;
        };
    }
    static String heardAs(String sound) {
        if (sound == null) return "something";
        if (sound.contains("step")) return "footsteps";
        if (sound.contains("break")) return "a block breaking";
        if (sound.contains("place")) return "a block going down";
        if (sound.contains("attack") || sound.contains("hurt")) return "a fight";
        return sound.replace('.', ' ').replace('_', ' ');
    }
    static String ago(long ticks) {
        long sec = Math.max(0, ticks / 20);
        return sec < 60 ? sec + " s ago" : sec < 3600 ? sec / 60 + " min ago" : sec / 3600 + " h ago";
    }

    /** The Claude playing with this player (no side effects), or null. */
    public static ClaudeEntity claudeOf(ServerPlayer p) {
        if (p == null || p.getServer() == null || p instanceof ClaudeEntity) return null;
        for (ServerPlayer sp : p.getServer().getPlayerList().getPlayers()) if (sp instanceof ClaudeEntity c && c.isAlive() && c.getTarget() == p) return c;
        return null;
    }

    // ---- what he hears of them that the server plays as a level event, not a sound (block breaking), and placing with them named

    public static void onBreak(net.neoforged.neoforge.event.level.BlockEvent.BreakEvent e) {
        if (!(e.getPlayer() instanceof ServerPlayer p) || p instanceof ClaudeEntity || !(e.getLevel() instanceof ServerLevel lv)) return;
        var snd = e.getState().getSoundType();
        Perception.heard(lv, Vec3.atCenterOf(e.getPos()), p, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(snd.getBreakSound()), SoundSource.BLOCKS, (snd.getVolume() + 1f) / 2f);
    }
    public static void onPlace(net.neoforged.neoforge.event.level.BlockEvent.EntityPlaceEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || p instanceof ClaudeEntity || !(e.getLevel() instanceof ServerLevel lv)) return;
        var snd = e.getPlacedBlock().getSoundType();
        Perception.heard(lv, Vec3.atCenterOf(e.getPos()), p, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(snd.getPlaceSound()), SoundSource.BLOCKS, (snd.getVolume() + 1f) / 2f);
    }

    /**
     * What of their doings reaches him: chat, advancements and deaths (in everyone's chat), else only what he saw them do. Seen:
     * the act, not what a watcher can't see (what went into a chest; how much a hit took off). Null: he didn't see it.
     */
    public static ActivityEvent asPerceived(ServerPlayer p, ActivityEvent e) {
        ActivityEvent inner = e instanceof ActivityEvent.Located l ? l.event() : e;
        if (inner instanceof ActivityEvent.Chat || inner instanceof ActivityEvent.Advancement || inner instanceof ActivityEvent.Died) return e;
        ClaudeEntity g = claudeOf(p);
        if (g == null || !seesNow(g, p)) return null;
        ActivityEvent shown = inner;
        if (inner instanceof ActivityEvent.Container c) shown = new ActivityEvent.Container("used a chest or furnace (what went in or out you couldn't see)", c.timestamp());
        else if (inner instanceof ActivityEvent.Damage d) shown = new ActivityEvent.Container("got hurt (" + ActivityEvent.friendly(d.source()) + ")", d.timestamp());
        if (shown == inner) return e;
        return e instanceof ActivityEvent.Located l ? new ActivityEvent.Located(shown, l.dimension(), l.biomeId(), l.pos()) : shown;
    }

    /** A test's clean slate. */
    public static void reset(ClaudeEntity g) { BOOK.remove(g.getUUID()); ASKED_BLANK.remove(g.getUUID()); if (POINTED != null) POINTED.remove(g.getUUID()); }

    // ---- lava they point him to ("there's some lava over here. follow me", "hear this lava?", "you should know where it is") ----
    /** Lava they pointed him to: who, about where they were (as he knows it), when, their words; asked: "where exactly?" said since. */
    public static final class Pointed { public final UUID who; public Vec3 at; public long time; public String words; public boolean asked; public BlockPos seen; Pointed(UUID who, Vec3 at, long time, String words) { this.who = who; this.at = at; this.time = time; this.words = words; } }
    private static Map<UUID, Pointed> POINTED; // (static: fields added live start null)
    private static final Pattern LAVA_WORD = Pattern.compile("\\blava\\b");
    private static final Pattern POINTING = Pattern.compile("\\b(here|this|that|follow|hear|heard|hearing|listen|near|close|by me|over there|this way|right there)\\b");
    private static final Pattern BACK_TO_IT = Pattern.compile("\\b(hear (it|this|that)|know where it is|the one you (can )?hear|that one|this one|wrong way|over here|this way|follow me|right here|it'?s here|down here)\\b");
    static final long POINT_FOR = 20 * 60 * 10; // ten minutes: a pool they pointed out stays what "the lava" means
    /** Their pointing is done with (the lava they meant is used up): "the lava" means any again. */
    public static void pointedDone(ClaudeEntity g) { if (POINTED != null) POINTED.remove(g.getUUID()); }
    /** The lava they pointed him to in the last 10 minutes, or null. */
    public static Pointed pointed(ClaudeEntity g) { if (POINTED == null) return null; Pointed p = POINTED.get(g.getUUID()); return p == null || now(g) - p.time > POINT_FOR || p.time > now(g) ? null : p; }
    /** Their chat line pointing at lava (or back at the lava they pointed to): where he knows them to be, now. */
    static void heardPointing(ClaudeEntity g, ServerPlayer p, String raw) {
        String low = raw.toLowerCase(java.util.Locale.ROOT);
        boolean lava = LAVA_WORD.matcher(low).find(); Pointed was = pointed(g);
        boolean fresh = lava && POINTING.matcher(low).find();
        boolean back = !fresh && was != null && was.who.equals(p.getUUID()) && (lava || BACK_TO_IT.matcher(low).find());
        if (!fresh && !back) return;
        Vec3 at = believed(g, p); // where his senses put them, never the server's live spot
        if (POINTED == null) POINTED = new HashMap<>();
        Pointed pt = was != null && was.who.equals(p.getUUID()) ? was : new Pointed(p.getUUID(), at, now(g), raw);
        if (at != null) pt.at = at; pt.time = now(g); pt.words = raw; pt.asked = false; if (fresh && at != null && pt.seen != null && pt.seen.distToCenterSqr(at) > 12 * 12) pt.seen = null; // (pointed somewhere new)
        POINTED.put(g.getUUID(), pt);
        com.claudemod.ClaudeMod.LOGGER.info("[Claude whereabouts] they {} lava (\"{}\"): {} (Whereabouts.heardPointing)", fresh ? "pointed him to" : "pointed again to the", raw, at == null ? "he doesn't know where they are" : "where he knows them to be, about " + BlockPos.containing(at).toShortString());
    }
}
