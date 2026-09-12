package top.fish1000.mcmcl.helper.hmcl;

import org.jackhuang.hmcl.auth.AuthInfo;
import org.jackhuang.hmcl.download.BMCLAPIDownloadProvider;
import org.jackhuang.hmcl.download.DefaultCacheRepository;
import org.jackhuang.hmcl.download.DefaultDependencyManager;
import org.jackhuang.hmcl.download.DownloadProvider;
import org.jackhuang.hmcl.download.GameBuilder;
import org.jackhuang.hmcl.download.MojangDownloadProvider;
import org.jackhuang.hmcl.download.game.GameVersionList;
import org.jackhuang.hmcl.download.game.GameRemoteVersion;
import org.jackhuang.hmcl.game.DefaultGameInstance;
import org.jackhuang.hmcl.game.DefaultGameRepository;
import org.jackhuang.hmcl.game.DefaultGameRepositoryLayout;
import org.jackhuang.hmcl.game.DefaultGameRepositorySnapshot;
import org.jackhuang.hmcl.game.GameComponentType;
import org.jackhuang.hmcl.game.GameInstance;
import org.jackhuang.hmcl.game.GameInstanceID;
import org.jackhuang.hmcl.game.GameInstanceManifest;
import org.jackhuang.hmcl.game.LaunchManifestNormalizer;
import org.jackhuang.hmcl.game.LaunchOptions;
import org.jackhuang.hmcl.game.ReleaseType;
import org.jackhuang.hmcl.java.JavaInfo;
import org.jackhuang.hmcl.java.JavaRuntime;
import org.jackhuang.hmcl.launch.DefaultLauncher;
import org.jackhuang.hmcl.launch.ProcessListener;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.task.TaskExecutor;
import org.jackhuang.hmcl.task.TaskListener;
import org.jackhuang.hmcl.util.CacheRepository;
import org.jackhuang.hmcl.util.platform.ManagedProcess;
import org.jackhuang.hmcl.util.platform.Platform;
import top.fish1000.mcmcl.helper.repository.InstanceDescriptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Real HMCL Core adapter, compiled only by the opt-in HMCL profile.
 *
 * <p>This adapter intentionally uses HMCL Core's official-layout repository
 * implementation rather than reading launch.json.  The small repository and
 * instance subclasses below supply the concrete headless types that Core
 * itself leaves abstract; no HMCL GUI/settings module is required.</p>
 */
public final class RealHmclCoreAdapter implements HmclCoreAdapter {
    private static final Pattern JAVA_VERSION_OUTPUT =
            Pattern.compile("(?:java|openjdk) version \"([^\"]+)\"");

    private final HeadlessGameRepository repository;
    private final Path repositoryRoot;
    private final String downloadProvider;
    private final Object repositoryLock = new Object();
    private final ConcurrentMap<String, LaunchContext> launches = new ConcurrentHashMap<>();
    private final Set<String> activeLaunches = ConcurrentHashMap.newKeySet();
    private final Set<String> pendingStops = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean installActive = new AtomicBoolean();
    private final AtomicReference<TaskExecutor> activeInstallExecutor = new AtomicReference<>();

    public RealHmclCoreAdapter(Path repositoryRoot, String downloadProvider) {
        this.repositoryRoot = repositoryRoot.toAbsolutePath().normalize();
        this.repository = new HeadlessGameRepository(repositoryRoot);
        this.downloadProvider = downloadProvider == null || downloadProvider.isBlank()
                ? "mojang"
                : downloadProvider;
        // HMCL's FetchTask downloads every remote file through the global
        // CacheRepository singleton, whose ETag index only exists after
        // changeDirectory.  Keep the download cache inside the HMCL repository.
        CacheRepository.getInstance().changeDirectory(this.repositoryRoot);
        // HMCL Core publishes repository snapshots and task progress through
        // JavaFX, so the toolkit must be running.  Note that the resulting
        // FX thread is not a daemon and Platform.exit() does not reliably
        // stop it without a launched Application; the process entry point
        // therefore terminates the JVM explicitly.
        try {
            javafx.application.Platform.startup(() -> {
            });
        } catch (IllegalStateException alreadyRunning) {
            // The toolkit is already up (another adapter started it).
        } catch (Throwable t) {
            System.err.println("mcmcl-hmcl-helper: JavaFX toolkit startup failed: " + t);
        }
    }

