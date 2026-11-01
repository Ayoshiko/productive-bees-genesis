package com.ayoshiko.productivebeesgenesis.apiary;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import java.util.ArrayList;
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
    private CompoundTag invalid;
    private ApiaryRetainedCycles retained;
    private boolean activeRetained;

    ApiaryProductionState(int slots, Runnable changed) {
        this.changed = changed;
        counts = new int[slots];
        bees = new BeeSlot[slots];
        beeSnapshots = new BeeSnapshot[slots];
        for (int i = 0; i < slots; i++) bees[i] = new BeeSlot();
    }

    void capture(int slot, BeeSlot bee) {
        if (counts[slot] > 0 && bees[slot].isEmpty()) {
            CompoundTag source = bee.getBeeData();
            BeeSnapshot cached = beeSnapshots[slot];
            if (cached == null || cached.source() != source) {
                cached = new BeeSnapshot(source, source.copy());
                beeSnapshots[slot] = cached;
            }
            bees[slot].setBeeData(cached.data());
            changed.run();
        }
    }

    void releaseEmptyBee(int slot) {
        if (counts[slot] == 0) beeSnapshots[slot] = null;
    }

    boolean canAdvance(int slot, BeeSlot bee) {
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
        if (suspended()) {
            return;
        }
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] <= 0 || !current[i].isEmpty() && canAdvance(i, current[i])) {
                continue;
            }
            if (retained == null) {
                retained = new ApiaryRetainedCycles();
            }
            if (!retained.accept(i, counts[i], bees[i].getBeeData())) {
                continue;
            }
            counts[i] = 0;
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

    public boolean suspended() { return request != null || invalid != null; }
    public UUID request() { return request; }
    public String phase() { return phase; }
    public String failure() { return failure; }
    boolean hasFrozen() { return !items.isEmpty() || fluidAmount > 0; }
    List<ItemStack> items() { return items; }
    FluidStack fluid() { return fluid; }
    long fluidAmount() { return fluidAmount; }

    void begin(OrderedSlotIndex indices) {
        begin(indices, false);
    }

    void begin(OrderedSlotIndex indices, boolean retainedGroup) {
        if (suspended() || hasFrozen()) throw new IllegalStateException("Production already held");
        if (retainedGroup && retained == null) {
            throw new IllegalStateException("Missing retained group");
        }
        activeRetained = retainedGroup;
        activeSlots = new int[indices.size()];
        for (int i = 0; i < activeSlots.length; i++) activeSlots[i] = indices.get(i);
        request = UUID.randomUUID();
        phase = "conversion";
        failure = "";
        changed.run();
    }

    void converted() { phase = "generation"; changed.run(); }

    void freeze(BeeProduceProcessor.Generated generated) {
        if (invalid != null || hasFrozen()) throw new IllegalStateException("Frozen output occupied");
        for (ItemStack stack : generated.items()) if (!stack.isEmpty()) items.add(stack.copy());
        fluid = generated.fluid().copy();
        fluidAmount = generated.fluidAmount();
        for (int slot : activeSlots) {
            if (activeRetained) {
                retained.clear(slot);
            } else {
                counts[slot] = 0;
                bees[slot].setBeeData(null);
            }
        }
        request = null;
        activeSlots = new int[0];
        activeRetained = false;
        phase = "";
        changed.run();
    }

    void fail(Throwable error) {
        failure = error.getClass().getSimpleName() + ": " + error.getMessage();
        if (failure.length() > 512) failure = failure.substring(0, 512);
        changed.run();
    }

    void itemsChanged() { items.removeIf(ItemStack::isEmpty); changed.run(); }
    void debitFluid(long amount) {
        if (amount < 0 || amount > fluidAmount) throw new IllegalArgumentException("Invalid fluid debit");
        fluidAmount -= amount;
        if (fluidAmount == 0) fluid = FluidStack.EMPTY;
        changed.run();
    }

    /** 只在管理员核验该组所有花源、费用和产物已经结清后确认，不自动补发或重放。 */
    public void acknowledgeSettled(UUID expected) {
        if (invalid != null || request == null || !request.equals(expected))
            throw new IllegalStateException("Stale production request");
        for (int slot : activeSlots) {
            if (activeRetained) {
                retained.clear(slot);
            } else {
                counts[slot] = 0;
                bees[slot].setBeeData(null);
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
        tag.putInt("version", 2);
        tag.putInt("live_slots", counts.length);
        ListTag pending = new ListTag();
        for (int i = 0; i < counts.length; i++) if (counts[i] > 0) {
            CompoundTag entry = new CompoundTag();
            entry.putInt("slot", i);
            entry.putInt("count", counts[i]);
            if (bees[i].getBeeData() != null) entry.put("bee", bees[i].getBeeData().copy());
            pending.add(entry);
        }
        tag.put("pending", pending);
        tag.put("retained", retained == null ? new ListTag() : retained.save());
        ListTag outputs = new ListTag();
        for (ItemStack item : items) {
            CompoundTag entry = new CompoundTag();
            entry.put("item", item.copyWithCount(1).save(new CompoundTag()));
            entry.putInt("count", item.getCount());
            outputs.add(entry);
        }
        tag.put("items", outputs);
        tag.putLong("fluid_amount", fluidAmount);
        if (!fluid.isEmpty()) tag.put("fluid", com.ayoshiko.productivebeesgenesis.port.ForgeNbt.save(com.ayoshiko.productivebeesgenesis.util.ForgeFluidStacks.copyWithAmount(fluid, 1),provider));
        if (request != null) {
            tag.putUUID("request", request);
            tag.putIntArray("active", activeSlots);
            tag.putString("phase", phase);
            tag.putString("failure", failure);
            tag.putBoolean("retained_request", activeRetained);
        }
        return tag;
    }

    void load(HolderLookup.Provider provider, CompoundTag tag) {
        java.util.Arrays.fill(counts, 0);
        java.util.Arrays.fill(beeSnapshots, null);
        for (BeeSlot bee : bees) bee.setBeeData(null);
        items.clear(); fluid = FluidStack.EMPTY; fluidAmount = 0;
        request = null; invalid = null; phase = ""; failure = ""; activeSlots = new int[0];
        retained = null;
        activeRetained = false;
        if (tag.isEmpty()) return;
        try {
            // NBT 宽松 getter 会把错误类型当空值或截断数字；资产快照必须先核对原始类型。
            if (!Set.of("version", "pending", "items", "fluid_amount", "fluid", "request", "active", "phase", "failure",
                    "live_slots", "retained", "retained_request")
                    .containsAll(tag.getAllKeys()) || !tag.contains("version", Tag.TAG_INT)
                    || tag.getInt("version") < 1 || tag.getInt("version") > 2 || !tag.contains("fluid_amount", Tag.TAG_LONG)) {
                throw new IllegalArgumentException("Invalid production header");
            }
            int savedSlots = counts.length;
            if (tag.getInt("version") == 2) {
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
                if (!Set.of("slot", "count", "bee").containsAll(entry.getAllKeys())
                        || !entry.contains("slot", Tag.TAG_INT) || !entry.contains("count", Tag.TAG_INT)) {
                    throw new IllegalArgumentException("Invalid paid cycle fields");
                }
                int slot = entry.getInt("slot");
                int count = entry.getInt("count");
                if (slot < 0 || slot >= savedSlots || counts[slot] != 0 || count <= 0
                        || !entry.contains("bee", 10)) throw new IllegalArgumentException("Invalid paid cycle");
                counts[slot] = count;
                bees[slot].setBeeData(entry.getCompound("bee").copy());
            }
            for (var value : compoundList(tag, "items")) {
                CompoundTag entry = (CompoundTag) value;
                if (!Set.of("item", "count").containsAll(entry.getAllKeys())
                        || !entry.contains("item", Tag.TAG_COMPOUND) || !entry.contains("count", Tag.TAG_INT)) {
                    throw new IllegalArgumentException("Invalid frozen item fields");
                }
                ItemStack item = java.util.Optional.of(ItemStack.of(entry.getCompound("item"))).filter(parsedStack -> !parsedStack.isEmpty()).orElseThrow();
                if (entry.getInt("count") <= 0) throw new IllegalArgumentException("Invalid frozen count");
                items.add(item.copyWithCount(entry.getInt("count")));
            }
            fluidAmount = tag.getLong("fluid_amount");
            if (fluidAmount < 0 || (fluidAmount > 0 ? !tag.contains("fluid", Tag.TAG_COMPOUND) : tag.contains("fluid")))
                throw new IllegalArgumentException("Invalid fluid amount or identity");
            if (fluidAmount > 0) {
                fluid = java.util.Optional.of(FluidStack.loadFluidStackFromNBT(tag.getCompound("fluid"))).filter(parsedStack -> !parsedStack.isEmpty()).orElseThrow();
                if (fluid.isEmpty()) throw new IllegalArgumentException("Invalid fluid");
            }
            if (tag.contains("request")) {
                if (!tag.hasUUID("request") || !tag.contains("active", Tag.TAG_INT_ARRAY)
                        || !tag.contains("phase", Tag.TAG_STRING) || !tag.contains("failure", Tag.TAG_STRING)
                        || tag.getInt("version") == 2 && !tag.contains("retained_request", Tag.TAG_BYTE)) {
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
                if (!phase.equals("conversion") && !phase.equals("generation")) throw new IllegalArgumentException("Unknown production phase");
            } else if (tag.contains("active") || tag.contains("phase") || tag.contains("failure") || tag.contains("retained_request")) {
                throw new IllegalArgumentException("Production group without request");
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
                    if (!active) {
                        throw new IllegalArgumentException("Zero retained count without an active request");
                    }
                }
            }
        } catch (RuntimeException error) { invalid = tag.copy(); failure = "Invalid paid production; original preserved"; }
    }

    private static ListTag compoundList(CompoundTag tag, String key) {
        if (!(tag.get(key) instanceof ListTag list) || !list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND) {
            throw new IllegalArgumentException("Invalid production list: " + key);
        }
        return list;
    }
}
