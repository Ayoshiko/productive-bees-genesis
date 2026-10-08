package com.ayoshiko.productivebeesgenesis.apiculture.me;

import com.ayoshiko.productivebeesgenesis.apiculture.bridge.MeBridgeIntegration;
import com.ayoshiko.productivebeesgenesis.apiculture.core.TerminalCursorExchange;
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
	private List<Row> rows = List.of();
	private String title = "";
	private long until;
	private MeTerminalView view = MeTerminalView.patternStatus(Status.CLOSED);
	static boolean handles(MeTerminalRequest.Action action) { return action == PATTERN_READ || action == PATTERN_MULTIPLY || action == PATTERN_DIVIDE || action == PATTERN_APPLY; }
	MeTerminalView request(ServerPlayer player, MeTerminalRequest request) {
		if (request.row() != -1) return clear(player, Status.INVALID);
		if (TerminalCursorExchange.unknown(player)) return clear(player, Status.TRANSFER_UNKNOWN);
		if (request.action() == PATTERN_READ || request.action() == PATTERN_MULTIPLY || request.action() == PATTERN_DIVIDE) {
			if (!MeTerminalBudget.expensive(player.server)) return view.status(Status.BUSY);
			long factor = request.action() == PATTERN_READ ? 1 : request.amount();
			if (factor < 1) return clear(player, Status.INVALID);
			close(); menu = player.containerMenu; original = menu.getCarried().copy();
			var plan = MeBridgeIntegration.scalePattern(original, factor, request.action() == PATTERN_DIVIDE);
			if (plan.status() != Status.OK) return clear(player, plan.status());
			result = plan.result(); rows = plan.rows(); title = (request.action() == PATTERN_DIVIDE ? "÷ " : "× ") + factor;
			until = player.server.overworld().getGameTime() + 600;
			if (!current(player)) return clear(player, Status.STALE);
			return page(player, 0, Status.OK);
		}
		if (request.revision() != view.revision() || request.revision() == 0 || !current(player)) return clear(player, Status.STALE);
		if (request.action() == PAGE) return page(player, request.page(), Status.OK);
		if (request.action() != PATTERN_APPLY || !view.confirm()) return clear(player, Status.INVALID);
		if (!MeTerminalBudget.expensive(player.server)) return view.status(Status.BUSY);
		var before = original; var after = result; var target = menu;
		clear(player, Status.WAITING);
		var changed = TerminalCursorExchange.rewrite(player, target, before, after);
		return clear(player, changed.outcome() == TerminalCursorExchange.Outcome.MOVED ? Status.PATTERN_APPLIED : MeTerminalSession.fluidStatus(changed.outcome()));
	}
	private boolean current(ServerPlayer player) {
		return menu != null && player.containerMenu == menu && !original.isEmpty() && !result.isEmpty()
				&& player.server.overworld().getGameTime() < until && ItemStack.matches(original, menu.getCarried());
	}
	private MeTerminalView page(ServerPlayer player, int page, Status status) {
		int start = rows.isEmpty() ? 0 : Math.min(page, (rows.size() - 1) / 8) * 8;
		return view = new MeTerminalView(MeTerminalBudget.revision(player.server), Mode.PATTERN, status, start / 8, start + 8 < rows.size(), title, 0, "",
				status == Status.OK && !result.isEmpty() && !ItemStack.matches(original, result), rows.subList(start, Math.min(start + 8, rows.size())));
	}
	private MeTerminalView clear(ServerPlayer player, Status status) { close(); return page(player, 0, status); }
	void close() { original = ItemStack.EMPTY; result = ItemStack.EMPTY; rows = List.of(); menu = null; title = ""; until = 0; }
}
