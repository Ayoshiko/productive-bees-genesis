package com.ayoshiko.productivebeesgenesis.mixin;

import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerIdentity;
import cy.jdkdigital.productivebees.common.entity.bee.ConfigurableBee;
import cy.jdkdigital.productivebees.common.entity.bee.ProductiveBee;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** 父类名称、蜂蛋取回和免伤属性沿实体元数据读取原身份，避免保管中的蜂触发展示异常。 */
@Mixin(ProductiveBee.class)
public abstract class PbBeeIdentityMetadataMixin {
	@Redirect(method = {"getBeeName()Ljava/lang/String;", "getPickedResult", "isInvulnerableTo"},
			at = @At(value = "INVOKE", target = "Lcy/jdkdigital/productivebees/common/entity/bee/ProductiveBee;getBeeType()Lnet/minecraft/resources/ResourceLocation;"))
	private ResourceLocation productivebeesgenesis$retainedMetadataIdentity(ProductiveBee bee) {
		if (bee instanceof ConfigurableBee configurable) {
			return BeeDesignerIdentity.migrate(ResourceLocation.tryParse(configurable.getEntityData().get(ConfigurableBee.TYPE)));
		}
		return bee.getBeeType();
	}
}
