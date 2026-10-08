package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.client.*;
import com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkCoreMenu;
import com.ayoshiko.productivebeesgenesis.apiculture.me.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;
import java.nio.file.*;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

final class MeInventoryClient {
	private static int previous = -1, step, age;
	private static net.minecraft.world.item.ItemStack pageItem;
	static CraftingClient.Reply advance(Minecraft client, int stage, boolean owner) throws Exception {
		if (!owner) return ack();
		if (previous != stage) { previous = stage; step = 0; age = 0; }
		if (++age < 4) return null;
		if (!(client.player.containerMenu instanceof NetworkCoreMenu menu)) return null;
		var session = menu.meTerminal();
		if (stage >= 861) return ack();
		if (session.waiting()) return null;
		if (stage == 850) {
			if (step == 0) { var screen = (NetworkTerminalScreen) client.screen; click(client, screen.getGuiLeft() + 230, screen.getGuiTop() + 15, 0); step++; return null; }
			if (session.view().mode() != MeTerminalView.Mode.STORAGE) return null;
			if (step == 1) { require(session.view().rows().size() == MeTerminalView.STORAGE_ROWS && session.view().more(), "Bounded stock page missing"); picture(client, "me-inventory-grid.png"); button(client, "next"); step++; return null; }
			if (step == 2) { require(session.view().page() == 1, "Stock next page did not advance"); pageItem = session.view().rows().getFirst().icon(); var request = session.begin(MeTerminalRequest.Action.TAKE, 0, 1, 1, ""); require(request != null, "No page extraction request"); PacketDistributor.sendToServer(request); step++; return null; }
			if (step == 3) { require(net.minecraft.world.item.ItemStack.isSameItemSameComponents(menu.getCarried(), pageItem) && menu.getCarried().getCount() == 1, "Page offset extracted a different key"); var request = session.begin(MeTerminalRequest.Action.DEPOSIT, -1, 1, 1, ""); require(request != null, "No page return request"); PacketDistributor.sendToServer(request); step++; return null; }
			if (step == 4) { require(menu.getCarried().isEmpty(), "Page item was not returned"); button(client, "previous"); step++; return null; }
			if (step == 5) { if (menu.craftingGeneration() == 0) return null; clickSlot(client, menu, 27); step++; return null; }
			if (step == 6) { if (!menu.getCarried().is(Items.OAK_LOG)) return null; clickSlot(client, menu, 36); step++; return null; }
			if (step == 7) { if (!menu.getCarried().isEmpty() || !menu.slots.get(45).getItem().is(Items.OAK_PLANKS)) return null; clickSlot(client, menu, 45); step++; return null; }
			if (step == 8) { if (!menu.getCarried().is(Items.OAK_PLANKS)) return null; require(menu.getCarried().getCount() == 4, "Wrong manual result amount"); clickSlot(client, menu, 28); step++; return null; }
			if (!menu.getCarried().isEmpty() || !menu.slots.get(28).getItem().is(Items.OAK_PLANKS)) return null;
			require(session.view().page() == 0, "Stock previous page did not return"); return ack();
		}
		if (stage == 851) {
			if (step == 0) { button(client, "sort.name"); step++; return null; }
			if (step == 1) { button(client, "ascending"); step++; return null; }
			require(session.view().rows().getFirst().kind() == MeTerminalView.Kind.FLUID && session.view().rows().getFirst().amount() == 1000, "Amount descending ignored stored fluid"); return ack();
		}
		if (stage == 852) {
			if (step++ == 0) { button(client, "type.all"); return null; }
			require(session.view().rows().stream().allMatch(r -> r.kind() == MeTerminalView.Kind.ITEM), "Item filter retained fluid");
			require(session.view().rows().getFirst().amount() == 64, "Item quantity ordering wrong"); return ack();
		}
		if (stage == 853) {
			if (step++ == 0) {
				var screen = (NetworkTerminalScreen) client.screen;
				var search = client.screen.children().stream().filter(w -> w instanceof EditBox).map(w -> (EditBox) w).findFirst().orElseThrow();
				search.setValue("diamond"); return null;
			}
			if (session.view().rows().size() != 1) return null;
			require(session.view().rows().getFirst().amount() == 10 && session.view().rows().getFirst().enabled(), "Stored/craftable identity was duplicated or hidden");
			picture(client, "me-inventory.png"); return ack();
		}
		if (stage == 854 || stage == 855 || stage == 859) {
			if (step++ == 0) { var screen = (NetworkTerminalScreen) client.screen; click(client, screen.getGuiLeft() + 40, screen.getGuiTop() + 59, stage == 854 ? 1 : 0); return null; }
			return ack();
		}
		if (stage == 856) {
			if (step++ == 0) { var request = session.begin(MeTerminalRequest.Action.TAKE_INVENTORY, 0, 0, 64, "diamond"); require(request != null, "No inventory request"); PacketDistributor.sendToServer(request); return null; }
			return ack();
		}
		if (stage == 857) {
			if (step == 0) {
				var slot = menu.slots.stream().filter(s -> s.index < 36 && s.getItem().is(Items.DIAMOND)).findFirst().orElseThrow();
				client.gameMode.handleInventoryMouseClick(menu.containerId, slot.index, 0, ClickType.PICKUP, client.player); step++; return null;
			}
			if (step == 1) { var screen = (NetworkTerminalScreen) client.screen; click(client, screen.getGuiLeft() + 40, screen.getGuiTop() + 59, 0); step++; return null; }
			return ack();
		}
		if (stage == 858) return ack();
		if (stage == 860) {
			if (step == 6) return TerminalPreferencesClient.advance(client, menu) ? ack() : null;
			if (step == 0) { ((NetworkTerminalScreen) client.screen).resize(client, 320, 240); step++; return null; }
			if (step == 1) { picture(client, "me-home-compact.png"); var screen = (NetworkTerminalScreen) client.screen; require(menu.slots.get(36).isActive() && menu.slots.get(0).y > menu.slots.get(44).y + 16, "Compact home hid crafting or overlapped inventory"); if (!menu.clientState().ready(net.minecraft.Util.getMillis())) return null; click(client, screen.getGuiLeft() + 16, screen.getGuiTop() + 153, 0); step++; return null; }
			if (step == 2) { if (!menu.clientState().actionable(net.minecraft.Util.getMillis()) || menu.clientState().view() == null) return null; var screen = (NetworkTerminalScreen) client.screen; picture(client, "me-home-management.png"); click(client, screen.getGuiLeft() + 16, screen.getGuiTop() + 16, 0); step++; return null; }
			if (step == 3) { if (menu.craftingGeneration() == 0 || !menu.clientState().actionable(net.minecraft.Util.getMillis())) return null; var screen = (NetworkTerminalScreen) client.screen; require(menu.slots.get(36).isActive(), "Management return lost crafting"); picture(client, "me-home-restored.png"); click(client, screen.getGuiLeft() + 16, screen.getGuiTop() + 16, 0); step++; return null; }
			if (!(client.screen instanceof MeTerminalScreen)) return null;
			if (step == 4) { button(client, "storage"); step++; return null; }
			if (session.view().mode() != MeTerminalView.Mode.STORAGE) return null;
			picture(client, "me-inventory-compact.png"); step = 6; return null;
		}
		return ack();
	}
	private static void button(Minecraft client, String key) {
		String label = Component.translatable("screen.productivebeesgenesis.me_terminal." + key).getString();
		var button = client.screen.children().stream().filter(w -> w instanceof AbstractWidget widget && widget.active && widget.visible && widget.getMessage().getString().equals(label)).map(w -> (AbstractWidget) w).findFirst().orElseThrow(() -> new IllegalStateException("Missing ME button: " + label));
		click(client, button.getX() + button.getWidth() / 2., button.getY() + button.getHeight() / 2., 0);
	}
	private static void click(Minecraft client, double x, double y, int button) { require(client.screen.mouseClicked(x, y, button), "ME UI click missed"); client.screen.mouseReleased(x, y, button); }
	private static void clickSlot(Minecraft client, NetworkCoreMenu menu, int index) { var screen = (NetworkTerminalScreen) client.screen; var slot = menu.slots.get(index); click(client, screen.getGuiLeft() + slot.x + 8, screen.getGuiTop() + slot.y + 8, 0); }
	private static void picture(Minecraft client, String name) throws Exception { Files.createDirectories(Path.of("results")); try (var image = Screenshot.takeScreenshot(client.getMainRenderTarget())) { image.writeToFile(Path.of("results", name)); } }
	private static CraftingClient.Reply ack() { return new CraftingClient.Reply(0, -1); }
	private MeInventoryClient() { }
}
