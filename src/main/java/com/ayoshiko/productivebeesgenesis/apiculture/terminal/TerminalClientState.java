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

	public TerminalClientState(int container, UUID session) { this.container = container; this.session = session; }
	public boolean ready(long now) { return !closed && pending == 0 && now >= nextSendAt && sequence < Long.MAX_VALUE; }
	public TerminalView view() { return view; }
	public TerminalReply result() { return result; }
	/** 查询刷新不会抹去刚确认的交换结果；下一次资产请求开始即清除。 */
	public TerminalReply exchangeResult() { return exchangeResult; }
	public TerminalUpgradePreview preview() { return preview; }
	public Notice notice() { return notice; }
	public boolean waiting() { return pending != 0; }

	public TerminalRequest begin(TerminalRequest.Operation operation, int row, int target, int inventory, int amount, long now) {
		tick(now);
		if (!ready(now)) return null;
		boolean query = operation == TerminalRequest.Operation.MEMBERS || operation == TerminalRequest.Operation.PRODUCTS || operation == TerminalRequest.Operation.UPGRADES;
		boolean cancel = operation == TerminalRequest.Operation.CANCEL;
		boolean previewQuery = TerminalRequest.upgradePreview(operation);
		if (!query && !cancel && (view == null || operation == TerminalRequest.Operation.NEXT && !view.hasNext()
				|| operation != TerminalRequest.Operation.NEXT && (row < 0 || row >= view.rows().size()))) return null;
		long generation = query || cancel ? 0 : view.generation();
		var request = new TerminalRequest(container, session, sequence + 1, operation, generation, row, target, inventory, amount);
		pendingExchange = !query && !cancel && !previewQuery && operation != TerminalRequest.Operation.NEXT;
		pendingPreview = previewQuery; preview = null;
		if (pendingExchange) exchangeResult = null;
		pending = ++sequence; sentAt = now; nextSendAt = now + SEND_INTERVAL_MILLIS;
		if (query) expiresAt = now + TIMEOUT_MILLIS;
		if (!previewQuery) view = null;
		result = null; notice = Notice.WAITING;
		return request;
	}
	public void accept(TerminalReply reply, long now) {
		if (closed || pending == 0 || reply.sequence() != pending || reply.containerId() != container || !reply.session().equals(session)) return;
		if (pendingExchange) exchangeResult = reply;
		if (pendingPreview && reply.preview() != null) preview = reply.preview(); else view = reply.view();
		pendingExchange = false; pendingPreview = false;
		pending = 0; result = reply; notice = Notice.REPLY;
		tick(now);
	}
	public void tick(long now) {
		if (closed) return;
		if (pending != 0 && now - sentAt >= TIMEOUT_MILLIS) { pending = 0; view = null; notice = Notice.TIMEOUT; }
		if (view != null && now >= expiresAt) { view = null; notice = Notice.EXPIRED; }
	}
	public void close() { closed = true; view = null; result = null; exchangeResult = null; preview = null; pending = 0; pendingExchange = false; pendingPreview = false; }
}
