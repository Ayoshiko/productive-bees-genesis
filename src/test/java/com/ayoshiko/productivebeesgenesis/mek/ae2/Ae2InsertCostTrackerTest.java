package com.ayoshiko.productivebeesgenesis.mek.ae2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link Ae2InsertCostTracker} 行为契约测试。
 * <p>
 * 核心不变量：<b>健康网络零限流</b>（配额恒为上限、预算恒不耗尽），
 * 只有实测均值超过健康阈值后才收缩，避免优化伤害正常推送吞吐。
 */
class Ae2InsertCostTrackerTest {

	/** 健康 insert：80µs */
	private static final long HEALTHY_COST = 80_000L;
	/** ae2lt Matrix Port 型中等昂贵 insert：300µs */
	private static final long MEDIUM_COST = 300_000L;
	/** EnderDrives WAL fsync 型极慢 insert：6ms */
	private static final long FSYNC_COST = 6_000_000L;

	@Test
	void noSamplesKeepsFullQuota() {
		Ae2InsertCostTracker tracker = new Ae2InsertCostTracker();
		assertEquals(32, tracker.keyQuota(32));
		assertFalse(tracker.isExhausted(1L));
		assertTrue(tracker.canInsertNow(1L, 32));
		assertFalse(tracker.isExpensiveNetwork());
	}

	@Test
	void healthyNetworkIsNeverThrottled() {
		Ae2InsertCostTracker tracker = new Ae2InsertCostTracker();
		// 同 tick 内连续 200 次健康 insert（远超单机名义上限）仍不被限制
		for (int i = 0; i < 200; i++) {
			tracker.record(7L, HEALTHY_COST);
			assertEquals(32, tracker.keyQuota(32));
			assertFalse(tracker.isExhausted(7L));
			assertTrue(tracker.canInsertNow(7L, 32));
		}
		assertFalse(tracker.isExpensiveNetwork());
	}

	@Test
	void mediumCostNetworkShrinksKeyQuota() {
		Ae2InsertCostTracker tracker = new Ae2InsertCostTracker();
		for (int i = 0; i < 32; i++) tracker.record(10L, MEDIUM_COST);
		assertTrue(tracker.isExpensiveNetwork());
		int quota = tracker.keyQuota(32);
		assertTrue(quota >= 1 && quota < 32, "中等昂贵网络应收缩配额，实际 " + quota);
		// 1.5ms / 300µs = 5
		assertEquals(5, quota);
	}

	@Test
	void fsyncNetworkFallsBackToSingleKey() {
		Ae2InsertCostTracker tracker = new Ae2InsertCostTracker();
		for (int i = 0; i < 32; i++) tracker.record(11L, FSYNC_COST);
		assertEquals(1, tracker.keyQuota(32));
		assertTrue(tracker.isExhausted(11L));
	}

	@Test
	void expensiveTickBudgetResetsOnNextTick() {
		Ae2InsertCostTracker tracker = new Ae2InsertCostTracker();
		for (int i = 0; i < 4; i++) tracker.record(20L, FSYNC_COST);
		assertTrue(tracker.isExhausted(20L));
		// 下一刻预算重置：物品留原槽后能继续推送，不会永久饥饿
		assertFalse(tracker.isExhausted(21L));
	}

	@Test
	void averageRecoversAfterNetworkBecomesHealthy() {
		Ae2InsertCostTracker tracker = new Ae2InsertCostTracker();
		for (int i = 0; i < 16; i++) tracker.record(30L, MEDIUM_COST);
		assertTrue(tracker.isExpensiveNetwork());
		// 玩家换掉昂贵存储元件后，EWMA 收敛回健康区间并恢复满配额
		for (int i = 0; i < 40; i++) tracker.record(31L + i, HEALTHY_COST);
		assertFalse(tracker.isExpensiveNetwork());
		assertEquals(32, tracker.keyQuota(32));
	}

	@Test
	void resetClearsLearnedCost() {
		Ae2InsertCostTracker tracker = new Ae2InsertCostTracker();
		for (int i = 0; i < 16; i++) tracker.record(40L, FSYNC_COST);
		assertEquals(1, tracker.keyQuota(32));
		tracker.reset();
		assertEquals(32, tracker.keyQuota(32));
		assertEquals(0L, tracker.averageCostNanos());
	}
}
