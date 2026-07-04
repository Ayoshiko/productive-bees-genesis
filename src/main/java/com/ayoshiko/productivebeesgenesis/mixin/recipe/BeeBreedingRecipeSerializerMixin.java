package com.ayoshiko.productivebeesgenesis.mixin.recipe;

import com.ayoshiko.productivebeesgenesis.util.BeeIngredientFallback;
import cy.jdkdigital.productivebees.common.recipe.BeeBreedingRecipe;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 修复 BeeBreedingRecipe 序列化崩溃
 * <p>
 * 原理：BeeBreedingRecipe.Serializer.toNetwork 无 null 检查，当 parent1/parent2/offspring
 * 任一 supplier 返回 null 时 NPE。此 Mixin 在 toNetwork 头部拦截，用 minecraft:bee 作为
 * fallback 安全序列化三个 BeeIngredient，保留原 parentDeathChance。
 * <p>
 * fallback 序列化逻辑统一抽取到 {@link BeeIngredientFallback} 工具类。
 */
@Mixin(targets = "cy.jdkdigital.productivebees.common.recipe.BeeBreedingRecipe$Serializer")
public abstract class BeeBreedingRecipeSerializerMixin {

	@Inject(method = "toNetwork", at = @At("HEAD"), cancellable = true, remap = false)
	private static void productivebeesgenesis$fallbackOnNullIngredient(
			RegistryFriendlyByteBuf buffer, BeeBreedingRecipe recipe,
			CallbackInfo ci) {
		try {
			if (recipe.parent1.get() == null || recipe.parent2.get() == null || recipe.offspring.get() == null) {
				BeeIngredientFallback.writeFallbackBeeIngredient(buffer);
				BeeIngredientFallback.writeFallbackBeeIngredient(buffer);
				BeeIngredientFallback.writeFallbackBeeIngredient(buffer);
				buffer.writeFloat(recipe.parentDeathChance);
				ci.cancel();
			}
		} catch (Exception e) {
			BeeIngredientFallback.logSerializationError("BeeBreedingRecipe", e);
		}
	}
}
