package com.ayoshiko.productivebeesgenesis.mixin.mek;

import com.ayoshiko.productivebeesgenesis.mek.IFactoryPbDelegateAccess;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import mekanism.common.tile.factory.TileEntityFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 在原槽数量聚合前排除未决输入，健康车道继续原生均摊。 */
@Mixin(value = TileEntityFactory.class, remap = false)
public abstract class SmeltingInputSortingMixin {
    @ModifyExpressionValue(method = "sortInventory", at = @At(value = "FIELD",
            target = "Lmekanism/common/tile/factory/TileEntityFactory;processInfoSlots:[Lmekanism/common/tile/factory/TileEntityFactory$ProcessInfo;"))
    private TileEntityFactory.ProcessInfo[] productivebeesgenesis$protectSmeltingInputs(
            TileEntityFactory.ProcessInfo[] inputs) {
        if ((Object) this instanceof IFactoryPbDelegateAccess factory) {
            return factory.productivebeesgenesis$getPbProcessor().sortableSmeltingInputs(
                    inputs, TileEntityFactory.ProcessInfo::process);
        }
        return inputs;
    }
}
