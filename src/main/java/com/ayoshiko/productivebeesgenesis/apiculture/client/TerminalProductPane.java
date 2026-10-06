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

/** 宽屏常驻产物区；查询与管理页各自保留过滤、页码和选择租约。 */
final class TerminalProductPane {
	static final int LEFT = 312, WIDTH = 172;
	private final TerminalClientState state;
	private final Runnable rebuild;
	private final List<AbstractWidget> widgets = new ArrayList<>();
	private final List<TerminalSkin.Control> buttons = new ArrayList<>();
	private final List<TerminalProductIcon> icons = new ArrayList<>();
	private TerminalView shown;
	private TerminalClientState.Notice notice;
	private TerminalSearchRequest.Sort sort = TerminalSearchRequest.Sort.POSITION;
	private EditBox search;
	private String query = "";
	private boolean dirty, subscribed;
	private long searchAt;
	private int scroll, visible, left, top;
	private TerminalSkin.Control previous, next, refresh, ordering;
	TerminalProductPane(NetworkCoreMenu menu, Runnable rebuild) { state = menu.productState(); this.rebuild = rebuild; }
	private static Component text(String key, Object... args) { return Component.translatable("screen.productivebeesgenesis.network.terminal." + key, args); }
	List<AbstractWidget> build(Font font, int x, int y, int inventoryY) {
		left = x; top = y; visible = Math.max(1, (inventoryY - 100 - 72) / 22);
		boolean focus = search != null && search.isFocused(); int cursor = search == null ? 0 : search.getCursorPosition();
		if (search != null) query = search.getValue();
		widgets.clear(); buttons.clear();
		if (shown != state.view()) { shown = state.view(); icons.clear(); if (shown != null) for (var row : shown.rows()) icons.add(new TerminalProductIcon(row)); }
		notice = state.notice();
		search = new EditBox(font, left + LEFT, top + 29, 113, 16, text("workspace_search"));
		search.setMaxLength(64); search.setValue(query); search.setHint(text("workspace_search")); search.setTooltip(Tooltip.create(text("search_help")));
		search.setFocused(focus); search.setCursorPosition(cursor); search.setHighlightPos(cursor);
		search.setResponder(value -> { query = value; dirty = true; searchAt = Util.getMillis() + 300; updateEnabled(); }); widgets.add(search);
		refresh = button("workspace_refresh", 428, 28, 56, () -> request(TerminalSearchRequest.Navigation.FIRST));
		previous = button("previous", LEFT, 49, 24, () -> request(TerminalSearchRequest.Navigation.PREVIOUS));
		next = button("workspace_next", LEFT + 28, 49, 24, () -> request(TerminalSearchRequest.Navigation.NEXT));
		ordering = button(switch (sort) { case QUANTITY_DESC -> "sort_quantity_desc"; case QUANTITY_ASC -> "sort_quantity_asc"; default -> "sort_id"; }, LEFT + 56, 49, WIDTH - 56, () -> {
			sort = switch (sort) { case POSITION -> TerminalSearchRequest.Sort.QUANTITY_DESC; case QUANTITY_DESC -> TerminalSearchRequest.Sort.QUANTITY_ASC; default -> TerminalSearchRequest.Sort.POSITION; };
			request(TerminalSearchRequest.Navigation.FIRST);
		});
		scroll = Math.min(scroll, maxScroll());
		if (shown != null) for (int i = scroll * 8; i < Math.min(shown.rows().size(), (scroll + visible) * 8); i++) {
			int index = i, cellX = left + LEFT + i % 8 * 21, cellY = top + 72 + (i / 8 - scroll) * 22;
			var row = shown.rows().get(i); var icon = icons.get(i);
			var label = icon.name().copy().append("\n").append(Component.translatable("screen.productivebeesgenesis.network.owned", row.owned()))
					.append("\n").append(Component.translatable("screen.productivebeesgenesis.network.available", row.available()));
			var button = new TerminalSkin.Control(cellX, cellY, 20, 20, label, mouse -> take(index, mouse), false, -1, g -> icon.render(g, cellX, cellY));
			button.setTooltip(Tooltip.create(label)); buttons.add(button); widgets.add(button);
		}
		updateEnabled(); return widgets;
	}
	private TerminalSkin.Control button(String key, int x, int y, int width, Runnable action) {
		var label = text(key); var button = new TerminalSkin.Control(left + x, top + y, width, 17, label, ignored -> action.run(), false, -1, null);
		button.setTooltip(Tooltip.create(label)); widgets.add(button); buttons.add(button); return button;
	}
	EditBox focusedSearch() { return search != null && search.isFocused() ? search : null; }
	void tick(boolean visible) {
		long now = Util.getMillis(); state.tick(now);
		if (!visible) {
			if (subscribed && state.ready(now)) { var request = state.begin(TerminalRequest.Operation.CANCEL, -1, -1, -1, 0, now); if (request != null) { PacketDistributor.sendToServer(request); subscribed = false; } }
			return;
		}
		if ((!subscribed || dirty && now >= searchAt) && state.ready(now)) request(TerminalSearchRequest.Navigation.FIRST);
		if (shown != state.view() || notice != state.notice()) rebuild.run();
		updateEnabled();
	}
	private void request(TerminalSearchRequest.Navigation navigation) {
		if (navigation != TerminalSearchRequest.Navigation.FIRST && (dirty || !state.actionable(Util.getMillis()))) return;
		var request = state.beginLive(NetworkSelectionSession.Kind.PRODUCTS, query, navigation, sort, TerminalClientNames.resolve(query), Util.getMillis());
		if (request != null) { dirty = false; subscribed = true; scroll = 0; PacketDistributor.sendToServer(request); rebuild.run(); }
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
	private int maxScroll() { return shown == null ? 0 : Math.max(0, (shown.rows().size() + 7) / 8 - visible); }
	boolean scroll(double x, double y, double delta) {
		if (delta == 0 || x < left + LEFT || x >= left + LEFT + WIDTH || y < top + 72 || y >= top + 72 + visible * 22) return false;
		scroll = Math.clamp(scroll + (delta > 0 ? -1 : 1), 0, maxScroll()); rebuild.run(); return true;
	}
	boolean keyPressed(int key, int scan, int modifiers) {
		if (search == null || !search.isFocused()) return false;
		if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER) { request(TerminalSearchRequest.Navigation.FIRST); return true; }
		return key != org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE && search.keyPressed(key, scan, modifiers);
	}
	boolean clear(double x, double y, int button) { if (button != 1 || search == null || !search.isMouseOver(x, y)) return false; search.setValue(""); return true; }
	void labels(GuiGraphics g, Font font, int inventoryY) {
		g.drawString(font, text("workspace_products"), LEFT, 11, TerminalSkin.GOLD, false);
		Component status = dirty || state.notice() == TerminalClientState.Notice.WAITING ? text("syncing") : state.notice() == TerminalClientState.Notice.EXPIRED || state.notice() == TerminalClientState.Notice.TIMEOUT ? text("sync_wait")
				: state.exchangeResult() != null ? Component.translatable("screen.productivebeesgenesis.network.result." + state.exchangeResult().status().name().toLowerCase(java.util.Locale.ROOT), state.exchangeResult().moved()) : text("live");
		g.drawString(font, font.plainSubstrByWidth(status.getString(), WIDTH), LEFT, inventoryY - 100, TerminalSkin.MUTED, false);
	}
}
