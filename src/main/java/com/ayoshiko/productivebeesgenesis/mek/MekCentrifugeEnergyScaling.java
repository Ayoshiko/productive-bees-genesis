package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.util.SaturatingMath;
import mekanism.common.capabilities.energy.MachineEnergyContainer;

/**
 * Dynamically keeps the local Mekanism energy buffer large enough for one real tick at the
 * machine's current upgrade-adjusted rate.
 * <p>
 * Mekanism deducts energy once per cached-recipe tick, and both the SMELTING path and the
 * PB path use {@code energyPerTick * operationsPerTick}. If the local buffer is smaller
 * than that product, even an AE2 creative energy cell cannot help because AE2 energy is
 * injected into the local {@link MachineEnergyContainer} and is therefore capped by
 * {@link MachineEnergyContainer#getMaxEnergy()}.
 */
public final class MekCentrifugeEnergyScaling {

    private MekCentrifugeEnergyScaling() {
    }

    /**
     * Returns the energy required to run every process at the current operations-per-tick
     * rate for one real tick, saturating at {@link Long#MAX_VALUE}.
     */
    public static long requiredEnergyPerTick(PbRecipeContext context) {
        return requiredEnergyPerTick(context, 1);
    }

    /**
     * Returns the energy required for one real tick, including the tick-accelerator batch
     * multiplier and PB productivity parallelism. The result saturates at Long.MAX_VALUE.
     */
    public static long requiredEnergyPerTick(PbRecipeContext context, int batchMultiplier) {
        MachineEnergyContainer<?> container = context.energyContainer();
        if (container == null) {
            return 0L;
        }
        long energyPerOperation = Math.max(0L, container.getEnergyPerTick());
        // Actual worst case for our machines: STACK 16 => 2^16 = 65536 parallel,
        // MU speed/energy 32 (already included in energyPerOperation), plus PB
        // productivity parallelism, then multiplied by the current tick-accelerator batch.
        int operationsPerTick = Math.max(1, context.operationsPerTick());
        int productivityParallel = Math.max(1, context.productivityParallelModifier());
        int processes = Math.max(1, context.processes());
        return requiredEnergyPerTick(energyPerOperation, operationsPerTick,
                productivityParallel, processes, batchMultiplier);
    }

    static long requiredEnergyPerTick(long energyPerOperation, int operationsPerTick,
            int productivityParallel, int processes, int batchMultiplier) {
        long required = SaturatingMath.saturatingMultiply(
                Math.max(0L, energyPerOperation), Math.max(1, operationsPerTick));
        required = SaturatingMath.saturatingMultiply(required, Math.max(1, productivityParallel));
        required = SaturatingMath.saturatingMultiply(required, Math.max(1, processes));
        return SaturatingMath.saturatingMultiply(required, Math.max(1, batchMultiplier));
    }

    /**
     * Capacity floor for the local buffer: one extra batch kept as a low-water reserve.
     * <p>
     * <b>Role (v1.0.2)</b>: this value is a <b>minimum capacity</b> for
     * {@link #ensureCapacity}, not an injection target — AE2 injection now fills the
     * container to its full capacity so the GUI FE bar reflects real stored energy.
     * With an exact-demand buffer the injector fills the machine and processing drains
     * it to zero every game tick, making the synced FE bar oscillate and leaving no
     * tolerance for another pipeline stage in that tick; the doubled floor absorbs
     * that oscillation and covers batched deduction spikes.
     */
    public static long bufferedCapacityForDemand(long required) {
        long demand = Math.max(0L, required);
        return SaturatingMath.saturatingAdd(demand, demand);
    }

    /**
     * Grows the local energy buffer to the current one-tick demand if it is too small.
     */
    public static void ensureCapacity(PbRecipeContext context) {
        ensureCapacity(context, requiredEnergyPerTick(context, 1));
    }

    /**
     * Grows the local energy buffer to the current batched one-tick demand if it is too small.
     */
    public static void ensureCapacity(PbRecipeContext context, int batchMultiplier) {
        ensureCapacity(context, requiredEnergyPerTick(context, batchMultiplier));
    }

    /**
     * Grows the local energy buffer to at least {@code required} FE. The capacity is never
     * reduced here: Mekanism/MEKExtras already recalculates the normal or creative capacity
     * on upgrade changes, and retaining a larger buffer is harmless.
     */
    public static void ensureCapacity(PbRecipeContext context, long required) {
        MachineEnergyContainer<?> container = context.energyContainer();
        if (container == null || required <= 0L) {
            return;
        }
        long currentMax = container.getMaxEnergy();
        if (currentMax == Long.MAX_VALUE || required <= currentMax) {
            return;
        }
        container.setMaxEnergy(required);
    }
}
