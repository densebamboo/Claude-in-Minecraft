package com.claudemod.entity.life;

import java.util.HashMap;
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
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Their word over his holds (2026-10-30 15:30-15:41, live: the user teaching him to mine obsidian the right way, "yes force it",
 * and nothing could lift his lava guards; "he literally has code in place preventing him from collecting obsidian the right
 * way"; then "if im instructing him ... he should ideally listen to what im telling him to do over his safety rules").
 * One decision every hold asks (theirWord): his hazard holds (lava behind, lava body, lava running in, a drop under his feet) and
 * the upkeep refusals that can block a step they named (his mine's shell, his house, a pit cap, a bed, a station, their build,
 * his farm, the litter rule). It yields to:
 * - instruction: a lesson of theirs open, or their direct step naming this cell (dig_at / place_at x y z) or this job (the
 *   step's target names the block; their goal names obsidian, or the block and lava). Any order on its own ("go strip mine")
 *   is not instruction for one risky block;
 * - for a hazard hold only, their go-ahead: he says the hold once (what, why, "say 'yes, break it' to go ahead"), and "yes break
 *   it" / "yes force it" / "do it anyway" / "go ahead" / "yes" within 60 s of it, or "break the block at x y z anyway", gives a
 *   one-shot for that cell, 30 s, used up by the break.
 * He then does the same step the safe way, as an expert would: the risk said once; a stand to the side or above (never under the
 * block, never on it, never beside lava; at its level or above when one is reachable); the water bucket ready; after the break,
 * lava by the hole gets water poured into the hole (a lava source beside water is obsidian, lava running down onto it stone), the
 * water scooped back once the lava by it is set; lava coming for him in the next seconds gets water poured on it. His own
 * reflexes (fleeing, eating, MLG water, out of lava) are untouched. Cells their word opened or had him put a block in stay as
 * they left them (his upkeep's patches and take-backs wait) while the lesson is open, else 10 minutes. Every branch is logged
 * "[Claude consent]".
 */
public final class Consent {
    private Consent() {}

    public static final int ASK_WINDOW = 20 * 60, GRANT_TICKS = 20 * 30, KEEP_TICKS = 20 * 600, WARD_TICKS = 20 * 8;
    static final Set<String> HAZARD = Set.of("lava behind", "lava body", "lava running in", "drop under his feet");
    public static boolean hazard(String kind) { return HAZARD.contains(kind); }

    /** A hold he made: the cell, its kind, why, the lava it's about (or null), when; said = he told them, when. */
    public static final class Hold {
        public final BlockPos cell, lava; public final String kind, why; public final int tick; public int saidAt = -1;
        Hold(BlockPos cell, String kind, String why, BlockPos lava, int tick) { this.cell = cell; this.kind = kind; this.why = why; this.lava = lava; this.tick = tick; }
        @Override public String toString() { return kind + " at " + xyz(cell) + " (" + why + ", tick " + tick + (saidAt >= 0 ? ", said" : "") + ")"; }
    }
    static final class Grant { BlockPos cell; int until; String words; }
    static final class Ward { BlockPos cell, lava; int until, pouredAt = -1; boolean scooped, footPoured; }
    static final class State {
        Hold last; Grant grant; Ward ward; int saidAt = -100000;
        final Map<String, Integer> logged = new HashMap<>(); final Map<BlockPos, Integer> riskSaid = new HashMap<>(), moved = new HashMap<>(), opened = new HashMap<>(), placed = new HashMap<>();
    }
    private static Map<UUID, State> states; // (static, made on first use)
    static State st(ClaudeEntity g) { if (states == null) states = new HashMap<>(); return states.computeIfAbsent(g.getUUID(), k -> new State()); }
    public static void reset(ClaudeEntity g) { if (states != null) states.remove(g.getUUID()); } // (tests)
    // (tests) what happened, counted
    public static int holds, lifts, risks, grants, used, pours, refused; public static String lastSaid = "", lastLift = ""; public static BlockPos brokeFrom;
    public static Hold last(ClaudeEntity g) { return st(g).last; }

    static String xyz(BlockPos p) { return OwnEvents.xyz(p); }
    static String block(BlockState s) { return s == null ? "" : BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath(); }
    static boolean once(State s, String key, int now, int every) { Integer t = s.logged.get(key); if (t != null && now - t < every) return false; if (s.logged.size() > 256) s.logged.clear(); s.logged.put(key, now); return true; }
    static ServerPlayer owner(ClaudeEntity g) { return g.getTarget() instanceof ServerPlayer sp && !(sp instanceof ClaudeEntity) ? sp : null; }
    static boolean water(ServerLevel level, BlockPos p) { return level.getFluidState(p).is(FluidTags.WATER); }
    static boolean lava(ServerLevel level, BlockPos p) { return level.getFluidState(p).is(FluidTags.LAVA); }
    static boolean bucket(ClaudeEntity g) { return g.inventory.count(x -> x.is(Items.WATER_BUCKET)) > 0; }

    /** A lesson of theirs is open. */
    public static boolean lesson(ClaudeEntity g) { return g.lessonOpen != null && !g.lessonOpen.isBlank(); }

    static final Pattern XYZ = Pattern.compile("(-?\\d+)[ ,_]+(-?\\d+)[ ,_]+(-?\\d+)");
    static boolean namesCell(String words, BlockPos p) {
        if (words == null) return false;
        Matcher m = XYZ.matcher(words);
        while (m.find()) try { if (new BlockPos(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))).equals(p)) return true; } catch (NumberFormatException e) { }
        return false;
    }
    static boolean namesBlock(String words, String block) {
        if (words == null || block.isEmpty() || block.equals("air")) return false;
        return Pattern.compile("\\b" + Pattern.quote(block.replace('_', ' ')) + "s?\\b").matcher(words.toLowerCase(Locale.ROOT).replace('_', ' ')).find();
    }

    /** Their direct step names this cell: their order's step (dig_at / place_at x y z) with it in, or the lesson's. Null: no. */
    public static String theirStepNames(ClaudeEntity g, BlockPos cell) {
        if (!(g.commandActive || lesson(g)) || g.lastStep == null) return null;
        return namesCell(g.lastStep.target(), cell) ? "their step " + g.lastStep.skill() + " " + g.lastStep.target() : null;
    }

    /** Their instruction covers this cell: a lesson open, their step naming the cell, or their job naming its block. Null: none (any order on its own is none). */
    public static String instructing(ClaudeEntity g, BlockPos cell, BlockState s) {
        if (lesson(g)) return "their lesson (\"" + (g.lessonOpen.length() > 60 ? g.lessonOpen.substring(0, 60) : g.lessonOpen) + "\")";
        String step = theirStepNames(g, cell); if (step != null) return step;
        if (!g.commandActive) return null;
        String b = block(s);
        if (g.lastStep != null && namesBlock(g.lastStep.target(), b)) return "their job (" + g.lastStep.skill() + " " + g.lastStep.target() + ")";
        String goal = g.playerGoal == null ? "" : g.playerGoal.toLowerCase(Locale.ROOT);
        if (namesBlock(goal, b) && (b.equals("obsidian") || goal.contains("lava"))) return "their job (\"" + (goal.length() > 60 ? goal.substring(0, 60) : goal) + "\")";
        return null;
    }

    /**
     * The one decision every hold asks: true when their word beats it here (logged "their word over the <kind> hold"; a hazard's
     * risk said once). False: the hold stands, logged and remembered; a hazard hold is said to them (rate-limited) with how to lift it.
     */
    public static boolean theirWord(ClaudeEntity g, BlockPos cell, String kind, String why, BlockPos lavaAt) {
        State s = st(g); int now = g.tickCount; ServerLevel level = (ServerLevel) g.level();
        String by = instructing(g, cell, level.getBlockState(cell));
        boolean granted = hazard(kind) && s.grant != null && s.grant.cell.equals(cell) && now <= s.grant.until;
        if (by == null && granted) by = "their go-ahead (\"" + s.grant.words + "\")";
        if (by != null && underFeetLava(g, cell, lavaAt) && !granted && theirStepNames(g, cell) == null) { // (live 15:59: "i told you not to mine the ones below you": the block under his feet with lava under or beside it, never on a lesson's word or a job's; a step naming it, or their go-ahead for it: off it first, from the side)
            refused++;
            if (once(s, "feet " + cell.asLong(), now, 20 * 30)) ClaudeMod.LOGGER.info("[Claude consent] {}: never the block under his feet with lava at {} ({}; {}): refused, his own safety (Consent.theirWord)", xyz(cell), xyz(lavaAt), kind, by);
            Hold h0 = new Hold(cell.immutable(), kind, why, lavaAt.immutable(), now); h0.saidAt = now; s.last = h0; // (their go-ahead for it, heard: off it first, from the side)
            sayOnce(g, s, cell, "not breaking the block under my feet at " + xyz(cell) + ": lava " + (lavaAt.getY() < cell.getY() ? "under" : "beside") + " it would take me down with it. say 'yes, break it' and i'll step off and take it from the side", "consent: under his feet");
            return false;
        }
        if (by != null) {
            if (once(s, "lift " + kind + " " + cell.asLong(), now, 20 * 30)) {
                lifts++; lastLift = kind + " at " + xyz(cell) + ": " + by;
                ClaudeMod.LOGGER.info("[Claude consent] {}: their word over the {} hold, {} ({}); he's at {} (Consent.theirWord)", xyz(cell), kind, by, why, xyz(g.blockPosition()));
            }
            if (hazard(kind)) sayRisk(g, s, cell, kind, why, by);
            return true;
        }
        if (!hazard(kind)) return false; // (an upkeep refusal: its own line says it; only instruction lifts it)
        hold(g, s, cell, kind, why, lavaAt);
        return false;
    }

    static void hold(ClaudeEntity g, State s, BlockPos cell, String kind, String why, BlockPos lavaAt) {
        int now = g.tickCount;
        Hold prev = s.last;
        Hold h = new Hold(cell.immutable(), kind, why, lavaAt == null ? null : lavaAt.immutable(), now);
        if (prev != null && prev.cell.equals(cell) && now - prev.saidAt <= ASK_WINDOW) h.saidAt = prev.saidAt; // (said already: the ask still stands)
        s.last = h;
        if (once(s, "hold " + kind + " " + cell.asLong(), now, 20 * 30)) { holds++; ClaudeMod.LOGGER.info("[Claude consent] holding at {}: {} ({}); he's at {}, last hold now {} (Consent.hold)", xyz(cell), kind, why, xyz(g.blockPosition()), h); }
        ServerPlayer o = owner(g);
        boolean near = o != null && o.level() == g.level() && o.distanceTo(g) <= 24;
        if (h.saidAt >= 0 || !(near || lesson(g)) || now - s.saidAt < 20 * 10) return;
        String line = "holding off on the " + block(((ServerLevel) g.level()).getBlockState(cell)).replace('_', ' ') + " at " + xyz(cell) + ": " + why + ". say 'yes, break it' to go ahead";
        h.saidAt = now; s.saidAt = now;
        say(g, line, "consent: hold " + kind);
    }

    /** The cell under his feet, lava under it or beside it. */
    public static boolean underFeetLava(ClaudeEntity g, BlockPos cell, BlockPos lavaAt) { return cell.equals(g.blockPosition().below()) && lavaAt != null && lavaAt.getY() <= cell.getY(); }
    public static final BlockPos REFUSE = new BlockPos(0, -30000, 0); // (safeStand: no way to do it safely, refused)

    static void sayOnce(ClaudeEntity g, State s, BlockPos cell, String line, String reason) {
        int now = g.tickCount; Integer at = s.riskSaid.get(cell.below(30000));
        if (at != null && now - at < 20 * 60) return;
        s.riskSaid.put(cell.below(30000).immutable(), now); say(g, line, reason);
    }

    static void sayRisk(ClaudeEntity g, State s, BlockPos cell, String kind, String why, String by) {
        int now = g.tickCount; Integer at = s.riskSaid.get(cell);
        if (at != null && now - at < 20 * 60) return;
        if (s.riskSaid.size() > 64) s.riskSaid.clear();
        s.riskSaid.put(cell.immutable(), now); risks++;
        ServerLevel level = (ServerLevel) g.level();
        boolean wet = false; for (Direction d : Direction.Plane.HORIZONTAL) if (water(level, cell.relative(d))) wet = true;
        String how = wet ? "the water runs in and sets it" : bucket(g) ? "water bucket ready" : "no water on me, so i'm standing clear";
        String line = "that's " + why + ", doing it as you said (" + how + ")";
        ClaudeMod.LOGGER.info("[Claude consent] {}: the risk said once ({}; {}): {} (Consent.sayRisk)", xyz(cell), kind, by, how);
        say(g, line, "consent: risk " + kind);
    }

    static void say(ClaudeEntity g, String line, String reason) {
        lastSaid = line;
        ServerPlayer o = owner(g);
        if (o == null) { ClaudeMod.LOGGER.info("[Claude consent] would say \"{}\": nobody of his to say it to (Consent.say)", line); return; }
        com.claudemod.director.ClaudeChat.say(o, line, reason);
    }

    // ---------------------------------------------------------------- their go-ahead, heard
    static final Pattern NO = Pattern.compile("\\b(don'?t|dont|do not|no|nope|never|stop|wait|hold on|not now|later|leave it)\\b");
    static final Pattern GO = Pattern.compile("\\b(break it|force it|mine it|dig it|do it|go ahead|go for it|send it)\\b|\\banyway\\b");
    static final Pattern YES = Pattern.compile("^\\s*(yes|yeah|yep|yup|ya|sure|ok|okay|go|do it)\\s*[.!]*\\s*$");
    static final Pattern CELL = Pattern.compile("\\b(?:break|mine|dig|force)\\b[^0-9-]*(-?\\d+)[ ,]+(-?\\d+)[ ,]+(-?\\d+).*\\banyway\\b");

    /** Their chat (ChatListener.respond, before the model): a go-ahead for his last hold, or for a cell named "anyway". True: handled. */
    public static boolean heard(ClaudeEntity g, ServerPlayer p, String raw) {
        if (raw == null || p == null || p instanceof ClaudeEntity) return false;
        String low = raw.toLowerCase(Locale.ROOT).trim();
        State s = st(g); int now = g.tickCount;
        BlockPos cell = null; String why = null;
        Matcher m = CELL.matcher(low);
        if (m.find()) { cell = new BlockPos(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))); why = "they named it"; }
        Hold h = s.last;
        boolean asked = h != null && h.saidAt >= 0 && now - h.saidAt <= ASK_WINDOW;
        if (cell == null && asked && (GO.matcher(low).find() || YES.matcher(low).matches())) { cell = h.cell; why = "their answer to his " + h.kind + " hold"; }
        if (cell == null) return false;
        if (NO.matcher(low).find()) { ClaudeMod.LOGGER.info("[Claude consent] \"{}\" about {}: a no in it, the hold stays (Consent.heard)", raw, xyz(cell)); return false; }
        Grant gr = new Grant(); gr.cell = cell.immutable(); gr.until = now + GRANT_TICKS; gr.words = raw.length() > 60 ? raw.substring(0, 60) : raw; s.grant = gr; grants++;
        g.attempts.forget(cell);
        boolean queued = new Task.Break(cell).equals(g.currentTask) || g.tasks.contains(new Task.Break(cell));
        ServerLevel level = (ServerLevel) g.level();
        if (!queued && !level.getBlockState(cell).isAir() && level.getFluidState(cell).isEmpty()) g.tasks.addFirst(new Task.Break(cell.immutable()));
        ClaudeMod.LOGGER.info("[Claude consent] their go-ahead for {} (\"{}\", {}): one break, {} s; the break {} (Consent.heard)", xyz(cell), raw, why, GRANT_TICKS / 20, queued ? "already queued" : "queued");
        say(g, "ok, breaking the " + block(level.getBlockState(cell)).replace('_', ' ') + " at " + xyz(cell) + (bucket(g) ? ", water bucket ready" : ""), "consent: go-ahead");
        return true;
    }

    // ---------------------------------------------------------------- the safe way to do the same step
    /**
     * Where he stands to break this cell with its hold lifted: null when where he is will do; else a stand to walk to first.
     * Never under it or on it, never with lava beside his feet or head or over him; at its level or above when one is reachable;
     * with lava over the block and a bucket, one orthogonally beside its column (water poured in from there).
     */
    public static BlockPos safeStand(ClaudeEntity g, ServerLevel level, BlockPos cell, BlockPos lavaAt) {
        State s = st(g); BlockPos me = g.blockPosition(); int now = g.tickCount;
        boolean feet = underFeetLava(g, cell, lavaAt);
        Integer mv = s.moved.get(cell);
        if (mv != null && now - mv < 20 * 20) { if (feet) return refuseFeet(g, s, cell, lavaAt, "the walk off it didn't get him off it"); return null; } // (sent to a stand a moment ago: from where he got to)
        boolean lavaOver = lavaAt != null && lavaAt.getY() > cell.getY();
        int mine = score(g, level, me, cell, lavaOver, me);
        Map<BlockPos, Integer> sc0 = new HashMap<>();
        for (BlockPos q0 : BlockPos.betweenClosed(cell.offset(-4, -4, -4), cell.offset(4, 3, 4))) { BlockPos q = q0.immutable(); if (q.equals(me)) continue; int sc = score(g, level, q, cell, lavaOver, me); if (sc >= 0) sc0.put(q, sc); }
        java.util.List<BlockPos> cand = new java.util.ArrayList<>(sc0.keySet());
        cand.sort(java.util.Comparator.comparingInt(sc0::get));
        BlockPos best = null; int bestScore = Integer.MAX_VALUE, tries = 0;
        for (BlockPos q : cand) {
            int sc = sc0.get(q);
            if (mine >= 0 && sc / 100 >= mine / 100) break; // (no better tier than where he is)
            if (tries++ >= 12) break;
            var path = g.getNavigation().createPath(q, 0);
            if (path != null && path.canReach()) { best = q; bestScore = sc; break; }
        }
        if (best == null) {
            if (feet) return refuseFeet(g, s, cell, lavaAt, "no stand beside it he can walk to (" + cand.size() + " looked at)");
            if (mine < 0) ClaudeMod.LOGGER.info("[Claude consent] {}: where he is ({}) isn't a safe stand for it and none he can walk to is ({} looked at): from here, the bucket ready ({}) (Consent.safeStand)", xyz(cell), xyz(me), cand.size(), bucket(g));
            else if (once(s, "stand " + cell.asLong(), now, 20 * 30)) ClaudeMod.LOGGER.info("[Claude consent] {}: from where he is ({}, {}) (Consent.safeStand)", xyz(cell), xyz(me), me.getY() >= cell.getY() ? "at its level or above" : "beside, below it");
            return null;
        }
        s.moved.put(cell.immutable(), now);
        ClaudeMod.LOGGER.info("[Claude consent] {}: the safe stand is {} (score {}; he's at {}: {}), walking there first (Consent.safeStand)", xyz(cell), xyz(best), bestScore, xyz(me), mine < 0 ? "not safe there" : "a better one");
        return best;
    }

    static BlockPos refuseFeet(ClaudeEntity g, State s, BlockPos cell, BlockPos lavaAt, String why) {
        refused++;
        ClaudeMod.LOGGER.info("[Claude consent] {}: the block under his feet with lava at {}: {}; refused, never dug from on top (Consent.safeStand)", xyz(cell), xyz(lavaAt), why);
        sayOnce(g, s, cell, "not breaking the block under my feet at " + xyz(cell) + ": lava " + (lavaAt.getY() < cell.getY() ? "under" : "beside") + " it, and " + why.replace("him", "me").replace("he can", "i can"), "consent: under his feet");
        return REFUSE;
    }

    /** A stand's score (lower is better: tier*100 + distance), -1: never there. */
    static int score(ClaudeEntity g, ServerLevel level, BlockPos q, BlockPos cell, boolean lavaOver, BlockPos me) {
        if (q.equals(cell) || q.above().equals(cell) || q.below().equals(cell)) return -1; // in it, or on it
        if (q.getX() == cell.getX() && q.getZ() == cell.getZ() && q.getY() < cell.getY()) return -1; // under it
        if (!level.getBlockState(q).getCollisionShape(level, q).isEmpty() || !level.getBlockState(q.above()).getCollisionShape(level, q.above()).isEmpty()) return -1;
        if (lava(level, q) || lava(level, q.above()) || lava(level, q.below())) return -1;
        if (!q.equals(me) && !level.getBlockState(q.below()).isFaceSturdy(level, q.below(), Direction.UP)) return -1;
        for (Direction d : Direction.Plane.HORIZONTAL) if (lava(level, q.relative(d)) || lava(level, q.above().relative(d))) return -1; // lava beside him
        for (int k = 2; k <= 4; k++) if (lava(level, q.above(k))) return -1; // lava over him
        Vec3 eye = Vec3.atBottomCenterOf(q).add(0, 1.62, 0);
        if (q.equals(me)) eye = g.getEyePosition();
        if (eye.distanceTo(Vec3.atCenterOf(cell)) > 4.25) return -1; // (WorkGoal.inReach)
        if (!sees(g, level, eye, cell)) return -1;
        int tier = q.getY() >= cell.getY() ? 0 : 1;
        int side = Math.abs(q.getX() - cell.getX()) + Math.abs(q.getZ() - cell.getZ());
        if (lavaOver && bucket(g) && side != 1) tier += 1; // (beside its column: the water goes in from there)
        return tier * 100 + Math.min(99, (int) Math.round(Math.sqrt(q.distSqr(me)) * 4));
    }

    static boolean sees(ClaudeEntity g, ServerLevel level, Vec3 eye, BlockPos cell) {
        Vec3 c = Vec3.atCenterOf(cell);
        Vec3[] aims = {c, c.add(0.45, 0, 0), c.add(-0.45, 0, 0), c.add(0, 0.45, 0), c.add(0, -0.45, 0), c.add(0, 0, 0.45), c.add(0, 0, -0.45)}; // (WorkGoal.blockInTheWay's faces)
        for (Vec3 aim : aims) { var hit = level.clip(new ClipContext(eye, aim, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, g)); if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(cell)) return true; }
        return false;
    }

    /**
     * The block is out (WorkGoal.doBreak, right after it broke): a go-ahead for it is used up; a cell their word opened is kept as
     * they left it; lava by the hole (no water running in) gets the bucket's water poured into the hole, and the ward is on for a few seconds.
     */
    public static void broke(ClaudeEntity g, BlockPos pos, BlockState was, BlockPos lv, boolean waterIn) {
        State s = st(g); int now = g.tickCount;
        boolean granted = s.grant != null && s.grant.cell.equals(pos);
        String by = granted ? "their go-ahead" : instructing(g, pos, was);
        if (granted) { s.grant = null; used++; ClaudeMod.LOGGER.info("[Claude consent] {}: broken on their go-ahead, from {}: used up (Consent.broke)", xyz(pos), xyz(g.blockPosition())); }
        if (by == null) return;
        if (s.opened.size() > 256) s.opened.clear();
        s.opened.put(pos.immutable(), now); s.placed.remove(pos);
        brokeFrom = g.blockPosition().immutable();
        if (lv == null) return;
        Ward w = new Ward(); w.cell = pos.immutable(); w.lava = lv.immutable(); w.until = now + WARD_TICKS; s.ward = w;
        if (waterIn) { ClaudeMod.LOGGER.info("[Claude consent] {}: lava at {} by the hole, water beside it runs in and sets it; the ward on for {} s (Consent.broke)", xyz(pos), xyz(lv), WARD_TICKS / 20); return; }
        if (!bucket(g)) { ClaudeMod.LOGGER.info("[Claude consent] {}: lava at {} by the hole and no water on me: standing clear, the ward watching (Consent.broke)", xyz(pos), xyz(lv)); return; }
        String r = com.claudemod.director.Primitives.pourAt(g, owner(g), new Skills.Step("place_at", xyz(pos) + " water #walked", 1, "near_you"), pos, Items.WATER_BUCKET);
        pours++; w.pouredAt = now;
        ClaudeMod.LOGGER.info("[Claude consent] {}: lava at {} by the hole: the bucket's water into it ({}); the ward on for {} s (Consent.broke)", xyz(pos), xyz(lv), r, WARD_TICKS / 20);
    }

    /** A cell their word opened (kept): counted as dug (Primitives.settle). */
    public static boolean opened(ClaudeEntity g, BlockPos p) { return keptNow(g, st(g).opened, p); }

    /** A block went in where their word had him put it (TempLedger.classify): his, theirs to keep, never taken back while it's kept. */
    public static void placedByWord(ClaudeEntity g, BlockPos p, String by) {
        State s = st(g); if (s.placed.size() > 256) s.placed.clear(); s.placed.put(p.immutable(), g.tickCount); s.opened.remove(p);
        ClaudeMod.LOGGER.info("[Claude consent] {}: put there on {}: theirs, it stays (no take-back, no tidy) (Consent.placedByWord)", xyz(p), by);
    }

    static boolean keptNow(ClaudeEntity g, Map<BlockPos, Integer> m, BlockPos p) {
        Integer at = m.get(p); if (at == null) return false;
        if (lesson(g) || g.tickCount - at <= KEEP_TICKS) return true;
        m.remove(p); return false;
    }
    /** A block of theirs (their word put it there) another job of his wants out: why it stays, or null. */
    public static String keptBlock(ClaudeEntity g, BlockPos p) {
        State s = st(g); if (!keptNow(g, s.placed, p) || theirStepNames(g, p) != null || s.grant != null && s.grant.cell.equals(p)) return null;
        return "their word put it there " + (g.tickCount - s.placed.get(p)) / 20 + " s ago" + (lesson(g) ? ", the lesson's open" : "");
    }
    /** A cell their word opened another job of his wants filled: why it stays open, or null. */
    public static String keptOpen(ClaudeEntity g, BlockPos p) {
        State s = st(g); if (!keptNow(g, s.opened, p) || theirStepNames(g, p) != null) return null;
        return "their word opened it " + (g.tickCount - s.opened.get(p)) / 20 + " s ago" + (lesson(g) ? ", the lesson's open" : "");
    }

    /** Every tick (ClaudeEntity.brainTick): the go-ahead runs out; the ward pours on lava coming for him and scoops its water back. */
    public static void tick(ClaudeEntity g) {
        if (states == null) return;
        State s = states.get(g.getUUID()); if (s == null) return;
        int now = g.tickCount;
        if (s.grant != null && now > s.grant.until) { ClaudeMod.LOGGER.info("[Claude consent] their go-ahead for {} ran out unused ({} s) (Consent.tick)", xyz(s.grant.cell), GRANT_TICKS / 20); s.grant = null; }
        Ward w = s.ward; if (w == null || !(g.level() instanceof ServerLevel level)) return;
        if (now > w.until) { s.ward = null; ClaudeMod.LOGGER.info("[Claude consent] {}: the ward's off ({}; he's at {}, health {}) (Consent.tick)", xyz(w.cell), lava(level, w.cell) ? "lava in the hole" : "the hole " + block(level.getBlockState(w.cell)), xyz(g.blockPosition()), (int) g.getHealth()); return; }
        BlockPos f = g.blockPosition(), come = null;
        for (BlockPos c : new BlockPos[] {f, f.above()}) { if (lava(level, c)) { come = c; break; } for (Direction d : Direction.Plane.HORIZONTAL) if (lava(level, c.relative(d))) { come = c.relative(d).immutable(); break; } if (come != null) break; }
        if (come != null && !w.footPoured && bucket(g)) {
            w.footPoured = true; pours++;
            String r = com.claudemod.director.Primitives.pourAt(g, owner(g), new Skills.Step("place_at", xyz(come) + " water #walked", 1, "near_you"), come, Items.WATER_BUCKET);
            ClaudeMod.LOGGER.info("[Claude consent] {}: lava at {} coming for him at {}: water on it ({}) (Consent.tick)", xyz(w.cell), xyz(come), xyz(f), r);
            return;
        }
        if (w.pouredAt >= 0 && !w.scooped && now - w.pouredAt >= 20 && level.getFluidState(w.cell).is(FluidTags.WATER) && level.getFluidState(w.cell).isSource()) {
            boolean set = true; for (Direction d : Direction.values()) if (lava(level, w.cell.relative(d))) set = false;
            if (set && g.inventory.count(x -> x.is(Items.BUCKET)) > 0) {
                w.scooped = true;
                String r = com.claudemod.director.Primitives.scoopAt(g, owner(g), new Skills.Step("scoop_at", xyz(w.cell) + " #walked", 1, "near_you"));
                ClaudeMod.LOGGER.info("[Claude consent] {}: the lava by it is set ({}): the water back in the bucket ({}) (Consent.tick)", xyz(w.cell), block(level.getBlockState(w.lava)), r);
            }
        }
    }
}
