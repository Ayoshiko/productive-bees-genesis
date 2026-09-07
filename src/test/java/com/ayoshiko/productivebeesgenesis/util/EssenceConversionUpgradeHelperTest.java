package com.ayoshiko.productivebeesgenesis.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
	@DisplayName("唯一单向配方直接使用，多候选时才以严格反向配方判优")
	void uniqueOneWayRecipeDoesNotConflictWithReverseTieBreaker() throws Exception {
		String source = Files.readString(Path.of(
				"src/main/java/com/ayoshiko/productivebeesgenesis/util/EssenceConversionRecipeIndex.java"));
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
				"src/main/java/com/ayoshiko/productivebeesgenesis/util/EssenceConversionRecipeIndex.java"));
		assertTrue(source.contains("pureInputs.keySet().retainAll(allowed)"),
				"标签输入应通过候选交集识别所有槽位共有的同一物品");
		assertTrue(source.contains("createPattern(result.copyWithCount(result.getCount()), result.getCount()"),
				"单锭拆粒配方必须登记为反向压缩候选");
		assertTrue(source.contains("isAllowedObsidianShardConversion"),
				"黑曜石碎片到黑曜石是唯一允许方块产物的例外");
		assertTrue(source.contains("OBSIDIAN_SHARD_ID"));
		assertTrue(source.contains("OBSIDIAN_ID"));
		assertTrue(source.contains("isAllowedRedstoneEssenceConversion"));
		assertTrue(source.contains("inputCount == 8 && result.getCount() == 12"),
				"只允许红石精华使用精确的 8:12 扩量配方");
		assertTrue(source.contains("REDSTONE_ESSENCE_ID"));
		assertTrue(source.contains("REDSTONE_ID"));
		assertTrue(source.contains("NETHER_STAR_ESSENCE_ID"));
		assertTrue(source.contains("NETHER_STAR_SHARD_ID"));
		assertTrue(source.contains("isNetherStarShardStep(entry.getKey(), pattern)"),
				"下界之星碎片必须作为唯一允许继续压缩的中间层");
		assertTrue(source.contains("pattern.inputCount() == 9 && pattern.result().getCount() == 1"),
				"下界之星精华白名单必须锁定 9:1 比例");
		assertTrue(source.contains("pattern.inputCount() == 3 && pattern.result().getCount() == 1"),
				"下界之星碎片白名单必须锁定 3:1 比例");
		assertTrue(source.contains("isAllowedNetherStarConversion"),
				"下界之星两段配方必须绕过可能存在的高层资源标签排除");
		assertTrue(source.contains("STORAGE_BLOCK_TAGS"),
				"方块判断必须兼容未直接继承 BlockItem 的存储方块物品");
		assertTrue(source.contains("itemTag(\"c\", \"raw_materials\")"),
				"精华转化不能接管粗矿熔炼升级的职责");
		assertTrue(source.contains("DECOMPRESSION_INPUT_TAGS"),
				"反向推断只允许锭和宝石，不能复用粗矿排除标签");
		assertTrue(source.contains("producedItems.contains(entry.getKey())"),
				"除下界之星白名单外，转换产物不能在后续生产周期继续压缩");
	}

	@Test
	@DisplayName("配方扫描结果冻结为不可变快照，运行时只做映射查询")
	void publishesImmutableRuntimeLookupSnapshot() throws Exception {
		String helper = Files.readString(Path.of(
				"src/main/java/com/ayoshiko/productivebeesgenesis/util/EssenceConversionUpgradeHelper.java"));
		String index = Files.readString(Path.of(
				"src/main/java/com/ayoshiko/productivebeesgenesis/util/EssenceConversionRecipeIndex.java"));
		assertTrue(index.contains("private static volatile ConversionSnapshot conversionSnapshot"));
		assertTrue(index.contains("Map.copyOf(conversions)"));
		assertTrue(helper.contains("EssenceConversionRecipeIndex.snapshotFor(level)"));
		assertTrue(helper.contains("Conversion conversion = snapshot.find(source);"),
				"一次转换应复用索引快照并保持 O(1) 查询");
		assertTrue(helper.contains("containsConversionCandidate(handler, outputSlots, conversionSnapshot)"),
				"256 倍加速下无候选库存应在分配和容量模拟前快速返回");
		assertTrue(helper.contains("containsConversionCandidate(outputs, conversionSnapshot)"),
				"自定义机器无候选输出也应在创建聚合列表前返回");
		assertTrue(index.contains("return byDefaultItem.get(stack.getItem());"),
				"无组件物品热路径不应创建 StackKey");
		assertTrue(index.contains("StackKey resultKey, boolean continueChain"),
				"结果键和连续转换标记应在索引构建时预存");
		assertFalse(helper.contains("private static Conversion findConversion"),
				"批次中的每种产物不能重复获取配方快照");
		assertFalse(index.contains("candidate -> !candidate.inferred()"),
				"候选判优不应为每个输入创建 Stream 临时列表");
		assertTrue(index.contains("BUILD_RETRY_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(5)"),
				"索引构建失败必须按现实时间节流，不能被 256 倍 tick 加速缩短");
		assertTrue(index.contains("lastFailedBuildNanos = System.nanoTime()"));
		assertFalse(index.contains("RECIPE_VERSION.get()"),
				"配方重载已有显式失效回调，产出热路径不应重复读取原子版本号");
		assertFalse(index.contains("ConcurrentHashMap"));
	}
}
