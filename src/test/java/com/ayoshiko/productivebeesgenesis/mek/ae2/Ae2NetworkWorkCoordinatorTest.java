package com.ayoshiko.productivebeesgenesis.mek.ae2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class Ae2NetworkWorkCoordinatorTest {

	@AfterEach
	void resetCoordinator() {
		Ae2NetworkWorkCoordinator.resetForTest();
	}

	@Test
	void healthyNetworkDoesNotThrottleWorkers() {
		Object network = new Object();
		long first = Ae2NetworkWorkCoordinator.createWorkerId();
		long second = Ae2NetworkWorkCoordinator.createWorkerId();

		assertTrue(Ae2NetworkWorkCoordinator.tryAcquire(network, first, 10L));
		assertTrue(Ae2NetworkWorkCoordinator.tryAcquire(network, second, 10L));
	}

	@Test
	void expensiveNetworkRotatesOneWorkerPerTickWithoutStarvation() {
		Object network = new Object();
		long first = Ae2NetworkWorkCoordinator.createWorkerId();
		long second = Ae2NetworkWorkCoordinator.createWorkerId();
		long third = Ae2NetworkWorkCoordinator.createWorkerId();

		// 先登记所有宿主，再用一次慢操作把网络切换到协调模式。
		assertTrue(Ae2NetworkWorkCoordinator.tryAcquire(network, first, 20L));
		assertTrue(Ae2NetworkWorkCoordinator.tryAcquire(network, second, 20L));
		assertTrue(Ae2NetworkWorkCoordinator.tryAcquire(network, third, 20L));
		Ae2NetworkWorkCoordinator.recordCost(network, 20L, 1_000_000L, 500_000L);

		assertTrue(Ae2NetworkWorkCoordinator.tryAcquire(network, first, 21L));
		assertFalse(Ae2NetworkWorkCoordinator.tryAcquire(network, second, 21L));
		assertFalse(Ae2NetworkWorkCoordinator.tryAcquire(network, third, 21L));

		assertFalse(Ae2NetworkWorkCoordinator.tryAcquire(network, first, 22L));
		assertTrue(Ae2NetworkWorkCoordinator.tryAcquire(network, second, 22L));
		assertFalse(Ae2NetworkWorkCoordinator.tryAcquire(network, third, 22L));

		assertFalse(Ae2NetworkWorkCoordinator.tryAcquire(network, first, 23L));
		assertFalse(Ae2NetworkWorkCoordinator.tryAcquire(network, second, 23L));
		assertTrue(Ae2NetworkWorkCoordinator.tryAcquire(network, third, 23L));
	}

	@Test
	void networkBudgetsAreIndependentAndResetEachTick() {
		Object slowNetwork = new Object();
		Object healthyNetwork = new Object();
		long slowWorker = Ae2NetworkWorkCoordinator.createWorkerId();
		long healthyWorker = Ae2NetworkWorkCoordinator.createWorkerId();

		Ae2NetworkWorkCoordinator.recordCost(slowNetwork, 30L, 3_000_001L, 500_000L);
		assertTrue(Ae2NetworkWorkCoordinator.spentNanosForTest(slowNetwork, 30L) >= 2_000_000L);
		assertFalse(Ae2NetworkWorkCoordinator.tryAcquire(slowNetwork, slowWorker, 30L));
		assertTrue(Ae2NetworkWorkCoordinator.tryAcquire(healthyNetwork, healthyWorker, 30L));
		assertTrue(Ae2NetworkWorkCoordinator.tryAcquire(slowNetwork, slowWorker, 31L));
	}

	@Test
	void workerThatStopsCallingCannotOwnFutureTurns() {
		Object network = new Object();
		long active = Ae2NetworkWorkCoordinator.createWorkerId();
		long vanished = Ae2NetworkWorkCoordinator.createWorkerId();

		assertTrue(Ae2NetworkWorkCoordinator.tryAcquire(network, active, 50L));
		assertTrue(Ae2NetworkWorkCoordinator.tryAcquire(network, vanished, 50L));
		Ae2NetworkWorkCoordinator.recordCost(network, 50L, 1_000_000L, 500_000L);

		assertTrue(Ae2NetworkWorkCoordinator.tryAcquire(network, active, 51L));
		assertTrue(Ae2NetworkWorkCoordinator.tryAcquire(network, active, 52L),
				"上刻已消失的 worker 不得占用本刻网络令牌");
	}

	@Test
	void healthySamplesEventuallyRestoreUnrestrictedAccess() {
		Object network = new Object();
		long first = Ae2NetworkWorkCoordinator.createWorkerId();
		long second = Ae2NetworkWorkCoordinator.createWorkerId();

		Ae2NetworkWorkCoordinator.tryAcquire(network, first, 60L);
		Ae2NetworkWorkCoordinator.tryAcquire(network, second, 60L);
		Ae2NetworkWorkCoordinator.recordCost(network, 60L, 1_000_000L, 500_000L);
		for (int sample = 0; sample < 128; sample++) {
			Ae2NetworkWorkCoordinator.recordCost(network, 61L, 0L, 500_000L);
		}

		assertTrue(Ae2NetworkWorkCoordinator.tryAcquire(network, first, 62L));
		assertTrue(Ae2NetworkWorkCoordinator.tryAcquire(network, second, 62L),
				"网络恢复健康后不应永久保留单宿主限流");
	}

	@Test
	void releasedWorkerLeavesTheRotation() {
		Object network = new Object();
		long first = Ae2NetworkWorkCoordinator.createWorkerId();
		long second = Ae2NetworkWorkCoordinator.createWorkerId();

		Ae2NetworkWorkCoordinator.tryAcquire(network, first, 40L);
		Ae2NetworkWorkCoordinator.tryAcquire(network, second, 40L);
		Ae2NetworkWorkCoordinator.recordCost(network, 40L, 1_000_000L, 500_000L);
		assertTrue(Ae2NetworkWorkCoordinator.tryAcquire(network, first, 41L));
		Ae2NetworkWorkCoordinator.release(network, second);

		assertTrue(Ae2NetworkWorkCoordinator.tryAcquire(network, first, 42L));
	}

	@Test
	void resolvedStateHandleBehavesLikeIdentityLookup() {
		Object network = new Object();
		long worker = Ae2NetworkWorkCoordinator.createWorkerId();
		Object handle = Ae2NetworkWorkCoordinator.resolve(network);

		assertTrue(handle != null, "解析出的状态句柄必须可缓存复用");
		assertTrue(Ae2NetworkWorkCoordinator.tryAcquireResolved(handle, worker, 70L));
		// 句柄路径记录的成本必须与 identity 路径看到的是同一份网络状态
		Ae2NetworkWorkCoordinator.recordResolvedCost(handle, 70L, 3_000_001L, 500_000L);
		assertTrue(Ae2NetworkWorkCoordinator.spentNanosForTest(network, 70L) >= 2_000_000L);
		assertFalse(Ae2NetworkWorkCoordinator.tryAcquire(network, worker, 70L));
		assertTrue(Ae2NetworkWorkCoordinator.tryAcquireResolved(handle, worker, 71L));
	}

	@Test
	void inactiveWorkersAreReapedPeriodicallyWithoutBlockingRotation() {
		Object network = new Object();
		long active = Ae2NetworkWorkCoordinator.createWorkerId();
		long vanished = Ae2NetworkWorkCoordinator.createWorkerId();

		assertTrue(Ae2NetworkWorkCoordinator.tryAcquire(network, active, 100L));
		assertTrue(Ae2NetworkWorkCoordinator.tryAcquire(network, vanished, 100L));
		assertEquals(2, Ae2NetworkWorkCoordinator.workerCountForTest(network));

		// 淘汰已从热路径摘出：只有超过回收周期（20 刻）才扫一次表
		for (long tick = 101L; tick <= 121L; tick++) {
			assertTrue(Ae2NetworkWorkCoordinator.tryAcquire(network, active, tick));
		}
		assertEquals(1, Ae2NetworkWorkCoordinator.workerCountForTest(network),
				"停止调用的宿主条目必须被周期性回收，宿主表不得随历史机器数量增长");
	}
}
