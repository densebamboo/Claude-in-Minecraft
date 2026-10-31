package com.claudemod.entity.life;

import java.util.List;

/** One step of a plan Claude made: a skill with a target ("oak_log", "iron_pickaxe", "them") and a count. */
public final class Skills {
    private Skills() {}

    /** where: near_you (around Claude), near_them (around the player), looked_at (what the player is looking at). */
    public record Step(String skill, String target, int count, String where) {
        public Step(String skill, String target, int count) { this(skill, target, count, "near_you"); }
    }

    /** Ordinary player skills, always available. (A method, not a constant: a constant's value survives a live code push, a method body doesn't.) */
    public static List<String> player() {
        return List.of(
            "obtain", "gear_up", "shelter", "sleep", "collect", "dig_down", "strip_mine", "explore_cave", "explore", "craft", "smelt", "build_my_place", "plant_saplings", "adopt_place", "start_mine", "retrieve_stash", "build_here", "watch_build", "copy_me", "build_blueprint", "farm", "harvest", "fish", "loot", "blaze_rods", "buried_treasure", "village_food", "village_beds", "village_iron", "make_obsidian", "build_portal", "complete_portal", "enter_portal", "find_fortress",
            "fight", "eat", "light_up", "light_mine", "return_drops", "recover_drops", "recover_left", "pick_up", "dig_at", "place_at", "scoop_at", "drop_down", "move", "ask", "carve", "store", "wear", "take_off", "take_from_chest", "give", "place", "go_to", "follow", "wait");
    }

    public static final List<String> PLAYER = player();

}
