package com.ayoshiko.productivebeesgenesis.mek;

import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import org.jetbrains.annotations.NotNull;

/**
 * PB 批量输出回退处理器
 * <br/>
 * 批量输出无法完整提交时的重试策略（从 {@link PbRecipeProcessor} 拆分，SRP）。无状态静态辅助类。
 * <p>
 * 按失败原因分流，避免无效重试：
 * <ul>
 *   <li><b>数量不足</b>（槽位还有空间，只是装不下这么多）：按二分回退批量，成功后逐步恢复，
 *       保持「输入消耗 ↔ 产物产出」的原子性。</li>
 *   <li><b>种类溢出</b>（某种产物在三个物理输出槽里完全无处安放，如屠夫蜜脾 4 种产物 + 7 个
 *       稳定性升级使概率产物 100% 触发）：减半批量不会减少产物<b>种类</b>，原子提交必然永久
 *       失败 —— 机器会卡在完成边界反复采样+规划。此时改为延迟提交：先把批量按「延迟量 ≤ 单槽
 *       容量」线性压缩，再写入放得下的部分并把其余产物留在 pending 后续排空。</li>
 * </ul>
 */
final class PbRecipeFlushHelper {

	private PbRecipeFlushHelper() {
	}

	/**
	 * 分批回退 — O(log N) 次减半尝试 + 批量执行（替代 N 次逐次重试）。
	 * 成功后逐步恢复 batchSize，避免高并行本地流体罐刚被 AE2 排空时仍以很小的回退批次循环。
	 * 种类溢出时改走延迟提交，单次操作始终能推进，机器不会永久卡死。
	 *
	 * @return 实际成功提交的操作数
	 */
	static int retryBatchedFlush(@NotNull PbRecipeCompleter completer, @NotNull CentrifugeRecipe recipe,
			int processIndex, int modifier, int totalOps) {
		int opsSuccessfullyRun = 0;
		int remaining = totalOps;
		int batchSize = totalOps;
		// 调用方的原子 flush 已判定「有产物种类完全无处安放」时，减半永远不会成功：
		// 直接按线性比例把批量压到延迟量可控的规模，省掉 O(log N) 次无用的采样 + 规划。
		if (completer.lastPlanHasUnplaceableType()) {
			batchSize = PbDeferralBatchMath.shrinkForDeferral(totalOps,
					completer.lastPlanMaxDeferredPerType(), completer.lastPlanSlotCapacity());
		}
		while (remaining > 0) {
			if (batchSize <= 0) batchSize = 1;
			int trySize = Math.min(batchSize, remaining);
			completer.resetPendingRecipe();
			if (trySize == 1) {
				completer.accumulatePbRecipeOutputs(recipe, processIndex, modifier);
			} else {
				completer.accumulatePbRecipeOutputsBatch(recipe, processIndex, modifier, trySize);
			}
			if (completer.flushPendingPbOutputs(processIndex)) {
				opsSuccessfullyRun += trySize;
				remaining -= trySize;
				if (remaining > 0 && batchSize < remaining) {
					batchSize = (int) Math.min((long) remaining, Math.max(1L, (long) batchSize * 2L));
				}
				continue;
			}
			if (completer.hasCommittedPendingOutputs()) {
				// 直输 AE 或延迟提交已扣除输入，剩余产物挂起重试
				opsSuccessfullyRun += trySize;
				break;
			}
			if (!completer.lastPlanHasUnplaceableType()) {
				// 纯数量不足：减半后有机会整批写入，保持配方原子性
				completer.resetPendingRecipe();
				if (batchSize <= 1) break; // 连单个 ops 都无法 flush — 输出槽完全满
				batchSize /= 2;
				continue;
			}
			// 种类溢出：单次操作允许无限延迟（保证至少推进 1 次操作，机器不会卡死）；
			// 批量则先压到「单种延迟量 ≤ 一个输出槽容量」，避免隐形缓冲膨胀。
			int budget = trySize <= 1 ? Integer.MAX_VALUE : completer.lastPlanSlotCapacity();
			int shrunk = PbDeferralBatchMath.shrinkForDeferral(trySize,
					completer.lastPlanMaxDeferredPerType(), budget);
			if (shrunk < trySize) {
				completer.resetPendingRecipe();
				batchSize = shrunk;
				continue;
			}
			if (completer.flushPendingPbOutputsWithDeferral(processIndex, budget)) {
				opsSuccessfullyRun += trySize;
			} else if (!completer.hasCommittedPendingOutputs()) {
				completer.resetPendingRecipe();
			}
			break;
		}
		return opsSuccessfullyRun;
	}
}
