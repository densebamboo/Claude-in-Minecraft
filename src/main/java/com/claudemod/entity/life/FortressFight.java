package com.claudemod.entity.life;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.claudemod.ClaudeMod;
import com.claudemod.entity.ClaudeEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.WitherSkeleton;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Fortress fights the way an expert does them (patch_blaze_loot.py, live 2026-10-30 16:52-17:02): blazes and wither skeletons
 * from cover, the rods counted to the number asked, and the facts about the dragon-path mobs he deals with, exact.
 *
 * Blaze (vanilla BlazeAttackGoal, 1.21): only with him in its line of sight does its volley clock run: it glows (charged) for 60
 * ticks, then 3 small fireballs 6 ticks apart (5 damage and 5 s of fire each), then 100 ticks quiet. So a corner that breaks its
 * sight stops the volley, the glow he sees tells him when the shots come, a shield takes them, and the quiet 5 s are when he
 * closes in. It has 20 health: a diamond axe crit (13.5) and one more hit, an iron sword in 3 crits.
 * Wither skeleton: 2.4 tall, so it can't stand in a 2-high cell, and its reach is its box widened ~0.83 sideways only: from a
 * cell with no 2.4-high standing room on any of its 8 sides it can't touch him, and he hits it 2 cells off (his reach is 3).
 */
public final class FortressFight {
    private FortressFight() {}

    /** Where he fights from: cover (out of its sight / out of its reach), peek (one step out, in sight of it), what it's about. */
    public record Cover(BlockPos cover, BlockPos peek, BlockPos anchor, String kind, String how, int tick) {}

    private static Map<UUID, Cover> covers; // per bot (static: fields added live start null)
    private static Map<UUID, Cover> covers() { if (covers == null) covers = new HashMap<>(); return covers; }
    private static Map<UUID, Integer> chargedSince; // per blaze: the tick he first saw it glow this volley
    private static Map<UUID, Integer> chargedSince() { if (chargedSince == null) chargedSince = new HashMap<>(); return chargedSince; }
    private static Map<UUID, Integer> riskSaid; // per bot: when the risk was said for this rod run
    private static Map<UUID, Integer> riskSaid() { if (riskSaid == null) riskSaid = new HashMap<>(); return riskSaid; }
    private static Map<UUID, int[]> runs; // per bot: {goal rods, passes with no rod gained, rods at the last pass, walks}
    private static Map<UUID, Integer> outOfReach; // when the blazes in view were last all out of his reach, with nothing to shoot them with (static: fields added live start null)
    private static Map<UUID, Integer> outOfReach() { if (outOfReach == null) outOfReach = new HashMap<>(); return outOfReach; }
    private static Map<UUID, int[]> runs() { if (runs == null) runs = new HashMap<>(); return runs; }
    private static Map<UUID, long[]> blazeLast; // per bot: {the cell, the tick} of the last blaze he saw, and the waits for the next since (static: fields added live start null)
    private static Map<UUID, long[]> blazeLast() { if (blazeLast == null) blazeLast = new HashMap<>(); return blazeLast; }

    public static void reset(ClaudeEntity g) { covers().remove(g.getUUID()); runs().remove(g.getUUID()); riskSaid().remove(g.getUUID()); }

    private static java.util.Map<java.util.UUID, BlockPos> lastGap; // the last 2-high gap he fought a wither skeleton from (static: fields added live start null)
    public static boolean coverMob(Entity e) { return e instanceof Blaze || e instanceof WitherSkeleton; }

    // ------------------------------------------------------------------ geometry

