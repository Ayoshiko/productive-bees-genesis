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

/** 客户端只接收本页倍率预览；待应用的改写结果由服务器保管。 */
final class MePatternPane {
	@FunctionalInterface interface Commands { void send(MeTerminalRequest.Action action, int page, long amount); }
	private final AbstractContainerMenu menu;
	private final MeTerminalSession session;
	private final Commands commands;
	private EditBox factor;
	private Button multiply, divide, apply, previous, next;
	private ItemStack cursor = ItemStack.EMPTY;
	private MeTerminalView shown;
	private int left, top, width, height;
	MePatternPane(AbstractContainerMenu menu, MeTerminalSession session, Commands commands) { this.menu = menu; this.session = session; this.commands = commands; }
	private static Component text(String key, Object... args) { return MeInventoryPane.text(key, args); }
	List<AbstractWidget> build(Font font, int left, int top, int width, int height, Runnable back) {
		String value = factor == null ? "2" : factor.getValue();
		this.left = left; this.top = top; this.width = width; this.height = height; shown = session.view(); cursor = menu.getCarried().copy();
		var widgets = new ArrayList<AbstractWidget>();
		factor = new EditBox(font, left + 8, top + 38, 96, 14, text("pattern_factor"));
		factor.setMaxLength(19); factor.setFilter(s -> s.isEmpty() || s.matches("[0-9]+")); factor.setValue(value); factor.setHint(text("pattern_factor"));
		factor.setTooltip(Tooltip.create(text("pattern_factor_hint"))); widgets.add(factor);
		multiply = button("pattern_multiply", 108, 38, 88, () -> preview(PATTERN_MULTIPLY)); widgets.add(multiply);
		divide = button("pattern_divide", 200, 38, width - 208, () -> preview(PATTERN_DIVIDE)); widgets.add(divide);
		int bottom = height - 21;
		widgets.add(button("back", 8, bottom, 52, back));
		previous = button("previous", 64, bottom, 24, () -> commands.send(PAGE, Math.max(0, shown.page() - 1), 0)); widgets.add(previous);
		next = button("next", 92, bottom, 24, () -> commands.send(PAGE, shown.page() + 1, 0)); widgets.add(next);
		apply = button("pattern_apply", width - 100, bottom, 92, () -> commands.send(PATTERN_APPLY, shown.page(), 0));
		apply.setTooltip(Tooltip.create(text("pattern_apply_hint", cursor.getCount()))); widgets.add(apply);
		factor.setResponder(ignored -> tick()); tick(); return widgets;
	}
	private Button button(String key, int x, int y, int width, Runnable action) {
		return Button.builder(text(key), ignored -> action.run()).bounds(left + x, top + y, width, 14).build();
	}
	private long value() { try { return Long.parseLong(factor.getValue()); } catch (NumberFormatException invalid) { return 0; } }
	private void preview(MeTerminalRequest.Action action) { long value = value(); if (value > 0) commands.send(action, 0, value); }
	private boolean current() { return ItemStack.matches(cursor, menu.getCarried()); }
	void tick() {
		if (factor == null) return;
		boolean idle = !session.waiting(); long value = value();
		factor.active = idle; multiply.active = divide.active = idle && value > 0;
		apply.active = idle && current() && shown.confirm() && value > 0 && shown.title().endsWith(" " + value);
		previous.active = idle && shown.page() > 0; next.active = idle && shown.more();
	}
	void render(GuiGraphics g, Font font, int mouseX, int mouseY) {
		if (shown.rows().isEmpty()) g.drawWordWrap(font, text("pattern_hint"), left + 8, top + 61, width - 16, TerminalSkin.MUTED);
		for (int i = 0; i < shown.rows().size(); i++) {
			var row = shown.rows().get(i); int y = top + 56 + i * 16;
			g.fill(left + 7, y, left + width - 7, y + 16, 0xff25383e);
			var icon = row.icon(); if (!icon.isEmpty()) g.renderItem(icon, left + 9, y);
			String label = text(row.kind() == MeTerminalView.Kind.PATTERN_INPUT ? "pattern_input" : "pattern_output").getString() + " " + row.label();
			g.drawString(font, font.plainSubstrByWidth(label, width - 142), left + 28, y + 4, 0xffe0e5de, false);
			String amount = TerminalProductIcon.compact(Long.toString(row.amount())) + " → " + TerminalProductIcon.compact(Long.toString(row.extra()));
			g.drawString(font, font.plainSubstrByWidth(amount, 103), left + width - 111, y + 4, 0xffc8cfba, false);
		}
		var status = session.waiting() ? MeTerminalView.Status.WAITING : !current() && shown.confirm() ? MeTerminalView.Status.STALE : shown.status();
		g.drawString(font, font.plainSubstrByWidth(text("status." + status.name().toLowerCase(Locale.ROOT)).getString(), width - 16), left + 8, top + height - 34, TerminalSkin.MUTED, false);
		if (!menu.getCarried().isEmpty()) {
			g.renderItem(menu.getCarried(), left + 120, top + height - 22);
			g.drawString(font, font.plainSubstrByWidth(menu.getCarried().getCount() + " · " + shown.title(), Math.max(0, width - 242)), left + 138, top + height - 18, TerminalSkin.MUTED, false);
		}
		if (mouseX >= left + 8 && mouseX < left + width - 8 && mouseY >= top + 56 && mouseY < top + 184) {
			int index = (mouseY - top - 56) / 16;
			if (index < shown.rows().size()) {
				var row = shown.rows().get(index);
				g.renderComponentTooltip(font, List.of(Component.literal(row.label()), text("pattern_before", row.amount()), text("pattern_after", row.extra())), mouseX, mouseY);
			}
		}
	}
}
