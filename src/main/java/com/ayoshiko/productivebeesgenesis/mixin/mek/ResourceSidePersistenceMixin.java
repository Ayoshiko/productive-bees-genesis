package com.ayoshiko.productivebeesgenesis.mixin.mek;

import com.ayoshiko.productivebeesgenesis.resource.ResourceSidePersistence;
import mekanism.common.tile.base.TileEntityMekanism;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** MEK 存档、机器物品及配置卡的共同持续数据入口；只处理 Genesis 宿主。 */
@Mixin(value = TileEntityMekanism.class, remap = false)
public abstract class ResourceSidePersistenceMixin {
    @Inject(method = "writeSustainedData", at = @At("TAIL"))
    private void productivebeesgenesis$writeNativeSides(HolderLookup.Provider provider, CompoundTag data, CallbackInfo result) {
        ResourceSidePersistence.write((TileEntityMekanism) (Object) this, data);
    }
    @Inject(method = "readSustainedData", at = @At("TAIL"))
    private void productivebeesgenesis$readNativeSides(HolderLookup.Provider provider, CompoundTag data, CallbackInfo result) {
        ResourceSidePersistence.read((TileEntityMekanism) (Object) this, data);
    }
}
