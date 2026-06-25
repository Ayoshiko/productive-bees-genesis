package com.ayoshiko.productivebeesgenesis.client.render.cosmic;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * 可渲染 cosmic 光晕层的能力接口
 * <br/>
 * 实现类需在基础物品渲染完成后调用 renderCosmicLayer 绘制宇宙星空效果。
 */
public interface CosmicRenderable {

	void renderCosmicLayer(ItemStack stack, ItemDisplayContext context, PoseStack poseStack, MultiBufferSource buffers, int packedLight, int packedOverlay);
}
