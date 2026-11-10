package com.ayoshiko.productivebeesgenesis.mek;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 同一工厂已冻结的产物按完整组件合并直输；资产始终保存在原进程的已付缓冲。 */
final class PbFactoryOutputBatch {
    private final PbRecipeContext context;
    private final PbRecipeCompleter[] owners;
    private final boolean[] queued;
    private final int[] order;
    private final Map<ItemIdentity, List<ItemGroup>> items = new LinkedHashMap<>();
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
            // 同键只在 long 总额溢出时分组；未接收的部分继续既有邻接和本地槽路线。
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
                long offered = Math.max(0, entry.getValue());
                if (offered == 0) continue;
                var identity = new ItemIdentity(entry.getKey());
                var groups = items.computeIfAbsent(identity, key -> new ArrayList<>());
                ItemGroup group = groups.isEmpty() ? null : groups.getLast();
                if (group == null || offered > Long.MAX_VALUE - group.amount) {
                    group = new ItemGroup(entry.getKey());
                    groups.add(group);
                }
                group.sources.add(new ItemSource(owner, entry.getKey(), offered));
                group.amount += offered;
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
        for (List<ItemGroup> groups : items.values()) {
            for (ItemGroup group : groups) {
                if (PbRecipeFlusher.outputBlocked(context)) return;
                long handed = PbRecipeFlusher.directItemToAe(context, group.template, group.amount);
                long remaining = handed;
                for (ItemSource source : group.sources) {
                    long debit = Math.min(remaining, source.remaining);
                    if (debit == 0) continue;
                    Map<ItemStack, Long> pending = source.owner.getPendingOutputs();
                    long previous = pending.getOrDefault(source.template, 0L);
                    if (debit > previous) throw new IllegalStateException("Factory output source changed during handoff");
                    if (debit == previous) pending.remove(source.template);
                    else pending.put(source.template, previous - debit);
                    source.owner.consumePendingItemCount(debit);
                    source.remaining -= debit;
                    remaining -= debit;
                    if (remaining == 0) break;
                }
                if (remaining != 0) throw new IllegalStateException("Factory output receipt has no matching source");
                // 同键的后续溢出组共享接收能力；部分或零接收后不能继续重复请求。
                if (handed < group.amount) break;
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
            // 交付保持完整资产身份，不沿第三方放宽后的堆叠规则替换产物组件。
            return other instanceof ItemIdentity key
                    && template.getItem() == key.template.getItem()
                    && template.getComponents().equals(key.template.getComponents());
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
