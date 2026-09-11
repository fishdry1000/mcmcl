package top.fish1000.mcmcl;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.User;

/** Builds and starts an instance command without invoking a shell. */
public final class InstanceLauncher {
    private static final Map<String, LaunchHandle> RUNNING = new ConcurrentHashMap<>();

    private InstanceLauncher() {
    }

    public static LaunchHandle launch(
            InstanceDefinition definition,
            Minecraft minecraft,
            Consumer<String> logSink
    ) throws IOException {
        LaunchHandle previous = RUNNING.get(definition.id());
        if (previous != null && previous.process().isAlive()) {
            throw new IOException("Instance is already running");
        }

        List<String> command = buildCommand(definition, minecraft);
        Path workingDirectory = resolveWorkingDirectory(definition, minecraft);
        Files.createDirectories(workingDirectory);

        ProcessBuilder processBuilder = new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true);
        Map<String, String> environment = processBuilder.environment();
        definition.environment().forEach((key, value) ->
                environment.put(key, expand(value, createTokens(definition, minecraft))));

        Process process = processBuilder.start();
        LaunchHandle handle = new LaunchHandle(process, process.onExit().thenApply(Process::exitValue));
        RUNNING.put(definition.id(), handle);

        Consumer<String> safeLogSink = logSink == null ? ignored -> { } : logSink;
        Thread outputThread = new Thread(
                () -> consumeOutput(definition, process, safeLogSink),
                "mcmcl-output-" + definition.id()
        );
        outputThread.setDaemon(true);
        outputThread.start();

