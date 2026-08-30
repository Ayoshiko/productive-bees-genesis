package com.ayoshiko.productivebeesgenesis.mek;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 多流体 PB 预留扫描与处理阶段的同 tick 配方查找复用接线校验。 */
class PbRecipeReservationCacheWiringTest {

	private static String read(String relativePath) throws Exception {
		return Files.readString(Path.of(relativePath));
	}

	@Test
	@DisplayName("预留扫描保存按进程输入快照，处理阶段验证后复用")
	void reservationScanPublishesValidatedRecipeSnapshot() throws Exception {
		String processor = read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/PbRecipeProcessor.java");
		String helper = read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/PbRecipeProcessorStateHelper.java");
		String coordinator = read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/MekCentrifugeFactoryHelper.java");

		assertTrue(processor.contains("private final ItemStack[] reservedPbInputs"));
		assertTrue(processor.contains("private final RecipeHolder<CentrifugeRecipe>[] reservedPbRecipes"));
		assertTrue(processor.contains("reservedPbRecipeCacheTick == tick"));
		assertTrue(processor.contains("reservedPbRecipeCacheVersion == ProductiveBeesGenesis.RECIPE_VERSION.get()"));
		assertTrue(processor.contains("reservedPbInputs[processIndex] == input"));
		assertTrue(helper.contains("cachedInputs[i] = input"));
		assertTrue(helper.contains("cachedRecipes[i] = recipe"));
		assertTrue(coordinator.contains("pbProcessor.hasReservedPbRecipe(i, input)"));
		assertTrue(coordinator.contains("pbProcessor.getReservedPbRecipe(i)"));
		assertFalse(coordinator.contains("isMyriad ? null : pbProcessor.findPbRecipe(input)"));
	}

	@Test
	@DisplayName("预留配方缓存按真实 tick 门控并在重载时清空")
	void reservationCacheInvalidationIsExplicit() throws Exception {
		String processor = read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/PbRecipeProcessor.java");
		assertTrue(processor.contains("if (tick == lastFluidReservationTick) return;"));
		assertTrue(processor.contains("clearReservedPbRecipeCache();"));
		assertTrue(processor.contains("clearReservedPbRecipeCache();\n\t}"));
		assertTrue(processor.contains("checkRecipeVersion();\n\t\treservedPbRecipeCacheTick = tick;"));
	}
}
