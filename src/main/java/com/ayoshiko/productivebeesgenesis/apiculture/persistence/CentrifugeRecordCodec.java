package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import com.ayoshiko.productivebeesgenesis.apiculture.centrifuge.*;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

/** 有限作业记录的严格恢复；冻结结果直接读取，不再采样。 */
final class CentrifugeRecordCodec {
	static CompoundTag encode(CentrifugeWorkState state) {
		var tag = new CompoundTag(); if (state == null) return tag;
		tag.putUUID("member", state.member()); tag.putLong("revision", state.revision()); tag.putInt("lanes", state.laneCount());
		tag.putLong("energy", state.energy()); tag.putLong("capacity", state.energyCapacity());
		var jobs = new ListTag();
		state.jobs().forEach((lane, job) -> {
			var value = new CompoundTag(); value.putInt("lane", lane); value.putUUID("id", job.id()); value.put("plan", plan(job.plan()));
			value.putInt("operations", job.operations()); value.putInt("progress", job.progress()); value.putLong("seed", job.seed());
			value.putBoolean("sampled", job.sampled()); value.put("frozen", job.sampled() ? ProductRecordCodec.amounts(job.frozen()) : new ListTag()); jobs.add(value);
		});
		tag.put("jobs", jobs); return tag;
	}
	static CentrifugeWorkState decode(CompoundTag tag, Consumer<ProductKey> validate) {
		if (tag.isEmpty()) return null;
		fields(tag, "member", "revision", "lanes", "energy", "capacity", "jobs");
		var jobs = new ConcurrentHashMap<Integer, CentrifugeJob>(); var codec = new ProductRecordCodec(validate);
		for (var raw : StrictNbt.list(tag, "jobs")) {
			var value = (CompoundTag) raw;
			fields(value, "lane", "id", "plan", "operations", "progress", "seed", "sampled", "frozen");
			var frozen = codec.readAmounts(StrictNbt.list(value, "frozen")); boolean sampled = StrictNbt.bool(value, "sampled");
			if (!sampled && !frozen.isEmpty()) throw new IllegalArgumentException("Unsampled centrifuge has frozen output");
			var job = new CentrifugeJob(StrictNbt.uuid(value, "id"), readPlan(StrictNbt.compound(value, "plan"), codec),
					StrictNbt.integer(value, "operations"), StrictNbt.integer(value, "progress"), StrictNbt.number(value, "seed"), sampled ? frozen : null);
			if (jobs.putIfAbsent(StrictNbt.integer(value, "lane"), job) != null) throw new IllegalArgumentException("Duplicate centrifuge lane");
		}
		return new CentrifugeWorkState(StrictNbt.uuid(tag, "member"), StrictNbt.number(tag, "revision"), StrictNbt.integer(tag, "lanes"),
				StrictNbt.number(tag, "energy"), StrictNbt.number(tag, "capacity"), jobs);
	}
	private static CompoundTag plan(CentrifugeRecipePlan plan) {
		var tag = new CompoundTag(); tag.putString("recipe", plan.recipe()); tag.putLong("recipeRevision", plan.recipeRevision());
		tag.putLong("capabilityRevision", plan.capabilityRevision()); tag.put("input", ProductRecordCodec.key(plan.input()));
		tag.putInt("ticks", plan.cycleTicks()); tag.putInt("parallel", plan.maxParallel()); tag.putLong("cost", plan.unitEnergyPerTick());
		tag.putInt("productivity", plan.productivity()); tag.putDouble("stability", plan.stability()); var outputs = new ListTag();
		for (var output : plan.outputs()) {
			var value = new CompoundTag(); value.put("key", ProductRecordCodec.key(output.key())); value.putInt("min", output.minimum());
			value.putInt("max", output.maximum()); value.putDouble("chance", output.chance()); outputs.add(value);
		}
		tag.put("outputs", outputs); return tag;
	}
	private static CentrifugeRecipePlan readPlan(CompoundTag tag, ProductRecordCodec codec) {
		fields(tag, "recipe", "recipeRevision", "capabilityRevision", "input", "ticks", "parallel", "cost", "productivity", "stability", "outputs");
		var outputs = new ArrayList<CentrifugeRecipePlan.Output>();
		for (var raw : StrictNbt.list(tag, "outputs")) {
			var value = (CompoundTag) raw; fields(value, "key", "min", "max", "chance");
			outputs.add(new CentrifugeRecipePlan.Output(codec.readKey(StrictNbt.compound(value, "key")), StrictNbt.integer(value, "min"),
					StrictNbt.integer(value, "max"), exactFloat(value, "chance")));
		}
		return new CentrifugeRecipePlan(StrictNbt.string(tag, "recipe"), StrictNbt.number(tag, "recipeRevision"), StrictNbt.number(tag, "capabilityRevision"),
				codec.readKey(StrictNbt.compound(tag, "input")), StrictNbt.integer(tag, "ticks"), StrictNbt.integer(tag, "parallel"),
				StrictNbt.number(tag, "cost"), StrictNbt.integer(tag, "productivity"), exactFloat(tag, "stability"), outputs);
	}
	private static float exactFloat(CompoundTag tag, String name) {
		double value = StrictNbt.decimal(tag, name); float result = (float) value;
		if (!Float.isFinite(result) || (double) result != value) throw new IllegalArgumentException("Invalid float projection");
		return result;
	}
	private static void fields(CompoundTag tag, String... fields) {
		if (!tag.getAllKeys().equals(Set.of(fields))) throw new IllegalArgumentException("Unknown or missing centrifuge fields");
	}
	private CentrifugeRecordCodec() { }
}
