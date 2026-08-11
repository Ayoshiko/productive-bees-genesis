package com.ayoshiko.productivebeesgenesis.mixin.recipe;

import com.ayoshiko.productivebeesgenesis.util.BeeIngredientFallback;
import cy.jdkdigital.productivebees.common.recipe.BeeFishingRecipe;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
	 * 修复 BeeFishingRecipe 序列化崩溃
	 * <p>
	 * 原理：BeeFishingRecipe.Serializer.toNetwork 无 null 检查，当 output.get() 返回 null 时 NPE。
	 * 此 Mixin 在 toNetwork 头部拦截，用 minecraft:bee 作为 fallback 安全序列化，保留原 biomes 和 chance。
	 * <p>
	 * fallback 序列化逻辑统一抽取到 {@link BeeIngredientFallback} 工具类。
	 */
@Mixin(targets = "cy.jdkdigital.productivebees.common.recipe.BeeFishingRecipe$Serializer")
public abstract class BeeFishingRecipeSerializerMixin {

	@Inject(method = "toNetwork", at = @At("HEAD"), cancellable = true, remap = false)
	private static void productivebeesgenesis$fallbackOnNullIngredient(
			RegistryFriendlyByteBuf buffer, BeeFishingRecipe recipe,
			CallbackInfo ci) {
		try {
			if (recipe.output.get() == null) {
				BeeIngredientFallback.writeFallbackBeeIngredient(buffer);
				ByteBufCodecs.holderSet(Registries.BIOME).encode(buffer, recipe.biomes);
				buffer.writeFloat(recipe.chance);
				ci.cancel();
			}
		} catch (Exception e) {
			BeeIngredientFallback.logSerializationError("BeeFishingRecipe", e);
			// 防御性 fallback：异常时写入完整 fallback 数据包并取消原方法，
			// 避免原 toNetwork 继续执行导致二次异常或返回部分填充的 Recipe。
			BeeIngredientFallback.writeFallbackBeeIngredient(buffer);
			ByteBufCodecs.holderSet(Registries.BIOME).encode(buffer, recipe.biomes);
			buffer.writeFloat(recipe.chance);
			ci.cancel();
		}
	}
}
