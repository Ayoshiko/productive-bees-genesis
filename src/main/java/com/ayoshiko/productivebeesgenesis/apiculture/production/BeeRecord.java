package com.ayoshiko.productivebeesgenesis.apiculture.production;

import com.ayoshiko.productivebeesgenesis.apiculture.ownership.AssetImage;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductAmount;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** 原始蜂位数据只作无损交还模板；执行进度、未采样轮数和冻结数量各有唯一字段。 */
public record BeeRecord(UUID id, UUID member, int slot, AssetImage originalSlot, StaticBeePlan plan,
		long revision, int progress, long pendingCycles, ProductAmount frozen) {
	public BeeRecord {
		Objects.requireNonNull(id); Objects.requireNonNull(member); Objects.requireNonNull(originalSlot);
		Objects.requireNonNull(plan); Objects.requireNonNull(frozen);
		if (slot < 0 || originalSlot.isEmpty() || revision < 0 || progress < 0 || pendingCycles < 0)
			throw new IllegalArgumentException("Invalid bee record");
		var original = originalSlot.copy();
		if (!original.contains("slot_index", 3) || original.getInt("slot_index") < 0 || !original.contains("entity_data", 10)
				|| !original.contains("ticks_in_hive", 3) || original.getInt("ticks_in_hive") < 0
				|| !plan.beeType().equals(original.getCompound("entity_data").getString("type"))) throw new IllegalArgumentException("Bee identity differs from preserved source");
	}
	public static UUID identity(UUID member, int slot) {
		return UUID.nameUUIDFromBytes((member + ":bee:" + slot).getBytes(StandardCharsets.UTF_8));
	}
	public BeeRecord work(int remaining, long pending, ProductAmount result) {
		return new BeeRecord(id, member, slot, originalSlot, plan, Math.incrementExact(revision), remaining, pending, result);
	}
	public boolean drained() { return pendingCycles == 0 && frozen.isZero(); }
	public BeeRecord relocate(int target) {
		if (!drained()) throw new IllegalStateException("Drain paid bee work before moving");
		return new BeeRecord(id, member, target, originalSlot, plan, Math.incrementExact(revision), progress, pendingCycles, frozen);
	}
}
