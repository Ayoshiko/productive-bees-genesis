package com.ayoshiko.productivebeesgenesis.mixin.client;

import com.ayoshiko.productivebeesgenesis.client.MyriadCreationsClientEventHandler;
import com.mojang.blaze3d.vertex.PoseStack;
import cy.jdkdigital.productivebees.client.render.entity.layers.BeeBodyLayer;
import cy.jdkdigital.productivebees.client.render.entity.model.ProductiveBeeModel;
import cy.jdkdigital.productivebees.common.entity.bee.ProductiveBee;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 在 PB 实际身体层内复用已准备的模型，保留主体旋转、大小与动画。 */
@Mixin(value = BeeBodyLayer.class, remap = false)
public abstract class NativeBeeGlowLayerMixin
		extends RenderLayer<ProductiveBee, ProductiveBeeModel<ProductiveBee>> {
	@Shadow
	@Final
	private String modelType;
	@Shadow
	@Final
	private EntityModel<ProductiveBee> model;

	protected NativeBeeGlowLayerMixin(RenderLayerParent<ProductiveBee, ProductiveBeeModel<ProductiveBee>> parent) {
		super(parent);
	}

	@Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILcy/jdkdigital/productivebees/common/entity/bee/ProductiveBee;FFFFFF)V",
			at = @At("TAIL"))
	private void productivebeesgenesis$glow(PoseStack poseStack, MultiBufferSource buffers, int packedLight,
			ProductiveBee bee, float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks,
			float netHeadYaw, float headPitch, CallbackInfo ci) {
		if (modelType.equals(bee.getRenderer()) && !bee.isInvisible()) {
			MyriadCreationsClientEventHandler.renderBeeGlow(bee, model, poseStack, buffers, packedLight, getTextureLocation(bee));
		}
	}
}
