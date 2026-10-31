package com.claudemod.director;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import com.claudemod.ClaudeMod;
import com.claudemod.entity.ClaudeEntity;
import com.claudemod.entity.life.ChestLedger;
import com.claudemod.entity.life.Crafting;
import com.claudemod.entity.life.Skills;
import com.claudemod.entity.life.Task;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.block.Blocks;

/**
 * Their give asks as one errand, the way a player handles "a pick and some food", "some stone picks", "more food", "torches"
 * coming in a row: trip one hands over what's already on him (and what's in a chest of his on the way), keeping what he needs
 * himself the way an expert does (his best pick, his sword, his next meals, a few torches); trip two makes or gathers the
 * rest (crafted at the nearest table, cooked at the nearest furnace) and brings it all in one delivery. A new ask merges into
 * the open errand as it arrives, from a chat line or from a plan step. Each plan is logged as one "[Claude errand #n]" line.
 *
 * One "errand" step in his queue runs it (ClaudeLife.runSkill): it works out the plan from what's owed and what he holds right
 * now, queues the hand-over (walk, chest, tosses) or the make steps and the delivery step behind them, and comes back after.
 */
public final class Errand {
    private Errand() {}

    /** Within this of them, what's on him goes over now (a few seconds' walk); further off, with the delivery if the making is quick. */
    static final int NEAR = 24;
    /** Torches he keeps for himself (of what he had when they asked). */
    static final int TORCH_KEEP = 16;
    /** Make rounds before he settles for what he has. */
    static final int ROUNDS = 3;

    static final class State {
        final int id, started; int lastAsk;
        final Map<Item, Integer> asked = new LinkedHashMap<>(), given = new LinkedHashMap<>(), sub = new LinkedHashMap<>(), self = new LinkedHashMap<>(), making = new LinkedHashMap<>(), start = new HashMap<>();
        final Map<String, Item> ownBest = new HashMap<>(); // his best pick and sword when they asked: those are his
        final List<Item> foodNames = new ArrayList<>();
        final List<String> kept = new ArrayList<>(), failedMakes = new ArrayList<>(), said = new ArrayList<>();
        int food, foodGiven, foodMaking, serial, trips, deliveries, rounds, plans, handTries, givenAtHand;
        final List<Task.Toss> tosses = new ArrayList<>(); // his hand-overs queued and not done yet (by identity)
        boolean diamondsTheirs;
        Skills.Step delivery;
        String lastLine = "";
        State(int id, int now) { this.id = id; this.started = now; this.lastAsk = now; }
    }
    private static Map<UUID, State> open, last; // (static maps: fields added live start null)
    private static int nextId = 1;
    private static Map<UUID, State> open() { if (open == null) open = new HashMap<>(); return open; }
    private static Map<UUID, State> last() { if (last == null) last = new HashMap<>(); return last; }

    // ---------------------------------------------------------------- for the tests and the truth block
    public static void reset(ClaudeEntity g) { open().remove(g.getUUID()); last().remove(g.getUUID()); }
    private static State any(ClaudeEntity g) { State e = open().get(g.getUUID()); return e != null ? e : last().get(g.getUUID()); }
    public static boolean isOpen(ClaudeEntity g) { return open().containsKey(g.getUUID()); }
    public static int id(ClaudeEntity g) { State e = any(g); return e == null ? -1 : e.id; }
    public static int deliveries(ClaudeEntity g) { State e = any(g); return e == null ? 0 : e.deliveries; }
    public static int trips(ClaudeEntity g) { State e = any(g); return e == null ? 0 : e.trips; }
    public static int plans(ClaudeEntity g) { State e = any(g); return e == null ? 0 : e.plans; }
    public static String line(ClaudeEntity g) { State e = any(g); return e == null ? "" : e.lastLine; }
    /** What he'd keep of this for himself now (the errand's rules; no errand open: as if one opened now). */
    public static int keepNow(ClaudeEntity g, Item it) { State e = open().get(g.getUUID()); if (e == null) { e = new State(0, g.tickCount); snapshot(g, e); if (it == Items.DIAMOND || tier(it) >= 3) e.diamondsTheirs = true; } return proper(g.serverLevel(), it) ? foodKeep(g, g.serverLevel()) : keep(g, e, it); }
    /** For truthFor: the open errand in a line, or null. */
    public static String describe(ClaudeEntity g) {
        State e = open().get(g.getUUID());
        if (e == null) return null;
        String l = e.lastLine.replaceFirst("^\\[Claude errand #\\d+\\] ", "").replaceFirst(" \\([A-Za-z.]+\\)$", "");
        return "#" + e.id + ": " + l + "; handed over so far: " + (given(e).isEmpty() ? "nothing" : given(e));
    }
    /** "give them 2 stone_pickaxe, 6 food, 16 torch": the goal in words. */
    public static String words(ClaudeEntity g) {
        State e = open().get(g.getUUID());
        if (e == null) return "give them what they asked for";
        List<String> w = new ArrayList<>();
        for (var x : wants(e).entrySet()) w.add(x.getValue() + " " + Crafting.name(x.getKey()));
        if (e.food > 0) w.add(e.food + " food");
        return "give them " + String.join(", ", w);
    }

    // ---------------------------------------------------------------- the asks
    static String norm(String t) { return t.toLowerCase().replace("minecraft:", "").trim().replace(' ', '_'); }
    /** The item a give names, the way they say it: "torches", "stone picks", "food" (his best proper food; none on him: cooked beef, made). */
    static Item resolve(ClaudeEntity g, String target) {
        String id = norm(target);
        if (id.matches("food|foods|meat|meal|meals|something_to_eat|grub|steak")) { Item f = ClaudeLife.foodToEat(g, false); return f != null && proper(g.serverLevel(), f) ? f : Items.COOKED_BEEF; }
        for (String c : new String[] {id, id.endsWith("es") ? id.substring(0, id.length() - 2) : id, id.endsWith("s") ? id.substring(0, id.length() - 1) : id}) {
            Item it = ClaudeLife.item(c.endsWith("_pick") ? c + "axe" : c);
            if (it != null) return it;
        }
        return null;
    }

