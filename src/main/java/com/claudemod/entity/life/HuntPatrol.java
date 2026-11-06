package com.claudemod.entity.life;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.claudemod.ClaudeMod;
import com.claudemod.entity.ClaudeEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * The pearl hunt's patrol (the user, 2026-11-01: "explore it while patroling, then intelligently patrol the most optimal area for
 * hunting"). Live 15:23 each re-decision queued a bare explore, its targets flipping (-121,76,-916 then -87,64,-919). His memory, per
 * dimension, on a 16-block grid: when he last walked each cell, and where endermen were seen or fought (a weight that halves every 10
 * minutes). The next patrol point is the best of the seen ground he can stand on 16-32 out in 12 directions, scored by: endermen
 * there or near (the pull), ground walked in the last 2 minutes (the push), the user's biome rules (in the Nether a warped forest; in
 * the overworld open ground over forest and jungle, where the trees block the view), and his heading held (no flip-flopping).
 */
public final class HuntPatrol {
    private HuntPatrol() {}

    static final int CELL = 16;
    /** Endermen seen a minute per 1000 square blocks of warped forest (shortcut: a rough figure, tune it from the pearls lines once hunts log sightings). */
    static final double RATE = 1.0, TYPICAL = 3000, SEARCH_S = 120;
    static final class Book { final Map<Long, Long> walked = new HashMap<>(); final Map<Long, double[]> hunt = new HashMap<>(); Double heading; } // hunt: {weight, server tick it was last added to}
    private static Map<UUID, Map<String, Book>> books; // (static: fields added live start null)
    static Book book(ClaudeEntity g, ServerLevel level) { if (books == null) books = new HashMap<>(); return books.computeIfAbsent(g.getUUID(), k -> new HashMap<>()).computeIfAbsent(PortalMap.dim(level), k -> new Book()); }
    public static void reset(ClaudeEntity g) { if (books != null) books.remove(g.getUUID()); if (picks != null) picks.remove(g.getUUID()); }

    /**
     * The endermen he's gone after and not killed (Day to Day, live 19:29-19:37: an angry one 4 down a pit, in sight; 15 s of swings at
     * air, the watchdog broke the fight off, and the hunt picked the same one again, 8 times in 7 minutes). Picked 3 times in 2 minutes
     * and still alive: let be for 2 minutes, as a player gives up on one he can't get at and looks for another.
     */
    private static Map<UUID, Map<UUID, java.util.ArrayDeque<Long>>> picks;
    private static java.util.ArrayDeque<Long> picksOf(ClaudeEntity g, UUID em) { if (picks == null) picks = new HashMap<>(); return picks.computeIfAbsent(g.getUUID(), k -> new HashMap<>()).computeIfAbsent(em, k -> new java.util.ArrayDeque<>()); }
    static final long PICK_WINDOW = 20L * 120, LET_BE = 20L * 120;
    /** He goes after this one now. */
    public static void picked(ClaudeEntity g, UUID em) { var q = picksOf(g, em); q.addLast(now(g)); while (q.size() > 8) q.pollFirst(); }
    /** Let be: picked 3+ times within 2 minutes, the last of them under 2 minutes ago. */
    public static boolean letBe(ClaudeEntity g, UUID em) {
        if (picks == null || !picks.containsKey(g.getUUID())) return false;
        var q = picks.get(g.getUUID()).get(em); if (q == null || q.size() < 3) return false;
        long now = now(g), last = q.peekLast(); int recent = 0; for (long t : q) if (last - t <= PICK_WINDOW) recent++;
        return recent >= 3 && now - last < LET_BE && now >= last;
    }
    static long cell(int x, int z) { return ((long) Math.floorDiv(x, CELL) << 32) ^ (Math.floorDiv(z, CELL) & 0xffffffffL); }
    static long now(ClaudeEntity g) { return g.getServer() == null ? g.tickCount : g.getServer().getTickCount(); } // (server ticks: his own restart after a portal)

