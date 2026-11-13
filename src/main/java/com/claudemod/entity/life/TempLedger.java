package com.claudemod.entity.life;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.claudemod.ClaudeMod;
import com.claudemod.director.ClaudeLife;
import com.claudemod.entity.ClaudeEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.TorchBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Every block he puts down for now (2026-10-30, world 14 day one, the user: "he's left quite a bit of litter lol", "mostly
 * crafting tables", "it should log everything he places 'temporarily'", "and when, and where, and what he was thinking before
 * during and after"). One record, fed by the place event itself, so nothing he puts down is missed whichever code path put it:
 * a table, a furnace, a step, a pillar, a bridge, a scaffold block, a cover block, a nerd-pole, a torch out in the world. What
 * belongs to one of his builds (his mine's blueprint and cells, his house, farm, nook, notches, junk chests, a station at his
 * base) is no temporary block and never goes on it; nor a plug (a block into water or lava) or a fill (a hole in the ground).
 *
 * Each entry: where, what, why, when (game time), BEFORE (the step and task that put it, the goal), DURING (his skill line
 * then, and the latest one when it's checked), AFTER (when and how it came back up, or why it's still standing). It comes back up
 * when the job that needed it is done: at the start of his next step (a table the moment the crafting is over), when he's idle
 * near it, or when he walks off past it (the old take-down rules, fed from here). One he can't get to stays on, with the reason,
 * and gets another go when he's been away and comes back by it; never a give-up count. One he left behind is litter: in the log,
 * the trace and his situation text, with its place. Dirt put down as a step into a hill may stay (the user's rule); a dirt pillar
 * or a line of dirt over flat ground may not. Saved with his mine record ("temps"), so a restart forgets nothing.
 *
 * Also here: trees he started and couldn't finish (logs still standing): owed, with the reason, back for them when he's by it.
 */
public final class TempLedger {
    private TempLedger() {}

    public static final class Entry {
        public final BlockPos pos; public final String item, why; public final long at; // game time put down
        public String before, during, now = "", after = "", blocked = ""; public boolean reported; public int triedAt = -1; public boolean awayAfterTry;
        Entry(BlockPos pos, String item, String why, long at, String before, String during) { this.pos = pos; this.item = item; this.why = why; this.at = at; this.before = before; this.during = during; }
        public String toString() { return item + " (" + why + ") at " + pos.toShortString(); }
    }
    /** A tree he started on with logs still up: the base, how many, why, when. */
    public static final class Owed {
        public final BlockPos base; public int logs; public String why; public long at; public int triedAt = -1; public boolean awayAfterTry = true;
        Owed(BlockPos base, int logs, String why, long at) { this.base = base; this.logs = logs; this.why = why; this.at = at; }
    }

    // per dimension: a Nether block read against the overworld's cell at the same numbers was "gone" or "not his now" and dropped, or
    // reported as litter while he was home (live 11-10: 1868 Nether blocks, "now: collect beef"); never taken back when he was next by it
    private static final Map<UUID, Map<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>, LinkedHashMap<BlockPos, Entry>>> books = new HashMap<>();
    private static final Map<UUID, List<Entry>> unsorted = new HashMap<>(); // from a save before the dimension was kept: claimed by the first dimension where his block stands in the cell
    private static final Map<UUID, LinkedHashMap<BlockPos, Owed>> trees = new HashMap<>();

    public static LinkedHashMap<BlockPos, Entry> book(ClaudeEntity g) { return book(g, g.level().dimension()); }
    public static LinkedHashMap<BlockPos, Entry> book(ClaudeEntity g, net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dim) { return books.computeIfAbsent(g.getUUID(), k -> new HashMap<>()).computeIfAbsent(dim, k -> new LinkedHashMap<>()); }
    public static LinkedHashMap<BlockPos, Owed> owedTrees(ClaudeEntity g) { return trees.computeIfAbsent(g.getUUID(), k -> new LinkedHashMap<>()); }
    public static void reset(ClaudeEntity g) { books.remove(g.getUUID()); unsorted.remove(g.getUUID()); trees.remove(g.getUUID()); lastSkill.remove(g.getUUID()); if (builds != null) builds.remove(g.getUUID()); }

    // ---- his builds: a block one of his build systems put down (his bedroom's walls, his nook, house, farm, his mine's patches) is his build: it stays, never on the ledger, never dug through by a walk (ActionPath) ----
    private static Map<UUID, LinkedHashMap<BlockPos, String>> builds; // cell -> who put it there, "ClaudeLife.secureBedroom:5707" (static: fields added live start null)
    public static LinkedHashMap<BlockPos, String> builtCells(ClaudeEntity g) { if (builds == null) builds = new HashMap<>(); return builds.computeIfAbsent(g.getUUID(), k -> new LinkedHashMap<>()); }
    /** A cell one of his build systems put a block in (his bedroom wall, a nook fill, a patch). */
    public static boolean built(ClaudeEntity g, BlockPos p) { if (builds == null) return false; var m = builds.get(g.getUUID()); return m != null && m.containsKey(p); }
    /** The systems whose blocks are builds: they stay (the placed-blocks rule: builds stay, movement blocks come back up). By the method that queued the Place (ClaudeEntity.originOf). */
    static final java.util.Set<String> BUILD_SYSTEMS = java.util.Set.of(
            "ClaudeLife.secureBedroom", "ClaudeLife.nookBuild", "ClaudeLife.nookCutsBack", "ClaudeLife.nookMove", "ClaudeLife.baseStations", "ClaudeLife.hbBuild", "ClaudeLife.hbUpkeep", "ClaudeLife.furnishHome", "ClaudeLife.buildShack",
            "ClaudeLife.farmBuild", "ClaudeLife.bpReconcile", "ClaudeLife.bpIntegrity", "ClaudeLife.mineIntegrity", "ClaudeLife.mineDeathRepair", "ClaudeLife.patchOpenings", "ClaudeLife.checkMineAroundHimRaw",
            "ClaudeLife.refillNotch", "ClaudeLife.furnaceWallUpkeep", "ClaudeLife.smoothStairs", "ClaudeLife.turnLanding", "ClaudeLife.frameEntrance",
            "BaseNook.reconcile", "MineBlueprint.reconcile", "HouseBlueprint.reconcile", "FarmBlueprint.reconcile", "PitGuard.close", "PitGuard.resume",
            "WorkGoal.doMineVisibleOres", "WorkGoal.doBreak", "WorkGoal.doSealStep", "WorkGoal.blockWaterInflow",
            "WorkGoal.doBore", "MineStairs.skill"); // (his travel way through the Nether: its floor, rails and walls over gaps and lava are a path, and stay: the user 11-05, "bridges stay")
    private static Map<UUID, Map<BlockPos, String>> plannedBuild; // cells a plan of his lays out as a build before the walker queues their Places (a lava crossing: a piece of highway) (static: fields added live start null)
    /** A cell a plan of his lays out as his build (the highway spec's crossing): the block he puts there stays. */
    public static void planBuild(ClaudeEntity g, BlockPos p, String by) { if (plannedBuild == null) plannedBuild = new HashMap<>(); var m = plannedBuild.computeIfAbsent(g.getUUID(), k -> new java.util.LinkedHashMap<>()); m.put(p.immutable(), by); while (m.size() > 4096) m.remove(m.keySet().iterator().next()); }
    /** The build system whose Place put this block down (his job right now is that Place, its origin on record), or null. */
    static String buildOrigin(ClaudeEntity g, BlockPos pos) {
        if (plannedBuild != null) { var m = plannedBuild.get(g.getUUID()); String by = m == null ? null : m.remove(pos); if (by != null) return by; }
        if (!(g.currentTask instanceof Task.Place pl) || !pl.pos().equals(pos)) return null;
        String o = ClaudeEntity.originOf(pl);
        return BUILD_SYSTEMS.contains(o.replaceAll(":\\d+$", "")) ? o : null;
    }
    public static Entry entry(ClaudeEntity g, BlockPos p) { return book(g).get(p); }
    public static boolean has(ClaudeEntity g, BlockPos p) { return book(g).containsKey(p); }

    static String cap(String s) { if (s == null) return ""; s = s.replace('\n', ' '); return s.length() > 120 ? s.substring(0, 117) + "..." : s; }
    static String task(Object t) { if (t == null) return "none"; String s = t.toString(); return s.replace("BlockPos{x=", "").replace(", y=", ",").replace(", z=", ",").replace("}", ""); }
    static String stepOf(ClaudeEntity g) { var s = g.lastStep; return s == null ? "none" : s.skill() + (s.target() == null || s.target().isBlank() ? "" : " " + s.target()) + (s.count() > 1 ? " x" + s.count() : ""); }
    static String before(ClaudeEntity g) { return cap("step " + stepOf(g) + "; task " + task(g.currentTask) + "; goal " + (g.playerGoal == null || g.playerGoal.isBlank() ? "none" : g.playerGoal)); }
    static String line(ClaudeEntity g) { return cap(g.lastSkillLine == null ? "" : g.lastSkillLine); }
    static String name(BlockState s) { return BuiltInRegistries.ITEM.getKey(s.getBlock().asItem()).getPath(); }
    static String esc(String s) { return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "'"); }
    static String ago(ServerLevel level, long at) { long s = Math.max(0, (level.getGameTime() - at) / 20); return s >= 120 ? (s / 60) + " min" : s + " s"; }

    static void trace(ClaudeEntity g, String kind, Entry e, String extra) {
        try { com.claudemod.context.PlayerTrace.temp(g, kind, String.format(Locale.ROOT, "\"block\":\"%s\",\"at\":[%d,%d,%d],\"why\":\"%s\",\"put\":%d,\"before\":\"%s\",\"during\":\"%s\",\"now\":\"%s\",\"after\":\"%s\"%s",
                e.item, e.pos.getX(), e.pos.getY(), e.pos.getZ(), e.why, e.at, esc(e.before), esc(e.during), esc(e.now), esc(e.after), extra == null ? "" : extra)); }
        catch (NoClassDefFoundError ignored) {}
    }

    /** The block standing in the entry's cell is the one he put down (his dirt gone to grass counts): only then is it his to take up. */
    static boolean his(ServerLevel level, Entry e) {
        BlockState s = level.getBlockState(e.pos);
        return name(s).equals(e.item) || (java.util.Set.of("dirt", "grass_block", "coarse_dirt", "rooted_dirt", "podzol", "mycelium", "dirt_path", "farmland").contains(e.item) && dirtLike(s));
    }
    static boolean dirtLike(BlockState s) { return s.is(BlockTags.DIRT) || s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.DIRT_PATH); }
    static boolean solid(ServerLevel level, BlockPos p) { return !level.getBlockState(p).getCollisionShape(level, p).isEmpty(); }

    /** Part of one of his builds (or a station at his base): never temporary. */
    public static boolean hisBuild(ClaudeEntity g, ServerLevel level, BlockPos p) {
        if (WatchBuild.ownsCell(g, p)) return true; // a learned build going up (or done): its blocks stay
        boolean walkway = g.blueprint != null && !g.blueprint.isEmpty() && g.blueprint.isWalk(p) || g.mineCells.contains(p) || g.landingCells.contains(p); // the mine's walkway is kept open: a block put in it (a pillar to a drop, live 03:18 11-10, D2D review 36) blocks his own tunnel, never a patch
        if (g.blueprint != null && !g.blueprint.isEmpty() && g.blueprint.touches(p) && !walkway) return true;
        if (g.house != null && g.house.touches(p)) return true;
        if (g.farm != null && g.farm.touches(p)) return true;
        if (com.claudemod.director.ClaudeLife.anyNookTouches(g, p)) return true;
        if (ClaudeLife.inHisNook(g, p)) return true; // the old nook's pocket too, while it's moved: its swaps and its fill are the wall going back (live: the walling's cobble booked as "cover", broken straight back out by the step start)
        if (g.notches.contains(p) || g.storageChests.contains(p) || ClaudeLife.isJunkChest(g, p)) return true;
        if (PitGuard.isCap(g, p)) return true; // the mouth of a hole by his door he closed: it stays
        if (ClaudeLife.portalFrameOf(g, p) != null || ClaudeLife.portalFrameKnown(g, p) != null) return true; // his portal's frame and corners (or the corners they had him fill): they stay
        if (built(g, p)) return true; // a block a build system of his put there (his bedroom's wall)
        return !walkway && (ClaudeLife.inHisMine(g, p) || ClaudeLife.touchesMine(g, p)); // in the mine, placing is a patch (but not in the walkway itself)
    }

    /** Natural ground on 3+ sides and solid under it: the block fills a hole, the ground made good. */
    static boolean holeFilled(ClaudeEntity g, ServerLevel level, BlockPos p) {
        int natural = 0; for (Direction d : Direction.Plane.HORIZONTAL) { BlockPos n = p.relative(d); if (solid(level, n) && !has(g, n)) natural++; }
        return natural >= 3 && solid(level, p.below());
    }

    /** What a block he just put down is for, or null if it's no temporary block of his. */
    static String classify(ClaudeEntity g, ServerLevel level, BlockPos p, BlockState placed, BlockState replaced) {
        if (!replaced.getFluidState().isEmpty()) return null; // a plug (into water or lava): it stays
        if (hisBuild(g, level, p)) { ClaudeMod.LOGGER.info("[Claude temp] {} at {} is in a cell of one of his builds (mine, house, nook, farm, a build's record): not booked as temporary (TempLedger.classify)", name(placed), p.toShortString()); return null; } // (said: this skip was silent, and a stale build record hid two blocks of litter)
        { String by0 = Consent.theirStepNames(g, p); if (by0 != null) { Consent.placedByWord(g, p, by0); return null; } } // their step named this cell (place_at x y z): theirs to keep, never temporary
        if (g.commandActive && g.lastStep != null && g.lastStep.skill().equals("place")) return null; // they asked for it there ("put a table here"): theirs to keep, no temporary block
        Block b = placed.getBlock();
        if (b instanceof CraftingTableBlock || b instanceof AbstractFurnaceBlock) {
            if ("no".equals(g.pref("pick_up_stations", "yes"))) return null;
            boolean another = g.myStations.stream().anyMatch(q -> !q.equals(p) && q.distSqr(p) <= 24 * 24 && level.isLoaded(q) && level.getBlockState(q).is(b));
            if (ClaudeLife.baseArea(g, p) && !another) return null; // his base's station
            return b instanceof CraftingTableBlock ? "craft table" : "furnace";
        }
        if (b instanceof TorchBlock || b == Blocks.LANTERN) return ClaudeLife.baseArea(g, p) || !level.canSeeSky(p) ? null : "light"; // (a torch down a cave is a way-mark: it stays)
        if (fullCell(level, p, placed) && !placed.hasBlockEntity() && holeFilled(g, level, p)) return null; // a hole filled level with the ground, his cover's too (his dug-in hole pillared full on the way out was booked "cover" and dug back out 2 s on, live 12:07 11-09: stage 1262)
        if (g.coverCell(p)) return "cover"; // a block of the cover he's making (by its shape the box's walls read as "step", "bridge", "scaffold", booked to the hunt that was running, live 04:42)
        if (placed.hasBlockEntity() || !fullCell(level, p, placed) || b == Blocks.OBSIDIAN) return null; // chests, beds, crops, saplings, fences, the portal's frame: none of it temporary
        BlockPos feet = g.blockPosition();
        if (holeFilled(g, level, p)) return null; // a hole filled (the ground made good again)
        boolean hostile = !Perception.known(g, net.minecraft.world.entity.monster.Monster.class, g.getBoundingBox().inflate(12), m -> m.isAlive()).isEmpty();
        if (p.getX() == feet.getX() && p.getZ() == feet.getZ() && p.getY() < feet.getY()) return hostile || g.currentTask instanceof Task.Flee ? "nerd-pole" : "pillar";
        if (p.getY() <= feet.getY() - 1 && !solid(level, p.below())) return "bridge";
        if (p.getY() > feet.getY() && Math.abs(p.getX() - feet.getX()) <= 1 && Math.abs(p.getZ() - feet.getZ()) <= 1) return hostile || g.holdingNight() || !com.claudemod.director.ClaudeLife.dayHere(level) ? "cover" : "scaffold";
        if (p.getY() >= feet.getY() - 1 && p.getY() <= feet.getY()) return "step";
        return "scaffold";
    }

    /** A block that fills its cell: by its shape, not its collision (soul sand and mud collide 2 px short, and his soul sand bridge went unbooked: live 02:32 11-10, stage 1362). */
    private static boolean fullCell(ServerLevel level, BlockPos p, BlockState s) { return Block.isShapeFullBlock(s.getShape(level, p)); }

    /** The place event: he put a block down. */
    private static Map<UUID, java.util.Set<BlockPos>> plugs; // blocks he put down into water or lava: he knows what's behind them (static: fields added live start null)
    /** A block he put down into water or lava (or his water-inflow plug): what it holds back is his own knowledge, so no way is ever dug through it. */
    public static boolean isPlug(ClaudeEntity g, BlockPos p) {
        if (plugs != null) { var s = plugs.get(g.getUUID()); if (s != null && s.contains(p)) return true; }
        String by = builtCells(g).get(p); return by != null && by.startsWith("WorkGoal.blockWaterInflow");
    }
    public static void placed(ClaudeEntity g, ServerLevel level, BlockPos pos, BlockState placed, BlockState replaced) {
        if (!replaced.getFluidState().isEmpty()) { if (plugs == null) plugs = new HashMap<>(); var s = plugs.computeIfAbsent(g.getUUID(), k -> new java.util.LinkedHashSet<>()); s.add(pos.immutable()); while (s.size() > 4096) s.remove(s.iterator().next()); } // (before the End's and the builds' returns: a plug is a plug whoever put it there)
        { BlockPos eye = BlockPos.containing(g.getEyePosition()); if (g.isEyeInFluid(net.minecraft.tags.FluidTags.WATER) && pos.getX() == eye.getX() && pos.getZ() == eye.getZ() && pos.getY() > eye.getY() && pos.getY() <= eye.getY() + 3) /* a block over his head while he is under water: does it seal his way up? (D2D death 10:39:46 11-12, proof for the next one) */
            ClaudeMod.LOGGER.info("[Claude plug] {} put down at {} over his head under water (eye {}, air {}, the nearest air now {}, by {}, task {}) (TempLedger.placed)", name(placed), pos.toShortString(), eye.toShortString(), g.getAirSupply(), java.util.Objects.toString(Watchdog.airNear(g, level), "none within 6"), java.util.Objects.toString(buildOrigin(g, pos), "-"), task(g.currentTask)); }
        if (DragonFight.active(g)) return; // (in the End fight nothing he puts down is litter)
        { String by = buildOrigin(g, pos); if (by != null) { // a build system of his put it there: his build (live: secureBedroom's walls booked "cover"/"step", tidied back out, walled again, round and round)
            var bc = builtCells(g); bc.remove(pos); bc.put(pos.immutable(), by); while (bc.size() > 65536) bc.remove(bc.keySet().iterator().next()); /* shortcut: oldest-first eviction past 65k standing builds (4096 dropped his early builds within a day of patching); broken cells already leave it, so only a huge base gets here */
            if (book(g, level.dimension()).remove(pos) != null) trace(g, "temp_left", new Entry(pos.immutable(), name(placed), "build", level.getGameTime(), before(g), line(g)), null);
            ClaudeMod.LOGGER.info("[Claude temp] {} at {} put down by {}: his build, it stays (never temporary, never dug through by a walk) (TempLedger.placed)", name(placed), pos.toShortString(), by);
            return; } }
        if (built(g, pos)) { // no build system of his put this one down, and the cell was open for it: the build's block that stood here is gone (blown up, burnt, the ground made over), so the record is stale; kept, every block he puts here for now read as "his build" and nothing took it back (stage 86: stage 84's house cells, a bridge and a step under the oak left standing)
            String was = builtCells(g).remove(pos);
            ClaudeMod.LOGGER.info("[Claude temp] {} at {} put down by {}: the cell was his build's ({}) but that block is gone, so it's no build cell now; booked on its own merits (TempLedger.placed)", name(placed), pos.toShortString(), task(g.currentTask), was);
        }
        String why = classify(g, level, pos, placed, replaced);
        if (why == null) return;
        BlockPos p = pos.immutable();
        Entry e = new Entry(p, name(placed), why, level.getGameTime(), before(g), line(g));
        if ((why.equals("step") || why.equals("pillar")) && dirtLike(placed) && hillStep(g, level, p)) { // the user: dirt may stay (a hill); a one-block tower step up a ledge is one too (review 454, stage 1286)
            e.after = "kept: a dirt step into the hill (may stay)"; trace(g, "temp_left", e, null);
            ClaudeMod.LOGGER.info("[Claude temp] dirt step at {} into the hill: it may stay", p.toShortString());
            return;
        }
        book(g, level.dimension()).put(p, e);
        // a station also goes on the old station queue (the walk-off rule turns him back for a table or furnace he's leaving); his
        // blocks don't (the walk-off rule for towers sent him back down a ledge for the steps that got him up it, then up it again
        // on new ones): the ledger's own rules take those, and the climb itself takes its steps back from the top
        if ((why.equals("craft table") || why.equals("furnace")) && !g.tempStations.contains(p)) g.tempStations.add(p);
        trace(g, "temp", e, null);
        ClaudeMod.LOGGER.info("[Claude temp] put down {} at {} ({}): {} | {}", e.item, p.toShortString(), why, e.before, e.during);
    }

    /** Taking p back up would leave a hollow he can't walk out of: natural ground under it, and no neighbour he could walk to
     *  level, a step up (with headroom) or a step down (World 17 20:52-20:54, incident 214: a pillar out of a hollow by his mine
     *  taken up at each step's start and put back by the walk out, 6 times in 60 s). A tower's upper blocks (his own under them) aren't. */
    static boolean stepOut(ClaudeEntity g, ServerLevel level, BlockPos p) {
        if (!solid(level, p.below()) || has(g, p.below())) return false;
        java.util.function.Predicate<BlockPos> so = q -> !q.equals(p) && solid(level, q), walk = q -> !so.test(q) && !so.test(q.above()) && so.test(q.below());
        java.util.ArrayDeque<BlockPos> open = new java.util.ArrayDeque<>(); java.util.Set<BlockPos> seen = new java.util.HashSet<>(); open.add(p); seen.add(p);
        while (!open.isEmpty() && seen.size() < 16) { BlockPos q = open.poll(); // (where he could walk from there without it: a hollow is a few cells, all below the rim)
            for (Direction d : Direction.Plane.HORIZONTAL) { BlockPos n = q.relative(d);
                if (Math.abs(n.getX() - p.getX()) > 5 || Math.abs(n.getZ() - p.getZ()) > 5) continue;
                for (BlockPos m : new BlockPos[] {n, n.above(), n.below()}) {
                    if (seen.contains(m) || !walk.test(m)) continue;
                    if (m.getY() > q.getY() && so.test(q.above(2))) continue; // (no headroom for the step up)
                    if (m.getY() < q.getY() && so.test(n.above())) continue;
                    if (m.getY() > p.getY()) return false; // (up out of it on foot)
                    seen.add(m.immutable()); open.add(m.immutable()); } } }
        return seen.size() < 16;
    }
    /** Dirt into a hill: his own ground under it, and beside it natural ground that rises past it (the slope it steps up). A lone
     *  pillar has nothing beside it; a raised line over flat ground has nothing higher beside it. */
    /** His dirt at p is a step into the hill, which may stay (the rule read off the block really there: his way may have planned another block). */
    public static boolean dirtHillStep(ClaudeEntity g, ServerLevel level, BlockPos p) { return dirtLike(level.getBlockState(p)) && hillStep(g, level, p); }
    static boolean hillStep(ClaudeEntity g, ServerLevel level, BlockPos p) {
        if (!solid(level, p.below()) || has(g, p.below())) return false;
        for (Direction d : Direction.Plane.HORIZONTAL) { BlockPos n = p.relative(d); if (solid(level, n) && solid(level, n.above()) && !has(g, n) && !has(g, n.above())) return true; }
        return false;
    }

    /** Someone broke a block: if it's on a Claude's ledger, off it. */
    public static void brokenBy(ServerPlayer by, ServerLevel level, BlockPos pos) {
        for (ServerPlayer sp : new ArrayList<>(level.getServer().getPlayerList().getPlayers())) if (sp instanceof ClaudeEntity g && has(g, pos)) broken(by, g, level, pos);
        for (ServerPlayer sp : new ArrayList<>(level.getServer().getPlayerList().getPlayers())) if (sp instanceof ClaudeEntity g && built(g, pos)) { String was = builtCells(g).remove(pos); ClaudeMod.LOGGER.info("[Claude temp] his build at {} (put down by {}) broken by {}: off his builds (TempLedger.brokenBy)", pos.toShortString(), was, by.getGameProfile().getName()); }
    }

    /** He (or someone) broke a block: off the ledger, the after-record written. */
    public static void broken(ServerPlayer by, ClaudeEntity g, ServerLevel level, BlockPos pos) {
        Entry e = book(g, level.dimension()).remove(pos);
        if (e == null) return;
        e.now = line(g);
        e.after = cap((by == g ? "back up after " + ago(level, e.at) + " (task " + task(g.currentTask) + ")" : "taken up by " + by.getGameProfile().getName() + " after " + ago(level, e.at)));
        trace(g, "temp_up", e, null);
        ClaudeMod.LOGGER.info("[Claude temp] {} at {} ({}) {}", e.item, pos.toShortString(), e.why, e.after);
    }

    /** A step still in his plan that works at a table or a furnace: the station stays through the steps before it (every step change took them up, the next smelt found none and the kit asked again: audit 2, stage 1136). Walking off from it is pickUpTempStations' to judge. */
    static boolean stationAhead(ClaudeEntity g) { return g.skillQueue.stream().anyMatch(st -> java.util.Set.of("craft", "smelt", "obtain", "gear_up").contains(st.skill())); }

    /** No job of his needs it now. */
    static boolean doneWith(ClaudeEntity g, ServerLevel level, Entry e, boolean stepStart) { return doneWith(g, level, e, stepStart, false); }
    static boolean doneWith(ClaudeEntity g, ServerLevel level, Entry e, boolean stepStart, boolean feetOk) {
        BlockPos p = e.pos;
        if (g.coverCell(p)) return false; // a block of the cover he's in: the cover's own steps take it down once the threat's gone, nothing else
        if (new Task.Break(p).equals(g.currentTask) || g.tasks.contains(new Task.Break(p))) return false; // on it already
        switch (e.why) {
            case "craft table": return stepStart ? !stationAhead(g) : g.lastStep == null || !java.util.Set.of("craft", "smelt", "obtain").contains(g.lastStep.skill()) || g.skillQueue.isEmpty() && g.tasks.isEmpty() && g.currentTask == null;
            case "furnace": return !(level.getBlockEntity(p) instanceof AbstractFurnaceBlockEntity f) || f.getItem(0).isEmpty() && f.getItem(2).isEmpty() && !(stepStart && stationAhead(g)); // nothing cooking, nothing to take out, and no smelt of his plan coming back to it
            case "light": return com.claudemod.director.ClaudeLife.dayHere(level) && !g.holdingNight();
            case "cover": case "nerd-pole":
                if (g.holdingNight()) return false;
                if (!Perception.known(g, net.minecraft.world.entity.monster.Monster.class, new net.minecraft.world.phys.AABB(p).inflate(16), m -> m.isAlive()).isEmpty()) return false;
                break;
            case "pillar": case "step":
                if (stepStart && g.getBlockY() >= p.getY() + 1 && solid(level, p.below())) return false; // his way up onto where he stands: kept while he works up here, taken when he leaves (put down, taken at the next job 1 s later, put down again 13 s on, review 308, stage 1223)
                break;
            default: break;
        }
        if (g.house != null && g.house.centre().distSqr(p) <= 16 * 16 && !g.house.complete(level)) return false; // his house going up: its scaffold is the build's
        if (g.currentTask instanceof Task.Pillar || g.currentTask instanceof Task.ChopTree || g.currentTask instanceof Task.FinishTree || g.tasks.stream().anyMatch(t -> t instanceof Task.ChopTree || t instanceof Task.FinishTree || t instanceof Task.Pillar)) return false; // the chop's tower is the chop's
        BlockPos f = g.blockPosition();
        if (!feetOk && p.getX() == f.getX() && p.getZ() == f.getZ() && p.getY() < f.getY()) return false; // under his feet: down it first (the tick's own rule), not a break from up there
        return true;
    }

    /** The open cells beside it are a small closed pocket: no sky, none of his mine's walkway, all of it found within 64 cells. Reached only by cutting his mine's shell or the rock. */
    static boolean shutOff(ClaudeEntity g, ServerLevel level, BlockPos pos) {
        if (g.blueprint == null || g.blueprint.isEmpty()) return false;
        java.util.Set<BlockPos> seen = new java.util.HashSet<>(); java.util.ArrayDeque<BlockPos> todo = new java.util.ArrayDeque<>();
        for (Direction d : Direction.values()) { BlockPos n = pos.relative(d); if (level.getBlockState(n).getCollisionShape(level, n).isEmpty() && !g.blueprint.isShell(n) && seen.add(n.immutable())) todo.add(n.immutable()); } // (an open cell of his mine's shell is shut: his patch closes it; through a hole in his floor the pocket read as open, every walk to it cut his wall and his mine filled it back, live 14:29-14:37 11-10, stage 1431)
        while (!todo.isEmpty()) { BlockPos p = todo.poll();
            if (seen.size() > 64 || g.blueprint.isWalk(p) || level.canSeeSky(p) || !level.isLoaded(p)) return false;
            for (Direction d : Direction.values()) { BlockPos n = p.relative(d); if (!n.equals(pos) && level.getBlockState(n).getCollisionShape(level, n).isEmpty() && !g.blueprint.isShell(n) && seen.add(n.immutable())) todo.add(n.immutable()); } }
        return !seen.isEmpty();
    }
    static boolean takeable(ClaudeEntity g, ServerLevel level, Entry e) {
        BlockState s = level.getBlockState(e.pos);
        { var m = level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class, new net.minecraft.world.phys.AABB(e.pos).inflate(8), x -> x.isAlive() && x.getTarget() == g); if (!m.isEmpty()) { ClaudeMod.LOGGER.info("[Claude temp] his {} at {} stays up for now: a {} after him {} off (TempLedger.takeable)", e.item, e.pos.toShortString(), m.get(0).getType().getDescription().getString(), (int) Math.sqrt(m.get(0).blockPosition().distSqr(e.pos))); return false; } } // ponytail: live 2026-11-01, off to explore he took his cover and gap plug down with 4 hoglins after him 7 off; a player keeps the wall till they're gone
        { int air = 0; while (air < 4 && level.getBlockState(e.pos.below(air + 1)).getCollisionShape(level, e.pos.below(air + 1)).isEmpty()) air++;
          if (air >= 2) { e.blocked = "a drop of 2+ under it: it stays, the way back over the gap (dug, its drop falls where the way back out is 2+ up: down after it, out east and the gap jumped again, live 15:06:50 11-12, D2D 151014-59/61, stage 1629)"; return false; }
          for (int d = 1; d <= 3; d++) if (level.getFluidState(e.pos.below(d)).is(net.minecraft.tags.FluidTags.LAVA)) { e.blocked = "over lava: a bridge, it stays (the user 11-05: bridges stay)"; return false; } } /* live 00:05: the tidy at a new step queued his bridge over a 6 drop, the drop guard held it, and the hold asked the user "say 'yes, break it'" for a break nobody ordered */
        if (stepOut(g, level, e.pos)) { if (!"step out".equals(e.blocked)) ClaudeMod.LOGGER.info("[Claude temp] his {} at {} is his step out of a hollow: taken up, every way out of it is 2+ up, so the next walk out puts it back (a block's worth against a pit trip and a re-place each time by): it stays (TempLedger.takeable)", e.item, e.pos.toShortString()); e.blocked = "step out"; return false; }
        if ("step out".equals(e.blocked)) e.blocked = ""; // (the hollow opened up since: litter again)
        { boolean open = false; for (Direction d : Direction.values()) { BlockPos n = e.pos.relative(d); if (level.getBlockState(n).getCollisionShape(level, n).isEmpty()) { open = true; break; } }
          if (!open) { if (!"buried".equals(e.blocked)) ClaudeMod.LOGGER.info("[Claude temp] his {} at {} is buried (solid on all six sides): part of the rock now, not litter; left (TempLedger.takeable)", e.item, e.pos.toShortString()); e.blocked = "buried"; return false; } } // live 01:19: a pillar block in his mine, built over since, re-queued and refused every 10 ticks
        if (shutOff(g, level, e.pos)) { if (!"shut off".equals(e.blocked)) ClaudeMod.LOGGER.info("[Claude temp] his {} at {} faces only a pocket shut off from his mine and the sky: part of the rock now, not litter; left (TempLedger.takeable)", e.item, e.pos.toShortString()); e.blocked = "shut off"; return false; } // (an old dig-in's cover over his tunnel, its hole sealed by his ceiling's patch: the walks to it cut his ceiling, his mine filled it back, 2.5 min, review 19, stage 1416)
        if ("shut off".equals(e.blocked)) e.blocked = "";
        for (Direction d : Direction.values()) { BlockPos n = e.pos.relative(d); if (d != Direction.DOWN && !level.getFluidState(n).isEmpty()) { if (!"holds fluid".equals(e.blocked)) ClaudeMod.LOGGER.info("[Claude temp] his {} at {} holds back the {} at {} now: a plug, not litter; it stays (TempLedger.takeable)", e.item, e.pos.toShortString(), level.getFluidState(n).getType().builtInRegistryHolder().key().location().getPath(), n.toShortString()); e.blocked = "holds fluid"; return false; } } // live 02:01 11-05: his 23-min-old scaffold at -91,14,-46 came up and let the water into his tunnel
        if ("holds fluid".equals(e.blocked)) e.blocked = ""; // (the water gone: litter again)
        if (s.requiresCorrectToolForDrops() && g.inventory.find(t -> t.isCorrectToolForDrops(s)).isEmpty()) { e.blocked = "no tool that drops it"; return false; }
        if (e.blocked.equals("no tool that drops it")) { e.blocked = ""; ClaudeMod.LOGGER.info("[Claude temp] correct tool now on him for {} at {}: the missing-tool hold is cleared (TempLedger.takeable)", e.item, e.pos.toShortString()); } // Live Nov 4 00:19: a previous bare-handed refusal survived a new stone pick forever.
        if (!e.blocked.isEmpty()) return false; // couldn't get it last time: again once he's been away and is back by it (tick clears the hold within 16; a try made from 32+ off read as "been away" at once and the tidy re-queued it every 5 s: live 05:06 11-08, review 109, stage 1030)
        if (Watchdog.off(g, new Task.Break(e.pos))) { if (!"written off".equals(e.blocked)) ClaudeMod.LOGGER.info("[Claude temp] his {} at {}: its take-up is written off by the watchdog (no way to it): left, not queued again (TempLedger.takeable)", e.item, e.pos.toShortString()); e.blocked = "written off"; return false; } // (World 15 13:50: a stray table re-queued every few seconds after the write-off, 32+ lines)
        return true;
    }

    /** At the start of a step that isn't crafting or smelting: what he put down round him for the last one comes back up first. */
    private static final Map<UUID, String> lastSkill = new HashMap<>();
    private static boolean cleanupWaits(ClaudeEntity g) {
        boolean wait = g.isSleeping() || g.holdingNight() || g.getHealth() <= 6 || g.getFoodData().getFoodLevel() <= 6;
        if (!wait) g.attempts.counters.remove("tempUrgentHold");
        else if (g.attempts.counters.putIfAbsent("tempUrgentHold", g.tickCount) == null) ClaudeMod.LOGGER.info("[Claude temp] cleanup waits: sleeping {}, night hold {}, health {}, hunger {} (TempLedger.cleanupWaits)", g.isSleeping(), g.holdingNight(), g.getHealth(), g.getFoodData().getFoodLevel());
        return wait;
    }
    public static int stepStart(ClaudeEntity g, ServerLevel level, String skill) {
        if (DragonFight.active(g)) return 0;
        if (cleanupWaits(g)) return 0; // Live Nov 4 01:13: a 53-walk tidy and the urgent bed trip kept cancelling one another.
        String was = lastSkill.put(g.getUUID(), skill);
        if (java.util.Set.of("craft", "smelt", "obtain", "gear_up").contains(skill)) return 0; // (gear_up only plans the crafts and smelts that use them: live 14:18 World 17, his furnace taken up for gear_up and put down a block over for its smelt; walking off still takes them, TempLedger.leaving)
        if (skill.equals(was) && !"craft".equals(was)) return 0; // the same job going on (its next step): what it put down may still be its own
        List<Entry> near = new ArrayList<>(); BlockPos me = g.blockPosition(); net.minecraft.world.phys.Vec3 eye = g.getEyePosition();
        boolean foes = !Perception.known(g, net.minecraft.world.entity.monster.Monster.class, g.getBoundingBox().inflate(12), m -> m.isAlive()).isEmpty(); // (leaving's rules here too: live 03:29 11-07 a 202-min-old bridge block 4 down beside lava, a magma cube heard on it: a 1 HP drop, a fight, a 14-leg climb back for one block; review 531)
        if (!foes) for (Entry e : book(g).values()) if (e.pos.distSqr(me) <= 6 * 6 && level.isLoaded(e.pos) && handy(g, level, e, me, eye) && !level.getBlockState(e.pos).canBeReplaced() && his(level, e) && doneWith(g, level, e, true) && takeable(g, level, e)) near.add(e); // (his block only: another's in the cell comes off the ledger on the tick)
        int cover = 0; for (Entry e : book(g).values()) if (e.pos.distSqr(g.blockPosition()) <= 6 * 6 && g.coverCell(e.pos)) cover++;
        if (cover > 0) ClaudeMod.LOGGER.info("[Claude temp] off to {}: {} block(s) of the cover he's in left standing, the cover's own steps take them down (TempLedger.stepStart)", skill, cover);
        if (near.isEmpty()) return 0;
        near.sort(Comparator.comparingInt((Entry e) -> -e.pos.getY()));
        { var pk = new Task.Pickup(60); g.tempPickup = pk; g.tempPickupY = g.getBlockY(); g.tasks.addFirst(pk); }
        for (int i = near.size() - 1; i >= 0; i--) { Entry e = near.get(i); g.tasks.addFirst(new Task.Break(e.pos)); e.triedAt = g.tickCount; e.awayAfterTry = false; e.now = line(g); }
        g.tablePlacedTick = -100000;
        ClaudeMod.LOGGER.info("[Claude temp] off to {}: {} of his temporary blocks round him back up first: {}", skill, near.size(), near);
        return near.size();
    }

    /** Every second: what's gone, what he can't get to, the idle tidy, the litter report. */
    public static void tick(ClaudeEntity g, ServerLevel level) {
        if (DragonFight.active(g)) return; // (the End fight: his pillars stay up, the user: they needn't be cleaned up; their take-down queued 159 breaks on him)
        var old = unsorted.get(g.getUUID());
        if (old != null && g.tickCount % 100 == 0) for (var it = old.iterator(); it.hasNext(); ) { Entry e = it.next(); if (level.isLoaded(e.pos) && name(level.getBlockState(e.pos)).equals(e.item)) { it.remove(); book(g, level.dimension()).putIfAbsent(e.pos, e); } }
        var book = book(g); if (book.isEmpty() && owedTrees(g).isEmpty()) return;
        BlockPos me = g.blockPosition();
        for (var it = book.values().iterator(); it.hasNext(); ) {
            Entry e = it.next();
            if (!level.isLoaded(e.pos)) continue;
            if (hisBuild(g, level, e.pos)) { e.after = "a cell of his build now: stays"; trace(g, "temp_left", e, null); it.remove(); ClaudeMod.LOGGER.info("[Claude temp] {} at {} ({}) is in a cell one of his builds owns (his nook, his mine, his house): not temporary, off the ledger (TempLedger.tick)", e.item, e.pos.toShortString(), e.why); continue; }
            double d2 = e.pos.distSqr(me);
            BlockState s = level.getBlockState(e.pos);
            if (!name(s).equals(e.item) && d2 <= 24 * 24 && (s.canBeReplaced() || s.isAir())) { // gone (he sees the spot empty)
                e.now = line(g); e.after = "gone: no longer there (seen " + ago(level, e.at) + " after)"; trace(g, "temp_up", e, null); it.remove();
                ClaudeMod.LOGGER.info("[Claude temp] {} at {} ({}) is gone", e.item, e.pos.toShortString(), e.why); continue;
            }
            if (d2 <= 48 * 48 && !s.canBeReplaced() && !s.isAir() && !his(level, e)) { // someone else's block stands there now (the ground made over): not his to take up (stage 31: stage 30's dirt cells were the test's stone, the tidy dug them and opened the lake)
                e.now = line(g); e.after = cap("not his now: " + name(s) + " stands there (seen " + ago(level, e.at) + " after)"); trace(g, "temp_up", e, null); it.remove();
                ClaudeMod.LOGGER.info("[Claude temp] his {} at {} ({}) isn't there any more: {} stands in the cell now, not his to take up: off the ledger (TempLedger.tick)", e.item, e.pos.toShortString(), e.why, name(s)); continue;
            }
            if (e.triedAt >= 0 && d2 > 32 * 32) e.awayAfterTry = true;
            if (e.triedAt >= 0 && e.blocked.isEmpty() && g.tickCount - e.triedAt > 40 && !new Task.Break(e.pos).equals(g.currentTask) && !g.tasks.contains(new Task.Break(e.pos)) && name(s).equals(e.item)) {
                int span = g.tickCount - e.triedAt + 1;
                boolean failed = g.attempts.within(Attempts.Kind.REACH, e.pos, g.tickCount, span) || g.attempts.within(Attempts.Kind.BREAK, e.pos, g.tickCount, span) || g.attempts.within(Attempts.Kind.WALK, e.pos, g.tickCount, span);
                if (failed) { // the go at it failed (no way in reach, the break refused): on the ledger with the reason, again when he's been away and is back
                    e.blocked = cap("couldn't take it up from " + me.toShortString() + " (" + line(g) + ")"); e.now = line(g);
                    ClaudeMod.LOGGER.info("[Claude temp] couldn't take up his {} at {}: {} (again when he's next by it)", e.item, e.pos.toShortString(), e.blocked);
                } else e.triedAt = -1; // the go was dropped (something came first): it's due again
            }
            if (e.awayAfterTry && d2 <= 16 * 16) { e.blocked = ""; e.triedAt = -1; e.awayAfterTry = false; } // back by it: another go
            if (!e.reported && d2 > 48 * 48) { // he left it behind: litter
                e.reported = true; e.now = line(g); e.after = cap("left standing" + (e.blocked.isEmpty() ? "" : ": " + e.blocked)); trace(g, "temp_left", e, ",\"off\":" + (int) Math.sqrt(d2));
                ClaudeMod.LOGGER.info("[Claude litter] left his {} at {} standing ({}, put down {} ago; before: {}; during: {}; now: {}{}) {} blocks off", e.item, e.pos.toShortString(), e.why, ago(level, e.at), e.before, e.during, e.now, e.blocked.isEmpty() ? "" : "; " + e.blocked, (int) Math.sqrt(d2));
            }
            if (e.reported && d2 <= 16 * 16) e.reported = false; // back in the area: it's his to tidy again (reported again if he leaves it)
        }
        if (cleanupWaits(g)) return; // preserve the ledger above; optional recovery waits for his own urgent needs
        if (leaving(g, level)) return; // setting off past blocks of his he's done with: back up first ("clear before he leaves")
        if ((g.tickCount / 20) % 5 == 0 && idle(g, level)) { // every 5 s, idle: a batch of his blocks back up, or a tree he owes
            BlockPos under = g.blockPosition().below(); Entry ue = book(g).get(under);
            BlockPos spawnerBy = ue != null && ("cover".equals(ue.why) || "nerd-pole".equals(ue.why)) ? Perception.seenSpawner(g, level, under, 16) : null; // a cover by a spawner he has seen: its mobs come back whether he knows of one yet or not (a relaunch empties what he knows; live 07:12:14 11-12, down his cover by the blaze spawner 13 off on the first tick, shot 11 s on; stage 1610)
            if (spawnerBy != null && (g.tickCount / 20) % 60 == 0) ClaudeMod.LOGGER.info("[Claude temp] his {} at {} stays up: the spawner he saw at {} is {} off, its mobs come back (TempLedger.tick)", ue.why, under.toShortString(), spawnerBy.toShortString(), (int) Math.sqrt(spawnerBy.distSqr(under)));
            if (ue != null && spawnerBy == null && g.onGround() && his(level, ue) && doneWith(g, level, ue, false, true) && (com.claudemod.director.ClaudeLife.dayHere(level) || !level.canSeeSky(under))) { // up on a tower of his with nothing left to do up here: down it, the blocks back
                g.tasks.add(new Task.Pillar(-1)); g.tasks.add(new Task.Pickup(60)); ue.triedAt = g.tickCount; ue.awayAfterTry = false; ue.now = line(g);
                ClaudeMod.LOGGER.info("[Claude temp] up on his {} at {} with nothing to do up here: down it, the blocks back", ue.why, under.toShortString());
                return;
            }
            int before = g.tasks.size(); tidy(g, level);
            if (g.tasks.size() == before && com.claudemod.director.ClaudeLife.dayHere(level)) { BlockPos due = treeDue(g); if (due != null) {
                g.tasks.add(new Task.GoTo(due, 3)); g.tasks.add(new Task.ChopTree(due, 0)); g.tasks.add(new Task.Pickup(300));
                ClaudeMod.LOGGER.info("[Claude temp] back by the tree he left at {}: finishing it", due.toShortString()); } }
        }
    }

    /** On his level (a cell up or down), or in reach and in sight: one he'd climb back down to is the climb's (taking it would undo the climb, the old loop). */
    static boolean handy(ClaudeEntity g, ServerLevel level, Entry e, BlockPos me, net.minecraft.world.phys.Vec3 eye) {
        BlockPos first = level.clip(new net.minecraft.world.level.ClipContext(eye, net.minecraft.world.phys.Vec3.atCenterOf(e.pos), net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, g)).getBlockPos();
        if (Math.abs(e.pos.getY() - me.getY()) <= 1) { // on his level: a walk over to it, unless his own build stands between (live 08:09 11-07: a block behind his mine wall, the break's way round dug the wall and put it back, again and again; stage 761)
            if (!first.equals(e.pos) && hisBuild(g, level, first)) { if (g.tickCount - handySaid > 200) { handySaid = g.tickCount; ClaudeMod.LOGGER.info("[Claude temp] his block at {}: his build at {} between, left for a way round (TempLedger.handy)", e.pos.toShortString(), first.toShortString()); } return false; }
            return true; }
        return eye.distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(e.pos)) <= com.claudemod.entity.goals.WorkGoal.BREAK_REACH && e.pos.equals(first);
    }
    private static int handySaid = -100000;

    /**
     * He's setting off (a walk under way, or next in his queue) for somewhere 24+ past blocks of his he's done with, within 16 of
     * him: they come back up first, the way a player takes up his step before he goes (05:26 a step 6.7 off was out of the step
     * start's 6, and it was litter 49 off). Only ones on his level (a cell up or down) or in reach: one he'd climb back down to is
     * the climb's (taking it would undo the climb, the old loop). Stations have their own walk-off rule.
     */
    static boolean leaving(ClaudeEntity g, ServerLevel level) {
        if (!g.onGround() || g.getHealth() < 10 || g.planning || g.holdingNight() || g.currentTask instanceof Task.Attack || g.currentTask instanceof Task.Flee || g.currentTask instanceof Task.Pillar) return false;
        // (in the air his feet's cell is not over the block he stands on: 08:54 the pillar's jump still rising (y 81.9), its fresh step
        // at -10,80,7 read as left behind, broken, put back by the route: a loop)
        boolean walkingNow = g.currentTask instanceof Task.GoTo;
        Task.GoTo go = walkingNow ? (Task.GoTo) g.currentTask : g.tasks.peek() instanceof Task.GoTo n ? n : null;
        if (go == null || book(g).isEmpty()) return false;
        BlockPos dest = go.pos(); List<BlockPos> legs = new ArrayList<>(); legs.add(go.pos()); for (Task q : g.tasks) { if (q instanceof Task.GoTo gq) { dest = gq.pos(); legs.add(gq.pos()); } else if (!(q instanceof Task.Pillar) && !(q instanceof Task.Leap)) break; } // (a route's legs: where the last of them ends; its pillar/leap moves are part of it)
        if (!Perception.known(g, net.minecraft.world.entity.monster.Monster.class, g.getBoundingBox().inflate(12), m -> m.isAlive()).isEmpty()) return false;
        BlockPos me = g.blockPosition(); net.minecraft.world.phys.Vec3 eye = g.getEyePosition(); final BlockPos to = dest;
        java.util.function.Predicate<Entry> due = e -> level.isLoaded(e.pos) && e.pos.distSqr(me) <= 16 * 16 && e.pos.distSqr(to) > 24 * 24 && !e.why.equals("craft table") && !e.why.equals("furnace")
                && !level.getBlockState(e.pos).canBeReplaced() && his(level, e)
                && handy(g, level, e, me, eye) // (in reach AND in sight: 02:2x a block 2 below behind the floor he stood on was queued, the walker refused every stand spot, a 3x CONFLICT)
                && !(e.triedAt >= 0 && g.tickCount - e.triedAt < 40) && !g.attempts.within(Attempts.Kind.BREAK, e.pos, g.tickCount, 20 * 60) && !Watchdog.off(g, new Task.Break(e.pos)) /* its break failed (a structure block in the way, live 01:30): not re-queued every 2 s */ && !(!com.claudemod.director.ClaudeLife.dayHere(level) && level.canSeeSky(e.pos) && !"light".equals(e.why))
                && doneWith(g, level, e, false) && takeable(g, level, e)
                && !e.pos.equals(me.below()) && !e.pos.equals(me) && legs.stream().noneMatch(l -> l.distSqr(e.pos) <= 2 * 2 || Math.abs(l.getX() - e.pos.getX()) <= 1 && Math.abs(l.getZ() - e.pos.getZ()) <= 1 && l.getY() > e.pos.getY() && l.getY() - e.pos.getY() <= 3); // (a leg a cell across and up to 3 above it climbs by it: 2.2 off his next leg, his pillar's block went, he fell back, a minute of it, D2D 173810-56) // still on the way: the route's legs climb over it (01:40 he pillared up for the route, took the pillar down "leaving", fell, pillared again: a loop)
        Entry first = null;
        for (Entry e : book(g).values()) if (due.test(e) && (first == null || e.pos.distSqr(me) < first.pos.distSqr(me))) first = e;
        if (first == null) return false;
        final BlockPos at = first.pos; List<Entry> batch = new ArrayList<>();
        for (Entry e : book(g).values()) if (e.pos.distSqr(at) <= 6 * 6 && due.test(e)) batch.add(e);
        batch.sort(Comparator.comparingInt((Entry e) -> -e.pos.getY())); // top down
        { List<Entry> refused = new ArrayList<>(); // a dig the gate won't let in (ended with nothing changed, nothing since): no reason to set the walk aside; on record a minute (live 18:35 11-11, D2D 183600-98: the walk to his chest 409 off dropped every 2 s for 2 pillar blocks 13 off with no way to them; stage 1530)
          for (Entry e : batch) { var br = new Task.Break(e.pos); g.tasks.addFirst(br); if (g.tasks.remove(br)) continue; refused.add(e); g.attempts.fail(Attempts.Kind.BREAK, e.pos, g.tickCount, me, Attempts.Reason.NO_PATH, "the gate refused it again: " + Gate.LAST_WHY.getOrDefault(g.getUUID(), "-")); }
          if (!refused.isEmpty()) { batch.removeAll(refused); ClaudeMod.LOGGER.info("[Claude temp] setting off for {}: {} of his temporary blocks left standing, their digs refused again by the gate (no way to them): {} (TempLedger.leaving)", to.toShortString(), refused.size(), refused); }
          if (batch.isEmpty()) return false; }
        if (walkingNow) { g.tasks.addFirst(go); ClaudeLife.dropCurrent(g); g.getNavigation().stop(); } // (turned back for them, then on; the walk's path stopped too: the dropped walk's path kept him running east 12 more blocks while the break was "on")
        { var pk = new Task.Pickup(60); g.tempPickup = pk; g.tempPickupY = g.getBlockY(); g.tasks.addFirst(pk); }
        for (int i = batch.size() - 1; i >= 0; i--) { Entry e = batch.get(i); g.tasks.addFirst(new Task.Break(e.pos)); e.triedAt = g.tickCount; e.awayAfterTry = false; e.now = line(g); }
        ClaudeMod.LOGGER.info("[Claude temp] setting off for {} ({} off) with {} of his temporary blocks standing here: back up first: {} (TempLedger.leaving)", to.toShortString(), (int) Math.sqrt(to.distSqr(me)), batch.size(), batch);
        return true;
    }

    static boolean idle(ClaudeEntity g, ServerLevel level) {
        return g.tasks.isEmpty() && g.currentTask == null && g.skillQueue.isEmpty() && !g.planning && !g.busy && !g.holdingCover() && g.getHealth() >= 10
                && !level.getBlockState(g.blockPosition()).is(net.minecraft.world.level.block.Blocks.NETHER_PORTAL) // (never out of a portal he's waiting in: 16:22 a dirt bridge 106 min old pulled him out of it twice; held where they put him: tidy() keeps to blocks in reach)
                && Perception.known(g, net.minecraft.world.entity.monster.Monster.class, g.getBoundingBox().inflate(12), m -> m.isAlive()).isEmpty();
    }

    /** Idle: the nearest batch of his temporary blocks within 48 (top down), and back in his pack. */
    static void tidy(ClaudeEntity g, ServerLevel level) {
        Entry best = null; double bd = g.staying || g.lessonOpen != null && !g.lessonOpen.isEmpty() ? 3 * 3 : 48 * 48; // (held where they put him: only what's in reach, his own pillar under him; no walk off for an old bridge)
        for (Entry e : book(g).values()) {
            if (!level.isLoaded(e.pos) || level.getBlockState(e.pos).canBeReplaced() || !his(level, e) || !doneWith(g, level, e, false) || !takeable(g, level, e)) continue;
            if (e.triedAt >= 0 && g.tickCount - e.triedAt < 40) continue; // just queued
            if (!com.claudemod.director.ClaudeLife.dayHere(level) && level.canSeeSky(e.pos) && !"light".equals(e.why)) continue; // out in the open at night: it waits for daylight
            double d = e.pos.distSqr(g.blockPosition()); if (d < bd) { bd = d; best = e; }
        }
        if (best == null) return;
        final BlockPos at = best.pos;
        List<Entry> batch = new ArrayList<>();
        for (Entry e : book(g).values()) if (e.pos.distSqr(at) <= 6 * 6 && level.isLoaded(e.pos) && !level.getBlockState(e.pos).canBeReplaced() && his(level, e) && doneWith(g, level, e, false) && takeable(g, level, e)) batch.add(e);
        batch.sort(Comparator.comparingInt((Entry e) -> -e.pos.getY())); // top down, like taking a tower apart
        for (Entry e : batch) { g.tasks.add(new Task.Break(e.pos)); e.triedAt = g.tickCount; e.awayAfterTry = false; e.now = line(g); }
        g.tasks.add(new Task.Pickup(80));
        g.noteDoing("taking up the blocks i put down at " + at.toShortString());
        ClaudeMod.LOGGER.info("[Claude temp] tidying up: {} of his temporary blocks at {} back in his pack: {}", batch.size(), at.toShortString(), batch);
    }

    // ---- trees he couldn't finish
    public static void treeOwed(ClaudeEntity g, ServerLevel level, BlockPos base, int logs, String why) {
        Owed o = owedTrees(g).computeIfAbsent(base.immutable(), k -> new Owed(k, logs, why, level.getGameTime()));
        o.logs = logs; o.why = cap(why); o.triedAt = g.tickCount; o.awayAfterTry = false;
        try { com.claudemod.context.PlayerTrace.temp(g, "tree_left", String.format(Locale.ROOT, "\"at\":[%d,%d,%d],\"logs\":%d,\"why\":\"%s\",\"step\":\"%s\",\"line\":\"%s\"", base.getX(), base.getY(), base.getZ(), logs, esc(o.why), esc(stepOf(g)), esc(line(g)))); } catch (NoClassDefFoundError ignored) {}
        ClaudeMod.LOGGER.info("[Claude litter] the tree at {} isn't finished: {} logs still up ({}); back for them when he's by it", base.toShortString(), logs, o.why);
    }
    public static void treeDone(ClaudeEntity g, BlockPos base) { owedTrees(g).remove(base); }
    /** A tree he owes, now that he's been away and is back by it (idle): its chop again. */
    public static BlockPos treeDue(ClaudeEntity g) {
        for (Owed o : owedTrees(g).values()) {
            double d2 = o.base.distSqr(g.blockPosition());
            if (d2 > 32 * 32) o.awayAfterTry = true;
            if (o.awayAfterTry && d2 <= 16 * 16) { o.awayAfterTry = false; o.triedAt = g.tickCount; return o.base; }
        }
        return null;
    }

    /** For his situation text: what of his he left standing out there. */
    public static String describe(ClaudeEntity g, ServerLevel level) {
        List<String> out = new ArrayList<>(); BlockPos me = g.blockPosition();
        List<Entry> es = new ArrayList<>(book(g).values()); es.sort(Comparator.comparingDouble(e -> e.pos.distSqr(me)));
        Map<String, Integer> nearKinds = new LinkedHashMap<>();
        for (Entry e : es) { if (e.pos.distSqr(me) <= 48 * 48) nearKinds.merge(e.item + " " + e.why, 1, Integer::sum); else if (out.size() < 8) out.add(e.item + " (" + e.why + ") at " + e.pos.toShortString() + " " + (int) Math.sqrt(e.pos.distSqr(me)) + " off" + (e.blocked.isEmpty() ? "" : ", " + e.blocked)); }
        for (Owed o : owedTrees(g).values()) if (out.size() < 10) out.add("a half-chopped tree at " + o.base.toShortString() + " (" + o.logs + " logs up: " + o.why + ")");
        if (out.isEmpty() && nearKinds.isEmpty()) return "";
        return "YOUR LITTER (blocks you put down for a job and haven't taken back up; a player picks them up before moving on): " + (nearKinds.isEmpty() ? "" : "round you " + nearKinds + "; ") + (out.isEmpty() ? "" : "left behind: " + String.join("; ", out)) + ".\n";
    }

    // ---- save / load (his mine record, "temps")
    public static CompoundTag save(ClaudeEntity g) {
        CompoundTag t = new CompoundTag(); ListTag l = new ListTag();
        for (var de : books.getOrDefault(g.getUUID(), Map.of()).entrySet()) for (Entry e : de.getValue().values()) {
            CompoundTag c = new CompoundTag(); c.putString("dim", de.getKey().location().toString()); c.putLong("p", e.pos.asLong()); c.putString("i", e.item); c.putString("w", e.why); c.putLong("t", e.at);
            c.putString("b", e.before); c.putString("d", e.during); c.putString("n", e.now); c.putString("x", e.blocked); c.putBoolean("r", e.reported); l.add(c);
        }
        for (Entry e : unsorted.getOrDefault(g.getUUID(), List.of())) { CompoundTag c = new CompoundTag(); c.putLong("p", e.pos.asLong()); c.putString("i", e.item); c.putString("w", e.why); c.putLong("t", e.at); c.putString("b", e.before); c.putString("d", e.during); c.putString("n", e.now); c.putString("x", e.blocked); c.putBoolean("r", e.reported); l.add(c); }
        t.put("e", l); ListTag tr = new ListTag();
        for (Owed o : owedTrees(g).values()) { CompoundTag c = new CompoundTag(); c.putLong("p", o.base.asLong()); c.putInt("n", o.logs); c.putString("w", o.why); c.putLong("t", o.at); tr.add(c); }
        t.put("trees", tr);
        ListTag bl = new ListTag(); for (var e : builtCells(g).entrySet()) { CompoundTag c = new CompoundTag(); c.putLong("p", e.getKey().asLong()); c.putString("w", e.getValue()); bl.add(c); } t.put("built", bl); // his builds by who put them
        return t;
    }
    public static void load(ClaudeEntity g, CompoundTag t) {
        books.remove(g.getUUID()); List<Entry> old = new ArrayList<>(); unsorted.put(g.getUUID(), old); int n = 0;
        for (Tag x : t.getList("e", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) x;
            Entry e = new Entry(BlockPos.of(c.getLong("p")), c.getString("i"), c.getString("w"), c.getLong("t"), c.getString("b"), c.getString("d"));
            e.now = c.getString("n"); e.blocked = c.getString("x"); e.reported = c.getBoolean("r"); if (!e.blocked.isEmpty()) { e.triedAt = 0; e.awayAfterTry = false; }
            if (c.contains("dim")) book(g, net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, net.minecraft.resources.ResourceLocation.parse(c.getString("dim")))).put(e.pos, e); else old.add(e);
            n++;
        }
        { var bc = builtCells(g); bc.clear(); for (Tag x : t.getList("built", Tag.TAG_COMPOUND)) { CompoundTag c = (CompoundTag) x; bc.put(BlockPos.of(c.getLong("p")), c.getString("w")); } }
        var tr = owedTrees(g); tr.clear();
        for (Tag x : t.getList("trees", Tag.TAG_COMPOUND)) { CompoundTag c = (CompoundTag) x; Owed o = new Owed(BlockPos.of(c.getLong("p")), c.getInt("n"), c.getString("w"), c.getLong("t")); tr.put(o.base, o); }
        if (n > 0 || !tr.isEmpty()) ClaudeMod.LOGGER.info("[Claude temp] {} temporary block(s) ({} from before dimensions were kept: claimed where his block stands) and {} unfinished tree(s) back from his saved state", n, old.size(), tr.size());
    }
    /** A save from before the ledger: his old take-down lists, so nothing on them is forgotten. */
    public static void seedFrom(ClaudeEntity g, ServerLevel level) {
        long now = level == null ? 0 : level.getGameTime();
        for (BlockPos p : g.tempStations) book(g).putIfAbsent(p, new Entry(p, level != null && level.isLoaded(p) ? name(level.getBlockState(p)) : "crafting_table", "craft table", now, "from his old list", ""));
        for (BlockPos p : g.pillarBlocks) book(g).putIfAbsent(p, new Entry(p, level != null && level.isLoaded(p) ? name(level.getBlockState(p)) : "dirt", "pillar", now, "from his old list", ""));
        for (BlockPos p : ClaudeLife.bridgeBlocks()) book(g).putIfAbsent(p, new Entry(p, level != null && level.isLoaded(p) ? name(level.getBlockState(p)) : "cobblestone", "bridge", now, "from his old list", ""));
    }
}
