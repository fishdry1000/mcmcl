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
 * <p>The helper owns repository loading, launch preparation and game launching for an already
 * prepared HMCL repository. This class only transports JSON Lines requests and events, so HMCL
 * dependencies never enter the NeoForge class loader.</p>
 */
public final class HmclHelperClient implements AutoCloseable {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final Path repositoryDirectory;
    private final Path helperJar;
    private final Path workingDirectory;
    private final Object processLock = new Object();
    private final Map<String, CompletableFuture<JsonObject>> pendingRequests = new ConcurrentHashMap<>();
    private final Map<String, LaunchHandle> runningInstances = new ConcurrentHashMap<>();
    private final Map<String, Consumer<String>> logSinks = new ConcurrentHashMap<>();

    private volatile Process process;
    private volatile BufferedWriter writer;

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

    public LaunchHandle launch(HmclInstance instance, Minecraft minecraft, Consumer<String> logSink)
            throws IOException {
        User user = minecraft.getUser();
        JsonObject request = new JsonObject();
        request.addProperty("command", "launch");
        request.addProperty("instanceId", instance.id());
        request.addProperty("username", user.getName());
        request.addProperty("uuid", user.getProfileId().toString());
        request.addProperty("accessToken", user.getAccessToken());
        request.addProperty("userType", "msa");
        user.getClientId().ifPresent(value -> request.addProperty("clientId", value));
        user.getXuid().ifPresent(value -> request.addProperty("xuid", value));

        LaunchHandle handle = new LaunchHandle(instance.id(), new CompletableFuture<>());
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

    public void stop(String instanceId) {
        if (!isRunning(instanceId)) {
            return;
        }

        JsonObject request = new JsonObject();
        request.addProperty("command", "stop");
        request.addProperty("instanceId", instanceId);
        try {
            send(request).whenComplete((response, error) -> {
                if (error != null) {
                    failLaunch(instanceId, error);
                    return;
                }
                try {
                    requireSuccess(response);
                } catch (IOException exception) {
                    failLaunch(instanceId, exception);
                }
            });
        } catch (IOException exception) {
            failLaunch(instanceId, exception);
        }
    }

    private CompletableFuture<JsonObject> send(JsonObject request) throws IOException {
        ensureStarted();
        return sendToProcess(request, null);
    }

    /** Sends on the current process, optionally requiring a specific process identity. */
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

    private void ensureStarted() throws IOException {
        synchronized (processLock) {
            if (process != null && process.isAlive()) {
                return;
            }

            Process staleProcess = process;
            if (staleProcess != null) {
                process = null;
                writer = null;
                failPendingAndLaunches(new IOException(
                        "HMCL helper exited with code " + safeExitCode(staleProcess)));
            }

            if (!Files.isRegularFile(helperJar)) {
                throw new IOException("HMCL helper JAR does not exist: " + helperJar);
            }
            Files.createDirectories(repositoryDirectory);
            Files.createDirectories(workingDirectory);

            ProcessBuilder builder = new ProcessBuilder(
                     currentJavaExecutable().toString(),
                    "--enable-native-access=ALL-UNNAMED",
                     "-jar",
                     helperJar.toString(),
                    "--repository",
                    repositoryDirectory.toString()
            );
            builder.directory(workingDirectory.toFile());
            Process startedProcess = builder.start();
            process = startedProcess;
            writer = new BufferedWriter(new OutputStreamWriter(startedProcess.getOutputStream(), StandardCharsets.UTF_8));

            // Bind each reader to the process it was created for.  Looking up
            // the volatile field inside the reader would let a delayed old
            // reader consume bytes from a freshly restarted helper.
            Thread.ofVirtual().name("mcmcl-hmcl-helper-reader").start(
                    () -> readResponses(startedProcess));
            Thread.ofVirtual().name("mcmcl-hmcl-helper-errors").start(
                    () -> readErrors(startedProcess));
        }
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
        if ("log".equals(eventName) && logSink != null) {
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
                handle.exitCode().completeExceptionally(new IOException(message));
            }
        } else if ("exit".equals(eventName)) {
            LaunchHandle handle = runningInstances.remove(instanceId);
            logSinks.remove(instanceId);
            if (handle != null && !handle.exitCode().isDone()) {
                handle.exitCode().complete(integer(event, "code", -1));
            }
        }
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
                String instanceId = string(instance, "instanceId", string(instance, "id", ""));
                String name = string(instance, "name", instanceId);
                String version = string(instance, "version", instanceId);
                result.add(new HmclInstance(
                        instanceId,
                        name,
                        version
                ));
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
            handle.exitCode().completeExceptionally(error);
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
        }
    }

    private void failPendingAndLaunches(Throwable error) {
        for (CompletableFuture<JsonObject> request : pendingRequests.values()) {
            request.completeExceptionally(error);
        }
        pendingRequests.clear();
        for (LaunchHandle handle : runningInstances.values()) {
            handle.exitCode().completeExceptionally(error);
        }
        runningInstances.clear();
        logSinks.clear();
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

    public record LaunchHandle(String instanceId, CompletableFuture<Integer> exitCode) {
    }
}
