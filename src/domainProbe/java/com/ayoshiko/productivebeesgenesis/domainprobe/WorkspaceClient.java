package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.client.NetworkTerminalScreen;
import com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkCoreMenu;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.*;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

final class WorkspaceClient {
	private static int previous = -1, step;
	private static long readyAt;
	static CraftingClient.Reply advance(Minecraft client, int stage, boolean owner) throws Exception {
		if (previous != stage) { previous = stage; step = 0; }
		if (!(client.screen instanceof NetworkTerminalScreen screen) || !(client.player.containerMenu instanceof NetworkCoreMenu menu)) return null;
		var primary = menu.clientState(); var products = menu.productState(); long now = Util.getMillis();
		if (now < readyAt || !primary.ready(now) || products.waiting()) return null;
		ClientTerminalProbe.verifyLayout(screen, menu);
		if (!owner) return ack();
		if (stage != 706 && !(stage == 707 && step == 0) && (products.view() == null || !products.actionable(now))) return null;
		switch (stage) {
			case 700 -> {
				require(screen.getXSize() == NetworkTerminalScreen.WORKSPACE_WIDTH && menu.slots.subList(36,46).stream().allMatch(s -> s.isActive()), "Workspace not visible");
				require(primary.view() != null && primary.view().kind() != NetworkSelectionSession.Kind.PRODUCTS && products.view().kind() == NetworkSelectionSession.Kind.PRODUCTS, "Views replaced each other");
				picture(client, "workspace-bees.png"); return ack();
			}
			case 701 -> {
				if (step == 0) { var box = search(screen); screen.mouseClicked(box.getX() + 5, box.getY() + 5, 0); box.setValue("minecraft:iron"); step++; return null; }
				if (products.view().rows().size() != 1 || !products.view().rows().getFirst().label().equals("minecraft:iron_ingot")) return null;
				if (step == 1) { for (char c : "_ingot".toCharArray()) screen.charTyped(c, 0); require(search(screen).getValue().equals("minecraft:iron_ingot"), "Product search lost typing focus after refresh"); readyAt = now + 500; step++; return null; }
				if (step == 2) { click(screen, 320, 80, 1); step++; return null; }
				require(products.exchangeResult() != null && products.exchangeResult().moved() == 1, "Workspace click did not take one item"); return ack();
			}
			case 702 -> {
				if (step == 0) { var request = products.begin(TerminalRequest.Operation.UPGRADE_INSTALL, 0, 0, 0, 1, now); if (request == null) return null; PacketDistributor.sendToServer(request); step++; return null; }
				require(products.exchangeResult().status() == TerminalReply.Status.INVALID, "Product view accepted management command"); return ack();
			}
			case 703 -> {
				if (step == 0) { ClientTerminalProbe.press(screen, "tab.3"); step++; return null; }
				if (primary.view() == null || primary.view().kind() != NetworkSelectionSession.Kind.UPGRADES || !primary.actionable(now)) return null;
				require(products.view().rows().size() == 1 && products.view().rows().getFirst().label().equals("minecraft:iron_ingot"), "Management switch reset product filter");
				if (step == 1) {
					var member = screen.children().stream().filter(net.minecraft.client.gui.components.Button.class::isInstance).map(net.minecraft.client.gui.components.Button.class::cast).filter(b -> b.getMessage().getString().startsWith("#1 ")).findFirst().orElseThrow(() -> new IllegalStateException("Upgrade member list invisible"));
					screen.mouseClicked(member.getX() + 8, member.getY() + 7, 0); step++; return null;
				}
				require(screen.children().stream().anyMatch(c -> c instanceof net.minecraft.client.gui.components.Button b && b.getMessage().equals(net.minecraft.network.chat.Component.translatable("screen.productivebeesgenesis.network.upgrade_install"))), "Upgrade details invisible");
				picture(client, "workspace-upgrades.png"); return ack();
			}
			case 704 -> {
				if (menu.craftingGeneration() == 0 || !primary.actionable(now)) return null;
				if (step == 0) { ClientTerminalProbe.chooseSlot(screen, menu, 0); var slot = menu.slots.get(36); click(screen, slot.x + 8, slot.y + 8, 0); step++; return null; }
				if (!menu.craftingItem(0).is(Items.OAK_LOG)) return null;
				picture(client, "workspace-crafting.png"); return ack();
			}
			case 705 -> {
				if (!primary.actionable(now)) return null;
				if (step == 0) { ClientTerminalProbe.press(screen, "terminal.craft_clear"); step++; return null; }
				return menu.craftingItem(0).isEmpty() && client.player.getInventory().countItem(Items.OAK_LOG) == 2 ? ack() : null;
			}
			case 706 -> {
				if (step == 0) { screen.resize(client, 320, 240); step++; return null; }
				require(screen.getXSize() == 304, "Compact fallback absent"); ClientTerminalProbe.verifyLayout(screen, menu); return ack();
			}
			case 707 -> {
				if (step == 0) { screen.resize(client, client.getWindow().getGuiScaledWidth(), client.getWindow().getGuiScaledHeight()); step++; return null; }
				require(screen.getXSize() == NetworkTerminalScreen.WORKSPACE_WIDTH, "Workspace failed to reopen");
				picture(client, "workspace-restored.png"); return ack();
			}
		}
		return null;
	}
	private static EditBox search(NetworkTerminalScreen screen) { return screen.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).filter(b -> b.getX() > screen.getGuiLeft() + 300).findFirst().orElseThrow(); }
	private static void click(NetworkTerminalScreen screen, int x, int y, int button) { require(screen.mouseClicked(screen.getGuiLeft() + x, screen.getGuiTop() + y, button), "Workspace click missed"); screen.mouseReleased(screen.getGuiLeft() + x, screen.getGuiTop() + y, button); }
	private static void picture(Minecraft client, String name) throws Exception { try (var image = Screenshot.takeScreenshot(client.getMainRenderTarget())) { image.writeToFile(java.nio.file.Path.of("results", name)); } }
	private static CraftingClient.Reply ack() { return new CraftingClient.Reply(0,-1); }
}
