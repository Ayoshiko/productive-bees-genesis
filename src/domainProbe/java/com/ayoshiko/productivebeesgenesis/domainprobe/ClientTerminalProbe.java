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
	private static long nextAt, resizeSequence;
	static boolean complete() { return step == 23; }
	static boolean advance(Minecraft client, NetworkCoreScreen screen, NetworkCoreMenu menu) throws Exception {
		ClientTerminalFixture.requested = true;
		if (!ClientTerminalFixture.ready || Util.getMillis() < nextAt || menu.clientState().waiting() || !menu.clientState().ready(Util.getMillis())) return false;
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
				if (!product(screen, menu, "minecraft:iron_ingot", "__plain__")) return false;
				chooseSlot(screen, menu, 4); click(screen, 240, 143); click(screen, 240, 143); press(screen, "take_product"); step++;
			}
			case 10 -> {
				result(menu, TerminalReply.Status.MOVED, 1); require(client.player.getInventory().getItem(4).getCount() == 64, "Client partial item delivery not synchronized");
				press(screen, "refresh"); step++;
			}
			case 11 -> {
				if (!product(screen, menu, "minecraft:iron_ingot", "__plain__")) return false;
				chooseSlot(screen, menu, 3); press(screen, "take_product"); step++;
			}
			case 12 -> { result(menu, TerminalReply.Status.NO_SPACE, 0); press(screen, "refresh"); step++; }
			case 13 -> { if (!product(screen, menu, "minecraft:water", "")) return false; chooseSlot(screen, menu, 2); press(screen, "take_bucket"); step++; }
			case 14 -> {
				result(menu, TerminalReply.Status.MOVED, 1000); require(client.player.getInventory().getItem(2).is(Items.WATER_BUCKET), "Client bucket not synchronized");
				press(screen, "refresh"); step++;
			}
			case 15 -> { if (!product(screen, menu, "minecraft:iron_ingot", "variant-red")) return false; step++; }
			case 16 -> { capture(client, "terminal-variants"); chooseSlot(screen, menu, 5); press(screen, "take_product"); step++; }
			case 17 -> { result(menu, TerminalReply.Status.MOVED, 2); press(screen, "refresh"); step++; }
			case 18 -> {
				if (menu.clientState().notice() != TerminalClientState.Notice.EXPIRED) return false;
				require(menu.clientState().view() == null, "Expired page retained actions"); capture(client, "terminal-expired"); press(screen, "refresh"); step++;
			}
			case 19 -> {
				require(menu.clientState().view() != null, "Refresh after expiry failed"); resizeSequence = menu.terminalReply().sequence();
				screen.resize(client, client.getWindow().getGuiScaledWidth(), client.getWindow().getGuiScaledHeight());
				press(screen, "refresh"); step++;
			}
			case 20 -> {
				require(menu.terminalReply().sequence() > resizeSequence && menu.clientState().view() != null, "Resize reset menu sequence");
				click(screen, 80, 194); step++;
			}
			case 21 -> { capture(client, "terminal-inventory"); press(screen, "back"); ClientTerminalFixture.done = true; step++; }
			case 22 -> { if (!ClientTerminalFixture.verified) return false; press(screen, "tab.0"); step++; }
		}
		return complete();
	}
	private static boolean member(NetworkCoreScreen screen, NetworkCoreMenu menu) {
		var view = menu.clientState().view(); if (view == null) return false;
		for (int i = 0; i < view.rows().size(); i++) if (!view.rows().get(i).bees().isEmpty()) { click(screen, 80, 75 + i * 14); return true; }
		throw new IllegalStateException("Client missing active apiary row");
	}
	private static boolean product(NetworkCoreScreen screen, NetworkCoreMenu menu, String label, String detail) {
		var view = menu.clientState().view(); if (view == null) return false;
		for (int i = 0; i < view.rows().size(); i++) {
			var row = view.rows().get(i);
			boolean matches = detail.equals("__plain__") ? !row.detail().contains("minecraft:custom_name") : row.detail().contains(detail);
			if (row.label().equals(label) && matches) { click(screen, 80, 75 + i * 14); return true; }
		}
		require(view.hasNext(), "Client missing product " + label + " " + detail); press(screen, "next"); return false;
	}
	private static void chooseSlot(NetworkCoreScreen screen, NetworkCoreMenu menu, int inventorySlot) {
		click(screen, 80, 194);
		var slot = menu.slots.stream().filter(value -> value.getContainerSlot() == inventorySlot).findFirst().orElseThrow();
		click(screen, slot.x + 8, slot.y + 8);
	}
	private static void result(NetworkCoreMenu menu, TerminalReply.Status status, int moved) {
		var result = menu.clientState().result(); require(result != null && result.status() == status && result.moved() == moved,
				"Client result mismatch at step " + step + ": " + result);
		require(menu.clientState().view() == null, "Asset command left clickable stale rows");
	}
	private static void press(NetworkCoreScreen screen, String key) {
		String label = Component.translatable("screen.productivebeesgenesis.network." + key).getString();
		var button = screen.children().stream().filter(child -> child instanceof Button b && b.getMessage().getString().endsWith(label))
				.map(Button.class::cast).findFirst().orElseThrow(() -> new IllegalStateException("Missing button " + key + " at " + step));
		require(button.active && button.visible, "Inactive client button " + key);
		require(screen.mouseClicked(button.getX() + button.getWidth() / 2.0, button.getY() + button.getHeight() / 2.0, 0), "Button did not receive click");
		screen.mouseReleased(button.getX() + 2, button.getY() + 2, 0);
	}
	private static void click(NetworkCoreScreen screen, int x, int y) {
		double mouseX = (screen.width - 318) / 2 + x, mouseY = (screen.height - 236) / 2 + y;
		require(screen.mouseClicked(mouseX, mouseY, 0), "Client click missed at " + step + ": " + x + "," + y);
		screen.mouseReleased(mouseX, mouseY, 0);
	}
	private static void capture(Minecraft client, String name) throws java.io.IOException {
		Files.createDirectories(Path.of("results"));
		try (var screenshot = Screenshot.takeScreenshot(client.getMainRenderTarget())) { screenshot.writeToFile(Path.of("results/" + name + ".png")); }
	}
	private ClientTerminalProbe() { }
}
