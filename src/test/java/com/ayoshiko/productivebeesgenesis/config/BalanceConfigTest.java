package com.ayoshiko.productivebeesgenesis.config;

import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeType;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BalanceConfigTest {

	private static final PbUpgradeType[] PRODUCTIVITY_TYPES = {
			PbUpgradeType.PRODUCTIVITY,
			PbUpgradeType.PRODUCTIVITY_2,
			PbUpgradeType.PRODUCTIVITY_3,
			PbUpgradeType.PRODUCTIVITY_4
	};

	@Test
	void newInstallDefaultsToBasicBehavior() {
		BalanceConfig.Rules rules = BalanceConfig.Rules.basic();

		assertEquals(BalancePreset.BASIC, BalanceConfig.DEFAULT_PRESET);
		assertEquals("productivebeesgenesis.configuration.balance.profile.BASIC",
				BalancePreset.BASIC.getTranslationKey());
		assertEquals("productivebeesgenesis.configuration.balance.profile.BASIC.tooltip",
				BalancePreset.BASIC.getTooltipKey());
		assertEquals(BalancePreset.BASIC, rules.preset());
		assertTrue(rules.productivityTiersExclusive());
		assertTrue(rules.speedTiersExclusive());
		assertFalse(rules.centrifugeProductivityAffectsOutput());
		assertEquals(4, BalanceConfig.DEFAULT_CONFIGURED_PB_UPGRADE_LIMIT);
		assertEquals(8, BalanceConfig.DEFAULT_CONFIGURED_STACK_UPGRADE_LIMIT);
	}

	@Test
	void namedProfilesResolveEveryBalanceRuleTogether() {
		BalanceConfig.Rules basic = BalanceConfig.resolve(BalancePreset.BASIC, false, false, true);
		BalanceConfig.Rules paradox = BalanceConfig.resolve(
				BalancePreset.PARADOX_INFINITY, true, true, false);

		assertTrue(basic.productivityTiersExclusive());
		assertTrue(basic.speedTiersExclusive());
		assertFalse(basic.centrifugeProductivityAffectsOutput());
		assertFalse(paradox.productivityTiersExclusive());
		assertFalse(paradox.speedTiersExclusive());
		assertTrue(paradox.centrifugeProductivityAffectsOutput());
	}

	@Test
	void customProfileUsesIndividualRuleValues() {
		BalanceConfig.Rules custom = BalanceConfig.resolve(BalancePreset.CUSTOM, true, false, false);

		assertTrue(custom.productivityTiersExclusive());
		assertFalse(custom.speedTiersExclusive());
		assertFalse(custom.centrifugeProductivityAffectsOutput());
	}

	@Test
	void basicToCustomCopiesEveryEffectiveSetting() {
		BalanceConfig.CustomSettings configured = new BalanceConfig.CustomSettings(
				false, false, true,
				8, 2, 12, 3, 24);

		BalanceConfig.CustomSettings inherited =
				BalanceConfig.settingsForPreset(BalancePreset.BASIC, configured);

		assertEquals(new BalanceConfig.CustomSettings(
				true, true, false,
				4, 2, 4, 3, 8), inherited);
	}

	@Test
	void paradoxToCustomCopiesEveryEffectiveSetting() {
		BalanceConfig.CustomSettings configured = new BalanceConfig.CustomSettings(
				true, true, false,
				4, 12, 2, 20, 8);

		BalanceConfig.CustomSettings inherited =
				BalanceConfig.settingsForPreset(BalancePreset.PARADOX_INFINITY, configured);

		assertEquals(new BalanceConfig.CustomSettings(
				false, false, true,
				8, 12, 8, 20, 16), inherited);
	}

	@Test
	void customSettingsRemainUntouchedWhenAlreadyCustom() {
		BalanceConfig.CustomSettings configured = new BalanceConfig.CustomSettings(
				false, true, false,
				2, 7, 3, 9, 24);

		assertEquals(configured,
				BalanceConfig.settingsForPreset(BalancePreset.CUSTOM, configured));
	}

	@Test
	void namedProfileMirrorsEffectiveValuesForOfflineCustomSwitch() {
		BalanceConfig.CustomSettings configured = new BalanceConfig.CustomSettings(
				false, false, true,
				8, 2, 12, 3, 24);

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
				true, true, false,
				4, 12, 2, 20, 8);

		assertEquals(
				BalanceConfig.settingsForPreset(BalancePreset.PARADOX_INFINITY, configured),
				BalanceConfig.settingsToPersist(
						true, true, BalancePreset.CUSTOM,
						BalancePreset.PARADOX_INFINITY, configured));
	}

	@Test
	void customStartupAndCustomReloadDoNotRewriteSettings() {
		BalanceConfig.CustomSettings configured = new BalanceConfig.CustomSettings(
				false, true, false,
				2, 7, 3, 9, 24);

		assertEquals(configured, BalanceConfig.settingsToPersist(
				false, false, BalancePreset.CUSTOM, BalancePreset.BASIC, configured));
		assertEquals(configured, BalanceConfig.settingsToPersist(
				true, true, BalancePreset.CUSTOM, BalancePreset.CUSTOM, configured));
	}

	@Test
	void basicRejectsEveryMixedProductivityPairButAllowsSameTier() {
		BalanceConfig.Rules basic = BalanceConfig.Rules.basic();
		for (PbUpgradeType installedType : PRODUCTIVITY_TYPES) {
			Map<PbUpgradeType, Integer> installed = new EnumMap<>(PbUpgradeType.class);
			installed.put(installedType, 3);
			assertTrue(BalanceConfig.canInstall(installedType, installed, basic));
			for (PbUpgradeType candidate : PRODUCTIVITY_TYPES) {
				if (candidate != installedType) {
					assertFalse(BalanceConfig.canInstall(candidate, installed, basic),
							installedType + " should conflict with " + candidate);
				}
			}
			assertEquals(3, installed.get(installedType));
		}
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

	@Test
	void profileLimitsMapConfiguredValuesWithoutChangingThem() {
		assertEquals(4, BalanceConfig.pbUpgradeLimit(BalancePreset.BASIC, 8));
		assertEquals(8, BalanceConfig.pbUpgradeLimit(BalancePreset.PARADOX_INFINITY, 4));
		assertEquals(2, BalanceConfig.pbUpgradeLimit(BalancePreset.CUSTOM, 2));
		assertEquals(12, BalanceConfig.pbUpgradeLimit(BalancePreset.CUSTOM, 12));

		assertEquals(8, BalanceConfig.centrifugeStackLimit(BalancePreset.BASIC, 16));
		assertEquals(16, BalanceConfig.centrifugeStackLimit(BalancePreset.PARADOX_INFINITY, 8));
		assertEquals(8, BalanceConfig.centrifugeStackLimit(BalancePreset.CUSTOM, 8));
		assertEquals(24, BalanceConfig.centrifugeStackLimit(BalancePreset.CUSTOM, 24));
	}
}
