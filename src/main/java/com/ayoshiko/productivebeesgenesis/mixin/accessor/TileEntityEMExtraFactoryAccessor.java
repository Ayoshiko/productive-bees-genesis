package com.ayoshiko.productivebeesgenesis.mixin.accessor;

import io.github.masyumero.emextras.common.tile.factory.TileEntityEMExtraFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * TileEntityEMExtraFactory 访问器：暴露EME基类的私有字段供外部包访问
 * <br/>
 * EME的TileEntityEMExtraFactory中activeStates和lastUsage是私有的，
 * PB离心机工厂需要在onUpdateServer中重算整体激活状态并追加PB能量消耗，
 * 通过Accessor Mixin暴露getter/setter。
 */
@Mixin(value = TileEntityEMExtraFactory.class, remap = false)
public interface TileEntityEMExtraFactoryAccessor {

    /** 暴露activeStates数组 — 用于onUpdateServer中重算整体激活状态 */
    @Accessor("activeStates")
    boolean[] productivebeesgenesis$getActiveStates();

    /** 暴露lastUsage setter — 用于更新包含PB处理的能量消耗 */
    @Accessor("lastUsage")
    void productivebeesgenesis$setLastUsage(long value);
}
