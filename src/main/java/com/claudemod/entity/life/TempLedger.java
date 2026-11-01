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

    private static final Map<UUID, LinkedHashMap<BlockPos, Entry>> books = new HashMap<>();
    private static final Map<UUID, LinkedHashMap<BlockPos, Owed>> trees = new HashMap<>();

    public static LinkedHashMap<BlockPos, Entry> book(ClaudeEntity g) { return books.computeIfAbsent(g.getUUID(), k -> new LinkedHashMap<>()); }
    public static LinkedHashMap<BlockPos, Owed> owedTrees(ClaudeEntity g) { return trees.computeIfAbsent(g.getUUID(), k -> new LinkedHashMap<>()); }
    public static void reset(ClaudeEntity g) { books.remove(g.getUUID()); trees.remove(g.getUUID()); lastSkill.remove(g.getUUID()); if (builds != null) builds.remove(g.getUUID()); }

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
            "WorkGoal.doMineVisibleOres", "WorkGoal.doBreak", "WorkGoal.doSealStep", "WorkGoal.blockWaterInflow");
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
    static boolean hisBuild(ClaudeEntity g, ServerLevel level, BlockPos p) {
        if (WatchBuild.ownsCell(g, p)) return true; // a learned build going up (or done): its blocks stay
        if (g.blueprint != null && !g.blueprint.isEmpty() && g.blueprint.touches(p)) return true;
        if (g.house != null && g.house.touches(p)) return true;
        if (g.farm != null && g.farm.touches(p)) return true;
        if (com.claudemod.director.ClaudeLife.anyNookTouches(g, p)) return true;
        if (ClaudeLife.inHisNook(g, p)) return true; // the old nook's pocket too, while it's moved: its swaps and its fill are the wall going back (live: the walling's cobble booked as "cover", broken straight back out by the step start)
        if (g.notches.contains(p) || g.storageChests.contains(p) || ClaudeLife.isJunkChest(g, p)) return true;
        if (PitGuard.isCap(g, p)) return true; // the mouth of a hole by his door he closed: it stays
        if (ClaudeLife.portalFrameOf(g, p) != null || ClaudeLife.portalFrameKnown(g, p) != null) return true; // his portal's frame and corners (or the corners they had him fill): they stay
        if (built(g, p)) return true; // a block a build system of his put there (his bedroom's wall)
        return ClaudeLife.inHisMine(g, p) || ClaudeLife.touchesMine(g, p); // in the mine, placing is a patch
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
        if (g.coverCell(p)) return "cover"; // a block of the cover he's making (by its shape the box's walls read as "step", "bridge", "scaffold", booked to the hunt that was running, live 04:42)
        if (placed.hasBlockEntity() || !placed.isCollisionShapeFullBlock(level, p) || b == Blocks.OBSIDIAN) return null; // chests, beds, crops, saplings, fences, the portal's frame: none of it temporary
        BlockPos feet = g.blockPosition();
        int sides = 0, natural = 0;
        for (Direction d : Direction.Plane.HORIZONTAL) { BlockPos n = p.relative(d); if (solid(level, n)) { sides++; if (!has(g, n)) natural++; } }
        if (natural >= 3 && solid(level, p.below())) return null; // a hole filled (the ground made good again)
        boolean hostile = !Perception.known(g, net.minecraft.world.entity.monster.Monster.class, g.getBoundingBox().inflate(12), m -> m.isAlive()).isEmpty();
        if (p.getX() == feet.getX() && p.getZ() == feet.getZ() && p.getY() < feet.getY()) return hostile || g.currentTask instanceof Task.Flee ? "nerd-pole" : "pillar";
        if (p.getY() <= feet.getY() - 1 && !solid(level, p.below())) return "bridge";
        if (p.getY() > feet.getY() && Math.abs(p.getX() - feet.getX()) <= 1 && Math.abs(p.getZ() - feet.getZ()) <= 1) return hostile || g.holdingNight() || !level.isDay() ? "cover" : "scaffold";
        if (p.getY() >= feet.getY() - 1 && p.getY() <= feet.getY()) return "step";
        return "scaffold";
    }

    /** The place event: he put a block down. */
    public static void placed(ClaudeEntity g, ServerLevel level, BlockPos pos, BlockState placed, BlockState replaced) {
        { String by = buildOrigin(g, pos); if (by != null) { // a build system of his put it there: his build (live: secureBedroom's walls booked "cover"/"step", tidied back out, walled again, round and round)
            var bc = builtCells(g); bc.remove(pos); bc.put(pos.immutable(), by); while (bc.size() > 4096) bc.remove(bc.keySet().iterator().next());
            if (book(g).remove(pos) != null) trace(g, "temp_left", new Entry(pos.immutable(), name(placed), "build", level.getGameTime(), before(g), line(g)), null);
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
        if (why.equals("step") && dirtLike(placed) && hillStep(g, level, p)) { // the user: dirt may stay (a hill)
            e.after = "kept: a dirt step into the hill (may stay)"; trace(g, "temp_left", e, null);
            ClaudeMod.LOGGER.info("[Claude temp] dirt step at {} into the hill: it may stay", p.toShortString());
            return;
        }
        book(g).put(p, e);
        // a station also goes on the old station queue (the walk-off rule turns him back for a table or furnace he's leaving); his
        // blocks don't (the walk-off rule for towers sent him back down a ledge for the steps that got him up it, then up it again
        // on new ones): the ledger's own rules take those, and the climb itself takes its steps back from the top
        if ((why.equals("craft table") || why.equals("furnace")) && !g.tempStations.contains(p)) g.tempStations.add(p);
        trace(g, "temp", e, null);
        ClaudeMod.LOGGER.info("[Claude temp] put down {} at {} ({}): {} | {}", e.item, p.toShortString(), why, e.before, e.during);
    }

    /** Dirt into a hill: his own ground under it, and beside it natural ground that rises past it (the slope it steps up). A lone
     *  pillar has nothing beside it; a raised line over flat ground has nothing higher beside it. */
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
        Entry e = book(g).remove(pos);
        if (e == null) return;
        e.now = line(g);
        e.after = cap((by == g ? "back up after " + ago(level, e.at) + " (task " + task(g.currentTask) + ")" : "taken up by " + by.getGameProfile().getName() + " after " + ago(level, e.at)));
        trace(g, "temp_up", e, null);
        ClaudeMod.LOGGER.info("[Claude temp] {} at {} ({}) {}", e.item, pos.toShortString(), e.why, e.after);
    }

    /** No job of his needs it now. */
    static boolean doneWith(ClaudeEntity g, ServerLevel level, Entry e, boolean stepStart) { return doneWith(g, level, e, stepStart, false); }
    static boolean doneWith(ClaudeEntity g, ServerLevel level, Entry e, boolean stepStart, boolean feetOk) {
        BlockPos p = e.pos;
        if (g.coverCell(p)) return false; // a block of the cover he's in: the cover's own steps take it down once the threat's gone, nothing else
        if (new Task.Break(p).equals(g.currentTask) || g.tasks.contains(new Task.Break(p))) return false; // on it already
        switch (e.why) {
            case "craft table": return stepStart || g.lastStep == null || !java.util.Set.of("craft", "smelt", "obtain").contains(g.lastStep.skill()) || g.skillQueue.isEmpty() && g.tasks.isEmpty() && g.currentTask == null;
            case "furnace": return !(level.getBlockEntity(p) instanceof AbstractFurnaceBlockEntity f) || f.getItem(0).isEmpty() && f.getItem(2).isEmpty(); // nothing cooking, nothing to take out
            case "light": return level.isDay() && !g.holdingNight();
            case "cover": case "nerd-pole":
                if (g.holdingNight()) return false;
                if (!Perception.known(g, net.minecraft.world.entity.monster.Monster.class, new net.minecraft.world.phys.AABB(p).inflate(16), m -> m.isAlive()).isEmpty()) return false;
                break;
            default: break;
        }
        if (g.house != null && g.house.centre().distSqr(p) <= 16 * 16 && !g.house.complete(level)) return false; // his house going up: its scaffold is the build's
        if (g.currentTask instanceof Task.Pillar || g.currentTask instanceof Task.ChopTree || g.currentTask instanceof Task.FinishTree || g.tasks.stream().anyMatch(t -> t instanceof Task.ChopTree || t instanceof Task.FinishTree || t instanceof Task.Pillar)) return false; // the chop's tower is the chop's
        BlockPos f = g.blockPosition();
        if (!feetOk && p.getX() == f.getX() && p.getZ() == f.getZ() && p.getY() < f.getY()) return false; // under his feet: down it first (the tick's own rule), not a break from up there
        return true;
    }

    static boolean takeable(ClaudeEntity g, ServerLevel level, Entry e) {
        BlockState s = level.getBlockState(e.pos);
        { int air = 0; while (air < 4 && level.getBlockState(e.pos.below(air + 1)).getCollisionShape(level, e.pos.below(air + 1)).isEmpty()) air++;
          if (air >= 4) { e.blocked = "a drop of 4+ under it: it stays, the way back over the gap"; return false; } } /* live 00:05: the tidy at a new step queued his bridge over a 6 drop, the drop guard held it, and the hold asked the user "say 'yes, break it'" for a break nobody ordered */
        { boolean open = false; for (Direction d : Direction.values()) { BlockPos n = e.pos.relative(d); if (level.getBlockState(n).getCollisionShape(level, n).isEmpty()) { open = true; break; } }
          if (!open) { if (!"buried".equals(e.blocked)) ClaudeMod.LOGGER.info("[Claude temp] his {} at {} is buried (solid on all six sides): part of the rock now, not litter; left (TempLedger.takeable)", e.item, e.pos.toShortString()); e.blocked = "buried"; return false; } } // live 01:19: a pillar block in his mine, built over since, re-queued and refused every 10 ticks
        if (s.requiresCorrectToolForDrops() && g.inventory.find(t -> t.isCorrectToolForDrops(s)).isEmpty()) { e.blocked = "no tool that drops it"; return false; }
        if (!e.blocked.isEmpty() && !e.awayAfterTry) return false; // couldn't get it last time: again once he's been away and is back by it
        if (Watchdog.off(g, new Task.Break(e.pos))) { if (!"written off".equals(e.blocked)) ClaudeMod.LOGGER.info("[Claude temp] his {} at {}: its take-up is written off by the watchdog (no way to it): left, not queued again (TempLedger.takeable)", e.item, e.pos.toShortString()); e.blocked = "written off"; return false; } // (World 15 13:50: a stray table re-queued every few seconds after the write-off, 32+ lines)
        return true;
    }

    /** At the start of a step that isn't crafting or smelting: what he put down round him for the last one comes back up first. */
    private static final Map<UUID, String> lastSkill = new HashMap<>();
    public static int stepStart(ClaudeEntity g, ServerLevel level, String skill) {
        String was = lastSkill.put(g.getUUID(), skill);
        if (java.util.Set.of("craft", "smelt", "obtain").contains(skill)) return 0;
        if (skill.equals(was) && !"craft".equals(was)) return 0; // the same job going on (its next step): what it put down may still be its own
        List<Entry> near = new ArrayList<>();
        for (Entry e : book(g).values()) if (e.pos.distSqr(g.blockPosition()) <= 6 * 6 && level.isLoaded(e.pos) && !level.getBlockState(e.pos).canBeReplaced() && his(level, e) && doneWith(g, level, e, true) && takeable(g, level, e)) near.add(e); // (his block only: another's in the cell comes off the ledger on the tick)
        int cover = 0; for (Entry e : book(g).values()) if (e.pos.distSqr(g.blockPosition()) <= 6 * 6 && g.coverCell(e.pos)) cover++;
        if (cover > 0) ClaudeMod.LOGGER.info("[Claude temp] off to {}: {} block(s) of the cover he's in left standing, the cover's own steps take them down (TempLedger.stepStart)", skill, cover);
        if (near.isEmpty()) return 0;
        near.sort(Comparator.comparingInt((Entry e) -> -e.pos.getY()));
        g.tasks.addFirst(new Task.Pickup(60));
        for (int i = near.size() - 1; i >= 0; i--) { Entry e = near.get(i); g.tasks.addFirst(new Task.Break(e.pos)); e.triedAt = g.tickCount; e.awayAfterTry = false; e.now = line(g); }
        g.tablePlacedTick = -100000;
        ClaudeMod.LOGGER.info("[Claude temp] off to {}: {} of his temporary blocks round him back up first: {}", skill, near.size(), near);
        return near.size();
    }

    /** Every second: what's gone, what he can't get to, the idle tidy, the litter report. */
    public static void tick(ClaudeEntity g, ServerLevel level) {
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
        if (leaving(g, level)) return; // setting off past blocks of his he's done with: back up first ("clear before he leaves")
        if ((g.tickCount / 20) % 5 == 0 && idle(g, level)) { // every 5 s, idle: a batch of his blocks back up, or a tree he owes
            BlockPos under = g.blockPosition().below(); Entry ue = book(g).get(under);
            if (ue != null && g.onGround() && his(level, ue) && doneWith(g, level, ue, false, true) && (level.isDay() || !level.canSeeSky(under))) { // up on a tower of his with nothing left to do up here: down it, the blocks back
                g.tasks.add(new Task.Pillar(-1)); g.tasks.add(new Task.Pickup(60)); ue.triedAt = g.tickCount; ue.awayAfterTry = false; ue.now = line(g);
                ClaudeMod.LOGGER.info("[Claude temp] up on his {} at {} with nothing to do up here: down it, the blocks back", ue.why, under.toShortString());
                return;
            }
            int before = g.tasks.size(); tidy(g, level);
            if (g.tasks.size() == before && level.isDay()) { BlockPos due = treeDue(g); if (due != null) {
                g.tasks.add(new Task.GoTo(due, 3)); g.tasks.add(new Task.ChopTree(due, 0)); g.tasks.add(new Task.Pickup(300));
                ClaudeMod.LOGGER.info("[Claude temp] back by the tree he left at {}: finishing it", due.toShortString()); } }
        }
    }

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
                && (Math.abs(e.pos.getY() - me.getY()) <= 1 || eye.distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(e.pos)) <= 4.5 && e.pos.equals(level.clip(new net.minecraft.world.level.ClipContext(eye, net.minecraft.world.phys.Vec3.atCenterOf(e.pos), net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, g)).getBlockPos())) // (in reach AND in sight: 02:2x a block 2 below behind the floor he stood on was queued, the walker refused every stand spot, a 3x CONFLICT)
                && !(e.triedAt >= 0 && g.tickCount - e.triedAt < 40) && !(!level.isDay() && level.canSeeSky(e.pos) && !"light".equals(e.why))
                && doneWith(g, level, e, false) && takeable(g, level, e)
                && !e.pos.equals(me.below()) && !e.pos.equals(me) && legs.stream().noneMatch(l -> l.distSqr(e.pos) <= 2 * 2); // still on the way: the route's legs climb over it (01:40 he pillared up for the route, took the pillar down "leaving", fell, pillared again: a loop)
        Entry first = null;
        for (Entry e : book(g).values()) if (due.test(e) && (first == null || e.pos.distSqr(me) < first.pos.distSqr(me))) first = e;
        if (first == null) return false;
        final BlockPos at = first.pos; List<Entry> batch = new ArrayList<>();
        for (Entry e : book(g).values()) if (e.pos.distSqr(at) <= 6 * 6 && due.test(e)) batch.add(e);
        batch.sort(Comparator.comparingInt((Entry e) -> -e.pos.getY())); // top down
        if (walkingNow) { g.tasks.addFirst(go); ClaudeLife.dropCurrent(g); g.getNavigation().stop(); } // (turned back for them, then on; the walk's path stopped too: the dropped walk's path kept him running east 12 more blocks while the break was "on")
        g.tasks.addFirst(new Task.Pickup(60));
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
            if (!level.isDay() && level.canSeeSky(e.pos) && !"light".equals(e.why)) continue; // out in the open at night: it waits for daylight
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
        for (Entry e : book(g).values()) {
            CompoundTag c = new CompoundTag(); c.putLong("p", e.pos.asLong()); c.putString("i", e.item); c.putString("w", e.why); c.putLong("t", e.at);
            c.putString("b", e.before); c.putString("d", e.during); c.putString("n", e.now); c.putString("x", e.blocked); c.putBoolean("r", e.reported); l.add(c);
        }
        t.put("e", l); ListTag tr = new ListTag();
        for (Owed o : owedTrees(g).values()) { CompoundTag c = new CompoundTag(); c.putLong("p", o.base.asLong()); c.putInt("n", o.logs); c.putString("w", o.why); c.putLong("t", o.at); tr.add(c); }
        t.put("trees", tr);
        ListTag bl = new ListTag(); for (var e : builtCells(g).entrySet()) { CompoundTag c = new CompoundTag(); c.putLong("p", e.getKey().asLong()); c.putString("w", e.getValue()); bl.add(c); } t.put("built", bl); // his builds by who put them
        return t;
    }
    public static void load(ClaudeEntity g, CompoundTag t) {
        var book = book(g); book.clear();
        for (Tag x : t.getList("e", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) x;
            Entry e = new Entry(BlockPos.of(c.getLong("p")), c.getString("i"), c.getString("w"), c.getLong("t"), c.getString("b"), c.getString("d"));
            e.now = c.getString("n"); e.blocked = c.getString("x"); e.reported = c.getBoolean("r"); if (!e.blocked.isEmpty()) { e.triedAt = 0; e.awayAfterTry = false; }
            book.put(e.pos, e);
        }
        { var bc = builtCells(g); bc.clear(); for (Tag x : t.getList("built", Tag.TAG_COMPOUND)) { CompoundTag c = (CompoundTag) x; bc.put(BlockPos.of(c.getLong("p")), c.getString("w")); } }
        var tr = owedTrees(g); tr.clear();
        for (Tag x : t.getList("trees", Tag.TAG_COMPOUND)) { CompoundTag c = (CompoundTag) x; Owed o = new Owed(BlockPos.of(c.getLong("p")), c.getInt("n"), c.getString("w"), c.getLong("t")); tr.put(o.base, o); }
        if (!book.isEmpty() || !tr.isEmpty()) ClaudeMod.LOGGER.info("[Claude temp] {} temporary block(s) and {} unfinished tree(s) back from his saved state", book.size(), tr.size());
    }
    /** A save from before the ledger: his old take-down lists, so nothing on them is forgotten. */
    public static void seedFrom(ClaudeEntity g, ServerLevel level) {
        long now = level == null ? 0 : level.getGameTime();
        for (BlockPos p : g.tempStations) book(g).putIfAbsent(p, new Entry(p, level != null && level.isLoaded(p) ? name(level.getBlockState(p)) : "crafting_table", "craft table", now, "from his old list", ""));
        for (BlockPos p : g.pillarBlocks) book(g).putIfAbsent(p, new Entry(p, level != null && level.isLoaded(p) ? name(level.getBlockState(p)) : "dirt", "pillar", now, "from his old list", ""));
        for (BlockPos p : ClaudeLife.bridgeBlocks()) book(g).putIfAbsent(p, new Entry(p, level != null && level.isLoaded(p) ? name(level.getBlockState(p)) : "cobblestone", "bridge", now, "from his old list", ""));
    }
}
