package com.ayoshiko.productivebeesgenesis.config;

import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeType;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BalanceConfigTest {

	@Test
	void namedProfileMirrorsEffectiveValuesForOfflineCustomSwitch() {
		BalanceConfig.CustomSettings configured = new BalanceConfig.CustomSettings(
				false, false, true, false,
				8, 2, 12, 3, 24, true);

		assertEquals(
				BalanceConfig.settingsForPreset(BalancePreset.BASIC, configured),
				BalanceConfig.settingsToPersist(
						false, false, BalancePreset.BASIC, BalancePreset.BASIC, configured));
		assertEquals(
				BalanceConfig.settingsForPreset(BalancePreset.PARADOX_INFINITY, configured),
				BalanceConfig.settingsToPersist(
						false, false, BalancePreset.PARADOX_INFINITY,
						BalancePreset.BASIC, configured));
	}

	@Test
	void liveNamedToCustomTransitionCopiesPreviousEffectiveValues() {
		BalanceConfig.CustomSettings configured = new BalanceConfig.CustomSettings(
				true, true, false, true,
				4, 12, 2, 20, 8, false);

		assertEquals(
				BalanceConfig.settingsForPreset(BalancePreset.PARADOX_INFINITY, configured),
				BalanceConfig.settingsToPersist(
						true, true, BalancePreset.CUSTOM,
						BalancePreset.PARADOX_INFINITY, configured));
	}

	@Test
	void customStartupAndCustomReloadDoNotRewriteSettings() {
		BalanceConfig.CustomSettings configured = new BalanceConfig.CustomSettings(
				false, true, false, false,
				2, 7, 3, 9, 24, true);

		assertEquals(configured, BalanceConfig.settingsToPersist(
				false, false, BalancePreset.CUSTOM, BalancePreset.BASIC, configured));
		assertEquals(configured, BalanceConfig.settingsToPersist(
				true, true, BalancePreset.CUSTOM, BalancePreset.CUSTOM, configured));
	}

	@Test
	void basicRejectsMixedSpeedTiersWithoutMutatingExistingCounts() {
		Map<PbUpgradeType, Integer> installed = new EnumMap<>(PbUpgradeType.class);
		installed.put(PbUpgradeType.TIME, 8);

		assertFalse(BalanceConfig.canInstall(
				PbUpgradeType.TIME_2, installed, BalanceConfig.Rules.basic()));
		assertEquals(Map.of(PbUpgradeType.TIME, 8), installed);
	}

	@Test
	void existingMixedStateIsPreservedButCannotGrowUntilConflictIsRemoved() {
		Map<PbUpgradeType, Integer> installed = new EnumMap<>(PbUpgradeType.class);
		installed.put(PbUpgradeType.PRODUCTIVITY, 8);
		installed.put(PbUpgradeType.PRODUCTIVITY_2, 8);

		assertFalse(BalanceConfig.canInstall(
				PbUpgradeType.PRODUCTIVITY, installed, BalanceConfig.Rules.basic()));
		assertFalse(BalanceConfig.canInstall(
				PbUpgradeType.PRODUCTIVITY_2, installed, BalanceConfig.Rules.basic()));
		assertEquals(8, installed.get(PbUpgradeType.PRODUCTIVITY));
		assertEquals(8, installed.get(PbUpgradeType.PRODUCTIVITY_2));
	}

	@Test
	void paradoxAllowsLegacyMixedUpgradeTiers() {
		Map<PbUpgradeType, Integer> installed = new EnumMap<>(PbUpgradeType.class);
		installed.put(PbUpgradeType.PRODUCTIVITY, 8);
		installed.put(PbUpgradeType.TIME, 8);

		assertTrue(BalanceConfig.canInstall(
				PbUpgradeType.PRODUCTIVITY_4, installed, BalanceConfig.Rules.paradoxInfinity()));
		assertTrue(BalanceConfig.canInstall(
				PbUpgradeType.TIME_2, installed, BalanceConfig.Rules.paradoxInfinity()));
	}

}
