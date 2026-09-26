package com.ayoshiko.productivebeesgenesis.multiblock.visual;

import java.util.IdentityHashMap;

/** 同一画面共享的细节额度；只保留本帧对象，重复绘制同一对象不重复领取。 */
public final class CoreFrameBudget<T> {
	public static final int LIMIT = 16;
	private final IdentityHashMap<T, Boolean> admitted = new IdentityHashMap<>();
	public boolean allow(T identity) {
		if (identity == null) return false;
		if (admitted.containsKey(identity)) return true;
		if (admitted.size() >= LIMIT) return false;
		admitted.put(identity, Boolean.TRUE); return true;
	}
	public void clear() { admitted.clear(); }
	public int retained() { return admitted.size(); }
}
