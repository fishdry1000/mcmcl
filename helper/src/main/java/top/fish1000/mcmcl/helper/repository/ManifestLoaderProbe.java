package top.fish1000.mcmcl.helper.repository;

import top.fish1000.mcmcl.helper.protocol.Json;
import top.fish1000.mcmcl.helper.protocol.ProtocolException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Best-effort read-only sniff of the mod loader recorded in a version
 * manifest, used to label instances in the UI and to decide whether a mods
 * folder is meaningful. It never fails listing: unreadable or unparsable
 * manifests simply report no loader.
 */
public final class ManifestLoaderProbe {
    private static final long MAX_MANIFEST_BYTES = 8 * 1024 * 1024;
    /** Loader component ids HMCL writes into the manifest's patches array. */
    private static final List<String> PATCH_IDS = List.of(
            "fabric", "forge", "neoforge", "quilt", "optifine", "liteloader", "cleanroom");
    /**
     * Marker substrings of library coordinates for manifests without patches, in
     * priority order.
     */
    private static final List<String[]> LOADER_MARKERS = List.of(
            new String[] { "net.fabricmc:fabric-loader", "fabric" },
            new String[] { "org.quiltmc:quilt-loader", "quilt" },
            new String[] { "net.neoforged.fancymodloader", "neoforge" },
            new String[] { "net.neoforged:neoforge", "neoforge" },
            new String[] { "net.minecraftforge:forge", "forge" },
            new String[] { "net.minecraftforge:minecraftforge", "forge" },
            new String[] { "net.minecraftforge:fmlcore", "forge" },
            new String[] { "optifine:optifine", "optifine" },
            new String[] { "com.mumfrey:liteloader", "liteloader" });

    private ManifestLoaderProbe() {
    }

    /**
     * Returns a loader id such as {@code fabric}, or an empty string for vanilla or
     * unreadable manifests.
     */
    public static String detect(Path manifest) {
        if (manifest == null || !Files.isRegularFile(manifest)) {
            return "";
        }
        try {
            if (Files.size(manifest) > MAX_MANIFEST_BYTES) {
                return "";
            }
            Object parsed = Json.parse(Files.readString(manifest));
            if (!(parsed instanceof Map<?, ?> manifestObject)) {
                return "";
            }
            // HMCL-installed instances record their components as patches
            // (game/neoforge/...), which is the most precise signal. The
            // merged libraries of a NeoForge install notably do not contain
            // a "net.neoforged:neoforge" coordinate at all.
            Object patches = manifestObject.get("patches");
            if (patches instanceof List<?> patchList) {
                for (Object patch : patchList) {
                    if (patch instanceof Map<?, ?> patchObject
                            && patchObject.get("id") instanceof String patchId
                            && PATCH_IDS.contains(patchId.toLowerCase())) {
                        return patchId.toLowerCase();
                    }
                }
            }
            Object libraries = manifestObject.get("libraries");
            if (!(libraries instanceof List<?> libraryList)) {
                return "";
            }
            for (Object library : libraryList) {
                if (!(library instanceof Map<?, ?> libraryObject)) {
                    continue;
                }
                Object name = libraryObject.get("name");
                if (!(name instanceof String coordinates)) {
                    continue;
                }
                String lowered = coordinates.toLowerCase();
                for (String[] marker : LOADER_MARKERS) {
                    if (lowered.contains(marker[0])) {
                        return marker[1];
                    }
                }
            }
            return "";
        } catch (IOException | ProtocolException | RuntimeException ignored) {
            return "";
        }
    }
}
