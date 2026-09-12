package top.fish1000.mcmcl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** A deliberately small instance picker; the launch protocol lives outside the UI. */
public final class LauncherScreen extends Screen {
    private static final int PANEL_WIDTH = 560;
    private static final int ROW_HEIGHT = 32;

    private final Screen parent;
    private List<HmclInstance> instances = List.of();
    private final List<RowWidgets> rowWidgets = new ArrayList<>();
    private final Set<String> stoppingInstances = new HashSet<>();
    private int scrollOffset;
    private Button installButton;
    private Component status = Component.translatable("screen.minecraftminecraftlauncher.loading");
    private boolean statusError;
    private String latestOutput = "";
    private boolean initialDiscoveryStarted;

    public LauncherScreen(Screen parent) {
        super(Component.translatable("screen.minecraftminecraftlauncher.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        rowWidgets.clear();
        if (!initialDiscoveryStarted) {
            initialDiscoveryStarted = true;
            reloadInstances();
        }

        int panelWidth = Math.min(PANEL_WIDTH, Math.max(300, width - 32));
        int left = (width - panelWidth) / 2;
        int bottom = height - 42;
        int rowTop = 70;
        int visibleRows = Math.max(1, (bottom - rowTop) / ROW_HEIGHT);
        scrollOffset = Math.min(scrollOffset, Math.max(0, instances.size() - visibleRows));

        for (int index = 0; index < Math.min(visibleRows, instances.size() - scrollOffset); index++) {
            HmclInstance instance = instances.get(index + scrollOffset);
            Button button = Button.builder(Component.empty(), ignored -> toggle(instance))
                    .bounds(left + panelWidth - 108, rowTop + index * ROW_HEIGHT, 100, 20)
                    .build();
            rowWidgets.add(new RowWidgets(instance, button));
            addRenderableWidget(button);
        }

        ButtonRowLayout.Geometry bottomRow = ButtonRowLayout.centered(left, panelWidth, 8, 100, 160, 100, 100);
        addRenderableWidget(Button.builder(
                        Component.translatable("screen.minecraftminecraftlauncher.refresh"),
                        ignored -> reloadInstances())
                .bounds(bottomRow.x()[0], height - 32, bottomRow.width()[0], 20)
                .build());
        addRenderableWidget(Button.builder(
                        Component.translatable("screen.minecraftminecraftlauncher.open_directory"),
                        ignored -> openDirectory())
                .bounds(bottomRow.x()[1], height - 32, bottomRow.width()[1], 20)
                .build());
        installButton = Button.builder(
                        Component.translatable("screen.minecraftminecraftlauncher.install"),
                        ignored -> minecraft.setScreenAndShow(new InstallScreen(this)))
                .bounds(bottomRow.x()[2], height - 32, bottomRow.width()[2], 20)
                .build();
        addRenderableWidget(installButton);
        addRenderableWidget(Button.builder(
                        Component.translatable("gui.done"),
                        ignored -> onClose())
                .bounds(bottomRow.x()[3], height - 32, bottomRow.width()[3], 20)
                .build());
        updateRowButtons();
    }

    @Override
    public void tick() {
        updateRowButtons();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int bottom = height - 42;
        int rowTop = 70;
        int visibleRows = Math.max(1, (bottom - rowTop) / ROW_HEIGHT);
        int maxOffset = Math.max(0, instances.size() - visibleRows);
        int newOffset = Math.max(0, Math.min(maxOffset, scrollOffset - (int) Math.signum(scrollY)));
        if (newOffset != scrollOffset) {
            scrollOffset = newOffset;
            rebuildWidgets();
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int panelWidth = Math.min(PANEL_WIDTH, Math.max(300, width - 32));
        int left = (width - panelWidth) / 2;
        int right = left + panelWidth;
        int bottom = height - 42;
        int rowTop = 70;
        int visibleRows = Math.max(1, (bottom - rowTop) / ROW_HEIGHT);

        graphics.centeredText(font, title, width / 2, 20, 0xFFFFFFFF);
        graphics.centeredText(font, status, width / 2, 40, statusError ? 0xFFFF7777 : 0xFFB8E0FF);
        graphics.fill(left - 8, 58, right + 8, Math.max(64, bottom - 4), 0x66000000);

        if (instances.isEmpty()) {
            graphics.centeredText(
                    font,
                    Component.translatable("screen.minecraftminecraftlauncher.empty"),
                    width / 2,
                    86,
                    0xFFD0D0D0
            );
        } else {
            for (int index = 0; index < Math.min(visibleRows, instances.size() - scrollOffset); index++) {
                HmclInstance instance = instances.get(index + scrollOffset);
                int rowY = rowTop + index * ROW_HEIGHT;
                graphics.fill(left, rowY - 2, right, rowY + 24, index % 2 == 0 ? 0x22000000 : 0x33000000);
                String text = instance.name() + "  ·  " + instance.version();
                graphics.text(font, font.plainSubstrByWidth(text, panelWidth - 130), left + 10, rowY + 6, 0xFFFFFFFF);
            }
        }

        String directory = InstanceManager.instancesDirectory(minecraft).toString();
        graphics.text(font, font.plainSubstrByWidth(directory, width - 24), 12, height - 48, 0xFFAAAAAA);
        if (!latestOutput.isBlank()) {
            graphics.text(font, font.plainSubstrByWidth(latestOutput, width - 24), 12, height - 62, 0xFFCCCCCC);
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(parent);
    }

    private void reloadInstances() {
        status = Component.translatable("screen.minecraftminecraftlauncher.loading");
        statusError = false;
        Thread.ofVirtual().name("mcmcl-hmcl-discovery").start(() -> {
            try {
                InstanceManager.DiscoveryResult result = InstanceManager.discover(minecraft);
                minecraft.execute(() -> {
                    instances = result.instances();
                    if (result.problems().isEmpty()) {
                        status = Component.translatable("screen.minecraftminecraftlauncher.found", instances.size());
                        statusError = false;
                    } else {
                        status = Component.translatable(
                                "screen.minecraftminecraftlauncher.found_with_errors",
                                instances.size(),
                                result.problems().size()
                        );
                        statusError = true;
                        latestOutput = result.problems().getFirst();
                    }
                    rebuildWidgets();
                });
            } catch (IOException | RuntimeException exception) {
                minecraft.execute(() -> {
                    instances = List.of();
                    status = Component.translatable("screen.minecraftminecraftlauncher.scan_failed");
                    statusError = true;
                    rebuildWidgets();
                });
                MinecraftMinecraftLauncher.LOGGER.warn("Could not discover HMCL instances", exception);
            }
        });
    }

    private void openDirectory() {
        try {
            InstanceManager.openDirectory(minecraft);
            status = Component.translatable("screen.minecraftminecraftlauncher.directory_opened");
            statusError = false;
        } catch (IOException | RuntimeException exception) {
            status = Component.translatable("screen.minecraftminecraftlauncher.directory_failed");
            statusError = true;
            MinecraftMinecraftLauncher.LOGGER.warn("Could not open MCMCL instances directory", exception);
        }
    }

    private void toggle(HmclInstance instance) {
        HmclHelperClient helper = InstanceManager.helper(minecraft);
        if (helper.isRunning(instance.id())) {
            stoppingInstances.add(instance.id());
            helper.stop(instance.id());
            status = Component.translatable("screen.minecraftminecraftlauncher.stopping", instance.name());
            statusError = false;
            return;
        }

        latestOutput = Component.translatable(
                "screen.minecraftminecraftlauncher.account", activeAccountName()).getString();
        stoppingInstances.remove(instance.id());
        status = Component.translatable("screen.minecraftminecraftlauncher.starting", instance.name());
        statusError = false;
        try {
            HmclHelperClient.LaunchHandle handle = helper.launch(
                    instance,
                    minecraft,
                    line -> minecraft.execute(() -> {
                        latestOutput = line;
                    })
            );
            handle.started().whenComplete((ignored, error) -> minecraft.execute(() -> {
                if (error == null
                        && helper.isRunning(instance.id())
                        && !stoppingInstances.contains(instance.id())) {
                    status = Component.translatable("screen.minecraftminecraftlauncher.running", instance.name());
                    statusError = false;
                }
            }));
            handle.exitCode().whenComplete((exitCode, error) -> minecraft.execute(() -> {
                stoppingInstances.remove(instance.id());
                if (error != null) {
                    status = Component.translatable("screen.minecraftminecraftlauncher.exit_error", instance.name());
                    statusError = true;
                    latestOutput = failureMessage(error);
                } else if (exitCode == 0) {
                    status = Component.translatable("screen.minecraftminecraftlauncher.exit_ok", instance.name());
                    statusError = false;
                } else {
                    status = Component.translatable(
                            "screen.minecraftminecraftlauncher.exit_code",
                            instance.name(),
                            exitCode
                    );
                    statusError = true;
                }
            }));
        } catch (IOException | RuntimeException exception) {
            stoppingInstances.remove(instance.id());
            status = Component.translatable("screen.minecraftminecraftlauncher.launch_failed");
            statusError = true;
            MinecraftMinecraftLauncher.LOGGER.warn("Could not launch MCMCL instance {}", instance.id(), exception);
        }
    }

    private String activeAccountName() {
        if (Config.OFFLINE_MODE.get()) {
            String offlineUsername = Config.OFFLINE_USERNAME.get();
            return offlineUsername.isBlank() ? minecraft.getUser().getName() : offlineUsername;
        }
        return minecraft.getUser().getName();
    }

    private void updateRowButtons() {
        HmclHelperClient helper = InstanceManager.helper(minecraft);
        HmclHelperClient.HelperInfo info = helper.helperInfo();
        for (RowWidgets row : rowWidgets) {
            boolean running = helper.isRunning(row.instance().id());
            row.button().setMessage(Component.translatable(
                    running
                            ? "screen.minecraftminecraftlauncher.stop"
                            : "screen.minecraftminecraftlauncher.launch"
            ));
            row.button().active = running || info == null || info.launchAvailable();
        }
        installButton.active = info == null || info.launchAvailable();
    }

    static String failureMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private record RowWidgets(HmclInstance instance, Button button) {
    }
}
