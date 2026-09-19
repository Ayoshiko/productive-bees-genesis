package com.ayoshiko.productivebeesgenesis.mixin.mek;

import com.ayoshiko.productivebeesgenesis.apiculture.ownership.MemberBinding;
import mekanism.common.inventory.container.tile.MekanismTileContainer;
import mekanism.common.tile.base.TileEntityMekanism;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = MekanismTileContainer.class, remap = false)
public abstract class ManagedMemberMenuMixin {
	@Shadow @Final protected TileEntityMekanism tile;
	@Inject(method = "stillValid", at = @At("HEAD"), cancellable = true)
	private void pbg$ownedMenu(CallbackInfoReturnable<Boolean> callback) { if (MemberBinding.isolated(tile)) callback.setReturnValue(false); }
}
