package com.ayoshiko.productivebeesgenesis.client.resource;

import com.ayoshiko.productivebeesgenesis.resource.ResourceMenuSync;
import com.ayoshiko.productivebeesgenesis.resource.ResourceNativeType;
import com.ayoshiko.productivebeesgenesis.resource.ResourceSideConfig;
import com.ayoshiko.productivebeesgenesis.resource.ResourceSideConfigSync;
import mekanism.api.RelativeSide;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiElement;
import mekanism.client.gui.element.GuiInnerScreen;
import mekanism.client.gui.element.button.BasicColorButton;
import mekanism.client.gui.element.button.MekanismButton;
import mekanism.client.gui.element.button.MekanismImageButton;
import mekanism.common.inventory.container.tile.MekanismTileContainer;
import mekanism.common.tile.component.config.DataType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.EnumMap;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** 化学品原生页和扩展资源详情共用控件；只有置顶页面保有配置查询会话。 */
public final class GuiResourceSideConfigPage extends GuiElement {
    private final MekanismTileContainer<?> menu;
    private final AbstractContainerScreen<?> screen;
    private final Supplier<ResourceNativeType> type;
    private final BooleanSupplier foreground;
    private final EnumMap<RelativeSide, GuiElement> sides = new EnumMap<>(RelativeSide.class);
    private final MekanismButton auto;
    private final MekanismButton disable;
    private UUID session = UUID.randomUUID();
    private ResourceSideConfig config;
    private boolean editable;
    private boolean subscribed;
    private boolean closed;
    private long sequence;
    private long accepted = -1;
    private long nextQuery;
    private long receivedAt;

    public GuiResourceSideConfigPage(IGuiWrapper gui, int x, int y, MekanismTileContainer<?> menu,
            AbstractContainerScreen<?> screen, Supplier<ResourceNativeType> type, BooleanSupplier foreground) {
        super(gui, x, y, 156, 131);
        this.menu = menu;
        this.screen = screen;
        this.type = type;
        this.foreground = foreground;
        addChild(new GuiInnerScreen(gui, relativeX + 38, relativeY + 25, 80, 12,
                () -> List.of(ejectStatus())));
        addSide(gui, RelativeSide.TOP, 67, 46);
        addSide(gui, RelativeSide.LEFT, 44, 69);
        addSide(gui, RelativeSide.FRONT, 67, 69);
        addSide(gui, RelativeSide.RIGHT, 90, 69);
        addSide(gui, RelativeSide.BOTTOM, 67, 92);
        addSide(gui, RelativeSide.BACK, 44, 92);
        auto = addChild(new MekanismImageButton(gui, relativeX + 136, relativeY + 6, 14,
                getButtonLocation("auto_eject"), (element, mx, my) -> {
                    change(mask(), config == null || !config.eject(type.get()));
                    return true;
                }));
        disable = addChild(new MekanismImageButton(gui, relativeX + 136, relativeY + 95, 14,
                getButtonLocation("clear_sides"), (element, mx, my) -> {
                    change(0, config != null && config.eject(type.get()));
                    return true;
                }));
        disable.setTooltip(Tooltip.create(tr("disable_all")));
        refresh();
    }

    public void show(boolean show) {
        visible = show;
        if (!show) deactivate();
        refresh();
    }

    private boolean current() {
        return !closed && visible && type.get() != null && foreground.getAsBoolean()
                && Minecraft.getInstance().screen == screen && screen.getMenu() == menu;
    }

    private static Component tr(String key) {
        return Component.translatable("resource.productivebeesgenesis.sides." + key);
    }

    private Component ejectStatus() {
        if (config == null || type.get() == null) return tr("eject.loading");
        return tr("eject." + (config.eject(type.get()) ? "on" : "off"));
    }

    private int mask() {
        return config == null || type.get() == null ? 0 : config.mask(type.get());
    }

