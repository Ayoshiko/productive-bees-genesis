package com.ayoshiko.productivebeesgenesis.mek;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

class MekCentrifugeEnergyScalingTest {

	@Test
	void smallParallelismKeepsLinearMekanismPricing() {
		assertEquals(39_520L,
				MekCentrifugeEnergyScaling.requiredEnergyPerTick(130L, 1, 16, 19, 1));
		assertEquals(16L, MekCentrifugeEnergyScaling.billableOperations(16L));
		assertEquals(17L, MekCentrifugeEnergyScaling.billableOperations(17L));
	}

	@Test
	void everyParallelDoublingAddsOneBillableOperation() {
		assertEquals(21L, MekCentrifugeEnergyScaling.billableOperations(480L));
		assertEquals(22L, MekCentrifugeEnergyScaling.billableOperations(960L));
	}

	@Test
	void fullCustomFactoryHasExactOptimizedPeakWithoutHardCap() {
		// Built-in balance: configured 50 FE/t becomes 10 FE/t.
		// Mekanism Unleashed: 10 * 10^((2 * 32 - min(32, max(8, 32))) / 8) = 100,000 FE/op.
		// CUSTOM: STACK 16 => 65,536,
		// all four PB productivity tiers at 8 each => 480, EM creative factory => 19 lanes.
		assertEquals(31_457_280L, 65_536L * 480L);
		assertEquals(37L, MekCentrifugeEnergyScaling.billableOperations(31_457_280L));
		long optimizedPeak = MekCentrifugeEnergyScaling.requiredEnergyPerTick(
				100_000L, 65_536, 480, 19, 1);

		assertEquals(70_300_000L, optimizedPeak);
		assertTrue(optimizedPeak > 64_000_000L);
		assertTrue(optimizedPeak < 100_000_000L);
		assertEquals(59_768_832_000_000L, 100_000L * 65_536L * 480L * 19L);
		long legacyConfiguredFullUpgradeCapacity =
				MekCentrifugeEnergyScaling.balancedBaseCapacity(20_000L) * 10_000L;
		assertTrue(legacyConfiguredFullUpgradeCapacity >= optimizedPeak);
	}

	@Test
	void smeltingCompatibilityUsesTheSameMarginalStackPricing() {
		long perLane = MekCentrifugeEnergyScaling.parallelEnergyCost(100_000L, 65_536L);

		assertEquals(2_800_000L, perLane);
		assertEquals(53_200_000L, perLane * 19L);
	}

	@ParameterizedTest(name = "{0}, STACK {1}: {4} FE/t")
	@MethodSource("fullFactoryEnergyConfigurations")
	void fullFactoryEnergyConfigurations(String upgradeConfiguration, int stackLevel,
			long energyPerOperation, long expectedBillableOperations, long expectedPeak) {
		int stackOperations = stackLevel == 0 ? 1 : 1 << stackLevel;
		long actualOperations = (long) stackOperations * 480L;

		assertEquals(expectedBillableOperations,
				MekCentrifugeEnergyScaling.billableOperations(actualOperations));
		assertEquals(expectedPeak, MekCentrifugeEnergyScaling.requiredEnergyPerTick(
				energyPerOperation, stackOperations, 480, 19, 1));
	}

	private static Stream<Arguments> fullFactoryEnergyConfigurations() {
		return Stream.of(
				Arguments.of("8 SPEED + 8 ENERGY", 0, 100L, 21L, 39_900L),
				Arguments.of("16 SPEED + 16 ENERGY", 0, 1_000L, 21L, 399_000L),
				Arguments.of("32 SPEED + 32 ENERGY", 0, 100_000L, 21L, 39_900_000L),
				Arguments.of("8 SPEED + 8 ENERGY", 8, 100L, 29L, 55_100L),
				Arguments.of("16 SPEED + 16 ENERGY", 8, 1_000L, 29L, 551_000L),
				Arguments.of("32 SPEED + 32 ENERGY", 8, 100_000L, 29L, 55_100_000L),
				Arguments.of("8 SPEED + 8 ENERGY", 16, 100L, 37L, 70_300L),
				Arguments.of("16 SPEED + 16 ENERGY", 16, 1_000L, 37L, 703_000L),
				Arguments.of("32 SPEED + 32 ENERGY", 16, 100_000L, 37L, 70_300_000L));
	}

	@Test
	void extremeConfiguredDemandSaturates() {
		assertEquals(Long.MAX_VALUE, MekCentrifugeEnergyScaling.requiredEnergyPerTick(
				Long.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, 256));
		assertEquals(0L, MekCentrifugeEnergyScaling.requiredEnergyPerTick(0L, 1, 1, 1, 256));
	}

	@Test
	void configuredBaseEnergyAndCapacityAreReducedWithoutDroppingBelowOne() {
		assertEquals(10L, MekCentrifugeEnergyScaling.balancedBaseEnergyPerTick(50L));
		assertEquals(50_000L, MekCentrifugeEnergyScaling.balancedBaseCapacity(100_000L));
		assertEquals(1L, MekCentrifugeEnergyScaling.balancedBaseEnergyPerTick(1L));
		assertEquals(4_611_686_018_427_387_904L,
				MekCentrifugeEnergyScaling.balancedBaseCapacity(Long.MAX_VALUE));
		assertEquals(1_844_674_407_370_955_162L,
				MekCentrifugeEnergyScaling.balancedBaseEnergyPerTick(Long.MAX_VALUE));
	}

	@Test
	void capacityDependsOnlyOnRegisteredBaseAndCurrentEnergyUpgrades() {
		long baseCapacity = 50_000L;

		assertEquals(500_000_000L,
				MekCentrifugeEnergyScaling.normalCapacity(baseCapacity, 500_000_000L));
		assertEquals(baseCapacity,
				MekCentrifugeEnergyScaling.normalCapacity(baseCapacity, 1L));
	}

	@Test
	void cachedRecipeBatchChargeSaturatesInsteadOfWrappingNegative() {
		assertEquals(Long.MAX_VALUE, MekCentrifugeEnergyScaling.batchEnergyCost(
				Long.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE));
		assertEquals(51_000L, MekCentrifugeEnergyScaling.batchEnergyCost(100L, 20, 30));
	}

	@Test
	void affordableOperationsInvertsHighParallelPricing() {
		long fullCost = MekCentrifugeEnergyScaling.parallelEnergyCost(130_000L, 31_457_280L);

		assertEquals(4_810_000L, fullCost);
		assertEquals(31_457_280, MekCentrifugeEnergyScaling.affordableOperations(
				130_000L, 31_457_280, fullCost));
		assertTrue(MekCentrifugeEnergyScaling.affordableOperations(
				130_000L, 31_457_280, fullCost - 130_000L) < 31_457_280);
	}
}
