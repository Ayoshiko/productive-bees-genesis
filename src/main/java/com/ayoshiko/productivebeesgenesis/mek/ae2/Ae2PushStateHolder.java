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
	/**
	 * 流体推送退避状态 — 256× 加速下 30s 长退避会让离心机长时间停机（流体槽满→处理暂停）。
	 * 改用 50ms→100ms→200ms→400ms→800ms→1s，网络恢复后能快速重新推送。
	 */
	private final Ae2PushBackoff fluidBackoff = new Ae2PushBackoff(50_000_000L, 1_000_000_000L);
	/**
	 * 物品推送退避状态使用同一短窗口，避免一个失败 key 长时间拖住并行工厂。
	 */
	private final Ae2PushBackoff itemBackoff = new Ae2PushBackoff(50_000_000L, 1_000_000_000L);
	/** 输入回送退避状态（Task 10：仅用于 Ae2InputPuller 回送失败） */
	private final Ae2PushBackoff returnBackoff = new Ae2PushBackoff();

	/** Per-AEItemKey input-pull failure backoff registry; Object keeps AE2 types out of this class. */
	private volatile Object inputKeyBackoffRegistry;

	/** AE2 input fairness scheduler; Object keeps AE2 types out of this class. */
	private volatile Object inputFairnessScheduler;

	// ===== 推送调用计数器（JDTE 兼容，替代 getGameTime） =====
	/** 流体推送调用计数器 */
	private volatile long fluidPushCallCounter = 0L;
	/** 上次流体推送的 counter（批量短路用） */
	private volatile long lastFluidPushCounter = 0L;
	/** 物品推送调用计数器（独立于流体） */
	private volatile long itemPushCallCounter = 0L;
	/** 上次物品推送的 counter（批量短路用） */
	private volatile long lastItemPushCounter = 0L;
	/** 输入槽轮转起点，避免每次都从最左侧输入槽开始分配。 */
	private volatile int inputSlotRotationIndex = 0;
	/** 最近执行物品/流体推送的真实游戏刻，用于合并同刻加速器重复调用。 */
	private volatile long lastItemPushGameTick = Long.MIN_VALUE;
	private volatile long lastFluidPushGameTick = Long.MIN_VALUE;

	// ===== Grid Node 状态缓存（模块2.1：避免每 tick 高频调用 getGridNodeState） =====
	/** 缓存刷新间隔（纳秒）— 20 tick ≈ 1000ms = 1_000_000_000ns，使用 wall clock 避免 JDTE 加速下 getGameTime 不变 */
	private static final long NODE_STATE_REFRESH_INTERVAL_NS = 1_000_000_000L;
	/** 缓存的 grid node 状态（-1 表示未初始化，需重新查询；否则为 Ae2GridNodeManager.getGridNodeState 返回值 0-3） */
	private volatile int cachedNodeState = -1;
	/** 上次刷新缓存的时间戳（nanoTime），0L 表示从未刷新 */
	private volatile long nodeStateRefreshAt = 0L;

	/** 获取流体推送退避状态 */
	public Ae2PushBackoff getFluidBackoff() { return fluidBackoff; }

	/** 获取物品推送退避状态 */
	public Ae2PushBackoff getItemBackoff() { return itemBackoff; }

	/** 获取输入回送退避状态（Task 10） */
	public Ae2PushBackoff getReturnBackoff() { return returnBackoff; }

	public Object getInputKeyBackoffRegistry() { return inputKeyBackoffRegistry; }
	public void setInputKeyBackoffRegistry(Object registry) { this.inputKeyBackoffRegistry = registry; }

	public Object getInputFairnessScheduler() { return inputFairnessScheduler; }
	public void setInputFairnessScheduler(Object scheduler) { this.inputFairnessScheduler = scheduler; }

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
	 * 返回本轮输入分配起点，并将下一轮起点向后移动一个槽位。
	 */
	public int getAndAdvanceInputSlotRotation(int slotCount) {
		if (slotCount <= 0) return 0;
		int current = Math.floorMod(inputSlotRotationIndex, slotCount);
		inputSlotRotationIndex = (current + 1) % slotCount;
		return current;
	}

	public boolean tryStartItemPush(long gameTick) {
		if (lastItemPushGameTick == gameTick) return false;
		lastItemPushGameTick = gameTick;
		return true;
	}

	public boolean tryStartFluidPush(long gameTick) {
		if (lastFluidPushGameTick == gameTick) return false;
		lastFluidPushGameTick = gameTick;
		return true;
	}

	// ===== Grid Node 状态缓存方法（模块2.1） =====

	/**
	 * 获取 grid node 状态（带缓存）
	 * <br/>
	 * 每 20 tick（约 1 秒）刷新一次，避免 256× 加速场景下每 tick 高频调用
	 * {@link Ae2GridNodeManager#getGridNodeState}（涉及 isPowered/hasGridBooted/meetsChannelRequirements 等查询）。
	 * <p>
	 * 缓存由 {@link #invalidateNodeStateCache()} 在 grid 变化时失效，
	 * 由 {@link Ae2OutputStateHolder#onGridChanged()} 触发。
	 * <p>
	 * <b>线程安全</b>：volatile 字段保证可见性，check-then-update 最多导致重复查询一次（无正确性问题），
	 * 服务端 tick 线程独占调用路径下无并发。
	 *
	 * @param host 输出宿主（用于查询 grid node 状态）
	 * @return grid node 状态 ordinal（0-3），对应 Ae2GridNodeManager.STATE_OFFLINE/NETWORK_BOOTING/MISSING_CHANNEL/ONLINE
	 */
	public int getCachedNodeState(IAe2OutputHostBase host) {
		long now = System.nanoTime();
		if (cachedNodeState < 0 || (now - nodeStateRefreshAt) >= NODE_STATE_REFRESH_INTERVAL_NS) {
			cachedNodeState = Ae2GridNodeManager.getGridNodeState(host);
			nodeStateRefreshAt = now;
		}
		return cachedNodeState;
	}

	/**
	 * 失效 grid node 状态缓存
	 * <br/>
	 * 由 {@link Ae2OutputStateHolder#onGridChanged()} 在 grid 变化时调用，
	 * 确保下次 {@link #getCachedNodeState} 重新查询 AE2 API。
	 */
	public void invalidateNodeStateCache() {
		cachedNodeState = -1;
	}

	/**
	 * 完全重置状态（方块销毁/重建时由 {@link Ae2OutputStateHolder#clear()} 调用）
	 * <br/>
	 * 重置所有退避实例和计数器，防止方块重建后残留旧状态。
	 */
	public void reset() {
		fluidBackoff.reset();
		itemBackoff.reset();
		returnBackoff.reset();
		inputKeyBackoffRegistry = null;
		inputFairnessScheduler = null;
		fluidPushCallCounter = 0L;
		lastFluidPushCounter = 0L;
		itemPushCallCounter = 0L;
		lastItemPushCounter = 0L;
		inputSlotRotationIndex = 0;
		lastItemPushGameTick = Long.MIN_VALUE;
		lastFluidPushGameTick = Long.MIN_VALUE;
		// 模块2.1：重置 grid node 状态缓存，方块重建后从初始状态重新查询
		cachedNodeState = -1;
		nodeStateRefreshAt = 0L;
	}
}
