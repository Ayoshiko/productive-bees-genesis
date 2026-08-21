package com.ayoshiko.productivebeesgenesis.mek.ae2;

/**
 * 全服级每 tick AE2 insert 时间预算 — 跨机器共享的第三层钳制。
 * <p>
 * Spark 报告（NeoForge 21.1.248 + EnderDrives 1.5.23）实证：EnderDrives 的 WAL 每次
 * {@code meStorage.insert} 都在主线程触发 {@code FileChannelImpl.force}（fsync 5-10ms），
 * 报告中 {@code pushBatchKey} 540ms 里 456ms 落在这条 fsync 链上。
 * <p>
 * <b>为什么需要全服预算</b>：单机预算（{@link Ae2OutputPusher}）和指数退避只能约束
 * 单台机器。所有蜂箱共享同一 ME 网络时，退避到期时刻天然对齐（同 tick 失败
 * → 同 tick 重试），N 台机器串行 fsync（100 台 × 5ms = 500ms 尖峰），对应报告
 * MSPT median 42ms 但 max 260ms 的「平均值健康、偶发尖峰」形态。
 * <p>
 * <b>只统计慢 insert 的超出耗时</b>（吞吐保护设计）：预算只累计单次耗时超过
 * {@link #SLOW_INSERT_NANOS} 的<b>超出部分</b>。健康网络单次 insert 50-100µs，
 * 累计恒为 0，预算永不触发，推送吞吐不受任何限制；病态网络单次 5-10ms，
 * 每次 insert 记入 4.5-9.5ms，第一次 insert 后预算即耗尽，后续 tick 顺延。
 * 若把所有 insert 耗时都计入预算，健康网络下 40 台机器 × 1 key 就会耗尽 2ms
 * 预算，严重伤害正常推送效率。
 * <p>
 * <b>语义</b>：以 gameTime 为 key 维护全服 insert 累计耗时；预算耗尽后同 tick 内
 * 所有机器的后续 insert 一律顺延（物品留原槽无损，下 tick 由轮转游标恢复，无饥饿）。
 * <p>
 * <b>线程模型</b>：仅服务端 tick 线程访问（与 {@link Ae2PushBackoff} 同一假设）。
 * 若未来出现跨线程调用，最坏影响为预算统计偏差（多推或少推几毫秒），不会崩溃或丢物品。
 */
final class Ae2GlobalInsertBudget {

	/**
	 * 单次 insert 慢阈值（纳秒）— 超过说明网络含昂贵外部存储（健康网络 insert 通常 &lt;100µs）。
	 * <p>
	 * 唯一事实来源：{@link Ae2OutputPusher} 与 {@link DirectItemPushSession} 的
	 * 慢 insert 检测、预算累计均引用此常量。
	 */
	static final long SLOW_INSERT_NANOS = 500_000L;

	/** 每 tick 全服「慢 insert 超出耗时」总预算（纳秒）— 4 次 5.5ms 慢 insert 即耗尽 */
	private static final long BUDGET_NANOS = 2_000_000L;

	/** 预算所属游戏刻 — 变化时自动重置累计值 */
	private static long budgetTick = Long.MIN_VALUE;

	/** 当前游戏刻已累计的慢 insert 超出耗时 */
	private static long spentNanos;

	private Ae2GlobalInsertBudget() {}

	/**
	 * 本 tick 全服预算是否已耗尽。
	 * <br/>
	 * gameTick 变化时先重置再判断，与 {@link #recordCost} 顺序无关。
	 * 健康网络（全部 insert 快于慢阈值）恒返回 false。
	 *
	 * @param gameTick 当前游戏刻（level.getGameTime()）
	 * @return true 表示预算已耗尽，应跳过本次 insert（物品留原槽）
	 */
	static boolean isExhausted(long gameTick) {
		refreshTick(gameTick);
		return spentNanos >= BUDGET_NANOS;
	}

	/** Whether one storage operation is slow enough to require budget and backoff handling. */
	static boolean isSlowOperation(long costNanos) {
		return costNanos > SLOW_INSERT_NANOS;
	}

	/**
	 * 将一次 insert 的耗时计入全服预算 — 仅累计超出慢阈值的部分。
	 *
	 * @param gameTick   当前游戏刻（level.getGameTime()）
	 * @param costNanos  本次 insert 实际耗时（System.nanoTime 差值）
	 */
	static void recordCost(long gameTick, long costNanos) {
		if (!isSlowOperation(costNanos)) return;
		refreshTick(gameTick);
		spentNanos += costNanos - SLOW_INSERT_NANOS;
	}

	/** gameTick 推进时重置累计值（同 tick 重复调用幂等） */
	private static void refreshTick(long gameTick) {
		if (gameTick != budgetTick) {
			budgetTick = gameTick;
			spentNanos = 0L;
		}
	}
}
