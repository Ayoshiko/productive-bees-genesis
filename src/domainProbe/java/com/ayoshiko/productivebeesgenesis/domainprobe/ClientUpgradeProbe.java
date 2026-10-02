package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.client.NetworkCoreScreen;
import com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkCoreMenu;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.*;
import java.nio.file.Path;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

/** 实际鼠标点击两个成员与两类升级，所有转移都经注册 C2S/S2C。 */
final class ClientUpgradeProbe {
	private static int step;
	private static int previewWaits;
	private static int nextServerTick;
	static int step() { return step; }
	private static long nextAt;
	static boolean complete() { return step == 25; }
	static boolean advance(Minecraft client, NetworkCoreScreen screen, NetworkCoreMenu menu) throws Exception {
		require(menu.clientState().notice() != TerminalClientState.Notice.TIMEOUT, "Upgrade request timed out at client step " + step);
		if (ClientOwnershipFixture.serverTick < nextServerTick) return false;
		if (Util.getMillis() < nextAt || !menu.clientState().ready(Util.getMillis())) return false;
		String refresh = Component.translatable("screen.productivebeesgenesis.network.refresh").getString();
		String tab = Component.translatable("screen.productivebeesgenesis.network.tab.3").getString();
		if (screen.children().stream().anyMatch(child -> child instanceof Button b
				&& (b.getMessage().getString().equals(refresh) || b.getMessage().getString().equals(tab)) && !b.active)) return false;
		nextAt = Util.getMillis() + 600;
		nextServerTick = ClientOwnershipFixture.serverTick + 8;
		switch (step) {
			case 0 -> { ClientTerminalProbe.press(screen, "tab.3"); step++; }
			case 1 -> { if (!select(screen, menu, "mek_apiary", 0, 0)) return false; ClientTerminalProbe.chooseSlot(screen, menu, 6); ClientTerminalProbe.press(screen, "upgrade_install"); step++; }
			case 2 -> { result(client, menu, 6, 1); ClientTerminalProbe.press(screen, "refresh"); step++; }
			case 3 -> { if (!select(screen, menu, "mek_apiary", 0, 1)) return false; ClientTerminalProbe.chooseSlot(screen, menu, 6); ClientTerminalProbe.press(screen, "upgrade_remove"); step++; }
			case 4 -> { result(client, menu, 6, 2); ClientTerminalProbe.press(screen, "refresh"); step++; }
			case 5 -> { if (!select(screen, menu, "mek_apiary", 8, 0)) return false; ClientTerminalProbe.chooseSlot(screen, menu, 7); ClientTerminalProbe.press(screen, "upgrade_install"); step++; }
			case 6 -> { result(client, menu, 7, 1); ClientTerminalProbe.press(screen, "refresh"); step++; }
			case 7 -> { if (!select(screen, menu, "mek_apiary", 8, 1)) return false; ClientTerminalProbe.chooseSlot(screen, menu, 7); step++; }
			case 8 -> { capture(client, "terminal-upgrades-apiary"); ClientTerminalProbe.press(screen, "upgrade_remove"); step++; }
			case 9 -> { result(client, menu, 7, 2); ClientTerminalProbe.press(screen, "refresh"); step++; }
			case 10 -> { if (!select(screen, menu, "mek_centrifuge", 0, 0)) return false; ClientTerminalProbe.chooseSlot(screen, menu, 6); ClientTerminalProbe.press(screen, "upgrade_install"); step++; }
			case 11 -> { result(client, menu, 6, 1); ClientTerminalProbe.press(screen, "refresh"); step++; }
			case 12 -> { if (!select(screen, menu, "mek_centrifuge", 0, 1)) return false; ClientTerminalProbe.chooseSlot(screen, menu, 6); step++; }
			case 13 -> { capture(client, "terminal-upgrades-centrifuge"); screen.resize(client, 320, 240); ClientTerminalProbe.verifyLayout(screen, menu); ClientTerminalProbe.press(screen, "upgrade_remove"); step++; }
			case 14 -> { result(client, menu, 6, 2); screen.resize(client, client.getWindow().getGuiScaledWidth(), client.getWindow().getGuiScaledHeight()); ClientTerminalProbe.press(screen, "refresh"); step++; }
			case 15 -> { if (!select(screen, menu, "mek_apiary", 0, 0)) return false; ClientTerminalProbe.chooseSlot(screen, menu, 6); ClientTerminalProbe.press(screen, "upgrade_single"); hover(client, screen, "upgrade_install_page"); step++; }
			case 16 -> { if (menu.clientState().preview() == null) {
					capture(client, "terminal-preview-wait");
					require(++previewWaits < 4, "Hover preview missing: mouse=" + client.mouseHandler.xpos() + "," + client.mouseHandler.ypos()
							+ " scale=" + client.getWindow().getGuiScale() + " notice=" + menu.clientState().notice() + " view=" + menu.clientState().view());
					return false;
				}
				require(menu.clientState().preview().movable() == 1 && menu.clientState().preview().after().timeFactor() < menu.clientState().preview().before().timeFactor(), "Client upgrade estimate missing");
				require(client.player.getInventory().getItem(6).getCount() == 2, "Hover preview consumed upgrades"); step++; }
			case 17 -> { capture(client, "terminal-upgrade-preview"); org.lwjgl.glfw.GLFW.glfwSetCursorPos(client.getWindow().getWindow(), 10, 10); ClientTerminalProbe.press(screen, "upgrade_install_page"); step++; }
			case 18 -> { batchResult(client, menu, 0); ClientTerminalProbe.press(screen, "refresh"); step++; }
			case 19 -> { if (!select(screen, menu, "mek_apiary", 0, 1)) return false; ClientTerminalProbe.chooseSlot(screen, menu, 6); ClientTerminalProbe.press(screen, "upgrade_remove_page"); step++; }
			case 20 -> { batchResult(client, menu, 2); step++; }
			case 21 -> { capture(client, "terminal-upgrade-batch"); screen.resize(client, 320, 240); ClientTerminalProbe.verifyLayout(screen, menu); step++; }
			case 22 -> { screen.resize(client, client.getWindow().getGuiScaledWidth(), client.getWindow().getGuiScaledHeight()); ClientTerminalFixture.upgradesDone = true; step++; }
			case 23 -> { if (!ClientTerminalFixture.upgradesVerified) return false; ClientTerminalProbe.press(screen, "tab.0"); step++; }
			case 24 -> step++;
		}
		return complete();
	}
	private static boolean select(NetworkCoreScreen screen, NetworkCoreMenu menu, String machine, int choice, int installed) {
		var view = menu.clientState().view(); if (view == null) return false;
		require(view.kind() == NetworkSelectionSession.Kind.UPGRADES, "Wrong upgrade page kind");
		for (int i = 0; i < view.rows().size(); i++) {
			var row = view.rows().get(i); if (!row.label().startsWith("productivebeesgenesis:" + machine + " @ ")) continue;
			click(screen, 80, 48 + i * 11);
			for (int j = 0; j < row.upgrades().size(); j++) if (row.upgrades().get(j).choice() == choice) {
				require(row.upgrades().get(j).installed() == installed, "Upgrade display count differs from server");
				click(screen, 53 + (j % 5) * 37, 68 + (j / 5) * 25); return true;
			}
			throw new IllegalStateException("Missing upgrade " + choice + " for " + machine + " at step " + step + ": " + row.upgrades());
		}
		throw new IllegalStateException("Missing displayed machine " + machine);
	}
	private static void result(Minecraft client, NetworkCoreMenu menu, int slot, int count) throws Exception {
		ClientTerminalProbe.result(menu, TerminalReply.Status.MOVED, 1);
		int actual = client.player.getInventory().getItem(slot).getCount();
		if (actual != count) capture(client, "terminal-upgrade-failure");
		require(actual == count, "Upgrade inventory differs at step " + step + ", slot " + slot + ": expected " + count + ", actual " + actual);
	}
	private static void batchResult(Minecraft client, NetworkCoreMenu menu, int inventoryCount) {
		ClientTerminalProbe.result(menu, TerminalReply.Status.BATCH_COMPLETE, 2);
		require(menu.clientState().exchangeResult().upgrades().size() == 2 && menu.clientState().exchangeResult().upgrades().stream()
				.allMatch(r -> r.status() == TerminalReply.Status.MOVED && r.moved() == 1), "Client lost per-member batch results");
		require(client.player.getInventory().getItem(6).getCount() == inventoryCount, "Client batch inventory differs");
	}
	private static void hover(Minecraft client, NetworkCoreScreen screen, String key) {
		String label = Component.translatable("screen.productivebeesgenesis.network." + key).getString();
		var button = screen.children().stream().filter(c -> c instanceof Button b && b.getMessage().getString().equals(label)).map(Button.class::cast).findFirst().orElseThrow();
		var scale = client.getWindow().getGuiScale();
		org.lwjgl.glfw.GLFW.glfwFocusWindow(client.getWindow().getWindow());
		org.lwjgl.glfw.GLFW.glfwSetCursorPos(client.getWindow().getWindow(), (button.getX() + button.getWidth() / 2.0) * scale, (button.getY() + 8) * scale);
	}
	private static void click(NetworkCoreScreen screen, int x, int y) {
		double mouseX = (screen.width - NetworkCoreScreen.WIDTH) / 2 + x, mouseY = (screen.height - NetworkCoreScreen.HEIGHT) / 2 + y;
		require(screen.mouseClicked(mouseX, mouseY, 0), "Upgrade widget missed click"); screen.mouseReleased(mouseX, mouseY, 0);
	}
	private static void capture(Minecraft client, String name) throws Exception { try (var image = Screenshot.takeScreenshot(client.getMainRenderTarget())) { image.writeToFile(Path.of("results", name + ".png")); } }
	private ClientUpgradeProbe() { }
}
