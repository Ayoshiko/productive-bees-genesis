package com.ayoshiko.productivebeesgenesis.apiculture.terminal;

import java.util.UUID;

/** 菜单所有的客户端请求状态；缩放重建界面不重置序号，不重试资产命令。 */
public final class TerminalClientState {
	public enum Notice { IDLE, WAITING, REPLY, TIMEOUT, EXPIRED }
	public static final long TIMEOUT_MILLIS = 5000, SEND_INTERVAL_MILLIS = 150;
	private final int container;
	private final UUID session;
	private long sequence, pending, sentAt, nextSendAt, expiresAt;
	private TerminalView view;
	private TerminalReply result, exchangeResult;
	private boolean pendingExchange;
	private boolean pendingPreview;
	private TerminalUpgradePreview preview;
	private Notice notice = Notice.IDLE;
	private boolean closed;
	private boolean live, liveReady, hasPrevious, liveHasView;
	private NetworkSelectionSession.Kind liveKind;
	private long liveQuery, liveRevision, liveAcknowledged;
	private long sortAgeTicks = -1, sortReceivedAt;
	public long sortAgeSeconds(long now) { return sortAgeTicks < 0 ? -1 : sortAgeTicks / 20 + Math.max(0, now - sortReceivedAt) / 1000; }

	public TerminalClientState(int container, UUID session) { this.container = container; this.session = session; }
	public boolean ready(long now) { return !closed && pending == 0 && now >= nextSendAt && sequence < Long.MAX_VALUE; }
	public TerminalView view() { return view; }
	public TerminalReply result() { return result; }
	/** 查询刷新不会抹去刚确认的交换结果；下一次资产请求开始即清除。 */
	public TerminalReply exchangeResult() { return exchangeResult; }
	public TerminalUpgradePreview preview() { return preview; }
	public Notice notice() { return notice; }
	public boolean waiting() { return pending != 0; }
	public boolean live() { return live; }
	public boolean hasPrevious() { return hasPrevious; }
	public boolean actionable(long now) { return ready(now) && (!live || liveReady && now < expiresAt); }
	/** 折叠管理页时撤销订阅；独立合成命令不再等待已取消的列表续租。 */
	public TerminalRequest suspend(long now) {
		var request = begin(TerminalRequest.Operation.CANCEL, -1, -1, -1, 0, now);
		if (request != null) { live = false; liveReady = false; view = null; preview = null; }
		return request;
	}
	public long acknowledgedSequence() { return liveAcknowledged; }
	public TerminalSearchRequest beginLive(NetworkSelectionSession.Kind kind, String query, TerminalSearchRequest.Navigation navigation, long now) {
		return beginLive(kind, query, navigation, TerminalSearchRequest.Sort.POSITION, now);
	}
	public TerminalSearchRequest beginLive(NetworkSelectionSession.Kind kind, String query, TerminalSearchRequest.Navigation navigation, TerminalSearchRequest.Sort sort, long now) {
		return beginLive(kind, query, navigation, sort, TerminalNameMatches.EMPTY, now);
	}
	public TerminalSearchRequest beginLive(NetworkSelectionSession.Kind kind, String query, TerminalSearchRequest.Navigation navigation,
			TerminalSearchRequest.Sort sort, TerminalNameMatches names, long now) {
		tick(now); if (!ready(now)) return null;
		var request = new TerminalSearchRequest(container, session, sequence + 1, kind, query, navigation, sort, names);
		if (view != null && view.kind() != kind) view = null;
		liveKind = kind; liveHasView = false; hasPrevious = false; sortAgeTicks = -1;
		live = true; liveReady = false; liveQuery = ++sequence; liveRevision = 0;
		pending = sequence; sentAt = now; nextSendAt = now + SEND_INTERVAL_MILLIS;
		pendingExchange = false; pendingPreview = false; preview = null; notice = Notice.WAITING;
		return request;
	}
	public void acceptLive(TerminalLiveUpdate update, long now) {
		if (closed || !live || update.containerId() != container || !session.equals(update.session()) || update.querySequence() != liveQuery
				|| update.revision() <= liveRevision || update.acknowledgedSequence() != sequence
				|| update.view() != null && update.view().kind() != liveKind) return;
		if (update.status() == TerminalLiveUpdate.Status.READY && update.view() == null && !liveHasView) return;
		liveRevision = update.revision(); liveAcknowledged = update.acknowledgedSequence();
		sortAgeTicks = update.sortAgeTicks(); sortReceivedAt = now;
		if (pending == liveQuery) pending = 0;
		liveReady = update.status() == TerminalLiveUpdate.Status.READY;
		if (liveReady) {
			if (update.view() != null) { view = update.view(); preview = null; liveHasView = true; }
			expiresAt = now + TIMEOUT_MILLIS; hasPrevious = update.hasPrevious(); notice = pending == 0 ? Notice.REPLY : Notice.WAITING;
		} else notice = update.status() == TerminalLiveUpdate.Status.SEARCHING ? Notice.WAITING : Notice.EXPIRED;
	}

