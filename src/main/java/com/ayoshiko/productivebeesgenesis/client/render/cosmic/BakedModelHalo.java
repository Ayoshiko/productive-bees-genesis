package com.ayoshiko.productivebeesgenesis.client.render.cosmic;

import java.util.List;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * Halo 光晕烘焙模型
 * <br/>
 * 继承 WrappedItemModel，在 GUI 下为基础物品叠加光晕效果。
 * <p>
 * 性能优化：baked quads 按类型缓存，避免每帧重新烘焙。
 * 渲染状态安全：blend/depthTest 在绘制后恢复，脉冲缩放在 pushPose 块内执行。
 */
public class BakedModelHalo extends WrappedItemModel {

	private static final ResourceLocation HALO_TEXTURE = ResourceLocation.fromNamespaceAndPath(ProductiveBeesGenesis.MOD_ID, "item/halo");
	private static final ResourceLocation HALO_NOISE_TEXTURE = ResourceLocation.fromNamespaceAndPath(ProductiveBeesGenesis.MOD_ID, "item/halo_noise");

	private final int type;
	private final float alpha;
	private final boolean pulse;

	/**
	 * 缓存烘焙后的四边形列表（按纹理区分：halo=HALO_TEXTURE，halo_noise=HALO_NOISE_TEXTURE）。
	 * <br/>
	 * type==0 与 type==2 均使用 HALO_TEXTURE，仅渲染时缩放参数不同，因此共用 cachedHaloQuads，
	 * 避免对同一纹理重复烘焙。
	 */
	private static volatile List<BakedQuad> cachedHaloQuads;
	private static volatile List<BakedQuad> cachedHaloNoiseQuads;

	/**
	 * 失效所有 halo 四边形缓存
	 * <br/>
	 * 由 {@link com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesisClient}
	 * 在 {@link net.neoforged.neoforge.client.event.TextureAtlasStitchedEvent} 中调用。
	 * <br/>
	 * 原因：图集重新拼接后，缓存中持有的 TextureAtlasSprite 引用对应的 UV 坐标可能已变化，
	 * 继续使用旧缓存会导致 halo 渲染错位或采样到错误纹理，因此必须在图集重建时清空缓存，
	 * 下一帧渲染时由双重检查锁定重新烘焙。
	 */
	public static void invalidateCache() {
		synchronized (BakedModelHalo.class) {
			cachedHaloQuads = null;
			cachedHaloNoiseQuads = null;
		}
	}

	public BakedModelHalo(BakedModel bakedModel, int type, float alpha, boolean pulse) {
		super(bakedModel);
		this.type = type;
		this.alpha = alpha;
		this.pulse = pulse;
	}

	/**
	 * 获取或烘焙halo纹理的四边形列表（线程安全的双重检查锁定）
	 */
	private static List<BakedQuad> getHaloQuads(TextureAtlas textureAtlas) {
		List<BakedQuad> quads = cachedHaloQuads;
		if (quads == null) {
			synchronized (BakedModelHalo.class) {
				quads = cachedHaloQuads;
				if (quads == null) {
					TextureAtlasSprite sprite = textureAtlas.getSprite(HALO_TEXTURE);
					quads = RenderUtils.bakeItem(sprite);
					cachedHaloQuads = quads;
				}
			}
		}
		return quads;
	}

	private static List<BakedQuad> getHaloNoiseQuads(TextureAtlas textureAtlas) {
		List<BakedQuad> quads = cachedHaloNoiseQuads;
		if (quads == null) {
			synchronized (BakedModelHalo.class) {
				quads = cachedHaloNoiseQuads;
				if (quads == null) {
					TextureAtlasSprite sprite = textureAtlas.getSprite(HALO_NOISE_TEXTURE);
					quads = RenderUtils.bakeItem(sprite);
					cachedHaloNoiseQuads = quads;
				}
			}
		}
		return quads;
	}

