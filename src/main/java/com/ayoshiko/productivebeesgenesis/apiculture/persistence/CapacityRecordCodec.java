package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import com.ayoshiko.productivebeesgenesis.apiculture.capacity.MemberCapabilitySnapshot;
import com.ayoshiko.productivebeesgenesis.apiculture.capacity.VirtualLaneState;
import com.ayoshiko.productivebeesgenesis.apiculture.capacity.WorkCapacity;
import com.ayoshiko.productivebeesgenesis.apiculture.capacity.WorkKey;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

final class CapacityRecordCodec {
	private CapacityRecordCodec() { }
	static CompoundTag origin(MemberCapabilitySnapshot.Origin origin) {
		var tag = new CompoundTag(); tag.putString("dimension", origin.dimension());
		tag.putInt("x", origin.x()); tag.putInt("y", origin.y()); tag.putInt("z", origin.z()); return tag;
	}
	static MemberCapabilitySnapshot.Origin readOrigin(CompoundTag tag) {
		return new MemberCapabilitySnapshot.Origin(net.minecraft.resources.ResourceLocation.parse(StrictNbt.string(tag, "dimension")).toString(),
				StrictNbt.integer(tag, "x"), StrictNbt.integer(tag, "y"), StrictNbt.integer(tag, "z"));
	}
	static CompoundTag capacity(WorkCapacity capacity) {
		var tag = new CompoundTag(); var work = capacity.work();
		tag.putString("kind", work.kind().name()); tag.putString("id", work.id()); tag.putLong("recipe", work.recipeRevision()); tag.putString("context", work.contextKey());
		tag.putInt("operations", capacity.operationsPerCycle()); tag.putInt("ticks", capacity.cycleTicks());
		tag.putLong("energy", capacity.energyPerOperation()); tag.putLong("laneEnergy", capacity.fullLaneEnergyPerTick());
		tag.putDouble("multiplier", capacity.outputMultiplier()); tag.putDouble("stability", capacity.stabilityBonus());
		var effects = new CompoundTag(); capacity.effects().forEach(effects::putInt); tag.put("effects", effects); return tag;
	}
	static WorkCapacity readCapacity(CompoundTag tag) {
		var work = new WorkKey(StrictNbt.choice(tag, "kind", WorkKey.Kind.class), StrictNbt.string(tag, "id"), StrictNbt.number(tag, "recipe"), StrictNbt.string(tag, "context"));
		Map<String, Integer> effects = new ConcurrentHashMap<>(); var encoded = StrictNbt.compound(tag, "effects");
		for (String key : encoded.getAllKeys()) effects.put(key, StrictNbt.integer(encoded, key));
		return new WorkCapacity(work, StrictNbt.integer(tag, "operations"), StrictNbt.integer(tag, "ticks"), StrictNbt.number(tag, "energy"),
				StrictNbt.number(tag, "laneEnergy"), StrictNbt.decimal(tag, "multiplier"), StrictNbt.decimal(tag, "stability"), effects);
	}
	static CompoundTag member(MemberCapabilitySnapshot member) {
		var tag = new CompoundTag(); tag.putUUID("id", member.memberId()); tag.putLong("revision", member.revision());
		tag.putString("machine", member.machineId()); tag.put("origin", origin(member.origin())); tag.putString("availability", member.availability().name());
		tag.putInt("bees", member.beeSlots()); tag.putInt("lanes", member.laneCount());
		var alternatives = new ListTag(); member.alternatives().forEach(value -> alternatives.add(capacity(value))); tag.put("alternatives", alternatives); return tag;
	}
	static MemberCapabilitySnapshot readMember(CompoundTag tag) {
		var alternatives = new ArrayList<WorkCapacity>();
		StrictNbt.list(tag, "alternatives").forEach(raw -> alternatives.add(readCapacity((CompoundTag) raw)));
		return new MemberCapabilitySnapshot(StrictNbt.uuid(tag, "id"), StrictNbt.number(tag, "revision"), StrictNbt.string(tag, "machine"),
				readOrigin(StrictNbt.compound(tag, "origin")), StrictNbt.choice(tag, "availability", MemberCapabilitySnapshot.Availability.class),
				StrictNbt.integer(tag, "bees"), StrictNbt.integer(tag, "lanes"), alternatives);
	}
	static CompoundTag lane(VirtualLaneState lane) {
		var tag = new CompoundTag(); tag.putUUID("member", lane.memberId()); tag.putLong("revision", lane.capabilityRevision());
		tag.putInt("index", lane.laneIndex()); tag.putInt("progress", lane.progress()); tag.put("capacity", capacity(lane.capability())); return tag;
	}
	static VirtualLaneState readLane(CompoundTag tag) {
		return new VirtualLaneState(StrictNbt.uuid(tag, "member"), StrictNbt.number(tag, "revision"), StrictNbt.integer(tag, "index"),
				readCapacity(StrictNbt.compound(tag, "capacity")), StrictNbt.integer(tag, "progress"));
	}
}
