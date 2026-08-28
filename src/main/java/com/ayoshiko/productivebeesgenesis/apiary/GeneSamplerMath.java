package com.ayoshiko.productivebeesgenesis.apiary;

/** 基因采样聚合路径使用的纯数学函数。 */
final class GeneSamplerMath {

	private GeneSamplerMath() {
	}

	/**
	 * 计算系统抽样到当前累计来源为止应分配的命中数。
	 * <p>
	 * 同一批次共用一个随机偏移，可在线性时间内完成按权重分配；跨批次期望值与独立抽样一致，
	 * 不会让占比较小的来源被确定性取整长期饿死。
	 */
	static long cumulativeHitAllocation(long hitCount, long cumulativeProduceCount,
			long totalProduceCount, double randomOffset) {
		if (hitCount <= 0 || cumulativeProduceCount <= 0 || totalProduceCount <= 0) return 0L;
		if (cumulativeProduceCount >= totalProduceCount) return hitCount;
		double ratio = cumulativeProduceCount / (double) totalProduceCount;
		double quota = hitCount * ratio
				+ Math.max(0.0D, Math.min(Math.nextDown(1.0D), randomOffset));
		return Math.min(hitCount, Math.max(0L, (long) Math.floor(quota)));
	}
}
