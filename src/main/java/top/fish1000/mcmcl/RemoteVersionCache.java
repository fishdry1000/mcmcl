package top.fish1000.mcmcl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Disk cache for the helper's remote version listings so the install screen
 * opens instantly; a background refresh keeps entries current.
 *
 * <p>
 * One JSON file under {@code <repository>/cache/} holds every entry keyed
 * by component: the empty string for the game version list, and
 * {@code loader/gameVersion} for loader version lists. Entries are tagged
 * with the download provider, so switching providers discards the cache.
 * </p>
 */
public final class RemoteVersionCache {
    /** Entries older than this are re-validated on open (but still shown). */
    public static final Duration REFRESH_AGE = Duration.ofHours(24);
    private static final Object LOCK = new Object();

    private RemoteVersionCache() {
    }

    public record Entry(List<HmclHelperClient.RemoteVersion> versions, long fetchedAt) {
        public boolean isFresh() {
            return System.currentTimeMillis() - this.fetchedAt < REFRESH_AGE.toMillis();
        }
    }

    /**
     * Returns the cached entry, or {@code null} when absent, stale-provider or
     * unreadable.
     */
    public static Entry read(Path repository, String provider, String key) {
        synchronized (LOCK) {
            try {
                Path file = cacheFile(repository);
                if (!Files.isRegularFile(file)) {
                    return null;
                }
                JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                JsonElement cachedProvider = root.get("provider");
                if (cachedProvider == null || !provider.equals(cachedProvider.getAsString())) {
                    return null;
                }
                JsonElement entriesElement = root.get("entries");
                if (!(entriesElement instanceof JsonObject entries)) {
                    return null;
                }
                JsonElement entryElement = entries.get(key);
                if (!(entryElement instanceof JsonObject entry)) {
                    return null;
                }
                List<HmclHelperClient.RemoteVersion> versions = new ArrayList<>();
                for (JsonElement element : entry.getAsJsonArray("versions")) {
                    JsonObject version = element.getAsJsonObject();
                    versions.add(new HmclHelperClient.RemoteVersion(
                            version.get("id").getAsString(),
                            version.get("type").getAsString(),
                            version.get("releaseTime").getAsString()));
                }
                return new Entry(List.copyOf(versions), entry.get("fetchedAt").getAsLong());
            } catch (IOException | RuntimeException ignored) {
                return null;
            }
        }
    }

    /** Best-effort write; cache failures must never break the install screen. */
    public static void write(Path repository, String provider, String key,
            List<HmclHelperClient.RemoteVersion> versions) {
        synchronized (LOCK) {
            try {
                Path file = cacheFile(repository);
                Files.createDirectories(file.getParent());
                JsonObject root = readRoot(file, provider);
                root.addProperty("provider", provider);
                JsonElement entriesElement = root.get("entries");
                JsonObject entries = entriesElement instanceof JsonObject existing ? existing : new JsonObject();

                JsonObject entry = new JsonObject();
                entry.addProperty("fetchedAt", System.currentTimeMillis());
                JsonArray array = new JsonArray();
                for (HmclHelperClient.RemoteVersion version : versions) {
                    JsonObject object = new JsonObject();
                    object.addProperty("id", version.id());
                    object.addProperty("type", version.type());
                    object.addProperty("releaseTime", version.releaseTime());
                    array.add(object);
                }
                entry.add("versions", array);
                entries.add(key, entry);
                root.add("entries", entries);

                Path temp = file.resolveSibling(file.getFileName() + ".tmp");
                Files.writeString(temp, root.toString());
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException | RuntimeException ignored) {
            }
        }
    }

    private static JsonObject readRoot(Path file, String provider) {
        try {
            if (Files.isRegularFile(file)) {
                JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                JsonElement cachedProvider = root.get("provider");
                if (cachedProvider != null && provider.equals(cachedProvider.getAsString())) {
                    return root;
                }
            }
        } catch (IOException | RuntimeException ignored) {
        }
        return new JsonObject();
    }

    private static Path cacheFile(Path repository) {
        return repository.resolve("cache").resolve("remote-versions.json");
    }
}
