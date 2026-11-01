package com.ayoshiko.productivebeesgenesis.mixin.mek;

import com.ayoshiko.productivebeesgenesis.port.ForgeBlockCapabilities;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value=mekanism.common.tile.base.CapabilityTileEntity.class,remap=false)
public abstract class ResourceForgeCapabilityMixin {
    @Inject(method="getCapability",at=@At("HEAD"),cancellable=true,remap=false)
    private <T> void genesis$resourceOutput(Capability<T> cap, Direction side, CallbackInfoReturnable<LazyOptional<T>> cir) {
        var value=ForgeBlockCapabilities.view((BlockEntity)(Object)this,cap,side);
        if(value.isPresent()) cir.setReturnValue(value);
    }
    @Inject(method="invalidateCaps",at=@At("TAIL"),remap=false)
    private void genesis$invalidateViews(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci){ForgeBlockCapabilities.invalidate((BlockEntity)(Object)this);}
}
