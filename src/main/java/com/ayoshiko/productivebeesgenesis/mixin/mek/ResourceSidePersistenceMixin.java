package com.ayoshiko.productivebeesgenesis.mixin.mek;
import com.ayoshiko.productivebeesgenesis.resource.ResourceSidePersistence;
import mekanism.common.tile.base.TileEntityMekanism;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
@Mixin(value=TileEntityMekanism.class,remap=false)
public abstract class ResourceSidePersistenceMixin {
 @Inject(method="saveAdditional",at=@At("TAIL"),remap=true)
 private void genesis$saveSides(CompoundTag data,CallbackInfo ci){ResourceSidePersistence.write((TileEntityMekanism)(Object)this,data);}
 @Inject(method="load",at=@At("TAIL"),remap=true)
 private void genesis$loadSides(CompoundTag data,CallbackInfo ci){ResourceSidePersistence.read((TileEntityMekanism)(Object)this,data);}
 @Inject(method="getConfigurationData",at=@At("RETURN"))
 private void genesis$cardRead(Player player,CallbackInfoReturnable<CompoundTag> ci){if(ci.getReturnValue()!=null)ResourceSidePersistence.write((TileEntityMekanism)(Object)this,ci.getReturnValue());}
 @Inject(method="setConfigurationData",at=@At("TAIL"))
 private void genesis$cardWrite(Player player,CompoundTag data,CallbackInfo ci){ResourceSidePersistence.read((TileEntityMekanism)(Object)this,data);}
}
