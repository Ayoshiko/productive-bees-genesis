package com.ayoshiko.productivebeesgenesis.apiculture.client;

import com.ayoshiko.productivebeesgenesis.apiculture.core.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

@EventBusSubscriber(modid = "productivebeesgenesis", value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class NetworkCoreScreen extends AbstractContainerScreen<NetworkCoreMenu> {
	private Button production;
	public NetworkCoreScreen(NetworkCoreMenu menu, Inventory inventory, Component title) { super(menu, inventory, title); imageWidth = 260; imageHeight = 263; }
	@SubscribeEvent public static void register(RegisterMenuScreensEvent event) { event.register(NetworkContent.CORE_MENU.get(), NetworkCoreScreen::new); }
	@Override protected void init() {
		super.init();
		addRenderableWidget(Button.builder(Component.translatable("screen.productivebeesgenesis.network.rebuild"), button -> minecraft.gameMode.handleInventoryButtonClick(menu.containerId, 0))
				.bounds(leftPos + 10, topPos + 184, 240, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("screen.productivebeesgenesis.network.join"), button -> minecraft.gameMode.handleInventoryButtonClick(menu.containerId, 1))
				.bounds(leftPos + 10, topPos + 210, 116, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("screen.productivebeesgenesis.network.return"), button -> minecraft.gameMode.handleInventoryButtonClick(menu.containerId, 2))
				.bounds(leftPos + 134, topPos + 210, 116, 20).build());
		production = addRenderableWidget(Button.builder(productionLabel(), button -> minecraft.gameMode.handleInventoryButtonClick(menu.containerId, 3))
				.bounds(leftPos + 10, topPos + 236, 240, 20).build());
	}
	private Component productionLabel() { return Component.translatable("screen.productivebeesgenesis.network." + (menu.productionRunning() ? "pause" : "start")); }
	@Override protected void containerTick() { super.containerTick(); production.setMessage(productionLabel()); }
	@Override protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
		graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xff292720);
		graphics.fill(leftPos + 1, topPos + 1, leftPos + imageWidth - 1, topPos + 22, 0xff65592e);
	}
	@Override protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
		graphics.drawString(font, title, 10, 8, 0xfff0d78d, false);
		graphics.drawString(font, Component.translatable("screen.productivebeesgenesis.network.state." + menu.value(0)), 10, 32, 0xffdddddd, false);
		for (int i = 1; i <= 4; i++) graphics.drawString(font, Component.translatable("screen.productivebeesgenesis.network.count." + i, menu.value(i)), 10, 35 + i * 16, 0xffdddddd, false);
		graphics.drawString(font, Component.translatable("screen.productivebeesgenesis.network.ownership." + menu.ownershipStatus()), 10, 118, 0xfff0d78d, false);
		graphics.drawString(font, Component.translatable("screen.productivebeesgenesis.network.energy",
				com.ayoshiko.productivebeesgenesis.util.NumberFormatter.formatCompact(menu.energy(false)),
				com.ayoshiko.productivebeesgenesis.util.NumberFormatter.formatCompact(menu.energy(true))), 10, 140, 0xffdddddd, false);
		graphics.drawString(font, Component.translatable("screen.productivebeesgenesis.network.runtime." + menu.runtimeStatus()), 10, 162, 0xffdddddd, false);
	}
}
