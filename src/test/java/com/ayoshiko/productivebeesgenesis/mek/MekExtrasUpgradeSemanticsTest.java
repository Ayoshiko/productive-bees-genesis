package com.ayoshiko.productivebeesgenesis.mek;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MekExtrasUpgradeSemanticsTest {

	@Test
	void creativeOverridesSpeedAndEnergyUpgrades() {
		int normalTicks = MekExtrasUpgradeSemantics.processingTicks(false, 200, 1.0D);
		int speedTicks = MekExtrasUpgradeSemantics.processingTicks(false, 200, 0.1D);
		int creativeTicks = MekExtrasUpgradeSemantics.processingTicks(true, 200, 0.1D);

		assertEquals(200, normalTicks);
		assertEquals(20, speedTicks);
		assertEquals(0, creativeTicks);
		assertEquals(400L, MekExtrasUpgradeSemantics.energyPerTick(false, 400L));
		assertEquals(0L, MekExtrasUpgradeSemantics.energyPerTick(true, 400L));
		assertEquals(8, MekExtrasUpgradeSemantics.operationsPerTick(false, 1, 8));
		assertEquals(1, MekExtrasUpgradeSemantics.operationsPerTick(true, 1, 8));
		assertEquals(256, MekExtrasUpgradeSemantics.operationsPerTick(true, 256, 2_048));
	}

	@Test
	void nonCreativeProcessingTimeNeverBecomesZero() {
		assertEquals(1, MekExtrasUpgradeSemantics.processingTicks(false, 1, 0.000_001D));
		assertEquals(1, MekExtrasUpgradeSemantics.processingTicks(false, 0, 1.0D));
	}
}
