package com.ayoshiko.productivebeesgenesis.mixin;

import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerIdentity;
import com.mojang.serialization.Codec;
import net.minecraft.world.item.component.CustomData;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CustomData.class)
public abstract class BeeIdentityCustomDataMixin {
	@Shadow @Final @Mutable
	public static Codec<CustomData> CODEC;
	@Shadow @Final @Mutable
	public static Codec<CustomData> CODEC_WITH_ID;

	@Inject(method = "<clinit>", at = @At("TAIL"))
	private static void productivebeesgenesis$migratePersistentBeeData(CallbackInfo ci) {
		CODEC = CODEC.xmap(BeeDesignerIdentity::migrateCustomData, BeeDesignerIdentity::migrateCustomData);
		CODEC_WITH_ID = CODEC_WITH_ID.xmap(BeeDesignerIdentity::migrateCustomData, BeeDesignerIdentity::migrateCustomData);
	}
}
