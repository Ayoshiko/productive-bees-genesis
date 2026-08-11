package com.ayoshiko.productivebeesgenesis.client.render.cosmic;

import com.ayoshiko.productivebeesgenesis.util.PBConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import cy.jdkdigital.productivebees.init.ModDataComponents;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
	 * 万象创世蜜脾物品 BakedModel 包装器
	 * <br/>
	 * 包装 PB 的 configurable_honeycomb 模型，在渲染时根据 ItemStack 的 bee_type 组件判断：
	 * <ul>
	 *   <li>bee_type = myriadcreations：委托给无尽创世蜜脾的 {@link BakedModelCosmic} 渲染（含星空特效）</li>
	 *   <li>其他蜜蜂：使用 PB 原始模型渲染（tintIndex 着色）</li>
	 * </ul>
	 * <p>
	 * 设计原理：不改变 PB 的 configurable_honeycomb 物品本身，仅在客户端渲染层
	 * 将万象创世蜜脾的视觉替换为无尽创世蜜脾的星空纹理，保留离心配方和随机转化功能。
	 */
public class BakedModelMyriadComb extends WrappedItemModel {

	/** 无尽创世蜜脾的 cosmic 模型（含星空特效） */
	private final BakedModel cosmicModel;

	public BakedModelMyriadComb(BakedModel originalPbModel, BakedModel cosmicModel) {
		super(originalPbModel);
		this.cosmicModel = cosmicModel;
	}

	@Override
	public void renderItem(ItemStack stack, ItemDisplayContext context, PoseStack poseStack,
			MultiBufferSource buffers, int packedLight, int packedOverlay) {
		ResourceLocation beeType = stack.get(ModDataComponents.BEE_TYPE.get());
		if (PBConstants.MYRIADCREATIONS_TYPE.equals(beeType) && cosmicModel instanceof PerspectiveModel cosmicPerspective) {
			cosmicPerspective.renderItem(stack, context, poseStack, buffers, packedLight, packedOverlay);
		} else {
			renderWrapped(stack, poseStack, buffers, packedLight, packedOverlay, true);
		}
	}
}
