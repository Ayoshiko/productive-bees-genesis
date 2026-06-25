package com.ayoshiko.productivebeesgenesis.mixin.accessor;

import mekanism.common.inventory.slot.EnergyInventorySlot;
import mekanism.common.tile.factory.TileEntityFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * TileEntityFactory 访问器：暴露包私有/私有字段供外部包访问
 * <br/>
 * TileEntityFactory中的energySlot是包私有的，activeStates和lastUsage是私有的，
 * 无法从外部包直接访问。通过Accessor Mixin暴露getter/setter。
 */
@Mixin(value = TileEntityFactory.class, remap = false)
public interface TileEntityFactoryAccessor {

    @Accessor("energySlot")
    EnergyInventorySlot productivebeesgenesis$getEnergySlot();

    @Accessor("energySlot")
    void productivebeesgenesis$setEnergySlot(EnergyInventorySlot slot);

    /** 暴露activeStates数组 — 用于onUpdateServer中重算整体激活状态 */
    @Accessor("activeStates")
    boolean[] productivebeesgenesis$getActiveStates();

    /** 暴露lastUsage setter — 用于更新包含PB处理的能量消耗 */
    @Accessor("lastUsage")
    void productivebeesgenesis$setLastUsage(long value);

    /** 暴露sortingNeeded setter — 用于重写getInitialInventory时标记排序需要更新 */
    @Accessor("sortingNeeded")
    void productivebeesgenesis$setSortingNeeded(boolean value);
}
