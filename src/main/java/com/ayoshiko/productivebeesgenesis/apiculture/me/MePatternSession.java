package com.ayoshiko.productivebeesgenesis.apiculture.me;

import com.ayoshiko.productivebeesgenesis.apiculture.bridge.MeBridgeIntegration;
import com.ayoshiko.productivebeesgenesis.apiculture.core.TerminalCursorExchange;
import com.ayoshiko.productivebeesgenesis.apiculture.core.TerminalPatternSource;
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
	private Mode mode = Mode.PATTERN;
	private List<Row> rows = List.of();
	private String title = "";
	private long until;
	private MeTerminalView view = MeTerminalView.patternStatus(Status.CLOSED);
	static boolean handles(MeTerminalRequest.Action action) { return action == PATTERN_READ || action == PATTERN_MULTIPLY || action == PATTERN_DIVIDE || action == PATTERN_APPLY || action == PATTERN_ENCODE_CRAFTING; }
	MeTerminalView request(ServerPlayer player, MeTerminalRequest request) {
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
				if (!MeBridgeIntegration.installed()) return clear(player, Status.PATTERN_UNSUPPORTED);
				if (!MeBridgeIntegration.blankPattern(original)) return clear(player, Status.PATTERN_NEEDS_BLANK);
				source = TerminalPatternSource.capture(player, menu);
				if (source == null) return clear(player, Status.PATTERN_NO_RECIPE);
				plan = MeBridgeIntegration.encodeCraftingPattern(player, original, source); title = source.recipe().id().toString();
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
		var before = original; var after = result; var target = menu; var encodingSource = source;
		boolean encoding = mode == Mode.PATTERN_ENCODING;
		clear(player, Status.WAITING);
		var changed = encodingSource == null ? TerminalCursorExchange.rewrite(player, target, before, after)
				: encodingSource.commit(player, target, () -> TerminalCursorExchange.convert(player, target, before, after));
		return clear(player, changed.outcome() == TerminalCursorExchange.Outcome.MOVED ? encoding ? Status.PATTERN_ENCODED : Status.PATTERN_APPLIED : MeTerminalSession.fluidStatus(changed.outcome()));
	}
	private boolean current(ServerPlayer player) {
		return menu != null && player.containerMenu == menu && !original.isEmpty() && !result.isEmpty()
				&& player.server.overworld().getGameTime() < until && ItemStack.matches(original, menu.getCarried())
				&& (source == null ? mode != Mode.PATTERN_ENCODING : source.current(player, menu));
	}
	private MeTerminalView page(ServerPlayer player, int page, Status status) {
		int start = rows.isEmpty() ? 0 : Math.min(page, (rows.size() - 1) / 8) * 8;
		return view = new MeTerminalView(MeTerminalBudget.revision(player.server), mode, status, start / 8, start + 8 < rows.size(), title, 0, "",
				status == Status.OK && !result.isEmpty() && !ItemStack.matches(original, result), rows.subList(start, Math.min(start + 8, rows.size())));
	}
	private MeTerminalView clear(ServerPlayer player, Status status) { close(); return page(player, 0, status); }
	void close() { original = ItemStack.EMPTY; result = ItemStack.EMPTY; rows = List.of(); menu = null; source = null; title = ""; until = 0; }
}
