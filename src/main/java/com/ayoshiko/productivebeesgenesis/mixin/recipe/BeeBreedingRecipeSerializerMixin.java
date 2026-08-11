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
			// 修复 v15 P1: catch 块必须 ci.cancel()，否则原版 toNetwork 继续执行
			// 会再次访问 recipe.parent1/parent2/offspring.get() 并抛出异常，导致玩家加入世界崩溃。
			// 写入 fallback 数据保证 buffer 格式完整（3 个 BeeIngredient + parentDeathChance），
			// 客户端能正确反序列化。parentDeathChance 反射读取失败时用 0.0f 兜底。
			BeeIngredientFallback.logSerializationError("BeeBreedingRecipe", e);
			BeeIngredientFallback.writeFallbackBeeIngredient(buffer);
			BeeIngredientFallback.writeFallbackBeeIngredient(buffer);
			BeeIngredientFallback.writeFallbackBeeIngredient(buffer);
			float deathChance = 0.0f;
			try {
				deathChance = recipe.parentDeathChance;
			} catch (RuntimeException ignored) {
				// recipe 字段访问失败时使用默认值 0.0f（外层 catch 已记录序列化错误日志）
			}
			buffer.writeFloat(deathChance);
			ci.cancel();
		}
	}
}
