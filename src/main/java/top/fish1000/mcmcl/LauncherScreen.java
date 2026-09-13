package top.fish1000.mcmcl;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Instance picker laid out like the vanilla world selection screen: a
 * searchable instance list between header and footer separators, with the
 * primary launch action and secondary actions grouped in the footer.
 * The launch protocol lives outside the UI.
 */
public final class LauncherScreen extends Screen {
    /** Title + search box, mirroring the vanilla SelectWorldScreen header. */
    private static final int HEADER_HEIGHT = 8 + 9 + 8 + 20 + 4;
    /** Status line, helper log line and two rows of footer buttons. */
    private static final int FOOTER_HEIGHT = 78;
    /**
     * Both footer rows are 308px wide like the vanilla world screen: the
     * primary row is two default buttons, the secondary row five narrow
     * ones (5 × 56 + 4 × 7 spacing).
     */
    private static final int SMALL_BUTTON_WIDTH = 56;
    private static final int SMALL_BUTTON_SPACING = 7;
    /** Two 20px button rows plus their 4px spacing. */
    private static final int FOOTER_BUTTON_ROWS = 44;
    private static final int FOOTER_BOTTOM_PADDING = 4;

    private final Screen parent;
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, HEADER_HEIGHT, FOOTER_HEIGHT);
    private List<HmclInstance> instances = List.of();
    private InstanceSelectionList list;
    private EditBox searchBox;
    private InstanceSelectionList.LoaderFilter loaderFilter = InstanceSelectionList.LoaderFilter.ALL;
    private Button launchButton;
    private Button installButton;
    private Button modsButton;
    private Button editButton;
    private final Set<String> stoppingInstances = new HashSet<>();
    private Component status = Component.translatable("screen.minecraftminecraftlauncher.loading");
    private boolean statusError;
    private String latestOutput = "";
    private boolean discoveryInProgress;
    private boolean initialDiscoveryStarted;

    public LauncherScreen(Screen parent) {
        super(Component.translatable("screen.minecraftminecraftlauncher.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        // Screen.clearWidgets only clears the screen's widget lists; without
        // this the layout keeps the previous init's list and rows and they
        // stack on top of the new ones.
        this.layout.removeChildren();
        if (!initialDiscoveryStarted) {
            initialDiscoveryStarted = true;
            reloadInstances();
        }

        LinearLayout header = this.layout.addToHeader(LinearLayout.vertical().spacing(4));
        header.defaultCellSetting().alignHorizontallyCenter();
        header.addChild(new StringWidget(this.title, this.font));
        LinearLayout subHeader = header.addChild(LinearLayout.horizontal().spacing(4));
        this.searchBox = subHeader.addChild(
                new EditBox(
                        this.font,
                        this.width / 2 - 100,
                        22,
                        200,
                        20,
                        this.searchBox,
                        Component.translatable("screen.minecraftminecraftlauncher.search")
                )
        );
        this.searchBox.setResponder(value -> {
            if (this.list != null) {
                this.list.updateFilter(value);
            }
        });
        this.searchBox.setHint(
                Component.translatable("screen.minecraftminecraftlauncher.search").setStyle(EditBox.SEARCH_HINT_STYLE)
        );
        subHeader.addChild(
                CycleButton.builder(InstanceSelectionList.LoaderFilter::label, this.loaderFilter)
                        .withValues(InstanceSelectionList.LoaderFilter.values())
                        .displayOnlyValue()
                        .create(
                                0,
                                0,
                                100,
                                20,
                                Component.translatable("screen.minecraftminecraftlauncher.filter"),
                                (button, value) -> {
                                    this.loaderFilter = value;
                                    if (this.list != null) {
                                        this.list.setLoaderFilter(value);
                                    }
                                }
                        )
        );

        this.list = this.layout.addToContents(
                new InstanceSelectionList(
                        this.minecraft,
                        this,
                        this.width,
                        this.layout.getContentHeight(),
                        instance -> InstanceManager.helper(this.minecraft).isRunning(instance.id()),
                        this::isInteractable,
                        ignored -> this.updateFooterButtons(),
                        this::toggle,
                        this.list
                )
        );
        this.createFooterButtons();
        this.layout.visitWidgets(this::addRenderableWidget);
        this.repositionElements();
        this.list.setLoaderFilter(this.loaderFilter);
        this.list.setInstances(this.instances, this.discoveryInProgress && this.instances.isEmpty());
        this.updateFooterButtons();
    }

    private void createFooterButtons() {
        // Everything lives in the layout so a window resize repositions the
        // rows through repositionElements() without rebuilding the screen.
        LinearLayout footerColumn = this.layout.addToFooter(
                LinearLayout.vertical().spacing(4),
                settings -> settings.align(0.5F, 1.0F).paddingBottom(FOOTER_BOTTOM_PADDING)
        );
        LinearLayout primaryRow = footerColumn.addChild(
                LinearLayout.horizontal().spacing(8),
                settings -> settings.alignHorizontallyCenter()
        );
        this.launchButton = primaryRow.addChild(Button.builder(
                Component.translatable("screen.minecraftminecraftlauncher.launch"),
                button -> this.launchSelected()
        ).build());
        this.installButton = primaryRow.addChild(Button.builder(
                Component.translatable("screen.minecraftminecraftlauncher.install"),
                button -> this.minecraft.setScreenAndShow(new InstallScreen(this))
        ).build());

        LinearLayout secondaryRow = footerColumn.addChild(
                LinearLayout.horizontal().spacing(SMALL_BUTTON_SPACING),
                settings -> settings.alignHorizontallyCenter()
        );
        this.modsButton = secondaryRow.addChild(Button.builder(
                Component.translatable("screen.minecraftminecraftlauncher.open_mods_directory"),
                button -> this.openSelectedMods()
        ).width(SMALL_BUTTON_WIDTH).build());
        this.editButton = secondaryRow.addChild(Button.builder(
                Component.translatable("screen.minecraftminecraftlauncher.edit"),
                button -> this.editSelected()
        ).width(SMALL_BUTTON_WIDTH).build());
        secondaryRow.addChild(Button.builder(
                Component.translatable("screen.minecraftminecraftlauncher.open_directory"),
                button -> this.openDirectory()
        )
                .width(SMALL_BUTTON_WIDTH)
                .tooltip(Tooltip.create(
                        Component.literal(InstanceManager.instancesDirectory(this.minecraft).toString())))
                .build());
        secondaryRow.addChild(Button.builder(
                Component.translatable("screen.minecraftminecraftlauncher.refresh"),
                button -> this.reloadInstances()
        ).width(SMALL_BUTTON_WIDTH).build());
        secondaryRow.addChild(Button.builder(
                CommonComponents.GUI_BACK,
                button -> this.onClose()
        ).width(SMALL_BUTTON_WIDTH).build());
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
        if (this.list != null) {
            this.list.refreshRunningStates();
        }
        this.updateFooterButtons();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        // Center the status/log block between the list's footer separator and
        // the first button row instead of hugging the separator.
        int bandTop = this.height - FOOTER_HEIGHT + 2;
        int band = FOOTER_HEIGHT - 2 - FOOTER_BUTTON_ROWS - FOOTER_BOTTOM_PADDING;
        boolean hasLog = !this.latestOutput.isBlank();
        int blockHeight = hasLog ? 19 : 9;
        int statusY = bandTop + (band - blockHeight) / 2;
        graphics.centeredText(this.font, this.status, this.width / 2, statusY, this.statusError ? 0xFFFF7777 : 0xFFB8E0FF);
        if (hasLog) {
            graphics.centeredText(
                    this.font,
                    this.font.plainSubstrByWidth(this.latestOutput, this.width - 24),
                    this.width / 2,
                    statusY + 10,
                    0xFFCCCCCC
            );
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreenAndShow(this.parent);
    }

    private boolean isInteractable() {
        HmclHelperClient.HelperInfo info = InstanceManager.helper(this.minecraft).helperInfo();
        return info == null || info.launchAvailable();
    }

    private void launchSelected() {
        HmclInstance instance = this.list.getSelectedInstance();
        if (instance != null) {
            this.toggle(instance);
        }
    }

    private void reloadInstances() {
        this.discoveryInProgress = true;
        this.status = Component.translatable("screen.minecraftminecraftlauncher.loading");
        this.statusError = false;
        if (this.list != null) {
            this.list.setInstances(this.instances, this.instances.isEmpty());
        }
        Thread.ofVirtual().name("mcmcl-hmcl-discovery").start(() -> {
            try {
                InstanceManager.DiscoveryResult result = InstanceManager.discover(this.minecraft);
                this.minecraft.execute(() -> {
                    this.discoveryInProgress = false;
                    this.instances = result.instances();
                    if (result.problems().isEmpty()) {
                        this.status = Component.translatable(
                                "screen.minecraftminecraftlauncher.found", this.instances.size());
                        this.statusError = false;
                    } else {
                        this.status = Component.translatable(
                                "screen.minecraftminecraftlauncher.found_with_errors",
                                this.instances.size(),
                                result.problems().size()
                        );
                        this.statusError = true;
                        this.latestOutput = result.problems().getFirst();
                    }
                    if (this.list != null) {
                        this.list.setInstances(this.instances, false);
                    }
                    this.updateFooterButtons();
                });
            } catch (IOException | RuntimeException exception) {
                this.minecraft.execute(() -> {
                    this.discoveryInProgress = false;
                    this.instances = List.of();
                    this.status = Component.translatable("screen.minecraftminecraftlauncher.scan_failed");
                    this.statusError = true;
                    if (this.list != null) {
                        this.list.setInstances(List.of(), false);
                    }
                    this.updateFooterButtons();
                });
                MinecraftMinecraftLauncher.LOGGER.warn("Could not discover HMCL instances", exception);
            }
        });
    }

    private void openSelectedMods() {
        HmclInstance instance = this.list.getSelectedInstance();
        if (instance == null) {
            return;
        }
        try {
            InstanceManager.openInstanceModsDirectory(this.minecraft, instance);
            this.status = Component.translatable("screen.minecraftminecraftlauncher.mods_opened");
            this.statusError = false;
        } catch (IOException | RuntimeException exception) {
            this.status = Component.translatable("screen.minecraftminecraftlauncher.mods_failed");
            this.statusError = true;
            MinecraftMinecraftLauncher.LOGGER.warn("Could not open mods directory of {}", instance.id(), exception);
        }
    }

    private void editSelected() {
        HmclInstance instance = this.list.getSelectedInstance();
        if (instance != null) {
            this.minecraft.setScreenAndShow(new InstanceEditScreen(this, instance));
        }
    }

    private void openDirectory() {
        try {
            InstanceManager.openDirectory(this.minecraft);
            this.status = Component.translatable("screen.minecraftminecraftlauncher.directory_opened");
            this.statusError = false;
        } catch (IOException | RuntimeException exception) {
            this.status = Component.translatable("screen.minecraftminecraftlauncher.directory_failed");
            this.statusError = true;
            MinecraftMinecraftLauncher.LOGGER.warn("Could not open MCMCL instances directory", exception);
        }
    }

    private void toggle(HmclInstance instance) {
        HmclHelperClient helper = InstanceManager.helper(this.minecraft);
        if (helper.isRunning(instance.id())) {
            this.stoppingInstances.add(instance.id());
            helper.stop(instance.id());
            this.status = Component.translatable("screen.minecraftminecraftlauncher.stopping", instance.name());
            this.statusError = false;
            this.updateFooterButtons();
            return;
        }

        this.latestOutput = Component.translatable(
                "screen.minecraftminecraftlauncher.account", activeAccountName()).getString();
        this.stoppingInstances.remove(instance.id());
        this.status = Component.translatable("screen.minecraftminecraftlauncher.starting", instance.name());
        this.statusError = false;
        try {
            HmclHelperClient.LaunchHandle handle = helper.launch(
                    instance,
                    this.minecraft,
                    line -> this.minecraft.execute(() -> {
                        this.latestOutput = line;
                    })
            );
            handle.started().whenComplete((ignored, error) -> this.minecraft.execute(() -> {
                if (error == null
                        && helper.isRunning(instance.id())
                        && !this.stoppingInstances.contains(instance.id())) {
                    this.status = Component.translatable("screen.minecraftminecraftlauncher.running", instance.name());
                    this.statusError = false;
                }
            }));
            handle.exitCode().whenComplete((exitCode, error) -> this.minecraft.execute(() -> {
                this.stoppingInstances.remove(instance.id());
                if (error != null) {
                    this.status = Component.translatable("screen.minecraftminecraftlauncher.exit_error", instance.name());
                    this.statusError = true;
                    this.latestOutput = failureMessage(error);
                } else if (exitCode == 0) {
                    this.status = Component.translatable("screen.minecraftminecraftlauncher.exit_ok", instance.name());
                    this.statusError = false;
                } else {
                    this.status = Component.translatable(
                            "screen.minecraftminecraftlauncher.exit_code",
                            instance.name(),
                            exitCode
                    );
                    this.statusError = true;
                }
                this.updateFooterButtons();
            }));
        } catch (IOException | RuntimeException exception) {
            this.stoppingInstances.remove(instance.id());
            this.status = Component.translatable("screen.minecraftminecraftlauncher.launch_failed");
            this.statusError = true;
            MinecraftMinecraftLauncher.LOGGER.warn("Could not launch MCMCL instance {}", instance.id(), exception);
        }
        this.updateFooterButtons();
    }

    private String activeAccountName() {
        if (Config.OFFLINE_MODE.get()) {
            String offlineUsername = Config.OFFLINE_USERNAME.get();
            return offlineUsername.isBlank() ? this.minecraft.getUser().getName() : offlineUsername;
        }
        return this.minecraft.getUser().getName();
    }

    /** Mirrors the vanilla world screen: the primary button swaps between play and stop per selection. */
    private void updateFooterButtons() {
        HmclHelperClient helper = InstanceManager.helper(this.minecraft);
        HmclHelperClient.HelperInfo info = helper.helperInfo();
        boolean launchAvailable = info == null || info.launchAvailable();
        HmclInstance selected = this.list == null ? null : this.list.getSelectedInstance();
        boolean running = selected != null && helper.isRunning(selected.id());
        if (this.launchButton != null) {
            this.launchButton.setMessage(Component.translatable(running
                    ? "screen.minecraftminecraftlauncher.stop"
                    : "screen.minecraftminecraftlauncher.launch"
            ));
            this.launchButton.active = selected != null && (running || launchAvailable);
        }
        if (this.installButton != null) {
            this.installButton.active = launchAvailable;
        }
        if (this.modsButton != null) {
            this.modsButton.active = selected != null && selected.hasModLoader();
        }
        if (this.editButton != null) {
            this.editButton.active = selected != null;
        }
    }

    static String failureMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
