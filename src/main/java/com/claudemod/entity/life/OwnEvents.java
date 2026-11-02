package com.claudemod.entity.life;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.claudemod.ClaudeMod;
import com.claudemod.entity.ClaudeEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

/**
 * His own recent events, as typed facts: what he mined, picked up, LEFT behind (and why), placed, gave, took from a chest. Beside
 * MindLog (the raw lines), these are what the chat and the planner answer from, and what "you didn't grab all your diamonds" resolves
 * against (03:32, world 14: he left a diamond "no way to it", then told the user "no luck, didn't find any more" and asked for coords).
 * A LEFT stays open until he picks that drop back up, or goes back and finds it gone.
 */
public final class OwnEvents {
    private OwnEvents() {}

    public enum Kind { MINED, LEFT, PICKED_UP, PLACED, GAVE, TOOK }

    public static final class Event {
        public final Kind kind; public final String what; public final BlockPos at; public final String why; public final long tick;
        public final String clock; public final UUID entity; public final ResourceKey<Level> dim;
        public int count; long lastTick; int tries; long waitTick, recoverTick; // (waitTick: the sweep gave up on it by his own mine, it waits for recover_left; recoverTick: recover_left went for it)
        public String resolved; // a LEFT: how it ended ("picked back up", "gone when I went back"); null while it's still lying there as far as he knows
        Event(Kind kind, String what, int count, BlockPos at, String why, long tick, UUID entity, ResourceKey<Level> dim) {
            this.kind = kind; this.what = what; this.count = count; this.at = at; this.why = why; this.tick = tick; this.lastTick = tick; this.entity = entity; this.dim = dim;
            this.clock = java.time.LocalTime.now().withNano(0).toString();
        }
        public boolean open() { return kind == Kind.LEFT && resolved == null && count > 0; }
    }

    private static final int KEEP = 40;
    private static final Map<UUID, ArrayDeque<Event>> BOOK = new HashMap<>();
    /** The line he last said from his own events (for the tests). */
    public static String lastReply = "";

    static ArrayDeque<Event> book(ClaudeEntity g) { return BOOK.computeIfAbsent(g.getUUID(), k -> new ArrayDeque<>()); }
    public static void reset(ClaudeEntity g) { BOOK.remove(g.getUUID()); lastReply = ""; }
    public static List<Event> all(ClaudeEntity g) { return new ArrayList<>(book(g)); }
    static String id(Item i) { return BuiltInRegistries.ITEM.getKey(i).getPath(); }
    static String name(String what) { return what.replace('_', ' '); }
    public static String xyz(BlockPos p) { return p.getX() + " " + p.getY() + " " + p.getZ(); }

    static Event note(ClaudeEntity g, Kind k, String what, int n, BlockPos at, String why, UUID ent) {
        long now = g.level().getGameTime();
        ArrayDeque<Event> b = book(g);
        Event last = null; for (var it = b.descendingIterator(); it.hasNext(); ) { Event x = it.next(); if (x.kind == k && x.what.equals(what)) { last = x; break; } } // (the vein's picks and pickups come between its blocks)
        if (last != null && last.kind == k && last.what.equals(what) && (k == Kind.MINED || k == Kind.PICKED_UP) && now - last.lastTick <= 200 && last.at.distManhattan(at) <= 8) {
            last.count += n; last.lastTick = now; return last; // one vein, one sweep: one fact
        }
        Event e = new Event(k, what, n, at.immutable(), why, now, ent, g.level().dimension());
        b.addLast(e);
        while (b.size() > KEEP) { // the oldest goes, but never a drop still lying where he left it
            Event drop = null; for (Event x : b) if (!x.open()) { drop = x; break; }
            b.remove(drop != null ? drop : b.peekFirst());
        }
        return e;
    }

    // ---- what happens to him (the game's own events, his only)

    /** Ore he broke. */
    public static void onBreak(net.neoforged.neoforge.event.level.BlockEvent.BreakEvent e) {
        if (!(e.getPlayer() instanceof ClaudeEntity g)) return;
        String id = BuiltInRegistries.BLOCK.getKey(e.getState().getBlock()).getPath();
        if (e.getState().is(net.minecraft.tags.BlockTags.GUARDED_BY_PIGLINS)) ClaudeMod.LOGGER.info("[Claude piglin] he broke {} at {}: piglins in sight get angry (OwnEvents.onBreak)", id, e.getPos().toShortString()); // ponytail: the 16:49 death's cause, logged
        if (id.endsWith("_ore") || id.equals("ancient_debris")) note(g, Kind.MINED, id, 1, e.getPos(), "", null);
        LessonWords.broke(g, e.getState(), e.getPos()); // (their lesson's count: dug by him)
    }

