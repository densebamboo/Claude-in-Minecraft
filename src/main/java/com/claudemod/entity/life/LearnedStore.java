package com.claudemod.entity.life;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.claudemod.ClaudeMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * What he has learned, kept per bot OUTSIDE the world save (config/claude/learned/<bot>/<kind>/<name>.json), so it carries into
 * every new world: the user's rule for learned things. One small store for every kind: blueprints (watched builds, WatchBuild) now;
 * learned skills (phase 2 of the primitives work: named, verified, saved per bot) will be a second Kind on the same calls.
 *
 * Each file is one JSON object: {"kind", "name", "bot", "saved" (ISO time), "version", ...the kind's own body}. A name is free text
 * ("tower", "lipid's build"); its file name is the slug of it (lower case, runs of anything else to "_"). Every call logs what it did
 * and why it didn't; a read that fails says so and returns null (never a half-read thing).
 */
public final class LearnedStore {
    private LearnedStore() {}

    /** The kinds of learned things, each its own folder. */
    public enum Kind {
        BLUEPRINT("blueprints"), SKILL("skills");
        public final String dir;
        Kind(String dir) { this.dir = dir; }
    }

    public static final int VERSION = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    /** The root, config/claude/learned (tests may point it elsewhere; null: the game's config folder). */
    public static Path rootOverride;

    public static Path root() {
        if (rootOverride != null) return rootOverride;
        return net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve("claude").resolve("learned");
    }
    /** One bot's folder for one kind: config/claude/learned/<bot>/<kind>. */
    public static Path dir(String bot, Kind kind) { return root().resolve(slug(bot)).resolve(kind.dir); }
    public static Path file(String bot, Kind kind, String name) { return dir(bot, kind).resolve(slug(name) + ".json"); }

    /** A name as a file name: lower case, letters and digits, the rest one "_". */
    public static String slug(String name) {
        String s = name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        return s.isEmpty() ? "unnamed" : s;
    }

    /** Saves (replacing any of that name). The body's own fields go in beside kind/name/bot/saved/version. True when it's on disk. */
    public static boolean save(String bot, Kind kind, String name, JsonObject body) {
        JsonObject o = body.deepCopy();
        o.addProperty("kind", kind.name().toLowerCase(Locale.ROOT)); o.addProperty("name", name); o.addProperty("bot", bot);
        o.addProperty("saved", java.time.OffsetDateTime.now().withNano(0).toString()); o.addProperty("version", VERSION);
        Path f = file(bot, kind, name);
        try {
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(o), StandardCharsets.UTF_8);
            Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            ClaudeMod.LOGGER.info("[Claude learned] saved {} \"{}\" for {} to {} (LearnedStore.save)", kind.dir, name, bot, f);
            return true;
        } catch (IOException e) {
            ClaudeMod.LOGGER.warn("[Claude learned] couldn't save {} \"{}\" for {} to {}: {} (LearnedStore.save)", kind.dir, name, bot, f, e.toString());
            return false;
        }
    }

    /** The saved object, or null (none of that name, or unreadable: logged). */
    public static JsonObject load(String bot, Kind kind, String name) { return read(file(bot, kind, name)); }

    /** Every saved one of this kind for this bot, oldest first (by the file's time). */
    public static List<JsonObject> all(String bot, Kind kind) {
        List<JsonObject> out = new ArrayList<>();
        Path d = dir(bot, kind);
        if (!Files.isDirectory(d)) return out;
        List<Path> files = new ArrayList<>();
        try (var st = Files.list(d)) { st.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(files::add); }
        catch (IOException e) { ClaudeMod.LOGGER.warn("[Claude learned] couldn't list {}: {} (LearnedStore.all)", d, e.toString()); return out; }
        files.sort(java.util.Comparator.comparingLong(p -> { try { return Files.getLastModifiedTime(p).toMillis(); } catch (IOException e) { return 0L; } }));
        for (Path p : files) { JsonObject o = read(p); if (o != null) out.add(o); }
        return out;
    }

    /** The names saved of this kind, oldest first. */
    public static List<String> list(String bot, Kind kind) {
        List<String> names = new ArrayList<>();
        for (JsonObject o : all(bot, kind)) names.add(o.has("name") ? o.get("name").getAsString() : "?");
        return names;
    }

    public static boolean exists(String bot, Kind kind, String name) { return Files.isRegularFile(file(bot, kind, name)); }

    /** Gone from disk (true if there was one). */
    public static boolean delete(String bot, Kind kind, String name) {
        Path f = file(bot, kind, name);
        try {
            boolean was = Files.deleteIfExists(f);
            ClaudeMod.LOGGER.info("[Claude learned] {} {} \"{}\" for {} (LearnedStore.delete)", was ? "deleted" : "nothing to delete for", kind.dir, name, bot);
            return was;
        } catch (IOException e) { ClaudeMod.LOGGER.warn("[Claude learned] couldn't delete {}: {} (LearnedStore.delete)", f, e.toString()); return false; }
    }

    private static JsonObject read(Path f) {
        if (!Files.isRegularFile(f)) return null;
        try { return JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject(); }
        catch (Exception e) { ClaudeMod.LOGGER.warn("[Claude learned] couldn't read {}: {} (LearnedStore.read)", f, e.toString()); return null; }
    }
}
