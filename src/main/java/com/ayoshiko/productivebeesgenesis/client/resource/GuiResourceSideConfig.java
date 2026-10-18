package com.ayoshiko.productivebeesgenesis.client.resource;

import com.ayoshiko.productivebeesgenesis.resource.*;
import mekanism.api.RelativeSide;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiElement;
import mekanism.client.gui.element.GuiInnerScreen;
import mekanism.client.gui.element.GuiInsetElement;
import mekanism.client.gui.element.button.BasicColorButton;
import mekanism.client.gui.element.button.MekanismImageButton;
import mekanism.client.gui.element.button.MekanismButton;
import mekanism.client.gui.element.window.GuiSideConfiguration;
import mekanism.client.gui.element.tab.GuiConfigTypeTab;
import mekanism.client.SpecialColors;
import mekanism.client.render.MekanismRenderer;
import mekanism.common.inventory.container.tile.MekanismTileContainer;
import mekanism.common.lib.transmitter.TransmissionType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import mekanism.common.tile.component.config.DataType;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;

/** 原生侧面窗口内的资源页；客户端只展示和请求，不变更资源资产。 */
public final class GuiResourceSideConfig extends GuiElement {
    public static final int WIDTH = 156;
    public static final int HEIGHT = 135;
    private final MekanismTileContainer<?> menu;
    private final AbstractContainerScreen<?> screen;
    private final UUID session = UUID.randomUUID();
    private final List<ResourceNativeType> types = new ArrayList<>();
    private final List<net.minecraft.world.item.ItemStack> icons = new ArrayList<>();
    private final EnumMap<RelativeSide, GuiElement> sides = new EnumMap<>(RelativeSide.class);
    private final MekanismButton auto;
    private final MekanismButton disable;
    private ResourceSideConfig config;
    private boolean editable;
    private boolean closed;
    private long sequence;
    private long accepted = -1;
    private long nextQuery;
    private long receivedAt;
    private int selected;
    private boolean selectedPage;
    private final GuiSideConfiguration<?> owner;
    private final List<GuiElement> nativeContent;
    private final List<GuiElement> content = new ArrayList<>();
    private final List<GuiElement> resourceTabs = new ArrayList<>();

