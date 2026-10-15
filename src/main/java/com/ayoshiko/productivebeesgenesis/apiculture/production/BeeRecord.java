package com.ayoshiko.productivebeesgenesis.apiculture.production;

import com.ayoshiko.productivebeesgenesis.apiculture.ownership.AssetImage;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 原蜂位为交还模板；冻结余量按完整键保管，续采结束且全部交付后才能开启新周期。 */
public record BeeRecord(UUID id, UUID member, int slot, AssetImage originalSlot, StaticBeePlan plan,
		long revision, int progress, long pendingCycles, Map<ProductKey, ProductAmount> frozenOutputs,
		BeeCycleRandom random, boolean enabled, BeeSamplingCursor sampling) {
	public BeeRecord {
		Objects.requireNonNull(id); Objects.requireNonNull(member); Objects.requireNonNull(originalSlot); Objects.requireNonNull(plan); Objects.requireNonNull(random);
		frozenOutputs = Map.copyOf(frozenOutputs);
		if (slot < 0 || originalSlot.isEmpty() || revision < 0 || progress < 0 || pendingCycles < 0) throw new IllegalArgumentException("Invalid bee record");
		Math.addExact(random.cursor(), pendingCycles);
		var keys = new HashSet<ProductKey>(); for (var output : plan.outputs()) keys.add(output.output());
		for (var entry : frozenOutputs.entrySet()) if (entry.getValue().isZero() || !keys.contains(entry.getKey())) throw new IllegalArgumentException("Foreign or zero frozen bee result");
		if (sampling != null && (pendingCycles == 0 || plan.deterministic() || sampling.output() >= plan.outputs().size())) throw new IllegalArgumentException("Orphaned bee sampling cursor");
		if (sampling != null) {
			var output = plan.outputs().get(sampling.output());
			if (!sampling.rolls().equals(BeeProductionRollPlan.fromMultiplier(plan.productionMultiplier()).sampleCycle(random.draw(0)))
					|| !sampling.consumed().isZero() && (output.deterministic() || output.basis() != BeeOutputPlan.Basis.ROLL
					|| sampling.consumed().compareTo(sampling.rolls()) >= 0
					|| sampling.consumed().exact().remainder(java.math.BigInteger.valueOf(BeeOutputSampler.RANDOM_ROLLS)).signum() != 0))
				throw new IllegalArgumentException("Bee sampling cursor differs from paid cycle");
		}
		var original = originalSlot.copy();
		if (!original.contains("slot_index", 3) || original.getInt("slot_index") < 0 || !original.contains("entity_data", 10)
				|| !original.contains("ticks_in_hive", 3) || original.getInt("ticks_in_hive") < 0
				|| !plan.beeType().equals(original.getCompound("entity_data").getString("type"))) throw new IllegalArgumentException("Bee identity differs from preserved source");
	}
	public BeeRecord(UUID id, UUID member, int slot, AssetImage original, StaticBeePlan plan, long revision, int progress,
			long pending, ProductAmount frozen, BeeCycleRandom random, boolean enabled, ProductAmount fluid) {
		this(id, member, slot, original, plan, revision, progress, pending, legacyFrozen(plan, frozen, fluid), random, enabled, null);
	}
	public BeeRecord(UUID id, UUID member, int slot, AssetImage original, StaticBeePlan plan, long revision, int progress,
			long pending, ProductAmount frozen, BeeCycleRandom random, boolean enabled) {
		this(id, member, slot, original, plan, revision, progress, pending, frozen, random, enabled, ProductAmount.ZERO);
	}
	public BeeRecord(UUID id, UUID member, int slot, AssetImage original, StaticBeePlan plan, long revision, int progress,
			long pending, ProductAmount frozen, BeeCycleRandom random) { this(id, member, slot, original, plan, revision, progress, pending, frozen, random, true); }
	public BeeRecord(UUID id, UUID member, int slot, AssetImage original, StaticBeePlan plan, long revision, int progress,
			long pending, ProductAmount frozen) { this(id, member, slot, original, plan, revision, progress, pending, frozen, BeeCycleRandom.initial(id)); }
	private static Map<ProductKey, ProductAmount> legacyFrozen(StaticBeePlan plan, ProductAmount item, ProductAmount fluid) {
		var result = new HashMap<ProductKey, ProductAmount>();
		if (!item.isZero()) result.put(plan.output(), item);
		if (!fluid.isZero()) { if (plan.fluidOutput() == null) throw new IllegalArgumentException("Unexpected frozen fluid"); result.put(plan.fluidOutput(), fluid); }
		return result;
	}
	public static UUID identity(UUID member, int slot) { return UUID.nameUUIDFromBytes((member + ":bee:" + slot).getBytes(StandardCharsets.UTF_8)); }
	public BeeRecord work(int remaining, long pending, Map<ProductKey, ProductAmount> results) {
		return new BeeRecord(id, member, slot, originalSlot, plan, Math.incrementExact(revision), remaining, pending, results, random, enabled, sampling);
	}
	public BeeRecord work(int remaining, long pending, ProductAmount result) { return work(remaining, pending, result, frozenFluid()); }
	public BeeRecord work(int remaining, long pending, ProductAmount result, ProductAmount fluid) { return work(remaining, pending, legacyFrozen(plan, result, fluid)); }
	public ProductAmount frozen() { return frozenOutputs.isEmpty() ? ProductAmount.ZERO : frozenOutputs.getOrDefault(plan.output(), ProductAmount.ZERO); }
	public ProductAmount frozenFluid() { var fluid = plan.fluidOutput(); return fluid == null ? ProductAmount.ZERO : frozenOutputs.getOrDefault(fluid, ProductAmount.ZERO); }
	public BeeRecord enabled(boolean value) {
		return enabled == value ? this : new BeeRecord(id, member, slot, originalSlot, plan, Math.incrementExact(revision), progress, pendingCycles, frozenOutputs, random, value, sampling);
	}
	public boolean hasFrozen() { return !frozenOutputs.isEmpty(); }
	public boolean drained() { return pendingCycles == 0 && sampling == null && !hasFrozen(); }
	public BeeRecord relocate(int target) {
		if (!drained()) throw new IllegalStateException("Drain paid bee work before moving");
		return new BeeRecord(id, member, target, originalSlot, plan, Math.incrementExact(revision), progress, pendingCycles, frozenOutputs, random, enabled, sampling);
	}
}
