package com.ayoshiko.productivebeesgenesis.mek;

import java.util.Arrays;

/** Pure numeric core for weighted integer allocation. */
final class WeightedAllocationMath {

	private WeightedAllocationMath() {
	}

	/** Returns {@code null} when the weights are invalid and the caller should use its fallback. */
	static int[] allocate(int total, double[] weights) {
		if (total <= 0 || weights == null || weights.length == 0) return null;
		int size = weights.length;
		double[] effectiveWeights = new double[size];
		double sumWeights = 0.0D;
		for (int i = 0; i < size; i++) {
			double weight = weights[i];
			if (!Double.isFinite(weight)) return null;
			effectiveWeights[i] = Math.max(0.0D, weight);
			sumWeights += effectiveWeights[i];
			if (!Double.isFinite(sumWeights)) return null;
		}
		if (sumWeights <= 0.0D) return null;

		int[] allocated = new int[size];
		long totalAllocated = 0L;
		for (int i = 0; i < size; i++) {
			double share = (double) total * effectiveWeights[i] / sumWeights;
			long remainingBudget = Math.max(0L, (long) total - totalAllocated);
			allocated[i] = (int) Math.max(0L, Math.min(remainingBudget, (long) Math.floor(share)));
			totalAllocated += allocated[i];
		}

		int remainder = (int) Math.max(0L, (long) total - totalAllocated);
		if (remainder <= 0) return allocated;

		Integer[] indices = new Integer[size];
		for (int i = 0; i < size; i++) indices[i] = i;
		Arrays.sort(indices, (a, b) -> {
			int comparison = Double.compare(effectiveWeights[b], effectiveWeights[a]);
			return comparison != 0 ? comparison : Integer.compare(a, b);
		});
		int fullRounds = remainder / size;
		int extra = remainder % size;
		for (int i = 0; i < size; i++) {
			int addition = fullRounds + (i < extra ? 1 : 0);
			allocated[indices[i]] = saturatedAdd(allocated[indices[i]], addition);
		}
		return allocated;
	}

	private static int saturatedAdd(int first, int second) {
		return (int) Math.min(Integer.MAX_VALUE,
				(long) Math.max(0, first) + Math.max(0, second));
	}
}
