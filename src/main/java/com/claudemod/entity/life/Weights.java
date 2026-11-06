package com.claudemod.entity.life;

import com.claudemod.ClaudeMod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/**
 * Every weighted choice's numbers, by name, in config/claude/weights.json, so the user can tune them without a build
 * (the user 11-05: "everything should become a weighted decision that we can tweak the values of later").
 * A saved edit is read within 5 s. A name the file lacks is added with its code default, so the file lists every weight he has used.
 * Names are "area.what", e.g. "path.lava_beside_flow".
 */
public final class Weights {
    private static final Map<String, Double> VALUES = new TreeMap<>();
    private static long checkedAt, fileTime = -1;

    private Weights() {}

    public static Path file() { return net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve("claude").resolve("weights.json"); }

    /** The weight called name: the file's value, else def (and def is written to the file). */
    public static synchronized double w(String name, double def) {
        long now = System.currentTimeMillis();
        if (now - checkedAt > 5000) { checkedAt = now; reload(); }
        Double v = VALUES.get(name);
        if (v == null) { VALUES.put(name, def); save(); return def; } // once per name: the file lists it from its first use
        return v;
    }

    private static void reload() {
        Path f = file();
        try {
            if (!Files.exists(f)) return;
            long t = Files.getLastModifiedTime(f).toMillis();
            if (t == fileTime) return;
            fileTime = t;
            var o = com.google.gson.JsonParser.parseString(Files.readString(f)).getAsJsonObject();
            int n = 0;
            for (var e : o.entrySet()) if (e.getValue().isJsonPrimitive() && e.getValue().getAsJsonPrimitive().isNumber()) { VALUES.put(e.getKey(), e.getValue().getAsDouble()); n++; }
            ClaudeMod.LOGGER.info("[Claude weights] read {} weight(s) from {}", n, f);
        } catch (Exception ex) {
            ClaudeMod.LOGGER.warn("[Claude weights] {} unreadable, keeping the last values: {}", f, ex.toString());
        }
    }

    private static void save() {
        Path f = file();
        try {
            Files.createDirectories(f.getParent());
            Files.writeString(f, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(VALUES));
            fileTime = Files.getLastModifiedTime(f).toMillis();
        } catch (Exception ex) {
            ClaudeMod.LOGGER.warn("[Claude weights] could not write {}: {}", f, ex.toString());
        }
    }
}