	public TerminalRequest begin(TerminalRequest.Operation operation, int row, int target, int inventory, int amount, long now) {
		tick(now);
		if (!ready(now)) return null;
		boolean query = operation == TerminalRequest.Operation.MEMBERS || operation == TerminalRequest.Operation.PRODUCTS || operation == TerminalRequest.Operation.UPGRADES;
		boolean cancel = operation == TerminalRequest.Operation.CANCEL;
		boolean automatic = operation == TerminalRequest.Operation.AUTO_BEE_IN;
		if (live && (query || operation == TerminalRequest.Operation.NEXT)) return null;
		boolean previewQuery = TerminalRequest.upgradePreview(operation);
		if (live && !query && !cancel && !actionable(now)) return null;
		if (!query && !cancel && (view == null || operation == TerminalRequest.Operation.NEXT && !view.hasNext()
				|| !automatic && operation != TerminalRequest.Operation.NEXT && (row < 0 || row >= view.rows().size()))) return null;
		long generation = query || cancel ? 0 : view.generation();
		var request = new TerminalRequest(container, session, sequence + 1, operation, generation, row, target, inventory, amount);
		pendingExchange = !query && !cancel && !previewQuery && operation != TerminalRequest.Operation.NEXT;
		pendingPreview = previewQuery; preview = null;
		if (pendingExchange) exchangeResult = null;
		pending = ++sequence; sentAt = now; nextSendAt = now + SEND_INTERVAL_MILLIS;
		if (query) expiresAt = now + TIMEOUT_MILLIS;
		if (!previewQuery) { if (live) liveReady = false; else view = null; }
		result = null; notice = Notice.WAITING;
		return request;
	}
	public TerminalRequest beginCrafting(TerminalRequest.Operation operation, long generation, int row, int inventory, int amount, long now) {
		tick(now); if (!ready(now) || !TerminalRequest.crafting(operation)) return null;
		var request = new TerminalRequest(container, session, sequence + 1, operation, generation, row, -1, inventory, amount);
		liveReady = false; if (!live) view = null; preview = null; result = null;
		pendingExchange = operation != TerminalRequest.Operation.CRAFTING; pendingPreview = false;
		if (pendingExchange) exchangeResult = null;
		pending = ++sequence; sentAt = now; nextSendAt = now + SEND_INTERVAL_MILLIS; notice = Notice.WAITING;
		return request;
	}
	public void accept(TerminalReply reply, long now) {
		if (closed || pending == 0 || reply.sequence() != pending || reply.containerId() != container || !reply.session().equals(session)) return;
		if (pendingExchange) exchangeResult = reply;
		if (pendingPreview && reply.preview() != null) preview = reply.preview();
		else if (!live || reply.view() != null) view = reply.view();
		if (live && pendingExchange && liveAcknowledged < pending) liveReady = false;
		boolean rejectedQuery = live && pending == liveQuery && reply.status() != TerminalReply.Status.OK;
		if (rejectedQuery) { liveReady = false; liveHasView = false; }
		pendingExchange = false; pendingPreview = false;
		pending = 0; result = reply; notice = rejectedQuery ? Notice.EXPIRED : Notice.REPLY;
		tick(now);
	}
	public void tick(long now) {
		if (closed) return;
		if (pending != 0 && now - sentAt >= TIMEOUT_MILLIS) { pending = 0; liveReady = false; if (!live) view = null; notice = Notice.TIMEOUT; }
		if (view != null && now >= expiresAt && (!live || liveReady)) { liveReady = false; if (!live) view = null; notice = Notice.EXPIRED; }
	}
	public void close() { closed = true; view = null; result = null; exchangeResult = null; preview = null; pending = 0; pendingExchange = false; pendingPreview = false; liveReady = false; }
}
