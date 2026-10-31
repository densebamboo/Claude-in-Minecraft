package com.claudemod.llm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.claudemod.ClaudeMod;

import net.neoforged.fml.loading.FMLPaths;

public final class PersonaLoader {
    private static final String SUBDIR = "claude";
    private static final String FILE_NAME = "persona.txt";

    private PersonaLoader() {}

    public static Path personaFile() {
        return FMLPaths.CONFIGDIR.get().resolve(SUBDIR).resolve(FILE_NAME);
    }

    /**
     * Returns the persona prompt for the given player. Reads from disk every call so edits made
     * while the game is running take effect on the very next /claude ask -- no rebuild, no reload.
     * Falls back to the built-in default if the file can't be read.
     */
    public static String load(String playerName) {
        Path path = personaFile();
        try {
            if (!Files.exists(path)) {
                writeDefault(path);
            }
            String template = Files.readString(path);
            return substitute(template, playerName);
        } catch (IOException e) {
            ClaudeMod.LOGGER.warn("[Claude] failed to read persona file at {}: {}. Using built-in default.",
                    path, e.getMessage());
            return substitute(Persona.DEFAULT_TEMPLATE, playerName);
        }
    }

    /** Writes the default persona file to disk if it doesn't already exist. Idempotent and safe to call on startup. */
    public static void ensureExists() {
        Path path = personaFile();
        try {
            if (!Files.exists(path)) {
                writeDefault(path);
            }
        } catch (IOException e) {
            ClaudeMod.LOGGER.warn("[Claude] failed to seed default persona file at {}: {}", path, e.getMessage());
        }
    }

    private static void writeDefault(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, Persona.DEFAULT_TEMPLATE);
        ClaudeMod.LOGGER.info("[Claude] wrote default persona file to {}", path);
    }

    private static String substitute(String template, String playerName) {
        return template.replace("{player}", playerName);
    }
}
