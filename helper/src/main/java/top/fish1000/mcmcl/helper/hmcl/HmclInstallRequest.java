package top.fish1000.mcmcl.helper.hmcl;

import top.fish1000.mcmcl.helper.protocol.Json;
import top.fish1000.mcmcl.helper.protocol.ProtocolException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Validated install request received from the mod side.
 *
 * <p>
 * A request with a {@code gameVersion} installs a fresh instance;
 * a request without one repairs an existing instance by re-downloading
 * missing files into the repository. A fresh install may additionally
 * request loader components (HMCL component types such as {@code fabric}
 * or {@code forge}) on top of the game version.
 * </p>
 */
public record HmclInstallRequest(
        String instanceId,
        String gameVersion,
        List<ComponentSpec> loaders,
        boolean versionIsolation) {

    /** One loader component: an HMCL component patch id plus its version. */
    public record ComponentSpec(String type, String version) {
    }

    private static final int MAX_LOADERS = 8;

    public static HmclInstallRequest install(Map<String, Object> request) throws ProtocolException {
        return new HmclInstallRequest(
                Json.requiredInstanceId(request),
                Json.requiredString(request, "gameVersion"),
                parseLoaders(request),
                Json.optionalBoolean(request, "versionIsolation", false));
    }

    public static HmclInstallRequest repair(Map<String, Object> request) throws ProtocolException {
        return new HmclInstallRequest(Json.requiredInstanceId(request), null, List.of(), false);
    }

    public boolean isNewInstall() {
        return gameVersion != null;
    }

    private static List<ComponentSpec> parseLoaders(Map<String, Object> request) throws ProtocolException {
        Object raw = request.get("loaders");
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> entries)) {
            throw new ProtocolException("loaders must be an array of {type, version} objects");
        }
        if (entries.size() > MAX_LOADERS) {
            throw new ProtocolException("loaders must contain at most " + MAX_LOADERS + " entries");
        }
        List<ComponentSpec> loaders = new ArrayList<>();
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> entryObject)) {
                throw new ProtocolException("each loader must be a JSON object with type and version");
            }
            Map<String, Object> entryMap = castStringKeys(entryObject);
            String type = Json.requiredString(entryMap, "type");
            String version = Json.requiredString(entryMap, "version");
            loaders.add(new ComponentSpec(type, version));
        }
        return List.copyOf(loaders);
    }

    private static Map<String, Object> castStringKeys(Map<?, ?> object) throws ProtocolException {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : object.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new ProtocolException("loader object keys must be strings");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }
}
