package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.mek.PbFactoryOutputGroups.Group;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2IntegrationLoader;
import com.ayoshiko.productivebeesgenesis.resource.ResourceCentrifugeProcessor.LocalBatch.Completion;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 同一工厂已冻结的产物按完整组件合并直输；资产始终保存在原进程的已付缓冲。 */
final class PbFactoryOutputBatch implements DeferredItemOutput {
    private final PbRecipeContext context;
    private final PbRecipeCompleter[] owners;
    private final boolean[] queued;
    private final boolean[] uncertain;
    private final Completion[] completions;
    private final int[] order;
    private final PbFactoryOutputGroups<ItemStack> items;
    private final PbFactoryOutputGroups<FluidStack> fluids;
    private int count;
    private boolean active;
    private boolean finishing;
    private boolean cancelled;
    private boolean deferred;
    private boolean claimed;
    private boolean completingDeferred;
    private long outputEpoch;
    private long deferredRecipeVersion;
    private ServerLevel deferredLevel;
    // 本批曾注册资源句柄后保留标志，嵌套卸载不会提前切换收尾路径。
    private boolean hasResourceCompletions;
    private boolean preparationPhase;
    private PbRecipeCompleter.OutputSettings outputSettings;

    PbFactoryOutputBatch(PbRecipeContext context, PbRecipeCompleter[] owners) {
        this.context = context;
        this.owners = owners;
        items = owners.length > 1 ? PbFactoryOutputGroups.items(owners.length) : null;
        fluids = owners.length > 1 ? PbFactoryOutputGroups.fluids(owners.length) : null;
        queued = new boolean[owners.length];
        uncertain = new boolean[owners.length];
        completions = new Completion[owners.length];
        order = new int[owners.length];
    }

    void beginPreparation() {
        preparationPhase = true;
        outputSettings = null;
    }

    void endPreparation() {
        preparationPhase = false;
        outputSettings = null;
    }

    PbRecipeCompleter.OutputSettings outputSettings() {
        if (!preparationPhase) return null;
        if (outputSettings == null || outputSettings.revision() != ProductiveBeesGenesis.RECIPE_VERSION.get()
                || outputSettings.configRevision() != MekCentrifugeFactoryHelper.runtimeConfigRevision()) {
            outputSettings = PbRecipeCompleter.OutputSettings.capture(context);
        }
        return outputSettings;
    }

    void begin() {
        beforeOwnerMutation();
        if (active || finishing || count != 0) throw new IllegalStateException("Factory output batch already active");
        cancelled = false;
        active = owners.length > 1 && context.productivebeesgenesis$isDirectAeOutputEnabled()
                && !PbRecipeFlusher.outputBlocked(context);
    }

    boolean canDefer(PbRecipeCompleter owner, int lane) {
        return active && !finishing && lane >= 0 && lane < owners.length && owners[lane] == owner
                && !queued[lane] && !PbRecipeFlusher.outputBlocked(context);
    }

    boolean defer(PbRecipeCompleter owner, int lane) {
        if (!active || finishing || lane < 0 || lane >= owners.length || owners[lane] != owner
                || owner.invalidPending() || owner.paymentPending()
                || owner.getPendingInputShrink() != 0 || !owner.isPreparedOutput()) return false;
        if (!queued[lane]) {
            queued[lane] = true;
            order[count++] = lane;
        }
        return true;
    }

    void defer(PbRecipeCompleter owner, int lane, Completion completion) {
        Objects.requireNonNull(completion);
        if (!canDefer(owner, lane) || !defer(owner, lane)) {
            throw new IllegalStateException("Resource output batch changed during handoff");
        }
        completions[lane] = completion;
        hasResourceCompletions = true;
    }

    void finish() {
        finish(false);
    }

    void finish(boolean allowDeferred) {
        if (finishing || deferred) throw new IllegalStateException("Factory output confirmation already in progress");
        active = false;
        finishing = true;
        boolean retained = false;
        try {
            if (count == 0 || outputStopped()) return;
            if (context.productivebeesgenesis$isDirectAeOutputEnabled()) {
                collect();
                if (allowDeferred && deferCollectedItems()) {
                    retained = true;
                    return;
                }
                if (outputStopped()) return;
                pushItems();
                pushFluids();
            }
        } finally {
            if (retained) {
                finishing = false;
            } else {
                try {
                    flushOwners();
                } finally {
                    completeOwners();
                }
            }
        }
    }

