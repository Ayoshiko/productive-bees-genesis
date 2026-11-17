package com.ayoshiko.productivebeesgenesis.mixin.mek;

import com.ayoshiko.productivebeesgenesis.mek.IFactoryPbDelegateAccess;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.jerry.mekextras.common.tile.factory.TileEntityExtraFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 在原槽数量聚合前排除未决输入，健康车道继续原生均摊。 */
@Mixin(value = TileEntityExtraFactory.class, remap = false)
public abstract class ExtraSmeltingInputSortingMixin {
    @ModifyExpressionValue(method = "sortInventory", at = @At(value = "FIELD",
            target = "Lcom/jerry/mekextras/common/tile/factory/TileEntityExtraFactory;processInfoSlots:[Lcom/jerry/mekextras/common/tile/factory/TileEntityExtraFactory$ProcessInfo;"))
    private TileEntityExtraFactory.ProcessInfo[] productivebeesgenesis$protectSmeltingInputs(
            TileEntityExtraFactory.ProcessInfo[] inputs) {
        if ((Object) this instanceof IFactoryPbDelegateAccess factory) {
            return factory.productivebeesgenesis$getPbProcessor().sortableSmeltingInputs(
                    inputs, TileEntityExtraFactory.ProcessInfo::process);
        }
        return inputs;
    }
}
