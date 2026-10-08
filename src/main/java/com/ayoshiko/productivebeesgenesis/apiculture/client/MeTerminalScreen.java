package com.ayoshiko.productivebeesgenesis.apiculture.client;

import com.ayoshiko.productivebeesgenesis.apiculture.me.*;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.neoforged.neoforge.network.PacketDistributor;
import static com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalRequest.Action.*;
import static com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalView.*;

/** 保留原服务端菜单的工作页，退出只取消尚未提交的规划。 */
public final class MeTerminalScreen extends Screen {
	private final Screen parent;
	private final AbstractContainerMenu menu;
	private final MeTerminalSession session;
	private final MeInventoryPane storage;
	private final MePatternPane patterns;
	private final List<Button> actions = new ArrayList<>();
	private MeTerminalView shown;
	private EditBox filter, quantity;
	private String query = "", count = "1";
	private int left, top, panelWidth, panelHeight, selected = -1;
	private boolean opened;
	private long nextPoll;
	public MeTerminalScreen(Screen parent, AbstractContainerMenu menu) {
		this(parent, menu, false);
	}
	public MeTerminalScreen(Screen parent, AbstractContainerMenu menu, boolean resume) {
		super(text("title")); opened = resume; this.parent = parent; this.menu = menu; session = ((MeTerminalHost) menu).meTerminal(); storage = new MeInventoryPane(menu, this::build, this::build);
		patterns = new MePatternPane(menu, session, (action, page, amount) -> request(action, -1, page, amount));
	}
	private static Component text(String key, Object... args) { return Component.translatable("screen.productivebeesgenesis.me_terminal." + key, args); }
	private Button button(String key, int x, int y, int width, Runnable action) {
		var b = addRenderableWidget(Button.builder(text(key), ignored -> action.run()).bounds(left+x, top+y, width, 14).build()); actions.add(b); return b;
	}
	@Override protected void init() {
		panelWidth = Math.min(312, width-8); panelHeight = Math.min(236, height-8); left = (width-panelWidth)/2; top = (height-panelHeight)/2;
		build(); if (!opened) { opened = true; request(BROWSE, -1, 0); }
	}
	private void build() {
		if (filter != null) query = filter.getValue(); if (quantity != null) count = quantity.getValue();
		clearWidgets(); actions.clear(); shown = session.view(); selected = -1;
		button("storage", 8, 20, 52, storage::refresh);
		button("catalogue", 64, 20, 60, () -> request(BROWSE, -1, 0));
		button("tasks", 128, 20, 44, () -> request(TASKS, -1, 0));
		button("patterns", 176, 20, 56, () -> request(PATTERN_READ, -1, 0));
		button("refresh", 236, 20, panelWidth - 244, () -> { if (shown.mode() == Mode.STORAGE) storage.refresh(); else request(shown.mode() == Mode.PATTERN ? PATTERN_READ : shown.mode() == Mode.TASKS ? TASKS : shown.mode() == Mode.PLAN ? POLL : BROWSE, -1, shown.page()); });
		if (shown.mode() == Mode.PATTERN) {
			filter = null; quantity = null;
			for (var widget : patterns.build(font, left, top, panelWidth, panelHeight, this::onClose)) addRenderableWidget(widget);
			if (session.waiting()) for (var action : actions) action.active = false;
			return;
		}
		if (shown.mode() == Mode.STORAGE) {
			if (filter != null) filter.setFocused(false);
			for (var widget : storage.build(font, left + 8, top + 40, panelWidth - 16, 4)) addRenderableWidget(widget);
			if (storage.focusedSearch() != null) setFocused(storage.focusedSearch());
			addRenderableWidget(Button.builder(text("back"), ignored -> onClose()).bounds(left + 8, top + panelHeight - 21, 52, 14).build());
			return;
		}
		filter = addRenderableWidget(new EditBox(font, left+8, top+38, panelWidth-114, 14, text("filter"))); filter.setMaxLength(64); filter.setValue(query); filter.setHint(text("filter")); filter.visible = shown.mode() == Mode.CATALOGUE;
		quantity = addRenderableWidget(new EditBox(font, left+panelWidth-100, top+38, 92, 14, text("quantity"))); quantity.setMaxLength(19); quantity.setFilter(value -> value.isEmpty() || value.matches("[0-9]+")); quantity.setValue(count); quantity.setHint(text("quantity")); quantity.visible = shown.mode() == Mode.CATALOGUE;
		int bottom = panelHeight-21;
		var back = addRenderableWidget(Button.builder(text("back"), ignored -> onClose()).bounds(left+8, top+bottom, 52, 14).build());
		button("previous", 64, bottom, 24, () -> request(PAGE, -1, Math.max(0, shown.page()-1))).active = shown.page() > 0;
		button("next", 92, bottom, 24, () -> request(PAGE, -1, shown.page()+1)).active = shown.more();
		if (shown.mode() == Mode.CATALOGUE) button("plan", 206, bottom, panelWidth-214, () -> { if (selected >= 0) request(PLAN, selected, 0); });
		if (shown.mode() == Mode.PLAN) {
			button("cpu", 120, bottom, 78, () -> request(CPU_NEXT, -1, shown.page())).active = shown.status() != Status.WAITING;
			button("confirm", 206, bottom, panelWidth-214, () -> request(CONFIRM, -1, shown.page())).active = shown.confirm();
		}
		if (shown.mode() == Mode.TASKS) button("cancel", 206, bottom, panelWidth-214, () -> { if (selected >= 0 && shown.rows().get(selected).enabled()) request(CANCEL, selected, shown.page()); });
		if (session.waiting()) for (var action : actions) action.active = false;
	}
	private void request(MeTerminalRequest.Action action, int row, int page) {
		long amount;
		try { amount = Long.parseLong(quantity == null ? count : quantity.getValue()); if (action == PLAN && amount <= 0) return; }
		catch (NumberFormatException invalid) { if (action == PLAN) return; amount = 0; }
		request(action, row, page, amount);
	}
	private void request(MeTerminalRequest.Action action, int row, int page, long amount) {
		var request = session.begin(action, row, page, amount, filter == null ? query : filter.getValue());
		if (request != null) { PacketDistributor.sendToServer(request); nextPoll = Util.getMillis()+1000; for (var button : actions) button.active = false; }
	}
	@Override public void tick() {
		if (minecraft.player == null || minecraft.player.containerMenu != menu) { minecraft.setScreen(null); return; }
		if (session.view().mode() == Mode.STORAGE) storage.tick(true);
		if (session.view().mode() == Mode.PATTERN) patterns.tick();
		if (shown != session.view() || !session.waiting() && actions.stream().noneMatch(button -> button.active)) build();
		if (!session.waiting() && Util.getMillis() >= nextPoll && (shown.mode() == Mode.PLAN && shown.status() == Status.WAITING || shown.mode() == Mode.TASKS)) {
			nextPoll = Util.getMillis()+2000; request(shown.mode() == Mode.TASKS ? TASKS : POLL, -1, shown.page());
		}
	}
	@Override public boolean mouseClicked(double x, double y, int button) {
		if (shown.mode() == Mode.STORAGE) return storage.click(x, y, button) || super.mouseClicked(x, y, button);
		if (shown.mode() == Mode.PATTERN) return super.mouseClicked(x, y, button);
		if (button == 0 && x >= left+8 && x < left+panelWidth-8 && y >= top+56 && y < top+184 && !session.waiting()) {
			int row = (int)(y-top-56)/16; if (row < shown.rows().size()) { selected = row; return true; }
		}
		return super.mouseClicked(x, y, button);
	}
	@Override public boolean keyPressed(int key, int scan, int modifiers) {
		if (shown.mode() == Mode.STORAGE) return storage.keyPressed(key, scan, modifiers) || super.keyPressed(key, scan, modifiers);
		if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER && filter != null && filter.isFocused()) { request(BROWSE, -1, 0); return true; }
		return super.keyPressed(key, scan, modifiers);
	}
	@Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
		return shown.mode() == Mode.STORAGE && storage.scroll(x, y, vertical) || super.mouseScrolled(x, y, horizontal, vertical);
	}
	@Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
		return shown.mode() == Mode.STORAGE && storage.drag(y, button) || super.mouseDragged(x, y, button, dx, dy);
	}
	@Override public boolean mouseReleased(double x, double y, int button) {
		return shown.mode() == Mode.STORAGE && storage.release(button) || super.mouseReleased(x, y, button);
	}
	@Override public void onClose() { request(CLOSE, -1, 0); minecraft.setScreen(parent); }
	@Override public void removed() {
		try {
			boolean changed = storage.savePreferences(); changed |= com.ayoshiko.productivebeesgenesis.config.ModConfig.CLIENT.terminalPreferences.forgetDisabledSearches();
			if (changed) com.ayoshiko.productivebeesgenesis.config.ModConfig.CLIENT_SPEC.save();
		}
		catch (RuntimeException failure) { com.mojang.logging.LogUtils.getLogger().warn("Could not save ME display preferences", failure); }
		super.removed();
	}
	@Override public boolean isPauseScreen() { return false; }
	@Override public void render(GuiGraphics g, int x, int y, float partial) {
		renderBackground(g, x, y, partial);
		TerminalSkin.panel(g, left, top, panelWidth, panelHeight);
		g.drawString(font, title, left+8, top+7, TerminalSkin.INK, false);
		g.drawString(font, text("page", shown.page()+1), left+panelWidth-70, top+7, TerminalSkin.MUTED, false);
		if (shown.mode() == Mode.PATTERN) {
			patterns.render(g, font, x, y);
			for (var widget : renderables) widget.render(g, x, y, partial); return;
		}
		if (shown.mode() == Mode.STORAGE) {
			storage.background(g);
			storage.labels(g, font, 0, 0);
			g.drawWordWrap(font, text("stored_hint"), left + 32, top + 156, panelWidth - 40, TerminalSkin.MUTED);
			var held = menu.getCarried(); if (!held.isEmpty()) { g.renderItem(held, left + 10, top + 183); g.renderItemDecorations(font, held, left + 10, top + 183); }
			for (var widget : renderables) widget.render(g, x, y, partial); return;
		}
		if (shown.mode() == Mode.PLAN) g.drawString(font, font.plainSubstrByWidth(text("plan_info", shown.bytes(), shown.cpu().isEmpty() ? text("automatic").getString() : shown.cpu()).getString(), panelWidth-16), left+8, top+40, TerminalSkin.MUTED, false);
		for (int i=0;i<shown.rows().size();i++) {
			var row = shown.rows().get(i); int rowY = top+56+i*16;
			g.fill(left+7, rowY, left+panelWidth-7, rowY+16, selected == i ? 0xff526a6e : 0xff25383e);
			var icon = row.icon(); if (!icon.isEmpty()) g.renderItem(icon, left+9, rowY);
			String label = row.kind() == Kind.TASK ? row.label() : (icon.isEmpty() ? row.label() : icon.getHoverName().getString());
			if (row.kind() == Kind.USED || row.kind() == Kind.MISSING || row.kind() == Kind.EMITTED) label = text("kind."+row.kind().name().toLowerCase(java.util.Locale.ROOT)).getString()+" "+label;
			g.drawString(font, font.plainSubstrByWidth(label, panelWidth-120), left+28, rowY+4, 0xffe0e5de, false);
			String amount = row.kind() == Kind.TASK ? row.amount()+"/"+row.extra() : row.amount() == 0 ? "" : Long.toString(row.amount());
			g.drawString(font, font.plainSubstrByWidth(amount, 83), left+panelWidth-91, rowY+4, 0xffc8cfba, false);
		}
		g.drawString(font, font.plainSubstrByWidth(text("status."+(session.waiting() ? "waiting" : shown.status().name().toLowerCase(java.util.Locale.ROOT))).getString(), panelWidth-16), left+8, top+panelHeight-34, TerminalSkin.MUTED, false);
		// Screen.render 会再次绘制模糊背景，组件在面板之后单独绘制。
		for (var widget : renderables) widget.render(g, x, y, partial);
		if (x >= left+8 && x < left+panelWidth-8 && y >= top+56 && y < top+184) {
			int index=(y-top-56)/16; if (index < shown.rows().size()) {
				var row=shown.rows().get(index); var lines=new ArrayList<Component>(); var icon=row.icon();
				if (!icon.isEmpty()) lines.add(icon.getHoverName()); lines.add(Component.literal(row.label()));
				if (row.amount()>0 || row.extra()>0) lines.add(Component.literal(row.amount()+(row.kind()==Kind.TASK ? "/"+row.extra() : row.kind()==Kind.FLUID ? " mB" : "")));
				if (row.kind()==Kind.TASK) lines.add(text(row.enabled() ? "cancel_hint" : "readonly")); g.renderComponentTooltip(font, lines, x, y);
			}
		}
		if (shown.mode()==Mode.PLAN && y>=top+36 && y<top+53) g.renderTooltip(font, Component.literal(shown.title()), x, y);
	}
}
