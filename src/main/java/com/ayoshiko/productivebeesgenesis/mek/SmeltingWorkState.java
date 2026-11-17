package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.mixin.accessor.BasicEnergyContainerAccessor;
import com.ayoshiko.productivebeesgenesis.mixin.accessor.BasicInventorySlotAccessor;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 熔炼完成时唯一拥有尚未扣除的输入、已付产物和共享费用；未知结果按原车道保管。 */
final class SmeltingWorkState {
    static final String NBT_KEY = "productivebeesgenesis_smelting_work";

    private static final class Completion {
        final UUID request;
        final ItemStack input;
        final ItemStack output;
        int inputPending;
        long outputPending;
        boolean unknown;
        String phase;
        String failure;

        Completion(UUID request, ItemStack input, int inputs, ItemStack output, long outputs,
                boolean unknown, String phase, String failure) {
            this.request = request;
            this.input = input.copyWithCount(1);
            this.output = output.copyWithCount(1);
            inputPending = inputs;
            outputPending = outputs;
            this.unknown = unknown;
            this.phase = phase;
            this.failure = failure;
        }
    }

    private final PbRecipeContext context;
    private final PbRecipeProcessor processor;
    private final Completion[] lanes;
    private final Tag[] invalidLanes;
    private Tag invalid;
    private Tag invalidEnergy;
    private boolean retryInvalidOnTick;
    private boolean inputLocks;
    private boolean outputLocks;
    private long energyPending;
    private UUID energyRequest;
    private boolean energyUnknown;
    private String energyFailure = "";
    private long lastEnergyRetryTick = Long.MIN_VALUE;

    SmeltingWorkState(PbRecipeContext context, PbRecipeProcessor processor, int processes) {
        this.context = context;
        this.processor = processor;
        lanes = new Completion[processes];
        invalidLanes = new Tag[processes];
    }

    boolean hasLane(int lane) {
        return lanes[lane] != null || invalidLanes[lane] != null;
    }

    boolean blocked(int lane) {
        return sharedEnergyBlocked() || hasLane(lane);
    }

    boolean hasInputLocks() {
        return inputLocks;
    }

    boolean hasOutputLocks() {
        return outputLocks;
    }

    boolean inputLocked(int lane) {
        Completion completion = lanes[lane];
        return invalid != null || invalidLanes[lane] != null || completion != null
                && (completion.inputPending > 0 || completion.unknown && !"output".equals(completion.phase));
    }

    boolean outputLocked(int lane) {
        Completion completion = lanes[lane];
        return invalid != null || invalidLanes[lane] != null || completion != null
                && completion.unknown && !"input".equals(completion.phase);
    }

    boolean hasUnresolved() {
        if (sharedEnergyBlocked()) return true;
        for (int lane = 0; lane < lanes.length; lane++) {
            if (hasLane(lane)) return true;
        }
        return false;
    }

    boolean sharedEnergyBlocked() {
        return invalid != null || invalidEnergy != null || energyPending > 0 || energyUnknown;
    }

    PbRecipeProcessor.LaneState laneState(int lane) {
        if (invalid != null || invalidEnergy != null || invalidLanes[lane] != null) {
            return PbRecipeProcessor.LaneState.INPUT_UNCERTAIN;
        }
        if (energyUnknown) return PbRecipeProcessor.LaneState.SHARED_PAYMENT_UNCERTAIN;
        if (energyPending > 0) return PbRecipeProcessor.LaneState.SMELTING_PENDING;
        Completion completion = lanes[lane];
        if (completion == null) return null;
        if (!completion.unknown) return PbRecipeProcessor.LaneState.SMELTING_PENDING;
        return switch (completion.phase) {
            case "input" -> PbRecipeProcessor.LaneState.INPUT_UNCERTAIN;
            case "output" -> PbRecipeProcessor.LaneState.OUTPUT_UNCERTAIN;
            default -> PbRecipeProcessor.LaneState.PAYMENT_UNCERTAIN;
        };
    }

    boolean hasInvalidData() {
        if (invalid != null || invalidEnergy != null) return true;
        for (Tag entry : invalidLanes) {
            if (entry != null) return true;
        }
        return false;
    }

    void retryInvalidOnFirstTick(HolderLookup.Provider provider) {
        if (!retryInvalidOnTick) return;
        retryInvalidData(provider);
    }

