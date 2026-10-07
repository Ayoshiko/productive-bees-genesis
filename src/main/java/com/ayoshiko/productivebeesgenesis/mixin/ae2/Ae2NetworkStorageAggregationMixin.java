package com.ayoshiko.productivebeesgenesis.mixin.ae2;

import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.me.storage.NetworkStorage;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.ayoshiko.productivebeesgenesis.apiculture.compat.ae2.MeBridgeStorage;
import com.ayoshiko.productivebeesgenesis.apiculture.compat.ae2.SafeStorageAggregation;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import java.util.List;
import java.util.NavigableMap;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 只在含蜂业桥且显式启用时隔离各提供者的显示贡献，消除挂载顺序导致的正数相加溢出。 */
@Mixin(value = NetworkStorage.class, remap = false)
public abstract class Ae2NetworkStorageAggregationMixin implements SafeStorageAggregation {
	@Shadow @Final private NavigableMap<Integer, List<MEStorage>> priorityInventory;
	@Shadow private boolean mountsInUse;
	@Unique private boolean pbg$safe, pbg$invalid;
	@Override public boolean pbgSafeAggregationAvailable() { return !pbg$invalid; }
	@Inject(method = "getAvailableStacks", at = @At("HEAD"))
	private void pbg$begin(KeyCounter output, CallbackInfo ci) {
		if (!mountsInUse) pbg$safe = priorityInventory.values().stream().anyMatch(group -> group.stream().anyMatch(MeBridgeStorage.class::isInstance))
				&& ModConfig.SERVER.beeNetwork.meStorageSafeAggregation.get();
	}
	@WrapOperation(method = "getAvailableStacks", at = @At(value = "INVOKE", target = "Lappeng/api/storage/MEStorage;getAvailableStacks(Lappeng/api/stacks/KeyCounter;)V"))
	private void pbg$collect(MEStorage storage, KeyCounter output, Operation<Void> original) {
		if (pbg$safe) { if (!SafeStorageAggregation.collect(counter -> original.call(storage, counter), output)) pbg$invalid = true; }
		else original.call(storage, output);
	}
}
