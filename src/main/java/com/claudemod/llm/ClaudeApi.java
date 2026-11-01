package com.claudemod.llm;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import com.claudemod.Config;
import com.claudemod.ClaudeMod;
import com.claudemod.context.PlayerContext;
import com.claudemod.context.PlayerProfile;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.server.level.ServerPlayer;

public final class ClaudeApi {
    private static final String ENDPOINT = "https://api.anthropic.com/v1/messages";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private static final int MEMORY_LINES = 24;
    private static final Map<UUID, Deque<String[]>> MEMORY = new ConcurrentHashMap<>();

    private ClaudeApi() {}

    /** The player said something to him (directed = /claude ask, otherwise chat near him). */
    public static CompletableFuture<JsonObject> conversation(ServerPlayer player, String message, String truthBlock, boolean directed) {
        return complete(player, truthBlock, Persona.CONVERSATION_GUIDE, Persona.conversationTurn(player.getGameProfile().getName(), message, directed));
    }

    /** Records a line in the per-player memory Claude sees on every call. Cleared on restart. */
    public static void remember(ServerPlayer player, String speaker, String line) {
        Deque<String[]> log = memory(player);
        synchronized (log) {
            log.addLast(new String[] {String.valueOf(System.currentTimeMillis()), speaker, line.replace('\t', ' ').replace('\n', ' ')});
            while (log.size() > MEMORY_LINES) log.removeFirst();
            StringBuilder sb = new StringBuilder();
            for (String[] e : log) sb.append(e[0]).append('\t').append(e[1]).append('\t').append(e[2]).append('\n');
            PlayerProfile.saveText(player, "memory", sb.toString());
        }
    }

    /** The conversation so far, loaded from the world folder the first time (it survives restarts). */
    private static Deque<String[]> memory(ServerPlayer player) {
        return MEMORY.computeIfAbsent(player.getUUID(), k -> {
            Deque<String[]> log = new ArrayDeque<>();
            for (String l : PlayerProfile.loadText(player, "memory").split("\n")) {
                String[] parts = l.split("\t", 3);
                if (parts.length == 3) log.addLast(parts);
            }
            return log;
        });
    }

    /** When Claude last showed the player anything (ms), or 0. */
    public static long lastSpokeAt(ServerPlayer player) {
        Deque<String[]> log = MEMORY.get(player.getUUID());
        if (log == null) return 0L;
        synchronized (log) {
            long last = 0L;
            for (String[] e : log) if (e[1].startsWith("you")) last = Long.parseLong(e[0]);
            return last;
        }
    }

    /** True if Claude already said this line (or one starting with the same four words) recently. */
    public static boolean saidBefore(ServerPlayer player, String line) {
        Deque<String[]> log = MEMORY.get(player.getUUID());
        if (log == null) return false;
        String key = openingWords(line);
        synchronized (log) {
            for (String[] e : log) {
                if (e[1].startsWith("you") && openingWords(e[2]).equals(key)) return true;
            }
        }
        return false;
    }

    private static String openingWords(String line) {
        String[] words = line.toLowerCase().replaceAll("[^a-z0-9' ]", " ").trim().split("\\s+");
        return String.join(" ", java.util.Arrays.copyOf(words, Math.min(4, words.length)));
    }

    private static String memoryBlock(ServerPlayer player) {
        Deque<String[]> log = memory(player);
        StringBuilder sb = new StringBuilder("THINGS ALREADY SAID (oldest first):\n");
        if (log == null || log.isEmpty()) return sb.append("  (nothing yet. this is the first time you speak to them.)\n").toString();
        long now = System.currentTimeMillis();
        synchronized (log) {
            for (String[] e : log) {
                long ageMin = (now - Long.parseLong(e[0])) / 60_000L;
                sb.append("  [").append(ageMin == 0 ? "just now" : ageMin + "m ago").append("] ")
                  .append(e[1]).append(": ").append(e[2]).append('\n');
            }
        }
        return sb.toString();
    }

