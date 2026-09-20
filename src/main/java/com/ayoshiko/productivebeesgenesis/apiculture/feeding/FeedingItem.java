package com.ayoshiko.productivebeesgenesis.apiculture.feeding;

import com.ayoshiko.productivebeesgenesis.apiculture.ownership.AssetImage;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

/** 有限喂食物品的完整序列化身份；不经过产物白名单，也不进入无限账本。 */
public record FeedingItem(AssetImage unit, int limit) {
	public FeedingItem {
		var tag = unit.copy();
		if (limit < 1 || limit > 64 || !tag.contains("id", 8) || ResourceLocation.tryParse(tag.getString("id")) == null
				|| !tag.contains("count", 3) || tag.getInt("count") != 1
				|| !Set.of("id", "count", "components").containsAll(tag.getAllKeys())
				|| tag.contains("components") && !tag.contains("components", 10)) throw new IllegalArgumentException("Invalid feeding item identity");
	}
	public CompoundTag stack(int count) {
		if (count < 1 || count > limit) throw new IllegalArgumentException("Feeding stack exceeds finite slot");
		var tag = unit.copy(); tag.putInt("count", count); return tag;
	}
}
