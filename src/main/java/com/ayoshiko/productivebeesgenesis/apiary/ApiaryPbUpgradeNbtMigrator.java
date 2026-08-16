package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.util.SaturatingMath;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/** Restores current and legacy apiary PB-upgrade NBT without applying install caps. */
class ApiaryPbUpgradeNbtMigrator {

	static final String NBT_KEY_PB_UPGRADE_HANDLER_LEGACY =
			"productivebeesgenesis_pb_upgrade_handler";
	static final String NBT_KEY_PB_UPGRADES_LEGACY =
			"productivebeesgenesis_pb_upgrades";

	private final Map<PbUpgradeType, Integer> targetCounts;

	ApiaryPbUpgradeNbtMigrator(@NotNull Map<PbUpgradeType, Integer> targetCounts) {
		this.targetCounts = targetCounts;
	}

	/** Persisted counts are authoritative; profile limits affect future installs only. */
	void restorePersistedCount(@NotNull PbUpgradeType type, int count) {
		if (count > 0) targetCounts.put(type, count);
	}

	/** Migrate the old ItemStackHandler representation into saturating integer counts. */
	void migrateLegacyHandlerNbt(
			@NotNull CompoundTag handlerTag, @NotNull HolderLookup.Provider provider) {
		if (!handlerTag.contains("Items", Tag.TAG_LIST)) return;
		var items = handlerTag.getList("Items", Tag.TAG_COMPOUND);
		for (int i = 0; i < items.size(); i++) {
			ItemStack stack = ItemStack.parseOptional(provider, items.getCompound(i));
			if (stack.isEmpty()) continue;
			PbUpgradeType type = PbUpgradeInventorySlot.getUpgradeType(stack);
			if (type == null || type.isBuiltin()) continue;
			int current = targetCounts.getOrDefault(type, 0);
			targetCounts.put(type,
					SaturatingMath.saturatingAddToInt(current, stack.getCount()));
		}
	}
}