    void retryInvalidData(HolderLookup.Provider provider) {
        if (!hasInvalidData()) return;
        CompoundTag retained = new CompoundTag();
        save(retained, provider);
        load(retained, provider);
        retryInvalidOnTick = false;
        for (int lane = 0; lane < lanes.length; lane++) {
            if (invalid == null) processor.markLaneRecovery(lane, "熔炼原始数据解码");
            if (invalidEnergy == null) processor.markLaneRecovery(lane, "熔炼共享费用数据解码");
            if (invalidLanes[lane] == null) processor.markLaneRecovery(lane, "熔炼车道数据解码");
        }
        notifyChanged();
        for (int lane = 0; lane < lanes.length; lane++) processor.finishLaneAttempt(lane);
    }

    void retain(int lane, ItemStack input, int inputs, ItemStack output, long outputs,
            boolean unknown, String phase, Throwable failure) {
        lanes[lane] = new Completion(UUID.randomUUID(), input, inputs, output, outputs, unknown, phase, message(failure));
        notifyChanged();
        processor.reportLaneFailure(lane, phase, failure);
    }

    void retainUnknownProcess(int lane, String phase, Throwable failure) {
        if (hasLane(lane)) return;
        retain(lane, ItemStack.EMPTY, 0, ItemStack.EMPTY, 0, true, phase, failure);
    }

    /** 上次真实增减已可读时只补齐余量；不再运行原缓存、抽样或重复付费。 */
    boolean retryCompletion(int lane) {
        if (invalidLanes[lane] != null) return false;
        Completion completion = lanes[lane];
        if (completion == null) return !sharedEnergyBlocked();
        if (completion.unknown || sharedEnergyBlocked()) return false;
        if (completion.inputPending > 0) {
            var slot = context.inputSlot(lane);
            ItemStack before = slot.getStack();
            if (!ItemStack.isSameItemSameComponents(before, completion.input)
                    || before.getCount() < completion.inputPending) return false;
            int beforeCount = before.getCount();
            int requested = completion.inputPending;
            try {
                int removed = slot.shrinkStack(requested, Action.EXECUTE);
                completion.inputPending -= removed;
            } catch (RuntimeException | LinkageError failure) {
                try {
                    int removed = removed(beforeCount, ((BasicInventorySlotAccessor) slot).productivebeesgenesis$getCurrent(), completion.input);
                    if (removed < 0 || removed > requested) throw new IllegalStateException("Smelting input changed during debit");
                    completion.inputPending -= removed;
                } catch (RuntimeException | LinkageError unknown) {
                    failure.addSuppressed(unknown);
                    completion.unknown = true;
                }
                completion.phase = "input";
                completion.failure = message(failure);
                notifyChanged();
                processor.reportLaneFailure(lane, "熔炼已付输入收尾", failure);
                return false;
            }
            notifyChanged();
            if (completion.inputPending > 0) return false;
        }
        if (completion.outputPending > 0) {
            var slot = context.primaryOutputSlot(lane);
            ItemStack before = slot.getStack();
            if (!before.isEmpty() && !ItemStack.isSameItemSameComponents(before, completion.output)) return false;
            int beforeCount = before.getCount();
            int requested = (int) Math.min(Integer.MAX_VALUE, completion.outputPending);
            try {
                ItemStack remainder = slot.insertItem(completion.output.copyWithCount(requested),
                        Action.EXECUTE, AutomationType.INTERNAL);
                completion.outputPending -= requested - remainder.getCount();
            } catch (RuntimeException | LinkageError failure) {
                try {
                    long accepted = accepted(beforeCount, ((BasicInventorySlotAccessor) slot).productivebeesgenesis$getCurrent(), completion.output);
                    if (accepted < 0 || accepted > requested) throw new IllegalStateException("Smelting output changed during insertion");
                    completion.outputPending -= accepted;
                } catch (RuntimeException | LinkageError unknown) {
                    failure.addSuppressed(unknown);
                    completion.unknown = true;
                }
                completion.phase = "output";
                completion.failure = message(failure);
                if (!completion.unknown && completion.outputPending == 0) lanes[lane] = null;
                notifyChanged();
                processor.reportLaneFailure(lane, "熔炼已付产物收尾", failure);
                return lanes[lane] == null;
            }
            notifyChanged();
            if (completion.outputPending > 0) return false;
        }
        lanes[lane] = null;
        processor.markLaneRecovery(lane, "input");
        processor.markLaneRecovery(lane, "output");
        processor.markLaneRecovery(lane, "熔炼已付输入收尾");
        processor.markLaneRecovery(lane, "熔炼已付产物收尾");
        notifyChanged();
        processor.finishLaneAttempt(lane);
        return true;
    }

