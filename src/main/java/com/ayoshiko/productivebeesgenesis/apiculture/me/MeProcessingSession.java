package com.ayoshiko.productivebeesgenesis.apiculture.me;

import com.ayoshiko.productivebeesgenesis.apiculture.bridge.MeBridgeIntegration;
import com.ayoshiko.productivebeesgenesis.apiculture.core.TerminalCursorExchange;
import com.ayoshiko.productivebeesgenesis.apiculture.core.TerminalPatternSample;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import static com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalRequest.Action.*;
import static com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalView.*;

/** 定义跟随真实菜单；离开工作页只撤销预览，允许返回九格更换样本。 */
final class MeProcessingSession {
	private MeProcessingDraft draft;
	private AbstractContainerMenu menu;
	private ItemStack original = ItemStack.EMPTY, result = ItemStack.EMPTY;
	private List<Row> rows = List.of();
	private Status status = Status.CLOSED;
	private long until, generation;
	private MeTerminalView view = MeTerminalView.patternStatus(Status.CLOSED, Mode.PATTERN_PROCESSING);

	MeTerminalView request(ServerPlayer player, MeTerminalRequest request) {
		if (TerminalCursorExchange.unknown(player)) { suspend(); return reject(player, Status.TRANSFER_UNKNOWN); }
		if (request.action() == PATTERN_ENCODE_PROCESSING) {
			suspend();
			if (request.row() != -1) return reject(player, Status.INVALID);
			if (!MeTerminalBudget.expensive(player.server)) return reject(player, Status.BUSY);
			if (draft == null) draft = MeBridgeIntegration.processingDraft();
			if (draft == null) return reject(player, Status.PATTERN_UNSUPPORTED);
			menu = player.containerMenu; original = menu.getCarried().copy();
			return preview(player, 0, null);
		}
		if (draft == null || request.revision() == 0 || request.revision() != view.revision() || !current(player)) {
			suspend(); return reject(player, Status.STALE);
		}
		if (request.action() == PAGE) {
			if (request.row() != -1) return reject(player, Status.INVALID);
			return page(player, request.page());
		}
		if (request.page() != view.page()) return reject(player, Status.STALE);
		if (!MeTerminalBudget.expensive(player.server)) return reject(player, Status.BUSY);
		if (request.action() == PATTERN_APPLY) return apply(player, request);
		boolean adding = request.action() == PATTERN_ADD_INPUT || request.action() == PATTERN_ADD_OUTPUT;
		TerminalPatternSample sample = null; int index = -1;
		if (adding) {
			if (request.row() < 0 || request.row() >= 9 || request.amount() < 1
					|| !request.query().equals("item") && !request.query().equals("contents")) return reject(player, Status.INVALID);
			sample = TerminalPatternSample.capture(player, menu, request.row());
			if (sample == null) return reject(player, Status.PATTERN_SAMPLE_INVALID);
		} else if (request.action() == PATTERN_SET_AMOUNT || request.action() == PATTERN_REMOVE) {
			if (request.row() < 0 || request.row() >= view.rows().size()) return reject(player, Status.STALE);
			index = view.page() * 8 + request.row();
		} else if (request.action() != PATTERN_CLEAR || request.row() != -1) return reject(player, Status.INVALID);
		var captured = sample; var activeDraft = draft; long expectedGeneration = generation;
		var edited = activeDraft.edit(request.action(), index, request.amount(), sample == null ? ItemStack.EMPTY : sample.item(),
				request.query().equals("contents"), () -> generation == expectedGeneration && draft == activeDraft && current(player)
						&& !TerminalCursorExchange.unknown(player) && (captured == null || captured.current(player, menu)));
		if (edited != Status.OK) return reject(player, edited);
		return preview(player, request.page(), adding ? request.action() == PATTERN_ADD_INPUT ? Kind.PATTERN_INPUT : Kind.PATTERN_OUTPUT : null);
	}
	private MeTerminalView preview(ServerPlayer player, int requestedPage, Kind focus) {
		result = ItemStack.EMPTY; until = player.server.overworld().getGameTime() + 600;
		long expectedGeneration = generation;
		var plan = draft.preview(original);
		if (generation != expectedGeneration || !current(player) || TerminalCursorExchange.unknown(player)) { suspend(); return reject(player, Status.STALE); }
		rows = plan.rows(); result = plan.result(); status = plan.status();
		if (focus != null) for (int i = 0; i < rows.size(); i++) if (rows.get(i).kind() == focus) requestedPage = i / 8;
		return page(player, requestedPage);
	}
	private MeTerminalView apply(ServerPlayer player, MeTerminalRequest request) {
		if (request.row() != -1 || !view.confirm() || result.isEmpty()) return reject(player, Status.INVALID);
		var before = original; var after = result; var target = menu; var activeDraft = draft;
		// 重新编码前撤销可重放的预览；定义不持资产，失败只需重新预览。
		suspend(); long expectedGeneration = generation;
		var fresh = activeDraft.preview(before);
		if (generation != expectedGeneration || draft != activeDraft || player.containerMenu != target || !target.stillValid(player)
				|| fresh.status() != Status.OK || !ItemStack.matches(after, fresh.result())) return reject(player, Status.STALE);
		var changed = TerminalCursorExchange.convert(player, target, before, after);
		return reject(player, changed.outcome() == TerminalCursorExchange.Outcome.MOVED ? Status.PATTERN_PROCESSING_ENCODED : MeTerminalSession.fluidStatus(changed.outcome()));
	}
	private boolean current(ServerPlayer player) {
		return menu != null && player.containerMenu == menu && menu.stillValid(player) && player.server.overworld().getGameTime() < until
				&& ItemStack.matches(original, menu.getCarried());
	}
	private MeTerminalView reject(ServerPlayer player, Status reason) { result = ItemStack.EMPTY; status = reason; return page(player, view.page()); }
	private MeTerminalView page(ServerPlayer player, int page) {
		int start = rows.isEmpty() ? 0 : Math.min(page, (rows.size() - 1) / 8) * 8;
		return view = new MeTerminalView(MeTerminalBudget.revision(player.server), Mode.PATTERN_PROCESSING, status,
				start / 8, start + 8 < rows.size(), "", 0, "", status == Status.OK && current(player) && !result.isEmpty(),
				rows.subList(start, Math.min(start + 8, rows.size())));
	}
	void suspend() { generation++; menu = null; original = ItemStack.EMPTY; result = ItemStack.EMPTY; until = 0; }
	void close() { suspend(); draft = null; rows = List.of(); }
}
