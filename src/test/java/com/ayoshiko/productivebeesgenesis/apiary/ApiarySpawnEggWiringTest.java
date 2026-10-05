package com.ayoshiko.productivebeesgenesis.apiary;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * 资源蜜蜂刷怪蛋直装蜂箱的接线回归测试。
 * <p>
 * 蜂箱方块实体和 NeoForge 容器不能在普通 JVM 单测中安全构造，因此这里锁定
 * 服务端权威入口、客户端发包分支和不复制任意刷怪蛋 NBT 等关键契约。
 */
class ApiarySpawnEggWiringTest {

	private static final String HANDLER =
			"src/main/java/com/ayoshiko/productivebeesgenesis/apiary/ApiaryCageHandler.java";
	private static final String HELPER =
			"src/main/java/com/ayoshiko/productivebeesgenesis/apiary/BeeSpawnEggHelper.java";
	private static final String CLIENT =
			"src/main/java/com/ayoshiko/productivebeesgenesis/apiary/client/ApiaryBeeSlotInteraction.java";
	private static final String PAYLOAD_HANDLER =
			"src/main/java/com/ayoshiko/productivebeesgenesis/network/ApiaryPayloadHandlers.java";
	private static final String PAYLOAD =
			"src/main/java/com/ayoshiko/productivebeesgenesis/network/ApiaryCageOperationPayload.java";
	private static final String SLOT_MANAGER =
			"src/main/java/com/ayoshiko/productivebeesgenesis/apiary/ApiarySlotManager.java";

	@Test
	void helperUsesPbSpawnEggCatalogAndStructuredComponents() throws Exception {
		String source = Files.readString(Path.of(HELPER));
		assertTrue(source.contains("ModItems.SPAWN_EGGS"),
				"所有 PB 专用蜜蜂刷怪蛋都必须走同一个注册列表入口");
		assertTrue(source.contains("spawnEgg.getType(ItemStack.EMPTY)"),
				"专用刷怪蛋必须使用注册时绑定的实体类型");
		assertTrue(source.contains("DataComponents.ENTITY_DATA"));
		assertTrue(source.contains("!entityId.toString().equals(tag.getString(\"id\"))"),
				"必须拒绝被 ENTITY_DATA.id 篡改为其他实体的 PB 刷怪蛋");
		assertTrue(source.contains("ResourceLocation.tryParse(tag.getString(\"type\"))"));
		assertTrue(source.contains("CONFIGURABLE_ENTITY_ID"));
	}

	// PB 初始化、基因附件、扣物与无世界实体由 ClientBeeInputFixture 的真实注册表场景验证。

	@Test
	void legacySimplifiedBeeDataIsNormalizedBeforeCaging() throws Exception {
		String source = Files.readString(Path.of(HANDLER));
		assertTrue(source.contains("normalizeBeeData(occupiedSlot.getBeeData())"),
				"自动蜂笼取出路径必须迁移旧简化 NBT");
		assertTrue(source.contains("normalizeBeeData(targetSlot.getBeeData())"),
				"玩家点槽取出路径必须迁移旧简化 NBT");
		assertTrue(source.contains("hasAttributeAttachment(copy)"));
		assertTrue(source.contains("attachments.contains(\"productivebees:attributes_handler\")"));
	}

	@Test
	void successfulInsertionUsesVanillaCreativeConsumptionAndServerBranch() throws Exception {
		String handler = Files.readString(Path.of(HANDLER));
		assertTrue(handler.contains("cursorEgg.consume(1, player)"));
		String payloadHandler = Files.readString(Path.of(PAYLOAD_HANDLER));
		assertTrue(payloadHandler.contains("OperationType.INSERT_SPAWN_EGG"));
		assertTrue(payloadHandler.contains("apiary.insertBeeFromSpawnEgg(slotIndex, cursor, serverPlayer)"));
	}

	@Test
	void clientSendsSpawnEggOperationOnlyForEmptyBeeSlot() throws Exception {
		String client = Files.readString(Path.of(CLIENT));
		assertTrue(client.contains("BeeSpawnEggHelper.isResourceBeeSpawnEgg(cursor)"));
		assertTrue(client.contains("!beeSlot.isEmpty()"));
		assertTrue(client.contains("OperationType.INSERT_SPAWN_EGG"));
		String payload = Files.readString(Path.of(PAYLOAD));
		assertTrue(payload.contains("INSERT_SPAWN_EGG"));
	}

	@Test
	void cageInputAcceptsAndBatchesResourceBeeSpawnEggs() throws Exception {
		String slotManager = Files.readString(Path.of(SLOT_MANAGER));
		assertTrue(slotManager.contains("BeeSpawnEggHelper.isResourceBeeSpawnEgg(stack)"));

		String handler = Files.readString(Path.of(HANDLER));
		assertTrue(handler.contains("tryInsertBeesFromSpawnEggInput(cageStack, spawnEgg)"));
		assertTrue(handler.contains("Math.min(eggStack.getCount(), countEmptyBeeSlots(beeSlots))"));
		assertTrue(handler.contains("shrinkStack(inserted, Action.EXECUTE)"));
		assertTrue(handler.contains("if (toInsert <= 0) return;"));
		assertFalse(handler.contains("getCageOutSlot().insertItem(beeData"));
	}
}
