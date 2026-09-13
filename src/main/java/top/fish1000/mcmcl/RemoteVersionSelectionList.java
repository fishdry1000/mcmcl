package top.fish1000.mcmcl;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;

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
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Util;

/**
 * Remote version list sharing the vanilla world-selection styling with
 * {@link InstanceSelectionList}: 36px rows with an icon, the version id and
 * a grey type line, a text filter, and loading/empty placeholders.  Icons
 * mirror HMCL's version page (grass for releases, command block for
 * snapshots, crafting table for old versions, per-loader marks otherwise).
 */
public final class RemoteVersionSelectionList extends ObjectSelectionList<RemoteVersionSelectionList.Entry> {
    private static final String ICON_PATH_PREFIX = "textures/gui/version/";
    private static final Identifier JOIN_SPRITE = Identifier.withDefaultNamespace("world_list/join");
    private static final Identifier JOIN_HIGHLIGHTED_SPRITE = Identifier.withDefaultNamespace("world_list/join_highlighted");
    private static final int ICON_SIZE = 32;
    private static final int TEXT_OFFSET = ICON_SIZE + 3;
    private static final int INFO_COLOR = 0xFF808080;
    private static final int HOVER_OVERLAY_COLOR = 0xA0A0A0A0;

    /** Icon for a game version row, mirroring HMCL's version page. */
    public static Identifier gameVersionIcon(HmclHelperClient.RemoteVersion version) {
        return switch (version.type()) {
            case "release" -> modIcon("grass");
            case "old" -> modIcon("craft_table");
            default -> modIcon("command");
        };
    }

    /** Icon for a loader version row; {@code loader} is an HMCL component id. */
    public static Identifier loaderVersionIcon(String loader) {
        return switch (loader == null ? "" : loader) {
            case "fabric", "forge", "neoforge", "quilt", "optifine" -> modIcon(loader);
            default -> modIcon("command");
        };
    }

    private static Identifier modIcon(String name) {
        return Identifier.fromNamespaceAndPath(
                MinecraftMinecraftLauncher.MODID,
                ICON_PATH_PREFIX + name + ".png"
        );
    }

    private final Screen screen;
    private final Function<HmclHelperClient.RemoteVersion, Identifier> iconProvider;
    private final Consumer<HmclHelperClient.RemoteVersion> onEntrySelect;
    private final Consumer<HmclHelperClient.RemoteVersion> onEntryInteract;
    private final Component loadingLabel;
    private final Component emptyLabel;
    private final List<HmclHelperClient.RemoteVersion> versions = new ArrayList<>();
    private VersionTypeFilter typeFilter = VersionTypeFilter.ALL;
    private String filter = "";
    private boolean loading;
    private String selectedId;

