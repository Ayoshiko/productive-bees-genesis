package com.ayoshiko.productivebeesgenesis.mixin.recipe;

import com.ayoshiko.productivebeesgenesis.util.BeeIngredientFallback;
import cy.jdkdigital.productivebees.common.recipe.AdvancedBeehiveRecipe;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
	 * 修复 AdvancedBeehiveRecipe 序列化崩溃
	 * <p>
	 * 原理：当 BeeIngredientFactory 在配方网络同步时刻未填充 configurable bees 时，
	 * AdvancedBeehiveRecipe.ingredient.get() 返回 null，原版 toNetwork 抛出
	 * RuntimeException("Bee produce recipe ingredient missing") 导致玩家加入世界崩溃。
	 * <p>
	 * 此 Mixin 在 toNetwork 头部拦截，若 ingredient 为 null 则用 minecraft:bee 作为
	 * fallback 安全序列化（写入空输出列表），保证 buffer 格式完整，客户端能正确反序列化。
	 * 服务端的配方对象本身不受影响，当 BeeIngredientFactory 就绪后 matches() 仍正常工作。
	 * <p>
	 * fallback 序列化逻辑统一抽取到 {@link BeeIngredientFallback} 工具类。
	 */
@Mixin(targets = "cy.jdkdigital.productivebees.common.recipe.AdvancedBeehiveRecipe$Serializer")
public abstract class AdvancedBeehiveRecipeSerializerMixin {

	@Inject(method = "toNetwork", at = @At("HEAD"), cancellable = true, remap = false)
	private static void productivebeesgenesis$fallbackOnNullIngredient(
			RegistryFriendlyByteBuf buffer, AdvancedBeehiveRecipe recipe,
			CallbackInfo ci) {
		try {
			if (recipe.ingredient.get() == null) {
				BeeIngredientFallback.writeFallbackBeeIngredient(buffer);
				buffer.writeInt(0); // 空输出列表
				ci.cancel();
			}
		} catch (Exception e) {
			// 修复 v15 P1: catch 块必须 ci.cancel()，否则原版 toNetwork 继续执行
			// 会再次访问 recipe.ingredient.get() 并抛出异常，导致玩家加入世界崩溃。
			// 写入 fallback 数据保证 buffer 格式完整，客户端能正确反序列化。
			BeeIngredientFallback.logSerializationError("AdvancedBeehiveRecipe", e);
			BeeIngredientFallback.writeFallbackBeeIngredient(buffer);
			buffer.writeInt(0); // 空输出列表
			ci.cancel();
		}
	}
}
