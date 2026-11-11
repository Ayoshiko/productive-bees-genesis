package com.ayoshiko.productivebeesgenesis.apiary;

import net.minecraft.world.item.ItemStack;

import java.util.Arrays;
import java.util.List;
import java.util.function.LongConsumer;

/** 一次冻结刷新内的同身份交付索引；原栈始终是资产所有者，批次结束释放全部引用。 */
final class ApiaryFrozenItemBatch {
    private ItemStack[] sources = new ItemStack[0];
    private int[] sourceGroups = new int[0];
    private int[] nextSources = new int[0];
    private int[] groupTails = new int[0];
    private int[] groupHashes = new int[0];
    private boolean[] attempted = new boolean[0];
    private int[] buckets = new int[0];
    private int sourceCount;
    private boolean active;
    private boolean changed;
    private int directSource = -1;
    private long directRemaining;
    private final LongConsumer directReceipt = this::debitDirectReceipt;

    void prepare(List<ItemStack> items, boolean adjacentOnly) {
        if (active) throw new IllegalStateException("Frozen item batch already active");
        ensureCapacity(items.size());
        Arrays.fill(buckets, 0);
        Arrays.fill(attempted, 0, items.size(), false);
        sourceCount = items.size();
        active = true;
        changed = false;
        for (int index = 0; index < sourceCount; index++) {
            ItemStack stack = items.get(index);
            sources[index] = stack;
            sourceGroups[index] = -1;
            nextSources[index] = -1;
            if (stack.isEmpty()) continue;
            if (adjacentOnly) {
                int previous = index - 1;
                if (previous >= 0 && sourceGroups[previous] >= 0
                        && sameIdentity(sources[previous], stack)) {
                    int first = sourceGroups[previous];
                    sourceGroups[index] = first;
                    nextSources[groupTails[first]] = index;
                    groupTails[first] = index;
                } else {
                    sourceGroups[index] = index;
                    groupTails[index] = index;
                }
                continue;
            }
            int hash = ItemStack.hashItemAndComponents(stack);
            int bucket = (hash ^ (hash >>> 16)) & (buckets.length - 1);
            while (buckets[bucket] != 0) {
                int first = buckets[bucket] - 1;
                ItemStack template = sources[first];
                // 第三方可能放宽堆叠比较；交付键必须保留全部组件，不能借此替换资产身份。
                if (groupHashes[first] == hash && sameIdentity(template, stack)) {
                    sourceGroups[index] = first;
                    nextSources[groupTails[first]] = index;
                    groupTails[first] = index;
                    break;
                }
                bucket = (bucket + 1) & (buckets.length - 1);
            }
            if (sourceGroups[index] < 0) {
                sourceGroups[index] = index;
                groupTails[index] = index;
                groupHashes[index] = hash;
                buckets[bucket] = index + 1;
            }
        }
    }

    private static boolean sameIdentity(ItemStack first, ItemStack second) {
        return first.getItem() == second.getItem() && first.getComponents().equals(second.getComponents());
    }

    /** 直交只合并连续来源；每个目的槽的已知回执立即扣原源，后续异常不能重放前缀。 */
    boolean pushToCentrifuges(int sourceIndex, TileEntityMekApiary apiary) {
        int first = sourceGroups[sourceIndex];
        if (first < 0 || attempted[first] || nextSources[first] < 0) return false;
        attempted[first] = true;
        long requested = 0;
        for (int index = first; index >= 0; index = nextSources[index]) {
            requested += sources[index].getCount();
        }
        if (requested <= 0) return true;
        // 回调会清空首个原栈；后续目的槽必须继续使用独立身份模板。
        ItemStack template = sources[sourceIndex].copyWithCount(1);
        directSource = first;
        directRemaining = requested;
        try {
            long handed = apiary.directTransferProducedToCentrifuges(template, requested, directReceipt);
            if (handed != requested - directRemaining) {
                throw new IllegalStateException("Centrifuge receipt does not match source debits");
            }
        } finally {
            directSource = -1;
            directRemaining = 0;
        }
        return true;
    }

    private void debitDirectReceipt(long amount) {
        if (!active || directSource < 0 || amount < 0 || amount > directRemaining) {
            throw new IllegalStateException("Invalid frozen centrifuge receipt");
        }
        long remaining = amount;
        while (remaining > 0 && directSource >= 0) {
            ItemStack source = sources[directSource];
            int debit = (int) Math.min(remaining, source.getCount());
            if (debit > 0) {
                changed = true;
                source.shrink(debit);
                remaining -= debit;
            }
            if (source.isEmpty()) directSource = nextSources[directSource];
        }
        if (remaining != 0) throw new IllegalStateException("Centrifuge receipt has no matching source");
        directRemaining -= amount;
    }

    /** 只在物理遍历首次到达该身份时交付；部分接收的后缀继续按原源顺序回落。 */
    void push(int sourceIndex, TileEntityMekApiary apiary) {
        int first = sourceGroups[sourceIndex];
        if (first < 0 || attempted[first]) return;
        attempted[first] = true;
        long requested = 0;
        for (int index = first; index >= 0; index = nextSources[index]) {
            requested += sources[index].getCount();
        }
        if (requested <= 0) return;
        long handed = apiary.pushGeneratedItemToAe(sources[sourceIndex], requested);
        if (handed < 0 || handed > requested) {
            throw new IllegalStateException("Invalid frozen item receipt");
        }
        // 未知结果也由 OutputCustody 接管完整请求；同样扣除原源，禁止再走本地路线重放。
        for (int index = first; index >= 0 && handed > 0; index = nextSources[index]) {
            ItemStack source = sources[index];
            int debit = (int) Math.min(handed, source.getCount());
            if (debit <= 0) continue;
            changed = true;
            source.shrink(debit);
            handed -= debit;
        }
    }

    boolean changed() {
        return changed;
    }

    boolean active() {
        return active;
    }

    void clear() {
        Arrays.fill(sources, 0, sourceCount, null);
        sourceCount = 0;
        active = false;
        changed = false;
        directSource = -1;
        directRemaining = 0;
    }

    private void ensureCapacity(int required) {
        if (sources.length < required) {
            sources = new ItemStack[required];
            sourceGroups = new int[required];
            nextSources = new int[required];
            groupTails = new int[required];
            groupHashes = new int[required];
            attempted = new boolean[required];
        }
        int capacity = 2;
        while (capacity < 2L * required) capacity = Math.multiplyExact(capacity, 2);
        if (buckets.length < capacity) buckets = new int[capacity];
    }
}
