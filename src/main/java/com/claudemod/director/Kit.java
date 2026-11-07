package com.claudemod.director;

import java.util.LinkedHashMap;
import java.util.Map;

import com.claudemod.entity.ClaudeEntity;
import com.claudemod.entity.life.Crafting;

import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SwordItem;

/**
 * What a careful player carries before going underground: a full set of iron armor, an iron sword, a shield and a stack
 * of food. Says what's still missing; {@link Procure} works out the trip that gets it. The basic kit (any sword, food) is
 * enough for digging a mine, which is where the iron for the rest comes from.
 */
public final class Kit {
    private Kit() {}

    static final Item[] IRON_ARMOR = {Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS};

    /**
     * Good food on him (nothing raw, rotten or poisonous), in meals by food value: nutrition + saturation over food.meal_points
     * (a cooked steak's 20.8), the same points the kit's food is priced in. Was a count of items: 8 dried kelp read as 8 meals
     * and "6+ meals down the mine: food waits" let him work on with nothing (audit "the food targets disagree", stage 843).
     */
    public static int food(ClaudeEntity g) {
        ServerLevel level = (ServerLevel) g.level(); double p = 0;
        for (int k = 0; k < g.inventory.items.getContainerSize(); k++) { ItemStack st = g.inventory.items.getItem(k); if (st.has(DataComponents.FOOD) && !ClaudeLife.rawOrBad(level, st)) p += pts(st.getItem()) * st.getCount(); }
        return (int) Math.round(p / w("food.meal_points", 20.8));
    }

    /** The cave kit is the overworld's (its iron, its cows, his chests): through the portal on his own run with 4+ meals on him, it's behind him till he's home, nothing of it to do where he is. What gear_up, the dragon roadmap and the rules all go by. */
    public static boolean behind(ClaudeEntity g) {
        return g.level().dimension() != net.minecraft.world.level.Level.OVERWORLD && !g.commandActive && food(g) >= 4;
    }

    /** Wearing something at least as good, or carrying the piece itself (it goes on at the next armor check). */
    static boolean has(ClaudeEntity g, Item armor) {
        ArmorItem a = (ArmorItem) armor;
        ItemStack worn = g.getItemBySlot(a.getEquipmentSlot());
        if (worn.is(armor) || g.inventory.count(s -> s.is(armor)) > 0) return true;
        if (java.util.Set.of(Items.GOLDEN_HELMET, Items.GOLDEN_CHESTPLATE, Items.GOLDEN_LEGGINGS, Items.GOLDEN_BOOTS).contains(armor)) return false; /* gold is wanted for itself (piglins), no better piece covers it: diamond boots on him and "already wearing something as good as golden_boots" */
        return worn.getItem() instanceof ArmorItem w && w.getDefense() >= a.getDefense()
            || g.inventory.count(s -> s.getItem() instanceof ArmorItem c && c.getEquipmentSlot() == a.getEquipmentSlot() && c.getDefense() >= a.getDefense()) > 0; // (a better one in his bag too: the diamond chestplate just crafted, not on yet, and obtain iron_chestplate planned 8 iron, live 17:24 11-04)
    }

    /** Past the iron stage: any diamond armor or a diamond pickaxe on him, or the full iron kit (four iron pieces, iron pick, iron sword). */
    public static boolean diamondStage(ClaudeEntity g) {
        for (Item a : new Item[] {Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS}) if (g.getItemBySlot(((ArmorItem) a).getEquipmentSlot()).is(a) || g.inventory.count(s -> s.is(a)) > 0) return true;
        if (g.inventory.count(s -> s.is(Items.DIAMOND_PICKAXE)) > 0) return true;
        // the user: the diamond stage starts with the full iron kit (all four iron pieces, an iron pick and an iron sword), not with a pick alone or a deep mine
        if (g.inventory.count(s -> s.is(Items.IRON_PICKAXE)) > 0 && sword(g, true) && has(g, Items.IRON_HELMET) && has(g, Items.IRON_CHESTPLATE) && has(g, Items.IRON_LEGGINGS) && has(g, Items.IRON_BOOTS)) return true;
        return false;
    }

    /** His diamonds (on him and in his chests; a diamond pick's 3 kept back till he has one) cover the diamond form of this iron piece or sword. */
    public static boolean diamondsCover(ClaudeEntity g, Item iron) {
        int cost = iron == Items.IRON_CHESTPLATE ? 8 : iron == Items.IRON_LEGGINGS ? 7 : iron == Items.IRON_HELMET ? 5 : iron == Items.IRON_BOOTS ? 4 : iron == Items.IRON_SWORD ? 2 : Integer.MAX_VALUE;
        int diamonds = g.inventory.count(s -> s.is(Items.DIAMOND)) + (g.level() instanceof ServerLevel sl ? ClaudeLife.stored(g, sl).getOrDefault(Items.DIAMOND, 0) : 0);
        if (g.inventory.count(st -> st.is(Items.DIAMOND_PICKAXE)) == 0) diamonds -= 3;
        return diamonds >= cost;
    }

    static boolean sword(ClaudeEntity g, boolean iron) {
        return g.inventory.count(s -> s.getItem() instanceof SwordItem && (!iron || s.is(Items.IRON_SWORD) || s.is(Items.DIAMOND_SWORD) || s.is(Items.NETHERITE_SWORD))) > 0;
    }

    static boolean shield(ClaudeEntity g) {
        return g.getItemBySlot(EquipmentSlot.OFFHAND).is(Items.SHIELD) || g.inventory.count(s -> s.is(Items.SHIELD)) > 0;
    }

    /** A short walk for string (by route): his chest that near is on the way; farther, only when the bow is the dragon run's next must. */
    public static final int STRING_WALK = 48;
    /** The bow's string to hand: 3 on him, or with what's in his chests within a short walk (by route). */
    public static boolean bowCheap(ClaudeEntity g) {
        int on = g.inventory.count(s -> s.is(Items.STRING));
        if (on >= 3) return true;
        if (!(g.level() instanceof ServerLevel sl)) return false;
        for (var sp : com.claudemod.entity.life.ChestLedger.where(g, sl, s -> s.is(Items.STRING))) if (sp.dist() <= STRING_WALK) on += sp.count();
        return on >= 3;
    }

