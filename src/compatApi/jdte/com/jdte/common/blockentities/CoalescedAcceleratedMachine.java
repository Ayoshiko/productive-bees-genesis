package com.jdte.common.blockentities;

/**
 * A block entity whose virtual Time Accelerator ticks can be accumulated and flushed once after
 * the manager finishes its real-server-tick execution pass.
 */
public interface CoalescedAcceleratedMachine {
    void accumulateAcceleratedTicks(int ticks);

    void flushAcceleratedTicks();
}
