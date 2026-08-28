package com.ayoshiko.productivebeesgenesis.apiary;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