    public GuiResourceSideConfig(GuiSideConfiguration<?> owner, MekanismTileContainer<?> menu,
            AbstractContainerScreen<?> screen, int nativeTabCount, List<GuiElement> nativeContent) {
        super(owner.gui(), owner.getRelativeX(), owner.getRelativeY(), WIDTH, HEIGHT);
        var gui = owner.gui();
        this.owner = owner;
        this.nativeContent = nativeContent;
        this.menu = menu;
        this.screen = screen;
        for (var type : ResourceNativeType.values()) {
            if (type == ResourceNativeType.ENERGY) continue;
            if (!type.available()) continue;
            var icon = ResourceSideIcons.icon(type);
            if (type == ResourceNativeType.CHEMICAL || !icon.isEmpty()) {
                types.add(type);
                icons.add(icon);
            }
        }
        int index = 0;
        for (var type : types) {
            final int choice = index++;
            int tabIndex = nativeTabCount + choice;
            boolean left = tabIndex < 4;
            var icon = icons.get(choice);
            resourceTabs.add(addChild(new GuiInsetElement<Void>(ResourceLocation.fromNamespaceAndPath("mekanism", "gui/chemicals.png"),
                    gui, null, relativeX + (left ? -26 : WIDTH), relativeY + 2 + (left ? tabIndex : tabIndex - 4) * 28,
                    26, 18, left) {
                @Override public void onClick(double mx, double my, int button) {
                    if (button == 0) select(choice);
                }
                @Override protected void colorTab(GuiGraphics graphics) {
                    if (type == ResourceNativeType.CHEMICAL) {
                        MekanismRenderer.color(graphics, SpecialColors.TAB_CHEMICAL_CONFIG);
                        return;
                    }
                    float shade = selectedPage && choice == selected ? 1 : 0.65F;
                    graphics.setColor(shade, shade, shade, 1);
                }
                @Override protected void drawBackgroundOverlay(GuiGraphics graphics) {
                    if (type == ResourceNativeType.CHEMICAL) {
                        super.drawBackgroundOverlay(graphics);
                        return;
                    }
                    graphics.renderItem(icon, getButtonX() + (getButtonWidth() - 16) / 2,
                            getButtonY() + (getButtonHeight() - 16) / 2);
                }
                @Override public void updateTooltip(int mx, int my) {
                    setTooltip(Tooltip.create(tr("type." + type.id())));
                }
            }));
        }
        content.add(addChild(new GuiInnerScreen(gui, relativeX + 38, relativeY + 25, 80, 12,
                () -> List.of(ejectStatus()))));
        addSide(gui, RelativeSide.TOP, 67, 46);
        addSide(gui, RelativeSide.LEFT, 44, 69);
        addSide(gui, RelativeSide.FRONT, 67, 69);
        addSide(gui, RelativeSide.RIGHT, 90, 69);
        addSide(gui, RelativeSide.BOTTOM, 67, 92);
        addSide(gui, RelativeSide.BACK, 44, 92);
        auto = addChild(new MekanismImageButton(gui, relativeX + 136, relativeY + 6, 14,
                getButtonLocation("auto_eject"),
                (element, mx, my) -> { change(mask(), config == null || !config.eject(type())); return true; }));
        auto.setTooltip(Tooltip.create(tr("eject_hint")));
        disable = addChild(new MekanismImageButton(gui, relativeX + 136, relativeY + 95, 14,
                getButtonLocation("clear_sides"),
                (element, mx, my) -> { change(0, config != null && config.eject(type())); return true; }));
        disable.setTooltip(Tooltip.create(tr("disable_all")));
        content.add(auto);
        content.add(disable);
        selectNative();
        refresh();
    }
    public boolean selectedPage() { return selectedPage; }
    /** 扩展页没有原生传输类型，覆盖层不得回退成物品页。 */
    public static TransmissionType nativeType(GuiSideConfiguration<?> owner) {
        TransmissionType first = null;
        TransmissionType selected = null;
        for (var element : owner.children()) {
            if (element instanceof GuiResourceSideConfig page && page.selectedPage()) return null;
            if (element instanceof GuiConfigTypeTab tab) {
                if (first == null) first = tab.getTransmissionType();
                if (!tab.visible) selected = tab.getTransmissionType();
            }
        }
        if (selected != null) return selected;
        return first == null ? TransmissionType.ITEM : first;
    }
    public List<GuiElement> resourceTabs() { return List.copyOf(resourceTabs); }
    public void select(int choice) {
        if (closed || choice < 0 || choice >= types.size()) return;
        if (!selectedPage) {
            config = null;
            editable = false;
            receivedAt = 0;
        }
        selected = choice;
        selectedPage = true;
        ResourceMachinePanel.openedSides(this);
        nextQuery = 0;
        applyVisibility();
        refresh();
    }
    public void selectNative() {
        if (selectedPage) {
            PacketDistributor.sendToServer(new ResourceSideConfigSync.Close(request()));
            ResourceMachinePanel.closedSides(this);
        }
        selectedPage = false;
        applyVisibility();
    }
    public void applyVisibility() {
        for (var element : nativeContent) {
            element.visible = !selectedPage;
        }
        if (selectedPage) {
            for (var element : owner.children()) {
                if (element.getClass().getName().startsWith("com.ayoshiko.productivebeesgenesis.client.screen.")) {
                    element.visible = false;
                }
            }
        }
        for (var element : content) element.visible = selectedPage;
        if (selectedPage) {
            for (var element : owner.children()) if (element instanceof GuiConfigTypeTab) element.visible = true;
        }
    }
    @Override protected boolean clicked(double mx, double my) { return false; }
    private static Component tr(String key) { return Component.translatable("resource.productivebeesgenesis.sides." + key); }
    private Component ejectStatus() {
        if (config == null) return tr("eject.loading");
        return tr("eject." + (config.eject(type()) ? "on" : "off"));
    }
    private ResourceNativeType type() { return types.get(selected); }
    private int mask() { return config == null ? 0 : config.mask(type()); }
    private void addSide(IGuiWrapper gui, RelativeSide side, int x, int y) {
        var element = addChild(new BasicColorButton(gui, relativeX + x, relativeY + y, 22,
                () -> (mask() & (1 << side.ordinal())) != 0 ? DataType.OUTPUT.getColor() : null,
                (button, mx, my) -> {
                    if (config != null) change(mask() ^ (1 << side.ordinal()), config.eject(type()));
                    return true;
                }, null) {
            @Override public void renderForeground(GuiGraphics graphics, int mx, int my) {
                var host = menu.getTileEntity();
                var level = Minecraft.getInstance().level;
                var direction = side.getDirection(ResourceSideConfig.facing(host));
                var pos = host.getBlockPos().relative(direction);
                if (level != null && level.hasChunkAt(pos)) {
                    var block = level.getBlockState(pos).getBlock();
                    var stack = new net.minecraft.world.item.ItemStack(block);
                    if (!stack.isEmpty()) {
                        graphics.pose().pushPose();
                        graphics.pose().translate(relativeX + 3, relativeY + 3, 0);
                        graphics.renderItem(stack, 0, 0);
                        graphics.pose().popPose();
                    }
                }
            }
        });
        sides.put(side, element);
        content.add(element);
    }
    private ResourceMenuSync.Request request() {
        return new ResourceMenuSync.Request(menu.containerId, menu.getTileEntity().getBlockPos(), session,
                ++sequence, 0, config == null ? 0 : config.revision(), 0);
    }
    private void change(int mask, boolean eject) {
        if (config == null || !editable || closed || !selectedPage) return;
        PacketDistributor.sendToServer(new ResourceSideConfigSync.Change(request(), type().ordinal(), mask, eject));
        nextQuery = net.minecraft.Util.getMillis() + 500;
    }
    void accept(ResourceSideConfigSync.Reply value) {
        var request = value.request();
        if (closed || !selectedPage || Minecraft.getInstance().screen != screen || screen.getMenu() != menu
                || !request.session().equals(session) || request.menu() != menu.containerId
                || !request.pos().equals(menu.getTileEntity().getBlockPos()) || request.sequence() <= accepted
                || request.sequence() > sequence) return;
        try {
            if (value.config() != null) config = value.config().isEmpty() ? null : ResourceSideConfig.load(value.config());
            editable = value.editable() && config != null;
            accepted = request.sequence();
            receivedAt = net.minecraft.Util.getMillis();
            refresh();
        } catch (IllegalArgumentException invalid) {
            config = null;
            editable = false;
            nextQuery = 0;
            refresh();
        }
    }
    private void refresh() {
        if (auto == null) return;
        auto.active = editable && config != null;
        disable.active = auto.active;
        auto.setTooltip(Tooltip.create(tr("eject." + (config != null && config.eject(type()) ? "on" : "off")).copy()
                .append("\n").append(tr("eject_hint"))));
        for (var entry : sides.entrySet()) {
            entry.getValue().active = auto.active;
            var state = (mask() & (1 << entry.getKey().ordinal())) != 0 ? tr("output") : tr("none");
            var tooltip = Component.translatable(entry.getKey().getTranslationKey()).append("\n").append(state)
                    .append("\n").append(tr("side_hint"));
            entry.getValue().setTooltip(Tooltip.create(tooltip));
        }
    }
    @Override public void tick() {
        applyVisibility();
        super.tick();
        if (closed || !selectedPage || Minecraft.getInstance().screen != screen || screen.getMenu() != menu) return;
        long now = net.minecraft.Util.getMillis();
        if (now >= nextQuery) {
            PacketDistributor.sendToServer(new ResourceSideConfigSync.Query(request()));
            nextQuery = now + 500;
        }
        if (receivedAt != 0 && now - receivedAt > 1500 && editable) {
            editable = false;
            refresh();
        }
    }
    @Override public void onWindowClose() {
        if (!closed) {
            closed = true;
            PacketDistributor.sendToServer(new ResourceSideConfigSync.Close(request()));
            ResourceMachinePanel.closedSides(this);
        }
        super.onWindowClose();
    }
    @Override public void renderForeground(GuiGraphics graphics, int mx, int my) {
        super.renderForeground(graphics, mx, my);
        if (!selectedPage) return;
        var title = tr("title");
        graphics.pose().pushPose();
        graphics.pose().translate(relativeX + 34, relativeY + 8, 0);
        graphics.pose().scale(0.8F, 0.8F, 1);
        graphics.drawString(font(), title, 0, 0, ResourceGuiTextures.TEXT, false);
        graphics.pose().popPose();
        if (config == null) graphics.drawString(font(), tr("loading"), relativeX + 38, relativeY + 120, ResourceGuiTextures.MUTED, false);
    }
}
