package top.fish1000.mcmcl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Per-instance launch overrides, stored by MCMCL next to its repository
 * cache. HMCL keeps its own version settings in the launcher's global
 * config, which the headless helper does not read, so these are the values
 * MCMCL itself applies when building a launch request: blank/zero/inherit
 * falls back to the global client configuration.
 */
public final class InstanceSettingsStore {
    private static final Object LOCK = new Object();

    private InstanceSettingsStore() {
    }

    public enum VersionIsolationOverride {
        INHERIT,
        ENABLED,
        DISABLED;

        public boolean resolve(boolean globalValue) {
            return switch (this) {
                case INHERIT -> globalValue;
                case ENABLED -> true;
                case DISABLED -> false;
            };
        }
    }

    public record Settings(String javaPath, int maxMemory, VersionIsolationOverride versionIsolation) {
        public Settings {
            javaPath = javaPath == null ? "" : javaPath.strip();
            if (maxMemory < 0) {
                maxMemory = 0;
            }
            versionIsolation = versionIsolation == null
                    ? VersionIsolationOverride.INHERIT
                    : versionIsolation;
        }
    }

    public static final Settings EMPTY = new Settings("", 0, VersionIsolationOverride.INHERIT);

    /**
     * Returns the stored overrides for the instance, or empty defaults when none
     * exist.
     */
    public static Settings read(Path repository, String instanceId) {
        synchronized (LOCK) {
            try {
                Path file = settingsFile(repository);
                if (!Files.isRegularFile(file)) {
                    return EMPTY;
                }
                JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                JsonElement element = root.get(instanceId);
                if (!(element instanceof JsonObject settings)) {
                    return EMPTY;
                }
                JsonElement javaPath = settings.get("javaPath");
                JsonElement maxMemory = settings.get("maxMemory");
                JsonElement versionIsolation = settings.get("versionIsolation");
                return new Settings(
                        javaPath == null ? "" : javaPath.getAsString(),
                        maxMemory != null && maxMemory.isJsonPrimitive() ? maxMemory.getAsInt() : 0,
                        parseVersionIsolation(versionIsolation));
            } catch (IOException | RuntimeException ignored) {
                return EMPTY;
            }
        }
    }

    /** Best-effort write; a failed save is surfaced through the return value. */
    public static boolean write(Path repository, String instanceId, Settings settings) {
        synchronized (LOCK) {
            try {
                Path file = settingsFile(repository);
                Files.createDirectories(file.getParent());
                JsonObject root = new JsonObject();
                if (Files.isRegularFile(file)) {
                    try {
                        root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                    } catch (IOException | RuntimeException ignored) {
                        root = new JsonObject();
                    }
                }
                JsonObject entry = new JsonObject();
                entry.addProperty("javaPath", settings.javaPath());
                entry.addProperty("maxMemory", settings.maxMemory());
                entry.addProperty("versionIsolation", settings.versionIsolation().name().toLowerCase());
                root.add(instanceId, entry);

                Path temp = file.resolveSibling(file.getFileName() + ".tmp");
                Files.writeString(temp, root.toString());
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
                return true;
            } catch (IOException | RuntimeException ignored) {
                return false;
            }
        }
    }

    private static Path settingsFile(Path repository) {
        return repository.resolve("cache").resolve("instance-settings.json");
    }

    private static VersionIsolationOverride parseVersionIsolation(JsonElement value) {
        if (value == null || !value.isJsonPrimitive()) {
            return VersionIsolationOverride.INHERIT;
        }
        try {
            return VersionIsolationOverride.valueOf(value.getAsString().strip().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return VersionIsolationOverride.INHERIT;
        }
    }

    /** Moves a stored settings entry when its instance is renamed; best effort. */
    public static void rename(Path repository, String fromId, String toId) {
        synchronized (LOCK) {
            try {
                Path file = settingsFile(repository);
                if (!Files.isRegularFile(file)) {
                    return;
                }
                JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                JsonElement moved = root.remove(fromId);
                if (moved == null) {
                    return;
                }
                root.add(toId, moved);
                Path temp = file.resolveSibling(file.getFileName() + ".tmp");
                Files.writeString(temp, root.toString());
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException | RuntimeException ignored) {
            }
        }
    }
}
