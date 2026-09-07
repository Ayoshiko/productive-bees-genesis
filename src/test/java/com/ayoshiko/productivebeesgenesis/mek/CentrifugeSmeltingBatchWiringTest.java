package com.ayoshiko.productivebeesgenesis.mek;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 熔炼配方批次能量账本在工厂公共契约与兼容工厂 tick 入口的接线校验。 */
class CentrifugeSmeltingBatchWiringTest {

	private static final String FACTORY_DELEGATE =
			"src/main/java/com/ayoshiko/productivebeesgenesis/mek/IFactoryPbDelegateAccess.java";
	private static final String BASIC_CENTRIFUGE_TICK_HANDLER =
			"src/main/java/com/ayoshiko/productivebeesgenesis/mek/MekCentrifugeTickHandler.java";
	private static final String VANILLA_FACTORY_TICK_HELPER =
			"src/main/java/com/ayoshiko/productivebeesgenesis/mek/FactoryUpgradeStateHelper.java";
	private static final String SMELTING_BATCH_HELPER =
			"src/main/java/com/ayoshiko/productivebeesgenesis/mek/MekCentrifugeFactoryHelper.java";
	private static final String CACHED_RECIPE_BATCH_MIXIN =
			"src/main/java/com/ayoshiko/productivebeesgenesis/mixin/mek/CachedRecipeBatchAccelMixin.java";
	private static final String AE2_OUTPUT_HOST_BASE =
			"src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/IAe2OutputHostBase.java";
	private static final String AE2_ENERGY_INJECTOR =
			"src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/Ae2EnergyInjector.java";
	private static final String ENERGY_SCALING =
			"src/main/java/com/ayoshiko/productivebeesgenesis/mek/MekCentrifugeEnergyScaling.java";
	private static final List<String> COMPAT_FACTORY_SOURCES = List.of(
			"src/main/java/com/ayoshiko/productivebeesgenesis/compat/mekanism_extras/"
					+ "TileEntityExtraMekCentrifugeFactory.java",
			"src/main/java/com/ayoshiko/productivebeesgenesis/compat/emextras/"
					+ "TileEntityEMExtraMekCentrifugeFactory.java"
	);

	private static String read(String relativePath) throws Exception {
		return Files.readString(Path.of(relativePath));
	}

	private static String methodBody(String source, String signature, String sourcePath) {
		int signatureStart = source.indexOf(signature);
		if (signatureStart < 0) {
			throw new AssertionError("找不到方法签名 " + signature + "：" + sourcePath);
		}
		int bodyStart = source.indexOf('{', signatureStart + signature.length());
		if (bodyStart < 0) {
			throw new AssertionError("找不到方法体：" + sourcePath);
		}
		int depth = 0;
		for (int index = bodyStart; index < source.length(); index++) {
			char current = source.charAt(index);
			if (current == '{') {
				depth++;
			} else if (current == '}' && --depth == 0) {
				return source.substring(bodyStart + 1, index);
			}
		}
		throw new AssertionError("方法体大括号不完整：" + sourcePath);
	}

	private static void assertEnergyCallsSurroundBatch(String method, String batchCall, String sourcePath) {
		int injectIndex = method.indexOf("productivebeesgenesis$injectAe2Energy(batchMultiplier)");
		int batchIndex = method.indexOf(batchCall);
		int refillIndex = method.indexOf("productivebeesgenesis$refillAe2EnergyAfterBatch()");

		assertTrue(injectIndex >= 0, "批次入口缺少按倍率预注能：" + sourcePath);
		assertTrue(batchIndex >= 0, "批次入口缺少共享能量账本调用：" + sourcePath);
		assertTrue(refillIndex >= 0, "批次出口缺少标准容量回填：" + sourcePath);
		assertTrue(injectIndex < batchIndex, "必须先准备整批能量，再执行熔炼批次：" + sourcePath);
		assertTrue(batchIndex < refillIndex, "必须等熔炼批次完成后再恢复并回填标准容量：" + sourcePath);
	}