    /** What he picked up; a drop he'd left, picked back up, closes that LEFT. */
    public static void onPickup(net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent.Post e) {
        if (!(e.getPlayer() instanceof ClaudeEntity g)) return;
        ItemStack orig = e.getOriginalStack(), rest = e.getCurrentStack();
        int n = orig.getCount() - (rest.isEmpty() ? 0 : rest.getCount());
        if (n <= 0) return;
        String what = id(orig.getItem()); BlockPos at = e.getItemEntity().blockPosition();
        int left = n;
        for (Event l : book(g)) {
            if (left <= 0) break;
            if (!l.open() || !l.what.equals(what) || !(e.getItemEntity().getUUID().equals(l.entity) || (l.dim == g.level().dimension() && l.at.distManhattan(at) <= 3))) continue;
            int k = Math.min(left, l.count); l.count -= k; left -= k;
            if (l.count <= 0) l.resolved = "picked back up";
            ClaudeMod.LOGGER.info("[Claude memory] picked back up {} {} he'd left at {} ({}) (OwnEvents.onPickup)", k, name(what), xyz(l.at), l.count <= 0 ? "that one's done" : l.count + " still there");
        }
        if (notable(orig)) note(g, Kind.PICKED_UP, what, n, at, "", null);
    }

    /** A station or chest he put down. */
    public static void onPlace(net.neoforged.neoforge.event.level.BlockEvent.EntityPlaceEvent e) {
        if (!(e.getEntity() instanceof ClaudeEntity g)) return;
        var s = e.getPlacedBlock();
        if (s.hasBlockEntity() || s.is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE)) note(g, Kind.PLACED, BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath(), 1, e.getPos(), "", null);
    }

    /** The pickup sweep gave up on a drop (WorkGoal.doPickup): left, with why. */
    public static void left(ClaudeEntity g, ItemEntity it, String why) {
        for (Event l : book(g)) if (l.open() && it.getUUID().equals(l.entity)) return; // (already on the book)
        Event e = note(g, Kind.LEFT, id(it.getItem().getItem()), it.getItem().getCount(), it.blockPosition(), why, it.getUUID());
        ClaudeMod.LOGGER.info("[Claude memory] noted: left {} {} at {} behind ({}) (OwnEvents.left)", e.count, name(e.what), xyz(e.at), why);
    }
    /** The sweep gave up on this drop by his own mine: it waits for recover_left. True while recover_left has goes left at it. */
    public static boolean waitFor(ClaudeEntity g, ItemEntity it) { for (Event l : book(g)) if (l.open() && it.getUUID().equals(l.entity)) { l.waitTick = g.level().getGameTime(); return l.tries < 3; } return false; }
    /** A drop of his the sweeps leave (it waits for recover_left), till recover_left goes for it. */
    public static boolean waiting(ClaudeEntity g, ItemEntity it) { ArrayDeque<Event> b = BOOK.get(g.getUUID()); if (b == null) return false; for (Event l : b) if (l.open() && it.getUUID().equals(l.entity) && l.waitTick > 0 && l.recoverTick < l.waitTick) return true; return false; }
    public static void gave(ClaudeEntity g, Item item, int n, BlockPos at) { note(g, Kind.GAVE, id(item), n, at, "", null); }
    public static void took(ClaudeEntity g, Item item, int n, BlockPos chest) { note(g, Kind.TOOK, id(item), n, chest, "", null); }

    static boolean notable(ItemStack s) {
        String p = id(s.getItem());
        return p.equals("diamond") || p.equals("emerald") || p.startsWith("raw_") || p.endsWith("_ingot") || p.equals("coal") || p.equals("lapis_lazuli") || p.equals("redstone")
                || p.equals("ancient_debris") || p.equals("netherite_scrap") || p.equals("ender_pearl") || p.equals("blaze_rod") || p.equals("obsidian") || p.equals("quartz")
                || p.contains("diamond_") || p.contains("netherite") || s.getItem() instanceof net.minecraft.world.item.ArmorItem;
    }

    // ---- resolvers

    static final String[] NOUNS = {"diamond", "emerald", "iron", "gold", "copper", "coal", "lapis", "redstone", "quartz", "debris", "netherite", "obsidian", "pearl", "blaze"};
    /** The item word in their words ("diamonds" -> "diamond"), or "" (them, it, the rest). */
    public static String noun(String words) {
        String low = words.toLowerCase();
        for (String n : NOUNS) if (low.matches("(?s).*\\b" + n + "(s|es)?\\b.*")) return n;
        return "";
    }
    static boolean matches(String what, String word) { return word == null || word.isBlank() || what.contains(word.toLowerCase().replace("minecraft:", "").replace(' ', '_').replaceAll("_ore$", "").replaceAll("s$", "")); }

    /** The latest drop of his still lying where he left it (as far as he knows) that matches the word ("diamond", "iron"; "" for any). */
    public static Event lastLeft(ClaudeEntity g, String word) {
        Event best = null; for (Event e : book(g)) if (e.open() && matches(e.what, word)) best = e; return best;
    }
    /** Every drop of that kind he left and hasn't got back. */
    public static List<Event> leftNow(ClaudeEntity g, String word) {
        List<Event> out = new ArrayList<>(); for (Event e : book(g)) if (e.open() && matches(e.what, word)) out.add(e); return out;
    }
    /** The latest ore of that kind he mined. */
    public static Event lastMined(ClaudeEntity g, String word) {
        Event best = null; for (Event e : book(g)) if (e.kind == Kind.MINED && matches(e.what, word)) best = e; return best;
    }

    /** The facts, for the chat and the planner. Empty when there are none. */
    public static String describe(ClaudeEntity g) {
        ArrayDeque<Event> b = BOOK.get(g.getUUID());
        if (b == null || b.isEmpty()) return "";
        long now = g.level().getGameTime();
        List<Event> show = new ArrayList<>(); int i = 0, from = Math.max(0, b.size() - 14);
        for (Event e : b) { if (i++ >= from || e.open()) show.add(e); }
        StringBuilder sb = new StringBuilder("YOUR OWN EVENTS (what you yourself did and saw, oldest first; fact: answer from these with their coordinates, never the opposite of them): ");
        boolean first = true;
        for (Event e : show) {
            sb.append(first ? "" : "; ").append(e.clock).append(" (").append(ago(now - e.tick)).append(") ");
            first = false;
            switch (e.kind) {
                case MINED -> { sb.append("mined ").append(e.count).append(' ').append(name(e.what)).append(" at ").append(xyz(e.at));
                    if (now - e.tick <= 20 * 600 && e.dim == g.level().dimension()) { String w = e.what.replace("deepslate_", "").replace("nether_", "").replace("_ore", ""); int more = 0; for (BlockPos p : BlockPos.betweenClosed(e.at.offset(-3, -3, -3), e.at.offset(3, 3, 3))) if (oreOf((ServerLevel) g.level(), p, w) && showing((ServerLevel) g.level(), p)) more++; if (more > 0) sb.append(" (").append(more).append(" more of it still standing round it)"); } }
                case PICKED_UP -> sb.append("picked up ").append(e.count).append(' ').append(name(e.what));
                case LEFT -> sb.append("LEFT ").append(e.open() ? e.count : "").append(e.open() ? " " : "").append(name(e.what)).append(" behind at ").append(xyz(e.at)).append(": ").append(e.why)
                        .append(e.open() ? " then (still lying there as far as you know" + (now - e.tick > 6000 ? ", though a drop lasts 5 minutes while its spot is loaded" : "") + "; recover_left " + e.what + " goes back and digs it out)" : " (since " + e.resolved + ")");
                case PLACED -> sb.append("placed a ").append(name(e.what)).append(" at ").append(xyz(e.at));
                case GAVE -> sb.append("gave them ").append(e.count).append(' ').append(name(e.what)).append(" (at ").append(xyz(e.at)).append(")");
                case TOOK -> sb.append("took ").append(e.count).append(' ').append(name(e.what)).append(" from your chest at ").append(xyz(e.at));
            }
        }
        return sb.append(".\n").toString();
    }
    static String ago(long ticks) { long s = Math.max(0, ticks / 20); return s < 60 ? s + " s ago" : (s / 60) + " min ago"; }

    // ---- their words about his own events

    /**
     * Their words point at something he left behind himself ("you didn't grab all your diamonds", "you left one", "get the rest",
     * "the diamonds you just mined" with one left): resolved against his book, he says what he left where and why, and goes back for
     * it (recover_left, before what he was doing). True when it's handled here; false leaves it to the chat, which has the facts.
     */
    public static boolean heardLeftover(ClaudeEntity g, ServerPlayer player, String raw) {
        String low = raw.toLowerCase().replace('’', '\'');
        if (low.matches("(?s).*\\b(don'?t|do not|no need|leave (it|them|that|the rest)|forget (it|them|about))\\b.*")) return false; // (theirs to call off: the chat reads it)
        boolean leftWords = low.matches("(?s).*\\b(didn'?t|did not|never|haven'?t|have not)\\b.*\\b(grab|get|got|pick(ed)? up|take|took|collect(ed)?)\\b.*\\b(all|every|everything|the rest|both)\\b.*")
                || low.matches("(?s).*\\byou (left|forgot|missed|dropped)\\b.*")
                || low.matches("(?s).*\\b(grab|get|pick up|take|collect|go back for|fetch)\\b.*\\b(the rest|the others?|the last( one)?|what you left|the ones? you left|the leftovers?)\\b.*")
                || low.matches("(?s).*\\bthere'?s (still )?(one|a|another|some|more)\\b.*\\b(left|lying|on the ground|still there|in (the|that) wall)\\b.*");
        boolean minedWords = low.matches("(?s).*\\byou (just )?mined\\b.*");
        boolean oreWords = low.matches("(?s).*\\b(missed|skipped|forgot|left|didn'?t (get|mine|take))\\b.*\\b(some|one|a|an|two|three|four|more|the rest|another|any)\\b.*")
                || low.matches("(?s).*\\bthere'?s (still )?(more|some|another|one more)\\b.*") || low.matches("(?s).*\\bmore\\b.*\\b(in (the|that) wall|there|left)\\b.*"); // "missed some lapis there bud": ore still in the wall (05:01 it went to recover_left: "no lapis left lying")
        if (!leftWords && !minedWords && !oreWords) return false;
        String word = noun(low);
        ServerLevel level = (ServerLevel) g.level();
        List<Event> open = leftNow(g, word);
        if (minedWords && !leftWords) open.removeIf(e -> g.level().getGameTime() - e.tick > 6000); // "the ones you just mined": a leftover of the last few minutes
        List<BlockPos> vein = oreWords && !word.isEmpty() ? standingOre(g, level, word) : List.of();
        if (open.isEmpty() && vein.isEmpty()) {
            ClaudeMod.LOGGER.info("[Claude memory] \"{}\" reads as about {} he left, but his book has none of that lying anywhere{}: the chat answers from his events (OwnEvents.heardLeftover)", raw, word.isEmpty() ? "something" : word, oreWords && !word.isEmpty() ? " and he saw none of it still standing where he mined" : "");
            return false;
        }
        var steps = new com.google.gson.JsonArray(); List<String> say = new ArrayList<>(); List<String> goal = new ArrayList<>(); String why = "";
        if (!open.isEmpty()) { // the drops first: they go, the vein stays
            Event e = open.get(open.size() - 1); why = e.why;
            int total = 0; List<String> spots = new ArrayList<>();
            for (Event x : open) if (x.what.equals(e.what)) { total += x.count; if (spots.size() < 3) spots.add(xyz(x.at)); }
            say.add("i left " + total + " " + name(e.what) + " at " + String.join(" and ", spots) + " (" + e.why + " then)");
            goal.add("get back the " + name(e.what) + " i left at " + xyz(e.at));
            ClaudeMod.LOGGER.info("[Claude memory] \"{}\" is about what he left: {} {} at {} ({}): going back for it, before what he was doing (OwnEvents.heardLeftover)", raw, total, name(e.what), String.join(", ", spots), e.why);
            var o = new com.google.gson.JsonObject(); o.addProperty("skill", "recover_left"); o.addProperty("target", e.what); o.addProperty("count", total); o.addProperty("where", "near_you"); steps.add(o);
        }
        if (!vein.isEmpty()) { // the rest of the vein: to it, and the ore path
            BlockPos at = vein.get(0); String block = BuiltInRegistries.BLOCK.getKey(level.getBlockState(at).getBlock()).getPath();
            say.add("there's " + vein.size() + " more " + word + " in that wall at " + xyz(at));
            goal.add("mine the rest of the " + word + " vein at " + xyz(at));
            Event m = lastMined(g, word);
            ClaudeMod.LOGGER.info("[Claude memory] \"{}\" is about ore he left standing: {} {} still there round {} ({}): to it and the rest of that vein, collect {} (OwnEvents.heardLeftover)", raw, vein.size(), block, xyz(at), m == null ? "round him" : "where he mined " + name(m.what) + " at " + xyz(m.at), block);
            var o1 = new com.google.gson.JsonObject(); o1.addProperty("skill", "go_to"); o1.addProperty("target", xyz(at)); o1.addProperty("count", 1); o1.addProperty("where", "near_you"); steps.add(o1);
            var o2 = new com.google.gson.JsonObject(); o2.addProperty("skill", "collect"); o2.addProperty("target", block); o2.addProperty("count", vein.size()); o2.addProperty("where", "near_you"); steps.add(o2);
        }
        String line = vein.isEmpty() ? "yeah, my bad: " + say.get(0) + ". going back to dig it out now"
                : open.isEmpty() ? "yeah, " + say.get(0) + ", getting them"
                : "yeah, my bad: " + String.join(", and ", say) + ". getting it all now";
        String said = com.claudemod.director.ClaudeChat.say(player, line, "own events: " + raw);
        lastReply = said == null ? line : said;
        com.claudemod.director.ClaudeLife.command(g, player, steps, String.join(", then ", goal), "do_first");
        return true;
    }

    /** Ore of that kind he saw still standing round where he last mined it (the last 10 minutes), else round him: the cells he remembers (ClaudeLife.seenOre, within 8) and the ore showing round the spot he mined (within 3, a face open: he saw it as he swung). Nearest that spot first. */
    public static List<BlockPos> standingOre(ClaudeEntity g, ServerLevel level, String word) {
        Event m = lastMined(g, word);
        if (m != null && (level.getGameTime() - m.tick > 20 * 600 || m.dim != level.dimension())) m = null;
        final BlockPos c = m != null ? m.at : g.blockPosition();
        java.util.LinkedHashSet<BlockPos> out = new java.util.LinkedHashSet<>();
        for (BlockPos p : com.claudemod.director.ClaudeLife.seenOre(g)) if (p.distSqr(c) <= 8 * 8 && oreOf(level, p, word)) out.add(p.immutable());
        if (m != null) for (BlockPos p : BlockPos.betweenClosed(c.offset(-3, -3, -3), c.offset(3, 3, 3))) if (oreOf(level, p, word) && showing(level, p)) out.add(p.immutable());
        List<BlockPos> l = new ArrayList<>(out); l.sort(java.util.Comparator.comparingDouble(p -> p.distSqr(c)));
        return l;
    }
    static boolean oreOf(ServerLevel level, BlockPos p, String word) { if (!level.isLoaded(p)) return false; var s = level.getBlockState(p); return MineBlueprint.ore(s) && BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath().contains(word); }
    static boolean showing(ServerLevel level, BlockPos p) { for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) { var n = level.getBlockState(p.relative(d)); if (n.isAir() || !n.canOcclude()) return true; } return false; }

    // ---- the skill

    /**
     * The way to a spot through his own patches (ActionPath MAY_REOPEN), then the sweep, then each patch it dug put back, lowest
     * first: doBreak's ownPatch reopen (Break patch, ..., Pickup(100), Place patch back). The shell guard lets a patch out while its
     * Place is queued; doPlace steps him back into his walkway before it patches from outside. Null: no such way.
     */
    public static List<Task> reopenRoute(ClaudeEntity g, ServerLevel level, BlockPos to, double within, String why) {
        BlockPos feet = BlockPos.containing(g.getX(), g.getY() + 0.2, g.getZ());
        Item scaff = null; for (Item s : new Item[] {Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.DIRT, Items.NETHERRACK}) if (g.inventory.count(x -> x.is(s)) > 0) { scaff = s; break; }
        long t0 = System.nanoTime();
        var plan = com.claudemod.entity.nav.ActionPath.plan(g, level, feet, to, within, scaff != null, 6000, com.claudemod.entity.nav.ActionPath.Mode.MAY_REOPEN, g.attempts.of(Attempts.Kind.WALK), g.tickCount);
        if (plan == null || plan.acts().isEmpty()) { ClaudeMod.LOGGER.info("[Claude memory] {}: no way to {} even through his patches (OwnEvents.reopenRoute)", why, xyz(to)); return null; }
        List<BlockPos> patches = new ArrayList<>(); Map<BlockPos, Item> block = new HashMap<>();
        int beside = 0; List<BlockPos> own = new ArrayList<>();
        for (var a : plan.acts()) if (a instanceof com.claudemod.entity.nav.ActionPath.Act.Dig d) { int r = com.claudemod.entity.nav.ActionPath.reopenable(g, level, d.pos()); if (r == 0) continue; var st0 = level.getBlockState(d.pos()); patches.add(d.pos().immutable()); if (r == 2) beside++; else own.add(d.pos().immutable());
            block.put(d.pos().immutable(), r == 1 ? st0.getBlock().asItem() : d.pos().getY() < 0 || st0.is(net.minecraft.world.level.block.Blocks.DEEPSLATE) ? Items.COBBLED_DEEPSLATE : Items.COBBLESTONE); } // (the cell under/over the patch goes back as his patch block: cobbled deepslate below y 0, what his mine's shell takes there)
        List<Task> out = new ArrayList<>(com.claudemod.entity.nav.ActionPath.tasks(plan, feet, scaff == null ? Items.COBBLESTONE : scaff, to, within, level));
        out.add(new Task.Pickup(100));
        if (!patches.isEmpty()) { BlockPos backTo = feet, lastWalk = feet; for (var a : plan.acts()) { if (a instanceof com.claudemod.entity.nav.ActionPath.Act.Walk w) lastWalk = w.feet(); else if (a instanceof com.claudemod.entity.nav.ActionPath.Act.Dig d0 && patches.contains(d0.pos())) { backTo = lastWalk; break; } }
            { BlockPos first = null; for (BlockPos q : patches) if (first == null || q.distSqr(backTo) < first.distSqr(backTo)) first = q; net.minecraft.core.Direction away = first == null ? null : net.minecraft.core.Direction.getNearest(backTo.getX() - first.getX(), 0, backTo.getZ() - first.getZ()); BlockPos deeper = away == null ? null : backTo.relative(away); if (deeper != null && g.blueprint != null && g.blueprint.isWalk(deeper) && level.getBlockState(deeper).getCollisionShape(level, deeper).isEmpty() && level.getBlockState(deeper.above()).getCollisionShape(level, deeper.above()).isEmpty()) backTo = deeper; } // (a cell further into his walkway: on the lip of the cell he opened, a walk to the cell right under him stalled, 263)
            out.add(new Task.GoTo(backTo, 0.6)); } // back out to where he stood before the first cell he opened (263: he stood in the reopened cell and its patch could never go back)
        patches.sort(java.util.Comparator.comparingInt(BlockPos::getY));
        for (BlockPos p : patches) out.add(new Task.Place(p, block.get(p))); // his patch back, from his walkway (doPlace steps him in first)
        java.util.List<BlockPos> placed = patches.isEmpty() ? List.of() : com.claudemod.context.PlayerProfile.placedBy(g);
        ClaudeMod.LOGGER.info("[Claude memory] {}: the way to {}: {} dig(s){}, {} walk(s), {} put(s) ({} ms) (OwnEvents.reopenRoute)", why, xyz(to), plan.digs(),
                patches.isEmpty() ? ", none of them a patch of his" : " (" + patches.size() + " of them his patch or the shell by it, " + beside + " the cell under/over it" + (own.stream().allMatch(placed::contains) ? ", on his placed record" : ", by its block: his placed record has lost it") + ": " + patches.stream().map(OwnEvents::xyz).toList() + ", put back after)", plan.walks(), plan.puts(), (System.nanoTime() - t0) / 1000000);
        return out;
    }

    /**
     * recover_left <item>: back to the latest drop of that item he left, the way there planned over a player's moves with digging
     * (ActionPath MAY_DIG), then the sweep (the drop is his again); this step comes back after to check his pocket, and goes on to
     * the next one he left. There and it's gone: said so, closed.
     */
    public static String recover(ClaudeEntity g, ServerLevel level, Skills.Step st, String target) {
        Event e = lastLeft(g, target);
        if (e == null) {
            Event back = null; for (Event x : book(g)) if (x.kind == Kind.LEFT && matches(x.what, target) && x.resolved != null && level.getGameTime() - x.tick <= 20 * 600) back = x;
            if (back != null) { String w = back.what; return "done: got back what I'd left (" + name(w) + " at " + xyz(back.at) + ": " + back.resolved + "), " + g.inventory.count(s -> id(s.getItem()).equals(w)) + " on me now"; }
            return "done: my book shows no " + (target.isBlank() ? "" : name(target) + " ") + "of mine left lying anywhere";
        }
        if (e.dim != level.dimension()) return "failed: the " + name(e.what) + " I left at " + xyz(e.at) + " is in another world (" + e.dim.location().getPath() + ")";
        ItemEntity it = level.getEntity(e.entity) instanceof ItemEntity ie && ie.isAlive() ? ie : null;
        if (it == null) for (ItemEntity x : level.getEntitiesOfClass(ItemEntity.class, new net.minecraft.world.phys.AABB(e.at).inflate(2), x -> x.isAlive() && id(x.getItem().getItem()).equals(e.what))) { it = x; break; }
        double d = Math.sqrt(e.at.distToCenterSqr(g.position()));
        if (it == null && d <= 3.0) { // he's there and it isn't
            e.resolved = "gone when I went back";
            ClaudeMod.LOGGER.info("[Claude memory] recover_left: at {} and the {} he left there is gone (OwnEvents.recover)", xyz(e.at), name(e.what));
            return "done: went back to " + xyz(e.at) + " and the " + name(e.what) + " I left there is gone (picked up or despawned)";
        }
        if (++e.tries > 3) {
            ClaudeMod.LOGGER.info("[Claude memory] recover_left: three goes at the {} at {} and it's still there: not again (OwnEvents.recover)", name(e.what), xyz(e.at));
            return "failed: three goes at the " + name(e.what) + " I left at " + xyz(e.at) + " and I still can't get to it";
        }
        if (it != null) g.unGift(it); // his again: the sweep takes it
        e.recoverTick = level.getGameTime(); // (and the sweeps go for it again)
        BlockPos feet = BlockPos.containing(g.getX(), g.getY() + 0.2, g.getZ());
        Item scaff = null; for (Item s : new Item[] {Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.DIRT, Items.NETHERRACK}) if (g.inventory.count(x -> x.is(s)) > 0) { scaff = s; break; }
        long t0 = System.nanoTime();
        List<Task> reopen = d <= 1.5 ? null : reopenRoute(g, level, e.at, 1.8, "recover_left"); // through his own patch when it lies behind one (04:25 MAY_DIG cut a 30-block tunnel round it)
        var plan = d <= 1.5 || reopen != null ? null : com.claudemod.entity.nav.ActionPath.plan(g, level, feet, e.at, 1.0, scaff != null, 6000, com.claudemod.entity.nav.ActionPath.Mode.MAY_DIG, g.attempts.of(Attempts.Kind.WALK), g.tickCount);
        String how;
        if (reopen != null) {
            g.tasks.addAll(reopen);
            long n = reopen.stream().filter(x -> x instanceof Task.Break).count(), back = reopen.stream().filter(x -> x instanceof Task.Place).count();
            how = n + " block(s) to dig" + (back > 0 ? ", " + back + " of them my own patch, put back after" : "");
        } else if (plan != null && !plan.acts().isEmpty()) {
            g.tasks.addAll(com.claudemod.entity.nav.ActionPath.tasks(plan, feet, scaff == null ? Items.COBBLESTONE : scaff, e.at, 1.0, level));
            how = plan.digs() + " block(s) to dig, " + plan.walks() + " walk(s)";
        } else if (d > 1.5) { g.tasks.add(new Task.GoTo(e.at, 1.0)); how = "no planned way from here, the walker's own"; }
        else how = "right by it";
        if (reopen == null) g.tasks.add(new Task.Pickup(100)); // (the reopen route has its own, before the patch goes back)
        g.skillQueue.addFirst(st); // back to this step after: is it in his pocket, and is there another one
        ClaudeMod.LOGGER.info("[Claude memory] recover_left: {} {} he left at {} ({}), try {}: {}{} ({} ms) (OwnEvents.recover)", e.count, name(e.what), xyz(e.at), e.why, e.tries, how,
                it == null ? ", none in sight from here" : "", (System.nanoTime() - t0) / 1000000);
        return "going back for the " + e.count + " " + name(e.what) + " I left at " + xyz(e.at) + " (" + e.why + " then; try " + e.tries + "): " + how;
    }
}
