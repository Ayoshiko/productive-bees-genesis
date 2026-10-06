package com.ayoshiko.productivebeesgenesis.apiculture.core;

import com.ayoshiko.productivebeesgenesis.apiculture.terminal.*;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;

/** 常驻产物区的独立选择令牌；调度、权限和实际转移仍归原菜单。 */
final class CoreProductWorkspace {
	private final NetworkCoreMenu menu;
	private final UUID id;
	private final NetworkSelectionSession selections;
	private final TerminalSequence sequence = new TerminalSequence();
	private final TerminalClientState client;
	private CoreTerminalSubscription subscription;
	CoreProductWorkspace(NetworkCoreMenu menu) {
		this.menu = menu;
		var primary = menu.terminalSession();
		id = new UUID(primary.getMostSignificantBits() ^ 0x50424e50524f4453L, primary.getLeastSignificantBits());
		selections = new NetworkSelectionSession(id); client = new TerminalClientState(menu.containerId, id);
	}
	boolean matches(UUID session) { return id.equals(session); }
	TerminalClientState client() { return client; }
	boolean active() { return subscription != null; }
	TerminalReply search(ServerPlayer player, TerminalSearchRequest request) {
		if (!menu.workspaceAvailable(player) || request.containerId() != menu.containerId || !matches(request.session()) || !sequence.begin(request.sequence())) return null;
		try {
			if (!menu.chargeTerminalRequest(player)) return null;
			if (request.kind() != NetworkSelectionSession.Kind.PRODUCTS) return reply(request.sequence(), TerminalReply.Status.INVALID, 0);
			if (subscription == null) subscription = new CoreTerminalSubscription(menu, selections, id);
			boolean accepted = subscription.request(request);
			if (accepted) { selections.cancel(); menu.wakeProducts(player); }
			return reply(request.sequence(), accepted ? TerminalReply.Status.OK : TerminalReply.Status.STALE, 0);
		} finally { sequence.finish(); }
	}
	TerminalReply request(ServerPlayer player, TerminalRequest request) {
		if (!menu.workspaceAvailable(player) || request.containerId() != menu.containerId || !matches(request.session()) || !sequence.begin(request.sequence())) return null;
		try {
			if (!menu.chargeTerminalRequest(player)) return null;
			if (request.operation() == TerminalRequest.Operation.CANCEL) { cancel(); return reply(request.sequence(), TerminalReply.Status.OK, 0); }
			if (request.operation() != TerminalRequest.Operation.TAKE_PRODUCT || request.targetSlot() != -1 || request.inventorySlot() != -1)
				return reply(request.sequence(), TerminalReply.Status.INVALID, 0);
			var core = menu.exchangeCore(player); var authority = core.ownership().readyAuthority();
			if (authority == null) return reply(request.sequence(), TerminalReply.Status.UNAVAILABLE, 0);
			var current = authority.checkpoint();
			var row = selections.resolve(authority, current, request.generation(), request.row(), player.serverLevel().getGameTime());
			if (!(row instanceof NetworkSelectionSession.ProductRow product)) return reply(request.sequence(), TerminalReply.Status.STALE, 0);
			try {
				var result = menu.withdrawProduct(player, product.key(), current.ledger().revision(), -1, request.amount(), false);
				return reply(request.sequence(), TerminalReply.Status.valueOf(result.status().name()), result.moved());
			} finally { selections.cancel(); menu.wakeProducts(player); }
		} finally { sequence.finish(); }
	}
	long step(ServerPlayer player, TerminalSyncBudget bytes, long now) {
		return subscription == null ? Long.MAX_VALUE : subscription.step(player, bytes, now, sequence.last());
	}
	void cancel() { if (subscription != null) subscription.close(); subscription = null; selections.cancel(); }
	void close() { cancel(); sequence.close(); selections.close(); client.close(); }
	private TerminalReply reply(long number, TerminalReply.Status status, int moved) { return new TerminalReply(menu.containerId, id, number, status, moved, 0, null); }
}
