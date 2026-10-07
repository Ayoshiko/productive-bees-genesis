package com.ayoshiko.productivebeesgenesis.apiculture.compat.ae2;

import appeng.api.stacks.KeyCounter;
import java.util.function.Consumer;

/** 只调整含蜂业桥的网格显示聚合；单次实际转移和精确账本不参与饱和。 */
public interface SafeStorageAggregation {
	boolean pbgSafeAggregationAvailable();
	static boolean collect(Consumer<KeyCounter> collect, KeyCounter output) {
		boolean valid = true;
		var contribution = new KeyCounter(); collect.accept(contribution);
		for (var entry : contribution) {
			long before = output.get(entry.getKey()), amount = entry.getLongValue();
			// 负值是兼容故障，保持负标记，不能被后续正数加法掩盖。
			if (before < 0 || amount < 0) { output.set(entry.getKey(), -1); valid = false; }
			else output.set(entry.getKey(), amount > Long.MAX_VALUE - before ? Long.MAX_VALUE : before + amount);
		}
		return valid;
	}
}
