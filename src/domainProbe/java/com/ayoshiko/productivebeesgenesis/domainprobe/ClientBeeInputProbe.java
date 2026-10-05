package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.client.NetworkTerminalScreen;
import com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkCoreMenu;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import java.nio.file.Path;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

final class ClientBeeInputProbe {
	private static int step;
	private static long next;
	private static int worldScenario;
	private static boolean clicked;
	static boolean complete() { return step == 12; }
	static void advance(Minecraft client) throws Exception {
		if (System.currentTimeMillis() < next) return;
		next = System.currentTimeMillis() + 200;
		if (step == 0) { ClientBeeInputFixture.requested = true; step++; return; }
		if (!ClientBeeInputFixture.ready) return;
		if (step == 8) { if (ClientBeeInputFixture.applied != 3) return; ClientBeeInputFixture.command = 5; step++; return; }
		if (step == 9) { world(client); return; }
		if (step == 10) { if (ClientBeeInputFixture.applied != 14) return; ClientBeeInputFixture.command = 4; step++; return; }
		if (step == 11) { if (ClientBeeInputFixture.verified) step++; return; }
		if (!(client.screen instanceof NetworkTerminalScreen screen) || !(client.player.containerMenu instanceof NetworkCoreMenu menu)
				|| !menu.clientState().actionable(net.minecraft.Util.getMillis()) || menu.clientState().view() == null) return;
		var view = menu.clientState().view();
		if (step == 1) {
			require(view.rows().size() == 8 && view.hasNext(), "Capacity page did not cover nine hives");
			var top = view.rows().getFirst().location(); require(top.x() == 41 && top.z() == 43, "Effective capacity did not beat energy-only upgrades");
			ClientTerminalProbe.verifyLayout(screen, menu);
			try (var shot = Screenshot.takeScreenshot(client.getMainRenderTarget())) { shot.writeToFile(Path.of("results/automatic-bee-capacity.png")); }
			ClientTerminalProbe.chooseSlot(screen, menu, 0); ClientTerminalProbe.press(screen, "terminal.auto_bee"); step++;
		} else if (step == 2) {
			require(menu.clientState().exchangeResult() != null && menu.clientState().exchangeResult().status() == TerminalReply.Status.MOVED, "Cage auto input failed");
			ClientBeeInputFixture.command = 1; step++;
		} else if (step == 3) {
			if (ClientBeeInputFixture.applied != 1) return;
			ClientTerminalProbe.press(screen, "terminal.sort_capacity"); step++;
		} else if (step == 4) {
			var first = view.rows().getFirst().location(); require(first.x() == 39 && first.z() == 41, "Position sort failed");
			ClientTerminalProbe.chooseSlot(screen, menu, 1); ClientTerminalProbe.press(screen, "terminal.auto_bee"); step++;
		} else if (step == 5) {
			require(menu.clientState().exchangeResult() != null && menu.clientState().exchangeResult().status() == TerminalReply.Status.MOVED, "Egg auto input failed");
			ClientBeeInputFixture.command = 2; step++;
		} else if (step == 6) {
			if (ClientBeeInputFixture.applied != 2) return;
			screen.resize(client, 320, 240); ClientTerminalProbe.verifyLayout(screen, menu); step++;
		} else if (step == 7) {
			try (var shot = Screenshot.takeScreenshot(client.getMainRenderTarget())) { shot.writeToFile(Path.of("results/automatic-bee-compact.png")); }
			ClientBeeInputFixture.command = 3; step++;
		}
	}

	private static void world(Minecraft client) throws Exception {
		if (worldScenario == 8) {
			if (ClientBeeInputFixture.applied != 13) return;
			ClientBeeInputFixture.command = 14; step++; return;
		}
		if (!clicked) {
			if (ClientBeeInputFixture.applied != 5 + worldScenario || client.screen != null) return;
			var pos = switch (worldScenario) {
				case 0 -> ClientBeeInputFixture.PLAIN; case 2 -> ClientBeeInputFixture.COMBINED;
				case 4 -> ClientBeeInputFixture.CENTRIFUGE; case 7 -> ClientBeeInputFixture.STANDALONE;
				default -> ClientBeeInputFixture.POS.north();
			};
			var hand = worldScenario == 1 ? net.minecraft.world.InteractionHand.OFF_HAND : net.minecraft.world.InteractionHand.MAIN_HAND;
			client.player.getInventory().selected = 0;
			client.player.setShiftKeyDown(true);
			client.player.connection.send(new net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket(client.player,
					net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY));
			client.gameMode.useItemOn(client.player, hand, new net.minecraft.world.phys.BlockHitResult(pos.getCenter(), net.minecraft.core.Direction.UP, pos, false));
			client.player.setShiftKeyDown(false);
			client.player.connection.send(new net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket(client.player,
					net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY));
			clicked = true; next = System.currentTimeMillis() + 800; return;
		}
		require(client.screen == null && client.player.containerMenu == client.player.inventoryMenu, "World input opened client menu at " + worldScenario);
		if (worldScenario == 0) require(client.player.getInventory().getItem(0).getCount() == 3, "World main hand did not synchronize");
		if (worldScenario == 1) {
			require(client.player.getOffhandItem().is(cy.jdkdigital.productivebees.init.ModItems.STURDY_BEE_CAGE.get())
					&& client.player.getOffhandItem().get(net.minecraft.core.component.DataComponents.CUSTOM_DATA) == null, "World offhand empty cage did not synchronize");
			try (var shot = Screenshot.takeScreenshot(client.getMainRenderTarget())) { shot.writeToFile(Path.of("results/world-bee-offhand.png")); }
		}
		if (worldScenario >= 2 && worldScenario <= 6) require(client.player.getInventory().getItem(0).getCount() == 2, "World rejected or combined input count differs at " + worldScenario);
		if (worldScenario == 7) require(client.player.getInventory().getItem(0).getCount() == 1, "Standalone world egg did not synchronize");
		ClientBeeInputFixture.command = 6 + worldScenario; worldScenario++; clicked = false;
	}
	private ClientBeeInputProbe() { }
}
