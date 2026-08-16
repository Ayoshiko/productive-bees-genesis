package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.stacks.AEFluidKey;
import com.ayoshiko.productivebeesgenesis.util.SaturatingMath;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;

import java.util.HashMap;
import java.util.Map;

/**
	 * AE2 流体推送批处理缓冲（简化版 PendingAEBatch）
	 * <br/>
	 * 参考 uselessmod 的 PendingAEBatch 模式：20 tick 累积窗口 + 按 key 合并，
	 * 减少 256× 加速 + 高堆叠升级下 AE2 poweredInsert API 调用次数。
	 * <p>
	 * <b>设计原理</b>：
	 * <ul>
	 *   <li><b>累积阶段</b>：每次 pushFluids 遍历流体罐时，将待推送量按 AEFluidKey 累积到 buffer</li>
	 *   <li><b>成熟阶段</b>：累积满 20 tick 后 isRipe() 返回 true，触发 flush</li>
	 *   <li><b>刷新阶段</b>：drain() 返回累积的所有 key→amount 映射并清空 buffer</li>
	 *   <li><b>即时刷新</b>：累积量超过阈值时 shouldFlushNow 提前刷新，避免内存占用过高</li>
	 * </ul>
	 * <p>
	 * <b>线程安全</b>：服务端 tick 线程独占访问，无需并发容器。
	 * <p>
	 * <b>性能收益</b>：256× 加速 + 16 STACK（65536 并行）下，原每 tick 每 tank 调用一次
	 * poweredInsert（N×M 次/gameTick），批处理后降为每 20 tick 一次批量调用（N/20 次/gameTick），
	 * API 调用次数降低 ≥ 99.8%。
	 *
	 * @since 2.0.0
	 * @author Ayoshiko
	 */
public final class Ae2PendingBatchBuffer {

	/** 累积窗口大小（tick）— Spark优化：从10 tick增大到20 tick（1秒真实时间）
	 *  原理：减少flush频率→减少poweredInsert调用次数→减少ExtendedAE无限单元NBT深拷贝
	 *  无加速下每20 tick(1秒)flush一次，流体输出延迟不影响游戏体验 */
	public static final int RIPE_TICKS = 20;

	/** 即时刷新阈值（mB）— 累积量超过此值时提前刷新，避免内存占用过高
	 *  Spark优化：从20亿提升到50亿mB，配合更大批量减少flush次数 */
	private static final long FLUSH_THRESHOLD_MB = 5_000_000_000L; // 50 亿 mB

	/** 累积的流体待推送量（按 AEFluidKey 合并），仅由服务端 tick 线程访问。 */
	/* Swap maps at drain time so a flush does not copy every pending entry. */
	private Object2LongOpenHashMap<AEFluidKey> pendingAmounts = new Object2LongOpenHashMap<>();
	private Object2LongOpenHashMap<AEFluidKey> drainedAmounts = new Object2LongOpenHashMap<>();
	/** Per-scan totals. A host may expose several tanks containing the same fluid. */
	private final Object2LongOpenHashMap<AEFluidKey> sampleAmounts = new Object2LongOpenHashMap<>();

	/** 剩余成熟 tick 数（初始 RIPE_TICKS，每 tick 递减，0 时成熟） */
	private int ripeTicksRemaining = RIPE_TICKS;

	/**
	 * 当前总累积量（mB）— 增量维护，避免 {@link #getTotalAmount()} 每 tick 遍历 map。
	 * <br/>
	 * 服务端单线程独占调用（pushFluids 在 tick 线程串行执行），无需 volatile/CAS；
	 * accumulate/drain/reset 均在同一线程内完成。
	 */
	private long totalPendingAmount = 0L;

