package top.fish1000.mcmcl.helper;

import top.fish1000.mcmcl.helper.hmcl.HmclCoreAdapter;
import top.fish1000.mcmcl.helper.protocol.JsonLineWriter;
import top.fish1000.mcmcl.helper.repository.RepositoryInstanceCatalog;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Process entry point for the standalone HMCL helper. */
public final class Main {
    private Main() {
    }

    /** Command line configuration of one helper run. */
    record HelperArgs(
            Path repository,
            String downloadProvider,
            String javafxVersion,
            Path javafxDirectory,
            String javafxRepository) {
    }

    public static void main(String[] args) {
        int exitCode;
        try {
            // HMCL Core's logger writes informational messages to
            // System.out. Keep stdout exclusively for the JSON Lines control
            // plane; diagnostics from this JVM belong on stderr.
            PrintStream protocolOutput = System.out;
            System.setOut(new PrintStream(System.err, true, StandardCharsets.UTF_8));

            HelperArgs helperArgs = parseHelperArgs(args);

            // The HMCL profile deliberately does not bundle the
            // platform-specific JavaFX modules.  Like HMCL's own bootstrap,
            // provision them once and relaunch with them on the classpath.
            if (HelperBuildInfo.hmclProfile()
                    && !JavaFxBootstrap.isJavaFxAvailable()
                    && !Boolean.getBoolean("mcmcl.helper.bootstrapped")) {
                String classifier = JavaFxBootstrap.detectClassifier();
                // Version and classifier subdirectories keep concurrently used
                // runtime variants from shadowing each other.
                Path javafxDirectory = helperArgs.javafxDirectory()
                        .resolve(helperArgs.javafxVersion() + "-" + classifier);
                String classpath;
                try {
                    classpath = JavaFxBootstrap.ensureModules(
                            javafxDirectory, helperArgs.javafxVersion(), classifier,
                            helperArgs.javafxRepository());
                } catch (IOException | RuntimeException e) {
                    error(System.err, "could not provision JavaFX " + helperArgs.javafxVersion()
                            + " for " + classifier + ": " + diagnosticMessage(e));
                    System.exit(1);
                    return;
                }
                Path helperJar = Path.of(JavaFxBootstrap.class.getProtectionDomain()
                        .getCodeSource().getLocation().toURI());
                int childCode = JavaFxBootstrap.relaunch(helperJar, classpath, List.of(args));
                if (childCode != 0) {
                    System.exit(childCode);
                }
                return;
            }

            if (!Files.isDirectory(helperArgs.repository())) {
                throw new IllegalArgumentException("Repository is not a directory: " + helperArgs.repository());
            }

            RepositoryInstanceCatalog catalog = new RepositoryInstanceCatalog(helperArgs.repository());
            HmclCoreAdapter adapter = HmclCoreAdapterFactory.create(
                    helperArgs.repository(), catalog, helperArgs.downloadProvider());
            HelperServer server = new HelperServer(adapter, new JsonLineWriter(protocolOutput));
            try (BufferedReader input = new BufferedReader(
                    new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                exitCode = server.run(input);
            }
        } catch (IllegalArgumentException e) {
            error(System.err, e.getMessage());
            exitCode = 2;
        } catch (Exception e) {
            error(System.err, diagnosticMessage(e));
            exitCode = 1;
        }

        // The JavaFX toolkit keeps a non-daemon thread alive, so the
        // standalone helper must terminate the JVM explicitly.  In-process
        // callers (the protocol tests) set mcmcl.helper.embedded to keep
        // control of the JVM.
        if (!Boolean.getBoolean("mcmcl.helper.embedded")) {
            System.exit(exitCode);
        }
    }

    static HelperArgs parseHelperArgs(String[] args) {
        Path repository = null;
        String downloadProvider = "mojang";
        String javafxVersion = JavaFxBootstrap.DEFAULT_VERSION;
        Path javafxDirectory = null;
        String javafxRepository = JavaFxBootstrap.DEFAULT_REPOSITORY;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--repository" -> {
                    String value = valueOf(args, i++, "--repository");
                    if (repository != null) {
                        throw new IllegalArgumentException("--repository may be specified only once");
                    }
                    repository = Path.of(value).toAbsolutePath().normalize();
                }
                case "--download-provider" -> downloadProvider = valueOf(args, i++, "--download-provider");
                case "--javafx-version" -> javafxVersion = valueOf(args, i++, "--javafx-version");
                case "--javafx-dir" -> javafxDirectory = Path.of(valueOf(args, i++, "--javafx-dir"))
                        .toAbsolutePath().normalize();
                case "--javafx-repo" -> javafxRepository = valueOf(args, i++, "--javafx-repo");
                case "--help", "-h" -> throw new IllegalArgumentException(
                        "usage: java -jar mcmcl-hmcl-helper.jar --repository <root> [--download-provider mojang|bmclapi|<url>]"
                                + " [--javafx-version <v>] [--javafx-dir <dir>] [--javafx-repo <url>]");
                default -> throw new IllegalArgumentException("Unknown argument: " + arg);
            }
        }
        if (repository == null) {
            throw new IllegalArgumentException("Missing required argument: --repository <root>");
        }
        return new HelperArgs(
                repository,
                validateDownloadProvider(downloadProvider),
                javafxVersion,
                javafxDirectory != null ? javafxDirectory : repository.resolve("javafx"),
                javafxRepository);
    }

    private static String valueOf(String[] args, int index, String option) {
        if (index + 1 >= args.length || args[index + 1].isBlank()) {
            throw new IllegalArgumentException(option + " requires a value");
        }
        return args[index + 1];
    }

    private static String validateDownloadProvider(String value) {
        if ("mojang".equals(value) || "bmclapi".equals(value)
                || value.startsWith("http://") || value.startsWith("https://")) {
            return value;
        }
        throw new IllegalArgumentException(
                "--download-provider must be mojang, bmclapi, or an http(s) URL: " + value);
    }

    private static void error(PrintStream stream, String message) {
        stream.println("mcmcl-hmcl-helper: " + (message == null ? "unknown error" : message));
    }

    private static String diagnosticMessage(Throwable error) {
        StringBuilder message = new StringBuilder();
        Throwable current = error;
        while (current != null) {
            if (message.length() > 0) {
                message.append("; caused by: ");
            }
            message.append(current.getClass().getSimpleName());
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                message.append(": ").append(current.getMessage());
            }
            current = current.getCause();
        }
        return message.toString();
    }
}
