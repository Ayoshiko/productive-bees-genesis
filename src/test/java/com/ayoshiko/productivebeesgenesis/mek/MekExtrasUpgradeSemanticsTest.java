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

	@Test
	void runtimeStackCountCannotBypassBasicProfileCap() {
		assertEquals(8, MekExtraUpgradeSupport.cappedStackUpgrades(16, 16));
		assertEquals(8, MekExtraUpgradeSupport.cappedStackUpgrades(32, 32));
		assertEquals(0, MekExtraUpgradeSupport.cappedStackUpgrades(-4, 16));
	}

	@Test
	void runtimeStackCountUsesTheSelectedProfileLimit() {
		assertEquals(8, MekExtraUpgradeSupport.cappedStackUpgrades(
				32, 32, ignored -> 8));
		assertEquals(16, MekExtraUpgradeSupport.cappedStackUpgrades(
				32, 8, configured -> Math.max(16, configured)));
		assertEquals(24, MekExtraUpgradeSupport.cappedStackUpgrades(
				32, 24, configured -> configured));
		assertEquals(32, MekExtraUpgradeSupport.cappedStackUpgrades(
				32, 32, configured -> configured));
	}

	@Test
	void invalidConfigurationFallsBackToConservativeCap() {
		assertEquals(8, MekExtraUpgradeSupport.cappedStackUpgrades(32, -100, null));
		assertEquals(8, MekExtraUpgradeSupport.cappedStackUpgrades(
				32, 32, ignored -> { throw new IllegalStateException("config unavailable"); }));
		assertEquals(8, MekExtraUpgradeSupport.cappedStackUpgrades(
				32, 32, ignored -> -1));
	}

	@Test
	void stackDisplayMultiplierDoesNotWrapAtThirtyTwoLevels() {
		assertEquals(256.0D, MekUpgradeSupport.stackUpgradeMultiplier(8));
		assertEquals(2_147_483_647.0D, MekUpgradeSupport.stackUpgradeMultiplier(32));
		assertEquals(1.0D, MekUpgradeSupport.stackUpgradeMultiplier(-1));
	}
}
