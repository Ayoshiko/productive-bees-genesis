package com.ayoshiko.productivebeesgenesis.mixin.mek;

import com.ayoshiko.productivebeesgenesis.apiculture.ownership.*;
import mekanism.api.inventory.ISidedItemHandler;
import mekanism.common.capabilities.proxy.ProxyItemHandler;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ProxyItemHandler.class, remap = false)
public abstract class ManagedItemProxyMixin implements MemberProxyAccess {
	@Shadow @Final private ISidedItemHandler inventory;
	@Override public Object productivebeesgenesis$proxyOwner() { return inventory; }
	@Inject(method = "setStackInSlot", at = @At("HEAD"), cancellable = true)
	private void pbg$ownedSet(CallbackInfo callback) { if (MemberBinding.isolated(inventory)) callback.cancel(); }
}
