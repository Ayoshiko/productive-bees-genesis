package com.ayoshiko.productivebeesgenesis.apiary;

/**
 * 蜂箱累积产出的刷新时机策略 —— 纯函数，可单测。
 * <p>
 * <b>原实现</b>：{@code accumulatedProgress >= 64} 就提前 flush，本意是把「满升级下 10 tick
 * 攒出的数百次产出」拆成小批，平滑 MSPT 尖刺。
 * <p>
 * <b>CREATIVE 升级下这个阈值反而变成放大器</b>：CREATIVE 让
 * {@code ApiaryProgressAdvancer} 的 {@code adjustedMinTicks} 恒为 1，于是
 * {@code completedCycles == tickMultiplier}，一只蜜蜂单个真实刻就累积 1024 次产出
 * （JDTE 1024 倍加速）。累积量恒 &ge; 64，提前 flush 于是<b>每个真实刻都触发</b>，
 * 而每次 flush 携带的批量并没有变小 —— 拆分不减少采样总量，只是把 flush 的固定开销
 * （分组重建、{@code hasValidFlower} 校验、配方查询、{@code ItemStackMergeHelper} 合并、
 * 输出槽/AE 插入、{@code markDirectEjectDirty}）从每 10 刻一次变成每刻一次，等于凭空 ×10。
 * <p>
 * <b>修正</b>：阈值随本刻批量倍率放大。含义是「提前 flush 只用于抑制<i>跨多刻</i>攒出的
 * 大批量，不用于抑制单刻内因加速而必然出现的大批量」——后者拆不动，只能等固定间隔。
 * 非加速场景（{@code tickMultiplier == 1}）行为与原实现逐位相同。
 */
final class ApiaryFlushPolicy {

	/**
	 * 基础累积阈值 — 非加速场景达到该值即提前 flush，避免单次 flush 量过大导致 MSPT 尖刺。
	 * <br/>
	 * 正常低升级场景 10 tick 内累积量通常 &lt; 10，不受影响。
	 */
	static final int BASE_ACCUMULATION_THRESHOLD = 64;

	/** 提前 flush 的阈值上限，防止极端倍率把阈值抬到永不触发（退化为纯间隔刷新亦可接受，但保留上界更可预期）。 */
	static final int MAX_ACCUMULATION_THRESHOLD = 1 << 20;

	private ApiaryFlushPolicy() {
	}

	/** 本刻的提前 flush 阈值 = 基础阈值 × 批量倍率（钳制上界）。 */
	static int accumulationThreshold(int tickMultiplier) {
		int multiplier = Math.max(1, tickMultiplier);
		long scaled = (long) BASE_ACCUMULATION_THRESHOLD * multiplier;
		return (int) Math.min(MAX_ACCUMULATION_THRESHOLD, scaled);
	}

	/**
	 * 是否应在本刻刷新累积产出。
	 *
	 * @param tickCounter         自上次刷新以来的 tick 数
	 * @param flushInterval       固定刷新间隔（tick）
	 * @param accumulatedProgress 当前累积产出次数
	 * @param tickMultiplier      本刻批量倍率
	 */
	static boolean shouldFlush(int tickCounter, int flushInterval, int accumulatedProgress,
			int tickMultiplier) {
		if (tickCounter >= flushInterval) return true;
		return accumulatedProgress >= accumulationThreshold(tickMultiplier);
	}
}
