package com.ayoshiko.productivebeesgenesis.apiculture.me;

import com.ayoshiko.productivebeesgenesis.apiculture.core.TerminalCursorExchange;
import com.ayoshiko.productivebeesgenesis.apiculture.core.TerminalPatternInventory;
import com.ayoshiko.productivebeesgenesis.apiculture.core.TerminalPatternSample;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import static com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalRequest.Action.*;
import static com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalView.*;

/** 按共享预算逐张准备；选定项在一次无外部调用的背包提交中全部改写。 */
final class MePatternBatchSession {
	private static final class Entry {
		final int slot; final ItemStack original;
		String label;
		MePatternPlan plan = MePatternPlan.failed(Status.WAITING); boolean selected;
		Entry(int slot, ItemStack original) { this.slot = slot; this.original = original.copy(); label = label(slot, new ItemStack(original.getItem()).getHoverName().getString()); }
	}
	private final List<Entry> entries = new ArrayList<>();
	private AbstractContainerMenu menu;
	private ItemStack carried;
	private TerminalPatternSample sample;
	private MePatternBatchEditor editor;
	private String title;
	private long until, generation;
	private int prepared, detail = -1;
	private MeTerminalView view;
	MePatternBatchSession(ServerPlayer player, ItemStack carried, TerminalPatternSample sample, MePatternBatchEditor editor) {
		menu = player.containerMenu; this.carried = carried.copy(); this.sample = sample; this.editor = editor; title = editor.title();
		for (int i = 0; i < 36; i++) if (editor.supports(player.getInventory().items.get(i))) entries.add(new Entry(i, player.getInventory().items.get(i)));
		until = player.server.overworld().getGameTime() + 600;
	}
	MeTerminalView start(ServerPlayer player) { return current(player) ? page(player, 0, entries.isEmpty() ? Status.PATTERN_BATCH_EMPTY : Status.WAITING) : clear(Status.STALE); }
	MeTerminalView request(ServerPlayer player, MeTerminalRequest request) {
		if (view == null || request.revision() == 0 || request.revision() != view.revision() || !current(player)) return clear(Status.STALE);
		if (request.action() == POLL) {
			if (request.row() != -1) return clear(Status.INVALID);
			if (prepared < entries.size() && MeTerminalBudget.expensive(player.server)) {
				var entry = entries.get(prepared); entry.plan = editor.prepare(entry.original);
				if (!current(player)) return clear(Status.STALE);
				for (var row : entry.plan.rows()) if (row.kind() == Kind.PATTERN_OUTPUT) { entry.label = label(entry.slot, row.label()); break; }
				prepared++; if (prepared == entries.size()) until = player.server.overworld().getGameTime() + 600;
			}
			return page(player, view.page(), readyStatus());
		}
		if (request.action() == PAGE) {
			if (request.row() != -1) return clear(Status.INVALID);
			return page(player, request.page(), readyStatus());
		}
		if (request.page() != view.page() || prepared < entries.size()) return clear(Status.STALE);
		if (request.action() == PATTERN_BATCH_LIST) {
			if (request.row() != -1) return clear(Status.INVALID);
			int page = Math.max(0, detail) / 8; detail = -1; return page(player, page, readyStatus());
		}
		if (detail >= 0) return clear(Status.INVALID);
		if (request.action() == PATTERN_BATCH_TOGGLE || request.action() == PATTERN_BATCH_DETAILS) {
			int index = view.page() * 8 + request.row();
			if (request.row() < 0 || request.row() >= view.rows().size() || index >= entries.size()) return clear(Status.STALE);
			var entry = entries.get(index);
			if (entry.plan.status() != Status.OK) return page(player, view.page(), entry.plan.status());
			if (request.action() == PATTERN_BATCH_DETAILS) { detail = index; return page(player, 0, Status.OK); }
			entry.selected = !entry.selected; return page(player, view.page(), Status.OK);
		}
		if (request.action() == PATTERN_BATCH_SELECT) {
			if (request.row() != -1 || request.amount() > 1) return clear(Status.INVALID);
			for (var entry : entries) entry.selected = request.amount() == 1 && entry.plan.status() == Status.OK;
			return page(player, view.page(), readyStatus());
		}
		if (request.action() != PATTERN_APPLY || request.row() != -1 || !view.confirm()) return clear(Status.INVALID);
		if (!MeTerminalBudget.expensive(player.server)) return page(player, view.page(), Status.BUSY);
		var changes = new ArrayList<TerminalPatternInventory.Replacement>();
		for (var entry : entries) if (entry.selected) changes.add(new TerminalPatternInventory.Replacement(entry.slot, entry.original, entry.plan.result()));
		var target = menu; var source = carried; var material = sample; var mapping = editor;
		close(); long expectedGeneration = generation;
		if (!mapping.current(material.item()) || generation != expectedGeneration || !material.current(player, target)) return clear(Status.STALE);
		var changed = material.commit(player, target, () -> TerminalPatternInventory.replace(player, target, source, changes));
		return clear(changed.outcome() == TerminalCursorExchange.Outcome.MOVED ? Status.PATTERN_BATCH_APPLIED : MeTerminalSession.fluidStatus(changed.outcome()));
	}
	private Status readyStatus() { return entries.isEmpty() ? Status.PATTERN_BATCH_EMPTY : prepared < entries.size() ? Status.WAITING : Status.OK; }
	private boolean current(ServerPlayer player) {
		if (menu == null || player.containerMenu != menu || !menu.stillValid(player) || player.server.overworld().getGameTime() >= until
				|| TerminalCursorExchange.unknown(player) || !ItemStack.matches(carried, menu.getCarried()) || !sample.current(player, menu)) return false;
		for (var entry : entries) if (!ItemStack.matches(entry.original, player.getInventory().items.get(entry.slot))) return false;
		return true;
	}
	private MeTerminalView page(ServerPlayer player, int page, Status status) {
		var rows = new ArrayList<Row>(); int selected = 0;
		for (var entry : entries) if (entry.selected) selected++;
		int size = detail >= 0 ? entries.get(detail).plan.rows().size() : entries.size();
		int start = size == 0 ? 0 : Math.min(page, (size - 1) / 8) * 8;
		if (detail >= 0) rows.addAll(entries.get(detail).plan.rows().subList(start, Math.min(start + 8, size)));
		else for (int i = start; i < Math.min(start + 8, size); i++) {
			var entry = entries.get(i);
			// 列表图标不携带整份编码组件；完整资源差异由详情页按八行发送。
			rows.add(new Row(Kind.PATTERN_BATCH_ITEM, new ItemStack(entry.original.getItem()), entry.label, entry.original.getCount(), entry.plan.status().ordinal(), entry.selected));
		}
		return view = new MeTerminalView(MeTerminalBudget.revision(player.server), detail < 0 ? Mode.PATTERN_BATCH : Mode.PATTERN_BATCH_DETAIL,
				status, start / 8, start + 8 < size, title, selected, "", status == Status.OK && detail < 0 && selected > 0, rows);
	}
	private static String label(int slot, String resource) {
		String label = (slot + 1) + " · " + resource;
		return label.length() <= 128 ? label : label.substring(0, Character.isHighSurrogate(label.charAt(127)) ? 127 : 128);
	}
	private MeTerminalView clear(Status status) { close(); return view = MeTerminalView.patternStatus(status, Mode.PATTERN_BATCH); }
	void close() { generation++; menu = null; carried = ItemStack.EMPTY; sample = null; editor = null; title = ""; entries.clear(); prepared = 0; detail = -1; until = 0; }
}
