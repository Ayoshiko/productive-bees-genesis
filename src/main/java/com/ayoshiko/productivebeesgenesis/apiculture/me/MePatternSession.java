package com.ayoshiko.productivebeesgenesis.apiculture.me;

import com.ayoshiko.productivebeesgenesis.apiculture.bridge.MeBridgeIntegration;
import com.ayoshiko.productivebeesgenesis.apiculture.core.TerminalCursorExchange;
import com.ayoshiko.productivebeesgenesis.apiculture.core.TerminalPatternSource;
import com.ayoshiko.productivebeesgenesis.apiculture.core.TerminalPatternSample;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import static com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalRequest.Action.*;
import static com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalView.*;

/** 预览只属于当前菜单；确认先撤销授权，再原位发布同数量实物。 */
final class MePatternSession {
	private ItemStack original = ItemStack.EMPTY, result = ItemStack.EMPTY;
	private AbstractContainerMenu menu;
	private TerminalPatternSource source;
	private TerminalPatternSample sample;
	private CraftingOptions craftingOptions = CraftingOptions.DEFAULT;
	private MePatternBatchSession batch;
	private int replacementRow = -1;
	private Mode mode = Mode.PATTERN;
	private List<Row> rows = List.of();
	private String title = "";
	private long until, generation;
	private MeTerminalView view = MeTerminalView.patternStatus(Status.CLOSED);
	static boolean handles(MeTerminalRequest.Action action) { return action.patternScaleBatch() || action == PATTERN_READ || action == PATTERN_MULTIPLY || action == PATTERN_DIVIDE || action == PATTERN_APPLY || action == PATTERN_ENCODE_CRAFTING || action == PATTERN_REPLACE || action == PATTERN_BATCH_REPLACE || action == PATTERN_BUFFER_BATCH_REPLACE || action == PATTERN_BATCH_TOGGLE || action == PATTERN_BATCH_SELECT || action == PATTERN_BATCH_DETAILS || action == PATTERN_BATCH_LIST; }
	MeTerminalView request(ServerPlayer player, MeTerminalRequest request) {
		if (batch != null && (request.action() == PAGE || request.action() == POLL || request.action() == PATTERN_APPLY || request.action() == PATTERN_BATCH_TOGGLE || request.action() == PATTERN_BATCH_SELECT || request.action() == PATTERN_BATCH_DETAILS || request.action() == PATTERN_BATCH_LIST)) return batch.request(player, request);
		if (request.action() == PATTERN_BATCH_REPLACE || request.action() == PATTERN_BUFFER_BATCH_REPLACE) return beginBatch(player, request);
		if (request.action().patternScaleBatch()) return beginScaleBatch(player, request);
		if (request.action() == PATTERN_REPLACE) return replace(player, request);
		boolean preview = request.action() == PATTERN_READ || request.action() == PATTERN_MULTIPLY || request.action() == PATTERN_DIVIDE || request.action() == PATTERN_ENCODE_CRAFTING;
		if (preview) { close(); mode = request.action() == PATTERN_ENCODE_CRAFTING ? Mode.PATTERN_ENCODING : Mode.PATTERN; }
		if (request.row() != -1) return clear(player, Status.INVALID);
		if (TerminalCursorExchange.unknown(player)) return clear(player, Status.TRANSFER_UNKNOWN);
		if (preview) {
			if (!MeTerminalBudget.expensive(player.server)) return clear(player, Status.BUSY);
			menu = player.containerMenu; original = menu.getCarried().copy();
			MePatternPlan plan;
			if (request.action() == PATTERN_ENCODE_CRAFTING || request.action() == PATTERN_READ && MeBridgeIntegration.blankPattern(original)) {
				mode = Mode.PATTERN_ENCODING;
				craftingOptions = request.action() == PATTERN_ENCODE_CRAFTING ? CraftingOptions.from(request.amount()) : CraftingOptions.DEFAULT;
				if (!MeBridgeIntegration.installed()) return clear(player, Status.PATTERN_UNSUPPORTED);
				if (!MeBridgeIntegration.blankPattern(original)) return clear(player, Status.PATTERN_NEEDS_BLANK);
				source = TerminalPatternSource.capture(player, menu);
				if (source == null) return clear(player, Status.PATTERN_NO_RECIPE);
				plan = MeBridgeIntegration.encodeCraftingPattern(player, original, source, craftingOptions); title = source.recipe().id().toString();
				if (title.length() > 256) title = title.substring(0, 256);
			} else {
				long factor = request.action() == PATTERN_READ ? 1 : request.amount();
				if (factor < 1) return clear(player, Status.INVALID);
				plan = MeBridgeIntegration.scalePattern(original, factor, request.action() == PATTERN_DIVIDE);
				title = (request.action() == PATTERN_DIVIDE ? "÷ " : "× ") + factor;
			}
			if (plan.status() != Status.OK) return clear(player, plan.status());
			result = plan.result(); rows = plan.rows();
			until = player.server.overworld().getGameTime() + 600;
			if (!current(player)) return clear(player, Status.STALE);
			return page(player, 0, Status.OK);
		}
		if (request.revision() != view.revision() || request.revision() == 0 || !current(player)) return clear(player, Status.STALE);
		if (request.action() == PAGE) return page(player, request.page(), Status.OK);
		if (request.action() != PATTERN_APPLY || !view.confirm()) return clear(player, Status.INVALID);
		if (!MeTerminalBudget.expensive(player.server)) return view.status(Status.BUSY);
		var before = original; var after = result; var target = menu; var encodingSource = source; var replacementSample = sample; int selectedRow = replacementRow;
		var selectedOptions = craftingOptions;
		boolean encoding = mode == Mode.PATTERN_ENCODING, replacing = mode == Mode.PATTERN_REPLACEMENT;
		clear(player, Status.WAITING);
		long commitGeneration = generation;
		TerminalCursorExchange.Result changed;
		if (encodingSource != null) {
			var fresh = MeBridgeIntegration.encodeCraftingPattern(player, before, encodingSource, selectedOptions);
			if (generation != commitGeneration || fresh.status() != Status.OK || !ItemStack.matches(after, fresh.result())) return clear(player, Status.STALE);
			changed = encodingSource.commit(player, target, () -> generation == commitGeneration && target.stillValid(player)
					? TerminalCursorExchange.convert(player, target, before, after) : new TerminalCursorExchange.Result(TerminalCursorExchange.Outcome.INVALID, 0));
		}
		else if (replacementSample != null) {
			var fresh = MeBridgeIntegration.replacePattern(before, selectedRow, replacementSample.item());
			if (fresh.status() != Status.OK || !ItemStack.matches(after, fresh.result())) return clear(player, Status.STALE);
			changed = replacementSample.commit(player, target, () -> TerminalCursorExchange.rewrite(player, target, before, after));
		} else changed = TerminalCursorExchange.rewrite(player, target, before, after);
		var success = encoding ? Status.PATTERN_ENCODED : replacing ? Status.PATTERN_REPLACED : Status.PATTERN_APPLIED;
		return clear(player, changed.outcome() == TerminalCursorExchange.Outcome.MOVED ? success : MeTerminalSession.fluidStatus(changed.outcome()));
	}
	private MeTerminalView replace(ServerPlayer player, MeTerminalRequest request) {
		if (mode != Mode.PATTERN || view.confirm() || view.status() != Status.OK || request.revision() != view.revision()
				|| request.page() != view.page() || request.row() < 0 || request.row() >= view.rows().size() || !current(player)) return clear(player, Status.STALE);
		var before = original; var target = menu; int selectedRow = view.page() * 8 + request.row(); String selectedTitle = rows.get(selectedRow).label();
		close(); mode = Mode.PATTERN_REPLACEMENT;
		if (TerminalCursorExchange.unknown(player)) return clear(player, Status.TRANSFER_UNKNOWN);
		if (!MeTerminalBudget.expensive(player.server)) return clear(player, Status.BUSY);
		menu = target; original = before; replacementRow = selectedRow; sample = TerminalPatternSample.capture(player, menu);
		if (sample == null) return clear(player, Status.PATTERN_SAMPLE_INVALID);
		var plan = MeBridgeIntegration.replacePattern(original, selectedRow, sample.item());
		if (plan.status() != Status.OK) return clear(player, plan.status());
		result = plan.result(); rows = plan.rows(); title = selectedTitle; until = player.server.overworld().getGameTime() + 600;
		if (!current(player)) return clear(player, Status.STALE);
		return page(player, 0, Status.OK);
	}
	private MeTerminalView beginBatch(ServerPlayer player, MeTerminalRequest request) {
		if (mode != Mode.PATTERN || view.confirm() || view.status() != Status.OK || request.revision() != view.revision()
				|| request.page() != view.page() || request.row() < 0 || request.row() >= view.rows().size() || !current(player)) return clear(player, Status.STALE);
		var before = original; var target = menu; int selectedRow = view.page() * 8 + request.row();
		boolean buffered = request.action() == PATTERN_BUFFER_BATCH_REPLACE;
		close(); mode = buffered ? Mode.PATTERN_BUFFER_BATCH : Mode.PATTERN_BATCH;
		if (TerminalCursorExchange.unknown(player)) return clear(player, Status.TRANSFER_UNKNOWN);
		if (!MeTerminalBudget.expensive(player.server)) return clear(player, Status.BUSY);
		var material = TerminalPatternSample.capture(player, target);
		if (material == null) return clear(player, Status.PATTERN_SAMPLE_INVALID);
		var mapping = MeBridgeIntegration.patternReplacement(before, selectedRow, material.item());
		if (mapping.status() != Status.OK) return clear(player, mapping.status());
		if (!material.current(player, target) || !ItemStack.matches(before, target.getCarried())) return clear(player, Status.STALE);
		var buffer = buffered ? com.ayoshiko.productivebeesgenesis.apiculture.core.TerminalPatternBuffer.capture(player) : null;
		if (buffered && buffer == null) return clear(player, Status.TRANSFER_UNKNOWN);
		batch = new MePatternBatchSession(player, before, material, mapping.editor(), buffer);
		return batch.start(player);
	}
	private MeTerminalView beginScaleBatch(ServerPlayer player, MeTerminalRequest request) {
		if (mode != Mode.PATTERN || view.status() != Status.OK || request.revision() == 0 || request.revision() != view.revision()
				|| request.page() != view.page() || !current(player) || !menu.stillValid(player)) return clear(player, Status.STALE);
		if (request.row() != -1 || request.amount() < 1) return clear(player, Status.INVALID);
		var before = original; var target = menu;
		boolean buffered = request.action() == PATTERN_BUFFER_BATCH_MULTIPLY || request.action() == PATTERN_BUFFER_BATCH_DIVIDE;
		boolean divide = request.action() == PATTERN_BATCH_DIVIDE || request.action() == PATTERN_BUFFER_BATCH_DIVIDE;
		close(); mode = buffered ? Mode.PATTERN_BUFFER_BATCH : Mode.PATTERN_BATCH;
		if (TerminalCursorExchange.unknown(player)) return clear(player, Status.TRANSFER_UNKNOWN);
		if (!MeTerminalBudget.expensive(player.server)) return clear(player, Status.BUSY);
		var scaling = MeBridgeIntegration.patternScale(request.amount(), divide);
		if (scaling.status() != Status.OK) return clear(player, scaling.status());
		if (player.containerMenu != target || !ItemStack.matches(before, target.getCarried())) return clear(player, Status.STALE);
		var buffer = buffered ? com.ayoshiko.productivebeesgenesis.apiculture.core.TerminalPatternBuffer.capture(player) : null;
		if (buffered && buffer == null) return clear(player, Status.TRANSFER_UNKNOWN);
		batch = new MePatternBatchSession(player, before, null, scaling.editor(), buffer);
		return batch.start(player);
	}
	private boolean current(ServerPlayer player) {
		return menu != null && player.containerMenu == menu && !original.isEmpty() && !result.isEmpty()
				&& player.server.overworld().getGameTime() < until && ItemStack.matches(original, menu.getCarried())
				&& (source == null ? mode != Mode.PATTERN_ENCODING : source.current(player, menu))
				&& (sample == null ? mode != Mode.PATTERN_REPLACEMENT : sample.current(player, menu));
	}
	private MeTerminalView page(ServerPlayer player, int page, Status status) {
		int size = MeTerminalView.pageSize(mode), start = rows.isEmpty() ? 0 : Math.min(page, (rows.size() - 1) / size) * size;
		return view = new MeTerminalView(MeTerminalBudget.revision(player.server), mode, status, start / size, start + size < rows.size(), title, 0, "",
				status == Status.OK && !result.isEmpty() && !ItemStack.matches(original, result), rows.subList(start, Math.min(start + size, rows.size()))).withCraftingOptions(craftingOptions);
	}
	private MeTerminalView clear(ServerPlayer player, Status status) { var selected = craftingOptions; close(); if (mode == Mode.PATTERN_ENCODING) craftingOptions = selected; return page(player, 0, status); }
	void close() { generation++; if (batch != null) batch.close(); batch = null; original = ItemStack.EMPTY; result = ItemStack.EMPTY; rows = List.of(); menu = null; source = null; sample = null; craftingOptions = CraftingOptions.DEFAULT; replacementRow = -1; title = ""; until = 0; }
}
