package com.ayoshiko.productivebeesgenesis.apiculture.core;

import com.ayoshiko.productivebeesgenesis.apiculture.terminal.*;
import net.minecraft.server.level.ServerPlayer;
import static com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalReply.Status.*;

/** 令牌解析与有限服务编排；不另写一套资产转移或修正账本。 */
final class CoreTerminalCommands {
	static TerminalReply execute(NetworkCoreMenu menu, ServerPlayer player, TerminalRequest request, NetworkSelectionSession selections) {
		var operation = request.operation();
		if (operation == TerminalRequest.Operation.CANCEL) {
			selections.cancel(); return reply(request, OK, 0, 0, null);
		}
		var core = menu.exchangeCore(player);
		if (core == null || core.ownership().readyAuthority() == null) return reply(request, UNAVAILABLE, 0, 0, null);
		if (operation == TerminalRequest.Operation.MEMBERS || operation == TerminalRequest.Operation.PRODUCTS || operation == TerminalRequest.Operation.NEXT) {
			NetworkSelectionSession.Page page;
			if (operation == TerminalRequest.Operation.NEXT) {
				var previous = selections.page();
				page = previous == null || request.generation() == 0 ? null : menu.querySelections(player, previous.kind(), request.generation());
			} else {
				page = menu.querySelections(player, operation == TerminalRequest.Operation.MEMBERS
						? NetworkSelectionSession.Kind.MEMBERS : NetworkSelectionSession.Kind.PRODUCTS, 0);
			}
			return reply(request, page == null ? STALE : OK, 0, 0, page == null ? null : TerminalViewProjection.project(page));
		}
		var selected = menu.selectedRow(player, request.session(), request.generation(), request.row());
		if (selected == null) return reply(request, STALE, 0, 0, null);
		try {
			var current = core.ownership().readyAuthority().checkpoint();
			if (operation == TerminalRequest.Operation.TAKE_PRODUCT) {
				if (!(selected instanceof NetworkSelectionSession.ProductRow product)) return reply(request, INVALID, 0, 0, null);
				var result = menu.withdrawProduct(player, product.key(), current.ledger().revision(), request.inventorySlot(), request.amount(), false);
				return reply(request, TerminalReply.Status.valueOf(result.status().name()), result.moved(), 0, null);
			}
			if (!(selected instanceof NetworkSelectionSession.MemberRow member)) return reply(request, INVALID, 0, 0, null);
			var record = current.ownedMachines().get(member.claim().member());
			if (!NetworkSelectionSession.sameRoster(member, record)) return reply(request, STALE, 0, 0, null);
			if (request.targetSlot() < 0 || request.targetSlot() >= 3) return reply(request, INVALID, 0, 0, null);
			if (operation == TerminalRequest.Operation.FEED_IN || operation == TerminalRequest.Operation.FEED_OUT) {
				if (record.bees().feeding() == null) return reply(request, UNAVAILABLE, 0, 0, null);
				var result = menu.exchangeFeeding(player, member.claim().member(), request.targetSlot(), record.bees().feeding().revision(),
						request.inventorySlot(), request.amount(), operation == TerminalRequest.Operation.FEED_IN
								? CoreFeedingExchange.Action.DEPOSIT : CoreFeedingExchange.Action.WITHDRAW, false);
				return reply(request, TerminalReply.Status.valueOf(result.status().name()), result.moved(), 0, null);
			}
			if (operation != TerminalRequest.Operation.CAGE_IN && operation != TerminalRequest.Operation.CAGE_OUT) return reply(request, INVALID, 0, 0, null);
			var bee = record.bees().bees().stream().filter(value -> value.slot() == request.targetSlot()).findFirst().orElse(null);
			if (request.amount() != 1) return reply(request, INVALID, 0, 0, null);
			if (operation == TerminalRequest.Operation.CAGE_OUT && bee == null) return reply(request, EMPTY, 0, 0, null);
			var result = menu.exchangeBee(player, member.claim().member(), request.targetSlot(), record.bees().revision(),
					operation == TerminalRequest.Operation.CAGE_OUT ? bee.id() : null, request.inventorySlot(),
					operation == TerminalRequest.Operation.CAGE_IN ? CoreBeeCageExchange.Action.INSERT : CoreBeeCageExchange.Action.EXTRACT, false);
			return reply(request, TerminalReply.Status.valueOf(result.status().name()), result.moved(), result.interruptedTicks(), null);
		} finally {
			// 结果不再携带可继续点按的旧页；客户端显式刷新，不自动重试资产命令。
			selections.cancel();
		}
	}
	private static TerminalReply reply(TerminalRequest request, TerminalReply.Status status, int moved, int interrupted, TerminalView view) {
		return new TerminalReply(request.containerId(), request.session(), request.sequence(), status, moved, interrupted, view);
	}
	private CoreTerminalCommands() { }
}
