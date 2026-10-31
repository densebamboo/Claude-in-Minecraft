package com.claudemod.entity.life;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.claudemod.ClaudeMod;
import com.claudemod.entity.ClaudeEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;

/**
 * "Watch what i build" (the user, 2026-10-30): he watches them build, learns it, and can build it again.
 *
 * Watching: "watch what i build" / "watch me build a tower" starts it, "done" / "that's it" ends it. His own work stops; he stands
 * by, faces what they're doing and keeps a spot he can see it from (a missed block, or them out of sight: a better spot round it).
 * He learns only what he sees: a block they place or break in his view with nothing in the way (Perception.seesBlock, at the event);
 * one behind a wall is only heard. At "done" he walks round it and looks it over from each corner: a block he sees then that joins
 * the build and wasn't there at his first look (or, a spot he never saw before, one he heard go in or no natural block) is added.
 * Terrain he never saw placed never is. The result is a LearnedBlueprint (full states, their order), named ("the tower") and saved
 * per bot outside the world (LearnedStore), so every new world has it.
 *
 * Building one again: "build that again here", "build me one of those", "build the tower over there [facing north]" (or the
 * planner's build_blueprint). The site is in front of them (or where he is, or where they look), turned if asked, checked flat and
 * clear (terrain beyond its own cells is reported, never dug away). The materials are counted against his pack and his chests (his
 * ledger) and what's short is fetched or got with the ordinary skills (obtain). Then every block goes in through the normal body
 * (Task.Place with the planned state: reach, sight, the shell guard), in their order, bottom-up where that order allows; blocks too
 * high to reach from the ground from a tower of his beside it, taken back down after (TempLedger). Last, he checks it block by
 * block, fixes what's off, and says "done: built the tower (N blocks, M fixed)". Every decision logs what he saw and why.
 */
public final class WatchBuild {
    private WatchBuild() {}

    static final int HOLD = 20 * 60 * 30; // standing by while they build: half an hour at most, then he looks it over anyway
    static final int SNAP_R = 10;

    // ================================================================ watching

    record Seen(BlockState state, int order, String how) {}

    static final class Session {
        final UUID user; final String userName, name; final boolean named; final BlockPos anchor; final Vec3 watcherStart; final int startTick;
        final Map<BlockPos, BlockState> before = new HashMap<>(); boolean frozen; // what he saw round there at the start (frozen at the first block they put in)
        final LinkedHashMap<BlockPos, Seen> learned = new LinkedHashMap<>();
        final List<BlockPos> heard = new ArrayList<>(); // blocks that went in (or out) out of his sight: heard only
        int order, seen, missed, missedAtMove, lastEventTick = -100000, lastMoveTick = -100000, userSeenTick = -100000;
        BlockPos lastEvent; Task.Wait hold;
        boolean lookingOver; final List<Task> looks = new ArrayList<>(); Task lookNow; int lookTicks, scans; Vec3 lookCentre;
        final Map<BlockPos, BlockState> lookSeen = new LinkedHashMap<>();
        Session(UUID user, String userName, String name, boolean named, BlockPos anchor, Vec3 watcherStart, int startTick) {
            this.user = user; this.userName = userName; this.name = name; this.named = named; this.anchor = anchor; this.watcherStart = watcherStart; this.startTick = startTick;
        }
    }
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    /** What he last said about a watched build (tests). */
    public static String lastLine = "";

    public static boolean watching(ClaudeEntity g) { Session s = SESSIONS.get(g.getUUID()); return s != null && !s.lookingOver; }
    public static boolean lookingOver(ClaudeEntity g) { Session s = SESSIONS.get(g.getUUID()); return s != null && s.lookingOver; }
    /** The cells he has learned so far in this watch (tests, his own account). */
    public static Map<BlockPos, BlockState> learnedNow(ClaudeEntity g) { Session s = SESSIONS.get(g.getUUID()); Map<BlockPos, BlockState> m = new LinkedHashMap<>(); if (s != null) s.learned.forEach((k, v) -> m.put(k, v.state())); return m; }
    public static List<BlockPos> heardNow(ClaudeEntity g) { Session s = SESSIONS.get(g.getUUID()); return s == null ? List.of() : List.copyOf(s.heard); }

    static String name(BlockState s) { return BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath().replace('_', ' '); }
    static String xyz(BlockPos p) { return p.getX() + " " + p.getY() + " " + p.getZ(); }
    static String display(String name) { return name.contains("'s ") ? name : "the " + name; }

