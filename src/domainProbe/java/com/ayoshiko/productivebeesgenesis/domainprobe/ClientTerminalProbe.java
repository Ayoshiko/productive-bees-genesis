package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.client.NetworkCoreScreen;
import com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkCoreMenu;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.*;
import java.nio.file.*;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

/** 真实鼠标命中控件后走注册 C2S/S2C；不直接调用服务器交换服务。 */
final class ClientTerminalProbe {
	private static int step;
	private static final java.util.Set<Integer> beeColors = new java.util.HashSet<>();
	private static long nextAt, resizeSequence;
	static boolean complete() { return step == 25; }
	static boolean advance(Minecraft client, NetworkCoreScreen screen, NetworkCoreMenu menu) throws Exception {
		return advance(client, screen, menu, false);
	}
	static boolean advance(Minecraft client, NetworkCoreScreen screen, NetworkCoreMenu menu, boolean remote) throws Exception {
		verifyLayout(screen, menu);
		if (!remote) ClientTerminalFixture.requested = true;
		if ((!remote && !ClientTerminalFixture.ready) || Util.getMillis() < nextAt || menu.clientState().waiting() || !menu.clientState().ready(Util.getMillis())) return false;
		// 回复可能在界面 tick 后到达；等控件消费新状态，不在同 tick 使用旧的禁用按钮。
		String refresh = Component.translatable("screen.productivebeesgenesis.network.refresh").getString();
		if (screen.children().stream().anyMatch(child -> child instanceof Button button
				&& button.getMessage().getString().equals(refresh) && !button.active)) return false;
		nextAt = Util.getMillis() + 250;
		switch (step) {
			case 0 -> { press(screen, "tab.1"); step++; }
			case 1 -> { if (!member(screen, menu)) return false; press(screen, "feed_in"); step++; }
			case 2 -> {
				result(menu, TerminalReply.Status.MOVED, 1); require(client.player.getInventory().getItem(0).getCount() == 63, "Client food input not synchronized");
				capture(client, "terminal-feeding"); press(screen, "refresh"); step++;
			}
			case 3 -> { if (!member(screen, menu)) return false; press(screen, "feed_out"); step++; }
			case 4 -> { result(menu, TerminalReply.Status.MOVED, 1); press(screen, "refresh"); step++; }
			case 5 -> { if (!member(screen, menu)) return false; chooseSlot(screen, menu, 1); press(screen, "cage_in"); step++; }
			case 6 -> {
				result(menu, TerminalReply.Status.MOVED, 1); require(!client.player.getInventory().getItem(1).has(net.minecraft.core.component.DataComponents.CUSTOM_DATA), "Client sturdy cage did not empty");
				press(screen, "refresh"); step++;
			}
			case 7 -> { if (!member(screen, menu)) return false; press(screen, "cage_out"); step++; }
			case 8 -> { result(menu, TerminalReply.Status.MOVED, 1); press(screen, "tab.2"); step++; }
			case 9 -> {
				capture(client, "terminal-products");
				if (!product(screen, menu, "minecraft:iron_ingot", "__plain__", 1)) return false;
				step++;
			}
			case 10 -> {
				result(menu, TerminalReply.Status.MOVED, 1); if (!autoRefreshed(menu)) return false;
				require(client.player.getInventory().getItem(4).getCount() == 64, "Automatic merge did not use matching inventory stack"); step++;
			}
			case 11 -> { if (!product(screen, menu, "minecraft:iron_ingot", "variant-red", 1)) return false; step++; }
			case 12 -> { result(menu, TerminalReply.Status.MOVED, 1); if (!autoRefreshed(menu)) return false; step++; }
			case 13 -> { if (!product(screen, menu, "minecraft:iron_ingot", "variant-red", 0)) return false; step++; }
			case 14 -> { result(menu, TerminalReply.Status.MOVED, 1); if (!autoRefreshed(menu)) return false; capture(client, "terminal-variants"); step++; }
			case 15 -> { if (!product(screen, menu, "minecraft:iron_ingot", "__plain__", 0)) return false; step++; }
			case 16 -> { result(menu, TerminalReply.Status.NO_SPACE, 0); press(screen, "refresh"); step++; }
			case 17 -> { if (!product(screen, menu, "minecraft:water", "", 0)) return false; step++; }
			case 18 -> {
				result(menu, TerminalReply.Status.MOVED, 1000);
				require(client.player.getInventory().getItem(2).is(Items.WATER_BUCKET), "Automatic bucket delivery not synchronized");
				if (menu.clientState().notice() != TerminalClientState.Notice.EXPIRED) return false;
				require(menu.clientState().view() == null, "Expired page retained actions"); capture(client, "terminal-expired"); press(screen, "refresh"); step++;
			}
			case 19 -> {
				require(menu.clientState().view() != null, "Refresh after expiry failed"); resizeSequence = menu.terminalReply().sequence();
				screen.resize(client, 320, 240); verifyLayout(screen, menu); press(screen, "refresh"); step++;
			}
			case 20 -> {
				require(menu.terminalReply().sequence() > resizeSequence && menu.clientState().view() != null, "Resize reset menu sequence");
				screen.resize(client, client.getWindow().getGuiScaledWidth(), client.getWindow().getGuiScaledHeight()); step++;
			}
			case 21 -> { capture(client, "terminal-inventory"); press(screen, "refresh"); step++; }
			case 22 -> { if (!verifyBeeIcons(client, screen, menu)) return false; step++; }
			case 23 -> { capture(client, "terminal-bee-icons"); if (!remote) ClientTerminalFixture.done = true; step++; }
			case 24 -> { if (!remote && !ClientTerminalFixture.verified) return false; press(screen, "tab.0"); step++; }
		}
		return complete();
	}
	static boolean member(NetworkCoreScreen screen, NetworkCoreMenu menu) {
		var view = menu.clientState().view(); if (view == null) return false;
		for (int i = 0; i < view.rows().size(); i++) if (!view.rows().get(i).bees().isEmpty()) { click(screen, 80, 48 + i * 11); return true; }
		throw new IllegalStateException("Client missing active apiary row");
	}
	private static boolean product(NetworkCoreScreen screen, NetworkCoreMenu menu, String label, String detail, int mouseButton) {
		var view = menu.clientState().view(); if (view == null) return false;
		for (int i = 0; i < view.rows().size(); i++) {
			var row = view.rows().get(i);
			boolean matches = detail.equals("__plain__") ? !row.detail().contains("minecraft:custom_name") : row.detail().contains(detail);
			if (row.label().equals(label) && matches) { click(screen, 49 + i * 22, 75, mouseButton); return true; }
		}
		require(view.hasNext(), "Client missing product " + label + " " + detail); press(screen, "next"); return false;
	}
	static void chooseSlot(NetworkCoreScreen screen, NetworkCoreMenu menu, int inventorySlot) {
		var slot = menu.slots.stream().filter(value -> value.getContainerSlot() == inventorySlot).findFirst().orElseThrow();
		click(screen, slot.x + 8, slot.y + 8);
	}
	static void result(NetworkCoreMenu menu, TerminalReply.Status status, int moved) {
		var result = menu.clientState().exchangeResult(); require(result != null && result.status() == status && result.moved() == moved,
				"Client result mismatch at step " + step + ": " + result);
		require(menu.clientState().view() == null || menu.terminalReply().sequence() > result.sequence(), "Asset command left clickable stale rows");
	}
	private static boolean autoRefreshed(NetworkCoreMenu menu) {
		return menu.clientState().view() != null && menu.terminalReply().sequence() > menu.clientState().exchangeResult().sequence();
	}
	static void press(NetworkCoreScreen screen, String key) {
		String label = Component.translatable("screen.productivebeesgenesis.network." + key).getString();
		var buttons = screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast).toList();
		var button = buttons.stream().filter(b -> b.getMessage().getString().equals(label)).findFirst()
				.orElseGet(() -> buttons.stream().filter(b -> b.getMessage().getString().endsWith(label)).findFirst()
						.orElseThrow(() -> new IllegalStateException("Missing button " + key + " at " + step)));
		require(button.active && button.visible, "Inactive client button " + key + " at step " + step);
		require(screen.mouseClicked(button.getX() + button.getWidth() / 2.0, button.getY() + button.getHeight() / 2.0, 0), "Button did not receive click");
		screen.mouseReleased(button.getX() + 2, button.getY() + 2, 0);
	}
	private static void click(NetworkCoreScreen screen, int x, int y) {
		click(screen, x, y, 0);
	}
	private static void click(NetworkCoreScreen screen, int x, int y, int mouseButton) {
		double mouseX = (screen.width - NetworkCoreScreen.WIDTH) / 2 + x, mouseY = (screen.height - NetworkCoreScreen.HEIGHT) / 2 + y;
		require(screen.mouseClicked(mouseX, mouseY, mouseButton), "Client click missed at " + step + ": " + x + "," + y);
		screen.mouseReleased(mouseX, mouseY, mouseButton);
	}
	private static void capture(Minecraft client, String name) throws java.io.IOException {
		Files.createDirectories(Path.of("results"));
		try (var screenshot = Screenshot.takeScreenshot(client.getMainRenderTarget())) { screenshot.writeToFile(Path.of("results/" + name + ".png")); }
	}
	private static boolean verifyBeeIcons(Minecraft client, NetworkCoreScreen screen, NetworkCoreMenu menu) {
		if (menu.clientState().view() == null) return false;
		for (var row : menu.clientState().view().rows()) if (row.label().equals("productivebees:configurable_honeycomb")) {
			var components = com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductIconPreview.decode(row.icon());
			var key = new com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey(
					com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey.Kind.ITEM,
					net.minecraft.resources.ResourceLocation.parse(row.label()), components);
			var stack = com.ayoshiko.productivebeesgenesis.apiculture.compat.ProductKeyCodec.item(key, 1, client.level.registryAccess());
			var type = stack.get(cy.jdkdigital.productivebees.init.ModDataComponents.BEE_TYPE.get());
			require(type != null && row.detail().contains(type.toString()), "PB icon lost the exact bee component");
			beeColors.add(client.getItemColors().getColor(stack, 0));
		}
		if (beeColors.size() == 2) return true;
		require(menu.clientState().view().hasNext(), "PB iron and gold comb previews lost their distinct tint");
		press(screen, "next"); return false;
	}
	static void verifyLayout(NetworkCoreScreen screen, NetworkCoreMenu menu) {
		require(screen.width >= NetworkCoreScreen.WIDTH && screen.height >= NetworkCoreScreen.HEIGHT, "Core exceeds minimum GUI viewport");
		require(menu.slots.size() == 36 && menu.slots.stream().allMatch(slot -> slot.isActive() && slot.y >= 154
				&& slot.y + 16 <= NetworkCoreScreen.HEIGHT && slot.x + 16 <= NetworkCoreScreen.WIDTH), "Inventory is not permanently visible below content");
	}
	private ClientTerminalProbe() { }
}
