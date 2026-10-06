package com.ayoshiko.productivebeesgenesis.apiculture.client;

import com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkCoreMenu;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.*;
import com.ayoshiko.productivebeesgenesis.apiary.BeeSlot;
import com.ayoshiko.productivebeesgenesis.apiary.client.BeeEntityRenderer;
import com.ayoshiko.productivebeesgenesis.util.BeeInfoHelper;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;
import static com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalRequest.Operation.*;

/** 专用终端界面：机器分组、蜜蜂／采蜜格、产物网格及固定背包，复用服务端资产事务。 */
public final class NetworkTerminalScreen extends AbstractContainerScreen<NetworkCoreMenu> {
	public static final int WIDTH = 304, WORKSPACE_WIDTH = 492;
	private final TerminalProductPane productPane;
	private boolean workspace;
	private final TerminalClientState state;
	private final Inventory inventory;
	private final List<Button> actions = new ArrayList<>();
	private final List<Button> navigationActions = new ArrayList<>();
	private final List<TerminalProductIcon> products = new ArrayList<>();
	private final BeeEntityRenderer beeRenderer = new BeeEntityRenderer();
	private final java.util.Map<String, BeeSlot> bees = new java.util.HashMap<>();
	private final List<HoveredBee> hoveredBees = new ArrayList<>();
	private EditBox search;
	private String query = "";
	private boolean queryDirty, draggingScroll;
	private long searchAt;
	private int tab, selected = -1, target, sourceSlot, amount = 1, upgradeChoice, scroll;
	private boolean confirmCage, batch;
	private TerminalSearchRequest.Sort sort = TerminalSearchRequest.Sort.CAPACITY;
	private TerminalSearchRequest.Sort productSort = TerminalSearchRequest.Sort.POSITION;
	private TerminalView displayed;
	private TerminalClientState.Notice notice;
	private Button install, remove;
	private Button nextPage, previousPage;
	private long hoverAt;
	private int hover;
	private int inventoryY;
	private int craftingTarget;
	private boolean craftingRequested, craftingSource;
	private TerminalView.Location restoreLocation;
	private java.util.UUID restoreBee;

