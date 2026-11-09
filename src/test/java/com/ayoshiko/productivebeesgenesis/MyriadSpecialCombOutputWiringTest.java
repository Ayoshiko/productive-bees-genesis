package com.ayoshiko.productivebeesgenesis;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** 万象创世随机产物必须复用蜂箱配方中的真实蜜脾形态。 */
class MyriadSpecialCombOutputWiringTest {

	private static final Path ABSTRACT_HANDLER = Path.of(
			"src/main/java/com/ayoshiko/productivebeesgenesis/AbstractCombEventHandler.java");
	private static final Path CACHE = Path.of(
			"src/main/java/com/ayoshiko/productivebeesgenesis/MyriadBeeTypeCache.java");
	private static final Path SELECTOR = Path.of(
			"src/main/java/com/ayoshiko/productivebeesgenesis/RandomHoneycombSelector.java");
	private static final Path HANDLER = Path.of(
			"src/main/java/com/ayoshiko/productivebeesgenesis/mek/MyriadCreationsHandler.java");
	private static final Path PLANNER = Path.of(
			"src/main/java/com/ayoshiko/productivebeesgenesis/mek/MyriadBatchPlanner.java");

	@Test
	void candidateGateTestsActualSpecialHoneycombRecipes() throws Exception {
		String source = Files.readString(ABSTRACT_HANDLER);
		String gate = source.substring(source.indexOf("public static List<ResourceLocation> buildBeeTypeCache("),
				source.indexOf("protected static boolean hasCentrifugeRecipe("));
		assertTrue(gate.contains("BeeInfoHelper.getAllBeeProduce(level, beeType)"));
		assertTrue(gate.contains("MyriadBeeTypeCache::isHoneycomb"));
		assertFalse(gate.contains("hasCentrifugeRecipe(level"));
	}

	@Test
	void cacheResolvesRealHoneycombAndCombBlockTemplates() throws Exception {
		String cache = Files.readString(CACHE);
		String selector = Files.readString(SELECTOR);
		assertTrue(cache.contains("BeeInfoHelper.getAllBeeProduce(level, beeType)"));
		assertTrue(cache.contains("if (isHoneycomb(output))"));
		assertTrue(cache.contains("RandomHoneycombSelector.normalizeHoneycombTemplate(beeType, output)"));
		assertTrue(selector.contains("BeeHelper\n\t\t\t\t\t.getCombBlockFromHoneyComb(honeycombTemplate)"));
	}

	@Test
	void mekanismPlannerUsesTheSameRealTemplates() throws Exception {
		String handler = Files.readString(HANDLER);
		String planner = Files.readString(PLANNER);
		String prepared = Files.readString(PLANNER.resolveSibling("MyriadPlanningAllocation.java"));
		assertTrue(handler.contains(".selectTemplates(selectedTypes, isCombBlock, level.getRandom())"));
		assertTrue(handler.contains("MyriadBeeTypeCache.cachedBeeTypes(isCombBlock)"));
		assertTrue(handler.contains("selectedTypes, effectiveBatchSize, templateByType"));
		assertTrue(handler.contains("allocation, currentTick, templateByType"));
		assertTrue(handler.contains("allocation, templateByType"));
		assertTrue(prepared.contains("ItemStack.isSameItemSameComponents(templates[right], templates[left])"));
		assertTrue(prepared.contains("snapshot.limitsFor(templates[index])"));
		assertTrue(planner.contains("return plan(snapshot, baseItem, allocation, templateByType, new PlanningScratch(snapshot.slotCount))"));
		assertTrue(planner.contains("return fillScratch(snapshot, allocation, scratch)"));
		assertTrue(prepared.contains("resolveTemplate(baseItem, entry.getKey(), templateByType)"));
	}
}
