package com.claudemod.director;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.claudemod.ClaudeMod;
import com.claudemod.context.PlayerProfile;
import com.claudemod.entity.ClaudeEntity;
import com.claudemod.entity.life.MindLog;
import com.claudemod.entity.life.OwnEvents;
import com.claudemod.entity.life.Perception;
import com.claudemod.entity.life.Skills;
import com.claudemod.entity.life.Task;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Primitive steps (2026-10-30, the user live: "you have to mine out the wall you patched in front of them first"; he understood,
 * and no skill could say it). dig_at x y z and place_at x y z block, beside go_to x y z and pick_up, for a plan to sequence when no
 * skill fits. They queue his ordinary body tasks (Task.Break / Task.Place / Task.GoTo / Task.Pickup), so every survival rule still
 * holds where it lives (WorkGoal); before they queue, the same rules are asked here so a refusal is the step's own result, with
 * why; once their tasks are done the world is checked (a guard that refused at the block shows up as the step's failure, with that
 * guard's own line). A spot he can't know (never seen, not his build, nobody told him) is refused: a player can't dig at a place he
 * knows nothing about, except on the way in to a spot he knows or was told. Each finished sequence is one "[Claude prim] sequence
 * for '<ask>': [..] -> <outcome>" line: what a later learned-skills pass reads.
 */
public final class Primitives {
    private Primitives() {}

    static final Pattern XYZ = Pattern.compile("(-?\\d+)[ ,_]+(-?\\d+)[ ,_]+(-?\\d+)(?:[ ,_]+(?:minecraft:)?([a-z0-9_]+))?");
    static final Pattern ANY_XYZ = Pattern.compile("(-?\\d+)[ ,]+(-?\\d+)[ ,]+(-?\\d+)");
    public static final Set<String> SEQ = Set.of("dig_at", "place_at", "go_to", "pick_up"); // the steps a primitive sequence is made of

    /** A primitive sequence in progress: its ask, its steps with how each went, the cells it took out, the step waiting on the world. */
    static final class Seq {
        final String ask; final int started; final List<String> steps = new ArrayList<>(); final Set<BlockPos> dug = new HashSet<>();
        BlockPos pendingAt; Item pendingItem; String pendingLine, pendingSkill;
        Seq(String ask, int started) { this.ask = ask; this.started = started; }
    }
    private static Map<UUID, Seq> seqs; // (static, made on first use: fields added live start null)
    private static Map<UUID, Set<BlockPos>> told;
    static Map<UUID, Seq> seqs() { if (seqs == null) seqs = new HashMap<>(); return seqs; }
    static Map<UUID, Set<BlockPos>> told() { if (told == null) told = new HashMap<>(); return told; }
    public static void reset(ClaudeEntity g) { seqs().remove(g.getUUID()); if (g.getTarget() != null) told().remove(g.getTarget().getUUID()); } // (tests)
    /** A sequence of primitives is under way (its last step not yet checked, or more of it queued). */
    public static boolean open(ClaudeEntity g) { return seqs().containsKey(g.getUUID()); }

    static Seq seq(ClaudeEntity g) { return seqs().computeIfAbsent(g.getUUID(), k -> new Seq(g.commandActive && !g.playerGoal.isBlank() ? g.playerGoal : g.goal, g.tickCount)); }
    static String xyz(BlockPos p) { return OwnEvents.xyz(p); }
    static String name(BlockState s) { return s.getBlock().getName().getString().toLowerCase(); }

    /** Their chat line: every "x y z" in it is a spot they told him of (dig there, put it there, the diamonds are at). */
    public static void heard(ServerPlayer p, String raw) {
        if (raw == null || p instanceof ClaudeEntity) return;
        Matcher m = ANY_XYZ.matcher(raw);
        while (m.find()) {
            try {
                BlockPos at = new BlockPos(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
                Set<BlockPos> s = told().computeIfAbsent(p.getUUID(), k -> new LinkedHashSet<>());
                s.add(at); if (s.size() > 64) s.remove(s.iterator().next());
                ClaudeMod.LOGGER.info("[Claude prim] they told him of {} (Primitives.heard)", xyz(at));
            } catch (NumberFormatException e) { /* not coordinates */ }
        }
    }

    static boolean toldOf(ClaudeEntity g, ServerPlayer player, BlockPos p) {
        if (player != null && told().getOrDefault(player.getUUID(), Set.of()).contains(p)) return true;
        for (String words : new String[] {g.playerGoal, g.goal}) { // (their order's own words: the chat model's goal carries the coordinates they gave)
            if (words == null) continue;
            Matcher m = ANY_XYZ.matcher(words);
            while (m.find()) try { if (new BlockPos(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))).equals(p)) return true; } catch (NumberFormatException e) { }
        }
        return false;
    }

    /** His own builds and things: his mine (its walkway and shell), house, farm, nook, stations, chests, scaffold, patches waiting. */
    static String ownBuild(ClaudeEntity g, BlockPos p) {
        if (ClaudeLife.bpMode(g) && g.blueprint != null && !g.blueprint.isEmpty() && g.blueprint.touches(p)) return "my mine's " + g.blueprint.roleAt(p).name().toLowerCase();
        if (ClaudeLife.isMineCell(g, p)) return "my mine";
        if (g.house != null && g.house.touches(p)) return "my house";
        if (g.farm != null && g.farm.touches(p)) return "my farm";
        if (g.nook != null && g.nook.touches(p)) return "my base nook";
        if (g.myStations.contains(p) || g.tempStations.contains(p) || g.notches.contains(p) || com.claudemod.entity.life.TempLedger.has(g, p)) return "a station of mine";
        if (com.claudemod.entity.life.ChestLedger.knows(g, p)) return "a chest I know";
        if (g.pillarBlocks.contains(p) || ClaudeLife.bridgeBlocks().contains(p) || g.pendingPatches.contains(p)) return "a block I put down";
        { String fr = ClaudeLife.portalFrameOf(g, p); if (fr != null) return fr; } // his portal, its corners too (16:15 place_at -20 67 -26: "never seen it, not one of my builds")
        return null;
    }

    /** How he knows what's at the spot, or null: in sight now, ore he saw, his own build or events, fluid he saw, told of it, or on the way in to one of those. */
    static String knownHow(ClaudeEntity g, ServerPlayer player, ServerLevel level, BlockPos p) {
        if (ClaudeLife.canSee(g, level, p)) return "in sight";
        if (level.getBlockState(p).isAir() && g.getEyePosition().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(p)) <= 8) { // an open cell: seen if nothing stands between his eyes and it (16:15 a portal corner 2 off read "never seen")
            var hit = level.clip(new net.minecraft.world.level.ClipContext(g.getEyePosition(), net.minecraft.world.phys.Vec3.atCenterOf(p), net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, g));
            if (hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS || hit.getBlockPos().equals(p)) return "in sight (open)";
        }
        if (ClaudeLife.seenOre(g).contains(p) || g.spottedDiamonds.contains(p)) return "ore I saw there";
        Seq s = seqs().get(g.getUUID()); if (s != null && s.dug.contains(p)) return "a block I just took out";
        String own = ownBuild(g, p); if (own != null) return own;
        { String fr = ClaudeLife.portalFrameKnown(g, p); if (fr != null) return fr; } // (a frame in view they had him fill)
        for (OwnEvents.Event e : OwnEvents.all(g)) if (e.at.equals(p) && e.dim == level.dimension()) return "on my own events (" + e.kind.name().toLowerCase() + " " + e.what + ")";
        if (Perception.knowsLava(g, p) || Perception.knowsWater(g, p)) return "fluid I saw there";
        if (toldOf(g, player, p)) return "they told me";
        if (com.claudemod.entity.life.CopyMode.knows(g, p)) return "I watched them work it (or the sequence they taught names it)";
        BlockPos to = towardKnown(g, player, level, p);
        if (to != null) return "on the way in to " + xyz(to);
        return null;
    }

    /** A spot he knows or was told of (within 24) that this block stands in front of, on the straight way in from his eyes: digging toward it is fine. */
    static BlockPos towardKnown(ClaudeEntity g, ServerPlayer player, ServerLevel level, BlockPos p) {
        List<BlockPos> goals = new ArrayList<>(ClaudeLife.seenOre(g)); goals.addAll(g.spottedDiamonds);
        if (player != null) goals.addAll(told().getOrDefault(player.getUUID(), Set.of()));
        for (OwnEvents.Event e : OwnEvents.all(g)) if (e.open()) goals.add(e.at);
        Vec3 eye = g.getEyePosition(), c = Vec3.atCenterOf(p);
        for (BlockPos t : goals) {
            if (t.equals(p)) continue;
            Vec3 tc = Vec3.atCenterOf(t), d = tc.subtract(eye); double len = d.length();
            if (len > 24 || len < 1e-3) continue;
            double along = c.subtract(eye).dot(d) / len;
            if (along <= 0 || along >= len) continue; // (in front of it, from where he is)
            double off = c.subtract(eye.add(d.scale(along / len))).length();
            if (off <= 0.9) return t;
        }
        return null;
    }

    static BlockPos coords(Matcher m) { return new BlockPos(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))); }

    static String refuse(String method, String what, String why) {
        ClaudeMod.LOGGER.info("[Claude prim] {}: refused, {} (Primitives.{})", what, why, method);
        return "failed: " + what + ": " + why;
    }

    /** The rules a dig of this block meets at the block (WorkGoal.doBreak's guards), asked before it's queued: the reason it's refused, or null. */
    static String digGuard(ClaudeEntity g, ServerPlayer player, ServerLevel level, BlockPos p, BlockState s) {
        BlockPos feetW = g.blockPosition(); boolean waterGuard = false; for (Direction d0 : Direction.Plane.HORIZONTAL) { var wf0 = level.getFluidState(p.relative(d0)); if (wf0.is(net.minecraft.tags.FluidTags.WATER)) waterGuard = true; } if (waterGuard && p.equals(g.blockPosition().below())) waterGuard = false; // (the block under his feet: the hold asks Consent, live 15:59) (their word over the hold: Consent.theirWord below) // (any water source beside it at its level, as in WorkGoal.doBreak) // the user's way: standing in his own water beside it, the water runs into the hole and the lava behind turns to obsidian (refused at -34,-55,-220 in their lesson, 15:10)
        if (!waterGuard) for (Direction d : Direction.values()) if (level.getFluidState(p.relative(d)).is(net.minecraft.tags.FluidTags.LAVA) && Perception.knowsLava(g, p.relative(d))) {
            if (com.claudemod.entity.life.Consent.theirWord(g, p, "lava behind", (d == Direction.UP ? "lava over it at " : d == Direction.DOWN ? "lava under it at " : "lava behind it at ") + xyz(p.relative(d)), p.relative(d))) break; // (their word: the same dig the safe way, WorkGoal.doBreak)
            return "it holds back the lava I saw at " + xyz(p.relative(d)) + " (say 'yes, break it' to have me do it anyway, the safe way)";
        }
        if (ClaudeLife.bpMode(g) && g.blueprint != null && !g.blueprint.isEmpty() && g.blueprint.isShell(p) && !com.claudemod.entity.life.MineBlueprint.ore(s) && !g.landingCells.contains(p) && !g.notches.contains(p) && !putsBack(g, p) && !com.claudemod.entity.life.Consent.theirWord(g, p, "mine shell", "my mine's wall", null))
            return "it's my mine's " + g.blueprint.roleAt(p).name().toLowerCase() + " (the shell stays: only ore comes out of it, or a block the same plan puts straight back with place_at)";
        if (ClaudeLife.houseMode(g) && g.house != null && g.house.isStructure(p) && g.house.rightBlock(level, p, g.house.roleAt(p)) && !putsBack(g, p) && !com.claudemod.entity.life.Consent.theirWord(g, p, "house", "my house's " + g.house.roleAt(p), null))
            return "it's my house's " + g.house.roleAt(p).name().toLowerCase();
        if (s.is(net.minecraft.tags.BlockTags.BEDS) && !ClaudeLife.bedPickup(p) && !com.claudemod.entity.life.Consent.theirWord(g, p, "bed", "a bed", null)) return "it's a bed (the bed rules take a bed up, never a dig)";
        if ((s.hasBlockEntity() || s.is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE)) && !ClaudeLife.isTempStation(g, p) && !ClaudeLife.stationPickup(p) && !com.claudemod.entity.life.Consent.theirWord(g, p, "station", "a station or storage", null)) return "the " + name(s) + " is a station or storage (they stay put)";
        if (player != null && (ClaudeLife.protectedBlock(g, player, p) || PlayerProfile.placedBy(player).contains(p)) && !com.claudemod.entity.life.Consent.theirWord(g, p, "their build", "part of their build", null)) return "it's part of their build";
        if (g.farm != null && g.farm.touches(p) && !putsBack(g, p) && !com.claudemod.entity.life.Consent.theirWord(g, p, "farm", "my farm", null)) return "it's my farm";
        return null;
    }

    /** The plan puts this block straight back: a place_at at that cell still queued (a swap, like the patch in front of ore). */
    public static boolean putsBack(ClaudeEntity g, BlockPos p) {
        for (Skills.Step st : g.skillQueue) {
            if (!st.skill().equals("place_at")) continue;
            Matcher m = XYZ.matcher(st.target().toLowerCase()); if (m.find() && coords(m).equals(p)) return true;
        }
        return false;
    }

    /** dig_at x y z: that block broken (Task.Break), once he knows the spot and every rule says yes. */
    public static String digAt(ClaudeEntity g, ServerPlayer player, Skills.Step st) {
        ServerLevel level = (ServerLevel) g.level();
        Matcher m = XYZ.matcher(st.target().toLowerCase());
        if (!m.find()) return refuse("digAt", "dig_at " + st.target(), "no coordinates in it (dig_at takes x y z)");
        BlockPos p = coords(m); String what = "dig_at " + xyz(p);
        if (!level.isLoaded(p)) return refuse("digAt", what, "that spot isn't loaded (too far off to know it)");
        String how = knownHow(g, player, level, p);
        if (how == null) return refuse("digAt", what, "I can't know what's there: never seen it, not one of my builds, and nobody told me of it (a player can't dig at a spot he knows nothing about)");
        BlockState s = level.getBlockState(p);
        if (!s.getFluidState().isEmpty()) return refuse("digAt", what, "it's " + (s.getFluidState().is(net.minecraft.tags.FluidTags.LAVA) ? "lava" : "water") + ", not a block to dig");
        if (s.isAir() || s.canBeReplaced()) {
            ClaudeMod.LOGGER.info("[Claude prim] {}: nothing to dig, it's already open ({}; known: {}) (Primitives.digAt)", what, name(s), how);
            seq(g).dug.add(p.immutable());
            return "nothing to dig at " + xyz(p) + ": it's already open (on with the plan)";
        }
        if (s.getDestroySpeed(level, p) < 0) return refuse("digAt", what, "the " + name(s) + " can't be broken");
        String guard = digGuard(g, player, level, p, s);
        if (guard != null) return refuse("digAt", what, guard);
        if (s.requiresCorrectToolForDrops() && g.inventory.find(x -> x.isCorrectToolForDrops(s)).isEmpty()) return refuse("digAt", what, "no tool on me that takes " + name(s) + " (by hand it drops nothing)");
        g.tasks.add(new Task.Break(p.immutable()));
        Seq q = seq(g); q.pendingAt = p.immutable(); q.pendingItem = null; q.pendingSkill = "dig_at"; q.pendingLine = what + " (" + name(s) + ")";
        ClaudeMod.LOGGER.info("[Claude prim] {}: the {} (known: {}), the dig queued (Primitives.digAt)", what, name(s), how);
        return "digging out the " + name(s) + " at " + xyz(p);
    }

    static final java.util.Map<java.util.UUID, java.util.Set<BlockPos>> POURED = new java.util.HashMap<>();
    /** Water he poured because they asked (place_at ... water): there on purpose until they have him scoop it (scoop_at). */
    public static boolean pouredOnAsk(ClaudeEntity g, BlockPos p) { var pr = POURED.get(g.getUUID()); return pr != null && pr.contains(p); }
    /** A full bucket poured into p: in reach (else a walk there, the step again), aimed at a solid face under or beside p he can see, a right-click with it. */
    public static String pourAt(ClaudeEntity g, ServerPlayer player, Skills.Step st, BlockPos p, Item bucket) {
        ServerLevel level = (ServerLevel) g.level(); String what = "place_at " + xyz(p) + " " + (bucket == net.minecraft.world.item.Items.WATER_BUCKET ? "water" : "lava");
        if (g.inventory.count(x -> x.is(bucket)) == 0) return refuse("pourAt", what, "no " + (bucket == net.minecraft.world.item.Items.WATER_BUCKET ? "water" : "lava") + " bucket on me");
        if (!level.getBlockState(p).canBeReplaced() && level.getFluidState(p).isEmpty()) return refuse("pourAt", what, "the " + name(level.getBlockState(p)) + " is in that spot (dig_at it first)");
        net.minecraft.world.phys.Vec3 eye = g.getEyePosition(); net.minecraft.world.phys.Vec3 aim = null;
        for (Direction d : new Direction[] {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) { // the face he clicks: the floor under it first, as a player pours at his feet
            BlockPos n = p.relative(d); BlockState ns = level.getBlockState(n);
            if (!ns.isFaceSturdy(level, n, d.getOpposite())) continue;
            net.minecraft.world.phys.Vec3 f = net.minecraft.world.phys.Vec3.atCenterOf(n).add(d.getOpposite().getStepX() * 0.5, d.getOpposite().getStepY() * 0.5, d.getOpposite().getStepZ() * 0.5);
            if (f.distanceTo(eye) > 4.5) continue;
            var hit = level.clip(new net.minecraft.world.level.ClipContext(eye, f.add(d.getStepX() * 0.01, d.getStepY() * 0.01, d.getStepZ() * 0.01), net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.NONE, g));
            if (hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK && hit.getBlockPos().equals(n) && hit.getDirection() == d.getOpposite()) { aim = f; break; }
        }
        if (aim == null) {
            if (p.distSqr(g.blockPosition()) > 9 && st.target() != null && !st.target().contains("#walked")) { g.skillQueue.addFirst(new Skills.Step(st.skill(), st.target() + " #walked", st.count(), st.where())); g.tasks.add(new Task.GoTo(p.immutable(), 2)); ClaudeMod.LOGGER.info("[Claude prim] {}: not in reach: over to it first (Primitives.pourAt)", what); return "over to " + xyz(p) + " to pour it"; }
            return refuse("pourAt", what, "no solid face under or beside it I can see from here to pour against");
        }
        g.hold(x -> x.is(bucket)); g.getLookControl().faceNow(aim);
        boolean ok = g.useHeld();
        boolean in = level.getFluidState(p).is(bucket == net.minecraft.world.item.Items.WATER_BUCKET ? net.minecraft.tags.FluidTags.WATER : net.minecraft.tags.FluidTags.LAVA);
        ClaudeMod.LOGGER.info("[Claude prim] {}: poured ({}), the cell now {} (Primitives.pourAt)", what, ok, level.getFluidState(p).isEmpty() ? "dry" : "wet");
        if (!in) return refuse("pourAt", what, "the bucket didn't take there (aimed at the face by it, nothing poured)");
        POURED.computeIfAbsent(g.getUUID(), k -> new java.util.HashSet<>()).add(p.immutable()); com.claudemod.entity.life.LessonWords.notePoured(g, player, p.immutable()); // (in a lesson: its pocket) // (on their ask: there on purpose, his bucket reflex leaves it)
        return "poured the " + (bucket == net.minecraft.world.item.Items.WATER_BUCKET ? "water" : "lava") + " at " + xyz(p);
    }

    /** scoop_at x y z: the empty bucket on a source he can see in reach, a right-click: the fluid back in the bucket. */
    public static String scoopAt(ClaudeEntity g, ServerPlayer player, Skills.Step st) {
        ServerLevel level = (ServerLevel) g.level();
        Matcher m = XYZ.matcher(st.target().toLowerCase());
        if (!m.find()) return refuse("scoopAt", "scoop_at " + st.target(), "no coordinates in it (scoop_at takes x y z)");
        BlockPos p = coords(m); String what = "scoop_at " + xyz(p);
        var f = level.getFluidState(p);
        boolean full = g.inventory.count(x -> x.is(net.minecraft.world.item.Items.WATER_BUCKET) || x.is(net.minecraft.world.item.Items.LAVA_BUCKET)) > 0;
        if (f.isEmpty() || !f.isSource()) return refuse("scoopAt", what, "no water or lava source there to take up" + (full ? " (my bucket's full already: that water's in it)" : "")); // (15:31 "pick up the water" with it already in his bucket sent him off for iron)
        if (g.inventory.count(x -> x.is(net.minecraft.world.item.Items.BUCKET)) == 0) return refuse("scoopAt", what, full ? "my one bucket's full already: pour it out first (place_at x y z water) to take this source up, no second bucket needed" : "no bucket on me at all");
        net.minecraft.world.phys.Vec3 c = net.minecraft.world.phys.Vec3.atCenterOf(p); // (inside the fluid: the look ray meets its surface on the way)
        if (c.distanceTo(g.getEyePosition()) > 4.5) {
            if (!st.target().contains("#walked")) { g.skillQueue.addFirst(new Skills.Step(st.skill(), st.target() + " #walked", st.count(), st.where())); g.tasks.add(new Task.GoTo(p.immutable(), 2)); ClaudeMod.LOGGER.info("[Claude prim] {}: not in reach: over to it first (Primitives.scoopAt)", what); return "over to " + xyz(p) + " to take it up"; }
            return refuse("scoopAt", what, "out of reach");
        }
        var hit = level.clip(new net.minecraft.world.level.ClipContext(g.getEyePosition(), c, net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.SOURCE_ONLY, g));
        if (!(hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK && hit.getBlockPos().equals(p))) return refuse("scoopAt", what, "I can't see that source from here (something's in the way)");
        g.hold(x -> x.is(net.minecraft.world.item.Items.BUCKET)); g.getLookControl().faceNow(c);
        boolean ok = g.useHeld();
        ClaudeMod.LOGGER.info("[Claude prim] {}: scooped ({}), the cell now {} (Primitives.scoopAt)", what, ok, level.getFluidState(p).isEmpty() ? "dry" : "still wet");
        if (!level.getFluidState(p).isEmpty() && level.getFluidState(p).isSource()) return refuse("scoopAt", what, "the bucket didn't take it");
        var pr = POURED.get(g.getUUID()); if (pr != null) pr.remove(p);
        return "took the " + (f.is(net.minecraft.tags.FluidTags.LAVA) ? "lava" : "water") + " up from " + xyz(p);
    }

    static final java.util.Map<java.util.UUID, Object[]> HEADING = new java.util.HashMap<>(); // per bot: {Direction, tick} his last turn/face/step left him
    static final java.util.Set<java.util.UUID> CROUCH = new java.util.HashSet<>();
    /** They had him crouch ("crouch" till "stand"): the walker's stand-up leaves it. */
    public static boolean crouching(ClaudeEntity g) { return CROUCH.contains(g.getUUID()); }
    /** The way "forward" means for him: his last turn/face/step (2 min), else his body's facing. */
    public static Direction heading(ClaudeEntity g) { Object[] h = HEADING.get(g.getUUID()); return h != null && g.tickCount - (int) h[1] <= 2400 ? (Direction) h[0] : g.getDirection(); }
    static void turnTo(ClaudeEntity g, Direction d) {
        HEADING.put(g.getUUID(), new Object[] {d, g.tickCount});
        float yaw = d.toYRot(); g.setYRot(yaw); g.setYHeadRot(yaw); g.setYBodyRot(yaw);
        g.getLookControl().faceNow(g.getEyePosition().add(d.getStepX() * 4, 0, d.getStepZ() * 4));
    }

    /** move <what>: a step, turn, face, look or crouch the way a player does it when told (see the cases). */
    public static String move(ClaudeEntity g, ServerPlayer player, Skills.Step st) {
        ServerLevel level = (ServerLevel) g.level();
        String t = (st.target() == null ? "" : st.target()).toLowerCase().trim(); String what = "move " + t;
        if (t.matches(".*\\b(crouch|sneak|shift)\\b.*")) { CROUCH.add(g.getUUID()); g.setShiftKeyDown(true); ClaudeMod.LOGGER.info("[Claude prim] {}: crouching (held till they say stand) (Primitives.move)", what); return "crouching at " + xyz(g.blockPosition()); }
        if (t.matches(".*\\b(stand|uncrouch|unsneak)\\b.*")) { CROUCH.remove(g.getUUID()); g.setShiftKeyDown(false); ClaudeMod.LOGGER.info("[Claude prim] {}: standing up (Primitives.move)", what); return "standing up at " + xyz(g.blockPosition()); }
        if (t.startsWith("look")) {
            Matcher m = XYZ.matcher(t);
            if (m.find()) { BlockPos p = coords(m); g.getLookControl().faceNow(net.minecraft.world.phys.Vec3.atCenterOf(p)); ClaudeMod.LOGGER.info("[Claude prim] {}: looking at {} (Primitives.move)", what, xyz(p)); return "looking at " + xyz(p); }
            if (t.matches(".*\\b(me|you|them|player)\\b.*") && player != null && player.level() == level) { g.getLookControl().faceNow(player.getEyePosition()); ClaudeMod.LOGGER.info("[Claude prim] {}: looking at {} (Primitives.move)", what, player.getName().getString()); return "looking at you"; }
            return refuse("move", what, "look at what (look at me, or look at x y z)");
        }
        Direction cur = heading(g); Direction d = null;
        for (Direction c : Direction.Plane.HORIZONTAL) if (t.matches(".*\\b" + c.getName() + "\\b.*")) d = c;
        if (t.startsWith("turn")) {
            if (t.contains("around") || t.contains("back")) d = cur.getOpposite(); else if (t.contains("left")) d = cur.getCounterClockWise(); else if (t.contains("right")) d = cur.getClockWise();
            if (d == null) return refuse("move", what, "turn which way (left, right, around)");
            turnTo(g, d); ClaudeMod.LOGGER.info("[Claude prim] {}: {} -> facing {} (Primitives.move)", what, cur.getName(), d.getName()); return "turned, facing " + d.getName();
        }
        if (t.startsWith("face")) {
            if (d == null) return refuse("move", what, "face which way (north, south, east, west)");
            turnTo(g, d); ClaudeMod.LOGGER.info("[Claude prim] {}: facing {} (Primitives.move)", what, d.getName()); return "facing " + d.getName();
        }
        if (d == null) { if (t.matches(".*\\b(forward|ahead|forwards)\\b.*")) d = cur; else if (t.matches(".*\\b(back|backward|backwards)\\b.*")) d = cur.getOpposite(); else if (t.matches(".*\\bleft\\b.*")) d = cur.getCounterClockWise(); else if (t.matches(".*\\bright\\b.*")) d = cur.getClockWise(); }
        if (d == null) return refuse("move", what, "which way (forward, back, left, right, or north/south/east/west)");
        Matcher nm = java.util.regex.Pattern.compile("\\b(\\d{1,2})\\b").matcher(t); int n = nm.find() ? Math.max(1, Math.min(16, Integer.parseInt(nm.group(1)))) : 1;
        boolean drop = t.matches(".*\\b(drop|fall|jump down|down in)\\b.*");
        BlockPos at = g.blockPosition(); BlockPos end = at; int went = 0; String stop = null;
        for (int i = 1; i <= n; i++) {
            BlockPos c = end.relative(d);
            if (!open(level, c) && open(level, c.above()) && open(level, c.above(2)) && open(level, end.above(2))) c = c.above(); // a block up: a step and a jump
            if (!open(level, c) || !open(level, c.above())) { stop = "a block in the way at " + xyz(c); break; }
            if (lava(level, c) || lava(level, c.below())) { stop = "lava at " + xyz(lava(level, c) ? c : c.below()); break; }
            if (level.getBlockState(c.below()).getCollisionShape(level, c.below()).isEmpty() && level.getFluidState(c.below()).isEmpty()) {
                int k = 1; while (k <= 4 && level.getBlockState(c.below(k + 1)).getCollisionShape(level, c.below(k + 1)).isEmpty() && level.getFluidState(c.below(k + 1)).isEmpty()) k++;
                BlockPos land = c.below(k);
                if (!drop) { stop = "a ledge at " + xyz(c) + " (" + k + " down; say drop to go over it)"; break; }
                if (k > 3) { // 4+ down: worked out like any drop (Leaps: the HP, the margin, the landing seen), taken as one when it's survivable
                    var f = com.claudemod.entity.nav.Leaps.drop(g, level, end, c, 0);
                    if (!f.ok()) { stop = "the drop at " + xyz(c) + ": " + com.claudemod.entity.nav.Leaps.cost(f) + " (" + f.why() + ")"; break; }
                    if (CROUCH.remove(g.getUUID()) | g.isShiftKeyDown()) g.setShiftKeyDown(false);
                    if (!end.equals(at)) g.tasks.add(new Task.GoTo(end.immutable(), 0.3));
                    g.tasks.add(new Task.Leap(end.immutable(), f.land(), com.claudemod.entity.nav.Leaps.DROP, f.down(), f.hp()));
                    HEADING.put(g.getUUID(), new Object[] {d, g.tickCount});
                    ClaudeMod.LOGGER.info("[Claude prim] {}: over the ledge at {}: {} (Primitives.move)", what, xyz(c), com.claudemod.entity.nav.Leaps.sayLine(g, f));
                    return "dropping off at " + xyz(c) + ": " + com.claudemod.entity.nav.Leaps.sayLine(g, f);
                }
                if (lava(level, land) || lava(level, land.below())) { stop = "the drop at " + xyz(c) + " is onto lava"; break; }
                end = land; went = i; if (CROUCH.remove(g.getUUID()) | g.isShiftKeyDown()) { g.setShiftKeyDown(false); ClaudeMod.LOGGER.info("[Claude prim] {}: standing up to drop (a crouch won't go off an edge) (Primitives.move)", what); } break; // over the edge: the drop ends the move
            }
            end = c; went = i;
        }
        if (went == 0) return refuse("move", what, "can't step " + d.getName() + ": " + stop); // (his heading stays: he didn't go)
        HEADING.put(g.getUUID(), new Object[] {d, g.tickCount});
        g.tasks.add(new Task.GoTo(end.immutable(), 0.3));
        ClaudeMod.LOGGER.info("[Claude prim] {}: {} {} block(s) {} from {} to {}{} (Primitives.move)", what, went < n ? "only" : "", went, d.getName(), xyz(at), xyz(end), stop == null ? "" : ", stopped: " + stop);
        return "stepping " + went + " " + d.getName() + " to " + xyz(end) + (stop == null ? "" : " (stopped short of " + n + ": " + stop + ")");
    }
    static boolean open(ServerLevel level, BlockPos c) { return level.getBlockState(c).getCollisionShape(level, c).isEmpty(); }
    static boolean lava(ServerLevel level, BlockPos c) { return level.getFluidState(c).is(net.minecraft.tags.FluidTags.LAVA); }

    /** place_at x y z block: that block put there (Task.Place), against a face he can see, from where he stands or a spot he walks to. */
    public static String placeAt(ClaudeEntity g, ServerPlayer player, Skills.Step st) {
        ServerLevel level = (ServerLevel) g.level();
        Matcher m = XYZ.matcher(st.target().toLowerCase());
        if (!m.find()) { String cs = ClaudeLife.portalCornerAsk(g, player, st); if (cs != null) { ClaudeMod.LOGGER.info("[Claude prim] place_at {}: a portal's corners, no spot given: {} (Primitives.placeAt)", st.target(), cs); return cs; } return refuse("placeAt", "place_at " + st.target(), "no coordinates in it (place_at takes x y z and a block)"); } // (16:12 "place_at cobbled_deepslate x4" for "the corners")
        BlockPos p = coords(m); String what = "place_at " + xyz(p);
        if (m.group(4) == null) return refuse("placeAt", what, "no block named (place_at x y z cobblestone)");
        { String fl = m.group(4); if (fl.equals("water") || fl.equals("water_bucket") || fl.equals("lava") || fl.equals("lava_bucket")) return pourAt(g, player, st, p, fl.startsWith("water") ? net.minecraft.world.item.Items.WATER_BUCKET : net.minecraft.world.item.Items.LAVA_BUCKET); } // (a fluid: the bucket poured there)
        Item item = BuiltInRegistries.ITEM.getOptional(net.minecraft.resources.ResourceLocation.withDefaultNamespace(m.group(4))).orElse(null);
        if (!(item instanceof BlockItem bi)) return refuse("placeAt", what, "'" + m.group(4) + "' isn't a block I can put down");
        what += " " + m.group(4);
        if (!level.isLoaded(p)) return refuse("placeAt", what, "that spot isn't loaded (too far off to know it)");
        String how = knownHow(g, player, level, p);
        if (how == null) return refuse("placeAt", what, "I can't know what's there: never seen it, not one of my builds, and nobody told me of it");
        if (g.inventory.count(x -> x.is(item)) == 0) return refuse("placeAt", what, "no " + m.group(4).replace('_', ' ') + " on me");
        BlockState s = level.getBlockState(p);
        if (s.is(bi.getBlock())) { ClaudeMod.LOGGER.info("[Claude prim] {}: already there (Primitives.placeAt)", what); return "the " + m.group(4).replace('_', ' ') + " is already at " + xyz(p) + " (on with the plan)"; }
        if (!s.canBeReplaced()) return refuse("placeAt", what, "the " + name(s) + " is in that spot (dig_at it first)");
        Seq q0 = seqs().get(g.getUUID());
        boolean putBack = q0 != null && q0.dug.contains(p);
        if (!putBack && ownBuild(g, p) == null && !com.claudemod.entity.life.Consent.theirWord(g, p, "litter", "a block out in the world on my own guess", null)) return refuse("placeAt", what, "a block out in the world on my own guess is litter (only into my own builds, back where I took one out, or when they ask)");
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(p))) if (e != g && e.isAlive() && !e.isSpectator()) return refuse("placeAt", what, e.getName().getString() + " is standing in it"); // (himself: he steps out to a spot that sees a face, below)
        BlockPos stand = viewSpot(g, level, p);
        if (stand == null) return refuse("placeAt", what, "no face of it I can see to put it against, from here or anywhere I can walk to (it's sealed in the rock)");
        boolean walk = !stand.equals(g.blockPosition());
        if (walk) g.tasks.add(new Task.GoTo(stand, 0.5));
        g.tasks.add(new Task.Place(p.immutable(), item));
        Seq q = seq(g); q.pendingAt = p.immutable(); q.pendingItem = item; q.pendingSkill = "place_at"; q.pendingLine = what;
        ClaudeMod.LOGGER.info("[Claude prim] {}: known ({}), a face in sight from {}{} (Primitives.placeAt)", what, how, xyz(stand), walk ? ", walking there first" : " where he stands");
        return "putting " + m.group(4).replace('_', ' ') + " at " + xyz(p) + (walk ? " (from " + xyz(stand) + ", where I can see a face of it)" : "");
    }

    /** A face of a block beside the cell, seen from this eye within reach: the ray reaches into the cell without hitting anything. */
    static boolean faceInSight(ClaudeEntity g, ServerLevel level, Vec3 eye, BlockPos p) {
        Vec3 c = Vec3.atCenterOf(p);
        if (eye.distanceTo(c) > 4.25) return false; // (WorkGoal.inReach)
        for (Direction d : Direction.values()) {
            BlockPos n = p.relative(d); BlockState ns = level.getBlockState(n);
            if (ns.isAir() || !ns.getFluidState().isEmpty() || ns.canBeReplaced()) continue; // nothing there to click
            Vec3 aim = c.add(d.getStepX() * 0.45, d.getStepY() * 0.45, d.getStepZ() * 0.45);
            if (level.clip(new ClipContext(eye, aim, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, g)).getType() == HitResult.Type.MISS) return true;
        }
        return false;
    }

    /** Where he can stand to see a face to place against: here, else the nearest cell about it he can walk to. Null: none. */
    static BlockPos viewSpot(ClaudeEntity g, ServerLevel level, BlockPos p) {
        if (!g.getBoundingBox().intersects(new AABB(p)) && faceInSight(g, level, g.getEyePosition(), p)) return g.blockPosition(); // (standing in the cell: out of it first, like a player)
        List<BlockPos> cand = new ArrayList<>();
        for (BlockPos q0 : BlockPos.betweenClosed(p.offset(-4, -4, -4), p.offset(4, 2, 4))) {
            BlockPos q = q0.immutable();
            if (q.equals(p) || q.above().equals(p)) continue;
            if (level.getBlockState(q).is(net.minecraft.world.level.block.Blocks.NETHER_PORTAL) || level.getBlockState(q.above()).is(net.minecraft.world.level.block.Blocks.NETHER_PORTAL)) continue; // (a corner of a lit frame: never from inside the portal, it takes him to the nether)
            if (!level.getBlockState(q).getCollisionShape(level, q).isEmpty() || !level.getBlockState(q.above()).getCollisionShape(level, q.above()).isEmpty() || !level.getBlockState(q.below()).isFaceSturdy(level, q.below(), Direction.UP) || !level.getFluidState(q).isEmpty()) continue;
            if (faceInSight(g, level, Vec3.atBottomCenterOf(q).add(0, 1.62, 0), p)) cand.add(q);
        }
        cand.sort(java.util.Comparator.comparingDouble(q -> q.distSqr(g.blockPosition())));
        int tries = 0;
        for (BlockPos q : cand) {
            if (tries++ >= 12) break;
            var path = g.getNavigation().createPath(q, 0);
            if (path != null && path.canReach()) return q;
        }
        ClaudeMod.LOGGER.info("[Claude prim] no spot to see a face of {} from: {} cell(s) about it see one, none he can walk to (Primitives.viewSpot)", xyz(p), cand.size());
        return null;
    }

    /** The step ran (ClaudeLife.runWork): a sequence starts with a primitive; a failure or a step of another kind ends it. */
    public static void ran(ClaudeEntity g, Skills.Step step, String result) {
        Seq s = seqs().get(g.getUUID());
        boolean prim = step.skill().equals("dig_at") || step.skill().equals("place_at");
        if (s == null && !prim) return;
        if (s == null) s = seq(g);
        if (!SEQ.contains(step.skill())) { close(g, s, "ended: the plan went on to " + step.skill()); return; }
        String line = step.skill() + (step.target().isBlank() ? "" : " " + step.target());
        if (result.startsWith("failed")) { s.pendingAt = null; s.steps.add(line + " -> " + result); close(g, s, result); return; }
        if (s.pendingAt == null || !step.skill().equals(s.pendingSkill)) s.steps.add(line + " -> " + result); // (a dig or place waiting on the world goes in when it's checked)
    }

    /**
     * Between steps, his tasks done (ClaudeLife.runWork): the last dig_at / place_at checked against the world. A miss is that step's
     * failure, with the line of the rule that stopped it, fed back like any failed step (their order's results, his last step line,
     * the planner's notes; the rest of the plan dropped). The sequence ends when no step of it is next. True: it failed.
     */
    public static boolean settle(ClaudeEntity g, ServerPlayer player) {
        Seq s = seqs().get(g.getUUID());
        if (s == null) return false;
        if (s.pendingAt != null) {
            ServerLevel level = (ServerLevel) g.level(); BlockPos p = s.pendingAt; BlockState now = level.getBlockState(p);
            boolean took = s.pendingItem == null ? now.isAir() || now.canBeReplaced() || !now.getFluidState().isEmpty() || com.claudemod.entity.life.Consent.opened(g, p) : now.is(Block.byItem(s.pendingItem)); // (their word opened it: the water cast may have set it to stone since)
            String line = s.pendingLine; s.pendingAt = null;
            if (took) {
                if (s.pendingItem == null) s.dug.add(p);
                s.steps.add(line + " -> ok");
                ClaudeMod.LOGGER.info("[Claude prim] {}: done, checked in the world ({}) (Primitives.settle)", line, name(now));
            } else {
                String why = MindLog.lastAbout(p.toShortString());
                String result = "failed: " + line + ": " + (s.pendingItem == null ? "the " + name(now) + " is still there" : "it didn't go in (" + name(now) + " there)") + " (" + why + ")";
                String full = s.pendingSkill + " " + xyz(p) + " -> " + result;
                g.lastSkillLine = full; PlayerProfile.noteDoing(player, full);
                if (g.commandActive) g.commandResults.add(full);
                g.failCount.merge(s.pendingSkill + " " + xyz(p), 1, Integer::sum); g.failReason.put(s.pendingSkill, result);
                g.skillQueue.clear(); g.nextPlanTick = Math.max(g.tickCount, g.planStartTick + 100); // the plan no longer holds: think again
                ClaudeMod.LOGGER.info("[Claude prim] {} (Primitives.settle)", full);
                ClaudeLife.dropOrderPrims(g, line + " missed"); // (their order's rest of this sequence: moot now)
                s.steps.add(line + " -> " + result);
                close(g, s, result);
                return true;
            }
        }
        Skills.Step next = g.skillQueue.peek();
        if (next == null || !SEQ.contains(next.skill())) close(g, s, "done: " + s.steps.size() + " step(s), each dig and place checked in the world (" + (g.tickCount - s.started) / 20 + " s)");
        return false;
    }

    static void close(ClaudeEntity g, Seq s, String outcome) {
        seqs().remove(g.getUUID());
        ClaudeMod.LOGGER.info("[Claude prim] sequence for '{}': {} -> {}", s.ask, s.steps, outcome);
    }

    /** For the planner and the chat: the ore he saw near him with its exact spot, and whether one of his own blocks seals it in (the coordinates dig_at / place_at need). */
    public static String describe(ClaudeEntity g) {
        ServerLevel level = (ServerLevel) g.level();
        List<BlockPos> ore = new ArrayList<>();
        for (BlockPos p : ClaudeLife.seenOre(g)) if (level.isLoaded(p) && p.distSqr(g.blockPosition()) <= 32 * 32 && com.claudemod.entity.life.MineBlueprint.ore(level.getBlockState(p))) ore.add(p);
        if (ore.isEmpty()) return "";
        ore.sort(java.util.Comparator.comparingDouble(p -> p.distSqr(g.blockPosition())));
        StringBuilder sb = new StringBuilder("KNOWN SPOTS (exact coordinates, for dig_at / place_at / go_to): ");
        int n = 0;
        for (BlockPos p : ore) {
            if (n++ >= 8) break;
            sb.append(n > 1 ? "; " : "").append(name(level.getBlockState(p))).append(" you saw at ").append(xyz(p));
            BlockPos seal = null; boolean open = false;
            for (Direction d : Direction.values()) {
                BlockPos q = p.relative(d); BlockState qs = level.getBlockState(q);
                if (qs.getCollisionShape(level, q).isEmpty()) open = true;
                else if (seal == null && ownBuild(g, q) != null && (qs.is(net.minecraft.world.level.block.Blocks.COBBLESTONE) || qs.is(net.minecraft.world.level.block.Blocks.COBBLED_DEEPSLATE))) seal = q;
            }
            if (!open && seal != null) sb.append(" (sealed behind your own patch, ").append(name(level.getBlockState(seal))).append(" at ").append(xyz(seal)).append(" in ").append(ownBuild(g, seal)).append(")");
            else if (!open) sb.append(" (in the rock, no face open)");
        }
        return sb.append("\n").toString();
    }
}
