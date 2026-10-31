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
    private static Map<UUID, int[]> runs() { if (runs == null) runs = new HashMap<>(); return runs; }

    public static void reset(ClaudeEntity g) { covers().remove(g.getUUID()); runs().remove(g.getUUID()); riskSaid().remove(g.getUUID()); }

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
        ClaudeMod.LOGGER.info("[Claude fight] cover for the {}: {} (FortressFight.coverFor)", foe.getType().getDescription().getString(), c.how());
        return c;
    }

    static boolean stillGood(ClaudeEntity g, ServerLevel level, Cover c, LivingEntity foe) {
        if (!stand(level, c.cover())) return false;
        if (c.kind().equals("wither")) return witherSafe(level, c.cover());
        for (Blaze b : blazesNear(g, level, c.cover(), 24)) if (sees(level, b, c.cover())) return false;
        return true;
    }

    /** The blazes about that he knows of: in his line of sight, or within 16 (their breathing and crackle carry), or already on his senses' record. */
    static List<Blaze> blazesNear(ClaudeEntity g, ServerLevel level, BlockPos at, int r) {
        java.util.Set<Blaze> known = new java.util.HashSet<>(Perception.known(g, Blaze.class, new AABB(at).inflate(r), Entity::isAlive));
        return level.getEntitiesOfClass(Blaze.class, new AABB(at).inflate(r), b -> b.isAlive() && (known.contains(b) || b.distanceTo(g) <= 16 || g.hasLineOfSight(b)));
    }
    static boolean sees(ServerLevel level, LivingEntity mob, BlockPos cell) {
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
    static Cover pickBlaze(ClaudeEntity g, ServerLevel level, LivingEntity foe) {
        BlockPos spawner = blazeSpawner(g, level, 20);
        List<Vec3> eyes = new ArrayList<>(); List<Blaze> bl = blazesNear(g, level, g.blockPosition(), 24); if (foe instanceof Blaze b0 && !bl.contains(b0)) bl.add(b0);
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
        for (int i = 0; i < Math.min(6, cands.size()); i++) {
            Cand k = cands.get(i);
            if (!k.c().equals(me)) { var path = g.getNavigation().createPath(k.c(), 0); if (path == null || !path.canReach()) continue; }
            String shape = k.walls() >= 3 ? "a nook" : k.walls() == 2 ? "a doorway's side" : "a corner";
            return new Cover(k.c(), k.peek(), spawner != null ? spawner : foe.blockPosition(), "blaze",
                    shape + " at " + k.c().toShortString() + " out of sight of " + bl.size() + " blaze(s)" + (spawner != null ? " and the spawner at " + spawner.toShortString() : "") + ", stepping out to " + k.peek().toShortString() + " (" + (int) k.da() + " off, " + cands.size() + " spots looked at)", g.tickCount);
        }
        ClaudeMod.LOGGER.info("[Claude fight] no cover from the blazes within 10 ({} spots out of sight, none walkable or with a step that sees them) (FortressFight.pickBlaze)", cands.size());
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
        for (int i = 0; i < Math.min(6, cands.size()); i++) {
            Cand k = cands.get(i);
            if (!k.c().equals(me)) { var path = g.getNavigation().createPath(k.c(), 0); if (path == null || !path.canReach()) continue; }
            return new Cover(k.c(), k.front(), foe.blockPosition(), "wither", "a 2-high gap at " + k.c().toShortString() + " (no 3-high room on any side: its sword can't reach in), hitting it at " + k.front().toShortString() + " 2 off (" + cands.size() + " gaps looked at)", g.tickCount);
        }
        ClaudeMod.LOGGER.info("[Claude fight] no 2-high gap within 10 to fight the wither skeleton from ({} out of its reach, none walkable): shield and crits in the open, off at 10 health (FortressFight.pickWither)", cands.size());
        return null;
    }

    // ------------------------------------------------------------------ blaze volleys

    /** What he sees of each blaze's glow: the tick it lit up (first seen lit: counted as half-way through its charge). */
    public static void observe(ClaudeEntity g, ServerLevel level) {
        for (Blaze b : blazesNear(g, level, g.blockPosition(), 32)) {
            if (!g.hasLineOfSight(b)) continue;
            if (b.isOnFire()) chargedSince().putIfAbsent(b.getUUID(), g.tickCount - 40); // (Blaze.isOnFire is its charged flag: the glow and smoke a player sees)
            else chargedSince().remove(b.getUUID());
        }
        if (chargedSince().size() > 64) chargedSince().clear();
    }
    /** Ticks since that blaze lit up, or -1 when it isn't glowing. */
    public static int chargeAge(ClaudeEntity g, Blaze b) { if (!b.isOnFire()) return -1; Integer t = chargedSince().get(b.getUUID()); return t == null ? 999 : g.tickCount - t; }
    /** First lit this tick (the start of its 60-tick charge, seen). */
    public static void sawLight(ClaudeEntity g, Blaze b) { chargedSince().put(b.getUUID(), g.tickCount); }

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
        observe(g, level);
        List<Blaze> bl = blazesNear(g, level, g.blockPosition(), 32);
        bl.removeIf(b -> !g.hasLineOfSight(b) && b.distanceTo(g) > 12); // in view, or close enough he hears it
        BlockPos spawner = blazeSpawner(g, level, 20);
        if (bl.isEmpty() && spawner == null) {
            // none in view: walk the fortress he has seen, piece by piece (each piece once a while), looking
            BlockPos best = null; double bd = Double.MAX_VALUE;
            for (var s : Perception.sightings(g)) {
                if (s.dim() != level.dimension() || !s.id().contains("fortress")) continue;
                BlockPos c = s.box().getCenter(); if (g.attempts.within(Attempts.Kind.WALK, c, g.tickCount, 20 * 300)) continue;
                double d = c.distSqr(g.blockPosition()); if (d < bd && d > 6 * 6) { bd = d; best = c.immutable(); }
            }
            if (best == null && com.claudemod.director.Structures.testPiece != null && !g.attempts.within(Attempts.Kind.WALK, com.claudemod.director.Structures.testPiece, g.tickCount, 20 * 300)) best = com.claudemod.director.Structures.testPiece;
            if (best == null) { ClaudeMod.LOGGER.info("[Claude fight] no blaze or blaze spawner in view, every fortress piece he has seen walked lately (FortressFight.blazeRods)"); runs().remove(g.getUUID()); return "failed: no blaze or blaze spawner in view, and no part of the fortress i've seen left to look in (find the fortress's spawner room first)"; }
            g.attempts.fail(Attempts.Kind.WALK, best, g.tickCount); run[3]++;
            g.tasks.add(new Task.GoTo(best, 3));
            g.skillQueue.addFirst(again);
            ClaudeMod.LOGGER.info("[Claude fight] no blaze in view: walking the fortress to its piece at {} to look (FortressFight.blazeRods)", best.toShortString());
            return "no blaze in view: walking the fortress to " + best.toShortString() + " to look for them (" + have + "/" + goal + " rods)";
        }
        if (bl.size() >= 4 && g.getHealth() <= 12 && spawner != null) { // swarming and he's losing: a spawner only works with a player within 16 (torches don't stop a blaze spawner: blazes spawn in any light), so out of its range and heal
            g.skillQueue.addFirst(again); g.tasks.add(new Task.Flee(20 * 12));
            ClaudeMod.LOGGER.info("[Claude fight] {} blazes out and health {}: backing out past the spawner's 16 blocks to heal (it stops spawning without a player in range) (FortressFight.blazeRods)", bl.size(), (int) g.getHealth());
            return "too many blazes (" + bl.size() + ") at health " + (int) g.getHealth() + ": backing out of the spawner's range to heal, then back in";
        }
        LivingEntity foe = bl.isEmpty() ? null : bl.stream().min(java.util.Comparator.comparingDouble(b -> b.distanceToSqr(g))).get();
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

    // ------------------------------------------------------------------ mob facts

    /** One table: exact vanilla facts (1.21) for the dragon-path mobs, keyed by entity id. */
    static final Map<String, String> FACTS = Map.of(
            "blaze", "blaze: 20 health, fire-proof, floats; it shoots only with you in its line of sight: it glows ~3 s (charging), then 3 small fireballs 0.3 s apart (5 damage and 5 s of fire each), then ~5 s quiet; a shield blocks the fireballs, a corner or doorway breaks its sight and stops the volley, the quiet 5 s are when you close in; a snowball does 3, water hurts it; drops 0-1 blaze rod, only when a player kills it (Looting adds 1 a level); fortress spawners make them (1-4 every 10-40 s while you're within 16, up to 6 about) whatever the light, and they spawn on fortress ground",
            "wither_skeleton", "wither skeleton: 20 health, 2.4 tall: it can't get into a 2-high gap, and its sword reaches only about 0.8 past its sides, so from 2 cells into a 2-high gap you hit it and it can't hit you; on Normal its stone sword does 8 and puts Wither on you for 10 s (1 damage every 2 s, it can kill; it outruns slow regen, so heal after it wears off; milk clears it); drops coal, bones and 2.5% a skull (player kills; Looting +1% a level); only in fortresses",
            "ghast", "ghast: 10 health, flies, shoots an exploding fireball from up to ~64 blocks when it sees you; hit the fireball (any swing or arrow) and it flies back: a reflected one kills the ghast outright; drops a ghast tear (often into lava) and gunpowder; cover it can't see through stops it",
            "piglin", "piglin: leaves you alone while you wear at least one gold armor piece; turns hostile if you hit one, open a chest or break gold blocks near them; give it gold (drop an ingot) and after ~6 s of looking at it, it barters: ender pearls about 2% a trade (2-4), obsidian ~9%, fire resistance potions ~2% each (plain and splash), string, quartz, soul sand; adults bring their gold armor and crossbows; a piglin brute ignores gold and always attacks",
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
