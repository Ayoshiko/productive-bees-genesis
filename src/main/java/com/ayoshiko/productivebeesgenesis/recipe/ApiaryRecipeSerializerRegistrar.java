package com.ayoshiko.productivebeesgenesis.recipe;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * ApiaryShapedRecipe 配方序列化器注册器
 * <br/>
 * 在 {@code RegisterEvent} 期间将 {@link ApiaryShapedRecipe#SERIALIZER} 注册到
 * {@code minecraft:recipe_serializer} 注册表（id = {@code productivebeesgenesis:apiary_shaped}）。
 * <p>
 * 注册必要性：datagen 生成配方 JSON 时使用 {@code getSerializer().getRecipeSerializerType()} 作为
 * JSON 的 {@code "type"} 字段；游戏加载配方时按该 id 查找序列化器，经
 * {@link ApiaryShapedRecipe.ApiaryShapedRecipeSerializer#fromJson} 创建 {@link ApiaryShapedRecipe}
 * 实例，使 {@code assemble} 覆盖（合成升级数据转移）真正生效。
 */
@EventBusSubscriber(modid = ProductiveBeesGenesis.MOD_ID)
public final class ApiaryRecipeSerializerRegistrar {

	private ApiaryRecipeSerializerRegistrar() {
		// 工具类禁止实例化
	}

	@SubscribeEvent
	public static void register(RegisterEvent event) {
		if (event.getRegistryKey().equals(Registries.RECIPE_SERIALIZER)) {
			event.register(Registries.RECIPE_SERIALIZER,
					ResourceLocation.fromNamespaceAndPath(ProductiveBeesGenesis.MOD_ID, "apiary_shaped"),
					() -> ApiaryShapedRecipe.SERIALIZER);
		}
	}
}
