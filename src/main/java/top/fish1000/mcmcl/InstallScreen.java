package top.fish1000.mcmcl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Fetches remote versions from the helper and installs them into new instances. */
public final class InstallScreen extends Screen {
    private static final int PANEL_WIDTH = 560;
    private static final int ROW_HEIGHT = 32;

    private final Screen parent;
    private List<HmclHelperClient.RemoteVersion> versions = List.of();
    private final List<RowWidgets> rowWidgets = new ArrayList<>();
    private int scrollOffset;
    private Component status = Component.translatable("screen.minecraftminecraftlauncher.install.loading");
    private boolean statusError;
    private String latestLog = "";
    private boolean initialFetchStarted;

    public InstallScreen(Screen parent) {
        super(Component.translatable("screen.minecraftminecraftlauncher.install.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        rowWidgets.clear();
        if (!initialFetchStarted) {
            initialFetchStarted = true;
            refreshVersions();
        }

        int panelWidth = Math.min(PANEL_WIDTH, Math.max(300, width - 32));
        int left = (width - panelWidth) / 2;
        int right = left + panelWidth;
        int bottom = height - 42;
        int rowTop = 70;
        int visibleRows = Math.max(1, (bottom - rowTop) / ROW_HEIGHT);
        scrollOffset = Math.min(scrollOffset, Math.max(0, versions.size() - visibleRows));

        for (int index = 0; index < Math.min(visibleRows, versions.size() - scrollOffset); index++) {
            HmclHelperClient.RemoteVersion version = versions.get(index + scrollOffset);
            Button button = Button.builder(Component.empty(), ignored -> toggle(version))
                    .bounds(right - 108, rowTop + index * ROW_HEIGHT, 100, 20)
                    .build();
            rowWidgets.add(new RowWidgets(version, button));
            addRenderableWidget(button);
        }

        addRenderableWidget(Button.builder(
                        Component.translatable("screen.minecraftminecraftlauncher.install.refresh"),
                        ignored -> refreshVersions())
                .bounds(left, height - 32, 100, 20)
                .build());
        addRenderableWidget(Button.builder(
                        Component.translatable("gui.done"),
                        ignored -> onClose())
                .bounds(left + panelWidth - 100, height - 32, 100, 20)
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
        int maxOffset = Math.max(0, versions.size() - visibleRows);
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

        if (versions.isEmpty()) {
            graphics.centeredText(
                    font,
                    Component.translatable("screen.minecraftminecraftlauncher.install.empty"),
                    width / 2,
                    86,
                    0xFFD0D0D0
            );
        } else {
            for (int index = 0; index < Math.min(visibleRows, versions.size() - scrollOffset); index++) {
                HmclHelperClient.RemoteVersion version = versions.get(index + scrollOffset);
                int rowY = rowTop + index * ROW_HEIGHT;
                graphics.fill(left, rowY - 2, right, rowY + 24, index % 2 == 0 ? 0x22000000 : 0x33000000);
                String text = version.id() + "  ·  " + versionType(version).getString();
                graphics.text(font, font.plainSubstrByWidth(text, panelWidth - 130), left + 10, rowY + 6, 0xFFFFFFFF);
            }
        }

        if (!latestLog.isBlank()) {
            graphics.text(font, font.plainSubstrByWidth(latestLog, width - 24), 12, height - 62, 0xFFCCCCCC);
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(parent);
    }

    private void refreshVersions() {
        HmclHelperClient helper = InstanceManager.helper(minecraft);
        HmclHelperClient.HelperInfo info = helper.helperInfo();
        if (info != null && !info.launchAvailable()) {
            versions = List.of();
            status = Component.translatable("screen.minecraftminecraftlauncher.install.unavailable");
            statusError = true;
            rebuildWidgets();
            return;
        }

        status = Component.translatable("screen.minecraftminecraftlauncher.install.loading");
        statusError = false;
        Thread.ofVirtual().name("mcmcl-hmcl-remote-versions").start(() -> {
            try {
                List<HmclHelperClient.RemoteVersion> fetched = helper
                        .remoteVersions()
                        .get(35, TimeUnit.SECONDS);
                minecraft.execute(() -> {
                    HmclHelperClient.HelperInfo knownInfo = helper.helperInfo();
                    if (knownInfo != null && !knownInfo.launchAvailable()) {
                        versions = List.of();
                        status = Component.translatable("screen.minecraftminecraftlauncher.install.unavailable");
                        statusError = true;
                    } else {
                        versions = fetched;
                        status = Component.translatable(
                                "screen.minecraftminecraftlauncher.install.found", versions.size());
                        statusError = false;
                    }
                    rebuildWidgets();
                });
            } catch (Exception exception) {
                minecraft.execute(() -> {
                    HmclHelperClient.HelperInfo knownInfo = helper.helperInfo();
                    if (knownInfo != null && !knownInfo.launchAvailable()) {
                        status = Component.translatable("screen.minecraftminecraftlauncher.install.unavailable");
                    } else {
                        status = Component.translatable("screen.minecraftminecraftlauncher.install.failed");
                    }
                    versions = List.of();
                    statusError = true;
                    rebuildWidgets();
                });
                MinecraftMinecraftLauncher.LOGGER.warn("Could not fetch remote versions", exception);
            }
        });
    }

    private void toggle(HmclHelperClient.RemoteVersion version) {
        HmclHelperClient helper = InstanceManager.helper(minecraft);
        if (helper.isInstalling(version.id())) {
            helper.stop(version.id());
            status = Component.translatable("screen.minecraftminecraftlauncher.install.stopping", version.id());
            statusError = false;
            return;
        }

        latestLog = "";
        status = Component.translatable("screen.minecraftminecraftlauncher.install.install_started", version.id());
        statusError = false;
        try {
            HmclHelperClient.InstallHandle handle = helper.install(
                    version.id(),
                    version.id(),
                    line -> minecraft.execute(() -> {
                        latestLog = line;
                    })
            );
            handle.completion().whenComplete((exitCode, error) -> minecraft.execute(() -> {
                if (error != null) {
                    status = Component.translatable(
                            "screen.minecraftminecraftlauncher.install.install_failed", version.id());
                    statusError = true;
                    latestLog = LauncherScreen.failureMessage(error);
                } else if (exitCode == 0) {
                    status = Component.translatable(
                            "screen.minecraftminecraftlauncher.install.install_done", version.id());
                    statusError = false;
                } else {
                    status = Component.translatable(
                            "screen.minecraftminecraftlauncher.install.install_exit_code",
                            version.id(),
                            exitCode
                    );
                    statusError = true;
                }
            }));
        } catch (IOException | RuntimeException exception) {
            status = Component.translatable(
                    "screen.minecraftminecraftlauncher.install.install_failed", version.id());
            statusError = true;
            latestLog = LauncherScreen.failureMessage(exception);
            MinecraftMinecraftLauncher.LOGGER.warn("Could not install MCMCL instance {}", version.id(), exception);
        }
    }

    private void updateRowButtons() {
        HmclHelperClient helper = InstanceManager.helper(minecraft);
        HmclHelperClient.HelperInfo info = helper.helperInfo();
        for (RowWidgets row : rowWidgets) {
            boolean installing = helper.isInstalling(row.version().id());
            row.button().setMessage(Component.translatable(
                    installing
                            ? "screen.minecraftminecraftlauncher.install.cancel"
                            : "screen.minecraftminecraftlauncher.install.install"
            ));
            row.button().active = info != null && info.launchAvailable();
        }
    }

    private static Component versionType(HmclHelperClient.RemoteVersion version) {
        String type = version.type();
        if (type.isBlank()) {
            return Component.empty();
        }
        return Component.translatableWithFallback(
                "screen.minecraftminecraftlauncher.install.type." + type, type);
    }

    private record RowWidgets(HmclHelperClient.RemoteVersion version, Button button) {
    }
}
