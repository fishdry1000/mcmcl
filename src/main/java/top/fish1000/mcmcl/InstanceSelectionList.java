package top.fish1000.mcmcl;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.LoadingDotsText;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Util;

/**
 * Instance list styled after the vanilla world selection list: a full-width
 * dark list of 36px rows, each with a 32x32 icon, the instance name, its id
 * and a version/running-status line, plus a text filter and a loading entry.
 */
public final class InstanceSelectionList extends ObjectSelectionList<InstanceSelectionList.Entry> {
    private static final String ICON_PATH_PREFIX = "textures/gui/version/";
    private static final Identifier JOIN_SPRITE = Identifier.withDefaultNamespace("world_list/join");
    private static final Identifier JOIN_HIGHLIGHTED_SPRITE = Identifier
            .withDefaultNamespace("world_list/join_highlighted");
    private static final int ICON_SIZE = 32;
    /**
     * Text starts this many pixels right of the row's left edge, mirroring the
     * world list.
     */
    private static final int TEXT_OFFSET = ICON_SIZE + 3;
    private static final int INFO_COLOR = 0xFF808080;
    private static final int RUNNING_COLOR = 0xFF55FF55;
    private static final int HOVER_OVERLAY_COLOR = 0xA0A0A0A0;

    private final Screen screen;
    private final Predicate<HmclInstance> runningChecker;
    private final BooleanSupplier interactable;
    private final Consumer<HmclInstance> onEntrySelect;
    private final Consumer<HmclInstance> onEntryInteract;
    private final List<HmclInstance> instances = new ArrayList<>();
    private LoaderFilter loaderFilter = LoaderFilter.ALL;
    private String filter = "";
    private boolean loading;
    private String selectedId;

    public InstanceSelectionList(
            Minecraft minecraft,
            Screen screen,
            int width,
            int height,
            Predicate<HmclInstance> runningChecker,
            BooleanSupplier interactable,
            Consumer<HmclInstance> onEntrySelect,
            Consumer<HmclInstance> onEntryInteract,
            InstanceSelectionList oldList) {
        super(minecraft, width, height, 0, 36);
        this.screen = screen;
        this.runningChecker = runningChecker;
        this.interactable = interactable;
        this.onEntrySelect = onEntrySelect;
        this.onEntryInteract = onEntryInteract;
        if (oldList != null) {
            this.filter = oldList.filter;
            this.selectedId = oldList.selectedId;
        }
    }

    @Override
    public int getRowWidth() {
        return 270;
    }

    /**
     * Replaces the shown instances; while {@code loading} a spinner entry is shown
     * instead.
     */
    public void setInstances(List<HmclInstance> instances, boolean loading) {
        this.instances.clear();
        this.instances.addAll(instances);
        this.loading = loading;
        this.fillEntries();
    }

    public void updateFilter(String newFilter) {
        if (!newFilter.equals(this.filter)) {
            this.filter = newFilter;
            if (!this.loading) {
                this.fillEntries();
            }
        }
    }

    public void setLoaderFilter(LoaderFilter newFilter) {
        if (this.loaderFilter != newFilter) {
            this.loaderFilter = newFilter;
            if (!this.loading) {
                this.fillEntries();
            }
        }
    }

    public HmclInstance getSelectedInstance() {
        Entry selected = this.getSelected();
        return selected instanceof InstanceEntry instanceEntry ? instanceEntry.instance : null;
    }

    /**
     * Recomputes every row's running-status line; the screen calls this each tick.
     */
    public void refreshRunningStates() {
        for (Entry entry : this.children()) {
            if (entry instanceof InstanceEntry instanceEntry) {
                instanceEntry.refreshStatus();
            }
        }
    }

    private void fillEntries() {
        List<Entry> entries = new ArrayList<>();
        if (this.loading) {
            entries.add(new LoadingEntry());
        } else if (this.instances.isEmpty()) {
            entries.add(new EmptyEntry());
        } else {
            String lowered = this.filter.toLowerCase(Locale.ROOT);
            for (HmclInstance instance : this.instances) {
                if (matches(lowered, instance)) {
                    entries.add(new InstanceEntry(instance));
                }
            }
        }
        this.replaceEntries(entries);
        Entry toSelect = null;
        if (this.selectedId != null) {
            for (Entry entry : entries) {
                if (entry instanceof InstanceEntry instanceEntry
                        && instanceEntry.instance.id().equals(this.selectedId)) {
                    toSelect = instanceEntry;
                    break;
                }
            }
        }
        this.setSelected(toSelect);
        this.refreshScrollAmount();
    }

