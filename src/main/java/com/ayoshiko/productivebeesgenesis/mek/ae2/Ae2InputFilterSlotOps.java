package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.stacks.AEItemKey;
import java.util.Arrays;

/**
	 * {@link Ae2InputFilter} 槽位状态数组操作工具（从过滤器拆分，SRP）
	 * <br/>
	 * 每个方法执行 clone-modify 并返回新数组；过滤器保留
	 * {@code synchronized} 互斥、volatile 发布与缓存失效职责。
	 */
final class Ae2InputFilterSlotOps {

	private Ae2InputFilterSlotOps() {
	}

	/** Immutable clone-modify result; the caller publishes the arrays (CopyOnWrite semantics). */
	record StateArrays(String[] slots, AEItemKey[] keys, long[] amounts, long[] visible, boolean[] unlimited) {
	}

	/** clone-modify step shared by setEntryAt/setEntryAtIndex/setDirectEntryFingerprintAt. */
	static StateArrays setEntry(String[] slots, AEItemKey[] keys, long[] amounts, long[] visible,
			boolean[] unlimited, int index, String entry, long amount) {
		String[] arr = slots.clone(); // CopyOnWrite
		AEItemKey[] k2 = keys.clone();
		arr[index] = entry;
		k2[index] = null;
		long[] a2 = amounts.clone();
		a2[index] = amount;
		long[] v2 = visible.clone();
		v2[index] = 0L;
		boolean[] u2 = unlimited.clone();
		u2[index] = false;
		return new StateArrays(arr, k2, a2, v2, u2);
	}

	/** Removes the slot at index (null result means the index is invalid or already empty). */
	static StateArrays removeEntry(String[] slots, AEItemKey[] keys, long[] amounts, long[] visible,
			boolean[] unlimited, int index) {
		AEItemKey[] k2 = keys.clone();
		if (index < 0 || index >= slots.length) return null;
		if (slots[index] == null) return null;
		String[] arr = slots.clone();
		k2[index] = null;
		long[] a2 = amounts.clone();
		a2[index] = 0L;
		long[] v2 = visible.clone();
		v2[index] = 0L;
		boolean[] u2 = unlimited.clone();
		u2[index] = false;
		arr[index] = null; // only clears the slot, no shifting
		return new StateArrays(arr, k2, a2, v2, u2);
	}

	static StateArrays clearAll(String[] slots, AEItemKey[] keys, long[] amounts, long[] visible, boolean[] unlimited) {
		AEItemKey[] k2 = keys.clone();
		String[] arr = slots.clone();
		Arrays.fill(arr, null);
		Arrays.fill(k2, null);
		long[] a2 = amounts.clone();
		Arrays.fill(a2, 0L);
		long[] v2 = visible.clone();
		Arrays.fill(v2, 0L);
		boolean[] u2 = unlimited.clone();
		Arrays.fill(u2, false);
		return new StateArrays(arr, k2, a2, v2, u2);
	}

	static StateArrays grow(String[] slots, AEItemKey[] keys, long[] amounts, long[] visible,
			boolean[] unlimited, int minCapacity) {
		String[] newArr = new String[minCapacity];
		System.arraycopy(slots, 0, newArr, 0, slots.length);
		AEItemKey[] newKeys = new AEItemKey[minCapacity];
		System.arraycopy(keys, 0, newKeys, 0, keys.length);
		long[] newAmounts = new long[minCapacity];
		System.arraycopy(amounts, 0, newAmounts, 0, amounts.length);
		long[] newVisible = new long[minCapacity];
		System.arraycopy(visible, 0, newVisible, 0, visible.length);
		boolean[] newUnlimited = new boolean[minCapacity];
		System.arraycopy(unlimited, 0, newUnlimited, 0, unlimited.length);
		return new StateArrays(newArr, newKeys, newAmounts, newVisible, newUnlimited);
	}

	static AEItemKey[] setKey(AEItemKey[] keys, int index, AEItemKey key) {
		AEItemKey[] k2 = keys.clone();
		k2[index] = key;
		return k2;
	}

	static long[] setAmount(long[] amounts, int index, long amount) {
		long[] a2 = amounts.clone();
		a2[index] = amount;
		return a2;
	}

	static long[] setVisible(long[] visible, int index, long amount) {
		long[] v2 = visible.clone();
		v2[index] = Math.max(0L, amount);
		return v2;
	}

	static boolean[] toggleUnlimited(boolean[] unlimited, int index) {
		boolean[] u2 = unlimited.clone();
		u2[index] = !u2[index];
		return u2;
	}

	record AmountChange(long[] amounts, int changed) {
	}

	static AmountChange setAllAmounts(String[] slots, long[] amounts, long clamped) {
		long[] a2 = amounts.clone();
		int changed = 0;
		for (int i = 0; i < slots.length; i++) {
			if (Ae2InputFilter.isDirectFingerprint(slots[i]) && a2[i] != clamped) {
				a2[i] = clamped;
				changed++;
			}
		}
		return new AmountChange(a2, changed);
	}

	record UnlimitedChange(boolean[] unlimited, int changed) {
	}

	static UnlimitedChange setAllUnlimited(String[] slots, boolean[] unlimited, boolean target) {
		boolean[] u2 = unlimited.clone();
		int changed = 0;
		for (int i = 0; i < slots.length; i++) {
			if (Ae2InputFilter.isDirectFingerprint(slots[i]) && u2[i] != target) {
				u2[i] = target;
				changed++;
			}
		}
		return new UnlimitedChange(u2, changed);
	}

}
