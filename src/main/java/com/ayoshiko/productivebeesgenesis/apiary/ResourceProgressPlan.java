package com.ayoshiko.productivebeesgenesis.apiary;

/** 先按容量裁定能完成的工作；尚未推进的虚拟 tick 不计费，旧的已付费进度不丢弃。 */
record ResourceProgressPlan(int advancedTicks, long completedCycles, int remainingTicks) {
	static ResourceProgressPlan plan(int storedTicks, int requiredTicks, int allocatedTicks, long capacityCycles) {
		if (storedTicks < 0 || requiredTicks < 1 || allocatedTicks < 0 || capacityCycles < 0) {
			throw new IllegalArgumentException("Invalid resource progress");
		}
		if (capacityCycles == 0) return new ResourceProgressPlan(0, 0, storedTicks);
		long requested = ((long) storedTicks + allocatedTicks) / requiredTicks;
		long accepted = Math.min(requested, capacityCycles);
		int advance = requested < capacityCycles ? allocatedTicks
				: (int) Math.max(0L, Math.min(allocatedTicks, accepted * requiredTicks - storedTicks));
		int remaining = Math.toIntExact((long) storedTicks + advance - accepted * requiredTicks);
		return new ResourceProgressPlan(advance, accepted, remaining);
	}
}
