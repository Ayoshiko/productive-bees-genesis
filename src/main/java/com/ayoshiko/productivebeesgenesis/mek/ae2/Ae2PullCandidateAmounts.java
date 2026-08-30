package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.stacks.AEItemKey;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

/**
 * 候选扫描过程中算出的数量侧表（复用，避免每 tick 分配装箱 Map）
 * <p>
 * 从 {@code Ae2OutputPusher.PullCandidateAmounts} 提为顶层类（原文件超 500 行阈值）。
 * 由 {@link Ae2PushBuffers#borrowScanCandidateAmounts()} 出借给 {@link Ae2InputPuller}。
 */
final class Ae2PullCandidateAmounts {

	private final Object2IntOpenHashMap<AEItemKey> amounts = new Object2IntOpenHashMap<>(16);

	void clear() {
		amounts.clear();
	}

	void put(AEItemKey key, int amount) {
		amounts.put(key, amount);
	}

	int get(AEItemKey key) {
		return amounts.getInt(key);
	}
}
