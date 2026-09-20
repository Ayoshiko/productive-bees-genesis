package com.ayoshiko.productivebeesgenesis.apiculture.centrifuge;

import com.ayoshiko.productivebeesgenesis.util.SaturatingMath;

/** 每个独立进程的计费曲线；能力池必须先逐进程计费，再汇总 FE。 */
public final class CentrifugeEnergyPricing {
	public static final int LINEAR_PARALLEL_OPERATIONS = 16;
	private static final int BILLABLE_OPERATIONS_PER_DOUBLING = 1;
	private static final double LOG_2 = Math.log(2.0D);

	public static long billableOperations(long operations) {
		long active = Math.max(0L, operations);
		if (active <= LINEAR_PARALLEL_OPERATIONS) return active;
		double doublings = Math.log((double) active / LINEAR_PARALLEL_OPERATIONS) / LOG_2;
		double scaled = LINEAR_PARALLEL_OPERATIONS + BILLABLE_OPERATIONS_PER_DOUBLING * doublings;
		return SaturatingMath.saturatingCeilToLong(scaled);
	}

	public static long parallelEnergyCost(long energyPerOperation, long operations) {
		return SaturatingMath.saturatingMultiply(
				Math.max(0L, energyPerOperation), billableOperations(operations));
	}

	public static int affordableOperations(long energyPerOperation, int requestedOperations, long availableEnergy) {
		int requested = Math.max(0, requestedOperations);
		if (requested == 0 || energyPerOperation <= 0L) return requested;
		long billableBudget = Math.max(0L, availableEnergy) / energyPerOperation;
		if (billableBudget <= 0L) return 0;
		if (billableOperations(requested) <= billableBudget) return requested;
		if (billableBudget <= LINEAR_PARALLEL_OPERATIONS) {
			return (int) Math.min(requested, billableBudget);
		}
		double affordableDoublings = (double) (billableBudget - LINEAR_PARALLEL_OPERATIONS)
				/ BILLABLE_OPERATIONS_PER_DOUBLING;
		double estimatedOperations = LINEAR_PARALLEL_OPERATIONS * Math.pow(2.0D, affordableDoublings);
		int affordable = estimatedOperations >= Integer.MAX_VALUE
				? Integer.MAX_VALUE : Math.max(0, (int) Math.floor(estimatedOperations));
		affordable = Math.min(requested, affordable);
		// 保留原算法在倍增边界的浮点误差修正。
		while (affordable > 0 && billableOperations(affordable) > billableBudget) affordable--;
		while (affordable < requested && billableOperations((long) affordable + 1L) <= billableBudget) affordable++;
		return affordable;
	}

	public static long batchEnergyCost(long energyPerTick, int operations, int ticks) {
		return SaturatingMath.saturatingMultiply(
				parallelEnergyCost(energyPerTick, Math.max(0, operations)), Math.max(0, ticks));
	}

	private CentrifugeEnergyPricing() { }
}
