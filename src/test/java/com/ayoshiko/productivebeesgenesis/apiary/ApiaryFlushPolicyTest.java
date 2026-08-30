package com.ayoshiko.productivebeesgenesis.apiary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * 蜂箱累积产出刷新时机回归。
 * <p>
 * 用户需求：「蜂箱也支持创造升级……对创造升级的性能进行优化」。
 * CREATIVE 升级让 {@code ApiaryProgressAdvancer} 的 adjustedMinTicks 恒为 1，
 * 于是 completedCycles == tickMultiplier，单只蜜蜂单个真实刻就累积 1024 次产出，
 * 固定 64 的提前 flush 阈值会让 flush 从每 10 刻一次变成每刻一次 —— 拆分不减少
 * 采样总量，只把 flush 的固定开销（分组重建 / hasValidFlower / 配方查询 /
 * ItemStackMergeHelper / 输出槽与 AE 插入 / markDirectEjectDirty）放大 10 倍。
 */
class ApiaryFlushPolicyTest {

	@Test
	void intervalAlwaysTriggersFlush() {
		assertTrue(ApiaryFlushPolicy.shouldFlush(10, 10, 0, 1), "达到固定间隔必须刷新");
		assertTrue(ApiaryFlushPolicy.shouldFlush(11, 10, 0, 1024),
				"高倍加速下固定间隔仍然生效，产出不会被无限攒住");
	}

	@Test
	void unacceleratedBehaviourMatchesLegacyThreshold() {
		assertEquals(64, ApiaryFlushPolicy.accumulationThreshold(1));
		assertFalse(ApiaryFlushPolicy.shouldFlush(1, 10, 63, 1), "未达 64 不提前刷新");
		assertTrue(ApiaryFlushPolicy.shouldFlush(1, 10, 64, 1), "达到 64 提前刷新");
	}

	/** 关键回归：CREATIVE + 1024 倍加速下单刻累积量必然巨大，不得因此每刻 flush。 */
	@Test
	void acceleratedThresholdScalesWithBatchMultiplier() {
		assertEquals(64 * 1024, ApiaryFlushPolicy.accumulationThreshold(1024));
		assertFalse(ApiaryFlushPolicy.shouldFlush(1, 10, 1024, 1024),
				"1024 倍加速下单刻累积 1024 次属正常，不得触发提前 flush");
		assertTrue(ApiaryFlushPolicy.shouldFlush(1, 10, 64 * 1024, 1024),
				"跨多刻攒出的超大批量仍应提前 flush");
	}

	@Test
	void thresholdIsClampedAndNeverNegative() {
		assertEquals(ApiaryFlushPolicy.MAX_ACCUMULATION_THRESHOLD,
				ApiaryFlushPolicy.accumulationThreshold(Integer.MAX_VALUE),
				"极端倍率必须钳制，不得溢出为负导致每刻 flush");
		assertEquals(64, ApiaryFlushPolicy.accumulationThreshold(0), "非法倍率退化为基础阈值");
		assertEquals(64, ApiaryFlushPolicy.accumulationThreshold(-5));
	}

	/** 接线断言：处理器必须走策略类，否则改动被重构掉后表现为「悄悄变慢」而非报错。 */
	@Test
	void tickProcessorUsesFlushPolicy() throws Exception {
		Path src = Path.of("src", "main", "java", "com", "ayoshiko", "productivebeesgenesis",
				"apiary", "BeeSlotTickProcessor.java");
		String source = Files.readString(src).replaceAll("\\s+", " ");
		assertTrue(source.contains("ApiaryFlushPolicy.shouldFlush(tickCounter, BATCH_FLUSH_INTERVAL,"),
				"刷新判定必须委托 ApiaryFlushPolicy");
		assertFalse(source.contains("accumulatedProgress.get() >= FLUSH_ACCUMULATION_THRESHOLD"),
				"不得保留固定阈值判定");
	}
}
