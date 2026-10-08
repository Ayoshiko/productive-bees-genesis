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

/** 客户端只选择真实样本槽和数量；完整资源键及编码草稿留在服务器。 */
final class MeProcessingPane {
	@FunctionalInterface interface Commands { void send(MeTerminalRequest.Action action, int row, int page, long amount, boolean contents); }
	private final AbstractContainerMenu menu;
	private final MeTerminalSession session;
	private final Commands commands;
	private final List<Button> edits = new ArrayList<>();
	private EditBox amount;
	private Button sample, type, addInput, addOutput, setAmount, remove, clear, apply, previous, next;
	private String amountValue = "1";
	private int slot, selected = -1, left, top, width, height;
	private boolean contents;
	private ItemStack cursor = ItemStack.EMPTY;
	private MeTerminalView shown;
	MeProcessingPane(AbstractContainerMenu menu, MeTerminalSession session, Commands commands) { this.menu = menu; this.session = session; this.commands = commands; }
	private static Component text(String key, Object... args) { return MeInventoryPane.text(key, args); }
	List<AbstractWidget> build(Font font, int left, int top, int width, int height, Runnable back) {
		if (amount != null) amountValue = amount.getValue();
		this.left = left; this.top = top; this.width = width; this.height = height;
		shown = session.view(); cursor = menu.getCarried().copy(); selected = -1; edits.clear();
		var widgets = new ArrayList<AbstractWidget>();
		sample = button(text("pattern_sample_slot", slot + 1), 8, 38, 62, () -> { slot = (slot + 1) % 9; sample.setMessage(text("pattern_sample_slot", slot + 1)); });
		sample.setTooltip(Tooltip.create(text("pattern_sample_slot_hint"))); widgets.add(sample);
		type = button(text(contents ? "pattern_sample_contents" : "pattern_sample_item"), 74, 38, 72, () -> { contents = !contents; type.setMessage(text(contents ? "pattern_sample_contents" : "pattern_sample_item")); });
		type.setTooltip(Tooltip.create(text("pattern_sample_type_hint"))); widgets.add(type);
		amount = new EditBox(font, left + 150, top + 38, width - 218, 14, text("quantity"));
		amount.setMaxLength(19); amount.setFilter(s -> s.isEmpty() || s.matches("[0-9]+")); amount.setValue(amountValue);
		amount.setTooltip(Tooltip.create(text("pattern_processing_amount_hint"))); widgets.add(amount);
		widgets.add(button(text("refresh"), width - 64, 38, 56, () -> send(PATTERN_ENCODE_PROCESSING, -1, 0, 0)));
		int cell = (width - 32) / 5;
		addInput = button(text("pattern_add_input"), 8, 56, cell, () -> send(PATTERN_ADD_INPUT, slot, shown.page(), value())); widgets.add(addInput);
		addOutput = button(text("pattern_add_output"), 12 + cell, 56, cell, () -> send(PATTERN_ADD_OUTPUT, slot, shown.page(), value())); widgets.add(addOutput);
		setAmount = button(text("pattern_set_amount"), 16 + cell * 2, 56, cell, () -> send(PATTERN_SET_AMOUNT, selected, shown.page(), value())); widgets.add(setAmount);
		remove = button(text("pattern_remove"), 20 + cell * 3, 56, cell, () -> send(PATTERN_REMOVE, selected, shown.page(), 0)); widgets.add(remove);
		clear = button(text("pattern_clear"), 24 + cell * 4, 56, cell, () -> send(PATTERN_CLEAR, -1, shown.page(), 0));
		clear.setTooltip(Tooltip.create(text("pattern_clear_hint"))); widgets.add(clear);
		int bottom = height - 21;
		widgets.add(Button.builder(text("back"), ignored -> back.run()).bounds(left + 8, top + bottom, 52, 14).build());
		previous = button(text("previous"), 64, bottom, 24, () -> send(PAGE, -1, Math.max(0, shown.page() - 1), 0)); widgets.add(previous);
		next = button(text("next"), 92, bottom, 24, () -> send(PAGE, -1, shown.page() + 1, 0)); widgets.add(next);
		apply = button(text("pattern_encode_apply"), width - 100, bottom, 92, () -> send(PATTERN_APPLY, -1, shown.page(), 0));
		apply.setTooltip(Tooltip.create(text("pattern_processing_apply_hint", cursor.getCount()))); widgets.add(apply);
		amount.setResponder(ignored -> tick()); tick(); return widgets;
	}
	private Button button(Component label, int x, int y, int width, Runnable action) {
		var button = Button.builder(label, ignored -> action.run()).bounds(left + x, top + y, width, 14).build(); edits.add(button); return button;
	}
	private void send(MeTerminalRequest.Action action, int row, int page, long count) { commands.send(action, row, page, count, contents); }
	private long value() { try { return Long.parseLong(amount.getValue()); } catch (NumberFormatException invalid) { return 0; } }
	private boolean current() { return ItemStack.matches(cursor, menu.getCarried()); }
	private boolean amountChanged() { return selected >= 0 && value() != shown.rows().get(selected).extra(); }
	void tick() {
		if (shown == null || apply == null) return;
		boolean idle = !session.waiting(), editable = idle && current() && shown.revision() != 0;
		for (var button : edits) button.active = idle;
		amount.active = idle;
		addInput.active = addOutput.active = editable && value() > 0;
		setAmount.active = editable && selected >= 0 && value() > 0;
		remove.active = editable && selected >= 0; clear.active = editable && !shown.rows().isEmpty();
		apply.active = idle && current() && shown.confirm() && !amountChanged();
		previous.active = editable && shown.page() > 0; next.active = editable && shown.more();
	}
	boolean click(double x, double y, int button) {
		if (button != 0 || session.waiting() || x < left + 8 || x >= left + width - 8 || y < top + 72 || y >= top + 200) return false;
		int row = (int) (y - top - 72) / 16;
		if (row >= shown.rows().size()) return false;
		selected = row; amount.setValue(Long.toString(shown.rows().get(row).extra())); tick(); return true;
	}
	void render(GuiGraphics g, Font font, int mouseX, int mouseY) {
		if (shown.rows().isEmpty()) g.drawWordWrap(font, text("pattern_processing_hint"), left + 8, top + 77, width - 16, TerminalSkin.MUTED);
		for (int i = 0; i < shown.rows().size(); i++) {
			var row = shown.rows().get(i); int y = top + 72 + i * 16;
			g.fill(left + 7, y, left + width - 7, y + 16, selected == i ? 0xff526a6e : 0xff25383e);
			var icon = row.icon(); if (!icon.isEmpty()) g.renderItem(icon, left + 9, y);
			String label = text(row.kind() == MeTerminalView.Kind.PATTERN_INPUT ? "pattern_input" : "pattern_output").getString() + " " + row.label();
			g.drawString(font, font.plainSubstrByWidth(label, width - 132), left + 28, y + 4, 0xffe0e5de, false);
			g.drawString(font, TerminalProductIcon.compact(Long.toString(row.extra())), left + width - 94, y + 4, 0xffc8cfba, false);
		}
		var status = session.waiting() ? MeTerminalView.Status.WAITING : !current() ? MeTerminalView.Status.STALE : shown.status();
		var message = status == MeTerminalView.Status.OK ? text(amountChanged() ? "pattern_amount_pending" : "pattern_processing_ready") : text("status." + status.name().toLowerCase(Locale.ROOT));
		g.drawString(font, font.plainSubstrByWidth(message.getString(), width - 16), left + 8, top + height - 34, TerminalSkin.MUTED, false);
		if (!menu.getCarried().isEmpty()) {
			g.renderItem(menu.getCarried(), left + 120, top + height - 22);
			g.drawString(font, font.plainSubstrByWidth(text("pattern_stack_count", menu.getCarried().getCount()).getString(), Math.max(0, width - 242)), left + 138, top + height - 18, TerminalSkin.MUTED, false);
		}
		if (mouseX >= left + 8 && mouseX < left + width - 8 && mouseY >= top + 72 && mouseY < top + 200) {
			int row = (mouseY - top - 72) / 16;
			if (row < shown.rows().size()) g.renderComponentTooltip(font, List.of(Component.literal(shown.rows().get(row).label()),
					text("pattern_encoded_amount", shown.rows().get(row).extra()), text("pattern_select_hint")), mouseX, mouseY);
		}
	}
}
