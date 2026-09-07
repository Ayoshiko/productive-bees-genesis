package com.ayoshiko.productivebeesgenesis.mek;

/**
 * 延迟提交的批量压缩数学 — 纯函数，无 Minecraft 依赖，便于单测。
 * <br/>
 * 产物种类多于物理输出槽时（如屠夫蜜脾 4 种产物 / 3 个输出槽），减半批量不会减少产物
 * <b>种类</b>，只能把放不下的种类留在 pending。延迟量与批量成正比，因此可直接按比例
 * 一次估算出「延迟量落进预算」的批量，避免 O(log N) 次无用的采样 + 规划。
 */
final class PbDeferralBatchMath {

	private PbDeferralBatchMath() {
	}

	/**
	 * 估算可提交批量。
	 *
	 * @param trySize            当前批量
	 * @param maxDeferredPerType 当前批量下单种产物需要延迟的最大数量
	 * @param budget             允许的单种延迟上限（通常为一个输出槽容量）
	 * @return 已在预算内时返回 {@code trySize}；否则返回<b>严格小于</b> {@code trySize}
	 *         且不小于 1 的估算值，保证调用方的重试循环必然收敛
	 */
	static int shrinkForDeferral(int trySize, int maxDeferredPerType, int budget) {
		if (trySize <= 1 || maxDeferredPerType <= 0 || budget <= 0 || maxDeferredPerType <= budget) {
			return trySize;
		}
		long scaled = (long) trySize * budget / maxDeferredPerType;
		return (int) Math.max(1L, Math.min((long) trySize - 1L, scaled));
	}
}
