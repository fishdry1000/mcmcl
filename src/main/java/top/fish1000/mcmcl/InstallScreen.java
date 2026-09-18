package top.fish1000.mcmcl;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Step-based install flow styled like the vanilla world selection screen:
 * step one picks a game version, and when a mod loader is selected a second
 * step picks the loader version before the install starts.
 */
public final class InstallScreen extends Screen {
    /** Title + search box, mirroring the vanilla SelectWorldScreen header. */
    private static final int HEADER_HEIGHT = 8 + 9 + 8 + 20 + 4;
    /** Status line, helper log line and two rows of footer buttons. */
    private static final int FOOTER_HEIGHT = 78;
    /**
     * Loader ids offered by the cycle button, in cycle order; {@code null} means
     * plain vanilla.
     */
    private static final String[] LOADER_CYCLE = { null, "fabric", "forge", "neoforge", "quilt", "optifine" };
    private static final int SMALL_BUTTON_WIDTH = 71;
    /** Two 20px button rows plus their 4px spacing. */
    private static final int FOOTER_BUTTON_ROWS = 44;
    private static final int FOOTER_BOTTOM_PADDING = 4;
    /**
     * Cache key of the game version list; loader lists use
     * {@code loader/gameVersion}.
     */
    private static final String GAME_VERSIONS_CACHE_KEY = "";