	/**
	 * 累积待推送的流体量。
	 * <br/>
	 * 同一 AEFluidKey 的多次采样保留最大当前库存，而不是累加。
	 * 流体槽在 flush 前尚未 shrink，累加会把同一批库存重复计算 20 次，
	 * 造成虚假的超大待推送量和不必要的 Long/Map 操作。
	 * <p>
	 * 增量维护 {@link #totalPendingAmount}：新 key 直接累加；已有 key 仅累加
	 * 「新采样 - 旧值」的正差，采样未超过旧值时总额不变。
	 *
	 * @param fluidKey 流体键
	 * @param amount   待推送量（mB，必须 > 0）
	 */
	public void accumulate(AEFluidKey fluidKey, long amount) {
		if (fluidKey == null || amount <= 0) return;
		long previous = pendingAmounts.getLong(fluidKey);
		if (previous == 0L) {
			pendingAmounts.put(fluidKey, amount);
			totalPendingAmount = SaturatingMath.saturatingAdd(totalPendingAmount, amount);
		} else if (amount > previous) {
			pendingAmounts.put(fluidKey, amount);
			totalPendingAmount = SaturatingMath.saturatingAdd(totalPendingAmount, amount - previous);
		}
	}

	/** Starts one host-tank snapshot; repeated push attempts must not double-count it. */
	void beginSample() {
		sampleAmounts.clear();
	}

	/** Adds one tank to the current snapshot. */
	void accumulateSample(AEFluidKey fluidKey, long amount) {
		if (fluidKey == null || amount <= 0) return;
		sampleAmounts.put(fluidKey,
				SaturatingMath.saturatingAdd(sampleAmounts.getLong(fluidKey), amount));
	}

	/** Commits the summed snapshot using the existing max-across-samples semantics. */
	void finishSample() {
		for (Object2LongMap.Entry<AEFluidKey> entry : sampleAmounts.object2LongEntrySet()) {
			accumulate(entry.getKey(), entry.getLongValue());
		}
		sampleAmounts.clear();
	}

	/**
	 * 判断缓冲是否已成熟（累积窗口已满）。
	 *
	 * @return true 表示已成熟，应调用 drain() 刷新
	 */
	public boolean isRipe() {
		return ripeTicksRemaining <= 0 && !pendingAmounts.isEmpty();
	}

	/** 仅检查时间窗是否成熟，供调度诊断与无 AE2 对象的单元测试使用。 */
	boolean isWindowRipe() {
		return ripeTicksRemaining <= 0;
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
	 * 返回的 Map 是独立副本；内部服务端路径使用 {@link #drainFast()} 避免复制，
	 * 但该路径返回的映射只保证在下一次 drain 前有效。
	 *
	 * @return 累积的 key→amount 映射（可能为空 Map，永不为 null）
	 */
	public Map<AEFluidKey, Long> drain() {
		Object2LongMap<AEFluidKey> snapshot = drainFast();
		return snapshot.isEmpty() ? new HashMap<>() : new HashMap<>(snapshot);
	}

	/**
	 * Internal server-thread drain that swaps reusable maps without copying entries.
	 * The returned map must be consumed before the next call on this buffer.
	 */
	Object2LongMap<AEFluidKey> drainFast() {
		// Swap instead of copying every entry. The returned map is only reused after
		// the caller has finished iterating it on the server tick thread.
		Object2LongOpenHashMap<AEFluidKey> snapshot = pendingAmounts;
		pendingAmounts = drainedAmounts;
		drainedAmounts = snapshot;
		pendingAmounts.clear();
		totalPendingAmount = 0L;
		ripeTicksRemaining = RIPE_TICKS;
		return snapshot;
	}

	/** 获取当前累积的不同流体 key 数量（诊断用） */
	public int getKeyCount() {
		return pendingAmounts.size();
	}

	/** 获取当前总累积量（mB，诊断用） */
	public long getTotalAmount() {
		return totalPendingAmount;
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
		drainedAmounts.clear();
		sampleAmounts.clear();
		totalPendingAmount = 0L;
		ripeTicksRemaining = RIPE_TICKS;
	}
}
