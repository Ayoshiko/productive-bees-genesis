package com.ayoshiko.productivebeesgenesis.apiary;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ApiaryUpgradeFormulaTest {

	@Test
	void fullMekSpeedAndTimeUpgradesMatchLegacyTwentySixTicks() {
		float mekMultiplier = ApiaryUpgradeMath.computeMekSpeedTimeMultiplier(32, 32, 10.0f);
		int effectiveTimeUpgrades = 8 + 8 * 2;
		float combinedMultiplier = mekMultiplier / (1.0f + 0.15f * effectiveTimeUpgrades);

		assertEquals(0.1f, mekMultiplier, 0.000_001f);
		assertEquals(26, Math.round(1_200 * combinedMultiplier));
	}

	@ParameterizedTest(name = "{0} apiary: {1} FE/t per slot and {2} slots")
	@MethodSource("factoryEnergyTable")
	void factoryTiersScaleEnergyBySlotsAnd256Acceleration(String tier, long baseEnergy, int slots) {
		assertEquals(baseEnergy, BeeSlotTickProcessor.calculateBeeEnergyCost(baseEnergy));
		assertEquals(baseEnergy * slots,
				BeeSlotTickProcessor.calculateBatchEnergyCost(baseEnergy, slots, 1));
		assertEquals(baseEnergy * slots * 256L,
				BeeSlotTickProcessor.calculateBatchEnergyCost(baseEnergy, slots, 256));
	}

	@Test
	void speedAndEnergyUpgradesUseMekanismExponent() {
		assertEquals(100.0f,
				ApiaryUpgradeMath.computeMekSpeedEnergyMultiplier(8, 0, 8, 10.0f), 0.000_001f);
		assertEquals(10.0f,
				ApiaryUpgradeMath.computeMekSpeedEnergyMultiplier(8, 8, 8, 10.0f), 0.000_001f);
		assertEquals(0.1f,
				ApiaryUpgradeMath.computeMekSpeedEnergyMultiplier(0, 8, 8, 10.0f), 0.000_001f);

		// MachineEnergyContainer uses ceil(base * multiplier), so energy upgrades
		// never undercharge a fractional FE result.
		assertEquals(2L, (long) Math.ceil(20L * 0.1f));
		assertEquals(200L, (long) Math.ceil(20L * 10.0f));
	}

	private static Stream<Arguments> factoryEnergyTable() {
		return Stream.of(
				Arguments.of("normal", 50L, 1),
				Arguments.of("basic", 20L, 5),
				Arguments.of("advanced", 22L, 10),
				Arguments.of("elite", 25L, 15),
				Arguments.of("ultimate", 30L, 20));
	}
}
