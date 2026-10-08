package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.bridge.MeBridgeStatus;
import com.ayoshiko.productivebeesgenesis.apiculture.client.NetworkTerminalScreen;
import com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkCoreMenu;
import com.ayoshiko.productivebeesgenesis.apiculture.me.*;
import java.nio.file.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

final class MeFluidClient {
	private static int previous = -1, step;
	private static long sequence;
	static CraftingClient.Reply advance(Minecraft client, int stage, boolean owner) throws Exception {
		if (!owner) return ack();
		if (previous != stage) { previous = stage; step = 0; }
		if (!(client.player.containerMenu instanceof NetworkCoreMenu menu) || !(client.screen instanceof NetworkTerminalScreen screen)) return null;
		var session = menu.meTerminal(); if (session.waiting()) return null;
		if (stage == 870) {
			if (step == 0) { click(client, screen.getGuiLeft() + 230, screen.getGuiTop() + 15, 0); step++; return null; }
			if (session.view().mode() != MeTerminalView.Mode.STORAGE || session.view().rows().isEmpty()) return null;
			if (step == 1) { var search = client.screen.children().stream().filter(w -> w instanceof EditBox).map(w -> (EditBox) w).findFirst().orElseThrow(); search.setValue("water"); step++; return null; }
			if (step == 2) { if (session.view().rows().size() != 1) return null; clickSlot(client, menu, 27); step++; return null; }
			return menu.getCarried().is(Items.BUCKET) ? ack() : null;
		}
		if (stage >= 871 && stage <= 875) {
			if (step == 0) { sequence = session.sequence(); click(client, screen.getGuiLeft() + 40, screen.getGuiTop() + 59, stage == 872 || stage == 875 ? 1 : 0); require(session.sequence() > sequence, "Fluid click did not send a command"); step++; return null; }
			if (stage == 875) picture(client, "me-fluid-retained.png"); return ack();
		}
		if (stage == 876) {
			if (menu.meStatus() != MeBridgeStatus.NO_CHANNEL) return null;
			if (step == 0) { clickSlot(client, menu, 27); step++; return null; }
			if (step == 1) { if (!menu.getCarried().isEmpty()) return null; clickSlot(client, menu, 28); step++; return null; }
			if (step == 2) { if (!menu.getCarried().is(Items.PAPER)) return null; button(client, "recover_fluid"); step++; return null; }
			if (session.view().receipt().retained() != 0) return null;
			picture(client, "me-fluid-offline-recovered.png"); return ack();
		}
		if (stage == 877) {
			if (step == 0) { clickSlot(client, menu, 28); step++; return null; }
			return menu.getCarried().isEmpty() ? ack() : null;
		}
		return ack();
	}
	private static void button(Minecraft client, String key) {
		String label = Component.translatable("screen.productivebeesgenesis.me_terminal." + key).getString();
		var button = client.screen.children().stream().filter(w -> w instanceof AbstractWidget widget && widget.active && widget.getMessage().getString().equals(label)).map(w -> (AbstractWidget) w).findFirst().orElseThrow();
		click(client, button.getX() + button.getWidth() / 2., button.getY() + button.getHeight() / 2., 0);
	}
	private static void clickSlot(Minecraft client, NetworkCoreMenu menu, int index) { var screen = (NetworkTerminalScreen) client.screen; var slot = menu.slots.get(index); click(client, screen.getGuiLeft() + slot.x + 8, screen.getGuiTop() + slot.y + 8, 0); }
	private static void click(Minecraft client, double x, double y, int button) { require(client.screen.mouseClicked(x, y, button), "Fluid UI click missed"); client.screen.mouseReleased(x, y, button); }
	private static void picture(Minecraft client, String name) throws Exception { Files.createDirectories(Path.of("results")); try (var image = Screenshot.takeScreenshot(client.getMainRenderTarget())) { image.writeToFile(Path.of("results", name)); } }
	private static CraftingClient.Reply ack() { return new CraftingClient.Reply(0, -1); }
	private MeFluidClient() { }
}
