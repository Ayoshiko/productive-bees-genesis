package com.ayoshiko.productivebeesgenesis.apiary;

import java.util.Arrays;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** 已推进并付费、尚未生成物品的周期；保存和迁移只复制计数，不重新推进或扣能。 */
final class PendingBeeCycles {
	static final String KEY = "productivebeesgenesis_pending_bee_cycles";
	private PendingBeeCycles() { }
	static void save(CompoundTag parent, int[] counts, int flushTicks, int rotation) {
		if (Arrays.stream(counts).allMatch(value -> value == 0)) { parent.remove(KEY); return; }
		validate(counts, counts.length, flushTicks, rotation);
		var tag = new CompoundTag(); tag.putInt("schema", 1); tag.putIntArray("counts", counts.clone());
		tag.putInt("flushTicks", flushTicks); tag.putInt("rotation", rotation); parent.put(KEY, tag);
	}
	static Snapshot read(CompoundTag parent, int slots) {
		if (!parent.contains(KEY)) return new Snapshot(new int[slots], 0, 0);
		if (!parent.contains(KEY, Tag.TAG_COMPOUND)) throw new IllegalArgumentException("Invalid pending bee cycles");
		var tag = parent.getCompound(KEY);
		if (!tag.contains("schema", Tag.TAG_INT) || tag.getInt("schema") != 1 || !tag.contains("counts", Tag.TAG_INT_ARRAY)
				|| !tag.contains("flushTicks", Tag.TAG_INT) || !tag.contains("rotation", Tag.TAG_INT)
				|| tag.getAllKeys().size() != 4) throw new IllegalArgumentException("Incomplete pending bee cycles");
		int[] counts = tag.getIntArray("counts").clone(); int ticks = tag.getInt("flushTicks"), rotation = tag.getInt("rotation");
		validate(counts, counts.length, ticks, rotation);
		if (counts.length > slots) throw new IllegalArgumentException("Pending bee cycles exceed target slots");
		return new Snapshot(Arrays.copyOf(counts, slots), ticks, rotation);
	}
	private static void validate(int[] counts, int slots, int ticks, int rotation) {
		if (counts.length != slots || ticks < 0 || rotation < 0 || rotation >= Math.max(1, slots)) throw new IllegalArgumentException("Pending bee cycles do not fit this apiary");
		for (int count : counts) if (count < 0) throw new IllegalArgumentException("Negative pending bee cycles");
	}
	record Snapshot(int[] counts, int flushTicks, int rotation) {
		Snapshot { counts = counts.clone(); }
		@Override public int[] counts() { return counts.clone(); }
		int total() { int result = 0; for (int count : counts) result = ApiaryEnergyMath.saturatingAdd(result, count); return result; }
	}
}
