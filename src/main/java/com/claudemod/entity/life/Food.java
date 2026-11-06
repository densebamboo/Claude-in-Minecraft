package com.claudemod.entity.life;

import com.claudemod.entity.ClaudeEntity;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;

/**
 * Food as a player reckons it, vanilla's numbers (the one food function, agreed with Loops 11-05: their regen prices and
 * runKit's food rule, Kit's food want and Leaps' HP price all read it instead of their own constants).
 * Exhaustion: 4 is a food point (saturation first, then hunger); a sprinted block 0.1, a sprint-jump 0.2, a jump 0.05, a block
 * mined 0.005, a hit dealt 0.1, damage taken 0.1 an HP, swimming 0.01 a block, and every HP healed 6.
 * Regen: food 20 with saturation, 1 HP every 0.5 s (6 exhaustion each); food 18+, 1 HP every 4 s; under 18, none.
 */
public final class Food {
    private static double w(String name, double def) { return Weights.w(name, def); }

    /** A stretch of his time: seconds sprinting and walking, jumps (sprint-jumps among them), blocks mined, hits dealt, HP he expects to lose (healed back), blocks swum. */
    public record Stretch(double sprintS, double walkS, int jumps, int sprintJumps, int digs, int hits, double hpLost, double swimBlocks) {
        public static Stretch of(double sprintS, int digs, int hits, double hpLost) { return new Stretch(sprintS, 0, 0, 0, digs, hits, hpLost, 0); }
    }

    /** Food points (hunger and saturation alike) the stretch burns, the HP it costs healed back included. */
    public static double points(Stretch st) {
        double ex = st.sprintS() * Decide.sprint() * w("food.ex_sprint_block", 0.1) + st.jumps() * w("food.ex_jump", 0.05) + st.sprintJumps() * w("food.ex_sprint_jump", 0.2)
                + st.digs() * w("food.ex_dig", 0.005) + st.hits() * w("food.ex_attack", 0.1) + st.hpLost() * (w("food.ex_damage_hp", 0.1) + w("food.ex_heal_hp", 6))
                + st.swimBlocks() * w("food.ex_swim_block", 0.01);
        return ex / 4.0;
    }

    /** The food in a stack (null: not food). */
    public static FoodProperties props(ItemStack s) { return s.isEmpty() ? null : s.get(DataComponents.FOOD); }
    /** A meal of his best food on him as food points (hunger + saturation); food.meal_points when he has none (a cooked beef's 20.8). */
    public static double perMeal(ClaudeEntity g) {
        double best = 0; for (int i = 0; i < g.getInventory().getContainerSize(); i++) { var f = props(g.getInventory().getItem(i)); if (f != null && !bad(g.getInventory().getItem(i))) best = Math.max(best, f.nutrition() + f.saturation()); }
        return best > 0 ? best : w("food.meal_points", 20.8);
    }
    /** Food he'd rather not eat: rotten flesh, spider eyes, raw chicken, pufferfish, poisonous potatoes (the stomach's or the poison's price). */
    static boolean bad(ItemStack s) { var i = s.getItem(); return i == net.minecraft.world.item.Items.ROTTEN_FLESH || i == net.minecraft.world.item.Items.SPIDER_EYE || i == net.minecraft.world.item.Items.CHICKEN || i == net.minecraft.world.item.Items.PUFFERFISH || i == net.minecraft.world.item.Items.POISONOUS_POTATO; }

    /** Meals of his best food the stretch needs past what's in him now above the regen line (food 18): 0 when he's fed for it. */
    public static double meals(ClaudeEntity g, Stretch st) {
        var fd = g.getFoodData(); double inHim = Math.max(0, fd.getFoodLevel() - 18) + fd.getSaturationLevel() - fd.getExhaustionLevel() / 4.0;
        return Math.max(0, points(st) - inHim) / perMeal(g);
    }

    /**
     * Seconds to win back hp HP from now, eating what's on him as a player does (topping up the moment saturation's gone and
     * hunger's under 20, each bite its own eating time): vanilla's regen stepped HP by HP. With nothing left to eat under food 18, every HP
     * still to go costs food.no_food_hp_s (finding food first): a price, never a refusal.
     */
    public static double regenS(ClaudeEntity g, double hp) { var fd = g.getFoodData(); return regenS(g, hp, fd.getFoodLevel(), fd.getSaturationLevel()); }
    /** The same from the given food and saturation (a what-if: fed after gear_up, HuntPatrol.fightS). */
    public static double regenS(ClaudeEntity g, double hp, double food, double sat) {
        double t = 0;
        java.util.List<FoodProperties> bag = new java.util.ArrayList<>();
        for (int i = 0; i < g.getInventory().getContainerSize(); i++) { ItemStack s = g.getInventory().getItem(i); var f = props(s); if (f != null && !bad(s)) for (int k = 0; k < s.getCount() && bag.size() < 64; k++) bag.add(f); }
        bag.sort((a, b) -> Double.compare(b.nutrition() + b.saturation(), a.nutrition() + a.saturation()));
        int n = (int) Math.ceil(Math.max(0, hp));
        for (int h = 0; h < n; h++) {
            while ((food < 18 || food < 20 && sat <= 0) && !bag.isEmpty()) { var f = bag.remove(0); food = Math.min(20, food + f.nutrition()); sat = Math.min(food, sat + f.saturation()); t += f.eatSeconds(); }
            if (food >= 20 && sat > 0) t += 0.5;
            else if (food >= 18) t += 4;
            else return t + (n - h) * w("food.no_food_hp_s", 60);
            double cost = 1.5; double fromSat = Math.min(sat, cost); sat -= fromSat; food -= cost - fromSat; // (6 exhaustion an HP: saturation first)
        }
        return t;
    }
    /** Seconds an HP over the next hp HP (regenS / hp): the per-HP price the decisions use. */
    public static double sPerHp(ClaudeEntity g, double hp) { var fd = g.getFoodData(); return sPerHp(g, hp, fd.getFoodLevel(), fd.getSaturationLevel()); }
    public static double sPerHp(ClaudeEntity g, double hp, double food, double sat) { return hp <= 0 ? 0 : regenS(g, hp, food, sat) / Math.max(1, Math.ceil(hp)); }
}
