package com.ayoshiko.productivebeesgenesis.apiculture.ownership;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

/** 机器特有资产：不含通用物品槽、Mekanism 升级与能量，也不承担交接提交。 */
public interface MachineAssetSection {
	/** 不同工厂基类提供同一资产段，外层交接无需按继承层级强转。 */
	interface Provider {
		MachineAssetSection ownershipAssets();
	}
	default boolean prepared() { return true; }
	CompoundTag capture(HolderLookup.Provider registries);
	void restore(CompoundTag tag, HolderLookup.Provider registries);
	void clear(HolderLookup.Provider registries);
	void validateWorld(ServerLevel level);
	boolean empty();
}
