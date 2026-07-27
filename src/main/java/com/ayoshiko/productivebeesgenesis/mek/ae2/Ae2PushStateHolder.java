package com.ayoshiko.productivebeesgenesis.mek.ae2;

/**
 * AE2 推送退避和计数器状态持有者（per-tile 独立）
 * <br/>
 * 封装流体/物品推送的退避状态（{@link Ae2PushBackoff}）和独立计数器，
 * 替代 {@code level.getGameTime()} 作为节流依据以兼容 JDTE 加速。
 * <p>
 * <b>线程安全</b>：volatile long 字段，服务端 tick 线程独占调用，无需 CAS。
 * 与 {@code Ae2OutputStateHolder.pullCallCounter} 风格保持一致。
 * <p>
 * <b>行数控制</b>：从 {@link Ae2OutputStateHolder} 抽取以保证主类 ≤ 500 行。
 *
 * @since 1.0.0
 */
public final class Ae2PushStateHolder {

	// ===== 推送退避状态（per-tile 独立） =====
	/** 流体推送退避状态 */
	private final Ae2PushBackoff fluidBackoff = new Ae2PushBackoff();
	/** 物品推送退避状态（仅用于 Ae2OutputPusher 输出失败） */
	private final Ae2PushBackoff itemBackoff = new Ae2PushBackoff();
	/** 输入回送退避状态（Task 10：仅用于 Ae2InputPuller 回送失败） */
	private final Ae2PushBackoff returnBackoff = new Ae2PushBackoff();

	// ===== 推送调用计数器（JDTE 兼容，替代 getGameTime） =====
	/** 流体推送调用计数器 */
	private volatile long fluidPushCallCounter = 0L;
	/** 上次流体推送的 counter（批量短路用） */
	private volatile long lastFluidPushCounter = 0L;
	/** 物品推送调用计数器（独立于流体） */
	private volatile long itemPushCallCounter = 0L;
	/** 上次物品推送的 counter（批量短路用） */
	private volatile long lastItemPushCounter = 0L;

	/** 获取流体推送退避状态 */
	public Ae2PushBackoff getFluidBackoff() { return fluidBackoff; }

	/** 获取物品推送退避状态 */
	public Ae2PushBackoff getItemBackoff() { return itemBackoff; }

	/** 获取输入回送退避状态（Task 10） */
	public Ae2PushBackoff getReturnBackoff() { return returnBackoff; }

	/** 递增流体推送计数器并返回新值（JDTE 兼容节流依据） */
	public long incrementFluidPushCallCounter() { return ++fluidPushCallCounter; }

	/** 递增物品推送计数器并返回新值（独立于流体） */
	public long incrementItemPushCallCounter() { return ++itemPushCallCounter; }

	/** 获取流体推送计数器当前值 */
	public long getFluidPushCallCounter() { return fluidPushCallCounter; }

	/** 获取物品推送计数器当前值 */
	public long getItemPushCallCounter() { return itemPushCallCounter; }

	/** 获取上次流体推送的计数器值（批量短路用） */
	public long getLastFluidPushCounter() { return lastFluidPushCounter; }

	/** 获取上次物品推送的计数器值（批量短路用） */
	public long getLastItemPushCounter() { return lastItemPushCounter; }

	/** 更新上次流体推送的计数器值 */
	public void updateLastFluidPushCounter(long value) { lastFluidPushCounter = value; }

	/** 更新上次物品推送的计数器值 */
	public void updateLastItemPushCounter(long value) { lastItemPushCounter = value; }

	/**
	 * 完全重置状态（方块销毁/重建时由 {@link Ae2OutputStateHolder#clear()} 调用）
	 * <br/>
	 * 重置所有退避实例和计数器，防止方块重建后残留旧状态。
	 */
	public void reset() {
		fluidBackoff.reset();
		itemBackoff.reset();
		returnBackoff.reset();
		fluidPushCallCounter = 0L;
		lastFluidPushCounter = 0L;
		itemPushCallCounter = 0L;
		lastItemPushCounter = 0L;
	}
}
