package com.ayoshiko.productivebeesgenesis.mek;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 同一工厂本批新产物按完整组件合并直输；资产始终保存在原进程的已付缓冲。 */
final class PbFactoryOutputBatch {
    private final PbRecipeContext context;
    private final PbRecipeCompleter[] owners;
    private final boolean[] queued;
    private final int[] order;
    private final Map<ItemIdentity, ItemGroup> items = new LinkedHashMap<>();
    private final List<FluidGroup> fluids = new ArrayList<>();
    private int count;
    private boolean active;

    PbFactoryOutputBatch(PbRecipeContext context, PbRecipeCompleter[] owners) {
        this.context = context;
        this.owners = owners;
        queued = new boolean[owners.length];
        order = new int[owners.length];
    }

    void begin() {
        if (active || count != 0) throw new IllegalStateException("Factory output batch already active");
        active = owners.length > 1 && context.productivebeesgenesis$isDirectAeOutputEnabled()
                && !PbRecipeFlusher.outputBlocked(context);
    }

    boolean defer(PbRecipeCompleter owner, int lane) {
        if (!active || owners[lane] != owner || owner.invalidPending() || owner.paymentPending()
                || owner.getPendingInputShrink() != 0 || !owner.isPreparedOutput()) return false;
        if (!queued[lane]) {
            queued[lane] = true;
            order[count++] = lane;
        }
        return true;
    }

    void finish() {
        active = false;
        try {
            if (count == 0 || PbRecipeFlusher.outputBlocked(context)) return;
            if (context.productivebeesgenesis$isDirectAeOutputEnabled()) {
                collect();
                pushItems();
                pushFluids();
            }
            // 每个键本批只走一次 AE 路线；未接收的部分继续既有邻接和本地槽路线。
            for (int index = 0; index < count && !PbRecipeFlusher.outputBlocked(context); index++) {
                int lane = order[index];
                owners[lane].flushAfterFactoryAe(lane);
            }
        } finally {
            for (int index = 0; index < count; index++) {
                int lane = order[index];
                PbRecipeCompleter owner = owners[lane];
                // custody 接管可能阻止后续 flush；已交空的原槽仍须结束冻结状态。
                if (!owner.invalidPending() && !owner.paymentPending()
                        && owner.getPendingInputShrink() == 0 && !owner.hasPendingOutputs()) {
                    owner.clearPendingOutputs();
                }
                queued[lane] = false;
            }
            count = 0;
            items.clear();
            fluids.clear();
        }
    }

    private void collect() {
        for (int index = 0; index < count; index++) {
            PbRecipeCompleter owner = owners[order[index]];
            if (owner.invalidPending() || owner.paymentPending() || owner.getPendingInputShrink() != 0) continue;
            for (var entry : owner.getPendingOutputs().entrySet()) {
                long offered = Math.min(Integer.MAX_VALUE, Math.max(0, entry.getValue()));
                if (offered == 0) continue;
                var identity = new ItemIdentity(entry.getKey());
                var group = items.computeIfAbsent(identity, key -> new ItemGroup(entry.getKey()));
                group.sources.add(new ItemSource(owner, entry.getKey(), offered));
                group.amount = Math.addExact(group.amount, offered);
            }
            FluidStack fluid = owner.getPendingFluidTemplate();
            long amount = owner.getPendingFluidAmount();
            if (fluid == null || fluid.isEmpty() || amount <= 0) continue;
            FluidGroup group = null;
            for (FluidGroup candidate : fluids) {
                if (FluidStack.isSameFluidSameComponents(candidate.template, fluid)
                        && amount <= Long.MAX_VALUE - candidate.amount) {
                    group = candidate;
                    break;
                }
            }
            if (group == null) {
                group = new FluidGroup(fluid);
                fluids.add(group);
            }
            group.sources.add(new FluidSource(owner, amount));
            group.amount += amount;
        }
    }

    private void pushItems() {
        for (ItemGroup group : items.values()) {
            int sourceIndex = 0;
            while (group.amount > 0 && !PbRecipeFlusher.outputBlocked(context)) {
                int requested = (int) Math.min(Integer.MAX_VALUE, group.amount);
                int handed = PbRecipeFlusher.directItem(context, group.template, requested, true);
                if (handed == 0) break;
                long remaining = handed;
                while (remaining > 0 && sourceIndex < group.sources.size()) {
                    ItemSource source = group.sources.get(sourceIndex);
                    long debit = Math.min(remaining, source.remaining);
                    if (debit == 0) {
                        sourceIndex++;
                        continue;
                    }
                    Map<ItemStack, Long> pending = source.owner.getPendingOutputs();
                    long previous = pending.getOrDefault(source.template, 0L);
                    if (debit > previous) throw new IllegalStateException("Factory output source changed during handoff");
                    if (debit == previous) pending.remove(source.template);
                    else pending.put(source.template, previous - debit);
                    source.owner.consumePendingItemCount(debit);
                    source.remaining -= debit;
                    remaining -= debit;
                    if (source.remaining == 0) sourceIndex++;
                }
                group.amount -= handed;
                if (handed < requested) break;
            }
        }
    }

    private void pushFluids() {
        for (FluidGroup group : fluids) {
            if (PbRecipeFlusher.outputBlocked(context)) break;
            long handed = PbRecipeFlusher.directFluid(context, group.template, group.amount);
            long remaining = handed;
            for (FluidSource source : group.sources) {
                long debit = Math.min(remaining, source.amount);
                if (debit == 0) continue;
                source.owner.consumePendingFluid(debit);
                remaining -= debit;
                if (remaining == 0) break;
            }
        }
    }

    private static final class ItemIdentity {
        private final ItemStack template;
        private final int hash;

        private ItemIdentity(ItemStack template) {
            this.template = template;
            hash = ItemStack.hashItemAndComponents(template);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ItemIdentity key
                    && ItemStack.isSameItemSameComponents(template, key.template);
        }
    }

    private static final class ItemGroup {
        private final ItemStack template;
        private final List<ItemSource> sources = new ArrayList<>();
        private long amount;

        private ItemGroup(ItemStack template) {
            this.template = template;
        }
    }

    private static final class ItemSource {
        private final PbRecipeCompleter owner;
        private final ItemStack template;
        private long remaining;

        private ItemSource(PbRecipeCompleter owner, ItemStack template, long remaining) {
            this.owner = owner;
            this.template = template;
            this.remaining = remaining;
        }
    }

    private static final class FluidGroup {
        private final FluidStack template;
        private final List<FluidSource> sources = new ArrayList<>();
        private long amount;

        private FluidGroup(FluidStack template) {
            this.template = template;
        }
    }

    private record FluidSource(PbRecipeCompleter owner, long amount) {}
}
