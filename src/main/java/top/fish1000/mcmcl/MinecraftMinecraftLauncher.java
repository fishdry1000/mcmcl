package top.fish1000.mcmcl;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;

/**
 * The common entry point for MCMCL.
 *
 * <p>The actual launcher UI is client-only. Keeping this entry point small is
 * intentional: the mod can still be present in a modpack without loading any
 * client classes on a dedicated server.</p>
 */
@Mod(MinecraftMinecraftLauncher.MODID)
public final class MinecraftMinecraftLauncher {
    public static final String MODID = "minecraftminecraftlauncher";
    public static final Logger LOGGER = LogUtils.getLogger();

    public MinecraftMinecraftLauncher(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.CLIENT, Config.SPEC);
    }
}