    void retainEnergy(long remaining, boolean unknown, Throwable failure) {
        energyPending = remaining;
        energyUnknown = unknown;
        if (energyRequest == null) energyRequest = UUID.randomUUID();
        energyFailure = message(failure);
        notifyChanged();
        for (int lane = 0; lane < lanes.length; lane++) {
            processor.reportLaneFailure(lane, "熔炼共享费用收尾", failure);
        }
    }

    /** 共享余额每个真实 tick 只尝试一次；未知扣取结果保留同一请求，不能重扣原账量。 */
    boolean retryEnergy() {
        if (invalid != null || invalidEnergy != null || energyUnknown) return false;
        if (energyPending == 0) return true;
        long tick = context.level().getGameTime();
        if (lastEnergyRetryTick == tick) return false;
        lastEnergyRetryTick = tick;
        var container = context.energyContainer();
        long before = container.getEnergy();
        long requested = Math.min(before, energyPending);
        if (requested == 0) return false;
        try {
            long removed = container.extract(requested, Action.EXECUTE, AutomationType.INTERNAL);
            energyPending -= removed;
        } catch (RuntimeException | LinkageError failure) {
            try {
                long removed = before - ((BasicEnergyContainerAccessor) container).productivebeesgenesis$getStored();
                if (removed < 0 || removed > requested) throw new IllegalStateException("Smelting energy changed during debit");
                energyPending -= removed;
            } catch (RuntimeException | LinkageError unknown) {
                failure.addSuppressed(unknown);
                energyUnknown = true;
            }
            energyFailure = message(failure);
            boolean settled = !energyUnknown && energyPending == 0;
            if (settled) clearEnergy();
            notifyChanged();
            for (int lane = 0; lane < lanes.length; lane++) {
                processor.reportLaneFailure(lane, settled ? "熔炼共享费用通知已结算" : "熔炼共享费用恢复", failure);
            }
            return settled;
        }
        if (energyPending == 0) {
            clearEnergy();
            for (int lane = 0; lane < lanes.length; lane++) {
                processor.markLaneRecovery(lane, "熔炼共享费用收尾");
                processor.markLaneRecovery(lane, "熔炼共享费用恢复");
            }
        }
        notifyChanged();
        if (energyPending == 0) {
            for (int lane = 0; lane < lanes.length; lane++) processor.finishLaneAttempt(lane);
        }
        return energyPending == 0;
    }

    private void clearEnergy() {
        energyPending = 0;
        energyRequest = null;
        energyUnknown = false;
        energyFailure = "";
    }

    List<PbRecipeProcessor.SmeltingStatus> status() {
        List<PbRecipeProcessor.SmeltingStatus> result = new ArrayList<>();
        if (energyRequest != null) {
            result.add(new PbRecipeProcessor.SmeltingStatus(-1, energyRequest,
                    energyUnknown ? "energy_unknown" : "energy_pending", energyPending, energyFailure));
        }
        for (int lane = 0; lane < lanes.length; lane++) {
            Completion completion = lanes[lane];
            if (completion != null) {
                result.add(new PbRecipeProcessor.SmeltingStatus(lane, completion.request,
                        completion.phase + (completion.unknown ? "_unknown" : "_pending"), 0, completion.failure));
            }
        }
        return List.copyOf(result);
    }

    /** 管理员已核验真实本地资产及费用后解除未知请求；不发放快照、不推测零接收。 */
    void confirmSettled(UUID request) {
        if (request.equals(energyRequest) && energyUnknown) {
            clearEnergy();
            for (int lane = 0; lane < lanes.length; lane++) {
                processor.markLaneRecovery(lane, "熔炼共享费用收尾");
                processor.markLaneRecovery(lane, "熔炼共享费用恢复");
            }
            notifyChanged();
            for (int lane = 0; lane < lanes.length; lane++) processor.finishLaneAttempt(lane);
            return;
        }
        for (int lane = 0; lane < lanes.length; lane++) {
            Completion completion = lanes[lane];
            if (completion != null && request.equals(completion.request) && completion.unknown) {
                lanes[lane] = null;
                processor.markLaneRecovery(lane, "input");
                processor.markLaneRecovery(lane, "output");
                processor.markLaneRecovery(lane, "processing");
                processor.markLaneRecovery(lane, "熔炼已付输入收尾");
                processor.markLaneRecovery(lane, "熔炼已付产物收尾");
                notifyChanged();
                processor.finishLaneAttempt(lane);
                return;
            }
        }
        throw new IllegalStateException("Stale or known smelting request");
    }

