package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.apiculture.centrifuge.PbVirtualTickPlan;

import java.util.Random;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class PbProcessingBaselineTest {

    @Test
    void fullyPoweredBatchMatchesAnIndependentSingleTickReference() {
        Random random = new Random(0xD01CE);
        for (int scenario = 0; scenario < 2_000; scenario++) {
            int period = 1 + random.nextInt(400);
            int progress = random.nextInt(period);
            int ticks = 1 + random.nextInt(1_024);
            int parallel = 1 + random.nextInt(65_536);
            int inputs = random.nextInt(100_000);
            long unitCost = 1 + random.nextInt(1_000);
            PbVirtualTickPlan actual = PbVirtualTickPlan.create(progress, ticks, period,
                    parallel, inputs, unitCost, Long.MAX_VALUE);
            Reference expected = tickReference(progress, ticks, period, parallel, inputs, unitCost);
            assertEquals(expected.completed(), actual.completedOperations(), "scenario " + scenario);
            assertEquals(expected.progress(), actual.remainingProgress(), "scenario " + scenario);
            assertEquals(expected.ticks(), actual.executedTicks(), "scenario " + scenario);
            assertEquals(expected.energy(), actual.energyUsed(), "scenario " + scenario);
        }
    }

    @Test
    void differentFactoryLanesMustBeChargedBeforeAggregation() {
        long separateLanes = MekCentrifugeEnergyScaling.batchEnergyCost(100, 17, 1)
                + MekCentrifugeEnergyScaling.batchEnergyCost(100, 17, 1);
        long mergedLane = MekCentrifugeEnergyScaling.batchEnergyCost(100, 34, 1);
        assertEquals(3_400, separateLanes);
        assertEquals(1_800, mergedLane);
        assertNotEquals(separateLanes, mergedLane, "pooling capacity must not grant a new energy discount");
    }

    @Test
    void parallelEnergyDoublingBoundariesMatchIntegerReference() {
        for (int power = 4; power <= 29; power++) {
            int boundary = 1 << power;
            for (int operations : new int[]{boundary - 1, boundary, boundary + 1}) {
                assertEquals(billable(operations) * 100,
                        MekCentrifugeEnergyScaling.batchEnergyCost(100, operations, 1));
            }
        }
    }

    @Test
    void energyPauseRetainsProgressAndDoesNotCompleteFreeWork() {
        PbVirtualTickPlan paused = PbVirtualTickPlan.create(99, 256, 100, 4, 20, 100, 0);
        assertEquals(new PbVirtualTickPlan(0, 99, 0, 0), paused);
        PbVirtualTickPlan resumed = PbVirtualTickPlan.create(paused.remainingProgress(), 1,
                100, 4, 20, 100, 400);
        assertEquals(new PbVirtualTickPlan(4, 0, 1, 400), resumed);
    }

    private static Reference tickReference(int progress, int ticks, int period,
                                            int parallel, int inputs, long cost) {
        int completed = 0;
        int executed = 0;
        long energy = 0;
        for (int tick = 0; tick < ticks && inputs > 0; tick++) {
            int working = Math.min(parallel, inputs);
            energy += billable(working) * cost;
            executed++;
            if (++progress == period) {
                inputs -= working;
                completed += working;
                progress = 0;
            }
        }
        return new Reference(completed, progress, executed, energy);
    }

    private static long billable(long operations) {
        // 整数阶梯参考，独立于生产代码的浮点 log/pow 实现。
        return operations <= 16 ? operations : 16 + Long.SIZE - Long.numberOfLeadingZeros((operations - 1) / 16);
    }

    private record Reference(int completed, int progress, int ticks, long energy) { }
}
