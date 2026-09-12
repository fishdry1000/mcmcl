package top.fish1000.mcmcl.helper.hmcl;

import java.util.LinkedHashMap;
import java.util.Map;

/** Stable, protocol-facing subset of an installable remote game version. */
public record RemoteVersionDescriptor(String id, String type, String releaseTime) {
    public Map<String, Object> toJson() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("type", type);
        result.put("releaseTime", releaseTime);
        return result;
    }
}
