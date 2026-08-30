package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.MEStorage;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import com.ayoshiko.productivebeesgenesis.util.SaturatingMath;
import net.minecraft.world.item.ItemStack;

import java.util.function.ToIntFunction;

/**
 * 预解析的直推会话 — 一次解析 AE 目标，供一批产物/缓冲物品逐个直推
 * <p>
 * 从 {@code Ae2OutputPusher.DirectItemPushSession} 提为顶层类（原文件 1102 行，超 500 行阈值）。
 * <p>
 * 满存储专项四重保护（玩家反馈：ME 磁盘满时单机 24-50ms/tick）：
 * insert 返回 0 时 AE2 仍完整遍历网络（每个存储单元尝试后拒绝），
 * 缓冲直推（32 组/次）与生成物直推（32 次/tick）在满存储下每 tick
 * 触发最多 64 次完整网络遍历。会话内短路消除该浪费：
 * <ol>
 *   <li>耗时预算 — 累计慢 insert 超出耗时超 {@link Ae2PushLimits#INSERT_TIME_BUDGET_NANOS} 后短路
 *       （健康网络累计恒 0，满速推送不受限）</li>
 *   <li>连续零接收中止 — 满存储时网络状态同 tick 不变，连续
 *       {@link Ae2PushLimits#CONSECUTIVE_ZERO_ACCEPT_LIMIT} 次 0 接收后短路剩余推送</li>
 *   <li>慢 insert 检测 — 单次超 {@link Ae2GlobalInsertBudget#SLOW_INSERT_NANOS} 标记，
 *       供调用方联动整体退避（部分成功也不复位）</li>
 *   <li>全服预算 + 自适应配额 — {@link Ae2GlobalInsertBudget} 跨机器钳制同 tick 慢 insert 总量，
 *       {@link Ae2InsertCostTracker} 另按实测均值收缩本 tick 允许的 insert 次数
 *       （覆盖单次 &lt;0.5ms 但极高频的外部存储）。物品留原槽无损顺延。</li>
 * </ol>
 */
public final class Ae2DirectItemPushSession implements ToIntFunction<ItemStack> {

	private MEStorage meStorage;
	private Ae2KeyBackoffRegistry<AEItemKey> keyBackoff;
	private long nowNanos;
	/** 会话创建时的游戏刻 — 用于全服 insert 预算的 tick 归属 */
	private long gameTick;
	private int attemptedCount;
	private int deferredCount;
	/** 本会话累计 insert 耗时（纳秒） */
	private long spentInsertNanos;
	/** 连续零接收计数 — 任一成功 insert 即清零 */
	private int zeroAcceptStreak;
	/** 是否检测到慢 insert */
	private boolean slowInsertDetected;
	/** 本机 insert 成本记账器（per-tile，与主推送路径共享 EWMA 与 tick 预算） */
	private Ae2InsertCostTracker costTracker;
	/** 本轮允许的 insert 次数上限 — 健康网络等于 MAX_ITEM_KEYS_PER_TICK（无损） */
	private int insertQuota;

	Ae2DirectItemPushSession(MEStorage meStorage, Ae2KeyBackoffRegistry<AEItemKey> keyBackoff,
			long gameTick, Ae2InsertCostTracker costTracker) {
		reset(meStorage, keyBackoff, gameTick, costTracker);
	}

	void reset(MEStorage meStorage, Ae2KeyBackoffRegistry<AEItemKey> keyBackoff, long gameTick,
			Ae2InsertCostTracker costTracker) {
		this.meStorage = meStorage;
		this.keyBackoff = keyBackoff;
		this.nowNanos = System.nanoTime();
		this.gameTick = gameTick;
		this.attemptedCount = 0;
		this.deferredCount = 0;
		this.spentInsertNanos = 0L;
		this.zeroAcceptStreak = 0;
		this.slowInsertDetected = false;
		this.costTracker = costTracker;
		this.insertQuota = costTracker == null
				? Ae2PushLimits.MAX_ITEM_KEYS_PER_TICK
				: costTracker.keyQuota(Ae2PushLimits.MAX_ITEM_KEYS_PER_TICK);
	}

