package com.ayoshiko.productivebeesgenesis.mixin.mek;

import com.ayoshiko.productivebeesgenesis.mek.IFactoryPbDelegateAccess;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import io.github.masyumero.emextras.common.tile.factory.TileEntityEMExtraFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 在原槽数量聚合前排除未决输入，健康车道继续原生均摊。 */
@Mixin(value = TileEntityEMExtraFactory.class, remap = false)
public abstract class EMExtraSmeltingInputSortingMixin {
    @ModifyExpressionValue(method = "sortInventory", at = @At(value = "FIELD",
            target = "Lio/github/masyumero/emextras/common/tile/factory/TileEntityEMExtraFactory;processInfoSlots:[Lio/github/masyumero/emextras/common/tile/factory/TileEntityEMExtraFactory$ProcessInfo;"))
    private TileEntityEMExtraFactory.ProcessInfo[] productivebeesgenesis$protectSmeltingInputs(
            TileEntityEMExtraFactory.ProcessInfo[] inputs) {
        if ((Object) this instanceof IFactoryPbDelegateAccess factory) {
            return factory.productivebeesgenesis$getPbProcessor().sortableSmeltingInputs(
                    inputs, TileEntityEMExtraFactory.ProcessInfo::process);
        }
        return inputs;
    }
}
