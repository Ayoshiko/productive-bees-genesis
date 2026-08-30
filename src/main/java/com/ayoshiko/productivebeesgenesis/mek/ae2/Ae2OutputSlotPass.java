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
		boolean slowInsertDetected = false;
		int heldEntries = 0;
		Ae2InsertCostTracker costTracker = ctx.buffers().insertCostTracker;
		for (Ae2SlotEntry entry : entries) {
			// 离心机优先：hold 物品（蜂箱蜜脾）跳过 AE 推送，保留给离心机；
			// 判定经 processability 跨 tick 缓存加速（拓扑/配方变化时失效）
			if (host.productivebeesgenesis$shouldHoldForCentrifuge(entry.stack)) {
				heldEntries++;
				continue;
			}
			// 全服预算：多台机器共享同一病态网络（EnderDrives fsync）时钳制同 tick insert 总量；
			// 预算判断前置 — 耗尽时 break 跳过后续所有 keyBackoff 查找
			if (spentInsertNanos >= Ae2PushLimits.INSERT_TIME_BUDGET_NANOS
					|| Ae2GlobalInsertBudget.isExhausted(gameTick)
					|| costTracker.isExhausted(gameTick)) break;
			if (ctx.keyBackoff().shouldSkip(entry.key, nowNanos)) continue;
			if (firstAttemptedEntry == null) firstAttemptedEntry = entry;
			attemptedEntries++;
			long insertStart = System.nanoTime();
			int pushed = Ae2OutputCommitter.tryPushSlotDirect(entry, ctx.meStorage(),
					Ae2PushLimits.ActionSourceHolder.INSTANCE, ctx.holder().getOutputLedger());
			long insertCost = System.nanoTime() - insertStart;
			boolean slowInsert = Ae2GlobalInsertBudget.isSlowOperation(insertCost);
			if (slowInsert) {
				// 只累计慢 insert 的超出耗时 — 健康网络预算恒 0，满速推送不受限
				spentInsertNanos += insertCost - Ae2GlobalInsertBudget.SLOW_INSERT_NANOS;
				slowInsertDetected = true;
			}
			Ae2GlobalInsertBudget.recordCost(gameTick, insertCost);
			// 自适应记账：全额计入 EWMA 与 tick 预算，覆盖「中等昂贵 + 极高频」外部存储
			costTracker.record(gameTick, insertCost);
			if (pushed > 0) {
				// Slot changes reset the tile-wide backoff. Preserve a key-local delay for
				// successful but pathological external-storage traversals.
				if (slowInsert) ctx.keyBackoff().recordFailure(entry.key, System.nanoTime());
				else ctx.keyBackoff().recordSuccess(entry.key);
			} else {
				ctx.keyBackoff().recordFailure(entry.key, slowInsert ? System.nanoTime() : nowNanos);
			}
			pushedItems = SaturatingMath.saturatingToInt(SaturatingMath.saturatingAdd(pushedItems, pushed));
		}
		if (pushedItems > 0) {
			host.productivebeesgenesis$onAe2PushComplete(pushedItems);
			host.productivebeesgenesis$markAe2StateChanged();
		}
		// 慢 insert 优先于成功复位判定：病态网络（含 ProjectExpansion 转换接口等昂贵
		// 外部存储）insert 仍会成功（返回>0），若先 recordSuccess 再 recordFailure，
		// 指数每轮被清零、窗口恒卡 50ms，稳态 = 每 50ms 一次 5-10ms 完整网络遍历
		// （Observable 实证单机 6ms/tick）。慢 insert 禁止复位，指数累积至 1s 封顶；
		// 网络恢复（insert 变快）后一次健康成功即复位，正常吞吐不受影响。
		Ae2PushBackoff itemBackoff = ctx.itemBackoff();
		if (slowInsertDetected) {
			itemBackoff.recordFailure(System.nanoTime());
			Ae2OutputBackoffLog.logSlowInsertBackoff(itemBackoff);
		} else if (pushedItems > 0) {
			itemBackoff.recordSuccess();
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
