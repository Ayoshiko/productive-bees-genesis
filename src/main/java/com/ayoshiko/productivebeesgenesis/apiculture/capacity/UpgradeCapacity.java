package com.ayoshiko.productivebeesgenesis.apiculture.capacity;

/** 下一工作段的升级参数；不承诺实际吞吐，不携带配方、世界或资产引用。 */
public record UpgradeCapacity(float timeFactor, long energyPerTick, long energyCapacity, int parallel,
		float productivity, float stability, boolean combBlock, boolean discardByproducts) {
	public UpgradeCapacity {
		if (!Float.isFinite(timeFactor) || timeFactor <= 0 || energyPerTick < 0 || energyCapacity <= 0 || parallel <= 0
				|| !Float.isFinite(productivity) || productivity <= 0 || !Float.isFinite(stability) || stability < 0)
			throw new IllegalArgumentException("Invalid upgrade capacity");
	}
}
