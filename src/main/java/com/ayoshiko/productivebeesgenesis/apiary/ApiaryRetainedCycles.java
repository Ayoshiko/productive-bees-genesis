package com.ayoshiko.productivebeesgenesis.apiary;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.Set;

/** 已取出的蜜蜂留下的已付周期。保管原蜂数据，不能占用后来放入的蜜蜂槽或无限增长。 */
final class ApiaryRetainedCycles {

    static final int MAX_GROUPS = 128;
    final int[] counts = new int[MAX_GROUPS];
    final BeeSlot[] bees = new BeeSlot[MAX_GROUPS];
    private final int[] sourceSlots = new int[MAX_GROUPS];
    private int groups;

    boolean accept(int sourceSlot, int count, CompoundTag bee) {
        if (count <= 0 || bee == null) {
            throw new IllegalArgumentException("Missing paid bee snapshot");
        }
        int empty = -1;
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] == 0) {
                if (empty < 0) {
                    empty = i;
                }
            } else if (sourceSlots[i] == sourceSlot && bees[i].getBeeData().equals(bee)
                    && (long) counts[i] + count <= Integer.MAX_VALUE) {
                counts[i] += count;
                return true;
            }
        }
        if (empty < 0) {
            return false;
        }
        if (bees[empty] == null) {
            bees[empty] = new BeeSlot();
        }
        bees[empty].setBeeData(bee);
        counts[empty] = count;
        sourceSlots[empty] = sourceSlot;
        groups++;
        return true;
    }

    void clear(int index) {
        if (bees[index].getBeeData() != null) {
            groups--;
        }
        counts[index] = 0;
        bees[index].setBeeData(null);
        sourceSlots[index] = 0;
    }

    int sourceSlot(int index) {
        return sourceSlots[index];
    }

    int groups() {
        return groups;
    }

    long total() {
        long total = 0;
        for (int count : counts) {
            total += count;
        }
        return total;
    }

    ListTag save() {
        ListTag entries = new ListTag();
        for (int i = 0; i < counts.length; i++) {
            if (bees[i] == null || bees[i].getBeeData() == null) {
                continue;
            }
            CompoundTag entry = new CompoundTag();
            entry.putInt("index", i);
            entry.putInt("slot", sourceSlots[i]);
            entry.putInt("count", counts[i]);
            entry.put("bee", bees[i].getBeeData().copy());
            entries.add(entry);
        }
        return entries;
    }

    void load(ListTag entries, int liveSlots) {
        if (entries.size() > MAX_GROUPS || !entries.isEmpty() && entries.getElementType() != Tag.TAG_COMPOUND) {
            throw new IllegalArgumentException("Invalid retained cycle list");
        }
        for (Tag value : entries) {
            CompoundTag entry = (CompoundTag) value;
            if (!Set.of("index", "slot", "count", "bee").containsAll(entry.getAllKeys())
                    || !entry.contains("index", Tag.TAG_INT) || !entry.contains("slot", Tag.TAG_INT)
                    || !entry.contains("count", Tag.TAG_INT) || !entry.contains("bee", Tag.TAG_COMPOUND)) {
                throw new IllegalArgumentException("Invalid retained cycle fields");
            }
            int index = entry.getInt("index");
            int slot = entry.getInt("slot");
            int count = entry.getInt("count");
            if (index < 0 || index >= MAX_GROUPS || bees[index] != null || count < 0
                    || slot < 0 || slot >= liveSlots) {
                throw new IllegalArgumentException("Invalid retained cycle identity");
            }
            sourceSlots[index] = slot;
            counts[index] = count;
            bees[index] = new BeeSlot();
            bees[index].setBeeData(entry.getCompound("bee").copy());
            groups++;
        }
    }
}
