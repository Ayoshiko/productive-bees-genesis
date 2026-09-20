package com.ayoshiko.productivebeesgenesis.apiculture.production;

import com.ayoshiko.productivebeesgenesis.apiculture.ownership.AssetImage;
import net.minecraft.nbt.ListTag;

/** 蜂与 FE 迁出后从旧映像移除；残余映像不能直接交给物理机恢复。 */
public final class BeeAssetProjection {
	public static final String MARKER = "beeAuthority";
	public static final String SLOTS = "productivebeesgenesis_apiary_bee_slots";
	public static final String PENDING = "productivebeesgenesis_pending_bee_cycles";
	public static AssetImage detach(AssetImage source) {
		var tag = source.copy();
		if (tag.contains(MARKER)) throw new IllegalArgumentException("Already detached bee assets");
		tag.putBoolean(MARKER, true); tag.putLong("energy", 0);
		var extra = tag.getCompound("extra"); extra.put(SLOTS, new ListTag()); extra.remove(PENDING);
		return new AssetImage(tag);
	}
	public static void validate(AssetImage residual, BeeMemberState state) {
		var tag = residual.copy(); var extra = tag.getCompound("extra");
		if (!tag.contains(MARKER, 1) || tag.getByte(MARKER) != 1 || !tag.contains("energy", 4) || tag.getLong("energy") != 0
				|| !tag.contains("energyCapacity", 4) || tag.getLong("energyCapacity") != state.energyCapacity() || !tag.contains("extra", 10)
				|| !extra.contains(SLOTS, 9) || !extra.get(SLOTS).equals(new ListTag()) || extra.contains(PENDING)) throw new IllegalArgumentException("Duplicated bee or energy authority");
	}
	public static void validateMigration(AssetImage source, BeeMemberState state) {
		var tag = source.copy(); var extra = tag.getCompound("extra"); var slots = extra.getList(SLOTS, 10);
		if (state.revision() != 0 || state.energy() != tag.getLong("energy") || state.energyCapacity() != tag.getLong("energyCapacity") || slots.size() != state.bees().size())
			throw new IllegalArgumentException("Bee migration differs from sealed inventory");
		int[] pending = extra.contains(PENDING) ? extra.getCompound(PENDING).getIntArray("counts") : new int[0];
		for (var raw : slots) {
			var slot = (net.minecraft.nbt.CompoundTag) raw; var bee = state.bee(slot.getInt("slot_index"));
			if (bee.revision() != 0 || !bee.frozen().isZero() || !bee.originalSlot().copy().equals(slot) || bee.progress() != slot.getInt("ticks_in_hive")
					|| bee.pendingCycles() != (bee.slot() < pending.length ? pending[bee.slot()] : 0)) throw new IllegalArgumentException("Bee migration lost or duplicated paid work");
		}
	}
	public static AssetImage attach(AssetImage residual, BeeMemberState state) {
		validate(residual, state);
		if (!state.drained()) throw new IllegalStateException("Settle paid bee work before returning this member");
		var tag = residual.copy(); tag.remove(MARKER); tag.putLong("energy", state.energy());
		var list = new ListTag();
		for (var bee : state.bees()) {
			var slot = bee.originalSlot().copy();
			if (bee.revision() > 0) {
				slot.putInt("ticks_in_hive", bee.progress()); slot.putInt("min_occupation_ticks", bee.plan().cycleTicks());
				slot.putFloat("progress", Math.min(1F, (float) bee.progress() / bee.plan().cycleTicks()));
			}
			list.add(slot);
		}
		tag.getCompound("extra").put(SLOTS, list);
		return new AssetImage(tag);
	}
	private BeeAssetProjection() { }
}
