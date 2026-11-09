package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.MEStorage;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import com.ayoshiko.productivebeesgenesis.resource.ResourceCentrifugeProcessor;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.inventory.IInventorySlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.List;

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

    static boolean returnLeftoverToMe(Ae2OutputStateHolder holder, MEStorage storage, AEItemKey key,
            IActionSource source, long tick) {
        var transfer = holder.getPendingItemBuffer().transfer();
        long remaining = transfer.knownAmount();
        var backoff = holder.getPushState().getReturnBackoff();
        if (remaining <= 0 || !canReturnNow(holder, storage, tick)) return false;
        long simulated;
        try {
            simulated = timedInsert(holder, storage, key, remaining, Actionable.SIMULATE, source, tick);
            if (simulated < 0 || simulated > remaining) {
                throw new IllegalStateException("Invalid ME simulation result: " + simulated);
            }
        } catch (LinkageError | RuntimeException error) {
            backoff.recordFailure(System.nanoTime());
            LogThrottle.warn("ae2_leftover_simulation", "AE2 回送模拟失败，保留已知余量: {}", error.toString());
            return true;
        }
        if (simulated > 0) {
            transfer.offer(simulated, -1);
            long accepted = timedInsert(holder, storage, key, simulated, Actionable.MODULATE, source, tick);
            transfer.accepted(accepted);
        }
        if (transfer.knownAmount() == 0) backoff.recordSuccess();
        else backoff.recordFailure(System.nanoTime());
        return true;
    }

    /** 输入恢复先于新取料共用输入预算，常规产物不能耗尽它的恢复机会。 */
    public static boolean canReturnNow(Ae2OutputStateHolder holder, MEStorage storage, long tick) {
        return storage != null && holder.tryAcquireNetworkExtract(storage, tick);
    }

    public static long timedInsert(Ae2OutputStateHolder holder, MEStorage storage, AEItemKey key,
            long amount, Actionable action, IActionSource source, long tick) {
        long start = System.nanoTime();
        try {
            return storage.insert(key, amount, action, source);
        } finally {
            long cost = System.nanoTime() - start;
            Ae2GlobalInsertBudget.recordCost(tick, cost);
            holder.recordNetworkExtractCost(storage, tick, cost, Ae2NetworkWorkCoordinator.HEALTHY_INSERT_NANOS);
            if (holder.getReusableBuffers() instanceof Ae2PushBuffers buffers) {
                buffers.insertCostTracker.record(tick, cost);
            }
        }
    }

    /** 每轮最多恢复四个已知身份；一次未知结果立即暂停后续交付。 */
    private static boolean acceptsLaneInput(BlockEntity host, int lane, ItemStack input) {
        var required = ResourceCentrifugeProcessor.requiredLaneInput(host, lane);
        return required == null || !required.isEmpty() && ItemStack.isSameItemSameComponents(required, input);
    }

    static boolean retryPendingItems(Level level, Ae2OutputStateHolder holder, MEStorage meStorage,
            List<IInventorySlot> inputSlots, long currentTick, BlockEntity host, IActionSource actionSource) {
        Ae2PendingItemBuffer pending = holder.getPendingItemBuffer();
        if (pending.isBlocked()) return false;
        int attempts = 0;
        for (Ae2PendingItemBuffer.PendingItem entry : pending.snapshot(currentTick, 4, meStorage != null)) {
            if (pending.isBlocked() || attempts >= 4) break;
            if (meStorage == null && entry.amount() <= 0) continue;
            attempts++;
            AEItemKey key = Ae2FingerprintCache.decode(entry.fingerprint(), level.registryAccess());
            if (key == null) {
                pending.recordFailure(entry.fingerprint(), currentTick);
                LogThrottle.warn("ae2_pending_item_decode", "AE2 pending 指纹无法解析，保留等待迁移: {}", entry.fingerprint());
                continue;
            }
            if (entry.returnToNetworkAmount() > 0 && meStorage != null) {
                retryKnownAmount(holder, meStorage, inputSlots, entry, key, entry.returnToNetworkAmount(), true, currentTick, host, actionSource);
            }
            if (entry.amount() > 0 && !pending.isBlocked()) {
                retryKnownAmount(holder, meStorage, inputSlots, entry, key, entry.amount(), false, currentTick, host, actionSource);
            }
        }
        return attempts > 0;
    }

    private static void retryKnownAmount(Ae2OutputStateHolder holder, MEStorage storage,
            List<IInventorySlot> slots, Ae2PendingItemBuffer.PendingItem entry, AEItemKey key,
            long amount, boolean networkOnly, long tick, BlockEntity host, IActionSource actionSource) {
        var pending = holder.getPendingItemBuffer();
        var transfer = pending.transfer();
        long request = Math.min(amount, Integer.MAX_VALUE);
        transfer.beginKnown(entry::fingerprint, request);
        if (networkOnly) pending.consumeReturnToNetwork(entry.fingerprint(), request, tick);
        else pending.consume(entry.fingerprint(), request, tick);
        try {
            if (!networkOnly) {
                for (int index = 0; index < slots.size() && transfer.knownAmount() > 0; index++) {
                    IInventorySlot slot = slots.get(index);
                    if (slot != null && (host == null || acceptsLaneInput(host, index, key.getReadOnlyStack()))) {
                        insertIntoSlot(transfer, key, slot, (int) transfer.knownAmount(), index);
                    }
                }
            }
            boolean returnAttempted = returnLeftoverToMe(
                    holder, storage, key, actionSource, tick);
            transfer.finish(pending, tick, networkOnly, entry.retries(), returnAttempted);
        } catch (LinkageError | RuntimeException error) {
            transfer.fingerprint();
            LogThrottle.error("ae2_input_transfer_unknown",
                    "AE2 输入交接待核验；停止自动重试 request={} target={} known={} offered={}: {}",
                    transfer.request(), transfer.destination(), transfer.knownAmount(), transfer.offeredAmount(), error.toString());
        }
    }

}