    /**
     * Their give steps out of a command into the errand (and the make steps for the same things: the errand decides what to
     * make); the rest of the command back, with the errand's step where the first give was. Null: the ask merged into the open
     * errand and there's nothing else in the command.
     */
    public static List<Skills.Step> take(ClaudeEntity g, ServerPlayer player, List<Skills.Step> queue, String change) {
        ServerLevel level = g.serverLevel();
        List<Item> giveItems = new ArrayList<>();
        for (Skills.Step s : queue) if (s.skill().equals("give")) { Item it = resolve(g, s.target()); if (it != null) giveItems.add(it); }
        if (giveItems.isEmpty()) return queue;
        State e = open().get(g.getUUID());
        if (e != null && stale(g, e)) { close(g, e, "nothing of it queued and nothing asked for " + (g.tickCount - e.lastAsk) / 20 + " s: a new errand"); e = null; }
        boolean merged = e != null;
        if (e == null) { e = new State(nextId++, g.tickCount); snapshot(g, e); open().put(g.getUUID(), e); last().put(g.getUUID(), e); }
        e.lastAsk = g.tickCount;
        List<Skills.Step> rest = new ArrayList<>(); int at = -1; List<String> took = new ArrayList<>();
        for (Skills.Step s : queue) {
            if (s.skill().equals("give")) {
                Item it = resolve(g, s.target());
                if (it == null) { rest.add(s); ClaudeMod.LOGGER.info("[Claude errand #{}] \"give {}\" names no item: left to the give skill to say so (Errand.take)", e.id, s.target()); continue; }
                if (at < 0) at = rest.size();
                took.add(ask(g, level, e, it, Math.max(1, s.count())));
                continue;
            }
            Item mi = Set.of("obtain", "craft", "smelt", "make_obsidian").contains(s.skill()) ? resolve(g, s.target()) : null;
            if (mi != null && (giveItems.contains(mi) || (proper(level, mi) && giveItems.stream().anyMatch(x -> proper(level, x))) || e.sub.containsKey(mi))) { took.add(s.skill() + " " + s.target() + " " + s.count() + " (the errand works out what to make)"); continue; }
            rest.add(s);
        }
        if (at < 0) return queue;
        if (merged) {
            ClaudeMod.LOGGER.info("[Claude errand #{}] their next ask, merged into the open errand: {} (Errand.take)", e.id, String.join("; ", took));
            onMerge(g, player, level, e);
            return rest.isEmpty() ? null : rest;
        }
        rest.add(at, step(e, "now"));
        ClaudeMod.LOGGER.info("[Claude errand #{}] their ask is an errand: {} (plan_change {}) (Errand.take)", e.id, String.join("; ", took), change);
        return rest;
    }

    /** One give into the errand: food is food (any cooked kind, by the item), a reminder isn't more, and his own only tool is kept. */
    private static String ask(ClaudeEntity g, ServerLevel level, State e, Item it, int n) {
        String nm = Crafting.name(it);
        if (proper(level, it)) {
            if (!e.foodNames.contains(it)) e.foodNames.add(it);
            int owed = e.food - e.foodGiven;
            if (owed > 0 && n <= owed) return "give " + nm + " " + n + " (food: a reminder of the " + owed + " still owed, not more)";
            e.food += n;
            return "give " + nm + " " + n + " (food: " + (e.food - e.foodGiven) + " owed now)";
        }
        if (it == Items.DIAMOND || tier(it) == 3) e.diamondsTheirs = true; // the diamond rule: what they ask of diamonds is theirs
        int owed = want(e, it) - e.given.getOrDefault(it, 0);
        if (owed > 0 && n <= owed) return "give " + nm + " " + n + " (a reminder of the " + owed + " still owed, not more)";
        String c = cls(it);
        if (c != null && it == e.ownBest.get(c) && keep(g, e, it) >= have(g, it)) { // his own best one and his only one: he works with it
            Item stone = stoneOf(it);
            if (e.diamondsTheirs && tier(it) >= 3 && stone != null) { // theirs (the diamond rule), once he has something to work with himself
                e.asked.merge(it, n, Integer::sum); e.self.merge(stone, 1, Integer::sum);
                String why = n + " " + nm + "? after a stone " + c + " for himself (his only " + c + "; diamonds are theirs)";
                e.kept.add(why); ClaudeMod.LOGGER.info("[Claude errand #{}] {} (Errand.ask)", e.id, why);
                return "give " + nm + " " + n + ": " + why;
            }
            if (stone != null && stone != it) {
                e.sub.merge(stone, n, Integer::sum);
                String why = n + " " + nm + "? no (his only " + c + ": making them " + (n == 1 ? "a stone " + c : n + " stone " + c + "s") + " instead)";
                e.kept.add(why); ClaudeMod.LOGGER.info("[Claude errand #{}] {} (Errand.ask)", e.id, why);
                return "give " + nm + " " + n + ": " + why;
            }
        }
        e.asked.merge(it, n, Integer::sum);
        return "give " + nm + " " + n;
    }

    /** A plan step "give" while no ask of theirs is running (the planner carrying on after a lost queue): the open errand's, so the errand runs it. */
    public static String fromPlan(ClaudeEntity g, ServerPlayer player, Skills.Step st) {
        State e = open().get(g.getUUID());
        ClaudeMod.LOGGER.info("[Claude errand #{}] the plan's \"give {} {}\" belongs to the open errand (they asked): the errand runs it (Errand.fromPlan)", e.id, st.target(), st.count());
        if (g.skillQueue.stream().anyMatch(s -> s.skill().equals("errand"))) return "their errand is already queued: on with it";
        return run(g, player, step(e, "now"));
    }

