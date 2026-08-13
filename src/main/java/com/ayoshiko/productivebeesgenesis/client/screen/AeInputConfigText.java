package com.ayoshiko.productivebeesgenesis.client.screen;

/**
	 * AE2 输入配置窗口文本格式化辅助 — 从 {@link GuiAeInputConfig} 拆分的静态工具类。
	 */
final class AeInputConfigText {

	private AeInputConfigText() {
	}

	static String formatCompactAmount(long amount) {
		long safeAmount = Math.max(0L, amount);
		if (safeAmount < 1_000L) return Long.toString(safeAmount);
		long divisor = 1_000L;
		char[] suffixes = {'K', 'M', 'G', 'T', 'P', 'E'};
		for (char suffix : suffixes) {
			if (divisor > Long.MAX_VALUE / 1_000L || safeAmount < divisor * 1_000L) {
				long whole = safeAmount / divisor;
				if (whole >= 100L) return whole + Character.toString(suffix);
				long tenth = (safeAmount % divisor) / Math.max(1L, divisor / 10L);
				return whole + "." + tenth + suffix;
			}
			divisor *= 1_000L;
		}
		return Long.toString(safeAmount);
	}
}
