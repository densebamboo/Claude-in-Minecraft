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
    static final int FOOD_WANT = 16; // the user, after a day of deaths that all started hungry: a real stack of food before caves (was 8)

    /** Good food on him (nothing raw, rotten or poisonous), as items. */
    public static int food(ClaudeEntity g) {
        ServerLevel level = (ServerLevel) g.level();
        return g.inventory.count(s -> s.has(DataComponents.FOOD) && !ClaudeLife.rawOrBad(level, s));
    }

    /** Wearing something at least as good, or carrying the piece itself (it goes on at the next armor check). */
    static boolean has(ClaudeEntity g, Item armor) {
        ArmorItem a = (ArmorItem) armor;
        ItemStack worn = g.getItemBySlot(a.getEquipmentSlot());
        if (worn.is(armor) || g.inventory.count(s -> s.is(armor)) > 0) return true;
        if (java.util.Set.of(Items.GOLDEN_HELMET, Items.GOLDEN_CHESTPLATE, Items.GOLDEN_LEGGINGS, Items.GOLDEN_BOOTS).contains(armor)) return false; /* gold is wanted for itself (piglins), no better piece covers it: diamond boots on him and "already wearing something as good as golden_boots" */
        return worn.getItem() instanceof ArmorItem w && w.getDefense() >= a.getDefense();
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
        int f0 = food(g);
        int rawMeat = g.inventory.count(s -> s.has(DataComponents.FOOD) && ClaudeLife.rawOrBad((ServerLevel) g.level(), s) && !s.is(Items.ROTTEN_FLESH) && !s.is(Items.SPIDER_EYE) && !s.is(Items.POISONOUS_POTATO)); // raw meat is a furnace away from food, not a trip away
        int short0 = FOOD_WANT - f0, beyondRaw = short0 - rawMeat;
        if (short0 > 0 && (beyondRaw <= 2 || rawMeat >= 6) && rawMeat > 0) short0 = Math.min(short0, rawMeat); // (6+ raw on him: cook those first, the rest is a top-up for later; 13 raw porkchops by his furnaces and he set off up the stairs to hunt 16 cows) // a trip across the map for one more cow is not worth it: cook what he has
        if (short0 > 0 && f0 >= 10) { // a top-up, not a need: only if there's food within reach (15 meals on him and 400 blocks walked for 6 beef, a skeleton on the way)
            boolean near = ClaudeLife.preyInReach(g, (ServerLevel) g.level()) // (one he can walk to: sheep up a mountain 57 across are no top-up)
                    || !g.level().getEntitiesOfClass(net.minecraft.world.entity.animal.AbstractFish.class, g.getBoundingBox().inflate(24)).isEmpty();
            if (!near && ClaudeLife.villageHayInSight(g, (ServerLevel) g.level()) > 0) near = true; // (a village's hay counts)
            if (!near) short0 = 0;
        }
        Item cookedRaw = null; // the cooked form of the raw meat he carries: cooking that is the job, not a hunt for some other animal
        for (Item[] rc : new Item[][]{{Items.PORKCHOP, Items.COOKED_PORKCHOP}, {Items.BEEF, Items.COOKED_BEEF}, {Items.MUTTON, Items.COOKED_MUTTON}, {Items.CHICKEN, Items.COOKED_CHICKEN}, {Items.RABBIT, Items.COOKED_RABBIT}, {Items.COD, Items.COOKED_COD}, {Items.SALMON, Items.COOKED_SALMON}})
            if (cookedRaw == null && g.inventory.count(st -> st.is(rc[0])) >= short0) cookedRaw = rc[1];
        if (short0 > 0 && cookedRaw == null) { // a village's hay bales in sight: bread, 3 a bale, beats a hunt across the map (he walked 300 blocks for cows past a village's hay)
            int bales = ClaudeLife.villageHayInSight(g, (ServerLevel) g.level());
            if (bales > 0) { int bread = Math.min(short0, bales * 3); m.put(Items.BREAD, bread); short0 -= bread; }
        }
        if (short0 > 0) m.put(cookedRaw != null ? cookedRaw : foodChoice(g), short0);
        boolean sheepAbout = !g.level().getEntitiesOfClass(Sheep.class, g.getBoundingBox().inflate(48)).isEmpty();
        if ((noBed || nookBed) && sheepAbout && !m.containsKey(bedChoice(g))) m.put(bedChoice(g), 1); // sleeping keeps the phantoms off (3 wool off sheep, 3 planks); no sheep about: the bed waits for the next flock he passes, never a 350-block trek for wool on day one
        if (!sword(g, false)) m.put(Items.STONE_SWORD, 1); // something to fight with from the first evening
        int string = g.inventory.count(s -> s.is(Items.STRING)), flint = g.inventory.count(s -> s.is(Items.FLINT)), feathers = g.inventory.count(s -> s.is(Items.FEATHER));
        if (g.inventory.count(s -> s.is(Items.BOW)) == 0 && string >= 3) m.put(Items.BOW, 1); // a bow: skeletons and creepers dealt with from where they can't hit back
        if (g.inventory.count(s -> s.is(Items.BOW)) > 0 && g.inventory.count(s -> s.is(Items.ARROW)) < 8 && flint > 0 && feathers > 0) m.put(Items.ARROW, Math.min(16, 4 * Math.min(flint, feathers)));
        int coal = g.inventory.count(s -> s.is(Items.COAL) || s.is(Items.CHARCOAL)), torches = g.inventory.count(s -> s.is(Items.TORCH));
        int logs = 0; for (int i = 0; i < g.inventory.items.getContainerSize(); i++) if (g.inventory.items.getItem(i).is(net.minecraft.tags.ItemTags.LOGS)) logs += g.inventory.items.getItem(i).getCount();
        if (coal <= 8) { // the user: half a stack of coal/charcoal (mixed) in the mining kit, topped back up to 32 once he's down to 8 (furnace fuel, torches on the go)
            int want = 32 - coal, spareLogs = logs - 40; // (8 of them kept for the planks the kit itself crafts: a table, a furnace, sticks; spare logs into charcoal left the crafts eating the trip logs, and he went up for wood in a loop) (the kit's own 32 logs stay logs: 28 of 38 went into charcoal and left him 15 short for the trip)
            if (coal == 0 && torches <= 6) m.put(Items.CHARCOAL, Math.max(2, Math.min(4, logs * 3 / 5))); // no light at all: four logs into charcoal for 16 torches first, then the coal trip with them (down his dark mine with no torch for coal is how a skeleton got him at 04:47) // no light at all: a few logs into charcoal first, deep or not (the deep branch below caught it first and sent him for coal in the dark)
            else if (spareLogs >= want) m.put(Items.CHARCOAL, want); // his own logs in a furnace
            else if (g.getBlockY() < 0) { if (spareLogs > 0) m.put(Items.CHARCOAL, spareLogs); } // deep down coal is rare (a strip mine for coal at y-58 found nothing): the spare logs into charcoal, never the kit's own 32 (24 of 40 logs went to charcoal and the trip came up short)
            else m.put(Items.COAL, want); // up near coal's level: mine it
        }
        if (torches <= 6 && (coal > 0 || logs >= 3)) m.put(Items.TORCH, coal == 0 && m.containsKey(Items.CHARCOAL) ? Math.min(64 - torches, 4 * m.get(Items.CHARCOAL)) : 64 - torches); // (with no coal, as many torches as the charcoal he's making covers: 64 of them sent the planner after coal in the dark) // the user: a stack of torches in the mining kit, topped off once he's down to 6 (no coal: logs into charcoal); down a dark mine with 0 torches is how a cave by his stairs stayed a mob den (seven deaths) // a mine on file and coal in his pocket: torches, for the stairs and his bedroom (a dark bed pocket is a mob den)
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
            for (Item t : dragonKit ? java.util.List.of(Items.DIAMOND_PICKAXE, Items.DIAMOND_AXE, Items.DIAMOND_SHOVEL) : com.claudemod.entity.life.PortalMap.litPortal(g) ? java.util.List.<Item>of() : java.util.List.of(Items.DIAMOND_PICKAXE)) if (g.inventory.count(st -> st.is(t)) == 0) m.put(t, 1); // (the Nether trip: a diamond pick for the obsidian; the axe and shovel for the dragon kit) // then the diamond tools: the same trip as the iron, just deeper (y -58)
            // the bow and its quiver are a want, not this kit's gate (the user: wanted for the Nether, a must only before the End): in the kit when they're cheap, the string on him or in a chest a short walk off, the flint and feathers on him; never a spider hunt for the kit (live 13:05: gear_up's "fight spider 2" with none about, then a daylight explore for spiders while the portal waited)
            if (g.inventory.count(s -> s.is(Items.BOW)) == 0 && bowCheap(g)) m.put(Items.BOW, 1);
            int arrowsOn = g.inventory.count(s -> s.is(Items.ARROW));
            if ((g.inventory.count(s -> s.is(Items.BOW)) > 0 || m.containsKey(Items.BOW)) && arrowsOn < 32 && flint > 0 && feathers > 0) m.put(Items.ARROW, Math.min(32 - arrowsOn, 4 * Math.min(flint, feathers)));
            if (dragonKit) for (Item a : new Item[] {Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS}) if (!has(g, a)) m.put(a, 1); // and for the End, full diamond armor (never before the Nether: live 12:04 World 15 it held the Nether trip on hours of diamonds)
        }
        return m;
    }

    /** The cooked meat of whatever animal there's most of around him (hunting what's there, not a cow he can't find). */
    public static Item foodChoice(ClaudeEntity g) {
        var box = g.getBoundingBox().inflate(48);
        var level = g.level();
        int cows = level.getEntitiesOfClass(Cow.class, box).size(), pigs = level.getEntitiesOfClass(Pig.class, box).size(),
                sheep = level.getEntitiesOfClass(Sheep.class, box).size(), hens = level.getEntitiesOfClass(Chicken.class, box).size();
        int best = Math.max(Math.max(cows, pigs), Math.max(sheep, hens));
        boolean raw = g.getHealth() <= 8 && food(g) == 0; // hurt with nothing to eat: raw beef now beats cooked beef after a furnace trip (raw chicken stays off the menu)
        if (best == 0) { // no livestock about: the fish in the water are food too (a sword in the river, then the furnace)
            int salmon = level.getEntitiesOfClass(net.minecraft.world.entity.animal.Salmon.class, box).size(), cod = level.getEntitiesOfClass(net.minecraft.world.entity.animal.Cod.class, box).size();
            if (salmon + cod > 0) return salmon >= cod ? (raw ? Items.SALMON : Items.COOKED_SALMON) : (raw ? Items.COD : Items.COOKED_COD);
        }
        if (best == 0 || best == cows) return raw ? Items.BEEF : Items.COOKED_BEEF;
        return best == pigs ? (raw ? Items.PORKCHOP : Items.COOKED_PORKCHOP) : best == sheep ? (raw ? Items.MUTTON : Items.COOKED_MUTTON) : raw && cows + pigs + sheep > 0 ? Items.BEEF : Items.COOKED_CHICKEN;
    }

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
    static int buildingBlocks(ClaudeEntity g) {
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
