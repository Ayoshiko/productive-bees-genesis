package com.ayoshiko.productivebeesgenesis.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 配方查找缓存的接线校验（源码级断言，不需要 Minecraft 运行时）。
 * <p>
 * 这些优化涉及 {@code Level} / {@code RecipeManager} / {@code ItemStack}，无法用纯逻辑单测覆盖；
 * 一旦被重构改回原样，行为仍完全正确、无任何报错，只是 tick 开销悄悄涨回去，
 * 因此沿用本项目既有做法，用源码断言把调用点与失效入口钉住。
 * <p>
 * 覆盖的三处改动：
 * <ul>
 *   <li>染料蜜蜂花→染料查找：原实现每个产出周期都 {@code getAllRecipesFor(CRAFTING)} 全量遍历；</li>
 *   <li>{@link InputValidationCache} 槽位数：原 4 条在多进程工厂上互相驱逐；</li>
 *   <li>{@link BeeProductModResolver} 产物归属缓存：原为无上限 map。</li>
 * </ul>
 */
class RecipeLookupCacheWiringTest {

	private static String read(String relativePath) throws Exception {
		return Files.readString(Path.of(relativePath));
	}

	@Test
	@DisplayName("染料查找走单原料合成索引，不再每次全量遍历合成配方表")
	void dyeLookupUsesSingleIngredientIndex() throws Exception {
		String resolver = read("src/main/java/com/ayoshiko/productivebeesgenesis/util/"
				+ "DyeProduceResolver.java");
		assertTrue(resolver.contains("SingleIngredientCraftingIndex.resolve(level, flower)"),
				"花→染料必须走索引查找");
		assertFalse(resolver.contains("getAllRecipesFor(RecipeType.CRAFTING)"),
				"染料解析器不得再直接全量遍历合成配方（染料蜜蜂每个产出周期都会调用）");
	}

	@Test
	@DisplayName("单原料合成索引用不可变快照发布，并保留首个匹配胜出语义")
	void singleIngredientIndexPublishesImmutableSnapshot() throws Exception {
		String index = read("src/main/java/com/ayoshiko/productivebeesgenesis/util/"
				+ "SingleIngredientCraftingIndex.java");
		assertTrue(index.contains("private static volatile Snapshot snapshot"),
				"必须用单一 volatile 引用原子替换快照，读线程不能看到半构建状态");
		assertTrue(index.contains("Map.copyOf(index)"),
				"快照必须冻结为不可变映射");
		assertTrue(index.contains("index.putIfAbsent("),
				"必须用 putIfAbsent 保留原实现的\"首个匹配配方胜出\"语义");
		assertTrue(index.contains("if (result.isEmpty()) continue;"),
				"产出为空的配方必须跳过，等价于原实现继续查找下一条");
		assertTrue(index.contains("RETRY_INTERVAL_TICKS"),
				"构建失败必须节流重试，否则失败后每次查询都重跑全量遍历");
		assertTrue(index.contains("public static void invalidate()"),
				"必须提供失效入口供配方重载调用");
	}

	@Test
	@DisplayName("配方/标签重载时失效单原料合成索引")
	void tagsReloadInvalidatesSingleIngredientIndex() throws Exception {
		String main = read("src/main/java/com/ayoshiko/productivebeesgenesis/"
				+ "ProductiveBeesGenesis.java");
		assertTrue(main.contains("SingleIngredientCraftingIndex.invalidate();"),
				"onTagsReload 必须失效索引，否则重载后仍返回旧配方产物");
	}

	@Test
	@DisplayName("输入校验缓存槽位覆盖最高等级工厂进程数")
	void inputValidationCacheCoversHighestTierProcessCount() throws Exception {
		String cache = read("src/main/java/com/ayoshiko/productivebeesgenesis/util/"
				+ "InputValidationCache.java");
		assertTrue(cache.contains("DEFAULT_MAX_ENTRIES = 20"),
				"槽位数须覆盖最高等级工厂的 19 进程；过小会让各进程输入互相驱逐，"
						+ "退化成每次探测都重跑配方查找");
	}

	@Test
	@DisplayName("产物归属缓存有界，且昂贵解析在锁外执行")
	void productModCacheIsBoundedAndComputesOutsideLock() throws Exception {
		String resolver = read("src/main/java/com/ayoshiko/productivebeesgenesis/util/"
				+ "BeeProductModResolver.java");
		assertTrue(resolver.contains("BoundedLruMap.synchronizedAccessOrdered(MAX_CACHE_ENTRIES)"),
				"必须有条目上限，防止自定义数据包大量一次性 bee_type 导致缓存无界增长");
		assertFalse(resolver.contains("PRODUCT_MOD_CACHE.computeIfAbsent"),
				"computeIfAbsent 会在持有全表锁时执行配方查找，须改为锁外解析 + putIfAbsent");
		assertTrue(resolver.contains("PRODUCT_MOD_CACHE.putIfAbsent(beeType, resolved)"),
				"锁外解析后用 putIfAbsent 收敛到同一份实例");
	}
}
