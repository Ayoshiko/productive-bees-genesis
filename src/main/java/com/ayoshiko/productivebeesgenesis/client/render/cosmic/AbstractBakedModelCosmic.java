package com.ayoshiko.productivebeesgenesis.client.render.cosmic;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * Cosmic 烘焙模型抽象基类
 * <br/>
 * 提取 {@link BakedModelCosmic} 与 {@link BakedModelHell} 的公共渲染逻辑：
 * 基础物品渲染、Iris 延迟入队、mask 光晕层渲染框架。
 * <br/>
 * 子类只需通过三个抽象方法提供差异化的着色器 uniform、RenderType 与批次收尾逻辑，
 * 遵循 DRY（消除重复）与 OCP（对扩展开放）原则。
 */
public abstract class AbstractBakedModelCosmic extends WrappedItemModel implements CosmicRenderable {

	/** mask 纹理资源位置列表，子类共享 */
	protected final List<ResourceLocation> maskSprites;

	protected AbstractBakedModelCosmic(BakedModel wrapped, List<ResourceLocation> maskSprites) {
		super(wrapped);
		this.maskSprites = maskSprites;
		// 标记为 cosmic 模型，影响 ItemOverrides.resolve 的行为
		this.cosmic = true;
	}

	/**
	 * 设置本类型专属的着色器 uniform
	 *
	 * @param time	游戏时间（已取模）
	 * @param yaw	玩家偏航角（弧度）
	 * @param pitch	玩家俯仰角（弧度）
	 * @param scale	外部缩放系数（GUI 时为 100，世界为 1）
	 */
	protected abstract void setupShaderUniforms(float time, float yaw, float pitch, float scale);

	/**
	 * 获取本类型对应的 RenderType
	 */
	protected abstract RenderType getRenderType();

	/**
	 * 结束本类型 RenderType 的批次，确保 cosmic 层立即刷新到 GPU
	 */
	protected abstract void endBatch(MultiBufferSource.BufferSource bufferSource);

	@Override
	public void renderItem(ItemStack stack, ItemDisplayContext context, PoseStack poseStack, MultiBufferSource buffers, int packedLight, int packedOverlay) {
		// 先渲染被包装的基础物品模型
		renderWrapped(stack, poseStack, buffers, packedLight, packedOverlay, true);
		// 立即刷新基础层，避免 cosmic 层深度测试异常
		if (buffers instanceof MultiBufferSource.BufferSource bufferSource) {
			bufferSource.endBatch();
		}
		// Iris 光影启用时延迟到世界渲染后执行，避免光影破坏 cosmic 着色器状态
		if (IrisCompat.shouldDefer(context)) {
			CosmicRenderQueue.enqueue(new CosmicRenderCall(this, stack, context, poseStack, packedLight, packedOverlay, RenderSystem.getProjectionMatrix(), RenderSystem.getModelViewMatrix()));
			return;
		}
		renderCosmicLayer(stack, context, poseStack, buffers, packedLight, packedOverlay);
	}

	@Override
	public void renderCosmicLayer(ItemStack stack, ItemDisplayContext context, PoseStack poseStack, MultiBufferSource buffers, int packedLight, int packedOverlay) {
		Minecraft mc = Minecraft.getInstance();
		// 防御性检查：主菜单或世界未加载时 mc.player/mc.level 可能为 null
		if (mc.player == null || mc.level == null) {
			return;
		}
		float yaw = 0.0F;
		float pitch = 0.0F;
		float scale = 1.0F;
		// GUI 或库存渲染时使用固定缩放，否则基于玩家视角计算旋转
		if (CosmicShaders.cosmicInventoryRender || context == ItemDisplayContext.GUI) {
			scale = 100.0F;
		} else {
			yaw = (float) ((mc.player.getYRot() * 2.0F) * Math.PI / 360.0D);
			pitch = -((float) ((mc.player.getXRot() * 2.0F) * Math.PI / 360.0D));
		}
		// 委托子类设置专属 uniform
		setupShaderUniforms((float) (mc.level.getGameTime() % Integer.MAX_VALUE), yaw, pitch, scale);
		// 获取本类型 RenderType 对应的 VertexConsumer
		VertexConsumer consumer = buffers.getBuffer(getRenderType());
		// 将 mask 纹理转换为图集精灵
		List<TextureAtlasSprite> atlasSprites = new ArrayList<>();
		for (ResourceLocation mask : this.maskSprites) {
			atlasSprites.add(mc.getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(mask));
		}
		mc.getItemRenderer().renderQuadList(poseStack, consumer, WrappedItemModel.bakeItem(atlasSprites), stack, packedLight, packedOverlay);
		// 立即刷新本类型批次，确保 cosmic 层在后续渲染前提交
		if (buffers instanceof MultiBufferSource.BufferSource bufferSource) {
			endBatch(bufferSource);
		}
	}
}
