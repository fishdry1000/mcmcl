package top.fish1000.mcmcl.helper;

import java.nio.file.Path;

/** Tiny process used by the HMCL profile smoke test. */
public final class LaunchFixtureMain {
    private LaunchFixtureMain() {
    }

    public static void main(String[] args) {
        System.out.println("fixture-stdout");
        System.err.println("fixture-stderr");
        System.out.println("fixture-cwd=" + Path.of("").toAbsolutePath().normalize());
    }
}
