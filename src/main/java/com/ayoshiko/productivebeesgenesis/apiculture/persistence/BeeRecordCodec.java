package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import com.ayoshiko.productivebeesgenesis.apiculture.ownership.AssetImage;
import com.ayoshiko.productivebeesgenesis.apiculture.production.*;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey;
import java.util.ArrayList;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** 共用单蜂记录格式；容器布局与载体身份由所有权记录再次核对。 */
public final class BeeRecordCodec {
	static CompoundTag encode(BeeMemberState state) {
		var tag = new CompoundTag(); if (state == null) return tag;
		tag.putInt("samplingVersion", 8);
		tag.putUUID("member", state.member()); tag.putLong("revision", state.revision());
		tag.putBoolean("networkPowered", state.networkPowered()); tag.putLong("energy", state.energy()); tag.putLong("capacity", state.energyCapacity());
		var list = new ListTag();
		for (var bee : state.bees()) list.add(encodeBee(bee, true));
		tag.put("bees", list); tag.put("feeding", FeedingRecordCodec.encode(state.feeding())); return tag;
	}
	static BeeMemberState decode(CompoundTag tag, Consumer<ProductKey> validate,
			Consumer<com.ayoshiko.productivebeesgenesis.apiculture.feeding.FeedingItem> validateFeeding, int schema) {
		BeeSchemaVersion.requireSupported(schema);
		if (tag.isEmpty()) return null;
		boolean legacy = schema == 6;
		if (legacy) fields(tag, "member", "revision", "energy", "capacity", "bees", "feeding", "networkPowered");
		else {
			fields(tag, "samplingVersion", "member", "revision", "energy", "capacity", "bees", "feeding", "networkPowered");
			if (StrictNbt.integer(tag, "samplingVersion") != (schema >= 14 ? schema - 9 : Math.min(schema, 10) - 6)) throw new IllegalArgumentException("Unsupported bee sampling version");
		}
		var member = StrictNbt.uuid(tag, "member"); var list = StrictNbt.list(tag, "bees");
		var feedingTag = StrictNbt.compound(tag, "feeding");
		int capacity = schema >= 15 && !feedingTag.isEmpty() ? StrictNbt.list(feedingTag, "slots").size() : 3;
		if (capacity > com.ayoshiko.productivebeesgenesis.apiculture.capacity.ApiaryWorkLayout.MAX_BEES || list.size() > capacity) throw new IllegalArgumentException("Invalid apiary bee capacity");
		var bees = new ArrayList<BeeRecord>();
		for (var raw : list) bees.add(decodeBee((CompoundTag) raw, member, validate, schema, true));
		return new BeeMemberState(member, StrictNbt.number(tag, "revision"), StrictNbt.number(tag, "energy"), StrictNbt.number(tag, "capacity"), bees,
				FeedingRecordCodec.decode(feedingTag, validateFeeding, capacity), StrictNbt.bool(tag, "networkPowered"));
	}
	public static CompoundTag encodeBee(BeeRecord bee) {
		return encodeBee(bee, true);
	}
	private static CompoundTag encodeBee(BeeRecord bee, boolean controlled) {
		var value = new CompoundTag(); value.putUUID("id", bee.id()); value.putInt("slot", bee.slot());
		value.put("original", bee.originalSlot().copy()); value.put("plan", plan(bee.plan()));
		value.putLong("revision", bee.revision()); value.putInt("progress", bee.progress());
		if (controlled) value.putBoolean("enabled", bee.enabled());
		value.putLong("seed", bee.random().seed()); value.putLong("cursor", bee.random().cursor());
		value.putLong("pending", bee.pendingCycles()); value.put("frozenOutputs", ProductRecordCodec.amounts(bee.frozenOutputs()));
		var sampling = new CompoundTag();
		if (bee.sampling() != null) {
			sampling.put("rolls", ProductRecordCodec.amount(bee.sampling().rolls())); sampling.putInt("output", bee.sampling().output());
			sampling.put("consumed", ProductRecordCodec.amount(bee.sampling().consumed()));
		}
		value.put("sampling", sampling);
		if (bee.generation() != null) value.put("generation", BeeGenerationCodec.encode(bee.generation()));
		return value;
	}
	/** 独立机器只接受当前完整记录；旧网络格式的兼容仍由网络容器决定。 */
	public static BeeRecord decodeBee(CompoundTag value, java.util.UUID member, Consumer<ProductKey> validate) {
		return decodeBee(value, member, validate, false);
	}
	/** 旧一体机 schema 1–4 使用原单蜂格式；不能根据缺字段猜测新格式。 */
	public static BeeRecord decodeBee(CompoundTag value, java.util.UUID member, Consumer<ProductKey> validate, boolean legacyMachine) {
		return decodeBee(value, member, validate, legacyMachine ? 8 : 17, !legacyMachine);
	}
	public static BeeRecord decodeMachineBee(CompoundTag value, java.util.UUID member, Consumer<ProductKey> validate, int machineSchema) {
		int schema = 14;
		if (machineSchema < 5) schema = 8;
		else if (machineSchema == 5) schema = 10;
		else if (machineSchema >= 8) schema = 17;
		else if (machineSchema == 7) schema = 16;
		return decodeBee(value, member, validate, schema, machineSchema >= 6);
	}
	private static BeeRecord decodeBee(CompoundTag value, java.util.UUID member, Consumer<ProductKey> validate, int schema, boolean controlled) {
		boolean legacy = schema == 6;
		var expected = new java.util.HashSet<>(Set.of("id", "slot", "original", "plan", "revision", "progress", "pending", "frozen"));
		if (!legacy) { expected.add("seed"); expected.add("cursor"); }
		if (controlled && schema >= 9) expected.add("enabled");
		if (schema >= 14) { expected.remove("frozen"); expected.add("frozenOutputs"); expected.add("sampling"); }
		else if (schema >= 10) expected.add("frozenFluid");
		if (schema >= 16 && value.contains("generation")) expected.add("generation");
		if (!value.getAllKeys().equals(expected)) throw new IllegalArgumentException("Unknown or missing bee fields");
		var id = StrictNbt.uuid(value, "id");
		var random = legacy ? BeeCycleRandom.initial(id) : new BeeCycleRandom(StrictNbt.number(value, "seed"), StrictNbt.number(value, "cursor"));
		if (schema >= 14) {
			var raw = StrictNbt.compound(value, "sampling"); BeeSamplingCursor sampling = null;
			if (!raw.isEmpty()) {
				fields(raw, "rolls", "output", "consumed");
				sampling = new BeeSamplingCursor(ProductRecordCodec.readAmount(raw, "rolls"), StrictNbt.integer(raw, "output"), ProductRecordCodec.readAmount(raw, "consumed"));
			}
			return new BeeRecord(id, member, StrictNbt.integer(value, "slot"), new AssetImage(StrictNbt.compound(value, "original")),
					readPlan(StrictNbt.compound(value, "plan"), validate, schema), StrictNbt.number(value, "revision"), StrictNbt.integer(value, "progress"),
					StrictNbt.number(value, "pending"), new ProductRecordCodec(validate).readAmounts(StrictNbt.list(value, "frozenOutputs")), random, StrictNbt.bool(value, "enabled"), sampling,
					value.contains("generation") ? BeeGenerationCodec.decode(StrictNbt.compound(value, "generation"), validate, schema >= 17) : null);
		}
		return new BeeRecord(id, member, StrictNbt.integer(value, "slot"),
				new AssetImage(StrictNbt.compound(value, "original")), readPlan(StrictNbt.compound(value, "plan"), validate, schema),
				StrictNbt.number(value, "revision"), StrictNbt.integer(value, "progress"), StrictNbt.number(value, "pending"), ProductRecordCodec.readAmount(value, "frozen"), random, !controlled || schema < 9 || StrictNbt.bool(value, "enabled"),
				schema < 10 ? com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductAmount.ZERO : ProductRecordCodec.readAmount(value, "frozenFluid"));
	}
	private static CompoundTag plan(StaticBeePlan plan) {
		var tag = new CompoundTag(); tag.putString("type", plan.beeType()); tag.putString("recipe", plan.recipe());
		tag.putLong("recipeRevision", plan.recipeRevision()); tag.putLong("capabilityRevision", plan.capabilityRevision());
		tag.putInt("ticks", plan.cycleTicks()); tag.putLong("cost", plan.energyPerTick()); tag.putInt("productivity", plan.productivity());
		tag.putFloat("multiplier", plan.productionMultiplier());
		if (plan.generator() != BeeGenerator.STATIC) {
			tag.putString("generator", plan.generator().name());
			tag.putBoolean("generatedCombBlock", plan.generatedCombBlock());
		}
		var outputs = new ListTag();
		for (var output : plan.outputs()) {
			var item = new CompoundTag(); item.put("source", ProductRecordCodec.key(output.source())); item.put("key", ProductRecordCodec.key(output.output()));
			item.putInt("min", output.minimum()); item.putInt("max", output.maximum()); item.putFloat("chance", output.chance()); item.putString("basis", output.basis().name()); outputs.add(item);
		}
		tag.put("outputs", outputs);
		tag.putBoolean("genes", plan.genesAffectWork()); tag.putString("behavior", plan.traits().behavior().name());
		tag.putString("weather", plan.traits().weatherTolerance().name()); return tag;
	}
	private static StaticBeePlan readPlan(CompoundTag tag, Consumer<ProductKey> validate, int schema) {
		if (schema >= 14) {
			var expected = new java.util.HashSet<>(Set.of("type", "recipe", "recipeRevision", "capabilityRevision", "ticks", "cost", "productivity", "genes", "behavior", "weather", "multiplier", "outputs"));
			if (schema >= 16 && tag.contains("generator")) expected.addAll(Set.of("generator", "generatedCombBlock"));
			if (!tag.getAllKeys().equals(expected)) throw new IllegalArgumentException("Unknown or missing bee plan fields");
			if (schema < 17 && tag.getString("generator").equals(BeeGenerator.MYRIAD.name()))
				throw new IllegalArgumentException("Myriad plan inside an old bee schema");
			if (!tag.contains("multiplier", Tag.TAG_FLOAT)) throw new IllegalArgumentException("Invalid bee multiplier type");
			var outputs = new ArrayList<BeeOutputPlan>(); var products = new ProductRecordCodec(validate);
			for (var raw : StrictNbt.list(tag, "outputs")) {
				var output = (CompoundTag) raw; fields(output, "source", "key", "min", "max", "chance", "basis");
				if (!output.contains("chance", Tag.TAG_FLOAT)) throw new IllegalArgumentException("Invalid bee chance type");
				outputs.add(new BeeOutputPlan(products.readKey(StrictNbt.compound(output, "source")), products.readKey(StrictNbt.compound(output, "key")),
						StrictNbt.integer(output, "min"), StrictNbt.integer(output, "max"), output.getFloat("chance"), StrictNbt.choice(output, "basis", BeeOutputPlan.Basis.class)));
			}
			return new StaticBeePlan(StrictNbt.string(tag, "type"), StrictNbt.string(tag, "recipe"), StrictNbt.number(tag, "recipeRevision"),
					StrictNbt.number(tag, "capabilityRevision"), StrictNbt.integer(tag, "ticks"), StrictNbt.number(tag, "cost"), StrictNbt.integer(tag, "productivity"),
					StrictNbt.bool(tag, "genes"), new BeeWorkConditions.Traits(StrictNbt.choice(tag, "behavior", BeeWorkConditions.Behavior.class),
						StrictNbt.choice(tag, "weather", BeeWorkConditions.WeatherTolerance.class)), tag.getFloat("multiplier"), outputs,
					tag.contains("generator") ? StrictNbt.choice(tag, "generator", BeeGenerator.class) : BeeGenerator.STATIC,
					tag.contains("generator") && StrictNbt.bool(tag, "generatedCombBlock"));
		}
		boolean legacy = schema == 6;
		if (legacy) fields(tag, "type", "recipe", "recipeRevision", "capabilityRevision", "ticks", "cost", "productivity", "genes", "behavior", "weather", "output", "count");
		else {
			if (schema == 7) fields(tag, "type", "recipe", "recipeRevision", "capabilityRevision", "ticks", "cost", "productivity", "genes", "behavior", "weather", "output", "count", "multiplier");
			else if (schema < 10) fields(tag, "type", "recipe", "recipeRevision", "capabilityRevision", "ticks", "cost", "productivity", "genes", "behavior", "weather", "output", "count", "multiplier", "sourceOutput");
			else fields(tag, "type", "recipe", "recipeRevision", "capabilityRevision", "ticks", "cost", "productivity", "genes", "behavior", "weather", "output", "count", "multiplier", "sourceOutput", "fluidOutput", "fluidPerCycle");
			if (!tag.contains("multiplier", Tag.TAG_FLOAT)) throw new IllegalArgumentException("Invalid bee multiplier type");
		}
		var products = new ProductRecordCodec(validate);
		var output = products.readKey(StrictNbt.compound(tag, "output"));
		var source = output;
		if (schema >= 8) {
			var sourceTag = StrictNbt.compound(tag, "sourceOutput"); fields(sourceTag, "kind", "id", "components");
			source = products.readKey(sourceTag);
		}
		ProductKey fluid = null; int fluidCount = 0;
		if (schema >= 10) {
			var fluidTag = StrictNbt.compound(tag, "fluidOutput"); fluidCount = StrictNbt.integer(tag, "fluidPerCycle");
			if (!fluidTag.isEmpty()) { fields(fluidTag, "kind", "id", "components"); fluid = products.readKey(fluidTag); }
		}
		return new StaticBeePlan(StrictNbt.string(tag, "type"), StrictNbt.string(tag, "recipe"), StrictNbt.number(tag, "recipeRevision"),
				StrictNbt.number(tag, "capabilityRevision"), StrictNbt.integer(tag, "ticks"), StrictNbt.number(tag, "cost"), StrictNbt.integer(tag, "productivity"),
				StrictNbt.bool(tag, "genes"), new BeeWorkConditions.Traits(StrictNbt.choice(tag, "behavior", BeeWorkConditions.Behavior.class),
				StrictNbt.choice(tag, "weather", BeeWorkConditions.WeatherTolerance.class)), output,
				StrictNbt.integer(tag, "count"), legacy ? 1 : tag.getFloat("multiplier"), source, fluid, fluidCount);
	}
	private static void fields(CompoundTag tag, String... names) {
		if (!tag.getAllKeys().equals(Set.of(names))) throw new IllegalArgumentException("Unknown or missing bee state fields");
	}
	private BeeRecordCodec() { }
}
