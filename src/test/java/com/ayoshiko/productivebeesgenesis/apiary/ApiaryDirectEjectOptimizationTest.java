package com.ayoshiko.productivebeesgenesis.apiary;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 蜂箱→离心机直连链路的优化接线校验。
 * <br/>
 * 可处理性掩码按拓扑和配方代际失效；直交的容量与失败行为由 Minecraft 行为测试覆盖。
 */
class ApiaryDirectEjectOptimizationTest {

	private static final String SRC = "src/main/java/com/ayoshiko/productivebeesgenesis/apiary/";

	private static String read(String fileName) throws Exception {
		return Files.readString(Path.of(SRC + fileName));
	}

	@Test
	@DisplayName("可处理性掩码：独立缓存类 + 组件 hash 预筛 + 拓扑/配方双失效")
	void acceptanceCacheIsExtractedAndCheap() throws Exception {
		String cache = read("ApiaryCentrifugeAcceptanceCache.java");
		assertTrue(cache.contains("if (componentHashes[i] != componentHash) continue;"),
				"深比较前必须先比 int hash：PB 蜜脾共用同一 Item，isSameItemSameComponents 无法快速失败");
		assertTrue(cache.contains("targetList != targetsRef")
						&& cache.contains("recipeVersion != ProductiveBeesGenesis.RECIPE_VERSION.get()"),
				"拓扑变化与配方重载都要失效");
		assertTrue(cache.contains("catch (Exception | LinkageError e)"),
				"跨方块实体调用需按不可处理降级，不阻断蜂箱 tick");

		String handler = read("ApiaryDirectEjectHandler.java");
		assertTrue(handler.contains("return acceptanceCache.maskFor(stack, targetList);"),
				"直连处理器应委托缓存类，自身不再维护掩码数组");
	}
}