	@Test
	@DisplayName("所有 IFactoryPbDelegateAccess 工厂默认启用熔炼批次能量预算")
	void factoryDelegateEnablesSmeltingEnergyBudgetByDefault() throws Exception {
		String source = read(FACTORY_DELEGATE);
		String method = methodBody(source,
				"default boolean productivebeesgenesis$usesSmeltingEnergyBudget()", FACTORY_DELEGATE);

		assertTrue(method.contains("return true;"),
				"工厂公共委托必须默认启用熔炼能量预算，避免兼容工厂漏写覆盖方法");
		assertFalse(method.contains("return false;"),
				"工厂默认值不得继承普通 AE2 宿主的关闭语义");
	}

	@Test
	@DisplayName("熔炼批次先打开账本并解除暂停，再执行完整 tick 后绑定最新缓存")
	void smeltingBatchBindsCachesCreatedByTheFullTick() throws Exception {
		String method = methodBody(read(SMELTING_BATCH_HELPER),
				"public static boolean runSmeltingBatch(", SMELTING_BATCH_HELPER);
		int activateIndex = method.indexOf("BatchEnergyLedger.activate(ledger)");
		int unpauseIndex = method.indexOf("monitor.unpause()", activateIndex);
		int fullTickIndex = method.indexOf("fullTick.getAsBoolean()", activateIndex);
		int snapshotIndex = method.indexOf("currentSmeltingRecipes(monitors)");
		int startBatchIndex = method.indexOf("productivebeesgenesis$startBatch(extraTicks)");

		assertTrue(activateIndex >= 0, "完整 tick 必须先进入共享能量账本作用域");
		assertTrue(unpauseIndex > activateIndex, "必须在账本作用域内显式解除监视器的旧暂停状态");
		assertTrue(fullTickIndex > unpauseIndex, "解除暂停后才能执行本批次的完整 tick");
		assertTrue(snapshotIndex > fullTickIndex, "必须在完整 tick 创建或替换缓存后读取监视器快照");
		assertTrue(startBatchIndex > snapshotIndex, "必须对完整 tick 后取得的最新缓存启动虚拟 tick 批次");
		assertFalse(method.substring(activateIndex, fullTickIndex).contains("currentSmeltingRecipes(monitors)"),
				"不得在完整 tick 前捕获即将失效的缓存快照");
	}

	@Test
	@DisplayName("非 marginal 配方在活动账本中也延迟扣能")
	void activeLedgerDefersNonMarginalRecipeEnergyCharge() throws Exception {
		String method = methodBody(read(CACHED_RECIPE_BATCH_MIXIN),
				"private void productivebeesgenesis$chargeFullRecipeTick(int operations, CallbackInfo ci)",
				CACHED_RECIPE_BATCH_MIXIN);
		int ledgerIndex = method.indexOf(
				"BatchEnergyLedger ledger = productivebeesgenesis$effectiveEnergyLedger()");
		int guardedReturnIndex = method.indexOf(
				"if (!productivebeesgenesis$marginalEnergyPricing && ledger == null) return;");
		int nonMarginalIndex = method.indexOf("if (!productivebeesgenesis$marginalEnergyPricing) {");
		int ledgerAddIndex = method.indexOf(
				"ledger.add(SaturatingMath.saturatingMultiply(energyPerTick, operations))");
		int cancelIndex = method.indexOf("ci.cancel()", ledgerAddIndex);

		assertTrue(ledgerIndex >= 0, "完整配方 tick 必须读取线程作用域或显式绑定的活动账本");
		assertTrue(guardedReturnIndex > ledgerIndex,
				"非 marginal 配方只能在没有活动账本时放行 Mekanism 原始即时扣能");
		assertTrue(nonMarginalIndex > guardedReturnIndex,
				"活动账本下的非 marginal 配方必须进入线性能耗记账分支");
		assertTrue(ledgerAddIndex > nonMarginalIndex,
				"非 marginal 配方的线性能耗必须累加到共享账本");
		assertTrue(cancelIndex > ledgerAddIndex,
				"记账后必须取消原始 useEnergy，避免同一完整 tick 重复扣能");
	}

