package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import net.minecraft.network.chat.Component;

import java.util.function.LongSupplier;

/** 只计量真实 ME 调用；本地资产保管、NBT 与槽位更新不属于网络成本。 */
final class Ae2MeasuredStorage implements MEStorage {
    private final LongSupplier clock;
    private MEStorage target;
    private Object networkState;
    private Ae2InsertCostTracker tracker;
    private long gameTick;
    private long lastCostNanos;

    Ae2MeasuredStorage() {
        this(System::nanoTime);
    }

    Ae2MeasuredStorage(LongSupplier clock) {
        this.clock = clock;
    }

    Ae2MeasuredStorage prepare(MEStorage target, long gameTick, Ae2InsertCostTracker tracker) {
        if (target == this) throw new IllegalArgumentException("Recursive measured storage");
        if (this.target != target) {
            this.target = target;
            networkState = Ae2NetworkWorkCoordinator.resolve(target);
        }
        this.gameTick = gameTick;
        this.tracker = tracker;
        if (tracker != null) tracker.useNetwork(target);
        lastCostNanos = 0;
        return this;
    }

    @Override
    public long insert(AEKey key, long amount, Actionable mode, IActionSource source) {
        var current = target;
        var currentTracker = tracker;
        var currentNetwork = networkState;
        long currentTick = gameTick;
        long started = clock.getAsLong();
        try {
            return current.insert(key, amount, mode, source);
        } finally {
            lastCostNanos = Math.max(0, clock.getAsLong() - started);
            Ae2GlobalInsertBudget.recordCost(currentTick, lastCostNanos);
            Ae2NetworkWorkCoordinator.recordResolvedCost(currentNetwork, currentTick, lastCostNanos,
                    Ae2NetworkWorkCoordinator.HEALTHY_INSERT_NANOS);
            if (currentTracker != null) currentTracker.recordForNetwork(current, currentTick, lastCostNanos);
        }
    }

    long lastCostNanos() {
        return lastCostNanos;
    }

    void clear() {
        target = null;
        networkState = null;
        tracker = null;
        lastCostNanos = 0;
    }

    @Override
    public Component getDescription() {
        return target.getDescription();
    }
}
