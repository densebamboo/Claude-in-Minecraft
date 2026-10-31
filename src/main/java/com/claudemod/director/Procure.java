package com.claudemod.director;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.claudemod.entity.ClaudeEntity;
import com.claudemod.entity.life.Crafting;
import com.claudemod.entity.life.Skills;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Blocks;

/**
 * How to get a thing from nothing, the way a player works it out in his head: have it? craft it (ingredients first), smelt it
 * (input, fuel and a furnace first), or go get it (the right pickaxe first, then mine, chop or hunt). Turns one wish ("an iron
 * pickaxe") into the ordered skill steps that get there, against what he's carrying right now. The steps are the ordinary
 * skills, so a mine is still dug in its pattern and a tree still comes down whole: this only decides what to do, never how.
 * Anything beyond him for now (the nether) comes back as a plain reason instead of a plan.
 */
public final class Procure {
    private Procure() {}

    /** The steps, in order; or why there is no way (steps empty). */
    public record Plan(List<Skills.Step> steps, String problem, Map<Item, Integer> fromChests) { // fromChests: what it counts on from his chests (his ledger), for the fetch line
        public Plan(List<Skills.Step> steps, String problem) { this(steps, problem, Map.of()); }
    }

    /** Where a raw thing comes from: a skill and its target, the pickaxe tier it takes (-1 none), or a reason he can't yet. */
    private record Source(String skill, String target, int tier, String notYet) {}

    private static final Map<Item, Source> SOURCES = new HashMap<>();
    // ponytail: two world sources ride in the plan's item counts under items he never carries, so a plan that backs out restores them
    private static final Item HAY_IN_SIGHT = Items.STRUCTURE_VOID, GOLEM_READY = Items.IRON_GOLEM_SPAWN_EGG;
    private static final Item[] PICKS = {Items.WOODEN_PICKAXE, Items.STONE_PICKAXE, Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE}; // by tier

    private static void gather(String target, int tier, Item... items) { for (Item i : items) SOURCES.put(i, new Source("collect", target, tier, null)); }
    private static void ore(String target, int tier, Item... items) { for (Item i : items) SOURCES.put(i, new Source("strip_mine", target, tier, null)); }
    private static void hunt(String drop, Item... items) { for (Item i : items) SOURCES.put(i, new Source("collect", drop, -1, null)); }
    private static void fight(String mob, Item... items) { for (Item i : items) SOURCES.put(i, new Source("fight", mob, -1, null)); }
    private static void notYet(String why, Item... items) { for (Item i : items) SOURCES.put(i, new Source(null, null, -1, why)); }

