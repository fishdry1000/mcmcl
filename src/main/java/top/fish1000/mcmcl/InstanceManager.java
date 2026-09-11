package top.fish1000.mcmcl;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import net.minecraft.client.Minecraft;

/** Discovers instance directories and handles the small amount of filesystem UI glue. */
public final class InstanceManager {
    private static final String DIRECTORY_README = """
            # MCMCL instances

            Create one directory per instance. Each directory needs a launch.json file.
            See the project's README for the launch.json format and token list.
            """;

    private InstanceManager() {
    }

    public static Path instancesDirectory(Minecraft minecraft) {
        Path configured = Path.of(Config.INSTANCES_DIRECTORY.get());
        Path root = configured.isAbsolute()
                ? configured
                : minecraft.gameDirectory.toPath().resolve(configured);
        return root.toAbsolutePath().normalize();
    }

    public static void ensureLayout(Minecraft minecraft) throws IOException {
        Path root = instancesDirectory(minecraft);
        Files.createDirectories(root);
        Path readme = root.resolve("README.md");
        if (Files.notExists(readme)) {
            Files.writeString(readme, DIRECTORY_README, StandardCharsets.UTF_8);
        }
    }

    public static DiscoveryResult discover(Minecraft minecraft) throws IOException {
        ensureLayout(minecraft);
        Path root = instancesDirectory(minecraft);
        List<InstanceDefinition> instances = new ArrayList<>();
        List<String> problems = new ArrayList<>();

        try (Stream<Path> children = Files.list(root)) {
            children.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                    .limit(Config.MAX_INSTANCES.get())
                    .forEach(directory -> {
                        Path manifest = directory.resolve(InstanceDefinition.MANIFEST_NAME);
                        if (Files.notExists(manifest)) {
                            return;
                        }
                        try {
                            instances.add(InstanceDefinition.read(directory));
                        } catch (Exception exception) {
                            String message = exception.getMessage() == null
                                    ? exception.getClass().getSimpleName()
                                    : exception.getMessage();
                            problems.add(directory.getFileName() + ": " + message);
                            MinecraftMinecraftLauncher.LOGGER.warn("Could not read MCMCL instance {}", directory, exception);
                        }
                    });
        }

        return new DiscoveryResult(List.copyOf(instances), List.copyOf(problems));
    }

    public static void openDirectory(Minecraft minecraft) throws IOException {
        Path directory = instancesDirectory(minecraft);
        ensureLayout(minecraft);
        if (!Desktop.isDesktopSupported()) {
            throw new IOException("Desktop integration is not available in this environment");
        }
        Desktop.getDesktop().open(directory.toFile());
    }

    public record DiscoveryResult(List<InstanceDefinition> instances, List<String> problems) {
        public DiscoveryResult {
            instances = List.copyOf(instances);
            problems = List.copyOf(problems);
        }
    }
}
