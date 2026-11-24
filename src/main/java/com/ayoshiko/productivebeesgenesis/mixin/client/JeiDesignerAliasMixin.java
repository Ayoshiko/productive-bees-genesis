package com.ayoshiko.productivebeesgenesis.mixin.client;

import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerIdentity;
import com.ayoshiko.productivebeesgenesis.mixin.accessor.BeeIngredientIdentityAccessor;
import cy.jdkdigital.productivebees.common.crafting.ingredient.BeeIngredient;
import cy.jdkdigital.productivebees.common.crafting.ingredient.BeeIngredientFactory;
import cy.jdkdigital.productivebees.compat.jei.ProductiveBeesJeiPlugin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.HashMap;
import java.util.Map;

/** 别名与正式蜂种共享 JEI UID，必须在注册去重之前过滤，避免旧材料覆盖正式本体。 */
@Mixin(value = ProductiveBeesJeiPlugin.class, remap = false)
public abstract class JeiDesignerAliasMixin {
	@Redirect(method = "registerIngredients", at = @At(value = "INVOKE",
			target = "Lcy/jdkdigital/productivebees/common/crafting/ingredient/BeeIngredientFactory;getOrCreateList(Z)Ljava/util/Map;"))
	private Map<String, BeeIngredient> productivebeesgenesis$visibleBees(boolean configurableOnly) {
		Map<String, BeeIngredient> ingredients = BeeIngredientFactory.getOrCreateList(configurableOnly);
		if (!BeeDesignerIdentity.hasMigrations()) {
			return ingredients;
		}
		Map<String, BeeIngredient> visible = new HashMap<>(ingredients);
		visible.values().removeIf(bee -> BeeDesignerIdentity.isAlias(
				((BeeIngredientIdentityAccessor) bee).productivebeesgenesis$originalBeeType()));
		return visible;
	}
}
