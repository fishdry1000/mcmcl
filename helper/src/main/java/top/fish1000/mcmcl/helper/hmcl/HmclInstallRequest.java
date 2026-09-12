package top.fish1000.mcmcl.helper.hmcl;

import top.fish1000.mcmcl.helper.protocol.Json;
import top.fish1000.mcmcl.helper.protocol.ProtocolException;

import java.util.Map;

/**
 * Validated install request received from the mod side.
 *
 * <p>A request with a {@code gameVersion} installs a fresh vanilla instance;
 * a request without one repairs an existing instance by re-downloading
 * missing files into the repository.</p>
 */
public record HmclInstallRequest(String instanceId, String gameVersion) {

    public static HmclInstallRequest install(Map<String, Object> request) throws ProtocolException {
        return new HmclInstallRequest(
                Json.requiredInstanceId(request),
                Json.requiredString(request, "gameVersion"));
    }

    public static HmclInstallRequest repair(Map<String, Object> request) throws ProtocolException {
        return new HmclInstallRequest(Json.requiredInstanceId(request), null);
    }

    public boolean isNewInstall() {
        return gameVersion != null;
    }
}
