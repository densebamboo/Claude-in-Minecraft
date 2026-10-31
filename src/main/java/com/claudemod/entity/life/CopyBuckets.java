package com.claudemod.entity.life;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.claudemod.ClaudeMod;
import com.claudemod.entity.ClaudeEntity;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Copy mode's buckets (2026-10-30: the live lesson is a bucket lesson, the obsidian cast). Learned the way he'd see it, while he
 * watches them (CopyMode.tick, them in sight):
 *  - a POUR: the bucket in their hand goes water_bucket -> bucket (or lava_bucket -> bucket) and a new source shows, in his sight,
 *    on or by their look ray: place_at <cell> water|lava, from their exact stand and facing (a go_to before it, as for a block);
 *  - a SCOOP: bucket -> water_bucket / lava_bucket and a source he saw on or by their look ray is gone: scoop_at <cell>;
 *  - OBSIDIAN forming where the water met lava, within 3 s of a pour, in his sight: a note on that pour (where, how long after),
 *    not a step. His go waits that long after his own pour, as they did, before the next step.
 * The lava they worked: when it's obsidian by his go, the same steps go to the nearest lava source he can see from where he is,
 * shifted as one (CopyMode.run). Every decision logs "[Claude copy] ... (CopyBuckets.method)".
 */
public final class CopyBuckets {
    private CopyBuckets() {}

    /** What he's seen of their bucket and the fluids round where they look, per demo. */
    static final class Seen {
        Item held; final Map<BlockPos, String> fluids = new HashMap<>(); // sources he's seen round their aim
        final List<BlockPos> lavaWatch = new ArrayList<>(); int watchUntil = -1, pourTick; CopyMode.Move pourMove;
    }
    private static final Map<CopyMode.Move, String> NOTES = new IdentityHashMap<>();
    private static final Map<CopyMode.Move, BlockPos> WORKS = new IdentityHashMap<>(); // the lava a pour turned to obsidian (relative to the anchor)
    private static final Map<CopyMode.Move, Integer> DELAY = new IdentityHashMap<>(); // ticks from the pour to the obsidian
    public static String note(CopyMode.Move m) { return NOTES.get(m); }
    public static BlockPos works(CopyMode.Move m) { return WORKS.get(m); }
    public static boolean fluid(String block) { return block != null && block.matches("water|lava|water_bucket|lava_bucket"); }
    static String xyz(BlockPos p) { return p.getX() + " " + p.getY() + " " + p.getZ(); }
    static String type(ServerLevel lv, BlockPos q) { var f = lv.getFluidState(q); return f.isEmpty() || !f.isSource() ? null : f.is(FluidTags.LAVA) ? "lava" : f.is(FluidTags.WATER) ? "water" : null; }

    /** Each tick he watches them: the bucket in their hand against last tick's; then the sources round their aim, as he sees them. */
    public static void watch(ClaudeEntity g, CopyMode.Session s, ServerPlayer p, ServerLevel lv) {
        Seen b = s.buckets;
        if (!Whereabouts.seesNow(g, p)) return; // (their hands and their look only while in sight)
        Item now = p.getMainHandItem().getItem();
        if (b.held != null && now != b.held) {
            if ((b.held == Items.WATER_BUCKET || b.held == Items.LAVA_BUCKET) && now == Items.BUCKET) pour(g, s, p, lv, b.held == Items.WATER_BUCKET ? "water" : "lava");
            else if (b.held == Items.BUCKET && (now == Items.WATER_BUCKET || now == Items.LAVA_BUCKET)) scoop(g, s, p, lv, now == Items.WATER_BUCKET ? "water" : "lava");
        }
        b.held = now;
        if (b.watchUntil >= g.tickCount) { // after a pour: the lava by it turning to obsidian, seen
            for (var it = b.lavaWatch.iterator(); it.hasNext(); ) {
                BlockPos q = it.next();
                if (!lv.getBlockState(q).is(Blocks.OBSIDIAN) || !Perception.seesBlock(g, lv, q) && !WatchBuild.openTo(lv, g.getEyePosition(), q)) continue;
                it.remove();
                if (b.pourMove != null && !WORKS.containsKey(b.pourMove)) {
                    int d = g.tickCount - b.pourTick; BlockPos rel = q.subtract(s.anchor);
                    WORKS.put(b.pourMove, rel); DELAY.put(b.pourMove, d);
                    NOTES.put(b.pourMove, "obsidian formed at " + xyz(rel) + " (from the anchor) " + d + " ticks after, where the water met the lava");
                    ClaudeMod.LOGGER.info("[Claude copy] saw obsidian form at {}, {} ticks after their pour: noted on that step, not a step (CopyBuckets.watch)", xyz(q), d);
                }
            }
        }
        Vec3 aim = WatchBuild.aimOf(p); BlockPos c = BlockPos.containing(aim);
        b.fluids.keySet().removeIf(q -> q.distSqr(c) > 8 * 8);
        for (BlockPos q : BlockPos.betweenClosed(c.offset(-3, -3, -3), c.offset(3, 3, 3))) {
            String t = type(lv, q);
            if (t == null) { b.fluids.remove(q); continue; }
            if (!b.fluids.containsKey(q) && (Perception.seesBlock(g, lv, q) || WatchBuild.openTo(lv, g.getEyePosition(), q))) b.fluids.put(q.immutable(), t);
        }
    }

