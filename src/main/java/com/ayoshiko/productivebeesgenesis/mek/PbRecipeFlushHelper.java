package com.ayoshiko.productivebeesgenesis.mek;

import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import org.jetbrains.annotations.NotNull;

/**
	 * PB output flush fallback: binary-search batch retry when a batched flush cannot
	 * fully commit (split from {@link PbRecipeProcessor}, SRP). Stateless helper.
	 */
final class PbRecipeFlushHelper {

	private PbRecipeFlushHelper() {
	}

	/**
	 * 分批减半回退 — O(log N) 次减半尝试 + 批量执行（替代 N 次逐次重试）。成功：batchSize 不变；失败：batchSize /= 2；batchSize=1 失败时跳出。
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
