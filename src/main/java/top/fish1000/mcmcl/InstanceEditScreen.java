package top.fish1000.mcmcl;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Per-instance launch settings, mirroring the vanilla settings screen
 * structure: labelled fields between the header and the save/cancel footer.
 * Blank values fall back to the global client configuration at launch time.
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
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, 33, FOOTER_HEIGHT);
    private EditBox javaPathBox;
    private EditBox maxMemoryBox;
    private boolean valuesLoaded;

    public InstanceEditScreen(Screen parent, HmclInstance instance) {
        super(Component.translatable("screen.minecraftminecraftlauncher.edit.title", instance.name()));
        this.parent = parent;
        this.instance = instance;
    }

    @Override
    protected void init() {
        this.layout.removeChildren();
        this.layout.addTitleHeader(this.title, this.font);

        LinearLayout fields = this.layout.addToContents(LinearLayout.vertical().spacing(4));
        fields.defaultCellSetting().alignHorizontallyLeft();
        LinearLayout javaGroup = fields.addChild(LinearLayout.vertical().spacing(2));
        javaGroup.addChild(new StringWidget(Component.translatable("screen.minecraftminecraftlauncher.edit.java_path"), this.font));
        this.javaPathBox = javaGroup.addChild(new EditBox(
                this.font, 0, 0, FIELD_WIDTH, 20, this.javaPathBox,
                Component.translatable("screen.minecraftminecraftlauncher.edit.java_path")));
        this.javaPathBox.setMaxLength(1024);
        this.javaPathBox.setHint(Component.translatable("screen.minecraftminecraftlauncher.edit.global_hint"));

        LinearLayout memoryGroup = fields.addChild(LinearLayout.vertical().spacing(2));
        memoryGroup.addChild(new StringWidget(Component.translatable("screen.minecraftminecraftlauncher.edit.max_memory"), this.font));
        this.maxMemoryBox = memoryGroup.addChild(new EditBox(
                this.font, 0, 0, FIELD_WIDTH, 20, this.maxMemoryBox,
                Component.translatable("screen.minecraftminecraftlauncher.edit.max_memory")));
        this.maxMemoryBox.setMaxLength(9);
        this.maxMemoryBox.setHint(Component.translatable("screen.minecraftminecraftlauncher.edit.global_hint"));
        this.maxMemoryBox.setTextColor(0xFFFFFFFF);
        this.maxMemoryBox.setResponder(value -> this.maxMemoryBox.setTextColor(0xFFFFFFFF));

        if (!this.valuesLoaded) {
            this.valuesLoaded = true;
            InstanceSettingsStore.Settings stored = InstanceSettingsStore.read(
                    InstanceManager.instancesDirectory(this.minecraft), this.instance.id());
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
                settings -> settings.align(0.5F, 1.0F).paddingBottom(FOOTER_BOTTOM_PADDING)
        );
        LinearLayout primaryRow = footerColumn.addChild(
                LinearLayout.horizontal().spacing(8),
                settings -> settings.alignHorizontallyCenter()
        );
        primaryRow.addChild(Button.builder(
                Component.translatable("screen.minecraftminecraftlauncher.edit.save"),
                button -> this.save()
        ).build());
        LinearLayout secondaryRow = footerColumn.addChild(
                LinearLayout.horizontal().spacing(8),
                settings -> settings.alignHorizontallyCenter()
        );
        secondaryRow.addChild(Button.builder(CommonComponents.GUI_CANCEL, button -> this.onClose())
                .width(71).build());
    }

    private void save() {
        int maxMemory = 0;
        String memoryText = this.maxMemoryBox.getValue().strip();
        if (!memoryText.isEmpty()) {
            try {
                maxMemory = Integer.parseInt(memoryText);
            } catch (NumberFormatException ignored) {
                this.maxMemoryBox.setTextColor(ERROR_COLOR);
                return;
            }
            if (maxMemory < 0) {
                this.maxMemoryBox.setTextColor(ERROR_COLOR);
                return;
            }
        }
        boolean saved = InstanceSettingsStore.write(
                InstanceManager.instancesDirectory(this.minecraft),
                this.instance.id(),
                new InstanceSettingsStore.Settings(this.javaPathBox.getValue().strip(), maxMemory)
        );
        if (saved) {
            this.minecraft.setScreenAndShow(this.parent);
        }
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
