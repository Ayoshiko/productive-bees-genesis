package com.ayoshiko.productivebeesgenesis.apiary;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 基因小食输入槽在方块物品、扳手、等级升级和工作台合成路径上的接线回归。
 * <p>
 * 这些路径依赖 NeoForge/Mekanism 的运行时方块实体，普通 JVM 测试无法安全构造完整实例，
 * 因此用源码级断言固定各路径必须经过的边界。实际 NBT 编解码仍由 Mekanism 容器组件执行。
 */
class ApiaryGeneTreatPersistenceWiringTest {

	private static final String APIARY = "src/main/java/com/ayoshiko/productivebeesgenesis/apiary/";

	private static String read(String relativePath) throws Exception {
		return Files.readString(Path.of(relativePath));
	}

	@Test
	@DisplayName("gene treat slot is retained by drop and wrench NBT paths")
	void dropAndWrenchPathsPersistGeneTreat() throws Exception {
		String serializer = read(APIARY + "ApiaryNbtSerializer.java");
		String persistence = read(APIARY + "ApiaryTilePersistence.java");
		String tile = read(APIARY + "TileEntityMekApiary.java");

		assertTrue(serializer.contains("NBT_KEY_DROP_GENE_TREAT_SLOT"));
		assertTrue(serializer.contains("tile.getGeneTreatSlot().serializeNBT(provider)"));
		assertTrue(serializer.contains("tile.getGeneTreatSlot().deserializeNBT(provider"));
		assertTrue(persistence.contains("tile.nbtSerializer().saveCustomData(provider)"));
		assertTrue(persistence.contains("tile.nbtSerializer().loadApiaryState(nbt, provider)"));
		assertTrue(tile.contains("ApiaryTilePersistence.saveAdditional(this, nbt, provider)"));
		assertTrue(tile.contains("ApiaryTilePersistence.loadAdditional(this, nbt, provider)"));
	}

	@Test
	@DisplayName("tier upgrades carry the gene treat snapshot and legacy second input")
	void tierUpgradePathsRestoreGeneTreat() throws Exception {
		String serializer = read(APIARY + "ApiaryNbtSerializer.java");
		String factory = read(APIARY + "TileEntityMekApiaryFactory.java");
		String upgradeData = read(APIARY + "ApiaryUpgradeData.java");

		assertTrue(serializer.contains("List.of(tile.getCageInSlot(), tile.getGeneTreatSlot())"));
		assertTrue(serializer.contains("tile.getGeneTreatSlot().serializeNBT(provider)"));
		assertTrue(serializer.contains("data.geneTreatSlotNbt"));
		assertTrue(upgradeData.contains("public final CompoundTag geneTreatSlotNbt"));
		assertTrue(factory.contains("data.inputSlots.size() > 1"));
		assertTrue(factory.contains("getGeneTreatSlot().deserializeNBT(provider"));
	}

	@Test
	@DisplayName("attached item component order leaves the new slot at the end")
	void blockItemAndCraftingPathsKeepSlotOrder() throws Exception {
		String registrar = read(APIARY + "MekApiaryContainerRegistrar.java");
		String recipe = read("src/main/java/com/ayoshiko/productivebeesgenesis/recipe/ApiaryShapedRecipe.java");

		assertTrue(registrar.contains("buildApiaryItemSlots(outputSlotCount)"));
		assertTrue(registrar.contains("ApiarySlotManager::isGeneTreat"));
		assertTrue(registrar.contains("ContainerType.ITEM.addDefaultCreators"));
		assertTrue(recipe.contains("ApiaryCraftingDataTransfer.transferAllBlockEntityData"));
		String transfer = read("src/main/java/com/ayoshiko/productivebeesgenesis/recipe/ApiaryCraftingDataTransfer.java");
		assertTrue(recipe.contains("mergeAttachedItemDataIntoFallback(machineInputs, fallback)"));
		assertTrue(transfer.contains("ItemHandlerHelper.insertItemStacked(target, stack.copy(), false)"));
	}
}
