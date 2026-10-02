package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.client.NetworkCoreScreen;
import com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkCoreMenu;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.*;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import static com.ayoshiko.productivebeesgenesis.domainprobe.ClientTerminalProbe.*;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

/** 实际 GUI 选择和提交；仅拒绝／重放场景主动发保留的正式请求。 */
final class UpgradeCompetitionClient {
	private static int activeStage = -1, step;
	private static TerminalRequest pending, old;
	static CompetitionSignal advance(Minecraft client, int stage, boolean owner) throws Exception {
		if (activeStage != stage) {
			activeStage = stage; step = 0;
			org.lwjgl.glfw.GLFW.glfwSetCursorPos(client.getWindow().getWindow(), 5, 5);
		}
		if (stage == 101 || stage == 111 || stage == 113) {
			if (owner) {
				var pos = PlayerLoginServerProbe.POS;
				client.getConnection().sendCommand("pbgnetwork access " + pos.getX() + " " + pos.getY() + " " + pos.getZ()
						+ " upgrades " + (stage == 111 ? "revoke " : "grant ") + CompetitionServerProbe.GUEST);
			}
			return ack(stage, 0, -1);
		}
		if (stage == 112 || stage == 114) {
			if (!owner) { require(old != null, "Missing revoked upgrade request"); PacketDistributor.sendToServer(old); }
			return ack(stage, 0, -2);
		}
		if (!(client.screen instanceof NetworkCoreScreen screen) || !(client.player.containerMenu instanceof NetworkCoreMenu menu)) return null;
		if (menu.canManage() != owner || menu.canUpgrade() != (stage > 101 || owner)) return null;
		if (stage == 104 || stage == 107) { PacketDistributor.sendToServer(pending); return ack(stage, 0, -2); }
		if (stage == 100 && owner) return ack(stage, 0, -1);
		if (stage == 110 && owner) return ack(stage, 0, -1);
		boolean commit = stage == 103 || stage == 106 || stage == 109 || stage == 116 || stage == 118 || stage == 120 || stage == 122 || stage == 124;
		if (commit) {
			if (step == 0) {
				if (!ready(screen, menu)) return null;
				require(pending != null && menu.clientState().view() != null && pending.generation() == menu.clientState().view().generation(),
						"Upgrade selection expired before race");
				pending = new TerminalRequest(pending.containerId(), pending.session(), menu.terminalReply().sequence() + 1, pending.operation(),
						pending.generation(), pending.row(), pending.targetSlot(), pending.inventorySlot(), pending.amount());
				press(screen, TerminalRequest.upgradeInstalling(pending.operation()) ? "upgrade_install" : "upgrade_remove");
				step++; return null;
			}
			var reply = menu.clientState().exchangeResult(); if (reply == null) return null;
			require(reply.sequence() == pending.sequence(), "Unexpected upgrade reply sequence: expected=" + pending.sequence() + " actual=" + reply.sequence());
			return ack(stage, reply.moved(), reply.status().ordinal());
		}
		if (!ready(screen, menu)) return null;
		if (step == 0) { press(screen, menu.memberScoped() ? "refresh" : "tab.3"); step++; return null; }
		if (step == 2) {
			var reply = menu.clientState().exchangeResult(); if (reply == null) return null;
			require(reply.status() == TerminalReply.Status.UNAVAILABLE && reply.moved() == 0, "Ordinary guest modified an upgrade");
			return ack(stage, 0, reply.status().ordinal());
		}
		var view = menu.clientState().view(); if (view == null) return null;
		String machine = stage == 121 || !owner && stage >= 108 ? "mek_centrifuge" : "mek_apiary";
		int choice = stage == 108 || stage == 110 || stage == 115 ? 6 : 0;
		int slot = choice == 6 ? 21 : stage == 119 ? 23 : stage == 121 ? 22 : 20;
		int rowIndex = -1;
		for (int i = 0; i < view.rows().size(); i++) if (view.rows().get(i).label().startsWith("productivebeesgenesis:" + machine + " @ ")) { rowIndex = i; break; }
		require(rowIndex >= 0 && view.kind() == NetworkSelectionSession.Kind.UPGRADES, "Missing upgrade member");
		var row = view.rows().get(rowIndex);
		click(screen, 80, 48 + rowIndex * 11);
		int choiceIndex = -1; for (int i = 0; i < row.upgrades().size(); i++) if (row.upgrades().get(i).choice() == choice) choiceIndex = i;
		require(choiceIndex >= 0, "Missing upgrade choice");
		click(screen, 53 + choiceIndex % 5 * 37, 68 + choiceIndex / 5 * 25); chooseSlot(screen, menu, slot);
		var operation = stage == 105 || stage == 110 || stage == 115 || stage == 119 || stage == 121
				? TerminalRequest.Operation.UPGRADE_REMOVE : TerminalRequest.Operation.UPGRADE_INSTALL;
		pending = new TerminalRequest(menu.containerId, menu.terminalSession(), menu.terminalReply().sequence() + 1, operation,
				view.generation(), rowIndex, choice, slot, 1);
		if (stage == 100) {
			require(!menu.canUpgrade() && !menu.canManage(), "Ordinary guest acquired upgrade role");
			var request = menu.clientState().begin(operation, rowIndex, choice, slot, 1, Util.getMillis());
			require(request != null, "Cannot prepare unauthorized probe"); PacketDistributor.sendToServer(request); step = 2; return null;
		}
		if (stage == 110) old = pending;
		if (stage == 115 && !owner) {
			require(menu.memberScoped() && menu.canUpgrade() && !menu.canManage(), "Granted member proxy role differs");
			java.nio.file.Files.createDirectories(java.nio.file.Path.of("results"));
			try (var image = Screenshot.takeScreenshot(client.getMainRenderTarget())) { image.writeToFile(java.nio.file.Path.of("results/upgrade-guest-proxy.png")); }
		}
		return ack(stage, 0, -1);
	}
	private static boolean ready(NetworkCoreScreen screen, NetworkCoreMenu menu) {
		if (!menu.clientState().ready(Util.getMillis())) return false;
		String refresh = Component.translatable("screen.productivebeesgenesis.network.refresh").getString();
		return screen.children().stream().noneMatch(c -> c instanceof Button b && b.getMessage().getString().equals(refresh) && !b.active);
	}
	private static void click(NetworkCoreScreen screen, int x, int y) {
		double px = (screen.width - NetworkCoreScreen.WIDTH) / 2 + x, py = (screen.height - NetworkCoreScreen.HEIGHT) / 2 + y;
		require(screen.mouseClicked(px, py, 0), "Upgrade race widget click missed"); screen.mouseReleased(px, py, 0);
	}
	private static CompetitionSignal ack(int stage, int moved, int status) { return new CompetitionSignal(stage, moved, status); }
	private UpgradeCompetitionClient() {}
}
