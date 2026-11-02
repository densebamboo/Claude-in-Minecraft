package com.claudemod.entity.life;

import com.claudemod.ClaudeMod;
import com.claudemod.entity.ClaudeEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * The skill {@code light_end_portal}: from the stronghold's bricks (where find_stronghold stops) into it, through its corridors
 * to the portal room, the silverfish spawner broken, an eye in every empty frame, the portal lit. He goes by what he sees: the
 * cells he has had in sight are his map of the place, and he walks to the nearest edge of it he hasn't seen past (a doorway,
 * a corridor's far end, stairs) until the frames come into sight. Never through walls: one hole in the wall to get in, no more.
 */
public final class StrongholdRoom {
    static final class Run {
        final Set<Long> known = new HashSet<>();   // cells he could stand in, inside the stronghold, that he has seen
        final Set<Long> failed = new HashSet<>();  // edges he couldn't walk to
        final Set<BlockPos> frames = new HashSet<>();
        final Map<Long, Integer> presses = new HashMap<>(); // button presses per iron door
        final List<BlockPos> trail = new ArrayList<>(); // where he has walked in the stronghold, a point every 4 blocks: his way back, kept with the lit portal
        final Set<UUID> ownFish = new HashSet<>(); // silverfish that came out of the walls by him (a block he broke, a fight's call): no sign of the spawner
        BlockPos spawner, going, door, lastGo, lastFrom, doorFrom, wayIn; int goes, eyesIn, holes, clears, inTries;
    }
    private static Map<UUID, Run> runs; // (static: fields added live start null)
    static Run run(ClaudeEntity g) { if (runs == null) runs = new HashMap<>(); return runs.computeIfAbsent(g.getUUID(), k -> new Run()); }
    public static void reset(ClaudeEntity g) { if (runs != null) runs.remove(g.getUUID()); }
    public static int known(ClaudeEntity g) { return run(g).known.size(); }
    public static int eyesIn(ClaudeEntity g) { return run(g).eyesIn; }

    static boolean open(ServerLevel level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        if (!level.getFluidState(p).isEmpty()) return false;
        return s.getCollisionShape(level, p).isEmpty() || s.getBlock() instanceof DoorBlock;
    }
    /** What a stronghold's floors are made of (its bricks, the library's planks, its stairs and slabs, the frames). */
    static boolean strongholdFloor(BlockState s) {
        String b = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
        return b.contains("stone_brick") || b.startsWith("infested") || b.contains("planks") || b.contains("cobblestone") || b.contains("smooth_stone") || s.is(Blocks.END_PORTAL_FRAME) || s.is(Blocks.IRON_BARS) || b.contains("oak_");
    }
    /** A cell he can stand in inside the stronghold: two open cells over a stronghold floor (never over lava or the portal's pool). */
    static boolean standable(ServerLevel level, BlockPos p) {
        if (!open(level, p) || !open(level, p.above())) return false;
        BlockPos f = p.below(); BlockState fs = level.getBlockState(f);
        if (fs.getCollisionShape(level, f).isEmpty() || !level.getFluidState(f).isEmpty()) return false;
        if (!strongholdFloor(fs)) return false;
        for (int k = 2; k <= 12; k++) { BlockPos u = p.above(k); BlockState us = level.getBlockState(u); // and its roof is the stronghold's too: his own staircase dug down onto its bricks has the rock over it (live 2026-11-02, -864,36,1679: his steps read as its inside, no hole made)
            if (us.getCollisionShape(level, u).isEmpty() && level.getFluidState(u).isEmpty()) continue;
            return strongholdFloor(us) || us.is(Blocks.BOOKSHELF) || us.is(Blocks.COBWEB); }
        return false;
    }
    /** Seen: the line from his eyes to that point meets nothing first, or meets that very block. */
    static boolean sees(ClaudeEntity g, ServerLevel level, Vec3 to, BlockPos block) {
        var hit = level.clip(new ClipContext(g.getEyePosition(), to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, g));
        return hit.getType() == HitResult.Type.MISS || block != null && hit.getBlockPos().equals(block);
    }
    static boolean shutIron(ServerLevel level, BlockPos p) { BlockState s = level.getBlockState(p); return s.is(Blocks.IRON_DOOR) && !s.getValue(DoorBlock.OPEN); }
    static BlockPos feet(ClaudeEntity g) { return BlockPos.containing(g.getX(), g.getY() + 0.2, g.getZ()); }

    /** A look round: the cells, frames and spawner in sight go on his map. */
    static void look(ClaudeEntity g, ServerLevel level, Run r) {
        BlockPos f = feet(g);
        for (BlockPos q : BlockPos.betweenClosed(f.offset(-16, -7, -16), f.offset(16, 5, 16))) {
            BlockState s = level.getBlockState(q);
            if (s.is(Blocks.END_PORTAL) && (PortalMap.endPortal(g) == null || !PortalMap.endPortal(g).closerThan(q, 6))) { PortalMap.noteEnd(g, q, r.wayIn != null ? r.wayIn : EyeHunt.found(g)); PortalMap.noteEndTrail(g, route(r.trail, feet(g))); } // (seen lit: remembered, saved, with the way he came)
            if (s.is(Blocks.END_PORTAL_FRAME) && !r.frames.contains(q) && sees(g, level, Vec3.atCenterOf(q).add(0, 0.3, 0), q)) r.frames.add(q.immutable());
            else if (s.is(Blocks.SPAWNER) && r.spawner == null && sees(g, level, Vec3.atCenterOf(q), q)) { r.spawner = q.immutable(); ClaudeMod.LOGGER.info("[Claude stronghold] a spawner in sight at {} (StrongholdRoom.look)", r.spawner.toShortString()); }
            else if (!r.known.contains(q.asLong()) && !s.is(Blocks.IRON_DOOR) && standable(level, q) && sees(g, level, Vec3.atCenterOf(q), null)) r.known.add(q.asLong()); // (an iron door is never on the map as a way: shut, it's a wall till its button)
        }
    }

