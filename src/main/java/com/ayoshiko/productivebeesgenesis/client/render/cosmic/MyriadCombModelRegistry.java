package com.ayoshiko.productivebeesgenesis.client.render.cosmic;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;

import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ModelEvent;

/**
 * 万象创世蜜脾/蜜脾块模型包装注册器
 * <br/>
 * 在 {@link ModelEvent.ModifyBakingResult} 中获取 PB 的 configurable_honeycomb / configurable_comb
 * 以及无尽创世的 infinitycreation_comb / infinitycreation_comb_block 的 BakedModel，
 * 用包装器替换 PB 的模型，实现万象创世蜜脾/蜜脾块视觉替换为无尽创世的星空纹理。
 * <p>
 * 设计原理：不修改 PB 物品/方块本身，仅在客户端 BakedModel 层包装，
 * 保留 PB 的离心配方和随机转化功能不受影响。
 * <p>
 * 使用 {@link ModelEvent.ModifyBakingResult} 而非 {@link ModelEvent.BakingCompleted}，
 * 因为后者的 getModels() 返回不可修改的 Map（NeoForge 设计行为）。
 */
@EventBusSubscriber(modid = ProductiveBeesGenesis.MOD_ID, value = Dist.CLIENT)
public final class MyriadCombModelRegistry {

	/** 方块模型包装注册日志仅输出一次 */
	private static final AtomicBoolean BLOCK_WRAPPER_LOGGED = new AtomicBoolean(false);

	private MyriadCombModelRegistry() {
	}

	@SubscribeEvent
	public static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
		Map<ModelResourceLocation, BakedModel> models = event.getModels();

		// PB 的 configurable_honeycomb 物品模型
		ModelResourceLocation pbHoneycombKey = ModelResourceLocation.inventory(
				net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("productivebees", "configurable_honeycomb"));
		BakedModel pbHoneycomb = models.get(pbHoneycombKey);
		// 无尽创世蜜脾物品模型（已使用 cosmic loader 烘焙为 BakedModelCosmic）
		ModelResourceLocation infinityCombKey = ModelResourceLocation.inventory(
				net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(ProductiveBeesGenesis.MOD_ID, "infinitycreation_comb"));
		BakedModel infinityComb = models.get(infinityCombKey);
		if (pbHoneycomb != null && infinityComb != null) {
			models.put(pbHoneycombKey, new BakedModelMyriadComb(pbHoneycomb, infinityComb));
		}

		// PB 的 configurable_comb 物品模型（BlockItem）
		ModelResourceLocation pbCombItemKey = ModelResourceLocation.inventory(
				net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("productivebees", "configurable_comb"));
		BakedModel pbCombItem = models.get(pbCombItemKey);
		// 无尽创世蜜脾块物品模型
		ModelResourceLocation infinityCombBlockItemKey = ModelResourceLocation.inventory(
				net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(ProductiveBeesGenesis.MOD_ID, "infinitycreation_comb_block"));
		BakedModel infinityCombBlockItem = models.get(infinityCombBlockItemKey);
		if (pbCombItem != null && infinityCombBlockItem != null) {
			models.put(pbCombItemKey, new BakedModelMyriadCombBlock(pbCombItem, infinityCombBlockItem));
		}

		// PB 的 configurable_comb 方块模型
		// 方块模型在 Map 中的键是 blockstate id + variant，而不是 model JSON 路径
		// 无属性方块的 variant 为空字符串，因此直接构造 ModelResourceLocation 查询
		ModelResourceLocation pbCombBlockKey = new ModelResourceLocation(
				ResourceLocation.fromNamespaceAndPath("productivebees", "configurable_comb"), "");
		BakedModel pbCombBlock = models.get(pbCombBlockKey);
		ModelResourceLocation infinityCombBlockKey = new ModelResourceLocation(
				ResourceLocation.fromNamespaceAndPath(ProductiveBeesGenesis.MOD_ID, "infinitycreation_comb_block"), "");
		BakedModel infinityCombBlock = models.get(infinityCombBlockKey);
		if (pbCombBlock != null && infinityCombBlock != null) {
			models.put(pbCombBlockKey, new BakedModelMyriadCombBlockBlock(pbCombBlock, infinityCombBlock));
			if (BLOCK_WRAPPER_LOGGED.compareAndSet(false, true)) {
				ProductiveBeesGenesis.LOGGER.info(
						"万象创世蜜脾块方块模型包装已注册：PB key={}, Infinity key={}", pbCombBlockKey, infinityCombBlockKey);
			}
		}
	}
}
