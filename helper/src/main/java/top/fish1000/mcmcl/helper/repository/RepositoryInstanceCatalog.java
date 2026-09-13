package top.fish1000.mcmcl.helper.repository;

import top.fish1000.mcmcl.helper.protocol.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Minimal read-only view of the official Minecraft/HMCL repository layout.
 * It deliberately does not parse manifests; HMCL Core will own manifest
 * inheritance and validation once the real adapter is installed.
 */
public final class RepositoryInstanceCatalog {
    private final Path repository;

    public RepositoryInstanceCatalog(Path repository) {
        this.repository = repository.toAbsolutePath().normalize();
    }

    public List<InstanceDescriptor> list() throws IOException {
        Path versions = repository.resolve("versions");
        if (!Files.isDirectory(versions)) {
            return List.of();
        }

        try (Stream<Path> entries = Files.list(versions)) {
            return entries
                    .filter(Files::isDirectory)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .map(this::describe)
                    .filter(java.util.Objects::nonNull)
                    .toList();
        }
    }

    private InstanceDescriptor describe(Path instanceRoot) {
        String instanceId = instanceRoot.getFileName().toString();
        if (!Json.isSafeInstanceId(instanceId)) {
            return null;
        }

        Path conventionalManifest = instanceRoot.resolve(instanceId + ".json");
        if (Files.isRegularFile(conventionalManifest)) {
            return describe(instanceId, instanceRoot, conventionalManifest);
        }

        try (Stream<Path> files = Files.list(instanceRoot)) {
            List<Path> jsonFiles = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".json"))
                    .toList();
            if (jsonFiles.size() == 1) {
                return describe(instanceId, instanceRoot, jsonFiles.get(0));
            }
        } catch (IOException ignored) {
            // A single unreadable instance should not make list fail for all
            // other instances.  HMCL itself also ignores un-loadable entries.
        }
        return null;
    }

    private InstanceDescriptor describe(String instanceId, Path instanceRoot, Path manifest) {
        return new InstanceDescriptor(instanceId, instanceId, instanceId,
                ManifestLoaderProbe.detect(manifest),
                instanceRoot.toAbsolutePath().normalize(), manifest.toAbsolutePath().normalize());
    }
}
