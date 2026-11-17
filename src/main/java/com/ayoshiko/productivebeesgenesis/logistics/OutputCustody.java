package com.ayoshiko.productivebeesgenesis.logistics;

import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import mekanism.common.tile.base.TileEntityMekanism;
import mekanism.common.tile.interfaces.ISideConfiguration;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.UUID;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

/**
 * 普通输出按外部请求和本地物理位置保管。返回值是已交给目标或本保管器的数量，不是假定的目标接收量。
 * 调用方必须扣除返回的交接量；未知量只存在本记录中，不能作为库存自动重试。
 * 服务器线程独占；组件负责保存与升级交接。跨文件断电原子性不在此类保证范围内。
 */
public final class OutputCustody {
    private final Runnable changed;
    private UUID sequence;
    private ItemStack item = ItemStack.EMPTY;
    private FluidStack fluid = FluidStack.EMPTY;
    private long amount;
    private String destination = "";
    private String failure = "";
    /** 通知不拥有资产；持续失败保留首次原因，正常回调后清理。 */
    private Throwable notificationFailure;
    private UUID notificationRequest;
    private String notificationTarget;
    private long notificationAmount;
    private boolean executing;
    private boolean resolved;
    private CompoundTag invalid;
    private long sourceDebitPending;
    private boolean sourceDebitUnknown;
    private boolean receiptKnown;
    private boolean localEvidence;
    private long localRequested;
    private ItemStack localItemBefore = ItemStack.EMPTY;
    private FluidStack localFluidBefore = FluidStack.EMPTY;
    private final Map<Integer, OutputCustody> local = new LinkedHashMap<>();

    public OutputCustody(Runnable changed) { this.changed = changed; }

    public static OutputCustody of(Object host) {
        if (host instanceof ISideConfiguration configured
                && configured.getEjector() instanceof GenesisTileComponentEjector ejector) {
            return ejector.outputCustody();
        }
        if (host instanceof TileEntityMekanism tile) {
            for (var component : tile.getComponents()) {
                if (component instanceof GenesisTileComponentEjector ejector) return ejector.outputCustody();
            }
        }
        throw new IllegalStateException("Output owner has no custody component");
    }

    /** 玩家提示聚合全部未决请求；实际交接按根请求或本地物理位置隔离。 */
    public boolean blocked() {
        if (ownBlocked()) return true;
        for (OutputCustody record : local.values()) if (record.localPositionBlocked()) return true;
        return false;
    }

    /** 本地未决只冻结对应槽罐；根外部请求未结清时不得覆盖或重放。 */
    public boolean externalOutputBlocked() {
        return ownBlocked();
    }

    private boolean ownBlocked() {
        return executing || sequence != null || invalid != null;
    }

    public boolean localOutputBlocked() {
        return executing || invalid != null || sourceDebitPending > 0 || sourceDebitUnknown;
    }

    public boolean localItemBlocked(int physicalSlot) {
        OutputCustody record = local.get(physicalSlot);
        return record != null && record.localPositionBlocked();
    }

    public boolean localFluidBlocked(int tank) {
        OutputCustody record = local.get(-tank - 1);
        return record != null && record.localPositionBlocked();
    }

    private boolean localPositionBlocked() {
        return executing || invalid != null || sourceDebitPending > 0 || sourceDebitUnknown || sequence != null && !resolved;
    }

    public OutputCustody localItem(int physicalSlot) {
        return local.computeIfAbsent(physicalSlot, key -> new OutputCustody(changed));
    }

    public OutputCustody localFluid(int tank) {
        return local.computeIfAbsent(-tank - 1, key -> new OutputCustody(changed));
    }

    Map<Integer, OutputCustody> localRecords() { return local; }

    public List<OutputCustody> requests() {
        List<OutputCustody> records = new ArrayList<>();
        if (ownBlocked()) records.add(this);
        for (OutputCustody entry : local.values()) {
            if (entry.ownBlocked()) records.add(entry);
        }
        return records;
    }

    public OutputCustody request(UUID expected) {
        for (OutputCustody entry : requests()) {
            if (expected.equals(entry.sequence())) return entry;
        }
        throw new IllegalStateException("Stale output request");
    }
    /** 成功交付通常不需恢复 ID；执行中保存／查询和异常保管仍得到同一个稳定身份。 */
    public UUID sequence() {
        if (sequence == null && executing && amount > 0) sequence = UUID.randomUUID();
        return sequence;
    }
    public long amount() { return amount; }
    public String failure() { return failure; }
    public String destination() { return destination; }

