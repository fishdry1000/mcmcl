package top.fish1000.mcmcl.helper.repository;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stable, protocol-facing subset of an HMCL game instance.  {@code loader}
 * is a best-effort sniff of the manifest (see {@link ManifestLoaderProbe})
 * and is empty for vanilla instances.
 */
public record InstanceDescriptor(
        String instanceId, String name, String version, String loader, Path root, Path manifest) {
    public InstanceDescriptor {
        loader = loader == null ? "" : loader;
    }

    public Map<String, Object> toJson() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("instanceId", instanceId);
        result.put("name", name);
        result.put("version", version);
        result.put("loader", loader);
        result.put("root", root.toString());
        result.put("manifest", manifest.toString());
        return result;
    }
}
