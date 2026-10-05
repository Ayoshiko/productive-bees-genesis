package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.client.NetworkTerminalScreen;
import com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkCoreMenu;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.*;
import java.nio.file.*;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;
import static com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalRequest.Operation.*;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

/** 实际屏幕和正式 C2S 路径；只有竞争／重放故意保留待发请求。 */
final class CraftingClient {
	record Reply(int moved, int status) { }
	private static int last = -1, step, filled;
	private static TerminalRequest pending;
	static Reply advance(Minecraft client, int stage, boolean owner) throws Exception {
		if (last != stage) { last = stage; step = 0; filled = 0; }
		if (stage == 304 || stage == 321) { if (pending != null) PacketDistributor.sendToServer(pending); return ack(); }
		if (!(client.screen instanceof NetworkTerminalScreen screen) || !(client.player.containerMenu instanceof NetworkCoreMenu menu)) return null;
		var state = menu.clientState(); long now = Util.getMillis();
		if (stage == 300 || stage == 322 || stage == 324 || stage == 80) {
			if (!state.ready(now)) return null;
			if (step == 0) { ClientTerminalProbe.press(screen, "tab.4"); step++; return null; }
			if (menu.craftingGeneration() == 0 || !state.actionable(now)) return null;
			if (stage == 322) require(menu.craftingItem(0).is(Items.OAK_LOG) && menu.craftingItem(0).getCount() == 3, "Rebuilt terminal lost real material grid");
			if (stage == 80) require(menu.craftingStatus() == 3 && menu.craftingItem(9).is(Items.OAK_PLANKS) && menu.craftingItem(9).getCount() == 4, "Restored callback result was not retained");
			return ack();
		}
		if (stage == 323 || stage == 328) {
			if (stage == 323 && step == 0) { screen.resize(client, 320, 240); ClientTerminalProbe.verifyLayout(screen, menu); step++; return null; }
			Files.createDirectories(Path.of("results"));
			try (var image = Screenshot.takeScreenshot(client.getMainRenderTarget())) { image.writeToFile(Path.of("results", stage == 323 ? "crafting-materials.png" : "crafting-retained.png")); }
			return ack();
		}
		if (!owner && stage != 302 && stage != 303 && stage != 315) return ack();
		if (stage == 307 || stage == 319) return ack();
		if (stage == 302) {
			if (!state.actionable(now)) return null;
			pending = state.beginCrafting(CRAFT_TAKE, menu.craftingGeneration(), -1, -1, 1, now); return pending == null ? null : ack();
		}
		if (stage == 315) {
			if (!state.ready(now)) return null;
			var bad = state.beginCrafting(CRAFT_TAKE, 0, -1, -1, 1, now); if (bad == null) return null;
			PacketDistributor.sendToServer(bad); return ack();
		}
		if (stage == 309 || stage == 317) {
			if (step == 1) {
				var reply = state.exchangeResult(); if (reply == null || reply.sequence() != pending.sequence()) return null;
				require(reply.status() == TerminalReply.Status.MOVED && reply.moved() == 1, "Recipe input transfer failed: " + reply); filled++; step = 0;
			}
			if (filled == 9) return ack();
			if (!state.actionable(now) || menu.craftingGeneration() == 0) return null;
			pending = state.beginCrafting(CRAFT_IN, menu.craftingGeneration(), filled, filled, 1, now);
			if (pending != null) { PacketDistributor.sendToServer(pending); step = 1; } return null;
		}
		if (step == 0) {
			if (stage != 303 && (!state.actionable(now) || menu.craftingGeneration() == 0)) return null;
			if (stage == 301 || stage == 310 || stage == 308) {
				var operation = stage == 301 ? CRAFT_IN : stage == 310 ? CRAFT_TAKE : CRAFT_CLEAR;
				pending = new TerminalRequest(menu.containerId, menu.terminalSession(), state.acknowledgedSequence() + 1, operation,
						menu.craftingGeneration(), stage == 301 ? 0 : -1, -1, stage == 301 ? 0 : -1, stage == 308 ? 0 : 1);
				if (stage == 308) ClientTerminalProbe.press(screen, "terminal.craft_clear");
				else {
					var slot = menu.slots.get(stage == 301 ? 36 : 45); int h = Math.max(236, Math.min(332, screen.height - 4));
					double x = (screen.width - NetworkTerminalScreen.WIDTH) / 2 + slot.x + 8, y = (screen.height - h) / 2 + slot.y + 8;
					require(screen.mouseClicked(x, y, 0), "Crafting click missed"); screen.mouseReleased(x, y, 0);
				}
				step++; return null;
			}
			if (stage != 303) {
				var operation = stage == 301 || stage == 305 || stage == 312 || stage == 320 || stage == 325 ? CRAFT_IN
						: stage == 308 || stage == 311 || stage == 314 || stage == 316 ? CRAFT_CLEAR : CRAFT_TAKE;
				int count = operation == CRAFT_CLEAR ? 0 : stage == 305 ? 64 : stage == 306 ? 8 : stage == 320 ? 3 : 1;
				pending = state.beginCrafting(operation, menu.craftingGeneration(), operation == CRAFT_IN ? 0 : -1, operation == CRAFT_IN ? 0 : -1, count, now);
				if (pending == null) return null;
			}
			PacketDistributor.sendToServer(pending); step++; return null;
		}
		var result = state.exchangeResult(); if (result == null || result.sequence() != pending.sequence()) return null;
		return new Reply(result.moved(), result.status().ordinal());
	}
	private static Reply ack() { return new Reply(0, -1); }
	private CraftingClient() { }
}
