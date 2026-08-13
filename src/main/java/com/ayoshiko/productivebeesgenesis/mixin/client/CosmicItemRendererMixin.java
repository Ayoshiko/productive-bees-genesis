package com.ayoshiko.productivebeesgenesis.mixin.client;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.client.render.cosmic.PerspectiveModel;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
	 * 物品渲染器 Mixin
	 * <br/>
	 * 在 ItemRenderer.render 首次 pushPose 之前注入：若模型实现 PerspectiveModel，
	 * 则应用视角变换并调用 renderItem 执行自定义渲染（基础物品 + cosmic 光晕）。
	 * <p>
	 * 设计说明：不 cancel 原方法。原方法后续走 isCustomRenderer()=true 的 else 分支，
	 * 调用 IClientItemExtensions.of(stack).getCustomRenderer().renderByItem()；
	 * 项目未注册 IClientItemExtensions，默认 BlockEntityWithoutLevelRenderer 对自定义
	 * cosmic 物品为空操作，因此不会产生视觉双重渲染。保留原方法流程可维持与原版
	 * 兼容性，允许未来通过 IClientItemExtensions 扩展装饰渲染。
	 */
@Mixin(ItemRenderer.class)
public abstract class CosmicItemRendererMixin {

	@Inject(method = "render(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IILnet/minecraft/client/resources/model/BakedModel;)V",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;pushPose()V", ordinal = 0))
	private void productivebeesgenesis$onRenderItem(
		ItemStack stack,
		ItemDisplayContext context,
		boolean leftHand,
		PoseStack poseStack,
		MultiBufferSource multiBufferSource,
		int packedLight,
		int packedOverlay,
		BakedModel modelIn,
		CallbackInfo callbackInfo
	) {
		if (modelIn instanceof PerspectiveModel renderer) {
			try {
				try {
					poseStack.pushPose();
					BakedModel transformed = renderer.applyTransform(context, poseStack, leftHand);
					poseStack.translate(-0.5F, -0.5F, -0.5F);
					if (transformed instanceof PerspectiveModel transformedRenderer) {
						transformedRenderer.renderItem(stack, context, poseStack, multiBufferSource, packedLight, packedOverlay);
					} else {
						renderer.renderItem(stack, context, poseStack, multiBufferSource, packedLight, packedOverlay);
					}
				} finally {
					poseStack.popPose();
				}
			} catch (Exception e) {
				// 捕获单个物品渲染异常，避免导致整体渲染崩溃
				ProductiveBeesGenesis.LOGGER.error("Cosmic item rendering failed", e);
			}
		}
	}
}
