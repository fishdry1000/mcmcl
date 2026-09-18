package top.fish1000.mcmcl;

import java.io.IOException;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Per-instance settings editor: rename plus launch overrides, mirroring the
 * vanilla settings screen structure with labelled fields between the header
 * and the save/cancel footer. Blank launch values fall back to the global
 * client configuration at launch time.
 */
public final class InstanceEditScreen extends Screen {
    private static final int FOOTER_HEIGHT = 74;
    /** Two 20px button rows plus their 4px spacing. */
    private static final int FOOTER_BUTTON_ROWS = 44;
    private static final int FOOTER_BOTTOM_PADDING = 4;
    private static final int FIELD_WIDTH = 250;
    private static final int ERROR_COLOR = 0xFFFF5555;

    private final Screen parent;
    private final HmclInstance instance;
    /** Invoked after a rename so the caller can refresh its instance list. */
    private final Runnable onChanged;
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, 33, FOOTER_HEIGHT);
    private EditBox idBox;
    private EditBox javaPathBox;
    private EditBox maxMemoryBox;
    private InstanceSettingsStore.VersionIsolationOverride versionIsolation = InstanceSettingsStore.VersionIsolationOverride.INHERIT;
    private StringWidget statusWidget;
    private boolean valuesLoaded;

    public InstanceEditScreen(Screen parent, HmclInstance instance, Runnable onChanged) {
        super(Component.translatable("screen.minecraftminecraftlauncher.edit.title", instance.name()));
        this.parent = parent;
        this.instance = instance;
        this.onChanged = onChanged;
    }

    @Override
    protected void init() {
        InstanceSettingsStore.Settings stored = null;
        if (!this.valuesLoaded) {
            this.valuesLoaded = true;
            stored = InstanceSettingsStore.read(
                    InstanceManager.instancesDirectory(this.minecraft), this.instance.id());
            this.versionIsolation = stored.versionIsolation();
        }

        this.layout.removeChildren();
        this.layout.addTitleHeader(this.title, this.font);

        LinearLayout fields = this.layout.addToContents(LinearLayout.vertical().spacing(4));
        fields.defaultCellSetting().alignHorizontallyLeft();

        LinearLayout idGroup = fields.addChild(LinearLayout.vertical().spacing(2));
        idGroup.addChild(
                new StringWidget(Component.translatable("screen.minecraftminecraftlauncher.edit.id"), this.font));
        this.idBox = idGroup.addChild(new EditBox(
                this.font, 0, 0, FIELD_WIDTH, 20, this.idBox,
                Component.translatable("screen.minecraftminecraftlauncher.edit.id")));
        this.idBox.setMaxLength(64);

        LinearLayout javaGroup = fields.addChild(LinearLayout.vertical().spacing(2));
        javaGroup.addChild(new StringWidget(Component.translatable("screen.minecraftminecraftlauncher.edit.java_path"),
                this.font));
        this.javaPathBox = javaGroup.addChild(new EditBox(
                this.font, 0, 0, FIELD_WIDTH, 20, this.javaPathBox,
                Component.translatable("screen.minecraftminecraftlauncher.edit.java_path")));
        this.javaPathBox.setMaxLength(1024);
        this.javaPathBox.setHint(Component.translatable("screen.minecraftminecraftlauncher.edit.global_hint"));

        LinearLayout memoryGroup = fields.addChild(LinearLayout.vertical().spacing(2));
        memoryGroup.addChild(new StringWidget(
                Component.translatable("screen.minecraftminecraftlauncher.edit.max_memory"), this.font));
        this.maxMemoryBox = memoryGroup.addChild(new EditBox(
                this.font, 0, 0, FIELD_WIDTH, 20, this.maxMemoryBox,
                Component.translatable("screen.minecraftminecraftlauncher.edit.max_memory")));
        this.maxMemoryBox.setMaxLength(9);
        this.maxMemoryBox.setHint(Component.translatable("screen.minecraftminecraftlauncher.edit.global_hint"));
        this.maxMemoryBox.setTextColor(0xFFFFFFFF);
        this.maxMemoryBox.setResponder(value -> this.maxMemoryBox.setTextColor(0xFFFFFFFF));

        fields.addChild(
                CycleButton.builder(InstanceEditScreen::isolationLabel, this.versionIsolation)
                        .withValues(InstanceSettingsStore.VersionIsolationOverride.values())
                        .create(
                                0,
                                0,
                                FIELD_WIDTH,
                                20,
                                Component.translatable("screen.minecraftminecraftlauncher.edit.version_isolation"),
                                (button, value) -> this.versionIsolation = value));

        this.statusWidget = fields.addChild(new StringWidget(Component.empty(), this.font));

        if (stored != null) {
            this.javaPathBox.setValue(stored.javaPath());
            if (stored.maxMemory() > 0) {
                this.maxMemoryBox.setValue(String.valueOf(stored.maxMemory()));
            }
        }

        this.createFooterButtons();
        this.layout.visitWidgets(this::addRenderableWidget);
        this.repositionElements();
    }

    private void createFooterButtons() {
        LinearLayout footerColumn = this.layout.addToFooter(
                LinearLayout.vertical().spacing(4),
                settings -> settings.align(0.5F, 1.0F).paddingBottom(FOOTER_BOTTOM_PADDING));
        LinearLayout primaryRow = footerColumn.addChild(
                LinearLayout.horizontal().spacing(8),
                settings -> settings.alignHorizontallyCenter());
        primaryRow.addChild(Button.builder(
                Component.translatable("screen.minecraftminecraftlauncher.edit.save"),
                button -> this.save()).build());
        LinearLayout secondaryRow = footerColumn.addChild(
                LinearLayout.horizontal().spacing(8),
                settings -> settings.alignHorizontallyCenter());
        secondaryRow.addChild(Button.builder(CommonComponents.GUI_CANCEL, button -> this.onClose())
                .width(71).build());
    }

    private void save() {
        int maxMemory = parseMemory();
        if (maxMemory < 0) {
            return;
        }
        String newId = this.idBox.getValue().strip();
        boolean renamed = false;
        if (!newId.equals(this.instance.id())) {
            if (newId.isEmpty()) {
                this.showStatus(Component.translatable("screen.minecraftminecraftlauncher.edit.rename_empty"));
                return;
            }
            if (!HmclInstance.isSafeId(newId)) {
                this.showStatus(Component.translatable("screen.minecraftminecraftlauncher.edit.rename_invalid"));
                return;
            }
            if (InstanceManager.helper(this.minecraft).isRunning(this.instance.id())) {
                this.showStatus(Component.translatable("screen.minecraftminecraftlauncher.edit.rename_running"));
                return;
            }
            if (InstanceManager.instanceExists(this.minecraft, newId)) {
                this.showStatus(Component.translatable("screen.minecraftminecraftlauncher.edit.rename_exists"));
                return;
            }
            try {
                InstanceManager.renameInstance(this.minecraft, this.instance, newId);
                renamed = true;
            } catch (IOException exception) {
                // Races (instances appearing mid-edit) surface the raw reason;
                // every common case was already covered above.
                this.showStatus(Component.literal(exception.getMessage() == null
                        ? "Rename failed"
                        : exception.getMessage()));
                return;
            }
        }
        InstanceSettingsStore.write(
                InstanceManager.instancesDirectory(this.minecraft),
                newId,
                new InstanceSettingsStore.Settings(
                        this.javaPathBox.getValue().strip(), maxMemory, this.versionIsolation));
        if (renamed && this.onChanged != null) {
            this.onChanged.run();
        }
        this.minecraft.setScreenAndShow(this.parent);
    }

    /**
     * Returns the parsed memory override, or -1 (after flagging the box red) when
     * invalid.
     */
    private int parseMemory() {
        String memoryText = this.maxMemoryBox.getValue().strip();
        if (memoryText.isEmpty()) {
            return 0;
        }
        try {
            int maxMemory = Integer.parseInt(memoryText);
            if (maxMemory >= 0) {
                return maxMemory;
            }
        } catch (NumberFormatException ignored) {
        }
        this.maxMemoryBox.setTextColor(ERROR_COLOR);
        return -1;
    }

    private void showStatus(Component message) {
        this.statusWidget.setMessage(message.copy().withColor(ERROR_COLOR));
    }

    private static Component isolationLabel(InstanceSettingsStore.VersionIsolationOverride value) {
        String suffix = switch (value) {
            case INHERIT -> "inherit";
            case ENABLED -> "enabled";
            case DISABLED -> "disabled";
        };
        return Component.translatable("screen.minecraftminecraftlauncher.edit.version_isolation." + suffix);
    }

    @Override
    protected void repositionElements() {
        this.layout.arrangeElements();
    }

    @Override
    public void onClose() {
        this.minecraft.setScreenAndShow(this.parent);
    }
}
