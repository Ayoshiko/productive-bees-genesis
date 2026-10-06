package com.ayoshiko.productivebeesgenesis.apiculture.core;

import com.ayoshiko.productivebeesgenesis.apiculture.terminal.*;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/** 订阅只读编排；一次服务步最多扫描 32 条，最终重新解析当前页并发送有预算的投影。 */
final class CoreTerminalSubscription {
	private final NetworkCoreMenu menu;
	private final NetworkSelectionSession selections;
	private final java.util.UUID session;
	private TerminalLiveQuery query;
	private String text;
	private TerminalNameMatches nameMatches;
	private TerminalSearchRequest.Sort sort;
	private long sequence, revision, sentAcknowledged, sentAt = Long.MIN_VALUE;
	private TerminalView sentView;
	private TerminalLiveUpdate.Status sentStatus;
	private com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductQuantityIndex quantityIndex;
	private com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductQuantityIndex.Order quantityOrder;
	CoreTerminalSubscription(NetworkCoreMenu menu, NetworkSelectionSession selections) { this(menu, selections, menu.terminalSession()); }
	CoreTerminalSubscription(NetworkCoreMenu menu, NetworkSelectionSession selections, java.util.UUID session) { this.menu = menu; this.selections = selections; this.session = session; }
	boolean request(TerminalSearchRequest request) {
		if (request.sort() == TerminalSearchRequest.Sort.CAPACITY && menu.scope() != TerminalScope.APIARY) return false;
		if (request.navigation() == TerminalSearchRequest.Navigation.FIRST) {
			if (!request.quantity()) close();
			query = new TerminalLiveQuery(request.kind(), menu.scope(), request.query(), TerminalSearchNames.INSTANCE.with(request.names())); nameMatches = request.names(); text = request.query(); sort = request.sort(); quantityOrder = null;
		} else {
			if (sort != request.sort() || query == null || query.kind() != request.kind() || !text.equals(request.query()) || !nameMatches.equals(request.names())) return false;
			if (request.navigation() == TerminalSearchRequest.Navigation.REFRESH) { query.restart(); quantityOrder = null; }
			else if (!query.navigate(request.navigation() == TerminalSearchRequest.Navigation.PREVIOUS)) return false;
		}
		sequence = request.sequence(); sentView = null; sentStatus = null; sentAt = Long.MIN_VALUE;
		return true;
	}
	void close() { if (quantityIndex != null) quantityIndex.release(session); quantityIndex = null; quantityOrder = null; }
	private boolean quantities() { return sort == TerminalSearchRequest.Sort.QUANTITY_DESC || sort == TerminalSearchRequest.Sort.QUANTITY_ASC; }
	long step(ServerPlayer player, TerminalSyncBudget bytes, long now, long acknowledged) {
		if (query == null) return Long.MAX_VALUE;
		var core = menu.exchangeCore(player); var authority = core == null ? null : core.ownership().readyAuthority();
		if (authority == null) {
			selections.cancel(); emit(player, bytes, now, acknowledged, TerminalLiveUpdate.Status.UNAVAILABLE, null, false);
			return now + 10;
		}
		var current = authority.checkpoint();
		int scanBudget = 32;
		if (quantities()) {
			if (quantityIndex != core.quantityIndex()) { close(); quantityIndex = core.quantityIndex(); }
			quantityIndex.retain(session);
			// 已有排序根后给查询保留一半工作额度，持续写入不能饿死稀疏查询。
			scanBudget -= quantityIndex.step(current.ledger().balances(), now, quantityIndex.order() == null ? scanBudget : 16);
			if (quantityOrder == null || query.complete() && query.atStart() && now - quantityOrder.sampledAt() >= 100) {
				quantityOrder = quantityIndex.order(); if (quantityOrder != null) query.quantity(quantityOrder, sort == TerminalSearchRequest.Sort.QUANTITY_ASC);
			}
			if (quantityOrder == null || !query.complete() && scanBudget == 0) {
				selections.cancel(); emit(player, bytes, now, acknowledged, TerminalLiveUpdate.Status.SEARCHING, null, false); return now + 1;
			}
		}
		boolean apiaries = menu.scope() == TerminalScope.APIARY && query.kind() != NetworkSelectionSession.Kind.PRODUCTS;
		ApiaryRank.Order capacity = apiaries ? core.apiaryIndex().step(core) : null;
		ApiaryRank.Order order = sort == TerminalSearchRequest.Sort.CAPACITY ? capacity : null;
		if (apiaries && capacity == null) {
			selections.cancel(); emit(player, bytes, now, acknowledged, TerminalLiveUpdate.Status.SEARCHING, null, false); return now + 1;
		}
		query.order(order);
		if (query.complete() && (query.catalogChanged(current) || query.dynamic())) query.restart();
		if (!query.complete() && scanBudget > 0) query.step(current, scanBudget, order);
		if (!query.complete()) {
			selections.cancel(); emit(player, bytes, now, acknowledged, TerminalLiveUpdate.Status.SEARCHING, null, false); return now + 1;
		}
		var rows = query.currentRows(current);
		var candidate = selections.prepareLive(authority, current, query.kind(), query.hasNext(), rows);
		if (candidate == null) return Long.MAX_VALUE;
		var view = CoreUpgradeCommands.project(menu, player, candidate);
		if (capacity != null) {
			var displayed = new java.util.ArrayList<TerminalView.Row>(view.rows().size());
			for (int i = 0; i < view.rows().size(); i++) {
				var row = view.rows().get(i); var rank = capacity.members().get(((NetworkSelectionSession.MemberRow) rows.get(i)).claim().member());
				var details = rank == null || rank.productivity() == Float.MIN_NORMAL ? null : new TerminalView.Apiary(rank.cycleTicks(), rank.productivity());
				displayed.add(new TerminalView.Row(row.label(), row.fluid(), row.owned(), row.available(), row.exact(), row.bees(), row.detail(), row.icon(), row.upgrades(), row.location(), details));
			}
			view = new TerminalView(view.kind(), view.generation(), view.hasNext(), displayed);
		}
		if (emit(player, bytes, now, acknowledged, TerminalLiveUpdate.Status.READY, view, query.hasPrevious()))
			selections.publishLive(authority, current, query.kind(), query.hasNext(), rows, player.serverLevel().getGameTime());
		return now + 10;
	}
	private boolean emit(ServerPlayer player, TerminalSyncBudget budget, long now, long acknowledged,
			TerminalLiveUpdate.Status status, TerminalView view, boolean previous) {
		boolean unchanged = status == sentStatus && java.util.Objects.equals(view, sentView);
		if (unchanged && sentAcknowledged == acknowledged && sentAt != Long.MIN_VALUE && now - sentAt < 40) return false;
		var update = new TerminalLiveUpdate(menu.containerId, session, sequence, acknowledged,
				Math.incrementExact(revision), status, previous, unchanged ? null : view, quantityOrder == null ? -1 : Math.max(0, now - quantityOrder.sampledAt()));
		if (!budget.acquire(now, update.encodedBytes())) return false;
		PacketDistributor.sendToPlayer(player, update);
		revision = update.revision(); sentAcknowledged = acknowledged; sentAt = now; sentStatus = status; sentView = view; return true;
	}
}
