package com.ayoshiko.productivebeesgenesis.mek;

import mekanism.api.inventory.IInventorySlot;
import net.minecraft.world.item.ItemStack;

/**
 * 工厂离心机输出槽标志位批量/增量管理器
 * <p>
 * 原实现：每次 insertItem 触发 {@link IContentsListener}， listener 中调用全量
 * {@code updateOutputSlotFlags()} 扫描所有进程，复杂度 O(processes) / 每次插入。
 * 在 256 倍时间手杖下，一个进程一次 completePbRecipe 可能产生数十个物品插入事件，
 * 导致 O(processes × inserts) 的级联开销（热力图中表现为 BasicInventorySlot.insertItem
 * → onContentsChanged → updateOutputSlotFlags → isSlotFull → getLimit 占比 8~13%）。
 * <p>
 * 本管理器改为：
 * <ul>
 *   <li>批量模式（begin/end）下 listener 只标记 dirty，不扫描；</li>
 *   <li>批量结束时只重新计算受影响的那一个进程，O(1)；</li>
 *   <li>非批量外部插入（SFM/AE2/漏斗）仍立即全量扫描，保持行为兼容。</li>
 * </ul>
 * 同时维护每进程 {@code hasItems} 与 {@code full} 状态，通过计数器 O(1) 给出全局标志。
 */
public final class OutputSlotFlagManager {

    private final PbRecipeContext context;
    private final boolean[] processHasItems;
    private final boolean[] processFull;
    private int hasItemsProcessCount;
    private int fullProcessCount;
    private int batchDepth;
    private boolean dirty;

    /**
     * 每进程输出槽物品数量（主+副1+副2）
     * <br/>
     * Step 5: 供 {@link #outputItemCount()} O(1) 读取，替代 Ejector Mixin 中
     * O(processes×3) 遍历的 {@code countOutputItems}。在 {@link #updateProcessInternal}
     * 中更新，{@link #updateProcess} 中维护增量。
     */
    private final long[] processItemCount;
    /** 所有进程输出槽物品总数（processItemCount 之和） */
    private long outputItemCount;

    /**
     * 每槽位上限缓存（identity 短路）
     * <br/>
     * {@code slot.getLimit(stack)} 在 owo 派生组件下会触发昂贵的 DataComponentMap 查询，
     * 而输出槽中的栈引用在多数插入操作中保持不变（仅 count 变化）。
     * 通过缓存 {@code stack == cachedStack} 时的上限，避免每次标志位更新都重新计算。
     * 索引 = process * 3 + slotIndex（0=主输出，1=副输出1，2=副输出2）。
     */
    private final ItemStack[] cachedLimitStacks;
    private final int[] cachedLimits;

    public OutputSlotFlagManager(PbRecipeContext context) {
        this.context = context;
        int processes = context.processes();
        this.processHasItems = new boolean[processes];
        this.processFull = new boolean[processes];
        this.processItemCount = new long[processes];
        this.cachedLimitStacks = new ItemStack[processes * 3];
        this.cachedLimits = new int[processes * 3];
    }

    /** 是否有任意输出槽含物品 */
    public boolean hasOutputItems() {
        return hasItemsProcessCount > 0;
    }

    /** 是否有任意进程的所有物品输出槽已满 */
    public boolean outputSlotsFull() {
        return fullProcessCount > 0;
    }

    /** 指定进程的所有物品输出槽是否已满 */
    public boolean outputSlotsFull(int process) {
        return process >= 0 && process < processFull.length && processFull[process];
    }

    /**
     * 所有输出槽的物品总数（O(1) 读取）
     * <br/>
     * Step 5: 供 Ejector Mixin 替代 O(processes×3) 遍历的 countOutputItems。
     * 在 {@link #updateProcessInternal} / {@link #updateProcess} / {@link #updateAll} 中维护。
     */
    public long outputItemCount() {
        return outputItemCount;
    }

