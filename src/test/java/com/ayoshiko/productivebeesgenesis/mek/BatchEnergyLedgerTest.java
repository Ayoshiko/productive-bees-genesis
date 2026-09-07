package com.ayoshiko.productivebeesgenesis.mek;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

/** 批次能量账本的纯逻辑测试，不加载 Minecraft 能量容器。 */
class BatchEnergyLedgerTest {

	@Test
	void activatedScopeIsVisibleOnCurrentThread() {
		BatchEnergyLedger ledger = new BatchEnergyLedger();

		assertNull(BatchEnergyLedger.active());
		try (BatchEnergyLedger.Scope ignored = BatchEnergyLedger.activate(ledger)) {
			assertSame(ledger, BatchEnergyLedger.active());
		}
		assertNull(BatchEnergyLedger.active());
	}

	@Test
	void nestedScopeRestoresOuterLedger() {
		BatchEnergyLedger outer = new BatchEnergyLedger();
		BatchEnergyLedger inner = new BatchEnergyLedger();

		try (BatchEnergyLedger.Scope ignoredOuter = BatchEnergyLedger.activate(outer)) {
			assertSame(outer, BatchEnergyLedger.active());
			try (BatchEnergyLedger.Scope ignoredInner = BatchEnergyLedger.activate(inner)) {
				assertSame(inner, BatchEnergyLedger.active());
			}
			assertSame(outer, BatchEnergyLedger.active());
		}
		assertNull(BatchEnergyLedger.active());
	}

	@Test
	void closingScopeClearsThreadLocalAndIsIdempotent() {
		BatchEnergyLedger ledger = new BatchEnergyLedger();
		BatchEnergyLedger.Scope scope = BatchEnergyLedger.activate(ledger);
		try {
			assertSame(ledger, BatchEnergyLedger.active());
		} finally {
			scope.close();
		}

		assertNull(BatchEnergyLedger.active());
		scope.close();
		assertNull(BatchEnergyLedger.active());
	}

	@Test
	void accumulatesAndFlushesOnce() {
		BatchEnergyLedger ledger = new BatchEnergyLedger();
		ledger.add(10L);
		ledger.add(25L);
		long[] submitted = {0L};

		ledger.flush(amount -> submitted[0] += amount);

		assertEquals(35L, submitted[0]);
		ledger.flush(amount -> submitted[0] += amount);
		assertEquals(35L, submitted[0]);
	}

	@Test
	void availableEnergySubtractsSharedPendingCharge() {
		BatchEnergyLedger ledger = new BatchEnergyLedger();
		ledger.add(70L);

		assertEquals(30L, ledger.available(100L));
		assertEquals(0L, ledger.available(50L));
	}

	@Test
	void additionSaturatesInsteadOfOverflowing() {
		BatchEnergyLedger ledger = new BatchEnergyLedger();
		ledger.add(Long.MAX_VALUE);
		ledger.add(1L);

		assertEquals(0L, ledger.available(Long.MAX_VALUE - 1L));
		long[] submitted = {0L};
		ledger.flush(amount -> submitted[0] = amount);
		assertEquals(Long.MAX_VALUE, submitted[0]);
	}

	@Test
	void sharedLedgerPreventsTwoLanesFromReusingEnergy() {
		BatchEnergyLedger ledger = new BatchEnergyLedger();
		ledger.add(60L);
		long laneOneBudget = ledger.available(100L);
		ledger.add(40L);
		long laneTwoBudget = ledger.available(100L);

		assertEquals(40L, laneOneBudget);
		assertEquals(0L, laneTwoBudget);
	}
}