    /** The Nether stage is behind him (6 blaze rods or their powder, or an eye of ender): the dragon kit (full diamond) applies; before it, the Nether-trip kit (iron or better, a diamond pick). The user, 2026-10-31: "iron or better is fine ... he needs a diamond pick ... for the dragon he should have full diamond". */
    public static boolean netherDone(ClaudeEntity g) {
        return g.inventory.count(s -> s.is(Items.BLAZE_ROD)) >= 6 || g.inventory.count(s -> s.is(Items.BLAZE_POWDER)) >= 12 || g.inventory.count(s -> s.is(Items.ENDER_EYE)) > 0 || g.level().dimension() == net.minecraft.world.level.Level.END;
    }
    /** What the kit still lacks (item -> count), in the order he'd make it. full: the whole cave kit; else just a sword and food. */
    // (the Nether trip's diamond pick is for a portal's obsidian: with a lit portal on record it isn't in that kit; the user, 14:06 World 15: "he already had the portal so that's not the right reasoning to mine for diamonds")
    public static Map<Item, Integer> missing(ClaudeEntity g, boolean full) {
        // the first-day order every player knows: food (and wool for a bed, off the same animals) while it's light, a stone sword to carry,
        // a bed by nightfall, and only then the iron for the armor, sword and shield
        Map<Item, Integer> m = new LinkedHashMap<>();
        boolean noBed = g.inventory.count(s -> s.is(net.minecraft.tags.ItemTags.BEDS)) == 0 && g.getRespawnPosition() == null; // a bed he's slept in (his spawn point) is a bed, in his pocket or not (he left the mine for wool with a bed standing at its foot)
        if (noBed && woolForBed(g) != null) m.put(bedChoice(g), 1); // the wool's already in his pocket: one craft, done first
        boolean nookBed = !noBed && g.inventory.count(s -> s.is(net.minecraft.tags.ItemTags.BEDS)) == 0 && ClaudeLife.nookWantsBed(g) != null; // a nook of his with no bed: one bed per nook (3 wool of a colour + 3 planks), planned like his first
        if (nookBed && woolForBed(g) != null) m.put(bedChoice(g), 1);
        { FoodAsk fa = foodAsk(g); if (fa != null) m.put(fa.item(), fa.count()); } // (the food: how much the trip takes, from where it's cheapest, priced: Kit.foodAsk)
        boolean sheepAbout = !g.level().getEntitiesOfClass(Sheep.class, g.getBoundingBox().inflate(48)).isEmpty();
        if ((noBed || nookBed) && sheepAbout && !m.containsKey(bedChoice(g))) m.put(bedChoice(g), 1); // sleeping keeps the phantoms off (3 wool off sheep, 3 planks); no sheep about: the bed waits for the next flock he passes, never a 350-block trek for wool on day one
        if (!sword(g, false)) m.put(Items.STONE_SWORD, 1); // something to fight with from the first evening
        int string = g.inventory.count(s -> s.is(Items.STRING)), flint = g.inventory.count(s -> s.is(Items.FLINT)), feathers = g.inventory.count(s -> s.is(Items.FEATHER));
        if (g.inventory.count(s -> s.is(Items.BOW)) == 0 && string >= 3) m.put(Items.BOW, 1); // a bow: skeletons and creepers dealt with from where they can't hit back
        if (g.inventory.count(s -> s.is(Items.BOW)) > 0 && g.inventory.count(s -> s.is(Items.ARROW)) < 8 && flint > 0 && feathers > 0) m.put(Items.ARROW, Math.min(16, 4 * Math.min(flint, feathers)));
        int coal = g.inventory.count(s -> s.is(Items.COAL) || s.is(Items.CHARCOAL)), torches = g.inventory.count(s -> s.is(Items.TORCH));
        int logs = 0; for (int i = 0; i < g.inventory.items.getContainerSize(); i++) if (g.inventory.items.getItem(i).is(net.minecraft.tags.ItemTags.LOGS)) logs += g.inventory.items.getItem(i).getCount();
        boolean netherLeg = g.playerGoal != null && g.playerGoal.toLowerCase().contains("dragon") && ClaudeLife.hisPortal.get(g.getUUID()) != null; // the user: "he doesnt really need coal for the nether": once his portal stands, the dragon run's trips are Nether trips, no mining top-up of coal or torches (live 11:03: a 32-coal charcoal grind kept him off his fortress)
        if (coal <= 8 && !netherLeg) { // (the Nether and the End need no torches, the user 11-05: the dragon run's kit has no light in it; a mining trip on the way packs its own torches, packForTrip) // (the dragon run waives the 32-coal top-up, never the light: at y-58 for the diamond helmet he mined 21 torches down to 0 and kept going dark, live 23:19) // the user: half a stack of coal/charcoal (mixed) in the mining kit, topped back up to 32 once he's down to 8 (furnace fuel, torches on the go)
            int want = 32 - coal, spareLogs = logs - 40; // (8 of them kept for the planks the kit itself crafts: a table, a furnace, sticks; spare logs into charcoal left the crafts eating the trip logs, and he went up for wood in a loop) (the kit's own 32 logs stay logs: 28 of 38 went into charcoal and left him 15 short for the trip)
            if (coal == 0 && torches <= 6) { int allLogs = logs + (g.level() instanceof ServerLevel sl ? ClaudeLife.stored(g, sl).entrySet().stream().filter(e -> new ItemStack(e.getKey()).is(net.minecraft.tags.ItemTags.LOGS)).mapToInt(java.util.Map.Entry::getValue).sum() : 0); m.put(Items.CHARCOAL, Math.max(2, Math.min(4, allLogs * 3 / 5))); } // (his chests count: 0 logs on him sent him 353 blocks home for 2 charcoal, 8 torches, live 23:25) // no light at all: four logs into charcoal for 16 torches first, then the coal trip with them (down his dark mine with no torch for coal is how a skeleton got him at 04:47) // no light at all: a few logs into charcoal first, deep or not (the deep branch below caught it first and sent him for coal in the dark)
            else if (spareLogs >= want) m.put(Items.CHARCOAL, want); // his own logs in a furnace
            else if (g.getBlockY() < 0) { if (spareLogs > 0) m.put(Items.CHARCOAL, spareLogs); } // deep down coal is rare (a strip mine for coal at y-58 found nothing): the spare logs into charcoal, never the kit's own 32 (24 of 40 logs went to charcoal and the trip came up short)
            else m.put(Items.COAL, want); // up near coal's level: mine it
        }
        if (!netherLeg && torches <= 6 && (coal > 0 || logs >= 3)) m.put(Items.TORCH, coal == 0 && m.containsKey(Items.CHARCOAL) ? Math.min(64 - torches, 4 * m.get(Items.CHARCOAL)) : 64 - torches); // (with no coal, as many torches as the charcoal he's making covers: 64 of them sent the planner after coal in the dark) // the user: a stack of torches in the mining kit, topped off once he's down to 6 (no coal: logs into charcoal); down a dark mine with 0 torches is how a cave by his stairs stayed a mob den (seven deaths) // a mine on file and coal in his pocket: torches, for the stairs and his bedroom (a dark bed pocket is a mob den)
        if (full) {
            if (g.inventory.count(s -> s.is(Items.BUCKET) || s.is(Items.WATER_BUCKET)) == 0) m.put(Items.BUCKET, 1); // the user: a bucket first at the iron stage (the farm's water, the portal cast, a fall, a lava pocket): 3 ingots before any armor piece
            // armor first, slot by slot: the diamond piece straight away when his diamonds (on him and in his chests) cover it, else the iron one
            // (the user: no iron armor needed when there are diamonds enough for diamond armor); a diamond pickaxe's 3 are kept back first
            int diamonds = g.inventory.count(s -> s.is(Items.DIAMOND)) + (g.level() instanceof ServerLevel sl ? ClaudeLife.stored(g, sl).getOrDefault(Items.DIAMOND, 0) : 0);
            if (g.inventory.count(st -> st.is(Items.IRON_PICKAXE) || st.is(Items.DIAMOND_PICKAXE) || st.is(Items.NETHERITE_PICKAXE)) == 0) m.put(diamonds >= 3 ? Items.DIAMOND_PICKAXE : Items.IRON_PICKAXE, 1); // (diamond when his diamonds cover it, else iron: either way the pick comes before any armor) // the user: an iron pick before any armor (redstone, gold and the diamonds need it; 4 ingots on him and only stone picks)
            if (g.inventory.count(st -> st.is(Items.DIAMOND_PICKAXE)) == 0) diamonds -= 3;
            Item[] ironBy = {Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_HELMET, Items.IRON_BOOTS}, diaBy = {Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_HELMET, Items.DIAMOND_BOOTS};
            int[] cost = {8, 7, 5, 4};
            boolean dragonKit = netherDone(g);
            boolean deep = diamondStage(g) && dragonKit; // (iron on a slot waits for its diamond piece only for the dragon kit: the Nether trip goes in iron, the user) // (the diamond stage: a slot with iron on it waits for its diamond piece; a bare slot never does, it gets iron unless the diamonds cover it: a diamond pick and nothing else had him at y -58 with no armor, every slot "waiting for diamonds")
            for (int i = 0; i < 4; i++) {
                if (has(g, diaBy[i])) continue;
                if (diamonds >= cost[i] || deep && has(g, ironBy[i])) { m.put(diaBy[i], 1); diamonds -= cost[i]; }
                else if (!has(g, ironBy[i])) m.put(ironBy[i], 1);
            }
            if (!sword(g, true)) m.put(diamonds >= 2 ? Items.DIAMOND_SWORD : Items.IRON_SWORD, 1); // a water bucket: the one thing that undoes a fall, a lava pocket or a burning (3 ingots, after the sword)
            if (!shield(g)) m.put(Items.SHIELD, 1);
            for (Item t : dragonKit ? java.util.List.of(Items.DIAMOND_PICKAXE) /* (no diamond axe or shovel: full diamond is the dragon's armor, not tools; asking for them sent him 350 blocks from the stronghold's bricks for 4 diamonds, live 00:36) */ : com.claudemod.entity.life.PortalMap.litPortal(g) ? java.util.List.<Item>of() : java.util.List.of(Items.DIAMOND_PICKAXE)) if (g.inventory.count(st -> st.is(t)) == 0) m.put(t, 1); // (the Nether trip: a diamond pick for the obsidian; the axe and shovel for the dragon kit) // then the diamond tools: the same trip as the iron, just deeper (y -58)
            // the bow and its quiver are a want, not this kit's gate (the user: wanted for the Nether, a must only before the End): in the kit when they're cheap, the string on him or in a chest a short walk off, the flint and feathers on him; never a spider hunt for the kit (live 13:05: gear_up's "fight spider 2" with none about, then a daylight explore for spiders while the portal waited)
            if (g.inventory.count(s -> s.is(Items.BOW)) == 0 && bowCheap(g)) m.put(Items.BOW, 1);
            int arrowsOn = g.inventory.count(s -> s.is(Items.ARROW));
            if ((g.inventory.count(s -> s.is(Items.BOW)) > 0 || m.containsKey(Items.BOW)) && arrowsOn < 32 && flint > 0 && feathers > 0) m.put(Items.ARROW, Math.min(32 - arrowsOn, 4 * Math.min(flint, feathers)));
            if (dragonKit) for (Item a : new Item[] {Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS}) if (!has(g, a)) m.put(a, 1); // and for the End, full diamond armor (never before the Nether: live 12:04 World 15 it held the Nether trip on hours of diamonds)
            m = byValue(g, m);
        }
        return m;
    }

