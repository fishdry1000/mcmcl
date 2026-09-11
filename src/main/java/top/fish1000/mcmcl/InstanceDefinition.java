package top.fish1000.mcmcl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Immutable, validated view of one {@code launch.json} instance manifest. */
public record InstanceDefinition(
        String id,
        String name,
        String version,
        Path directory,
        String javaExecutable,
        String mainClass,
        String jar,
        List<String> classpath,
        List<String> jvmArgs,
        List<String> gameArgs,
        String workingDirectory,
        String assetsDirectory,
        String assetIndex,
        String userType,
        String versionType,
        Map<String, String> environment
) {
    public static final String MANIFEST_NAME = "launch.json";
    public static final String DEFAULT_MAIN_CLASS = "net.minecraft.client.main.Main";

    public InstanceDefinition {
        classpath = List.copyOf(classpath);
        jvmArgs = List.copyOf(jvmArgs);
        gameArgs = List.copyOf(gameArgs);
        environment = Map.copyOf(environment);
    }

    public static InstanceDefinition read(Path directory) throws IOException {
        Path manifest = directory.resolve(MANIFEST_NAME);
        JsonElement parsed = JsonParser.parseString(Files.readString(manifest, StandardCharsets.UTF_8));
        if (!parsed.isJsonObject()) {
            throw new IOException("launch.json must contain a JSON object");
        }

        JsonObject json = parsed.getAsJsonObject();
        String id = directory.getFileName().toString();
        String name = string(json, "name", id);
        String version = string(json, "version", id);
        String javaExecutable = string(json, "java", "");
        String mainClass = string(json, "mainClass", DEFAULT_MAIN_CLASS);
        String jar = string(json, "jar", "");
        String workingDirectory = string(json, "workingDirectory", "");
        String assetsDirectory = string(json, "assetsDirectory", "");
        String assetIndex = string(json, "assetIndex", version);
        String userType = string(json, "userType", "msa");
        String versionType = string(json, "versionType", "release");

        List<String> classpath = stringList(json, "classpath");
        List<String> jvmArgs = stringList(json, "jvmArgs");
        List<String> gameArgs = stringList(json, "gameArgs");
        Map<String, String> environment = stringMap(json, "environment");

        if (jar.isBlank() && classpath.isEmpty()) {
            throw new IOException("launch.json needs either 'jar' or a non-empty 'classpath'");
        }

        return new InstanceDefinition(
                id,
                name,
                version,
                directory.toAbsolutePath().normalize(),
                javaExecutable,
                mainClass,
                jar,
                classpath,
                jvmArgs,
                gameArgs,
                workingDirectory,
                assetsDirectory,
                assetIndex,
                userType,
                versionType,
                environment
        );
    }

    private static String string(JsonObject json, String key, String fallback) {
        JsonElement value = json.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : fallback;
    }

    private static List<String> stringList(JsonObject json, String key) {
        JsonElement value = json.get(key);
        if (value == null || value.isJsonNull()) {
            return List.of();
        }
        if (value.isJsonPrimitive()) {
            return List.of(value.getAsString());
        }
        if (!value.isJsonArray()) {
            throw new IllegalArgumentException("'" + key + "' must be a string or array of strings");
        }

        List<String> result = new ArrayList<>();
        JsonArray array = value.getAsJsonArray();
        for (JsonElement entry : array) {
            if (!entry.isJsonPrimitive()) {
                throw new IllegalArgumentException("'" + key + "' must contain only strings");
            }
            result.add(entry.getAsString());
        }
        return result;
    }

    private static Map<String, String> stringMap(JsonObject json, String key) {
        JsonElement value = json.get(key);
        if (value == null || value.isJsonNull()) {
            return Map.of();
        }
        if (!value.isJsonObject()) {
            throw new IllegalArgumentException("'" + key + "' must be an object of string values");
        }

        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
            if (!entry.getValue().isJsonPrimitive()) {
                throw new IllegalArgumentException("environment values must be strings");
            }
            result.put(entry.getKey(), entry.getValue().getAsString());
        }
        return result;
    }
}