    private boolean deferCollectedItems() {
        if (hasResourceCompletions || items == null || items.groupCount() == 0
                || !(context instanceof BlockEntity blockEntity)
                || !(context.level() instanceof ServerLevel level) || blockEntity.isRemoved()) return false;
        deferredLevel = level;
        deferredRecipeVersion = ProductiveBeesGenesis.RECIPE_VERSION.get();
        outputEpoch = Math.incrementExact(outputEpoch);
        // 票据跨越本机调用栈，独立快照防止外部原地改组件后仍按旧键合并。
        for (int index = 0; index < items.groupCount(); index++) {
            Group<ItemStack> group = items.group(index);
            group.template = group.key == group ? group.template.copyWithCount(1) : group.key.template;
        }
        for (int index = 0; index < fluids.groupCount(); index++) {
            Group<FluidStack> group = fluids.group(index);
            group.template = group.key == group ? group.template.copyWithAmount(1) : group.key.template;
        }
        deferred = true;
        try {
            if (available(outputEpoch) && Ae2IntegrationLoader.deferItemOutput(this) && !cancelled) {
                if (context instanceof IFactoryPbDelegateAccess factory) {
                    for (int index = 0; index < count; index++) {
                        factory.productivebeesgenesis$getPbProcessor().markLaneRecovery(order[index], "刻末产物排队");
                    }
                }
                return true;
            }
        } catch (RuntimeException | LinkageError failure) {
            // 注册阶段不执行外部交付；撤销代际后可安全沿当前已收集批次同步交付。
            for (int index = 0; index < count; index++) reportFailure(order[index], "刻末产物排队", failure);
        }
        deferred = false;
        deferredLevel = null;
        return false;
    }

    @Override
    public BlockEntity owner() {
        return context instanceof BlockEntity blockEntity ? blockEntity : null;
    }

    @Override
    public long epoch() {
        return outputEpoch;
    }

    @Override
    public boolean available(long epoch) {
        if (!deferred || outputEpoch != epoch || cancelled || deferredLevel == null
                || ProductiveBeesGenesis.RECIPE_VERSION.get() != deferredRecipeVersion
                || !context.productivebeesgenesis$isDirectAeOutputEnabled()
                || !context.canFunction() || context.hasUnresolvedInput()) return false;
        BlockEntity blockEntity = owner();
        return blockEntity != null && !blockEntity.isRemoved() && blockEntity.getLevel() == deferredLevel
                && context.level() == deferredLevel && deferredLevel.hasChunkAt(blockEntity.getBlockPos())
                && deferredLevel.getBlockEntity(blockEntity.getBlockPos()) == blockEntity;
    }

    @Override
    public boolean claim(long epoch) {
        if (finishing || claimed || !available(epoch)) return false;
        if (!sourcesUnchanged()) {
            cancel(epoch);
            return false;
        }
        claimed = true;
        finishing = true;
        return true;
    }

    private boolean sourcesUnchanged() {
        for (int index = 0; index < count; index++) {
            PbRecipeCompleter owner = owners[order[index]];
            if (owner.invalidPending() || owner.paymentPending() || owner.getPendingInputShrink() != 0
                    || !owner.isPreparedOutput()) return false;
        }
        for (int index = 0; index < items.groupCount(); index++) {
            Group<ItemStack> group = items.group(index);
            for (int source = group.firstSource; source >= 0; source = items.nextSource(source)) {
                ItemStack template = items.sourceTemplate(source);
                if (!sameItem(group.template, template)
                        || owners[items.sourceLane(source)].getPendingOutputs().getOrDefault(template, 0L)
                                != items.sourceAmount(source)) return false;
            }
        }
        for (int index = 0; index < fluids.groupCount(); index++) {
            Group<FluidStack> group = fluids.group(index);
            for (int source = group.firstSource; source >= 0; source = fluids.nextSource(source)) {
                PbRecipeCompleter owner = owners[fluids.sourceLane(source)];
                FluidStack template = owner.getPendingFluidTemplate();
                if (template == null || !FluidStack.isSameFluidSameComponents(group.template, template)
                        || owner.getPendingFluidAmount() != fluids.sourceAmount(source)) return false;
            }
        }
        return true;
    }

    private static boolean sameItem(ItemStack first, ItemStack second) {
        return first.getItem() == second.getItem() && first.getComponents().equals(second.getComponents());
    }

    @Override
    public int groupCount() {
        return deferred && items != null ? items.groupCount() : 0;
    }

