package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.client.NetworkCoreScreen;
import com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkCoreMenu;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.*;
import java.nio.file.Path;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

/** 实际方块右键与注册菜单路径；两类机器都完成单成员升级，离心机操作时玩家远离核心。 */
final class ClientMemberProxyProbe {
	private static int step, observedStep = -1, nextServerTick;
	private static long nextAt, stepStarted;
	static boolean complete() { return step == 34; }
	static boolean advance(Minecraft client) throws Exception {
		long now = Util.getMillis();
		if (observedStep != step) { observedStep = step; stepStarted = now; }
		require(now - stepStarted < 15000, "Member proxy client stalled at " + step);
		if (now < nextAt || ClientOwnershipFixture.serverTick < nextServerTick) return false;
		var menu = client.player.containerMenu instanceof NetworkCoreMenu value ? value : null;
		var screen = client.screen instanceof NetworkCoreScreen value ? value : null;
		if (menu != null && (!menu.clientState().ready(now) || menu.clientState().waiting())) return false;
		nextAt = now + 600; nextServerTick = ClientOwnershipFixture.serverTick + 8;
		switch (step) {
			case 0 -> { client.player.closeContainer(); step++; }
			case 1 -> { interact(client, new BlockPos(9, 100, 8)); step++; }
			case 2 -> { if (!ready(screen, menu, "mek_apiary", "upgrade_install")) return false; ClientTerminalProbe.chooseSlot(screen, menu, 6); ClientTerminalProbe.press(screen, "upgrade_install"); step++; }
			case 3 -> { result(client, menu, 1); ClientTerminalProbe.press(screen, "refresh"); step++; }
			case 4 -> { if (!ready(screen, menu, "mek_apiary", "upgrade_remove")) return false; capture(client, "terminal-member-apiary"); ClientTerminalProbe.press(screen, "upgrade_remove"); step++; }
			case 5 -> { result(client, menu, 2); client.player.closeContainer(); step++; }
			case 6 -> { interact(client, new BlockPos(10, 100, 8)); step++; }
			case 7 -> { if (!ready(screen, menu, "mek_centrifuge", "upgrade_install")) return false; ClientMemberProxyFixture.positionRequest = 1; step++; }
			case 8 -> { if (ClientMemberProxyFixture.positionApplied != 1 || client.player.distanceToSqr(new BlockPos(8, 100, 8).getCenter()) <= 64) return false;
				if (!ready(screen, menu, "mek_centrifuge", "upgrade_install")) return false; ClientTerminalProbe.chooseSlot(screen, menu, 6); ClientTerminalProbe.press(screen, "upgrade_install"); step++; }
			case 9 -> { result(client, menu, 1); ClientTerminalProbe.press(screen, "refresh"); step++; }
			case 10 -> { if (!ready(screen, menu, "mek_centrifuge", "upgrade_remove")) return false; capture(client, "terminal-member-centrifuge"); ClientTerminalProbe.press(screen, "upgrade_remove"); step++; }
			case 11 -> { result(client, menu, 2); ClientMemberProxyFixture.positionRequest = 2; step++; }
			case 12 -> { if (ClientMemberProxyFixture.positionApplied != 2) return false; client.player.closeContainer(); step++; }
			case 13 -> { interact(client, new BlockPos(8, 100, 8)); step++; }
			case 14 -> { if (menu == null || screen == null || menu.memberScoped() || !menu.canManage()) return false; ClientMemberProxyFixture.done = true; step++; }
			case 15 -> { if (!ClientMemberProxyFixture.verified) return false; step++; }
			case 16 -> { ClientMemberProxyFixture.terminalsRequested = true; step++; }
			case 17 -> { if (!ClientMemberProxyFixture.terminalsReady) return false; client.player.closeContainer(); step++; }
			case 18 -> { interact(client, DedicatedTerminalChecks.BEE); step++; }
			case 19 -> { if (!terminal(screen, menu, TerminalScope.APIARY)) return false; ClientTerminalProbe.press(screen, "tab.3"); step++; }
			case 20 -> { if (!selectTerminal(screen, menu)) return false; ClientTerminalProbe.chooseSlot(screen, menu, 6); ClientTerminalProbe.press(screen, "upgrade_install"); step++; }
			case 21 -> { terminalResult(client, menu, 1); ClientTerminalProbe.press(screen, "refresh"); step++; }
			case 22 -> { if (!selectTerminal(screen, menu)) return false; step++; }
			case 23 -> { capture(client, "dedicated-bee-terminal"); ClientTerminalProbe.press(screen, "upgrade_remove"); step++; }
			case 24 -> { terminalResult(client, menu, 2); client.player.closeContainer(); step++; }
			case 25 -> { interact(client, DedicatedTerminalChecks.CENTRIFUGE); step++; }
			case 26 -> { if (!terminal(screen, menu, TerminalScope.CENTRIFUGE) || !selectTerminal(screen, menu)) return false;
				ClientTerminalProbe.chooseSlot(screen, menu, 6); ClientTerminalProbe.press(screen, "upgrade_install"); step++; }
			case 27 -> { terminalResult(client, menu, 1); ClientTerminalProbe.press(screen, "refresh"); step++; }
			case 28 -> { if (!selectTerminal(screen, menu)) return false; step++; }
			case 29 -> { capture(client, "dedicated-centrifuge-terminal"); ClientTerminalProbe.press(screen, "upgrade_remove"); step++; }
			case 30 -> { terminalResult(client, menu, 2); ClientTerminalProbe.press(screen, "tab.2"); step++; }
			case 31 -> { require(menu.clientState().view() != null && menu.clientState().view().kind() == NetworkSelectionSession.Kind.PRODUCTS
					&& !menu.clientState().view().rows().isEmpty(), "Dedicated terminal shared products missing");
				screen.resize(client, 320, 240); ClientTerminalProbe.verifyLayout(screen, menu);
				client.player.closeContainer(); step++; }
			case 32 -> { interact(client, new BlockPos(8, 100, 8)); step++; }
			case 33 -> { if (menu == null || screen == null || menu.dedicatedTerminal() || !menu.canManage()) return false;
				ClientMemberProxyFixture.terminalsDone = true; if (!ClientMemberProxyFixture.terminalsVerified) return false; step++; }
		}
		return complete();
	}
	private static boolean ready(NetworkCoreScreen screen, NetworkCoreMenu menu, String machine, String action) {
		if (screen == null || menu == null || !menu.memberScoped() || menu.clientState().view() == null) return false;
		var view = menu.clientState().view();
		require(view.kind() == NetworkSelectionSession.Kind.UPGRADES && view.rows().size() == 1 && !view.hasNext()
				&& view.rows().getFirst().label().startsWith("productivebeesgenesis:" + machine + " @ "), "Member UI has wrong selection scope");
		String label = Component.translatable("screen.productivebeesgenesis.network." + action).getString();
		for (String forbidden : new String[]{"tab.0", "tab.1", "tab.2", "upgrade_single", "upgrade_page"}) {
			String text = Component.translatable("screen.productivebeesgenesis.network." + forbidden).getString();
			require(screen.children().stream().noneMatch(child -> child instanceof Button b && b.getMessage().getString().equals(text)), "Proxy exposed whole-core controls");
		}
		return screen.children().stream().anyMatch(child -> child instanceof Button b && b.getMessage().getString().equals(label) && b.active);
	}
	private static boolean terminal(NetworkCoreScreen screen, NetworkCoreMenu menu, TerminalScope scope) {
		if (screen == null || menu == null || menu.clientState().view() == null) return false;
		require(menu.scope() == scope && !menu.memberScoped() && !menu.canManage(), "Dedicated terminal role incorrect");
		for (var row : menu.clientState().view().rows()) require(row.label().startsWith(scope.machine() + " @ "), "Dedicated terminal crossed type");
		String overview = Component.translatable("screen.productivebeesgenesis.network.tab.0").getString();
		require(screen.children().stream().noneMatch(child -> child instanceof Button b && b.getMessage().getString().equals(overview)), "Dedicated terminal exposed overview");
		return true;
	}
	private static boolean selectTerminal(NetworkCoreScreen screen, NetworkCoreMenu menu) {
		if (screen == null || menu == null || menu.clientState().view() == null) return false;
		var view = menu.clientState().view(); require(view.kind() == NetworkSelectionSession.Kind.UPGRADES && view.rows().size() == 1, "Dedicated upgrade page missing");
		var row = screen.children().stream().filter(child -> child instanceof Button b && b.getMessage().getString().startsWith("#1 ")).findFirst();
		if (row.isEmpty()) return false; ((Button) row.get()).onPress(); return true;
	}
	private static void terminalResult(Minecraft client, NetworkCoreMenu menu, int count) {
		require(menu != null && menu.dedicatedTerminal(), "Dedicated terminal closed during exchange");
		ClientTerminalProbe.result(menu, TerminalReply.Status.MOVED, 1);
		require(client.player.getInventory().getItem(6).getCount() == count, "Dedicated terminal inventory mismatch");
	}
	private static void result(Minecraft client, NetworkCoreMenu menu, int count) {
		require(menu != null && menu.memberScoped(), "Proxy closed during member exchange"); ClientTerminalProbe.result(menu, TerminalReply.Status.MOVED, 1);
		require(client.player.getInventory().getItem(6).getCount() == count, "Member proxy inventory mismatch");
	}
	private static void interact(Minecraft client, BlockPos pos) {
		require(client.screen == null, "Expected a closed GUI before block interaction");
		client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, new BlockHitResult(pos.getCenter(), Direction.UP, pos, false));
	}
	private static void capture(Minecraft client, String name) throws Exception {
		try (var image = Screenshot.takeScreenshot(client.getMainRenderTarget())) { image.writeToFile(Path.of("results", name + ".png")); }
	}
	private ClientMemberProxyProbe() { }
}
