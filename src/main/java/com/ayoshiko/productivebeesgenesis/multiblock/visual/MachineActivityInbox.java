package com.ayoshiko.productivebeesgenesis.multiblock.visual;

import java.util.Optional;

/** 客户端线程所有；只保留当前及一个后继摘要，无世界引用、资源模型或资产回调。 */
public final class MachineActivityInbox {
	public enum Receipt { BASELINE, STARTED, MERGED, SKIPPED, REJECTED }
	private MachineVisualSnapshot structure;
	private long revision, sequence, lastTick = -1, startedAt;
	private boolean baseline;
	private MachineActivityEvent current, pending;

	/** 结构失效时保留发布水位，旧身份不能重新接管收件槽。 */
	public void bind(MachineVisualSnapshot frame) {
		if (frame == null) { invalidate(); return; }
		if (frame.equals(structure)) return;
		if (frame.revision() <= revision) return;
		cancel(); structure = frame.variant() < 0 ? null : frame; revision = frame.revision(); sequence = 0; baseline = false;
	}
	/** 初始跟踪只记序号；后续重复基线不得清除正在播放的事件。 */
	public boolean baseline(MachineVisualSnapshot frame, long value) {
		if (frame == null || !frame.equals(structure) || baseline || value < 0) return false;
		sequence = value; baseline = true; return true;
	}
	public Receipt accept(MachineActivityEvent event, long gameTick, boolean mayAnimate) {
		if (!event.structure().equals(structure) || gameTick < 0) return Receipt.REJECTED;
		boolean continuous = advance(gameTick);
		if (!baseline) { sequence = event.sequence(); baseline = true; return Receipt.BASELINE; }
		if (event.sequence() <= sequence) return Receipt.REJECTED;
		sequence = event.sequence();
		// 跳时、视距或总预算拒绝仍消费序号；恢复后不补播被丢弃的历史。
		if (!continuous || !mayAnimate || !fresh(event, gameTick)) return Receipt.SKIPPED;
		if (current == null) { current = event; startedAt = event.gameTick(); return Receipt.STARTED; }
		// 最新摘要替换旧摘要，不累积数量，也不把不同活动拼成未发生的工序。
		pending = event; return Receipt.MERGED;
	}
	/** 每 tick 有界推进；长时间不可见或时间回退取消，不沿墙钟追赶。 */
	public boolean advance(long gameTick) {
		if (gameTick < 0 || (current != null && lastTick >= 0 && (gameTick < lastTick || gameTick - lastTick > 20))) {
			cancel(); lastTick = gameTick; return false;
		}
		lastTick = gameTick;
		if (current != null && gameTick - startedAt >= current.activity().duration()) {
			current = null;
			if (pending != null && fresh(pending, gameTick)) { current = pending; startedAt = gameTick; }
			pending = null;
		}
		return true;
	}
	public Optional<CombinedApiaryTimeline.Frame> sample(long gameTick, float partialTick) {
		advance(gameTick);
		if (current == null || !Float.isFinite(partialTick) || partialTick < 0 || partialTick > 1) return Optional.empty();
		// 先减 long，再转浮点；长存档时间不吞掉 partial tick 精度。
		return Optional.of(CombinedApiaryTimeline.sample(current, (gameTick - startedAt) + (double) partialTick));
	}
	private static boolean fresh(MachineActivityEvent event, long now) {
		return event.gameTick() <= now && now - event.gameTick() < event.activity().duration();
	}
	public boolean active() { return current != null; }
	public int retainedEvents() { return (current == null ? 0 : 1) + (pending == null ? 0 : 1); }
	/** 取消保留身份与序号基线，资源重载不能让同一完成事件重演。 */
	public void cancel() { current = null; pending = null; lastTick = -1; }
	public void invalidate() { cancel(); structure = null; baseline = false; }
}
