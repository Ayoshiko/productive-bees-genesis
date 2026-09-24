package com.ayoshiko.productivebeesgenesis.apiculture.client;

import com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkContent;
import com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkCoreMenu;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.*;
import com.ayoshiko.productivebeesgenesis.util.NumberFormatter;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import static com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalRequest.Operation.*;

/** 最小本地终端：页面选择、有限背包位置与请求状态分开，不持有权威资产。 */
@EventBusSubscriber(modid = "productivebeesgenesis", value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class NetworkCoreScreen extends AbstractContainerScreen<NetworkCoreMenu> {
	private final TerminalClientState state;
	private final Inventory playerInventory;
	private final List<Button> requests = new ArrayList<>();
	private int tab, selected = -1, target, inventorySlot, amount = 1;
	private boolean choosingInventory, confirmCage;
	private TerminalView displayed;
	private TerminalClientState.Notice displayedNotice;
	private Button production;

	public NetworkCoreScreen(NetworkCoreMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title); state = menu.clientState(); playerInventory = inventory; imageWidth = 318; imageHeight = 236;
	}
	@SubscribeEvent public static void register(RegisterMenuScreensEvent event) { event.register(NetworkContent.CORE_MENU.get(), NetworkCoreScreen::new); }
	@Override protected void init() { super.init(); rebuild(); }
	private Component tr(String key, Object... args) { return Component.translatable("screen.productivebeesgenesis.network." + key, args); }
	private Button button(Component label, int x, int y, int width, int height, Runnable action) {
		return addRenderableWidget(Button.builder(label, ignored -> action.run()).bounds(leftPos + x, topPos + y, width, height).build());
	}
	private Button requestButton(Component label, int x, int y, int width, Runnable action) {
		var button = button(label, x, y, width, 18, action); requests.add(button); return button;
	}
	private void rebuild() {
		clearWidgets(); requests.clear(); production = null;
		menu.inventoryVisible(choosingInventory && tab != 0);
		for (int i = 0; i < 3; i++) {
			int page = i;
			var control = requestButton(tr("tab." + i), 8 + i * 102, 25, 98, () -> switchTab(page));
			if (i == tab) control.setMessage(Component.literal("• ").append(tr("tab." + i)));
		}
		if (tab == 0) {
			button(tr("rebuild"), 8, 156, 302, 20, () -> coreCommand(0));
			button(tr("join"), 8, 181, 147, 20, () -> coreCommand(1));
			button(tr("return"), 163, 181, 147, 20, () -> coreCommand(2));
			production = button(productionLabel(), 8, 206, 302, 20, () -> coreCommand(3));
		} else if (choosingInventory) {
			button(tr("back"), 8, 184, 166, 20, () -> { choosingInventory = false; rebuild(); });
		} else {
			requestButton(tr("refresh"), 8, 47, 80, this::refresh);
			var next = requestButton(tr("next"), 94, 47, 80, () -> send(NEXT, 0));
			if (state.view() == null || !state.view().hasNext()) requests.remove(next);
			next.active = state.view() != null && state.view().hasNext();
			var view = state.view();
			if (view != null) for (int i = 0; i < view.rows().size(); i++) {
				int row = i; var entry = view.rows().get(i);
				String label = (i == selected ? "▶ " : "") + "#" + (i + 1) + " " + entryName(entry).getString();
				var control = button(Component.literal(font.plainSubstrByWidth(label, 158)), 8, 69 + i * 14, 166, 14,
						() -> { selected = row; target = 0; confirmCage = false; rebuild(); });
				control.setTooltip(Tooltip.create(rowTooltip(entry, i)));
			}
			button(tr("inventory_slot", inventorySlot + 1), 8, 184, 146, 20, () -> { choosingInventory = true; confirmCage = false; rebuild(); });
			if (selectedRow() != null) addActions();
		}
		updateEnabled();
	}
	private void addActions() {
		var row = selectedRow();
		if (tab == 1 && !row.bees().isEmpty()) {
			for (var bee : row.bees()) {
				int slot = bee.slot();
				button(Component.literal((target == slot ? "▶" : "") + (slot + 1)), 182 + slot * 43, 89, 40, 18,
						() -> { target = slot; confirmCage = false; rebuild(); });
			}
			button(tr("amount", amount), 182, 111, 128, 18, () -> { amount = amount == 1 ? 16 : amount == 16 ? 64 : 1; rebuild(); });
			requestButton(tr("feed_in"), 182, 135, 62, () -> send(FEED_IN, amount));
			requestButton(tr("feed_out"), 248, 135, 62, () -> send(FEED_OUT, amount));
			var bee = selectedBee();
			if (bee != null && bee.occupied()) {
				var cage = requestButton(tr(confirmCage ? "cage_confirm" : "cage_out"), 182, 160, 128, () -> {
					if (bee.progress() > 0 && !confirmCage) { confirmCage = true; rebuild(); }
					else send(CAGE_OUT, 1);
				});
				cage.setTooltip(Tooltip.create(tr("cage_warning")));
			} else requestButton(tr("cage_in"), 182, 160, 128, () -> send(CAGE_IN, 1));
		} else if (tab == 2) {
			if (!row.fluid()) button(tr("amount", amount), 182, 135, 128, 18,
					() -> { amount = amount == 1 ? 16 : amount == 16 ? 64 : 1; rebuild(); });
			var take = requestButton(tr(row.fluid() ? "take_bucket" : "take_product"), 182, 160, 128,
					() -> send(TAKE_PRODUCT, row.fluid() ? 1000 : amount));
			take.setTooltip(Tooltip.create(tr(row.fluid() ? "bucket_hint" : "take_hint")));
		}
	}
	private void switchTab(int page) {
		if (!state.ready(Util.getMillis())) return;
		tab = page; selected = -1; choosingInventory = false; confirmCage = false;
		if (tab == 0) send(CANCEL, 0); else refresh();
		rebuild();
	}
	private void refresh() { send(tab == 1 ? MEMBERS : PRODUCTS, 0); }
	private void send(TerminalRequest.Operation operation, int count) {
		var request = state.begin(operation, selected, target, inventorySlot, count, Util.getMillis());
		if (request == null) return;
		PacketDistributor.sendToServer(request); selected = -1; confirmCage = false; rebuild();
	}
	private void coreCommand(int id) { minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id); }
	private Component productionLabel() { return tr(menu.productionRunning() ? "pause" : "start"); }
	private TerminalView.Row selectedRow() {
		var view = state.view(); return view != null && selected >= 0 && selected < view.rows().size() ? view.rows().get(selected) : null;
	}
	private TerminalView.Bee selectedBee() {
		var row = selectedRow(); return row == null ? null : row.bees().stream().filter(bee -> bee.slot() == target).findFirst().orElse(null);
	}
	private void updateEnabled() {
		boolean ready = state.ready(Util.getMillis());
		for (var button : requests) button.active = ready;
	}
	@Override protected void containerTick() {
		super.containerTick(); state.tick(Util.getMillis());
		if (state.view() != displayed || state.notice() != displayedNotice) {
			displayed = state.view(); displayedNotice = state.notice(); selected = -1; confirmCage = false; rebuild();
		}
		if (production != null) production.setMessage(productionLabel());
		updateEnabled();
	}
	@Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (choosingInventory && tab != 0 && button == 0) {
			for (var slot : menu.slots) if (mouseX >= leftPos + slot.x && mouseX < leftPos + slot.x + 16
					&& mouseY >= topPos + slot.y && mouseY < topPos + slot.y + 16) {
				inventorySlot = slot.getContainerSlot(); choosingInventory = false; rebuild(); return true;
			}
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}
	@Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		super.render(graphics, mouseX, mouseY, partialTick); renderTooltip(graphics, mouseX, mouseY);
	}
	@Override protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
		graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xff292720);
		graphics.fill(leftPos + 1, topPos + 1, leftPos + imageWidth - 1, topPos + 21, 0xff65592e);
		if (tab != 0 && choosingInventory) for (var slot : menu.slots)
			graphics.fill(leftPos + slot.x - 1, topPos + slot.y - 1, leftPos + slot.x + 17, topPos + slot.y + 17, 0xff514d40);
	}
	private void line(GuiGraphics graphics, Component text, int x, int y, int width) {
		graphics.drawString(font, font.plainSubstrByWidth(text.getString(), width), x, y, 0xffdddddd, false);
	}
	@Override protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
		graphics.drawString(font, title, 8, 7, 0xfff0d78d, false);
		if (tab == 0) {
			line(graphics, tr("state." + menu.value(0)), 8, 49, 302);
			for (int i = 1; i <= 4; i++) line(graphics, tr("count." + i, menu.value(i)), 8 + ((i - 1) % 2) * 154, 65 + ((i - 1) / 2) * 16, 148);
			line(graphics, tr("ownership." + menu.ownershipStatus()), 8, 103, 302);
			line(graphics, tr("energy", NumberFormatter.formatCompact(menu.energy(false)), NumberFormatter.formatCompact(menu.energy(true))), 8, 120, 302);
			line(graphics, tr("runtime." + menu.runtimeStatus()), 8, 137, 302); return;
		}
		if (choosingInventory) {
			line(graphics, tr("choose_inventory"), 8, 52, 302);
			line(graphics, tr("inventory_hint"), 8, 162, 302);
		} else {
			var row = selectedRow();
			if (row != null) {
				line(graphics, tr("entry", selected + 1), 182, 49, 128);
				if (tab == 1) {
					var bee = selectedBee();
					line(graphics, bee == null ? tr("no_bees") : bee.occupied() ? Component.literal(bee.type()) : tr("empty_bee"), 182, 63, 128);
					if (bee != null) line(graphics, bee.pending() ? tr("pending") : tr("progress", bee.progress(), bee.cycleTicks()), 182, 77, 128);
				} else {
					line(graphics, tr("owned", row.owned()), 182, 65, 128);
					line(graphics, tr("available", row.available()), 182, 80, 128);
					line(graphics, tr(row.fluid() ? "fluid_units" : "item_units"), 182, 96, 128);
					var details = font.split(Component.literal(row.detail()), 128);
					for (int i = 0; i < Math.min(2, details.size()); i++) graphics.drawString(font, details.get(i), 182, 110 + i * 10, 0xffdddddd, false);
				}
			} else line(graphics, tr(state.view() != null && state.view().rows().isEmpty() ? "empty_page" : "select_entry"), 182, 69, 128);
			graphics.renderItem(playerInventory.getItem(inventorySlot), 156, 186);
			graphics.renderItemDecorations(font, playerInventory.getItem(inventorySlot), 156, 186);
		}
		var lines = font.split(statusMessage(), 302);
		for (int i = 0; i < Math.min(2, lines.size()); i++) graphics.drawString(font, lines.get(i), 8, 211 + i * 10, 0xfff0d78d, false);
	}
	private Component entryName(TerminalView.Row row) {
		var id = ResourceLocation.tryParse(row.label());
		if (id != null && tab == 2 && !row.fluid() && BuiltInRegistries.ITEM.containsKey(id)) return BuiltInRegistries.ITEM.get(id).getDescription();
		return Component.literal(row.label());
	}
	private net.minecraft.network.chat.MutableComponent rowTooltip(TerminalView.Row row, int index) {
		var text = Component.translatable("screen.productivebeesgenesis.network.entry", index + 1).append("\n").append(row.label());
		if (tab == 2) text.append("\n").append(tr("owned", row.owned())).append("\n").append(tr("available", row.available()))
				.append("\n").append(row.detail()).append("\n").append(tr("preview_hint"));
		return text;
	}
	private Component statusMessage() {
		return switch (state.notice()) {
			case IDLE -> tr("select_entry");
			case WAITING -> tr("waiting");
			case TIMEOUT -> tr("timeout");
			case EXPIRED -> tr("expired");
			case REPLY -> {
				var result = state.result();
				if (result.interruptedTicks() > 0) yield tr("interrupted", result.interruptedTicks());
				yield tr("result." + result.status().name().toLowerCase(java.util.Locale.ROOT), result.moved());
			}
		};
	}
}
