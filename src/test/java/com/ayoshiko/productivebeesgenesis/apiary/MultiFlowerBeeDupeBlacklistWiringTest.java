package com.ayoshiko.productivebeesgenesis.apiary;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/** 锁定木材蜂/石料蜂与 Productive Bees 原版一致的复制黑名单语义。 */
class MultiFlowerBeeDupeBlacklistWiringTest {

	@Test
	void lumberAndQuarryExcludePbDupeBlacklist() throws Exception {
		String adapter = Files.readString(Path.of(
				"src/main/java/com/ayoshiko/productivebeesgenesis/util/MultiFlowerBeeAdapter.java"));
		assertTrue(adapter.contains(
				"getRandomBlockFromFeeder(ModTags.LUMBER, ModTags.DUPE_BLACKLIST)"));
		assertTrue(adapter.contains(
				"getRandomBlockFromFeeder(ModTags.QUARRY, ModTags.DUPE_BLACKLIST)"));
	}

	@Test
	void exclusionRunsBeforeReservoirSelection() throws Exception {
		String sampler = Files.readString(Path.of(
				"src/main/java/com/ayoshiko/productivebeesgenesis/apiary/FeederTagSampler.java"));
		int exclusion = sampler.indexOf("block.defaultBlockState().is(excludedTag)");
		int selection = sampler.indexOf("random.nextInt(++matches)");
		assertTrue(exclusion >= 0 && selection > exclusion,
				"黑名单方块必须在计入随机候选前排除");
	}

	@Test
	void flowerGateUsesTheSameSpecialBeeStrategy() throws Exception {
		String manager = Files.readString(Path.of(
				"src/main/java/com/ayoshiko/productivebeesgenesis/apiary/FeederSlotManager.java"));
		assertTrue(manager.contains("MultiFlowerBeeAdapter.hasValidFlower(beeTypeKey, this)"),
				"黑名单方块不能只在产出时过滤，还必须阻止蜜蜂进入工作状态");

		String adapter = Files.readString(Path.of(
				"src/main/java/com/ayoshiko/productivebeesgenesis/util/MultiFlowerBeeAdapter.java"));
		assertTrue(adapter.contains(
				"containsBlockInFeeder(ModTags.LUMBER, ModTags.DUPE_BLACKLIST)"));
		assertTrue(adapter.contains(
				"containsBlockInFeeder(ModTags.QUARRY, ModTags.DUPE_BLACKLIST)"));
	}
}
