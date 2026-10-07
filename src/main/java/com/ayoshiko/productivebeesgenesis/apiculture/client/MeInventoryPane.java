package com.ayoshiko.productivebeesgenesis.apiculture.client;

import com.ayoshiko.productivebeesgenesis.apiculture.me.*;
import java.util.*;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.neoforged.neoforge.network.PacketDistributor;
import static com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalRequest.Action.*;

/** 同一菜单内的八项 ME 浏览区；大列表、资产键与转移结果都由服务器持有。 */
final class MeInventoryPane {
	private final AbstractContainerMenu menu;
	private final MeTerminalSession session;
	private final Runnable rebuild, openPlan;
	private final List<AbstractWidget> widgets = new ArrayList<>();
	private final List<TerminalSkin.Control> buttons = new ArrayList<>();
	private MeTerminalView shown;
	private MeStorageFilter filter = MeStorageFilter.DEFAULT;
	private EditBox search;
	private String query = "";
	private boolean dirty, subscribed, opening, awaiting;
	private long due;
	private int left, top, width;
	MeInventoryPane(AbstractContainerMenu menu, Runnable rebuild, Runnable openPlan) {
		this.menu = menu; session = ((MeTerminalHost) menu).meTerminal(); this.rebuild = rebuild; this.openPlan = openPlan;
	}
	static Component text(String key, Object... args) { return Component.translatable("screen.productivebeesgenesis.me_terminal." + key, args); }
	List<AbstractWidget> build(Font font, int left, int top, int width) {
		this.left = left; this.top = top; this.width = width; shown = session.view(); awaiting = session.waiting(); widgets.clear(); buttons.clear();
		boolean focus = search != null && search.isFocused(); int cursor = search == null ? 0 : search.getCursorPosition();
		search = new EditBox(font, left, top, width - 56, 15, text("storage_filter"));
		search.setMaxLength(64); search.setValue(query); search.setHint(text("storage_filter")); search.setFocused(focus);
		search.setCursorPosition(cursor); search.setHighlightPos(cursor);
		search.setResponder(value -> { query = value; dirty = true; due = Util.getMillis() + 300; }); widgets.add(search);
		button("refresh", width - 52, 0, 52, () -> request(STORAGE, -1, 0, 0));
		int third = (width - 4) / 3;
		button("sort." + filter.sort().name().toLowerCase(Locale.ROOT), 0, 18, third, () -> {
			filter = new MeStorageFilter(MeStorageFilter.Sort.values()[(filter.sort().ordinal() + 1) % 3], filter.descending(), filter.content(), filter.type()); request(STORAGE, -1, 0, 0);
		});
		button(filter.descending() ? "descending" : "ascending", third + 2, 18, third, () -> {
			filter = new MeStorageFilter(filter.sort(), !filter.descending(), filter.content(), filter.type()); request(STORAGE, -1, 0, 0);
		});
		button("content." + filter.content().name().toLowerCase(Locale.ROOT), 2 * third + 4, 18, width - 2 * third - 4, () -> {
			filter = new MeStorageFilter(filter.sort(), filter.descending(), MeStorageFilter.Content.values()[(filter.content().ordinal() + 1) % 3], filter.type()); request(STORAGE, -1, 0, 0);
		});
		button("type." + filter.type().name().toLowerCase(Locale.ROOT), 0, 36, width - 106, () -> {
			filter = new MeStorageFilter(filter.sort(), filter.descending(), filter.content(), MeStorageFilter.Type.values()[(filter.type().ordinal() + 1) % 4]); request(STORAGE, -1, 0, 0);
		});
		button("previous", width - 102, 36, 24, () -> request(PAGE, -1, Math.max(0, shown.page() - 1), 0)).active &= shown.page() > 0;
		button("next", width - 76, 36, 24, () -> request(PAGE, -1, shown.page() + 1, 0)).active &= shown.more();
		button("tasks", width - 50, 36, 50, () -> { opening = true; request(TASKS, -1, 0, 0); });
		if (shown.mode() == MeTerminalView.Mode.STORAGE) for (int i = 0; i < shown.rows().size(); i++) {
			var row = shown.rows().get(i); int index = i, x = left + i * 21, y = top + 56;
			var label = (row.icon().isEmpty() ? Component.literal(row.label()) : row.icon().getHoverName().copy()).append("\n" + row.label() + "\n" + row.amount() + (row.kind() == MeTerminalView.Kind.FLUID ? " mB" : ""))
					.append("\n").append(text(row.enabled() ? "stored_craftable_hint" : row.kind() == MeTerminalView.Kind.ITEM ? "stored_hint" : "resource_readonly"));
			var button = new TerminalSkin.Control(x, y, 20, 20, label, mouse -> choose(index, mouse), false, -1, g -> {
				var icon = row.icon(); if (!icon.isEmpty()) g.renderItem(icon, x + 2, y + 2);
				String count = row.amount() == 0 && row.enabled() ? "+" : TerminalProductIcon.compact(Long.toString(row.amount()));
				g.pose().pushPose();
				try { g.pose().translate(x + 19, y + 14, 200); float scale = Math.min(.65f, 18f / Math.max(1, font.width(count))); g.pose().scale(scale, scale, 1); g.drawString(font, count, -font.width(count), 0, 0xfff6edcc, true); }
				finally { g.pose().popPose(); }
				if (row.enabled() && row.amount() > 0) g.drawString(font, "+", x, y - 2, TerminalSkin.GOLD, true);
			});
			button.setTooltip(Tooltip.create(label)); button.active = !session.waiting() && !dirty; widgets.add(button);
		}
		return widgets;
	}
	private TerminalSkin.Control button(String key, int x, int y, int width, Runnable action) {
		var label = text(key); var value = new TerminalSkin.Control(left + x, top + y, width, 15, label, ignored -> action.run(), false, -1, null);
		value.setTooltip(Tooltip.create(label)); value.active = !session.waiting(); widgets.add(value); buttons.add(value); return value;
	}
	private void request(MeTerminalRequest.Action action, int row, int page, long amount) {
		if (action == STORAGE) opening = false;
		var request = session.begin(action, row, page, amount, query, filter);
		if (request != null) { dirty = false; subscribed = action != CLOSE; due = Util.getMillis() + 2000; PacketDistributor.sendToServer(request); rebuild.run(); }
	}
	private void choose(int row, int mouse) {
		if (session.waiting() || dirty || shown != session.view() || shown.mode() != MeTerminalView.Mode.STORAGE) return;
		if (!menu.getCarried().isEmpty()) request(DEPOSIT, -1, shown.page(), mouse == 1 ? 1 : 64);
		else if (Screen.hasControlDown() && shown.rows().get(row).enabled()) { opening = true; request(PLAN, row, 0, 1); }
		else if (shown.rows().get(row).kind() == MeTerminalView.Kind.ITEM) request(Screen.hasShiftDown() ? TAKE_INVENTORY : TAKE, row, shown.page(), mouse == 1 ? 1 : 64);
	}
	void tick(boolean visible) {
		if (!visible) {
			if (subscribed && !session.waiting()) { request(CLOSE, -1, 0, 0); subscribed = false; } return;
		}
		if (opening && !session.waiting() && (session.view().mode() == MeTerminalView.Mode.PLAN || session.view().mode() == MeTerminalView.Mode.TASKS)) {
			opening = false; openPlan.run(); return;
		}
		long now = Util.getMillis();
		if (!opening && !session.waiting() && (!subscribed || now >= due)) request(STORAGE, -1, dirty ? 0 : session.view().page(), 0);
		if (shown != session.view() || awaiting != session.waiting()) rebuild.run();
	}
	void refresh() { request(STORAGE, -1, 0, 0); }
	EditBox focusedSearch() { return search != null && search.isFocused() ? search : null; }
	boolean keyPressed(int key, int scan, int modifiers) {
		if (search == null || !search.isFocused()) return false;
		if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER) { request(STORAGE, -1, 0, 0); return true; }
		return key != org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE && search.keyPressed(key, scan, modifiers);
	}
	boolean click(double x, double y, int mouse) {
		if (mouse == 1 && search != null && search.isMouseOver(x, y)) { search.setValue(""); return true; }
		if (mouse < 0 || mouse > 1 || x < left || x >= left + width || y < top + 56 || y >= top + 76) return false;
		if (!menu.getCarried().isEmpty() && !session.waiting() && !dirty && shown.mode() == MeTerminalView.Mode.STORAGE) request(DEPOSIT, -1, shown.page(), mouse == 1 ? 1 : 64);
		else { int index = (int) (x - left) / 21; if (index < shown.rows().size()) choose(index, mouse); }
		return true;
	}
	void labels(GuiGraphics g, Font font, int originX, int originY) {
		var status = text("status." + (session.waiting() ? "waiting" : session.view().status().name().toLowerCase(Locale.ROOT)));
		g.drawString(font, font.plainSubstrByWidth(status.getString(), width), left - originX, top - originY + 82, TerminalSkin.MUTED, false);
	}
}
