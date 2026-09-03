package com.ayoshiko.productivebeesgenesis.config;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FactoryTierConfigSnapshotTest {

	@Test
	void defaultsMatchTheLegacyTierDefaults() {
		FactoryTierConfigSnapshot snapshot = FactoryTierConfigSnapshot.defaults();
		for (FactoryTierKey tier : FactoryTierKey.values()) {
			assertEquals(tier.centrifugeOutputStackDefault(), snapshot.centrifugeOutputStack(tier));
			assertEquals(tier.centrifugeInputStackDefault(), snapshot.centrifugeInputStack(tier));
			assertEquals(tier.centrifugeFluidTankDefault(), snapshot.centrifugeFluidTank(tier));
			assertEquals(tier.apiaryOutputStackDefault(), snapshot.apiaryOutputStack(tier));
		}
	}

	@Test
	void snapshotReadsEachConfigValueOnceAndThenRemainsStable() {
		AtomicInteger base = new AtomicInteger(1_000);
		AtomicInteger reads = new AtomicInteger();
		ToIntFunction<FactoryTierKey> source = tier -> {
			reads.incrementAndGet();
			return base.get() * tier.parallelProcesses();
		};

		FactoryTierConfigSnapshot snapshot = FactoryTierConfigSnapshot.create(
				source, source, source, source);
		assertEquals(FactoryTierKey.values().length * 4, reads.get());

		base.set(2_000);
		for (FactoryTierKey tier : FactoryTierKey.values()) {
			int expected = 1_000 * tier.parallelProcesses();
			assertEquals(expected, snapshot.centrifugeOutputStack(tier));
			assertEquals(expected, snapshot.centrifugeInputStack(tier));
			assertEquals(expected, snapshot.centrifugeFluidTank(tier));
			assertEquals(expected, snapshot.apiaryOutputStack(tier));
		}
		assertEquals(FactoryTierKey.values().length * 4, reads.get());
	}

	@Test
	void invalidOrderingFallsBackToDefaultsWithoutMutatingTheSource() {
		ToIntFunction<FactoryTierKey> invalidOutput = tier -> switch (tier) {
			case BASIC -> 10;
			case ADVANCED -> 1;
			default -> tier.centrifugeOutputStackDefault();
		};
		FactoryTierConfigSnapshot snapshot = FactoryTierConfigSnapshot.create(
				invalidOutput,
				FactoryTierKey::centrifugeInputStackDefault,
				FactoryTierKey::centrifugeFluidTankDefault,
				FactoryTierKey::apiaryOutputStackDefault);

		assertEquals(FactoryTierKey.BASIC.centrifugeOutputStackDefault(),
				snapshot.centrifugeOutputStack(FactoryTierKey.BASIC));
		assertEquals(FactoryTierKey.ADVANCED.centrifugeOutputStackDefault(),
				snapshot.centrifugeOutputStack(FactoryTierKey.ADVANCED));
		assertEquals(FactoryTierKey.ELITE.centrifugeOutputStackDefault(),
				snapshot.centrifugeOutputStack(FactoryTierKey.ELITE));
	}

	@Test
	void invalidGroupIsRejectedBeforeItCanBePublished() {
		ToIntFunction<FactoryTierKey> invalid = tier -> tier == FactoryTierKey.BASIC ? 0 : 1;
		ToIntFunction<FactoryTierKey> valid = tier -> 1;

		assertThrows(IllegalArgumentException.class,
				() -> FactoryTierConfigSnapshot.create(invalid, valid, valid, valid));
	}
}
