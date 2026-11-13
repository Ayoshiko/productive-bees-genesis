package com.ayoshiko.productivebeesgenesis.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.resources.ResourceLocation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 精华转化升级的配方形状和索引规则测试。 */
class EssenceConversionUpgradeHelperTest {

	@Test
	@DisplayName("接受低级资源压缩，拒绝解压、方块输入和高层资源输入")
	void acceptsOnlyLowestTierCompressionShapes() {
		assertTrue(EssenceConversionRecipeIndex.isCompressionShape(9, 1, false, false));
		assertTrue(EssenceConversionRecipeIndex.isCompressionShape(4, 1, false, false));
		assertFalse(EssenceConversionRecipeIndex.isCompressionShape(1, 9, false, false));
		assertFalse(EssenceConversionRecipeIndex.isCompressionShape(8, 12, false, false));
		assertFalse(EssenceConversionRecipeIndex.isCompressionShape(9, 1, true, false));
		assertFalse(EssenceConversionRecipeIndex.isCompressionShape(9, 1, false, true));
		assertFalse(EssenceConversionRecipeIndex.isCompressionShape(4, 1, false, true, false));
	}

	@Test
	@DisplayName("允许从单锭拆粒配方建立反向压缩，但拒绝粗矿、方块和已排除产物")
	void acceptsSafeDecompressionDeclarations() {
		assertTrue(EssenceConversionRecipeIndex.isDecompressionShape(1, 9, false, false, true, false));
		assertTrue(EssenceConversionRecipeIndex.isDecompressionShape(1, 4, false, false, true, false));
		assertFalse(EssenceConversionRecipeIndex.isDecompressionShape(1, 9, false, false, false, false));
		assertFalse(EssenceConversionRecipeIndex.isDecompressionShape(1, 9, true, false, true, false));
		assertFalse(EssenceConversionRecipeIndex.isDecompressionShape(1, 9, false, true, true, false));
		assertFalse(EssenceConversionRecipeIndex.isDecompressionShape(1, 9, false, false, true, true));
		assertFalse(EssenceConversionRecipeIndex.isDecompressionShape(2, 9, false, false, true, false));
	}

	@Test
	@DisplayName("连续转换保留每一级余数")
	void preservesRemaindersAcrossNetherStarChain() {
		EssenceConversionUpgradeHelper.AmountConversion first =
				EssenceConversionUpgradeHelper.calculateAmounts(30, 9, 1);
		EssenceConversionUpgradeHelper.AmountConversion second =
				EssenceConversionUpgradeHelper.calculateAmounts(first.resultAmount(), 3, 1);
		assertEquals(3, first.remainder());
		assertEquals(0, second.remainder());
		assertEquals(1, second.resultAmount());

		first = EssenceConversionUpgradeHelper.calculateAmounts(18, 9, 1);
		second = EssenceConversionUpgradeHelper.calculateAmounts(first.resultAmount(), 3, 1);
		assertEquals(0, first.remainder());
		assertEquals(2, second.remainder());
		assertEquals(0, second.resultAmount());

		EssenceConversionUpgradeHelper.AmountConversion shard =
				EssenceConversionUpgradeHelper.calculateAmounts(4, 3, 1);
		assertEquals(1, shard.remainder());
		assertEquals(1, shard.resultAmount());
	}

	@Test
	@DisplayName("工业先锋吸管配方不得被精华转化升级自动合成")
	void excludesIndustrialForegoingStrawRecipe() {
		ResourceLocation strawRecipe =
				ResourceLocation.fromNamespaceAndPath("industrialforegoing", "straw");
		ResourceLocation unrelatedRecipe =
				ResourceLocation.fromNamespaceAndPath("industrialforegoing", "plastic");
		assertTrue(EssenceConversionRecipeIndex.isRecipeExcluded(strawRecipe));
		assertFalse(EssenceConversionRecipeIndex.isRecipeExcluded(unrelatedRecipe),
				"黑名单必须精确匹配配方 ID，不能禁用整个模组的配方");

	}

	@Test
	@DisplayName("骨钩及原版和整合包唱片 5 配方不参与精华转化，保留同模组其它配方")
	void excludesBoneCrookAndMusicDiscRecipes() {
		assertTrue(EssenceConversionRecipeIndex.isRecipeExcluded(
				ResourceLocation.fromNamespaceAndPath("exdeorum", "bone_crook")));
		assertTrue(EssenceConversionRecipeIndex.isRecipeExcluded(
				ResourceLocation.fromNamespaceAndPath("minecraft", "music_disc_5")));
		assertTrue(EssenceConversionRecipeIndex.isRecipeExcluded(
				ResourceLocation.fromNamespaceAndPath("kubejs", "kjs/music_disc_5")));
		assertFalse(EssenceConversionRecipeIndex.isRecipeExcluded(
				ResourceLocation.fromNamespaceAndPath("exdeorum", "porcelain_clay")));
		assertFalse(EssenceConversionRecipeIndex.isRecipeExcluded(
				ResourceLocation.fromNamespaceAndPath("minecraft", "bone_meal")));
		assertFalse(EssenceConversionRecipeIndex.isRecipeExcluded(
				ResourceLocation.fromNamespaceAndPath("kubejs", "kjs/iron_ingot")));
	}

}
