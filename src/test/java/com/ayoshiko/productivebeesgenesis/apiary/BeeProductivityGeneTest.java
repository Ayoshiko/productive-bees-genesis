package com.ayoshiko.productivebeesgenesis.apiary;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BeeProductivityGeneTest {

	@Test
	void mapsProductiveBeesSerializedValuesToLevels() {
		assertEquals(0, BeeProductivityGene.levelFromSerializedName(null));
		assertEquals(0, BeeProductivityGene.levelFromSerializedName("unknown"));
		assertEquals(0, BeeProductivityGene.levelFromSerializedName("productivity.normal"));
		assertEquals(1, BeeProductivityGene.levelFromSerializedName("productivity.medium"));
		assertEquals(2, BeeProductivityGene.levelFromSerializedName("productivity.high"));
		assertEquals(3, BeeProductivityGene.levelFromSerializedName("productivity.very_high"));
	}

	@Test
	void appliesProductiveBeesSingleStackFormulaExactly() {
		assertEquals(1, BeeProductivityGene.adjustStackCount(1, 0));
		assertEquals(2, BeeProductivityGene.adjustStackCount(1, 1));
		assertEquals(3, BeeProductivityGene.adjustStackCount(1, 2));
		assertEquals(4, BeeProductivityGene.adjustStackCount(1, 3));

		assertEquals(5, BeeProductivityGene.adjustStackCount(2, 1));
		assertEquals(6, BeeProductivityGene.adjustStackCount(2, 2));
		assertEquals(6, BeeProductivityGene.adjustStackCount(2, 3));
		assertEquals(13, BeeProductivityGene.adjustStackCount(4, 3));
	}

	@Test
	void fixedCountBatchKeepsPerStackRounding() {
		long adjusted = BeeProduceBatchSampler.sampleGeneAdjustedSum(
				ThreadLocalRandom.current(), 1, 1, 100L, 3);

		assertEquals(400L, adjusted);
	}

	@Test
	void integerUpgradeMultiplierBecomesExactAdditionalRolls() {
		long rolls = BeeProduceBatchSampler.sampleRollCount(
				ThreadLocalRandom.current(), 100, 3.0F);

		assertEquals(300, rolls);
	}

	@Test
	void highestTier1024xBatchKeepsPerStackGeneRoundingExact() {
		long eventsPerLevel = 15L * 1_024L;
		long total = 0L;
		for (int level = BeeProductivityGene.NORMAL;
				level <= BeeProductivityGene.VERY_HIGH; level++) {
			total += BeeProduceBatchSampler.sampleGeneAdjustedSum(
					ThreadLocalRandom.current(), 1, 1, eventsPerLevel, level);
		}

		// 60 slots split evenly across all four gene levels: (1 + 2 + 3 + 4) * 15 * 1024.
		assertEquals(153_600L, total);
	}

	@Test
	void totalsAboveIntLimitRemainExact() {
		assertEquals(4_096_000_000L, BeeProduceBatchSampler.sampleGeneAdjustedSum(
				new java.util.Random(1L), 1_000_000, 1_000_000, 4_096, BeeProductivityGene.NORMAL));
		assertEquals(4_294_967_296L, BeeProduceBatchSampler.sampleRollCount(
				new java.util.Random(1L), 2_147_483_648L, 2.0F));
	}

	@Test
	void veryHighVariableBatchStaysOnPossiblePerStackTotals() {
		long total = BeeProduceBatchSampler.sampleGeneAdjustedSum(
				new java.util.Random(42L), 2, 3, 33, BeeProductivityGene.VERY_HIGH);
		assertTrue(total >= 198 && total <= 330);
		assertEquals(0, (total - 198) % 4);
	}

	@Test
	void fullOmegaUpgradeBatchSamplesRollsWithoutPerTickExpansion() {
		int productionEvents = 60 * 1_024;
		long rolls = BeeProduceBatchSampler.sampleRollCount(
				ThreadLocalRandom.current(), productionEvents, 11.4F);

		assertTrue(rolls >= productionEvents * 11);
		assertTrue(rolls <= productionEvents * 12);
	}
}
