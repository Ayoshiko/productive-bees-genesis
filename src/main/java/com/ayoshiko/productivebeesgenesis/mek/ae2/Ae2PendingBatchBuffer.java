package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.stacks.AEFluidKey;
import com.ayoshiko.productivebeesgenesis.util.SaturatingMath;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;

import java.util.HashMap;
import java.util.Map;

/**
	 * AE2 流体推送的 per-host 待推送缓冲（按 AEFluidKey 合并 + 成熟窗口）
	 * <br/>
	 * 调度模型参考本地参考模组 useless 的 {@code PendingAEBatch}
	 * （{@code AdvancedAlloyFurnaceAeManager}）：<b>累积窗口 + 成熟后统一排空 + 失败整窗退避</b>。
	 * 三点关键差异是有意为之，照抄会出错：
	 * <ol>
	 *   <li><b>累积取最大值而非累加</b>：useless 累积的是「已从 AE2 CPU 抽出、所有权已转移」
	 *       的物料，累加天然不重复；我们累积的是宿主槽位里<b>尚未 shrink</b> 的库存，
	 *       同一批流体会被反复采到，累加会虚增约「加速倍率」倍待推送量。</li>
	 *   <li><b>成熟后先过门禁再排空</b>：useless 的 {@code drain()} 会先 clear，失败路径必须
	 *       显式放回；我们的 {@link #drainFast()} 只在推送路径通过全部门禁后才调用，
	 *       任何提前返回都让数据留在 {@link #pendingAmounts} 里，不存在丢料窗口。</li>
	 *   <li><b>窗口不因累积而重置</b>：与 useless 的 {@code add()} 一致（其源码注释明确记录：
	 *       若每次 push 都重置计时器，持续产出时批次永远不会成熟，表现为「一直不推送」）。</li>
	 * </ol>
	 * <p>
	 * <b>触发语义</b>（由 {@link Ae2FluidPusher} 组合使用）：
	 * <ul>
	 *   <li>{@link #needsPush} —— 有新增流体，或某种流体的槽位已满 → 本刻立即推送（有就推送）；</li>
	 *   <li>{@link #isRipe} —— 没有新增但窗口到期 → 重试上一批（成熟窗口就是重试节奏，
	 *       避免对同一批被网络拒绝的流体每刻空转一次全网络遍历）。</li>
	 * </ul>
	 * <p>
	 * <b>线程安全</b>：服务端 tick 线程独占访问，无需并发容器。
	 * <p>
	 * <b>性能</b>：所有表跨 tick 复用（排空用交换而非复制），正常路径零分配。
	 *
	 * @since 2.0.0
	 * @author Ayoshiko
	 */
public final class Ae2PendingBatchBuffer {

	/**
	 * 成熟窗口（真实游戏刻）。参考 useless 的 {@code BATCH_RIPE_TICKS = 10}
	 * （其 {@code UNRETURNED_RETRY_TICKS = 20} 是另一件事，历史注释把两者混用过，勿再混淆）。
	 * <p>
	 * 窗口的作用是「重试节奏」而非「延迟推送」：有新流体时本刻就推，只有**没有新增**
	 * 的滞留流体才等到窗口到期再重试一次。
	 */
	public static final int RIPE_TICKS = 10;

	/** 累积的流体待推送量（按 AEFluidKey 合并取最大值），仅由服务端 tick 线程访问。 */
	/* Swap maps at drain time so a flush does not copy every pending entry. */
	private Object2LongOpenHashMap<AEFluidKey> pendingAmounts = new Object2LongOpenHashMap<>();
	private Object2LongOpenHashMap<AEFluidKey> drainedAmounts = new Object2LongOpenHashMap<>();
	/** 本轮采样：每个 key 在槽内的总量（同一宿主可能有多个槽装同一种流体）。 */
	private final Object2LongOpenHashMap<AEFluidKey> sampleAmounts = new Object2LongOpenHashMap<>();
	/**
	 * 本轮采样：每个 key 所占槽位的容量之和。
	 * <p>
	 * 用于识别「该流体的槽已满」这一压力状态 —— 满槽时液面被容量钳住、无法再上升，
	 * 若只以「液面比上次尝试更高」判新增，则判据永远为假，满槽会长时间不排空。
	 */
	private final Object2LongOpenHashMap<AEFluidKey> sampleCapacities = new Object2LongOpenHashMap<>();

