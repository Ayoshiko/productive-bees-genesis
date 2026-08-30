package com.ayoshiko.productivebeesgenesis.mek.ae2;

/**
 * AE2 insert 成本自适应记账器（per-tile 实例 + 全服静态总额）。
 * <p>
 * <b>为什么需要它</b>：{@link Ae2GlobalInsertBudget} 只对<b>单次超过 0.5ms</b> 的 insert 计费
 * （EnderDrives WAL fsync 5-10ms 这类「少次极慢」外部存储）。Spark 报告
 * （spark.lucko.me/7lodoyye3L，本模组 1.0.5）实证另一种病态形态完全逃过该阈值：
 * ae2lt 的 {@code MatrixPortBlockEntity$PortPatternItemHandler.insertItem} →
 * {@code MatrixPatternStorageBlockEntity.isValidPatternStack} → {@code PatternDetailsHelper.decodePattern}
 * → {@code AEItemKey.of} + {@code ItemStack.copy} + 组件 hashCode，单次约 0.2-0.4ms
 * （低于 SLOW_INSERT_NANOS，三层钳制全部不触发），但被高频调用后
 * {@code Ae2OutputPusher.pushBatchKey} 524ms、{@code DirectItemPushSession.applyAsInt} 620ms，
 * 单台离心机工厂 tick 占 3.19%。即「中等昂贵 + 极高频」外部存储的漏网场景。
 * <p>
 * <b>原理</b>：对实测 insert 耗时做指数滑动平均（EWMA），再用「时间预算 ÷ 平均单次成本」
 * 反推本 tick 允许提交的<b>不同物品键数量</b>：
 * <ul>
 *   <li>健康网络（≤ {@link #HEALTHY_INSERT_NANOS}）：直接返回调用方上限，配额与优化前<b>完全一致</b>，吞吐零损失</li>
 *   <li>ae2lt 型（约 300µs）：1.5ms ÷ 300µs ≈ 5 键/tick，单机 tick 成本从最坏 9.6ms 降到约 1.5ms</li>
 *   <li>EnderDrives 型（5-10ms）：配额 1，再由 {@link Ae2GlobalInsertBudget} 与指数退避顺延</li>
 * </ul>
 * <b>为什么这样不伤吞吐</b>：配额限制的是「不同键的数量」而非物品数量——单次 insert 的 count
 * 不受限（合并路径把同键各槽数量累加后一次提交），产物<b>件数</b>吞吐基本不变，
 * 只是把「同 tick 塞进 32 种不同产物」摊到几个 tick；未提交的物品留在原槽，
 * 由既有轮转游标下 tick 恢复，无饥饿、无丢失。健康网络下本类只做几次整数运算，无任何限制。
 * <p>
 * <b>作用域</b>：EWMA 与单机预算是实例字段（每台机器独立），因为不同机器可能接在不同 ME 网络上，
 * 一个网络病态不应拖慢另一网络上的机器；总额预算是静态字段（全服共享），
 * 兜住「N 台机器 × 中等成本」的合计尖峰。两个硬预算都取得足够宽松，
 * 健康网络（32 键 × 100µs ≈ 3.2ms）永远不会触发。
 * <p>
 * <b>线程模型</b>：仅服务端 tick 线程访问（与 {@link Ae2PushBackoff}/{@link Ae2GlobalInsertBudget} 同一假设）。
 * 最坏竞态只让统计偏差几毫秒，不会崩溃或丢物品。
 */
final class Ae2InsertCostTracker {

	/**
	 * 健康 insert 上界（纳秒）— 平均成本不超过此值时完全不限制配额。
	 * <p>
	 * 依据：Spark 与源码注释实证健康 ME 网络单次 insert 50-100µs；取 150µs 留出抖动余量，
	 * 保证「不影响机器处理效率与产物推送效率」这一硬约束。
	 */
	private static final long HEALTHY_INSERT_NANOS = 150_000L;

	/** 昂贵网络下每机器每 tick 的 insert 时间配额（纳秒）— 配额换算的分子 */
	private static final long QUOTA_BUDGET_NANOS = 1_500_000L;

	/** 单机每 tick insert 总耗时硬上限（纳秒）— 灾难兜底，健康网络不会触及 */
	private static final long TILE_TICK_BUDGET_NANOS = 6_000_000L;

	/** 全服每 tick insert 总耗时硬上限（纳秒）— 多机同 tick 集中提交的兜底 */
	private static final long GLOBAL_TICK_BUDGET_NANOS = 12_000_000L;

	/** EWMA 平滑位移：new = old + (sample - old) >> 3，约 8 次采样收敛，网络变化后能快速跟上 */
	private static final int EWMA_SHIFT = 3;

	/** 全服预算所属游戏刻 */
	private static long globalTick = Long.MIN_VALUE;

	/** 全服本 tick 已消耗的 insert 总耗时 */
	private static long globalSpentNanos;

	/** 本机 insert 平均耗时（纳秒）— 0 表示尚无样本，此时不限制配额 */
	private long averageNanos;

