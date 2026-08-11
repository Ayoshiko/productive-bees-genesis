package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.stacks.AEItemKey;

import java.util.List;

/**
 * AE2 输入过滤器客户端快照构建工具（纯静态，无状态）
 * <br/>
 * 从 {@link Ae2InputFilter} 拆分而来，职责（SRP）：把网络同步的平行数组
 * （indices/entries/amounts/visible/unlimited）构建为一次性发布的数组快照，
 * 由调用方统一 volatile 发布。
 */
final class Ae2InputFilterSnapshot {

	private Ae2InputFilterSnapshot() {
	}

	/**
	 * 构建完整快照数组（容量取当前容量与最大索引的较大值）
	 *
	 * @param mode            过滤模式
	 * @param precise         精确模式
	 * @param indices         非空槽位索引（与 entries 平行）
	 * @param entries         条目字符串（与 indices 平行）
	 * @param amounts         直连条目数量（与 indices 平行）
	 * @param visibleAmounts  直连条目可见库存（与 indices 平行，仅客户端展示）
	 * @param unlimitedFlags  直连条目无限提供标记（与 indices 平行）
	 * @param currentCapacity 当前数组容量
	 * @return 构建完成的快照
	 */
	static Snapshot build(Ae2InputFilter.FilterMode mode, boolean precise,
			List<Integer> indices, List<String> entries, List<Long> amounts,
			List<Long> visibleAmounts, List<Boolean> unlimitedFlags, int currentCapacity) {
		int capacity = Math.max(Ae2InputFilter.getDefaultCapacity(), currentCapacity);
		for (int index : indices) {
			if (index >= 0 && index < Ae2InputFilter.getMaxFilterSlots()) {
				capacity = Math.max(capacity, index + 1);
			}
		}
		String[] newSlots = new String[capacity];
		AEItemKey[] newKeys = new AEItemKey[capacity];
		long[] newAmounts = new long[capacity];
		long[] newVisible = new long[capacity];
		boolean[] newUnlimited = new boolean[capacity];
		for (int i = 0; i < indices.size(); i++) {
			int index = indices.get(i);
			if (index < 0 || index >= Ae2InputFilter.getMaxFilterSlots()) continue;
			String entry = entries.get(i);
			newSlots[index] = entry == null || entry.isBlank() ? null : entry;
			if (Ae2InputFilter.isDirectFingerprint(newSlots[index])) {
				newAmounts[index] = Math.max(0L, Math.min(Ae2InputFilter.getMaxDirectAmount(), amounts.get(i)));
				newVisible[index] = Math.max(0L, visibleAmounts.get(i));
				newUnlimited[index] = Boolean.TRUE.equals(unlimitedFlags.get(i));
			}
		}
		return new Snapshot(mode, precise, newSlots, newKeys, newAmounts, newVisible, newUnlimited);
	}

	/** 快照结果（数组均为新分配，可直接发布） */
	record Snapshot(Ae2InputFilter.FilterMode mode, boolean precise,
			String[] slots, AEItemKey[] keys, long[] amounts, long[] visible, boolean[] unlimited) {
	}
}
