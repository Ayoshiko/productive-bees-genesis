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
	void samplerUsesSixAttributesAndBoundedHotPath() throws Exception {
		String source = Files.readString(Path.of(
				"src/main/java/com/ayoshiko/productivebeesgenesis/apiary/GeneSampler.java"));
		String profile = Files.readString(Path.of(
				"src/main/java/com/ayoshiko/productivebeesgenesis/apiary/GeneSampleProfile.java"));

		assertTrue(source.contains("MAX_EXACT_EVENTS = 128"));
		assertTrue(source.contains("ATTRIBUTES[random.nextInt(ATTRIBUTES.length)]"));
		assertTrue(source.contains("ProductiveBeesConfig.UPGRADES.samplerChance.get()"));
		assertTrue(source.contains("GeneSamplerMath.cumulativeHitAllocation"));
		assertTrue(source.contains("private final long[] sampledCounts"));
		assertTrue(source.contains("Gene.getStack(attribute, value, stackCount, purity)"));
		assertTrue(profile.contains("\"bee_productivity\""));
		assertTrue(profile.contains("\"bee_endurance\""));
		assertTrue(profile.contains("\"bee_temper\""));
		assertTrue(profile.contains("\"bee_behavior\""));
		assertTrue(profile.contains("\"bee_weather_tolerance\""));
	}

	@Test
	void produceProcessorReusesPerBeeProfilesAndClearsBatch() throws Exception {
		String source = Files.readString(Path.of(
				"src/main/java/com/ayoshiko/productivebeesgenesis/apiary/BeeProduceProcessor.java"));

		assertTrue(source.contains("geneSampleBatch.add(slot.getGeneSampleProfile(), count)"));
		assertTrue(source.contains("allItems, geneSampleBatch, beeTypeKey, geneSamplerCount, level"));
		assertTrue(source.contains("geneSampleBatch.clear()"));
	}

	@Test
	void samplerPluginsAreAppliedAtTheSamplingBoundary() throws Exception {
		String sampler = Files.readString(Path.of(
				"src/main/java/com/ayoshiko/productivebeesgenesis/apiary/GeneSampler.java"));
		String snapshot = Files.readString(Path.of(
				"src/main/java/com/ayoshiko/productivebeesgenesis/apiary/ApiaryBatchUpgradeSnapshot.java"));
		String processor = Files.readString(Path.of(
				"src/main/java/com/ayoshiko/productivebeesgenesis/apiary/BeeProduceProcessor.java"));

		assertTrue(sampler.contains("boolean typeOnly, boolean fullPurity"));
		assertTrue(sampler.contains("? GeneAttribute.TYPE : ATTRIBUTES[random.nextInt(ATTRIBUTES.length)]"));
		assertTrue(sampler.contains("int purity = fullPurity ? 100 : attributeOffset % PURITY_COUNT + 1"));
		assertTrue(snapshot.contains("geneTypeOnly") && snapshot.contains("geneFullPurity"));
		assertTrue(processor.contains("upgrades.geneTypeOnly(), upgrades.geneFullPurity()"));
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