	public int attemptedCount() { return attemptedCount; }
	public int deferredCount() { return deferredCount; }

	/**
	 * 是否应触发调用方整体退避 — 慢 insert（网络遍历昂贵）或连续零接收（满存储）。
	 * <br/>
	 * 调用方据此调用 {@link Ae2PushBackoff#recordFailure(long)}，
	 * 即使本轮部分成功也保持退避（修复半满网络下"塞进 1 个物品就复位退避"的抖动漏洞）。
	 */
	public boolean shouldTriggerBackoff() {
		return slowInsertDetected || zeroAcceptStreak >= Ae2PushLimits.CONSECUTIVE_ZERO_ACCEPT_LIMIT;
	}

	/** 记录单次 insert 耗时到单机预算与全服预算（成功与异常路径共用） */
	private void recordInsertCost(long insertCost) {
		if (Ae2GlobalInsertBudget.isSlowOperation(insertCost)) {
			// 只累计慢 insert 的超出耗时 — 健康网络预算恒 0，满速推送不受限
			spentInsertNanos += insertCost - Ae2GlobalInsertBudget.SLOW_INSERT_NANOS;
			slowInsertDetected = true;
		}
		Ae2GlobalInsertBudget.recordCost(gameTick, insertCost);
		// 自适应记账：全额计入 EWMA 与 tick 预算，覆盖 ae2lt 样板解码这类中等昂贵高频 insert
		if (costTracker != null) costTracker.record(gameTick, insertCost);
	}

	@Override
	public int applyAsInt(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return 0;
		// 满存储/预算耗尽短路：不再发起 insert（即完整网络遍历），物品由调用方保留
		if (spentInsertNanos >= Ae2PushLimits.INSERT_TIME_BUDGET_NANOS
				|| zeroAcceptStreak >= Ae2PushLimits.CONSECUTIVE_ZERO_ACCEPT_LIMIT
				|| attemptedCount >= insertQuota
				|| Ae2GlobalInsertBudget.isExhausted(gameTick)
				|| (costTracker != null
						&& (costTracker.isExhausted(gameTick)
								|| !costTracker.canInsertNow(gameTick, Ae2PushLimits.MAX_ITEM_KEYS_PER_TICK)))) {
			deferredCount++;
			return 0;
		}
		AEItemKey key = AEItemKey.of(stack);
		if (key == null) return 0;
		if (keyBackoff != null && keyBackoff.shouldSkip(key, nowNanos)) {
			deferredCount++;
			return 0;
		}
		attemptedCount++;
		long inserted;
		long insertStart = System.nanoTime();
		try {
			inserted = meStorage.insert(key, stack.getCount(), Actionable.MODULATE,
					Ae2PushLimits.ActionSourceHolder.INSTANCE);
		} catch (Exception e) {
			// 抛异常的 insert 恰恰最昂贵（病态网络的 fsync/转换接口），同样入账预算防止每 tick 重复全量遍历
			recordInsertCost(System.nanoTime() - insertStart);
			zeroAcceptStreak++;
			if (keyBackoff != null) keyBackoff.recordFailure(key, System.nanoTime());
			Ae2PushExceptionLog.handle(e, 0, 0, stack, stack.getCount());
			return 0;
		}
		long insertCost = System.nanoTime() - insertStart;
		boolean slowInsert = Ae2GlobalInsertBudget.isSlowOperation(insertCost);
		recordInsertCost(insertCost);
		if (inserted <= 0) {
			zeroAcceptStreak++;
			if (keyBackoff != null) keyBackoff.recordFailure(key, System.nanoTime());
			LogThrottle.warnWithCooldown("ae2_buffer_push_backoff", 60_000L,
					"AE2 缓冲区物品推送失败 item={}, count={}", key, stack.getCount());
			return 0;
		}
		zeroAcceptStreak = 0;
		if (keyBackoff != null) {
			if (slowInsert) keyBackoff.recordFailure(key, System.nanoTime());
			else keyBackoff.recordSuccess(key);
		}
		return SaturatingMath.saturatingToInt(
				SaturatingMath.clampToRequest(inserted, stack.getCount()));
	}
}
