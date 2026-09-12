package top.fish1000.mcmcl.helper.hmcl;

import top.fish1000.mcmcl.helper.protocol.Json;
import top.fish1000.mcmcl.helper.protocol.ProtocolException;

import java.util.Map;
import java.util.UUID;

/** Validated launch credentials received from the mod side. */
public record HmclLaunchRequest(
        String instanceId,
        String username,
        UUID uuid,
        String accessToken,
        String userType,
        String xuid,
        String clientId,
        String javaPath,
        Integer maxMemory) {

    public static HmclLaunchRequest from(Map<String, Object> request) throws ProtocolException {
        String instanceId = Json.requiredInstanceId(request);
        String username = Json.requiredString(request, "username");
        String uuidText = Json.requiredString(request, "uuid");
        String accessToken = Json.requiredString(request, "accessToken");
        String userType = Json.requiredString(request, "userType");
        String xuid = Json.optionalString(request, "xuid");
        String clientId = Json.optionalString(request, "clientId");
        String javaPath = Json.optionalString(request, "javaPath");
        Integer maxMemory = optionalPositiveInt(request, "maxMemory");

        final UUID uuid;
        try {
            uuid = UUID.fromString(uuidText);
        } catch (IllegalArgumentException e) {
            throw new ProtocolException("uuid must be a UUID string");
        }

        return new HmclLaunchRequest(
                instanceId, username, uuid, accessToken, userType, xuid, clientId, javaPath, maxMemory);
    }

    private static Integer optionalPositiveInt(Map<String, Object> request, String key)
            throws ProtocolException {
        Object value = request.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Number number) || number.intValue() != number.doubleValue()) {
            throw new ProtocolException(key + " must be an integer");
        }
        if (number.intValue() <= 0) {
            throw new ProtocolException(key + " must be positive");
        }
        return number.intValue();
    }

    /** Avoid accidentally logging credentials while debugging adapter code. */
    @Override
    public String toString() {
        return "HmclLaunchRequest[instanceId=" + instanceId
                + ", username=" + username
                + ", uuid=" + uuid
                + ", accessToken=<redacted>"
                + ", userType=" + userType
                + ", xuid=" + xuid
                + ", clientId=" + clientId
                + ", javaPath=" + javaPath
                + ", maxMemory=" + maxMemory + ']';
    }
}