	@Test
	@DisplayName("熔炼与蜜脾共用标准容量，批次前后按差额补电")
	void smeltingUsesStandardCapacityLikeHoneycombProcessing() throws Exception {
		String source = read(AE2_OUTPUT_HOST_BASE);
		String inject = methodBody(source,
				"default void productivebeesgenesis$injectAe2Energy(int batchMultiplier)", AE2_OUTPUT_HOST_BASE);
		int normalizeIndex = inject.indexOf("MekCentrifugeEnergyScaling.normalizeCapacity(this)");
		int injectIndex = inject.indexOf(
				"productivebeesgenesis$injectAe2EnergyIntoPreparedCapacity(0, 0L)");

		assertTrue(normalizeIndex >= 0, "必须恢复升级派生的标准容量");
		assertTrue(injectIndex > normalizeIndex, "必须在标准容量确定后按剩余空间补电");
		assertFalse(inject.contains("getRequiredEnergyForBatch("), "不再按熔炼批次需求准备容量");
		assertFalse(inject.contains("prepareBatchCapacity(") || inject.contains("ensureCapacity("),
				"熔炼不得因时间加速扩大本地容量");

		String refill = methodBody(source,
				"default void productivebeesgenesis$refillAe2EnergyAfterBatch()", AE2_OUTPUT_HOST_BASE);
		assertTrue(refill.contains("productivebeesgenesis$injectAe2EnergyIntoPreparedCapacity(0, 0L)"),
				"批后应只补满已准备容量，不重新计算或调整容量");
		assertFalse(refill.contains("productivebeesgenesis$injectAe2Energy(0)"),
				"倍率 0 重新进入容量准备会在外部电缆填充窗口前缩容");
		assertFalse(refill.contains("normalizeCapacity(") || refill.contains("prepareBatchCapacity("),
				"批后回填不得改变批次容量");

		String prepare = methodBody(read(ENERGY_SCALING),
				"public static void prepareBatchCapacity(PbRecipeContext context, long required)", ENERGY_SCALING);
		int targetIndex = prepare.indexOf("long targetCapacity = batchCapacity(normalCapacity, required)");
		int setCapacityIndex = prepare.indexOf("container.setMaxEnergy(targetCapacity)");
		int firstSetCapacityCall = prepare.indexOf("setMaxEnergy(");
		assertTrue(targetIndex >= 0, "容量辅助方法必须一次计算标准容量与批次需求的较大值");
		assertTrue(setCapacityIndex > targetIndex, "目标确定后才能直接修改一次容器容量");
		assertTrue(firstSetCapacityCall == prepare.lastIndexOf("setMaxEnergy("),
				"稳定容量准备不得包含第二次 setMaxEnergy 缩扩往返");
		assertFalse(prepare.contains("normalizeCapacity(") || prepare.contains("ensureCapacity("),
				"稳定容量辅助方法内部也不得退回两阶段缩容再扩容");
	}