    private boolean matches(String loweredFilter, HmclInstance instance) {
        return this.loaderFilter.test(instance)
                && (instance.name().toLowerCase(Locale.ROOT).contains(loweredFilter)
                        || instance.id().toLowerCase(Locale.ROOT).contains(loweredFilter));
    }

    @Override
    public void setSelected(Entry selected) {
        super.setSelected(selected);
        this.selectedId = selected instanceof InstanceEntry instanceEntry ? instanceEntry.instance.id() : null;
        this.onEntrySelect.accept(this.getSelectedInstance());
    }

    private void playClickSound() {
        this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    private static boolean mouseOverIcon(int relX, int relY) {
        return relX >= 0 && relX < ICON_SIZE && relY >= 0 && relY < ICON_SIZE;
    }

    private static Identifier defaultIcon(HmclInstance instance) {
        return Identifier.fromNamespaceAndPath(
                MinecraftMinecraftLauncher.MODID,
                ICON_PATH_PREFIX + instance.defaultIconName() + ".png");
    }

    public abstract static class Entry extends ObjectSelectionList.Entry<InstanceSelectionList.Entry> {
    }

    /**
     * Vanilla-style "loading" row with animated dots, shown while instances are
     * being discovered.
     */
    private final class LoadingEntry extends Entry {
        private Component label() {
            return Component.translatable("screen.minecraftminecraftlauncher.loading");
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
            Font font = InstanceSelectionList.this.minecraft.font;
            Component label = this.label();
            int labelX = this.getContentXMiddle() - font.width(label) / 2;
            int labelY = this.getContentY() + (this.getContentHeight() - 9) / 2;
            graphics.text(font, label, labelX, labelY, -1);
            String dots = LoadingDotsText.get(Util.getMillis());
            graphics.text(font, dots, this.getContentXMiddle() - font.width(dots) / 2, labelY + 9, INFO_COLOR);
        }

        @Override
        public Component getNarration() {
            return this.label();
        }
    }

    /** Shown instead of the list when no instances exist at all. */
    private final class EmptyEntry extends Entry {
        private Component label() {
            return Component.translatable("screen.minecraftminecraftlauncher.empty");
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
            Font font = InstanceSelectionList.this.minecraft.font;
            Component label = this.label();
            graphics.text(font, label, this.getContentXMiddle() - font.width(label) / 2, this.getContentYMiddle() - 4,
                    -1);
        }

        @Override
        public Component getNarration() {
            return this.label();
        }
    }

    public final class InstanceEntry extends Entry {
        private final HmclInstance instance;
        private final StringWidget nameText;
        private final StringWidget idText;
        private final StringWidget statusText;
        private boolean running;

        private InstanceEntry(HmclInstance instance) {
            this.instance = instance;
            Font font = InstanceSelectionList.this.minecraft.font;
            int maxTextWidth = InstanceSelectionList.this.getRowWidth() - TEXT_OFFSET - 4;

            Component name = Component.literal(instance.name());
            this.nameText = new StringWidget(name, font);
            this.nameText.setMaxWidth(maxTextWidth);
            if (font.width(name) > maxTextWidth) {
                this.nameText.setTooltip(Tooltip.create(name));
            }

            Component id = Component.literal(instance.id()).withColor(INFO_COLOR);
            this.idText = new StringWidget(id, font);
            this.idText.setMaxWidth(maxTextWidth);
            if (font.width(id) > maxTextWidth) {
                this.idText.setTooltip(Tooltip.create(id));
            }

            this.running = InstanceSelectionList.this.runningChecker.test(instance);
            this.statusText = new StringWidget(this.statusLine(), font);
            this.statusText.setMaxWidth(maxTextWidth);
        }

        private Component statusLine() {
            MutableComponent line = Component.translatable(
                    "screen.minecraftminecraftlauncher.entry.version_prefix", this.instance.version());
            if (this.instance.hasModLoader()) {
                line.append(" · ").append(this.loaderLabel());
            }
            line.append(" · ").append(Component.translatable(this.running
                    ? "screen.minecraftminecraftlauncher.entry.status_running_label"
                    : "screen.minecraftminecraftlauncher.entry.status_stopped_label"));
            return line.withColor(this.running ? RUNNING_COLOR : INFO_COLOR);
        }

        private Component loaderLabel() {
            return Component.translatableWithFallback(
                    "screen.minecraftminecraftlauncher.install.loader." + this.instance.loader(),
                    this.instance.loader());
        }

        private void refreshStatus() {
            boolean running = InstanceSelectionList.this.runningChecker.test(this.instance);
            if (running != this.running) {
                this.running = running;
                this.statusText.setMessage(this.statusLine());
            }
        }

        private int getTextX() {
            return this.getContentX() + TEXT_OFFSET;
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
            int textX = this.getTextX();
            this.nameText.setPosition(textX, this.getContentY() + 1);
            this.nameText.extractRenderState(graphics, mouseX, mouseY, a);
            this.idText.setPosition(textX, this.getContentY() + 9 + 3);
            this.idText.extractRenderState(graphics, mouseX, mouseY, a);
            this.statusText.setPosition(textX, this.getContentY() + 9 + 9 + 3);
            this.statusText.extractRenderState(graphics, mouseX, mouseY, a);

            graphics.blit(
                    RenderPipelines.GUI_TEXTURED,
                    defaultIcon(this.instance),
                    this.getContentX(),
                    this.getContentY(),
                    0.0F,
                    0.0F,
                    ICON_SIZE,
                    ICON_SIZE,
                    ICON_SIZE,
                    ICON_SIZE);
            if (hovered) {
                graphics.fill(
                        this.getContentX(),
                        this.getContentY(),
                        this.getContentX() + ICON_SIZE,
                        this.getContentY() + ICON_SIZE,
                        HOVER_OVERLAY_COLOR);
                boolean overIcon = mouseOverIcon(mouseX - this.getContentX(), mouseY - this.getContentY());
                if (!this.running) {
                    Identifier joinSprite = overIcon ? JOIN_HIGHLIGHTED_SPRITE : JOIN_SPRITE;
                    graphics.blitSprite(RenderPipelines.GUI_TEXTURED, joinSprite, this.getContentX(),
                            this.getContentY(), ICON_SIZE, ICON_SIZE);
                    if (overIcon) {
                        InstanceSelectionList.this.handleCursor(graphics);
                    }
                }
            }
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            if (this.canInteract()) {
                int relX = (int) event.x() - this.getContentX();
                int relY = (int) event.y() - this.getContentY();
                if (doubleClick || mouseOverIcon(relX, relY) && !this.running) {
                    InstanceSelectionList.this.playClickSound();
                    InstanceSelectionList.this.onEntryInteract.accept(this.instance);
                    return true;
                }
            }
            return super.mouseClicked(event, doubleClick);
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            if (event.isSelection() && this.canInteract()) {
                InstanceSelectionList.this.playClickSound();
                InstanceSelectionList.this.onEntryInteract.accept(this.instance);
                return true;
            }
            return super.keyPressed(event);
        }

        /**
         * A row may be interacted with when the primary action is meaningful:
         * stopping a running instance, or launching when the helper supports it.
         */
        private boolean canInteract() {
            return this.running || InstanceSelectionList.this.interactable.getAsBoolean();
        }

        @Override
        public Component getNarration() {
            return Component.translatable(
                    "narrator.minecraftminecraftlauncher.instance_info",
                    this.instance.name(),
                    this.instance.version(),
                    Component.translatable(this.running
                            ? "screen.minecraftminecraftlauncher.entry.status_running_label"
                            : "screen.minecraftminecraftlauncher.entry.status_stopped_label"));
        }
    }

    public Screen getScreen() {
        return this.screen;
    }

    /**
     * Search-row loader filter: ALL shows everything, VANILLA only non-modded
     * instances.
     */
    public enum LoaderFilter {
        ALL("screen.minecraftminecraftlauncher.filter.all"),
        VANILLA("screen.minecraftminecraftlauncher.install.loader.none"),
        FABRIC("screen.minecraftminecraftlauncher.install.loader.fabric"),
        FORGE("screen.minecraftminecraftlauncher.install.loader.forge"),
        NEOFORGE("screen.minecraftminecraftlauncher.install.loader.neoforge"),
        QUILT("screen.minecraftminecraftlauncher.install.loader.quilt"),
        OPTIFINE("screen.minecraftminecraftlauncher.install.loader.optifine");

        private final String labelKey;

        LoaderFilter(String labelKey) {
            this.labelKey = labelKey;
        }

        public Component label() {
            return Component.translatable(this.labelKey);
        }

        public boolean test(HmclInstance instance) {
            return switch (this) {
                case ALL -> true;
                case VANILLA -> !instance.hasModLoader();
                default -> instance.loader().equals(this.name().toLowerCase(Locale.ROOT));
            };
        }
    }
}
