package com.ayoshiko.productivebeesgenesis.apiculture.client;

import com.ayoshiko.productivebeesgenesis.apiculture.me.*;
import java.util.*;
import net.minecraft.Util;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.neoforged.neoforge.network.PacketDistributor;
import static com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalRequest.Action.*;

/** 同一菜单内的有界 ME 网格；客户端只持当前页，行号始终对应服务器快照。 */
final class MeInventoryPane {
	private final AbstractContainerMenu menu;
	private final MeTerminalSession session;
	private final Runnable rebuild, openPlan;
	private final List<AbstractWidget> widgets = new ArrayList<>();
	private final TerminalGridViewport grid = new TerminalGridViewport();
	private final TerminalSearchSync searchSync = new TerminalSearchSync();
	private MeTerminalView shown;
	private MeStorageFilter filter = MeStorageFilter.DEFAULT;
	private EditBox search;
	private String query = "";
	private boolean dirty, subscribed, opening, awaiting, preferencesLoaded, preferencesDirty;
	private long due;
	private int left, top, width;
	MeInventoryPane(AbstractContainerMenu menu, Runnable rebuild, Runnable openPlan) {
		this.menu = menu; session = ((MeTerminalHost) menu).meTerminal(); this.rebuild = rebuild; this.openPlan = openPlan;
	}
	static Component text(String key, Object... args) { return Component.translatable("screen.productivebeesgenesis.me_terminal." + key, args); }
	List<AbstractWidget> build(Font font, int left, int top, int width) { return build(font, left, top, width, 2); }
	List<AbstractWidget> build(Font font, int left, int top, int width, int visibleRows) {
		loadPreferences();
		this.left = left; this.top = top; this.width = width;
		if (shown != null && shown.page() != session.view().page()) grid.offset = 0;
		shown = session.view(); awaiting = session.waiting(); widgets.clear();
		boolean focus = search == null ? preferences().autoFocus.get() : search.isFocused(); int cursor = search == null ? query.length() : search.getCursorPosition();
		search = new EditBox(font, left + 24, top, width - 46, 15, text("storage_filter"));
		search.setMaxLength(64); search.setValue(query); search.setHint(text("storage_filter")); search.setFocused(focus);
		search.setCursorPosition(cursor); search.setHighlightPos(cursor);
		search.setResponder(value -> { query = value; dirty = true; preferencesDirty = true; grid.offset = 0; due = Util.getMillis() + 300; searchSync.edited(value); }); widgets.add(search);
		button("refresh", width - 20, -1, 20, 16, 14, () -> request(STORAGE, -1, 0, 0));
		button("sort." + filter.sort().name().toLowerCase(Locale.ROOT), 0, 20, 20, 20, 9, () -> {
			filter = new MeStorageFilter(MeStorageFilter.Sort.values()[(filter.sort().ordinal() + 1) % 3], filter.descending(), filter.content(), filter.type()); preferencesDirty = true; request(STORAGE, -1, 0, 0);
		});
		button("content." + filter.content().name().toLowerCase(Locale.ROOT), 0, 42, 20, 20, 12, () -> {
			filter = new MeStorageFilter(filter.sort(), filter.descending(), MeStorageFilter.Content.values()[(filter.content().ordinal() + 1) % 3], filter.type()); preferencesDirty = true; request(STORAGE, -1, 0, 0);
		});
		button("type." + filter.type().name().toLowerCase(Locale.ROOT), 0, 64, 20, 20, 11, () -> {
			filter = new MeStorageFilter(filter.sort(), filter.descending(), filter.content(), MeStorageFilter.Type.values()[(filter.type().ordinal() + 1) % 4]); preferencesDirty = true; request(STORAGE, -1, 0, 0);
		});
		button(filter.descending() ? "descending" : "ascending", 0, 86, 20, 20, filter.descending() ? 10 : 16, () -> {
			filter = new MeStorageFilter(filter.sort(), !filter.descending(), filter.content(), filter.type()); preferencesDirty = true; request(STORAGE, -1, 0, 0);
		});
		grid.layout(left + 24, top + 22, width - 24, visibleRows, shown.mode() == MeTerminalView.Mode.STORAGE ? shown.rows().size() : 0);
		int bottom = 24 + grid.rows * 18;
		button("previous", 24, bottom, 22, 14, -1, () -> page(-1)).active &= shown.page() > 0;
		button("next", 48, bottom, 22, 14, -1, () -> page(1)).active &= shown.more();
		button("tasks", width - 20, bottom, 20, 16, 13, () -> { opening = true; request(TASKS, -1, 0, 0); });
		var receipt = shown.receipt();
		if (receipt.retained() > 0 || receipt.uncertain() > 0) {
			var recover = button("recover_fluid", width - 44, bottom, 20, 16, 17, () -> request(RECOVER_FLUID, -1, 0, Screen.hasShiftDown() ? 1 : 0));
			var hint = text("retained_fluid", receipt.fluid(), receipt.retained());
			if (receipt.uncertain() > 0) hint = hint.copy().append("\n").append(text("uncertain_fluid", receipt.uncertain()));
			recover.setTooltip(Tooltip.create(hint.copy().append("\n").append(text("recover_fluid_hint"))));
			recover.active = !session.waiting() && receipt.retained() > 0;
		}
		for (int i = grid.first(); i < grid.end(); i++) {
			var row = shown.rows().get(i); int index = i, x = grid.cellX(i), y = grid.cellY(i);
			var label = (row.icon().isEmpty() ? Component.literal(row.label()) : row.icon().getHoverName().copy()).append("\n" + row.label() + "\n" + row.amount() + (row.kind() == MeTerminalView.Kind.FLUID ? " mB" : ""))
					.append("\n").append(text(row.kind() == MeTerminalView.Kind.FLUID ? "fluid_container_hint" : row.enabled() ? "stored_craftable_hint" : row.kind() == MeTerminalView.Kind.ITEM ? "stored_hint" : "resource_readonly"));
			var button = new TerminalSkin.Control(x, y, 18, 18, label, mouse -> choose(index, mouse), false, -1, g -> {
				var icon = row.icon(); if (!icon.isEmpty()) g.renderItem(icon, x, y);
				String count = row.amount() == 0 && row.enabled() ? "+" : TerminalProductIcon.compact(Long.toString(row.amount()));
				g.pose().pushPose();
				try { g.pose().translate(x + 17, y + 11, 200); float scale = Math.min(.65f, 18f / Math.max(1, font.width(count))); g.pose().scale(scale, scale, 1); g.drawString(font, count, -font.width(count), 0, 0xfff6edcc, true); }
				finally { g.pose().popPose(); }
				if (row.enabled() && row.amount() > 0) g.drawString(font, "+", x, y - 2, 0xffffde75, true);
			}).slot();
			button.setTooltip(Tooltip.create(label)); button.active = !session.waiting() && !dirty; widgets.add(button);
		}
		return widgets;
	}
	private TerminalSkin.Control button(String key, int x, int y, int width, int height, int icon, Runnable action) {
		var label = text(key); var value = new TerminalSkin.Control(left + x, top + y, width, height, label, ignored -> action.run(), false, icon, null);
		value.setTooltip(Tooltip.create(label)); value.active = !session.waiting() && !dirty; widgets.add(value); return value;
	}
	private void request(MeTerminalRequest.Action action, int row, int page, long amount) {
		loadPreferences();
		if (action == STORAGE) opening = false;
		var request = session.begin(action, row, page, amount, query, filter);
		if (request != null) { dirty = false; subscribed = action != CLOSE; due = Util.getMillis() + 2000; PacketDistributor.sendToServer(request); rebuild.run(); }
	}
	private void page(int direction) { if (direction < 0 ? shown.page() > 0 : shown.more()) request(PAGE, -1, Math.max(0, shown.page() + direction), 0); }
	private void choose(int row, int mouse) {
		if (session.waiting() || dirty || shown != session.view() || shown.mode() != MeTerminalView.Mode.STORAGE) return;
		if (!menu.getCarried().isEmpty()) {
			boolean container;
			try { container = menu.getCarried().copyWithCount(1).getCapability(net.neoforged.neoforge.capabilities.Capabilities.FluidHandler.ITEM) != null; }
			catch (RuntimeException failure) { container = false; }
			if (container && mouse == 1) request(EMPTY_CONTAINER, -1, shown.page(), Screen.hasShiftDown() ? 1 : 0);
			else if (container && row >= 0 && row < shown.rows().size() && shown.rows().get(row).kind() == MeTerminalView.Kind.FLUID) request(FILL_CONTAINER, row, shown.page(), Screen.hasShiftDown() ? 1 : 0);
			else request(DEPOSIT, -1, shown.page(), mouse == 1 ? 1 : 64);
		}
		else if (row >= 0 && row < shown.rows().size()) {
			if (Screen.hasControlDown() && shown.rows().get(row).enabled()) { opening = true; request(PLAN, row, 0, 1); }
			else if (shown.rows().get(row).kind() == MeTerminalView.Kind.ITEM) request(Screen.hasShiftDown() ? TAKE_INVENTORY : TAKE, row, shown.page(), mouse == 1 ? 1 : 64);
		}
	}
	void tick(boolean visible) {
		if (!visible) { if (subscribed && !session.waiting()) { request(CLOSE, -1, 0, 0); subscribed = false; } return; }
		searchSync.tick(search, text("storage_filter"));
		if (opening && !session.waiting() && (session.view().mode() == MeTerminalView.Mode.PLAN || session.view().mode() == MeTerminalView.Mode.TASKS)) {
			opening = false; openPlan.run(); return;
		}
		long now = Util.getMillis();
		if (!opening && !session.waiting() && (!subscribed || now >= due)) request(STORAGE, -1, dirty ? 0 : session.view().page(), 0);
		if (shown != session.view() || awaiting != session.waiting()) rebuild.run();
	}
	void refresh() { request(STORAGE, -1, 0, 0); }
	private static com.ayoshiko.productivebeesgenesis.config.TerminalPreferenceConfigSection preferences() { return com.ayoshiko.productivebeesgenesis.config.ModConfig.CLIENT.terminalPreferences; }
	private void loadPreferences() {
		if (preferencesLoaded) return;
		var prefs = preferences(); filter = prefs.meFilter(); query = prefs.rememberSearch.get() ? prefs.meSearch.get() : "";
		preferencesLoaded = true; search = null; subscribed = false; dirty = true; due = 0; grid.offset = 0;
	}
	boolean savePreferences() {
		if (!preferencesLoaded) return false;
		var prefs = preferences(); boolean changed = preferencesDirty || !prefs.rememberSearch.get() && !prefs.meSearch.get().isEmpty();
		if (changed) prefs.storeMe(query, filter);
		preferencesLoaded = false; preferencesDirty = false; search = null; searchSync.reset();
		return changed;
	}
	EditBox focusedSearch() { return search != null && search.isFocused() ? search : null; }
	boolean keyPressed(int key, int scan, int modifiers) {
		if (search != null && search.isFocused()) {
			if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER) { request(STORAGE, -1, 0, 0); search.setFocused(false); return true; }
			return key != org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE && search.keyPressed(key, scan, modifiers);
		}
		if (!session.waiting() && !dirty && (key == org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_UP || key == org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_DOWN)) { page(key == org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_UP ? -1 : 1); return true; }
		return false;
	}
	boolean click(double x, double y, int mouse) {
		if (mouse == 1 && search != null && search.isMouseOver(x, y)) { search.setValue(""); return true; }
		if (grid.press(x, y, mouse)) { rebuild.run(); return true; }
		int index = grid.rowAt(x, y);
		if (mouse < 0 || mouse > 1 || index < 0) return false;
		choose(index, mouse); return true;
	}
	boolean scroll(double x, double y, double delta) {
		if (delta == 0 || !grid.contains(x, y)) return false;
		int direction = delta > 0 ? -1 : 1, next = Math.clamp(grid.offset + direction, 0, grid.max());
		if (next != grid.offset) { grid.offset = next; rebuild.run(); }
		else if (!session.waiting() && !dirty) page(direction);
		return true;
	}
	boolean drag(double y, int button) { if (!grid.drag(y, button)) return false; rebuild.run(); return true; }
	boolean release(int button) { return grid.release(button); }
	void background(GuiGraphics g) { grid.render(g); }
	void labels(GuiGraphics g, Font font, int originX, int originY) {
		var status = text("status." + (session.waiting() ? "waiting" : session.view().status().name().toLowerCase(Locale.ROOT)));
		var receipt = session.view().receipt();
		if (!session.waiting() && receipt.retained() > 0 && receipt.uncertain() == 0) status = text("retained_fluid", receipt.fluid(), receipt.retained());
		g.drawString(font, font.plainSubstrByWidth(status.getString(), width - 124), left - originX + 76, grid.y - originY + grid.rows * 18 + 5, TerminalSkin.MUTED, false);
	}
}