    /** A new ask into the open errand: what's on him for it goes over now if he's by them, its making goes in ahead of the delivery. */
    private static void onMerge(ClaudeEntity g, ServerPlayer player, ServerLevel level, State e) {
        boolean queued = g.skillQueue.stream().anyMatch(s -> s.skill().equals("errand"));
        if (!queued) { g.skillQueue.addFirst(step(e, "now")); ClaudeMod.LOGGER.info("[Claude errand #{}] nothing of it was queued: its step goes first (Errand.onMerge)", e.id); return; }
        boolean makeOn = e.delivery != null && g.skillQueue.contains(e.delivery);
        Plan p = plan(g, level, e, player);
        double d = ClaudeLife.toThem(g, player);
        boolean hand = (!p.give.isEmpty() || !p.chest.isEmpty()) && d <= NEAR;
        if (makeOn) {
            Make m = makes(g, level, e, p);
            log(g, e, p, m, hand ? "now" : "with the delivery", d, "Errand.onMerge");
            speak(player, e, p, m, hand);
            if (!m.steps.isEmpty()) { insertBefore(g, e.delivery, m.steps); e.making.putAll(m.making); e.foodMaking += m.food; }
            if (hand) g.skillQueue.addFirst(step(e, "now"));
        } else { // its step is queued and plans the making when it runs; first in line when there's something to hand over by them
            if (hand && !(g.skillQueue.peekFirst() != null && g.skillQueue.peekFirst().skill().equals("errand"))) g.skillQueue.addFirst(step(e, "now"));
            log(g, e, p, makes(g, level, e, p), hand ? "now" : "when its step runs", d, "Errand.onMerge");
        }
    }

    // ---------------------------------------------------------------- the errand step
    /** The errand's step: hand over what can go now, else make the rest (then one delivery), else close it. */
    public static String run(ClaudeEntity g, ServerPlayer player, Skills.Step st) {
        State e = open().get(g.getUUID());
        if (e == null) return "nothing owed to them now (that errand is closed)";
        ServerLevel level = g.serverLevel();
        boolean delivery = st.target().startsWith("deliver");
        if (delivery || (e.delivery != null && !g.skillQueue.contains(e.delivery))) { e.delivery = null; e.making.clear(); e.foodMaking = 0; } // the make phase is over (or its steps were lost: planned again)
        boolean makeOn = e.delivery != null;
        if (player.isSpectator()) { close(g, e, "they're spectating"); return "done: they're spectating, nothing can be handed over; keeping it"; }
        Plan p = plan(g, level, e, player);
        Make m = makeOn ? new Make(List.of(), List.of(), List.of(), false, Map.of(), 0) : makes(g, level, e, p);
        double d = ClaudeLife.toThem(g, player);
        boolean hand = !p.give.isEmpty() || !p.chest.isEmpty();
        if (hand && e.handTries >= 3 && totalGiven(e) <= e.givenAtHand) { ClaudeMod.LOGGER.info("[Claude errand #{}] three hand-overs and nothing reached them ({} still on him for them): settling (Errand.run)", e.id, nice(p.give)); return finish(g, player, e, p); }
        boolean wait = hand && d > NEAR && !delivery && (makeOn || (!m.steps.isEmpty() && !m.travel)); // far off, the making quick and near him: it all goes in the one delivery
        if (hand && !wait) {
            com.claudemod.entity.life.Whereabouts.lookFor(g, player); // (a look round for them first)
            BlockPos to = ClaudeLife.groundUnder(g, player);
            if (to == null) {
                com.claudemod.entity.life.Whereabouts.askWhere(g, player);
                ClaudeMod.LOGGER.info("[Claude errand #{}] has things to hand over ({}) but doesn't know where they are: asked them (Errand.run)", e.id, nice(p.give));
                return "done: I don't know where they are right now (asked them); I'll hand it over when I see them";
            }
            log(g, e, p, m, delivery ? "the delivery" : "now", d, "Errand.run");
            if (!delivery && !makeOn) speak(player, e, p, m, true);
            Map<Item, Integer> toss = new LinkedHashMap<>(p.give);
            for (Take t : p.chest) { g.tasks.add(new Task.GoTo(t.chest(), 2)); g.tasks.add(new Task.Chest(t.chest(), true, t.item(), t.n())); toss.merge(t.item(), t.n(), Integer::sum); }
            g.tasks.add(new Task.GoTo(to, 3));
            for (var x : toss.entrySet()) { Task.Toss ts = new Task.Toss(x.getKey(), x.getValue()); e.tosses.add(ts); g.tasks.add(ts); }
            int gv = totalGiven(e); if (gv > e.givenAtHand) e.handTries = 0; e.givenAtHand = gv; e.handTries++;
            g.approaching = true;
            e.trips++; if (delivery) e.deliveries++;
            if (!makeOn) g.skillQueue.addFirst(st); // back after the hand-over: the making, or done (mid-making the delivery step does that)
            return (delivery ? "delivering " : "handing them ") + nice(toss) + (p.chest.isEmpty() ? "" : " (from my chest on the way)") + (delivery || makeOn ? "" : m.steps.isEmpty() ? "" : "; making the rest after");
        }
        if (!m.steps.isEmpty() && !makeOn) {
            if (e.rounds >= ROUNDS) { ClaudeMod.LOGGER.info("[Claude errand #{}] {} make rounds already and still short of {}: settling for what's handed over (Errand.run)", e.id, e.rounds, m.words); return finish(g, player, e, p); }
            e.rounds++;
            log(g, e, p, m, wait ? "with the delivery (" + (int) d + " blocks off, the making is quick)" : "nothing now", d, "Errand.run");
            speak(player, e, p, m, false);
            Skills.Step dv = step(e, "deliver"); e.delivery = dv;
            g.skillQueue.addFirst(dv);
            for (int i = m.steps.size() - 1; i >= 0; i--) g.skillQueue.addFirst(m.steps.get(i));
            e.making.putAll(m.making); e.foodMaking += m.food;
            return "making for them: " + String.join(", ", m.words) + "; then one delivery";
        }
        if (makeOn) return "nothing more to hand over before the delivery";
        return finish(g, player, e, p);
    }