    public RemoteVersionSelectionList(
            Minecraft minecraft,
            Screen screen,
            int width,
            int height,
            Function<HmclHelperClient.RemoteVersion, Identifier> iconProvider,
            Component loadingLabel,
            Component emptyLabel,
            Consumer<HmclHelperClient.RemoteVersion> onEntrySelect,
            Consumer<HmclHelperClient.RemoteVersion> onEntryInteract,
            RemoteVersionSelectionList oldList
    ) {
        super(minecraft, width, height, 0, 36);
        this.screen = screen;
        this.iconProvider = iconProvider;
        this.loadingLabel = loadingLabel;
        this.emptyLabel = emptyLabel;
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

    /** Replaces the shown versions; while {@code loading} a spinner entry is shown instead. */
    public void setVersions(List<HmclHelperClient.RemoteVersion> versions, boolean loading) {
        this.versions.clear();
        this.versions.addAll(versions);
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

    public void setTypeFilter(VersionTypeFilter newFilter) {
        if (this.typeFilter != newFilter) {
            this.typeFilter = newFilter;
            if (!this.loading) {
                this.fillEntries();
            }
        }
    }

    public HmclHelperClient.RemoteVersion getSelectedVersion() {
        Entry selected = this.getSelected();
        return selected instanceof VersionEntry versionEntry ? versionEntry.version : null;
    }

    private void fillEntries() {
        List<Entry> entries = new ArrayList<>();
        if (this.loading) {
            entries.add(new MessageEntry(this.loadingLabel, true));
        } else if (this.versions.isEmpty()) {
            entries.add(new MessageEntry(this.emptyLabel, false));
        } else {
            String lowered = this.filter.toLowerCase(Locale.ROOT);
            for (HmclHelperClient.RemoteVersion version : this.versions) {
                if (matches(lowered, version)) {
                    entries.add(new VersionEntry(version));
                }
            }
        }
        this.replaceEntries(entries);
        Entry toSelect = null;
        if (this.selectedId != null) {
            for (Entry entry : entries) {
                if (entry instanceof VersionEntry versionEntry && versionEntry.version.id().equals(this.selectedId)) {
                    toSelect = versionEntry;
                    break;
                }
            }
        }
        this.setSelected(toSelect);
        this.refreshScrollAmount();
    }

    @Override
    public void setSelected(Entry selected) {
        super.setSelected(selected);
        this.selectedId = selected instanceof VersionEntry versionEntry ? versionEntry.version.id() : null;
        this.onEntrySelect.accept(this.getSelectedVersion());
    }

    private void playClickSound() {
        this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    private static boolean mouseOverIcon(int relX, int relY) {
        return relX >= 0 && relX < ICON_SIZE && relY >= 0 && relY < ICON_SIZE;
    }

    public abstract static class Entry extends ObjectSelectionList.Entry<RemoteVersionSelectionList.Entry> {
    }

    /** Centered single- or two-line notice used for loading (with animated dots) and empty states. */
    private final class MessageEntry extends Entry {
        private final Component label;
        private final boolean withDots;

        private MessageEntry(Component label, boolean withDots) {
            this.label = label;
            this.withDots = withDots;
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
            Font font = RemoteVersionSelectionList.this.minecraft.font;
            int lines = this.withDots ? 2 : 1;
            int labelY = this.getContentY() + (this.getContentHeight() - 9 * lines) / 2;
            graphics.text(font, this.label, this.getContentXMiddle() - font.width(this.label) / 2, labelY, -1);
            if (this.withDots) {
                String dots = LoadingDotsText.get(Util.getMillis());
                graphics.text(font, dots, this.getContentXMiddle() - font.width(dots) / 2, labelY + 9, INFO_COLOR);
            }
        }

        @Override
        public Component getNarration() {
            return this.label;
        }
    }

    public final class VersionEntry extends Entry {
        private final HmclHelperClient.RemoteVersion version;
        private final Identifier icon;
        private final StringWidget idText;
        private final StringWidget typeText;

        private VersionEntry(HmclHelperClient.RemoteVersion version) {
            this.version = version;
            this.icon = RemoteVersionSelectionList.this.iconProvider.apply(version);
            Font font = RemoteVersionSelectionList.this.minecraft.font;
            int maxTextWidth = RemoteVersionSelectionList.this.getRowWidth() - TEXT_OFFSET - 4;

            Component id = Component.literal(version.id());
            this.idText = new StringWidget(id, font);
            this.idText.setMaxWidth(maxTextWidth);
            if (font.width(id) > maxTextWidth) {
                this.idText.setTooltip(Tooltip.create(id));
            }

            Component type = versionType(version);
            this.typeText = new StringWidget(type, font);
            this.typeText.setMaxWidth(maxTextWidth);
        }

        private static Component versionType(HmclHelperClient.RemoteVersion version) {
            String type = version.type();
            if (type.isBlank()) {
                return Component.empty();
            }
            return Component.translatableWithFallback(
                    "screen.minecraftminecraftlauncher.install.type." + type, type).withColor(INFO_COLOR);
        }

        private int getTextX() {
            return this.getContentX() + TEXT_OFFSET;
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
            int textX = this.getTextX();
            this.idText.setPosition(textX, this.getContentY() + 1);
            this.idText.extractRenderState(graphics, mouseX, mouseY, a);
            this.typeText.setPosition(textX, this.getContentY() + 9 + 3);
            this.typeText.extractRenderState(graphics, mouseX, mouseY, a);

            graphics.blit(
                    RenderPipelines.GUI_TEXTURED,
                    this.icon,
                    this.getContentX(),
                    this.getContentY(),
                    0.0F,
                    0.0F,
                    ICON_SIZE,
                    ICON_SIZE,
                    ICON_SIZE,
                    ICON_SIZE
            );
            if (hovered) {
                graphics.fill(
                        this.getContentX(),
                        this.getContentY(),
                        this.getContentX() + ICON_SIZE,
                        this.getContentY() + ICON_SIZE,
                        HOVER_OVERLAY_COLOR
                );
                boolean overIcon = mouseOverIcon(mouseX - this.getContentX(), mouseY - this.getContentY());
                Identifier joinSprite = overIcon ? JOIN_HIGHLIGHTED_SPRITE : JOIN_SPRITE;
                graphics.blitSprite(RenderPipelines.GUI_TEXTURED, joinSprite, this.getContentX(), this.getContentY(), ICON_SIZE, ICON_SIZE);
                if (overIcon) {
                    RemoteVersionSelectionList.this.handleCursor(graphics);
                }
            }
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            int relX = (int) event.x() - this.getContentX();
            int relY = (int) event.y() - this.getContentY();
            if (doubleClick || mouseOverIcon(relX, relY)) {
                RemoteVersionSelectionList.this.playClickSound();
                RemoteVersionSelectionList.this.onEntryInteract.accept(this.version);
                return true;
            }
            return super.mouseClicked(event, doubleClick);
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            if (event.isSelection()) {
                RemoteVersionSelectionList.this.playClickSound();
                RemoteVersionSelectionList.this.onEntryInteract.accept(this.version);
                return true;
            }
            return super.keyPressed(event);
        }

        @Override
        public Component getNarration() {
            Component type = versionType(this.version);
            return type.getString().isBlank()
                    ? Component.translatable("narrator.minecraftminecraftlauncher.remote_version", this.version.id())
                    : Component.translatable(
                            "narrator.minecraftminecraftlauncher.remote_version_with_type", this.version.id(), type);
        }
    }

    private boolean matches(String loweredFilter, HmclHelperClient.RemoteVersion version) {
        return this.typeFilter.test(version)
                && version.id().toLowerCase(Locale.ROOT).contains(loweredFilter);
    }

    /** Search-row release-type filter, mirroring HMCL's version download page. */
    public enum VersionTypeFilter {
        ALL("screen.minecraftminecraftlauncher.filter.all"),
        RELEASE("screen.minecraftminecraftlauncher.install.type.release"),
        SNAPSHOT("screen.minecraftminecraftlauncher.install.type.snapshot"),
        OLD("screen.minecraftminecraftlauncher.install.type.old");

        private final String labelKey;

        VersionTypeFilter(String labelKey) {
            this.labelKey = labelKey;
        }

        public Component label() {
            return Component.translatable(this.labelKey);
        }

        public boolean test(HmclHelperClient.RemoteVersion version) {
            return this == ALL || version.type().equals(this.name().toLowerCase(Locale.ROOT));
        }
    }

    public Screen getScreen() {
        return this.screen;
    }
}
