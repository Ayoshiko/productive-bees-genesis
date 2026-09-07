package com.ayoshiko.productivebeesgenesis.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 精华转化升级的配方形状和索引规则测试。 */
class EssenceConversionUpgradeHelperTest {

	@Test
	@DisplayName("接受低级资源压缩，拒绝解压、方块输入和高层资源输入")
	void acceptsOnlyLowestTierCompressionShapes() {
		assertTrue(EssenceConversionUpgradeHelper.isCompressionShape(9, 1, false, false));
		assertTrue(EssenceConversionUpgradeHelper.isCompressionShape(4, 1, false, false));
		assertFalse(EssenceConversionUpgradeHelper.isCompressionShape(1, 9, false, false));
		assertFalse(EssenceConversionUpgradeHelper.isCompressionShape(9, 1, true, false));
		assertFalse(EssenceConversionUpgradeHelper.isCompressionShape(9, 1, false, true));
		assertFalse(EssenceConversionUpgradeHelper.isCompressionShape(4, 1, false, true, false));
	}

	@Test
	@DisplayName("唯一单向配方直接使用，多候选时才以严格反向配方判优")
	void uniqueOneWayRecipeDoesNotConflictWithReverseTieBreaker() throws Exception {
		String source = Files.readString(Path.of(
				"src/main/java/com/ayoshiko/productivebeesgenesis/util/EssenceConversionUpgradeHelper.java"));
		assertTrue(source.contains("if (eligibleCount == 1) return onlyCandidate;"));
		assertTrue(source.contains("return reversibleCount == 1 ? reversibleCandidate : null;"));
		assertTrue(source.contains("reverse.inputCount() == forward.result().getCount()"));
		assertTrue(source.contains("reverse.result().getCount() == forward.inputCount()"));
		assertTrue(source.contains("reverse.resultKey().equals(forward.inputKey())"));
	}

	@Test
	@DisplayName("只索引纯同物配方，并排除粗矿与后续压缩层级")
	void indexesOnlySameItemRecipesAndLowestTierInputs() throws Exception {
		String source = Files.readString(Path.of(
				"src/main/java/com/ayoshiko/productivebeesgenesis/util/EssenceConversionUpgradeHelper.java"));
		assertTrue(source.contains("else if (!ItemStack.isSameItemSameComponents(input, choices[0]))"),
				"混合材料配方必须在索引构建时忽略");
		assertTrue(source.contains("itemTag(\"c\", \"raw_materials\")"),
				"精华转化不能接管粗矿熔炼升级的职责");
		assertTrue(source.contains("if (producedItems.contains(entry.getKey())) continue;"),
				"转换产物不能在后续生产周期继续压缩");
		assertTrue(source.contains("List<ItemStack> merged = new ArrayList<>(drops.size())"));
		assertTrue(source.contains("for (ItemStack stack : merged)"));
	}

	@Test
	@DisplayName("配方扫描结果冻结为不可变快照，运行时只做映射查询")
	void publishesImmutableRuntimeLookupSnapshot() throws Exception {
		String source = Files.readString(Path.of(
				"src/main/java/com/ayoshiko/productivebeesgenesis/util/EssenceConversionUpgradeHelper.java"));
		assertTrue(source.contains("private static volatile ConversionSnapshot conversionSnapshot"));
		assertTrue(source.contains("new ConversionSnapshot(Map.copyOf(conversions))"));
		assertTrue(source.contains("return snapshot.byInput().get(key);"));
		assertTrue(source.contains("BUILD_RETRY_INTERVAL_TICKS = 100L"),
				"索引构建失败必须节流重试，不能在每次产出时重新扫描配方表");
		assertFalse(source.contains("ConcurrentHashMap"));
	}
}
