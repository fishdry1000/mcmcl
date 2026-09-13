package top.fish1000.mcmcl;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import net.minecraft.client.Minecraft;

/** Resolves the HMCL repository and handles the small amount of filesystem UI glue. */
public final class InstanceManager {
    private static final String DIRECTORY_README = """
            # MCMCL HMCL repository

            This directory is managed by the standalone HMCL helper.
            Put standard HMCL/Minecraft version metadata under versions/.
            """;
    private static final Map<Path, HmclHelperClient> HELPERS = new ConcurrentHashMap<>();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(InstanceManager::closeHelpers, "mcmcl-hmcl-shutdown"));
    }

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
            Files.writeString(readme, DIRECTORY_README);
        }
    }

    public static DiscoveryResult discover(Minecraft minecraft) throws IOException {
        ensureLayout(minecraft);
        try {
            HmclHelperClient helper = helper(minecraft);
            List<HmclInstance> instances = helper
                    .listInstances()
                    .get(35, TimeUnit.SECONDS)
                    .stream()
                    .sorted(Comparator.comparing(HmclInstance::name, String.CASE_INSENSITIVE_ORDER))
                    .limit(Config.MAX_INSTANCES.get())
                    .toList();
            HmclHelperClient.HelperInfo info = helper.helperInfo();
            List<String> problems = info != null && !info.launchAvailable()
                    ? List.of("Helper " + info.helperVersion()
                            + " is not built with the HMCL Core launch profile")
                    : List.of();
            return new DiscoveryResult(instances, problems);
        } catch (Exception exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("Could not query the HMCL helper", cause);
        }
    }

    public static HmclHelperClient helper(Minecraft minecraft) {
        Path repository = instancesDirectory(minecraft);
        Path helperJar = resolveConfiguredPath(Config.HMCL_HELPER_JAR.get(), minecraft.gameDirectory.toPath());
        Path gameDirectory = minecraft.gameDirectory.toPath().toAbsolutePath().normalize();
        return HELPERS.computeIfAbsent(repository, ignored -> new HmclHelperClient(repository, helperJar, gameDirectory));
    }

    public static void openDirectory(Minecraft minecraft) throws IOException {
        Path directory = instancesDirectory(minecraft);
        ensureLayout(minecraft);
        openInFileManager(directory);
    }

    /** The instance's own directory, preferring the helper-reported root over the conventional layout. */
    public static Path instanceDirectory(Minecraft minecraft, HmclInstance instance) {
        if (!instance.root().isBlank()) {
            Path reported = Path.of(instance.root());
            if (Files.isDirectory(reported)) {
                return reported.toAbsolutePath().normalize();
            }
        }
        return instancesDirectory(minecraft).resolve("versions").resolve(instance.id());
    }

    /** Opens the instance directory, creating it when missing so it can be edited. */
    public static void openInstanceDirectory(Minecraft minecraft, HmclInstance instance) throws IOException {
        Path directory = instanceDirectory(minecraft, instance);
        Files.createDirectories(directory);
        openInFileManager(directory);
    }

    /**
     * Opens the mods folder of a modded instance: the instance-local
     * {@code mods} directory when present (HMCL version isolation), otherwise
     * the repository-wide one; when neither exists the instance-local one is
     * created.
     */
    public static void openInstanceModsDirectory(Minecraft minecraft, HmclInstance instance) throws IOException {
        Path instanceMods = instanceDirectory(minecraft, instance).resolve("mods");
        if (Files.isDirectory(instanceMods)) {
            openInFileManager(instanceMods);
            return;
        }
        Path sharedMods = instancesDirectory(minecraft).resolve("mods");
        if (Files.isDirectory(sharedMods)) {
            openInFileManager(sharedMods);
            return;
        }
        Files.createDirectories(instanceMods);
        openInFileManager(instanceMods);
    }

    private static void openInFileManager(Path directory) throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase();
        String[] command;
        if (os.contains("win")) {
            command = new String[]{"explorer.exe", directory.toString()};
        } else if (os.contains("mac") || os.contains("darwin")) {
            command = new String[]{"open", directory.toString()};
        } else {
            command = new String[]{"xdg-open", directory.toString()};
        }
        try {
            new ProcessBuilder(command).start();
        } catch (IOException exception) {
            // Fall back to Desktop on platforms without the shell opener.
            if (!Desktop.isDesktopSupported()) {
                throw exception;
            }
            Desktop.getDesktop().open(directory.toFile());
        }
    }

    private static void closeHelpers() {
        for (HmclHelperClient helper : HELPERS.values()) {
            try {
                helper.close();
            } catch (RuntimeException exception) {
                MinecraftMinecraftLauncher.LOGGER.debug("Could not close HMCL helper", exception);
            }
        }
        HELPERS.clear();
    }

    private static Path resolveConfiguredPath(String raw, Path gameDirectory) {
        Path path = Path.of(raw);
        return path.isAbsolute()
                ? path.normalize()
                : gameDirectory.toAbsolutePath().normalize().resolve(path).normalize();
    }

    public record DiscoveryResult(List<HmclInstance> instances, List<String> problems) {
        public DiscoveryResult {
            instances = List.copyOf(instances);
            problems = List.copyOf(problems);
        }
    }
}
