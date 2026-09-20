package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import com.ayoshiko.productivebeesgenesis.apiculture.ownership.AssetImage;
import com.ayoshiko.productivebeesgenesis.apiculture.production.*;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey;
import java.util.ArrayList;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

/** 当前每成员最多三个蜂位；恢复有界，任何错误都交给整域隔离路径。 */
final class BeeRecordCodec {
	static CompoundTag encode(BeeMemberState state) {
		var tag = new CompoundTag(); if (state == null) return tag;
		tag.putUUID("member", state.member()); tag.putLong("revision", state.revision());
		tag.putLong("energy", state.energy()); tag.putLong("capacity", state.energyCapacity());
		var list = new ListTag();
		for (var bee : state.bees()) {
			var value = new CompoundTag(); value.putUUID("id", bee.id()); value.putInt("slot", bee.slot());
			value.put("original", bee.originalSlot().copy()); value.put("plan", plan(bee.plan()));
			value.putLong("revision", bee.revision()); value.putInt("progress", bee.progress());
			value.putLong("pending", bee.pendingCycles()); value.put("frozen", ProductRecordCodec.amount(bee.frozen())); list.add(value);
		}
		tag.put("bees", list); tag.put("feeding", FeedingRecordCodec.encode(state.feeding())); return tag;
	}
	static BeeMemberState decode(CompoundTag tag, Consumer<ProductKey> validate, Consumer<com.ayoshiko.productivebeesgenesis.apiculture.feeding.FeedingItem> validateFeeding) {
		if (tag.isEmpty()) return null;
		fields(tag, "member", "revision", "energy", "capacity", "bees", "feeding");
		var member = StrictNbt.uuid(tag, "member"); var list = StrictNbt.list(tag, "bees");
		if (list.size() > 3) throw new IllegalArgumentException("Unverified factory bee state");
		var bees = new ArrayList<BeeRecord>();
		for (var raw : list) {
			var value = (CompoundTag) raw; fields(value, "id", "slot", "original", "plan", "revision", "progress", "pending", "frozen");
			bees.add(new BeeRecord(StrictNbt.uuid(value, "id"), member, StrictNbt.integer(value, "slot"),
					new AssetImage(StrictNbt.compound(value, "original")), readPlan(StrictNbt.compound(value, "plan"), validate),
					StrictNbt.number(value, "revision"), StrictNbt.integer(value, "progress"), StrictNbt.number(value, "pending"), ProductRecordCodec.readAmount(value, "frozen")));
		}
		return new BeeMemberState(member, StrictNbt.number(tag, "revision"), StrictNbt.number(tag, "energy"), StrictNbt.number(tag, "capacity"), bees,
				FeedingRecordCodec.decode(StrictNbt.compound(tag, "feeding"), validateFeeding));
	}
	private static CompoundTag plan(StaticBeePlan plan) {
		var tag = new CompoundTag(); tag.putString("type", plan.beeType()); tag.putString("recipe", plan.recipe());
		tag.putLong("recipeRevision", plan.recipeRevision()); tag.putLong("capabilityRevision", plan.capabilityRevision());
		tag.putInt("ticks", plan.cycleTicks()); tag.putLong("cost", plan.energyPerTick()); tag.putInt("productivity", plan.productivity());
		tag.putBoolean("genes", plan.genesAffectWork()); tag.putString("behavior", plan.traits().behavior().name());
		tag.putString("weather", plan.traits().weatherTolerance().name()); tag.put("output", ProductRecordCodec.key(plan.output())); tag.putInt("count", plan.count()); return tag;
	}
	private static StaticBeePlan readPlan(CompoundTag tag, Consumer<ProductKey> validate) {
		fields(tag, "type", "recipe", "recipeRevision", "capabilityRevision", "ticks", "cost", "productivity", "genes", "behavior", "weather", "output", "count");
		return new StaticBeePlan(StrictNbt.string(tag, "type"), StrictNbt.string(tag, "recipe"), StrictNbt.number(tag, "recipeRevision"),
				StrictNbt.number(tag, "capabilityRevision"), StrictNbt.integer(tag, "ticks"), StrictNbt.number(tag, "cost"), StrictNbt.integer(tag, "productivity"),
				StrictNbt.bool(tag, "genes"), new BeeWorkConditions.Traits(StrictNbt.choice(tag, "behavior", BeeWorkConditions.Behavior.class),
				StrictNbt.choice(tag, "weather", BeeWorkConditions.WeatherTolerance.class)), new ProductRecordCodec(validate).readKey(StrictNbt.compound(tag, "output")), StrictNbt.integer(tag, "count"));
	}
	private static void fields(CompoundTag tag, String... names) {
		if (!tag.getAllKeys().equals(Set.of(names))) throw new IllegalArgumentException("Unknown or missing bee state fields");
	}
	private BeeRecordCodec() { }
}
