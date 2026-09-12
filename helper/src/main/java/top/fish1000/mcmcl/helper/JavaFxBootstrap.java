package top.fish1000.mcmcl.helper;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;

/**
 * Resolves the JavaFX modules HMCL Core needs at runtime, so the HMCL profile
 * JAR itself stays platform independent.
 *
 * <p>Like HMCL's own bootstrap, the first startup downloads the three JavaFX
 * modules for the current platform into a cache directory and relaunches the
 * helper with them on the classpath.  Later startups reuse the cached jars;
 * pointing {@code --javafx-dir} at a pre-populated directory skips the
 * download entirely (offline machines).</p>
 */
public final class JavaFxBootstrap {
    /** The JavaFX modules HMCL Core touches; order defines the classpath. */
    private static final List<String> MODULES = List.of("base", "graphics", "controls");
    public static final String DEFAULT_REPOSITORY = "https://repo1.maven.org/maven2";
    public static final String DEFAULT_VERSION = "25";

    private static final int CONNECT_TIMEOUT_MILLIS = 15_000;
    private static final int READ_TIMEOUT_MILLIS = 60_000;

    private JavaFxBootstrap() {
    }

    /** Whether the current JVM can already load JavaFX classes. */
    public static boolean isJavaFxAvailable() {
        try {
            Class.forName("javafx.application.Platform", false, JavaFxBootstrap.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    /** The OpenJFX platform classifier of the current machine. */
    public static String detectClassifier() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean aarch64 = arch.contains("aarch64") || arch.contains("arm64");
        if (os.contains("win")) {
            return aarch64 ? "win-aarch64" : "win";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return aarch64 ? "mac-aarch64" : "mac";
        }
        if (os.contains("linux")) {
            return aarch64 ? "linux-aarch64" : "linux";
        }
        throw new IllegalStateException("Unsupported JavaFX platform: " + os + "/" + arch);
    }

    /**
     * Makes sure the JavaFX module jars exist under {@code directory} and
     * returns them as a classpath fragment.  Present jars are reused without
     * contacting the repository; missing ones are downloaded and verified
     * against the repository's SHA-1 checksum.
     */
    public static String ensureModules(Path directory, String version, String classifier, String repository)
            throws IOException {
        StringJoiner classpath = new StringJoiner(File.pathSeparator);
        for (String module : MODULES) {
            Path jar = directory.resolve("javafx-" + module + ".jar");
            if (!Files.isRegularFile(jar) || Files.size(jar) == 0) {
                downloadModule(jar, module, version, classifier, repository);
            }
            classpath.add(jar.toAbsolutePath().toString());
        }
        return classpath.toString();
    }

    private static void downloadModule(Path target, String module, String version, String classifier,
                                       String repository) throws IOException {
        String base = repository.replaceAll("/+$", "");
        String fileName = "javafx-" + module + "-" + version + "-" + classifier + ".jar";
        URI jarUri = URI.create(base + "/org/openjfx/javafx-" + module + "/" + version + "/" + fileName);
        URI shaUri = jarUri.resolve(fileName + ".sha1");

        System.err.println("mcmcl-hmcl-helper: downloading " + fileName + " from " + jarUri);
        Path temp = target.resolveSibling(target.getFileName() + ".download");
        Files.createDirectories(target.getParent());
        download(jarUri, temp);
        String expectedSha = readSha1(shaUri);
        String actualSha = sha1(temp);
        if (!expectedSha.equalsIgnoreCase(actualSha)) {
            Files.deleteIfExists(temp);
            throw new IOException("SHA-1 mismatch for " + fileName
                    + ": expected " + expectedSha + ", got " + actualSha);
        }
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // A concurrent helper may have won the race; keep either jar.
            Files.deleteIfExists(temp);
            if (!Files.isRegularFile(target)) {
                throw e;
            }
        }
    }

    private static void download(URI uri, Path target) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
        connection.setReadTimeout(READ_TIMEOUT_MILLIS);
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) {
            throw new IOException("HTTP " + status + " for " + uri);
        }
        try (InputStream input = connection.getInputStream()) {
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            connection.disconnect();
        }
    }

    private static String readSha1(URI uri) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
        connection.setReadTimeout(READ_TIMEOUT_MILLIS);
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) {
            throw new IOException("HTTP " + status + " for " + uri);
        }
        try (InputStream input = connection.getInputStream()) {
            String text = new String(input.readAllBytes(), StandardCharsets.UTF_8).strip();
            int end = text.indexOf(' ');
            String sha = end < 0 ? text : text.substring(0, end);
            if (sha.length() != 40) {
                throw new IOException("Malformed SHA-1 checksum: " + sha);
            }
            return sha;
        } finally {
            connection.disconnect();
        }
    }

    private static String sha1(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            StringBuilder result = new StringBuilder();
            for (byte value : digest.digest()) {
                result.append(String.format("%02x", value));
            }
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Relaunches the helper with the helper JAR plus {@code classpath} and
     * waits for it, forwarding the exit code.  The child inherits stdin and
     * stdout, so the protocol pipes keep working across the restart.
     */
    public static int relaunch(Path helperJar, String classpath, List<String> args) throws IOException,
            InterruptedException {
        String javaHome = System.getProperty("java.home");
        String executable = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? "java.exe"
                : "java";
        String mainClass = Main.class.getName();

        ProcessBuilder builder = new ProcessBuilder(
                javaHome == null ? "java" : Path.of(javaHome, "bin", executable).toString(),
                "--enable-native-access=ALL-UNNAMED",
                "-Dmcmcl.helper.bootstrapped=true",
                "-cp",
                helperJar.toAbsolutePath() + File.pathSeparator + classpath,
                mainClass);
        builder.command().addAll(args);
        builder.inheritIO();
        Process process = builder.start();
        return process.waitFor();
    }
}
