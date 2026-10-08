package com.ayoshiko.productivebeesgenesis.apiculture.client;

import com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkCoreMenu;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.*;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.Util;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/** 库存首页与管理页共用查询；切换布局不新建菜单、账户或服务端预算。 */
final class TerminalProductPane {
	static final int LEFT = 312, WIDTH = 172;
	private final TerminalClientState state;
	private final MeInventoryPane mePane;
	private final Runnable rebuild;
	private final List<AbstractWidget> widgets = new ArrayList<>();
	private final List<TerminalSkin.Control> buttons = new ArrayList<>();
	private final List<TerminalProductIcon> icons = new ArrayList<>();
	private final TerminalGridViewport grid = new TerminalGridViewport();
	private TerminalView shown;
	private TerminalClientState.Notice notice;
	private TerminalSearchRequest.Sort sort = TerminalSearchRequest.Sort.POSITION;
	private EditBox search;
	private String query = "";
	private boolean dirty, subscribed, meMode;
	private long searchAt;
	private int left, top, paneWidth;
	private TerminalSkin.Control previous, next, refresh, ordering;
	TerminalProductPane(NetworkCoreMenu menu, Runnable rebuild) {
		state = menu.productState(); this.rebuild = rebuild;
		mePane = new MeInventoryPane(menu, rebuild, () -> { var client = net.minecraft.client.Minecraft.getInstance(); client.setScreen(new MeTerminalScreen(client.screen, menu, true)); });
	}
	private static Component text(String key, Object... args) { return Component.translatable("screen.productivebeesgenesis.network.terminal." + key, args); }
	List<AbstractWidget> build(Font font, int x, int y, int inventoryY) { return build(font, x, y, inventoryY, false); }
	List<AbstractWidget> build(Font font, int x, int y, int inventoryY, boolean home) {
		left = x + (home ? 8 : LEFT); top = y + 29; paneWidth = home ? 288 : WIDTH;
		int rows = Math.max(1, (inventoryY - (home ? 135 : 149)) / 18);
		boolean focus = search != null && search.isFocused(); int cursor = search == null ? 0 : search.getCursorPosition();
		if (search != null) query = search.getValue();
		widgets.clear(); buttons.clear();
		var source = new TerminalSkin.Control(x + (home ? 188 : LEFT), y + 7, home ? 108 : WIDTH, 17,
				meMode ? MeInventoryPane.text("storage") : text("workspace_products"), ignored -> { meMode = !meMode; rebuild.run(); }, meMode, -1, null);
		source.setTooltip(Tooltip.create(MeInventoryPane.text("source_hint"))); widgets.add(source);
		if (meMode) { widgets.addAll(mePane.build(font, left, top, paneWidth, rows)); return widgets; }
		if (shown != state.view()) { shown = state.view(); icons.clear(); if (shown != null) for (var row : shown.rows()) icons.add(new TerminalProductIcon(row)); }
		notice = state.notice();
		search = new EditBox(font, left + 24, top, paneWidth - 46, 16, text("workspace_search"));
		search.setMaxLength(64); search.setValue(query); search.setHint(text("workspace_search")); search.setTooltip(Tooltip.create(text("search_help")));
		search.setFocused(focus); search.setCursorPosition(cursor); search.setHighlightPos(cursor);
		search.setResponder(value -> { query = value; dirty = true; searchAt = Util.getMillis() + 300; updateEnabled(); }); widgets.add(search);
		refresh = button("workspace_refresh", paneWidth - 20, -1, 20, 16, 14, () -> request(TerminalSearchRequest.Navigation.FIRST));
		ordering = button(switch (sort) { case QUANTITY_DESC -> "sort_quantity_desc"; case QUANTITY_ASC -> "sort_quantity_asc"; default -> "sort_id"; }, 0, 20, 20, 20, 9, () -> {
			sort = switch (sort) { case POSITION -> TerminalSearchRequest.Sort.QUANTITY_DESC; case QUANTITY_DESC -> TerminalSearchRequest.Sort.QUANTITY_ASC; default -> TerminalSearchRequest.Sort.POSITION; };
			request(TerminalSearchRequest.Navigation.FIRST);
		});
		grid.layout(left + 24, top + 22, paneWidth - 24, rows, shown == null ? 0 : shown.rows().size());
		int bottom = 24 + rows * 18;
		previous = button("previous", 24, bottom, 22, 14, -1, () -> request(TerminalSearchRequest.Navigation.PREVIOUS));
		next = button("workspace_next", 48, bottom, 22, 14, -1, () -> request(TerminalSearchRequest.Navigation.NEXT));
		for (int i = grid.first(); i < grid.end(); i++) {
			int index = i, cellX = grid.cellX(i), cellY = grid.cellY(i);
			var row = shown.rows().get(i); var icon = icons.get(i);
			var label = icon.name().copy().append("\n").append(Component.translatable("screen.productivebeesgenesis.network.owned", row.owned()))
					.append("\n").append(Component.translatable("screen.productivebeesgenesis.network.available", row.available()));
			var button = new TerminalSkin.Control(cellX, cellY, 18, 18, label, mouse -> take(index, mouse), false, -1,
					g -> icon.render(g, cellX, cellY, true)).slot();
			button.setTooltip(Tooltip.create(label)); buttons.add(button); widgets.add(button);
		}
		updateEnabled(); return widgets;
	}
	private TerminalSkin.Control button(String key, int x, int y, int width, int height, int icon, Runnable action) {
		var label = text(key); var button = new TerminalSkin.Control(left + x, top + y, width, height, label, ignored -> action.run(), false, icon, null);
		button.setTooltip(Tooltip.create(label)); widgets.add(button); buttons.add(button); return button;
	}
	EditBox focusedSearch() { if (meMode) return mePane.focusedSearch(); return search != null && search.isFocused() ? search : null; }
	void tick(boolean visible) {
		long now = Util.getMillis(); state.tick(now); mePane.tick(visible && meMode);
		if (!visible || meMode) {
			if (subscribed && state.ready(now)) { var request = state.begin(TerminalRequest.Operation.CANCEL, -1, -1, -1, 0, now); if (request != null) { PacketDistributor.sendToServer(request); subscribed = false; } }
			return;
		}
		if ((!subscribed || dirty && now >= searchAt) && state.ready(now)) request(TerminalSearchRequest.Navigation.FIRST);
		if (shown != state.view() || notice != state.notice()) rebuild.run();
		updateEnabled();
	}
	private void request(TerminalSearchRequest.Navigation navigation) {
		if (navigation != TerminalSearchRequest.Navigation.FIRST && (dirty || !state.actionable(Util.getMillis()))) return;
		if (navigation == TerminalSearchRequest.Navigation.PREVIOUS && !state.hasPrevious() || navigation == TerminalSearchRequest.Navigation.NEXT && (shown == null || !shown.hasNext())) return;
		var request = state.beginLive(NetworkSelectionSession.Kind.PRODUCTS, query, navigation, sort, TerminalClientNames.resolve(query), Util.getMillis());
		if (request != null) { dirty = false; subscribed = true; grid.offset = 0; PacketDistributor.sendToServer(request); rebuild.run(); }
	}
	private void take(int row, int mouse) {
		if (dirty || !state.actionable(Util.getMillis()) || state.view() != shown) return;
		var request = state.begin(TerminalRequest.Operation.TAKE_PRODUCT, row, -1, -1, shown.rows().get(row).fluid() ? 1000 : mouse == 1 ? 1 : 64, Util.getMillis());
		if (request != null) { PacketDistributor.sendToServer(request); updateEnabled(); }
	}
	private void updateEnabled() {
		long now = Util.getMillis(); for (var button : buttons) button.active = state.actionable(now) && !dirty;
		if (refresh != null) refresh.active = state.ready(now);
		if (ordering != null) ordering.active = state.ready(now) && !dirty;
		if (previous != null) previous.active &= state.hasPrevious();
		if (next != null) next.active &= shown != null && shown.hasNext();
	}
	boolean scroll(double x, double y, double delta) {
		if (meMode) return mePane.scroll(x, y, delta);
		if (delta == 0 || !grid.contains(x, y)) return false;
		int next = Math.clamp(grid.offset + (delta > 0 ? -1 : 1), 0, grid.max());
		if (next != grid.offset) { grid.offset = next; rebuild.run(); }
		else request(delta > 0 ? TerminalSearchRequest.Navigation.PREVIOUS : TerminalSearchRequest.Navigation.NEXT);
		return true;
	}
	boolean keyPressed(int key, int scan, int modifiers) {
		if (meMode) return mePane.keyPressed(key, scan, modifiers);
		if (search != null && search.isFocused()) {
			if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER) { request(TerminalSearchRequest.Navigation.FIRST); search.setFocused(false); return true; }
			return key != org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE && search.keyPressed(key, scan, modifiers);
		}
		if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_UP || key == org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_DOWN) { request(key == org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_UP ? TerminalSearchRequest.Navigation.PREVIOUS : TerminalSearchRequest.Navigation.NEXT); return true; }
		return false;
	}
	boolean clear(double x, double y, int button) {
		if (meMode) return mePane.click(x, y, button);
		if (grid.press(x, y, button)) { rebuild.run(); return true; }
		if (button != 1 || search == null || !search.isMouseOver(x, y)) return false;
		search.setValue(""); return true;
	}
	boolean drag(double y, int button) { if (meMode) return mePane.drag(y, button); if (!grid.drag(y, button)) return false; rebuild.run(); return true; }
	boolean release(int button) { return meMode ? mePane.release(button) : grid.release(button); }
	void background(GuiGraphics g) { if (meMode) mePane.background(g); else grid.render(g); }
	void labels(GuiGraphics g, Font font, int originX, int originY) {
		if (meMode) { mePane.labels(g, font, originX, originY); return; }
		Component status = dirty || state.notice() == TerminalClientState.Notice.WAITING ? text("syncing") : state.notice() == TerminalClientState.Notice.EXPIRED || state.notice() == TerminalClientState.Notice.TIMEOUT ? text("sync_wait")
				: state.exchangeResult() != null ? Component.translatable("screen.productivebeesgenesis.network.result." + state.exchangeResult().status().name().toLowerCase(java.util.Locale.ROOT), state.exchangeResult().moved()) : text("live");
		g.drawString(font, font.plainSubstrByWidth(status.getString(), paneWidth - 100), left - originX + 76, grid.y - originY + grid.rows * 18 + 5, TerminalSkin.MUTED, false);
	}
}
