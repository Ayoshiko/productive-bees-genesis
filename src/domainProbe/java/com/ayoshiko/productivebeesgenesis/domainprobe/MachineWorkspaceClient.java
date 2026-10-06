package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.multiblock.client.MachineScreen;
import com.ayoshiko.productivebeesgenesis.multiblock.world.MachineMenu;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;
import static com.ayoshiko.productivebeesgenesis.multiblock.world.MachineMenuDetails.Field.*;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

final class MachineWorkspaceClient {
	private static int previous = -1, step;
	static CraftingClient.Reply advance(Minecraft client, int stage, boolean owner) throws Exception {
		if (!owner) return ack();
		if (previous != stage) { previous = stage; step = 0; }
		if (!(client.screen instanceof MachineScreen screen) || !(client.player.containerMenu instanceof MachineMenu menu)) return null;
		if (!menu.craftingState().ready(Util.getMillis())) return null;
		var d = menu.details(); if (d.value(AVAILABLE) == 0 || d.value(ITEM_COUNT) != 3) return null;
		require(d.value(BEE_SLOTS) == 6 && d.value(LANES) == 3 && d.value(ENERGY_CAPACITY) == 1_000_000 && menu.energy() == 12345
				&& d.value(FLUID_AMOUNT) == 1000 && d.value(FLUID_CAPACITY) == 64000, "Machine details do not match real work");
		require(d.value(POS_X) != 0 && d.value(SIZE_X) > 0 && menu.slots.subList(0,36).stream().allMatch(s -> s.isActive() && s.x >= 0 && s.y >= 0 && s.x + 16 <= screen.getXSize() && s.y + 16 <= screen.getYSize()), "Machine bounds or inventory layout invalid");
		switch (stage) {
			case 750 -> {
				if (menu.craftingGeneration() == 0) return null;
				require(screen.getXSize() == MachineScreen.WORKSPACE_WIDTH && menu.slots.get(36).isActive(), "Machine workspace did not keep crafting visible");
				picture(client, "machine-workspace-overview.png"); return ack();
			}
			case 751 -> {
				if (step == 0) { press(screen, "screen.productivebeesgenesis.machine.details.page.0"); step++; return null; }
				if (step == 1) { picture(client, "machine-workspace-bees.png"); press(screen, "screen.productivebeesgenesis.machine.details.page.1"); step++; return null; }
				picture(client, "machine-workspace-jobs.png"); return ack();
			}
			case 752 -> {
				if (step == 0) { press(screen, "screen.productivebeesgenesis.machine.upgrades_tab"); step++; return null; }
				if (step == 1) { selectInventory(screen, menu, 1); press(screen, "screen.productivebeesgenesis.machine.upgrade_in"); step++; return null; }
				if (menu.upgradeCount(0) != 1) return null;
				picture(client, "machine-workspace-upgrades.png"); return ack();
			}
			case 753 -> {
				if (step == 0) { press(screen, "screen.productivebeesgenesis.machine.upgrade_out"); step++; return null; }
				return menu.upgradeCount(0) == 0 ? ack() : null;
			}
			case 754 -> {
				if (menu.craftingGeneration() == 0) return null;
				if (step == 0) { selectInventory(screen, menu, 0); var slot = menu.slots.get(36); click(screen, slot.x + 8, slot.y + 8); step++; return null; }
				if (!menu.craftingItem(0).is(Items.OAK_LOG)) return null;
				picture(client, "machine-workspace-crafting.png"); return ack();
			}
			case 755 -> {
				if (step == 0) { press(screen, "screen.productivebeesgenesis.network.terminal.craft_clear"); step++; return null; }
				return menu.craftingItem(0).isEmpty() ? ack() : null;
			}
			case 756 -> {
				if (step == 0) { screen.resize(client,320,240); step++; return null; }
				if (step == 1) { press(screen, "screen.productivebeesgenesis.machine.details.open"); step++; return null; }
				require(screen.getXSize() == 230, "Machine compact fallback missing"); picture(client, "machine-workspace-compact.png"); return ack();
			}
			case 757 -> {
				if (step == 0) { screen.resize(client, client.getWindow().getGuiScaledWidth(), client.getWindow().getGuiScaledHeight()); step++; return null; }
				require(screen.getXSize() == MachineScreen.WORKSPACE_WIDTH && menu.slots.get(36).isActive(), "Machine workspace resize lost material slots"); return ack();
			}
		}
		return null;
	}
	private static void selectInventory(MachineScreen screen, MachineMenu menu, int source) { var slot = menu.slots.subList(0,36).stream().filter(s -> s.getContainerSlot() == source).findFirst().orElseThrow(); click(screen,slot.x + 8,slot.y + 8); }
	private static void click(MachineScreen screen, int x, int y) { require(screen.mouseClicked(screen.getGuiLeft()+x,screen.getGuiTop()+y,0), "Machine workspace click missed"); screen.mouseReleased(screen.getGuiLeft()+x,screen.getGuiTop()+y,0); }
	private static void press(MachineScreen screen, String key) { var label = Component.translatable(key); var button = screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast).filter(b -> b.visible && b.getMessage().equals(label)).findFirst().orElseThrow(); require(button.active,"Machine control disabled: "+key); screen.mouseClicked(button.getX()+4,button.getY()+4,0); screen.mouseReleased(button.getX()+4,button.getY()+4,0); }
	private static void picture(Minecraft client, String name) throws Exception { try(var image=Screenshot.takeScreenshot(client.getMainRenderTarget())) { image.writeToFile(java.nio.file.Path.of("results",name)); } }
	private static CraftingClient.Reply ack() { return new CraftingClient.Reply(0,-1); }
}
