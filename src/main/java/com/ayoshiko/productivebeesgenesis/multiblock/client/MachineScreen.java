package com.ayoshiko.productivebeesgenesis.multiblock.client;

import com.ayoshiko.productivebeesgenesis.multiblock.world.*;
import com.ayoshiko.productivebeesgenesis.multiblock.production.MachineUpgrades;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** 固定六行与主背包选择；一条请求在途，显示状态不携带权威资产组件。 */
@EventBusSubscriber(modid = "productivebeesgenesis", value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class MachineScreen extends AbstractContainerScreen<MachineMenu> {
	private final List<Button> actions = new ArrayList<>();
	private final java.util.Map<Button, int[]> scopes = new java.util.HashMap<>();
	private int inventorySlot;
	private boolean upgrades;
	private int upgradePage;
	private static final int[] UPGRADE_STARTS = {0, 4, 8, 11, 15};
	private int upgradeRows() { return upgradePage == 2 ? 3 : 4; }
	private Component upgradeName(int slot) {
		var pb = MachineUpgrades.pbType(slot); return pb == null ? tr("upgrade_name." + slot) : Component.translatable(pb.getNameKey());
	}
	private long sequence;
	public MachineScreen(MachineMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title); imageWidth = 230; imageHeight = 226; inventorySlot = inventory.selected;
	}
	@SubscribeEvent public static void register(RegisterMenuScreensEvent event) { event.register(MachineContent.MENU.get(), MachineScreen::new); }
	private static Component tr(String key, Object... args) { return Component.translatable("screen.productivebeesgenesis.machine." + key, args); }
	@Override protected void init() {
		super.init(); actions.clear(); scopes.clear();
		addRenderableWidget(Button.builder(tr(upgrades ? "bees_tab" : "upgrades_tab"), button -> { upgrades = !upgrades; rebuildWidgets(); })
				.bounds(leftPos + 177, topPos + 4, 46, 14).build()).setTooltip(Tooltip.create(tr("upgrade_scope")));
		if (upgrades) {
			for (int row = 0; row < upgradeRows(); row++) for (int action = 0; action < 2; action++) {
				int slot = UPGRADE_STARTS[upgradePage] + row, operation = 4 + action; String key = action == 0 ? "upgrade_in" : "upgrade_out";
				var button = addRenderableWidget(Button.builder(tr(key), ignored -> send(operation, slot))
						.bounds(leftPos + 151 + action * 36, topPos + 27 + row * 21, 34, 18).build());
				button.setTooltip(Tooltip.create(Component.empty().append(upgradeName(slot)).append("\n").append(tr(key + "_hint")))); actions.add(button); scopes.put(button, new int[]{operation, slot});
			}
			addRenderableWidget(Button.builder(Component.literal("<"), ignored -> { upgradePage--; rebuildWidgets(); })
					.bounds(leftPos + 6, topPos + 109, 18, 12).build()).active = upgradePage > 0;
			addRenderableWidget(Button.builder(Component.literal(">"), ignored -> { upgradePage++; rebuildWidgets(); })
					.bounds(leftPos + 205, topPos + 109, 18, 12).build()).active = upgradePage < UPGRADE_STARTS.length - 1;
			return;
		}
		String[] labels = {"cage_in", "cage_out", "feed_in", "feed_out"};
		for (int row = 0; row < 6; row++) for (int action = 0; action < labels.length; action++) {
			int slot = row, operation = action;
			var button = addRenderableWidget(Button.builder(tr(labels[action]), ignored -> send(operation, slot))
					.bounds(leftPos + 79 + action * 36, topPos + 24 + row * 16, 34, 15).build());
			button.setTooltip(Tooltip.create(tr(labels[action] + "_hint"))); actions.add(button); scopes.put(button, new int[]{operation, slot});
		}
	}
	private void send(int action, int slot) {
		if (sequence > menu.acknowledged() || !menu.allowsAction(action, slot)) return;
		PacketDistributor.sendToServer(new MachineMenuRequest(menu.containerId, menu.session(), ++sequence, menu.viewRevision(), action, slot, inventorySlot, hasShiftDown() ? 64 : 1));
	}
	@Override protected void containerTick() {
		super.containerTick(); for (var button : actions) { var scope = scopes.get(button); button.active = sequence <= menu.acknowledged() && menu.allowsAction(scope[0], scope[1]); }
	}
	@Override public boolean mouseClicked(double x, double y, int button) {
		if (button == 0) for (var slot : menu.slots) if (isHovering(slot.x, slot.y, 16, 16, x, y)) { inventorySlot = slot.getContainerSlot(); return true; }
		return super.mouseClicked(x, y, button);
	}
	@Override protected void renderBg(GuiGraphics g, float partial, int mouseX, int mouseY) {
		g.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xffa77932);
		g.fill(leftPos + 2, topPos + 2, leftPos + imageWidth - 2, topPos + imageHeight - 2, 0xff282c31);
		if (upgrades) {
			for (int row = 0; row < upgradeRows(); row++) {
				int slot = UPGRADE_STARTS[upgradePage] + row;
				int y = topPos + 27 + row * 21;
				g.fill(leftPos + 6, y, leftPos + 146, y + 18, 0xff383d43);
				String count = menu.upgradeCount(slot) + "/" + menu.upgradeLimit(slot); int countWidth = font.width(count);
				g.drawString(font, font.plainSubstrByWidth(upgradeName(slot).getString(), Math.max(1, 128 - countWidth)), leftPos + 9, y + 5, 0xffead5a7, false);
				g.drawString(font, count, leftPos + 142 - countWidth, y + 5, 0xffead5a7, false);
			}
			g.drawCenteredString(font, tr("upgrade_page." + upgradePage), leftPos + imageWidth / 2, topPos + 111, 0xffc3c8cc);
		} else for (int row = 0; row < 6; row++) {
			int y = topPos + 24 + row * 16;
			g.fill(leftPos + 6, y, leftPos + 74, y + 15, 0xff383d43);
			g.drawString(font, tr(menu.occupied(row) ? "occupied" : "empty", row + 1), leftPos + 9, y + 4, 0xffead5a7, false);
			var food = menu.foodIcon(row); if (!food.isEmpty()) { g.renderItem(food, leftPos + 52, y); g.renderItemDecorations(font, food, leftPos + 52, y); }
		}
		for (var slot : menu.slots) {
			int x = leftPos + slot.x, y = topPos + slot.y;
			g.fill(x - 1, y - 1, x + 17, y + 17, slot.getContainerSlot() == inventorySlot ? 0xffffce65 : 0xff626970);
			g.fill(x, y, x + 16, y + 16, 0xff171a1e);
		}
	}
	@Override protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
		g.drawString(font, font.plainSubstrByWidth(title.getString(), 164), 7, 7, 0xffead5a7, false);
		g.drawString(font, tr("energy", menu.energy(), menu.jobs()), 7, 16, 0xffc3c8cc, false);
		g.drawString(font, tr("status." + menu.status()), 7, 123, 0xffe1b96b, false);
		g.drawString(font, menu.wireless() ? Component.translatable("screen.productivebeesgenesis.network.terminal.wireless_energy", menu.deviceEnergy()) : tr("inventory_hint"), 7, 133, 0xffc3c8cc, false);
	}
	@Override public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
		super.render(g, mouseX, mouseY, partial); renderTooltip(g, mouseX, mouseY);
	}
}