    private void addSide(IGuiWrapper gui, RelativeSide side, int x, int y) {
        var element = addChild(new BasicColorButton(gui, relativeX + x, relativeY + y, 22,
                () -> (mask() & (1 << side.ordinal())) != 0 ? DataType.OUTPUT.getColor() : null,
                (button, mx, my) -> {
                    if (config != null && type.get() != null) change(mask() ^ (1 << side.ordinal()), config.eject(type.get()));
                    return true;
                }, null) {
            @Override public void renderForeground(GuiGraphics graphics, int mx, int my) {
                var host = menu.getTileEntity();
                var level = Minecraft.getInstance().level;
                var direction = side.getDirection(ResourceSideConfig.facing(host));
                var pos = host.getBlockPos().relative(direction);
                if (level != null && level.hasChunkAt(pos)) {
                    var stack = new ItemStack(level.getBlockState(pos).getBlock());
                    if (!stack.isEmpty()) graphics.renderItem(stack, relativeX + 3, relativeY + 3);
                }
            }
        });
        sides.put(side, element);
    }

    private ResourceMenuSync.Request request() {
        return new ResourceMenuSync.Request(menu.containerId, menu.getTileEntity().getBlockPos(), session,
                ++sequence, 0, config == null ? 0 : config.revision(), 0);
    }

    private void change(int mask, boolean eject) {
        if (config == null || !editable || !subscribed || !current()) return;
        PacketDistributor.sendToServer(new ResourceSideConfigSync.Change(request(), type.get().ordinal(), mask, eject));
        nextQuery = net.minecraft.Util.getMillis() + 500;
    }

    void accept(ResourceSideConfigSync.Reply value) {
        var request = value.request();
        if (!subscribed || !current() || !request.session().equals(session) || request.menu() != menu.containerId
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
        boolean enabled = subscribed && current() && editable && config != null;
        auto.active = enabled;
        disable.active = enabled;
        auto.setTooltip(Tooltip.create(ejectStatus().copy().append("\n").append(tr("eject_hint"))));
        for (var entry : sides.entrySet()) {
            entry.getValue().active = enabled;
            var state = (mask() & (1 << entry.getKey().ordinal())) != 0 ? tr("output") : tr("none");
            entry.getValue().setTooltip(Tooltip.create(Component.translatable(entry.getKey().getTranslationKey())
                    .append("\n").append(state).append("\n").append(tr("side_hint"))));
        }
    }

    @Override protected boolean clicked(double mx, double my) {
        return false;
    }

    @Override public void tick() {
        super.tick();
        if (!current()) {
            deactivate();
            return;
        }
        if (!subscribed) {
            ResourceMachinePanel.openedSides(this);
            session = UUID.randomUUID();
            subscribed = true;
            nextQuery = 0;
        }
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

    void deactivate() {
        if (!subscribed) return;
        subscribed = false;
        PacketDistributor.sendToServer(new ResourceSideConfigSync.Close(request()));
        ResourceMachinePanel.closedSides(this);
        config = null;
        editable = false;
        receivedAt = 0;
        refresh();
    }

    @Override public void onWindowClose() {
        closed = true;
        deactivate();
        super.onWindowClose();
    }

    @Override public void renderForeground(GuiGraphics graphics, int mx, int my) {
        super.renderForeground(graphics, mx, my);
        var selected = type.get();
        if (selected == null) return;
        var title = tr("type." + selected.id());
        float titleScale = Math.min(1, 96F / Math.max(1, font().width(title)));
        graphics.pose().pushPose();
        graphics.pose().translate(relativeX + 34, relativeY + 8, 0);
        graphics.pose().scale(titleScale, titleScale, 1);
        graphics.drawString(font(), title, 0, 0, ResourceGuiTextures.TEXT, false);
        graphics.pose().popPose();
        if (config == null || !editable) {
            var text = tr("loading");
            float scale = Math.min(1, 132F / Math.max(1, font().width(text)));
            graphics.pose().pushPose();
            graphics.pose().translate(relativeX + 12, relativeY + 118, 0);
            graphics.pose().scale(scale, scale, 1);
            graphics.drawString(font(), text, 0, 0, ResourceGuiTextures.MUTED, false);
            graphics.pose().popPose();
        }
    }
}
