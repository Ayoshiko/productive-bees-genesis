package com.ayoshiko.productivebeesgenesis.mek.ae2;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import appeng.api.stacks.AEFluidKey;

/**
 * AE2 流体推送批处理缓冲（简化版 PendingAEBatch）
 * <br/>
 * 参考 uselessmod 的 PendingAEBatch 模式：10 tick 累积窗口 + 按 key 合并，
 * 减少 256× 加速 + 高堆叠升级下 AE2 poweredInsert API 调用次数。
 * <p>
 * <b>设计原理</b>：
 * <ul>
 *   <li><b>累积阶段</b>：每次 pushFluids 遍历流体罐时，将待推送量按 AEFluidKey 累积到 buffer</li>
 *   <li><b>成熟阶段</b>：累积满 10 tick 后 isRipe() 返回 true，触发 flush</li>
 *   <li><b>刷新阶段</b>：drain() 返回累积的所有 key→amount 映射并清空 buffer</li>
 *   <li><b>即时刷新</b>：累积量超过阈值时 shouldFlushNow 提前刷新，避免内存占用过高</li>
 * </ul>
 * <p>
 * <b>线程安全</b>：使用 ConcurrentHashMap（防御性），实际由服务端单线程独占调用。
 * <p>
 * <b>性能收益</b>：256× 加速 + 16 STACK（65536 并行）下，原每 tick 每 tank 调用一次
 * poweredInsert（N×M 次/gameTick），批处理后降为每 10 tick 一次批量调用（N/10 次/gameTick），
 * API 调用次数降低 ≥ 99.6%。
 *
 * @since 1.8.2
 * @author Ayoshiko
 */
public final class Ae2PendingBatchBuffer {

	/** 累积窗口大小（tick）— 参考 uselessmod PendingAEBatch 的 10 tick 窗口 */
	public static final int RIPE_TICKS = 10;

	/** 即时刷新阈值（mB）— 累积量超过此值时提前刷新，避免内存占用过高 */
	private static final long FLUSH_THRESHOLD_MB = 2_000_000_000L; // 20 亿 mB（接近 int 上限）

	/** 累积的流体待推送量（按 AEFluidKey 合并） — ConcurrentHashMap 防御性并发保护 */
	private final ConcurrentMap<AEFluidKey, Long> pendingAmounts = new ConcurrentHashMap<>();

	/** 剩余成熟 tick 数（初始 RIPE_TICKS，每 tick 递减，0 时成熟） */
	private int ripeTicksRemaining = RIPE_TICKS;

	/**
	 * 累积待推送的流体量。
	 * <br/>
	 * 同一 AEFluidKey 的多次累积会合并（Long::sum），
	 * 减少 AE2 poweredInsert 调用次数。
	 *
	 * @param fluidKey 流体键
	 * @param amount   待推送量（mB，必须 > 0）
	 */
	public void accumulate(AEFluidKey fluidKey, long amount) {
		if (fluidKey == null || amount <= 0) return;
		pendingAmounts.merge(fluidKey, amount, Long::sum);
	}

	/**
	 * 判断缓冲是否已成熟（累积窗口已满）。
	 *
	 * @return true 表示已成熟，应调用 drain() 刷新
	 */
	public boolean isRipe() {
		return ripeTicksRemaining <= 0 && !pendingAmounts.isEmpty();
	}

	/**
	 * 判断是否应立即刷新（累积量超阈值）。
	 * <br/>
	 * 即使未成熟，累积量过大时也应提前刷新，避免：
	 * (1) 内存占用过高；(2) 单次 flush 的 poweredInsert 量过大导致 AE2 内部分块处理。
	 *
	 * @param currentAmount 当前总累积量（mB）
	 * @param threshold     刷新阈值（mB）
	 * @return true 表示应立即刷新
	 */
	public boolean shouldFlushNow(long currentAmount, long threshold) {
		return currentAmount >= threshold && !pendingAmounts.isEmpty();
	}

	/**
	 * 每 tick 调用，递减成熟计数器。
	 * <br/>
	 * 成熟后（ripeTicksRemaining=0）保持 0，等待 drain() 重置。
	 */
	public void tick() {
		if (ripeTicksRemaining > 0) {
			ripeTicksRemaining--;
		}
	}

	/**
	 * 排空缓冲并返回累积的 key→amount 映射。
	 * <br/>
	 * 调用后缓冲被清空，成熟计数器重置为 RIPE_TICKS。
	 * 返回的 Map 是内部 ConcurrentHashMap 的引用快照（通过 new HashMap 包装避免并发修改）。
	 *
	 * @return 累积的 key→amount 映射（可能为空 Map，永不为 null）
	 */
	public ConcurrentMap<AEFluidKey, Long> drain() {
		// 复制到新 Map 避免调用方遍历时内部 Map 被修改
		ConcurrentMap<AEFluidKey, Long> snapshot = new ConcurrentHashMap<>(pendingAmounts);
		pendingAmounts.clear();
		ripeTicksRemaining = RIPE_TICKS;
		return snapshot;
	}

	/** 获取当前累积的不同流体 key 数量（诊断用） */
	public int getKeyCount() {
		return pendingAmounts.size();
	}

	/** 获取当前总累积量（mB，诊断用） */
	public long getTotalAmount() {
		long total = 0L;
		for (long amount : pendingAmounts.values()) {
			total += amount;
		}
		return total;
	}

	/** 获取剩余成熟 tick 数（诊断用） */
	public int getRipeTicksRemaining() {
		return ripeTicksRemaining;
	}

	/** 获取即时刷新阈值（mB） */
	public static long getFlushThresholdMb() {
		return FLUSH_THRESHOLD_MB;
	}

	/** 完全重置（方块销毁时调用） */
	public void reset() {
		pendingAmounts.clear();
		ripeTicksRemaining = RIPE_TICKS;
	}
}
