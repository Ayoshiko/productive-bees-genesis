package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 普通生产的唯一已付所有者。未开始周期、进行中的组和冻结结果分别保存。服务器线程独占。 */
public final class ApiaryProductionState {
    final int[] counts;
    final BeeSlot[] bees;
    // 蜜蜂数据沿 setBeeData 替换；每槽保留一份封闭快照，跨已结清批次复用。
    private record BeeSnapshot(CompoundTag source, CompoundTag data) {}
    private final BeeSnapshot[] beeSnapshots;
    private final Runnable changed;
    private final List<ItemStack> items = new ArrayList<>();
    private FluidStack fluid = FluidStack.EMPTY;
    private long fluidAmount;
    private UUID request;
    private int[] activeSlots = new int[0];
    private String phase = "";
    private String failure = "";
    private CompoundTag localContext;
    private CompoundTag invalid;
    private ApiaryRetainedCycles retained;
    private boolean activeRetained;
    private long frozenVersion;
    private final boolean[] conversionCompleted;
    private final boolean[] myriadCompleted;
    private final List<HeldGroup> heldGroups = new ArrayList<>();

    private record HeldGroup(UUID request, int[] slots, boolean retained, String phase, String failure) {}

    ApiaryProductionState(int slots, Runnable changed) {
        this.changed = changed;
        counts = new int[slots];
        conversionCompleted = new boolean[slots];
        myriadCompleted = new boolean[slots];
        bees = new BeeSlot[slots];
        beeSnapshots = new BeeSnapshot[slots];
        for (int i = 0; i < slots; i++) bees[i] = new BeeSlot();
    }

    void prepareBeeSnapshot(int slot, BeeSlot bee) {
        CompoundTag source = bee.getBeeData();
        BeeSnapshot cached = beeSnapshots[slot];
        if (cached == null || cached.source() != source) {
            beeSnapshots[slot] = new BeeSnapshot(source, source.copy());
        }
    }

    void capture(int slot, BeeSlot bee) {
        if (counts[slot] > 0 && bees[slot].isEmpty()) {
            prepareBeeSnapshot(slot, bee);
            BeeSnapshot cached = beeSnapshots[slot];
            bees[slot].setProductionSnapshot(cached.data(), bee);
            changed.run();
        }
    }

    void releaseEmptyBee(int slot) {
        if (counts[slot] == 0) beeSnapshots[slot] = null;
    }

    boolean canAdvance(int slot, BeeSlot bee) {
        if (slotSuspended(slot) || retryableGeneration(slot)) return false;
        if (counts[slot] == 0) return true;
        CompoundTag source = bee.getBeeData();
        CompoundTag held = bees[slot].getBeeData();
        BeeSnapshot cached = beeSnapshots[slot];
        if (cached != null && cached.source() == source && cached.data() == held) return true;
        if (held == null || !held.equals(source)) return false;
        beeSnapshots[slot] = new BeeSnapshot(source, held);
        return true;
    }

    /** 只转移尚未开始生成的已付周期；结果未知的请求和原始坏数据继续由原所有者保管。 */
    void detachReplaced(BeeSlot[] current) {
        if (invalid != null || request != null) {
            return;
        }
        for (int i = 0; i < counts.length; i++) {
            if (slotSuspended(i) || counts[i] <= 0 || !current[i].isEmpty()
                    && bees[i].getBeeData().equals(current[i].getBeeData())) {
                continue;
            }
            if (retained == null) {
                retained = new ApiaryRetainedCycles();
            }
            if (!retained.accept(i, counts[i], bees[i].getBeeData(), conversionCompleted[i], myriadCompleted[i],
                    index -> !groupSuspended(index, true))) {
                continue;
            }
            counts[i] = 0;
            conversionCompleted[i] = false;
            myriadCompleted[i] = false;
            bees[i].setBeeData(null);
            beeSnapshots[i] = null;
            changed.run();
        }
    }

