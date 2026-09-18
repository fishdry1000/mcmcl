package top.fish1000.mcmcl;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;
import net.minecraft.client.User;

/**
 * Client for the standalone HMCL helper process.
 *
 * <p>
 * The helper owns repository loading, launch preparation and game launching for
 * an already
 * prepared HMCL repository. This class only transports JSON Lines requests and
 * events, so HMCL
 * dependencies never enter the NeoForge class loader.
 * </p>
 */
public final class HmclHelperClient implements AutoCloseable {
    private static final int PROTOCOL_VERSION = 1;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final Path repositoryDirectory;
    private final Path helperJar;
    private final Path workingDirectory;
    private final Object processLock = new Object();
    private final Map<String, CompletableFuture<JsonObject>> pendingRequests = new ConcurrentHashMap<>();
    private final Map<String, LaunchHandle> runningInstances = new ConcurrentHashMap<>();
    private final Map<String, InstallHandle> runningInstalls = new ConcurrentHashMap<>();
    private final Map<String, Consumer<String>> logSinks = new ConcurrentHashMap<>();

    private volatile Process process;
    private volatile BufferedWriter writer;
    private volatile CompletableFuture<Void> ready;
    private volatile HelperInfo helperInfo;

    public HmclHelperClient(Path repositoryDirectory, Path helperJar, Path workingDirectory) {
        this.repositoryDirectory = repositoryDirectory.toAbsolutePath().normalize();
        this.helperJar = helperJar.toAbsolutePath().normalize();
        this.workingDirectory = workingDirectory.toAbsolutePath().normalize();
    }

    public CompletableFuture<List<HmclInstance>> listInstances() throws IOException {
        JsonObject request = new JsonObject();
        request.addProperty("command", "list");
        return send(request).thenApply(this::parseInstances);
    }

    public CompletableFuture<List<RemoteVersion>> remoteVersions() throws IOException {
        return remoteVersions(null, null);
    }

    /**
     * Fetches remote versions; with a non-blank {@code component} this returns the
     * loader versions
     * offered for that component on top of {@code gameVersion}.
     */
    public CompletableFuture<List<RemoteVersion>> remoteVersions(String component, String gameVersion)
            throws IOException {
        JsonObject request = new JsonObject();
        request.addProperty("command", "remoteVersions");
        if (component != null && !component.isBlank()) {
            request.addProperty("component", component);
            request.addProperty("gameVersion", gameVersion);
        }
        return send(request).thenApply(this::parseRemoteVersions);
    }

