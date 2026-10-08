package com.ayoshiko.productivebeesgenesis.apiculture.client;

import com.ayoshiko.productivebeesgenesis.apiculture.me.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import static com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalRequest.Action.*;

/** 客户端只接收本页样板预览；编码或倍率改写结果由服务器保管。 */
final class MePatternPane {
	@FunctionalInterface interface Commands { void send(MeTerminalRequest.Action action, int page, long amount); }
	private final AbstractContainerMenu menu;
	private final MeTerminalSession session;
	private final Commands commands;
	private EditBox factor;
	private Button multiply, divide, encode, apply, previous, next;
	private String factorValue = "2";
	private ItemStack cursor = ItemStack.EMPTY;
	private MeTerminalView shown;
	private int left, top, width, height;
	MePatternPane(AbstractContainerMenu menu, MeTerminalSession session, Commands commands) { this.menu = menu; this.session = session; this.commands = commands; }
	private static Component text(String key, Object... args) { return MeInventoryPane.text(key, args); }
	List<AbstractWidget> build(Font font, int left, int top, int width, int height, Runnable back) {
		if (factor != null) factorValue = factor.getValue();
		this.left = left; this.top = top; this.width = width; this.height = height; shown = session.view(); cursor = menu.getCarried().copy();
		var widgets = new ArrayList<AbstractWidget>();
		factor = null; multiply = divide = encode = null;
		if (encoding()) {
			encode = button("pattern_encode_preview", 8, 38, 108, () -> commands.send(PATTERN_ENCODE_CRAFTING, 0, 0)); widgets.add(encode);
			encode.setTooltip(Tooltip.create(text("pattern_encode_hint")));
		} else {
			factor = new EditBox(font, left + 8, top + 38, 96, 14, text("pattern_factor"));
			factor.setMaxLength(19); factor.setFilter(s -> s.isEmpty() || s.matches("[0-9]+")); factor.setValue(factorValue); factor.setHint(text("pattern_factor"));
			factor.setTooltip(Tooltip.create(text("pattern_factor_hint"))); widgets.add(factor);
			multiply = button("pattern_multiply", 108, 38, 88, () -> preview(PATTERN_MULTIPLY)); widgets.add(multiply);
			divide = button("pattern_divide", 200, 38, width - 208, () -> preview(PATTERN_DIVIDE)); widgets.add(divide);
		}
		int bottom = height - 21;
		widgets.add(button("back", 8, bottom, 52, back));
		previous = button("previous", 64, bottom, 24, () -> commands.send(PAGE, Math.max(0, shown.page() - 1), 0)); widgets.add(previous);
		next = button("next", 92, bottom, 24, () -> commands.send(PAGE, shown.page() + 1, 0)); widgets.add(next);
		apply = button(encoding() ? "pattern_encode_apply" : "pattern_apply", width - 100, bottom, 92, () -> commands.send(PATTERN_APPLY, shown.page(), 0));
		apply.setTooltip(Tooltip.create(text(encoding() ? "pattern_encode_apply_hint" : "pattern_apply_hint", cursor.getCount()))); widgets.add(apply);
		if (factor != null) factor.setResponder(ignored -> tick()); tick(); return widgets;
	}
	private Button button(String key, int x, int y, int width, Runnable action) {
		return Button.builder(text(key), ignored -> action.run()).bounds(left + x, top + y, width, 14).build();
	}
	private long value() { try { return factor == null ? 0 : Long.parseLong(factor.getValue()); } catch (NumberFormatException invalid) { return 0; } }
	private boolean encoding() { return shown.mode() == MeTerminalView.Mode.PATTERN_ENCODING; }
	private void preview(MeTerminalRequest.Action action) { long value = value(); if (value > 0) commands.send(action, 0, value); }
	private boolean current() { return ItemStack.matches(cursor, menu.getCarried()); }
	void tick() {
		if (shown == null || apply == null) return;
		boolean idle = !session.waiting(); long value = value();
		if (encoding()) encode.active = idle;
		else { factor.active = idle; multiply.active = divide.active = idle && value > 0; }
		apply.active = idle && current() && shown.confirm() && (encoding() || value > 0 && shown.title().endsWith(" " + value));
		previous.active = idle && shown.page() > 0; next.active = idle && shown.more();
	}
	void render(GuiGraphics g, Font font, int mouseX, int mouseY) {
		if (shown.rows().isEmpty()) g.drawWordWrap(font, text(encoding() ? "pattern_encode_hint" : "pattern_hint"), left + 8, top + 61, width - 16, TerminalSkin.MUTED);
		if (encoding()) g.drawString(font, font.plainSubstrByWidth(shown.title(), width - 132), left + 124, top + 41, TerminalSkin.MUTED, false);
		for (int i = 0; i < shown.rows().size(); i++) {
			var row = shown.rows().get(i); int y = top + 56 + i * 16;
			g.fill(left + 7, y, left + width - 7, y + 16, 0xff25383e);
			var icon = row.icon(); if (!icon.isEmpty()) g.renderItem(icon, left + 9, y);
			String label = text(row.kind() == MeTerminalView.Kind.PATTERN_INPUT ? "pattern_input" : "pattern_output").getString() + " " + row.label();
			g.drawString(font, font.plainSubstrByWidth(label, width - 142), left + 28, y + 4, 0xffe0e5de, false);
			String amount = (encoding() ? "" : TerminalProductIcon.compact(Long.toString(row.amount())) + " → ") + TerminalProductIcon.compact(Long.toString(row.extra()));
			g.drawString(font, font.plainSubstrByWidth(amount, 103), left + width - 111, y + 4, 0xffc8cfba, false);
		}
		var status = session.waiting() ? MeTerminalView.Status.WAITING : !current() && shown.confirm() ? MeTerminalView.Status.STALE : shown.status();
		var message = encoding() && status == MeTerminalView.Status.OK ? text("pattern_encode_ready") : text("status." + status.name().toLowerCase(Locale.ROOT));
		g.drawString(font, font.plainSubstrByWidth(message.getString(), width - 16), left + 8, top + height - 34, TerminalSkin.MUTED, false);
		if (!menu.getCarried().isEmpty()) {
			g.renderItem(menu.getCarried(), left + 120, top + height - 22);
			String held = encoding() ? text("pattern_stack_count", menu.getCarried().getCount()).getString() : menu.getCarried().getCount() + " · " + shown.title();
			g.drawString(font, font.plainSubstrByWidth(held, Math.max(0, width - 242)), left + 138, top + height - 18, TerminalSkin.MUTED, false);
		}
		if (mouseX >= left + 8 && mouseX < left + width - 8 && mouseY >= top + 56 && mouseY < top + 184) {
			int index = (mouseY - top - 56) / 16;
			if (index < shown.rows().size()) {
				var row = shown.rows().get(index);
				var lines = new ArrayList<Component>(); lines.add(Component.literal(row.label()));
				if (!encoding()) lines.add(text("pattern_before", row.amount()));
				lines.add(text(encoding() ? "pattern_encoded_amount" : "pattern_after", row.extra()));
				g.renderComponentTooltip(font, lines, mouseX, mouseY);
			}
		}
		if (encoding() && !shown.title().isEmpty() && mouseX >= left + 124 && mouseX < left + width - 8 && mouseY >= top + 38 && mouseY < top + 52)
			g.renderTooltip(font, Component.literal(shown.title()), mouseX, mouseY);
	}
}
