package com.ayoshiko.productivebeesgenesis.inventory;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * 槽位插入作用域接线校验。
 * <p>
 * 防的是这条已发生过的回归：退回保护放宽了「外部可否插入」这一基础语义，而它注入在
 * Mekanism 全部机器共用的槽位基类上。一旦丢掉作用域限定或丢掉凭据判定，AE2 样板供应器
 * 在输入槽占满后就会把原料塞进输出槽，机器不加工，合成 CPU 永远等不到产物。
 * 这些约束无法在纯 JVM 单测里跑真实槽位，因此以源码断言形式固化。
 */
class SlotInsertScopeWiringTest {

	private static final String BASIC_SLOT_MIXIN =
			"src/main/java/com/ayoshiko/productivebeesgenesis/mixin/mek/BasicInventorySlotMixin.java";
	private static final String BASIC_CENTRIFUGE_SLOTS =
			"src/main/java/com/ayoshiko/productivebeesgenesis/mek/MekCentrifugeSlotManager.java";
	private static final String VANILLA_FACTORY_SLOTS =
			"src/main/java/com/ayoshiko/productivebeesgenesis/mek/TileEntityMekCentrifugeFactory.java";

	private static String read(String relativePath) throws Exception {
		return Files.readString(Path.of(relativePath));
	}

	@Test
	void rollbackProtectionIsScopedToOwnSlots() throws Exception {
		String source = read(BASIC_SLOT_MIXIN);
		assertTrue(source.contains("if (!productivebeesgenesis$ownSlot) return;"),
				"退回保护必须先判定槽位归属，否则会改写原版 Mekanism 机器的外部插入语义");
	}

	@Test
	void rollbackRequiresExtractionCredential() throws Exception {
		String source = read(BASIC_SLOT_MIXIN);
		assertTrue(source.contains("window.isArmed()"),
				"退回保护必须以「刚被取走」的凭据为前提做前置短路，不能仅凭同种物品就放行");
		assertTrue(source.contains("recordExternalExtraction"),
				"缺少外部提取记录注入时凭据恒为空，退回保护会静默失效");
	}

	@Test
	void extractionRecordingShortCircuitsOnForeignSlots() throws Exception {
		String source = read(BASIC_SLOT_MIXIN);
		int recordAt = source.indexOf("productivebeesgenesis$recordExternalExtraction(int amount");
		assertTrue(recordAt > 0, "未找到外部提取记录注入");
		String body = source.substring(recordAt);
		int ownCheck = body.indexOf("if (!productivebeesgenesis$ownSlot) return;");
		int externalCheck = body.indexOf("AutomationType.EXTERNAL");
		assertTrue(ownCheck > 0 && ownCheck < externalCheck,
				"提取记录落在时间加速放大的热路径上，归属判定必须排在第一位以保持原版机器零开销");
	}

	@Test
	void basicCentrifugeLimitsExternalInputDepth() throws Exception {
		String source = read(BASIC_CENTRIFUGE_SLOTS);
		assertTrue(source.contains("externalInputPolicy.register(inputSlot)"),
				"基础离心机输入槽上限是 64 × 配置倍率（BASIC 默认 16384），必须限制外部一次填入的深度");
		assertTrue(source.contains("productivebeesgenesis$getAccelerationMultiplier()"),
				"工作集必须随 JDTE 时间加速倍率放大，否则手杖加速下会供料不足");
	}

	@Test
	void vanillaFactoryKeepsExternalInsertPolicy() throws Exception {
		String source = read(VANILLA_FACTORY_SLOTS);
		assertTrue(source.contains("externalInputPolicy.register(inputSlot)"),
				"原版等级工厂的外部插入配额不得被移除");
	}
}
