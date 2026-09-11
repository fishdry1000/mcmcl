package top.fish1000.mcmcl.helper;

import top.fish1000.mcmcl.helper.hmcl.HmclCoreAdapter;
import top.fish1000.mcmcl.helper.hmcl.HmclLaunchEventSink;
import top.fish1000.mcmcl.helper.hmcl.HmclLaunchHandle;
import top.fish1000.mcmcl.helper.hmcl.HmclLaunchRequest;
import top.fish1000.mcmcl.helper.protocol.Json;
import top.fish1000.mcmcl.helper.protocol.JsonLineWriter;
import top.fish1000.mcmcl.helper.repository.InstanceDescriptor;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
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
        repositoryCatalogFindsConventionalAndFallbackManifests();
        defaultFactoryUsesUnavailableProviderWithoutProfile();
        serverListsAndRejectsMalformedRequests();
        serverBridgesLaunchLifecycleAndStop();
        int testCount = 5;
        if (Boolean.getBoolean("mcmcl.hmcl.profile")) {
            realHmclCoreLaunchesFixture();
            realHelperKeepsStdoutProtocolOnly();
            testCount += 2;
        }
        System.out.println("ProtocolTest: " + testCount + " tests passed");
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

    private static void repositoryCatalogFindsConventionalAndFallbackManifests() throws Exception {
        Path repository = Files.createTempDirectory("mcmcl-helper-catalog-");
        try {
            Path conventional = Files.createDirectories(repository.resolve("versions/1.26.2"));
            Files.writeString(conventional.resolve("1.26.2.json"), "{}", StandardCharsets.UTF_8);
            Path fallback = Files.createDirectories(repository.resolve("versions/custom"));
            Files.writeString(fallback.resolve("profile.json"), "{}", StandardCharsets.UTF_8);
            Files.createDirectories(repository.resolve("versions/without-manifest"));

            List<InstanceDescriptor> instances = new top.fish1000.mcmcl.helper.repository.RepositoryInstanceCatalog(repository).list();
            check(instances.size() == 2, "catalog should ignore directories without a manifest");
            check(instances.get(0).instanceId().equals("1.26.2"), "catalog should sort instance ids");
            check(instances.get(1).manifest().getFileName().toString().equals("profile.json"),
                    "catalog should support HMCL's single-json fallback");
        } finally {
            deleteTree(repository);
        }
    }

    private static void serverListsAndRejectsMalformedRequests() throws Exception {
        RecordingAdapter adapter = new RecordingAdapter();
        String input = "{\"id\":\"list-1\",\"command\":\"list\"}\n"
                + "not-json\n"
                + "{\"id\":\"bad-command\",\"command\":\"wat\"}\n"
                + "{\"id\":\"stop-1\",\"command\":\"stop\",\"instanceId\":\"none\"}\n"
                + "{\"id\":\"shutdown-1\",\"command\":\"shutdown\"}\n";
        List<Map<String, Object>> responses = run(adapter, input);
        check(hasResponse(responses, "list-1", true), "list response missing");
        check(hasResponse(responses, null, false), "malformed line should get a null-id error");
        check(hasResponse(responses, "bad-command", false), "unknown command should fail");
        check(hasResponse(responses, "stop-1", false), "unknown stop should fail");
        check(hasResponse(responses, "shutdown-1", true), "shutdown response missing");
    }

    private static void defaultFactoryUsesUnavailableProviderWithoutProfile() throws Exception {
        Path repository = Files.createTempDirectory("mcmcl-helper-factory-");
        try {
            var catalog = new top.fish1000.mcmcl.helper.repository.RepositoryInstanceCatalog(repository);
            String adapterName = HmclCoreAdapterFactory.create(repository, catalog).getClass().getSimpleName();
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
                    new top.fish1000.mcmcl.helper.repository.RepositoryInstanceCatalog(repository));
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
                    "demo", "Player", UUID.randomUUID(), "token", "msa", null, null);
            adapter.launch(request, events);
            check(started.get(), "real HMCL adapter did not report started");
            check(exit.get(10, TimeUnit.SECONDS) == 0, "fixture game did not exit successfully");
            check(logs.stream().anyMatch(line -> line.contains("fixture-stdout")),
                    "stdout was not bridged through HMCL Core: " + logs);
            check(logs.stream().anyMatch(line -> line.contains("fixture-stderr")),
                    "stderr was not bridged through HMCL Core: " + logs);
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
        try (PrintStream protocol = new PrintStream(protocolBytes, true, StandardCharsets.UTF_8);
             PrintStream diagnostics = new PrintStream(diagnosticsBytes, true, StandardCharsets.UTF_8)) {
            System.setIn(new ByteArrayInputStream(("{\"id\":\"list\",\"command\":\"list\"}\n"
                    + "{\"id\":\"shutdown\",\"command\":\"shutdown\"}\n")
                    .getBytes(StandardCharsets.UTF_8)));
            System.setOut(protocol);
            System.setErr(diagnostics);
            Main.main(new String[]{"--repository", repository.toString()});
        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
            System.setErr(originalErr);
            deleteTree(repository);
        }

        List<String> lines = protocolBytes.toString(StandardCharsets.UTF_8).lines().toList();
        check(lines.size() == 2, "helper stdout should contain exactly two protocol responses");
        for (String line : lines) {
            Object message = Json.parse(line);
            check(message instanceof Map<?, ?>, "helper stdout contained a non-JSON-object line");
        }
    }

    private static void createFixtureJar(Path jar) throws Exception {
        String className = "top/fish1000/mcmcl/helper/LaunchFixtureMain.class";
        try (InputStream input = LaunchFixtureMain.class.getResourceAsStream("/" + className);
             JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            check(input != null, "fixture class is not available on the test classpath");
            output.putNextEntry(new JarEntry(className));
            input.transferTo(output);
            output.closeEntry();
            output.putNextEntry(new JarEntry("version.json"));
            output.write("{\"id\":\"fixture\"}".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
    }

    private static List<Map<String, Object>> run(RecordingAdapter adapter, String input) throws Exception {
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

    private static final class RecordingAdapter implements HmclCoreAdapter {
        private volatile boolean stopped;
        private volatile HmclLaunchEventSink events;
        private final AtomicBoolean exited = new AtomicBoolean();
        private final CountDownLatch startedLatch = new CountDownLatch(1);
        private final CountDownLatch stoppedLatch = new CountDownLatch(1);

        @Override
        public List<InstanceDescriptor> listInstances() {
            return List.of(new InstanceDescriptor("test-instance", "Test", Path.of("test-instance"),
                    Path.of("test-instance/test-instance.json")));
        }

        @Override
        public HmclLaunchHandle launch(HmclLaunchRequest request, HmclLaunchEventSink events) {
            check(!request.toString().contains("secret-token"), "request toString leaked token");
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
