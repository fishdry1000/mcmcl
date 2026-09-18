package top.fish1000.mcmcl.helper;

import top.fish1000.mcmcl.helper.hmcl.HmclCoreAdapter;
import top.fish1000.mcmcl.helper.hmcl.HmclInstallHandle;
import top.fish1000.mcmcl.helper.hmcl.HmclInstallRequest;
import top.fish1000.mcmcl.helper.hmcl.HmclLaunchEventSink;
import top.fish1000.mcmcl.helper.hmcl.HmclLaunchHandle;
import top.fish1000.mcmcl.helper.hmcl.HmclLaunchRequest;
import top.fish1000.mcmcl.helper.hmcl.RemoteVersionDescriptor;
import top.fish1000.mcmcl.helper.protocol.Json;
import top.fish1000.mcmcl.helper.protocol.JsonLineWriter;
import top.fish1000.mcmcl.helper.repository.InstanceDescriptor;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedReader;
import java.io.PipedWriter;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/** Dependency-free executable tests; run through Gradle's protocolTest task. */
public final class ProtocolTest {
    private ProtocolTest() {
    }

    public static void main(String[] args) throws Exception {
        jsonRoundTripEscapesStrings();
        jsonLineWriterAlwaysUsesUtf8();
        repositoryCatalogFindsConventionalAndFallbackManifests();
        defaultFactoryUsesUnavailableProviderWithoutProfile();
        serverListsAndRejectsMalformedRequests();
        serverBridgesLaunchLifecycleAndStop();
        serverParsesLaunchOptionsAndInstallLifecycle();
        serverCancelsRunningInstall();
        javaFxBootstrapProvisionsAndVerifies();
        mainParsesJavaFxArguments();
        int testCount = 10;
        if (Boolean.getBoolean("mcmcl.hmcl.profile")) {
            realHmclCoreLaunchesFixture();
            realHmclCoreInstallsRepairsAndLaunchesFixtureFromLocalServer();
            realHelperKeepsStdoutProtocolOnly();
            testCount += 3;
        }
        System.out.println("ProtocolTest: " + testCount + " tests passed");
        if (Boolean.getBoolean("mcmcl.hmcl.profile")) {
            // The HMCL profile starts the JavaFX toolkit, whose application
            // thread is not a daemon; exit explicitly once tests are done.
            System.exit(0);
        }
    }

    private static void jsonRoundTripEscapesStrings() throws Exception {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("line", "中文\nquote=\" slash=\\");
        value.put("items", Arrays.asList(true, null, 12));
        Object parsed = Json.parse(Json.stringify(value));
        check(parsed instanceof Map<?, ?>, "round trip should produce an object");
        check("中文\nquote=\" slash=\\".equals(((Map<?, ?>) parsed).get("line")),
                "escaped string did not round-trip");
    }

    private static void jsonLineWriterAlwaysUsesUtf8() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        // Simulate a Chinese Windows JVM whose original System.out was
        // constructed with a non-UTF-8 platform charset.
        try (PrintStream platformOutput = new PrintStream(
                bytes, true, StandardCharsets.ISO_8859_1)) {
            new JsonLineWriter(platformOutput).write(Map.of(
                    "instanceId", "中文实例",
                    "line", "中文日志"));
        }