	public NetworkTerminalScreen(NetworkCoreMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title); this.state = menu.clientState(); this.inventory = inventory;
		productPane = new TerminalProductPane(menu, this::rebuild);
		tab = menu.scope() == TerminalScope.APIARY ? 1 : 3;
	}
	@Override protected void init() {
		workspace = width >= WORKSPACE_WIDTH + 4 && height >= 300;
		if (workspace && (tab == 2 || tab == 4)) { tab = menu.scope() == TerminalScope.APIARY ? 1 : 3; query = ""; queryDirty = true; }
		imageWidth = workspace ? WORKSPACE_WIDTH : WIDTH; imageHeight = Math.max(236, Math.min(332, height - 4));
		super.init(); inventoryY = imageHeight - 84; menu.layoutTerminalInventory(workspace ? 318 : 88, inventoryY);
		state.tick(Util.getMillis());
		rebuild();
		if (state.view() == null && state.ready(Util.getMillis())) refresh();
		if (search != null) setInitialFocus(search);
	}
	private Component tr(String key, Object... args) { return Component.translatable("screen.productivebeesgenesis.network." + key, args); }
	private Component own(String key, Object... args) { return tr("terminal." + key, args); }
	private TerminalSkin.Control control(Component label, int x, int y, int w, int h, Runnable action, boolean selected, int icon) {
		var result = addRenderableWidget(new TerminalSkin.Control(leftPos + x, topPos + y, w, h, label, ignored -> action.run(), selected, icon, null));
		result.setTooltip(Tooltip.create(label)); return result;
	}
	private Button request(Component label, int x, int y, int w, int h, Runnable action) {
		var result = control(label, x, y, w, h, action, false, -1); actions.add(result); return result;
	}
	private Button navigation(Component label, int x, int y, int w, int h, Runnable action) {
		var button = control(label, x, y, w, h, action, false, -1); navigationActions.add(button); return button;
	}
	private Button cell(Component label, int x, int y, boolean selected, java.util.function.IntConsumer action,
			java.util.function.Consumer<GuiGraphics> painter) {
		var result = addRenderableWidget(new TerminalSkin.Control(leftPos + x, topPos + y, 22, 22, label, action, selected, -1, painter));
		result.setTooltip(Tooltip.create(label)); actions.add(result); return result;
	}
	private void rebuild() {
		menu.layoutCrafting(craftingVisible(), workspace ? 318 : 51, inventoryY - 78);
		synchronizeView();
		boolean focus = search != null && search.isFocused();
		int cursor = search == null ? 0 : search.getCursorPosition();
		if (search != null) query = search.getValue();
		clearWidgets(); actions.clear(); navigationActions.clear(); hoveredBees.clear(); install = null; remove = null;
		for (int page : workspace ? menu.scope() == TerminalScope.APIARY ? new int[]{1, 3} : new int[]{3} : menu.scope() == TerminalScope.APIARY ? new int[]{1, 2, 3, 4} : new int[]{2, 3, 4}) {
			var button = control(tr("tab." + page), 2, 42 + (page - 1) * 30, 26, 26, () -> switchTab(page), page == tab, page == 1 ? 0 : page == 2 ? 2 : page == 3 ? 3 : -1);
			navigationActions.add(button);
		}
		if (menu.scope() == TerminalScope.APIARY && (tab == 1 || tab == 3)) {
			navigation(own(sort == TerminalSearchRequest.Sort.CAPACITY ? "sort_capacity" : "sort_position"), 2, 168, 26, 26,
					() -> { sort = sort == TerminalSearchRequest.Sort.CAPACITY ? TerminalSearchRequest.Sort.POSITION : TerminalSearchRequest.Sort.CAPACITY; refresh(); })
					.setTooltip(Tooltip.create(own("sort_hint")));
			if (tab == 1) request(own("auto_bee"), 2, 198, 26, 26, this::automaticBee).setTooltip(Tooltip.create(own("auto_bee_hint")));
		}
		if (tab == 2 || tab == 4) navigation(own(switch (productSort) {
			case QUANTITY_DESC -> "sort_quantity_desc"; case QUANTITY_ASC -> "sort_quantity_asc"; default -> "sort_id";
		}), 2, 168, 26, 26, () -> {
			productSort = switch (productSort) {
				case POSITION -> TerminalSearchRequest.Sort.QUANTITY_DESC;
				case QUANTITY_DESC -> TerminalSearchRequest.Sort.QUANTITY_ASC;
				default -> TerminalSearchRequest.Sort.POSITION;
			}; scroll = 0; refresh();
		}).setTooltip(Tooltip.create(own("sort_quantity_hint")));
		if (menu.combinedTerminal()) navigation(tr(menu.scope() == TerminalScope.APIARY ? "mode.centrifuge" : "mode.bees"),
				218, 8, 75, 16, () -> minecraft.gameMode.handleInventoryButtonClick(menu.containerId, menu.scope() == TerminalScope.APIARY ? 11 : 10));
		search = addRenderableWidget(new EditBox(font, leftPos + 38, topPos + 29, 137, 16, own("search")));
		search.setTooltip(Tooltip.create(own("search_help")));
		search.setMaxLength(64); search.setValue(query); search.setHint(own("search")); search.setFocused(focus); if (focus) setFocused(search);
		search.setCursorPosition(cursor); search.setHighlightPos(cursor);
		search.setResponder(value -> { query = value; queryDirty = true; searchAt = Util.getMillis() + 300; updateEnabled(); });
		navigation(tr("refresh"), 179, 28, 43, 18, () -> { selected = -1; scroll = 0; restoreLocation = null; refresh(); });
		previousPage = navigation(own("previous"), 226, 28, 30, 18, () -> navigate(TerminalSearchRequest.Navigation.PREVIOUS));
		nextPage = navigation(tr("next"), 258, 28, 38, 18, () -> navigate(TerminalSearchRequest.Navigation.NEXT));
		var view = state.view();
		// JEI 返回时仍可能持有成员页快照，等待新订阅前不能将它解释为产物行。
		if (view != null && view.kind() == ((tab == 2 || tab == 4) ? NetworkSelectionSession.Kind.PRODUCTS : tab == 3 ? NetworkSelectionSession.Kind.UPGRADES : NetworkSelectionSession.Kind.MEMBERS)) {
			if (tab == 2 || tab == 4) productGrid(view);
			else if (selectedRow() == null) memberGrid(view);
			else if (tab == 1) beeActions();
			else upgradeActions();
		}
		if (craftingVisible()) {
			request(own("craft_clear"), workspace ? 398 : 202, inventoryY - (workspace ? 36 : 78), workspace ? 86 : 89, 18, () -> craft(CRAFT_CLEAR, -1, -1, 0));
			control(tr("amount", amount), workspace ? 398 : 202, inventoryY - (workspace ? 78 : 54), workspace ? 86 : 89, 18, () -> { amount = amount == 1 ? 16 : amount == 16 ? 64 : 1; rebuild(); }, false, -1)
					.setTooltip(Tooltip.create(own("craft_input_hint")));
		}
		if (workspace) {
			for (var widget : productPane.build(font, leftPos, topPos, inventoryY)) addRenderableWidget(widget);
			if (productPane.focusedSearch() != null) setFocused(productPane.focusedSearch());
		}
		updateEnabled();
	}
	private boolean craftingVisible() { return workspace || tab == 4; }
	private void synchronizeView() {
		if (displayed == state.view()) return;
		var priorRow = displayed != null && selected >= 0 && selected < displayed.rows().size() ? displayed.rows().get(selected) : null;
		var keep = restoreLocation != null ? restoreLocation : priorRow == null ? null : priorRow.location();
		var priorBee = priorRow != null && priorRow.bees().size() > target ? priorRow.bees().get(target).identity() : restoreBee;
		selected = -1; displayed = state.view(); products.clear(); bees.clear();
		if (displayed != null && displayed.kind() == NetworkSelectionSession.Kind.PRODUCTS)
			for (var row : displayed.rows()) products.add(new TerminalProductIcon(row));
		if (displayed != null && keep != null) {
			for (int i = 0; i < displayed.rows().size(); i++) if (keep.equals(displayed.rows().get(i).location())) {
				var next = displayed.rows().get(i);
				boolean sameBee = tab != 1 || next.bees().size() > target
						&& java.util.Objects.equals(priorBee, next.bees().get(target).identity());
				if (sameBee) selected = i; else confirmCage = false;
				break;
			}
			restoreLocation = null; restoreBee = null;
		}
	}
	private void productGrid(TerminalView view) {
		int visible = Math.max(1, (inventoryY - (tab == 4 ? 128 : 80)) / 24);
		int rows = (view.rows().size() + 8) / 9; scroll = Math.min(scroll, Math.max(0, rows - visible));
		for (int i = scroll * 9; i < Math.min(view.rows().size(), (scroll + visible) * 9); i++) {
			int row = i, x = 48 + i % 9 * 26, y = (tab == 4 ? 50 : 54) + (i / 9 - scroll) * 24; var product = products.get(i);
			cell(product.name().copy().append("\n").append(tr("owned", view.rows().get(i).owned()))
					.append("\n").append(tr("available", view.rows().get(i).available())).append("\n").append(tr("quick_take")),
					x, y, false, mouse -> take(row, mouse), g -> product.render(g, leftPos + x + 1, topPos + y + 1));
		}
	}
	private void memberGrid(TerminalView view) {
		int rowHeight = tab == 1 ? 44 : 30;
		int visible = Math.max(1, (inventoryY - 79) / rowHeight); scroll = Math.min(scroll, Math.max(0, view.rows().size() - visible));
		for (int i = scroll; i < Math.min(view.rows().size(), scroll + visible); i++) {
			int index = i, y = 52 + (i - scroll) * rowHeight; var row = view.rows().get(i);
			var label = Component.literal("#" + (i + 1) + " ").append(memberName(row));
			if (row.location() != null) { var p = row.location(); label.append(" · " + p.x() + "," + p.y() + "," + p.z()); }
			var header = control(label, 37, y, tab == 1 ? 252 : 222, 15, () -> select(index, 0), false, -1);
			header.setTooltip(Tooltip.create(locationText(row))); actions.add(header);
			if (tab == 1) for (var bee : row.bees()) {
				int x = 45 + bee.slot() * 78, yy = y + 17;
				beeCell(bee, x, yy, false, () -> select(index, bee.slot()));
				feedCell(bee, x + 26, yy, () -> select(index, bee.slot()));
			}
			else {
				var block = row.location() == null ? ItemStack.EMPTY : new ItemStack(BuiltInRegistries.BLOCK.get(ResourceLocation.parse(row.location().machine())));
				cell(locationText(row), 265, y, false, ignored -> select(index, 0), g -> g.renderItem(block, leftPos + 268, topPos + y + 3));
			}
		}
	}
	private void select(int row, int slot) { craftingSource = false; selected = row; target = slot; upgradeChoice = 0; confirmCage = false; rebuild(); }
	private void detailHeader() {
		var row = selectedRow();
		control(own("back"), 37, 51, 34, 15, () -> { selected = -1; rebuild(); }, false, -1);
		if (row.location() != null) control(own("locate"), 254, 51, 38, 15, () -> NetworkMemberHighlight.show(row.location()), false, 4)
				.setTooltip(Tooltip.create(locationText(row).copy().append("\n").append(own("locate_hint"))));
	}
	private void beeActions() {
		detailHeader(); var row = selectedRow();
		for (var bee : row.bees()) {
			int x = 48 + bee.slot() * 79;
			beeCell(bee, x, 69, target == bee.slot(), () -> { target = bee.slot(); confirmCage = false; rebuild(); });
			feedCell(bee, x + 27, 69, () -> { target = bee.slot(); rebuild(); });
		}
		var bee = selectedBee();
		int y = Math.min(inventoryY - 58, 153);
		control(tr("amount", amount), 38, y, 60, 17, () -> { amount = amount == 1 ? 16 : amount == 16 ? 64 : 1; rebuild(); }, false, -1);
		request(tr("feed_in"), 102, y, 61, 17, () -> send(FEED_IN, amount));
		request(tr("feed_out"), 167, y, 61, 17, () -> send(FEED_OUT, amount));
		var toggle = request(own(bee != null && bee.feedingDisabled() ? "feed_enable" : "feed_disable"), 232, y, 61, 17,
				() -> send(bee != null && bee.feedingDisabled() ? FEED_ENABLE : FEED_DISABLE, 0));
		if (bee == null || bee.feedingCount() == 0) { actions.remove(toggle); toggle.active = false; }
		if (bee != null && bee.occupied()) {
			request(own(bee.enabled() ? "bee_disable" : "bee_enable"), 38, y + 18, 60, 16,
					() -> send(bee.enabled() ? BEE_DISABLE : BEE_ENABLE, 0)).setTooltip(Tooltip.create(own("bee_control_hint")));
			request(tr(confirmCage ? "cage_confirm" : "cage_out"), 102, y + 18, 191, 16, () -> {
				if (bee.progress() > 0 && !confirmCage) { confirmCage = true; rebuild(); } else send(CAGE_OUT, 1);
			}).setTooltip(Tooltip.create(tr("cage_warning")));
		} else request(tr("cage_in"), 102, y + 18, 191, 16, () -> send(CAGE_IN, 1)).setTooltip(Tooltip.create(own("bee_input_hint")));
	}
	private void beeCell(TerminalView.Bee bee, int x, int y, boolean selected, Runnable action) {
		Component label = bee.occupied() ? beeName(bee.type()) : tr("empty_bee");
		var beeType = ResourceLocation.tryParse(bee.type());
		if (bee.occupied() && beeType != null) hoveredBees.add(new HoveredBee(beeType,
				new net.minecraft.client.renderer.Rect2i(leftPos + x, topPos + y, 22, 22)));
		label = label.copy().append("\n").append(tr("progress", bee.progress(), bee.cycleTicks()));
		if (bee.occupied()) label = label.copy().append("\n").append(own(bee.enabled() ? "bee_enabled" : "bee_disabled"));
		if (bee.genes() != null) for (int i = 0; i < TerminalBeeGenes.FIELDS.size(); i++) {
			String field = TerminalBeeGenes.FIELDS.get(i), raw = bee.genes().values().get(i);
			String value = raw.startsWith(field + ".") ? raw.substring(field.length() + 1) : raw;
			Component detail = value.isEmpty() ? own("unknown") : Component.translatable("gui.productivebeesgenesis.bee_tooltip." + field + "." + value);
			label = label.copy().append("\n").append(Component.translatable("gui.productivebeesgenesis.bee_tooltip." + field, detail));
		}
		var visual = bee.occupied() && ResourceLocation.tryParse(bee.type()) != null ? bees.computeIfAbsent(bee.type(), type -> {
			var slot = new BeeSlot(); var data = new CompoundTag();
			data.putString("id", "productivebees:configurable_bee"); data.putString("type", type); slot.setBeeData(data); return slot;
		}) : null;
		cell(label, x, y, selected, ignored -> action.run(), g -> {
			if (visual != null) {
				beeRenderer.renderBee(g, leftPos + x + 2, topPos + y + 2, visual, 0);
				if (!bee.enabled()) g.drawString(font, "Ⅱ", leftPos + x + 13, topPos + y + 12, 0xffedb96b, false);
			}
			else g.drawString(font, "+", leftPos + x + 8, topPos + y + 7, TerminalSkin.MUTED, false);
		});
	}
	private void feedCell(TerminalView.Bee bee, int x, int y, Runnable action) {
		var item = bee.feedingItem().isEmpty() ? ItemStack.EMPTY : new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(bee.feedingItem())), bee.feedingCount());
		cell(item.isEmpty() ? own("empty_feed") : item.getHoverName().copy().append("\n").append(own(bee.feedingDisabled() ? "feed_disabled" : "feed_active")),
				x, y, false, ignored -> action.run(), g -> {
				if (!item.isEmpty()) { g.renderItem(item, leftPos + x + 3, topPos + y + 3); g.renderItemDecorations(font, item, leftPos + x + 3, topPos + y + 3); }
				if (bee.feedingDisabled()) g.renderOutline(leftPos + x + 1, topPos + y + 1, 20, 20, 0xffed7869);
			});
	}
	private void upgradeActions() {
		detailHeader();
		for (int i = 0; i < selectedRow().upgrades().size(); i++) {
			var upgrade = selectedRow().upgrades().get(i);
			int x = inventoryY < 190 ? 40 + i * 24 : 42 + i % 5 * 49, y = inventoryY < 190 ? 69 : 72 + i / 5 * 25;
			var icon = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(upgrade.item())));
			cell(icon.getHoverName().copy().append(" " + upgrade.installed() + "/" + upgrade.limit()), x, y, upgradeChoice == upgrade.choice(),
					ignored -> { upgradeChoice = upgrade.choice(); rebuild(); }, g -> {
					g.renderItem(icon, leftPos + x + 3, topPos + y + 2);
					g.drawString(font, Integer.toString(upgrade.installed()), leftPos + x + 13, topPos + y + 13, TerminalSkin.INK, true);
				});
		}
		int y = Math.min(inventoryY - 57, 157);
		control(tr("amount", amount), 38, y, 60, 16, () -> { amount = amount == 1 ? 16 : amount == 16 ? 64 : 1; rebuild(); }, false, -1);
		control(tr(batch ? "upgrade_page" : "upgrade_single"), 102, y, 91, 16, () -> { batch = !batch; rebuild(); }, batch, -1);
		install = request(tr("upgrade_install"), 38, y + 18, 122, 16, () -> send(batch ? UPGRADE_INSTALL_PAGE : UPGRADE_INSTALL, amount));
		remove = request(tr("upgrade_remove"), 165, y + 18, 127, 16, () -> send(batch ? UPGRADE_REMOVE_PAGE : UPGRADE_REMOVE, amount));
	}
	private void switchTab(int page) {
		if (!state.ready(Util.getMillis())) return;
		tab = page; selected = -1; scroll = 0; query = ""; search.setValue(""); refresh();
	}
	/** JEI 发送资产命令后只切换显示；等待其回执，再建立产物页订阅。 */
	public void prepareRecipeTransfer() {
		if (!workspace) tab = 4; selected = -1; scroll = 0; query = ""; if (search != null) search.setValue("");
		craftingRequested = false; queryDirty = true; searchAt = Util.getMillis();
		if (minecraft != null && minecraft.screen == this) rebuild();
	}
	private void refresh() {
		craftingRequested = false;
		query = search == null ? query : search.getValue();
		var request = state.beginLive(tab == 1 ? NetworkSelectionSession.Kind.MEMBERS : tab == 3 ? NetworkSelectionSession.Kind.UPGRADES : NetworkSelectionSession.Kind.PRODUCTS,
				query, TerminalSearchRequest.Navigation.FIRST, selectedSort(), TerminalClientNames.resolve(query), Util.getMillis()); if (request == null) return;
		queryDirty = false;
		PacketDistributor.sendToServer(request);
		selected = -1; rebuild();
	}
	private void navigate(TerminalSearchRequest.Navigation navigation) {
		var view = state.view(); if (queryDirty || view == null || !state.actionable(Util.getMillis())) return;
		var request = state.beginLive(view.kind(), query, navigation, selectedSort(), TerminalClientNames.resolve(query), Util.getMillis()); if (request == null) return;
		selected = -1; restoreLocation = null; scroll = 0; PacketDistributor.sendToServer(request); rebuild();
	}
	private TerminalSearchRequest.Sort selectedSort() { return tab == 2 || tab == 4 ? productSort : menu.scope() == TerminalScope.APIARY ? sort : TerminalSearchRequest.Sort.POSITION; }
	private void craft(TerminalRequest.Operation operation, int slot, int inventorySlot, int count) {
		if (queryDirty || operation != CRAFTING && (!state.actionable(Util.getMillis()) || menu.craftingGeneration() == 0 || menu.craftingStatus() == 3)) return;
		var request = state.beginCrafting(operation, operation == CRAFTING ? 0 : menu.craftingGeneration(), slot, inventorySlot, count, Util.getMillis());
		if (request != null) { if (operation == CRAFTING) craftingRequested = true; PacketDistributor.sendToServer(request); updateEnabled(); }
	}
	private void automaticBee() {
		if (queryDirty || menu.scope() != TerminalScope.APIARY || tab != 1) return;
		var request = state.begin(AUTO_BEE_IN, -1, -1, sourceSlot, 1, Util.getMillis());
		if (request != null) { PacketDistributor.sendToServer(request); rebuild(); }
	}
	private void send(TerminalRequest.Operation operation, int count) {
		if (queryDirty) return;
		var row = selectedRow();
		var request = state.begin(operation, selected, TerminalRequest.upgradeAction(operation) ? upgradeChoice : target,
				operation == BEE_ENABLE || operation == BEE_DISABLE ? -1 : sourceSlot, count, Util.getMillis());
		if (request == null) return; PacketDistributor.sendToServer(request);
		if (!TerminalRequest.upgradePreview(operation)) {
			restoreLocation = row == null ? null : row.location(); confirmCage = false;
			restoreBee = selectedBee() == null ? null : selectedBee().identity();
		}
		rebuild();
	}
	private void take(int row, int mouse) {
		if (queryDirty) return;
		var view = state.view(); if (view == null) return;
		var request = state.begin(TAKE_PRODUCT, row, -1, -1, view.rows().get(row).fluid() ? 1000 : mouse == 1 ? 1 : 64, Util.getMillis());
		if (request != null) { PacketDistributor.sendToServer(request); rebuild(); }
	}
	private TerminalView.Row selectedRow() { return state.view() != null && selected >= 0 && selected < state.view().rows().size() ? state.view().rows().get(selected) : null; }
	private TerminalView.Bee selectedBee() { return selectedRow() == null ? null : selectedRow().bees().stream().filter(bee -> bee.slot() == target).findFirst().orElse(null); }
	private TerminalView.Upgrade selectedUpgrade() { return selectedRow() == null ? null : selectedRow().upgrades().stream().filter(upgrade -> upgrade.choice() == upgradeChoice).findFirst().orElse(null); }
	private void updateEnabled() {
		long now = Util.getMillis();
		for (var button : actions) button.active = state.actionable(now) && !queryDirty;
		for (var button : navigationActions) button.active = state.ready(now) && !queryDirty;
		if (previousPage != null) previousPage.active = state.actionable(now) && !queryDirty && state.hasPrevious();
		if (nextPage != null) nextPage.active = state.actionable(now) && !queryDirty && state.view() != null && state.view().hasNext();
		if (install == null) return;
		var upgrade = selectedUpgrade(); boolean allowed = state.actionable(now) && !queryDirty && menu.canUpgrade() && upgrade != null;
		install.active = allowed && (batch || upgrade.installable()); remove.active = allowed && (batch || upgrade.installed() > 0);
		install.setTooltip(Tooltip.create(previewHint(true))); remove.setTooltip(Tooltip.create(previewHint(false)));
	}
	private Component previewHint(boolean installing) {
		return !menu.canUpgrade() ? tr("upgrade_permission") : matchingPreview(installing) ? UpgradePreviewText.text(state.preview(), batch) : tr("preview_hover");
	}
	@Override protected void containerTick() {
		if (minecraft.player == null || minecraft.player.containerMenu != menu) { state.close(); minecraft.setScreen(null); return; }
		super.containerTick(); long now = Util.getMillis(); state.tick(now);
		productPane.tick(workspace);
		if (craftingVisible() && !craftingRequested && !queryDirty && state.actionable(now) && state.view() != null) craft(CRAFTING, -1, -1, 0);
		if (queryDirty && now >= searchAt && state.ready(now)) { selected = -1; scroll = 0; restoreLocation = null; refresh(); }
		if (displayed != state.view() || notice != state.notice()) {
			notice = state.notice(); rebuild();
		}
		int activeHover = install != null && selectedRow() != null ? install.isHovered() ? 1 : remove.isHovered() ? 2 : 0 : 0;
		if (hover != activeHover) { hover = activeHover; hoverAt = now; }
		if (hover != 0 && now - hoverAt > 350 && state.actionable(now) && !queryDirty && !matchingPreview(hover == 1))
			send(hover == 1 ? UPGRADE_PREVIEW_INSTALL : UPGRADE_PREVIEW_REMOVE, amount);
		updateEnabled();
	}
	@Override public boolean keyPressed(int key, int scan, int modifiers) {
		if (workspace && productPane.keyPressed(key, scan, modifiers)) return true;
		if (search.isFocused() && key == GLFW.GLFW_KEY_ENTER) { selected = -1; scroll = 0; refresh(); search.setFocused(false); setFocused(null); return true; }
		if (search.isFocused() && key != GLFW.GLFW_KEY_ESCAPE) return search.keyPressed(key, scan, modifiers);
		return super.keyPressed(key, scan, modifiers);
	}
	@Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
		if (workspace && productPane.scroll(x, y, vertical)) return true;
		if (x >= leftPos + 32 && x < leftPos + WIDTH && y >= topPos + 48 && y < topPos + inventoryY - 24 && selectedRow() == null) {
			if (vertical == 0) return false;
			scroll = Math.clamp(scroll + (vertical > 0 ? -1 : 1), 0, maxScroll()); rebuild(); return true;
		}
		return super.mouseScrolled(x, y, horizontal, vertical);
	}
	@Override public boolean mouseClicked(double x, double y, int button) {
		if (workspace && productPane.clear(x, y, button)) return true;
		if (button == 0 && menu.meStatus() == com.ayoshiko.productivebeesgenesis.apiculture.bridge.MeBridgeStatus.ONLINE && x >= leftPos+9 && x < leftPos+28 && y >= topPos+10 && y < topPos+22) {
			minecraft.setScreen(new MeTerminalScreen(this, menu)); return true;
		}
		if (craftingVisible() && (button == 0 || button == 1)) for (var slot : menu.slots) if (slot.index >= 36 && slot.isActive()
				&& x >= leftPos + slot.x && x < leftPos + slot.x + 16 && y >= topPos + slot.y && y < topPos + slot.y + 16) {
			if (slot.index == 45) craft(CRAFT_TAKE, -1, -1, hasShiftDown() ? 8 : 1);
			else { craftingSource = true; craftingTarget = slot.index - 36; boolean take = button == 1 || hasShiftDown(); craft(take ? CRAFT_OUT : CRAFT_IN, craftingTarget, take ? -1 : sourceSlot, take ? hasShiftDown() ? 64 : 1 : amount); }
			return true;
		}
		if (button == 1 && search.isMouseOver(x, y)) { search.setValue(""); setFocused(search); return true; }
		if (button == 0 && maxScroll() > 0 && x >= leftPos + 289 && x < leftPos + 297 && y >= topPos + 51 && y < topPos + inventoryY - 28) {
			draggingScroll = true; scrollTo(y); return true;
		}
		if (button == 0) for (var slot : menu.slots) if (slot.index < 36 && x >= leftPos + slot.x && x < leftPos + slot.x + 16 && y >= topPos + slot.y && y < topPos + slot.y + 16) {
			sourceSlot = slot.getContainerSlot(); confirmCage = false;
			if (hasShiftDown() && (tab == 4 || workspace && craftingSource)) craft(CRAFT_IN, craftingTarget, sourceSlot, 64);
			else if (hasShiftDown() && tab == 1 && menu.scope() == TerminalScope.APIARY) automaticBee();
			rebuild(); return true;
		}
		return super.mouseClicked(x, y, button);
	}
	@Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
		if (draggingScroll && button == 0) { scrollTo(y); return true; }
		return super.mouseDragged(x, y, button, dx, dy);
	}
	@Override public boolean mouseReleased(double x, double y, int button) {
		if (button == 0 && draggingScroll) { draggingScroll = false; return true; }
		return super.mouseReleased(x, y, button);
	}
	private int maxScroll() {
		var view = state.view(); if (view == null || selectedRow() != null) return 0;
		return tab == 2 || tab == 4 ? Math.max(0, (view.rows().size() + 8) / 9 - Math.max(1, (inventoryY - (tab == 4 ? 128 : 80)) / 24))
				: Math.max(0, view.rows().size() - Math.max(1, (inventoryY - 79) / (tab == 1 ? 44 : 30)));
	}
	private void scrollTo(double mouseY) {
		scroll = Math.clamp((int) Math.round((mouseY - topPos - 57) * maxScroll() / Math.max(1, inventoryY - 91)), 0, maxScroll()); rebuild();
	}
	@Override protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
		TerminalSkin.panel(g, leftPos + 30, topPos, imageWidth - 30, imageHeight);
		g.fill(leftPos + 36, topPos + 6, leftPos + 297, topPos + 24, 0xff182d36);
		g.fill(leftPos + 35, topPos + 48, leftPos + 297, topPos + inventoryY - 25, 0xff14232a);
		if (maxScroll() > 0) {
			g.fill(leftPos + 290, topPos + 51, leftPos + 296, topPos + inventoryY - 28, 0xff0c171c);
			int thumb = 51 + scroll * (inventoryY - 91) / maxScroll();
			TerminalSkin.panel(g, leftPos + 290, topPos + thumb, 6, 12);
		}
		for (var slot : menu.slots) {
			if (!slot.isActive()) continue;
			TerminalSkin.panel(g, leftPos + slot.x - 1, topPos + slot.y - 1, 18, 18);
			if (slot.index < 36 && slot.getContainerSlot() == sourceSlot || slot.index == 36 + craftingTarget) g.renderOutline(leftPos + slot.x - 1, topPos + slot.y - 1, 18, 18, TerminalSkin.GOLD);
		}
		if (workspace) {
			g.fill(leftPos + 306, topPos + 6, leftPos + 307, topPos + imageHeight - 6, 0xff5c5845);
			g.fill(leftPos + 310, topPos + 69, leftPos + 484, topPos + inventoryY - 104, 0xff14232a);
		}
		if (craftingVisible()) g.drawString(font, "→", leftPos + (workspace ? 393 : 126), topPos + inventoryY - 54, TerminalSkin.INK, false);
	}
	@Override public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		super.render(g, mouseX, mouseY, partialTick); renderTooltip(g, mouseX, mouseY);
		MeBridgeIndicator.render(g, font, menu.meStatus(), leftPos + 12, topPos + 12, mouseX, mouseY);
		if (workspace && mouseX >= leftPos + 38 && mouseX < leftPos + 297 && mouseY >= topPos + inventoryY && mouseY < topPos + imageHeight - 6)
			g.renderComponentTooltip(font, List.of(own("workspace_members", menu.value(1), menu.value(2), menu.value(3)), own("workspace_energy", menu.energy(false), menu.energy(true))), mouseX, mouseY);
		if (craftingVisible()) for (var slot : menu.slots) if (slot.index >= 36 && mouseX >= leftPos + slot.x && mouseX < leftPos + slot.x + 16
				&& mouseY >= topPos + slot.y && mouseY < topPos + slot.y + 16) {
			var hint = new ArrayList<Component>(); if (slot.hasItem()) hint.add(slot.getItem().getHoverName());
			hint.add(own(slot.index == 45 ? "craft_output_hint" : "craft_input_hint"));
			g.renderComponentTooltip(font, hint, mouseX, mouseY); break;
		}
	}
	@Override protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
		line(g, title, 41, 11, menu.combinedTerminal() ? 172 : 246, TerminalSkin.GOLD);
		var row = selectedRow();
		if (row != null) {
			line(g, memberName(row), 76, 54, 172, TerminalSkin.INK);
			if (tab == 1 && inventoryY >= 186) {
				var bee = selectedBee();
				if (bee != null) {
					line(g, bee.occupied() ? beeName(bee.type()) : tr("empty_bee"), 42, 104, 246, TerminalSkin.INK);
					line(g, bee.pending() ? tr("pending") : tr("progress", bee.progress(), bee.cycleTicks()), 42, 117, 246, TerminalSkin.MUTED);
				}
			}
		} else if (state.view() != null && state.view().rows().isEmpty()) line(g, own(state.view().hasNext() ? "continue_search" : "no_matches"), 45, tab == 4 ? 54 : 68, 240, TerminalSkin.MUTED);
		Component status = queryDirty || state.notice() == TerminalClientState.Notice.WAITING ? own("syncing")
				: state.notice() == TerminalClientState.Notice.EXPIRED || state.notice() == TerminalClientState.Notice.TIMEOUT ? own("sync_wait")
				: state.exchangeResult() != null ? state.exchangeResult().status() == TerminalReply.Status.MOVED ? own("moved", state.exchangeResult().moved())
						: tr("result." + state.exchangeResult().status().name().toLowerCase(java.util.Locale.ROOT), state.exchangeResult().moved()) : own("live");
		long sortAge = state.sortAgeSeconds(Util.getMillis());
		if ((tab == 2 || tab == 4) && sortAge >= 0) status = status.copy().append(" · ").append(own("sort_age", sortAge));
		if (craftingVisible() && menu.craftingStatus() != 1) status = own(menu.craftingStatus() == 2 ? "craft_pending" : menu.craftingStatus() == 3 ? "craft_quarantined" : "craft_unavailable");
		line(g, status, 38, inventoryY - 22, 253, TerminalSkin.MUTED);
		line(g, menu.wirelessTerminal() ? own("wireless_energy", menu.wirelessEnergy()) : inventory.getDisplayName(), workspace ? 318 : 40, inventoryY - 11, workspace ? 92 : menu.wirelessTerminal() ? 128 : 63, TerminalSkin.INK);
		line(g, tr("inventory_slot", sourceSlot + 1), workspace ? 414 : 174, inventoryY - 11, workspace ? 70 : 118, TerminalSkin.MUTED);
		if (workspace) {
			productPane.labels(g, font, inventoryY);
			line(g, own("workspace_status"), 42, inventoryY + 4, 246, TerminalSkin.GOLD);
			line(g, own("workspace_members", menu.value(1), menu.value(2), menu.value(3)), 42, inventoryY + 22, 246, TerminalSkin.INK);
			line(g, own("workspace_energy", menu.energy(false), menu.energy(true)), 42, inventoryY + 38, 246, TerminalSkin.MUTED);
			line(g, own(menu.productionRunning() ? "workspace_running" : "workspace_stopped"), 42, inventoryY + 54, 246, TerminalSkin.MUTED);
		}
	}
	private void line(GuiGraphics g, Component value, int x, int y, int width, int color) { g.drawString(font, font.plainSubstrByWidth(value.getString(), width), x, y, color, false); }
	private Component memberName(TerminalView.Row row) {
		if (row.location() == null) return Component.literal(row.label());
		return BuiltInRegistries.BLOCK.get(ResourceLocation.parse(row.location().machine())).getName();
	}
	private Component beeName(String type) {
		var id = ResourceLocation.tryParse(type); return id == null ? Component.literal(type) : BeeInfoHelper.getBeeDisplayName(id);
	}
	private boolean matchingPreview(boolean installing) {
		var preview = state.preview();
		return preview != null && preview.row() == selected && preview.choice() == upgradeChoice && preview.inventorySlot() == sourceSlot
				&& preview.requested() == amount && preview.installing() == installing;
	}
	private Component locationText(TerminalView.Row row) {
		if (row.location() == null) return memberName(row);
		var p = row.location();
		var text = memberName(row).copy().append("\n" + p.dimension() + "\n" + p.x() + ", " + p.y() + ", " + p.z());
		if (row.apiary() != null) text.append("\n").append(own("capacity", row.apiary().cycleTicks(), row.apiary().productivity()));
		return text;
	}
	/** 仅返回当前可见蜂格；JEI 自己处理玩家的配方／用途按键，不在常驻界面引用 JEI 类型。 */
	public java.util.Optional<HoveredBee> getBeeUnderMouse(double x, double y) {
		if (state.view() == null || queryDirty) return java.util.Optional.empty();
		return hoveredBees.stream().filter(bee -> bee.area().contains((int) x, (int) y)).findFirst();
	}
	public record HoveredBee(ResourceLocation type, net.minecraft.client.renderer.Rect2i area) { }
	@Override public void removed() {
		var row = selectedRow(); if (row != null) { restoreLocation = row.location(); restoreBee = selectedBee() == null ? null : selectedBee().identity(); }
		super.removed(); displayed = null; products.clear(); bees.clear(); hoveredBees.clear();
	}
}
