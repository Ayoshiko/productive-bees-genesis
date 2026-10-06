package com.ayoshiko.productivebeesgenesis.apiculture.bridge;

import net.minecraft.nbt.CompoundTag;

/** 可选 AE2 的生命周期边界；常驻方块及菜单不加载 AE2 类型。 */
public interface MeBridgeLink {
	void connect();
	MeBridgeStatus status();
	CompoundTag save();
	void close();
}
