package com.ayoshiko.productivebeesgenesis.mixin.recipe;

import com.ayoshiko.productivebeesgenesis.util.BeeIngredientFallback;
import cy.jdkdigital.productivebees.common.recipe.BeeConversionRecipe;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.crafting.Ingredient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
	 * 修复 BeeConversionRecipe 序列化崩溃
	 * <p>
	 * 原理：BeeConversionRecipe.Serializer.toNetwork 无 null 检查，当 source.get()
	 * 或 result.get() 返回 null 时会 NPE。此 Mixin 在 toNetwork 头部拦截，若 source
	 * 或 result 为 null 则用 minecraft:bee 作为 fallback 安全序列化，保留原 item 和 chance。
	 * <p>
	 * fallback 序列化逻辑统一抽取到 {@link BeeIngredientFallback} 工具类。
	 */
@Mixin(targets = "cy.jdkdigital.productivebees.common.recipe.BeeConversionRecipe$Serializer")
public abstract class BeeConversionRecipeSerializerMixin {

	@Inject(method = "toNetwork", at = @At("HEAD"), cancellable = true, remap = false)
	private static void productivebeesgenesis$fallbackOnNullIngredient(
			RegistryFriendlyByteBuf buffer, BeeConversionRecipe recipe,
			CallbackInfo ci) {
		try {
			if (recipe.source.get() == null || recipe.result.get() == null) {
				// source
				BeeIngredientFallback.writeFallbackBeeIngredient(buffer);
				// result
				BeeIngredientFallback.writeFallbackBeeIngredient(buffer);
				// item（保留原值）
				Ingredient.CONTENTS_STREAM_CODEC.encode(buffer, recipe.item);
				// chance（保留原值）
				buffer.writeFloat(recipe.chance);
				ci.cancel();
			}
		} catch (Exception e) {
			BeeIngredientFallback.logSerializationError("BeeConversionRecipe", e);
			// 防御性 fallback：异常时写入完整 fallback 数据包并取消原方法，
			// 避免原 toNetwork 继续执行导致二次异常或返回部分填充的 Recipe。
			// source
			BeeIngredientFallback.writeFallbackBeeIngredient(buffer);
			// result
			BeeIngredientFallback.writeFallbackBeeIngredient(buffer);
			// item（保留原值）
			Ingredient.CONTENTS_STREAM_CODEC.encode(buffer, recipe.item);
			// chance（保留原值）
			buffer.writeFloat(recipe.chance);
			ci.cancel();
		}
	}
}
