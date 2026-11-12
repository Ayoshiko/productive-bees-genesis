package com.ayoshiko.productivebeesgenesis.apiary;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneSamplerTest {

	@Test
	void systematicAllocationDoesNotStarveSmallSourceAcrossBatches() {
		long rareSourceHits = 0L;
		for (int sample = 0; sample < 1_000; sample++) {
			double offset = (sample + 0.5D) / 1_000.0D;
			long largeSourceHits = GeneSamplerMath.cumulativeHitAllocation(
					50L, 1_000L, 1_001L, offset);
			rareSourceHits += 50L - largeSourceHits;
		}
		assertEquals(50L, rareSourceHits);
	}

	@Test
	void pluginItemsHaveIndependentModelsAndRecipes() throws Exception {
		String typeModel = Files.readString(Path.of(
				"src/main/resources/assets/productivebeesgenesis/models/item/gene_type_only_upgrade.json"));
		String purityModel = Files.readString(Path.of(
				"src/main/resources/assets/productivebeesgenesis/models/item/gene_full_purity_upgrade.json"));
		assertTrue(typeModel.contains("gene_type_only"));
		assertTrue(purityModel.contains("gene_full_purity"));
		assertTrue(Files.exists(Path.of(
				"src/main/resources/data/productivebeesgenesis/recipe/gene_type_only_upgrade.json")));
		assertTrue(Files.exists(Path.of(
				"src/main/resources/data/productivebeesgenesis/recipe/gene_full_purity_upgrade.json")));
		String typeRecipe = Files.readString(Path.of(
				"src/main/resources/data/productivebeesgenesis/recipe/gene_type_only_upgrade.json"));
		String purityRecipe = Files.readString(Path.of(
				"src/main/resources/data/productivebeesgenesis/recipe/gene_full_purity_upgrade.json"));
		assertTrue(typeRecipe.contains("\"productivelib:upgrade_gene_sampler\""));
		assertTrue(typeRecipe.contains("\"minecraft:comparator\""));
		assertTrue(typeRecipe.contains("\"minecraft:writable_book\""));
		assertTrue(purityRecipe.contains("\"productivelib:upgrade_gene_sampler\""));
		assertTrue(purityRecipe.contains("\"minecraft:nether_star\""));
		assertTrue(purityRecipe.contains("\"minecraft:diamond_block\""));
		assertFalse(typeRecipe.contains("productivebees:gene"),
				"配方不得依赖带数据组件的基因产物或不可得的空白占位物");
		assertFalse(purityRecipe.contains("productivebees:gene"),
				"配方不得依赖带数据组件的基因产物或不可得的空白占位物");
	}
}
