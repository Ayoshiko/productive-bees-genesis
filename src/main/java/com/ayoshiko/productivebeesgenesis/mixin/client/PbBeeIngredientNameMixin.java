package com.ayoshiko.productivebeesgenesis.mixin.client;

import cy.jdkdigital.productivebees.compat.jei.ingredients.BeeIngredientHelper;
import net.minecraft.network.chat.MutableComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** PB 的组件调试字符串不能作为 JEI 名称；分组后缀仍由原方法追加。 */
@Mixin(value = BeeIngredientHelper.class, remap = false)
public abstract class PbBeeIngredientNameMixin {
	@Redirect(method = "getDisplayName(Lcy/jdkdigital/productivebees/common/crafting/ingredient/BeeIngredient;)Ljava/lang/String;",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/network/chat/MutableComponent;toString()Ljava/lang/String;", remap = false))
	private String productivebeesgenesis$translatedName(MutableComponent name) {
		return name.getString();
	}
}
