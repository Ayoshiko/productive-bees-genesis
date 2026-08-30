package com.ayoshiko.productivebeesgenesis.mek.ae2;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 时间加速热路径的两处记忆化接线校验（源码级断言，不需要 Minecraft/AE2 运行时）。
 * <p>
 * 这两处优化一旦被重构改回原样，行为完全正确、无任何报错，只是 MSPT 悄悄涨回去，
 * 纯逻辑单测无法发现，故用源码断言把调用点钉住。
 * <p>
 * 依据（spark 采样，NeoForge 21.1.214 / MC 1.21.1 / 44 mods）：
 * <ul>
 *   <li>gUqyZmn5q6（加速可熔炼配方）：{@code BasicInventorySlot.productivebeesgenesis$getCachedBaseLimit}
 *       自耗 1272ms / 4.24%，全服第 2 热点；</li>
 *   <li>BHSGIz87Uw（部分机器时间手杖）：同方法 1464ms / 2.44%，全服第 3 热点；</li>
 *   <li>ejYMNQjDf7（无加速）：{@code Ae2ItemFingerprint.encode} 拉取侧 432ms / 1.44%
 *       + 推送侧 408ms / 1.36%，成本来自 {@code AEItemKey.toTag} 的 Codec 编码与
 *       {@code CompoundTag.toString} 的 StringTagVisitor 遍历。</li>
 * </ul>
 */
class Ae2HotPathCacheWiringTest {

	private static String read(String relativePath) throws Exception {
		return Files.readString(Path.of(relativePath));
	}

	@Test
	@DisplayName("输出账本与输入 pending 都走 per-tile 指纹缓存，不再每次重新编码")
	void fingerprintEncodingIsMemoizedPerHost() throws Exception {
		String buffers = read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/Ae2PushBuffers.java");
		assertTrue(buffers.contains("final Ae2FingerprintCache fingerprintCache = new Ae2FingerprintCache()"),
				"指纹缓存必须与其他 per-tile 缓冲同生命周期");

		String committer = read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/"
				+ "Ae2OutputCommitter.java");
		assertTrue(committer.contains("buffers.fingerprintCache.get(key, registries)"),
				"输出槽收集必须复用缓存指纹");
		assertFalse(committer.contains("Ae2ItemFingerprint.encode(key, registries)"),
				"collectSlot 不得再直接编码（每个非空输出槽每刻一次）");

		String puller = read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/Ae2InputPuller.java");
		assertTrue(puller.contains("fingerprintCache.get(key, level.registryAccess())"),
				"抽取前的 pending 条目位检查必须复用缓存指纹");
		assertTrue(puller.contains("buffers.fingerprintCache"),
				"缓存必须由 per-tile 缓冲传入 pullBatchForType，不能新建");
	}

	@Test
	@DisplayName("指纹缓存有界且随注册表切换整表失效")
	void fingerprintCacheIsBoundedAndRegistryAware() throws Exception {
		String cache = read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/"
				+ "Ae2FingerprintCache.java");
		assertTrue(cache.contains("MAX_ENTRIES"), "必须有条目上限，防止内存无界增长");
		assertTrue(cache.contains("if (registries != provider)"),
				"注册表访问器变化（换存档/重启）必须整表清空，否则可能返回旧注册表的编码");
		assertTrue(cache.contains("if (cache.size() >= MAX_ENTRIES) cache.clear();"),
				"超上限时整表清空（单机器物品种类远小于上限，仅异常场景触发）");
	}

	@Test
	@DisplayName("四个 getLimit 拦截点都先查已乘倍率的最终上限缓存")
	void everyGetLimitInterceptorPeeksEffectiveLimit() throws Exception {
		String[] mixins = {
			"src/main/java/com/ayoshiko/productivebeesgenesis/mixin/mek/BasicInventorySlotMixin.java",
			"src/main/java/com/ayoshiko/productivebeesgenesis/mixin/mek/"
					+ "ExtraFactoryInputInventorySlotMixin.java",
			"src/main/java/com/ayoshiko/productivebeesgenesis/mixin/mek/"
					+ "ExtraFactoryOutputInventorySlotMixin.java",
			"src/main/java/com/ayoshiko/productivebeesgenesis/mixin/mek/"
					+ "EMExtraFactoryInputInventorySlotMixin.java",
			"src/main/java/com/ayoshiko/productivebeesgenesis/mixin/mek/"
					+ "EMExtraFactoryOutputInventorySlotMixin.java",
		};
		for (String path : mixins) {
			String source = read(path);
			assertTrue(source.contains("peekEffectiveLimit(stack)"),
					path + " 必须先查最终上限缓存");
			assertTrue(source.contains("storeEffectiveLimit(stack, effective)"),
					path + " 必须回填最终上限缓存，否则每次都重算");
		}
	}

	@Test
	@DisplayName("最终上限缓存以 Item + 倍率版本为键，换供应商时立即失效")
	void effectiveLimitCacheKeyAndInvalidation() throws Exception {
		String cache = read("src/main/java/com/ayoshiko/productivebeesgenesis/inventory/SlotLimitCache.java");
		assertTrue(cache.contains("effectiveVersion == TieredInputSlot.MULTIPLIER_VERSION.get()"),
				"配置 reload 递增版本号后必须失效");
		assertTrue(cache.contains("public void invalidate()"),
				"必须提供本地立即失效入口");

		String mixin = read("src/main/java/com/ayoshiko/productivebeesgenesis/mixin/mek/"
				+ "BasicInventorySlotMixin.java");
		assertTrue(mixin.contains("if (limitCache != null) limitCache.invalidate();"),
				"setInputStackMultiplier 换供应商不递增全局版本号，必须本地清缓存");
		assertTrue(mixin.contains("if (productivebeesgenesis$inputMultiplier == null)"),
				"非本模组分等级槽位必须提前返回，不得污染缓存或改变 Mekanism 原逻辑");
	}
}
