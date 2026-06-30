package com.ayoshiko.productivebeesgenesis.mixin.accessor;

import com.jerry.mekextras.common.tile.factory.TileEntityExtraFactory;
import mekanism.common.inventory.slot.EnergyInventorySlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * TileEntityExtraFactory 访问器：暴露ME基类的私有/包私有字段供外部包访问
 * <br/>
 * ME的TileEntityExtraFactory中activeStates和lastUsage是私有的，
 * energySlot是包私有的，
 * PB离心机工厂需要在onUpdateServer中重算整体激活状态并追加PB能量消耗，
 * 在构造函数中重新配置IO和弹出器，
 * 通过Accessor Mixin暴露getter/setter。
 */
@Mixin(value = TileEntityExtraFactory.class, remap = false)
public interface TileEntityExtraFactoryAccessor {

	/** 暴露activeStates数组 — 用于onUpdateServer中重算整体激活状态 */
	@Accessor("activeStates")
	boolean[] productivebeesgenesis$getActiveStates();

	/** 暴露lastUsage setter — 用于更新包含PB处理的能量消耗 */
	@Accessor("lastUsage")
	void productivebeesgenesis$setLastUsage(long value);

	/** 暴露energySlot — 用于构造函数中重新配置IO和getUpgradeData */
	@Accessor("energySlot")
	EnergyInventorySlot productivebeesgenesis$getEnergySlot();
}
