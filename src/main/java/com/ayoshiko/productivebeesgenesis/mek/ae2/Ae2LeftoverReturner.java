package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.MEStorage;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.inventory.IInventorySlot;
import net.minecraft.world.item.ItemStack;

/** 新取料和 pending 恢复共用的确认式交付；未知结果由宿主交接记录保管。 */
public final class Ae2LeftoverReturner {
    private Ae2LeftoverReturner() {}

    static int insertIntoSlot(Ae2InputTransfer transfer, AEItemKey key, IInventorySlot slot,
            int amount, int index) {
        ItemStack offered = key.toStack(amount);
        transfer.offer(amount, index);
        ItemStack remainder = slot.insertItem(offered, Action.EXECUTE, AutomationType.INTERNAL);
        if (remainder == null || (!remainder.isEmpty() && !key.matches(remainder))) {
            throw new IllegalStateException("Input slot returned a different item");
        }
        int remaining = remainder.isEmpty() ? 0 : remainder.getCount();
        long accepted = (long) amount - remaining;
        transfer.accepted(accepted);
        return (int) accepted;
    }

    static void returnLeftoverToMe(Ae2OutputStateHolder holder, MEStorage storage, AEItemKey key,
            IActionSource source, long tick) {
        var transfer = holder.getPendingItemBuffer().transfer();
        long remaining = transfer.knownAmount();
        if (remaining <= 0 || !holder.tryAcquireNetworkWork(storage, tick)) return;
        var backoff = holder.getPushState().getReturnBackoff();
        long simulated;
        try {
            simulated = timedInsert(holder, storage, key, remaining, Actionable.SIMULATE, source, tick);
            if (simulated < 0 || simulated > remaining) {
                throw new IllegalStateException("Invalid ME simulation result: " + simulated);
            }
        } catch (LinkageError | RuntimeException error) {
            backoff.recordFailure(System.nanoTime());
            LogThrottle.warn("ae2_leftover_simulation", "AE2 回送模拟失败，保留已知余量: {}", error.toString());
            return;
        }
        if (simulated > 0) {
            transfer.offer(simulated, -1);
            long accepted = timedInsert(holder, storage, key, simulated, Actionable.MODULATE, source, tick);
            transfer.accepted(accepted);
        }
        if (transfer.knownAmount() == 0) backoff.recordSuccess();
        else backoff.recordFailure(System.nanoTime());
    }

    private static long timedInsert(Ae2OutputStateHolder holder, MEStorage storage, AEItemKey key,
            long amount, Actionable action, IActionSource source, long tick) {
        long start = System.nanoTime();
        try {
            return storage.insert(key, amount, action, source);
        } finally {
            long cost = System.nanoTime() - start;
            Ae2GlobalInsertBudget.recordCost(tick, cost);
            holder.recordNetworkCost(storage, tick, cost, Ae2NetworkWorkCoordinator.HEALTHY_INSERT_NANOS);
            if (holder.getReusableBuffers() instanceof Ae2PushBuffers buffers) {
                buffers.insertCostTracker.record(tick, cost);
            }
        }
    }
}
