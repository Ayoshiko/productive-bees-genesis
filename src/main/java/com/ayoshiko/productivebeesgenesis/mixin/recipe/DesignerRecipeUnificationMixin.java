package com.ayoshiko.productivebeesgenesis.mixin.recipe;

import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 已快照的编辑器配方保持材料及组件原值，避免目标整合包再次统一或删除重复配方。 */
@Pseudo
@Mixin(targets = "com.almostreliable.unified.recipe.RecipeLink", remap = false)
public abstract class DesignerRecipeUnificationMixin {
	@Inject(method = "of", at = @At("HEAD"), cancellable = true, remap = false)
	private static void productivebeesgenesis$preserveDesignerRecipe(ResourceLocation id, JsonObject recipe,
			CallbackInfoReturnable<Object> callback) {
		var marker = recipe.get("productivebeesgenesis:designer_recipe");
		if (marker != null && marker.isJsonPrimitive() && marker.getAsJsonPrimitive().isBoolean()
				&& marker.getAsBoolean()) {
			callback.setReturnValue(null);
		}
	}
}
