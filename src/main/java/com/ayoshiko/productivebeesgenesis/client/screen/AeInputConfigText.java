package com.ayoshiko.productivebeesgenesis.client.screen;

import com.ayoshiko.productivebeesgenesis.util.NumberFormatter;

/**
	 * AE2 输入配置窗口文本格式化辅助 — 从 {@link GuiAeInputConfig} 拆分的静态工具类。
	 */
final class AeInputConfigText {

	private AeInputConfigText() {
	}

	static String formatCompactAmount(long amount) {
		long safeAmount = Math.max(0L, amount);
		if (safeAmount == Long.MAX_VALUE) return "∞";
		return NumberFormatter.formatCompact(safeAmount);
	}
}
