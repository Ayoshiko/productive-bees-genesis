package com.ayoshiko.productivebeesgenesis.apiary;

import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** D01：直接验证现有进度入口，避免只测一份重新抄写的公式。 */
class ApiaryProgressBaselineTest {

    @Test
    void acceleratedBatchesPreservePaidTicksCyclesAndRemainders() {
        Random random = new Random(0xD01BEE);
        for (int scenario = 0; scenario < 250; scenario++) {
            int period = 1 + random.nextInt(1_200);
            int initialProgress = random.nextInt(period);
            BeeSlot slot = slot(period, initialProgress);
            int[] pending = {0};
            AtomicInteger total = new AtomicInteger();
            long elapsed = initialProgress;
            long charged = 0;
            int stackMultiplier = 1 + random.nextInt(16);
            for (int batch = 0; batch < 20; batch++) {
                int ticks = 1 + random.nextInt(256);
                elapsed += ticks;
                charged += advance(slot, ticks, 1.0F, false, 50, stackMultiplier, pending, total);
                assertEquals(elapsed / period * stackMultiplier, pending[0], "completed production events");
                assertEquals(elapsed % period, slot.getTicksInHive(), "unpaid progress must not be invented");
                assertEquals(pending[0], total.get());
                assertEquals((elapsed - initialProgress) * 50, charged);
            }
        }
    }

    @Test
    void adjustedPeriodNeverFeedsBackIntoItsOwnSpeedCalculation() {
        BeeSlot slot = slot(1_200, 0);
        int[] pending = {0};
        AtomicInteger total = new AtomicInteger();
        for (int tick = 0; tick < 300; tick++) {
            advance(slot, 1, 0.25F, false, 50, 1, pending, total);
            assertEquals(300, slot.getMinOccupationTicks());
            assertEquals(1_200, slot.getBaseMinOccupationTicks());
        }
        assertEquals(1, pending[0]);
        assertEquals(0, slot.getTicksInHive());
    }

    @Test
    void speedChangePreservesProgressAndChargesOnlyNewTicks() {
        BeeSlot slot = slot(1_200, 590);
        int[] pending = {0};
        AtomicInteger total = new AtomicInteger();
        assertEquals(500, advance(slot, 10, 0.5F, false, 50, 1, pending, total));
        assertEquals(1, pending[0]);
        assertEquals(0, slot.getTicksInHive());
        assertEquals(2_500, advance(slot, 50, 1.0F, false, 50, 1, pending, total));
        assertEquals(1_200, slot.getMinOccupationTicks());
        assertEquals(50, slot.getTicksInHive());
        assertEquals(1, pending[0]);
    }

    @Test
    void creativeCycleAndMissingBasePeriodHaveExplicitBehavior() {
        BeeSlot creative = slot(1_200, 0);
        int[] pending = {0};
        AtomicInteger total = new AtomicInteger();
        long cost = ApiaryEnergyMath.calculateBeeEnergyCost(50, true);
        assertEquals(0, advance(creative, 256, 1.0F, true, cost, 1, pending, total));
        assertEquals(256, pending[0]);
        assertEquals(1, creative.getMinOccupationTicks());
        BeeSlot fallback = slot(0, 1_199);
        int[] fallbackPending = {0};
        advance(fallback, 1, 1.0F, false, 50, 1, fallbackPending, new AtomicInteger());
        assertEquals(1, fallbackPending[0]);
        assertEquals(1_200, fallback.getMinOccupationTicks());
    }

    private static BeeSlot slot(int period, int progress) {
        BeeSlot slot = new BeeSlot();
        slot.setBaseMinOccupationTicks(period);
        slot.setTicksInHive(progress);
        return slot;
    }

    private static long advance(BeeSlot slot, int ticks, float speed, boolean creative,
                                long energy, int stack, int[] pending, AtomicInteger total) {
        // 非诊断采样刻不读取世界／升级宿主；计时、能耗及 pending 仍走真实入口。
        return ApiaryProgressAdvancer.advance(slot, 0, ticks, 1, speed, creative,
                energy, stack, 1_200, null, pending, total);
    }
}
