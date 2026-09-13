package top.fish1000.mcmcl;

/**
 * A small UI-facing description of an instance discovered by the HMCL helper.
 * {@code loader} is empty for vanilla instances; {@code root} is the helper-reported
 * instance directory (empty when the helper did not provide one).
 */
public record HmclInstance(String id, String name, String version, String loader, String root) {
    public HmclInstance {
        if (!isSafeId(id)) {
            throw new IllegalArgumentException("HMCL instance id must be a safe path segment");
        }
        name = name == null || name.isBlank() ? id : name;
        version = version == null || version.isBlank() ? id : version;
        loader = loader == null ? "" : loader;
        root = root == null ? "" : root;
    }

    /** Whether the id is a non-blank path segment the repository layout can use as a directory name. */
    public static boolean isSafeId(String id) {
        return id != null && !id.isBlank()
                && !id.equals(".")
                && !id.equals("..")
                && !id.contains("/")
                && !id.contains("\\")
                && id.indexOf('\u0000') < 0;
    }

    /** Whether the instance carries a mod loader, so a mods folder is meaningful. */
    public boolean hasModLoader() {
        return !loader.isBlank();
    }
}
