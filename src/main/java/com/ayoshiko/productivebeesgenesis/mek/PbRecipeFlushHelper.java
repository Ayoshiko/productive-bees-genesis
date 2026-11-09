package com.ayoshiko.productivebeesgenesis.mek;

/** 完成批次只有一个随机结果；拒收后的调用只移交输入和排空原结果。 */
final class PbRecipeFlushHelper {
	private PbRecipeFlushHelper() {
	}

	static boolean flushCompleted(PbRecipeCompleter completer, int processIndex) {
		return completer.commitCompletedInput(processIndex) && completer.flushPendingPbOutputs(processIndex);
	}
}