    @Override
    public ItemStack template(int group) {
        return deferredGroup(group).template;
    }

    @Override
    public long amount(int group) {
        return deferredGroup(group).amount;
    }

    private Group<ItemStack> deferredGroup(int group) {
        if (!deferred || items == null || group < 0 || group >= items.groupCount()) {
            throw new IllegalStateException("Stale factory output group");
        }
        return items.group(group);
    }

    @Override
    public void debit(int groupIndex, long amount) {
        if (!claimed || !finishing || amount < 0) throw new IllegalStateException("Factory output was not claimed");
        Group<ItemStack> group = deferredGroup(groupIndex);
        long remaining = 0;
        for (int source = group.firstSource; source >= 0; source = items.nextSource(source)) {
            remaining = Math.addExact(remaining, items.sourceAmount(source));
        }
        if (amount > remaining) throw new IllegalStateException("Deferred factory output receipt exceeds its sources");
        try {
            for (int source = group.firstSource; source >= 0; source = items.nextSource(source)) {
                long expected = items.sourceAmount(source);
                if (expected == 0) continue;
                ItemStack template = items.sourceTemplate(source);
                if (!sameItem(group.template, template)
                        || owners[items.sourceLane(source)].getPendingOutputs().getOrDefault(template, 0L) != expected) {
                    throw new IllegalStateException("Deferred factory output source changed during handoff");
                }
            }
            debitItems(group, amount);
        } catch (RuntimeException | LinkageError failure) {
            if (amount > 0) retainItemFailure(group, amount, true, failure);
            for (int source = group.firstSource; source >= 0; source = items.nextSource(source)) {
                int lane = items.sourceLane(source);
                if (hasResourceCompletions && amount > 0) uncertain[lane] = true;
                reportFailure(lane, "刻末物品来源扣除", failure);
            }
            throw failure;
        }
    }

    @Override
    public void finish(long epoch) {
        if (!deferred || outputEpoch != epoch) return;
        if (!claimed) {
            cancel(epoch);
            return;
        }
        if (completingDeferred) throw new IllegalStateException("Deferred factory output is already finishing");
        completingDeferred = true;
        try {
            if (!available(epoch)) cancelled = true;
            if (!outputStopped()) pushFluids();
        } finally {
            try {
                try {
                    flushOwners();
                } finally {
                    completeOwners();
                }
            } finally {
                completingDeferred = false;
            }
        }
    }

    @Override
    public void cancel(long epoch) {
        if (!deferred || outputEpoch != epoch) return;
        cancelled = true;
        // 认领后的回执仍须扣原来源；外部回调不能抢先清除工作区。
        if (claimed || finishing) return;
        reset(0);
    }

    void cancelDeferredOutput() {
        outputSettings = null;
        if (deferred) cancel(outputEpoch);
    }

    void beforeOwnerMutation() {
        if (!deferred) return;
        if (claimed) {
            cancel(outputEpoch);
            throw new IllegalStateException("Cannot replace centrifuge output during a claimed handoff");
        }
        cancel(outputEpoch);
    }

    private void flushOwners() {
        // 同键只在 long 总额溢出时分组；未接收部分继续既有邻接和本地槽路线。
        for (int index = 0; index < count && !cancelled; index++) {
            int lane = order[index];
            try {
                if (!PbRecipeFlusher.outputBlocked(context, lane) && owners[lane].flushAfterFactoryAe(lane)
                        && context instanceof IFactoryPbDelegateAccess factory) {
                    PbRecipeProcessor processor = factory.productivebeesgenesis$getPbProcessor();
                    processor.markLaneRecovery(lane, "本地输出排空");
                    processor.markLaneRecovery(lane, "物品分组交付");
                    processor.markLaneRecovery(lane, "流体分组交付");
                    processor.markLaneRecovery(lane, "输出分组准备");
                    processor.markLaneRecovery(lane, "刻末产物排队");
                    processor.markLaneRecovery(lane, "付款恢复或已付产物排空");
                }
            } catch (RuntimeException | LinkageError failure) {
                if (hasResourceCompletions && (owners[lane].invalidPending()
                        || PbRecipeFlusher.outputBlocked(context, lane))) uncertain[lane] = true;
                reportFailure(lane, "本地输出排空", failure);
            } finally {
                if (hasResourceCompletions && PbRecipeFlusher.outputBlocked(context, lane)) uncertain[lane] = true;
            }
        }
    }

