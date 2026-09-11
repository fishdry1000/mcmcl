package top.fish1000.mcmcl.helper;

/** Tiny process used by the HMCL profile smoke test. */
public final class LaunchFixtureMain {
    private LaunchFixtureMain() {
    }

    public static void main(String[] args) {
        System.out.println("fixture-stdout");
        System.err.println("fixture-stderr");
    }
}
