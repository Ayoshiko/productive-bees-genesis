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
		assertTrue(planner.contains("long maxDeferredPerType()") && planner.contains("int slotCapacity()"),
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
						"long deferralBudget = alreadyCommitted ? Long.MAX_VALUE : Math.max(0, maxDeferredPerType);"),
				"输入已扣除的 pending 不再有原子性约束，应允许无限次部分排空");
		assertTrue(flusher.contains("if (deferralBudget <= 0) {"),
				"默认仍是原子提交：空间不足时不做任何修改");
		assertTrue(flusher.contains("consumePlacedItems(completer, pendingOutputs);"),
				"部分提交必须只扣减实际写出的数量，剩余数量保留在 pending");
		assertTrue(flusher.contains("lastPlanUnplaceableType = false;"),
				"诊断信息必须每次 flush 重置，避免流体满被误判为种类溢出");
		assertTrue(flusher.contains("return planner.placedAll() || (planner.placedAny() || direct)")
				&& flusher.contains("inputs == 1 || planner.maxDeferredPerType() <= planner.slotCapacity()"),
				"准备计划只能在全部放下、单输入或余量不超过一槽预算时通过容量检查");
	}

	@Test
	@DisplayName("完成批次拒收后只排空已付结果，不重选产物或再次扣费")
	void completedRetryPreservesFrozenResults() throws Exception {
		String helper = read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/PbRecipeFlushHelper.java");
		String processor = read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/PbRecipeProcessor.java");

		assertTrue(helper.contains(
				"return completer.commitCompletedInput(processIndex) && completer.flushPendingPbOutputs(processIndex);"),
				"完成批次必须先交接一次原输入，再排空原冻结结果");
		assertFalse(helper.contains("rebuildPendingBatch(") || helper.contains("accumulatePbRecipeOutputsBatch("),
				"已完成批次拒收不得通过减半或重新采样改变已付结果");
		assertFalse(helper.contains("payForPlan(") || helper.contains("energyContainer().extract("),
				"完成批次的排空重试不得再次扣费");
		String processing = processor.substring(processor.indexOf("private boolean tryProcessPbRecipeInternal("),
				processor.indexOf("public boolean drainCommittedPendingOutputs("));
		int pendingGuard = processing.indexOf("if (completer.hasPaidPendingOutputs()");
		int drain = processing.indexOf("!PbRecipeFlushHelper.flushCompleted(completer, processIndex)", pendingGuard);
		int inputRead = processing.indexOf("ItemStack input = context.inputSlot(processIndex).getStack()");
		assertTrue(pendingGuard >= 0 && drain > pendingGuard && inputRead > drain,
				"处理新输入前必须先排空已付结果，拒收时暂停后续生产");
		int freeze = processing.indexOf("completer.freezeCompletedBatch(input)");
		int payment = processing.indexOf("completer.payForPlan(plan", freeze);
		int delivery = processing.indexOf("if (completed > 0) completer.flushCompletedBatch(", payment);
		assertTrue(freeze >= 0 && payment > freeze && delivery > payment,
				"正常完成路径必须先冻结结果、再付款、最后交付，拒收后保持同一批次");
		String completer = read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/PbRecipeCompleter.java");
		String completed = completer.substring(completer.indexOf("boolean flushCompletedBatch("),
				completer.indexOf("boolean flushAfterFactoryAe("));
		int inputCommit = completed.indexOf("if (!commitCompletedInput(processIndex)) return false;");
		int defer = completed.indexOf("factoryOutputBatch.defer(this, processIndex)");
		int fallback = completed.indexOf("return flushPendingPbOutputs(processIndex);");
		assertTrue(inputCommit >= 0 && defer > inputCommit && fallback > defer,
				"延后同机交付或直接回退都必须先交接原输入，产物仍由原进程保管");
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
		assertTrue(factoryHelper.contains("pbProcessor.updateFactoryInputState(i, input.isEmpty());"),
				"工厂空输入分支必须继续排空 pending");
		assertTrue(processor.indexOf("drainCommittedPendingOutputs(process);")
				< processor.indexOf("if (!emptyFactoryProcesses[process])"),
				"持续排空必须在一次性空闲清理的门控之外");
		assertTrue(tickHandler.contains("pbProcessor.drainCommittedPendingOutputs(0);"),
				"基础离心机空输入分支必须继续排空 pending");
		String stateHelper = read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/"
				+ "PbRecipeProcessorStateHelper.java");
		assertTrue(processor.contains("if (!recipeCompleters[i].hasPaidPendingOutputs()) {"),
				"配方重载不得重置已扣输入或尚在付款／交接中的冻结结果");
		assertTrue(processor.contains("PbRecipeProcessorStateHelper.clearPbState(")
				&& processor.contains("PbRecipeProcessorStateHelper.resetPbState("),
				"空闲和强制重置必须继续委托带已付缓冲保护的正式实现");
		String clearState = stateHelper.substring(stateHelper.indexOf("static void clearPbState("),
				stateHelper.indexOf("static void resetPbState("));
		String resetState = stateHelper.substring(stateHelper.indexOf("static void resetPbState("),
				stateHelper.indexOf("static boolean isPbProcessing("));
		String paidGuard = "if (!completer.hasPaidPendingOutputs()) completer.resetPendingRecipe();";
		assertTrue(clearState.contains(paidGuard) && resetState.contains(paidGuard),
				"两个状态清理入口均不得丢弃已付物品、流体或未决付款");
	}
}
