package com.ayoshiko.productivebeesgenesis.apiculture.production;

import com.ayoshiko.productivebeesgenesis.apiculture.ownership.AssetImage;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductAmount;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 原始蜂位只作交还模板；物品和流体冻结余量分别保管，全部结清才能开启新周期。 */
public record BeeRecord(UUID id, UUID member, int slot, AssetImage originalSlot, StaticBeePlan plan,
		long revision, int progress, long pendingCycles, ProductAmount frozen, BeeCycleRandom random, boolean enabled,
		ProductAmount frozenFluid) {
	public BeeRecord(UUID id, UUID member, int slot, AssetImage originalSlot, StaticBeePlan plan,
			long revision, int progress, long pendingCycles, ProductAmount frozen, BeeCycleRandom random, boolean enabled) {
		this(id, member, slot, originalSlot, plan, revision, progress, pendingCycles, frozen, random, enabled, ProductAmount.ZERO);
	}
	public BeeRecord(UUID id, UUID member, int slot, AssetImage originalSlot, StaticBeePlan plan,
			long revision, int progress, long pendingCycles, ProductAmount frozen, BeeCycleRandom random) {
		this(id, member, slot, originalSlot, plan, revision, progress, pendingCycles, frozen, random, true);
	}
	public BeeRecord(UUID id, UUID member, int slot, AssetImage originalSlot, StaticBeePlan plan,
			long revision, int progress, long pendingCycles, ProductAmount frozen) {
		this(id, member, slot, originalSlot, plan, revision, progress, pendingCycles, frozen, BeeCycleRandom.initial(id));
	}
	public BeeRecord {
		Objects.requireNonNull(id); Objects.requireNonNull(member); Objects.requireNonNull(originalSlot);
		Objects.requireNonNull(plan); Objects.requireNonNull(frozen); Objects.requireNonNull(random); Objects.requireNonNull(frozenFluid);
		if (slot < 0 || originalSlot.isEmpty() || revision < 0 || progress < 0 || pendingCycles < 0
				|| plan.fluidOutput() == null && !frozenFluid.isZero()) throw new IllegalArgumentException("Invalid bee record");
		Math.addExact(random.cursor(), pendingCycles);
		var original = originalSlot.copy();
		if (!original.contains("slot_index", 3) || original.getInt("slot_index") < 0 || !original.contains("entity_data", 10)
				|| !original.contains("ticks_in_hive", 3) || original.getInt("ticks_in_hive") < 0
				|| !plan.beeType().equals(original.getCompound("entity_data").getString("type"))) throw new IllegalArgumentException("Bee identity differs from preserved source");
	}
	public static UUID identity(UUID member, int slot) {
		return UUID.nameUUIDFromBytes((member + ":bee:" + slot).getBytes(StandardCharsets.UTF_8));
	}
	public BeeRecord work(int remaining, long pending, ProductAmount result) { return work(remaining, pending, result, frozenFluid); }
	public BeeRecord work(int remaining, long pending, ProductAmount result, ProductAmount fluid) {
		return new BeeRecord(id, member, slot, originalSlot, plan, Math.incrementExact(revision), remaining, pending, result, random, enabled, fluid);
	}
	public BeeRecord enabled(boolean value) {
		return enabled == value ? this : new BeeRecord(id, member, slot, originalSlot, plan,
				Math.incrementExact(revision), progress, pendingCycles, frozen, random, value, frozenFluid);
	}
	public boolean hasFrozen() { return !frozen.isZero() || !frozenFluid.isZero(); }
	public Map<ProductKey, ProductAmount> frozenOutputs() {
		if (frozen.isZero()) return frozenFluid.isZero() ? Map.of() : Map.of(plan.fluidOutput(), frozenFluid);
		return frozenFluid.isZero() ? Map.of(plan.output(), frozen) : Map.of(plan.output(), frozen, plan.fluidOutput(), frozenFluid);
	}
	public boolean drained() { return pendingCycles == 0 && !hasFrozen(); }
	public BeeRecord relocate(int target) {
		if (!drained()) throw new IllegalStateException("Drain paid bee work before moving");
		return new BeeRecord(id, member, target, originalSlot, plan, Math.incrementExact(revision), progress, pendingCycles, frozen, random, enabled, frozenFluid);
	}
}