    /** stack 仍由调用方持有；只交接返回的数量，零表示完全没有执行。 */
    public int handoffItem(ItemStack stack, String target, LongSupplier insert) {
        if (externalOutputBlocked() || stack.isEmpty()) return 0;
        return (int) handoff(stack.copyWithCount(1), FluidStack.EMPTY, stack.getCount(), target, insert);
    }

    public long handoffItem(ItemStack template, long requested, String target, LongSupplier insert) {
        if (externalOutputBlocked() || template.isEmpty() || requested <= 0) return 0;
        return handoff(template.copyWithCount(1), FluidStack.EMPTY, requested, target, insert);
    }

    /** 本机位置可在回调失败后核对真实增量；无法回读时保管完整原请求和库存前值。 */
    public long handoffLocalItem(ItemStack template, long requested, ItemStack before, String target,
            Supplier<ItemStack> current, LongSupplier insert) {
        if (blocked() || template.isEmpty() || requested <= 0) return 0;
        localItemBefore = before.copy();
        localFluidBefore = FluidStack.EMPTY;
        localEvidence = true;
        localRequested = requested;
        return handoffLocal(template.copyWithCount(1), FluidStack.EMPTY, requested, target,
                () -> observedItemAcceptance(current.get()), insert);
    }

    public long handoffLocalFluid(FluidStack template, long requested, FluidStack before, String target,
            Supplier<FluidStack> current, LongSupplier insert) {
        if (blocked() || template.isEmpty() || requested <= 0) return 0;
        localFluidBefore = before.copy();
        localItemBefore = ItemStack.EMPTY;
        localEvidence = true;
        localRequested = requested;
        return handoffLocal(ItemStack.EMPTY, template.copyWithAmount(1), requested, target,
                () -> observedFluidAcceptance(current.get()), insert);
    }

