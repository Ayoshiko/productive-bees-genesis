package com.ayoshiko.productivebeesgenesis.mixin;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerIdentity;
import cy.jdkdigital.productivebees.common.entity.bee.ConfigurableBee;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ConfigurableBee.class)
public abstract class PbBeeIdentityMixin {
	@Unique
	private String productivebeesgenesis$identityFailure;

	@ModifyVariable(method = "setBeeType", at = @At("HEAD"), argsOnly = true)
	private String productivebeesgenesis$migrateIdentity(String type) {
		return BeeDesignerIdentity.migrate(type);
	}

	@Inject(method = "getBeeType", at = @At("RETURN"), cancellable = true)
	private void productivebeesgenesis$resolveLoadedIdentity(CallbackInfoReturnable<ResourceLocation> ci) {
		ci.setReturnValue(BeeDesignerIdentity.resolve(ci.getReturnValue()));
	}

	/** 保存和外观属性只读取仍受保管的身份；它们不能替代生产资格解析。 */
	@Redirect(method = {"addAdditionalSaveData", "getNBTData", "setDefaultAttributes"},
			at = @At(value = "INVOKE", target = "Lcy/jdkdigital/productivebees/common/entity/bee/ConfigurableBee;getBeeType()Lnet/minecraft/resources/ResourceLocation;"))
	private ResourceLocation productivebeesgenesis$retainedMetadataIdentity(ConfigurableBee bee) {
		return BeeDesignerIdentity.migrate(ResourceLocation.tryParse(bee.getEntityData().get(ConfigurableBee.TYPE)));
	}

	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void productivebeesgenesis$holdInvalidIdentity(CallbackInfo callback) {
		ConfigurableBee bee = (ConfigurableBee) (Object) this;
		String original = bee.getEntityData().get(ConfigurableBee.TYPE);
		String failure = BeeDesignerIdentity.failure(original);
		if (failure != null) {
			if (productivebeesgenesis$identityFailure == null) {
				productivebeesgenesis$identityFailure = failure;
				ProductiveBeesGenesis.LOGGER.error("蜜蜂身份迁移未决，保管实体 {} 于 {}，原身份 {}：{}",
						bee.getUUID(), bee.blockPosition(), original, failure);
			}
			callback.cancel();
			return;
		}
		String migrated = BeeDesignerIdentity.migrate(original);
		if (!bee.level().isClientSide && !original.equals(migrated)) {
			bee.setBeeType(migrated);
		}
		if (productivebeesgenesis$identityFailure != null) {
			ProductiveBeesGenesis.LOGGER.info("蜜蜂身份迁移恢复，实体 {} 于 {}，身份 {}；首因 {}",
					bee.getUUID(), bee.blockPosition(), migrated, productivebeesgenesis$identityFailure);
			productivebeesgenesis$identityFailure = null;
		}
	}

	@Inject(method = "getTypeName", at = @At("RETURN"), cancellable = true)
	private void productivebeesgenesis$showIdentityFailure(CallbackInfoReturnable<Component> callback) {
		ConfigurableBee bee = (ConfigurableBee) (Object) this;
		String original = bee.getEntityData().get(ConfigurableBee.TYPE);
		if (BeeDesignerIdentity.failure(original) != null) {
			callback.setReturnValue(callback.getReturnValue().copy().append(Component.literal(" "))
					.append(Component.translatable("designer.status.identity_suspended", original).withStyle(ChatFormatting.RED)));
		}
	}
}
