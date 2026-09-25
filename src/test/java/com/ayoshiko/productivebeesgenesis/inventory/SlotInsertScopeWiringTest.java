package com.ayoshiko.productivebeesgenesis.inventory;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * <p>
 * 同时限定样板目标的调用预算作用域，禁止每槽工作集限制或裁剪第三方已选定的单批数量。
 */
class SlotInsertScopeWiringTest {

	private static final String BASIC_SLOT_MIXIN =
			"src/main/java/com/ayoshiko/productivebeesgenesis/mixin/mek/BasicInventorySlotMixin.java";
	private static final String BASIC_CENTRIFUGE_SLOTS =
			"src/main/java/com/ayoshiko/productivebeesgenesis/mek/MekCentrifugeSlotManager.java";
	private static final String VANILLA_FACTORY_SLOTS =
			"src/main/java/com/ayoshiko/productivebeesgenesis/mek/TileEntityMekCentrifugeFactory.java";
	private static final String PATTERN_PROVIDER_TARGET =
			"src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/CentrifugePatternProviderTarget.java";

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
	void outputRollbackShortCircuitsForInputSlots() throws Exception {
		String mixin = read(BASIC_SLOT_MIXIN);
		// 性能优化：输入槽无需输出槽退回保护，发配插入热路径上应提前短路（省去每次插入的冗余配方校验）；
		// 短路必须先于 ownSlot 判定与昂贵的 isItemValidForInsertion。
		int inputGuard = mixin.indexOf("if (productivebeesgenesis$inputSlot) return;");
		assertTrue(inputGuard > 0, "退回保护应对输入槽提前短路");
		int externalValidCheck = mixin.indexOf("isItemValidForInsertion(stack, AutomationType.EXTERNAL)");
		assertTrue(externalValidCheck < 0 || inputGuard < externalValidCheck,
				"输入槽短路必须排在昂贵的 isItemValidForInsertion(EXTERNAL) 之前");
		// 输入槽装配点必须显式标记，否则优化不生效（漏标只是少一次优化、不影响正确性）。
		assertTrue(read(BASIC_CENTRIFUGE_SLOTS).contains("productivebeesgenesis$markInputSlot()"),
				"基础离心机输入槽应标记为输入槽以启用退回保护短路");
		assertTrue(read(VANILLA_FACTORY_SLOTS).contains("productivebeesgenesis$markInputSlot()"),
				"原版等级工厂输入槽应标记为输入槽以启用退回保护短路");
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
	void dispatchGuardIsScopedToOwnTargetAndNeverClipsBatchSize() throws Exception {
		String source = read(PATTERN_PROVIDER_TARGET);
		assertTrue(source.contains("storage instanceof CentrifugeExternalAeStorage"));
		assertTrue(source.contains("return delegate.insert(what, amount, mode);"));
		assertTrue(source.contains("mode == Actionable.MODULATE && amount > 0"));
		assertTrue(source.contains("CentrifugeDispatchScope.externalPushOverBudget()"));
		assertFalse(read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/"
				+ "CentrifugeExternalAeStorage.java").contains("externalPushOverBudget"),
				"普通存储总线/接口不得消耗样板发配预算");
		assertTrue(read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/"
				+ "Ae2IntegrationLoader.java").contains("CentrifugeDispatchScope.reset()"));
	}

	@Test
	void machinesDoNotReintroducePerSlotExternalInsertThrottle() throws Exception {
		// 防回归：工厂/基础离心机绝不能再注册每槽「工作集」节流——那会把 ECO/EAEP/闪电 的大批次
		// 翻倍截断成小批、退化成逐份滴流，且填不满机器真实容量（超大堆叠）。外部插入放行到真实容量。
		for (String path : new String[]{VANILLA_FACTORY_SLOTS, BASIC_CENTRIFUGE_SLOTS}) {
			String source = read(path);
			assertFalse(source.contains("externalInputPolicy.register"),
					"不得重新引入每槽工作集节流：" + path);
			assertFalse(source.contains("recommendedWorkingSet"),
					"不得重新引入工作集深度估算：" + path);
		}
	}
}
