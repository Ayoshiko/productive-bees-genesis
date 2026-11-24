package com.ayoshiko.productivebeesgenesis.mixin;

import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerIdentity;
import com.google.gson.JsonObject;
import cy.jdkdigital.productivebees.util.BeeCreator;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BeeCreator.class)
public abstract class PbBeeIdentityDefinitionMixin {
	@Inject(method = "create", at = @At("RETURN"))
	private static void productivebeesgenesis$retainIdentityTarget(ResourceLocation id, JsonObject definition,
			CallbackInfoReturnable<CompoundTag> ci) {
		if (definition.has(BeeDesignerIdentity.TARGET)) {
			ci.getReturnValue().putString(BeeDesignerIdentity.TARGET,
					definition.get(BeeDesignerIdentity.TARGET).getAsString());
		}
	}
}