    /** Nothing left to hand over or make: closed, and said plainly if something they asked for couldn't be had. */
    private static String finish(ClaudeEntity g, ServerPlayer player, State e, Plan p) {
        List<String> shortOf = new ArrayList<>();
        for (var x : p.left.entrySet()) shortOf.add(x.getValue() + " " + Crafting.name(x.getKey()));
        if (p.foodLeft > 0) shortOf.add(p.foodLeft + " food");
        for (var x : p.give.entrySet()) shortOf.add(x.getValue() + " " + Crafting.name(x.getKey()) + " (couldn't get it to them)");
        close(g, e, shortOf.isEmpty() ? "all handed over" : "short of " + shortOf);
        if (shortOf.isEmpty()) return "gave them everything they asked for: " + given(e);
        ClaudeChat.say(player, "couldn't get you " + String.join(", ", shortOf).replace('_', ' ') + ", sorry. " + (given(e).isEmpty() ? "" : "you've got the rest"), "errand short");
        return "gave them what I could (" + (given(e).isEmpty() ? "nothing" : given(e)) + "); couldn't get " + String.join(", ", shortOf);
    }

    /** The hand-over happened (WorkGoal.doToss): counted toward the errand. */
    public static void tossed(ClaudeEntity g, Item item, int n) {
        State e = open().get(g.getUUID());
        if (e == null || n <= 0) return;
        if (e.food > e.foodGiven && proper(g.serverLevel(), item) && !wants(e).containsKey(item)) e.foodGiven += n;
        else e.given.merge(item, n, Integer::sum);
        ClaudeMod.LOGGER.info("[Claude errand #{}] handed them {} {} (Errand.tossed)", e.id, n, Crafting.name(item));
    }

    /** They said stop: the errand goes with everything else. */
    public static void drop(ClaudeEntity g, String why) { State e = open().get(g.getUUID()); if (e != null) close(g, e, why); }

    /** The errand's steps in his queue that a new job ("replace") keeps: what they asked him to hand over isn't the old job. */
    public static List<Skills.Step> queued(ClaudeEntity g, List<Skills.Step> incoming) {
        List<Skills.Step> out = new ArrayList<>();
        for (Skills.Step s : g.skillQueue) if (s.skill().equals("errand") && !incoming.contains(s)) out.add(s);
        if (!out.isEmpty()) ClaudeMod.LOGGER.info("[Claude errand #{}] a new job from them: the errand's {} step(s) stay ahead of it (what they asked him to hand over) (Errand.queued)", id(g), out.size());
        return out;
    }

    /** A make step of the errand's that came back "failed"/"done:": not the end of the errand (that would clear his queue and the delivery with it). */
    public static String after(ClaudeEntity g, ServerPlayer player, Skills.Step step, String result) {
        State e = open().get(g.getUUID());
        if (e == null || result == null || e.delivery == null || !g.skillQueue.contains(e.delivery)) return result;
        if (!Set.of("obtain", "craft", "smelt", "collect", "strip_mine", "fight", "dig_down", "make_obsidian").contains(step.skill())) return result;
        boolean failed = result.startsWith("failed"), done = result.startsWith("done:");
        if (!failed && !done) return result;
        if (failed) e.failedMakes.add(norm(step.target()));
        ClaudeMod.LOGGER.info("[Claude errand #{}] {} {} for their errand came back \"{}\": {} (Errand.after)", e.id, step.skill(), step.target(), result, failed ? "the delivery still goes, short of it" : "nothing to do there, on with it");
        return "for their errand: " + result.replaceFirst("^(failed|done):\\s*", "") + (failed ? " (the delivery still goes)" : "");
    }

    // ---------------------------------------------------------------- the plan
    record Take(BlockPos chest, Item item, int n) {}
    static final class Plan { final Map<Item, Integer> give = new LinkedHashMap<>(), left = new LinkedHashMap<>(); final List<Take> chest = new ArrayList<>(); final List<String> kept = new ArrayList<>(); int foodLeft; }
    record Make(List<Skills.Step> steps, List<String> words, List<String> say, boolean travel, Map<Item, Integer> making, int food) {}