    private void completeOwners() {
        try {
            for (int index = 0; index < count; index++) {
                int lane = order[index];
                try {
                    PbRecipeCompleter owner = owners[lane];
                    // custody 接管可能阻止后续 flush；已交空的原槽仍须结束冻结状态。
                    if (!owner.invalidPending() && !owner.paymentPending()
                            && owner.getPendingInputShrink() == 0 && !owner.hasPendingOutputs()) {
                        owner.clearPendingOutputs();
                        if (context instanceof IFactoryPbDelegateAccess factory) {
                            PbRecipeProcessor processor = factory.productivebeesgenesis$getPbProcessor();
                            processor.markLaneRecovery(lane, "批次收尾");
                        }
                    }
                } catch (RuntimeException | LinkageError failure) {
                    if (hasResourceCompletions) uncertain[lane] = true;
                    reportFailure(lane, "批次收尾", failure);
                } finally {
                    try {
                        if (hasResourceCompletions) confirm(lane);
                    } finally {
                        if (context instanceof IFactoryPbDelegateAccess factory) {
                            try {
                                factory.productivebeesgenesis$getPbProcessor().finishLaneAttempt(lane);
                            } catch (RuntimeException | LinkageError failure) {
                                reportFailure(lane, "批次收尾", failure);
                            }
                        }
                    }
                }
            }
        } finally {
            reset(0);
            finishing = false;
        }
    }

    /** 卸载可在外部回调内发生；先撤销完成句柄，正在结算的原产物仍由该调用收尾扣账。 */
    void abandon() {
        endPreparation();
        active = false;
        cancelled = true;
        boolean alreadyFinishing = finishing;
        finishing = true;
        try {
            if (hasResourceCompletions) {
                for (int index = 0; index < count; index++) {
                    int lane = order[index];
                    if (completions[lane] == null) continue;
                    uncertain[lane] = true;
                    confirm(lane);
                }
            }
        } finally {
            if (!alreadyFinishing) {
                reset(0);
                finishing = false;
            }
        }
    }

    private boolean outputStopped() {
        return cancelled || PbRecipeFlusher.outputBlocked(context);
    }

    private void confirm(int lane) {
        Completion completion = completions[lane];
        if (completion == null) return;
        completions[lane] = null;
        try {
            if (uncertain[lane] || cancelled || owners[lane].invalidPending() || owners[lane].paymentPending()) {
                completion.uncertain("Factory output requires reconciliation");
            } else {
                completion.complete();
                if (context instanceof IFactoryPbDelegateAccess factory) {
                    PbRecipeProcessor processor = factory.productivebeesgenesis$getPbProcessor();
                    processor.markLaneRecovery(lane, "资源组合交付确认");
                }
            }
        } catch (RuntimeException | LinkageError failure) {
            try {
                completion.uncertain("Factory output confirmation failed");
            } catch (RuntimeException | LinkageError isolationFailure) {
                failure.addSuppressed(isolationFailure);
            }
            reportFailure(lane, "资源组合交付确认", failure);
        }
    }

    private void reportFailure(int lane, String phase, Throwable failure) {
        if (context instanceof IFactoryPbDelegateAccess factory) {
            factory.productivebeesgenesis$getPbProcessor().reportLaneFailure(lane, phase, failure);
        } else {
            LogThrottle.errorWithCooldown("centrifuge_batch_failure", 60_000L,
                "离心机车道 {} 的 {} 失败，原输入与已付产物保留", lane, phase, failure);
        }
    }

    private void reset(int firstUncleared) {
        for (int index = firstUncleared; index < count; index++) {
            int lane = order[index];
            queued[lane] = false;
            if (hasResourceCompletions) {
                uncertain[lane] = false;
                completions[lane] = null;
            }
        }
        count = 0;
        hasResourceCompletions = false;
        deferred = false;
        claimed = false;
        deferredLevel = null;
        if (items != null) items.clear(cancelled);
        if (fluids != null) fluids.clear(cancelled);
    }