	/**
	 * 上次推送尝试后仍留在槽内的量（按 key）。
	 * <p>
	 * 用于判定「本刻是否出现了新增流体」：当前采样量 &gt; 该值时说明机器又产出了新流体，
	 * 应立即推送；相等说明槽内还是那批被网络拒绝的流体，交给窗口重试即可。
	 * 未知 key（从未尝试过）按 0 处理，因此首次出现的流体永远算「新增」。
	 */
	private final Object2LongOpenHashMap<AEFluidKey> lastAttemptRemaining = new Object2LongOpenHashMap<>();

	/** 剩余成熟 tick 数（初始 RIPE_TICKS，每真实游戏刻递减，0 时成熟） */
	private int ripeTicksRemaining = RIPE_TICKS;

	/**
	 * 当前总累积量（mB）— 增量维护，避免每次查询都遍历 map。
	 * <br/>
	 * 服务端单线程独占调用（pushFluids 在 tick 线程串行执行），无需 volatile/CAS。
	 */
	private long totalPendingAmount = 0L;

	/**
	 * 累积待推送的流体量。
	 * <br/>
	 * 同一 AEFluidKey 的多次采样保留最大当前库存，而不是累加：流体槽在推送前尚未 shrink，
	 * 累加会把同一批库存重复计算「窗口 tick 数」次，造成虚假的超大待推送量。
	 * <p>
	 * 增量维护 {@link #totalPendingAmount}：新 key 直接累加；已有 key 仅累加
	 * 「新采样 - 旧值」的正差，采样未超过旧值时总额不变。
	 * <p>
	 * <b>不触碰成熟计时器</b>：与 useless 的 {@code PendingAEBatch#add} 一致，
	 * 计时从批次创建/重排起算，否则持续产出时窗口永远不会到期。
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

	/**
	 * 开始一轮槽位采样：清空并返回复用的采样表。
	 * <p>
	 * 调用方（{@link Ae2FluidPusher}）在一次遍历流体罐的过程中按 key 聚合当前库存。
	 * 聚合结果既作为本刻采样，也直接当作后续「推送量 clamp 到当前实际库存」的索引，
	 * 因此推送阶段不再需要按 key 重扫全部槽位（原实现为 O(流体键数 × 槽数)）。
	 * <p>
	 * 返回的 Map 在 {@link #commitTankSample()} 之后仍然有效（commit 不清表），
	 * 直到下一次 {@link #beginTankSample()}。
	 *
	 * @return 复用的采样表（服务端 tick 线程独占）
	 */
	Object2LongOpenHashMap<AEFluidKey> beginTankSample() {
		sampleAmounts.clear();
		sampleCapacities.clear();
		return sampleAmounts;
	}

	/**
	 * 本轮采样中「每个 key 所占槽位的容量之和」表，与 {@link #beginTankSample()} 的库存表同生命周期。
	 * <p>
	 * 调用方在采样循环里对每个非空槽累加 {@code tank.getCapacity()}；用于识别该流体是否已经满槽。
	 *
	 * @return 复用的容量表（服务端 tick 线程独占）
	 */
	Object2LongOpenHashMap<AEFluidKey> tankSampleCapacities() {
		return sampleCapacities;
	}

	/**
	 * 把采完的采样表并入待推送表（同 key 保留最大值语义）。
	 * <p>
	 * 采样表本身不清空，供调用方继续作为 clamp 索引使用。
	 */
	void commitTankSample() {
		for (Object2LongMap.Entry<AEFluidKey> entry : sampleAmounts.object2LongEntrySet()) {
			accumulate(entry.getKey(), entry.getLongValue());
		}
	}