    /** What goes over now (on him, past what he keeps; from his chests on the way), and what's left to make. */
    static Plan plan(ClaudeEntity g, ServerLevel level, State e, ServerPlayer player) {
        Plan p = new Plan();
        BlockPos them = ClaudeLife.theirSpot(g, player);
        Map<Item, Integer> pend = pending(g, e); int pendFood = 0; // (tosses queued and not done: no second hand-over of the same things)
        for (var x : pend.entrySet()) if (proper(level, x.getKey()) && !wants(e).containsKey(x.getKey())) pendFood += x.getValue();
        for (var w : wants(e).entrySet()) {
            Item it = w.getKey(); int owed = w.getValue() - e.given.getOrDefault(it, 0) - pend.getOrDefault(it, 0);
            if (owed <= 0) continue;
            int on = have(g, it), k = keep(g, e, it), n = Math.min(owed, Math.max(0, on - k));
            if (n > 0) p.give.put(it, n);
            if (n < owed && k > 0 && on > 0) p.kept.add("keeping " + Math.min(on, k) + " " + Crafting.name(it) + " (" + keepWhy(e, it) + ")");
            owed -= n;
            if (owed > 0) owed -= fromChests(g, level, s -> s.is(it), owed, them, p);
            if (owed > 0) p.left.put(it, owed);
        }
        int fo = e.food - e.foodGiven - pendFood;
        if (fo > 0) {
            List<Item> kinds = new ArrayList<>();
            for (Item f : e.foodNames) if (have(g, f) > 0 && !kinds.contains(f)) kinds.add(f);
            List<Item> rest = new ArrayList<>();
            for (int i = 0; i < g.inventory.items.getContainerSize(); i++) { ItemStack s = g.inventory.items.getItem(i); if (!s.isEmpty() && proper(level, s.getItem()) && !kinds.contains(s.getItem()) && !rest.contains(s.getItem())) rest.add(s.getItem()); }
            rest.sort((a, b) -> nutrition(b) - nutrition(a));
            kinds.addAll(rest);
            int total = 0; for (Item f : kinds) total += have(g, f);
            int k = foodKeep(g, level), spare = Math.max(0, total - k);
            for (Item f : kinds) { if (fo <= 0 || spare <= 0) break; int n = Math.min(Math.min(fo, spare), have(g, f)); if (n > 0) { p.give.merge(f, n, Integer::sum); fo -= n; spare -= n; } }
            if (fo > 0 && k > 0 && total > 0) p.kept.add("keeping " + Math.min(total, k) + " food (" + (g.getFoodData().getFoodLevel() < 20 ? "hunger " + g.getFoodData().getFoodLevel() + ": his own next meals" : "nothing on him or in his chests to cook more from") + ")");
            if (fo > 0) fo -= fromChests(g, level, s -> proper(level, s.getItem()), fo, them, p);
            p.foodLeft = Math.max(0, fo);
        }
        return p;
    }

    /** Takes from his chests (his ledger) that are by him, by them, or on the way between: the first trip. */
    private static int fromChests(ClaudeEntity g, ServerLevel level, Predicate<ItemStack> want, int n, BlockPos them, Plan p) {
        int got = 0;
        for (var sp : ChestLedger.where(g, level, want)) {
            if (got >= n) break;
            Integer failed = g.attempts.any.get(sp.pos()); if (failed != null && g.tickCount - failed < 20 * 300) continue; // (one he couldn't get to a moment ago)
            if (!onTheWay(g, sp.pos(), them)) continue;
            int already = 0; for (Take t : p.chest) if (t.chest().equals(sp.pos()) && t.item() == sp.item()) already += t.n();
            int t = Math.min(n - got, sp.count() - already);
            if (t <= 0) continue;
            p.chest.add(new Take(sp.pos(), sp.item(), t)); got += t;
        }
        return got;
    }

    static boolean onTheWay(ClaudeEntity g, BlockPos c, BlockPos them) {
        BlockPos me = g.blockPosition();
        if (c.distSqr(me) <= NEAR * NEAR) return true;
        if (them == null) return false;
        if (c.distSqr(them) <= NEAR * NEAR) return true;
        double ax = me.getX(), ay = me.getY(), az = me.getZ(), bx = them.getX() - ax, by = them.getY() - ay, bz = them.getZ() - az, l2 = bx * bx + by * by + bz * bz;
        double t = l2 == 0 ? 0 : Math.max(0, Math.min(1, ((c.getX() - ax) * bx + (c.getY() - ay) * by + (c.getZ() - az) * bz) / l2));
        double dx = c.getX() - (ax + t * bx), dy = c.getY() - (ay + t * by), dz = c.getZ() - (az + t * bz);
        return dx * dx + dy * dy + dz * dz <= 8 * 8;
    }

