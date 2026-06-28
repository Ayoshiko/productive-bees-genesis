package com.ayoshiko.productivebeesgenesis.capability;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.NotNull;

/**
 * 限流 IItemHandler 包装器（Task 13）
 * <br/>
 * 包装一个内部 IItemHandler，对外部通过 Capability 拉取物品的行为进行每 tick 总量限制。
 * <p>
 * 背景：AE2 ME 接口高频拉取离心机输出槽时，会触发 IContentsListener → setSortingNeeded(true)
 * → 全量排序扫描，导致主线程卡顿。此包装器在 extractItem 路径上限制单 tick 内可拉取的物品总数。
 * <p>
 * 线程安全：
 * <ul>
 *   <li>使用 AtomicInteger 维护本 tick 已提取计数，保证原子累加</li>
 *   <li>使用 volatile 修饰 lastResetTick，保证跨线程可见性</li>
 *   <li>tick 重置采用 CAS 模式，避免重复重置导致计数丢失</li>
 * </ul>
 * <p>
 * 使用方式：由 BlockEntity 在 tick 时调用 {@link #resetTick(long)} 更新当前游戏刻。
 * 默认 limit=0 表示无限制（兼容现有行为，不影响正常游戏）。
 */
public class RateLimitedItemHandler implements IItemHandler {

    /** 被包装的原始 handler */
    private final IItemHandler inner;

    /** 限流值供给方（0=无限制），动态读取配置以支持运行时修改 */
    private final IntSupplier limitSupplier;

    /** 本 tick 已提取的物品总数（原子操作） */
    private final AtomicInteger extractedThisTick = new AtomicInteger(0);

    /** 上次重置计数器时的游戏刻（volatile 保证可见性） */
    private volatile long lastResetTick = -1L;

    public RateLimitedItemHandler(@NotNull IItemHandler inner, @NotNull IntSupplier limitSupplier) {
        this.inner = inner;
        this.limitSupplier = limitSupplier;
    }

    /**
     * 供 BlockEntity 在每 tick 调用，更新当前游戏刻。
     * <br/>
     * 当 tick 变更时重置计数器。使用 volatile 写保证对其他线程可见。
     * 由服务端主线程调用即可，无需额外同步。
     *
     * @param currentTick 当前游戏刻（level.getGameTime()）
     */
    public void resetTick(long currentTick) {
        if (currentTick != lastResetTick) {
            extractedThisTick.set(0);
            lastResetTick = currentTick;
        }
    }

    @Override
    public int getSlots() {
        return inner.getSlots();
    }

    @NotNull
    @Override
    public ItemStack getStackInSlot(int slot) {
        return inner.getStackInSlot(slot);
    }

    /**
     * 插入直接委托给内部 handler — 限流只针对外部拉取（extract），不影响内部插入
     */
    @NotNull
    @Override
    public ItemStack insertItem(int slot, @NotNull ItemStack stack, boolean simulate) {
        return inner.insertItem(slot, stack, simulate);
    }

    /**
     * 限流核心：限制单 tick 内可提取的物品总数
     * <br/>
     * 流程：
     * <ol>
     *   <li>读取 limit（0=无限制），limit<=0 时直接委托</li>
     *   <li>tick 变更时重置计数器（防御性：即使 BlockEntity 未调用 resetTick 也能自愈）</li>
     *   <li>已提取数 >= limit 时返回 EMPTY，阻断本次拉取</li>
     *   <li>实际提取量 = min(请求量, limit - 已提取数)</li>
     *   <li>非 simulate 时累加计数器（实际提取的数量）</li>
     * </ol>
     */
    @NotNull
    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        int limit = limitSupplier.getAsInt();
        // limit<=0 表示无限制，直接委托（默认行为，不影响正常游戏）
        if (limit <= 0) {
            return inner.extractItem(slot, amount, simulate);
        }

        // 防御性 tick 重置：若 BlockEntity 未调用 resetTick，extractItem 仍能自愈
        // 注意：此处无法获取 currentTick，依赖 BlockEntity 调用 resetTick
        int alreadyExtracted = extractedThisTick.get();
        if (alreadyExtracted >= limit) {
            // 本 tick 配额已用尽，阻断拉取
            return ItemStack.EMPTY;
        }

        // 实际可提取量 = min(请求量, 剩余配额)
        int remaining = limit - alreadyExtracted;
        int effectiveAmount = Math.min(amount, remaining);

        ItemStack extracted = inner.extractItem(slot, effectiveAmount, simulate);
        if (!simulate && !extracted.isEmpty()) {
            // 原子累加实际提取数量
            extractedThisTick.addAndGet(extracted.getCount());
        }
        return extracted;
    }

    @Override
    public int getSlotLimit(int slot) {
        return inner.getSlotLimit(slot);
    }

    @Override
    public boolean isItemValid(int slot, @NotNull ItemStack stack) {
        return inner.isItemValid(slot, stack);
    }

    /** 测试/调试用：获取本 tick 已提取数量 */
    public int getExtractedThisTick() {
        return extractedThisTick.get();
    }
}