	/**
	 * 本刻是否应当推送：**有新增流体，或某种流体的槽已经满了**。
	 * <p>
	 * <b>缺陷修复（满槽长时间不排空）</b>：只比较「液面是否比上次尝试后更高」在高并行产线下会失效 ——
	 * 槽满时液面被容量钳住无法再上升，判据恒为假，于是只剩窗口重试（10 刻一次），
	 * 而工厂产量远大于单次尝试能排掉的量，槽就长期满着。因此必须把「该流体的槽位已满」
	 * 也作为立即推送条件：它表示机器正在被本地槽憋住，应每刻尝试排空。
	 * <p>
	 * 两种条件都不成立时（既没有新增、也没满槽）说明只是上一批被网络拒绝的滞留量，
	 * 交给成熟窗口按 10 刻节奏重试即可，避免每刻空转一次全网络遍历。
	 *
	 * @param sample     本刻按 key 聚合的槽内库存（{@link #beginTankSample()} 返回的表）
	 * @param capacities 本刻按 key 聚合的槽位容量（{@link #tankSampleCapacities()}）
	 * @return true 表示本刻应立即推送
	 */
	boolean needsPush(Object2LongMap<AEFluidKey> sample, Object2LongMap<AEFluidKey> capacities) {
		for (Object2LongMap.Entry<AEFluidKey> entry : sample.object2LongEntrySet()) {
			long amount = entry.getLongValue();
			if (amount > lastAttemptRemaining.getLong(entry.getKey())) return true;
			long capacity = capacities.getLong(entry.getKey());
			if (capacity > 0L && amount >= capacity) return true;
		}
		return false;
	}

	/**
	 * 记录一次推送尝试后该 key 仍留在槽内的量。
	 * <p>
	 * 全量被接收时记为 0，于是下一次产出会立刻被判为「新增」；被拒绝时记为当前库存，
	 * 于是同一批流体会等窗口到期再重试，而不是每刻发起注定失败的全网络遍历。
	 *
	 * @param fluidKey         流体键
	 * @param remainingInTank  尝试后槽内剩余量（mB，≥ 0）
	 */
	void recordAttempt(AEFluidKey fluidKey, long remainingInTank) {
		if (fluidKey == null) return;
		long normalized = Math.max(0L, remainingInTank);
		if (normalized == 0L) {
			lastAttemptRemaining.removeLong(fluidKey);
		} else if (lastAttemptRemaining.getLong(fluidKey) != normalized) {
			lastAttemptRemaining.put(fluidKey, normalized);
		}
	}

	/**
	 * 判断缓冲是否已成熟（窗口到期且有待推送流体）。
	 *
	 * @return true 表示应重试排空
	 */
	public boolean isRipe() {
		return ripeTicksRemaining <= 0 && !pendingAmounts.isEmpty();
	}

	/** 仅检查时间窗是否成熟，供调度诊断与无 AE2 对象的单元测试使用。 */
	boolean isWindowRipe() {
		return ripeTicksRemaining <= 0;
	}

	/**
	 * 每个真实游戏刻调用一次，递减成熟计数器。
	 * <br/>
	 * 加速子 tick 必须在入口合并（{@code tryStartFluidPush}）后再调用本方法，
	 * 否则窗口会被子 tick 提前耗尽。成熟后保持 0，等待排空重置。
	 */
	public void tick() {
		if (ripeTicksRemaining > 0) {
			ripeTicksRemaining--;
		}
	}

	/**
	 * 排空缓冲并返回累积的 key→amount 映射。
	 * <br/>
	 * 调用后缓冲被清空、成熟计时器重置为 {@link #RIPE_TICKS}。
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

	/** 获取当前总累积量（mB） */
	public long getTotalAmount() {
		return totalPendingAmount;
	}

	/** 获取剩余成熟 tick 数（诊断用） */
	public int getRipeTicksRemaining() {
		return ripeTicksRemaining;
	}

	/** 完全重置（方块销毁时调用） */
	public void reset() {
		pendingAmounts.clear();
		drainedAmounts.clear();
		sampleAmounts.clear();
		sampleCapacities.clear();
		lastAttemptRemaining.clear();
		totalPendingAmount = 0L;
		ripeTicksRemaining = RIPE_TICKS;
	}
}