    /** The top half of a door or tall plant, a bed's head: it comes with its other half, never a block of its own. */
    static boolean companion(BlockState s) {
        return s.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) && s.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER
                || s.hasProperty(BlockStateProperties.BED_PART) && s.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD;
    }
    /** Ground and growth: what a spot he never saw before is taken to be when it's one of these. */
    static boolean natural(BlockState s) {
        return s.is(BlockTags.DIRT) || s.is(BlockTags.SAND) || s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(BlockTags.BASE_STONE_NETHER) || s.is(BlockTags.LEAVES)
                || s.is(BlockTags.LOGS) || s.is(Blocks.GRAVEL) || s.is(Blocks.CLAY) || s.is(Blocks.SNOW_BLOCK) || s.is(Blocks.ICE) || s.is(BlockTags.SNOW) || !s.getFluidState().isEmpty();
    }

    /** Starts watching them: the before look round the spot they're at. */
    static Session start(ClaudeEntity g, ServerPlayer player, String name) {
        boolean named = name != null && !name.isBlank();
        String nm = named ? name.trim() : freeName(g, player.getGameProfile().getName() + "'s build");
        BlockPos at = Whereabouts.spot(g, player); if (at == null) at = g.blockPosition();
        Session s = new Session(player.getUUID(), player.getGameProfile().getName(), nm, named, at.immutable(), g.position(), g.tickCount);
        if (Whereabouts.seesNow(g, player)) s.userSeenTick = g.tickCount;
        SESSIONS.put(g.getUUID(), s);
        snapshot(g, s, g.serverLevel());
        ClaudeMod.LOGGER.info("[Claude watch] watching {} build \"{}\" round {}: {} cells there seen as they are now, his before look (WatchBuild.start)", s.userName, nm, xyz(at), s.before.size());
        return s;
    }
    static String freeName(ClaudeEntity g, String base) {
        var lib = library(g); if (!lib.containsKey(base.toLowerCase(Locale.ROOT))) return base;
        for (int i = 2; ; i++) if (!lib.containsKey((base + " " + i).toLowerCase(Locale.ROOT))) return base + " " + i;
    }

    /** His before look: every cell round the spot he sees right now, as it is (only until the first block goes in: after that a cell he first sees may be theirs). */
    static void snapshot(ClaudeEntity g, Session s, ServerLevel level) {
        int n0 = s.before.size();
        for (BlockPos q : BlockPos.betweenClosed(s.anchor.offset(-SNAP_R, -3, -SNAP_R), s.anchor.offset(SNAP_R, 8, SNAP_R))) {
            if (s.before.containsKey(q) || !level.isLoaded(q)) continue;
            if (Perception.seesBlock(g, level, q)) s.before.put(q.immutable(), level.getBlockState(q));
        }
        if (s.before.size() > n0 && n0 > 0) ClaudeMod.LOGGER.info("[Claude watch] before look: {} more cells seen round {} (nothing built yet) (WatchBuild.snapshot)", s.before.size() - n0, xyz(s.anchor));
    }

    /** They put a block down (the place event, every cell of a multi-place): learned if he saw it go in. */
    public static void userPlaced(ServerPlayer p, ServerLevel level, net.neoforged.neoforge.event.level.BlockEvent.EntityPlaceEvent e) {
        if (SESSIONS.isEmpty()) return;
        List<BlockPos> at = new ArrayList<>();
        if (e instanceof net.neoforged.neoforge.event.level.BlockEvent.EntityMultiPlaceEvent m) { for (var snap : m.getReplacedBlockSnapshots()) if (!at.contains(snap.getPos())) at.add(snap.getPos().immutable()); }
        else at.add(e.getPos().immutable());
        for (ClaudeEntity g : watchersOf(level, p)) for (BlockPos q : at) event(g, SESSIONS.get(g.getUUID()), level, q, level.getBlockState(q), true);
    }
    /** They broke a block: learned as "air here" if he saw it go. */
    public static void userBroke(ServerPlayer p, ServerLevel level, BlockPos pos, BlockState was) {
        if (SESSIONS.isEmpty() || was.canBeReplaced()) return; // (grass and flowers knocked off: nothing built)
        if (companion(was)) { pos = was.hasProperty(BlockStateProperties.BED_PART) ? pos.relative(was.getValue(BedBlock.FACING).getOpposite()) : pos.below(); was = level.getBlockState(pos); } // (the door's top half broken: the door)
        for (ClaudeEntity g : watchersOf(level, p)) event(g, SESSIONS.get(g.getUUID()), level, pos.immutable(), was, false);
    }
    static List<ClaudeEntity> watchersOf(ServerLevel level, ServerPlayer p) {
        List<ClaudeEntity> out = new ArrayList<>();
        for (ServerPlayer sp : level.getServer().getPlayerList().getPlayers()) if (sp instanceof ClaudeEntity g && g.level() == level) { Session s = SESSIONS.get(g.getUUID()); if (s != null && !s.lookingOver && s.user.equals(p.getUUID())) out.add(g); }
        return out;
    }

    static void event(ClaudeEntity g, Session s, ServerLevel level, BlockPos pos, BlockState state, boolean place) {
        if (place && companion(state)) return; // (comes with its lower half)
        s.frozen = true; s.lastEvent = pos; s.lastEventTick = g.tickCount;
        String what = (place ? "put in " : "broke ") + name(state);
        if (!Perception.seesBlock(g, level, pos)) {
            s.heard.add(pos); s.missed++;
            ClaudeMod.LOGGER.info("[Claude watch] they {} at {} out of his sight (he's at {} looking {}): heard only, not learned (WatchBuild.event)", what, xyz(pos), g.blockPosition().toShortString(), Perception.compassOf(g.getViewVector(1f)));
            return;
        }
        s.seen++;
        if (place) s.learned.put(pos, new Seen(state, s.order++, "seen"));
        else if (s.learned.containsKey(pos)) { s.learned.remove(pos); ClaudeMod.LOGGER.info("[Claude watch] they took the {} at {} back out: off the build (WatchBuild.event)", name(state), xyz(pos)); return; }
        else s.learned.put(pos, new Seen(LearnedBlueprint.air(), s.order++, "seen broken"));
        ClaudeMod.LOGGER.info("[Claude watch] saw them {} at {}: {} learned so far (WatchBuild.event)", what, xyz(pos), s.learned.size());
    }

    /** Every tick: standing by facing the work, a better spot when he can't see it, the look round after "done". */
    public static void tick(ClaudeEntity g) {
        if (g.level().isClientSide || SESSIONS.isEmpty()) return;
        Session s = SESSIONS.get(g.getUUID()); if (s == null) return;
        ServerLevel level = g.serverLevel();
        ServerPlayer user = level.getServer().getPlayerList().getPlayer(s.user);
        if (user == null || user.level() != g.level()) { ClaudeMod.LOGGER.info("[Claude watch] {} isn't in his world any more: {} (WatchBuild.tick)", s.userName, s.lookingOver || s.learned.isEmpty() ? "stopping" : "keeping what he saw"); if (s.learned.isEmpty()) end(g, s, "they left"); else learn(g, s, user); return; }
        g.nextPlanTick = Math.max(g.nextPlanTick, g.tickCount + 100); // (his own planning waits while he watches)
        if (s.lookingOver) { lookOverTick(g, s, level, user); return; }
        if (!s.frozen && g.tickCount % 20 == 0) snapshot(g, s, level);
        if (Whereabouts.seesNow(g, user)) s.userSeenTick = g.tickCount;
        boolean holding = s.hold != null && (g.currentTask == s.hold || g.tasks.stream().anyMatch(x -> x == s.hold));
        if (!holding) {
            if (com.claudemod.director.ClaudeLife.stopHeld(g)) { end(g, s, "they said stop"); return; }
            if (g.skillQueue.stream().anyMatch(x -> x.skill().equals("watch_build"))) return; // (the step's about to start it)
            boolean theJob = g.lastStep != null && g.lastStep.skill().equals("watch_build") && g.skillQueue.isEmpty();
            if (!theJob) { end(g, s, "a new job (" + (g.lastStep == null ? "none" : g.lastStep.skill()) + ", queue " + g.skillQueue + ")"); return; }
            if (s.hold != null && g.tickCount - s.startTick >= HOLD) { finish(g, s, user, "half an hour of watching and no \"done\""); return; }
            if (g.currentTask == null && g.tasks.isEmpty()) { s.hold = new Task.Wait(HOLD, false); g.tasks.add(s.hold); ClaudeMod.LOGGER.info("[Claude watch] back to standing by while they build (the hold was dropped by something that came first) (WatchBuild.tick)"); }
            return;
        }
        if (g.currentTask != s.hold) return; // (walking to a better spot, or a reflex first)
        Vec3 at = s.lastEvent != null && g.tickCount - s.lastEventTick < 40 ? Vec3.atCenterOf(s.lastEvent) : Whereabouts.seesNow(g, user) ? aimOf(user) : centre(s);
        if (at != null) g.getLookControl().setLookAt(at); // watching their hands (where they look, what they just put in), as a player watches a friend build
        if (g.tickCount % 20 == 0 && g.tickCount - s.lastMoveTick > 60) {
            String why = s.missed > s.missedAtMove ? "missed " + (s.missed - s.missedAtMove) + " block(s) going in out of his sight"
                    : g.tickCount - s.userSeenTick > 60 && !recentVisible(g, s, level) ? "can't see them or the build"
                    : inTheWay(g, s) ? "standing where they're building" : null;
            if (why != null) {
                s.lastMoveTick = g.tickCount; s.missedAtMove = s.missed;
                BlockPos spot = vantage(g, s, level, user);
                if (spot != null) {
                    g.tasks.addFirst(s.hold = new Task.Wait(HOLD, false)); g.tasks.addFirst(new Task.GoTo(spot, 0.6)); g.dropCurrent = true;
                    ClaudeMod.LOGGER.info("[Claude watch] {}: to {} where he can see it (WatchBuild.tick)", why, spot.toShortString());
                } else ClaudeMod.LOGGER.info("[Claude watch] {}: no spot round it sees more than {} does (WatchBuild.tick)", why, g.blockPosition().toShortString());
            }
        }
    }

    /** Where they're looking (their head shows it): the block their eyes are on within reach-and-a-bit, else a few blocks ahead of them. */
    static Vec3 aimOf(ServerPlayer p) {
        Vec3 eye = p.getEyePosition(), to = eye.add(p.getViewVector(1f).scale(8));
        var hit = p.level().clip(new net.minecraft.world.level.ClipContext(eye, to, net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.NONE, p));
        return hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK ? hit.getLocation() : eye.add(p.getViewVector(1f).scale(3));
    }
    static Vec3 centre(Session s) {
        double x = 0, y = 0, z = 0; int n = 0;
        for (var e : s.learned.entrySet()) if (!e.getValue().state().isAir()) { x += e.getKey().getX() + 0.5; y += e.getKey().getY() + 0.5; z += e.getKey().getZ() + 0.5; n++; }
        for (BlockPos h : s.heard) { x += h.getX() + 0.5; y += h.getY() + 0.5; z += h.getZ() + 0.5; n++; }
        return n == 0 ? Vec3.atCenterOf(s.anchor) : new Vec3(x / n, y / n, z / n);
    }
    static List<BlockPos> recent(Session s, int n) {
        List<BlockPos> all = new ArrayList<>(s.learned.keySet()); all.addAll(s.heard);
        return all.subList(Math.max(0, all.size() - n), all.size());
    }
    /** A face of the cell his eyes reach from there (no frustum: where he'd look from that spot). */
    static boolean openTo(ServerLevel level, Vec3 eye, BlockPos b) {
        Vec3 c = Vec3.atCenterOf(b);
        if (level.getBlockState(b).isAir() && Perception.clear(level, eye, c)) return true;
        for (Direction d : Direction.values()) {
            if (level.getBlockState(b.relative(d)).canOcclude()) continue;
            if (Perception.clear(level, eye, c.add(d.getStepX() * 0.51, d.getStepY() * 0.51, d.getStepZ() * 0.51))) return true;
        }
        return false;
    }
    static boolean recentVisible(ClaudeEntity g, Session s, ServerLevel level) {
        for (BlockPos b : recent(s, 3)) if (openTo(level, g.getEyePosition(), b)) return true;
        return false;
    }
    static boolean inTheWay(ClaudeEntity g, Session s) {
        Vec3 me = g.position();
        for (var e : s.learned.entrySet()) if (!e.getValue().state().isAir() && Vec3.atBottomCenterOf(e.getKey()).distanceTo(me) < 1.8) return true;
        return s.lastEvent != null && Vec3.atBottomCenterOf(s.lastEvent).distanceTo(me) < 1.8;
    }
    static int score(ServerLevel level, Vec3 eye, List<BlockPos> cells, ServerPlayer user) {
        int n = 0; for (BlockPos b : cells) if (openTo(level, eye, b)) n++;
        if (user != null && Perception.clear(level, eye, user.getEyePosition())) n += 2;
        return n;
    }
    /** A spot 4-7 from the work he can stand on and see more of it from; null: none better than where he is. */
    static BlockPos vantage(ClaudeEntity g, Session s, ServerLevel level, ServerPlayer user) {
        List<BlockPos> cells = recent(s, 10);
        Vec3 c = cells.isEmpty() && user != null ? user.position() : centre(s);
        int bs = score(level, g.getEyePosition(), cells, user); BlockPos best = null; double bd = Double.MAX_VALUE;
        for (int r = 4; r <= 7; r++) for (int a = 0; a < 16; a++) {
            double ang = a * Math.PI / 8;
            int x = (int) Math.floor(c.x + r * Math.cos(ang)), z = (int) Math.floor(c.z + r * Math.sin(ang));
            for (int dy = 3; dy >= -4; dy--) {
                BlockPos sp = new BlockPos(x, (int) Math.floor(c.y) + dy, z);
                if (!PlaceAim.standable(level, sp)) continue;
                boolean near = false; for (BlockPos b : s.learned.keySet()) if (Math.abs(b.getX() - x) <= 1 && Math.abs(b.getZ() - z) <= 1) near = true;
                if (near) break;
                int sc = score(level, Vec3.atBottomCenterOf(sp).add(0, g.getEyeHeight(), 0), cells, user);
                double d = sp.distSqr(g.blockPosition());
                if (sc > bs || sc == bs && best != null && d < bd) { best = sp; bs = sc; bd = d; }
                break;
            }
        }
        return best;
    }

    /** "Done": he stops standing by and walks round it, looking it over from each corner. */
    static void finish(ClaudeEntity g, Session s, ServerPlayer user, String why) {
        s.lookingOver = true;
        if (s.hold != null) { final Task.Wait h = s.hold; g.tasks.removeIf(x -> x == h); if (g.currentTask == h) g.dropCurrent = true; }
        ServerLevel level = g.serverLevel();
        int[] box = box(s);
        if (box == null) { ClaudeMod.LOGGER.info("[Claude watch] {}: nothing seen or heard go in, nothing to look over (WatchBuild.finish)", why); learn(g, s, user); return; }
        s.lookCentre = new Vec3((box[0] + box[3] + 1) / 2.0, (box[1] + box[4] + 1) / 2.0, (box[2] + box[5] + 1) / 2.0);
        int[][] corners = {{box[0] - 2, box[2] - 2}, {box[3] + 2, box[2] - 2}, {box[3] + 2, box[5] + 2}, {box[0] - 2, box[5] + 2}};
        int start = 0; double sd = Double.MAX_VALUE; // round from the corner nearest him
        for (int i = 0; i < 4; i++) { double d = g.position().distanceToSqr(corners[i][0] + 0.5, g.getY(), corners[i][1] + 0.5); if (d < sd) { sd = d; start = i; } }
        List<String> spots = new ArrayList<>();
        for (int k = 0; k < 4; k++) {
            int[] cn = corners[(start + k) % 4]; BlockPos stand = null;
            for (int dy = 2; dy >= -3 && stand == null; dy--) { BlockPos sp = new BlockPos(cn[0], box[1] + dy, cn[1]); if (PlaceAim.standable(level, sp)) stand = sp; }
            if (stand == null) { ClaudeMod.LOGGER.info("[Claude watch] no footing at the corner {} {} to look from: skipped (WatchBuild.finish)", cn[0], cn[1]); continue; }
            Task go = new Task.GoTo(stand, 0.8), look = new Task.Wait(12, false);
            g.tasks.add(go); g.tasks.add(look); s.looks.add(go); s.looks.add(look); spots.add(stand.toShortString());
        }
        ClaudeMod.LOGGER.info("[Claude watch] {}: {} seen go in, {} heard out of sight; looking it over from {} spots round it: {} (WatchBuild.finish)", why, s.seen, s.missed, spots.size(), spots);
        if (spots.isEmpty()) learn(g, s, user);
    }
    /** Min x y z, max x y z of what went in (seen or heard). */
    static int[] box(Session s) {
        int[] b = null;
        List<BlockPos> all = new ArrayList<>(s.learned.keySet()); all.addAll(s.heard);
        for (BlockPos p : all) {
            if (b == null) b = new int[] {p.getX(), p.getY(), p.getZ(), p.getX(), p.getY(), p.getZ()};
            b[0] = Math.min(b[0], p.getX()); b[1] = Math.min(b[1], p.getY()); b[2] = Math.min(b[2], p.getZ()); b[3] = Math.max(b[3], p.getX()); b[4] = Math.max(b[4], p.getY()); b[5] = Math.max(b[5], p.getZ());
        }
        return b;
    }
    static void lookOverTick(ClaudeEntity g, Session s, ServerLevel level, ServerPlayer user) {
        boolean any = false; for (Task x : s.looks) if (x == g.currentTask || g.tasks.stream().anyMatch(y -> y == x)) { any = true; break; }
        if (g.currentTask instanceof Task.Wait w && s.looks.stream().anyMatch(x -> x == w)) {
            if (s.lookNow != w) { s.lookNow = w; s.lookTicks = 0; }
            s.lookTicks++;
            g.getLookControl().faceNow(s.lookCentre);
            if (s.lookTicks == 2) scan(g, s, level);
        }
        if (!any) learn(g, s, user);
    }
    /** From where he stands, facing it: every cell round it he sees, as it is. */
    static void scan(ClaudeEntity g, Session s, ServerLevel level) {
        int[] b = box(s); if (b == null) return;
        int n = 0;
        for (BlockPos q : BlockPos.betweenClosed(b[0] - 2, b[1] - 1, b[2] - 2, b[3] + 2, b[4] + 2, b[5] + 2)) {
            if (s.learned.containsKey(q) || !Perception.seesBlock(g, level, q)) continue;
            s.lookSeen.put(q.immutable(), level.getBlockState(q)); n++;
        }
        s.scans++;
        ClaudeMod.LOGGER.info("[Claude watch] looking it over from {}: {} cells round it in view (WatchBuild.scan)", g.blockPosition().toShortString(), n);
    }
    /** Why a cell seen on the look round is part of the build, or null (terrain, unchanged, not joined to it). */
    static String foundWhy(Session s, BlockPos c, BlockState now) {
        if (companion(now)) return null;
        BlockState was = s.before.get(c);
        if (was != null) return PlaceAim.same(now, was) || now.isAir() && was.canBeReplaced() ? null : now.isAir() ? "gone since his first look (" + name(was) + " then)" : "not there at his first look (" + (was.isAir() ? "empty" : name(was)) + " then)";
        if (now.isAir() || now.canBeReplaced()) return null;
        for (BlockPos h : s.heard) if (h.distManhattan(c) <= 3) return "a spot he never saw before, where he heard a block go in";
        return natural(now) ? null : "a spot he never saw before, and no natural block (" + name(now) + ")";
    }
    static boolean touches(BlockPos c, Set<BlockPos> solid) { for (Direction d : Direction.values()) if (solid.contains(c.relative(d))) return true; return false; }

    /** The look round's over (or there was none): what he found joins what he saw, and the blueprint is made and saved. */
    static void learn(ClaudeEntity g, Session s, ServerPlayer user) {
        SESSIONS.remove(g.getUUID());
        Set<BlockPos> solid = new HashSet<>(); for (var e : s.learned.entrySet()) if (!e.getValue().state().isAir()) solid.add(e.getKey());
        List<BlockPos> cand = new ArrayList<>(s.lookSeen.keySet()); cand.sort(Comparator.comparingInt((BlockPos p) -> p.getY()).thenComparingInt(p -> p.getX()).thenComparingInt(p -> p.getZ()));
        int added = 0, notBuild = 0; boolean grew = true; Set<BlockPos> judged = new HashSet<>();
        while (grew) {
            grew = false;
            for (BlockPos c : cand) {
                if (s.learned.containsKey(c) || judged.contains(c) || !touches(c, solid)) continue;
                BlockState now = s.lookSeen.get(c); String why = foundWhy(s, c, now);
                judged.add(c);
                if (why == null) { notBuild++; continue; }
                s.learned.put(c, new Seen(now.isAir() ? LearnedBlueprint.air() : now, s.order++, "looked over"));
                if (!now.isAir()) solid.add(c);
                added++; grew = true;
                ClaudeMod.LOGGER.info("[Claude watch] found {} at {} looking it over: {} (WatchBuild.learn)", now.isAir() ? "air" : name(now), xyz(c), why);
            }
        }
        if (s.learned.isEmpty()) { lastLine = "i didn't see anything go in"; if (user != null) com.claudemod.director.ClaudeChat.say(user, lastLine, "watched build: nothing"); ClaudeMod.LOGGER.info("[Claude watch] nothing learned watching {}: no blueprint (WatchBuild.learn)", s.userName); return; }
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        for (BlockPos p : s.learned.keySet()) { minX = Math.min(minX, p.getX()); minY = Math.min(minY, p.getY()); minZ = Math.min(minZ, p.getZ()); }
        BlockPos corner = new BlockPos(minX, minY, minZ);
        List<LearnedBlueprint.Cell> cells = new ArrayList<>(); Direction front = null; int seenIn = 0;
        for (var e : s.learned.entrySet()) {
            BlockState st = e.getValue().state();
            cells.add(new LearnedBlueprint.Cell(e.getKey().subtract(corner), st, e.getValue().order(), e.getValue().how()));
            if (e.getValue().how().startsWith("seen")) seenIn++;
            if (front == null && st.getBlock() instanceof DoorBlock) front = st.getValue(DoorBlock.FACING).getOpposite(); // (a door: its outside is the front)
        }
        String frontWhy = "its door's outside";
        if (front == null) { Vec3 c = centre(s); front = Direction.getNearest(s.watcherStart.x - c.x, 0, s.watcherStart.z - c.z); if (!front.getAxis().isHorizontal()) front = Direction.SOUTH; frontWhy = "the side he watched from"; }
        LearnedBlueprint bp = new LearnedBlueprint(s.name, s.userName, front, cells);
        boolean replaced = library(g).containsKey(s.name.toLowerCase(Locale.ROOT));
        boolean saved = keep(g, bp);
        LAST.put(g.getUUID(), s.name);
        ClaudeMod.LOGGER.info("[Claude watch] learned \"{}\": {} ({} seen go in, {} found looking it over, {} seen round it that aren't part of it); front {} ({}); {}{} (WatchBuild.learn)", s.name, bp.summary(), seenIn, added, notBuild, front.getName(), frontWhy, saved ? "saved" : "NOT saved", replaced ? ", in place of the old one" : "");
        lastLine = "got it: " + display(s.name) + ", " + bp.blocks() + " blocks (" + seenIn + " seen go in" + (added > 0 ? ", " + added + " found looking it over" : "") + "). say \"build " + display(s.name) + " here\" and i'll make one";
        if (user != null) com.claudemod.director.ClaudeChat.say(user, lastLine, "watched build: learned");
    }

    /** Watching ends without a blueprint (a new job, a stop). */
    static void end(ClaudeEntity g, Session s, String why) {
        SESSIONS.remove(g.getUUID());
        if (s.hold != null) { final Task.Wait h = s.hold; g.tasks.removeIf(x -> x == h); if (g.currentTask == h) g.dropCurrent = true; }
        ClaudeMod.LOGGER.info("[Claude watch] stopped watching {} ({}): {} blocks seen, not kept (WatchBuild.end)", s.userName, why, s.learned.size());
    }

    // ================================================================ his library (per bot, outside the world)

    private static final Map<UUID, LinkedHashMap<String, LearnedBlueprint>> LIB = new HashMap<>();
    private static final Map<UUID, String> LAST = new HashMap<>();
    static String bot(ClaudeEntity g) { return g.getGameProfile().getName(); }

    /** His learned blueprints, by lower-case name (read from config the first time: every new world has them). */
    public static LinkedHashMap<String, LearnedBlueprint> library(ClaudeEntity g) {
        LinkedHashMap<String, LearnedBlueprint> lib = LIB.get(g.getUUID());
        if (lib != null) return lib;
        lib = new LinkedHashMap<>();
        for (var o : LearnedStore.all(bot(g), LearnedStore.Kind.BLUEPRINT)) { LearnedBlueprint bp = LearnedBlueprint.fromJson(o); if (bp != null) lib.put(bp.name.toLowerCase(Locale.ROOT), bp); }
        LIB.put(g.getUUID(), lib);
        ClaudeMod.LOGGER.info("[Claude learned] {} learned build(s) for {} read from {}: {} (WatchBuild.library)", lib.size(), bot(g), LearnedStore.dir(bot(g), LearnedStore.Kind.BLUEPRINT), lib.keySet());
        return lib;
    }
    static boolean keep(ClaudeEntity g, LearnedBlueprint bp) { library(g).put(bp.name.toLowerCase(Locale.ROOT), bp); return LearnedStore.save(bot(g), LearnedStore.Kind.BLUEPRINT, bp.name, bp.toJson()); }
    public static LearnedBlueprint get(ClaudeEntity g, String name) { return library(g).get(name.toLowerCase(Locale.ROOT)); }
    public static String latest(ClaudeEntity g) {
        String l = LAST.get(g.getUUID()); if (l != null && get(g, l) != null) return l;
        String last = null; for (LearnedBlueprint bp : library(g).values()) last = bp.name; return last;
    }

    /** The blueprint their words point at: a learned name in them, else "that/it/one of those" (the latest); null: none. */
    public static LearnedBlueprint find(ClaudeEntity g, String words) {
        String w = words == null ? "" : words.toLowerCase(Locale.ROOT).trim();
        LearnedBlueprint best = null;
        for (LearnedBlueprint bp : library(g).values()) {
            String n = bp.name.toLowerCase(Locale.ROOT);
            if (w.equals(n) || LearnedStore.slug(w).equals(LearnedStore.slug(n)) || (" " + w + " ").contains(" " + n + " ")) if (best == null || n.length() > best.name.length()) best = bp;
        }
        if (best != null) return best;
        String rest = w.replaceAll("\\b(the|a|an|that|this|it|one|of|those|these|them|again|another|like|here|there|over|me|build|make|copy|same|thing|please)\\b", "").trim();
        if (rest.isEmpty()) { String l = latest(g); return l == null ? null : get(g, l); }
        return null;
    }

    // ================================================================ their words

    static final Pattern WATCH = Pattern.compile("\\b(watch|see|look at|learn|study|copy)\\b\\s+(what|how|me|while)\\b(\\s+(i|me|im|i'm))?\\s+(build|make|making|building|do)\\b(\\s+(a|an|the|my|this|some|me a)\\s+(?<name>[a-z0-9' -]{1,40}))?");
    static final Pattern DONE = Pattern.compile("(ok(ay)?|alright|right|and|so|ok so)?[ ,]*(done|i'?m done|im done|that'?s it|thats it|finished|i'?m finished|all done|there|there you go|that'?s all|done building|it'?s done|that'?s the build)([ ,]+claude)?[ .!]*");
    static final Pattern FACING = Pattern.compile("\\b(?:facing|face|faced|pointing|front (?:to|toward|towards|facing)(?: the)?)\\s+(north|south|east|west)\\b|\\b(north|south|east|west)[- ]facing\\b");

    /** Their words about watching or rebuilding: handled here (true), or left to the chat (false). */
    public static boolean heard(ClaudeEntity g, ServerPlayer player, String raw) {
        String low = raw.toLowerCase(Locale.ROOT).replace('’', '\'').trim();
        Session s = SESSIONS.get(g.getUUID());
        if (s != null && !s.lookingOver && s.user.equals(player.getUUID()) && DONE.matcher(low).matches()) {
            ClaudeMod.LOGGER.info("[Claude watch] \"{}\" from {}: the build's done, looking it over (WatchBuild.heard)", raw, s.userName);
            lastLine = "ok, let me look it over"; com.claudemod.director.ClaudeChat.say(player, lastLine, "watch: done");
            finish(g, s, player, "they said \"" + raw + "\"");
            return true;
        }
        Matcher w = WATCH.matcher(low);
        if (w.find() && !low.endsWith("?")) {
            String nm = w.group("name") == null ? "" : w.group("name").replaceAll("\\b(here|now|real quick|quick|ok|okay|please|for me|claude|and)\\b.*$", "").replaceAll("[^a-z0-9' -]", "").trim();
            ClaudeMod.LOGGER.info("[Claude watch] \"{}\": watch them build{} (WatchBuild.heard)", raw, nm.isEmpty() ? "" : " \"" + nm + "\"");
            com.claudemod.director.ClaudeLife.command(g, player, steps("watch_build", nm, "near_them"), "watch them build" + (nm.isEmpty() ? " and learn it" : " a " + nm + " and learn it") + " (their words: \"" + raw + "\")", "replace");
            lastLine = "ok, watching" + (nm.isEmpty() ? "" : " you build the " + nm) + ". say done when it's finished"; com.claudemod.director.ClaudeChat.say(player, lastLine, "watch: start");
            return true;
        }
        if (low.matches("(?s).*\\b(build|make|copy|rebuild|put up)\\b.*") && !low.endsWith("?")) {
            LearnedBlueprint bp = referred(g, low);
            if (bp != null) {
                Matcher f = FACING.matcher(low); String facing = f.find() ? (f.group(1) != null ? f.group(1) : f.group(2)) : null;
                String where = low.matches("(?s).*\\b(over )?there\\b.*") && !low.matches("(?s).*\\bhere\\b.*") ? "looked_at" : "near_them";
                String target = bp.name + (facing == null ? "" : " facing " + facing);
                ClaudeMod.LOGGER.info("[Claude build] \"{}\" is about his learned \"{}\": build_blueprint \"{}\" {} (WatchBuild.heard)", raw, bp.name, target, where);
                com.claudemod.director.ClaudeLife.command(g, player, steps("build_blueprint", target, where), "build " + display(bp.name) + " again" + (facing == null ? "" : " facing " + facing) + " (their words: \"" + raw + "\")", "replace");
                lastLine = "on it: " + display(bp.name) + ", " + bp.blocks() + " blocks" + (facing == null ? "" : ", its front to the " + facing); com.claudemod.director.ClaudeChat.say(player, lastLine, "build: start");
                return true;
            }
        }
        return false;
    }
    /** A learned name in their words, else "that again / one of those / another / like that / copy that" (the latest one). */
    static LearnedBlueprint referred(ClaudeEntity g, String low) {
        LearnedBlueprint best = null;
        for (LearnedBlueprint bp : library(g).values()) { String n = bp.name.toLowerCase(Locale.ROOT); if (low.matches("(?s).*\\b" + Pattern.quote(n) + "s?\\b.*") && (best == null || n.length() > best.name.length())) best = bp; }
        if (best != null) return best;
        if (low.matches("(?s).*\\b((that|this|it) (one )?again|one of (those|these|them|that)|another (one|of (those|these))|(one|another) like (that|this|it)|copy (of )?(it|that|this)|same (thing|one|build)|build that\\b( (here|there|over there))?).*")) { String l = latest(g); return l == null ? null : get(g, l); }
        return null;
    }
    static com.google.gson.JsonArray steps(String skill, String target, String where) {
        var a = new com.google.gson.JsonArray(); var o = new com.google.gson.JsonObject();
        o.addProperty("skill", skill); o.addProperty("target", target); o.addProperty("count", 1); o.addProperty("where", where); a.add(o);
        return a;
    }

    // ================================================================ the skills

    /** watch_build [name]: stand by facing them and learn what he sees them build, till they say done. */
    public static String skillWatch(ClaudeEntity g, ServerPlayer player, ServerLevel level, Skills.Step st, String target) {
        Session s = SESSIONS.get(g.getUUID());
        if (s != null && s.lookingOver) return "looking over what they built (the look round's still going)";
        if (s != null && !s.user.equals(player.getUUID())) { end(g, s, "watching someone else now"); s = null; }
        if (s == null) s = start(g, player, target.replace('_', ' ').trim());
        final Task.Wait old = s.hold; if (old != null) g.tasks.removeIf(x -> x == old);
        g.tasks.add(s.hold = new Task.Wait(HOLD, false));
        s.lastMoveTick = -100000;
        return "watching " + s.userName + " build " + display(s.name) + ": standing by, learning each block i see go in, till they say done";
    }

    static final class Build {
        final Skills.Step step; final LearnedBlueprint bp; final Rotation rot; final Direction front; final BlockPos origin; final int baseY; final UUID user; final int startTick;
        final LinkedHashMap<BlockPos, BlockState> cells = new LinkedHashMap<>(); // world spot -> planned state, in their order
        int phase, fixed, fixRounds, gathers; String site = "";
        Build(Skills.Step step, LearnedBlueprint bp, Rotation rot, Direction front, BlockPos origin, UUID user, int startTick) { this.step = step; this.bp = bp; this.rot = rot; this.front = front; this.origin = origin; this.baseY = origin.getY(); this.user = user; this.startTick = startTick; }
    }
    static final int GATHER = 0, PLACE = 1, VERIFY = 2;
    private static final Map<UUID, Build> BUILDS = new HashMap<>();
    private static final Map<UUID, Set<BlockPos>> BUILT = new HashMap<>();

    /** A cell of a build of his (one going up, or one he finished): no temporary block, never litter. */
    public static boolean ownsCell(ClaudeEntity g, BlockPos p) {
        Build b = BUILDS.get(g.getUUID()); if (b != null && b.cells.containsKey(p)) return true;
        Set<BlockPos> done = BUILT.get(g.getUUID()); return done != null && done.contains(p);
    }
    public static BlockPos buildOrigin(ClaudeEntity g) { Build b = BUILDS.get(g.getUUID()); return b == null ? null : b.origin; }

    static Direction facingIn(String words) { Matcher f = FACING.matcher(words); if (!f.find()) return null; return Direction.byName(f.group(1) != null ? f.group(1) : f.group(2)); }

    /** build_blueprint <name> [facing <dir>]: one of his learned builds, again. Comes back to itself: materials, the blocks, the check. */
    public static String skillBuild(ClaudeEntity g, ServerPlayer player, ServerLevel level, Skills.Step st, String target) {
        Build b = BUILDS.get(g.getUUID());
        if (b == null || b.step != st) { // a new ask
            String words = target.replace('_', ' ').trim();
            Direction facing = facingIn(words);
            String nameWords = FACING.matcher(words).replaceAll("").trim();
            LearnedBlueprint bp = find(g, nameWords);
            if (bp == null) {
                String known = String.join(", ", library(g).values().stream().map(x -> x.name).toList());
                ClaudeMod.LOGGER.info("[Claude build] no learned build matches \"{}\" (he knows: {}) (WatchBuild.skillBuild)", nameWords, known.isEmpty() ? "none" : known);
                return "failed: i haven't learned a build called \"" + nameWords + "\" (" + (known.isEmpty() ? "none learned yet: say \"watch what i build\" first" : "i know: " + known) + ")";
            }
            Rotation rot = bp.turnTo(facing);
            List<LearnedBlueprint.Cell> turned = bp.turned(rot);
            Direction front = rot.rotate(bp.front);
            int w = 0, d = 0; for (var c : turned) { w = Math.max(w, c.at().getX() + 1); d = Math.max(d, c.at().getZ() + 1); }
            BlockPos at; String whereWhy;
            BlockPos look = "looked_at".equals(st.where()) ? lookedAt(g, player) : null;
            BlockPos origin;
            if (look != null) { at = look; whereWhy = "where they're looking (" + xyz(look) + ")"; origin = new BlockPos(at.getX() - (w - 1) / 2, at.getY(), at.getZ() - (d - 1) / 2); }
            else {
                BlockPos them = "near_you".equals(st.where()) ? null : Whereabouts.current(g, player) ? Whereabouts.spot(g, player) : null;
                at = them != null ? them : g.blockPosition();
                whereWhy = them != null ? "in front of them (at " + xyz(them) + ")" : "near_you".equals(st.where()) ? "in front of me" : "in front of me (i can't see where they are right now)";
                origin = switch (front) { // their spot 2 clear of its front, its middle lined up with them
                    case SOUTH -> new BlockPos(at.getX() - (w - 1) / 2, at.getY(), at.getZ() - 3 - (d - 1));
                    case NORTH -> new BlockPos(at.getX() - (w - 1) / 2, at.getY(), at.getZ() + 3);
                    case EAST -> new BlockPos(at.getX() - 3 - (w - 1), at.getY(), at.getZ() - (d - 1) / 2);
                    default -> new BlockPos(at.getX() + 3, at.getY(), at.getZ() - (d - 1) / 2);
                };
            }
            b = new Build(st, bp, rot, front, origin, player.getUUID(), g.tickCount);
            for (var c : turned) if (!companion(c.state())) b.cells.put(origin.offset(c.at()), c.state());
            String bad = siteCheck(g, level, b);
            if (bad != null) { ClaudeMod.LOGGER.info("[Claude build] {} at {}: {} (WatchBuild.skillBuild)", display(bp.name), xyz(origin), bad); return "failed: " + bad; }
            BUILDS.put(g.getUUID(), b);
            ClaudeMod.LOGGER.info("[Claude build] {}: {} at {} ({}), turned {} so its front is to the {}; the site: {} (WatchBuild.skillBuild)", display(bp.name), bp.summary(), xyz(origin), whereWhy, rot, front.getName(), b.site);
        }
        if (b.phase == GATHER) { String r = gather(g, level, b); if (r != null) return r; b.phase = PLACE; }
        if (b.phase == PLACE) return place(g, level, b);
        return verify(g, level, b, player);
    }

    /** The block they're looking at (he sees them and where they point), or null. */
    static BlockPos lookedAt(ClaudeEntity g, ServerPlayer p) {
        if (!Whereabouts.seesNow(g, p)) return null;
        var hit = p.level().clip(new net.minecraft.world.level.ClipContext(p.getEyePosition(), p.getEyePosition().add(p.getViewVector(1f).scale(48)), net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.NONE, p));
        return hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK ? hit.getBlockPos().relative(hit.getDirection()).immutable() : null;
    }

    static boolean right(ServerLevel level, BlockPos p, BlockState want) { return PlaceAim.same(level.getBlockState(p), want); }

    /** Flat and clear enough: holes under its bottom and ground in its cells counted; refused past a third (filling or digging the ground round it is no part of the build). */
    static String siteCheck(ClaudeEntity g, ServerLevel level, Build b) {
        int blocked = 0, holes = 0, base = 0; List<String> hard = new ArrayList<>();
        for (var e : b.cells.entrySet()) {
            BlockPos p = e.getKey(); BlockState now = level.getBlockState(p), want = e.getValue();
            if (!level.isLoaded(p)) return "the spot at " + xyz(p) + " isn't loaded";
            if (!PlaceAim.same(now, want) && !now.canBeReplaced()) {
                blocked++;
                if (now.hasBlockEntity() || now.is(BlockTags.BEDS) || now.getDestroySpeed(level, p) < 0) hard.add(name(now) + " at " + xyz(p));
            }
            if (p.getY() == b.baseY && !want.isAir()) { base++; BlockPos u = p.below(); if (level.getBlockState(u).getCollisionShape(level, u).isEmpty() && !b.cells.containsKey(u)) holes++; }
        }
        if (!hard.isEmpty()) return "there's something there i won't break for it: " + String.join(", ", hard.subList(0, Math.min(3, hard.size()))) + " (somewhere else?)";
        if (base > 0 && holes * 3 > base) return "the ground there isn't flat enough: " + holes + " of the " + base + " spots under it are holes, and filling the ground round it isn't part of the build (somewhere flatter?)";
        if (blocked * 3 > b.cells.size()) return "there's a hill in the way there: " + blocked + " of its " + b.cells.size() + " cells have ground in them, and digging the land away isn't part of the build (somewhere clearer?)";
        b.site = (holes > 0 ? holes + " dip(s) under it, built over and not filled" : "flat") + ", " + (blocked > 0 ? blocked + " of its own cells to clear first" : "clear");
        return null;
    }

    static int need(BlockState want) { return want.hasProperty(BlockStateProperties.SLAB_TYPE) && want.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE ? 2 : 1; }
    static String id(Item i) { return BuiltInRegistries.ITEM.getKey(i).getPath(); }

    /** What's still to go in, against his pack and his chests: fetched from a chest, else got (obtain); null when it's all on him. */
    static String gather(ClaudeEntity g, ServerLevel level, Build b) {
        Map<Item, Integer> need = new LinkedHashMap<>(); List<String> none = new ArrayList<>();
        for (var e : b.cells.entrySet()) {
            if (e.getValue().isAir() || right(level, e.getKey(), e.getValue())) continue;
            Item it = e.getValue().getBlock().asItem();
            if (it == Items.AIR) { none.add(name(e.getValue())); continue; }
            BlockState now = level.getBlockState(e.getKey());
            need.merge(it, PlaceAim.growsInto(now, e.getValue()) ? 1 : need(e.getValue()), Integer::sum);
        }
        if (!none.isEmpty()) ClaudeMod.LOGGER.info("[Claude build] no item places {}: left out of {} (WatchBuild.gather)", none, display(b.bp.name));
        int up = 0; for (BlockPos p : b.cells.keySet()) if (!b.cells.get(p).isAir() && !right(level, p, b.cells.get(p))) up = Math.max(up, p.getY() - b.baseY - 1);
        if (up > 0) { // his tower beside it: blocks he has spare (they come back up after)
            int spare = 0; for (Item s0 : new Item[] {Items.DIRT, Items.COBBLESTONE, Items.COBBLED_DEEPSLATE}) spare += Math.max(0, g.inventory.count(x -> x.is(s0)) - need.getOrDefault(s0, 0));
            if (spare < up) need.merge(Items.DIRT, up - spare, Integer::sum);
        }
        List<Map.Entry<Item, Integer>> order = new ArrayList<>(need.entrySet()); // the made things first (a door, stairs), the plain blocks last: their planks aren't eaten after
        order.sort(Comparator.comparingInt(e -> e.getKey() instanceof net.minecraft.world.item.BlockItem bi && bi.getBlock().defaultBlockState().isCollisionShapeFullBlock(level, BlockPos.ZERO) ? 1 : 0));
        List<Skills.Step> obtain = new ArrayList<>(); List<String> fetched = new ArrayList<>(), got = new ArrayList<>(), have = new ArrayList<>();
        for (var e : order) {
            Item it = e.getKey(); int n = e.getValue(), onHim = g.inventory.count(x -> x.is(it)), shortBy = n - onHim;
            if (shortBy <= 0) { have.add(n + " " + id(it)); continue; }
            for (var sp : ChestLedger.where(g, level, x -> x.is(it))) {
                if (shortBy <= 0) break;
                Integer failed = g.attempts.any.get(sp.pos()); if (failed != null && g.tickCount - failed < 20 * 300) continue; // (one he couldn't get to a moment ago)
                int take = Math.min(shortBy, sp.count());
                g.tasks.add(new Task.GoTo(sp.pos(), 2)); g.tasks.add(new Task.Chest(sp.pos(), true, it, take));
                fetched.add(take + " " + id(it) + " from my chest at " + xyz(sp.pos())); shortBy -= take;
            }
            if (shortBy > 0) { obtain.add(new Skills.Step("obtain", id(it), Math.min(64, n), "near_you")); got.add(shortBy + " " + id(it)); } // (obtain's count is what he's to have on him in all: the chest's share is in his pack by then)
        }
        if (fetched.isEmpty() && obtain.isEmpty()) { ClaudeMod.LOGGER.info("[Claude build] {}: everything it takes is on him ({}) (WatchBuild.gather)", display(b.bp.name), have); return null; }
        if (++b.gathers > 4) { BUILDS.remove(g.getUUID()); ClaudeMod.LOGGER.info("[Claude build] {}: four rounds of getting things and still short of {} (WatchBuild.gather)", display(b.bp.name), got.isEmpty() ? fetched : got); return "failed: couldn't get together what " + display(b.bp.name) + " takes: still short of " + String.join(", ", got.isEmpty() ? fetched : got); }
        g.skillQueue.addFirst(b.step);
        for (int i = obtain.size() - 1; i >= 0; i--) g.skillQueue.addFirst(obtain.get(i));
        ClaudeMod.LOGGER.info("[Claude build] {}: on him {}; from his chests {}; to get {} (WatchBuild.gather)", display(b.bp.name), have, fetched, got);
        return "getting what " + display(b.bp.name) + " takes first:" + (fetched.isEmpty() ? "" : " " + String.join(", ", fetched) + ";") + (got.isEmpty() ? "" : " making/getting " + String.join(", ", got));
    }

    static boolean solidAt(ServerLevel level, BlockPos p) { return !level.getBlockState(p).getCollisionShape(level, p).isEmpty(); }
    static boolean needsFloor(BlockState s) { return s.getBlock() instanceof DoorBlock || s.getBlock() instanceof BedBlock || s.is(BlockTags.WOOL_CARPETS) || s.getBlock() instanceof net.minecraft.world.level.block.PressurePlateBlock; }

    /** The blocks in their order, one held back only until something it can go against is in (bottom-up where their order allows), then the tasks: from the ground, or from a tower of his beside it for the high ones. */
    static String place(ClaudeEntity g, ServerLevel level, Build b) {
        Set<BlockPos> clearFirst = new HashSet<>(); List<BlockPos> todo = new ArrayList<>();
        for (var e : b.cells.entrySet()) {
            BlockPos p = e.getKey(); BlockState want = e.getValue(), now = level.getBlockState(p);
            if (PlaceAim.same(now, want)) continue;
            if (!now.canBeReplaced() && !PlaceAim.growsInto(now, want)) clearFirst.add(p);
            todo.add(p);
        }
        Set<BlockPos> soon = new HashSet<>(); List<BlockPos> ordered = new ArrayList<>(), waiting = new ArrayList<>(); int heldBack = 0;
        for (BlockPos p : todo) {
            if (supported(level, b, p, soon, clearFirst)) { ordered.add(p); if (!b.cells.get(p).isAir()) soon.add(p); }
            else { waiting.add(p); heldBack++; continue; }
            for (boolean more = true; more; ) { more = false; for (var it = waiting.iterator(); it.hasNext(); ) { BlockPos q = it.next(); if (supported(level, b, q, soon, clearFirst)) { it.remove(); ordered.add(q); if (!b.cells.get(q).isAir()) soon.add(q); more = true; } } }
        }
        if (!waiting.isEmpty()) ClaudeMod.LOGGER.info("[Claude build] {}: {} block(s) with nothing to go against even at the end ({}): last, doPlace finds them a face or says why (WatchBuild.place)", display(b.bp.name), waiting.size(), waiting.stream().map(WatchBuild::xyz).toList());
        ordered.addAll(waiting);
        List<Task> out = new ArrayList<>(); Set<BlockPos> done = new HashSet<>(), in = new HashSet<>(); int towers = 0, places = 0, breaks = 0, walks = 0;
        if (b.fixRounds > 0) { int n = 0; for (BlockPos p : ordered) if (g.attempts.of(Attempts.Kind.PLACE).remove(p) != null) n++; if (n > 0) ClaudeMod.LOGGER.info("[Claude build] {}: {} of the cells to fix had failed lately; planned again from a spot that sees them (WatchBuild.place)", display(b.bp.name), n); }
        BlockPos at = g.blockPosition(), tower = null; int towerH = 0; LinkedHashMap<String, List<String>> from = new LinkedHashMap<>(); // (where he stands -> the blocks he sets from there; the tower he's up, how high)
        for (BlockPos p : ordered) {
            if (done.contains(p)) continue;
            BlockState want = b.cells.get(p);
            if (want.isAir()) { out.add(new Task.Break(p)); done.add(p); breaks++; continue; }
            int h = p.getY() - b.baseY - 1;
            if (h < 1) {
                if (tower != null) { out.add(new Task.Pillar(-1)); out.add(new Task.Pickup(40)); at = tower; tower = null; } // (down off his tower for a block on the ground)
                if (!PlaceAim.directional(want)) { // where he stands for it: a face of what it goes against in sight and in reach, as the build will be by then (a stair or door picks its own spot: its click decides the state)
                    BlockPos st = standFor(level, b, p, in, at);
                    if (st == null) ClaudeMod.LOGGER.info("[Claude build] no spot on the ground sees a face to set {} at {} against, as built by then: doPlace finds one or says why (WatchBuild.place)", name(want), xyz(p));
                    else { out.add(new Task.GoTo(st, 0.5)); if (!st.equals(at)) walks++; at = st; } // (each block from its own spot: the last block's placing may have nudged him off it)
                    if (st != null) from.computeIfAbsent(xyz(st), k -> new ArrayList<>()).add(xyz(p));
                }
                if (clearFirst.contains(p)) { out.add(new Task.Break(p)); breaks++; } out.add(placeTask(p, want)); done.add(p); in.add(p); places++; continue;
            }
            boolean fromHere = tower != null && towerH == h && seesFrom(level, b, p, in, tower, h); // (his tower now: a face of what it goes against in sight from up there, as built by then)
            if (!fromHere) {
                if (tower != null) { out.add(new Task.Pillar(-1)); out.add(new Task.Pickup(40)); at = tower; tower = null; }
                List<BlockPos> next = new ArrayList<>(); // the blocks coming up at this height, in their order (till one on the ground or another height)
                for (int k = ordered.indexOf(p); k < ordered.size() && next.size() < 12; k++) { BlockPos q = ordered.get(k); if (done.contains(q)) continue; BlockState qs = b.cells.get(q); if (qs.isAir() || q.getY() - b.baseY - 1 != h) break; next.add(q); }
                BlockPos stand = towerSpot(level, b, next, h, in, at);
                if (stand == null) {
                    ClaudeMod.LOGGER.info("[Claude build] {} at {} is {} up and no spot beside it to tower up from sees a face to set it against: placed from where he can reach it (WatchBuild.place)", name(want), xyz(p), h + 1);
                    if (clearFirst.contains(p)) { out.add(new Task.Break(p)); breaks++; } out.add(placeTask(p, want)); done.add(p); in.add(p); places++; continue;
                }
                out.add(new Task.GoTo(stand, 0.5)); out.add(new Task.Pillar(h)); towers++; tower = stand; towerH = h;
            }
            if (clearFirst.contains(p)) { out.add(new Task.Break(p)); breaks++; } out.add(placeTask(p, want)); done.add(p); in.add(p); places++;
            from.computeIfAbsent(xyz(tower) + " (" + h + " up)", k -> new ArrayList<>()).add(xyz(p));
        }
        if (tower != null) { out.add(new Task.Pillar(-1)); out.add(new Task.Pickup(40)); }
        out.add(new Task.Pickup(60));
        g.tasks.addAll(out);
        b.phase = VERIFY;
        g.skillQueue.addFirst(b.step);
        ClaudeMod.LOGGER.info("[Claude build] {} at {}: {} to put in, {} to clear, {} tower(s) beside it, {} walk(s) round it {}, {} held back for something to go against (WatchBuild.place)", display(b.bp.name), xyz(b.origin), places, breaks, towers, walks, from, heldBack);
        return "building " + display(b.bp.name) + " at " + xyz(b.origin) + ": " + places + " block(s) to put in" + (breaks > 0 ? ", " + breaks + " to clear" : "") + ", in the order they built it (site " + b.site + ")";
    }
    /** As the build will stand when this block's turn comes: solid where the world is and nothing of the plan undoes it, or where an earlier block of it went in. */
    /** The other half a planned block brings with it when it goes in (a door's top, a bed's head): that cell is taken too. */
    static boolean halfOfPlaced(Build b, BlockPos q, Set<BlockPos> in) {
        BlockPos lo = q.below(); BlockState l = in.contains(lo) ? b.cells.get(lo) : null;
        if (l != null && l.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) && l.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER) return true;
        for (Direction d : Direction.Plane.HORIZONTAL) { BlockPos f = q.relative(d); BlockState fs = in.contains(f) ? b.cells.get(f) : null; if (fs != null && fs.getBlock() instanceof BedBlock && fs.getValue(BedBlock.FACING) == d.getOpposite()) return true; }
        return false;
    }
    static boolean willHold(ServerLevel level, Build b, BlockPos q, Set<BlockPos> in) {
        if (in.contains(q) || halfOfPlaced(b, q, in)) return true;
        BlockState want = b.cells.get(q);
        if (want != null) return !want.isAir() && right(level, q, want) && solidAt(level, q);
        return solidAt(level, q);
    }
    /** In the way of his click's line then: anything with a body (a door, a stair, glass too: the click stops at what it touches, as doPlace's own ray does). */
    static boolean willBlockSight(ServerLevel level, Build b, BlockPos q, Set<BlockPos> in) {
        BlockState want = b.cells.get(q);
        if (in.contains(q)) return want != null && !want.getCollisionShape(level, q).isEmpty();
        if (halfOfPlaced(b, q, in)) return true;
        if (want != null) return right(level, q, want) && solidAt(level, q);
        return solidAt(level, q);
    }
    /** His click's line to that spot meets the block he's clicking first, as built by then: the same test doPlace makes (aimed just inside the block, the first thing with a body on the way must be it). */
    static boolean sightThen(ServerLevel level, Build b, Vec3 eye, Vec3 to, BlockPos target, BlockPos onto, Set<BlockPos> in) {
        BlockPos from = BlockPos.containing(eye); Vec3 d = to.subtract(eye); int n = Math.max(2, (int) Math.ceil(d.length() / 0.05));
        for (int i = 1; i <= n; i++) {
            BlockPos q = BlockPos.containing(eye.add(d.scale(i / (double) n)));
            if (q.equals(onto)) return true;
            if (q.equals(from) || q.equals(target)) continue;
            if (willBlockSight(level, b, q, in)) return false;
        }
        return false;
    }
    /** The ground spot nearest where he'll be that sees a face of something it goes against (as built by then), in his reach; never a cell the plan fills. */
    static BlockPos standFor(ServerLevel level, Build b, BlockPos p, Set<BlockPos> in, BlockPos from) {
        int x0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        for (BlockPos c : b.cells.keySet()) { x0 = Math.min(x0, c.getX()); z0 = Math.min(z0, c.getZ()); x1 = Math.max(x1, c.getX()); z1 = Math.max(z1, c.getZ()); }
        final int ax = x0, az = z0, bx = x1, bz = z1; // (its outline: inside it is inside its walls, an open-topped tower's middle too)
        List<BlockPos> cands = new ArrayList<>();
        for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) for (int dy = -1; dy <= 1; dy++) {
            BlockPos sp = new BlockPos(p.getX() + dx, b.baseY + dy, p.getZ() + dz);
            if (sp.equals(p) || sp.above().equals(p) || in.contains(sp) || in.contains(sp.above()) || halfOfPlaced(b, sp, in) || halfOfPlaced(b, sp.above(), in)) continue;
            if (!willHold(level, b, sp.below(), in) || solidAt(level, sp) || solidAt(level, sp.above())) continue; // (feet and head clear, ground under him then)
            BlockState f = b.cells.get(sp), hd = b.cells.get(sp.above());
            if (f != null && !f.isAir() || hd != null && !hd.isAir()) continue; // (in a cell the plan fills he'd be in its way)
            cands.add(sp);
        }
        Vec3 home = Vec3.atBottomCenterOf(from);
        cands.sort(Comparator.comparingDouble(sp -> Vec3.atBottomCenterOf(sp).distanceTo(home) + (sp.getX() >= ax && sp.getX() <= bx && sp.getZ() >= az && sp.getZ() <= bz ? 12 : 0))); // (from outside, as a player builds: inside its walls only if nothing outside will do, he'd wall himself in)
        for (BlockPos sp : cands) {
            boolean all = true; // from anywhere in that cell (the walk ends within half a block of its middle): a face in sight
            for (double[] off : new double[][] {{0, 0}, {0.25, 0.25}, {-0.25, 0.25}, {0.25, -0.25}, {-0.25, -0.25}}) if (!seesASupport(level, b, p, in, Vec3.atBottomCenterOf(sp).add(off[0], 1.62, off[1]))) { all = false; break; }
            if (all) return sp;
        }
        return null;
    }
    static boolean seesASupport(ServerLevel level, Build b, BlockPos p, Set<BlockPos> in, Vec3 eye) {
        {
            if (eye.distanceTo(Vec3.atCenterOf(p)) > 4.2) return false; // (doPlace's own reach to the cell)
            for (Direction d : Direction.values()) {
                BlockPos nb = p.relative(d); if (!willHold(level, b, nb, in)) continue;
                Direction face = d.getOpposite(); Vec3 c = Vec3.atCenterOf(nb).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
                Vec3 u = face.getAxis() == Direction.Axis.X ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0), v = face.getAxis() == Direction.Axis.Z ? new Vec3(0, 1, 0) : new Vec3(0, 0, 1);
                for (double[] o : new double[][] {{0, 0}, {0.4, 0}, {-0.4, 0}, {0, 0.4}, {0, -0.4}, {0.4, 0.4}, {0.4, -0.4}, {-0.4, 0.4}, {-0.4, -0.4}}) { // (doPlace's own spots on the face)
                    Vec3 pt = c.add(u.scale(o[0])).add(v.scale(o[1]));
                    if (eye.distanceTo(pt) > 4.5) continue;
                    if (pt.subtract(eye).dot(new Vec3(face.getStepX(), face.getStepY(), face.getStepZ())) >= 0) continue; // (the face turned away from him)
                    if (sightThen(level, b, eye, pt.add(d.getStepX() * 0.45, d.getStepY() * 0.45, d.getStepZ() * 0.45), p, nb, in)) return true;
                }
            }
        }
        return false;
    }
    static Task.Place placeTask(BlockPos p, BlockState want) { return new Task.Place(p, want.getBlock().asItem(), null, want); }

    static boolean supported(ServerLevel level, Build b, BlockPos p, Set<BlockPos> soon, Set<BlockPos> clearFirst) {
        BlockState want = b.cells.get(p);
        if (want.isAir()) return true;
        java.util.function.Predicate<BlockPos> holds = q -> soon.contains(q) || solidAt(level, q) && !clearFirst.contains(q) && !(b.cells.containsKey(q) && b.cells.get(q).isAir());
        if (needsFloor(want)) return holds.test(p.below());
        for (Direction d : Direction.values()) if (holds.test(p.relative(d))) return true;
        return false;
    }
    /** From the top of an h-block tower at that spot: a face of what it goes against in sight and reach, as built by then (from anywhere he'd stand up there). */
    static boolean seesFrom(ServerLevel level, Build b, BlockPos p, Set<BlockPos> in, BlockPos tower, int h) {
        for (double[] off : new double[][] {{0, 0}, {0.25, 0.25}, {-0.25, 0.25}, {0.25, -0.25}, {-0.25, -0.25}}) if (!seesASupport(level, b, p, in, Vec3.atBottomCenterOf(tower).add(off[0], h + 1.62, off[1]))) return false;
        return true;
    }
    /** A spot on the ground beside it to tower up from: room overhead, from up there a face of what it goes against in sight (as built by then); outside its outline first, the nearest to where he is. */
    static BlockPos towerSpot(ServerLevel level, Build b, List<BlockPos> next, int h, Set<BlockPos> in, BlockPos from) {
        BlockPos p = next.get(0);
        int x0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        for (BlockPos c : b.cells.keySet()) { x0 = Math.min(x0, c.getX()); z0 = Math.min(z0, c.getZ()); x1 = Math.max(x1, c.getX()); z1 = Math.max(z1, c.getZ()); }
        List<BlockPos> cands = new ArrayList<>();
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
            BlockPos sp = new BlockPos(p.getX() + dx, b.baseY, p.getZ() + dz);
            if (b.cells.containsKey(sp) || in.contains(sp) || !willHold(level, b, sp.below(), in) || solidAt(level, sp) || solidAt(level, sp.above())) continue;
            boolean room = true; for (int k = 1; k <= h + 2; k++) { BlockPos q = sp.above(k); if (solidAt(level, q) || in.contains(q) || b.cells.containsKey(q) && !b.cells.get(q).isAir()) room = false; }
            if (room) cands.add(sp);
        }
        final int ax = x0, az = z0, bx = x1, bz = z1; Vec3 home = Vec3.atBottomCenterOf(from);
        cands.sort(Comparator.comparingDouble(sp -> Vec3.atBottomCenterOf(sp).distanceTo(home) + (sp.getX() >= ax && sp.getX() <= bx && sp.getZ() >= az && sp.getZ() <= bz ? 12 : 0)));
        BlockPos best = null; int run = 0; // the spot that sees the longest run of them in turn, each as built by then (fewest times up and down); the nearer on a tie
        for (BlockPos sp : cands) {
            if (!seesFrom(level, b, p, in, sp, h)) continue;
            Set<BlockPos> then = new HashSet<>(in); then.add(p); int n = 1;
            for (int k = 1; k < next.size() && seesFrom(level, b, next.get(k), then, sp, h); k++) { then.add(next.get(k)); n++; }
            if (n > run) { run = n; best = sp; }
            if (run == next.size()) break;
        }
        return best;
    }

    /** Block by block against the plan: done, or the fixes queued (materials first). */
    static String verify(ClaudeEntity g, ServerLevel level, Build b, ServerPlayer player) {
        List<String> off = new ArrayList<>(); int wrong = 0;
        for (var e : b.cells.entrySet()) {
            if (right(level, e.getKey(), e.getValue())) continue;
            wrong++;
            if (off.size() < 4) { Integer f = g.attempts.of(Attempts.Kind.PLACE).get(e.getKey()); off.add(xyz(e.getKey()) + ": " + (level.getBlockState(e.getKey()).isAir() ? "empty" : name(level.getBlockState(e.getKey())) + " " + props(level.getBlockState(e.getKey()))) + " for " + (e.getValue().isAir() ? "air" : name(e.getValue()) + " " + props(e.getValue())) + (f != null ? " (placing failed there " + (g.tickCount - f) / 20 + "s ago)" : "")); }
        }
        int n = b.bp.blocks();
        if (wrong == 0) {
            BUILDS.remove(g.getUUID()); BUILT.computeIfAbsent(g.getUUID(), k -> new HashSet<>()).addAll(b.cells.keySet());
            String line = "done: built " + display(b.bp.name) + " (" + n + " blocks, " + b.fixed + " fixed)";
            ClaudeMod.LOGGER.info("[Claude build] {} at {}: every block checked against the plan and right, {} s from the ask (WatchBuild.verify)", display(b.bp.name), xyz(b.origin), (g.tickCount - b.startTick) / 20);
            lastLine = line; com.claudemod.director.ClaudeChat.say(player, line, "build: done");
            return line;
        }
        if (b.fixRounds >= 2) {
            BUILDS.remove(g.getUUID()); BUILT.computeIfAbsent(g.getUUID(), k -> new HashSet<>()).addAll(b.cells.keySet());
            ClaudeMod.LOGGER.info("[Claude build] {}: after two rounds of fixes {} block(s) still off: {} (WatchBuild.verify)", display(b.bp.name), wrong, off);
            return "failed: built " + display(b.bp.name) + " but " + wrong + " block(s) still aren't right: " + String.join("; ", off);
        }
        b.fixRounds++; b.fixed += wrong; b.phase = GATHER; b.gathers = 0;
        g.skillQueue.addFirst(b.step);
        ClaudeMod.LOGGER.info("[Claude build] checked {} block by block: {} off ({}): fixing them (WatchBuild.verify)", display(b.bp.name), wrong, off);
        return "checked " + display(b.bp.name) + " block by block: " + wrong + " off (" + String.join("; ", off) + "), fixing them";
    }
    static String props(BlockState s) {
        StringBuilder sb = new StringBuilder();
        for (var p : s.getProperties()) if (PlaceAim.CLICK.contains(p)) sb.append(sb.length() == 0 ? "[" : ",").append(p.getName()).append("=").append(s.getValue(p));
        return sb.length() == 0 ? "" : sb.append("]").toString();
    }

    // ================================================================ his account

    /** For his situation text: his learned builds by name and size, and what he's watching or building now. */
    public static String describe(ClaudeEntity g) {
        StringBuilder sb = new StringBuilder();
        var lib = library(g);
        if (!lib.isEmpty()) {
            sb.append("LEARNED BUILDS (copied from watching them, kept outside the world so every world has them; build_blueprint <name> builds one again, \"facing <dir>\" turns it): ");
            List<String> l = new ArrayList<>(); for (LearnedBlueprint bp : lib.values()) l.add(bp.name + " (" + bp.summary() + ", by " + bp.by + ")");
            sb.append(String.join("; ", l)).append(".\n");
        }
        Session s = SESSIONS.get(g.getUUID());
        if (s != null) sb.append("WATCHING: ").append(s.userName).append(" building ").append(display(s.name)).append(": ").append(s.learned.size()).append(" block(s) seen go in, ").append(s.missed).append(" heard out of your sight").append(s.lookingOver ? "; they said done and you're looking it over" : "; you stand by till they say done").append(".\n");
        Build b = BUILDS.get(g.getUUID());
        if (b != null) { int in = 0; for (var e : b.cells.entrySet()) if (right(g.serverLevel(), e.getKey(), e.getValue())) in++; sb.append("BUILDING: ").append(display(b.bp.name)).append(" at ").append(xyz(b.origin)).append(", front to the ").append(b.front.getName()).append(": ").append(in).append(" of ").append(b.cells.size()).append(" cells right so far.\n"); }
        return sb.toString();
    }

    // ================================================================ tests

    /** The world's side forgotten (a new world): sessions, builds in progress and finished, the cache. What's on disk stays. */
    public static void forgetWorldSide(ClaudeEntity g) { SESSIONS.remove(g.getUUID()); BUILDS.remove(g.getUUID()); BUILT.remove(g.getUUID()); LIB.remove(g.getUUID()); LAST.remove(g.getUUID()); lastLine = ""; }
    /** Everything, his saved blueprints too (a clean slate for the tests). */
    public static void reset(ClaudeEntity g) {
        for (String n : LearnedStore.list(bot(g), LearnedStore.Kind.BLUEPRINT)) LearnedStore.delete(bot(g), LearnedStore.Kind.BLUEPRINT, n);
        forgetWorldSide(g);
    }
}