    /** The cells he can walk to from where he stands over his map, nearest first (the BFS's order), each with its first-seen parent. */
    static LinkedHashMap<Long, Long> reach(ClaudeEntity g, Run r) {
        ServerLevel level = (ServerLevel) g.level();
        LinkedHashMap<Long, Long> seen = new LinkedHashMap<>(); ArrayDeque<Long> q = new ArrayDeque<>();
        long start = feet(g).asLong(); seen.put(start, start); q.add(start);
        while (!q.isEmpty()) { long c = q.poll(); BlockPos p = BlockPos.of(c);
            for (Direction d : Direction.Plane.HORIZONTAL) for (int dy = -1; dy <= 1; dy++) { long n = p.relative(d).offset(0, dy, 0).asLong();
                if (!seen.containsKey(n) && r.known.contains(n) && !shutIron(level, BlockPos.of(n))) { seen.put(n, c); q.add(n); } } }
        return seen;
    }

    public static String skill(ClaudeEntity g, ServerPlayer player, ServerLevel level, Skills.Step st) {
        if (level.dimension() != Level.OVERWORLD) return "failed: the end portal is in a stronghold, in the Overworld";
        Run r = run(g);
        Skills.Step again = new Skills.Step("light_end_portal", st.target(), st.count(), st.where());
        if (++r.goes > 600) return "failed: 600 goes in the stronghold and the portal isn't lit (" + r.known.size() + " cells seen, " + r.frames.size() + " frames)";
        BlockPos f = feet(g);
        BlockPos lit = PortalMap.endPortal(g);
        if (lit != null && level.getBlockState(lit).is(Blocks.END_PORTAL) || lit != null && !level.isLoaded(lit)) { // lit already (by him, before a restart too): back to it — through his way in and its corridors, the portal itself the guide
            if (lit.distSqr(f) <= 5 * 5) return "done: at the lit end portal, " + lit.toShortString();
            List<BlockPos> tr = PortalMap.endTrail(g);
            if (tr.size() >= 2) { // the way he walked there: the nearest point of it, then on along it toward the portal
                int i = 0; double bd = Double.MAX_VALUE; for (int k = 0; k < tr.size(); k++) { double dd = tr.get(k).distSqr(f); if (dd < bd) { bd = dd; i = k; } }
                BlockPos next = bd > 3 * 3 ? tr.get(i) : tr.get(Math.min(tr.size() - 1, i + 1));
                if (bd <= 3 * 3 && i + 2 < tr.size() && tr.get(i + 2).distSqr(f) <= 8 * 8) next = tr.get(i + 2);
                if (!next.equals(r.lastGo) || !f.equals(r.lastFrom)) { r.lastGo = next; r.lastFrom = f; r.going = null; g.tasks.add(new Task.GoTo(next, 0.8)); g.skillQueue.addFirst(again); return "back to the lit end portal the way I came: on to " + next.toShortString() + " (" + (tr.size() - 1 - i) + " points to go)"; }
                ClaudeMod.LOGGER.info("[Claude stronghold] the way I came: the walk to {} ended where it began, at {}: by my map from here (StrongholdRoom)", next.toShortString(), f.toShortString());
            }
            BlockPos in = PortalMap.endWayIn(g);
            if (level.isLoaded(lit)) look(g, level, r);
            if (in != null && in.distSqr(f) > 2 * 2 && reach(g, r).size() <= 1 && in.distSqr(f) <= 40 * 40) { r.going = null; g.tasks.add(new Task.GoTo(in, 0.5)); g.skillQueue.addFirst(again); return "back to the lit end portal: in by my way in at " + in.toShortString(); } // (outside it still: in by his own hole, not a new one)
            if (in != null && in.distSqr(f) > 40 * 40 && lit.distSqr(f) > lit.distSqr(in)) { r.going = null; /* (only when the way in is nearer the portal than he is: live 01:34, 11 from the portal, 47 from the way in, sent back out to it and round again) */ Vec3 dv = Vec3.atCenterOf(in).subtract(g.position()); double len = dv.length(), leg = Math.min(len, 96); BlockPos step = len <= 96 ? in : BlockPos.containing(g.getX() + dv.x / len * leg, g.getY() + dv.y / len * leg, g.getZ() + dv.z / len * leg);
                g.tasks.add(new Task.GoTo(step, len <= 96 ? 1.0 : 3.0)); g.skillQueue.addFirst(again); return "back to the lit end portal: to my way into its stronghold at " + in.toShortString() + ", " + (int) len + " to go"; }
            if (level.isLoaded(lit)) { BlockPos best = null; for (long k : r.known) { BlockPos q = BlockPos.of(k); if (q.distSqr(lit) <= 4 * 4 && (best == null || q.distSqr(lit) < best.distSqr(lit))) best = q; }
                LinkedHashMap<Long, Long> rch = best == null ? null : reach(g, r);
                if (best != null && rch.containsKey(best.asLong())) { // along his own map in legs of 8 (one long walk was refused by the walker every tick, live 01:36)
                    java.util.List<Long> chain = new java.util.ArrayList<>(); for (long c = best.asLong(); ; c = rch.get(c)) { chain.add(c); if (rch.get(c) == c) break; }
                    BlockPos leg = BlockPos.of(chain.get(Math.max(0, chain.size() - 1 - 8))); if (leg.equals(f)) leg = best;
                    r.going = leg; g.tasks.add(new Task.GoTo(leg, 0.5)); g.skillQueue.addFirst(again); return "back to the lit end portal: on to " + best.toShortString(); } }
        }
        if (r.going != null && r.going.equals(r.lastGo) && f.equals(r.lastFrom)) { r.failed.add(r.going.asLong()); ClaudeMod.LOGGER.info("[Claude stronghold] the walk to {} ended with him still at {}: another way (StrongholdRoom)", r.going.toShortString(), f.toShortString()); }
        if (r.going != null) { r.lastGo = r.going; r.lastFrom = f; }
        if (r.going != null) { if (r.going.distSqr(f) > 2.5 * 2.5 || r.going.distSqr(f) <= 1 && !r.known.contains(r.going.asLong()) && !sees(g, level, Vec3.atCenterOf(r.going), null)) { r.failed.add(r.going.asLong()); ClaudeMod.LOGGER.info("[Claude stronghold] didn't get to {} (stopped at {}): another way (StrongholdRoom)", r.going.toShortString(), f.toShortString()); } r.going = null; }
        { BlockState fs = level.getBlockState(f); // stood in a doorway: out of it, on the side away from where he came (a door shuts on him; walks from inside its cell go nowhere)
          if (fs.getBlock() instanceof DoorBlock) { Direction fa = fs.getValue(DoorBlock.FACING); BlockPos a = f.relative(fa), b = f.relative(fa.getOpposite()); BlockPos from = r.doorFrom != null && r.doorFrom.distSqr(f) <= 9 ? r.doorFrom : r.lastFrom;
            BlockPos to = from == null ? a : (a.distSqr(from) >= b.distSqr(from) ? a : b);
            if (open(level, to) && open(level, to.above())) { r.doorFrom = null; g.tasks.add(new Task.GoTo(to.immutable(), 0.4)); g.skillQueue.addFirst(again); ClaudeMod.LOGGER.info("[Claude stronghold] standing in the doorway at {}: out to {} (StrongholdRoom)", f.toShortString(), to.toShortString()); return "out of the doorway to " + to.toShortString(); } } }
        look(g, level, r);
        if (r.wayIn == null && r.known.contains(f.asLong())) r.wayIn = f.immutable();
        if (r.known.contains(f.asLong()) && (r.trail.isEmpty() || r.trail.get(r.trail.size() - 1).distSqr(f) >= 4 * 4)) { r.trail.add(f.immutable()); if (r.trail.size() > 400) r.trail.remove(0); } // (the first stronghold cell he stood on: his way in, kept with the lit portal)
        if (!r.frames.isEmpty() || r.spawner != null) { String rm = room(g, level, r, again); if (rm != null) return rm; } // (a stronghold's one spawner is the portal room's: in there)
        if (r.door != null) { BlockPos d = r.door; r.door = null; String s = ironDoor(g, level, r, d, again); if (s != null) return s; }
        var reach = reach(g, r);
        if (reach.size() <= 1) { // nothing on his map he can walk to from here: onto the nearest cell of it in reach, else in through the wall
            BlockPos near = null; for (long k : r.known) { BlockPos q = BlockPos.of(k); if (q.distSqr(f) <= 9 && !r.failed.contains(k) && (near == null || q.distSqr(f) < near.distSqr(f))) near = q; }
            if (near != null) { r.going = near; g.tasks.add(new Task.GoTo(near, 0.4)); g.skillQueue.addFirst(again); return "onto the stronghold's floor at " + near.toShortString(); }
            return breakIn(g, level, r, again); }
        // the nearest edge of what he has seen: a cell he can get to with an open, unseen cell beside it
        List<Long> order = new ArrayList<>(reach.keySet()); // nearest first; with lava heard, toward it (the portal room's pool pops through its walls: a player follows his ears)
        Vec3 cue = cue(g, level, r);
        if (cue != null) { Map<Long, Double> sc = new HashMap<>(); int i = 0; for (long c : order) sc.put(c, i++ * 0.5 + 1.5 * Vec3.atCenterOf(BlockPos.of(c)).distanceTo(cue));
            order.sort(Comparator.comparingDouble(sc::get));
            if (r.goes % 10 == 1) ClaudeMod.LOGGER.info("[Claude stronghold] the portal room by ear about {}: the ways toward it first (StrongholdRoom)", BlockPos.containing(cue).toShortString()); }
        for (int pass = 0; pass < 2; pass++) for (long c : order) { if (r.failed.contains(c) && c != f.asLong()) continue; BlockPos p = BlockPos.of(c); // (his own cell is never out of reach) (pass 2: no other way on, so an iron door back out counts whatever is behind it)
            for (Direction d : Direction.Plane.HORIZONTAL) for (int dy = -1; dy <= 1; dy++) { BlockPos n = p.relative(d).offset(0, dy, 0);
                if (r.known.contains(n.asLong()) || r.failed.contains(n.asLong()) || !standable(level, n)) continue; // (an unseen cell of the stronghold itself: never his own pocket or a cave beyond it)
                BlockState ns = level.getBlockState(n);
                if (ns.is(Blocks.IRON_DOOR) && (ns.getValue(DoorBlock.OPEN) && !p.equals(f) || reach.containsKey(n.relative(d).asLong()) || r.known.contains(n.relative(d).asLong()) && (pass == 0 || !unseenBehind(level, r, n.relative(d))))) continue; // (back to ground he has seen: only once his side is done, and only with something unseen there) // (an iron door back into what he can walk to anyway, or to ground he has seen all of: not a way on)
                if (ns.is(Blocks.IRON_DOOR) && p.equals(f)) { String s = ironDoor(g, level, r, n, again); if (s != null) return s; continue; } // (beside it: its button; from farther, walked to like any edge)
                if (p.equals(f) && ns.getBlock() instanceof DoorBlock && !ns.is(Blocks.IRON_DOOR)) { BlockPos past = n.relative(d); // a wooden door: through it to the cell past it (his walk opens it; its own cell is half door)
                    if (open(level, past) && open(level, past.above())) { r.going = past.immutable(); g.tasks.add(new Task.GoTo(r.going, 0.6)); g.skillQueue.addFirst(again); ClaudeMod.LOGGER.info("[Claude stronghold] a door at {}: through it to {} (StrongholdRoom)", n.toShortString(), past.toShortString()); return "in the stronghold: through the door at " + n.toShortString(); } }
                if (p.equals(f)) { r.going = n.immutable(); g.tasks.add(new Task.GoTo(r.going, 0.5)); g.skillQueue.addFirst(again); return "in the stronghold: a step into " + n.toShortString() + ", unseen past it"; }
                r.going = p.immutable(); g.tasks.add(new Task.GoTo(r.going, 0.6)); g.skillQueue.addFirst(again);
                if (r.goes % 10 == 1) ClaudeMod.LOGGER.info("[Claude stronghold] {} cells seen, the nearest unseen way from {}: walking there (StrongholdRoom)", r.known.size(), p.toShortString());
                return "in the stronghold: on to " + p.toShortString() + " from " + f.toShortString() + " (" + r.known.size() + " cells seen, no portal room yet)";
            } }
        if (!r.failed.isEmpty() && r.clears < 3) { r.clears++; ClaudeMod.LOGGER.info("[Claude stronghold] no way on, {} ways written off: trying them again ({} of 3) (StrongholdRoom)", r.failed.size(), r.clears); r.failed.clear(); r.presses.clear(); g.skillQueue.addFirst(again); return "in the stronghold: trying the ways I wrote off again"; }
        { StringBuilder sb = new StringBuilder(); int k = 0; // what's at the edges he turned down, and why (the silent-path rule)
          for (long c : reach.keySet()) { BlockPos p = BlockPos.of(c); for (Direction d : Direction.Plane.HORIZONTAL) { BlockPos n = p.relative(d); if (r.known.contains(n.asLong()) || level.getBlockState(n).getCollisionShape(level, n).isEmpty() == false && !(level.getBlockState(n).getBlock() instanceof DoorBlock)) continue;
              if (k++ < 24) sb.append(n.toShortString()).append(' ').append(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(n).getBlock()).getPath()).append(" over ").append(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(n.below()).getBlock()).getPath()).append(r.failed.contains(n.asLong()) ? " (failed)" : "").append("; "); } }
          for (long c : reach.keySet()) { BlockPos p = BlockPos.of(c); for (Direction d : Direction.Plane.HORIZONTAL) for (int dy = -1; dy <= 1; dy++) { BlockPos n = p.relative(d).offset(0, dy, 0); if (!level.getBlockState(n).is(Blocks.IRON_DOOR)) continue;
              ClaudeMod.LOGGER.info("[Claude stronghold] iron door {} from {}: known {} failed {} standable {} open {} pastInReach {} pastKnown {} pIsFeet {} pFailed {}", n.toShortString(), p.toShortString(), r.known.contains(n.asLong()), r.failed.contains(n.asLong()), standable(level, n), level.getBlockState(n).getValue(DoorBlock.OPEN), reach.containsKey(n.relative(d).asLong()), r.known.contains(n.relative(d).asLong()), p.equals(f), r.failed.contains(c)); } }
          ClaudeMod.LOGGER.info("[Claude stronghold] no way on from {} ({} cells he can walk to): the open cells at their edges: {} (StrongholdRoom)", f.toShortString(), reach.size(), sb); }
        return "failed: walked every way I could see in the stronghold (" + r.known.size() + " cells) and found no portal room";
    }

    /**
     * Where his ears put the portal room, or null: its lava pool popping through the walls, or silverfish he hears that didn't come
     * out of the walls by him (in a stronghold the only other source is the portal room's spawner). The latest of the two.
     */
    static Vec3 cue(ClaudeEntity g, ServerLevel level, Run r) {
        if (PortalMap.endPortal(g) != null && PortalMap.endPortal(g).distSqr(g.blockPosition()) < 96 * 96) return Vec3.atCenterOf(PortalMap.endPortal(g)); // (he lit it: he knows where it is)
        for (var sf : level.getEntitiesOfClass(net.minecraft.world.entity.monster.Silverfish.class, g.getBoundingBox().inflate(6), net.minecraft.world.entity.Entity::isAlive)) r.ownFish.add(sf.getUUID()); // (on him: out of a block he broke, or called from the walls by a fight)
        if (r.ownFish.size() > 256) r.ownFish.clear();
        var lava = Perception.lavaHeardNear(g, g.position(), 48, 20 * 600);
        Perception.Contact fish = null;
        for (var c : Perception.mind(g).sounds) if (c.type() == net.minecraft.world.entity.EntityType.SILVERFISH && c.id() != null && !r.ownFish.contains(c.id()) && g.tickCount - c.tick() <= 20 * 120 && c.at().distanceTo(g.position()) > 8) { fish = c; break; }
        if (fish != null && (lava == null || fish.tick() > lava.tick())) { if (r.goes % 10 == 1) ClaudeMod.LOGGER.info("[Claude stronghold] heard a silverfish about {} that isn't one of the wall's by me: a spawner's (StrongholdRoom.cue)", BlockPos.containing(fish.at()).toShortString()); return fish.at(); }
        return lava == null ? null : lava.at();
    }

    /** His walk as a route: every loop cut (a point back within 3 of an earlier one drops what's between), ending where he stands. */
    static List<BlockPos> route(List<BlockPos> walked, BlockPos end) {
        List<BlockPos> out = new ArrayList<>(); List<BlockPos> all = new ArrayList<>(walked); all.add(end);
        for (BlockPos p : all) { int back = -1; for (int k = 0; k < out.size(); k++) if (out.get(k).distSqr(p) <= 3 * 3) { back = k; break; } if (back >= 0) while (out.size() > back) out.remove(out.size() - 1); out.add(p.immutable()); }
        return out;
    }

    /** Any unseen stronghold cell at the edge of the seen ground behind a door (walked from that cell over his map, never through a shut iron door). */
    static boolean unseenBehind(ServerLevel level, Run r, BlockPos from) {
        Set<Long> seen = new HashSet<>(); ArrayDeque<BlockPos> q = new ArrayDeque<>(); q.add(from); seen.add(from.asLong());
        while (!q.isEmpty() && seen.size() < 4000) { BlockPos p = q.poll();
            for (Direction d : Direction.Plane.HORIZONTAL) for (int dy = -1; dy <= 1; dy++) { BlockPos n = p.relative(d).offset(0, dy, 0); long k = n.asLong();
                if (seen.contains(k)) continue;
                if (r.known.contains(k)) { if (!shutIron(level, n) && !r.failed.contains(k)) { seen.add(k); q.add(n); } continue; } // (as the edge search walks: never through a cell he wrote off)
                if (level.getBlockState(n).is(Blocks.IRON_DOOR)) { seen.add(k); if (!r.failed.contains(k) && !r.known.contains(n.relative(d).asLong()) && open(level, n.relative(d))) return true; continue; } // (another iron door, unseen past it: that's unseen ground too)
                if (!r.failed.contains(k) && standable(level, n)) return true; } }
        return false;
    }

    /** One hole into the stronghold, where he stands beside its bricks: the wall in front at feet and head, or the roof under him with a floor in reach. */
    static String breakIn(ClaudeEntity g, ServerLevel level, Run r, Skills.Step again) {
        BlockPos f = feet(g);
        if (++r.inTries > 10 || r.holes >= 3) return "failed: no way into the stronghold from " + f.toShortString() + " (" + r.holes + " holes, " + r.inTries + " tries, no corridor in sight)";
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos a = f.relative(d), b = a.above();
            if (!(EyeHunt.strongholdBlock(level.getBlockState(a)) || EyeHunt.strongholdBlock(level.getBlockState(b)))) continue;
            if (!sees(g, level, Vec3.atCenterOf(b), b) && !sees(g, level, Vec3.atCenterOf(a), a)) continue;
            BlockPos in = a.relative(d); boolean inside = open(level, in) || open(level, in.above());
            if (!inside) continue; // (the wall's other side is stone too: not the way in)
            for (BlockPos c : new BlockPos[] {b, a}) if (!open(level, c)) g.tasks.add(new Task.Break(c.immutable()));
            r.holes++; r.wayIn = a.immutable(); g.tasks.add(new Task.GoTo(a.immutable(), 0.4)); g.skillQueue.addFirst(again); // (and into the hole: its floor is the stronghold's, his pocket's isn't)
            ClaudeMod.LOGGER.info("[Claude stronghold] a hole in its wall at {} going {}: in (StrongholdRoom.breakIn)", a.toShortString(), d.getName());
            return "breaking into the stronghold: a hole in its wall going " + d.getName();
        }
        BlockPos below = f.below();
        if (EyeHunt.strongholdBlock(level.getBlockState(below))) {
            int drop = 0; BlockPos q = below.below(); while (drop < 8 && open(level, q)) { q = q.below(); drop++; }
            if (drop >= 1 && drop <= 5 && level.getFluidState(q).isEmpty() && level.getFluidState(q.above()).isEmpty()) {
                r.holes++; r.wayIn = below.immutable(); g.tasks.add(new Task.Break(below.immutable())); g.tasks.add(new Task.GoTo(q.above().immutable(), 0.6)); g.skillQueue.addFirst(again);
                ClaudeMod.LOGGER.info("[Claude stronghold] its roof under him at {}, a {}-block drop to its floor: in (StrongholdRoom.breakIn)", below.toShortString(), drop);
                return "breaking into the stronghold through its roof (a " + drop + "-block drop)";
            }
        }
        // its roof under him but solid below it (a pillar, a staircase): the roof brick beside him with room under it, the rock over it cleared, down through it
        if (EyeHunt.strongholdBlock(level.getBlockState(below)) || strongholdFloor(level.getBlockState(below))) for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos nb = below.relative(d); if (!EyeHunt.strongholdBlock(level.getBlockState(nb)) && !strongholdFloor(level.getBlockState(nb))) continue;
            int drop = 0; BlockPos q = nb.below(); while (drop < 8 && open(level, q)) { q = q.below(); drop++; }
            if (drop < 1 || drop > 5 || !level.getFluidState(q).isEmpty() || !level.getFluidState(q.above()).isEmpty()) continue;
            BlockPos side = f.relative(d);
            for (BlockPos c : new BlockPos[] {side.above(), side}) if (!open(level, c)) { if (level.getBlockState(c).getDestroySpeed(level, c) < 0 || EyeHunt.strongholdBlock(level.getBlockState(c))) { side = null; break; } g.tasks.add(new Task.Break(c.immutable())); }
            if (side == null) continue;
            r.holes++; r.wayIn = nb.immutable(); g.tasks.add(new Task.Break(nb.immutable())); g.tasks.add(new Task.GoTo(q.above().immutable(), 0.6)); g.skillQueue.addFirst(again); // (to the floor under the hole: the walker never walks onto air, it plans the drop)
            ClaudeMod.LOGGER.info("[Claude stronghold] its roof under him at {} is solid below; beside it at {} a {}-block drop: the rock over it cleared, down through it (StrongholdRoom.breakIn)", below.toShortString(), nb.toShortString(), drop);
            return "breaking into the stronghold through its roof beside me (a " + drop + "-block drop)";
        }
        // the bricks are beside him but not where he can get in: a step along them
        for (Direction d : Direction.Plane.HORIZONTAL) { BlockPos a = f.relative(d);
            if (!open(level, a) || !open(level, a.above())) continue;
            for (Direction e : Direction.Plane.HORIZONTAL) if (EyeHunt.strongholdBlock(level.getBlockState(a.relative(e))) && open(level, a.relative(e, 2))) { g.tasks.add(new Task.GoTo(a.immutable(), 0.3)); g.skillQueue.addFirst(again); return "a step along the stronghold's wall to get in"; } }
        // its bricks not beside him (a few steps up his own staircase, or after a restart): the ones he knows of, or sees now, and down beside them
        BlockPos brick = EyeHunt.found(g);
        if (brick == null || brick.distSqr(f) > 24 * 24) brick = Perception.nearestSign(g, f, 24, "a stronghold");
        if (brick == null) for (BlockPos q : BlockPos.betweenClosed(f.offset(-10, -8, -10), f.offset(10, 4, 10))) if (EyeHunt.strongholdBlock(level.getBlockState(q)) && sees(g, level, Vec3.atCenterOf(q), q) && (brick == null || q.distSqr(f) < brick.distSqr(f))) brick = q.immutable();
        if (brick != null) {
            BlockPos stand = null; // an open cell with a floor beside it or on it, nearest him (his staircase's last step, as a rule)
            for (BlockPos q : new BlockPos[] {brick.above(), brick.north(), brick.south(), brick.east(), brick.west(), brick.north().below(), brick.south().below(), brick.east().below(), brick.west().below()})
                if (open(level, q) && open(level, q.above()) && !level.getBlockState(q.below()).getCollisionShape(level, q.below()).isEmpty() && level.getFluidState(q.below()).isEmpty() && (stand == null || q.distSqr(f) < stand.distSqr(f))) stand = q.immutable();
            if (stand != null && !stand.equals(f)) { g.tasks.add(new Task.GoTo(stand, 0.4)); g.skillQueue.addFirst(again); ClaudeMod.LOGGER.info("[Claude stronghold] its bricks at {}, {} off: down beside them to {} to get in (StrongholdRoom.breakIn)", brick.toShortString(), (int) Math.sqrt(brick.distSqr(f)), stand.toShortString()); return "down to the stronghold's bricks at " + brick.toShortString() + " to get in"; }
        }
        if (brick == null) { // nothing known or in sight: down the open steps round him (his own staircase) and look again
            Set<Long> seen = new HashSet<>(); ArrayDeque<BlockPos> q = new ArrayDeque<>(); q.add(f); seen.add(f.asLong()); BlockPos low = f;
            while (!q.isEmpty()) { BlockPos p = q.poll(); if (p.getY() < low.getY() || p.getY() == low.getY() && p.distSqr(f) < low.distSqr(f)) low = p;
                for (Direction d : Direction.Plane.HORIZONTAL) for (int dy = -1; dy <= 1; dy++) { BlockPos n = p.relative(d).offset(0, dy, 0);
                    if (n.distSqr(f) > 8 * 8 || !seen.add(n.asLong())) continue;
                    if (open(level, n) && open(level, n.above()) && !level.getBlockState(n.below()).getCollisionShape(level, n.below()).isEmpty() && level.getFluidState(n.below()).isEmpty()) q.add(n); } }
            if (low.getY() < f.getY()) { g.tasks.add(new Task.GoTo(low.immutable(), 0.4)); g.skillQueue.addFirst(again); ClaudeMod.LOGGER.info("[Claude stronghold] no stronghold bricks known or in sight from {}: down the steps to {} to look (StrongholdRoom.breakIn)", f.toShortString(), low.toShortString()); return "down my steps to " + low.toShortString() + " to find the stronghold's wall"; }
        }
        return "failed: no stronghold corridor in sight from " + f.toShortString() + " and no wall of it beside me to open" + (brick == null ? " (no stronghold bricks known or in sight within 24)" : " (its bricks at " + brick.toShortString() + ", no open cell beside them)");
    }

    /** A closed iron door: its button pressed (a stone button beside it, on his side), then through while it's open. */
    static String ironDoor(ClaudeEntity g, ServerLevel level, Run r, BlockPos door, Skills.Step again) {
        BlockState ds = level.getBlockState(door);
        if (!ds.is(Blocks.IRON_DOOR)) return null;
        if (ds.getValue(DoorBlock.OPEN)) { Direction facing = ds.getValue(DoorBlock.FACING); BlockPos a = door.relative(facing), b = door.relative(facing.getOpposite()); BlockPos to = a.distSqr(feet(g)) > b.distSqr(feet(g)) ? a : b;
            r.going = to.immutable(); g.tasks.add(new Task.GoTo(to, 0.5)); g.skillQueue.addFirst(again); g.setSprinting(true); return "through the iron door while it's open"; }
        BlockPos btn = null;
        if (r.presses.merge(door.asLong(), 1, Integer::sum) > 1) { BlockPos low = ds.getValue(DoorBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER ? door : door.below(); // two presses and still this side: the door comes out (one block, cheap)
            g.tasks.add(new Task.Break(low.immutable())); g.skillQueue.addFirst(again); ClaudeMod.LOGGER.info("[Claude stronghold] a press at the iron door at {} and still this side of it: breaking it (StrongholdRoom.ironDoor)", low.toShortString()); return "breaking the iron door at " + low.toShortString() + " (a try through it failed)"; }
        for (BlockPos q : BlockPos.betweenClosed(door.offset(-1, 0, -1), door.offset(1, 1, 1))) if (level.getBlockState(q).getBlock() instanceof ButtonBlock && Vec3.atCenterOf(q).distanceTo(g.getEyePosition()) <= 4.4 && sees(g, level, Vec3.atCenterOf(q), q) && (btn == null || q.distSqr(door) < btn.distSqr(door))) btn = q.immutable(); // (its own button, beside it: never the next door's)
        if (btn == null) { // no button this side (a prison cell's door, or he's shut in past one): a player breaks the door, the one block it takes
            BlockPos low = ds.getValue(DoorBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER ? door : door.below();
            if (Vec3.atCenterOf(low).distanceTo(g.getEyePosition()) > 4.4) { r.failed.add(door.asLong()); return null; }
            g.tasks.add(new Task.Break(low.immutable())); g.skillQueue.addFirst(again);
            ClaudeMod.LOGGER.info("[Claude stronghold] an iron door at {} with no button on this side: breaking it, the way on (StrongholdRoom.ironDoor)", low.toShortString());
            return "no button this side of the iron door: breaking it"; }
        Direction face = level.getBlockState(btn).getValue(ButtonBlock.FACING);
        g.useOn(btn, face, false); r.doorFrom = feet(g);
        { Direction fa = ds.getValue(DoorBlock.FACING); BlockPos a = door.relative(fa), b = door.relative(fa.getOpposite()); BlockPos to = a.distSqr(feet(g)) > b.distSqr(feet(g)) ? a : b; // straight through while it's open (a stone button holds it 1 s)
          r.going = to.immutable(); g.setSprinting(true); g.tasks.add(new Task.GoTo(to.immutable(), 0.5)); }
        g.skillQueue.addFirst(again);
        ClaudeMod.LOGGER.info("[Claude stronghold] pressed the button at {} for the iron door at {} (StrongholdRoom.ironDoor)", btn.toShortString(), door.toShortString());
        return "pressed the button by the iron door";
    }

    static Vec3 frameTop(BlockPos p) { return new Vec3(p.getX() + 0.5, p.getY() + 0.8125, p.getZ() + 0.5); }
    /** The nearest cell he can walk to (on his map) from which that point is in reach and in sight, or null. */
    static BlockPos spotFor(ClaudeEntity g, ServerLevel level, Run r, Vec3 at, BlockPos block) {
        for (long c : reach(g, r).keySet()) { BlockPos p = BlockPos.of(c); Vec3 eye = new Vec3(p.getX() + 0.5, p.getY() + 1.62, p.getZ() + 0.5);
            if (eye.distanceTo(at) > 4.2) continue;
            var hit = level.clip(new ClipContext(eye, at, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, g));
            if (hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(block)) return p; }
        return null;
    }

    /** In the portal room: the spawner broken, the frames filled one by one from where each is in reach, the portal checked. */
    static String room(ClaudeEntity g, ServerLevel level, Run r, Skills.Step again) {
        if (r.spawner != null && level.getBlockState(r.spawner).is(Blocks.SPAWNER)) { // (its silverfish keep coming while he works the frames: an expert breaks it first)
            Vec3 c = Vec3.atCenterOf(r.spawner);
            if (c.distanceTo(g.getEyePosition()) <= 4.4 && sees(g, level, c, r.spawner)) { g.tasks.add(new Task.Break(r.spawner)); g.skillQueue.addFirst(again); ClaudeMod.LOGGER.info("[Claude stronghold] breaking the silverfish spawner at {} (StrongholdRoom.room)", r.spawner.toShortString()); return "breaking the silverfish spawner first"; }
            BlockPos s = spotFor(g, level, r, c, r.spawner);
            if (s != null && !r.failed.contains(s.asLong())) { r.going = s.immutable(); g.tasks.add(new Task.GoTo(s, 0.4)); g.skillQueue.addFirst(again); return "to the silverfish spawner, to break it"; }
        }
        if (r.frames.isEmpty()) return null; // (the spawner's gone and no frame seen yet: on round the room)
        List<BlockPos> empty = new ArrayList<>(); int lit = 0;
        for (BlockPos p : r.frames) { BlockState s = level.getBlockState(p); if (!s.is(Blocks.END_PORTAL_FRAME)) continue; if (s.getValue(EndPortalFrameBlock.HAS_EYE)) lit++; else empty.add(p); }
        int eyes = g.inventory.count(x -> x.is(Items.ENDER_EYE));
        if (empty.isEmpty() && r.frames.size() >= 12) {
            BlockPos portal = null; for (BlockPos p : r.frames) for (BlockPos q : BlockPos.betweenClosed(p.offset(-4, -1, -4), p.offset(4, 1, 4))) if (level.getBlockState(q).is(Blocks.END_PORTAL)) { portal = q.immutable(); break; }
            if (portal == null) return "failed: all 12 frames have eyes and no portal opened (" + r.frames.size() + " frames seen)";
            PortalMap.noteEnd(g, portal, r.wayIn != null ? r.wayIn : EyeHunt.found(g)); PortalMap.noteEndTrail(g, route(r.trail, feet(g)));
            ClaudeMod.LOGGER.info("[Claude stronghold] the end portal is lit at {}: {} eyes of mine in, {} left (StrongholdRoom)", portal.toShortString(), r.eyesIn, eyes);
            return "done: the end portal is lit at " + portal.toShortString() + " (" + r.eyesIn + " eyes put in, " + eyes + " left)";
        }
        int unseen = Math.max(0, 12 - r.frames.size());
        if (eyes < empty.size()) return "failed: the portal room is at " + r.frames.iterator().next().toShortString() + ": " + empty.size() + " empty frames" + (unseen > 0 ? " (and " + unseen + " not seen yet)" : "") + " and I have " + eyes + " eyes of ender";
        if (eyes == 0 && unseen > 0) return "failed: out of eyes in the portal room with " + unseen + " frames not seen yet";
        empty.sort(Comparator.comparingDouble(p -> frameTop(p).distanceTo(g.getEyePosition())));
        for (BlockPos p : empty) { Vec3 top = frameTop(p);
            if (top.distanceTo(g.getEyePosition()) <= 4.4 && sees(g, level, top, p)) {
                g.getNavigation().stop();
                if (!g.hold(s -> s.is(Items.ENDER_EYE))) return "failed: couldn't get an eye in hand";
                g.useOnAt(p, Direction.UP, false, top);
                if (level.getBlockState(p).getValue(EndPortalFrameBlock.HAS_EYE)) { r.eyesIn++; ClaudeMod.LOGGER.info("[Claude stronghold] an eye in the frame at {} ({} of 12 filled, {} eyes left) (StrongholdRoom)", p.toShortString(), lit + 1, eyes - 1); }
                else ClaudeMod.LOGGER.info("[Claude stronghold] the eye didn't go into the frame at {} (StrongholdRoom)", p.toShortString());
                g.tasks.add(new Task.Wait(4, false)); g.skillQueue.addFirst(again);
                return "an eye into the frame at " + p.toShortString() + " (" + (lit + 1) + " of 12)";
            }
        }
        for (BlockPos p : empty) { BlockPos s = spotFor(g, level, r, frameTop(p), p); if (s == null || r.failed.contains(s.asLong())) continue;
            r.going = s.immutable(); g.tasks.add(new Task.GoTo(s, 0.4)); g.skillQueue.addFirst(again); return "to the frame at " + p.toShortString(); }
        if (unseen > 0) { // the rest of the frames: up level with them (from the floor below the platform its stairs hide them), else on round the room
            int fy = r.frames.iterator().next().getY(); BlockPos best = null;
            for (long c : reach(g, r).keySet()) { BlockPos p = BlockPos.of(c); if (p.getY() < fy || r.failed.contains(c) || p.equals(feet(g))) continue;
                boolean near = false; for (BlockPos fr : r.frames) if (Math.abs(fr.getX() - p.getX()) <= 3 && Math.abs(fr.getZ() - p.getZ()) <= 3) near = true;
                if (near) { best = p; break; } }
            if (best != null) { r.going = best.immutable(); g.tasks.add(new Task.GoTo(best, 0.4)); g.skillQueue.addFirst(again); ClaudeMod.LOGGER.info("[Claude stronghold] {} of 12 frames seen: up level with them at {} (StrongholdRoom.room)", r.frames.size(), best.toShortString()); return "up to the frames' platform, " + r.frames.size() + " frames seen so far"; }
            return null; // (no cell level with them seen yet: on exploring the room)
        }
        { BlockPos f0 = feet(g); StringBuilder sb = new StringBuilder(); for (BlockPos q : BlockPos.betweenClosed(f0.offset(-8, -4, -8), f0.offset(8, 4, 8))) if (level.getBlockState(q).is(Blocks.END_PORTAL_FRAME)) { var hit = level.clip(new ClipContext(g.getEyePosition(), Vec3.atCenterOf(q).add(0, 0.3, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, g)); sb.append(q.toShortString()).append("->").append(hit.getType() == HitResult.Type.MISS ? "miss" : hit.getBlockPos().toShortString()).append("; "); }
          ClaudeMod.LOGGER.info("[Claude stronghold] frames round him at {} (eye {}): {} (StrongholdRoom.room)", f0.toShortString(), String.format("%.2f", g.getEyeY()), sb); }
        if (!r.failed.isEmpty() && r.clears < 3) { r.clears++; r.failed.clear(); g.skillQueue.addFirst(again); return "in the portal room: trying the spots I wrote off again"; }
        if (r.goes % 3 != 0) return null; // (no spot in reach of the last frames on his map yet: another look round the room first)
        return "failed: in the portal room (" + r.frames.size() + " frames seen, " + empty.size() + " empty) and no frame I can get to";
    }
}
