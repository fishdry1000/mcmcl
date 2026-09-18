package top.fish1000.mcmcl.helper;

import top.fish1000.mcmcl.helper.hmcl.HmclCoreAdapter;
import top.fish1000.mcmcl.helper.hmcl.HmclInstallHandle;
import top.fish1000.mcmcl.helper.hmcl.HmclInstallRequest;
import top.fish1000.mcmcl.helper.hmcl.HmclLaunchEventSink;
import top.fish1000.mcmcl.helper.hmcl.HmclLaunchHandle;
import top.fish1000.mcmcl.helper.hmcl.HmclLaunchRequest;
import top.fish1000.mcmcl.helper.protocol.Json;
import top.fish1000.mcmcl.helper.protocol.JsonLineWriter;
import top.fish1000.mcmcl.helper.protocol.ProtocolException;
import top.fish1000.mcmcl.helper.repository.InstanceDescriptor;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JSON Lines command loop. The class is deliberately independent of HMCL
 * classes; only {@link HmclCoreAdapter} knows how to invoke HMCL Core.
 */
public final class HelperServer {
    static final String CODE_INVALID_REQUEST = "INVALID_REQUEST";
    static final String CODE_UNKNOWN_COMMAND = "UNKNOWN_COMMAND";
    static final String CODE_HMCL_CORE_UNAVAILABLE = "HMCL_CORE_UNAVAILABLE";
    static final String CODE_ALREADY_RUNNING = "ALREADY_RUNNING";
    static final String CODE_NOT_RUNNING = "NOT_RUNNING";
    static final String CODE_INTERNAL_ERROR = "INTERNAL_ERROR";
    static final String CODE_CANCELLED = "CANCELLED";

