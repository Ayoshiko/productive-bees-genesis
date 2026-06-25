package com.ayoshiko.productivebeesgenesis.client.render.cosmic;

import com.mojang.blaze3d.vertex.PoseStack;

import cy.jdkdigital.productivebees.init.ModDataComponents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * 万象创世蜜脾块物品 BakedModel 包装器
 * <br/>
 * 包装 PB 的 configurable_comb BlockItem 模型，在渲染时根据 ItemStack 的 bee_type 组件判断：
 * <ul>
 *   <li>bee_type = myriadcreations：使用无尽创世蜜脾块的 BakedModel 渲染（彩色纹理，无 tintIndex）</li>
 *   <li>其他蜜蜂：使用 PB 原始模型渲染（灰度纹理 + tintIndex 着色）</li>
 * </ul>
 * <p>
 * 无尽创世蜜脾块模型是普通 cube_all（非 PerspectiveModel），因此使用
 * {@link ItemRenderer#renderModelLists} 直接渲染 quads。
 */
public class BakedModelMyriadCombBlock extends WrappedItemModel {

	/** 万象创世蜜蜂类型 ID */
	private static final ResourceLocation MYRIADCREATIONS_TYPE =
			ResourceLocation.fromNamespaceAndPath("productivebees", "myriadcreations");

	/** 无尽创世蜜脾块的 BakedModel */
	private final BakedModel infinityCombBlockModel;

	public BakedModelMyriadCombBlock(BakedModel originalPbModel, BakedModel infinityCombBlockModel) {
		super(originalPbModel);
		this.infinityCombBlockModel = infinityCombBlockModel;
	}

	@Override
	public void renderItem(ItemStack stack, ItemDisplayContext context, PoseStack poseStack,
						   MultiBufferSource buffers, int packedLight, int packedOverlay) {
		ResourceLocation beeType = stack.get(ModDataComponents.BEE_TYPE.get());
		if (MYRIADCREATIONS_TYPE.equals(beeType)) {
			renderModelDirect(infinityCombBlockModel, stack, poseStack, buffers, packedLight, packedOverlay);
		} else {
			renderWrapped(stack, poseStack, buffers, packedLight, packedOverlay, true);
		}
	}

	/**
	 * 直接渲染指定 BakedModel 的 quads（不经过 PerspectiveModel 接口）
	 * <p>
	 * 复用 {@link WrappedItemModel#renderWrapped} 的内部逻辑，但传入任意 BakedModel
	 * 而非 this.wrapped，使子类可以切换渲染目标模型。
	 */
	private void renderModelDirect(BakedModel modelToRender, ItemStack stack, PoseStack poseStack,
								   MultiBufferSource buffers, int packedLight, int packedOverlay) {
		BakedModel resolved = modelToRender.getOverrides().resolve(modelToRender, stack, this.world, this.entity, 0);
		ItemRenderer itemRenderer = Minecraft.getInstance().getItemRenderer();
		for (BakedModel bakedModel : resolved.getRenderPasses(stack, true)) {
			for (RenderType renderType : bakedModel.getRenderTypes(stack, true)) {
				itemRenderer.renderModelLists(bakedModel, stack, packedLight, packedOverlay, poseStack, buffers.getBuffer(renderType));
			}
		}
	}
}
