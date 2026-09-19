package com.ayoshiko.productivebeesgenesis.mixin.mek;

import com.ayoshiko.productivebeesgenesis.apiculture.ownership.*;
import mekanism.api.fluid.ISidedFluidHandler;
import mekanism.common.capabilities.proxy.ProxyFluidHandler;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ProxyFluidHandler.class, remap = false)
public abstract class ManagedFluidProxyMixin implements MemberProxyAccess {
	@Shadow @Final private ISidedFluidHandler fluidHandler;
	@Override public Object productivebeesgenesis$proxyOwner() { return fluidHandler; }
	@Inject(method = "setFluidInTank", at = @At("HEAD"), cancellable = true)
	private void pbg$ownedSet(CallbackInfo callback) { if (MemberBinding.isolated(fluidHandler)) callback.cancel(); }
}
