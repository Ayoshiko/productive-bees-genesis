package com.ayoshiko.productivebeesgenesis.mixin.recipe;

import com.ayoshiko.productivebeesgenesis.util.BeeIngredientFallback;
import cy.jdkdigital.productivebees.common.crafting.ingredient.BeeIngredient;
import cy.jdkdigital.productivebees.common.recipe.BeeSpawningRecipe;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.world.item.crafting.Ingredient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Supplier;

/**
	 * 修复 BeeSpawningRecipe 序列化崩溃
	 * <p>
	 * 原理：BeeSpawningRecipe.Serializer.toNetwork 对 output 中 null 的处理是跳过不写入，
	 * 这会导致客户端读取的 output 数量与实际不符，引发 buffer 错乱或崩溃。
	 * 此 Mixin 在 toNetwork 头部拦截，只要 output 列表中存在任何 null，
	 * 就将全部 output 替换为单个 minecraft:bee fallback，并保留原 ingredient/spawnItem/biomes。
	 * <p>
	 * fallback 序列化逻辑统一抽取到 {@link BeeIngredientFallback} 工具类。
	 */
@Mixin(targets = "cy.jdkdigital.productivebees.common.recipe.BeeSpawningRecipe$Serializer")
public abstract class BeeSpawningRecipeSerializerMixin {

	@Inject(method = "toNetwork", at = @At("HEAD"), cancellable = true, remap = false)
	private static void productivebeesgenesis$fallbackOnNullOutput(
			RegistryFriendlyByteBuf buffer, BeeSpawningRecipe recipe,
			CallbackInfo ci) {
		try {
			// 防御性检查：recipe.output 列表本身可能为 null，此时直接触发 fallback 逻辑
			boolean hasNull = recipe.output == null;
			if (!hasNull) {
				for (Supplier<BeeIngredient> beeOutput : recipe.output) {
					if (beeOutput == null || beeOutput.get() == null) {
						hasNull = true;
						break;
					}
				}
			}
			if (hasNull) {
				Ingredient.CONTENTS_STREAM_CODEC.encode(buffer, recipe.ingredient);
				Ingredient.CONTENTS_STREAM_CODEC.encode(buffer, recipe.spawnItem);
				buffer.writeInt(1); // 替换为单个 fallback 输出
				BeeIngredientFallback.writeFallbackBeeIngredient(buffer);
				ByteBufCodecs.holderSet(Registries.BIOME).encode(buffer, recipe.biomes);
				ci.cancel();
			}
		} catch (Exception e) {
			BeeIngredientFallback.logSerializationError("BeeSpawningRecipe", e);
			// 防御性 fallback：异常时写入完整 fallback 数据包并取消原方法，
			// 避免原 toNetwork 继续执行导致二次异常或返回部分填充的 Recipe。
			Ingredient.CONTENTS_STREAM_CODEC.encode(buffer, recipe.ingredient);
			Ingredient.CONTENTS_STREAM_CODEC.encode(buffer, recipe.spawnItem);
			buffer.writeInt(1); // 单个 fallback 输出
			BeeIngredientFallback.writeFallbackBeeIngredient(buffer);
			ByteBufCodecs.holderSet(Registries.BIOME).encode(buffer, recipe.biomes);
			ci.cancel();
		}
	}
}