    /** The cells along their look ray (reach 4.5) and each one's neighbours, nearest first. */
    static List<BlockPos> rayCells(ServerPlayer p) {
        List<BlockPos> out = new ArrayList<>(); Vec3 eye = p.getEyePosition(), v = p.getViewVector(1f);
        for (double d = 0.5; d <= 4.75; d += 0.25) { BlockPos q = BlockPos.containing(eye.add(v.scale(d))); if (!out.contains(q)) out.add(q.immutable()); }
        List<BlockPos> all = new ArrayList<>(out);
        for (BlockPos q : out) for (Direction dir : Direction.values()) { BlockPos n = q.relative(dir); if (!all.contains(n)) all.add(n.immutable()); }
        return all;
    }

    static void pour(ClaudeEntity g, CopyMode.Session s, ServerPlayer p, ServerLevel lv, String what) {
        BlockPos at = null;
        for (BlockPos q : rayCells(p)) if (what.equals(type(lv, q)) && !what.equals(s.buckets.fluids.get(q)) && (Perception.seesBlock(g, lv, q) || WatchBuild.openTo(lv, g.getEyePosition(), q))) { at = q; break; }
        if (at == null) { ClaudeMod.LOGGER.info("[Claude copy] their {} bucket emptied, but no new {} he can see by their look: not learned (CopyBuckets.pour)", what, what); return; }
        stand(g, s, p);
        CopyMode.Move m = new CopyMode.Move("place_at", at.subtract(s.anchor), what, p.getYRot(), p.getXRot());
        CopyMode.add(g, s, m, "saw them pour the " + what + " (their bucket emptied, a source at " + xyz(at) + ")");
        CopyMode.note(g, at);
        s.buckets.fluids.put(at, what);
        Seen b = s.buckets; b.lavaWatch.clear(); b.pourMove = m; b.pourTick = g.tickCount; b.watchUntil = g.tickCount + 60;
        if (what.equals("water")) for (var e : b.fluids.entrySet()) if (e.getValue().equals("lava") && e.getKey().distSqr(at) <= 3 * 3) b.lavaWatch.add(e.getKey());
        for (BlockPos q : BlockPos.betweenClosed(at.offset(-2, -2, -2), at.offset(2, 2, 2))) if (what.equals("water") && "lava".equals(type(lv, q)) && !b.lavaWatch.contains(q)) b.lavaWatch.add(q.immutable());
    }

    static void scoop(ClaudeEntity g, CopyMode.Session s, ServerPlayer p, ServerLevel lv, String what) {
        BlockPos at = null;
        for (BlockPos q : rayCells(p)) if (what.equals(s.buckets.fluids.get(q)) && !what.equals(type(lv, q))) { at = q; break; }
        if (at == null) { ClaudeMod.LOGGER.info("[Claude copy] their bucket filled with {}, but no {} source he'd seen by their look is gone: not learned (CopyBuckets.scoop)", what, what); return; }
        stand(g, s, p);
        CopyMode.add(g, s, new CopyMode.Move("scoop_at", at.subtract(s.anchor), null, p.getYRot(), p.getXRot()), "saw them scoop the " + what + " (their bucket filled, the source at " + xyz(at) + " gone)");
        CopyMode.note(g, at);
        s.buckets.fluids.remove(at);
    }

    /** Where they stood for it, as for a block: a go_to before the act when they've moved. */
    static void stand(ClaudeEntity g, CopyMode.Session s, ServerPlayer p) {
        BlockPos st = BlockPos.containing(p.getX(), p.getY() + 0.2, p.getZ());
        if (!st.equals(s.lastStand)) { CopyMode.add(g, s, new CopyMode.Move("go_to", st.subtract(s.anchor), null, p.getYRot(), p.getXRot()), "where they stood for it, seen"); s.lastStand = st; }
    }