    /** Picks Claude's next activity in his own life. Returns {activity, why}. */
    public static CompletableFuture<JsonObject> plan(ServerPlayer player, String turn, boolean simple) {
        // ponytail: simple job -> the cheap chat model plans it; anything tricky gets the reasoning model
        String model = simple ? Config.CHAT_MODEL.get() : Config.PLAN_MODEL.get();
        if (capturing()) { String name = player.getGameProfile().getName(); capture("chattpl", requestBody(Persona.systemPrompt(name), Persona.CONVERSATION_GUIDE, contextFor(player, com.claudemod.director.ClaudeChat.truthFor(player), Persona.conversationTurn(name, "<<MSG>>", true)), SPEECH_SCHEMA, "high", Config.CHAT_MODEL.get())); } // the chat request of this same moment, their words to be filled in
        ClaudeMod.LOGGER.info("[Claude plan] thinking with {} ({})", model, simple ? "simple job" : "needs reasoning");
        return complete(Persona.systemPrompt(player.getGameProfile().getName()), Persona.LIFE_GUIDE, contextFor(player, "", turn), PLAN_SCHEMA, simple ? "medium" : "high", model) // real planning thinks harder
                .thenApply(text -> JsonParser.parseString(text).getAsJsonObject());
    }

    private static final JsonObject PLAN_SCHEMA = JsonParser.parseString("""
            {"type": "object", "additionalProperties": false, "required": ["goal", "steps", "why"],
             "properties": {
               "goal": {"type": "string"},
               "why": {"type": "string"},
               "steps": {"type": "array", "items": {"type": "object", "additionalProperties": false,
                 "required": ["skill", "target", "count", "where"],
                 "properties": {"skill": {"type": "string"}, "target": {"type": "string"}, "count": {"type": "integer"},
                   "where": {"type": "string", "enum": ["near_you", "near_them", "looked_at", "exact_them", "exact"]}}}}}}
            """).getAsJsonObject();

    /** Rewrites Claude's long-term notes about the player from what they have done. */
    public static CompletableFuture<JsonObject> observe(String notesPrompt) {
        return complete(Persona.OBSERVER_SYSTEM, notesPrompt, READ_SCHEMA, "medium", Config.PLAN_MODEL.get())
                .thenApply(text -> JsonParser.parseString(text).getAsJsonObject());
    }

    /** One chat line Claude sends on his own (reports, remarks). Returns {text}. */
    public static CompletableFuture<JsonObject> line(ServerPlayer player, String truthBlock, String turn) {
        return complete(Persona.systemPrompt(player.getGameProfile().getName()), contextFor(player, truthBlock, turn), LINE_SCHEMA, "high", Config.CHAT_MODEL.get())
                .thenApply(text -> JsonParser.parseString(text).getAsJsonObject());
    }

    private static final JsonObject LINE_SCHEMA = JsonParser.parseString("""
            {"type": "object", "additionalProperties": false, "required": ["text"], "properties": {"text": {"type": "string"}}}
            """).getAsJsonObject();

    private static String contextFor(ServerPlayer player, String truthBlock, String turn) {
        return PlayerContext.buildDirectorPromptBlock(player) + "\n"
                + PlayerProfile.notesBlock(player) + "\n"
                + (truthBlock.isBlank() ? "" : truthBlock + "\n\n")
                + memoryBlock(player) + "\n" + turn;
    }

    private static CompletableFuture<JsonObject> complete(ServerPlayer player, String truthBlock, String guide, String turn) {
        return complete(Persona.systemPrompt(player.getGameProfile().getName()), guide, contextFor(player, truthBlock, turn), SPEECH_SCHEMA, "high", Config.CHAT_MODEL.get())
                .thenApply(text -> JsonParser.parseString(text).getAsJsonObject());
    }

    private static final JsonObject SPEECH_SCHEMA = JsonParser.parseString("""
            {"type": "object", "additionalProperties": false, "required": ["text", "action", "new_goal", "steps", "plan_change", "standing"],
             "properties": {
               "text": {"type": "string"},
               "action": {"type": "string", "enum": ["none", "follow", "stay", "come", "fight", "stop"]},
               "new_goal": {"type": "string"},
               "plan_change": {"type": "string", "enum": ["none", "replace", "add_after", "do_first"]},
               "standing": {"type": "array", "items": {"type": "object", "additionalProperties": false, "required": ["key", "value"],
                 "properties": {"key": {"type": "string", "enum": ["torches_while_digging", "torch_every", "torch_side", "tunnel_width", "new_mine", "stay_close", "build_with", "pick_up_stations", "tunnel_height", "branch_every", "branch_length", "ongoing_job", "storage_at", "gear_before_caves", "mine_heading", "village_mode", "mine_model", "house_model"]},
                   "value": {"type": "string"}}}},
               "steps": {"type": "array", "items": {"type": "object", "additionalProperties": false,
                 "required": ["skill", "target", "count", "where"],
                 "properties": {"skill": {"type": "string"}, "target": {"type": "string"}, "count": {"type": "integer"},
                   "where": {"type": "string", "enum": ["near_you", "near_them", "looked_at", "exact_them", "exact"]}}}}}}
            """).getAsJsonObject();

