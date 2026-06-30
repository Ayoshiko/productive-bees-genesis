package com.ayoshiko.productivebeesgenesis.mek;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import org.jetbrains.annotations.NotNull;

import com.ayoshiko.productivebeesgenesis.AbstractCombEventHandler;

import cy.jdkdigital.productivebees.init.ModDataComponents;
import cy.jdkdigital.productivebees.init.ModItems;
import mekanism.api.inventory.IInventorySlot;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 万象创世产物批量插入规划器
 * <br/>
 * 将“按 bee_type 分配的数量”转换为“槽位索引 → 增长数量/是否空槽/模板”的执行计划，
 * 避免传统 insertItem 路径中反复的 ItemStack.copy()、DataComponentMap 派生与 listener 扫描。
 * <p>
 * 设计原则：
 * <ul>
 *   <li>单一职责：只负责规划与执行插入，不参与类型选择/分配</li>
 *   <li>零模拟副本：plan 阶段只读取槽位 beeType/count/limit，不复制 ItemStack</li>
 *   <li>直接操作：确认容量足够后，空槽直接 setStack，同类型槽直接 grow</li>
 * </ul>
 * <p>
 * 线程安全：plan 与 apply 在服务端主线程执行，无并发竞争。
 */
public final class MyriadBatchPlanner {

    private MyriadBatchPlanner() {
    }

    /** 单个槽位的执行计划 */
    public record SlotPlan(int slotIndex, int amount, boolean wasEmpty, ItemStack template) {
    }

    /** 批量插入计划 */
    public record Plan(@Nullable List<SlotPlan> plans) {
        private static final Plan FAILURE = new Plan(null);

        public boolean isSuccess() {
            return plans != null;
        }

        public List<SlotPlan> getPlans() {
            return plans;
        }

        public static Plan success(List<SlotPlan> plans) {
            return new Plan(plans);
        }

        public static Plan failure() {
            return FAILURE;
        }
    }

    /**
     * 槽位容量快照
     * <br/>
     * 一次性读取每个输出槽的 limit/count/bee_type，避免批量规划过程中反复调用
     * {@link IInventorySlot#getLimit(ItemStack)} 等可能开销较大的方法。
     * 同一 tick 内同一进程的输出槽 limit 不变，因此一次 complete 调用只需拍一张快照。
     */
    public static final class SlotCapacitySnapshot {
        public final long tick;
        public final int slotCount;
        public final boolean[] empty;
        public final ResourceLocation[] slotBeeTypes;
        public final int[] slotCounts;
        public final int[] slotLimits;
        /** 可用于万象产物的剩余总容量（仅统计空槽与可配置蜜脾/蜜脾块槽） */
        public final long totalRemainingCapacity;

        private SlotCapacitySnapshot(long tick, int slotCount, boolean[] empty,
                                     ResourceLocation[] slotBeeTypes, int[] slotCounts,
                                     int[] slotLimits, long totalRemainingCapacity) {
            this.tick = tick;
            this.slotCount = slotCount;
            this.empty = empty;
            this.slotBeeTypes = slotBeeTypes;
            this.slotCounts = slotCounts;
            this.slotLimits = slotLimits;
            this.totalRemainingCapacity = totalRemainingCapacity;
        }
    }