    private void collect() {
        for (int index = 0; index < count; index++) {
            int lane = order[index];
            try {
                PbRecipeCompleter owner = owners[lane];
                if (owner.invalidPending() || owner.paymentPending() || owner.getPendingInputShrink() != 0) {
                    if (hasResourceCompletions) uncertain[lane] = true;
                    continue;
                }
                for (var entry : owner.getPendingOutputs().entrySet()) {
                    items.add(lane, entry.getKey(), entry.getValue());
                }
                FluidStack fluid = owner.getPendingFluidTemplate();
                long amount = owner.getPendingFluidAmount();
                if (fluid != null && !fluid.isEmpty() && amount > 0) fluids.add(lane, fluid, amount);
            } catch (RuntimeException | LinkageError failure) {
                // 收集仍未交付，原 owner 保有全部资产；其它 owner 继续收集和本地回退。
                reportFailure(lane, "输出分组准备", failure);
            }
        }
    }

    private void pushItems() {
        for (Group<ItemStack> key = items.firstKey(); key != null; key = key.nextKey) {
            for (Group<ItemStack> group = key; group != null; group = group.nextPart) {
                if (outputStopped()) return;
                if (group.key.rejected) break;
                if (!groupSourcesReady(items, group)) continue;
                long handed = 0;
                boolean receiptKnown = false;
                try {
                    handed = PbRecipeFlusher.directItemToAe(context, group.template, group.amount);
                    receiptKnown = true;
                    debitItems(group, handed);
                } catch (RuntimeException | LinkageError failure) {
                    boolean unknown = receiptKnown && handed > 0 || externalExecutionUnresolved();
                    if (unknown) retainItemFailure(group, handed, receiptKnown, failure);
                    for (int source = group.firstSource; source >= 0; source = items.nextSource(source)) {
                        int lane = items.sourceLane(source);
                        if (hasResourceCompletions && unknown) uncertain[lane] = true;
                        reportFailure(lane, "物品分组交付", failure);
                    }
                    group.key.rejected = true;
                } finally {
                    if (hasResourceCompletions && PbRecipeFlusher.outputBlocked(context)) {
                        for (int source = group.firstSource; source >= 0; source = items.nextSource(source)) {
                            uncertain[items.sourceLane(source)] = true;
                        }
                    }
                }
                // 同键的后续溢出组共享接收能力；部分或零接收后不能继续重复请求。
                if (handed < group.amount) break;
            }
        }
    }

    /** 同一 owner 其它模板仍属于已保管前后证据，不能在后续分组再次交付。 */
    private <T> boolean groupSourcesReady(PbFactoryOutputGroups<T> groups, Group<T> group) {
        for (int source = group.firstSource; source >= 0; source = groups.nextSource(source)) {
            PbRecipeCompleter owner = owners[groups.sourceLane(source)];
            if (owner.invalidPending() || owner.paymentPending()) return false;
        }
        return true;
    }

    private boolean externalExecutionUnresolved() {
        var custody = PbRecipeFlusher.custody(context);
        return custody != null && (custody.sequence() != null || custody.localOutputBlocked());
    }

    private <T> CompoundTag outputFailureEvidence(PbFactoryOutputGroups<T> groups, Group<T> group,
            long handed, boolean receiptKnown, Throwable error) {
        CompoundTag evidence = new CompoundTag();
        evidence.putLong("requested", group.amount);
        evidence.putBoolean("receipt_known", receiptKnown);
        if (receiptKnown) evidence.putLong("received", handed);
        evidence.putString("error_type", error.getClass().getName());
        evidence.putString("error", String.valueOf(error.getMessage()));
        var custody = PbRecipeFlusher.custody(context);
        if (custody != null && custody.sequence() != null) evidence.putUUID("root_request", custody.sequence());
        ListTag sources = new ListTag();
        for (int source = group.firstSource; source >= 0; source = groups.nextSource(source)) {
            CompoundTag entry = new CompoundTag();
            entry.putInt("lane", groups.sourceLane(source));
            entry.putLong("before", groups.sourceInitialAmount(source));
            entry.putLong("remaining", groups.sourceAmount(source));
            sources.add(entry);
        }
        evidence.put("sources", sources);
        return evidence;
    }

    private void retainItemFailure(Group<ItemStack> group, long handed, boolean receiptKnown, Throwable failure) {
        CompoundTag evidence = outputFailureEvidence(items, group, handed, receiptKnown, failure);
        for (int source = group.firstSource; source >= 0; source = items.nextSource(source)) {
            int lane = items.sourceLane(source);
            if (owners[lane].invalidPending()) continue;
            Map<ItemStack, Long> original = new LinkedHashMap<>();
            for (int sameLane = group.firstSource; sameLane >= 0; sameLane = items.nextSource(sameLane)) {
                if (items.sourceLane(sameLane) == lane) {
                    original.put(items.sourceTemplate(sameLane), items.sourceInitialAmount(sameLane));
                }
            }
            try {
                owners[lane].retainItemOutputFailure(original, evidence);
            } catch (RuntimeException | LinkageError isolationFailure) {
                failure.addSuppressed(isolationFailure);
            }
        }
    }

