package com.ayoshiko.productivebeesgenesis.mek;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 「产物种类多于物理输出槽」的处理链路接线校验。
 * <br/>
 * 场景：屠夫蜜脾（块）有 4 种物品产物，安装 7 个稳定性升级后概率产物 100% 触发，
 * 但单个并行只有 3 个物品输出槽 —— 原实现的原子提交必然永久失败，机器卡在完成边界。
 */
class CentrifugeOutputOverflowWiringTest {

	private static String read(String relativePath) throws Exception {
		return Files.readString(Path.of(relativePath));
	}

	@Test
	@DisplayName("规划器区分「数量不足」与「无处安放的产物种类」")
	void plannerClassifiesUnplaceableTypes() throws Exception {
		String planner = read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/"
				+ "PbOutputPlacementPlanner.java");

		assertTrue(planner.contains("boolean hasUnplaceableType()"),
				"必须能区分「减半批量无效」的种类溢出");
		assertTrue(planner.contains("if (!sawSpace) unplaceableType = true;"),
				"某种产物在所有槽位都找不到空间才算种类溢出");
		assertTrue(planner.contains("int maxDeferredPerType()") && planner.contains("int slotCapacity()"),
				"需要延迟量与单槽容量以约束隐形缓冲规模");
		assertTrue(planner.contains("boolean placedAny()"),
				"部分写入是延迟提交的前提");
	}

	@Test
	@DisplayName("本地 flush 支持延迟提交：写出放得下的部分，其余留在已提交 pending")
	void flusherSupportsDeferredCommit() throws Exception {
		String flusher = read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/PbRecipeFlusher.java");

		assertTrue(flusher.contains("DEFERRED") && flusher.contains("DEFERRAL_TOO_LARGE"),
				"flush 结果需区分「已部分提交」与「延迟量超预算」");
		assertTrue(flusher.contains(
						"int deferralBudget = alreadyCommitted ? Integer.MAX_VALUE : Math.max(0, maxDeferredPerType);"),
				"输入已扣除的 pending 不再有原子性约束，应允许无限次部分排空");
		assertTrue(flusher.contains("if (deferralBudget <= 0) {"),
				"默认仍是原子提交：空间不足时不做任何修改");
		assertTrue(flusher.contains("consumePlacedItems(completer, pendingOutputs);"),
				"部分提交必须只扣减实际写出的数量，剩余数量保留在 pending");
		assertTrue(flusher.contains("lastPlanUnplaceableType = false;"),
				"诊断信息必须每次 flush 重置，避免流体满被误判为种类溢出");
	}

	@Test
	@DisplayName("重试策略按失败原因分流：数量不足减半，种类溢出改延迟提交")
	void retryStrategySplitsByFailureReason() throws Exception {
		String helper = read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/PbRecipeFlushHelper.java");

		assertTrue(helper.contains("if (!completer.lastPlanHasUnplaceableType()) {"),
				"仅在数量不足时继续减半重试");
		assertTrue(helper.contains("PbDeferralBatchMath.shrinkForDeferral("),
				"种类溢出时按延迟量线性压缩批量，而非 O(log N) 减半");
		assertTrue(helper.contains("int budget = trySize <= 1 ? Integer.MAX_VALUE : completer.lastPlanSlotCapacity();"),
				"单次操作必须允许无限延迟，保证机器不会卡死");
		assertTrue(helper.contains("completer.flushPendingPbOutputsWithDeferral(processIndex, budget)"),
				"延迟提交入口必须接线");
	}

	@Test
	@DisplayName("输入槽兼容性判定改为「任一产物可放」")
	void inputGateAcceptsPartiallyPlaceableRecipes() throws Exception {
		String checker = read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/"
				+ "PbRecipeOutputChecker.java");

		assertFalse(checker.contains("tryMatchTemplate"),
				"完全匹配判定会让 4 种产物 / 3 槽的配方被工厂输入槽永久拒收");
		assertTrue(checker.contains("private static boolean canPlace("),
				"改为逐产物判断是否存在可承载的槽位");
	}

	@Test
	@DisplayName("已扣除输入的 pending 不会被丢弃，输入清空后仍继续排空")
	void committedPendingIsNeverDroppedOrStranded() throws Exception {
		String processor = read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/PbRecipeProcessor.java");
		String factoryHelper = read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/"
				+ "MekCentrifugeFactoryHelper.java");
		String tickHandler = read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/"
				+ "MekCentrifugeTickHandler.java");

		assertTrue(processor.contains("public boolean drainCommittedPendingOutputs(int processIndex)"),
				"必须提供输入为空时的排空入口");
		assertTrue(factoryHelper.contains("pbProcessor.drainCommittedPendingOutputs(i);"),
				"工厂空输入分支必须继续排空 pending");
		assertTrue(tickHandler.contains("pbProcessor.drainCommittedPendingOutputs(0);"),
				"基础离心机空输入分支必须继续排空 pending");
		assertTrue(processor.contains("if (!recipeCompleters[processIndex].hasCommittedPendingOutputs()) {\n"
						+ "\t\t\t\t\trecipeCompleters[processIndex].resetPendingRecipe();"),
				"配方切换不得丢弃已扣除输入的产物");
	}
}