    /**
     * 拍摄输出槽容量快照
     * <br/>
     * 空槽容量优先使用 {@link IInventorySlot#getLimit(ItemStack)}（传入基础物品模板），
     * 若调用异常或返回非正数则回退到 {@link Item#getMaxStackSize(ItemStack)}；
     * 非空槽容量使用 {@code slot.getLimit(stack) - stack.getCount()}，准确反映槽位自定义上限。
     *
     * @param slots    输出槽列表
     * @param baseItem 基础物品（configurable_honeycomb 或 configurable_comb_block）
     * @param tick     当前游戏刻，用于缓存标识
     * @return 容量快照
     */
    @NotNull
    public static SlotCapacitySnapshot takeSnapshot(List<IInventorySlot> slots, Item baseItem, long tick) {
        int slotCount = slots.size();
        boolean[] empty = new boolean[slotCount];
        ResourceLocation[] slotBeeTypes = new ResourceLocation[slotCount];
        int[] slotCounts = new int[slotCount];
        int[] slotLimits = new int[slotCount];
        long totalRemainingCapacity = 0L;

        // 空槽模板： bee_type 不影响槽位 limit，统一用一个无 bee_type 模板
        ItemStack emptyTemplate = new ItemStack(baseItem);

        for (int i = 0; i < slotCount; i++) {
            IInventorySlot slot = slots.get(i);
            if (slot == null) {
                slotLimits[i] = 0;
                continue;
            }
            ItemStack stack = slot.getStack();
            if (stack.isEmpty()) {
                empty[i] = true;
                int limit = safeGetSlotLimit(slot, emptyTemplate);
                if (limit <= 0) {
                    limit = baseItem.getMaxStackSize(ItemStack.EMPTY);
                }
                slotLimits[i] = limit;
                totalRemainingCapacity += limit;
            } else {
                empty[i] = false;
                int count = stack.getCount();
                slotCounts[i] = count;
                int limit = safeGetSlotLimit(slot, stack);
                if (limit < count) {
                    // 防御性：limit 不应小于当前数量，若出现则按当前数量处理（剩余 0）
                    limit = count;
                }
                slotLimits[i] = limit;
                Item item = stack.getItem();
                if (item == ModItems.CONFIGURABLE_HONEYCOMB.get() || item == ModItems.CONFIGURABLE_COMB_BLOCK.get()) {
                    slotBeeTypes[i] = stack.get(ModDataComponents.BEE_TYPE.get());
                    totalRemainingCapacity += (long) limit - count;
                }
            }
        }

        return new SlotCapacitySnapshot(tick, slotCount, empty, slotBeeTypes,
                slotCounts, slotLimits, totalRemainingCapacity);
    }

    private static int safeGetSlotLimit(IInventorySlot slot, ItemStack stack) {
        try {
            return slot.getLimit(stack);
        } catch (Exception e) {
            // 某些槽位实现可能因组件派生抛出异常，回退到物品自身上限保证稳定性
            return stack.getItem().getMaxStackSize(stack);
        }
    }

    /**
     * 规划批量插入（兼容旧签名：内部自动拍摄快照）
     *
     * @param slots      输出槽列表（允许 null 元素，会被忽略）
     * @param baseItem   基础物品
     * @param allocation 蜜蜂类型 → 数量的分配映射
     * @return 可成功插入的计划；失败返回 failure
     */
    @NotNull
    public static Plan plan(List<IInventorySlot> slots, Item baseItem,
                            Map<ResourceLocation, Integer> allocation) {
        return plan(takeSnapshot(slots, baseItem, -1L), baseItem, allocation);
    }

    /**
     * 基于容量快照规划批量插入
     * <br/>
     * 按以下优先级分配：
     * <ol>
     *   <li>已有相同 bee_type 的槽位（优先堆叠）</li>
     *   <li>空槽</li>
     * </ol>
     * 任一 bee_type 无法完整放下时返回 {@link Plan#failure()}，保证原子性。
     *
     * @param snapshot   槽位容量快照
     * @param baseItem   基础物品
     * @param allocation 蜜蜂类型 → 数量的分配映射
     * @return 可成功插入的计划；失败返回 failure
     */
    @NotNull
    public static Plan plan(SlotCapacitySnapshot snapshot, Item baseItem,
                            Map<ResourceLocation, Integer> allocation) {
        int slotCount = snapshot.slotCount;
        int[] addAmounts = new int[slotCount];
        boolean[] wasEmpty = new boolean[slotCount];
        ItemStack[] templates = new ItemStack[slotCount];

        for (Map.Entry<ResourceLocation, Integer> entry : allocation.entrySet()) {
            ResourceLocation beeType = entry.getKey();
            int remaining = entry.getValue();
            if (remaining <= 0) {
                continue;
            }

            // 第一优先级：已有同类型槽位（走 grow 路径，不需要 template）
            for (int i = 0; i < slotCount && remaining > 0; i++) {
                if (snapshot.empty[i] || snapshot.slotBeeTypes[i] == null
                        || !snapshot.slotBeeTypes[i].equals(beeType)) {
                    continue;
                }
                int space = snapshot.slotLimits[i] - snapshot.slotCounts[i];
                if (space <= 0) {
                    continue;
                }
                int add = Math.min(space, remaining);
                addAmounts[i] += add;
                wasEmpty[i] = false;
                remaining -= add;
            }

            // 第二优先级：空槽
            for (int i = 0; i < slotCount && remaining > 0; i++) {
                if (!snapshot.empty[i]) {
                    continue;
                }
                int space = snapshot.slotLimits[i];
                if (space <= 0) {
                    continue;
                }
                int add = Math.min(space, remaining);
                addAmounts[i] += add;
                wasEmpty[i] = true;
                remaining -= add;
                if (templates[i] == null) {
                    templates[i] = createTemplate(baseItem, beeType);
                }
            }

            // 仍有剩余说明物理上放不下
            if (remaining > 0) {
                return Plan.failure();
            }
        }

        List<SlotPlan> plans = new ArrayList<>(slotCount);
        for (int i = 0; i < slotCount; i++) {
            if (addAmounts[i] > 0) {
                plans.add(new SlotPlan(i, addAmounts[i], wasEmpty[i], templates[i]));
            }
        }
        return Plan.success(plans);
    }