    ApiaryRetainedCycles retained() {
        return retained;
    }

    int total() {
        long total = 0;
        for (int n : counts) total += n;
        if (retained != null) {
            total += retained.total();
        }
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    public boolean suspended() { return request != null || invalid != null || !heldGroups.isEmpty(); }

    boolean slotSuspended(int slot) {
        if (invalid != null) return true;
        if (request != null && !activeRetained) {
            for (int index : activeSlots) if (index == slot) return true;
        }
        for (HeldGroup group : heldGroups) {
            if (group.retained()) continue;
            for (int index : group.slots()) if (index == slot) return true;
        }
        return false;
    }

    boolean groupSuspended(int index, boolean retainedGroup) {
        for (HeldGroup group : heldGroups) {
            if (group.retained() != retainedGroup) continue;
            for (int held : group.slots()) if (held == index) return true;
        }
        return false;
    }

    boolean retryableGeneration(int slot) {
        return counts[slot] > 0 && conversionCompleted[slot];
    }

    boolean conversionCompleted(int index, boolean retainedGroup) {
        return retainedGroup ? retained.conversionCompleted[index] : conversionCompleted[index];
    }

    boolean myriadCompleted(int index, boolean retainedGroup) {
        return retainedGroup ? retained.myriadCompleted[index] : myriadCompleted[index];
    }

    boolean activeMyriadCompleted() {
        return myriadCompleted(activeSlots[0], activeRetained);
    }

    void retryConversionPreparation() {
        if (request == null || !"conversion".equals(phase)) throw new IllegalStateException("No conversion preparation");
        request = null;
        activeSlots = new int[0];
        activeRetained = false;
        phase = "";
        changed.run();
    }

    boolean active() {
        return request != null;
    }

    boolean frozenOutputBlocked() {
        return invalid != null || request != null;
    }

    private boolean ownsPaidSnapshot(int slot) {
        return counts[slot] > 0 || slotSuspended(slot) && !bees[slot].isEmpty();
    }
    boolean hasInvalidData() { return invalid != null; }

    /** 只重新解析原快照；合法未知请求仍保持暂停，不重新生成或补发产物。 */
    boolean retryInvalid(HolderLookup.Provider provider) {
        if (invalid == null) return false;
        CompoundTag original = invalid;
        load(provider, original);
        if (invalid != null) return false;
        changed.run();
        return true;
    }
    public UUID request() { return request != null || heldGroups.isEmpty() ? request : heldGroups.getFirst().request(); }
    public String phase() { return request != null || heldGroups.isEmpty() ? phase : heldGroups.getFirst().phase(); }
    public String failure() { return request != null || heldGroups.isEmpty() ? failure : heldGroups.getFirst().failure(); }

    public record RequestStatus(UUID request, String phase, String failure) {}

    public List<RequestStatus> requestStatus() {
        List<RequestStatus> statuses = new ArrayList<>();
        if (request != null || invalid != null) statuses.add(new RequestStatus(request, phase, failure));
        for (HeldGroup group : heldGroups) {
            statuses.add(new RequestStatus(group.request(), group.phase(), group.failure()));
        }
        return statuses;
    }

    boolean hasFrozen() { return !items.isEmpty() || fluidAmount > 0; }
    List<ItemStack> items() { return items; }
    FluidStack fluid() { return fluid; }
    long fluidAmount() { return fluidAmount; }
    long frozenVersion() { return frozenVersion; }

    void begin(OrderedSlotIndex indices) {
        begin(indices, false);
    }

    void begin(OrderedSlotIndex indices, boolean retainedGroup) {
        if (request != null || invalid != null || hasFrozen()) throw new IllegalStateException("Production already held");
        if (retainedGroup && retained == null) {
            throw new IllegalStateException("Missing retained group");
        }
        for (int i = 0; i < indices.size(); i++) {
            if (groupSuspended(indices.get(i), retainedGroup)) {
                throw new IllegalStateException("Paid bee group outcome unresolved");
            }
        }
        activeRetained = retainedGroup;
        activeSlots = new int[indices.size()];
        for (int i = 0; i < activeSlots.length; i++) activeSlots[i] = indices.get(i);
        request = UUID.randomUUID();
        phase = conversionCompleted(activeSlots[0], activeRetained) ? "generation" : "conversion";
        failure = "";
        changed.run();
    }

    UUID beginLocal(int slot, CompoundTag context, BeeProduceProcessor.Generated generated) {
        if (suspended() || hasFrozen()) throw new IllegalStateException("Production already held");
        localContext = context.copy();
        request = UUID.randomUUID();
        activeSlots = new int[]{slot};
        activeRetained = false;
        phase = "local";
        failure = "";
        for (ItemStack stack : generated.items()) {
            if (!stack.isEmpty()) items.add(stack.copy());
        }
        fluid = generated.fluid().copy();
        fluidAmount = generated.fluidAmount();
        frozenVersion++;
        changed.run();
        return request;
    }

    void commitLocal(UUID expected) {
        if (!"local".equals(phase) || !expected.equals(request) || invalid != null) {
            throw new IllegalStateException("Stale local production");
        }
        request = null;
        activeSlots = new int[0];
        localContext = null;
        phase = "";
        failure = "";
        changed.run();
    }

    /** 旧本地批次尚未交付；零费用且无花源时只补完幂等进度，继续已冻结的原结果。 */
    boolean recoverLocal(BeeSlot[] current, HolderLookup.Provider provider) {
        if (invalid != null || request == null || !"local".equals(phase)) return false;
        if (!Set.of("host", "bee", "bee_data", "slot", "previous_ticks", "next_ticks", "cycles",
                "energy_before", "energy_cost", "mixed_local").containsAll(localContext.getAllKeys())
                || !localContext.contains("host", Tag.TAG_STRING) || !"mek_apiary".equals(localContext.getString("host"))
                || !localContext.contains("bee", Tag.TAG_STRING)
                || ResourceLocation.tryParse(localContext.getString("bee")) == null
                || !localContext.contains("bee_data", Tag.TAG_COMPOUND) || localContext.getCompound("bee_data").isEmpty()
                || !localContext.contains("slot", Tag.TAG_INT) || localContext.getInt("slot") != activeSlots[0]
                || !localContext.contains("previous_ticks", Tag.TAG_INT) || localContext.getInt("previous_ticks") < 0
                || !localContext.contains("next_ticks", Tag.TAG_INT) || localContext.getInt("next_ticks") < 0
                || !localContext.contains("cycles", Tag.TAG_LONG) || localContext.getLong("cycles") < 0
                || !localContext.contains("energy_before", Tag.TAG_LONG) || localContext.getLong("energy_before") < 0
                || !localContext.contains("energy_cost", Tag.TAG_LONG) || localContext.getLong("energy_cost") != 0) {
            return false;
        }
        int slot = activeSlots[0];
        BeeSlot live = current[slot];
        int next = localContext.getInt("next_ticks");
        if (!localContext.getCompound("bee_data").equals(live.getBeeData())
                || live.getTicksInHive() != localContext.getInt("previous_ticks") && live.getTicksInHive() != next) {
            return false;
        }
        if (localContext.contains("mixed_local")) {
            if (!localContext.contains("mixed_local", Tag.TAG_COMPOUND)) return false;
            CompoundTag mixed = localContext.getCompound("mixed_local");
            if (!Set.of("items_snbt", "fluid", "fluid_amount").containsAll(mixed.getAllKeys())
                    || !mixed.contains("items_snbt", Tag.TAG_STRING)
                    || !mixed.getString("items_snbt").equals(new ApiaryOutputEvidenceCache().encode(items, provider))) {
                return false;
            }
            if (fluidAmount > 0) {
                if (!mixed.contains("fluid", Tag.TAG_COMPOUND) || !mixed.contains("fluid_amount", Tag.TAG_LONG)
                        || mixed.getLong("fluid_amount") != fluidAmount
                        || !mixed.getCompound("fluid").equals(fluid.copyWithAmount(1).save(provider))) {
                    return false;
                }
            } else if (mixed.contains("fluid") || mixed.contains("fluid_amount")) {
                return false;
            }
        } else if (hasFrozen()) {
            return false;
        }
        UUID original = request;
        live.setTicksInHive(next);
        commitLocal(original);
        ProductiveBeesGenesis.LOGGER.info("Automatically resumed local bee production: request={}, slot={}; zero energy, no flower debit, original output retained",
                original, slot);
        return true;
    }

    void converted() {
        for (int slot : activeSlots) {
            if (activeRetained) retained.conversionCompleted[slot] = true;
            else conversionCompleted[slot] = true;
        }
        phase = "generation";
        changed.run();
    }

    void freeze(BeeProduceProcessor.Generated generated) {
        freeze(generated, null, false);
    }

    /** 只有本段产物冻结成功才扣对应已付周期，剩余仍属原蜜蜂并跳过花源重放。 */
    void freeze(BeeProduceProcessor.Generated generated, int[] generatedCounts) {
        freeze(generated, generatedCounts, false);
    }

    void freeze(BeeProduceProcessor.Generated generated, int[] generatedCounts, boolean completeMyriad) {
        if (invalid != null || hasFrozen()) throw new IllegalStateException("Frozen output occupied");
        int[] ownedCounts = activeRetained ? retained.counts : counts;
        if (generatedCounts != null) {
            for (int slot : activeSlots) {
                if (generatedCounts[slot] < 0 || generatedCounts[slot] > ownedCounts[slot]) {
                    throw new IllegalArgumentException("Invalid generated paid cycle count");
                }
            }
        }
        List<ItemStack> prepared = new ArrayList<>(generated.items().size());
        for (ItemStack stack : generated.items()) {
            if (!stack.isEmpty()) prepared.add(stack.copy());
        }
        FluidStack preparedFluid = generated.fluid().copy();
        items.addAll(prepared);
        fluid = preparedFluid;
        fluidAmount = generated.fluidAmount();
        for (int slot : activeSlots) {
            int settled = generatedCounts == null ? ownedCounts[slot] : generatedCounts[slot];
            ownedCounts[slot] -= settled;
            if (activeRetained) {
                if (ownedCounts[slot] == 0) retained.clear(slot);
                else {
                    retained.conversionCompleted[slot] = true;
                    retained.myriadCompleted[slot] |= completeMyriad;
                }
            } else if (ownedCounts[slot] == 0) {
                conversionCompleted[slot] = false;
                myriadCompleted[slot] = false;
                bees[slot].setBeeData(null);
            } else {
                conversionCompleted[slot] = true;
                myriadCompleted[slot] |= completeMyriad;
            }
        }
        request = null;
        activeSlots = new int[0];
        activeRetained = false;
        phase = "";
        frozenVersion++;
        changed.run();
    }

    void fail(Throwable error) {
        failure = error.getClass().getSimpleName() + ": " + error.getMessage();
        if (failure.length() > 512) failure = failure.substring(0, 512);
        if (request != null && !"local".equals(phase)) {
            // 本项目的生成准备不扣费或交付；花源已完成，重试不得再消费花源。
            if (!"generation".equals(phase)) {
                heldGroups.add(new HeldGroup(request, activeSlots, activeRetained, phase, failure));
            } else {
                retainConvertedCycles(activeSlots, activeRetained);
            }
            request = null;
            activeSlots = new int[0];
            activeRetained = false;
            phase = "";
        }
        changed.run();
    }

    private void retainConvertedCycles(int[] slots, boolean retainedGroup) {
        for (int slot : slots) {
            if (retainedGroup) {
                if (retained.counts[slot] == 0) retained.clear(slot);
                else retained.conversionCompleted[slot] = true;
            } else if (counts[slot] == 0) {
                conversionCompleted[slot] = false;
                myriadCompleted[slot] = false;
                bees[slot].setBeeData(null);
            } else {
                conversionCompleted[slot] = true;
            }
        }
    }

    void itemsChanged() { items.removeIf(ItemStack::isEmpty); changed.run(); }
    void debitFluid(long amount) {
        if (amount < 0 || amount > fluidAmount) throw new IllegalArgumentException("Invalid fluid debit");
        if (amount == 0) return;
        fluidAmount -= amount;
        if (fluidAmount == 0) fluid = FluidStack.EMPTY;
        changed.run();
    }

    /** 只在管理员核验该组所有花源、费用和产物已经结清后确认，不自动补发或重放。 */
    public void acknowledgeSettled(UUID expected) {
        if (invalid == null && (request == null || !request.equals(expected))) {
            for (int i = 0; i < heldGroups.size(); i++) {
                HeldGroup group = heldGroups.get(i);
                if (!group.request().equals(expected)) continue;
                for (int slot : group.slots()) {
                    if (group.retained()) retained.clear(slot);
                    else {
                        counts[slot] = 0;
                        conversionCompleted[slot] = false;
                        myriadCompleted[slot] = false;
                        bees[slot].setBeeData(null);
                    }
                }
                heldGroups.remove(i);
                changed.run();
                return;
            }
        }
        if (invalid != null || request == null || !request.equals(expected))
            throw new IllegalStateException("Stale production request");
        if ("local".equals(phase)) {
            items.clear();
            fluid = FluidStack.EMPTY;
            fluidAmount = 0;
            localContext = null;
            frozenVersion++;
        } else {
            for (int slot : activeSlots) {
                if (activeRetained) {
                    retained.clear(slot);
                } else {
                    counts[slot] = 0;
                    conversionCompleted[slot] = false;
                    myriadCompleted[slot] = false;
                    bees[slot].setBeeData(null);
                }
            }
        }
        request = null;
        activeSlots = new int[0];
        activeRetained = false;
        phase = "";
        failure = "";
        changed.run();
    }

    CompoundTag save(HolderLookup.Provider provider) {
        if (invalid != null) return invalid.copy();
        CompoundTag tag = new CompoundTag();
        tag.putInt("version", 3);
        tag.putInt("live_slots", counts.length);
        ListTag pending = new ListTag();
        for (int i = 0; i < counts.length; i++) if (ownsPaidSnapshot(i)) {
            CompoundTag entry = new CompoundTag();
            entry.putInt("slot", i);
            entry.putInt("count", counts[i]);
            if (conversionCompleted[i]) entry.putBoolean("converted", true);
            if (myriadCompleted[i]) entry.putBoolean("myriad_completed", true);
            if (bees[i].getBeeData() != null) entry.put("bee", bees[i].getBeeData().copy());
            pending.add(entry);
        }
        tag.put("pending", pending);
        tag.put("retained", retained == null ? new ListTag() : retained.save());
        ListTag held = new ListTag();
        for (HeldGroup group : heldGroups) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("request", group.request());
            entry.putIntArray("active", group.slots());
            entry.putBoolean("retained", group.retained());
            entry.putString("phase", group.phase());
            entry.putString("failure", group.failure());
            held.add(entry);
        }
        tag.put("held_groups", held);
        ListTag outputs = new ListTag();
        for (ItemStack item : items) {
            CompoundTag entry = new CompoundTag();
            entry.put("item", item.copyWithCount(1).save(provider));
            entry.putInt("count", item.getCount());
            outputs.add(entry);
        }
        tag.put("items", outputs);
        tag.putLong("fluid_amount", fluidAmount);
        if (!fluid.isEmpty()) tag.put("fluid", fluid.copyWithAmount(1).save(provider));
        if (request != null) {
            tag.putUUID("request", request);
            tag.putIntArray("active", activeSlots);
            tag.putString("phase", phase);
            tag.putString("failure", failure);
            tag.putBoolean("retained_request", activeRetained);
            if (localContext != null) tag.put("local_context", localContext.copy());
        }
        return tag;
    }