    void save(CompoundTag destination, HolderLookup.Provider provider) {
        if (invalid != null) {
            destination.put(NBT_KEY, invalid.copy());
            return;
        }
        if (!hasUnresolved()) {
            destination.remove(NBT_KEY);
            return;
        }
        CompoundTag root = new CompoundTag();
        root.putInt("version", 1);
        ListTag entries = new ListTag();
        for (int lane = 0; lane < lanes.length; lane++) {
            if (invalidLanes[lane] != null) {
                entries.add(invalidLanes[lane].copy());
                continue;
            }
            Completion completion = lanes[lane];
            if (completion == null) continue;
            CompoundTag entry = new CompoundTag();
            entry.putInt("lane", lane);
            entry.putUUID("request", completion.request);
            entry.putInt("input_pending", completion.inputPending);
            entry.putLong("output_pending", completion.outputPending);
            entry.putBoolean("unknown", completion.unknown);
            entry.putString("phase", completion.phase);
            entry.putString("failure", completion.failure);
            if (!completion.input.isEmpty()) entry.put("input", completion.input.save(provider));
            if (!completion.output.isEmpty()) entry.put("output", completion.output.save(provider));
            entries.add(entry);
        }
        root.put("lanes", entries);
        if (invalidEnergy != null) {
            root.put("energy", invalidEnergy.copy());
        } else if (energyPending > 0 || energyUnknown) {
            CompoundTag energy = new CompoundTag();
            energy.putUUID("request", energyRequest);
            energy.putLong("pending", energyPending);
            energy.putBoolean("unknown", energyUnknown);
            energy.putString("failure", energyFailure);
            root.put("energy", energy);
        }
        destination.put(NBT_KEY, root);
    }

    void load(CompoundTag source, HolderLookup.Provider provider) {
        Arrays.fill(lanes, null);
        Arrays.fill(invalidLanes, null);
        invalid = null;
        invalidEnergy = null;
        retryInvalidOnTick = false;
        inputLocks = false;
        outputLocks = false;
        lastEnergyRetryTick = Long.MIN_VALUE;
        clearEnergy();
        Tag raw = source.get(NBT_KEY);
        if (raw == null) return;
        try {
            if (!(raw instanceof CompoundTag root) || !Set.of("version", "lanes", "energy").containsAll(root.getAllKeys())
                    || !root.contains("version", Tag.TAG_INT) || root.getInt("version") != 1
                    || !(root.get("lanes") instanceof ListTag entries)
                    || !entries.isEmpty() && entries.getElementType() != Tag.TAG_COMPOUND
                    || entries.size() > lanes.length) throw new IllegalArgumentException("Invalid smelting work root");
            Set<UUID> requests = new HashSet<>();
            for (Tag value : entries) {
                CompoundTag entry = (CompoundTag) value;
                if (!entry.contains("lane", Tag.TAG_INT)) throw new IllegalArgumentException("Missing smelting lane");
                int lane = entry.getInt("lane");
                if (lane < 0 || lane >= lanes.length || hasLane(lane)) throw new IllegalArgumentException("Duplicate smelting lane");
                try {
                    Completion completion = readCompletion(entry, provider);
                    if (!requests.add(completion.request)) throw new IllegalArgumentException("Duplicate smelting request");
                    lanes[lane] = completion;
                } catch (RuntimeException | LinkageError failure) {
                    invalidLanes[lane] = entry.copy();
                    processor.reportLaneFailure(lane, "熔炼车道数据解码", failure);
                }
            }
            if (root.contains("energy")) {
                try {
                    if (!(root.get("energy") instanceof CompoundTag energy)
                            || !Set.of("request", "pending", "unknown", "failure").equals(energy.getAllKeys())
                            || !energy.hasUUID("request") || !energy.contains("pending", Tag.TAG_LONG) || energy.getLong("pending") < 0
                            || !energy.contains("unknown", Tag.TAG_BYTE) || energy.getByte("unknown") < 0 || energy.getByte("unknown") > 1
                            || energy.getLong("pending") == 0 && !energy.getBoolean("unknown")
                            || !energy.contains("failure", Tag.TAG_STRING) || energy.getString("failure").length() > 512
                            || !requests.add(energy.getUUID("request"))) throw new IllegalArgumentException("Invalid smelting energy");
                    energyPending = energy.getLong("pending");
                    energyRequest = energy.getUUID("request");
                    energyUnknown = energy.getBoolean("unknown");
                    energyFailure = energy.getString("failure");
                } catch (RuntimeException | LinkageError failure) {
                    invalidEnergy = root.get("energy").copy();
                    processor.reportLaneFailure(0, "熔炼共享费用数据解码", failure);
                }
            }
        } catch (RuntimeException | LinkageError failure) {
            invalid = raw.copy();
            processor.reportLaneFailure(0, "熔炼原始数据解码", failure);
        }
        retryInvalidOnTick = hasInvalidData();
        refreshLocks();
    }