    private long handoffLocal(ItemStack template, FluidStack fluidTemplate, long requested, String target,
            LongSupplier observe, LongSupplier insert) {
        item = template;
        fluid = fluidTemplate;
        amount = requested;
        destination = target;
        failure = "";
        resolved = false;
        executing = true;
        prepareHandoff();
        try {
            long accepted;
            try {
                accepted = insert.getAsLong();
                validateLocalReceipt(accepted);
            } catch (RuntimeException | LinkageError error) {
                try {
                    accepted = observe.getAsLong();
                    validateLocalReceipt(accepted);
                } catch (RuntimeException | LinkageError unreadable) {
                    error.addSuppressed(unreadable);
                    sequence();
                    failure = error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage());
                    if (failure.length() > 512) failure = failure.substring(0, 512);
                    LogThrottle.error("output_custody_local_unknown", "Local output awaiting inventory verification: request={}, target={}, amount={}, failure={}",
                            sequence, destination, amount, failure, error);
                    notifyState(true);
                    return requested;
                }
                LogThrottle.error("output_custody_verified_callback", "Local output callback failed; actual receipt verified: target={}, requested={}, accepted={}",
                        target, requested, accepted, error);
            }
            clear();
            return accepted;
        } finally {
            executing = false;
        }
    }

    private void validateLocalReceipt(long accepted) {
        if (accepted < 0 || accepted > localRequested) throw new IllegalStateException("Invalid local output receipt: " + accepted);
    }

    private long observedItemAcceptance(ItemStack current) {
        if (ItemStack.matches(localItemBefore, current)) return 0;
        if (!localItemBefore.isEmpty() && !ItemStack.isSameItemSameComponents(localItemBefore, item)
                || !current.isEmpty() && !ItemStack.isSameItemSameComponents(current, item)) {
            throw new IllegalStateException("Local output item identity changed");
        }
        long accepted = (long) current.getCount() - localItemBefore.getCount();
        validateLocalReceipt(accepted);
        return accepted;
    }

    private long observedFluidAcceptance(FluidStack current) {
        if (localFluidBefore.getAmount() == current.getAmount()
                && (localFluidBefore.isEmpty() && current.isEmpty()
                || FluidStack.isSameFluidSameComponents(localFluidBefore, current))) return 0;
        if (!localFluidBefore.isEmpty() && !FluidStack.isSameFluidSameComponents(localFluidBefore, fluid)
                || !current.isEmpty() && !FluidStack.isSameFluidSameComponents(current, fluid)) {
            throw new IllegalStateException("Local output fluid identity changed");
        }
        long accepted = (long) current.getAmount() - localFluidBefore.getAmount();
        validateLocalReceipt(accepted);
        return accepted;
    }

    /** 仅有本机库存前值的未决记录自动核验；旧未知请求仍保管，不猜接收量。 */
    public void recoverLocalItem(Supplier<ItemStack> current, ToIntFunction<ItemStack> insert) {
        if (executing || invalid != null || sourceDebitPending > 0 || sourceDebitUnknown || item.isEmpty()) return;
        if (!resolved) {
            if (!localEvidence) return;
            long accepted;
            try {
                accepted = observedItemAcceptance(current.get());
            } catch (RuntimeException | LinkageError unreadable) {
                // 首次异常和库存前值已经保管；目标仍不可读时不执行或改动原请求。
                return;
            }
            settleObservedLocal(accepted);
        }
        ItemStack offered = recoveredItem();
        if (offered.isEmpty()) return;
        ItemStack before;
        try {
            before = current.get().copy();
        } catch (RuntimeException | LinkageError unreadable) {
            // 读取发生在回填执行之前，已知余量仍留在原记录。
            return;
        }
        localItemBefore = before;
        localFluidBefore = FluidStack.EMPTY;
        recoverLocal(offered.getCount(), () -> observedItemAcceptance(current.get()), () -> insert.applyAsInt(offered));
    }

    public void recoverLocalFluid(Supplier<FluidStack> current, ToIntFunction<FluidStack> insert) {
        if (executing || invalid != null || sourceDebitPending > 0 || sourceDebitUnknown || fluid.isEmpty()) return;
        if (!resolved) {
            if (!localEvidence) return;
            long accepted;
            try {
                accepted = observedFluidAcceptance(current.get());
            } catch (RuntimeException | LinkageError unreadable) {
                // 首次异常和库存前值已经保管；目标仍不可读时不执行或改动原请求。
                return;
            }
            settleObservedLocal(accepted);
        }
        FluidStack offered = recoveredFluid();
        if (offered.isEmpty()) return;
        FluidStack before;
        try {
            before = current.get().copy();
        } catch (RuntimeException | LinkageError unreadable) {
            // 读取发生在回填执行之前，已知余量仍留在原记录。
            return;
        }
        localFluidBefore = before;
        localItemBefore = ItemStack.EMPTY;
        recoverLocal(offered.getAmount(), () -> observedFluidAcceptance(current.get()), () -> insert.applyAsInt(offered));
    }

    private void settleObservedLocal(long accepted) {
        UUID original = sequence;
        String originalTarget = destination;
        long originalAmount = amount;
        amount -= accepted;
        resolved = true;
        failure = "";
        clearLocalEvidence();
        if (amount == 0) reset();
        notifyState(true, original, originalTarget, originalAmount);
        LogThrottle.infoWithCooldown("output_custody_local_recovered", LogThrottle.DEFAULT_COOLDOWN_MS,
                "Local output automatically verified: request={}, target={}, accepted={}, remaining={}", original, originalTarget, accepted, amount);
    }

    private void recoverLocal(long requested, LongSupplier observe, LongSupplier insert) {
        localEvidence = true;
        localRequested = requested;
        resolved = false;
        executing = true;
        try {
            try {
                notifyState(false);
            } catch (RuntimeException | LinkageError error) {
                resolved = true;
                clearLocalEvidence();
                if (failure.isEmpty()) {
                    failure = error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage());
                    if (failure.length() > 512) failure = failure.substring(0, 512);
                }
                return;
            }
            long accepted;
            try {
                accepted = insert.getAsLong();
                validateLocalReceipt(accepted);
            } catch (RuntimeException | LinkageError error) {
                try {
                    accepted = observe.getAsLong();
                } catch (RuntimeException | LinkageError unreadable) {
                    error.addSuppressed(unreadable);
                    resolved = false;
                    if (failure.isEmpty()) {
                        failure = error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage());
                        if (failure.length() > 512) failure = failure.substring(0, 512);
                    }
                    LogThrottle.error("output_custody_local_unknown", "Local recovery awaiting inventory verification: request={}, target={}, amount={}, failure={}",
                            sequence, destination, amount, failure, error);
                    notifyState(true);
                    return;
                }
                LogThrottle.error("output_custody_verified_callback", "Local recovery callback failed; actual receipt verified: request={}, accepted={}",
                        sequence, accepted, error);
            }
            UUID originalRequest = sequence;
            String originalTarget = destination;
            long originalAmount = amount;
            amount -= accepted;
            resolved = true;
            failure = "";
            clearLocalEvidence();
            if (amount == 0) reset();
            notifyState(true, originalRequest, originalTarget, originalAmount);
        } finally {
            executing = false;
        }
    }

    private void clearLocalEvidence() {
        localEvidence = false;
        localRequested = 0;
        localItemBefore = ItemStack.EMPTY;
        localFluidBefore = FluidStack.EMPTY;
    }

    /** 合并交付先登记每台机器的贡献；外部调用前所有来源都必须处于可保存的执行状态。 */
    public boolean beginSharedItem(UUID request, ItemStack template, long requested, String target) {
        if (request == null || template == null || template.isEmpty() || requested <= 0) {
            throw new IllegalArgumentException("Invalid shared item request");
        }
        if (externalOutputBlocked()) return false;
        ItemStack snapshot = template.copyWithCount(1);
        sequence = request;
        item = snapshot;
        fluid = FluidStack.EMPTY;
        amount = requested;
        destination = target;
        failure = "";
        resolved = false;
        receiptKnown = false;
        sourceDebitPending = requested;
        sourceDebitUnknown = false;
        executing = true;
        prepareHandoff();
        return true;
    }

    /** 先保存本来源的已知回执，再由原产物所有者扣量并调用 confirmSourceDebit。 */
    public void sharedItemReceipt(long accepted) {
        if (!executing || item.isEmpty() || sequence == null || sourceDebitUnknown
                || accepted < 0 || accepted > amount) {
            throw new IllegalStateException("Invalid shared item receipt");
        }
        if (accepted == 0) {
            executing = false;
            clear();
            return;
        }
        amount = accepted;
        sourceDebitPending = accepted;
        receiptKnown = true;
        try {
            notifyState(false);
        } finally {
            executing = false;
        }
    }

    /** 未知回执保留本来源的完整贡献，禁止将其当成拒收再次提交。 */
    public void sharedItemUncertain(Throwable error) {
        if (!executing || item.isEmpty() || sequence == null || sourceDebitPending <= 0) {
            throw new IllegalStateException("No shared item handoff to isolate");
        }
        failure = error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage());
        if (failure.length() > 512) failure = failure.substring(0, 512);
        receiptKnown = false;
        try {
            notifyState(false);
        } finally {
            executing = false;
            LogThrottle.error("output_custody_external_unknown", "Shared item output suspended: request={}, target={}, amount={}, failure={}",
                    sequence, destination, amount, failure, error);
        }
    }

    public long handoffFluid(FluidStack template, long requested, String target, LongSupplier insert) {
        if (externalOutputBlocked() || template.isEmpty() || requested <= 0) return 0;
        return handoff(ItemStack.EMPTY, template.copyWithAmount(1), requested, target, insert);
    }

    /** 已有源罐的交付必须等待本地扣源确认；已知接收量不能提前退役。 */
    public long handoffFluidFromTank(FluidStack template, long requested, String target, LongSupplier insert) {
        if (externalOutputBlocked() || template.isEmpty() || requested <= 0) return 0;
        return handoff(template.copyWithAmount(1), requested, target, insert);
    }

    private long handoff(FluidStack template, long requested, String target, LongSupplier insert) {
        fluid = template;
        item = ItemStack.EMPTY;
        amount = requested;
        destination = target;
        failure = "";
        resolved = false;
        receiptKnown = false;
        sourceDebitPending = requested;
        sourceDebitUnknown = false;
        executing = true;
        prepareHandoff();
        UUID clearedRequest = null;
        try {
            long accepted = insert.getAsLong();
            if (accepted < 0 || accepted > requested) {
                throw new IllegalStateException("Invalid output receipt: " + accepted);
            }
            if (accepted == 0) {
                clearedRequest = sequence;
                reset();
            } else {
                amount = accepted;
                sourceDebitPending = accepted;
                receiptKnown = true;
            }
            return accepted;
        } catch (RuntimeException | LinkageError exception) {
            sequence();
            failure = exception.getClass().getSimpleName() + ": " + String.valueOf(exception.getMessage());
            if (failure.length() > 512) failure = failure.substring(0, 512);
            LogThrottle.error("output_custody_external_unknown", "Fluid output suspended: request={}, target={}, amount={}, failure={}",
                    sequence, destination, amount, failure, exception);
            return requested;
        } finally {
            if (sourceDebitPending > 0) sequence();
            executing = false;
            if (amount == 0) notifyState(true, clearedRequest, target, requested);
            else notifyState(true);
        }
    }

    public long sourceDebitPending() { return sourceDebitPending; }
    public boolean sourceDebitUnknown() { return sourceDebitUnknown; }

    public void confirmSourceDebit(long actual) {
        if (executing || sourceDebitUnknown || actual < 0 || actual > sourceDebitPending) {
            throw new IllegalStateException("Invalid source debit confirmation");
        }
        sourceDebitPending -= actual;
        if (sourceDebitPending == 0 && receiptKnown) {
            clear();
            return;
        }
        notifyState(false);
    }

    public void markSourceDebitUnknown(Throwable error) {
        sourceDebitUnknown = true;
        if (failure.isEmpty()) {
            failure = "Source debit unknown: " + error.getClass().getSimpleName() + ": " + error.getMessage();
            if (failure.length() > 512) failure = failure.substring(0, 512);
        }
        sequence();
        try {
            notifyState(false);
        } finally {
            LogThrottle.error("output_custody_source_debit_unknown", "Fluid source debit suspended: request={}, target={}, remaining={}, failure={}",
                    sequence, destination, sourceDebitPending, failure, error);
        }
    }

    /** 管理员核验并完成源端实际扣量后确认；此方法不补扣或再次对外输出。 */
    public void confirmSourceSettled(UUID expected) {
        if (executing || expected == null || !expected.equals(sequence) || sourceDebitPending <= 0) {
            throw new IllegalStateException("Stale source debit confirmation");
        }
        sourceDebitPending = 0;
        sourceDebitUnknown = false;
        if (receiptKnown) clear();
        notifyState(false);
    }

    private long handoff(ItemStack template, FluidStack fluidTemplate, long requested,
            String target, LongSupplier insert) {
        item = template;
        fluid = fluidTemplate;
        amount = requested;
        destination = target;
        failure = "";
        resolved = false;
        executing = true;
        prepareHandoff();
        try {
            long accepted = insert.getAsLong();
            if (accepted < 0 || accepted > requested) throw new IllegalStateException("Invalid output receipt: " + accepted);
            clear();
            return accepted;
        } catch (RuntimeException | LinkageError exception) {
            sequence();
            failure = exception.getClass().getSimpleName() + ": " + String.valueOf(exception.getMessage());
            if (failure.length() > 512) failure = failure.substring(0, 512);
            notifyState(true);
            LogThrottle.error("output_custody_external_unknown", "Output suspended: request={}, target={}, amount={}, failure={}",
                    sequence, destination, amount, failure, exception);
            // 外部可能已写入；把整份请求从原库存移到未决记录，不退回、不再次调用接收方。
            return requested;
        } finally {
            executing = false;
        }
    }

    /** 已抽取但尚未送出的已知余量。回填不足时由本记录保管。 */
    public int quarantineItem(ItemStack stack, String target, RuntimeException error) {
        return handoffItem(stack, target, () -> { throw error; });
    }

    public void retainItem(ItemStack stack, String reason) {
        if (stack.isEmpty()) return;
        if (externalOutputBlocked()) throw new IllegalStateException("Output custody already occupied");
        sequence = UUID.randomUUID();
        item = stack.copyWithCount(1);
        fluid = FluidStack.EMPTY;
        amount = stack.getCount();
        destination = reason;
        failure = "";
        resolved = true;
        notifyState(true);
    }

    public void retainFluid(FluidStack template, long retained, String reason) {
        if (retained <= 0) return;
        if (externalOutputBlocked()) throw new IllegalStateException("Output custody already occupied");
        sequence = UUID.randomUUID();
        item = ItemStack.EMPTY;
        fluid = template.copyWithAmount(1);
        amount = retained;
        destination = reason;
        failure = "";
        resolved = true;
        notifyState(true);
    }

    /** 管理员已在两端核验实际接收量；剩余仍留本机，不能由命令直接发给玩家或生成掉落。 */
    public void resolve(UUID expected, long accepted) {
        if (executing || invalid != null || sourceDebitPending > 0 || sourceDebitUnknown
                || sequence == null || !sequence.equals(expected)
                || resolved || accepted < 0 || accepted > amount) {
            throw new IllegalStateException("Stale or invalid output resolution");
        }
        amount -= accepted;
        resolved = true;
        failure = "";
        if (amount == 0) clear();
        notifyState(false);
    }

    public ItemStack recoveredItem() {
        return invalid == null && sourceDebitPending == 0 && !sourceDebitUnknown && resolved && !item.isEmpty()
                ? item.copyWithCount((int) Math.min(Integer.MAX_VALUE, amount)) : ItemStack.EMPTY;
    }

    public FluidStack recoveredFluid() {
        return invalid == null && sourceDebitPending == 0 && !sourceDebitUnknown && resolved && !fluid.isEmpty() ? fluid.copyWithAmount((int) Math.min(Integer.MAX_VALUE, amount)) : FluidStack.EMPTY;
    }

    /** 已知余量尝试回到本机；回填执行抛错后同样转为未知，不反复补偿。 */
    public void recoverItem(java.util.function.ToIntFunction<ItemStack> insert) {
        ItemStack offered = recoveredItem();
        if (offered.isEmpty()) return;
        int accepted;
        try {
            accepted = insert.applyAsInt(offered);
        } catch (RuntimeException | LinkageError error) {
            resolved = false;
            if (failure.isEmpty()) failure = error.toString();
            notifyState(true);
            LogThrottle.error("output_custody_local_unknown", "Local item recovery suspended: request={}, target={}, amount={}, failure={}",
                    sequence, destination, amount, failure, error);
            return;
        }
        consumeRecovered(accepted);
    }

    public void recoverFluid(java.util.function.ToIntFunction<FluidStack> insert) {
        FluidStack offered = recoveredFluid();
        if (offered.isEmpty()) return;
        int accepted;
        try {
            accepted = insert.applyAsInt(offered);
        } catch (RuntimeException | LinkageError error) {
            resolved = false;
            if (failure.isEmpty()) failure = error.toString();
            notifyState(true);
            LogThrottle.error("output_custody_local_unknown", "Local fluid recovery suspended: request={}, target={}, amount={}, failure={}",
                    sequence, destination, amount, failure, error);
            return;
        }
        consumeRecovered(accepted);
    }

    public void retryDecode(HolderLookup.Provider provider) {
        if (invalid != null) load(provider, invalid.copy());
        for (OutputCustody entry : local.values()) entry.retryDecode(provider);
    }

    public void consumeRecovered(long consumed) {
        if (!resolved || consumed < 0 || consumed > amount) throw new IllegalStateException("Invalid recovered debit");
        UUID originalRequest = sequence;
        String originalTarget = destination;
        long originalAmount = amount;
        amount -= consumed;
        if (amount == 0) reset();
        notifyState(true, originalRequest, originalTarget, originalAmount);
    }

    private void prepareHandoff() {
        try {
            notifyState(false);
        } catch (RuntimeException | LinkageError error) {
            // 接收方尚未被调用，源资产仍由调用方持有。
            executing = false;
            reset();
            throw error;
        }
    }

    /** 提交后通知失败只保留首因；准备失败继续沿原调用边界中止未执行的交接。 */
    private void notifyState(boolean committed) {
        notifyState(committed, sequence, destination, amount);
    }

    private void notifyState(boolean committed, UUID request, String target, long notifiedAmount) {
        try {
            changed.run();
        } catch (RuntimeException | LinkageError error) {
            if (notificationFailure == null) {
                notificationFailure = error;
                notificationRequest = request;
                notificationTarget = target;
                notificationAmount = notifiedAmount;
                LogThrottle.error("output_custody_notification", "Output state notification failed: request={}, target={}, amount={}, failure={}",
                        request, target, notifiedAmount, failure, error);
            }
            if (!committed) throw error;
            return;
        }
        if (notificationFailure != null) {
            Throwable recovered = notificationFailure;
            notificationFailure = null;
            LogThrottle.infoWithCooldown("output_custody_notification_recovered", LogThrottle.DEFAULT_COOLDOWN_MS,
                    "Output state notification recovered: request={}, target={}, amount={}, cause={}",
                    notificationRequest, notificationTarget, notificationAmount, recovered.toString());
            notificationRequest = null;
            notificationTarget = null;
            notificationAmount = 0;
        }
    }

    private void clear() {
        UUID originalRequest = sequence;
        String originalTarget = destination;
        long originalAmount = amount;
        reset();
        notifyState(true, originalRequest, originalTarget, originalAmount);
    }

    private void reset() {
        sequence = null;
        item = ItemStack.EMPTY;
        fluid = FluidStack.EMPTY;
        amount = 0;
        failure = "";
        destination = "";
        resolved = false;
        sourceDebitPending = 0;
        sourceDebitUnknown = false;
        receiptKnown = false;
        clearLocalEvidence();
    }

    public CompoundTag save(HolderLookup.Provider provider) {
        if (invalid != null) return invalid.copy();
        ListTag records = new ListTag();
        for (var entry : local.entrySet()) {
            CompoundTag saved = entry.getValue().save(provider);
            if (saved.isEmpty()) continue;
            CompoundTag record = new CompoundTag();
            int key = entry.getKey();
            record.putString("location", key >= 0 ? "item:" + key : "fluid:" + (-key - 1));
            record.put("request", saved);
            records.add(record);
        }
        CompoundTag external = saveRequest(provider);
        if (records.isEmpty()) return external;
        CompoundTag tag = new CompoundTag();
        tag.putInt("version", 3);
        tag.put("external", external);
        tag.put("local", records);
        return tag;
    }

    private CompoundTag saveRequest(HolderLookup.Provider provider) {
        if (invalid != null) return invalid.copy();
        var tag = new CompoundTag();
        if (sequence() == null) return tag;
        int version = localEvidence ? 4 : sourceDebitPending > 0 || sourceDebitUnknown ? 2 : 1;
        tag.putInt("version", version);
        tag.putUUID("sequence", sequence);
        tag.putLong("amount", amount);
        tag.putString("destination", destination);
        tag.putString("failure", failure);
        tag.putBoolean("resolved", resolved);
        if (sourceDebitPending > 0 || sourceDebitUnknown) {
            tag.putLong("source_debit_pending", sourceDebitPending);
            tag.putBoolean("source_debit_unknown", sourceDebitUnknown);
            tag.putBoolean("receipt_known", receiptKnown);
        }
        if (!item.isEmpty()) tag.put("item", item.save(provider));
        if (!fluid.isEmpty()) tag.put("fluid", fluid.save(provider));
        if (localEvidence) {
            tag.putLong("local_requested", localRequested);
            CompoundTag before = new CompoundTag();
            if (!item.isEmpty()) {
                if (!localItemBefore.isEmpty()) before = (CompoundTag) localItemBefore.copyWithCount(1).save(provider);
                tag.putInt("local_before_count", localItemBefore.getCount());
            }
            if (!fluid.isEmpty() && !localFluidBefore.isEmpty()) before = (CompoundTag) localFluidBefore.save(provider);
            tag.put("local_before", before);
        }
        return tag;
    }

    /** 外层字段类型错误时保留原始标签，防止 getCompound 将其隐式替换为空记录。 */
    public void loadRaw(HolderLookup.Provider provider, Tag raw) {
        if (raw == null || raw instanceof CompoundTag) {
            load(provider, raw == null ? new CompoundTag() : (CompoundTag) raw);
        } else {
            CompoundTag preserved = new CompoundTag();
            preserved.put("invalid_data", raw.copy());
            load(provider, preserved);
        }
    }

    public void load(HolderLookup.Provider provider, CompoundTag tag) {
        if (executing || local.values().stream().anyMatch(entry -> entry.executing)) {
            throw new IllegalStateException("Cannot replace custody during an output handoff");
        }
        local.clear();
        if (tag.contains("version", Tag.TAG_INT) && tag.getInt("version") == 3) {
            try {
                if (!tag.getAllKeys().equals(Set.of("version", "external", "local"))
                        || !tag.contains("external", Tag.TAG_COMPOUND)
                        || !(tag.get("local") instanceof ListTag entries)
                        || !entries.isEmpty() && entries.getElementType() != Tag.TAG_COMPOUND) {
                    throw new IllegalArgumentException("Invalid local output custody");
                }
                loadRequest(provider, tag.getCompound("external"));
                if (invalid != null) throw new IllegalArgumentException("Invalid external output request");
                for (Tag value : entries) {
                    CompoundTag entry = (CompoundTag) value;
                    if (!entry.getAllKeys().equals(Set.of("location", "request"))
                            || !entry.contains("location", Tag.TAG_STRING)
                            || !entry.contains("request", Tag.TAG_COMPOUND)) {
                        throw new IllegalArgumentException("Invalid local output request");
                    }
                    String location = entry.getString("location");
                    String[] parts = location.split(":");
                    if (parts.length != 2 || !Set.of("item", "fluid").contains(parts[0])
                            || Integer.parseInt(parts[1]) < 0) {
                        throw new IllegalArgumentException("Invalid local output location");
                    }
                    int index = Integer.parseInt(parts[1]);
                    int key = parts[0].equals("item") ? index : -index - 1;
                    if (local.containsKey(key)) throw new IllegalArgumentException("Duplicate local output position");
                    OutputCustody record = new OutputCustody(changed);
                    record.loadRequest(provider, entry.getCompound("request"));
                    if (!record.ownBlocked()) throw new IllegalArgumentException("Empty local output request");
                    local.put(key, record);
                }
            } catch (RuntimeException | LinkageError error) {
                invalid = tag.copy();
                failure = "Invalid output custody; original data preserved";
                LogThrottle.error("output_custody_restore", "Output custody data could not be restored", error);
            }
            return;
        }
        loadRequest(provider, tag);
    }

    private void loadRequest(HolderLookup.Provider provider, CompoundTag tag) {
        if (executing) throw new IllegalStateException("Cannot replace custody during an output handoff");
        invalid = null;
        clear();
        if (tag.isEmpty()) return;
        try {
            if (!Set.of("version", "sequence", "amount", "destination", "failure", "resolved", "item", "fluid",
                    "source_debit_pending", "source_debit_unknown", "receipt_known", "local_requested", "local_before", "local_before_count")
                    .containsAll(tag.getAllKeys()) || !tag.contains("version", Tag.TAG_INT)
                    || !Set.of(1, 2, 4).contains(tag.getInt("version"))
                    || !tag.hasUUID("sequence") || !tag.contains("amount", Tag.TAG_LONG) || tag.getLong("amount") <= 0
                    || !tag.contains("destination", Tag.TAG_STRING) || !tag.contains("failure", Tag.TAG_STRING)
                    || !tag.contains("resolved", Tag.TAG_BYTE) || tag.getByte("resolved") < 0 || tag.getByte("resolved") > 1
                    || tag.contains("item") == tag.contains("fluid")
                    || tag.contains("item") && !tag.contains("item", Tag.TAG_COMPOUND)
                    || tag.contains("fluid") && !tag.contains("fluid", Tag.TAG_COMPOUND)) {
                throw new IllegalArgumentException("Invalid custody header");
            }
            var loadedItem = tag.contains("item") ? ItemStack.parse(provider, tag.getCompound("item")).orElseThrow() : ItemStack.EMPTY;
            var loadedFluid = tag.contains("fluid") ? FluidStack.parseOptional(provider, tag.getCompound("fluid")) : FluidStack.EMPTY;
            if (loadedItem.isEmpty() == loadedFluid.isEmpty()) {
                throw new IllegalArgumentException("Invalid custody asset");
            }
            item = loadedItem;
            fluid = loadedFluid;
            amount = tag.getLong("amount");
            sequence = tag.getUUID("sequence");
            destination = tag.getString("destination");
            failure = tag.getString("failure");
            resolved = tag.getBoolean("resolved");
            if (tag.getInt("version") == 2) {
                if (!tag.contains("source_debit_pending", Tag.TAG_LONG)
                        || tag.getLong("source_debit_pending") <= 0
                        || tag.getLong("source_debit_pending") > amount
                        || !tag.contains("source_debit_unknown", Tag.TAG_BYTE)
                        || tag.getByte("source_debit_unknown") < 0 || tag.getByte("source_debit_unknown") > 1
                        || !tag.contains("receipt_known", Tag.TAG_BYTE)
                        || tag.getByte("receipt_known") < 0 || tag.getByte("receipt_known") > 1 || resolved) {
                    throw new IllegalArgumentException("Invalid source debit record");
                }
                sourceDebitPending = tag.getLong("source_debit_pending");
                sourceDebitUnknown = tag.getBoolean("source_debit_unknown");
                receiptKnown = tag.getBoolean("receipt_known");
            } else if (tag.contains("source_debit_pending") || tag.contains("source_debit_unknown")
                    || tag.contains("receipt_known")) {
                throw new IllegalArgumentException("Unexpected source debit fields");
            }
            if (tag.getInt("version") == 4) {
                if (!tag.contains("local_requested", Tag.TAG_LONG) || tag.getLong("local_requested") <= 0
                        || tag.getLong("local_requested") > amount || !tag.contains("local_before", Tag.TAG_COMPOUND) || resolved) {
                    throw new IllegalArgumentException("Invalid local inventory evidence");
                }
                localRequested = tag.getLong("local_requested");
                CompoundTag before = tag.getCompound("local_before");
                if (!item.isEmpty()) {
                    if (tag.contains("local_before_count")) {
                        if (!tag.contains("local_before_count", Tag.TAG_INT) || tag.getInt("local_before_count") < 0
                                || (tag.getInt("local_before_count") == 0) != before.isEmpty()) {
                            throw new IllegalArgumentException("Invalid local item inventory count");
                        }
                        if (!before.isEmpty()) {
                            ItemStack identity = ItemStack.parse(provider, before).orElseThrow();
                            if (identity.getCount() != 1) throw new IllegalArgumentException("Invalid local item inventory identity");
                            localItemBefore = identity.copyWithCount(tag.getInt("local_before_count"));
                        }
                    } else if (!before.isEmpty()) {
                        localItemBefore = ItemStack.parse(provider, before).orElseThrow();
                    }
                } else {
                    if (tag.contains("local_before_count")) throw new IllegalArgumentException("Item inventory count in a fluid request");
                    if (!before.isEmpty()) localFluidBefore = FluidStack.parse(provider, before).orElseThrow();
                }
                localEvidence = true;
            } else if (tag.contains("local_requested") || tag.contains("local_before") || tag.contains("local_before_count")) {
                throw new IllegalArgumentException("Local inventory evidence in an older request");
            }
        } catch (RuntimeException | LinkageError exception) {
            invalid = tag.copy();
            failure = "Invalid output custody; original data preserved";
            LogThrottle.error("output_custody_restore", "Output custody data could not be restored", exception);
        }
    }
}