    /** What's left, as make steps: crafted (Procure: the nearest table, his own put down if none), cooked from raw (on him first, then his chests), hunted last. */
    static Make makes(ClaudeEntity g, ServerLevel level, State e, Plan p) {
        List<Skills.Step> st = new ArrayList<>(); List<String> words = new ArrayList<>(), say = new ArrayList<>(); boolean travel = false; Map<Item, Integer> mk = new LinkedHashMap<>(); int food = 0;
        Set<Item> items = new LinkedHashSet<>(p.left.keySet());
        for (var s : e.self.entrySet()) if (have(g, s.getKey()) < keep(g, e, s.getKey())) items.add(s.getKey()); // his own replacement (a diamond tool of his promised to them)
        for (Item it : items) {
            String nm = Crafting.name(it);
            if (e.failedMakes.contains(nm)) { words.add(nm + "? couldn't be made (tried)"); continue; }
            int left = p.left.getOrDefault(it, 0), selfShort = Math.max(0, e.self.getOrDefault(it, 0) - have(g, it));
            if (left - e.making.getOrDefault(it, 0) <= 0 && selfShort <= 0) continue;
            int after = have(g, it) - p.give.getOrDefault(it, 0), target = keep(g, e, it) + left; // what he must hold at the delivery
            if (target <= after) continue;
            Procure.Plan pp = Procure.plan(g, level, it, target); // (the total, as the obtain step below asks: the solver counts his pocket itself; target - after took it off twice)
            if (pp.problem() != null) { words.add(nm + "? can't be had yet (" + pp.problem() + ")"); e.failedMakes.add(nm); continue; }
            boolean trip = pp.steps().stream().anyMatch(s -> !Set.of("craft", "smelt").contains(s.skill()));
            travel |= trip;
            st.add(new Skills.Step("obtain", nm, target, "near_you"));
            if (left > 0) mk.put(it, left);
            int under = e.making.getOrDefault(it, 0);
            words.add((Crafting.recipesFor(level, it).isEmpty() ? (pp.steps().stream().anyMatch(s0 -> s0.skill().equals("smelt")) ? "smelt " : "get ") : "craft ") + (under > 0 && left > under ? (left - under) + " more " : (target - after) + " ") + nm + (selfShort > 0 && left == 0 ? " for himself" : "") + (trip ? " (" + Procure.describe(pp.steps()) + ")" : " " + tableNote(g, level)));
            if (left > 0) say.add("making you " + nice(left, it));
            else if (selfShort > 0) say.add("making myself a stone " + cls(it));
        }
        int fneed = p.foodLeft - e.foodMaking;
        if (fneed > 0) {
            Map<Item, Integer> raws = new LinkedHashMap<>();
            for (int i = 0; i < g.inventory.items.getContainerSize(); i++) { ItemStack s = g.inventory.items.getItem(i); if (!s.isEmpty() && raw(level, s)) raws.merge(s.getItem(), s.getCount(), Integer::sum); }
            int cooking = 0; Map<Item, Integer> cookedOut = new HashMap<>(); String rawName = null;
            boolean fuel = g.inventory.count(t -> t.is(Items.COAL) || t.is(Items.CHARCOAL) || t.is(net.minecraft.tags.ItemTags.LOGS) || t.is(net.minecraft.tags.ItemTags.PLANKS)) > 0;
            if (fuel) for (var r : raws.entrySet()) { // all of it (one batch in the furnace; what's over feeds him)
                Item out = cooksInto(level, new ItemStack(r.getKey())); int n = Math.min(64, r.getValue());
                st.add(new Skills.Step("smelt", Crafting.name(out), n, "near_you")); cooking += n; cookedOut.merge(out, n, Integer::sum);
                if (rawName == null) rawName = Crafting.name(r.getKey()).replace("raw_", "");
                words.add("cook " + n + " " + Crafting.name(r.getKey()) + " " + furnaceNote(g, level));
            }
            if (fuel && cooking < fneed) for (var sp : ChestLedger.where(g, level, s -> raw(level, s))) { // raw in his chests: the smelt step fetches it
                if (cooking >= fneed) break;
                Item out = cooksInto(level, new ItemStack(sp.item())); int n = Math.min(sp.count(), fneed - cooking);
                st.add(new Skills.Step("smelt", Crafting.name(out), n, "near_you")); cooking += n; cookedOut.merge(out, n, Integer::sum);
                if (rawName == null) rawName = Crafting.name(sp.item()).replace("raw_", "");
                if (sp.dist() > NEAR) travel = true;
                words.add("cook " + n + " " + Crafting.name(sp.item()) + " from his chest at " + sp.pos().toShortString() + " (" + sp.dist() + " blocks)");
            }
            if (cooking < fneed) {
                int hunt = fneed - cooking;
                st.add(new Skills.Step("obtain", "cooked_beef", have(g, Items.COOKED_BEEF) + cookedOut.getOrDefault(Items.COOKED_BEEF, 0) + hunt, "near_you")); travel = true;
                words.add("hunt and cook " + hunt + " food" + (fuel ? "" : " (no fuel on him to cook the raw)") + (cooking == 0 ? " (nothing raw on him or in his chests)" : ""));
                say.add(cooking > 0 ? "cooking some " + rawName + " and getting more" : "getting you more food");
            } else say.add("cooking some " + rawName);
            food = fneed;
        }
        return new Make(st, words, say, travel, mk, food);
    }

    // ---------------------------------------------------------------- what he keeps
    /** How many of this he keeps for himself: his own best pick and sword (the ones he had when they asked), a few torches. */
    static int keep(ClaudeEntity g, State e, Item it) {
        int k = e.self.getOrDefault(it, 0);
        String c = cls(it);
        if (c != null) {
            if (it != e.ownBest.get(c)) return k; // a spare one, or one made for them
            if (e.diamondsTheirs && tier(it) >= 3) { // promised to them: theirs once he has another to work with
                int others = 0; for (int i = 0; i < g.inventory.items.getContainerSize(); i++) { ItemStack s = g.inventory.items.getItem(i); if (!s.isEmpty() && c.equals(cls(s.getItem())) && s.getItem() != it) others += s.getCount(); }
                return k + (others > 0 ? 0 : 1);
            }
            return k + 1;
        }
        if (it == Items.TORCH) return k + Math.min(TORCH_KEEP, e.start.getOrDefault(Items.TORCH, 0));
        return k;
    }
    private static String keepWhy(State e, Item it) {
        String c = cls(it);
        if (c != null) return e.diamondsTheirs && tier(it) >= 3 ? "his only " + c + " till he has another" : "his own " + c + ", the one he works with";
        if (it == Items.TORCH) return "a few for his own way";
        return "his own";
    }
    /** Food he keeps: his next meals if he's hungry, and two more when there's nothing to cook more from. */
    static int foodKeep(ClaudeEntity g, ServerLevel level) {
        int lvl = g.getFoodData().getFoodLevel();
        Item best = ClaudeLife.foodToEat(g, false);
        int nut = best == null ? 5 : Math.max(1, nutrition(best));
        int now = lvl >= 20 ? 0 : (int) Math.ceil((20 - lvl) / (double) nut);
        return now + (cookSource(g, level) ? 0 : 2);
    }
    static boolean cookSource(ClaudeEntity g, ServerLevel level) {
        boolean fuel = g.inventory.count(t -> t.is(Items.COAL) || t.is(Items.CHARCOAL) || t.is(net.minecraft.tags.ItemTags.LOGS) || t.is(net.minecraft.tags.ItemTags.PLANKS)) > 0;
        if (!fuel) return false;
        if (g.inventory.count(s -> raw(level, s)) > 0) return true;
        for (var sp : ChestLedger.where(g, level, s -> raw(level, s))) if (sp.dist() <= 64) return true;
        return false;
    }
    private static void snapshot(ClaudeEntity g, State e) {
        for (String c : new String[] {"pick", "sword"}) { Item b = best(g, c); if (b != null) e.ownBest.put(c, b); }
        e.start.put(Items.TORCH, have(g, Items.TORCH));
    }

