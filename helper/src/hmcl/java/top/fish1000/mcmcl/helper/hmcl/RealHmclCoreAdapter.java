package top.fish1000.mcmcl.helper.hmcl;

import org.jackhuang.hmcl.auth.AuthInfo;
import org.jackhuang.hmcl.game.DefaultGameInstance;
import org.jackhuang.hmcl.game.DefaultGameRepository;
import org.jackhuang.hmcl.game.DefaultGameRepositoryLayout;
import org.jackhuang.hmcl.game.DefaultGameRepositorySnapshot;
import org.jackhuang.hmcl.game.GameInstance;
import org.jackhuang.hmcl.game.GameInstanceID;
import org.jackhuang.hmcl.game.GameInstanceManifest;
import org.jackhuang.hmcl.game.LaunchManifestNormalizer;
import org.jackhuang.hmcl.game.LaunchOptions;
import org.jackhuang.hmcl.java.JavaRuntime;
import org.jackhuang.hmcl.launch.DefaultLauncher;
import org.jackhuang.hmcl.launch.ProcessListener;
import org.jackhuang.hmcl.util.platform.ManagedProcess;
import top.fish1000.mcmcl.helper.repository.InstanceDescriptor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Real HMCL Core adapter, compiled only by the opt-in HMCL profile.
 *
 * <p>This adapter intentionally uses HMCL Core's official-layout repository
 * implementation rather than reading launch.json.  The small repository and
 * instance subclasses below supply the concrete headless types that Core
 * itself leaves abstract; no HMCL GUI/settings module is required.</p>
 */
public final class RealHmclCoreAdapter implements HmclCoreAdapter {
    private final HeadlessGameRepository repository;
    private final Object repositoryLock = new Object();
    private final ConcurrentMap<String, LaunchContext> launches = new ConcurrentHashMap<>();
    private final Set<String> activeLaunches = ConcurrentHashMap.newKeySet();
    private final Set<String> pendingStops = ConcurrentHashMap.newKeySet();

    public RealHmclCoreAdapter(Path repositoryRoot) {
        this.repository = new HeadlessGameRepository(repositoryRoot);
    }

    @Override
    public List<InstanceDescriptor> listInstances() throws Exception {
        synchronized (repositoryLock) {
            repository.refresh();
            List<InstanceDescriptor> result = new ArrayList<>();
            for (GameInstance instance : repository.getSnapshot().getInstances()) {
                result.add(new InstanceDescriptor(
                        instance.getId().toString(),
                        instance.getId().toString(),
                        instance.getVersion().toString(),
                        instance.getInstanceRoot().toAbsolutePath().normalize(),
                        instance.getManifestFile().toAbsolutePath().normalize()));
            }
            return result;
        }
    }

    @Override
    public HmclLaunchHandle launch(HmclLaunchRequest request, HmclLaunchEventSink events) throws Exception {
        if (Thread.currentThread().isInterrupted()) {
            throw new LaunchCancelledException();
        }

        String instanceId = request.instanceId();
        activeLaunches.add(instanceId);
        LaunchContext context = new LaunchContext(instanceId, events, launches, activeLaunches);
        if (launches.putIfAbsent(instanceId, context) != null) {
            activeLaunches.remove(instanceId);
            throw new IllegalStateException("instance is already launching or running: " + instanceId);
        }
        if (pendingStops.remove(instanceId)) {
            context.stop();
        }

        try {
            checkCancelled(context);
            GameInstanceID id = new GameInstanceID(instanceId);
            GameInstance instance;
            synchronized (repositoryLock) {
                checkCancelled(context);
                repository.refresh();
                checkCancelled(context);
                instance = repository.getInstance(id);
            }

            // Keep the same launch-time normalization step used by HMCL's GUI
            // launcher.  The repository still owns inheritance resolution; Core
            // owns loader-specific repairs and duplicate-library cleanup.
            GameInstanceManifest manifest = LaunchManifestNormalizer.repairForLaunch(
                    instance.getResolvedManifest());
            checkCancelled(context);
            JavaRuntime java = JavaRuntime.getDefault();
            if (java == null) {
                throw new IOException("HMCL Core could not detect the helper JVM as a Java runtime");
            }

            LaunchOptions options = new LaunchOptions.Builder()
                    .setInstanceId(id)
                    .setGameDir(instance.getRunDirectory())
                    .setJava(java)
                    .setVersionName(id.toString())
                    .setProfileName("MCMCL")
                    .setWidth(854)
                    .setHeight(480)
                    .setDaemon(false)
                    .create();
            checkCancelled(context);
            AuthInfo authInfo = new AuthInfo(
                    request.username(),
                    request.uuid(),
                    request.accessToken(),
                    request.userType(),
                    "{}");
            context.setAuthInfo(authInfo);
            checkCancelled(context);

            ProcessListener listener = new ProcessListener() {
                @Override
                public void setProcess(ManagedProcess process) {
                    context.setProcess(process);
                    events.started();
                }

                @Override
                public void onLog(String log, boolean isErrorStream) {
                    events.log(log);
                }

                @Override
                public void onExit(int exitCode, ExitType exitType) {
                    context.complete(exitCode);
                }
            };

            checkCancelled(context);
            DefaultLauncher launcher = new DefaultLauncher(
                    instance, manifest, context.authInfo(), options, listener, false);
            ManagedProcess process = launcher.launch();
            context.setProcess(process);
            return context::stop;
        } catch (Exception e) {
            launches.remove(instanceId, context);
            context.stop();
            context.closeAuth();
            throw e;
        } finally {
            activeLaunches.remove(instanceId);
        }
    }

