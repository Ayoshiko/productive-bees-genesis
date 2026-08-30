package com.ayoshiko.productivebeesgenesis.mek.ae2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 低 TPS 降级闸门与存储健康阈值回归。
 * <p>
 * 对应玩家反馈：「1.0.5 后离心机不从 AE2 拉取蜜脾，机器在线，处理和产物回送正常」
 * 以及「两个相同配置的离心工厂只有一个会拉取，有时会自己恢复」。
 */
class Ae2LowTpsGateTest {

	@Test
	void healthyTpsNeverSkips() {
		for (long counter = 0L; counter < 64L; counter++) {
			assertFalse(Ae2LowTpsGate.shouldSkip(20.0, counter),
					"健康 TPS 下不得跳过拉取 counter=" + counter);
			assertFalse(Ae2LowTpsGate.shouldSkip(Ae2LowTpsGate.LOW_TPS_THRESHOLD, counter),
					"恰好等于阈值属健康侧 counter=" + counter);
		}
	}

	/** 关键回归：低 TPS 必须限流而非停机，否则机器永久断供。 */
	@Test
	void lowTpsThrottlesButNeverStarves() {
		int allowed = 0;
		long window = Ae2LowTpsGate.LOW_TPS_ALLOW_EVERY_N_CALLS * 5L;
		for (long counter = 0L; counter < window; counter++) {
			if (!Ae2LowTpsGate.shouldSkip(1.0, counter)) allowed++;
		}
		assertEquals(5, allowed, "低 TPS 下每 N 次调用应恰好放行 1 次，不能一次都不放行");
	}

	@Test
	void negativeCounterStillAllowsPeriodically() {
		// pullCallCounter 在 clear() 后可能与 lastPullCounter 组合出负值路径，
		// 用 floorMod 保证放行点分布不错位。
		assertFalse(Ae2LowTpsGate.shouldSkip(1.0,
						-Ae2LowTpsGate.LOW_TPS_ALLOW_EVERY_N_CALLS),
				"负计数在放行点上仍须放行");
	}

	/**
	 * 退避判定阈值必须显著高于预算记账阈值。
	 * 健康大型 ME 网络单次 extract 0.6-3ms，若按 0.5ms 判为故障，
	 * per-key 退避会拉黑该 key、整机 returnBackoff 爬到 1 秒上限并锁死拉取入口。
	 */
	@Test
	void pathologicalThresholdIsAboveBudgetThreshold() {
		assertTrue(Ae2StorageHealth.PATHOLOGICAL_OPERATION_NANOS
						> Ae2GlobalInsertBudget.SLOW_INSERT_NANOS,
				"故障阈值必须高于预算记账阈值");
		assertFalse(Ae2StorageHealth.isPathological(3_000_000L),
				"3ms extract 是健康大网络的正常开销，不得触发退避");
		assertTrue(Ae2StorageHealth.isPathological(8_000_000L),
				"8ms 属 EnderDrives 型 WAL fsync 病态形态，必须退避");
	}

	/** 自定义阈值记账：正常 extract 不得消耗全服预算。 */
	@Test
	void budgetIgnoresNormalExtractUnderPathologicalThreshold() {
		long tick = 4_242L;
		assertFalse(Ae2GlobalInsertBudget.isExhausted(tick));
		for (int i = 0; i < 32; i++) {
			Ae2GlobalInsertBudget.recordCost(tick, 3_000_000L,
					Ae2StorageHealth.PATHOLOGICAL_OPERATION_NANOS);
		}
		assertFalse(Ae2GlobalInsertBudget.isExhausted(tick),
				"32 次 3ms 的正常 extract 不应打满全服预算，否则同 tick 后续机器一律不拉取");
		// 换 tick 隔离，避免污染同类其他测试
		Ae2GlobalInsertBudget.isExhausted(tick + 1L);
	}
}
