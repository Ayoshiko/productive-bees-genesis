package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2IntegrationLoader;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2OutputStateHolder;
import com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2OutputHostBase;
import com.ayoshiko.productivebeesgenesis.util.SaturatingMath;
import mekanism.api.Upgrade;
import mekanism.api.inventory.IInventorySlot;
import mekanism.common.capabilities.energy.MachineEnergyContainer;
import mekanism.common.tile.interfaces.IUpgradeTile;
import mekanism.common.util.MekanismUtils;

/** Coordinates high-parallel energy pricing and the local centrifuge energy buffer. */
public final class MekCentrifugeEnergyScaling {

	/** Parallel work up to this amount keeps Mekanism's original linear per-operation price. */
	public static final int LINEAR_PARALLEL_OPERATIONS = 16;

	/** Additional billable operations charged whenever high parallelism doubles. */
	private static final int BILLABLE_OPERATIONS_PER_DOUBLING = 1;
	private static final long BASE_ENERGY_USAGE_DIVISOR = 5L;
	private static final long BASE_CAPACITY_DIVISOR = 2L;

	private static final double LOG_2 = Math.log(2.0D);

	private MekCentrifugeEnergyScaling() {
	}

	/** Applies the built-in balance reduction to a configured base energy usage. */
	public static long balancedBaseEnergyPerTick(long configuredEnergyPerTick) {
		return dividePositive(configuredEnergyPerTick, BASE_ENERGY_USAGE_DIVISOR);
	}

	/** Applies the built-in balance reduction to a configured base storage capacity. */
	public static long balancedBaseCapacity(long configuredCapacity) {
		return dividePositive(configuredCapacity, BASE_CAPACITY_DIVISOR);
	}

	private static long dividePositive(long configuredValue, long divisor) {
		long positive = Math.max(1L, configuredValue);
		return positive / divisor + (positive % divisor == 0L ? 0L : 1L);
	}

	/** Returns the actual FE required by every process for one real tick. */
	public static long requiredEnergyPerTick(PbRecipeContext context) {
		return requiredEnergyPerTick(context, 1);
	}

	/** Returns actual FE demand including accelerator and PB productivity parallelism. */
	public static long requiredEnergyPerTick(PbRecipeContext context, int batchMultiplier) {
		MachineEnergyContainer<?> container = context.energyContainer();
		if (container == null) return 0L;
		long energyPerOperation = Math.max(0L, container.getEnergyPerTick());
		int operationsPerTick = Math.max(1, context.operationsPerTick());
		int productivityParallel = Math.max(1, context.productivityParallelModifier());
		int processes = Math.max(1, context.processes());
		long activeDemand = activeEnergyDemand(context, energyPerOperation,
				operationsPerTick, productivityParallel, processes);
		if (activeDemand >= 0L) {
			return SaturatingMath.saturatingMultiply(activeDemand, Math.max(1, batchMultiplier));
		}
		return requiredEnergyPerTick(energyPerOperation, operationsPerTick,
				productivityParallel, processes, batchMultiplier);
	}

	/**
	 * Returns current input-limited operations, or {@code -1} when a host cannot expose a
	 * complete input-slot snapshot. A zero result is meaningful: an empty machine should not
	 * pull a full worst-case energy batch merely because its upgrade count is high.
	 */
	private static long activeEnergyDemand(PbRecipeContext context, long energyPerOperation,
			int operationsPerTick, int productivityParallel, int processes) {
		long perProcessMaximum = SaturatingMath.saturatingMultiply(
				Math.max(1, operationsPerTick), Math.max(1, productivityParallel));
		long totalDemand = 0L;
		for (int process = 0; process < processes; process++) {
			IInventorySlot slot;
			try {
				slot = context.inputSlot(process);
			} catch (RuntimeException ignored) {
				return -1L;
			}
			if (slot == null) return -1L;
			int count = slot.getStack().isEmpty() ? 0 : slot.getStack().getCount();
			long activeOperations = Math.min(perProcessMaximum, Math.max(0, count));
			totalDemand = SaturatingMath.saturatingAdd(totalDemand,
					parallelEnergyCost(energyPerOperation, activeOperations));
		}
		return totalDemand;
	}

	static long requiredEnergyPerTick(long energyPerOperation, int operationsPerTick,
			int productivityParallel, int processes, int batchMultiplier) {
		long operationsPerProcess = SaturatingMath.saturatingMultiply(
				Math.max(1, operationsPerTick), Math.max(1, productivityParallel));
		long perProcessDemand = parallelEnergyCost(energyPerOperation, operationsPerProcess);
		return SaturatingMath.saturatingMultiply(
				perProcessDemand, Math.max(1, processes), Math.max(1, batchMultiplier));
	}

