package com.ayoshiko.productivebeesgenesis.mixin.mek;

import com.ayoshiko.productivebeesgenesis.apiculture.ownership.*;
import mekanism.common.capabilities.proxy.ProxyHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ProxyHandler.class, remap = false)
public abstract class ManagedProxyHandlerMixin {
	@Inject(method = {"readOnlyInsert", "readOnlyExtract"}, at = @At("HEAD"), cancellable = true)
	private void pbg$ownedAccess(CallbackInfoReturnable<Boolean> callback) {
		if (this instanceof MemberProxyAccess proxy && MemberBinding.isolated(proxy.productivebeesgenesis$proxyOwner())) callback.setReturnValue(true);
	}
}