    private final HmclCoreAdapter adapter;
    private final JsonLineWriter output;
    private final ExecutorService launchExecutor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "mcmcl-hmcl-launch");
        thread.setDaemon(true);
        return thread;
    });
    // The repository backend allows only one exclusive draft at a time, so
    // installs are serialized on a single thread.
    private final ExecutorService installExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "mcmcl-hmcl-install");
        thread.setDaemon(true);
        return thread;
    });
    private final ConcurrentMap<String, LaunchSlot> launches = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, InstallSlot> installs = new ConcurrentHashMap<>();
    private final AtomicBoolean shuttingDown = new AtomicBoolean();

    public HelperServer(HmclCoreAdapter adapter, JsonLineWriter output) {
        this.adapter = adapter;
        this.output = output;
    }

    /**
     * Reads requests until EOF or a shutdown request. A malformed line gets a
     * response with a null id and does not terminate the helper.
     */
    public int run(BufferedReader input) throws IOException {
        String line;
        try {
            while (!shuttingDown.get() && (line = input.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                handleLine(line);
            }
        } finally {
            shutdown();
        }
        return 0;
    }

    private void handleLine(String line) {
        Map<String, Object> request;
        Object id = null;
        try {
            Object parsed = Json.parse(line);
            if (!(parsed instanceof Map<?, ?> parsedObject)) {
                throw new ProtocolException("request must be a JSON object");
            }
            request = castObject(parsedObject);
            if (request.containsKey("id")) {
                id = request.get("id");
            }
            if (!request.containsKey("id")) {
                throw new ProtocolException("request is missing id");
            }
            if (!Json.isJsonScalar(id)) {
                throw new ProtocolException("id must be a JSON string, number, boolean, or null");
            }
            String command = Json.requiredString(request, "command");
            switch (command) {
                case "hello" -> hello(id);
                case "list" -> list(id);
                case "launch" -> launch(id, request);
                case "install" -> install(id, request);
                case "repair" -> repair(id, request);
                case "remoteVersions" -> remoteVersions(id, request);
                case "stop" -> stop(id, request);
                case "shutdown" -> {
                    respondOk(id, "shutdown requested");
                    shutdown();
                }
                default -> respondError(id, CODE_UNKNOWN_COMMAND, "unknown command: " + command);
            }
        } catch (ProtocolException e) {
            respondError(id, CODE_INVALID_REQUEST, e.getMessage());
        } catch (Exception e) {
            respondError(id, CODE_INTERNAL_ERROR, safeMessage(e));
        }
    }

    private void hello(Object id) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("type", "response");
        response.put("id", id);
        response.put("ok", true);
        response.put("protocolVersion", HelperBuildInfo.PROTOCOL_VERSION);
        response.put("helperVersion", HelperBuildInfo.helperVersion());
        response.put("backend", adapter.backendName());
        response.put("launchAvailable", adapter.isLaunchAvailable());
        response.put("installAvailable", adapter.isInstallAvailable());
        response.put("hmclProfile", HelperBuildInfo.hmclProfile());
        response.put("hmclCommit", HelperBuildInfo.hmclCommit());
        output.write(response);
    }

    private void list(Object id) {
        try {
            List<Map<String, Object>> instances = new ArrayList<>();
            for (InstanceDescriptor instance : adapter.listInstances()) {
                instances.add(instance.toJson());
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("type", "response");
            body.put("id", id);
            body.put("ok", true);
            body.put("instances", instances);
            output.write(body);
        } catch (Exception e) {
            respondError(id, CODE_INTERNAL_ERROR, safeMessage(e));
        }
    }

    private void remoteVersions(Object id, Map<String, Object> request) {
        String component;
        String gameVersion;
        try {
            component = Json.optionalString(request, "component");
            gameVersion = Json.optionalString(request, "gameVersion");
            if (component != null && gameVersion == null) {
                throw new ProtocolException("gameVersion is required when component is set");
            }
        } catch (ProtocolException e) {
            respondError(id, CODE_INVALID_REQUEST, e.getMessage());
            return;
        }

        if (!adapter.isInstallAvailable()) {
            respondError(id, CODE_HMCL_CORE_UNAVAILABLE, adapter.unavailableMessage());
            return;
        }
        try {
            List<Map<String, Object>> versions = new ArrayList<>();
            for (var version : adapter.listRemoteVersions(component, gameVersion)) {
                versions.add(version.toJson());
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("type", "response");
            body.put("id", id);
            body.put("ok", true);
            body.put("versions", versions);
            output.write(body);
        } catch (Exception e) {
            respondError(id, CODE_INTERNAL_ERROR, safeMessage(e));
        }
    }

    private void install(Object id, Map<String, Object> request) {
        acceptInstall(id, request, true);
    }

    private void repair(Object id, Map<String, Object> request) {
        acceptInstall(id, request, false);
    }

    private void acceptInstall(Object id, Map<String, Object> request, boolean requireGameVersion) {
        HmclInstallRequest installRequest;
        try {
            installRequest = requireGameVersion
                    ? HmclInstallRequest.install(request)
                    : HmclInstallRequest.repair(request);
        } catch (ProtocolException e) {
            respondError(id, CODE_INVALID_REQUEST, e.getMessage());
            return;
        }

        if (!adapter.isInstallAvailable()) {
            respondError(id, CODE_HMCL_CORE_UNAVAILABLE, adapter.unavailableMessage());
            return;
        }

        String instanceId = installRequest.instanceId();
        InstallSlot slot = new InstallSlot(instanceId);
        if (installs.putIfAbsent(instanceId, slot) != null) {
            respondError(id, CODE_ALREADY_RUNNING, "instance is already installing: " + instanceId);
            return;
        }

        respondOk(id, requireGameVersion ? "install accepted" : "repair accepted");
        try {
            slot.task = installExecutor.submit(() -> startInstall(slot, installRequest));
        } catch (Exception exception) {
            installs.remove(instanceId, slot);
            emitError(instanceId, CODE_INTERNAL_ERROR, safeMessage(exception));
        }
    }

    private void startInstall(InstallSlot slot, HmclInstallRequest request) {
        slot.taskStarted.set(true);
        AtomicBoolean terminal = new AtomicBoolean();
        HmclLaunchEventSink sink = new HmclLaunchEventSink() {
            @Override
            public void started() {
                // Installs have no process; progress is reported through log.
            }

            @Override
            public void log(String line) {
                if (!terminal.get()) {
                    emitLog(request.instanceId(), line);
                }
            }

            @Override
            public void exit(int code) {
                if (terminal.compareAndSet(false, true)) {
                    installs.remove(request.instanceId(), slot);
                    emitExit(request.instanceId(), code);
                }
            }

            @Override
            public void error(String message) {
                if (terminal.compareAndSet(false, true)) {
                    installs.remove(request.instanceId(), slot);
                    emitError(request.instanceId(), "HMCL_CORE_ERROR", message);
                }
            }
        };

        try {
            HmclInstallHandle handle = adapter.install(request, sink);
            if (handle == null) {
                throw new IllegalStateException("HMCL adapter returned no install handle");
            }
            slot.handle = handle;
        } catch (Exception e) {
            installs.remove(request.instanceId(), slot);
            if (terminal.compareAndSet(false, true)) {
                emitError(
                        request.instanceId(),
                        slot.cancelRequested.get() ? "CANCELLED" : "HMCL_CORE_ERROR",
                        slot.cancelRequested.get() ? "install cancelled" : safeMessage(e));
            }
        }
    }

    private void launch(Object id, Map<String, Object> request) {
        HmclLaunchRequest launchRequest;
        try {
            launchRequest = HmclLaunchRequest.from(request);
        } catch (ProtocolException e) {
            respondError(id, CODE_INVALID_REQUEST, e.getMessage());
            return;
        }

        if (!adapter.isLaunchAvailable()) {
            respondError(id, CODE_HMCL_CORE_UNAVAILABLE, adapter.unavailableMessage());
            return;
        }

        String instanceId = launchRequest.instanceId();
        LaunchSlot slot = new LaunchSlot(instanceId);
        if (launches.putIfAbsent(instanceId, slot) != null) {
            respondError(id, CODE_ALREADY_RUNNING, "instance is already launching or running: " + instanceId);
            return;
        }

        respondOk(id, "launch accepted");
        try {
            slot.task = launchExecutor.submit(() -> startLaunch(slot, launchRequest));
            if (slot.stopRequested.get()) {
                // A stop may have arrived between putIfAbsent and submit.
                // Re-run the cancellation path after the task is visible.
                stopSlot(slot);
            }
        } catch (Exception exception) {
            launches.remove(instanceId, slot);
            emitError(instanceId, CODE_INTERNAL_ERROR, safeMessage(exception));
        }
    }

    private void startLaunch(LaunchSlot slot, HmclLaunchRequest request) {
        slot.taskStarted.set(true);
        AtomicBoolean terminal = new AtomicBoolean();
        HmclLaunchEventSink sink = new HmclLaunchEventSink() {
            @Override
            public void started() {
                if (!terminal.get()) {
                    emitStarted(request.instanceId());
                }
            }

            @Override
            public void log(String line) {
                if (!terminal.get()) {
                    emitLog(request.instanceId(), line);
                }
            }

            @Override
            public void exit(int code) {
                if (terminal.compareAndSet(false, true)) {
                    launches.remove(request.instanceId(), slot);
                    emitExit(request.instanceId(), code);
                }
            }

            @Override
            public void error(String message) {
                if (terminal.compareAndSet(false, true)) {
                    launches.remove(request.instanceId(), slot);
                    emitError(request.instanceId(), "HMCL_CORE_ERROR", message);
                }
            }
        };

        try {
            if (slot.stopRequested.get()) {
                throw new LaunchCancelledException();
            }
            HmclLaunchHandle handle = adapter.launch(request, sink);
            if (handle == null) {
                throw new IllegalStateException("HMCL adapter returned no launch handle");
            }
            slot.handle = handle;
            if (terminal.get() || slot.stopRequested.get()) {
                stopSlot(slot);
            }
        } catch (Exception e) {
            launches.remove(request.instanceId(), slot);
            if (terminal.compareAndSet(false, true)) {
                emitError(
                        request.instanceId(),
                        slot.stopRequested.get() ? CODE_CANCELLED : CODE_INTERNAL_ERROR,
                        slot.stopRequested.get() ? "launch cancelled before the game process started" : safeMessage(e));
            }
        }
    }

    private void stop(Object id, Map<String, Object> request) {
        final String instanceId;
        try {
            instanceId = Json.requiredInstanceId(request);
        } catch (ProtocolException e) {
            respondError(id, CODE_INVALID_REQUEST, e.getMessage());
            return;
        }

        LaunchSlot launchSlot = launches.get(instanceId);
        if (launchSlot != null) {
            launchSlot.stopRequested.set(true);
            try {
                stopSlot(launchSlot);
                respondOk(id, "stop requested");
            } catch (Exception e) {
                respondError(id, CODE_INTERNAL_ERROR, safeMessage(e));
            }
            return;
        }

        InstallSlot installSlot = installs.get(instanceId);
        if (installSlot != null) {
            installSlot.cancelRequested.set(true);
            try {
                stopInstallSlot(installSlot);
                respondOk(id, "stop requested");
            } catch (Exception e) {
                respondError(id, CODE_INTERNAL_ERROR, safeMessage(e));
            }
            return;
        }

        respondError(id, CODE_NOT_RUNNING, "instance is not launching, installing, or running: " + instanceId);
    }

    private void stopInstallSlot(InstallSlot slot) {
        HmclInstallHandle handle = slot.handle;
        if (handle != null) {
            try {
                handle.cancel();
            } catch (Exception e) {
                // Cancellation is best-effort; the install task reports the
                // terminal error event itself.
            }
        }
        Future<?> task = slot.task;
        if (task != null && slot.taskStarted.get()) {
            task.cancel(true);
        }
    }

    private void shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) {
            return;
        }

        for (LaunchSlot slot : List.copyOf(launches.values())) {
            try {
                slot.stopRequested.set(true);
                stopSlot(slot);
            } catch (Exception e) {
                emitError(slot.instanceId, CODE_INTERNAL_ERROR, safeMessage(e));
            }
        }

        for (InstallSlot slot : List.copyOf(installs.values())) {
            slot.cancelRequested.set(true);
            stopInstallSlot(slot);
        }

        launchExecutor.shutdown();
        try {
            if (!launchExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                launchExecutor.shutdownNow();
                launchExecutor.awaitTermination(1, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            launchExecutor.shutdownNow();
        }
        installExecutor.shutdown();
        try {
            if (!installExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                installExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            installExecutor.shutdownNow();
        }
        try {
            adapter.shutdown();
        } catch (Exception e) {
            // There is no request left to attach this to. Keep protocol
            // stdout valid and leave diagnostics to the adapter/OS log.
        }
    }

    private void stopSlot(LaunchSlot slot) throws Exception {
        HmclLaunchHandle handle = slot.handle;
        if (handle != null) {
            handle.stop();
        } else {
            // A launch task may still be preparing the HMCL process and not
            // have returned its opaque handle yet. Interrupt it when it has
            // started, and also notify the adapter so it can cancel work that
            // is not interruptible at this layer.
            Future<?> task = slot.task;
            if (task != null && slot.taskStarted.get()) {
                task.cancel(true);
            }
            adapter.stop(slot.instanceId);
        }
    }

    private void respondOk(Object id, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("type", "response");
        response.put("id", id);
        response.put("ok", true);
        if (message != null) {
            response.put("message", message);
        }
        output.write(response);
    }

    private void respondError(Object id, String code, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("type", "response");
        response.put("id", id);
        response.put("ok", false);
        response.put("code", code);
        response.put("message", message == null ? "unknown error" : message);
        output.write(response);
    }

    private void emitStarted(String instanceId) {
        Map<String, Object> event = event(instanceId, "started");
        output.write(event);
    }

    private void emitLog(String instanceId, String line) {
        Map<String, Object> event = event(instanceId, "log");
        event.put("line", line == null ? "" : line);
        output.write(event);
    }

    private void emitExit(String instanceId, int code) {
        Map<String, Object> event = event(instanceId, "exit");
        event.put("code", code);
        output.write(event);
    }

    private void emitError(String instanceId, String code, String message) {
        Map<String, Object> event = event(instanceId, "error");
        event.put("code", code);
        event.put("message", message == null ? "unknown error" : message);
        output.write(event);
    }

    private static Map<String, Object> event(String instanceId, String name) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "event");
        event.put("instanceId", instanceId);
        event.put("event", name);
        return event;
    }

    private static Map<String, Object> castObject(Map<?, ?> object) throws ProtocolException {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : object.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new ProtocolException("request object keys must be strings");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    private static final class LaunchCancelledException extends Exception {
        private LaunchCancelledException() {
            super("launch cancelled");
        }
    }

    private static final class LaunchSlot {
        private final String instanceId;
        private final AtomicBoolean stopRequested = new AtomicBoolean();
        private final AtomicBoolean taskStarted = new AtomicBoolean();
        private volatile HmclLaunchHandle handle;
        private volatile Future<?> task;

        private LaunchSlot(String instanceId) {
            this.instanceId = instanceId;
        }
    }

    private static final class InstallSlot {
        private final String instanceId;
        private final AtomicBoolean taskStarted = new AtomicBoolean();
        private final AtomicBoolean cancelRequested = new AtomicBoolean();
        private volatile HmclInstallHandle handle;
        private volatile Future<?> task;

        private InstallSlot(String instanceId) {
            this.instanceId = instanceId;
        }
    }
}
