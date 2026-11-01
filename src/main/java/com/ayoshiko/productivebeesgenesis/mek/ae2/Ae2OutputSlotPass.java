package com.ayoshiko.productivebeesgenesis.mek.ae2;

import com.ayoshiko.productivebeesgenesis.util.SaturatingMath;

import java.util.List;

/**
 * 逐槽提交路径 — 槽位数少于合并阈值时逐个 insert，避免建 Map 的额外开销
 * <p>
 * 从 {@code Ae2OutputPusher.pushOutputs} 拆出（原文件 1102 行，超 500 行阈值）。
 * 本类只负责「逐槽这一条策略」：预算/配额判定、离心机优先 hold、退避记账，
 * 实际 ME 提交委托 {@link Ae2OutputCommitter#tryPushSlotDirect}。
 */
final class Ae2OutputSlotPass {

	private Ae2OutputSlotPass() {
	}

	/**
	 * 执行逐槽推送。
	 *
	 * @param entries 已收集且已剔除账本占用的槽位条目
	 */
	static void run(Ae2OutputPushContext ctx, List<Ae2SlotEntry> entries) {
		IAe2OutputHostBase host = ctx.host();
		long gameTick = ctx.gameTick();
		long nowNanos = ctx.nowNanos();
		int pushedItems = 0;
		int attemptedEntries = 0;
		Ae2SlotEntry firstAttemptedEntry = null;
		// Spark 优化：insert 耗时预算 — 病态网络单次遍历 5-10ms，预算耗尽即停止本轮，
		// 剩余槽位留原槽由下 tick outputSlotScanCursor 轮转重扫，物品无损
		long spentInsertNanos = 0L;
		long successfulDelayNanos = 0;
		int heldEntries = 0;
		Ae2InsertCostTracker costTracker = ctx.buffers().insertCostTracker;
		costTracker.useNetwork(ctx.meStorage());
		for (Ae2SlotEntry entry : entries) {
			// 离心机优先：hold 物品（蜂箱蜜脾）跳过 AE 推送，保留给离心机；
			// 判定经 processability 跨 tick 缓存加速（拓扑/配方变化时失效）
			if (host.productivebeesgenesis$shouldHoldForCentrifuge(entry.stack)) {
				heldEntries++;
				continue;
			}
			// 本机预算负责限制单次推送；跨机器协调由 per-network 令牌负责，
			// 不再使用旧全服硬闸门，以免一个病态网络阻塞其它健康网络。
			if (spentInsertNanos >= Ae2PushLimits.INSERT_TIME_BUDGET_NANOS
					|| costTracker.isExhausted(gameTick)) break;
			if (ctx.keyBackoff().shouldSkip(entry.key, nowNanos)) continue;
			if (firstAttemptedEntry == null) firstAttemptedEntry = entry;
			attemptedEntries++;
			var measured = ctx.buffers().measuredStorage.prepare(ctx.meStorage(), gameTick, costTracker);
			int pushed = Ae2OutputCommitter.tryPushSlotDirect(entry, measured,
					Ae2PushLimits.ActionSourceHolder.INSTANCE, ctx.holder().getOutputLedger(), com.ayoshiko.productivebeesgenesis.logistics.OutputCustody.of(ctx.host()));
			long insertCost = measured.lastCostNanos();
			boolean slowInsert = Ae2GlobalInsertBudget.isSlowOperation(insertCost);
			if (slowInsert) {
				// 只累计慢 insert 的超出耗时 — 健康网络预算恒 0，满速推送不受限
				spentInsertNanos += insertCost - Ae2GlobalInsertBudget.SLOW_INSERT_NANOS;
			}
			if (pushed > 0) {
				long delay = costTracker.successDelayNanos();
				ctx.keyBackoff().recordSuccess(entry.key, System.nanoTime(), delay);
				successfulDelayNanos = Math.max(successfulDelayNanos, delay);
			} else {
				ctx.keyBackoff().recordFailure(entry.key, System.nanoTime());
			}
			pushedItems = SaturatingMath.saturatingToInt(SaturatingMath.saturatingAdd(pushedItems, pushed));
		}
		if (pushedItems > 0) {
			host.productivebeesgenesis$onAe2PushComplete(pushedItems);
			host.productivebeesgenesis$markAe2StateChanged();
		}
		// 成功的成本延迟与拒收指数分开，避免一次抖动长期阻塞整机。
		Ae2PushBackoff itemBackoff = ctx.itemBackoff();
		if (pushedItems > 0) {
			itemBackoff.recordSuccessWithDelay(System.nanoTime(), successfulDelayNanos);
			if (successfulDelayNanos > 0) Ae2OutputBackoffLog.logSlowInsertDelay(successfulDelayNanos);
		} else if (attemptedEntries > 0) {
			// 完全失败 — 记录退避 + 诊断（取首个 key 作为代表）
			Ae2OutputBackoffLog.handleCompleteFailure(itemBackoff,
					firstAttemptedEntry.key, firstAttemptedEntry.count);
		} else if (heldEntries > 0) {
			// 全 hold 空转退避：输出槽全为蜜脾时避免每刻重复扫描+判定（加速场景 mspt）。
			// 槽位任何变化（直连转移/新产物/玩家操作）触发 onOutputSlotContentsChanged
			// → itemBackoff.reset()，非蜜脾物品零延迟恢复推送。
			itemBackoff.recordFailure(nowNanos);
		}
	}
}
