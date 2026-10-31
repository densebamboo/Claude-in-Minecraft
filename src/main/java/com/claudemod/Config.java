package com.claudemod;

import net.neoforged.neoforge.common.ModConfigSpec;

public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue ENABLE_LLM = BUILDER
            .comment("Master switch for Claude's AI. If false, Claude does not think, plan or talk.")
            .define("enableLLM", true);

    public static final ModConfigSpec.ConfigValue<String> ANTHROPIC_API_KEY = BUILDER
            .comment("Your Anthropic API key. Get one at https://platform.claude.com/settings/keys",
                    "Stored in plaintext in this config file -- do not share or commit.")
            .define("anthropicApiKey", "");

    public static final ModConfigSpec.ConfigValue<String> CHAT_MODEL = BUILDER
            .comment("Model for talking to you in chat (and turning what you say into steps), and for his simple plans. It answers every message.")
            .define("chatModel", "claude-sonnet-5-5");

    public static final ModConfigSpec.ConfigValue<String> PLAN_MODEL = BUILDER
            .comment("Model for his hard reasoning: multi-step plans with something to work out, and his notes on you.")
            .define("planModel", "claude-sonnet-5-5");

    public static final ModConfigSpec.IntValue REQUEST_TIMEOUT_MS = BUILDER
            .comment("HTTP timeout for Claude requests, milliseconds.")
            .defineInRange("requestTimeoutMs", 20000, 1000, 60000);

    public static final ModConfigSpec.BooleanValue ENABLE_SPAWN = BUILDER
            .comment("If true, Claude joins your world near you a few seconds after you load in.")
            .define("enableSpawn", true);

    public static final ModConfigSpec.IntValue CHAT_COOLDOWN_SECONDS = BUILDER
            .comment("Minimum seconds between Claude's chat replies (messages in between are answered after).")
            .defineInRange("chatCooldownSeconds", 3, 0, 60);

    public static final ModConfigSpec.BooleanValue DEBUG_LOGGING = BUILDER
            .comment("Extra logs (token counts, notes). The important lines are always logged.")
            .define("debugLogging", false);

    static final ModConfigSpec SPEC = BUILDER.build();
}
