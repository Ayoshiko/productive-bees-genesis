package com.ayoshiko.productivebeesgenesis.mek.ae2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 输入预算的隔离、恢复、高倍加速与计费接线回归测试。 */
class Ae2ExtractBudgetTest {

	/** 健康 extract：80µs */
	private static final long HEALTHY_COST = 80_000L;
	/** WAL fsync 型 extract（低于 5ms 病态阈值，逐个逃过既有闸门）：800µs */
	private static final long FSYNC_COST = 800_000L;
	/** 极慢 fsync extract：3ms */
	private static final long SLOW_FSYNC_COST = 3_000_000L;

	@Test
	void noSamplesAllowsAllTypes() {
		Ae2ExtractBudget budget = new Ae2ExtractBudget();
		assertEquals(38, budget.keyQuota(38));
		assertTrue(budget.canExtractNow(100L, 38));
		assertFalse(budget.isExpensiveNetwork());
		assertEquals(0L, budget.averageCostNanos());
	}

	@Test
	void typicalHealthyBatchKeepsItsQuota() {
		Ae2ExtractBudget budget = new Ae2ExtractBudget();
		// 单 tick 内连续抽取 38 种健康类型（高等级满插件离心机的典型上限）仍全程放行。
		for (int i = 0; i < 38; i++) {
			assertTrue(budget.canExtractNow(200L, 38), "健康网络第 " + i + " 次仍应放行");
			budget.record(200L, HEALTHY_COST);
		}
		assertEquals(38, budget.keyQuota(38));
		assertFalse(budget.isExpensiveNetwork());
	}

	@Test
	void fsyncNetworkShrinksTypeQuota() {
		Ae2ExtractBudget budget = new Ae2ExtractBudget();
		for (int i = 0; i < 38; i++) budget.record(300L, FSYNC_COST);
		assertTrue(budget.isExpensiveNetwork());
		// 2ms / 800µs = 2
		assertEquals(2, budget.keyQuota(38), "fsync 网络应收缩类型配额");
	}

	@Test
	void slowFsyncFallsBackToFewTypes() {
		Ae2ExtractBudget budget = new Ae2ExtractBudget();
		for (int i = 0; i < 16; i++) budget.record(400L, SLOW_FSYNC_COST);
		// 2ms / 3ms 向下取整为 0，钳到最小 1 键，绝不停机。
		assertEquals(1, budget.keyQuota(38));
	}

	@Test
	void tileDisasterCeilingCapsBorderlineBurst() {
		Ae2ExtractBudget budget = new Ae2ExtractBudget();
		// 单次成本恰在健康阈值内（不触发配额收缩），仅靠单 tile 灾难上限(8ms)封顶：
		// 250µs × n ≥ 8ms → n ≈ 32。这验证「均值健康但类型极多」时仍有兜底。
		long borderlineCost = 250_000L;
		int allowed = 0;
		for (int i = 0; i < 80; i++) {
			if (!budget.canExtractNow(500L, 80)) break;
			allowed++;
			budget.record(500L, borderlineCost);
		}
		assertFalse(budget.isExpensiveNetwork(), "均值应仍判为健康");
		assertTrue(allowed >= 28 && allowed <= 34, "应由单 tile 灾难上限封顶，实际放行 " + allowed);
	}

	@Test
	void budgetResetsOnNextTick() {
		Ae2ExtractBudget budget = new Ae2ExtractBudget();
		for (int i = 0; i < 38; i++) budget.record(600L, SLOW_FSYNC_COST);
		assertFalse(budget.canExtractNow(600L, 38));
		// 下一刻预算重置：剩余类型能继续抽取，不会永久饥饿。
		assertTrue(budget.canExtractNow(601L, 38));
	}

	@Test
	void averageRecoversAfterNetworkBecomesHealthy() {
		Ae2ExtractBudget budget = new Ae2ExtractBudget();
		for (int i = 0; i < 16; i++) budget.record(700L, FSYNC_COST);
		assertTrue(budget.isExpensiveNetwork());
		// 玩家把无限存储换成普通元件后，EWMA 收敛回健康区间并恢复满配额。
		for (int i = 0; i < 48; i++) budget.record(701L + i, HEALTHY_COST);
		assertFalse(budget.isExpensiveNetwork());
		assertEquals(38, budget.keyQuota(38));
	}

	@Test
	void resetClearsLearnedCost() {
		Ae2ExtractBudget budget = new Ae2ExtractBudget();
		for (int i = 0; i < 16; i++) budget.record(900L, SLOW_FSYNC_COST);
		assertEquals(1, budget.keyQuota(38));
		budget.reset();
		assertEquals(38, budget.keyQuota(38));
		assertEquals(0L, budget.averageCostNanos());
		assertFalse(budget.isExpensiveNetwork());
	}

