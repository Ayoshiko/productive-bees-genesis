package com.ayoshiko.productivebeesgenesis.mixin.client;

import com.ayoshiko.productivebeesgenesis.client.screen.authoring.BeeDesignerClient;
import cy.jdkdigital.productivebees.common.crafting.ingredient.BeeIngredient;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.library.ingredients.IngredientManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Collection;

/** 搜索别名沿 JEI 名称构建入口加入；不改变本体显示名称或普通物品结果。 */
@Mixin(value = IngredientManager.class, remap = false)
public abstract class JeiBeeSearchNamesMixin {
	@Inject(method = "getIngredientAliases", at = @At("RETURN"), cancellable = true)
	private void productivebeesgenesis$registeredNames(ITypedIngredient<?> ingredient,
			CallbackInfoReturnable<Collection<String>> callback) {
		if (ingredient.getIngredient() instanceof BeeIngredient bee) {
			Collection<String> aliases = new ArrayList<>(callback.getReturnValue());
			aliases.addAll(BeeDesignerClient.registeredSearchNames(bee.getBeeType().toString()));
			callback.setReturnValue(aliases);
		}
	}
}