        handle.exitCode().whenComplete((exitCode, error) -> {
            RUNNING.remove(definition.id(), handle);
            if (error != null) {
                MinecraftMinecraftLauncher.LOGGER.warn("Instance {} exited with an error", definition.id(), error);
            } else {
                MinecraftMinecraftLauncher.LOGGER.info("Instance {} exited with code {}", definition.id(), exitCode);
            }
        });
        return handle;
    }

    public static boolean isRunning(String id) {
        LaunchHandle handle = RUNNING.get(id);
        return handle != null && handle.process().isAlive();
    }

    public static void stop(String id) {
        LaunchHandle handle = RUNNING.get(id);
        if (handle == null) {
            return;
        }
        handle.process().destroy();
        Thread.ofVirtual().start(() -> {
            try {
                if (!handle.process().waitFor(2, TimeUnit.SECONDS)) {
                    handle.process().destroyForcibly();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private static List<String> buildCommand(InstanceDefinition definition, Minecraft minecraft) throws IOException {
        Map<String, String> tokens = createTokens(definition, minecraft);
        String configuredJava = definition.javaExecutable().trim();
        if (!Config.ALLOW_CUSTOM_JAVA.get()
                && !configuredJava.isBlank()
                && !configuredJava.equals("${java}")) {
            throw new IOException("Custom Java executables are disabled in the MCMCL config");
        }

        String javaExecutable = configuredJava.isBlank() || configuredJava.equals("${java}")
                ? currentJavaExecutable().toString()
                : expand(configuredJava, tokens);
        List<String> command = new ArrayList<>();
        command.add(javaExecutable);
        definition.jvmArgs().forEach(argument -> command.add(expand(argument, tokens)));

        if (!definition.jar().isBlank()) {
            Path jar = resolveArtifact(definition.jar(), definition, minecraft, tokens);
            requireExists(jar, "jar");
            command.add("-jar");
            command.add(jar.toString());
        } else {
            List<String> classpath = new ArrayList<>();
            for (String entry : definition.classpath()) {
                Path path = resolveArtifact(entry, definition, minecraft, tokens);
                requireExists(path, "classpath entry");
                classpath.add(path.toString());
            }
            command.add("-cp");
            command.add(String.join(java.io.File.pathSeparator, classpath));
            command.add(expand(definition.mainClass(), tokens));
        }

        List<String> gameArgs = definition.gameArgs().isEmpty()
                ? defaultGameArguments(tokens)
                : definition.gameArgs();
        gameArgs.forEach(argument -> command.add(expand(argument, tokens)));
        return List.copyOf(command);
    }

    private static List<String> defaultGameArguments(Map<String, String> tokens) {
        return List.of(
                "--version", tokens.get("version"),
                "--gameDir", tokens.get("gameDir"),
                "--assetsDir", tokens.get("assetsDir"),
                "--assetIndex", tokens.get("assetIndex"),
                "--username", tokens.get("username"),
                "--uuid", tokens.get("uuid"),
                "--accessToken", tokens.get("accessToken"),
                "--userType", tokens.get("userType"),
                "--versionType", tokens.get("versionType")
        );
    }

    private static Map<String, String> createTokens(InstanceDefinition definition, Minecraft minecraft) {
        User user = minecraft.getUser();
        Path gameDirectory = minecraft.gameDirectory.toPath().toAbsolutePath().normalize();
        Path assetsDirectory = definition.assetsDirectory().isBlank()
                ? definition.directory().resolve("assets")
                : resolveConfiguredPath(definition.assetsDirectory(), definition.directory(), gameDirectory);

        Map<String, String> tokens = new HashMap<>();
        tokens.put("java", currentJavaExecutable().toString());
        tokens.put("id", definition.id());
        tokens.put("name", definition.name());
        tokens.put("version", definition.version());
        tokens.put("gameDir", definition.directory().toString());
        tokens.put("instanceDir", definition.directory().toString());
        tokens.put("assetsDir", assetsDirectory.toAbsolutePath().normalize().toString());
        tokens.put("assetIndex", definition.assetIndex());
        tokens.put("mainClass", definition.mainClass());
        tokens.put("username", user.getName());
        tokens.put("uuid", user.getProfileId().toString());
        tokens.put("accessToken", user.getAccessToken());
        tokens.put("sessionId", user.getSessionId());
        tokens.put("userType", definition.userType());
        tokens.put("versionType", definition.versionType());
        user.getClientId().ifPresent(value -> tokens.put("clientId", value));
        user.getXuid().ifPresent(value -> tokens.put("xuid", value));
        tokens.put("minecraftGameDir", gameDirectory.toString());

        // These aliases make manifests easier to migrate from launcher metadata.
        tokens.put("auth.name", tokens.get("username"));
        tokens.put("auth.uuid", tokens.get("uuid"));
        tokens.put("auth.accessToken", tokens.get("accessToken"));
        return Map.copyOf(tokens);
    }

    private static Path resolveWorkingDirectory(InstanceDefinition definition, Minecraft minecraft) {
        Path gameDirectory = minecraft.gameDirectory.toPath().toAbsolutePath().normalize();
        if (definition.workingDirectory().isBlank()) {
            return definition.directory();
        }
        return resolveConfiguredPath(definition.workingDirectory(), definition.directory(), gameDirectory);
    }

    private static Path resolveArtifact(
            String raw,
            InstanceDefinition definition,
            Minecraft minecraft,
            Map<String, String> tokens
    ) {
        String expanded = expand(raw, tokens);
        Path path = Path.of(expanded);
        if (path.isAbsolute()) {
            return path.normalize();
        }

        Path instancePath = definition.directory().resolve(path).normalize();
        Path gamePath = minecraft.gameDirectory.toPath().toAbsolutePath().normalize().resolve(path).normalize();
        if (Files.exists(instancePath)) {
            return instancePath;
        }
        if (Files.exists(gamePath)) {
            return gamePath;
        }
        return looksLikeGameRelativePath(expanded) ? gamePath : instancePath;
    }

    private static Path resolveConfiguredPath(String raw, Path instanceDirectory, Path gameDirectory) {
        String normalized = raw.replace("${instanceDir}", instanceDirectory.toString())
                .replace("${gameDir}", instanceDirectory.toString())
                .replace("${minecraftGameDir}", gameDirectory.toString());
        Path path = Path.of(normalized);
        return path.isAbsolute() ? path.normalize() : instanceDirectory.resolve(path).normalize();
    }

    private static boolean looksLikeGameRelativePath(String path) {
        return path.startsWith("libraries/")
                || path.startsWith("libraries\\")
                || path.startsWith("versions/")
                || path.startsWith("versions\\")
                || path.startsWith("assets/")
                || path.startsWith("assets\\");
    }

    private static void requireExists(Path path, String kind) throws IOException {
        if (!Files.exists(path)) {
            throw new IOException(kind + " does not exist: " + path);
        }
    }

    private static Path currentJavaExecutable() {
        Path javaHome = Path.of(System.getProperty("java.home"));
        String executable = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "java.exe"
                : "java";
        return javaHome.resolve("bin").resolve(executable).toAbsolutePath().normalize();
    }

    private static String expand(String value, Map<String, String> tokens) {
        String expanded = value;
        for (Map.Entry<String, String> token : tokens.entrySet()) {
            expanded = expanded.replace("${" + token.getKey() + "}", token.getValue());
        }
        return expanded;
    }

    private static void consumeOutput(InstanceDefinition definition, Process process, Consumer<String> logSink) {
        try (BufferedReader reader = process.inputReader(StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                logSink.accept(line);
                MinecraftMinecraftLauncher.LOGGER.info("[{}] {}", definition.id(), line);
            }
        } catch (IOException exception) {
            MinecraftMinecraftLauncher.LOGGER.debug("Could not read output for {}", definition.id(), exception);
        }
    }

    public record LaunchHandle(Process process, CompletableFuture<Integer> exitCode) {
    }
}
