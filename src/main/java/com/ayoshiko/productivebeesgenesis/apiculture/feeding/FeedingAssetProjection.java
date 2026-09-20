package com.ayoshiko.productivebeesgenesis.apiculture.feeding;

import com.ayoshiko.productivebeesgenesis.apiculture.ownership.AssetImage;
import net.minecraft.nbt.*;

/** 原九格映像与逐蜂位账户之间只有一次所有权移动。 */
public final class FeedingAssetProjection {
	public static final String SLOTS = "productivebeesgenesis_feeder_slots", DISABLED = "productivebeesgenesis_feeder_disabled", MARKER = "feedingAuthority";
	public static String fingerprint(AssetImage source) {
		var extra = source.copy().getCompound("extra"); var tag = new CompoundTag();
		if (!extra.contains(SLOTS, Tag.TAG_LIST)) throw new IllegalArgumentException("Missing feeder source");
		tag.put(SLOTS, extra.get(SLOTS).copy()); if (extra.contains(DISABLED)) tag.put(DISABLED, extra.get(DISABLED).copy());
		return new AssetImage(tag).fingerprint();
	}
	public static AssetImage detach(AssetImage source, FeedingSlotStore store) {
		if (store.revision() != 0 || !store.sourceFingerprint().equals(fingerprint(source))) throw new IllegalArgumentException("Stale feeding migration");
		var tag = source.copy(); if (tag.contains(MARKER)) throw new IllegalArgumentException("Feeding already migrated");
		tag.putBoolean(MARKER, true); var extra = tag.getCompound("extra"); extra.remove(SLOTS); extra.remove(DISABLED); return new AssetImage(tag);
	}
	public static void validate(AssetImage residual, FeedingSlotStore store) {
		var tag = residual.copy();
		if (store == null ? tag.contains(MARKER) || !tag.getCompound("extra").contains(SLOTS, Tag.TAG_LIST) || tag.getCompound("extra").getList(SLOTS, Tag.TAG_COMPOUND).size() != 9
				: !tag.contains(MARKER, 1) || tag.getByte(MARKER) != 1
				|| tag.getCompound("extra").contains(SLOTS) || tag.getCompound("extra").contains(DISABLED)) throw new IllegalArgumentException("Missing or duplicated feeding authority");
	}
	public static AssetImage attach(AssetImage residual, FeedingSlotStore store) {
		validate(residual, store); if (store == null) return residual;
		var tag = residual.copy(); tag.remove(MARKER); var slots = new ListTag(); long disabled = 0;
		for (int i = 0; i < store.legacySlots(); i++) {
			var slotTag = new CompoundTag();
			if (i < store.slots().size()) {
				var slot = store.slots().get(i);
				if (slot.item() != null) slotTag.put("item", slot.item().stack(slot.count()));
				if (slot.disabled()) disabled |= 1L << i;
			}
			slots.add(slotTag);
		}
		var extra = tag.getCompound("extra"); extra.put(SLOTS, slots);
		if (disabled != 0) extra.putLongArray(DISABLED, new long[]{disabled}); return new AssetImage(tag);
	}
	private FeedingAssetProjection() { }
}
