package com.ayoshiko.productivebeesgenesis.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/** 粗矿熔炼升级的数量守恒和边界测试。 */
class RawOreSmeltingUpgradeHelperTest {

	@Test
	void convertsCompleteRecipeGroupsOnly() {
		assertEquals(3L, RawOreSmeltingUpgradeHelper.convertedCount(3, 1, 1));
		assertEquals(2L, RawOreSmeltingUpgradeHelper.convertedCount(9, 4, 1));
		assertEquals(18L, RawOreSmeltingUpgradeHelper.convertedCount(9, 3, 6));
	}

	@Test
	void invalidRecipeValuesPreserveInput() {
		assertEquals(7L, RawOreSmeltingUpgradeHelper.convertedCount(7, 0, 1));
		assertEquals(7L, RawOreSmeltingUpgradeHelper.convertedCount(7, 1, 0));
		assertEquals(7L, RawOreSmeltingUpgradeHelper.convertedCount(7, -1, 1));
	}

	@Test
	void acceptsPowahUraniniteSmeltingPairWithoutAnIngotTag() {
		assertTrue(RawOreSmeltingUpgradeHelper.isPowahUraniniteSmelting(
				ResourceLocation.parse("powah:uraninite_raw"), ResourceLocation.parse("powah:uraninite")));
	}

	@Test
	void rejectsOtherNonIngotSmeltingPairs() {
		assertFalse(RawOreSmeltingUpgradeHelper.isPowahUraniniteSmelting(
				ResourceLocation.parse("powah:uraninite_raw"), ResourceLocation.parse("minecraft:iron_ingot")));
		assertFalse(RawOreSmeltingUpgradeHelper.isPowahUraniniteSmelting(
				ResourceLocation.parse("minecraft:raw_iron"), ResourceLocation.parse("powah:uraninite")));
	}
}
