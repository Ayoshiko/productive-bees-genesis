package com.ayoshiko.productivebeesgenesis.apiary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 蜂箱批量产出的「机器级状态一轮一次」接线校验（源码级断言，不需要 Minecraft 运行时）。
 * <p>
 * <b>背景</b>：{@code flushPendingProductions} 按蜜蜂类型分组，对<b>每个组</b>调用一次
 * {@code processBatchProduce}。而 {@code ApiaryUpgradeHandler} 的所有倍率/flags 查询最终都落到
 * {@link ApiaryUpgradeCache}，其每个 getter 首行都是
 * {@code internalCounter.incrementAndGet()} —— 一次 AtomicLong 锁定自增（100 次调用刷新守卫）。
 * <p>
 * <b>用户场景（问题①）</b>：蜂箱每个蜜蜂格子放不同种类的蜜蜂时，分组数 = 槽位数
 * （高阶工厂可达 49），逐组重复查询等于把固定开销乘以 N。这正是「混养比纯养更卡」的直接来源，
 * 与 {@code BeeSlotTickProcessor.tick()} 里已经做过一次的
 * 「CREATIVE 状态循环外读取一次」属同一类修复。
 * <p>
 * 纯逻辑测不出这类回归（结果完全正确、只是更慢），故用源码断言把调用点钉住。
 */
class ApiaryBatchUpgradeSnapshotTest {

	private static final String APIARY = "src/main/java/com/ayoshiko/productivebeesgenesis/apiary/";
	private static final String PROCESSOR = APIARY + "BeeProduceProcessor.java";
	private static final String TICK_PROCESSOR = APIARY + "BeeSlotTickProcessor.java";
	private static final String SNAPSHOT = APIARY + "ApiaryBatchUpgradeSnapshot.java";

	@Test
	@DisplayName("逐组处理的 processBatchProduce 不得再查询机器级升级状态")
	void perGroupProcessingDoesNotQueryMachineLevelUpgrades() throws Exception {
		String body = methodBody(Files.readString(Path.of(PROCESSOR)), "public void processBatchProduce(");
		// 这些取值一轮 flush 内恒定，必须由 ApiaryBatchUpgradeSnapshot 提供
		for (String forbidden : List.of(
				"upgradeHandler.getGeneSamplerCount()",
				"upgradeHandler.getProductivityMultiplier()",
				"upgradeHandler.hasCombBlockUpgrade()",
				"apiary.getPbUpgradeInstalledCount(",
				"apiary.isDirectAeOutputEnabled()",
				"apiary.isDirectContainerOutputEnabled()",
				"apiary.isCentrifugePriorityEnabled()",
				"apiary.isDirectEjectEnabled()")) {
			assertFalse(body.contains(forbidden),
					"processBatchProduce 是按蜂种分组逐组调用的，这里不得出现机器级查询 "
							+ forbidden + "（会随分组数放大，混养时分组数 = 槽位数）");
		}
		assertTrue(body.contains("ApiaryBatchUpgradeSnapshot upgrades"),
				"processBatchProduce 必须接收本轮 flush 共享的机器级快照");
		assertTrue(body.contains("upgrades.geneSamplerCount()")
						&& body.contains("upgrades.productivityMultiplier()")
						&& body.contains("upgrades.discardUselessByproducts()"),
				"采样相关的机器级取值必须改读快照");
	}

	@Test
	@DisplayName("多花蜂判定每分组只做一次并传入，不在内层重复")
	void multiFlowerCheckIsComputedOncePerGroup() throws Exception {
		String processor = Files.readString(Path.of(PROCESSOR));
		String body = methodBody(processor, "public void processBatchProduce(");
		int occurrences = body.split(
				"MultiFlowerBeeAdapter\\.isMultiFlowerBee\\(beeTypeKey\\)", -1).length - 1;
		assertEquals(0, occurrences,
				"processBatchProduce 内不得重新判定多花蜂：该结论按 typeKey 恒定，必须由调用方传入");
		assertTrue(body.contains("boolean multiFlowerBee)"),
				"多花蜂判定必须作为参数传入");

		String tickProcessor = Files.readString(Path.of(TICK_PROCESSOR));
		assertTrue(tickProcessor.contains("boolean feederDependentProduce = "
						+ "MultiFlowerBeeAdapter.isMultiFlowerBee(typeKey);"),
				"每个分组只允许在 flush 层判定一次多花蜂");
	}