    /** 输出槽内容变化时调用（由 IContentsListener 回调） */
    public void onSlotChanged() {
        if (batchDepth > 0) {
            dirty = true;
        } else {
            updateAll();
        }
    }

    /** 开始批量输出插入；嵌套调用安全 */
    public void beginBatch() {
        batchDepth++;
    }

    /**
     * 结束批量输出插入
     *
     * @param process 发生变化的进程索引
     * @return true 表示本批次确实更新了标志位（调用方需要执行 sorting/unpause）
     */
    public boolean endBatch(int process) {
        if (--batchDepth == 0 && dirty) {
            dirty = false;
            if (process >= 0 && process < processHasItems.length) {
                updateProcess(process);
            } else {
                updateAll();
            }
            return true;
        }
        return false;
    }

    /** 全量扫描并初始化计数器（初始化时或降级回退用） */
    public void updateAll() {
        hasItemsProcessCount = 0;
        fullProcessCount = 0;
        outputItemCount = 0;
        for (int i = 0; i < processHasItems.length; i++) {
            updateProcessInternal(i);
            if (processHasItems[i]) hasItemsProcessCount++;
            if (processFull[i]) fullProcessCount++;
            outputItemCount += processItemCount[i];
        }
    }

    /** 仅更新单个进程的标志位并维护全局计数器 */
    private void updateProcess(int process) {
        boolean oldHasItems = processHasItems[process];
        boolean oldFull = processFull[process];
        long oldItemCount = processItemCount[process];
        updateProcessInternal(process);
        if (oldHasItems != processHasItems[process]) {
            hasItemsProcessCount += processHasItems[process] ? 1 : -1;
        }
        if (oldFull != processFull[process]) {
            fullProcessCount += processFull[process] ? 1 : -1;
        }
        // 维护 outputItemCount 增量（避免全量遍历）
        outputItemCount += processItemCount[process] - oldItemCount;
    }

    private void updateProcessInternal(int process) {
        IInventorySlot primary = context.primaryOutputSlot(process);
        IInventorySlot secondary = context.secondaryOutputSlot(process);
        IInventorySlot tertiary = context.tertiaryOutputSlot(process);

        ItemStack primaryStack = primary.getStack();
        ItemStack secondaryStack = secondary == null ? ItemStack.EMPTY : secondary.getStack();
        ItemStack tertiaryStack = tertiary.getStack();

        boolean primaryEmpty = primaryStack.isEmpty();
        boolean secondaryEmpty = secondaryStack.isEmpty();
        boolean tertiaryEmpty = tertiaryStack.isEmpty();

        processHasItems[process] = !primaryEmpty || !secondaryEmpty || !tertiaryEmpty;
        processFull[process] = isFullCached(process, 0, primary)
                && (secondary == null || isFullCached(process, 1, secondary))
                && isFullCached(process, 2, tertiary);

        // 累加本进程三槽位的物品数量（空槽为 0）
        long count = 0;
        if (!primaryEmpty) count += primaryStack.getCount();
        if (!secondaryEmpty) count += secondaryStack.getCount();
        if (!tertiaryEmpty) count += tertiaryStack.getCount();
        processItemCount[process] = count;
    }

    /**
     * 检查槽位是否已满，带上限 identity 缓存
     *
     * @param process  进程索引
     * @param slotIdx  槽位索引（0=主输出，1=副输出1，2=副输出2）
     * @param slot     输出槽
     */
    private boolean isFullCached(int process, int slotIdx, IInventorySlot slot) {
        ItemStack stack = slot.getStack();
        if (stack.isEmpty()) {
            return false;
        }
        int cacheIdx = process * 3 + slotIdx;
        if (stack == cachedLimitStacks[cacheIdx]) {
            return stack.getCount() >= cachedLimits[cacheIdx];
        }
        int limit = slot.getLimit(stack);
        cachedLimitStacks[cacheIdx] = stack;
        cachedLimits[cacheIdx] = limit;
        return stack.getCount() >= limit;
    }
}