    /** An enderman seen or fought at {@code p}: that ground scores for the hunt. */
    public static void saw(ClaudeEntity g, ServerLevel level, BlockPos p, double w) {
        Book b = book(g, level); long k = cell(p.getX(), p.getZ()); double[] h = b.hunt.computeIfAbsent(k, kk -> new double[] {0, now(g)});
        h[0] = weight(h, now(g)) + w; h[1] = now(g);
    }
    static double weight(double[] h, long now) { double age = Math.max(0, now - h[1]) / (20.0 * 60 * 10); return h[0] * Math.pow(0.5, age); }
    /** Each call: the ground he's on is walked now. */
    public static void walked(ClaudeEntity g, ServerLevel level) { Book b = book(g, level); long t = now(g); b.walked.put(cell(g.getBlockX(), g.getBlockZ()), t);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) { double[] h = b.hunt.get(cell(g.getBlockX() + dx * CELL, g.getBlockZ() + dz * CELL)); if (h != null) { h[0] = weight(h, t) * 0.5; h[1] = t; } } } // ponytail: called only with no enderman in sight: ground seen empty pulls less (the user 20:2x "pacing back and forth in the same area": the kills at -49..-75,-880 kept pulling him back, east-west 8 times in 30 s)

    static int biomeScore(ServerLevel level, BlockPos p) {
        String b = level.getBiome(p).unwrapKey().map(k -> k.location().getPath()).orElse("");
        if (level.dimension() == net.minecraft.world.level.Level.NETHER) return b.equals("warped_forest") ? 3 : -5;
        if (b.contains("plains") || b.contains("desert") || b.contains("savanna") || b.contains("badlands") || b.contains("meadow") || b.contains("snowy_slopes")) return 2;
        if (b.contains("forest") || b.contains("jungle") || b.contains("taiga") || b.contains("swamp")) return -2;
        return 0;
    }

    /** Warped forest he has in view round him, in square blocks: standing ground on a 4-block grid within 40: seen, or within 16 (the ground round him he knows, stems or not). */
    static int forestArea(ClaudeEntity g, ServerLevel level) {
        int n = 0;
        for (int x = -40; x <= 40; x += 4) for (int z = -40; z <= 40; z += 4) {
            if (x * x + z * z > 1600) continue;
            BlockPos p = FortressScout.standAt(level, g.getBlockX() + x, g.getBlockZ() + z, g.getBlockY());
            if (p != null && level.getBiome(p).is(net.minecraft.world.level.biome.Biomes.WARPED_FOREST) && (x * x + z * z <= 256 || FortressScout.seen(g, level, p))) n++;
        }
        return n * 16;
    }
    /** The next patrol point and why, or null (no seen ground to stand on round him). */
    public static BlockPos next(ClaudeEntity g, ServerLevel level, String[] why) {
        walked(g, level);
        Book b = book(g, level); long now = now(g);
        BlockPos best = null; double bs = -1e9, bestA = 0; String bwhy = "";
        boolean inForest = false; // in a warped forest or by its edge (live 11:01:22: 2 out of it, his feet read the wastes and he left for a leg 26 off)
        if (level.dimension() == net.minecraft.world.level.Level.NETHER) for (int k = 0; k <= 16 && !inForest; k++) { BlockPos q = k == 0 ? g.blockPosition() : g.blockPosition().offset((int) Math.round(Math.cos(k * Math.PI / 4) * (k <= 8 ? 3 : 6)), 0, (int) Math.round(Math.sin(k * Math.PI / 4) * (k <= 8 ? 3 : 6))); inForest = level.getBiome(q).is(net.minecraft.world.level.biome.Biomes.WARPED_FOREST); }
        if (inForest && Atlas.quietAt(g, level, g.blockPosition())) inForest = false; // (found quiet: no more patrolling it)
        boolean anyHunt = false; for (double[] h : b.hunt.values()) if (weight(h, now) > 0.05) anyHunt = true;
        if (inForest && !anyHunt) { // the user 11-05: he knows the forest's size and its odds, and goes elsewhere only when that's the better bet
            int area = forestArea(g, level); double here = 60 * 1000 / (RATE * Math.max(16, area));
            BlockPos other = Atlas.findAway(g, level, "warped_forest", 64);
            double there = (other == null ? SEARCH_S : FortressScout.BORE_S * (Math.abs(other.getX() - g.getBlockX()) + Math.abs(other.getZ() - g.getBlockZ()))) + 60 * 1000 / (RATE * TYPICAL);
            String odds = String.format("this warped forest is about %d square blocks (seen), an enderman every ~%.0f s here; %s ~%.0f s", area, here, other == null ? "finding another and one there:" : "the one at " + other.toShortString() + " and one there:", there);
            if (here > there) { Atlas.quiet(g, level, g.blockPosition()); why[0] = odds + ": on to another"; ClaudeMod.LOGGER.info("[Claude skill] ender_pearls patrol: {} (HuntPatrol.next)", why[0]); return null; }
            if (g.tickCount % 200 < 40) ClaudeMod.LOGGER.info("[Claude skill] ender_pearls patrol: {}: staying (HuntPatrol.next)", odds);
        }
        int[] ds = inForest ? new int[] {12, 8, 16, 24} : new int[] {24, 16, 32, 48}; // (in a warped forest, where they spawn: short hops inside it; its stems hide all past 12, and the far legs it saw were the wastes beyond, live 08:14:52, review-111)
        for (int i = 0; i < 12; i++) {
            double a = i * Math.PI / 6;
            for (int d : ds) {
                BlockPos p = FortressScout.alongRay(g, level, a, d); if (p == null) continue;
                double score = 0; StringBuilder w = new StringBuilder();
                double hunt = 0; for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) { double[] h = b.hunt.get(cell(p.getX() + dx * CELL, p.getZ() + dz * CELL)); if (h != null) hunt += weight(h, now) * (dx == 0 && dz == 0 ? 1.0 : 0.4); }
                Long last = b.walked.get(cell(p.getX(), p.getZ())); boolean fresh = last != null && now - last < 20 * 120 && now >= last;
                if (hunt > 0.05 && !fresh) { score += 4 * hunt; w.append(String.format("endermen there %.1f; ", hunt)); } // ponytail: ground he walked in the last 2 min was seen empty, so old sightings there don't pull him back (the -44/-70 pacing)
                if (fresh) { score -= 4; w.append("walked ").append((now - last) / 20).append(" s ago; "); }
                int lv = lavaNear(level, p); if (lv > 0) { score -= lv * w("pearls.spot_lava", 0.5); w.append(lv).append(" lava by it; "); } // (footing: a spot by lava is a poor place to fight an enderman, D2D 01:0x "right next to the lava")
                int bio = biomeScore(level, p); score += bio; if (bio != 0) w.append(level.getBiome(p).unwrapKey().map(k -> k.location().getPath()).orElse("?")).append(' ').append(bio > 0 ? "+" : "").append(bio).append("; ");
                if (b.heading != null) { double turn = Math.cos(a - b.heading); score += 1.5 * turn; if (turn > 0.7) w.append("on his heading; "); }
                score -= Math.abs(d - ds[0]) * 0.02;
                if (score > bs) { bs = score; best = p; bestA = a; bwhy = w + String.format("score %.1f", score); }
                if (!fresh) break; // (the first standable spot along this way; ponytail: a just-walked one looks further out instead, live 20:5x a N-S corridor, both nearest ends walked, he flipped between them)
            }
        }
        if (inForest && !bwhy.contains("endermen there") && (best == null || !level.getBiome(best).is(net.minecraft.world.level.biome.Biomes.WARPED_FOREST))) { // all of the forest walked in the last 2 min: back through it, the oldest ground first; they keep spawning here, the wastes round it have none (live 09:12-09:18, out to the wastes and sent back, round and round)
            BlockPos f = null; double fs = -1e9, fa = 0;
            for (int i = 0; i < 12; i++) { double a = i * Math.PI / 6;
                for (int d : new int[] {8, 12, 16}) {
                    BlockPos p = FortressScout.standAt(level, g.getBlockX() + (int) Math.round(Math.cos(a) * d), g.getBlockZ() + (int) Math.round(Math.sin(a) * d), g.getBlockY());
                    if (p == null || !level.getBiome(p).is(net.minecraft.world.level.biome.Biomes.WARPED_FOREST) || !FortressScout.seen(g, level, p)) continue;
                    Long last = b.walked.get(cell(p.getX(), p.getZ())); double sc = (last == null ? 20 * 120 : Math.min(20 * 120, now - last)) / 20.0 + (b.heading == null ? 0 : 10 * Math.cos(a - b.heading));
                    if (sc > fs) { fs = sc; f = p; fa = a; }
                }
            }
            if (f != null) { best = f; bestA = fa; bwhy = "warped_forest, all of it walked in the last 2 min: back through it, its oldest ground first (the wastes round it have no endermen)"; }
        }
        if (best == null) { why[0] = "no ground in sight to walk to"; return null; }
        if (level.dimension() == net.minecraft.world.level.Level.NETHER && bs < 0 && !level.getBiome(best).is(net.minecraft.world.level.biome.Biomes.WARPED_FOREST)) { // (review-185/191: walked to wastes it scored -3.7 and -4.3, found nothing, turned back)
            why[0] = "nothing worth walking to round here (best " + best.toShortString() + ": " + bwhy + ")";
            ClaudeMod.LOGGER.info("[Claude skill] ender_pearls patrol: {} (HuntPatrol.next)", why[0]); return null;
        }
        b.heading = bestA;
        why[0] = "heading " + Perception.compassOf(new net.minecraft.world.phys.Vec3(Math.cos(bestA), 0, Math.sin(bestA))) + " to " + best.toShortString() + ": " + bwhy;
        ClaudeMod.LOGGER.info("[Claude skill] ender_pearls patrol: {} ({} cells walked, {} with endermen on record) (HuntPatrol.next)", why[0], b.walked.size(), b.hunt.size());
        return best;
    }

    // ---- the pearl hunt's next move, one priced choice (the user 01:19 11-06: "the pearl step should have engage enderman weighted higher than explore, and if there are no endermen nearby then patrol known forests and explore for new forests should be next respectively. a good forest should be expansive though") ----
    private static double w(String n, double d) { return Weights.w(n, d); }
    /** What the round does: "engage" (em, its believed spot), "patrol" (the forest he's in), "go" (to another forest he knows), "explore" (find a new one). */
    public record Next(String kind, net.minecraft.world.entity.monster.EnderMan em, BlockPos to, String why) {}
    private static Map<UUID, Object[]> trips; // his last "go": {spot, server tick, start distance}; a go that didn't get him there counts against that forest
    private static Map<UUID, Map<Long, Integer>> tripFails;
    static Map<Long, Integer> fails(ClaudeEntity g) { if (tripFails == null) tripFails = new HashMap<>(); return tripFails.computeIfAbsent(g.getUUID(), k -> new HashMap<>()); }
    /** Seconds till an enderman turns up in a warped forest of that area (square blocks). */
    static double waitS(double area) { return 60 * 1000 / (RATE * Math.max(16, area)); }
    /** A warped forest he knows of, sized by the spots of it on his atlas round it (they're 48 apart: more of them, the bigger it is). */
    static double knownArea(java.util.List<BlockPos> spots, BlockPos at) { int n = 0; for (BlockPos q : spots) if (q.distSqr(at) <= 96 * 96) n++; return Math.max(TYPICAL, n * w("pearls.area_per_spot", 2000)); }
    /** Lava within 2 of a spot (cells): a fight or a stand there risks a knock into it. */
    static int lavaNear(ServerLevel level, BlockPos p) { int n = 0; for (BlockPos q : BlockPos.betweenClosed(p.offset(-2, -2, -2), p.offset(2, 1, 2))) if (level.getFluidState(q).is(net.minecraft.tags.FluidTags.LAVA)) n++; return n; }

    /** His melee: {damage a swing, seconds a swing} with the best sword on him (a fist: 1 a swing). */
    static double[] melee(ClaudeEntity g) {
        double dmg = 1; for (int i = 0; i < g.inventory.items.getContainerSize(); i++) { var it = g.inventory.items.getItem(i);
            double d = it.is(net.minecraft.world.item.Items.NETHERITE_SWORD) ? 8 : it.is(net.minecraft.world.item.Items.DIAMOND_SWORD) ? 7 : it.is(net.minecraft.world.item.Items.IRON_SWORD) ? 6 : it.is(net.minecraft.world.item.Items.STONE_SWORD) ? 5 : it.is(net.minecraft.world.item.Items.WOODEN_SWORD) || it.is(net.minecraft.world.item.Items.GOLDEN_SWORD) ? 4 : 1; dmg = Math.max(dmg, d); }
        return new double[] {dmg, dmg > 1 ? 0.625 : 0.25};
    }
    /**
     * An enderman fight, in seconds of his time (D2D 01:57 World 17: unarmored, 19 HP, no food, an iron sword: 7 swings, ~4 of its
     * 7-HP hits, 17 -> 9 in 10 s; it was priced as 1.5 HP). Its 40 HP at his swing's damage is the fight's length; it lands about 0.4 hits a
     * second on a swordsman (knockback keeps it off him half the time), 7 a hit (Normal) through his armor; hpS weighs that HP at his health and food.
     * At the given health and armor factor (a "ready" what-if), or his own.
     */
    public static double fightS(ClaudeEntity g, double health, double armor, double tough, boolean fed) {
        double[] m = melee(g); double swings = Math.ceil(w("pearls.enderman_hp", 40) / m[0]), len = swings * m[1];
        double dmg = w("pearls.enderman_hit_hp", 7), perHit = dmg * (1 - Math.min(20, Math.max(armor / 5, armor - dmg / (2 + tough / 4))) / 25); // (vanilla's armor sum: armor 14 takes a 7 to ~4)
        double hits = len * w("pearls.enderman_hits_per_s", 0.4), hp = hits * perHit;
        double s0 = len + w("pearls.fight_s", 4) + Decide.hpS(g, health, 0, hp, perHit, (int) Math.ceil(len) + 1, fed ? 20 : g.getFoodData().getFoodLevel(), fed ? w("food.fed_saturation", 5) : g.getFoodData().getSaturationLevel());
        return s0;
    }
    /** At his own health, armor and food. */
    public static double fightS(ClaudeEntity g) { return fightS(g, g.getHealth(), g.getArmorValue(), g.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR_TOUGHNESS), false); }

    public static Next decide(ClaudeEntity g, ServerLevel level, boolean nether) {
        java.util.List<Decide.Option> opts = new ArrayList<>(); Map<String, Next> acts = new HashMap<>();
        double sprint = Decide.sprint(), road = nether ? PortalMap.NETHER_OFF_ROAD : 1.2;
        // a go last round that didn't get him there: against that forest from now on (D2D: the 'there' leg priced ~50 s failed across a lava lake again and again)
        if (trips != null && trips.get(g.getUUID()) instanceof Object[] tr) { BlockPos to = (BlockPos) tr[0]; double d0 = (double) tr[2];
            if (g.blockPosition().distSqr(to) > 24 * 24 && Math.sqrt(g.blockPosition().distSqr(to)) > d0 - 16) { int f = fails(g).merge(to.asLong(), 1, Integer::sum); ClaudeMod.LOGGER.info("[Claude skill] ender_pearls: the trip to the forest at {} got nowhere ({} off, was {}): {} failed trip(s) there on record (HuntPatrol.decide)", to.toShortString(), (int) Math.sqrt(g.blockPosition().distSqr(to)), (int) d0, f); }
            trips.remove(g.getUUID()); }
        double fight = fightS(g); // (every way to a pearl ends in this fight: priced on each option, the same at his gear)
        boolean sword = g.inventory.count(x -> x.is(net.minecraft.world.item.Items.IRON_SWORD) || x.is(net.minecraft.world.item.Items.DIAMOND_SWORD) || x.is(net.minecraft.world.item.Items.NETHERITE_SWORD)) > 0;
        // 1. engage: each enderman he sees or heard within 48, not one he's let be (his armor and health in hpS, his sword in the fight's length)
        for (var m : Perception.known(g, net.minecraft.world.entity.monster.EnderMan.class, g.getBoundingBox().inflate(48, 16, 48), x -> x.isAlive())) {
            if (letBe(g, m.getUUID())) continue;
            var at = Perception.believedAt(g, m); if (at == null) continue;
            boolean sees = Perception.seesNow(g, m); BlockPos ap = BlockPos.containing(at); double d = at.distanceTo(g.position());
            int lava = lavaNear(level, ap); double dy = Math.abs(ap.getY() - g.getBlockY());
            double s = d * road / sprint + fight + lava * w("pearls.lava_near_s", 8) + dy * w("pearls.climb_s", 3) + (sees ? 0 : w("pearls.heard_s", 15));
            String n = "enderman " + (int) d + " off" + (sees ? "" : " (heard)") + " #" + acts.size();
            opts.add(Decide.Option.of(n, s, (sees ? "in sight" : "heard about " + ap.toShortString()) + (lava > 0 ? ", " + lava + " lava by it" : "") + (dy > 2 ? ", " + (int) dy + " up/down" : "") + (sword ? "" : ", no iron+ sword") + ", fight ~" + Math.round(fight) + " s at " + (int) g.getHealth() + " HP, armor " + g.getArmorValue() + ", food " + g.getFoodData().getFoodLevel()));
            acts.put(n, new Next("engage", m, ap, n));
        }
        // 2. patrol: the forest he's in, then each one on his atlas, by size (bigger: an enderman sooner), the trip, its failed trips
        String here = level.getBiome(g.blockPosition()).unwrapKey().map(k -> k.location().getPath()).orElse("");
        if (here.equals("warped_forest")) { boolean quiet = Atlas.quietAt(g, level, g.blockPosition()); double area = forestArea(g, level), s = waitS(area) * (quiet ? w("pearls.quiet_factor", 3) : 1) + fight;
            opts.add(Decide.Option.of("patrol this forest", s, "about " + (int) area + " square blocks seen" + (quiet ? ", judged quiet" : "")));
            acts.put("patrol this forest", new Next("patrol", null, null, "this forest, ~" + (int) area + " square blocks")); }
        var spots = Atlas.spots(g, level, "warped_forest");
        java.util.List<BlockPos> far = new ArrayList<>(); for (BlockPos q : spots) if (q.distSqr(g.blockPosition()) > 48 * 48) { boolean dup = false; for (BlockPos f0 : far) if (f0.distSqr(q) <= 96 * 96) dup = true; if (!dup) far.add(q); }
        for (BlockPos q : far) { double d = Math.sqrt(q.distSqr(g.blockPosition())), area = knownArea(spots, q); int f = fails(g).getOrDefault(q.asLong(), 0); boolean quiet = Atlas.quietAt(g, level, q);
            double s = d * road / sprint + f * w("pearls.trip_fail_s", 120) + waitS(area) * (quiet ? w("pearls.quiet_factor", 3) : 1) + fight;
            String n = "the forest at " + q.toShortString();
            opts.add(Decide.Option.of(n, s, (int) d + " off, ~" + (int) area + " square blocks" + (f > 0 ? ", " + f + " failed trip(s)" : "") + (quiet ? ", judged quiet" : "")));
            acts.put(n, new Next("go", null, q, n)); }
        // 3. explore for a new one, last
        opts.add(Decide.Option.of("explore for a new forest", w("pearls.search_s", SEARCH_S) + waitS(TYPICAL) + fight, "a search, then a typical forest"));
        // 4. get ready first: full health, fed, a chestplate's worth of armor (gear_up's kit), then the same fight at that (the user's rule: priced, never a gate)
        { Object[] lr = readied == null ? null : readied.get(g.getUUID()); // (got ready a while ago and his armor's no better: the kit can't give armor now, only health and food)
          boolean noArmorToHave = lr != null && now(g) - (long) lr[0] < 20 * w("pearls.ready_again_s", 1200) && g.getArmorValue() <= (int) lr[1];
          double readyArmor = noArmorToHave ? g.getArmorValue() : Math.max(g.getArmorValue(), w("pearls.ready_armor", 14)), readyFight = fightS(g, 20, readyArmor, g.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR_TOUGHNESS), true);
          var portal = nether ? PortalMap.net(g).portals.stream().filter(p0 -> PortalMap.NETHER.equals(p0.dim)).min(java.util.Comparator.comparingDouble(p0 -> p0.at.distSqr(g.blockPosition()))).orElse(null) : null;
          double trip = nether ? (portal == null ? w("pearls.no_portal_s", 1800) : 2 * (Math.sqrt(portal.at.distSqr(g.blockPosition())) * road / sprint + w("highway.portal_s", 8))) : 0, cost = w("pearls.ready_s", 240) + trip;
          opts.add(Decide.Option.of("get ready first", cost + readyFight, (nether ? "through the portal and back (~" + Math.round(trip) + " s), " : "") + "gear_up's kit (~" + Math.round(w("pearls.ready_s", 240)) + " s: food, armor), then the fight ~" + Math.round(readyFight) + " s at 20 HP, armor " + (int) readyArmor + (noArmorToHave ? " (no armor came of the last go)" : "")));
          acts.put("get ready first", new Next("ready", null, null, "the kit first")); }
        acts.put("explore for a new forest", new Next("explore", null, null, "a new forest"));
        var pick = Decide.choose(g, "pearls.next", (here.isEmpty() ? "" : here + ", ") + g.blockPosition().toShortString(), true, opts);
        Next nx = acts.get(pick.name());
        if ("go".equals(nx.kind()) && acts.containsKey("patrol this forest")) Atlas.quiet(g, level, g.blockPosition()); // (the one he's in judged the poorer bet: priced as such from now on)
        return nx;
    }

    private static Map<UUID, Object[]> readied; // his last "get ready first": {server tick, armor then}
    /** He goes to get ready: noted, so a kit that gave no armor isn't promised again. */
    public static void readying(ClaudeEntity g) { if (readied == null) readied = new HashMap<>(); readied.put(g.getUUID(), new Object[] {now(g), g.getArmorValue()}); }
    /** He sets off for that forest: next round, not there and no nearer is a failed trip against it. */
    public static void going(ClaudeEntity g, BlockPos to) { if (trips == null) trips = new HashMap<>(); trips.put(g.getUUID(), new Object[] {to.immutable(), now(g), Math.sqrt(to.distSqr(g.blockPosition()))}); }
    /** Each tick: on a hunt (ender_pearls in his plan), a walk with an enderman coming into view stops, so the hunt weighs it (the user: "engage enderman weighted higher than explore"; live 00:57-01:18 explore walked him off with 5 within 48). */
    public static void tick(ClaudeEntity g) {
        if (g.tickCount % 10 != 0 || g.tasks.isEmpty() && g.currentTask == null || !(g.level() instanceof ServerLevel level)) return; // (the walk running is current, off the list)
        boolean hunting = false; for (var s0 : g.skillQueue) if ("ender_pearls".equals(s0.skill())) hunting = true; if (!hunting) return;
        for (var t : g.tasks) if (!(t instanceof Task.GoTo) && !(t instanceof Task.Bore)) return; // (only walks: a fight, a pickup or a dig runs on)
        if (g.currentTask != null && !(g.currentTask instanceof Task.GoTo) && !(g.currentTask instanceof Task.Bore)) return;
        for (var m : Perception.known(g, net.minecraft.world.entity.monster.EnderMan.class, g.getBoundingBox().inflate(32, 12, 32), x -> x.isAlive()))
            if (Perception.seesNow(g, m) && !letBe(g, m.getUUID())) {
                ClaudeMod.LOGGER.info("[Claude skill] ender_pearls: an enderman {} off came into view on the walk: the walk stops, the hunt weighs it (HuntPatrol.tick)", (int) m.distanceTo(g));
                g.tasks.clear(); g.dropCurrent = true;
                while (!g.skillQueue.isEmpty() && !"ender_pearls".equals(g.skillQueue.peekFirst().skill())) ClaudeMod.LOGGER.info("[Claude skill] ender_pearls: {} dropped for the enderman in view (HuntPatrol.tick)", g.skillQueue.pollFirst()); // (explore re-queues its next leg ahead of the hunt: it would walk him off again)
                return; }
    }
}
