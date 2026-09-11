package top.fish1000.mcmcl.helper;

import top.fish1000.mcmcl.helper.hmcl.HmclCoreAdapter;
import top.fish1000.mcmcl.helper.protocol.JsonLineWriter;
import top.fish1000.mcmcl.helper.repository.RepositoryInstanceCatalog;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Process entry point for the standalone HMCL helper. */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        int exitCode;
        try {
            // HMCL Core's logger writes informational messages to
            // System.out. Keep stdout exclusively for the JSON Lines control
            // plane; diagnostics from this JVM belong on stderr.
            PrintStream protocolOutput = System.out;
            System.setOut(new PrintStream(System.err, true, StandardCharsets.UTF_8));

            Path repository = parseRepository(args);
            if (!Files.isDirectory(repository)) {
                throw new IllegalArgumentException("Repository is not a directory: " + repository);
            }

            RepositoryInstanceCatalog catalog = new RepositoryInstanceCatalog(repository);
            HmclCoreAdapter adapter = HmclCoreAdapterFactory.create(repository, catalog);
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

        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static Path parseRepository(String[] args) {
        Path repository = null;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--repository".equals(arg)) {
                if (++i >= args.length || args[i].isBlank()) {
                    throw new IllegalArgumentException("--repository requires a path");
                }
                if (repository != null) {
                    throw new IllegalArgumentException("--repository may be specified only once");
                }
                repository = Path.of(args[i]).toAbsolutePath().normalize();
            } else if ("--help".equals(arg) || "-h".equals(arg)) {
                throw new IllegalArgumentException(
                        "usage: java -jar mcmcl-hmcl-helper.jar --repository <root>");
            } else {
                throw new IllegalArgumentException("Unknown argument: " + arg);
            }
        }
        if (repository == null) {
            throw new IllegalArgumentException("Missing required argument: --repository <root>");
        }
        return repository;
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