    /**
     * 计算输出槽能容纳的最大输入数量
     * <br/>
     * 根据容量快照的 {@code totalRemainingCapacity} 与产物倍率 {@code multiplier} 确定初始上界，
     * 再用二分搜索在该上界内找到能够完整插入的最大 batch size。相比原代码从
     * {@code operationsPerTick} 开始逐级减半，这里直接按“输出槽剩余总容量”定位可行区间，
     * 在 Mekanism Unleashed 高 opsPerTick 场景下可一次处理 64 甚至更多输入。
     *
     * @param snapshot      容量快照
     * @param multiplier    单个输入产出的物品数量（蜜脾=1，蜜脾块=4）
     * @param selectedTypes 候选蜜蜂类型列表（通常 1~3 个）
     * @param maxRequested  调用方允许的最大 batch size（如 operationsPerTick 与输入数量的较小值）
     * @return 最大可容纳的输入数量；0 表示完全放不下
     */
    public static int planOrFindMaxBatch(SlotCapacitySnapshot snapshot, Item baseItem, int multiplier,
                                         List<ResourceLocation> selectedTypes, int maxRequested) {
        if (snapshot == null || baseItem == null || selectedTypes == null || selectedTypes.isEmpty()
                || maxRequested <= 0 || multiplier <= 0) {
            return 0;
        }

        long maxByCapacity = snapshot.totalRemainingCapacity / multiplier;
        int high = (int) Math.min(maxByCapacity, maxRequested);
        if (high <= 0) {
            return 0;
        }

        int best = 0;
        int low = 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            int totalCount = mid * multiplier;
            // 类型数不超过总数量（避免 allocateEvenly 出现大量 0 分配），也不超过 3 种
            int typesToUse = Math.min(selectedTypes.size(), Math.max(1, Math.min(totalCount, 3)));
            Map<ResourceLocation, Integer> allocation = AbstractCombEventHandler.allocateEvenly(
                    totalCount, selectedTypes.subList(0, typesToUse));

            Plan plan = plan(snapshot, baseItem, allocation);
            if (plan.isSuccess()) {
                best = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return best;
    }

    /**
     * 执行插入计划
     * <br/>
     * 应在 {@link PbRecipeContext#productivebeesgenesis$beginOutputBatch()} /
     * {@link PbRecipeContext#productivebeesgenesis$endOutputBatch(int)} 之间调用，
     * 或基础机的等效批量包装内调用，以避免每次插入都触发 listener 扫描。
     *
     * @param plan  执行计划
     * @param slots 输出槽列表
     */
    public static void apply(@NotNull Plan plan, @NotNull List<IInventorySlot> slots) {
        if (!plan.isSuccess()) {
            return;
        }
        for (SlotPlan slotPlan : plan.getPlans()) {
            IInventorySlot slot = slots.get(slotPlan.slotIndex());
            if (slot == null) {
                continue;
            }
            if (slotPlan.wasEmpty()) {
                ItemStack toSet = slotPlan.template().copyWithCount(slotPlan.amount());
                slot.setStack(toSet);
            } else {
                slot.getStack().grow(slotPlan.amount());
            }
        }
    }

    /**
     * 执行插入计划（重载：指定 begin/end 回调）
     * <br/>
     * 供基础机使用，避免调用方手动包装批量。
     */
    public static void apply(@NotNull Plan plan, @NotNull List<IInventorySlot> slots,
                             @NotNull Runnable beginBatch, @NotNull Runnable endBatch) {
        beginBatch.run();
        try {
            apply(plan, slots);
        } finally {
            endBatch.run();
        }
    }

    /** 创建带 bee_type 的模板（不设置 count） */
    private static ItemStack createTemplate(Item baseItem, ResourceLocation beeType) {
        ItemStack template = new ItemStack(baseItem);
        template.set(ModDataComponents.BEE_TYPE.get(), beeType);
        return template;
    }
}
