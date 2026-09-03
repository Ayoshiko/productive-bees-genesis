package com.ayoshiko.productivebeesgenesis.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.IntFunction;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FactoryTierConfigCompatibilityTest {

	private static final List<String> LEGACY_KEYS = List.of(
			"basic", "advanced", "elite", "ultimate",
			"meAbsolute", "meSupreme", "meCosmic", "meInfinite",
			"emOverclocked", "emQuantum", "emDense", "emMultiversal", "emCreative",
			"emeAbsoluteOverclocked", "emeSupremeQuantum", "emeCosmicDense",
			"emeInfiniteMultiversal");

	private static final int[] OUTPUT_DEFAULTS = {
			65_536, 327_680, 458_752, 589_824,
			720_896, 851_968, 983_040, 1_114_112,
			720_896, 851_968, 983_040, 1_114_112, 1_245_184,
			786_432, 917_504, 1_048_576, 1_179_648
	};
	private static final int[] INPUT_DEFAULTS = {
			16_384, 81_920, 114_688, 147_456,
			180_224, 212_992, 245_760, 278_528,
			180_224, 212_992, 245_760, 278_528, 311_296,
			196_608, 229_376, 262_144, 294_912
	};
	private static final int[] FLUID_DEFAULTS = {
			1, 2, 4, 8, 16, 32, 64, 128,
			256, 512, 1_024, 2_048, 4_096,
			4_096, 8_192, 16_384, 32_768
	};
	private static final int[] APIARY_DEFAULTS = {
			1, 2, 4, 8, 16, 32, 64, 128,
			16, 32, 64, 128, 256,
			256, 512, 1_024, 4_096
	};
	private static final int[] PARALLEL_PROCESSES = {
			3, 5, 7, 9,
			11, 13, 15, 17,
			11, 13, 15, 17, 19,
			12, 14, 16, 18
	};

	@Test
	void legacyKeysDefaultsAndRuntimeMappingsRemainStable() {
		FactoryTierKey[] tiers = FactoryTierKey.values();
		assertEquals(LEGACY_KEYS.size(), tiers.length);
		for (int index = 0; index < tiers.length; index++) {
			FactoryTierKey tier = tiers[index];
			assertEquals(LEGACY_KEYS.get(index), tier.configKey(), "config key " + index);
			assertEquals(OUTPUT_DEFAULTS[index], tier.centrifugeOutputStackDefault(), tier.configKey());
			assertEquals(INPUT_DEFAULTS[index], tier.centrifugeInputStackDefault(), tier.configKey());
			assertEquals(FLUID_DEFAULTS[index], tier.centrifugeFluidTankDefault(), tier.configKey());
			assertEquals(APIARY_DEFAULTS[index], tier.apiaryOutputStackDefault(), tier.configKey());
			assertEquals(PARALLEL_PROCESSES[index], tier.parallelProcesses(), tier.configKey());
		}

		assertMapping(FactoryTierKey::vanillaFactory,
				FactoryTierKey.BASIC, FactoryTierKey.ADVANCED,
				FactoryTierKey.ELITE, FactoryTierKey.ULTIMATE);
		assertMapping(FactoryTierKey::mekanismExtrasFactory,
				FactoryTierKey.ME_ABSOLUTE, FactoryTierKey.ME_SUPREME,
				FactoryTierKey.ME_COSMIC, FactoryTierKey.ME_INFINITE);
		assertMapping(FactoryTierKey::evolvedMekanismFactory,
				FactoryTierKey.EM_OVERCLOCKED, FactoryTierKey.EM_QUANTUM,
				FactoryTierKey.EM_DENSE, FactoryTierKey.EM_MULTIVERSAL,
				FactoryTierKey.EM_CREATIVE);
		assertMapping(FactoryTierKey::evolvedMekanismExtrasFactory,
				FactoryTierKey.EME_ABSOLUTE_OVERCLOCKED, FactoryTierKey.EME_SUPREME_QUANTUM,
				FactoryTierKey.EME_COSMIC_DENSE, FactoryTierKey.EME_INFINITE_MULTIVERSAL);
	}

	@Test
	void capacityOrderUsesParallelProcessCountWithoutReorderingLegacyKeys() {
		for (String group : FactoryTierKey.configGroups()) {
			List<Integer> values = FactoryTierKey.groupTiers(group).stream()
					.map(FactoryTierKey::parallelProcesses)
					.toList();
			assertTrue(FactoryTierOrderingValidator.validateGroup(group, values).isEmpty(), group);
		}
		assertTrue(FactoryTierOrderingValidator.validateGroup(
				"mekanism", List.of(3, 5, 7, 9)).isEmpty());
		assertTrue(FactoryTierOrderingValidator.validateGroup(
				"mekanism", List.of(9, 7, 5, 3)).isPresent());
	}

	@Test
	void shippedDefaultsRemainValidForEveryCapacityMatrix() {
		assertDefaultsValid(FactoryTierKey::centrifugeOutputStackDefault);
		assertDefaultsValid(FactoryTierKey::centrifugeInputStackDefault);
		assertDefaultsValid(FactoryTierKey::centrifugeFluidTankDefault);
		assertDefaultsValid(FactoryTierKey::apiaryOutputStackDefault);
	}

	private static void assertDefaultsValid(ToIntFunction<FactoryTierKey> defaults) {
		for (String group : FactoryTierKey.configGroups()) {
			List<Integer> values = FactoryTierKey.groupTiers(group).stream()
					.mapToInt(defaults)
					.boxed()
					.toList();
			assertTrue(FactoryTierOrderingValidator.validateGroup(group, values).isEmpty(), group);
		}
	}

	@Test
	void multiplierValidationKeepsTheLegacyPositiveIntegerRange() {
		assertFalse(FactoryTierConfigValues.isValidMultiplier(null));
		assertFalse(FactoryTierConfigValues.isValidMultiplier(0));
		assertFalse(FactoryTierConfigValues.isValidMultiplier(-1));
		assertFalse(FactoryTierConfigValues.isValidMultiplier(1L));
		assertTrue(FactoryTierConfigValues.isValidMultiplier(1));
		assertTrue(FactoryTierConfigValues.isValidMultiplier(Integer.MAX_VALUE));
	}

	private static void assertMapping(
			IntFunction<FactoryTierKey> resolver, FactoryTierKey... expected) {
		for (int ordinal = 0; ordinal < expected.length; ordinal++) {
			assertEquals(expected[ordinal], resolver.apply(ordinal), "ordinal " + ordinal);
		}
	}
}