    private static final java.util.Map<java.util.UUID, String> orderSaid = new java.util.HashMap<>();
    /**
     * The iron-stage pieces (bucket, pick, armor, sword, shield) in the order of their worth per ingot on the next trip, the rest
     * of the kit where it was (the user 11-06: every fixed rule a weighed decision; was bucket, pick, chest, legs, helmet, boots,
     * sword, shield whatever he wore). Worth in seconds: armor and a shield by the HP they keep off the trip's hits (kit.trip_hits
     * through vanilla's armor sum, kit.hp_s an HP), a sword by the fight time it saves, a bucket and a pick by what they open up.
     */
    static Map<Item, Integer> byValue(ClaudeEntity g, Map<Item, Integer> m) {
        java.util.List<Item> ranked = new java.util.ArrayList<>(); for (Item i : m.keySet()) if (ingots(i) > 0) ranked.add(i);
        if (ranked.size() < 2) return m;
        java.util.Map<Item, Double> per = new java.util.HashMap<>(); for (Item i : ranked) per.put(i, worth(g, i) / ingots(i));
        ranked.sort((a, b) -> Double.compare(per.get(b), per.get(a)));
        Map<Item, Integer> out = new LinkedHashMap<>(); boolean put = false;
        for (var e : m.entrySet()) { if (ingots(e.getKey()) > 0) { if (!put) { for (Item i : ranked) out.put(i, m.get(i)); put = true; } } else out.put(e.getKey(), e.getValue()); }
        StringBuilder said = new StringBuilder(); for (Item i : ranked) said.append(said.length() == 0 ? "" : ", ").append(Crafting.name(i)).append(' ').append(Math.round(per.get(i))).append(" s/ingot");
        if (!said.toString().equals(orderSaid.get(g.getUUID()))) { if (orderSaid.size() > 64) orderSaid.clear(); orderSaid.put(g.getUUID(), said.toString()); com.claudemod.ClaudeMod.LOGGER.info("[Claude kit] the iron kit by worth per ingot on the next trip: {} (Kit.byValue)", said); }
        return out;
    }
    /** Ingots (a diamond as kit.diamond_ingots) a piece costs; 0: not an iron-stage piece. */
    static double ingots(Item i) {
        String n = Crafting.name(i); double d = w("kit.diamond_ingots", 3);
        if (i == Items.BUCKET) return 3; if (i == Items.SHIELD) return 1;
        if (n.equals("iron_pickaxe")) return 3; if (n.equals("diamond_pickaxe")) return 3 * d;
        if (n.equals("iron_sword")) return 2; if (n.equals("diamond_sword")) return 2 * d;
        int[] c = {8, 7, 5, 4}; String[] s = {"chestplate", "leggings", "helmet", "boots"};
        for (int k = 0; k < 4; k++) { if (n.equals("iron_" + s[k])) return c[k]; if (n.equals("diamond_" + s[k])) return c[k] * d; }
        return 0;
    }
    private static double red(double a, double t, double dmg) { return Math.min(20, Math.max(a / 5, a - dmg / (2 + t / 4))) / 25; }
    /** Seconds a piece saves on the next trip. */
    static double worth(ClaudeEntity g, Item i) {
        double hits = w("kit.trip_hits", 60), dmg = com.claudemod.entity.life.Decide.hitHp(), hpS = w("kit.hp_s", 6);
        double a = g.getArmorValue(), t = g.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR_TOUGHNESS);
        if (i == Items.BUCKET) return w("kit.bucket_trip_s", 240); // (falls, lava, a burning, the farm's water, the portal cast: the user, "a bucket first at the iron stage")
        if (i == Items.SHIELD) return hits * w("kit.shield_block", 0.35) * dmg * (1 - red(a, t, dmg)) * hpS;
        if (i instanceof net.minecraft.world.item.PickaxeItem) return w(i == Items.DIAMOND_PICKAXE ? "kit.diamond_pick_s" : "kit.iron_pick_s", 900); // (diamonds and gold need iron, obsidian diamond: the goal waits on it)
        if (i instanceof net.minecraft.world.item.SwordItem sw) {
            double best = 1; for (int k = 0; k < g.inventory.items.getContainerSize(); k++) if (g.inventory.items.getItem(k).getItem() instanceof net.minecraft.world.item.SwordItem o) best = Math.max(best, 4 + o.getTier().getAttackDamageBonus());
            double nw = 4 + sw.getTier().getAttackDamageBonus(), mob = w("kit.mob_hp", 20), swing = w("kit.swing_s", 0.625);
            double saved = w("kit.trip_kills", 15) * Math.max(0, (Math.ceil(mob / best) - Math.ceil(mob / nw)) * swing);
            return saved + saved * w("kit.fight_hits_per_s", 0.4) * dmg * (1 - red(a, t, dmg)) * hpS;
        }
        if (i instanceof net.minecraft.world.item.ArmorItem ai) {
            var worn = g.getItemBySlot(ai.getEquipmentSlot()); double wd = worn.getItem() instanceof net.minecraft.world.item.ArmorItem wa ? wa.getDefense() : 0, wt = worn.getItem() instanceof net.minecraft.world.item.ArmorItem wa2 ? wa2.getToughness() : 0;
            double a2 = a - wd + ai.getDefense(), t2 = t - wt + ai.getToughness();
            return Math.max(0, hits * dmg * (red(a2, t2, dmg) - red(a, t, dmg))) * hpS;
        }
        return 0;
    }

    /** What the kit asks for in food: an item and a count, or null (he goes as he is). */
    public record FoodAsk(Item item, int count) {}
    private static final java.util.Map<java.util.UUID, Object[]> foodAskMemo = new java.util.HashMap<>();
    private static double w(String name, double def) { return com.claudemod.entity.life.Weights.w(name, def); }
    /** Food points (nutrition + saturation) in one of the item. */
    static double pts(Item i) { var f = new ItemStack(i).get(DataComponents.FOOD); return f == null ? 0 : f.nutrition() + f.saturation(); }
    /**
     * The kit's food (the user 11-05: every fixed rule a weighed decision; review 105 / 384: salmon chased 6+ deep as a fixed
     * 16-item target, a furnace of cooked salmon left for more). How much: the trip's food points (Food: a stretch on kit.trip_*,
     * by default about the old 16 meals) and a reserve, less what's in him and on him. From where: each source priced in seconds
     * (one [Claude decide] line when it changes): the raw meat he carries cooked, each kind of animal about (the walk to the
     * nearest, a kill each, the cooking), fish near the surface (the deep ones out), a village's hay, a search for animals;
     * every option pays for what it leaves uncovered at kit.short_meal_s a meal, and going as he is pays it all.
     */
    private static final java.util.Map<java.util.UUID, String> foodSaid = new java.util.HashMap<>();
    /**
     * The trip's food for the planner, when he's short: the kit's own priced options from where he stands (what's in view, how
     * far, what's out and why) and the means he has for the rest. D2D review 083848 11-07: told only "you see: no creatures",
     * the planner wrote "fight chicken" 75 off, bread with no wheat, cod with no rod or string; each failed and it guessed again.
     */
    public static String foodLine(ClaudeEntity g) {
        if (foodAsk(g) == null) return "";
        int string = g.inventory.count(x -> x.is(Items.STRING)), wheat = g.inventory.count(x -> x.is(Items.WHEAT)) + 9 * g.inventory.count(x -> x.is(Items.HAY_BLOCK));
        boolean rod = g.inventory.count(x -> x.is(Items.FISHING_ROD)) > 0;
        return "FOOD FOR THE TRIP, weighed from here in seconds: " + foodSaid.getOrDefault(g.getUUID(), "?") + ". Plan the pick (obtain it); the rest of your means: "
                + (rod ? "a fishing rod" : string >= 2 ? "no rod, " + string + " string for one" : "no fishing rod and no string for one (fish only by hand in shallow water)")
                + ", " + (wheat >= 3 ? wheat + " wheat (bread)" : "no wheat (bread needs a grown farm first: minutes, not food now)") + (char) 10;
    }
    public static FoodAsk foodAsk(ClaudeEntity g) {
        Object[] memo = foodAskMemo.get(g.getUUID()); if (memo != null && g.tickCount - (int) memo[0] >= 0 && g.tickCount - (int) memo[0] < 40) return (FoodAsk) memo[1];
        FoodAsk fa = foodAsk0(g); if (foodAskMemo.size() > 64) foodAskMemo.clear(); foodAskMemo.put(g.getUUID(), new Object[] {g.tickCount, fa}); return fa;
    }
    private static FoodAsk foodAsk0(ClaudeEntity g) {
        ServerLevel level = (ServerLevel) g.level();
        var trip = new com.claudemod.entity.life.Food.Stretch(w("kit.trip_sprint_s", 900), 0, 0, 0, (int) w("kit.trip_digs", 2000), (int) w("kit.trip_hits", 60), w("kit.trip_hp", 60), 0);
        var fd = g.getFoodData(); double inHim = Math.max(0, fd.getFoodLevel() - 18) + fd.getSaturationLevel();
        double carried = 0; int rawN = 0; Item rawCooked = null; java.util.Map<Item, Integer> rawBy = new java.util.LinkedHashMap<>();
        Item[][] rawToCooked = RAW_COOKED;
        for (int k = 0; k < g.inventory.items.getContainerSize(); k++) { ItemStack st = g.inventory.items.getItem(k); if (!st.has(DataComponents.FOOD)) continue;
            if (!ClaudeLife.rawOrBad(level, st)) carried += pts(st.getItem()) * st.getCount();
            else for (Item[] rc : rawToCooked) if (st.is(rc[0])) rawBy.merge(rc[1], st.getCount(), Integer::sum); }
        for (var e : rawBy.entrySet()) if (rawCooked == null || e.getValue() * pts(e.getKey()) > rawN * pts(rawCooked)) { rawCooked = e.getKey(); rawN = e.getValue(); } // the raw kind he has most food in, its own count (1 raw pork and 4 beef asked "4 cooked_porkchop": a pork errand from nothing, D2D live 14:16; stage 912)
        double need = com.claudemod.entity.life.Food.points(trip) + w("kit.reserve_points", 42) - inHim, shortP = need - carried;
        if (shortP <= 0) return null;
        double meal = w("food.meal_points", 20.8), shortS = w("kit.short_meal_s", 90), sprint = com.claudemod.entity.life.Decide.sprint(), cook = w("kit.cook_s", 5), kill = w("kit.kill_s", 6);
        boolean nether = level.dimensionType().ultraWarm();
        double home = 0; if (nether) { var pt = com.claudemod.entity.life.PortalMap.net(g).portals.stream().filter(p0 -> com.claudemod.entity.life.PortalMap.NETHER.equals(p0.dim) && "lit".equals(p0.state)).min(java.util.Comparator.comparingDouble(p0 -> p0.at.distSqr(g.blockPosition()))).orElse(null);
            home = pt == null ? w("kit.no_portal_s", 1800) : 2 * (Math.sqrt(pt.at.distSqr(g.blockPosition())) * com.claudemod.entity.life.PortalMap.NETHER_OFF_ROAD / sprint + w("highway.portal_s", 8)); } // (no animals in the Nether: through his portal for them, and back)
        final double homeS = home;
        // a search for cows: one kit.search_s a herd it has to find (7 cows is 3 herds, not one look round: live 02:51 World 17 five empty legs east, 4 sheep 2 off passed by; reviews 386/408)
        java.util.function.DoubleUnaryOperator searchS = p -> { if (p <= 0) return 0; int k = (int) Math.ceil(p / (2 * pts(Items.COOKED_BEEF))); return homeS + Math.ceil(k / w("kit.herd_cows", 3)) * w("kit.search_s", 120) + k * (kill + 2 * cook); };
        // what an option leaves: a search for the rest after it, or the trip without it, whichever is less (charged as never found, the sheep beside him lost to the search)
        java.util.function.DoubleUnaryOperator rest = p -> p <= 0 ? 0 : Math.min(p / meal * shortS, searchS.applyAsDouble(p));
        java.util.List<com.claudemod.entity.life.Decide.Option> opts = new java.util.ArrayList<>(); java.util.Map<String, FoodAsk> asks = new java.util.HashMap<>();
        opts.add(com.claudemod.entity.life.Decide.Option.of("as he is", shortP / meal * shortS, (int) Math.ceil(shortP / meal) + " meal(s) short, found on the way"));
        if (rawN > 0) { // his own raw meat, at a furnace of his close by (the smelt skill's rule: a few raws ride home, never a furnace set down out here; live 03:17 the kit asked and smelt said "cooks at home", every few seconds)
            double fur = Double.MAX_VALUE; for (var q : g.myStations) if (level.getBlockState(q).is(net.minecraft.world.level.block.Blocks.FURNACE)) fur = Math.min(fur, Math.sqrt(q.distSqr(g.blockPosition())));
            int n = (int) Math.min(rawN, Math.ceil(shortP / pts(rawCooked))); double left = Math.max(0, shortP - n * pts(rawCooked));
            if (fur > w("kit.cook_furnace_range", 16)) opts.add(com.claudemod.entity.life.Decide.Option.out("cook his raw", rawN + " raw, no furnace of his within " + (int) w("kit.cook_furnace_range", 16) + ": they cook at home"));
            else { opts.add(com.claudemod.entity.life.Decide.Option.of("cook his raw", fur / sprint + n * cook + rest.applyAsDouble(left), n + " to cook")); asks.put("cook his raw", new FoodAsk(rawCooked, n)); } }
        { // his stores: cooked food in his chests (as of his last look) and his furnaces' output close by (review 384: 5 cooked salmon left in his furnace for a hunt 70 off)
          Item best = null; int have = 0; for (var e : ClaudeLife.stored(g, level).entrySet()) { ItemStack st = new ItemStack(e.getKey()); if (!st.has(DataComponents.FOOD) || ClaudeLife.rawOrBad(level, st)) continue; if (best == null || pts(e.getKey()) * e.getValue() > pts(best) * have) { best = e.getKey(); have = e.getValue(); } }
          if (best != null) { final Item bi = best; double far = Double.MAX_VALUE;
            for (var sp : com.claudemod.entity.life.ChestLedger.where(g, level, x -> x.is(bi))) far = Math.min(far, Math.sqrt(sp.pos().distSqr(g.blockPosition())));
            for (var q : g.myStations) if (level.isLoaded(q) && level.getBlockEntity(q) instanceof net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity f && f.getItem(2).is(bi)) far = Math.min(far, Math.sqrt(q.distSqr(g.blockPosition())));
            if (far < Double.MAX_VALUE) { int n = (int) Math.min(have, Math.ceil(shortP / pts(bi))); double left = Math.max(0, shortP - n * pts(bi));
              String nm = "his stores (" + Crafting.name(bi) + ")"; opts.add(com.claudemod.entity.life.Decide.Option.of(nm, far / sprint + w("kit.take_s", 3) + rest.applyAsDouble(left), have + " there, " + (int) far + " off")); asks.put(nm, new FoodAsk(bi, n)); } } }
        Object[][] herds = {{Cow.class, Items.COOKED_BEEF, 2.0}, {Pig.class, Items.COOKED_PORKCHOP, 2.0}, {Sheep.class, Items.COOKED_MUTTON, 1.5}, {Chicken.class, Items.COOKED_CHICKEN, 1.0}};
        var box = g.getBoundingBox().inflate(w("kit.prey_range", 48));
        for (Object[] hd : herds) { @SuppressWarnings("unchecked") var cls = (Class<? extends net.minecraft.world.entity.animal.Animal>) hd[0]; Item item = (Item) hd[1]; double yield = (double) hd[2];
            var near = new java.util.ArrayList<>(level.getEntitiesOfClass(cls, box, a -> a.isAlive() && !a.isBaby())); if (near.isEmpty()) continue;
            String name = "hunt " + net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(near.get(0).getType()).getPath();
            near.sort(java.util.Comparator.comparingDouble(g::distanceToSqr)); double walk = ClaudeLife.preyWalk(g, level, near.get(0));
            if (walk < 0) { opts.add(com.claudemod.entity.life.Decide.Option.out(name, near.size() + " about, no walk to the nearest")); continue; }
            int k = (int) Math.min(near.size(), Math.ceil(shortP / (yield * pts(item)))); double got = k * yield * pts(item), left = Math.max(0, shortP - got);
            opts.add(com.claudemod.entity.life.Decide.Option.of(name, walk / sprint + k * (kill + yield * cook) + rest.applyAsDouble(left) + ClaudeLife.nightOutS(g, level, near.get(0).blockPosition(), -1), k + " of " + near.size() + ", nearest " + (int) walk + " walk"));
            asks.put(name, new FoodAsk(item, (int) Math.ceil(Math.min(got, shortP) / pts(item)))); }
        { var fishBox = g.getBoundingBox().inflate(w("kit.fish_range", 24));
          var fish = level.getEntitiesOfClass(net.minecraft.world.entity.animal.AbstractFish.class, fishBox, f -> f.isAlive() && (f instanceof net.minecraft.world.entity.animal.Salmon || f instanceof net.minecraft.world.entity.animal.Cod));
          var shallow = fish.stream().filter(f -> ClaudeLife.shallowFish(level, f)).sorted(java.util.Comparator.comparingDouble(g::distanceToSqr)).toList();
          if (!fish.isEmpty() && shallow.isEmpty()) opts.add(com.claudemod.entity.life.Decide.Option.out("fish", fish.size() + " about, all too deep to reach"));
          else if (!shallow.isEmpty()) { long sal = shallow.stream().filter(f -> f instanceof net.minecraft.world.entity.animal.Salmon).count(); Item item = sal * 2 >= shallow.size() ? Items.COOKED_SALMON : Items.COOKED_COD;
            int k = (int) Math.min(shallow.size(), Math.ceil(shortP / pts(item))); double left = Math.max(0, shortP - k * pts(item));
            opts.add(com.claudemod.entity.life.Decide.Option.of("fish", Math.sqrt(g.distanceToSqr(shallow.get(0))) / sprint + k * (w("kit.fish_s", 15) + cook) + rest.applyAsDouble(left) + ClaudeLife.nightOutS(g, level, shallow.get(0).blockPosition(), -1), k + " of " + shallow.size() + " near the top"));
            asks.put("fish", new FoodAsk(item, k)); } }
        if (nether) { // the Nether's own meat: hoglins (crimson forest), 2-4 pork each, fought from 2 up (collect's hoglin tower), cooked at a furnace of his or one set down here (D2D 01:46 World 17: 1 meal, "search for animals" took him back to the portal for cows)
            var hogs = new java.util.ArrayList<>(level.getEntitiesOfClass(net.minecraft.world.entity.monster.hoglin.Hoglin.class, g.getBoundingBox().inflate(w("kit.prey_range", 48)), a -> a.isAlive() && !a.isBaby()));
            double fur = Double.MAX_VALUE; for (var q : g.myStations) if (level.getBlockState(q).is(net.minecraft.world.level.block.Blocks.FURNACE)) fur = Math.min(fur, Math.sqrt(q.distSqr(g.blockPosition())));
            double furnaceS = fur <= w("kit.cook_furnace_range", 16) ? fur / sprint : w("kit.nether_furnace_s", 45); // (none near: 8 blackstone or cobble dug and a furnace set down)
            double per = w("kit.hoglin_pork", 3) * pts(Items.COOKED_PORKCHOP), fightS = w("kit.hoglin_kill_s", 12) + com.claudemod.entity.life.Decide.hpS(g, w("kit.hoglin_hp", 2));
            int k = (int) Math.ceil(shortP / per); double reach; String where;
            if (!hogs.isEmpty()) { hogs.sort(java.util.Comparator.comparingDouble(g::distanceToSqr)); k = Math.min(k, hogs.size()); reach = Math.sqrt(g.distanceToSqr(hogs.get(0))) * com.claudemod.entity.life.PortalMap.NETHER_OFF_ROAD / sprint; where = k + " of " + hogs.size() + " hoglin(s), nearest " + (int) Math.sqrt(g.distanceToSqr(hogs.get(0))) + " off"; }
            else { net.minecraft.core.BlockPos cf = com.claudemod.entity.life.Atlas.find(g, level, "crimson_forest"); reach = cf == null ? Double.MAX_VALUE : Math.sqrt(cf.distSqr(g.blockPosition())) * com.claudemod.entity.life.PortalMap.NETHER_OFF_ROAD / sprint + w("kit.hoglin_search_s", 60); where = cf == null ? null : "a crimson forest at " + cf.toShortString(); }
            if (reach == Double.MAX_VALUE) opts.add(com.claudemod.entity.life.Decide.Option.out("hunt hoglins", "none in sight and no crimson forest on my map"));
            else { double left = Math.max(0, shortP - k * per); int pork = (int) Math.ceil(Math.min(k * per, shortP) / pts(Items.COOKED_PORKCHOP));
              opts.add(com.claudemod.entity.life.Decide.Option.of("hunt hoglins", reach + k * fightS + furnaceS + pork * cook + rest.applyAsDouble(left), where + (fur <= w("kit.cook_furnace_range", 16) ? ", his furnace " + (int) fur + " off" : ", a furnace set down here")));
              asks.put("hunt hoglins", new FoodAsk(Items.COOKED_PORKCHOP, pork)); }
        }
        { int bales = ClaudeLife.villageHayInSight(g, level); if (bales > 0) { int bread = (int) Math.min(bales * 3, Math.ceil(shortP / pts(Items.BREAD))); double left = Math.max(0, shortP - bread * pts(Items.BREAD));
            opts.add(com.claudemod.entity.life.Decide.Option.of("village bread", w("kit.hay_walk_s", 20) + bread / 3.0 * w("kit.bale_s", 4) + rest.applyAsDouble(left), bales + " bale(s) in sight")); asks.put("village bread", new FoodAsk(Items.BREAD, bread)); }
          else { var vil = ClaudeLife.knownVillage(g, "village_food", level); // a village he saw with hay, out of view now: its bread priced by the walk there and back (the user 11-06: "hay from villages should also be weighted as food"; the village_food skill walks there, Procure counts it the same)
            if (vil != null) { int known = (int) (g.pref("village_mode", "useful").equals("raid") ? w("kit.known_village_bales_raid", 8) : w("kit.known_village_bales", 4)); net.minecraft.core.BlockPos vc = vil.nearestCell(g.position()); double far = ClaudeLife.routeDist(g, g.blockPosition(), vc);
              int bread = (int) Math.min(known * 3, Math.ceil(shortP / pts(Items.BREAD))); double left = Math.max(0, shortP - bread * pts(Items.BREAD));
              opts.add(com.claudemod.entity.life.Decide.Option.of("village bread", 2 * far / sprint + bread / 3.0 * w("kit.bale_s", 4) + rest.applyAsDouble(left) + ClaudeLife.nightOutS(g, level, vc, -1), "the village at " + vc.toShortString() + ", " + (int) far + " off, hay seen there (" + known + " bale(s) counted)")); asks.put("village bread", new FoodAsk(Items.BREAD, bread)); } } }
        boolean inView = opts.stream().anyMatch(o -> o.no() == null && (o.name().startsWith("hunt ") || o.name().equals("fish"))); // food he can walk to in view: a search walks past it, so a search first is never cheaper than that hunt with the search for what it leaves (rest()), which its price already holds (D2D reviews 082343-15, 083848-112: "search ~336 s" over a cow 53 off, then 380 blocks to an old spot; chickens at 41 and pigs at 54 passed for beef)
        if (inView) opts.add(com.claudemod.entity.life.Decide.Option.out("search for animals", "food in view to hunt first: the search is only for what that leaves, in its price"));
        else { int k = (int) Math.ceil(shortP / (2 * pts(Items.COOKED_BEEF)));
          opts.add(com.claudemod.entity.life.Decide.Option.of("search for animals", searchS.applyAsDouble(shortP), k + " cows' worth" + (nether ? ", through the portal and back ~" + Math.round(home) + " s" : ""))); asks.put("search for animals", new FoodAsk(Items.COOKED_BEEF, 2 * k)); }
        var pick = com.claudemod.entity.life.Decide.choose(g, "kit.food", "", false, opts);
        StringBuilder said = new StringBuilder(); for (var o : opts) said.append(said.length() == 0 ? "" : "; ").append(o.no() != null ? o.name() + " out (" + o.no() + ")" : o.name() + " ~" + Math.round(o.s()) + " s" + (o.detail().isEmpty() ? "" : " (" + o.detail() + ")"));
        FoodAsk fa = pick == null ? null : asks.get(pick.name());
        foodSaid.put(g.getUUID(), (int) shortP + " food points short (" + (int) carried + " on him): " + said + "; the pick: " + (pick == null ? "none" : pick.name()) + (fa == null ? "" : " -> " + fa.count() + " " + Crafting.name(fa.item())));
        ClaudeLife.nookSay(g, "kitFood", "[Claude decide] kit.food (" + (int) shortP + " food points short of the trip's " + (int) need + ", " + (int) carried + " on him): " + said + ": " + (pick == null ? "none" : pick.name()) + (fa == null ? "" : " -> " + fa.count() + " " + Crafting.name(fa.item())) + " (Kit.foodAsk)");
        return fa;
    }

    /**
     * The food to go for now, hurt or starving with nothing on him: the kit's priced ask (foodAsk), as the raw meat when he's
     * hurt with nothing to eat (eaten as it drops, no furnace wait first; raw chicken stays cooked: it can sicken him). Null when
     * the pricing says go as he is.
     */
    public static FoodAsk foodNow(ClaudeEntity g) {
        FoodAsk fa = foodAsk(g); if (fa == null || !(g.getHealth() <= 8 && food(g) == 0)) return fa;
        for (Item[] rc : RAW_COOKED) if (rc[1] == fa.item() && rc[0] != Items.CHICKEN) return new FoodAsk(rc[0], fa.count());
        return fa;
    }
    static final Item[][] RAW_COOKED = {{Items.PORKCHOP, Items.COOKED_PORKCHOP}, {Items.BEEF, Items.COOKED_BEEF}, {Items.MUTTON, Items.COOKED_MUTTON}, {Items.CHICKEN, Items.COOKED_CHICKEN}, {Items.RABBIT, Items.COOKED_RABBIT}, {Items.COD, Items.COOKED_COD}, {Items.SALMON, Items.COOKED_SALMON}};

    /** The wool colour he has 3 or more of (enough for a bed), or null. */
    public static String woolForBed(ClaudeEntity g) {
        for (net.minecraft.world.item.DyeColor c : net.minecraft.world.item.DyeColor.values()) {
            Item wool = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.withDefaultNamespace(c.getName() + "_wool"));
            if (g.inventory.count(s -> s.is(wool)) >= 3) return c.getName();
        }
        return null;
    }

    /** A bed in the colour of the wool he has (3 of one colour) or of the sheep nearest him: any bed sleeps the same, and no dye chain for it. */
    public static Item bedChoice(ClaudeEntity g) {
        for (net.minecraft.world.item.DyeColor c : net.minecraft.world.item.DyeColor.values()) {
            Item wool = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.withDefaultNamespace(c.getName() + "_wool"));
            if (g.inventory.count(s -> s.is(wool)) >= 3) return net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.withDefaultNamespace(c.getName() + "_bed"));
        }
        var sheep = g.level().getEntitiesOfClass(Sheep.class, g.getBoundingBox().inflate(48), s -> s.isAlive() && !s.isSheared());
        if (!sheep.isEmpty()) { // the colour there's most of round here (the nearest one was a black sheep: he walked 150 blocks looking for two more)
            int[] n = new int[16]; for (Sheep sh : sheep) n[sh.getColor().getId()]++;
            int best = 0; for (int i = 1; i < 16; i++) if (n[i] > n[best]) best = i;
            return net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.withDefaultNamespace(net.minecraft.world.item.DyeColor.byId(best).getName() + "_bed"));
        }
        return Items.WHITE_BED;
    }

    /**
     * What a seasoned player carries before walking into each dangerous place, and why: one bar a place, not the dragon kit for all
     * (world 14, 03:36: "missing 7 cooked_chicken, diamond_chestplate ... bow, 32 arrow" for a mineshaft). armor: pieces of that
     * tier or better, worn or carried (diamond: diamond tier, else iron); sword: 0 any, 1 iron or better, 2 diamond or better;
     * meals: good food items; torches; arrows: a bow and that many; blocks: building blocks (a chokepoint wall, cover, a pillar);
     * gold: a piece of gold armor on.
     */
    public record Bar(String place, int armor, boolean diamond, int sword, boolean shield, int meals, int torches, int arrows, int blocks, boolean gold, String why) {}
    private static Map<String, Bar> bars; // by Structures' KNOWN key (static: fields added live start null)
    public static Map<String, Bar> bars() {
        if (bars == null) bars = Map.ofEntries(
                Map.entry("mineshaft", new Bar("a mineshaft", 3, false, 0, false, 6, 16, 0, 0, false, "cave spiders out of the cobweb spawners bite with poison and the corridors are dark: most of an iron set, a sword for the webs and the spiders, a few meals to heal on, torches to light the way back and cap the spawner")),
                Map.entry("stronghold", new Bar("a stronghold", 4, false, 1, true, 10, 32, 0, 0, false, "silverfish in the walls, a mob round every dark corner, the portal room's spawner: full iron, an iron sword and a shield, food to heal on, torches to mark the way out of the maze")),
                Map.entry("trial_chambers", new Bar("a trial chamber", 4, false, 1, true, 12, 0, 16, 32, false, "trial spawners wake as you come near and send wave after wave, breezes knock you about, skeletons shoot: full iron, an iron sword and a shield, a bow for the ranged ones, food for long fights, blocks to wall a corridor into a chokepoint")),
                Map.entry("fortress", new Bar("a nether fortress", 4, false, 1, true, 12, 0, 16, 32, false, "blazes shoot fireballs from range and wither skeletons hit hard and wither you: full iron, an iron sword and a shield, a bow for the blazes, food, blocks for cover")),
                Map.entry("bastion_remnant", new Bar("a bastion", 4, false, 1, true, 12, 0, 0, 32, true, "piglin brutes hit through iron and ignore gold, the other piglins stay calm only with gold armor on, lava and drops everywhere: full iron with a gold piece, an iron sword and a shield, food, blocks to bridge and wall")),
                Map.entry("ancient_city", new Bar("an ancient city", 4, true, 2, true, 16, 0, 0, 32, false, "the warden kills a player in iron in a few hits and the shriekers call it up: only in full diamond with a diamond sword and a shield, lots of food, blocks to pillar away; even then you sneak and leave the warden alone")),
                Map.entry("monument", new Bar("an ocean monument", 4, false, 1, false, 16, 0, 0, 32, false, "guardians' lasers and the elder guardians' mining fatigue, all under water: full iron, an iron sword, a lot of food, blocks to wall the guardians off and make air pockets")),
                Map.entry("mansion", new Bar("a woodland mansion", 4, false, 1, true, 12, 32, 0, 0, false, "vindicators with axes and evokers with vexes and fangs, rooms upon rooms: full iron, an iron sword and a shield, food, torches to light and mark the rooms")),
                Map.entry("pillager_outpost", new Bar("a pillager outpost", 4, false, 1, true, 8, 0, 0, 0, false, "crossbow volleys from the tower: full iron and a shield for the bolts, an iron sword, food")),
                Map.entry("end_city", new Bar("an end city", 4, true, 2, false, 16, 0, 0, 64, false, "shulker bullets float you off the towers and the fall kills: full diamond, a diamond sword, lots of food, blocks to wall the shulkers off and to get down")),
                Map.entry("swamp_hut", new Bar("a witch hut", 0, false, 0, false, 4, 0, 0, 0, false, "one witch, her potions the danger: a sword and some food")),
                Map.entry("ocean_ruin", new Bar("the ocean ruins", 2, false, 0, false, 6, 0, 0, 0, false, "drowned about, some with tridents, all under water: some iron armor, a sword, food"))); // (the temples have no bar: traps, not mobs; the loot skill minds the plate and the tripwires)
        return bars;
    }
    /** The bar for a structure id, or null (no gear bar: nothing there a sword and sense don't cover). */
    public static Bar bar(String id) { String k = Structures.key(id); return k == null ? null : bars().get(k); }

    /** Worn or carried: armor for that piece's slot at least as good as it. */
    static boolean atLeast(ClaudeEntity g, Item piece) {
        ArmorItem a = (ArmorItem) piece;
        if (g.getItemBySlot(a.getEquipmentSlot()).getItem() instanceof ArmorItem w && w.getDefense() >= a.getDefense()) return true;
        return g.inventory.count(s -> s.getItem() instanceof ArmorItem w && w.getEquipmentSlot() == a.getEquipmentSlot() && w.getDefense() >= a.getDefense()) > 0;
    }
    public static int buildingBlocks(ClaudeEntity g) {
        return g.inventory.count(s -> s.is(Items.COBBLESTONE) || s.is(Items.COBBLED_DEEPSLATE) || s.is(Items.DIRT) || s.is(Items.NETHERRACK) || s.is(Items.STONE) || s.is(Items.DEEPSLATE) || s.is(Items.BLACKSTONE) || s.is(Items.END_STONE) || s.is(net.minecraft.tags.ItemTags.PLANKS) || s.is(net.minecraft.tags.ItemTags.WOOL));
    }
    /** What he lacks for that place's bar, as a player says it ("most of an iron set", "a shield", "6 meals", "16 torches"); empty: he's geared for it. */
    public static java.util.List<String> shortOf(ClaudeEntity g, Bar b) {
        java.util.List<String> out = new java.util.ArrayList<>();
        Item[] want = b.diamond() ? new Item[] {Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_HELMET, Items.DIAMOND_BOOTS} : new Item[] {Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_HELMET, Items.IRON_BOOTS};
        java.util.List<Item> lack = new java.util.ArrayList<>(); for (Item a : want) if (!atLeast(g, a)) lack.add(a);
        int armorShort = b.armor() - (4 - lack.size());
        String tier = b.diamond() ? "diamond" : "iron", aTier = b.diamond() ? "a diamond" : "an iron";
        if (armorShort > 0) {
            if (armorShort == 4) out.add("full " + tier + " armor");
            else if (lack.size() == 4) out.add(armorShort == 3 ? "most of " + aTier + " set" : armorShort == 1 ? "a piece of " + tier + " armor" : armorShort + " pieces of " + tier + " armor");
            else for (int i = 0; i < armorShort; i++) out.add(spoken(lack.get(i), 1));
        }
        if (b.gold()) {
            boolean gold = false;
            for (var sl : EquipmentSlot.values()) if (sl.isArmor() && BuiltInRegistriesPath(g.getItemBySlot(sl).getItem()).startsWith("golden_")) gold = true;
            if (!gold) out.add("a piece of gold armor");
        }
        boolean sw = b.sword() == 0 ? sword(g, false) : b.sword() == 1 ? sword(g, true) : g.inventory.count(s -> s.is(Items.DIAMOND_SWORD) || s.is(Items.NETHERITE_SWORD)) > 0;
        if (!sw) out.add(b.sword() == 0 ? "a sword" : b.sword() == 1 ? "an iron sword" : "a diamond sword");
        if (b.shield() && !shield(g)) out.add("a shield");
        int f = food(g);
        if (f < b.meals()) out.add(f == 0 ? b.meals() + " meals" : (b.meals() - f) + " more meal" + (b.meals() - f == 1 ? "" : "s"));
        int t = g.inventory.count(s -> s.is(Items.TORCH));
        if (t < b.torches()) out.add(t == 0 ? spoken(Items.TORCH, b.torches()) : (b.torches() - t) + " more torch" + (b.torches() - t == 1 ? "" : "es"));
        if (b.arrows() > 0) {
            if (g.inventory.count(s -> s.is(Items.BOW)) == 0) out.add("a bow");
            int ar = g.inventory.count(s -> s.is(Items.ARROW));
            if (ar < b.arrows()) out.add(ar == 0 ? spoken(Items.ARROW, b.arrows()) : (b.arrows() - ar) + " more arrow" + (b.arrows() - ar == 1 ? "" : "s"));
        }
        int bl = buildingBlocks(g);
        if (bl < b.blocks()) out.add(bl == 0 ? b.blocks() + " blocks" : (b.blocks() - bl) + " more blocks");
        return out;
    }
    private static String BuiltInRegistriesPath(Item i) { return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(i).getPath(); }

    /** An item as a player says it: the game's own English name, lower case ("iron leggings", "an iron chestplate", "32 arrows"); cooked food by its meat ("chicken"). */
    public static String spoken(Item item, int n) {
        String s = item.getDescription().getString().toLowerCase(java.util.Locale.ROOT);
        if (s.isEmpty() || s.contains(".")) s = Crafting.name(item).replace('_', ' '); // (no English name on file: the id, spaced)
        if (s.startsWith("cooked ")) s = s.substring(7);
        boolean pair = s.endsWith("leggings") || s.endsWith("boots") || s.endsWith("shears");
        boolean mass = false;
        for (String m : new String[] {"chicken", "beef", "mutton", "cod", "salmon", "bread", "rabbit", "steak", "coal", "charcoal", "stone", "deepslate", "dirt", "sand", "gravel", "wool", "string", "dust", "netherrack"}) if (s.endsWith(m)) mass = true;
        if (n == 1) return pair || mass ? s : ("aeiou".indexOf(s.charAt(0)) >= 0 ? "an " : "a ") + s;
        String pl = pair || mass || s.endsWith("s") ? s : s.endsWith("ch") || s.endsWith("sh") || s.endsWith("x") ? s + "es" : s + "s";
        return n + " " + pl;
    }
    /** Items as a player lists them ("a bow, 16 torches and chicken"). */
    public static String say(Map<Item, Integer> m) { java.util.List<String> l = new java.util.ArrayList<>(); m.forEach((i, n) -> l.add(spoken(i, n))); return join(l); }
    /** "a", "a and b", "a, b and c". */
    public static String join(java.util.List<String> l) {
        if (l.isEmpty()) return "";
        if (l.size() == 1) return l.get(0);
        return String.join(", ", l.subList(0, l.size() - 1)) + " and " + l.get(l.size() - 1);
    }

    public static String describe(Map<Item, Integer> m) {
        StringBuilder sb = new StringBuilder();
        m.forEach((i, n) -> sb.append(sb.length() == 0 ? "" : ", ").append(n > 1 ? n + " " : "").append(Crafting.name(i)));
        return sb.toString();
    }
}