    @Override
    public void stop(String instanceId) {
        LaunchContext context = launches.get(instanceId);
        if (context != null) {
            context.stop();
        } else if (activeLaunches.contains(instanceId)) {
            // The launch task has entered the adapter but has not published
            // its context yet.  The first launch-time cancellation check will
            // consume this marker after the context is installed.
            pendingStops.add(instanceId);
        }
    }

    @Override
    public void shutdown() {
        for (LaunchContext context : List.copyOf(launches.values())) {
            context.stop();
        }
    }

    private static void closeQuietly(AuthInfo authInfo) {
        try {
            authInfo.close();
        } catch (Exception ignored) {
            // AuthInfo currently has no close work; do not mask the launch error.
        }
    }

    private static void checkCancelled(LaunchContext context) throws LaunchCancelledException {
        if (context.isStopRequested() || Thread.currentThread().isInterrupted()) {
            throw new LaunchCancelledException();
        }
    }

    private static final class LaunchCancelledException extends IOException {
        private LaunchCancelledException() {
            super("launch cancelled");
        }
    }

    private static final class LaunchContext {
        private final String instanceId;
        private final HmclLaunchEventSink events;
        private final ConcurrentMap<String, LaunchContext> owner;
        private final Set<String> activeLaunches;
        private final Object authLock = new Object();
        private final AtomicBoolean authClosed = new AtomicBoolean();
        private final AtomicBoolean stopRequested = new AtomicBoolean();
        private final AtomicBoolean stopIssued = new AtomicBoolean();
        private final AtomicBoolean exitReported = new AtomicBoolean();
        private volatile AuthInfo authInfo;
        private volatile ManagedProcess process;

        private LaunchContext(
                String instanceId,
                HmclLaunchEventSink events,
                ConcurrentMap<String, LaunchContext> owner,
                Set<String> activeLaunches) {
            this.instanceId = instanceId;
            this.events = events;
            this.owner = owner;
            this.activeLaunches = activeLaunches;
        }

        private boolean isStopRequested() {
            return stopRequested.get();
        }

        private void setAuthInfo(AuthInfo value) {
            synchronized (authLock) {
                if (authClosed.get()) {
                    closeQuietly(value);
                } else {
                    authInfo = value;
                }
            }
        }

        private AuthInfo authInfo() {
            AuthInfo value = authInfo;
            if (value == null) {
                throw new IllegalStateException("HMCL AuthInfo was not initialized");
            }
            return value;
        }

        private void setProcess(ManagedProcess value) {
            process = value;
            if (stopRequested.get()) {
                stop();
            }
        }

        private void stop() {
            ManagedProcess current = process;
            stopRequested.set(true);
            if (current == null || !stopIssued.compareAndSet(false, true)) {
                return;
            }
            current.stop();

            // ManagedProcess.stop() interrupts HMCL's own exit waiter. Keep a
            // small independent waiter so the helper always closes the
            // launch slot and emits one final exit event.
            Thread waiter = new Thread(() -> {
                try {
                    current.getProcess().waitFor();
                    complete(current.getProcess().exitValue());
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }, "mcmcl-hmcl-exit-waiter");
            waiter.setDaemon(true);
            waiter.start();
        }

        private void complete(int exitCode) {
            if (exitReported.compareAndSet(false, true)) {
                owner.remove(instanceId, this);
                activeLaunches.remove(instanceId);
                closeAuth();
                events.exit(exitCode);
            }
        }

        private void closeAuth() {
            AuthInfo value;
            synchronized (authLock) {
                if (!authClosed.compareAndSet(false, true)) {
                    return;
                }
                value = authInfo;
                authInfo = null;
            }
            if (value != null) {
                closeQuietly(value);
            }
        }
    }

    private static final class HeadlessGameRepository extends DefaultGameRepository {
        private HeadlessGameRepository(Path baseDirectory) {
            super(baseDirectory.toAbsolutePath().normalize());
        }

        @Override
        protected DefaultGameRepositoryLayout createLayout(Path baseDirectory) {
            return new DefaultGameRepositoryLayout(baseDirectory);
        }

        @Override
        protected DefaultGameInstance createInstance(
                DefaultGameRepositorySnapshot snapshot,
                GameInstanceID id,
                GameInstanceManifest manifest,
                Path manifestFile) {
            return new HeadlessGameInstance(snapshot, id, manifest, manifestFile);
        }
    }

    private static final class HeadlessGameInstance extends DefaultGameInstance {
        private HeadlessGameInstance(
                DefaultGameRepositorySnapshot snapshot,
                GameInstanceID id,
                GameInstanceManifest manifest,
                Path manifestFile) {
            super(snapshot, id, manifest, manifestFile);
        }

        @Override
        protected DefaultGameInstance withNewSnapshot(DefaultGameRepositorySnapshot newSnapshot) {
            return new HeadlessGameInstance(newSnapshot, id, manifest, manifestFile);
        }

        @Override
        protected DefaultGameInstance withManifest(
                DefaultGameRepositorySnapshot newSnapshot,
                GameInstanceManifest newManifest) {
            return new HeadlessGameInstance(newSnapshot, id, newManifest, manifestFile);
        }
    }
}
