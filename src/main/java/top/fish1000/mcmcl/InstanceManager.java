package top.fish1000.mcmcl;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;

/**
 * Resolves the HMCL repository and handles the small amount of filesystem UI
 * glue.
 */
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
        return HELPERS.computeIfAbsent(repository,
                ignored -> new HmclHelperClient(repository, helperJar, gameDirectory));
    }

    public static void openDirectory(Minecraft minecraft) throws IOException {
        Path directory = instancesDirectory(minecraft);
        ensureLayout(minecraft);
        openInFileManager(directory);
    }

    /** Whether a version directory with this id exists in the repository. */
    public static boolean instanceExists(Minecraft minecraft, String instanceId) {
        return Files.isDirectory(instancesDirectory(minecraft).resolve("versions").resolve(instanceId));
    }

    /**
     * Renames an instance: moves {@code versions/<oldId>} to
     * {@code versions/<newId>}, renames the conventional manifest file, fixes
     * the manifest's {@code id}/{@code jar} fields and migrates the stored
     * per-instance settings. Case-only renames go through a temporary name
     * because Windows treats the paths as identical.
     */
    public static void renameInstance(Minecraft minecraft, HmclInstance instance, String newId) throws IOException {
        if (!HmclInstance.isSafeId(newId)) {
            throw new IOException("New instance id is not a safe path segment: " + newId);
        }
        if (newId.equals(instance.id())) {
            return;
        }
        if (helper(minecraft).isRunning(instance.id())) {
            throw new IOException("Instance is still running: " + instance.id());
        }
        Path versions = instancesDirectory(minecraft).resolve("versions");
        Path oldDir = versions.resolve(instance.id());
        Path newDir = versions.resolve(newId);
        boolean caseOnly = newId.equalsIgnoreCase(instance.id());
        if (Files.exists(newDir) && !caseOnly) {
            throw new IOException("Instance already exists: " + newId);
        }
        if (!Files.isDirectory(oldDir)) {
            throw new IOException("Instance directory is missing: " + oldDir);
        }
        Path manifest = findManifest(oldDir, instance.id());

        if (caseOnly) {
            Path temp = versions.resolve(instance.id() + ".mcmcl-renaming");
            Files.move(oldDir, temp);
            oldDir = temp;
        }
        Files.move(oldDir, newDir);
        try {
            if (manifest != null) {
                Path movedManifest = newDir.resolve(manifest.getFileName());
                Path targetManifest = manifest.getFileName().toString().equals(instance.id() + ".json")
                        ? newDir.resolve(newId + ".json")
                        : movedManifest;
                if (targetManifest != movedManifest) {
                    Files.move(movedManifest, targetManifest);
                }
                rewriteManifestIdentity(targetManifest, instance.id(), newId);
            }
            InstanceSettingsStore.rename(instancesDirectory(minecraft), instance.id(), newId);
        } catch (IOException exception) {
            // Best effort rollback so the instance stays discoverable.
            Files.move(newDir, oldDir);
            throw exception;
        }
    }

    private static Path findManifest(Path instanceDir, String instanceId) throws IOException {
        Path conventional = instanceDir.resolve(instanceId + ".json");
        if (Files.isRegularFile(conventional)) {
            return conventional;
        }
        try (Stream<Path> files = Files.list(instanceDir)) {
            List<Path> jsonFiles = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".json"))
                    .toList();
            return jsonFiles.size() == 1 ? jsonFiles.get(0) : null;
        }
    }

    private static void rewriteManifestIdentity(Path manifest, String oldId, String newId) throws IOException {
        JsonObject root = JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
        boolean changed = false;
        if (root.has("id") && oldId.equals(root.get("id").getAsString())) {
            root.addProperty("id", newId);
            changed = true;
        }
        if (root.has("jar") && oldId.equals(root.get("jar").getAsString())) {
            root.addProperty("jar", newId);
            changed = true;
        }
        if (changed) {
            Path temp = manifest.resolveSibling(manifest.getFileName() + ".tmp");
            Files.writeString(temp, root.toString());
            Files.move(temp, manifest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Opens the mods folder selected by the instance's effective version
     * isolation setting.
     */
    public static void openInstanceModsDirectory(Minecraft minecraft, HmclInstance instance) throws IOException {
        Path instanceRoot = instance.root().isBlank()
                ? instancesDirectory(minecraft).resolve("versions").resolve(instance.id())
                : Path.of(instance.root());
        Path instanceMods = instanceRoot.toAbsolutePath().normalize().resolve("mods");
        Path sharedMods = instancesDirectory(minecraft).resolve("mods");
        InstanceSettingsStore.Settings settings = InstanceSettingsStore.read(
                instancesDirectory(minecraft), instance.id());
        boolean isolated = settings.versionIsolation().resolve(
                Config.VERSION_ISOLATION.get().isolates(instance.hasModLoader()));
        Path modsDirectory = isolated ? instanceMods : sharedMods;
        Files.createDirectories(modsDirectory);
        openInFileManager(modsDirectory);
    }

    private static void openInFileManager(Path directory) throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase();
        String[] command;
        if (os.contains("win")) {
            command = new String[] { "explorer.exe", directory.toString() };
        } else if (os.contains("mac") || os.contains("darwin")) {
            command = new String[] { "open", directory.toString() };
        } else {
            command = new String[] { "xdg-open", directory.toString() };
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
