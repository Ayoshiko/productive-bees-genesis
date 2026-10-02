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

/** 常驻有限背包、侧边导航与服务器选择分离；界面只展示和发送受校验的命令。 */
@EventBusSubscriber(modid = "productivebeesgenesis", value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class NetworkCoreScreen extends AbstractContainerScreen<NetworkCoreMenu> {
	public static final int WIDTH = 230, HEIGHT = 238;
	private static final ResourceLocation BACKGROUND = NetworkGuiButton.texture("core");
	private final TerminalClientState state;
	private final Inventory playerInventory;
	private final List<Button> requests = new ArrayList<>(), management = new ArrayList<>();
	private int tab, selected = -1, target, inventorySlot, amount = 1;
	private boolean confirmCage, refreshAfterTake;
	private long takeSequence;
	private TerminalView displayed, iconView;
	private final List<TerminalProductIcon> productIcons = new ArrayList<>();
	private TerminalClientState.Notice displayedNotice;
	private Button production;
	private Button upgradeInstall, upgradeRemove;
	private int upgradeChoice;
	private boolean upgradeBatch;
	private int previewHover;
	private long previewHoverSince;

	public NetworkCoreScreen(NetworkCoreMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title); state = menu.clientState(); playerInventory = inventory;
		imageWidth = WIDTH; imageHeight = HEIGHT;
		if (menu.memberScoped()) tab = 3;
	}
	@SubscribeEvent public static void register(RegisterMenuScreensEvent event) { event.register(NetworkContent.CORE_MENU.get(), NetworkCoreScreen::new); }
	@Override protected void init() {
		super.init(); rebuild();
		if (menu.memberScoped() && state.notice() == TerminalClientState.Notice.IDLE) refresh();
	}
	private Component tr(String key, Object... args) { return Component.translatable("screen.productivebeesgenesis.network." + key, args); }
	private Button button(Component label, int x, int y, int width, int height, Runnable action) {
		var button = addRenderableWidget(new NetworkGuiButton(leftPos + x, topPos + y, width, height, label, action, false, -1));
		button.setTooltip(Tooltip.create(label)); return button;
	}
	private Button requestButton(Component label, int x, int y, int width, int height, Runnable action) {
		var button = button(label, x, y, width, height, action); requests.add(button); return button;
	}
	private void rebuild() {
		clearWidgets(); requests.clear(); management.clear(); production = null; upgradeInstall = null; upgradeRemove = null;
		for (int i = 0; i < 4; i++) {
			if (menu.memberScoped() && i != 3) continue;
			int page = i;
			var control = addRenderableWidget(new NetworkGuiButton(leftPos + 2, topPos + 27 + i * 32, 24, 28,
					tr("tab." + i), () -> switchTab(page), i == tab, i));
			control.setTooltip(Tooltip.create(tr("tab." + i))); requests.add(control);
		}
		if (tab == 0) {
			management.add(button(tr("rebuild"), 36, 108, 44, 20, () -> coreCommand(0)));
			management.add(button(tr("join"), 83, 108, 44, 20, () -> coreCommand(1)));
			management.add(button(tr("return"), 130, 108, 44, 20, () -> coreCommand(2)));
			production = button(productionLabel(), 177, 108, 44, 20, () -> coreCommand(3)); management.add(production);
		} else {
			requestButton(tr("refresh"), 36, 26, menu.memberScoped() ? 185 : 90, 15, this::refresh);
			if (!menu.memberScoped() && tab == 3 && selectedRow() != null) {
				var scope = requestButton(tr(upgradeBatch ? "upgrade_page" : "upgrade_single"), 130, 26, 91, 15,
						() -> { upgradeBatch = !upgradeBatch; rebuild(); });
				scope.setTooltip(Tooltip.create(tr("upgrade_page_hint", state.view().rows().size())));
			} else if (!menu.memberScoped()) {
				var next = requestButton(tr("next"), 130, 26, 91, 15, () -> send(NEXT, 0));
				if (state.view() == null || !state.view().hasNext()) requests.remove(next);
				next.active = state.view() != null && state.view().hasNext();
			}
			var view = state.view();
			if (view != iconView) {
				iconView = view; productIcons.clear();
				if (view != null && tab == 2) for (var row : view.rows()) productIcons.add(new TerminalProductIcon(row));
			}
			if (view != null && (tab == 2 || selectedRow() == null)) for (int i = 0; i < view.rows().size(); i++) {
				int row = i; var entry = view.rows().get(i);
				if (tab == 2) {
					var icon = productIcons.get(i);
					var control = addRenderableWidget(new NetworkProductButton(leftPos + 40 + i * 22,
							topPos + 66, icon, mouse -> take(row, mouse)));
					requests.add(control);
					control.setTooltip(Tooltip.create(icon.name().copy().append("\n").append(rowTooltip(entry, i)).append("\n").append(tr(entry.fluid() ? "quick_bucket" : "quick_take")).append("\n").append(tr("quick_destination"))
							.append(icon.simplified() ? Component.literal("\n").append(tr("simplified_icon")) : Component.empty())));
					continue;
				}
				var label = Component.literal("#" + (i + 1) + " " + entryName(entry).getString());
				var control = addRenderableWidget(new NetworkGuiButton(leftPos + 35, topPos + 43 + i * 11, 185, 11,
						label, () -> { selected = row; target = 0; upgradeChoice = 0; confirmCage = false; rebuild(); }, i == selected, -1));
				control.setTooltip(Tooltip.create(rowTooltip(entry, i)));
			}
			if (tab == 1 && selectedRow() != null) addActions();
			if (tab == 3 && selectedRow() != null) addUpgradeActions();
			if (tab == 3 && view == null && state.exchangeResult() != null) for (var result : state.exchangeResult().upgrades()) {
				var label = tr("upgrade_row_result", result.row() + 1, UpgradePreviewText.result(result.status(), result.moved()));
				var row = button(label, 35, 43 + result.row() * 11, 185, 11, () -> { }); row.active = false;
				row.setTooltip(Tooltip.create(Component.literal(result.label()).append("\n").append(label)));
			}
		}
		updateEnabled();
	}
	private void addActions() {
		var row = selectedRow();
		if (tab == 1 && !row.bees().isEmpty()) {
			for (var bee : row.bees()) {
				int slot = bee.slot();
				button(Component.literal((target == slot ? "•" : "") + (slot + 1)), 36 + slot * 63, 66, 59, 16,
						() -> { target = slot; confirmCage = false; rebuild(); });
			}
			button(tr("amount", amount), 36, 84, 185, 15, () -> { cycleAmount(); });
			requestButton(tr("feed_in"), 36, 101, 90, 14, () -> send(FEED_IN, amount));
			requestButton(tr("feed_out"), 130, 101, 91, 14, () -> send(FEED_OUT, amount));
			var bee = selectedBee();
			if (bee != null && bee.occupied()) {
				var cage = requestButton(tr(confirmCage ? "cage_confirm" : "cage_out"), 36, 117, 185, 14, () -> {
					if (bee.progress() > 0 && !confirmCage) { confirmCage = true; rebuild(); }
					else send(CAGE_OUT, 1);
				});
				cage.setTooltip(Tooltip.create(tr("cage_warning")));
			} else requestButton(tr("cage_in"), 36, 117, 185, 14, () -> send(CAGE_IN, 1));
		}
	}
	private void take(int row, int mouseButton) {
		var view = state.view(); if (view == null || row >= view.rows().size()) return;
		var request = state.begin(TAKE_PRODUCT, row, -1, -1, view.rows().get(row).fluid() ? 1000 : mouseButton == 1 ? 1 : 64, Util.getMillis());
		if (request == null) return;
		refreshAfterTake = true; takeSequence = request.sequence();
		PacketDistributor.sendToServer(request); selected = -1; rebuild();
	}
	private void addUpgradeActions() {
		var upgrades = selectedRow().upgrades();
		for (int i = 0; i < upgrades.size(); i++) {
			var upgrade = upgrades.get(i);
			var control = addRenderableWidget(new NetworkUpgradeButton(leftPos + 36 + (i % 5) * 37, topPos + 58 + (i / 5) * 25,
					upgrade, upgradeChoice == upgrade.choice(), () -> { upgradeChoice = upgrade.choice(); rebuild(); }));
			requests.add(control);
			control.setTooltip(Tooltip.create(control.getMessage().copy().append("\n").append(tr("upgrade_count", upgrade.installed(), upgrade.limit()))
					.append("\n").append(tr(upgrade.installable() ? "upgrade_next_cycle" : "upgrade_blocked"))));
		}
		button(Component.literal(Integer.toString(amount)), 36, 111, 40, 18, this::cycleAmount).setTooltip(Tooltip.create(tr("amount", amount)));
		upgradeInstall = requestButton(tr(upgradeBatch ? "upgrade_install_page" : "upgrade_install"), 80, 111, 68, 18,
				() -> send(upgradeBatch ? UPGRADE_INSTALL_PAGE : UPGRADE_INSTALL, amount));
		upgradeRemove = requestButton(tr(upgradeBatch ? "upgrade_remove_page" : "upgrade_remove"), 152, 111, 69, 18,
				() -> send(upgradeBatch ? UPGRADE_REMOVE_PAGE : UPGRADE_REMOVE, amount));
	}
	private TerminalView.Upgrade selectedUpgrade() {
		var row = selectedRow(); return row == null ? null : row.upgrades().stream().filter(upgrade -> upgrade.choice() == upgradeChoice).findFirst().orElse(null);
	}
	private void cycleAmount() { amount = amount == 1 ? 16 : amount == 16 ? 64 : 1; rebuild(); }
	private void switchTab(int page) {
		if (menu.memberScoped() && page != 3) return;
		if (!state.ready(Util.getMillis())) return;
		tab = page; refreshAfterTake = false; selected = -1; confirmCage = false;
		if (tab == 0) send(CANCEL, 0); else refresh();
		rebuild();
	}
	private void refresh() { send(tab == 1 ? MEMBERS : tab == 3 ? UPGRADES : PRODUCTS, 0); }
	private void send(TerminalRequest.Operation operation, int count) {
		var request = state.begin(operation, selected, TerminalRequest.upgradeAction(operation) ? upgradeChoice : target, inventorySlot, count, Util.getMillis());
		if (request == null) return;
		PacketDistributor.sendToServer(request);
		if (!TerminalRequest.upgradePreview(operation)) selected = -1;
		confirmCage = false; rebuild();
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
		for (var button : requests) button.active = state.ready(Util.getMillis());
		for (var button : management) {
			button.active = menu.canManage();
			button.setTooltip(Tooltip.create(menu.canManage() ? button.getMessage() : tr("owner_only")));
		}
		if (upgradeInstall != null) {
			var upgrade = selectedUpgrade(); boolean allowed = state.ready(Util.getMillis()) && menu.canUpgrade() && upgrade != null;
			upgradeInstall.active = allowed && (upgradeBatch || upgrade.installable()); upgradeRemove.active = allowed && (upgradeBatch || upgrade.installed() > 0);
			upgradeInstall.setTooltip(Tooltip.create(upgradeTooltip(true))); upgradeRemove.setTooltip(Tooltip.create(upgradeTooltip(false)));
		}
	}
	private boolean matchingPreview(boolean install) {
		var p = state.preview(); return p != null && p.row() == selected && p.choice() == upgradeChoice && p.inventorySlot() == inventorySlot
				&& p.requested() == amount && p.installing() == install;
	}
	private Component upgradeTooltip(boolean install) {
		if (!menu.canUpgrade()) return tr("upgrade_permission");
		return matchingPreview(install) ? UpgradePreviewText.text(state.preview(), upgradeBatch) : tr("preview_hover");
	}
	private void previewHoveredUpgrade() {
		int hover = tab == 3 && selectedRow() != null && upgradeInstall != null && menu.canUpgrade()
				? upgradeInstall.isHovered() ? 1 : upgradeRemove.isHovered() ? 2 : 0 : 0;
		long now = Util.getMillis();
		if (hover != previewHover) { previewHover = hover; previewHoverSince = now; }
		if (hover != 0 && now - previewHoverSince >= 350 && state.ready(now) && !matchingPreview(hover == 1))
			send(hover == 1 ? UPGRADE_PREVIEW_INSTALL : UPGRADE_PREVIEW_REMOVE, amount);
	}
	@Override protected void containerTick() {
		super.containerTick(); state.tick(Util.getMillis());
		if (state.view() != displayed || state.notice() != displayedNotice) {
			if (displayed != state.view()) selected = menu.memberScoped() && state.view() != null
					&& state.view().kind() == NetworkSelectionSession.Kind.UPGRADES && state.view().rows().size() == 1 ? 0 : -1;
			displayed = state.view(); displayedNotice = state.notice(); confirmCage = false; rebuild();
		}
		if (refreshAfterTake && !state.waiting() && state.ready(Util.getMillis()) && state.result() != null) {
			refreshAfterTake = false;
			if (state.result().status() == TerminalReply.Status.MOVED) refresh();
		}
		if (production != null) production.setMessage(productionLabel());
		updateEnabled();
		previewHoveredUpgrade();
	}
	@Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if ((tab == 1 || tab == 3) && button == 0) for (var slot : menu.slots) {
			if (mouseX >= leftPos + slot.x && mouseX < leftPos + slot.x + 16
					&& mouseY >= topPos + slot.y && mouseY < topPos + slot.y + 16) {
				inventorySlot = slot.getContainerSlot(); confirmCage = false; rebuild(); return true;
			}
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}
	@Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		super.render(graphics, mouseX, mouseY, partialTick); renderTooltip(graphics, mouseX, mouseY);
		if (mouseX >= leftPos + 32 && mouseX < leftPos + 224 && mouseY >= topPos + 133 && mouseY < topPos + 144)
			graphics.renderTooltip(font, font.split(statusMessage(), 190), mouseX, mouseY);
	}
	@Override protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
		graphics.blit(BACKGROUND, leftPos, topPos, 0, 0, WIDTH, HEIGHT, WIDTH, HEIGHT);
		for (var slot : menu.slots) if ((tab == 1 || tab == 3) && slot.getContainerSlot() == inventorySlot)
			graphics.renderOutline(leftPos + slot.x - 1, topPos + slot.y - 1, 18, 18, 0xfff0c66f);
	}
	private void line(GuiGraphics graphics, Component text, int x, int y, int width) {
		graphics.drawString(font, font.plainSubstrByWidth(text.getString(), width), x, y, 0xffe6ead6, false);
	}
	@Override protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
		line(graphics, title, 36, 9, 118);
		line(graphics, tr("tab." + tab), 169, 9, 52);
		if (tab == 0) {
			line(graphics, tr("state." + menu.value(0)), 36, 29, 185);
			for (int i = 1; i <= 4; i++) line(graphics, tr("count." + i, menu.value(i)), 36 + (i - 1) % 2 * 94, 44 + (i - 1) / 2 * 13, 90);
			line(graphics, tr("ownership." + menu.ownershipStatus()), 36, 73, 185);
			line(graphics, tr("runtime." + menu.runtimeStatus()), 36, 86, 185);
			graphics.fill(36, 99, 221, 103, 0xff151e21);
			long capacity = menu.energy(true);
			int filled = capacity <= 0 ? 0 : (int) (185 * Math.min(1, (double) menu.energy(false) / capacity));
			graphics.fill(36, 99, 36 + filled, 103, 0xffd3b469);
		} else if (tab == 1) {
			var row = selectedRow();
			if (row != null) {
				var bee = selectedBee();
				line(graphics, bee == null ? tr("no_bees") : bee.occupied() ? beeName(bee.type()) : tr("empty_bee"), 36, 44, 185);
				if (bee != null) line(graphics, bee.pending() ? tr("pending") : tr("progress", bee.progress(), bee.cycleTicks()), 36, 55, 185);
			} else if (state.view() != null && state.view().rows().isEmpty()) line(graphics, tr("empty_page"), 36, 48, 185);
		} else if (tab == 3) {
			var row = selectedRow();
			if (row != null) {
				line(graphics, entryName(row), 36, 44, 185);
				if (row.upgrades().isEmpty()) line(graphics, tr("upgrade_unavailable"), 36, 65, 185);
			} else if (state.view() != null && state.view().rows().isEmpty()) line(graphics, tr("empty_page"), 36, 48, 185);
		} else {
			line(graphics, tr("state." + menu.value(0)), 36, 47, 185);
			if (state.view() != null && state.view().rows().isEmpty()) line(graphics, tr("empty_page"), 40, 71, 174);
			line(graphics, tr("quick_take"), 36, 98, 185);
			line(graphics, tr("quick_bucket"), 36, 114, 185);
		}
		line(graphics, statusMessage(), 36, 134, 185);
		line(graphics, playerInventory.getDisplayName(), 38, 144, 68);
		line(graphics, tab == 1 || tab == 3 ? tr("inventory_slot", inventorySlot + 1) : tr(menu.canManage() ? "role_owner" : "role_guest"), 112, 144, 107);
	}

	private Component beeName(String type) {
		var id = ResourceLocation.tryParse(type);
		return id == null ? Component.literal(type) : com.ayoshiko.productivebeesgenesis.util.BeeInfoHelper.getBeeDisplayName(id);
	}
	private Component entryName(TerminalView.Row row) {
		if (tab == 1 || tab == 3) {
			int at = row.label().indexOf(" @ ");
			if (at > 0) {
				var machine = ResourceLocation.tryParse(row.label().substring(0, at));
				if (machine != null && BuiltInRegistries.BLOCK.containsKey(machine))
					return BuiltInRegistries.BLOCK.get(machine).getName().copy().append(row.label().substring(at));
			}
		}
		var id = ResourceLocation.tryParse(row.label());
		if (id != null && tab == 2 && !row.fluid() && BuiltInRegistries.ITEM.containsKey(id)) return BuiltInRegistries.ITEM.get(id).getDescription();
		return Component.literal(row.label());
	}
	private Component rowTooltip(TerminalView.Row row, int index) {
		if (tab == 2) return tr("owned", row.owned()).copy().append("\n").append(tr("available", row.available()));
		return entryName(row);
	}
	private Component statusMessage() {
		if (tab == 0) return tr("energy", NumberFormatter.formatCompact(menu.energy(false)), NumberFormatter.formatCompact(menu.energy(true)));
		return switch (state.notice()) {
			case IDLE -> tr("select_entry");
			case WAITING -> tr("waiting");
			case TIMEOUT -> tr("timeout");
			case EXPIRED -> tr("expired");
			case REPLY -> {
				var result = tab == 2 && state.exchangeResult() != null && state.exchangeResult().sequence() == takeSequence ? state.exchangeResult() : state.result();
				if (tab == 2 && state.view() != null && result.status() == TerminalReply.Status.MOVED) yield tr("quick_moved", result.moved());
				if (result.interruptedTicks() > 0) yield tr("interrupted", result.interruptedTicks());
				if (tab == 3 && result.status() == TerminalReply.Status.EMPTY) yield tr("upgrade_empty");
				yield tr("result." + result.status().name().toLowerCase(java.util.Locale.ROOT), result.moved());
			}
		};
	}
}
