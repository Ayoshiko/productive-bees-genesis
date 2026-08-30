package com.ayoshiko.productivebeesgenesis.apiary;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归：per-tile 转化开关关闭时，转化原料不得被当成有效花朵
 * <br/>
 * 缺失该门禁时，末影龙蜜蜂只放黑曜石（其转化原料）也会通过花朵校验，
 * 照常采蜜产出本应靠龙蛋才能获得的蜜脾。
 * 依赖 Minecraft 运行时的类无法在单测中实例化，故按源码断言关键接线。
 */
class FeederConversionFlowerGateTest {

	private static final String FEEDER_MANAGER =
			"src/main/java/com/ayoshiko/productivebeesgenesis/apiary/FeederSlotManager.java";
	private static final String TILE =
			"src/main/java/com/ayoshiko/productivebeesgenesis/apiary/TileEntityMekApiary.java";

	@Test
	void conversionFlowerPathIsGatedByPerTileToggle() throws Exception {
		String source = Files.readString(Path.of(FEEDER_MANAGER));
		assertTrue(source.contains("computeHasValidFlower(ResourceLocation beeTypeKey, boolean conversionEnabled)"));
		assertTrue(source.contains("if (conversionEnabled\n\t\t\t\t&& BeeConversionQueries.hasAnyConversionRecipe(beeTypeKey)"));
	}

	@Test
	void toggleFlipInvalidatesFlowerCache() throws Exception {
		String source = Files.readString(Path.of(FEEDER_MANAGER));
		assertTrue(source.contains("if (lastConversionEnabled != conversionEnabled)"));
		assertTrue(source.contains("public void setConversionEnabledSupplier(BooleanSupplier supplier)"));
	}

	@Test
	void tileInjectsToggleSupplierIntoFeederManager() throws Exception {
		String source = Files.readString(Path.of(TILE));
		assertTrue(source.contains("feederSlotManager.setConversionEnabledSupplier(this::isFeederConversionEnabled)"));
	}

	@Test
	void perBeeFlowerResultUsesFeederSemanticVersionAcrossTicks() throws Exception {
		String manager = Files.readString(Path.of(FEEDER_MANAGER));
		String validation = Files.readString(Path.of(
				"src/main/java/com/ayoshiko/productivebeesgenesis/apiary/ApiaryFlowerValidation.java"));
		String processor = Files.readString(Path.of(
				"src/main/java/com/ayoshiko/productivebeesgenesis/apiary/BeeSlotTickProcessor.java"));

		assertTrue(manager.contains("public int getFlowerCacheVersion()"));
		assertTrue(manager.contains("refreshFlowerCacheState();\n\t\treturn flowerValidityCache.version();"));
		assertTrue(validation.contains("consumeCachedFlowerValid(cacheVersion)"));
		assertTrue(processor.contains("int flowerCacheVersion = feederManager.getFlowerCacheVersion();"));
	}
}
