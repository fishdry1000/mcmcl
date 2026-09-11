package top.fish1000.mcmcl;

/** A small UI-facing description of an instance discovered by the HMCL helper. */
public record HmclInstance(String id, String name, String version) {
    public HmclInstance {
        if (id == null || id.isBlank()
                || id.equals(".")
                || id.equals("..")
                || id.contains("/")
                || id.contains("\\")
                || id.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("HMCL instance id must be a safe path segment");
        }
        name = name == null || name.isBlank() ? id : name;
        version = version == null || version.isBlank() ? id : version;
    }
}