	/** 本机预算所属游戏刻 */
	private long tileTick = Long.MIN_VALUE;

	/** 本机本 tick 已消耗的 insert 总耗时 */
	private long tileSpentNanos;

	/** 本机本 tick 已发起的 insert 次数 — 仅在昂贵网络下用于限流 */
	private int tileInsertsThisTick;

	/**
	 * 记录一次真实 insert 耗时：更新 EWMA 并累计单机/全服本 tick 总耗时。
	 *
	 * @param gameTick  当前游戏刻（level.getGameTime()）
	 * @param costNanos 本次 insert 实测耗时（System.nanoTime 差值）
	 */
	void record(long gameTick, long costNanos) {
		if (costNanos < 0L) return;
		averageNanos = averageNanos <= 0L
				? costNanos
				: averageNanos + ((costNanos - averageNanos) >> EWMA_SHIFT);
		refreshTile(gameTick);
		tileSpentNanos += costNanos;
		tileInsertsThisTick++;
		refreshGlobal(gameTick);
		globalSpentNanos += costNanos;
	}

	/**
	 * 逐次直推路径的限流闸门（{@code DirectItemPushSession} 每个物品一次调用）。
	 * <p>
	 * 直推会话每个物品都会被 {@code prepareDirectItemPush} 重置，会话内计数无法跨物品累计，
	 * 因此把「本 tick 已发起次数」放在 per-tile 记账器里判定。
	 * <b>健康网络恒返回 true</b>（零限流，产物直推效率不变）；
	 * 仅当 EWMA 判定网络昂贵时，才把本 tick insert 次数限制到 {@link #keyQuota(int)}。
	 *
	 * @param gameTick    当前游戏刻
	 * @param maxInserts  健康网络下的名义上限
	 * @return true 表示允许本次 insert
	 */
	boolean canInsertNow(long gameTick, int maxInserts) {
		if (averageNanos <= HEALTHY_INSERT_NANOS) return true;
		refreshTile(gameTick);
		return tileInsertsThisTick < keyQuota(maxInserts);
	}

	/**
	 * 本 tick 是否已用完单机或全服 insert 时间硬预算。
	 * <p>
	 * <b>健康网络恒返回 false</b>：均值不超过 {@link #HEALTHY_INSERT_NANOS} 时完全不介入，
	 * 一台机器同 tick 可能发起「输出槽 32 键 + 生成物直推 32 次 + 缓冲直推」共近百次 insert，
	 * 健康网络下合计仍只有几毫秒，若用硬预算去卡就会误伤正常推送吞吐。
	 * 只有 EWMA 判定网络昂贵后，硬预算才作为灾难兜底生效。
	 * <br/>
	 * 耗尽后调用方停止本轮提交（物品留原槽，下 tick 由轮转游标恢复，无饥饿、无丢失）。
	 */
	boolean isExhausted(long gameTick) {
		if (averageNanos <= HEALTHY_INSERT_NANOS) return false;
		refreshTile(gameTick);
		if (tileSpentNanos >= TILE_TICK_BUDGET_NANOS) return true;
		refreshGlobal(gameTick);
		return globalSpentNanos >= GLOBAL_TICK_BUDGET_NANOS;
	}

	/**
	 * 按实测平均成本换算本 tick 允许提交的不同物品键数量。
	 *
	 * @param maxKeys 调用方上限；健康网络或无样本时原样返回（行为与优化前一致）
	 * @return [1, maxKeys] 区间内的配额
	 */
	int keyQuota(int maxKeys) {
		if (maxKeys <= 1) return Math.max(1, maxKeys);
		// 无样本或健康网络：不限制，保持满速推送
		if (averageNanos <= HEALTHY_INSERT_NANOS) return maxKeys;
		long quota = QUOTA_BUDGET_NANOS / averageNanos;
		if (quota >= maxKeys) return maxKeys;
		return quota <= 1L ? 1 : (int) quota;
	}

	/** 当前是否判定所在 ME 网络为昂贵网络（诊断日志用） */
	boolean isExpensiveNetwork() {
		return averageNanos > HEALTHY_INSERT_NANOS;
	}

	/** 当前平均单次 insert 耗时（纳秒）— 诊断日志用，0 表示无样本 */
	long averageCostNanos() {
		return averageNanos;
	}

	/** 方块销毁/重建时清空统计，避免旧网络的成本估计影响新网络 */
	void reset() {
		averageNanos = 0L;
		tileTick = Long.MIN_VALUE;
		tileSpentNanos = 0L;
		tileInsertsThisTick = 0;
	}

	/** 游戏刻推进时重置单机累计值（同 tick 重复调用幂等） */
	private void refreshTile(long gameTick) {
		if (gameTick != tileTick) {
			tileTick = gameTick;
			tileSpentNanos = 0L;
			tileInsertsThisTick = 0;
		}
	}

	/** 游戏刻推进时重置全服累计值（同 tick 重复调用幂等） */
	private static void refreshGlobal(long gameTick) {
		if (gameTick != globalTick) {
			globalTick = gameTick;
			globalSpentNanos = 0L;
		}
	}
}
