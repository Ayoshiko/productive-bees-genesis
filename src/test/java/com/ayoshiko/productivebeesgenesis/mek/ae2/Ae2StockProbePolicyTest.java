package com.ayoshiko.productivebeesgenesis.mek.ae2;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link Ae2StockProbePolicy} 行为测试（纯 Java，用 String 充当存储键）
 * <br/>
 * 核心不变式：健康网络永不降级；昂贵网络只保留「真正需要探针」的键。
 */
class Ae2StockProbePolicyTest {

	/** 普通 AE2 存储的 SIMULATE extract 量级。 */
	private static final long HEALTHY_COST = 20_000L;
	/** megacells 大宗盘量级（压缩链线性扫描）。 */
	private static final long BULK_CELL_COST = 400_000L;

	@Test
	void healthyNetworkAlwaysProbes() {
		Ae2StockProbePolicy<String> policy = new Ae2StockProbePolicy<>();
		for (int i = 0; i < 500; i++) {
			assertTrue(policy.shouldProbe(7L, "key" + i));
			policy.record(7L, "key" + i, HEALTHY_COST, 100L, 100L);
		}
		assertFalse(policy.isExpensiveNetwork());
		assertTrue(policy.shouldProbe(7L, "keyNew"));
	}

	@Test
	void expensiveNetworkSkipsKeysWithoutPlaceholderStock() {
		Ae2StockProbePolicy<String> policy = new Ae2StockProbePolicy<>();
		// 第 0 tick 是学习窗口：先把成本与结论喂进去
		policy.shouldProbe(0L, "plain");
		policy.record(0L, "plain", BULK_CELL_COST, 500L, 500L);
		for (int i = 0; i < 8; i++) {
			policy.record(0L, "plain", BULK_CELL_COST, 500L, 500L);
		}
		assertTrue(policy.isExpensiveNetwork());
		// 跳到非重探窗口：已知且无占位差异的键不再探测
		assertFalse(policy.shouldProbe(1L, "plain"));
	}

	@Test
	void expensiveNetworkKeepsProbingPlaceholderStorageKeys() {
		Ae2StockProbePolicy<String> policy = new Ae2StockProbePolicy<>();
		policy.shouldProbe(0L, "infinite");
		for (int i = 0; i < 8; i++) {
			// 模拟量高于上报量 = 占位/无限存储，必须继续探针
			policy.record(0L, "infinite", BULK_CELL_COST, 1L, 1_000_000L);
		}
		assertTrue(policy.isExpensiveNetwork());
		assertTrue(policy.shouldProbe(1L, "infinite"));
	}

	@Test
	void expensiveNetworkStillProbesUnseenKeyOnce() {
		Ae2StockProbePolicy<String> policy = new Ae2StockProbePolicy<>();
		for (int i = 0; i < 8; i++) {
			policy.record(0L, "plain", BULK_CELL_COST, 500L, 500L);
		}
		assertTrue(policy.isExpensiveNetwork());
		assertTrue(policy.shouldProbe(1L, "brandNew"));
	}

	@Test
	void tickBudgetStopsRunawayProbing() {
		Ae2StockProbePolicy<String> policy = new Ae2StockProbePolicy<>();
		for (int i = 0; i < 8; i++) {
			policy.record(0L, "plain", BULK_CELL_COST, 500L, 500L);
		}
		// 非重探窗口内堆满 1ms 预算后，连未知键也不再探测
		long tick = 1L;
		for (int i = 0; i < 10; i++) {
			policy.record(tick, "worthy" + i, BULK_CELL_COST, 1L, 10L);
		}
		assertFalse(policy.shouldProbe(tick, "anotherNew"));
		// 下一 tick 预算复位
		assertTrue(policy.shouldProbe(tick + 1L, "anotherNew"));
	}

	@Test
	void relearnWindowReopensFullProbingPeriodically() {
		Ae2StockProbePolicy<String> policy = new Ae2StockProbePolicy<>();
		policy.shouldProbe(0L, "plain");
		for (int i = 0; i < 8; i++) {
			policy.record(0L, "plain", BULK_CELL_COST, 500L, 500L);
		}
		assertFalse(policy.shouldProbe(1L, "plain"));
		// 100 tick 后放开一次全量重探，玩家换掉昂贵元件即可自动恢复
		assertTrue(policy.shouldProbe(100L, "plain"));
	}

	@Test
	void resetClearsLearnedState() {
		Ae2StockProbePolicy<String> policy = new Ae2StockProbePolicy<>();
		for (int i = 0; i < 8; i++) {
			policy.record(0L, "plain", BULK_CELL_COST, 500L, 500L);
		}
		assertTrue(policy.isExpensiveNetwork());
		policy.reset();
		assertFalse(policy.isExpensiveNetwork());
		assertTrue(policy.shouldProbe(1L, "plain"));
	}
}