	/** 截取指定方法的方法体（到下一个带 javadoc 的成员为止），避免把同文件其它方法算进来。 */
	private static String methodBody(String source, String signaturePrefix) {
		int start = source.indexOf(signaturePrefix);
		assertTrue(start > 0, "找不到方法 " + signaturePrefix);
		int end = source.indexOf("\n\t/**", start);
		return source.substring(start, end > 0 ? end : source.length());
	}

	@Test
	@DisplayName("快照在输出空间检查之后、分组循环之前采集一次")
	void snapshotIsCapturedOncePerFlushAfterOutputCheck() throws Exception {
		String source = Files.readString(Path.of(TICK_PROCESSOR));
		int captureIndex = source.indexOf("ApiaryBatchUpgradeSnapshot.capture(tile, upgradeHandler)");
		int outputFullIndex = source.indexOf("if (slotManager.isOutputFull()) {\n\t\t\t// 输出已满");
		int groupLoopIndex = source.indexOf(
				"for (int groupIndex = 0; groupIndex < activePendingProductionGroupCount; groupIndex++)");
		assertTrue(captureIndex > 0, "flush 层必须采集一次机器级快照");
		assertTrue(outputFullIndex > 0, "找不到输出空间检查点");
		assertTrue(groupLoopIndex > 0, "找不到分组循环");
		assertTrue(captureIndex > outputFullIndex,
				"采集必须在输出空间检查之后：无需 flush 时不应做任何升级查询");
		assertTrue(captureIndex < groupLoopIndex,
				"采集必须在分组循环之前：否则又变成逐组查询");
		int secondCapture = source.indexOf("ApiaryBatchUpgradeSnapshot.capture(",
				captureIndex + 1);
		assertEquals(-1, secondCapture, "一轮 flush 只允许采集一次快照");
	}

	@Test
	@DisplayName("快照只读取确实与蜂种无关的机器级状态")
	void snapshotReadsOnlyMachineLevelState() throws Exception {
		String source = Files.readString(Path.of(SNAPSHOT));
		assertTrue(source.contains("static ApiaryBatchUpgradeSnapshot capture("),
				"快照必须由唯一工厂方法创建");
		// 一旦混入依赖 beeTypeKey 的取值，快照就会在多蜂种场景下给出错误结果
		assertFalse(source.toLowerCase().contains("beetype"),
				"快照不得包含任何依赖蜂种类型的取值");
		assertFalse(source.contains("MultiFlowerBeeAdapter"),
				"多花蜂判定按 typeKey 变化，不属于机器级快照");
		// 采集点必须显式读取这几项（防止有人把 getter 换回 processBatchProduce）
		for (String required : List.of(
				"upgradeHandler.getGeneSamplerCount()",
				"upgradeHandler.getProductivityMultiplier()",
				"apiary.getPbUpgradeInstalledCount(PbUpgradeType.USELESS_BYPRODUCT)",
				"upgradeHandler.hasCombBlockUpgrade()",
				"apiary.getPbUpgradeInstalledCount(PbUpgradeType.ESSENCE_CONVERSION)",
				"apiary.isDirectAeOutputEnabled()",
				"apiary.isDirectContainerOutputEnabled()",
				"apiary.isCentrifugePriorityEnabled() && apiary.isDirectEjectEnabled()")) {
			assertTrue(source.contains(required),
					"快照采集必须包含 " + required);
		}
	}
}
