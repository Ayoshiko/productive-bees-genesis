package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.stacks.AEItemKey;
import com.ayoshiko.productivebeesgenesis.util.RoundRobinSlotTraversal;
import com.ayoshiko.productivebeesgenesis.util.SaturatingMath;
import it.unimi.dsi.fastutil.objects.Object2LongLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 合并提交路径 — 按 AEItemKey 分组后每个键只调用一次 insert
 * <p>
 * 从 {@code Ae2OutputPusher.pushOutputs} 拆出（原文件 1102 行，超 500 行阈值）。
 * 本类只负责「合并这一条策略」：分组、键配额、预算判定、游标顺延与退避记账，
 * 实际 ME 提交委托 {@link Ae2OutputCommitter#pushBatchKey}。
 * <p>
 * <b>为什么合并能省时间</b>：一次 {@code insert(key, totalCount)} 与
 * {@code insert(key, 1)} 在 AE2 侧成本几乎相同（都要遍历整个存储链），
 * 所以把同键各槽数量累加后一次提交，可把「槽位数」次网络遍历压成「键数」次。
 * 这也是自适应配额限「键数」而非「物品数」的前提 —— 产物件数吞吐不受影响。
 */
final class Ae2OutputMergedPass {

	private Ae2OutputMergedPass() {
	}

	static void run(Ae2OutputPushContext ctx, List<Ae2SlotEntry> entries) {
		IAe2OutputHostBase host = ctx.host();
		Ae2PushBuffers buffers = ctx.buffers();
		long gameTick = ctx.gameTick();
		long nowNanos = ctx.nowNanos();

		// 1. 按 AEItemKey 分组（复用容器，clear 而非新建）
		Map<AEItemKey, List<Ae2SlotEntry>> keyToEntries = buffers.keyToEntries;
		Object2LongLinkedOpenHashMap<AEItemKey> keyToTotalCount = buffers.keyToTotalCount;
		for (List<Ae2SlotEntry> grouped : buffers.keyEntryListPool) grouped.clear();
		keyToEntries.clear();
		buffers.keyEntryListPoolCursor = 0;
		keyToTotalCount.clear();
		for (Ae2SlotEntry entry : entries) {
			List<Ae2SlotEntry> grouped = keyToEntries.get(entry.key);
			if (grouped == null) {
				if (buffers.keyEntryListPoolCursor < buffers.keyEntryListPool.size()) {
					grouped = buffers.keyEntryListPool.get(buffers.keyEntryListPoolCursor++);
				} else {
					grouped = new ArrayList<>();
					buffers.keyEntryListPool.add(grouped);
					buffers.keyEntryListPoolCursor++;
				}
				keyToEntries.put(entry.key, grouped);
			}
			grouped.add(entry);
			keyToTotalCount.put(entry.key, SaturatingMath.saturatingAdd(
					keyToTotalCount.getLong(entry.key), entry.count));
		}

		// 2. 对每个 key 调用一次 insert，按比例清空槽位
		int pushedItems = 0;
		AEItemKey firstDeferredKey = null;
		AEItemKey firstAttemptedKey = null;
		long firstAttemptedAmount = 0L;
		int attemptedKeys = 0;
		// Spark 优化：insert 耗时预算 — 与 key 数量限制同位检查，预算耗尽时当前 key 未尝试
		// 即成为 firstDeferredKey，复用既有 cursor 轮转恢复逻辑（无饥饿）
		long spentInsertNanos = 0L;
		boolean slowInsertDetected = false;
		int heldKeys = 0;
		Ae2InsertCostTracker costTracker = buffers.insertCostTracker;
		// 自适应键配额：健康网络恒等于 MAX_ITEM_KEYS_PER_TICK（无损），
		// 昂贵网络按实测均值收缩，未提交的键留原槽由 firstDeferredKey 轮转恢复
		int keyQuota = costTracker.keyQuota(Ae2PushLimits.MAX_ITEM_KEYS_PER_TICK);
		if (keyQuota < Ae2PushLimits.MAX_ITEM_KEYS_PER_TICK) {
			Ae2OutputBackoffLog.logAdaptiveKeyQuota(costTracker, keyQuota);
		}
		for (Object2LongMap.Entry<AEItemKey> keyEntry : keyToTotalCount.object2LongEntrySet()) {
			if (attemptedKeys >= keyQuota || spentInsertNanos >= Ae2PushLimits.INSERT_TIME_BUDGET_NANOS
					|| Ae2GlobalInsertBudget.isExhausted(gameTick)
					|| costTracker.isExhausted(gameTick)) {
				if (firstDeferredKey == null) firstDeferredKey = keyEntry.getKey();
				break;
			}
			AEItemKey key = keyEntry.getKey();
			long totalCount = keyEntry.getLongValue();
			// 离心机优先：hold key 整组跳过（key 级判定 — 同 key 占 N 槽只判定一次，
			// 满蜜脾 102 槽场景从 102 次判定降为 1 次；processability 缓存跨 tick 命中）。
			// hold key 不计入 attemptedKeys 也不设为 deferred（非预算顺延，是功能路由）。
			if (host.productivebeesgenesis$shouldHoldForCentrifuge(keyToEntries.get(key).get(0).stack)) {
				heldKeys++;
				continue;
			}
			if (ctx.keyBackoff().shouldSkip(key, nowNanos)) {
				if (firstDeferredKey == null) firstDeferredKey = key;
				continue;
			}
			if (firstAttemptedKey == null) {
				firstAttemptedKey = key;
				firstAttemptedAmount = totalCount;
			}
			long insertStart = System.nanoTime();
			int pushed = Ae2OutputCommitter.pushBatchKey(key, totalCount, keyToEntries.get(key),
					ctx.meStorage(), Ae2PushLimits.ActionSourceHolder.INSTANCE,
					ctx.holder().getOutputLedger());
			long insertCost = System.nanoTime() - insertStart;
			boolean slowInsert = Ae2GlobalInsertBudget.isSlowOperation(insertCost);
			if (slowInsert) {
				// 只累计慢 insert 的超出耗时 — 健康网络预算恒 0，满速推送不受限
				spentInsertNanos += insertCost - Ae2GlobalInsertBudget.SLOW_INSERT_NANOS;
				slowInsertDetected = true;
			}
			Ae2GlobalInsertBudget.recordCost(gameTick, insertCost);
			// 自适应记账：全额计入 EWMA 与 tick 预算（下一 tick 的 keyQuota 据此收缩/放开）
			costTracker.record(gameTick, insertCost);
			if (pushed > 0) {
				if (slowInsert) ctx.keyBackoff().recordFailure(key, System.nanoTime());
				else ctx.keyBackoff().recordSuccess(key);
			} else {
				ctx.keyBackoff().recordFailure(key, slowInsert ? System.nanoTime() : nowNanos);
			}
			pushedItems = SaturatingMath.saturatingToInt(SaturatingMath.saturatingAdd(pushedItems, pushed));
			attemptedKeys++;
		}
		if (firstDeferredKey == null) {
			// Every key was attempted. Rotate one physical slot so duplicate stacks share priority over time.
			buffers.outputSlotScanCursor = RoundRobinSlotTraversal.advance(
					ctx.scanStart(), ctx.flatSlotCount());
		} else {
			// Resume at the first physical occurrence of the first deferred key. Advancing merely by the
			// number of keys is incorrect when one key occupies several slots and can starve later pages.
			List<Ae2SlotEntry> deferredEntries = keyToEntries.get(firstDeferredKey);
			Ae2SlotEntry deferred = deferredEntries.get(0);
			buffers.outputSlotScanCursor = deferred.process * AeItemKeyCache.SLOTS_PER_PROCESS
					+ deferred.slotIdx;
		}

		if (pushedItems > 0) {
			host.productivebeesgenesis$onAe2PushComplete(pushedItems);
			host.productivebeesgenesis$markAe2StateChanged();
		}
		// 慢 insert 优先于成功复位判定（与逐槽路径同语义）：病态网络 insert 仍会成功，
		// 先 recordSuccess 会每轮清零指数，窗口恒卡 50ms（每 50ms 一次 5-10ms 网络遍历）。
		// 慢 insert 禁止复位，指数累积至 1s 封顶；网络恢复（insert 变快）后一次成功即复位。
		Ae2PushBackoff itemBackoff = ctx.itemBackoff();
		if (slowInsertDetected) {
			itemBackoff.recordFailure(System.nanoTime());
			Ae2OutputBackoffLog.logSlowInsertBackoff(itemBackoff);
		} else if (pushedItems > 0) {
			itemBackoff.recordSuccess();
		} else if (firstAttemptedKey != null) {
			Ae2OutputBackoffLog.handleCompleteFailure(itemBackoff, firstAttemptedKey, firstAttemptedAmount);
		} else if (heldKeys > 0) {
			// 全 hold 空转退避（与逐槽路径同语义）：输出槽全为离心机优先蜜脾时，
			// 避免每刻重复扫描+分组+判定；槽位变化即 reset，非蜜脾零延迟恢复
			itemBackoff.recordFailure(nowNanos);
		}
	}
}
