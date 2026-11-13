package com.ayoshiko.productivebeesgenesis.mek;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ServerTickTimeMonitorTest {

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

}