        Map<String, Object> message = cast(Json.parse(bytes.toString(StandardCharsets.UTF_8).strip()));
        check("中文实例".equals(message.get("instanceId")),
                "instance name was not encoded as UTF-8");
        check("中文日志".equals(message.get("line")),
                "log line was not encoded as UTF-8");
    }

    private static void repositoryCatalogFindsConventionalAndFallbackManifests() throws Exception {
        Path repository = Files.createTempDirectory("mcmcl-helper-catalog-");
        try {
            Path conventional = Files.createDirectories(repository.resolve("versions/1.26.3"));
            Files.writeString(conventional.resolve("1.26.3.json"), "{}", StandardCharsets.UTF_8);
            Path modded = Files.createDirectories(repository.resolve("versions/fabric-demo"));
            Files.writeString(modded.resolve("fabric-demo.json"), """
                    {"libraries":[{"name":"net.fabricmc:fabric-loader:0.16.9"}]}
                    """, StandardCharsets.UTF_8);
            Path patched = Files.createDirectories(repository.resolve("versions/patched-demo"));
            Files.writeString(patched.resolve("patched-demo.json"), """
                    {"patches":[{"id":"game"},{"id":"neoforge","inheritsFrom":"1.26.3"}]}
                    """, StandardCharsets.UTF_8);
            Path fallback = Files.createDirectories(repository.resolve("versions/custom"));
            Files.writeString(fallback.resolve("profile.json"), "{}", StandardCharsets.UTF_8);
            Files.createDirectories(repository.resolve("versions/without-manifest"));

            List<InstanceDescriptor> instances = new top.fish1000.mcmcl.helper.repository.RepositoryInstanceCatalog(
                    repository).list();
            check(instances.size() == 4, "catalog should ignore directories without a manifest");
            check(instances.get(0).instanceId().equals("1.26.3"), "catalog should sort instance ids");
            check(instances.get(0).loader().isEmpty(), "vanilla manifest should report no loader");
            check(instances.get(1).manifest().getFileName().toString().equals("profile.json"),
                    "catalog should support HMCL's single-json fallback");
            check(instances.get(2).instanceId().equals("fabric-demo"), "catalog should sort instance ids");
            check(instances.get(2).loader().equals("fabric"), "loader libraries should be detected");
            check(instances.get(3).instanceId().equals("patched-demo"), "catalog should sort instance ids");
            check(instances.get(3).loader().equals("neoforge"), "HMCL patches should be detected as the loader");
        } finally {
            deleteTree(repository);
        }
    }

    private static void serverListsAndRejectsMalformedRequests() throws Exception {
        RecordingAdapter adapter = new RecordingAdapter();
        String input = "{\"id\":\"list-1\",\"command\":\"list\"}\n"
                + "{\"id\":\"hello-1\",\"command\":\"hello\"}\n"
                + "not-json\n"
                + "{\"id\":\"bad-command\",\"command\":\"wat\"}\n"
                + "{\"id\":\"stop-1\",\"command\":\"stop\",\"instanceId\":\"none\"}\n"
                + "{\"id\":\"shutdown-1\",\"command\":\"shutdown\"}\n";
        List<Map<String, Object>> responses = run(adapter, input);
        check(hasResponse(responses, "list-1", true), "list response missing");
        Map<String, Object> hello = findResponse(responses, "hello-1");
        check(hello != null, "hello response missing");
        check(hello.get("protocolVersion") instanceof Number protocolVersion
                && protocolVersion.intValue() == HelperBuildInfo.PROTOCOL_VERSION,
                "hello response has the wrong protocol version");
        check("hmcl-core".equals(hello.get("backend")), "hello response has the wrong backend");
        check(Boolean.TRUE.equals(hello.get("launchAvailable")),
                "hello response should report launch availability");
        check(hasResponse(responses, null, false), "malformed line should get a null-id error");
        check(hasResponse(responses, "bad-command", false), "unknown command should fail");
        check(hasResponse(responses, "stop-1", false), "unknown stop should fail");
        check(hasResponse(responses, "shutdown-1", true), "shutdown response missing");
    }

    private static void defaultFactoryUsesUnavailableProviderWithoutProfile() throws Exception {
        Path repository = Files.createTempDirectory("mcmcl-helper-factory-");
        try {
            var catalog = new top.fish1000.mcmcl.helper.repository.RepositoryInstanceCatalog(repository);
            String adapterName = HmclCoreAdapterFactory
                    .create(repository, catalog, "mojang")
                    .getClass().getSimpleName();
            if (Boolean.getBoolean("mcmcl.hmcl.profile")) {
                check(adapterName.equals("RealHmclCoreAdapter"),
                        "HMCL profile must load the real adapter");
            } else {
                check(adapterName.equals("UnavailableHmclCoreAdapter"),
                        "default build must not load the profile-only HMCL provider");
            }
        } finally {
            deleteTree(repository);
        }
    }

    private static void serverBridgesLaunchLifecycleAndStop() throws Exception {
        RecordingAdapter adapter = new RecordingAdapter();
        String instanceId = "test-instance";
        String launch = "{\"id\":\"launch-1\",\"command\":\"launch\","
                + "\"instanceId\":\"" + instanceId + "\","
                + "\"username\":\"Player\","
                + "\"uuid\":\"" + UUID.randomUUID() + "\","
                + "\"accessToken\":\"secret-token\","
                + "\"userType\":\"msa\","
                + "\"xuid\":\"123\",\"clientId\":\"client\"}";
        List<Map<String, Object>> messages = runLaunchInteraction(adapter, instanceId, launch);
        check(hasResponse(messages, "launch-1", true), "launch should be accepted");
        check(hasEvent(messages, instanceId, "started"), "started event missing");
        check(hasEvent(messages, instanceId, "log"), "log event missing");
        check(hasEvent(messages, instanceId, "exit"), "exit event missing");
        check(adapter.stopped, "stop should be forwarded to the adapter");
        check(!Json.stringify(messages).contains("secret-token"), "access token leaked to protocol output");
    }

    private static void serverParsesLaunchOptionsAndInstallLifecycle() throws Exception {
        // Launch options are forwarded to the adapter; runLaunchInteraction
        // waits for the adapter to actually observe the launch.
        RecordingAdapter launchAdapter = new InstallRecordingAdapter(false, null, null);
        String launch = "{\"id\":\"launch-1\",\"command\":\"launch\","
                + "\"instanceId\":\"demo\",\"username\":\"Player\","
                + "\"uuid\":\"" + UUID.randomUUID() + "\",\"accessToken\":\"secret-token\","
                + "\"userType\":\"msa\",\"javaPath\":\"C:\\\\java\\\\bin\\\\java.exe\","
                + "\"maxMemory\":4096,\"versionIsolation\":true}";
        List<Map<String, Object>> messages = runLaunchInteraction(launchAdapter, "demo", launch);
        check(hasResponse(messages, "launch-1", true), "launch with options should be accepted");
        check(launchAdapter.lastRequest != null
                && "C:\\java\\bin\\java.exe".equals(launchAdapter.lastRequest.javaPath()),
                "javaPath should be forwarded to the adapter");
        check(launchAdapter.lastRequest != null
                && Integer.valueOf(4096).equals(launchAdapter.lastRequest.maxMemory()),
                "maxMemory should be forwarded to the adapter");
        check(launchAdapter.lastRequest != null && launchAdapter.lastRequest.versionIsolation(),
                "versionIsolation should be forwarded to the adapter");

        messages = run(new RecordingAdapter(),
                "{\"id\":\"bad-isolation\",\"command\":\"launch\","
                        + "\"instanceId\":\"demo\",\"username\":\"Player\","
                        + "\"uuid\":\"" + UUID.randomUUID() + "\",\"accessToken\":\"token\","
                        + "\"userType\":\"msa\",\"versionIsolation\":\"yes\"}\n");
        check(hasResponseWithCode(messages, "bad-isolation", "INVALID_REQUEST"),
                "non-boolean versionIsolation should be rejected");

        InstallRecordingAdapter installer = new InstallRecordingAdapter(true, null, null);
        messages = run(installer,
                "{\"id\":\"rv-1\",\"command\":\"remoteVersions\"}\n"
                        + "{\"id\":\"rv-2\",\"command\":\"remoteVersions\",\"component\":\"fabric\",\"gameVersion\":\"1.0\"}\n"
                        + "{\"id\":\"bad-rv\",\"command\":\"remoteVersions\",\"component\":\"fabric\"}\n"
                        + "{\"id\":\"bad-install\",\"command\":\"install\",\"instanceId\":\"demo\"}\n"
                        + "{\"id\":\"install-1\",\"command\":\"install\",\"instanceId\":\"demo\",\"gameVersion\":\"1.0\"}\n"
                        + "{\"id\":\"bad-loaders\",\"command\":\"install\",\"instanceId\":\"x\","
                        + "\"gameVersion\":\"1.0\",\"loaders\":[{\"type\":\"fabric\"}]}\n"
                        + "{\"id\":\"bad-loaders-2\",\"command\":\"install\",\"instanceId\":\"x\","
                        + "\"gameVersion\":\"1.0\",\"loaders\":\"nope\"}\n"
                        + "{\"id\":\"repair-1\",\"command\":\"repair\",\"instanceId\":\"other\"}\n"
                        + "{\"id\":\"stop-missing\",\"command\":\"stop\",\"instanceId\":\"none\"}\n");
        Map<String, Object> remoteVersions = findResponse(messages, "rv-1");
        check(remoteVersions != null, "remoteVersions response missing");
        check(remoteVersions.get("versions") instanceof List<?> versions && versions.size() == 1,
                "remoteVersions should carry the adapter version list");
        check("fabric".equals(installer.lastComponent) && "1.0".equals(installer.lastGameVersion),
                "component version list request should reach the adapter with its arguments");
        check(hasResponseWithCode(messages, "bad-rv", "INVALID_REQUEST"),
                "component list without gameVersion should be rejected");
        check(hasResponseWithCode(messages, "bad-install", "INVALID_REQUEST"),
                "install without gameVersion should be rejected");
        check(hasResponse(messages, "install-1", true), "install should be accepted");
        check(hasResponseWithCode(messages, "bad-loaders", "INVALID_REQUEST"),
                "loader without version should be rejected");
        check(hasResponseWithCode(messages, "bad-loaders-2", "INVALID_REQUEST"),
                "non-array loaders should be rejected");
        check(hasResponse(messages, "repair-1", true), "repair should be accepted");
        check(hasResponseWithCode(messages, "stop-missing", "NOT_RUNNING"),
                "stop for an idle instance should report NOT_RUNNING");

        // Install execution is asynchronous; drive it through an interactive
        // session so the tasks are guaranteed to reach the adapter.
        CountDownLatch installsEntered = new CountDownLatch(2);
        InstallRecordingAdapter installer2 = new InstallRecordingAdapter(true, installsEntered, null);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream stream = new PrintStream(bytes, true, StandardCharsets.UTF_8);
                PipedWriter inputWriter = new PipedWriter()) {
            PipedReader inputReader = new PipedReader(inputWriter);
            HelperServer server = new HelperServer(installer2, new JsonLineWriter(stream));
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread serverThread = new Thread(() -> {
                try {
                    server.run(new BufferedReader(inputReader));
                } catch (Throwable throwable) {
                    failure.set(throwable);
                }
            }, "mcmcl-helper-install-test");
            serverThread.start();

            inputWriter.write("{\"id\":\"install-1\",\"command\":\"install\","
                    + "\"instanceId\":\"demo\",\"gameVersion\":\"1.0\"}\n");
            inputWriter.write("{\"id\":\"install-loaders\",\"command\":\"install\","
                    + "\"instanceId\":\"loader-demo\",\"gameVersion\":\"1.0\","
                    + "\"versionIsolation\":true,"
                    + "\"loaders\":[{\"type\":\"fabric\",\"version\":\"0.16.9\"}]}\n");
            inputWriter.flush();
            check(installsEntered.await(5, TimeUnit.SECONDS), "installs did not reach the adapter in time");

            inputWriter.write("{\"id\":\"shutdown-1\",\"command\":\"shutdown\"}\n");
            inputWriter.flush();
            inputWriter.close();
            serverThread.join(10000);
            check(!serverThread.isAlive(), "test helper server did not terminate");
            if (failure.get() != null) {
                throw new AssertionError("test helper server failed", failure.get());
            }
        }

        messages = new ArrayList<>();
        for (String line : bytes.toString(StandardCharsets.UTF_8).split("\\R")) {
            if (!line.isBlank()) {
                messages.add(cast(Json.parse(line)));
            }
        }
        check(hasEvent(messages, "demo", "log"), "install progress event missing");
        check(hasEventWithCode(messages, "demo", "exit", 0), "install exit event missing");
        check(hasResponse(messages, "install-loaders", true), "install with loaders should be accepted");
        check(installer2.lastInstallRequest != null
                && installer2.lastInstallRequest.loaders().size() == 1
                && "fabric".equals(installer2.lastInstallRequest.loaders().get(0).type())
                && "0.16.9".equals(installer2.lastInstallRequest.loaders().get(0).version())
                && installer2.lastInstallRequest.versionIsolation(),
                "loader components should reach the adapter");
    }

    private static void serverCancelsRunningInstall() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        InstallRecordingAdapter adapter = new InstallRecordingAdapter(true, entered, release);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream stream = new PrintStream(bytes, true, StandardCharsets.UTF_8);
                PipedWriter inputWriter = new PipedWriter()) {
            PipedReader inputReader = new PipedReader(inputWriter);
            HelperServer server = new HelperServer(adapter, new JsonLineWriter(stream));
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread serverThread = new Thread(() -> {
                try {
                    server.run(new BufferedReader(inputReader));
                } catch (Throwable throwable) {
                    failure.set(throwable);
                }
            }, "mcmcl-helper-cancel-test");
            serverThread.start();

            inputWriter.write("{\"id\":\"install-1\",\"command\":\"install\","
                    + "\"instanceId\":\"demo\",\"gameVersion\":\"1.0\"}\n");
            inputWriter.flush();
            check(entered.await(5, TimeUnit.SECONDS), "test adapter did not enter install in time");

            inputWriter.write("{\"id\":\"install-2\",\"command\":\"install\","
                    + "\"instanceId\":\"demo\",\"gameVersion\":\"2.0\"}\n");
            inputWriter.flush();
            inputWriter.write("{\"id\":\"stop-1\",\"command\":\"stop\",\"instanceId\":\"demo\"}\n");
            inputWriter.flush();
            inputWriter.write("{\"id\":\"shutdown-1\",\"command\":\"shutdown\"}\n");
            inputWriter.flush();
            inputWriter.close();
            serverThread.join(10000);
            check(!serverThread.isAlive(), "test helper server did not terminate");
            if (failure.get() != null) {
                throw new AssertionError("test helper server failed", failure.get());
            }
        } finally {
            release.countDown();
        }

        List<Map<String, Object>> messages = new ArrayList<>();
        for (String line : bytes.toString(StandardCharsets.UTF_8).split("\\R")) {
            if (!line.isBlank()) {
                messages.add(cast(Json.parse(line)));
            }
        }
        check(hasResponse(messages, "install-1", true), "first install should be accepted");
        check(hasResponseWithCode(messages, "install-2", "ALREADY_RUNNING"),
                "second concurrent install should report ALREADY_RUNNING");
        check(hasResponse(messages, "stop-1", true), "stop for a running install should be accepted");
        check(hasEventWithCode(messages, "demo", "error", "CANCELLED"),
                "cancelled install should emit a CANCELLED error event");
        check(hasResponse(messages, "shutdown-1", true), "shutdown response missing");
    }

    private static List<Map<String, Object>> runLaunchInteraction(
            RecordingAdapter adapter,
            String instanceId,
            String launch) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream stream = new PrintStream(bytes, true, StandardCharsets.UTF_8);
                PipedWriter inputWriter = new PipedWriter()) {
            PipedReader inputReader = new PipedReader(inputWriter);
            HelperServer server = new HelperServer(adapter, new JsonLineWriter(stream));
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread serverThread = new Thread(() -> {
                try {
                    server.run(new BufferedReader(inputReader));
                } catch (Throwable throwable) {
                    failure.set(throwable);
                }
            }, "mcmcl-helper-test-server");
            serverThread.start();

            inputWriter.write(launch);
            inputWriter.write('\n');
            inputWriter.flush();
            check(adapter.startedLatch.await(5, TimeUnit.SECONDS), "test adapter did not start in time");

            inputWriter.write("{\"id\":\"stop-1\",\"command\":\"stop\",\"instanceId\":\""
                    + instanceId + "\"}\n");
            inputWriter.flush();
            check(adapter.stoppedLatch.await(5, TimeUnit.SECONDS), "test adapter did not stop in time");

            inputWriter.write("{\"id\":\"shutdown-1\",\"command\":\"shutdown\"}\n");
            inputWriter.flush();
            inputWriter.close();
            serverThread.join(5000);
            check(!serverThread.isAlive(), "test helper server did not terminate");
            if (failure.get() != null) {
                throw new AssertionError("test helper server failed", failure.get());
            }
        }

        List<Map<String, Object>> messages = new ArrayList<>();
        for (String line : bytes.toString(StandardCharsets.UTF_8).split("\\R")) {
            if (!line.isBlank()) {
                messages.add(cast(Json.parse(line)));
            }
        }
        return messages;
    }

    /** Exercises the JavaFX bootstrap against a fake Maven repository. */
    private static void javaFxBootstrapProvisionsAndVerifies() throws Exception {
        byte[] jarBytes = "fake javafx jar bytes".getBytes(StandardCharsets.UTF_8);
        String checksum = sha1(jarBytes);
        var server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (!path.contains("/org/openjfx/javafx-")) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            byte[] body = path.endsWith(".sha1")
                    ? (checksum + "\n").getBytes(StandardCharsets.UTF_8)
                    : jarBytes;
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        Path directory = Files.createTempDirectory("mcmcl-helper-javafx-");
        try {
            String classpath = JavaFxBootstrap.ensureModules(
                    directory, "25", "win", "http://127.0.0.1:" + server.getAddress().getPort());
            check(classpath.split(java.io.File.pathSeparator).length == 3,
                    "bootstrap classpath should contain three modules");
            for (String module : new String[] { "base", "graphics", "controls" }) {
                check(Files.isRegularFile(directory.resolve("javafx-" + module + ".jar")),
                        "javafx-" + module + " jar was not provisioned");
            }

            // A second call must reuse the cached jars without network.
            server.stop(0);
            String cached = JavaFxBootstrap.ensureModules(directory, "25", "win", "http://127.0.0.1:1");
            check(cached.equals(classpath), "cached bootstrap should return the same classpath");
        } finally {
            server.stop(0);
            deleteTree(directory);
        }

        // A corrupted download must be rejected instead of cached.
        var badServer = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        badServer.createContext("/", exchange -> {
            byte[] body = exchange.getRequestURI().getPath().endsWith(".sha1")
                    ? "0123456789012345678901234567890123456789".getBytes(StandardCharsets.UTF_8)
                    : jarBytes;
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        badServer.start();
        Path badDirectory = Files.createTempDirectory("mcmcl-helper-javafx-bad-");
        try {
            JavaFxBootstrap.ensureModules(badDirectory, "25", "win",
                    "http://127.0.0.1:" + badServer.getAddress().getPort());
            check(false, "checksum mismatch should fail the bootstrap");
        } catch (IOException expected) {
            // checksum mismatch surfaces as IOException
        } finally {
            badServer.stop(0);
            deleteTree(badDirectory);
        }
    }

    private static void mainParsesJavaFxArguments() throws Exception {
        Path repository = Files.createTempDirectory("mcmcl-helper-args-");
        try {
            Main.HelperArgs args = Main.parseHelperArgs(new String[] {
                    "--repository", repository.toString(),
                    "--download-provider", "bmclapi",
                    "--javafx-version", "21",
                    "--javafx-dir", "fx",
                    "--javafx-repo", "http://mirror/maven" });
            check("bmclapi".equals(args.downloadProvider()), "download provider should parse");
            check("21".equals(args.javafxVersion()), "javafx version should parse");
            check(args.javafxDirectory().endsWith("fx"), "javafx dir should parse");
            check("http://mirror/maven".equals(args.javafxRepository()), "javafx repo should parse");

            Main.HelperArgs defaults = Main.parseHelperArgs(new String[] { "--repository", repository.toString() });
            check("mojang".equals(defaults.downloadProvider()), "download provider default");
            check(JavaFxBootstrap.DEFAULT_VERSION.equals(defaults.javafxVersion()), "javafx version default");
            check(defaults.javafxDirectory().equals(Main.defaultJavaFxDirectory()),
                    "javafx dir should default beside the helper");
            check(!defaults.javafxDirectory().startsWith(repository),
                    "javafx dir must not follow the Minecraft repository");
            check(JavaFxBootstrap.DEFAULT_REPOSITORY.equals(defaults.javafxRepository()),
                    "javafx repo default");
        } finally {
            deleteTree(repository);
        }

        try {
            Main.parseHelperArgs(new String[] { "--repository", ".", "--download-provider", "nope" });
            check(false, "invalid download provider should be rejected");
        } catch (IllegalArgumentException expected) {
            // argument validation
        }
    }

    private static void realHmclCoreLaunchesFixture() throws Exception {
        Path repository = Files.createTempDirectory("mcmcl-helper-launch-");
        try {
            Path instanceRoot = Files.createDirectories(repository.resolve("versions/demo"));
            Files.writeString(instanceRoot.resolve("demo.json"), """
                    {
                      "id": "demo",
                      "type": "release",
                      "mainClass": "top.fish1000.mcmcl.helper.LaunchFixtureMain",
                      "assetIndex": {"id": "fixture"},
                      "libraries": []
                    }
                    """, StandardCharsets.UTF_8);
            createFixtureJar(instanceRoot.resolve("demo.jar"));
            Path assetIndexes = Files.createDirectories(repository.resolve("assets/indexes"));
            Files.writeString(assetIndexes.resolve("fixture.json"), "{\"objects\":{}}", StandardCharsets.UTF_8);

            HmclCoreAdapter adapter = HmclCoreAdapterFactory.create(
                    repository,
                    new top.fish1000.mcmcl.helper.repository.RepositoryInstanceCatalog(repository),
                    "mojang");
            AtomicBoolean started = new AtomicBoolean();
            List<String> logs = Collections.synchronizedList(new ArrayList<>());
            CompletableFuture<Integer> exit = new CompletableFuture<>();
            HmclLaunchEventSink events = new HmclLaunchEventSink() {
                @Override
                public void started() {
                    started.set(true);
                }

                @Override
                public void log(String line) {
                    logs.add(line);
                }

                @Override
                public void exit(int code) {
                    exit.complete(code);
                }

                @Override
                public void error(String message) {
                    exit.completeExceptionally(new AssertionError(message));
                }
            };

            HmclLaunchRequest request = new HmclLaunchRequest(
                    "demo", "Player", UUID.randomUUID(), "token", "msa", null, null, null, null, true);
            adapter.launch(request, events);
            check(started.get(), "real HMCL adapter did not report started");
            check(exit.get(10, TimeUnit.SECONDS) == 0, "fixture game did not exit successfully");
            check(logs.stream().anyMatch(line -> line.contains("fixture-stdout")),
                    "stdout was not bridged through HMCL Core: " + logs);
            check(logs.stream().anyMatch(line -> line.contains("fixture-stderr")),
                    "stderr was not bridged through HMCL Core: " + logs);
            check(logs.stream().anyMatch(line -> line.contains(
                    "fixture-cwd=" + instanceRoot.toAbsolutePath().normalize())),
                    "version isolation did not use the instance directory: " + logs);
            adapter.shutdown();
        } finally {
            deleteTree(repository);
        }
    }

    private static void realHelperKeepsStdoutProtocolOnly() throws Exception {
        Path repository = Files.createTempDirectory("mcmcl-helper-main-");
        Path instanceRoot = Files.createDirectories(repository.resolve("versions/demo"));
        Files.writeString(instanceRoot.resolve("demo.json"), """
                {
                  "id": "demo",
                  "type": "release",
                  "mainClass": "net.minecraft.client.main.Main"
                }
                """, StandardCharsets.UTF_8);

        PrintStream originalOut = System.out;
        var originalIn = System.in;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream protocolBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream diagnosticsBytes = new ByteArrayOutputStream();
        String embedded = System.getProperty("mcmcl.helper.embedded");
        System.setProperty("mcmcl.helper.embedded", "true");
        try (PrintStream protocol = new PrintStream(protocolBytes, true, StandardCharsets.UTF_8);
                PrintStream diagnostics = new PrintStream(diagnosticsBytes, true, StandardCharsets.UTF_8)) {
            System.setIn(new ByteArrayInputStream(("{\"id\":\"list\",\"command\":\"list\"}\n"
                    + "{\"id\":\"shutdown\",\"command\":\"shutdown\"}\n")
                    .getBytes(StandardCharsets.UTF_8)));
            System.setOut(protocol);
            System.setErr(diagnostics);
            Main.main(new String[] { "--repository", repository.toString() });
        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
            System.setErr(originalErr);
            if (embedded == null) {
                System.clearProperty("mcmcl.helper.embedded");
            } else {
                System.setProperty("mcmcl.helper.embedded", embedded);
            }
            deleteTree(repository);
        }

        List<String> lines = protocolBytes.toString(StandardCharsets.UTF_8).lines().toList();
        check(lines.size() == 2, "helper stdout should contain exactly two protocol responses");
        for (String line : lines) {
            Object message = Json.parse(line);
            check(message instanceof Map<?, ?>, "helper stdout contained a non-JSON-object line");
        }
    }

    /**
     * Installs a fake vanilla version from a local BMCLAPI-compatible mirror,
     * repairs a deleted client jar, and launches the installed instance.
     */
    private static void realHmclCoreInstallsRepairsAndLaunchesFixtureFromLocalServer() throws Exception {
        com.sun.net.httpserver.HttpServer fixtureServer = startFixtureServer();
        try {
            String mirror = "http://127.0.0.1:" + fixtureServer.getAddress().getPort();
            Path repository = Files.createTempDirectory("mcmcl-helper-install-");
            try {
                HmclCoreAdapter adapter = HmclCoreAdapterFactory.create(
                        repository,
                        new top.fish1000.mcmcl.helper.repository.RepositoryInstanceCatalog(repository),
                        mirror);

                List<Map<String, Object>> remote = new ArrayList<>();
                for (var version : adapter.listRemoteVersions(null, null)) {
                    remote.add(version.toJson());
                }
                check(remote.stream().anyMatch(version -> "fixture".equals(version.get("id"))),
                        "remote version list should contain the fixture version");

                List<Map<String, Object>> fabricVersions = new ArrayList<>();
                for (var version : adapter.listRemoteVersions("fabric", "fixture-vanilla")) {
                    fabricVersions.add(version.toJson());
                }
                check(fabricVersions.stream().anyMatch(version -> "0.16.9".equals(version.get("id"))),
                        "fabric version list should contain the fixture loader version: " + fabricVersions);

                RecordingSink installSink = new RecordingSink();
                adapter.install(new HmclInstallRequest("fixture", "fixture", List.of(), false), installSink);
                check(installSink.exit.get(120, TimeUnit.SECONDS) == 0,
                        "fixture install did not finish successfully: "
                                + installSink.error.get() + " logs: " + installSink.logs);
                check(Files.isRegularFile(repository.resolve("versions/fixture/fixture.json")),
                        "installed manifest is missing");
                Path installedJar = repository.resolve("versions/fixture/fixture.jar");
                check(Files.isRegularFile(installedJar), "installed client jar is missing");
                check(adapter.listInstances().stream().anyMatch(i -> i.instanceId().equals("fixture")),
                        "installed instance should appear in the instance list");

                Files.delete(installedJar);
                RecordingSink repairSink = new RecordingSink();
                adapter.install(new HmclInstallRequest("fixture", null, List.of(), false), repairSink);
                check(repairSink.exit.get(120, TimeUnit.SECONDS) == 0,
                        "fixture repair did not finish: " + repairSink.error.get());
                check(Files.isRegularFile(installedJar), "repair did not restore the client jar");

                // Installing a Fabric loader instance from the fake fabric-meta
                // endpoints, then launching it, exercises the loader chain.
                RecordingSink fabricSink = new RecordingSink();
                adapter.install(new HmclInstallRequest("fabric-demo", "fixture-vanilla",
                        List.of(new HmclInstallRequest.ComponentSpec("fabric", "0.16.9")), true),
                        fabricSink);
                check(fabricSink.exit.get(120, TimeUnit.SECONDS) == 0,
                        "fabric install did not finish: " + fabricSink.error.get() + " logs: " + fabricSink.logs);
                Path fabricManifest = repository.resolve("versions/fabric-demo/fabric-demo.json");
                check(Files.isRegularFile(fabricManifest), "fabric instance manifest is missing");
                String manifestText = Files.readString(fabricManifest);
                check(manifestText.contains("fabric") && manifestText.contains("0.16.9"),
                        "fabric manifest should record the loader component");

                RecordingSink fabricLaunchSink = new RecordingSink();
                HmclLaunchRequest fabricLaunch = new HmclLaunchRequest(
                        "fabric-demo", "Player", UUID.randomUUID(), "token", "msa",
                        null, null, null, null, true);
                adapter.launch(fabricLaunch, fabricLaunchSink);
                check(fabricLaunchSink.started.get(), "fabric instance did not report started");
                check(fabricLaunchSink.exit.get(30, TimeUnit.SECONDS) == 0,
                        "fabric instance did not exit successfully: " + fabricLaunchSink.error.get());
                check(fabricLaunchSink.logs.stream().anyMatch(line -> line.contains("fixture-stdout")),
                        "fabric instance stdout was not bridged: " + fabricLaunchSink.logs);
                check(fabricLaunchSink.logs.stream().anyMatch(line -> line.contains(
                        "fixture-cwd=" + repository.resolve("versions/fabric-demo").toAbsolutePath().normalize())),
                        "fabric version isolation did not use the instance directory: " + fabricLaunchSink.logs);
                adapter.shutdown();

                RecordingSink launchSink = new RecordingSink();
                HmclLaunchRequest request = new HmclLaunchRequest(
                        "fixture", "Player", UUID.randomUUID(), "token", "msa",
                        null, null, null, null, false);
                adapter.launch(request, launchSink);
                check(launchSink.started.get(), "installed instance did not report started");
                check(launchSink.exit.get(30, TimeUnit.SECONDS) == 0,
                        "installed instance did not exit successfully: " + launchSink.error.get());
                check(launchSink.logs.stream().anyMatch(line -> line.contains("fixture-stdout")),
                        "installed instance stdout was not bridged: " + launchSink.logs);
                adapter.shutdown();
            } finally {
                deleteTree(repository);
            }
        } finally {
            fixtureServer.stop(0);
        }
    }

    private static com.sun.net.httpserver.HttpServer startFixtureServer() throws Exception {
        byte[] clientJar = createFixtureJarBytes();
        byte[] assetIndex = "{\"objects\":{}}".getBytes(StandardCharsets.UTF_8);
        var server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        int port = server.getAddress().getPort();
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] body;
            if (path.endsWith("/mc/game/version_manifest.json")) {
                body = ("{\"latest\":{\"release\":\"fixture\"},\"versions\":["
                        + fixtureManifestEntry(port, "fixture")
                        + ","
                        + fixtureManifestEntry(port, "fixture-vanilla")
                        + "]}")
                        .getBytes(StandardCharsets.UTF_8);
            } else if (path.endsWith("/versions/fixture/fixture.json")) {
                body = fixtureVersionJson(port, clientJar, assetIndex, "top.fish1000.mcmcl.helper.LaunchFixtureMain");
            } else if (path.endsWith("/versions/fixture-vanilla/fixture-vanilla.json")) {
                body = fixtureVersionJson(port, clientJar, assetIndex, "net.minecraft.client.main.Main");
            } else if (path.endsWith("/assets/indexes/fixture.json")) {
                body = assetIndex;
            } else if (path.endsWith("/client.jar")) {
                body = clientJar;
            } else if (path.endsWith("/fabric-meta/v2/versions/game")) {
                body = "[{\"version\":\"fixture-vanilla\",\"maven\":\"https://maven.fabricmc.net\",\"stable\":true}]"
                        .getBytes(StandardCharsets.UTF_8);
            } else if (path.endsWith("/fabric-meta/v2/versions/loader")) {
                body = "[{\"version\":\"0.16.9\",\"maven\":\"https://maven.fabricmc.net\",\"stable\":true}]"
                        .getBytes(StandardCharsets.UTF_8);
            } else if (path.endsWith("/fabric-meta/v2/versions/loader/fixture-vanilla/0.16.9")) {
                body = fabricLaunchMeta();
            } else if (path.contains("/maven/net/fabricmc/")) {
                body = clientJar;
            } else {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        return server;
    }

    private static String fixtureManifestEntry(int port, String id) {
        return "{\"id\":\"" + id + "\",\"type\":\"release\","
                + "\"url\":\"http://127.0.0.1:" + port + "/versions/" + id + "/" + id + ".json\","
                + "\"time\":\"2026-01-01T00:00:00+00:00\","
                + "\"releaseTime\":\"2026-01-01T00:00:00+00:00\"}";
    }

    private static byte[] fabricLaunchMeta() {
        return ("{"
                + "\"loader\":{\"maven\":\"net.fabricmc:fabric-loader:0.16.9\",\"version\":\"0.16.9\","
                + "\"stable\":true,\"separator\":\".\",\"build\":1},"
                + "\"intermediary\":{\"maven\":\"net.fabricmc:intermediary:fixture-vanilla\","
                + "\"version\":\"fixture-vanilla\",\"stable\":true},"
                + "\"launcherMeta\":{"
                + "\"mainClass\":\"top.fish1000.mcmcl.helper.LaunchFixtureMain\","
                + "\"libraries\":{\"common\":[],\"server\":[]}"
                + "}}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] fixtureVersionJson(int port, byte[] clientJar, byte[] assetIndex, String mainClass) {
        return ("{"
                + "\"id\":\"fixture\","
                + "\"type\":\"release\","
                + "\"time\":\"2026-01-01T00:00:00+00:00\","
                + "\"releaseTime\":\"2026-01-01T00:00:00+00:00\","
                + "\"mainClass\":\"" + mainClass + "\","
                + "\"assets\":\"fixture\","
                + "\"assetIndex\":{\"id\":\"fixture\","
                + "\"url\":\"http://127.0.0.1:" + port + "/assets/indexes/fixture.json\","
                + "\"totalSize\":" + assetIndex.length + ",\"size\":" + assetIndex.length
                + ",\"sha1\":\"" + sha1(assetIndex) + "\"},"
                + "\"downloads\":{\"client\":{\"url\":\"http://127.0.0.1:" + port + "/client.jar\","
                + "\"size\":" + clientJar.length + ",\"sha1\":\"" + sha1(clientJar) + "\"}},"
                + "\"libraries\":[],"
                + "\"minecraftArguments\":\"--fixture\","
                + "\"minimumLauncherVersion\":21}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String sha1(byte[] data) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-1");
            StringBuilder result = new StringBuilder();
            for (byte value : digest.digest(data)) {
                result.append(String.format("%02x", value));
            }
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] createFixtureJarBytes() throws Exception {
        String className = "top/fish1000/mcmcl/helper/LaunchFixtureMain.class";
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream input = LaunchFixtureMain.class.getResourceAsStream("/" + className);
                JarOutputStream output = new JarOutputStream(bytes)) {
            check(input != null, "fixture class is not available on the test classpath");
            output.putNextEntry(new JarEntry(className));
            input.transferTo(output);
            output.closeEntry();
            output.putNextEntry(new JarEntry("version.json"));
            output.write("{\"id\":\"fixture\"}".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static void createFixtureJar(Path jar) throws Exception {
        Files.write(jar, createFixtureJarBytes());
    }

    private static boolean hasResponseWithCode(List<Map<String, Object>> messages, Object id, String code) {
        return messages.stream().anyMatch(message -> "response".equals(message.get("type"))
                && java.util.Objects.equals(id, message.get("id"))
                && code.equals(message.get("code")));
    }

    private static boolean hasEventWithCode(List<Map<String, Object>> messages, String instanceId, String event,
            Object code) {
        return messages.stream().anyMatch(message -> "event".equals(message.get("type"))
                && instanceId.equals(message.get("instanceId"))
                && event.equals(message.get("event"))
                && codeMatches(code, message.get("code")));
    }

    private static boolean codeMatches(Object expected, Object actual) {
        if (expected instanceof Number expectedNumber && actual instanceof Number actualNumber) {
            return expectedNumber.intValue() == actualNumber.intValue();
        }
        return java.util.Objects.equals(expected, actual);
    }

    private static final class RecordingSink implements HmclLaunchEventSink {
        final AtomicBoolean started = new AtomicBoolean();
        final List<String> logs = Collections.synchronizedList(new ArrayList<>());
        final CompletableFuture<Integer> exit = new CompletableFuture<>();
        final AtomicReference<String> error = new AtomicReference<>();

        @Override
        public void started() {
            started.set(true);
        }

        @Override
        public void log(String line) {
            logs.add(line);
        }

        @Override
        public void exit(int code) {
            exit.complete(code);
        }

        @Override
        public void error(String message) {
            error.set(message);
            exit.completeExceptionally(new AssertionError(message));
        }
    }

    /** Adapter that can emulate a blocking install for concurrency tests. */
    private static final class InstallRecordingAdapter extends RecordingAdapter {
        private final boolean installAvailable;
        private final CountDownLatch enteredLatch;
        private final CountDownLatch releaseLatch;
        private volatile HmclInstallRequest lastInstallRequest;

        private InstallRecordingAdapter(
                boolean installAvailable,
                CountDownLatch enteredLatch,
                CountDownLatch releaseLatch) {
            this.installAvailable = installAvailable;
            this.enteredLatch = enteredLatch;
            this.releaseLatch = releaseLatch;
        }

        @Override
        public boolean isInstallAvailable() {
            return installAvailable;
        }

        @Override
        public HmclInstallHandle install(HmclInstallRequest request, HmclLaunchEventSink events) throws Exception {
            lastInstallRequest = request;
            if (enteredLatch != null) {
                enteredLatch.countDown();
            }
            if (releaseLatch != null) {
                releaseLatch.await();
            }
            events.log("installing " + (request.isNewInstall() ? request.gameVersion() : "repair"));
            events.exit(0);
            return () -> {
            };
        }
    }

    private static List<Map<String, Object>> run(HmclCoreAdapter adapter, String input) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream stream = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        HelperServer server = new HelperServer(adapter, new JsonLineWriter(stream));
        server.run(new BufferedReader(new StringReader(input)));
        stream.flush();
        List<Map<String, Object>> messages = new ArrayList<>();
        for (String line : bytes.toString(StandardCharsets.UTF_8).split("\\R")) {
            if (!line.isBlank()) {
                messages.add(cast(Json.parse(line)));
            }
        }
        return messages;
    }

    private static boolean hasResponse(List<Map<String, Object>> messages, Object id, boolean ok) {
        return messages.stream().anyMatch(message -> "response".equals(message.get("type"))
                && java.util.Objects.equals(id, message.get("id"))
                && Boolean.valueOf(ok).equals(message.get("ok")));
    }

    private static Map<String, Object> findResponse(List<Map<String, Object>> messages, Object id) {
        return messages.stream()
                .filter(message -> "response".equals(message.get("type"))
                        && java.util.Objects.equals(id, message.get("id")))
                .findFirst()
                .orElse(null);
    }

    private static boolean hasEvent(List<Map<String, Object>> messages, String instanceId, String event) {
        return messages.stream().anyMatch(message -> "event".equals(message.get("type"))
                && instanceId.equals(message.get("instanceId"))
                && event.equals(message.get("event")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object value) {
        return (Map<String, Object>) value;
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void deleteTree(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    private static class RecordingAdapter implements HmclCoreAdapter {
        private volatile boolean stopped;
        private volatile HmclLaunchEventSink events;
        private volatile HmclLaunchRequest lastRequest;
        volatile String lastComponent;
        volatile String lastGameVersion;
        private final AtomicBoolean exited = new AtomicBoolean();
        private final CountDownLatch startedLatch = new CountDownLatch(1);
        private final CountDownLatch stoppedLatch = new CountDownLatch(1);

        @Override
        public List<InstanceDescriptor> listInstances() {
            return List.of(new InstanceDescriptor("test-instance", "Test", "1.0", "",
                    Path.of("test-instance"), Path.of("test-instance/test-instance.json")));
        }

        @Override
        public List<RemoteVersionDescriptor> listRemoteVersions(String component, String gameVersion) {
            lastComponent = component;
            lastGameVersion = gameVersion;
            return List.of(new RemoteVersionDescriptor("1.0", "release", "2026-01-01T00:00:00Z"));
        }

        @Override
        public HmclLaunchHandle launch(HmclLaunchRequest request, HmclLaunchEventSink events) {
            check(!request.toString().contains("secret-token"), "request toString leaked token");
            this.lastRequest = request;
            this.events = events;
            events.started();
            startedLatch.countDown();
            events.log("hello from game");
            return this::finish;
        }

        @Override
        public void stop(String instanceId) {
            finish();
        }

        private void finish() {
            stopped = true;
            stoppedLatch.countDown();
            HmclLaunchEventSink currentEvents = events;
            if (currentEvents != null && exited.compareAndSet(false, true)) {
                currentEvents.exit(0);
            }
        }
    }
}