    private static Completion readCompletion(CompoundTag entry, HolderLookup.Provider provider) {
        if (!Set.of("lane", "request", "input_pending", "output_pending", "unknown", "phase", "failure", "input", "output")
                .containsAll(entry.getAllKeys())
                || !entry.hasUUID("request")
                || !entry.contains("input_pending", Tag.TAG_INT) || entry.getInt("input_pending") < 0
                || !entry.contains("output_pending", Tag.TAG_LONG) || entry.getLong("output_pending") < 0
                || !entry.contains("unknown", Tag.TAG_BYTE) || entry.getByte("unknown") < 0 || entry.getByte("unknown") > 1
                || !entry.contains("phase", Tag.TAG_STRING) || !Set.of("input", "output", "processing").contains(entry.getString("phase"))
                || !entry.contains("failure", Tag.TAG_STRING) || entry.getString("failure").length() > 512) {
            throw new IllegalArgumentException("Invalid smelting lane");
        }
        ItemStack input = readStack(entry, "input", provider);
        ItemStack output = readStack(entry, "output", provider);
        int inputs = entry.getInt("input_pending");
        long outputs = entry.getLong("output_pending");
        boolean unknown = entry.getBoolean("unknown");
        if (inputs > 0 && input.isEmpty() || outputs > 0 && output.isEmpty()
                || inputs == 0 && outputs == 0 && !unknown) throw new IllegalArgumentException("Missing smelting assets");
        return new Completion(entry.getUUID("request"), input, inputs, output, outputs,
                unknown, entry.getString("phase"), entry.getString("failure"));
    }

    private static ItemStack readStack(CompoundTag entry, String field, HolderLookup.Provider provider) {
        if (!entry.contains(field)) return ItemStack.EMPTY;
        if (!(entry.get(field) instanceof CompoundTag stack)) throw new IllegalArgumentException("Invalid smelting stack");
        ItemStack parsed = ItemStack.parse(provider, stack).orElseThrow();
        if (parsed.isEmpty() || parsed.getCount() != 1) throw new IllegalArgumentException("Invalid smelting template");
        return parsed;
    }

    static int removed(int before, ItemStack after, ItemStack template) {
        if (after.isEmpty()) return before;
        return ItemStack.isSameItemSameComponents(after, template) ? before - after.getCount() : -1;
    }

    static long accepted(int before, ItemStack after, ItemStack template) {
        if (after.isEmpty()) return -before;
        return ItemStack.isSameItemSameComponents(after, template) ? (long) after.getCount() - before : -1;
    }

    private static String message(Throwable failure) {
        String message = failure.getClass().getSimpleName() + ": " + failure.getMessage();
        return message.substring(0, Math.min(512, message.length()));
    }

    private void refreshLocks() {
        inputLocks = invalid != null;
        outputLocks = invalid != null;
        for (int lane = 0; lane < lanes.length && (!inputLocks || !outputLocks); lane++) {
            inputLocks |= inputLocked(lane);
            outputLocks |= outputLocked(lane);
        }
    }

    private void notifyChanged() {
        refreshLocks();
        try {
            context.productivebeesgenesis$markForSave();
            processor.markLaneRecovery(0, "熔炼保存通知");
        } catch (RuntimeException | LinkageError failure) {
            processor.reportLaneFailure(0, "熔炼保存通知", failure);
        }
    }
}