    @Override
    public boolean isInstallAvailable() {
        return true;
    }

    @Override
    public List<InstanceDescriptor> listInstances() throws Exception {
        synchronized (repositoryLock) {
            // An active install holds an exclusive repository draft, and HMCL
            // Core rejects refresh() while a draft exists.  Serving the last
            // snapshot keeps list working during long downloads.
            if (!installActive.get()) {
                repository.refresh();
            }
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
    public List<RemoteVersionDescriptor> listRemoteVersions() throws Exception {
        GameVersionList list = new GameVersionList(createDownloadProvider());
        list.refreshAsync().run();
        List<RemoteVersionDescriptor> result = new ArrayList<>();
        for (GameRemoteVersion version : list.getVersions(null)) {
            ReleaseType type = version.getType();
            result.add(new RemoteVersionDescriptor(
                    version.getGameVersion(),
                    type == null ? "unknown" : type.name().toLowerCase(Locale.ROOT),
                    version.getReleaseDate() == null ? "" : version.getReleaseDate().toString()));
        }
        result.sort(Comparator
                .comparing(RemoteVersionDescriptor::releaseTime, Comparator.nullsLast(Comparator.naturalOrder()))
                .reversed());
        return result;
    }

    @Override
    public HmclInstallHandle install(HmclInstallRequest request, HmclLaunchEventSink events) throws Exception {
        if (Thread.currentThread().isInterrupted()) {
            throw new InstallCancelledException();
        }
        if (!installActive.compareAndSet(false, true)) {
            throw new IllegalStateException("another install or repair is already in progress");
        }
        try {
            runInstall(request, events);
            return () -> {
            };
        } finally {
            installActive.set(false);
            activeInstallExecutor.set(null);
        }
    }

    private void runInstall(HmclInstallRequest request, HmclLaunchEventSink events) throws Exception {
        GameInstanceID id = new GameInstanceID(request.instanceId());
        DefaultDependencyManager dependencyManager = new DefaultDependencyManager(
                repository,
                createDownloadProvider(),
                new DefaultCacheRepository(repositoryRoot));

        Task<?> task;
        synchronized (repositoryLock) {
            checkInstallCancelled();
            repository.refresh();
            boolean exists = repository.hasInstance(id);
            if (request.isNewInstall()) {
                if (exists) {
                    throw new IllegalStateException("instance already exists: " + request.instanceId());
                }
                events.log("installing " + request.gameVersion()
                        + " as instance " + request.instanceId() + " ...");
                GameBuilder builder = dependencyManager.newGameBuilder(id);
                try {
                    builder.component(GameComponentType.GAME, request.gameVersion());
                    task = builder.buildAsync();
                } finally {
                    // No-op once buildAsync has transferred the draft, but it
                    // releases the exclusive draft if anything failed early.
                    builder.close();
                }
            } else {
                if (!exists) {
                    throw new IOException("no such instance: " + request.instanceId());
                }
                DefaultGameInstance instance = repository.getInstance(id);
                events.log("repairing instance " + request.instanceId() + " ...");
                task = dependencyManager.checkGameCompletionAsync(instance, instance.getResolvedManifest(), false);
            }
        }

        checkInstallCancelled();
        // Tasks composed with whenComplete rely on executor-maintained state;
        // running them via Task.run() trips HMCL's own state assertions. The
        // executor also enables cancelling a download in flight.
        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean success = new AtomicBoolean();
        TaskExecutor executor = task.executor(new TaskListener() {
            @Override
            public void onStop(boolean taskSuccess, TaskExecutor finishedExecutor) {
                success.set(taskSuccess);
                done.countDown();
            }
        });
        activeInstallExecutor.set(executor);
        try {
            executor.start();
            while (true) {
                try {
                    done.await();
                    break;
                } catch (InterruptedException e) {
                    // A stop request interrupts this thread; translate that
                    // into an executor cancellation so downloads abort too.
                    try {
                        executor.cancel();
                    } catch (Exception ignored) {
                        // Already stopped.
                    }
                }
            }
        } finally {
            activeInstallExecutor.compareAndSet(executor, null);
        }

        if (!success.get()) {
            Exception failure = executor.getException();
            if (executor.isCancelled() || failure == null) {
                throw new InstallCancelledException();
            }
            throw failure;
        }
        events.log(request.isNewInstall() ? "install completed" : "repair completed");
        events.exit(0);
    }

    private DownloadProvider createDownloadProvider() {
        if ("bmclapi".equals(downloadProvider)) {
            return new BMCLAPIDownloadProvider("https://bmclapi2.bangbang93.com");
        }
        if (!"mojang".equals(downloadProvider)
                && (downloadProvider.startsWith("http://") || downloadProvider.startsWith("https://"))) {
            // A custom BMCLAPI-compatible mirror root; also used by tests to
            // point the installer at a local fixture server.
            return new BMCLAPIDownloadProvider(downloadProvider);
        }
        return new MojangDownloadProvider();
    }

    @Override
    public HmclLaunchHandle launch(HmclLaunchRequest request, HmclLaunchEventSink events) throws Exception {
        if (Thread.currentThread().isInterrupted()) {
            throw new LaunchCancelledException();
        }
        if (installActive.get()) {
            throw new IllegalStateException("an install or repair is in progress; launch again when it finishes");
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
            JavaRuntime java = resolveJavaRuntime(request);

            LaunchOptions.Builder optionsBuilder = new LaunchOptions.Builder()
                    .setInstanceId(id)
                    .setGameDir(instance.getRunDirectory())
                    .setJava(java)
                    .setVersionName(id.toString())
                    .setProfileName("MCMCL")
                    .setWidth(854)
                    .setHeight(480)
                    .setDaemon(false);
            if (request.maxMemory() != null) {
                optionsBuilder.setMaxMemory(request.maxMemory());
            }
            LaunchOptions options = optionsBuilder.create();
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

    private JavaRuntime resolveJavaRuntime(HmclLaunchRequest request) throws IOException {
        String javaPath = request.javaPath();
        if (javaPath == null || javaPath.isBlank()) {
            JavaRuntime current = JavaRuntime.getDefault();
            if (current == null) {
                throw new IOException("HMCL Core could not detect the helper JVM as a Java runtime");
            }
            return current;
        }
        Path binary = Path.of(javaPath).toAbsolutePath().normalize();
        if (!Files.isRegularFile(binary)) {
            throw new IOException("Java executable does not exist: " + binary);
        }
        return JavaRuntime.of(binary, detectJavaInfo(binary), false);
    }

    private static JavaInfo detectJavaInfo(Path binary) throws IOException {
        Process process = new ProcessBuilder(binary.toString(), "-version")
                .redirectErrorStream(false)
                .start();
        String output;
        try {
            process.waitFor(15, TimeUnit.SECONDS);
            output = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("interrupted while detecting the Java version of " + binary, e);
        } finally {
            process.destroyForcibly();
        }
        Matcher matcher = JAVA_VERSION_OUTPUT.matcher(output);
        if (!matcher.find()) {
            throw new IOException("could not determine the Java version of " + binary
                    + "; -version output: " + output.strip());
        }
        return new JavaInfo(Platform.CURRENT_PLATFORM, matcher.group(1), null);
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

    private static void checkInstallCancelled() throws InstallCancelledException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InstallCancelledException();
        }
    }

    private static final class LaunchCancelledException extends IOException {
        private LaunchCancelledException() {
            super("launch cancelled");
        }
    }

    private static final class InstallCancelledException extends IOException {
        private InstallCancelledException() {
            super("install cancelled");
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
