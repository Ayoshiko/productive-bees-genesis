package com.ayoshiko.productivebeesgenesis.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PB 离心配方查找缓存静态共享化的接线校验（源码级断言，不需要 Minecraft 运行时）。
 * <p>
 * 优化目标（参考 Mekanism {@code InputRecipeCache} 静态单例 / PB 原版静态 {@code recipeMap}）：
 * 原 {@code PbRecipeFinder.pbRecipeCache} 为实例级 LRU，N 台离心机处理同种蜜脾时各自维护
 * 长期缓存并在首次查找时重复全量遍历；改为
 * {@link SharedPbRecipeCache} 静态共享后跨机器复用。该优化被改回实例级时行为完全正确、
 * 无任何报错，只是 tick 开销悄悄涨回去，故用源码断言把接线钉住。
 */
class SharedPbRecipeCacheWiringTest {

	private static String read(String relativePath) throws Exception {
		return Files.readString(Path.of(relativePath));
	}

	@Test
	@DisplayName("PbRecipeFinder 下层缓存走 SharedPbRecipeCache 静态共享，不再持有实例级 RecipeCacheManager")
	void pbRecipeFinderUsesSharedStaticCache() throws Exception {
		String finder = read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/PbRecipeFinder.java");
		assertFalse(finder.contains("new RecipeCacheManager"),
				"pbRecipeCache 不得再是实例级 RecipeCacheManager（每台机器一份会重复全量遍历）");
		assertFalse(finder.contains("RecipeCacheManager<"),
				"PbRecipeFinder 不得再声明 RecipeCacheManager 类型字段");
		assertTrue(finder.contains("SharedPbRecipeCache.get(input)"),
				"下层缓存查询必须走 SharedPbRecipeCache 静态共享");
		assertTrue(finder.contains("SharedPbRecipeCache.put(input,"),
				"下层缓存写入必须走 SharedPbRecipeCache 静态共享");
	}

	@Test
	@DisplayName("SharedPbRecipeCache 为静态 LRU + 有界 + 防御性线程安全")
	void sharedCacheIsBoundedStaticLru() throws Exception {
		String cache = read("src/main/java/com/ayoshiko/productivebeesgenesis/util/SharedPbRecipeCache.java");
		assertTrue(cache.contains("Collections.synchronizedMap(new LinkedHashMap"),
				"静态缓存须用 synchronizedMap 防御 JEI 客户端与服务端 tick 并发访问 invalidate");
		assertTrue(cache.contains("removeEldestEntry"),
				"必须是 LRU 淘汰（访问有序 LinkedHashMap）");
		assertTrue(cache.contains("size() > MAX_CACHE_SIZE"),
				"必须有条目上限，防止自定义数据包大量一次性输入导致缓存无界增长");
		assertTrue(cache.contains("Optional.empty()"),
				"必须支持缓存\"无配方\"负结果，避免对无配方输入每 tick 重复全量遍历");
	}

	@Test
	@DisplayName("通用缓存键保留完整组件，不把 int 哈希当作唯一身份")
	void genericCacheKeyPreservesComponents() throws Exception {
		String cache = read("src/main/java/com/ayoshiko/productivebeesgenesis/util/SharedPbRecipeCache.java");
		assertTrue(cache.contains("DataComponentPatch components"),
				"通用键必须保留完整组件补丁，用 equals 消解哈希碰撞");
		assertTrue(cache.contains("stack.getComponentsPatch()"),
				"构造键时必须保留 ItemStack 的完整组件补丁");
		assertTrue(cache.contains("new CacheKey(item, beeType, stack.getComponentsPatch(), hash)"),
				"可配置蜜脾也必须保留非 bee_type 组件用于碰撞消解");
		assertFalse(cache.contains("record CacheKey(Item item, @Nullable ResourceLocation beeType, int componentHash)"),
				"不得仅用 32 位哈希区分带组件输入");
	}

	@Test
	@DisplayName("配方/标签重载与服务器停止时失效静态共享缓存，防跨存档残留")
	void reloadAndServerStopInvalidateSharedCache() throws Exception {
		String main = read("src/main/java/com/ayoshiko/productivebeesgenesis/ProductiveBeesGenesis.java");
		assertTrue(main.contains("SharedPbRecipeCache.invalidate();"),
				"onTagsReload 必须直接失效静态共享缓存，与 CentrifugeRecipeIndex 原子替换同步");
		assertTrue(main.contains("SharedPbRecipeCache::invalidate"),
				"onServerStopped 必须经 safeClear 清理静态共享缓存，否则跨存档残留旧 RecipeHolder 引用");

		String reloader = read("src/main/java/com/ayoshiko/productivebeesgenesis/util/BeeRecipeReloader.java");
		int rebuild = reloader.indexOf("CentrifugeRecipeIndex.rebuild(recipeManager)");
		int invalidate = reloader.indexOf("SharedPbRecipeCache.invalidate()", rebuild);
		int version = reloader.indexOf("ProductiveBeesGenesis.RECIPE_VERSION.incrementAndGet()", invalidate);
		assertTrue(rebuild >= 0 && invalidate > rebuild && version > invalidate,
				"延迟配方重试可能晚于 TagsUpdatedEvent，重建后必须清共享缓存并通知实例短缓存");
	}

	@Test
	@DisplayName("PbRecipeFinder.clearCaches 仍清实例级短期缓存，静态缓存由全局钩子统一失效")
	void clearCachesClearsInstanceShortCacheOnly() throws Exception {
		String finder = read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/PbRecipeFinder.java");
		assertTrue(finder.contains("inputRecipeCache.clear()"),
				"clearCaches 必须清实例级 InputValidationCache（TTL 短期缓存）");
		assertFalse(finder.contains("pbRecipeCache.clear()"),
				"静态共享缓存不得由实例方法清理（会被单台机器的 clearCaches 误清全服缓存）");
	}
}