    static Vec3 eyeOf(BlockPos feet) { return new Vec3(feet.getX() + 0.5, feet.getY() + 1.62, feet.getZ() + 0.5); }
    static boolean clear(ServerLevel level, Vec3 a, Vec3 b, Entity ctx) { return level.clip(new ClipContext(a, b, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, ctx)).getType() == HitResult.Type.MISS; }
    static boolean open(ServerLevel level, BlockPos p) { return level.getBlockState(p).getCollisionShape(level, p).isEmpty() && level.getFluidState(p).isEmpty(); }
    static boolean floor(ServerLevel level, BlockPos p) { return !level.getBlockState(p).getCollisionShape(level, p).isEmpty() && level.getFluidState(p).isEmpty(); }
    /** He can stand there: floor, two open cells, no fire, no lava. */
    public static boolean stand(ServerLevel level, BlockPos feet) {
        return open(level, feet) && open(level, feet.above()) && floor(level, feet.below()) && !level.getBlockState(feet).is(BlockTags.FIRE)
                && !level.getBlockState(feet.below()).is(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK);
    }
    /** A 2.4-tall mob (a wither skeleton) stands there: floor and three open cells. */
    static boolean tall(ServerLevel level, BlockPos feet) { return floor(level, feet.below()) && open(level, feet) && open(level, feet.above()) && open(level, feet.above(2)); }
    /** No cell round it (its 8 sides, a step up or down) where a wither skeleton stands: from here its sword can't reach him. */
    static boolean witherSafe(ServerLevel level, BlockPos c) {
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 1; dy++) { if (dx == 0 && dz == 0) continue; if (tall(level, c.offset(dx, dy, dz))) return false; }
        return true;
    }
    static BlockPos feet(ClaudeEntity g) { return BlockPos.containing(g.getX(), g.getY() + 0.2, g.getZ()); }
    public static boolean at(ClaudeEntity g, BlockPos cell) {
        return cell != null && Math.abs(g.getX() - (cell.getX() + 0.5)) < 0.35 && Math.abs(g.getZ() - (cell.getZ() + 0.5)) < 0.35 && Math.abs(g.getY() - cell.getY()) < 0.6;
    }

    // ------------------------------------------------------------------ cover

    /** The cover for this fight: kept while it still works (out of the foes' sight / reach), else picked again. Null: none here. */
    public static Cover coverFor(ClaudeEntity g, ServerLevel level, LivingEntity foe) {
        Cover c = covers().get(g.getUUID());
        boolean blaze = foe instanceof Blaze;
        String kind = blaze ? "blaze" : "wither";
        if (c != null && c.kind().equals(kind) && g.tickCount - c.tick() < 20 * 20 && c.cover().closerThan(g.blockPosition(), 16) && stillGood(g, level, c, foe)) return c;
        if (c != null && c.kind().equals(kind) && g.tickCount - c.tick() < 20) return c; // (picked under a second ago: no re-scan every tick)
        c = blaze ? pickBlaze(g, level, foe) : pickWither(g, level, foe);
        if (c == null) { covers().remove(g.getUUID()); return null; }
        covers().put(g.getUUID(), c);
        if (!blaze) { if (lastGap == null) lastGap = new java.util.HashMap<>(); lastGap.put(g.getUUID(), c.cover()); }
        ClaudeMod.LOGGER.info("[Claude fight] cover for the {}: {} (FortressFight.coverFor)", foe.getType().getDescription().getString(), c.how());
        return c;
    }

    static boolean stillGood(ClaudeEntity g, ServerLevel level, Cover c, LivingEntity foe) {
        if (!stand(level, c.cover())) return false;
        if (c.kind().equals("wither")) return witherSafe(level, c.cover()) && blazeSees(g, level, c.cover()) == null;
        List<BlockPos> put = builds().get(g.getUUID()); if (put != null && !c.cover().equals(buildFor().get(g.getUUID()))) put = null;
        for (Blaze b : blazesNear(g, level, c.cover(), SHOT_RANGE)) if (sees(level, b, c.cover()) && (put == null || seenPast(level, b.getEyePosition(), c.cover(), put, g))) return false;
        return true;
    }

    /** A wither skeleton nearer the gap than he is and within 1.5 of the straight way there: the gap is past it, or null. */
    static net.minecraft.world.entity.monster.WitherSkeleton pastWither(ClaudeEntity g, ServerLevel level, BlockPos me, BlockPos gap) {
        Vec3 a = Vec3.atBottomCenterOf(me), b = Vec3.atBottomCenterOf(gap); Vec3 ab = b.subtract(a); double len2 = ab.x * ab.x + ab.z * ab.z;
        for (var w : level.getEntitiesOfClass(net.minecraft.world.entity.monster.WitherSkeleton.class, new AABB(me).inflate(16), Entity::isAlive)) {
            Vec3 p = w.position(); if (p.distanceToSqr(b) >= a.distanceToSqr(b)) continue;
            double f = len2 < 1e-6 ? 0 : Math.max(0, Math.min(1, ((p.x - a.x) * ab.x + (p.z - a.z) * ab.z) / len2));
            double dx = a.x + ab.x * f - p.x, dz = a.z + ab.z * f - p.z; if (dx * dx + dz * dz <= 1.5 * 1.5 && Math.abs(p.y - (a.y + ab.y * f)) <= 2.5) return w;
        }
        return null;
    }
    /** How far a blaze shoots: its follow range (vanilla 48), any it sees within that it fires on. Cover is out of sight of all of them (24 left the ones 27 off out: "healing in cover" under their fireballs, World 17 16:12 death 7; stage 1135). */
    static final int SHOT_RANGE = 48;
    /** A blaze he knows of with sight of the cell, or null. */
    static Blaze blazeSees(ClaudeEntity g, ServerLevel level, BlockPos cell) { for (Blaze b : blazesNear(g, level, cell, SHOT_RANGE)) if (sees(level, b, cell)) return b; return null; }

    /** The blazes about that he knows of: in his line of sight, or within 16 (their breathing and crackle carry), or already on his senses' record. */
    static List<Blaze> blazesNear(ClaudeEntity g, ServerLevel level, BlockPos at, int r) {
        java.util.Set<Blaze> known = new java.util.HashSet<>(Perception.known(g, Blaze.class, new AABB(at).inflate(r), Entity::isAlive));
        return level.getEntitiesOfClass(Blaze.class, new AABB(at).inflate(r), b -> b.isAlive() && (known.contains(b) || b.distanceTo(g) <= 16 || g.hasLineOfSight(b)));
    }
    public static boolean sees(ServerLevel level, LivingEntity mob, BlockPos cell) {
        Vec3 from = mob.getEyePosition();
        return clear(level, from, eyeOf(cell), mob) || clear(level, from, new Vec3(cell.getX() + 0.5, cell.getY() + 0.9, cell.getZ() + 0.5), mob);
    }

    /** A blaze spawner he can see (the blaze turning in the cage), or null. */
    public static BlockPos blazeSpawner(ClaudeEntity g, ServerLevel level, int r) {
        BlockPos best = null; double bd = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(g.blockPosition().offset(-r, -8, -r), g.blockPosition().offset(r, 8, r))) {
            if (!level.getBlockState(p).is(net.minecraft.world.level.block.Blocks.SPAWNER)) continue;
            if (!(level.getBlockEntity(p) instanceof net.minecraft.world.level.block.entity.SpawnerBlockEntity sb)) continue;
            Entity shown = null; try { shown = sb.getSpawner().getOrCreateDisplayEntity(level, p); } catch (RuntimeException e) { }
            if (!(shown instanceof Blaze)) continue;
            if (!com.claudemod.director.ClaudeLife.canSee(g, level, p) && !com.claudemod.director.ClaudeLife.visibleBlock(g, level, p)) continue;
            double d = p.distSqr(g.blockPosition());
            if (d < bd) { bd = d; best = p.immutable(); }
        }
        return best;
    }

    /**
     * Blazes: a standing cell none of them (nor the spawner's top) can see, with a step beside it that sees what he's fighting:
     * he waits in the corner, steps out to draw or strike, steps back in before the volley. Walls on more sides (a doorway's
     * jamb, a corner) and 4-12 off the spawner are better; the nearest of those he can walk to.
     */
    private static Map<UUID, List<BlockPos>> builds; // per bot: the blocks his built blaze cover wants (static: fields added live start null)
    private static Map<UUID, List<BlockPos>> builds() { if (builds == null) builds = new HashMap<>(); return builds; }
    private static Map<UUID, BlockPos> buildFor; // per bot: the cover cell those blocks are for
    private static Map<UUID, BlockPos> buildFor() { if (buildFor == null) buildFor = new HashMap<>(); return buildFor; }
    /** The blocks his blaze cover still wants put down (open cells), or an empty list: none, or the cover is found, not built. */
    public static List<BlockPos> unbuilt(ClaudeEntity g, ServerLevel level, Cover c) {
        List<BlockPos> put = builds().get(g.getUUID());
        if (put == null || c == null || !c.cover().equals(buildFor().get(g.getUUID()))) return List.of();
        List<BlockPos> out = new ArrayList<>(); for (BlockPos q : put) if (level.getBlockState(q).canBeReplaced()) out.add(q); return out;
    }
    /** What he walls with in the Nether, as WorkGoal.scaffoldBlock picks it there: cobblestone or deepslate first (ghast-proof), else netherrack or dirt. */
    public static net.minecraft.world.item.Item wallItem(ClaudeEntity g) {
        var s = FortressScout.ghastProof(g); if (!s.isEmpty()) return s.getItem();
        for (var it : new net.minecraft.world.item.Item[] {Items.NETHERRACK, Items.DIRT}) if (g.inventory.count(x -> x.is(it)) > 0) return it;
        return null;
    }
    /** The cells along the line from a to b, in order (eighth-block steps). */
    static List<BlockPos> cellsOn(Vec3 a, Vec3 b) { List<BlockPos> out = new ArrayList<>(); int n = Math.max(1, (int) Math.ceil(a.distanceTo(b) * 8)); for (int i = 0; i <= n; i++) { BlockPos p = BlockPos.containing(a.lerp(b, i / (double) n)); if (out.isEmpty() || !out.get(out.size() - 1).equals(p)) out.add(p); } return out; }
    /** An eye still sees the cell (his eyes or his middle) with the blocks of {@code put} standing, each a full block. */
    static boolean seenPast(ServerLevel level, Vec3 eye, BlockPos c, List<BlockPos> put, Entity ctx) {
        for (Vec3 p : new Vec3[] {eyeOf(c), new Vec3(c.getX() + 0.5, c.getY() + 0.9, c.getZ() + 0.5)}) { if (!clear(level, eye, p, ctx)) continue; boolean cut = false; for (BlockPos q : cellsOn(p, eye)) if (put.contains(q)) { cut = true; break; } if (!cut) return true; }
        return false;
    }
    /**
     * Cover put up where there's none to find, as a player does on an open fortress bridge (live 04:39-04:48 11-09: "no cover from the
     * blazes ... 0 spots out of sight" the whole fight, 7 blazes, 20 -> 2; ~360 such lines against ~50 covers found, 11-07..09): a cell
     * near him, a block in each line of sight to it (the first cell out of his own two that the line crosses: a wall in front, a roof
     * when they're above), and a step beside it, clear of the blocks, that sees what he's fighting. Fewest blocks, then nearest.
     */
    /** A face to put a block in q against: a solid block beside it, or one of {@code up} (put there before it). */
    static boolean faced(ServerLevel level, BlockPos q, List<BlockPos> up) { for (Direction d : Direction.values()) { BlockPos n = q.relative(d); if (up.contains(n) || !level.getBlockState(n).getCollisionShape(level, n).isEmpty()) return true; } return false; }
    private static String buildWhy = ""; // why the last buildBlaze built nothing (for the no-cover line)
    static Cover buildBlaze(ClaudeEntity g, ServerLevel level, List<Vec3> eyes, Vec3 anchor, int seen, BlockPos spawner, LivingEntity foe) {
        var item = wallItem(g); if (item == null || eyes.isEmpty()) { buildWhy = item == null ? "; no blocks to build with" : ""; return null; }
        int max = (int) Math.min(g.inventory.count(x -> x.is(item)), Weights.w("blaze.cover_build_max", 8));
        BlockPos me = feet(g);
        record Cand(BlockPos c, BlockPos peek, List<BlockPos> put, double score) {}
        Cand best = null; int[] no = new int[6]; // why the cells were passed over: {can't stand / near the anchor, a line's first cell can't take a block, too many blocks, a block with no face, no peek, no walk}
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) for (int dy = -1; dy <= 1; dy++) {
            BlockPos c = me.offset(dx, dy, dz);
            if (!stand(level, c) || Math.sqrt(c.distToCenterSqr(anchor)) < 3.0) { no[0]++; continue; }
            List<BlockPos> put = new ArrayList<>(); boolean ok = true;
            for (Vec3 e : eyes) {
                for (Vec3 p : new Vec3[] {eyeOf(c), new Vec3(c.getX() + 0.5, c.getY() + 0.9, c.getZ() + 0.5)}) {
                    if (!clear(level, e, p, g)) continue;
                    List<BlockPos> line = cellsOn(p, e); boolean cut = false; for (BlockPos q : line) if (put.contains(q)) { cut = true; break; } if (cut) continue;
                    BlockPos first = null; for (BlockPos q : line) if (!q.equals(c) && !q.equals(c.above())) { first = q; break; }
                    if (first == null || g.attempts.within(Attempts.Kind.PLACE, first, g.tickCount, 20 * 60) || !level.getBlockState(first).canBeReplaced() || !level.getFluidState(first).isEmpty() || !level.getEntitiesOfClass(LivingEntity.class, new AABB(first), x -> x != g).isEmpty()) { ok = false; break; }
                    put.add(first.immutable());
                }
                if (!ok) { no[1]++; break; }
                if (put.size() > max) { ok = false; no[2]++; break; }
            }
            if (!ok) continue;
            put.sort(java.util.Comparator.comparingInt(BlockPos::getY)); // (each against one under or beside it: bottom up)
            List<BlockPos> up = new ArrayList<>();
            for (BlockPos q : put) { // a block with nothing to go against gets one under it first (or beside it, on something), as a player props a roof
                if (up.contains(q)) continue;
                if (!faced(level, q, up)) { BlockPos prop = null;
                    for (BlockPos n : new BlockPos[] {q.below(), q.north(), q.south(), q.east(), q.west()}) if (!n.equals(c) && !n.equals(c.above()) && !up.contains(n) && level.getBlockState(n).canBeReplaced() && level.getFluidState(n).isEmpty() && faced(level, n, up)) { prop = n.immutable(); break; }
                    if (prop == null) { ok = false; break; } up.add(prop); }
                up.add(q); }
            if (ok && up.size() > max) { no[2]++; continue; }
            if (!ok) { no[3]++; continue; }
            BlockPos peek = null; double pd = Double.MAX_VALUE;
            for (Direction d : Direction.Plane.HORIZONTAL) { BlockPos q = c.relative(d); if (up.contains(q) || up.contains(q.above()) || !stand(level, q) || !clear(level, eyeOf(q), anchor, g)) continue;
                boolean cut = false; for (BlockPos x : cellsOn(eyeOf(q), anchor)) if (up.contains(x)) { cut = true; break; } if (cut) continue;
                double qd = q.distToCenterSqr(anchor); if (qd < pd) { pd = qd; peek = q.immutable(); } }
            if (peek == null) { no[4]++; continue; }
            double score = up.size() * Weights.w("blaze.cover_block_weight", 1.0) + Math.sqrt(c.distSqr(me)) * 0.6 + Math.abs(Math.sqrt(c.distToCenterSqr(anchor)) - 7) * 0.2;
            if (best != null && best.score() <= score) continue;
            if (!c.equals(me) && com.claudemod.entity.nav.ActionPath.plan(g, level, me, c, 0.5, true, 400, com.claudemod.entity.nav.ActionPath.Mode.NO_DIG, null, 0) == null) { no[5]++; continue; }
            best = new Cand(c.immutable(), peek, up, score);
        }
        if (best == null) { buildWhy = "; none to build with " + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).getPath() + ": " + no[0] + " no standing/by the anchor, " + no[1] + " a line's cell can't take a block, " + no[2] + " over " + max + " blocks, " + no[3] + " a block with no face, " + no[4] + " no peek, " + no[5] + " no walk"; return null; }
        builds().put(g.getUUID(), best.put()); buildFor().put(g.getUUID(), best.c());
        return new Cover(best.c(), best.peek(), spawner != null ? spawner : foe.blockPosition(), "blaze",
                "built: " + best.put().size() + " " + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).getPath() + " at " + best.c().toShortString() + " cutting the sight of " + seen + " blaze(s)" + (spawner != null ? " and the spawner at " + spawner.toShortString() : "") + " (no cover to find here), stepping out to " + best.peek().toShortString(), g.tickCount);
    }
    /** The solid cells in the line from his eyes to a blaze's: at most 3, the first in his reach, each soft (netherrack, soul soil...), no fortress block, no lava beside any; null when that's no window to cut. */
    static List<BlockPos> windowTo(ServerLevel level, ClaudeEntity g, Blaze b) {
        Vec3 a = g.getEyePosition(), e = b.getEyePosition(); List<BlockPos> cells = new ArrayList<>(); int n = (int) Math.ceil(a.distanceTo(e) * 4);
        for (int i = 1; i < n; i++) { BlockPos p = BlockPos.containing(a.lerp(e, i / (double) n)); if (cells.contains(p)) continue;
            var st = level.getBlockState(p); if (st.getCollisionShape(level, p).isEmpty()) continue;
            float hard = st.getDestroySpeed(level, p);
            if (cells.size() >= 3 || hard < 0 || hard > com.claudemod.entity.life.Weights.w("blaze.window_hardness", 1.5) || st.hasBlockEntity() || com.claudemod.entity.nav.ActionPath.structureBlock(level, p)) return null;
            if (cells.isEmpty() && Vec3.atCenterOf(p).distanceTo(a) > 4.5) return null;
            for (Direction d : Direction.values()) if (level.getFluidState(p.relative(d)).is(net.minecraft.tags.FluidTags.LAVA)) return null;
            cells.add(p.immutable()); }
        return cells.isEmpty() ? null : cells;
    }
    static Cover pickBlaze(ClaudeEntity g, ServerLevel level, LivingEntity foe) {
        BlockPos spawner = blazeSpawner(g, level, 20);
        List<Vec3> eyes = new ArrayList<>(); List<Blaze> bl = blazesNear(g, level, g.blockPosition(), SHOT_RANGE); if (foe instanceof Blaze b0 && !bl.contains(b0)) bl.add(b0);
        for (Blaze b : bl) eyes.add(b.getEyePosition());
        if (spawner != null && open(level, spawner.above())) eyes.add(Vec3.atCenterOf(spawner.above()));
        Vec3 anchor = spawner != null ? Vec3.atCenterOf(spawner.above()) : foe.getEyePosition();
        BlockPos me = feet(g);
        record Cand(BlockPos c, BlockPos peek, double score, int walls, double da) {}
        List<Cand> cands = new ArrayList<>();
        for (int dx = -10; dx <= 10; dx++) for (int dz = -10; dz <= 10; dz++) for (int dy = -3; dy <= 3; dy++) {
            BlockPos c = me.offset(dx, dy, dz);
            if (!stand(level, c)) continue;
            double da = Math.sqrt(c.distToCenterSqr(anchor));
            if (da < 3.0 || da > 18) continue;
            boolean seen = false; for (Vec3 e : eyes) if (clear(level, e, eyeOf(c), g) || clear(level, e, new Vec3(c.getX() + 0.5, c.getY() + 0.9, c.getZ() + 0.5), g)) { seen = true; break; }
            if (seen) continue;
            BlockPos peek = null; double pd = Double.MAX_VALUE;
            for (Direction d : Direction.Plane.HORIZONTAL) { BlockPos q = c.relative(d); if (!stand(level, q) || !clear(level, eyeOf(q), anchor, g)) continue; double qd = q.distToCenterSqr(anchor); if (qd < pd) { pd = qd; peek = q; } }
            if (peek == null) continue;
            int walls = 0; for (Direction d : Direction.Plane.HORIZONTAL) if (!open(level, c.relative(d).above())) walls++;
            cands.add(new Cand(c.immutable(), peek.immutable(), Math.sqrt(c.distSqr(me)) * 0.6 + Math.abs(da - 7) * 0.5 - walls * 1.5, walls, da));
        }
        cands.sort(java.util.Comparator.comparingDouble(Cand::score));
        for (int i = 0; i < Math.min(12, cands.size()); i++) {
            Cand k = cands.get(i);
            // reached by his own walker (no digging, a block put down where he has them), not vanilla's: live 17:37 11-07 "12 spots out of sight, none walkable" with 4 blazes on him, every spot refused by createPath
            if (!k.c().equals(me) && com.claudemod.entity.nav.ActionPath.plan(g, level, me, k.c(), 0.5, true, 800, com.claudemod.entity.nav.ActionPath.Mode.NO_DIG, null, 0) == null) continue;
            builds().remove(g.getUUID()); buildFor().remove(g.getUUID());
            String shape = k.walls() >= 3 ? "a nook" : k.walls() == 2 ? "a doorway's side" : "a corner";
            return new Cover(k.c(), k.peek(), spawner != null ? spawner : foe.blockPosition(), "blaze",
                    shape + " at " + k.c().toShortString() + " out of sight of " + bl.size() + " blaze(s)" + (spawner != null ? " and the spawner at " + spawner.toShortString() : "") + ", stepping out to " + k.peek().toShortString() + " (" + (int) k.da() + " off, " + cands.size() + " spots looked at)", g.tickCount);
        }
        { Cover built = buildBlaze(g, level, eyes, anchor, bl.size(), spawner, foe); if (built != null) return built; }
        com.claudemod.director.ClaudeLife.nookSay(g, "blazeNoCover", "[Claude fight] no cover from the blazes within 10 of " + g.blockPosition().toShortString() + " (" + cands.size() + " spots out of sight, none walkable or with a step that sees them" + buildWhy + ") (FortressFight.pickBlaze)"); /* once per spot (the backlog: every few seconds at the fortress, 03:54) */ // ponytail: once per 5 s, one shared clock (it is asked every tick, 20 lines a second live)
        return null;
    }

    /** Wither skeletons: a cell with no 2.4-high standing room on any side (a 2-high gap, two deep), with a spot 2 off it can walk up to, in his sight. */
    static Cover pickWither(ClaudeEntity g, ServerLevel level, LivingEntity foe) {
        BlockPos me = feet(g);
        record Cand(BlockPos c, BlockPos front, double score) {}
        List<Cand> cands = new ArrayList<>();
        for (int dx = -10; dx <= 10; dx++) for (int dz = -10; dz <= 10; dz++) for (int dy = -2; dy <= 2; dy++) {
            BlockPos c = me.offset(dx, dy, dz);
            if (!stand(level, c) || !witherSafe(level, c)) continue;
            BlockPos front = null;
            for (int ox = -2; ox <= 2 && front == null; ox++) for (int oz = -2; oz <= 2 && front == null; oz++) {
                if (Math.max(Math.abs(ox), Math.abs(oz)) != 2) continue;
                for (int oy = -1; oy <= 1 && front == null; oy++) { BlockPos m = c.offset(ox, oy, oz); if (tall(level, m) && clear(level, eyeOf(c), new Vec3(m.getX() + 0.5, m.getY() + 1.2, m.getZ() + 0.5), g)) front = m.immutable(); }
            }
            if (front == null) continue;
            cands.add(new Cand(c.immutable(), front, Math.sqrt(c.distSqr(me)) + 0.2 * Math.sqrt(c.distToCenterSqr(foe.position()))));
        }
        cands.sort(java.util.Comparator.comparingDouble(Cand::score));
        for (int i = 0; i < Math.min(12, cands.size()); i++) {
            Cand k = cands.get(i);
            // reached by his own walker (no digging, a block put down where he has them), not vanilla's: live 17:37 11-07 "12 spots out of sight, none walkable" with 4 blazes on him, every spot refused by createPath
            if (pastWither(g, level, me, k.c()) != null) continue; // (a gap with a wither skeleton between him and it: each step in meets its sword and the knockback throws him back out; death 53, 11-08: 14 hits on the way, 20 -> 5)
            if (blazeSees(g, level, k.c()) != null) continue; // (out of the sword's reach is no cover with a blaze in sight of it: death 53, two fireballs and the burn in the gap)
            if (!k.c().equals(me) && com.claudemod.entity.nav.ActionPath.plan(g, level, me, k.c(), 0.5, true, 800, com.claudemod.entity.nav.ActionPath.Mode.NO_DIG, null, 0) == null) continue;
            return new Cover(k.c(), k.front(), foe.blockPosition(), "wither", "a 2-high gap at " + k.c().toShortString() + " (no 3-high room on any side: its sword can't reach in), hitting it at " + k.front().toShortString() + " 2 off (" + cands.size() + " gaps looked at)", g.tickCount);
        }
        BlockPos last = lastGap == null ? null : lastGap.get(g.getUUID()); // (none within 10: the gap that held a wither skeleton off before, within 24, as a player goes back to the spot that worked; live 13:59 11-04 it was 15 off and he ran instead)
        if (last != null && last.closerThan(me, 24) && stand(level, last) && witherSafe(level, last)) {
            BlockPos front = null;
            for (int ox = -2; ox <= 2 && front == null; ox++) for (int oz = -2; oz <= 2 && front == null; oz++) { if (Math.max(Math.abs(ox), Math.abs(oz)) != 2) continue; for (int oy = -1; oy <= 1 && front == null; oy++) { BlockPos m = last.offset(ox, oy, oz); if (tall(level, m)) front = m.immutable(); } }
            var path = front == null ? null : g.getNavigation().createPath(last, 0);
            if (path != null && path.canReach()) return new Cover(last, front, foe.blockPosition(), "wither", "back to the 2-high gap at " + last.toShortString() + " that held one off before (" + (int) Math.sqrt(last.distSqr(me)) + " off; none nearer)", g.tickCount);
        }
        if (g.tickCount % 40 == 0) ClaudeMod.LOGGER.info("[Claude fight] no 2-high gap within 10 to fight the wither skeleton from ({} out of its reach, none walkable): shield and crits in the open, off at 10 health (FortressFight.pickWither)", cands.size()); // ponytail: said once per 2 s, it runs every tick (live 15:04: 2 lines a tick)
        return null;
    }

    // ------------------------------------------------------------------ blaze volleys

    /** What he sees of each blaze's glow: the tick it lit up (first seen lit: counted as half-way through its charge). */
    public static void observe(ClaudeEntity g, ServerLevel level) {
        for (Blaze b : blazesNear(g, level, g.blockPosition(), 32)) {
            if (!g.hasLineOfSight(b)) continue;
            blazeLast().put(g.getUUID(), new long[] {b.blockPosition().asLong(), g.tickCount, 0}); // (where the blazes are: their spawner or their spawning ground is round there)
            if (b.isOnFire()) chargedSince().putIfAbsent(b.getUUID(), g.tickCount - 40); // (Blaze.isOnFire is its charged flag: the glow and smoke a player sees)
            else chargedSince().remove(b.getUUID());
        }
        if (chargedSince().size() > 64) chargedSince().clear();
    }
    /** Ticks since that blaze lit up, or -1 when it isn't glowing. */
    public static int chargeAge(ClaudeEntity g, Blaze b) { if (!b.isOnFire()) return -1; Integer t = chargedSince().get(b.getUUID()); return t == null ? 999 : g.tickCount - t; }

    /** The blaze whose volley is due at him now (lit 44+ ticks, in sight of him), or one with a fireball coming his way; null: none. */
    public static LivingEntity volleyDue(ClaudeEntity g, ServerLevel level) {
        for (Blaze b : blazesNear(g, level, g.blockPosition(), 32)) {
            int age = chargeAge(g, b);
            if (age >= 44 && clear(level, b.getEyePosition(), g.getEyePosition(), b)) return b;
        }
        for (var f : level.getEntitiesOfClass(net.minecraft.world.entity.projectile.SmallFireball.class, g.getBoundingBox().inflate(12), f -> f.getOwner() != g)) {
            if (f.getDeltaMovement().dot(g.getEyePosition().subtract(f.position())) > 0 && clear(level, g.getEyePosition(), f.position(), g)) return f.getOwner() instanceof LivingEntity l ? l : blazesNear(g, level, g.blockPosition(), 32).stream().findFirst().orElse(null);
        }
        return null;
    }

    // ------------------------------------------------------------------ the rod run

    static int rods(ClaudeEntity g) { return g.inventory.count(s -> s.is(Items.BLAZE_ROD)); }

    /** What he says once before the rods: the risks he sees in his kit, and where he'll fight from. */
    static String riskLine(ClaudeEntity g, Cover c) {
        boolean fireRes = g.hasEffect(MobEffects.FIRE_RESISTANCE) || g.inventory.count(s -> s.is(Items.POTION) && String.valueOf(s.get(net.minecraft.core.component.DataComponents.POTION_CONTENTS)).contains("fire_resistance")) > 0;
        boolean bow = g.inventory.count(s -> s.is(Items.BOW)) > 0 && g.inventory.count(s -> s.is(Items.ARROW)) > 0;
        boolean shield = g.getOffhandItem().is(Items.SHIELD) || g.inventory.count(s -> s.is(Items.SHIELD)) > 0;
        List<String> no = new ArrayList<>();
        if (!fireRes) no.add("no fire res"); if (!bow) no.add("no bow"); if (!shield) no.add("no shield");
        String where = c == null ? "from the best corner i can find" : c.how().startsWith("a doorway") ? "from that doorway" : c.how().startsWith("a nook") ? "from that nook" : "from that corner";
        if (no.isEmpty()) return "blaze time: shield up for the volleys, fighting them " + where;
        return String.join(" and ", no) + ": i'll fight them " + where + ", it's risky";
    }

    /** "obtain blaze_rod N": find blazes (a spawner he can see, or blazes in view), fight from cover, count the rods, stop at N. */
    /** Where to stand on the fortress piece round {@code c}: the nether brick with two clear cells over it nearest the piece's middle (its floor or deck, not the lava under it). */
    public static BlockPos deckCellTest(ServerLevel level, BlockPos c) { return deckCell(level, c); }
    static BlockPos deckCell(ServerLevel level, BlockPos c) {
        BlockPos best = null; double bs = -Double.MAX_VALUE;
        for (BlockPos q : BlockPos.betweenClosed(c.offset(-12, -10, -12), c.offset(12, 14, 12))) {
            if (!level.isLoaded(q) || !level.getBlockState(q).is(net.minecraft.world.level.block.Blocks.NETHER_BRICKS)) continue;
            BlockPos f = q.above(); if (!level.getBlockState(f).getCollisionShape(level, f).isEmpty() || !level.getBlockState(f.above()).getCollisionShape(level, f.above()).isEmpty() || !level.getFluidState(f).isEmpty()) continue;
            double score = -f.distSqr(c); if (score > bs) { bs = score; best = f.immutable(); } // (nearest its middle: its floor or deck, not a corridor's roof, the highest bricks)
        }
        return best;
    }

    /**
     * Seconds the rods still to come cost in blaze fights: per blaze (blaze.rods_per_kill rods each) the approach and the swings
     * (HuntPatrol.melee), and the fireballs that land (blaze.hits_per_blaze, a shield's blaze.shield_factor of them) at 5 through
     * his armor (vanilla's sum) plus the burning after (blaze.fire_hp, none on fire resistance), each blaze priced by Decide.hpS
     * from the given health, at his food or fed.
     */
    /** Blazes out by their spawner: fight on at his health, or back out past its 16 blocks (it stops spawning), heal to full and come back to fight at full health. True: back out. */
    public static boolean swarmBackOut(ClaudeEntity g, int n, boolean shield, boolean fed, BlockPos spawner) {
        double armor = g.getArmorValue(), tough = g.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR_TOUGHNESS);
        double on = fightS(g, g.getHealth(), armor, tough, shield, fed, 0, n);
        double fb = Weights.w("blaze.fireball_hp", 5), perB = fb * (1 - Math.min(20, Math.max(armor / 5.0, armor - fb / (2 + tough / 4))) / 25) + Weights.w("blaze.fire_hp", 3);
        double walk = 2 * Math.max(0, Weights.w("blaze.spawner_range_blocks", 17) - Math.sqrt(spawner.distSqr(g.blockPosition()))) / Decide.sprint();
        double leaveHp = n * Weights.w("blaze.backout_exposed_s", 3) * Weights.w("blaze.shots_per_s", 0.6) * Weights.w("blaze.shot_hit_chance", 0.5) * perB * (shield ? Weights.w("blaze.shield_factor", 0.4) : 1);
        double back = walk + Decide.hpS(g, 0, leaveHp, perB) + Food.regenS(g, g.getMaxHealth() - Math.max(1, g.getHealth() - leaveHp)) + fightS(g, g.getMaxHealth(), armor, tough, shield, fed, 0, n);
        var pick = Decide.choose(g, "blaze.swarm", n + " blazes out by the spawner, health " + (int) g.getHealth() + ", armor " + (int) armor + (shield ? ", a shield" : ""), true, java.util.List.of(
            Decide.Option.of("fight on", on, "at " + (int) g.getHealth() + " HP"),
            Decide.Option.of("back out and heal", back, "past its " + (int) Weights.w("blaze.spawner_range_blocks", 17) + " blocks ~" + Math.round(walk) + " s there and back, ~" + Math.round(leaveHp) + " HP of fireballs leaving, then the fight at full health")));
        return pick != null && pick.name().equals("back out and heal");
    }
    public static double fightS(ClaudeEntity g, double health, double armor, double tough, boolean shield, boolean fed, int rodsLeft) { return fightS(g, health, armor, tough, shield, fed, rodsLeft, -1); }
    /** The same for a given number of blazes (blazesN >= 0), not rods: the fight-or-run price on the ones in front of him (Loops, WorkGoal.shouldFlee). */
    public static double fightS(ClaudeEntity g, double health, double armor, double tough, boolean shield, boolean fed, int rodsLeft, double blazesN) {
        double[] m = HuntPatrol.melee(g); double kill = Math.ceil(Weights.w("blaze.hp", 20) / m[0]) * m[1] + Weights.w("blaze.approach_s", 8);
        double dmg = Weights.w("blaze.fireball_hp", 5), perHit = dmg * (1 - Math.min(20, Math.max(armor / 5, armor - dmg / (2 + tough / 4))) / 25) + (g.hasEffect(net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE) ? 0 : Weights.w("blaze.fire_hp", 3));
        double hits = Weights.w("blaze.hits_per_blaze", 1.5) * (shield ? Weights.w("blaze.shield_factor", 0.4) : 1), blazes = blazesN >= 0 ? blazesN : Math.ceil(Math.max(0, rodsLeft) / Weights.w("blaze.rods_per_kill", 0.5));
        var fd = g.getFoodData(); double food = fed ? 20 : fd.getFoodLevel(), sat = fed ? Weights.w("food.fed_saturation", 5) : fd.getSaturationLevel();
        return blazes * (kill + Decide.hpS(g, health, 0, hits * perHit, perHit, (int) Math.ceil(hits) + 2, food, sat));
    }

    public static String blazeRods(ClaudeEntity g, ServerPlayer player, ServerLevel level, Skills.Step st) {
        String target = st.target().toLowerCase();
        int have = rods(g);
        int[] run = runs().get(g.getUUID());
        int goal;
        int at = target.indexOf('@');
        if (at >= 0) { try { goal = Integer.parseInt(target.substring(at + 1)); } catch (NumberFormatException e) { goal = have + st.count(); } }
        else { goal = have + Math.max(1, st.count()); run = null; } // a new ask: the count is what's to come in on top of what he has
        if (run == null || run[0] != goal) { run = new int[] {goal, 0, have, 0, 0}; runs().put(g.getUUID(), run); riskSaid().remove(g.getUUID()); } // {goal, goes since a rod, rods then, walks, shield asked}
        if (have >= goal) { ClaudeMod.LOGGER.info("[Claude fight] {} blaze rods of {}: done (FortressFight.blazeRods)", have, goal); runs().remove(g.getUUID()); covers().remove(g.getUUID()); return "done: " + have + " blaze rods (" + goal + " wanted)"; }
        if (have > run[2]) { run[1] = 0; run[2] = have; } else run[1]++;
        if (run[1] > 40) { runs().remove(g.getUUID()); return "failed: 40 goes at the blazes since the last rod (" + have + " of " + goal + "): not getting them here as things stand"; }
        Skills.Step again = new Skills.Step("blaze_rods", "blaze_rod@" + goal, st.count(), st.where());
        // weapon and food: no fight without a blade or something to regen with (the combat rule; their ask overrides the food part, said once)
        boolean blade = g.inventory.count(s -> s.getItem() instanceof net.minecraft.world.item.SwordItem || s.getItem() instanceof net.minecraft.world.item.AxeItem) > 0;
        if (!blade) return "failed: no sword or axe for the blazes (20 health each: an iron sword or a diamond axe)";
        int food = com.claudemod.director.Kit.food(g);
        if (food == 0 && !g.commandActive) return "failed: no food to regen on between volleys: food first";
        // a shield first when he can make one (6 planks, an iron ingot): it takes the whole volley (FortressFight.blazeRods)
        boolean shield = g.getOffhandItem().is(Items.SHIELD) || g.inventory.count(s -> s.is(Items.SHIELD)) > 0;
        int iron = g.inventory.count(s -> s.is(Items.IRON_INGOT)), planks = g.inventory.count(s -> s.is(net.minecraft.tags.ItemTags.PLANKS)), logs = g.inventory.count(s -> s.is(net.minecraft.tags.ItemTags.LOGS));
        if (!shield && iron >= 1 && planks + logs * 4 >= 6 && run[4] == 0) {
            run[4] = 1; // (once a run: a craft that can't be done here isn't asked for again)
            g.skillQueue.addFirst(again); g.skillQueue.addFirst(new Skills.Step("craft", "shield", 1));
            ClaudeMod.LOGGER.info("[Claude fight] no shield, {} iron and {} planks' worth on him: a shield first, it blocks every fireball of a volley (FortressFight.blazeRods)", iron, planks + logs * 4);
            return "a shield first (6 planks and an iron ingot): it takes the blazes' fireballs";
        }
        { // fight now or get ready first, priced (D2D 04:52 World 17: no armor, every fireball the full 5, 20 -> 3 in 18 s, burned to death; the user's rule: a price, never a gate)
          double tough = g.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR_TOUGHNESS); int left = goal - have;
          double now = fightS(g, g.getHealth(), g.getArmorValue(), tough, shield, false, left);
          boolean inVain = HuntPatrol.readiedInVain(g); double readyArmor = inVain ? g.getArmorValue() : Math.max(g.getArmorValue(), Weights.w("blaze.ready_armor", 15)); boolean readyShield = shield || !inVain;
          double ready = fightS(g, 20, readyArmor, tough, readyShield, true, left);
          var portal = PortalMap.net(g).portals.stream().filter(p0 -> PortalMap.NETHER.equals(p0.dim)).min(java.util.Comparator.comparingDouble(p0 -> p0.at.distSqr(g.blockPosition()))).orElse(null);
          double trip = portal == null ? Weights.w("pearls.no_portal_s", 1800) : 2 * (Math.sqrt(portal.at.distSqr(g.blockPosition())) * PortalMap.NETHER_OFF_ROAD / Decide.sprint() + Weights.w("highway.portal_s", 8));
          var pick = Decide.choose(g, "blaze.ready", left + " rod(s) to go", true, List.of(
              Decide.Option.of("fight now", now, (int) g.getHealth() + " HP, armor " + g.getArmorValue() + (shield ? ", a shield" : ", no shield")),
              Decide.Option.of("get ready first", trip + Weights.w("blaze.ready_s", 240) + ready, "through the portal and back ~" + Math.round(trip) + " s, gear_up's kit, then the fight at 20 HP, armor " + (int) readyArmor + (readyShield ? ", a shield" : "") + (inVain ? " (no armor came of the last go)" : ""))));
          if (pick != null && pick.name().equals("get ready first")) {
              HuntPatrol.readying(g); g.skillQueue.addFirst(again); g.skillQueue.addFirst(new Skills.Step("enter_portal", "", 1)); g.skillQueue.addFirst(new Skills.Step("gear_up", "", 1)); g.skillQueue.addFirst(new Skills.Step("enter_portal", "", 1));
              return "getting ready first: through the portal for armor and a shield (the blazes' fireballs at armor " + g.getArmorValue() + " priced ~" + Math.round(now) + " s against ~" + Math.round(trip + Weights.w("blaze.ready_s", 240) + ready) + " s geared)";
          } }
        observe(g, level);
        List<Blaze> bl = blazesNear(g, level, g.blockPosition(), 32);
        bl.removeIf(b -> !g.hasLineOfSight(b) && b.distanceTo(g) > 12); // in view, or close enough he hears it
        BlockPos spawner = blazeSpawner(g, level, 20);
        if (bl.isEmpty() && spawner == null) {
            // none in view: walk the fortress he has seen, piece by piece (each piece once a while), looking
            if (FortressScout.onFortress(g)) { BlockPos step = FortressScout.deckStep(g, level); long[] last = blazeLast().get(g.getUUID()); // where the last blaze was, or along the deck into pieces he hasn't seen, priced (D2D 07:20 11-07: one blaze killed, 0/6, he walked the deck to 297,65,485 into a wither skeleton and piglins and died; a player waits where the blazes come from)
              if (last != null && g.tickCount - last[1] < 20 * Weights.w("blaze.wait_memory_s", 600)) { BlockPos lastAt = BlockPos.of(last[0]); double age = (g.tickCount - last[1]) / 20.0, far = Math.sqrt(lastAt.distSqr(g.blockPosition()));
                double waitS = far / Decide.sprint() + Weights.w("blaze.wait_s", 30) * (1 + last[2] * 10 / Weights.w("blaze.spawner_max_s", 40)) * (1 + age / 300); // (a spawner sends more every 10-40 s while he's within 16: the longer he's waited past that with none, and the older the sighting, the less likely one's there)
                double lookS = step == null ? Double.MAX_VALUE : Math.sqrt(step.distSqr(g.blockPosition())) / Decide.sprint() + Weights.w("fortress.look_risk_s", 45); // (pieces he hasn't seen: wither skeletons, a skeleton group, piglins, met out of cover)
                var pick = Decide.choose(g, "blaze.look", "no blaze in view; the last one " + Math.round(age) + " s ago at " + lastAt.toShortString() + ", " + last[2] + " wait(s) since", true, List.of(
                    Decide.Option.of("wait where they were", waitS, Math.round(far) + " blocks there, then the next one's spawn"), Decide.Option.of("walk the deck to look", lookS, step == null ? "no deck he hasn't seen" : "to " + step.toShortString() + ", pieces he hasn't seen")));
                if (pick != null && pick.name().startsWith("wait")) { last[2]++; g.tasks.add(far > 6 ? new Task.GoTo(lastAt, 4.0) : new Task.Wait(20 * 10, false)); g.skillQueue.addFirst(again); return "no blaze in view: waiting where the last one was (" + lastAt.toShortString() + ", " + Math.round(age) + " s ago) for the next (" + have + "/" + goal + " rods)"; } }
              if (step != null) { run[3]++; g.tasks.add(new Task.GoTo(step, 1.0)); g.skillQueue.addFirst(again); return "no blaze in view: along the fortress deck to " + step.toShortString() + " to look (" + have + "/" + goal + " rods)"; } } // (on its deck: along it, never back down to a box's middle)
            BlockPos best = null; double bd = Double.MAX_VALUE;
            for (var s : Perception.sightings(g)) {
                if (s.dim() != level.dimension() || !s.id().contains("fortress")) continue;
                BlockPos c = s.box().getCenter(); if (g.attempts.within(Attempts.Kind.WALK, c, g.tickCount, 20 * 300)) continue;
                double d = c.distSqr(g.blockPosition()); if (d < bd && d > 6 * 6) { bd = d; best = c.immutable(); }
            }
            if (best == null && com.claudemod.director.Structures.testPiece != null && !g.attempts.within(Attempts.Kind.WALK, com.claudemod.director.Structures.testPiece, g.tickCount, 20 * 300)) best = com.claudemod.director.Structures.testPiece;
            if (best == null) { ClaudeMod.LOGGER.info("[Claude fight] no blaze or blaze spawner in view, every fortress piece he has seen walked lately (FortressFight.blazeRods)"); runs().remove(g.getUUID()); return "failed: no blaze or blaze spawner in view, and no part of the fortress i've seen left to look in (find the fortress's spawner room first)"; }
            g.attempts.fail(Attempts.Kind.WALK, best, g.tickCount); run[3]++;
            BlockPos deck = deckCell(level, best); // (onto its deck, the bricks he can stand on: the box's middle is in its wall or under it, and the route there went by the lava sea)
            g.tasks.add(new Task.GoTo(deck != null ? deck : best, deck != null ? 1.5 : 3));
            g.skillQueue.addFirst(again);
            ClaudeMod.LOGGER.info("[Claude fight] no blaze in view: walking the fortress to its piece at {} to look (FortressFight.blazeRods)", best.toShortString());
            return "no blaze in view: walking the fortress to " + best.toShortString() + " to look for them (" + have + "/" + goal + " rods)";
        }
        { // blazes he can't reach and nothing to shoot them with (World 15 01:27: 4 blazes hovering 7-14 off a hall, 5 min, 0 rods, not one swing): vanilla's blaze never closes on a player it sees (it hangs back and shoots; out of sight it drifts in 5 ticks and stops), so a sword gets one only at its spawner, where they appear within 4 of the cage
            boolean ranged = g.inventory.count(s0 -> s0.is(Items.BOW)) > 0 && g.inventory.count(s0 -> s0.is(Items.ARROW)) > 0;
            boolean shieldOn = g.getOffhandItem().is(Items.SHIELD) || g.inventory.count(s0 -> s0.is(Items.SHIELD)) > 0;
            boolean reachable = false; for (Blaze b0 : bl) if (b0.getBoundingBox().minY <= g.getY() + 3.2 && b0.distanceTo(g) <= 14 && (shieldOn || !(b0.isOnFire() && !b0.hasLineOfSight(g)))) reachable = true; // (lit and out of his sight, no shield: vanilla's blaze stays charged till it sees him, its volley the moment it does, so he can't close on it unhit; it counts as out of reach. World 15: "waiting at cover: lit 999" and "drawing its shots" loops at -98 75 61)
            int nowT = g.getServer() == null ? g.tickCount : g.getServer().getTickCount(); // (server ticks: his own tickCount starts at 0 again after every portal trip, and a time kept from before it read as never passing)
            if (Boolean.getBoolean("claude.debugBlazes")) for (Blaze b0 : bl) ClaudeMod.LOGGER.info("[Claude debug] blaze {} at {} lit {} sees him {} minY {} his y {} dist {}", b0.getId(), b0.blockPosition().toShortString(), b0.isOnFire(), b0.hasLineOfSight(g), String.format("%.1f", b0.getBoundingBox().minY), String.format("%.1f", g.getY()), String.format("%.1f", b0.distanceTo(g)));
            Integer since = outOfReach().get(g.getUUID());
            if (ranged || reachable || bl.isEmpty() || spawner != null && spawner.distSqr(g.blockPosition()) <= 8 * 8) outOfReach().remove(g.getUUID());
            else if (since == null || since > nowT) outOfReach().put(g.getUUID(), nowT);
            else if (nowT - since >= 20 * 15) {
                outOfReach().remove(g.getUUID());
                BlockPos cage = spawner != null ? spawner : Perception.nearestSign(g, g.blockPosition(), 128, "a mob spawner");
                String why = bl.size() + " blaze(s) out of reach 15 s (they hang back and shoot at a player they see) and no bow and arrows on me";
                if (cage != null) {
                    BlockPos by = null; double bd0 = Double.MAX_VALUE; // (a cell beside the cage he can stand in: fresh blazes appear within 4 of it, in sword reach)
                    for (BlockPos q : BlockPos.betweenClosed(cage.offset(-3, -2, -3), cage.offset(3, 1, 3))) { if (q.distSqr(cage) < 2 * 2 || !stand(level, q)) continue; double dq = q.distSqr(g.blockPosition()); if (dq < bd0) { bd0 = dq; by = q.immutable(); } }
                    if (by != null) { g.tasks.add(new Task.GoTo(by, 1.0)); g.skillQueue.addFirst(again);
                        ClaudeMod.LOGGER.info("[Claude fight] {}: to the blaze spawner at {}, beside it at {}; new ones appear within 4 of it, in reach (FortressFight.blazeRods)", why, cage.toShortString(), by.toShortString());
                        return "the blazes here stay out of my reach and i've no bow: to their spawner at " + cage.toShortString() + ", where new ones appear right by me (" + have + "/" + goal + " rods)"; }
                }
                if (FortressScout.onFortress(g)) { BlockPos step = FortressScout.deckStep(g, level); if (step != null) { g.tasks.add(new Task.GoTo(step, 1.0)); g.skillQueue.addFirst(again);
                    ClaudeMod.LOGGER.info("[Claude fight] {}, no spawner known: along the deck to {} for the spawner room (FortressFight.blazeRods)", why, step.toShortString());
                    return "the blazes here stay out of my reach and i've no bow: looking along the fortress for their spawner (" + have + "/" + goal + " rods)"; } }
                ClaudeMod.LOGGER.info("[Claude fight] {}, no spawner known and no deck left to look along: a bow is what this needs (FortressFight.blazeRods)", why);
                runs().remove(g.getUUID());
                return "failed: the blazes stay out of sword reach, I know of no spawner to fight them at, and I've no bow and arrows (a bow, or their spawner, is what this needs)";
            }
        }
        if (bl.size() >= 2 && g.getHealth() < g.getMaxHealth() && spawner != null && swarmBackOut(g, bl.size(), shield, food > 0, spawner)) { // swarming and he's losing: a spawner only works with a player within 16 (torches don't stop a blaze spawner: blazes spawn in any light), so out of its range and heal; priced (blaze.swarm), was "4+ blazes at health 12 or less"
            g.skillQueue.addFirst(again); g.tasks.add(new Task.Flee(20 * 12));
            ClaudeMod.LOGGER.info("[Claude fight] {} blazes out and health {}: backing out past the spawner's 16 blocks to heal (it stops spawning without a player in range) (FortressFight.blazeRods)", bl.size(), (int) g.getHealth());
            return "too many blazes (" + bl.size() + ") at health " + (int) g.getHealth() + ": backing out of the spawner's range to heal, then back in";
        }
        { // rods on the ground in sight: picked up between volleys (stage 556, live blazes: 16 killed, mostly by arrow 10-20 off, the rods lay on the deck out of the kill's 8-block sweep and he fought on: 2 of 3 in 180 s)
            var due = volleyDue(g, level);
            int rodsAbout = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, g.getBoundingBox().inflate(24, 8, 24), x -> x.isAlive() && x.getItem().is(Items.BLAZE_ROD)).size();
            if (rodsAbout > 0 && (due != null || g.getHealth() < 10)) ClaudeMod.LOGGER.info("[Claude fight] rods {}/{}: {} rod(s) on the ground within 24, not now: {} (FortressFight.blazeRods)", have, goal, rodsAbout, due != null ? "a volley due from the " + due.getType().getDescription().getString() + " " + (int) due.distanceTo(g) + " off" : "health " + (int) g.getHealth());
            if (due == null && g.getHealth() >= 10) {
                net.minecraft.world.entity.item.ItemEntity rod = null;
                for (var it : level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, g.getBoundingBox().inflate(24, 8, 24), x -> x.isAlive() && x.getItem().is(Items.BLAZE_ROD) && level.getFluidState(x.blockPosition()).isEmpty())) {
                    if (!clear(level, g.getEyePosition(), it.position().add(0, 0.15, 0), g) && it.distanceTo(g) > 4) continue; // (a line from his eyes: the rod's own cell is air, a block-sight check never passes)
                    if (rod == null || it.distanceToSqr(g) < rod.distanceToSqr(g)) rod = it; }
                if (rod == null && rodsAbout > 0) ClaudeMod.LOGGER.info("[Claude fight] rods {}/{}: {} rod(s) on the ground within 24, none in sight on dry ground (FortressFight.blazeRods)", have, goal, rodsAbout);
                if (rod != null) { var path = g.getNavigation().createPath(rod.blockPosition(), 1);
                    if (path == null || !path.canReach()) ClaudeMod.LOGGER.info("[Claude fight] rods {}/{}: the rod {} off at {} has no walk to it (FortressFight.blazeRods)", have, goal, (int) rod.distanceTo(g), rod.blockPosition().toShortString());
                    if (path != null && path.canReach()) {
                        g.tasks.add(new Task.GoTo(rod.blockPosition(), 1.0)); g.tasks.add(new Task.Pickup(60)); g.skillQueue.addFirst(again);
                        ClaudeMod.LOGGER.info("[Claude fight] rods {}/{}: a rod on the ground {} off at {}, no volley due: picking it up first (FortressFight.blazeRods)", have, goal, (int) rod.distanceTo(g), rod.blockPosition().toShortString());
                        return "blaze rods " + have + "/" + goal + ": picking up a rod " + (int) rod.distanceTo(g) + " off between volleys"; } }
            }
        }
        LivingEntity foe = bl.stream().filter(g::hasLineOfSight).min(java.util.Comparator.comparingDouble(b -> b.distanceToSqr(g))).orElse(null); // (one he can see: World 15 04:01, the nearest was behind basalt, the attack let it be at once and he re-picked it 5 times standing still)
        if (foe == null && !bl.isEmpty()) { // all of them behind rock: out of sight a blaze drifts in and stops, so he holds here till one comes round into view
            for (Blaze b0 : bl.stream().sorted(java.util.Comparator.comparingDouble(x -> x.distanceToSqr(g))).limit(3).toList()) { // a thin wall of soft rock to the nearest: a window cut through it, like a player (live 17:40-17:42 11-07: 3-8 blazes behind 1-2 netherrack, 75 s "waiting for one to come into view", then 40 goes and the job failed)
                List<BlockPos> win = windowTo(level, g, b0); if (win == null) continue;
                for (BlockPos q : win) g.tasks.add(new Task.Break(q)); g.skillQueue.addFirst(again);
                ClaudeMod.LOGGER.info("[Claude fight] rods {}/{}: {} blaze(s) about, none in sight: a window through {} block(s) of {} at {} toward the blaze {} off (FortressFight.blazeRods)", have, goal, bl.size(), win.size(), level.getBlockState(win.get(0)).getBlock().getName().getString(), win.get(0).toShortString(), (int) b0.distanceTo(g));
                return "blaze rods " + have + "/" + goal + ": cutting a window through " + win.size() + " block(s) toward a blaze " + (int) b0.distanceTo(g) + " off";
            }
            g.tasks.add(new Task.Wait(40, false)); g.skillQueue.addFirst(again);
            ClaudeMod.LOGGER.info("[Claude fight] rods {}/{}: {} blaze(s) about, none in sight (all behind rock): waiting for one to come into view (FortressFight.blazeRods)", have, goal, bl.size());
            return "blaze rods " + have + "/" + goal + ": " + bl.size() + " blaze(s) behind the rock, waiting for one to come into sight";
        }
        Cover c = foe != null ? coverFor(g, level, foe) : null;
        if (foe == null) { // only the spawner: wait by it, out of sight, for the next blazes (it spawns 1-4 every 10-40 s with him within 16)
            var fake = net.minecraft.world.entity.EntityType.BLAZE.create(level);
            if (fake != null) { fake.moveTo(spawner.getX() + 0.5, spawner.getY() + 1, spawner.getZ() + 0.5); c = covers().get(g.getUUID()); if (c == null || !c.kind().equals("blaze")) { c = pickBlaze(g, level, fake); if (c != null) covers().put(g.getUUID(), c); } fake.discard(); }
        }
        if (!riskSaid().containsKey(g.getUUID())) {
            riskSaid().put(g.getUUID(), g.tickCount);
            String line = riskLine(g, c);
            ClaudeMod.LOGGER.info("[Claude fight] the risk, said once: \"{}\" (FortressFight.blazeRods)", line);
            if (player != null) com.claudemod.director.ClaudeChat.say(player, line, "blaze risk");
        }
        if (c != null && !at(g, c.cover()) && !g.blockPosition().closerThan(c.cover(), 1.5)) g.tasks.add(new Task.GoTo(c.cover(), 0.4));
        if (foe != null) {
            g.tasks.add(new Task.Attack(foe.getUUID(), 20 * 60));
            g.skillQueue.addFirst(again);
            ClaudeMod.LOGGER.info("[Claude fight] rods {}/{}: the {} blaze(s) in view, the nearest {} off; {} (FortressFight.blazeRods)", have, goal, bl.size(), (int) foe.distanceTo(g), c == null ? "no cover found: shield and timing in the open" : "from " + c.how());
            return "blaze rods " + have + "/" + goal + ": fighting the blaze " + (int) foe.distanceTo(g) + " off " + (c == null ? "in the open, shield up for the volleys" : "from cover at " + c.cover().toShortString());
        }
        g.tasks.add(new Task.Wait(60, false));
        g.skillQueue.addFirst(again);
        ClaudeMod.LOGGER.info("[Claude fight] rods {}/{}: the spawner at {} and no blaze out yet: waiting {} for them (FortressFight.blazeRods)", have, goal, spawner.toShortString(), c == null ? "by it" : "in cover at " + c.cover().toShortString());
        return "blaze rods " + have + "/" + goal + ": waiting by the spawner at " + spawner.toShortString() + " for the next blazes";
    }

    private static Map<UUID, int[]> standSaid; // per bot: {tick, 1 = fight here} of the last walk-or-fight pick (static: fields added live start null)
    /**
     * A wither skeleton in its sword's reach and his cover a walk off: the walk priced against the fight where he stands (D2D, live death
     * 14:28 11-08: at 15 HP with Wither on, the gap 12 off, the skeleton 1 off keeping pace; sprinting there with his shield down he
     * took 5 hits and never swung). The walk: its hits on his back the whole way (it keeps pace), then the kill from the gap. Here: the
     * kill with sword and shield, its hits while he swings. True: fight it here. Picked again every second.
     */
    public static boolean standAndFight(ClaudeEntity g, LivingEntity mob, BlockPos cover) {
        if (!(mob instanceof WitherSkeleton)) return false;
        double reach = Weights.w("wither.keep_pace_reach", 3.5);
        if (mob.distanceTo(g) > reach) return false;
        if (standSaid == null) standSaid = new HashMap<>(); int[] last = standSaid.get(g.getUUID());
        if (last != null && g.tickCount - last[0] < 20 && g.tickCount >= last[0]) return last[1] == 1;
        double far = walkBlocks(g, cover); // the way his feet take, not the crow's: live 07:20 11-09 (death 220) the gap was 2.2 off straight but 2 up with no step to it, so "near" skipped this pricing and he walked at it shield up, 0 swings, 13 -> 0
        if (far < Weights.w("wither.gap_near_blocks", 2.5)) { standSaid.put(g.getUUID(), new int[] {g.tickCount, 0}); return false; }
        double[] m = HuntPatrol.melee(g); double killS = Math.ceil(mob.getHealth() / m[0]) * m[1];
        double rate = Weights.w("wither.hits_per_s", 0.8), dmg = Weights.w("wither.hit_hp", 8), armor = g.getArmorValue(), tough = g.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR_TOUGHNESS);
        double perHit = dmg * (1 - Math.min(20, Math.max(armor / 5, armor - dmg / (2 + tough / 4))) / 25) + Weights.w("wither.wither_hp", 2);
        boolean shield = g.getOffhandItem().is(Items.SHIELD) && !g.getCooldowns().isOnCooldown(Items.SHIELD);
        double walkS = far / Decide.sprint(), walkHits = Math.ceil(walkS * rate), hereHits = killS * rate * (shield ? Weights.w("wither.shield_factor", 0.5) : 1);
        double walk = walkS + killS + Decide.hpS(g, g.getHealth(), 0, walkHits * perHit, perHit, (int) walkHits + 1), here = killS + Decide.hpS(g, g.getHealth(), 0, hereHits * perHit, perHit, (int) Math.ceil(hereHits) + 1);
        var pick = Decide.choose(g, "wither.cover_walk", "a wither skeleton " + String.format("%.1f", mob.distanceTo(g)) + " off, his gap " + (far >= 64 ? "with no walk to it" : Math.round(far) + " off on foot") + ", health " + (int) g.getHealth(), last == null || last[1] != (here < walk ? 1 : 0), List.of(
            Decide.Option.of("walk to the gap", walk, "~" + String.format("%.1f", walkS) + " s with it on his back, ~" + Math.round(walkHits) + " hit(s) of ~" + Math.round(perHit) + ", then the kill from the gap"),
            Decide.Option.of("fight it here", here, "~" + Math.round(killS) + " s of swings" + (shield ? " behind his shield" : "") + ", ~" + String.format("%.1f", hereHits) + " hit(s) of ~" + Math.round(perHit))));
        boolean fight = pick != null && pick.name().equals("fight it here");
        standSaid.put(g.getUUID(), new int[] {g.tickCount, fight ? 1 : 0});
        return fight;
    }

    /** Blocks of walking to the cell on his own walker (no digging, no blocks put down: the cover walk is a plain walk), or 64 when there's no such way. */
    static double walkBlocks(ClaudeEntity g, BlockPos to) {
        BlockPos me = feet(g); if (me.equals(to)) return 0;
        var p = com.claudemod.entity.nav.ActionPath.plan(g, (ServerLevel) g.level(), me, to, 0.5, false, 400, com.claudemod.entity.nav.ActionPath.Mode.NO_DIG, null, 0);
        if (p == null) return 64;
        double d = 0; BlockPos at = me;
        for (var a : p.acts()) { BlockPos n = a instanceof com.claudemod.entity.nav.ActionPath.Act.Walk w ? w.feet() : a instanceof com.claudemod.entity.nav.ActionPath.Act.Leap l ? l.land() : null; if (n == null) continue; d += Math.sqrt(at.distSqr(n)) + (n.getY() > at.getY() ? 0.5 : 0); at = n; } // (a step up is a jump: slower than the flat)
        return Math.max(d, Math.sqrt(me.distSqr(to)));
    }

    // ------------------------------------------------------------------ mob facts

    /** One table: exact vanilla facts (1.21) for the dragon-path mobs, keyed by entity id. */
    static final Map<String, String> FACTS = Map.of(
            "blaze", "blaze: 20 health, fire-proof, floats; it shoots only with you in its line of sight: it glows ~3 s (charging), then 3 small fireballs 0.3 s apart (5 damage and 5 s of fire each), then ~5 s quiet; a shield blocks the fireballs, a corner or doorway breaks its sight and stops the volley, the quiet 5 s are when you close in; a snowball does 3, water hurts it; drops 0-1 blaze rod, only when a player kills it (Looting adds 1 a level); fortress spawners make them (1-4 every 10-40 s while you're within 16, up to 6 about) whatever the light, and they spawn on fortress ground",
            "wither_skeleton", "wither skeleton: 20 health, 2.4 tall: it can't get into a 2-high gap, and its sword reaches only about 0.8 past its sides, so from 2 cells into a 2-high gap you hit it and it can't hit you; on Normal its stone sword does 8 and puts Wither on you for 10 s (1 damage every 2 s, it can kill; it outruns slow regen, so heal after it wears off; milk clears it); drops coal, bones and 2.5% a skull (player kills; Looting +1% a level); only in fortresses",
            "ghast", "ghast: 10 health, flies, shoots an exploding fireball from up to ~64 blocks when it sees you; hit the fireball (any swing or arrow) and it flies back: a reflected one kills the ghast outright; drops a ghast tear (often into lava) and gunpowder; cover it can't see through stops it",
            "piglin", "piglin: leaves you alone while you wear at least one gold armor piece; turns hostile, with every piglin near, if you hit one, or open or break a chest or barrel, or break a gold block, nether gold ore or gilded blackstone where one can see it; give it gold (drop an ingot) and after ~6 s of looking at it, it barters: ender pearls about 2% a trade (2-4), obsidian ~9%, fire resistance potions ~2% each (plain and splash), string, quartz, soul sand; adults bring their gold armor and crossbows; a piglin brute ignores gold and always attacks",
            "enderman", "enderman: 40 health, 2.9 tall (a 2-high gap keeps it out), hits for 7; neutral until you look at its head or hit it; teleports away from arrows and water; drops 0-1 ender pearl (Looting adds 1 a level); many in warped forests and soul sand valleys");
    static final String DESPAWN = "despawning (every hostile, spawner mobs too): gone at once past 128 blocks from every player, by chance past 32 once it has been idle 30 s, never within 32 of a player; a name tag or picked-up item keeps it";

    /** The facts for the mobs known within 32 of him, or named in their ask (null: none). Shown so neither chat nor planner guesses. */
    public static String mobFacts(ClaudeEntity g, String ask) {
        if (g == null) return "";
        String a = ask == null ? "" : ask.toLowerCase();
        java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>();
        for (var e : Perception.known(g, LivingEntity.class, g.getBoundingBox().inflate(32), Entity::isAlive)) {
            String id = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
            if (id.equals("piglin_brute")) id = "piglin";
            if (FACTS.containsKey(id)) keys.add(id);
        }
        for (String k : FACTS.keySet()) if (a.contains(k.replace('_', ' ')) || a.contains(k) || (k.equals("wither_skeleton") && a.contains("wither")) || (k.equals("enderman") && (a.contains("endermen") || a.contains("ender man")))) keys.add(k);
        boolean despawnAsk = a.contains("despawn");
        if (keys.isEmpty() && !despawnAsk) return "";
        StringBuilder sb = new StringBuilder("MOB FACTS (exact vanilla rules: say these, never guess): ");
        for (String k : keys) sb.append(FACTS.get(k)).append(". ");
        sb.append(DESPAWN).append(".\n");
        return sb.toString();
    }
}
