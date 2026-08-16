package com.ayoshiko.productivebeesgenesis.mek;

import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import org.jetbrains.annotations.NotNull;

/**
	 * PB 批量输出回退处理器
	 * <br/>
	 * 批量输出无法完整提交时，按二分回退批量大小重试（从 {@link PbRecipeProcessor} 拆分，SRP）。
	 * 无状态静态辅助类。
	 */
final class PbRecipeFlushHelper {

	private PbRecipeFlushHelper() {
	}

	/**
	 * 分批减半回退 — O(log N) 次减半尝试 + 批量执行（替代 N 次逐次重试）。
	 * 成功后逐步恢复 batchSize，避免高并行本地流体罐刚被 AE2 排空时仍以很小的回退批次循环。
	 * 失败时 batchSize /= 2；batchSize=1 失败时跳出。
	 */
	static int retryBatchedFlush(@NotNull PbRecipeCompleter completer, @NotNull CentrifugeRecipe recipe,
			int processIndex, int modifier, int totalOps) {
		int opsSuccessfullyRun = 0;
		int remaining = totalOps;
		int batchSize = totalOps;
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
			} else {
				if (completer.hasCommittedPendingOutputs()) {
					opsSuccessfullyRun += trySize;
					break;
				}
				completer.resetPendingRecipe();
				if (batchSize <= 1) break; // 连单个 ops 都无法 flush — 输出槽完全满
				batchSize /= 2;
			}
		}
		return opsSuccessfullyRun;
	}
}
