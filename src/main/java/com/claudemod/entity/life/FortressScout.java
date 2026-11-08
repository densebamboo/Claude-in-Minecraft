package com.claudemod.entity.life;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.claudemod.ClaudeMod;
import com.claudemod.entity.ClaudeEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Scouting the Nether like an expert (patch_nether_scout.py; live 2026-10-30 16:24-16:30, his first Nether trip: "find a
 * fortress" got "which way for the fortress, you got a direction?", and explore climbed toward the bedrock roof).
 *
 * What he knows (Java 1.21): fortresses and bastions are one structure set, nether_complexes: a random spread over 27-chunk
 * (432-block) regions, separation 4. Each region holds exactly one of the two (a fortress 2 times in 5, a bastion 3 in 5), its
 * start chunk somewhere in the region's first 23x23 chunks, so its likeliest spot is about 184 blocks into the region on each
 * axis. (The old "fortresses run in strips along the z axis" is pre-1.16 lore.) From his own coordinates he works out which
 * region he's in and where its one complex most likely sits, heads there along an axis, and once a region is scouted (or its
 * complex turned out to be a bastion) goes on to the nearest region not yet scouted. The ground is walked in legs of about 64
 * at his own height, each ending on the highest open ground near it; at each end he turns all the way round (1.5 s), each stretch seen as it comes into his view.
 * Each leg is noted (from, to, what he saw), so the same ground is never scouted twice.
 *
 * Sight only: a fortress counts once its nether brick was in his view (his structure sightings, and the long-range landmarks of
 * patch_far_sight.py once that lands: {@link #landmarkBrick}); blazes and spawners he has seen or heard pull the next leg toward
 * them. Nothing is read through rock. The floors his legs end on are walk targets, chosen the way explore always chose them.
 */
public final class FortressScout {
    private FortressScout() {}

    /** Above this the Nether is the roof's underside (bedrock from y 123): no walk or scout target goes there. */
    public static final int MAX_Y = 110;
    public static final int LEG = 64;
    static final int REGION = 27 * 16, MIDDLE = 23 * 16 / 2; // a nether_complexes region in blocks; the middle of its possible start chunks
    static final int WALK = 1, LOOK = 2;

    /** One leg he scouted: heading, from, to, what he saw from its end. */
    public record Leg(int n, Direction dir, BlockPos from, BlockPos to, String saw) {}
    static final class State {
        int phase; Direction dir, said; String why = ""; BlockPos legFrom, legTo; int legTick;
        final List<Leg> legs = new ArrayList<>(); final Set<Long> regionsDone = new HashSet<>(); final Set<String> seenBefore = new HashSet<>(); final List<BlockPos> blocked = new ArrayList<>(); // (leg ends his walk couldn't reach: not tried again)
        BlockPos found; String foundHow = "";
        Vec3 heardAt; int heardTick = -100000; String heardWhat; // the last blaze or wither skeleton he heard (through rock too: only a fortress has them)
    }
    /** From his ears (Perception.heard): a blaze's or a wither skeleton's sound, placed by its estimate. */
    public static void heard(ClaudeEntity g, Vec3 est, net.minecraft.world.entity.EntityType<?> type, double d) {
        if (type != net.minecraft.world.entity.EntityType.BLAZE && type != net.minecraft.world.entity.EntityType.WITHER_SKELETON) return;
        State s = state(g); boolean news = g.tickCount - s.heardTick > 20 * 30;
        s.heardAt = est; s.heardTick = g.tickCount; s.heardWhat = type.getDescription().getString().toLowerCase();
        if (news) ClaudeMod.LOGGER.info("[Claude scout] heard a {} {} blocks off, about {}: blazes and wither skeletons are a fortress's (FortressScout.heard)", s.heardWhat, (int) d, BlockPos.containing(est).toShortString());
    }
    private static Map<UUID, State> states; // (static: fields added live start null)
    static State state(ClaudeEntity g) { if (states == null) states = new HashMap<>(); return states.computeIfAbsent(g.getUUID(), k -> new State()); }
    public static void reset(ClaudeEntity g) { if (states != null) states.remove(g.getUUID()); }
    public static List<Leg> legs(ClaudeEntity g) { return state(g).legs; }
    public static BlockPos found(ClaudeEntity g) { // (this run's, else the one on his atlas: World 15, seen 3 times in a day and scouted for again after each restart)
        BlockPos f = state(g).found; if (g.level() instanceof ServerLevel sl) { BlockPos a0 = Atlas.find(g, sl, "fortress"); if (a0 != null && (f == null || a0.distSqr(g.blockPosition()) < f.distSqr(g.blockPosition()))) f = a0; } // (the nearest he knows: World 15 23:55 one 94 off and him bound for one 200 off)
        if (f != null && g.level() instanceof ServerLevel sl2 && sl2.isLoaded(f)) { BlockPos deck = deck(sl2, f); if (deck != null) return deck; } // (its deck, never the brick seen from below)
        return f;
    }
    /** Where a fortress seen at p is walked: up the brick column from p (a pillar seen from the lava sea climbs to its bridge; live 14:19 World 17, his atlas said y 33 and the highway went down to the lava), then the deck cell nearest there; null if none. */
    public static BlockPos deck(ServerLevel level, BlockPos p) {
        BlockPos top = p;
        for (int k = 0; k < 96 && level.isLoaded(top.above()) && fortressBlock(level.getBlockState(top.above())); k++) top = top.above();
        return FortressFight.deckCell(level, top);
    }
    public static boolean fortressBlock(net.minecraft.world.level.block.state.BlockState s) { return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath().contains("nether_brick"); }
    /** On the fortress: a fortress block (nether bricks, its fence, its stairs) under his feet or the one below that. */
    // ---- on the fortress: along its deck to the parts he hasn't walked (Day to Day 21:26 World 15: "find the spawner room" sent him off the fortress, 71 blocks over the netherrack) ----
    private static java.util.Map<java.util.UUID, java.util.Set<Long>> deckSeen;
    static java.util.Set<Long> deckSeen(ClaudeEntity g) { if (deckSeen == null) deckSeen = new java.util.HashMap<>(); return deckSeen.computeIfAbsent(g.getUUID(), k -> new java.util.HashSet<>()); }
    static long deckKey(BlockPos p) { return BlockPos.asLong(p.getX() >> 2, p.getY() >> 2, p.getZ() >> 2); }
    public static void deckReset(ClaudeEntity g) { if (deckSeen != null) deckSeen.remove(g.getUUID()); }
    static boolean fortressFloor(net.minecraft.world.level.block.state.BlockState s) {
        return s.is(net.minecraft.world.level.block.Blocks.NETHER_BRICKS) || s.is(net.minecraft.world.level.block.Blocks.NETHER_BRICK_STAIRS) || s.is(net.minecraft.world.level.block.Blocks.NETHER_BRICK_SLAB) || s.is(net.minecraft.world.level.block.Blocks.SOUL_SAND);
    }
    /** Where he stands on the fortress, the 4x4 patch counted as walked (every 2 s, Atlas.tick). */
    public static void noteDeck(ClaudeEntity g) { if (onFortress(g)) deckSeen(g).add(deckKey(g.blockPosition())); }
    /** The far end of the deck he hasn't walked: the unwalked cell furthest along the bricks he can walk from here (64 at most), or null. */
    public static BlockPos deckStep(ClaudeEntity g, ServerLevel level) {
        BlockPos start = g.blockPosition(); java.util.Set<Long> seen = deckSeen(g); seen.add(deckKey(start));
        java.util.Map<BlockPos, Integer> dist = new java.util.HashMap<>(); java.util.ArrayDeque<BlockPos> q = new java.util.ArrayDeque<>();
        dist.put(start, 0); q.add(start); BlockPos best = null; int bd = -1;
        while (!q.isEmpty() && dist.size() < 4000) {
            BlockPos f = q.poll(); int d0 = dist.get(f);
            if (d0 > 64) continue;
            if (d0 >= 6 && !seen.contains(deckKey(f)) && d0 > bd) { bd = d0; best = f; }
            for (Direction dir : Direction.Plane.HORIZONTAL) for (int dy = -1; dy <= 1; dy++) {
                BlockPos n = f.relative(dir).above(dy); if (dist.containsKey(n) || !level.isLoaded(n)) continue;
                if (!level.getBlockState(n).getCollisionShape(level, n).isEmpty() || !level.getBlockState(n.above()).getCollisionShape(level, n.above()).isEmpty()) continue;
                if (dy == 1 && !level.getBlockState(f.above(2)).getCollisionShape(level, f.above(2)).isEmpty()) continue;
                if (!fortressFloor(level.getBlockState(n.below())) || !level.getFluidState(n).isEmpty()) continue;
                dist.put(n.immutable(), d0 + 1); q.add(n.immutable());
            }
        }
        if (best != null) ClaudeMod.LOGGER.info("[Claude scout] on the fortress at {}: along its deck to {} ({} steps, not walked yet; {} cells of deck in reach) (FortressScout.deckStep)", start.toShortString(), best.toShortString(), bd, dist.size());
        return best;
    }

    public static boolean onFortress(ClaudeEntity g) {
        net.minecraft.core.BlockPos f = net.minecraft.core.BlockPos.containing(g.getX(), g.getY() - 0.2, g.getZ());
        for (net.minecraft.core.BlockPos q : new net.minecraft.core.BlockPos[] {f, f.below()}) { var s = g.level().getBlockState(q); if (s.is(net.minecraft.world.level.block.Blocks.NETHER_BRICKS) || s.is(net.minecraft.world.level.block.Blocks.NETHER_BRICK_FENCE) || s.is(net.minecraft.world.level.block.Blocks.NETHER_BRICK_STAIRS) || s.is(net.minecraft.world.level.block.Blocks.NETHER_BRICK_SLAB)) return true; }
        return false;
    }
    /** (tests) */
    public static void foundTest(ClaudeEntity g, BlockPos p) { state(g).found = p == null ? null : p.immutable(); }
    public static int phase(ClaudeEntity g) { return state(g).phase; }
    public static Direction heading(ClaudeEntity g) { return state(g).dir; }
    public static BlockPos legTo(ClaudeEntity g) { return state(g).legTo; }

    // ---- the ground: where a walk in the Nether can end ----

    /** A cell he can stand in: open feet and head, no fluid, nothing burning, a sturdy top under him (no magma, no bedrock). */
    public static boolean stand(ServerLevel level, BlockPos p) {
        var feet = level.getBlockState(p); var head = level.getBlockState(p.above()); BlockPos u = p.below(); var under = level.getBlockState(u);
        if (!feet.getCollisionShape(level, p).isEmpty() || !head.getCollisionShape(level, p.above()).isEmpty()) return false;
        if (!level.getFluidState(p).isEmpty() || !level.getFluidState(p.above()).isEmpty() || !level.getFluidState(u).isEmpty()) return false;
        if (feet.is(BlockTags.FIRE) || under.is(Blocks.MAGMA_BLOCK) || under.is(Blocks.BEDROCK)) return false;
        if (under.is(BlockTags.WART_BLOCKS) || under.is(BlockTags.LOGS) || under.is(BlockTags.LEAVES) || under.is(Blocks.SHROOMLIGHT)) return false; // ponytail: a fungus or tree top is no ground to head for (live 20:1x: in the warped forest the canopy was what he saw, he pillared up to it again and again)
        return under.isFaceSturdy(level, u, Direction.UP);
    }
    /** Over (x, z): the cell he can stand in nearest his own height (16 up to 24 down), never above MAX_Y; null over lava or solid rock. */
    public static BlockPos standAt(ServerLevel level, int x, int z, int nearY) {
        if (!level.hasChunkAt(new BlockPos(x, nearY, z))) return null;
        BlockPos best = null;
        for (int y = Math.min(MAX_Y, nearY + 16); y >= Math.max(level.getMinBuildHeight() + 5, nearY - 24); y--) {
            BlockPos p = new BlockPos(x, y, z);
            if (stand(level, p) && (best == null || Math.abs(y - nearY) < Math.abs(best.getY() - nearY))) best = p;
        }
        return best;
    }
    /** Ground about d out along yaw a (x = cos, z = sin): the far shore first when it's lava there (his blocks bridge it), then nearer. */
    public static BlockPos alongRay(ClaudeEntity g, ServerLevel level, double a, int d) {
        for (int k : new int[] {d, d + 8, d + 16, d + 24, d + 32, d - 8, d - 16, d - 24, d - 32}) {
            if (k < Math.min(12, d)) continue;
            BlockPos p = standAt(level, g.getBlockX() + (int) Math.round(Math.cos(a) * k), g.getBlockZ() + (int) Math.round(Math.sin(a) * k), g.getBlockY());
            if (p != null && HuntPatrol.book(g, level).walked.get(HuntPatrol.cell(p.getX(), p.getZ())) instanceof Long t && HuntPatrol.now(g) - t < 20 * 120 && HuntPatrol.now(g) >= t) continue; // ponytail: ground he walked in the last 2 min is no new ground (live 21:22, inside his own dug tunnel both ends were all he saw: patrol and explore flipped between them; now nothing's in sight, and explore's nook branch digs out)
            if (p != null && seen(g, level, p)) return p; // (only ground he can see: a pocket behind the rock is no target, live 2026-11-01 he dug to openings out of sight)
        }
        return null;
    }
    /** His eyes reach the spot's feet or head cell with nothing solid between (fluids see-through: the far shore of a lava lake counts). */
    static boolean seen(ClaudeEntity g, ServerLevel level, BlockPos p) {
        for (BlockPos c : new BlockPos[] {p, p.above()})
            if (level.clip(new net.minecraft.world.level.ClipContext(g.getEyePosition(), Vec3.atCenterOf(c), net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, g)).getType() == net.minecraft.world.phys.HitResult.Type.MISS) return true;
        return false;
    }
    /** explore in the Nether: ground about 45 out his way (a little to either side, then across, then back), never the roof. */
    public static BlockPos exploreSpot(ClaudeEntity g, ServerLevel level, double a) {
        for (double turn : new double[] {0, 0.5, -0.5, Math.PI / 2, -Math.PI / 2, Math.PI}) { BlockPos p = alongRay(g, level, a + turn, 45); if (p != null) return p; }
        for (double turn : new double[] {0, Math.PI / 2, -Math.PI / 2, Math.PI}) { BlockPos p = alongRay(g, level, a + turn, 6); if (p != null) return p; } // ponytail: live 13:13, a warped forest's stems hid everything 12+ out: "no ground any way" on open ground; short legs then
        return null;
    }
    /** A leg's end: the ray's ground, then the highest open ground within 6 to either side of it (looking out over the open beats a hollow). */
    static BlockPos legEnd(ClaudeEntity g, ServerLevel level, Direction dir, int len) {
        double a = Math.atan2(dir.getStepZ(), dir.getStepX());
        BlockPos base = alongRay(g, level, a, len);
        if (base == null) return null;
        Direction side = dir.getClockWise(); BlockPos best = base; int bestScore = score(level, base, dir);
        for (int off : new int[] {-6, -3, 3, 6}) {
            BlockPos q = standAt(level, base.getX() + side.getStepX() * off, base.getZ() + side.getStepZ() * off, base.getY());
            if (q == null || q.getY() > g.getBlockY() + 8) continue;
            int sc = score(level, q, dir); if (sc > bestScore) { best = q; bestScore = sc; }
        }
        return best;
    }
    /** High and open ahead: its height, and the open cells at eye level in front of it (12 out). */
    static int score(ServerLevel level, BlockPos p, Direction dir) {
        int open = 0; for (int k = 1; k <= 12; k++) { BlockPos q = p.above().relative(dir, k); if (level.getBlockState(q).getCollisionShape(level, q).isEmpty() && level.getFluidState(q).isEmpty()) open++; else break; }
        return p.getY() * 2 + open;
    }

    // ---- the Nether's bridge and wall blocks ----

    /** The user (16:30): "preferrably not netherack. its not ghast proof. cobble is better": in the Nether a bridge or wall is cobblestone
     *  or cobbled deepslate (blast resistance 6), else another stone as tough, while he has any; EMPTY when he has none. */
    public static ItemStack ghastProof(ClaudeEntity g) {
        ItemStack s = g.inventory.find(x -> x.is(Items.COBBLESTONE) || x.is(Items.COBBLED_DEEPSLATE));
        if (!s.isEmpty()) return s;
        return g.inventory.find(x -> x.is(Items.STONE) || x.is(Items.DEEPSLATE) || x.is(Items.BLACKSTONE) || x.is(Items.ANDESITE) || x.is(Items.DIORITE) || x.is(Items.GRANITE) || x.is(Items.TUFF)
                || x.is(Items.STONE_BRICKS) || x.is(Items.POLISHED_BLACKSTONE) || x.is(Items.POLISHED_BLACKSTONE_BRICKS) || x.is(Items.NETHER_BRICKS));
    }

    // ---- what he has seen ----

    /** Long-range landmark sight (patch_far_sight.py: Perception.landmarks(g) / nearestLandmark(g, kind)) until it is merged: an adapter
     *  that finds it by name, so this file compiles with or without it; once it lands a direct call replaces this. Null: none seen. */
    static BlockPos landmarkBrick(ClaudeEntity g) {
        java.lang.reflect.Method m;
        try { m = Perception.class.getMethod("nearestLandmark", ClaudeEntity.class, String.class); } catch (NoSuchMethodException e) { return null; }
        for (String kind : new String[] {"nether_bricks", "nether_brick", "fortress"}) {
            try { BlockPos p = asPos(m.invoke(null, g, kind)); if (p != null) return p; } catch (ReflectiveOperationException | RuntimeException e) { return null; }
        }
        return null;
    }
    private static BlockPos asPos(Object o) throws ReflectiveOperationException {
        if (o instanceof java.util.Optional<?> op) o = op.orElse(null);
        if (o == null) return null;
        if (o instanceof BlockPos p) return p;
        if (o instanceof Vec3 v) return BlockPos.containing(v);
        for (String acc : new String[] {"pos", "at", "where", "block"}) {
            try { Object r = o.getClass().getMethod(acc).invoke(o); if (r instanceof BlockPos p) return p; if (r instanceof Vec3 v) return BlockPos.containing(v); } catch (NoSuchMethodException e) { /* the next name */ }
        }
        return null;
    }
    /** The nether fortress piece he has seen nearest him (one of its blocks was in his view), or null. */
    static Perception.Sighting fortressSighting(ClaudeEntity g, ServerLevel level) {
        Perception.Sighting best = null; double bd = Double.MAX_VALUE;
        for (var s : Perception.sightings(g)) {
            if (s.dim() != level.dimension() || !"fortress".equals(com.claudemod.director.Structures.key(s.id()))) continue;
            double d = s.saw().distSqr(g.blockPosition()); if (d < bd) { bd = d; best = s; }
        }
        return best;
    }
    static String key(Perception.Sighting s) { return s.id() + " " + s.box(); }

    // ---- the skill ----

    static long region(int rx, int rz) { return ((long) rx << 32) ^ (rz & 0xffffffffL); }
    static int regionOf(int block) { return Math.floorDiv(Math.floorDiv(block, 16), 27); }
    static BlockPos middle(int rx, int rz, int y) { return new BlockPos(rx * REGION + MIDDLE, y, rz * REGION + MIDDLE); }
    static boolean regionDone(State s, int rx, int rz) {
        if (s.regionsDone.contains(region(rx, rz))) return true;
        BlockPos m = middle(rx, rz, 0);
        for (Leg l : s.legs) { int dx = l.to().getX() - m.getX(), dz = l.to().getZ() - m.getZ(); if (dx * dx + dz * dz <= 64 * 64) return true; }
        return false;
    }
    /** That leg end is ground scouted already: by him now, or within 32 of a leg's end or start before. */
    static boolean scouted(State s, BlockPos to, BlockPos me) {
        if (horiz(to, me) < 16) return true;
        for (BlockPos b : s.blocked) if (horiz(b, to) < 16) return true;
        for (Leg l : s.legs) if (horiz(l.to(), to) < 32 || horiz(l.from(), to) < 32) return true;
        return false;
    }
    static double horiz(BlockPos a, BlockPos b) { double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ(); return Math.sqrt(dx * dx + dz * dz); }
    static String dirName(Direction d) { return d == null ? "?" : d.getName(); }

    /** find_fortress: the next leg, a leg's end, or done once he has seen one. Re-queued as "@n.." between its phases. */
    public static String skill(ClaudeEntity g, ServerPlayer player, ServerLevel level, Skills.Step st) {
        State s = state(g);
        if (level.dimension() != net.minecraft.world.level.Level.NETHER) return "failed: fortresses are only in the Nether: enter_portal first";
        if (!st.target().startsWith("@")) { s.phase = 0; s.legTo = null; s.legFrom = null; } // a fresh ask: his notes on the ground scouted stay, a leg something else cut off doesn't
        var f = fortressSighting(g, level); BlockPos far = f == null ? landmarkBrick(g) : null;
        if (s.phase == LOOK || s.phase == WALK && (f != null || far != null)) finishLeg(g, level, s); // (seen on the way, before the leg's end: the leg is still noted, with the fortress in it; it read "after 0 leg(s)" with the bricks in view from the leg's last steps)
        if (f == null && far == null) { // a fortress named with its coords in his goal or their order ("the nether fortress at 185 64 -14"): on his atlas (World 15 23:52: scouting south for one the goal put east)
            for (String w0 : new String[] {st.target(), g.playerGoal, g.goal}) { if (w0 == null) continue; var m0 = java.util.regex.Pattern.compile("fortress[^0-9-]{0,24}(-?\\d+)[ ,]+(-?\\d+)[ ,]+(-?\\d+)").matcher(w0.toLowerCase());
                if (m0.find()) { BlockPos named = new BlockPos(Integer.parseInt(m0.group(1)), Integer.parseInt(m0.group(2)), Integer.parseInt(m0.group(3))); if (Atlas.find(g, level, "fortress") == null || Atlas.find(g, level, "fortress").distSqr(named) > 48 * 48) { Atlas.note(g, level, "fortress", named); ClaudeMod.LOGGER.info("[Claude scout] a nether fortress at {} named in \"{}\": on his atlas (FortressScout.skill)", named.toShortString(), w0); } break; } } }
        if (f == null && far == null) { BlockPos rec = Atlas.find(g, level, "fortress"); if (rec != null) { s.found = rec; int d0 = (int) Math.sqrt(rec.distSqr(g.blockPosition())); ClaudeMod.LOGGER.info("[Claude scout] a nether fortress on his atlas at {} ({} off): no scouting for it (FortressScout.skill)", rec.toShortString(), d0); return "done: found a nether fortress: the one on my atlas at " + rec.toShortString() + ", " + d0 + " blocks off; blazes spawn at its spawners and in its halls"; } }
        if (f != null || far != null) {
            BlockPos at = f != null ? f.saw() : far;
            s.found = at.immutable(); s.phase = 0; Atlas.note(g, level, "fortress", at); // (on his atlas: kept across a restart)
            int d = (int) Math.sqrt(at.distSqr(g.blockPosition()));
            String where = Perception.compassOf(Vec3.atCenterOf(at).subtract(g.position()));
            s.foundHow = (f != null && com.claudemod.director.Structures.signOf(f.id(), level.getBlockState(f.saw())) ? level.getBlockState(f.saw()).getBlock().getName().getString() : "nether brick") + " at " + at.toShortString() + ", " + d + " blocks " + where;
            ClaudeMod.LOGGER.info("[Claude scout] a nether fortress: {} after {} leg(s) (FortressScout.skill)", s.foundHow, s.legs.size());
            g.noteDoing("found a nether fortress: " + s.foundHow);
            return "done: found a nether fortress: saw its " + s.foundHow + " (after " + s.legs.size() + " leg(s) of scouting); blazes spawn at its spawners and in its halls";
        }
        if (s.phase == WALK) { // the leg's walk is over, there or cut short: a look all the way round from where he stands
            boolean there = s.legTo != null && horiz(g.blockPosition(), s.legTo) <= 6;
            Perception.lookRound(g, null); g.tasks.add(new Task.Wait(40, false)); // the real turn, 1.5 s: each stretch of the way round is scanned as it comes into his view (Perception.tick), never an all-round look at once
            s.phase = LOOK; g.skillQueue.addFirst(new Skills.Step("find_fortress", "@" + (s.legs.size() + 1) + "l", st.count(), st.where()));
            String r = "end of leg " + (s.legs.size() + 1) + (there || s.legTo == null ? "" : " (cut short at " + g.blockPosition().toShortString() + ", " + (int) horiz(g.blockPosition(), s.legTo) + " short of " + s.legTo.toShortString() + ")") + ": looking all the way round over the open for nether brick";
            ClaudeMod.LOGGER.info("[Claude scout] {} (FortressScout.skill)", r);
            return r;
        }
        String[] why = new String[1]; Direction[] dir = new Direction[1];
        BlockPos to = plan(g, level, s, why, dir);
        if (to == null) { // truly out of options: only then a question
            String q = "i've scouted every way i can get to from here (" + s.legs.size() + " legs) and there's no ground on: which way should i look for the fortress?";
            com.claudemod.director.ClaudeChat.say(player, q, "find_fortress: out of options");
            ClaudeMod.LOGGER.info("[Claude scout] out of options at {} after {} legs: asked them (FortressScout.skill)", g.blockPosition().toShortString(), s.legs.size());
            return "failed: no leg left to scout from " + g.blockPosition().toShortString() + " (every way scouted, lava too wide or rock): asked them which way";
        }
        clearFire(g, level, to);
        boolean bore = travel(g, level, to) instanceof Task.Bore;
        if (bore) g.tasks.add(new Task.Bore(to, to.getY())); // straight through: rock dug, lava floored (the user 11-05: "travel way farther out")
        else { // the leg in hops of about 16 on the ground under the line (one walk plans about 28 out; a gap or a drop in the way is bridged within a hop)
            int dTot = (int) horiz(to, g.blockPosition()), n = Math.max(1, (int) Math.ceil(dTot / 16.0)); BlockPos last = g.blockPosition();
            for (int i = 1; i < n; i++) {
                double f0 = (double) i / n; BlockPos wp = null;
                for (double df : new double[] {0, -3.0 / dTot, 3.0 / dTot, -6.0 / dTot}) { double ff = f0 + df; wp = standAt(level, (int) Math.round(g.getX() + (to.getX() + 0.5 - g.getX()) * ff), (int) Math.round(g.getZ() + (to.getZ() + 0.5 - g.getZ()) * ff), last.getY()); if (wp != null) break; }
                if (wp != null && horiz(wp, last) >= 8) { g.tasks.add(new Task.GoTo(wp, 2)); last = wp; }
            }
            g.tasks.add(new Task.GoTo(to, 3));
        }
        s.phase = WALK; s.legFrom = g.blockPosition().immutable(); s.legTo = to; s.legTick = g.tickCount; s.dir = dir[0]; s.why = why[0];
        s.seenBefore.clear(); for (var sg : Perception.sightings(g)) s.seenBefore.add(key(sg));
        g.skillQueue.addFirst(new Skills.Step("find_fortress", "@" + (s.legs.size() + 1) + "w", st.count(), st.where()));
        int d = (int) horiz(to, g.blockPosition());
        String r = "leg " + (s.legs.size() + 1) + ": scouting " + dirName(dir[0]) + " to " + to.toShortString() + " (" + d + " blocks " + (bore ? (lastTravel.endsWith(": bridge") ? "paved across the lava" : "through the rock") : why[0].contains("tunnel") || why[0].contains("through the rock") ? "through the rock" : "along the ground") + "): " + why[0];
        ClaudeMod.LOGGER.info("[Claude scout] {} (FortressScout.skill)", r);
        if (s.said != dir[0]) { s.said = dir[0]; com.claudemod.director.ClaudeChat.say(player, "heading " + dirName(dir[0]) + " to look for a fortress: " + why[0], "find_fortress: the heading"); }
        g.noteDoing("scouting " + dirName(dir[0]) + " for a nether fortress, leg " + (s.legs.size() + 1));
        return r;
    }

    /** The leg he just looked round from goes on his notes, with what he saw. */
    static void finishLeg(ClaudeEntity g, ServerLevel level, State s) {
        s.phase = 0;
        if (s.legFrom == null) return;
        List<String> saw = new ArrayList<>();
        for (var sg : Perception.sightings(g)) {
            if (sg.dim() != level.dimension() || s.seenBefore.contains(key(sg))) continue;
            String k = com.claudemod.director.Structures.key(sg.id());
            saw.add((k == null ? sg.id() : k.replace('_', ' ')) + " (" + level.getBlockState(sg.saw()).getBlock().getName().getString() + " at " + sg.saw().toShortString() + ")");
            if ("bastion_remnant".equals(k)) { BlockPos c = sg.box().getCenter(); s.regionsDone.add(region(regionOf(c.getX()), regionOf(c.getZ()))); } // that region's one complex is a bastion: its fortress is in another region
        }
        BlockPos lm = landmarkBrick(g); if (lm != null) saw.add("nether brick far off at " + lm.toShortString());
        var blazes = Perception.known(g, net.minecraft.world.entity.monster.Blaze.class, g.getBoundingBox().inflate(96));
        if (!blazes.isEmpty()) saw.add(blazes.size() + " blaze(s)");
        BlockPos sp = Perception.nearestSign(g, g.blockPosition(), 96, "a mob spawner"); if (sp != null) saw.add("a spawner about " + sp.toShortString());
        BlockPos me = g.blockPosition();
        int rx = regionOf(me.getX()), rz = regionOf(me.getZ());
        if (horiz(me, middle(rx, rz, me.getY())) <= 64) s.regionsDone.add(region(rx, rz));
        boolean there = s.legTo != null && horiz(me, s.legTo) <= 6;
        if (!there && s.legTo != null) s.blocked.add(s.legTo.immutable()); // (his walk couldn't get there: that end is off)
        if (horiz(s.legFrom, me) < 8 && saw.isEmpty()) { // no headway at all: no leg to note, only the way that's off
            ClaudeMod.LOGGER.info("[Claude scout] {} toward {}: no way on from {} (the walk got nowhere): that end is off (FortressScout.finishLeg)", dirName(s.dir), s.legTo == null ? "?" : s.legTo.toShortString(), me.toShortString());
            s.legFrom = null; return;
        }
        Leg leg = new Leg(s.legs.size() + 1, s.dir, s.legFrom, me.immutable(), saw.isEmpty() ? "nothing: no nether brick, blaze or spawner in view" : String.join("; ", saw));
        s.legs.add(leg); s.legFrom = null;
        ClaudeMod.LOGGER.info("[Claude scout] leg {} {}: {} -> {} ({} blocks, {} s), saw {} (FortressScout.finishLeg)", leg.n(), dirName(leg.dir()), leg.from().toShortString(), leg.to().toShortString(), (int) horiz(leg.from(), leg.to()), (g.tickCount - s.legTick) / 20, leg.saw());
    }

    /** The next leg's end and heading, with the why; null when there's no way left to scout. */
    static BlockPos plan(ClaudeEntity g, ServerLevel level, State s, String[] why, Direction[] dir) {
        BlockPos me = g.blockPosition();
        // signs first: a blaze or a spawner he knows of: the fortress is about there
        var blazes = Perception.known(g, net.minecraft.world.entity.monster.Blaze.class, g.getBoundingBox().inflate(96));
        Vec3 sign = null; String what = null;
        if (!blazes.isEmpty()) { sign = Perception.believedAt(g, blazes.get(0)); what = "a blaze"; }
        if (sign == null) { var ws = Perception.known(g, net.minecraft.world.entity.monster.WitherSkeleton.class, g.getBoundingBox().inflate(96)); if (!ws.isEmpty()) { sign = Perception.believedAt(g, ws.get(0)); what = "a wither skeleton"; } }
        if (sign == null) { BlockPos sp = Perception.nearestSign(g, me, 96, "a mob spawner"); if (sp != null) { sign = Vec3.atCenterOf(sp); what = "a spawner"; } }
        boolean byEar = false;
        if (sign == null && s.heardAt != null && g.tickCount - s.heardTick <= 20 * 90) { sign = s.heardAt; what = "a " + s.heardWhat + " heard " + (g.tickCount - s.heardTick) / 20 + " s ago"; byEar = true; } // (through rock: a buried fortress is found by ear first)
        if (sign != null) {
            Vec3 v = sign.subtract(g.position()); double dist = Math.sqrt(v.x * v.x + v.z * v.z);
            BlockPos to = alongRay(g, level, Math.atan2(v.z, v.x), (int) Math.max(16, Math.min(LEG, dist - 6)));
            if (to != null && !scouted(s, to, me)) { dir[0] = Direction.getNearest(v.x, 0, v.z); why[0] = what + " " + (int) dist + " blocks " + Perception.compassOf(v) + ": blazes, wither skeletons and their spawners are a fortress's"; return to; }
            if (dist >= 2 && g.inventory.count(x -> x.getItem() instanceof net.minecraft.world.item.PickaxeItem) > 0) { // no open ground to it: through the rock straight at it
                BlockPos tt = tunnelToward(level, me, sign, (int) Math.min(LEG, dist));
                if (tt != null) { dir[0] = Direction.getNearest(v.x, 0, v.z); why[0] = what + " " + (int) dist + " blocks " + Perception.compassOf(v) + (byEar ? ", through the rock" : "") + ": blazes, wither skeletons and their spawners are a fortress's; tunnelling straight to it"; ClaudeMod.LOGGER.info("[Claude scout] {} {} off: a tunnel to {} (FortressScout.plan)", what, (int) dist, tt.toShortString()); return tt; }
            }
        }
        // the region grid: the nearest region middle not scouted yet
        int rx0 = regionOf(me.getX()), rz0 = regionOf(me.getZ());
        BlockPos goal = null; int grx = 0, grz = 0; double gd = Double.MAX_VALUE;
        for (int i = -2; i <= 2; i++) for (int j = -2; j <= 2; j++) {
            if (regionDone(s, rx0 + i, rz0 + j)) continue;
            BlockPos m = middle(rx0 + i, rz0 + j, me.getY()); double d = horiz(m, me);
            if (s.dir != null && (m.getX() - me.getX()) * s.dir.getStepX() + (m.getZ() - me.getZ()) * s.dir.getStepZ() > 0) d -= 24; // (near-ties: the one on the way he's going)
            if (d < gd) { gd = d; goal = m; grx = rx0 + i; grz = rz0 + j; }
        }
        List<Direction> order = new ArrayList<>();
        String base;
        if (goal != null) {
            int dx = goal.getX() - me.getX(), dz = goal.getZ() - me.getZ();
            Direction major = Math.abs(dx) >= Math.abs(dz) ? (dx >= 0 ? Direction.EAST : Direction.WEST) : (dz >= 0 ? Direction.SOUTH : Direction.NORTH);
            Direction minor = Math.abs(dx) >= Math.abs(dz) ? (dz >= 0 ? Direction.SOUTH : Direction.NORTH) : (dx >= 0 ? Direction.EAST : Direction.WEST);
            if (s.dir != null && dx * s.dir.getStepX() + dz * s.dir.getStepZ() >= 32) order.add(s.dir); // committed: on along the axis while it still closes the distance
            if (!order.contains(major)) order.add(major);
            if (Math.abs(major.getAxis() == Direction.Axis.X ? dz : dx) >= 16 && !order.contains(minor)) order.add(minor);
            boolean here = grx == rx0 && grz == rz0;
            base = (here ? "fortresses and bastions share one grid of 432-block regions, one of the two in each (a fortress 2 times in 5); this region's (x " + rx0 * REGION + " to " + (rx0 * REGION + REGION - 1) + ", z " + rz0 * REGION + " to " + (rz0 * REGION + REGION - 1) + ") is likeliest about its middle, "
                    : "the regions round here are scouted or hold a bastion; the next region's middle is ") + (int) horiz(goal, me) + " blocks " + Perception.compassOf(new Vec3(dx, 0, dz)) + ", so ";
        } else base = "every region round here is scouted: further out, so ";
        for (Direction d : new Direction[] {s.dir == null ? Direction.NORTH : s.dir, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) if (!order.contains(d)) order.add(d); // (then any way not scouted)
        Direction first = order.get(0);
        for (Direction d : order) {
            BlockPos to = legEnd(g, level, d, LEG);
            if (to == null && d == first && bores(g, level)) { to = me.relative(d, LEG).atY(Math.max(FORT_LO, Math.min(FORT_HI, me.getY()))); ClaudeMod.LOGGER.info("[Claude scout] no open ground {} of {}: on that bearing through it, to {} (FortressScout.plan)", dirName(d), me.toShortString(), to.toShortString()); } // (Day to Day 11-05: "no ground <dir>" fired ~85 times and turned him round the portal)
            if (to == null) { ClaudeMod.LOGGER.info("[Claude scout] no ground {} of {} (lava too wide or rock) (FortressScout.plan)", dirName(d), me.toShortString()); continue; }
            if (scouted(s, to, me)) { ClaudeMod.LOGGER.info("[Claude scout] {} to {}: scouted already (FortressScout.plan)", dirName(d), to.toShortString()); continue; }
            dir[0] = d;
            why[0] = base + (d == first ? "" : "(that way's blocked or scouted) ") + dirName(d) + " in legs of " + LEG + " along the ground, looking out over the open from each end";
            return to;
        }
        // no open ground in sight any way: through the rock, as a player does with a pickaxe (live 08:10:37, review-95: one leg, then "which way?" with a diamond pick and 40 cobble on him)
        if (g.inventory.count(x -> x.getItem() instanceof net.minecraft.world.item.PickaxeItem) > 0) for (Direction d : order) {
            BlockPos to = tunnelEnd(level, me, d, TUNNEL);
            if (to == null || scouted(s, to, me)) continue;
            dir[0] = d;
            why[0] = base + "no open ground in sight that way, so " + dirName(d) + " through the rock (" + TUNNEL + " blocks), looking out from the far end";
            ClaudeMod.LOGGER.info("[Claude scout] no open ground any way from {}: a tunnel {} to {} (FortressScout.plan)", me.toShortString(), dirName(d), to.toShortString());
            return to;
        }
        if (bores(g, level)) { // every end checked is lava, a drop or scouted: the bore floors lava and gaps, so on along the first way, never a question
            Direction d = order.get(0); BlockPos to = me.relative(d, LEG).atY(Math.max(FORT_LO, Math.min(FORT_HI, me.getY())));
            dir[0] = d; why[0] = base + dirName(d) + " straight through rock and lava (" + LEG + " blocks), looking out from the far end";
            ClaudeMod.LOGGER.info("[Claude scout] no clear end any way from {}: bored {} to {} (FortressScout.plan)", me.toShortString(), dirName(d), to.toShortString());
            return to;
        }
        return null;
    }
    /** Paces, seconds a block: a tunnel through rock he can't see into (stage 816: 500 blocks of rock, caves and lava in 197 s), a bridge over open lava (a floor and two rails a block: stage 819, 34 blocks in 35 s), a sprint over ground. A player knows these paces, not what's in the rock. */
    public static final double BORE_S = 0.4, BRIDGE_S = 0.9, WALK_S = 0.2, WALL_S = 0.3, MINE_S = 0.5; // MINE_S: a block of netherrack for the floor, walked to, dug and picked up
    /** The nearest blaze, ghast or skeleton he knows of within 48 that can see him, or null. */
    public static net.minecraft.world.entity.Mob shooterInSight(ClaudeEntity g) {
        for (var m : Perception.known(g, net.minecraft.world.entity.Mob.class, g.getBoundingBox().inflate(48), x -> x.isAlive() && (x instanceof net.minecraft.world.entity.monster.Blaze || x instanceof net.minecraft.world.entity.monster.Ghast || x instanceof net.minecraft.world.entity.monster.AbstractSkeleton)))
            if (m.hasLineOfSight(g)) return m;
        return null;
    }
    public static String lastTravel = ""; // (the last choice and its costs: for stages)
    /**
     * The way there, whichever of three he reckons the fastest (the user 11-05: "fully capable of navigating around the terrain, or paving his own way across lava"):
     * the walk round by its path's length at a sprint, no path = no walk; the bridge, the straight line priced by what he sees of it (open lava paved, open
     * ground walked, the rest dug); the bore, the line at tunnelling pace throughout. Bridge and bore are the same mover (Task.Bore paves lava and digs rock as
     * it meets them); their prices differ by what he can see. Logged with all three.
     */
    public static Task travel(ClaudeEntity g, ServerLevel level, BlockPos to) {
        BlockPos me = g.blockPosition(); boolean can = level.dimension() == net.minecraft.world.level.Level.NETHER && g.inventory.count(x -> x.getItem() instanceof net.minecraft.world.item.PickaxeItem) > 0; // (a pick is the tool: blocks short are mined first, the bore tops itself up; Day to Day 18:47 World 17: an iron pick, "short of blocks (34 of 147)", the walk with no path picked)
        int dx = to.getX() - me.getX(), dz = to.getZ() - me.getZ(), dy = to.getY() - me.getY(), n = Math.abs(dx) + Math.abs(dz);
        int lava = 0, open = 0, rock = 0; Vec3 eye = g.getEyePosition(); int x = me.getX(), z = me.getZ();
        for (int k = 1; k <= n; k++) { // the L: the longer axis first, as the bore goes
            if (Math.abs(dx) >= Math.abs(dz) ? x != to.getX() : z == to.getZ()) x += Integer.signum(dx); else z += Integer.signum(dz);
            BlockPos c = new BlockPos(x, me.getY() + dy * k / Math.max(1, n), z);
            if (!level.getBlockState(c).isAir() || !level.getBlockState(c.above()).isAir() || !Perception.clear(level, eye, Vec3.atCenterOf(c))) { rock++; continue; }
            boolean hot = false; for (int d = 1; d <= 3; d++) if (level.getFluidState(c.below(d)).is(net.minecraft.tags.FluidTags.LAVA)) hot = true;
            if (!hot && level.getBlockState(c.below()).isFaceSturdy(level, c.below(), net.minecraft.core.Direction.UP)) open++; else lava++; // (a gap is paved like lava)
        }
        double boreRowS = Weights.w("travel.bore_s", BORE_S), bridgeRowS = Weights.w("travel.bridge_s", BRIDGE_S), walkRowS = Weights.w("travel.walk_s", WALK_S), wallS = Weights.w("travel.wall_s", WALL_S), mineS = Weights.w("travel.mine_s", MINE_S);
        double[] wear = pickWear(g); // {seconds of pick per dig, digs left in his picks}: a 2-high bore is 2 digs a block, his pick's wear priced in (a stone pick is 131 uses)
        int boreDigs = 2 * (n + Math.abs(dy));
        double boreS = can ? boreRowS * (n + Math.abs(dy)) + boreDigs * wear[0] + Math.max(0, boreDigs - wear[1]) * Weights.w("travel.no_pick_dig_s", 4) : Double.MAX_VALUE; // (the line through rock he can't see into; digs past his picks' last use: by hand)
        var shooter = shooterInSight(g); // a knock off a bridge is the fall: the expert's cut is a wall block on the shooter's side over the lava, its time priced in (the user 11-05: a cost, not a ban)
        int need = lava * (shooter != null ? 4 : 3), have = g.inventory.count(it -> it.is(net.minecraft.world.item.Items.NETHERRACK) || it.is(net.minecraft.world.item.Items.COBBLESTONE) || it.is(net.minecraft.world.item.Items.COBBLED_DEEPSLATE) || it.is(net.minecraft.world.item.Items.BLACKSTONE)) + rock * 2; // a floor and two rails a block of lava (and a wall under fire); the rock on the way gives 2 a block
        int mine = lava > 0 ? Math.max(0, need - have) : 0; // blocks short: netherrack is all round him in the Nether, mined first at a pick's pace
        int bridgeDigs = mine + 2 * (rock + Math.abs(dy));
        double bridgeS = can && lava > 0 ? mineS * mine + (bridgeRowS + (shooter != null ? wallS : 0)) * lava + walkRowS * open + boreRowS * (rock + Math.abs(dy)) + bridgeDigs * wear[0] + Math.max(0, bridgeDigs - wear[1]) * Weights.w("travel.no_pick_dig_s", 4) : Double.MAX_VALUE;
        var path = g.getNavigation().createPath(to, 2); double walkS = Double.MAX_VALUE; int nodes = 0;
        if (path != null && path.canReach()) { nodes = path.getNodeCount(); double len = 0; for (int i = 1; i < nodes; i++) len += Math.sqrt(path.getNode(i).distanceToSqr(path.getNode(i - 1))); walkS = walkRowS * len; }
        if (lava > 0) boreS = bridgeS; // (lava he can see on the line: the bore meets it too and paves it, the same line at the same price; short of blocks, neither can)
        String pick = walkS <= bridgeS && walkS <= boreS ? "walk" : bridgeS <= boreS ? "bridge" : "bore";
        lastTravel = String.format("to %s: walk %s vs bridge %s vs bore %s: %s", to.toShortString(), walkS == Double.MAX_VALUE ? "no path" : String.format("~%.0f s (%d steps)", walkS, nodes),
                bridgeS == Double.MAX_VALUE ? (!can ? "no pick" : "no lava in sight on the line") : String.format("~%.0f s (%s%d paved over lava%s, %d open, %d through rock)", bridgeS, mine > 0 ? mine + " blocks to mine first, " : "", lava, shooter == null ? "" : ", walled against the " + shooter.getType().getDescription().getString().toLowerCase(), open, rock),
                boreS == Double.MAX_VALUE ? "no pick" : String.format("~%.0f s (%d digs, pick wear %.2f s a dig, %d uses left)", boreS, boreDigs, wear[0], (int) wear[1]), pick);
        ClaudeMod.LOGGER.info("[Claude route] {} (FortressScout.travel)", lastTravel);
        return pick.equals("walk") ? new Task.GoTo(to, 3) : new Task.Bore(to, to.getY());
    }
    /** His picks' wear: {seconds a dig costs of the best pick (its replacement priced over its uses: travel.pick_replace_s.<tier>), digs left in all his picks}. */
    static double[] pickWear(ClaudeEntity g) {
        double perDig = 0, left = 0, bestTier = -1;
        for (int i = 0; i < g.inventory.items.getContainerSize(); i++) { var it = g.inventory.items.getItem(i); if (!(it.getItem() instanceof net.minecraft.world.item.PickaxeItem)) continue;
            left += it.getMaxDamage() - it.getDamageValue();
            String tier = it.is(net.minecraft.world.item.Items.NETHERITE_PICKAXE) ? "netherite" : it.is(net.minecraft.world.item.Items.DIAMOND_PICKAXE) ? "diamond" : it.is(net.minecraft.world.item.Items.IRON_PICKAXE) ? "iron" : it.is(net.minecraft.world.item.Items.STONE_PICKAXE) ? "stone" : it.is(net.minecraft.world.item.Items.GOLDEN_PICKAXE) ? "gold" : "wood";
            double rank = switch (tier) { case "netherite" -> 5; case "diamond" -> 4; case "iron" -> 3; case "stone" -> 2; case "gold" -> 1; default -> 0; };
            double replace = Weights.w("travel.pick_replace_s." + tier, switch (tier) { case "netherite" -> 3600; case "diamond" -> 1200; case "iron" -> 300; case "stone" -> 30; case "gold" -> 200; default -> 20; });
            if (rank > bestTier) { bestTier = rank; perDig = replace / Math.max(1, it.getMaxDamage()); } }
        return new double[] {perDig, left};
    }
    /** Nether travel by bore: a pickaxe and 16+ blocks to floor with. */
    public static boolean bores(ClaudeEntity g, ServerLevel level) {
        return level.dimension() == net.minecraft.world.level.Level.NETHER && g.inventory.count(x -> x.getItem() instanceof net.minecraft.world.item.PickaxeItem) > 0
                && g.inventory.count(x -> x.is(net.minecraft.world.item.Items.NETHERRACK) || x.is(net.minecraft.world.item.Items.COBBLESTONE) || x.is(net.minecraft.world.item.Items.COBBLED_DEEPSLATE) || x.is(net.minecraft.world.item.Items.BLACKSTONE)) >= 16;
    }
    static final int TUNNEL = 40, FORT_LO = 48, FORT_HI = 70;
    /** A tunnel's end toward a point (a sign heard or seen): the cell up to len along the line, at the point's height, never in lava or beside it or over a 2-deep drop; nearer when that one isn't. */
    static BlockPos tunnelToward(ServerLevel level, BlockPos me, Vec3 at, int len) {
        Vec3 v = at.subtract(Vec3.atCenterOf(me)); double dh = Math.sqrt(v.x * v.x + v.z * v.z); if (dh < 1) return null;
        for (int k = len; k >= Math.min(len, 6); k -= 2) {
            double f = Math.min(1.0, k / dh); BlockPos c = BlockPos.containing(me.getX() + 0.5 + v.x * f, Math.round(me.getY() + (at.y - me.getY()) * f), me.getZ() + 0.5 + v.z * f);
            if (!level.hasChunkAt(c) || c.getY() <= level.getMinBuildHeight() + 2 || c.getY() >= MAX_Y) continue;
            boolean hot = false; for (BlockPos q : BlockPos.betweenClosed(c.offset(-1, -1, -1), c.offset(1, 2, 1))) if (level.getFluidState(q).is(net.minecraft.tags.FluidTags.LAVA)) { hot = true; break; }
            if (hot || level.getBlockState(c.below()).getCollisionShape(level, c.below()).isEmpty() && level.getBlockState(c.below(2)).getCollisionShape(level, c.below(2)).isEmpty()) continue;
            return c.immutable();
        }
        return null;
    }
    /** A tunnel's end: the cell len along d at his height (back to 32), never one with lava in it, over it, beside it or a drop under it; rock is fine (the walk digs it). */
    static BlockPos tunnelEnd(ServerLevel level, BlockPos me, Direction d, int len) {
        int y = Math.max(FORT_LO, Math.min(FORT_HI, me.getY())); // (at a fortress's height: buried ones sit about y 48 to 70, a tunnel above or below them never meets their bricks)
        for (int k = len; k >= 32; k -= 4) {
            BlockPos c = me.relative(d, k).atY(y); if (!level.hasChunkAt(c)) continue;
            boolean hot = false; for (BlockPos q : BlockPos.betweenClosed(c.offset(-1, -1, -1), c.offset(1, 2, 1))) if (level.getFluidState(q).is(net.minecraft.tags.FluidTags.LAVA)) { hot = true; break; }
            if (hot || level.getBlockState(c.below()).getCollisionShape(level, c.below()).isEmpty() && level.getBlockState(c.below(2)).getCollisionShape(level, c.below(2)).isEmpty()) continue;
            return c.immutable();
        }
        return null;
    }

    /** The user (16:29): "feel free to put out fire if its in your way": fire he can see on the first 24 blocks of the leg gets punched out first. */
    static void clearFire(ClaudeEntity g, ServerLevel level, BlockPos to) {
        Vec3 eye = g.getEyePosition(), d = Vec3.atCenterOf(to).subtract(g.position()); double len = Math.sqrt(d.x * d.x + d.z * d.z); if (len < 1) return;
        java.util.LinkedHashSet<BlockPos> fires = new java.util.LinkedHashSet<>();
        for (int k = 1; k <= Math.min(24, len) && fires.size() < 6; k++) {
            int x = (int) Math.floor(g.getX() + d.x / len * k), z = (int) Math.floor(g.getZ() + d.z / len * k);
            for (int ox = -1; ox <= 1; ox++) for (int oz = -1; oz <= 1; oz++) for (int y = g.getBlockY() - 2; y <= g.getBlockY() + 2; y++) {
                BlockPos q = new BlockPos(x + ox, y, z + oz);
                if (level.getBlockState(q).is(BlockTags.FIRE) && Perception.clear(level, eye, Vec3.atCenterOf(q))) fires.add(q.immutable());
            }
        }
        for (BlockPos q : fires) g.tasks.add(new Task.Break(q));
        if (!fires.isEmpty()) ClaudeMod.LOGGER.info("[Claude scout] fire on the way at {}: punching it out first (FortressScout.clearFire)", fires);
    }

    /** For his situation: the fortress he found, or the scouting so far; "" when he hasn't scouted. */
    public static String describe(ClaudeEntity g, ServerLevel level) {
        State s = states == null ? null : states.get(g.getUUID());
        if (s == null || (s.legs.isEmpty() && s.found == null)) return "";
        StringBuilder sb = new StringBuilder("FORTRESS SCOUTING: ");
        sb.append(s.found != null ? "found one: saw its " + s.foundHow : "none seen yet" + (s.dir != null ? ", heading " + dirName(s.dir) : ""));
        int from = Math.max(0, s.legs.size() - 4);
        if (!s.legs.isEmpty()) sb.append("; legs (each scouted once, never again): ");
        for (int i = from; i < s.legs.size(); i++) { Leg l = s.legs.get(i); sb.append(i == from ? "" : ", ").append(l.n()).append(" ").append(dirName(l.dir())).append(" ").append(l.from().toShortString()).append(" -> ").append(l.to().toShortString()).append(" saw ").append(l.saw()); }
        return sb.append("\n").toString();
    }
}