    public LaunchHandle launch(HmclInstance instance, Minecraft minecraft, Consumer<String> logSink)
            throws IOException {
        User user = minecraft.getUser();
        JsonObject request = new JsonObject();
        request.addProperty("command", "launch");
        request.addProperty("instanceId", instance.id());
        if (Config.OFFLINE_MODE.get()) {
            String username = Config.OFFLINE_USERNAME.get();
            if (username.isBlank()) {
                username = user.getName();
            }
            request.addProperty("username", username);
            request.addProperty("uuid", UUID
                    .nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8))
                    .toString());
            request.addProperty("accessToken", UUID.randomUUID().toString());
            request.addProperty("userType", "msa");
        } else {
            request.addProperty("username", user.getName());
            request.addProperty("uuid", user.getProfileId().toString());
            request.addProperty("accessToken", user.getAccessToken());
            request.addProperty("userType", "msa");
            user.getClientId().ifPresent(value -> request.addProperty("clientId", value));
            user.getXuid().ifPresent(value -> request.addProperty("xuid", value));
        }

        InstanceSettingsStore.Settings overrides = InstanceSettingsStore.read(this.repositoryDirectory, instance.id());
        String javaPath = overrides.javaPath().isBlank() ? Config.JAVA_PATH.get() : overrides.javaPath();
        if (!javaPath.isBlank()) {
            request.addProperty("javaPath", javaPath);
        }
        int maxMemory = overrides.maxMemory() > 0 ? overrides.maxMemory() : Config.MAX_MEMORY.get();
        if (maxMemory > 0) {
            request.addProperty("maxMemory", maxMemory);
        }
        boolean globalIsolation = Config.VERSION_ISOLATION.get().isolates(instance.hasModLoader());
        request.addProperty("versionIsolation", overrides.versionIsolation().resolve(globalIsolation));

        LaunchHandle handle = new LaunchHandle(
                instance.id(), new CompletableFuture<>(), new CompletableFuture<>());
        registerLaunch(instance.id(), handle);
        if (logSink != null) {
            logSinks.put(instance.id(), logSink);
        }

        try {
            send(request).whenComplete((response, error) -> {
                if (error != null) {
                    failLaunch(instance.id(), error);
                    return;
                }
                try {
                    requireSuccess(response);
                } catch (IOException exception) {
                    failLaunch(instance.id(), exception);
                }
            });
        } catch (IOException | RuntimeException exception) {
            failLaunch(instance.id(), exception);
            throw exception;
        }
        return handle;
    }

    public boolean isRunning(String instanceId) {
        LaunchHandle handle = runningInstances.get(instanceId);
        return handle != null && !handle.exitCode().isDone();
    }

    /**
     * Starts installing {@code gameVersion} into a new instance and reports
     * progress through {@code logSink}.
     */
    public InstallHandle install(String instanceId, String gameVersion, Consumer<String> logSink)
            throws IOException {
        return install(instanceId, gameVersion, List.of(), logSink);
    }

    /**
     * Starts installing {@code gameVersion} into a new instance with the given mod
     * loaders layered
     * on top and reports progress through {@code logSink}.
     */
    public InstallHandle install(
            String instanceId,
            String gameVersion,
            List<LoaderSpec> loaders,
            Consumer<String> logSink) throws IOException {
        return startInstall("install", instanceId, gameVersion, loaders, logSink);
    }

    /**
     * Starts repairing an existing instance; accepted and terminal events mirror
     * {@link #install}.
     */
    public InstallHandle repair(String instanceId, Consumer<String> logSink) throws IOException {
        return startInstall("repair", instanceId, null, List.of(), logSink);
    }

    public boolean isInstalling(String instanceId) {
        InstallHandle install = runningInstalls.get(instanceId);
        return install != null && !install.completion().isDone();
    }

    private InstallHandle startInstall(
            String command,
            String instanceId,
            String gameVersion,
            List<LoaderSpec> loaders,
            Consumer<String> logSink) throws IOException {
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalArgumentException("instanceId must not be blank");
        }
        if ("install".equals(command) && (gameVersion == null || gameVersion.isBlank())) {
            throw new IllegalArgumentException("gameVersion must not be blank");
        }
        List<LoaderSpec> specs = loaders == null ? List.of() : loaders;
        for (LoaderSpec spec : specs) {
            if (spec.type() == null || spec.type().isBlank()) {
                throw new IllegalArgumentException("Loader type must not be blank");
            }
            if (spec.version() == null || spec.version().isBlank()) {
                throw new IllegalArgumentException("Loader version must not be blank");
            }
        }

        InstallHandle handle = new InstallHandle(instanceId, new CompletableFuture<>());
        registerInstall(instanceId, handle);
        if (logSink != null) {
            logSinks.put(instanceId, logSink);
        }

        JsonObject request = new JsonObject();
        request.addProperty("command", command);
        request.addProperty("instanceId", instanceId);
        if (gameVersion != null) {
            request.addProperty("gameVersion", gameVersion);
            request.addProperty("versionIsolation",
                    Config.VERSION_ISOLATION.get().isolates(!specs.isEmpty()));
        }
        if (!specs.isEmpty()) {
            JsonArray loaderArray = new JsonArray();
            for (LoaderSpec spec : specs) {
                JsonObject loader = new JsonObject();
                loader.addProperty("type", spec.type());
                loader.addProperty("version", spec.version());
                loaderArray.add(loader);
            }
            request.add("loaders", loaderArray);
        }

        try {
            send(request).whenComplete((response, error) -> {
                if (error != null) {
                    failInstall(instanceId, error);
                    return;
                }
                try {
                    requireSuccess(response);
                } catch (IOException exception) {
                    failInstall(instanceId, exception);
                }
            });
        } catch (IOException | RuntimeException exception) {
            failInstall(instanceId, exception);
            throw exception;
        }
        return handle;
    }

    public HelperInfo helperInfo() {
        return helperInfo;
    }

    public void stop(String instanceId) {
        boolean running = isRunning(instanceId);
        boolean installing = isInstalling(instanceId);
        if (!running && !installing) {
            return;
        }

        JsonObject request = new JsonObject();
        request.addProperty("command", "stop");
        request.addProperty("instanceId", instanceId);
        try {
            send(request).whenComplete((response, error) -> {
                if (error != null) {
                    failStop(instanceId, running, installing, error);
                    return;
                }
                try {
                    requireSuccess(response);
                } catch (IOException exception) {
                    failStop(instanceId, running, installing, exception);
                }
            });
        } catch (IOException exception) {
            failStop(instanceId, running, installing, exception);
        }
    }

    private CompletableFuture<JsonObject> send(JsonObject request) throws IOException {
        CompletableFuture<Void> readiness = ensureStarted();
        return readiness.thenCompose(ignored -> {
            try {
                return sendToProcess(request, null);
            } catch (IOException exception) {
                return CompletableFuture.failedFuture(exception);
            }
        });
    }

    /**
     * Sends on the current process, optionally requiring a specific process
     * identity.
     */
    private CompletableFuture<JsonObject> sendToProcess(JsonObject request, Process expectedProcess)
            throws IOException {
        String requestId = UUID.randomUUID().toString();
        request.addProperty("id", requestId);
        CompletableFuture<JsonObject> response = new CompletableFuture<>();
        Process brokenProcess = null;

        try {
            synchronized (processLock) {
                Process currentProcess = process;
                if (currentProcess == null
                        || (expectedProcess != null && currentProcess != expectedProcess)
                        || !currentProcess.isAlive()
                        || writer == null) {
                    brokenProcess = expectedProcess != null && currentProcess == expectedProcess
                            ? currentProcess
                            : null;
                    throw new IOException("HMCL helper output is not available");
                }
                pendingRequests.put(requestId, response);
                try {
                    writer.write(request.toString());
                    writer.newLine();
                    writer.flush();
                } catch (IOException exception) {
                    pendingRequests.remove(requestId);
                    response.completeExceptionally(exception);
                    brokenProcess = currentProcess;
                    throw exception;
                }
            }
        } catch (IOException exception) {
            if (brokenProcess != null) {
                failConnection(brokenProcess, exception);
            }
            throw exception;
        }

        response.orTimeout(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .whenComplete((ignored, ignoredError) -> pendingRequests.remove(requestId));
        return response;
    }

    private CompletableFuture<Void> ensureStarted() throws IOException {
        Process startedProcess;
        CompletableFuture<Void> readiness;
        synchronized (processLock) {
            if (process != null && process.isAlive()) {
                return ready;
            }

            Process staleProcess = process;
            if (staleProcess != null) {
                process = null;
                writer = null;
                failPendingAndLaunches(new IOException(
                        "HMCL helper exited with code " + safeExitCode(staleProcess)));
                ready = null;
                helperInfo = null;
            }

            // The mod bundles a helper JAR; extract it when the configured
            // location is empty or still holds one of our older extractions.
            try {
                HelperBundle.ensureExtracted(helperJar);
            } catch (IOException exception) {
                throw new IOException("Could not extract the bundled HMCL helper to " + helperJar, exception);
            }
            if (!Files.isRegularFile(helperJar)) {
                throw new IOException("HMCL helper JAR does not exist: " + helperJar
                        + " (this mod build does not bundle one either)");
            }
            Files.createDirectories(repositoryDirectory);
            Files.createDirectories(workingDirectory);

            ProcessBuilder builder = new ProcessBuilder(
                    currentJavaExecutable().toString(),
                    "--enable-native-access=ALL-UNNAMED",
                    "-jar",
                    helperJar.toString(),
                    "--repository",
                    repositoryDirectory.toString(),
                    "--javafx-dir",
                    helperJar.resolveSibling("javafx").toString());
            String downloadProvider = Config.DOWNLOAD_PROVIDER.get();
            if ("mojang".equals(downloadProvider) || "bmclapi".equals(downloadProvider)) {
                builder.command().addAll(List.of("--download-provider", downloadProvider));
            }
            builder.directory(workingDirectory.toFile());
            startedProcess = builder.start();
            process = startedProcess;
            writer = new BufferedWriter(
                    new OutputStreamWriter(startedProcess.getOutputStream(), StandardCharsets.UTF_8));
            readiness = new CompletableFuture<>();
            ready = readiness;
            helperInfo = null;

            // Bind each reader to the process it was created for. Looking up
            // the volatile field inside the reader would let a delayed old
            // reader consume bytes from a freshly restarted helper.
            Thread.ofVirtual().name("mcmcl-hmcl-helper-reader").start(
                    () -> readResponses(startedProcess));
            Thread.ofVirtual().name("mcmcl-hmcl-helper-errors").start(
                    () -> readErrors(startedProcess));
        }

        JsonObject hello = new JsonObject();
        hello.addProperty("command", "hello");
        try {
            sendToProcess(hello, startedProcess).whenComplete((response, error) -> {
                if (error != null) {
                    readiness.completeExceptionally(error);
                    return;
                }
                try {
                    HelperInfo info = parseHello(response);
                    synchronized (processLock) {
                        if (process == startedProcess) {
                            helperInfo = info;
                        }
                    }
                    readiness.complete(null);
                } catch (IOException exception) {
                    readiness.completeExceptionally(exception);
                    failConnection(startedProcess, exception);
                    startedProcess.destroy();
                }
            });
        } catch (IOException exception) {
            readiness.completeExceptionally(exception);
            throw exception;
        }
        return readiness;
    }

    private void readResponses(Process observedProcess) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(observedProcess.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    handleMessage(JsonParser.parseString(line));
                } catch (RuntimeException exception) {
                    MinecraftMinecraftLauncher.LOGGER.warn("Invalid HMCL helper message", exception);
                }
            }
            int exitCode = observedProcess.waitFor();
            failConnection(observedProcess, new IOException("HMCL helper exited with code " + exitCode));
        } catch (Exception exception) {
            failConnection(observedProcess, exception);
        }
    }

    private void readErrors(Process observedProcess) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(observedProcess.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                MinecraftMinecraftLauncher.LOGGER.debug("[HMCL helper] {}", line);
            }
        } catch (IOException exception) {
            MinecraftMinecraftLauncher.LOGGER.debug("Could not read HMCL helper stderr", exception);
        }
    }

    private void handleMessage(JsonElement element) {
        if (!element.isJsonObject()) {
            MinecraftMinecraftLauncher.LOGGER.warn("Ignoring non-object HMCL helper message: {}", element);
            return;
        }
        JsonObject message = element.getAsJsonObject();
        String type = string(message, "type", "");
        if ("response".equals(type)) {
            CompletableFuture<JsonObject> response = pendingRequests.remove(string(message, "id", ""));
            if (response != null) {
                response.complete(message);
            }
            return;
        }
        if ("event".equals(type)) {
            handleEvent(message);
            return;
        }
        MinecraftMinecraftLauncher.LOGGER.warn("Ignoring unknown HMCL helper message: {}", message);
    }

    private void handleEvent(JsonObject event) {
        String instanceId = string(event, "instanceId", "");
        String eventName = string(event, "event", "");
        Consumer<String> logSink = logSinks.get(instanceId);
        if ("started".equals(eventName)) {
            LaunchHandle handle = runningInstances.get(instanceId);
            if (handle != null) {
                handle.started().complete(null);
            }
        } else if ("log".equals(eventName) && logSink != null) {
            logSink.accept(string(event, "line", ""));
        } else if ("error".equals(eventName)) {
            String message = string(event, "message", "HMCL helper reported an unknown error");
            if (logSink != null) {
                logSink.accept(message);
            }
            MinecraftMinecraftLauncher.LOGGER.warn("HMCL instance {} failed: {}", instanceId, message);
            LaunchHandle handle = runningInstances.remove(instanceId);
            logSinks.remove(instanceId);
            if (handle != null && !handle.exitCode().isDone()) {
                IOException exception = new IOException(message);
                handle.started().completeExceptionally(exception);
                handle.exitCode().completeExceptionally(exception);
                return;
            }
            InstallHandle install = runningInstalls.remove(instanceId);
            if (install != null && !install.completion().isDone()) {
                install.completion().completeExceptionally(new IOException(message));
            }
        } else if ("exit".equals(eventName)) {
            LaunchHandle handle = runningInstances.remove(instanceId);
            logSinks.remove(instanceId);
            if (handle != null && !handle.exitCode().isDone()) {
                int exitCode = integer(event, "code", -1);
                if (!handle.started().isDone()) {
                    handle.started().completeExceptionally(
                            new IOException("HMCL game process exited before reporting startup"));
                }
                handle.exitCode().complete(exitCode);
                return;
            }
            InstallHandle install = runningInstalls.remove(instanceId);
            if (install != null && !install.completion().isDone()) {
                install.completion().complete(integer(event, "code", -1));
            }
        }
    }

    private HelperInfo parseHello(JsonObject response) throws IOException {
        requireSuccess(response);
        int protocolVersion = integer(response, "protocolVersion", -1);
        if (protocolVersion != PROTOCOL_VERSION) {
            throw new IOException("Incompatible HMCL helper protocol " + protocolVersion
                    + "; this mod requires " + PROTOCOL_VERSION);
        }
        return new HelperInfo(
                protocolVersion,
                string(response, "helperVersion", "unknown"),
                string(response, "backend", "unknown"),
                bool(response, "launchAvailable", false),
                string(response, "hmclCommit", "unknown"));
    }

    private List<HmclInstance> parseInstances(JsonObject response) {
        try {
            requireSuccess(response);
            JsonArray array = response.getAsJsonArray("instances");
            List<HmclInstance> result = new ArrayList<>();
            if (array == null) {
                return List.of();
            }
            for (JsonElement element : array) {
                JsonObject instance = element.getAsJsonObject();
                String instanceId = string(instance, "instanceId", "");
                String name = string(instance, "name", instanceId);
                String version = string(instance, "version", instanceId);
                String loader = string(instance, "loader", "");
                String root = string(instance, "root", "");
                result.add(new HmclInstance(
                        instanceId,
                        name,
                        version,
                        loader,
                        root));
            }
            return List.copyOf(result);
        } catch (IOException exception) {
            throw new IllegalStateException(exception.getMessage(), exception);
        }
    }

    private List<RemoteVersion> parseRemoteVersions(JsonObject response) {
        try {
            requireSuccess(response);
            JsonArray array = response.getAsJsonArray("versions");
            List<RemoteVersion> result = new ArrayList<>();
            if (array == null) {
                return List.of();
            }
            for (JsonElement element : array) {
                JsonObject version = element.getAsJsonObject();
                result.add(new RemoteVersion(
                        string(version, "id", ""),
                        string(version, "type", ""),
                        string(version, "releaseTime", "")));
            }
            return List.copyOf(result);
        } catch (IOException exception) {
            throw new IllegalStateException(exception.getMessage(), exception);
        }
    }

    private static void requireSuccess(JsonObject response) throws IOException {
        JsonElement ok = response.get("ok");
        if (ok == null || !ok.isJsonPrimitive() || !ok.getAsBoolean()) {
            throw new IOException(string(response, "message", "HMCL helper request failed"));
        }
    }

    private void failLaunch(String instanceId, Throwable error) {
        LaunchHandle handle = runningInstances.remove(instanceId);
        logSinks.remove(instanceId);
        if (handle != null) {
            handle.started().completeExceptionally(error);
            handle.exitCode().completeExceptionally(error);
        }
    }

    private void failInstall(String instanceId, Throwable error) {
        InstallHandle install = runningInstalls.remove(instanceId);
        logSinks.remove(instanceId);
        if (install != null) {
            install.completion().completeExceptionally(error);
        }
    }

    private void failStop(String instanceId, boolean running, boolean installing, Throwable error) {
        if (running) {
            failLaunch(instanceId, error);
        }
        if (installing) {
            failInstall(instanceId, error);
        }
    }

    private void failConnection(Process observedProcess, Throwable error) {
        synchronized (processLock) {
            if (process != observedProcess) {
                return;
            }
            writer = null;
            process = null;
            failPendingAndLaunches(error);
            ready = null;
            helperInfo = null;
        }
    }

    private void failPendingAndLaunches(Throwable error) {
        CompletableFuture<Void> readiness = ready;
        if (readiness != null) {
            readiness.completeExceptionally(error);
        }
        for (CompletableFuture<JsonObject> request : pendingRequests.values()) {
            request.completeExceptionally(error);
        }
        pendingRequests.clear();
        for (LaunchHandle handle : runningInstances.values()) {
            handle.started().completeExceptionally(error);
            handle.exitCode().completeExceptionally(error);
        }
        runningInstances.clear();
        for (InstallHandle install : runningInstalls.values()) {
            install.completion().completeExceptionally(error);
        }
        runningInstalls.clear();
        logSinks.clear();
    }

    private void registerInstall(String instanceId, InstallHandle handle) throws IOException {
        while (true) {
            InstallHandle current = runningInstalls.get(instanceId);
            if (current != null) {
                if (!current.completion().isDone()) {
                    throw new IOException("Instance is already installing: " + instanceId);
                }
                if (!runningInstalls.replace(instanceId, current, handle)) {
                    continue;
                }
                return;
            }
            if (runningInstalls.putIfAbsent(instanceId, handle) == null) {
                return;
            }
        }
    }

    private void registerLaunch(String instanceId, LaunchHandle handle) throws IOException {
        while (true) {
            LaunchHandle current = runningInstances.get(instanceId);
            if (current != null) {
                if (!current.exitCode().isDone()) {
                    throw new IOException("Instance is already running: " + instanceId);
                }
                if (!runningInstances.replace(instanceId, current, handle)) {
                    continue;
                }
                return;
            }
            if (runningInstances.putIfAbsent(instanceId, handle) == null) {
                return;
            }
        }
    }

    private static int safeExitCode(Process process) {
        try {
            return process.exitValue();
        } catch (IllegalThreadStateException ignored) {
            return -1;
        }
    }

    private static String string(JsonObject object, String key, String fallback) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : fallback;
    }

    private static int integer(JsonObject object, String key, int fallback) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsInt() : fallback;
    }

    private static boolean bool(JsonObject object, String key, boolean fallback) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsBoolean() : fallback;
    }

    private static Path currentJavaExecutable() {
        Path javaHome = Path.of(System.getProperty("java.home"));
        String executable = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "java.exe"
                : "java";
        return javaHome.resolve("bin").resolve(executable).toAbsolutePath().normalize();
    }

    @Override
    public void close() {
        Process observedProcess;
        synchronized (processLock) {
            observedProcess = process;
        }
        if (observedProcess == null) {
            return;
        }

        JsonObject request = new JsonObject();
        request.addProperty("command", "shutdown");
        try {
            CompletableFuture<JsonObject> response = sendToProcess(request, observedProcess);
            try {
                response.get(2, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (Exception ignored) {
                // The helper may have exited while processing shutdown.
            }
        } catch (IOException ignored) {
            // The process may already have exited; the final destroy is still safe.
        }
        if (observedProcess.isAlive()) {
            observedProcess.destroy();
            try {
                if (!observedProcess.waitFor(2, TimeUnit.SECONDS)) {
                    observedProcess.destroyForcibly();
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                observedProcess.destroyForcibly();
            }
        }
    }

    public record LaunchHandle(
            String instanceId,
            CompletableFuture<Void> started,
            CompletableFuture<Integer> exitCode) {
    }

    /**
     * Tracks one accepted install/repair request; {@code completion} gets the
     * process exit code.
     */
    public record InstallHandle(String instanceId, CompletableFuture<Integer> completion) {
    }

    public record RemoteVersion(String id, String type, String releaseTime) {
    }

    /** Describes one mod loader to install on top of the requested game version. */
    public record LoaderSpec(String type, String version) {
    }

    public record HelperInfo(
            int protocolVersion,
            String helperVersion,
            String backend,
            boolean launchAvailable,
            String hmclCommit) {
    }
}
