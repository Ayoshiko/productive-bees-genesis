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
			filter = new MeStorageFilter(filter.sort(), filter.descending(), filter.content(), MeStorageFilter.Type.values()[(filter.type().ordinal() + 1) % MeStorageFilter.Type.values().length]); preferencesDirty = true; request(STORAGE, -1, 0, 0);
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
		if (receipt.retainedEnergy() > 0 || receipt.uncertainEnergy() > 0) {
			var recover = button("recover_energy", width - 68, bottom, 20, 16, 18, () -> request(RECOVER_ENERGY, -1, 0, Screen.hasShiftDown() ? 1 : 0));
			var hint = text("retained_energy", receipt.retainedEnergy());
			if (receipt.uncertainEnergy() > 0) hint = hint.copy().append("\n").append(text("uncertain_energy", receipt.uncertainEnergy()));
			recover.setTooltip(Tooltip.create(hint.copy().append("\n").append(text("recover_energy_hint"))));
			recover.active = !session.waiting() && receipt.retainedEnergy() > 0;
		}
		if (receipt.retainedChemical() > 0 || receipt.uncertainChemical() > 0) {
			var recover = button("recover_chemical", width - 92, bottom, 20, 16, 19, () -> request(RECOVER_CHEMICAL, -1, 0, Screen.hasShiftDown() ? 1 : 0));
			var hint = text("retained_chemical", receipt.chemical(), receipt.retainedChemical());
			if (receipt.uncertainChemical() > 0) hint = hint.copy().append("\n").append(text("uncertain_chemical", receipt.uncertainChemical()));
			recover.setTooltip(Tooltip.create(hint.copy().append("\n").append(text("recover_chemical_hint"))));
			recover.active = !session.waiting() && receipt.retainedChemical() > 0;
		}
		for (int i = grid.first(); i < grid.end(); i++) {
			var row = shown.rows().get(i); int index = i, x = grid.cellX(i), y = grid.cellY(i);
			var label = (row.kind() == MeTerminalView.Kind.CHEMICAL ? chemicalName(row.label()).copy() : row.kind() == MeTerminalView.Kind.ENERGY ? text("type.energy").copy() : row.icon().isEmpty() ? Component.literal(row.label()) : row.icon().getHoverName().copy())
					.append("\n" + row.label() + "\n" + row.amount() + (row.kind() == MeTerminalView.Kind.FLUID || row.kind() == MeTerminalView.Kind.CHEMICAL ? " mB" : row.kind() == MeTerminalView.Kind.ENERGY ? " FE" : ""))
					.append("\n").append(text(row.kind() == MeTerminalView.Kind.CHEMICAL ? "chemical_container_hint" : row.kind() == MeTerminalView.Kind.ENERGY ? "energy_container_hint" : row.kind() == MeTerminalView.Kind.FLUID ? "fluid_container_hint" : row.enabled() ? "stored_craftable_hint" : row.kind() == MeTerminalView.Kind.ITEM ? "stored_hint" : "resource_readonly"));
			if (row.pinned()) label.append("\n").append(text("completed_pin"));
			var button = new TerminalSkin.Control(x, y, 18, 18, label, mouse -> choose(index, mouse), false, -1, g -> {
				var icon = row.icon(); if (!icon.isEmpty()) g.renderItem(icon, x, y);
				else if (row.kind() == MeTerminalView.Kind.ENERGY) TerminalSkin.glyph(g, 18, x, y);
				else if (row.kind() == MeTerminalView.Kind.CHEMICAL) TerminalSkin.glyph(g, 19, x, y);
				String count = row.amount() == 0 && row.enabled() ? "+" : TerminalProductIcon.compact(Long.toString(row.amount()));
				g.pose().pushPose();
				try { g.pose().translate(x + 17, y + 11, 200); float scale = Math.min(.65f, 18f / Math.max(1, font.width(count))); g.pose().scale(scale, scale, 1); g.drawString(font, count, -font.width(count), 0, 0xfff6edcc, true); }
				finally { g.pose().popPose(); }
				if (row.enabled() && row.amount() > 0) g.drawString(font, "+", x, y - 2, 0xffffde75, true);
				if (row.pinned()) {
					g.fill(x + 12, y + 1, x + 16, y + 3, 0xffffde75);
					g.fill(x + 13, y + 3, x + 15, y + 5, 0xffffde75);
					g.fill(x + 12, y + 5, x + 16, y + 6, 0xffffde75);
					g.fill(x + 14, y + 6, x + 15, y + 8, 0xffffde75);
				}
			}).slot();
			button.setTooltip(Tooltip.create(label)); button.active = !session.waiting() && !dirty; widgets.add(button);
		}
		return widgets;
	}
	private static Component chemicalName(String id) {
		var key = net.minecraft.resources.ResourceLocation.tryParse(id);
		return key != null && mekanism.api.MekanismAPI.CHEMICAL_REGISTRY.containsKey(key)
				? mekanism.api.MekanismAPI.CHEMICAL_REGISTRY.get(key).getTextComponent() : Component.literal(id);
	}
	private TerminalSkin.Control button(String key, int x, int y, int width, int height, int icon, Runnable action) {
		var label = text(key); var value = new TerminalSkin.Control(left + x, top + y, width, height, label, ignored -> action.run(), false, icon, null);
		value.setTooltip(Tooltip.create(label)); value.active = !session.waiting() && !dirty; widgets.add(value); return value;
	}
	private void request(MeTerminalRequest.Action action, int row, int page, long amount) {
		loadPreferences();
		if (action == STORAGE) opening = false;
		var request = session.begin(action, row, page, amount, query, filter, preferences().pinCraftingFinished.get());
		if (request != null) { dirty = false; subscribed = action != CLOSE; due = Util.getMillis() + 2000; PacketDistributor.sendToServer(request); rebuild.run(); }
	}
	private void page(int direction) { if (direction < 0 ? shown.page() > 0 : shown.more()) request(PAGE, -1, Math.max(0, shown.page() + direction), 0); }
	private void choose(int row, int mouse) {
		if (session.waiting() || dirty || shown != session.view() || shown.mode() != MeTerminalView.Mode.STORAGE) return;
		if (!menu.getCarried().isEmpty()) {
			boolean container = false, energy = false, chemical = false;
			try { container = menu.getCarried().copyWithCount(1).getCapability(net.neoforged.neoforge.capabilities.Capabilities.FluidHandler.ITEM) != null; }
			catch (RuntimeException | LinkageError failure) { }
			try { energy = com.ayoshiko.productivebeesgenesis.mek.ae2.AppliedFluxIntegrationLoader.isAppliedFluxLoaded()
					&& menu.getCarried().copyWithCount(1).getCapability(net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.ITEM) != null; }
			catch (RuntimeException | LinkageError failure) { }
			try { chemical = net.neoforged.fml.ModList.get().isLoaded("appmek")
					&& menu.getCarried().copyWithCount(1).getCapability(mekanism.common.capabilities.Capabilities.CHEMICAL.item()) != null; }
			catch (RuntimeException | LinkageError failure) { }
			var kind = row >= 0 && row < shown.rows().size() ? shown.rows().get(row).kind() : null;
			long target = Screen.hasShiftDown() ? 1 : 0;
			if (kind == MeTerminalView.Kind.CHEMICAL && chemical) request(mouse == 1 ? EMPTY_CHEMICAL : FILL_CHEMICAL, mouse == 1 ? -1 : row, shown.page(), target);
			else if (kind == MeTerminalView.Kind.ENERGY && energy) request(mouse == 1 ? DISCHARGE_ITEM : CHARGE_ITEM, mouse == 1 ? -1 : row, shown.page(), target);
			else if (container && mouse == 1) request(EMPTY_CONTAINER, -1, shown.page(), target);
			else if (container && kind == MeTerminalView.Kind.FLUID) request(FILL_CONTAINER, row, shown.page(), target);
			else if (chemical && mouse == 1) request(EMPTY_CHEMICAL, -1, shown.page(), target);
			else if (energy && mouse == 1) request(DISCHARGE_ITEM, -1, shown.page(), target);
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
		boolean energy = receipt.retainedEnergy() > 0 || receipt.uncertainEnergy() > 0;
		boolean chemical = receipt.retainedChemical() > 0 || receipt.uncertainChemical() > 0;
		if (!session.waiting() && receipt.uncertain() == 0 && receipt.uncertainEnergy() == 0 && receipt.uncertainChemical() == 0 && session.view().status() != MeTerminalView.Status.TRANSFER_UNKNOWN) {
			if (receipt.retained() > 0) status = text("retained_fluid", receipt.fluid(), receipt.retained());
			else if (receipt.retainedEnergy() > 0) status = text("retained_energy", receipt.retainedEnergy());
			else if (receipt.retainedChemical() > 0) status = text("retained_chemical", receipt.chemical(), receipt.retainedChemical());
		}
		g.drawString(font, font.plainSubstrByWidth(status.getString(), Math.max(0, width - (chemical ? 172 : energy ? 148 : 124))), left - originX + 76, grid.y - originY + grid.rows * 18 + 5, TerminalSkin.MUTED, false);
	}
}
