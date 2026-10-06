package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.client.NetworkTerminalScreen;
import com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkCoreMenu;
import com.ayoshiko.productivebeesgenesis.apiculture.core.WirelessTerminalItem;
import com.ayoshiko.productivebeesgenesis.multiblock.client.MachineScreen;
import com.ayoshiko.productivebeesgenesis.multiblock.world.MachineMenu;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.world.InteractionHand;
import org.lwjgl.glfw.GLFW;

final class MeBridgeClient {
	private static int previous = -1, step;
	static CraftingClient.Reply advance(Minecraft client, int stage, boolean owner) throws Exception {
		if (stage != previous) { previous = stage; step = 0; }
		if (!owner && stage >= 507 && stage <= 510) return ack();
		if (stage == 508) return ack();
		if (stage == 510) return client.player.containerMenu == client.player.inventoryMenu ? ack() : null;
		if (owner && (stage == 507 || stage == 509) && step == 0) {
			if (client.player.containerMenu != client.player.inventoryMenu || !(client.player.getMainHandItem().getItem() instanceof WirelessTerminalItem)) return null;
			client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND); step++; return null;
		}
		var expected = MeBridgeProbe.expected(stage, owner);
		if (stage == 509) {
			if (!(client.player.containerMenu instanceof MachineMenu menu) || menu.meStatus() != expected || !(client.screen instanceof MachineScreen)) return null;
		} else if (!(client.player.containerMenu instanceof NetworkCoreMenu menu) || menu.meStatus() != expected || !(client.screen instanceof NetworkTerminalScreen)) return null;
		if (owner && (stage == 502 || stage == 509)) {
			var screen = (net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>) client.screen;
			double x = screen.getGuiLeft() + (stage == 509 ? 207 : 16);
			double y = screen.getGuiTop() + (stage == 509 ? 136 : 16);
			GLFW.glfwSetCursorPos(client.getWindow().getWindow(), x * client.getWindow().getGuiScale(), y * client.getWindow().getGuiScale());
			if (step++ < 3) return null;
			Files.createDirectories(Path.of("results"));
			try (var picture = Screenshot.takeScreenshot(client.getMainRenderTarget())) { picture.writeToFile(Path.of("results", stage == 509 ? "me-machine.png" : "me-network.png")); }
		}
		return ack();
	}
	private static CraftingClient.Reply ack() { return new CraftingClient.Reply(0, -1); }
}
