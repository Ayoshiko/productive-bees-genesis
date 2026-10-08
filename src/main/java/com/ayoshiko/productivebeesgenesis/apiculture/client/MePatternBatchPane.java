package com.ayoshiko.productivebeesgenesis.apiculture.client;

import com.ayoshiko.productivebeesgenesis.apiculture.me.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import static com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalRequest.Action.*;
import static com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalView.*;

/** 背包／私人缓冲批量选择和逐样板差异；客户端不持权威槽或编码结果。 */
final class MePatternBatchPane {
	private static final Status[] STATUSES = Status.values();
	private final AbstractContainerMenu menu;
	private final MeTerminalSession session;
	private final MePatternPane.Commands commands;
	private final List<Button> selections = new ArrayList<>();
	private Button previous, next, apply;
	private ItemStack cursor = ItemStack.EMPTY;
	private MeTerminalView shown;
	private int left, top, width, height;
	MePatternBatchPane(AbstractContainerMenu menu, MeTerminalSession session, MePatternPane.Commands commands) { this.menu = menu; this.session = session; this.commands = commands; }
	private static Component text(String key, Object... args) { return MeInventoryPane.text(key, args); }
	List<AbstractWidget> build(Font font, int left, int top, int width, int height, Runnable back) {
		this.left = left; this.top = top; this.width = width; this.height = height; shown = session.view(); cursor = menu.getCarried().copy(); selections.clear();
		var widgets = new ArrayList<AbstractWidget>();
		if (detail()) selections.add(button("pattern_batch_list", 8, 38, 112, () -> commands.send(PATTERN_BATCH_LIST, -1, shown.page(), 0)));
		else {
			selections.add(button("pattern_batch_select", 8, 38, 100, () -> commands.send(PATTERN_BATCH_SELECT, -1, shown.page(), 1)));
			selections.add(button("pattern_batch_deselect", 112, 38, 88, () -> commands.send(PATTERN_BATCH_SELECT, -1, shown.page(), 0)));
		}
		widgets.addAll(selections); int bottom = height - 21;
		widgets.add(button("back", 8, bottom, 52, back));
		previous = button("previous", 64, bottom, 24, () -> commands.send(PAGE, -1, Math.max(0, shown.page() - 1), 0)); widgets.add(previous);
		next = button("next", 92, bottom, 24, () -> commands.send(PAGE, -1, shown.page() + 1, 0)); widgets.add(next);
		apply = button("pattern_batch_apply", width - 100, bottom, 92, () -> commands.send(PATTERN_APPLY, -1, shown.page(), 0));
		apply.setTooltip(Tooltip.create(text(shown.mode().bufferBatch() ? "pattern_buffer_batch_apply_hint" : "pattern_batch_apply_hint", shown.bytes()))); widgets.add(apply); tick(); return widgets;
	}
	private Button button(String key, int x, int y, int width, Runnable action) {
		return Button.builder(text(key), ignored -> action.run()).bounds(left + x, top + y, width, 14).build();
	}
	private boolean detail() { return shown.mode().batchDetail(); }
	private boolean current() { return ItemStack.matches(cursor, menu.getCarried()); }
	private static Status status(Row row) { return row.extra() < STATUSES.length ? STATUSES[(int) row.extra()] : Status.INVALID; }
	void tick() {
		if (shown == null || apply == null) return;
		boolean ready = !session.waiting() && current() && shown.revision() != 0;
		for (var button : selections) button.active = ready && shown.status() != Status.WAITING;
		apply.active = ready && shown.confirm() && !detail();
		previous.active = ready && shown.page() > 0; next.active = ready && shown.more();
	}
	boolean click(double x, double y, int button) {
		if (detail() || button != 0 && button != 1 || session.waiting() || !current() || shown.status() == Status.WAITING
				|| x < left + 8 || x >= left + width - 8 || y < top + 56 || y >= top + 184) return false;
		int row = (int) (y - top - 56) / 16;
		if (row >= shown.rows().size() || status(shown.rows().get(row)) != Status.OK) return false;
		commands.send(button == 0 ? PATTERN_BATCH_TOGGLE : PATTERN_BATCH_DETAILS, row, shown.page(), 0); return true;
	}
	void render(GuiGraphics g, Font font, int mouseX, int mouseY) {
		g.drawString(font, font.plainSubstrByWidth(text(shown.mode().bufferBatch() ? "pattern_buffer_batch_selected" : "pattern_batch_selected", shown.bytes()).getString(), width - 212), left + 204, top + 41, TerminalSkin.MUTED, false);
		if (shown.rows().isEmpty()) g.drawWordWrap(font, text(shown.mode().bufferBatch() ? "pattern_buffer_batch_hint" : "pattern_batch_hint"), left + 8, top + 61, width - 16, TerminalSkin.MUTED);
		for (int i = 0; i < shown.rows().size(); i++) {
			var row = shown.rows().get(i); int y = top + 56 + i * 16;
			g.fill(left + 7, y, left + width - 7, y + 16, row.enabled() ? detail() ? 0xff465439 : 0xff526a6e : 0xff25383e);
			var icon = row.icon(); if (!icon.isEmpty()) g.renderItem(icon, left + 9, y);
			String label = detail() ? text(row.kind() == Kind.PATTERN_INPUT ? "pattern_input" : "pattern_output").getString() + " " + row.label() : (row.enabled() ? "☑ " : "☐ ") + row.label();
			g.drawString(font, font.plainSubstrByWidth(label, width - 142), left + 28, y + 4, !detail() && status(row) != Status.OK ? TerminalSkin.MUTED : 0xffe0e5de, false);
			String count = detail() ? TerminalProductIcon.compact(Long.toString(row.amount())) + " → " + TerminalProductIcon.compact(Long.toString(row.extra())) : "× " + row.amount();
			g.drawString(font, font.plainSubstrByWidth(count, 103), left + width - 111, y + 4, 0xffc8cfba, false);
		}
		g.drawString(font, font.plainSubstrByWidth(shown.title(), width - 16), left + 8, top + 188, TerminalSkin.MUTED, false);
		var status = session.waiting() ? Status.WAITING : !current() ? Status.STALE : shown.status();
		var message = status == Status.OK ? text(detail() ? "pattern_batch_detail_hint" : "pattern_batch_ready") : text("status." + status.name().toLowerCase(Locale.ROOT));
		g.drawString(font, font.plainSubstrByWidth(message.getString(), width - 16), left + 8, top + height - 34, TerminalSkin.MUTED, false);
		if (mouseX >= left + 8 && mouseX < left + width - 8 && mouseY >= top + 56 && mouseY < top + 184) {
			int index = (mouseY - top - 56) / 16;
			if (index < shown.rows().size()) {
				var row = shown.rows().get(index); var lines = new ArrayList<Component>(); lines.add(Component.literal(row.label()));
				if (detail()) { lines.add(text("pattern_before", row.amount())); lines.add(text("pattern_after", row.extra())); }
				else { lines.add(text("pattern_stack_count", row.amount())); lines.add(status(row) == Status.OK ? text("pattern_batch_ready") : text("status." + status(row).name().toLowerCase(Locale.ROOT))); }
				g.renderComponentTooltip(font, lines, mouseX, mouseY);
			}
		}
		if (mouseX >= left + 8 && mouseX < left + width - 8 && mouseY >= top + 186 && mouseY < top + 199)
			g.renderTooltip(font, Component.literal(shown.title()), mouseX, mouseY);
	}
}
