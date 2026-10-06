package com.ayoshiko.productivebeesgenesis.multiblock.client;

import com.ayoshiko.productivebeesgenesis.multiblock.world.*;
import com.ayoshiko.productivebeesgenesis.multiblock.production.MachineUpgrades;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.*;
import net.minecraft.Util;
import static com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalRequest.Operation.*;
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
	private boolean upgrades, crafting, craftingRequested;
	private int craftingTarget;
	private Button craftTab, clearCrafting;
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
	private static Component terminal(String key, Object... args) { return Component.translatable("screen.productivebeesgenesis.network.terminal." + key, args); }
	public void prepareRecipeTransfer() {
		crafting = true; upgrades = false; craftingRequested = false;
		if (minecraft != null && minecraft.screen == this) rebuildWidgets();
	}
	private void craft(TerminalRequest.Operation operation, int row, int source, int amount) {
		if (!menu.wireless() || sequence > menu.acknowledged() || operation != CRAFTING && menu.craftingGeneration() == 0) return;
		var request = menu.craftingState().beginCrafting(operation, operation == CRAFTING ? 0 : menu.craftingGeneration(), row, source, amount, Util.getMillis());
		if (request != null) { craftingRequested = true; PacketDistributor.sendToServer(request); }
	}
	@Override protected void init() {
		super.init(); actions.clear(); scopes.clear(); clearCrafting = null; menu.layoutCrafting(crafting);
		craftTab = addRenderableWidget(Button.builder(crafting ? tr("bees_tab") : Component.translatable("screen.productivebeesgenesis.network.tab.4"), button -> {
			if (!menu.craftingState().ready(Util.getMillis()) || sequence > menu.acknowledged()) return;
			crafting = !crafting; upgrades = false; craftingRequested = false; rebuildWidgets();
		}).bounds(leftPos + 127, topPos + 4, 46, 14).build()); craftTab.visible = menu.wireless();
		addRenderableWidget(Button.builder(tr(upgrades ? "bees_tab" : "upgrades_tab"), button -> { upgrades = !upgrades; crafting = false; rebuildWidgets(); })
				.bounds(leftPos + 177, topPos + 4, 46, 14).build()).setTooltip(Tooltip.create(tr("upgrade_scope")));
		if (crafting) {
			clearCrafting = addRenderableWidget(Button.builder(terminal("craft_clear"), button -> craft(CRAFT_CLEAR, -1, -1, 0))
					.bounds(leftPos + 154, topPos + 55, 69, 18).build());
			clearCrafting.setTooltip(Tooltip.create(tr("crafting_hint")));
			addRenderableWidget(Button.builder(Component.translatable("screen.productivebeesgenesis.network.refresh"), button -> craft(CRAFTING, -1, -1, 0))
					.bounds(leftPos + 154, topPos + 78, 69, 18).build());
			return;
		}
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
		if (sequence > menu.acknowledged() || !menu.craftingState().ready(Util.getMillis()) || !menu.allowsAction(action, slot)) return;
		PacketDistributor.sendToServer(new MachineMenuRequest(menu.containerId, menu.session(), ++sequence, menu.viewRevision(), action, slot, inventorySlot, hasShiftDown() ? 64 : 1));
	}
	@Override protected void containerTick() {
		super.containerTick(); long now = Util.getMillis(); var state = menu.craftingState(); state.tick(now);
		craftTab.visible = menu.wireless(); craftTab.active = state.ready(now) && sequence <= menu.acknowledged();
		if (crafting && !craftingRequested && state.ready(now)) craft(CRAFTING, -1, -1, 0);
		if (clearCrafting != null) clearCrafting.active = state.ready(now) && menu.craftingGeneration() != 0 && menu.craftingStatus() == 1;
		for (var button : actions) { var scope = scopes.get(button); button.active = state.ready(now) && sequence <= menu.acknowledged() && menu.allowsAction(scope[0], scope[1]); }
	}
	@Override public boolean mouseClicked(double x, double y, int button) {
		if ((button == 0 || button == 1) && crafting) for (var slot : menu.slots) if (slot.index >= 36 && slot.isActive() && isHovering(slot.x, slot.y, 16, 16, x, y)) {
			if (slot.index == 45) craft(CRAFT_TAKE, -1, -1, hasShiftDown() ? 8 : 1);
			else { craftingTarget = slot.index - 36; boolean take = button == 1 || hasShiftDown();
				craft(take ? CRAFT_OUT : CRAFT_IN, craftingTarget, take ? -1 : inventorySlot, take && hasShiftDown() ? 64 : 1); }
			return true;
		}
		if (button == 0) for (var slot : menu.slots) if (slot.index < 36 && isHovering(slot.x, slot.y, 16, 16, x, y)) {
			inventorySlot = slot.getContainerSlot(); if (crafting && hasShiftDown()) craft(CRAFT_IN, craftingTarget, inventorySlot, 64); return true;
		}
		return super.mouseClicked(x, y, button);
	}
	@Override protected void renderBg(GuiGraphics g, float partial, int mouseX, int mouseY) {
		g.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xffa77932);
		g.fill(leftPos + 2, topPos + 2, leftPos + imageWidth - 2, topPos + imageHeight - 2, 0xff282c31);
		if (crafting) {
			g.drawString(font, "\u2192", leftPos + 103, topPos + 64, 0xffead5a7, false);
		} else if (upgrades) {
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
			if (!slot.isActive()) continue;
			int x = leftPos + slot.x, y = topPos + slot.y;
			g.fill(x - 1, y - 1, x + 17, y + 17, (slot.index < 36 ? slot.getContainerSlot() == inventorySlot : slot.index == 36 + craftingTarget) ? 0xffffce65 : 0xff626970);
			g.fill(x, y, x + 16, y + 16, 0xff171a1e);
		}
	}
	@Override protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
		g.drawString(font, font.plainSubstrByWidth(title.getString(), menu.wireless() ? 116 : 164), 7, 7, 0xffead5a7, false);
		g.drawString(font, tr("energy", menu.energy(), menu.jobs()), 7, 16, 0xffc3c8cc, false);
		Component status = tr("status." + menu.status());
		if (crafting) {
			g.drawString(font, font.plainSubstrByWidth(tr("crafting_hint").getString(), 210), 7, 105, 0xffc3c8cc, false);
			var state = menu.craftingState(); var result = state.exchangeResult();
			status = state.waiting() ? terminal("syncing") : menu.craftingStatus() != 1
					? terminal(menu.craftingStatus() == 2 ? "craft_pending" : menu.craftingStatus() == 3 ? "craft_quarantined" : "craft_unavailable")
					: result == null || result.status() == TerminalReply.Status.OK ? tr("crafting_ready") : result.status() == TerminalReply.Status.MOVED ? terminal("moved", result.moved())
					: Component.translatable("screen.productivebeesgenesis.network.result." + result.status().name().toLowerCase(java.util.Locale.ROOT), result.moved());
		}
		g.drawString(font, font.plainSubstrByWidth(status.getString(), 216), 7, 123, 0xffe1b96b, false);
		g.drawString(font, menu.wireless() ? Component.translatable("screen.productivebeesgenesis.network.terminal.wireless_energy", menu.deviceEnergy()) : tr("inventory_hint"), 7, 133, 0xffc3c8cc, false);
	}
	@Override public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
		super.render(g, mouseX, mouseY, partial); renderTooltip(g, mouseX, mouseY);
	}
}
