package top.fish1000.mcmcl;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Settings that affect the client-side launcher. */
public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue SHOW_LAUNCHER_BUTTON = BUILDER
            .comment("Whether to show the MCMCL button on the title and pause screens.")
            .define("showLauncherButton", true);

    public static final ModConfigSpec.ConfigValue<String> INSTANCES_DIRECTORY = BUILDER
            .comment("HMCL repository directory. Relative paths are resolved from the Minecraft game directory.")
            .define("instancesDirectory", "mcmcl/hmcl");

    public static final ModConfigSpec.ConfigValue<String> HMCL_HELPER_JAR = BUILDER
            .comment("Path to the standalone HMCL helper JAR. Relative paths are resolved from the Minecraft game directory.")
            .define("hmclHelperJar", "mcmcl/hmcl-helper.jar");

    public static final ModConfigSpec.IntValue MAX_INSTANCES = BUILDER
            .comment("Maximum number of instance directories shown in the in-game launcher.")
            .defineInRange("maxInstances", 32, 1, 256);

    public static final ModConfigSpec.BooleanValue OFFLINE_MODE = BUILDER
            .comment("Launch instances with an offline account instead of the current Minecraft session.")
            .define("offlineMode", false);

    public static final ModConfigSpec.ConfigValue<String> OFFLINE_USERNAME = BUILDER
            .comment("Username for the offline account. Blank falls back to the current session name.")
            .define("offlineUsername", "");

    public static final ModConfigSpec.ConfigValue<String> JAVA_PATH = BUILDER
            .comment("Full path to the Java executable used to launch instances. Blank uses the helper JVM's Java.")
            .define("javaPath", "");

    public static final ModConfigSpec.IntValue MAX_MEMORY = BUILDER
            .comment("Maximum memory in MB for launched instances. 0 uses the HMCL default.")
            .defineInRange("maxMemory", 0, 0, 65536);

    public static final ModConfigSpec.EnumValue<VersionIsolationPolicy> VERSION_ISOLATION = BUILDER
            .comment("Default version isolation policy: ALWAYS, MODDED, or NEVER.")
            .defineEnum("versionIsolation", VersionIsolationPolicy.MODDED);

    public static final ModConfigSpec.ConfigValue<String> DOWNLOAD_PROVIDER = BUILDER
            .comment("Download source used when installing instances: mojang or bmclapi.")
            .define("downloadProvider", "mojang");

    public static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {
    }

    public enum VersionIsolationPolicy {
        ALWAYS,
        MODDED,
        NEVER;

        public boolean isolates(boolean modded) {
            return this == ALWAYS || this == MODDED && modded;
        }
    }
}
