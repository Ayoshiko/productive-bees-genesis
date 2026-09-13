package com.ayoshiko.productivebeesgenesis;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

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
		assertTrue(source.contains("BeeInfoHelper.getBeeProduceStacks(level, beeType)"));
		assertTrue(source.contains("produce.getItem() instanceof net.minecraft.world.item.HoneycombItem"));
		assertTrue(source.contains("findCentrifugeRecipe(level, produce)"));
	}

	@Test
	void cacheResolvesRealHoneycombAndCombBlockTemplates() throws Exception {
		String cache = Files.readString(CACHE);
		String selector = Files.readString(SELECTOR);
		assertTrue(cache.contains("BeeInfoHelper.getBeeProduceStacks(level, beeType)"));
		assertTrue(cache.contains("output.getItem() instanceof net.minecraft.world.item.HoneycombItem"));
		assertTrue(cache.contains("RandomHoneycombSelector.normalizeHoneycombTemplate(beeType, output)"));
		assertTrue(selector.contains("BeeHelper\n\t\t\t\t\t.getCombBlockFromHoneyComb(honeycombTemplate)"));
	}

	@Test
	void mekanismPlannerUsesTheSameRealTemplates() throws Exception {
		String handler = Files.readString(HANDLER);
		String planner = Files.readString(PLANNER);
		assertTrue(handler.contains("snapshot().honeycombTemplateByType()"));
		assertTrue(handler.contains("snapshot().combBlockTemplateByType()"));
		assertTrue(handler.contains("selectedTypes, effectiveBatchSize, templateByType"));
		assertTrue(handler.contains("allocation, currentTick, templateByType"));
		assertTrue(handler.contains("allocation, templateByType"));
		assertTrue(planner.contains("ItemStack.isSameItemSameComponents(workingTemplates[i], outputTemplate)"));
		assertTrue(planner.contains("int space = snapshot.slotLimits[i] - workingCounts[i]"));
		assertTrue(planner.contains("Plan plan = plan(snapshot, baseItem, allocation, templateByType)"));
		assertTrue(planner.contains("resolveTemplate(baseItem, beeType, templateByType)"));
	}
}
