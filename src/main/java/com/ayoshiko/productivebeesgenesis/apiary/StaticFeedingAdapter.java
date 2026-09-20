package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.apiculture.feeding.*;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.AssetImage;
import com.ayoshiko.productivebeesgenesis.util.BeeInfoHelper;
import java.util.*;
import mekanism.api.SerializerHelper;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/** 固定版本物品 codec 与花源规则的只读边界；不调用旧喂食器 tick 或槽写入口。 */
public final class StaticFeedingAdapter {
	public static FeedingSlotStore migrate(AssetImage source, HolderLookup.Provider registries) {
		var extra = source.copy().getCompound("extra"); var rawSlots = extra.getList(FeedingAssetProjection.SLOTS, Tag.TAG_COMPOUND);
		if (!extra.contains(FeedingAssetProjection.SLOTS, Tag.TAG_LIST) || rawSlots.size() != 9) throw new IllegalArgumentException("Unverified physical feeder layout");
		long mask = 0;
		if (extra.contains(FeedingAssetProjection.DISABLED)) {
			if (!extra.contains(FeedingAssetProjection.DISABLED, Tag.TAG_LONG_ARRAY)) throw new IllegalArgumentException("Invalid feeder disable mask");
			var words = extra.getLongArray(FeedingAssetProjection.DISABLED);
			if (words.length != 1 || (words[0] >>> 9) != 0) throw new IllegalArgumentException("Feeder disable mask exceeds source"); mask = words[0];
		}
		var slots = new ArrayList<FeedingSlotStore.Slot>();
		for (int i = 0; i < 3; i++) slots.add(new FeedingSlotStore.Slot(null, 0, false, i));
		for (int sourceIndex = 0; sourceIndex < rawSlots.size(); sourceIndex++) {
			var raw = rawSlots.getCompound(sourceIndex); if (raw.isEmpty()) continue;
			if (!raw.getAllKeys().equals(Set.of("item")) || !raw.contains("item", Tag.TAG_COMPOUND)) throw new IllegalArgumentException("Invalid feeder slot data");
			var stack = parse(raw.getCompound("item"), registries);
			if (PbUpgradeInventorySlot.isValidUpgradeItem(stack) || !raw.get("item").equals(SerializerHelper.saveOversized(registries, stack))) throw new IllegalArgumentException("Feeder item cannot round-trip");
			var item = new FeedingItem(new AssetImage((CompoundTag) SerializerHelper.saveOversized(registries, stack.copyWithCount(1))), Math.min(64, stack.getMaxStackSize()));
			boolean disabled = (mask & 1L << sourceIndex) != 0; int remaining = stack.getCount();
			// 先合并同身份／同禁用状态，再优先填原位置，避免可合并的旧栈误占满三格。
			for (int pass = 0; pass < 2 && remaining > 0; pass++) for (int offset = 0; offset < 3 && remaining > 0; offset++) {
				int target = pass == 1 && sourceIndex < 3 ? (sourceIndex + offset) % 3 : offset;
				var slot = slots.get(target);
				if (pass == 0 ? slot.item() == null || !item.equals(slot.item()) || slot.disabled() != disabled : slot.item() != null) continue;
				int accepted = Math.min(remaining, item.limit() - slot.count());
				if (accepted > 0) { slots.set(target, new FeedingSlotStore.Slot(item, slot.count() + accepted, disabled, target)); remaining -= accepted; }
			}
			if (remaining != 0) throw new IllegalArgumentException("All nine feeding slots must fit the three bee feeding slots before migration");
		}
		return new FeedingSlotStore(0, 9, FeedingAssetProjection.fingerprint(source), slots);
	}
	public static void validate(FeedingItem item, HolderLookup.Provider registries) {
		var stack = parse(item.unit().copy(), registries);
		if (item.limit() != Math.min(64, stack.getMaxStackSize()) || PbUpgradeInventorySlot.isValidUpgradeItem(stack)
				|| !item.unit().copy().equals(SerializerHelper.saveOversized(registries, stack))) throw new IllegalArgumentException("Unrestorable feeding item");
	}
	public static boolean flower(FeedingSlotStore store, int beeSlot, ResourceLocation type, HolderLookup.Provider registries) {
		var pref = BeeInfoHelper.getFlowerPreference(type);
		if (!BeeInfoHelper.FlowerPreference.TYPE_BLOCKS.equals(pref.flowerType()) || !pref.hasFlowerDefinition()) return false;
		return store.matches(beeSlot, item -> BlockFlowerMatcher.matches(parse(item.unit().copy(), registries), pref));
	}
	private static ItemStack parse(CompoundTag tag, HolderLookup.Provider registries) {
		return SerializerHelper.OVERSIZED_ITEM_CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE), tag).getOrThrow();
	}
	private StaticFeedingAdapter() { }
}