    static {
        gather("logs", -1, Items.OAK_LOG, Items.SPRUCE_LOG, Items.BIRCH_LOG, Items.JUNGLE_LOG, Items.ACACIA_LOG, Items.DARK_OAK_LOG, Items.MANGROVE_LOG, Items.CHERRY_LOG);
        gather("cobblestone", 0, Items.COBBLESTONE, Items.COBBLED_DEEPSLATE);
        gather("dirt", -1, Items.DIRT);
        gather("sand", -1, Items.SAND);
        gather("gravel", -1, Items.GRAVEL, Items.FLINT);
        SOURCES.put(Items.OBSIDIAN, new Source("make_obsidian", "obsidian", 3, null)); // made at lava (water over it, a diamond pick): never a portal's frame (04:55)
        ore("coal_ore", 0, Items.COAL);
        ore("iron_ore", 1, Items.RAW_IRON);
        ore("copper_ore", 1, Items.RAW_COPPER);
        ore("lapis_ore", 1, Items.LAPIS_LAZULI);
        ore("gold_ore", 2, Items.RAW_GOLD);
        ore("redstone_ore", 2, Items.REDSTONE);
        ore("diamond_ore", 2, Items.DIAMOND);
        ore("emerald_ore", 2, Items.EMERALD);
        hunt("cod", Items.COD); hunt("salmon", Items.SALMON); hunt("chicken", Items.CHICKEN, Items.FEATHER); // fish and chickens are food sources too: "no way to get cod" with 22 raw cod in his pack never got cooked
        hunt("beef", Items.BEEF); hunt("leather", Items.LEATHER); hunt("porkchop", Items.PORKCHOP); hunt("mutton", Items.MUTTON);
        for (net.minecraft.world.item.DyeColor c : net.minecraft.world.item.DyeColor.values()) hunt(c.getName() + "_wool", net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.withDefaultNamespace(c.getName() + "_wool"))); // any colour of wool comes off a sheep of that colour, never through a dye hunt("chicken", Items.CHICKEN); hunt("feather", Items.FEATHER);
        fight("spider", Items.SPIDER_EYE); fight("skeleton", Items.BONE); fight("zombie", Items.ROTTEN_FLESH);
        SOURCES.put(Items.STRING, new Source("get_string", "string", -1, null));
        SOURCES.put(Items.ARROW, new Source("get_arrows", "arrow", -1, null)); // the same shape: his chest, a skeleton he senses and is fit for, flint and feathers crafted, else it waits (never "fight skeleton 24" by day) // what's to hand (a cobweb in sight, a spider he senses and is fit for, his chest a short walk off), else it waits: never a fight queued with no spider about, never a daylight hunt (live 13:05)
        fight("creeper", Items.GUNPOWDER); fight("enderman", Items.ENDER_PEARL); fight("slime", Items.SLIME_BALL);
        SOURCES.put(Items.BLAZE_ROD, new Source("blaze_rods", "blaze_rod", -1, null)); // a fortress's blazes, fought from cover (FortressFight.blazeRods)
        SOURCES.put(Items.NETHER_WART, new Source("loot", "fortress", -1, null)); // a fortress's ripe wart, with its chests
        notYet("the nether (build_portal, then enter_portal: past the portal there's no nether work yet)", Items.NETHERRACK, Items.QUARTZ, Items.GLOWSTONE_DUST,
                Items.ANCIENT_DEBRIS, Items.SOUL_SAND, Items.GHAST_TEAR, Items.MAGMA_CREAM);
        notYet("the end, and there's no way there yet", Items.END_STONE, Items.CHORUS_FRUIT, Items.SHULKER_SHELL, Items.DRAGON_BREATH);
    }

    public static Plan plan(ClaudeEntity g, ServerLevel level, Item target, int count) { return planAll(g, level, Map.of(target, count)); }

    /** Several wants in one trip (a whole kit): the pickaxe, table and furnace are shared, the trips merged. */
    public static Plan planAll(ClaudeEntity g, ServerLevel level, Map<Item, Integer> wants) {
        if (!SOURCES.containsKey(Items.COD)) { hunt("cod", Items.COD); hunt("salmon", Items.SALMON); } // (live: the static block above doesn't re-run on a hot push)
        if (!SOURCES.containsKey(Items.CHICKEN)) hunt("chicken", Items.CHICKEN, Items.FEATHER);
        if (SOURCES.get(Items.STRING) == null || !"get_string".equals(SOURCES.get(Items.STRING).skill())) SOURCES.put(Items.STRING, new Source("get_string", "string", -1, null)); // (live: the static block doesn't re-run on a hot push)
        if (SOURCES.get(Items.ARROW) == null || !"get_arrows".equals(SOURCES.get(Items.ARROW).skill())) SOURCES.put(Items.ARROW, new Source("get_arrows", "arrow", -1, null));
        if (SOURCES.get(Items.BLAZE_ROD) == null || !"blaze_rods".equals(SOURCES.get(Items.BLAZE_ROD).skill())) { SOURCES.put(Items.BLAZE_ROD, new Source("blaze_rods", "blaze_rod", -1, null)); SOURCES.put(Items.NETHER_WART, new Source("loot", "fortress", -1, null)); } // (live: the static block doesn't re-run on a hot push)
        Solver s = new Solver(g, level);
        for (Map.Entry<Item, Integer> e : wants.entrySet()) {
            String problem = s.need(e.getKey(), e.getValue(), Set.of(), 0);
            if (problem != null) return new Plan(List.of(), problem);
        }
        return new Plan(merge(s.steps), null, s.fromChests(g));
    }

    /** "collect logs 3, craft stick 2, craft wooden_pickaxe 1" */
    public static String describe(List<Skills.Step> steps) {
        StringBuilder sb = new StringBuilder();
        for (Skills.Step st : steps) sb.append(sb.length() == 0 ? "" : ", ").append(st.skill()).append(' ').append(st.target()).append(' ').append(st.count());
        return sb.toString();
    }

    /** The same skill for the same thing twice (sticks for two tools) is one trip: summed into the first time it comes up. */
    static List<Skills.Step> merge(List<Skills.Step> raw) {
        // ponytail: merging a craft forward is safe as long as its inputs' first trip is earlier too (true for how need() walks);
        // where it isn't, the craft skill re-plans the shortfall on the spot, so nothing is lost
        List<Skills.Step> out = new ArrayList<>();
        for (Skills.Step st : raw) {
            int at = -1;
            for (int i = 0; i < out.size(); i++) if (out.get(i).skill().equals(st.skill()) && out.get(i).target().equals(st.target())) { at = i; break; }
            if (at < 0) out.add(st);
            else out.set(at, new Skills.Step(st.skill(), st.target(), out.get(at).count() + st.count()));
        }
        return out;
    }

    private static final class Solver {
        private final ServerLevel level;
        private Map<Item, Integer> have = new HashMap<>(); // what's in his pockets, used up as the plan spends it
        private boolean table, furnace;
        final List<Skills.Step> steps = new ArrayList<>();

        Solver(ClaudeEntity g, ServerLevel level) {
            this.level = level;
            for (int i = 0; i < g.inventory.items.getContainerSize(); i++) {
                ItemStack s = g.inventory.items.getItem(i);
                if (!s.isEmpty()) have.merge(s.getItem(), s.getCount(), Integer::sum);
            }
            pocket.putAll(have);
            ClaudeLife.stored(g, level).forEach((k, v) -> have.merge(k, v, Integer::sum));
            have.put(Items.STRING, pocket.getOrDefault(Items.STRING, 0)); have.put(Items.ARROW, pocket.getOrDefault(Items.ARROW, 0)); // (arrows too: get_arrows fetches) // string in his chests is get_string's to fetch (a short walk, or any once the bow's a must): 1 string 313 blocks off was counted, and the craft walked there for it
            start.putAll(have); // his chests and furnaces count too (the craft/smelt skills fetch from them): no "mine 1 more ore" with 15 ingots in the chest
            // his own stations a walk off count too (the craft walks back to them): he climbed out of his mine at y -58 for a log to make a table, his mine's table 20 blocks down the tunnel
            boolean ownTable = false, ownFurnace = false;
            for (net.minecraft.core.BlockPos p : g.myStations) { if (p.distSqr(g.blockPosition()) > 64 * 64 || !level.isLoaded(p)) continue; if (level.getBlockState(p).is(Blocks.CRAFTING_TABLE)) ownTable = true; if (level.getBlockState(p).is(Blocks.FURNACE)) ownFurnace = true; }
            int bales0 = ClaudeLife.villageHayInSight(g, level); if (bales0 > 0) have.put(HAY_IN_SIGHT, bales0); // a village's bales and golem, counted like items so a plan that backs out puts them back
            if (ClaudeLife.golemReady(g, level) != null) have.put(GOLEM_READY, 1);
            table = ownTable || ClaudeLife.findNear(level, g.blockPosition(), 16, b -> b.is(Blocks.CRAFTING_TABLE)) != null || have.getOrDefault(Items.CRAFTING_TABLE, 0) > 0;
            furnace = ownFurnace || ClaudeLife.findNear(level, g.blockPosition(), 16, b -> b.is(Blocks.FURNACE)) != null || have.getOrDefault(Items.FURNACE, 0) > 0;
        }

        private final Map<Item, Integer> pocket = new HashMap<>(), start = new HashMap<>();
        /** What the plan used past his pockets that his chests hold (by his ledger). */
        Map<Item, Integer> fromChests(ClaudeEntity g) {
            Map<Item, Integer> out = new java.util.LinkedHashMap<>();
            for (var e : com.claudemod.entity.life.ChestLedger.totals(g, level).entrySet()) {
                int used = start.getOrDefault(e.getKey(), 0) - have.getOrDefault(e.getKey(), 0) - pocket.getOrDefault(e.getKey(), 0), d = Math.min(e.getValue(), used);
                if (d > 0) out.put(e.getKey(), d);
            }
            return out;
        }

        /** Gets n of item into the plan. Null when it worked, else why not. */
        String need(Item item, int n, Set<Item> path, int depth) {
            int h = have.getOrDefault(item, 0);
            int take = Math.min(h, n);
            if (take > 0) have.put(item, h - take);
            n -= take;
            if (n == 0) return null;
            if (item == Items.BREAD && have.getOrDefault(HAY_IN_SIGHT, 0) > 0) { // a village's hay bales in sight: one village_food (the bales, the wheat, the bread), 3 a bale
                int bales = have.remove(HAY_IN_SIGHT);
                steps.add(new Skills.Step("village_food", "", bales));
                n -= Math.min(n, bales * 3);
                if (n == 0) return null;
            }
            if (item == Items.IRON_INGOT && have.getOrDefault(GOLEM_READY, 0) > 0) { // a golem in sight he's fit to fight: 3-5 iron from the 3-high tower beside it, quicker than any strip mine
                have.remove(GOLEM_READY);
                steps.add(new Skills.Step("village_iron", "", 1));
                n -= Math.min(n, 3);
                if (n == 0) return null;
            }
            if (depth > 8 || path.contains(item)) return "no way to get " + Crafting.name(item);
            Set<Item> deeper = new HashSet<>(path);
            deeper.add(item);
            Source src = SOURCES.get(item);
            if (src != null) {
                if (src.notYet() != null) return Crafting.name(item) + " comes from " + src.notYet();
                if (src.tier() >= 0) { String r = needPickaxe(src.tier(), deeper, depth + 1); if (r != null) return r; }
                steps.add(new Skills.Step(src.skill(), src.target(), n)); // (the skill brings in at least n; nothing counted on beyond that)
                return null;
            }
            String why = null;
            String outName = Crafting.name(item), family = outName.contains("_") ? outName.substring(outName.lastIndexOf('_')) : null;
            recipes: for (RecipeHolder<CraftingRecipe> holder : Crafting.recipesFor(level, item)) {
                CraftingRecipe r = holder.value();
                if (family != null) for (Ingredient ing : r.getIngredients()) for (net.minecraft.world.item.ItemStack st : ing.getItems()) { // a dye on a bed, wool or carpet is the thing itself in another colour: never a way to make one
                    String nm = Crafting.name(st.getItem());
                    if (nm.endsWith(family) && !nm.equals(outName)) continue recipes;
                }
                int out = Math.max(1, r.getResultItem(level.registryAccess()).getCount());
                int crafts = (n + out - 1) / out;
                Map<Item, Integer> snap = new HashMap<>(have);
                int mark = steps.size();
                String rr = Crafting.isBig(r) ? needTable(deeper, depth + 1) : null;
                if (rr == null) for (Ingredient ing : r.getIngredients()) {
                    if (ing.isEmpty()) continue;
                    rr = needAny(ing, crafts, deeper, depth + 1);
                    if (rr != null) break;
                }
                if (rr == null) {
                    steps.add(new Skills.Step("craft", Crafting.name(item), crafts * out)); // what the crafts make (the skill's count is items), so merged trips add up right
                    if (crafts * out > n) have.merge(item, crafts * out - n, Integer::sum); // the extra sticks stay in his pocket
                    return null;
                }
                have = snap;
                steps.subList(mark, steps.size()).clear();
                if (why == null) why = rr;
            }
            for (var holder : level.getRecipeManager().getAllRecipesFor(RecipeType.SMELTING)) {
                var r = holder.value();
                if (!r.getResultItem(level.registryAccess()).is(item) || r.getIngredients().isEmpty()) continue;
                if (java.util.Arrays.stream(r.getIngredients().get(0).getItems()).allMatch(net.minecraft.world.item.ItemStack::isDamageableItem)) continue; // (never his gear into the furnace: live 13:17 World 15 his iron swords smelted into nuggets for a shield)
                Map<Item, Integer> snap = new HashMap<>(have);
                int mark = steps.size();
                String rr = needFurnace(deeper, depth + 1);
                if (rr == null) rr = needAny(r.getIngredients().get(0), n, deeper, depth + 1);
                if (rr == null) rr = needFuel(n, deeper, depth + 1);
                if (rr == null) {
                    steps.add(new Skills.Step("smelt", Crafting.name(item), n));
                    return null;
                }
                have = snap;
                steps.subList(mark, steps.size()).clear();
                if (why == null) why = rr;
            }
            return why != null ? why : "no way to get " + Crafting.name(item);
        }

        /** One of the things a recipe slot accepts (any plank, any log): what he has first, then whatever can be got. */
        private String needAny(Ingredient ing, int n, Set<Item> path, int depth) {
            List<Item> options = new ArrayList<>();
            for (ItemStack s : ing.getItems()) if (have.getOrDefault(s.getItem(), 0) > 0) options.add(s.getItem());
            for (ItemStack s : ing.getItems()) if (!options.contains(s.getItem())) options.add(s.getItem());
            if (options.contains(Items.CHARCOAL) && options.contains(Items.COAL) && have.getOrDefault(Items.COAL, 0) < n) { int logs = 0; for (var e : have.entrySet()) if (new ItemStack(e.getKey()).is(net.minecraft.tags.ItemTags.LOGS)) logs += e.getValue(); if (logs >= n) { options.remove(Items.CHARCOAL); options.add(0, Items.CHARCOAL); } } // logs on him: charcoal from the furnace, never a climb to the coal level (75 blocks up his stairs for 3 coal, 9 logs in his pack)
            String why = null;
            Map<Item, Integer> snap0 = new HashMap<>(have), okHave = null; List<Skills.Step> okSteps = null; Item okOpt = null;
            boolean table0 = table, furnace0 = furnace, okTable = false, okFurnace = false; // (a table planned in an option that's dropped isn't planned)
            int mark = steps.size();
            for (Item opt : options) {
                have = new HashMap<>(snap0); table = table0; furnace = furnace0;
                steps.subList(mark, steps.size()).clear();
                String r = need(opt, n, path, depth);
                if (r == null) {
                    boolean trip = steps.subList(mark, steps.size()).stream().anyMatch(s -> !s.skill().equals("craft") && !s.skill().equals("smelt"));
                    if (!trip) { // made from what he carries: this one (the tag's first, oak planks, sent him 217 blocks up for a log with 46 birch logs on him)
                        if (okOpt != null) com.claudemod.ClaudeMod.LOGGER.info("[Claude procure] {} for it from what he carries, not {} ({}) (Procure.needAny)", Crafting.name(opt), Crafting.name(okOpt), describe(okSteps));
                        return null;
                    }
                    if (okOpt == null) { okHave = new HashMap<>(have); okSteps = new ArrayList<>(steps.subList(mark, steps.size())); okOpt = opt; okTable = table; okFurnace = furnace; }
                    continue;
                }
                if (why == null) why = r;
            }
            have = snap0; table = table0; furnace = furnace0;
            steps.subList(mark, steps.size()).clear();
            if (okOpt != null) { have = okHave; table = okTable; furnace = okFurnace; steps.addAll(okSteps); return null; } // every way needs a trip: the first that works, as before
            return why != null ? why : "nothing fits";
        }

        /** A pickaxe good enough for the block (the cheapest that is), kept for the rest of the plan. */
        private String needPickaxe(int tier, Set<Item> path, int depth) {
            for (Map.Entry<Item, Integer> e : have.entrySet())
                if (e.getValue() > 0 && e.getKey() instanceof PickaxeItem && tierOf(e.getKey()) >= tier) return null;
            Item pick = PICKS[Math.min(tier, PICKS.length - 1)];
            String r = need(pick, 1, path, depth);
            if (r == null) have.merge(pick, 1, Integer::sum); // a tool is kept, not spent
            return r;
        }

        private static int tierOf(Item pick) {
            for (int i = 0; i < PICKS.length; i++) if (PICKS[i] == pick) return i;
            return pick == Items.NETHERITE_PICKAXE || pick == Items.GOLDEN_PICKAXE ? (pick == Items.GOLDEN_PICKAXE ? 0 : 3) : 0;
        }

        private String needTable(Set<Item> path, int depth) {
            if (table) return null;
            String r = need(Items.CRAFTING_TABLE, 1, path, depth); // the craft skill puts it down when it gets there
            if (r == null) table = true;
            return r;
        }

        private String needFurnace(Set<Item> path, int depth) {
            if (furnace) return null;
            String r = need(Items.FURNACE, 1, path, depth); // the smelt skill puts it down when it gets there
            if (r == null) furnace = true;
            return r;
        }

        /** Coal from his pocket first (8 a piece); a small batch burns logs (1.5 each); a big one is worth mining coal for. */
        private String needFuel(int items, Set<Item> path, int depth) {
            int coal = have.getOrDefault(Items.COAL, 0) + have.getOrDefault(Items.CHARCOAL, 0);
            int wantCoal = (items + 7) / 8;
            if (coal >= wantCoal) {
                int fromCoal = Math.min(wantCoal, have.getOrDefault(Items.COAL, 0));
                have.merge(Items.COAL, -fromCoal, Integer::sum);
                have.merge(Items.CHARCOAL, -(wantCoal - fromCoal), Integer::sum);
                return null;
            }
            if (items > 16) return need(Items.COAL, wantCoal, path, depth);
            return needAny(Ingredient.of(ItemTags.LOGS), (int) Math.ceil(items / 1.5), path, depth);
        }
    }
}
