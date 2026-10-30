package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.util.SaturatingMath;

/**
	 * Collapses repeated accelerator ticks into one PB recipe update. The plan keeps
	 * progress, input and energy limits equivalent to repeated Mekanism recipe ticks
	 * without rerunning the complete block-entity tick for every virtual tick.
	 */
record PbVirtualTickPlan(int completedOperations, int remainingProgress,
		int executedTicks, long energyUsed) {

	static PbVirtualTickPlan create(int currentProgress, int virtualTicks, int processingTime,
			int operationsPerCycle, int availableInputs, long energyPerOperation, long availableEnergy) {
		int required = Math.max(1, processingTime);
		int progress = Math.max(0, Math.min(currentProgress, required - 1));
		int ticksLeft = Math.max(0, virtualTicks);
		int inputsLeft = Math.max(0, availableInputs);
		int maxOperations = Math.max(1, operationsPerCycle);
		long perOperation = Math.max(0L, energyPerOperation);
		long energyLeft = Math.max(0L, availableEnergy);
		int completed = 0;
		int executed = 0;
		long used = 0L;

		if (ticksLeft == 0 || inputsLeft == 0) {
			return new PbVirtualTickPlan(0, progress, 0, 0L);
		}

		int activeOperations = affordableOperationsForOneTick(
				maxOperations, inputsLeft, perOperation, energyLeft);
		if (activeOperations <= 0) {
			return new PbVirtualTickPlan(0, progress, 0, 0L);
		}
		int firstSegment = Math.min(ticksLeft, required - progress);
		int firstExecuted = affordableTicks(firstSegment, activeOperations, perOperation, energyLeft);
		long firstEnergy = energyFor(firstExecuted, activeOperations, perOperation);
		progress += firstExecuted;
		ticksLeft -= firstExecuted;
		executed += firstExecuted;
		used = SaturatingMath.saturatingAdd(used, firstEnergy);
		energyLeft = Math.max(0L, energyLeft - firstEnergy);
		if (firstExecuted < firstSegment || progress < required) {
			return new PbVirtualTickPlan(0, progress, executed, used);
		}

		completed += activeOperations;
		inputsLeft -= activeOperations;
		progress = 0;

		if (ticksLeft >= required && inputsLeft >= maxOperations) {
			long cycles = Math.min((long) ticksLeft / required, (long) inputsLeft / maxOperations);
			if (perOperation > 0L) {
				long energyPerCycle = energyFor(required, maxOperations, perOperation);
				cycles = energyPerCycle <= 0L ? 0L : Math.min(cycles, energyLeft / energyPerCycle);
			}
			if (cycles > 0L) {
				int cycleTicks = (int) (cycles * required);
				int cycleOperations = (int) Math.min(Integer.MAX_VALUE, cycles * maxOperations);
				long cycleEnergy = energyFor(cycleTicks, maxOperations, perOperation);
				completed = saturatedAdd(completed, cycleOperations);
				inputsLeft -= cycleOperations;
				ticksLeft -= cycleTicks;
				executed += cycleTicks;
				used = SaturatingMath.saturatingAdd(used, cycleEnergy);
				energyLeft = Math.max(0L, energyLeft - cycleEnergy);
			}
		}

		if (ticksLeft > 0 && inputsLeft > 0) {
			activeOperations = affordableOperationsForOneTick(
					maxOperations, inputsLeft, perOperation, energyLeft);
			if (activeOperations <= 0) {
				return new PbVirtualTickPlan(completed, progress, executed, used);
			}
			int lastSegment = Math.min(ticksLeft, required);
			int lastExecuted = affordableTicks(lastSegment, activeOperations, perOperation, energyLeft);
			long lastEnergy = energyFor(lastExecuted, activeOperations, perOperation);
			progress = lastExecuted;
			executed += lastExecuted;
			used = SaturatingMath.saturatingAdd(used, lastEnergy);
			if (progress == required) {
				completed = saturatedAdd(completed, activeOperations);
				progress = 0;
			}
		}

		return new PbVirtualTickPlan(completed, progress, executed, used);
	}

	private static int affordableOperationsForOneTick(int requestedOperations, int availableInputs,
			long energyPerOperation, long availableEnergy) {
		int operations = Math.min(Math.max(0, requestedOperations), Math.max(0, availableInputs));
		return MekCentrifugeEnergyScaling.affordableOperations(
				energyPerOperation, operations, availableEnergy);
	}

	private static int affordableTicks(int requestedTicks, int operations, long energyPerOperation,
			long availableEnergy) {
		if (requestedTicks <= 0 || operations <= 0) return 0;
		if (energyPerOperation == 0L) return requestedTicks;
		long perTick = MekCentrifugeEnergyScaling.parallelEnergyCost(
				energyPerOperation, operations);
		if (perTick <= 0L) return 0;
		long affordable = availableEnergy / perTick;
		return (int) Math.min(requestedTicks, affordable);
	}

	private static long energyFor(int ticks, int operations, long energyPerOperation) {
		if (ticks <= 0 || operations <= 0 || energyPerOperation <= 0L) return 0L;
		return MekCentrifugeEnergyScaling.batchEnergyCost(energyPerOperation, operations, ticks);
	}

	private static int saturatedAdd(int first, int second) {
		return first > Integer.MAX_VALUE - second ? Integer.MAX_VALUE : first + second;
	}
}
