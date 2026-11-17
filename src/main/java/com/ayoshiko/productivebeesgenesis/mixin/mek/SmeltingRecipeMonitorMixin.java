package com.ayoshiko.productivebeesgenesis.mixin.mek;

import com.ayoshiko.productivebeesgenesis.mek.IFactoryPbDelegateAccess;
import com.ayoshiko.productivebeesgenesis.mek.MekCentrifugeFactoryHelper;
import com.ayoshiko.productivebeesgenesis.mek.TileEntityMekCentrifuge;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import mekanism.common.recipe.lookup.IRecipeLookupHandler;
import mekanism.common.recipe.lookup.monitor.RecipeCacheLookupMonitor;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** 缓存准备故障只返回本车道未加工；父工厂循环继续其它车道，已付执行由实际缓存保管。 */
@Mixin(value = RecipeCacheLookupMonitor.class, remap = false)
public abstract class SmeltingRecipeMonitorMixin {
    @Shadow
    @Final
    private IRecipeLookupHandler<?> handler;

    @Shadow
    @Final
    protected int cacheIndex;

    @WrapMethod(method = "updateAndProcess()Z")
    private boolean productivebeesgenesis$isolateSmeltingLane(Operation<Boolean> original) {
        if (handler instanceof IFactoryPbDelegateAccess factory) {
            return MekCentrifugeFactoryHelper.processSmeltingMonitor(factory.productivebeesgenesis$getPbProcessor(),
                    cacheIndex, () -> original.call());
        }
        if (handler instanceof TileEntityMekCentrifuge machine) {
            return MekCentrifugeFactoryHelper.processSmeltingMonitor(machine.productivebeesgenesis$getPbProcessor(),
                    cacheIndex, () -> original.call());
        }
        return original.call();
    }
}
