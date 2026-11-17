package com.ayoshiko.productivebeesgenesis.mixin.accessor;

import mekanism.common.capabilities.energy.BasicEnergyContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 只在本地扣费抛错后读取实际余额，区分扣取失败与扣取后的通知失败。 */
@Mixin(value = BasicEnergyContainer.class, remap = false)
public interface BasicEnergyContainerAccessor {
    @Accessor("stored")
    long productivebeesgenesis$getStored();
}
