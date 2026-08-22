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
	record StateArrays(String[] slots, AEItemKey[] keys, long[] amounts, long[] reserves, long[] visible,
			boolean[] unlimited, boolean[] networkStock) {
	}

	/** clone-modify step shared by setEntryAt/setEntryAtIndex/setDirectEntryFingerprintAt. */
	static StateArrays setEntry(String[] slots, AEItemKey[] keys, long[] amounts, long[] reserves, long[] visible,
			boolean[] unlimited, boolean[] networkStock, int index, String entry, long amount) {
		String[] arr = slots.clone(); // CopyOnWrite
		AEItemKey[] k2 = keys.clone();
		arr[index] = entry;
		k2[index] = null;
		long[] a2 = amounts.clone();
		a2[index] = amount;
		long[] r2 = reserves.clone();
		r2[index] = 0L;
		long[] v2 = visible.clone();
		v2[index] = 0L;
		boolean[] u2 = unlimited.clone();
		u2[index] = false;
		boolean[] n2 = networkStock.clone();
		n2[index] = false;
		return new StateArrays(arr, k2, a2, r2, v2, u2, n2);
	}

	/** Removes the slot at index (null result means the index is invalid or already empty). */
	static StateArrays removeEntry(String[] slots, AEItemKey[] keys, long[] amounts, long[] reserves, long[] visible,
			boolean[] unlimited, boolean[] networkStock, int index) {
		AEItemKey[] k2 = keys.clone();
		if (index < 0 || index >= slots.length) return null;
		if (slots[index] == null) return null;
		String[] arr = slots.clone();
		k2[index] = null;
		long[] a2 = amounts.clone();
		a2[index] = 0L;
		long[] r2 = reserves.clone();
		r2[index] = 0L;
		long[] v2 = visible.clone();
		v2[index] = 0L;
		boolean[] u2 = unlimited.clone();
		u2[index] = false;
		boolean[] n2 = networkStock.clone();
		n2[index] = false;
		arr[index] = null; // only clears the slot, no shifting
		return new StateArrays(arr, k2, a2, r2, v2, u2, n2);
	}

	static StateArrays clearAll(String[] slots, AEItemKey[] keys, long[] amounts, long[] reserves, long[] visible,
			boolean[] unlimited, boolean[] networkStock) {
		AEItemKey[] k2 = keys.clone();
		String[] arr = slots.clone();
		Arrays.fill(arr, null);
		Arrays.fill(k2, null);
		long[] a2 = amounts.clone();
		Arrays.fill(a2, 0L);
		long[] r2 = reserves.clone();
		Arrays.fill(r2, 0L);
		long[] v2 = visible.clone();
		Arrays.fill(v2, 0L);
		boolean[] u2 = unlimited.clone();
		Arrays.fill(u2, false);
		boolean[] n2 = networkStock.clone();
		Arrays.fill(n2, false);
		return new StateArrays(arr, k2, a2, r2, v2, u2, n2);
	}

	static StateArrays grow(String[] slots, AEItemKey[] keys, long[] amounts, long[] reserves, long[] visible,
			boolean[] unlimited, boolean[] networkStock, int minCapacity) {
		String[] newArr = new String[minCapacity];
		System.arraycopy(slots, 0, newArr, 0, slots.length);
		AEItemKey[] newKeys = new AEItemKey[minCapacity];
		System.arraycopy(keys, 0, newKeys, 0, keys.length);
		long[] newAmounts = new long[minCapacity];
		System.arraycopy(amounts, 0, newAmounts, 0, amounts.length);
		long[] newReserves = new long[minCapacity];
		System.arraycopy(reserves, 0, newReserves, 0, reserves.length);
		long[] newVisible = new long[minCapacity];
		System.arraycopy(visible, 0, newVisible, 0, visible.length);
		boolean[] newUnlimited = new boolean[minCapacity];
		System.arraycopy(unlimited, 0, newUnlimited, 0, unlimited.length);
		boolean[] newNetworkStock = new boolean[minCapacity];
		System.arraycopy(networkStock, 0, newNetworkStock, 0, networkStock.length);
		return new StateArrays(newArr, newKeys, newAmounts, newReserves, newVisible, newUnlimited, newNetworkStock);
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

	static long[] setReserve(long[] reserves, int index, long amount) {
		long[] r2 = reserves.clone();
		r2[index] = amount;
		return r2;
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

	static boolean[] toggleNetworkStock(boolean[] networkStock, int index) {
		boolean[] n2 = networkStock.clone();
		n2[index] = !n2[index];
		return n2;
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

	static AmountChange setAllReserves(String[] slots, long[] reserves, long clamped) {
		long[] r2 = reserves.clone();
		int changed = 0;
		for (int i = 0; i < slots.length; i++) {
			if (Ae2InputFilter.isDirectFingerprint(slots[i]) && r2[i] != clamped) {
				r2[i] = clamped;
				changed++;
			}
		}
		return new AmountChange(r2, changed);
	}

	record UnlimitedChange(boolean[] unlimited, int changed) {
	}

	static UnlimitedChange setAllNetworkStock(String[] slots, boolean[] networkStock, boolean target) {
		boolean[] n2 = networkStock.clone();
		int changed = 0;
		for (int i = 0; i < slots.length; i++) {
			if (Ae2InputFilter.isDirectFingerprint(slots[i]) && n2[i] != target) {
				n2[i] = target;
				changed++;
			}
		}
		return new UnlimitedChange(n2, changed);
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