    /**
     * His go: the lava their pour worked is obsidian now (or gone): the same steps on the nearest lava source he can see from
     * where he is, shifted as one. ZERO: as shown.
     */
    public static BlockPos shift(ClaudeEntity g, CopyMode.Session s, List<CopyMode.Move> todo) {
        ServerLevel lv = (ServerLevel) g.level();
        for (CopyMode.Move m : todo) {
            BlockPos w = WORKS.get(m); if (w == null) continue;
            BlockPos lava = s.anchor.offset(w);
            if ("lava".equals(type(lv, lava))) return BlockPos.ZERO;
            BlockPos best = null; BlockPos me = g.blockPosition();
            for (BlockPos q : BlockPos.betweenClosed(me.offset(-12, -4, -12), me.offset(12, 4, 12))) {
                if (!"lava".equals(type(lv, q)) || !(WatchBuild.openTo(lv, g.getEyePosition(), q) || Perception.knowsLava(g, q))) continue;
                if (best == null || q.distSqr(me) < best.distSqr(me)) best = q.immutable();
            }
            if (best == null) { ClaudeMod.LOGGER.info("[Claude copy] the lava they worked at {} is {} now and he sees no other lava about: as shown (CopyBuckets.shift)", xyz(lava), lv.getBlockState(lava).getBlock().getName().getString().toLowerCase()); return BlockPos.ZERO; }
            BlockPos sh = best.subtract(lava);
            ClaudeMod.LOGGER.info("[Claude copy] the lava they worked at {} is {} now: the same on the lava at {} (every step moved {}) (CopyBuckets.shift)", xyz(lava), lv.getBlockState(lava).getBlock().getName().getString().toLowerCase(), xyz(best), xyz(sh));
            return sh;
        }
        return BlockPos.ZERO;
    }

    static final Pattern XYZ = Pattern.compile("(-?\\d+)[ ,_]+(-?\\d+)[ ,_]+(-?\\d+)");
    /** A step of his go ran (ClaudeLife.runWork, beside Primitives.ran): after his pour, the wait they took for the obsidian. */
    public static void ran(ClaudeEntity g, Skills.Step step, String result) {
        if (!step.skill().equals("place_at") || result == null || !result.startsWith("poured")) return;
        CopyMode.Session s = CopyMode.sessions().get(g.getUUID()); if (s == null || s.phase == CopyMode.WATCH) return;
        Matcher m = XYZ.matcher(step.target()); if (!m.find()) return;
        BlockPos at = new BlockPos(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
        BlockPos base = s.anchor.offset(s.shift);
        for (int i = s.replayFrom; i < s.replayTo && i < s.moves.size(); i++) {
            CopyMode.Move mv = s.moves.get(i);
            if (!mv.skill().equals("place_at") || !fluid(mv.block()) || !base.offset(mv.at()).equals(at)) continue;
            Integer d = DELAY.get(mv); if (d == null) return;
            int w = Math.min(80, d + 5);
            g.tasks.add(new Task.Wait(w, false));
            ClaudeMod.LOGGER.info("[Claude copy] poured at {}: {} ticks for the obsidian, as they waited, before the next step (CopyBuckets.ran)", xyz(at), w);
            return;
        }
    }

    /** His go checked: a pour that the same go scoops back is checked by the scoop; a scoop: no source left there. Null: as it should be. */
    public static String check(CopyMode.Session s, int i, ServerLevel lv, BlockPos base) {
        CopyMode.Move m = s.moves.get(i); BlockPos at = base.offset(m.at());
        if (m.skill().equals("place_at") && fluid(m.block())) {
            for (int k = i + 1; k < s.replayTo && k < s.moves.size(); k++) if (s.moves.get(k).skill().equals("scoop_at") && s.moves.get(k).at().equals(m.at())) return null;
            String want = m.block().startsWith("water") ? "water" : "lava";
            return want.equals(type(lv, at)) ? null : want + " not at " + xyz(at);
        }
        if (m.skill().equals("scoop_at")) return type(lv, at) == null ? null : "the " + type(lv, at) + " still at " + xyz(at);
        return null;
    }

    /** A saved (or trace-made) step's notes back on its move. */
    public static CopyMode.Move loaded(JsonObject e, CopyMode.Move m) {
        if (e.has("note")) NOTES.put(m, e.get("note").getAsString());
        if (e.has("works")) { JsonArray w = e.getAsJsonArray("works"); WORKS.put(m, new BlockPos(w.get(0).getAsInt(), w.get(1).getAsInt(), w.get(2).getAsInt())); }
        if (e.has("delay")) DELAY.put(m, e.get("delay").getAsInt());
        return m;
    }
    /** A step's notes into its saved form. */
    public static void save(CopyMode.Move m, JsonObject e) {
        if (NOTES.containsKey(m)) e.addProperty("note", NOTES.get(m));
        if (WORKS.containsKey(m)) { BlockPos w = WORKS.get(m); JsonArray a = new JsonArray(); a.add(w.getX()); a.add(w.getY()); a.add(w.getZ()); e.add("works", a); }
        if (DELAY.containsKey(m)) e.addProperty("delay", DELAY.get(m));
    }
}
