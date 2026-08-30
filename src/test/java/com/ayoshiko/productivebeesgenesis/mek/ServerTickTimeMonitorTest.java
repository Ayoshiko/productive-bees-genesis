package com.ayoshiko.productivebeesgenesis.mek;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ServerTickTimeMonitorTest {

	@Test
	void healthyServerKeepsFullBatchBudget() {
		assertEquals(1.0, ServerTickTimeMonitor.factorForMspt(50.0, true));
		assertEquals(0.9, ServerTickTimeMonitor.factorForMspt(55.0, true), 1.0e-9);
	}

	@Test
	void sustainedHighMsptDegradesTowardTenPercent() {
		assertEquals(0.55, ServerTickTimeMonitor.factorForMspt(75.0, false), 1.0e-9);
		assertEquals(0.1, ServerTickTimeMonitor.factorForMspt(100.0, false), 1.0e-9);
		assertEquals(0.1, ServerTickTimeMonitor.factorForMspt(250.0, false), 1.0e-9);
	}

	@Test
	void hysteresisPreventsThresholdOscillation() {
		assertTrue(ServerTickTimeMonitor.nextFullFactorState(true, 60.0));
		assertFalse(ServerTickTimeMonitor.nextFullFactorState(true, 60.1));
		assertFalse(ServerTickTimeMonitor.nextFullFactorState(false, 45.0));
		assertTrue(ServerTickTimeMonitor.nextFullFactorState(false, 44.9));
	}

	@Test
	void responsiveAverageReactsToSpikeAndThenRecovers() {
		double responsive = ServerTickTimeMonitor.nextResponsiveMspt(50.0, 200.0);
		assertEquals(80.0, responsive, 1.0e-9);
		assertEquals(74.0, ServerTickTimeMonitor.nextResponsiveMspt(responsive, 50.0), 1.0e-9);
	}

	/**
	 * 回归：Post 没有配对的 Pre 时必须丢弃样本。
	 * 原实现 tickStartNanos 初值为 0，此时 nanoTime()-0 = 开机以来的纳秒数，
	 * 写进滚动平均后 avgMspt 永久巨大，AE2 输入拉取被 TPS 闸门永久降级。
	 */
	@Test
	void missingPreEventDiscardsSampleInsteadOfRecordingUptime() {
		assertEquals(ServerTickTimeMonitor.INVALID_SAMPLE,
				ServerTickTimeMonitor.sampleMsFor(ServerTickTimeMonitor.UNSET_TICK_START, 9_876_543_210L),
				"无配对 Pre 必须返回 INVALID_SAMPLE，不能把 nanoTime 绝对值当耗时");
	}

	@Test
	void clockRegressionDiscardsSample() {
		assertEquals(ServerTickTimeMonitor.INVALID_SAMPLE,
				ServerTickTimeMonitor.sampleMsFor(1_000_000L, 999_999L),
				"时钟回拨（负耗时）必须丢弃样本");
	}

	@Test
	void normalSampleConvertsNanosToMillisAndClampsOutliers() {
		assertEquals(42.0, ServerTickTimeMonitor.sampleMsFor(0L, 42_000_000L), 1.0e-9,
				"正常样本按纳秒转毫秒");
		assertEquals(ServerTickTimeMonitor.MAX_SAMPLE_MS,
				ServerTickTimeMonitor.sampleMsFor(0L, 60_000_000_000L), 1.0e-9,
				"超长停顿钳制到 MAX_SAMPLE_MS，避免长期占据 100-tick 窗口");
	}
}
