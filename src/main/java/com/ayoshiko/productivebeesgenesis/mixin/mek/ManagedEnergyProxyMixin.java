package com.ayoshiko.productivebeesgenesis.mixin.mek;

import com.ayoshiko.productivebeesgenesis.apiculture.ownership.*;
import mekanism.api.energy.ISidedStrictEnergyHandler;
import mekanism.common.capabilities.proxy.ProxyStrictEnergyHandler;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ProxyStrictEnergyHandler.class, remap = false)
public abstract class ManagedEnergyProxyMixin implements MemberProxyAccess {
	@Shadow @Final private ISidedStrictEnergyHandler energyHandler;
	@Override public Object productivebeesgenesis$proxyOwner() { return energyHandler; }
	@Inject(method = "setEnergy", at = @At("HEAD"), cancellable = true)
	private void pbg$ownedSet(CallbackInfo callback) { if (MemberBinding.isolated(energyHandler)) callback.cancel(); }
}
