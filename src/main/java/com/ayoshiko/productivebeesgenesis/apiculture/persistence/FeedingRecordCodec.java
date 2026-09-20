package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import com.ayoshiko.productivebeesgenesis.apiculture.feeding.*;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.AssetImage;
import java.util.ArrayList;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.nbt.*;

final class FeedingRecordCodec {
	static CompoundTag encode(FeedingSlotStore store) {
		var tag = new CompoundTag(); if (store == null) return tag;
		tag.putLong("revision", store.revision()); tag.putInt("legacySlots", store.legacySlots()); tag.putString("source", store.sourceFingerprint());
		var slots = new ListTag();
		for (var slot : store.slots()) {
			var value = new CompoundTag(); value.putInt("count", slot.count()); value.putBoolean("disabled", slot.disabled()); value.putInt("group", slot.group());
			value.put("item", slot.item() == null ? new CompoundTag() : slot.item().unit().copy()); value.putInt("limit", slot.item() == null ? 0 : slot.item().limit()); slots.add(value);
		}
		tag.put("slots", slots); return tag;
	}
	static FeedingSlotStore decode(CompoundTag tag, Consumer<FeedingItem> validate) {
		if (tag.isEmpty()) return null;
		if (!tag.getAllKeys().equals(Set.of("revision", "legacySlots", "source", "slots"))) throw new IllegalArgumentException("Missing feeding account fields");
		var list = StrictNbt.list(tag, "slots"); if (list.size() != 3) throw new IllegalArgumentException("Unverified feeding capacity");
		var slots = new ArrayList<FeedingSlotStore.Slot>();
		for (var raw : list) {
			var value = (CompoundTag) raw;
			if (!value.getAllKeys().equals(Set.of("count", "disabled", "group", "item", "limit"))) throw new IllegalArgumentException("Invalid feeding slot record");
			var itemTag = StrictNbt.compound(value, "item"); int limit = StrictNbt.integer(value, "limit");
			FeedingItem item = itemTag.isEmpty() ? null : new FeedingItem(new AssetImage(itemTag), limit);
			if (item != null) validate.accept(item); else if (limit != 0) throw new IllegalArgumentException("Empty feeding slot has item metadata");
			slots.add(new FeedingSlotStore.Slot(item, StrictNbt.integer(value, "count"), StrictNbt.bool(value, "disabled"), StrictNbt.integer(value, "group")));
		}
		return new FeedingSlotStore(StrictNbt.number(tag, "revision"), StrictNbt.integer(tag, "legacySlots"), StrictNbt.string(tag, "source"), slots);
	}
	private FeedingRecordCodec() { }
}