    private static final JsonObject READ_SCHEMA = JsonParser.parseString("""
            {"type": "object", "additionalProperties": false, "required": ["notes", "read"],
             "properties": {"notes": {"type": "string"}, "read": {"type": "string"}}}
            """).getAsJsonObject();

    private static CompletableFuture<String> complete(String systemPrompt, String userMessage, JsonObject schema, String effortLevel, String model) {
        return complete(systemPrompt, null, userMessage, schema, effortLevel, model);
    }

    private static CompletableFuture<String> complete(String systemPrompt, String guide, String userMessage, JsonObject schema, String effortLevel, String model) {
        if (!Config.ENABLE_LLM.get()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Claude LLM integration is disabled in config."));
        }
        String apiKey = Config.ANTHROPIC_API_KEY.get();
        if (apiKey == null || apiKey.isBlank()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Anthropic API key is not configured. Set anthropicApiKey in config/claude-common.toml."));
        }

        JsonObject body = requestBody(systemPrompt, guide, userMessage, schema, effortLevel, model);
        capture(kindOf(schema, effortLevel), body);

        HttpRequest request = HttpRequest.newBuilder(URI.create(ENDPOINT))
                .timeout(Duration.ofMillis(Config.REQUEST_TIMEOUT_MS.get()))
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2026-10-21")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        if (Config.DEBUG_LOGGING.get()) {
            ClaudeMod.LOGGER.info("[Claude] Claude request ({} chars of context)", userMessage.length());
        }

        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .exceptionallyCompose(ex -> { // a timeout: once more, then the failure as before (Day to Day 22:02 and 22:03: two plans dropped on HttpTimeoutException)
                    Throwable c = ex instanceof java.util.concurrent.CompletionException && ex.getCause() != null ? ex.getCause() : ex;
                    if (!(c instanceof java.net.http.HttpTimeoutException)) return CompletableFuture.failedFuture(c);
                    ClaudeMod.LOGGER.info("[Claude] the {} call timed out after {} ms: once more", kindOf(schema, effortLevel), Config.REQUEST_TIMEOUT_MS.get());
                    return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString());
                })
                .thenApply(ClaudeApi::extractContent);
    }

    // ---- capture for the model comparison: on only while <game dir>/claude-capture exists
    private static final java.util.concurrent.atomic.AtomicInteger CAPTURED = new java.util.concurrent.atomic.AtomicInteger();
    public static java.nio.file.Path captureDir() { return net.neoforged.fml.loading.FMLPaths.GAMEDIR.get().resolve("claude-capture"); }
    static boolean capturing() { return java.nio.file.Files.isDirectory(captureDir()) && CAPTURED.get() < 120; }
    public static String kindOf(JsonObject schema, String effort) {
        return schema == PLAN_SCHEMA ? "plan-" + effort : schema == SPEECH_SCHEMA ? "chat" : schema == LINE_SCHEMA ? "line" : schema == READ_SCHEMA ? "notes" : "other";
    }
    /** Saves one request body (no key: that's a header) while capturing; a failure never touches the call. */
    public static void capture(String kind, JsonObject body) {
        if (!capturing()) return;
        try {
            int n = CAPTURED.incrementAndGet();
            java.nio.file.Files.writeString(captureDir().resolve(kind + "-" + System.currentTimeMillis() + "-" + n + ".json"), body.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) { ClaudeMod.LOGGER.warn("[Claude] prompt capture failed: {}", e.toString()); }
    }

    /** The request: the system prompt cached, then (with a guide) the fixed instructions as a cached first block and the live state after them. */
    public static JsonObject requestBody(String systemPrompt, String guide, String userMessage, JsonObject schema, String effortLevel, String model) {
        JsonArray messages = new JsonArray();
        if (guide == null) messages.add(message("user", userMessage));
        else { // the fixed instructions first, cached (the same bytes every call), then the live state: a cache only matches up to the first change, and with the live state first the fixed text was billed in full every call
            JsonObject g0 = new JsonObject(); g0.addProperty("type", "text"); g0.addProperty("text", guide);
            JsonObject c0 = new JsonObject(); c0.addProperty("type", "ephemeral"); c0.addProperty("ttl", "1h"); g0.add("cache_control", c0); // (an hour: his calls come minutes apart, past the 5-minute default)
            JsonObject u0 = new JsonObject(); u0.addProperty("type", "text"); u0.addProperty("text", userMessage);
            JsonArray parts = new JsonArray(); parts.add(g0); parts.add(u0);
            JsonObject m = new JsonObject(); m.addProperty("role", "user"); m.add("content", parts);
            messages.add(m);
        }

        JsonObject effort = new JsonObject();
        effort.addProperty("effort", effortLevel);
        if (schema != null) {
            JsonObject format = new JsonObject();
            format.addProperty("type", "json_schema");
            format.add("schema", schema);
            effort.add("format", format);
        }

        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        // The system prompt never changes between calls, so it is cached (cache reads cost a tenth of normal input).
        JsonObject cache = new JsonObject();
        cache.addProperty("type", "ephemeral");
        cache.addProperty("ttl", "1h"); // plans come ~6 minutes apart: the 5-minute default had always expired (a write every call, never a read)
        JsonObject systemBlock = new JsonObject();
        systemBlock.addProperty("type", "text");
        systemBlock.addProperty("text", systemPrompt);
        systemBlock.add("cache_control", cache);
        JsonArray system = new JsonArray();
        system.add(systemBlock);
        body.add("system", system);
        body.add("messages", messages);
        body.add("output_config", effort);
        // Thinking counts toward max_tokens; reply length is held by the prompt and cleanReply's cap.
        body.addProperty("max_tokens", 4096); // room for thinking plus the answer; only used tokens are billed
        return body;
    }

    private static String extractContent(HttpResponse<String> response) {
        if (response.statusCode() / 100 != 2) {
            throw new RuntimeException("Claude HTTP " + response.statusCode() + ": " + errorMessage(response.body()));
        }
        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        if (Config.DEBUG_LOGGING.get() && root.has("usage")) {
            JsonObject u = root.getAsJsonObject("usage");
            ClaudeMod.LOGGER.info("[Claude] tokens: in={} cached={} cache_write={} out={}",
                    u.get("input_tokens"), u.get("cache_read_input_tokens"), u.get("cache_creation_input_tokens"), u.get("output_tokens"));
        }
        String stop = root.has("stop_reason") && !root.get("stop_reason").isJsonNull() ? root.get("stop_reason").getAsString() : "";
        if (stop.equals("refusal")) {
            throw new RuntimeException("Claude refused: " + response.body());
        }
        StringBuilder text = new StringBuilder();
        JsonArray content = root.getAsJsonArray("content");
        if (content != null) {
            for (var el : content) {
                JsonObject block = el.getAsJsonObject();
                if ("text".equals(block.get("type").getAsString())) text.append(block.get("text").getAsString());
            }
        }
        if (text.toString().isBlank()) {
            throw new RuntimeException("Claude response has no text (stop_reason=" + stop + "): " + response.body());
        }
        return text.toString().trim();
    }

    private static JsonObject message(String role, String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", role);
        m.addProperty("content", content);
        return m;
    }

    private static String errorMessage(String body) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonObject error = root.getAsJsonObject("error");
            if (error != null && error.has("message") && !error.get("message").isJsonNull()) {
                return truncate(error.get("message").getAsString());
            }
        } catch (RuntimeException ignored) {
            // Fall through to a trimmed raw body.
        }
        return truncate(body);
    }

    private static String truncate(String text) {
        if (text == null) return "";
        String oneLine = text.replace('\n', ' ').replace('\r', ' ').trim();
        return oneLine.length() > 220 ? oneLine.substring(0, 220).trim() + "..." : oneLine;
    }
}