    void load(HolderLookup.Provider provider, CompoundTag tag) {
        frozenVersion++;
        java.util.Arrays.fill(counts, 0);
        java.util.Arrays.fill(conversionCompleted, false);
        java.util.Arrays.fill(myriadCompleted, false);
        heldGroups.clear();
        java.util.Arrays.fill(beeSnapshots, null);
        for (BeeSlot bee : bees) bee.setBeeData(null);
        items.clear(); fluid = FluidStack.EMPTY; fluidAmount = 0;
        request = null; invalid = null; phase = ""; failure = ""; activeSlots = new int[0];
        retained = null;
        activeRetained = false;
        localContext = null;
        if (tag.isEmpty()) return;
        try {
            // NBT 宽松 getter 会把错误类型当空值或截断数字；资产快照必须先核对原始类型。
            if (!Set.of("version", "pending", "items", "fluid_amount", "fluid", "request", "active", "phase", "failure",
                    "live_slots", "retained", "retained_request", "local_context", "held_groups")
                    .containsAll(tag.getAllKeys()) || !tag.contains("version", Tag.TAG_INT)
                    || tag.getInt("version") < 1 || tag.getInt("version") > 3 || !tag.contains("fluid_amount", Tag.TAG_LONG)) {
                throw new IllegalArgumentException("Invalid production header");
            }
            int savedSlots = counts.length;
            if (tag.getInt("version") >= 2) {
                if (!tag.contains("live_slots", Tag.TAG_INT) || tag.getInt("live_slots") < 1
                        || tag.getInt("live_slots") > counts.length) {
                    throw new IllegalArgumentException("Invalid saved bee slots");
                }
                savedSlots = tag.getInt("live_slots");
                ListTag retainedEntries = compoundList(tag, "retained");
                if (!retainedEntries.isEmpty()) {
                    retained = new ApiaryRetainedCycles();
                    retained.load(retainedEntries, savedSlots);
                }
            } else {
                // BlockItem 将新机器默认 NBT 递归合并进旧物品；只接受无资产的新增默认字段。
                if (tag.contains("live_slots")) {
                    if (!tag.contains("live_slots", Tag.TAG_INT) || tag.getInt("live_slots") != counts.length) {
                        throw new IllegalArgumentException("Invalid merged legacy bee slots");
                    }
                    savedSlots = tag.getInt("live_slots");
                }
                if (tag.contains("retained") && !compoundList(tag, "retained").isEmpty()
                        || tag.contains("retained_request")) {
                    throw new IllegalArgumentException("Retained cycles in legacy production header");
                }
            }
            for (var value : compoundList(tag, "pending")) {
                CompoundTag entry = (CompoundTag) value;
                if (!Set.of("slot", "count", "bee", "converted", "myriad_completed").containsAll(entry.getAllKeys())
                        || !entry.contains("slot", Tag.TAG_INT) || !entry.contains("count", Tag.TAG_INT)) {
                    throw new IllegalArgumentException("Invalid paid cycle fields");
                }
                int slot = entry.getInt("slot");
                int count = entry.getInt("count");
                if (slot < 0 || slot >= savedSlots || !bees[slot].isEmpty() || count < 0
                        || count == 0 && tag.getInt("version") < 3
                        || !entry.contains("bee", 10)) throw new IllegalArgumentException("Invalid paid cycle");
                counts[slot] = count;
                if (entry.contains("converted") && (!entry.contains("converted", Tag.TAG_BYTE)
                        || entry.getByte("converted") < 0 || entry.getByte("converted") > 1)) {
                    throw new IllegalArgumentException("Invalid paid conversion state");
                }
                conversionCompleted[slot] = entry.getBoolean("converted");
                if (entry.contains("myriad_completed") && (!entry.contains("myriad_completed", Tag.TAG_BYTE)
                        || entry.getByte("myriad_completed") < 0 || entry.getByte("myriad_completed") > 1)) {
                    throw new IllegalArgumentException("Invalid paid myriad state");
                }
                myriadCompleted[slot] = entry.getBoolean("myriad_completed");
                if (myriadCompleted[slot] && !conversionCompleted[slot]) {
                    throw new IllegalArgumentException("Myriad completed before paid conversion");
                }
                bees[slot].setBeeData(entry.getCompound("bee").copy());
            }
            for (var value : compoundList(tag, "items")) {
                CompoundTag entry = (CompoundTag) value;
                if (!Set.of("item", "count").containsAll(entry.getAllKeys())
                        || !entry.contains("item", Tag.TAG_COMPOUND) || !entry.contains("count", Tag.TAG_INT)) {
                    throw new IllegalArgumentException("Invalid frozen item fields");
                }
                ItemStack item = ItemStack.parse(provider, entry.getCompound("item")).orElseThrow();
                if (entry.getInt("count") <= 0) throw new IllegalArgumentException("Invalid frozen count");
                items.add(item.copyWithCount(entry.getInt("count")));
            }
            fluidAmount = tag.getLong("fluid_amount");
            if (fluidAmount < 0 || (fluidAmount > 0 ? !tag.contains("fluid", Tag.TAG_COMPOUND) : tag.contains("fluid")))
                throw new IllegalArgumentException("Invalid fluid amount or identity");
            if (fluidAmount > 0) {
                fluid = FluidStack.parse(provider, tag.getCompound("fluid")).orElseThrow();
                if (fluid.isEmpty()) throw new IllegalArgumentException("Invalid fluid");
            }
            if (tag.contains("request")) {
                if (!tag.hasUUID("request") || !tag.contains("active", Tag.TAG_INT_ARRAY)
                        || !tag.contains("phase", Tag.TAG_STRING) || !tag.contains("failure", Tag.TAG_STRING)
                        || tag.getInt("version") >= 2 && !tag.contains("retained_request", Tag.TAG_BYTE)) {
                    throw new IllegalArgumentException("Invalid production request");
                }
                activeRetained = tag.getBoolean("retained_request");
                if (activeRetained && retained == null) {
                    throw new IllegalArgumentException("Missing retained request owner");
                }
                int[] activeCounts = activeRetained ? retained.counts : counts;
                request = tag.getUUID("request");
                activeSlots = tag.getIntArray("active");
                if (activeSlots.length == 0 || activeSlots.length > activeCounts.length) throw new IllegalArgumentException("Invalid group size");
                boolean[] seen = new boolean[activeCounts.length];
                for (int i : activeSlots) {
                    if (i < 0 || i >= activeCounts.length || seen[i]
                            || activeRetained && (retained.bees[i] == null || retained.bees[i].getBeeData() == null))
                        throw new IllegalArgumentException("Invalid group");
                    seen[i] = true;
                }
                phase = tag.getString("phase"); failure = tag.getString("failure");
                if (phase.equals("local")) {
                    if (activeRetained || activeSlots.length != 1 || !tag.contains("local_context", Tag.TAG_COMPOUND)) {
                        throw new IllegalArgumentException("Invalid local production owner");
                    }
                    localContext = tag.getCompound("local_context").copy();
                } else if (!phase.equals("conversion") && !phase.equals("generation") || tag.contains("local_context")) {
                    throw new IllegalArgumentException("Unknown production phase");
                }
            } else if (tag.contains("active") || tag.contains("phase") || tag.contains("failure")
                    || tag.contains("retained_request") || tag.contains("local_context")) {
                throw new IllegalArgumentException("Production group without request");
            }
            if (tag.contains("held_groups")) {
                if (tag.getInt("version") < 3 && !compoundList(tag, "held_groups").isEmpty()) {
                    throw new IllegalArgumentException("Held groups in legacy production");
                }
                for (Tag value : compoundList(tag, "held_groups")) {
                    CompoundTag entry = (CompoundTag) value;
                    if (!entry.getAllKeys().equals(Set.of("request", "active", "retained", "phase", "failure"))
                            || !entry.hasUUID("request") || !entry.contains("active", Tag.TAG_INT_ARRAY)
                            || !entry.contains("retained", Tag.TAG_BYTE) || !entry.contains("phase", Tag.TAG_STRING)
                            || !entry.contains("failure", Tag.TAG_STRING)) {
                        throw new IllegalArgumentException("Invalid held production group");
                    }
                    boolean retainedGroup = entry.getBoolean("retained");
                    int[] slots = entry.getIntArray("active");
                    BeeSlot[] owners = retainedGroup && retained != null ? retained.bees : bees;
                    if (slots.length == 0 || retainedGroup && retained == null || !Set.of("conversion", "generation").contains(entry.getString("phase"))) {
                        throw new IllegalArgumentException("Invalid held production owner");
                    }
                    for (int i = 0; i < slots.length; i++) {
                        int slot = slots[i];
                        if (slot < 0 || slot >= owners.length || owners[slot] == null || owners[slot].isEmpty()
                                || groupSuspended(slot, retainedGroup)) {
                            throw new IllegalArgumentException("Missing held paid bee");
                        }
                        for (int prior = 0; prior < i; prior++) {
                            if (slots[prior] == slot) throw new IllegalArgumentException("Duplicate held slot");
                        }
                    }
                    heldGroups.add(new HeldGroup(entry.getUUID("request"), slots, retainedGroup,
                            entry.getString("phase"), entry.getString("failure")));
                }
            }
            if (request != null && !"local".equals(phase)) {
                // 先保管原请求；完整验证后只自动恢复没有冻结产物的生成阶段。
                heldGroups.add(new HeldGroup(request, activeSlots, activeRetained, phase, failure));
                request = null;
                activeSlots = new int[0];
                activeRetained = false;
                phase = "";
            }
            for (int i = 0; i < counts.length; i++) {
                if (counts[i] == 0 && !bees[i].isEmpty() && !slotSuspended(i)) {
                    throw new IllegalArgumentException("Zero paid count without held request");
                }
            }
            if (retained != null) {
                for (int i = 0; i < retained.counts.length; i++) {
                    if (retained.bees[i] == null || retained.bees[i].getBeeData() == null || retained.counts[i] > 0) {
                        continue;
                    }
                    boolean active = false;
                    if (activeRetained && request != null) {
                        for (int index : activeSlots) {
                            active |= index == i;
                        }
                    }
                    if (!active && !groupSuspended(i, true)) {
                        throw new IllegalArgumentException("Zero retained count without an active request");
                    }
                }
            }
            if (!hasFrozen()) {
                for (int i = heldGroups.size() - 1; i >= 0; i--) {
                    HeldGroup group = heldGroups.get(i);
                    if (!"generation".equals(group.phase())) continue;
                    retainConvertedCycles(group.slots(), group.retained());
                    heldGroups.remove(i);
                    ProductiveBeesGenesis.LOGGER.info("Automatically resumed paid bee generation: version={}, request={}, slots={}, retained={}, previousFailure={}",
                            tag.getInt("version"), group.request(), Arrays.toString(group.slots()), group.retained(), group.failure());
                }
            }
        } catch (RuntimeException | LinkageError error) {
            invalid = tag.copy();
            failure = "Invalid paid production; original preserved";
            ProductiveBeesGenesis.LOGGER.error("Paid bee production could not be restored; original data preserved", error);
        }
    }

    private static ListTag compoundList(CompoundTag tag, String key) {
        if (!(tag.get(key) instanceof ListTag list) || !list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND) {
            throw new IllegalArgumentException("Invalid production list: " + key);
        }
        return list;
    }
}