    private final Screen parent;
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, HEADER_HEIGHT, FOOTER_HEIGHT);
    private Component headerTitle = Component.translatable("screen.minecraftminecraftlauncher.install.title");
    private List<HmclHelperClient.RemoteVersion> versions = List.of();
    private RemoteVersionSelectionList list;
    private EditBox searchBox;
    private RemoteVersionSelectionList.VersionTypeFilter typeFilter = RemoteVersionSelectionList.VersionTypeFilter.ALL;
    private Button primaryButton;
    private Button loaderButton;
    private Component status = Component.translatable("screen.minecraftminecraftlauncher.install.loading");
    private boolean statusError;
    private String latestLog = "";
    private boolean fetchInProgress;
    private boolean initialFetchStarted;
    private String selectedLoader;
    private String loaderGameVersion;

    public InstallScreen(Screen parent) {
        super(Component.translatable("screen.minecraftminecraftlauncher.install.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        // Screen.clearWidgets only clears the screen's widget lists; without
        // this the layout keeps the previous step's list and rows and they
        // stack on top of the new ones.
        this.layout.removeChildren();
        if (!initialFetchStarted) {
            initialFetchStarted = true;
            refreshVersions(false);
        }

        LinearLayout header = this.layout.addToHeader(LinearLayout.vertical().spacing(4));
        header.defaultCellSetting().alignHorizontallyCenter();
        header.addChild(new StringWidget(this.headerTitle, this.font));
        LinearLayout subHeader = header.addChild(LinearLayout.horizontal().spacing(4));
        this.searchBox = subHeader.addChild(
                new EditBox(
                        this.font,
                        this.width / 2 - 100,
                        22,
                        200,
                        20,
                        this.searchBox,
                        Component.translatable("screen.minecraftminecraftlauncher.search")));
        this.searchBox.setResponder(value -> {
            if (this.list != null) {
                this.list.updateFilter(value);
            }
        });
        this.searchBox.setHint(
                Component.translatable("screen.minecraftminecraftlauncher.install.search")
                        .setStyle(EditBox.SEARCH_HINT_STYLE));
        if (!this.inLoaderMode()) {
            // The type filter only makes sense for the game version list;
            // loader version rows share one release type.
            subHeader.addChild(
                    CycleButton.builder(RemoteVersionSelectionList.VersionTypeFilter::label, this.typeFilter)
                            .withValues(RemoteVersionSelectionList.VersionTypeFilter.values())
                            .displayOnlyValue()
                            .create(
                                    0,
                                    0,
                                    100,
                                    20,
                                    Component.translatable("screen.minecraftminecraftlauncher.filter"),
                                    (button, value) -> {
                                        this.typeFilter = value;
                                        if (this.list != null) {
                                            this.list.setTypeFilter(value);
                                        }
                                    }));
        }

        this.list = this.layout.addToContents(
                new RemoteVersionSelectionList(
                        this.minecraft,
                        this,
                        this.width,
                        this.layout.getContentHeight(),
                        this.inLoaderMode()
                                ? version -> RemoteVersionSelectionList.loaderVersionIcon(this.selectedLoader)
                                : RemoteVersionSelectionList::gameVersionIcon,
                        loadingLabel(),
                        Component.translatable("screen.minecraftminecraftlauncher.install.empty"),
                        ignored -> this.updateFooterButtons(),
                        version -> this.primaryAction(),
                        this.list));
        this.createFooterButtons();
        this.layout.visitWidgets(this::addRenderableWidget);
        this.repositionElements();
        // The type filter belongs to the game version list only; applying it
        // to loader versions would hide every row behind step one's choice.
        this.list.setTypeFilter(
                this.inLoaderMode() ? RemoteVersionSelectionList.VersionTypeFilter.ALL : this.typeFilter);
        this.list.setVersions(this.versions, this.fetchInProgress && this.versions.isEmpty());
        this.updateFooterButtons();
    }

    private Component loadingLabel() {
        return Component.translatable(this.inLoaderMode()
                ? "screen.minecraftminecraftlauncher.install.loading_loader"
                : "screen.minecraftminecraftlauncher.install.loading");
    }

    private void createFooterButtons() {
        // Everything lives in the layout so a window resize (and the step
        // switch's rebuildWidgets) repositions the rows cleanly. The back
        // action stays in the same slot on every screen: last button of the
        // small row, like the vanilla world screen.
        LinearLayout footerColumn = this.layout.addToFooter(
                LinearLayout.vertical().spacing(4),
                settings -> settings.align(0.5F, 1.0F).paddingBottom(FOOTER_BOTTOM_PADDING));
        LinearLayout primaryRow = footerColumn.addChild(
                LinearLayout.horizontal().spacing(8),
                settings -> settings.alignHorizontallyCenter());
        this.primaryButton = primaryRow.addChild(Button.builder(
                Component.empty(),
                button -> this.primaryAction()).build());

        LinearLayout secondaryRow = footerColumn.addChild(
                LinearLayout.horizontal().spacing(8),
                settings -> settings.alignHorizontallyCenter());
        if (this.inLoaderMode()) {
            secondaryRow.addChild(this.refreshButton());
        } else {
            this.loaderButton = secondaryRow.addChild(Button.builder(
                    Component.empty(),
                    button -> this.cycleLoader()).width(100).build());
            secondaryRow.addChild(this.refreshButton());
        }
        secondaryRow.addChild(Button.builder(
                Component.translatable("screen.minecraftminecraftlauncher.install.back"),
                button -> this.back()).width(SMALL_BUTTON_WIDTH).build());
    }

    private Button refreshButton() {
        return Button.builder(
                Component.translatable("screen.minecraftminecraftlauncher.refresh"),
                button -> this.refreshCurrentVersions()).width(SMALL_BUTTON_WIDTH).build();
    }

    @Override
    protected void repositionElements() {
        if (this.list != null) {
            this.list.updateSize(this.width, this.layout);
        }
        this.layout.arrangeElements();
    }

    @Override
    protected void setInitialFocus() {
        if (this.searchBox != null) {
            this.setInitialFocus(this.searchBox);
        }
    }

    @Override
    public void tick() {
        this.updateFooterButtons();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        // Center the status/log block between the list's footer separator and
        // the first button row instead of hugging the separator.
        int bandTop = this.height - FOOTER_HEIGHT + 2;
        int band = FOOTER_HEIGHT - 2 - FOOTER_BUTTON_ROWS - FOOTER_BOTTOM_PADDING;
        boolean hasLog = !this.latestLog.isBlank();
        int blockHeight = hasLog ? 19 : 9;
        int statusY = bandTop + (band - blockHeight) / 2;
        graphics.centeredText(this.font, this.status, this.width / 2, statusY,
                this.statusError ? 0xFFFF7777 : 0xFFB8E0FF);
        if (hasLog) {
            graphics.centeredText(
                    this.font,
                    this.font.plainSubstrByWidth(this.latestLog, this.width - 24),
                    this.width / 2,
                    statusY + 10,
                    0xFFCCCCCC);
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreenAndShow(this.parent);
    }

    /**
     * Step one's back button leaves the screen; step two's goes back to the game
     * version list.
     */
    private void back() {
        if (this.inLoaderMode()) {
            this.leaveLoaderMode();
        } else {
            this.onClose();
        }
    }

    private boolean inLoaderMode() {
        return this.loaderGameVersion != null;
    }

    private void primaryAction() {
        HmclHelperClient.RemoteVersion selected = this.list.getSelectedVersion();
        if (selected == null) {
            return;
        }
        HmclHelperClient helper = InstanceManager.helper(this.minecraft);
        String instanceId = this.rowInstanceId(selected);
        if (helper.isInstalling(instanceId)) {
            helper.stop(instanceId);
            this.status = Component.translatable(
                    "screen.minecraftminecraftlauncher.install.stopping", selected.id());
            this.statusError = false;
            this.updateFooterButtons();
            return;
        }

        if (!this.inLoaderMode() && this.selectedLoader != null) {
            this.enterLoaderMode(selected);
            return;
        }

        if (this.inLoaderMode()) {
            this.startInstall(
                    instanceId,
                    this.loaderGameVersion,
                    List.of(new HmclHelperClient.LoaderSpec(this.selectedLoader, selected.id())),
                    selected.id());
        } else {
            this.startInstall(instanceId, selected.id(), List.of(), selected.id());
        }
    }

    private void startInstall(
            String instanceId,
            String gameVersion,
            List<HmclHelperClient.LoaderSpec> loaders,
            String displayName) {
        HmclHelperClient helper = InstanceManager.helper(this.minecraft);
        this.latestLog = "";
        this.status = Component.translatable(
                "screen.minecraftminecraftlauncher.install.install_started", displayName);
        this.statusError = false;
        Consumer<String> logSink = line -> this.minecraft.execute(() -> {
            this.latestLog = line;
        });
        Thread.ofVirtual().name("mcmcl-hmcl-install-start").start(() -> {
            try {
                // The helper refuses to install over an existing instance;
                // check up front so the player sees a clear message instead
                // of a raw failure after the fact.
                boolean exists = helper.listInstances().get(35, TimeUnit.SECONDS).stream()
                        .anyMatch(instance -> instance.id().equals(instanceId));
                if (exists) {
                    this.minecraft.execute(() -> {
                        this.status = Component.translatable(
                                "screen.minecraftminecraftlauncher.install.exists", displayName);
                        this.statusError = true;
                        this.updateFooterButtons();
                    });
                    return;
                }
                HmclHelperClient.InstallHandle handle = loaders.isEmpty()
                        ? helper.install(instanceId, gameVersion, logSink)
                        : helper.install(instanceId, gameVersion, loaders, logSink);
                handle.completion().whenComplete((exitCode, error) -> this.minecraft.execute(() -> {
                    if (error != null) {
                        this.status = Component.translatable(
                                "screen.minecraftminecraftlauncher.install.install_failed", displayName);
                        this.statusError = true;
                        this.latestLog = LauncherScreen.failureMessage(error);
                    } else if (exitCode == 0) {
                        this.status = Component.translatable(
                                "screen.minecraftminecraftlauncher.install.install_done", displayName);
                        this.statusError = false;
                    } else {
                        this.status = Component.translatable(
                                "screen.minecraftminecraftlauncher.install.install_exit_code",
                                displayName,
                                exitCode);
                        this.statusError = true;
                    }
                    this.updateFooterButtons();
                }));
            } catch (Exception exception) {
                this.minecraft.execute(() -> {
                    this.status = Component.translatable(
                            "screen.minecraftminecraftlauncher.install.install_failed", displayName);
                    this.statusError = true;
                    this.latestLog = LauncherScreen.failureMessage(exception);
                    this.updateFooterButtons();
                });
                MinecraftMinecraftLauncher.LOGGER.warn("Could not install MCMCL instance {}", instanceId, exception);
            }
        });
        this.updateFooterButtons();
    }

    private void refreshCurrentVersions() {
        if (this.inLoaderMode()) {
            this.fetchLoaderVersions(true);
        } else {
            this.refreshVersions(true);
        }
    }

    /**
     * Shows the game version list, serving the disk cache when it is usable
     * and refreshing it in the background otherwise (or when {@code force}).
     */
    private void refreshVersions(boolean force) {
        HmclHelperClient helper = InstanceManager.helper(this.minecraft);
        if (this.rejectUnavailable(helper)) {
            return;
        }

        RemoteVersionCache.Entry cached = RemoteVersionCache.read(
                InstanceManager.instancesDirectory(this.minecraft),
                Config.DOWNLOAD_PROVIDER.get(),
                GAME_VERSIONS_CACHE_KEY);
        boolean showingCache = cached != null;
        if (showingCache) {
            this.versions = cached.versions();
            this.status = Component.translatable(
                    "screen.minecraftminecraftlauncher.install.found", this.versions.size());
            this.statusError = false;
        } else {
            this.versions = List.of();
            this.status = Component.translatable("screen.minecraftminecraftlauncher.install.loading");
            this.statusError = false;
        }
        this.fetchInProgress = !showingCache;
        if (this.list != null) {
            this.list.setVersions(this.versions, this.fetchInProgress);
        }
        if (showingCache && !force && cached.isFresh()) {
            this.updateFooterButtons();
            return;
        }

        Thread.ofVirtual().name("mcmcl-hmcl-remote-versions").start(() -> {
            try {
                List<HmclHelperClient.RemoteVersion> fetched = helper
                        .remoteVersions()
                        .get(35, TimeUnit.SECONDS);
                this.minecraft.execute(() -> {
                    if (this.inLoaderMode()) {
                        return; // The game-version list is no longer shown.
                    }
                    this.fetchInProgress = false;
                    if (this.rejectUnavailable(helper)) {
                        this.versions = List.of();
                    } else {
                        this.versions = fetched;
                        RemoteVersionCache.write(
                                InstanceManager.instancesDirectory(this.minecraft),
                                Config.DOWNLOAD_PROVIDER.get(),
                                GAME_VERSIONS_CACHE_KEY,
                                fetched);
                        this.status = Component.translatable(
                                "screen.minecraftminecraftlauncher.install.found", this.versions.size());
                        this.statusError = false;
                    }
                    if (this.list != null) {
                        this.list.setVersions(this.versions, false);
                    }
                    this.updateFooterButtons();
                });
            } catch (Exception exception) {
                this.minecraft.execute(() -> {
                    if (this.inLoaderMode()) {
                        return; // The game-version list is no longer shown.
                    }
                    this.fetchInProgress = false;
                    if (!this.rejectUnavailable(helper)) {
                        if (showingCache) {
                            this.status = Component.translatable(
                                    "screen.minecraftminecraftlauncher.install.refresh_failed_cached");
                            this.statusError = true;
                        } else {
                            this.versions = List.of();
                            this.status = Component.translatable(
                                    "screen.minecraftminecraftlauncher.install.failed");
                            this.statusError = true;
                            if (this.list != null) {
                                this.list.setVersions(this.versions, false);
                            }
                        }
                    }
                    this.updateFooterButtons();
                });
                MinecraftMinecraftLauncher.LOGGER.warn("Could not fetch remote versions", exception);
            }
        });
    }

    /**
     * Loader version variant of {@link #refreshVersions(boolean)}; cached per
     * loader and game version.
     */
    private void fetchLoaderVersions(boolean force) {
        HmclHelperClient helper = InstanceManager.helper(this.minecraft);
        if (this.rejectUnavailable(helper)) {
            return;
        }

        String loader = this.selectedLoader;
        String gameVersion = this.loaderGameVersion;
        RemoteVersionCache.Entry cached = RemoteVersionCache.read(
                InstanceManager.instancesDirectory(this.minecraft),
                Config.DOWNLOAD_PROVIDER.get(),
                loader + "/" + gameVersion);
        boolean showingCache = cached != null;
        if (showingCache) {
            this.versions = cached.versions();
            this.status = Component.translatable(
                    "screen.minecraftminecraftlauncher.install.found", this.versions.size());
            this.statusError = false;
        } else {
            this.versions = List.of();
            this.status = Component.translatable("screen.minecraftminecraftlauncher.install.loading_loader");
            this.statusError = false;
        }
        this.fetchInProgress = !showingCache;
        if (this.list != null) {
            this.list.setVersions(this.versions, this.fetchInProgress);
        }
        if (showingCache && !force && cached.isFresh()) {
            this.updateFooterButtons();
            return;
        }

        Thread.ofVirtual().name("mcmcl-hmcl-loader-versions").start(() -> {
            try {
                List<HmclHelperClient.RemoteVersion> fetched = helper
                        .remoteVersions(loader, gameVersion)
                        .get(35, TimeUnit.SECONDS);
                this.minecraft.execute(() -> {
                    if (!this.isCurrentLoaderFetch(loader, gameVersion)) {
                        return; // The user moved to another loader or left loader mode.
                    }
                    this.fetchInProgress = false;
                    if (this.rejectUnavailable(helper)) {
                        this.versions = List.of();
                    } else {
                        this.versions = fetched;
                        RemoteVersionCache.write(
                                InstanceManager.instancesDirectory(this.minecraft),
                                Config.DOWNLOAD_PROVIDER.get(),
                                loader + "/" + gameVersion,
                                fetched);
                        this.status = Component.translatable(
                                "screen.minecraftminecraftlauncher.install.found", this.versions.size());
                        this.statusError = false;
                    }
                    if (this.list != null) {
                        this.list.setVersions(this.versions, false);
                    }
                    this.updateFooterButtons();
                });
            } catch (Exception exception) {
                this.minecraft.execute(() -> {
                    if (!this.isCurrentLoaderFetch(loader, gameVersion)) {
                        return; // The user moved to another loader or left loader mode.
                    }
                    this.fetchInProgress = false;
                    if (!this.rejectUnavailable(helper)) {
                        if (showingCache) {
                            this.status = Component.translatable(
                                    "screen.minecraftminecraftlauncher.install.refresh_failed_cached");
                            this.statusError = true;
                        } else {
                            this.versions = List.of();
                            this.status = Component.translatable(
                                    "screen.minecraftminecraftlauncher.install.failed");
                            this.statusError = true;
                            if (this.list != null) {
                                this.list.setVersions(this.versions, false);
                            }
                        }
                    }
                    this.updateFooterButtons();
                });
                MinecraftMinecraftLauncher.LOGGER.warn("Could not fetch loader versions", exception);
            }
        });
    }

    /**
     * Marks the screen unavailable (and returns true) when the helper cannot
     * install.
     */
    private boolean rejectUnavailable(HmclHelperClient helper) {
        HmclHelperClient.HelperInfo info = helper.helperInfo();
        if (info == null || !info.launchAvailable()) {
            this.versions = List.of();
            this.fetchInProgress = false;
            this.status = Component.translatable("screen.minecraftminecraftlauncher.install.unavailable");
            this.statusError = true;
            if (this.list != null) {
                this.list.setVersions(this.versions, false);
            }
            this.updateFooterButtons();
            return true;
        }
        return false;
    }

    private boolean isCurrentLoaderFetch(String loader, String gameVersion) {
        return this.inLoaderMode()
                && Objects.equals(loader, this.selectedLoader)
                && Objects.equals(gameVersion, this.loaderGameVersion);
    }

    private void enterLoaderMode(HmclHelperClient.RemoteVersion version) {
        this.loaderGameVersion = version.id();
        this.headerTitle = Component.translatable(
                "screen.minecraftminecraftlauncher.install.loader_for",
                version.id() + " · " + loaderLabel(this.selectedLoader).getString());
        this.versions = List.of();
        this.fetchLoaderVersions(false);
        this.rebuildWidgets();
    }

    private void leaveLoaderMode() {
        this.loaderGameVersion = null;
        this.headerTitle = Component.translatable("screen.minecraftminecraftlauncher.install.title");
        this.versions = List.of();
        this.refreshVersions(false);
        this.rebuildWidgets();
    }

    private void cycleLoader() {
        int next = 0;
        for (int index = 0; index < LOADER_CYCLE.length; index++) {
            if (Objects.equals(LOADER_CYCLE[index], this.selectedLoader)) {
                next = (index + 1) % LOADER_CYCLE.length;
                break;
            }
        }
        this.selectedLoader = LOADER_CYCLE[next];
        this.updateFooterButtons();
    }

    private String rowInstanceId(HmclHelperClient.RemoteVersion version) {
        return this.inLoaderMode() ? this.loaderInstanceId() : version.id();
    }

    /**
     * Loader installs live in a dedicated instance named after the game version and
     * loader.
     */
    private String loaderInstanceId() {
        return this.loaderGameVersion + "-" + this.selectedLoader;
    }

    /**
     * Mirrors the vanilla world screen: the primary button reflects the selection's
     * state.
     */
    private void updateFooterButtons() {
        HmclHelperClient helper = InstanceManager.helper(this.minecraft);
        HmclHelperClient.HelperInfo info = helper.helperInfo();
        boolean installAvailable = info != null && info.launchAvailable();
        HmclHelperClient.RemoteVersion selected = this.list == null ? null : this.list.getSelectedVersion();
        boolean installing = selected != null && helper.isInstalling(this.rowInstanceId(selected));
        if (this.primaryButton != null) {
            this.primaryButton.setMessage(Component.translatable(installing
                    ? "screen.minecraftminecraftlauncher.install.cancel"
                    : this.inLoaderMode()
                            ? "screen.minecraftminecraftlauncher.install.install"
                            : "screen.minecraftminecraftlauncher.install.next"));
            this.primaryButton.active = installing || (selected != null && installAvailable);
        }
        if (this.loaderButton != null) {
            this.loaderButton.setMessage(Component.translatable(
                    "screen.minecraftminecraftlauncher.install.loader_prefix",
                    loaderLabel(this.selectedLoader)));
        }
    }

    private static Component loaderLabel(String loader) {
        if (loader == null) {
            return Component.translatable("screen.minecraftminecraftlauncher.install.loader.none");
        }
        return Component.translatableWithFallback(
                "screen.minecraftminecraftlauncher.install.loader." + loader, loader);
    }
}
