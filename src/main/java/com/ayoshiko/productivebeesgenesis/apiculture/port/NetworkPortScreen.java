package com.ayoshiko.productivebeesgenesis.apiculture.port;

import com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkContent;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.network.PacketDistributor;

@EventBusSubscriber(modid = "productivebeesgenesis", value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class NetworkPortScreen extends AbstractContainerScreen<NetworkPortMenu> {
    private int selected;
    private long sequence;
    private Button toggle;
    public NetworkPortScreen(NetworkPortMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title); imageWidth = 208; imageHeight = 208; selected = inventory.selected;
    }
    @SubscribeEvent public static void register(RegisterMenuScreensEvent event) { event.register(NetworkContent.PORT_MENU.get(), NetworkPortScreen::new); }
    @Override protected void init() {
        super.init();
        toggle = addRenderableWidget(Button.builder(NetworkPortMenu.text(menu.enabled() ? "on" : "off"), b -> send(0, 0, 0))
                .bounds(leftPos + 143, topPos + 22, 57, 16).build());
        if (menu.kind() == PortState.Kind.SUPPLY) {
            addRenderableWidget(Button.builder(Component.literal("<"), b -> send(1, 0, 0)).bounds(leftPos + 8, topPos + 22, 18, 16).build());
            addRenderableWidget(Button.builder(Component.literal(">"), b -> send(2, 0, 0)).bounds(leftPos + 121, topPos + 22, 18, 16).build());
        }
    }
    private void send(int action, int slot, int source) {
        PacketDistributor.sendToServer(new PortMenuRequest(menu.containerId, menu.session(), ++sequence, menu.configuration(),
                action, slot, source, hasShiftDown() ? 64 : 1));
    }
    @Override protected void containerTick() { super.containerTick(); toggle.setMessage(NetworkPortMenu.text(menu.enabled() ? "on" : "off")); }
    @Override public boolean mouseClicked(double x, double y, int button) {
        if (button == 0) for (var slot : menu.slots.subList(0, 36)) if (isHovering(slot.x, slot.y, 16, 16, x, y)) { selected = slot.getContainerSlot(); return true; }
        for (int slot = 0; slot < 10; slot++) {
            if (slot == 9 && menu.kind() == PortState.Kind.SUPPLY) continue;
            int sx = slot == 9 ? 22 : 22 + slot * 18, sy = slot == 9 ? 76 : 52;
            if (isHovering(sx, sy, 16, 16, x, y) && (button == 0 || button == 1)) {
                int action = menu.kind() == PortState.Kind.SUPPLY ? button == 0 ? 4 : 5 : 3;
                send(action, slot, menu.kind() != PortState.Kind.SUPPLY && button == 1 ? -1 : selected); return true;
            }
        }
        return super.mouseClicked(x, y, button);
    }
    @Override protected void renderBg(GuiGraphics g, float partial, int mouseX, int mouseY) {
        g.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xffa77932);
        g.fill(leftPos + 2, topPos + 2, leftPos + imageWidth - 2, topPos + imageHeight - 2, 0xff282c31);
        for (int slot = 0; slot < 9; slot++) {
            int x = leftPos + 22 + slot * 18, y = topPos + 52;
            g.fill(x - 1, y - 1, x + 17, y + 17, 0xff777e83); g.fill(x, y, x + 16, y + 16, 0xff30363a);
        }
        if (menu.kind() != PortState.Kind.SUPPLY) {
            int x = leftPos + 22, y = topPos + 76;
            g.fill(x - 1, y - 1, x + 17, y + 17, 0xff777e83);
            var fluid = menu.fluid();
            if (!fluid.isEmpty()) g.renderItem(new net.minecraft.world.item.ItemStack(fluid.getFluid().getBucket()), x, y);
            g.drawString(font, NetworkPortMenu.text("fluid_sample"), x + 22, y + 4, 0xffc3c8cc, false);
        }
        for (var slot : menu.slots.subList(0, 36)) {
            int x = leftPos + slot.x, y = topPos + slot.y;
            g.fill(x - 1, y - 1, x + 17, y + 17, slot.getContainerSlot() == selected ? 0xffe2b55d : 0xff777e83);
            g.fill(x, y, x + 16, y + 16, 0xff979ca5);
        }
    }
    @Override protected void renderLabels(GuiGraphics g, int mx, int my) {
        g.drawString(font, title, 8, 7, 0xffead5a7, false);
        if (menu.kind() == PortState.Kind.SUPPLY) g.drawString(font, font.plainSubstrByWidth(menu.target().getString(), 89), 29, 26, 0xffc3c8cc, false);
        g.drawString(font, font.plainSubstrByWidth(NetworkPortMenu.text(menu.kind() == PortState.Kind.SUPPLY ? "supply_hint" : "filter_hint").getString(), 192), 8, 41, 0xffc3c8cc, false);
        g.drawString(font, font.plainSubstrByWidth(NetworkPortMenu.text("status." + menu.result()).getString(), 192), 8, 96, 0xffe1b96b, false);
        g.drawString(font, NetworkPortMenu.text("inventory"), 8, 106, 0xffc3c8cc, false);
    }
    @Override public void render(GuiGraphics g, int mx, int my, float partial) {
        super.render(g, mx, my, partial);
        for (int slot = 0; slot < 9; slot++) if (isHovering(22 + slot * 18, 52, 16, 16, mx, my)) {
            var icon = menu.icon(slot);
            if (!icon.isEmpty()) {
                var lines = new java.util.ArrayList<Component>();
                lines.add(menu.cage(slot) == 0 ? icon.getHoverName() : NetworkPortMenu.text(menu.cage(slot) == 1 ? "filled_cage" : "empty_cage"));
                if (menu.previewOmitted(slot)) lines.add(NetworkPortMenu.text("preview_omitted"));
                g.renderComponentTooltip(font, lines, mx, my);
            }
            return;
        }
        renderTooltip(g, mx, my);
        if (isHovering(8, 41, 192, 9, mx, my))
            g.renderTooltip(font, NetworkPortMenu.text(menu.kind() == PortState.Kind.SUPPLY ? "supply_hint" : "filter_hint"), mx, my);
        if (menu.kind() == PortState.Kind.SUPPLY && isHovering(29, 26, 89, 9, mx, my))
            g.renderTooltip(font, menu.target(), mx, my);
        if (menu.kind() != PortState.Kind.SUPPLY && isHovering(22, 76, 16, 16, mx, my) && !menu.fluid().isEmpty())
            g.renderTooltip(font, menu.fluid().getHoverName(), mx, my);
    }
}
