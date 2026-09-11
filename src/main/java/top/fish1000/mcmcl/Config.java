package top.fish1000.mcmcl;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Settings that affect the client-side launcher. */
public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.ConfigValue<String> INSTANCES_DIRECTORY = BUILDER
            .comment("Directory containing MCMCL instances. Relative paths are resolved from the Minecraft game directory.")
            .define("instancesDirectory", "mcmcl/instances");

    public static final ModConfigSpec.BooleanValue ALLOW_CUSTOM_JAVA = BUILDER
            .comment("Allow an instance manifest to select a Java executable different from the current one.")
            .define("allowCustomJava", true);

    public static final ModConfigSpec.IntValue MAX_INSTANCES = BUILDER
            .comment("Maximum number of instance directories shown in the in-game launcher.")
            .defineInRange("maxInstances", 32, 1, 256);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {
    }
}