    // ---------------------------------------------------------------- helpers
    static Map<Item, Integer> wants(State e) {
        Map<Item, Integer> w = new LinkedHashMap<>();
        for (var a : e.asked.entrySet()) w.put(a.getKey(), a.getValue());
        for (var s : e.sub.entrySet()) w.merge(s.getKey(), s.getValue(), (a, b) -> Math.max(a, b)); // a stand-in counts toward a later ask for the same thing ("a pick", then "some stone picks")
        return w;
    }
    static int want(State e, Item it) { return wants(e).getOrDefault(it, 0); }
    private static String given(State e) {
        List<String> w = new ArrayList<>();
        for (var x : e.given.entrySet()) w.add(x.getValue() + " " + Crafting.name(x.getKey()));
        if (e.foodGiven > 0) w.add(e.foodGiven + " food");
        return String.join(", ", w);
    }
    private static int totalGiven(State e) { int n = e.foodGiven; for (int v : e.given.values()) n += v; return n; }
    /** His queued hand-overs not done yet, by item (a toss done or dropped is off the list). */
    private static Map<Item, Integer> pending(ClaudeEntity g, State e) {
        e.tosses.removeIf(ts -> g.currentTask != ts && g.tasks.stream().noneMatch(t -> t == ts));
        Map<Item, Integer> m = new HashMap<>();
        for (Task.Toss ts : e.tosses) m.merge(ts.item(), ts.count(), Integer::sum);
        return m;
    }
    private static int owedAll(State e) { int n = Math.max(0, e.food - e.foodGiven); for (var w : wants(e).entrySet()) n += Math.max(0, w.getValue() - e.given.getOrDefault(w.getKey(), 0)); return n; }
    private static boolean stale(ClaudeEntity g, State e) {
        boolean queued = g.skillQueue.stream().anyMatch(s -> s.skill().equals("errand")) || (g.lastStep != null && g.lastStep.skill().equals("errand") && (!g.tasks.isEmpty() || g.currentTask != null));
        return !queued && (owedAll(e) == 0 || g.tickCount - e.lastAsk > 20 * 120);
    }
    private static void close(ClaudeEntity g, State e, String why) {
        open().remove(g.getUUID());
        ClaudeMod.LOGGER.info("[Claude errand #{}] closed: {}; handed them {} in {} trip(s), {} delivery(ies) (Errand.close)", e.id, why, given(e).isEmpty() ? "nothing" : given(e), e.trips, e.deliveries);
    }
    private static Skills.Step step(State e, String kind) { return new Skills.Step("errand", kind + "#" + e.id + "." + (++e.serial), 1, "near_them"); }
    private static void insertBefore(ClaudeEntity g, Skills.Step at, List<Skills.Step> steps) {
        List<Skills.Step> all = new ArrayList<>(g.skillQueue); int i = all.indexOf(at); if (i < 0) i = all.size();
        all.addAll(i, steps); g.skillQueue.clear(); g.skillQueue.addAll(all);
    }

    static boolean proper(ServerLevel level, Item it) { ItemStack s = new ItemStack(it); return s.get(DataComponents.FOOD) != null && !ClaudeLife.rawOrBad(level, s); }
    static boolean raw(ServerLevel level, ItemStack s) {
        return s.get(DataComponents.FOOD) != null && ClaudeLife.rawOrBad(level, s) && !s.is(Items.ROTTEN_FLESH) && !s.is(Items.SPIDER_EYE) && !s.is(Items.POISONOUS_POTATO) && !s.is(Items.PUFFERFISH) && cooksInto(level, s) != null;
    }
    static Item cooksInto(ServerLevel level, ItemStack s) {
        return level.getRecipeManager().getRecipeFor(RecipeType.SMELTING, new SingleRecipeInput(s), level).map(r -> r.value().getResultItem(level.registryAccess()).getItem()).orElse(null);
    }
    static int nutrition(Item it) { var f = new ItemStack(it).get(DataComponents.FOOD); return f == null ? 0 : f.nutrition(); }
    static String cls(Item it) { return it instanceof PickaxeItem ? "pick" : it instanceof SwordItem ? "sword" : null; }
    static int tier(Item it) { String n = Crafting.name(it); return n.startsWith("netherite_") ? 4 : n.startsWith("diamond_") ? 3 : n.startsWith("iron_") ? 2 : n.startsWith("stone_") ? 1 : 0; }
    static Item stoneOf(Item it) { return ClaudeLife.item(Crafting.name(it).replaceFirst("^(wooden|golden|iron|diamond|netherite)_", "stone_")); }
    static int have(ClaudeEntity g, Item it) { return g.inventory.count(s -> s.is(it)); }
    static Item best(ClaudeEntity g, String c) {
        Item b = null;
        for (int i = 0; i < g.inventory.items.getContainerSize(); i++) { ItemStack s = g.inventory.items.getItem(i); if (!s.isEmpty() && c.equals(cls(s.getItem())) && (b == null || tier(s.getItem()) > tier(b))) b = s.getItem(); }
        return b;
    }

    /** Where the crafting happens, said the way the craft step will choose it: the table by him, his nook's, the nearest, or his own put down. */
    static String tableNote(ClaudeEntity g, ServerLevel level) {
        if (ClaudeLife.tableInReach(g, level) != null) return "at the table here";
        if (g.nook != null && level.isLoaded(g.nook.tableCell()) && level.getBlockState(g.nook.tableCell()).is(Blocks.CRAFTING_TABLE) && g.nook.tableCell().distSqr(g.blockPosition()) <= 32 * 32) return "at the nook table (" + (int) Math.sqrt(g.nook.tableCell().distSqr(g.blockPosition())) + " blocks)";
        BlockPos t = ClaudeLife.findNear(level, g.blockPosition(), 32, b -> b.is(Blocks.CRAFTING_TABLE));
        if (t != null) return "at the table at " + t.toShortString() + " (" + (int) Math.sqrt(t.distSqr(g.blockPosition())) + " blocks)";
        return g.inventory.count(s -> s.is(Items.CRAFTING_TABLE) || s.is(net.minecraft.tags.ItemTags.PLANKS) || s.is(net.minecraft.tags.ItemTags.LOGS)) > 0 ? "on a table of his put down where he is" : "once he has a table";
    }
    static String furnaceNote(ClaudeEntity g, ServerLevel level) {
        BlockPos f = ClaudeLife.findNear(level, g.blockPosition(), 32, b -> b.is(Blocks.FURNACE));
        if (f != null) return "at the furnace at " + f.toShortString() + " (" + (int) Math.sqrt(f.distSqr(g.blockPosition())) + " blocks)";
        return "in a furnace of his put down where he is";
    }

