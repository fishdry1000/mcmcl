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
 * cache.  HMCL keeps its own version settings in the launcher's global
 * config, which the headless helper does not read, so these are the values
 * MCMCL itself applies when building a launch request: blank/zero falls
 * back to the global client configuration.
 */
public final class InstanceSettingsStore {
    private static final Object LOCK = new Object();

    private InstanceSettingsStore() {
    }

    public record Settings(String javaPath, int maxMemory) {
        public Settings {
            javaPath = javaPath == null ? "" : javaPath.strip();
            if (maxMemory < 0) {
                maxMemory = 0;
            }
        }
    }

    public static final Settings EMPTY = new Settings("", 0);

    /** Returns the stored overrides for the instance, or empty defaults when none exist. */
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
                return new Settings(
                        javaPath == null ? "" : javaPath.getAsString(),
                        maxMemory != null && maxMemory.isJsonPrimitive() ? maxMemory.getAsInt() : 0
                );
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
}
