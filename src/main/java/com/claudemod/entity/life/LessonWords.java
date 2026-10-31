package com.claudemod.entity.life;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * A lesson's words (the user live 2026-10-30 15:42-15:51, the obsidian lesson: he stands in his own water source in a pocket by a
 * lava pool and mines the obsidian the water makes). Three things a player keeps track of in a lesson, that ExactSpot's feet words
 * and the planner's counting got wrong:
 * <ul>
 * <li>Its count: "mine 10 of them" in a lesson is 10 dug by HIM now. His pack and his chests don't count toward it (15:44 obtain took
 *     the last one from his chest: "i didnt tell you to grab the last one from your chest. this is a lesson"), and nothing is handed
 *     over unless they ask (15:50 "ok continuing lesson" became make_obsidian 10 + give 10, and with 11 on him he just gave them 10).</li>
 * <li>Its places: the pocket, the spot, the water. Shown or named once, "the pocket", "back to the pocket", "your spot" mean that
 *     place, never their feet (15:45 "from the pocket." walked him onto their block). "this water" / "in the water" is a water cell by
 *     them he stands IN; "next to me" a free cell beside theirs; both: a water cell beside theirs (15:47 "right next to me, in the
 *     water", 15:49 "i want you to stand in this water" both took him to their block). Only their-feet words mean their feet.</li>
 * <li>Its pin: "stay in this pocket where the water is, and mine 10 obsidian" holds the job to that cell: he mines what he can reach
 *     from there, standing in its water (make_obsidian in place), and a step that would take him out of it is refused and asked
 *     about (15:46 make_obsidian walked him between two lava pools instead).</li>
 * </ul>
 * And "ok continuing lesson" resumes the lesson's last instruction as THEIR words gave it (kept with the lesson, saved), never a
 * goal the planner wrote. Every choice logs "[Claude lesson] ... (LessonWords.method)".
 */
public final class LessonWords {
    private LessonWords() {}

    /** A lesson place: its name ("pocket", "spot", "water"), its cell, how he knows it, whether it's water he stands in. */
    public record Place(String name, BlockPos cell, String how, boolean water) {}
    /** A place their latest line named, resolved: for that line's plan (a go_to of it lands here), with the words that matched. */
    public record Said(BlockPos cell, String name, String matched, String how, long time, boolean water) {}
    /** The job held to a cell ("stay in this pocket ... and mine"): their words, when. */
    public record Pin(BlockPos cell, String words, long time) {}
    /** Their count in a lesson: want of item dug by him since they said it. */
    public static final class Tally { Item item; final int want; int dug; final String words; final boolean fromWords; Skills.Step step; Tally(Item item, int want, String words, boolean fromWords) { this.item = item; this.want = want; this.words = words; this.fromWords = fromWords; } }

    private static Map<UUID, Said> said; private static Map<UUID, Pin> pins; private static Map<UUID, BlockPos> sent; private static Map<UUID, Tally> tallies; private static Map<UUID, Long> lineTime; private static Map<UUID, String> resumed; private static Map<UUID, BlockPos> commit; private static Map<UUID, Integer> enterTries; private static Map<UUID, String> logOnce; // (static, made on first use: fields added live start null)
    static Map<UUID, Said> said() { if (said == null) said = new HashMap<>(); return said; }
    static Map<UUID, Pin> pins() { if (pins == null) pins = new HashMap<>(); return pins; }
    static Map<UUID, BlockPos> sent() { if (sent == null) sent = new HashMap<>(); return sent; }
    static Map<UUID, Tally> tallies() { if (tallies == null) tallies = new HashMap<>(); return tallies; }
    static Map<UUID, Long> lineTime() { if (lineTime == null) lineTime = new HashMap<>(); return lineTime; }
    static Map<UUID, String> resumed() { if (resumed == null) resumed = new HashMap<>(); return resumed; }
    static Map<UUID, BlockPos> commit() { if (commit == null) commit = new HashMap<>(); return commit; }
    static Map<UUID, Integer> enterTries() { if (enterTries == null) enterTries = new HashMap<>(); return enterTries; }
    static Map<UUID, String> logOnce() { if (logOnce == null) logOnce = new HashMap<>(); return logOnce; }
    /** What he last said about it (tests). */
    public static String lastLine = "";

    public static void reset(ClaudeEntity g) { UUID u = g.getUUID(); said().remove(u); pins().remove(u); sent().remove(u); tallies().remove(u); lineTime().remove(u); resumed().remove(u); commit().remove(u); enterTries().remove(u); logOnce().remove(u); g.lessonPlaces = ""; g.lessonLast = ""; lastLine = ""; }
    public static Pin pin(ClaudeEntity g) { return pins().get(g.getUUID()); }
    public static Tally tally(ClaudeEntity g) { return tallies().get(g.getUUID()); }
    public static int dug(ClaudeEntity g) { Tally t = tally(g); return t == null ? -1 : t.dug; }
    public static Said saidNow(ClaudeEntity g) { return said().get(g.getUUID()); }

    // ================================================================ the words

    static final Pattern PLACE = Pattern.compile("\\b(this|that|the|your|ur|my|our)\\s+(?:little\\s+|water\\s+|own\\s+|old\\s+)?(pocket|pool|hole|nook|spot|place|cell)\\b");
    static final Pattern WATER = Pattern.compile("\\b(in|into|this|that|the)\\s+(?:the\\s+)?water\\b");
    static final Pattern BESIDE = Pattern.compile("\\b(?:right\\s+)?(next to|beside|by)\\s+me\\b");
    static final Pattern STAY = Pattern.compile("\\b(stay|remain|keep|wait|sit|stand)\\s+(?:right\\s+)?(?:in|at|inside|on|by)\\b|\\bfrom (?:the|this|that|your|ur|in the)\\b|\\bdon'?t (?:leave|move|get out)\\b");
    static final Pattern COUNT = Pattern.compile("\\b(mine|dig|collect|make|get|break|gather|grab|farm)\\s+(?:out\\s+|up\\s+|me\\s+|another\\s+)?(\\d+)\\b(?:\\s+more)?(?:\\s+(?:of\\s+(?:them|those|these|it)|([a-z_]+(?:\\s+[a-z_]+)?)))?");
    static final Pattern CONTINUE = Pattern.compile("\\b(continu(?:e|ing)|keep going|carry on|carrying on|resume|let'?s go on|back to the lesson|where we left off)\\b");
    static final Pattern MOVE_OFF = Pattern.compile("\\b(come (?:here|over|with me|back)|follow me|get out|leave (?:the|it|this|that)|lesson('?s| is)? (?:over|done)|you can go)\\b");
    static final Pattern GIVE = Pattern.compile("\\b(give|hand|bring|pass|toss|throw|send|deliver)\\b|\\b(for|to) me\\b|\\b(get|grab|fetch) me\\b|\\b(can|could|may) i (have|get)\\b|\\bi need(?! you\\b)\\b");
    static final Pattern GRAB = Pattern.compile("\\b(grab|get|take|fetch|pull|pick up)\\b");
    static final Pattern STACKS = Pattern.compile("\\b(a couple|couple|a few|few|a|one|two|three|four|five|\\d+)\\s+(?:of\\s+)?stacks?\\b|\\b(a couple|a few)\\b");
    /** "a couple (stacks)" = 2 stacks, "a few" = 3, "N stacks", "a stack" = 1: the count in items; 0: none said. */
    static int stacksIn(String words, Item it) {
        Matcher m = STACKS.matcher(words == null ? "" : words); if (!m.find()) return 0;
        String w = m.group(1) != null ? m.group(1) : m.group(2);
        int n = switch (w) { case "a couple", "couple", "two" -> 2; case "a few", "few", "three" -> 3; case "a", "one" -> 1; case "four" -> 4; case "five" -> 5; default -> { try { yield Integer.parseInt(w); } catch (NumberFormatException e) { yield 0; } } };
        return Math.max(0, n) * Math.max(1, it == null ? 64 : new net.minecraft.world.item.ItemStack(it).getMaxStackSize());
    }
    static final Pattern JOB = Pattern.compile("\\b(mine|dig|collect|make|get|break|gather|grab|place|put|pour|scoop|stay|stand|keep|build|craft|fill)\\b");
    static final Pattern THEIR_WORDS = Pattern.compile("\\(their words: \"(.*)\"\\)\\s*$", Pattern.DOTALL);
    static final Set<String> STOP = Set.of("air", "light", "chest", "barrier", "structure_void", "jigsaw", "them", "it");

    static String norm(String raw) { return raw == null ? "" : raw.toLowerCase(Locale.ROOT).replace('’', '\'').trim(); }
    static String xyz(BlockPos p) { return p.getX() + " " + p.getY() + " " + p.getZ(); }
    static boolean lessonNow(ClaudeEntity g, ServerPlayer p, String low) { return ExactSpot.lesson(g, p) || ExactSpot.LESSON.matcher(low).find(); }

    /** A block item their words name ("obsidian", "10 of them" no; "cobbled deepslate"), the longest first. */
    public static Item itemIn(String words) {
        if (words == null || words.isBlank()) return null;
        String[] w = norm(words).replaceAll("[^a-z_ ]", " ").trim().split("\\s+");
        for (int i = 0; i < w.length; i++) for (int n = 3; n >= 1; n--) {
            if (i + n > w.length) continue;
            String id = String.join("_", Arrays.copyOfRange(w, i, i + n));
            for (String c : new String[] {id, id.endsWith("s") ? id.substring(0, id.length() - 1) : id}) {
                if (c.isEmpty() || STOP.contains(c)) continue;
                ResourceLocation rl = ResourceLocation.tryParse("minecraft:" + c); if (rl == null) continue;
                var it = BuiltInRegistries.ITEM.getOptional(rl);
                if (it.isPresent() && it.get() != Items.AIR && it.get() instanceof BlockItem) return it.get();
            }
        }
        return null;
    }

    /**
     * Each chat line of theirs, before ExactSpot's feet words (ExactSpot.heard): a lesson's count, its last instruction (and
     * "continuing" resuming it), the place the line names and a pin to it. Null: nothing here, ExactSpot's feet words go on. True:
     * handled (a plain "get in the water": the walk queued, the line said). False: the chat plans the rest (its go_to lands on the
     * place, orderStep).
     */
    public static Boolean heardLine(ClaudeEntity g, ServerPlayer p, String raw) {
        String low = norm(raw); UUID u = g.getUUID();
        lineTime().put(u, g.level().getGameTime()); said().remove(u); resumed().remove(u);
        boolean feetWords = ExactSpot.exactWords(raw) && !PLACE.matcher(low).find() && !WATER.matcher(low).find() && !BESIDE.matcher(low).find();
        { ExactSpot.Ask old = ExactSpot.asks().get(u); if (old != null && !feetWords) { ExactSpot.asks().remove(u); ClaudeMod.LOGGER.info("[Claude lesson] \"{}\" names no block of theirs: the older ask ({} from \"{}\") is spent, not what a later go_to means (LessonWords.heardLine)", raw, xyz(old.cell()), old.words()); } }
        boolean lesson = lessonNow(g, p, low);
        if (MOVE_OFF.matcher(low).find() || feetWords) unpin(g, "their words \"" + raw + "\"");
        String words = low;
        if (lesson && CONTINUE.matcher(low).find() && !COUNT.matcher(low).find() && !JOB.matcher(low.replaceAll("\\b(keep going|carry on|carrying on)\\b", " ")).find()) {
            String last = g.lessonLast == null ? "" : g.lessonLast;
            if (!last.isBlank()) { words = norm(last); resumed().put(u, last); ClaudeMod.LOGGER.info("[Claude lesson] \"{}\": resumes their last lesson instruction, in their words: \"{}\" (not a goal the planner wrote) (LessonWords.heardLine)", raw, last); }
            else ClaudeMod.LOGGER.info("[Claude lesson] \"{}\": a resume, but no instruction of theirs kept from this lesson (LessonWords.heardLine)", raw);
        } else if (lesson && JOB.matcher(low).find()) { g.lessonLast = raw; ClaudeMod.LOGGER.info("[Claude lesson] their lesson instruction kept, in their words: \"{}\" (LessonWords.heardLine)", raw); }
        if (lesson) tallyWords(g, p, raw, words);
        Said s = resolvePlace(g, p, raw, words);
        if (s == null) { if (feetWords) sent().remove(u); return null; }
        said().put(u, s);
        boolean stay = STAY.matcher(words).find();
        if (stay && (lesson || ExactSpot.lesson(g, p) || g.commandActive || COUNT.matcher(words).find() || ExactSpot.OTHER_JOB.matcher(words).find())) {
            pins().put(u, new Pin(s.cell(), raw, s.time()));
            ClaudeMod.LOGGER.info("[Claude lesson] \"{}\": the job is held to {} ({}; \"{}\" matched): from there only, a step that takes him out of it is asked about (LessonWords.heardLine)", raw, xyz(s.cell()), s.name(), s.matched());
        }
        sent().put(u, s.cell());
        boolean job = ExactSpot.OTHER_JOB.matcher(words).find() || COUNT.matcher(words).find();
        if (job) { ClaudeMod.LOGGER.info("[Claude lesson] \"{}\": the place is {} ({}), more asked in it: the chat plans the rest, its walk lands there (LessonWords.heardLine)", raw, xyz(s.cell()), s.name()); return false; }
        if (ExactSpot.onCell(g, s.cell())) { lastLine = "i'm in it already (" + xyz(s.cell()) + ")"; com.claudemod.director.ClaudeChat.say(p, lastLine, "lesson: place"); ClaudeMod.LOGGER.info("[Claude lesson] \"{}\": already on {}: staying (LessonWords.heardLine)", raw, xyz(s.cell())); return true; }
        var steps = new com.google.gson.JsonArray(); var o = new com.google.gson.JsonObject();
        o.addProperty("skill", "go_to"); o.addProperty("target", xyz(s.cell())); o.addProperty("count", 1); o.addProperty("where", "exact"); steps.add(o);
        boolean running = g.commandActive && (!g.skillQueue.isEmpty() || !g.tasks.isEmpty() || g.currentTask != null);
        lastLine = "ok, " + (s.water() ? "into the water" : "onto it") + " at " + xyz(s.cell()) + (s.name().isEmpty() || s.name().equals("water") ? "" : " (the " + s.name() + ")");
        com.claudemod.director.ClaudeChat.say(p, lastLine, "lesson: place");
        com.claudemod.director.ClaudeLife.command(g, p, steps, (s.water() ? "stand in the water at " : "stand on ") + xyz(s.cell()) + (s.name().isEmpty() ? "" : ", the " + s.name()) + " (their words: \"" + raw + "\")", running ? "do_first" : "replace");
        ClaudeMod.LOGGER.info("[Claude lesson] \"{}\": onto {} ({}, \"{}\" matched; {}) (LessonWords.heardLine)", raw, xyz(s.cell()), s.how(), s.matched(), running ? "first, then on with the job" : "the order");
        return true;
    }

    static void tallyWords(ClaudeEntity g, ServerPlayer p, String raw, String words) {
        Matcher m = COUNT.matcher(words);
        if (!m.find()) return;
        int n; try { n = Integer.parseInt(m.group(2)); } catch (NumberFormatException e) { return; }
        if (n <= 0 || n > 640) return;
        Item it = itemIn(m.group(3));
        Tally old = tally(g);
        if (it == null) it = itemIn(g.lessonOpen);
        if (it == null) it = itemIn(g.playerGoal);
        if (it == null && old != null) it = old.item;
        if (old != null && old.want == n && (it == null || it == old.item) && resumed().containsKey(g.getUUID())) { ClaudeMod.LOGGER.info("[Claude lesson] \"{}\": the same count resumed, {} of {} {} dug so far (LessonWords.tallyWords)", raw, old.dug, old.want, old.item == null ? "blocks" : name(old.item)); return; }
        tallies().put(g.getUUID(), new Tally(it, n, raw, true));
        final Item it0 = it;
        ClaudeMod.LOGGER.info("[Claude lesson] \"{}\": {} {} dug by him now, from 0; his pack ({} on him) and his chests don't count toward it, and nothing's handed over unless they ask (LessonWords.tallyWords)", raw, n, it0 == null ? "(the item from the step)" : name(it0), it0 == null ? "-" : String.valueOf(g.inventory.count(x -> x.is(it0))));
    }
    static String name(Item it) { return BuiltInRegistries.ITEM.getKey(it).getPath().replace('_', ' '); }

    /** A block he broke (OwnEvents.onBreak): one toward their count when it's what they asked for. */
    public static void broke(ClaudeEntity g, BlockState s, BlockPos at) {
        Tally t = tally(g); if (t == null || t.item == null || s.getBlock().asItem() != t.item) return;
        t.dug++;
        ClaudeMod.LOGGER.info("[Claude lesson] dug the {} at {}: {} of {} dug by him in this lesson (LessonWords.broke)", name(t.item), xyz(at), t.dug, t.want);
    }

    static void unpin(ClaudeEntity g, String why) {
        UUID u = g.getUUID(); Pin was = pins().remove(u); BlockPos s0 = sent().remove(u);
        if (was != null || s0 != null) ClaudeMod.LOGGER.info("[Claude lesson] {} {} no longer held ({}) (LessonWords.unpin)", was != null ? "the job's place" : "the place they sent him to", xyz(was != null ? was.cell() : s0), why);
    }

    // ================================================================ the places

    /** The lesson's places, saved with him: "name|x|y|z|w|how;..." in g.lessonPlaces. */
    public static List<Place> places(ClaudeEntity g) {
        List<Place> out = new ArrayList<>();
        if (g.lessonPlaces == null || g.lessonPlaces.isBlank()) return out;
        for (String e : g.lessonPlaces.split(";")) { String[] f = e.split("\\|"); if (f.length < 6) continue; try { out.add(new Place(f[0], new BlockPos(Integer.parseInt(f[1]), Integer.parseInt(f[2]), Integer.parseInt(f[3])), f[5], f[4].equals("w"))); } catch (NumberFormatException ex) { } }
        return out;
    }
    static void savePlaces(ClaudeEntity g, List<Place> ps) {
        StringBuilder sb = new StringBuilder();
        for (Place q : ps) sb.append(sb.length() > 0 ? ";" : "").append(q.name()).append('|').append(q.cell().getX()).append('|').append(q.cell().getY()).append('|').append(q.cell().getZ()).append('|').append(q.water() ? "w" : "d").append('|').append(q.how().replace(";", ",").replace("|", "/"));
        g.lessonPlaces = sb.toString();
    }
    /** A place of the lesson: recorded under its name (the old one of that name replaced), the newest last. */
    public static void notePlace(ClaudeEntity g, String name, BlockPos cell, String how, boolean water) {
        List<Place> ps = places(g);
        Place same = null; for (Place q : ps) if (q.name().equals(name) && q.cell().equals(cell)) same = q;
        if (same != null) return;
        ps.removeIf(q -> q.name().equals(name)); ps.add(new Place(name, cell.immutable(), how, water));
        while (ps.size() > 8) ps.remove(0);
        savePlaces(g, ps);
        ClaudeMod.LOGGER.info("[Claude lesson] the lesson's {} is {} ({}) (LessonWords.notePlace)", name.isEmpty() ? "place" : name, xyz(cell), how);
    }
    /** Water he poured on their ask (Primitives.pourAt) in a lesson: the lesson's pocket unless they named another. */
    public static void notePoured(ClaudeEntity g, ServerPlayer p, BlockPos cell) {
        if (!ExactSpot.lesson(g, p)) return;
        boolean named = places(g).stream().anyMatch(q -> q.name().equals("pocket"));
        notePlace(g, named ? "water" : "pocket", cell, "the water he poured on their ask", true);
    }
    static Place named(ClaudeEntity g, String name) { Place hit = null; for (Place q : places(g)) if (q.name().equals(name)) hit = q; return hit; }
    static Place newest(ClaudeEntity g, boolean water) { Place hit = null; for (Place q : places(g)) if (!water || q.water()) hit = q; return hit; }

    /** Their feet cell as he knows it (seen now, or last seen); null: he doesn't know. */
    static BlockPos theirCell(ClaudeEntity g, ServerPlayer p) { return Whereabouts.seesNow(g, p) ? BlockPos.containing(p.getX(), p.getY() + 0.2, p.getZ()) : Whereabouts.spot(g, p); }

    static Said resolvePlace(ClaudeEntity g, ServerPlayer p, String raw, String low) {
        long now = g.level().getGameTime();
        Matcher pm = PLACE.matcher(low); boolean place = pm.find();
        Matcher wm = WATER.matcher(low); boolean water = wm.find();
        Matcher bm = BESIDE.matcher(low); boolean beside = bm.find();
        if (!place && !water && !beside) return null;
        String noun = place ? pm.group(2) : null, det = place ? pm.group(1) : null;
        boolean waterNoun = noun != null && Set.of("pocket", "pool", "hole", "nook").contains(noun);
        BlockPos them = theirCell(g, p);
        String matched = (place ? pm.group() : "") + (water ? (place ? " + " : "") + wm.group() : "") + (beside ? (place || water ? " + " : "") + bm.group() : "");
        if (place) {
            Place k = named(g, noun);
            if (k == null && (waterNoun || water)) k = newest(g, true);
            if (k == null && !waterNoun && !"this".equals(det) && !"that".equals(det)) k = newest(g, false);
            boolean shown = "this".equals(det) || "that".equals(det);
            if (k != null && !(shown && them != null && k.cell().distSqr(them) > 8 * 8)) {
                ClaudeMod.LOGGER.info("[Claude lesson] \"{}\": \"{}\" is the lesson's {} at {} ({}) (LessonWords.resolvePlace)", raw, matched, k.name(), xyz(k.cell()), k.how());
                return new Said(k.cell(), k.name(), matched, "the lesson's " + k.name() + " (" + k.how() + ")", now, k.water());
            }
            if (!waterNoun && !water) { ClaudeMod.LOGGER.info("[Claude lesson] \"{}\": \"{}\" names no place of the lesson he knows: left to the feet words / the chat (LessonWords.resolvePlace)", raw, matched); return null; }
        }
        if (them == null) { ClaudeMod.LOGGER.info("[Claude lesson] \"{}\": \"{}\" is by them, and he doesn't know where they are (LessonWords.resolvePlace)", raw, matched); return null; }
        ServerLevel lv = (ServerLevel) g.level();
        if (water || waterNoun) {
            BlockPos w = waterBy(g, lv, them, beside);
            if (w == null) { ClaudeMod.LOGGER.info("[Claude lesson] \"{}\": \"{}\": no water he can see {} {} (LessonWords.resolvePlace)", raw, matched, beside ? "beside their block" : "near them at", xyz(them)); return null; }
            String nm = waterNoun ? noun : "water";
            if (ExactSpot.lesson(g, p) || ExactSpot.LESSON.matcher(low).find()) notePlace(g, nm, w, "the water by them they named (\"" + matched + "\")", true);
            ClaudeMod.LOGGER.info("[Claude lesson] \"{}\": \"{}\" is the water at {} ({} their block {}) (LessonWords.resolvePlace)", raw, matched, xyz(w), beside ? "beside" : "nearest", xyz(them));
            return new Said(w, nm, matched, "the water " + (beside ? "beside them" : "by them"), now, true);
        }
        BlockPos b = besideThem(g, lv, them);
        if (b == null) { ClaudeMod.LOGGER.info("[Claude lesson] \"{}\": \"{}\": no free block to stand on beside theirs {} (LessonWords.resolvePlace)", raw, matched, xyz(them)); return null; }
        ClaudeMod.LOGGER.info("[Claude lesson] \"{}\": \"{}\" is {} beside their block {}, not their own (LessonWords.resolvePlace)", raw, matched, xyz(b), xyz(them));
        return new Said(b, "", matched, "beside them", now, false);
    }

    /** A water cell by their block he can stand in (its top open), not theirs: beside it when asked, else the nearest to them within 6; a source poured on their ask first, then any source, then a flow. */
    static BlockPos waterBy(ClaudeEntity g, ServerLevel lv, BlockPos them, boolean beside) {
        BlockPos best = null; double bs = Double.MAX_VALUE; int r = beside ? 1 : 6;
        for (BlockPos q : BlockPos.betweenClosed(them.offset(-r, -2, -r), them.offset(r, 1, r))) {
            if (q.equals(them) || !lv.getFluidState(q).is(FluidTags.WATER)) continue;
            if (beside && (Math.abs(q.getX() - them.getX()) + Math.abs(q.getZ() - them.getZ()) != 1 || Math.abs(q.getY() - them.getY()) > 1)) continue;
            if (!lv.getBlockState(q.above()).getCollisionShape(lv, q.above()).isEmpty()) continue; // (his head goes there)
            if (q.above().equals(them)) continue;
            boolean seen = Vec3.atCenterOf(q).distanceTo(g.getEyePosition()) <= 2.5 || com.claudemod.director.ClaudeLife.canSee(g, lv, q) || com.claudemod.director.Primitives.pouredOnAsk(g, q) || places(g).stream().anyMatch(x -> x.cell().equals(q));
            if (!seen) continue;
            boolean src = lv.getFluidState(q).isSource(), floor = !lv.getBlockState(q.below()).getCollisionShape(lv, q.below()).isEmpty();
            double sc = q.distSqr(them) + (com.claudemod.director.Primitives.pouredOnAsk(g, q) ? -50 : 0) + (src ? 0 : 20) + (floor ? 0 : 10);
            if (sc < bs) { bs = sc; best = q.immutable(); }
        }
        return best;
    }
    /** A free cell beside theirs he can stand on (dry), the nearest to him. */
    static BlockPos besideThem(ClaudeEntity g, ServerLevel lv, BlockPos them) {
        BlockPos best = null;
        for (Direction d : Direction.Plane.HORIZONTAL) for (int dy = -1; dy <= 1; dy++) {
            BlockPos q = them.relative(d).above(dy);
            if (!PlaceAim.standable(lv, q) || !lv.getFluidState(q).isEmpty()) continue;
            if (best == null || q.distSqr(g.blockPosition()) < best.distSqr(g.blockPosition())) best = q.immutable();
            break;
        }
        return best;
    }

    /** The place he holds in a lesson with no order running (ExactSpot.lessonHold): the job's pin, or the place they last sent him to; null: none. */
    public static BlockPos holdCell(ClaudeEntity g, ServerPlayer p) {
        if (!ExactSpot.lesson(g, p)) { if (pins().containsKey(g.getUUID()) || sent().containsKey(g.getUUID())) unpin(g, "the lesson's over"); return null; }
        Pin pn = pin(g); if (pn != null) return pn.cell();
        BlockPos s0 = sent().get(g.getUUID()); if (s0 != null) return s0;
        pn = pinWater(g, (ServerLevel) g.level(), p); // (in the lesson's water: he holds it, never walked out to them)
        return pn == null ? null : pn.cell();
    }
    private static Map<UUID, Integer> driftLogged;
    /**
     * Each tick (ExactSpot.tick): held to a water cell, the flow out of his source into the holes he opens pushes him (a source by a
     * lower flow has a current); like a player he leans back against it, a step to the cell's middle whenever he's 0.12 off, so he
     * never drifts out (15:51 "stay in the water", "stop drifting" x4). Not while a walk of his is under way.
     */
    public static void tickHold(ClaudeEntity g) {
        if (g.level().isClientSide) return;
        Pin pn = pins == null ? null : pins.get(g.getUUID());
        BlockPos c = pn != null ? pn.cell() : null;
        if (c == null && ownPockets != null) for (BlockPos q : ownPockets.getOrDefault(g.getUUID(), java.util.Set.of())) if (Math.abs(q.getX() + 0.5 - g.getX()) < 1.0 && Math.abs(q.getZ() + 0.5 - g.getZ()) < 1.0 && Math.abs(q.getY() - g.getY()) < 1.2) c = q; // (his own pocket, the user's way)
        if (c == null) return;
        if (g.currentTask instanceof Task.GoTo) return;
        if (!g.getNavigation().isDone()) { // (a break's approach from the pocket: every block he breaks there is in reach from the cell, no walk needed; the walk and the current took him into the hole he'd opened, stage 250)
            if (!(g.currentTask instanceof Task.Break)) return;
            g.getNavigation().stop();
            if (driftLogged == null) driftLogged = new HashMap<>();
            Integer l0 = driftLogged.get(g.getUUID()); if (l0 == null || g.tickCount - l0 > 100) { driftLogged.put(g.getUUID(), g.tickCount); ClaudeMod.LOGGER.info("[Claude lesson] breaking from {}: no walk for it (in reach from the cell), his navigation stopped (LessonWords.tickHold)", xyz(c)); }
        }
        double dx = c.getX() + 0.5 - g.getX(), dz = c.getZ() + 0.5 - g.getZ(), d2 = dx * dx + dz * dz;
        if (Math.abs(g.getY() - c.getY()) > 1.2 || d2 > 1.6 * 1.6 || d2 < 0.12 * 0.12) return;
        g.getMoveControl().setWantedPosition(c.getX() + 0.5, g.getY(), c.getZ() + 0.5, 1.0); // (full input: in water a half step's 0.01 a tick loses to the current's 0.014)
        if (driftLogged == null) driftLogged = new HashMap<>();
        Integer l = driftLogged.get(g.getUUID());
        if (l == null || g.tickCount - l > 100) { driftLogged.put(g.getUUID(), g.tickCount); ClaudeMod.LOGGER.info("[Claude lesson] the water pushing him {} off the middle of {}: a step back against it (LessonWords.tickHold)", String.format("%.2f", Math.sqrt(d2)), xyz(c)); }
    }
    private static Map<UUID, Integer> stepIns;
    /** A cell beside him (or his own, off its middle): a step to its middle with the move control, full input against the current, a few ticks (a walk to a cell beside him "arrives" at once and never gets there). False: further off (a walk), or 40 steps already for it. */
    static boolean stepIn(ClaudeEntity g, BlockPos c) {
        double dx = c.getX() + 0.5 - g.getX(), dz = c.getZ() + 0.5 - g.getZ();
        if (dx * dx + dz * dz > 1.7 * 1.7 || Math.abs(g.getY() - c.getY()) > 1.1) return false;
        if (stepIns == null) stepIns = new HashMap<>();
        int n = stepIns.merge(g.getUUID(), 1, Integer::sum);
        if (n > 40) { stepIns.remove(g.getUUID()); return false; }
        g.getNavigation().stop(); g.getMoveControl().setWantedPosition(c.getX() + 0.5, c.getY(), c.getZ() + 0.5, 1.0); g.tasks.add(new Task.Wait(10, false));
        if (n == 1 || n % 10 == 0) ClaudeMod.LOGGER.info("[Claude lesson] a step back into {} ({} off its middle, step {}), not a walk (LessonWords.stepIn)", xyz(c), String.format("%.2f", Math.sqrt(dx * dx + dz * dz)), n);
        return true;
    }
    /** Tests: the hold let go, as if they'd moved him on. */
    public static void clearHold(ClaudeEntity g) { unpin(g, "let go (test)"); }

    // ================================================================ the steps

    /**
     * Each step of a plan (top of ClaudeLife.runSkill): a walk of that line's plan lands on the place its words named; the pin holds
     * the job to its cell (a step that leaves it is refused and asked about); in a lesson, their count is what he digs now (obtain,
     * collect and make_obsidian of it work out what's left of it, never his chest or his pack). A line: handled (the step's result);
     * null: on with the step as it is.
     */
    public static String orderStep(ClaudeEntity g, ServerPlayer p, Skills.Step st) {
        UUID u = g.getUUID(); String sk = st.skill(); String tl = st.target() == null ? "" : st.target().toLowerCase(Locale.ROOT);
        boolean coords = ExactSpot.XYZ.matcher(tl).find();
        if (sk.equals("go_to") && !coords && !tl.matches("(?s).*\\b(home|house|farm|chest|storage|nook|stash|mine|base|bed)\\b.*")) {
            Said s = said().get(u); Long lt = lineTime().get(u);
            if (s != null && lt != null && s.time() == lt && g.level().getGameTime() - s.time() <= 20 * 60) {
                said().remove(u);
                ClaudeMod.LOGGER.info("[Claude lesson] go_to {} \"{}\": their line named {} (\"{}\"): onto {}, not {} (LessonWords.orderStep)", st.where(), st.target(), s.name().isEmpty() ? "a place" : "the " + s.name(), s.matched(), xyz(s.cell()), "exact_them".equals(st.where()) ? "their feet" : "near them");
                sent().put(u, s.cell());
                return ExactSpot.start(g, p, new Skills.Step("go_to", xyz(s.cell()), 1, "exact"));
            }
        }
        Pin pn = pin(g);
        if (pn != null && !ExactSpot.lesson(g, p) && !g.commandActive) { unpin(g, "no lesson and no order of theirs running"); pn = null; }
        if (pn == null && ExactSpot.lesson(g, p)) pn = pinWater(g, (ServerLevel) g.level(), p);
        boolean lesson = ExactSpot.lesson(g, p);
        Item si0 = itemIn(tl);
        if (sk.equals("make_obsidian") && si0 == null) si0 = Items.OBSIDIAN;
        final Item si = si0;
        boolean countSkill = lesson && Set.of("obtain", "collect", "make_obsidian", "mine").contains(sk) && !tl.startsWith("@") && si != null;
        if (countSkill) {
            Tally t = tally(g);
            if (t != null && t.step == st) { final Tally t1 = t; if (t1.want - t1.dug <= 0) return "done: dug " + t1.dug + " " + name(t1.item) + " myself since they asked, the " + t1.want + " they wanted (" + g.inventory.count(x -> x.is(t1.item)) + " on me)"; return null; } // (the step this count queued: on with it)
            if (t != null && t.fromWords && t.step == null && (t.item == null || t.item == si)) { // their words gave the count ("mine 10 of them"): this is its step
                if (t.item == null) t.item = si;
                ClaudeMod.LOGGER.info("[Claude lesson] {} {} x{}: the step for their count \"{}\" ({} {} dug by him, {} so far) (LessonWords.orderStep)", sk, tl, st.count(), t.words, t.want, name(t.item), t.dug);
            } else { // a new step of theirs or the planner's ("collect obsidian x10", "keep going"): N newly dug from now
                t = new Tally(si, Math.max(1, st.count()), sk + " " + tl + " x" + st.count(), false); tallies().put(u, t);
                ClaudeMod.LOGGER.info("[Claude lesson] {} {} x{} in their lesson: {} newly dug by him from now, never ended early by the {} on him or his chests (LessonWords.orderStep)", sk, tl, st.count(), t.want, g.inventory.count(x -> x.is(si)));
            }
            final Tally tt = t;
            int left = tt.want - tt.dug;
            if (left <= 0) return "done: dug " + tt.dug + " " + name(tt.item) + " myself since they asked, the " + tt.want + " they wanted (" + g.inventory.count(x -> x.is(tt.item)) + " on me)";
            boolean obs = tt.item == Items.OBSIDIAN;
            String to = obs ? "make_obsidian" : "collect";
            Skills.Step ns = new Skills.Step(to, BuiltInRegistries.ITEM.getKey(tt.item).getPath(), left, st.where());
            tt.step = ns; g.skillQueue.addFirst(ns);
            ClaudeMod.LOGGER.info("[Claude lesson] {} {} x{}: {} {} left of {} (\"{}\"; {} dug so far; not from his chest, not counting the {} on him){} (LessonWords.orderStep)", sk, tl, st.count(), to, left, tt.want, tt.words, tt.dug, g.inventory.count(x -> x.is(tt.item)), pn != null ? ", from " + xyz(pn.cell()) + " only" : "");
            return "in their lesson " + tt.want + " " + name(tt.item) + " means dug by me now (" + tt.dug + " so far): " + (obs ? "making" : "mining") + " the " + left + " left myself" + (pn != null ? " from " + xyz(pn.cell()) : "") + ", not from my chest or my pack";
        }
        if (pn != null) {
            BlockPos c = pn.cell();
            if (Set.of("dig_at", "place_at", "scoop_at", "pick_up", "make_obsidian", "wait", "eat", "ask", "move", "give", "copy_me", "watch_build").contains(sk)) return null;
            if (sk.equals("go_to") && coords) { Matcher m = ExactSpot.XYZ.matcher(tl); m.find(); BlockPos d = new BlockPos(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))); if (d.distManhattan(c) <= 1) return null; }
            if ((sk.equals("collect") || sk.equals("mine")) && si == Items.OBSIDIAN) { g.skillQueue.addFirst(new Skills.Step("make_obsidian", "obsidian", Math.max(1, st.count()), st.where())); ClaudeMod.LOGGER.info("[Claude lesson] {} obsidian while held to {}: make_obsidian from there instead (LessonWords.orderStep)", sk, xyz(c)); return "the obsidian from here (" + xyz(c) + "), as they said"; }
            String key = "pin:" + sk + ":" + tl;
            if (!key.equals(logOnce().get(u))) { logOnce().put(u, key); lastLine = "you told me to stay at " + xyz(c) + " (\"" + pn.words() + "\"): " + sk + (tl.isBlank() ? "" : " " + tl.replace('_', ' ')) + " would take me out of it. leave it?"; com.claudemod.director.ClaudeChat.say(p, lastLine, "lesson: pinned"); }
            ClaudeMod.LOGGER.info("[Claude lesson] {} {} refused: the job is held to {} by their words \"{}\", and this would take him out of it; asked them (LessonWords.orderStep)", sk, tl, xyz(c), pn.words());
            return "failed: they told me to stay at " + xyz(c) + " (\"" + pn.words() + "\"); " + sk + " " + tl + " would take me out of it: asked them first";
        }
        return null;
    }

    /** Their order's give steps (ClaudeLife.command, before Errand.take): in a lesson only when their words ask for a hand-over; the planner's own "and hand it to them" is dropped. */
    /**
     * Their order's give steps (ClaudeLife.command, before Errand.take): a give stays only when their words ask for a hand-over; the
     * chat model's own "and bring it to them" goes (17:13 "go grab your blocks" handed them the stack). With grab / get / take in
     * their words it's X into his own pack: take_from_chest (and the plan's obtain of it for the give goes too). No words of theirs
     * (his own plan): as it was, except in a lesson, where a give needs their words too.
     */
    public static List<Skills.Step> unaskedGive(ClaudeEntity g, ServerPlayer p, List<Skills.Step> queue, String goal) {
        if (queue.stream().noneMatch(s -> s.skill().equals("give"))) return queue;
        Matcher m = goal == null ? null : THEIR_WORDS.matcher(goal);
        String theirs = m != null && m.find() ? norm(m.group(1)) : "";
        String res = resumed().get(g.getUUID());
        String words = theirs + (res != null && CONTINUE.matcher(theirs).find() ? " " + norm(res) : "");
        boolean lesson = ExactSpot.lesson(g, p) || ExactSpot.LESSON.matcher(words).find();
        if (theirs.isEmpty() && !lesson) return queue; // (no words of theirs: his own plan, as it was)
        if (GIVE.matcher(words).find()) return queue;
        boolean grab = GRAB.matcher(words).find();
        List<Skills.Step> out = new ArrayList<>(); java.util.Set<String> took = new java.util.HashSet<>();
        for (Skills.Step s : queue) if (s.skill().equals("give") && grab && !s.target().isBlank()) took.add(s.target().toLowerCase(Locale.ROOT).replace(' ', '_'));
        for (Skills.Step s : queue) {
            String tg = s.target() == null ? "" : s.target().toLowerCase(Locale.ROOT).replace(' ', '_');
            if (s.skill().equals("give")) {
                if (grab && !tg.isBlank()) {
                    Item it = itemIn(tg); int n = stacksIn(words, it); if (n <= 0) n = Math.max(1, s.count());
                    out.add(new Skills.Step("take_from_chest", tg, n, "near_you"));
                    ClaudeMod.LOGGER.info("[Claude errand] give {} x{} read as take_from_chest {} x{}: their words (\"{}\") ask him to grab it, into his own pack, and for no hand-over; the goal's \"{}\" is the chat model's own (LessonWords.unaskedGive)", s.target(), s.count(), tg, n, words, goal);
                } else ClaudeMod.LOGGER.info("[Claude {}] give {} x{} dropped: their words (\"{}\") ask for no hand-over; the goal's \"{}\" is the chat model's own (LessonWords.unaskedGive)", lesson ? "lesson" : "errand", s.target(), s.count(), words, goal);
                continue;
            }
            if (took.contains(tg) && Set.of("obtain", "collect", "craft", "smelt").contains(s.skill())) { ClaudeMod.LOGGER.info("[Claude errand] {} {} x{} dropped: it was for the give, and his chest's take covers it (LessonWords.unaskedGive)", s.skill(), s.target(), s.count()); continue; }
            out.add(s);
        }
        return out;
    }

    // ================================================================ obsidian from the pocket

    static final Pattern WET_WORDS = Pattern.compile("\\b(pocket|water)\\b");
    /** A water cell of the lesson's: one he poured on their ask, a lesson place, or (their lesson about the pocket or the water) any source. */
    public static boolean lessonWater(ClaudeEntity g, ServerPlayer p, ServerLevel level, BlockPos c) {
        if (!level.getFluidState(c).is(FluidTags.WATER) || !level.getFluidState(c).isSource()) return false;
        if (com.claudemod.director.Primitives.pouredOnAsk(g, c)) return true;
        boolean lesson = p != null ? ExactSpot.lesson(g, p) : g.lessonOpen != null && !g.lessonOpen.isBlank();
        if (!lesson) return false;
        if (places(g).stream().anyMatch(q -> q.water() && q.cell().equals(c))) return true;
        String w = norm((g.lessonOpen == null ? "" : g.lessonOpen) + " " + (g.lessonLast == null ? "" : g.lessonLast) + " " + (g.playerGoal == null ? "" : g.playerGoal));
        return WET_WORDS.matcher(w).find();
    }
    /** In their lesson, standing in its water (the pocket): the job is held to that cell till they move him. Null: not in it. */
    static Pin pinWater(ClaudeEntity g, ServerLevel level, ServerPlayer p) {
        final BlockPos f = ExactSpot.feet(g).immutable();
        if (!lessonWater(g, p, level, f)) return null;
        Pin pn = new Pin(f, "standing in the lesson's water", g.level().getGameTime()); pins().put(g.getUUID(), pn);
        if (places(g).stream().noneMatch(q -> q.cell().equals(f))) notePlace(g, places(g).stream().anyMatch(q -> q.name().equals("pocket")) ? "water" : "pocket", f, "the lesson's water he stands in", true);
        ClaudeMod.LOGGER.info("[Claude lesson] he's in the lesson's water at {}: the job's held there (only what he can break from it counts), till they move him (LessonWords.pinWater)", xyz(f));
        return pn;
    }
    /** The cell a make_obsidian works from without moving: the job's pin, or the lesson's water he stands in. Null: the ordinary make_obsidian. */
    public static BlockPos jobCell(ClaudeEntity g, ServerLevel level, ServerPlayer p) {
        Pin pn = pin(g); if (pn != null) return pn.cell();
        BlockPos f = ExactSpot.feet(g);
        if (com.claudemod.director.Primitives.pouredOnAsk(g, f) && level.getFluidState(f).isSource()) return f;
        pn = p == null ? null : pinWater(g, level, p);
        return pn == null ? null : pn.cell();
    }
    /** The water would run into q once it's broken: water beside it (a source, or a flow with room to run on, over a floor) or over it. */
    static boolean flowsInto(ServerLevel level, BlockPos q) {
        if (level.getFluidState(q.above()).is(FluidTags.WATER)) return true;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos n = q.relative(d); var fs = level.getFluidState(n);
            if (!fs.is(FluidTags.WATER) || !(fs.isSource() || fs.getAmount() >= 2)) continue;
            BlockPos nb = n.below(); if (!level.getBlockState(nb).getCollisionShape(level, nb).isEmpty() || level.getFluidState(nb).is(FluidTags.WATER)) return true; // (water over open air runs down, not across)
        }
        return false;
    }
    /** Where his source would go so the water runs into q: an open cell beside q, a floor under it, room for his head, in reach of his eyes. Null: none. */
    static BlockPos sourceFor(ClaudeEntity g, ServerLevel level, BlockPos q, BlockPos cell) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos n = q.relative(d);
            if (n.equals(cell) || !level.getBlockState(n).getCollisionShape(level, n).isEmpty() || !(level.getFluidState(n).isEmpty() || level.getFluidState(n).is(FluidTags.WATER) && !level.getFluidState(n).isSource())) continue; // (open, or a hole his water runs in: "step into the new hole")
            if (n.getY() != cell.getY()) continue; // (the same level: the next one down is its own cycle)
            if (level.getBlockState(n.below()).getCollisionShape(level, n.below()).isEmpty() || !level.getBlockState(n.above()).getCollisionShape(level, n.above()).isEmpty()) continue;
            if (Vec3.atCenterOf(n).distanceTo(g.getEyePosition()) > 4.5) continue;
            return n.immutable();
        }
        return null;
    }

    /**
     * make_obsidian in place (the pocket technique): in on the cell, the obsidian in reach that he sees mined (one at a time, kept till
     * it's out), the water from his source runs into each hole so the lava behind turns too; the drops by him picked up. Never a walk
     * to lava. Done at their count (dug by him) or with nothing more in reach: said so, never a walk off.
     */
    public static String fromPlace(ClaudeEntity g, ServerLevel level, ServerPlayer p, int want, Runnable again, BlockPos cell) {
        UUID u = g.getUUID();
        Tally t = tally(g); boolean byTally = t != null && t.item == Items.OBSIDIAN;
        int have = g.inventory.count(x -> x.is(Items.OBSIDIAN));
        String count = byTally ? t.dug + " of " + t.want + " dug by me" : have + " of " + want + " on me";
        BlockPos f = ExactSpot.feet(g);
        if (!f.equals(cell) || Vec3.atBottomCenterOf(cell).distanceTo(new Vec3(g.getX(), cell.getY(), g.getZ())) > 0.6) {
            int n = enterTries().merge(u, 1, Integer::sum);
            if (n > 4) { enterTries().remove(u); ClaudeMod.LOGGER.info("[Claude lesson] can't get into {} (he's at {}, 4 tries): asked them (LessonWords.fromPlace)", xyz(cell), xyz(f)); return "failed: couldn't get into " + xyz(cell) + " to mine from it (I'm at " + xyz(f) + ")"; }
            if (!stepIn(g, cell)) g.tasks.add(new Task.GoTo(cell, 0.3)); again.run();
            ClaudeMod.LOGGER.info("[Claude lesson] make_obsidian from {}: in there first (he's at {}, try {}) (LessonWords.fromPlace)", xyz(cell), xyz(f), n);
            return "into " + xyz(cell) + " first, to mine from there (" + count + ")";
        }
        enterTries().remove(u); if (stepIns != null) stepIns.remove(u);
        // drops: the ones by him come to him on their own (a player's pickup reach); the rest wait, he never steps out for one
        var reachBox = g.getBoundingBox().inflate(1.0, 0.5, 1.0);
        var near = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, reachBox, e -> e.getItem().is(Items.OBSIDIAN));
        var far = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, g.getBoundingBox().inflate(6, 3, 6), e -> e.getItem().is(Items.OBSIDIAN) && !e.getBoundingBox().intersects(reachBox));
        if (!far.isEmpty()) { String k = "far:" + far.size(); if (!k.equals(logOnce().get(u))) { logOnce().put(u, k); ClaudeMod.LOGGER.info("[Claude lesson] {} obsidian drop(s) out of his reach from {} (nearest at {}): they wait, he doesn't step out of the water for them (LessonWords.fromPlace)", far.size(), xyz(cell), far.get(0).blockPosition().toShortString()); } }
        if (!near.isEmpty() && g.tickCount % 2 == 0) { g.tasks.add(new Task.Wait(4, false)); again.run(); return "the obsidian by me coming into my pack (" + count + ")"; }
        boolean done = byTally ? t.dug >= t.want : have >= want;
        if (done) { commit().remove(u); ClaudeMod.LOGGER.info("[Claude lesson] make_obsidian from {} done: {} (LessonWords.fromPlace)", xyz(cell), count); { String sw = sweepFirst(g, level, again, cell); if (sw != null) return sw; } String back = waterBack(g, cell); return "done: " + count + " from " + xyz(cell) + ", without leaving it" + back; }
        // the next block: obsidian in reach that his eyes see from the water cell, never the floor under him or anything lower (his water would run down into it: 15:59 "i told you not to mine the ones below you"); one the water runs into once it's out first (the user: "you want to make sure the water can flow into the block youre about to break too"), then the nearest; kept till it's out
        BlockPos o = commit().get(u);
        Vec3 eye = g.getEyePosition();
        if (o != null && !(level.getBlockState(o).is(Blocks.OBSIDIAN) && Vec3.atCenterOf(o).distanceTo(eye) <= 4.5)) { commit().remove(u); o = null; }
        boolean oFlows = o != null && flowsInto(level, o);
        if (o == null || !oFlows) {
            BlockPos best = null; double bs = Double.MAX_VALUE; boolean bestFlows = false;
            for (BlockPos q : BlockPos.betweenClosed(cell.offset(-5, 0, -5), cell.offset(5, 0, 5))) { // (his layer only: the whole of it first, the user's way)
                if (!level.getBlockState(q).is(Blocks.OBSIDIAN) || Vec3.atCenterOf(q).distanceTo(eye) > 4.5) continue;
                if (!com.claudemod.director.ClaudeLife.canSee(g, level, q)) continue;
                if (level.getFluidState(q.above()).is(FluidTags.LAVA)) { String k = "lavaover:" + q.toShortString(); if (!k.equals(logOnce().get(u))) { logOnce().put(u, k); ClaudeMod.LOGGER.info("[Claude lesson] not the obsidian at {}: lava over it would pour into the hole (LessonWords.fromPlace)", xyz(q)); } continue; }
                boolean fl = flowsInto(level, q);
                double sc = q.distSqr(cell) - (fl ? 1000 : 0);
                if (sc < bs) { bs = sc; best = q.immutable(); bestFlows = fl; }
            }
            if (best != null && (o == null || bestFlows)) { o = best; oFlows = bestFlows; commit().put(u, o); ClaudeMod.LOGGER.info("[Claude lesson] the obsidian at {} next, from {} (in reach, seen from the water; {}; kept till it's out) (LessonWords.fromPlace)", xyz(o), xyz(cell), oFlows ? "the water runs into it once it's out" : "no water would run into it yet"); }
        }
        if (o == null) { String dn = nextLayer(g, level, again, cell, count); if (dn != null) return dn; }
        if (o == null && ownPocket(g, cell) && pin(g) == null) { // his own pocket, nothing left at its level, no next one: the water back, more lava the old way
            { String sw = sweepFirst(g, level, again, cell); if (sw != null) return sw; }
            techs().remove(u); String back = waterBack(g, cell); again.run();
            ClaudeMod.LOGGER.info("[Claude obsidian] the pool's layers in reach of {} are done ({}){}: more lava next (LessonWords.fromPlace)", xyz(cell), count, back);
            return "this pool's worked out from " + xyz(cell) + " (" + count + ")" + back + ": more lava next";
        }
        if (o == null) {
            String k = "none:" + xyz(cell) + ":" + count; if (!k.equals(logOnce().get(u))) { logOnce().put(u, k); lastLine = "no more obsidian i can break from here (" + count + "). want me to move?"; if (p != null) com.claudemod.director.ClaudeChat.say(p, lastLine, "lesson: none in reach"); }
            ClaudeMod.LOGGER.info("[Claude lesson] make_obsidian from {}: no obsidian left in reach that he sees from the water ({}): staying put, asked them (LessonWords.fromPlace)", xyz(cell), count);
            return "done: no more obsidian in reach from " + xyz(cell) + " (" + count + "): staying here, asked them whether to move";
        }
        if (!oFlows) { // his source moved so the water runs into it (the user: "if you need to readjust the source block so it can still flow into"): scooped, poured beside it, in there, then the break
            BlockPos n = sourceFor(g, level, o, cell);
            if (n != null && g.inventory.count(x -> x.is(Items.BUCKET) || x.is(Items.WATER_BUCKET)) > 0 && level.getFluidState(cell).isSource()) {
                again.run();
                g.skillQueue.addFirst(new Skills.Step("go_to", xyz(n), 1, "exact"));
                g.skillQueue.addFirst(new Skills.Step("place_at", xyz(n) + " water", 1, "near_you"));
                if (g.inventory.count(x -> x.is(Items.WATER_BUCKET)) == 0) g.skillQueue.addFirst(new Skills.Step("scoop_at", xyz(cell), 1, "near_you"));
                Pin was = pin(g); if (was != null) pins().put(u, new Pin(n, was.words(), g.level().getGameTime())); // (a pin moves with the source; his own pocket has none)
                if (p != null && ExactSpot.lesson(g, p)) notePlace(g, "pocket", n, "his source, moved beside " + xyz(o) + " so the water runs into it", true);
                if (ownPocket(g, cell)) { ownPockets.get(u).remove(cell); markOwn(g, n); } // (his own pocket moved: still his, scooped back at the end)
                ClaudeMod.LOGGER.info("[Claude lesson] the water wouldn't run into {} from {}: his source re-placed at {} first (scoop_at, place_at water, in there), the job held there now (LessonWords.fromPlace)", xyz(o), xyz(cell), xyz(n));
                return "moving my source to " + xyz(n) + " so the water runs into " + xyz(o) + " (" + count + ")";
            }
            ClaudeMod.LOGGER.info("[Claude lesson] the water won't run into {} and there's no spot for his source beside it: mined anyway from {} (LessonWords.fromPlace)", xyz(o), xyz(cell));
        }
        g.tasks.add(new Task.Break(o)); again.run();
        return "mining the obsidian at " + xyz(o) + " from " + xyz(cell) + " (" + count + ")";
    }

    // ================================================================ the technique: make_obsidian's own pocket (the user's way, 16:06)

    /** A pocket of his: the cell at the lava's level, the dry spot it's worked from, the layer. */
    record Tech(BlockPos pocket, BlockPos from, int layer, int started) {}
    private static Map<UUID, Tech> techs; static Map<UUID, Tech> techs() { if (techs == null) techs = new HashMap<>(); return techs; }
    private static Map<UUID, java.util.Set<BlockPos>> ownPockets; // the water cells he poured for his own technique (scooped back after; a lesson's stay)
    static boolean ownPocket(ClaudeEntity g, BlockPos c) { return ownPockets != null && ownPockets.getOrDefault(g.getUUID(), java.util.Set.of()).contains(c); }
    static void markOwn(ClaudeEntity g, BlockPos c) { if (ownPockets == null) ownPockets = new HashMap<>(); ownPockets.computeIfAbsent(g.getUUID(), k -> new java.util.HashSet<>()).add(c.immutable()); }
    /** Done with his own pocket: its water back in his bucket (no litter); a lesson's pocket stays. */
    static String waterBack(ClaudeEntity g, BlockPos cell) {
        if (!ownPocket(g, cell) || !g.level().getFluidState(cell).isSource()) return "";
        ownPockets.get(g.getUUID()).remove(cell); techs().remove(g.getUUID());
        if (g.inventory.count(x -> x.is(Items.BUCKET)) == 0 || Vec3.atCenterOf(cell).distanceTo(g.getEyePosition()) > 4.5) { ClaudeMod.LOGGER.info("[Claude obsidian] his pocket's water at {} left: no empty bucket on him, or out of reach (LessonWords.waterBack)", xyz(cell)); return ""; }
        g.hold(x -> x.is(Items.BUCKET)); g.getLookControl().faceNow(Vec3.atCenterOf(cell).add(0, -0.3, 0)); g.useHeld(); // (a scoop where he stands, now: a "done" ends the plan's queue)
        boolean back = !g.level().getFluidState(cell).isSource();
        ClaudeMod.LOGGER.info("[Claude obsidian] his pocket's water at {} {} (LessonWords.waterBack)", xyz(cell), back ? "back in the bucket" : "not scooped (the bucket missed it)");
        return back ? "; my water back in the bucket" : "";
    }

    private static Map<UUID, BlockPos> sweptAt;
    /** His own pocket done with: the obsidian lying in his water picked up once (a walk through his own water, the work over), then the water back. Null: none, or swept already. */
    static String sweepFirst(ClaudeEntity g, ServerLevel level, Runnable again, BlockPos cell) {
        if (!ownPocket(g, cell)) return null;
        if (sweptAt == null) sweptAt = new HashMap<>();
        if (cell.equals(sweptAt.get(g.getUUID()))) return null;
        var drops = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(cell).inflate(6, 2, 6), e -> e.getItem().is(Items.OBSIDIAN));
        if (drops.isEmpty()) return null;
        sweptAt.put(g.getUUID(), cell.immutable());
        int n = drops.stream().mapToInt(e -> e.getItem().getCount()).sum();
        g.tasks.add(new Task.Pickup(120)); g.tasks.add(new Task.GoTo(cell, 0.3)); again.run();
        ClaudeMod.LOGGER.info("[Claude obsidian] {} obsidian lying in my water round {}: picked up now the work's done, then the water back (LessonWords.sweepFirst)", n, xyz(cell));
        return "picking up the " + n + " obsidian in my water before it goes back";
    }

    static boolean lavaSrc(ServerLevel level, BlockPos p) { var f = level.getFluidState(p); return f.is(FluidTags.LAVA) && f.isSource(); }
    static boolean dryStand(ServerLevel level, BlockPos c) {
        if (!level.getBlockState(c).getCollisionShape(level, c).isEmpty() || !level.getBlockState(c.above()).getCollisionShape(level, c.above()).isEmpty() || !level.getFluidState(c).isEmpty() || !level.getFluidState(c.above()).isEmpty()) return false;
        if (level.getBlockState(c.below()).getCollisionShape(level, c.below()).isEmpty() || level.getFluidState(c.below()).is(FluidTags.LAVA)) return false;
        for (BlockPos f : new BlockPos[] {c, c.above()}) for (Direction d : Direction.values()) if (level.getFluidState(f.relative(d)).is(FluidTags.LAVA)) return false;
        return true;
    }

    /**
     * Top of makeObsidian (after the pickups): his pocket at a pool he knows, the user's way. Null: none to make (no pool he knows that
     * a pocket fits, or he's in his water already and fromPlace works it), the old make_obsidian goes on.
     */
    public static String technique(ClaudeEntity g, ServerLevel level, int want, Runnable again) {
        UUID u = g.getUUID();
        if (g.inventory.count(x -> x.is(Items.WATER_BUCKET) || x.is(Items.BUCKET)) == 0) return null;
        Tech k = techs().get(u);
        if (k != null && g.tickCount - k.started() > 20 * 120 && !ownPocket(g, k.pocket())) { ClaudeMod.LOGGER.info("[Claude obsidian] the pocket at {} not made in 2 minutes: left, the old way (LessonWords.technique)", xyz(k.pocket())); g.attempts.fail(Attempts.Kind.REACH, k.pocket(), g.tickCount); techs().remove(u); return null; }
        if (k == null) {
            BlockPos lava = null; double ld = Double.MAX_VALUE;
            for (BlockPos q : com.claudemod.director.ClaudeLife.lavaSeen(g)) if (level.isLoaded(q) && lavaSrc(level, q) && q.distSqr(g.blockPosition()) < ld && q.distSqr(g.blockPosition()) <= 24 * 24) { ld = q.distSqr(g.blockPosition()); lava = q.immutable(); }
            if (lava == null) for (BlockPos q : BlockPos.betweenClosed(g.blockPosition().offset(-8, -4, -8), g.blockPosition().offset(8, 3, 8))) if (lavaSrc(level, q) && q.distSqr(g.blockPosition()) < ld && com.claudemod.director.ClaudeLife.canSee(g, level, q)) { ld = q.distSqr(g.blockPosition()); lava = q.immutable(); }
            if (lava == null) return null;
            BlockPos top = lava; for (int i = 0; i < 6 && lavaSrc(level, top.above()); i++) top = top.above();
            int y = top.getY();
            java.util.Set<BlockPos> pool = new java.util.HashSet<>(); java.util.ArrayDeque<BlockPos> qq = new java.util.ArrayDeque<>(); qq.add(top.immutable()); pool.add(top.immutable());
            while (!qq.isEmpty() && pool.size() < 96) { BlockPos c = qq.poll(); for (Direction d : Direction.Plane.HORIZONTAL) { BlockPos n = c.relative(d); if (!pool.contains(n) && lavaSrc(level, n)) { pool.add(n.immutable()); qq.add(n.immutable()); } } }
            BlockPos bestE = null, bestD = null; double bs = Double.MAX_VALUE;
            for (BlockPos p : pool) for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos e = p.relative(d);
                if (pool.contains(e) || level.getFluidState(e).is(FluidTags.LAVA) || level.getFluidState(e.above()).is(FluidTags.LAVA) || g.attempts.within(Attempts.Kind.REACH, e, g.tickCount, 20 * 600)) continue;
                if (level.getBlockState(e.below()).getCollisionShape(level, e.below()).isEmpty() || level.getFluidState(e.below()).is(FluidTags.LAVA) || level.getBlockState(e).getDestroySpeed(level, e) < 0 || level.getBlockState(e).is(Blocks.OBSIDIAN) || level.getBlockState(e.above()).getDestroySpeed(level, e.above()) < 0) continue;
                for (Direction d2 : Direction.Plane.HORIZONTAL) for (int dy = 0; dy <= 1; dy++) {
                    BlockPos dd = e.relative(d2).above(dy);
                    if (pool.contains(dd.below()) || !dryStand(level, dd)) continue;
                    double sc = dd.distSqr(g.blockPosition());
                    if (sc < bs) { bs = sc; bestE = e.immutable(); bestD = dd.immutable(); }
                }
            }
            if (bestE == null) { ClaudeMod.LOGGER.info("[Claude obsidian] the pool at {} (top y {}, {} cells): no pocket cell at its level with a floor and a dry spot beside it: the old way (LessonWords.technique)", xyz(top), y, pool.size()); return null; }
            k = new Tech(bestE, bestD, y, g.tickCount); techs().put(u, k);
            ClaudeMod.LOGGER.info("[Claude obsidian] the pool at {}: its top layer at y {} ({} cells); my pocket at {} beside it, worked from {}: the water poured at my feet there, the obsidian it makes mined from in it, layer by layer (LessonWords.technique)", xyz(top), y, pool.size(), xyz(bestE), xyz(bestD));
        }
        BlockPos e = k.pocket(), f = ExactSpot.feet(g);
        var fe = level.getFluidState(e);
        if (fe.is(FluidTags.WATER) && fe.isSource()) {
            if (f.equals(e)) { markOwn(g, e); return null; } // (in it: fromPlace from here)
            markOwn(g, e); again.run(); if (stepIn(g, e)) return "back into my water at " + xyz(e) + " (" + String.format("%.1f", Math.sqrt(Math.pow(e.getX() + 0.5 - g.getX(), 2) + Math.pow(e.getZ() + 0.5 - g.getZ(), 2))) + " off, step " + stepIns.get(g.getUUID()) + ")"; g.skillQueue.addFirst(new Skills.Step("go_to", xyz(e), 1, "exact"));
            ClaudeMod.LOGGER.info("[Claude obsidian] the water's in the pocket at {}: in there (LessonWords.technique)", xyz(e));
            return "into my water at " + xyz(e) + ", at the lava's level";
        }
        if (!f.equals(k.from()) && !(Vec3.atCenterOf(e).distanceTo(g.getEyePosition()) <= 4.0 && level.getFluidState(f).isEmpty() && !f.equals(e))) {
            g.tasks.add(new Task.GoTo(k.from(), 0.4)); again.run();
            return "over to " + xyz(k.from()) + ", beside my pocket at " + xyz(e) + " (the lava's level)";
        }
        if (!level.getBlockState(e.above()).getCollisionShape(level, e.above()).isEmpty()) { g.tasks.add(new Task.Break(e.above().immutable())); again.run(); return "the head room over my pocket at " + xyz(e) + " first"; }
        if (!level.getBlockState(e).getCollisionShape(level, e).isEmpty()) { g.tasks.add(new Task.Break(e)); again.run(); ClaudeMod.LOGGER.info("[Claude obsidian] opening my pocket {} beside the lava, the water straight in after (LessonWords.technique)", xyz(e)); return "opening my pocket at " + xyz(e) + ", the water ready"; }
        if (g.inventory.count(x -> x.is(Items.WATER_BUCKET)) == 0) return null; // (his bucket's empty: the old way fills it)
        again.run(); g.skillQueue.addFirst(new Skills.Step("place_at", xyz(e) + " water", 1, "near_you"));
        ClaudeMod.LOGGER.info("[Claude obsidian] the water into my pocket at {} (at the lava's level, y {}): the lava it touches turns (LessonWords.technique)", xyz(e), k.layer());
        return "the water into my pocket at " + xyz(e);
    }

    /** The pocket (or its head cell) he's opening beside the lava, his water ready: doBreak lets it (ClaudeLife.obsOpening). */
    public static boolean techOpening(ClaudeEntity g, BlockPos pos) { Tech k = techs == null ? null : techs.get(g.getUUID()); return k != null && (k.pocket().equals(pos) || k.pocket().above().equals(pos)); }
    /** His layer worked out and more wanted: one level down, the same cycle. An obsidian under a water hole beside him (never under him), broken; his source moved down into it. Null: nothing one down. */
    static String nextLayer(ClaudeEntity g, ServerLevel level, Runnable again, BlockPos cell, String count) {
        Vec3 eye = g.getEyePosition(); BlockPos best = null; double bs = Double.MAX_VALUE;
        for (BlockPos q : BlockPos.betweenClosed(cell.offset(-4, -1, -4), cell.offset(4, -1, 4))) {
            if (q.equals(cell.below()) || !level.getBlockState(q).is(Blocks.OBSIDIAN) || !level.getFluidState(q.above()).is(FluidTags.WATER) || Vec3.atCenterOf(q).distanceTo(eye) > 4.5) continue;
            if (!com.claudemod.director.ClaudeLife.canSee(g, level, q) || level.getFluidState(q.below()).is(FluidTags.LAVA)) continue;
            double sc = q.distSqr(cell); if (sc < bs) { bs = sc; best = q.immutable(); }
        }
        if (best == null) return null;
        if (g.inventory.count(x -> x.is(Items.BUCKET) || x.is(Items.WATER_BUCKET)) == 0) return null;
        again.run();
        g.skillQueue.addFirst(new Skills.Step("go_to", xyz(best), 1, "exact"));
        g.skillQueue.addFirst(new Skills.Step("place_at", xyz(best) + " water", 1, "near_you"));
        if (level.getFluidState(cell).isSource()) g.skillQueue.addFirst(new Skills.Step("scoop_at", xyz(cell), 1, "near_you"));
        g.tasks.add(new Task.Break(best));
        boolean own = ownPocket(g, cell); if (own) { ownPockets.get(g.getUUID()).remove(cell); markOwn(g, best); }
        Pin was = pin(g); if (was != null) pins().put(g.getUUID(), new Pin(best, was.words(), g.level().getGameTime()));
        ClaudeMod.LOGGER.info("[Claude obsidian] the layer at y {} done from {} ({}): one down, the obsidian at {} under the water out and my source moved down into it (LessonWords.nextLayer)", cell.getY(), xyz(cell), count, xyz(best));
        return "the top layer's done (" + count + "): one down, my source into " + xyz(best);
    }

    /** The drain wait in make_obsidian: only water that will recede (a flow cut off from any source). Water fed by a source that stays (his pocket's) never drains: no wait. */
    public static boolean draining(ClaudeEntity g, ServerLevel level) {
        BlockPos f = g.blockPosition();
        java.util.ArrayDeque<BlockPos> q = new java.util.ArrayDeque<>(); java.util.Set<BlockPos> seen = new java.util.HashSet<>();
        for (BlockPos s0 : new BlockPos[] {f, f.above()}) if (level.getFluidState(s0).is(FluidTags.WATER)) { q.add(s0); seen.add(s0); }
        BlockPos src = null;
        while (!q.isEmpty() && seen.size() < 96 && src == null) {
            BlockPos c = q.poll();
            if (level.getFluidState(c).isSource()) { src = c; break; }
            for (Direction d : Direction.values()) { BlockPos n = c.relative(d); if (seen.contains(n) || n.distManhattan(f) > 9 || !level.getFluidState(n).is(FluidTags.WATER)) continue; seen.add(n.immutable()); q.add(n.immutable()); }
        }
        String k = src == null ? "drain" : "fed:" + src.toShortString();
        if (!k.equals(logOnce().get(g.getUUID()))) { logOnce().put(g.getUUID(), k); ClaudeMod.LOGGER.info("[Claude lesson] the water round him at {}: {} (LessonWords.draining)", xyz(f), src == null ? "a flow with no source left: it drains, waiting it off" : "fed by the source at " + xyz(src) + (com.claudemod.director.Primitives.pouredOnAsk(g, src) ? " (the one they had him pour)" : "") + ": it never drains, mining on"); }
        return src == null;
    }

    /** Their lesson's over (ExactSpot.closeLesson): its count, its pin, its places and its last instruction go with it. */
    public static void lessonClosed(ClaudeEntity g) {
        unpin(g, "the lesson's over"); tallies().remove(g.getUUID()); commit().remove(g.getUUID());
        if ((g.lessonPlaces != null && !g.lessonPlaces.isBlank()) || (g.lessonLast != null && !g.lessonLast.isBlank())) ClaudeMod.LOGGER.info("[Claude lesson] the lesson's places ({}) and its last instruction (\"{}\") end with it (LessonWords.lessonClosed)", g.lessonPlaces, g.lessonLast);
        g.lessonPlaces = ""; g.lessonLast = "";
    }

    // ================================================================ for the planner (ExactSpot.feetTalk)

    public static String talk(ClaudeEntity g, ServerPlayer p) {
        StringBuilder sb = new StringBuilder();
        List<Place> ps = places(g);
        if (!ps.isEmpty() && ExactSpot.lesson(g, p)) { sb.append("LESSON PLACES (\"the pocket\", \"the spot\", \"your spot\", \"back to the pocket\" mean these, never their feet):"); for (Place q : ps) sb.append(' ').append(q.name().isEmpty() ? "place" : q.name()).append(" = ").append(xyz(q.cell())).append(q.water() ? " (water you stand in)" : "").append(','); sb.setLength(sb.length() - 1); }
        Said s = said().get(g.getUUID()); Long lt = lineTime().get(g.getUUID());
        if (s != null && lt != null && s.time() == lt) sb.append(sb.length() > 0 ? "; " : "").append("their latest line names ").append(s.name().isEmpty() ? "a place" : "the " + s.name()).append(" (\"").append(s.matched()).append("\") = ").append(xyz(s.cell())).append(": a walk there is go_to target \"").append(xyz(s.cell())).append("\" where \"exact\", never exact_them or near_them");
        Pin pn = pin(g);
        if (pn != null) sb.append(sb.length() > 0 ? "; " : "").append("THE JOB IS HELD TO ").append(xyz(pn.cell())).append(" (their words \"").append(pn.words()).append("\"): work from there only (make_obsidian works in place there, dig_at what's in reach); a step that leaves it is refused");
        Tally t = ExactSpot.lesson(g, p) ? tally(g) : null;
        if (t != null) sb.append(sb.length() > 0 ? "; " : "").append("LESSON COUNT: they asked for ").append(t.want).append(' ').append(t.item == null ? "blocks" : name(t.item)).append(" dug by you now (\"").append(t.words).append("\"): ").append(t.dug).append(" dug so far; your pack and your chests don't count toward it, and no give step unless they ask you to hand it over");
        String res = resumed().get(g.getUUID());
        if (res != null) sb.append(sb.length() > 0 ? "; " : "").append("RESUME: they said carry on with the lesson: its last instruction in THEIR words is \"").append(res).append("\": plan exactly that, nothing your own goal added");
        else if (ExactSpot.lesson(g, p) && g.lessonLast != null && !g.lessonLast.isBlank()) sb.append(sb.length() > 0 ? "; " : "").append("the lesson's last instruction in their words: \"").append(g.lessonLast).append("\"");
        return sb.toString();
    }
}
