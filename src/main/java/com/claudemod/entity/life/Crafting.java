package com.claudemod.entity.life;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.level.Level;

/**
 * Real crafting from Minecraft's recipe book, against Claude's inventory. Crafts missing
 * intermediates (planks, sticks...) on the way, a few levels deep, like a player would.
 */
public final class Crafting {
    private Crafting() {}

    /** Outcome of one attempt: how many were made, or what was missing. */
    public record Result(int made, String missing, boolean needsTable) {}

    public static Result craft(Level level, ClaudeInventory inv, Item target, int count, boolean hasTable) {
        int made = 0;
        String missing = null;
        boolean needsTable = false;
        while (made < count) {
            Result r = craftOnce(level, inv, target, hasTable, 0, java.util.Set.of(target));
            if (r.made() == 0) { missing = r.missing(); needsTable = r.needsTable(); break; }
            made += r.made();
        }
        return new Result(made, missing, needsTable);
    }

    /** True if every recipe for this item needs a 3x3 grid. */
    public static boolean needsTable(Level level, Item target) {
        List<RecipeHolder<CraftingRecipe>> rs = recipesFor(level, target);
        if (rs.isEmpty()) return false;
        for (RecipeHolder<CraftingRecipe> h : rs) if (!isBig(h.value())) return false;
        return true;
    }

    /** path: items being crafted further up; never used as an ingredient source (ingot -> nuggets -> ingot froze the server). */
    private static Result craftOnce(Level level, ClaudeInventory inv, Item target, boolean hasTable, int depth, java.util.Set<Item> path) {
        List<RecipeHolder<CraftingRecipe>> recipes = recipesFor(level, target);
        if (recipes.isEmpty()) return new Result(0, "there is no crafting recipe for " + name(target), false);
        String firstMissing = null;
        boolean tableProblem = false;
        for (RecipeHolder<CraftingRecipe> h : recipes) {
            CraftingRecipe recipe = h.value();
            if (isBig(recipe) && !hasTable) { tableProblem = true; continue; }
            List<Ingredient> needed = new ArrayList<>();
            for (Ingredient ing : recipe.getIngredients()) if (!ing.isEmpty()) needed.add(ing);
            // Make missing intermediates first (one level at a time).
            boolean ok = true;
            for (Ingredient ing : needed) {
                int have = inv.count(ing::test);
                int want = (int) needed.stream().filter(o -> o == ing || sameAs(o, ing)).count();
                if (have >= want) continue;
                boolean madeOne = false;
                if (depth < 3) {
                    for (ItemStack option : ing.getItems()) {
                        Item sub = option.getItem();
                        if (path.contains(sub)) continue;
                        var subPath = new java.util.HashSet<>(path);
                        subPath.add(sub);
                        while (inv.count(ing::test) < want) {
                            int before = inv.count(ing::test);
                            if (craftOnce(level, inv, sub, hasTable, depth + 1, subPath).made() == 0) break;
                            madeOne = true;
                            if (inv.count(ing::test) <= before) break; // no net gain: a conversion loop, stop
                        }
                        if (inv.count(ing::test) >= want) break;
                    }
                }
                if (inv.count(ing::test) < want) {
                    ok = false;
                    if (firstMissing == null) firstMissing = describe(ing) + (madeOne ? " (made some, not enough)" : "");
                    break;
                }
            }
            if (!ok) continue;
            ItemStack out = recipe.getResultItem(level.registryAccess()).copy();
            if (!inv.hasRoomFor(out)) return new Result(0, "room in my pack (it's full)", false); // before the ingredients go: a full pack ate 12 planks for three tables that never existed
            for (Ingredient ing : needed) inv.remove(ing::test, 1);
            int n = out.getCount();
            ItemStack rest = inv.add(out);
            return new Result(n - rest.getCount(), null, false);
        }
        if (firstMissing == null && tableProblem) return new Result(0, "a crafting table nearby", true);
        return new Result(0, firstMissing == null ? "ingredients" : firstMissing, tableProblem);
    }

    private static boolean sameAs(Ingredient a, Ingredient b) {
        ItemStack[] x = a.getItems(), y = b.getItems();
        return x.length > 0 && y.length > 0 && x[0].getItem() == y[0].getItem() && x.length == y.length;
    }

    public static List<RecipeHolder<CraftingRecipe>> recipesFor(Level level, Item target) {
        List<RecipeHolder<CraftingRecipe>> out = new ArrayList<>();
        String outName = name(target), family = outName.contains("_") ? outName.substring(outName.lastIndexOf('_')) : null;
        recipes: for (RecipeHolder<CraftingRecipe> h : level.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
            CraftingRecipe r = h.value();
            if (r.getIngredients().isEmpty()) continue; // special recipes (map cloning, dyes on armor...)
            if (r.getResultItem(level.registryAccess()).getItem() != target) continue;
            if (family != null) for (Ingredient ing : r.getIngredients()) for (ItemStack st : ing.getItems()) { // a dye on a bed, wool or carpet is the thing itself in another colour: never a way to make one
                String nm = name(st.getItem());
                if (nm.endsWith(family) && !nm.equals(outName)) continue recipes;
            }
            out.add(h);
        }
        return out;
    }

    public static boolean isBig(CraftingRecipe r) {
        if (r instanceof ShapedRecipe s) return s.getWidth() > 2 || s.getHeight() > 2;
        return r.getIngredients().stream().filter(i -> !i.isEmpty()).count() > 4;
    }

    private static String describe(Ingredient ing) {
        ItemStack[] items = ing.getItems();
        return items.length == 0 ? "something" : name(items[0].getItem()) + (items.length > 1 ? " (or similar)" : "");
    }

    public static String name(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).getPath();
    }
}