	@Override
	public void renderItem(ItemStack stack, ItemDisplayContext context, PoseStack poseStack, MultiBufferSource buffers, int packedLight, int packedOverlay) {
		Minecraft mc = Minecraft.getInstance();
		ItemRenderer itemRenderer = mc.getItemRenderer();
		TextureAtlas textureAtlas = mc.getModelManager().getAtlas(InventoryMenu.BLOCK_ATLAS);

		boolean haloDrawn = false;
		for (BakedModel bakedModel : this.wrapped.getRenderPasses(stack, true)) {
			for (RenderType renderType : bakedModel.getRenderTypes(stack, true)) {
				VertexConsumer vertexConsumer = buffers.getBuffer(renderType);

				// 仅在第一个renderType绘制halo，避免多renderType重复绘制
				if (context == ItemDisplayContext.GUI && !haloDrawn) {
					haloDrawn = true;
					renderHalo(poseStack, vertexConsumer, packedLight, packedOverlay, textureAtlas);
				}

				// 脉冲缩放：包裹renderModelLists，防止缩放累积泄漏到后续迭代
				boolean usePulse = context == ItemDisplayContext.GUI && (this.type == 0 || this.pulse);
				if (usePulse) {
					poseStack.pushPose();
					try {
						// 基于时间的正弦波缩放，替代随机数消除每帧闪烁
						float scale = 1.0F + 0.05F * (float) Math.sin(System.nanoTime() / 300_000_000.0);
						double translate = (1.0D - scale) / 2.0D;
						poseStack.scale(scale, scale, 1.0001F);
						poseStack.translate(translate, translate, 0.0F);
						itemRenderer.renderModelLists(bakedModel, stack, packedLight, packedOverlay, poseStack, vertexConsumer);
					} finally {
						poseStack.popPose();
					}
				} else {
					itemRenderer.renderModelLists(bakedModel, stack, packedLight, packedOverlay, poseStack, vertexConsumer);
				}
			}
		}
	}

	/**
	 * 渲染halo光晕效果
	 * <br/>
	 * 统一处理blend启用/恢复，确保渲染状态不泄漏。
	 * 所有缩放操作都在 pushPose/popPose 块内执行。
	 */
	private void renderHalo(PoseStack poseStack, VertexConsumer vertexConsumer, int packedLight, int packedOverlay, TextureAtlas textureAtlas) {
		boolean blendEnabled = false;
		boolean depthTestDisabled = false;

		try {
			if (this.type == 0) {
				blendEnabled = true;
				depthTestDisabled = true;
				RenderSystem.enableBlend();
				RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
				RenderSystem.disableDepthTest();

				poseStack.pushPose();
				try {
					PoseStack.Pose pose = poseStack.last();
					poseStack.scale(2.25F, 2.25F, 1.0F);
					poseStack.translate(-0.295F, -0.265F, 0.0F);
					List<BakedQuad> quads = getHaloQuads(textureAtlas);
					for (BakedQuad quad : quads) {
						vertexConsumer.putBulkData(pose, quad, 0.0F, 0.0F, 0.0F, this.alpha, packedLight, packedOverlay, true);
					}
				} finally {
					poseStack.popPose();
				}
			} else if (this.type == 1) {
				// type==1 使用不透明噪声纹理（HALO_NOISE_TEXTURE），故不启用 blend、不禁用深度测试，
				// 与 type==0/2 的半透明光晕（HALO_TEXTURE）设计不同：噪声为实体覆盖层，需保留深度测试确保遮挡正确
				poseStack.pushPose();
				try {
					PoseStack.Pose pose = poseStack.last();
					poseStack.scale(2.0F, 2.0F, 1.0F);
					poseStack.translate(-0.25F, -0.255F, 0.0F);
					List<BakedQuad> quads = getHaloNoiseQuads(textureAtlas);
					for (BakedQuad quad : quads) {
						vertexConsumer.putBulkData(pose, quad, 1.0F, 1.0F, 1.0F, this.alpha, packedLight, packedOverlay, true);
					}
				} finally {
					poseStack.popPose();
				}
			} else if (this.type == 2) {
				// type==2 复用 cachedHaloQuads（与 type==0 共用 HALO_TEXTURE），仅缩放参数不同
				blendEnabled = true;
				depthTestDisabled = true;
				RenderSystem.enableBlend();
				RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
				RenderSystem.disableDepthTest();

				poseStack.pushPose();
				try {
					PoseStack.Pose pose = poseStack.last();
					poseStack.scale(1.5F, 1.5F, 1.0F);
					poseStack.translate(-0.17F, -0.155F, 0.0F);
					List<BakedQuad> quads = getHaloQuads(textureAtlas);
					for (BakedQuad quad : quads) {
						vertexConsumer.putBulkData(pose, quad, 0.0F, 0.0F, 0.0F, this.alpha, packedLight, packedOverlay, true);
					}
				} finally {
					poseStack.popPose();
				}
			}
		} finally {
			// 恢复渲染状态，防止影响后续物品渲染
			if (blendEnabled) {
				RenderSystem.defaultBlendFunc();
				RenderSystem.disableBlend();
			}
			if (depthTestDisabled) {
				RenderSystem.enableDepthTest();
			}
		}
	}
}
