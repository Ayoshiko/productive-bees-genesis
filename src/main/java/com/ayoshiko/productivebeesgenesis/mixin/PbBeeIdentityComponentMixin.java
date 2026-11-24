package com.ayoshiko.productivebeesgenesis.mixin;

import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerIdentity;
import com.mojang.serialization.Codec;
import cy.jdkdigital.productivebees.init.ModDataComponents;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.resources.ResourceLocation;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ModDataComponents.class)
public abstract class PbBeeIdentityComponentMixin {
	@Redirect(method = {"lambda$static$0", "lambda$static$1"}, at = @At(value = "FIELD",
			target = "Lnet/minecraft/resources/ResourceLocation;CODEC:Lcom/mojang/serialization/Codec;"))
	private static Codec<ResourceLocation> productivebeesgenesis$migratePersistentIdentity() {
		return ResourceLocation.CODEC.xmap(BeeDesignerIdentity::migrate, BeeDesignerIdentity::migrate);
	}

	@Redirect(method = "lambda$static$1", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/core/component/DataComponentType$Builder;cacheEncoding()Lnet/minecraft/core/component/DataComponentType$Builder;"))
	private static DataComponentType.Builder<List<ResourceLocation>> productivebeesgenesis$encodeCurrentIdentity(
			DataComponentType.Builder<List<ResourceLocation>> builder) {
		return builder;
	}
}