    private void retainFluidFailure(Group<FluidStack> group, long handed, boolean receiptKnown, Throwable failure) {
        CompoundTag evidence = outputFailureEvidence(fluids, group, handed, receiptKnown, failure);
        for (int source = group.firstSource; source >= 0; source = fluids.nextSource(source)) {
            int lane = fluids.sourceLane(source);
            if (owners[lane].invalidPending()) continue;
            try {
                owners[lane].retainFluidOutputFailure(fluids.sourceTemplate(source),
                        fluids.sourceInitialAmount(source), evidence);
            } catch (RuntimeException | LinkageError isolationFailure) {
                failure.addSuppressed(isolationFailure);
            }
        }
    }

    private void debitItems(Group<ItemStack> group, long handed) {
        long remaining = handed;
        for (int source = group.firstSource; source >= 0; source = items.nextSource(source)) {
            long debit = Math.min(remaining, items.sourceAmount(source));
            if (debit == 0) continue;
            PbRecipeCompleter owner = owners[items.sourceLane(source)];
            ItemStack template = items.sourceTemplate(source);
            Map<ItemStack, Long> pending = owner.getPendingOutputs();
            long previous = pending.getOrDefault(template, 0L);
            if (previous != items.sourceAmount(source)) throw new IllegalStateException("Factory output source changed during handoff");
            if (debit == previous) pending.remove(template);
            else pending.put(template, previous - debit);
            owner.debitPendingItemCount(debit);
            items.debitSource(source, debit);
            remaining -= debit;
            if (remaining == 0) break;
        }
        if (remaining != 0) throw new IllegalStateException("Factory output receipt has no matching source");
        // 一个宿主保存所有来源；通知只能发生在确定回执的全部数量结清之后。
        if (handed > 0) owners[items.sourceLane(group.firstSource)].notifyCommittedOutputChange();
    }

    private void pushFluids() {
        // 流体保持分组创建顺序，不把后出现的溢出组提前到其它流体之前。
        for (int index = 0; index < fluids.groupCount(); index++) {
            Group<FluidStack> group = fluids.group(index);
            if (outputStopped()) break;
            if (group.key.rejected || !groupSourcesReady(fluids, group)) continue;
            long handed = 0;
            boolean receiptKnown = false;
            try {
                handed = PbRecipeFlusher.directFluid(context, group.template, group.amount);
                receiptKnown = true;
                long remaining = handed;
                for (int source = group.firstSource; source >= 0; source = fluids.nextSource(source)) {
                    long debit = Math.min(remaining, fluids.sourceAmount(source));
                    if (debit == 0) continue;
                    PbRecipeCompleter owner = owners[fluids.sourceLane(source)];
                    if (owner.getPendingFluidAmount() != fluids.sourceAmount(source)) {
                        throw new IllegalStateException("Factory fluid source changed during handoff");
                    }
                    owner.debitPendingFluid(debit);
                    fluids.debitSource(source, debit);
                    remaining -= debit;
                    if (remaining == 0) break;
                }
                if (remaining != 0) throw new IllegalStateException("Factory fluid receipt has no matching source");
                if (handed > 0) owners[fluids.sourceLane(group.firstSource)].notifyCommittedOutputChange();
                group.key.rejected = handed < group.amount;
            } catch (RuntimeException | LinkageError failure) {
                boolean unknown = receiptKnown && handed > 0 || externalExecutionUnresolved();
                if (unknown) retainFluidFailure(group, handed, receiptKnown, failure);
                for (int source = group.firstSource; source >= 0; source = fluids.nextSource(source)) {
                    int lane = fluids.sourceLane(source);
                    if (hasResourceCompletions && unknown) uncertain[lane] = true;
                    reportFailure(lane, "流体分组交付", failure);
                }
                group.key.rejected = true;
            } finally {
                if (hasResourceCompletions && PbRecipeFlusher.outputBlocked(context)) {
                    for (int source = group.firstSource; source >= 0; source = fluids.nextSource(source)) {
                        uncertain[fluids.sourceLane(source)] = true;
                    }
                }
            }
        }
    }
}
