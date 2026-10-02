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

/** 当前每成员最多三个蜂位；恢复有界，任何错误都交给整域隔离路径。 */
final class BeeRecordCodec {
	static CompoundTag encode(BeeMemberState state) {
		var tag = new CompoundTag(); if (state == null) return tag;
		tag.putInt("samplingVersion", 2);
		tag.putUUID("member", state.member()); tag.putLong("revision", state.revision());
		tag.putBoolean("networkPowered", state.networkPowered()); tag.putLong("energy", state.energy()); tag.putLong("capacity", state.energyCapacity());
		var list = new ListTag();
		for (var bee : state.bees()) {
			var value = new CompoundTag(); value.putUUID("id", bee.id()); value.putInt("slot", bee.slot());
			value.put("original", bee.originalSlot().copy()); value.put("plan", plan(bee.plan()));
			value.putLong("revision", bee.revision()); value.putInt("progress", bee.progress());
			value.putLong("seed", bee.random().seed()); value.putLong("cursor", bee.random().cursor());
			value.putLong("pending", bee.pendingCycles()); value.put("frozen", ProductRecordCodec.amount(bee.frozen())); list.add(value);
		}
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
			if (StrictNbt.integer(tag, "samplingVersion") != (schema == 7 ? 1 : 2)) throw new IllegalArgumentException("Unsupported bee sampling version");
		}
		var member = StrictNbt.uuid(tag, "member"); var list = StrictNbt.list(tag, "bees");
		if (list.size() > 3) throw new IllegalArgumentException("Unverified factory bee state");
		var bees = new ArrayList<BeeRecord>();
		for (var raw : list) {
			var value = (CompoundTag) raw;
			if (legacy) fields(value, "id", "slot", "original", "plan", "revision", "progress", "pending", "frozen");
			else fields(value, "id", "slot", "original", "plan", "revision", "progress", "pending", "frozen", "seed", "cursor");
			var id = StrictNbt.uuid(value, "id");
			var random = legacy ? BeeCycleRandom.initial(id) : new BeeCycleRandom(StrictNbt.number(value, "seed"), StrictNbt.number(value, "cursor"));
			bees.add(new BeeRecord(id, member, StrictNbt.integer(value, "slot"),
					new AssetImage(StrictNbt.compound(value, "original")), readPlan(StrictNbt.compound(value, "plan"), validate, schema),
					StrictNbt.number(value, "revision"), StrictNbt.integer(value, "progress"), StrictNbt.number(value, "pending"), ProductRecordCodec.readAmount(value, "frozen"), random));
		}
		return new BeeMemberState(member, StrictNbt.number(tag, "revision"), StrictNbt.number(tag, "energy"), StrictNbt.number(tag, "capacity"), bees,
				FeedingRecordCodec.decode(StrictNbt.compound(tag, "feeding"), validateFeeding), StrictNbt.bool(tag, "networkPowered"));
	}
	private static CompoundTag plan(StaticBeePlan plan) {
		var tag = new CompoundTag(); tag.putString("type", plan.beeType()); tag.putString("recipe", plan.recipe());
		tag.putLong("recipeRevision", plan.recipeRevision()); tag.putLong("capabilityRevision", plan.capabilityRevision());
		tag.putInt("ticks", plan.cycleTicks()); tag.putLong("cost", plan.energyPerTick()); tag.putInt("productivity", plan.productivity());
		tag.putFloat("multiplier", plan.productionMultiplier()); tag.put("sourceOutput", ProductRecordCodec.key(plan.sourceOutput()));
		tag.putBoolean("genes", plan.genesAffectWork()); tag.putString("behavior", plan.traits().behavior().name());
		tag.putString("weather", plan.traits().weatherTolerance().name()); tag.put("output", ProductRecordCodec.key(plan.output())); tag.putInt("count", plan.count()); return tag;
	}
	private static StaticBeePlan readPlan(CompoundTag tag, Consumer<ProductKey> validate, int schema) {
		boolean legacy = schema == 6;
		if (legacy) fields(tag, "type", "recipe", "recipeRevision", "capabilityRevision", "ticks", "cost", "productivity", "genes", "behavior", "weather", "output", "count");
		else {
			if (schema == 7) fields(tag, "type", "recipe", "recipeRevision", "capabilityRevision", "ticks", "cost", "productivity", "genes", "behavior", "weather", "output", "count", "multiplier");
			else fields(tag, "type", "recipe", "recipeRevision", "capabilityRevision", "ticks", "cost", "productivity", "genes", "behavior", "weather", "output", "count", "multiplier", "sourceOutput");
			if (!tag.contains("multiplier", Tag.TAG_FLOAT)) throw new IllegalArgumentException("Invalid bee multiplier type");
		}
		var products = new ProductRecordCodec(validate);
		var output = products.readKey(StrictNbt.compound(tag, "output"));
		var source = output;
		if (schema >= 8) {
			var sourceTag = StrictNbt.compound(tag, "sourceOutput"); fields(sourceTag, "kind", "id", "components");
			source = products.readKey(sourceTag);
		}
		return new StaticBeePlan(StrictNbt.string(tag, "type"), StrictNbt.string(tag, "recipe"), StrictNbt.number(tag, "recipeRevision"),
				StrictNbt.number(tag, "capabilityRevision"), StrictNbt.integer(tag, "ticks"), StrictNbt.number(tag, "cost"), StrictNbt.integer(tag, "productivity"),
				StrictNbt.bool(tag, "genes"), new BeeWorkConditions.Traits(StrictNbt.choice(tag, "behavior", BeeWorkConditions.Behavior.class),
				StrictNbt.choice(tag, "weather", BeeWorkConditions.WeatherTolerance.class)), output,
				StrictNbt.integer(tag, "count"), legacy ? 1 : tag.getFloat("multiplier"), source);
	}
	private static void fields(CompoundTag tag, String... names) {
		if (!tag.getAllKeys().equals(Set.of(names))) throw new IllegalArgumentException("Unknown or missing bee state fields");
	}
	private BeeRecordCodec() { }
}
