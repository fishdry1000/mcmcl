package top.fish1000.mcmcl;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;

/**
 * Extracts the HMCL helper JAR bundled in the mod so the launcher works out
 * of the box.
 *
 * <p>
 * Extractions are tracked with a marker file next to the helper JAR: a
 * missing helper is extracted, a stale extraction is refreshed when the mod
 * ships a newer helper, and a helper that was placed by the user (no marker)
 * is never touched.
 * </p>
 *
 * <p>
 * This class deliberately depends only on the JDK so its behavior can be
 * exercised outside the game process.
 * </p>
 */
public final class HelperBundle {
    /** Resource path of the helper JAR inside the mod. */
    public static final String RESOURCE = "/helper/hmcl-helper.jar";
    private static final String MARKER_SUFFIX = ".bundled-sha1";

    private HelperBundle() {
    }

    /**
     * Makes sure {@code helperJar} matches the helper bundled in the mod.
     *
     * @return {@code true} when the mod bundles a helper (so extraction is
     *         managed), {@code false} when this mod build ships without one
     * @throws IOException when reading the bundle or extracting fails
     */
    public static boolean ensureExtracted(Path helperJar) throws IOException {
        byte[] embedded;
        try (InputStream input = HelperBundle.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                return false;
            }
            embedded = input.readAllBytes();
        }

        String embeddedSha = sha1(embedded);
        Path marker = helperJar.resolveSibling(helperJar.getFileName() + MARKER_SUFFIX);
        if (Files.isRegularFile(helperJar)) {
            String recorded = Files.isRegularFile(marker)
                    ? Files.readString(marker, StandardCharsets.UTF_8).strip()
                    : "";
            if (recorded.equals(embeddedSha) || recorded.isEmpty()) {
                // Up to date, or placed by the user and therefore theirs.
                return true;
            }
            // Our own older extraction: fall through and refresh it.
        }

        Path parent = helperJar.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = helperJar.resolveSibling(helperJar.getFileName() + ".tmp");
        Files.write(temp, embedded);
        try {
            Files.move(temp, helperJar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, helperJar, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.writeString(marker, embeddedSha + "\n", StandardCharsets.UTF_8);
        return true;
    }

    private static String sha1(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            StringBuilder result = new StringBuilder();
            for (byte value : digest.digest(data)) {
                result.append(String.format("%02x", value));
            }
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
