package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.stacks.AEItemKey;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;

/**
 * 候选扫描的数量、保留线与标记侧表（复用，避免每 tick 分配装箱 Map）
 * <p>
 * 从 {@code Ae2OutputPusher.PullCandidateAmounts} 提为顶层类（原文件超 500 行阈值）。
 * 由 {@link Ae2PushBuffers#borrowScanCandidateAmounts()} 出借给 {@link Ae2InputPuller}。
 */
final class Ae2PullCandidateAmounts {

	private final Object2IntOpenHashMap<AEItemKey> amounts = new Object2IntOpenHashMap<>(16);
	private final Object2LongOpenHashMap<AEItemKey> reserves = new Object2LongOpenHashMap<>(16);
	private final Object2IntOpenHashMap<AEItemKey> flags = new Object2IntOpenHashMap<>(16);

	void clear() {
		amounts.clear();
		reserves.clear();
		flags.clear();
	}

	void put(AEItemKey key, int amount, Ae2PullDecision decision) {
		amounts.put(key, amount);
		reserves.put(key, decision.reserveFloor);
		flags.put(key, (decision.unlimited ? 1 : 0) | (decision.marked ? 2 : 0));
	}

	void apply(AEItemKey key, Ae2InputPuller.PullEntry entry, boolean unlimitedMode) {
		int value = flags.getInt(key);
		entry.unlimited = unlimitedMode && (value & 1) != 0;
		entry.marked = (value & 2) != 0;
		entry.reserveFloor = reserves.getLong(key);
	}

	int get(AEItemKey key) {
		return amounts.getInt(key);
	}
}