	@Test
	@DisplayName("AE2 活动批次只提取预计算需求与当前库存的缺口")
	void ae2InjectionUsesPrecomputedBatchShortfall() throws Exception {
		String hostSource = read(AE2_OUTPUT_HOST_BASE);
		String preparedInjection = methodBody(hostSource,
				"private void productivebeesgenesis$injectAe2EnergyIntoPreparedCapacity(", AE2_OUTPUT_HOST_BASE);
		assertTrue(preparedInjection.contains(
				"Ae2EnergyInjector.injectEnergy(this, batchMultiplier, requiredEnergy)"),
				"宿主必须把已计算需求传入注能器，避免再次扫描全部输入槽");

		String injector = methodBody(read(AE2_ENERGY_INJECTOR),
				"public static long injectEnergy(IAe2OutputHostBase host, int batchMultiplier, long requiredEnergy)",
				AE2_ENERGY_INJECTOR);
		int normalizeIndex = injector.indexOf("long normalizedRequired = Math.max(0L, requiredEnergy)");
		int shortfallIndex = injector.indexOf("Ae2EnergyMath.requiredShortfall(", normalizeIndex);
		int extractionGuardIndex = injector.indexOf("if (toExtract <= 0L) return 0;", shortfallIndex);
		String compactInjector = injector.replaceAll("\\s+", "");

		assertTrue(normalizeIndex >= 0, "注能器必须先规范化调用方提供的批次需求");
		assertTrue(shortfallIndex > normalizeIndex, "活动批次必须按已有库存计算实际缺口");
		assertTrue(compactInjector.contains(
				"Ae2EnergyMath.requiredShortfall(currentEnergy,normalizedRequired,remainingCapacity)"),
				"缺口计算必须使用当前库存、批次需求和物理剩余容量");
		assertTrue(extractionGuardIndex > shortfallIndex, "零缺口必须在访问 AE2 网格前短路");
		assertFalse(injector.contains("productivebeesgenesis$getRequiredEnergyForBatch("),
				"三参数入口不得重复扫描输入槽计算批次需求");
	}

	@Test
	@DisplayName("基础离心机与原版工厂在熔炼批次前注能并在批次后回填")
	void coreCentrifugesSurroundSmeltingBatchWithEnergyCalls() throws Exception {
		String basicRunTick = methodBody(read(BASIC_CENTRIFUGE_TICK_HANDLER),
				"private boolean runTick(boolean skipPb, int batchMultiplier)", BASIC_CENTRIFUGE_TICK_HANDLER);
		assertEnergyCallsSurroundBatch(basicRunTick, "tile.runSmeltingBatch(", BASIC_CENTRIFUGE_TICK_HANDLER);

		String factorySource = read(VANILLA_FACTORY_TICK_HELPER);
		String factoryRunTick = methodBody(factorySource,
				"public static boolean onUpdateServer(", VANILLA_FACTORY_TICK_HELPER);
		assertEnergyCallsSurroundBatch(factoryRunTick,
				"factory.productivebeesgenesis$runSmeltingBatch(", VANILLA_FACTORY_TICK_HELPER);

		String factoryCoalescedFlush = methodBody(factorySource,
				"public static void onCoalescedFlush(", VANILLA_FACTORY_TICK_HELPER);
		assertEnergyCallsSurroundBatch(factoryCoalescedFlush,
				"factory.productivebeesgenesis$runSmeltingBatch(", VANILLA_FACTORY_TICK_HELPER + "#onCoalescedFlush");
	}

	@Test
	@DisplayName("ME 与 EME 兼容工厂通过 runSmeltingBatch 执行完整 tick 与虚拟 tick")
	void compatibilityFactoryRunTicksUseSmeltingBatch() throws Exception {
		for (String sourcePath : COMPAT_FACTORY_SOURCES) {
			String runTick = methodBody(read(sourcePath),
					"private boolean productivebeesgenesis$runTick(boolean skipPb, int batchMultiplier)", sourcePath);

			assertTrue(runTick.contains("productivebeesgenesis$runSmeltingBatch("),
					"兼容工厂 runTick 必须让首个真实 tick 与虚拟 tick 共用批次能量账本：" + sourcePath);
			assertFalse(runTick.contains("productivebeesgenesis$runLightSmeltingTicks("),
					"兼容工厂 runTick 不得继续在完整 super tick 后单独执行轻量补调：" + sourcePath);
			assertFalse(runTick.contains("boolean sendUpdatePacket = super.onUpdateServer();"),
					"兼容工厂不得在创建熔炼批次账本前先执行并扣除首个完整 tick：" + sourcePath);
			assertEnergyCallsSurroundBatch(runTick,
					"productivebeesgenesis$runSmeltingBatch(", sourcePath);
		}
	}
}