    static String nice(int n, Item it) {
        String nm = Crafting.name(it).replace("_pickaxe", "_pick").replace('_', ' ');
        if (n == 1) return (nm.matches("^[aeiou].*") ? "an " : "a ") + nm;
        boolean mass = nm.matches(".*\\b(bread|beef|mutton|chicken|cod|salmon|cobblestone|dirt|sand|gravel|redstone|lapis lazuli|coal|stew)$");
        return n + " " + nm + (mass ? "" : nm.endsWith("ch") || nm.endsWith("sh") || nm.endsWith("s") ? "es" : "s");
    }
    static String nice(Map<Item, Integer> m) { List<String> w = new ArrayList<>(); for (var x : m.entrySet()) w.add(nice(x.getValue(), x.getKey())); return String.join(", ", w); }

    /** The whole plan as one line (logged when it changes). */
    private static void log(ClaudeEntity g, State e, Plan p, Make m, String handWhen, double d, String where) {
        List<String> now = new ArrayList<>();
        for (var x : p.give.entrySet()) now.add(x.getValue() + " " + Crafting.name(x.getKey()));
        for (Take t : p.chest) now.add(t.n() + " " + Crafting.name(t.item()) + " from his chest at " + t.chest().toShortString() + " (on the way)");
        StringBuilder b = new StringBuilder("for them: give ");
        if (now.isEmpty()) b.append("now: nothing on him for it");
        else if (handWhen.startsWith("now")) b.append("now ").append(String.join(", ", now));
        else if (handWhen.equals("the delivery")) b.append("in the one delivery ").append(String.join(", ", now));
        else b.append(String.join(", ", now)).append(" ").append(handWhen);
        List<String> kept = new ArrayList<>(e.kept); kept.addAll(p.kept);
        if (!kept.isEmpty()) b.append("; ").append(String.join("; ", kept));
        List<String> under = new ArrayList<>();
        if (e.delivery != null) { for (var x : e.making.entrySet()) under.add(x.getValue() + " " + Crafting.name(x.getKey())); if (e.foodMaking > 0) under.add(e.foodMaking + " food"); }
        if (!m.words().isEmpty()) b.append("; then ").append(String.join(", ", m.words()));
        if (!under.isEmpty()) b.append(m.words().isEmpty() ? "; under way: " : " (besides ").append(String.join(", ", under)).append(m.words().isEmpty() ? "" : " under way)");
        if (!m.words().isEmpty() || !under.isEmpty()) b.append("; one delivery");
        String body = b.toString();
        if (body.equals(e.lastLine.replaceFirst("^\\[Claude errand #\\d+\\] ", "").replaceFirst(" \\([A-Za-z.]+\\)$", ""))) return;
        e.lastLine = "[Claude errand #" + e.id + "] " + body + " (" + where + ")";
        e.plans++;
        ClaudeMod.LOGGER.info(e.lastLine);
    }

    /** In player words, when the plan holds something they didn't hear yet: what he keeps, or the making ("back in a minute"). */
    private static void speak(ServerPlayer player, State e, Plan p, Make m, boolean handNow) {
        List<String> news = new ArrayList<>();
        for (String k : e.kept) if (!e.said.contains(k)) news.add(k);
        for (String k : p.kept) if (!e.said.contains(k.replaceFirst("^keeping \\d+ ", "keeping "))) news.add(k); // (what he keeps of food or torches, said once)
        boolean makingNews = !m.say().isEmpty() && !e.said.contains("make:" + String.join(",", m.say()));
        if (news.isEmpty() && !makingNews) return;
        List<String> parts = new ArrayList<>();
        if (handNow && !p.give.isEmpty()) parts.add("here's " + nice(p.give) + (m.say().isEmpty() ? "" : " for now"));
        for (String k : news) if (k.contains("? no (his only ")) parts.add("keeping my " + k.replaceFirst("^\\d+ ", "").replaceFirst("\\?.*", "").replace("_pickaxe", " pick").replace('_', ' ') + ", it's my only one");
        else if (k.contains("? after a stone")) parts.add("the " + k.replaceFirst("^\\d+ ", "").replaceFirst("\\?.*", "").replace("_pickaxe", " pick").replace('_', ' ') + "'s yours once i've made myself a stone one");
        else if (k.startsWith("keeping ") && k.contains(" food (hunger")) parts.add("keeping " + (k.startsWith("keeping 1 food") ? "my bit of food" : "some food") + " for myself, i'm hungry");
        else if (k.startsWith("keeping ") && k.contains(" food (")) parts.add("keeping a couple of meals for myself");
        else if (k.startsWith("keeping ") && k.contains(" torch ")) parts.add("keeping a few torches for my own way");
        if (!m.say().isEmpty()) parts.add(String.join(" and ", m.say()) + (m.travel() ? ", back in a bit" : ", back in a minute"));
        for (String k : news) e.said.add(k.replaceFirst("^keeping \\d+ ", "keeping ")); if (makingNews) e.said.add("make:" + String.join(",", m.say()));
        if (!parts.isEmpty()) ClaudeChat.say(player, String.join("; ", parts), "errand #" + e.id);
    }
}
