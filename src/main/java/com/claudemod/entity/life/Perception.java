package com.claudemod.entity.life;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import com.claudemod.entity.ClaudeEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * What he knows of the things around him, the way a player does (the user, 2026-10-29: "the senses of the player"): what he SEES,
 * what he HEARS, and what is right on him. Everything else in the world is unknown to him, whatever the server knows.
 *
 * Sight: a player's screen at Quake Pro FOV (110 degrees vertical, 16:9, so about 137 across) about where his head points,
 * out to 20 chunks for blocks and to each entity type's own client tracking range (what a player's client is even sent), with
 * the line of sight blocked by anything opaque (glass, leaves, fences and water let it through; lava doesn't). Something he saw
 * stays known for 10 s where he last saw it (it went round the corner, it's still there).
 * Hearing: every sound the server plays, within that sound's vanilla range (16 blocks at volume 1, more when louder), through
 * walls as in the game; the source placed from the sound alone: exact close by, the error growing with distance (about a tenth
 * of it across, a quarter up and down, the way stereo and loudness tell a player). Heard things stay known for 5 s.
 * Touch: anything within 2.5 blocks, and whatever hits him (with a turn of the head toward it, like the screen tilt).
 * He turns his head to a hostile he hears but can't see, the way a player turns to a groan behind him.
 */
public final class Perception {
    private Perception() {}

    public static final double FOV_V = 110, ASPECT = 16.0 / 9.0;
    static final double TAN_V = Math.tan(Math.toRadians(FOV_V / 2)), TAN_H = TAN_V * ASPECT; // half-height, half-width of the view at distance 1
    public static final int RENDER = 20 * 16; // 20 chunks
    static final double TOUCH = 2.5;
    static final int SEEN_MEMORY = 200, HEARD_MEMORY = 100, FELT_MEMORY = 100, TURN_FOR = 30;

    public enum How { SEEN, HEARD, FELT }
    /** One thing he knows of: what, where he believes it is, when, and how he knows. */
    public record Contact(UUID id, EntityType<?> type, Vec3 at, int tick, How how, String sound) {}

    static final class Mind {
        final Map<UUID, Contact> seen = new HashMap<>(), heard = new HashMap<>(), felt = new HashMap<>();
        final Map<UUID, Contact> tagged = new HashMap<>(); // players whose nametag he saw (their spot only)
        int spinLeft, spinEnd = -100000; UUID askFor; // a full turn round in progress, when the last one ended, whom to ask after it
        final ArrayDeque<Contact> sounds = new ArrayDeque<>(); // the latest sounds, for his account of what's about
        Vec3 turnTo; int turnTick = -1000;
        final java.util.Map<java.util.UUID, double[]> lookedFor = new java.util.HashMap<>(); // per source he turned to and didn't see: x, y, z, distance then
        int turns; // (tests: how many times a sound turned him)
    }
    private static final Map<UUID, Mind> minds = new HashMap<>();
    private static final List<WeakReference<ClaudeEntity>> listeners = new ArrayList<>();
    static Mind mind(ClaudeEntity g) { return minds.computeIfAbsent(g.getUUID(), k -> new Mind()); }

    // ---- sight ----

    /** Every 4 ticks: what is in view now. Called after his work's own tick, so a turn to a sound wins over the idle head. */
    public static void tick(ClaudeEntity g) {
        if (g.level().isClientSide) return;
        if (listeners.stream().noneMatch(r -> r.get() == g)) { listeners.removeIf(r -> r.get() == null || r.get().isRemoved()); listeners.add(new WeakReference<>(g)); }
        Mind m = mind(g); ServerLevel level = (ServerLevel) g.level(); int now = g.tickCount;
        if (m.spinLeft > 0) { // the turn round: 12 degrees a tick, a full circle in 1.5 s, his sight with it
            m.spinLeft--; float yaw = g.getYRot() + 12f; g.setYRot(yaw); g.yBodyRot = yaw; g.setYHeadRot(yaw); lookNow(g);
            if (m.spinLeft % 5 == 0) { scanFluids(g, level); scanStructures(g, level); scanSigns(g, level); } // (a look round sees what faces him as he turns, 60 degrees a scan inside his view: the lava and water, a structure's blocks, a spawner; never all round at once)
            if (m.spinLeft % 10 == 0) FarSight.scan(g, level, false); // (and the horizon all round)
            if (m.spinLeft == 0) {
                m.spinEnd = now; com.claudemod.ClaudeMod.LOGGER.info("[Claude sense] looked all the way round (Perception.tick)");
                if (m.askFor != null) { UUID id = m.askFor; m.askFor = null; var pl = level.getPlayerByUUID(id); if (pl != null) Whereabouts.askWhere(g, pl); } // nothing turned up: now he asks (askWhere checks again)
            }
            return;
        }
        if (m.turnTo != null && now - m.turnTick <= TURN_FOR && !precise(g)) {
            g.getLookControl().setLookAt(m.turnTo.x, m.turnTo.y, m.turnTo.z);
            if (g.getNavigation().isDone() && !(g.currentTask instanceof Task.GoTo)) { // standing: the whole of him turns (a head alone stops at the shoulder), about a quarter turn a tick like a flick of the mouse
                float want = (float) Math.toDegrees(Math.atan2(-(m.turnTo.x - g.getX()), m.turnTo.z - g.getZ()));
                float yaw = g.getYRot() + net.minecraft.util.Mth.clamp(net.minecraft.util.Mth.wrapDegrees(want - g.getYRot()), -45f, 45f);
                g.setYRot(yaw); g.yBodyRot = yaw; g.setYHeadRot(yaw);
            }
        }
        if (now % 20 == 2) scanFluids(g, level); // (the lava and water he can see, each second)
        if (now % 20 == 9) drips(g, level); // (the drips he can see under a block with lava or water on it, each second: the client's own particles)
        if (now % 20 == 12) scanStructures(g, level); // (the structures he can see, each second)
        if (now % FarSight.SCAN_EVERY == 23) FarSight.scan(g, level, false); // (far off, every 3 s: his rays out to 320, the Nether's fog at 96)
        if (now % 20 == 7) scanSigns(g, level); // (the hazard blocks he can see, each second)
        if (now % 20 == 17) earLava(g, level); // (the lava he can hear, each second: the client's own sampling)
        if (now % 4 != 0) return;
        lookNow(g);
        if (now % 200 == 0) { m.seen.values().removeIf(c -> now - c.tick > 20 * 60); m.heard.values().removeIf(c -> now - c.tick > 20 * 60); m.felt.values().removeIf(c -> now - c.tick > 20 * 60); }
    }

    /** A look round where his head points, now (each 4 ticks, and when he turns to set off somewhere). */
    public static void lookNow(ClaudeEntity g) { lookAlong(g, g.getViewVector(1f)); }

    /** A look round, the four quarters, as a player does before choosing where to hole up (a second's turn of the mouse). */
    public static void glance(ClaudeEntity g) {
        float yaw = g.getYRot();
        for (int k = 0; k < 4; k++) { double a = Math.toRadians(yaw + 90 * k); lookAlong(g, new Vec3(-Math.sin(a), 0, Math.cos(a))); }
    }

    static void lookAlong(ClaudeEntity g, Vec3 f) {
        Mind m = mind(g); ServerLevel level = (ServerLevel) g.level(); int now = g.tickCount;
        Vec3 eye = g.getEyePosition();
        Vec3 r = f.cross(new Vec3(0, 1, 0)); r = r.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : r.normalize(); Vec3 u = r.cross(f).normalize();
        for (net.minecraft.world.entity.player.Player pl : level.players()) if (pl != g && tagSeen(level, pl, eye, f, r, u)) m.tagged.put(pl.getUUID(), new Contact(pl.getUUID(), pl.getType(), pl.position(), now, How.SEEN, "nametag"));
        for (Entity e : level.getEntities(g, g.getBoundingBox().inflate(128), e -> e.isAlive() && !(e instanceof ExperienceOrb))) {
            if (sees(level, e, eye, f, r, u)) m.seen.put(e.getUUID(), new Contact(e.getUUID(), e.getType(), e.position(), now, How.SEEN, null));
            else if (e.distanceTo(g) <= TOUCH) m.felt.put(e.getUUID(), new Contact(e.getUUID(), e.getType(), e.position(), now, How.FELT, null));
        }
    }

    /** In his view right now: in the screen's frustum, within its type's range, nothing opaque between (any of its eye, middle, feet). */
    static boolean sees(ServerLevel level, Entity e, Vec3 eye, Vec3 f, Vec3 r, Vec3 u) {
        if (e.isInvisible() || e.isSpectator()) return false; // (a spectator is invisible to a survival player)
        double range = Math.min(RENDER, Math.max(4, e.getType().clientTrackingRange()) * 16);
        if (e.getBoundingBox().getCenter().distanceTo(eye) > range) return false;
        for (Vec3 p : new Vec3[] {e.getEyePosition(), e.getBoundingBox().getCenter(), e.position().add(0, 0.1, 0)}) {
            if (inView(eye, f, r, u, p) && clear(level, eye, p)) return true;
        }
        return false;
    }
    public static final double NAMETAG = 64; // vanilla's label distance for players
    /** Their nametag in his view: within 64, in the frustum; a standing player's label through blocks (see-through), a sneaking one's only in the open; none for an invisible one. A spectator's counts (the user's ruling). */
    static boolean tagSeen(ServerLevel level, net.minecraft.world.entity.player.Player p, Vec3 eye, Vec3 f, Vec3 r, Vec3 u) {
        if (p.isRemoved() || (p.isInvisible() && !p.isSpectator())) return false;
        Vec3 tag = p.position().add(0, p.getBbHeight() + 0.5, 0);
        if (tag.distanceTo(eye) > NAMETAG || !inView(eye, f, r, u, tag)) return false;
        return !p.isDiscrete() || clear(level, eye, tag);
    }
    /** Their nametag seen within the last {@code ticks}: where it was. Null otherwise. */
    public static Contact taggedWithin(ClaudeEntity g, Entity e, int ticks) { Contact c = mind(g).tagged.get(e.getUUID()); return c != null && g.tickCount - c.tick() <= ticks && c.tick() <= g.tickCount ? c : null; }
    /** A full turn round (1.5 s), and whom to ask after it if it turns up nothing. */
    public static void lookRound(ClaudeEntity g, UUID askAfter) { Mind m = mind(g); if (m.spinLeft <= 0) { m.spinLeft = 30; com.claudemod.ClaudeMod.LOGGER.info("[Claude sense] looking all the way round (Perception.lookRound)"); } if (askAfter != null) m.askFor = askAfter; }
    /** He finished a turn round within the last {@code ticks}. */
    public static boolean lookedRound(ClaudeEntity g, int ticks) { Mind m = mind(g); return m.spinLeft == 0 && g.tickCount - m.spinEnd <= ticks; }
    public static boolean turningRound(ClaudeEntity g) { return mind(g).spinLeft > 0; }
    public static void forget(ClaudeEntity g, Entity e) { Mind m = mind(g); UUID id = e.getUUID(); m.seen.remove(id); m.heard.remove(id); m.felt.remove(id); m.tagged.remove(id); } // (tests: out of mind)
    static boolean inView(Vec3 eye, Vec3 f, Vec3 r, Vec3 u, Vec3 p) {
        Vec3 v = p.subtract(eye); double z = v.dot(f);
        return z > 0.05 && Math.abs(v.dot(r) / z) <= TAN_H && Math.abs(v.dot(u) / z) <= TAN_V;
    }
    /** Nothing opaque on the line (glass, leaves, fences, water: see-through; lava: not). */
    public static boolean clear(ServerLevel level, Vec3 from, Vec3 to) {
        BlockPos fromCell = BlockPos.containing(from), toCell = BlockPos.containing(to);
        Boolean ok = BlockGetter.traverseBlocks(from, to, level, (lv, pos) -> {
            if (pos.equals(fromCell) || pos.equals(toCell)) return null;
            var s = lv.getBlockState(pos);
            if (lv.getFluidState(pos).is(FluidTags.LAVA)) return Boolean.FALSE;
            if (!s.canOcclude()) return null;
            return s.getShape(lv, pos).clip(from, to, pos) != null ? Boolean.FALSE : null;
        }, lv -> Boolean.TRUE);
        return ok == null || ok;
    }
    /** A block in his view now (in the frustum, out to 20 chunks, its centre or an open face with nothing opaque before it). */
    public static boolean seesBlock(ClaudeEntity g, ServerLevel level, BlockPos b) {
        Vec3 eye = g.getEyePosition(), f = g.getViewVector(1f), c = Vec3.atCenterOf(b);
        if (c.distanceTo(eye) > RENDER) return false;
        Vec3 r = f.cross(new Vec3(0, 1, 0)); r = r.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : r.normalize(); Vec3 u = r.cross(f).normalize();
        if (!inView(eye, f, r, u, c)) return false;
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
            BlockPos n = b.relative(d); if (level.getBlockState(n).canOcclude()) continue; // (a covered face)
            Vec3 face = c.add(d.getStepX() * 0.51, d.getStepY() * 0.51, d.getStepZ() * 0.51);
            if (clear(level, eye, face)) return true;
        }
        return false;
    }

    /** Doing something with his hands at a block or a station: his eyes are on that, not turned to a sound. */
    static boolean precise(ClaudeEntity g) {
        Task t = g.currentTask;
        return t instanceof Task.Break || t instanceof Task.Place || t instanceof Task.Pillar || t instanceof Task.FurnaceLoad || t instanceof Task.FurnaceTake || t instanceof Task.Deposit || t instanceof Task.Attack || t instanceof Task.WallTorch;
    }

    // ---- hearing ----

    public static void onSoundAt(net.neoforged.neoforge.event.PlayLevelSoundEvent.AtPosition e) { heard(e.getLevel(), e.getPosition(), null, e.getSound(), e.getSource(), e.getNewVolume()); }
    public static void onSoundFrom(net.neoforged.neoforge.event.PlayLevelSoundEvent.AtEntity e) { if (e.getEntity() != null) heard(e.getLevel(), e.getEntity().position(), e.getEntity(), e.getSound(), e.getSource(), e.getNewVolume()); }

    static void heard(net.minecraft.world.level.Level lv, Vec3 at, Entity from, Holder<SoundEvent> sound, SoundSource src, float volume) {
        if (lv.isClientSide || sound == null || src == SoundSource.MUSIC || src == SoundSource.RECORDS || src == SoundSource.WEATHER || src == SoundSource.AMBIENT || src == SoundSource.VOICE) return;
        double range = sound.value().getRange(volume);
        for (var ref : listeners) {
            ClaudeEntity g = ref.get(); if (g == null || g.isRemoved() || g.level() != lv) continue;
            double d = g.getEyePosition().distanceTo(at); if (d > range) continue;
            Entity src0 = from;
            if (src0 == null) { // whose it is: whatever stands where the sound came from
                double best = 1.0;
                for (Entity e : lv.getEntities(g, new AABB(at, at).inflate(1.0), Entity::isAlive)) { double dd = e.position().distanceTo(at); if (dd < best) { best = dd; src0 = e; } }
                if (src0 == null && g.position().distanceTo(at) < 1.0) continue; // (his own steps and digging)
            }
            if (src0 == g) continue;
            Vec3 est = estimate(g, at, d);
            String id = sound.unwrapKey().map(k -> k.location().getPath()).orElse("sound");
            if (src0 == null && id.startsWith("block.lava")) { heardLava(g, at, d, id); continue; } // lava popping, a lava bucket, lava hissing: a spot by ear
            Mind m = mind(g); int now = g.tickCount;
            Contact c = new Contact(src0 == null ? null : src0.getUUID(), src0 == null ? null : src0.getType(), est, now, How.HEARD, id);
            if (src0 != null) m.heard.put(src0.getUUID(), c);
            m.sounds.addFirst(c); while (m.sounds.size() > 24) m.sounds.removeLast();
            heardSign(g, lv, est, d, src0, id); // (a sculk sensor's clicks, a spawner's mobs: a hazard known by ear)
            boolean hostile = src0 instanceof net.minecraft.world.entity.monster.Enemy
                    && !(src0 instanceof net.minecraft.world.entity.NeutralMob nm0 && !nm0.isAngryAt(g)) // (zombified piglins, endermen: no threat till angry at him)
                    && !(src0 instanceof net.minecraft.world.entity.monster.piglin.AbstractPiglin ap0 && !(src0 instanceof net.minecraft.world.entity.monster.piglin.PiglinBrute) && ap0.getTarget() != g
                         && net.minecraft.world.entity.monster.piglin.PiglinAi.isWearingGold(g)); // (gold on: a piglin's no threat unless it's after him)
            boolean seenNow = src0 != null && m.seen.containsKey(src0.getUUID()) && now - m.seen.get(src0.getUUID()).tick <= 8;
            if (src0 != null && seenNow) m.lookedFor.remove(src0.getUUID()); // (seen: the next time it's out of sight is news again)
            double[] lf = src0 == null ? null : m.lookedFor.get(src0.getUUID());
            if (hostile && !seenNow && d <= 24 && lf != null && est.distanceTo(new Vec3(lf[0], lf[1], lf[2])) <= 4 && d > lf[3] - 3) { if (now % 200 < 2) com.claudemod.ClaudeMod.LOGGER.info("[Claude sense] heard the {} again {} blocks off, where he looked for it already (not closer): not turning (Perception.heard)", src0.getType().getDescription().getString(), (int) d); continue; } // a groan through rock he's placed: no new look
            if (hostile && !seenNow && d <= 24 && src0 != null) { m.lookedFor.put(src0.getUUID(), new double[] {est.x, est.y, est.z, d}); m.turns++; if (m.lookedFor.size() > 32) m.lookedFor.clear(); }
            if (hostile && !seenNow && d <= 24) { if (m.turnTo == null || now - m.turnTick > 40) com.claudemod.ClaudeMod.LOGGER.info("[Claude sense] heard a {} {} blocks off (about {}), not in sight: turning to it", src0 == null ? "mob" : src0.getType().getDescription().getString(), (int) d, net.minecraft.core.BlockPos.containing(est).toShortString()); m.turnTo = est.add(0, 1, 0); m.turnTick = now; } // a groan behind him: he turns to it
        }
    }
    public static int soundTurns(ClaudeEntity g) { return mind(g).turns; } // (tests)
    /** Where he places a sound heard from d away: exact close by, a tenth of the distance off across and a quarter up or down far off. */
    static Vec3 estimate(ClaudeEntity g, Vec3 at, double d) {
        java.util.Random rnd = new java.util.Random(Double.doubleToLongBits(at.x) * 31 + Double.doubleToLongBits(at.z) * 17 + g.tickCount);
        double h = 0.1 * Math.max(0, d - 2), v = 0.25 * Math.max(0, d - 2);
        double a = rnd.nextDouble() * Math.PI * 2, rr = rnd.nextDouble() * h;
        return at.add(Math.cos(a) * rr, (rnd.nextDouble() * 2 - 1) * v, Math.sin(a) * rr);
    }

    /**
     * The enemies he sensed lately (seen, heard or felt), within r of where he sensed them, for as long as three of that mob's own
     * sound gaps: under a lid a zombie is only heard, and one silent for 5 s is still standing on it to a player.
     */
    public static List<net.minecraft.world.entity.Mob> lately(ClaudeEntity g, double r) {
        Mind m = mind(g); int now = g.tickCount; List<net.minecraft.world.entity.Mob> out = new ArrayList<>();
        for (Entity e : g.level().getEntities(g, g.getBoundingBox().inflate(r + 16), x -> x.isAlive() && x instanceof net.minecraft.world.entity.Mob && x instanceof net.minecraft.world.entity.monster.Enemy)) {
            net.minecraft.world.entity.Mob mob = (net.minecraft.world.entity.Mob) e; Contact last = null;
            for (Contact c : new Contact[] {m.seen.get(e.getUUID()), m.heard.get(e.getUUID()), m.felt.get(e.getUUID())}) if (c != null && (last == null || c.tick > last.tick)) last = c;
            if (last != null && now - last.tick <= Math.max(HEARD_MEMORY, mob.getAmbientSoundInterval() * 3) && last.at.distanceTo(g.position()) <= r) out.add(mob);
        }
        return out;
    }

    // ---- touch ----

    /** Hit by something: he knows it, and his head turns to it (the screen tilts toward a hit). */
    public static void felt(ClaudeEntity g, Entity by) {
        if (by == null || by == g) return;
        Mind m = mind(g);
        m.felt.put(by.getUUID(), new Contact(by.getUUID(), by.getType(), by.position(), g.tickCount, How.FELT, null));
        m.turnTo = by.getEyePosition(); m.turnTick = g.tickCount;
    }

    // ---- what the rest of him asks ----

    /** He knows it's there: seen in the last 10 s, heard in the last 5 s, felt in the last 5 s, or right on him. */
    public static boolean aware(ClaudeEntity g, Entity e) {
        if (e == g) return true;
        if (e.distanceTo(g) <= TOUCH) return true;
        Mind m = mind(g); int now = g.tickCount; UUID id = e.getUUID();
        Contact s = m.seen.get(id), h = m.heard.get(id), t = m.felt.get(id);
        return s != null && now - s.tick <= SEEN_MEMORY || h != null && now - h.tick <= HEARD_MEMORY || t != null && now - t.tick <= FELT_MEMORY;
    }
    /** In his view this moment (or a tick or two ago): for aiming, chasing, judging exactly where it is. */
    public static boolean seesNow(ClaudeEntity g, Entity e) { Contact s = mind(g).seen.get(e.getUUID()); return s != null && g.tickCount - s.tick <= 8; }
    /** Where he believes it is: where he sees it, where he last saw it, or where he heard it. Null: he doesn't know of it. */
    public static Vec3 believedAt(ClaudeEntity g, Entity e) {
        if (seesNow(g, e) || e.distanceTo(g) <= TOUCH) return e.position();
        Mind m = mind(g); Contact s = m.seen.get(e.getUUID()), h = m.heard.get(e.getUUID()), t = m.felt.get(e.getUUID());
        Contact best = null; for (Contact c : new Contact[] {s, h, t}) if (c != null && (best == null || c.tick > best.tick)) best = c;
        return best == null || !aware(g, e) ? null : best.at;
    }
    /**
     * The things of a kind in the box that he knows of: the drop-in for level.getEntitiesOfClass in his rules (a creeper behind
     * a wall he can neither see nor hear is no reason to run). ponytail: returns the live entity, so a rule reading its exact
     * position after only hearing it knows a little more than the ear says; believedAt() is there for the rules that aim.
     */
    public static <T extends Entity> List<T> known(ClaudeEntity g, Class<T> cls, AABB box, Predicate<? super T> p) {
        return g.level().getEntitiesOfClass(cls, box, e -> p.test(e) && aware(g, e));
    }
    public static <T extends Entity> List<T> known(ClaudeEntity g, Class<T> cls, AABB box) { return known(g, cls, box, e -> true); }

    /** For his planner: what he sees and hears, with a compass direction and distance, nearest first. */
    public static String describe(ClaudeEntity g) {
        Mind m = mind(g); int now = g.tickCount; Vec3 me = g.position();
        List<String> see = new ArrayList<>(), hear = new ArrayList<>(); int items = 0;
        List<Contact> s = new ArrayList<>(m.seen.values()); s.sort(java.util.Comparator.comparingDouble(c -> c.at.distanceTo(me)));
        for (Contact c : s) {
            if (now - c.tick > SEEN_MEMORY || c.type == null) continue;
            if (c.type == EntityType.ITEM) { if (now - c.tick <= 8) items++; continue; }
            if (c.type == EntityType.ARROW || c.type == EntityType.EXPERIENCE_ORB || see.size() >= 10) continue;
            see.add(c.type.getDescription().getString().toLowerCase() + " " + (int) c.at.distanceTo(me) + " " + compass(me, c.at) + (now - c.tick > 8 ? " (last seen " + (now - c.tick) / 20 + " s ago)" : ""));
        }
        java.util.Set<UUID> told = new java.util.HashSet<>();
        for (Contact c : m.sounds) {
            if (now - c.tick > HEARD_MEMORY || hear.size() >= 8 || (c.sound != null && c.sound.startsWith("block.lava"))) continue; // (lava: its own line)
            if (c.id != null && (!told.add(c.id) || (m.seen.containsKey(c.id) && now - m.seen.get(c.id).tick <= 8))) continue; // (in view: told as seen)
            hear.add(c.sound.replace('.', ' ').replace('_', ' ') + " about " + Math.round(c.at.distanceTo(me)) + " " + compass(me, c.at));
        }
        return "you see: " + (see.isEmpty() ? "no creatures" : String.join(", ", see)) + (items > 0 ? "; " + items + " dropped item(s) in view" : "")
                + "\nyou hear: " + (hear.isEmpty() ? "nothing much" : String.join(", ", hear)) + "\n" + lavaHeardLine(g) + "\n";
    }
    public static String compassOf(Vec3 dir) { return compass(Vec3.ZERO, dir); }
    static String compass(Vec3 from, Vec3 to) {
        double dx = to.x - from.x, dz = to.z - from.z, dy = to.y - from.y;
        String[] n = {"south", "south-west", "west", "north-west", "north", "north-east", "east", "south-east"}; // (Minecraft: +z is south)
        int i = (int) Math.floorMod(Math.round(Math.toDegrees(Math.atan2(-dx, dz)) / 45.0), 8);
        return n[i] + (dy > 3 ? ", above" : dy < -3 ? ", below" : "");
    }
    /** A test's clean slate. */
    public static void reset(ClaudeEntity g) { if (lavaEar != null) lavaEar.remove(g.getUUID()); minds.remove(g.getUUID()); fluids.remove(g.getUUID()); if (sighted != null) sighted.remove(g.getUUID()); if (signsSeen != null) signsSeen.remove(g.getUUID()); if (signsHeard != null) signsHeard.remove(g.getUUID()); if (spawnerEars != null) spawnerEars.remove(g.getUUID()); if (dripsSeen != null) dripsSeen.remove(g.getUUID()); FarSight.reset(g); }

    // ---- the fluid he has seen: a player knows the lava and water he has looked at, never what's inside the rock ----
    static final class Fluids { final Map<BlockPos, Integer> lava = new HashMap<>(), water = new HashMap<>(); }
    private static final Map<UUID, Fluids> fluids = new HashMap<>();
    static Fluids fl(ClaudeEntity g) { return fluids.computeIfAbsent(g.getUUID(), k -> new Fluids()); }
    public static boolean knowsLava(ClaudeEntity g, BlockPos p) { return fl(g).lava.containsKey(p); }
    /**
     * This lava cell is one body with lava he has seen or noted: a flood through lava from it (6-way, up to 512 cells) reaches a
     * cell on his fluid record, or within 1 of a pool he noted (ClaudeLife.lavaSeen: saved, so it holds over a restart). A
     * player who has seen a pool knows it runs on behind the rock round it. Lava with no such tie is unknown to him.
     */
    public static boolean knowsLavaBody(ClaudeEntity g, ServerLevel level, BlockPos p) {
        if (knowsLava(g, p)) return true;
        if (!level.getFluidState(p).is(FluidTags.LAVA)) return false;
        var noted = com.claudemod.director.ClaudeLife.lavaSeen(g); Fluids m = fl(g);
        java.util.ArrayDeque<BlockPos> q = new java.util.ArrayDeque<>(); java.util.Set<BlockPos> seen = new java.util.HashSet<>();
        q.add(p.immutable()); seen.add(p.immutable());
        while (!q.isEmpty() && seen.size() <= 512) {
            BlockPos c = q.poll();
            if (m.lava.containsKey(c)) return true;
            for (BlockPos n0 : noted) if (n0.distManhattan(c) <= 1) return true;
            for (Direction d : Direction.values()) { BlockPos nb = c.relative(d); if (seen.contains(nb) || !level.isLoaded(nb) || !level.getFluidState(nb).is(FluidTags.LAVA)) continue; seen.add(nb.immutable()); q.add(nb.immutable()); }
        }
        return false;
    }
    public static boolean knowsWater(ClaudeEntity g, BlockPos p) { return fl(g).water.containsKey(p); }
    /** Every lava cell he has seen (and not seen gone since). */
    public static java.util.Set<BlockPos> knownLava(ClaudeEntity g) { return fl(g).lava.keySet(); }

    // ---- lava by ear: vanilla plays lava's pop and rumble on the client only (LavaFluid.animateTick, lava with air over it, in the
    // ---- client's random-tick box round the camera), so the server never fires them: his ear samples the same way. A heard lava is a rough spot.
    public record HeardLava(Vec3 at, int tick, double dist, String sound) {}
    private static Map<UUID, ArrayDeque<HeardLava>> lavaEar; // (static: fields added live start null)
    public static ArrayDeque<HeardLava> lavaHeard(ClaudeEntity g) { if (lavaEar == null) lavaEar = new HashMap<>(); return lavaEar.computeIfAbsent(g.getUUID(), k -> new ArrayDeque<>()); }
    /** Once a second: vanilla's client looks at 667 cells a tick round the camera (each axis nextInt(16) - nextInt(16)); lava with air over it pops 1 in 100, rumbles 1 in 200. A second's worth: the same cells, 20 times the odds. Range 16 (its volume is under 1). */
    static void earLava(ClaudeEntity g, ServerLevel level) {
        var rnd = g.getRandom(); BlockPos c = BlockPos.containing(g.getEyePosition()); BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
        for (int i = 0; i < 667; i++) {
            q.set(c.getX() + rnd.nextInt(16) - rnd.nextInt(16), c.getY() + rnd.nextInt(16) - rnd.nextInt(16), c.getZ() + rnd.nextInt(16) - rnd.nextInt(16));
            if (!level.isLoaded(q) || !level.getFluidState(q).is(FluidTags.LAVA)) continue;
            BlockPos up = q.above(); var us = level.getBlockState(up); if (!us.isAir() || us.isSolidRender(level, up)) continue;
            int roll = rnd.nextInt(100); String id = roll < 20 ? "block.lava.pop" : roll < 30 ? "block.lava.ambient" : null; if (id == null) continue;
            Vec3 at = Vec3.atCenterOf(q); double d = g.getEyePosition().distanceTo(at); if (d > 16) continue;
            heardLava(g, at, d, id);
        }
    }
    /** A lava sound heard from d away: placed by estimate() (exact close by, a tenth across and a quarter up/down far off), kept with the closer hearing of the same spot. */
    static void heardLava(ClaudeEntity g, Vec3 at, double d, String id) {
        Vec3 est = estimate(g, at, d); Mind m = mind(g); int now = g.tickCount;
        m.sounds.addFirst(new Contact(null, null, est, now, How.HEARD, id)); while (m.sounds.size() > 24) m.sounds.removeLast();
        var ear = lavaHeard(g); HeardLava near = null; for (HeardLava h : ear) if (h.at().distanceTo(est) <= 4) { near = h; break; }
        boolean news = near == null || now - near.tick() > 20 * 60 || near.tick() > now;
        if (near != null) ear.remove(near);
        boolean keepOld = near != null && !news && near.dist() < d;
        ear.addFirst(new HeardLava(keepOld ? near.at() : est, now, keepOld ? near.dist() : d, id)); while (ear.size() > 16) ear.removeLast();
        if (news) com.claudemod.ClaudeMod.LOGGER.info("[Claude sense] heard lava ({}) about {}, {} blocks off, {} (Perception.heardLava)", id, BlockPos.containing(est).toShortString(), (int) d, rough(g, est));
    }
    /** The lava he heard within r of `around` in the last `ticks` (the latest), or null. */
    public static HeardLava lavaHeardNear(ClaudeEntity g, Vec3 around, double r, int ticks) { for (HeardLava h : lavaHeard(g)) if (g.tickCount - h.tick() <= ticks && h.tick() <= g.tickCount && h.at().distanceTo(around) <= r) return h; return null; }
    /** For his prompts: the lava he heard lately, roughly where from his facing; or that he heard none (so he never says he does). */
    public static String lavaHeardLine(ClaudeEntity g) {
        HeardLava h = null; for (HeardLava x : lavaHeard(g)) if (g.tickCount - x.tick() <= 20 * 120 && x.tick() <= g.tickCount) { h = x; break; }
        if (h == null) return "lava by ear: none heard lately (don't say you hear lava)";
        return "lava by ear: you heard lava " + rough(g, h.at()) + " (" + compass(g.getEyePosition(), h.at()) + ", " + (g.tickCount - h.tick()) / 20 + " s ago; a rough spot from the sound, not seen)";
    }
    /** Where a spot is from him the way a player says it: ahead / to the left / behind, up or down, maybe how far. */
    static String rough(ClaudeEntity g, Vec3 at) {
        Vec3 eye = g.getEyePosition(), f = g.getViewVector(1f); double dx = at.x - eye.x, dz = at.z - eye.z, dy = at.y - eye.y, hd = Math.sqrt(dx * dx + dz * dz), fl = Math.sqrt(f.x * f.x + f.z * f.z);
        String side;
        if (hd < 1.5) side = "right here";
        else { double cos = fl < 1e-6 ? 1 : (f.x * dx + f.z * dz) / (fl * hd), cross = f.x * dz - f.z * dx; String lr = cross > 0 ? "right" : "left";
            side = cos > 0.7 ? "ahead" : cos > 0.3 ? "ahead and to your " + lr : cos < -0.7 ? "behind you" : "to your " + lr; }
        String vert = dy < -4 ? ", well below" : dy < -1.5 ? " and a bit down" : dy > 4 ? ", well above" : dy > 1.5 ? " and a bit up" : "";
        return side + vert + ", maybe " + Math.max(1, Math.round(Math.sqrt(hd * hd + dy * dy))) + " blocks";
    }
    /** A cell he's looking at, or one a dig of his just opened beside him: its fluid on record (off it when he sees it's gone). */
    public static void sawCell(ClaudeEntity g, ServerLevel level, BlockPos p) {
        var f = level.getFluidState(p); Fluids m = fl(g); BlockPos k = p.immutable();
        if (f.is(FluidTags.LAVA)) { m.lava.put(k, g.tickCount); m.water.remove(k); }
        else if (f.is(FluidTags.WATER)) { m.water.put(k, g.tickCount); m.lava.remove(k); }
        else { m.lava.remove(k); m.water.remove(k); }
        if (m.lava.size() > 4096) m.lava.clear(); if (m.water.size() > 8192) m.water.clear(); // ponytail: a cap, not an age-out (an ocean's worth)
    }
    /** Each second: the fluid cells within 10 in his view with nothing opaque between (and the known ones there checked again). */
    static void scanFluids(ClaudeEntity g, ServerLevel level) {
        Vec3 eye = g.getEyePosition(), f = g.getViewVector(1f);
        Vec3 r = f.cross(new Vec3(0, 1, 0)); r = r.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : r.normalize(); Vec3 u = r.cross(f).normalize();
        BlockPos c = g.blockPosition(); Fluids m = fl(g);
        for (BlockPos q : BlockPos.betweenClosed(c.offset(-10, -6, -10), c.offset(10, 6, 10))) {
            boolean fluid = !level.getFluidState(q).isEmpty();
            if (!fluid && !m.lava.containsKey(q) && !m.water.containsKey(q)) continue;
            Vec3 cc = Vec3.atCenterOf(q);
            if (!inView(eye, f, r, u, cc) || !clear(level, eye, cc)) continue;
            sawCell(g, level, q);
        }
    }

    // ---- drips: vanilla hangs dripping lava / water under a block with fluid on it, on the client only (ClientLevel.doAnimateTick ->
    // ---- trySpawnDripParticles), so the server never fires them: his eye samples the same way. A drip says "fluid right over this block", nothing more.
    private static Map<UUID, java.util.Set<BlockPos>> dripsSeen; // (static: fields added live start null) the fluid cells he's seen drip, logged once each
    static java.util.Set<BlockPos> dripSet(ClaudeEntity g) { if (dripsSeen == null) dripsSeen = new HashMap<>(); return dripsSeen.computeIfAbsent(g.getUUID(), k -> new java.util.HashSet<>()); }
    /** He has seen the fluid at this cell drip through the block under it. */
    public static boolean sawDrip(ClaudeEntity g, BlockPos fluid) { return dripsSeen != null && dripSet(g).contains(fluid); }
    /** Where vanilla hangs the drip of the fluid at f (trySpawnDripParticles, mirrored): the drip's y, or NaN when it hangs none. */
    static double dripY(ServerLevel level, BlockPos f) {
        var fs = level.getFluidState(f);
        if (!fs.is(FluidTags.LAVA) && !fs.is(FluidTags.WATER)) return Double.NaN; // (FluidState.getDripParticle: lava and water only)
        BlockPos b = f.below(); var bs = level.getBlockState(b);
        if (!bs.getFluidState().isEmpty()) return Double.NaN;
        var shape = bs.getCollisionShape(level, b); double top = shape.max(Direction.Axis.Y);
        if (top < 1.0) return level.getBlockState(f).isFaceSturdy(level, f, Direction.DOWN) ? b.getY() + 1 - 0.05 : Double.NaN; // (a waterlogged block with a sturdy bottom over an open cell: under its own face)
        if (bs.is(net.minecraft.tags.BlockTags.IMPERMEABLE)) return Double.NaN; // (glass: no drip through it)
        double bot = shape.min(Direction.Axis.Y);
        if (bot > 0.0) return b.getY() + bot - 0.05;
        BlockPos c = b.below(); var cs = level.getBlockState(c);
        return cs.getCollisionShape(level, c).max(Direction.Axis.Y) < 1.0 && cs.getFluidState().isEmpty() ? b.getY() - 0.05 : Double.NaN;
    }
    /** The odds that vanilla hangs a drip from the fluid at f within a second, the player's feet at feet: 667 draws a tick in each box (16, 32), each axis nextInt(r) - nextInt(r); a fluid cell drawn drips 1 in 10. */
    static double dripOdds(BlockPos feet, BlockPos f) {
        int dx = Math.abs(f.getX() - feet.getX()), dy = Math.abs(f.getY() - feet.getY()), dz = Math.abs(f.getZ() - feet.getZ());
        double tick = 1;
        for (int r : new int[] {16, 32}) if (dx < r && dy < r && dz < r) { double p = (double) (r - dx) * (r - dy) * (r - dz) / Math.pow(r, 6); tick *= Math.pow(1 - p * 0.1, 667); }
        return 1 - Math.pow(tick, 20);
    }
    /** Each second: the drip points within 16 above his eye, in his view, nothing opaque between, each rolled at vanilla's odds; a drip seen tells him the fluid over that block (drip). */
    static void drips(ClaudeEntity g, ServerLevel level) {
        Vec3 eye = g.getEyePosition(), f = g.getViewVector(1f);
        Vec3 r = f.cross(new Vec3(0, 1, 0)); r = r.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : r.normalize(); Vec3 u = r.cross(f).normalize();
        BlockPos feet = g.blockPosition(), c = BlockPos.containing(eye); var rnd = g.getRandom();
        com.claudemod.director.ClaudeLife.dripCheck(g, level); // (drip lava on his list he hasn't seen yet: checked by sight)
        for (BlockPos q : BlockPos.betweenClosed(c.offset(-16, 0, -16), c.offset(16, 17, 16))) {
            if (level.getFluidState(q).isEmpty()) continue;
            double y = dripY(level, q); if (Double.isNaN(y) || y <= eye.y) continue; // (none hangs there, or it's under a face he's above)
            Vec3 pt = new Vec3(q.getX() + 0.5, y, q.getZ() + 0.5);
            if (pt.distanceTo(eye) > 16 || !inView(eye, f, r, u, pt) || !clear(level, eye, pt)) continue;
            if (rnd.nextDouble() >= dripOdds(feet, q)) continue; // (no drip hung there this second)
            drip(g, level, q, pt);
        }
    }
    /** A drip he saw at `at` from the fluid at `fluid`: that fluid on his fluid record; lava on his lava list too (ClaudeLife.lavaByDrip). Logged once a spot. */
    static void drip(ClaudeEntity g, ServerLevel level, BlockPos fluid, Vec3 at) {
        BlockPos k = fluid.immutable(); boolean lava = level.getFluidState(k).is(FluidTags.LAVA);
        var set = dripSet(g); if (set.size() > 2048) set.clear(); boolean news = set.add(k); // ponytail: a cap, not an age-out
        sawCell(g, level, k); // (knowsLava / knowsLavaBody: the dig guard and the walk planner hold the block; knowsWater: the bucket and the mine)
        if (!news) return;
        BlockPos from = BlockPos.containing(at.x, at.y + 0.1, at.z);
        com.claudemod.ClaudeMod.LOGGER.info("[Claude sense] {} dripping from the ceiling at {} ({}): {} above at {} (Perception.drips)", lava ? "lava" : "water", from.toShortString(), level.getBlockState(from).getBlock().getName().getString(), lava ? "lava" : "water", k.toShortString());
        if (lava) com.claudemod.director.ClaudeLife.lavaByDrip(g, level, k);
    }

    // ---- the structures he has seen: by their own blocks in his view (FarSight: close by cell by cell, far off by his rays), never the server's structure data ----
    /**
     * A structure he has seen, as much of it as he saw: what he took it for (a Structures key: "fortress", "mineshaft"...), the cells
     * of it he saw (up to 512), their box, and that box grown by 2 for what a player works out round what he sees (a corridor's walls
     * round its rails, a bridge's sides). Live 17:05: the worldgen piece's box handed him a fortress's layout he had never seen.
     */
    public static final class Sighting {
        public static final int INFER = 2;
        final String id; final net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dim; final BlockPos saw;
        int tick; String block; boolean sure; int minX, minY, minZ, maxX, maxY, maxZ;
        final java.util.LinkedHashSet<BlockPos> cells = new java.util.LinkedHashSet<>();
        Sighting(String id, net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dim, BlockPos at, int tick, String block, boolean sure) {
            this.id = id; this.dim = dim; this.saw = at.immutable(); this.block = block; this.sure = sure; this.tick = tick;
            minX = maxX = at.getX(); minY = maxY = at.getY(); minZ = maxZ = at.getZ(); add(at, tick);
        }
        void add(BlockPos p, int now) {
            if (cells.size() < 512) cells.add(p.immutable());
            minX = Math.min(minX, p.getX()); minY = Math.min(minY, p.getY()); minZ = Math.min(minZ, p.getZ()); maxX = Math.max(maxX, p.getX()); maxY = Math.max(maxY, p.getY()); maxZ = Math.max(maxZ, p.getZ());
            tick = Math.max(tick, now);
        }
        public String id() { return id; }
        public net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dim() { return dim; }
        /** The first block of it that told him. */
        public BlockPos saw() { return saw; }
        /** When he last saw some of it. */
        public int tick() { return tick; }
        /** The block that says what it is ("nether brick"). */
        public String block() { return block; }
        /** A sure sign (nether brick, a bell), not a maybe (mossy cobblestone, obsidian). */
        public boolean sure() { return sure; }
        public java.util.Collection<BlockPos> cells() { return java.util.Collections.unmodifiableCollection(cells); }
        /** The box of the cells he saw. */
        public net.minecraft.world.level.levelgen.structure.BoundingBox seen() { return new net.minecraft.world.level.levelgen.structure.BoundingBox(minX, minY, minZ, maxX, maxY, maxZ); }
        /** That box grown by what a player works out round it (INFER). */
        public net.minecraft.world.level.levelgen.structure.BoundingBox box() { return seen().inflatedBy(INFER); }
        /** A cell of it he saw within r (each axis) of p. */
        public boolean near(BlockPos p, int r) { for (BlockPos c : cells) if (Math.abs(c.getX() - p.getX()) <= r && Math.abs(c.getY() - p.getY()) <= r && Math.abs(c.getZ() - p.getZ()) <= r) return true; return false; }
        /** The cell of it he saw nearest `from`. */
        public BlockPos nearestCell(Vec3 from) { BlockPos best = saw; double bd = Double.MAX_VALUE; for (BlockPos c : cells) { double d = c.distToCenterSqr(from); if (d < bd) { bd = d; best = c; } } return best; }
        @Override public String toString() { return id + " (" + block + ", " + cells.size() + " cells seen, " + seen() + ")"; }
    }
    private static Map<UUID, List<Sighting>> sighted; // (static: fields added live start null)
    static List<Sighting> sightList(ClaudeEntity g) { if (sighted == null) sighted = new HashMap<>(); return sighted.computeIfAbsent(g.getUUID(), k -> new ArrayList<>()); }
    public static java.util.Collection<Sighting> sightings(ClaudeEntity g) { return sightList(g); }
    /** The structures and landmarks he has seen in the dimension he's in (the nether scout's list). */
    public static List<Sighting> landmarks(ClaudeEntity g) { List<Sighting> out = new ArrayList<>(); for (Sighting s : sightList(g)) if (s.dim == g.level().dimension()) out.add(s); return out; }
    /** The nearest one of a kind he has seen here ("fortress", "bastion_remnant", "temple", "portal", "*" any), by its nearest seen cell; null: none seen. */
    public static Sighting nearestLandmark(ClaudeEntity g, String kind) {
        Sighting best = null; double bd = Double.MAX_VALUE; Vec3 at = g.getEyePosition();
        for (Sighting s : landmarks(g)) { if (!FarSight.kindMatches(kind, s.id)) continue; double d = s.nearestCell(at).distToCenterSqr(at); if (d < bd) { bd = d; best = s; } }
        return best;
    }
    /** Each second: the structure blocks close by in his view (FarSight.nearScan). */
    public static void scanStructures(ClaudeEntity g, ServerLevel level) { FarSight.nearScan(g, level); }

    // ---- the hazard signs he knows of: seen (in his line of sight) or heard (a sculk sensor's clicks, a shrieker's shriek, a trial spawner's hum, a spawner's mobs), never read through rock ----
    private static Map<UUID, Map<BlockPos, String>> signsSeen, signsHeard; // (static: fields added live start null)
    private static Map<UUID, Map<BlockPos, Map<UUID, Integer>>> spawnerEars; // per spawner, the mobs heard about it lately and when
    static Map<BlockPos, String> seenSigns(ClaudeEntity g) { if (signsSeen == null) signsSeen = new HashMap<>(); return signsSeen.computeIfAbsent(g.getUUID(), k -> new HashMap<>()); }
    static Map<BlockPos, String> heardSigns(ClaudeEntity g) { if (signsHeard == null) signsHeard = new HashMap<>(); return signsHeard.computeIfAbsent(g.getUUID(), k -> new HashMap<>()); }
    /** The hazard he knows at this cell (seen there, or heard from about there), or null. */
    /** (tests) A sign he saw earlier: on his record as if seen. */
    public static void noteSignTest(ClaudeEntity g, BlockPos p, String what) { seenSigns(g).put(p.immutable(), what); }
    public static String signAt(ClaudeEntity g, BlockPos p) { String s = seenSigns(g).get(p); return s != null ? s : heardSigns(g).get(p); }
    /** The nearest sign of {@code what} ("the deep dark", "a trial chamber"...) he has seen or heard within r of p, or null. */
    public static BlockPos nearestSign(ClaudeEntity g, BlockPos p, int r, String what) {
        BlockPos best = null; double bd = (double) r * r;
        for (var m : java.util.List.of(seenSigns(g), heardSigns(g))) for (var e : m.entrySet()) { if (!what.equals(e.getValue())) continue; double d = e.getKey().distSqr(p); if (d <= bd) { bd = d; best = e.getKey(); } }
        return best;
    }
    /** Each second: the hazard blocks within 10 (6 up and down) in his line of sight go on record; a known one seen gone comes off. */
    public static void scanSigns(ClaudeEntity g, ServerLevel level) {
        BlockPos c = g.blockPosition(); Map<BlockPos, String> m = seenSigns(g);
        for (BlockPos q : BlockPos.betweenClosed(c.offset(-10, -6, -10), c.offset(10, 6, 10))) {
            String hz = com.claudemod.director.ClaudeLife.hazardOf(level.getBlockState(q));
            if (hz == null && !m.containsKey(q)) continue;
            if (!seesBlock(g, level, q)) continue;
            if (hz == null) { m.remove(q); continue; }
            if (m.put(q.immutable(), hz) == null) com.claudemod.ClaudeMod.LOGGER.info("[Claude sense] saw {} at {} from {}: {} (Perception.scanSigns)", level.getBlockState(q).getBlock().getName().getString(), q.toShortString(), c.toShortString(), hz);
        }
        if (m.size() > 4096) m.clear(); // ponytail: a cap, not an age-out
    }
    /** A sound that tells a player of a hazard: any sculk sound (a sensor's clicks, a shrieker): the deep dark; a trial spawner's or vault's: a trial chamber; two or more mobs heard within a minute about the same spawner: a mob spawner. Put where the sound seemed to come from. */
    static void heardSign(ClaudeEntity g, net.minecraft.world.level.Level lv, Vec3 est, double d, Entity src, String id) {
        String what = id.startsWith("block.sculk") ? "the deep dark" : id.startsWith("block.trial_spawner") || id.startsWith("block.vault") ? "a trial chamber" : null;
        if (what == null && src instanceof net.minecraft.world.entity.monster.Enemy) {
            BlockPos sp = null, sc = src.blockPosition();
            for (BlockPos q : BlockPos.betweenClosed(sc.offset(-4, -4, -4), sc.offset(4, 4, 4))) if (lv.getBlockState(q).is(net.minecraft.world.level.block.Blocks.SPAWNER)) { sp = q.immutable(); break; }
            if (sp == null) return;
            if (spawnerEars == null) spawnerEars = new HashMap<>();
            var ears = spawnerEars.computeIfAbsent(g.getUUID(), k -> new HashMap<>()).computeIfAbsent(sp, k -> new HashMap<>());
            int now = g.tickCount; ears.values().removeIf(t0 -> now - t0 > 20 * 60 || t0 > now); ears.put(src.getUUID(), now);
            if (ears.size() < 2) return; // one mob groaning is a mob; two or more about one spot within a minute is a spawner's work
            what = "a mob spawner";
        }
        if (what == null) return;
        BlockPos k = BlockPos.containing(est); Map<BlockPos, String> m = heardSigns(g);
        for (var e : m.entrySet()) if (e.getValue().equals(what) && e.getKey().distSqr(k) <= 4 * 4) return; // (heard there already)
        m.put(k, what);
        if (m.size() > 2048) m.clear(); // ponytail: a cap, not an age-out
        com.claudemod.ClaudeMod.LOGGER.info("[Claude sense] heard {} ({}) about {}, {} blocks off: {} (Perception.heard)", id, src == null ? "a block" : src.getType().getDescription().getString(), k.toShortString(), (int) d, what);
    }
}
