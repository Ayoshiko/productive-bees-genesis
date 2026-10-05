package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.client.NetworkTerminalScreen;
import com.ayoshiko.productivebeesgenesis.apiculture.core.*;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.*;
import com.ayoshiko.productivebeesgenesis.multiblock.world.*;
import java.nio.file.*;
import net.minecraft.Util;
import net.minecraft.client.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;
import static com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalRequest.Operation.*;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

final class WirelessClient {
	private static int previous = -1, step;
	private static TerminalRequest pending;
	private static long machineSequence;
	static CraftingClient.Reply advance(Minecraft client, int stage, boolean owner) throws Exception {
		if (previous != stage) { previous = stage; step = 0; }
		if (stage == 349) {
			if (client.screen != null) return null;
			client.options.hideGui = true; if (step++ < 4) return null;
			picture(client, "terminal-panels.png"); client.options.hideGui = false; return ack();
		}
		if (!owner && stage != 330 && stage != 332 && stage != 333 && stage != 342) return ack();
		if (stage == 336 || stage == 338 || stage == 340 || stage == 342 || stage == 346) {
			if (client.player.containerMenu instanceof NetworkCoreMenu || client.player.containerMenu instanceof MachineMenu) return null;
			if (pending != null) PacketDistributor.sendToServer(pending); return ack();
		}
		if (stage == 330 || stage == 337 || stage == 339 || stage == 341 || stage == 343 || stage == 347) {
			if (step == 0) {
				if (!(client.player.getMainHandItem().getItem() instanceof WirelessTerminalItem)) return null;
				client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND); step++; return null;
			}
			if (stage == 343) return client.player.containerMenu instanceof MachineMenu m && m.wireless() ? ack() : null;
			if (!(client.player.containerMenu instanceof NetworkCoreMenu m) || !m.wirelessTerminal()) return null;
			if (stage != 330 && stage != 347) return ack();
			if (!(client.screen instanceof NetworkTerminalScreen screen) || !m.clientState().ready(Util.getMillis())) return null;
			if (step == 1) { ClientTerminalProbe.press(screen, "tab.4"); step++; return null; }
			if (m.craftingGeneration() == 0 || !m.clientState().actionable(Util.getMillis())) return null;
			if (stage == 347) require(m.craftingItem(0).is(Items.OAK_LOG) && m.craftingItem(0).getCount() == 2, "Target switch lost client grid");
			return ack();
		}
		if (stage == 344 || stage == 345) {
			if (!(client.player.containerMenu instanceof MachineMenu m) || m.viewRevision() == 0) return null;
			if (step == 0) {
				machineSequence = m.acknowledged() + 1;
				PacketDistributor.sendToServer(new MachineMenuRequest(m.containerId, m.session(), machineSequence, m.viewRevision(), stage == 344 ? 2 : 3, 0, 0, 1)); step++; return null;
			}
			if (m.acknowledged() != machineSequence) return null;
			require(m.foodCount(0) == (stage == 344 ? 1 : 0), "Wireless machine transfer did not synchronize");
			if (stage == 345) picture(client, "wireless-machine.png"); return ack();
		}
		if (!(client.player.containerMenu instanceof NetworkCoreMenu menu) || !(client.screen instanceof NetworkTerminalScreen screen)) return null;
		var state = menu.clientState(); long now = Util.getMillis();
		if (stage == 334) {
			if (step == 0) { client.gameMode.handleInventoryButtonClick(menu.containerId, 11); step++; return null; }
			return menu.scope() == TerminalScope.CENTRIFUGE ? ack() : null;
		}
		if (stage == 335) {
			if (!state.ready(now)) return null;
			if (step == 0) { ClientTerminalProbe.press(screen, "tab.4"); step++; return null; }
			if (menu.craftingGeneration() == 0) return null; picture(client, "wireless-network.png"); return ack();
		}
		if (stage == 348) return ack();
		if (stage == 332) {
			if (!state.actionable(now) || menu.craftingGeneration() == 0) return null;
			pending = state.beginCrafting(CRAFT_TAKE, menu.craftingGeneration(), -1, -1, 1, now); return pending == null ? null : ack();
		}
		if (step == 0) {
			if (stage == 331) {
				if (!state.actionable(now)) return null;
				pending = state.beginCrafting(CRAFT_IN, menu.craftingGeneration(), 0, 0, 3, now); if (pending == null) return null;
			}
			PacketDistributor.sendToServer(pending); step++; return null;
		}
		var result = state.exchangeResult(); if (result == null || result.sequence() != pending.sequence()) return null;
		return new CraftingClient.Reply(result.moved(), result.status().ordinal());
	}
	private static void picture(Minecraft client, String name) throws Exception {
		Files.createDirectories(Path.of("results")); try (var image = Screenshot.takeScreenshot(client.getMainRenderTarget())) { image.writeToFile(Path.of("results", name)); }
	}
	private static CraftingClient.Reply ack() { return new CraftingClient.Reply(0, -1); }
	private WirelessClient() { }
}
