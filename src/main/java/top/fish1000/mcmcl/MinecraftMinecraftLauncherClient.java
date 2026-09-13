package top.fish1000.mcmcl;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

/** Client entry point and integration points for the in-game launcher. */
@Mod(value = MinecraftMinecraftLauncher.MODID, dist = Dist.CLIENT)
public final class MinecraftMinecraftLauncherClient {
    private static final KeyMapping OPEN_LAUNCHER = new KeyMapping(
            "key.minecraftminecraftlauncher.open_launcher",
            InputConstants.Type.KEYSYM,
            InputConstants.KEY_M,
            KeyMapping.Category.MISC
    );

    public MinecraftMinecraftLauncherClient(IEventBus modEventBus, ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        modEventBus.addListener(this::registerKeyMappings);
        NeoForge.EVENT_BUS.register(this);
    }

    private void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(OPEN_LAUNCHER);
    }

    @SubscribeEvent
    public void onScreenInit(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        if (!(screen instanceof TitleScreen) && !(screen instanceof PauseScreen)) {
            return;
        }

        // Parked in the top-left corner, which both the title and pause
        // screens keep free (logo and menu rows are centered).
        Button button = Button.builder(
                        Component.translatable("screen.minecraftminecraftlauncher.open"),
                        ignored -> Minecraft.getInstance().setScreenAndShow(new LauncherScreen(screen)))
                .bounds(4, 4, 200, 20)
                .build();
        event.addListener(button);
    }

    @SubscribeEvent
    public void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gui.screen() == null && OPEN_LAUNCHER.consumeClick()) {
            minecraft.setScreenAndShow(new LauncherScreen(null));
        }
    }
}