	@Test
	void combinedCeilingCountsInsertDiskTime() {
		Ae2ExtractBudget budget = new Ae2ExtractBudget();
		long insertSpent = 10_000_000L;
		assertTrue(budget.canExtractNow(1000L, 38, insertSpent),
				"慢输出不能阻止本刻第一次输入尝试");
		budget.record(1000L, HEALTHY_COST);
		assertFalse(budget.canExtractNow(1000L, 38, insertSpent),
				"输入已经获得推进机会后，合计成本耗尽应停止后续提取");
		// 无 insert 占用时同一冷启动 tick 正常放行（合计上限不误伤纯拉取负载）。
		assertTrue(budget.canExtractNow(1001L, 38, 0L));
	}

	@Test
	void combinedCeilingDoesNotThrottleHealthyDualDirection() {
		// 健康快速网络：insert 与 extract 单侧都只花数百 µs，合计远低于 10ms，全程放行。
		Ae2ExtractBudget budget = new Ae2ExtractBudget();
		long healthyInsertSpent = 300_000L; // 0.3ms
		for (int i = 0; i < 38; i++) {
			assertTrue(budget.canExtractNow(1100L, 38, healthyInsertSpent),
					"健康双向网络第 " + i + " 次仍应放行");
			budget.record(1100L, HEALTHY_COST);
		}
		assertFalse(budget.isExpensiveNetwork());
	}

	@Test
	void unrelatedMachinesDoNotConsumeEachOthersBudget() {
		Ae2ExtractBudget slow = new Ae2ExtractBudget();
		Ae2ExtractBudget healthy = new Ae2ExtractBudget();
		for (long tick = 0; tick < 20; tick++) {
			slow.record(tick, 30_000_000L);
			assertFalse(slow.canExtractNow(tick, 38));
			for (int key = 0; key < 38; key++) {
				assertTrue(healthy.canExtractNow(tick, 38));
				healthy.record(tick, HEALTHY_COST);
			}
		}
	}

	@Test
	void expensiveProbesStopFurtherTypesWithoutDistortingExtractAverage() {
		Ae2ExtractBudget budget = new Ae2ExtractBudget();
		budget.recordProbe(10, 8_000_000L);
		assertFalse(budget.canExtractNow(10, 38));
		assertEquals(0L, budget.averageCostNanos());
		assertTrue(budget.canExtractNow(11, 38));
	}

	@Test
	void repeatedAcceleratedCallsCannotResetBudget() {
		Ae2ExtractBudget budget = new Ae2ExtractBudget();
		budget.record(10, 3_000_000L);
		for (int subTick = 0; subTick < 256; subTick++) {
			assertFalse(budget.canExtractNow(10, 38));
		}
		assertTrue(budget.canExtractNow(11, 38));
	}

	@Test
	void emptyCandidateSetAndOverflowCannotReopenBudget() {
		Ae2ExtractBudget budget = new Ae2ExtractBudget();
		assertFalse(budget.canExtractNow(10, 0));
		assertEquals(0, budget.keyQuota(0));
		budget.recordProbe(10, Long.MAX_VALUE);
		budget.recordProbe(10, 1L);
		assertFalse(budget.canExtractNow(10, 38));
		budget.reset();
		assertTrue(budget.canExtractNow(10, 38));
	}

	@Test
	void zeroReturnsAndExceptionsAreChargedBeforeLeavingTheAttempt() throws Exception {
		String source = Files.readString(Path.of("src/main/java/com/ayoshiko/productivebeesgenesis/"
				+ "mek/ae2/Ae2InputPuller.java"));
		String extract = source.substring(source.indexOf("long extractStart = System.nanoTime()"),
				source.indexOf("Ae2NetworkInventoryView.recordExtract(holder, gameTick"));
		int failure = extract.indexOf("catch (LinkageError | RuntimeException error)");
		int firstCharge = extract.indexOf("extractBudget.record(gameTick, extractCost)");
		int rethrow = extract.indexOf("throw error");
		int successCharge = extract.lastIndexOf("extractBudget.record(gameTick, extractCost)");
		assertTrue(failure < firstCharge && firstCharge < rethrow);
		assertTrue(rethrow < successCharge && successCharge < extract.indexOf("if (extracted <= 0)"));
		assertEquals(2, source.split("extractBudget.recordProbe\\(gameTick, reserveQueryCost\\)", -1).length - 1);
	}
}
