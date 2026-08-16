package com.ayoshiko.productivebeesgenesis.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class BeeProductModProfileTest {

	@Test
	void vanillaResourceBeeBelongsToMinecraftInsteadOfProductiveBees() {
		BeeProductModProfile profile = BeeProductModProfile.create(
				List.of("minecraft"), null, "productivebees");

		assertEquals("minecraft", profile.primaryModId());
		assertEquals(List.of("minecraft"), profile.allModIds());
	}

	@Test
	void integrationBeeUsesResolvedOutputMod() {
		BeeProductModProfile profile = BeeProductModProfile.create(
				List.of("ae2"), null, "productivebees");

		assertEquals("ae2", profile.primaryModId());
	}

	@Test
	void fluidOnlyBeeUsesResolvedFluidMod() {
		BeeProductModProfile profile = BeeProductModProfile.create(
				List.of(), "pneumaticcraft", "productivebees");

		assertEquals("pneumaticcraft", profile.primaryModId());
	}

	@Test
	void modpackDefinedProductNamespaceRequiresNoHardcodedIntegration() {
		BeeProductModProfile profile = BeeProductModProfile.create(
				List.of("kubejs"), null, "productivebees");

		assertEquals("kubejs", profile.primaryModId());
	}

	@Test
	void multiOutputBeeKeepsOneGroupAndAllSearchableMods() {
		BeeProductModProfile profile = BeeProductModProfile.create(
				List.of("  mekanism  ", "ae2", "mekanism"), "pneumaticcraft", "productivebees");

		assertEquals("mekanism", profile.primaryModId());
		assertEquals(List.of("mekanism", "ae2", "pneumaticcraft"), profile.allModIds());
	}

	@Test
	void beeWithoutDistinctResourceFallsBackToProductiveBees() {
		BeeProductModProfile profile = BeeProductModProfile.fallback("productivebees");

		assertEquals("productivebees", profile.primaryModId());
		assertEquals(List.of("productivebees"), profile.allModIds());
	}
}
