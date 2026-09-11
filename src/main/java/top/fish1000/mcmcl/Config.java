package top.fish1000.mcmcl;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Settings that affect the client-side launcher. */
public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.ConfigValue<String> INSTANCES_DIRECTORY = BUILDER
            .comment("HMCL repository directory. Relative paths are resolved from the Minecraft game directory.")
            .define("instancesDirectory", "mcmcl/hmcl");

    public static final ModConfigSpec.ConfigValue<String> HMCL_HELPER_JAR = BUILDER
            .comment("Path to the standalone HMCL helper JAR. Relative paths are resolved from the Minecraft game directory.")
            .define("hmclHelperJar", "mcmcl/hmcl-helper.jar");

    public static final ModConfigSpec.IntValue MAX_INSTANCES = BUILDER
            .comment("Maximum number of instance directories shown in the in-game launcher.")
            .defineInRange("maxInstances", 32, 1, 256);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {
    }
}
