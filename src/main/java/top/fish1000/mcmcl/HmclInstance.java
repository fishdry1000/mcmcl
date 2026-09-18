package top.fish1000.mcmcl;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A small UI-facing description of an instance discovered by the HMCL helper.
 * {@code loader} is empty for vanilla instances; {@code root} is the
 * helper-reported
 * instance directory (empty when the helper did not provide one).
 */
public record HmclInstance(String id, String name, String version, String loader, String root) {
    private static final Pattern LEGACY_SNAPSHOT = Pattern
            .compile("\\d{2}w\\d{2}[a-z](?:_unobfuscated| unobfuscated)?");

    public HmclInstance {
        if (!isSafeId(id)) {
            throw new IllegalArgumentException("HMCL instance id must be a safe path segment");
        }
        name = name == null || name.isBlank() ? id : name;
        version = version == null || version.isBlank() ? id : version;
        loader = loader == null ? "" : loader;
        root = root == null ? "" : root;
    }

    /**
     * Whether the id is a non-blank path segment the repository layout can use as a
     * directory name.
     */
    public static boolean isSafeId(String id) {
        return id != null && !id.isBlank()
                && !id.equals(".")
                && !id.equals("..")
                && !id.contains("/")
                && !id.contains("\\")
                && id.indexOf('\u0000') < 0;
    }

    /**
     * Whether the instance carries a mod loader, so a mods folder is meaningful.
     */
    public boolean hasModLoader() {
        return !loader.isBlank();
    }

    /** Returns the bundled icon name using HMCL's default instance-icon order. */
    public String defaultIconName() {
        String normalizedLoader = loader.toLowerCase(Locale.ROOT);
        String loaderIcon = switch (normalizedLoader) {
            case "fabric", "forge", "neoforge", "quilt", "optifine" -> normalizedLoader;
            case "" -> null;
            default -> "command";
        };
        if (loaderIcon != null) {
            return loaderIcon;
        }

        String normalizedVersion = version.toLowerCase(Locale.ROOT);
        if (isOldVersion(normalizedVersion)) {
            return "craft_table";
        }
        if (LEGACY_SNAPSHOT.matcher(normalizedVersion).matches()
                || normalizedVersion.contains("snapshot")
                || normalizedVersion.matches(".*(?:-| )pre(?:-?release)?[ -]?\\d+.*")
                || normalizedVersion.matches(".*(?:-| )rc[ -]?\\d+.*")
                || normalizedVersion.contains("release candidate")) {
            return "command";
        }
        return "grass";
    }

    private static boolean isOldVersion(String version) {
        int numberStart;
        if (version.startsWith("rd-")) {
            numberStart = 3;
        } else if (version.startsWith("inf-")) {
            numberStart = 4;
        } else if (version.startsWith("in-")) {
            numberStart = 3;
        } else if (!version.isEmpty() && "abc".indexOf(version.charAt(0)) >= 0) {
            numberStart = 1;
        } else {
            return false;
        }
        return version.length() > numberStart && Character.isDigit(version.charAt(numberStart));
    }
}
