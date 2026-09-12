package top.fish1000.mcmcl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Fetches remote versions from the helper and installs them into new instances. */
public final class InstallScreen extends Screen {
    private static final int PANEL_WIDTH = 560;
    private static final int ROW_HEIGHT = 32;
    /** Loader ids offered by the cycle button, in cycle order; {@code null} means plain vanilla. */
    private static final String[] LOADER_CYCLE = {null, "fabric", "forge", "neoforge", "quilt", "optifine"};

    private final Screen parent;
    private List<HmclHelperClient.RemoteVersion> versions = List.of();
    private final List<RowWidgets> rowWidgets = new ArrayList<>();
    private int scrollOffset;
    private Component status = Component.translatable("screen.minecraftminecraftlauncher.install.loading");
    private boolean statusError;
    private String latestLog = "";
    private boolean initialFetchStarted;
    private String selectedLoader;
    private String loaderGameVersion;
    private Button loaderButton;

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

        boolean loaderMode = inLoaderMode();
        ButtonRowLayout.Geometry bottomRow = loaderMode
                ? ButtonRowLayout.centered(left, panelWidth, 8, 100, 100, 100, 100)
                : ButtonRowLayout.centered(left, panelWidth, 8, 100, 100, 100);
        int slot = 0;
        if (loaderMode) {
            addRenderableWidget(Button.builder(
                            Component.translatable("screen.minecraftminecraftlauncher.install.back"),
                            ignored -> leaveLoaderMode())
                    .bounds(bottomRow.x()[slot], height - 32, bottomRow.width()[slot], 20)
                    .build());
            slot++;
        }
        addRenderableWidget(Button.builder(
                        Component.translatable("screen.minecraftminecraftlauncher.install.refresh"),
                        ignored -> refreshCurrentVersions())
                .bounds(bottomRow.x()[slot], height - 32, bottomRow.width()[slot], 20)
                .build());
        slot++;
        loaderButton = Button.builder(
                        Component.translatable(
                                "screen.minecraftminecraftlauncher.install.loader_prefix",
                                loaderLabel(selectedLoader)
                        ),
                        ignored -> cycleLoader())
                .bounds(bottomRow.x()[slot], height - 32, bottomRow.width()[slot], 20)
                .build();
        addRenderableWidget(loaderButton);
        slot++;
        addRenderableWidget(Button.builder(
                        Component.translatable("gui.done"),
                        ignored -> onClose())
                .bounds(bottomRow.x()[slot], height - 32, bottomRow.width()[slot], 20)
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
        if (inLoaderMode()) {
            graphics.centeredText(
                    font,
                    Component.translatable(
                            "screen.minecraftminecraftlauncher.install.loader_for",
                            loaderGameVersion
                    ),
                    width / 2,
                    32,
                    0xFFFFFFFF
            );
        }
        graphics.centeredText(
                font,
                status,
                width / 2,
                inLoaderMode() ? 44 : 40,
                statusError ? 0xFFFF7777 : 0xFFB8E0FF
        );
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

    private boolean inLoaderMode() {
        return loaderGameVersion != null;
    }

    private void refreshCurrentVersions() {
        if (inLoaderMode()) {
            fetchLoaderVersions();
        } else {
            refreshVersions();
        }
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
                    if (inLoaderMode()) {
                        return; // The game-version list is no longer shown.
                    }
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
                    if (inLoaderMode()) {
                        return; // The game-version list is no longer shown.
                    }
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

    private void fetchLoaderVersions() {
        HmclHelperClient helper = InstanceManager.helper(minecraft);
        HmclHelperClient.HelperInfo info = helper.helperInfo();
        if (info != null && !info.launchAvailable()) {
            versions = List.of();
            status = Component.translatable("screen.minecraftminecraftlauncher.install.unavailable");
            statusError = true;
            rebuildWidgets();
            return;
        }

        status = Component.translatable("screen.minecraftminecraftlauncher.install.loading_loader");
        statusError = false;
        String loader = selectedLoader;
        String gameVersion = loaderGameVersion;
        Thread.ofVirtual().name("mcmcl-hmcl-loader-versions").start(() -> {
            try {
                List<HmclHelperClient.RemoteVersion> fetched = helper
                        .remoteVersions(loader, gameVersion)
                        .get(35, TimeUnit.SECONDS);
                minecraft.execute(() -> {
                    if (!isCurrentLoaderFetch(loader, gameVersion)) {
                        return; // The user moved to another loader or left loader mode.
                    }
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
                    if (!isCurrentLoaderFetch(loader, gameVersion)) {
                        return; // The user moved to another loader or left loader mode.
                    }
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
                MinecraftMinecraftLauncher.LOGGER.warn("Could not fetch loader versions", exception);
            }
        });
    }

    private boolean isCurrentLoaderFetch(String loader, String gameVersion) {
        return inLoaderMode()
                && Objects.equals(loader, selectedLoader)
                && Objects.equals(gameVersion, loaderGameVersion);
    }

    private void enterLoaderMode(HmclHelperClient.RemoteVersion version) {
        loaderGameVersion = version.id();
        versions = List.of();
        scrollOffset = 0;
        fetchLoaderVersions();
    }

    private void leaveLoaderMode() {
        loaderGameVersion = null;
        versions = List.of();
        scrollOffset = 0;
        refreshVersions();
    }

    private void cycleLoader() {
        int next = 0;
        for (int index = 0; index < LOADER_CYCLE.length; index++) {
            if (Objects.equals(LOADER_CYCLE[index], selectedLoader)) {
                next = (index + 1) % LOADER_CYCLE.length;
                break;
            }
        }
        selectedLoader = LOADER_CYCLE[next];
        if (inLoaderMode()) {
            if (selectedLoader == null) {
                leaveLoaderMode();
            } else {
                versions = List.of();
                scrollOffset = 0;
                fetchLoaderVersions();
            }
        }
    }

    private void toggle(HmclHelperClient.RemoteVersion version) {
        HmclHelperClient helper = InstanceManager.helper(minecraft);
        String instanceId = rowInstanceId(version);
        if (helper.isInstalling(instanceId)) {
            helper.stop(instanceId);
            status = Component.translatable("screen.minecraftminecraftlauncher.install.stopping", version.id());
            statusError = false;
            return;
        }

        if (!inLoaderMode() && selectedLoader != null) {
            enterLoaderMode(version);
            return;
        }

        latestLog = "";
        status = Component.translatable("screen.minecraftminecraftlauncher.install.install_started", version.id());
        statusError = false;
        Consumer<String> logSink = line -> minecraft.execute(() -> {
            latestLog = line;
        });
        try {
            HmclHelperClient.InstallHandle handle = inLoaderMode()
                    ? helper.install(
                            loaderInstanceId(),
                            loaderGameVersion,
                            List.of(new HmclHelperClient.LoaderSpec(selectedLoader, version.id())),
                            logSink)
                    : helper.install(version.id(), version.id(), logSink);
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
            MinecraftMinecraftLauncher.LOGGER.warn("Could not install MCMCL instance {}", instanceId, exception);
        }
    }

    private String rowInstanceId(HmclHelperClient.RemoteVersion version) {
        return inLoaderMode() ? loaderInstanceId() : version.id();
    }

    /** Loader installs live in a dedicated instance named after the game version and loader. */
    private String loaderInstanceId() {
        return loaderGameVersion + "-" + selectedLoader;
    }

    private void updateRowButtons() {
        HmclHelperClient helper = InstanceManager.helper(minecraft);
        HmclHelperClient.HelperInfo info = helper.helperInfo();
        for (RowWidgets row : rowWidgets) {
            boolean installing = helper.isInstalling(rowInstanceId(row.version()));
            row.button().setMessage(Component.translatable(
                    installing
                            ? "screen.minecraftminecraftlauncher.install.cancel"
                            : "screen.minecraftminecraftlauncher.install.install"
            ));
            row.button().active = info != null && info.launchAvailable();
        }
        loaderButton.setMessage(Component.translatable(
                "screen.minecraftminecraftlauncher.install.loader_prefix",
                loaderLabel(selectedLoader)
        ));
    }

    private static Component loaderLabel(String loader) {
        if (loader == null) {
            return Component.translatable("screen.minecraftminecraftlauncher.install.loader.none");
        }
        return Component.translatableWithFallback(
                "screen.minecraftminecraftlauncher.install.loader." + loader, loader);
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