	/**
	 * Returns billable operations for one process. Work stays linear through 16 operations;
	 * above that point every doubling adds one billable operation. A STACK level therefore
	 * has a predictable marginal energy cost without multiplying demand by its full throughput.
	 */
	static long billableOperations(long operations) {
		long active = Math.max(0L, operations);
		if (active <= LINEAR_PARALLEL_OPERATIONS) return active;
		double doublings = Math.log((double) active / LINEAR_PARALLEL_OPERATIONS) / LOG_2;
		double scaled = LINEAR_PARALLEL_OPERATIONS
				+ BILLABLE_OPERATIONS_PER_DOUBLING * doublings;
		return SaturatingMath.saturatingCeilToLong(scaled);
	}

	/** Overflow-safe energy charge for simultaneous operations in one process. */
	static long parallelEnergyCost(long energyPerOperation, long operations) {
		return SaturatingMath.saturatingMultiply(
				Math.max(0L, energyPerOperation), billableOperations(operations));
	}

	/** Returns the most operations affordable for one tick under the shared pricing curve. */
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
		double estimatedOperations = LINEAR_PARALLEL_OPERATIONS
				* Math.pow(2.0D, affordableDoublings);
		int affordable = estimatedOperations >= Integer.MAX_VALUE
				? Integer.MAX_VALUE : Math.max(0, (int) Math.floor(estimatedOperations));
		affordable = Math.min(requested, affordable);
		// Correct the at-most-few-ULP error at doubling boundaries so pricing and affordability
		// remain exact inverses even for very large configured operation counts.
		while (affordable > 0 && billableOperations(affordable) > billableBudget) affordable--;
		while (affordable < requested && billableOperations((long) affordable + 1L) <= billableBudget) affordable++;
		return affordable;
	}

	/** Overflow-safe energy charge for a cached-recipe or virtual-tick batch. */
	public static long batchEnergyCost(long energyPerTick, int operations, int ticks) {
		return SaturatingMath.saturatingMultiply(
				parallelEnergyCost(energyPerTick, Math.max(0, operations)), Math.max(0, ticks));
	}

	/** Returns Mekanism's deterministic capacity for a registered base and current upgrades. */
	static long normalCapacity(long baseCapacity, long upgradedCapacity) {
		long base = Math.max(1L, baseCapacity);
		return Math.max(base, upgradedCapacity);
	}

	/**
	 * Restores the standard upgrade-derived capacity. This also migrates legacy machines whose
	 * buffers retained a historical demand peak, so identical machines no longer diverge by load history.
	 */
	public static void normalizeCapacity(PbRecipeContext context) {
		MachineEnergyContainer<?> container = context.energyContainer();
		if (container == null) return;
		boolean ae2Loaded = Ae2IntegrationLoader.isAe2Loaded();
		if (context.hasCreativeUpgrade()) {
			if (ae2Loaded && context instanceof IAe2OutputHostBase ae2Host) {
				ae2Host.productivebeesgenesis$getAe2StateHolder().invalidateNormalizedCapacity();
			}
			return;
		}
		long baseCapacity = Math.max(1L, container.getBaseMaxEnergy());
		long currentCapacity = container.getMaxEnergy();
		long upgradeFingerprint = Long.MIN_VALUE;
		Ae2OutputStateHolder capacityCache = null;
		if (ae2Loaded && context instanceof IAe2OutputHostBase ae2Host) {
			capacityCache = ae2Host.productivebeesgenesis$getAe2StateHolder();
			if (capacityCache != null) {
				upgradeFingerprint = upgradeFingerprint(context);
				if (capacityCache.isNormalizedCapacity(baseCapacity, currentCapacity, upgradeFingerprint)) return;
			}
		}
		long desired = normalCapacityFor(context, container, baseCapacity);
		if (currentCapacity != desired) {
			container.setMaxEnergy(desired);
		}
		if (capacityCache != null) {
			capacityCache.cacheNormalizedCapacity(baseCapacity, desired, upgradeFingerprint);
		}
	}

	/**
	 * Capacity only depends on ENERGY upgrades. Reading that counter directly keeps
	 * accelerated sub-ticks allocation-free without walking unrelated upgrade types.
	 */
	private static long upgradeFingerprint(PbRecipeContext context) {
		if (!(context instanceof IUpgradeTile upgradeTile)) return Long.MIN_VALUE;
		return upgradeTile.getComponent().getUpgrades(Upgrade.ENERGY);
	}

	private static long normalCapacityFor(PbRecipeContext context,
			MachineEnergyContainer<?> container, long baseCapacity) {
		long upgradedCapacity = baseCapacity;
		if (context instanceof IUpgradeTile upgradeTile) {
			upgradedCapacity = MekanismUtils.getMaxEnergy(upgradeTile, baseCapacity);
		}
		return normalCapacity(baseCapacity, upgradedCapacity);
	}
}
