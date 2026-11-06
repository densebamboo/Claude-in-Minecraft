package com.claudemod.entity.life;

import com.claudemod.ClaudeMod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/**
 * Every weighted choice's numbers, by name, so the user can tune them without a build
 * (the user 11-05: "everything should become a weighted decision that we can tweak the values of later").
 * config/claude/weights.json holds only the values the user set: read within 5 s of a save, and a name taken out goes back to its default.
 * config/claude/weights-defaults.json lists every weight used so far with its code default (written by the mod, for reference: copy a line over to change it).
 * Names are "area.what", e.g. "path.lava_beside_flow".
 */
public final class Weights {
    private static final Map<String, Double> SET = new TreeMap<>(), DEFAULTS = new TreeMap<>();
    private static long checkedAt, fileTime = -1;

    private Weights() {}

    public static Path file() { return dir().resolve("weights.json"); }
    public static Path defaultsFile() { return dir().resolve("weights-defaults.json"); }
    private static Path dir() { return net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve("claude"); }

    /** The weight called name: the user's value if set, else def. */
    public static synchronized double w(String name, double def) {
        long now = System.currentTimeMillis();
        if (now - checkedAt > 5000) { checkedAt = now; reload(); }
        Double d = DEFAULTS.put(name, def);
        if (d == null || d != def) saveDefaults(); // once per name (and when a default changes): the list stays current
        Double v = SET.get(name);
        return v != null ? v : def;
    }

    private static void reload() {
        Path f = file();
        try {
            if (!Files.exists(f)) { if (!SET.isEmpty()) SET.clear(); fileTime = -1; return; }
            long t = Files.getLastModifiedTime(f).toMillis();
            if (t == fileTime) return;
            fileTime = t;
            var o = com.google.gson.JsonParser.parseString(Files.readString(f)).getAsJsonObject();
            SET.clear();
            for (var e : o.entrySet()) if (e.getValue().isJsonPrimitive() && e.getValue().getAsJsonPrimitive().isNumber()) SET.put(e.getKey(), e.getValue().getAsDouble());
            ClaudeMod.LOGGER.info("[Claude weights] {} value(s) set in {}: {}", SET.size(), f, SET);
        } catch (Exception ex) {
            ClaudeMod.LOGGER.warn("[Claude weights] {} unreadable, keeping the last values: {}", f, ex.toString());
        }
    }

    private static void saveDefaults() {
        Path f = defaultsFile();
        try {
            Files.createDirectories(f.getParent());
            Files.writeString(f, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(DEFAULTS));
        } catch (Exception ex) {
            ClaudeMod.LOGGER.warn("[Claude weights] could not write {}: {}", f, ex.toString());
        }
    }
}
